package com.nexusai.application.agent;

import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [ER-IMP-13] +500k 接线测试 · 预算源 = 用户 prompt 解析（parseTokenBudget），非 computeBudgetFromGates。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC query.ts:1312 checkTokenBudget 预算源 =
 * {@code getCurrentTurnTokenBudget()} = 用户 +500k/null（REPL.tsx:2895
 * {@code snapshotOutputTokensForTurn(parseTokenBudget(input) ?? current)}），而非 context-window
 * 恒非 null。Java 若预算源仍用 computeBudgetFromGates，则用户 +500k 输入完全不生效；若 budget=null
 * 时仍 break+MAX_OUTPUT_TOKENS，则无 +500k 时每 turn 停机（R1 伴生缺陷）。两用例分别钉死：
 * <ol>
 *   <li><b>+500k 解析 → 预算源生效</b> — 用户 prompt 含 "+500k" → state.turnTokenBudget()==500_000，
 *       checkTokenBudget 走 continue 注入 nudge（CC 千分位文案），不 MAX_OUTPUT_TOKENS 停机。</li>
 *   <li><b>无 +500k → budget=null → stop(null) 不 break</b> — CC query.ts:1347 stop(null)=正常
 *       completed（预算门控 no-op），LLM 正常被调用产出 assistant 消息。</li>
 * </ol>
 */
class LlmAgentLoopTokenBudgetWireTest {

    /** 主线程（agentId=sessionId）首调用返回 stop 纯文本 → loop 正常 NORMAL 退出。 */
    private LlmProviderFactory factory(LlmProvider provider) {
        LlmProviderFactory f = mock(LlmProviderFactory.class);
        when(f.getProvider(any(), any())).thenReturn(provider);
        return f;
    }

    private LlmProvider stopProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
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
        return provider;
    }

    private LlmAgentLoop loopWithBudgetChecker() {
        LlmAgentLoop loop = new LlmAgentLoop(factory(stopProvider()));
        loop.setTokenBudgetChecker(new TokenBudgetChecker());
        // 预算源已改 state.turnTokenBudget()（用户 +500k），gate 值不再影响 checkTokenBudget；
        // 保留 queryConfig 供 blocking-limit 路径（computeBudgetFromGates :3050）读取。
        loop.setQueryConfig(new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)));
        return loop;
    }

    @Test
    @DisplayName("+500k 解析 → turnTokenBudget=500000 → checkTokenBudget continue 注入 nudge（CC 千分位文案），非 MAX_OUTPUT_TOKENS")
    void userPromptWith500k_parsesBudgetAndContinues() {
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = loopWithBudgetChecker().run(RunRequest.session(
            "please complete this task +500k", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).isNotNull();
        // 1) 预算源接线：userPrompt "+500k" → parseTokenBudget=500_000 → state.turnTokenBudget
        assertThat(state.turnTokenBudget())
            .as("用户 prompt '+500k' 必须解析为预算 500000 写入 AgentState（REPL.tsx:2895 parseTokenBudget(input)）")
            .isEqualTo(500_000);
        // 2) checkTokenBudget 走 continue：nudge（CC utils/tokenBudget.ts:66-73 千分位文案）注入 messages
        assertThat(state.messages().stream().map(m -> m.content()))
            .as("budget=500000 且 turnTokens<90% → continue 注入 nudge（CC query.ts:1330 token_budget_continuation）")
            .anyMatch(c -> c != null && c.contains("Keep working") && c.contains("/ 500,000"));
        // 3) 主线程（agentId=sessionId 归一为 null）不得 MAX_OUTPUT_TOKENS 停机
        assertThat(state.exitReason())
            .as("+500k 走 continue 续跑，不得 MAX_OUTPUT_TOKENS（R1 伴生：预算源换用户 +500k 后 stop 消费对齐 CC）")
            .isNotEqualTo(AgentState.ExitReason.MAX_OUTPUT_TOKENS);
    }

    @Test
    @DisplayName("无 +500k → turnTokenBudget=null → stop(null) 不 break（CC query.ts:1347 正常 completed），LLM 正常被调用")
    void userPromptWithoutBudget_nullBudget_doesNotBreak() {
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = loopWithBudgetChecker().run(RunRequest.session(
            "hello", sessionUuid, null,
            ProviderConfig.empty(), "test-model", null, null));

        assertThat(state).isNotNull();
        // 1) 无 +500k → 预算 null（CC currentTurnTokenBudget 会话首 turn 亦 null）
        assertThat(state.turnTokenBudget())
            .as("无 +500k 输入 → turnTokenBudget 必须为 null（预算=用户 +500k/null 语义）")
            .isNull();
        // 2) budget=null → checkTokenBudget stop(null) → 不 break、不 MAX_OUTPUT_TOKENS → LLM 被调用
        assertThat(state.exitReason())
            .as("budget=null → stop(null) 不得 MAX_OUTPUT_TOKENS 停机（CC query.ts:1312 stop(null)=正常 completed）")
            .isNotEqualTo(AgentState.ExitReason.MAX_OUTPUT_TOKENS);
        assertThat(state.messages().stream().anyMatch(m -> m.role() == Role.assistant))
            .as("stop(null) 不 break → loop 继续 → provider 被真实调用并产出 assistant 消息")
            .isTrue();
    }
}
