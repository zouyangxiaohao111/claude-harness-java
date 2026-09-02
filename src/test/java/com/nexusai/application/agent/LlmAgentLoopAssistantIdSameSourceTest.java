package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.recovery.MaxTokensHandler;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [同源改造] LlmAgentLoop assistant 落库 id 统一 turnAssistantId · 净新增。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 纯文本 turn（:5890）与 max_tokens 截断续写
 * （:5599）append 的 assistant 消息 id 必须 == 本 turn {@code prepareAssistantMessageId()} 的返回值
 * （=流式 chunk.assistantMessageId 同源），否则 state.messages() 内 id 与前端块 id 不一致，后续
 * ChatService 落库（B1 取末条 assistant 真实 id）也无法同源。变异点：
 * <ul>
 *   <li>5890 未传 4-参 → 纯文本 assistant id 随机 ≠ turnAssistantId → 红</li>
 *   <li>5599 仍 3-参 → 截断 assistant id 随机 ≠ turnAssistantId → 红</li>
 * </ul>
 *
 * <p>流式路径镜像 {@code LlmAgentLoopReasoningDurationTest} 的 mock-provider 模式
 * （blocks stream：onChunk@9 / onAssistantMessage@10 / onComplete@16）；max_tokens 恢复路径镜像
 * {@code MaxOutputTokensEscalationIntegrationTest}（maxTokensHandler 注入，record 位置 13）。
 */
@DisplayName("[同源改造] LlmAgentLoop assistant 落库 id 统一 turnAssistantId")
class LlmAgentLoopAssistantIdSameSourceTest {

    // ─────────────────────── 基础设施 helper ───────────────────────

