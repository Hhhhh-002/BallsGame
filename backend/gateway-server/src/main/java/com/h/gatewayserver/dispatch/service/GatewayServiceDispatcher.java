package com.h.gatewayserver.dispatch.service;

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
import com.h.gatewayserver.registries.GatewayServiceCommandRegistry;
import com.h.gatewayserver.dispatch.service.handlers.GatewayServiceHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 网关内部 HTTP 入口的服务间分发器，与客户端 ClientEnvelope 分发完全独立。 */
@Component
@Slf4j
public class GatewayServiceDispatcher {
    /** 共享协议模块负责 cmd -> 业务请求类型，网关注册表只负责 cmd -> Handler。 */
    private final ServiceMessageDecoder decoder = new ServiceMessageDecoder();
    private final GatewayServiceCommandRegistry registry;

    public GatewayServiceDispatcher(GatewayServiceCommandRegistry registry) {
        this.registry = registry;
    }

    /** 解析统一服务信封，按 ServiceCommands 查 Handler，并返回 Protobuf 服务响应。 */
    public Mono<byte[]> dispatch(byte[] bytes) {
        final ServiceEnvelope envelope;
        try {
            envelope = ServiceEnvelope.parseFrom(bytes);
            CommandReceiveLog.service("gateway-server","Feign HTTP",envelope);
        } catch (InvalidProtocolBufferException exception) {
            CommandReceiveLog.unreadable("gateway-server","Feign HTTP");
            return Mono.just(encode(error(0, 0, GameError.INVALID_MESSAGE)));
        }
        if (!envelope.hasRequest()) {
            return Mono.just(encode(error(0, 0, GameError.INVALID_MESSAGE)));
        }
        ServiceRequest request = envelope.getRequest();
        final DecodedMessage<ServiceRequest, ServiceCommands> message;
        try {
            // 进入 Handler 前就解析好 body，统一客户端与服务间请求的处理方式。
            message = decoder.decode(request);
        } catch (UnknownCommandException exception) {
            return Mono.just(encode(error(exception.getCmd(), exception.getRequestId(), GameError.UNKNOWN_COMMAND)));
        } catch (InvalidCommandBodyException exception) {
            return Mono.just(encode(error(exception.getCmd(), exception.getRequestId(), GameError.INVALID_MESSAGE)));
        }
        GatewayServiceHandler handler = registry.get(message.command());
        if (handler == null) {
            return Mono.just(encode(error(request.getCmd(), request.getRequestId(), GameError.UNKNOWN_COMMAND)));
        }

        // Handler 的异步结果结束后，HTTP 入口才会回复 AgentMgr；不在 HTTP EventLoop 上等待 Channel 关闭。
        return Mono.defer(() -> handler.handle(message))
//                handler 如果返回一个空 Mono（没有 onNext，直接 onComplete，没有返回任何响应对象），抛出异常。
//业务约束：所有服务间指令 handler **必须返回响应，不能空手结束**。
                .switchIfEmpty(Mono.error(new IllegalStateException("服务间 Handler 未返回最终响应")))
//                handler 成功返回 response 对象之后，校验：响应的 cmd、requestId 和原始请求一致。
                .flatMap(response -> {
                    if (response.getCmd() != request.getCmd()
                            || response.getRequestId() != request.getRequestId()) {
                        return Mono.error(new IllegalStateException("服务间响应 cmd/requestId 与请求不匹配"));
                    }
                    //- `Mono.just(response)` 会立刻发射 `onNext(response)`
                    //- 这个 `response` 继续往下游传递，走到后面的 `onErrorResume`（此时没有异常，直接跳过），最后给到 `map`。
                    return Mono.just(response);
                })
                //三层 `onErrorResume`，统一异常转业务错误包
                //onErrorResume：捕获异常，**把异常流恢复成正常流，返回业务错误响应对象，不向上抛异常**
                .onErrorResume(InvalidProtocolBufferException.class,
                        exception -> Mono.just(error(request.getCmd(), request.getRequestId(), GameError.INVALID_MESSAGE)))
                .onErrorResume(IllegalArgumentException.class,
                        exception -> Mono.just(error(request.getCmd(), request.getRequestId(), GameError.INVALID_MESSAGE)))
                .onErrorResume(exception -> {
                    log.error("网关服务间命令处理失败, cmd={}, requestId={}",
                            request.getCmd(), request.getRequestId(), exception);
                    return Mono.just(error(request.getCmd(), request.getRequestId(), GameError.INTERNAL_ERROR));
                })
                .map(this::encode);
    }

    private ServiceResponse error(int cmd, long requestId, GameError gameError) {
        return ServiceResponse.newBuilder()
                .setCmd(cmd)
                .setRequestId(requestId)
                .setCode(gameError.code())
                .setMessage(gameError.message())
                .build();
    }

    private byte[] encode(ServiceResponse response) {
        return ServiceEnvelope.newBuilder().setResponse(response).build().toByteArray();
    }
}
