package com.h.agentserver.actor;
import akka.actor.ActorRef;
import com.h.agentserver.registries.*;
import com.h.agentserver.rpc.SceneLinkManager;
import com.h.agentserver.store.*;
import com.h.gamecore.actor.AkkaSupport;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gameprotocol.proto.*;
import java.util.concurrent.*;

/** 仅测试的类型化消息入口；不持有或直接调用 PlayerActor 实例。 */
public final class PlayerEndpoint {
    public final ActorRef ref;
    private final String user;
    private final PlayerContext player;
    public PlayerEndpoint(CreatePlayerAgentRequest.Builder lease,AkkaTestSystem runtime,SceneLinkManager scenes,
            PeerRegistry gateways,PlayerRequestStore requests,Executor db,
            AgentGameCommandRegistry games,AgentSceneMessageRegistry outputs) {
        user=lease.getUserId();
        player=new PlayerContext(lease.build());
        ref=runtime.system.actorOf(PlayerActor.props(player,scenes,gateways,requests,db,games,outputs));
    }
    public CompletableFuture<Void> forward(String gateway,ClientEnvelope request) {
        return AkkaSupport.ask(ref,new PlayerActor.Forward(gateway,request));
    }
    /** 测试初始化也投递到真实 Actor 邮箱，不读取 Redis 恢复身份或场景。 */
    public CompletableFuture<Void> seedScene(ClientEnvelope request,String instance,long scene,String nickname) {
        return AkkaSupport.run(ref,()-> {
            player.routeRequest=request;player.route=new PlayerContext.Route(instance,scene,nickname,request.getRequestId());player.joined=true;
        });
    }
    public void sceneEvent(String instance,SceneToAgentEvent event,String join) {
        ref.tell(new PlayerActor.SceneMessage(user,instance,event,join),ActorRef.noSender());
    }
    public <T> CompletableFuture<T> inspect(java.util.function.Function<PlayerContext,T> query) {
        return AkkaSupport.call(ref,()->query.apply(player));
    }
}
