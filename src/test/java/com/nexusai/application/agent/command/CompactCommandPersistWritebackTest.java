package com.nexusai.application.agent.command;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.command.CompactCommand.CompactCommandContext;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactConversationContext;
import com.nexusai.application.agent.compact.CompactionResult;
import com.nexusai.application.agent.compact.CompactWarningState;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.compact.SqliteBusyRetry;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.config.ToolRegistrationConfig;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【compact-persist-fix】/compact 压缩结果写回（落库 + 内存替换）契约测试。
 *
 * <h2>WHY（CLAUDE.md 规则九：测试验证意图）</h2>
 * <b>真实缺陷</b>：真机跑 /compact（sess-fcdcdc68，2129 行）日志报
 * {@code [CompactCommand] SM 优先压缩成功} / {@code [R1] /compact 压缩成功}，但库中
 * {@code compact_boundary=0}、{@code is_compact_summary=1} 的行 = 0 —— <b>压缩白干</b>。
 * 根因：{@code ToolRegistrationConfig.handleCompactCommand} 拿到
 * {@link CompactCommand.CompactCommandResult} 后<b>只取 {@code displayText} 当回复</b>，压缩产物
 * （boundary + summary）被整个丢弃：不写回 state、不落库。CC 侧写回发生在命令调用方
 * （{@code processSlashCommand.tsx:895-916} 的 {@code result.type === 'compact'} 分支：
 * {@code buildPostCompactMessages(compactionResult)} → REPL 替换 messages 数组），本仓对应物
 * 即 handler。
 *
 * <p>本测试钉死的意图：<b>「/compact 报成功」必须等价于「boundary + 摘要进了历史 + 内存已是
 * 压缩后视图」</b>；两者不可分离。任何让写回失效的改动（删掉 {@code applyResultToState} 调用、
 * 只落库不替换内存、无通道时静默返回成功）都会让本测试变红。
 *
 * <p>覆盖（任务要求的四条）：
 * <ol>
 *   <li>SM 优先分支（{@code trySessionMemoryCompaction} 成功）→ 落库 + 内存替换</li>
 *   <li>传统 compactConversation 分支 → 落库 + 内存替换</li>
 *   <li>无落库通道（messageService=null 且 AgentState 未武装）→ fail-loud 降级（不静默）</li>
 *   <li>拿不到 live AgentState（注册表无该会话）→ fail-loud，且不得谎报压缩成功</li>
 * </ol>
 */
class CompactCommandPersistWritebackTest {

    private static final String SESSION = "s1";
    private static final String AGENT = "agent-1";

