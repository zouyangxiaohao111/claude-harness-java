package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.prompt.SystemPromptInjection;
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

import java.lang.reflect.Field;
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
 * RES-C2 · {@code LlmAgentLoop.loop()} 会话生命周期结束 close provider 意图测试
 * （R5-4 另两处 new provider 接注销通道之一）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：loop() 每次调用构造会话级
 * {@code sysPromptCtxProvider}（:2219，register +1 hook）；若 loop 生命周期结束（正常 return /
 * 重入点 return / 异常出口）不 close → 每次会话永久泄漏一个 Runnable。本测试驱动一次完整
 * queryLoop（mock provider 单轮 stop 结束），断言 {@code CACHE_CLEAR_HOOKS} 回到基线。
 * 若删除 loop() 的 finally close（回退到「只构造不注销」），本测试变红。
 *
 * <p>重入点语义：loop() 重入（blockingError → {@code return loop(...)}）会先跑内层再跑外层
 * finally —— 两层各自 close 自身 provider，表不累积（本用例单层单轮已覆盖出口不变量）。
 *
 * <p>隔离：{@code CACHE_CLEAR_HOOKS} 为进程级静态表，本用例只做相对断言（before/after）。
 */
class LlmAgentLoopCloseTest {

    /** 反射读静态表当前大小 · 断言表有界性（SystemPromptInjectionTest 同款观察点）。 */
    private static int tableSize() throws Exception {
        Field field = SystemPromptInjection.class.getDeclaredField("CACHE_CLEAR_HOOKS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Runnable> table = (List<Runnable>) field.get(null);
        return table.size();
    }

    @Test
    @DisplayName("会话 loop 结束 → sysPromptCtxProvider close（CACHE_CLEAR_HOOKS 回到基线）")
    void sessionLoopEnd_closesProvider() throws Exception {
        // ── 1. provider：mock 单轮立即产出 stop 消息（无工具调用 → 单轮自然结束）──
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("你好");
            onMsg.accept(new AssistantMessage("你好", "stop", List.of()));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. state + ctx（per-turn TUC 携带 dummy tool → executor 可构建，完整走一轮）──
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

        int before = tableSize();
        LlmAgentLoop.queryLoop(
            QueryParams.forLoop(
                state.messages(), null, baseTuc,
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new java.util.ArrayList<>());

        assertThat(tableSize())
            .as("loop 结束后 sysPromptCtxProvider 已 close（hook 注销，表回到基线；旧实现此处 +1 泄漏）")
            .isEqualTo(before);
    }
}
