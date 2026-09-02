package com.nexusai.application.agent.policy;

import java.util.List;
import java.util.Optional;

/**
 * Tip 调度器 · 对齐 CC services/tips/tipScheduler.ts.
 *
 * <p>L1 语义: 给定 available tips, 选择 sessions-since-last-shown 最久的 (CC selectTipWithLongestTimeSinceShown).
 *            配合 {@link TipHistoryTracker} 查询 sessions.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `selectWithLongestTimeSinceShown(List&lt;Tip&gt;, TipHistoryTracker) → Optional&lt;Tip&gt;`</li>
 *   <li><b>A2 Golden Trace</b>: 0 个 tip → Optional.empty(); 1 个 tip → 直接返回; 多 tip → sessionsSinceLastShown 最大者</li>
 *   <li><b>A3</b>: 纯函数; 多次调用结果稳定 (依赖 TipHistoryTracker 状态)</li>
 *   <li><b>A4</b>: never shown (MAX_VALUE) 自然成为 sessions 最大者优先选; tie 时取首项</li>
 *   <li><b>A5</b>: 真实 3 tip 调度 — {rotate-model: 5, fast-mode: 0, auto-mode: 100} → auto-mode (100 最大)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Optional&lt;Tip&gt; 替代 TS `undefined`; Comparator 链式表达排序.
 */
public final class TipScheduler {

    /** 单个 tip 数据 (CC Tip 简化版). */
    public record Tip(String id, int cooldownSessions) {}

    private TipScheduler() {}

    /**
     * 选 sessions-since-last-shown 最久的 tip.
     *
     * @param tips     候选 tip 列表
     * @param history  history tracker 用于查 sessionsSinceLastShown
     * @return Optional.of(选中的 tip); tips 为空 → Optional.empty()
     */
    public static Optional<Tip> selectWithLongestTimeSinceShown(List<Tip> tips, TipHistoryTracker history) {
        if (tips == null || tips.isEmpty()) return Optional.empty();
        if (tips.size() == 1) return Optional.of(tips.get(0));
        return tips.stream()
            .max((a, b) -> Long.compare(
                history.sessionsSinceLastShown(a.id()),
                history.sessionsSinceLastShown(b.id())));
    }
}