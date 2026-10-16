package com.h.sceneserver.config;
import com.h.gamecore.actor.AkkaSupport;
import akka.actor.ActorSystem;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.h.gamecore.netty.*;
import com.h.sceneserver.dispatch.SceneServiceDispatcher;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class SceneConfiguration {
    /** Redis 是阻塞 I/O，独立有界线程池，不占用场景邮箱或网络 EventLoop。 */
    @Bean(destroyMethod="shutdown") java.util.concurrent.ExecutorService rankingExecutor(Environment env) {
        return com.h.gamecommon.Excutor.GameExecutorFactory.newFixedThreadPool("scene-ranking-redis",
                env.getProperty("scene.ranking-threads",Integer.class,2),
                env.getProperty("scene.ranking-queue",Integer.class,128));
    }
    @Bean(destroyMethod = "terminate") ActorSystem sceneActors(Environment env) {
        return AkkaSupport.system("scene-actors",env.getProperty("scene.actor-threads",Integer.class,4));
    }
    @Bean PeerRegistry agentChannels() { return new PeerRegistry(); }
    @Bean(initMethod = "start", destroyMethod = "close") InternalWebSocketServer sceneWebSocket(
            Environment env, PeerRegistry peers, SceneServiceDispatcher dispatcher,NacosDiscoveryProperties discovery) {
        int port=env.getProperty("scene.websocket.port",Integer.class,9201);
        discovery.getMetadata().put("scene-ws-port",Integer.toString(port));
        return new InternalWebSocketServer(port,
                env.getProperty("scene.websocket.path","/internal/scene"), peers, dispatcher::dispatch);
    }
}
