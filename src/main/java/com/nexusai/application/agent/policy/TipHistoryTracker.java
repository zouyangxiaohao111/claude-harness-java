package com.nexusai.application.agent.policy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Tip 显示历史记录 · 对齐 CC services/tips/tipHistory.ts recordTipShown / getSessionsSinceLastShown.
 *
 * <p>L1 语义: 记录某 tip 最近一次显示时的 startup 数, 用于决定下次显示时机 (避免短期内重复刷屏).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `recordShown(tipId)` / `sessionsSinceLastShown(tipId)` 双 API</li>
 *   <li><b>A2 Golden Trace</b>: record → sessions 返回 0; 下次 startup → sessions 返回 N - prevN</li>
 *   <li><b>A3</b>: 纯内存 + LongSupplier startupClock 注入; 同 tipId 同 startup record 二次无效</li>
 *   <li><b>A4</b>: 未 record 的 tip → {@link Long#MAX_VALUE} (CC Infinity 语义: "从未显示过")</li>
 *   <li><b>A5</b>: 真实用例 — tips "rotate-model" record at startup=5, startup=12 → sessionsSinceLast=7</li>
 * </ul>
 *
 * <p>L3 (Java idiom): `ConcurrentHashMap` 替代 TS object literal; `LongSupplier` startup 注入测试可控;
 *                    `Long.MAX_VALUE` 替代 `Infinity` (语义等价 — "从未显示过").
 */
public final class TipHistoryTracker {

    private final ConcurrentHashMap<String, Long> history = new ConcurrentHashMap<>();
    private final LongSupplier startupClock;

    public TipHistoryTracker() {
        this(System::currentTimeMillis);
    }

    /** 测试用: 注入 startup 计数器. */
    public TipHistoryTracker(LongSupplier startupClock) {
        this.startupClock = startupClock;
    }

    /**
     * 记录某 tip 在当前 startup 时被显示.
     * 同 startup 数二次记录同一 tipId → no-op (CC tipsHistory.ts:7 优化).
     */
    public void recordShown(String tipId) {
        if (tipId == null) return;
        long currentStartup = startupClock.getAsLong();
        history.merge(tipId, currentStartup, (oldVal, newVal) ->
            oldVal == newVal ? oldVal : newVal);
    }

    /**
     * 返回自上次显示以来经过的 startup 数.
     * 从未显示 → {@link Long#MAX_VALUE} (对齐 CC `Infinity`).
     */
    public long sessionsSinceLastShown(String tipId) {
        if (tipId == null) return Long.MAX_VALUE;
        Long lastShown = history.get(tipId);
        if (lastShown == null) return Long.MAX_VALUE;
        return startupClock.getAsLong() - lastShown;
    }

    /** 测试用: 当前 history 快照. */
    public Map<String, Long> snapshot() {
        return Map.copyOf(history);
    }

    /** 测试用: 清空历史. */
    public void clear() {
        history.clear();
    }
}