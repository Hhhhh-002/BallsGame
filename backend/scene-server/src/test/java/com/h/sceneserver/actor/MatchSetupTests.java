package com.h.sceneserver.actor;

import com.h.gamecommon.code.GameError;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.BattleException;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.config.BattleRules;
import com.h.sceneserver.dispatch.handlers.*;
import com.h.sceneserver.registries.SceneGameCommandRegistry;
import com.h.sceneserver.store.MatchRankingStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MatchSetupTests {
    private SceneJoinRequest join(String user) {
        return SceneJoinRequest.newBuilder().setSceneId(7).setUserId(user).setPlayerActorId("actor-"+user)
                .setJoinOperationId("10").setNickname(user).build();
    }
    private SceneLeaveRequest leave(String user) {
        return SceneLeaveRequest.newBuilder().setSceneId(7).setUserId(user).setPlayerActorId("actor-"+user)
                .setJoinOperationId("10").build();
    }
    private PeerRegistry peers() {
        var peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        return peers;
    }
    private SceneEndpoint scene(SceneEndpoint.TestSystem runtime,MatchRankingStore rankings) {
        return new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment()),runtime,peers(),rankings,
                new SceneGameCommandRegistry(java.util.List.of(new ShiftHandler(),new SplitHandler(),new EjectHandler())));
    }
    @Test void initialSnapshotAlreadyContainsRandomFoodAndSinglePlayerDoesNotAdvance() throws Exception {
        var rankings=mock(MatchRankingStore.class);
        try(var runtime=new SceneEndpoint.TestSystem(1)) {
            var scene=scene(runtime,rankings);
            scene.join("agent",join("a")).get(3,TimeUnit.SECONDS);
            var before=scene.snapshot();
            assertEquals(50,before.getFoodsCount());assertFalse(before.getStarted());
            assertEquals(1,before.getPlayerCount());assertEquals(2,before.getMinPlayersToStart());
            assertEquals(50,before.getFoodsList().stream().map(FoodState::getFoodId).distinct().count());
            assertTrue(before.getFoodsList().stream().allMatch(f->f.getX()>=0 && f.getX()<=100 && f.getY()>=0 && f.getY()<=100));
            assertTrue(before.getFoodsList().stream().map(FoodState::getX).distinct().count()>1);
            scene.inspect(actual->{
                ReflectionTestUtils.setField(actual,"lastNanos",System.nanoTime()-TimeUnit.SECONDS.toNanos(20));
                ReflectionTestUtils.invokeMethod(actual,"advance");return null;
            });
            assertEquals(before,scene.snapshot());verifyNoInteractions(rankings);
            scene.join("agent",join("a")).get(3,TimeUnit.SECONDS);
            assertEquals(before,scene.snapshot()); // 重试入场不重复生成食物。
            var code=scene.inspect(actual->{
                try {actual.splitInput("a",SplitRequest.getDefaultInstance());return 0;}
                catch(BattleException waiting){return waiting.error().code();}
            });
            assertEquals(GameError.MATCH_WAITING.code(),code);
        }
    }
    @Test void secondMemberStartsMatchAndLaterLeaveDoesNotPauseIt() throws Exception {
        try(var runtime=new SceneEndpoint.TestSystem(1)) {
            var scene=scene(runtime,mock(MatchRankingStore.class));
            scene.join("agent",join("a")).get(3,TimeUnit.SECONDS);
            assertEquals(0L,scene.<Long>inspect(actual->(Long)ReflectionTestUtils.getField(actual,"startedAtMs")).longValue());
            scene.join("agent",join("b")).get(3,TimeUnit.SECONDS);
            assertTrue(scene.snapshot().getStarted());assertEquals(2,scene.snapshot().getPlayerCount());
            long start=scene.inspect(actual->(Long)ReflectionTestUtils.getField(actual,"startedAtMs"));
            assertTrue(start>0);
            scene.leave("agent",leave("b")).get(3,TimeUnit.SECONDS);
            assertTrue(scene.snapshot().getStarted());assertEquals(1,scene.snapshot().getPlayerCount());
            assertEquals(start,scene.<Long>inspect(actual->(Long)ReflectionTestUtils.getField(actual,"startedAtMs")).longValue());
        }
    }
    @Test void leavingAnUnstartedRoomDoesNotSaveAFakeMatch() throws Exception {
        var rankings=mock(MatchRankingStore.class);
        try(var runtime=new SceneEndpoint.TestSystem(1)) {
            var scene=scene(runtime,rankings);
            scene.join("agent",join("a")).get(3,TimeUnit.SECONDS);
            scene.leave("agent",leave("a")).get(3,TimeUnit.SECONDS);
            scene.inspect(actual->{ReflectionTestUtils.invokeMethod(actual,"advance");return null;});
            assertFalse(scene.snapshot().getStarted());assertEquals(0,scene.snapshot().getPlayerCount());
            assertFalse(scene.<Boolean>inspect(actual->(Boolean)ReflectionTestUtils.getField(actual,"finishing")).booleanValue());
            verifyNoInteractions(rankings);
        }
    }
    @Test void validatesFoodAndMinimumPlayerConfiguration() {
        assertThrows(IllegalArgumentException.class,()->BattleRules.load(new MockEnvironment().withProperty("battle.min-players-to-start","1")));
        assertThrows(IllegalArgumentException.class,()->BattleRules.load(new MockEnvironment().withProperty("battle.initial-foods","51")));
        var rules=BattleRules.load(new MockEnvironment().withProperty("battle.max-foods","10"));
        assertEquals(10,rules.initialFoods());assertEquals(2,rules.minPlayersToStart());
    }
}
