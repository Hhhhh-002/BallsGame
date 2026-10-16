package com.h.gamecommon.exception;

import com.h.gamecommon.code.GameError;

/** Login 可预期的业务失败；由分发器转换为统一 Protobuf 错误响应。 */
public class LoginServiceException extends RuntimeException {
    private final GameError error;

    /** 不携带明文密码或原始请求内容。 */
    public LoginServiceException(GameError error) {
        super(error.message());
        this.error = error;
    }

    /** 使用现有公共错误码，不为 Login 再创建一套重复编号。 */
    public GameError getError() {
        return error;
    }
}
