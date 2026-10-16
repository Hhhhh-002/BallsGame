package com.h.sceneserver.actor;

import com.h.gamecommon.command.GameCommands;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.config.BattleRules;
import com.h.sceneserver.dispatch.handlers.*;
import com.h.sceneserver.registries.SceneGameCommandRegistry;
import com.h.sceneserver.store.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoundEndedTests {
    @Test void firstLateInputDetectsDeadlineAndAllLaterBattleInputsAreSilentlyDropped() throws Exception {
        var peers=mock(PeerRegistry.class);var requests=mock(SceneRequestStore.class);
        when(peers.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        try(var runtime=new SceneEndpoint.TestSystem(1)) {
            var scene=scene(runtime,peers,requests);join(scene,"u");join(scene,"v");
            var before=scene.snapshot();clearInvocations(peers);
            scene.inspect(actor->{ReflectionTestUtils.setField(actor,"startedNanos",System.nanoTime()-TimeUnit.SECONDS.toNanos(601));return null;});
            for(var cmd:List.of(GameCommands.SHIFT,GameCommands.SPLIT,GameCommands.EJECT))
                scene.input("agent",input(cmd)).get(3,TimeUnit.SECONDS);
            assertEquals(before,scene.snapshot());
            assertTrue(scene.<Boolean>inspect(actor->(Boolean)ReflectionTestUtils.getField(actor,"finishing")).booleanValue());
            var frozen=scene.inspect(actor->(MatchRanking)ReflectionTestUtils.getField(actor,"finalRanking"));
            assertTrue(frozen.getFrozen());assertEquals(2,frozen.getPlayersCount());
            verifyNoInteractions(peers,requests);
        }
    }
    @Test void shiftAlreadyWaitingBehindAnActionIsAlsoDiscardedWhenSettlementBegins() throws Exception {
        var peers=mock(PeerRegistry.class);var requests=mock(SceneRequestStore.class);
        when(peers.send(anyString(),any())).thenReturn(CompletableFuture.completedFuture(null));
        try(var runtime=new SceneEndpoint.TestSystem(1)) {
            var scene=scene(runtime,peers,requests);join(scene,"u");join(scene,"v");
            var gate=new CompletableFuture<Void>();var before=scene.snapshot();
            scene.inspect(actor->{
                Object member=((Map<?,?>)ReflectionTestUtils.getField(actor,"members")).get("u");
                ReflectionTestUtils.setField(member,"actions",gate);return null;
            });
            clearInvocations(peers);
            var waiting=scene.input("agent",input(GameCommands.SHIFT));
            scene.inspect(actor->{ReflectionTestUtils.setField(actor,"startedNanos",System.nanoTime()-TimeUnit.SECONDS.toNanos(601));return null;});
            gate.complete(null);waiting.get(3,TimeUnit.SECONDS);
            assertEquals(before,scene.snapshot());verifyNoInteractions(peers,requests);
        }
    }
    private SceneEndpoint scene(SceneEndpoint.TestSystem runtime,PeerRegistry peers,SceneRequestStore requests) {
        return new SceneEndpoint(7,"scene",BattleRules.load(new MockEnvironment()),runtime,peers,mock(MatchRankingStore.class),requests,
                new SceneGameCommandRegistry(List.of(new ShiftHandler(),new SplitHandler(),new EjectHandler())));
    }
    private void join(SceneEndpoint scene,String user) throws Exception {
        scene.join("agent",SceneJoinRequest.newBuilder().setSceneId(7).setUserId(user).setPlayerActorId("actor-"+user)
                .setJoinOperationId("10").setNickname(user).build()).get(3,TimeUnit.SECONDS);
    }
    private ScenePlayerInput input(GameCommands cmd) {
        var request=ClientEnvelope.newBuilder().setCmd(cmd.code()).setClientSequence(1);
        if(cmd==GameCommands.SHIFT)request.setBody(ShiftRequest.newBuilder().setDirectionX(1).build().toByteString());
        if(cmd==GameCommands.SPLIT)request.setRequestId(20).setBody(SplitRequest.getDefaultInstance().toByteString());
        if(cmd==GameCommands.EJECT)request.setRequestId(21).setBody(EjectRequest.getDefaultInstance().toByteString());
        return ScenePlayerInput.newBuilder().setSceneId(7).setUserId("u").setPlayerActorId("actor-u")
                .setJoinOperationId("10").setClientMessage(request).build();
    }
}
