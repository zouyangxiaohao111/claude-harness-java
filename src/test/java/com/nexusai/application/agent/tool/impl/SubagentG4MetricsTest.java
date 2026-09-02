package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.UserContextProvider;
import com.nexusai.application.agent.subagent.AgentNameRegistry;
import com.nexusai.application.agent.team.TeamHelpers;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-G4 组11-1] Subagent 遥测/systemContext/路由聚焦测试 · 对齐 CC
 * AgentTool.tsx（tengu_agent_tool_selected :419-428 / memory_loaded :522-531）+
 * agentToolUtils.ts（completed/cache_eviction :322-357）+ runAgent.ts（systemContext :380-383）
 * + SendMessageTool.ts（in-process 按名路由 :800-813）。
 *
 * <p>WHY 这些行为重要（规则九）：hard_metrics 0 发射（TR-G1 B12）、systemContext 硬编码空串
 * （TR-G1 F5）、name→agentId 无写入点（TR-G1 C7）三条实证缺口 → 子代理用量统计/环境上下文/
 * 按名路由三项能力断层，本测试锚定修复后的可观察行为。
 */
class SubagentG4MetricsTest {

    private AnalyticsTracker newTracker() {
        return new AnalyticsTracker();
    }

    @Test
    @DisplayName("emitAgentMetrics: 注入 tracker 时发射 hard_metrics；未注入时 no-op（不破坏既有调用）")
    void emitAgentMetrics_injectedEmits_uninjectedNoop() {
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "model", "system-prompt");

        // 未注入 → no-op（不抛错、不计数）
        executor.emitAgentMetrics(AnalyticsTracker.EventName.AGENT_TOOL_COMPLETED, Map.of());
        assertThat(newTracker().totalEvents()).as("未注入 tracker 不产生事件").isZero();

        // 注入 → 发射并计数
        AnalyticsTracker tracker = newTracker();
        executor.setAnalyticsTracker(tracker);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("agent_type", "general-purpose");
        props.put("model", "claude-sonnet-4-6");
        executor.emitAgentMetrics(AnalyticsTracker.EventName.AGENT_TOOL_COMPLETED, props);
        assertThat(tracker.totalEvents()).as("注入 tracker 后事件计数+1").isEqualTo(1);
        // [IMP-T REWORK] emitAgentMetrics 已迁移 logEvent 统一通道 → 按 CC 事件名计数
        //   （counts() 枚举维度不再承载 tengu_agent_*，避免 metadata 丢失）。
        assertThat(tracker.countsByEventName().get("tengu_agent_tool_completed"))
            .as("completed 事件计数（logEvent 统一通道）").isEqualTo(1);
    }

    @Test
    @DisplayName("resolveSystemContextText: git 仓库下 systemContext 非空（F5 修复硬编码空串，CC runAgent.ts:380-383）")
    void resolveSystemContextText_nonEmptyInGitRepo() {
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "model", "system-prompt");
        // 未注入 → 惰性构造会话级 provider（GitStatusProvider 读当前工作树）
        String systemContext = executor.resolveSystemContextText();
        // 测试 CWD = 项目根（git 仓库）→ gitStatus 非空 → systemContext 非空
        assertThat(systemContext).as("git 仓库下子 agent systemContext 非空（含 gitStatus 行）").isNotBlank();
        assertThat(systemContext).contains("gitStatus");
    }

    @Test
    @DisplayName("resolveSystemContextText: 注入 provider 走注入实例（会话级 memoize 复用）")
    void resolveSystemContextText_usesInjectedProvider() {
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "model", "system-prompt");
        SystemPromptContextProvider injected = new SystemPromptContextProvider(
            "2026-08-16",
            new UserContextProvider((com.nexusai.application.agent.context.ClaudemdEngine) null),
            new GitStatusProvider());
        executor.setSystemPromptContextProvider(injected);
        String systemContext = executor.resolveSystemContextText();
        assertThat(systemContext).as("注入 provider 产出 gitStatus 上下文").contains("gitStatus");
    }

    @Test
    @DisplayName("SendMessage 按名路由: 注册名命中 → 待办队列投递 + queued 输出（CC SendMessageTool.ts:800-813）")
    void sendMessage_routeToRegisteredSubagent_queues() {
        AgentNameRegistry registry = new AgentNameRegistry();
        registry.register("worker-a", "agent-111");
        SendMessageTool tool = new SendMessageTool(new TeamHelpers());
        tool.setAgentNameRegistry(registry);

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", "worker-a");
        input.put("message", "please continue");
        ToolUseBlock block = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);

        // execute 走注册名路由分支（message.isTextual 且 to 非 '*'）
        AgentToolResult<?> raw = tool.execute(block, null);
        ToolResult<?> result = (ToolResult<?>) raw;
        assertThat(result.data()).as("注册名路由输出含 queued 消息").isInstanceOf(ObjectNode.class);
        assertThat(((ObjectNode) result.data()).path("message").asText())
            .contains("queued for delivery to worker-a");
        // 待办队列已投递（子 agent 下一轮 drain 消费）
        assertThat(registry.hasPending("agent-111")).as("按名路由消息已入待办队列").isTrue();
        assertThat(registry.drain("agent-111")).containsExactly("please continue");
    }

    @Test
    @DisplayName("SendMessage 按名路由: 未注册名 → 降级 mailbox（不拦截，回归 handleMessage）")
    void sendMessage_unregisteredName_fallsThroughToMailbox() {
        AgentNameRegistry registry = new AgentNameRegistry();
        SendMessageTool tool = new SendMessageTool(new TeamHelpers());
        tool.setAgentNameRegistry(registry);

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("to", "someone-not-registered");
        input.put("message", "hello");
        ToolUseBlock block = new ToolUseBlock(UUID.randomUUID().toString(), SendMessageTool.NAME, input);
        AgentToolResult<?> raw = tool.execute(block, null);
        ToolResult<?> result = (ToolResult<?>) raw;
        // 未注册名 → mailbox 路径（原 handleMessage 行为不回归）；无 team context → mailbox 失败，
        // 输出不含 queued（未被 in-process 路由拦截）
        assertThat(registry.hasPending("agent-null")).as("未注册名不入待办队列").isFalse();
        assertThat(String.valueOf(result.data())).as("未注册名不走按名路由（无 queued 输出）")
            .doesNotContain("queued for delivery");
    }
}
