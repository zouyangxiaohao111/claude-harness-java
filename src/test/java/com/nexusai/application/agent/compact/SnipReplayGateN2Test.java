package com.nexusai.application.agent.compact;

import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.PartialCompactRequest;
import com.nexusai.model.session.dto.Role;
import com.nexusai.common.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [N2 2026-09-11 · 用户拍板 (B)「历史 snip 不复活」] 回放门 vs 执行门拆分验收。
 *
 * <p><b>决策</b>：关掉 {@code history_snip_enabled} 只应禁止「产生**新** snip」，不应让**已执行过**
 * 的 snip 失效。故 {@code BoundaryReader.getMessagesAfterCompactBoundary} 的 snip 投影改「回放门」——
 * 只要 {@code !includeSnipped} 就投影，**不看任何开关**；与 compact boundary 剥离（恒定、本就无门）
 * 对称。{@code LlmAgentLoop} 的 snip 步骤（执行门）保持读开关，管「本轮是否产生新 snip」。
 *
 * <p><b>CC 对照（实际 TS 源码，非注释）</b>：CC 的字面门是
 * {@code !options?.includeSnipped && feature('HISTORY_SNIP')}（{@code utils/messages.ts:5088}），
 * 而 {@code feature()} 是**构建期常量**——在 CC 里以模块级三元求值一次
 * （{@code tools.ts:141} {@code const SnipTool = feature('HISTORY_SNIP') ? require(...) : null}、
 * {@code query.ts:141} {@code const snipModule = ...}）→ 构建后不可能翻转 ⇒ CC 侧「回放」与「执行」
 * **天然同源、不可能分叉**。本仓把它实现成**运行时 DB 开关**，才必须拆成两个语义。本改造是
 * <b>有意偏离 CC 字面、但保持 CC 语义</b>：CC 中「flag 关」⇒ 不可能有 boundary ⇒ 投影天然 no-op，
 * 与「有 boundary 才投影」等价。
 *
 * <p><b>RED teeth（变异验证）</b>：把 {@code BoundaryReader} 投影改回
 * {@code if (!includeSnipped && isHistorySnipEnabled())} → {@link #gateOff_historicalSnip_stillProjected}
 * 与 {@link #partialCompactConsumer_gateOff_snippedMessageId_404} 必须 fail。
 */
@DisplayName("[N2] snip 回放门（历史 snip 不复活）· 5 消费点审计 + PartialCompact 消费点断言")
class SnipReplayGateN2Test {

    private static final String SESSION = "sess-n2";

    /** 5 处静态调用面（qa.md §11.2(4) 清单；行号以实施当日 grep 复核为准，故此处只锚定文件）。 */
    private static final List<String> FIVE_CALL_SITES = List.of(
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java",
        "src/main/java/com/nexusai/application/agent/command/CompactCommand.java",
        "src/main/java/com/nexusai/application/agent/compact/PartialCompactService.java",
        "src/main/java/com/nexusai/application/agent/compact/StreamCompactSummary.java",
        "src/main/java/com/nexusai/application/agent/skill/SkillifySkillRegistrar.java");

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        CompactProgressState.clear();
        CompactProgressState.clearAbort();
        CompactProgressState.removeSessionAbort(SESSION);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 回放门：门关（flag 全关 + DB 列 null）仍投影历史 snip
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("门关 + 历史有 snip_boundary → 模型面视图仍不含被 snip 的消息（回放生效）")
    void gateOff_historicalSnip_stillProjected() {
        // 门关：FeatureFlags 全关（截图路径：application.yml 默认也会被 DB=false 覆盖）
        List<ChatMessageDto> projected =
            BoundaryReader.getMessagesAfterCompactBoundary(snipHistory());

        assertThat(ids(projected))
            .as("[N2 (B)] 已执行过的 snip 是既成事实 → 门关时仍须剔除 removedUuids（u0）")
            .doesNotContain("u0")
            .contains("u1", "u2", "snip-boundary-1");
    }

    @Test
    @DisplayName("门关 + 无历史 snip → 行为与改前一致（不误伤）")
    void gateOff_noSnipHistory_identicalToBefore() {
        List<ChatMessageDto> noSnip = new ArrayList<>();
        noSnip.add(msg("u0", Role.user));
        noSnip.add(msg("u1", Role.user));

        assertThat(ids(BoundaryReader.getMessagesAfterCompactBoundary(noSnip)))
            .as("[N2] 无 snip_boundary → removedSet 为空 → projectSnippedView 原样返回（零行为变化）")
            .containsExactly("u0", "u1");
    }

    @Test
    @DisplayName("compact boundary 剥离恒定无门（对称性对照）· 与 snip 回放门同类")
    void compactBoundaryStrippingIsUnconditional() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        msgs.add(msg("u0", Role.user));
        msgs.add(compactBoundary("compact-boundary-1"));
        msgs.add(msg("u1", Role.user));

        assertThat(ids(BoundaryReader.getMessagesAfterCompactBoundary(msgs)))
            .as("compact boundary 前的 u0 恒定被切掉（无任何开关参与）——snip 回放门与之对称")
            .containsExactly("compact-boundary-1", "u1");
    }

    @Test
    @DisplayName("includeSnipped=true（UI/REPL 全量面）→ 仍拿到全量（不回归）")
    void includeSnippedTrue_returnsFullHistory() {
        assertThat(ids(BoundaryReader.getMessagesAfterCompactBoundary(snipHistory(), true)))
            .as("[N2] includeSnipped 语义不变（CC REPL.tsx:3167-3169 UI 面 opt-out）→ true 时不做投影")
            .contains("u0", "u1", "u2", "snip-boundary-1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 消费点断言 · PartialCompactService.java（pivot 判据依赖回放）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[消费点 3/5] PartialCompactService 门关仍回放：选已 snip 的 messageId → 404 提示（非静默 no-op）")
    void partialCompactConsumer_gateOff_snippedMessageId_404() {
        // WHY（规则 9）：此处的剥离结果不只是「喂模型」，还承担 pivot 定位判据——
        //   :322-330 把「indexOf(messageId) == -1」解释为「已 snipped / pre-compact」并抛 404。
        //   若投影被开关门控，门关时 u0 会重新出现 → pivot 命中 → partial compact 会对一条
        //   模型面已不可见的消息做摘要，破坏该判据。故本消费点必须是回放语义。
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(snipHistory());
        when(messageService.appendPostCompactMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));

        PartialCompactService svc =
            new PartialCompactService(messageService, sessionService, summary);

        assertThatThrownBy(() -> svc.partialCompact(SESSION,
            new PartialCompactRequest("u0", PartialCompactRequest.Direction.FROM, null)))
            .as("[N2] 门关时 u0 已被 snip 剔除 → pivot=-1 → 404（REPL.tsx:4923-4930）；"
                + "投影若被门控则该消息复活 → pivot 命中 → 断言失败")
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("no longer in the active context");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 5 处静态调用面审计（可执行形式）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[审计] 5 处消费点全部使用单参重载（includeSnipped=false ⇒ 回放面），无一处传 true")
    void fiveCallSites_allUseReplayOverload() throws IOException {
        // 审计结论（qa.md §11.2(4) 清单，实施当日逐一复核）：
        //   LlmAgentLoop:5085（入口剥离写回 state=CC messagesForQuery 角色）→ 回放
        //   CompactCommand:217（/compact 压缩输入面）                        → 回放
        //   PartialCompactService:318（剥离后 pivot 定位判据）                → 回放
        //   StreamCompactSummary:744（流式 fallback apiMessages）             → 回放
        //   SkillifySkillRegistrar:301（extractUserMessages 输入）           → 回放
        // 全部为「模型面回放」，无一处需要「执行」语义（执行门只在 LlmAgentLoop 的 snip 步骤）。
        Pattern twoArg = Pattern.compile(
            "getMessagesAfterCompactBoundary\\([^()]*,[^()]*\\)");
        for (String file : FIVE_CALL_SITES) {
            Path p = Path.of(file);
            assertThat(Files.exists(p)).as("消费点文件必须存在: %s", file).isTrue();
            String source = Files.readString(p);
            assertThat(source)
                .as("%s 必须调用 BoundaryReader.getMessagesAfterCompactBoundary（回放门单一入口）", file)
                .contains("getMessagesAfterCompactBoundary(");
            Matcher m = twoArg.matcher(source);
            assertThat(m.find())
                .as("%s 不得传第二参（includeSnipped=true 会关闭回放；生产面不允许）", file)
                .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** [u0, u1, snip_boundary(removedUuids=[u0]), u2] · 无 compact boundary。 */
    private static List<ChatMessageDto> snipHistory() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        msgs.add(msg("u0", Role.user));
        msgs.add(msg("u1", Role.user));
        msgs.add(snipBoundary("snip-boundary-1", List.of("u0")));
        msgs.add(msg("u2", Role.user));
        return msgs;
    }

    private static List<String> ids(List<ChatMessageDto> messages) {
        return messages.stream().map(ChatMessageDto::id).toList();
    }

    private static ChatMessageDto msg(String id, Role role) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            "content-" + id, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** snip 边界消息（subtype='snip_boundary' + snipMetadata.removedUuids，CC snipCompact.ts:99-106）。 */
    private static ChatMessageDto snipBoundary(String id, List<String> removedUuids) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("removedUuids", removedUuids);
        return new ChatMessageDto(
            id, SESSION, Role.system, "system", "snip boundary", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(),
            null, false, false, null, "snip_boundary",
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, meta);
    }

    /** compact 边界消息（subtype='compact_boundary'，BoundaryReader.isCompactBoundaryMessage 判据）。 */
    private static ChatMessageDto compactBoundary(String id) {
        return new ChatMessageDto(
            id, SESSION, Role.system, "system", "compact summary", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(),
            null, false, false, null, CompactBoundaryMessage.SUBTYPE_COMPACT_BOUNDARY,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, null);
    }

}
