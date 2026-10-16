package com.h.agentserver.rpc;
import com.h.agentserver.dispatch.AgentServiceDispatcher;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;

/** AgentMgr 的 Feign 接收端；返回数据保存、Actor 创建/停止后的最终结果。 */
@RestController
public class AgentInternalController {
    private final AgentServiceDispatcher dispatcher;
    public AgentInternalController(AgentServiceDispatcher dispatcher){this.dispatcher=dispatcher;}
    @PostMapping(value="/internal/game/dispatch",consumes="application/x-protobuf",produces="application/x-protobuf")
    public CompletableFuture<byte[]> dispatch(@RequestBody byte[] request){return dispatcher.http(request);}
}
