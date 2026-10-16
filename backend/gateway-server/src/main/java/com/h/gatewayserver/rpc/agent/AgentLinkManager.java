package com.h.gatewayserver.rpc.agent;

import com.h.gamecommon.Excutor.GameExecutorFactory;
import com.h.gamecommon.services.ServiceNames;
import com.h.gameprotocol.codec.LinkKey;
import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gatewayserver.registries.GatewayChannelRegistry;
import com.h.gatewayserver.netty.handler.AgentLinkHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadPoolExecutor;

/** 一个 Gateway 到一个 Agent 实例复用一条双向 WebSocket，不为每个玩家建连接。 */
@Component
@Slf4j
public class AgentLinkManager {
    private final DiscoveryClient discovery;
    private final GatewayChannelRegistry channels;
    private final String gatewayInstanceId;
    private final String path;
    private final NioEventLoopGroup ioGroup = new NioEventLoopGroup(2);
    private final ThreadPoolExecutor connectExecutor = GameExecutorFactory.newFixedThreadPool(
            "gateway-agent-connect", 2, 128);
    private final ConcurrentMap<LinkKey, CompletableFuture<Channel>> links = new ConcurrentHashMap<>();

    public AgentLinkManager(DiscoveryClient discovery, GatewayChannelRegistry channels,
                            @Value("${spring.cloud.nacos.discovery.metadata.instanceid:}") String gatewayInstanceId,
                            @Value("${agent.websocket.path:/internal/agent}") String path) {
        this.discovery = discovery;
        this.channels = channels;
        this.gatewayInstanceId = gatewayInstanceId;
        this.path = path;
    }

    /** 登录确认前建立连接；Nacos 查找和 TCP 握手不会阻塞客户端 EventLoop。 */
    /*
     * // ❌ 不使用defer：一创建Mono就立刻执行channelFor()，不管有没有订阅
     * Mono.fromFuture(channelFor(key));
     *
     * // ✅ defer：等到subscribe的时候，才执行 channelFor(key)
     * Mono.defer(() -> Mono.fromFuture(channelFor(key)));
     */

    /*
     * `.then()` 的含义：**只关心上游流是否成功完成，丢弃上游发射出来的数据（Channel）**
     *
     * - 上游成功完成 → 直接发出 onComplete，不带任何数据
     * - 上游失败 → 直接传递异常
     *
     * >
     * > 对应这个方法语义：`ensureConnected` 只需要保证「连接准备好了」，
     * **不需要拿到 Channel 对象**，Channel 留给后续 send 方法再去获取。
     */
    public Mono<Void> ensureConnected(String serviceName, String instanceId) {
//**Mono.fromFuture (future) 会给这个 CompletableFuture 注册回调监听**，当 future 完成 / 失败时，Mono 流会收到事件；
        return Mono.defer(() -> Mono.fromFuture(channelFor(new LinkKey(serviceName, instanceId)))).then();
    }
/**
 * `agentLinks.send(...)` 本身不是 “正在运行的异步任务”，它只是构造并返回了一个 `Mono`，
 * 这个 Mono 描述了一套未来要执行的异步动作；只有当外部调用 `.subscribe()` 之后，这套动作才会真正启动执行。
 * subscribe 这一步，才会去启动 Mono 内部编排好的逻辑：获取 Agent 长连接，执行 Netty writeAndFlush
 */
    /** 发送一个服务间信封；仅等待 Netty 写入，不等待 Agent 业务处理。 */
    public Mono<Void> send(String serviceName, String instanceId, ServiceEnvelope message) {
//       延迟构造内部 Mono，**只有订阅的时候才执行里面的 lambda**。 延迟构造内部 Mono，**只有订阅的时候才执行里面的 lambda**。
        return Mono.defer(() -> Mono.fromFuture(channelFor(new LinkKey(serviceName, instanceId))))
                .flatMap(channel -> Mono.<Void>create(sink -> channel.writeAndFlush(
                        new BinaryWebSocketFrame(Unpooled.wrappedBuffer(message.toByteArray())))
                        .addListener(future -> {
                            if (future.isSuccess()) sink.success();
                            else sink.error(future.cause());
                        })));
    }

