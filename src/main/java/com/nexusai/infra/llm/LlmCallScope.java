package com.nexusai.infra.llm;

import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.tool.AgentUsage;

/**
 * 流式调用的<b>调用作用域</b>载体 · 替换 {@code stream(...)} 末参 {@link AgentContext}
 * （<b>形参个数不变</b>：三档 arity 仍为 19 / 19 / 20，{@code LlmProviderStreamArityInvariantTest}
 * 钉住的位置索引零漂移）。
 *
 * <p><b>WHY 替换而非新增形参</b>：本接口三档重载在测试树里被 Mockito 以<b>纯位置索引</b>消费
 * （{@code inv.getArgument(9/10/16)} 等，全仓百余处），中部插入会静默漂移索引；而<b>类型替换</b>
 * 保持位置与个数不变 —— 这正是上两次「末尾追加」（skipCacheWrite / agentContext）取舍的延续。
 *
 * <p><b>两个组件各自独立可空</b>：{@link #agentContext()} 语义与替换前完全一致（provider 归因边
 * 发射用）；{@link #sink()} 为本次调用的用量采集器（null / {@link LlmUsageSink#NONE} = 不采集）。
 *
 * @param agentContext 归因上下文（可 null = 主线程 / 无归因）
 * @param sink         用量采集器（可 null = 不采集；provider 侧统一经
 *                     {@link #record(LlmCallScope, AgentUsage, UsageTokenSource, LlmUsagePhase)} 调用，
 *                     空值安全）
 */
public record LlmCallScope(AgentContext agentContext, LlmUsageSink sink) {

    /** 无 sink 的作用域（仅归因，不采集用量）。 */
    public static LlmCallScope of(AgentContext agentContext) {
        return new LlmCallScope(agentContext, LlmUsageSink.NONE);
    }

    /** 空作用域（替代原 {@code null} agentContext 的调用方）。 */
    public static LlmCallScope none() {
        return new LlmCallScope(null, LlmUsageSink.NONE);
    }

    /** 提取归因上下文（null 作用域安全）。 */
    public static AgentContext agentContextOf(LlmCallScope scope) {
        return scope != null ? scope.agentContext() : null;
    }

    /**
     * provider 侧统一入口 · 空值安全地把一次尝试结局的用量写入 sink
     * （scope / sink 为 null 时静默 no-op，等价原「未接线」语义）。
     */
    public static void record(LlmCallScope scope, AgentUsage usage, UsageTokenSource source, LlmUsagePhase phase) {
        if (scope == null) {
            return;
        }
        LlmUsageSink target = scope.sink();
        if (target == null) {
            return;
        }
        target.record(usage, source, phase);
    }

    /**
     * 便捷入口（正常结算）· 按「是否真拿到 token 数值」自动判定来源：
     * 有值 → {@link UsageTokenSource#UPSTREAM}，零值/空 → {@link UsageTokenSource#UNKNOWN}
     * （一个真实调用不可能 input/output 全为 0，故零值即「未解析到」）。
     */
    public static void recordSettled(LlmCallScope scope, AgentUsage usage) {
        boolean hasTokens = usage != null && usage.totalTokens() > 0;
        record(scope, hasTokens ? usage : AgentUsage.EMPTY,
            hasTokens ? UsageTokenSource.UPSTREAM : UsageTokenSource.UNKNOWN, LlmUsagePhase.SETTLED);
    }
}
