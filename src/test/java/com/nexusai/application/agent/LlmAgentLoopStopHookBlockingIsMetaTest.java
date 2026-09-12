package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tool.AbortController;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [P2-14 2026-09-12] Stop hook 回注（blockingError → user message）必须带 {@code isMeta=true}。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：CC 真源
 * {@code stopHooks.ts:267-271}：
 * <pre>
 *   const userMessage = createUserMessage({
 *     content: getStopHookMessage(result.blockingError),
 *     isMeta: true, // Hide from UI (shown in summary message instead)
 *   })
 * </pre>
 * Java 旧实现走 {@code toMessage(Role.user, blockingText, null)}（3 参重载）→ {@code isMeta} 取默认
 * <b>false</b> → 该行经 {@code appendListener} <b>实时落库</b>：前端把它渲染成<b>用户自己的气泡</b>
 * （刷新后仍在），并污染 {@code lastUserMessageId} 归属。模型面不受影响（isMeta 与
 * isVisibleInTranscriptOnly 一样<b>绝不</b>参与模型请求裁剪）。
 *
 * <p><b>RED 条件</b>：把两处回注点（in-loop / §14）改回 3 参 {@code toMessage(Role.user, blockingText, null)}
 * → 断言的 {@code isMeta()} 为 false → 红。
 */
@DisplayName("[P2-14] Stop hook blockingError 回注 user 消息带 isMeta=true（CC stopHooks.ts:267-271）")
class LlmAgentLoopStopHookBlockingIsMetaTest {

    private static final String BLOCKING_TEXT = "检查未通过，请修复后继续";

    @Test
    @DisplayName("in-loop Stop hook 返回 blockingError → 注入 state.rawMessages() 的 user 消息 isMeta=true")
    void inLoopBlockingError_injectsIsMetaUserMessage() {
        // ── provider：单文本帧 → 无 tool_calls → 进入 stop hooks 分支 ──
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("first");
            onMsg.accept(new AssistantMessage("first", "stop", List.of(), "", null, 100L));
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── hookRegistry：Stop 收集返回带 blockingError 的结果 → 触发回注 + 重入 ──
        HookRegistry hookRegistry = Mockito.mock(HookRegistry.class);
        when(hookRegistry.executeStopHooksCollecting(any(), any(), any()))
            .thenReturn(new HookRegistry.StopHookCollectResult(
                List.of(GenericHook.HookResult.stop("blocked", BLOCKING_TEXT)),
                1, List.of("check.sh"), List.of(BLOCKING_TEXT), false, null, true));

        AgentState state = new AgentState("sys", "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        TokenBudgetChecker checker = new TokenBudgetChecker();
        state.setBudgetTracker(checker.createBudgetTracker());

        AgentLoopContext ctx = new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            hookRegistry, null, null, null, null, null, null,
            checker,
            new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)),
            factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };

        // ── 执行（blocking → 重入；重入上限安全阀终止，全程不抛）──
        assertThatCode(() -> LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.rawMessages(), null,
                tucWithNotification(null).withAvailableTools(List.of(
                    TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>()))
            .as("[P2-14] Stop hook blocking 重入不应抛穿 queryLoop（安全阀终止）")
            .doesNotThrowAnyException();

        // ── 断言：回注的 user 消息存在且 isMeta=true ──
        List<ChatMessageDto> injected = state.rawMessages().stream()
            .filter(m -> Role.user.equals(m.role()) && m.content() != null
                && m.content().contains(BLOCKING_TEXT))
            .toList();
        assertThat(injected)
            .as("Stop hook blockingError 必须回注进对话历史（CC query.ts:1274-1277 全部 append）")
            .isNotEmpty();
        assertThat(injected).allSatisfy(m -> assertThat(m.isMeta())
            .as("回注内容必须 isMeta=true —— 否则经 appendListener 实时落库后被前端渲染成用户气泡，"
                + "并污染 lastUserMessageId（CC stopHooks.ts:270 'Hide from UI'）")
            .isTrue());
    }

    /** 32 参构造器构建 TUC（of() 工厂不含 UI 回调）。 */
    private static ToolUseContext tucWithNotification(Consumer<com.nexusai.application.agent.tool.Notification> addNotification) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, null, null, null, Map.of(), p -> {},
            null, null, null, null,
            addNotification, null, null, null, null, null, null, null, null, null);
    }
}
