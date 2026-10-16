package com.h.agentserver.dispatch.handlers;

import com.h.gamecommon.annotation.ServiceCommand;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.Messages;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** Gateway 长连接握手，只有此 Handler 可以在未绑定连接上执行。 */
@Component
@ServiceCommand(ServiceCommands.AGENT_LINK_HELLO)
public final class GatewayHelloHandler implements AgentServiceHandler {
    private final PeerRegistry peers;
    public GatewayHelloHandler(PeerRegistry peers) { this.peers = peers; }
    public boolean requiresBoundPeer() { return false; }
    public CompletionStage<ServiceEnvelope> handle(Channel channel, DecodedMessage<ServiceRequest, ServiceCommands> message) {
        peers.bind(((GatewayAgentLinkHello) message.body()).getGatewayInstanceId(), channel);
        return CompletableFuture.completedFuture(Messages.response(message.envelope(), null));
    }
}
