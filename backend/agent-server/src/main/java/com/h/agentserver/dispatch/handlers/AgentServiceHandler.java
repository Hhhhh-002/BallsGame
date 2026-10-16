package com.h.agentserver.dispatch.handlers;

import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;
import io.netty.channel.Channel;
import java.util.concurrent.CompletionStage;

/** 服务间请求 Handler；入口权限属于 Handler 元数据，业务分发只查 cmd。 */
public interface AgentServiceHandler {
    CompletionStage<ServiceEnvelope> handle(Channel channel, DecodedMessage<ServiceRequest, ServiceCommands> message);
    default boolean http() { return false; }
    default boolean requiresBoundPeer() { return true; }
}
