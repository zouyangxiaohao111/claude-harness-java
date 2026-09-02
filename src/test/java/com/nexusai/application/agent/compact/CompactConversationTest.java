package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.compact.CompactProgressEvent.HooksStart;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import java.nio.file.Files;
import java.nio.file.Path;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMP-04 · compactConversation 主流程重建单测 · 对齐 CC compact.ts:387-763。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-04 的目标是按 CC 单函数语义
 * 重建全量压缩主流程（REQ-01/03/04/06）。本测试逐条验证 IMP-04 §5 验收标准：
 * <ol>
 *   <li>空输入抛 NOT_ENOUGH_MESSAGES（REQ-01，compact.ts:398）</li>
 *   <li>hooks 指令合并：user 在前 hook 在后，空串→undefined（compact.ts:374-381）</li>
 *   <li>buildPostCompactMessages 顺序 boundary→summary→messagesToKeep→attachments→hookResults（compact.ts:330-338，INV-2）</li>
 *   <li>CompactionResult 10 字段契约（compact.ts:299-310）</li>
 *   <li>无摘要 / API 前缀抛错（compact.ts:493-515）</li>
 *   <li>错误通知跳过 USER_ABORT / NOT_ENOUGH，仅 !isAutoCompact 加通知（compact.ts:1108-1123）</li>
 *   <li>单流程 5 事件顺序 pre_compact→compact_start→session_start→post_compact→compact_end（compact.ts:406/429/587/719/760，INV-1）</li>
 *   <li>PTL 重试循环（MAX_PTL_RETRIES=3，compact.ts:227/450-491）</li>
 * </ol>
 */
class CompactConversationTest {

    private static final String SESSION = "s1";

    @TempDir
    Path tempDir;

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** 构造带记录事件列表 + fake 摘要生产者的上下文。 */
    private static CompactConversationContext ctx(CompactConversation.SummaryProducer producer,
                                                  List<CompactProgressEvent> events) {
        CompactConversationContext c = new CompactConversationContext();
        c.setSessionId(SESSION);
        c.setAgentId("main");
        c.setModel("claude-sonnet-4-5");
        c.setQuerySource("compact");
        c.setSummaryProducer(producer);
        c.setOnCompactProgress(events::add);
        return c;
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1 · 空输入抛 NOT_ENOUGH_MESSAGES（REQ-01）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("空输入抛 ERROR_MESSAGE_NOT_ENOUGH_MESSAGES (compact.ts:398)")
    void emptyMessagesThrowsNotEnoughMessages() {
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> new CompactConversation.SummaryResult("ok", null), new ArrayList<>());
        assertThatThrownBy(() -> CompactConversation.compactConversation(
                List.of(), c, false, null, false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2 · mergeHookInstructions（compact.ts:374-381）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("hooks 指令合并: user 在前 hook 在后，空串→undefined")
    void mergeHookInstructionsUserFirstHookAfter() {
        // user 在前，hook 在后
        assertThat(CompactConversation.mergeHookInstructions("user ctx", "hook ctx"))
            .isEqualTo("user ctx\n\nhook ctx");
        // 仅 user
        assertThat(CompactConversation.mergeHookInstructions("user ctx", null))
            .isEqualTo("user ctx");
        // 仅 hook
        assertThat(CompactConversation.mergeHookInstructions(null, "hook ctx"))
            .isEqualTo("hook ctx");
        // 双空 → undefined
        assertThat(CompactConversation.mergeHookInstructions(null, null)).isNull();
        // 空白串归一为 undefined（CC !hookInstructions 真值语义）
        assertThat(CompactConversation.mergeHookInstructions("  ", "")).isNull();
        // user 保留、hook 空白 → user
        assertThat(CompactConversation.mergeHookInstructions("user ctx", "   ")).isEqualTo("user ctx");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · buildPostCompactMessages 顺序/完整性（INV-2，OD-04）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("buildPostCompactMessages 顺序: boundary→summary→messagesToKeep→attachments→hookResults")
    void buildPostCompactMessagesOrder() {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createCompactBoundaryMessage(
            "manual", 100, null, null, null);
        List<ChatMessageDto> summary = List.of(msg("sum", Role.user, "summary content"));
        List<ChatMessageDto> keep = List.of(msg("keep1", Role.user, "kept recent"), msg("keep2", Role.user, "kept recent 2"));
        List<ChatMessageDto> attachments = List.of(msg("att", Role.user, "attachment"));
        List<ChatMessageDto> hooks = List.of(msg("hook", Role.user, "hook message"));

        CompactionResult result = new CompactionResult(
            boundary, summary, attachments, hooks, keep, null,
            100, 50, 30, null);

        List<ChatMessageDto> built = CompactionResult.buildPostCompactMessages(result);
        List<String> ids = built.stream().map(ChatMessageDto::id).toList();
        // boundary → summary → keep1 → keep2 → attachments → hookResults
        assertThat(ids).containsExactly("compact-boundary-compact_boundary", "sum", "keep1", "keep2", "att", "hook");
        // L4 尾段不丢: messagesToKeep 完整保留（OD-04）
        assertThat(built).hasSize(6);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 8 · CompactionResult 10 字段契约（compact.ts:299-310）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CompactionResult 10 字段契约（boundaryMarker/summaryMessages/attachments/hookResults/messagesToKeep/userDisplayMessage/pre/post/truePost/compactionUsage）")
    void compactionResultHasTenFields() {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createCompactBoundaryMessage("auto", 200, null, null, null);
        CompactionResult result = new CompactionResult(
            boundary,
            List.of(msg("s", Role.user, "summary")),
            List.of(msg("a", Role.user, "att")),
            List.of(msg("h", Role.user, "hook")),
            null,
            "display",
            200,
            80,
            40,
            new CompactConversation.TokenUsage(190, 10, 0, 0)
        );
        // 10 字段逐个断言
        assertThat(result.boundaryMarker()).isSameAs(boundary);
        assertThat(result.summaryMessages()).hasSize(1);
        assertThat(result.attachments()).hasSize(1);
        assertThat(result.hookResults()).hasSize(1);
        assertThat(result.messagesToKeep()).isNull();
        assertThat(result.userDisplayMessage()).isEqualTo("display");
        assertThat(result.preCompactTokenCount()).isEqualTo(200);
        assertThat(result.postCompactTokenCount()).isEqualTo(80);
        assertThat(result.truePostCompactTokenCount()).isEqualTo(40);
        assertThat(result.compactionUsage()).isNotNull();
        assertThat(result.compactionUsage().total()).isEqualTo(200);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 9 · 无摘要 / API 前缀抛错（compact.ts:493-515）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无摘要文本 → 抛 Failed to generate conversation summary (compact.ts:493-506)")
    void noSummaryThrows() {
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> new CompactConversation.SummaryResult(null, null), new ArrayList<>());
        assertThatThrownBy(() -> CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), c, false, null, false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to generate conversation summary");
    }

    @Test
    @DisplayName("摘要为 API 错误前缀 → 抛原始错误 (compact.ts:507-515)")
    void apiErrorPrefixThrows() {
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> new CompactConversation.SummaryResult(
            "API Error: Request was aborted.", null), new ArrayList<>());
        assertThatThrownBy(() -> CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), c, false, null, false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("API Error: Request was aborted.");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 6 · 错误通知跳过 USER_ABORT / NOT_ENOUGH；仅 !isAutoCompact
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("错误通知: 跳过 USER_ABORT / NOT_ENOUGH，仅 !isAutoCompact 加通知")
    void errorNotificationSkipsUserAbortAndNotEnough() {
        // NOT_ENOUGH_MESSAGES → 不加通知
        assertThat(CompactConversation.shouldAddErrorNotification(
                new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_NOT_ENOUGH_MESSAGES))).isFalse();
        // USER_ABORT → 不加通知
        assertThat(CompactConversation.shouldAddErrorNotification(
                new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_USER_ABORT))).isFalse();
        // 其他错误 → 加通知
        assertThat(CompactConversation.shouldAddErrorNotification(
                new IllegalArgumentException(CompactConstants.ERROR_MESSAGE_PROMPT_TOO_LONG))).isTrue();
    }

    @Test
    @DisplayName("自动压缩失败不加错误通知 (compact.ts:752-756)")
    void autoCompactFailureSkipsNotification() {
        List<CompactProgressEvent> events = new ArrayList<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> new CompactConversation.SummaryResult(null, null), events);
        List<CompactConversation.CompactionNotification> notifications = new ArrayList<>();
        c.setNotification(notifications::add);
        // isAutoCompact=true → catch 块不加通知
        assertThatThrownBy(() -> CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), c, false, null, true, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(notifications).isEmpty();
    }

    @Test
    @DisplayName("手动压缩失败加 'Error compacting conversation' 通知")
    void manualCompactFailureAddsNotification() {
        List<CompactConversation.CompactionNotification> notifications = new ArrayList<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> new CompactConversation.SummaryResult(null, null), new ArrayList<>());
        c.setNotification(notifications::add);
        assertThatThrownBy(() -> CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), c, false, null, false, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).key()).isEqualTo("error-compacting-conversation");
        assertThat(notifications.get(0).text()).isEqualTo("Error compacting conversation");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · 单流程 5 事件顺序（INV-1）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("成功路径恰 5 事件且顺序符合 CC（pre_compact→compact_start→session_start→post_compact→compact_end）")
    void successPathEmitsFiveEventsInCcOrder() {
        List<CompactProgressEvent> events = new ArrayList<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> new CompactConversation.SummaryResult("summary ok", new CompactConversation.TokenUsage(10, 5, 0, 0)), events);
        List<ChatMessageDto> messages = List.of(
            msg("u1", Role.user, "first user message"),
            msg("a1", Role.assistant, "assistant reply"),
            msg("u2", Role.user, "second user message"));
        CompactConversation.compactConversation(messages, c, true, null, false, null);

