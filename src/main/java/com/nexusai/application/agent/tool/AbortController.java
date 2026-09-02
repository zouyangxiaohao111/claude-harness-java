package com.nexusai.application.agent.tool;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 轻量级 AbortController · 对齐 CC {@code utils/abortController.ts:97-110 AbortControllerRef}。
 *
 * <h2>用途</h2>
 * <p>用户取消、工具执行超时、错误终止时调用 {@link #abort()}，下游工具可监听：
 * <pre>
 *   if (ctx.abortController().isCancelled()) {
 *       throw new AbortException("user cancelled");
 *   }
 * </pre>
 *
 * <h2>对齐 CC 行为</h2>
 * <ul>
 *   <li>CC {@code permissions.ts:1163} — 入口检查 {@code abortController.signal.aborted}</li>
 *   <li>CC {@code toolExecution.ts:415-451} — abort 时生成 CANCEL_MESSAGE + 跳过 tool.call</li>
 *   <li>CC {@code toolExecution.ts:1694} — catch AbortError 标 isInterrupt + 调 PostToolUseFailure</li>
 * </ul>
 *
 * <h2>L3 升级</h2>
 * <p>CC 实际用 {@code AbortSignal}（Web API）— Java 无原生支持。本类用
 * {@code volatile boolean cancelled} + {@code CopyOnWriteArrayList<Consumer>} 实现等价行为：
 * 状态查询 O(1)、事件订阅线程安全、abort() 后所有 listener 触发一次。
 *
 * <h2>R32-#25 reason 字段</h2>
 * <p>CC {@code AbortSignal.reason} 是字符串（{@code 'interrupt' / 'sibling_error' /
 * 'permission_denied' / 'streaming_fallback'} 等），用于 {@link com.nexusai.application.agent.tool.StreamingToolExecutor}
 * 等下游做精细决策（如 {@code getAbortReason()} 三态）。本类新增
 * {@link #abort(String)} 重载 + {@link #reason()} getter；{@code reason} 类型为
 * {@link String}（nullable），首个 reason 胜出（对齐 CC once 语义 + Web AbortSignal 行为）。
 *
 * <h2>NOOP 模式</h2>
 * <p>{@link #NOOP} 是常量单例，工具即使没注入 AbortController 也安全返回 NOOP，
 * {@code isCancelled()} 永远 false，避免 NPE。对齐 CC "default value"。
 *
 * @see ToolUseContext
 * @since R28
 */
public final class AbortController {

    private static final Logger log = LoggerFactory.getLogger(AbortController.class);

    /**
     * NOOP 单例 — 永不取消。对齐 CC default AbortSignal。
     */
    public static final AbortController NOOP = new AbortController();

    private final java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * R32-#25: 取消原因 · 对齐 CC {@code AbortSignal.reason}。
     *
     * <p>首个非 null reason 胜出（compareAndSet(null, newReason)），保证幂等语义下 reason 唯一。
     * 未取消或 abort 时未传 reason → {@code null}。NOOP 单例永远为 {@code null}。
     *
     * <p>为何用 {@link java.util.concurrent.atomic.AtomicReference}：
     * {@code cancelled.compareAndSet} 与 {@code reason} 写入之间存在 race，
     * 用 AtomicReference 让首个 reason 写入原子化，无需额外锁。
     */
    private final java.util.concurrent.atomic.AtomicReference<String> reason =
        new java.util.concurrent.atomic.AtomicReference<>(null);
    private final java.util.List<Consumer<AbortController>> listeners =
        new CopyOnWriteArrayList<>();

    public AbortController() {
    }

    /**
     * 触发取消（不带 reason）· 对齐 CC {@code abortController.abort()}。
     *
     * <p>委派 {@link #abort(String) abort(null)} 保持单一取消路径。
     *
     * <p><b>NOOP 例外</b>: 若 {@code this == NOOP},no-op 立即返回,不修改任何状态,
     * 不触发 listener。对齐 CC 默认 AbortSignal 永远不取消的语义。
     */
    public void abort() {
        if (this == NOOP) return; // NOOP 单例永不取消
        abort(null);
    }

    /**
     * R32-#25: 触发取消 + 携带 reason · 对齐 CC {@code abortController.abort(reason)}。
     *
     * <p>幂等：多次调用仅第一次触发 listeners,首个非 null reason 胜出。
     *
     * <p><b>NOOP 例外</b>: 若 {@code this == NOOP},no-op 立即返回。
     *
     * <p>用例：
     * <ul>
     *   <li>{@code abort("interrupt")} — 用户发新消息打断</li>
     *   <li>{@code abort("sibling_error")} — Bash 错误连带取消兄弟</li>
     *   <li>{@code abort("permission_denied")} — 权限拒绝</li>
     *   <li>{@code abort("streaming_fallback")} — 流式 fallback</li>
     * </ul>
     *
     * @param newReason 取消原因（可为 null · 等价于无参 {@link #abort()}）
     */
    public void abort(String newReason) {
        if (this == NOOP) return; // NOOP 单例永不取消
        if (cancelled.compareAndSet(false, true)) {
            // 首个非 null reason 胜出: 后续 abort 调用即使带 reason 也不会覆盖首个
            // (对齐 CC Web AbortSignal 行为 — 首个 abort 决定 signal.reason)
            reason.compareAndSet(null, newReason);
            for (Consumer<AbortController> listener : listeners) {
                try {
                    listener.accept(this);
                } catch (Exception e) {
                    // listener 异常不影响其他 listener
                }
            }
        }
    }

    /**
     * 是否已取消。对齐 CC {@code signal.aborted}。
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * R32-#25: 获取取消原因 · 对齐 CC {@code signal.reason}。
     *
     * <p>返回值：
     * <ul>
     *   <li>{@code null} — 未取消、或 {@link #abort()}（无参）调用</li>
     *   <li>字符串 — 调用 {@link #abort(String)} 时传入的 reason</li>
     * </ul>
     *
     * <p>NOOP 单例永远返回 {@code null}（永不取消）。
     */
    public String reason() {
        return reason.get();
    }

    /**
     * 注册取消 listener（对齐 CC addEventListener + once:true 简化版）。
     *
     * <p>本实现不保证 once（CC addEventListener + once:true）；多次调用 listener 会触发多次。
     * 实际使用中 listener 通常检查状态避免重复处理。
     *
     * @param listener 取消回调
     */
    public void onCancel(Consumer<AbortController> listener) {
        if (listener == null) return;
        if (cancelled.get()) {
            listener.accept(this); // 已取消 → 立即触发
        } else {
            listeners.add(listener);
        }
    }

    /**
     * 移除取消 listener · 对齐 CC {@code removeEventListener('abort', listener)}
     * （combinedAbortSignal.ts:40-44 / execAgentHook.ts:229-230/:305-306 /
     * execPromptHook.ts:102/:184 / execHttpHook.ts:219/:232）.
     *
     * <p>WHY: {@link #onCancel} 注册的 listener 在 {@link CopyOnWriteArrayList} 持久累积，
     * 长会话多次 exec 会残留父 abort 回调（内存泄漏 + 取消风暴）。CC 的 cleanup 在
     * 正常/异常两路径均移除父 signal 监听器，Java 以本方法配对（幂等：未注册的
     * listener 移除是 no-op，CopyOnWriteArrayList.remove 语义）。
     *
     * @param listener 之前经 {@link #onCancel} 注册的回调；null → no-op
     */
    public void removeOnCancel(Consumer<AbortController> listener) {
        if (listener == null) {
            return;
        }
        listeners.remove(listener);
    }

    /**
     * 当前注册的 listener 数 · 测试断言用（E8 父监听器清理验证）。
     *
     * <p>跨包测试（permission.hook 域）断言父 abort 监听器清理，故 public。
     */
    public int listenerCount() {
        return listeners.size();
    }

    /**
     * [R32-#29] 创建 child AbortController · 对齐 CC {@code utils/abortController.ts:67-95 createChildAbortController}.
     *
     * <p>child 是独立的 controller. parent abort 时 child 立即 abort(reason 透传).
     * child abort 时不影响 parent(单向级联).
     *
     * <p>Java 简化: 使用 {@link #onCancel} 监听 parent 取消事件; 不使用 CC 的 WeakRef
     * 模式(Java GC 模型不同). listener 捕获 parent reference, parent 取消时调
     * {@code child.abort(parent.reason())}.
     *
     * <p>NOOP 例外: parent 是 NOOP 单例时, child 也是普通 controller(永不被 parent 取消,
     * 因为 NOOP.abort() 永远 early-return). child 自己仍可正常被取消.
     */
    public AbortController createChild() {
        AbortController child = new AbortController();
        // NOOP 例外: parent 是 NOOP 单例时, 不注册 listener; child 由调用方按需管理
        if (this == NOOP) {
            log.debug("AbortController.createChild: parent is NOOP, child is independent");
            return child;
        }
        // Fast path: parent 已经 abort, 立即 abort child(reason 透传)
        if (cancelled.get()) {
            child.abort(this.reason.get());
            return child;
        }
        // 注册 listener: parent 取消时, child 跟随 abort
        this.onCancel(parent -> {
            if (!child.isCancelled()) {
                child.abort(parent.reason());
            }
        });
        log.debug("AbortController.createChild: registered propagate listener (parent reason={})",
            this.reason.get());
        return child;
    }

    /**
     * 重置（测试用，谨慎调用 — 会清除所有 listener）。
     */
    void reset() {
        cancelled.set(false);
        reason.set(null);
        listeners.clear();
    }
}
