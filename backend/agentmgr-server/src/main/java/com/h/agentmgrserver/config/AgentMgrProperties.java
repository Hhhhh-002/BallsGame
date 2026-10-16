package com.h.agentmgrserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可在 AgentMgr 专属 Nacos 配置中覆盖；不混用在线记录 TTL 和操作逻辑超时。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "agentmgr")
public class AgentMgrProperties {
    /** Redis 连接统一配置在 spring.data.redis；这里只保留业务 key 前缀。 */
    private String redisKeyPrefix = "balls:agentmgr:";
    /** 锁等待有限，不指定固定租期；由 Redisson watchdog 维护锁生命周期。 */
    private long lockWaitMs = 200;
    /** 一次操作的逻辑期限，超过后不删除在线占位或 PROCESSING 记录。 */
    private long operationTimeoutMs = 90_000;
    /** 一个 HTTP 调用最多等待最终结果多久；超时不取消正在进行的共享任务。 */
    private long resultWaitMs = 80_000;
    private int workerThreads = 16;
    private int workerQueue = 512;
    /** 单次 RPC 超时；总尝试次数包含第一次发送。 */
    private int rpcConnectTimeoutMs = 1000;
    private int rpcReadTimeoutMs = 8000;
    private int rpcMaxAttempts = 3;
    private long initialBackoffMs = 200;
    private long maxBackoffMs = 2000;
    /** 只影响新建 Actor 的选址；正常转发和重试不会重新负载均衡。 */
    private double sameHostMultiplier = 3.0;
    /** 已完成请求结果的缓存窗口；PROCESSING 不自动过期，不做重启恢复。 */
    private long idempotencyResultTtlMs = 600_000;
}
