package com.h.sceneserver.dispatch.handlers;

import com.h.sceneserver.actor.SceneActor;
import com.h.gamecommon.annotation.GameCommand;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import org.springframework.stereotype.Component;

/** SPLIT 的独立处理器，所有可变战斗数据仍由 SceneActor 持有。 */
@Component
@GameCommand(GameCommands.SPLIT)
public final class SplitHandler implements SceneGameHandler {
    public void handle(SceneActor scene, String userId, DecodedMessage<ClientEnvelope, GameCommands> message) {
        scene.splitInput(userId, (SplitRequest) message.body());
    }
}
