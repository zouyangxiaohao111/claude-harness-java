package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.recovery.FallbackTriggeredError;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * [H7-arch Phase 5 P4 C3] model fallback 测试 · 对齐 CC query.ts:894-953 + withRetry.ts:160-168。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>FallbackTriggeredError → 模型切到 fallbackModel + 重试</b> — CC {@code currentModel =
 *       fallbackModel; attemptWithFallback = true} 后用新模型重发整个请求。</li>
 *   <li><b>orphan tool_use 造 error tool_result</b> — CC {@code yieldMissingToolResultBlocks
 *       (assistantMessages)} 保证 tool_use/tool_result 配对契约，避免 API 拒收。</li>
 *   <li><b>warning system message</b> — CC {@code createSystemMessage('Switched to ...', 'warning')}
 *       用户无需 verbose 也能看到降级通知。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert loop 内 FallbackTriggeredError 分支（不切换模型 / 不造 error
 * tool_result / 不重试）→ 本测试必须 fail。
 */
class LlmAgentLoopModelFallbackTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("provider 抛 FallbackTriggeredError → 模型切到 fallbackModel + 重试 + orphan tool_use 有 error tool_result")
    void fallbackError_switchesModelAndRetries() {
        // ── 1. provider ──
        // [H7-arch Phase 5-2 P3-⑤] 重方法已 static 化（真实 buildStreamingExecutor）：
        //   isStreamingToolExecutionEnabled → ctx.queryConfig()==null → true；
        //   computeBudgetFromGates → 无 TokenBudgetBeans → FALLBACK=200_000；
        //   getModelForCall → deps.resolveModel() null → 回落 recoveryState=params.modelName()。
        //   executor 由 per-turn TUC 的 availableTools（dummy "Bash"）驱动，非空 → 真实构建。

        // ── 2. provider：1st 抛 FallbackTriggeredError（带 tool_calls 的 orphan），2nd 成功 ──
        List<String> calledModels = new ArrayList<>();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        // [IMP-SP-08] blocks 重载：model@1 不变，onChunk@9/onMsg@10/onErr@15/onComplete@16
        Mockito.doAnswer(inv -> {
            String model = inv.getArgument(1);
            calledModels.add(model);
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            if (calledModels.size() == 1) {
                // 1st call: 先产出 tool_calls（orphan），再抛 FallbackTriggeredError
                ObjectNode input = JSON.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("I'll check", "tool_calls",
                    List.of(new ToolUseBlock("toolu_c3_1", "Bash", input))));
                onErr.accept(new FallbackTriggeredError("test-model", "fallback-model"));
            } else {
                // 2nd call: 降级模型成功返回纯文本
                onChunk.accept("Final answer from fallback");
                onMsg.accept(new AssistantMessage("Final answer from fallback", "stop", List.of()));
                onComplete.run();
            }
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 3. state + ctx ──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);

        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };

        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(
                    java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(java.util.List.of(
                        com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());

        // ── 4. 断言 ──
        assertThat(calledModels)
            .as("重试必须发生（2 次 provider 调用）且第 2 次用 fallbackModel（CC query.ts:896 currentModel=fallbackModel）")
            .containsExactly("test-model", "fallback-model");
        assertThat(state.messages().stream().anyMatch(m ->
                m.role() == Role.tool && m.content() != null && m.content().contains("Model fallback triggered")))
            .as("orphan tool_use 必须造 is_error tool_result（CC yieldMissingToolResultBlocks）")
            .isTrue();
        assertThat(state.messages().stream().anyMatch(m ->
                m.role() == Role.system
                    && "informational".equals(m.subtype())
                    && "warning".equals(m.level())
                    && m.content() != null && m.content().contains("Switched to")))
            .as("必须 yield warning system message（P-27: CC createSystemMessage(content,'warning') → "
                + "role=system/subtype=informational/level=warning, query.ts:945-948）")
            .isTrue();
    }

    @Test
    @DisplayName("fallback warning 用 renderModelName 显示名（CC query.ts:946 renderModelName(fallback)/renderModelName(original)）")
    void fallbackWarningUsesRenderModelName() {
        // ── provider：1st 抛 FallbackTriggeredError（已知模型名），2nd 成功 ──
        List<String> calledModels = new ArrayList<>();
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            calledModels.add(inv.getArgument(1));
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            if (calledModels.size() == 1) {
                // 1st call: 抛 FallbackTriggeredError — Opus 4.6 → Sonnet 4.5
                onErr.accept(new FallbackTriggeredError(
                    "claude-opus-4-6", "claude-sonnet-4-5-20250929"));
            } else {
                onChunk.accept("Final answer from fallback");
                onMsg.accept(new AssistantMessage("Final answer from fallback", "stop", List.of()));
                onComplete.run();
            }
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };

        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(
                    java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(java.util.List.of(
                        com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "claude-opus-4-6", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());

        // ── 断言：warning 内容用显示名（CC model.ts:395-412 renderModelName）──
        // [P-27] 改读 role=system 消息（旧 model_fallback_warning attachment 已删, query.ts:945-948）
        String warningContent = state.messages().stream()
            .filter(m -> m.role() == Role.system && "warning".equals(m.level()))
            .map(ChatMessageDto::content)
            .findFirst().orElse(null);
        assertThat(warningContent)
            .as("warning 必须用 renderModelName 显示名而非 raw 模型 ID（CC query.ts:946）")
            .isEqualTo("Switched to Sonnet 4.5 due to high demand for Opus 4.6");
        assertThat(calledModels)
            .as("重试必须发生且第 2 次用 fallbackModel")
            .containsExactly("claude-opus-4-6", "claude-sonnet-4-5-20250929");
    }
}
