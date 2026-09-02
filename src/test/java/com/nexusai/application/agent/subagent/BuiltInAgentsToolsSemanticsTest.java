package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S1 P0-2] BuiltInAgents 工具语义反转测试 (白名单 → 黑名单).
 *
 * <p>CC Explore/Plan/verification 三个 agent <b>无 tools 字段</b> (=undefined=全部工具),
 * 用 disallowedTools 黑名单 [Agent,ExitPlanMode,Edit,Write,NotebookEdit] 减 5.
 * Java 旧实现 tools 白名单仅 3 工具 (Read/Glob/Grep 或 Read/Bash/Grep) 严重阉割能力.
 */
class BuiltInAgentsToolsSemanticsTest {

    private static final List<String> CC_DISALLOWED =
        List.of("Agent", "ExitPlanMode", "Edit", "Write", "NotebookEdit");

    @Test
    @DisplayName("Explore: tools=empty + disallowedTools 5 项 (对齐 CC exploreAgent.ts:64-83)")
    void explore_agent_uses_disallowedTools_not_tools_whitelist() {
        // WHY: CC Explore 无 tools 字段(=全部), 用 disallowedTools 减 5 (含 Bash/WebFetch/WebSearch).
        // Java 旧白名单仅 3 工具, 能力被阉割.
        AgentDefinition explore = BuiltInAgents.EXPLORE_AGENT;
        assertThat(explore.tools()).isEmpty();
        assertThat(explore.disallowedTools()).hasValue(CC_DISALLOWED);
    }

    @Test
    @DisplayName("Plan: tools=empty + disallowedTools (对齐 CC planAgent.ts:85 tools=EXPLORE_AGENT.tools=undefined)")
    void plan_agent_uses_disallowedTools_inherits_explore_tools() {
        AgentDefinition plan = BuiltInAgents.PLAN_AGENT;
        assertThat(plan.tools()).isEmpty();
        assertThat(plan.disallowedTools()).hasValue(CC_DISALLOWED);
    }

    @Test
    @DisplayName("verification: color=red / background=true / model=inherit / criticalSystemReminder 非空")
    void verification_agent_has_color_red_background_true_criticalSystemReminder() {
        // WHY: CC verificationAgent.ts:137 color='red', :138 background=true, :148 model='inherit',
        // :150-151 criticalSystemReminder_EXPERIMENTAL 非空.
        AgentDefinition v = BuiltInAgents.VERIFICATION_AGENT;
        assertThat(v.color()).hasValue("red");
        assertThat(v.background()).hasValue(true);
        assertThat(v.model()).hasValue("inherit");
        assertThat(v.criticalSystemReminder_EXPERIMENTAL()).isPresent();
        assertThat(v.criticalSystemReminder_EXPERIMENTAL().get()).contains("VERIFICATION-ONLY");
        assertThat(v.tools()).isEmpty();
        assertThat(v.disallowedTools()).hasValue(CC_DISALLOWED);
    }

    @Test
    @DisplayName("Explore: model=haiku (外部用户) + omitClaudeMd=true")
    void explore_agent_has_model_haiku_omitClaudeMd_true() {
        // WHY: CC exploreAgent.ts:78 实际行为三元 process.env.USER_TYPE==='ant'?'inherit':'haiku',
        // Java 无 USER_TYPE 概念, 按外部用户场景取 'haiku'; :81 omitClaudeMd=true.
        AgentDefinition explore = BuiltInAgents.EXPLORE_AGENT;
        assertThat(explore.model()).hasValue("haiku");
        assertThat(explore.omitClaudeMd()).hasValue(true);
    }

    @Test
    @DisplayName("Plan: model=inherit + omitClaudeMd=true (对齐 CC planAgent.ts:87/:90)")
    void plan_agent_has_model_inherit_omitClaudeMd_true() {
        AgentDefinition plan = BuiltInAgents.PLAN_AGENT;
        assertThat(plan.model()).hasValue("inherit");
        assertThat(plan.omitClaudeMd()).hasValue(true);
    }

    @Test
    @DisplayName("statusline-setup: tools=['Read','Edit'] + model=sonnet + color=orange (对齐 CC statuslineSetup.ts:138-142)")
    void statusline_setup_agent_has_model_sonnet_color_orange() {
        AgentDefinition s = BuiltInAgents.STATUSLINE_SETUP_AGENT;
        assertThat(s.tools()).hasValue(List.of("Read", "Edit"));
        assertThat(s.model()).hasValue("sonnet");
        assertThat(s.color()).hasValue("orange");
    }
}
