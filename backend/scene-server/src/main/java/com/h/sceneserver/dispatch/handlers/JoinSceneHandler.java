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
import java.util.concurrent.*;

/** SCENE_JOIN 的独立 Handler，沿用原分配/入场/离场规则。 */
@Component
@ServiceCommand(ServiceCommands.SCENE_JOIN)
public final class JoinSceneHandler implements SceneServiceHandler {
    private final SceneDirectory scenes;
    public JoinSceneHandler(SceneDirectory scenes) { this.scenes = scenes; }
    public CompletionStage<ServiceEnvelope> handle(Channel channel, DecodedMessage<? extends Message, ServiceCommands> message) {
        String agent = channel.attr(PeerRegistry.PEER_ID).get();
        SceneJoinRequest body = (SceneJoinRequest) message.body();
        return scenes.join(agent, body).thenApply(result -> Messages.response((ServiceRequest) message.envelope(), result));
    }
}
