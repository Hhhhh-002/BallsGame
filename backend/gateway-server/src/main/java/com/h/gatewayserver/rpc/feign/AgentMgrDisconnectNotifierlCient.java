package com.h.gatewayserver.rpc.feign;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.Excutor.GameExecutorFactory;
import com.h.gamecommon.exception.RetryableDisconnectException;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.proto.PlayerDisconnectedRequest;
import com.h.gameprotocol.proto.PlayerDisconnectedResult;
import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gameprotocol.codec.CommandReceiveLog;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import com.h.gatewayserver.rpc.feign.client.AgentMgrFeignClient;
import feign.FeignException;
import feign.RetryableException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关断线通知器：把已登录客户端的断线事实交给 AgentMgr，并检查最终处理结果。
 * 这里不直接操作 Agent，也不负责保存玩家数据；AgentMgr 才负责核对登录记录、通知 Agent 保存并下线。
 * Feign 是阻塞调用，因此放入专用线程池；Netty 的客户端 EventLoop 只负责发起异步流程。
 */
@Component
@Slf4j
public class AgentMgrDisconnectNotifierlCient {
    /** 按 Nacos 服务名调用 AgentMgr 的 Feign 客户端。 */
    private final AgentMgrFeignClient client;
    /** 专供断线通知使用的有界线程池，避免与登录 RPC 相互占用线程。 */
    private final ThreadPoolExecutor executor;
    /** Reactor 将阻塞的 Feign 调用调度到上述线程池。 */
    private final Scheduler scheduler;
    /** 单次尝试的最大等待时间，不是所有重试的总耗时。 */
    private final Duration timeout;
    /** 第一次重试前的基础退避时间。 */
    private final Duration initialBackoff;
    /** 指数退避的最大等待时间。 */
    private final Duration maxBackoff;
    /** 总尝试次数，包含第一次发送。 */
    private final int maxAttempts;
    /** 生成服务间 request_id；仅用于匹配一次请求及响应，不代替业务幂等键。 */
    private final AtomicLong requestIds = new AtomicLong();
    /** 同一 Actor 的在途断线通知只保留一条重试链。 */
    private final java.util.Set<String> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 从网关配置读取超时、重试次数和线程池容量，并创建独立的 Feign 工作线程池。 */
    public AgentMgrDisconnectNotifierlCient(AgentMgrFeignClient client,
                                            @Value("${gateway.disconnect.timeout-ms:35000}") long timeoutMs,
                                            @Value("${gateway.disconnect.max-attempts:3}") int maxAttempts,
                                            @Value("${gateway.disconnect.initial-backoff-ms:200}") long initialBackoffMs,
                                            @Value("${gateway.disconnect.max-backoff-ms:2000}") long maxBackoffMs,
                                            @Value("${gateway.disconnect.feign-threads:8}") int threads,
                                            @Value("${gateway.disconnect.feign-queue:512}") int queueCapacity) {
        // 启动时拒绝不合法的参数，避免运行期间出现负时间或无效的重试次数。
        if (timeoutMs <= 0 || maxAttempts <= 0 || initialBackoffMs <= 0
                || maxBackoffMs < initialBackoffMs) {
            throw new IllegalArgumentException("Gateway 断线通知的超时或重试参数不合法");
        }
        this.client = client;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.initialBackoff = Duration.ofMillis(initialBackoffMs);
        this.maxBackoff = Duration.ofMillis(maxBackoffMs);
        this.maxAttempts = maxAttempts;
        // 与 Login Feign 隔离，断线保存耗时不会占用登录请求的工作线程。
        this.executor = GameExecutorFactory.newFixedThreadPool(
                "client-disconnect-feign", threads, queueCapacity);
        // Scheduler 只包装这个线程池，不会让 Feign 在 Netty EventLoop 上执行。
        this.scheduler = Schedulers.fromExecutorService(executor);
    }

