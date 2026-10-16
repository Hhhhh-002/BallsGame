package com.h.gamecommon.code;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LoginError {
    /** 注册错误独立于登录白名单，不把远端异常原文发送给客户端。 */
    public static final Map<Integer, GameError> PUBLIC_REGISTER_ERRORS = Stream.of(
            GameError.INVALID_MESSAGE, GameError.ACCOUNT_EMPTY, GameError.PASSWORD_EMPTY,
            GameError.ACCOUNT_TOO_LONG, GameError.PASSWORD_TOO_LONG,
            GameError.ACCOUNT_FORMAT_INVALID, GameError.PASSWORD_FORMAT_INVALID,
            GameError.ACCOUNT_ALREADY_EXISTS, GameError.REGISTER_FAILED,
            GameError.SERVICE_UNAVAILABLE, GameError.INTERNAL_ERROR
    ).collect(Collectors.toUnmodifiableMap(GameError::code, error -> error));

    /**
     * 允许向客户端公开的登录错误白名单，按错误码映射到本地可信提示。
     * 不直接使用远端 response.message，防止把 SQL、实例地址或内部异常详情发给客户端。
     * 保留旧的合并错误码兼容旧服务；新增服务可以返回细分账号、密码和封禁错误。
     */
    public static final Map<Integer, GameError> PUBLIC_LOGIN_ERRORS = Stream.of(
            GameError.LOGIN_FAILED,
            GameError.LOGIN_IN_PROGRESS,
            GameError.LOGIN_HAVE_LOGINED,
            GameError.LOGOUT_IN_PROGRESS,
            GameError.LOGIN_OPERATION_SUPERSEDED,
            GameError.OPERATION_CONFLICT,
            GameError.ACCOUNT_PASSWORD_EMPTY,
            GameError.ACCOUNT_PASSWORD_ERROR,
            GameError.ACCOUNT_FORMAT_INVALID,
            GameError.PASSWORD_FORMAT_INVALID,
            GameError.ACCOUNT_NOT_FOUND,
            GameError.PASSWORD_INCORRECT,
            GameError.ACCOUNT_DISABLED,
            GameError.ACCOUNT_EMPTY,
            GameError.PASSWORD_EMPTY,
            GameError.ACCOUNT_TOO_LONG,
            GameError.PASSWORD_TOO_LONG,
            GameError.SERVICE_UNAVAILABLE,
            GameError.INTERNAL_ERROR
    ).collect(Collectors.toUnmodifiableMap(GameError::code, error -> error));
}
