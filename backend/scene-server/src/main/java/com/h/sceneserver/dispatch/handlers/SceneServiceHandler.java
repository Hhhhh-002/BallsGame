package com.h.sceneserver.dispatch.handlers;

import com.google.protobuf.Message;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.ServiceEnvelope;
import io.netty.channel.Channel;
import java.util.concurrent.CompletionStage;

/** 服务请求和事件共用注册方式，保留各自原始信封和已解码 body。 */
public interface SceneServiceHandler {
    CompletionStage<ServiceEnvelope> handle(Channel channel, DecodedMessage<? extends Message, ServiceCommands> message);
    default boolean event() { return false; }
    default boolean requiresBoundPeer() { return true; }
}
