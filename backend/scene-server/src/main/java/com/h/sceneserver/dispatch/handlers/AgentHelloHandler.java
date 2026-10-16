package com.h.sceneserver.dispatch.handlers;

import com.google.protobuf.Message;
import com.h.sceneserver.service.SceneDirectory;
import com.h.gamecommon.annotation.ServiceCommand;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.Messages;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletionStage;

/** 先确认 Agent 握手再补发快照，避免客户端 ready 完成前丢掉恢复消息。 */
@Component
@ServiceCommand(ServiceCommands.AGENT_SCENE_LINK_HELLO)
public final class AgentHelloHandler implements SceneServiceHandler {
    private final SceneDirectory scenes;
    private final PeerRegistry peers;
    public AgentHelloHandler(SceneDirectory scenes, PeerRegistry peers) { this.scenes = scenes; this.peers = peers; }
    public boolean requiresBoundPeer() { return false; }
    public CompletionStage<ServiceEnvelope> handle(Channel channel, DecodedMessage<? extends Message, ServiceCommands> message) {
        String agent = ((AgentSceneLinkHello) message.body()).getAgentHostInstanceId();
        peers.bind(agent, channel);
        return PeerRegistry.write(channel, Messages.response((ServiceRequest) message.envelope(), null))
                .thenApply(v -> { scenes.resyncAgent(agent); return null; });
    }
}
