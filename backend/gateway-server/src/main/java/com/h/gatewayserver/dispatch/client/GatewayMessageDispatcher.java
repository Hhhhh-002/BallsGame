package com.h.gatewayserver.dispatch.client;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.exception.GatewayDispatchException;
import com.h.gamecommon.code.GameError;
import com.h.gameprotocol.codec.decode.ClientMessageDecoder;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.codec.CommandReceiveLog;
import com.h.gameprotocol.proto.ClientEnvelope;
import com.h.gamecommon.exception.InvalidCommandBodyException;
import com.h.gamecommon.exception.UnknownCommandException;
import com.h.gameprotocol.proto.ServerEnvelope;
import com.h.gatewayserver.registries.GatewayCommandRegistry;
import com.h.gatewayserver.context.GatewayContext;
import com.h.gatewayserver.dispatch.client.result.GatewayHandlerResult;
import com.h.gatewayserver.dispatch.client.handlers.GatewayCommandHandler;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Signal;

/** 负责 Protobuf 解码、cmd 查表、并发限制、异步结果回写。 */
@Component
@Slf4j
public class GatewayMessageDispatcher {
    private final ClientMessageDecoder decoder = new ClientMessageDecoder();
    private final GatewayCommandRegistry registry;
    private final int maxInFlight;

    public GatewayMessageDispatcher(GatewayCommandRegistry registry,
                                    @Value("${gateway.rpc.max-in-flight-per-channel:32}") int maxInFlight) {
        this.registry = registry;
        this.maxInFlight = maxInFlight;//同一个客户端到网关的一个连接最大请求数 ，限流
    }

    /** 在当前 Channel 的 EventLoop 中调用；不做阻塞网络调用。 */
    public void dispatch(ChannelHandlerContext ctx, GatewayContext context, byte[] bytes) {
        // Protocol 模块负责 cmd -> 请求类型；本类只负责 cmd -> Spring Handler。
        DecodedMessage<ClientEnvelope, GameCommands> message;
        try {
            ClientEnvelope envelope=ClientEnvelope.parseFrom(bytes);
            CommandReceiveLog.game("gateway-server","Client WS",envelope);
            message = decoder.decode(envelope);
        } catch (UnknownCommandException exception) {
            writeError(ctx, exception.getCmd(), exception.getRequestId(), GameError.UNKNOWN_COMMAND);
            return;
        } catch (InvalidCommandBodyException exception) {
            writeError(ctx, exception.getCmd(), exception.getRequestId(), GameError.INVALID_MESSAGE);
            return;
        } catch (InvalidProtocolBufferException exception) {
            CommandReceiveLog.unreadable("gateway-server","Client WS");
            // 外层信封损坏，此时无法可靠读取 cmd/requestId。
            writeError(ctx, 0, 0, GameError.INVALID_MESSAGE);
            return;
        }

        int cmd = message.envelope().getCmd();
        long requestId = message.envelope().getRequestId();
        GatewayCommandHandler handler = registry.get(message.command());
        if (handler == null) {
            writeError(ctx, cmd, requestId, GameError.UNKNOWN_COMMAND);
            return;
        }
        if (context.getInFlightRequests() >= maxInFlight) {
            writeError(ctx, cmd, requestId, GameError.SERVICE_UNAVAILABLE);
            return;
        }

        try {
//            创建发起连接的Mono流流程  返回结果是Mono<GatewayHandlerResult>
            var result = handler.handle(context, message);
            // 防止单个客户端无限积压未完成 RPC。
            context.setInFlightRequests(context.getInFlightRequests() + 1);
            // materialize 把响应、无返回值的单向发送、错误统一变成一次完成信号。
//            `materialize()` 将 Mono 的所有终止事件（onError /onComplete/onNext）统一封装成 `Signal` 对象，
//            传入 `handleSignal`。
//
//          订阅Mono流，**这里才真正启动转发消息给Agent的逻辑**
//// 转发发包动作：执行在Agent Channel的EventLoop，不是客户端Channel的EventLoop

            result.materialize().subscribe(
//                 / signal回调函数：运行在Agent Channel的EventLoop线程
//    // 通过ctx.executor().execute()，把收尾任务投递到【客户端Channel】EventLoop执行
                    signal -> ctx.executor().execute(() -> {
                        // 投递完成后，下面代码跑在客户端Channel EventLoop
                        // 当异步结果回调到达时，如果客户端连接已断开/上下文不匹配，则直接放弃本次收尾，不处理
//       
                if (!stillConnected(ctx, context)) {
                    handler.onDiscarded(context, signal.hasValue() ? signal.get() : null);
                    return;
                }
                context.setInFlightRequests(context.getInFlightRequests() - 1);
                handleSignal(ctx, context, message, handler, signal);
            }));
        } catch (GatewayDispatchException exception) {
            writeError(ctx, cmd, requestId, exception.getError());
        } catch (Exception exception) {
            handler.onFailure(context);
            log.error("网关分发失败, cmd={}, requestId={}", cmd, requestId, exception);
            writeError(ctx, cmd, requestId, GameError.INTERNAL_ERROR);
        }
    }

    private boolean stillConnected(ChannelHandlerContext ctx, GatewayContext context) {
        // 旧请求完成时，连接可能早已断开或 Channel 上的上下文已被清理。
        return ctx.channel().isActive() && ctx.channel().attr(GatewayContext.KEY).get() == context;
    }

    private void handleSignal(ChannelHandlerContext ctx, GatewayContext context,
                              DecodedMessage<ClientEnvelope, GameCommands> request, GatewayCommandHandler handler,
                              Signal<GatewayHandlerResult> signal) {
        int cmd = request.envelope().getCmd();
        long requestId = request.envelope().getRequestId();
        if (signal.isOnError()) {
            handler.onFailure(context);
            log.warn("下游网关请求失败, cmd={}, requestId={}", cmd, requestId, signal.getThrowable());
            writeError(ctx, cmd, requestId, GameError.SERVICE_UNAVAILABLE);
            return;
        }
        if (!signal.hasValue()) {
            // Agent 单向转发成功后没有同步客户端响应，等待它的主动推送。
            return;
        }
        try {
            GatewayHandlerResult response = signal.get();//取出 GatewayHandlerResult 响应对象；
            validateResponse(request, response);//校验应答包的 cmd、requestId，和原始请求是否匹配，防止消息串包；校验失败会抛异常；
            handler.onResponse(ctx, context, response);//回送响应给客户端
        } catch (Exception exception) {
            handler.onFailure(context);
            log.warn("下游网关响应无效, cmd={}, requestId={}", cmd, requestId, exception);
            writeError(ctx, cmd, requestId, GameError.INTERNAL_ERROR);
        }
    }

    private void validateResponse(DecodedMessage<ClientEnvelope, GameCommands> request, GatewayHandlerResult response) {
        // 禁止把错位的 RPC 响应发给客户端或绑定到错误的登录请求。
        if (response == null || response.clientResponse() == null
                || response.clientResponse().getCmd() != request.envelope().getCmd()
                || response.clientResponse().getRequestId() != request.envelope().getRequestId()) {
            throw new IllegalStateException("下游网关响应 cmd/requestId 与请求不匹配");
        }
    }

    /** 使用统一响应信封回传错误，保留能解析出的 cmd/requestId。 */
    public void writeError(ChannelHandlerContext ctx, int cmd, long requestId, GameError error) {
        ServerEnvelope response = ServerEnvelope.newBuilder()
                .setCmd(cmd)
                .setRequestId(requestId)
                .setCode(error.code())
                .setMessage(error.message())
                .build();
        ctx.writeAndFlush(response.toByteArray());
    }
}
