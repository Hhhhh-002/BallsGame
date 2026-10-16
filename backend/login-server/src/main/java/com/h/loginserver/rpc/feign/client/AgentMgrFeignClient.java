package com.h.loginserver.rpc.feign.client;

import com.h.gamecommon.services.ServiceNames;
import com.h.loginserver.rpc.feign.config.AgentMgrFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import feign.Request;

/** Login -> 单例 AgentMgr 的 HTTP RPC；实例位置通过 Nacos 服务发现获取。 */
@FeignClient(name = ServiceNames.AGENT_MANAGER, configuration = AgentMgrFeignConfiguration.class)
public interface AgentMgrFeignClient {
    /** 调用方等待 AgentMgr 的最终响应；不接收“已受理、正在处理”作为成功结果。 */
    @PostMapping(value = "/internal/game/dispatch",
            consumes = "application/x-protobuf", produces = "application/x-protobuf")
    // Options 是 Feign 的特殊参数，不进入请求 body；每次尝试按剩余总预算限制等待。
    byte[] dispatch(@RequestBody byte[] request, Request.Options options);
}
