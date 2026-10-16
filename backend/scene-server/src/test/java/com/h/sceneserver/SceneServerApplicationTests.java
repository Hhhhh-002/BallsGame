package com.h.sceneserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

/** 真实配置启动检查：MySQL/Redis 只读，不生成比赛或修改排名。 */
@EnabledIfSystemProperty(named = "balls.infrastructure-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.cloud.nacos.discovery.register-enabled=false"
})
class SceneServerApplicationTests {
    @Autowired ConfigurableEnvironment environment;
    @Autowired DataSource dataSource;
    @Autowired StringRedisTemplate redis;

	@Test
	void contextLoads() throws Exception {
        assertTrue(environment.getPropertySources().stream().anyMatch(source ->
                source.getName().contains("global-") && source.containsProperty("spring.datasource.url")));
        assertTrue(environment.getPropertySources().stream().anyMatch(source ->
                source.getName().contains("global-") && source.containsProperty("spring.data.redis.host")));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertTrue(connection.getMetaData().getURL().startsWith(environment.getRequiredProperty("spring.datasource.url")));
            try (var rows = statement.executeQuery("SELECT 1")) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            try (var rows = statement.executeQuery("SELECT match_id, data FROM game_match LIMIT 0")) {
                assertEquals(2, rows.getMetaData().getColumnCount());
            }
            try (var rows = statement.executeQuery("SELECT result_id, data FROM game_match_result LIMIT 0")) {
                assertEquals(2, rows.getMetaData().getColumnCount());
            }
        }
        try (var connection = redis.getConnectionFactory().getConnection()) { assertEquals("PONG", connection.ping()); }
	}

}
