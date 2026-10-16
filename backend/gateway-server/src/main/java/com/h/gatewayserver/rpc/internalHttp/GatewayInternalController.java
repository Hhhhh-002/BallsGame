package com.h.gatewayserver.rpc.internalHttp;

import com.h.gatewayserver.dispatch.service.GatewayServiceDispatcher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** AgentMgr 等内部服务调用的 HTTP 入口，不是玩家的 WebSocket 消息入口。 */
@RestController
public class GatewayInternalController {
    private final GatewayServiceDispatcher dispatcher;

    public GatewayInternalController(GatewayServiceDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 请求与最终响应都使用 ServiceEnvelope 的 Protobuf 字节。 */
    @PostMapping(value = "/internal/game/dispatch",
            consumes = "application/x-protobuf", produces = "application/x-protobuf")
    public Mono<byte[]> dispatch(@RequestBody byte[] request) {
        return dispatcher.dispatch(request);
    }
}
