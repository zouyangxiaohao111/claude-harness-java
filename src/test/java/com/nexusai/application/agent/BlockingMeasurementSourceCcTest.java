package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.memory.MemoryPrefetcher;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [IMP2-09 · DRIFT-6] blocking 测量源对齐测试 · CC query.ts:637-638。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>测量偏差</b> — 旧实现 blocking 预检测量 {@code messagesForLlm}（含 relevant_memories /
 *       todo/task/hook 注入 + prependUserContext），注入内容计入 token 测量 → 比 CC
 *       {@code tokenCountWithEstimation(messagesForQuery)} 高估（CC messagesForQuery 在
 *       query.ts:365 由 boundary 剥离链构建，注入内容在 query.ts:1599-1614 消费进 toolResults
 *       供下一 turn，当前 turn 测量不含注入）。</li>
 *   <li><b>修复契约</b> — 测量源 = 注入前基础消息链（boundary 剥离 + snip/micro/collapse/compact
 *       落地后、任何注入前的 state 消息链 = CC messagesForQuery 等价）；注入内容存在时测量结果
 *       与无注入场景同值，注入内容仍必须到达发送边界（LLM 调用）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert LlmAgentLoop 测量源回 {@code messagesForLlm} → 测量列表含
 * relevant_memories 注入内容，本测试 {@code containsExactlyElementsOf(baseChain)} 必须 fail。
 */
class BlockingMeasurementSourceCcTest {

    /** 注入的 relevant_memories 内容标记 · 断言测量源不含、发送边界含。 */
    private static final String INJECTED_MEMORY_CONTENT = "MEMORY-PREFETCH-INJECTED-CONTENT-IMP2-09";
    /** contextWindow 50000 → blockingLimit = 47000；测量值 1000 &lt; 47000 → 预检通过走 LLM 调用。 */
    private static final int MOCK_CONTEXT_WINDOW = 50_000;
    /** 低于 blockingLimit 的固定测量值（只验证测量源列表，不触发拦截）。 */
    private static final int MOCK_TOKEN_USAGE = 1_000;

    @Test
    @DisplayName("无注入场景：测量源 = 基础消息链（基线）")
    void noInjection_measurementIsBaseChain() {
        List<List<ChatMessageDto>> measured = new ArrayList<>();
        List<ChatMessageDto>[] sentHistory = new List[1];
        LlmProvider provider = normalCompletingProvider(sentHistory);
        AgentState state = stateWithOneUserMessage();
        List<ChatMessageDto> baseChain = new ArrayList<>(state.messages());

        // memoryPrefetcher 返回 null 句柄 → 无注入（CC 无相关记忆场景）
        MemoryPrefetcher prefetcher = Mockito.mock(MemoryPrefetcher.class);
        when(prefetcher.startPrefetch(any(), any(), any())).thenReturn(null);

        AgentLoopContext ctx = buildContext(prefetcher, provider, measured);
        LoopDeps deps = loopDeps(ctx);

        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        assertThat(measured).as("blocking 预检必须恰好测量一次").hasSize(1);
        assertThat(measured.get(0))
            .as("无注入场景测量源 = 基础消息链（CC messagesForQuery）")
            .containsExactlyElementsOf(baseChain);
        assertThat(state.exitReason())
            .as("测量值低于 blockingLimit → 不拦截，正常走 LLM 调用")
            .isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
    }

