package com.h.gamecore.netty;

import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecore.protocol.*;
import com.h.gameprotocol.proto.*;
import com.h.gameprotocol.codec.CommandReceiveLog;
import com.google.protobuf.Message;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** 按实例缓存连接；不同玩家的请求在同一连接上按 request_id 关联响应。 */
public final class SharedWebSocketClient implements AutoCloseable {
    private record Pending(int cmd, CompletableFuture<ServiceResponse> result) {}
    private final class Link {
        final CompletableFuture<Channel> ready = new CompletableFuture<>();
        final ConcurrentMap<Long, Pending> pending = new ConcurrentHashMap<>();
    }
    private final ConcurrentMap<String, Link> links = new ConcurrentHashMap<>();
    private final NioEventLoopGroup io = new NioEventLoopGroup(2);
    private final ExecutorService discoveryExecutor;
    private final Function<String, URI> resolver;
    private final BiConsumer<String, ServiceEvent> events;
    private final ServiceCommands hello;
    private final String receivingService;
    private final Message identity;
    private final AtomicLong ids = new AtomicLong(1);
    private final long timeoutMs;
    private volatile boolean closed;

    public SharedWebSocketClient(String receivingService,ExecutorService discoveryExecutor, Function<String, URI> resolver,
            ServiceCommands hello, Message identity, long timeoutMs, BiConsumer<String, ServiceEvent> events) {
        if(timeoutMs<=0 || timeoutMs>60000)throw new IllegalArgumentException("Scene RPC 超时须在 1～60000ms");
        this.discoveryExecutor = discoveryExecutor; this.resolver = resolver;
        this.receivingService=receivingService;
        this.hello = hello; this.identity = identity; this.timeoutMs = timeoutMs; this.events = events;
    }
    private Link link(String instance) {
        if (closed) throw new RejectedExecutionException("长连接管理器已关闭");
        Link created = new Link();
        Link existing = links.putIfAbsent(instance, created);
        if (existing != null) return existing;
        // 连同 Nacos 查询和 TCP 建连一起限时，不只限制 channelActive 之后的握手。
        io.next().schedule(()-> {
            if(!created.ready.isDone())fail(instance,created,new TimeoutException("Scene 建连总等待超时"));
        },5,TimeUnit.SECONDS);
        // 放入缓存后才启动，避免连接失败清理发生在缓存写入之前。
        try { discoveryExecutor.execute(() -> connect(instance, created)); }
        catch (RuntimeException failure) { fail(instance, created, failure); }
        return created;
    }
    private void connect(String instance, Link link) {
        try {
            if (closed) throw new RejectedExecutionException("长连接管理器已关闭");
            URI uri = resolver.apply(instance);
            if(link.ready.isCompletedExceptionally())return;
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, null, false, null, InternalWebSocketServer.MAX_FRAME_BYTES);
            new Bootstrap().group(io).channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpClientCodec(), new HttpObjectAggregator(65536),
                                    new WebSocketClientProtocolHandler(handshaker),
                                    new WebSocketFrameAggregator(InternalWebSocketServer.MAX_FRAME_BYTES), new IdleStateHandler(45, 15, 0),
                                    new Incoming(instance, link));
                        }
                    }).connect(uri.getHost(), uri.getPort()).addListener(f -> {
                        if (!f.isSuccess()) fail(instance, link, f.cause());
                    });
        } catch (Exception failure) { fail(instance, link, failure); }
    }
    /** 自动重试只允许用于 JOIN/LEAVE/FIND 等已设计幂等的控制请求；目标实例与业务 body 固定。 */
    public CompletableFuture<ServiceResponse> request(String instance, ServiceCommands cmd, Message body) {
        return attempt(instance, cmd, body, ids.incrementAndGet(), 0);
    }
    private CompletableFuture<ServiceResponse> attempt(String instance, ServiceCommands cmd, Message body, long id, int retry) {
        CompletableFuture<ServiceResponse> result = new CompletableFuture<>();
        Link link;
        try { link = link(instance); } catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
        link.ready.whenComplete((channel, failure) -> {
            if (failure != null) { result.completeExceptionally(failure); return; }
            if (link.pending.size() >= 1024) { result.completeExceptionally(new RejectedExecutionException("控制请求积压")); return; }
            Pending pending = new Pending(cmd.code(), result);
            link.pending.put(id, pending);
            try {
                channel.eventLoop().schedule(() -> {
                    if (link.pending.remove(id, pending)) result.completeExceptionally(new TimeoutException("Scene RPC 超时"));
                }, timeoutMs, TimeUnit.MILLISECONDS);
            } catch(RuntimeException rejected) {
                link.pending.remove(id,pending); result.completeExceptionally(rejected); return;
            }
            ServiceEnvelope envelope = ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder()
                    .setCmd(cmd.code()).setRequestId(id).setBody(body.toByteString())).build();
            PeerRegistry.write(channel, envelope).whenComplete((v, e) -> {
                if (e != null && link.pending.remove(id, pending)) result.completeExceptionally(e);
            });
        });
        return result.handle((response, failure) -> {
            if (failure == null && (response.getCode()!=5001 || retry>=2)) return CompletableFuture.completedFuture(response);
            if (retry >= 2 || closed) return failure==null?CompletableFuture.completedFuture(response)
                    :CompletableFuture.<ServiceResponse>failedFuture(Messages.unwrap(failure));
            if(failure!=null) {
                Throwable cause=Messages.unwrap(failure);
                if(!(cause instanceof java.io.IOException) && !(cause instanceof TimeoutException)
                        && !(cause instanceof RejectedExecutionException)
                        && !(cause instanceof BattleException b && b.error()==com.h.gamecommon.code.GameError.SERVICE_UNAVAILABLE))
                    return CompletableFuture.<ServiceResponse>failedFuture(cause);
            }
            // 非阻塞退避；所有尝试沿用相同请求及实例，不能重新选择 Scene。
            return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(200L << retry, TimeUnit.MILLISECONDS))
                    .thenCompose(v -> attempt(instance, cmd, body, id, retry + 1));
        }).thenCompose(Function.identity());
    }
    /** 高频输入只等写入，不伪装成业务处理完成，也不自动重放分裂/吐球动作。 */
    public CompletableFuture<Void> send(String instance, ServiceEnvelope envelope) {
        try {
            return link(instance).ready.thenCompose(channel -> {
                if (!channel.isWritable()) return CompletableFuture.failedFuture(new RejectedExecutionException("Scene 发送缓冲已满"));
                return PeerRegistry.write(channel, envelope);
            });
        } catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
    }
    private final class Incoming extends SimpleChannelInboundHandler<WebSocketFrame> {
        private final String instance; private final Link link;
        Incoming(String instance, Link link) { this.instance = instance; this.link = link; }
        @Override public void channelActive(ChannelHandlerContext ctx) {
            if(link.ready.isCompletedExceptionally()){ctx.close();return;}
            ctx.executor().schedule(() -> {
                if (!link.ready.isDone()) { fail(instance, link, new TimeoutException("Scene 握手超时")); ctx.close(); }
            }, 5, TimeUnit.SECONDS);
        }
        @Override public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
            if (event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                PeerRegistry.write(ctx.channel(), ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder()
                        .setCmd(hello.code()).setRequestId(1).setBody(identity.toByteString())).build())
                        .whenComplete((v, e) -> { if (e != null) { fail(instance, link, e); ctx.close(); } });
            } else if (event instanceof IdleStateEvent idle) {
                if (idle.state() == IdleState.READER_IDLE) ctx.close();
                else if (idle.state() == IdleState.WRITER_IDLE) ctx.writeAndFlush(new PingWebSocketFrame());
            } else ctx.fireUserEventTriggered(event);
        }
        @Override protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
            if (!(frame instanceof BinaryWebSocketFrame binary)) return;
            byte[] bytes = new byte[binary.content().readableBytes()]; binary.content().readBytes(bytes);
            ServiceEnvelope envelope = ServiceEnvelope.parseFrom(bytes);
            CommandReceiveLog.service(receivingService,"Scene WS",envelope);
            if (envelope.hasResponse()) {
                ServiceResponse r = envelope.getResponse();
                if (r.getRequestId() == 1 && r.getCmd() == hello.code()) {
                    if (r.getCode() != 0) throw new IllegalStateException("Scene 拒绝业务握手");
                    if (closed) ctx.close(); else link.ready.complete(ctx.channel());
                } else {
                    Pending pending = link.pending.remove(r.getRequestId());
                    if (pending != null) {
                        if (pending.cmd != r.getCmd()) pending.result.completeExceptionally(new IllegalStateException("RPC cmd 不匹配"));
                        else pending.result.complete(r);
                    }
                }
            } else if (envelope.hasEvent() && link.ready.isDone() && !link.ready.isCompletedExceptionally()) {
                events.accept(instance, envelope.getEvent());
            }
        }
        @Override public void channelInactive(ChannelHandlerContext ctx) { fail(instance, link, new java.io.IOException("Scene 长连接断开")); }
        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { fail(instance, link, cause); ctx.close(); }
    }
    private void fail(String instance, Link link, Throwable failure) {
        link.ready.completeExceptionally(failure);
        link.pending.forEach((id, pending) -> pending.result.completeExceptionally(failure));
        link.pending.clear(); links.remove(instance, link);
    }
    @Override public void close() {
        closed = true;
        links.forEach((id, link) -> {
            link.ready.thenAccept(Channel::close);
            fail(id, link, new RejectedExecutionException("服务停止"));
        });
        discoveryExecutor.shutdown(); io.shutdownGracefully();
    }
}
