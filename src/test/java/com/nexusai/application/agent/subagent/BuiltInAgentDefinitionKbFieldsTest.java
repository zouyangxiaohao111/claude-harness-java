package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [KB 内置 Agent 对齐 · 阶段 A] {@code agentMode} / {@code kbAgentType} 两个本仓扩展字段.
 *
 * <p><b>WHY 单独立 kbAgentType 而不复用 agentType</b>：本仓 {@code agentType} 是注册表主键
 * （{@code AgentDefinitionRegistry:117/169} 做 Map key，{@code SubagentTool:2254} 按它查 Agent，
 * {@code SessionService:287} 判空即拒绝），塞源站的 {@code rag-qa} 会破坏所有查找。
 * 故新字段独立命名，{@code agentType} 保持 CC 语义（身份标识）。
 *
 * <p><b>WHY kbAgentType 只存不消费</b>（用户 2026-11-10 选 A）：实证源站四个
 * {@code AgentType*} 常量在 Go 代码里零引用，运行时 KB 过滤只读
 * {@code agentMode + allowedTools}（{@code capabilities.go:202}），
 * 该字段真实用途是编辑器表单预填种子。
 */
class BuiltInAgentDefinitionKbFieldsTest {

    @Test
    @DisplayName("agentMode / kbAgentType 未设置时缺省 empty（不影响现有 6 个内置 agent）")
    void unset_yields_empty() {
        AgentDefinition def = AgentDefinition.BuiltInAgentDefinition
            .builder("general-purpose", "when", (ctx, dirs) -> "prompt")
            .build();

        assertThat(def.agentMode()).isEmpty();
        assertThat(def.kbAgentType()).isEmpty();
    }

    @Test
    @DisplayName("Builder 可设置 agentMode / kbAgentType 且可回读")
    void builder_sets_and_reads_back() {
        AgentDefinition def = AgentDefinition.BuiltInAgentDefinition
            .builder("builtin-smart-reasoning", "when", (ctx, dirs) -> "prompt")
            .agentMode("smart-reasoning")
            .kbAgentType("rag-qa")
            .build();

        assertThat(def.agentMode()).hasValue("smart-reasoning");
        assertThat(def.kbAgentType()).hasValue("rag-qa");
        // 关键：agentType 仍是注册表主键，未被 rag-qa 污染
        assertThat(def.agentType()).isEqualTo("builtin-smart-reasoning");
    }

    @Test
    @DisplayName("quick-answer 模式只设 agentMode，kbAgentType 保持 empty（源站：quick-answer 下 agent_type 被忽略）")
    void quick_answer_ignores_agent_type() {
        AgentDefinition def = AgentDefinition.BuiltInAgentDefinition
            .builder("builtin-quick-answer", "when", (ctx, dirs) -> "prompt")
            .agentMode("quick-answer")
            .build();

        assertThat(def.agentMode()).hasValue("quick-answer");
        assertThat(def.kbAgentType()).as("quick-answer 下 agent_type 无意义，源站 EnsureDefaults 也不填")
            .isEmpty();
    }

    @Test
    @DisplayName("record 直接构造时两个新字段传 null → accessor 返回 empty（不 NPE）")
    void null_components_safe() {
        // WHY: ForkSubagentAgentDefinition 用 canonical 构造器逐字段传值；若某调用点
        // 图省事传 null，不能让 accessor 抛 NPE —— 接口契约是 Optional。
        AgentDefinition.BuiltInAgentDefinition def = new AgentDefinition.BuiltInAgentDefinition(
            "raw", "w",
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
            (m, d) -> "p", java.util.Optional.empty(),
            null, null
        );

        assertThat(def.agentMode()).isEmpty();
        assertThat(def.kbAgentType()).isEmpty();
    }

    @Test
    @DisplayName("现有 6 个内置 agent 全部不声明新字段（本阶段零行为变化）")
    void existing_builtins_unaffected() {
        List<AgentDefinition> existing = List.of(
            BuiltInAgents.GENERAL_PURPOSE_AGENT,
            BuiltInAgents.STATUSLINE_SETUP_AGENT,
            BuiltInAgents.EXPLORE_AGENT,
            BuiltInAgents.PLAN_AGENT,
            BuiltInAgents.VERIFICATION_AGENT,
            BuiltInAgents.CLAUDE_CODE_GUIDE_AGENT
        );

        for (AgentDefinition def : existing) {
            assertThat(def.agentMode())
                .as("%s 不应声明 agentMode（CC 对齐 agent 不走源站 kb 语义）", def.agentType())
                .isEmpty();
            assertThat(def.kbAgentType())
                .as("%s 不应声明 kbAgentType", def.agentType())
                .isEmpty();
        }
    }
}
