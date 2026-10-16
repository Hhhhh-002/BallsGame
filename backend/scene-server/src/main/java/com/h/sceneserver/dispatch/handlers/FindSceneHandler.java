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

/** FIND_SCENE 的独立 Handler，沿用原分配/入场/离场规则。 */
@Component
@ServiceCommand(ServiceCommands.FIND_SCENE)
public final class FindSceneHandler implements SceneServiceHandler {
    private final SceneDirectory scenes;
    public FindSceneHandler(SceneDirectory scenes) { this.scenes = scenes; }
    public CompletionStage<ServiceEnvelope> handle(Channel channel, DecodedMessage<? extends Message, ServiceCommands> message) {
        String agent = channel.attr(PeerRegistry.PEER_ID).get();
        FindSceneRequest body = (FindSceneRequest) message.body();
        return scenes.allocate(agent, body.getPlayerActorId(), body.getOperationId(), false).thenApply(result -> Messages.response((ServiceRequest) message.envelope(), result));
    }
}
