package com.h.sceneserver.service;

import akka.actor.*;
import com.h.gamecore.actor.AkkaSupport;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.actor.SceneActor;
import com.h.sceneserver.config.BattleRules;
import com.h.sceneserver.registries.SceneGameCommandRegistry;
import com.h.sceneserver.store.MatchRankingStore;
import com.h.sceneserver.store.SceneRequestStore;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;

/** Spring 入口只持有目录 ActorRef；目录和比赛状态由 Akka Actor 管理。 */
@Component
public final class SceneDirectory {
    private final ActorRef directory;
    private final ActorSystem system;
    public SceneDirectory(Environment env,ActorSystem system,PeerRegistry peers,MatchRankingStore rankings,
            SceneRequestStore requests, SceneGameCommandRegistry gameHandlers) {
        BattleRules.load(env);
        this.system=system;
        directory=system.actorOf(SceneDirectoryActor.props(env,peers,rankings,requests,gameHandlers),"scene-directory");
    }
    public CompletableFuture<CreateSceneResult> allocate(String agent,String actorId,String requestId,boolean forceNew) {
        return AkkaSupport.ask(directory,new SceneDirectoryActor.Allocate(agent,actorId,requestId,forceNew));
    }
    public CompletableFuture<ActorRef> get(long id) {return AkkaSupport.ask(directory,new SceneDirectoryActor.Get(id));}
    public CompletableFuture<SceneJoinResult> join(String agent,SceneJoinRequest request) {
        return get(request.getSceneId()).thenCompose(ref->AkkaSupport.ask(ref,new SceneActor.Join(agent,request)));
    }
    public CompletableFuture<Void> input(String agent,ScenePlayerInput request) {
        return get(request.getSceneId()).thenCompose(ref->AkkaSupport.ask(ref,new SceneActor.Input(agent,request)));
    }
    public CompletableFuture<SceneLeaveResult> leave(String agent,SceneLeaveRequest request) {
        return AkkaSupport.ask(directory,new SceneDirectoryActor.Leave(agent,request));
    }
    public void resyncAgent(String agent) {directory.tell(new SceneDirectoryActor.Resync(agent),ActorRef.noSender());}
    @PreDestroy public void close() {system.stop(directory);}
}
