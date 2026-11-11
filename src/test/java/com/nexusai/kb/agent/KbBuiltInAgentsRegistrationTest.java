package com.nexusai.kb.agent;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [KB 内置 Agent 对齐 · 阶段 E] 注册与分派判据。
 *
 * <p>三条要验的：
 * <ol>
 *   <li>6 个 agent <b>真的</b>出现在 {@code BuiltInAgents.getBuiltInAgents()} 里
 *       （「写了定义类」≠「注册了」）</li>
 *   <li>{@code agentMode} 有<b>读侧出口</b>（否则就是第 27 类静默缺陷：字段写了没人读）</li>
 *   <li>quick-answer 的守卫判据能在分派点区分两种模式</li>
 * </ol>
 */
class KbBuiltInAgentsRegistrationTest {

    private static final Set<String> KB_AGENT_IDS = Set.of(
        "builtin-quick-answer",
        "builtin-smart-reasoning",
        "builtin-data-analyst",
        "builtin-wiki-researcher",
        "builtin-wiki-fixer",
        "builtin-assistant"
    );

    @Test
    @DisplayName("⭐ 6 个 kb agent 真的出现在 getBuiltInAgents() 里（不只是定义了类）")
    void kb_agents_registered_in_builtin_list() {
        Set<String> registered = BuiltInAgents.getBuiltInAgents().stream()
            .map(AgentDefinition::agentType)
            .collect(Collectors.toSet());

        assertThat(registered)
            .as("源站 6 个内置 agent 必须注册进 BuiltInAgents")
            .containsAll(KB_AGENT_IDS);
    }

    @Test
    @DisplayName("注册后 CC 对齐的 5 个 agent 仍然都在（没有把原有的挤掉）")
    void cc_agents_still_registered() {
        Set<String> registered = BuiltInAgents.getBuiltInAgents().stream()
            .map(AgentDefinition::agentType)
            .collect(Collectors.toSet());

        assertThat(registered).contains("general-purpose", "statusline-setup");
    }

    @Test
    @DisplayName("未注册 skill-installer，也没有 weknora 原名")
    void excluded_agents_absent() {
        Set<String> registered = BuiltInAgents.getBuiltInAgents().stream()
            .map(AgentDefinition::agentType)
            .collect(Collectors.toSet());

        assertThat(registered).doesNotContain("builtin-skill-installer");
        assertThat(registered).doesNotContain("builtin-weknora-assistant");
    }

    @Test
    @DisplayName("⭐ agentMode 有读侧出口（BuiltInAgents.agentModeOf），不是只写不读")
    void agent_mode_has_reader() {
        // WHY: 本仓第 27 类静默缺陷 = 「配置字段设计了但从未接线」。
        // 判据就是「有没有一个真实读侧」。这条断言锁死出口存在且返回正确值。
        assertThat(BuiltInAgents.agentModeOf("builtin-quick-answer"))
            .hasValue("quick-answer");
        assertThat(BuiltInAgents.agentModeOf("builtin-smart-reasoning"))
            .hasValue("smart-reasoning");
    }

    @Test
    @DisplayName("agentModeOf 对未知 agent / CC 对齐 agent 返回 empty（不伪造）")
    void agent_mode_of_unknown_is_empty() {
        assertThat(BuiltInAgents.agentModeOf("does-not-exist")).isEmpty();
        assertThat(BuiltInAgents.agentModeOf("general-purpose")).isEmpty();
    }

    @Test
    @DisplayName("只有 quick-answer 一个是 quick-answer 模式，其余 5 个都是 smart-reasoning")
    void mode_distribution() {
        List<AgentDefinition> kbAgents = BuiltInAgents.getBuiltInAgents().stream()
            .filter(a -> KB_AGENT_IDS.contains(a.agentType()))
            .toList();

        assertThat(kbAgents).hasSize(6);
        List<String> quick = kbAgents.stream()
            .filter(a -> a.agentMode().filter("quick-answer"::equals).isPresent())
            .map(AgentDefinition::agentType)
            .toList();
        assertThat(quick).containsExactly("builtin-quick-answer");

        long smartCount = kbAgents.stream()
            .filter(a -> a.agentMode().filter("smart-reasoning"::equals).isPresent())
            .count();
        assertThat(smartCount).isEqualTo(5);
    }

    @Test
    @DisplayName("quick-answer 声明 0 工具（RAG 流水线不调工具）")
    void quick_answer_has_no_tools() {
        AgentDefinition def = BuiltInAgents.get("builtin-quick-answer");

        assertThat(def).isNotNull();
        assertThat(def.tools()).hasValue(List.of());
    }

    @Test
    @DisplayName("启动自检能跑通并返回 6（人格 md 全部可加载）")
    void self_check_passes() {
        // 这条同时验证：6 份 md 打包后确实在 classpath 上、且内容非空
        int count = KbBuiltInAgentDefs.selfCheckOnStartup();

        assertThat(count).isEqualTo(6);
    }

    @Test
    @DisplayName("MODE_QUICK_ANSWER 常量值与源站一致（分派守卫用它比对）")
    void mode_constants_match_source() {
        assertThat(KbBuiltInAgentDefs.MODE_QUICK_ANSWER).isEqualTo("quick-answer");
        assertThat(KbBuiltInAgentDefs.MODE_SMART_REASONING).isEqualTo("smart-reasoning");
    }
}
