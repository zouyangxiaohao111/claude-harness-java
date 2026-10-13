package com.nexusai.application.agent.cost;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.infra.llm.UsageAttribution;
import com.nexusai.infra.llm.UsageCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [usage-gateway] 旁路单发（{@link LlmSideCallClient.SideCallUsage}）的**记账归属**意图测试。
 *
 * <h2>WHY（规则九：这条守的是用户实测看到的那两行）</h2>
 * 用户在「按代理层级」里看到**同一个子代理出现两行**、费用被拆开（子代理本体一行 + 它的旁路一行）。
 * 根因就在本类：旁路调用点普遍传 {@code state.agentId().toString()}（UUID 形式），而归属同一 agent 的
 * 主循环调用用 a+16hex ⇒ 账本按 agentId 分节点时裂成两个。本组用例守住三件事：
 * <ol>
 *   <li><b>身份归一</b>：UUID 形式的 agentId 必须归一到 a+16hex（单一身份源），否则同 agent 裂行；</li>
 *   <li><b>不另开子节点</b>：该 agent 已有账本节点时，本调用记在**它自己的节点**上（复用其 parent/depth），
 *       而不是造一个 depth+1 的子节点（那会把「自己的花费」显示成「自己的子节点花费」）；</li>
 *   <li><b>不造自环</b>：会话根自己发起（agentId 空 / 等于会话 id）→ depth 0，⛔ 不得出现
 *       {@code parentAgentId == agentId} 的自环节点（此前实测存在：agentId=sess-xxx 且 parent=sess-xxx）。</li>
 * </ol>
 *
 * <h2>RED（改坏哪条红）</h2>
 * <ul>
 *   <li>去掉 {@code canonicalAgentId} 归一 → 「UUID → a+16hex」用例红；</li>
 *   <li>把「已有节点 → 复用自身节点」改回 {@code child(...)} → 「不另开子节点」用例红；</li>
 *   <li>去掉 {@code agentKey.equals(sessionId)} 分支 → 「会话根 depth 0 且无自环」用例红。</li>
 * </ul>
 */
@DisplayName("[usage-gateway] 旁路单发归属 · 身份归一 / 不另开子节点 / 不造自环")
class LlmSideCallClientAttributionTest {

    private static final String SESSION = "sess-side";
    private static final String HEX = "a48afc67b6604cf17";
    private static final String UUID_FORM = "48afc67b-6604-cf17-0000-000000000000";

    /** 该 UUID 形式与 hex 形式确实是同一 agent（packAgentId 往返）——用例前提自证。 */
    @Test
    @DisplayName("前提：UUID 形式与 a+16hex 是同一 agent 的两种渲染（packAgentId 往返）")
    void uuidAndHexAreSameAgent() {
        assertThat(AgentContext.packAgentId(HEX).toString()).isEqualTo(UUID_FORM);
        assertThat(AgentContext.unpackAgentId(java.util.UUID.fromString(UUID_FORM))).isEqualTo(HEX);
    }

    @Test
    @DisplayName("UUID 形式的 agentId → 归一到 a+16hex（否则同一 agent 裂成两个账本节点）")
    void canonicalizesUuidFormToHex() {
        LlmSideCallClient.SideCallUsage usage = LlmSideCallClient.SideCallUsage.inSession(
            SESSION, UUID_FORM, UsageCategory.SIDE_CALL, "skill-catalog-summary");

        UsageAttribution who = usage.attribution(null);

        assertThat(who.agentId()).isEqualTo(HEX);
        assertThat(who.category()).isEqualTo(UsageCategory.SIDE_CALL);
        assertThat(who.label()).isEqualTo("skill-catalog-summary");
        assertThat(who.parentAgentId()).as("账本无该节点 → 挂会话根（depth 1）").isEqualTo(SESSION);
        assertThat(who.depth()).isEqualTo(1);
    }

    @Test
    @DisplayName("该 agent 已有账本节点 → 记在它自己的节点上（复用 parent/depth · 不另开子节点）")
    void reusesExistingAgentNode() {
        SessionUsageLedger ledger = new SessionUsageLedger(SESSION, null, null);
        // 子代理本体先入账（SubagentExecutor 的装配方式：父 = 会话根，depth 1）
        UsageAttribution subagent = UsageAttribution
            .top(SESSION, SESSION, UsageCategory.MAIN, null)
            .child(HEX, UsageCategory.SUBAGENT, "general-purpose");
        ledger.attach(subagent, null);

        UsageAttribution who = LlmSideCallClient.SideCallUsage.inSession(
            SESSION, UUID_FORM, UsageCategory.SIDE_CALL, "skill-catalog-summary").attribution(ledger);

        assertThat(who.agentId()).as("同一节点（不裂行）").isEqualTo(HEX);
        assertThat(who.depth()).as("复用自身 depth（不 +1）").isEqualTo(1);
        assertThat(who.parentAgentId()).isEqualTo(SESSION);
        assertThat(who.category()).as("类别用本次调用（byCategory 照常分列）").isEqualTo(UsageCategory.SIDE_CALL);
    }

    @Test
    @DisplayName("会话根自己发起（agentId 空 / 等于会话 id）→ depth 0 且无自环")
    void sessionRootCallHasNoSelfParent() {
        for (String agentId : new String[] {null, "", "   ", SESSION}) {
            UsageAttribution who = LlmSideCallClient.SideCallUsage.inSession(
                SESSION, agentId, UsageCategory.SIDE_CALL, "title-generation").attribution(null);

            assertThat(who.agentId()).isEqualTo(SESSION);
            assertThat(who.depth()).as("agentId=[%s] → depth 0", agentId).isZero();
            assertThat(who.parentAgentId()).as("⛔ 不得自环（parent == agentId）").isNull();
            assertThat(who.category()).isEqualTo(UsageCategory.SIDE_CALL);
        }
    }

    @Test
    @DisplayName("无会话上下文 → UNKNOWN 归类（不伪造 sessionId）· 用途 label 保留（否则前端只显示「未归类」）")
    void standaloneHasNoSession() {
        UsageAttribution who = LlmSideCallClient.SideCallUsage
            .standalone(UsageCategory.SIDE_CALL, "vision-analyze").attribution(null);

        assertThat(who.sessionId()).isNull();
        assertThat(who.category()).as("无会话 → UNKNOWN（既有语义不变）").isEqualTo(UsageCategory.UNKNOWN);
        assertThat(who.label()).as("用途名必须留下（前端据此显示「未归类 · 图片分析」）").isEqualTo("vision-analyze");
    }

    /** 防御：AgentState 的 agentId 是 UUID（调用点形态来源）——归一后必须等于其 hex 渲染。 */
    @Test
    @DisplayName("调用点形态（AgentState.agentId().toString()）归一后 == 该 agent 的 hex 渲染")
    void agentStateFormMatchesHex() {
        AgentState state = new AgentState("sys", SESSION, AgentContext.packAgentId(HEX));

        UsageAttribution who = LlmSideCallClient.SideCallUsage.inSession(
            state.sessionId(), state.agentId().toString(), UsageCategory.SIDE_CALL, "tool-use-summary")
            .attribution(null);

        assertThat(who.agentId()).isEqualTo(HEX);
    }
}
