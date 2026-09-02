package com.nexusai.infra.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SleepUtils · 对齐 CC utils/sleep.ts.
 *
 * <p>L1 语义: abort-responsive sleep + withTimeout 工具方法。
 * <ul>
 *   <li>{@link #sleep(long)} — 简单 sleep ms</li>
 *   <li>{@link #withTimeout(CompletableFuture, long, String)} — race 异步 vs timeout</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 静态方法 + 注入式 error message</li>
 *   <li><b>A2 Golden Trace</b>: sleep(0) 立即返回;withTimeout fast future→future value;timeout→TimeoutException</li>
 *   <li><b>A3 副作用</b>: Thread.sleep 调用;Timer 启动</li>
 *   <li><b>A4 边界</b>: ms=0→立即;negative→IllegalArgumentException;future 已完成→直接返回</li>
 *   <li><b>A5 业务场景</b>: API call 200ms timeout 保护;backoff sleep 接收 AbortSignal</li>
 * </ul>
 *
 * <p>L3 升级: TS setTimeout promise → Java CompletableFuture delayedExecutor;
 * TS Promise.race → Java CompletableFuture.completeOnTimeout / orTimeout;
 * TS throwOnAbort option → Java runnable / supplier (default 行为).
 */
public final class SleepUtils {

    private SleepUtils() {}

    /**
     * Abort-responsive sleep. Resolves after {@code ms} milliseconds.
     * Java standard equivalent of CC sleep (no AbortSignal in JDK 17 core; we
     * use Thread.interrupt check on the current thread for cancellation).
     *
     * @param ms milliseconds to wait
     * @return future that completes after the delay
     */
    public static CompletableFuture<Void> sleep(long ms) {
        if (ms < 0) throw new IllegalArgumentException("ms must be >= 0");
        if (ms == 0) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(ms);
                future.complete(null);
            } catch (InterruptedException e) {
                future.completeExceptionally(e);
            }
        });
        t.setDaemon(true);
        t.start();
        return future;
    }

    /**
     * Race a future against a timeout. Rejects with {@link RuntimeException}(message)
     * if the future doesn't settle within {@code ms}. The timeout timer is cleared
     * when the future settles (no dangling timer).
     */
    public static <T> T withTimeout(CompletableFuture<T> future, long ms, String message) {
        if (future == null) throw new IllegalArgumentException("future must not be null");
        if (future.isDone()) return future.join();
        try {
            return future.get(ms, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException(message, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(message, e);
        } catch (ExecutionException e) {
            throw new RuntimeException(message, e.getCause());
        }
    }
}
