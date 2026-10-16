package com.h.gatewayserver.dispatch.client.handlers;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.code.LoginError;
import com.h.gamecommon.command.GameCommands;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import com.h.gatewayserver.annotions.GatewayCommand;
import com.h.gatewayserver.context.GatewayContext;
import com.h.gatewayserver.dispatch.client.result.GatewayHandlerResult;
import com.h.gatewayserver.rpc.GatewayRpcClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/** 客户端注册 -> Login Feign -> 原客户端响应；不改变 GatewayContext 的登录状态。 */
@Component
@GatewayCommand(GameCommands.REGISTER)
public class RegisterGatewayHandler implements GatewayCommandHandler {
    private final GatewayRpcClient rpc;
    private final AtomicLong requestIds = new AtomicLong();

    public RegisterGatewayHandler(GatewayRpcClient rpc) {
        this.rpc = rpc;
    }

    /** 邮箱、密码规则统一由 Login 校验，Gateway 不重复维护鉴权规则。 */
    @Override
    public Mono<GatewayHandlerResult> handle(GatewayContext context,
                                            DecodedMessage<ClientEnvelope, GameCommands> message) {
        if (context.getUserId() != null && !context.getUserId().isBlank()) {
            return Mono.just(error(message, GameError.LOGIN_HAVE_LOGINED));
        }
        if (!context.getLoginOperationId().isBlank()) {
            return Mono.just(error(message, GameError.LOGIN_IN_PROGRESS));
        }
        RegisterRequest body = (RegisterRequest) message.body();
        ServiceEnvelope request = ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder()
                .setCmd(ServiceCommands.REGISTER_ACCOUNT.code()).setRequestId(requestIds.incrementAndGet())
                .setBody(body.toByteString())).build();
        return rpc.register(request).map(response -> {
            if (response.getCode() != 0) {
                return error(message, LoginError.PUBLIC_REGISTER_ERRORS
                        .getOrDefault(response.getCode(), GameError.REGISTER_FAILED));
            }
            try {
                RegisterResponse result = RegisterResponse.parseFrom(response.getBody());
                if (result.getUserId().isBlank() || !body.getAccount().equals(result.getUserId())) {
                    throw new IllegalStateException("注册响应账号与请求不一致");
                }
                return new GatewayHandlerResult(ServerEnvelope.newBuilder()
                        .setCmd(message.envelope().getCmd()).setRequestId(message.envelope().getRequestId())
                        .setBody(result.toByteString()).build());
            } catch (InvalidProtocolBufferException invalid) {
                throw new IllegalStateException("Login 注册响应格式错误", invalid);
            }
        });
    }

    /** 错误保留客户端关联字段，仅公开受信任的枚举提示。 */
    private GatewayHandlerResult error(DecodedMessage<ClientEnvelope, GameCommands> message, GameError error) {
        return new GatewayHandlerResult(ServerEnvelope.newBuilder().setCmd(message.envelope().getCmd())
                .setRequestId(message.envelope().getRequestId()).setCode(error.code()).setMessage(error.message()).build());
    }
}
