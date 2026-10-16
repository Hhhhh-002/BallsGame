package com.h.gatewayserver.netty.handler;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gameprotocol.codec.LinkKey;
import com.h.gameprotocol.codec.CommandReceiveLog;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.proto.ForwardToClientEvent;
import com.h.gameprotocol.proto.GatewayAgentLinkHello;
import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gatewayserver.registries.GatewayChannelRegistry;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 一条 Gateway -> Agent WebSocket 连接的握手、回程推送和断线事件处理器。 */
@Slf4j
public class AgentLinkHandler extends SimpleChannelInboundHandler<Object> {
    private final LinkKey key;
    private final CompletableFuture<Channel> ready;
    private final WebSocketClientHandshaker handshaker;
    private final String gatewayInstanceId;
    private final GatewayChannelRegistry channels;
    private final Consumer<Throwable> onFailure;
    private final Runnable onDisconnected;

    /** 每条内部连接单独创建一个 Handler；Manager 负责连接缓存和失败后的清理。 */
    public AgentLinkHandler(LinkKey key, CompletableFuture<Channel> ready,
                            WebSocketClientHandshaker handshaker, String gatewayInstanceId,
                            GatewayChannelRegistry channels, Consumer<Throwable> onFailure,
                             Runnable onDisconnected) {
        this.key = key;
        this.ready = ready;
        this.handshaker = handshaker;
        this.gatewayInstanceId = gatewayInstanceId;
        this.channels = channels;
        this.onFailure = onFailure;
        this.onDisconnected = onDisconnected;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // TCP/WebSocket/服务握手必须在限定时间内完成，（这里是业务层的自定义握手）避免缓存永久待完成的 Future。
        ctx.executor().schedule(() -> {
//            延迟3s后在执行
            if (!ready.isDone()) {
                onFailure.accept(new IllegalStateException("Agent 长连接握手超时"));
                ctx.close();
            }
        }, 3, TimeUnit.SECONDS);
        handshaker.handshake(ctx.channel());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object incoming) throws Exception {
        /**
         * handshaker.isHandshakeComplete()  // false：握手未完成
         * !handshaker.isHandshakeComplete() // true：进入处理握手响应的分支
         */
        if (!handshaker.isHandshakeComplete()) {
            if (!(incoming instanceof FullHttpResponse response)) {
                throw new IllegalStateException("Agent WebSocket 握手响应错误");
            }
            handshaker.finishHandshake(ctx.channel(), response);//w收到FullHttpResponse后完成协议升级
            ServiceEnvelope hello = ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder()
                    .setCmd(ServiceCommands.AGENT_LINK_HELLO.code())
                    .setRequestId(1)
                    .setBody(GatewayAgentLinkHello.newBuilder()
                            .setGatewayInstanceId(gatewayInstanceId).build().toByteString())).build();
//            业务消息 → Protobuf 字节 → Netty 缓冲区 → WebSocket 二进制帧
            ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(hello.toByteArray())))
                    .addListener(future -> {
//                        监听通道写消息是否成功，失败就关闭连接
                        if (!future.isSuccess()) {
                            onFailure.accept(future.cause());
                            ctx.close();
                        }
                    });
            return;
        }
//        Agent 主动下发 WebSocket 关闭帧（标准 WS 协议帧）。
//
//- 收到这个帧：网关直接关闭当前 Channel `ctx.close()`，return 不再继续处理。
        if (incoming instanceof CloseWebSocketFrame) {
            ctx.close();
            return;
        }
        // 内部长连接心跳：手动握手 Handler 需要自行回复 Ping，避免空闲玩家导致整条共享连接断开。
        if (incoming instanceof PingWebSocketFrame ping) {
            ctx.writeAndFlush(new PongWebSocketFrame(ping.content().retain()));
            return;
        }

        /**
         * WebSocket 帧有多种：Text、Binary、Ping、Pong、Close。
         * **网关和 Agent 内部约定只用 Binary 二进制帧传输 Protobuf 消息**。
         * 文本帧、ping/pong 帧直接忽略，return 丢弃不处理。
         */
        if (!(incoming instanceof BinaryWebSocketFrame frame)) {
            return;
        }
