package com.nexusai.application.agent.workflow.registry;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.workflow.AgentRunParams;
import com.nexusai.application.agent.workflow.AgentRunResult;
import com.nexusai.application.agent.workflow.AgentRunResultDead;
import com.nexusai.application.agent.workflow.HostHandle;
import com.nexusai.application.agent.workflow.agent.AgentAdapterContext;
import com.nexusai.application.agent.workflow.agent.AgentAdapterRegistry;
import com.nexusai.application.agent.workflow.agent.ClaudeCodeBackendAdapter;
import com.nexusai.application.agent.workflow.worktree.AgentWorktreeManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Fix-G1] WorkflowRegistry.buildRegistry 生产回落注册 workflow-worker · CC original:
 * {@code buildRegistry} (Open-ClaudeCode/src/workflow/registry.ts:9-13) + {@code WORKFLOW_AGENT}
 * (claudeCodeBackend.ts:36-44)。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：E2E G-1 生产中 adapter 回落
 * {@code WORKFLOW_AGENT}（agentType='workflow-worker'）后，{@code SubagentExecutor.executeStreaming}
 * Step 1 二次按 agentType 解析（SubagentExecutor:1267）。若 workflow-worker 未注册 →
 * {@code resolveAgentDefinition} 返回 null → :1293 抛 {@code AgentNotFoundException}
 * （此前测试手动注册才打通）。本测试锁定「生产注册点 buildRegistry 已注册 workflow-worker」，
 * 使回落路径二次解析必命中——对齐 CC runAgent 直接持有 WORKFLOW_AGENT 定义（runAgent.ts:703）。
 */
@DisplayName("[Fix-G1] WorkflowRegistry.buildRegistry 生产回落注册 workflow-worker")
class WorkflowRegistryTest {

    /** 裸 SubagentExecutor（最小接线；additionalAgentDefinitions 默认 Map.of()，等价 Spring @Bean 初始态）。 */
    private static SubagentExecutor bareExecutor() {
        return new SubagentExecutor(
                new ToolRegistry(), null, null, null, null,
                "gpt-4", "You are a workflow sub-agent.");
    }

    @Test
    @DisplayName("G1.1 生产注册：buildRegistry 把 WORKFLOW_AGENT 注册进 executor → resolveAgentDefinition('workflow-worker') 命中（非 null）")
    void buildRegistry_registersWorkflowWorker_productionFallbackResolves() {
        // WHY：E2E G-1 生产中 workflow-worker 未注册 → executeStreaming :1293 AgentNotFoundException。
        //   本断言锁「buildRegistry 已把 workflow-worker 注册进 executor」= 回落路径二次解析必命中，
        //   AgentNotFoundException 不可能发生。这是 G-1 修复的唯一生产注册点。
        SubagentExecutor executor = bareExecutor();

        AgentAdapterRegistry registry = WorkflowRegistry.buildRegistry(executor, new AgentWorktreeManager());

        assertThat(registry.has("claude-code"))
                .as("buildRegistry 必须注册 claude-code default adapter（registry.ts:9-13）").isTrue();
        AgentDefinition resolved = executor.resolveAgentDefinition("workflow-worker");
        assertThat(resolved)
                .as("生产 buildRegistry 必须注册 workflow-worker（G-1 生产回落，非 AgentNotFoundException）")
                .isNotNull();
        assertThat(resolved.agentType()).as("agentType 必须为 workflow-worker").isEqualTo("workflow-worker");
        assertThat(resolved).as("注册的必须是 CC WORKFLOW_AGENT 同一常量（claudeCodeBackend.ts:36-44）")
                .isSameAs(ClaudeCodeBackendAdapter.WORKFLOW_AGENT);
    }

    @Test
    @DisplayName("G1.2 对照：不经 buildRegistry 注册 → resolveAgentDefinition('workflow-worker') 返回 null（G-1 修复前的缺口）")
    void withoutBuildRegistry_workflowWorkerUnresolvable() {
        // WHY：对照试验证明 G-1 缺口根因——注册前 workflow-worker 不在 additionalAgentDefinitions /
        //   agentDefinitionResolver / BuiltInAgents 任何解析源，executeStreaming :1293 会抛
        //   AgentNotFoundException。修复前后行为差异即本测试验证意图。
        SubagentExecutor executor = bareExecutor();

        assertThat(executor.resolveAgentDefinition("workflow-worker"))
                .as("不经 buildRegistry 注册，workflow-worker 不可解析（= 修复前 AgentNotFoundException 根因）")
                .isNull();
    }

    @Test
    @DisplayName("G1.3 生产路径 adapter.run（不手动注册）：未知 agentType 回落 workflow-worker，二次解析命中（非 AgentNotFoundException）")
    void adapterRun_productionPath_noManualRegistration_noAgentNotFound() {
        // WHY：生产实际路径 = WorkflowPortsImpl 构造 → buildRegistry 注册 → adapter.run
        //   （未知 agentType → 回落 WORKFLOW_AGENT → executeStreaming 二次解析命中）。
        //   不手动 setAdditionalAgentDefinitions —— 全链只依赖 buildRegistry 的 G-1 注册。
        //   裸 executor 无 contextFactory：越过 resolveAgentDefinition 后必在后续步骤失败 →
        //   dead{runagent-threw}；detail 绝不可能是 AgentNotFoundException（'未找到'）——
        //   即 workflow-worker 已命中解析（G-1 目标：生产回落不再 AgentNotFoundException）。
        SubagentExecutor executor = bareExecutor();
        WorkflowRegistry.buildRegistry(executor, new AgentWorktreeManager());
        ClaudeCodeBackendAdapter adapter = new ClaudeCodeBackendAdapter(executor);

        AgentRunParams params = new AgentRunParams(
                "do the task", null, null, null, "some-unknown-agent", null, null, null, null);
        AgentAdapterContext ctx = new AgentAdapterContext(
                HostHandle.create(null), new AbortController(), "run-g1", 1, null,
                (id, ac) -> {
                }, id -> {
                });

        AgentRunResult result = adapter.run(params, ctx).join();

        // 裸 executor 无 contextFactory → executeStreaming 越过 resolveAgentDefinition 后必在后续步骤失败
        assertThat(result).as("无 contextFactory 的裸 executor 必须失败为 dead（非 ok）")
                .isInstanceOf(AgentRunResultDead.class);
        String detail = ((AgentRunResultDead) result).detail();
        assertThat(detail)
                .as("生产注册后 workflow-worker 必须二次解析命中，detail 不得是 AgentNotFoundException 的 '未找到'")
                .doesNotContain("未找到")
                .doesNotContain("AgentNotFoundException");
    }
}
