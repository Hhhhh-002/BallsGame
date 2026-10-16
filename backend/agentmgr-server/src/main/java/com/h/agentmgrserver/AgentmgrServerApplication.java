package com.h.agentmgrserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import com.h.agentmgrserver.config.AgentMgrProperties;

/** 在线集合在内存，Redis 保存原请求及结果；同操作重试核实原目标，不定时重放阶段日志。 */
@SpringBootApplication
@EnableFeignClients
@EnableConfigurationProperties(AgentMgrProperties.class)
public class AgentmgrServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentmgrServerApplication.class, args);
	}

}
