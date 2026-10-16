package com.h.agentserver.actor;

import com.h.agentserver.dispatch.handlers.*;
import com.h.agentserver.registries.*;
import com.h.agentserver.rpc.SceneLinkManager;
import com.h.agentserver.store.PlayerRequestStore;
import com.h.gamecommon.command.*;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.Messages;
import com.h.gameprotocol.proto.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoundEndedTests {
    @Test void dropsBattleInputsDuringAndAfterSettlementButAllowsHistoryAndTheNextRound() throws Exception {
        var scenes=mock(SceneLinkManager.class);
        var peers=mock(PeerRegistry.class);
        var requests=mock(PlayerRequestStore.class);
        when(peers.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        when(scenes.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        when(scenes.select()).thenReturn(CompletableFuture.completedFuture("scene"));
        var leaving=new CompletableFuture<ServiceResponse>();
        when(scenes.request(eq("scene"),eq(ServiceCommands.SCENE_LEAVE),any())).thenReturn(leaving);
        when(scenes.request(eq("scene"),eq(ServiceCommands.MATCH_HISTORY),any())).thenReturn(CompletableFuture.completedFuture(
                ServiceResponse.newBuilder().setBody(MatchHistoryResponse.getDefaultInstance().toByteString()).build()));
        when(scenes.request(eq("scene"),eq(ServiceCommands.FIND_SCENE),any())).thenReturn(CompletableFuture.completedFuture(
                ServiceResponse.newBuilder().setBody(CreateSceneResult.newBuilder().setSceneId(8)
                        .setSceneHostInstanceId("scene").setSceneHostServiceName("scene-server").build().toByteString()).build()));
        when(scenes.request(eq("scene"),eq(ServiceCommands.SCENE_JOIN),any())).thenReturn(CompletableFuture.completedFuture(
                ServiceResponse.newBuilder().setBody(SceneJoinResult.newBuilder().setSceneId(8).build().toByteString()).build()));
        when(requests.claim(anyString(),any())).thenReturn(new PlayerRequestStore.Stored("",null,"",0,""));
        var games=new AgentGameCommandRegistry(List.of(new ShiftHandler(),new SplitHandler(),new EjectHandler(),
                new StartGameHandler(),new MatchHistoryHandler()));
        for(var cmd:List.of(GameCommands.SHIFT,GameCommands.SPLIT,GameCommands.EJECT))assertTrue(games.get(cmd).battleInput());
        assertFalse(games.get(GameCommands.START_GAME).battleInput());assertFalse(games.get(GameCommands.MATCH_HISTORY).battleInput());
        try(var runtime=new AkkaTestSystem(1)) {
            var player=new PlayerEndpoint(CreatePlayerAgentRequest.newBuilder().setUserId("u")
                    .setPlayerActorId("actor").setGatewayInstanceId("gw"),runtime,scenes,peers,requests,Runnable::run,games,
                    new AgentSceneMessageRegistry(List.of(new ForwardSceneMessageHandler(),new MatchEndedHandler())));
            var start=ClientEnvelope.newBuilder().setCmd(GameCommands.START_GAME.code()).setRequestId(10)
                    .setBody(StartGameRequest.newBuilder().setNickname("name").build().toByteString()).build();
            player.seedScene(start,"scene",7,"name").get(3,TimeUnit.SECONDS);
            var finalMessage=Messages.push(GameCommands.MATCH_ENDED,MatchRanking.newBuilder().setSceneId(7)
                    .setSceneInstanceId("scene").setFrozen(true).build());
            player.sceneEvent("scene",SceneToAgentEvent.newBuilder().setSceneId(7).setClientMessage(finalMessage).build(),"10");
            assertTrue(player.inspect(p->p.roundEnding).get(3,TimeUnit.SECONDS));
            dropInputs(player);
            verify(requests,never()).claim(anyString(),any());verify(scenes,never()).send(anyString(),any());
            leaving.complete(ServiceResponse.newBuilder().setBody(SceneLeaveResult.newBuilder()
                    .setOutcome(SceneLeaveResult.Outcome.LEFT).build().toByteString()).build());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!player.inspect(p->p.route==null).get(3,TimeUnit.SECONDS)) {
                assertTrue(System.nanoTime()<deadline);Thread.sleep(5);
            }
            assertTrue(player.inspect(p->p.roundEnding).get(3,TimeUnit.SECONDS));
            clearInvocations(peers,scenes,requests);
            dropInputs(player);
            verifyNoInteractions(peers);verify(requests,never()).claim(anyString(),any());verify(scenes,never()).send(anyString(),any());
            player.forward("gw",ClientEnvelope.newBuilder().setCmd(GameCommands.MATCH_HISTORY.code()).setRequestId(101)
                    .setBody(MatchHistoryRequest.getDefaultInstance().toByteString()).build()).get(3,TimeUnit.SECONDS);
            verify(scenes).request(eq("scene"),eq(ServiceCommands.MATCH_HISTORY),any());
            player.forward("gw",start.toBuilder().setRequestId(102).build()).get(3,TimeUnit.SECONDS);
            assertFalse(player.inspect(p->p.roundEnding).get(3,TimeUnit.SECONDS));
            assertTrue(player.inspect(p->p.joined && p.route.scene()==8).get(3,TimeUnit.SECONDS));
            player.forward("gw",ClientEnvelope.newBuilder().setCmd(GameCommands.SHIFT.code()).setClientSequence(1)
                    .setBody(ShiftRequest.newBuilder().setDirectionX(1).build().toByteString()).build()).get(3,TimeUnit.SECONDS);
            verify(scenes).send(eq("scene"),any());
        }
    }
    private void dropInputs(PlayerEndpoint player) throws Exception {
        player.forward("gw",ClientEnvelope.newBuilder().setCmd(GameCommands.SHIFT.code()).setClientSequence(1)
                .setBody(ShiftRequest.newBuilder().setDirectionX(1).build().toByteString()).build()).get(3,TimeUnit.SECONDS);
        player.forward("gw",ClientEnvelope.newBuilder().setCmd(GameCommands.SPLIT.code()).setRequestId(20)
                .setBody(SplitRequest.getDefaultInstance().toByteString()).build()).get(3,TimeUnit.SECONDS);
        player.forward("gw",ClientEnvelope.newBuilder().setCmd(GameCommands.EJECT.code()).setRequestId(21)
                .setBody(EjectRequest.getDefaultInstance().toByteString()).build()).get(3,TimeUnit.SECONDS);
    }
}
