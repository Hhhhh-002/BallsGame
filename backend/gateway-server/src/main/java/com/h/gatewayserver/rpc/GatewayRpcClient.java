package com.h.gatewayserver.rpc;

import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gameprotocol.proto.ServiceResponse;
import reactor.core.publisher.Mono;

/** Gateway 到无状态 Login 的一次性请求；玩家指令不经过此 HTTP 客户端。 */
public interface GatewayRpcClient {
    /** Login 按服务名负载均衡；响应应为 ServiceEnvelope.response。 */
    Mono<ServiceResponse> login(ServiceEnvelope request);
    /** 注册复用到 Login 的 Feign 入口，不绑定玩家路由。 */
    Mono<ServiceResponse> register(ServiceEnvelope request);
}
