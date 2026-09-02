package com.nexusai.application.agent.permission.bubble;

import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bubble deny 过滤匹配键 + source 范围对齐 CC 测试 · 对齐 CC
 * {@code utils/permissions/permissions.ts:307-343} {@code getDenyRuleForAgent} /
 * {@code filterDeniedAgents}。
 *
 * <p><b>WHY (意图验证)</b>: 旧实现 {@code getDenyRuleForAgent} 只在 SESSION source 中按
 * {@code agentId == ruleValue.toolName} 匹配，与 CC 语义两处不符：
 * <ol>
 *   <li>匹配键：CC 是 {@code ruleValue.toolName === agentToolName('Agent') &&
 *       ruleValue.ruleContent === agentType}（Agent(agentType) 语法）；旧实现把 agentId 当 toolName 比。</li>
 *   <li>source 范围：CC 遍历全 8 source（{@code PERMISSION_RULE_SOURCES.flatMap}）；
 *       旧实现只查 SESSION。</li>
 * </ol>
 * 本测试 5 项（T1-T5）锁定这两个语义：匹配键 / 非命中 / 全 source / filter 顺序 / null 守卫。
 */
class PermissionBubbleServiceDenyFilterTest {

    private final PermissionBubbleService service = new PermissionBubbleService();

    /** 构造一条 Agent(agentType) 的 deny 规则（CC Agent(x) 语法）。 */
    private static PermissionRule denyAgentRule(PermissionRuleSource source, String agentType) {
        return new PermissionRule(
            source,
            PermissionBehavior.DENY,
            PermissionRuleValue.withContent("Agent", agentType));
    }

    /** 构造只有指定 deny 规则桶的 ToolPermissionContext。 */
    private static ToolPermissionContext ctx(Map<PermissionRuleSource, Set<PermissionRule>> denyRules) {
        return new ToolPermissionContext(
            PermissionMode.DEFAULT,
            Map.of(), denyRules, Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
    }

    private static Map<PermissionRuleSource, Set<PermissionRule>> rules(
            PermissionRuleSource source, PermissionRule... rules) {
        EnumMap<PermissionRuleSource, Set<PermissionRule>> m =
            new EnumMap<>(PermissionRuleSource.class);
        m.put(source, Set.of(rules));
        return m;
    }

    @Test
    @DisplayName("T1 匹配键：Agent(Explore) 命中 getDenyRuleForAgent(\"Explore\")")
    void matchingKeyHitsByRuleContentEqualsAgentType() {
        ToolPermissionContext ctx = ctx(rules(
            PermissionRuleSource.SESSION, denyAgentRule(PermissionRuleSource.SESSION, "Explore")));

        PermissionRule rule = service.getDenyRuleForAgent("Explore", ctx);

        assertThat(rule).as("ruleValue.toolName=='Agent' && ruleValue.ruleContent=='Explore' → 必须命中").isNotNull();
        assertThat(rule.ruleValue().toolName()).isEqualTo("Agent");
        assertThat(rule.ruleValue().ruleContent()).isEqualTo("Explore");
        assertThat(rule.source()).isEqualTo(PermissionRuleSource.SESSION);
    }

    @Test
    @DisplayName("T2 非命中：agentType 不匹配 / 非 Agent 工具 / 无 ruleContent 均返回 null")
    void nonMatchReturnsNull() {
        ToolPermissionContext ctx = ctx(Map.of(
            PermissionRuleSource.SESSION,
            Set.of(
                denyAgentRule(PermissionRuleSource.SESSION, "Explore"),
                // 旧 legacy 工具名 Task(Explore) 不应用 Agent 键命中（CC 只匹配 toolName==='Agent'）
                new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
                    PermissionRuleValue.withContent("Task", "Explore")),
                // 整工具规则 Agent（无 ruleContent）不命中 agent 级过滤
                new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
                    PermissionRuleValue.wholeTool("Agent")))));

        assertThat(service.getDenyRuleForAgent("Plan", ctx))
            .as("ruleContent != agentType → null").isNull();
        assertThat(service.getDenyRuleForAgent("Explore", ctx))
            .as("仍按 Agent(Explore) 命中").isNotNull();
    }

    @Test
    @DisplayName("T3 全 source：非 SESSION source 的 deny 规则也参与匹配（含 source 顺序语义）")
    void allEightSourcesParticipate() {
        // POLICY_SETTINGS（第 5 source）中的 Agent(Explore) 必须命中 —— 旧实现只查 SESSION 会 miss
        ToolPermissionContext ctx = ctx(Map.of(
            PermissionRuleSource.POLICY_SETTINGS,
            Set.of(denyAgentRule(PermissionRuleSource.POLICY_SETTINGS, "Explore")),
            PermissionRuleSource.USER_SETTINGS,
            Set.of(denyAgentRule(PermissionRuleSource.USER_SETTINGS, "Explore"))));

        PermissionRule rule = service.getDenyRuleForAgent("Explore", ctx);

        assertThat(rule).as("POLICY_SETTINGS 的 deny 规则必须命中（非 SESSION-only）").isNotNull();
        // source 顺序语义：USER_SETTINGS 在 POLICY_SETTINGS 之前，.find 应返回更早 source
        assertThat(rule.source())
            .as("CC .find 首个命中按 PERMISSION_RULE_SOURCES 顺序 → USER_SETTINGS 先于 POLICY_SETTINGS")
            .isEqualTo(PermissionRuleSource.USER_SETTINGS);

        // filterDeniedAgents 同样命中（跨 source 预计算）
        assertThat(service.filterDeniedAgents(List.of("Explore", "Plan"), ctx))
            .as("filter 必须跨全 source 过滤掉被 deny 的 Explore")
            .containsExactly("Plan");
    }

    @Test
    @DisplayName("T4 filter 顺序：预计算 Set 后过滤，保持输入顺序")
    void filterPreservesOrder() {
        ToolPermissionContext ctx = ctx(Map.of(
            PermissionRuleSource.CLI_ARG,
            Set.of(
                denyAgentRule(PermissionRuleSource.CLI_ARG, "Explore"),
                denyAgentRule(PermissionRuleSource.CLI_ARG, "Plan"))));

        List<String> result = service.filterDeniedAgents(
            List.of("Explore", "Plan", "Explore2", "GeneralPurpose"), ctx);

        assertThat(result)
            .as("被 deny 的 Explore/Plan 过滤掉，剩余保持原顺序")
            .containsExactly("Explore2", "GeneralPurpose");
    }

    @Test
    @DisplayName("T5 null 守卫：null agentType / null ctx / null 或空列表安全返回")
    void nullGuards() {
        ToolPermissionContext ctx = ctx(Map.of());

        assertThat(service.getDenyRuleForAgent(null, ctx)).isNull();
        assertThat(service.getDenyRuleForAgent("Explore", null)).isNull();
        assertThat(service.filterDeniedAgents(null, ctx)).isEmpty();
        assertThat(service.filterDeniedAgents(List.of(), ctx)).isEmpty();
        assertThat(service.filterDeniedAgents(List.of("Explore"), null)).isEmpty();
    }
}
