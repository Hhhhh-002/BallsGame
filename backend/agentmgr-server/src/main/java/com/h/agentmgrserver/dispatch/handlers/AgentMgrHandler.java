package com.h.agentmgrserver.dispatch.handlers;

import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;

/** 与 Login 一样接收已解析消息；同步等待最终结果，不返回“已受理”作为成功。 */
public interface AgentMgrHandler {
    ServiceResponse handle(DecodedMessage<ServiceRequest, ServiceCommands> message);
}
