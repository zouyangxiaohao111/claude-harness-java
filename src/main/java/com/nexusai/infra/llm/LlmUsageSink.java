package com.nexusai.infra.llm;

import com.nexusai.application.agent.tool.AgentUsage;

import java.util.List;

/**
 * 单次 LLM 调用的<b>用量采集器</b>（调用作用域内，provider 写 → 网关读）。
 *
 * <p><b>职责边界（用户裁定：协议差异交 provider）</b>：只有 provider 知道怎么从自己的协议里
 * 取 usage（Anthropic message_start/message_delta、OpenAI final chunk / 非流式
 * ChatCompletion.usage）⇒ provider 负责「解析并 record」；<b>不负责</b>聚合、计费、落库、
 * 归属 —— 那些是请求网关与账本的事。
 *
 * <p><b>为什么是「快照列表」而不是单一累加值</b>：
 * <ol>
 *   <li>流式 provider 每个<b>尝试结局</b>记录一次快照（正常完成 / 被 abort / 流式失败回退前的
 *       已累积值）。快照是<b>该次尝试的累计值</b>，不是增量 ⇒ 不允许相加去重，只能按尝试分别记账。</li>
 *   <li>流式失败后回退非流式时，<b>两次尝试上游都可能已计费</b> ⇒ 明细行按尝试各落一行，
 *       账本求和时两次都计入（这正是用户要求「流中断、取消、结束时也要统计实际开销」的落点）。</li>
 *   <li>Abort 场景 Anthropic 已有部分 usage（message_start 起 {@code StreamState} 就填了
 *       input/cache/output），provider 在 abort 出口 record(ABORTED) 即可，无需伪造。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：provider 的回调可能来自不同线程（abort listener 线程 / SDK 消费线程），
 * 实现必须线程安全；{@link #collecting()} 返回基于 {@code CopyOnWriteArrayList} 的实现。
 *
 * <p><b>local-only</b>：本对象只在单次调用作用域内存活，<b>绝不</b>进 STOMP / outbound DTO /
 * LLM 请求 payload（与 BudgetTracker 同约束）。
 */
public interface LlmUsageSink {

    /** 空实现（未接线 / 测试 / 不需要统计的路径）。 */
    LlmUsageSink NONE = new LlmUsageSink() {
        @Override
        public void record(AgentUsage usage, UsageTokenSource source, LlmUsagePhase phase) {
            // 故意丢弃：调用方显式选择不统计
        }

        @Override
        public List<LlmUsageSnapshot> snapshots() {
            return List.of();
        }
    };

    /**
     * 记录一次尝试结局的用量快照（累计值，非增量）。
     *
     * @param usage  该次尝试的累计 usage（null → 视为 {@link AgentUsage#EMPTY}）
     * @param source 数值来源（上游上报 / 本地估算 / 未知）
     * @param phase  尝试结局（正常结算 / 中断 / 失败回退）
     */
    void record(AgentUsage usage, UsageTokenSource source, LlmUsagePhase phase);

    /** 快照只读列表（插入序）；无记录 → 空列表。 */
    List<LlmUsageSnapshot> snapshots();

    /** 新建线程安全的采集器。 */
    static LlmUsageSink collecting() {
        return new Collecting();
    }

    /** 线程安全采集器实现（CopyOnWriteArrayList；调用作用域内写入次数极少）。 */
    final class Collecting implements LlmUsageSink {

        private final List<LlmUsageSnapshot> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void record(AgentUsage usage, UsageTokenSource source, LlmUsagePhase phase) {
            snapshots.add(new LlmUsageSnapshot(usage != null ? usage : AgentUsage.EMPTY,
                source != null ? source : UsageTokenSource.UNKNOWN,
                phase != null ? phase : LlmUsagePhase.SETTLED));
        }

        @Override
        public List<LlmUsageSnapshot> snapshots() {
            return List.copyOf(snapshots);
        }
    }

    /**
     * 一次尝试结局的用量快照。
     *
     * @param usage  累计 usage（四 token 通道 + 嵌套字段；EMPTY 表示该尝试无可用数值）
     * @param source 数值来源可信度（UPSTREAM 才可与账单对账）
     * @param phase  尝试结局
     */
    record LlmUsageSnapshot(AgentUsage usage, UsageTokenSource source, LlmUsagePhase phase) {

        /** 该快照是否携带任何 token 数值。 */
        public boolean hasTokens() {
            return usage != null && usage.totalTokens() > 0;
        }
    }
}
