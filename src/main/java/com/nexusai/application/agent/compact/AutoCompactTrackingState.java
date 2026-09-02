package com.nexusai.application.agent.compact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 自动压缩跟踪状态 · 对齐 CC autoCompact.ts AutoCompactTrackingState
 *
 * <p>记录压缩的运行时状态，包括回合计数、熔断器等。
 *
 * <h2>CC 对齐</h2>
 * <p>对齐 CC autoCompact.ts:51-60:
 * <pre>
 * export type AutoCompactTrackingState = {
 *   compacted: boolean
 *   turnCounter: number
 *   turnId: string
 *   consecutiveFailures?: number
 * }
 * </pre>
 *
 * <h2>熔断器设计</h2>
 * <p>连续自动压缩失败超过 {@link CompactConstants#MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES} 次后，
 * 熔断器打开，停止尝试自动压缩。成功压缩时重置计数。
 *
 * <p>WHY: BQ 2026-03-10 发现单会话最高 3,272 次连续失败，每天浪费 ~250K API 调用。
 */
public class AutoCompactTrackingState {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactTrackingState.class);

    /** 本轮是否已经执行过压缩 */
    private boolean compacted;

    /** 回合计数器（每个 turn +1） */
    private int turnCounter;

    /** 唯一回合 ID（对齐 CC turnId: string） */
    private String turnId;

    /** 连续自动压缩失败次数（对齐 CC consecutiveFailures） */
    private int consecutiveFailures;

    /**
     * 创建新的跟踪状态
     */
    public AutoCompactTrackingState() {
        this.compacted = false;
        this.turnCounter = 0;
        this.turnId = UUID.randomUUID().toString();
        this.consecutiveFailures = 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 回合管理
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 回合末计数 · 对齐 CC query.ts:1523-1524 {@code if (tracking?.compacted) tracking.turnCounter++}
     *
     * <p>压缩成功后每经过一个回合 turnCounter +1（gate = {@link #isCompacted()}，由调用方
     * 判定，CC query.ts:1523 同构），供 tengu_post_autocompact_turn 遥测
     * （query.ts:1525-1533）与 recompactionInfo.turnsSincePreviousCompact（autoCompact.ts:281）。
     *
     * <p><b>IMP2-07（DRIFT-4/S-6）</b>：不再重置 {@code compacted}、不再轮换 {@code turnId}
     * ——CC 中 compacted 在同一 query() 调用内保持 true（isRecompactionInChain 持续，
     * autoCompact.ts:280），turnId 仅在压缩成功时轮换（query.ts:523，见 {@link #recordSuccess()}）。
     * 旧实现"compacted=false + turnId 重生成"为错误语义（S-6 根因之一）。
     */
    public void startNewTurn() {
        this.turnCounter++;

        if (log.isDebugEnabled()) {
            log.debug("[AutoCompactTracking] 回合末 turnCounter++ → {} (turnId={})", turnCounter, turnId);
        }
    }

    /**
     * 标记本轮已执行压缩
     */
    public void markCompacted() {
        this.compacted = true;
        if (log.isDebugEnabled()) {
            log.debug("[AutoCompactTracking] 回合 {} 已标记压缩", turnCounter);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 熔断器 · 对齐 CC consecutiveFailures
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 记录压缩失败 · 对齐 CC consecutiveFailures++（autoCompact.ts:341-342）。
     *
     * <p>每次自动压缩失败时调用。连续失败达到
     * {@link CompactConstants#MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES} 后熔断器打开。
     *
     * <p><b>USER_ABORT 也计入</b>（CC 实际源码 autoCompact.ts:341-342 无条件 +1；
     * :335-337 hasExactErrorMessage 仅门控 logError）。调用方（AutoCompactor）
     * 仅在 USER_ABORT 时跳过 error 日志，计数照常递增。
     *
     * <p><b>[V54 token-compact-settings-fix] 无参委托</b>：使用常量阈值，测试兼容。
     */
    public void recordFailure() {
        recordFailure(CompactConstants.MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES);
    }

    /**
     * 记录压缩失败（DB 实时阈值可传入）· 对齐 CC consecutiveFailures++（autoCompact.ts:341-342）。
     *
     * <p>每次自动压缩失败时调用。连续失败达到 {@code maxFailures} 后熔断器打开。
     *
     * <p><b>[V54 token-compact-settings-fix] 阈值参数化</b>：阈值可传 DB 实时值——
     * AutoCompactor 判门处用 {@code resolveMaxConsecutiveAutocompactFailures()}
     * （settings.max_consecutive_autocompact_failures 有值覆盖常量 3），消除常量门；
     * 无参委托传常量 {@link CompactConstants#MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES}。
     *
     * <p><b>USER_ABORT 也计入</b>（CC 实际源码 autoCompact.ts:341-342 无条件 +1；
     * :335-337 hasExactErrorMessage 仅门控 logError）。调用方（AutoCompactor）
     * 仅在 USER_ABORT 时跳过 error 日志，计数照常递增。
     *
     * @param maxFailures 连续失败熔断阈值（DB 实时值或常量默认）
     */
    public void recordFailure(int maxFailures) {
        this.consecutiveFailures++;
        log.warn("[AutoCompactTracking] 压缩失败: 连续失败 {} 次 (熔断阈值={})",
            consecutiveFailures, maxFailures);

        if (isCircuitBreakerOpen(maxFailures)) {
            log.error("[AutoCompactTracking] 熔断器已打开！连续 {} 次压缩失败（阈值={}），"
                + "停止自动压缩直至会话结束", consecutiveFailures, maxFailures);
        }
    }

    /**
     * 记录压缩成功 · 对齐 CC 成功时 tracking 全量复位（query.ts:521-526）
     *
     * <p>复位为 {@code {compacted:true, turnId:uuid(), turnCounter:0, consecutiveFailures:0}}：
     * <ul>
     *   <li>turnId 轮换（query.ts:523 deps.uuid()）——previousCompactTurnId 取最近一次压缩
     *       （autoCompact.ts:282，S-6/DRIFT-4 修复）；SM 与 legacy 成功路径同样轮换（T-3）</li>
     *   <li>turnCounter 归零（query.ts:524）——turnsSincePreviousCompact 从 0 重新计数</li>
     *   <li>compacted=true（query.ts:522）——后续回合 startNewTurn 计数开关</li>
     *   <li>consecutiveFailures 归零（query.ts:525），关闭熔断器</li>
     * </ul>
     */
    public void recordSuccess() {
        if (consecutiveFailures > 0) {
            log.info("[AutoCompactTracking] 压缩成功，重置连续失败计数（此前 {} 次）",
                consecutiveFailures);
        }
        this.consecutiveFailures = 0;
        // IMP2-07（DRIFT-4/S-6）: 成功轮换 turnId + 归零 turnCounter（CC query.ts:521-526）
        this.turnId = UUID.randomUUID().toString();
        this.turnCounter = 0;
        markCompacted();
        if (log.isDebugEnabled()) {
            log.debug("[AutoCompactTracking] 压缩成功复位: turnId={} turnCounter=0", turnId);
        }
    }

    /**
     * 熔断器是否打开 · 对齐 CC MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES 检查
     *
     * <p><b>[V54 token-compact-settings-fix] 无参委托</b>：使用常量阈值，测试兼容。
     *
     * @return true 表示应停止自动压缩
     */
    public boolean isCircuitBreakerOpen() {
        return isCircuitBreakerOpen(CompactConstants.MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES);
    }

    /**
     * 熔断器是否打开（DB 实时阈值可传入）· 对齐 CC MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES 检查
     *
     * <p><b>[V54 token-compact-settings-fix] 阈值参数化</b>：阈值可传 DB 实时值——
     * AutoCompactor 判门处用 {@code resolveMaxConsecutiveAutocompactFailures()}
     * （settings.max_consecutive_autocompact_failures 有值覆盖常量 3），消除常量门；
     * 无参委托传常量 {@link CompactConstants#MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES}。
     *
     * @param maxFailures 连续失败熔断阈值（DB 实时值或常量默认）
     * @return true 表示应停止自动压缩
     */
    public boolean isCircuitBreakerOpen(int maxFailures) {
        return consecutiveFailures >= maxFailures;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Getters
    // ════════════════════════════════════════════════════════════════════════

    public boolean isCompacted() {
        return compacted;
    }

    public int getTurnCounter() {
        return turnCounter;
    }

    public String getTurnId() {
        return turnId;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * 设置连续失败计数 · CC original: 调用方写回（query.ts:536-542
     * {@code tracking = {...(tracking ?? {compacted:false, turnId:'', turnCounter:0}), consecutiveFailures}}）。
     *
     * <p>[IMP-A4-3 · OPD-CM5-A-31] 失败传播返回值通道：autoCompactIfNeeded 失败路径不再内部
     * 直接写共享 tracking（旧 {@code recordFailure()}），而是经返回值承载 nextFailures，由调用方
     * （LlmAgentLoop / tryAutoCompact）调用本方法写回——熔断计数经返回值通道可跨 AutoCompactor
     * 实例传递。仅更新 consecutiveFailures，保留 compacted/turnId/turnCounter（对齐 CC 失败分支
     * 只覆盖 consecutiveFailures 的语义）。
     *
     * @param consecutiveFailures 新的连续失败计数
     */
    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
        if (log.isDebugEnabled()) {
            log.debug("[AutoCompactTracking] 调用方写回 consecutiveFailures={}（CC query.ts:536-542）",
                consecutiveFailures);
        }
    }

    /**
     * 重置全部状态（新会话时调用）
     */
    public void reset() {
        this.compacted = false;
        this.turnCounter = 0;
        this.turnId = UUID.randomUUID().toString();
        this.consecutiveFailures = 0;
        log.info("[AutoCompactTracking] 状态已重置");
    }
}