    private AgentState initialState() {
        AgentState state = new AgentState("sys", "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m0", "s", Role.user, "user", "hello", null, List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null, null, List.of(), List.of()));
        return state;
    }

    private void runQueryLoop(AgentState state, LlmProviderFactory factory, AgentLoopContext ctx) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        QueryParams params = QueryParams.forLoop(
            state.messages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8))
                .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());
    }

    /** 流式纯文本 provider（plain text → 'stop'，无 tool_use）。 */
    private LlmProviderFactory plainTextProviderFactory(AtomicInteger callCount) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            @SuppressWarnings("unchecked")
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int call = callCount.getAndIncrement();
            onChunk.accept("response " + call);
            onMsg.accept(new AssistantMessage("response " + call, "stop", List.of()));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    /**
     * max_tokens 截断 provider：按 {@code finishReasons} 返回对应 finishReason 的 assistant message
     * （越界回落最后一项），同时把每次调用时的 {@code state.currentAssistantMessageId()}
     * （=本迭代 turnAssistantId · :4047 prepare 已设）捕获到 {@code capturedTurnIds}。
     * blocks 重载回调位置：onChunk@9/onMsg@10/onComplete@16。
     */
    private LlmProviderFactory truncationProviderFactory(List<String> finishReasons, AtomicInteger callCount,
            AgentState state, List<String> capturedTurnIds) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            @SuppressWarnings("unchecked")
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int call = callCount.getAndIncrement();
            // 捕获本迭代 turnAssistantId（:4047 prepare 后、stream 回调时刻即生效）
            capturedTurnIds.add(state.currentAssistantMessageId());
            String text = "response " + call;
            onChunk.accept(text);
            String fr = finishReasons.get(Math.min(call, finishReasons.size() - 1));
            String apiError = ("max_tokens".equals(fr) || "model_context_window_exceeded".equals(fr))
                ? "max_output_tokens" : null;
            onMsg.accept(new AssistantMessage(text, fr, List.of(), null, apiError));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    /**
     * 最小 AgentLoopContext · maxTokensHandler 注入（record 位置 13）。
     * 对齐 {@code MaxOutputTokensEscalationIntegrationTest.recoveryCtx} 结构。
     */
    private AgentLoopContext recoveryCtx(LlmProviderFactory factory, MaxTokensHandler handler) {
        FeatureFlags flags = new FeatureFlags(false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
        return new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class), // 1
            null, null, null, null,                                    // 2-5
            null, null, null, null,                                    // 6-9
            null, factory, null, handler, null, null,                  // 10-15
            null, null, null, null,                                    // 16-19
            flags, null, null, null, null, null,                       // 20-25
            null, null, null, null, null, null, null);                 // 26-32
    }

    private MaxTokensHandler maxTokensHandler(boolean gate) {
        MaxTokensHandler handler = new MaxTokensHandler(gate);
        handler.setSettingsOverrideProvider(() -> null); // 无 settings.maxOutputTokens override
        return handler;
    }

    private AgentLoopContext plainCtx(LlmProviderFactory factory) {
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class), factory, null, null, null);
        return ctx;
    }

    // ─────────────────────── 用例 ───────────────────────

    @Test
    @DisplayName("纯文本 turn：末条 assistant id == 本 turn prepareAssistantMessageId 返回值（=chunk 同源）")
    void plainTextTurnAssistantIdSameSource() {
        AgentState state = initialState();
        AtomicInteger callCount = new AtomicInteger();
        LlmProviderFactory factory = plainTextProviderFactory(callCount);
        AgentLoopContext ctx = plainCtx(factory);

        runQueryLoop(state, factory, ctx);

        ChatMessageDto lastAsst = lastAssistant(state);
        assertThat(lastAsst).as("content 响应后必须产出 assistant 消息").isNotNull();
        assertThat(lastAsst.id())
            .as("纯文本 turn 末条 assistant id 必须 == 本 turn prepareAssistantMessageId 返回值（=turnAssistantId=chunk 同源）")
            .isEqualTo(state.currentAssistantMessageId());
        assertThat(state.currentAssistantMessageId())
            .as("turnAssistantId 为真实 UUID（非 null）")
            .isNotNull();
    }

    @Test
    @DisplayName("max_tokens RECOVERY 截断续写：截断 assistant id == 本迭代 turnAssistantId，且迭代间无 id 冲突")
    void maxTokensRecoveryTruncationSameSource() {
        AgentState state = initialState();
        AtomicInteger callCount = new AtomicInteger();
        List<String> capturedTurnIds = new ArrayList<>();
        // gate 关闭 → 直走多轮续写（CC query.ts:1223 只续写不发升级）；全截断 → RECOVERY 续写路径
        LlmProviderFactory factory = truncationProviderFactory(
            List.of("max_tokens"), callCount, state, capturedTurnIds);
        AgentLoopContext ctx = recoveryCtx(factory, maxTokensHandler(false));

        runQueryLoop(state, factory, ctx);

        // 每次 stream 回调捕获的 currentAssistantMessageId == 该迭代 turnAssistantId
        assertThat(capturedTurnIds)
            .as("RECOVERY 路径必须产生 ≥3 次 LLM 调用（多轮续写 ≤3 + 末次耗尽 surface 不续写）")
            .hasSizeGreaterThanOrEqualTo(3);
        // 截断 assistant 消息（content="response N"）id 必须与对应迭代 turnAssistantId 一致（同源）
        //   —— 末次耗尽调用（exhausted）不追加截断 assistant，故 truncationAsst.size() <= capturedTurnIds.size()
        List<ChatMessageDto> truncationAsst = state.messages().stream()
            .filter(m -> m.role() == Role.assistant)
            .filter(m -> m.content() != null && m.content().startsWith("response "))
            .toList();
        assertThat(truncationAsst)
            .as("RECOVERY 截断续写必须追加 ≥2 条截断 assistant（多轮续写）")
            .hasSizeGreaterThanOrEqualTo(2);
        for (int i = 0; i < truncationAsst.size(); i++) {
            assertThat(truncationAsst.get(i).id())
                .as("截断 assistant #%d id 必须 == 本迭代 turnAssistantId（=chunk 同源）", i)
                .isEqualTo(capturedTurnIds.get(i));
        }
        // 无 id 冲突：迭代间 turnAssistantId 互不相同（:4047 每迭代重新 prepare）
        assertThat(new HashSet<>(capturedTurnIds))
            .as("迭代间 turnAssistantId 互不相同（continue 后 :4047 重新 prepare，无 id 冲突）")
            .hasSize(capturedTurnIds.size());
    }

    // ── helpers ──

    private static ChatMessageDto lastAssistant(AgentState state) {
        List<ChatMessageDto> messages = state.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                return m;
            }
        }
        return null;
    }
}
