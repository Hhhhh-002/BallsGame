package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.actor.PlayerActor;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

/** 主动离场，保留账号登录；场景绑定清理仍在原 Actor 流程执行。 */
@Component
@GameCommand(GameCommands.LEAVE)
public final class LeaveGameHandler implements AgentGameHandler {
    public CompletableFuture<ServerEnvelope> handle(PlayerActor actor, DecodedMessage<ClientEnvelope, GameCommands> message) {
        return actor.leaveGame(message.envelope());
    }
}