    /**
     * 从 Netty channelInactive 调用；只构造请求并订阅，Feign 和延迟重试都不在客户端 EventLoop 上执行。
     * 只持有 userId 和本次登录独有的 playerActorId，不保留已断开的 Channel 或 GatewayContext。
     */
    public void notifyDisconnected(String userId, String playerActorId) {
        String key = userId + ":" + playerActorId;
        if (!pending.add(key)) return;
        // AgentMgr 已保存用户到 Agent/Actor 的权威关联；按 userId 查找并核对本次登录的 Actor ID。
        // 同一个断线操作在每次重试中复用该 body，避免旧连接通知误下线新登录。
        PlayerDisconnectedRequest body = PlayerDisconnectedRequest.newBuilder()
                .setUserId(userId)
                .setPlayerActorId(playerActorId)
                .build();
        // 外层 ServiceRequest 的 cmd 决定 AgentMgr 使用哪个 Handler，body 再由该 Handler 解析。
        // 一次断线只生成一个 request_id；它用于校验回包，业务幂等依赖 userId + playerActorId。
        ServiceEnvelope request = ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder()
                .setCmd(ServiceCommands.PLAYER_DISCONNECTED.code())
                .setRequestId(requestIds.incrementAndGet())
                .setBody(body.toByteString())).build();

