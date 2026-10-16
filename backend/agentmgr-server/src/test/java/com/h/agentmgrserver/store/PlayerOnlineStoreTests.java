package com.h.agentmgrserver.store;

import com.h.agentmgrserver.config.AgentMgrProperties;
import com.h.agentmgrserver.model.*;
import com.h.gamecommon.code.LoginStatus;
import com.h.gameprotocol.proto.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只验证正常请求去重与内存在线状态；Redis 为离线替身，不测试重启恢复。 */
class PlayerOnlineStoreTests {
    static class Fixture {
        final StringRedisTemplate redis=mock(StringRedisTemplate.class);
        final ValueOperations<String,String> values=mock(ValueOperations.class);
        final Map<String,String> data=new ConcurrentHashMap<>();
        final Map<String,Duration> ttl=new ConcurrentHashMap<>();
        boolean loseAck;
        Fixture() {
            when(redis.opsForValue()).thenReturn(values);
            when(values.get(anyString())).thenAnswer(c->data.get(c.getArgument(0)));
            when(values.setIfAbsent(anyString(),anyString())).thenAnswer(c->data.putIfAbsent(c.getArgument(0),c.getArgument(1))==null);
            doAnswer(c-> {
                data.put(c.getArgument(0),c.getArgument(1));ttl.put(c.getArgument(0),c.getArgument(2));
                if(loseAck) {loseAck=false;throw new IllegalStateException("模拟 SET 成功但确认丢失");}
                return null;
            }).when(values).set(anyString(),anyString(),any(Duration.class));
        }
        PlayerOnlineStore store() {return new PlayerOnlineStore(redis,JsonMapper.builder().build(),new AgentMgrProperties());}
    }
    static IdempotencyRecord login(String user,String id) {
        var r=new IdempotencyRecord();r.setUserId(user);r.setId(id);r.setType(IdempotencyRecord.Type.LOGIN);
        r.setGatewayInstanceId("gw");r.setAgentHostInstanceId("agent");r.setPlayerActorId("player-"+id);r.setDeadline(100000);return r;
    }
    static byte[] binding(IdempotencyRecord r) {
        return LoginBinding.newBuilder().setUserId(r.getUserId()).setLoginOperationId(r.getId()).setPlayerActorId(r.getPlayerActorId())
                .setAgentHostInstanceId(r.getAgentHostInstanceId()).setAgentHostServiceName("agent-server").build().toByteArray();
    }
    static ManagedPlayer playing(IdempotencyRecord r) {
        return new ManagedPlayer(r.getUserId(),LoginStatus.PLAYING,r.getGatewayInstanceId(),r.getAgentHostInstanceId(),r.getPlayerActorId(),r.getId(),"",1,0);
    }
    @Test void onlineStateOnlyLivesInMemoryAndLoadingMissingUserDoesNotReadRedis() {
        var f=new Fixture();var store=f.store();assertNull(store.load("a@example.com"));
        verifyNoInteractions(f.redis);
        var r=login("a@example.com","1002");store.save(playing(r));assertEquals(playing(r),store.load(r.getUserId()));
        verifyNoInteractions(f.redis);
    }
    @Test void doneIsCachedWithoutCreatingOnlineHashOrAutomaticallyRestoringIdentity() {
        var f=new Fixture();var store=f.store();var r=login("a@example.com","1002");assertTrue(store.claim(r));
        store.complete(r,playing(r),binding(r));
        assertEquals(playing(r),store.load(r.getUserId()));
        assertEquals(IdempotencyRecord.Status.DONE,store.record(r.getUserId(),r.getType(),r.getId()).getStatus());
        assertEquals(1,f.ttl.size());assertEquals(Duration.ofMinutes(10),f.ttl.values().iterator().next());
        var emptyStore=f.store();assertNull(emptyStore.load(r.getUserId()));
        emptyStore.record(r.getUserId(),r.getType(),r.getId());assertNull(emptyStore.load(r.getUserId()));
        verify(f.redis,never()).opsForHash();verify(f.redis,never()).scan(any(ScanOptions.class));
    }
    @Test void logoutRemovesOnlyMemoryPlayerAndKeepsNormalDedupResult() {
        var f=new Fixture();var store=f.store();var r=login("a@example.com","1002");store.claim(r);store.complete(r,playing(r),binding(r));
        var stop=new IdempotencyRecord();stop.setUserId(r.getUserId());stop.setId(r.getPlayerActorId());
        stop.setType(IdempotencyRecord.Type.DISCONNECT);stop.setOldPlayerActorId(r.getPlayerActorId());
        store.claim(stop);store.complete(stop,null,PlayerDisconnectedResult.newBuilder().setOutcome(PlayerDisconnectedResult.Outcome.SAVED_AND_OFFLINE).build().toByteArray());
        assertNull(store.load(r.getUserId()));
        assertEquals(IdempotencyRecord.Status.DONE,store.record(r.getUserId(),stop.getType(),stop.getId()).getStatus());
        assertNull(store.load(r.getUserId()));verify(f.redis,never()).scan(any(ScanOptions.class));
    }
    @Test void lostSetAcknowledgementOnlyFinishesExistingPendingContext() {
        var f=new Fixture();var store=f.store();var r=login("a@example.com","1002");store.claim(r);
        store.save(new ManagedPlayer(r.getUserId(),LoginStatus.LOGINING,"gw","agent",r.getPlayerActorId(),r.getId(),r.getId(),1,100000));
        f.loseAck=true;assertThrows(IllegalStateException.class,()->store.complete(r,playing(r),binding(r)));
        assertEquals(LoginStatus.LOGINING,store.load(r.getUserId()).status());
        assertEquals(IdempotencyRecord.Status.DONE,store.record(r.getUserId(),r.getType(),r.getId()).getStatus());
        assertEquals(LoginStatus.PLAYING,store.load(r.getUserId()).status());
    }
    @Test void sameRequestIdForDifferentUsersIsIsolatedAndOldResultCannotChangeNewContext() {
        var f=new Fixture();var store=f.store();var first=login("a@example.com","1002");var other=login("b@example.com","1002");
        store.claim(first);store.complete(first,playing(first),binding(first));store.claim(other);store.complete(other,playing(other),binding(other));
        var next=login(first.getUserId(),"1003");store.claim(next);store.complete(next,playing(next),binding(next));
        store.record(first.getUserId(),first.getType(),first.getId());
        assertEquals("player-1003",store.load(first.getUserId()).playerActorId());
        assertEquals("player-1002",store.load(other.getUserId()).playerActorId());
    }
}