    @Test
    @DisplayName("注入内容存在 → 测量源与无注入场景同值，注入只出现在发送边界")
    void injectionPresent_measurementUnchanged() {
        List<List<ChatMessageDto>> measured = new ArrayList<>();
        List<ChatMessageDto>[] sentHistory = new List[1];
        LlmProvider provider = normalCompletingProvider(sentHistory);
        AgentState state = stateWithOneUserMessage();
        List<ChatMessageDto> baseChain = new ArrayList<>(state.messages());

        // ── relevant_memories 预取注入（对齐 CC query.ts:1599-1614 consume → 当前 turn 注入）──
        MemoryPrefetcher prefetcher = Mockito.mock(MemoryPrefetcher.class);
        MemoryPrefetcher.MemoryPrefetch handle = new MemoryPrefetcher.MemoryPrefetch(
            CompletableFuture.completedFuture(List.of(memoryAttachment())),
            com.nexusai.application.agent.tool.AbortController.NOOP);
        handle.settledAt = 1L;   // 已 settle → consume 分支触发（settledAt != 0）
        when(prefetcher.startPrefetch(any(), any(), any())).thenReturn(handle);
        when(prefetcher.filterDuplicateMemoryAttachments(anyList(), any()))
            .thenReturn(List.of(memoryAttachment()));

        AgentLoopContext ctx = buildContext(prefetcher, provider, measured);
        LoopDeps deps = loopDeps(ctx);

        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        // ── 断言 1：测量源 = 基础链（与无注入场景同值），不含注入内容 ──
        assertThat(measured).as("blocking 预检必须恰好测量一次").hasSize(1);
        List<ChatMessageDto> measuredList = measured.get(0);
        assertThat(measuredList)
            .as("注入存在时测量源仍 = 基础消息链（CC query.ts:637 messagesForQuery，注入不计入测量）")
            .containsExactlyElementsOf(baseChain);
        assertThat(measuredList.stream().map(ChatMessageDto::content))
            .as("测量源不得含 relevant_memories 注入内容（DRIFT-6 测量偏差修复）")
            .noneMatch(c -> c != null && c.contains(INJECTED_MEMORY_CONTENT));

        // ── 断言 2：注入内容到达发送边界（LLM 调用仍收到注入）──
        assertThat(sentHistory[0])
            .as("发送边界必须含注入内容（注入机制不受测量源切换影响）")
            .anyMatch(m -> m.content() != null && m.content().contains(INJECTED_MEMORY_CONTENT));
        assertThat(state.exitReason())
            .as("测量值低于 blockingLimit → 不拦截，正常走 LLM 调用")
            .isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static MemoryPrefetcher.RelevantMemoryAttachment memoryAttachment() {
        return new MemoryPrefetcher.RelevantMemoryAttachment(
            "mem/imp2-09.md", INJECTED_MEMORY_CONTENT, 0L, "relevant_memories", null);
    }

    private static AgentState stateWithOneUserMessage() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        return state;
    }

    /** 正常完成的 provider stub（17-arg blocks 重载）· 记录发送边界 history（arg 3）。 */
    private static LlmProvider normalCompletingProvider(List<ChatMessageDto>[] sentHistory) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<ChatMessageDto> history = new ArrayList<>((List<ChatMessageDto>) inv.getArgument(3));
            sentHistory[0] = history;
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain text reply");
            onMsg.accept(new AssistantMessage("plain text reply", "stop", List.of()));
            onComplete.run();
            return null;
        }).when(provider).stream(
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /** 捕获 tokenCountWithEstimation 入参的 TokenBudgetBeans（固定 contextWindow / 低 tokenUsage）。 */
    private static AgentLoopContext.TokenBudgetBeans capturingTokenBudgetBeans(List<List<ChatMessageDto>> measured) {
        TokenEstimator te = Mockito.mock(TokenEstimator.class);
        when(te.tokenCountWithEstimation(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<ChatMessageDto> list = (List<ChatMessageDto>) inv.getArgument(0);
            measured.add(new ArrayList<>(list));
            return MOCK_TOKEN_USAGE;
        });
        ModelMapper modelMapper = Mockito.mock(ModelMapper.class);
        ModelRecord model = new ModelRecord();
        model.setProviderId("p1");
        // W2-1: 窗口源从 provider 级迁到模型级（运行时改读 models.max_context_tokens）
        model.setMaxContextTokens(MOCK_CONTEXT_WINDOW);
        when(modelMapper.selectOneByQuery(any())).thenReturn(model);
        // W1-2 ModelNameResolver 兼容路径（裸名查询）走 selectListByQuery —— 必须 stub 否则 resolve 回落默认
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model));
        ProviderMapper providerMapper = Mockito.mock(ProviderMapper.class);
        ProviderRecord provider = new ProviderRecord();
        when(providerMapper.selectOneById("p1")).thenReturn(provider);
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider);
        return new AgentLoopContext.TokenBudgetBeans(te, modelMapper, providerMapper);
    }

    /** 最小 ctx：唯一注入 = memoryPrefetcher（位置 7）+ factory + tokenBudgetBeans（位置 28）。 */
    private static AgentLoopContext buildContext(MemoryPrefetcher prefetcher, LlmProvider provider,
                                                 List<List<ChatMessageDto>> measured) {
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        // [merge 适配 2026-08-14] AgentLoopContext 以 LlmProviderFactory 取 provider（不再直传），
        //   mock 必须 stub getProvider 否则 queryLoop 内 provider 为 null → NPE（provider.type()）
        Mockito.when(factory.getProvider(Mockito.any(), Mockito.any())).thenReturn(provider);
        return new AgentLoopContext(
            null, null, null, null, null, null,                    // 1-6
            prefetcher, null, null, null,                          // 7-10 memoryPrefetcher
            factory, null, null, null, null, null, null, null, null,   // 11-19 llmProviderFactory
            FeatureFlags.ALL_DISABLED, null, null, null, null, null,   // 20-25
            null, capturingTokenBudgetBeans(measured), null, null, null, null, null);  // 26-32 tokenBudgetBeans
    }

    private static LoopDeps loopDeps(AgentLoopContext ctx) {
        return new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
    }
}
