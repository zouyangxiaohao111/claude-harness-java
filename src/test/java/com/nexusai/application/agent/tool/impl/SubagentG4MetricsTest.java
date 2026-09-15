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
        // [r10b · D3] 签名改为带 sessionId（会话态显式传参）。本用例走「确无会话」哨兵 ——
        //   ⛔ 刻意不用合成 id：合成 id 在测试环境靠全局 NoDatabaseSessionProjectRootExtension
        //   答 sessionless 才恰好落到 user.dir，那是环境巧合而非本用例的语义；哨兵是显式声明。
        String systemContext = executor.resolveSystemContextText(
            com.nexusai.common.SessionKeys.NO_SESSION);
        // 无会话 ⇒ 无会话命名出口 = 进程 user.dir = 项目根（git 仓库）→ gitStatus 非空
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
        String systemContext = executor.resolveSystemContextText(
            com.nexusai.common.SessionKeys.NO_SESSION);
        assertThat(systemContext).as("注入 provider 产出 gitStatus 上下文").contains("gitStatus");
    }

    /**
     * [r10b · D3] 兜底 provider 的 git 锚点<b>按会话 cwd</b>（而非进程 user.dir）。
     *
     * <p>WHY（守护什么）：D3 的缺陷形态 = 兜底 provider 用无参 {@code new GitStatusProvider()}
     * ⇒ 锚进程 user.dir。生产两个构造器都不注入 provider ⇒ 本兜底是<b>生产路径</b>。
     *
     * <p>装置（P0-2 锚点夹具同款）：会话 cwd = 临时目录 A（<b>非</b> git 仓库）；
     * 进程 {@code user.dir} = 临时目录 B（<b>是</b> git 仓库）。
     *
     * <p>反向实验配方：把 {@code systemPromptContextProvider(sessionId)} 里的
     * {@code new GitStatusProvider(Path.of(CwdResolution.getCwd(sessionId)))} 改回无参
     * {@code new GitStatusProvider()} ⇒ 锚 B（git）⇒ systemContext 含 gitStatus ⇒ 本用例红。
     */
    @Test
    @DisplayName("[r10b-D3] resolveSystemContextText: git 锚点随会话 cwd（非 git 会话 ⇒ 无 gitStatus）")
    void resolveSystemContextText_gitAnchorFollowsSessionCwd(@org.junit.jupiter.api.io.TempDir
                                                             java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path sessionDir = java.nio.file.Files.createDirectories(tmp.resolve("session-proj"));
        java.nio.file.Path processDir = java.nio.file.Files.createDirectories(tmp.resolve("process-dir"));
        java.nio.file.Files.createDirectory(processDir.resolve(".git"));
        assertThat(new GitStatusProvider(sessionDir).findGitRoot())
            .as("夹具前置：临时目录 A 必须不在任何 git 仓库内（否则锚 A/锚 B 不可分辨）")
            .isNull();

        String savedUserDir = System.getProperty("user.dir");
        String sid = "sess-r10b-subagent-anchor";
        try {
            System.setProperty("user.dir", processDir.toString());
            com.nexusai.application.agent.agent.SessionCwdHolder.set(sid, sessionDir.toString());
            // 会话层命中 ⇒ 不会落到回源器；显式装 unknown() 使「落回源」可观测为抛
            com.nexusai.common.SessionProjectRoot.setDbResolver(
                s -> com.nexusai.common.SessionProjectRoot.Lookup.unknown());

            SubagentExecutor executor = new SubagentExecutor(
                null, null, null, null, null, "model", "system-prompt");
            String systemContext = executor.resolveSystemContextText(sid);

            assertThat(systemContext)
                .as("⭐ 会话 cwd = 非 git 目录 ⇒ 兜底 provider 的 isGit=false ⇒ 无 gitStatus 行")
                .doesNotContain("gitStatus");
        } finally {
            com.nexusai.application.agent.agent.SessionCwdHolder.reset();
            com.nexusai.common.SessionProjectRoot.reset();
            com.nexusai.common.SessionProjectRoot.setDbResolver(null);
            if (savedUserDir != null) {
                System.setProperty("user.dir", savedUserDir);
            }
        }
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
