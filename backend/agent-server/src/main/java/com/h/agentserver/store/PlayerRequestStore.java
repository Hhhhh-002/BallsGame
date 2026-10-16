package com.h.agentserver.store;

import com.h.gamecommon.code.GameError;
import com.h.gameprotocol.proto.ClientEnvelope;
import com.h.gameprotocol.proto.ServerEnvelope;
import com.h.gameprotocol.proto.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static com.h.gamecore.protocol.Messages.require;

/** 原请求及结果的 Redis 去重容器；不保存 Future、在线列表或独立场景绑定。 */
@Repository
public class PlayerRequestStore {
    /** 原创建请求、固定 Agent 目标与最终创建响应；不是独立在线记录。 */
    public record Creation(String request, String instance, String response) {
        public CreatePlayerAgentRequest body() {
            try { return CreatePlayerAgentRequest.parseFrom(Base64.getDecoder().decode(request)); }
            catch(Exception invalid) { throw new IllegalStateException("创建请求损坏",invalid); }
        }
    }
    public record CreateClaim(Creation record,boolean owner) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public record Stored(String request, String response, String instance, long scene, String nickname) {
        public ClientEnvelope envelope() {
            try { return ClientEnvelope.parseFrom(Base64.getDecoder().decode(request)); }
            catch(Exception invalid) { throw new IllegalStateException("原请求损坏",invalid); }
        }
        public boolean done() { return response != null; }
        public ServerEnvelope result() {
            try { return ServerEnvelope.parseFrom(Base64.getDecoder().decode(response)); }
            catch (com.google.protobuf.InvalidProtocolBufferException failure) { throw new IllegalStateException("去重响应损坏", failure); }
        }
    }
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String prefix;
    private final Duration retention;
    public PlayerRequestStore(StringRedisTemplate redis, Environment env) {
        this.redis = redis;
        prefix = env.getProperty("agent.request-key-prefix", "balls:agent:requests:");
        long seconds = env.getProperty("agent.stopped-request-retention-seconds", Long.class, 86400L);
        if (seconds < 1) throw new IllegalArgumentException("停止后的请求保留时间必须为正数");
        retention = Duration.ofSeconds(seconds);
    }
    private String key(String actorId) {
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(actorId.getBytes(StandardCharsets.UTF_8));
    }
    private String field(long requestId) { return Long.toUnsignedString(requestId); }
    private String encode(ClientEnvelope request) { return Base64.getEncoder().encodeToString(request.toByteArray()); }
    private String lifecycleKey(String actor) { return prefix+"lifecycle:"+Base64.getUrlEncoder().withoutPadding()
            .encodeToString(actor.getBytes(StandardCharsets.UTF_8)); }
    public Creation creation(String actor) {
        Object raw=redis.opsForHash().get(lifecycleKey(actor),"_create");
        return raw==null?null:json.readValue(raw.toString(),Creation.class);
    }
    public CreateClaim claimCreate(CreatePlayerAgentRequest request,String instance) {
        String encoded=Base64.getEncoder().encodeToString(request.toByteArray());
        boolean owner=Boolean.TRUE.equals(redis.opsForHash().putIfAbsent(lifecycleKey(request.getPlayerActorId()),"_create",
                json.writeValueAsString(new Creation(encoded,instance,null))));
        Creation original=creation(request.getPlayerActorId());
        require(original!=null && original.request().equals(encoded) && original.instance().equals(instance),GameError.OPERATION_CONFLICT);
        require(stopResult(request.getPlayerActorId())==null,GameError.LOGIN_OPERATION_SUPERSEDED);
        return new CreateClaim(original,owner);
    }
    public void created(String actor,CreatePlayerAgentResult result) {
        Creation original=creation(actor); require(original!=null,GameError.OPERATION_CONFLICT);
        String response=Base64.getEncoder().encodeToString(result.toByteArray());
        require(original.response()==null || original.response().equals(response),GameError.OPERATION_CONFLICT);
        redis.opsForHash().put(lifecycleKey(actor),"_create",json.writeValueAsString(
                new Creation(original.request(),original.instance(),response)));
    }
    public void claimStop(KickOldAgentRequest request) {
        String value=Base64.getEncoder().encodeToString(request.toByteArray());
        String key=lifecycleKey(request.getPlayerActorId());
        redis.opsForHash().putIfAbsent(key,"_stopRequest",value);
        require(value.equals(redis.opsForHash().get(key,"_stopRequest")),GameError.OPERATION_CONFLICT);
    }
    public boolean stopping(String actor) { return redis.opsForHash().get(lifecycleKey(actor),"_stopRequest")!=null; }
    public KickOldAgentResult stopResult(String actor) {
        Object raw=redis.opsForHash().get(lifecycleKey(actor),"_stopResult"); if(raw==null)return null;
        try { return KickOldAgentResult.parseFrom(Base64.getDecoder().decode(raw.toString())); }
        catch(Exception invalid) { throw new IllegalStateException("停止结果损坏",invalid); }
    }
    public void stopped(String actor,KickOldAgentResult result) {
        String value=Base64.getEncoder().encodeToString(result.toByteArray());
        redis.opsForHash().putIfAbsent(lifecycleKey(actor),"_stopResult",value);
        require(result.equals(stopResult(actor)),GameError.OPERATION_CONFLICT);
    }
    private Stored read(String actorId, ClientEnvelope request) {
        return read(actorId,request,false);
    }
    private Stored read(String actorId, ClientEnvelope request, boolean replayOnly) {
        Object raw = redis.opsForHash().get(key(actorId), field(request.getRequestId()));
        require(raw!=null || !replayOnly,GameError.REQUEST_EXPIRED);
        if (raw == null) throw new IllegalStateException("Redis 去重占位丢失，拒绝执行");
        Stored stored = json.readValue(raw.toString(), Stored.class);
        require(stored.request().equals(encode(request)), GameError.OPERATION_CONFLICT);
        return stored;
    }
    /** 本地窗口外只能查询原持久记录；不存在时拒绝旧编号，不能占位成新业务。 */
    public Stored replay(String actorId,ClientEnvelope request) { return read(actorId,request,true); }
    /** Hash 的 field 就是 requestId；response=null 为 PROCESSING，否则为 DONE。活 Actor 不设过期。 */
    public Stored claim(String actorId, ClientEnvelope request) {
        require(request.getRequestId() != 0, GameError.INVALID_INPUT);
        Stored fresh = new Stored(encode(request), null, "", 0, "");
        redis.opsForHash().putIfAbsent(key(actorId), field(request.getRequestId()), json.writeValueAsString(fresh));
        return read(actorId, request);
    }
    /** RPC 前保存固定目标；同请求重试使用原场景，不能把一个 PROCESSING 请求重新随机分配。 */
    public void binding(String actorId, ClientEnvelope request, String instance, long scene, String nickname) {
        Stored old = read(actorId, request);
        if (old.done()) {
            require(old.instance().equals(instance) && old.scene() == scene
                    && old.nickname().equals(nickname), GameError.OPERATION_CONFLICT);
            return; // 离场核实原路由时，不覆盖已经提交的 START_GAME 响应。
        }
        redis.opsForHash().put(key(actorId), field(request.getRequestId()), json.writeValueAsString(
                new Stored(old.request(), null, instance, scene, nickname)));
    }
    /** 单个 field 原子覆盖，写入确认后才能完成本地 Future/回客户端。无需业务 Lua。 */
    public void complete(String actorId, ClientEnvelope request, ServerEnvelope response) {
        Stored old = read(actorId, request);
        if (old.done()) {
            require(old.result().equals(response), GameError.OPERATION_CONFLICT);
            return;
        }
        redis.opsForHash().put(key(actorId), field(request.getRequestId()), json.writeValueAsString(
                new Stored(old.request(), Base64.getEncoder().encodeToString(response.toByteArray()),
                        old.instance(), old.scene(), old.nickname())));
    }
    /** Akka 已确认 Actor 终止且停止结果已提交 Redis 后才允许整个容器到期；不能用 TTL 判断活 Actor 已退出。 */
    public void retired(String actorId) {
        redis.expire(key(actorId),retention);
        redis.expire(lifecycleKey(actorId),retention); // 正常请求缓存有期限，不为重启永久保留。
    }
}
