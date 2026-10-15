package com.nexusai.infra.llm;

import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.tool.AgentUsage;

/**
 * 一次 LLM 调用尝试的<b>记账记录</b>（请求网关产出 → 账本/明细表消费）。
 *
 * <p><b>粒度</b>：一次「尝试」一行 —— 常规调用 1 行；流式失败后回退非流式 2 行（两次上游都可能计费）；
 * 被 abort 的流式 1 行（{@link LlmUsagePhase#ABORTED}，用 abort 时已累积的部分用量）。
 * 用户裁定：<b>每次 call 落表</b>（{@code llm_usage_calls}），本 record 即该表一行的载荷。
 *
 * <p><b>local-only 边界</b>：本 record 只走「网关 → 账本 → 明细表」链路，
 * <b>不进</b> STOMP / outbound DTO / LLM 请求 payload（BUDGET 红线同源约束）。
 *
 * @param callId          本次调用 id（前缀 {@code uc-}，见仓库 id 约定）
 * @param attribution     归属（会话 / agent / 归类 / 层级）· <b>可含 AgentState 引用以外的全部维度</b>
 * @param modelName       计费用模型名（DB 模型名，价格解析键；与 {@link #sdkModelName()} 可能不同）
 * @param sdkModelName    实际发给上游的模型名（已剥 provider 前缀）
 * @param providerType    provider 类型（openai_sdk / anthropic / mock…）
 * @param querySource     调用来源字符串（{@code QuerySource} 值或旁路自述名；可 null）
 * @param stream          是否流式调用
 * @param phase           尝试结局（SETTLED / ABORTED / FAILED）
 * @param tokenSource     数值来源可信度（UPSTREAM 才可与账单对账；ESTIMATED 仅观测）
 * @param usage           该次尝试的累计 usage（EMPTY = 无可用数值）
 * @param costYuan        该次尝试折算费用（元；{@code ESTIMATED} 时同样是估算值）
 * @param peakHour        计价时段（true = 高峰档）
 * @param costBreakdown   V101 费用分项（{@code null} = 无分项来源：网关产出后未经计价点
 *                        {@code ModelCostCalculator.price(...)} —— 落表时该行记 {@code LEGACY}）
 * @param status          调用状态短标签（{@code ok} / {@code aborted} / {@code error}）
 * @param errorKind       错误类别（可 null；仅 status != ok 时填）
 * @param startedAtMs     调用开始时刻（epoch ms）
 * @param elapsedMs       调用耗时（ms）
 */
public record LlmCallRecord(
        String callId,
        UsageAttribution attribution,
        String modelName,
        String sdkModelName,
        String providerType,
        String querySource,
        boolean stream,
        LlmUsagePhase phase,
        UsageTokenSource tokenSource,
        AgentUsage usage,
        double costYuan,
        boolean peakHour,
        ModelCostCalculator.CostBreakdown costBreakdown,
        String status,
        String errorKind,
        long startedAtMs,
        long elapsedMs
) {

    /** 生成新调用 id（仓库 id 约定：{@code prefix-8hex}）。 */
    public static String newCallId() {
        return "uc-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** 归属会话 id（可 null）。 */
    public String sessionId() {
        return attribution != null ? attribution.sessionId() : null;
    }

    /** 归类维度（attribution 缺失 → UNKNOWN）。 */
    public UsageCategory category() {
        return attribution != null && attribution.category() != null
            ? attribution.category() : UsageCategory.UNKNOWN;
    }

    /**
     * 补齐计价结果（网关产出时不带价格 —— 计价依赖 models 表价格与时段档，属应用层账本职责；
     * 记账器算完后用本方法得到最终可落库记录）。
     *
     * <p><b>V101</b>：本重载<b>不带分项</b>（{@code costBreakdown = null}）→ 落表记
     * {@code cost_detail_source='LEGACY'}。带分项的路径见
     * {@link #withCost(ModelCostCalculator.CostBreakdown, boolean)}（账本走它）。
     *
     * @param costYuan 本次尝试折算费用（元）
     * @param peakHour 是否高峰档
     */
    public LlmCallRecord withCost(double costYuan, boolean peakHour) {
        return new LlmCallRecord(callId, attribution, modelName, sdkModelName, providerType, querySource,
            stream, phase, tokenSource, usage, costYuan, peakHour, null, status, errorKind, startedAtMs, elapsedMs);
    }

    /**
     * V101 · 用<b>计价点的分项</b>补齐记录：总额取自 {@code breakdown.totalYuan()}（单一公式点
     * {@code ModelCostCalculator.price(...)}，⛔ 不由调用方另算），分项本身随记录传到落表点。
     *
     * @param breakdown 分项（{@code null} → 退化为不带分项，仅置时段档）
     * @param peakHour  是否高峰档
     */
    public LlmCallRecord withCost(ModelCostCalculator.CostBreakdown breakdown, boolean peakHour) {
        if (breakdown == null) {
            return withCost(costYuan, peakHour);
        }
        return new LlmCallRecord(callId, attribution, modelName, sdkModelName, providerType, querySource,
            stream, phase, tokenSource, usage, breakdown.totalYuan(), peakHour, breakdown,
            status, errorKind, startedAtMs, elapsedMs);
    }

    /**
     * 补齐计价元数据但不改金额（usage 为零/未知时使用；保持「有调用发生」的观测事实）。
     */
    public LlmCallRecord withPeakHour(boolean peakHour) {
        return new LlmCallRecord(callId, attribution, modelName, sdkModelName, providerType, querySource,
            stream, phase, tokenSource, usage, costYuan, peakHour, costBreakdown, status, errorKind,
            startedAtMs, elapsedMs);
    }
}