//读取二进制字节，反序列化成外层协议包 ServiceEnvelope
        byte[] bytes = new byte[frame.content().readableBytes()];
        frame.content().readBytes(bytes);
        ServiceEnvelope envelope = ServiceEnvelope.parseFrom(bytes);
        CommandReceiveLog.service("gateway-server","Agent WS",envelope);
//        接收的是业务握手响应信息
        if (envelope.hasResponse()
                && envelope.getResponse().getCmd() == ServiceCommands.AGENT_LINK_HELLO.code()) {
            if (envelope.getResponse().getRequestId() != 1
                    || envelope.getResponse().getCode() != 0) {
                throw new IllegalStateException("Agent 拒绝长连接握手");
            }
//            channel就绪后存入当前key的CompletableFuture<Channel> 作用是标记连接好了标记 “这件事好了” 的信号。
//            `ready.complete(ctx.channel())`：**完成这个 Future，
//            把当前 Netty 的 Channel 对象作为成功结果，唤醒所有正在等待 `ready` 的代码**。
//            `CompletableFuture` 内部维护一个回调链表。
//
//            1. 调用 `future.whenComplete(回调A)` → 把回调 A 挂到链表；
//            2. 调用 `future.thenAccept(回调B)` → 把回调 B 挂到链表；
//            3. 调用 `future.complete(channel)`：
//            - 标记 Future 状态为【成功完成】，保存结果；
//            - **遍历链表，挨个执行所有挂上去的回调 A、回调 B**；
//            4. 如果**先 complete，后注册回调 C**：注册的一瞬间，回调 C 马上同步执行。
            ready.complete(ctx.channel());
        } else if (ready.isDone() && envelope.hasEvent()
//                接收到的是agent推送信息
                && envelope.getEvent().getCmd() == ServiceCommands.FORWARD_TO_CLIENT.code()) {
            ForwardToClientEvent event = ForwardToClientEvent.parseFrom(envelope.getEvent().getBody());
            CommandReceiveLog.nested("gateway-server","Agent WS",event);
            if (!event.hasClientMessage()) {
                throw new InvalidProtocolBufferException("Agent 推送缺少客户端消息");
            }
            for (String userId : event.getTargetUserIdsList()) {
                channels.push(userId, key.serviceName(), key.instanceId(), event.getClientMessage());
            }
        }
    }
//TCP 连上，但是业务握手还没完成，Agent 直接断连 → channelInactive 触发
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!ready.isDone()) {
            onFailure.accept(new IllegalStateException("Agent 长连接提前断开"));
        }
        // 只移除当前这条连接，避免旧连接晚到的断线事件误删重连后的新连接。
        onDisconnected.run();
    }

    /**
     * `ready` 还没完成 = **业务握手阶段发生异常**。
     * 调用上层传入的 `onFailure` 回调，把异常丢给 AgentLinkManager（连接管理器）。
     * 管理器收到后认为：**本次新建Agent连接尝试失败**，可以触发重连逻辑。
     *
     * > 如果 `ready.isDone() == true`（连接早已握手成功，正在正常收发游戏消息），发生异常，**不会执行 onFailure**。
     * > 理由：`onFailure` 只用于标记「**建连尝试失败**」；已经建好的长连接中途出问题属于断线，不是“建立连接失败”。
     *
     * 2. ```java
     * log.warn("Agent 长连接异常: {}", key, cause);
      * @param ctx
     * @param cause
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!ready.isDone()) {
            onFailure.accept(cause);
        }
        log.warn("Agent 长连接异常: {}", key, cause);
        ctx.close();
    }
}
