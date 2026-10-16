package com.h.sceneserver.actor;

import com.h.gamecommon.command.GameCommands;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.config.BattleRules;
import com.h.sceneserver.dispatch.handlers.*;
import com.h.sceneserver.registries.SceneGameCommandRegistry;
import com.h.sceneserver.store.MatchRankingStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FoodRefreshTests {
    @Test void defaultsAndInvalidRefreshValuesAreExplicit() {
        var rules=BattleRules.load(new MockEnvironment());
        assertEquals(50,rules.initialFoods());assertEquals(50,rules.maxFoods());
        assertEquals(32,rules.maxPlayers());assertEquals(100f,rules.width());assertEquals(100f,rules.height());
        assertEquals(250,rules.foodIntervalTicks());assertEquals(5,rules.foodRefreshMaxCount());
        assertEquals(13,BattleRules.load(new MockEnvironment().withProperty("battle.food-interval-ms","500")).foodIntervalTicks());
        for(String value:List.of("0","-1","513"))
            assertThrows(IllegalArgumentException.class,()->BattleRules.load(new MockEnvironment().withProperty("battle.food-refresh-max-count",value)));
        assertThrows(IllegalArgumentException.class,()->BattleRules.load(new MockEnvironment().withProperty("battle.food-interval-ms","0")));
        assertThrows(IllegalArgumentException.class,()->BattleRules.load(new MockEnvironment().withProperty("battle.width","19")));
        assertThrows(IllegalArgumentException.class,()->BattleRules.load(new MockEnvironment().withProperty("battle.height","1001")));
        assertThrows(IllegalArgumentException.class,()->BattleRules.load(new MockEnvironment().withProperty("battle.max-players","65")));
    }
    @Test void randomBatchIncludesBothEndpointsAndBroadcastsOneBatch() throws Exception {
        var messages=new LinkedBlockingQueue<ServerEnvelope>();var peers=peers(messages);
        var env=new MockEnvironment().withProperty("battle.initial-foods","0").withProperty("battle.max-foods","20")
                .withProperty("battle.food-refresh-max-count","5");
        try(var runtime=new SceneEndpoint.TestSystem(1)) {
            var scene=scene(7,env,runtime,peers);
            scene.join("agent",join(7,"u")).get(3,TimeUnit.SECONDS);messages.clear();
            var random=mock(SplittableRandom.class);
            when(random.nextInt(1,6)).thenReturn(1,5);
            when(random.nextDouble(anyDouble(),anyDouble())).thenReturn(40d);
            scene.inspect(actor->{ReflectionTestUtils.setField(actor,"random",random);ReflectionTestUtils.invokeMethod(actor,"refreshFoods");return null;});
            assertEquals(1,scene.snapshot().getFoodsCount());
            assertEquals(1,FoodAddedEvent.parseFrom(messages.remove().getBody()).getFoodsCount());assertTrue(messages.isEmpty());
            scene.inspect(actor->{ReflectionTestUtils.invokeMethod(actor,"refreshFoods");return null;});
            assertEquals(6,scene.snapshot().getFoodsCount());
            var broadcast=messages.remove();assertEquals(GameCommands.FOOD_ADDED.code(),broadcast.getCmd());
            assertEquals(5,FoodAddedEvent.parseFrom(broadcast.getBody()).getFoodsCount());assertTrue(messages.isEmpty());
            verify(random,times(2)).nextInt(1,6);
        }
    }
    @Test void partialCapacityClampsBatchAndFullOrZeroCapacityDoesNotSpawn() throws Exception {
        for(int cap:List.of(0,3)) {
            var messages=new LinkedBlockingQueue<ServerEnvelope>();var env=new MockEnvironment()
                    .withProperty("battle.initial-foods",Integer.toString(Math.max(0,cap-1)))
                    .withProperty("battle.max-foods",Integer.toString(cap)).withProperty("battle.food-refresh-max-count","5");
            try(var runtime=new SceneEndpoint.TestSystem(1)) {
                var scene=scene(7,env,runtime,peers(messages));scene.join("agent",join(7,"u")).get(3,TimeUnit.SECONDS);messages.clear();
                var random=mock(SplittableRandom.class);when(random.nextInt(1,6)).thenReturn(5);
                when(random.nextDouble(anyDouble(),anyDouble())).thenReturn(40d);
                scene.inspect(actor->{ReflectionTestUtils.setField(actor,"random",random);ReflectionTestUtils.invokeMethod(actor,"refreshFoods");return null;});
                assertEquals(cap,scene.snapshot().getFoodsCount());assertEquals(cap==0?0:1,messages.size());
                if(cap>0)assertEquals(1,FoodAddedEvent.parseFrom(messages.remove().getBody()).getFoodsCount());
                scene.inspect(actor->{ReflectionTestUtils.invokeMethod(actor,"refreshFoods");return null;});
                assertEquals(cap,scene.snapshot().getFoodsCount());assertTrue(messages.isEmpty());
                verify(random,times(cap==0?0:1)).nextInt(1,6);
            }
        }
    }
    @Test void updatesAreVisibleToNewActorsButExistingMatchesRetainTheirSnapshot() throws Exception {
        var env=new MockEnvironment().withProperty("battle.initial-foods","7").withProperty("battle.max-foods","20")
                .withProperty("battle.max-players","4").withProperty("battle.width","120").withProperty("battle.height","80")
                .withProperty("battle.food-refresh-max-count","2").withProperty("battle.food-interval-ms","1000");
        var firstRules=BattleRules.load(env);var peers=peers(new LinkedBlockingQueue<>());
        try(var runtime=new SceneEndpoint.TestSystem(1)) {
            var first=scene(7,env,runtime,peers);var original=first.snapshot();
            env.withProperty("battle.initial-foods","9").withProperty("battle.max-foods","30")
                    .withProperty("battle.max-players","8").withProperty("battle.width","240").withProperty("battle.height","160")
                    .withProperty("battle.food-refresh-max-count","6").withProperty("battle.food-interval-ms","2000");
            var second=scene(8,env,runtime,peers);var updated=second.snapshot();var secondRules=BattleRules.load(env);
            assertEquals(original,first.snapshot());assertEquals(7,original.getFoodsCount());assertEquals(120f,original.getWidth());
            assertEquals(9,updated.getFoodsCount());assertEquals(240f,updated.getWidth());assertEquals(160f,updated.getHeight());
            assertEquals(4,firstRules.maxPlayers());assertEquals(8,secondRules.maxPlayers());
            assertEquals(20,firstRules.maxFoods());assertEquals(30,secondRules.maxFoods());
            assertEquals(2,firstRules.foodRefreshMaxCount());assertEquals(6,secondRules.foodRefreshMaxCount());
            assertEquals(25,firstRules.foodIntervalTicks());assertEquals(50,secondRules.foodIntervalTicks());
            assertTrue(updated.getFoodsList().stream().allMatch(f->f.getX()>=0&&f.getX()<240&&f.getY()>=0&&f.getY()<160));
        }
    }
    @Test void refreshRunsOnConfiguredTickIntervalAndDoesNotRunWhileWaiting() throws Exception {
        var env=new MockEnvironment().withProperty("battle.initial-foods","0").withProperty("battle.max-foods","20")
                .withProperty("battle.food-interval-ms","80");
        try(var runtime=new SceneEndpoint.TestSystem(1)) {
            var scene=scene(7,env,runtime,peers(new LinkedBlockingQueue<>()));
            scene.join("agent",join(7,"u")).get(3,TimeUnit.SECONDS);
            scene.inspect(actor->{ReflectionTestUtils.setField(actor,"lastNanos",System.nanoTime()-TimeUnit.SECONDS.toNanos(10));
                ReflectionTestUtils.invokeMethod(actor,"advance");return null;});
            assertEquals(0,scene.snapshot().getFoodsCount());assertEquals(0,scene.snapshot().getServerTick());
            scene.join("agent",join(7,"v")).get(3,TimeUnit.SECONDS);
            scene.inspect(actor->{((Map<?,?>)ReflectionTestUtils.getField(actor,"balls")).clear();
                ReflectionTestUtils.invokeMethod(actor,"update");return null;});
            assertEquals(1,scene.snapshot().getServerTick());assertEquals(0,scene.snapshot().getFoodsCount());
            scene.inspect(actor->{ReflectionTestUtils.invokeMethod(actor,"update");return null;});
            assertEquals(2,scene.snapshot().getServerTick());
            int count=scene.snapshot().getFoodsCount();assertTrue(count>=1&&count<=5);
        }
    }
    private SceneEndpoint scene(long id,MockEnvironment env,SceneEndpoint.TestSystem runtime,PeerRegistry peers) {
        return new SceneEndpoint(id,"scene",BattleRules.load(env),runtime,peers,mock(MatchRankingStore.class),
                new SceneGameCommandRegistry(List.of(new ShiftHandler(),new SplitHandler(),new EjectHandler())));
    }
    private SceneJoinRequest join(long id,String user) {
        return SceneJoinRequest.newBuilder().setSceneId(id).setUserId(user).setPlayerActorId("actor-"+user)
                .setJoinOperationId("10").setNickname(user).build();
    }
    private PeerRegistry peers(BlockingQueue<ServerEnvelope> messages) {
        var peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenAnswer(call->{
            ServiceEnvelope envelope=call.getArgument(1);
            messages.add(SceneToAgentEvent.parseFrom(envelope.getEvent().getBody()).getClientMessage());
            return CompletableFuture.completedFuture(null);
        });return peers;
    }
}
