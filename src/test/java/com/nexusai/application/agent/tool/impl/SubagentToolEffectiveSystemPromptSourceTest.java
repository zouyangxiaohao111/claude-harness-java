package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.prompt.AgentToolSection;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-SUB] {@link SubagentTool#getEffectiveSystemPrompt} 新源验证。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: 4 个消费点 doExecute fork fallback(:990)/
 * executeSync(:1392)/executeAsync(:1564)/降级路径(:1654) 全部经 {@code getEffectiveSystemPrompt}
 * 装配子代理 system prompt。伪真源删除后该 seam 的 base 必须指向 {@link AgentToolSection#get()}
 * （CC getAgentToolSection prompts.ts:319 非 fork 变体），且伪真源文本
 * （{@code 'You are a coding agent at {workdir}... Do not delegate further.'}，CC 无对应）不得残留。
 * 若该 seam 仍返回旧文本，则 4 消费点行为全部错位。
 */
@DisplayName("[IMP-SP-SUB] SubagentTool.getEffectiveSystemPrompt 新源 (AgentToolSection, 非伪真源)")
class SubagentToolEffectiveSystemPromptSourceTest {

    @Test
    @DisplayName("effectiveSystemPrompt 尾部 == AgentToolSection 非 fork 文本, 且不含伪真源文本")
    void effectivePromptTail_isAgentToolSection_nonFork() throws Exception {
        // GIVEN: 最小 SubagentTool（无 Spring 注入）+ 真实 built-in agent 定义
        SubagentTool tool = new SubagentTool(null, null, null, null, "gpt-4", "",
            null, Path.of("."), null);
        AgentDefinition agent = BuiltInAgents.GENERAL_PURPOSE_AGENT;

        // WHEN: 反射调私有 seam getEffectiveSystemPrompt
        Method m = SubagentTool.class.getDeclaredMethod("getEffectiveSystemPrompt", AgentDefinition.class);
        m.setAccessible(true);
        String effective = (String) m.invoke(tool, agent);

        // THEN: 尾部 == "\n\n" + AgentToolSection.get()（新源；agent prompt 自身在前）
        assertThat(effective)
            .as("4 消费点共享的 effectiveSystemPrompt 尾部必须是 AgentToolSection 非 fork 文本")
            .endsWith("\n\n" + AgentToolSection.get())
            .contains(agent.getSystemPrompt(null, List.of()));
        // 伪真源文本不残留（CC 无 'coding agent at / Do not delegate further'）
        assertThat(effective)
            .as("伪真源文本不得残留（CC 无对应 base）")
            .doesNotContain("Do not delegate further")
            .doesNotContain("coding agent at");
    }
}
