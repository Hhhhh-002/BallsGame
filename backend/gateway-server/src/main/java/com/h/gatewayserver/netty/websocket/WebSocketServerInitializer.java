package com.h.gatewayserver.netty.websocket;

import com.h.gatewayserver.netty.handler.NettyHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 为每个 WebSocket 客户端连接创建 Pipeline。
 * 后续新增、删除或调整 Handler 的顺序，只修改此类。
 */
@Component
public class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {

    private final NettyHandler nettyHandler;

    @Value("${netty.gateway.websocket.path:/ws}")
    private String webSocketPath;

    @Value("${netty.gateway.websocket.reader-idle-seconds:60}")
    private int readerIdleSeconds;

    /**
     * 注入可被多个连接共享的业务消息 Handler。
     *
     * @param nettyHandler 处理连接、断开、超时和二进制游戏消息的 Handler
     */
    public WebSocketServerInitializer(NettyHandler nettyHandler) {
        this.nettyHandler = nettyHandler;
    }

    @Override
    /**
     * 每建立一个新的客户端连接，Netty 都调用一次此方法。
     * 方法按顺序加入 HTTP 编解码、WebSocket 握手、二进制帧编解码、空闲检测和业务 Handler。
     *
     * @param channel 当前新建立的客户端 SocketChannel
     */
    protected void initChannel(SocketChannel channel) {
        channel.pipeline().addLast(
                new HttpServerCodec(),
                new ChunkedWriteHandler(),
                new HttpObjectAggregator(64 * 1024),
                new IdleStateHandler(readerIdleSeconds, 0, 0),
                new WebSocketServerProtocolHandler(webSocketPath, null, true),
                // 聚合WebSocket分片消息
                new WebSocketFrameAggregator(64 * 1024),
                new WebSocketBinaryFrameDecoder(),
                new WebSocketBinaryFrameEncoder(),
                nettyHandler
        );
    }
}
