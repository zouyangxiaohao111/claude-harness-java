package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [G1 主线程可达性] buildBaseToolUseContext 主线程（agentId=null）工具可达性修复全链测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 修复前 buildBaseToolUseContext
 * （LlmAgentLoop）首行守卫 {@code if (state.agentId() == null || state.sessionId() == null) return null}
 * —— 主线程（agentId=null）恒返回 null base TUC → per-turn TUC null → {@code llmToolsArray(null, ...)}
 * 返回 tools=null → 主线程工具的 schema 从不发往 LLM（TodoWrite / Bash 等主线程工具不可达）。
 * 而 CC 主线程 {@code toolUseContext.agentId=undefined}（Tool.ts:245 agentId? optional）仍构造完整
 * 工具上下文（query.ts:342 {@code if (!toolUseContext.agentId)} 仅跳过 headless 埋点），
 * 工具在主线程完整可达。本测试复现『主线程 agentId=null + sessionId 非 null』场景，断言工具 schema
 * 真实发往 LLM（provider.stream 的 tools 参数非 null 且含注册工具）——若守卫仍按 agentId==null 短路
 * 返回 null，tools 参数为 null → 断言失败即 RED。
 */
class LlmAgentLoopMainThreadToolsReachableTest {

    /**
     * 装配真实 loop + 真实 ToolRegistry（注册 "Bash" 使 availableTools 非空）+ mocked provider，
     * 捕获 provider.stream 的 tools 参数（5 参位置 index=4）与 per-turn TUC。
     */
    private static LlmAgentLoop newLoop(LlmProvider provider, ToolRegistry registry,
                                        AtomicReference<ArrayNode> toolsRef,
                                        AtomicReference<ToolUseContext> tucRef) {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        LlmAgentLoop loop = new LlmAgentLoop(factory, null, registry);
        // 镜像 TokenBudgetMainThreadContinuationTest：budget=null → checkTokenBudget stop(null) no-op，
        // 不干扰工具可达性断言（主线程 agentId=null → agentIdStr=null → 无 R-TOK 停机回归）。
        loop.setTokenBudgetChecker(new TokenBudgetChecker());
        loop.setQueryConfig(new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)));
        doAnswer(inv -> {
            // stream(config, modelName, systemPrompt, history, tools, ...) —— tools 为 5 参（index=4）
            toolsRef.set(inv.getArgument(4));
            ToolUseContext tuc = loop.getCurrentToolUseContext();
            if (tuc != null) {
                tucRef.set(tuc);
            }
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("Hello from main thread");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("Hello from main thread", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return loop;
    }

    /**
     * [G1 主线程可达性 · 修复验收] 主线程（agentId=null + sessionId 非 null）工具 schema 必须发往 LLM，
     * 且 base TUC 的 agentId 以 sessionId 兜底（agentId==sessionId → TodoWriteTool.isMainThread 判 true）。
     *
     * <p>修复前：守卫 agentId==null → null base TUC → per-turn TUC null → llmToolsArray null →
     * tools=null → 断言 RED。修复后：effectiveAgentId=sessionId → 完整 base TUC → tools 含 Bash → GREEN。
     */
    @Test
    @DisplayName("[G1] 主线程 agentId=null + sessionId 非 null → 工具 schema 发往 LLM 且 TUC agentId==sessionId")
    void mainThreadAgentIdNull_sessionIdNonNull_toolsReachable() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        LlmProvider provider = mock(LlmProvider.class);
        AtomicReference<ArrayNode> toolsRef = new AtomicReference<>();
        AtomicReference<ToolUseContext> tucRef = new AtomicReference<>();
        LlmAgentLoop loop = newLoop(provider, registry, toolsRef, tucRef);

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = loop.run(RunRequest.session(
            "hello", sessionUuid, null /* 主线程 agentId=null */,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).as("run() 必须返回非 null AgentState").isNotNull();
        assertThat(toolsRef.get())
            .as("[G1] 主线程（agentId=null + sessionId 非 null）工具 schema 必须发往 LLM ——"
                + "修复前 buildBaseToolUseContext agentId==null 直接返回 null → per-turn TUC null"
                + "→ llmToolsArray null → tools=null（主线程工具不可达）")
            .isNotNull();
        assertThat(toolsRef.get().size())
            .as("[G1] 注册的 Bash 工具必须在主线程工具 schema 中可见（对齐 CC 主线程"
                + "toolUseContext.options.tools 完整构造，query.ts:342 agentId=undefined 仅跳过 headless 埋点）")
            .isGreaterThan(0);
        assertThat(tucRef.get())
            .as("[G1] per-turn TUC 必须非 null（base TUC 主线程不再返回 null）").isNotNull();
        assertThat(tucRef.get().agentId())
            .as("[G1] 主线程 base TUC.agentId 必须由 sessionId 兜底（effectiveAgentId）——"
                + "TodoWriteTool.isMainThread 的 ctx.agentId().equals(ctx.sessionId()) 因此判 true，"
                + "对齐 CC TodoWriteTool.ts:80 !context.agentId 主线程语义")
            .isEqualTo(sessionUuid);
        assertThat(tucRef.get().agentId().equals(tucRef.get().sessionId()))
            .as("[G1] 主线程 TUC agentId==sessionId → isMainThread 判 true")
            .isTrue();
    }

    /**
     * [G1 守卫保留 · 无回归] sessionId 亦 null（RunRequest.user REPL / forTest）→ buildBaseToolUseContext
     * 仍返回 null → 无工具 schema 发往 LLM（tools=null）。该路径是 REPL 既有行为，不得因本修复改变。
     */
    @Test
    @DisplayName("[G1] sessionId=null（REPL RunRequest.user）→ 仍无工具发往 LLM（守卫保留，无回归）")
    void repl_sessionIdNull_toolsStillAbsent() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        LlmProvider provider = mock(LlmProvider.class);
        AtomicReference<ArrayNode> toolsRef = new AtomicReference<>();
        AtomicReference<ToolUseContext> tucRef = new AtomicReference<>();
        LlmAgentLoop loop = newLoop(provider, registry, toolsRef, tucRef);

        AgentState state = loop.run(RunRequest.user(
            "hello", ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).as("run() 必须返回非 null AgentState").isNotNull();
        assertThat(toolsRef.get())
            .as("[G1] sessionId=null（REPL RunRequest.user）→ base TUC 仍返回 null"
                + "（ToolUseContext compact ctor 对 null sessionId 抛 IllegalArgumentException，"
                + "守卫必须拦截）→ llmToolsArray null → tools=null，不得有工具 schema 发往 LLM")
            .isNull();
    }

    /**
     * [G1 子 Agent 无回归] agentId!=null（真子 Agent）→ effectiveAgentId 必须保持自身 agentId
     * （非 sessionId 兜底），TUC.agentId != sessionId → TodoWriteTool.isMainThread 判 false（nudge 跳过），
     * 工具 schema 仍完整发往 LLM。
     */
    @Test
    @DisplayName("[G1] 子 Agent agentId!=null → TUC agentId 保持自身（非 sessionId 兜底），工具仍可达")
    void subAgent_agentIdDistinct_toolsReachableAndAgentIdPreserved() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(TestContexts.dummyTool("Bash"));
        LlmProvider provider = mock(LlmProvider.class);
        AtomicReference<ArrayNode> toolsRef = new AtomicReference<>();
        AtomicReference<ToolUseContext> tucRef = new AtomicReference<>();
        LlmAgentLoop loop = newLoop(provider, registry, toolsRef, tucRef);

        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID subAgentUuid = UUID.randomUUID();
        AgentState state = loop.run(RunRequest.session(
            "hello", sessionUuid, subAgentUuid,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).as("run() 必须返回非 null AgentState").isNotNull();
        assertThat(toolsRef.get())
            .as("[G1] 子 Agent 工具 schema 必须照常发往 LLM（修复不影响 agentId!=null 路径）")
            .isNotNull();
        assertThat(tucRef.get().agentId())
            .as("[G1] 子 Agent 的 TUC.agentId 必须保持自身 agentId（effectiveAgentId=agentId，"
                + "不得以 sessionId 兜底覆盖子 Agent 身份）")
            .isEqualTo(subAgentUuid);
        assertThat(tucRef.get().agentId().equals(tucRef.get().sessionId()))
            .as("[G1] 子 Agent agentId != sessionId → isMainThread 判 false（nudge 跳过）")
            .isFalse();
    }
}
