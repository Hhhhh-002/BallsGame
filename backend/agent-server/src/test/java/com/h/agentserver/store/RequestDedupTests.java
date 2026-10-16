package com.h.agentserver.store;

import com.h.agentserver.actor.PlayerActor;
import com.h.agentserver.dispatch.handlers.*;
import com.h.agentserver.registries.*;
import com.h.agentserver.rpc.SceneLinkManager;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.*;
import com.h.gamecore.actor.*;
import com.h.agentserver.actor.AkkaTestSystem;
import com.h.agentserver.actor.PlayerEndpoint;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.*;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真正运行邮箱/上下文/Redis 存储逻辑；Redis 传输用内存替身，不连接外部服务。 */
class RequestDedupTests {
    static ClientEnvelope request(long id) {
        return ClientEnvelope.newBuilder().setCmd(GameCommands.START_GAME.code()).setRequestId(id)
                .setBody(StartGameRequest.newBuilder().setNickname("name").build().toByteString()).build();
    }
    static class RedisFixture {
        final StringRedisTemplate redis=mock(StringRedisTemplate.class);
        final HashOperations<String,Object,Object> hashes=mock(HashOperations.class);
        final Map<String,Map<Object,Object>> data=new ConcurrentHashMap<>();
        RedisFixture() {
            when(redis.opsForHash()).thenReturn(hashes);
            when(hashes.putIfAbsent(anyString(),any(),any())).thenAnswer(call->
                    data.computeIfAbsent(call.getArgument(0),key->new ConcurrentHashMap<>())
                            .putIfAbsent(call.getArgument(1),call.getArgument(2))==null);
            when(hashes.get(anyString(),any())).thenAnswer(call->
                    data.getOrDefault(call.getArgument(0),Map.of()).get(call.getArgument(1)));
            when(hashes.entries(anyString())).thenAnswer(call->new HashMap<>(data.getOrDefault(call.getArgument(0),Map.of())));
            doAnswer(call-> {
                data.computeIfAbsent(call.getArgument(0),key->new ConcurrentHashMap<>())
                        .put(call.getArgument(1),call.getArgument(2));return null;
            }).when(hashes).put(anyString(),any(),any());
            when(hashes.delete(anyString(),any())).thenAnswer(call-> {
                var values=data.get(call.getArgument(0));
                return values!=null && values.remove(call.getArgument(1))!=null?1L:0L;
            });
        }
        PlayerRequestStore store() { return new PlayerRequestStore(redis,new MockEnvironment()); }
    }
    @Test void sharesPendingFutureAndCachesExactDefinitiveFailure() {
        MessageContext context=new MessageContext(2);
        var first=context.begin(request(10),false);
        var duplicate=context.begin(request(10),false);
        assertTrue(first.owner());assertFalse(duplicate.owner());
        assertSame(first.pending().result(),duplicate.pending().result());
        var error=Messages.clientError(request(10),new BattleException(GameError.INVALID_INPUT));
        context.complete(first.pending(),error,false);
        assertEquals(error,duplicate.pending().result().join());
        assertEquals(error,context.begin(request(10),false).pending().result().join());
        var different=request(10).toBuilder().setCmd(GameCommands.LEAVE.code()).build();
        assertThrows(BattleException.class,()->context.begin(different,false));
        assertThrows(BattleException.class,()->context.begin(request(0),false));
    }
    @Test void evictedRequestIsRejectedAndPendingFutureIsNeverEvicted() {
        MessageContext context=new MessageContext(2);
        var waiting=context.begin(request(10),false);
        for(long id=11;id<15;id++) {
            var next=context.begin(request(id),false);
            context.complete(next.pending(),Messages.client(request(id),null),false);
        }
        assertSame(waiting.pending().result(),context.begin(request(10),false).pending().result());
        var expired=assertThrows(BattleException.class,()->context.begin(request(11),false));
        assertEquals(GameError.REQUEST_EXPIRED,expired.error());
        context.complete(waiting.pending(),Messages.client(request(10),null),false);
        assertTrue(waiting.pending().result().isDone());
    }
    @Test void transientFailureRetriesButUncommittedResultDoesNotRepeatBusiness() {
        MessageContext context=new MessageContext(2);
        var first=context.begin(request(10),true);
        var error=Messages.clientError(request(10),new TimeoutException());
        context.complete(first.pending(),error,true);
        var second=context.begin(request(10),true);
        assertTrue(second.owner());assertNull(second.uncommitted());
        var result=Messages.client(request(10),StartGameResponse.newBuilder().setSceneId(7).build());
        context.writeFailed(second.pending(),result,error);
        var retry=context.begin(request(10),true);
        assertEquals(result,retry.uncommitted());
        context.complete(retry.pending(),result,false);
        assertEquals(result,context.begin(request(10),true).pending().result().join());
    }
    @Test void redisSurvivesStoreRecreationAndScopesSameRequestIdToEachActor() {
        RedisFixture fixture=new RedisFixture();
        var store=fixture.store();
        assertFalse(store.claim("actor-a",request(10)).done());
        store.binding("actor-a",request(10),"scene",7,"name");
        var result=Messages.client(request(10),StartGameResponse.newBuilder().setSceneId(7).build());
        store.complete("actor-a",request(10),result);
        var restored=fixture.store().claim("actor-a",request(10));
        assertTrue(restored.done());assertEquals(result,restored.result());assertEquals(7,restored.scene());
        assertFalse(store.claim("actor-b",request(10)).done());
        var conflict=request(10).toBuilder().setBody(StartGameRequest.newBuilder().setNickname("other").build().toByteString()).build();
        assertThrows(BattleException.class,()->store.claim("actor-a",conflict));
        verify(fixture.redis,never()).expire(anyString(),any(java.time.Duration.class));
        store.retired("actor-a");
        verify(fixture.redis,times(2)).expire(anyString(),eq(java.time.Duration.ofDays(1)));
    }
    @Test void creationAndOriginalStartAreDedupRecordsWithoutSeparateSceneMetadata() {
        var fixture=new RedisFixture();var store=fixture.store();
        var login=CreatePlayerAgentRequest.newBuilder().setUserId("u").setPlayerActorId("actor")
                .setGatewayInstanceId("gw").setLoginOperationId("9").build();
        store.claimCreate(login,"agent");store.claimCreate(login,"agent");
        assertEquals("9",fixture.store().creation("actor").body().getLoginOperationId());
        assertThrows(BattleException.class,()->store.claimCreate(login.toBuilder().setLoginOperationId("8").build(),"agent"));
        store.claim("actor",request(10));store.binding("actor",request(10),"scene",7,"name");
        var restored=fixture.store().claim("actor",request(10));
        assertEquals(request(10),restored.envelope());assertEquals("scene",restored.instance());assertEquals(7,restored.scene());
        assertEquals("9",fixture.store().creation("actor").body().getLoginOperationId());
        assertFalse(fixture.store().claim("actor",request(10)).done()); // 请求记录保存原调用目标，但不另存当前场景绑定。
        assertThrows(NullPointerException.class,()->java.util.Objects.requireNonNull(store.creation("unknown-actor")));
        verify(fixture.hashes,never()).put(anyString(),eq("_scene"),any());
    }
    @Test void completedStopReplaysRedisResponseAndRefusesLateCreate() {
        var fixture=new RedisFixture();var store=fixture.store();
        var login=CreatePlayerAgentRequest.newBuilder().setUserId("u").setPlayerActorId("actor")
                .setGatewayInstanceId("gw").setLoginOperationId("9").build();
        store.claimCreate(login,"agent");
        var result=CreatePlayerAgentResult.newBuilder().setPlayerActorId("actor").setAgentHostInstanceId("agent")
                .setAgentHostServiceName("agent-server").setPlayer(PlayerPublicInfo.newBuilder().setNickname("玩家")).build();
        store.created("actor",result);
        assertNotNull(fixture.store().claimCreate(login,"agent").record().response());
        var stop=KickOldAgentRequest.newBuilder().setUserId("u").setPlayerActorId("actor").build();
        store.claimStop(stop);assertTrue(fixture.store().stopping("actor"));
        var stopped=KickOldAgentResult.newBuilder().setUserId("u").setPlayerActorId("actor")
                .setOutcome(KickOldAgentResult.Outcome.SAVED_AND_STOPPED).build();
        store.stopped("actor",stopped);
        assertEquals(stopped,fixture.store().stopResult("actor"));
        var conflict=assertThrows(BattleException.class,()->fixture.store().claimCreate(login,"agent"));
        assertEquals(GameError.LOGIN_OPERATION_SUPERSEDED,conflict.error());
        assertThrows(BattleException.class,()->fixture.store().claimStop(stop.toBuilder().setUserId("other").build()));
    }
    @Test void historyUsesContextUserAndDoesNotCacheAStaleQueryResponse() throws Exception {
        var fixture=new RedisFixture();var scenes=mock(SceneLinkManager.class);
        when(scenes.select()).thenReturn(CompletableFuture.completedFuture("scene"));
        var history=MatchHistoryResponse.newBuilder().addRecords(MatchHistoryItem.newBuilder().setMatchId("scene:7").setNickname("本局名").setRank(1)).build();
        when(scenes.request(eq("scene"),eq(ServiceCommands.MATCH_HISTORY),any())).thenReturn(CompletableFuture.completedFuture(
                ServiceResponse.newBuilder().setBody(history.toByteString()).build()));
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new AkkaTestSystem(1)) {
            var actor=actor(runtime,fixture.store(),new MatchHistoryHandler(),scenes,responses);
            var query=ClientEnvelope.newBuilder().setCmd(GameCommands.MATCH_HISTORY.code()).setRequestId(50)
                    .setBody(MatchHistoryRequest.newBuilder().setLimit(20).build().toByteString()).build();
            actor.forward("gw",query).get(3,TimeUnit.SECONDS);actor.forward("gw",query).get(3,TimeUnit.SECONDS);
            for(int i=0;i<2;i++)assertEquals(history,MatchHistoryResponse.parseFrom(responses.poll(3,TimeUnit.SECONDS).getBody()));
            verify(scenes,times(2)).request(eq("scene"),eq(ServiceCommands.MATCH_HISTORY),argThat(body->
                    body instanceof SceneMatchHistoryRequest request && request.getUserId().equals("u")));
            assertTrue(fixture.data.isEmpty());
        }
    }
    @Test void livePlayerStopsThroughItsContextSceneWithoutMysqlRoutingColumns() throws Exception {
        var fixture=new RedisFixture();var store=fixture.store();
        store.claim("actor",request(10));store.binding("actor",request(10),"original-scene",7,"name");
        store.complete("actor",request(10),Messages.client(request(10),StartGameResponse.newBuilder().setSceneId(7).build()));
        var scenes=mock(SceneLinkManager.class);
        when(scenes.request(eq("original-scene"),eq(ServiceCommands.SCENE_LEAVE),any())).thenReturn(
                CompletableFuture.completedFuture(ServiceResponse.newBuilder().setBody(SceneLeaveResult.newBuilder()
                        .setOutcome(SceneLeaveResult.Outcome.LEFT).build().toByteString()).build()));
        try(var runtime=new AkkaTestSystem(1)) {
            var actor=actor(runtime,fixture.store(),new StartGameHandler(),scenes,new LinkedBlockingQueue<>());
            actor.seedScene(request(10),"original-scene",7,"name").get(3,TimeUnit.SECONDS);
            AkkaSupport.<Void>ask(actor.ref,PlayerActor.PrepareStop.INSTANCE).get(3,TimeUnit.SECONDS);
            assertTrue(store.claim("actor",request(10)).done());
            verify(scenes,never()).select();
            verify(scenes).request(eq("original-scene"),eq(ServiceCommands.SCENE_LEAVE),argThat(body->
                    body instanceof SceneLeaveRequest leave && leave.getSceneId()==7 && leave.getJoinOperationId().equals("10")));
        }
    }
    @GameCommand(GameCommands.START_GAME)
    static class CountingHandler implements AgentGameHandler {
        final AtomicInteger calls=new AtomicInteger();
        Throwable firstFailure;
        public CompletableFuture<ServerEnvelope> handle(PlayerActor actor,DecodedMessage<ClientEnvelope,GameCommands> message) {
            int n=calls.incrementAndGet();
            if(n==1 && firstFailure!=null)return CompletableFuture.failedFuture(firstFailure);
            return CompletableFuture.completedFuture(Messages.client(message.envelope(),StartGameResponse.newBuilder().setSceneId(7).build()));
        }
    }
    static CreatePlayerAgentRequest.Builder lease() {
        var lease=CreatePlayerAgentRequest.newBuilder();lease.setPlayerActorId("actor");lease.setUserId("u");lease.setGatewayInstanceId("gw");
        return lease;
    }
    static PeerRegistry peers(BlockingQueue<ServerEnvelope> responses) {
        var peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenAnswer(call-> {
            ServiceEnvelope envelope=call.getArgument(1);
            responses.add(ForwardToClientEvent.parseFrom(envelope.getEvent().getBody()).getClientMessage());
            return CompletableFuture.completedFuture(null);
        });return peers;
    }
    static PlayerEndpoint actor(AkkaTestSystem runtime,PlayerRequestStore store,AgentGameHandler handler,
            SceneLinkManager scenes,BlockingQueue<ServerEnvelope> responses) {
        return new PlayerEndpoint(lease(),runtime,scenes,
                peers(responses),store,Runnable::run,
                new AgentGameCommandRegistry(List.of(handler)),new AgentSceneMessageRegistry(List.of(new ForwardSceneMessageHandler(),new MatchEndedHandler())));
    }
    @Test void recreatedActorReplaysRedisResultWithoutCallingHandlerAgain() throws Exception {
        var fixture=new RedisFixture();var handler=new CountingHandler();
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new AkkaTestSystem(1)) {
            var first=actor(runtime,fixture.store(),handler,mock(SceneLinkManager.class),responses);
            first.forward("gw",request(10)).get(3,TimeUnit.SECONDS);
            assertNotNull(responses.poll(3,TimeUnit.SECONDS));
            var restored=actor(runtime,fixture.store(),handler,mock(SceneLinkManager.class),responses);
            restored.forward("gw",request(10)).get(3,TimeUnit.SECONDS);
            assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());assertEquals(1,handler.calls.get());
            restored.forward("gw",request(11)).get(3,TimeUnit.SECONDS);
            assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());assertEquals(2,handler.calls.get());
        }
    }
    @Test void definitiveErrorIsDurableButTimeoutCanResumeSameRequest() throws Exception {
        var fixture=new RedisFixture();var handler=new CountingHandler();
        handler.firstFailure=new BattleException(GameError.INVALID_INPUT);
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new AkkaTestSystem(1)) {
            actor(runtime,fixture.store(),handler,mock(SceneLinkManager.class),responses).forward("gw",request(10)).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.INVALID_INPUT.code(),responses.poll(3,TimeUnit.SECONDS).getCode());
            actor(runtime,fixture.store(),handler,mock(SceneLinkManager.class),responses).forward("gw",request(10)).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.INVALID_INPUT.code(),responses.poll(3,TimeUnit.SECONDS).getCode());assertEquals(1,handler.calls.get());
            handler=new CountingHandler();handler.firstFailure=new TimeoutException();
            var actor=actor(runtime,fixture.store(),handler,mock(SceneLinkManager.class),responses);
            actor.forward("gw",request(11)).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.SERVICE_UNAVAILABLE.code(),responses.poll(3,TimeUnit.SECONDS).getCode());
            assertFalse(fixture.store().claim("actor",request(11)).done());
            actor.forward("gw",request(11)).get(3,TimeUnit.SECONDS);
            assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());assertEquals(2,handler.calls.get());
        }
    }
    @Test void redisWriteFailureRetriesCommitOnlyAndDoesNotPoisonOriginalRequest() throws Exception {
        var fixture=new RedisFixture();var store=spy(fixture.store());var handler=new CountingHandler();
        doThrow(new IllegalStateException("Redis unavailable")).doCallRealMethod().when(store).complete(eq("actor"),any(),any());
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new AkkaTestSystem(1)) {
            var actor=actor(runtime,store,handler,mock(SceneLinkManager.class),responses);
            actor.forward("gw",request(10)).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.INTERNAL_ERROR.code(),responses.poll(3,TimeUnit.SECONDS).getCode());
            actor.forward("gw",request(10)).get(3,TimeUnit.SECONDS);
            assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());assertEquals(1,handler.calls.get());
            var restored=actor(runtime,fixture.store(),handler,mock(SceneLinkManager.class),responses);
            var wrong=request(10).toBuilder().setBody(StartGameRequest.newBuilder().setNickname("wrong").build().toByteString()).build();
            restored.forward("gw",wrong).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.OPERATION_CONFLICT.code(),responses.poll(3,TimeUnit.SECONDS).getCode());
            restored.forward("gw",request(10)).get(3,TimeUnit.SECONDS);
            assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());assertEquals(1,handler.calls.get());
        }
    }
    @Test void processingAfterRestartUsesOriginalSceneInsteadOfSelectingAnother() throws Exception {
        var fixture=new RedisFixture();var store=fixture.store();var request=request(10);
        store.claim("actor",request);store.binding("actor",request,"original-scene",7,"name");
        var scenes=mock(SceneLinkManager.class);
        when(scenes.request(eq("original-scene"),eq(ServiceCommands.SCENE_JOIN),any())).thenReturn(
                CompletableFuture.completedFuture(ServiceResponse.newBuilder().setBody(SceneJoinResult.newBuilder().setSceneId(7).build().toByteString()).build()));
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new AkkaTestSystem(1)) {
            actor(runtime,store,new StartGameHandler(),scenes,responses).forward("gw",request).get(3,TimeUnit.SECONDS);
            var response=responses.poll(3,TimeUnit.SECONDS);assertNotNull(response);assertEquals(0,response.getCode());
            assertEquals(7,StartGameResponse.parseFrom(response.getBody()).getSceneId());
            verify(scenes,never()).select();verify(scenes,never()).request(anyString(),eq(ServiceCommands.FIND_SCENE),any());
            verify(scenes).request(eq("original-scene"),eq(ServiceCommands.SCENE_JOIN),argThat(body->
                    body instanceof SceneJoinRequest join && join.getJoinOperationId().equals("10")));
        }
    }

    @Test void realStartAndLeavePreserveCommittedStartResultWithoutRejoining() throws Exception {
        var fixture=new RedisFixture();var store=fixture.store();
        var scenes=mock(SceneLinkManager.class);
        when(scenes.select()).thenReturn(CompletableFuture.completedFuture("scene"));
        when(scenes.request(eq("scene"),eq(ServiceCommands.FIND_SCENE),any())).thenReturn(
                CompletableFuture.completedFuture(ServiceResponse.newBuilder().setBody(CreateSceneResult.newBuilder()
                        .setSceneId(7).setSceneHostInstanceId("scene").setSceneHostServiceName("scene-server").build().toByteString()).build()));
        when(scenes.request(eq("scene"),eq(ServiceCommands.SCENE_JOIN),any())).thenReturn(
                CompletableFuture.completedFuture(ServiceResponse.newBuilder().setBody(SceneJoinResult.newBuilder().setSceneId(7).build().toByteString()).build()));
        when(scenes.request(eq("scene"),eq(ServiceCommands.SCENE_LEAVE),any())).thenReturn(
                CompletableFuture.completedFuture(ServiceResponse.newBuilder().setBody(SceneLeaveResult.newBuilder()
                        .setOutcome(SceneLeaveResult.Outcome.LEFT).build().toByteString()).build()));
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new AkkaTestSystem(1)) {
            var actor=new PlayerEndpoint(lease(),runtime,scenes,
                    peers(responses),store,Runnable::run,
                    new AgentGameCommandRegistry(List.of(new StartGameHandler(),new LeaveGameHandler())),
                    new AgentSceneMessageRegistry(List.of(new ForwardSceneMessageHandler(),new MatchEndedHandler())));
            actor.forward("gw",request(10)).get(3,TimeUnit.SECONDS);
            var started=responses.poll(3,TimeUnit.SECONDS);assertNotNull(started);assertEquals(0,started.getCode());
            var leave=ClientEnvelope.newBuilder().setCmd(GameCommands.LEAVE.code()).setRequestId(11)
                    .setBody(LeaveRequest.getDefaultInstance().toByteString()).build();
            actor.forward("gw",leave).get(3,TimeUnit.SECONDS);
            assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());
            actor.forward("gw",leave).get(3,TimeUnit.SECONDS);
            assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());
            actor.forward("gw",request(10)).get(3,TimeUnit.SECONDS);
            assertEquals(started,responses.poll(3,TimeUnit.SECONDS));
            verify(scenes,times(1)).request(anyString(),eq(ServiceCommands.SCENE_JOIN),any());
            verify(scenes,times(1)).request(anyString(),eq(ServiceCommands.SCENE_LEAVE),any());
            assertEquals(started,store.claim("actor",request(10)).result());
            assertTrue(store.claim("actor",leave).done());
            verify(scenes).request(eq("scene"),eq(ServiceCommands.FIND_SCENE),argThat(body->
                    body instanceof FindSceneRequest find && find.getOperationId().equals("10") && find.getPlayerActorId().equals("actor")));
            verify(scenes).request(eq("scene"),eq(ServiceCommands.SCENE_JOIN),argThat(body->
                    body instanceof SceneJoinRequest join && join.getJoinOperationId().equals("10")));
        }
    }

    @Test void forwardedActionWaitsForSceneResponseAndOldRetryCannotActInNewScene() throws Exception {
        var fixture=new RedisFixture();var store=fixture.store();var scenes=mock(SceneLinkManager.class);
        when(scenes.inputResponseTimeoutMs()).thenReturn(60000L);
        when(scenes.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        when(scenes.select()).thenReturn(CompletableFuture.completedFuture("new-scene"));
        when(scenes.request(anyString(),eq(ServiceCommands.SCENE_LEAVE),any())).thenReturn(
                CompletableFuture.completedFuture(ServiceResponse.newBuilder().setBody(SceneLeaveResult.newBuilder()
                        .setOutcome(SceneLeaveResult.Outcome.LEFT).build().toByteString()).build()));
        when(scenes.request(eq("new-scene"),eq(ServiceCommands.FIND_SCENE),any())).thenReturn(
                CompletableFuture.completedFuture(ServiceResponse.newBuilder().setBody(CreateSceneResult.newBuilder()
                        .setSceneId(8).setSceneHostInstanceId("new-scene").setSceneHostServiceName("scene-server").build().toByteString()).build()));
        when(scenes.request(eq("new-scene"),eq(ServiceCommands.SCENE_JOIN),any())).thenReturn(
                CompletableFuture.completedFuture(ServiceResponse.newBuilder().setBody(SceneJoinResult.newBuilder().setSceneId(8).build().toByteString()).build()));
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new AkkaTestSystem(1)) {
            var lease=lease();store.claim("actor",request(10));store.binding("actor",request(10),"scene",7,"name");
            var actor=new PlayerEndpoint(lease,runtime,scenes,
                    peers(responses),store,Runnable::run,
                    new AgentGameCommandRegistry(List.of(new StartGameHandler(),new LeaveGameHandler(),new SplitHandler(),new EjectHandler())),
                    new AgentSceneMessageRegistry(List.of(new ForwardSceneMessageHandler(),new MatchEndedHandler())));
            actor.seedScene(request(10),"scene",7,"name").get(3,TimeUnit.SECONDS);
            var split=ClientEnvelope.newBuilder().setCmd(GameCommands.SPLIT.code()).setRequestId(20)
                    .setBody(SplitRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
            var waiting=actor.forward("gw",split);
            verify(scenes,timeout(3000)).send(eq("scene"),any());
            assertFalse(waiting.isDone());assertTrue(responses.isEmpty()); // 写入成功不能冒充业务成功。
            actor.forward("gw",split).get(3,TimeUnit.SECONDS);
            verify(scenes,times(1)).send(anyString(),any());
            var leave=ClientEnvelope.newBuilder().setCmd(GameCommands.LEAVE.code()).setRequestId(21)
                    .setBody(LeaveRequest.getDefaultInstance().toByteString()).build();
            actor.forward("gw",leave).get(3,TimeUnit.SECONDS);
            assertEquals(21,responses.poll(3,TimeUnit.SECONDS).getRequestId());
            actor.forward("gw",request(22)).get(3,TimeUnit.SECONDS);
            assertEquals(22,responses.poll(3,TimeUnit.SECONDS).getRequestId());
            var result=Messages.client(split,null);
            var event=SceneToAgentEvent.newBuilder().setSceneId(7).setClientMessage(result).build();
            actor.sceneEvent("wrong-scene",event,"10");
            actor.forward("gw",split).get(3,TimeUnit.SECONDS); // 邮箱屏障，错误来源仍不完成 Future。
            assertFalse(waiting.isDone());
            actor.sceneEvent("scene",event,"10");
            waiting.get(3,TimeUnit.SECONDS);
            for(int i=0;i<3;i++)assertEquals(result,responses.poll(3,TimeUnit.SECONDS));
            actor.forward("gw",split).get(3,TimeUnit.SECONDS);
            assertEquals(result,responses.poll(3,TimeUnit.SECONDS));
            verify(scenes,times(1)).send(anyString(),any());
            assertEquals(3,fixture.data.values().stream().flatMap(values->values.keySet().stream())
                    .filter(key->key.toString().matches("[0-9]+")).count()); // 两次 START 与一次 LEAVE，不存动作。
        }
    }

    @Test void actionTimeoutReleasesProcessingAndOriginalSceneRetryCanFinish() throws Exception {
        var store=mock(PlayerRequestStore.class);
        var scenes=mock(SceneLinkManager.class);when(scenes.inputResponseTimeoutMs()).thenReturn(20L);
        when(scenes.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        try(var runtime=new AkkaTestSystem(1)) {
            var lease=lease();
            var actor=new PlayerEndpoint(lease,runtime,scenes,
                    peers(responses),store,Runnable::run,
                    new AgentGameCommandRegistry(List.of(new SplitHandler())),
                    new AgentSceneMessageRegistry(List.of(new ForwardSceneMessageHandler(),new MatchEndedHandler())));
            actor.seedScene(request(10),"scene",7,"name").get(3,TimeUnit.SECONDS);
            var split=ClientEnvelope.newBuilder().setCmd(GameCommands.SPLIT.code()).setRequestId(20)
                    .setBody(SplitRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
            actor.forward("gw",split).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.SERVICE_UNAVAILABLE.code(),responses.poll(3,TimeUnit.SECONDS).getCode());
            when(scenes.inputResponseTimeoutMs()).thenReturn(60000L);
            var retry=actor.forward("gw",split);
            verify(scenes,timeout(3000).times(2)).send(eq("scene"),any());
            var result=Messages.client(split,null);
            actor.sceneEvent("scene",SceneToAgentEvent.newBuilder().setSceneId(7).setClientMessage(result).build(),"10");
            retry.get(3,TimeUnit.SECONDS);
            assertEquals(result,responses.poll(3,TimeUnit.SECONDS));
        }
    }
}
