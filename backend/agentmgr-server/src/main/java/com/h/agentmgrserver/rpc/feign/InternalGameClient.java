package com.h.agentmgrserver.rpc.feign;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 按明确 URL 创建，不声明普通 @FeignClient，防止玩家生命周期请求被再次负载均衡。 */
public interface InternalGameClient {
    @PostMapping(value = "/internal/game/dispatch", consumes = "application/x-protobuf", produces = "application/x-protobuf")
    byte[] dispatch(@RequestBody byte[] request);
}
