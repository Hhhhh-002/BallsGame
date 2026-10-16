package com.h.sceneserver.config;

import com.alibaba.nacos.api.NacosFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 显式开启才访问真实 Nacos；仅创建/更新/删除自己生成的临时配置，不改 global-prod.yaml，不注册服务。 */
@EnabledIfSystemProperty(named="balls.nacos-refresh-test",matches="true")
class NacosBattleRefreshTests {
    @TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    static class RefreshProbe {}

    @Test void springEnvironmentHotReloadsWithoutRestartAndNewRulesUseLatestValues() throws Exception {
        String address=setting("NACOS_SERVER_ADDR",setting("NACOS_ADDRESS","127.0.0.1:8848"));
        String namespace=setting("NACOS_NAMESPACE","eb7842a8-9b4e-4add-8516-83d7f1d01ee1");
        String username=setting("NACOS_USERNAME","nacos"),password=setting("NACOS_PASSWORD","nacos");
        String dataId="balls-battle-refresh-test-"+UUID.randomUUID()+".yaml";
        Properties properties=new Properties();properties.setProperty("serverAddr",address);properties.setProperty("namespace",namespace);
        properties.setProperty("username",username);properties.setProperty("password",password);
        var publisher=NacosFactory.createConfigService(properties);boolean created=false;
        try {
            assertNull(publisher.getConfig(dataId,"DEFAULT_GROUP",5000));
            assertTrue(publisher.publishConfig(dataId,"DEFAULT_GROUP",yaml(7,20,4,120,80,1000,2),"yaml"));created=true;
            Map<String,String> options=new LinkedHashMap<>();
            options.put("spring.config.name","balls-battle-refresh-probe");
            options.put("spring.application.name","balls-battle-refresh-probe");
            options.put("spring.config.import","nacos:"+dataId+"?group=DEFAULT_GROUP&refreshEnabled=true");
            options.put("spring.cloud.nacos.config.server-addr",address);options.put("spring.cloud.nacos.config.namespace",namespace);
            options.put("spring.cloud.nacos.config.username",username);options.put("spring.cloud.nacos.config.password",password);
            options.put("spring.cloud.nacos.discovery.enabled","false");options.put("spring.cloud.nacos.discovery.register-enabled","false");
            options.put("spring.cloud.service-registry.auto-registration.enabled","false");
            options.put("spring.main.banner-mode","off");options.put("logging.level.root","WARN");
            String[] arguments=options.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new);
            try(var context=new SpringApplicationBuilder(RefreshProbe.class).web(WebApplicationType.NONE).run(arguments)) {
                var environment=context.getEnvironment();var first=BattleRules.load(environment);
                assertEquals(7,first.initialFoods());assertEquals(120f,first.width());assertEquals(2,first.foodRefreshMaxCount());
                assertTrue(publisher.publishConfig(dataId,"DEFAULT_GROUP",yaml(9,30,8,240,160,2000,6),"yaml"));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(!Objects.equals(6,environment.getProperty("battle.food-refresh-max-count",Integer.class))) {
                    assertTrue(System.nanoTime()<deadline,"Nacos 发布后 Spring Environment 未在30秒内刷新");Thread.sleep(100);
                }
                var second=BattleRules.load(environment);
                assertTrue(context.isActive());assertEquals(9,second.initialFoods());assertEquals(30,second.maxFoods());
                assertEquals(8,second.maxPlayers());assertEquals(240f,second.width());assertEquals(160f,second.height());
                assertEquals(50,second.foodIntervalTicks());assertEquals(6,second.foodRefreshMaxCount());
                assertEquals(7,first.initialFoods());assertEquals(120f,first.width());assertEquals(2,first.foodRefreshMaxCount());
            }
        } finally {
            try { if(created)assertTrue(publisher.removeConfig(dataId,"DEFAULT_GROUP"),"临时测试配置清理失败："+dataId); }
            finally { publisher.shutDown(); }
        }
    }
    private String setting(String key,String fallback) {String value=System.getenv(key);return value==null||value.isBlank()?fallback:value;}
    private String yaml(int initial,int max,int players,int width,int height,int interval,int batch) {
        return "battle:\n  initial-foods: "+initial+"\n  max-foods: "+max+"\n  max-players: "+players
                +"\n  width: "+width+"\n  height: "+height+"\n  food-interval-ms: "+interval+"\n  food-refresh-max-count: "+batch+"\n";
    }
}