        // defer 使每次订阅（包括重试）都重新执行一次 Feign 调用，但始终发送上面同一个 request。
//        / fromCallable：将【有返回值、可能抛出异常的同步阻塞方法】包装成惰性Mono
//// 特点：只有Mono被订阅时，才会执行里面的 dispatchAndValidate(request)
//// dispatchAndValidate：同步阻塞调用，内部是Feign远程调用AgentMgr，通知玩家断线
//// 阻塞调用不能跑在Netty EventLoop，后续通过 subscribeOn 调度到独立线程池执行
        Mono<PlayerDisconnectedResult.Outcome> call = Mono.defer(() -> Mono.fromCallable(
                        () -> dispatchAndValidate(request))
                //`subscribeOn(scheduler)`：把这个 Mono 的执行，**调度到我们专门准备的线程池 scheduler**。
                //不管是谁订阅这个流，实际执行 `dispatchAndValidate` 的线程都来自这个 scheduler。
                .subscribeOn(scheduler)
                .timeout(timeout));
        // Reactor 的 retry 次数不含首次发送，因此使用 maxAttempts - 1。
        if (maxAttempts > 1) {
            // 指数退避并加入少量随机抖动，避免多条连接同时断开后整批重试再次撞上 AgentMgr。
            // filter 只允许临时性故障重试；明确成功、旧连接、协议错误都不会重试。
            call = call.retryWhen(Retry.backoff(maxAttempts - 1, initialBackoff)
                    .maxBackoff(maxBackoff)
                    .jitter(0.2)
                    .filter(this::isRetryable)
                    .doBeforeRetry(signal -> log.warn(
                            "断线通知临时失败，准备第 {} 次尝试, userId={}, actorId={}",
                            signal.totalRetries() + 2, userId, playerActorId, signal.failure())));
        }
        // subscribe 才真正启动调用；它立即返回，后续 Feign 和重试由异步流程处理。
        // 客户端 Channel 已断开，最终结果只用于日志/告警，不写回旧 Channel 或修改新的登录路由。
        call.doFinally(signal -> pending.remove(key)).subscribe(outcome -> log.info(
                        "AgentMgr 断线处理结束, userId={}, actorId={}, outcome={}",
                        userId, playerActorId, outcome),
                failure -> log.error(
                        "AgentMgr 断线处理未成功，重试已结束或错误不可重试, userId={}, actorId={}",
                        userId, playerActorId, failure));
    }

    /**
     * 在线程池中执行 Feign 调用，并校验服务间响应。
     * 只有保存完成、此前已经完成或旧连接已被忽略，才算拿到了 AgentMgr 的最终结果。
     */
    private PlayerDisconnectedResult.Outcome dispatchAndValidate(ServiceEnvelope request) throws IOException {
        // Feign 发起阻塞 HTTP 请求；AgentMgr 应在 Agent 保存并下线后才返回成功。
        byte[] bytes = client.dispatch(request.toByteArray());
        // 空响应无法判断远端是否已处理，允许使用同一幂等标识重试。
        if (bytes == null || bytes.length == 0) {
            throw new RetryableDisconnectException("AgentMgr 返回空响应，处理结果未知");
        }
        // 先解析统一信封，再确认收到的是响应而不是请求或主动事件。
        ServiceEnvelope envelope = ServiceEnvelope.parseFrom(bytes);
        CommandReceiveLog.service("gateway-server","AgentMgr HTTP",envelope);
        if (!envelope.hasResponse()) {
            throw new IllegalStateException("AgentMgr 未返回 ServiceResponse");
        }
        ServiceResponse response = envelope.getResponse();
        // cmd/request_id 必须与这次断线请求一致，否则可能错把其他调用的响应当成结果。
        if (response.getCmd() != request.getRequest().getCmd()
                || response.getRequestId() != request.getRequest().getRequestId()) {
            throw new IllegalStateException("AgentMgr 断线响应 cmd/requestId 不匹配");
        }
        // code=0 才解析最终结果；临时性业务错误可重试，其他错误作为最终失败报告。
        if (response.getCode() != 0) {
            if (isTemporaryCode(response.getCode())) {
                throw new RetryableDisconnectException(
                        "AgentMgr 暂时无法完成断线处理, code=" + response.getCode());
            }
            throw new IllegalStateException(
                    "AgentMgr 明确拒绝断线处理, code=" + response.getCode()
                    + ", message=" + response.getMessage());
        }
        // 成功响应必须明确给出最终状态；不能把“正在处理”或缺失的状态算作成功。
        PlayerDisconnectedResult result = PlayerDisconnectedResult.parseFrom(response.getBody());
        return switch (result.getOutcome()) {
            case SAVED_AND_OFFLINE, ALREADY_FINISHED, STALE_IGNORED -> result.getOutcome();
            default -> throw new IllegalStateException("AgentMgr 未给出有效的最终断线结果");
        };
    }

    /** HTTP 暂时不可用、超时、线程池暂满及业务 5xx/5xxx 结果才允许有限重试。 */
    private boolean isRetryable(Throwable failure) {
        // 去掉 Reactor 的包装异常后，按实际失败类型决定是否再次发送同一请求。
        //`Exceptions.unwrap(failure)`：Reactor 里异常经常会被包装（比如 `RuntimeException` 套一层原始异常），
        // `unwrap` 递归剥开外层包装，拿到**最底层真实根因异常**，方便后面判断类型。
        Throwable cause = Exceptions.unwrap(failure);
        // 业务临时失败、Feign 可重试故障、单次超时或本机线程池暂满都可能恢复。
        /**
         * 满足下面任意一种，**允许重试**：
         * 1. `RetryableDisconnectException`：自定义异常，断线通知场景里标记为临时可重试的业务异常
         * 2. `RetryableException`：Feign 或内部 RPC 标记的可重试异常
         * 3. `TimeoutException`：调用超时，网络临时抖动，有可能下一次成功
         * 4. `RejectedExecutionException`：线程池满了，任务被拒绝。属于瞬时负载过高，等一会重试有概率成功
         */
        if (cause instanceof RetryableDisconnectException || cause instanceof RetryableException
                || cause instanceof TimeoutException || cause instanceof RejectedExecutionException) {
            return true;
        }
        // HTTP 408/429/5xx 是暂时性结果；其他 4xx 通常是参数或接口错误，不盲目重试。
        if (cause instanceof FeignException feignException) {
            int status = feignException.status();
            return status == 408 || status == 429 || status >= 500 && status < 600;
        }
        // Protobuf 解码失败属于协议错误，重复发送同样的请求无法修复。
        return cause instanceof IOException && !(cause instanceof InvalidProtocolBufferException);
    }

    /** 兼容 HTTP 风格的 5xx 与游戏服务现有 5xxx 风格的临时错误码。 */
    private boolean isTemporaryCode(int code) {
        return code >= 500 && code < 600 || code >= 5000 && code < 6000;
    }

    /** 停止网关时回收专用线程池；未完成的内存中重试不会跨进程恢复。 */
    @PreDestroy
    public void shutdown() {
        // 停止后不再接受新的异步调用；仅在内存中的重试不会跨网关进程恢复。
        scheduler.dispose();
        executor.shutdown();
    }

}
