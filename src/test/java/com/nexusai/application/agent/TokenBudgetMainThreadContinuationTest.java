package com.nexusai.application.agent;

import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
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
 * [ER-IMP-02 · R-TOK] 主线程 token-budget 停机修复 run()->loop() 全链测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: R-TOK 根因是主线程 token-budget 停机--
 * ChatService 主线程恒传 agentId=sessionUuid，checkTokenBudget 首行
 * {@code if (agentId || budget === null || budget <= 0) return stop}（CC tokenBudget.ts:51）
 * 命中 agentId 非空 -> 主线程首迭代恒 StopDecision -> LlmAgentLoop:2690
 * {@code exitReason = MAX_OUTPUT_TOKENS} 直接 break，一次 LLM 调用都没发。而 CC 主线程
 * {@code toolUseContext.agentId} 是 undefined（query.ts:1311），走续跑逻辑
 * （tokenBudget.ts:59-92：{@code !diminishing && turnTokens < 90% budget -> continue}）。
 *
 * <p>本测试以 {@code RunRequest.session(prompt, sessionUuid, sessionUuid, ...)} 复现
 * 『主线程 agentId 非空』场景（回归保护：即使生产入口改传 null，直接以 agentId=sessionUuid
 * 驱动 loop 的主线程调用方也必须被正确判定为主线程）。断言首迭代**不应**
 * {@code MAX_OUTPUT_TOKENS} break -- 若停机缺陷回归（agentId 非空恒 stop），
 * exitReason==MAX_OUTPUT_TOKENS 且无 assistant 消息 -> 断言失败即 RED。
 */
class TokenBudgetMainThreadContinuationTest {

    /**
     * RED-tooth: 主线程（agentId=sessionUuid）run()->loop() 首迭代不应 MAX_OUTPUT_TOKENS break。
     *
     * <p>真实 LlmAgentLoop + 真实 TokenBudgetChecker + mocked provider。prompt="hello" 无 +500k
     * -> turnTokenBudget=null -> checkTokenBudget 返回 StopDecision(null) = no-op（CC
     * tokenBudget.ts:51 {@code if (budget === null) return stop}，completionEvent=null）。
     * 若主线程被误判为『非空 agentId -> stop』，loop 在首个 checkTokenBudget 即 break，
     * exitReason=MAX_OUTPUT_TOKENS、无 assistant 消息 -> RED。修复后 agentId==sessionId
     * 判定为主线程 -> checkTokenBudget 传 agentId=null + budget=null -> stop(null) no-op
     * -> 不 break -> provider 返回 stop -> 正常 NORMAL 退出。
     *
     * <p><b>不走 continue 路径</b>（budget=null -> stop(null) no-op）。continue 路径由
     * {@link LlmAgentLoopTokenBudgetWireTest#userPromptWith500k_parsesBudgetAndContinues}
     * 以 "+500k" prompt 真实覆盖。本测试聚焦 R-TOK 停机根因：agentId==sessionId 必须归一为
     * 主线程（agentIdStr=null），而非被误判为子代理导致首迭代恒 StopDecision break。
     */
    @Test
    @DisplayName("主线程 agentId=sessionUuid 首迭代不应 MAX_OUTPUT_TOKENS break（R-TOK 停机修复）")
    void mainThreadAgentIdEqualsSessionId_shouldNotBreakOnMaxOutputTokens() {
        // ── 1. provider：首调返回 stop 纯文本 -> loop 正常退出 ──
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
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. 真实 LlmAgentLoop + 真实 TokenBudgetChecker（budget=null -> stop(null) no-op）──
        LlmAgentLoop loop = new LlmAgentLoop(factory);
        loop.setTokenBudgetChecker(new TokenBudgetChecker());
        loop.setQueryConfig(new QueryConfig("s", new QueryConfig.Gates(false, false, false, true)));

        // ── 3. 复现『主线程 agentId 非空』：agentId=sessionId（主线程惯例，CC agentId undefined）──
        // prompt="hello" 无 +500k -> parsedBudget=null -> turnTokenBudget=null
        // -> checkTokenBudget(agentId=null, budget=null) -> StopDecision(null) no-op（不 break）
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        AgentState state = loop.run(RunRequest.session(
            "hello", sessionUuid, UUID.randomUUID(), ProviderConfig.empty(), "test-model", null, null));

        // ── 4. 断言 ──
        assertThat(state).as("run() 必须返回非 null AgentState").isNotNull();
        assertThat(state.exitReason())
            .as("主线程首迭代不得因 token budget 停机（R-TOK 修复：agentId==sessionId 应归一主线程 -> stop(null) no-op -> NORMAL）")
            .isNotEqualTo(AgentState.ExitReason.MAX_OUTPUT_TOKENS);
        assertThat(state.turnCount())
            .as("主线程必须至少跑满 1 轮 LLM 调用（恒 stop 首迭代 break 时无任何调用）")
            .isGreaterThanOrEqualTo(1);
        assertThat(state.messages().stream().anyMatch(m -> m.role() == com.nexusai.model.session.dto.Role.assistant))
            .as("loop 必须真实产出 assistant 消息（停机缺陷时 provider 从未被调用 -> 无 assistant）")
            .isTrue();
    }
}
