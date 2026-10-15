package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.cost.SessionUsageLedger;
import com.nexusai.infra.llm.UsageAttribution;
import com.nexusai.infra.llm.UsageCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子代理记账归属的 **label 取值策略**（{@link SubagentExecutor#subagentUsageAttribution}）。
 *
 * <h2>WHY（规则九 · 这条守的是用户实测的那个显示故障）</h2>
 * 「用量与花费 → 统计 → 按代理层级」的子代理行此前显示成 **「通用型」「探索型」「规划型」** —— 那是
 * {@code subagentCtx.subagentName()}（agent **类型**名 {@code general-purpose / Explore / Plan}）经前端
 * {@code LABEL_GLOSS} 的译名。同一会话可以跑多个同型子代理，行名因此全部一样，用户读不出是哪一个
 * （实测反馈：「子代理行应该显示具体的会话名称而不是 xx 型」）。
 * <p>「具体名称」= CC {@code AgentTool} 的 {@code description}（3-5 词任务短名），即任务注册表
 * {@code registerSyncForeground(agentId, summaryDescription, …)} 里那个 description，也是「用量」页
 * {@code @名称} 用的同一个串。故 label 应取它；只有取不到（teammate / fork-skill / workflow 三条走共享
 * 单例的路径不设该字段）才回落类型名。
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>把三元反过来（优先 {@code subagentName}）→ 「有任务名取任务名」红；</li>
 *   <li>把 label 直接写死 {@code subagentName}（删掉任务名分支）→ 同上红；</li>
 *   <li>只判 {@code != null} 不判 {@code isBlank} → 「空白任务名回落类型名」红（会写出空 label，
 *       前端「按代理层级」变成一行空白 + 悬停标题只剩「子代理 ·」）；</li>
 *   <li>把 {@code child(...)} 换成 {@code top(...)} 或改 depth/父 → 「层级维度不因 label 变化而变」红。</li>
 * </ul>
 *
 * <p>⛔ 无需 Spring：{@code spawningTuc = null} + 空账本 ⇒ 父归属走「会话根」分支（与
 * {@code SubagentExecutor} javadoc 所列第 3 档一致），本用例只关心 label 与层级两个维度。
 */
class SubagentExecutorUsageLabelTest {

    private static final String SESSION = "sess-label";
    private static final String CHILD_AGENT_ID = "a0123456789abcdef";

    /** 空账本（无已登记节点 ⇒ 父归属解析走「会话根」档）。 */
    private static SessionUsageLedger emptyLedger() {
        return new SessionUsageLedger(SESSION, new ModelCostCalculator(), null);
    }

    private static UsageAttribution attribution(String taskDescription, String subagentName) {
        return SubagentExecutor.subagentUsageAttribution(
            emptyLedger(), null, CHILD_AGENT_ID, taskDescription, subagentName, SESSION);
    }

    @Test
    @DisplayName("有任务名 → label = 任务名（⛔ 不是 agent 类型名）")
    void prefersTaskDescription() {
        UsageAttribution a = attribution("分析可用编译构建环境", "general-purpose");

        assertThat(a.label()).as("label 应为该次子代理的具体任务名")
            .isEqualTo("分析可用编译构建环境");
        assertThat(a.label()).as("⛔ 不得退化成 agent 类型名（前端会译成「通用型」）")
            .isNotEqualTo("general-purpose");
    }

    @Test
    @DisplayName("任务名缺失 → 回落 agent 类型名（fail-soft，不伪造空串）")
    void fallsBackToTypeNameWhenDescriptionMissing() {
        assertThat(attribution(null, "Explore").label())
            .as("null → 回落类型名").isEqualTo("Explore");
        assertThat(attribution("   ", "Plan").label())
            .as("纯空白 → 回落类型名（不得写出空 label）").isEqualTo("Plan");
        assertThat(attribution(null, null).label())
            .as("两者皆无 → null（不伪造）").isNull();
    }

    @Test
    @DisplayName("层级维度不因 label 取值而变（category/depth/父链仍是子代理派生）")
    void levelDimensionsUnaffectedByLabel() {
        UsageAttribution withName = attribution("分析可用编译构建环境", "general-purpose");
        UsageAttribution fallback = attribution(null, "general-purpose");

        for (UsageAttribution a : new UsageAttribution[] {withName, fallback}) {
            assertThat(a.category()).as("归类恒为 SUBAGENT").isEqualTo(UsageCategory.SUBAGENT);
            assertThat(a.depth()).as("子代理 depth = 1").isEqualTo(1);
            assertThat(a.parentAgentId()).as("父 = 会话根").isEqualTo(SESSION);
            assertThat(a.sessionId()).isEqualTo(SESSION);
            assertThat(a.agentId()).isEqualTo(CHILD_AGENT_ID);
        }
    }
}
