package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.actor.PlayerActor;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.proto.ServerEnvelope;
import org.springframework.stereotype.Component;

/** 最终榜需校验冻结状态，并触发原有的离场清理。 */
@Component
@GameCommand(GameCommands.MATCH_ENDED)
public final class MatchEndedHandler implements AgentSceneMessageHandler {
    public void handle(PlayerActor actor, String sceneInstance, ServerEnvelope message) {
        actor.matchEnded(sceneInstance, message);
    }
}
