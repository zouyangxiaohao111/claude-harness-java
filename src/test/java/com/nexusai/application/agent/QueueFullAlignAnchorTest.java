package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
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
 * [queue-full-align P0/P4] 排队消息消费完整对齐 CC 的测试锚点。
 *
 * <p>P0: turn-0 排队命令注入（对齐 CC handlePromptSubmit —— 排队命令作为首批 user 消息进新 turn）。
 * P4: didLastTurnUseSleep 等价性（CC query.ts:1566 toolUseBlocks 判定 Sleep 轮 → 升阈 'later'）。
 */
class QueueFullAlignAnchorTest {

    private static LlmProvider stopProvider(String text) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept(text);
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage(text, "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    @Test
    @DisplayName("P0: turn-0 排队命令注入 FIFO——busy-queued 先入队先出，主 prompt 后注入；busy-queued 进 injectedQueuedMessages")
    void turnZero_busyQueuedThenMainPromptFifoInjection() {
        // WHY（规则九 · 锁 P0 语义）: CC handlePromptSubmit —— 排队命令作为首批 user 消息进新 turn
        //   （query.ts:307 首轮直接 callModel，无 drain 块；drain 在工具边界之后）。Java turn-0 顶部
        //   drain（drainAndInjectQueued，firstIteration 强制放行）即消费排队命令注入首批 user 消息。
        //   RED: 若 turn-0 不注入（排队命令要等第一个工具边界才被看到）→ 断言变红。FIFO 序 =
        //   同优先级先入队先出（CC dequeue :175-185）→ busy-queued 先于主 prompt。
        LlmProvider provider = stopProvider("response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        String sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
        String busyUuid = "msg-queued-turn0";
        // busy-queued 先入队（priority=NEXT，用户输入默认），run() 内主 prompt 后入队 → 同优先级 FIFO
        queue.enqueue(new NotificationQueue.QueueItem(
            "忙时追问", NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null, busyUuid, false, "busy-queued", false, null, sid));

        AgentState state = loop.run(RunRequest.session("主问题", sid, null,
            com.nexusai.infra.llm.ProviderConfig.empty(), "test-model", null, null));

        String ccPrefix = "The user sent a new message while you were working:\n";
        String ccSuffix = "\n\nIMPORTANT: After completing your current task, you MUST address the user's message above. Do not ignore it.";
        List<String> userContents = state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .map(ChatMessageDto::content)
            .toList();
        // [prompt-wrap-fix] 契约分化：busy-queued（mid-turn 排队）保留 wrapCommandText 壳（prefix + 原文
        //   + suffix）；turn-0 主 prompt（workload=null）走 handlePromptSubmit 原文不套壳。
        assertThat(userContents).hasSize(2);
        assertThat(userContents.get(0))
            .as("busy-queued 先入队先出（FIFO），保留 wrapCommandText 壳（prefix + 原文 + suffix）")
            .isEqualTo(ccPrefix + "忙时追问" + ccSuffix);
        assertThat(userContents.get(1))
            .as("turn-0 主 prompt（workload=null）后注入，走原文不套壳（对齐 CC handlePromptSubmit）")
            .isEqualTo("主问题");
        // turn-0 注入的 busy-queued 进 state.injectedQueuedMessages（原位落库通道，对齐文档反思 #6）
        assertThat(state.injectedQueuedMessages())
            .as("turn-0 注入的 busy-queued 必须进 injectedQueuedMessages（{uuid, 原始文本}，供轮末补落库）")
            .anyMatch(inj -> busyUuid.equals(inj.uuid()) && "忙时追问".equals(inj.content()));
        // 队列已清空（drainForQuery remove 消费，天然防与后续工具边界重复注入）
        assertThat(queue.size()).as("turn-0 drain 后队列清空（消费项 remove，防双发）").isZero();
    }

    // ============ P4: didLastTurnUseSleep 等价性锚点（CC query.ts:1566 toolUseBlocks → Sleep 轮升阈） ============

    /** 反射调用私有静态 didLastTurnUseSleep（Spring 版无 ReflectionTestUtils.invokeStaticMethod）。 */
    private static boolean didLastTurnUseSleep(AgentState state) throws Exception {
        Method m = LlmAgentLoop.class.getDeclaredMethod("didLastTurnUseSleep", AgentState.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, state);
    }

    private static AgentState stateWithLastAssistant(List<ToolCallDto> toolCalls) {
        AgentState state = new AgentState("sys", "sess-sleep-anchor", null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false));
        state.appendMessage(new ChatMessageDto(
            "m2", null, Role.assistant, "assistant", "text", null, toolCalls,
            FinishReason.stop, null, null, null, OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null, false, false));
        return state;
    }

    @Test
    @DisplayName("P4: didLastTurnUseSleep——Sleep 工具轮→true（CC query.ts:1566 toolUseBlocks.some(b.name===SLEEP_TOOL_NAME)）")
    void didLastTurnUseSleep_sleepToolTurn_true() throws Exception {
        // WHY: drain 阈值依赖 sleepRan（Sleep 过 → 升阈 'later' 连 later 通知一起 drain，否则 'next'）。
        //   误判 false → later 通知滞留到下一轮/Sleep 轮外（CC query.ts:1570-1571）。RED: 漏检 Sleep → 变红。
        AgentState state = stateWithLastAssistant(List.of(
            new ToolCallDto("c1", "Sleep", "{}", null, false)));
        assertThat(didLastTurnUseSleep(state)).as("最近 assistant 含 Sleep tool_use → sleepRan=true（升阈 'later'）").isTrue();
    }

    @Test
    @DisplayName("P4: didLastTurnUseSleep——普通工具轮→false（无 Sleep 不升阈，阈值 'next'）")
    void didLastTurnUseSleep_normalToolTurn_false() throws Exception {
        AgentState state = stateWithLastAssistant(List.of(
            new ToolCallDto("c1", "Bash", "{\"cmd\":\"ls\"}", null, false)));
        assertThat(didLastTurnUseSleep(state)).as("普通工具轮不含 Sleep → sleepRan=false（阈值 'next'，later 留队）").isFalse();
    }

    @Test
    @DisplayName("P4: didLastTurnUseSleep——纯文本轮→false（无 toolCalls）")
    void didLastTurnUseSleep_pureTextTurn_false() throws Exception {
        AgentState state = stateWithLastAssistant(List.of());
        assertThat(didLastTurnUseSleep(state)).as("纯文本轮无 toolCalls → sleepRan=false（不升阈）").isFalse();
    }
}
