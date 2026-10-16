package com.h.gamecommon.code;

/** 网关返回给客户端的通用错误码。 */
public enum GameError {
    OK(0, "OK"),
    INVALID_MESSAGE(4001, "消息格式错误"),
    UNKNOWN_COMMAND(4002, "未知指令"),
    NOT_LOGGED_IN(4003, "尚未登录"),
    LOGIN_IN_PROGRESS(4004, "正在登录"),
    LOGIN_HAVE_LOGINED(4005, "已经登录"),
    AGENT_ROUTE_MISSING(4006, "尚未绑定玩家 Agent"),
    LOGIN_FAILED(4007, "登录失败"),
    ACCOUNT_PASSWORD_EMPTY(40008,"账号或密码为空"),
    ACCOUNT_PASSWORD_ERROR(40009,"账号或密码为错误"),
    /** 非空且长度符合存储限制，但不是支持的邮箱格式。 */
    ACCOUNT_FORMAT_INVALID(40010, "请输入正确的邮箱"),
    /** 密码格式校验失败；不在错误信息中回显用户输入的密码。 */
    PASSWORD_FORMAT_INVALID(40011, "密码必须包含数字、字母和特殊字符，长度必须大于8位，且不能包含空白"),
    /** 邮箱格式有效，但数据库中没有这个账号；按产品要求向客户端明确提示。 */
    ACCOUNT_NOT_FOUND(40012, "账号不存在"),
    /** 账号存在且未封禁，但 BCrypt 密码比对不通过。 */
    PASSWORD_INCORRECT(40013, "密码错误"),
    /** disabled=1；先返回封禁提示，不继续比对密码。 */
    ACCOUNT_DISABLED(40014, "账号已被封禁"),
    /** 账号校验先于密码校验；两者均空时先提示填写邮箱。 */
    ACCOUNT_EMPTY(40015, "请输入邮箱"),
    /** 不把空密码与账号不存在、密码不匹配混为一类。 */
    PASSWORD_EMPTY(40016, "请输入密码"),
    /** 与当前数据库 user_id 的 VARCHAR(64) 容量保持一致。 */
    ACCOUNT_TOO_LONG(40017, "邮箱长度不能超过64个字符"),
    /** BCrypt 长度上限按 UTF-8 字节计算，不能只比较字符数。 */
    PASSWORD_TOO_LONG(40018, "密码UTF-8编码长度不能超过72字节"),
    /** 不同登录请求不能顶替正在保存和退出的用户。 */
    LOGOUT_IN_PROGRESS(40019, "正在登出，请稍后重试"),
    /** 旧登录操作已被顶替或下线，不允许重放它来重新绑定连接。 */
    LOGIN_OPERATION_SUPERSEDED(40020, "本次登录已失效，请重新登录"),
    /** 同一操作 ID 被用于不同参数，或远端状态与仲裁记录冲突。 */
    OPERATION_CONFLICT(40021, "登录状态冲突，请稍后重试"),
    /** 注册邮箱已经存在；并发插入的主键冲突也返回此错误。 */
    ACCOUNT_ALREADY_EXISTS(40022, "该邮箱已被注册"),
    REGISTER_FAILED(40023, "注册失败"),
    NOT_IN_SCENE(40101, "尚未进入比赛"),
    ALREADY_IN_SCENE(40102, "已在比赛中"),
    SCENE_FULL(40103, "比赛人数已满"),
    SCENE_NOT_FOUND(40104, "比赛不存在或已结束"),
    INVALID_INPUT(40105, "游戏输入不合法"),
    REQUEST_EXPIRED(40106, "请求已超出去重窗口，请刷新比赛状态"),
    MATCH_WAITING(40107, "比赛尚未开始，请等待其他玩家"),
    SERVICE_UNAVAILABLE(5001, "服务暂不可用"),
    INTERNAL_ERROR(5002, "服务内部错误");


    /** code 随 ServerEnvelope 回给客户端，客户端不依赖中文 message 判断。 */
    private final int code;
    private final String message;

    GameError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() { return code; }
    public String message() { return message; }
}
