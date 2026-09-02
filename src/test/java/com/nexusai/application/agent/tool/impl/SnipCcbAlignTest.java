package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.compact.CompactSettingsResolver;
import com.nexusai.application.agent.compact.SnipCompactor;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [snip-ccb-align] Snip 链路对齐 CCB 的测试 · 验证意图（CLAUDE.md 规则 9）。
 *
 * <p><b>WHY</b>：用户拍板「真删除 + 模型自行调用 SnipTool + 区间删除不断裂」。本测试钉死：
 * <ol>
 *   <li><b>[id:xxx] 短 id tag 注入</b>（对齐 CCB messages.ts:2667-2686 + deriveShortMessageId
 *       messages.ts:201-206）：HISTORY_SNIP 门控给 user 非 isMeta 消息追加 {@code \n[id:<6位短id>]}，
 *       让模型能引用消息 ID 调用 SnipTool；assistant / isMeta 不加。</li>
 *   <li><b>SnipTool 短 id 匹配</b>：模型传 [id:xxx] 短 id → 反解回完整 user 消息。</li>
 *   <li><b>区间删除</b>：删 user 消息时连带删其后的 assistant（含 tool_calls）+ tool 结果
 *       （tool_use/tool_result 配对整段删）—— 杜绝「只删一个 tool 造成 API 序列断裂」。</li>
 *   <li><b>空匹配 fail loud</b>：message_ids 未匹配到任何 user → 返回 error 且<b>不注入空 boundary</b>
 *       （空 removedUuids 的 boundary 会触发 SnipCompactor slice 灾难截断，212→3 事故根因）。</li>
 * </ol>
 */
class SnipCcbAlignTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "sess-snipalign";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    private static ChatMessageDto msg(String id, Role role, String content) {
        return msg(id, role, content, null, false);
    }

    private static ChatMessageDto msg(String id, Role role, String content, String toolCallId) {
        return msg(id, role, content, toolCallId, false);
    }

    private static ChatMessageDto msg(String id, Role role, String content, String toolCallId, boolean isMeta) {
        return new ChatMessageDto(
            id, SESSION, role, role == Role.user ? "user" : (role == Role.tool ? "tool" : "assistant"),
            content, null, List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), toolCallId, null, null,
            List.of(), List.of(), null, isMeta, false);
    }

    private static List<ChatMessageDto> history(ChatMessageDto... msgs) {
        return new ArrayList<>(List.of(msgs));
    }

    private static ToolUseContext ctxWithMessages(List<?> messages) {
        return ToolUseContext.of(AGENT, SESSION, PermissionMode.DEFAULT, List.of(), "",
            AbortController.NOOP, messages);
    }

    private static ToolUseBlock snipCall(String... messageIds) {
        com.fasterxml.jackson.databind.node.ObjectNode input = MAPPER.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode ids = input.putArray("message_ids");
        for (String id : messageIds) {
            ids.add(id);
        }
        return new ToolUseBlock(UUID.randomUUID().toString(), SnipTool.NAME, input);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult<String> asToolResult(AgentToolResult<?> r) {
        return (ToolResult<String>) r;
    }

    private static FeatureFlags snipEnabledFlags() {
        return new FeatureFlags(
            false, false, false, false, false, true,   // historySnip=true（第 6 参）
            false, false, false, false, false, false,
            false, false, false, false, false, false,
            false, false, false, false, false, false);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("data 非合法 JSON: " + json, e);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // deriveShortMessageId · 对齐 CCB messages.ts:201-206
    // ────────────────────────────────────────────────────────────────────

    @Test
    void deriveShortMessageId_standardUuid_returns6CharBase36() {
        String uuid = "3c9d9b0a-1f4e-4f2e-9b7c-1a2b3c4d5e6f";
        String shortId = SnipCompactor.deriveShortMessageId(uuid);
        assertEquals(6, shortId.length(), "短 id 恒 6 位（CCB：前10 hex → base36 → 前6位）");
        assertEquals(shortId, SnipCompactor.deriveShortMessageId(uuid), "确定性（同一 id → 同一短 id，注入与匹配对称）");
    }

    @Test
    void deriveShortMessageId_msgPrefixTolerant() {
        String id = "msg-" + UUID.randomUUID();
        String shortId = SnipCompactor.deriveShortMessageId(id);
        assertEquals(6, shortId.length(), "msg- 前缀非纯 hex id 也输出 6 位（Java 容错，CCB 假设标准 UUID）");
        assertEquals(shortId, SnipCompactor.deriveShortMessageId(id), "对称性");
    }

    @Test
    void deriveShortMessageId_nullReturnsPlaceholder() {
        assertEquals("?", SnipCompactor.deriveShortMessageId(null), "null → 定长占位（不抛）");
    }

    // ────────────────────────────────────────────────────────────────────
    // SnipTool 区间删除 · 真删除 + tool_use/tool_result 配对整段删（不断裂）
    // ────────────────────────────────────────────────────────────────────

    @Test
    void execute_rangeDelete_includesAssistantAndToolPair() {
        // 历史：user0 → assistant0(tool_calls=call_0) → tool0(tool_result) → user1 → assistant1 → user2
        ChatMessageDto user0 = msg("u0", Role.user, "open baidu");
        ChatMessageDto asst0 = msg("a0", Role.assistant, "searching");
        ChatMessageDto tool0 = msg("t0", Role.tool, "result of call_0", "call_0");
        ChatMessageDto user1 = msg("u1", Role.user, "open apple");
        ChatMessageDto asst1 = msg("a1", Role.assistant, "ok");
        ChatMessageDto user2 = msg("u2", Role.user, "login");
        List<ChatMessageDto> history = history(user0, asst0, tool0, user1, asst1, user2);

        ToolResult<String> result =
            asToolResult(new SnipTool(snipEnabledFlags()).execute(snipCall("u0"), ctxWithMessages(history)));

        assertNotNull(result);
        assertEquals(1, result.newMessages().size(), "单条 boundary 经 newMessages 注入");
        List<?> removed = (List<?>) result.newMessages().get(0).snipMetadata().get("removedUuids");
        // 区间 [user0, 下一 user1) = user0+assistant0+tool0 整段删：tool_use/tool_result 配对完整，不断裂
        assertEquals(List.of("u0", "a0", "t0"), removed,
            "区间删除：指定 user0 → 连带删 assistant0 + tool_result（配对整段删，杜绝 API 序列断裂）");
        assertEquals(3, parse(result.data()).path("snipped_count").asInt(), "snipped_count = 实际删除消息数");
    }

    @Test
    void execute_multipleUserIndices_mergeOverlappingRanges() {
        ChatMessageDto user0 = msg("u0", Role.user, "a");
        ChatMessageDto asst0 = msg("a0", Role.assistant, "r1");
        ChatMessageDto user1 = msg("u1", Role.user, "b");
        ChatMessageDto asst1 = msg("a1", Role.assistant, "r2");
        ChatMessageDto user2 = msg("u2", Role.user, "c");
        List<ChatMessageDto> history = history(user0, asst0, user1, asst1, user2);

        // 指定 u0、u1 → 区间 [0,2) 与 [1,3) 重叠 → 合并 [0,3)
        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u0", "u1"), ctxWithMessages(history)));

        List<?> removed = (List<?>) result.newMessages().get(0).snipMetadata().get("removedUuids");
        assertEquals(List.of("u0", "a0", "u1", "a1"), removed,
            "重叠区间合并：u0+assistant0+u1+assistant1 整段删，u2 保留");
    }

    // ────────────────────────────────────────────────────────────────────
    // SnipTool 短 id 匹配（模型传 [id:xxx] 短 id，与注入对称）
    // ────────────────────────────────────────────────────────────────────

    @Test
    void execute_shortIdMatch_resolvesToUserMessageAndRangeDeletes() {
        String u0Id = "u0-" + UUID.randomUUID();
        ChatMessageDto user0 = msg(u0Id, Role.user, "open baidu");
        ChatMessageDto asst0 = msg("a0", Role.assistant, "searching");
        ChatMessageDto user1 = msg("u1", Role.user, "next");
        List<ChatMessageDto> history = history(user0, asst0, user1);

        String shortId = SnipCompactor.deriveShortMessageId(u0Id);
        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall(shortId), ctxWithMessages(history)));

        assertNotNull(result);
        List<?> removed = (List<?>) result.newMessages().get(0).snipMetadata().get("removedUuids");
        assertTrue(removed.contains(u0Id), "短 id 匹配到完整 user 消息（区间删除起点）");
        assertTrue(removed.contains("a0"), "区间含 assistant（区间删除语义）");
        assertEquals(2, removed.size(), "u0 + assistant0 区间删除，u1 保留");
    }

    @Test
    void execute_fullIdMatch_stillWorks() {
        // 兼容旧调用：模型传完整 id（非 [id:xxx] 短 id）也能匹配（回归锚点，SnipToolTest 同款场景）
        ChatMessageDto user0 = msg("u0", Role.user, "a");
        ChatMessageDto user1 = msg("u1", Role.user, "b");
        ChatMessageDto user2 = msg("u2", Role.user, "c");
        List<ChatMessageDto> history = history(user0, user1, user2);

        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u1"), ctxWithMessages(history)));

        List<?> removed = (List<?>) result.newMessages().get(0).snipMetadata().get("removedUuids");
        assertEquals(List.of("u1"), removed, "完整 id 匹配仍生效（仅删 u1，区间 [1,2)）");
    }

    // ────────────────────────────────────────────────────────────────────
    // SnipTool 空匹配 → fail loud，不注入空 boundary（杜绝灾难截断）
    // ────────────────────────────────────────────────────────────────────

    @Test
    void execute_noMatch_returnsErrorWithoutBoundary() {
        List<ChatMessageDto> history = history(msg("u0", Role.user, "a"), msg("u1", Role.user, "b"));
        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("ghost-id"), ctxWithMessages(history)));

        assertTrue(result.data().contains("未匹配"),
            "未匹配到任何 user 消息 → fail loud（对齐 CCB 语义：不静默假成功）");
        assertTrue(result.newMessages().isEmpty(),
            "不注入空 removedUuids 的 boundary —— 否则 SnipCompactor slice 灾难截断（212→3 事故根因）");
    }

    // ────────────────────────────────────────────────────────────────────
    // maybeAppendSnipIdTags · 对齐 CCB messages.ts:2667-2686
    // ────────────────────────────────────────────────────────────────────

    @Test
    void maybeAppendSnipIdTags_injectsTagOnUserNonMetaOnly() {
        CompactSettingsResolver resolver = mock(CompactSettingsResolver.class);
        when(resolver.historySnipEnabled()).thenReturn(true);

        ChatMessageDto user = msg("u0", Role.user, "open baidu");
        ChatMessageDto asst = msg("a0", Role.assistant, "ok");
        ChatMessageDto metaUser = msg("u1", Role.user, "nudge text", null, true);
        List<ChatMessageDto> messages = List.of(user, asst, metaUser);

        List<ChatMessageDto> out = AgentLoopContext.maybeAppendSnipIdTags(null, resolver, messages);

        assertEquals(3, out.size(), "注入不增删消息数");
        String expectTag = "\n[id:" + SnipCompactor.deriveShortMessageId("u0") + "]";
        assertTrue(out.get(0).content().endsWith(expectTag),
            "user 非 isMeta 消息末尾追加 [id:短id] tag（对齐 CCB messages.ts:2667-2686）");
        assertEquals("ok", out.get(1).content(), "assistant 消息不加 tag（CCB 只给 user）");
        assertEquals("nudge text", out.get(2).content(), "isMeta user 消息不加 tag（CCB appendMessageTagToUserMessage isMeta 跳过）");
    }

    @Test
    void maybeAppendSnipIdTags_gateOff_returnsOriginalReference() {
        CompactSettingsResolver resolver = mock(CompactSettingsResolver.class);
        when(resolver.historySnipEnabled()).thenReturn(false);
        List<ChatMessageDto> messages = List.of(msg("u0", Role.user, "a"));

        assertSame(messages, AgentLoopContext.maybeAppendSnipIdTags(null, resolver, messages),
            "HISTORY_SNIP 关 → 原引用，零行为变化（对齐 CCB messages.ts:2673 门控）");
    }

    @Test
    void maybeAppendSnipIdTags_noUserMessages_returnsOriginalReference() {
        CompactSettingsResolver resolver = mock(CompactSettingsResolver.class);
        when(resolver.historySnipEnabled()).thenReturn(true);
        List<ChatMessageDto> messages = List.of(msg("a0", Role.assistant, "ok"), msg("t0", Role.tool, "r", "c0"));

        assertSame(messages, AgentLoopContext.maybeAppendSnipIdTags(null, resolver, messages),
            "无 user 非 meta 消息 → 原引用（无 tag 可注，零变化）");
    }

    // ────────────────────────────────────────────────────────────────────
    // 链路验证：SnipTool 只【添加 boundary】标记 removedUuids，不直接删除 state.messages；
    // 真正的剔除由下轮查询引擎（snipCompactIfNeeded）对模型请求面投影执行，state.messages 保留全量
    // ────────────────────────────────────────────────────────────────────

    @Test
    void execute_addsBoundaryOnly_doesNotDeleteState_removalHappensInQueryProjection() {
        // 历史：user0 → assistant0(tool_calls=call_0) → tool0(tool_result) → user1
        ChatMessageDto user0 = msg("u0", Role.user, "open baidu");
        ChatMessageDto asst0 = msg("a0", Role.assistant, "searching");
        ChatMessageDto tool0 = msg("t0", Role.tool, "result", "call_0");
        ChatMessageDto user1 = msg("u1", Role.user, "next");
        List<ChatMessageDto> history = history(user0, asst0, tool0, user1);
        int before = history.size();

        ToolResult<String> result =
            asToolResult(new SnipTool(snipEnabledFlags()).execute(snipCall("u0"), ctxWithMessages(history)));

        // ① SnipTool 不直接删除 state.messages —— history 保持原样（只返回 boundary，不改入参）
        assertEquals(before, history.size(), "SnipTool 只添加 boundary，不直接删除 state.messages（入参列表未被修改）");

        // ② boundary 在 newMessages 里，removedUuids 标记要 snipe 的区间（user → 下一 user 前）
        assertEquals(1, result.newMessages().size(), "newMessages 仅含 boundary");
        ChatMessageDto boundary = result.newMessages().get(0);
        List<?> removed = (List<?>) boundary.snipMetadata().get("removedUuids");
        assertEquals(List.of("u0", "a0", "t0"), removed, "boundary 标记区间 u0+assistant0+tool0");

        // ③ 模拟 ToolResultApplier.apply（boundary 追加到 state.messages）→ 下轮查询引擎投影剔除
        history.add(boundary);
        SnipCompactor.SnipResult snip = new SnipCompactor().snipCompactIfNeeded(history);
        assertTrue(snip.executed(), "boundary 存在即执行（CC snipCompact.ts:111-113）");
        List<String> projection = snip.messages().stream().map(ChatMessageDto::id).toList();
        assertFalse(projection.contains("u0"), "模型请求面剔除 u0");
        assertFalse(projection.contains("a0"), "模型请求面剔除 a0");
        assertFalse(projection.contains("t0"), "模型请求面剔除 t0（tool_use/tool_result 配对整段删）");
        assertTrue(projection.contains("u1"), "未 snipe 的 user1 保留");
        assertTrue(projection.contains(boundary.id()), "boundary 保留在投影后消息链（摘要替换）");

        // ④ state.messages 保留全量 —— 剔除只发生在请求面（投影），持久历史不受影响（F5 重拉仍在）
        assertEquals(before + 1, history.size(),
            "state.messages 保留全量（仅追加 boundary；被 snipe 消息 u0/a0/t0 仍在历史中，供 transcript/重拉）");
        assertTrue(history.stream().map(ChatMessageDto::id).toList().containsAll(List.of("u0", "a0", "t0")),
            "被 snipe 消息仍在 state.messages（Snip 是标记，非直接删除）");
    }
}
