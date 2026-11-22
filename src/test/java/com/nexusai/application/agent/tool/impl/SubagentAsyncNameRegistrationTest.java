package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 工具的名字规则 · 对齐 CC AgentTool（claude.exe 2.1.328 只读字节检索）。
 *
 * <p>锁三件：
 * <ul>
 *   <li>入参 name 的格式与保留名拒绝（schema 广告 + 运行时 {@link SubagentTool#validateAgentName}）；</li>
 *   <li>async 子代理撞名时「先注册者保号，后者不注册」（CC @333963676），
 *       被测方法 = 生产写点 {@link SubagentTool#registerAsyncAgentName}（改前是覆盖 ⇒ 本用例红）。</li>
 * </ul>
 */
class SubagentAsyncNameRegistrationTest {

    @Test
    @DisplayName("async 子代理同名：先注册者保留，后者不注册（spawn 不失败）")
    void asyncDuplicateName_keepsFirstRegistration() {
        AgentNameRegistry registry = new AgentNameRegistry();

        assertThat(SubagentTool.registerAsyncAgentName(registry, "s1", "researcher", "agent-1"))
            .as("第一个 agent 注册成功").isTrue();
        assertThat(SubagentTool.registerAsyncAgentName(registry, "s1", "researcher", "agent-2"))
            .as("同名第二个 agent 不注册（CC registry.get(name)===undefined 守卫）").isFalse();

        assertThat(registry.resolveActiveEntry("s1", "researcher").agentId())
            .as("⛔ 先注册者保号：改前本仓 register() 会覆盖成 agent-2").isEqualTo("agent-1");

        // 不同 owner（会话）不受影响：名字空间按会话隔离
        assertThat(SubagentTool.registerAsyncAgentName(registry, "s2", "researcher", "agent-3"))
            .isTrue();
        assertThat(registry.resolveActiveEntry("s2", "researcher").agentId()).isEqualTo("agent-3");
    }

    @Test
    @DisplayName("name 入参校验：格式（CC Hrl）+ 保留名 / agent-id 形 / session-id 形")
    void validateName_matchesCcRules() {
        assertThat(SubagentTool.validateAgentName(null)).as("未传 name 合法").isNull();
        assertThat(SubagentTool.validateAgentName("researcher")).isNull();
        assertThat(SubagentTool.validateAgentName("teammate"))
            .as("teammate 不是保留名（CC v1 不含）").isNull();
        assertThat(SubagentTool.validateAgentName("a_b-c9")).isNull();

        assertThat(SubagentTool.validateAgentName("ab c"))
            .as("非法字符 ⇒ CC 格式文案").isEqualTo(AgentNameRegistry.NAME_FORMAT_HINT);
        assertThat(SubagentTool.validateAgentName("x".repeat(65)))
            .as("超长 ⇒ CC 格式文案").isEqualTo(AgentNameRegistry.NAME_FORMAT_HINT);
        assertThat(SubagentTool.validateAgentName("*"))
            .as("* 不在字符集内").isEqualTo(AgentNameRegistry.NAME_FORMAT_HINT);

        assertThat(SubagentTool.validateAgentName("main"))
            .as("main 专属文案（CC：SendMessage routes it to the main conversation）")
            .contains("reserved").contains("main conversation");
        assertThat(SubagentTool.validateAgentName("user"))
            .as("user 是保留名").contains("reserved name");
        assertThat(SubagentTool.validateAgentName("a0123456789abcdef"))
            .as("agent id 形被拒").contains("shape of an agent id");
        assertThat(SubagentTool.validateAgentName("123e4567-e89b-12d3-a456-426614174000"))
            .as("session id 形被拒").contains("session id");
    }

    @Test
    @DisplayName("schema 广告：name 带 pattern/maxLength（CC Hrl 同步到模型可见层）")
    void inputSchema_advertisesNamePattern() {
        JsonNode name = new SubagentTool().inputSchema().path("properties").path("name");
        assertThat(name.path("type").asText()).isEqualTo("string");
        assertThat(name.path("pattern").asText())
            .isEqualTo("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
        assertThat(name.path("maxLength").asInt()).isEqualTo(64);
    }
}
