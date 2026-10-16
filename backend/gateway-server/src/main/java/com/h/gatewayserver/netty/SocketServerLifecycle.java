package com.h.gatewayserver.netty;

import com.h.gatewayserver.netty.websocket.WebSocketServerInitializer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 实现 SmartLifecycle，让 Spring Boot 负责启动和关闭 Netty WebSocket 服务。
 */
@Component
@Slf4j
public class SocketServerLifecycle implements SmartLifecycle {


    private final WebSocketServerInitializer webSocketServerInitializer;

    @Value("${netty.gateway.websocket.port:9001}")
    private int webSocketPort;

    @Value("${netty.gateway.websocket.bossthread:1}")
    private int bossThreads;

    @Value("${netty.gateway.websocket.workthread:12}")
    private int workerThreads;

    private volatile boolean running;
    private NettySocketServer nettySocketServer;

    /**
     * 注入 WebSocket Pipeline 初始化器；Spring 会自动创建并传入该 Bean。
     *
     * @param webSocketServerInitializer 用于初始化每一个 WebSocket 客户端连接的 Pipeline
     */
    public SocketServerLifecycle(WebSocketServerInitializer webSocketServerInitializer) {
        this.webSocketServerInitializer = webSocketServerInitializer;
    }

    @Override
    /**
     * Spring Boot 启动生命周期调用此方法。
     * 创建 Netty 服务、绑定 WebSocket 端口，并使用 WebSocketServerInitializer 配置客户端 Pipeline。
     */
    public synchronized void start() {
        if (running) {
            return;
        }

        nettySocketServer = new NettySocketServer(bossThreads, workerThreads);
        try {
            nettySocketServer.startServer(webSocketPort, webSocketServerInitializer);
        } catch (InterruptedException e) {
            log.error("Failed to start websocket server", e);
            throw new RuntimeException(e);
        }
        running = true;
        log.info("Netty WebSocket service is listening on port {}", webSocketPort);
    }

    @Override
    /**
     * Spring Boot 关闭生命周期调用此方法。
     * 关闭监听端口和 Netty 的 Boss/Worker 线程池，释放网络资源。
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }

        nettySocketServer.shutdown();
        nettySocketServer = null;
        running = false;
        log.info("Netty WebSocket service stopped");
    }

    @Override
    /**
     * SmartLifecycle 的异步关闭回调版本。
     * 当前关闭动作同步完成，因此调用 stop() 后立即通知 Spring 关闭完成。
     *
     * @param callback 关闭完成后必须执行的回调
     */
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    /**
     * 返回 Netty 服务当前是否已完成端口监听。
     *
     * @return true 表示服务正在运行
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    /**
     * 指示 Spring Boot 在应用启动时自动调用 start()，无需在启动类中手动启动 Netty。
     *
     * @return 始终为 true
     */
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    /**
     * 定义生命周期顺序。最大值表示它会在其他低 phase 组件之后启动，
     * 并在应用关闭时优先停止接收新连接。
     *
     * @return 当前组件的生命周期 phase
     */
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
