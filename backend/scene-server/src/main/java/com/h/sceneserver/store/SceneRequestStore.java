package com.h.sceneserver.store;

import com.h.gamecommon.code.GameError;
import com.h.gameprotocol.proto.ClientEnvelope;
import com.h.gameprotocol.proto.ServerEnvelope;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.*;
import static com.h.gamecore.protocol.Messages.require;

/** 一场比赛内每个玩家的本次入场上下文一个 Hash；field 直接是原客户端 requestId。Future 只在本地。 */
@Repository
public class SceneRequestStore {
    public record Stored(String request, String response) {
        public boolean done() { return response != null; }
        public ServerEnvelope result() {
            try { return ServerEnvelope.parseFrom(Base64.getDecoder().decode(response)); }
            catch(com.google.protobuf.InvalidProtocolBufferException invalid) { throw new IllegalStateException("场景请求响应损坏",invalid); }
        }
    }
    public record Admission(Stored stored, boolean owner) {}
    private final StringRedisTemplate redis;
    private final ExecutorService executor;
    private final JsonMapper json=JsonMapper.builder().build();
    private final String prefix;
    private final Duration retention;
    public SceneRequestStore(StringRedisTemplate redis, ExecutorService executor, Environment env) {
        this.redis=redis;this.executor=executor;
        prefix=env.getProperty("scene.request-key-prefix","balls:scene:requests:");
        long seconds=env.getProperty("scene.left-request-retention-seconds",Long.class,86400L);
        if(prefix.isBlank() || seconds<1)throw new IllegalArgumentException("场景请求存储配置不合法");
        retention=Duration.ofSeconds(seconds);
    }
    private String key(String instance,long scene,String actor) {
        String scope=instance+":"+Long.toUnsignedString(scene)+":"+actor;
        return prefix+Base64.getUrlEncoder().withoutPadding().encodeToString(scope.getBytes(StandardCharsets.UTF_8));
    }
    private String encode(ClientEnvelope request) {return Base64.getEncoder().encodeToString(request.toByteArray());}
    private Stored read(String key,ClientEnvelope request) {
        Object raw=redis.opsForHash().get(key,Long.toUnsignedString(request.getRequestId()));
        require(raw!=null,GameError.REQUEST_EXPIRED);
        Stored stored=json.readValue(raw.toString(),Stored.class);
        require(stored.request().equals(encode(request)),GameError.OPERATION_CONFLICT);
        return stored;
    }
    /** PROCESSING 不到期；查到别的进程留下的未确认动作时，不盲目重复执行。 */
    public CompletableFuture<Admission> claim(String instance,long scene,String actor,ClientEnvelope request,boolean replayOnly) {
        return io(()-> {
            require(request.getRequestId()!=0,GameError.INVALID_INPUT);
            String key=key(instance,scene,actor);
            boolean owner=!replayOnly && Boolean.TRUE.equals(redis.opsForHash().putIfAbsent(key,
                    Long.toUnsignedString(request.getRequestId()),json.writeValueAsString(new Stored(encode(request),null))));
            return new Admission(read(key,request),owner);
        });
    }
    public CompletableFuture<Void> complete(String instance,long scene,String actor,ClientEnvelope request,ServerEnvelope response) {
        return io(()-> {
            require(response.getCmd()==request.getCmd() && response.getRequestId()==request.getRequestId(),GameError.OPERATION_CONFLICT);
            String key=key(instance,scene,actor);Stored previous=read(key,request);
            if(previous.done()) {require(previous.result().equals(response),GameError.OPERATION_CONFLICT);return null;}
            redis.opsForHash().put(key,Long.toUnsignedString(request.getRequestId()),json.writeValueAsString(
                    new Stored(previous.request(),Base64.getEncoder().encodeToString(response.toByteArray()))));
            return null;
        });
    }
    public CompletableFuture<Void> retired(String instance,long scene,String actor) {
        return io(()-> {redis.expire(key(instance,scene,actor),retention);return null;});
    }
    private <T> CompletableFuture<T> io(java.util.function.Supplier<T> task) {
        try {return CompletableFuture.supplyAsync(task,executor);}
        catch(RejectedExecutionException failure) {return CompletableFuture.failedFuture(failure);}
    }
}
