package com.h.gatewayserver.context;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.util.AttributeKey;
import lombok.Getter;
import lombok.Setter;

/** 单条客户端连接的状态，只在所属 Channel 的 EventLoop 上修改。 */
@Getter
@Setter
public class GatewayContext {
    /** 每条客户端 Channel 各自保存一个 GatewayContext。 */
    public static final AttributeKey<GatewayContext> KEY = AttributeKey.valueOf("BALLS_GATEWAY_CONTEXT");
    /** 当前客户端连接；断开时 Channel Attribute 会解除对本对象的引用。 */
    private final Channel channel;

    /** Login 返回的字符串注册账号；未绑定前为空，不代表网关负责认证。 */
    private String userId = "";

    /** 登录服务返回的逻辑 PlayerAgent ID。 */
    private String playerActorId;

    /** PlayerAgent 宿主在 Nacos 注册的服务名。 */
    private String agentHostServiceName;

    /** PlayerAgent 宿主的 Nacos 实例 ID；玩家命令固定发往该实例。 */
    private String agentHostInstanceId;

    /** 当前 Channel 当前连接尚未完成的网关转发操作数，用于限制请求积压。 */
    private int inFlightRequests;
    /** 等待登录返回的操作 ID；此时尚不能凭 userId/Actor ID 建立已登录索引。 */
    private String loginOperationId = "";
    /** 登录等待的截止时间；超时后不能接受迟到的登录结果。 */
    private long loginDeadlineMs;

    /** 建立连接时创建，登录字段保持初始状态。 */
    public GatewayContext(Channel channel) {
//        如果传入的 channel == null，直接抛出 `NullPointerException`，异常消息为 `"channel"`
        this.channel = java.util.Objects.requireNonNull(channel, "channel");
    }

    /** 使用 Netty 自带的 Channel ID，无须另造连接标识。 */
    public ChannelId getChannelId() {
        return channel.id();
    }
}
