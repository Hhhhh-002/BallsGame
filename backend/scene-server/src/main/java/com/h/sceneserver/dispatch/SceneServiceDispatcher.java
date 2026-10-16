package com.h.sceneserver.dispatch;

import com.google.protobuf.Message;
import com.h.gamecommon.code.GameError;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecore.netty.PeerRegistry;
import com.h.gamecore.protocol.Messages;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.codec.CommandReceiveLog;
import com.h.gameprotocol.codec.decode.ServiceMessageDecoder;
import com.h.gameprotocol.proto.*;
import com.h.sceneserver.registries.SceneServiceCommandRegistry;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import static com.h.gamecore.protocol.Messages.require;

/** 请求、事件均通过注解注册器查 cmd；不在入口列举任何业务命令。 */
@Component
public final class SceneServiceDispatcher {
    private final SceneServiceCommandRegistry handlers;
    private final PeerRegistry peers;
    private final ServiceMessageDecoder decoder = new ServiceMessageDecoder();

    public SceneServiceDispatcher(SceneServiceCommandRegistry handlers, PeerRegistry peers) {
        this.handlers = handlers; this.peers = peers;
    }

    public CompletionStage<ServiceEnvelope> dispatch(Channel channel, ServiceEnvelope envelope) {
        CommandReceiveLog.service("scene-server","Agent WS",envelope);
        try {
            require(envelope.hasRequest() || envelope.hasEvent(), GameError.INVALID_MESSAGE);
            DecodedMessage<? extends Message, ServiceCommands> message = envelope.hasRequest()
                    ? decoder.decode(envelope.getRequest()) : decoder.decode(envelope.getEvent());
            CommandReceiveLog.nested("scene-server","Agent WS",message.body());
            var handler = handlers.get(message.command());
            require(handler != null && handler.event() == envelope.hasEvent(), GameError.UNKNOWN_COMMAND);
            if (handler.requiresBoundPeer()) require(peers.current(channel), GameError.NOT_LOGGED_IN);
            return handler.handle(channel, message).exceptionally(failure -> {
                if (envelope.hasRequest()) return Messages.error(envelope.getRequest(), failure);
                throw new CompletionException(Messages.unwrap(failure));
            });
        } catch (Exception failure) {
            if (envelope.hasRequest()) return CompletableFuture.completedFuture(Messages.error(envelope.getRequest(), failure));
            return CompletableFuture.failedFuture(failure);
        }
    }
}
