package com.h.gatewayserver.netty.handler;

import com.h.gatewayserver.context.GatewayContext;
import com.h.gatewayserver.registries.GatewayChannelRegistry;
import com.h.gatewayserver.dispatch.client.GatewayMessageDispatcher;
import com.h.gatewayserver.rpc.feign.AgentMgrDisconnectNotifierlCient;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WebSocket 游戏消息入口。前面的 Decoder 已将完整二进制帧转换为 byte[]；
 * 本类只管理连接生命周期并把协议消息交给 GatewayMessageDispatcher。
 */
@Component
@ChannelHandler.Sharable
@Slf4j
public class NettyHandler extends ChannelInboundHandlerAdapter {
    private final GatewayMessageDispatcher dispatcher;
    private final GatewayChannelRegistry channels;
    private final AgentMgrDisconnectNotifierlCient disconnectNotifier;
//构造器注入
    public NettyHandler(GatewayMessageDispatcher dispatcher, GatewayChannelRegistry channels,
                        AgentMgrDisconnectNotifierlCient disconnectNotifier) {
        this.dispatcher = dispatcher;
        this.channels = channels;
        this.disconnectNotifier = disconnectNotifier;
    }

    /**
     * 客户端完成 TCP/WebSocket 连接后调用；后续可在这里创建玩家会话或连接上下文。
     *
     * @param context 当前客户端连接的上下文
     */
    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {

        Channel channel=context.channel();
        GatewayContext gatewayContext = new GatewayContext(channel);
        channel.attr(GatewayContext.KEY).set(gatewayContext);
        log.info("WebSocket client connected: {}", context.channel().remoteAddress());
        super.channelActive(context);
    }

    /**
     * 客户端断开连接后调用；后续可在这里清理玩家会话、下线状态或缓存资源。
     *
     * @param context 已断开的客户端连接上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        Channel channel = context.channel();
        // 解除 Channel 对上下文的引用；尚未绑定 Agent 的连接也要执行这一步。
        GatewayContext gatewayContext = channel.attr(GatewayContext.KEY).getAndSet(null);
        try {
            if (gatewayContext == null) {
                log.info(
                        " 连接：{} ，上下文不存在",
                        channel.remoteAddress()
                );
                return;
            }

            String userId = gatewayContext.getUserId();
            channels.endLogin(gatewayContext);
            // 无论下游通知是否成功，都先解除本机推送路由。在路由表中移除当前用户的路由项
            channels.unbind(userId, gatewayContext);

            if (userId == null || userId.isBlank() || gatewayContext.getPlayerActorId() == null
                    || gatewayContext.getPlayerActorId().isBlank()) {
                // 当前连接尚未完整绑定 PlayerAgent，不能凭 userId 误下线其他登录。
                log.info(
                        "连接: {}，尚未绑定 Agent",
                        channel.remoteAddress()
                );
                return;
            }

            // 只传断线时的标识快照，不把已关闭的 Channel/GatewayContext 交给异步任务。
            // Feign 在线程池中执行；AgentMgr 核对当前登录后，等待 Agent 保存并下线再返回最终结果。
            disconnectNotifier.notifyDisconnected(userId, gatewayContext.getPlayerActorId());
        } finally {
            // Netty 生命周期事件继续传递给后面的入站 Handler。
            super.channelInactive(context);
        }

    }

    /**
     * 处理入站消息。Pipeline 已将 WebSocket 二进制帧解码为 byte[]；
     * GatewayMessageDispatcher 会按协议解析并按 cmd 分发。
     *
     * @param context 当前客户端连接的上下文
     * @param message Pipeline 上游传入的消息
     */
    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (message instanceof byte[] bytes) {
            // 踢人开始后，即使提示尚未写完、连接尚未关闭，也不再解析或转发新的业务请求。
            // byte[] 无需引用计数释放；只丢弃业务消息，不影响后续生命周期事件和断线清理。
            if (channels.isKicking(context.channel())) {
                return;
            }
            // 完整 WebSocket 帧已经在上游解码，这里直接交给 Protobuf 分发器。
            GatewayContext gatewayContext = context.channel().attr(GatewayContext.KEY).get();
            if (gatewayContext == null) {
                context.close();
                return;
            }
            dispatcher.dispatch(context, gatewayContext, bytes);
            return;
        }
//        向下传播 `channelRead` 事件，把收到的 `message` 交给管道（ChannelPipeline）中下一个 Handler 的 `channelRead()` 方法处理。
        context.fireChannelRead(message);
    }

    /**
     * 处理 IdleStateHandler 产生的空闲事件。客户端在配置时间内没有任何读请求时，关闭该连接。
     *心跳
     * @param context 当前客户端连接的上下文
     * @param event   Pipeline 上游触发的用户事件
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) {
        if (event instanceof IdleStateEvent idleEvent && idleEvent.state() == IdleState.READER_IDLE) {
            log.info("WebSocket read idle timeout: {}", context.channel().remoteAddress());
            context.close();
            return;
        }
        context.fireUserEventTriggered(event);
    }

    /**
     * 处理 Pipeline 中未捕获的异常，记录日志后关闭异常连接，避免连接继续处于不确定状态。
     *
     * @param context 当前客户端连接的上下文
     * @param cause   捕获到的异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        log.warn("WebSocket channel exception: {}", context.channel().remoteAddress(), cause);
        context.close();
    }
}
