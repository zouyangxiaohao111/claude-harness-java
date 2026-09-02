package com.nexusai.application.agent.permission.hook;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Hook 事件总线 · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/utils/hooks/hookEvents.ts} (192 行全文).
 *
 * <p><b>WHY (Session H10)</b>: async hook 的完成信号 (response) 与进度信号 (progress) 需要
 * 独立于主消息流广播给下游 (UI/SDK) — CC 的 hookEvents.ts 提供独立事件系统
 * (模块注释 :1-7: "generic event system separate from the main message stream").
 * 主 agent 决策: Java 端保留 100 条 pending buffer + 白名单 (H10-2), 供 handler
 * 注册前缓冲、注册后回放.
 *
 * <p><b>与 CC 的映射</b> (hookEvents.ts):
 * <ul>
 *   <li>3 种事件 ( :22-49) → sealed interface {@link HookExecutionEvent} + 3 record
 *       (CC {@code type: 'started'|'progress'|'response'} 判别字段 → Java sealed 子类型,
 *       instanceof 即判别, 对齐本仓库 HookJSONOutput 的同类做法)</li>
 *   <li>{@code pendingEvents} ( :57) → {@link #pendingEvents} (ArrayDeque, 上限
 *       {@link #MAX_PENDING_EVENTS} = 100, :20)</li>
 *   <li>{@code eventHandler} ( :58) → volatile {@link #eventHandler}</li>
 *   <li>{@code allHookEventsEnabled} ( :59) → volatile boolean</li>
 *   <li>{@code ALWAYS_EMITTED_HOOK_EVENTS} ( :18) → {@link #ALWAYS_EMITTED_HOOK_EVENTS}
 *       = ['SessionStart', 'Setup']</li>
 *   <li>{@code HOOK_EVENTS} (coreTypes.ts:25-52) → {@link HookEventType#ccEventNames()}
 *       27 项白名单</li>
 *   <li>{@code startHookProgressInterval} ( :124-151) 的 {@code setInterval().unref()}
 *       ( :148) → daemon {@link ScheduledThreadPoolExecutor} (不阻止 JVM 退出)</li>
 * </ul>
 *
 * <p><b>同步化</b>: CC 单线程, Java 多线程 (轮询线程/执行器线程并发 emit) → buffer 操作
 * 用 {@code synchronized (bufferLock)}; handler 引用 volatile 读, 在锁外调用
 * (防 handler 阻塞拖死 emit 方, 同时避免锁内回调重入死锁).
 *
 * <p><b>日志</b>: slf4j + 中文, debug 用 {@code if (log.isDebugEnabled())} 包裹.
 *
 * @see AsyncHookRegistry
 * @since Session H10
 */
@Component
public class HookEventBus {

    private static final Logger log = LoggerFactory.getLogger(HookEventBus.class);

    /**
     * 恒发事件 · 对齐 CC {@code ALWAYS_EMITTED_HOOK_EVENTS} (hookEvents.ts:18)
     * {@code ['SessionStart', 'Setup']} — 原始 allowlist 的低噪声生命周期事件,
     * 不随 includeHookEvents 开关变化.
     */
    public static final Set<String> ALWAYS_EMITTED_HOOK_EVENTS = Set.of("SessionStart", "Setup");

    /** 事件 buffer 上限 · 对齐 CC {@code MAX_PENDING_EVENTS = 100} (hookEvents.ts:20). */
    public static final int MAX_PENDING_EVENTS = 100;

    /** 进度定时器默认间隔 · 对齐 CC {@code params.intervalMs ?? 1000} (hookEvents.ts:147). */
    public static final long DEFAULT_PROGRESS_INTERVAL_MS = 1000L;

    /**
     * response 事件 outcome · 对齐 CC 字面量联合 {@code 'success' | 'error' | 'cancelled'}
     * (hookEvents.ts:48, AsyncHookRegistry.ts:94).
     *
     * <p>WHY (规则五): outcome 是确定性枚举 (3 值), 用 enum 而非裸 String —
     * 编译期钉死取值, 防 'sucess' 之类笔误; ccName() 保留 CC 原文供序列化.
     */
    public enum HookOutcome {
        SUCCESS("success"),
        ERROR("error"),
        CANCELLED("cancelled");

        private final String ccName;

        HookOutcome(String ccName) { this.ccName = ccName; }

        /** CC 原文 ('success'|'error'|'cancelled') · 供日志/序列化. */
        public String ccName() { return ccName; }
    }

    /**
     * hook 执行事件 · 对齐 CC {@code HookExecutionEvent} (hookEvents.ts:51-54)
     * 3 分支 union.
     *
     * <p>CC {@code type} 判别字段 ('started'|'progress'|'response') → Java sealed 子类型:
     * {@link HookStartedEvent} / {@link HookProgressEvent} / {@link HookResponseEvent}.
     */
    public sealed interface HookExecutionEvent
        permits HookStartedEvent, HookProgressEvent, HookResponseEvent {
    }

    /**
     * started 事件 · 对齐 CC {@code HookStartedEvent} (hookEvents.ts:22-27):
     * {@code {type:'started', hookId, hookName, hookEvent}}.
     */
    public record HookStartedEvent(String hookId, String hookName, String hookEvent)
        implements HookExecutionEvent {
    }

    /**
     * progress 事件 · 对齐 CC {@code HookProgressEvent} (hookEvents.ts:29-37):
     * {@code {type:'progress', hookId, hookName, hookEvent, stdout, stderr, output}}.
     *
     * <p>[hooks_v3 决策 2-4 / D-WF5-06 · X-WF5-01 WF1-X3] 9 字段超集<b>回缩为 CC 六字段</b>:
     * 旧实现把 command/promptText/statusMessage (对齐 CC hook_progress 消息载荷
     * hooks.ts:2094-2116) 并入事件总线 record — 这融合了 CC <b>两条独立概念</b>:
     * <ul>
     *   <li><b>事件总线</b> HookProgressEvent (hookEvents.ts:29-37): 只承载
     *       stdout/stderr/output (async 轮询通道字段, I4 佐证)</li>
     *   <li><b>消息流</b> hook_progress 消息载荷 (hooks.ts:2094-2116
     *       {@code data:{type:'hook_progress', hookEvent, hookName, command, promptText?, statusMessage?}}):
     *       独立消息类型, 经工具流消息发出, 不属事件总线</li>
     * </ul>
     * 本 record 回缩为 CC 六字段; command/promptText/statusMessage 迁往消息流通道
     * (HookRegistry 按 {@code H-WF5a-patch-note.md} 合并阶段迁移, 前端接线随决策 0-4).
     */
    public record HookProgressEvent(String hookId, String hookName, String hookEvent,
                                    String stdout, String stderr, String output)
        implements HookExecutionEvent {
    }

    /** emitHookProgress 数据对象 · 对齐 CC :108-122 参数对象 (六字段). */
    public record HookProgressData(String hookId, String hookName, String hookEvent,
                                   String stdout, String stderr, String output) {
    }

    /**
     * response 事件 · 对齐 CC {@code HookResponseEvent} (hookEvents.ts:39-49):
     * {@code {type:'response', hookId, hookName, hookEvent, output, stdout, stderr, exitCode?, outcome}}.
     */
    public record HookResponseEvent(String hookId, String hookName, String hookEvent,
                                    String output, String stdout, String stderr,
                                    Integer exitCode, HookOutcome outcome)
        implements HookExecutionEvent {
    }

    /** emitHookResponse 数据对象 · 对齐 CC :153-162 参数对象. */
    public record HookResponseData(String hookId, String hookName, String hookEvent,
                                   String output, String stdout, String stderr,
                                   Integer exitCode, HookOutcome outcome) {
    }

    /** 进度定时器 getOutput 返回结构 · 对齐 CC :128 {@code {stdout, stderr, output}}. */
    public record HookProgressOutput(String stdout, String stderr, String output) {
    }

    /** pending buffer · 对齐 CC pendingEvents (:57); 上限 MAX_PENDING_EVENTS, 满则丢最旧 (:77-79). */
    private final ArrayDeque<HookExecutionEvent> pendingEvents = new ArrayDeque<>();
    private final Object bufferLock = new Object();

    /** 事件 handler · 对齐 CC eventHandler (:58); volatile + 锁外调用. */
    private volatile Consumer<HookExecutionEvent> eventHandler;

    /** 全量事件开关 · 对齐 CC allHookEventsEnabled (:59); SDK includeHookEvents / remote 模式开启. */
    private volatile boolean allHookEventsEnabled;

    /** 进度定时器共享调度器 · daemon (对齐 CC interval.unref(), :148). */
    private final ScheduledExecutorService progressScheduler;

    public HookEventBus() {
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "nexusai-hook-progress");
            t.setDaemon(true);
            return t;
        });
        this.progressScheduler = ex;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 注册 / 缓冲 (CC :57-81)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 注册事件 handler · 对齐 CC {@code registerHookEventHandler} (hookEvents.ts:61-70).
     *
     * <p>注册后立即回放 pending buffer (CC {@code pendingEvents.splice(0)} 全量按序
     * 交给新 handler, :66-68) — 先执行后订阅场景的事件不丢失.
     *
     * <p><b>null 注册语义 (D-08, CC :64-69)</b>: {@code eventHandler = handler} 无条件执行,
     * 但 {@code if (handler && pendingEvents.length > 0)} 的 splice 回放仅在 handler 非 null
     * 时执行 — null 注销只置空 handler, <b>pending buffer 保留</b>; 后续再注册非 null handler
     * 时, 注销期间缓冲的全部事件仍按序回放. buffer 只由 {@link #clearHookEventState} 清空
     * (CC :188-192).
     *
     * @param handler 事件消费者; null = 取消注册 (CC handler 可为 null, :62; 不触碰 buffer)
     */
    public void registerHookEventHandler(Consumer<HookExecutionEvent> handler) {
        List<HookExecutionEvent> replay;
        int buffered;
        synchronized (bufferLock) {
            eventHandler = handler;
            // D-08 (CC :65): splice(0) 仅在 handler 非 null 且 buffer 非空时执行 —
            // null 分支保留 buffer, 不执行 clear
            if (handler != null && !pendingEvents.isEmpty()) {
                replay = new ArrayList<>(pendingEvents);
                pendingEvents.clear();
            } else {
                replay = List.of();
            }
            buffered = pendingEvents.size();
        }
        for (HookExecutionEvent event : replay) {
            handler.accept(event);
        }
        if (log.isDebugEnabled()) {
            if (handler != null) {
                log.debug("HOOK 事件 handler 已注册, 回放 {} 条 pending 事件", replay.size());
            } else {
                log.debug("HOOK 事件 handler 已注销, pending buffer 保留 {} 条", buffered);
            }
        }
    }

    /**
     * 内部 emit · 对齐 CC {@code emit} (hookEvents.ts:72-81).
     *
     * <p>有 handler 直接投递; 否则进 buffer, 超 {@link #MAX_PENDING_EVENTS} 丢最旧
     * (CC :77-79 {@code shift()}).
     */
    private void emit(HookExecutionEvent event) {
        Consumer<HookExecutionEvent> handler = eventHandler;
        if (handler != null) {
            handler.accept(event);
            return;
        }
        synchronized (bufferLock) {
            pendingEvents.addLast(event);
            if (pendingEvents.size() > MAX_PENDING_EVENTS) {
                pendingEvents.removeFirst();
                if (log.isDebugEnabled()) {
                    log.debug("HOOK 事件 buffer 超 {} 条, 丢弃最旧事件", MAX_PENDING_EVENTS);
                }
            }
        }
    }

    /**
     * 白名单判定 · 对齐 CC {@code shouldEmit} (hookEvents.ts:83-91).
     *
     * <p>ALWAYS_EMITTED (SessionStart/Setup) 恒发; 其余事件需
     * {@code allHookEventsEnabled && HOOK_EVENTS.includes(hookEvent)} — 白名单是默认
     * 降噪开关, 不是能力上限.
     *
     * @param hookEvent CC 事件名 (PascalCase, 如 "PreToolUse")
     */
    private boolean shouldEmit(String hookEvent) {
        if (ALWAYS_EMITTED_HOOK_EVENTS.contains(hookEvent)) {
            return true;
        }
        return allHookEventsEnabled && HookEventType.ccEventNames().contains(hookEvent);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3 个 emit 入口 (CC :93-177)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * started 事件 · 对齐 CC {@code emitHookStarted} (hookEvents.ts:93-106).
     *
     * <p>shouldEmit 不过 → 直接 return, <b>不进 buffer</b> (CC :98) — 白名单外事件
     * 不能占用 100 条 buffer 容量.
     *
     * @param hookId   CC original: hookId (hookEvents.ts:94); 单次执行的关联标识
     * @param hookName CC original: hookName (hookEvents.ts:95)
     * @param hookEvent CC original: hookEvent (hookEvents.ts:96); CC 事件名 (PascalCase)
     */
    public void emitHookStarted(String hookId, String hookName, String hookEvent) {
        if (!shouldEmit(hookEvent)) {
            return;
        }
        emit(new HookStartedEvent(hookId, hookName, hookEvent));
        if (log.isDebugEnabled()) {
            log.debug("HOOK 事件 started: hook={} event={}", hookName, hookEvent);
        }
    }

    /**
     * progress 事件 · 对齐 CC {@code emitHookProgress} (hookEvents.ts:108-122).
     *
     * <p>shouldEmit 不过 → 直接 return 不进 buffer (CC :116).
     *
     * @param data CC original: {hookId, hookName, hookEvent, stdout, stderr, output} (:108-114)
     */
    public void emitHookProgress(HookProgressData data) {
        if (!shouldEmit(data.hookEvent())) {
            return;
        }
        emit(new HookProgressEvent(data.hookId(), data.hookName(), data.hookEvent(),
            data.stdout(), data.stderr(), data.output()));
    }

    /**
     * 启动进度定时器 · 对齐 CC {@code startHookProgressInterval} (hookEvents.ts:124-151).
     *
     * <p>语义 (CC :131-150):
     * <ul>
     *   <li>shouldEmit 不过 → 返回 no-op stop (CC :131 {@code return () => {}})</li>
     *   <li>每次 tick 调 getOutput; output 与上次相同 → 不 emit (去重, :136-137)</li>
     *   <li>返回 stop = 取消定时器 (CC :150 {@code clearInterval})</li>
     * </ul>
     *
     * <p>Java 用共享 daemon 调度器 (CC setInterval().unref() 的等价, :148), 每个
     * interval 一个 {@link ScheduledFuture}.
     *
     * @param hookId     CC original: hookId (hookEvents.ts:125)
     * @param hookName   CC original: hookName (hookEvents.ts:126)
     * @param hookEvent  CC original: hookEvent (hookEvents.ts:127)
     * @param getOutput  CC original: getOutput (hookEvents.ts:128); 取当前 stdout/stderr/output
     * @return stop 回调 · 调用后停止该 interval
     */
    public Runnable startHookProgressInterval(String hookId, String hookName, String hookEvent,
                                              Supplier<HookProgressOutput> getOutput) {
        return startHookProgressInterval(hookId, hookName, hookEvent, getOutput,
            DEFAULT_PROGRESS_INTERVAL_MS);
    }

    /**
     * 启动进度定时器 (显式间隔) · 测试用 intervalMs 覆盖默认 1000ms.
     *
     * @param intervalMs CC original: intervalMs (hookEvents.ts:129); optional, 默认 1000
     */
    public Runnable startHookProgressInterval(String hookId, String hookName, String hookEvent,
                                              Supplier<HookProgressOutput> getOutput, long intervalMs) {
        if (!shouldEmit(hookEvent)) {
            // CC :131 shouldEmit 不过 → no-op stop
            if (log.isDebugEnabled()) {
                log.debug("HOOK 事件 {} 白名单不过, 进度定时器不启动", hookEvent);
            }
            return () -> {
            };
        }
        AtomicReference<String> lastEmittedOutput = new AtomicReference<>("");
        ScheduledFuture<?> future = progressScheduler.scheduleAtFixedRate(() -> {
            try {
                HookProgressOutput out = getOutput.get();
                String output = out.output() != null ? out.output() : "";
                if (output.equals(lastEmittedOutput.get())) {
                    return; // CC :136-137 相同输出不重复 emit
                }
                lastEmittedOutput.set(output);
                emitHookProgress(new HookProgressData(hookId, hookName, hookEvent,
                    out.stdout(), out.stderr(), output));
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("HOOK 进度定时器 tick 异常 (hook={}): {}", hookName, e.toString());
                }
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        if (log.isDebugEnabled()) {
            log.debug("HOOK 进度定时器已启动 (hook={} event={} interval={}ms)", hookName, hookEvent, intervalMs);
        }
        return () -> future.cancel(false);
    }

    /**
     * response 事件 · 对齐 CC {@code emitHookResponse} (hookEvents.ts:153-177).
     *
     * <p>CC :163-169 先全文记录输出 (outputToLog = stdout || stderr || output) 再
     * shouldEmit → emit — 输出全文必进 debug 日志, 不因白名单过滤而丢失可观测性.
     *
     * @param data CC original: {hookId, hookName, hookEvent, output, stdout, stderr, exitCode?, outcome}
     *             (hookEvents.ts:153-162)
     */
    public void emitHookResponse(HookResponseData data) {
        // CC :164 outputToLog = data.stdout || data.stderr || data.output (JS truthiness)
        String outputToLog = (data.stdout() != null && !data.stdout().isEmpty()) ? data.stdout()
            : (data.stderr() != null && !data.stderr().isEmpty()) ? data.stderr()
            : data.output();
        if (outputToLog != null && !outputToLog.isEmpty() && log.isDebugEnabled()) {
            log.debug("HOOK {} ({}) {}:\n{}", data.hookName(), data.hookEvent(),
                data.outcome().ccName(), outputToLog);
        }
        if (!shouldEmit(data.hookEvent())) {
            return; // CC :171
        }
        emit(new HookResponseEvent(data.hookId(), data.hookName(), data.hookEvent(),
            data.output(), data.stdout(), data.stderr(), data.exitCode(), data.outcome()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 开关 / 清理 (CC :184-192)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 全量事件开关 · 对齐 CC {@code setAllHookEventsEnabled} (hookEvents.ts:184-186).
     *
     * <p>WHY: SDK {@code includeHookEvents} 选项或 CLAUDE_CODE_REMOTE 模式需要放开
     * SessionStart/Setup 之外的全部事件 (CC :179-183 注释).
     *
     * @param enabled true=白名单外事件也 emit
     */
    public void setAllHookEventsEnabled(boolean enabled) {
        this.allHookEventsEnabled = enabled;
        if (log.isDebugEnabled()) {
            log.debug("HOOK 全量事件开关 → {}", enabled);
        }
    }

    /**
     * 清空事件状态 · 对齐 CC {@code clearHookEventState} (hookEvents.ts:188-192):
     * handler=null, buffer 清空, allHookEventsEnabled=false.
     *
     * <p>WHY: 测试/会话重置需要回到初始状态, 否则上一个 handler 持续接收事件.
     */
    public void clearHookEventState() {
        synchronized (bufferLock) {
            eventHandler = null;
            pendingEvents.clear();
            allHookEventsEnabled = false;
        }
        if (log.isDebugEnabled()) {
            log.debug("HOOK 事件状态已清空 (handler/buffer/开关)");
        }
    }

    /**
     * Spring 销毁收尾 · 停掉进度调度器 (daemon 线程不阻止 JVM 退出, 但显式 shutdown
     * 避免泄漏; 各 interval 的 stop 由 registry 的 clearAllAsyncHooks/finalize 负责).
     */
    @PreDestroy
    public void shutdown() {
        progressScheduler.shutdownNow();
        if (log.isInfoEnabled()) {
            log.info("HookEventBus 销毁: 进度调度器已关闭");
        }
    }
}
