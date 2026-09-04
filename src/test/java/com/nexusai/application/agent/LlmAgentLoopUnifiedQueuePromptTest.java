package com.nexusai.application.agent;

import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [OPD-TS-27 · WF3-03] 统一队列用户输入入队端到端测试。
 *
 * <p>意图 (WHY): CC enqueue(mode='prompt', default 'next') (messageQueueManager.ts:128-135) —
 * 主线程用户输入走统一队列, 由首个 turn drain 注入; 若 drain 未注入则用户输入丢失 (模型看不到
 * 用户问题, 应答无从谈起)。测试验证 run() 主线程 + 队列 bean 存在时 prompt 真实入队且被 drain
 * 注入为 user 消息; 真 subagent prompt 不入队 (CC query.ts:1577 subagent 绝不消费 prompt)。
 *
 * <p>测试经 AgentLoopContextFactory.setNotificationQueue 注入队列 bean +
 * LlmAgentLoop.setContextFactory 走生产同构路径（factory.forSession）模拟 bean 存在路径；
 * 不依赖已删除的 LlmAgentLoop.notificationQueue 实例字段（CRON-F7 去冗余）。
 */
class LlmAgentLoopUnifiedQueuePromptTest {

    private static LlmProvider stopProvider(String text) {
        return stopProvider(text, null);
    }

