package com.h.agentmgrserver.dispatch.handlers;

import com.h.agentmgrserver.annotation.AgentMgrCommand;
import com.h.agentmgrserver.service.PlayerLifecycleService;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.BeginLoginRequest;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import org.springframework.stereotype.Component;

/** Login 已完成凭据校验，AgentMgr 只执行在线仲裁及 Actor 生命周期。 */
@Component
@AgentMgrCommand(ServiceCommands.BEGIN_LOGIN)
public class BeginLoginHandler implements AgentMgrHandler {
    private final PlayerLifecycleService lifecycle;
    public BeginLoginHandler(PlayerLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    @Override
    public ServiceResponse handle(DecodedMessage<ServiceRequest, ServiceCommands> message) {
        var binding = lifecycle.beginLogin((BeginLoginRequest) message.body());
        return ServiceResponse.newBuilder().setCmd(message.envelope().getCmd())
                .setRequestId(message.envelope().getRequestId()).setBody(binding.toByteString()).build();
    }
}
