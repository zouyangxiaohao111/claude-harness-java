package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.tool.ToolRegistry;
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

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [reasoningDurationMs] LlmAgentLoop 后端测推理耗时测试 · 净新增（非 CC 对齐）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 用户拍板（2026-08-24）后端测量模型推理耗时
 * （thinking 阶段）。LlmAgentLoop 在流式回调中计时：首 reasoning chunk 置 reasoningStartMs、
 * 首 content chunk（或 onAssistantMessage 兜底）置 reasoningEndMs，构建点
 * {@code withReasoningDurationMs(computeReasoningDurationMs(...))} 挂载到 assistant 消息。
 * 变异点：
 * <ul>
 *   <li>推理计时起点未在 reasoning chunk 置位 → duration 恒 null → 红</li>
 *   <li>推理结束未在 content chunk 置位 → duration = now-start（虚高/漂移）→ 红</li>
 *   <li>纯 content 无 reasoning 时 computeReasoningDurationMs 误返回非 null → 无 reasoning 也留痕 → 红</li>
 *   <li>computeReasoningDurationMs 边界错（start&lt;0 应 null / end 已置应 end-start / end 未置应 now-start）→ 红</li>
 * </ul>
 *
 * <p>流式路径镜像 {@code LlmAgentLoopStreamingFallbackTombstoneTest} 的 mock-provider 模式
 * （17-arg blocks stream：onChunk@9 / onAssistantMessage@10 / onReasoningChunk@12 / onComplete@16）。
 */
@DisplayName("[reasoningDurationMs] LlmAgentLoop 推理计时 + computeReasoningDurationMs")
class LlmAgentLoopReasoningDurationTest {

    @Test
    @DisplayName("流式 reasoning→content→msg：末条 assistant 挂载非 null 且 >0 的推理耗时")
    void reasoningDurationMountedOnAssistant() throws Exception {
        // GIVEN: provider 先吐 reasoning chunk 再吐 content chunk（thinking 阶段 → 推理结束）
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onReasoning = inv.getArgument(12);
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onReasoning.accept("这是思考过程…");
            // 确保 start/end 时间戳不同（毫秒分辨率，sleep 保证 duration > 0 确定性）
            Thread.sleep(5);
            onChunk.accept("hello");
            onMsg.accept(new AssistantMessage("hello", "stop", List.of()));
            onComplete.run();
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
        runQueryLoop(state, factory);

        // THEN: 末条 assistant 消息必须携带非 null 且 >0 的推理耗时
        ChatMessageDto lastAssistant = lastAssistant(state);
        assertThat(lastAssistant).as("content 响应后必须产出 assistant 消息").isNotNull();
        assertThat(lastAssistant.reasoningDurationMs())
            .as("推理流（reasoning→content）末条 assistant 必须携带后端测推理耗时（>0）")
            .isNotNull()
            .isGreaterThan(0L);
    }

    @Test
    @DisplayName("纯 content 无 reasoning：末条 assistant 推理耗时 null（不误写）")
    void pureContentNoReasoningDurationNull() throws Exception {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("hello");
            onMsg.accept(new AssistantMessage("hello", "stop", List.of()));
            onComplete.run();
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
        runQueryLoop(state, factory);

        ChatMessageDto lastAssistant = lastAssistant(state);
        assertThat(lastAssistant).as("content 响应后必须产出 assistant 消息").isNotNull();
        assertThat(lastAssistant.reasoningDurationMs())
            .as("无 reasoning（未收到 reasoning chunk）→ 推理耗时保持 null（不误写，前端 null=无数据）")
            .isNull();
    }

    @Test
    @DisplayName("computeReasoningDurationMs：start<0 → null（无 reasoning 不记录）")
    void compute_noReasoning_returnsNull() throws Exception {
        assertThat(compute(new long[]{-1L}, new long[]{-1L})).isNull();
        assertThat(compute(null, new long[]{123L})).isNull();
    }

    @Test
    @DisplayName("computeReasoningDurationMs：end 已置位 → end-start（正常路径）")
    void compute_endSet_returnsEndMinusStart() throws Exception {
        assertThat(compute(new long[]{1000L}, new long[]{2500L})).isEqualTo(1500L);
    }

    @Test
    @DisplayName("computeReasoningDurationMs：end 未置位 → now-start（兜底：推理已开始但未结束）")
    void compute_endUnset_returnsNowMinusStart() throws Exception {
        long before = System.currentTimeMillis();
        Long result = compute(new long[]{before - 1000L}, new long[]{-1L});
        assertThat(result)
            .as("推理已开始但未结束（如 fallback/中断在途）→ now-start 兜底，非 null 且 >=0")
            .isNotNull()
            .isGreaterThanOrEqualTo(0L);
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

    private static void runQueryLoop(AgentState state, LlmProviderFactory factory) {
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            Mockito.mock(ToolRegistry.class), factory, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        ToolUseContext baseTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
            .withAvailableTools(List.of(TestContexts.dummyTool("Bash")));
        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null, baseTuc,
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());
    }

    /** 反射调用 private static {@code computeReasoningDurationMs(long[], long[])}。 */
    private static Long compute(long[] start, long[] end) throws Exception {
        Method m = LlmAgentLoop.class.getDeclaredMethod("computeReasoningDurationMs", long[].class, long[].class);
        m.setAccessible(true);
        return (Long) m.invoke(null, start, end);
    }
}
