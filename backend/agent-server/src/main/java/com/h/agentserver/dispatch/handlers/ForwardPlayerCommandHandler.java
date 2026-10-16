package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.service.PlayerActorRegistry;
import com.h.gamecommon.annotation.ServiceCommand;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletionStage;

/** 将已解码的玩家消息送入对应 Actor 邮箱，业务响应沿原链路异步回传。 */
@Component
@ServiceCommand(ServiceCommands.FORWARD_PLAYER_COMMAND)
public final class ForwardPlayerCommandHandler implements AgentServiceHandler {
    private final PlayerActorRegistry actors;
    public ForwardPlayerCommandHandler(PlayerActorRegistry actors) { this.actors = actors; }
    public CompletionStage<ServiceEnvelope> handle(Channel channel, DecodedMessage<ServiceRequest, ServiceCommands> message) {
        return actors.forward(channel.attr(PeerRegistry.PEER_ID).get(), (ForwardPlayerCommand) message.body()).thenApply(v -> null);
    }
}
