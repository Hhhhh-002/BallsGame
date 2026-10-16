package com.h.gameprotocol.codec;

import com.google.protobuf.Message;
/**
 * 客户端和服务间请求共用的解码结果：保留外层请求、已识别命令和已解析的业务 body。
 * 客户端使用 ClientEnvelope/GameCommands；服务间使用 ServiceRequest/ServiceCommands。
 * 泛型保留外层协议和命令的具体类型，Handler 可以直接取 cmd/request_id 及业务参数。
 *
 * @param envelope 外层请求，保留请求关联信息
 * @param command 解码器识别的命令枚举
 * @param body 由命令对应 Parser 解析的业务消息，例如 LoginRequest 或 KickOldClientRequest
 */
public record DecodedMessage<E extends Message, C extends Enum<C>>(E envelope, C command, Message body) {
}
