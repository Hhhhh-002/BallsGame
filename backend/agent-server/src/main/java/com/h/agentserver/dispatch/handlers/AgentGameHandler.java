package com.h.agentserver.dispatch.handlers;

import com.h.agentserver.actor.PlayerActor;
import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import java.util.concurrent.CompletableFuture;

/** 在玩家邮箱内调用；Spring Handler 无玩家状态，可供多个 Actor 共用。 */
public interface AgentGameHandler {
    CompletableFuture<ServerEnvelope> handle(PlayerActor actor, DecodedMessage<ClientEnvelope, GameCommands> message);
    /** 是否需要最终响应去重；连续方向输入关闭，低频操作及动作响应开启。 */
    default boolean cacheResponse() { return true; }
    /** 异步等待 Scene 真正的业务响应，不把 Channel 写入成功当成响应。 */
    default boolean forwardedResponse() { return false; }
    /** 本局战斗输入在结算后直接丢弃；入场、离场、战绩查询不属于此类。 */
    default boolean battleInput() { return false; }
    /** 入场 Handler 的正常重试沿用原场景目标，统一入口不按 cmd 写分支。 */
    default boolean retriesOriginalTarget() { return false; }
    /** 已完成请求的补发动作由对应 Handler 决定，不在统一入口判断 cmd。 */
    default void replay(PlayerActor actor, ClientEnvelope request, boolean completed) {}
}
