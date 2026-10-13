package com.nexusai.application.agent.cost;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.infra.llm.LlmCallRecord;
import com.nexusai.infra.llm.LlmUsagePhase;
import com.nexusai.infra.llm.UsageAttribution;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.infra.llm.UsageTokenSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话账本（{@link SessionUsageLedger}）记账与层级聚合的行为守护。
 *
 * <h2>WHY（规则九：这条测试守的是哪个真实故障）</h2>
 * <p>改造前只有「主 agent 流式调用」会进会话合计（唯一落库点 {@code ChatService:1154}），
 * 子代理 / fork / cron / 旁路调用的用量要么进隔离 state 后丢弃、要么根本不统计 ⇒
 * 会话金额系统性低估（用户实测「/cost 与统计图表明显低于上游账单」）。
 * 本账本是修复该故障的唯一聚合点，因此必须守住三件事：
 * <ol>
 *   <li><b>分层不丢</b>：主 / 子代理 / 旁路各成节点，会话合计 = 各节点之和（漏节点即漏钱）；</li>
 *   <li><b>视图等价</b>：账本回写 {@link AgentState} 的会话字段必须与改造前
 *       {@code accumulateSessionUsage} 公式逐项一致（否则 STOMP/DTO 数字会漂移）；</li>
 *   <li><b>可信度单列</b>：估算值与失败/中断尝试单列计数，不与上游上报值混算（防把估算当账单）。</li>
 * </ol>
 *
 * <h2>反向实验（哪个变异会让哪条断言变红）</h2>
 * <ul>
 *   <li>把 {@link SessionUsageLedger#record(LlmCallRecord)} 里的
 *       {@code record(enriched, costYuan, peak, contextWindow, maxOutputTokens)} 改成只更新
 *       {@code modelUsage} 不更新节点桶 ⇒ {@code 分层聚合} 的 rollup 断言红；</li>
 *   <li>把 {@code mirrorToState} 里的 {@code addSessionInputTokens} 删掉 ⇒
 *       {@code 镜像回写与改造前公式等价} 的 input 断言红；</li>
 *   <li>把 {@code estimatedCalls}/{@code abortedCalls} 的累加分支删掉 ⇒
 *       {@code 可信度单列} 两条断言红。</li>
 * </ul>
 */
@DisplayName("[usage-gateway] 会话账本：分层聚合 / 视图等价 / 可信度单列")
class SessionUsageLedgerAccountingTest {

    private static final String SESSION = "sess-ledger-test";
    private static final String MODEL = "deepseek-v4-flash";

    /** 账本测试用计价器（无 Spring：mapper 为 null → 价格回落内置默认档，仍可算出非零费用）。 */
    private static ModelCostCalculator calculator() {
        return new ModelCostCalculator();
    }

    private static LlmCallRecord call(UsageAttribution who, long in, long out, String model,
                                      UsageTokenSource source, LlmUsagePhase phase, boolean stream) {
        return call(who, new AgentUsage(in, out, null, null, null, null, null), model, source, phase, stream);
    }

    /** 全量 usage 版本（cache 通道非空时用；视图等价用例必须与旧公式吃同一份 usage）。 */
    private static LlmCallRecord call(UsageAttribution who, AgentUsage usage, String model,
                                      UsageTokenSource source, LlmUsagePhase phase, boolean stream) {
        return new LlmCallRecord(LlmCallRecord.newCallId(), who, model, model, "openai_sdk",
            "test", stream, phase, source,
            usage,
            0.0, false, phase == LlmUsagePhase.SETTLED ? "ok" : "error", null,
            System.currentTimeMillis(), 12L);
    }

    @Nested
    @DisplayName("分层聚合：主 / 子代理 / 旁路各成节点，rollup = 各节点之和")
    class LayeredAggregation {

        @Test
        @DisplayName("主 agent + 子代理 + 旁路三条调用 → 三个节点、rollup 等于三者之和、子节点挂在父节点下")
        void layeredNodesAndRollup() {
            // WHY: 会话合计必须覆盖全部调用面 —— 漏任何一个节点 = 少算钱（本次改造的根因）
            // WHEN: 主 agent 一次 + 子代理一次 + 无会话旁路一次
            SessionUsageLedger ledger = new SessionUsageLedger(SESSION, calculator(), null);
            UsageAttribution main = UsageAttribution.top(SESSION, SESSION, UsageCategory.MAIN, null);
            UsageAttribution sub = main.child("a1234567890abcde", UsageCategory.SUBAGENT, "Explore");
            UsageAttribution side = UsageAttribution.unknown(null);

            ledger.record(call(main, 1000, 200, MODEL, UsageTokenSource.UPSTREAM, LlmUsagePhase.SETTLED, true));
            ledger.record(call(sub, 3000, 400, MODEL, UsageTokenSource.UPSTREAM, LlmUsagePhase.SETTLED, true));
            ledger.record(call(side, 50, 10, MODEL, UsageTokenSource.UPSTREAM, LlmUsagePhase.SETTLED, false));

            assertThat(ledger.nodes()).as("三个调用面必须各成一个节点（漏节点即漏统计）").hasSize(3);
            assertThat(ledger.bucketOf(SESSION).inputTokens())
                .as("主 agent 节点只应含自己的 token：%s", ledger.bucketOf(SESSION)).isEqualTo(1000L);
            assertThat(ledger.bucketOf("a1234567890abcde").inputTokens())
                .as("子代理节点应独立成桶").isEqualTo(3000L);
            assertThat(ledger.childrenOf(SESSION))
                .as("子代理节点应挂在主 agent 节点下（层级信息）").containsKey("a1234567890abcde");
            assertThat(ledger.rollup().inputTokens())
                .as("rollup 必须等于三节点 input 之和（1000+3000+50）").isEqualTo(4050L);
            assertThat(ledger.rollup().calls()).as("调用次数按尝试计 3 次").isEqualTo(3L);
            assertThat(ledger.rollup().streamCalls()).as("流式/非流式拆分").isEqualTo(2L);
            assertThat(ledger.rollup().nonStreamCalls()).isEqualTo(1L);
        }

        @Test
        @DisplayName("层级深度：子代理 depth=1、其派生节点 depth=2，parentAgentId 串成链")
        void depthChain() {
            // WHY: 用户要求「体现费用与 token 用量的层级信息」—— 只有 depth/parent 正确才能重建树
            SessionUsageLedger ledger = new SessionUsageLedger(SESSION, calculator(), null);
            UsageAttribution main = UsageAttribution.top(SESSION, SESSION, UsageCategory.MAIN, null);
            UsageAttribution child = main.child("aChild", UsageCategory.SUBAGENT, "Explore");
            UsageAttribution grand = child.child("aGrand", UsageCategory.SUBAGENT, "nested");

            ledger.attach(main, new AgentState("sys", SESSION, UUID.randomUUID()));
            ledger.record(call(grand, 7, 7, MODEL, UsageTokenSource.UPSTREAM, LlmUsagePhase.SETTLED, true));

            UsageAttribution restored = ledger.attributionOf("aGrand");
            assertThat(restored).as("节点归属必须可反查（spawn 点据此派生下一级）").isNotNull();
            assertThat(restored.depth()).as("嵌套子代理 depth 应为 2").isEqualTo(2);
            assertThat(restored.parentAgentId()).as("parentAgentId 应指向直接父节点").isEqualTo("aChild");
        }
    }

    @Nested
    @DisplayName("视图等价：账本回写 AgentState 与改造前公式一致")
    class MirrorEquivalence {

        @Test
        @DisplayName("记账一次后 state 的会话字段 == 既有公式（input/output/cost/模型桶）逐项一致")
        void mirrorMatchesLegacyFormula() {
            // WHY: 账本接管后 state 字段仍是 STOMP/DTO 的数字来源（用户裁定保留视图）——
            //      若回写公式与旧 accumulateSessionUsage 不一致，前端金额会无解释地漂移
            // WHEN: 同一 usage 分别经「旧公式手算」与「账本回写」，再逐项对比
            ModelCostCalculator calc = calculator();
            AgentUsage usage = new AgentUsage(1200, 340, 12L, 800L, null, null, null);
            UsageAttribution main = UsageAttribution.top(SESSION, SESSION, UsageCategory.MAIN, null);
            AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
            SessionUsageLedger ledger = new SessionUsageLedger(SESSION, calc, null);
            ledger.attach(main, state);

            // 旧公式（改造前 LlmAgentLoop.accumulateSessionUsage + addSessionOutputTokens）
            boolean peak = calc.isPeakHour();
            double expectedCost = calc.calculateCostYuan(MODEL, usage, peak);
            CostTracker.ModelUsage expectedBucket = CostTracker.computeModelUsageIncrement(
                expectedCost, usage, MODEL, calc.contextWindowFor(MODEL), calc.maxOutputFor(MODEL));

            ledger.record(call(main, usage, MODEL,
                UsageTokenSource.UPSTREAM, LlmUsagePhase.SETTLED, true));

            assertThat(state.sessionInputTokens()).as("会话 input 视图").isEqualTo(usage.inputTokens());
            assertThat(state.sessionOutputTokens()).as("会话 output 视图").isEqualTo(usage.outputTokens());
            assertThat(state.sessionCostYuan()).as("会话费用视图（元）").isEqualTo(expectedCost);
            assertThat(state.sessionModelUsage().get(MODEL))
                .as("按模型桶必须与旧公式 computeModelUsageIncrement 完全一致")
                .isEqualTo(expectedBucket);
        }
    }

    @Nested
    @DisplayName("可信度单列：估算与失败/中断尝试不与上游上报混算")
    class SourceSeparation {

        @Test
        @DisplayName("ESTIMATED / ABORTED / FAILED 各计一次单列计数，token 仍并入总量")
        void estimatedAndFailureCounters() {
            // WHY: 估算值不可用于对账、失败尝试可能仍被上游计费 —— 必须能一眼分辨
            SessionUsageLedger ledger = new SessionUsageLedger(SESSION, calculator(), null);
            UsageAttribution main = UsageAttribution.top(SESSION, SESSION, UsageCategory.MAIN, null);

            ledger.record(call(main, 100, 10, MODEL, UsageTokenSource.ESTIMATED, LlmUsagePhase.SETTLED, true));
            ledger.record(call(main, 200, 20, MODEL, UsageTokenSource.UPSTREAM, LlmUsagePhase.ABORTED, true));
            ledger.record(call(main, 0, 0, MODEL, UsageTokenSource.UNKNOWN, LlmUsagePhase.FAILED, false));

            UsageBucket bucket = ledger.rollup();
            assertThat(bucket.estimatedCalls()).as("估算尝试单独计数").isEqualTo(1L);
            assertThat(bucket.abortedCalls()).as("中断尝试单独计数").isEqualTo(1L);
            assertThat(bucket.failedCalls()).as("失败尝试单独计数").isEqualTo(1L);
            assertThat(bucket.calls()).as("总尝试次数 = 3（含失败/中断，调用确实发生过）").isEqualTo(3L);
            assertThat(bucket.inputTokens()).as("token 总量仍并计（不做静默丢弃）").isEqualTo(300L);
        }
    }

    @Nested
    @DisplayName("持久化往返：usage_tree_json 快照可无损恢复层级与构成")
    class SnapshotRoundTrip {

        @Test
        @DisplayName("snapshotJson → 新账本 restoreFromJson → 节点与 rollup 一致")
        void snapshotRoundTrip() {
            // WHY: 跨 turn 恢复若丢节点，会话重启后子代理成本会凭空消失
            SessionUsageLedger ledger = new SessionUsageLedger(SESSION, calculator(), null);
            UsageAttribution main = UsageAttribution.top(SESSION, SESSION, UsageCategory.MAIN, null);
            ledger.record(call(main, 5000, 600, MODEL, UsageTokenSource.UPSTREAM, LlmUsagePhase.SETTLED, true));
            ledger.record(call(main.child("aSub", UsageCategory.SUBAGENT, "Explore"),
                700, 80, MODEL, UsageTokenSource.UPSTREAM, LlmUsagePhase.SETTLED, true));

            String json = ledger.snapshotJson();
            assertThat(json).as("有调用 → 快照非空").isNotNull();

            SessionUsageLedger restored = new SessionUsageLedger(SESSION, calculator(), null);
            restored.restoreFromJson(json);

            assertThat(restored.nodes()).as("节点数往返一致").hasSize(ledger.nodes().size());
            assertThat(restored.rollup().inputTokens()).as("rollup input 往返一致")
                .isEqualTo(ledger.rollup().inputTokens());
            assertThat(restored.rollup().totalCostYuan()).as("费用往返一致（含高峰/空闲分档合计）")
                .isEqualTo(ledger.rollup().totalCostYuan());
            assertThat(restored.modelUsage().get(MODEL)).as("按模型 rollup 往返一致")
                .isEqualTo(ledger.modelUsage().get(MODEL));
        }

        @Test
        @DisplayName("空账本 → snapshotJson 为 null（不写空串）")
        void emptyLedgerSnapshotIsNull() {
            assertThat(new SessionUsageLedger(SESSION, calculator(), null).snapshotJson()).isNull();
        }
    }
}
