package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * IMP2-08 · subagent autocompact gate 裁决落地（DRIFT-8/S-8，簇E）。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * <ol>
 *   <li><b>裁决方向（09 §7-17 默认建议对齐 CC；OD-20 ADJUDICATED）</b> — CC
 *       {@code shouldAutoCompact}（autoCompact.ts:160-239）<b>无 agent:* 守卫</b>，仅递归守卫
 *       （session_memory/compact/marble_origami）+ feature 门 + 阈值；子代理（runAgent.ts:748
 *       {@code query()}，querySource {@code agent:builtin:fork} 等）走同一 query() 照常压缩。
 *       Java 端 {@code !isSubagent}（LlmAgentLoop:2924）全禁子代理 → 移除对齐。</li>
 *   <li><b>递归防护不回归（S-3 风险注记）</b> — gate 移除后，compact/SM fork 的递归死锁防护
 *       依赖 AutoCompactor 递归守卫（canonical 归一已由 IMP2-01 落地）——本测试固化
 *       {@code querySource='compact'} 永不压缩。</li>
 *   <li><b>fork 隔离回归</b> — 子代理（agentId≠sessionId）压缩不破坏主线程行为；主线程
 *       （agentId==sessionId）压缩行为不变。</li>
 * </ol>
 *
 * <p><b>⚠️ [G4 消歧 · 必读] 本类是「部件测试」，⛔ 它不覆盖生产接线</b>：
 * <ol>
 *   <li><b>压缩器是**当形参注入**的</b>：下面全部用例调
 *       {@code queryLoop(params, state, uuids, AutoCompactor)} —— 即 <b>4 参 {@code AutoCompactor} 重载</b>
 *       （测试专用通道）。本类因此只证明「**若给**实例，{@code shouldAutoCompact} 内无 agent 守卫」
 *       —— 这是一个**正确且有价值的部件结论**（CC {@code autoCompact.ts:160-239} 确无 agent:* 守卫）。</li>
 *   <li><b>生产子代理走的是另一条重载</b>：{@code SubagentExecutor:4676} 调
 *       <b>4 参 {@code boolean} 重载</b>（{@code queryLoop(params, state, uuids, skillListingResume)}），
 *       fork/hook 走 <b>3 参重载</b>；这两条重载的形参 {@code autoCompactor} 恒为 {@code null}，
 *       实例只能经 {@code AgentLoopContext} 翻包拿到（G1 的派生行）。</li>
 *   <li>⇒ <b>「生产子代理到底会不会压缩 / 会不会被 blocking 预检硬退」不由本类守护</b>，而由：
 *       <ul>
 *         <li>{@link SubagentAutoCompactWiringG1Test} —— 走生产 4 参 {@code boolean} 重载，
 *             断言压缩器**只从 ctx 分量**下发时子代理超阈**真的发生压缩**（{@code compact_boundary}）；</li>
 *         <li>{@link SubagentBlockingLimitShieldG4Test} —— 断言子代理超阈**不发生
 *             {@code BLOCKING_LIMIT} 硬退**（{@code rcOwnsBlocking} 恒真 = 等价 CC 全局判据）。</li>
 *       </ul>
 *   </li>
 *   <li><b>本类的真实教训</b>：曾被读成「本仓子代理会压缩」并写进
 *       {@code docs/zjkycode/specs/2026-09-14-subagent-compaction-verify-brief.md}，
 *       而当时生产**不会** —— 本仓铁律「<b>seam 层有守护 ≠ 接线被守护</b>」的实例。</li>
 * </ol>
 */
@DisplayName("[IMP2-08] 【部件测试 · 不覆盖生产接线】subagent autocompact gate 对齐 CC：压缩器由形参注入，仅证 shouldAutoCompact 内无 agent 守卫（移除 !isSubagent 排除，DRIFT-8/S-8；生产路径见 SubagentAutoCompactWiringG1Test / SubagentBlockingLimitShieldG4Test）")
class SubagentAutoCompactGateCcTest {

    @AfterEach
    void tearDown() {
        PostCompactionState.reset();
    }

