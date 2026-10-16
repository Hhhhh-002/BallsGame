package com.h.agentserver.actor;
import akka.actor.ActorSystem;
import com.h.gamecore.actor.AkkaSupport;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 测试使用真实 Akka ActorSystem，不用普通实体模拟 Actor。 */
public final class AkkaTestSystem implements AutoCloseable {
    public final ActorSystem system;
    public AkkaTestSystem(int threads) {system=AkkaSupport.system("test-"+UUID.randomUUID(),threads);}
    @Override public void close() throws Exception {system.terminate();system.getWhenTerminated().toCompletableFuture().get(10,TimeUnit.SECONDS);}
}
