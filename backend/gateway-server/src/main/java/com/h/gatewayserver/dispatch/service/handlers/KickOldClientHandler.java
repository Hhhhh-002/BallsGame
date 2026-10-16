package com.h.gatewayserver.dispatch.service.handlers;

import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gamecommon.command.GameCommands;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.proto.KickOldClientRequest;
import com.h.gameprotocol.proto.KickOldClientResult;
import com.h.gameprotocol.proto.ServerEnvelope;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import com.h.gatewayserver.annotions.GatewayServiceCommand;
import com.h.gatewayserver.registries.GatewayChannelRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** AgentMgr 要求旧 Gateway 通知并关闭指定的旧登录连接。 */
@Component
@GatewayServiceCommand(ServiceCommands.KICK_OLD_CLIENT)
public class KickOldClientHandler implements GatewayServiceHandler {
    private final GatewayChannelRegistry channels;
//构造器注入
    public KickOldClientHandler(GatewayChannelRegistry channels) {
        this.channels = channels;
    }

    /** 返回 AgentMgr 的服务间响应；不会把这个响应写到客户端 Channel。 */
    @Override
    public Mono<ServiceResponse> handle(DecodedMessage<ServiceRequest, ServiceCommands> message) {
        // 与客户端 Handler 一样，直接使用解码器按 cmd 解析好的业务参数。
        ServiceRequest request = message.envelope();
        KickOldClientRequest body = (KickOldClientRequest) message.body();
        if (body.getUserId().isBlank() || body.getPlayerActorId().isBlank()) {
            return Mono.error(new IllegalArgumentException("踢旧客户端请求缺少 userId 或 playerActorId"));
        }
        // 客户端只接收一条主动推送；随后该客户端 Channel 会被关闭。
        String reason = body.getReason().isBlank() ? "账号在其他地方登录" : body.getReason();
        ServerEnvelope notice = ServerEnvelope.newBuilder()
                .setCmd(GameCommands.KICKED_OFFLINE.code())
                .setRequestId(0)
                .setMessage(reason)
                .build();
        return channels.kick(body.getUserId(), body.getPlayerActorId(), notice)
                .map(outcome -> ServiceResponse.newBuilder()
                        .setCmd(request.getCmd())
                        .setRequestId(request.getRequestId())
                        .setBody(KickOldClientResult.newBuilder()
                                .setOutcome(toProtocolOutcome(outcome))
                                .build().toByteString())
                        .build());
    }

    /** 网关内部结果映射为服务间 Protobuf 结果，均为最终状态。 */
    private KickOldClientResult.Outcome toProtocolOutcome(GatewayChannelRegistry.KickOutcome outcome) {
        return switch (outcome) {
            case CONNECTION_CLOSED -> KickOldClientResult.Outcome.CONNECTION_CLOSED;
            case ALREADY_OFFLINE -> KickOldClientResult.Outcome.ALREADY_OFFLINE;
            case STALE_IGNORED -> KickOldClientResult.Outcome.STALE_IGNORED;
        };
    }
}
