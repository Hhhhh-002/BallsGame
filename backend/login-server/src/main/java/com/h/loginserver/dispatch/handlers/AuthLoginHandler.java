package com.h.loginserver.dispatch.handlers;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.BeginLoginRequest;
import com.h.gameprotocol.proto.GatewayLoginRequest;
import com.h.gameprotocol.proto.LoginBinding;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import com.h.loginserver.account.AccountAuthenticationService;
import com.h.loginserver.account.AccountRepository;
import com.h.loginserver.annotation.LoginServiceCommand;
import com.h.gamecommon.exception.LoginServiceException;
import com.h.loginserver.rpc.feign.AgentMgrLoginRpcClient;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** AUTH_LOGIN：MySQL 鉴权 -> AgentMgr 完成登录分配 -> 返回 Gateway 内部路由。 */
@Component
@Slf4j
@LoginServiceCommand(ServiceCommands.AUTH_LOGIN)
public class AuthLoginHandler implements LoginServiceHandler {
    private final AccountAuthenticationService authentication;
    private final AgentMgrLoginRpcClient agentMgr;
    /** 成功登录后更新持久化时间，不保存玩家在线状态或路由。 */
    private final AccountRepository accounts;

    /** Spring 注入鉴权和 RPC 组件；同一 Handler Bean 可以并发处理不同账号请求。 */
    public AuthLoginHandler(AccountAuthenticationService authentication, AgentMgrLoginRpcClient agentMgr,
                            AccountRepository accounts) {
        this.authentication = authentication;
        this.agentMgr = agentMgr;
        this.accounts = accounts;
    }

    /** 不保存用户在线状态，不直接创建 PlayerAgent，也不直接向客户端 Channel 写消息。 */
    @Override
    public ServiceResponse handle(DecodedMessage<ServiceRequest, ServiceCommands> message) {
        ServiceRequest request = message.envelope();
        GatewayLoginRequest body = (GatewayLoginRequest) message.body();
        if (!body.hasCredentials() || body.getLoginOperationId().isBlank()
                || body.getLoginOperationId().length() > 64 || body.getLoginDeadlineMs() <= 0
                || body.getGatewayInstanceId().isBlank()
                || body.getGatewayInstanceId().length() > 256) {
            throw new LoginServiceException(GameError.INVALID_MESSAGE);
        }
        // userId 只来源于校验通过的数据库账号，不能对客户端 account 做 tonumber/parseLong。
        String userId = authentication.authenticate(body.getCredentials());
        BeginLoginRequest begin = BeginLoginRequest.newBuilder()
                .setUserId(userId).setGatewayInstanceId(body.getGatewayInstanceId())
                .setLoginOperationId(body.getLoginOperationId()).setLoginDeadlineMs(body.getLoginDeadlineMs()).build();
        ServiceResponse managerResponse = agentMgr.beginLogin(begin);
        if (managerResponse.getCode() != 0) {
            // AgentMgr 的业务失败返回 Gateway；关联字段改回 Gateway -> Login 这一跳。
            return ServiceResponse.newBuilder().setCmd(request.getCmd()).setRequestId(request.getRequestId())
                    .setCode(managerResponse.getCode()).setMessage(managerResponse.getMessage()).build();
        }
        final LoginBinding binding;
        try {
            binding = LoginBinding.parseFrom(managerResponse.getBody());
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalStateException("AgentMgr 登录结果 body 不是 LoginBinding", exception);
        }
        // 不接受 code=0 但尚未创建 Actor、未加载完成或路由信息缺失的中间结果。
        if (!body.getLoginOperationId().equals(binding.getLoginOperationId())
                || !userId.equals(binding.getUserId()) || binding.getPlayerActorId().isBlank()
                || binding.getAgentHostInstanceId().isBlank() || binding.getAgentHostServiceName().isBlank()) {
            throw new IllegalStateException("AgentMgr 登录结果缺少已确认的玩家路由或 userId 不匹配");
        }
        // “最近登录”以 AgentMgr 最终成功为准，账号校验失败或分配失败不会更新这个时间。
        accounts.recordSuccessfulLogin(userId, System.currentTimeMillis());
        log.info("AgentMgr 已完成登录分配, userId={}, gatewayRequestId={}", userId, request.getRequestId());
        // 返回到原 HTTP 请求；Gateway 保存 binding 并确认 Agent 长连接，再向客户端回登录结果。
        // 对应 Lua 的 sure_agent，但不再增加一次 Login -> Gateway 的独立回调。
        return ServiceResponse.newBuilder().setCmd(request.getCmd()).setRequestId(request.getRequestId())
                .setCode(0).setBody(binding.toByteString()).build();
    }
}
