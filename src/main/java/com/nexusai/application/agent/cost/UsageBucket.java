package com.nexusai.application.agent.cost;

/**
 * 用量/费用<b>构成桶</b> · 账本与层级快照的最小聚合单元（「费用和 token 用量构成」的载体）。
 *
 * <p><b>WHY 分列这些维度</b>（用户要求「能体现费用和 token 用量构成的层级信息」）：
 * <ul>
 *   <li>四 token 通道分列（input / output / cacheRead / cacheWrite）—— 与上游 usage 字段一一对应，
 *       便于按 anthropic / 非 anthropic 两种计费语义核对；</li>
 *   <li>{@code estimatedCalls} / {@code failedCalls} / {@code abortedCalls} —— 把「估算值」与
 *       「失败/中断尝试」单列，防止与上游上报值混算（{@code UsageTokenSource} 语义在聚合层的延续）；</li>
 *   <li>{@code costYuanPeak} / {@code costYuanOffpeak} —— 高峰/空闲两档分列，使金额变化可归因到
 *       「时段」而非「用量」（{@code ModelCostCalculator.isPeakHour} 口径）；</li>
 *   <li>{@code streamCalls} / {@code nonStreamCalls} —— 流式/非流式统一后的调用面拆分。</li>
 * </ul>
 *
 * <p>不可变 record；累加走 {@link #plus(UsageBucket)}（返回新实例）。
 *
 * @param inputTokens      输入 token 合计（上游 input_tokens）
 * @param outputTokens     输出 token 合计
 * @param cacheReadTokens  缓存读 token 合计（nullable 上游字段按 0 计）
 * @param cacheWriteTokens 缓存写 token 合计（Anthropic cache_creation；deepseek 无该档恒 0）
 * @param calls            调用尝试次数（每个尝试结局计 1）
 * @param estimatedCalls   其中「数值为本地估算」的尝试数（不可用于对账）
 * @param failedCalls      其中失败的尝试数（部分用量可能仍被上游计费）
 * @param abortedCalls     其中被中断的尝试数（同上）
 * @param streamCalls      其中流式调用数
 * @param nonStreamCalls   其中非流式调用数
 * @param costYuanPeak     高峰档费用（元）
 * @param costYuanOffpeak  空闲档费用（元）
 * @param firstAtMs        首次调用时刻（epoch ms；未知 = 0）
 * @param lastAtMs         末次调用时刻（epoch ms；未知 = 0）
 */
public record UsageBucket(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long calls,
        long estimatedCalls,
        long failedCalls,
        long abortedCalls,
        long streamCalls,
        long nonStreamCalls,
        double costYuanPeak,
        double costYuanOffpeak,
        long firstAtMs,
        long lastAtMs
) {

    /** 零桶。 */
    public static UsageBucket empty() {
        return new UsageBucket(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0L, 0L);
    }

    /** 四通道 token 合计（不含 web search 等非 token 计费项）。 */
    public long totalTokens() {
        return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    }

    /** 费用合计（元）。 */
    public double totalCostYuan() {
        return costYuanPeak + costYuanOffpeak;
    }

    /** 桶累加（返回新实例，不修改自身）。 */
    public UsageBucket plus(UsageBucket other) {
        if (other == null) {
            return this;
        }
        long first = this.firstAtMs == 0 ? other.firstAtMs
            : (other.firstAtMs == 0 ? this.firstAtMs : Math.min(this.firstAtMs, other.firstAtMs));
        long last = Math.max(this.lastAtMs, other.lastAtMs);
        return new UsageBucket(
            this.inputTokens + other.inputTokens,
            this.outputTokens + other.outputTokens,
            this.cacheReadTokens + other.cacheReadTokens,
            this.cacheWriteTokens + other.cacheWriteTokens,
            this.calls + other.calls,
            this.estimatedCalls + other.estimatedCalls,
            this.failedCalls + other.failedCalls,
            this.abortedCalls + other.abortedCalls,
            this.streamCalls + other.streamCalls,
            this.nonStreamCalls + other.nonStreamCalls,
            this.costYuanPeak + other.costYuanPeak,
            this.costYuanOffpeak + other.costYuanOffpeak,
            first,
            last);
    }
}
