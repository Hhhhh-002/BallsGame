package com.h.agentmgrserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.redisson.api.RedissonClient;
import static org.junit.jupiter.api.Assertions.*;

/** 真实配置启动检查，只验证连接，不加锁、不写在线或请求记录。 */
@EnabledIfSystemProperty(named = "balls.infrastructure-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.cloud.nacos.discovery.register-enabled=false"
})
class AgentmgrServerApplicationTests {
    @Autowired ConfigurableEnvironment environment;
    @Autowired StringRedisTemplate redis;
    @Autowired RedissonClient redisson;

	@Test
	void contextLoads() {
        assertTrue(environment.getPropertySources().stream().anyMatch(source ->
                source.getName().contains("global-") && source.containsProperty("spring.data.redis.host")));
        assertTrue(redis.getConnectionFactory() instanceof org.redisson.spring.data.connection.RedissonConnectionFactory,
                "StringRedisTemplate 应复用 Redisson 连接");
        assertFalse(redisson.isShutdown());
        try (var connection = redis.getConnectionFactory().getConnection()) { assertEquals("PONG", connection.ping()); }
	}

}
