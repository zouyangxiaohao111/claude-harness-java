package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [FIX-AM REQ-M-19] SubagentExecutor.resolveAgentDefinition 自定义 agent 解析器核验。
 *
 * <p>生产接线：SubagentTool 3 个 executor 构造点注入 {@code agentRegistry::findAgent}
 * （对齐 CC AgentTool.tsx:286 {@code activeAgents.find}）。本测试直接注入 resolver 验证
 * resolveAgentDefinition 先查 resolver（自定义 memory agent 可达）再回退 BuiltInAgents。
 */
class SubagentExecutorResolverTest {

    @Test
    @DisplayName("setAgentDefinitionResolver 命中自定义 agent（含 memory 字段）")
    void resolver_lookup_returns_custom_agent() throws Exception {
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "gpt-4", "sys", null);
        AgentDefinition custom = AgentDefinition.CustomAgentDefinition.builder(
            "my-custom", "desc", "userSettings", "prompt").memory("user").build();
        executor.setAgentDefinitionResolver(type -> "my-custom".equals(type) ? custom : null);

        Method m = SubagentExecutor.class.getDeclaredMethod("resolveAgentDefinition", String.class);
        m.setAccessible(true);
        AgentDefinition resolved = (AgentDefinition) m.invoke(executor, "my-custom");
        assertThat(resolved).isSameAs(custom);
        assertThat(resolved.memory()).hasValue("user");
    }

    @Test
    @DisplayName("resolver 未命中 → 回退 BuiltInAgents（不破坏内置解析）")
    void resolver_miss_falls_back_to_builtin() throws Exception {
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "gpt-4", "sys", null);
        executor.setAgentDefinitionResolver(type -> null); // 不命中任何自定义

        Method m = SubagentExecutor.class.getDeclaredMethod("resolveAgentDefinition", String.class);
        m.setAccessible(true);
        AgentDefinition resolved = (AgentDefinition) m.invoke(executor, BuiltInAgents.GENERAL_PURPOSE);
        assertThat(resolved).isNotNull();
    }
}
