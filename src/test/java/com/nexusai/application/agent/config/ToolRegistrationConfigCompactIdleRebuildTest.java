package com.nexusai.application.agent.config;

import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.CompactWarningState;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.memory.SessionMemoryService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【compact-idle-rebuild】空闲/未注册会话上的 manual {@code /compact} 契约测试。
 *
 * <h2>WHY（CLAUDE.md 规则九：测试验证意图，而非仅验证行为）</h2>
 * <b>真实缺陷</b>：用户打开一个历史会话（尚未跑任何一轮 → 主循环没在跑 → 该会话未注册
 * {@code AgentState}），打 {@code /compact} → 生产日志只有一行
 * {@code [R1] /compact 会话未注册 AgentState}（旧实现 {@code ToolRegistrationConfig:2231} 直接 bail），
 * <b>boundary / 摘要一行都没落、什么都不做</b>。
 *
 * <p>根因不是「/compact 写回」而是「state 从哪来」：本仓 {@code AgentState} 只由
 * {@code LlmAgentLoop.run} 主会话入口注册（{@code LlmAgentLoop:2454}），空闲会话 registry miss；
 * 而 CC 的 REPL <b>恒持有 messages</b>（{@code src/commands/compact/compact.ts:44}
 * {@code let { messages } = context}）→ CC 的 {@code /compact} 任何时刻都能压。
 *
 * <p>本测试钉死的意图：<b>「空闲会话 /compact」必须与「在跑会话 /compact」等价地产生
 * boundary + 摘要并落库</b>，而「未注册」只应是「无法重建」（messageService 未注入 / 会话不存在）
 * 的降级理由，不再是拒绝压缩的理由。让重建失效的任何改动都会让本测试变红：
 * <ul>
 *   <li>删掉 {@code state = rebuildIdleStateFromDb(...)}（回到 registry miss 即 bail）→
 *       {@link #unregisteredSessionRebuildsFromDbAndCompacts} 的 appendPostCompactMessages 断言
 *       {@code Wanted but not invoked} 红 + 文案断言红；</li>
 *   <li>把临时 state 注册进 registry → {@link #rebuiltStateIsNotRegisteredIntoLRegistry} 红；</li>
 *   <li>去掉在途 {@code /compact} 行排除守卫 → 排除语义断言红（详见各用例 RED 条件）。</li>
 * </ul>
 *
 * <p><b>测试基建</b>：复用 {@code CompactCommandPersistWritebackTest} 同款「真实 SM 优先分支」手法
 * —— {@link SessionMemoryService} 指向 {@code @TempDir} 且 session-memory/summary.md 已落盘 →
 * {@code trySessionMemoryCompaction} 直接产出压缩结果，<b>不发起任何 LLM 请求</b>（离线可跑）。
 */
class ToolRegistrationConfigCompactIdleRebuildTest {

    /** 会话 DB 键（short 形态；同时是 SM 目录名 → 必须是单层合法路径段）。 */
    private static final String SESSION = "sess-idle01";

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

    /** SM 优先分支可用的 SessionMemoryService（@TempDir 落 summary.md → 零 LLM 调用）。 */
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
    // 1 · 未注册会话（registry miss）→ 从 DB 重建 → 真压缩 + 真落库 + 不注册
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>RED 条件</b>:
     * <ol>
     *   <li>把 {@code state == null} 分支改回「直接 return '/compact 会话未注册 AgentState（无进行中循环）。'」
     *       （即删掉 {@code rebuildIdleStateFromDb} 调用）→
     *       {@code verify(messageService).appendPostCompactMessages(...)} 报
     *       {@code Wanted but not invoked}，且 {@code doesNotContain("会话未注册 AgentState")} 红。</li>
     *   <li>把重建结果 {@code registry.register(SESSION, rebuilt)} 进 registry →
     *       {@code assertThat(registry.get(SESSION)).isNull()} 红（见下一个用例的完整理由）。</li>
     * </ol>
     */
    @Test
    @DisplayName("[空闲会话] registry miss → 从 DB 历史重建后真压缩 + boundary/摘要落库（旧实现：只 WARN、什么都不做）")
    void unregisteredSessionRebuildsFromDbAndCompacts(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = newSmService(baseDir);
        MessageService messageService = mock(MessageService.class);
        // DB 历史 = 历史会话的真实转录；末条 = ChatController 在本命令 dispatch 前落库的 /compact 行
        // （ChatController:149 createUserMessage → ChatService.processUserMessage:624 经 requestId 带下来）
        List<ChatMessageDto> dbRows = new ArrayList<>(List.of(
            msg("h1", Role.user, "first question"),
            msg("h2", Role.assistant, "first answer"),
            msg("h3", Role.user, "/compact")));
        when(messageService.listBySession(SESSION)).thenReturn(dbRows);
        when(messageService.listForResumeExcluding(anyList(), nullable(String.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry(); // 空 = 空闲进程，无 live state
        RequestContext.set(SESSION, "h3"); // = ChatService:624 set(sessionId, userMessageId)

        String out = invokeHandleCompact(registry, sm, messageService);

        // ① 不再拒绝：既不报「未注册」，也不谎报；成功且显式说明「已从历史重建」
        assertThat(out)
            .as("空闲会话不再是 bail 理由（旧实现的全部行为 = 这句文案 + 零落库）")
            .doesNotContain("会话未注册 AgentState")
            .contains(ToolRegistrationConfig.INFO_REBUILT_FROM_DB)
            .contains("Compacted");
        // ② 压缩产物真落库（boundary + 摘要）—— 旧实现此处恒 0 条（真机日志实证）
        ArgumentCaptor<List<ChatMessageDto>> persisted = ArgumentCaptor.forClass(List.class);
        verify(messageService).appendPostCompactMessages(eq(SESSION), persisted.capture());
        assertThat(persisted.getValue())
            .as("boundary + 摘要必须进历史（旧实现一条都没有）")
            .isNotEmpty();
        assertThat(persisted.getValue().get(0).subtype())
            .as("首条 = compact_boundary（CC buildPostCompactMessages 固定顺序）")
            .isEqualTo("compact_boundary");
        assertThat(persisted.getValue().size())
            .as("至少 boundary + 摘要 2 条（只有 boundary = 摘要丢失，仍是白干一半）")
            .isGreaterThanOrEqualTo(2);
        // ③ 压缩输入 = 中断语义漏斗后的历史（listForResume 通道，与 LlmAgentLoop:2402 /
        //    PartialCompactService:224 同源），且在途 /compact 行被排除（CC: 压缩后才拼回 messagesToKeep）
        ArgumentCaptor<List<ChatMessageDto>> input = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> excludeId = ArgumentCaptor.forClass(String.class);
        verify(messageService).listForResumeExcluding(input.capture(), excludeId.capture());
        assertThat(excludeId.getValue())
            .as("在途 /compact 行（requestId）必须排除 —— CC processSlashCommand.tsx:883-895 是压缩完成"
                + "之后才把它拼进 messagesToKeep，压缩输入不含自身；不排除会引入幽灵 'Continue' sentinel")
            .isEqualTo("h3");
        // ④ 临时 state 不进 registry：无生命周期所有者（唯一回收点=会话删除）→ 注册即无界泄漏；
        //    且会覆盖在飞主循环的 live state（SkillTool 写侧 / 权限弹窗 / partial compact 的解析目标）
        assertThat(registry.get(SESSION))
            .as("临时重建 state 绝不注册（见 rebuildIdleStateFromDb javadoc「不注册」三条理由）")
            .isNull();
    }

    /**
     * 与上例同一场景，独立成例以便单点定位「是否注册」这一决定。
     *
     * <p><b>RED 条件</b>: 在 {@code handleCompactCommand} 的 registry miss 分支补
     * {@code registry.register(rawSessionId, state)}（或让 {@code rebuildIdleStateFromDb} 内部注册）
     * → 本用例红。
     */
    @Test
    @DisplayName("[空闲会话] 临时重建 state 不注册进 SessionAgentStateRegistry（防覆盖在飞主循环 / 防泄漏）")
    void rebuiltStateIsNotRegisteredIntoLRegistry(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = newSmService(baseDir);
        MessageService messageService = mock(MessageService.class);
        when(messageService.listBySession(SESSION)).thenReturn(new ArrayList<>(List.of(
            msg("h1", Role.user, "q"), msg("h2", Role.assistant, "a"))));
        when(messageService.listForResumeExcluding(anyList(), nullable(String.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        RequestContext.set(SESSION, "req-idle-2");

        invokeHandleCompact(registry, sm, messageService);

        assertThat(registry.get(SESSION))
            .as("registry 只登记「主循环在跑」的 live state，重建的临时 state 不登记（无所有者 → 泄漏；"
                + "且会被 SkillTool 写侧 / 权限弹窗 / partial compact 误当作 live state 读出）")
            .isNull();
        assertThat(registry.size()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2 · 已注册会话（主循环在跑 / 刚跑完）→ 行为不变（回归）
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>RED 条件</b>: 把重建改成「无条件从 DB 重建」（不判 registry miss，例如把
     * {@code if (state == null)} 去掉直接调 {@code rebuildIdleStateFromDb}）→
     * {@code verify(messageService, never()).listForResumeExcluding(...)} 红（多读一次 DB），
     * 且 {@code doesNotContain(INFO_REBUILT_FROM_DB)} 红（谎报「从历史重建」）。
     */
    @Test
    @DisplayName("[回归] 已注册会话走 live state 原路径：不读 DB 重建、文案无「已重建」说明、落库不变")
    void registeredSessionKeepsLiveStatePath(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = newSmService(baseDir);
        MessageService messageService = mock(MessageService.class);
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        com.nexusai.application.agent.AgentState live =
            new com.nexusai.application.agent.AgentState("sys", SESSION, java.util.UUID.randomUUID());
        live.replaceMessages(List.of(
            msg("m1", Role.user, "hi"), msg("m2", Role.assistant, "yo")));
        registry.register(SESSION, live);
        RequestContext.set(SESSION, "req-live");

        String out = invokeHandleCompact(registry, sm, messageService);

        assertThat(out)
            .as("live 路径不追加「已从历史重建」说明（否则用户被误导为重建过）")
            .doesNotContain(ToolRegistrationConfig.INFO_REBUILT_FROM_DB)
            .doesNotContain("会话未注册 AgentState");
        verify(messageService, never())
            .listForResumeExcluding(anyList(), nullable(String.class));
        verify(messageService, never()).listBySession(SESSION);
        ArgumentCaptor<List<ChatMessageDto>> persisted = ArgumentCaptor.forClass(List.class);
        verify(messageService).appendPostCompactMessages(eq(SESSION), persisted.capture());
        assertThat(persisted.getValue().get(0).subtype()).isEqualTo("compact_boundary");
        assertThat(live.messages())
            .as("live state 内存仍是压缩后视图（原有写回语义不变）")
            .isEqualTo(persisted.getValue());
    }

    // ════════════════════════════════════════════════════════════════════
    // 3 · 空会话（DB 里真的一条消息都没有）→ 对齐 CC 'No messages to compact'
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>RED 条件</b>: 若为空历史补一条兜底消息（或提前 return 别的文案）→
     * {@code contains("No messages to compact")} 红。
     */
    @Test
    @DisplayName("[空会话] 无任何消息 → 走 CompactCommand 空校验，文案 = CC 'No messages to compact'（不是「未注册」）")
    void emptySessionReportsNoMessagesToCompact(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = newSmService(baseDir);
        MessageService messageService = mock(MessageService.class);
        when(messageService.listBySession(SESSION)).thenReturn(new ArrayList<>());
        when(messageService.listForResumeExcluding(anyList(), nullable(String.class)))
            .thenReturn(List.of());

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        RequestContext.set(SESSION, "req-empty");

        String out = invokeHandleCompact(registry, sm, messageService);

        assertThat(out)
            .as("空会话 = CC compact.ts:48-50 `if (messages.length === 0) throw new Error('No messages to compact')` "
                + "—— Java 对应物即 CompactCommand.call 的 IllegalArgumentException(ERROR_NO_MESSAGES_TO_COMPACT) 分支，"
                + "与「未注册 AgentState」是两件不同的事，不得混为一谈")
            .contains(com.nexusai.application.agent.command.CompactCommand.ERROR_NO_MESSAGES_TO_COMPACT)
            .doesNotContain("会话未注册 AgentState")
            .doesNotContain("Compacted");
        // 空会话无产物可落库（DB 不变，只回显 CC 文案）
        verify(messageService, never()).appendPostCompactMessages(eq(SESSION), anyList());
        assertThat(registry.size()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4 · 残留/异请求 requestId 不得误删真实历史（排除守卫）
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>RED 条件</b>: 把 requestId 无条件当 excludeId 传下去（去掉「须为末条 id」守卫）→
     * 本用例 {@code isNull()} 红（会把一条真实历史消息从压缩输入里静默丢掉）。
     */
    @Test
    @DisplayName("[守卫] requestId 不是末条消息 id（跨请求残留 / CommandController 直调路径）→ 不排除任何消息")
    void staleOrForeignRequestIdDoesNotExcludeRealMessage(@TempDir Path baseDir) throws Exception {
        SessionMemoryService sm = newSmService(baseDir);
        MessageService messageService = mock(MessageService.class);
        // 末条是真实会话内容（CommandController.executeCompactBuiltin 直调路径不落 /compact 行）
        when(messageService.listBySession(SESSION)).thenReturn(new ArrayList<>(List.of(
            msg("m1", Role.user, "q"), msg("m2", Role.assistant, "a"))));
        when(messageService.listForResumeExcluding(anyList(), nullable(String.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        RequestContext.set(SESSION, "msg-stale-from-other-request"); // 不属于本转录

        invokeHandleCompact(registry, sm, messageService);

        ArgumentCaptor<String> excludeId = ArgumentCaptor.forClass(String.class);
        verify(messageService).listForResumeExcluding(anyList(), excludeId.capture());
        assertThat(excludeId.getValue())
            .as("守卫：requestId 必须恰为本转录末条 id 才排除，否则跨请求残留 reqId 会静默删掉一条真实历史")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 5 · 无重建通道（messageService 未注入）→ 维持 fail-loud（不谎报成功）
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>RED 条件</b>: 若把「无法重建」也当成功返回（或返回 null）→
     * {@code contains("无法从历史重建")} / {@code doesNotContain("Compacted")} 红。
     *
     * <p>与 {@code CompactCommandPersistWritebackTest#handleCompactCommandFailsLoudWhenStateMissing}
     * 互补：那条钉「未注册 + 无通道 → 不谎报成功」，本条钉「失败理由必须显式区分『无法重建』」。
     */
    @Test
    @DisplayName("[降级] 未注册 + messageService 未注入 → 显式报「无法从历史重建」，不谎报成功")
    void unregisteredWithoutRebuildChannelFailsLoud() {
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        RequestContext.set(SESSION, "req-nochannel");

        String out = invokeHandleCompact(registry, null, null);

        assertThat(out)
            .as("无重建通道（messageService=null）→ 无法取历史 → 必须显式失败并说明理由")
            .contains("会话未注册 AgentState")
            .contains("无法从历史重建")
            .doesNotContain("Compacted");
        assertThat(registry.size()).isZero();
    }
}
