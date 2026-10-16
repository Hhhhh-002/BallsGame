package com.h.agentserver.actor;

import akka.actor.*;
import akka.japi.pf.DeciderBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 用 Akka supervision/DeathWatch 管理玩家 Actor；异常停止，不用陈旧构造参数静默重启会话。 */
public final class PlayerActorSupervisor extends AbstractActor {
    public record Spawn(String id,Props props,Runnable terminated) {}
    private final Map<ActorRef,Runnable> watched=new HashMap<>();
    @Override public SupervisorStrategy supervisorStrategy() {
        return new OneForOneStrategy(0,Duration.ofSeconds(1),DeciderBuilder.matchAny(failure->SupervisorStrategy.stop()).build());
    }
    @Override public Receive createReceive() {
        return receiveBuilder().match(Spawn.class,message-> {
            try {
                String name="player-"+Base64.getUrlEncoder().withoutPadding().encodeToString(message.id().getBytes(StandardCharsets.UTF_8));
                ActorRef actor=getContext().actorOf(message.props(),name);
                watched.put(actor,message.terminated());getContext().watch(actor);
                getSender().tell(actor,getSelf());
            } catch(Exception failure) {getSender().tell(new Status.Failure(failure),getSelf());}
        }).match(Terminated.class,message-> {
            Runnable callback=watched.remove(message.getActor());if(callback!=null)callback.run();
        }).build();
    }
}