        assertThat(events).hasSize(5);
        assertThat(events.get(0)).isEqualTo(new HooksStart(HooksStart.HookType.PRE_COMPACT));
        assertThat(events.get(1)).isEqualTo(new CompactProgressEvent.CompactStart());
        assertThat(events.get(2)).isEqualTo(new HooksStart(HooksStart.HookType.SESSION_START));
        assertThat(events.get(3)).isEqualTo(new HooksStart(HooksStart.HookType.POST_COMPACT));
        assertThat(events.get(4)).isEqualTo(new CompactProgressEvent.CompactEnd());
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 · PTL 重试循环（MAX_PTL_RETRIES=3）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PTL 前缀摘要触发重试：第二次成功返回摘要（compact.ts:450-491）")
    void ptlRetryThenSucceeds() {
        List<String> calls = new ArrayList<>();
        CompactConversation.SummaryProducer producer = (messages, prompt, preTokens) -> {
            calls.add("call-" + messages.size());
            if (calls.size() == 1) {
                return new CompactConversation.SummaryResult("Prompt is too long. Try reducing the length of the messages.", null);
            }
            return new CompactConversation.SummaryResult("valid summary", new CompactConversation.TokenUsage(10, 5, 0, 0));
        };
        CompactConversationContext c = ctx(producer, new ArrayList<>());
        // 交替 user/assistant 以便 groupMessagesByApiRound 产出 ≥2 组（PTL 截断才有可丢组）
        List<ChatMessageDto> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        CompactionResult result = CompactConversation.compactConversation(messages, c, true, null, false, null);
        // 两次调用（PTL 重试一次）
        assertThat(calls).hasSize(2);
        assertThat(result.summaryMessages()).isNotEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 · 附件恢复（INV-15）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("readFileState 缓存：压缩前缓存 → 清空 → 附件恢复 5 文件上限")
    void readFileStateCachedThenClearedAndRestored() throws Exception {
        // [R1 A-03] 附件恢复重读磁盘（restoreFileAttachments contentReader=readFileFresh），
        //   测试须创建真实 temp 文件供重读；仅快照 content 而无磁盘文件 → 重读失败被跳过。
        java.nio.file.Path f1 = tempDir.resolve("f1.txt");
        java.nio.file.Path f2 = tempDir.resolve("f2.txt");
        java.nio.file.Path f3 = tempDir.resolve("f3.txt");
        Files.writeString(f1, "content1");
        Files.writeString(f2, "content2");
        Files.writeString(f3, "content3");
        Map<String, CompactConversation.ReadFileState> readFileState = new LinkedHashMap<>();
        readFileState.put(f1.toString(), new CompactConversation.ReadFileState("content1", 5));
        readFileState.put(f2.toString(), new CompactConversation.ReadFileState("content2", 4));
        readFileState.put(f3.toString(), new CompactConversation.ReadFileState("content3", 3));

        CompactConversationContext c = ctx((messages, prompt, preTokens) -> new CompactConversation.SummaryResult("summary ok", null), new ArrayList<>());
        c.setReadFileState(readFileState);
        c.setSessionId("s1");

        CompactionResult result = CompactConversation.compactConversation(
            List.of(msg("u", Role.user, "hi")), c, true, null, false, null);

        // 缓存已清空
        assertThat(readFileState).isEmpty();
        // 附件恢复: 最近文件优先
        assertThat(result.attachments()).isNotEmpty();
        assertThat(result.attachments()).hasSize(3);
    }

    @Test
    @DisplayName("A13: loadedNestedMemoryPaths 压缩后清空（CC compact.ts:522）——防压缩后 memory 文件重注入依赖陈旧 Set")
    void loadedNestedMemoryPathsClearedAfterCompact() {
        // WHY: CC compactConversation 在 readFileState.clear() 后紧跟 context.loadedNestedMemoryPaths?.clear()
        // （compact.ts:522）。该 Set 是 memory 文件重注入去重双源之一（跨域 CM-F1），压缩后不复位会导致
        // 压缩前的嵌套记忆路径被错误视为"已加载"而跳过重注入（OPD-CM5-A-08 拍板）。
        java.util.Set<String> triggers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<String> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
        loaded.add("/mem/nested/a.md");
        loaded.add("/mem/nested/b.md");
        java.util.Set<String> dynamic = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<String> discovered = java.util.concurrent.ConcurrentHashMap.newKeySet();

        com.nexusai.application.agent.tool.ToolUseContext tuc = new com.nexusai.application.agent.tool.ToolUseContext(
            java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), List.of(), "",
            null, List.of(), null, com.nexusai.application.agent.permission.PermissionMode.DEFAULT, Map.of(), false, "",
            null, null, Map.of(), null,
            null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            false, triggers, loaded, dynamic, discovered,
            null, false, false, null, null, null, null, null,
            null);

        CompactConversationContext c = ctx((messages, prompt, preTokens) ->
            new CompactConversation.SummaryResult("summary ok", null), new ArrayList<>());
        c.setToolUseContext(tuc);

        CompactConversation.compactConversation(
            List.of(msg("u", Role.user, "hi")), c, true, null, false, null);

        // 压缩完成后清空已加载嵌套记忆路径（对齐 CC compact.ts:522 A13）
        assertThat(tuc.loadedNestedMemoryPaths()).isEmpty();
    }

