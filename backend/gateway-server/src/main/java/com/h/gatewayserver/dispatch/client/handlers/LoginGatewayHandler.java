package com.h.gatewayserver.dispatch.client.handlers;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.code.LoginError;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gamecommon.command.GameCommands;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.proto.*;
import com.h.gatewayserver.annotions.GatewayCommand;
import com.h.gatewayserver.registries.GatewayChannelRegistry;
import com.h.gatewayserver.context.GatewayContext;
import com.h.gatewayserver.dispatch.client.result.GatewayHandlerResult;
import com.h.gatewayserver.rpc.GatewayRpcClient;
import com.h.gatewayserver.rpc.agent.AgentLinkManager;
import com.h.gatewayserver.rpc.feign.AgentMgrDisconnectNotifierlCient;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Gateway 只转交凭据，并保存 Login/AgentMgr 最终确认的玩家路由。 */
@Component
@GatewayCommand(GameCommands.LOGIN)
public class LoginGatewayHandler implements GatewayCommandHandler {


    private final GatewayRpcClient rpcClient;
    private final AgentLinkManager agentLinks;
    private final GatewayChannelRegistry channels;
    private final AgentMgrDisconnectNotifierlCient disconnects;
    private final String gatewayInstanceId;
    /** 包括排队及 RPC 重试的登录总等待预算，不是每次尝试重新计时。 */
    private final long loginTimeoutMs;
    private final AtomicLong requestIds = new AtomicLong();
//构造器注入
    public LoginGatewayHandler(GatewayRpcClient rpcClient, AgentLinkManager agentLinks,
                               GatewayChannelRegistry channels,
                               AgentMgrDisconnectNotifierlCient disconnects,
                               @Value("${spring.cloud.nacos.discovery.metadata.instanceid:}") String gatewayInstanceId,
                               @Value("${gateway.rpc.timeout-ms:90000}") long loginTimeoutMs) {
        this.rpcClient = rpcClient;
        this.agentLinks = agentLinks;
        this.channels = channels;
        this.disconnects = disconnects;
        this.gatewayInstanceId = gatewayInstanceId;
        this.loginTimeoutMs = loginTimeoutMs;
    }
/**
 * 业务：接收客户端登录包 → 组装鉴权请求 → RPC 调用登录服务 → 鉴权成功拿到`LoginBinding`玩家路由信息 →
 * 预先确保网关和目标 Agent 场景服务长连接就绪
 * → 返回给网关调度器一个 GatewayHandlerResult（RPC 应答包 + 绑定信息 binding）。
 */
    /** 客户端与服务间 requestId 分离；服务间的只用于关联 Feign 调用。 */
    @Override
    public Mono<GatewayHandlerResult> handle(GatewayContext context,
                                            DecodedMessage<ClientEnvelope, GameCommands> message) {
        if (context.getUserId() != null && !context.getUserId().isBlank()) {
            // 当前连接已绑定字符串 userId，代表已经登录，禁止再次提交登录请求。
            return Mono.just(new GatewayHandlerResult(clientError(message, GameError.LOGIN_HAVE_LOGINED)));
        }
        if (!context.getLoginOperationId().isBlank()) {
            throw new com.h.gamecommon.exception.GatewayDispatchException(GameError.LOGIN_IN_PROGRESS);
        }
        if (gatewayInstanceId.isBlank()) {
            return Mono.error(new IllegalStateException("Gateway 未配置 Nacos instanceid"));
        }
        LoginRequest loginRequest=((LoginRequest) message.body());
        if(message.envelope().getRequestId()==0)return Mono.error(
                new com.h.gamecommon.exception.GatewayDispatchException(GameError.INVALID_MESSAGE));
        // 账号、密码的非空和格式规则统一交给 Login，避免两边重复维护且提示不一致。
        GatewayLoginRequest login = GatewayLoginRequest.newBuilder()
                .setCredentials(loginRequest).setGatewayInstanceId(gatewayInstanceId)
                // 沿用客户端登录 requestId；字段名保留兼容，不另生成操作 UUID。
                .setLoginOperationId(Long.toUnsignedString(message.envelope().getRequestId()))
                .setLoginDeadlineMs(System.currentTimeMillis() + loginTimeoutMs).build();
        channels.beginLogin(login.getLoginOperationId(), login.getLoginDeadlineMs(), context);
        ServiceEnvelope request = ServiceEnvelope.newBuilder().setRequest(ServiceRequest.newBuilder()
                .setCmd(ServiceCommands.AUTH_LOGIN.code()).setRequestId(requestIds.incrementAndGet())
                .setBody(login.toByteString())).build();
/**
 * - `rpcClient.login(request)`：异步发 RPC 请求到独立的登录鉴权服务；返回 `Mono<ServiceResponse>`
 * - flatMap 的 lambda，**在 RPC 应答回来时执行**
 */
        return rpcClient.login(request).flatMap(response -> {
            if (response.getCode() != 0) {
                // 保留可公开的细分错误码，未知内部错误降级为“登录失败”。
                GameError error = LoginError.PUBLIC_LOGIN_ERRORS.getOrDefault(response.getCode(), GameError.LOGIN_FAILED);
                return Mono.just(new GatewayHandlerResult(clientError(message, error)));
            }
            final LoginBinding binding;
            try {
                binding = LoginBinding.parseFrom(response.getBody());
            } catch (InvalidProtocolBufferException exception) {
                return Mono.error(new IllegalStateException("agent返回参数格式错误", exception));
            }
            if (!binding.getLoginOperationId().equals(login.getLoginOperationId())
                    || binding.getUserId().isBlank() || binding.getPlayerActorId().isBlank()
                    || binding.getAgentHostInstanceId().isBlank()
                    || binding.getAgentHostServiceName().isBlank()) {
                return Mono.error(new IllegalStateException("agent返回参数缺少玩家路由"));
            }
            // 先确认可连接到承载该 Actor 的实例，再向客户端报告登录成功。
            return agentLinks.ensureConnected(binding.getAgentHostServiceName(),
                    binding.getAgentHostInstanceId()).thenReturn(new GatewayHandlerResult(
                    ServerEnvelope.newBuilder().setCmd(message.envelope().getCmd())
                            .setRequestId(message.envelope().getRequestId())
                            .setBody(LoginResponse.newBuilder().setUserId(binding.getUserId())
                                    .setPlayer(binding.getPlayer()).build().toByteString()).build(), binding))
                    // 远端已创建 Actor，但内部连接建立失败，客户端无法使用它，必须走同一保存下线流程。
                    .doOnError(failure -> disconnects.notifyDisconnected(binding.getUserId(), binding.getPlayerActorId()));
        });
    }

