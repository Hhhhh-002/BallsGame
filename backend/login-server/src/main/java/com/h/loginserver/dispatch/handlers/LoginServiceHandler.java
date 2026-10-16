package com.h.loginserver.dispatch.handlers;

import com.h.gamecommon.command.ServiceCommands;
import com.h.gameprotocol.codec.DecodedMessage;
import com.h.gameprotocol.proto.ServiceRequest;
import com.h.gameprotocol.proto.ServiceResponse;

/** Login 的服务间 Handler；复用公共解码结果，不重新解析 body。 */
public interface LoginServiceHandler {
    /** 在 Spring MVC 的 HTTP 工作线程执行，返回最终结果，并沿用请求 cmd/request_id。 */
    ServiceResponse handle(DecodedMessage<ServiceRequest, ServiceCommands> message);
}
