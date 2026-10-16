package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.service.PlayerActorRegistry;
import com.h.gamecommon.annotation.ServiceCommand;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecore.protocol.Messages;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletionStage;

/** HTTP CREATE_PLAYER_AGENT，保留原有持久化核对和共享 Future。 */
@Component
@ServiceCommand(ServiceCommands.CREATE_PLAYER_AGENT)
public final class CreatePlayerHandler implements AgentServiceHandler {
    private final PlayerActorRegistry actors;
    public CreatePlayerHandler(PlayerActorRegistry actors) { this.actors = actors; }
    public boolean http() { return true; }
    public CompletionStage<ServiceEnvelope> handle(Channel channel, DecodedMessage<ServiceRequest, ServiceCommands> message) {
        return actors.create((CreatePlayerAgentRequest) message.body()).thenApply(body -> Messages.response(message.envelope(), body));
    }
}
