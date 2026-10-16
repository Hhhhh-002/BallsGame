package com.h.gamecommon.exception;

import com.h.gamecommon.code.GameError;

/** 分发器只公开公共错误码和提示；内部异常文本不直接进入客户端响应。 */
public class AgentMgrException extends RuntimeException {
    private final GameError error;
    private final boolean retryable;

    public AgentMgrException(GameError error) { this(error, false); }

    public AgentMgrException(GameError error, boolean retryable) {
        super(error.message());
        this.error = error;
        this.retryable = retryable;
    }

    public GameError getError() { return error; }
    public boolean isRetryable() { return retryable; }
}
