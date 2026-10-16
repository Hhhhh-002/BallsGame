package com.h.gatewayserver.dispatch.service.handlers;

import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import reactor.core.publisher.Mono;

/** 处理发往 Gateway 的服务间请求；返回的是调用方服务的响应，不是客户端回包。 */
public interface GatewayServiceHandler {
    /** 业务 body 已由解码器解析；响应沿用外层请求的 cmd/request_id，不阻塞 HTTP EventLoop。 */
    Mono<ServiceResponse> handle(DecodedMessage<ServiceRequest, ServiceCommands> message);
}
