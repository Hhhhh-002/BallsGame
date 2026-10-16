package com.h.gamecore.netty;

import com.h.gameprotocol.proto.*;
import com.h.gamecore.protocol.Messages;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/** Agent/Scene 共用的内部 WebSocket 服务。业务 Handler 每个 Channel 独立创建。 */
public final class InternalWebSocketServer implements AutoCloseable {
    public static final int MAX_FRAME_BYTES = 256 * 1024; // 包括多球全量快照和批量接收者。
    private final NioEventLoopGroup accept = new NioEventLoopGroup(1), io = new NioEventLoopGroup(2);
    private final PeerRegistry peers;
    private final BiFunction<Channel, ServiceEnvelope, CompletionStage<ServiceEnvelope>> dispatch;
    private final int port;
    private final String path;
    private Channel listener;
    public InternalWebSocketServer(int port, String path, PeerRegistry peers,
            BiFunction<Channel, ServiceEnvelope, CompletionStage<ServiceEnvelope>> dispatch) {
        if (port < 1 || port > 65535 || !path.startsWith("/")) throw new IllegalArgumentException("WS 地址配置错误");
        this.port = port; this.path = path; this.peers = peers; this.dispatch = dispatch;
    }
    /** 启动时绑定失败直接报错，不把没有监听的实例当作可用实例。 */
    public void start() throws InterruptedException {
        try {
            listener = new ServerBootstrap().group(accept, io).channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(65536),
                                    new WebSocketServerProtocolHandler(path, null, false, MAX_FRAME_BYTES),
                                    new WebSocketFrameAggregator(MAX_FRAME_BYTES), new IdleStateHandler(45, 15, 0),
                                    new Incoming());
                        }
                    }).bind(port).sync().channel();
        } catch (InterruptedException | RuntimeException e) { close(); throw e; }
    }
    private final class Incoming extends SimpleChannelInboundHandler<WebSocketFrame> {
        @Override public void channelActive(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> { if (ctx.channel().attr(PeerRegistry.PEER_ID).get() == null) ctx.close(); },
                    5, TimeUnit.SECONDS);
        }
        @Override protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
            if (!(frame instanceof BinaryWebSocketFrame binary)) return;
            byte[] bytes = new byte[binary.content().readableBytes()];
            binary.content().readBytes(bytes);
            ServiceEnvelope message = ServiceEnvelope.parseFrom(bytes);
            CompletionStage<ServiceEnvelope> result;
            try { result = dispatch.apply(ctx.channel(), message); }
            catch (RuntimeException e) {
                if (!message.hasRequest()) { ctx.close(); return; }
                result = CompletableFuture.completedFuture(Messages.error(message.getRequest(), e));
            }
            result.whenComplete((response, failure) -> {
                if (failure != null) {
                    if (message.hasRequest()) PeerRegistry.write(ctx.channel(), Messages.error(message.getRequest(), failure));
                    else ctx.close();
                } else if (response != null) PeerRegistry.write(ctx.channel(), response);
            });
        }
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event instanceof IdleStateEvent idle) {
                if (idle.state() == IdleState.READER_IDLE) ctx.close();
                else if (idle.state() == IdleState.WRITER_IDLE) ctx.writeAndFlush(new PingWebSocketFrame());
            } else ctx.fireUserEventTriggered(event);
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) { peers.unbind(ctx.channel()); }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
    }
    @Override public void close() {
        if (listener != null) listener.close();
        io.shutdownGracefully(); accept.shutdownGracefully();
    }
}
