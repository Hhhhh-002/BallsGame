package com.h.agentserver.rpc;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.google.protobuf.Message;
import com.h.gamecommon.Excutor.GameExecutorFactory;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecommon.services.ServiceNames;
import com.h.gamecore.netty.SharedWebSocketClient;
import com.h.gamecore.protocol.*;
import com.h.gameprotocol.proto.*;
import com.h.gameprotocol.codec.CommandReceiveLog;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import static com.h.gamecore.protocol.Messages.require;

/** Agent 到多个 Scene 实例各建立一条共享长连接；业务路由一旦确定就不再负载均衡。 */
@Component
public final class SceneLinkManager {
    private final NacosServiceManager nacos;
    private final Environment env;
    private final SharedWebSocketClient client;
    private final ExecutorService discovery = GameExecutorFactory.newFixedThreadPool("agent-scene-discovery",2,128);
    private volatile BiConsumer<String,SceneToAgentEvent> receiver=(id,event)->{};
    public SceneLinkManager(NacosServiceManager nacos, Environment env) {
        this.nacos=nacos; this.env=env;
        client=new SharedWebSocketClient(ServiceNames.AGENT,discovery,this::resolve,ServiceCommands.AGENT_SCENE_LINK_HELLO,
                AgentSceneLinkHello.newBuilder().setAgentHostInstanceId(env.getRequiredProperty("spring.cloud.nacos.discovery.metadata.instanceid")).build(),
                env.getProperty("agent.scene-rpc-timeout-ms",Long.class,5000L), (id,event)-> {
                    if (event.getCmd()!=ServiceCommands.SCENE_TO_AGENT.code()) return;
                    try {
                        SceneToAgentEvent message=SceneToAgentEvent.parseFrom(event.getBody());
                        CommandReceiveLog.nested(ServiceNames.AGENT,"Scene WS",message);
                        receiver.accept(id,message);
                    }
                    catch (Exception failure) { throw new IllegalArgumentException("Scene 事件格式错误",failure); }
                });
    }
    public void receiver(BiConsumer<String,SceneToAgentEvent> receiver) { this.receiver=receiver; }
    public long inputResponseTimeoutMs() {
        long timeout=env.getProperty("agent.scene-input-response-timeout-ms",Long.class,15000L);
        require(timeout>0,GameError.INVALID_MESSAGE);return timeout;
    }
    public CompletableFuture<String> select() {
        return CompletableFuture.supplyAsync(() -> {
            List<Instance> candidates=instances().stream().filter(i->i.getWeight()>0 && Double.isFinite(i.getWeight())
                    && !id(i).isBlank()).toList();
            require(!candidates.isEmpty(),GameError.SERVICE_UNAVAILABLE);
            String host=env.getProperty("spring.cloud.nacos.discovery.metadata.host-id","");
            double multiplier=env.getProperty("agent.same-host-multiplier",Double.class,3d);
            require(Double.isFinite(multiplier) && multiplier>=1,GameError.INVALID_MESSAGE);
            double total=candidates.stream().mapToDouble(i->weight(i,host,multiplier)).sum();
            require(Double.isFinite(total) && total>0,GameError.SERVICE_UNAVAILABLE);
            double point=ThreadLocalRandom.current().nextDouble(total);
            for (Instance i:candidates) { point-=weight(i,host,multiplier); if(point<0)return id(i); }
            return id(candidates.get(candidates.size()-1));
        },discovery);
    }
    private double weight(Instance i,String host,double multiplier) {
        return i.getWeight()*(!host.isBlank() && host.equals(i.getMetadata().get(ServiceNames.HOST_ID_METADATA))?multiplier:1);
    }
    private List<Instance> instances() {
        try {
            return nacos.getNamingService().getAllInstances(ServiceNames.SCENE,
                    env.getProperty("spring.cloud.nacos.discovery.group","DEFAULT_GROUP")).stream()
                    .filter(i->i.isHealthy() && i.isEnabled()).toList();
        } catch (Exception failure) { throw new BattleException(GameError.SERVICE_UNAVAILABLE); }
    }
    private URI resolve(String instanceId) {
        List<Instance> matches=instances().stream().filter(i->instanceId.equals(id(i))).toList();
        require(matches.size()==1,GameError.SERVICE_UNAVAILABLE);
        Instance i=matches.get(0);
        int port=Integer.parseInt(i.getMetadata().getOrDefault("scene-ws-port","0"));
        require(port>0 && port<=65535,GameError.SERVICE_UNAVAILABLE);
        try { return new URI("ws",null,i.getIp(),port,env.getProperty("scene.websocket.path","/internal/scene"),null,null); }
        catch (Exception failure) { throw new IllegalArgumentException("Scene WS 地址错误",failure); }
    }
    private String id(Instance instance) { return instance.getMetadata().getOrDefault(ServiceNames.INSTANCE_ID_METADATA,""); }
    public CompletableFuture<ServiceResponse> request(String instance,ServiceCommands cmd,Message body) {
        return client.request(instance,cmd,body).thenApply(response-> {
            if(response.getCode()!=0) {
                GameError error=Arrays.stream(GameError.values()).filter(e->e.code()==response.getCode()).findFirst().orElse(GameError.INTERNAL_ERROR);
                throw new BattleException(error);
            }
            return response;
        });
    }
    public CompletableFuture<Void> send(String instance,ServiceEnvelope message) { return client.send(instance,message); }
    @PreDestroy public void close() { client.close(); }
}
