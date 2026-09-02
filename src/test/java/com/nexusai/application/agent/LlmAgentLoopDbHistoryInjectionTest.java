package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [fix-loop-resume-history] 主路径/后台任务 DB 历史注入 loop 级测试（计划 §6 T2/T5）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: 主路径注入块（LlmAgentLoop.doRun:1890-1951）
 * 把 DB 历史全量灌入 state.messages() 并登记 prePersistedMessageIds（对齐 CC
 * {@code loadConversationForResume} 全量注入）。此前只有 service 层（T1
 * MessageServiceResumeExcludingTest）与 ChatService 层（T3 skip 用例）覆盖，注入块本体零直接
 * 测试。本测试经<b>真实 LlmAgentLoop.run</b>（mocked provider 首调 stop + mocked MessageService
 * 返回历史）钉死注入语义：
 * <ul>
 *   <li>主路径注入：run 后 state.messages() 前缀=注入历史、历史之后含当前用户消息、
 *       prePersistedMessageIds 含历史 id（否则 replayAndPersist 无法跳过重插 → 幽灵行回归）</li>
 *   <li>best-effort 失败：messageService.listBySession 抛异常 → loop 不阻断、无注入
 *       （对齐 skill 恢复块同款容错语义，残留变量不影响后续 turn）</li>
 *   <li>agentId != null 且未设任务流上下文（子代理 fork 自有上下文）→ 不注入</li>
 *   <li>[Re-think R2 修复] agentId != null 且 setTaskStreamContext 置真（后台化主会话任务，
 *       MainSessionBackgroundService:348）→ 注入全量会话历史（对齐 CC LocalMainSessionTask
 *       bgMessages 进 query({messages})，修复「后台通道模型上下文缺先前消息」）</li>
 *   <li>[计划 §6 T5] 注入超窗历史 → 首轮 auto-compact 触发、state.messages() 被替换
 *       （DRIFT-6 测量源 = messagesForQuery = 含历史的 state.messages() 快照）</li>
 * </ul>
 *
 * <p><b>测试基建</b>: 复用 LlmAgentLoopResumeRestoreEntryTest 同款真实 run 模式（裸
 * {@code new LlmAgentLoop(factory)} + mocked provider 首调 stop + {@link CapturingContextFactory}）。
 * 注入块消费 {@code messageService.listForResumeExcluding(List, String)} 重载（原始转录内存派生，
 * 消除与 skill 恢复块的重复 DB 读取）——mock 的 listBySession 返回原始转录，listForResumeExcluding
 * 返回反序列化漏斗产物。
 */
class LlmAgentLoopDbHistoryInjectionTest {

    /** 生产 sessionId 原始键（"sess-xxx" 格式 · SessionService.generateId 前缀）。 */
    private static final String SESSION_KEY = "sess-ab12cd34";

    /** 注入历史中的历史 assistant id · 应被登记 prePersistedMessageIds 且不得重插。 */
    private static final String HIST_ASST_ID = "hist-asst";
    /** 注入历史中的历史 tool 消息 id · 应被登记 prePersistedMessageIds 且不得重插。 */
    private static final String HIST_TOOL_ID = "hist-tool";

    /** provider 首调返回 stop 纯文本 → loop 正常退出（对齐 LlmAgentLoopResumeRestoreEntryTest）。 */
    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    private static ChatMessageDto historyAssistant(String id, String content) {
        return new ChatMessageDto(id, SESSION_KEY, Role.assistant, null, content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false, null);
    }

    private static ChatMessageDto historyTool(String id, String toolCallId, String content) {
        return new ChatMessageDto(id, SESSION_KEY, Role.tool, null, content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            toolCallId, null, null, List.of(), List.of(), null, false, false, null);
    }

