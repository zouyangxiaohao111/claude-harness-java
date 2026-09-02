package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session S2] SubagentToolPrompt.getPrompt 移植对齐测试 · CC prompt.ts:66-287。
 *
 * <p><b>WHY (CLAUDE.md 规则九)</b>: CC {@code prompt()} 是 LLM 看到 Agent 工具使用指南的唯一通道
 * （agent 列表 / fork 语义 / when NOT to use / usage notes / examples）。Java 端此前未覆写
 * {@code Tool.prompt()} → 继承 default null（Tool.java:541）→ LLM 看不到任何 Agent 指南 →
 * 在不该 spawn 时 spawn / 不知道有哪些 agent 可用（探查 §8.3 第 1 条）。测试验证意图：prompt
 * 必须非 null 且包含 CC 语义分段，否则 LLM 指南缺失的根因未修。
 */
@DisplayName("Session S2 · SubagentToolPrompt.getPrompt 移植对齐 CC prompt.ts:66-287")
class SubagentToolPromptTest {

    /**
     * 构造最小 Agent 列表（无需 Spring 容器）。
     */
    private static List<AgentDefinition> agents() {
        return List.of(BuiltInAgents.GENERAL_PURPOSE_AGENT, BuiltInAgents.EXPLORE_AGENT);
    }

    @Test
    @DisplayName("prompt 非 null 且含 agent 列表行 + usage notes + when NOT to use (非 fork)")
    void prompt_returnsNonNullContainingAgentList_whenNotFork() {
        // GIVEN: 非 fork、非 coordinator、非 attachment、非 pro
        SubagentToolPrompt.PromptOptions opts = SubagentToolPrompt.PromptOptions.of(false, false, false, false);

        // WHEN
        String prompt = SubagentToolPrompt.getPrompt(agents(), false, null, opts);

        // THEN: 非 null；含 agent 列表行 + when NOT to use + usage notes
        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("Available agent types and the tools they have access to:");
        assertThat(prompt).contains("- general-purpose:");
        assertThat(prompt).contains("When NOT to use the Agent tool:");
        assertThat(prompt).contains("Usage notes:");
        // CC prompt.ts:216-218: 非 coordinator 走完整版（含 when NOT to use）
        assertThat(prompt).doesNotContain("Available agent types are listed in <system-reminder>");
    }

    @Test
    @DisplayName("fork gate on → 含 When to fork 段 + writing the prompt + forkExamples")
    void prompt_returnsForkSection_whenForkEnabled() {
        // GIVEN: forkEnabled=true, 非 coordinator
        SubagentToolPrompt.PromptOptions opts = SubagentToolPrompt.PromptOptions.of(true, false, false, false);

        // WHEN
        String prompt = SubagentToolPrompt.getPrompt(agents(), false, null, opts);

        // THEN: fork 段 + writing the prompt + forkExamples（CC prompt.ts:80-97, 99-113, 115-154）
        assertThat(prompt).contains("## When to fork");
        assertThat(prompt).contains("## Writing the prompt");
        assertThat(prompt).contains("Example usage:");
        assertThat(prompt).contains("When spawning a fresh agent (with a `subagent_type`), it starts with zero context.");
        // 非 fork 的 when NOT to use 段不出现（CC prompt.ts:232-234 forkEnabled ? ''）
        assertThat(prompt).doesNotContain("When NOT to use the Agent tool:");
    }

    @Test
    @DisplayName("formatAgentLine 注入 '- {type}: {whenToUse} (Tools: ...)' (CC prompt.ts:43-46)")
    void prompt_includesFormattedAgentLines() {
        // GIVEN: 非 fork、非 attachment
        SubagentToolPrompt.PromptOptions opts = SubagentToolPrompt.PromptOptions.of(false, false, false, false);

        // WHEN
        String prompt = SubagentToolPrompt.getPrompt(agents(), false, null, opts);

        // THEN: 每行 "- {type}: {whenToUse} (Tools: ...)"（CC prompt.ts:198-199 agentListSection）
        assertThat(prompt).contains("- general-purpose: ");
        assertThat(prompt).contains("(Tools: ");
        // general-purpose tools=['*'] → allowlist 分支 tools.join(', ') = "*"（CC prompt.ts:28-30）
        assertThat(prompt).contains("- general-purpose: " + BuiltInAgents.GENERAL_PURPOSE_AGENT.whenToUse() + " (Tools: *)");
    }

    @Test
    @DisplayName("coordinator → slim shared 段（无 when NOT to use / usage notes / examples）")
    void prompt_coordinator_returnsSlimShared() {
        // GIVEN: isCoordinator=true（CC prompt.ts:216-218 只返回 shared）
        SubagentToolPrompt.PromptOptions opts = SubagentToolPrompt.PromptOptions.of(false, false, false, false);

        // WHEN
        String prompt = SubagentToolPrompt.getPrompt(agents(), true, null, opts);

        // THEN: 只含 shared 段，无完整版三段
        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("Launch a new agent to handle complex, multi-step tasks autonomously.");
        assertThat(prompt).contains("Available agent types and the tools they have access to:");
        assertThat(prompt).doesNotContain("When NOT to use the Agent tool:");
        assertThat(prompt).doesNotContain("Usage notes:");
        assertThat(prompt).doesNotContain("Example usage:");
    }

    @Test
    @DisplayName("SubagentTool.prompt() 覆写非 null (Tool.java:541 default null 被覆盖)")
    void subagentTool_prompt_override_isNonNull() {
        // WHY: 静态 getPrompt 直接调用验证移植正确，但还须验证 SubagentTool 覆写了 Tool.prompt()
        // （否则 StreamingToolExecutor 走 default null → LLM 仍看不到指南）。此测试对 null 变异 RED。
        // GIVEN: 默认构造（agentRegistry 含 getBuiltInAgents() 动态列表）
        SubagentTool tool = new SubagentTool();

        // WHEN
        String prompt = tool.prompt();

        // THEN: 非 null 且含 shared 段（ToolRegistry.toOpenAiToolsArray:424 用 prompt() 作 description）
        assertThat(prompt).isNotNull();
        assertThat(prompt).contains("Launch a new agent to handle complex, multi-step tasks autonomously.");
        assertThat(prompt).contains("Available agent types and the tools they have access to:");
    }

    @Test
    @DisplayName("allowedAgentTypes 过滤 (CC prompt.ts:72-74)")
    void prompt_filtersByAllowedAgentTypes() {
        // GIVEN: allowedAgentTypes 只允许 general-purpose
        SubagentToolPrompt.PromptOptions opts = SubagentToolPrompt.PromptOptions.of(false, false, false, false);

        // WHEN
        String prompt = SubagentToolPrompt.getPrompt(agents(), false, List.of("general-purpose"), opts);

        // THEN: agent 列表只含 general-purpose，不含 Explore
        assertThat(prompt).contains("- general-purpose:");
        assertThat(prompt).doesNotContain("- Explore:");
    }
}
