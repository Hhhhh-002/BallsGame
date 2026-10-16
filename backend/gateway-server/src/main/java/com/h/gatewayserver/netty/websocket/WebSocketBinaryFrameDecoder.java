package com.h.gatewayserver.netty.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.List;

/** 将入站 BinaryWebSocketFrame 转换为 byte[]，供游戏业务 Handler 使用。 */
public class WebSocketBinaryFrameDecoder extends MessageToMessageDecoder<BinaryWebSocketFrame> {

    @Override
    /**
     * 读取二进制帧中的全部字节，并将 byte[] 交给 Pipeline 的下一个入站 Handler。
     *
     * @param context 当前 Channel 的处理上下文
     * @param frame   客户端发送的 WebSocket 二进制帧
     * @param output  用于保存解码结果的列表
     */
    protected void decode(ChannelHandlerContext context, BinaryWebSocketFrame frame, List<Object> output) {
        byte[] bytes = new byte[frame.content().readableBytes()];
        frame.content().readBytes(bytes);
        output.add(bytes);
//        todo 后面讨论frame 是否需要释放，是否造成内存泄露

    }
}
