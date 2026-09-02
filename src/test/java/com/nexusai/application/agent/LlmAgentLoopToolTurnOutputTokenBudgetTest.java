package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [V-TOK-01 返工] 工具调用回合 output_tokens 计入本轮累计测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 的 {@code getTurnOutputTokens()}
 * （state.ts:726-728）= {@code getTotalOutputTokens() - outputTokensAtTurnStart}，而
 * {@code modelUsage.outputTokens} 在<b>每</b> message_delta 累加（cost-tracker.ts:267
 * {@code modelUsage.outputTokens += usage.output_tokens}，含工具调用回合）。故 CC 的 turnTokens
 * 是「本轮全部模型调用 output_tokens 之和」。
 *
 * <p>Java 旧实现仅在纯文本分支累计（:4261-4269），漏工具调用回合 → turnTokens 低估 → 90% 阈值与
 * diminishing 判定偏移（比 CC 更晚触发 stop，可能超预算续跑）。
 *
 * <p><b>RED tooth</b>: 回退「工具回合累计」后本测试必须 fail —— budget=1000、工具回合 800 tokens +
 * 文本回合 300 tokens（合计 1100 ≥ 90%×1000=900）→ 走 stop(null)；若漏工具回合（仅 300）→
 * 300 &lt; 900 → continue → 注入 nudge → provider 被第 3 次调用。
 */
class LlmAgentLoopToolTurnOutputTokenBudgetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("工具调用回合 output_tokens 计入累计 → turnTokens=800+300=1100 ≥ 90%×1000 → stop(null) 不续跑（CC getTurnOutputTokens 含工具回合）")
    void toolTurnOutputTokensCounted_thenTextTurnReachesBudgetStop() {
        // ── 1. provider：1st 调用工具回合（outputTokens=800），2nd 调用文本回合（outputTokens=300）──
        List<String> calledModels = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            String model = inv.getArgument(1);
            calledModels.add(model);
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int count = callCount.incrementAndGet();
            if (count == 1) {
                // 工具调用回合 · 6-arg 构造器带 outputTokens=800（API usage.output_tokens，CC claude.ts:2214）
                ObjectNode input = JSON.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("checking", "tool_calls",
                    List.of(new ToolUseBlock("toolu_1", "Bash", input)), "", null, 800L));
            } else {
                // 文本回合 · outputTokens=300
                onChunk.accept("done");
                onMsg.accept(new AssistantMessage("done", "stop", List.of(), "", null, 300L));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        // ── 2. state：budgetTracker（checkTokenBudget 前置）+ 预算 1000 ──
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));
        TokenBudgetChecker checker = new TokenBudgetChecker();
        state.setBudgetTracker(checker.createBudgetTracker());
        state.setTurnTokenBudget(1000);

        // ── 3. ctx：注入 tokenBudgetChecker（TestContexts 5/6/7 参重载位置 11 恒 null → 直接构造）──
        QueryConfig qc = new QueryConfig("s", new QueryConfig.Gates(false, false, false, true));
        AgentLoopContext ctx = new AgentLoopContext(
            Mockito.mock(ToolRegistry.class),
            null, null, null, null, null, null, null,
            checker,
            qc, factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };

        // ── 4. 执行 queryLoop ──
        LlmAgentLoop.queryLoop(
            com.nexusai.application.agent.loop.QueryParams.forLoop(
                state.messages(), null,
                com.nexusai.application.agent.tool.ToolUseContext.of(
                    java.util.UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8))
                    .withAvailableTools(java.util.List.of(
                        com.nexusai.application.agent.TestContexts.dummyTool("Bash"))),
                QuerySource.USER, "test-model", null, null, null, null, null,
                deps, ProviderConfig.empty()),
            state, new ArrayList<>());

        // ── 5. 断言 ──
        // 工具回合(800) + 文本回合(300) = 1100 ≥ 900 → stop(null) 不 continue → 不再调模型（2 次结束）
        assertThat(calledModels)
            .as("[V-TOK-01] 工具回合 output_tokens 计入后 turnTokens=1100 ≥ 90%×1000 → stop，无需第 3 次模型调用（漏工具回合时 300<900 → continue → 第 3 次调用）")
            .hasSize(2);
        assertThat(state.messages().stream().map(m -> m.content()))
            .as("[V-TOK-01] 工具回合计入后 budget stop（非 continue）→ 不得注入 nudge（CC query.ts:1316-1340 continue 才有 nudge）")
            .noneMatch(c -> c != null && c.contains("Keep working"));
    }
}
