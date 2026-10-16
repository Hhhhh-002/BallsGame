package com.h.gamecore.protocol;
import com.h.gamecommon.code.GameError;
/** 可映射为协议错误码的业务失败，不回显异常堆栈或数据库信息。 */
public final class BattleException extends RuntimeException {
    private final GameError error;
    public BattleException(GameError error) { super(error.message()); this.error = error; }
    public GameError error() { return error; }
}
