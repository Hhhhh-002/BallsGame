package com.h.loginserver.dispatch;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecommon.exception.InvalidCommandBodyException;
import com.h.gamecommon.exception.UnknownCommandException;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.codec.CommandReceiveLog;
import com.h.gameprotocol.codec.decode.ServiceMessageDecoder;
import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import com.h.loginserver.dispatch.handlers.LoginServiceHandler;
import com.h.gamecommon.exception.LoginServiceException;
import com.h.loginserver.registries.LoginServiceCommandRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 解码服务信封、按 cmd 查 Handler，并把最终结果包装回 ServiceEnvelope。
 * 协议内业务异常在这里统一转换，保留 cmd/request_id；不重复交给 ControllerAdvice。
 */
@Component
public class LoginServiceDispatcher {
    private static final Logger log = LoggerFactory.getLogger(LoginServiceDispatcher.class);
    private static final int MAX_REQUEST_BYTES = 65_536;
    private final ServiceMessageDecoder decoder = new ServiceMessageDecoder();
    private final LoginServiceCommandRegistry registry;

    /** Registry 的 Handler 均已由 Spring 创建，不在每次请求时重新实例化。 */
    public LoginServiceDispatcher(LoginServiceCommandRegistry registry) {
        this.registry = registry;
    }

    /** MVC 工作线程同步协调登录；Feign 超时由底层 HTTP 配置限制。 */
    public byte[] dispatch(byte[] bytes) {
//        // 1. 基础字节校验：空包、超长包直接拒绝
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES) {
            return encode(error(0, 0, GameError.INVALID_MESSAGE));
        }
        final ServiceEnvelope envelope;
        try {
            envelope = ServiceEnvelope.parseFrom(bytes);
            CommandReceiveLog.service("login-server","Gateway HTTP",envelope);
        } catch (InvalidProtocolBufferException exception) {
            CommandReceiveLog.unreadable("login-server","Gateway HTTP");
//            如果二进制损坏、不符合 protobuf 格式，捕获异常，返回`INVALID_MESSAGE`。
            return encode(error(0, 0, GameError.INVALID_MESSAGE));
        }
//        业务信封校验：`ServiceEnvelope`必须包含 Request 请求体，没有请求体视为非法消息。取出内部`ServiceRequest`。
        if (!envelope.hasRequest()) {
            return encode(error(0, 0, GameError.INVALID_MESSAGE));
        }
        ServiceRequest request = envelope.getRequest();
        try {
            DecodedMessage<ServiceRequest, ServiceCommands> message = decoder.decode(request);
            LoginServiceHandler handler = registry.get(message.command());
            if (handler == null) {
                return encode(error(request.getCmd(), request.getRequestId(), GameError.UNKNOWN_COMMAND));
            }
            ServiceResponse response = handler.handle(message);
            if (response == null || response.getCmd() != request.getCmd()
                    || response.getRequestId() != request.getRequestId()) {
                throw new IllegalStateException("Login Handler 未返回匹配的最终响应");
            }
            return encode(response);
        } catch (UnknownCommandException exception) {
            return encode(error(request.getCmd(), request.getRequestId(), GameError.UNKNOWN_COMMAND));
        } catch (InvalidCommandBodyException exception) {
            return encode(error(request.getCmd(), request.getRequestId(), GameError.INVALID_MESSAGE));
        } catch (LoginServiceException exception) {
            // 只输出公共枚举中的错误码和提示，不直接输出异常堆栈、数据库或远端原文。
            return encode(error(request.getCmd(), request.getRequestId(), exception.getError()));
        } catch (DataAccessException exception) {
            // 数据库故障不能误判成密码错误，也不把 SQL 或凭据暴露给调用方。
            log.error("Login 账号查询失败, requestId={}, failureType={}",
                    request.getRequestId(), exception.getClass().getSimpleName());
            return encode(error(request.getCmd(), request.getRequestId(), GameError.SERVICE_UNAVAILABLE));
        } catch (Exception exception) {
            // 不记录原始登录包，也不将内部异常详情透传 Gateway。
            log.error("Login 指令处理异常, cmd={}, requestId={}, failureType={}",
                    request.getCmd(), request.getRequestId(), exception.getClass().getSimpleName());
            return encode(error(request.getCmd(), request.getRequestId(), GameError.INTERNAL_ERROR));
        }
    }

    /** 错误也沿用调用方关联字段，失败时不携带成功路由。 */
    private ServiceResponse error(int cmd, long requestId, GameError error) {
        return ServiceResponse.newBuilder().setCmd(cmd).setRequestId(requestId)
                .setCode(error.code()).setMessage(error.message()).build();
    }

    /** HTTP 请求和响应都传 ServiceEnvelope 的序列化字节，不传 JSON。 */
    private byte[] encode(ServiceResponse response) {
        return ServiceEnvelope.newBuilder().setResponse(response).build().toByteArray();
    }
}
