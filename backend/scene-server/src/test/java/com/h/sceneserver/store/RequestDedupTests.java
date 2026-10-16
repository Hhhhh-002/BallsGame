package com.h.sceneserver.store;

import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.GameCommands;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.Messages;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.actor.SceneActor;
import com.h.sceneserver.actor.SceneEndpoint;
import com.h.sceneserver.config.BattleRules;
import com.h.sceneserver.dispatch.handlers.SceneGameHandler;
import com.h.sceneserver.registries.SceneGameCommandRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 Akka 邮箱与 Redis 存储逻辑；Redis 传输为替身，不测试外部网络。 */
class RequestDedupTests {
    @GameCommand(GameCommands.SPLIT)
    static class CountingHandler implements SceneGameHandler {
        final AtomicInteger calls=new AtomicInteger();
        public void handle(SceneActor scene,String user,DecodedMessage<ClientEnvelope,GameCommands> message) {calls.incrementAndGet();}
    }
    @GameCommand(GameCommands.SHIFT)
    static class TrackingShiftHandler implements SceneGameHandler {
        final CountingHandler earlier;
        volatile int earlierCalls=-1;
        TrackingShiftHandler(CountingHandler earlier) {this.earlier=earlier;}
        public boolean cacheResponse() {return false;}
        public void handle(SceneActor scene,String user,DecodedMessage<ClientEnvelope,GameCommands> message) {
            earlierCalls=earlier.calls.get();
        }
    }
    private ClientEnvelope request(long id) {
        return ClientEnvelope.newBuilder().setCmd(GameCommands.SPLIT.code()).setRequestId(id)
                .setBody(SplitRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
    }
    private ScenePlayerInput input(String user,String actor,long scene,ClientEnvelope request) {
        return ScenePlayerInput.newBuilder().setSceneId(scene).setUserId(user).setPlayerActorId(actor)
                .setJoinOperationId("10").setClientMessage(request).build();
    }
    private void join(SceneEndpoint scene,String user,String actor,long id) throws Exception {
        scene.join("agent",SceneJoinRequest.newBuilder().setSceneId(id).setUserId(user).setPlayerActorId(actor)
                .setJoinOperationId("10").setNickname(user).build()).get(3,TimeUnit.SECONDS);
    }
    private PeerRegistry peers(BlockingQueue<ServerEnvelope> responses) {
        PeerRegistry peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenAnswer(call->{
            ServiceEnvelope envelope=call.getArgument(1);
            ServerEnvelope response=SceneToAgentEvent.parseFrom(envelope.getEvent().getBody()).getClientMessage();
            if(response.getRequestId()!=0)responses.add(response);
            return CompletableFuture.completedFuture(null);
        });return peers;
    }
    private SceneEndpoint scene(long id,SceneEndpoint.TestSystem runtime,SceneRequestStore store,CountingHandler handler,
            BlockingQueue<ServerEnvelope> responses) {
        return new SceneEndpoint(id,"scene",BattleRules.load(new MockEnvironment()),runtime,peers(responses),
                mock(MatchRankingStore.class),store,new SceneGameCommandRegistry(List.of(handler)));
    }
    @Test void sameRequestIdIsIsolatedByPlayerSessionAndMatchAndReplaysAfterStoreRecreation() throws Exception {
        var fixture=new RedisRequestFixture();var handler=new CountingHandler();var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new SceneEndpoint.TestSystem(2)) {
            var scene=scene(7,runtime,fixture.store(),handler,responses);join(scene,"a","actor-a",7);join(scene,"b","actor-b",7);
            scene.input("agent",input("a","actor-a",7,request(100))).get(3,TimeUnit.SECONDS);
            scene.input("agent",input("b","actor-b",7,request(100))).get(3,TimeUnit.SECONDS);
            assertEquals(2,handler.calls.get());
            var restored=scene(7,runtime,fixture.store(),handler,responses);join(restored,"a","actor-a",7);
            restored.input("agent",input("a","actor-a",7,request(100))).get(3,TimeUnit.SECONDS);
            assertEquals(2,handler.calls.get());
            var another=scene(8,runtime,fixture.store(),handler,responses);join(another,"a","actor-a",8);
            another.input("agent",input("a","actor-a",8,request(100))).get(3,TimeUnit.SECONDS);
            assertEquals(3,handler.calls.get());assertEquals(3,fixture.data.size());
            verify(fixture.redis,never()).expire(anyString(),any(java.time.Duration.class));
        }
    }
    @Test void duplicateWaitsForOriginalProcessingFutureWithoutSecondClaimOrExecution() throws Exception {
        var fixture=new RedisRequestFixture();var store=spy(fixture.store());var handler=new CountingHandler();
        var gate=new CompletableFuture<SceneRequestStore.Admission>();
        doReturn(gate).when(store).claim(eq("scene"),eq(7L),eq("actor:10"),any(),eq(false));
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new SceneEndpoint.TestSystem(2)) {
            var scene=scene(7,runtime,store,handler,responses);join(scene,"u","actor",7);
            var first=scene.input("agent",input("u","actor",7,request(100)));
            var second=scene.input("agent",input("u","actor",7,request(100)));
            scene.snapshot(); // 邮箱屏障：两份输入已经登记，不等待 Redis。
            assertEquals(0,handler.calls.get());assertFalse(first.isDone());assertFalse(second.isDone());
            gate.complete(fixture.store().claim("scene",7,"actor:10",request(100),false).join());
            CompletableFuture.allOf(first,second).get(3,TimeUnit.SECONDS);
            assertEquals(1,handler.calls.get());
            verify(store,times(1)).claim(eq("scene"),eq(7L),eq("actor:10"),any(),eq(false));
            assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());
        }
    }
    @Test void responseWriteRetryDoesNotRepeatHandlerAndOrphanProcessingDoesNotExecute() throws Exception {
        var fixture=new RedisRequestFixture();var store=spy(fixture.store());var handler=new CountingHandler();
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("Redis unavailable"))).doCallRealMethod()
                .when(store).complete(anyString(),anyLong(),anyString(),any(),any());
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new SceneEndpoint.TestSystem(2)) {
            var scene=scene(7,runtime,store,handler,responses);join(scene,"u","actor",7);
            scene.input("agent",input("u","actor",7,request(100))).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.INTERNAL_ERROR.code(),responses.poll(3,TimeUnit.SECONDS).getCode());
            scene.input("agent",input("u","actor",7,request(100))).get(3,TimeUnit.SECONDS);
            assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());assertEquals(1,handler.calls.get());
            fixture.store().claim("scene",7,"actor:10",request(101),false).join();
            var recreated=scene(7,runtime,fixture.store(),handler,responses);join(recreated,"u","actor",7);
            recreated.input("agent",input("u","actor",7,request(101))).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.SERVICE_UNAVAILABLE.code(),responses.poll(3,TimeUnit.SECONDS).getCode());
            assertEquals(1,handler.calls.get());
        }
    }
    @Test void failedRedisAdmissionAndStaleMembershipNeverReachHandler() throws Exception {
        var fixture=new RedisRequestFixture();var store=spy(fixture.store());var handler=new CountingHandler();
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("Redis unavailable")))
                .when(store).claim(anyString(),anyLong(),anyString(),any(),anyBoolean());
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new SceneEndpoint.TestSystem(2)) {
            var scene=scene(7,runtime,store,handler,responses);join(scene,"u","actor",7);
            scene.input("agent",input("u","actor",7,request(100))).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.INTERNAL_ERROR.code(),responses.poll(3,TimeUnit.SECONDS).getCode());assertEquals(0,handler.calls.get());
            scene.input("agent",input("u","old-actor",7,request(101))).get(3,TimeUnit.SECONDS);
            assertEquals(0,handler.calls.get());
            verify(store,times(1)).claim(anyString(),anyLong(),anyString(),any(),anyBoolean());
            assertTrue(responses.isEmpty());
        }
    }
    @Test void leavingAndReenteringSameMatchDoesNotShareRetiredMemberContainer() throws Exception {
        var fixture=new RedisRequestFixture();var handler=new CountingHandler();var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new SceneEndpoint.TestSystem(2)) {
            var scene=scene(7,runtime,fixture.store(),handler,responses);join(scene,"u","actor",7);join(scene,"anchor","anchor",7);
            scene.input("agent",input("u","actor",7,request(100))).get(3,TimeUnit.SECONDS);
            scene.leave("agent",SceneLeaveRequest.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor")
                    .setJoinOperationId("10").build()).get(3,TimeUnit.SECONDS);
            scene.join("agent",SceneJoinRequest.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor")
                    .setJoinOperationId("11").setNickname("new-name").build()).get(3,TimeUnit.SECONDS);
            var incoming=input("u","actor",7,request(101)).toBuilder().setJoinOperationId("11").build();
            scene.input("agent",incoming).get(3,TimeUnit.SECONDS);
            assertEquals(2,handler.calls.get());assertEquals(2,fixture.data.size());
            scene.input("agent",input("u","actor",7,request(102))).get(3,TimeUnit.SECONDS);
            assertEquals(2,handler.calls.get()); // 旧入场消息不能进入新成员容器。
            verify(fixture.redis,times(1)).expire(anyString(),eq(java.time.Duration.ofDays(1)));
        }
    }
    @Test void shiftDoesNotOvertakeSamePlayersActionWaitingForRedis() throws Exception {
        var fixture=new RedisRequestFixture();var store=spy(fixture.store());
        var split=new CountingHandler();var shift=new TrackingShiftHandler(split);
        var gate=new CompletableFuture<SceneRequestStore.Admission>();
        doReturn(gate).when(store).claim(eq("scene"),eq(7L),eq("actor:10"),any(),eq(false));
        try(var runtime=new SceneEndpoint.TestSystem(2)) {
            var scene=new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment()),runtime,peers(new LinkedBlockingQueue<>()),
                    mock(MatchRankingStore.class),store,new SceneGameCommandRegistry(List.of(split,shift)));
            join(scene,"u","actor",7);
            var first=scene.input("agent",input("u","actor",7,request(100)));
            var movement=ClientEnvelope.newBuilder().setCmd(GameCommands.SHIFT.code()).setClientSequence(1)
                    .setBody(ShiftRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
            var second=scene.input("agent",input("u","actor",7,movement));
            scene.snapshot();assertEquals(-1,shift.earlierCalls);
            gate.complete(fixture.store().claim("scene",7,"actor:10",request(100),false).join());
            CompletableFuture.allOf(first,second).get(3,TimeUnit.SECONDS);
            assertEquals(1,shift.earlierCalls);
            verify(store,times(1)).claim(anyString(),anyLong(),anyString(),any(),anyBoolean()); // SHIFT 不写 Redis。
        }
    }
}
