package com.h.agentserver.config;
import com.h.agentserver.dispatch.AgentServiceDispatcher;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.h.gamecommon.services.ServiceNames;
import com.h.gamecommon.Excutor.GameExecutorFactory;
import com.h.gamecore.actor.AkkaSupport;
import akka.actor.ActorSystem;
import com.h.gamecore.netty.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.concurrent.ExecutorService;

@Configuration
public class AgentConfiguration {
    @Bean(destroyMethod="terminate") ActorSystem playerActors(Environment env) {
        return AkkaSupport.system("player-actors",env.getProperty("agent.actor-threads",Integer.class,4));
    }
    @Bean(destroyMethod="shutdown") ExecutorService playerRequestIo(Environment env) {
        return GameExecutorFactory.newFixedThreadPool("agent-request-redis",
                env.getProperty("agent.io-threads",Integer.class,8),env.getProperty("agent.io-queue",Integer.class,512));
    }
    @Bean PeerRegistry gatewayChannels(){return new PeerRegistry();}
    @Bean(initMethod="start",destroyMethod="close") InternalWebSocketServer agentWebSocket(
            Environment env,PeerRegistry peers,AgentServiceDispatcher dispatcher,NacosDiscoveryProperties discovery) {
        int port=env.getProperty("agent.websocket.port",Integer.class,9101);
        // Map 的键不依赖 YAML 占位符替换；注册前按实际实例 ID/监听端口构造 Gateway 要读取的键。
        discovery.getMetadata().put(ServiceNames.getAgentWsPortMetadataKey(
                env.getRequiredProperty("spring.cloud.nacos.discovery.metadata.instanceid")),Integer.toString(port));
        return new InternalWebSocketServer(port,
                env.getProperty("agent.websocket.path","/internal/agent"),peers,dispatcher::websocket);
    }
}
