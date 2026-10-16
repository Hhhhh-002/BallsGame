package com.h.sceneserver.actor;

import akka.actor.*;
import com.h.gamecore.actor.AkkaSupport;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.config.BattleRules;
import com.h.sceneserver.registries.SceneGameCommandRegistry;
import com.h.sceneserver.store.MatchRankingStore;
import com.h.sceneserver.store.SceneRequestStore;
import com.h.sceneserver.store.RedisRequestFixture;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** 测试只通过真实 ActorRef 发送消息；诊断/初始化也在 Actor 内执行，不在测试线程修改状态。 */
public final class SceneEndpoint {
    public static final class TestSystem implements AutoCloseable {
        public final ActorSystem system;
        public TestSystem(int threads) {system=AkkaSupport.system("test-"+UUID.randomUUID(),threads);}
        @Override public void close() throws Exception {system.terminate();system.getWhenTerminated().toCompletableFuture().get(10,TimeUnit.SECONDS);}
    }
    public static final class FrozenScene extends SceneActor {
        public FrozenScene(long id,String instance,BattleRules rules,PeerRegistry peers,MatchRankingStore rankings,SceneRequestStore requests,SceneGameCommandRegistry handlers) {
            super(id,instance,rules,null,peers,rankings,requests,handlers);
        }
        @Override public void preStart() {} // 隔离墙钟和随机食物；生产 Actor 使用 Akka Tick 定时器。
        @Override public Receive createReceive() {
            return receiveBuilder().match(AkkaSupport.Continuation.class,AkkaSupport::execute).build().orElse(super.createReceive());
        }
    }
    private final ActorRef ref;
    private final AtomicReference<SceneActor> instance=new AtomicReference<>();
    public SceneEndpoint(long id,String instanceId,BattleRules rules,TestSystem runtime,PeerRegistry peers,
            MatchRankingStore rankings,SceneGameCommandRegistry handlers) {
        this(id,instanceId,rules,runtime,peers,rankings,new RedisRequestFixture().store(),handlers);
    }
    public SceneEndpoint(long id,String instanceId,BattleRules rules,TestSystem runtime,PeerRegistry peers,
            MatchRankingStore rankings,SceneRequestStore requests,SceneGameCommandRegistry handlers) {
        ref=runtime.system.actorOf(Props.create(FrozenScene.class,()-> {
            var actor=new FrozenScene(id,instanceId,rules,peers,rankings,requests,handlers);instance.set(actor);return actor;
        }));
    }
    public CompletableFuture<SceneJoinResult> join(String agent,SceneJoinRequest request) {return AkkaSupport.ask(ref,new SceneActor.Join(agent,request));}
    public CompletableFuture<SceneLeaveResult> leave(String agent,SceneLeaveRequest request) {return AkkaSupport.ask(ref,new SceneActor.Leave(agent,request));}
    public CompletableFuture<Void> input(String agent,ScenePlayerInput request) {return AkkaSupport.ask(ref,new SceneActor.Input(agent,request));}
    public SceneSnapshot snapshot() throws Exception {
        ServerEnvelope envelope=AkkaSupport.<ServerEnvelope>ask(ref,SceneActor.Snapshot.INSTANCE).get(3,TimeUnit.SECONDS);
        return SceneSnapshot.parseFrom(envelope.getBody());
    }
    /** query 在真实 Actor 线程执行；调用方只取不可变结果，不应把可变内部对象返回测试线程。 */
    public <T> T inspect(Function<SceneActor,T> query) throws Exception {
        return AkkaSupport.call(ref,()->query.apply(instance.get())).get(3,TimeUnit.SECONDS);
    }
}
