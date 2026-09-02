package com.nexusai.application.agent.toolsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
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
 * [WF-10 hint-scan-role] {@link SchemaNotSentHint#extractDiscoveredToolNames} 扫描覆盖
 * {@link Role#tool} · 对齐 CC {@code extractDiscoveredToolNames}（toolSearch.ts:545-592）。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: CC 真源中 tool_result 块存于 {@code role:'user'} 消息
 * （messages.ts:505 createUserMessage），content 数组内嵌 tool_reference（toolSearch.ts:568-578）。
 * Java 把 CC user 消息内的 tool_result 扁平化为 {@link Role#tool} ChatMessageDto
 * （{@code LlmAgentLoop.toolResultMessage} 工厂 + {@code ToolResultStorage} 候选提取一致；
 * AnthropicSdkProvider.buildSdkMessages 把 Role.tool 翻译回 role=user，LLM 视角即 CC user
 * 消息）—— 语义等价角色 = Role.user（contentBlocks 内含 tool_result 包裹块）+ Role.tool
 * （扁平化 tool_result，contentBlocks 即 tool_result.content 数组）。
 *
 * <p>修复前仅扫 Role.user，生产经 WF-9 producer-toolref 注入 Role.tool contentBlocks 的
 * tool_reference 块（{@code toolResultMessage} 块数组分支）被漏扫 → discovered set 恒缺 →
 * gate4 对已发现工具仍注入 hint、llmToolsArray schema 门控（经 ToolSearchService:443 委托）
 * 漏掉已发现 deferred 工具。本测试锁定 Role.tool 扫描分支，防回退为 Role.user-only。
 *
 * <p><b>变异自验</b>: 临时删除 Role.tool 分支 → 用例 1（discovered 提取）与用例 2
 * （gate4 build()==null 拦截）必红；Role.tool 消息含 image/text contentBlocks 不命中
 * （用例 3 负例）不受影响。
 */
class SchemaNotSentHintRoleToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 对齐生产 Role.tool 消息（LlmAgentLoop.toolResultMessage）：author="tool" + toolCallId + contentBlocks. */
    private static ChatMessageDto toolMsg(String toolCallId, List<JsonNode> contentBlocks) {
        return new ChatMessageDto("t1", "s1", Role.tool, "tool", "",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), toolCallId, null, null,
            contentBlocks, List.of(), null, false, false);
    }

    /** tool_reference 块 (对齐 CC ToolSearchTool 发射形状 + isToolReferenceWithName). */
    private static JsonNode toolRef(String toolName) {
        return JSON.createObjectNode()
            .put("type", "tool_reference")
            .put("tool_name", toolName);
    }

    /** tool_result 包裹块: content 数组内嵌 tool_reference (对齐 CC tool_result block). */
    private static JsonNode toolResultBlock(JsonNode... items) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = JSON.createArrayNode();
        for (JsonNode item : items) {
            arr.add(item);
        }
        return JSON.createObjectNode()
            .put("type", "tool_result")
            .set("content", arr);
    }

    /** 负例块: type=image / type=text (生产 Role.tool contentBlocks 的 permission 块, 不命中). */
    private static JsonNode imageBlock() {
        // 注意: Jackson 2.11 ObjectNode.put(String, JsonNode) 已废弃且返回被替换的旧值(null) →
        // 必须用 set(String, JsonNode) (返回 this), 否则链式结果为空
        return JSON.createObjectNode()
            .put("type", "image")
            .set("source", JSON.createObjectNode().put("type", "base64"));
    }

    private static JsonNode textBlock() {
        return JSON.createObjectNode().put("type", "text").put("text", "allowed");
    }

    // ─────────────────────── Role.tool 扁平化主形状 ───────────────────────

    @Test
    @DisplayName("Role.tool 扁平化 contentBlocks 直接承载 tool_reference → discovered 含 tool_name")
    void toolMsg_flatToolReference_discoveredContains() {
        // WF-9 producer-toolref: toolResultMessage 块数组分支把 ToolSearchTool 命中的
        // tool_reference 序列化为 List<JsonNode> 注入 Role.tool contentBlocks —— 修复前被漏扫
        ChatMessageDto toolMsg = toolMsg("call-1",
            List.of(toolRef("mcp__github__create_issue"), toolRef("mcp__gh__delete")));

        Set<String> discovered = SchemaNotSentHint.extractDiscoveredToolNames(List.of(toolMsg));

        assertThat(discovered)
            .as("Role.tool 扁平化 tool_result.content 项必须提取 tool_reference.tool_name")
            .containsExactlyInAnyOrder("mcp__github__create_issue", "mcp__gh__delete");
    }

    @Test
    @DisplayName("Role.tool 含目标工具 → gate4 build()==null (schema 已发, 不再注入误导 hint)")
    void toolMsg_discoveredContainsTarget_buildNull() {
        // 目标工具 schema 已随 Role.tool tool_reference 发送 → gate4 返回 null (对齐
        // CC extractDiscoveredToolNames 扫到 tool_result→tool_reference 后的行为)
        Tool mcpTool = mockMcp("mcp__github__create_issue");
        ToolUseContext ctx = ctxWith(
            List.of(mockToolSearch(), mcpTool),
            List.of(toolMsg("call-1", List.of(toolRef("mcp__github__create_issue")))));

        String hint = SchemaNotSentHint.build(mcpTool, ctx, JSON.createObjectNode());

        assertThat(hint).as("Role.tool 已发现目标工具 → schema 已发 → 不注入").isNull();
    }

    @Test
    @DisplayName("Role.tool 发现其它工具, 目标不在 → 注入 hint (schema 未发送路径不受影响)")
    void toolMsg_discoveredWithoutTarget_hintInjected() {
        Tool mcpTool = mockMcp("mcp__github__create_issue");
        ToolUseContext ctx = ctxWith(
            List.of(mockToolSearch(), mcpTool),
            List.of(toolMsg("call-1", List.of(toolRef("mcp__gh__delete")))));

        String hint = SchemaNotSentHint.build(mcpTool, ctx, JSON.createObjectNode());

        assertThat(hint).as("Role.tool 只发现其它工具 → 目标 schema 未发 → 注入 hint").isNotNull();
        assertThat(hint).contains("select:mcp__github__create_issue");
    }

    // ─────────────────────── Role.tool 兼容形状 ───────────────────────

    @Test
    @DisplayName("Role.tool contentBlocks 含 tool_result 包裹块 → 复用包裹分支提取 (兼容形状)")
    void toolMsg_wrappedToolResult_discoveredContains() {
        // 兼容形状: contentBlocks 直接放 tool_result 包裹块 (与 Role.user 同路径), 不语义重叠
        ChatMessageDto toolMsg = toolMsg("call-2",
            List.of(toolResultBlock(toolRef("mcp__github__create_issue"))));

        Set<String> discovered = SchemaNotSentHint.extractDiscoveredToolNames(List.of(toolMsg));

        assertThat(discovered)
            .as("Role.tool 包裹形状与扁平主形状都必须发现 tool_reference")
            .containsExactly("mcp__github__create_issue");
    }

    // ─────────────────────── 负例: 非 tool_reference 不命中 ───────────────────────

    @Test
    @DisplayName("Role.tool contentBlocks 仅 image/text permission 块 → 不进入 discovered (无 false positive)")
    void toolMsg_imageTextBlocks_noFalsePositive() {
        // 生产 Role.tool contentBlocks 可含 permission 的 image/text 块 —— isToolReferenceWithName
        // 要求 type=='tool_reference'，image/text 天然不命中 → discovered 恒空
        ChatMessageDto toolMsg = toolMsg("call-3", List.of(imageBlock(), textBlock()));

        Set<String> discovered = SchemaNotSentHint.extractDiscoveredToolNames(List.of(toolMsg));

        assertThat(discovered).as("image/text 块不是 tool_reference → 不得进入 discovered").isEmpty();
    }

    @Test
    @DisplayName("混合消息: Role.user 既有路径 + Role.tool 扁平路径共存提取 (不互斥)")
    void mixedUserAndTool_discoveredBoth() {
        // Role.user 消息 contentBlocks 内含 tool_result 包裹块 (既有路径, 行为不变)
        JsonNode userWrapped = toolResultBlock(toolRef("mcp__user__tool"));
        ChatMessageDto userMsg = new ChatMessageDto("u1", "s1", Role.user, "user", "tool result",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(userWrapped), List.of(), null, false, false);
        // Role.tool 消息扁平化 tool_reference
        ChatMessageDto toolMsg = toolMsg("call-1", List.of(toolRef("mcp__flat__tool")));

        Set<String> discovered = SchemaNotSentHint.extractDiscoveredToolNames(List.of(userMsg, toolMsg));

        assertThat(discovered)
            .as("Role.user 包裹 + Role.tool 扁平两条路径都必须各自发现")
            .containsExactlyInAnyOrder("mcp__user__tool", "mcp__flat__tool");
    }

    @Test
    @DisplayName("Role.assistant / null contentBlocks 跳过 (防御, 不抛异常)")
    void nonToolRolesAndNullBlocks_skipped() {
        ChatMessageDto asst = new ChatMessageDto("a1", "s1", Role.assistant, "assistant", "reply",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(toolRef("mcp__should__skip")), List.of(), null, false, false);
        ChatMessageDto toolNoBlocks = toolMsg("call-4", null);

        assertThat(SchemaNotSentHint.extractDiscoveredToolNames(List.of(asst, toolNoBlocks)))
            .as("assistant 消息跳过 + Role.tool null contentBlocks 跳过, 不抛异常").isEmpty();
    }

    // ─────────────────────── 工具 mocks ───────────────────────

    private static Tool mockMcp(String name) {
        return new ConfigurableTool(name, true, false, true);
    }

    private static Tool mockToolSearch() {
        return new ConfigurableTool("ToolSearch", false, false, false);
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
