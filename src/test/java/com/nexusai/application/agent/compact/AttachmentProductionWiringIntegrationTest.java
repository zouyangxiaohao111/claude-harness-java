package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.tool.McpClientRuntime;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP2-03] 附件生产接线集成测试 · 对齐 CC compact.ts:545-585（✗-1..✗-4，INV-15）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9）</b>：PostCompactAttachmentRestorer 的
 * async-agent/plan/plan_mode/3×delta 四类附件工厂从「0 生产调用」变为生产路径真实填充——
 * 本测试走真实 {@code populatePostCompactAttachments + compactConversation → restore} 链：
 * <ol>
 *   <li>plan/async-agent 场景压缩后附件存在且预算合规（file 5/50K/5K、skill 5K/25K 不变量
 *       已有 PostCompactAttachmentRestorerTest 覆盖，此处断言生产链路附件存在 + 总预算）</li>
 *   <li>无计划场景行为不变（回归：无 plan/plan_mode/delta 附件）</li>
 *   <li>3×delta gate 开时（env 注入 seam）从当前工具/MCP 状态真实生产三类 delta 附件</li>
 * </ol>
 */
class AttachmentProductionWiringIntegrationTest {

    private static final String SESSION = "s1";

    @AfterEach
    void resetEnvSeam() {
        PostCompactAttachmentRestorer.envOverride = null;
        CompactConversation.setSessionAgentStateRegistry(null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · plan/async-agent 场景压缩后附件存在且预算合规（✗-1/✗-2/✗-3）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("plan+async-agent 场景: 压缩后附件含 plan_file_reference/plan_mode/task_status 且总预算合规")
    void planAndAsyncAgentScenarioAttachmentsPresentAndBudgetCompliant() {
        // ── 数据源：async-agent 任务（LOCAL_AGENT，running，CC appState.tasks local_agent）──
        UUID asyncAgentId = UUID.randomUUID();
        TaskFrameworkService taskFrameworkService = new TaskFrameworkService(null);
        taskFrameworkService.registerTask(new BackgroundTask(
            asyncAgentId.toString(), TaskType.LOCAL_AGENT, BackgroundTaskStatus.RUNNING,
            "正在整理调研报告", null, System.currentTimeMillis(), null, null,
            com.nexusai.application.agent.tasks.BackgroundTaskRunner.taskOutputPath(asyncAgentId.toString()), 0L, false,
            asyncAgentId, true));
        // ── 数据源：plan 文件（fake PlanProvider，CC getPlan/getPlanFilePath）· 非函数式接口，匿名类 ──
        PlanProvider planProvider = new PlanProvider() {
            @Override public String getPlanFilePath(UUID agentId) { return "plans/main.md"; }
            @Override public String getPlan(UUID agentId) { return "# 当前计划\n1. 完成附件接线"; }
            @Override public boolean copyPlanForResume(String targetSessionId, String sourceSlug) { return false; }
            @Override public boolean copyPlanForFork(String targetSessionId, String sourceSlug) { return false; }
            @Override public AttachmentMessageDto.PlanRef createPlanAttachmentIfNeeded(UUID agentId) {
                return new AttachmentMessageDto.PlanRef("plans/main.md", "# 当前计划\n1. 完成附件接线");
            }
        };
        // ── 数据源：plan_mode（tuc.permissionMode=PLAN，CC toolPermissionContext.mode==='plan'）──
        ToolUseContext tuc = ToolUseContext.of(
            null, "", PermissionMode.PLAN,
            List.of(tool("Read", false), tool("Grep", false)),
            "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            null, PermissionMode.PLAN);

        CompactConversationContext ctx = ctx(tuc);
        PostCompactAttachmentRestorer.populatePostCompactAttachments(ctx, taskFrameworkService, planProvider);

        CompactionResult result = CompactConversation.compactConversation(
            messages("m1", "m2", "m3"), ctx, false, null, false, null);

        List<ChatMessageDto> attachments = result.attachments();
        List<String> subtypes = attachments.stream().map(ChatMessageDto::subtype).toList();
        // async-agent（CC compact.ts:545-548）+ plan（:545-548）+ plan_mode（:552-555）
        assertThat(subtypes).contains("task_status", "plan_file_reference", "plan_mode");
        // 附件顺序对齐 CC：async → plan → plan_mode（compact.ts:541-560）
        assertThat(subtypes.indexOf("task_status"))
            .isLessThan(subtypes.indexOf("plan_file_reference"));
        assertThat(subtypes.indexOf("plan_file_reference"))
            .isLessThan(subtypes.indexOf("plan_mode"));
        // 预算合规（INV-15）：全部附件内容 token 粗估 ≤ POST_COMPACT_TOKEN_BUDGET=50K
        int totalTokens = attachments.stream()
            .mapToInt(a -> CompactConversation.roughTokenCountEstimation(a.content() == null ? "" : a.content()))
            .sum();
        assertThat(totalTokens).isLessThanOrEqualTo(CompactConstants.POST_COMPACT_TOKEN_BUDGET);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · 无计划场景行为不变（回归）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无计划场景: 无 plan/plan_mode/delta 附件，行为与接线前一致")
    void noPlanScenarioNoPlanAttachments() {
        ToolUseContext tuc = ToolUseContext.of(
            null, "", PermissionMode.DEFAULT,
            List.of(tool("Read", false)), "", com.nexusai.application.agent.tool.AbortController.NOOP,
            List.of(), null, PermissionMode.DEFAULT);

        CompactConversationContext ctx = ctx(tuc);
        // 生产数据源均缺省（无 async 任务 / 无 plan provider / delta gate 默认关）
        PostCompactAttachmentRestorer.populatePostCompactAttachments(ctx, null, null);

        CompactionResult result = CompactConversation.compactConversation(
            messages("m1", "m2"), ctx, false, null, false, null);

        // 无 readFileState + 无附加数据源 → 附件恒空（行为与接线前一致）
        assertThat(result.attachments()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · 3×delta gate 开时从当前状态真实生产（✗-4，compact.ts:567-585）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3×delta gate 开: 压缩后重宣布 deferred_tools/agent_listing/mcp_instructions_delta")
    void deltaGatesOnProduceThreeDeltaAttachments() {
        // gate 注入（CC isDeferredToolsDeltaEnabled USER_TYPE=ant / shouldInjectAgentListInMessages
        // env / isMcpInstructionsDeltaEnabled env，测试 seam 替代真实环境）
        PostCompactAttachmentRestorer.envOverride = Map.of(
            "USER_TYPE", "ant",
            "CLAUDE_CODE_AGENT_LIST_IN_MESSAGES", "true",
            "CLAUDE_CODE_MCP_INSTR_DELTA", "true");

        // 工具池：MCP 工具（恒 deferred）+ ToolSearch（gate2/4 目标）+ Agent 工具
        List<Tool> tools = new ArrayList<>();
        tools.add(tool("mcp__docs-server__search", true));   // isMcp → deferred
        tools.add(tool("ToolSearch", false));                // isToolSearchToolAvailable
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "cc-imp2-03-test");
        tools.add(new SubagentTool(List.of(), null, null, null, null, null, null, tmpDir,
            List.of(AgentDefinition.BuiltInAgentDefinition.builder(
                "agent-a", "处理需要独立上下文的任务", (o, dirs) -> "agent system prompt").build())));

        ToolUseContext tuc = ToolUseContext.of(
            null, "", PermissionMode.DEFAULT,
            tools, "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of("docs-server", new McpClientRuntime("docs-server", "mcp__docs-server__search",
                "文档服务器使用说明：先查目录再读文件")),
            false, "");

        CompactConversationContext ctx = ctx(tuc);
        PostCompactAttachmentRestorer.populatePostCompactAttachments(ctx, null, null);

        CompactionResult result = CompactConversation.compactConversation(
            messages("m1", "m2", "m3"), ctx, false, null, false, null);

        List<String> subtypes = result.attachments().stream()
            .map(ChatMessageDto::subtype).toList();
        // 三类 delta 重宣布（compact.ts:567-585）：对空历史 diff → 宣布全量
        assertThat(subtypes)
            .contains(PostCompactAttachmentRestorer.DELTA_TYPE_DEFERRED_TOOLS,
                PostCompactAttachmentRestorer.DELTA_TYPE_AGENT_LISTING,
                PostCompactAttachmentRestorer.DELTA_TYPE_MCP_INSTRUCTIONS);

        // deferred_tools_delta 载荷：MCP 工具名入 addedNames（formatDeferredToolLine=name）
        ChatMessageDto dtd = result.attachments().stream()
            .filter(a -> PostCompactAttachmentRestorer.DELTA_TYPE_DEFERRED_TOOLS.equals(a.subtype()))
            .findFirst().orElseThrow();
        assertThat(dtd.content()).contains("mcp__docs-server__search");

        // agent_listing_delta 载荷：agent-a 入 addedTypes（formatAgentLine；内置 agent 一并
        // 宣布——SubagentTool 构造合并 BuiltInAgents，CC getActiveAgentsFromList 同语义）
        ChatMessageDto ald = result.attachments().stream()
            .filter(a -> PostCompactAttachmentRestorer.DELTA_TYPE_AGENT_LISTING.equals(a.subtype()))
            .findFirst().orElseThrow();
        assertThat(ald.content()).contains("\"addedTypes\":[").contains("\"agent-a\"");

        // mcp_instructions_delta 载荷：docs-server 指令块（## name\ninstructions）
        ChatMessageDto mid = result.attachments().stream()
            .filter(a -> PostCompactAttachmentRestorer.DELTA_TYPE_MCP_INSTRUCTIONS.equals(a.subtype()))
            .findFirst().orElseThrow();
        assertThat(mid.content()).contains("## docs-server").contains("文档服务器使用说明");
    }

    @Test
    @DisplayName("3×delta gate 默认关: 压缩后无 delta 附件（对齐 CC feature 默认关）")
    void deltaGatesOffNoDeltaAttachments() {
        ToolUseContext tuc = ToolUseContext.of(
            null, "", PermissionMode.DEFAULT,
            List.of(tool("mcp__docs-server__search", true), tool("ToolSearch", false)),
            "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            null, PermissionMode.DEFAULT,
            Map.of("docs-server", new McpClientRuntime("docs-server", "mcp__docs-server__search",
                "文档服务器使用说明")),
            false, "");

        CompactConversationContext ctx = ctx(tuc);
        PostCompactAttachmentRestorer.populatePostCompactAttachments(ctx, null, null);

        CompactionResult result = CompactConversation.compactConversation(
            messages("m1", "m2"), ctx, false, null, false, null);

        assertThat(result.attachments()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 小工具
    // ════════════════════════════════════════════════════════════════════

    /** 压缩上下文（mock 摘要生产者 + tuc）。 */
    private static CompactConversationContext ctx(ToolUseContext tuc) {
        CompactConversationContext c = new CompactConversationContext();
        c.setSessionId(SESSION);
        c.setAgentId("main-agent");
        c.setModel("claude-sonnet-4-5");
        c.setQuerySource("compact");
        c.setSummaryProducer((messagesToSummarize, compactPrompt, preCompactTokenCount) ->
            new CompactConversation.SummaryResult("摘要：已总结对话要点。", null));
        c.setToolUseContext(tuc);
        return c;
    }

    /** 会话消息（compaction 输入，≥1 条）。 */
    private static List<ChatMessageDto> messages(String... contents) {
        List<ChatMessageDto> out = new ArrayList<>();
        for (String c : contents) {
            out.add(new ChatMessageDto(UUID.randomUUID().toString(), SESSION, Role.user, "user",
                c, null, List.of(), FinishReason.stop, null, null, "刚刚",
                OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false));
        }
        return out;
    }

    /** 假 Tool（isMcp → 恒 deferred，CC isDeferredTool MCP 分支）。 */
    private static Tool tool(String name, boolean mcp) {
        return new Tool() {
            @Override
            public String name() { return name; }
            @Override
            public String description() { return name + " desc"; }
            @Override
            public JsonNode inputSchema() { return JsonNodeFactory.instance.objectNode(); }
            @Override
            public AgentToolResult<?> execute(ToolUseBlock call) { return null; }
            @Override
            public boolean isMcp() { return mcp; }
            @Override
            public boolean shouldDefer(JsonNode input) { return mcp; }
        };
    }
}
