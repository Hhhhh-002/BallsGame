package com.h.gatewayserver.dispatch.client.handlers;

import com.h.gamecommon.command.GameCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.ClientEnvelope;
import com.h.gatewayserver.context.GatewayContext;
import com.h.gatewayserver.dispatch.client.result.GatewayHandlerResult;
import io.netty.channel.ChannelHandlerContext;
import reactor.core.publisher.Mono;

/** handle 在 Channel EventLoop 中调用；RPC 完成后 onResponse 也回到该 EventLoop。 */
public interface GatewayCommandHandler {
    /** 校验连接状态并发起异步调用；不可阻塞 Netty EventLoop。 */
    Mono<GatewayHandlerResult> handle(GatewayContext gatewayContext,
                                     DecodedMessage<ClientEnvelope, GameCommands> message);

    /** 已切回原 Channel EventLoop，可安全更新连接状态并给客户端回包。 */
    default void onResponse(ChannelHandlerContext ctx, GatewayContext gatewayContext,
                            GatewayHandlerResult response) {
        ctx.writeAndFlush(response.clientResponse().toByteArray());
    }

    /** RPC 失败时回滚本 Handler 保存的临时连接状态。 */
    default void onFailure(GatewayContext gatewayContext) {
    }
    /** RPC 已返回，但原客户端已断开；供登录 Handler 清理迟到的成功绑定。 */
    default void onDiscarded(GatewayContext context, GatewayHandlerResult response) {
        onFailure(context);
    }
}
