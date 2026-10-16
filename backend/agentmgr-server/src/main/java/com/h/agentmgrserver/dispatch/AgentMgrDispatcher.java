package com.h.agentmgrserver.dispatch;

import com.google.protobuf.InvalidProtocolBufferException;
import com.h.gamecommon.exception.AgentMgrException;
import com.h.agentmgrserver.registries.AgentMgrCommandRegistry;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.exception.InvalidCommandBodyException;
import com.h.gamecommon.exception.UnknownCommandException;
import com.h.gameprotocol.codec.decode.ServiceMessageDecoder;
import com.h.gameprotocol.proto.ServiceEnvelope;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;
import com.h.gameprotocol.codec.CommandReceiveLog;
import lombok.extern.slf4j.Slf4j;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** HTTP 字节入口的协议边界：解码、按 cmd 分发、保留关联 ID、统一返回公共错误。 */
@Component
@Slf4j
public class AgentMgrDispatcher {
    private final ServiceMessageDecoder decoder = new ServiceMessageDecoder();
    private final AgentMgrCommandRegistry registry;
    public AgentMgrDispatcher(AgentMgrCommandRegistry registry) { this.registry = registry; }

    public byte[] dispatch(byte[] bytes) {
        ServiceRequest request = ServiceRequest.getDefaultInstance();
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > 65_536) {
                return error(request, GameError.INVALID_MESSAGE);
            }
            ServiceEnvelope envelope = ServiceEnvelope.parseFrom(bytes);
            CommandReceiveLog.service("agentmgr-server","Feign HTTP",envelope);
            if (!envelope.hasRequest()) return error(request, GameError.INVALID_MESSAGE);
            request = envelope.getRequest();
            var message = decoder.decode(request);
            var handler = registry.get(message.command());
            if (handler == null) return error(request, GameError.UNKNOWN_COMMAND);
            ServiceResponse response = handler.handle(message);
            if (response == null || response.getCmd() != request.getCmd() || response.getRequestId() != request.getRequestId()) {
                throw new IllegalStateException("AgentMgr Handler 返回了不匹配的响应");
            }
            return encode(response);
        } catch (UnknownCommandException failure) {
            return error(request, GameError.UNKNOWN_COMMAND);
        } catch (InvalidCommandBodyException | InvalidProtocolBufferException failure) {
            return error(request, GameError.INVALID_MESSAGE);
        } catch (AgentMgrException failure) {
            return error(request, failure.getError());
        } catch (RedisException | DataAccessException failure) {
            // Redis 故障时禁止绕过仲裁去创建 Actor，返回临时不可用供原操作有限重试。
            log.warn("AgentMgr Redis 不可用, requestId={}", request.getRequestId());
            return error(request, GameError.SERVICE_UNAVAILABLE);
        } catch (Exception failure) {
            log.error("AgentMgr 内部处理失败, cmd={}, requestId={}, failureType={}",
                    request.getCmd(), request.getRequestId(), failure.getClass().getSimpleName());
            return error(request, GameError.INTERNAL_ERROR);
        }
    }

    private byte[] error(ServiceRequest request, GameError error) {
        return encode(ServiceResponse.newBuilder().setCmd(request.getCmd()).setRequestId(request.getRequestId())
                .setCode(error.code()).setMessage(error.message()).build());
    }

    private byte[] encode(ServiceResponse response) {
        return ServiceEnvelope.newBuilder().setResponse(response).build().toByteArray();
    }
}
