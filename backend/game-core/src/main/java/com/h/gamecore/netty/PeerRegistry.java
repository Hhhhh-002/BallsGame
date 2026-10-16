package com.h.gamecore.netty;
import com.h.gameprotocol.proto.ServiceEnvelope;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.util.AttributeKey;
import java.util.concurrent.*;

/** 对端实例 ID -> 当前共享连接；旧连接断开时只能删除自己。 */
public final class PeerRegistry {
    public static final AttributeKey<String> PEER_ID = AttributeKey.valueOf("balls.internal.peer");
    private final ConcurrentMap<String, Channel> peers = new ConcurrentHashMap<>();
    public void bind(String id, Channel channel) {
        if (id == null || id.isBlank() || id.length() > 256 || channel.attr(PEER_ID).get() != null)
            throw new IllegalArgumentException("内部连接实例身份不合法");
        channel.attr(PEER_ID).set(id);
        Channel previous = peers.put(id, channel);
        if (previous != null && previous != channel) previous.close();
    }
    public boolean current(Channel channel) {
        String id = channel.attr(PEER_ID).get();
        return id != null && peers.get(id) == channel;
    }
    public void unbind(Channel channel) {
        String id = channel.attr(PEER_ID).getAndSet(null);
        if (id != null) peers.remove(id, channel);
    }
    public CompletableFuture<Void> send(String id, ServiceEnvelope envelope) {
        Channel channel = peers.get(id);
        if (channel == null || !channel.isActive() || !channel.isWritable())
            return CompletableFuture.failedFuture(new RejectedExecutionException("对端连接未就绪或发送缓冲已满"));
        return write(channel, envelope);
    }
    public static CompletableFuture<Void> write(Channel channel, ServiceEnvelope envelope) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        BinaryWebSocketFrame frame=new BinaryWebSocketFrame(Unpooled.wrappedBuffer(envelope.toByteArray()));
        try {
            channel.writeAndFlush(frame)
                .addListener(f -> { if (f.isSuccess()) result.complete(null); else result.completeExceptionally(f.cause()); });
        } catch(RuntimeException failure) {
            if(frame.refCnt()>0)frame.release();
            result.completeExceptionally(failure);
        }
        return result;
    }
}
