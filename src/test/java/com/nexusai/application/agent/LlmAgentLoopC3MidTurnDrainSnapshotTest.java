package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
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
 * [C3 2026-09-19] mid-turn drain 必须位于消息快照（entryModelView / messagesForQuery）
 * <b>之前</b> —— 判据：drain 产物进【紧接的下一轮】请求。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：CC 的两轮请求之间，消息快照在 drain
 * <b>之后</b>组装（query.ts:1547 drain → 循环顶 :523 getMessagesAfterCompactBoundary）⇒ drain
 * 产物<b>必然</b>进紧接的下一轮请求。nexusai 原实现在快照之后（旧 :5871 &gt; :5758）⇒ 产物要
 * <b>再等一轮</b>才进请求；且 drain 之后若该轮只回文本（needsFollowUp=false）⇒ do-while 退出，
 * 该消息<b>从未进任何请求</b>（队列项已被移除 = 通知永久丢）。本测试锚定「进本轮请求」这条
 * 语义：若把 drain 移回快照之后（或删除快照前那处 drain），call1 的请求不再含该通知 → 变红。
 *
 * <p><b>RED tooth（反向实验）</b>：把 LlmAgentLoop 循环入口的 mid-turn drain 块移回
 * messagesForQuery 之后 → {@code sent.get(1)} 不含通知 → 断言 ② 变红。
 *
 * <p>harness 与 {@link LlmAgentLoopOdD2DrainSuppressionTest} 同源（真实 queue + queryLoop +
 * 工具轮），额外捕获每次 provider.stream 第 3 参（发送边界消息列表）。
 */
@DisplayName("[C3] mid-turn drain 在快照之前 ⇒ 产物进【本轮】请求")
class LlmAgentLoopC3MidTurnDrainSnapshotTest {

    /** 一条 NEXT 优先级的 task-notification（monitor 通知同款：UUID 由消费点兜底生成）。 */
    private static final String NOTIF_XML =
        "<task-notification>\n"
            + "<task-id>t-c3-anchor</task-id>\n"
            + "<task-type>monitor</task-type>\n"
            + "<status>completed</status>\n"
            + "<summary>Background command \"ls\" completed</summary>\n"
            + "</task-notification>\n";

    /** 真实 NotificationQueue 注入位置 4 的最小 AgentLoopContext（同 OdD2 helper）。 */
    private AgentLoopContext ctxWithQueue(LlmProviderFactory factory, NotificationQueue queue) {
        FeatureFlags flags = new FeatureFlags(false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false, false, false, false, false);
        return new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class), // 1 toolRegistry
            null, null, queue, null,                              // 2-5
            null, null, null, null,                               // 6-9
            null, factory, null, null, null, null,                // 10-15
            null, null, null, null,                               // 16-19
            flags, null, null, null, null, null,                  // 20-25
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

    @Test
    @DisplayName("工具轮后 drain 的通知进【紧接的下一轮】请求（call0 不含 / call1 含）；且登记落库 registry")
    void midTurnDrain_productEntersImmediatelyNextRequest() {
        String sid = "sess-c3-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = initialState(sid);
        NotificationQueue queue = new NotificationQueue();
        AtomicInteger callCount = new AtomicInteger(0);
        // 每次 provider.stream 第 3 参（发送边界消息列表 = 本轮真实请求）
        List<List<ChatMessageDto>> sent = new ArrayList<>();

        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<ChatMessageDto> history = inv.getArgument(3);
            sent.add(history == null ? List.of() : new ArrayList<>(history));
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            int call = callCount.getAndIncrement();
            if (call == 0) {
                // 工具轮：先模拟「用户/后台在工具轮期间入队一条 NEXT 通知」（此刻 turn-0 drain 已过），
                // 再返回 Bash 工具调用 → 置 lastIterationRanTools → 下一轮循环顶 drain 会消费它。
                queue.enqueue(new NotificationQueue.QueueItem(
                    NOTIF_XML, NotificationQueue.MODE_TASK_NOTIFICATION, NotificationQueue.Priority.NEXT,
                    null, null, false, null, false, null, sid));
                com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode input = json.createObjectNode().put("command", "ls");
                onMsg.accept(new AssistantMessage("need tool", "tool_calls",
                    List.of(new ToolUseBlock("toolu_c3", "Bash", input)), null, null));
            } else {
                onChunk.accept("final answer");
                onMsg.accept(new AssistantMessage("final answer", "end_turn", List.of(), null, null));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentLoopContext ctx = ctxWithQueue(factory, queue);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), sid)
                .withAvailableTools(List.of(TestContexts.dummyTool("Bash"))),
            QuerySource.USER, "test-model", 8, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>());

        // ── 断言 ① 场景完整性：工具轮 + 收尾 = 2 次模型调用 ──
        assertThat(callCount.get()).as("工具轮 → 收尾 = 2 次 LLM 调用").isEqualTo(2);

        // ── 断言 ② ★C3 核心判据：紧接工具轮的那一轮请求必须已含 drain 注入的通知 ──
        //   判据 = 内容含 <task-id>t-c3-anchor</task-id>（发送边界已包 <system-reminder> 壳，故按 XML 内标识判）。
        assertThat(sent.get(0))
            .as("call0（工具轮）请求不可能含尚未入队的通知")
            .noneMatch(m -> m.content() != null && m.content().contains("t-c3-anchor"));
        assertThat(sent.get(1))
            .as("★ call1（工具轮后的紧接一轮）请求必须已含 mid-turn drain 注入的通知 —— "
                + "drain 若在快照之后（C3 改前）此处不含 ⇒ 变红")
            .anyMatch(m -> m.role() == Role.user && m.content() != null
                && m.content().contains("t-c3-anchor"));

        // ── 断言 ③ 注入恰一次（不因位置前移而双发） ──
        long inState = state.rawMessages().stream()
            .filter(m -> m.content() != null && m.content().contains("t-c3-anchor"))
            .count();
        assertThat(inState).as("通知在 state 恰注入一次（无双发）").isEqualTo(1);

        // ── 断言 ④ queue 已消费清空 ──
        assertThat(queue.size()).as("drain 后队列清空").isZero();

        // ── 断言 ⑤ [C2] 该注入项已登记落库 registry（content=RAW、queuedOrigin=task-notification） ──
        assertThat(state.injectedQueuedMessages())
            .as("[C2] mid-turn 通知须登记 registry（落库载体；原实现只登记 busy-queued）")
            .hasSize(1);
        AgentState.InjectedQueuedMessage inj = state.injectedQueuedMessages().get(0);
        assertThat(inj.content()).as("registry content = RAW XML").isEqualTo(NOTIF_XML);
        assertThat(inj.queuedOrigin()).as("registry queuedOrigin = task-notification").isEqualTo("task-notification");
        assertThat(inj.isMeta()).as("[C2] 通知 isMeta=true（落 is_meta ⇒ resume 后 UI 隐藏）").isTrue();
        assertThat(inj.uuid()).as("uuid = 产出消息自身 id（队列项 uuid=null 时兜底）").isNotBlank();
    }
}
