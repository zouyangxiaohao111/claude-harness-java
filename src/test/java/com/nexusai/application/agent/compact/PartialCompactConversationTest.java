package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.compact.CompactBoundaryMessage.CompactMetadata;
import com.nexusai.application.agent.compact.CompactBoundaryMessage.CompactMetadata.PreservedSegment;
import com.nexusai.application.agent.compact.CompactProgressEvent.HooksStart;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.UserContextProvider;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMP-11 · partial 压缩 direction up_to/from 全流程单测 · 对齐 CC compact.ts:772-1106。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: IMP-11 的目标是按 CC 单函数语义
 * 完整实现 partial 压缩（REQ-18 / OD-14 裁决不因无调用方砍语义）。本测试逐条验证
 * IMP-11 §5 验收标准：
 * <ol>
 *   <li>切片过滤单测（up_to keep 过滤 progress/boundary/compactSummary；from 保留旧 boundary）</li>
 *   <li>strip 旧 boundary 单测（up_to 保留段剥离旧 boundary）</li>
 *   <li>指令合并单测（`User context: ${userFeedback}`）</li>
 *   <li>缓存直发测试（up_to 前缀命中直发 messagesToSummarize）</li>
 *   <li>PTL retry 测试（tengu_partial_compact_failed）</li>
 *   <li>preservedSegment 注解单测（up_to anchor=summary / from anchor=boundary）</li>
 *   <li>空 summarize 异常断言（'Nothing to summarize before/after the selected message.'）</li>
 *   <li>boundary 字段断言（'manual' + preTokens + userFeedback + messagesSummarized）</li>
 * </ol>
 */
class PartialCompactConversationTest {

    private static final String SESSION = "s1";

    // ── 测试消息工厂 ───────────────────────────────────────────────────

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** progress 消息（CC type='progress' → Java subtype=PROGRESS_SUBTYPE）。 */
    private static ChatMessageDto progressMsg(String id) {
        return new ChatMessageDto(id, SESSION, Role.user, "user", "progress update",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false,
            PartialCompactConversation.PROGRESS_SUBTYPE);
    }

    /** compactSummary 消息（CC type='user' && isCompactSummary → Java subtype=SUMMARY_SUBTYPE）。 */
    private static ChatMessageDto compactSummaryMsg(String id) {
        return new ChatMessageDto(id, SESSION, Role.user, "system", "summary",
            null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false,
            CompactConversation.SUMMARY_SUBTYPE);
    }

    /** compact_boundary 消息（CC subtype='compact_boundary'）。 */
    private static ChatMessageDto boundaryMsg(String logicalParentUuid) {
        return CompactBoundaryMessage.createCompactBoundaryMessage(
            "auto", 100, logicalParentUuid, null, null).toChatMessageDto();
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

    private static CompactConversation.SummaryResult okSummary() {
        return new CompactConversation.SummaryResult("summary ok", new CompactConversation.TokenUsage(10, 5, 0, 0));
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1+2 · 切片过滤 + strip 旧 boundary（compact.ts:781-800）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("up_to: keep 过滤 progress/boundary/compactSummary（compact.ts:790-799）")
    void upToKeepFiltersProgressBoundarySummary() {
        List<ChatMessageDto> all = new ArrayList<>();
        all.add(msg("u0", Role.user, "early 0"));
        all.add(msg("a0", Role.assistant, "assistant 0"));
        // pivot=2 · 尾段含 progress / boundary / compactSummary 均应被过滤
        all.add(progressMsg("p1"));
        all.add(boundaryMsg("parent-1"));
        all.add(compactSummaryMsg("s1"));
        all.add(msg("u3", Role.user, "kept recent"));

        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), new ArrayList<>());
        CompactionResult result = PartialCompactConversation.partialCompactConversation(
            all, 2, c, null, CompactPrompt.Direction.UP_TO);

        // keep 只含尾部普通消息（u3）；progress/boundary/compactSummary 全被过滤（strip 旧 boundary）
        assertThat(result.messagesToKeep()).extracting(ChatMessageDto::id).containsExactly("u3");
        // 被摘要段 = [0,pivot) 前两条
        // boundary.messagesSummarized 应 = messagesToSummarize.length = 2
        assertThat(result.boundaryMarker().compactMetadata().messagesSummarized()).isEqualTo(2);
    }

