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
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),any());
        return loop;
    }

    /**
     * [G1 主线程可达性 · 修复验收] 主线程（agentId=null + sessionId 非 null）工具 schema 必须发往 LLM，
     * 且 base TUC 的 agentId <b>保持 null</b>（= 生产的主线程判据 {@code !context.agentId}）。
     *
     * <p>修复前：守卫 agentId==null → null base TUC → per-turn TUC null → llmToolsArray null →
     * tools=null → 断言 RED。修复后：守卫只看 sessionId → 完整 base TUC → tools 含 Bash → GREEN。
     *
     * <p><b>⚠️ [F-19 裁决 · 2026-09-14] 本用例的 agentId 期望曾被写错（已改）</b>：原文断言
     * {@code tucRef.get().agentId()).isEqualTo(sessionUuid)} + {@code agentId.equals(sessionId) isTrue()}
     * —— 那正是<b>生产已删除的「恒 false 死分支」</b>（{@code UUID.equals(String)} 结构上恒 false，
     * 该断言永远不可能成立）。生产侧主线程判据是 <b>{@code agentId == null}</b>，与 CC
     * {@code !context.agentId} 同义，见 {@code TodoWriteTool.java:1232-1234} 与
     * {@code TaskUpdateTool.java:927-931}（后者注释逐字记录了「原 agentId.equals(sessionId) UUID/String
     * 恒 false 死分支，会把主线程误判为子 Agent」）。若按旧断言的方向（agentId==sessionId）成立，
     * {@code !context.agentId} 会变 <b>false</b> ⇒ <b>把主线程误判为子 Agent</b> —— 与本用例名
     * （{@code mainThreadAgentIdNull}）及 CC 语义都相反。
     *
     * <p><b>RED 条件（两个变异，实测各一条）</b>
     * <ol>
     *   <li>把 {@code buildBaseToolUseContext} 里 TUC 首参改回「主线程时以 sessionId 派生 UUID
     *       （复活 effectiveAgentId 兜底）」⇒ 下面的 {@code isNull()} 断言<b>实测变红</b>
     *       （assertj 首个失败即中止，故同一执行里反射 {@code isMainThread} 断言不被求值）。</li>
     *   <li>把 {@code TodoWriteTool.isMainThread} 判据改回死分支
     *       {@code ctx.agentId().equals(ctx.sessionId())} ⇒ 反射正向断言<b>实测变红</b>
     *       （经 {@code InvocationTargetException}，因死分支对 null agentId 自身即 NPE）——
     *       这一条单独证明「正向断言」有独立鉴别力，不是 {@code isNull()} 的复述。</li>
     * </ol>
     */
    @Test
    @DisplayName("[G1] 主线程 agentId=null + sessionId 非 null → 工具 schema 发往 LLM 且 TUC.agentId 保持 null")
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
            .as("[F-19 裁决] 主线程 base TUC.agentId 必须<b>保持 null</b> —— 主线程判据是"
                + " agentId==null（对齐 CC !context.agentId；effectiveAgentId 的 sessionId 兜底已删）。"
                + "⛔ 旧断言 isEqualTo(sessionUuid) 是生产已删除的死分支（UUID.equals(String) 恒 false）")
            .isNull();
        assertThat(isMainThreadByProductionPredicate(tucRef.get()))
            .as("[F-19 裁决 · 正向断言] 主线程 TUC 必须被生产判据（TodoWriteTool.isMainThread，"
                + "实现 = ctx.agentId() == null）判定为<b>主线程</b>。⛔ 若把判据退回"
                + " ctx.agentId().equals(ctx.sessionId())（UUID/String 恒 false）⇒ 本断言红"
                + "（会把主线程误判成子 Agent，verification nudge 被错误跳过）")
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
     * [G1 子 Agent 无回归] agentId!=null（真子 Agent）→ TUC.agentId 必须保持自身 agentId
     * （非 sessionId 兜底），主线程判据 {@code !context.agentId} 因此为 false（nudge 跳过），
     * 工具 schema 仍完整发往 LLM。
     */
    @Test
    @DisplayName("[G1] 子 Agent agentId!=null → TUC agentId 保持自身，主线程判据为 false，工具仍可达")
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
            .as("[G1] 子 Agent 的 TUC.agentId 必须保持自身 agentId（不得以 sessionId 兜底覆盖子 Agent 身份）")
            .isEqualTo(subAgentUuid);
        assertThat(isMainThreadByProductionPredicate(tucRef.get()))
            .as("[F-19 裁决] 子 Agent（agentId != null）必须被生产判据判定为<b>非</b>主线程"
                + "（nudge 跳过）。⛔ 旧断言 agentId().equals(sessionId()).isFalse() 是恒真死断言"
                + "（UUID 与 String 类型不同，equals 结构上恒 false）⇒ 零鉴别力，已换为真实判据")
            .isFalse();
    }

    /**
     * [F-19 裁决] 反射调用<b>生产</b>主线程判据 {@code TodoWriteTool.isMainThread(ToolUseContext)}。
     *
     * <p><b>WHY 不用 {@code agentId == null} 直接重写一遍</b>：那样只是把测试的期望抄成断言，
     * 判据一旦被改回 {@code agentId.equals(sessionId)}（恒 false 死分支），测试仍绿。反射真实谓词
     * 才让「主线程 ⇒ isMainThread 判 true」这句话<b>可被证伪</b>。
     *
     * <p>生产实现 = {@code TodoWriteTool.java:1232-1234}（{@code ctx.agentId() == null}，对齐 CC
     * {@code !context.agentId}）；{@code TaskUpdateTool.java:927-931} 是同义的第二处。
     */
    private static boolean isMainThreadByProductionPredicate(ToolUseContext tuc) {
        try {
            java.lang.reflect.Method m = com.nexusai.application.agent.tool.impl.TodoWriteTool.class
                .getDeclaredMethod("isMainThread", ToolUseContext.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(
                com.nexusai.application.agent.tool.impl.TodoWriteTool.class.getDeclaredConstructor().newInstance(),
                tuc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("反射调用 TodoWriteTool.isMainThread 失败（判据被改名/改签名？）", e);
        }
    }
}
