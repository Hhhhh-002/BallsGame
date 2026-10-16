package com.h.gamecommon.exception;

/** 保留未知 cmd 和 requestId，方便 Gateway 原样关联错误响应。 */
public class UnknownCommandException extends RuntimeException {
    private final int cmd;
    private final long requestId;

    public UnknownCommandException(int cmd, long requestId) {
        super("Unknown command: " + cmd);
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
