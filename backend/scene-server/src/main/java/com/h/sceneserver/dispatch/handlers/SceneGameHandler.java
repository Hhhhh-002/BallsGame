package com.h.sceneserver.dispatch.handlers;

import com.h.sceneserver.actor.SceneActor;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.ClientEnvelope;

/** 在场景邮箱中执行业务；不得在 Handler Bean 保存某场比赛的可变数据。 */
public interface SceneGameHandler {
    void handle(SceneActor scene, String userId, DecodedMessage<ClientEnvelope, GameCommands> message);
    /** 连续方向输入不逐条回成功包，其序号过滤由 ShiftHandler 对应业务处理。 */
    default boolean cacheResponse() { return true; }
}