    @Test
    @DisplayName("from: keep 保留旧 boundary，仅过滤 progress（compact.ts:800）")
    void fromKeepRetainsOldBoundary() {
        List<ChatMessageDto> all = new ArrayList<>();
        all.add(msg("u0", Role.user, "kept 0"));
        all.add(boundaryMsg("parent-old"));          // 旧 boundary —— from 应保留
        all.add(progressMsg("p1"));                    // progress —— 应过滤
        all.add(msg("u2", Role.user, "kept 1"));
        all.add(msg("u3", Role.user, "summarize tail"));

        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), new ArrayList<>());
        CompactionResult result = PartialCompactConversation.partialCompactConversation(
            all, 4, c, null, CompactPrompt.Direction.FROM);

        // from: messagesToKeep = [0,pivot) 过滤 progress → 保留 boundary + u0 + u2
        List<String> keptIds = result.messagesToKeep().stream().map(ChatMessageDto::id).toList();
        assertThat(keptIds).containsExactly("u0", "compact-boundary-compact_boundary", "u2");
        // 被摘要段 = [pivot,size) → 1 条（u3）
        assertThat(result.boundaryMarker().compactMetadata().messagesSummarized()).isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 7 · 空 summarize 异常（compact.ts:802-808）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("up_to pivot=0: 无前段可摘要 → 'Nothing to summarize before the selected message.'")
    void upToEmptySummarizeThrows() {
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), new ArrayList<>());
        List<ChatMessageDto> all = List.of(msg("u0", Role.user, "hi"));
        assertThatThrownBy(() -> PartialCompactConversation.partialCompactConversation(
                all, 0, c, null, CompactPrompt.Direction.UP_TO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Nothing to summarize before the selected message.");
    }

    @Test
    @DisplayName("from pivot=size: 无后段可摘要 → 'Nothing to summarize after the selected message.'")
    void fromEmptySummarizeThrows() {
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), new ArrayList<>());
        List<ChatMessageDto> all = List.of(msg("u0", Role.user, "hi"));
        assertThatThrownBy(() -> PartialCompactConversation.partialCompactConversation(
                all, 1, c, null, CompactPrompt.Direction.FROM))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Nothing to summarize after the selected message.");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 · 指令合并（compact.ts:827-834）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("指令合并: hook 在前 + `User context: ${userFeedback}`")
    void mergeHookWithUserContext() {
        // 双有 → hook + "\n\nUser context: " + feedback
        assertThat(PartialCompactConversation.mergeHookWithUserContext("hook ctx", "fb"))
            .isEqualTo("hook ctx\n\nUser context: fb");
        // 仅 hook → hook
        assertThat(PartialCompactConversation.mergeHookWithUserContext("hook ctx", null))
            .isEqualTo("hook ctx");
        // 仅 feedback → `User context: ${feedback}`
        assertThat(PartialCompactConversation.mergeHookWithUserContext(null, "fb"))
            .isEqualTo("User context: fb");
        // 双空 → null
        assertThat(PartialCompactConversation.mergeHookWithUserContext(null, null)).isNull();
    }

    @Test
    @DisplayName("userFeedback 指令合并: 完整 partial 流程把 feedback 注入压缩提示词")
    void userFeedbackMergedIntoPrompt() {
        List<String[]> prompts = new ArrayList<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> {
            prompts.add(new String[]{String.join("|", messages.stream().map(ChatMessageDto::id).toList()), prompt});
            return okSummary();
        }, new ArrayList<>());
        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "early"),
            msg("a0", Role.assistant, "asst"),
            msg("u1", Role.user, "recent"));
        PartialCompactConversation.partialCompactConversation(all, 2, c, "keep this context", CompactPrompt.Direction.UP_TO);

        assertThat(prompts).isNotEmpty();
        assertThat(prompts.get(0)[1]).contains("User context: keep this context");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4 · 缓存直发（compact.ts:852-858）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("up_to: API 直发 messagesToSummarize（前缀命中缓存）")
    void upToCacheDirectSendPrefix() {
        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "early 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "kept recent"));
        List<List<ChatMessageDto>> seen = new ArrayList<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> {
            seen.add(List.copyOf(messages));
            return okSummary();
        }, new ArrayList<>());
        PartialCompactConversation.partialCompactConversation(all, 2, c, null, CompactPrompt.Direction.UP_TO);

        // up_to 直发 messagesToSummarize = [0,pivot) = u0,a0（不是全量）
        assertThat(seen.get(0)).extracting(ChatMessageDto::id).containsExactly("u0", "a0");
    }

    @Test
    @DisplayName("from: API 发送全量 allMessages（尾段不缓存）")
    void fromCacheDirectSendAll() {
        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "kept 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "tail to summarize"));
        List<List<ChatMessageDto>> seen = new ArrayList<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> {
            seen.add(List.copyOf(messages));
            return okSummary();
        }, new ArrayList<>());
        PartialCompactConversation.partialCompactConversation(all, 2, c, null, CompactPrompt.Direction.FROM);

        assertThat(seen.get(0)).extracting(ChatMessageDto::id).containsExactly("u0", "a0", "u1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 5 · PTL retry（compact.ts:862-899，tengu_partial_compact_failed）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PTL 前缀摘要触发重试：第二次成功返回（compact.ts:872-899）")
    void ptlRetryThenSucceeds() {
        List<String> calls = new ArrayList<>();
        CompactConversation.SummaryProducer producer = (messages, prompt, preTokens) -> {
            calls.add("call-" + messages.size());
            if (calls.size() == 1) {
                return new CompactConversation.SummaryResult(
                    "Prompt is too long. Try reducing the length of the messages.", null);
            }
            return okSummary();
        };
        CompactConversationContext c = ctx(producer, new ArrayList<>());
        // 交替 user/assistant 以便 groupMessagesByApiRound 产出 ≥2 组（PTL 截断才有可丢组）
        List<ChatMessageDto> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        CompactionResult result = PartialCompactConversation.partialCompactConversation(
            all, 5, c, null, CompactPrompt.Direction.UP_TO);
        assertThat(calls).hasSize(2);
        assertThat(result.summaryMessages()).isNotEmpty();
    }

    @Test
    @DisplayName("PTL 重试耗尽（MAX_PTL_RETRIES=3）→ tengu_partial_compact_failed 抛 PROMPT_TOO_LONG")
    void ptlRetryExhaustedThrows() {
        CompactConversationContext c = ctx((messages, prompt, preTokens) ->
            new CompactConversation.SummaryResult("Prompt is too long. Try reducing the length of the messages.", null),
            new ArrayList<>());
        List<ChatMessageDto> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        assertThatThrownBy(() -> PartialCompactConversation.partialCompactConversation(
                all, 5, c, null, CompactPrompt.Direction.UP_TO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(CompactConstants.ERROR_MESSAGE_PROMPT_TOO_LONG);
    }

    // ════════════════════════════════════════════════════════════════════
    // [RES-C4] PTL retry 每截断更新 fork 前缀（对齐 CC compact.ts:895-898）
    // ════════════════════════════════════════════════════════════════════

    /**
     * [RES-C4] OPD-SP-33 PTL retry 前缀偏移（§十二 用户拍板）：PTL 重试截断后 forkContextMessages
     * 未更新 → retry 摘要请求的 fork 缓存前缀与实际发送消息偏移（缓存永不命中）。本测试钉死
     * 对齐 CC {@code retryCacheSafeParams = { ...retryCacheSafeParams, forkContextMessages: truncated }}
     * （compact.ts:895-898）后的契约：<b>每次 retry 迭代，summaryProducer（即 StreamCompactSummary
     * fork 读侧，cacheSafeParamsSupplier=Holder.get()）读到的 forkContextMessages 必须等于该次实际
     * 发送的 apiMessages</b>；首轮 = up_to 前缀，retry 轮 = 截断后消息。
     * WHY（规则 9）: 「PTL retry 前缀偏移」修复意图 = fork 前缀与发送消息一致（缓存不偏移），
     * 本测试把「retry 后 fork 前缀跟随截断」这个不变量钉死为不可回归。
     */
    @Test
    @DisplayName("[RES-C4] PTL retry: 每截断更新 forkContextMessages=truncated（对齐 CC compact.ts:895-898）")
    void ptlRetryUpdatesForkContextMessagesToTruncated() {
        ToolUseContext tuc = baseContext();
        Supplier<SystemPrompt> defaultAssemble = () -> {
            throw new IllegalStateException("custom 短路: defaultAssemble 不应被调用");
        };
        SystemPromptContextProvider sysCtx = new SystemPromptContextProvider(
            "2026-08-06",
            new UserContextProvider() {
                @Override
                public String claudeMd() {
                    return "项目指令";
                }

                @Override
                public String currentDate(String sessionStartDate) {
                    return "Today's date is " + sessionStartDate + ".";
                }
            },
            new GitStatusProvider() {
                @Override
                public String getGitStatus() {
                    return "GIT-BLOCK";
                }
            });
        List<List<String>> forkPrefixesPerCall = new ArrayList<>();
        List<List<String>> sentMessagesPerCall = new ArrayList<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> {
            // summaryProducer 即 StreamCompactSummary fork 读侧（cacheSafeParamsSupplier=Holder.get()）
            CacheSafeParams cs = CacheSafeParamsHolder.get();
            forkPrefixesPerCall.add(cs == null
                ? List.of()
                : cs.forkContextMessages().stream().map(ChatMessageDto::id).toList());
            sentMessagesPerCall.add(messages.stream().map(ChatMessageDto::id).toList());
            if (sentMessagesPerCall.size() == 1) {
                return new CompactConversation.SummaryResult(
                    "Prompt is too long. Try reducing the length of the messages.", null);
            }
            return okSummary();
        }, new ArrayList<>());
        c.setToolUseContext(tuc);
        c.setSysPromptCtxProvider(sysCtx);
        c.setDefaultSysPromptAssemble(defaultAssemble);
        c.setCustomSystemPrompt("CUSTOM-PROMPT");
        c.setAppendSystemPrompt(null);
        c.setUseGlobalCacheScope(false);
        // 交替 user/assistant 以便 groupMessagesByApiRound 产出 ≥2 组（PTL 截断才有可丢组）
        List<ChatMessageDto> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        PartialCompactConversation.partialCompactConversation(all, 5, c, null, CompactPrompt.Direction.UP_TO);

        // 恰好一次 retry：两次 summarize 调用
        assertThat(sentMessagesPerCall).hasSize(2);
        // 首轮：fork 前缀 = up_to 前缀 messagesToSummarize = [0,pivot)
        assertThat(forkPrefixesPerCall.get(0)).isEqualTo(sentMessagesPerCall.get(0));
        assertThat(forkPrefixesPerCall.get(0)).containsExactly("m0", "m1", "m2", "m3", "m4");
        // 截断确实发生（retry 轮发送消息 ≠ 首轮）
        assertThat(sentMessagesPerCall.get(1)).isNotEqualTo(sentMessagesPerCall.get(0));
        // retry 轮：fork 前缀必须跟随截断 = 该次实际发送消息（对齐 CC compact.ts:895-898）
        assertThat(forkPrefixesPerCall.get(1)).isEqualTo(sentMessagesPerCall.get(1));
        // 压缩后槽位清空（finally clear）
        assertThat(CacheSafeParamsHolder.get()).isNull();
    }

    @Test
    @DisplayName("A13 partial: loadedNestedMemoryPaths 压缩后清空（CC compact.ts:921）——partial 压缩后同 Set 实例复位")
    void partialLoadedNestedMemoryPathsClearedAfterCompact() {
        // WHY: CC partialCompactConversation 在 readFileState.clear() 后紧跟
        // context.loadedNestedMemoryPaths?.clear()（compact.ts:921）。该 Set 是 memory 文件重注入
        // 去重双源之一（loadedNestedMemoryPaths + readFileState.has，跨域 CM-F1），partial 压缩后
        // 不复位 → 下轮 memory 重注入命中陈旧 Set 跳过（OPD-CM5-A-08 拍板 + A2 GLOBAL_REFLECTOR
        // REWORK）。PartialCompactService:373 注入的是主循环会话级 Set 同一实例（LlmAgentLoop:814）
        // → 本测试用同一 TUC 实例验证 partial 压缩后复位。
        ToolUseContext tuc = baseContext();
        tuc.loadedNestedMemoryPaths().add("/mem/nested/a.md");
        tuc.loadedNestedMemoryPaths().add("/mem/nested/b.md");
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), new ArrayList<>());
        c.setToolUseContext(tuc);

        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "early 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "kept recent"));
        PartialCompactConversation.partialCompactConversation(all, 2, c, null, CompactPrompt.Direction.UP_TO);

        // partial 压缩完成后清空已加载嵌套记忆路径（对齐 CC compact.ts:921 A13）
        assertThat(tuc.loadedNestedMemoryPaths()).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 6 · preservedSegment 注解（compact.ts:1077-1087）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("preservedSegment: up_to anchor=最后 summary 消息")
    void preservedSegmentUpToAnchorIsSummary() {
        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "early 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "kept recent"));
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), new ArrayList<>());
        CompactionResult result = PartialCompactConversation.partialCompactConversation(
            all, 2, c, null, CompactPrompt.Direction.UP_TO);

        CompactMetadata meta = result.boundaryMarker().compactMetadata();
        PreservedSegment seg = meta.preservedSegment();
        assertThat(seg).isNotNull();
        // up_to: headUuid = keep[0].uuid = u1；anchorUuid = 最后 summary 消息 uuid；tailUuid = keep[-1] = u1
        assertThat(seg.headUuid()).isEqualTo("u1");
        assertThat(seg.anchorUuid()).isEqualTo(result.summaryMessages().get(result.summaryMessages().size() - 1).id());
        assertThat(seg.tailUuid()).isEqualTo("u1");
    }

    @Test
    @DisplayName("preservedSegment: from anchor=boundary")
    void preservedSegmentFromAnchorIsBoundary() {
        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "kept 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "tail to summarize"));
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), new ArrayList<>());
        CompactionResult result = PartialCompactConversation.partialCompactConversation(
            all, 2, c, null, CompactPrompt.Direction.FROM);

        CompactMetadata meta = result.boundaryMarker().compactMetadata();
        PreservedSegment seg = meta.preservedSegment();
        assertThat(seg).isNotNull();
        // from: anchorUuid = boundary 自身 uuid
        assertThat(seg.anchorUuid()).isEqualTo(result.boundaryMarker().uuid());
        // headUuid = keep[0] = u0；tailUuid = keep[-1] = a0
        assertThat(seg.headUuid()).isEqualTo("u0");
        assertThat(seg.tailUuid()).isEqualTo("a0");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 8 · boundary 字段断言（compact.ts:1014-1020）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("boundary: 'manual' + preTokens + userFeedback(userContext) + messagesSummarized")
    void boundaryFields() {
        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "early 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "kept recent"));
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), new ArrayList<>());
        CompactionResult result = PartialCompactConversation.partialCompactConversation(
            all, 2, c, "my feedback", CompactPrompt.Direction.UP_TO);

        CompactMetadata meta = result.boundaryMarker().compactMetadata();
        assertThat(result.boundaryMarker().subtype()).isEqualTo("compact_boundary");
        assertThat(meta.trigger()).isEqualTo("manual");
        assertThat(meta.userContext()).isEqualTo("my feedback");
        assertThat(meta.messagesSummarized()).isEqualTo(2);   // messagesToSummarize = [0,pivot) = u0,a0
        assertThat(meta.preTokens()).isGreaterThan(0);        // tokenCountWithEstimation(allMessages)
    }

    // ════════════════════════════════════════════════════════════════════
    // 事件顺序（INV-1 单流程 5 事件）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("成功路径恰 5 事件且顺序符合 CC（pre_compact→compact_start→session_start→post_compact→compact_end）")
    void successPathEmitsFiveEventsInCcOrder() {
        List<CompactProgressEvent> events = new ArrayList<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), events);
        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "early 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "kept recent"));
        PartialCompactConversation.partialCompactConversation(all, 2, c, null, CompactPrompt.Direction.UP_TO);

        assertThat(events).hasSize(5);
        assertThat(events.get(0)).isEqualTo(new HooksStart(HooksStart.HookType.PRE_COMPACT));
        assertThat(events.get(1)).isEqualTo(new CompactProgressEvent.CompactStart());
        assertThat(events.get(2)).isEqualTo(new HooksStart(HooksStart.HookType.SESSION_START));
        assertThat(events.get(3)).isEqualTo(new HooksStart(HooksStart.HookType.POST_COMPACT));
        assertThat(events.get(4)).isEqualTo(new CompactProgressEvent.CompactEnd());
    }

    // ════════════════════════════════════════════════════════════════════
    // summarizeMetadata（compact.ts:1037-1042）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("summarizeMetadata: keep 非空时 summary 消息携带 {messagesSummarized,userContext,direction}")
    void summarizeMetadataAttachedWhenKeepNonEmpty() {
        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "early 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "kept recent"));
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> okSummary(), new ArrayList<>());
        CompactionResult result = PartialCompactConversation.partialCompactConversation(
            all, 2, c, "my feedback", CompactPrompt.Direction.UP_TO);

        ChatMessageDto summary = result.summaryMessages().get(0);
        Map<String, Object> structured = summary.structuredOutput();
        assertThat(structured).isNotNull();
        Map<?, ?> meta = (Map<?, ?>) structured.get("summarizeMetadata");
        assertThat(meta).isNotNull();
        assertThat(meta.get("messagesSummarized")).isEqualTo(2);
        assertThat(meta.get("userContext")).isEqualTo("my feedback");
        assertThat(meta.get("direction")).isEqualTo("up_to");
        // isCompactSummary 判别：subtype=compact_summary
        assertThat(summary.subtype()).isEqualTo(CompactConversation.SUMMARY_SUBTYPE);
    }

    // ════════════════════════════════════════════════════════════════════
    // [RES-OPD-SP33] partial 触发摘要前接入 fork 缓存共享通道（同 R1 manual 方案）
    //   CC getCacheSharingParams compact.ts:250-287 + partial 缓存选择 :852-858
    // ════════════════════════════════════════════════════════════════════

    /**
     * [RES-OPD-SP33] partial 压缩启用时无缓存共享通道 → 命中 CacheSafeParamsHolder 槽位空 →
     * fork 缓存共享跳过 → 落流式 fallback（偏离 CC partial 缓存直发）。本测试钉死接线后的契约：
     * 摘要生产（summaryProducer 即 StreamCompactSummary fork 读侧，cacheSafeParamsSupplier=Holder.get()）
     * 读到非 null CacheSafeParams，且 6 字段完整（systemPrompt/userContext/systemContext/toolUseContext/
     * forkContextMessages/useGlobalCacheScope）；压缩结束后 finally clear 槽位空（防串台/泄漏）。
     * WHY（规则 9）: OPD-SP-33「现在就修（对齐 CC，同 R1 方案）」——用户拍板 partial 与 manual /compact
     * 共用 save→summarize→clear 契约，本测试把「槽位空 = 无缓存共享」这个偏离钉死为不可回归。
     */
    @Test
    @DisplayName("[RES-OPD-SP33] up_to: summarize 前 save CacheSafeParams（forkContextMessages=前缀）→ 摘要期间 Holder 非空 → finally clear")
    void upToSavesAndClearsCacheSafeParams() {
        ToolUseContext tuc = baseContext();
        // I-13 custom 短路：customSystemPrompt 非空 → defaultAssemble 不被调用（无须构造真实组装链）
        Supplier<SystemPrompt> defaultAssemble = () -> {
            throw new IllegalStateException("custom 短路: defaultAssemble 不应被调用");
        };
        SystemPromptContextProvider sysCtx = new SystemPromptContextProvider(
            "2026-08-06",
            new UserContextProvider() {
                @Override
                public String claudeMd() {
                    return "项目指令";
                }

                @Override
                public String currentDate(String sessionStartDate) {
                    return "Today's date is " + sessionStartDate + ".";
                }
            },
            new GitStatusProvider() {
                @Override
                public String getGitStatus() {
                    return "GIT-BLOCK";
                }
            });
        AtomicReference<CacheSafeParams> seenDuringSummarize = new AtomicReference<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> {
            // summaryProducer 即 StreamCompactSummary fork 读侧（cacheSafeParamsSupplier=Holder.get()）
            seenDuringSummarize.set(CacheSafeParamsHolder.get());
            return okSummary();
        }, new ArrayList<>());
        c.setToolUseContext(tuc);
        c.setSysPromptCtxProvider(sysCtx);
        c.setDefaultSysPromptAssemble(defaultAssemble);
        c.setCustomSystemPrompt("CUSTOM-PROMPT");
        c.setAppendSystemPrompt(null);
        c.setUseGlobalCacheScope(false);   // 3P 默认 gate=false

        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "early 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "kept recent"));
        PartialCompactConversation.partialCompactConversation(all, 2, c, null, CompactPrompt.Direction.UP_TO);

        // 摘要生产期间 Holder 非 null → fork 路径可达（修复前恒 null → RED）
        CacheSafeParams cs = seenDuringSummarize.get();
        assertThat(cs).isNotNull();
        // 6 字段完整（forkedAgent.ts:57-68 + betas.ts:227-233）
        assertThat(cs.systemPrompt()).containsExactly("CUSTOM-PROMPT");
        assertThat(cs.userContext()).isNotEmpty();
        assertThat(cs.systemContext()).isEmpty();      // I-13 custom 短路
        assertThat(cs.toolUseContext()).isSameAs(tuc); // 会话一致 TUC 透传
        // forkContextMessages = apiMessages = up_to 前缀（compact.ts:855-858）
        assertThat(cs.forkContextMessages()).extracting(ChatMessageDto::id).containsExactly("u0", "a0");
        assertThat(cs.useGlobalCacheScope()).isFalse();
        // 压缩后槽位清空（finally clear，防串台/泄漏到下一流程）
        assertThat(CacheSafeParamsHolder.get()).isNull();
    }

    @Test
    @DisplayName("[RES-OPD-SP33] from: summarize 前 save CacheSafeParams（forkContextMessages=全量）→ finally clear")
    void fromSavesAndClearsCacheSafeParams() {
        ToolUseContext tuc = baseContext();
        SystemPromptContextProvider sysCtx = new SystemPromptContextProvider(
            "2026-08-06",
            new UserContextProvider() {
                @Override
                public String claudeMd() {
                    return "项目指令";
                }

                @Override
                public String currentDate(String sessionStartDate) {
                    return "Today's date is " + sessionStartDate + ".";
                }
            },
            new GitStatusProvider() {
                @Override
                public String getGitStatus() {
                    return "GIT-BLOCK";
                }
            });
        AtomicReference<CacheSafeParams> seenDuringSummarize = new AtomicReference<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> {
            seenDuringSummarize.set(CacheSafeParamsHolder.get());
            return okSummary();
        }, new ArrayList<>());
        c.setToolUseContext(tuc);
        c.setSysPromptCtxProvider(sysCtx);
        c.setCustomSystemPrompt("CUSTOM-PROMPT");

        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "kept 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "tail to summarize"));
        PartialCompactConversation.partialCompactConversation(all, 2, c, null, CompactPrompt.Direction.FROM);

        CacheSafeParams cs = seenDuringSummarize.get();
        assertThat(cs).isNotNull();
        // from: forkContextMessages = apiMessages = 全量 allMessages（compact.ts:852-858 tail 不缓存）
        assertThat(cs.forkContextMessages()).extracting(ChatMessageDto::id).containsExactly("u0", "a0", "u1");
        assertThat(CacheSafeParamsHolder.get()).isNull();
    }

    @Test
    @DisplayName("[RES-OPD-SP33] best-effort: toolUseContext/sysPromptCtxProvider 未注入 → save(null) → fork 跳过（不阻断压缩）")
    void missingIngredientsSkipsCacheSharingWithoutBlocking() {
        // 无 ToolUseContext/sysPromptCtxProvider（生产 REST 线程未接线时的既有行为）
        AtomicReference<CacheSafeParams> seenDuringSummarize = new AtomicReference<>();
        CompactConversationContext c = ctx((messages, prompt, preTokens) -> {
            seenDuringSummarize.set(CacheSafeParamsHolder.get());
            return okSummary();
        }, new ArrayList<>());

        List<ChatMessageDto> all = List.of(
            msg("u0", Role.user, "early 0"),
            msg("a0", Role.assistant, "asst 0"),
            msg("u1", Role.user, "kept recent"));
        CompactionResult result = PartialCompactConversation.partialCompactConversation(
            all, 2, c, null, CompactPrompt.Direction.UP_TO);

        // 摘要期间槽位空（fork 跳过，走流式 fallback）——缓存共享为优化项，不阻断压缩
        assertThat(seenDuringSummarize.get()).isNull();
        assertThat(result.summaryMessages()).isNotEmpty();
        assertThat(CacheSafeParamsHolder.get()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试工具（[RES-OPD-SP33] 新增）
    // ════════════════════════════════════════════════════════════════════

    /** 最小 ToolUseContext（8 参兼容构造器）· 对齐 StreamCompactSummaryForkUserContextTest.baseContext。 */
    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP2-16（△-9）· partial 重组方向语义（REPL.tsx:4950-4952 + CompactionResult.buildPartialPostCompactMessages）
    // ════════════════════════════════════════════════════════════════════

    /** 构造 partial CompactionResult（boundary + summary + keep + attachments + hooks 五段齐备）。 */
    private static CompactionResult partialResult(
            List<String> summaryIds, List<String> keepIds,
            List<String> attachmentIds, List<String> hookIds) {
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createCompactBoundaryMessage(
            "manual", 100, null, null, summaryIds.size());
        return new CompactionResult(
            boundary,
            summaryIds.stream().map(id -> msg(id, Role.user, "summary " + id)).toList(),
            attachmentIds.stream().map(id -> msg(id, Role.user, "att " + id)).toList(),
            hookIds.stream().map(id -> msg(id, Role.user, "hook " + id)).toList(),
            keepIds.stream().map(id -> msg(id, Role.user, "keep " + id)).toList(),
            null,
            100, 50, 0, null);
    }

    @Test
    @DisplayName("△-9 重组 from: [boundary, ...keep, ...summary, ...attachments, ...hooks]（REPL.tsx:4950-4952）")
    void reassemblyFromKeepsPrefixBeforeSummary() {
        CompactionResult result = partialResult(
            List.of("s1", "s2"), List.of("k1", "k2"),
            List.of("a1"), List.of("h1"));
        List<ChatMessageDto> postCompact =
            CompactionResult.buildPartialPostCompactMessages(result, CompactPrompt.Direction.FROM);
        assertThat(postCompact.stream().map(ChatMessageDto::id).toList())
            .containsExactly("compact-boundary-compact_boundary", "k1", "k2", "s1", "s2", "a1", "h1");
        // boundary 结构化消息（subtype=compact_boundary）在首位（REPL.tsx:4952 postCompact[0]）
        assertThat(postCompact.get(0).subtype()).isEqualTo("compact_boundary");
    }

    @Test
    @DisplayName("△-9 重组 up_to: [boundary, ...summary, ...keep, ...attachments, ...hooks]（REPL.tsx:4950-4952）")
    void reassemblyUpToSummaryBeforeKept() {
        CompactionResult result = partialResult(
            List.of("s1", "s2"), List.of("k1", "k2"),
            List.of("a1"), List.of("h1"));
        List<ChatMessageDto> postCompact =
            CompactionResult.buildPartialPostCompactMessages(result, CompactPrompt.Direction.UP_TO);
        assertThat(postCompact.stream().map(ChatMessageDto::id).toList())
            .containsExactly("compact-boundary-compact_boundary", "s1", "s2", "k1", "k2", "a1", "h1");
    }

    @Test
    @DisplayName("△-9 重组: null result → 空列表；direction=null 回落 from 顺序（CC else 分支语义）")
    void reassemblyNullHandlingAndNullDirectionFallsBackToFrom() {
        assertThat(CompactionResult.buildPartialPostCompactMessages(null, CompactPrompt.Direction.UP_TO)).isEmpty();

        CompactionResult result = partialResult(
            List.of("s1"), List.of("k1"), List.of(), List.of());
        List<ChatMessageDto> postCompact =
            CompactionResult.buildPartialPostCompactMessages(result, null);
        assertThat(postCompact.stream().map(ChatMessageDto::id).toList())
            .containsExactly("compact-boundary-compact_boundary", "k1", "s1");
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-CM-17] partial 失败/重试事件结构化遥测（compact.ts:880/:888/:901/:910）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("partial PTL 重试成功 → tengu_compact_ptl_retry 结构化遥测（path=partial，CC compact.ts:888-893）")
    void partialPtlRetryEmitsStructuredTelemetry() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        List<String> calls = new ArrayList<>();
        CompactConversation.SummaryProducer producer = (messages, prompt, preTokens) -> {
            calls.add("call-" + messages.size());
            if (calls.size() == 1) {
                return new CompactConversation.SummaryResult(
                    "Prompt is too long. Try reducing the length of the messages.", null);
            }
            return okSummary();
        };
        CompactConversationContext c = ctx(producer, new ArrayList<>());
        c.setTelemetry(telemetry);
        List<ChatMessageDto> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        PartialCompactConversation.partialCompactConversation(
            all, 5, c, null, CompactPrompt.Direction.UP_TO);
        Map<String, Object> attrs = telemetry.attrsOf("tengu_compact_ptl_retry");
        assertThat(attrs)
            .as("partial PTL 重试必须发射 tengu_compact_ptl_retry 结构化事件（CC compact.ts:888）")
            .isNotNull();
        assertThat(attrs.get("attempt")).isEqualTo(1);
        assertThat(attrs.get("droppedMessages")).isInstanceOf(Integer.class);
        assertThat(attrs.get("remainingMessages")).isInstanceOf(Integer.class);
        assertThat(attrs.get("path"))
            .as("partial 路径 path='partial'（CC compact.ts:892）")
            .isEqualTo("partial");
        assertThat(telemetry.otelEvents).contains("tengu_compact_ptl_retry");
        assertThat(telemetry.attrsOf("tengu_partial_compact_failed"))
            .as("重试成功路径不得发射失败事件")
            .isNull();
    }

    @Test
    @DisplayName("partial PTL 耗尽 → tengu_partial_compact_failed 结构化遥测（failureMetadata+ptlAttempts，CC compact.ts:880-885）")
    void partialPtlExhaustedEmitsFailedTelemetry() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        CompactConversationContext c = ctx((messages, prompt, preTokens) ->
            new CompactConversation.SummaryResult(
                "Prompt is too long. Try reducing the length of the messages.", null), new ArrayList<>());
        c.setTelemetry(telemetry);
        List<ChatMessageDto> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        assertThatThrownBy(() -> PartialCompactConversation.partialCompactConversation(
                all, 5, c, null, CompactPrompt.Direction.UP_TO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(CompactConstants.ERROR_MESSAGE_PROMPT_TOO_LONG);
        Map<String, Object> attrs = telemetry.attrsOf("tengu_partial_compact_failed");
        assertThat(attrs)
            .as("partial PTL 耗尽必须发射 tengu_partial_compact_failed 结构化事件（CC compact.ts:880）")
            .isNotNull();
        assertThat(attrs.get("reason")).isEqualTo("prompt_too_long");
        // ptlAttempts 为当前耗尽轮次（≤MAX_PTL_RETRIES+1；truncateHeadForPTLRetry 无法再截断即提前耗尽，
        //   CC compact.ts:875-878 同语义）；且必 ≥1（至少一轮 PTL 尝试后才失败）
        assertThat(attrs.get("ptlAttempts")).isInstanceOf(Integer.class);
        assertThat((Integer) attrs.get("ptlAttempts"))
            .as("ptlAttempts 在 [1, MAX_PTL_RETRIES+1] 区间")
            .isBetween(1, CompactConstants.MAX_PTL_RETRIES + 1);
        // failureMetadata（compact.ts:845-849）：{preCompactTokenCount, direction, messagesSummarized}
        assertThat(attrs).containsKeys("preCompactTokenCount", "direction", "messagesSummarized");
        assertThat(attrs.get("direction")).isEqualTo("up_to");
        // 耗尽前至少发射过 1 次重试事件（path=partial）
        assertThat(telemetry.otelEvents).contains("tengu_compact_ptl_retry", "tengu_partial_compact_failed");
    }

    @Test
    @DisplayName("partial 无摘要失败 → tengu_partial_compact_failed 结构化遥测（reason=no_summary+failureMetadata，CC compact.ts:901-905）")
    void partialNoSummaryEmitsFailedTelemetry() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        CompactConversationContext c = ctx((messages, prompt, preTokens) ->
            new CompactConversation.SummaryResult(null, null), new ArrayList<>());
        c.setTelemetry(telemetry);
        List<ChatMessageDto> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            all.add(msg("m" + i, i % 2 == 0 ? Role.user : Role.assistant, "message " + i));
        }
        assertThatThrownBy(() -> PartialCompactConversation.partialCompactConversation(
                all, 5, c, null, CompactPrompt.Direction.FROM))
            .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> attrs = telemetry.attrsOf("tengu_partial_compact_failed");
        assertThat(attrs)
            .as("partial 无摘要必须发射 tengu_partial_compact_failed 结构化事件（CC compact.ts:901）")
            .isNotNull();
        assertThat(attrs.get("reason")).isEqualTo("no_summary");
        assertThat(attrs.get("direction")).isEqualTo("from");
        assertThat(attrs).containsKeys("preCompactTokenCount", "messagesSummarized");
        assertThat(telemetry.otelEvents).contains("tengu_partial_compact_failed");
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
    // ════════════════════════════════════════════════════════════════════
    // extractDiscoveredToolNames boundary 携带（toolSearch.ts:553-560）· IMP-MV2-04
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extractDiscoveredToolNames: 先前 compact_boundary 的 preCompactDiscoveredTools 并入（toolSearch.ts:553-560）")
    void extractDiscoveredToolNamesCarriesBoundaryTools() {
        // 先前 boundary 携带已发现工具（多次压缩链上集合不收缩）
        CompactBoundaryMessage boundary = CompactBoundaryMessage.createCompactBoundaryMessage(
            "auto", 100, "parent-1", null, null)
            .withCompactMetadata(new CompactMetadata(
                "auto", 100, null, null, List.of("ToolA", "ToolB"), null));
        ChatMessageDto boundaryDto = boundary.toChatMessageDto();

        // user 消息 tool_result 块内 tool_reference.tool_name（toolSearch.ts:562-580）
        ObjectNode toolResult = JsonNodeFactory.instance.objectNode();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", "tu1");
        ArrayNode content = toolResult.putArray("content");
        ObjectNode ref = content.addObject();
        ref.put("type", "tool_reference");
        ref.put("tool_name", "ToolC");

        ChatMessageDto userWithToolResult = new ChatMessageDto(
            "u1", SESSION, Role.user, "user", null, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(toolResult), List.of(), null, false, false, null, null,
            false, null, null, null, null);

        List<ChatMessageDto> messages = List.of(boundaryDto, userWithToolResult);
        assertThat(PartialCompactConversation.extractDiscoveredToolNames(messages))
            .as("boundary 携带集与 tool_reference 扫描并集（SM/partial 双路径共享）")
            .containsExactlyInAnyOrder("ToolA", "ToolB", "ToolC");
    }

    @Test
    @DisplayName("extractDiscoveredToolNames: 无 preCompactDiscoveredTools 的 boundary 不影响 tool_reference 扫描（toolSearch.ts:553-560）")
    void extractDiscoveredToolNamesBoundaryWithoutCarriedTools() {
        ChatMessageDto boundaryDto = boundaryMsg("parent-1"); // 5 参工厂 → preCompactDiscoveredTools 空

        ObjectNode toolResult = JsonNodeFactory.instance.objectNode();
        toolResult.put("type", "tool_result");
        toolResult.put("tool_use_id", "tu1");
        ArrayNode content = toolResult.putArray("content");
        ObjectNode ref = content.addObject();
        ref.put("type", "tool_reference");
        ref.put("tool_name", "ToolC");

        ChatMessageDto userWithToolResult = new ChatMessageDto(
            "u1", SESSION, Role.user, "user", null, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(toolResult), List.of(), null, false, false, null, null,
            false, null, null, null, null);

        assertThat(PartialCompactConversation.extractDiscoveredToolNames(List.of(boundaryDto, userWithToolResult)))
            .containsExactly("ToolC");
    }

    @Test
    @DisplayName("extractDiscoveredToolNames: 空消息列表 → 空集；null 输入 → 空集（toolSearch.ts:546）")
    void extractDiscoveredToolNamesEmptyInputs() {
        assertThat(PartialCompactConversation.extractDiscoveredToolNames(null)).isEmpty();
        assertThat(PartialCompactConversation.extractDiscoveredToolNames(List.of())).isEmpty();
    }
}
