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
 */
@DisplayName("[IMP2-08] subagent autocompact gate 对齐 CC（移除 !isSubagent 排除，DRIFT-8/S-8）")
class SubagentAutoCompactGateCcTest {

    @AfterEach
    void tearDown() {
        PostCompactionState.reset();
    }

    @Test
    @DisplayName("子代理（agentId≠sessionId）超阈 → 自动压缩照常执行（CC 无 agent:* 守卫）")
    void subagentOverLimit_autoCompacts() {
        AgentState state = subagentState();
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();
        AutoCompactor auto = autoCompactor();

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = forLoopParams(ctx, QuerySource.SUBAGENT, state);
        LoopResult result = LlmAgentLoop.queryLoop(params, state, new ArrayList<>(), auto);
        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(state.messages())
            .as("子代理超阈必须触发自动压缩（CC shouldAutoCompact 无 agent:* 守卫；DRIFT-8/S-8）")
            .anyMatch(m -> "compact_boundary".equals(m.subtype()));
    }

    @Test
    @DisplayName("fork 子代理（querySource=agent:builtin:fork）超阈 → 压缩照常（fork 隔离回归）")
    void forkOverLimit_autoCompacts() {
        AgentState state = subagentState();
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();
        AutoCompactor auto = autoCompactor();

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = forLoopParams(ctx, QuerySource.FORK, state);
        LoopResult result = LlmAgentLoop.queryLoop(params, state, new ArrayList<>(), auto);
        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(state.messages())
            .as("fork 子代理超阈必须触发自动压缩（CC runAgent.ts:748 同一 query()，agent:builtin:fork 非守卫源）")
            .anyMatch(m -> "compact_boundary".equals(m.subtype()));
    }

    @Test
    @DisplayName("主线程（agentId==sessionId）超阈 → 压缩照常（既有行为不回归）")
    void mainThreadOverLimit_autoCompacts() {
        String id = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", id, UUID.randomUUID());
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();
        AutoCompactor auto = autoCompactor();

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        LoopResult result = LlmAgentLoop.queryLoop(params, state, new ArrayList<>(), auto);
        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(state.messages())
            .as("主线程超阈必须触发自动压缩（既有行为保持）")
            .anyMatch(m -> "compact_boundary".equals(m.subtype()));
    }

    @Test
    @DisplayName("递归守卫: querySource=compact 超阈也不压缩（gate 移除后死锁防护仍在，S-3）")
    void compactQuerySource_neverCompacts() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        appendLargeMessages(state, 50);
        LlmProviderFactory factory = completingProviderFactory();
        AutoCompactor auto = autoCompactor();

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = forLoopParams(ctx, QuerySource.COMPACT, state);
        assertThat(state.messages())
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
            (p, m) -> new CompactConversation.SummaryResult(SUMMARY_MARK + "compact</summary>", null));
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
            state.messages(), null,
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
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
