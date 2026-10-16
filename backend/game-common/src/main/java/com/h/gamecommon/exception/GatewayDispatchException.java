package com.h.gamecommon.exception;

import com.h.gamecommon.code.GameError;

/** 可直接转换为客户端业务错误码的分发异常。 */
public class GatewayDispatchException extends RuntimeException {
    private final GameError error;

    public GatewayDispatchException(GameError error) {
        super(error.message());
        this.error = error;
    }

    public GameError getError() {
        return error;
    }
}
