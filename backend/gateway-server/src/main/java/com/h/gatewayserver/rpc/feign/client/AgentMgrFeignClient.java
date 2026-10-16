package com.h.gatewayserver.rpc.feign.client;

import com.h.gamecommon.services.ServiceNames;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** AgentMgr 是登录状态的权威服务；断线请求不直接发送给 Agent。 */
@FeignClient(name = ServiceNames.AGENT_MANAGER)
public interface AgentMgrFeignClient {
    /** 发送服务间 Protobuf 请求，并等待 AgentMgr 返回最终处理结果。 */
    @PostMapping(value = "/internal/game/dispatch",
            consumes = "application/x-protobuf", produces = "application/x-protobuf")
    byte[] dispatch(@RequestBody byte[] request);
}
