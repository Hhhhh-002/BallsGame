package com.h.agentmgrserver.store;

import com.h.agentmgrserver.config.AgentMgrProperties;
import com.h.agentmgrserver.model.*;
import com.h.gamecommon.code.LoginStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 在线集合在本机内存；Redis 只存原请求及结果。不扫描、不恢复在线身份。 */
@Repository
public class PlayerOnlineStore {
    private final Map<String,ManagedPlayer> players=new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String prefix;
    private final Duration resultRetention;
    public PlayerOnlineStore(StringRedisTemplate redis,JsonMapper json,AgentMgrProperties properties) {
        this.redis=redis;this.json=json;prefix=properties.getRedisKeyPrefix();
        resultRetention=Duration.ofMillis(properties.getIdempotencyResultTtlMs());
    }
    private String encoded(String user) {return Base64.getUrlEncoder().withoutPadding().encodeToString(user.getBytes(StandardCharsets.UTF_8));}
    public String lockName(String user) {return prefix+"lock:{"+encoded(user)+"}";}
    public String executionLockName(String user) {return lockName(user)+":execution";}
    private String idempotencyKey(String user,IdempotencyRecord.Type type,String id) {
        return prefix+"idem:{"+encoded(user)+"}:"+type.name()+":"+id;
    }
    /** 找不到就返回 null，不从 Redis 重建玩家身份。调用方持用户级锁。 */
    public ManagedPlayer load(String user) {return players.get(user);}
    public void save(ManagedPlayer player) {players.put(player.userId(),player);}
    public IdempotencyRecord record(String user,IdempotencyRecord.Type type,String id) {
        String value=redis.opsForValue().get(idempotencyKey(user,type,id));if(value==null)return null;
        var record=json.readValue(value,IdempotencyRecord.class);
        if(!user.equals(record.getUserId()) || !id.equals(record.getId()) || type!=record.getType() || record.getStatus()==null)
            throw new IllegalStateException("原请求记录身份不匹配");
        // 正常请求的 SET 已成功但确认丢失：只完成本机已有的那份待处理 Context，绝不补建缺失身份。
        ManagedPlayer current=players.get(user);
        if(record.getStatus()==IdempotencyRecord.Status.DONE && current!=null && id.equals(current.pendingOperationId())) {
            if(type==IdempotencyRecord.Type.DISCONNECT)players.remove(user,current);
            else {
                if(!current.playerActorId().equals(record.getPlayerActorId()))throw new IllegalStateException("原登录结果与本机 Context 不一致");
                players.put(user,new ManagedPlayer(user,LoginStatus.PLAYING,record.getGatewayInstanceId(),
                        record.getAgentHostInstanceId(),record.getPlayerActorId(),id,"",System.currentTimeMillis(),0));
            }
        }
        return record;
    }
    public boolean claim(IdempotencyRecord record) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(idempotencyKey(record.getUserId(),record.getType(),record.getId()),
                json.writeValueAsString(record)));
    }
    /** 一个请求结果只需一次原子 SET；成功确认后再更新已有的内存在线集合。 */
    public void complete(IdempotencyRecord record,ManagedPlayer player,byte[] body) {
        record.setStatus(IdempotencyRecord.Status.DONE);record.setResultBody(Base64.getEncoder().encodeToString(body));
        redis.opsForValue().set(idempotencyKey(record.getUserId(),record.getType(),record.getId()),
                json.writeValueAsString(record),resultRetention);
        if(player==null)players.remove(record.getUserId());else players.put(player.userId(),player);
    }
    public String taskKey(IdempotencyRecord record) {return idempotencyKey(record.getUserId(),record.getType(),record.getId());}
}
