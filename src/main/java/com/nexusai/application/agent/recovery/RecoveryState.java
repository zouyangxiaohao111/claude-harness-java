package com.nexusai.application.agent.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单次 Agent 调用的恢复状态跟踪 · 对齐 CC query.ts State（mutable loop state）。
 *
 * <p>这是一个可变的 POJO，用于在单次 {@code LlmAgentLoop.run()} 调用期间
 * 跟踪恢复进度。与 {@link com.nexusai.application.agent.AgentState AgentState}
 * （会话级状态）不同，RecoveryState 是调用级状态，每次 run() 新建。
 *
 * <h2>CC 对齐对照（grep 自验）</h2>
 * <p><b>偏离标注（TODO）</b>：本类字段是 Java 侧把 CC withRetry 域动作载体
 * （currentModel / lastBackoffMs）与 CC query.ts 恢复字段（maxOutputTokensRecoveryCount /
 * hasAttemptedReactiveCompact）混装的调用级容器；CC query.ts:204-217 {@code type State}
 * 无这些字段——currentModel 是 query.ts:572 getRuntimeMainLoopModel 赋值的循环局部
 * （:896 fallback 重写，:670 入参），lastBackoffMs 是 withRetry.ts:432/:462 的循环局部
 * delayMs（:509 createSystemAPIErrorMessage 载荷），CC 无跨类 State 载体。
 * 本类<b>保留为调用级桥</b>（withRetry 动作与 query 恢复同源），字段级偏差逐项标注。
 *
 * <p><b>P-14 裁决（2026-08-15 登记保留）</b>：currentModel / lastBackoffMs <b>保留</b>——
 * 消费方为生产必需：529 资格闸（TransientErrorHandler:129/:131）、退避睡眠单一来源
 * （TransientErrorHandler:176 写 → LlmAgentLoop:4150 读）、fallback 生效链
 * （TransientErrorHandler:212 / LlmAgentLoop:5887 写 → LlmAgentLoop:3464 读）；
 * CC 等价为 query.ts 局部 currentModel / withRetry.ts 局部 delayMs+载荷，Java 跨类载体
 * 属承载方式差异而非行为偏离（删除需 RecoveryResult 载荷迁移，另立轮次）。
 * <table>
 *   <tr><th>本字段</th><th>CC 对齐源</th><th>偏离标注</th></tr>
 *   <tr><td>continuationCount</td><td>query.ts:1223 maxOutputTokensRecoveryCount</td><td>对齐</td></tr>
 *   <tr><td>hasAttemptedReactiveCompact</td><td>query.ts:1154 / :1162</td><td>对齐</td></tr>
 *   <tr><td>currentModel</td><td>query.ts:572 / :896 局部 currentModel（:670 入参；withRetry.ts:130 fallbackModel 为触发源）</td><td>Java ⊕（跨类载体，P-14 登记保留）</td></tr>
 *   <tr><td>lastBackoffMs</td><td>withRetry.ts:432 / :462 delayMs 循环局部 + :509 载荷</td><td>Java ⊕（跨类载体，P-14 登记保留）</td></tr>
 *   <tr><td>lastReason</td><td>query.ts:216 transition: Continue | undefined</td><td>对齐（仅 Continue 值写实）</td></tr>
 * </table>
 *
 * <p><b>ER-IMP-03 移除（DC-10 部分）</b>：旧 {@code retryAttempt} 与 {@code consecutive529Count}
 * 计数器字段已删除——CC 用 withRetry 循环闭包局部量（withRetry.ts:186
 * {@code consecutive529Errors} / :189 attempt）承载，挂可变 POJO 为脏代码；现由
 * LlmAgentLoop 以方法内闭包局部数组（{@code retryAttemptHolder} /
 * {@code consecutive529ErrorsHolder}）表达，随 Path3 接线移除。
 *
 * <p><b>本 session 对齐（ER-IMP-01）</b>：旧 {@code lastTransition: Transition}（8 值
 * 混合枚举）收敛为 {@code lastReason: LoopReason}（CC query 17 reason 全集）；
 * withRetry 域动作（backoff / exhausted / fatal / fallback）不再写 lastReason（置 null），
 * 避免把无 CC reason 的动作冒充 query reason（DC-09）。
 */
public class RecoveryState {

    private static final Logger log = LoggerFactory.getLogger(RecoveryState.class);

    /** 续写提示已发射次数 · CC query.ts:164 MAX_OUTPUT_TOKENS_RECOVERY_LIMIT=3 */
    private int continuationCount;

    /** 是否已尝试 reactive compact（单次） · CC query.ts:1154 */
    private boolean hasAttemptedReactiveCompact;