    @AfterEach
    void resetStaticState() {
        SessionMemoryService.setLastSummarizedMessageId(SESSION, null);
        CompactWarningState.clearCompactWarningSuppression();
        PostCompactionState.clear(SESSION);
        CacheSafeParamsHolder.clear();
        RequestContext.clear();
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** 传统分支上下文（无 SM → 直落 compactConversation）· 摘要生产者 stub。 */
    private static CompactCommandContext traditionalCtx(List<ChatMessageDto> messages) {
        CompactConversationContext cc = new CompactConversationContext();
        cc.setSessionId(SESSION);
        cc.setAgentId(AGENT);
        cc.setModel("claude-sonnet-4-5");
        cc.setQuerySource("compact");
        cc.setSummaryProducer((m, p, t) -> new CompactConversation.SummaryResult("summary ok", null));
        cc.setOnCompactProgress(e -> { });
        return new CompactCommandContext(messages, SESSION, AGENT, "compact", false, new AbortController(),
            null, new MicroCompactor(), null, () -> cc, () -> { }, () -> { },
            null, null, null, null, null, false, () -> false);
    }

    /** SM 优先上下文 · baseDir 下 s1/session-memory/summary.md 提供真实 session memory。 */
    private static CompactCommandContext smCtx(List<ChatMessageDto> messages, SessionMemoryService sm) {
        CompactConversationContext cc = new CompactConversationContext();
        cc.setSessionId(SESSION);
        cc.setAgentId(AGENT);
        cc.setModel("claude-sonnet-4-5");
        cc.setQuerySource("compact");
        cc.setSummaryProducer((m, p, t) -> {
            throw new IllegalStateException("SM 优先分支不应走摘要生产");
        });
        cc.setOnCompactProgress(e -> { });
        return new CompactCommandContext(messages, SESSION, AGENT, "compact", false, new AbortController(),
            sm, new MicroCompactor(), null, () -> cc, () -> { }, () -> { },
            null, null, null, null, null, false, () -> false);
    }

    private static SessionMemoryService newSmService(Path baseDir) throws Exception {
        java.nio.file.Files.createDirectories(baseDir.resolve(SESSION).resolve("session-memory"));
        java.nio.file.Files.writeString(
            baseDir.resolve(SESSION).resolve("session-memory").resolve("summary.md"),
            "# Learnings\nsome real learning content\n");
        SessionMemoryService sm = new SessionMemoryService(baseDir);
        sm.setSmSessionMemoryEnabled(true);
        sm.setSmCompactEnabled(true);
        SessionMemoryService.setLastSummarizedMessageId(SESSION, null);
        CompactWarningState.clearCompactWarningSuppression();
        return sm;
    }

    /** 断言「boundary + 摘要进了历史」的公共检查（顺序 = 对齐 CC buildPostCompactMessages）。 */
    private static void assertBoundaryAndSummaryPersisted(List<ChatMessageDto> persisted,
                                                          CompactionResult expected) {
        assertThat(persisted)
            .as("落库列表非空（boundary + 摘要必须进历史；旧实现恒为空 → 生产 compact_boundary=0）")
            .isNotEmpty();
        assertThat(persisted.get(0).subtype())
            .as("首条 = compact_boundary（CC buildPostCompactMessages 固定顺序；"
                + "库中 compact_boundary 行即由此产生）")
            .isEqualTo("compact_boundary");
        assertThat(persisted)
            .as("落库列表 = buildPostCompactMessages(result)（boundary → summary → keep → attachments → hooks 全量）")
            .isEqualTo(CompactionResult.buildPostCompactMessages(expected));
        assertThat(persisted.size())
            .as("至少 boundary + 摘要 2 条（仅 boundary 或空 → 摘要丢失，仍是白干的一半）")
            .isGreaterThanOrEqualTo(2);
    }

    // ════════════════════════════════════════════════════════════════════
    // 分支 1 · 传统 compactConversation（call → applyResultToState）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("传统分支: /compact 结果经 appendPostCompactMessages 落库 + state.rawMessages() 替换为压缩后视图")
    void traditionalPathPersistsAndReplacesStateMessages() {
        MessageService messageService = mock(MessageService.class);
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());

        List<ChatMessageDto> pre = List.of(
            msg("m1", Role.user, "hi"),
            msg("m2", Role.assistant, "yo"),
            msg("m3", Role.user, "how are you"));
        CompactionResult result = CompactCommand.call("  ", traditionalCtx(pre)).compactionResult();

        CompactCommand.ApplyOutcome outcome =
            CompactCommand.applyResultToState(state, SESSION, result, messageService);

        assertThat(outcome).isEqualTo(CompactCommand.ApplyOutcome.PERSISTED);
        ArgumentCaptor<List<ChatMessageDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageService).appendPostCompactMessages(eq(SESSION), captor.capture());
        assertBoundaryAndSummaryPersisted(captor.getValue(), result);
        // 内存已替换为落库归一化列表（memory 与 DB id 一致，对齐 LlmAgentLoop.persistCompactedMessages）
        assertThat(state.rawMessages())
            .as("state.rawMessages() 已替换为压缩后视图（旧实现恒为压缩前全量）")
            .isEqualTo(captor.getValue());
        assertThat(state.rawMessages().get(0).subtype()).isEqualTo("compact_boundary");
    }

    // ════════════════════════════════════════════════════════════════════
    // 分支 2 · SM 优先（日志里真实走的就是它 —— 与分支 1 返回同构，共用同一写回）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("SM 优先分支: 同样落库 boundary+摘要并替换 state.rawMessages()（两条分支不得只修一条）")
    void smPathPersistsAndReplacesStateMessages(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = newSmService(baseDir);
        MessageService messageService = mock(MessageService.class);
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());

        List<ChatMessageDto> pre = List.of(
            msg("m1", Role.user, "hi"),
            msg("m2", Role.assistant, "yo"));
        CompactCommand.CompactCommandResult callResult = CompactCommand.call("", smCtx(pre, sm));
        CompactionResult result = callResult.compactionResult();
        assertThat(result.summaryMessages())
            .as("SM 优先确实产出摘要（否则本用例没走到目标分支）")
            .isNotEmpty();
        assertThat(result.boundaryMarker())
            .as("SM 结果携带 boundaryMarker（sessionMemoryCompact.ts:461-469 等价物）——否则库里不会有 compact_boundary")
            .isNotNull();

        CompactCommand.ApplyOutcome outcome =
            CompactCommand.applyResultToState(state, SESSION, result, messageService);

        assertThat(outcome).isEqualTo(CompactCommand.ApplyOutcome.PERSISTED);
        ArgumentCaptor<List<ChatMessageDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageService).appendPostCompactMessages(eq(SESSION), captor.capture());
        assertBoundaryAndSummaryPersisted(captor.getValue(), result);
        assertThat(state.rawMessages()).isEqualTo(captor.getValue());
    }

    // ════════════════════════════════════════════════════════════════════
    // 降级 1 · 无落库通道（messageService=null 且 AgentState 未武装）→ fail-loud
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无落库通道: 返回 NO_PERSIST_CHANNEL 且内存仍替换为压缩后视图（不静默、不谎报）")
    void noPersistChannelFailsLoud() {
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
        assertThat(state.isAppendPersistenceArmed())
            .as("前置：未武装（run 空闲态，ChatService 收口已 clearCompactPersistListener）")
            .isFalse();

        List<ChatMessageDto> pre = List.of(
            msg("m1", Role.user, "hi"),
            msg("m2", Role.assistant, "yo"));
        CompactionResult result = CompactCommand.call("  ", traditionalCtx(pre)).compactionResult();

        CompactCommand.ApplyOutcome outcome =
            CompactCommand.applyResultToState(state, SESSION, result, null);

        assertThat(outcome)
            .as("无通道 → 必须显式失败（当前 bug 的本质就是此处静默什么都不做）")
            .isEqualTo(CompactCommand.ApplyOutcome.NO_PERSIST_CHANNEL);
        // 内存仍替换（本会话按压缩后视图继续），但 DB 未更新 —— 显式失败而非静默
        assertThat(state.rawMessages()).isEqualTo(CompactionResult.buildPostCompactMessages(result));
    }

    @Test
    @DisplayName("AgentState 已武装: 回落 state.persistCompactedMessages（与 auto/reactive 同一通道），messageService 可 null")
    void armedStateChannelIsUsedWhenNoDirectMessageService() {
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
        List<ChatMessageDto> capturedByState = new ArrayList<>();
        state.setAppendListener(m -> { });
        state.setCompactPersistListener(msgs -> {
            capturedByState.addAll(msgs);
            return msgs;
        });

        List<ChatMessageDto> pre = List.of(
            msg("m1", Role.user, "hi"),
            msg("m2", Role.assistant, "yo"));
        CompactionResult result = CompactCommand.call("  ", traditionalCtx(pre)).compactionResult();

        CompactCommand.ApplyOutcome outcome =
            CompactCommand.applyResultToState(state, SESSION, result, null);

        assertThat(outcome).isEqualTo(CompactCommand.ApplyOutcome.PERSISTED);
        assertBoundaryAndSummaryPersisted(capturedByState, result);
        assertThat(state.rawMessages()).isEqualTo(CompactionResult.buildPostCompactMessages(result));
    }

    @Test
    @DisplayName("落库抛异常: 返回 PERSIST_FAILED，内存仍替换（对齐 LlmAgentLoop fail-loud 兜底）")
    void persistFailureIsLoudAndKeepsInMemoryCompactedView() {
        MessageService messageService = mock(MessageService.class);
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenThrow(new RuntimeException("db down"));
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());

        List<ChatMessageDto> pre = List.of(
            msg("m1", Role.user, "hi"),
            msg("m2", Role.assistant, "yo"));
        CompactionResult result = CompactCommand.call("  ", traditionalCtx(pre)).compactionResult();

        CompactCommand.ApplyOutcome outcome =
            CompactCommand.applyResultToState(state, SESSION, result, messageService);

        assertThat(outcome).isEqualTo(CompactCommand.ApplyOutcome.PERSIST_FAILED);
        assertThat(state.rawMessages()).isEqualTo(CompactionResult.buildPostCompactMessages(result));
    }

    // ════════════════════════════════════════════════════════════════════
    // 端到端 · handleCompactCommand（CC processSlashCommand 的 Java 对应物）
    // ════════════════════════════════════════════════════════════════════

    /**
     * handleCompactCommand 成功路径 → 压缩结果落库 + displayText 原样返回（不含告警）。
     *
     * <p><b>RED 条件</b>: 删掉 handleCompactCommand 里的
     * {@code CompactCommand.applyResultToState(...)} 调用（回到「只取 displayText」的旧实现）
     * → {@code verify(messageService).appendPostCompactMessages(...)} 报
     * {@code Wanted but not invoked}，本用例红。
     */
    @Test
    @DisplayName("[端到端] handleCompactCommand 成功 → appendPostCompactMessages 落库 + displayText 无告警")
    void handleCompactCommandPersistsResult(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = newSmService(baseDir);
        MessageService messageService = mock(MessageService.class);
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
        state.replaceMessages(List.of(msg("m1", Role.user, "hi"), msg("m2", Role.assistant, "yo")));
        registry.register(SESSION, state);
        RequestContext.set(SESSION, "req-persist");

        String out = invokeHandleCompact(registry, sm, messageService);

        ArgumentCaptor<List<ChatMessageDto>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageService).appendPostCompactMessages(eq(SESSION), captor.capture());
        List<ChatMessageDto> persisted = captor.getValue();
        assertThat(persisted).isNotEmpty();
        assertThat(persisted.get(0).subtype())
            .as("库里应出现 compact_boundary 行（修复前恒 0）")
            .isEqualTo("compact_boundary");
        assertThat(state.rawMessages())
            .as("会话内存已是压缩后视图（下次拉取历史即压缩后态）")
            .isEqualTo(persisted);
        assertThat(out)
            .as("成功路径不应出现「未写入历史」告警")
            .doesNotContain(CompactCommand.WARN_NO_PERSIST_CHANNEL)
            .doesNotContain(CompactCommand.WARN_PERSIST_FAILED)
            .startsWith("Compacted ");
    }

    /**
     * 降级：压缩成功但没有落库通道（messageService 未注入 + AgentState 未武装）→
     * <b>displayText 必须显式告诉用户「未写入历史」</b>，不得静默当成功。
     *
     * <p><b>RED 条件</b>: 把 switch 里的 {@code NO_PERSIST_CHANNEL} 分支改回 {@code displayText}
     * （或删掉告警常量拼接）→ 本用例 {@code contains(WARN_NO_PERSIST_CHANNEL)} 红。
     */
    @Test
    @DisplayName("[端到端] handleCompactCommand 无落库通道 → displayText 含「未写入历史」告警（fail-loud）")
    void handleCompactCommandFailsLoudWhenNoChannel(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = newSmService(baseDir);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
        state.replaceMessages(List.of(msg("m1", Role.user, "hi"), msg("m2", Role.assistant, "yo")));
        registry.register(SESSION, state);
        RequestContext.set(SESSION, "req-nochannel");

        String out = invokeHandleCompact(registry, sm, null);

        assertThat(out)
            .as("无通道时用户可见文案必须明示「压缩结果未写入历史」（当前 bug 的本质 = 静默）")
            .contains(CompactCommand.WARN_NO_PERSIST_CHANNEL);
        // 内存仍按压缩后视图继续（boundary 在首位）
        assertThat(state.rawMessages()).isNotEmpty();
        assertThat(state.rawMessages().get(0).subtype()).isEqualTo("compact_boundary");
    }

    /**
     * 降级：拿不到 live AgentState 且<b>无重建通道</b>（messageService=null，旧实现里这是唯一形态）
     * → fail-loud 且<b>不谎报压缩成功</b>。
     *
     * <p>[compact-idle-rebuild] 语义收窄：registry miss 不再是拒绝压缩的理由 ——
     * {@code handleCompactCommand} 会先经 {@code rebuildIdleStateFromDb} 用 {@code messageService}
     * 从 DB 历史重建临时 state（对齐 CC REPL 恒持 messages）并真压缩落库
     * （覆盖见 {@code ToolRegistrationConfigCompactIdleRebuildTest}，本类
     * {@link #invokeHandleCompact} 传的 messageService=null 是「连历史都读不到」这一残留降级路径）。
     *
     * <p><b>RED 条件</b>: 若把 state==null 分支改成返回 displayText/成功文案，本用例红。
     */
    @Test
    @DisplayName("[端到端] handleCompactCommand 会话未注册 AgentState → 明确失败文案（不谎报压缩成功）")
    void handleCompactCommandFailsLoudWhenStateMissing() {
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry(); // 空注册表
        RequestContext.set(SESSION, "req-nostate");

        String out = invokeHandleCompact(registry, null, null);

        assertThat(out)
            .as("拿不到 live state → 明确说明未压缩，绝不返回 'Compacted …' 谎报成功")
            .contains("会话未注册 AgentState")
            .doesNotContain("Compacted");
    }

    /** 反射驱动私有 handleCompactCommand（参数序与生产 registerCompactSlashCommand lambda 一致）。 */
    private static String invokeHandleCompact(SessionAgentStateRegistry registry,
                                              SessionMemoryService sm,
                                              MessageService messageService) {
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        Object out = ReflectionTestUtils.invokeMethod(config, "handleCompactCommand",
            "", registry, null, null, sm, null, null, null, null, null, messageService);
        assertThat(out).isInstanceOf(String.class);
        return (String) out;
    }

    // ════════════════════════════════════════════════════════════════════
    // [P2-9 · 2026-09-12] ③ manual /compact 通道接共用 BUSY_SNAPSHOT 重试
    // ════════════════════════════════════════════════════════════════════

    /** 真机异常文案（取自 3461 实例 500 响应体）。 */
    private static final String BUSY_SNAPSHOT_MSG =
        "[SQLITE_BUSY_SNAPSHOT] Another database connection has already written to the database"
            + " (database is locked)";

    /** 生产形状：最外层 MyBatis 包装（message 不带 SQLITE_BUSY），文案在 cause 链上。 */
    private static RuntimeException busySnapshot() {
        return new RuntimeException(
            "### Error updating database. Cause: org.sqlite.SQLiteException (see cause)",
            new IllegalStateException(BUSY_SNAPSHOT_MSG));
    }

    /**
     * [P2-9 · 2026-09-12] <b>③ manual {@code /compact} 首次抛 BUSY_SNAPSHOT → 共用重试后成功</b>。
     *
     * <p><b>WHY（规则九）</b>：manual 与 auto/reactive/SM/partial 走的是<b>同一个</b> DB 出口
     * （{@code MessageService.appendPostCompactMessages}，先读 knownIds 拿读快照再写）→ WAL 下并发提交
     * 顶掉快照即抛 BUSY_SNAPSHOT，属于<b>同源故障</b>。此前只有 partial 有重试，manual 命中即
     * {@code PERSIST_FAILED}（用户可见「压缩结果写库失败」+ 下轮重复压缩）。本用例钉死
     * 「同源故障 = 同策略」。
     *
     * <p><b>RED</b>：把 {@code applyResultToState} 的直接通道改回裸调
     * {@code messageService.appendPostCompactMessages(...)}（不包 {@link SqliteBusyRetry}）→ 第 1 次
     * 就抛 → outcome 变 {@code PERSIST_FAILED}、调用次数 = 1 → 红。
     */
    @Test
    @DisplayName("[P2-9] ③ manual：首次 BUSY_SNAPSHOT → 共用重试后成功（PERSISTED，不降级不谎报）")
    void manualChannelRetriesBusySnapshotThenSucceeds() {
        MessageService messageService = mock(MessageService.class);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw busySnapshot();
            }
            return inv.getArgument(1);
        });
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
        CompactionResult result = CompactCommand.call("  ", traditionalCtx(List.of(
            msg("m1", Role.user, "hi"), msg("m2", Role.assistant, "yo")))).compactionResult();

        CompactCommand.ApplyOutcome outcome =
            CompactCommand.applyResultToState(state, SESSION, result, messageService);

        assertThat(outcome)
            .as("BUSY_SNAPSHOT 是瞬态并发错误 → 重试后必须成功，不得让用户看到「写库失败」")
            .isEqualTo(CompactCommand.ApplyOutcome.PERSISTED);
        assertThat(calls.get()).as("第 1 次 BUSY → 第 2 次成功").isEqualTo(2);
        assertThat(state.rawMessages()).as("内存已替换为落库归一化列表").isNotEmpty();
    }

    /**
     * [P2-9 · 2026-09-12] <b>③ manual BUSY_SNAPSHOT 耗尽 → {@code PERSIST_FAILED}（fail loud）</b>。
     *
     * <p>重试是<b>有限</b>的：持续 BUSY（如另一个写事务长时间不释放）必须显式失败并让用户看到
     * 「历史未更新」告警，绝不静默当成功（规则十二）。
     *
     * <p><b>RED</b>：把耗尽分支改成静默返回（吞异常 / 返回 {@code PERSISTED}）→ 红。
     */
    @Test
    @DisplayName("[P2-9] ③ manual：BUSY_SNAPSHOT 耗尽 → PERSIST_FAILED（尝试次数 = 共用上限，不静默）")
    void manualChannelFailsLoudAfterBusyRetriesExhausted() {
        MessageService messageService = mock(MessageService.class);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw busySnapshot();
        });
        AgentState state = new AgentState("sys", SESSION, UUID.randomUUID());
        CompactionResult result = CompactCommand.call("  ", traditionalCtx(List.of(
            msg("m1", Role.user, "hi"), msg("m2", Role.assistant, "yo")))).compactionResult();

        CompactCommand.ApplyOutcome outcome =
            CompactCommand.applyResultToState(state, SESSION, result, messageService);

        assertThat(outcome)
            .as("耗尽必须降级为显式失败（调用方在 displayText 里明示历史未更新）")
            .isEqualTo(CompactCommand.ApplyOutcome.PERSIST_FAILED);
        assertThat(calls.get())
            .as("尝试次数 = 共用上限（与 ①②④⑤ 同档；另起一套 = 策略分裂复发）")
            .isEqualTo(SqliteBusyRetry.MAX_WRITE_ATTEMPTS);
    }
}
