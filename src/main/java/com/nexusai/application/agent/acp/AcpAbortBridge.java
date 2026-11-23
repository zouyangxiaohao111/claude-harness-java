package com.nexusai.application.agent.acp;

import com.nexusai.application.agent.tool.AbortController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 父轮取消 → ACP 子进程收尾的桥。
 *
 * <p><b>为什么需要</b>：{@code POST /sessions/{id}/cancel} 只 abort 父轮，
 * ACP prompt 是阻塞在工具线程里的同步请求；没有本桥时子进程会继续跑完整轮。
 *
 * <p><b>收尾阶梯</b>：① 发 ACP {@code session/cancel}（通知，尽力）→
 * ② {@link AcpProcess#quiesce}（关 stdin 让对端处理 cancel 后自行退出 → 宽限 → 杀树）。
 * 关 stdin 同时让 {@link AcpTransport} 读到 EOF，把在飞的 {@code session/prompt} future
 * 立即失败 —— 父轮不必等到超时。
 *
 * <p>只触发一次（幂等）；父 controller 已取消时 {@link #bind()} 会立即执行。
 */
final class AcpAbortBridge {

    private static final Logger log = LoggerFactory.getLogger(AcpAbortBridge.class);

    /** 发 cancel 后给对端处理并退出的宽限（超过就杀树）。 */
    private static final long ABORT_EOF_GRACE_MS = 1_500L;

    private final AbortController parent;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final java.util.function.Consumer<AbortController> listener = this::onParentCancel;

    private volatile AcpClient client;
    private volatile AcpProcess.Started started;

    AcpAbortBridge(AbortController parent) {
        this.parent = parent;
    }

    /** 注册父取消监听（父已取消则立即执行一次）。 */
    void bind() {
        if (parent != null) {
            parent.onCancel(listener);
        }
    }

    /** 绑定当前子进程/客户端；若已取消，立即执行收尾。 */
    void attach(AcpClient client, AcpProcess.Started started) {
        this.client = client;
        this.started = started;
        if (cancelled.get()) {
            onParentCancel(null);
        }
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    /** 释放监听（必须与 {@link #bind()} 配对，避免长会话累积）。 */
    void unbind() {
        if (parent != null) {
            parent.removeOnCancel(listener);
        }
    }

    private void onParentCancel(AbortController ignored) {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        log.warn("[AcpAbortBridge] 父轮取消 → 发送 ACP session/cancel 并终止子进程树");
        AcpClient c = this.client;
        if (c != null) {
            try {
                c.cancel();
            } catch (RuntimeException e) {
                log.debug("[AcpAbortBridge] session/cancel 发送失败（进程可能已退出）: {}", e.getMessage());
            }
        }
        AcpProcess.Started s = this.started;
        if (s != null) {
            AcpProcess.quiesce(s.process(), ABORT_EOF_GRACE_MS);
        }
    }
}
