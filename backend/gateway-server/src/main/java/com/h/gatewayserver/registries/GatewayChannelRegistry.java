package com.h.gatewayserver.registries;

import com.h.gameprotocol.proto.ServerEnvelope;
import com.h.gatewayserver.context.GatewayContext;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 本机 userId -> GatewayContext 反向索引；Channel 只保存在 GatewayContext。 */
@Component
@Slf4j
public class GatewayChannelRegistry {
    private final ConcurrentMap<String, GatewayContext> contexts = new ConcurrentHashMap<>();
    public void beginLogin(String operationId, long deadline, GatewayContext context) {
        context.setLoginOperationId(operationId);
        context.setLoginDeadlineMs(deadline);
    }

    /** 在原客户端 EventLoop 清理登录中的状态。 */
    public void endLogin(GatewayContext context) {
        context.setLoginOperationId("");
        context.setLoginDeadlineMs(0);
    }
    /** 重复踢人请求共用关闭结果；属性存在也表示该客户端连接不再接收业务请求。 */
    private static final AttributeKey<CompletableFuture<KickOutcome>> KICK_RESULT =
            AttributeKey.valueOf("BALLS_GATEWAY_KICK_RESULT");

    /** 告诉 AgentMgr 的只是网关本机操作结果，不表示客户端应用一定展示了提示。 */
    public enum KickOutcome {
        CONNECTION_CLOSED,
        ALREADY_OFFLINE,
        STALE_IGNORED
    }

    /** 仅在 Login 成功并确认 Agent 路由后登记当前连接。 */
    public void bind(String userId, GatewayContext context) {
        if (userId == null || userId.isBlank() || context == null || !context.getChannel().isActive()) {
            throw new IllegalArgumentException("无法绑定无效的用户或连接");
        }
        contexts.put(userId, context);
    }

    /** 比较并删除，避免旧连接晚到的断开事件误删新连接。 */
    public void unbind(String userId, GatewayContext context) {
        if (userId != null && !userId.isBlank() && context != null) {
            /**
             * 只有满足两个条件才会删除：
             *
             * 1. map 中存在 key = `userId`
             * 2. map 中该 key 对应的 value **引用等于** `context`
             * 全部匹配成功 → 删除 entry，返回 `true`；
             * 只要任意一条不满足 → 不删除，返回 `false`。
             */
            contexts.remove(userId, context);
        }
    }

    /**
     * 客户端消息入口在对应 EventLoop 上检查此标记，不阻塞等待关闭结果。
     * 标记在踢人任务校验通过后设置，不会拦截任务尚未执行前已经分发的请求。
     */
    public boolean isKicking(Channel channel) {
        return channel.attr(KICK_RESULT).get() != null;
    }

    /**
     * 按现有 userId 索引找旧连接，再在该 Channel 的 EventLoop 核对本次登录的 Actor ID。
     * 先写客户端通知，随后关闭连接；关闭 Future 完成后才向 AgentMgr 报告本机处理结果。
     */
    public Mono<KickOutcome> kick(String userId, String playerActorId, ServerEnvelope notice) {
        return Mono.defer(() -> {
            GatewayContext target = contexts.get(userId);
            if (target == null) {
                return Mono.just(KickOutcome.ALREADY_OFFLINE);
            }
            Channel channel = target.getChannel();
            CompletableFuture<KickOutcome> result = new CompletableFuture<>();
            try {
                // 提交到该客户端 Channel 所属的 EventLoop，与本连接的消息处理串行执行。
                // EventLoop 可以管理多个 Channel；这不替代对全局路由及旧 Actor 的校验。
                channel.eventLoop().execute(() -> {
                    // 排队期间用户路由可能已变化，绝不能按旧快照关闭新连接。
                    //外层拿到的 `target` 只是一个旧快照。投递任务到 EventLoop 的这段间隙，用户可能已经重连、换了新 Channel。
                    GatewayContext current = contexts.get(userId);
                    if (current != target) {
                        result.complete(current == null ? KickOutcome.ALREADY_OFFLINE : KickOutcome.STALE_IGNORED);
                        return;
                    }
                    //channel 已经关闭；或者 Channel 上绑定的 GatewayContext 已经不是 target
                    if (!channel.isActive() || channel.attr(GatewayContext.KEY).get() != target) {
                        contexts.remove(userId, target);
                        result.complete(KickOutcome.ALREADY_OFFLINE);
                        return;
                    }
                    //AgentMgr 下发踢人指令带了 playerActorId，和当前连接上的 actorId 不一致 → 陈旧请求，忽略。
                    if (!playerActorId.equals(target.getPlayerActorId())) {
                        result.complete(KickOutcome.STALE_IGNORED);
                        return;
                    }
                    // 同一连接的重试等待第一次关闭完成，不重复推送或关闭。
                    CompletableFuture<KickOutcome> pending = channel.attr(KICK_RESULT).get();
                    if (pending != null) {
                        pending.whenComplete((outcome, failure) -> {
                            if (failure != null) result.completeExceptionally(failure);
                            else result.complete(outcome);
                        });
                        return;
                    }
                    //- 如果已经有 pending（正在踢）：**复用上次踢人 Future，等待上次结果，不重复执行写包、关闭**。
                    //- 如果没有 pending：把当前 result 挂到 channel 属性上，标记踢人流程开始。
                    //
                    //>
                    //> 作用：同一个连接，并发收到多条踢人指令，只执行一次关闭，其余请求等待同一个结果。
                    // 先禁止后续客户端业务消息分发，再发提示、关闭连接，不依赖客户端停止发送。
                    channel.attr(KICK_RESULT).set(result);
                    //**给当前Channel注册回调  channel真正关闭成功之后才会回调**。
                    channel.closeFuture().addListener(future -> result.complete(KickOutcome.CONNECTION_CLOSED));
                    try {
                        channel.writeAndFlush(notice.toByteArray()).addListener(future -> {
                            if (!future.isSuccess()) {
                                log.warn("踢人提示写入失败，仍关闭旧客户端连接, userId={}, actorId={}",
                                        userId, playerActorId, future.cause());
                            }
                            channel.close();
                        });
                    } catch (RuntimeException exception) {
                        // 即使无法写出提示，旧连接也必须关闭；closeFuture 会完成响应。
                        log.warn("踢人提示发送异常，仍关闭旧客户端连接, userId={}, actorId={}",
                                userId, playerActorId, exception);
                        channel.close();
                    }
                });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
            // AgentMgr 若因 HTTP 超时取消等待，也不能取消正在关闭的共享 Channel 结果。
            return Mono.fromFuture(result, true);
        });
    }

    /** Netty 允许跨线程提交写操作；真正的写入会由目标 Channel 的 EventLoop 执行。 */
    public boolean push(String userId, String serviceName, String instanceId, ServerEnvelope message) {
        GatewayContext context = contexts.get(userId);
        if (context == null || !context.getChannel().isActive()
                || context.getChannel().attr(GatewayContext.KEY).get() != context
                || !serviceName.equals(context.getAgentHostServiceName())
                || !instanceId.equals(context.getAgentHostInstanceId())) {
            return false;
        }
        context.getChannel().writeAndFlush(message.toByteArray());
        return true;
    }
}
