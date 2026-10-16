package com.h.agentserver.actor;

import akka.actor.ActorRef;
import com.h.agentserver.dispatch.handlers.*;
import com.h.agentserver.registries.*;
import com.h.agentserver.rpc.SceneLinkManager;
import com.h.agentserver.service.PlayerActorRegistry;
import com.h.agentserver.store.PlayerRequestStore;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gameprotocol.proto.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActorLifecycleTests {
    @Test void missingActorNeverTriggersRedisIdentityRestoreOrActorCreation() throws Exception {
        var requests=mock(PlayerRequestStore.class);var io=directIo();
        var login=CreatePlayerAgentRequest.newBuilder().setUserId("u").setPlayerActorId("player-a")
                .setGatewayInstanceId("gw").setLoginOperationId("10").build();
        var old=new PlayerRequestStore.Creation(Base64.getEncoder().encodeToString(login.toByteArray()),"agent",null);
        when(requests.creation("player-a")).thenReturn(old);
        when(requests.claimCreate(login,"agent")).thenReturn(new PlayerRequestStore.CreateClaim(old,false));
        try(var runtime=new AkkaTestSystem(1)) {
            var registry=registry(requests,io,runtime);
            assertThrows(com.h.gamecore.protocol.BattleException.class,()->registry.forward("gw",ForwardPlayerCommand.newBuilder()
                    .setPlayerActorId("player-a").setClientMessage(ClientEnvelope.newBuilder().setCmd(2004)).build()));
            var createFailure=assertThrows(ExecutionException.class,()->registry.create(login).get(3,TimeUnit.SECONDS));
            assertEquals(com.h.gamecommon.code.GameError.AGENT_ROUTE_MISSING,
                    ((com.h.gamecore.protocol.BattleException)createFailure.getCause()).error());
            var stopFailure=assertThrows(ExecutionException.class,()->registry.stop(KickOldAgentRequest.newBuilder()
                    .setUserId("u").setPlayerActorId("player-a").build()).get(3,TimeUnit.SECONDS));
            assertEquals(com.h.gamecommon.code.GameError.AGENT_ROUTE_MISSING,
                    ((com.h.gamecore.protocol.BattleException)stopFailure.getCause()).error());
            assertTrue(registry.logins().isEmpty());assertTrue(((Map<?,?>)ReflectionTestUtils.getField(registry,"actors")).isEmpty());
            verify(requests,never()).created(anyString(),any());verify(requests,never()).stopped(anyString(),any());
        }
    }
    @Test void normalStopRetryOnlyCommitsRetainedResultAfterRedisFailure() throws Exception {
        var requests=mock(PlayerRequestStore.class);var io=directIo();
        var login=CreatePlayerAgentRequest.newBuilder().setUserId("u").setPlayerActorId("player-a")
                .setGatewayInstanceId("gw").setLoginOperationId("10").build();
        var original=new PlayerRequestStore.Creation(Base64.getEncoder().encodeToString(login.toByteArray()),"agent",null);
        when(requests.creation("player-a")).thenReturn(original);
        when(requests.claimCreate(login,"agent")).thenReturn(new PlayerRequestStore.CreateClaim(original,true));
        doThrow(new IllegalStateException("模拟停止响应提交失败")).doNothing().when(requests).stopped(anyString(),any());
        try(var runtime=new AkkaTestSystem(1)) {
            var registry=registry(requests,io,runtime);registry.create(login).get(3,TimeUnit.SECONDS);
            var stop=KickOldAgentRequest.newBuilder().setUserId("u").setPlayerActorId("player-a").build();
            assertThrows(ExecutionException.class,()->registry.stop(stop).get(3,TimeUnit.SECONDS));
            assertTrue(registry.logins().isEmpty());
            assertEquals(KickOldAgentResult.Outcome.SAVED_AND_STOPPED,registry.stop(stop).get(3,TimeUnit.SECONDS).getOutcome());
            assertTrue(registry.logins().isEmpty()); // 只补交原响应，没有为了重试重新创建 Actor。
            verify(requests,times(1)).created(anyString(),any());verify(requests,times(2)).stopped(anyString(),any());
        }
    }
    private ExecutorService directIo() {
        var io=mock(ExecutorService.class);doAnswer(c->{((Runnable)c.getArgument(0)).run();return null;}).when(io).execute(any());return io;
    }
    @Test void oneContextAndActorStopsBeforeRedisFinalResultAndDuplicateStopReplays() throws Exception {
        var requests=mock(PlayerRequestStore.class);
        var io=mock(ExecutorService.class);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(io).execute(any());
        var login=CreatePlayerAgentRequest.newBuilder().setUserId("u").setPlayerActorId("player-a")
                .setGatewayInstanceId("gw").setLoginOperationId("10").build();
        var stored=new PlayerRequestStore.Creation(Base64.getEncoder().encodeToString(login.toByteArray()),"agent",null);
        when(requests.creation("player-a")).thenReturn(stored);
        when(requests.claimCreate(login,"agent")).thenReturn(new PlayerRequestStore.CreateClaim(stored,true));
        AtomicReference<KickOldAgentResult> finished=new AtomicReference<>();
        when(requests.stopResult("player-a")).thenAnswer(call->finished.get());
        AtomicReference<ActorRef> address=new AtomicReference<>();
        doAnswer(call->{assertTrue(address.get().isTerminated());finished.set(call.getArgument(1));return null;})
                .when(requests).stopped(eq("player-a"),any());
        var stop=KickOldAgentRequest.newBuilder().setUserId("u").setPlayerActorId("player-a").build();
        try(var runtime=new AkkaTestSystem(2)) {
            var registry=registry(requests,io,runtime);
            assertEquals("player-a",registry.create(login).get(3,TimeUnit.SECONDS).getPlayerActorId());
            registry.create(login).get(3,TimeUnit.SECONDS);assertEquals(1,registry.logins().size());
            Map<?,?> actors=(Map<?,?>)ReflectionTestUtils.getField(registry,"actors");
            ActorRef ref=(ActorRef)((CompletableFuture<?>)actors.get("player-a")).get(3,TimeUnit.SECONDS);
            address.set(ref);assertTrue(ref.path().toString().contains("/player-supervisor/"));
            assertEquals(KickOldAgentResult.Outcome.SAVED_AND_STOPPED,registry.stop(stop).get(3,TimeUnit.SECONDS).getOutcome());
            assertTrue(ref.isTerminated());assertTrue(actors.isEmpty());assertTrue(registry.logins().isEmpty());
            assertEquals(finished.get(),registry.stop(stop).get(3,TimeUnit.SECONDS));
        }
        verify(requests,times(1)).stopped(eq("player-a"),any());
    }
    private PlayerActorRegistry registry(PlayerRequestStore requests,ExecutorService io,AkkaTestSystem runtime) {
        return new PlayerActorRegistry(requests,io,runtime.system,mock(SceneLinkManager.class),mock(PeerRegistry.class),
                new MockEnvironment().withProperty("spring.cloud.nacos.discovery.metadata.instanceid","agent"),
                new AgentGameCommandRegistry(List.of(new StartGameHandler())),
                new AgentSceneMessageRegistry(List.of(new ForwardSceneMessageHandler(),new MatchEndedHandler())));
    }
}
