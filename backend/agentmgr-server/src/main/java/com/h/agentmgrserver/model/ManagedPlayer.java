package com.h.agentmgrserver.model;

import com.h.gamecommon.code.LoginStatus;

/**
 * 当前在线玩家记录，以用户 ID 为索引；不存 node、IP、Channel 或玩家业务数据。
 * pendingOperationId 标识正在执行的流程；loginOperationId 标识当前 Actor 所属的登录。
 */
public record ManagedPlayer(String userId, LoginStatus status, String gatewayInstanceId,
                            String agentHostInstanceId, String playerActorId, String loginOperationId,
                            String pendingOperationId, long updatedAt, long operationDeadline) { }
