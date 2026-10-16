package com.h.gatewayserver.netty.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.List;

/** 将出站 byte[] 转换为 BinaryWebSocketFrame，再通过 WebSocket 发给客户端。 */
public class WebSocketBinaryFrameEncoder extends MessageToMessageEncoder<byte[]> {

    @Override
    /**
     * 为待发送的字节数组分配 ByteBuf，并封装为 WebSocket 二进制帧。
     *
     * @param context 当前 Channel 的处理上下文
     * @param bytes   业务层准备发送给客户端的二进制数据
     * @param output  用于保存编码结果的列表
     */
    protected void encode(ChannelHandlerContext context, byte[] bytes, List<Object> output) {
//        context.alloc()：获取当前 Netty Channel 使用的内存分配器
//buffer(bytes.length)：申请一个容量至少为 bytes.length 的 ByteBuf
//buffer 用来承载即将发送的数据
        ByteBuf buffer = context.alloc().buffer(bytes.length);
//        把 byte[] 中的所有字节写入 ByteBuf。
        buffer.writeBytes(bytes);
//        使用 buffer 创建一个 WebSocket 二进制帧
        output.add(new BinaryWebSocketFrame(buffer));
    }
}
