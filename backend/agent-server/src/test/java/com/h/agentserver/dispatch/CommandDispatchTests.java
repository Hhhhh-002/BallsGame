package com.h.agentserver.dispatch;

import com.h.agentserver.actor.PlayerActor;
import com.h.agentserver.dispatch.handlers.*;
import com.h.agentserver.registries.*;
import com.h.agentserver.rpc.SceneLinkManager;
import com.h.agentserver.service.PlayerActorRegistry;
import com.h.agentserver.store.*;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.*;
import com.h.agentserver.actor.AkkaTestSystem;
import com.h.agentserver.actor.PlayerEndpoint;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.*;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 不启动 Nacos、MySQL；覆盖真实 Spring 注册、入口权限及 Actor 内去重。 */
class CommandDispatchTests {
    @Test
    void discoversAllHandlersAndKeepsTransportPermissions() throws Exception {
        PlayerActorRegistry actors=mock(PlayerActorRegistry.class);
        PeerRegistry peers=new PeerRegistry();
        try (var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(PlayerActorRegistry.class,()->actors);
            context.registerBean(PeerRegistry.class,()->peers);
            context.scan("com.h.agentserver.dispatch.handlers");
            context.register(AgentGameCommandRegistry.class,AgentSceneMessageRegistry.class,AgentServiceCommandRegistry.class);
            context.refresh();
            var game=context.getBean(AgentGameCommandRegistry.class);
            assertInstanceOf(StartGameHandler.class,game.get(GameCommands.START_GAME));
            assertInstanceOf(LeaveGameHandler.class,game.get(GameCommands.LEAVE));
            assertInstanceOf(ShiftHandler.class,game.get(GameCommands.SHIFT));
            assertInstanceOf(SplitHandler.class,game.get(GameCommands.SPLIT));
            assertInstanceOf(EjectHandler.class,game.get(GameCommands.EJECT));
            assertNull(game.get(null));
            assertNull(game.get(GameCommands.LOGIN));
            var incoming=context.getBean(AgentSceneMessageRegistry.class);
            for (GameCommands cmd:List.of(GameCommands.MOVE,GameCommands.FOOD_ADDED,GameCommands.FOOD_EATEN,
                    GameCommands.PLAYER_ENTERED,GameCommands.PLAYER_LEFT,GameCommands.BALL_CHANGED,GameCommands.MATCH_RANKING))
                assertInstanceOf(ForwardSceneMessageHandler.class,incoming.get(cmd));
            assertInstanceOf(MatchEndedHandler.class,incoming.get(GameCommands.MATCH_ENDED));

            var dispatcher=new AgentServiceDispatcher(context.getBean(AgentServiceCommandRegistry.class),peers);
            var create=ServiceRequest.newBuilder().setCmd(ServiceCommands.CREATE_PLAYER_AGENT.code()).setRequestId(91)
                    .setBody(CreatePlayerAgentRequest.newBuilder().setUserId("u").build().toByteString()).build();
            when(actors.create(any())).thenReturn(CompletableFuture.completedFuture(
                    CreatePlayerAgentResult.newBuilder().setPlayerActorId("a").build()));
            var response=ServiceEnvelope.parseFrom(dispatcher.http(ServiceEnvelope.newBuilder().setRequest(create).build()
                    .toByteArray()).get(3,TimeUnit.SECONDS)).getResponse();
            assertEquals(91,response.getRequestId());
            assertEquals(0,response.getCode());
            verify(actors,times(1)).create(any());
            var channel=new EmbeddedChannel();
            try {
                assertEquals(GameError.UNKNOWN_COMMAND.code(),dispatcher.websocket(channel,
                        ServiceEnvelope.newBuilder().setRequest(create).build()).toCompletableFuture().join().getResponse().getCode());
                var forward=ServiceRequest.newBuilder().setCmd(ServiceCommands.FORWARD_PLAYER_COMMAND.code())
                        .setBody(ForwardPlayerCommand.getDefaultInstance().toByteString()).build();
                assertEquals(GameError.NOT_LOGGED_IN.code(),dispatcher.websocket(channel,
                        ServiceEnvelope.newBuilder().setRequest(forward).build()).toCompletableFuture().join().getResponse().getCode());
                var hello=ServiceRequest.newBuilder().setCmd(ServiceCommands.AGENT_LINK_HELLO.code()).setRequestId(1)
                        .setBody(GatewayAgentLinkHello.newBuilder().setGatewayInstanceId("gw").build().toByteString()).build();
                assertEquals(0,dispatcher.websocket(channel,ServiceEnvelope.newBuilder().setRequest(hello).build())
                        .toCompletableFuture().join().getResponse().getCode());
                assertTrue(peers.current(channel));
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    @Test
    void rejectsMissingAnnotationsAndDuplicateCommands() {
        assertThrows(IllegalStateException.class,()->new AgentGameCommandRegistry(List.of(new ShiftHandler(),new ShiftHandler())));
        AgentGameHandler unannotated=(actor,message)->CompletableFuture.completedFuture(null);
        assertThrows(IllegalStateException.class,()->new AgentGameCommandRegistry(List.of(unannotated)));
    }

    @GameCommand(GameCommands.START_GAME)
    static final class WaitingHandler implements AgentGameHandler {
        final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1);
        final CompletableFuture<ServerEnvelope> result=new CompletableFuture<>();
        volatile Thread executionThread;
        public CompletableFuture<ServerEnvelope> handle(PlayerActor actor,DecodedMessage<ClientEnvelope,GameCommands> message) {
            executionThread=Thread.currentThread();calls.incrementAndGet();entered.countDown();return result;
        }
    }

    @Test
    void deduplicatesByRequestIdInsideMailboxAndReplaysResponse() throws Exception {
        WaitingHandler handler=new WaitingHandler();
        var registry=new AgentGameCommandRegistry(List.of(handler));
        PeerRegistry peers=mock(PeerRegistry.class);
        var responses=new LinkedBlockingQueue<ServerEnvelope>();
        when(peers.send(anyString(),any())).thenAnswer(call-> {
            ServiceEnvelope envelope=call.getArgument(1);
            responses.add(ForwardToClientEvent.parseFrom(envelope.getEvent().getBody()).getClientMessage());
            return CompletableFuture.completedFuture(null);
        });
        CreatePlayerAgentRequest.Builder lease=CreatePlayerAgentRequest.newBuilder();
        lease.setPlayerActorId("actor");lease.setUserId("u");lease.setGatewayInstanceId("gw");
        var request=ClientEnvelope.newBuilder().setCmd(GameCommands.START_GAME.code()).setRequestId(100)
                .setBody(StartGameRequest.newBuilder().setNickname("one").build().toByteString()).build();
        try(var runtime=new AkkaTestSystem(1)) {
            PlayerRequestStore requests=mock(PlayerRequestStore.class);
            when(requests.claim(anyString(),any())).thenReturn(new PlayerRequestStore.Stored("",null,"",0,""));
            PlayerEndpoint actor=new PlayerEndpoint(lease,runtime,
                    mock(SceneLinkManager.class),peers,requests,Runnable::run,
                    registry,new AgentSceneMessageRegistry(List.of(new ForwardSceneMessageHandler(),new MatchEndedHandler())));
            Thread caller=Thread.currentThread();
            var first=actor.forward("gw",request);
            assertTrue(handler.entered.await(3,TimeUnit.SECONDS));
            assertNotSame(caller,handler.executionThread);
            actor.forward("gw",request).get(3,TimeUnit.SECONDS);
            assertEquals(1,handler.calls.get());
            handler.result.complete(Messages.client(request,null));
            first.get(3,TimeUnit.SECONDS);
            actor.forward("gw",request).get(3,TimeUnit.SECONDS);
            for(int i=0;i<3;i++) {
                ServerEnvelope response=responses.poll(3,TimeUnit.SECONDS);
                assertNotNull(response);assertEquals(100,response.getRequestId());assertEquals(0,response.getCode());
            }
            assertEquals(1,handler.calls.get());
            var conflict=request.toBuilder().setBody(StartGameRequest.newBuilder().setNickname("changed").build().toByteString()).build();
            CompletionException failure=assertThrows(CompletionException.class,()->actor.forward("gw",conflict).join());
            assertEquals(GameError.OPERATION_CONFLICT,((BattleException)Messages.unwrap(failure)).error());
            var unknown=request.toBuilder().setCmd(99999).setRequestId(101).build();
            failure=assertThrows(CompletionException.class,()->actor.forward("gw",unknown).join());
            assertEquals(GameError.UNKNOWN_COMMAND,((BattleException)Messages.unwrap(failure)).error());
        }
    }
}