    /** 只在 Channel 的 EventLoop 修改 GatewayContext。 */
    @Override
    public void onResponse(ChannelHandlerContext ctx, GatewayContext context, GatewayHandlerResult result) {
        if (result.binding() != null) {
            LoginBinding binding = result.binding();
            if(context.getLoginDeadlineMs()<=System.currentTimeMillis()) {
                disconnects.notifyDisconnected(binding.getUserId(),binding.getPlayerActorId());
                channels.endLogin(context);
                throw new IllegalStateException("登录回包已超过等待期限，已请求清理原 Actor");
            }
            context.setUserId(binding.getUserId());
            context.setPlayerActorId(binding.getPlayerActorId());
            context.setAgentHostServiceName(binding.getAgentHostServiceName());
            context.setAgentHostInstanceId(binding.getAgentHostInstanceId());
            channels.bind(binding.getUserId(), context);
        }
        channels.endLogin(context);
        GatewayCommandHandler.super.onResponse(ctx, context, result);
    }

    @Override public void onFailure(GatewayContext context) { channels.endLogin(context); }

    @Override public void onDiscarded(GatewayContext context, GatewayHandlerResult result) {
        channels.endLogin(context);
        if (result != null && result.binding() != null) {
            disconnects.notifyDisconnected(result.binding().getUserId(), result.binding().getPlayerActorId());
        }
        // 登录 HTTP 超时、结果丢失时没有 Actor ID，不能猜测要下线哪个 Actor。
    }

    private ServerEnvelope clientError(DecodedMessage<ClientEnvelope, GameCommands> request, GameError error) {
        return ServerEnvelope.newBuilder().setCmd(request.envelope().getCmd())
                .setRequestId(request.envelope().getRequestId())
                .setCode(error.code()).setMessage(error.message()).build();
    }
}
