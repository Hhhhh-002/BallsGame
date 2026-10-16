package com.h.agentmgrserver.rpc.feign;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.h.agentmgrserver.config.AgentMgrProperties;
import com.h.agentmgrserver.discovery.AgentInstanceSelector;
import com.h.gamecommon.exception.AgentMgrException;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecommon.rpc.BoundedRpcRetry;
import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import com.h.gameprotocol.codec.CommandReceiveLog;
import feign.FeignException;
import feign.Request;
import feign.Retryer;
import org.springframework.cloud.openfeign.FeignClientBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 固定实例的 HTTP RPC：只在此处做一次有限重试，不叠加 Feign/LoadBalancer 隐式重试。 */
@Component
public class InstanceRpcClient {
    private record Endpoint(String url, InternalGameClient client) { }
    private final ConcurrentMap<String, Endpoint> clients = new ConcurrentHashMap<>();
    private final FeignClientBuilder builder;
    private final AgentInstanceSelector discovery;
    private final AgentMgrProperties properties;
    private final AtomicLong requestIds = new AtomicLong();

    public InstanceRpcClient(ApplicationContext context, AgentInstanceSelector discovery, AgentMgrProperties properties) {
        this.builder = new FeignClientBuilder(context);
        this.discovery = discovery;
        this.properties = properties;
    }

    /** body、request_id 及实例 ID 在本次所有尝试中不变；业务幂等依赖 body 中的操作/Actor ID。 */
    public ServiceResponse call(String service, String instanceId, ServiceCommands command, Message body) {
        ServiceRequest request = ServiceRequest.newBuilder().setCmd(command.code())
                .setRequestId(requestIds.incrementAndGet()).setBody(body.toByteString()).build();
        byte[] bytes = ServiceEnvelope.newBuilder().setRequest(request).build().toByteArray();
        try {
            return BoundedRpcRetry.execute(() -> send(service, instanceId, request, bytes), this::retryable,
                    properties.getRpcMaxAttempts(), properties.getInitialBackoffMs(), properties.getMaxBackoffMs());
        } catch (FeignException transport) {
            // 本跳耗尽尝试后仍要给上游正确的临时失败码，不能落入分发器的通用 INTERNAL_ERROR。
            // 同操作请求重试沿用 Redis 中的原实例和 Actor，不生成第二轮分配。
            throw new AgentMgrException(retryable(transport)
                    ? GameError.SERVICE_UNAVAILABLE : GameError.OPERATION_CONFLICT, retryable(transport));
        }
    }

    private ServiceResponse send(String service, String instanceId, ServiceRequest request, byte[] bytes) {
        String url = discovery.url(service, instanceId);
        Endpoint endpoint = clients.compute(service + ":" + instanceId, (key, existing) -> {
            if (existing != null && existing.url().equals(url)) return existing;
            InternalGameClient client = builder.forType(InternalGameClient.class, "agentmgr-direct-" + service)
                    .url(url).customize(feign -> feign.retryer(Retryer.NEVER_RETRY)
                            .options(new Request.Options(properties.getRpcConnectTimeoutMs(), TimeUnit.MILLISECONDS,
                                    properties.getRpcReadTimeoutMs(), TimeUnit.MILLISECONDS, false))).build();
            return new Endpoint(url, client);
        });
        byte[] responseBytes = endpoint.client().dispatch(bytes);
        if (responseBytes == null || responseBytes.length == 0) {
            throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
        }
        try {
            ServiceEnvelope envelope = ServiceEnvelope.parseFrom(responseBytes);
            CommandReceiveLog.service("agentmgr-server","Fixed-instance HTTP",envelope);
            if (!envelope.hasResponse()) throw new IllegalStateException("内部 RPC 缺少响应信封");
            ServiceResponse response = envelope.getResponse();
            if (response.getCmd() != request.getCmd() || response.getRequestId() != request.getRequestId()) {
                throw new IllegalStateException("内部 RPC 关联字段不匹配");
            }
            if (response.getCode() != 0) {
                // 只将明确的临时不可用纳入自动重试；INTERNAL_ERROR/格式错误不盲目重放。
                if (response.getCode() == GameError.SERVICE_UNAVAILABLE.code()) {
                    throw new AgentMgrException(GameError.SERVICE_UNAVAILABLE, true);
                }
                throw new AgentMgrException(GameError.OPERATION_CONFLICT);
            }
            return response;
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalStateException("内部 RPC 响应协议错误", exception);
        }
    }

    private boolean retryable(RuntimeException failure) {
        if (failure instanceof AgentMgrException business) return business.isRetryable();
        if (failure instanceof FeignException http) {
            int status = http.status();
            return status < 0 || status == 408 || status == 429 || status >= 500 && status < 600;
        }
        return false;
    }
}