    /** 最小 user 消息（T5 超窗历史构造 · 对齐 SubagentAutoCompactGateCcTest.singleMessage）。 */
    private static ChatMessageDto singleMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false, null);
    }

    /** 捕获 run 内构建的 LoopSessionState（经 forSession 5 参重载透传）· 供断言 skill 恢复不误伤注入。 */
    private static final class CapturingContextFactory extends AgentLoopContextFactory {
        final AtomicReference<AgentLoopContext.LoopSessionState> captured = new AtomicReference<>();

        @Override
        public AgentLoopContext forSession(String streamTopic, String streamSessionId, String streamUserMessageId,
                AgentLoopContext.LoopSessionState session, ApplicationEventPublisher overridePublisher) {
            captured.set(session);
            return super.forSession(streamTopic, streamSessionId, streamUserMessageId, session, overridePublisher);
        }
    }

    /** 装配真实 run 基建：mocked provider 首调 stop + mocked MessageService + 捕获 ctx。 */
    private LlmAgentLoop buildLoop(MessageService messageService, LlmProvider provider) {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setMessageService(messageService);
        CapturingContextFactory contextFactory = new CapturingContextFactory();
        contextFactory.setLlmProviderFactory(factory);
        loop.setContextFactory(contextFactory);
        loop.setStreamContext(null, SESSION_KEY, "msg-1");
        return loop;
    }

    @Test
    @DisplayName("主路径注入: run 后 state.messages() 前缀=注入历史、历史后含当前用户消息、prePersistedMessageIds 含历史 id")
    void resumeInjection_realRun_historyPrependedAndRegistered() {
        // GIVEN: 原始转录含 [历史 assistant, 历史 tool, 当前用户消息]；注入块经
        //        listForResumeExcluding(raw, "msg-1") 排除当前用户消息 → 注入 [hist-asst, hist-tool]
        List<ChatMessageDto> raw = List.of(
            historyAssistant(HIST_ASST_ID, "上一轮回复"),
            historyTool(HIST_TOOL_ID, "tc-hist", "上一轮工具结果"),
            new ChatMessageDto("msg-1", SESSION_KEY, Role.user, null, "resume query", null,
                List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of(), null, false, false, null));
        MessageService messageService = mock(MessageService.class);
        when(messageService.listBySession(SESSION_KEY)).thenReturn(raw);
        // 反序列化漏斗产物（服务层已覆盖语义，这里直接 mock 产物聚焦注入块本身）
        when(messageService.listForResumeExcluding(anyList(), anyString())).thenReturn(
            List.of(historyAssistant(HIST_ASST_ID, "上一轮回复"),
                historyTool(HIST_TOOL_ID, "tc-hist", "上一轮工具结果")));

        LlmAgentLoop loop = buildLoop(messageService, stopProvider("resume response"));

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // agentId=null（主会话续跑 · ChatService.processUserMessage 归一 null 语义）
        AgentState state = loop.run(RunRequest.session("resume query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        // ① 注入历史在 state.messages() 前缀（顺序保序：注入块 1889-1914 先于用户消息 2480）
        List<ChatMessageDto> msgs = state.messages();
        assertThat(msgs).extracting(ChatMessageDto::id)
            .as("主路径注入后 state.messages() 前缀必须是 DB 历史（对齐 CC loadConversationForResume 全量注入）")
            .startsWith(HIST_ASST_ID, HIST_TOOL_ID);

        // ② 历史之后含当前用户消息（本 run 的用户 prompt）
        assertThat(msgs)
            .as("历史之后必须含当前用户消息（LLM 上下文 [历史..., 当前用户]）")
            .anyMatch(m -> m.role() == Role.user && "resume query".equals(m.content()));

        // ③ 注入历史 id 登记 prePersistedMessageIds（replayAndPersist 据此跳过重插，防幽灵行）
        assertThat(state.prePersistedMessageIds())
            .as("注入历史 id 必须登记 prePersistedMessageIds（ChatService.replayAndPersist:421 消费）")
            .contains(HIST_ASST_ID, HIST_TOOL_ID);
    }

    @Test
    @DisplayName("best-effort 失败: listBySession 抛异常 → loop 不阻断、无注入（prePersistedMessageIds=null）")
    void dbReadFailure_loopContinues_noInjection() {
        // GIVEN: 主流程预取 listBySession 抛异常（DB 抖动）→ resumeRawTranscript=null
        MessageService messageService = mock(MessageService.class);
        when(messageService.listBySession(SESSION_KEY))
            .thenThrow(new RuntimeException("db down"));

        LlmAgentLoop loop = buildLoop(messageService, stopProvider("fresh response"));

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        // WHEN: 真实 run（agentId=null 主会话路径）
        AgentState state = loop.run(RunRequest.session("fresh query", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        // THEN: loop 正常返回（不阻断）；无注入（best-effort 容错，对齐 :2349 同款语义）
        assertThat(state).as("DB 读取失败不得阻断 loop（best-effort 容错）").isNotNull();
        assertThat(state.prePersistedMessageIds())
            .as("DB 读取失败 → 无历史注入（prePersistedMessageIds 保持 null，replayAndPersist 等价现状）")
            .isNull();
    }

    @Test
    @DisplayName("agentId != null 且未设任务流上下文（子代理 fork 自有上下文）→ 不注入")
    void agentIdNonNull_withoutTaskStreamContext_skipsInjection() {
        // GIVEN: messageService 可用且返回历史，但 agentId 非 null 且未调 setTaskStreamContext
        //        （子代理路径 —— fork 自有上下文，SubagentExecutor.java:2015 不调 LlmAgentLoop.run）
        MessageService messageService = mock(MessageService.class);
        when(messageService.listBySession(SESSION_KEY)).thenReturn(
            List.of(historyAssistant(HIST_ASST_ID, "上一轮回复")));

        LlmAgentLoop loop = buildLoop(messageService, stopProvider("subagent response"));

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID agentUuid = UUID.randomUUID();
        // WHEN: agentId 非 null（真子代理路径，无后台任务标志）
        AgentState state = loop.run(RunRequest.session("subagent query", sessionUuid, agentUuid,
            ProviderConfig.empty(), "test-model", null, null));

        // THEN: 不注入（注入门控 agentId==null || backgroundSessionTask；子代理两条件均不命中）
        assertThat(state).isNotNull();
        assertThat(state.prePersistedMessageIds())
            .as("子代理（无任务流上下文）→ 不得注入 DB 历史（prePersistedMessageIds 保持 null）")
            .isNull();
        assertThat(state.messages()).extracting(ChatMessageDto::id)
            .as("子代理（无任务流上下文）→ 历史不得出现在 state.messages()")
            .doesNotContain(HIST_ASST_ID);
    }

    @Test
    @DisplayName("后台化主会话任务（agentId != null + setTaskStreamContext）→ 注入全量会话历史")
    void backgroundTask_injectsHistory() {
        // GIVEN: 原始转录含 [历史 assistant, 历史 tool]；后台 loop streamUserMessageId=null →
        //        listForResumeExcluding(raw, null) 无排除 → 注入全量历史（CC LocalMainSessionTask
        //        bgMessages 进 query({messages}) :384-385）
        List<ChatMessageDto> raw = List.of(
            historyAssistant(HIST_ASST_ID, "上一轮回复"),
            historyTool(HIST_TOOL_ID, "tc-hist", "上一轮工具结果"));
        MessageService messageService = mock(MessageService.class);
        when(messageService.listBySession(SESSION_KEY)).thenReturn(raw);
        when(messageService.listForResumeExcluding(anyList(), nullable(String.class))).thenReturn(
            List.of(historyAssistant(HIST_ASST_ID, "上一轮回复"),
                historyTool(HIST_TOOL_ID, "tc-hist", "上一轮工具结果")));

        LlmAgentLoop loop = buildLoop(messageService, stopProvider("bg response"));
        // 后台任务流上下文：streamUserMessageId 置 null、backgroundSessionTask 置真
        loop.setTaskStreamContext(null, "task-1", SESSION_KEY);

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID agentUuid = UUID.randomUUID();
        // WHEN: agentId 非 null（后台化主会话任务，MainSessionBackgroundService:359 run 路径）
        AgentState state = loop.run(RunRequest.session("bg query", sessionUuid, agentUuid,
            ProviderConfig.empty(), "test-model", null, null));

        // THEN: 后台 loop 模型上下文含先前会话（修复「后台通道模型上下文缺先前消息」），
        //       历史 id 登记 prePersistedMessageIds
        assertThat(state).isNotNull();
        assertThat(state.messages()).extracting(ChatMessageDto::id)
            .as("后台任务必须注入会话历史（CC bgMessages 进 query({messages})）")
            .startsWith(HIST_ASST_ID, HIST_TOOL_ID);
        assertThat(state.prePersistedMessageIds())
            .as("后台任务注入历史 id 必须登记 prePersistedMessageIds")
            .contains(HIST_ASST_ID, HIST_TOOL_ID);
    }

    @Test
    @DisplayName("T5: 注入超窗历史 → 首轮 auto-compact 触发（state.messages() 被替换，测量源含历史）")
    void injectedOverWindowHistory_firstTurnAutoCompact() {
        // GIVEN: state.messages() 前缀 = 注入的 DB 历史（模拟 listForResumeExcluding 产物，超窗），
        //        其后为当前用户消息 —— 与注入块产出的 [历史..., 当前用户] 顺序一致
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (int i = 0; i < 50; i++) {
            state.appendMessage(singleMessage("hist-" + i, "previous context " + i));
        }
        state.appendMessage(singleMessage("current", "current query"));
        // eager autoCompactor：tokenCounter 恒 200_000 → 超窗即触发（SubagentAutoCompactGateCcTest 同款）
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m) -> new CompactConversation.SummaryResult("<summary>compacted</summary>", null));

        // provider 先独立构造（stopProvider 内部 doAnswer.when 嵌套，不能在 thenReturn 实参求值内调用，
        // 否则 Mockito UnfinishedStubbing 检测触发）再 stub factory
        LlmProvider provider = stopProvider("reply");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        com.nexusai.application.agent.loop.QueryParams params =
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty());

        // WHEN: queryLoop 首轮（DRIFT-6 测量源 = messagesForQuery = state.messages() 含注入历史）
        LlmAgentLoop.queryLoop(params, state, new java.util.ArrayList<>(), auto);

        // THEN: 首轮 auto-compact 触发 → state.messages() 被压缩替换（含 compact_boundary 摘要，
        //       不再含全部原始注入历史）——证明注入历史进入了压缩/测量管线，不因「历史在上下文而
        //       测量源不含历史」虚低/漏压缩
        assertThat(state.messages())
            .as("注入超窗历史必须在首轮触发 auto-compact（state.messages() 被替换为压缩集）")
            .anyMatch(m -> "compact_boundary".equals(m.subtype()));
        assertThat(state.messages())
            .as("压缩替换后原始超窗历史不得全量残留")
            .noneMatch(m -> "hist-0".equals(m.id()));
    }
}
