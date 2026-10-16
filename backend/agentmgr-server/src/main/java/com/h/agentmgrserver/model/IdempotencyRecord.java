package com.h.agentmgrserver.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 一次业务请求的去重记录：只保留 PROCESSING/DONE、固定目标和最终结果，不记录逐步执行日志。
 * 整个对象以 JSON String 单独存 Redis；Future、线程和回调不参与序列化。
 */
@Getter
@Setter
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
public class IdempotencyRecord {
    /** 登录和断线使用不同的 key 空间，避免标识碰撞。 */
    public enum Type { LOGIN, DISCONNECT }
    public enum Status { PROCESSING, DONE }

    private String id;
    private String userId;
    private Type type;
    private Status status = Status.PROCESSING;
    /** 新登录的固定目标；普通超时重试使用原标识，不重新随机分配。 */
    private String gatewayInstanceId = "";
    private String agentHostInstanceId = "";
    private String playerActorId = "";
    /** 顶号或断线时要清理的旧目标，不是每个阶段的历史快照。 */
    private String oldGatewayInstanceId = "";
    private String oldAgentHostInstanceId = "";
    private String oldPlayerActorId = "";
    /** 只约束等待或诊断，不能因超时清空占位、放行第二个 Actor。 */
    private long deadline;
    /** 只缓存业务 body；每次 HTTP 回包由 Handler 使用当前 cmd/request_id 包装。 */
    private String resultBody = "";
}
