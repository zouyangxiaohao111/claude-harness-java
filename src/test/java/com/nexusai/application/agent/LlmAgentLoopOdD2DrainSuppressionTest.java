package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.recovery.MaxTokensHandler;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
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
 * [OD-D2] 恢复轮不 drain 排队、下一真工具轮才 drain 的聚焦测试。
 *
 * <p><b>WHY（规则九 · 意图）</b>: OD-D2 对齐 CC query.ts —— drain（:1547）只在<b>工具结果路径尾</b>；
 * 恢复类 continue（fallback/budget/max_tokens 截断重入）全部在工具前 return/continue 回循环顶
 * <b>绕过 :1547</b> → 恢复轮不 drain 排队命令。Java 旧实现用 needsFollowUp 门控循环顶 drain，恢复路径
 * markNeedsFollowUp → 恢复轮仍 drain（偏差）。OD-D2 改看 prevIterationRanTools（仅真工具轮置位）。
 *
 * <p><b>可观测口径（drain 注入晚一拍）</b>: 循环顶 drain（:4574）位于 messagesForQuery 快照（:4483）
 * <b>之后</b> → 被 drain 的 busy-queued 只进 state.messages，<b>不进本轮</b>模型请求（下一轮才可见）。
 * 因此本测试断言<b>每次模型调用时队列的 size</b>：
 * <ul>
 *   <li><b>call1（恢复 continue 后的下一模型调用）</b>：OD-D2 下恢复轮不 drain → busy-queued <b>仍在队列</b>
 *       （queue.size()==1）。旧实现（看 needsFollowUp）恢复轮已 drain → 队列空 → 断言变红。</li>
 *   <li><b>call2（真工具轮后的下一模型调用）</b>：真工具轮置 lastIterationRanTools → 循环顶 drain 消费
 *       busy-queued → 队列空（queue.size()==0）且 state.messages 已含该消息 → 锚定「下一真工具轮才
 *       drain」红线保留。</li>
 * </ul>
 *
 * <p><b>RED tooth</b>: 回退 OD-D2（循环顶 drain 改回看 needsFollowUp）后，恢复 continue 的下一轮
 * （call1 时点）队列已被 drain 清空 → 断言 queue.size()==1 变红。
 */
@DisplayName("[OD-D2] 恢复轮不 drain 排队、下一真工具轮才 drain")
class LlmAgentLoopOdD2DrainSuppressionTest {

    private static final String BUSY_TEXT = "用户忙时追问-od-d2";

    private static final String RESUME_TEXT =
        "Output token limit hit. Resume directly — no apology, no recap of what you were doing. Pick up mid-thought if that is where the cut happened. Break remaining work into smaller pieces.";

