package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.tool.AgentToolConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-SUB] AgentToolSection 真源测试 · 对齐 CC {@code getAgentToolSection}
 * （Open-ClaudeCode/src/constants/prompts.ts:316-320）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: 伪真源（CC 无 code.py/SUB_SYSTEM，grep 自验
 * 0 命中）曾把 {@code 'You are a coding agent at {workdir}... Do not delegate further.'} 拼进每个
 * 子代理 system prompt，污染 LLM 可见提示。新源必须是 CC 真源文本<b>逐字</b>（非 fork 变体
 * prompts.ts:319）且 {@code ${AGENT_TOOL_NAME}} 插值为 {@code 'Agent'}（CC AgentTool/constants.ts:1）；
 * 若文本漂移则子代理提示尾部偏离 CC。
 *
 * <p><b>双分支保结构</b>: {@link AgentToolSection#get(boolean)} 保留 fork 变体（prompts.ts:318）
 * 供未来 fork gate 接线切换；当前调用方统一走非 fork 变体（OPD-SP-23 待确认）。
 */
@DisplayName("[IMP-SP-SUB] AgentToolSection 对齐 CC getAgentToolSection (prompts.ts:316-320)")
class AgentToolSectionTest {

    /** CC prompts.ts:319 非 fork 变体逐字文本（AGENT_TOOL_NAME 插值占位 %s）。 */
    private static final String CC_NON_FORK_TEMPLATE =
        "Use the %s tool with specialized agents when the task at hand matches the agent's description. "
        + "Subagents are valuable for parallelizing independent queries or for protecting the main "
        + "context window from excessive results, but they should not be used excessively when not needed. "
        + "Importantly, avoid duplicating work that subagents are already doing - if you delegate research "
        + "to a subagent, do not also perform the same searches yourself.";

    /** CC prompts.ts:318 fork 变体逐字文本（AGENT_TOOL_NAME 插值占位 %s）。 */
    private static final String CC_FORK_TEMPLATE =
        "Calling %s without a subagent_type creates a fork, which runs in the background and keeps its "
        + "tool output out of your context — so you can keep chatting with the user while it works. "
        + "Reach for it when research or multi-step implementation work would otherwise fill your context "
        + "with raw output you won't need again. **If you ARE the fork** — execute directly; do not re-delegate.";

    @Test
    @DisplayName("get() == 非 fork 变体逐字对齐 CC prompts.ts:319（AGENT_TOOL_NAME='Agent' 插值）")
    void get_returnsNonForkCcText_withAgentNameInterpolated() {
        String expected = String.format(CC_NON_FORK_TEMPLATE, AgentToolConstants.AGENT_TOOL_NAME);

        assertThat(AgentToolSection.get())
            .as("非 fork 变体必须与 CC prompts.ts:319 逐字一致")
            .isEqualTo(expected);
        assertThat(AgentToolSection.get())
            .as("AGENT_TOOL_NAME 插值 = 'Agent'（CC AgentTool/constants.ts:1）")
            .contains("Use the " + AgentToolConstants.AGENT_TOOL_NAME + " tool")
            .contains("do not also perform the same searches yourself.");
    }

    @Test
    @DisplayName("get(true) == fork 变体逐字对齐 CC prompts.ts:318（保结构供未来 fork gate 接线）")
    void getTrue_returnsForkCcText() {
        String expected = String.format(CC_FORK_TEMPLATE, AgentToolConstants.AGENT_TOOL_NAME);

        assertThat(AgentToolSection.get(true))
            .as("fork 变体必须与 CC prompts.ts:318 逐字一致")
            .isEqualTo(expected);
        assertThat(AgentToolSection.get(true))
            .as("fork 变体关键语义: without subagent_type creates a fork + do not re-delegate")
            .contains("without a subagent_type creates a fork")
            .contains("do not re-delegate.");
    }

    @Test
    @DisplayName("get(false) == get()（当前调用方统一非 fork 变体）")
    void getFalse_equalsGet() {
        assertThat(AgentToolSection.get(false))
            .as("get(false) 与无参 get() 语义一致")
            .isEqualTo(AgentToolSection.get());
    }
}
