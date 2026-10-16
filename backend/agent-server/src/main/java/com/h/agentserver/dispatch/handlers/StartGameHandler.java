package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.actor.PlayerActor;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

/** 开始比赛：沿用 Actor 原有的选服、分配、入场与超时恢复流程。 */
@Component
@GameCommand(GameCommands.START_GAME)
public final class StartGameHandler implements AgentGameHandler {
    @Override public boolean retriesOriginalTarget() { return true; }
    public CompletableFuture<ServerEnvelope> handle(PlayerActor actor, DecodedMessage<ClientEnvelope, GameCommands> message) {
        return actor.startGame(message.envelope(), (StartGameRequest) message.body());
    }
    public void replay(PlayerActor actor, ClientEnvelope request, boolean completed) {
        actor.replayStartGame(request, completed);
    }
}