    /** 构造含真实 NotificationQueue + MaxTokensHandler(false) 的 AgentLoopContext（maxTokensHandler 位置 13）。 */
    private AgentLoopContext ctxWithQueue(LlmProviderFactory factory, MaxTokensHandler handler,
                                          NotificationQueue queue) {
        FeatureFlags flags = new FeatureFlags(false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false, false, false, false, false);
        return new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class), // 1 toolRegistry
            null, null, queue, null,                              // 2 hookRegistry · 3 mcpServerService · 4 notificationQueue · 5 commandLifecycleNotifier
            null, null, null, null,                               // 6 skillCatalog · 7 memoryPrefetcher · 8 memoryStorage · 9 tokenBudgetChecker
            null, factory, null, handler, null, null,             // 10 queryConfig · 11 llmProviderFactory · 12 transientErrorHandler · 13 maxTokensHandler · 14-15
            null, null, null, null,                               // 16-19
            flags, null, null, null, null, null,                  // 20 featureFlags · 21-25
            null, null, null, null, null, null, null);            // 26-32
    }

    private AgentState initialState(String sid) {
        AgentState state = new AgentState("sys", sid, null);
        state.appendMessage(new ChatMessageDto(
            "m0", "s", Role.user, "user",
            "hello", null, List.of(), null, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of()));
        return state;
    }

    private MaxTokensHandler maxTokensHandler(boolean gate) {
        MaxTokensHandler handler = new MaxTokensHandler(gate);
        handler.setSettingsOverrideProvider(() -> null); // 无 settings.maxOutputTokens override
        return handler;
    }

    /**
     * provider 序列：
     * <ol>
     *   <li>call 0（callCount=0）：先向队列 enqueue busy-queued（此时 turn-0 drain 已过），再返回
     *       max_tokens 截断消息 → gate off 走多轮恢复 continue（恢复轮，不跑工具）。</li>
     *   <li>call 1（callCount=1）：返回 Bash 工具调用 → 真工具轮（置 lastIterationRanTools）。</li>
     *   <li>call 2（callCount=2）：返回 end_turn 纯文本 → 正常退出。</li>
     * </ol>
     * 每次 provider 调用记录当时队列 size（queueAtCall）：OD-D2 下 call1 时 busy 仍在队列
     * （恢复轮未 drain），call2 时已空（真工具轮后 drain）；据此断言 drain 时机。
     */
    @Test
    @DisplayName("max_tokens 恢复轮不 drain 排队；下一真工具轮才 drain（call1 队列仍有 busy，call2 已 drain）")
    void recoveryTurn_noDrain_thenToolTurn_drains() {
        String sid = "sess-od2-" + UUID.randomUUID().toString().substring(0, 6);
        AgentState state = initialState(sid);
        NotificationQueue queue = new NotificationQueue();
        AtomicInteger callCount = new AtomicInteger(0);
        List<Integer> queueAtCall = new ArrayList<>();

        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            queueAtCall.add(queue.size());   // 记录本次模型调用时点的队列状态
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int call = callCount.getAndIncrement();
            if (call == 0) {
                // 模拟 mid-turn 用户忙时排队：turn-0 drain 已过（本轮稍后才入队）→ 排在恢复轮 / 工具轮
                queue.enqueue(new NotificationQueue.QueueItem(
                    BUSY_TEXT, NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
                    null, "msg-od2-busy", false, "busy-queued", false, null, sid));
                onChunk.accept("truncated partial");
                // gate off 多轮恢复触发信号 = apiError max_output_tokens（对齐 MaxOutputTokensEscalationIntegrationTest）
                onMsg.accept(new AssistantMessage("truncated partial", "max_tokens", List.of(), null, "max_output_tokens"));
            } else if (call == 1) {
                com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode input = json.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("need tool", "tool_calls",
                    List.of(new ToolUseBlock("toolu_od2", "Bash", input)), null, null));
            } else {
                onChunk.accept("final answer");
                onMsg.accept(new AssistantMessage("final answer", "end_turn", List.of(), null, null));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentLoopContext ctx = ctxWithQueue(factory, maxTokensHandler(false), queue);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        QueryParams params = QueryParams.forLoop(
            state.messages(), null,
            ToolUseContext.of(UUID.randomUUID(), sid)
                .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, "test-model", 8, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());

        // ── 断言 ──
        // ① 确有 3 次模型调用（截断恢复 → 真工具轮 → 收尾文本）
        assertThat(callCount.get()).as("截断恢复→工具轮→收尾 = 3 次 LLM 调用").isEqualTo(3);
        // ② 场景完整性：恢复消息确实出现过（证明走的是截断恢复路径，非纯工具链误配）
        assertThat(state.messages().stream().map(ChatMessageDto::content))
            .as("截断恢复消息已追加（证明 max_tokens 恢复路径真实发生）")
            .contains(RESUME_TEXT);
        // ③ OD-D2 核心：恢复 continue 后的下一模型调用（call1）时，busy-queued 必须仍在队列
        //   （恢复轮不 drain）。queueAtCall[1]==0（旧实现 needsFollowUp 门控 → 恢复轮已 drain）→ 变红。
        assertThat(queueAtCall).as("每次模型调用的队列 size（call0 空 → call1 应仍有 busy → call2 已 drain）")
            .containsExactly(0, 1, 0);
        // ④ 保留红线：真工具轮后 drain 注入 state（busy 恰一次）
        long busyInState = state.messages().stream()
            .filter(m -> m.role() == Role.user && m.content() != null && m.content().contains(BUSY_TEXT))
            .count();
        assertThat(busyInState).as("busy-queued 最终注入 state 恰一次（真工具轮后 drain，无双发）").isEqualTo(1);
        // ⑤ run 结束队列清空（busy-queued 已被消费）
        assertThat(queue.size()).as("run 结束队列清空（busy-queued 已被消费）").isZero();
    }
}
