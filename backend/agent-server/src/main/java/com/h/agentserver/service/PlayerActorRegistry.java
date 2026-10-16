package com.h.agentserver.service;

import com.h.agentserver.actor.*;
import com.h.agentserver.rpc.SceneLinkManager;
import com.h.agentserver.store.PlayerRequestStore;
import com.h.agentserver.registries.*;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.services.ServiceNames;
import com.h.gamecore.actor.AkkaSupport;
import akka.actor.*;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gameprotocol.proto.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import static com.h.gamecore.protocol.Messages.require;

/** 当前 Actor/Context 在内存；原创建/停止请求及最终结果在 Redis，不依赖 MySQL。 */
@Component
public final class PlayerActorRegistry {
    private record StopPlan(KickOldAgentResult completed,boolean needsCommit) {}
    private record CompletedStop(KickOldAgentResult result,long expiresAt,boolean committed) {}
    private static final System.Logger LOG=System.getLogger(PlayerActorRegistry.class.getName());
    private final ConcurrentMap<String,CompletableFuture<ActorRef>> actors=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,PlayerContext> contexts=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,CompletableFuture<KickOldAgentResult>> stops=new ConcurrentHashMap<>();
    /** 正常下线已执行、Redis 响应提交失败时保留原结果；重试只补提交，不补建 Actor。 */
    private final LinkedHashMap<String,CompletedStop> stopResponses=new LinkedHashMap<>();
    private final long responseRetentionMs;
    private final PlayerRequestStore requests;
    private final ExecutorService io;
    private final ActorSystem system;
    private final ActorRef supervisor;
    private final SceneLinkManager scenes;
    private final PeerRegistry gateways;
    private final String instance;
    private final AgentGameCommandRegistry gameHandlers;
    private final AgentSceneMessageRegistry sceneHandlers;
    public PlayerActorRegistry(PlayerRequestStore requests,ExecutorService io,ActorSystem system,
            SceneLinkManager scenes,PeerRegistry gateways,Environment env,
            AgentGameCommandRegistry gameHandlers,AgentSceneMessageRegistry sceneHandlers) {
        this.requests=requests;this.io=io;this.system=system;this.scenes=scenes;this.gateways=gateways;
        this.gameHandlers=gameHandlers;this.sceneHandlers=sceneHandlers;
        supervisor=system.actorOf(Props.create(PlayerActorSupervisor.class),"player-supervisor");
        instance=env.getRequiredProperty("spring.cloud.nacos.discovery.metadata.instanceid");
        responseRetentionMs=Math.multiplyExact(env.getProperty("agent.stopped-request-retention-seconds",Long.class,86400L),1000L);
        require(!instance.isBlank(),GameError.INVALID_MESSAGE);
        scenes.receiver(this::sceneEvent);
    }
    public CompletableFuture<CreatePlayerAgentResult> create(CreatePlayerAgentRequest request) {
        require(!request.getUserId().isBlank() && request.getUserId().length()<=64
                && !request.getGatewayInstanceId().isBlank() && request.getGatewayInstanceId().length()<=256
                && !request.getPlayerActorId().isBlank() && request.getPlayerActorId().length()<=64
                && !request.getLoginOperationId().isBlank() && request.getLoginOperationId().length()<=64,GameError.INVALID_MESSAGE);
        return CompletableFuture.supplyAsync(()-> {
            var claim=requests.claimCreate(request,instance);
            require(claim.owner() || actors.containsKey(request.getPlayerActorId()),GameError.AGENT_ROUTE_MISSING);
            require(!requests.stopping(request.getPlayerActorId()),GameError.LOGOUT_IN_PROGRESS);
            return request;
        },io).thenCompose(this::actor)
                .thenCompose(ref->AkkaSupport.<PlayerPublicInfo>ask(ref,PlayerActor.Info.INSTANCE))
                .thenCompose(info->CompletableFuture.supplyAsync(()-> {
                    require(!requests.stopping(request.getPlayerActorId()),GameError.LOGOUT_IN_PROGRESS);
                    CreatePlayerAgentResult result=CreatePlayerAgentResult.newBuilder().setPlayerActorId(request.getPlayerActorId())
                            .setAgentHostInstanceId(instance).setAgentHostServiceName(ServiceNames.AGENT).setPlayer(info).build();
                    requests.created(request.getPlayerActorId(),result);return result;
                },io));
    }
    private CompletableFuture<ActorRef> actor(CreatePlayerAgentRequest login) {
        String id=login.getPlayerActorId();
        CompletableFuture<ActorRef> created=new CompletableFuture<>();
        CompletableFuture<ActorRef> existing=actors.putIfAbsent(id,created);
        if(existing!=null)return existing;
        var originalContext=new java.util.concurrent.atomic.AtomicReference<PlayerContext>();
        try {
            CompletableFuture.completedFuture(new PlayerContext(login)).thenCompose(context-> {
                originalContext.set(context);
                contexts.put(id,context);
                return AkkaSupport.<ActorRef>ask(supervisor,new PlayerActorSupervisor.Spawn(id,
                        PlayerActor.props(context,scenes,gateways,requests,io,gameHandlers,sceneHandlers),()-> {
                            actors.remove(id,created);contexts.remove(id,context);
                            created.completeExceptionally(new TimeoutException("PlayerActor 已停止"));
                        }));
            }).whenComplete((ref,failure)-> {
                if(failure!=null) {
                    var context=originalContext.get();if(context!=null)contexts.remove(id,context);
                    actors.remove(id,created);created.completeExceptionally(failure);return;
                }
                AkkaSupport.<PlayerPublicInfo>ask(ref,PlayerActor.Info.INSTANCE).whenComplete((info,startFailure)-> {
                    if(startFailure!=null) {system.stop(ref);created.completeExceptionally(startFailure);}
                    else created.complete(ref);
                });
            });
        } catch(RuntimeException failure) {created.completeExceptionally(failure);actors.remove(id,created);}
        return created;
    }
    public CompletableFuture<KickOldAgentResult> stop(KickOldAgentRequest request) {
        require(!request.getUserId().isBlank() && !request.getPlayerActorId().isBlank(),GameError.INVALID_MESSAGE);
        String id=request.getPlayerActorId();
        CompletableFuture<KickOldAgentResult> created=new CompletableFuture<>();
        CompletableFuture<KickOldAgentResult> existing=stops.putIfAbsent(id,created);
        if(existing!=null)return existing.thenApply(result-> {
            require(result.getUserId().equals(request.getUserId()),GameError.OPERATION_CONFLICT);return result;
        });
        try {
            CompletableFuture.supplyAsync(()-> {
                var creation=requests.creation(id);
                require(creation!=null,GameError.AGENT_ROUTE_MISSING);
                require(creation.body().getUserId().equals(request.getUserId())
                        && creation.instance().equals(instance),GameError.OPERATION_CONFLICT);
                requests.claimStop(request);
                var cached=completedStop(id);
                return cached!=null?new StopPlan(cached.result(),!cached.committed()):new StopPlan(requests.stopResult(id),false);
            },io).thenCompose(plan-> {
                if(plan.completed()!=null) {
                    require(plan.completed().getUserId().equals(request.getUserId()),GameError.OPERATION_CONFLICT);
                    return plan.needsCommit()?commitStop(id,plan.completed()):CompletableFuture.completedFuture(plan.completed());
                }
                var found=actors.get(id);
                require(found!=null,GameError.AGENT_ROUTE_MISSING);
                return found.thenCompose(ref->AkkaSupport.<Void>ask(ref,PlayerActor.PrepareStop.INSTANCE)
                        .thenCompose(v->AkkaSupport.stop(ref,PlayerActor.Stop.INSTANCE)))
                        .thenCompose(v->{
                            KickOldAgentResult result=KickOldAgentResult.newBuilder().setUserId(request.getUserId())
                                    .setPlayerActorId(id).setOutcome(KickOldAgentResult.Outcome.SAVED_AND_STOPPED).build();
                            // 兼容旧枚举名：现已不保存多余的玩家资料，表示离场完成且 Actor 已实际停止。
                            rememberStop(id,result);
                            actors.remove(id);contexts.remove(id);
                            return commitStop(id,result);
                        });
            }).whenComplete((result,failure)-> {
                if(failure==null)created.complete(result);else created.completeExceptionally(failure);
                stops.remove(id,created);
            });
        } catch(RuntimeException failure) {created.completeExceptionally(failure);stops.remove(id,created);}
        return created;
    }
    private CompletableFuture<KickOldAgentResult> commitStop(String id,KickOldAgentResult result) {
        return CompletableFuture.supplyAsync(()-> {
            requests.stopped(id,result);
            markStopCommitted(id,result);
            try {requests.retired(id);}catch(RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING,"已停止，去重记录过期设置失败: "+id,failure);
            }
            return result;
        },io);
    }
    private synchronized CompletedStop completedStop(String id) {
        long now=System.currentTimeMillis();stopResponses.values().removeIf(value->value.expiresAt()<=now);
        return stopResponses.get(id);
    }
    private synchronized void rememberStop(String id,KickOldAgentResult result) {
        stopResponses.put(id,new CompletedStop(result,System.currentTimeMillis()+responseRetentionMs,false));
        while(stopResponses.size()>1024)stopResponses.remove(stopResponses.keySet().iterator().next());
    }
    private synchronized void markStopCommitted(String id,KickOldAgentResult result) {
        var cached=stopResponses.get(id);
        if(cached!=null && cached.result().equals(result))stopResponses.put(id,new CompletedStop(result,cached.expiresAt(),true));
    }
    public CompletableFuture<Void> forward(String gateway,ForwardPlayerCommand request) {
        require(request.hasClientMessage(),GameError.INVALID_MESSAGE);
        var found=actors.get(request.getPlayerActorId());
        var context=contexts.get(request.getPlayerActorId());
        require(found!=null && context!=null && context.gatewayId().equals(gateway)
                && !stops.containsKey(request.getPlayerActorId()),GameError.AGENT_ROUTE_MISSING);
        return found.thenCompose(ref->deliver(ref,gateway,request)); // 找不到就返回错误，不补建 Actor。
    }
    private CompletableFuture<Void> deliver(ActorRef ref,String gateway,ForwardPlayerCommand request) {
        return AkkaSupport.<Void>ask(ref,new PlayerActor.Forward(gateway,request.getClientMessage())).exceptionally(failure-> {
            ref.tell(new PlayerActor.Failure(request.getClientMessage(),failure),ActorRef.noSender());return null;
        });
    }
    /** 只暴露不可变登录参数，不在 Actor 线程外读取可变场景信息。 */
    public List<CreatePlayerAgentRequest> logins() {return contexts.values().stream().map(c->c.login).toList();}
    private void sceneEvent(String sceneInstance,SceneToAgentEvent event) {
        require(event.hasClientMessage() && event.getTargetUserIdsCount()==event.getTargetPlayerActorIdsCount()
                && event.getTargetUserIdsCount()==event.getTargetJoinOperationIdsCount(),GameError.INVALID_MESSAGE);
        for(int i=0;i<event.getTargetUserIdsCount();i++) {
            String user=event.getTargetUserIds(i),actorId=event.getTargetPlayerActorIds(i),join=event.getTargetJoinOperationIds(i);
            CompletableFuture<ActorRef> actor=actors.get(actorId);
            if(actor!=null)actor.thenAccept(ref->ref.tell(new PlayerActor.SceneMessage(user,sceneInstance,event,join),ActorRef.noSender()));
        }
    }
}
