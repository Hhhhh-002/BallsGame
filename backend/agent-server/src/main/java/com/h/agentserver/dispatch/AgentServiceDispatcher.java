package com.h.agentserver.dispatch;

import com.h.agentserver.registries.AgentServiceCommandRegistry;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.*;
import com.h.gameprotocol.codec.decode.ServiceMessageDecoder;
import com.h.gameprotocol.codec.CommandReceiveLog;
import com.h.gameprotocol.proto.*;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import static com.h.gamecore.protocol.Messages.require;

/** HTTP 控制与 Gateway 长连接入口分离；共用协议 cmd 解码，不在入口线程执行 Actor/数据库业务。 */
@Component
public final class AgentServiceDispatcher {
    private final AgentServiceCommandRegistry handlers;
    private final PeerRegistry peers;
    private final ServiceMessageDecoder decoder=new ServiceMessageDecoder();
    public AgentServiceDispatcher(AgentServiceCommandRegistry handlers,PeerRegistry peers) {this.handlers=handlers;this.peers=peers;}
    public CompletableFuture<byte[]> http(byte[] bytes) {
        try {
            require(bytes.length<=65536,GameError.INVALID_MESSAGE);
            ServiceEnvelope envelope=ServiceEnvelope.parseFrom(bytes);
            CommandReceiveLog.service("agent-server","AgentMgr HTTP",envelope);
            require(envelope.hasRequest(),GameError.INVALID_MESSAGE);
            ServiceRequest request=envelope.getRequest();
            var message=decoder.decode(request);
            var handler=handlers.get(message.command());
            require(handler!=null && handler.http(),GameError.UNKNOWN_COMMAND);
            CompletionStage<ServiceEnvelope> result=handler.handle(null,message);
            return result.handle((response,failure)->(failure==null?response:Messages.error(request,failure)).toByteArray()).toCompletableFuture();
        } catch(Exception failure) {
            ServiceRequest request;
            try {
                ServiceEnvelope parsed=ServiceEnvelope.parseFrom(bytes);
                request=parsed.hasRequest()?parsed.getRequest():ServiceRequest.getDefaultInstance();
            } catch(Exception ignored){request=ServiceRequest.getDefaultInstance();}
            return CompletableFuture.completedFuture(Messages.error(request,failure).toByteArray());
        }
    }
    public CompletionStage<ServiceEnvelope> websocket(Channel channel,ServiceEnvelope envelope) {
        CommandReceiveLog.service("agent-server","Gateway WS",envelope);
        try {
            require(envelope.hasRequest(),GameError.INVALID_MESSAGE);
            ServiceRequest request=envelope.getRequest();
            var message=decoder.decode(request);
            CommandReceiveLog.nested("agent-server","Gateway WS",message.body());
            var handler=handlers.get(message.command());
            require(handler!=null && !handler.http(),GameError.UNKNOWN_COMMAND);
            if(handler.requiresBoundPeer())require(peers.current(channel),GameError.NOT_LOGGED_IN);
            return handler.handle(channel,message);
        } catch(Exception failure) {
            return envelope.hasRequest()?CompletableFuture.completedFuture(Messages.error(envelope.getRequest(),failure))
                    :CompletableFuture.failedFuture(failure);
        }
    }
}
