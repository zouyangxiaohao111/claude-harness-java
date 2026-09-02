package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.compact.SnipCompactor;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SnipTool 真行为测试 · 对齐 CC 真源 {@code Open-ClaudeCode/src/tools/SnipTool/SnipTool.ts}
 * + {@code services/compact/snipCompact.ts} + {@code services/compact/snipProjection.ts}。
 *
 * <p><b>验证意图（WHY）</b>：SnipTool 的核心价值 = 「把指定消息从会话历史替换为摘要，
 * 释放上下文」。测试必须证明：
 * <ol>
 *   <li><b>真裁剪</b>：message_ids 中存在于会话历史的消息被收进 boundary.removedUuids
 *       （LlmAgentLoop 下轮 snip 步骤按 removedUuids 物理剔除，CC snipCompact.ts:128-139）。</li>
 *   <li><b>输出契约</b>：data = {snipped_count, summary}（CC SnipOutput，SnipTool.ts:25），
 *       模型侧 content = "Snipped N messages. Summary: S"（SnipTool.ts:77）。</li>
 *   <li><b>上下文释放闭环</b>：boundary 注入历史后，SnipCompactor.snipCompactIfNeeded
 *       把 removedUuids 消息剔除、boundary（摘要）保留 —— 即「替换为摘要」的完整语义。</li>
 *   <li><b>门控</b>：isEnabled() = featureFlags.historySnip()，默认关（配置门控保留）。</li>
 * </ol>
 */
class SnipToolTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 构造 historySnip 开启的 FeatureFlags（其余全关）。 */
    private static FeatureFlags snipEnabledFlags() {
        return new FeatureFlags(
            false, false, false, false, false, true,   // historySnip=true（第 6 参）
            false, false, false, false, false, false,
            false, false, false, false, false, false,
            false, false, false, false, false, false);
    }

    private static ChatMessageDto message(String id) {
        return message(id, "hi");
    }

    private static ChatMessageDto message(String id, String content) {
        return new ChatMessageDto(
            id, SESSION.toString(), Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static List<ChatMessageDto> history(String... ids) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (String id : ids) {
            list.add(message(id));
        }
        return list;
    }

    private static ToolUseContext ctxWithMessages(List<?> messages) {
        return ToolUseContext.of(AGENT, SESSION, PermissionMode.DEFAULT, List.of(), "",
            AbortController.NOOP, messages);
    }

    private static ToolUseBlock snipCall(String... messageIds) {
        ObjectNode input = new ObjectMapper().createObjectNode();
        ArrayNode ids = input.putArray("message_ids");
        for (String id : messageIds) {
            ids.add(id);
        }
        return new ToolUseBlock(UUID.randomUUID().toString(), SnipTool.NAME, input);
    }

    private static ToolUseBlock snipCallWithReason(String reason, String... messageIds) {
        ObjectNode input = new ObjectMapper().createObjectNode();
        ArrayNode ids = input.putArray("message_ids");
        for (String id : messageIds) {
            ids.add(id);
        }
        input.put("reason", reason);
        return new ToolUseBlock(UUID.randomUUID().toString(), SnipTool.NAME, input);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult<String> asToolResult(AgentToolResult<?> r) {
        return (ToolResult<String>) r;
    }

    // ────────────────────────────────────────────────────────────────────
    // 真行为：指定消息被收进 boundary.removedUuids + 输出契约
    // ────────────────────────────────────────────────────────────────────

    @Test
    void execute_matchesExistingMessages_returnsBoundaryWithRemovedUuidsAndContract() {
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");
        SnipTool tool = new SnipTool(snipEnabledFlags());
        ToolUseContext ctx = ctxWithMessages(history);

        ToolResult<String> result =
            asToolResult(tool.execute(snipCallWithReason("compressed exploration", "u1", "u2"), ctx));

        // 输出契约 {snipped_count, summary}（CC SnipOutput，SnipTool.ts:25）
        assertNotNull(result);
        assertEquals(1, result.newMessages().size(), "单条 boundary 经 newMessages 注入历史");
        JsonNode data = parse(result.data());
        assertEquals(2, data.path("snipped_count").asInt(), "snipped_count = 实际裁剪的消息数");
        assertEquals("compressed exploration", data.path("summary").asText(),
            "summary = reason（CC SnipTool.ts:88 input.reason ?? 默认）");

        // boundary = snip_boundary system 消息，removedUuids = 匹配到的消息 id
        ChatMessageDto boundary = result.newMessages().get(0);
        assertEquals(Role.system, boundary.role(), "boundary 为 system 消息（CC snipProjection.ts:15-18）");
        assertEquals(SnipCompactor.SUBTYPE_SNIP_BOUNDARY, boundary.subtype(),
            "subtype='snip_boundary'（CC snipProjection.ts:17）");
        assertEquals("compressed exploration", boundary.content(),
            "boundary content = 摘要替换（模型在投影后看到摘要）");
        List<?> removed = (List<?>) boundary.snipMetadata().get("removedUuids");
        assertNotNull(removed, "snipMetadata.removedUuids 必须存在（CC snipCompact.ts:99-106）");
        assertEquals(List.of("u1", "u2"), removed, "removedUuids = 被裁剪消息 id（保持请求顺序）");
    }

    @Test
    void execute_withoutReason_defaultsSummaryToSnippedCount() {
        List<ChatMessageDto> history = history("u0", "u1", "u2");
        SnipTool tool = new SnipTool(snipEnabledFlags());

        ToolResult<String> result = asToolResult(tool.execute(snipCall("u0"), ctxWithMessages(history)));

        JsonNode data = parse(result.data());
        assertEquals(1, data.path("snipped_count").asInt());
        assertEquals("Snipped 1 messages", data.path("summary").asText(),
            "无 reason 时 summary 回退 'Snipped N messages'（CC SnipTool.ts:88）");
        assertEquals("Snipped 1 messages", result.newMessages().get(0).content());
    }

    @Test
    void execute_ignoresMessageIdsNotInHistory() {
        List<ChatMessageDto> history = history("u0", "u1", "u2");
        SnipTool tool = new SnipTool(snipEnabledFlags());

        // u1 存在、ghost 不存在 → 只裁剪 u1
        ToolResult<String> result =
            asToolResult(tool.execute(snipCall("u1", "ghost", "u2"), ctxWithMessages(history)));

        JsonNode data = parse(result.data());
        assertEquals(2, data.path("snipped_count").asInt(), "只裁剪真实存在的消息（真行为）");
        ChatMessageDto boundary = result.newMessages().get(0);
        assertEquals(List.of("u1", "u2"), boundary.snipMetadata().get("removedUuids"),
            "不存在的 id 不进入 removedUuids");
    }

    // ────────────────────────────────────────────────────────────────────
    // 上下文释放闭环：boundary + removedUuids → SnipCompactor 投影物理剔除
    // ────────────────────────────────────────────────────────────────────

    @Test
    void boundaryFeedsSnipCompact_specifiedMessagesReplacedBySummary() {
        List<ChatMessageDto> history = new ArrayList<>(history("u0", "u1", "u2", "u3", "u4"));
        SnipTool tool = new SnipTool(snipEnabledFlags());

        // 模型裁剪 u1,u2，SnipTool 注入 boundary
        ToolResult<String> result =
            asToolResult(tool.execute(snipCallWithReason("earlier tool outputs compacted", "u1", "u2"),
                ctxWithMessages(history)));
        ChatMessageDto boundary = result.newMessages().get(0);

        // boundary 追加到会话历史（ToolResultApplier.apply → state.messages().addAll）
        history.add(boundary);

        // LlmAgentLoop 下轮 snip 步骤（LlmAgentLoop.java:3761-3787，CC query.ts:401-410）
        SnipCompactor.SnipResult snipResult = new SnipCompactor().snipCompactIfNeeded(history);

        assertTrue(snipResult.executed(), "boundary 存在即执行（CC snipCompact.ts:111-113）");
        assertTrue(snipResult.tokensFreed() > 0, "被裁剪消息释放了 token（CC snipCompact.ts:128-139）");

        // 被裁剪消息从历史消失，boundary（摘要）保留 —— 「替换为摘要」闭环
        List<String> remainingIds =
            snipResult.messages().stream().map(ChatMessageDto::id).toList();
        assertFalse(remainingIds.contains("u1"), "u1 已从历史剔除");
        assertFalse(remainingIds.contains("u2"), "u2 已从历史剔除");
        assertTrue(remainingIds.contains("u0"), "未裁剪消息保留");
        assertTrue(remainingIds.contains("u3"), "未裁剪消息保留");
        assertTrue(remainingIds.contains("u4"), "未裁剪消息保留");
        assertTrue(remainingIds.contains(boundary.id()), "boundary（摘要）保留在投影后消息链");
    }

    @Test
    void execute_noBoundary_noSnipCompaction() {
        List<ChatMessageDto> history = history("u0", "u1");
        SnipCompactor.SnipResult snipResult = new SnipCompactor().snipCompactIfNeeded(history);
        assertFalse(snipResult.executed(), "无 snip_boundary 不执行（CC snipCompact.ts:111-113）");
    }

    // ────────────────────────────────────────────────────────────────────
    // 模型侧 tool_result content（CC SnipTool.ts:70-79）
    // ────────────────────────────────────────────────────────────────────

    @Test
    void mapToToolResultBlockParam_rendersCcContent() {
        SnipTool tool = new SnipTool(snipEnabledFlags());
        ToolResult<String> result =
            asToolResult(tool.execute(snipCallWithReason("long exploration", "u0", "u1"),
                ctxWithMessages(history("u0", "u1", "u2"))));

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "tool-use-1", false);
        assertEquals("Snipped 2 messages. Summary: long exploration", block.content(),
            "模型侧 content 对齐 CC SnipTool.ts:77");
    }

    // ────────────────────────────────────────────────────────────────────
    // 门控：isEnabled() = featureFlags.historySnip()（默认关 · 配置门控保留）
    // ────────────────────────────────────────────────────────────────────

    @Test
    void isEnabled_defaultsToFalse() {
        SnipTool tool = new SnipTool();
        assertFalse(tool.isEnabled(), "默认 FeatureFlags 全关 → 工具不暴露（对齐 CC HISTORY_SNIP 默认关）");
    }

    @Test
    void isEnabled_reflectsHistorySnipFlag() {
        SnipTool tool = new SnipTool(snipEnabledFlags());
        assertTrue(tool.isEnabled(), "historySnip=true 时工具暴露（nexusai.feature.history-snip=true）");
    }

    // ────────────────────────────────────────────────────────────────────
    // fail loud：无会话历史访问能力 / 非法输入
    // ────────────────────────────────────────────────────────────────────

    @Test
    void execute_withoutContext_failsLoud() {
        SnipTool tool = new SnipTool(snipEnabledFlags());
        ToolResult<String> result = asToolResult(tool.execute(snipCall("u0")));
        assertTrue(result.data().contains("无法访问会话历史"),
            "无 ToolUseContext 时无法访问会话历史，必须 fail loud（而非静默成功）");
    }

    @Test
    void execute_emptyMessageIds_returnsError() {
        SnipTool tool = new SnipTool(snipEnabledFlags());
        ToolResult<String> result =
            asToolResult(tool.execute(snipCall(), ctxWithMessages(history("u0"))));
        assertTrue(result.data().contains("message_ids"),
            "message_ids 必填（CC SnipTool.ts:7-21 z.array(z.string())）——空输入报错");
    }

    @Test
    void ccMetadata_alignedConstants() {
        assertEquals("snip_boundary", SnipCompactor.SUBTYPE_SNIP_BOUNDARY);
        assertEquals("snip_marker", SnipCompactor.SUBTYPE_SNIP_MARKER);
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("data 非合法 JSON: " + json, e);
        }
    }
}
