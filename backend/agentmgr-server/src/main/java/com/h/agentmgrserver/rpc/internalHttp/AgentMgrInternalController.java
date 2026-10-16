package com.h.agentmgrserver.rpc.internalHttp;

import com.h.agentmgrserver.dispatch.AgentMgrDispatcher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 接收 Login/Gateway 的 Feign RPC；返回同一次 HTTP 调用的最终 Protobuf 响应。 */
@RestController
public class AgentMgrInternalController {
    private final AgentMgrDispatcher dispatcher;
    public AgentMgrInternalController(AgentMgrDispatcher dispatcher) { this.dispatcher = dispatcher; }

    @PostMapping(value = "/internal/game/dispatch", consumes = "application/x-protobuf", produces = "application/x-protobuf")
    public byte[] dispatch(@RequestBody byte[] request) { return dispatcher.dispatch(request); }
}
