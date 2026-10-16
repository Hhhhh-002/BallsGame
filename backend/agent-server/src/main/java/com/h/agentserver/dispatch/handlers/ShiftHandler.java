package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.actor.PlayerActor;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

/** 转发 SHIFT 到当前 Scene；Scene 的业务结果才是最终结果。 */
@Component
@GameCommand(GameCommands.SHIFT)
public final class ShiftHandler implements AgentGameHandler {
    public boolean battleInput() { return true; }
    public boolean cacheResponse() { return false; }
    public CompletableFuture<ServerEnvelope> handle(PlayerActor actor, DecodedMessage<ClientEnvelope, GameCommands> message) {
        return actor.forwardSceneInput(message.envelope()).thenApply(v -> null);
    }
}