    @Test
    @DisplayName("全量压缩不去重: messages 含 assistant tool_use Read（file_path 与 readFileState key 一致）该文件仍被恢复（CC compact.ts:533-537 默认 []）")
    void fullCompactDoesNotDedupReadPathsFromHistory() throws Exception {
        // [R1 A-03] 附件恢复重读磁盘（contentReader=readFileFresh）：absPath 须为真实 temp 文件，
        //   否则重读失败被跳过（快照 content 不再作为恢复内容源）。
        java.nio.file.Path realFile = tempDir.resolve("readme.md");
        Files.writeString(realFile, "readme content");
        String absPath = realFile.toString();
        Map<String, CompactConversation.ReadFileState> readFileState = new LinkedHashMap<>();
        readFileState.put(absPath, new CompactConversation.ReadFileState("readme content", 5));

        // assistant tool_use Read（file_path = 绝对路径，与 readFileState key 完全一致）
        ObjectNode toolUse = JsonNodeFactory.instance.objectNode();
        toolUse.put("type", "tool_use");
        toolUse.put("name", "Read");
        toolUse.set("input", JsonNodeFactory.instance.objectNode().put("file_path", absPath));
        ChatMessageDto assistant = new ChatMessageDto("a1", SESSION, Role.assistant, "assistant",
            null, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(toolUse), List.of(), null, false, false);

        CompactConversationContext c = ctx((messages, prompt, preTokens) -> new CompactConversation.SummaryResult("summary ok", null), new ArrayList<>());
        c.setReadFileState(readFileState);

        CompactionResult result = CompactConversation.compactConversation(
            List.of(msg("u1", Role.user, "user message"), assistant), c, true, null, false, null);

        // 全量压缩 preserved=[]（compact.ts:533-537）→ 不去重 Read 路径 → 该文件仍被恢复
        // （F1 回归防护：若误传 messages 作 preserved，readFileState key 与 tool_use file_path 一致时会被跳过）
        assertThat(result.attachments()).isNotEmpty();
        assertThat(result.attachments())
            .anyMatch(a -> a.content() != null && a.content().contains(absPath));
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP2-13 · △-3 PTL retry fork 前缀 re-save + △-2 PTL gap 数据源
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-3: PTL 重试后 CacheSafeParamsHolder re-save forkContextMessages=截断集（对齐 CC compact.ts:487-490）")
    void ptlRetry_reSavesForkPrefixToHolder() {
        // 压缩前槽位已 save（模拟 LlmAgentLoop/CompactCommand 压缩前 CacheSafeParamsHolder.save）
        List<ChatMessageDto> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        CacheSafeParams initial = new CacheSafeParams(
            List.of("sys"), Map.of(), Map.of(), baseToolUseContext(), new ArrayList<>(messages));
        CacheSafeParamsHolder.save(initial);
        try {
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<List<ChatMessageDto>> lastSummarized = new AtomicReference<>();
            CompactConversation.SummaryProducer producer = (msgs, prompt, preTokens) -> {
                lastSummarized.set(msgs);
                if (calls.getAndIncrement() == 0) {
                    return new CompactConversation.SummaryResult(
                        "Prompt is too long. Try reducing the length of the messages.", null);
                }
                return new CompactConversation.SummaryResult(
                    "valid summary", new CompactConversation.TokenUsage(10, 5, 0, 0));
            };
            CompactConversation.compactConversation(messages, ctx(producer, new ArrayList<>()), true, null, false, null);

            // 重试轮 summarize 收到截断后消息（PTL 截断生效）
            assertThat(lastSummarized.get()).as("第二次 summarize 必须收到截断集").isNotNull();
            // [IMP2-15 △-17] CC 分组：preamble 自成 group 0（grouping.ts:43-48），
            //   本测试消息形状（u0,a1,u2,...）→ 6 组；20% 兜底丢 1 组（u0）→ 截断集 =
            //   [PTL_RETRY_MARKER, a1,u2,...,a9]（首条 assistant → 前置合成标记，compact.ts:512-517）
            //   = 10 条 == 原始条数（标记替换 preamble 而非净减）——旧分组（preamble 并入首组）
            //   时丢 2 条净减，断言"小于"是旧语义。CC 语义下以"首条为标记 + 内容已截断"为判据。
            assertThat(lastSummarized.get().size())
                .as("截断集条数 ≤ 原始条数（标记替换 preamble，CC 语义）")
                .isLessThanOrEqualTo(messages.size());
            assertThat(lastSummarized.get().get(0).content())
                .as("截断集首条为 PTL_RETRY_MARKER（group 0 被丢且首条为 assistant → 前置合成标记）")
                .isEqualTo(CompactConstants.PTL_RETRY_MARKER);
            // △-3 RED teeth：holder forkContextMessages 必须 re-save 为该轮实际摘要消息
            //（CC compact.ts:487-490 retryCacheSafeParams.forkContextMessages = truncated；
            //  旧实现不更新 → holder 仍为压缩前全量 → fork 重试轮缓存前缀偏移）
            CacheSafeParams updated = CacheSafeParamsHolder.get();
            assertThat(updated).as("holder 槽位必须仍在（压缩期间不清空）").isNotNull();
            assertThat(updated.forkContextMessages())
                .as("PTL 重试后 fork 前缀必须 re-save 为截断集（对齐 CC compact.ts:487-490）")
                .isEqualTo(lastSummarized.get());
        } finally {
            CacheSafeParamsHolder.clear();
        }
    }

    @Test
    @DisplayName("△-2: PTL gap 数据源=摘要文本（01 侧保留口径 · Java 解析摘要文本 vs CC msg.errorDetails）")
    void ptlGap_gapParsedFromSummaryText() {
        // 摘要文本携带数字 → gap = actual - limit
        assertThat(CompactConversation.getPromptTooLongTokenGap(
            "Prompt is too long. Try reducing the length of the messages. 100000 tokens > 80000"))
            .as("摘要文本解析 gap = actual-limit = 20000（保留口径：数据源=摘要文本）")
            .isEqualTo(20_000);
        // 无数字 → null（落 20% 兜底）
        assertThat(CompactConversation.getPromptTooLongTokenGap("no numbers here")).isNull();
        // 无 PTL 前缀 → null
        assertThat(CompactConversation.getPromptTooLongTokenGap("100000 tokens > 80000")).isNull();
        // gap ≤ 0 → null（actual ≤ limit 非 PTL）
        assertThat(CompactConversation.getPromptTooLongTokenGap(
            "Prompt is too long. 5000 tokens > 9000")).isNull();
        assertThat(CompactConversation.getPromptTooLongTokenGap(null)).isNull();
    }

    @Test
    @DisplayName("△-2: truncateHeadForPTLRetry 用 gap 驱动丢弃组（gap 覆盖不足才落 20% 兜底）")
    void truncateHeadForPTLRetry_usesGapFromSummaryText() {
        // 3 组：组1 ≈ 30 tokens（100+10+10 字符）、组2 ≈ 6、组3 ≈ 6
        List<ChatMessageDto> messages = List.of(
            msg("u0", Role.user, "x".repeat(100)),
            msg("a0", Role.assistant, "resp 0"),
            msg("u1", Role.user, "msg 1"),
            msg("a1", Role.assistant, "resp 1"),
            msg("u2", Role.user, "msg 2"),
            msg("a2", Role.assistant, "resp 2"),
            msg("u3", Role.user, "msg 3"));
        // gap=40：组1(30) < 40 → 组2(+6=36) < 40 → 组3(+6=42 ≥ 40) → drop 3 组 → 上限 groups-1=2
        // → 丢组1+组2，保留组3（2 条）。20% 兜底只会丢 1 组（4 条）——gap 驱动与兜底可区分。
        List<ChatMessageDto> truncated = CompactConversation.truncateHeadForPTLRetry(
            messages, "Prompt is too long. 40 tokens > 0");
        assertThat(truncated).isNotNull();
        assertThat(truncated.size())
            .as("gap=40 应覆盖组1+组2 → 仅保留组3（2 条）+ 首条 assistant 前置 PTL_RETRY_MARKER（CC compact.ts:512-517）")
            .isEqualTo(3);
        assertThat(truncated.get(0).isMeta())
            .as("保留组首条为 assistant → 前置 PTL_RETRY_MARKER meta user（CC compact.ts:512-517）")
            .isTrue();
        assertThat(truncated.get(0).content()).isEqualTo(CompactConstants.PTL_RETRY_MARKER);
        assertThat(truncated.get(1).id()).isEqualTo("a2");
    }

    /** 最小 ToolUseContext（供 CacheSafeParams 构造）。 */
    private static com.nexusai.application.agent.tool.ToolUseContext baseToolUseContext() {
        return new com.nexusai.application.agent.tool.ToolUseContext(
            java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new com.nexusai.application.agent.tool.AbortController(), List.of());
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP2-14 · ✗-5 preCompactDiscoveredTools 全量路径 + ✗-8 摘要可观察性
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("✗-5: 全量压缩后 boundary compactMetadata 携带 preCompactDiscoveredTools（compact.ts:606-611）")
    void fullCompactBoundaryCarriesPreCompactDiscoveredTools() {
        ObjectNode toolResult = JsonNodeFactory.instance.objectNode();
        toolResult.put("type", "tool_result");
        com.fasterxml.jackson.databind.node.ArrayNode refs = toolResult.putArray("content");
        ObjectNode ref1 = refs.addObject();
        ref1.put("type", "tool_reference");
        ref1.put("tool_name", "Read");
        ObjectNode ref2 = refs.addObject();
        ref2.put("type", "tool_reference");
        ref2.put("tool_name", "ToolSearch");
        ChatMessageDto userWithRef = new ChatMessageDto(
            "u-ref", SESSION, Role.user, "user", "discovered 工具已加载",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(toolResult), List.of(), null, false, false, null);
        List<ChatMessageDto> messages = List.of(
            msg("u1", Role.user, "first"),
            msg("a1", Role.assistant, "resp"),
            userWithRef);

        CompactionResult result = CompactConversation.compactConversation(
            messages, ctx((m, prompt, preTokens) ->
                new CompactConversation.SummaryResult("valid summary",
                    new CompactConversation.TokenUsage(10, 5, 0, 0)),
                new ArrayList<>()), true, null, false, null);

        // ✗-5 验收：全量压缩 boundary compactMetadata.preCompactDiscoveredTools 正确填充
        assertThat(result.boundaryMarker().compactMetadata()).isNotNull();
        assertThat(result.boundaryMarker().compactMetadata().preCompactDiscoveredTools())
            .as("tool_reference 提取 + 按名排序（CC [...set].sort()）")
            .containsExactly("Read", "ToolSearch");
        // 序列化闭环：boundary → ChatMessageDto 元数据随行（△-6/△-16）
        assertThat(result.boundaryMarker().toChatMessageDto().compactMetadata()
                .get("preCompactDiscoveredTools"))
            .asList().containsExactly("Read", "ToolSearch");
    }

    @Test
    @DisplayName("✗-5: 无 tool_reference 时 boundary 不写 preCompactDiscoveredTools（compact.ts:607 if size>0）")
    void fullCompactWithoutDiscoveredToolsOmitsField() {
        CompactionResult result = CompactConversation.compactConversation(
            List.of(msg("u1", Role.user, "first"), msg("a1", Role.assistant, "resp")),
            ctx((m, prompt, preTokens) ->
                new CompactConversation.SummaryResult("valid summary",
                    new CompactConversation.TokenUsage(10, 5, 0, 0)),
                new ArrayList<>()), true, null, false, null);

        assertThat(result.boundaryMarker().compactMetadata().preCompactDiscoveredTools())
            .as("无发现工具 → 空列表（compact.ts:607-611 if size>0 不写字段）")
            .isEmpty();
    }

    @Test
    @DisplayName("✗-5: extractDiscoveredToolNames boundary carry + tool_reference 扫描（toolSearch.ts:545-592）")
    void extractDiscoveredToolNamesCarriesBoundaryAndScansReferences() {
        // boundary carry：已有 boundary 的 compactMetadata.preCompactDiscoveredTools 带过压缩边界
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createCompactBoundaryMessage(
            "auto", 100, "parent", null, null);
        boundary = boundary.withCompactMetadata(new CompactBoundaryMessage.CompactMetadata(
            "auto", 100, null, null, List.of("CarriedTool"), null));
        ObjectNode toolResult = JsonNodeFactory.instance.objectNode();
        toolResult.put("type", "tool_result");
        com.fasterxml.jackson.databind.node.ArrayNode refs = toolResult.putArray("content");
        ObjectNode ref1 = refs.addObject();
        ref1.put("type", "tool_reference");
        ref1.put("tool_name", "FreshTool");
        ObjectNode ref2 = refs.addObject();
        ref2.put("type", "tool_reference");
        ref2.put("tool_name", "FreshTool"); // 重复 → Set 去重
        ChatMessageDto userWithRef = new ChatMessageDto(
            "u-ref", SESSION, Role.user, "user", "x",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(toolResult), List.of(), null, false, false, null);
        // 非 tool_result 块 / 非 user 消息不扫描
        ObjectNode textBlock = JsonNodeFactory.instance.objectNode();
        textBlock.put("type", "text");
        textBlock.put("text", "plain");
        ChatMessageDto userText = new ChatMessageDto(
            "u-text", SESSION, Role.user, "user", "y",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null,
            List.of(textBlock), List.of(), null, false, false, null);

        java.util.Set<String> discovered = CompactConversation.extractDiscoveredToolNames(
            List.of(boundary.toChatMessageDto(), userWithRef, userText));

        assertThat(discovered)
            .as("boundary carry + tool_reference 扫描合并（Set 去重，toolSearch.ts:545-592）")
            .containsExactlyInAnyOrder("CarriedTool", "FreshTool");
    }

    @Test
    @DisplayName("✗-8: buildCompactSummaryMessage 携带 isCompactSummary/isVisibleInTranscriptOnly 观察点（compact.ts:614-624）")
    void compactSummaryMessageCarriesObservabilityFlags() throws Exception {
        ChatMessageDto summary = CompactConversation.buildCompactSummaryMessage("summary text");

        assertThat(summary.isCompactSummary())
            .as("CC createUserMessage({isCompactSummary: true})")
            .isTrue();
        assertThat(summary.isVisibleInTranscriptOnly())
            .as("CC createUserMessage({isVisibleInTranscriptOnly: true})")
            .isTrue();
        assertThat(summary.subtype()).isEqualTo(CompactConversation.SUMMARY_SUBTYPE);
        // JSON 序列化可观察（✗-8 验收：字段在消息链可观察）
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .writeValueAsString(summary);
        assertThat(json).contains("\"isCompactSummary\":true");
        assertThat(json).contains("\"isVisibleInTranscriptOnly\":true");
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP2-15 · △-18 无摘要判据 / △-17 分组 id 源 / △-7 摘要标记
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("△-18: 纯空白摘要不抛错（CC !summary 仅拒绝 null/''；isBlank 为 Java 独有偏移 compact.ts:493）")
    void whitespaceOnlySummary_PassesLikeCc() {
        CompactConversationContext c = ctx((messages, prompt, preTokens) ->
            new CompactConversation.SummaryResult("   ", null), new ArrayList<>());
        // CC：!summary 对 "   " 为 false → 放行（空白串继续走 formatCompactSummary/摘要消息构建）；
        // Java 旧实现 isBlank() 拒绝 → 抛 no_summary（△-18 偏移）。RED teeth：改回 isBlank 则红。
        CompactionResult result = CompactConversation.compactConversation(
            List.of(msg("u", Role.user, "hi")), c, false, null, false, null);
        assertThat(result.summaryMessages())
            .as("空白摘要放行 → 正常产出摘要消息（CC !summary 判据）")
            .isNotEmpty();
    }

    @Test
    @DisplayName("△-18: 空串摘要仍抛 no_summary（CC !'' = truthy 拒绝，compact.ts:493）")
    void emptySummary_StillThrows() {
        CompactConversationContext c = ctx((messages, prompt, preTokens) ->
            new CompactConversation.SummaryResult("", null), new ArrayList<>());
        assertThatThrownBy(() -> CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), c, false, null, false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to generate conversation summary");
    }

    @Test
    @DisplayName("△-17: 分组 id 源=API round id；id 缺失时不制造随机 id（CC grouping.ts:46 无随机回退）")
    void groupMessagesByApiRound_nullIdsMergeLikeCc() {
        // 无 id / 无 assistantMessageId 的 assistant（CC msg.message.id undefined 语义）：
        // CC 中 consecutive undefined id → 不触发边界 → 全部同组；Java 旧实现回退
        // UUID.randomUUID()（每次不同）→ 拆组（△-17）。RED teeth：加回随机回退则红。
        ChatMessageDto a1 = assistant(null, null, "resp 1");
        ChatMessageDto a2 = assistant(null, null, "resp 2");
        List<List<ChatMessageDto>> groups = CompactConversation.groupMessagesByApiRound(
            List.of(msg("u0", Role.user, "hi"), a1, a2));
        assertThat(groups)
            .as("null id 连续 assistant 同组（CC undefined===undefined 不触发边界）")
            .hasSize(1);
    }

    @Test
    @DisplayName("△-17: 同一 API round 分块共享 assistantMessageId → 同组（CC grouping.ts:22-63 边界=message.id 变化）")
    void groupMessagesByApiRound_sharedRoundIdStaysTogether() {
        ChatMessageDto a1 = assistant("a1", "round-1", "tu_A");
        ChatMessageDto a2 = assistant("a2", "round-1", "tu_B"); // 同 round：CC [tu_A(id=X), result_A, tu_B(id=X)] 同组
        ChatMessageDto a3 = assistant("a3", "round-2", "next round");
        List<List<ChatMessageDto>> groups = CompactConversation.groupMessagesByApiRound(
            List.of(msg("u0", Role.user, "hi"), a1, a2, a3));
        // CC：preamble（首个 assistant 之前）自成 group 0（grouping.ts:43-48 边界
        //   message.id !== lastAssistantId && current.length>0 —— 首个 assistant 的
        //   id !== undefined 且 current=[u0] 非空 → 边界先于 a1 触发）；round-1 两分块同组；
        //   round-2 另起。旧实现 lastAssistantId==null 守卫把 preamble 并入首组（△-17 修正）。
        assertThat(groups).as("preamble 组0 + round-1 同组 + round-2 另组").hasSize(3);
        assertThat(groups.get(0)).extracting(ChatMessageDto::id)
            .containsExactly("u0");
        assertThat(groups.get(1)).extracting(ChatMessageDto::id)
            .containsExactly("a1", "a2");
        assertThat(groups.get(2)).extracting(ChatMessageDto::id)
            .containsExactly("a3");
    }

    @Test
    @DisplayName("△-7: 全量压缩 summaryMessages 携带摘要标记（isCompactSummary/isVisibleInTranscriptOnly/subtype，compact.ts:613-624）")
    void compactConversation_summaryMessagesCarryMarkers() {
        CompactionResult result = CompactConversation.compactConversation(
            List.of(msg("u1", Role.user, "first"), msg("a1", Role.assistant, "resp")),
            ctx((m, prompt, preTokens) ->
                new CompactConversation.SummaryResult("valid summary",
                    new CompactConversation.TokenUsage(10, 5, 0, 0)),
                new ArrayList<>()), true, null, false, null);

        assertThat(result.summaryMessages()).hasSize(1);
        ChatMessageDto summary = result.summaryMessages().get(0);
        assertThat(summary.isCompactSummary())
            .as("CC createUserMessage({isCompactSummary: true})")
            .isTrue();
        assertThat(summary.isVisibleInTranscriptOnly())
            .as("CC createUserMessage({isVisibleInTranscriptOnly: true})")
            .isTrue();
        assertThat(summary.subtype())
            .as("OD-18 单表示：subtype=compact_summary 判别等价")
            .isEqualTo(CompactConversation.SUMMARY_SUBTYPE);
    }

    /** 构造 assistant 消息（id/assistantMessageId 可控，供 △-17 分组测试）。 */
    private static ChatMessageDto assistant(String id, String assistantMessageId, String content) {
        return new ChatMessageDto(
            id, SESSION, Role.assistant, "assistant", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null,
            assistantMessageId, null, List.of(), List.of(), null, false);
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-CM-17] tengu_compact 结构化遥测（compact.ts:650-695 logEvent）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("压缩成功发射 tengu_compact 全字段（CC compact.ts:650-695）")
    void successPathEmitsTenguCompactTelemetry() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        CompactConversationContext c = ctx((m, prompt, preTokens) ->
                new CompactConversation.SummaryResult("valid summary",
                    new CompactConversation.TokenUsage(10, 5, 2, 3)),
                new ArrayList<>());
        c.setTelemetry(telemetry);
        c.setQueryChainId("chain-1");
        c.setQueryDepth(7);
        c.setPromptCacheSharingEnabled(true);
        CompactConversation.RecompactionInfo recompactionInfo =
            new CompactConversation.RecompactionInfo(true, 3, "turn-9", 1, "recompaction-src");
        List<ChatMessageDto> messages = List.of(
            msg("u1", Role.user, "first user message"),
            msg("a1", Role.assistant, "assistant reply"),
            msg("u2", Role.user, "second user message"));

        CompactConversation.compactConversation(messages, c, true, null, false, recompactionInfo);

        Map<String, Object> attrs = telemetry.attrsOf("tengu_compact");
        assertThat(attrs)
            .as("压缩成功必须发射 tengu_compact 结构化事件（CC compact.ts:650）")
            .isNotNull();
        assertThat(telemetry.otelEvents)
            .as("双发射: recordEvent + logOTelEvent OTel 转发")
            .contains("tengu_compact");

        // 基础计数三口径（CC :651-653）
        assertThat(attrs).containsKeys(
            "preCompactTokenCount", "postCompactTokenCount", "truePostCompactTokenCount");
        // recompaction 透传属性（CC :654-664）
        assertThat(attrs.get("autoCompactThreshold")).isEqualTo(1);
        assertThat(attrs.get("querySource"))
            .as("recompactionInfo.querySource 优先于 ctx.querySource（CC :647-648）")
            .isEqualTo("recompaction-src");
        assertThat(attrs.get("isRecompactionInChain")).isEqualTo(true);
        assertThat(attrs.get("turnsSincePreviousCompact")).isEqualTo(3);
        assertThat(attrs.get("previousCompactTurnId")).isEqualTo("turn-9");
        // queryTracking 透传（CC :663-664）
        assertThat(attrs.get("queryChainId")).isEqualTo("chain-1");
        assertThat(attrs.get("queryDepth")).isEqualTo(7);
        // usage 透传（CC :668-678，IMP-CM-14 产物）
        assertThat(attrs.get("compactionInputTokens")).isEqualTo(10);
        assertThat(attrs.get("compactionOutputTokens")).isEqualTo(5);
        assertThat(attrs.get("compactionCacheReadTokens")).isEqualTo(2);
        assertThat(attrs.get("compactionCacheCreationTokens")).isEqualTo(3);
        assertThat(attrs.get("compactionTotalTokens")).isEqualTo(20);
        // promptCacheSharingEnabled（CC :679）
        assertThat(attrs.get("promptCacheSharingEnabled")).isEqualTo(true);
        // willRetriggerNextTurn（CC :656-658，阈值 1 < 估算 truePost → true）
        assertThat(attrs.get("willRetriggerNextTurn"))
            .as("recompactionInfo 存在且 truePost>=threshold（阈值=1）→ true")
            .isEqualTo(true);
        // 尾部 analyzeContext breakdown（CC :683-695，tokenStatsToStatsigMetrics）
        assertThat(attrs)
            .as("tokenStatsToStatsigMetrics(analyzeContext(messages)) 指标已并入事件")
            .containsKey("total_tokens");
    }

    @Test
    @DisplayName("telemetry 未注入 → tengu_compact 静默跳过（测试/未接线零行为变化）")
    void noTelemetry_skipsTenguCompactEmission() {
        // ctx 未 setTelemetry（默认 null）→ emitTenguCompactTelemetry 直接 return
        CompactConversationContext c = ctx((m, prompt, preTokens) ->
                new CompactConversation.SummaryResult("valid summary",
                    new CompactConversation.TokenUsage(10, 5, 0, 0)),
                new ArrayList<>());
        List<ChatMessageDto> messages = List.of(
            msg("u1", Role.user, "first user message"),
            msg("a1", Role.assistant, "assistant reply"));

        // 不抛异常即通过（null telemetry 静默跳过，CompactionResult 正常产出）
        CompactionResult result =
            CompactConversation.compactConversation(messages, c, true, null, false, null);
        assertThat(result).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-CM-17] 失败/重试事件结构化遥测（compact.ts:470/:479/:498/:508）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无摘要失败 → tengu_compact_failed 结构化遥测（reason=no_summary，CC compact.ts:498-503）")
    void noSummaryFailureEmitsTenguCompactFailedTelemetry() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        CompactConversationContext c = ctx((messages, prompt, preTokens) ->
            new CompactConversation.SummaryResult(null, null), new ArrayList<>());
        c.setTelemetry(telemetry);
        c.setPromptCacheSharingEnabled(true);
        assertThatThrownBy(() -> CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), c, false, null, false, null))
            .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> attrs = telemetry.attrsOf("tengu_compact_failed");
        assertThat(attrs)
            .as("无摘要必须发射 tengu_compact_failed 结构化事件（CC compact.ts:498）")
            .isNotNull();
        assertThat(attrs.get("reason"))
            .as("reason 与 CC 一致")
            .isEqualTo("no_summary");
        assertThat(attrs).containsKey("preCompactTokenCount");
        assertThat(attrs.get("promptCacheSharingEnabled")).isEqualTo(true);
        assertThat(telemetry.otelEvents)
            .as("双发射: recordEvent + logOTelEvent OTel 转发")
            .contains("tengu_compact_failed");
    }

    @Test
    @DisplayName("API 错误前缀失败 → tengu_compact_failed 结构化遥测（reason=api_error，CC compact.ts:508-514）")
    void apiErrorFailureEmitsTenguCompactFailedTelemetry() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        CompactConversationContext c = ctx((messages, prompt, preTokens) ->
            new CompactConversation.SummaryResult("API Error: Request was aborted.", null), new ArrayList<>());
        c.setTelemetry(telemetry);
        c.setPromptCacheSharingEnabled(false);
        assertThatThrownBy(() -> CompactConversation.compactConversation(
                List.of(msg("u", Role.user, "hi")), c, false, null, false, null))
            .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> attrs = telemetry.attrsOf("tengu_compact_failed");
        assertThat(attrs)
            .as("API 错误必须发射 tengu_compact_failed 结构化事件（CC compact.ts:508）")
            .isNotNull();
        assertThat(attrs.get("reason")).isEqualTo("api_error");
        assertThat(attrs).containsKey("preCompactTokenCount");
        assertThat(attrs.get("promptCacheSharingEnabled")).isEqualTo(false);
        assertThat(telemetry.otelEvents).contains("tengu_compact_failed");
    }

    @Test
    @DisplayName("PTL 重试成功 → tengu_compact_ptl_retry 结构化遥测（CC compact.ts:479-483）")
    void ptlRetryEmitsStructuredTelemetry() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AtomicInteger calls = new AtomicInteger();
        CompactConversation.SummaryProducer producer = (messages, prompt, preTokens) -> {
            if (calls.getAndIncrement() == 0) {
                return new CompactConversation.SummaryResult(
                    "Prompt is too long. Try reducing the length of the messages.", null);
            }
            return new CompactConversation.SummaryResult("summary ok",
                new CompactConversation.TokenUsage(10, 5, 0, 0));
        };
        CompactConversationContext c = ctx(producer, new ArrayList<>());
        c.setTelemetry(telemetry);
        List<ChatMessageDto> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        CompactConversation.compactConversation(messages, c, true, null, false, null);
        Map<String, Object> attrs = telemetry.attrsOf("tengu_compact_ptl_retry");
        assertThat(attrs)
            .as("PTL 重试必须发射 tengu_compact_ptl_retry 结构化事件（CC compact.ts:479）")
            .isNotNull();
        assertThat(attrs.get("attempt")).isEqualTo(1);
        assertThat(attrs.get("droppedMessages")).isInstanceOf(Integer.class);
        assertThat(attrs.get("remainingMessages")).isInstanceOf(Integer.class);
        assertThat(telemetry.otelEvents).contains("tengu_compact_ptl_retry");
        assertThat(telemetry.attrsOf("tengu_compact_failed"))
            .as("重试成功路径不得发射失败事件")
            .isNull();
    }

    @Test
    @DisplayName("PTL 重试耗尽 → tengu_compact_failed 结构化遥测（reason=prompt_too_long+ptlAttempts，CC compact.ts:470-476）")
    void ptlExhaustedEmitsTenguCompactFailedTelemetry() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        CompactConversationContext c = ctx((messages, prompt, preTokens) ->
            new CompactConversation.SummaryResult(
                "Prompt is too long. Try reducing the length of the messages.", null), new ArrayList<>());
        c.setTelemetry(telemetry);
        List<ChatMessageDto> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        assertThatThrownBy(() -> CompactConversation.compactConversation(
                messages, c, true, null, false, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(CompactConstants.ERROR_MESSAGE_PROMPT_TOO_LONG);
        Map<String, Object> attrs = telemetry.attrsOf("tengu_compact_failed");
        assertThat(attrs)
            .as("PTL 耗尽必须发射 tengu_compact_failed 结构化事件（CC compact.ts:470）")
            .isNotNull();
        assertThat(attrs.get("reason")).isEqualTo("prompt_too_long");
        assertThat(attrs.get("ptlAttempts"))
            .as("失败事件携带耗尽次数（MAX_PTL_RETRIES+1）")
            .isEqualTo(CompactConstants.MAX_PTL_RETRIES + 1);
        assertThat(attrs.get("promptCacheSharingEnabled")).isNotNull();
        // 耗尽前 3 次重试均发射 ptl_retry 事件
        assertThat(telemetry.otelEvents)
            .as("耗尽路径重试事件与失败事件并存")
            .contains("tengu_compact_ptl_retry", "tengu_compact_failed");
    }

    /** 记录事件名 + 属性 + OTel 事件的 Telemetry 假实现（对齐 SessionMemoryTelemetryTest.RecordingTelemetry）。 */
    private static final class RecordingTelemetry extends Telemetry {
        final List<String> otelEvents = new ArrayList<>();
        final Map<String, Map<String, Object>> attrsByEvent = new LinkedHashMap<>();

        @Override
        public void recordEvent(String eventName, Map<String, Object> attributes) {
            attrsByEvent.put(eventName, new LinkedHashMap<>(attributes));
        }

        @Override
        public void logOTelEvent(String eventName, Map<String, ?> metadata) {
            otelEvents.add(eventName);
        }

        Map<String, Object> attrsOf(String eventName) {
            return attrsByEvent.get(eventName);
        }
    }
}
