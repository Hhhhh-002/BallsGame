package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.actor.PlayerActor;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

/** 查询没有修改状态的副作用，不占用开始/离场的 transition，也不永久缓存一份过时战绩。 */
@Component
@GameCommand(GameCommands.MATCH_HISTORY)
public final class MatchHistoryHandler implements AgentGameHandler {
    @Override public boolean cacheResponse() {return false;}
    @Override public CompletableFuture<ServerEnvelope> handle(PlayerActor actor,DecodedMessage<ClientEnvelope,GameCommands> message) {
        return actor.history(message.envelope(),(MatchHistoryRequest)message.body());
    }
}
