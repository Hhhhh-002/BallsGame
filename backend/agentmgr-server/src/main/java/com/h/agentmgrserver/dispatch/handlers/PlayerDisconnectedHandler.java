package com.h.agentmgrserver.dispatch.handlers;

import com.h.agentmgrserver.annotation.AgentMgrCommand;
import com.h.agentmgrserver.service.PlayerLifecycleService;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.PlayerDisconnectedRequest;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import org.springframework.stereotype.Component;

/** 复用 Gateway 已定义的断线协议，只有最终保存结果或幂等/旧请求结果才返回成功。 */
@Component
@AgentMgrCommand(ServiceCommands.PLAYER_DISCONNECTED)
public class PlayerDisconnectedHandler implements AgentMgrHandler {
    private final PlayerLifecycleService lifecycle;
    public PlayerDisconnectedHandler(PlayerLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    @Override
    public ServiceResponse handle(DecodedMessage<ServiceRequest, ServiceCommands> message) {
        var result = lifecycle.disconnect((PlayerDisconnectedRequest) message.body());
        return ServiceResponse.newBuilder().setCmd(message.envelope().getCmd())
                .setRequestId(message.envelope().getRequestId()).setBody(result.toByteString()).build();
    }
}
