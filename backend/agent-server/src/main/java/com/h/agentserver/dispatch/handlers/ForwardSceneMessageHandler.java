package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.actor.PlayerActor;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.proto.ServerEnvelope;
import org.springframework.stereotype.Component;

/** 无附加业务的场景推送及输入响应共享转发 Handler，不逐命令写分支。 */
@Component
@GameCommand({GameCommands.MOVE, GameCommands.SCENE_SNAPSHOT, GameCommands.PLAYER_ENTERED,
        GameCommands.FOOD_ADDED, GameCommands.FOOD_EATEN, GameCommands.PLAYER_LEFT,
        GameCommands.BALL_CHANGED, GameCommands.MATCH_RANKING,
        GameCommands.SHIFT, GameCommands.SPLIT, GameCommands.EJECT})
public final class ForwardSceneMessageHandler implements AgentSceneMessageHandler {
    public void handle(PlayerActor actor, String sceneInstance, ServerEnvelope message) {
        actor.emitSceneMessage(message);
    }
}
