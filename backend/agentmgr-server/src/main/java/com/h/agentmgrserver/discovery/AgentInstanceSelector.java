package com.h.agentmgrserver.discovery;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.h.agentmgrserver.config.AgentMgrProperties;
import com.h.gamecommon.exception.AgentMgrException;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.services.ServiceNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Nacos 只提供实例；AgentMgr 在新建操作开始时做一次同机加权选择。 */
@Component
public class AgentInstanceSelector {
    private final NacosServiceManager nacos;
    private final AgentMgrProperties properties;
    private final String group;

    public AgentInstanceSelector(NacosServiceManager nacos, AgentMgrProperties properties,
                                 @Value("${spring.cloud.nacos.discovery.group:DEFAULT_GROUP}") String group) {
        this.nacos = nacos;
        this.properties = properties;
        this.group = group;
    }

    /** 精确匹配应用声明的 metadata.instanceid；不能把端口或服务名当作实例 ID。 */
    public Instance find(String serviceName, String instanceId) {
        List<Instance> matches = instances(serviceName).stream().filter(i -> instanceId.equals(id(i))).toList();
        if (matches.size() > 1) throw new AgentMgrException(GameError.OPERATION_CONFLICT);
        if (matches.isEmpty()) throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        return matches.get(0);
    }

    /** 同机只增加概率；host-id 缺失时不根据 127.0.0.1 猜测物理机器。 */
    public Instance select(String gatewayInstanceId) {
        Instance gateway = find(ServiceNames.GATEWAY, gatewayInstanceId);
        String host = gateway.getMetadata().getOrDefault(ServiceNames.HOST_ID_METADATA, "");
        List<Instance> candidates = instances(ServiceNames.AGENT).stream()
                .filter(i -> i.getWeight() > 0 && Double.isFinite(i.getWeight()) && !id(i).isBlank()).toList();
        if (candidates.isEmpty()) throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        double total = candidates.stream().mapToDouble(i -> weight(i, host)).sum();
        if (!Double.isFinite(total) || total <= 0) throw new IllegalStateException("Agent 权重配置不合法");
        double point = ThreadLocalRandom.current().nextDouble(total);
        for (Instance candidate : candidates) {
            point -= weight(candidate, host);
            if (point < 0) return candidate;
        }
        return candidates.get(candidates.size() - 1);
    }

    private double weight(Instance instance, String host) {
        boolean sameHost = !host.isBlank() && host.equals(instance.getMetadata().get(ServiceNames.HOST_ID_METADATA));
        return instance.getWeight() * (sameHost ? properties.getSameHostMultiplier() : 1.0);
    }

    /** RPC 地址取 HTTP 注册端口，不取内部 WebSocket 元数据端口。 */
    public String url(String serviceName, String instanceId) {
        Instance instance = find(serviceName, instanceId);
        try {
            return new URI("http", null, instance.getIp(), instance.getPort(), null, null, null).toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Nacos 实例 HTTP 地址不合法", exception);
        }
    }

    public String id(Instance instance) { return instance.getMetadata().getOrDefault(ServiceNames.INSTANCE_ID_METADATA, ""); }

    private List<Instance> instances(String serviceName) {
        try {
            return nacos.getNamingService().getAllInstances(serviceName, group).stream()
                    .filter(i -> i.isHealthy() && i.isEnabled()).toList();
        } catch (NacosException exception) {
            throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        }
    }
}