    /*
`compute` 是 ConcurrentHashMap 原子方法：
传入 key，lambda 接收**当前 map 里旧值 current**，你返回新值，原子写入 map。
全程原子，不用手动加锁，多线程并发调用`channelFor`也不会出现竞态。
     *这个方法作用是去建立websocket连接
     */

    private CompletableFuture<Channel> channelFor(LinkKey key) {
        return links.compute(key, (ignored, current) -> {
//            连接对象已经存在（如果 map 里已有 Future：
//要么【Future 还在跑（正在建立连接）】，
//要么【Future 成功跑完、不是异常、拿到 Channel、Channel 还 active】
//→ 满足任一，就复用这个 Future。）
            if (current != null && (!current.isDone() ||
                    (!current.isCompletedExceptionally() && current.getNow(null) != null
                            && current.getNow(null).isActive()))) {
                return current;
            }
//            未注册任何channel,则新发起连接
            CompletableFuture<Channel> created = new CompletableFuture<>();
            try {
                connectExecutor.execute(() -> connect(key, created));
            } catch (RuntimeException exception) {
                created.completeExceptionally(exception);
            }
            return created;
        });
    }

    /** 用完整实例 ID 匹配 Nacos 结果；绝不能对持有 Actor 的实例随机负载均衡。 */
    private void connect(LinkKey key, CompletableFuture<Channel> result) {
        try {
            List<ServiceInstance> instances = discovery.getInstances(key.serviceName());
            ServiceInstance instance = instances.stream().filter(candidate ->
                    key.instanceId().equals(candidate.getMetadata().get(ServiceNames.INSTANCE_ID_METADATA))).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Nacos 中找不到 Agent 实例: " + key));
            String portText = instance.getMetadata().get(ServiceNames.getAgentWsPortMetadataKey(key.instanceId()));
            if (portText == null || portText.isBlank()) {
                throw new IllegalStateException("Agent 实例缺少元数据 " + portText);
            }
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535 || !path.startsWith("/")) {
                throw new IllegalArgumentException("Agent WebSocket 端口或地址配置不合法");
            }
            URI uri = URI.create("ws://" + instance.getHost() + ":" + port + path);
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, null, true, null, 256 * 1024);
            Bootstrap bootstrap = new Bootstrap().group(ioGroup).channel(NioSocketChannel.class)
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(65536),
                                    new WebSocketFrameAggregator(256 * 1024),
                                    new AgentLinkHandler(key, result, handshaker, gatewayInstanceId, channels,
                                            cause -> fail(key, result, cause),
                                            () -> links.remove(key, result)));
                        }
                    });
            bootstrap.connect(instance.getHost(), port).addListener(future -> {
                if (!future.isSuccess()) fail(key, result, future.cause());
            });
        } catch (Exception exception) {
            fail(key, result, exception);
        }
    }

    private void fail(LinkKey key, CompletableFuture<Channel> result, Throwable cause) {
        result.completeExceptionally(cause);
        links.remove(key, result);
        log.warn(" gateway向agent消息写入失败，关闭channel，原因：{}", key, cause);
    }




//`@PreDestroy` 是 Spring 的生命周期注解：**当这个 Bean 被 Spring 容器销毁之前，自动执行 `shutdown()` 方法**。
    /** 服务关闭时回收连接和专用线程，不影响客户端 WebSocket 服务。 */
    @PreDestroy
    public void shutdown() {
        /**
         * 遍历所有 Future：
         * - future 如果**已经完成（拿到 Channel）**：`thenAccept` 回调执行 `channel.close()`，关闭网关到 Agent 的 Netty 长连接
         * - future 如果**还没完成（正在握手）**：`thenAccept` 会挂着，等后续建连完成后再关闭这个 Channel
         */
        links.values().forEach(future -> future.thenAccept(Channel::close));
        links.clear();
        connectExecutor.shutdown();
        ioGroup.shutdownGracefully();
    }
}
