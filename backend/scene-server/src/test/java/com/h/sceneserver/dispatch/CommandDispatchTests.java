package com.h.sceneserver.dispatch;

import com.h.sceneserver.actor.SceneActor;
import com.h.sceneserver.config.BattleRules;
import com.h.sceneserver.dispatch.handlers.*;
import com.h.sceneserver.registries.*;
import com.h.sceneserver.service.SceneDirectory;
import com.h.sceneserver.store.MatchRankingStore;
import com.h.sceneserver.store.RedisRequestFixture;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.*;
import com.h.sceneserver.actor.SceneEndpoint;
import com.h.sceneserver.actor.SceneEndpoint.TestSystem;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 离线验证注解发现、握手/请求/事件分发、成功动作去重和方向序号过滤。 */
class CommandDispatchTests {
    @Test void finalRankingExcludesLeftPlayersButKeepsSpectators() throws Exception {
        var peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        try(var runtime=new TestSystem(1)) {
            var scene=new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment()),runtime,peers,
                    mock(MatchRankingStore.class),new SceneGameCommandRegistry(List.of(new ShiftHandler())));
            for(String user:List.of("left","watching"))scene.join("agent",SceneJoinRequest.newBuilder().setSceneId(7)
                    .setUserId(user).setPlayerActorId("actor-"+user).setJoinOperationId("10").setNickname(user).build()).get(3,TimeUnit.SECONDS);
            scene.leave("agent",SceneLeaveRequest.newBuilder().setSceneId(7).setUserId("left")
                    .setPlayerActorId("actor-left").setJoinOperationId("10").build()).get(3,TimeUnit.SECONDS);
            MatchRanking ranking=scene.inspect(actual->{
                ((Map<?,?>)ReflectionTestUtils.getField(actual,"balls")).clear();
                return ReflectionTestUtils.invokeMethod(actual,"ranking",true,MatchRanking.EndReason.TIME_LIMIT);
            });
            assertEquals(1,ranking.getPlayersCount());assertEquals("watching",ranking.getPlayers(0).getUserId());
            assertEquals(0,ranking.getPlayers(0).getArea());assertFalse(ranking.getPlayers(0).getLeft());
            assertEquals(1,ranking.getPlayers(0).getRank());
        }
    }
    @Test
    void discoversHandlersAndDispatchesRequestsAndEvents() {
        SceneDirectory scenes=mock(SceneDirectory.class);
        PeerRegistry peers=new PeerRegistry();
        when(scenes.join(anyString(),any())).thenReturn(CompletableFuture.completedFuture(SceneJoinResult.newBuilder().setSceneId(7).build()));
        when(scenes.input(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(SceneDirectory.class,()->scenes);
            context.registerBean(PeerRegistry.class,()->peers);
            var history=mock(com.h.sceneserver.store.MatchHistoryRepository.class);
            when(history.history(anyString(),anyInt(),anyString())).thenReturn(new com.h.sceneserver.store.MatchHistoryRepository.Page(List.of(),""));
            context.registerBean(com.h.sceneserver.store.MatchHistoryRepository.class,()->history);
            var io=mock(ExecutorService.class);
            doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(io).execute(any());
            context.registerBean(ExecutorService.class,()->io);
            context.scan("com.h.sceneserver.dispatch.handlers");
            context.register(SceneGameCommandRegistry.class,SceneServiceCommandRegistry.class);
            context.refresh();
            var game=context.getBean(SceneGameCommandRegistry.class);
            assertInstanceOf(ShiftHandler.class,game.get(GameCommands.SHIFT));
            assertInstanceOf(SplitHandler.class,game.get(GameCommands.SPLIT));
            assertInstanceOf(EjectHandler.class,game.get(GameCommands.EJECT));
            var registry=context.getBean(SceneServiceCommandRegistry.class);
            for(ServiceCommands cmd:List.of(ServiceCommands.FIND_SCENE,ServiceCommands.CREATE_SCENE,
                    ServiceCommands.SCENE_JOIN,ServiceCommands.SCENE_LEAVE,ServiceCommands.AGENT_SCENE_LINK_HELLO,
                    ServiceCommands.SCENE_PLAYER_INPUT,ServiceCommands.MATCH_HISTORY))assertNotNull(registry.get(cmd));
            var dispatcher=new SceneServiceDispatcher(registry,peers);
            var join=ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder().setCmd(ServiceCommands.SCENE_JOIN.code())
                    .setRequestId(71).setBody(SceneJoinRequest.newBuilder().setSceneId(7).build().toByteString())).build();
            var channel=new EmbeddedChannel();
            try {
                assertEquals(GameError.NOT_LOGGED_IN.code(),dispatcher.dispatch(channel,join).toCompletableFuture().join().getResponse().getCode());
                verify(scenes,never()).join(anyString(),any());
                var hello=ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder().setCmd(ServiceCommands.AGENT_SCENE_LINK_HELLO.code())
                        .setRequestId(1).setBody(AgentSceneLinkHello.newBuilder().setAgentHostInstanceId("agent").build().toByteString())).build();
                assertNull(dispatcher.dispatch(channel,hello).toCompletableFuture().join());
                assertTrue(peers.current(channel));
                verify(scenes).resyncAgent("agent");
                var response=dispatcher.dispatch(channel,join).toCompletableFuture().join().getResponse();
                assertEquals(71,response.getRequestId());assertEquals(0,response.getCode());
                var query=ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder().setCmd(ServiceCommands.MATCH_HISTORY.code())
                        .setRequestId(72).setBody(SceneMatchHistoryRequest.newBuilder().setUserId("u").build().toByteString())).build();
                var historyResponse=dispatcher.dispatch(channel,query).toCompletableFuture().join().getResponse();
                assertEquals(72,historyResponse.getRequestId());assertEquals(0,historyResponse.getCode());
                verify(history).history("u",20,"");
                var input=ScenePlayerInput.newBuilder().setSceneId(7).setClientMessage(ClientEnvelope.newBuilder()
                        .setCmd(GameCommands.SPLIT.code()).setRequestId(100)).build();
                var event=ServiceEnvelope.newBuilder().setEvent(ServiceEvent.newBuilder().setCmd(ServiceCommands.SCENE_PLAYER_INPUT.code())
                        .setBody(input.toByteString())).build();
                assertNull(dispatcher.dispatch(channel,event).toCompletableFuture().join());
                verify(scenes).input("agent",input);
                var unknown=join.toBuilder().setRequest(join.getRequest().toBuilder().setCmd(99999)).build();
                assertEquals(GameError.UNKNOWN_COMMAND.code(),dispatcher.dispatch(channel,unknown).toCompletableFuture().join().getResponse().getCode());
            } finally {channel.finishAndReleaseAll();}
        }
    }

    @GameCommand(GameCommands.SPLIT)
    static final class CountingSplitHandler implements SceneGameHandler {
        final AtomicInteger calls=new AtomicInteger();
        volatile Thread executionThread;
        public void handle(SceneActor scene,String user,DecodedMessage<ClientEnvelope,GameCommands> message) {
            executionThread=Thread.currentThread();calls.incrementAndGet();
        }
    }

    @Test
    void retainsInputOrderingAndSuccessfulActionDeduplication() throws Exception {
        CountingSplitHandler split=new CountingSplitHandler();
        var registry=new SceneGameCommandRegistry(List.of(new ShiftHandler(),split,new EjectHandler()));
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        PeerRegistry peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenAnswer(call-> {
            ServiceEnvelope envelope=call.getArgument(1);
            responses.add(SceneToAgentEvent.parseFrom(envelope.getEvent().getBody()).getClientMessage());
            return CompletableFuture.completedFuture(null);
        });
        try(var runtime=new TestSystem(1)) {
            SceneEndpoint scene=new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment()),runtime,peers,
                    mock(MatchRankingStore.class),registry);
            scene.join("agent",SceneJoinRequest.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor")
                    .setJoinOperationId("join").setNickname("name").build()).get(3,TimeUnit.SECONDS);
            responses.clear();
            var request=ClientEnvelope.newBuilder().setCmd(GameCommands.SPLIT.code()).setRequestId(100)
                    .setBody(SplitRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
            Thread caller=Thread.currentThread();
            scene.input("agent",input(request)).get(3,TimeUnit.SECONDS);
            scene.input("agent",input(request)).get(3,TimeUnit.SECONDS);
            assertEquals(1,split.calls.get());assertNotSame(caller,split.executionThread);
            for(int i=0;i<2;i++) {
                var response=responses.poll(3,TimeUnit.SECONDS);
                assertNotNull(response);assertEquals(100,response.getRequestId());assertEquals(0,response.getCode());
            }
            var conflict=request.toBuilder().setCmd(GameCommands.EJECT.code())
                    .setBody(EjectRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
            scene.input("agent",input(conflict)).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.OPERATION_CONFLICT.code(),responses.poll(3,TimeUnit.SECONDS).getCode());
            var shift=ClientEnvelope.newBuilder().setCmd(GameCommands.SHIFT.code()).setClientSequence(11)
                    .setBody(ShiftRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
            scene.input("agent",input(shift)).get(3,TimeUnit.SECONDS);
            scene.input("agent",input(shift.toBuilder().setClientSequence(10)
                    .setBody(ShiftRequest.newBuilder().setDirectionX(-1).build().toByteString()).build())).get(3,TimeUnit.SECONDS);
            var direction=scene.inspect(actual-> {
                Map<?,?> members=(Map<?,?>)ReflectionTestUtils.getField(actual,"members");
                Object member=members.get("u");
                return List.of(ReflectionTestUtils.getField(member,"sequence"),ReflectionTestUtils.getField(member,"dx"));
            });
            assertEquals(11L,direction.get(0));
            assertEquals(1f,direction.get(1));
            assertTrue(responses.isEmpty()); // SHIFT 没有逐条成功回包。
            var unknown=request.toBuilder().setCmd(99999).setRequestId(101).build();
            scene.input("agent",input(unknown)).get(3,TimeUnit.SECONDS);
            assertEquals(GameError.UNKNOWN_COMMAND.code(),responses.poll(3,TimeUnit.SECONDS).getCode());
        }
    }

    @Test
    void rejectsDuplicateAndMissingCommandAnnotations() {
        assertThrows(IllegalStateException.class,()->new SceneGameCommandRegistry(List.of(new ShiftHandler(),new ShiftHandler())));
        SceneGameHandler unannotated=(scene,user,message)->{};
        assertThrows(IllegalStateException.class,()->new SceneGameCommandRegistry(List.of(unannotated)));
    }

    @Test
    void realSplitAndEjectHandlersDoNotRepeatBattleMutations() throws Exception {
        var registry=new SceneGameCommandRegistry(List.of(new ShiftHandler(),new SplitHandler(),new EjectHandler()));
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        PeerRegistry peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenAnswer(call-> {
            ServiceEnvelope envelope=call.getArgument(1);
            responses.add(SceneToAgentEvent.parseFrom(envelope.getEvent().getBody()).getClientMessage());
            return CompletableFuture.completedFuture(null);
        });
        try(var runtime=new TestSystem(1)) {
            SceneEndpoint scene=new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment().withProperty("battle.initial-foods","0")),runtime,peers,
                    mock(MatchRankingStore.class),registry);
            scene.join("agent",SceneJoinRequest.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor")
                    .setJoinOperationId("join").setNickname("name").build()).get(3,TimeUnit.SECONDS);
            scene.join("agent",SceneJoinRequest.newBuilder().setSceneId(7).setUserId("anchor").setPlayerActorId("anchor")
                    .setJoinOperationId("anchor").setNickname("anchor").build()).get(3,TimeUnit.SECONDS);
            // 达到两人开赛；把另一个成员设为无球观战，隔离本测试的动作数量断言。
            scene.inspect(actual-> {
                Map<?,?> balls=(Map<?,?>)ReflectionTestUtils.getField(actual,"balls");
                balls.remove(2L);
                ReflectionTestUtils.setField(balls.values().iterator().next(),"radius",5f);return null;
            });
            responses.clear();
            var split=ClientEnvelope.newBuilder().setCmd(GameCommands.SPLIT.code()).setRequestId(200)
                    .setBody(SplitRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
            scene.input("agent",input(split)).get(3,TimeUnit.SECONDS);
            scene.input("agent",input(split)).get(3,TimeUnit.SECONDS);
            assertEquals(2,scene.snapshot().getBallsCount());
            double area=scene.snapshot().getBallsList().stream().mapToDouble(ball->ball.getRadius()*ball.getRadius()).sum();
            assertEquals(25d,area,0.0001d);
            var eject=ClientEnvelope.newBuilder().setCmd(GameCommands.EJECT.code()).setRequestId(201)
                    .setBody(EjectRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
            scene.input("agent",input(eject)).get(3,TimeUnit.SECONDS);
            assertEquals(2,scene.snapshot().getFoodsCount());
            scene.input("agent",input(eject)).get(3,TimeUnit.SECONDS);
            assertEquals(2,scene.snapshot().getFoodsCount());
            assertEquals(2,responses.stream().filter(r->r.getRequestId()==200 && r.getCode()==0).count());
            assertEquals(2,responses.stream().filter(r->r.getRequestId()==201 && r.getCode()==0).count());
        }
    }

    private ScenePlayerInput input(ClientEnvelope request) {
        return ScenePlayerInput.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor")
                .setJoinOperationId("join").setClientMessage(request).build();
    }

    @GameCommand(GameCommands.SPLIT)
    static final class RejectingSplitHandler implements SceneGameHandler {
        int calls; boolean reject=true;
        public void handle(SceneActor scene,String user,DecodedMessage<ClientEnvelope,GameCommands> message) {
            calls++;
            if(reject)throw new com.h.gamecore.protocol.BattleException(GameError.INVALID_INPUT);
        }
    }
    @GameCommand(GameCommands.SPLIT)
    static final class SerialHandler implements SceneGameHandler {
        final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),calls=new AtomicInteger();
        public void handle(SceneActor scene,String user,DecodedMessage<ClientEnvelope,GameCommands> message) {
            int count=active.incrementAndGet();peak.accumulateAndGet(count,Math::max);
            try {Thread.yield();calls.incrementAndGet();} finally {active.decrementAndGet();}
        }
    }
    @Test void realAkkaMailboxSerializesManyRequestsOnSharedDispatcher() throws Exception {
        var handler=new SerialHandler();var peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        try(var runtime=new TestSystem(4)) {
            var scene=new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment()),runtime,peers,
                    mock(MatchRankingStore.class),new SceneGameCommandRegistry(List.of(handler)));
            scene.join("agent",SceneJoinRequest.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor")
                    .setJoinOperationId("join").setNickname("name").build()).get(3,TimeUnit.SECONDS);
            var batch=new ArrayList<CompletableFuture<Void>>();
            for(int id=1;id<=100;id++)batch.add(scene.input("agent",input(ClientEnvelope.newBuilder()
                    .setCmd(GameCommands.SPLIT.code()).setRequestId(id).setBody(SplitRequest.getDefaultInstance().toByteString()).build())));
            CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);
            assertEquals(100,handler.calls.get());assertEquals(1,handler.peak.get());
        }
    }
    @GameCommand(GameCommands.SPLIT)
    static final class BlockingHandler implements SceneGameHandler {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        public void handle(SceneActor scene,String user,DecodedMessage<ClientEnvelope,GameCommands> message) {
            entered.countDown();
            try {if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("测试未释放 Actor");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
        }
    }
    @Test void separateSceneActorsCanRunInParallel() throws Exception {
        var blocking=new BlockingHandler();var other=new SerialHandler();var peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        try(var runtime=new TestSystem(2)) {
            var first=new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment()),runtime,peers,
                    mock(MatchRankingStore.class),new SceneGameCommandRegistry(List.of(blocking)));
            var second=new SceneEndpoint(8,"scene",BattleRules.load(new MockEnvironment()),runtime,peers,
                    mock(MatchRankingStore.class),new SceneGameCommandRegistry(List.of(other)));
            var join=SceneJoinRequest.newBuilder().setUserId("u").setPlayerActorId("actor").setJoinOperationId("join").setNickname("name");
            first.join("agent",join.setSceneId(7).build()).get(3,TimeUnit.SECONDS);
            second.join("agent",join.setSceneId(8).build()).get(3,TimeUnit.SECONDS);
            var message=input(ClientEnvelope.newBuilder().setCmd(GameCommands.SPLIT.code()).setRequestId(1).build());
            var waiting=first.input("agent",message);
            try {
                assertTrue(blocking.entered.await(3,TimeUnit.SECONDS));
                second.input("agent",message).get(2,TimeUnit.SECONDS);
                assertEquals(1,other.calls.get());assertFalse(waiting.isDone());
            } finally {blocking.release.countDown();}
            waiting.get(3,TimeUnit.SECONDS);
        }
    }
    @Test void productionSceneTickRunsFromAkkaTimerAndStopsWithActor() throws Exception {
        var peers=mock(PeerRegistry.class);var rankings=mock(MatchRankingStore.class);
        when(rankings.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        var messages=new LinkedBlockingQueue<ServerEnvelope>();
        when(peers.send(anyString(),any())).thenAnswer(call-> {
            ServiceEnvelope envelope=call.getArgument(1);
            messages.add(SceneToAgentEvent.parseFrom(envelope.getEvent().getBody()).getClientMessage());
            return CompletableFuture.completedFuture(null);
        });
        try(var runtime=new TestSystem(2)) {
            var actor=runtime.system.actorOf(SceneActor.props(7,"scene",BattleRules.load(new MockEnvironment()),null,peers,rankings,new RedisRequestFixture().store(),
                    new SceneGameCommandRegistry(List.of(new ShiftHandler(),new SplitHandler(),new EjectHandler()))));
            com.h.gamecore.actor.AkkaSupport.<SceneJoinResult>ask(actor,new SceneActor.Join("agent",SceneJoinRequest.newBuilder()
                    .setSceneId(7).setUserId("u").setPlayerActorId("actor").setJoinOperationId("10").setNickname("name").build())).get(3,TimeUnit.SECONDS);
            // 第一名玩家进入后真实定时器也不能推进逻辑或推送 MOVE。
            messages.clear();
            assertNull(messages.poll(150,TimeUnit.MILLISECONDS));
            var waiting=SceneSnapshot.parseFrom(com.h.gamecore.actor.AkkaSupport.<ServerEnvelope>ask(actor,SceneActor.Snapshot.INSTANCE)
                    .get(3,TimeUnit.SECONDS).getBody());
            assertFalse(waiting.getStarted());assertEquals(0,waiting.getServerTick());
            com.h.gamecore.actor.AkkaSupport.<SceneJoinResult>ask(actor,new SceneActor.Join("agent",SceneJoinRequest.newBuilder()
                    .setSceneId(7).setUserId("v").setPlayerActorId("actor-v").setJoinOperationId("11").setNickname("second").build())).get(3,TimeUnit.SECONDS);
            ServerEnvelope move=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline && move==null) {
                var message=messages.poll(500,TimeUnit.MILLISECONDS);
                if(message!=null && message.getCmd()==GameCommands.MOVE.code())move=message;
            }
            assertNotNull(move);assertTrue(MoveBroadcast.parseFrom(move.getBody()).getServerTick()>0);
            com.h.gamecore.actor.AkkaSupport.stop(actor,akka.actor.PoisonPill.getInstance()).get(3,TimeUnit.SECONDS);
            assertTrue(actor.isTerminated());
            messages.clear();assertNull(messages.poll(150,TimeUnit.MILLISECONDS));
        }
    }
    @Test
    void scopesAllocationRequestIdToEachActor() throws Exception {
        var env=new MockEnvironment().withProperty("spring.cloud.nacos.discovery.metadata.instanceid","scene");
        try(var runtime=new TestSystem(1)) {
            var directory=new SceneDirectory(env,runtime.system,mock(PeerRegistry.class),mock(MatchRankingStore.class),new RedisRequestFixture().store(),
                    new SceneGameCommandRegistry(List.of(new ShiftHandler(),new SplitHandler(),new EjectHandler())));
            try {
                var first=directory.allocate("agent","actor-a","10",true).get(3,TimeUnit.SECONDS);
                assertEquals(first,directory.allocate("agent","actor-a","10",true).get(3,TimeUnit.SECONDS));
                var other=directory.allocate("agent","actor-b","10",true).get(3,TimeUnit.SECONDS);
                assertNotEquals(first.getSceneId(),other.getSceneId());
                assertThrows(CompletionException.class,()->directory.allocate("agent","actor-a","10",false).join());
                assertThrows(CompletionException.class,()->directory.allocate("agent","","10",true).join());
            } finally {directory.close();}
        }
    }
    @Test
    void oldLeaveCannotCancelAnotherActorWithTheSameRequestId() throws Exception {
        try(var runtime=new TestSystem(1)) {
            var peers=mock(PeerRegistry.class);
            when(peers.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
            var scene=new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment()),runtime,peers,
                    mock(MatchRankingStore.class),new SceneGameCommandRegistry(List.of(new ShiftHandler(),new SplitHandler(),new EjectHandler())));
            var old=SceneJoinRequest.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor-a")
                    .setJoinOperationId("10").setNickname("name").build();
            scene.join("agent",old).get(3,TimeUnit.SECONDS);
            scene.join("agent",old.toBuilder().setUserId("anchor").setPlayerActorId("anchor").setJoinOperationId("20").build()).get(3,TimeUnit.SECONDS);
            var leave=SceneLeaveRequest.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor-a").setJoinOperationId("10").build();
            assertEquals(SceneLeaveResult.Outcome.LEFT,scene.leave("agent",leave).get(3,TimeUnit.SECONDS).getOutcome());
            scene.join("agent",old.toBuilder().setPlayerActorId("actor-b").build()).get(3,TimeUnit.SECONDS);
            assertEquals(SceneLeaveResult.Outcome.ALREADY_LEFT,scene.leave("agent",leave).get(3,TimeUnit.SECONDS).getOutcome());
            assertEquals("actor-b",scene.inspect(actual-> {
                Map<?,?> members=(Map<?,?>)ReflectionTestUtils.getField(actual,"members");
                return ReflectionTestUtils.getField(members.get("u"),"actor");
            }));
        }
    }
    @Test
    void cachesFailedActionResponseAndReplaysRetiredIdFromRedisWithoutReexecuting() throws Exception {
        var handler=new RejectingSplitHandler();
        var registry=new SceneGameCommandRegistry(List.of(handler));
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        var peers=mock(PeerRegistry.class);
        when(peers.send(anyString(),any())).thenAnswer(call-> {
            ServiceEnvelope envelope=call.getArgument(1);
            responses.add(SceneToAgentEvent.parseFrom(envelope.getEvent().getBody()).getClientMessage());
            return CompletableFuture.completedFuture(null);
        });
        try(var runtime=new TestSystem(1)) {
            var scene=new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment()),runtime,peers,
                    mock(MatchRankingStore.class),registry);
            scene.join("agent",SceneJoinRequest.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor")
                    .setJoinOperationId("join").setNickname("name").build()).get(3,TimeUnit.SECONDS);
            scene.inspect(actual-> {
                Map<?,?> members=(Map<?,?>)ReflectionTestUtils.getField(actual,"members");
                ReflectionTestUtils.setField(members.get("u"),"messages",new com.h.gamecore.actor.MessageContext(2));return null;
            });
            responses.clear();
            var request=ClientEnvelope.newBuilder().setCmd(GameCommands.SPLIT.code()).setRequestId(100)
                    .setBody(SplitRequest.newBuilder().setDirectionX(1).build().toByteString()).build();
            scene.input("agent",input(request)).get(3,TimeUnit.SECONDS);
            var first=responses.poll(3,TimeUnit.SECONDS);assertEquals(GameError.INVALID_INPUT.code(),first.getCode());
            handler.reject=false;
            scene.input("agent",input(request)).get(3,TimeUnit.SECONDS);
            assertEquals(first,responses.poll(3,TimeUnit.SECONDS));assertEquals(1,handler.calls);
            for(long id=101;id<=102;id++) {
                scene.input("agent",input(request.toBuilder().setRequestId(id).build())).get(3,TimeUnit.SECONDS);
                assertEquals(0,responses.poll(3,TimeUnit.SECONDS).getCode());
            }
            scene.input("agent",input(request)).get(3,TimeUnit.SECONDS);
            assertEquals(first,responses.poll(3,TimeUnit.SECONDS));
            assertEquals(3,handler.calls);
        }
    }
}
