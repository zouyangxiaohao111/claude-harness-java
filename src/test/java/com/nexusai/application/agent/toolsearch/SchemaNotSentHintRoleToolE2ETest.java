package com.nexusai.application.agent.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ToolSearchTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [WF-10 e2e-role] {@link SchemaNotSentHint#extractDiscoveredToolNames} 真实 producer 闭环
 * E2E 测试 · 对齐 CC {@code extractDiscoveredToolNames}（toolSearch.ts:545-592）。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: CC 真源中 tool_result 块存于 {@code role:'user'} 消息
 * （messages.ts:505 createUserMessage），content 数组内嵌 tool_reference（toolSearch.ts:568-578）。
 * Java 把 CC user 消息内的 tool_result 扁平化为 {@link Role#tool} ChatMessageDto
 * （{@code LlmAgentLoop.toolResultMessage} 工厂存 Role.tool:6702，Provider 翻译回 role=user）。
 * 真实 producer（{@code LlmAgentLoop.toolResultMessage}）经 per-tool mapper
 * {@code ToolSearchTool.mapToToolResultBlockParam}（ToolSearchTool.ts:444-470）产出
 * {@code content=[{type:'tool_reference',tool_name}]} 块数组（ToolSearchTool.ts:462-469），再经
 * WF-9 producer-toolref 分支（LlmAgentLoop:6707-6729 serializeToolResultBlocks）注入
 * {@code Role.tool} 的 {@code contentBlocks}。
 *
 * <p>修复前 {@code extractDiscoveredToolNames} 仅扫 Role.user，该 Role.tool tool_reference 被漏扫
 * → discovered set 恒缺 → gate4 对<b>已发现</b>工具仍注入误导 hint、defer_loading 管线
 * （ToolSearchService:443 委托 → {@code LlmAgentLoop.llmToolsArray:6200}）漏发已发现 deferred 工具。
 * 本测试锁定<b>真实 producer → 扫描 → gate4</b> 全链闭环，防回退为 Role.user-only 或 producer
 * 回退文本渲染器（块数组丢弃）。
 *
 * <p><b>变异自验</b>: 临时删除 Role.tool 扫描分支（SchemaNotSentHint:197 改回 Role.user-only）
 * → T1（discovered 提取 + gate4 拦截）与 T3（并集）必红；producer 回退
 * {@code renderToolResultPayloadText}（WF-9 分支删除）→ T1 contentBlocks 断言必红。
 */
class SchemaNotSentHintRoleToolE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ─────────────────────── T1 真实 producer 闭环 ───────────────────────

    @Test
    @DisplayName("T1 真实 producer 闭环: ToolSearch 命中 mcp__gh__create → Role.tool tool_reference → discovered → gate4 build()==null 拦截")
    void realProducer_toolSearchHit_discoveredContainsAndGate4Intercept() {
        ToolSearchTool toolSearch = new ToolSearchTool();
        Tool mcpTool = mockMcp("mcp__gh__create");

        // 真实执行 ToolSearchTool：ctx.availableTools 含 deferred mcp 工具，select 精确命中
        ToolUseContext execCtx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                PermissionMode.DEFAULT, List.of(mcpTool));
        ToolUseBlock call = new ToolUseBlock("toolu_1", ToolNameConstants.TOOL_SEARCH_TOOL_NAME,
                JSON.createObjectNode().put("query", "select:mcp__gh__create").put("max_results", 5));
        AgentToolResult<?> result = toolSearch.execute(call, execCtx);

        // 真实 producer：per-tool mapper → 块数组注入 contentBlocks（WF-9 producer-toolref 分支）
        ChatMessageDto dto = LlmAgentLoop.toolResultMessage((ToolResult<?>) result, "toolu_1", false, toolSearch,
                null, null, List.of(), List.of(), Map.of());

        // producer 契约：Role.tool + contentBlocks 承载 tool_reference（不回退文本渲染器）
        assertThat(dto.role())
                .as("真实 producer 产物必须是 Role.tool（Java 内部等价 CC user 消息 tool_result）")
                .isEqualTo(Role.tool);
        assertThat(dto.contentBlocks())
                .as("producer 不得丢弃 tool_reference 块数组（对齐 CC tool_result.content 块数组语义）")
                .isNotEmpty();

        // discovered 提取含目标（修复前 Role.tool 被漏扫 → 此断言红）
        Set<String> discovered = SchemaNotSentHint.extractDiscoveredToolNames(List.of(dto));
        assertThat(discovered)
                .as("真实 producer 的 Role.tool tool_reference 必须被发现")
                .contains("mcp__gh__create");

        // gate4 拦截：目标已在 discovered → build()==null（schema 已发，不注入误导 hint）
        ToolUseContext gateCtx = ctxWith(List.of(toolSearch, mcpTool), List.of(dto));
        assertThat(SchemaNotSentHint.build(mcpTool, gateCtx, JSON.createObjectNode()))
                .as("已发现工具 → schema 已发 → gate4 拦截, 不注入 hint")
                .isNull();
    }

    // ─────────────────────── T2 负向对照 ───────────────────────

    @Test
    @DisplayName("T2 负向对照: 真实 producer 只发现其它工具 → 目标不在 discovered → gate4 注入 hint")
    void realProducer_discoveredOtherTool_hintInjected() {
        ToolSearchTool toolSearch = new ToolSearchTool();
        Tool otherMcp = mockMcp("mcp__gh__delete");
        Tool targetMcp = mockMcp("mcp__gh__create");

        ToolUseContext execCtx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                PermissionMode.DEFAULT, List.of(otherMcp));
        ToolUseBlock call = new ToolUseBlock("toolu_1", ToolNameConstants.TOOL_SEARCH_TOOL_NAME,
                JSON.createObjectNode().put("query", "select:mcp__gh__delete"));
        AgentToolResult<?> result = toolSearch.execute(call, execCtx);
        ChatMessageDto dto = LlmAgentLoop.toolResultMessage((ToolResult<?>) result, "toolu_1", false, toolSearch,
                null, null, List.of(), List.of(), Map.of());

        assertThat(dto.role()).isEqualTo(Role.tool);
        assertThat(SchemaNotSentHint.extractDiscoveredToolNames(List.of(dto)))
                .contains("mcp__gh__delete");

        // 目标不在 discovered → schema 未发 → 注入 hint（schema 未发送场景不受影响）
        ToolUseContext gateCtx = ctxWith(List.of(toolSearch, targetMcp), List.of(dto));
        String hint = SchemaNotSentHint.build(targetMcp, gateCtx, JSON.createObjectNode());
        assertThat(hint)
                .as("目标工具不在 discovered → schema 未发 → 注入 hint")
                .isNotNull()
                .contains("select:mcp__gh__create");
    }

    // ─────────────────────── T3 混合扫描 ───────────────────────

    @Test
    @DisplayName("T3 混合扫描: 真实 producer Role.tool + Role.user tool_result 包裹 → 并集非空")
    void mixedProducerAndUserMsg_discoveredUnion() {
        // Role.tool 真实 producer 产物（扁平化 tool_reference）
        ToolSearchTool toolSearch = new ToolSearchTool();
        Tool flatMcp = mockMcp("mcp__flat__tool");
        ToolUseContext execCtx = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                PermissionMode.DEFAULT, List.of(flatMcp));
        ToolUseBlock call = new ToolUseBlock("toolu_1", ToolNameConstants.TOOL_SEARCH_TOOL_NAME,
                JSON.createObjectNode().put("query", "select:mcp__flat__tool"));
        ChatMessageDto toolMsg = LlmAgentLoop.toolResultMessage(
                (ToolResult<?>) toolSearch.execute(call, execCtx),
                "toolu_1", false, toolSearch, null, null, List.of(), List.of(), Map.of());

        // Role.user 消息（既有路径：contentBlocks 内 tool_result 包裹块内嵌 tool_reference）
        ChatMessageDto userMsg = userMsgWithToolReference("mcp__user__tool");

        Set<String> discovered = SchemaNotSentHint.extractDiscoveredToolNames(List.of(userMsg, toolMsg));
        assertThat(discovered)
                .as("Role.user 包裹 + Role.tool 扁平两条路径必须各自发现（并集）")
                .containsExactlyInAnyOrder("mcp__user__tool", "mcp__flat__tool");
    }

    // ─────────────────────── T4 防御 ───────────────────────

    @Test
    @DisplayName("T4 防御: Role.tool null contentBlocks + 非 ChatMessageDto 混合 → 跳过不抛")
    void defensive_nullBlocksAndForeignElements_noThrow() {
        ChatMessageDto toolNoBlocks = new ChatMessageDto("t1", "s1", Role.tool, "tool", "",
                null, List.of(), FinishReason.stop, null, null, "刚刚",
                OffsetDateTime.now(), "call-x", null, null,
                null, List.of(), null, false, false);
        ChatMessageDto asst = new ChatMessageDto("a1", "s1", Role.assistant, "assistant", "reply",
                null, List.of(), FinishReason.stop, null, null, "刚刚",
                OffsetDateTime.now(), null, null, null,
                List.of(toolRef("mcp__should__skip")), List.of(), null, false, false);

        assertThat(SchemaNotSentHint.extractDiscoveredToolNames(List.of(toolNoBlocks, asst, new Object())))
                .as("null contentBlocks / assistant / 非 DTO 元素均跳过, 不抛异常")
                .isEmpty();
    }

    // ─────────────────────── helpers ───────────────────────

    /** 对齐生产 Role.tool 消息形状（LlmAgentLoop.toolResultMessage）— 供 Role.user 手工构造对照. */
    private static ChatMessageDto userMsgWithToolReference(String toolName) {
        return new ChatMessageDto("u1", "s1", Role.user, "user", "tool result",
                null, List.of(), FinishReason.stop, null, null, "刚刚",
                OffsetDateTime.now(), null, null, null,
                List.of(toolResultBlock(toolRef(toolName))), List.of(), null, false, false);
    }

    private static JsonNode toolRef(String toolName) {
        return JSON.createObjectNode()
                .put("type", "tool_reference")
                .put("tool_name", toolName);
    }

    /** tool_result 包裹块: content 数组内嵌 tool_reference（对齐 CC tool_result block / Role.user 路径). */
    private static JsonNode toolResultBlock(JsonNode... items) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = JSON.createArrayNode();
        for (JsonNode item : items) {
            arr.add(item);
        }
        return JSON.createObjectNode()
                .put("type", "tool_result")
                .set("content", arr);
    }

    private static Tool mockMcp(String name) {
        return new ConfigurableTool(name, true, false, true);
    }

    private static ToolUseContext ctxWith(List<Tool> availableTools, List<?> messages) {
        UUID agentId = UUID.randomUUID();
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return new ToolUseContext(agentId, sessionId, PermissionMode.DEFAULT,
                Map.of(), availableTools, "", null, messages,
                null, PermissionMode.DEFAULT, Map.of(), false, "", null, null, null, null);
    }

    static class ConfigurableTool implements Tool {
        private final String name;
        private final boolean isMcp;
        private final boolean alwaysLoad;
        private final boolean shouldDefer;

        ConfigurableTool(String name, boolean isMcp, boolean alwaysLoad, boolean shouldDefer) {
            this.name = name;
            this.isMcp = isMcp;
            this.alwaysLoad = alwaysLoad;
            this.shouldDefer = shouldDefer;
        }

        @Override public String name() { return name; }

        @Override public String description() { return "mock tool " + name; }

        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }

        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "ok");
        }

        @Override public boolean isMcp() { return isMcp; }

        @Override public boolean alwaysLoad() { return alwaysLoad; }

        @Override public boolean shouldDefer(JsonNode input) { return shouldDefer; }

        @Override public McpServerInfo mcpInfo() {
            return isMcp ? new McpServerInfo(name + "_server", "stdio") : null;
        }
    }
}
