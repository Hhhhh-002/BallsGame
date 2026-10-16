package com.h.loginserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/** 无状态登录服务：校验凭据，并协调 AgentMgr 返回最终玩家路由。 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.h.loginserver.rpc.feign.client")
@SpringBootApplication
public class LoginServerApplication {

    /** 启动 HTTP 服务，同时向 Nacos 注册 login-server 实例。 */
    public static void main(String[] args) {
        SpringApplication.run(LoginServerApplication.class, args);
    }

}
