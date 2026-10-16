package com.h.loginserver.rpc.feign.config;

import feign.Retryer;
import org.springframework.context.annotation.Bean;
/** 仅供 AgentMgrFeignClient 的子容器加载，不声明为全局 @Configuration。 */
public class AgentMgrFeignConfiguration {
    /** 重试由 AgentMgrLoginRpcClient 按业务幂等标识统一控制，不再叠加 Feign 自身重试。 */
    @Bean
    public Retryer agentMgrRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
