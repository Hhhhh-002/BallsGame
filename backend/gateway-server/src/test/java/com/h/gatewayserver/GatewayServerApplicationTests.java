package com.h.gatewayserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Nacos 与 HTTP/Netty 启动检查，不发登录或战斗请求。 */
@EnabledIfSystemProperty(named = "balls.infrastructure-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.cloud.nacos.discovery.register-enabled=false"
})
class GatewayServerApplicationTests {
    @Autowired ConfigurableEnvironment environment;

	@Test
	void contextLoads() {
        assertTrue(environment.getPropertySources().stream().anyMatch(source ->source.getName().contains("global-")),
                "Gateway 必须加载 Nacos 全局配置");
	}

}
