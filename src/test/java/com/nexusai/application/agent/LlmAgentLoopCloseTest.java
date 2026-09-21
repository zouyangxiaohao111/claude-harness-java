package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.prompt.SessionPromptCacheRegistry;
import com.nexusai.application.agent.prompt.SessionPromptCacheStore;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
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
 * <b>步骤 2</b> · 会话级 {@link SystemPromptContextProvider} 生命周期意图测试
 * （改造自 RES-C2 R5-4 的「每 run close」用例 —— 该不变量已被本步<b>有意反转</b>）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：CC 的 {@code getSystemContext}/{@code getUserContext} 是
 * <b>进程级 lodash memoize</b>（context.ts:116/:155），一进程一会话 ⇒ 一次会话内只算一次、
 * 直到 /clear 才清。本仓把「进程级」映射为「会话级」（按 sessionId 分区），落点 =
 * {@link SessionPromptCacheStore} 持有 provider 实例 ⇒ <b>同一会话的相邻 run 复用同一 provider</b>，
 * 其 memoize（userContext / systemContext / gitStatus）+ 会话冻结日期一并跨 run 存在。
 *
 * <p>⇒ 旧用例「collectRunMaterial 结束必须 close（CACHE_CLEAR_HOOKS 回到基线）」编码的正是
 * <b>要修掉的 bug 形态</b>（每 run 重建 provider ⇒ memoize 归零 ⇒ 头部字节每 run 漂移 ⇒
 * DeepSeek 前缀缓存命中塌）。本用例改为钉死新不变量：
 * <ol>
 *   <li>run 1：会话级 provider 首建 → {@code CACHE_CLEAR_HOOKS} +1；</li>
 *   <li>run 2（同 sessionId、新 AgentState —— 生产每 run 新建 AgentState）：<b>仍为 +1</b>
 *       （复用同一 provider，未重建）；</li>
 *   <li>会话终结 {@link SessionPromptCacheRegistry#evict(String)} → provider close 注销回调
 *       → 回到基线（register/unregister 仍成对，只是归属从「每 run」改为「每会话」）。</li>
 * </ol>
 *
 * <p>隔离：{@code CACHE_CLEAR_HOOKS} 为进程级静态表，本用例只做相对断言（before/after），
 * 并在结尾 evict 掉本用例造成的条目。
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

    /** 走一轮真实 run（collectRunMaterial + queryLoop），provider 单轮立即 stop 无工具调用。 */
    private static void executeRun(String sessionId) {
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
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. state + ctx（per-turn TUC 携带 dummy tool → executor 可构建，完整走一轮）──
        AgentState state = new AgentState("sys", sessionId, null);
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
        ToolUseContext baseTuc = ToolUseContext.of(UUID.randomUUID(), sessionId)
            .withAvailableTools(List.of(TestContexts.dummyTool("Bash")));

        QueryParams callerParams0 = QueryParams.forLoop(
                state.rawMessages(), null, baseTuc,
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(callerParams0.deps().context(), callerParams0, state),
            state, new java.util.ArrayList<>());
    }

    @Test
    @DisplayName("步骤 2：provider 会话级 —— 相邻两 run 复用同一实例（hook 不注销不重建）；仅会话终结 evict 才回到基线")
    void sessionScopedProvider_survivesAcrossRuns_closedOnSessionEnd() throws Exception {
        String sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        int before;
        try {
            before = tableSize();

            // ── run 1：会话级 provider 首建（构造即注册缓存清理回调 +1 hook）──
            executeRun(sessionId);
            assertThat(tableSize())
                .as("run 1：会话级 provider 首建 ⇒ CACHE_CLEAR_HOOKS +1（旧实现此处亦 +1，但随后被 finally close 掉）")
                .isEqualTo(before + 1);

            // ── run 2：同 sessionId、新 AgentState（生产每 run 新建）⇒ 复用同一 provider ──
            executeRun(sessionId);
            assertThat(tableSize())
                .as("run 2：复用同一会话级 provider ⇒ 不再 +1（旧实现每 run new+close ⇒ memoize 归零 = 本批修的 bug）")
                .isEqualTo(before + 1);

            // ── 会话内跨 run 的同一性（store 层证据）──
            AgentState probe = new AgentState("sys", sessionId, null);
            SessionPromptCacheStore store = probe.promptCacheStore();
            assertThat(store.hasContextProvider())
                .as("同会话 store 仍持有 run 1 建的 provider（跨 run 不销毁）")
                .isTrue();
        } finally {
            // 会话终结 → evict（生产接线点 = SessionService#delete）
            SessionPromptCacheRegistry.evict(sessionId);
        }

        assertThat(tableSize())
            .as("会话终结 evict → provider close 注销回调 → 回到基线（register/unregister 仍成对，归属从每 run 改为每会话）")
            .isEqualTo(before);
    }
}
