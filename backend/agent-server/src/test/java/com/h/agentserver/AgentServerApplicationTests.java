package com.h.agentserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Nacos/Redis 启动检查，不创建玩家 Actor，不写去重记录。 */
@EnabledIfSystemProperty(named = "balls.infrastructure-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.cloud.nacos.discovery.register-enabled=false"
})
class AgentServerApplicationTests {
    @Autowired ConfigurableEnvironment environment;
    @Autowired StringRedisTemplate redis;

	@Test
	void contextLoads() {
        assertTrue(environment.getPropertySources().stream().anyMatch(source ->
                source.getName().contains("global-") && source.containsProperty("spring.data.redis.host")));
        try (var connection = redis.getConnectionFactory().getConnection()) { assertEquals("PONG", connection.ping()); }
	}

}
