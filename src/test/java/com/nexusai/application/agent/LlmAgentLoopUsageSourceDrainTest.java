package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.MessageUsageEvent;
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
 * [D1/R1 usage-source · 逐轮来源] mid-turn drain 注入项的来源分类必须落到 {@code state.usageSource}
 * （= {@code message.usage} 事件的 {@code source}，前端底部数字据此只统计「用户自己的请求」）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：F5 之后的重拉态判据 = 「该轮 user 行 is_meta=true」
 * （V51，DB 权威列）；live 侧必须与它<b>同语义</b>，否则同一会话状态在刷新前后 footer 数字会跳变：
 * <ul>
 *   <li>后台注入项（cron 调度 / 任务通知 / coordinator / channel）→ {@code background}；</li>
 *   <li>busy-queued（用户自己排的队）→ {@code user}；</li>
 *   <li>普通 prompt / turn-0 **不盖章** → 保持 run 级来源（CronIdleExecutor 起的后台 run
 *       其"本轮 prompt"必须保持 background，不得被改写为 user）。</li>
 * </ul>
 *
 * <p><b>RED tooth（反向实验）</b>：删掉 drain 里那两行 {@code state.setUsageSource(...)} →
 * notification 用例红（源停在 run 级 user）、busy-queued 用例红（若误把 busy 当 background）。
 *
 * <p>harness 与 {@code LlmAgentLoopC3MidTurnDrainSnapshotTest} 同源（真实 NotificationQueue +
 * 工具轮 → 下一轮循环顶 drain 消费）。
 */
@DisplayName("[D1/R1] drain 注入项的 usage 来源逐轮分类")
class LlmAgentLoopUsageSourceDrainTest {

    private static final String NOTIF_XML =
        "<task-notification>\n<task-id>t-r1-anchor</task-id>\n<status>completed</status>\n"
            + "<summary>Background command \"ls\" completed</summary>\n</task-notification>\n";

    /** 真实 NotificationQueue 注入位置 4 的最小 AgentLoopContext（同 C3 helper）。 */
    private AgentLoopContext ctxWithQueue(LlmProviderFactory factory, NotificationQueue queue) {
        FeatureFlags flags = new FeatureFlags(false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false, false, false, false, false);
        return new AgentLoopContext(
            Mockito.mock(com.nexusai.application.agent.tool.ToolRegistry.class),
            null, null, queue, null,
            null, null, null, null,
            null, factory, null, null, null, null,
            null, null, null, null,
            flags, null, null, null, null, null,
            null, null, null, null, null, null, null);
    }

    private AgentState initialState(String sid) {
        AgentState state = new AgentState("sys", sid, null);
        state.appendMessage(new ChatMessageDto(
            "m0", sid, Role.user, "user",
            "hello", null, List.of(), null, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of()));
        return state;
    }

    /** 跑一轮「工具轮 + 收尾」，在工具轮期间入队 {@code item} → 下一轮循环顶 drain 会消费它。 */
    private AgentState runWithQueuedItem(AgentState state, NotificationQueue.QueueItem item) {
        String sid = state.sessionId();
        NotificationQueue queue = new NotificationQueue();
        AtomicInteger callCount = new AtomicInteger(0);

        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            if (callCount.getAndIncrement() == 0) {
                queue.enqueue(item);   // 工具轮期间入队（此刻 turn-0 drain 已过）
                com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
                onMsg.accept(new AssistantMessage("need tool", "tool_calls",
                    List.of(new ToolUseBlock("toolu_r1", "Bash", json.createObjectNode().put("command", "ls"))),
                    null, null));
            } else {
                onChunk.accept("final answer");
                onMsg.accept(new AssistantMessage("final answer", "end_turn", List.of(), null, null));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
        assertThat(callCount.get()).as("工具轮 + 收尾 = 2 次模型调用（场景完整性）").isEqualTo(2);
        return state;
    }

    @Test
    @DisplayName("task-notification 注入 → 本轮来源 background（前端底部数字不计入该轮）")
    void taskNotificationInjectionMarksBackground() {
        String sid = "sess-r1-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = runWithQueuedItem(initialState(sid),
            new NotificationQueue.QueueItem(NOTIF_XML, NotificationQueue.MODE_TASK_NOTIFICATION,
                NotificationQueue.Priority.NEXT, null, null, false, null, false, null, sid));

        assertThat(state.usageSource())
            .as("通知轮必须标 background（与重拉态判据『该轮 user 行 is_meta=true』同语义）")
            .isEqualTo(MessageUsageEvent.SOURCE_BACKGROUND);
    }

    @Test
    @DisplayName("busy-queued 注入（用户自己排的队）→ 本轮来源 user（不得当后台任务滤掉）")
    void busyQueuedInjectionMarksUser() {
        String sid = "sess-r1-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = runWithQueuedItem(initialState(sid),
            new NotificationQueue.QueueItem("用户排队消息", NotificationQueue.MODE_PROMPT,
                NotificationQueue.Priority.NEXT, null, "msg-r1-busy", false, "busy-queued", false, null, sid));

        assertThat(state.usageSource())
            .as("busy-queued = 用户自己的请求 ⇒ user")
            .isEqualTo(MessageUsageEvent.SOURCE_USER);
    }

    @Test
    @DisplayName("cron 调度注入（workload=cron）→ 本轮来源 background")
    void cronWorkloadInjectionMarksBackground() {
        String sid = "sess-r1-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = runWithQueuedItem(initialState(sid),
            new NotificationQueue.QueueItem("定时任务提示", NotificationQueue.MODE_PROMPT,
                NotificationQueue.Priority.NEXT, null, null, true, NotificationQueue.WORKLOAD_CRON, false, null, sid));

        assertThat(state.usageSource()).isEqualTo(MessageUsageEvent.SOURCE_BACKGROUND);
    }

    @Test
    @DisplayName("后台 run 的「本轮 prompt」不盖章 → 保持 run 级来源（CronIdleExecutor 打的 background 不被改回 user）")
    void plainPromptInjectionKeepsRunLevelSource() {
        String sid = "sess-r1-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = initialState(sid);
        state.setUsageSource(MessageUsageEvent.SOURCE_BACKGROUND);   // 模拟 cron/通知 run 的 run 级盖章
        runWithQueuedItem(state,
            new NotificationQueue.QueueItem("run 自己的 prompt（mode=prompt / workload=null）",
                NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
                null, null, false, null, false, null, sid));

        assertThat(state.usageSource())
            .as("普通 prompt 不参与逐轮覆盖 ⇒ 后台 run 保持 background（若被改写成 user 则本用例红）")
            .isEqualTo(MessageUsageEvent.SOURCE_BACKGROUND);
    }
}
