package com.h.sceneserver.dispatch.handlers;

import com.google.protobuf.Message;
import com.h.sceneserver.service.SceneDirectory;
import com.h.gamecommon.annotation.ServiceCommand;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.*;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import static com.h.gamecore.protocol.Messages.require;

/** SCENE_PLAYER_INPUT 事件入口；单场失败返回玩家错误，不关闭承载其他玩家的长连接。 */
@Component
@ServiceCommand(ServiceCommands.SCENE_PLAYER_INPUT)
public final class ScenePlayerInputHandler implements SceneServiceHandler {
    private final SceneDirectory scenes;
    private final PeerRegistry peers;
    public ScenePlayerInputHandler(SceneDirectory scenes, PeerRegistry peers) { this.scenes = scenes; this.peers = peers; }
    public boolean event() { return true; }
    public CompletionStage<ServiceEnvelope> handle(Channel channel, DecodedMessage<? extends Message, ServiceCommands> message) {
        String agent = channel.attr(PeerRegistry.PEER_ID).get();
        ScenePlayerInput input = (ScenePlayerInput) message.body();
        require(input.hasClientMessage(), GameError.INVALID_MESSAGE);
        try {
            return scenes.input(agent, input).handle((v, failure) -> {
                if (failure != null) inputError(agent, input, failure);
                return null;
            });
        } catch (BattleException failure) {
            inputError(agent, input, failure);
            return CompletableFuture.completedFuture(null);
        }
    }
    private void inputError(String agent, ScenePlayerInput input, Throwable failure) {
        peers.send(agent, Messages.event(ServiceCommands.SCENE_TO_AGENT, SceneToAgentEvent.newBuilder()
                .setSceneId(input.getSceneId()).addTargetUserIds(input.getUserId())
                .addTargetPlayerActorIds(input.getPlayerActorId()).addTargetJoinOperationIds(input.getJoinOperationId())
                .setClientMessage(Messages.clientError(input.getClientMessage(), failure)).build()));
    }
}
