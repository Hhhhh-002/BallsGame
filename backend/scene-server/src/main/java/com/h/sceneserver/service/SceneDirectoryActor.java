package com.h.sceneserver.service;

import akka.actor.*;
import akka.japi.pf.DeciderBuilder;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.services.ServiceNames;
import com.h.gamecore.actor.AkkaSupport;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.BattleException;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.actor.SceneActor;
import com.h.sceneserver.config.BattleRules;
import com.h.sceneserver.registries.SceneGameCommandRegistry;
import com.h.sceneserver.store.MatchRankingStore;
import com.h.sceneserver.store.SceneRequestStore;
import org.springframework.core.env.Environment;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import static com.h.gamecore.protocol.Messages.require;

/** 场景目录状态也由 Akka 邮箱管理；创建的是子 Actor，不暴露 SceneActor 实例。 */
public final class SceneDirectoryActor extends AbstractActorWithTimers {
    public record Allocate(String agent,String actorId,String requestId,boolean forceNew) {}
    public record Get(long sceneId) {}
    public record Leave(String agent,SceneLeaveRequest request) {}
    public record Resync(String agent) {}
    public record Availability(long sceneId,boolean joinable,boolean closed) {}
    private enum Sweep { INSTANCE }
    private record Slot(ActorRef ref,boolean joinable) {}
    private record Allocation(long sceneId,long createdAt,String agent,boolean forceNew) {}
    private final Map<Long,Slot> scenes=new HashMap<>();
    private final Map<String,Map<String,Allocation>> allocations=new HashMap<>();
    private int allocationCount;
    private long ids=(System.currentTimeMillis()<<20)|ThreadLocalRandom.current().nextInt(1<<20);
    private final Environment env;
    private final PeerRegistry peers;
    private final MatchRankingStore rankings;
    private final SceneRequestStore requests;
    private final SceneGameCommandRegistry handlers;
    private final String instance;
    public SceneDirectoryActor(Environment env,PeerRegistry peers,MatchRankingStore rankings,SceneRequestStore requests,SceneGameCommandRegistry handlers) {
        this.env=env;this.peers=peers;this.rankings=rankings;this.handlers=handlers;
        this.requests=requests;
        instance=env.getRequiredProperty("spring.cloud.nacos.discovery.metadata.instanceid");
        require(!instance.isBlank(),GameError.INVALID_MESSAGE);BattleRules.load(env);
    }
    public static Props props(Environment env,PeerRegistry peers,MatchRankingStore rankings,SceneRequestStore requests,SceneGameCommandRegistry handlers) {
        return Props.create(SceneDirectoryActor.class,()->new SceneDirectoryActor(env,peers,rankings,requests,handlers));
    }
    @Override public void preStart() {
        getTimers().startTimerWithFixedDelay(Sweep.INSTANCE,Sweep.INSTANCE,Duration.ofSeconds(30));
    }
    @Override public SupervisorStrategy supervisorStrategy() {
        return new OneForOneStrategy(0,Duration.ofSeconds(1),DeciderBuilder.matchAny(failure->SupervisorStrategy.stop()).build());
    }
    @Override public Receive createReceive() {
        return receiveBuilder()
                .match(Allocate.class,m->AkkaSupport.reply(getSender(),getSelf(),()->CompletableFuture.completedFuture(allocate(m))))
                .match(Get.class,m->AkkaSupport.reply(getSender(),getSelf(),()->CompletableFuture.completedFuture(get(m.sceneId()).ref())))
                .match(Leave.class,m->AkkaSupport.reply(getSender(),getSelf(),()-> {
                    require(m.request().getSceneId()!=0 && !m.request().getJoinOperationId().isBlank(),GameError.INVALID_MESSAGE);
                    Slot slot=scenes.get(m.request().getSceneId());
                    return slot==null?CompletableFuture.completedFuture(SceneLeaveResult.newBuilder()
                            .setOutcome(SceneLeaveResult.Outcome.ALREADY_LEFT).build())
                            :AkkaSupport.<SceneLeaveResult>ask(slot.ref(),new SceneActor.Leave(m.agent(),m.request()));
                }))
                .match(Resync.class,m->scenes.values().forEach(slot->slot.ref().tell(new SceneActor.Resync(m.agent()),getSelf())))
                .match(Availability.class,m-> {
                    Slot slot=scenes.get(m.sceneId());
                    if(slot==null || !slot.ref().equals(getSender()))return;
                    if(m.closed()) {scenes.remove(m.sceneId());getContext().stop(slot.ref());}
                    else scenes.put(m.sceneId(),new Slot(slot.ref(),m.joinable()));
                })
                .matchEquals(Sweep.INSTANCE,m->sweep())
                .match(Terminated.class,m->scenes.values().removeIf(slot->slot.ref().equals(m.getActor())))
                .match(Boolean.class,m->{}) // 子 Actor 的过期检查应答，目录变更以 Availability 身份消息为准。
                .build();
    }
    private CreateSceneResult allocate(Allocate request) {
        String operation=request.requestId(),actor=request.actorId(),agent=request.agent();
        require(!operation.isBlank() && operation.length()<=128 && actor.length()<=64,GameError.INVALID_MESSAGE);
        if(actor.isBlank()) {
            try {require(UUID.fromString(operation).toString().equals(operation),GameError.INVALID_MESSAGE);}
            catch(IllegalArgumentException invalid) {throw new BattleException(GameError.INVALID_MESSAGE);}
        }
        String scope=actor.isBlank()?"legacy:"+agent:actor;
        var requests=allocations.get(scope);
        Allocation previous=requests==null?null:requests.get(operation);
        if(previous!=null) {
            require(previous.agent().equals(agent) && previous.forceNew()==request.forceNew(),GameError.OPERATION_CONFLICT);
            get(previous.sceneId());return route(previous.sceneId());
        }
        require(allocationCount<8192,GameError.SERVICE_UNAVAILABLE);
        Long target=request.forceNew()?null:scenes.entrySet().stream().filter(entry->entry.getValue().joinable())
                .map(Map.Entry::getKey).min(Long::compare).orElse(null);
        if(target==null) {
            int max=env.getProperty("battle.max-scenes-per-instance",Integer.class,128);
            require(max>0 && scenes.size()<max,GameError.SERVICE_UNAVAILABLE);
            target=++ids;
            ActorRef ref=getContext().actorOf(SceneActor.props(target,instance,BattleRules.load(env),getSelf(),peers,rankings,this.requests,handlers),"scene-"+target);
            getContext().watch(ref);
            scenes.put(target,new Slot(ref,true));
        }
        allocations.computeIfAbsent(scope,key->new HashMap<>()).put(operation,
                new Allocation(target,System.currentTimeMillis(),agent,request.forceNew()));
        allocationCount++;
        return route(target);
    }
    private Slot get(long id) {Slot slot=scenes.get(id);require(slot!=null,GameError.SCENE_NOT_FOUND);return slot;}
    private CreateSceneResult route(long id) {
        return CreateSceneResult.newBuilder().setSceneId(id).setSceneHostInstanceId(instance).setSceneHostServiceName(ServiceNames.SCENE).build();
    }
    private void sweep() {
        long idle=TimeUnit.SECONDS.toNanos(env.getProperty("battle.empty-scene-idle-seconds",Long.class,60L));
        scenes.values().forEach(slot->slot.ref().tell(new SceneActor.Expire(idle),getSelf()));
        for(var requests:allocations.values()) {
            int before=requests.size();
            requests.entrySet().removeIf(entry->System.currentTimeMillis()-entry.getValue().createdAt()>600000);
            allocationCount-=before-requests.size();
        }
        allocations.values().removeIf(Map::isEmpty);
    }
}
