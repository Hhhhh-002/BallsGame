package com.h.gatewayserver.dispatch.client.result;

import com.h.gameprotocol.proto.LoginBinding;
import com.h.gameprotocol.proto.ServerEnvelope;

/** Handler 的一次客户端响应；登录成功时额外携带仅供 Gateway 保存的内部路由。 */
public record GatewayHandlerResult(ServerEnvelope clientResponse, LoginBinding binding) {
    public GatewayHandlerResult(ServerEnvelope clientResponse) {
        this(clientResponse, null);
    }
}
