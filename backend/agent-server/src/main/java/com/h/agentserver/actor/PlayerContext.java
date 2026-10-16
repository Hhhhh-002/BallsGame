package com.h.agentserver.actor;

import com.h.gamecore.actor.MessageContext;
import com.h.gameprotocol.proto.ClientEnvelope;
import com.h.gameprotocol.proto.CreatePlayerAgentRequest;
import java.util.concurrent.CompletableFuture;

/** 一个 PlayerActor 一份 Context；可变字段仅由这个 Actor 的邮箱访问，不写用户资料表。 */
public final class PlayerContext {
    public record Route(String instance, long scene, String nickname, long requestId) {
        public String join() { return Long.toUnsignedString(requestId); }
    }
    public final CreatePlayerAgentRequest login;
    public final MessageContext messages = new MessageContext(1024);
    ClientEnvelope routeRequest;
    Route route;
    CompletableFuture<?> transition;
    boolean joined, closing, stopped;
    // 本局进入结算后一直保持 true；下一局 JOIN 成功才恢复接收战斗输入。
    boolean roundEnding;

    public PlayerContext(CreatePlayerAgentRequest login) { this.login = login; }
    public String actorId() { return login.getPlayerActorId(); }
    public String userId() { return login.getUserId(); }
    public String gatewayId() { return login.getGatewayInstanceId(); }
}
