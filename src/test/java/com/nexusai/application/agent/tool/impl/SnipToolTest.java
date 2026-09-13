package com.nexusai.application.agent.tool.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.api.DisplayName;
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
        // [snip-protect-recent D4] 5 条 user → 首条 u0 + 尾两条 u3/u4 受保护，u1 才是合法目标
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");
        SnipTool tool = new SnipTool(snipEnabledFlags());

        ToolResult<String> result = asToolResult(tool.execute(snipCall("u1"), ctxWithMessages(history)));

        JsonNode data = parse(result.data());
        assertEquals(1, data.path("snipped_count").asInt());
        assertEquals("Snipped 1 messages", data.path("summary").asText(),
            "无 reason 时 summary 回退 'Snipped N messages'（CC SnipTool.ts:88）");
        assertEquals("Snipped 1 messages", result.newMessages().get(0).content());
    }

    @Test
    void execute_ignoresMessageIdsNotInHistory() {
        // [snip-protect-recent] 5 条 user → 最后 2 条（u3/u4）受保护，u1/u2 才是合法目标
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");
        SnipTool tool = new SnipTool(snipEnabledFlags());

        // u1/u2 存在、ghost 不存在 → 只裁剪 u1,u2
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

        // boundary 追加到会话历史（ToolResultApplier.apply → state.rawMessages().addAll）
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
                // [snip-protect-recent D4] 首条 'up' 受保护（首尾双保护）→ u0/u1 落在中间、仍合法
                ctxWithMessages(history("up", "u0", "u1", "u2", "u3", "u4"))));

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
    // [snip-protect-recent 2026-09-13] 硬门：最后 KEEP_RECENT_USER_TURNS 条非 meta 用户消息不可 snip
    //   WHY（设计规范 §5.2）：这两条定义「当前任务」，被裁 → 模型丢失意图
    //   （用户症状「裁剪后 AI 不知道干嘛了」）。D3：整次失败（原子），绝不静默跳过。
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("点名最后一条用户消息 → 拒绝")
    void rejectsNewestUserMessage() {
        // 5 条 user → 受保护 = 最后 2 条（index 3,4）= u3,u4
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");

        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u4"), ctxWithMessages(history)));

        assertTrue(result.data().contains("不能 snip 最近 " + SnipCompactor.KEEP_RECENT_USER_TURNS
                + " 条用户消息"),
            "① 说明被保护的是最近 N 条用户消息");
        assertTrue(result.data().contains("定义了当前任务，必须始终保留在上下文中"),
            "② 说明原因（定义当前任务，必须留在上下文）");
        assertTrue(result.data().contains("请改用更早的用户消息"),
            "③ 给出可行动建议（改选更早的消息；错误里点名被保护项故重试可收敛）");
        assertTrue(result.data().contains("受保护（未裁剪）："),
            "错误点名列出了被保护的具体条目");
        assertTrue(result.newMessages().isEmpty(), "拒绝调用不注入 boundary");
    }

    @Test
    @DisplayName("点名最后第二条用户消息 → 拒绝")
    void rejectsSecondNewestUserMessage() {
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");

        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u3"), ctxWithMessages(history)));

        assertTrue(result.data().contains("不能 snip 最近 " + SnipCompactor.KEEP_RECENT_USER_TURNS
                + " 条用户消息"), "倒数第二条同样受保护");
        assertTrue(result.newMessages().isEmpty(), "拒绝调用不注入 boundary");
    }

    @Test
    @DisplayName("点名第三条倒数的用户消息 → 通过，且 removedUuids 不含任何保护项")
    void allowsThirdNewestAndNeverTouchesProtected() {
        // 设计规范 §2.4 的守护测试：拒绝保护项后，删除区间必然在保护区首条 user 处停下
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");

        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u2"), ctxWithMessages(history)));

        assertEquals(1, result.newMessages().size(), "合法目标 → 正常注入 boundary");
        List<?> removed = (List<?>) result.newMessages().get(0).snipMetadata().get("removedUuids");
        assertEquals(List.of("u2"), removed, "区间 [u2, u3) 在保护区首条 user（u3）处停下");
        assertFalse(removed.contains("u3"), "保护区（倒数第二）不被吞");
        assertFalse(removed.contains("u4"), "保护区（最后一条）不被吞");
    }

    @Test
    @DisplayName("合法与保护项混在一批 → 整次拒绝，removedUuids 为空（原子性）")
    void mixedBatchFailsAtomically() {
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");

        // u1 合法（倒数第四）+ u4 受保护（最后一条）→ 整次失败，u1 也不执行（D3 原子语义）
        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u1", "u4"), ctxWithMessages(history)));

        assertTrue(result.data().contains("本次请求已整体拒绝"),
            "混合批整次失败（不做部分成功，避免模型误以为全成功）");
        assertTrue(result.newMessages().isEmpty(),
            "removedUuids 为空 —— 合法的 u1 也不执行（原子性）");
    }

    @Test
    @DisplayName("拒绝日志的 requested 取模型点名条数（不是匹配到的条数）")
    void rejectLogCountsRequestedNotMatched() {
        // 点名 3 条（u1 合法 / ghost 不存在 / u4 受保护）→ 实际匹配到 2 条 → 拒绝。
        //   旧写法用 targetUserIndices.size() 会把日志写成「点名 2 条」，排障时误导。
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");
        ListAppender<ILoggingEvent> app = attachWarnCapture();

        try {
            ToolResult<String> result = asToolResult(new SnipTool(snipEnabledFlags())
                .execute(snipCall("u1", "ghost", "u4"), ctxWithMessages(history)));

            assertTrue(result.data().contains("本次请求已整体拒绝"), "前置：整次拒绝已发生");
            List<String> logs = app.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(logs.stream().anyMatch(m -> m.contains("requested=3")),
                "日志 requested 必须 = 模型点名条数 3（与同方法既有那条同口径）");
            assertFalse(logs.stream().anyMatch(m -> m.contains("requested=2")),
                "不得写成匹配到的 2 条（点名 3 条只匹配到 2 条时报 2 会误导排障）");
            assertTrue(logs.stream().anyMatch(
                    m -> m.contains(SnipCompactor.deriveShortMessageId("u4"))),
                "日志点名列出了被保护的具体条目");
        } finally {
            detachWarnCapture(app);
        }
    }

    @Test
    @DisplayName("点名首条用户消息 → 拒绝（D4 首尾双保护）")
    void rejectsFirstUserMessage() {
        // D4：首条承载原始任务陈述 + 约束。硬门若只保尾部，模型可裁 U₁ ——
        //   模型处境与用户报告的「裁完 AI 不知道干嘛了」同构（触发点换成「最早那条」）
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");

        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u0"), ctxWithMessages(history)));

        assertTrue(result.data().contains("不能 snip 最近 " + SnipCompactor.KEEP_RECENT_USER_TURNS
                + " 条用户消息"), "★ 点名首条（index 0）必须被拒 —— 硬门下界补齐");
        assertTrue(result.newMessages().isEmpty(), "拒绝调用不注入 boundary");
    }

    @Test
    @DisplayName("首条与尾条混点名 → 整次拒绝（原子性对 D4 同样成立）")
    void rejectsFirstAndTailMixedBatchAtomically() {
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", "u4");

        // u0 首条 + u4 尾条 都受保护，u1 合法 → 整次失败
        ToolResult<String> result = asToolResult(new SnipTool(snipEnabledFlags())
            .execute(snipCall("u0", "u1", "u4"), ctxWithMessages(history)));

        assertTrue(result.data().contains("本次请求已整体拒绝"), "首尾混合批同样整次失败");
        assertTrue(result.newMessages().isEmpty(), "合法的 u1 也不执行（原子性）");
        assertTrue(result.data().contains(SnipCompactor.deriveShortMessageId("u0"))
                && result.data().contains(SnipCompactor.deriveShortMessageId("u4")),
            "错误点名列出了首条与尾条两个保护项");
    }

    @Test
    @DisplayName("用完整 UUID 点名保护项 → 也被拒")
    void rejectsProtectedTargetByFullId() {
        // 设计规范 §6 边界 6：完整 UUID 形态点名保护项，硬门同样覆盖（resolveTargetUserIndices 两种形态都匹配，硬门在其后）
        String newestFullId = UUID.randomUUID().toString();
        List<ChatMessageDto> history = history("u0", "u1", "u2", "u3", newestFullId);

        ToolResult<String> result = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall(newestFullId), ctxWithMessages(history)));

        assertTrue(result.data().contains("不能 snip 最近 " + SnipCompactor.KEEP_RECENT_USER_TURNS
                + " 条用户消息"), "完整 UUID 形态点名保护项也被拒");
        assertTrue(result.newMessages().isEmpty(), "拒绝调用不注入 boundary");
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

    /** 挂 WARN 捕获到 SnipTool logger（照 SqliteBusyRetryTest:289 同构）。 */
    private static ListAppender<ILoggingEvent> attachWarnCapture() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SnipTool.class);
        logger.setLevel(Level.WARN);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachWarnCapture(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SnipTool.class);
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(null); // 恢复继承（logback-test.xml 的 com.nexusai 级别），不污染同 JVM 其它用例
    }
}
