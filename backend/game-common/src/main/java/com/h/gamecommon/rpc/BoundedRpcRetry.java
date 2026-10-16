package com.h.gamecommon.rpc;

import java.util.function.Predicate;
import java.util.function.Supplier;

/** 阻塞式 RPC 共用的有限退避循环；只能在 MVC/工作线程使用，不能放入 Netty EventLoop。 */
public final class BoundedRpcRetry {
    private BoundedRpcRetry() { }

    /**
     * action 在重试时必须复用原业务标识及目标实例；这里不生成请求、不切换实例。
     * maxAttempts 包含首次调用。调用方分类临时故障，协议错误和确定业务失败不重试。
     */
    public static <T> T execute(Supplier<T> action, Predicate<RuntimeException> retryable,
                                int maxAttempts, long initialBackoffMs, long maxBackoffMs) {
        if (maxAttempts < 1 || initialBackoffMs < 1 || maxBackoffMs < initialBackoffMs) {
            throw new IllegalArgumentException("RPC 重试参数不合法");
        }
        long delay = initialBackoffMs;
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException failure) {
                if (attempt >= maxAttempts || !retryable.test(failure)) {
                    throw failure;
                }
                try {
                    // 仅阻塞当前 HTTP/业务工作线程；中断时终止重试，不吞掉停止信号。
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("RPC 重试被中断", interrupted);
                }
                delay = delay >= maxBackoffMs / 2 ? maxBackoffMs : Math.min(maxBackoffMs, delay * 2);
            }
        }
    }
}
