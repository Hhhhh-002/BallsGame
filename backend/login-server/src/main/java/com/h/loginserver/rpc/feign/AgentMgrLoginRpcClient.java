package com.h.loginserver.rpc.feign;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecommon.rpc.BoundedRpcRetry;
import com.h.gameprotocol.proto.BeginLoginRequest;
import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import com.h.gameprotocol.codec.CommandReceiveLog;
import com.h.gamecommon.exception.LoginServiceException;
import com.h.loginserver.rpc.feign.client.AgentMgrFeignClient;
import feign.FeignException;
import feign.Request;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/** 封装 BEGIN_LOGIN 信封，并校验 AgentMgr 返回的 cmd/request_id。 */
@Component
public class AgentMgrLoginRpcClient {
    private static final Logger log = LoggerFactory.getLogger(AgentMgrLoginRpcClient.class);
    private final AgentMgrFeignClient client;
    private final int maxAttempts;
    private final long initialBackoff;
    private final long maxBackoff;
    /** 同时兼容 YAML 的连字符写法与现有 properties 的驼峰写法。 */
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    /** 本实例服务调用关联编号，不是用户 ID、Actor ID 或业务幂等键。 */
    private final AtomicLong requestIds = new AtomicLong();

    /** Feign 由 Spring 创建，禁止每次请求手动构造客户端。 */
    public AgentMgrLoginRpcClient(AgentMgrFeignClient client,
                                  @Value("${login.rpc.max-attempts:3}") int maxAttempts,
                                  @Value("${login.rpc.initial-backoff-ms:200}") long initialBackoff,
                                  @Value("${login.rpc.max-backoff-ms:2000}") long maxBackoff,
                                  @Value("${spring.cloud.openfeign.client.config.agentmgr-server.connect-timeout:${spring.cloud.openfeign.client.config.agentmgr-server.connectTimeout:1000}}") int connectTimeoutMs,
                                  @Value("${spring.cloud.openfeign.client.config.agentmgr-server.read-timeout:${spring.cloud.openfeign.client.config.agentmgr-server.readTimeout:65000}}") int readTimeoutMs) {
        if (maxAttempts < 1 || initialBackoff < 1 || maxBackoff < initialBackoff
                || connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Login -> AgentMgr RPC 参数不合法");
        }
        this.client = client;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * 账号已通过校验后调用；Login 不选择 Agent 实例、不持有用户在线状态。
     * 此方法运行在 MVC 工作线程，不需要再套一个线程池或调用 Mono.block()。
     */
    public ServiceResponse beginLogin(BeginLoginRequest body) {
        ServiceRequest request = ServiceRequest.newBuilder()
                .setCmd(ServiceCommands.BEGIN_LOGIN.code())
                .setRequestId(requestIds.incrementAndGet()).setBody(body.toByteString()).build();
        ServiceEnvelope outbound = ServiceEnvelope.newBuilder().setRequest(request).build();
        // 所有尝试复用同一 request 与 login_operation_id，不重跑一次新的登录仲裁。
        return BoundedRpcRetry.execute(() -> send(body, request, outbound.toByteArray()),
                failure -> failure instanceof LoginServiceException business
                        && business.getError() == GameError.SERVICE_UNAVAILABLE,
                maxAttempts, initialBackoff, maxBackoff);
    }

    /** 单次尝试根据端到端剩余期限设置底层 HTTP 超时；不延长原登录操作的总预算。 */
    private ServiceResponse send(BeginLoginRequest body, ServiceRequest request, byte[] outbound) {
        long remaining = body.getLoginDeadlineMs() - System.currentTimeMillis();
        if (remaining <= 0) throw new LoginServiceException(GameError.SERVICE_UNAVAILABLE);
        final byte[] responseBytes;
        try {
            responseBytes = client.dispatch(outbound, new Request.Options(Math.min(connectTimeoutMs, remaining), TimeUnit.MILLISECONDS,
                    Math.min(readTimeoutMs, remaining), TimeUnit.MILLISECONDS, false));
        } catch (FeignException exception) {
            // 幂等操作允许临时故障有限重试；参数/鉴权等 HTTP 4xx 不重复发送。
            log.warn("AgentMgr 登录 RPC 失败或结果未确认, userId={}, requestId={}, httpStatus={}",
                    body.getUserId(), request.getRequestId(), exception.status());
            int status = exception.status();
            throw new LoginServiceException(status < 0 || status == 408 || status == 429
                    || status >= 500 && status < 600 ? GameError.SERVICE_UNAVAILABLE : GameError.INTERNAL_ERROR);
        }
        if (responseBytes == null || responseBytes.length == 0) {
            throw new LoginServiceException(GameError.SERVICE_UNAVAILABLE);
        }
        final ServiceEnvelope envelope;
        try {
            envelope = ServiceEnvelope.parseFrom(responseBytes);
            CommandReceiveLog.service("login-server","AgentMgr HTTP",envelope);
        } catch (InvalidProtocolBufferException exception) {
            throw new IllegalStateException("AgentMgr 响应不是有效的 ServiceEnvelope", exception);
        }
        if (!envelope.hasResponse()) {
            throw new IllegalStateException("AgentMgr 未返回 ServiceResponse");
        }
        ServiceResponse response = envelope.getResponse();
        if (response.getCmd() != request.getCmd() || response.getRequestId() != request.getRequestId()) {
            throw new IllegalStateException("AgentMgr 响应 cmd/request_id 与请求不匹配");
        }
        if (response.getCode() == GameError.SERVICE_UNAVAILABLE.code()) {
            throw new LoginServiceException(GameError.SERVICE_UNAVAILABLE);
        }
        return response;
    }
}
