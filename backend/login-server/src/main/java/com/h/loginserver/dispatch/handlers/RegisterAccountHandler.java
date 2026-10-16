package com.h.loginserver.dispatch.handlers;

import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.RegisterRequest;
import com.h.gameprotocol.proto.RegisterResponse;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import com.h.loginserver.account.AccountRegistrationService;
import com.h.loginserver.annotation.LoginServiceCommand;
import org.springframework.stereotype.Component;

/** 复用现有 HTTP Controller 和分发器，注册异常由 LoginServiceDispatcher 统一回包。 */
@Component
@LoginServiceCommand(ServiceCommands.REGISTER_ACCOUNT)
public class RegisterAccountHandler implements LoginServiceHandler {
    private final AccountRegistrationService registration;

    public RegisterAccountHandler(AccountRegistrationService registration) {
        this.registration = registration;
    }

    /** INSERT 确认成功后才返回最终成功；不调用 AgentMgr，不创建 PlayerActor。 */
    @Override
    public ServiceResponse handle(DecodedMessage<ServiceRequest, ServiceCommands> message) {
        String userId = registration.register((RegisterRequest) message.body());
        return ServiceResponse.newBuilder().setCmd(message.envelope().getCmd())
                .setRequestId(message.envelope().getRequestId())
                .setBody(RegisterResponse.newBuilder().setUserId(userId).build().toByteString()).build();
    }
}
