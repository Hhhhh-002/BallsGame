package com.h.loginserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 Nacos/MySQL 启动检查，只读，不建表、不插入账号。 */
@EnabledIfSystemProperty(named = "balls.infrastructure-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.cloud.nacos.discovery.register-enabled=false"
})
class LoginServerApplicationTests {
    @Autowired ConfigurableEnvironment environment;
    @Autowired DataSource dataSource;

    @Test
    void contextLoads() throws Exception {
        assertTrue(environment.getPropertySources().stream().anyMatch(source ->
                source.getName().contains("global-") && source.containsProperty("spring.datasource.url")),
                "MySQL 连接必须从 Nacos 全局配置加载");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertTrue(connection.getMetaData().getURL().startsWith(environment.getRequiredProperty("spring.datasource.url")));
            try (var rows = statement.executeQuery("SELECT 1")) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            // 不读取账号数据，只检查 Mapper 需要的两列是否存在。
            try (var rows = statement.executeQuery("SELECT user_id, data FROM game_account LIMIT 0")) {
                assertEquals(2, rows.getMetaData().getColumnCount());
            }
        }
    }

}
