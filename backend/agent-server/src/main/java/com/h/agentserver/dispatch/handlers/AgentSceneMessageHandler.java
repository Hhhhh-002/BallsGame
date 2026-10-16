package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.actor.PlayerActor;
import com.h.gameprotocol.proto.ServerEnvelope;

/** Scene 回传的游戏消息也按 cmd 注册；调用时已在玩家邮箱核对场景和入场身份。 */
public interface AgentSceneMessageHandler {
    void handle(PlayerActor actor, String sceneInstance, ServerEnvelope message);
}
