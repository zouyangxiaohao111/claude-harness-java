package com.nexusai.infra.llm;

/**
 * 用量记账端口 · 请求网关的唯一记账出口（由应用层实现：会话账本 + 明细表 + AgentState 视图回写）。
 *
 * <p><b>WHY 端口在 infra、实现在 application</b>：网关是出站边界（infra/llm），但记账要读
 * models 表价格（`ModelCostCalculator`）、写会话账本与明细表、回写 {@code AgentState}
 * （都在 application 层）⇒ 用依赖倒置：infra 定义端口，application 提供实现并由 Spring 注入
 * （{@code @Autowired(required = false)}，未注入 = 不记账，保持单测可 new 的既有范式）。
 *
 * <p><b>调用时机</b>：网关在调用返回后（成功 / 中断 / 失败一律）把 sink 里的快照逐条交给本端口；
 * 快照为空时也会交一条 {@link UsageTokenSource#UNKNOWN} + 0 token 的记录，用于「调用次数/耗时」
 * 观测（不伪造 token）。
 */
@FunctionalInterface
public interface LlmUsageRecorder {

    /** 空实现（未接线 / 测试 / 不需要统计）。 */
    LlmUsageRecorder NONE = call -> {
        // 故意丢弃：调用方显式选择不统计
    };

    /**
     * 记录一次调用尝试。
     *
     * @param call 记账记录（非 null；数值可能为 0/UNKNOWN）
     */
    void record(LlmCallRecord call);
}