    /**
     * 当前使用的模型 ID · CC 等价 query.ts:572 循环局部 currentModel（getRuntimeMainLoopModel
     * 赋值，:670 入参；fallback 切换为 :896 局部重写，withRetry.ts:347-350
     * FallbackTriggeredError 触发，fallbackModel 为 withRetry.ts:130 RetryOptions 字段）。
     * Java 跨类载体（P-14 登记保留）：消费方 = 529 资格闸 TransientErrorHandler:129/:131、
     * fallback 生效 LlmAgentLoop:3464。
     */
    private String currentModel;

    /**
     * 最近一次恢复 reason（CC query.ts:216 {@code transition: Continue | undefined}）。
     *
     * <p>仅写 CC Continue reason 实值；withRetry 域动作 / 已耗尽置 null
     * （CC 无对应 reason，DC-09）。
     */
    private LoopReason lastReason;

    /**
     * 最近一次退避等待毫秒数 · CC 等价 withRetry.ts:432/:462 循环局部 delayMs（:509
     * createSystemAPIErrorMessage 载荷传递，CC 无 State 载体）。Java 跨类载体（P-14 登记保留）：
     * 写点 TransientErrorHandler:176（退避结果登记，s11-P2-3，RetryDelayCalculator 单一公式），
     * 读点 LlmAgentLoop:4150（实际 sleep）。
     */
    private long lastBackoffMs;

