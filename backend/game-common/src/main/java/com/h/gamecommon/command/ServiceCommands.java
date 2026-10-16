package com.h.gamecommon.command;

import java.util.HashMap;
import java.util.Map;

public enum ServiceCommands {
    /** 未指定命令；收到该编号时应按非法消息处理。 */
    UNSPECIFIED(0),

    /** Gateway -> Login：校验账号并完成登录流程。 */
    AUTH_LOGIN(10001),

    /** Login -> AgentMgr：账号已校验，请求处理该用户登录。 */
    BEGIN_LOGIN(10002),

    /** AgentMgr -> 指定 Agent 实例：创建 PlayerActor 和当前玩家 Context。 */
    CREATE_PLAYER_AGENT(10003),

    /** AgentMgr -> 旧 Agent 实例：确认离场并停止旧 PlayerActor，返回实际停止结果。 */
    KICK_OLD_AGENT(10004),

    /** AgentMgr -> 旧 Gateway：通知并断开旧客户端连接。 */
    KICK_OLD_CLIENT(10005),

    /** Gateway -> Agent 实例：声明内部长连接属于哪个 Gateway 实例。 */
    AGENT_LINK_HELLO(10006),

    /** Gateway -> Agent 实例：将客户端游戏指令交给对应 PlayerAgent。 */
    FORWARD_PLAYER_COMMAND(10007),

    /** Agent 实例 -> Gateway：向一个或多个客户端主动推送消息。 */
    FORWARD_TO_CLIENT(10008),

    AGENT_SCENE_LINK_HELLO(10009),// Agent -> Scene：长连接握手

    SCENE_JOIN(10010),             // Agent -> Scene：玩家加入

    SCENE_LEAVE(10011),            // Agent -> Scene：玩家离开

    SCENE_PLAYER_INPUT(10012),     // Agent -> Scene：移动/分裂等战斗输入

    SCENE_TO_AGENT(10013),         // Scene -> Agent：战斗结果或广播

    CREATE_SCENE(10014),           // Agent -> Scene：创建一场游戏的 SceneActor

    /** Gateway -> AgentMgr：客户端断线，请求保存并下线本次登录的 PlayerAgent。 */
    PLAYER_DISCONNECTED(10015),
    /** 在已选定 Scene 实例内查找/创建可加入的比赛。 */
    FIND_SCENE(10016),
    /** Gateway -> Login：注册邮箱账号，body 为 RegisterRequest。 */
    REGISTER_ACCOUNT(10018),
    /** Agent -> Scene：查询当前已登录用户的战绩，用户身份由 PlayerContext 提供。 */
    MATCH_HISTORY(10019);

    private static final Map<Integer, ServiceCommands> BY_CODE;

    /**
     *  枚举类自动生成的隐式方法，
     * 1. `ServiceCommands[] values()`   第一个可以获取到当前枚举类定义的所以枚举实例
     * 2. `ServiceCommands valueOf(String name)`   第二个可以获取到当前枚举类定义的指定的枚举实例
     */
    static {
        Map<Integer, ServiceCommands> commands = new HashMap<>();
        for (ServiceCommands command : values()) {
            if (commands.putIfAbsent(command.code, command) != null) {
                throw new IllegalStateException("重复的服务间 cmd 编号: " + command.code);
            }
        }
        BY_CODE = Map.copyOf(commands);
    }

    private final int code;

    ServiceCommands(int code) {
        this.code = code;
    }

    /** 写入 Protobuf 的数字命令编号。 */
    public int code() {
        return code;
    }

    /** 未知编号返回 null，由接收服务按未知命令处理。 */
    public static ServiceCommands fromCode(int code) {
        return BY_CODE.get(code);
    }
}


