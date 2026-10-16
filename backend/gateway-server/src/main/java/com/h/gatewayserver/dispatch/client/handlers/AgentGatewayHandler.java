package com.h.gatewayserver.dispatch.client.handlers;

import com.h.gamecommon.code.GameError;
import com.h.gamecommon.exception.GatewayDispatchException;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gamecommon.command.GameCommands;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.proto.ForwardPlayerCommand;
import com.h.gameprotocol.proto.ClientEnvelope;
import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gatewayserver.annotions.GatewayCommand;
import com.h.gatewayserver.context.GatewayContext;
import com.h.gatewayserver.dispatch.client.result.GatewayHandlerResult;
import com.h.gatewayserver.rpc.agent.AgentLinkManager;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 游戏操作发往登录结果指定的 Agent 实例；结果由 Agent 异步推送。 */
@Component
@GatewayCommand({ GameCommands.SPLIT, GameCommands.EJECT, GameCommands.START_GAME,GameCommands.LEAVE,GameCommands.SHIFT,GameCommands.MATCH_HISTORY})
public class AgentGatewayHandler implements GatewayCommandHandler {
    private final AgentLinkManager agentLinks;

    public AgentGatewayHandler(AgentLinkManager agentLinks) {
        this.agentLinks = agentLinks;
    }
//构造器注入
    @Override
    public Mono<GatewayHandlerResult> handle(GatewayContext context,
                                            DecodedMessage<ClientEnvelope, GameCommands> message) {
        if (context.getUserId() == null || context.getUserId().isBlank() || context.getPlayerActorId() == null
                || context.getPlayerActorId().isBlank()
                || context.getAgentHostServiceName() == null || context.getAgentHostServiceName().isBlank()
                || context.getAgentHostInstanceId() == null || context.getAgentHostInstanceId().isBlank()) {
            throw new GatewayDispatchException(GameError.AGENT_ROUTE_MISSING);
        }
        ForwardPlayerCommand forward = ForwardPlayerCommand.newBuilder()
                .setPlayerActorId(context.getPlayerActorId()).setClientMessage(message.envelope()).build();
        ServiceEnvelope request = ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder()
                .setCmd(ServiceCommands.FORWARD_PLAYER_COMMAND.code())
                .setBody(forward.toByteString())).build();
        // 写入长连接不等于业务完成；Agent 后续以 FORWARD_TO_CLIENT 事件回传结果。
        return agentLinks.send(context.getAgentHostServiceName(),
                context.getAgentHostInstanceId(), request).then(Mono.empty());
    }
}
