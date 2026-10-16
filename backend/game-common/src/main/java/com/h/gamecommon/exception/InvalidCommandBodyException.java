package com.h.gamecommon.exception;

/** 信封完整、body 解析失败时，保留 cmd/requestId 用于对应错误响应。 */

public class InvalidCommandBodyException extends RuntimeException {
    private final int cmd;
    private final long requestId;

    public InvalidCommandBodyException(int cmd, long requestId, Throwable cause) {
        super("Invalid command body: " + cmd, cause);
        this.cmd = cmd;
        this.requestId = requestId;
    }

    public int getCmd() {
        return cmd;
    }

    public long getRequestId() {
        return requestId;
    }
}
