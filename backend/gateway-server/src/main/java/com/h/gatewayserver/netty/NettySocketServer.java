package com.h.gatewayserver.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;

/**
 * 通用 Netty 监听服务：负责线程池、端口绑定和关闭；客户端 Pipeline 由调用方传入。
 */
public final class NettySocketServer {

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    /**
     * 保存当前 NettySocketServer 绑定的全部监听 Channel。
     * 当同时监听 TCP、WebSocket 等多个端口时，可在 shutdown() 中一次性关闭所有监听端口。
     */
    private final ChannelGroup listeningChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    /**
     * 创建 Netty 的 Boss/Worker 线程池。
     *
     * @param bossThreads   接收新客户端连接的线程数
     * @param workerThreads 处理已连接客户端读写事件的线程数
     */
    public NettySocketServer(int bossThreads, int workerThreads) {
//        第二个参数給线程池里面的线程取前缀名字
        this.bossGroup = new NioEventLoopGroup(bossThreads, new DefaultThreadFactory("netty-boss"));
        this.workerGroup = new NioEventLoopGroup(workerThreads, new DefaultThreadFactory("netty-worker"));
    }

    /**
     * 绑定一个监听端口；每一个客户端连接都会由 channelInitializer 创建专属 Pipeline。
     * bind(...).syncUninterruptibly() 会等待端口绑定成功后才返回。
     *
     * @param port               需要监听的 TCP 端口
     * @param channelInitializer 初始化每个客户端连接 Pipeline 的对象
     */
    public synchronized void startServer(int port, ChannelInitializer<SocketChannel> channelInitializer) throws InterruptedException {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(channelInitializer);
        bootstrap.bind(port).sync();
//
//        Channel listeningChannel = bootstrap.bind(port).syncUninterruptibly().channel();
//        listeningChannels.add(listeningChannel);
    }

    /**
     * 停止整个 Netty 服务：先关闭所有监听端口，阻止新客户端接入，
     * 再依次关闭 Boss 和 Worker 线程池，并等待线程完全退出。
     */
    public synchronized void shutdown() {
//        listeningChannels.close().syncUninterruptibly();
        bossGroup.shutdownGracefully().syncUninterruptibly();
        workerGroup.shutdownGracefully().syncUninterruptibly();
    }
}
