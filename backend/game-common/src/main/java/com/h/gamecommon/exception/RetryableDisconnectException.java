package com.h.gamecommon.exception;

/**
 * 表示断线通知的处理结果暂时不确定或 AgentMgr 明确报告临时失败。
 * 调用方可用该类型识别可重试的业务失败；协议格式错误不应使用此异常。
 */
public class RetryableDisconnectException extends RuntimeException {
    /** 保留临时失败原因，供重试日志与最终错误日志使用。 */
    public RetryableDisconnectException(String message) {
        super(message);
    }
}
