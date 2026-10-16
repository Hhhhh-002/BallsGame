package com.h.gatewayserver.rpc.feign.client;

import com.h.gamecommon.services.ServiceNames;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import feign.Request;

/** Login 不持有玩家 Actor，可由 Spring Cloud LoadBalancer 选择任意健康实例。 */

@FeignClient(name = ServiceNames.LOGIN)
public interface LoginFeignClient {
    /** 内部 RPC 仍传输共享的 Protobuf 二进制信封。 */
    @PostMapping(value = "/internal/game/dispatch",
            consumes = "application/x-protobuf", produces = "application/x-protobuf")
    /** Options 不进入 Protobuf body，用于防止重试叠加超出原登录预算。 */
    byte[] dispatch(@RequestBody byte[] request, Request.Options options);
}