    @Test
    @DisplayName("子代理（agentId≠sessionId）超阈 → 自动压缩照常执行（CC 无 agent:* 守卫）【部件级：压缩器为形参注入 · ⛔ 非生产路径，见类 javadoc】")
    void subagentOverLimit_autoCompacts() {
        AgentState state = subagentState();
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();
        AutoCompactor auto = autoCompactor();

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = forLoopParams(ctx, QuerySource.SUBAGENT, state);
        LoopResult result = LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>(), auto);
        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(state.rawMessages())
            .as("子代理超阈必须触发自动压缩（CC shouldAutoCompact 无 agent:* 守卫；DRIFT-8/S-8）")
            .anyMatch(m -> "compact_boundary".equals(m.subtype()));
    }

    @Test
    @DisplayName("fork 子代理（querySource=agent:builtin:fork）超阈 → 压缩照常（fork 隔离回归）【部件级：压缩器为形参注入 · ⛔ 非生产路径，见类 javadoc】")
    void forkOverLimit_autoCompacts() {
        AgentState state = subagentState();
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();
        AutoCompactor auto = autoCompactor();

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = forLoopParams(ctx, QuerySource.FORK, state);
        LoopResult result = LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>(), auto);
        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(state.rawMessages())
            .as("fork 子代理超阈必须触发自动压缩（CC runAgent.ts:748 同一 query()，agent:builtin:fork 非守卫源）")
            .anyMatch(m -> "compact_boundary".equals(m.subtype()));
    }

    @Test
    @DisplayName("主线程（agentId==sessionId）超阈 → 压缩照常（既有行为不回归）【部件级：压缩器为形参注入 · ⛔ 非生产路径，见类 javadoc】")
    void mainThreadOverLimit_autoCompacts() {
        String id = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", id, UUID.randomUUID());
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();
        AutoCompactor auto = autoCompactor();

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        LoopResult result = LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>(), auto);
        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(state.rawMessages())
            .as("主线程超阈必须触发自动压缩（既有行为保持）")
            .anyMatch(m -> "compact_boundary".equals(m.subtype()));
    }

    @Test
    @DisplayName("递归守卫: querySource=compact 超阈也不压缩（gate 移除后死锁防护仍在，S-3）【部件级：压缩器为形参注入 · ⛔ 非生产路径，见类 javadoc】")
    void compactQuerySource_neverCompacts() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();
        AutoCompactor auto = autoCompactor();

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = forLoopParams(ctx, QuerySource.COMPACT, state);
        assertThat(state.rawMessages())
            .as("querySource=compact（压缩 fork）必须被递归守卫拦截，永不自动压缩（autoCompact.ts:171-173；S-3 风险注记）")
            .noneMatch(m -> "compact_boundary".equals(m.subtype()));
    }

    // ─────────────────────── helpers ───────────────────────

    private static final String SUMMARY_MARK = "<summary>";

    /** 子代理状态：agentId ≠ sessionId（触发 Java 旧 !isSubagent 判定的场景）。 */
    private static AgentState subagentState() {
        String session = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID agent = UUID.randomUUID();
        return new AgentState("sys", session, agent);
    }

    /** 超阈 autoCompactor：tokenCounter 恒 200_000，默认窗口 → shouldAutoCompact=true（非守卫源）。 */
    private static AutoCompactor autoCompactor() {
        AutoCompactor auto = new AutoCompactor(msgs -> 200_000,
            (p, m, ctx) -> new CompactConversation.SummaryResult(SUMMARY_MARK + "compact</summary>", null));
        return auto;
    }

    private static void appendLargeMessages(AgentState state, int count) {
        for (int i = 0; i < count; i++) {
            state.appendMessage(singleMessage("u" + i, "hi"));
        }
    }

    private static QueryParams forLoopParams(AgentLoopContext ctx, QuerySource source, AgentState state) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
            source, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
    }

    private static ChatMessageDto singleMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** provider 正常完成（onChunk/onMsg/onComplete）· blocking 不拦截时 loop 可快速完成。 */
    private static LlmProviderFactory completingProviderFactory() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain text reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain text reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
