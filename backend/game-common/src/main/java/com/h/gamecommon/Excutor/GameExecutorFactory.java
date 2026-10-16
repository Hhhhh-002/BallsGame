package com.h.gamecommon.Excutor;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 各服务共用的线程池创建工厂。这里只提供静态创建方法，不持有全局共享线程池；
 * 每个服务应保存自己创建的线程池，并在服务停止时负责关闭。
 */
public final class GameExecutorFactory {
    private GameExecutorFactory() {
    }

    /**
     * 创建有界固定线程池，适合阻塞式 RPC 等任务。
     * 队列满且全部线程繁忙时直接拒绝新任务，避免请求无限积压。
     *
     * @param name 线程名前缀，便于日志和线程转储定位
     * @param threads 固定线程数，必须大于 0
     * @param queueCapacity 等待队列容量，必须大于 0
     * @return 由调用方负责关闭的线程池
     */
    public static ThreadPoolExecutor newFixedThreadPool(String name, int threads, int queueCapacity) {
        return newThreadPool(name, threads, threads, 0, queueCapacity);
    }

    /**
     * 创建有界可伸缩线程池。核心线程以外的线程空闲超过 keepAliveSeconds 后退出。
     * 当线程和队列都满时采用 AbortPolicy，调用方应处理拒绝异常。
     *
     * @param name 线程名前缀，不能为空
     * @param coreThreads 核心线程数，至少为 1
     * @param maxThreads 最大线程数，不小于 coreThreads
     * @param keepAliveSeconds 非核心线程的空闲存活秒数，不小于 0
     * @param queueCapacity 等待队列容量，至少为 1
     * @return 由调用方负责关闭的线程池
     */
    public static ThreadPoolExecutor newThreadPool(String name, int coreThreads, int maxThreads,
                                                    long keepAliveSeconds, int queueCapacity) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("线程池名称不能为空");
        }
        if (coreThreads <= 0 || maxThreads < coreThreads || keepAliveSeconds < 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("线程池参数不合法：coreThreads 和 queueCapacity 必须大于 0，"
                    + "maxThreads 不小于 coreThreads，keepAliveSeconds 不小于 0");
        }

        AtomicInteger threadNumber = new AtomicInteger(1);
        ThreadFactory threadFactory = task -> new Thread(task, name + "-" + threadNumber.getAndIncrement());
        return new ThreadPoolExecutor(coreThreads, maxThreads, keepAliveSeconds, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }


}
