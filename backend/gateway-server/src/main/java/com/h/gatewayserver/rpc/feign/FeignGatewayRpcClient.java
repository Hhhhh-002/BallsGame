package com.h.gatewayserver.rpc.feign;

import com.h.gamecommon.Excutor.GameExecutorFactory;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.exception.LoginServiceException;
import com.h.gamecommon.rpc.BoundedRpcRetry;
import com.h.gameprotocol.proto.GatewayLoginRequest;
import feign.FeignException;
import feign.Request;
import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gameprotocol.proto.ServiceResponse;
import com.h.gameprotocol.codec.CommandReceiveLog;
import com.h.gatewayserver.rpc.GatewayRpcClient;
import com.h.gatewayserver.rpc.feign.client.LoginFeignClient;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 仅负责 Gateway -> Login 的阻塞式 Feign RPC，绝不在客户端 EventLoop 上执行。 */
@Component
public class FeignGatewayRpcClient implements GatewayRpcClient {
    private final LoginFeignClient loginClient;
    private final ThreadPoolExecutor executor;
    private final Scheduler scheduler;
    private final Duration timeout;
    private final Duration registerTimeout;
    private final int maxAttempts;
    /** Feign 配置的单次上限；动态 Options 只能缩短它，不能忽略 Nacos 配置。 */
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public FeignGatewayRpcClient(LoginFeignClient loginClient,
                                 @Value("${gateway.rpc.timeout-ms:90000}") long timeoutMs,
                                 @Value("${gateway.rpc.register-timeout-ms:10000}") long registerTimeoutMs,
                                 @Value("${gateway.rpc.feign-threads:16}") int threads,
                                 @Value("${gateway.rpc.feign-queue-per-thread:64}") int queuedPerThread,
                                 @Value("${gateway.rpc.max-attempts:3}") int maxAttempts,
                                 @Value("${spring.cloud.openfeign.client.config.login-server.connect-timeout:${spring.cloud.openfeign.client.config.login-server.connectTimeout:1000}}") int connectTimeoutMs,
                                 @Value("${spring.cloud.openfeign.client.config.login-server.read-timeout:${spring.cloud.openfeign.client.config.login-server.readTimeout:85000}}") int readTimeoutMs) {
        if (timeoutMs <= 0 || registerTimeoutMs <= 0 || maxAttempts < 1 || connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Gateway 登录 RPC 参数不合法");
        }
        this.loginClient = loginClient;
        this.executor = GameExecutorFactory.newFixedThreadPool(
                "gateway-login-feign", threads, Math.multiplyExact(threads, queuedPerThread));
        this.scheduler = Schedulers.fromExecutorService(executor);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.registerTimeout = Duration.ofMillis(registerTimeoutMs);
        this.maxAttempts = maxAttempts;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** 将 Protobuf 服务间信封发给 Login，并校验响应命令及调用关联 ID。 */
    @Override
    public Mono<ServiceResponse> login(ServiceEnvelope request) {
        if (!request.hasRequest()
                || request.getRequest().getCmd() != ServiceCommands.AUTH_LOGIN.code()) {
            return Mono.error(new IllegalArgumentException("Login RPC 需要 AUTH_LOGIN 请求"));
        }
        return Mono.fromCallable(() -> {
                    GatewayLoginRequest login = GatewayLoginRequest.parseFrom(request.getRequest().getBody());
                    // 外层 Mono 的 timeout 和 body 的 deadline 一起限制排队、HTTP 与重试总预算。
                    return BoundedRpcRetry.execute(() -> send(request, login.getLoginDeadlineMs()),
                            failure -> failure instanceof LoginServiceException business
                                    && business.getError() == GameError.SERVICE_UNAVAILABLE,
                            maxAttempts, 200, 2000);
                })
                .subscribeOn(scheduler)
                .timeout(timeout);
    }

    /**
     * 注册共用有界工作池，包含排队的预算默认 10 秒；不在 EventLoop 做 Feign 或 BCrypt。
     * 注册没有业务操作 ID，禁止自动重放：首次 INSERT 可能成功但回包丢失，重发会变成邮箱已注册。
     * 这不改变已有登录和下线的业务重试机制；主键保证注册不覆盖、不重复创建账号。
     */
    @Override
    public Mono<ServiceResponse> register(ServiceEnvelope request) {
        if (!request.hasRequest() || request.getRequest().getCmd() != ServiceCommands.REGISTER_ACCOUNT.code()) {
            return Mono.error(new IllegalArgumentException("注册 RPC 需要 REGISTER_ACCOUNT 请求"));
        }
        long deadline = System.currentTimeMillis() + registerTimeout.toMillis();
        return Mono.fromCallable(() -> send(request, deadline))
                .subscribeOn(scheduler).timeout(registerTimeout);
    }

    /** Feign 在工作线程执行；调用方控制是否重试，本方法只发送一次并校验回包。 */
    private ServiceResponse send(ServiceEnvelope request, long deadline) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) throw new LoginServiceException(GameError.SERVICE_UNAVAILABLE);
        try {
            byte[] responseBytes = loginClient.dispatch(request.toByteArray(),
                    new Request.Options(Math.min(connectTimeoutMs, remaining), TimeUnit.MILLISECONDS,
                            Math.min(readTimeoutMs, remaining), TimeUnit.MILLISECONDS, false));
            if (responseBytes == null || responseBytes.length == 0) {
                throw new LoginServiceException(GameError.SERVICE_UNAVAILABLE);
            }
            ServiceEnvelope envelope = ServiceEnvelope.parseFrom(responseBytes);
            CommandReceiveLog.service("gateway-server","Login HTTP",envelope);
            if (!envelope.hasResponse()) {
                throw new IllegalStateException("Login 未返回 ServiceResponse");
            }
            ServiceResponse response = envelope.getResponse();
            if (response.getCmd() != request.getRequest().getCmd()
                    || response.getRequestId() != request.getRequest().getRequestId()) {
                throw new IllegalStateException("Login 响应 cmd/requestId 不匹配");
            }
            if (response.getCode() == GameError.SERVICE_UNAVAILABLE.code()) {
                throw new LoginServiceException(GameError.SERVICE_UNAVAILABLE);
            }
            return response;
        } catch (FeignException http) {
            int status = http.status();
            throw new LoginServiceException(status < 0 || status == 408 || status == 429
                    || status >= 500 && status < 600 ? GameError.SERVICE_UNAVAILABLE : GameError.INTERNAL_ERROR);
        } catch (com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalStateException("Login 返回的 Protobuf 格式错误", invalid);
        }
    }

    /** 关闭时回收仅供 Login RPC 使用的线程。 */
    @PreDestroy
    public void shutdown() {
        scheduler.dispose();
        executor.shutdown();
    }
}
