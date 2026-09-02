package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
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
 * [H7-arch Phase 5 P4 C2] streaming→non-streaming fallback tombstone 测试 · 对齐 CC query.ts:712-741。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>fallback 后新 message 到达 → tombstone 已积累的部分消息</b> — CC 在降级响应到达前为
 *       {@code assistantMessages} 逐条 yield tombstone（部分消息的 thinking block 签名已失效，
 *       残留会造成 API 错误）。Java 端必须追加 tombstone attachment。</li>
 *   <li><b>executor 重建</b> — CC query.ts:738-741 discard + new StreamingToolExecutor，防止旧
 *       tool_use_id 的孤儿 tool_results 在降级响应后泄漏。[H7-arch Phase 5-2 P3-⑤] buildStreamingExecutor
 *       已 static 化（不可 mock 计数），重建路径由「tombstone 追加 + 后续 msg 正常处理」间接覆盖——
 *       若重建被破坏（返回 null），fallback 后 msg 无 executor 可绑定，tombstone 分支逻辑失效。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert loop 内 tombstone 分支（删 streamingFallbackOccured 检查 / 不重建
 * executor / 不追加 tombstone attachment）→ 本测试必须 fail。
 */
class LlmAgentLoopStreamingFallbackTombstoneTest {

    @Test
    @DisplayName("provider 触发 fallback 后产出新 message → tombstone 追加（executor 重建为内部路径）")
    void streamingFallback_tombstoneAndRebuild() {
        // ── 1. provider：先产出部分 assistant msg → fallback → 再产出降级 msg ──
        // [H7-arch Phase 5-2 P3-⑤] 重方法已 static 化：executor 由 per-turn TUC 的 availableTools
        // （dummy "Bash"）驱动，非空 → 真实构建（含 streaming-fallback 后的 discard + 重建）。
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        // [IMP-SP-08] blocks 重载：onChunk@9/onMsg@10/onStreamingFallback@13/onComplete@16
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onStreamingFallback = inv.getArgument(13);
            Runnable onComplete = inv.getArgument(16);
            // 失败流的"部分 assistant 消息"（应被 tombstone）
            onChunk.accept("partial thinking ");
            onMsg.accept(new AssistantMessage("partial thinking", "stop", List.of()));
            // provider 内部降级为 non-streaming
            onStreamingFallback.run();
            // 降级响应
            onChunk.accept("fallback result");
            onMsg.accept(new AssistantMessage("fallback result", "stop", List.of()));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. state + ctx（per-turn TUC 携带 dummy tool → executor 会被真实构建）──
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

        ToolUseContext baseTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
            .withAvailableTools(List.of(TestContexts.dummyTool("Bash")));

        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null, baseTuc,
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());

        // ── 3. 断言 ──
        java.util.Optional<com.nexusai.application.agent.attachment.AttachmentMessageDto> tombstone =
            state.attachments().stream()
                .filter(a -> "tombstone".equals(a.type()))
                .findFirst();
        assertThat(tombstone)
            .as("fallback 后新 message 到达必须 tombstone 已积累的部分消息（CC query.ts:716-722）")
            .isPresent();
        assertThat(tombstone.get().targetMessageId())
            .as("[P-27] tombstone 载荷必须带 targetMessageId（CC tombstone message.uuid 等价位, query.ts:717）")
            .isNotNull();
    }
}