    /**
     * 构造 RecoveryState，指定初始模型 ID。
     *
     * @param initialModel 初始使用的模型 ID（如 "gpt-4"）
     */
    public RecoveryState(String initialModel) {
        this.currentModel = initialModel;
        this.continuationCount = 0;
        this.hasAttemptedReactiveCompact = false;
        this.lastReason = null;
        if (log.isDebugEnabled()) {
            log.debug("RecoveryState 初始化: model={}", initialModel);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 状态变更方法
    // ════════════════════════════════════════════════════════════════════════

    /** 递增续写计数 · CC query.ts:1223 maxOutputTokensRecoveryCount */
    public void incrementContinuation() {
        this.continuationCount++;
        this.lastReason = LoopReason.MAX_OUTPUT_TOKENS_RECOVERY;
        log.info("RecoveryState: 续写 #{} · CC MAX_OUTPUT_TOKENS_RECOVERY_LIMIT=3",
            this.continuationCount);
    }

    /**
     * 随轮重置续写计数 · 对齐 CC query.ts:1721 next_turn {@code maxOutputTokensRecoveryCount: 0}。
     *
     * <p><b>WHY（DRIFT-8 修复）</b>: CC 在 {@code next_turn} transition（query.ts:1721）把
     * {@code maxOutputTokensRecoveryCount} 重置为 0——每个新 turn 都从全新恢复预算开始（≤3 次）。
     * Java 端 {@link #continuationCount} 即 CC {@code maxOutputTokensRecoveryCount}（query.ts:1223
     * {@code maxOutputTokensRecoveryCount < MAX_OUTPUT_TOKENS_RECOVERY_LIMIT}），旧实现跨 turn 累计，
     * 后续 turn 的 max_tokens 续写被提前判死（比 CC 更严格）。本方法按 turn 复位，由主循环在
     * "genuine next_turn 边界"（真实 assistant 响应产生后，与 {@link #resetReactiveCompactAttempt()}
     * 同一调用点）调用。
     *
     * <p><b>恢复链内语义保留</b>: 同一恢复链内（CONTINUATION 恢复 → retry → 再次截断）计数仍递增，
     * 因此 ≤3 上限在恢复链内仍生效（max_tokens 恢复的 continue 绕过 next_turn 边界，不触达本方法）。
     */
    public void resetContinuation() {
        if (this.continuationCount > 0) {
            log.info("RecoveryState: continuationCount 随轮重置 {} → 0 · CC next_turn query.ts:1721",
                this.continuationCount);
        }
        this.continuationCount = 0;
    }

    /** 标记已尝试 reactive compact · CC query.ts:1154 / :1162（Continue: reactive_compact_retry） */
    public void markReactiveCompact() {
        this.hasAttemptedReactiveCompact = true;
        this.lastReason = LoopReason.REACTIVE_COMPACT_RETRY;
        log.info("RecoveryState: reactive compact 已执行 · CC 单次限制 query.ts:1154");
    }

    /**
     * 随轮重置 reactive compact 尝试标志 · 对齐 CC query.ts:1721 next_turn 置 false。
     *
     * <p><b>WHY（DRIFT-7 修复）</b>: CC 在 {@code next_turn} transition（query.ts:1721）把
     * {@code hasAttemptedReactiveCompact} 重置为 false——每个新 turn 都允许再尝试一次应急压缩。
     * Java 旧实现把该标志做成每 run() 一次（RecoveryState 单实例，LlmAgentLoop:1997），
     * 后续 turn 的 PTL 永不二次 reactive compact（比 CC 更严格）。本方法把标志按 turn 复位，
     * 由主循环在"genuine next_turn 边界"（真实 assistant 响应产生后）调用。
     *
     * <p><b>防螺旋语义保留</b>: 本方法只重置「跨 turn」尝试资格；同一恢复链内（reactive compact
     * 成功 → retry → 再次 PTL）标志仍保持 true（markReactiveCompact 后未被重置），
     * 因此 single-shot 守卫（LlmAgentLoop:2988）在恢复链内仍生效，不会死亡螺旋。
     */
    public void resetReactiveCompactAttempt() {
        if (this.hasAttemptedReactiveCompact) {
            log.info("RecoveryState: hasAttemptedReactiveCompact 随轮重置 true→false · CC next_turn query.ts:1721");
        }
        this.hasAttemptedReactiveCompact = false;
    }

    /**
     * 保留 reactive compact 尝试守卫 · 对齐 CC query.ts:1297 stop_hook_blocking 重建 State
     * {@code hasAttemptedReactiveCompact} 保留（不置 false）。
     *
     * <p><b>WHY（ER-IMP-09 防死亡螺旋）</b>: CC query.ts:1293-1296 注释明示——compact 已跑仍
     * PTL 后，stop-hook blocking 错误后重试会得到相同结果；此处若置 false 会引发无限循环
     * （compact → 仍过长 → error → stop hook blocking → compact → … 烧几千次 API）。Java 端
     * stop-hook 重入（blockingError → loop(..., stopHookActive=true)）会新建 RecoveryState
     * 天然 reset hasAttemptedReactiveCompact=false，故需在 loop 入口把旧守卫搬运到新实例。
     *
     * <p><b>与 resetReactiveCompactAttempt 的区分</b>: 本方法只在重入边界（stop_hook_blocking
     * query.ts:1297）保留守卫；resetReactiveCompactAttempt 是 next_turn 边界（query.ts:1721）
     * 重置。两者调用点不同，语义不冲突。
     *
     * @param preserve true=保留旧守卫值到本实例（本实例先前 false 则置 true，已 true 则不变）；
     *                 false=不改变本实例当前值（调用方仅在重入且旧守卫为 true 时传 true）
     */
    public void preserveReactiveCompactAttempt(boolean preserve) {
        if (preserve && !this.hasAttemptedReactiveCompact) {
            this.hasAttemptedReactiveCompact = true;
            log.warn("RecoveryState: stop-hook 重入保留 hasAttemptedReactiveCompact=false→true · CC stop_hook_blocking query.ts:1297（防死亡螺旋）");
        }
    }

    /**
     * 更新当前模型 ID（fallback 切换）· CC 等价 query.ts:896 {@code currentModel = fallbackModel}
     * 局部重写（withRetry.ts:347-350 FallbackTriggeredError 抛出后由 query 捕获切换；
     * fallbackModel 为 withRetry.ts:130 RetryOptions 字段）。Java 以本方法跨类写回，
     * 下一轮 effectiveModel 解析经 {@link #getCurrentModel()} 生效（LlmAgentLoop:3464 / :5887，
     * P-14 登记保留）。
     *
     * <p><b>ER-IMP-01 修正</b>：模型切换属 withRetry 域动作，CC query.ts 无对应 reason
     * （DC-09），不再写 lastReason（置 null）；CC query.ts:1302 stop_hook_blocking 保留
     * hasAttemptedReactiveCompact、但模型切换不会产生 Continue reason。
     */
    public void setCurrentModel(String model) {
        String old = this.currentModel;
        this.currentModel = model;
        this.lastReason = null;
        log.warn("RecoveryState: 模型切换 {} → {} · CC withRetry.ts:337（非 CC query reason，lastReason 置 null）", old, model);
    }

    /** 设置最近一次恢复 reason（仅写 CC Continue reason 实值，withRetry 域动作传 null） */
    public void setLastReason(LoopReason r) {
        this.lastReason = r;
    }

    /**
     * 设置最近一次退避毫秒数 · 生产写点 TransientErrorHandler:176（退避结果登记，s11-P2-3；
     * 非纯测试钩子），读点 LlmAgentLoop:4150（实际 sleep 单一来源）；CC 等价 withRetry.ts:432/:462
     * 循环局部 delayMs + :509 createSystemAPIErrorMessage 载荷（P-14 登记保留）。
     */
    public void setLastBackoffMs(long ms) {
        this.lastBackoffMs = ms;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Getters
    // ════════════════════════════════════════════════════════════════════════

    public int getContinuationCount() { return continuationCount; }
    public boolean isHasAttemptedReactiveCompact() { return hasAttemptedReactiveCompact; }
    public String getCurrentModel() { return currentModel; }
    public LoopReason getLastReason() { return lastReason; }
    public long getLastBackoffMs() { return lastBackoffMs; }
}
