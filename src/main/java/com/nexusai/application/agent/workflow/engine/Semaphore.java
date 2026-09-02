package com.nexusai.application.agent.workflow.engine;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.workflow.WorkflowConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 异步信号量 · 对齐 CC {@code engine/concurrency.ts:10-56 Semaphore}。
 *
 * <p><b>为何自实现而非 java.util.concurrent.Semaphore</b>（P0-plan §5 CC 对齐要点）：
 * Java 标准 Semaphore 的同步 acquire 不支持「等待中 abort → 立即失败 + 不消耗 permit」的取消语义。
 * 本类用 CompletableFuture 队列实现等价行为：</p>
 * <ul>
 *   <li><b>permit 守恒</b>：acquire 成功返回 {@link Permit}；release 时 permit <b>直接转移</b>给下一 waiter
 *       （available 不变，concurrency.ts:48-55），无 waiter 才 available+1。</li>
 *   <li><b>abort 不占槽</b>：{@code signal} 已 abort 或等待中 abort → acquire 立即失败、waiter 出队、
 *       <b>不消耗 permit</b>（防取消 agent 占并发槽，concurrency.ts:21-45）。</li>
 *   <li>{@link #clampMaxConcurrency} 归一化用户 maxConcurrency（concurrency.ts:70-73）：
 *       null/NaN→DEFAULT_MAX_CONCURRENCY(3)、&lt;1→1、&gt;CAP(16)→16、否则 trunc。</li>
 * </ul>
 */
public final class Semaphore {

    private static final Logger log = LoggerFactory.getLogger(Semaphore.class);

    /** acquire 因 abort 失败的异常（复用单例：不携带堆栈，性能友好）。 */
    private static final IllegalStateException ABORTED = new IllegalStateException("Semaphore.acquire aborted");

    private final Object lock = new Object();
    private int available;
    private final Deque<Waiter> waiters = new ArrayDeque<>();

    /**
     * @param permits 许可数；构造时 {@code max(1, floor(permits))}（concurrency.ts:18）
     */
    public Semaphore(int permits) {
        this.available = Math.max(1, (int) Math.floor(permits));
    }

    /**
     * 归一化用户 maxConcurrency 为合法许可数 · 对齐 CC {@code clampMaxConcurrency}
     * (concurrency.ts:70-73)：null → 默认 3；&lt;1 → 1（至少一个槽，否则 workflow 无法推进）；
     * &gt;MAX_CONCURRENCY_CAP → CAP；否则 trunc。
     */
    public static int clampMaxConcurrency(Integer n) {
        if (n == null) {
            return WorkflowConstants.DEFAULT_MAX_CONCURRENCY;
        }
        int t = (int) Math.floor(n);
        return Math.max(1, Math.min(t, WorkflowConstants.MAX_CONCURRENCY_CAP));
    }

    /**
     * 异步 acquire · 对齐 CC {@code acquire(signal?): Promise<() => void>} (concurrency.ts:21-46)。
     *
     * <p>available &gt; 0 → 立即扣减并返回已完成 permit；否则入队等待。{@code signal} 已 abort →
     * 立即 failed；等待中 abort → 出队 + failed 且不消耗 permit。</p>
     *
     * @param signal 取消信号（可为 null；对齐 CC AbortSignal 语义，Java 复用现有
     *               {@link AbortController} 的 isCancelled/onCancel/removeOnCancel）
     * @return 完成的 permit（成功）或 failed（abort）
     */
    public CompletableFuture<Permit> acquire(AbortController signal) {
        if (signal != null && signal.isCancelled()) {
            if (log.isDebugEnabled()) {
                log.debug("Semaphore.acquire 立即失败：signal 已 abort（不占槽，concurrency.ts:22-24）");
            }
            return CompletableFuture.failedFuture(ABORTED);
        }
        synchronized (lock) {
            if (available > 0) {
                available -= 1;
                return CompletableFuture.completedFuture(new Permit(() -> release()));
            }
        }
        Waiter w = new Waiter(signal);
        synchronized (lock) {
            waiters.addLast(w);
        }
        if (signal != null) {
            w.abortListener = ac -> abortWaiter(w);
            signal.onCancel(w.abortListener);
            // 竞态守卫：onCancel 注册与 abort 之间可能已 abort → 同步触发一次
            if (signal.isCancelled()) {
                abortWaiter(w);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Semaphore.acquire 入队等待：waiters={}（permits={}，等待中 abort 不占槽）",
                    waiters.size(), available);
        }
        return w.future;
    }

    /**
     * 等待中 abort：从队列移除 waiter 并失败，不消耗 permit（concurrency.ts:30-34）。
     */
    private void abortWaiter(Waiter w) {
        synchronized (lock) {
            if (waiters.remove(w)) {
                w.completeAbort();
            }
            // 不在队列 = release 已把它取走 → permit 路径负责完成，abort 为 no-op
        }
    }

    /**
     * release：permit 直接转移给下一 waiter（available 不变）；无 waiter 才 available+1
     * (concurrency.ts:48-55)。
     */
    private void release() {
        Waiter next;
        synchronized (lock) {
            next = waiters.pollFirst();
            if (next == null) {
                available += 1;
                if (log.isDebugEnabled()) {
                    log.debug("Semaphore.release 无等待者：available+1 = {}", available);
                }
                return;
            }
        }
        next.completePermit(this);
        if (log.isDebugEnabled()) {
            log.debug("Semaphore.release 直传 permit 给下一 waiter（available 不变 = {}）", available);
        }
    }

    /** 排队中的 waiter：release 直传 permit 或 abort 移除，恰好一次完成。 */
    private static final class Waiter {
        final CompletableFuture<Permit> future = new CompletableFuture<>();
        final AbortController signal;
        volatile Consumer<AbortController> abortListener;
        volatile boolean done;

        Waiter(AbortController signal) {
            this.signal = signal;
        }

        void completePermit(Semaphore sem) {
            if (tryMarkDone()) {
                removeAbortListener();
                future.complete(new Permit(sem::release));
            }
        }

        void completeAbort() {
            if (tryMarkDone()) {
                removeAbortListener();
                future.completeExceptionally(ABORTED);
            }
        }

        private synchronized boolean tryMarkDone() {
            if (done) {
                return false;
            }
            done = true;
            return true;
        }

        private void removeAbortListener() {
            AbortController s = signal;
            Consumer<AbortController> l = abortListener;
            if (s != null && l != null) {
                s.removeOnCancel(l);
            }
        }
    }

    /** 已获得的许可（释放函数封装）· 对齐 CC acquire 返回的 release 函数。 */
    public record Permit(Runnable release) {
    }
}
