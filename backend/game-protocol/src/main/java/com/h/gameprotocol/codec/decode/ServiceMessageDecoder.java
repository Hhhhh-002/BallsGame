package com.h.gameprotocol.codec.decode;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.h.gamecommon.command.ServiceCommands;
import com.h.gamecommon.exception.InvalidCommandBodyException;
import com.h.gamecommon.exception.UnknownCommandException;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.*;

import java.util.Map;

/** 按服务间请求 cmd 解析业务 body，向 Handler 提供与客户端相同结构的解码结果。 */
public final class ServiceMessageDecoder {
    /** 主动事件也按 cmd 解码，不伪造成请求或分配不存在的 request_id。 */
    private static final Map<ServiceCommands, Parser<? extends Message>> EVENT_PARSERS = Map.of(
            ServiceCommands.SCENE_PLAYER_INPUT, ScenePlayerInput.parser());

    public DecodedMessage<ServiceEvent, ServiceCommands> decode(ServiceEvent event) {
        ServiceCommands command = ServiceCommands.fromCode(event.getCmd());
        Parser<? extends Message> parser = command == null ? null : EVENT_PARSERS.get(command);
        if (parser == null) throw new UnknownCommandException(event.getCmd(), 0);
        try {
            return new DecodedMessage<>(event, command, parser.parseFrom(event.getBody()));
        } catch (InvalidProtocolBufferException failure) {
            throw new InvalidCommandBodyException(event.getCmd(), 0, failure);
        }
    }

    /** 只登记请求消息的 Parser；响应和主动事件使用各自的协议处理流程。 */
    private static final Map<ServiceCommands, Parser<? extends Message>> PARSERS = Map.ofEntries(
            Map.entry(ServiceCommands.AUTH_LOGIN, GatewayLoginRequest.parser()),
            Map.entry(ServiceCommands.REGISTER_ACCOUNT, RegisterRequest.parser()),
            Map.entry(ServiceCommands.MATCH_HISTORY, SceneMatchHistoryRequest.parser()),
            Map.entry(ServiceCommands.BEGIN_LOGIN, BeginLoginRequest.parser()),
            Map.entry(ServiceCommands.CREATE_PLAYER_AGENT, CreatePlayerAgentRequest.parser()),
            Map.entry(ServiceCommands.KICK_OLD_AGENT, KickOldAgentRequest.parser()),
            Map.entry(ServiceCommands.KICK_OLD_CLIENT, KickOldClientRequest.parser()),
            Map.entry(ServiceCommands.AGENT_LINK_HELLO, GatewayAgentLinkHello.parser()),
            Map.entry(ServiceCommands.FORWARD_PLAYER_COMMAND, ForwardPlayerCommand.parser()),
            Map.entry(ServiceCommands.AGENT_SCENE_LINK_HELLO, AgentSceneLinkHello.parser()),
            Map.entry(ServiceCommands.SCENE_JOIN, SceneJoinRequest.parser()),
            Map.entry(ServiceCommands.SCENE_LEAVE, SceneLeaveRequest.parser()),
            Map.entry(ServiceCommands.CREATE_SCENE, CreateSceneRequest.parser()),
            Map.entry(ServiceCommands.FIND_SCENE, FindSceneRequest.parser()),
            Map.entry(ServiceCommands.PLAYER_DISCONNECTED, PlayerDisconnectedRequest.parser())
    );

    /** 外层 ServiceEnvelope 由入口解析；这里解析其中的 ServiceRequest，保留 cmd/request_id。 */
    public DecodedMessage<ServiceRequest, ServiceCommands> decode(ServiceRequest request) {
        ServiceCommands command = ServiceCommands.fromCode(request.getCmd());
//        解析为Message子类的对应协议消息类的解析器 Parser
        Parser<? extends Message> parser = command == null ? null : PARSERS.get(command);
        if (parser == null) {
            throw new UnknownCommandException(request.getCmd(), request.getRequestId());
        }
        try {
            Message body = parser.parseFrom(request.getBody());
            return new DecodedMessage<>(request, command, body);
        } catch (InvalidProtocolBufferException exception) {
            // 外层请求仍有效，错误响应可以沿用调用方的 cmd 和 request_id。
            throw new InvalidCommandBodyException(request.getCmd(), request.getRequestId(), exception);
        }
    }
}
