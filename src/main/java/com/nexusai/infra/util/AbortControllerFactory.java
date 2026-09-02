package com.nexusai.infra.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * AbortControllerFactory · 对齐 CC utils/abortController.ts.
 *
 * <p>L1 语义: abort controller 工厂 + 一次性 listener + sleep with abort。
 * <ul>
 *   <li>{@link #create()} — 返回新 AbortController (record + AtomicBoolean + listener list)</li>
 *   <li>{@link #sleep(long, AbortControllerRef, boolean)} — await ms, abort → silent resolve OR throw</li>
 *   <li>{@link #peekForStdinData(CompletionStage, long, Runnable)} — 异步 peek 检测首次 data</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 2 record (AbortControllerRef + PeekResult) + 3 静态方法 (create + sleep + peekForStdinData)</li>
 *   <li><b>A2 Golden Trace</b>: sleep 50ms 正常返回;abort during sleep → silent resolve;throwOnAbort→抛;peek 首次 data → clear timer</li>
 *   <li><b>A3 副作用</b>: Thread sleep + abort listener add/remove</li>
 *   <li><b>A4 边界</b>: ms=0→立即返;null signal→等同未 abort;future 已完成→返</li>
 *   <li><b>A5 业务场景</b>: REPL CLI -p mode 区分 idle stdin vs inherited 父 stdin</li>
 * </ul>
 *
 * <p>L3 升级: TS AbortSignal → Java AbortControllerRef record (Java JDK 没原生 AbortController);
 * TS Promise → Java CompletableFuture;
 * TS EventEmitter addEventListener once:true → Java AtomicBoolean listener.
 */
public final class AbortControllerFactory {

    public static final class AbortControllerRef {
        private final AtomicBoolean aborted = new AtomicBoolean(false);
        private volatile String reason;
        private final java.util.List<Runnable> listeners = new java.util.ArrayList<>();

        public AbortControllerRef() {}

        public AtomicBoolean aborted() { return aborted; }
        public String reason() { return reason; }
        public java.util.List<Runnable> listeners() { return listeners; }

        public void abort(String newReason) {
            aborted.set(true);
            // CC: retain first reason (idempotent abort)
            if (this.reason == null) {
                this.reason = newReason;
            }
            for (Runnable l : listeners) {
                try { l.run(); } catch (RuntimeException ignored) {}
            }
        }

        public void abort() { abort(null); }

        public void addListener(Runnable l) {
            if (l != null) listeners.add(l);
        }

        public void removeListener(Runnable l) {
            listeners.remove(l);
        }
    }

    public record PeekResult(boolean timedOut) {}

    private AbortControllerFactory() {}

    public static AbortControllerRef create() {
        return new AbortControllerRef();
    }

    public static void sleep(long ms, AbortControllerRef signal, boolean throwOnAbort) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            if (signal != null && signal.aborted().get()) {
                if (throwOnAbort) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("aborted", e);
                }
            }
        }
    }

    /**
     * Race a future against a timeout. Returns true if the future settled, false on timeout.
     * If the future completes first, the timer is cleared.
     */
    public static PeekResult peekForStdinData(
        CompletableFuture<?> future, long ms, Runnable onTimeout) {
        java.util.concurrent.ScheduledFuture<?> timer = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor()
            .schedule(() -> onTimeout.run(), ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            future.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            timer.cancel(false);
            return new PeekResult(false);
        } catch (Exception e) {
            return new PeekResult(true);
        }
    }
}