    /** 捕获 provider.stream 第 3 参 history（发送边界消息列表）——[P0-1] 包壳移发送边界后，壳产物在此断言。 */
    private static LlmProvider stopProvider(String text, List<List<ChatMessageDto>> sentMessages) {
        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            if (sentMessages != null) {
                @SuppressWarnings("unchecked")
                List<ChatMessageDto> history = inv.getArgument(3);
                sentMessages.add(history == null ? List.of() : new ArrayList<>(history));
            }
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

    /** [P0-1] 汇总所有发送批次的消息列表（跨多次 stream 调用展平）。 */
    private static List<ChatMessageDto> allSent(List<List<ChatMessageDto>> sentMessages) {
        return sentMessages.stream().flatMap(List::stream).toList();
    }

    @Test
    @DisplayName("主线程 + 队列 bean 存在 → prompt 入队并被首个 turn drain 注入为 user 消息")
    void mainThread_promptEnqueuedAndDrainedIntoMessages() {
        LlmProvider provider = stopProvider("response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);  // 生产同构路径：factory 注入队列 bean（build() :78）
        loop.setContextFactory(ctxFactory);      // 走 factory.forSession 而非 fallback

        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));

        // 用户输入经统一队列 drain 注入为 user 消息（若入队后无 drain 注入 → prompt 丢失）。
        // [prompt-wrap-fix] turn-0 首次输入（workload=null）对齐 CC handlePromptSubmit：注入原文不套壳
        //   （wrapCommandText human 前缀 'The user sent a new message' 仅 busy-queued 排队使用）——
        //   模型正确识别「用户已发新消息」，直接回复该输入
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .map(m -> m.content()).toList())
            .as("主线程 prompt 必须经队列 drain 注入为 user 消息（turn-0 首次输入 = 原文，不套 CC human 前缀）")
            .anyMatch(c -> c.equals("hello"))
            .noneMatch(c -> c.startsWith("The user sent a new message while you were working:\n"));
        // 队列已被消费清空（drainForQuery 消费 + remove）
        assertThat(queue.size()).as("drain 后队列应为空").isZero();
    }

    @Test
    @DisplayName("[3b+3a] 真实会话 prompt 入队带 sessionId=A，别的会话 cron 不被本回合捞走")
    void realSession_promptEnqueuedWithSessionId_otherSessionCronNotDrained() {
        // WHY（规则九 · 验证 3b + 3a 意图）: A-queue-ownership-probe §2.2 场景 B —— 用户 prompt 入队
        // 若 sessionId=null，别的会话并发 turn 会把它捞走（错误归属 + 本回合空转）。3b 改传
        // state.sessionId()（派生 UUID）→ 本会话首轮 drain（3a currentSessionId 过滤）精确命中，
        // 别的会话 cron 捞不走。
        LlmProvider provider = stopProvider("response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        String sid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);   // 会话 A 的派生 UUID
        // 别的会话 B 的 cron 先入队（priority NEXT 保证进本回合快照阈值 next）
        queue.enqueue(new NotificationQueue.QueueItem(
            "B的cron", NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null, null, true, NotificationQueue.WORKLOAD_CRON, false, null,
            UUID.randomUUID().toString()));
        // 会话 A（sid）run() 把自己的 prompt 入队 → [3b] 带 sessionId=sid
        AgentState state = loop.run(RunRequest.session("hello-A", sid, null,
            com.nexusai.infra.llm.ProviderConfig.empty(), "test-model", null, null));

        // 本回合 drain（[3a] currentSessionId=sid）只捞 A 自己的 prompt，B 的 cron 留队列
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .map(m -> m.content()).toList())
            .as("A 的回合必须经队列 drain 注入本会话 prompt（turn-0 首次输入 = 原文，不套 CC human 前缀）")
            .anyMatch(c -> c.equals("hello-A"))
            .noneMatch(c -> c.startsWith("The user sent a new message while you were working:\n"));
        assertThat(queue.size())
            .as("B 的 cron 不得被 A 的回合捞走（sessionId 归属过滤，留队列等 B 自身 turn / CronIdleExecutor）")
            .isEqualTo(1);
        assertThat(queue.peek(q -> true).orElseThrow().value()).isEqualTo("B的cron");
    }

    @Test
    @DisplayName("真 subagent (agentId != sessionId) → prompt 直 appendMessage, 不入队")
    void subagent_promptAppendedDirectly_queueStaysEmpty() {
        LlmProvider provider = stopProvider("response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        String sid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        UUID subAgentId = UUID.randomUUID();   // agentId != sessionId → 真 subagent
        AgentState state = loop.run(RunRequest.session("sub-prompt", sid, subAgentId,
            com.nexusai.infra.llm.ProviderConfig.empty(), "test-model", null, null));

        // CC query.ts:1577 subagent 绝不消费 prompt → prompt 不得入队 (直 append)
        assertThat(queue.size()).as("subagent prompt 不得入统一队列").isZero();
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .map(m -> m.content()).toList())
            .as("subagent prompt 直 append 进 messages")
            .contains("sub-prompt");
    }

    @Test
    @DisplayName("3 条 prompt drain → 恰 3 条独立 user 消息（非 String.join 合并单条）· 对齐 CC queueProcessor.ts:42-43")
    void multiplePrompts_drainedIntoIndependentUserMessages() {
        LlmProvider provider = stopProvider("response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        // 预置 2 条 prompt（NEXT 主线程），run() 再入队 1 条 → 共 3 条待 drain
        queue.enqueue(new NotificationQueue.QueueItem(
            "queued-prompt-A", NotificationQueue.MODE_PROMPT, null, null));
        queue.enqueue(new NotificationQueue.QueueItem(
            "queued-prompt-B", NotificationQueue.MODE_PROMPT, null, null));

        AgentState state = loop.run(RunRequest.forTest("queued-prompt-C", "test-model", null));

        // CC queueProcessor.ts:42-43「each becomes its own user message with its own UUID」：
        // 批量取但逐命令独立 user message。3 条 prompt 必须产出 3 条独立 user 消息，
        // 各自独立文本（而非 String.join("\n\n") 合并为单条 —— 那会让模型把 N 个输入当 1 个处理）。
        // [prompt-wrap-fix] 3 条 prompt 的 workload 均为 null（turn-0 首次输入）→ 对齐 CC
        //   handlePromptSubmit 原文语义：注入消息 content = 原始文本，不套 wrapCommandText human 壳
        //   （前缀仅 busy-queued 排队使用）。
        java.util.List<String> userContents = state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .map(ChatMessageDto::content)
            .toList();
        assertThat(userContents)
            .as("N 条 prompt drain 必须产生 N 条独立 user 消息（turn-0 首次输入均走原文，对齐 CC 逐 cmd 独立 UUID）")
            .hasSize(3)
            .containsExactlyInAnyOrder("queued-prompt-A", "queued-prompt-B", "queued-prompt-C");
        // 无任何一条被套 'The user sent a new message' 壳（workload=null 不触发 wrapCommandText human 分支）
        assertThat(userContents)
            .as("turn-0 首次输入（workload=null）不得套 wrapCommandText human 壳（前缀/后缀剥壳已无意义）")
            .noneMatch(c -> c.startsWith("The user sent a new message while you were working:\n"));
    }

    @Test
    @DisplayName("prompt 注入 user 消息 —— turn-0 首次输入（workload=null）走原文，不套 CC human 前缀")
    void promptInjection_originalTextWhenWorkloadNull() {
        LlmProvider provider = stopProvider("response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        // 预置 1 条 MODE_PROMPT（workload=null —— 注意：此处并非真实 cron；真实 cron 应以 WORKLOAD_CRON
        // 入队，且 UP-04 起 cron mid-turn drain 套 CC human 壳 + system-reminder，不再走原文）。
        // run() 主线程 prompt 亦入队（workload=null）→ 共 2 条待 drain，均按 turn-0 首次输入原文注入
        queue.enqueue(new NotificationQueue.QueueItem(
            "cron-triggered-prompt", NotificationQueue.MODE_PROMPT, null, null));

        AgentState state = loop.run(RunRequest.forTest("main-prompt", "test-model", null));

        // [prompt-wrap-fix] workload=null 的 prompt（turn-0 首次输入）对齐 CC handlePromptSubmit：
        //   注入消息 content = 原始文本，绝不套 'The user sent a new message' 壳
        //   （wrapCommandText human 分支 messages.ts:5991-6006 仅 queued/busy-queued 使用）
        java.util.List<String> userContents = state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .map(ChatMessageDto::content)
            .toList();
        assertThat(userContents)
            .as("turn-0 首次输入（workload=null）注入消息 = 原文（不套 wrapCommandText human 壳）")
            .hasSize(2)
            .containsExactlyInAnyOrder("cron-triggered-prompt", "main-prompt");
        // 无任何一条被套 'The user sent a new message' 壳（workload=null 不触发 human 分支）
        assertThat(userContents)
            .as("workload=null 的 prompt 不得出现 'The user sent a new message' 前缀（CC handlePromptSubmit 原文）")
            .noneMatch(c -> c.startsWith("The user sent a new message while you were working:\n"));
    }

    @Test
    @DisplayName("3 条 task-notification drain → 每条独立 user 消息(原文)+独立 attachment · 壳在发送边界(前缀) · 对齐 CC messages.ts:5502/3782")
    void multipleNotifications_drainedIntoIndependentMessagesAndAttachments() {
        java.util.List<java.util.List<ChatMessageDto>> sentMessages = new java.util.ArrayList<>();
        LlmProvider provider = stopProvider("response", sentMessages);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        // NEXT 优先级入队保证单 turn 即 drain（优先级仲裁已由 drainForQuery 对齐 CC query.ts:1570-1571）
        queue.enqueue(new NotificationQueue.QueueItem(
            "notif-1", NotificationQueue.MODE_TASK_NOTIFICATION, null, null));
        queue.enqueue(new NotificationQueue.QueueItem(
            "notif-2", NotificationQueue.MODE_TASK_NOTIFICATION, null, null));
        queue.enqueue(new NotificationQueue.QueueItem(
            "notif-3", NotificationQueue.MODE_TASK_NOTIFICATION, null, null));

        AgentState state = loop.run(RunRequest.forTest("hello", "test-model", null));

        // [P0-1 机制切换] state 存原文 RAW + queuedOrigin=task-notification（壳只在发送边界生成）
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .map(ChatMessageDto::content)
            .filter(c -> c != null && !c.equals("hello"))
            .toList())
            .as("3 条通知 state 存原文 RAW（非壳文本；包壳在发送边界 wrapQueuedMessagesForApi）")
            .containsExactlyInAnyOrder("notif-1", "notif-2", "notif-3");
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .filter(m -> m.queuedOrigin() != null && m.queuedOrigin().equals("task-notification"))
            .map(ChatMessageDto::content))
            .as("task-notification state 消息必须带 queuedOrigin=task-notification（发送层据此包壳）")
            .containsExactlyInAnyOrder("notif-1", "notif-2", "notif-3");
        // [P0-3 OD-D3] mid-turn task-notification isMeta=false→true（UI 隐藏、模型可见）
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .filter(m -> m.queuedOrigin() != null && m.queuedOrigin().equals("task-notification"))
            .map(ChatMessageDto::isMeta)
            .distinct())
            .as("mid-turn task-notification 注入消息必须全部 isMeta=true（OD-D3：UI 隐藏、模型可见，CC :3753-3756）")
            .containsExactly(true);

        // CC attachments.ts:1046-1083 每 command → 独立 attachment（source_uuid=cmd.uuid）；
        // Java 侧类型保持 Java 专属 background_task_notification（R25-1 schema，CC 为 queued_command）
        // 注：排除无关 task_summary attachment（loop 退出时系统追加，与 drain 注入无关）
        assertThat(state.attachments().stream()
            .filter(a -> "background_task_notification".equals(a.type()))
            .toList())
            .as("3 条通知必须产出 3 条独立 background_task_notification attachment（禁止合并单条）")
            .hasSize(3)
            .extracting(a -> a.content())
            .containsExactlyInAnyOrder("notif-1", "notif-2", "notif-3");

        // 发送边界包壳产物：CC messages.ts:5502 前缀 + system-reminder（各条独立，非合并单条）
        java.util.List<String> sentNotif = allSent(sentMessages).stream()
            .filter(m -> m.role() == Role.user)
            .map(ChatMessageDto::content)
            .filter(c -> c != null && c.startsWith("<system-reminder>\nA background agent completed a task:\n"))
            .toList();
        assertThat(sentNotif)
            .as("3 条通知发送边界必须产出 3 条独立 user 消息，各带 wrapCommandText 前缀 + system-reminder 包裹")
            .containsExactlyInAnyOrder(
                LlmAgentLoop.wrapQueuedContentForApi("task-notification", "notif-1"),
                LlmAgentLoop.wrapQueuedContentForApi("task-notification", "notif-2"),
                LlmAgentLoop.wrapQueuedContentForApi("task-notification", "notif-3"));
    }

    @Test
    @DisplayName("channel 包裹项 (mode=prompt) drain → 发送层注入 CC messages.ts:5506 不可信警告前缀；cron/用户 prompt 与通知前缀互不串扰")
    void channelWrappedItem_drainsWithUntrustedPrefix_othersUnaffected() {
        java.util.List<java.util.List<ChatMessageDto>> sentMessages = new java.util.ArrayList<>();
        LlmProvider provider = stopProvider("response", sentMessages);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        String channelRaw = "<channel source=\"slack\">\nhi from channel\n</channel>";
        // channel 入站（ChannelNotification:131 同型 8-arg：mode=prompt/priority=next/isMeta/skipSlashCommands，
        // value 为 wrapChannelMessage 包裹）→ 显式 MessageOrigin{kind:'channel',server:'slack'}（S07 裁定：
        // origin 由入队侧显式携带，对齐 useManageMCPConnections.ts:528）→ :5506 不可信前缀
        queue.enqueue(new NotificationQueue.QueueItem(
            channelRaw,
            NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null, null, true, null, true,
            new NotificationQueue.MessageOrigin("channel", "slack")));
        // cron 触发 prompt（TestJob.enqueueLead :292-294 同型 6-arg + WORKLOAD_CRON；priority 用 NEXT
        // 保证单 turn 即 drain —— 优先级仲裁已由 drainForQuery 覆盖，本测试只断言前缀隔离）
        queue.enqueue(new NotificationQueue.QueueItem(
            "cron-triggered-prompt", NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null, true, NotificationQueue.WORKLOAD_CRON));
        // task-notification（BackgroundTaskRunner 同型 2-arg）→ :5502 前缀
        queue.enqueue(new NotificationQueue.QueueItem(
            "notif-1", NotificationQueue.MODE_TASK_NOTIFICATION));

        AgentState state = loop.run(RunRequest.forTest("main-prompt", "test-model", null));

        // [P0-1 机制切换] state 存原文 RAW + queuedOrigin（壳只在发送边界生成）
        java.util.List<String> userContents = state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .map(ChatMessageDto::content)
            .toList();
        assertThat(userContents)
            .as("state 存原文 RAW（channel/cron/task-notification/main 均为原文；包壳在发送边界）")
            .contains(channelRaw, "cron-triggered-prompt", "notif-1", "main-prompt")
            .noneMatch(c -> c == null || c.startsWith("<system-reminder>"));

        // 发送边界包壳产物（各来源独立，互不串扰）
        java.util.List<String> sentContents = allSent(sentMessages).stream()
            .filter(m -> m.role() == Role.user)
            .map(ChatMessageDto::content)
            .toList();
        // ① channel → CC messages.ts:5506 逐字前缀（含 em-dash 与不可信警告），NOT human 分支
        String channelExpected = LlmAgentLoop.wrapQueuedContentForApi("channel|slack", channelRaw);
        assertThat(sentContents)
            .as("channel 项发送边界必须注入 CC messages.ts:5506 不可信警告前缀 + system-reminder 包裹")
            .contains(channelExpected);
        assertThat(channelExpected)
            .as("channel 壳不得含 human 分支 MUST address")
            .doesNotContain("MUST address");
        // ② cron 触发 prompt → CC 默认 human 壳 + system-reminder（useScheduledTasks enqueue 无 origin）
        String cronExpected = LlmAgentLoop.wrapQueuedContentForApi("cron", "cron-triggered-prompt");
        assertThat(sentContents)
            .as("cron 触发 prompt 套 CC wrapCommandText 默认 human 壳 + system-reminder 包裹（UP-04 对齐 CC 真源）")
            .contains(cronExpected);
        // ②' cron mid-turn 注入 isMeta=true（C5-cron：state 上判别，content 为原文）
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .filter(m -> m.content() != null && m.content().equals("cron-triggered-prompt"))
            .findFirst())
            .as("cron mid-turn 注入消息 isMeta=true（C5-cron：对齐 C1 空闲路径，前端隐藏、模型可见）")
            .isPresent()
            .satisfies(opt -> assertThat(opt.get().isMeta()).isTrue());
        // ③ 主线程 prompt（workload=null，turn-0 首次输入）→ 原文不套壳
        assertThat(userContents)
            .as("主线程 prompt（workload=null）注入 = 原文（不套 'The user sent a new message' 壳）")
            .contains("main-prompt");
        assertThat(sentContents)
            .as("主线程 prompt 发送边界 = 原文（不套壳）")
            .contains("main-prompt");
        assertThat(userContents)
            .as("workload=null 的 prompt 不得出现 'The user sent a new message' 前缀")
            .noneMatch(c -> c != null && c.startsWith("The user sent a new message while you were working:\n"));
        // ④ task-notification → :5502 逐字前缀 + system-reminder（发送边界）
        assertThat(sentContents)
            .as("task-notification 发送边界保持 :5502 前缀（逐字）+ system-reminder 包裹")
            .contains(LlmAgentLoop.wrapQueuedContentForApi("task-notification", "notif-1"));
        // ⑤ [P0-3 OD-D3] mid-turn task-notification isMeta=false→true（UI 隐藏、模型可见）
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user)
            .filter(m -> m.content() != null && m.content().equals("notif-1"))
            .findFirst())
            .as("mid-turn task-notification 注入消息 isMeta=true（OD-D3：对齐 CC queued_command :3753-3756）")
            .isPresent()
            .satisfies(opt -> assertThat(opt.get().isMeta()).isTrue());
    }

    // ============ [mid-turn-align] busy-queued mid-turn 注入（同轮回答） ============

    @Test
    @DisplayName("busy-queued(NEXT) 被当前轮工具边界 drain 注入为同轮 user 消息（state 原文 RAW+queuedOrigin；发送边界中文提醒壳 + uuid）· 不落库仅暂存")
    void busyQueued_midTurnDrainedIntoSameTurnUserMessage() {
        // WHY（规则九 · 验证 mid-turn 同轮回答而非 turn 结束新轮）: CC query.ts:1556-1560 工具边界
        //   drain 排队命令 → getQueuedCommandAttachments 注入「当前轮」上下文，模型同轮看到并回答。
        //   Java 对齐：busy-queued（mode=prompt + workload="busy-queued" + sessionId=本会话）不再被
        //   drainForQuery 过滤（原 [queue-order-fix 方案A B2] 过滤已删），sleepRan=false → threshold=NEXT
        //   快照能进（priority 必须 NEXT —— 若 LATER 连快照都进不去会静默退化回方案A）。注入的 user
        //   消息【不立即落库】，state.injectedQueuedMessages() 暂存原始文本供轮结束补落库。
        java.util.List<java.util.List<ChatMessageDto>> sentMessages = new java.util.ArrayList<>();
        LlmProvider provider = stopProvider("response", sentMessages);
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        loop.setContextFactory(ctxFactory);

        String sid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String busyUuid = "msg-queued-busy01";
        // busy-queued 以 priority=NEXT 入队（对齐 CC 用户输入默认 'next'，ChatService.enqueueBusyPrompt）
        queue.enqueue(new NotificationQueue.QueueItem(
            "忙时追问", NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null, busyUuid, false, "busy-queued", false, null, sid));

        AgentState state = loop.run(RunRequest.session("主问题", sid, null,
            com.nexusai.infra.llm.ProviderConfig.empty(), "test-model", null, null));

        // ① [P0-1 机制切换] mid-turn 注入：state 存原文 RAW + queuedOrigin=busy-queued（壳只在发送边界）
        ChatMessageDto busyState = state.messages().stream()
            .filter(m -> m.role() == Role.user && busyUuid.equals(m.id()))
            .findFirst().orElse(null);
        assertThat(busyState)
            .as("busy-queued 必须被 mid-turn 工具边界注入当前轮上下文（同轮回答，非 turn 结束新轮）")
            .isNotNull();
        assertThat(busyState.content())
            .as("state 存原文 RAW（不套壳；对齐 CC transcript 存 RAW，发送边界临时包壳）")
            .isEqualTo("忙时追问");
        assertThat(busyState.queuedOrigin()).as("busy-queued state 消息必须带 queuedOrigin=busy-queued").isEqualTo("busy-queued");
        assertThat(busyState.isMeta()).as("busy-queued isMeta=false（真实用户输入，UI 可见）").isFalse();
        // 发送边界：中文提醒壳（Java 独有，含原文）
        assertThat(allSent(sentMessages).stream()
            .filter(m -> m.role() == Role.user)
            .map(ChatMessageDto::content)
            .filter(c -> c != null && c.startsWith("<system-reminder>\n用户在你工作时发来一条新消息"))
            .toList())
            .as("busy-queued 发送边界必须注入中文提醒壳（含原文忙时追问）")
            .anyMatch(c -> c.contains("忙时追问"));
        // ② 注入消息携带原队列 uuid 作消息 id（CC messages.ts:3782 source_uuid → createUserMessage uuid）
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user && busyUuid.equals(m.id()))
            .map(ChatMessageDto::content).toList())
            .as("注入的 busy-queued user 消息必须携带原队列 uuid 作消息 id")
            .anyMatch(c -> c.equals("忙时追问"));
        // ③ 队列清空（busy-queued 已被 drain 消费出队）
        assertThat(queue.size()).as("drain 后 busy-queued 已出队").isZero();
        // ④ state.injectedQueuedMessages 暂存原始文本 + queuedOrigin（轮结束 ChatService 补落库用）
        assertThat(state.injectedQueuedMessages())
            .as("mid-turn 注入的排队 user 消息必须暂存 {uuid, 原始文本, queuedOrigin}（供轮结束补落库，不落壳文本）")
            .anyMatch(inj -> busyUuid.equals(inj.uuid()) && "忙时追问".equals(inj.content())
                && "busy-queued".equals(inj.queuedOrigin()));
        // ⑤ 不进图片分支：注入消息无 contentBlocks/imagePasteIds（纯文本 queued_command）
        assertThat(state.messages().stream()
            .filter(m -> m.role() == Role.user && busyUuid.equals(m.id()))
            .findFirst().orElseThrow())
            .as("busy-queued 恒走纯文本分支（无图片 block，不误附残留 pending 图片）")
            .satisfies(m -> {
                assertThat(m.contentBlocks()).isEmpty();
                assertThat(m.imagePasteIds()).isEmpty();
            });
    }

    @Test
    @DisplayName("busy-queued mid-turn drain 后推 queue.drained：drained[].streamTopic 恒会话 topic（前端已订阅，无需切换）")
    void busyQueued_emitDrainedWithEmptyStreamTopic() {
        // WHY（会话级单 topic 改造）: mid-turn 注入=同轮流式，assistant 回答流与兜底新轮同走会话级
        //   /topic/sessions/{sid}/stream——前端已在会话 topic 单一订阅，queue.drained 无需携带新订阅
        //   地址，drained[].streamTopic 恒会话 topic（QueueEventPublisher 恒派生，三参 override 已删）。
        //   两参签名断言锁住 mid-turn 注入归属契约防回归。
        LlmProvider provider = stopProvider("response");
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        LlmAgentLoop loop = new LlmAgentLoop(factory);
        AgentLoopContextFactory ctxFactory = new AgentLoopContextFactory();
        ctxFactory.setLlmProviderFactory(factory);
        NotificationQueue queue = new NotificationQueue();
        ctxFactory.setNotificationQueue(queue);
        QueueEventPublisher mockPublisher = mock(QueueEventPublisher.class);
        ctxFactory.setQueueEventPublisher(mockPublisher);
        loop.setContextFactory(ctxFactory);

        String sid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String busyUuid = "msg-queued-busy02";
        queue.enqueue(new NotificationQueue.QueueItem(
            "忙时追问B", NotificationQueue.MODE_PROMPT, NotificationQueue.Priority.NEXT,
            null, busyUuid, false, "busy-queued", false, null, sid));

        loop.run(RunRequest.session("主问题", sid, null,
            com.nexusai.infra.llm.ProviderConfig.empty(), "test-model", null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<NotificationQueue.QueueItem>> drainedCaptor =
            ArgumentCaptor.forClass(java.util.List.class);
        verify(mockPublisher).emitDrained(eq(sid), drainedCaptor.capture());
        assertThat(drainedCaptor.getValue())
            .as("emitDrained 的 drained 列表必须恰好含该 busy-queued 项（uuid 匹配）")
            .singleElement()
            .satisfies(item -> {
                assertThat(item.uuid()).isEqualTo(busyUuid);
                assertThat(item.workload()).isEqualTo("busy-queued");
            });
    }
}
