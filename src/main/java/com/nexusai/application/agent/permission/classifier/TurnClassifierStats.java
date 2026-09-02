package com.nexusai.application.agent.permission.classifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 回合分类器耗时统计 · 对齐 CC {@code addToTurnClassifierDuration}
 * （Open-ClaudeCode/src/bootstrap/state.ts:627-630 + permissions.ts:814-816）。
 *
 * <p>CC 真源语义：
 * <pre>{@code
 * export function addToTurnClassifierDuration(duration: number): void {
 *   STATE.turnClassifierDurationMs += duration
 *   STATE.turnClassifierCount++
 * }
 * export function getTurnClassifierDurationMs(): number { return STATE.turnClassifierDurationMs }
 * export function getTurnClassifierCount(): number { return STATE.turnClassifierCount }
 * export function resetTurnClassifierDuration(): void { STATE.turnClassifierDurationMs = 0; STATE.turnClassifierCount = 0 }
 * }</pre>
 *
 * <p>CC 在 auto-mode 分类器每次调用后累计耗时（permissions.ts:814-816
 * {@code if (classifierResult.durationMs !== undefined) { addToTurnClassifierDuration(classifierResult.durationMs) }}），
 * 供回合末状态行展示「分类器 overhead」（REPL.tsx:2830-2831）。web 后端无 REPL 状态行，
 * 但「回合级分类器耗时累计」是遥测维度（OPD-WF3-01-16 拍板：补耗时遥测）。
 *
 * <p><b>Java 架构差异（多会话）</b>: CC {@code STATE} 是单进程全局（CLI 单会话）；web 后端
 * 多会话并发，进程级单例会混会话，故以 {@code sessionId} 分桶（{@link ConcurrentMap}）。
 * 回合边界（reset）由宿主 agent 循环在回合开始调用 {@link #reset(String)}，不在权限域内
 * （权限管线无回合概念）；消费（overhead 遥测/状态行）同理归 agent 循环侧 —— 本次仅补
 * 累计侧（CC :814-816 等价）。
 *
 * @param add     累计一次分类器耗时（CC addToTurnClassifierDuration）
 * @param getDurationMs 取会话回合分类器累计耗时（CC getTurnClassifierDurationMs）
 * @param getCount 取会话回合分类器调用次数（CC getTurnClassifierCount）
 * @param reset    会话回合开始重置（CC resetTurnClassifierDuration）
 */
@Component
public final class TurnClassifierStats {

    private static final Logger log = LoggerFactory.getLogger(TurnClassifierStats.class);

    /** 会话级可变累计器（per-session 分桶，防多会话互串）。 */
    // [session-id-short] 键空间 UUID→String（short 形态 sess-xxx）
    private final ConcurrentMap<String, Mutable> perSession = new ConcurrentHashMap<>();

    /** 会话级可变累计状态（durationMs + count 原子更新于 compute）。 */
    private static final class Mutable {
        long durationMs;
        int count;
    }

    /**
     * 累计一次分类器耗时 · 对齐 CC {@code addToTurnClassifierDuration}
     * （state.ts:627-630：durationMs += duration; count++）。
     *
     * @param sessionId 会话 ID（short；null → 忽略，无法归因）
     * @param durationMs 本次分类器调用耗时（CC durationMs；负数防御性忽略）
     */
    public void add(String sessionId, long durationMs) {
        if (sessionId == null || durationMs < 0) {
            return;
        }
        perSession.compute(sessionId, (k, m) -> {
            if (m == null) {
                m = new Mutable();
            }
            m.durationMs += durationMs;
            m.count++;
            return m;
        });
        if (log.isDebugEnabled()) {
            log.debug("TurnClassifierStats: 累计分类器耗时 sessionId={} durationMs={} 累计={} 次数={}",
                sessionId, durationMs, getDurationMs(sessionId), getCount(sessionId));
        }
    }

    /**
     * 取会话回合分类器累计耗时 · 对齐 CC {@code getTurnClassifierDurationMs}（state.ts:623-625）。
     *
     * @param sessionId 会话 ID（short；null → 0）
     * @return 累计耗时 ms；未累计 → 0
     */
    public long getDurationMs(String sessionId) {
        Mutable m = sessionId == null ? null : perSession.get(sessionId);
        return m != null ? m.durationMs : 0L;
    }

    /**
     * 取会话回合分类器调用次数 · 对齐 CC {@code getTurnClassifierCount}（state.ts:637-639）。
     *
     * @param sessionId 会话 ID（short；null → 0）
     * @return 累计次数；未累计 → 0
     */
    public int getCount(String sessionId) {
        Mutable m = sessionId == null ? null : perSession.get(sessionId);
        return m != null ? m.count : 0;
    }

    /**
     * 会话回合开始重置 · 对齐 CC {@code resetTurnClassifierDuration}（state.ts:632-635）。
     *
     * @param sessionId 会话 ID（short；null → 忽略）
     */
    public void reset(String sessionId) {
        if (sessionId != null) {
            perSession.remove(sessionId);
        }
    }
}
