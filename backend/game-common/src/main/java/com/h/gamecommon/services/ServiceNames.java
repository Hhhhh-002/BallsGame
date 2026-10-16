package com.h.gamecommon.services;

import java.text.MessageFormat;

/** Nacos 中的逻辑服务名。 */
public final class ServiceNames {
    /** 登录服务不持有玩家 Actor，可从可用实例中任选一个。 */
    public static final String LOGIN = "login-server";
    /** AgentMgr 按此服务名发现并定向调用旧 Gateway 实例。 */
    public static final String GATEWAY = "gateway-server";
    /** 单例管理服务只负责玩家 Agent 的分配和管理，不接收网关的游戏指令。 */
    public static final String AGENT_MANAGER = "agentmgr-server";

    /** 玩家 Actor 运行在 Agent 服务实例上；场景 Actor 运行在 Scene 服务实例上。 */
    public static final String AGENT = "agent-server";
    public static final String SCENE = "scene-server";

    /** Nacos 实例元数据中由应用声明的实例标识和内部 WebSocket 监听端口。 */
    public static final String INSTANCE_ID_METADATA = "instanceid";
    /** Gateway 与 Agent 同机时填写相同值；只用于分配加权，不进入每个玩家的在线路由。 */
    public static final String HOST_ID_METADATA = "host-id";
    public static String getAgentWsPortMetadataKey(String type) {
        return MessageFormat.format("netty.{0}.websocket.port", type);
    }



    private ServiceNames() {
    }
}
