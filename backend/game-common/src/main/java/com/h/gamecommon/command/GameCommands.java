package com.h.gamecommon.command;

import java.util.HashMap;
import java.util.Map;

/** 客户端请求和服务端推送共用的游戏 cmd 编号；发布协议后不要修改或复用旧编号。 */
public enum GameCommands {
    /** 登录请求，交给 Login 服务。 */
    LOGIN(1001),
    /** 注册邮箱账号，只写账号数据，不自动登录或创建 Actor。 */
    REGISTER(1002),
    /** 已登录用户查询自己的历史战绩，body 为 MatchHistoryRequest。 */
    MATCH_HISTORY(1003),
    /** 服务端 -> 客户端：推送玩家位置，body 为 MoveBroadcast；不是客户端移动请求。 */
    MOVE(2001),
    /** 客户端 -> 服务端：分裂输入，经玩家所属 Agent 转给 SceneActor。 */
    SPLIT(2002),
    /** 客户端 -> 服务端：吐球输入，经玩家所属 Agent 转给 SceneActor。 */
    EJECT(2003),
    /** 客户端 -> 服务端：移动输入，body 为 ShiftRequest；经 Agent 转交 SceneActor。 */
    SHIFT(2004),
    /** 主动离开比赛，保留账号登录；body 为 LeaveRequest。 */
    LEAVE(2005),
    /** 开始游戏，Agent 选定 Scene 实例，查找或创建比赛后加入。 */
    START_GAME(2006),
    /** 服务端 -> 客户端：该连接因同一账号在其他地方登录而被踢下线。 */
    KICKED_OFFLINE(3001),
    SCENE_SNAPSHOT(3002),
    PLAYER_ENTERED(3003),
    FOOD_ADDED(3004),
    FOOD_EATEN(3005),
    PLAYER_LEFT(3006),
    BALL_CHANGED(3007),
    /** 每局实时榜推送，body 为 MatchRanking。 */
    MATCH_RANKING(3008),
    /** 最终战绩已提交 MySQL，最终榜快照已写入 Redis，body 为 frozen=true 的 MatchRanking。 */
    MATCH_ENDED(3009);

    private static final Map<Integer, GameCommands> BY_CODE;

    static {
        Map<Integer, GameCommands> commands = new HashMap<>();
        for (GameCommands command : values()) {
            if (commands.putIfAbsent(command.code, command) != null) {
                throw new IllegalStateException("重复的 cmd 编号: " + command.code);
            }
        }
        BY_CODE = Map.copyOf(commands);
    }

    /** Protobuf 信封中的数字协议号。 */
    private final int code;

    GameCommands(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 未知编号返回 null，由解码器生成带 requestId 的协议错误。 */
    public static GameCommands fromCode(int code) {
        return BY_CODE.get(code);
    }
}
