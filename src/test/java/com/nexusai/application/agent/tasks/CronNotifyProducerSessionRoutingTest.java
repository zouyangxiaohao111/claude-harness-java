package com.nexusai.application.agent.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.SessionKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cron-notify · 后台任务通知 / channel 通知带创建会话 sessionId → drain 注入该会话回合。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: 用户拍板「task-notification/channel CC 中自动注入当前循环，
 * 我们也需要——本身是该会话构建的后台任务，结果没法通知到对应会话循环中」。CC 中后台任务完成通知经
 * {@code enqueuePendingNotification({value, mode:'task-notification'})}（LocalShellTask.tsx:105-171）无
 * agentId 直进<b>当前会话</b>队列（单进程单主会话 ambient）；channel 消息经 {@code enqueue({mode:'prompt'})}
 * （useManageMCPConnections.ts:523-530）同样注入当前会话。Java 多会话 web 服务没有「唯一主会话」，
 * 必须由<b>生产方</b>在注册时把创建会话 sessionId 透传到 {@code QueueItem.sessionId}，drain 3a 才能把
 * 通知注入对应会话回合（会话活跃时）；会话空闲由 CronIdleExecutor 代跑；创建会话已结束 → 回落全局。
 *
 * <p><b>capture 源（F1 返工）</b>: 生产方<b>不得读 MDC</b> —— BashTool/PowerShellTool/SubagentTool/
 * SubagentExecutor 全部在 {@link StreamingToolExecutor} 的 tool-exec 池线程执行，ThreadLocal MDC
 * 不跨线程、恒 null；创建会话必须从 {@code ToolUseContext.sessionId()}（ctx 字段，可靠源）提取透传。
 * 本测试补「经真实 StreamingToolExecutor 派发路径」用例（{@link #backgroundTaskRunner_poolThread_noMdc_carriesCtxSession}
 * ），锁死生产路径：池线程 MDC 为 null，但 ctx.sessionId() 仍正确注入任务并路由到创建会话回合。
 *
 * <p><b>断言形态（四类生产方逐一）</b>:
 * <ol>
 *   <li>后台任务完成通知带创建会话 sessionId，且 drainForQuery(本会话) 注入、drainForQuery(别的会话) 不捞</li>
 *   <li>channel 通知带创建会话 sessionId，且 drainForQuery(本会话) 注入</li>
 *   <li>会话已结束（currentSessionId 无匹配）→ 通知不回任何具体会话 turn，由全局执行器消费</li>
 * </ol>
 *
 * <p>本测试直连生产方入队 + drainForQuery 消费点（不启动完整 agent loop），聚焦「生产方补 sessionId →
 * drain 归属」闭环。3a 过滤本身已有 NotificationQueueScopingTest 覆盖，此处验证<b>生产方确实带上 sessionId</b>
 * （回归即变红 = 生产方漏带，通知重新归全局）。
 */
class CronNotifyProducerSessionRoutingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ─────────────────── 1. BackgroundTaskRunner 后台任务完成通知 ───────────────────

    /**
     * WHY: async agent（registerAsyncAgent）是「该会话构建的后台任务」的典型——注册时把创建会话
     * sessionId（F1：显式透传 ctx.sessionId()，非 MDC）存在 task 上，完成通知在 worker 线程入队
     * （MDC 已丢）。若生产方不把创建会话 sessionId 透传到 QueueItem，通知 sessionId=null 归全局，
     * 创建会话的回合拿不到自己任务的完成通知（用户拍板背景）。
     */
    @Test
    @DisplayName("BackgroundTaskRunner: async agent 完成通知带创建会话 sessionId → 该会话 turn drain 注入")
    void backgroundTaskRunner_asyncAgentNotification_carriesCreatingSession_andDrainsToThatSessionTurn() {
        String createSession = "sess-notify-a1";
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(queue, new TaskFrameworkService(null));

        UUID agentId = UUID.randomUUID();
        BackgroundTask task = runner.registerAsyncAgent(agentId, "异步任务", "prompt", "general-purpose", null, createSession);
        // 完成通知（transitionToTerminal 内部 enqueue）
        runner.completeAsyncAgent(task.id(), AsyncAgentResult.success("summary", 1, 10L, task.id(), 5L, com.nexusai.application.agent.tool.AgentUsage.EMPTY));

        // 创建会话回合 drain 注入（3a: canonicalUuid 归一化相等）——先 drain 再断言，避免 dequeueAll 清空
        NotificationQueue.QueueItem drained = drainTaskNotification(queue, createSession);
        assertThat(drained).as("创建会话 turn 必须捞到自己的任务完成通知").isNotNull();
        assertThat(drained.value()).contains(task.id());
        // 通知项带创建会话 sessionId（注册时透传捕获）
        assertThat(SessionKeys.canonicalUuid(drained.sessionId()))
            .as("通知必须携带创建会话 sessionId（注册时透传）")
            .isEqualTo(SessionKeys.canonicalUuid(createSession));
    }

    /**
     * WHY: 会话 A 构建后台任务后，会话 B 的回合不得捞走 A 的任务通知（跨会话捞走 = A-queue-ownership-probe
     * §2.2 场景 A 的 task-notification 变体）。3a 过滤由生产方 sessionId 触发——若生产方漏带，B 会把 A 的
     * 通知当全局消息捞进自己回合（幻影消息）。
     */
    @Test
    @DisplayName("BackgroundTaskRunner: 别的会话 turn 不捞该通知（sessionId 归属过滤生效）")
    void backgroundTaskRunner_notification_notDrainedByOtherSessionTurn() {
        String createSession = "sess-notify-a2";
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(queue, new TaskFrameworkService(null));

        UUID agentId = UUID.randomUUID();
        BackgroundTask task = runner.registerAsyncAgent(agentId, "异步任务", "prompt", "general-purpose", null, createSession);
        runner.completeAsyncAgent(task.id(), AsyncAgentResult.success("summary", 1, 10L, task.id(), 5L, com.nexusai.application.agent.tool.AgentUsage.EMPTY));

        String otherSession = "sess-notify-b2";
        // 别的会话（未创建任务）的 turn drain → 捞不到
        assertThat(drainTaskNotification(queue, otherSession))
            .as("别的会话 turn 不得捞走创建会话的任务通知（3a 归属过滤）")
            .isNull();
        // 通知仍留在队列（未被别的会话消费）
        assertThat(queue.hasCommandsInQueue()).isTrue();
    }

    /**
     * WHY: 创建会话已结束（无该会话的 turn 消费），通知应回落全局执行器（CronIdleExecutor 语义，
     * 3c: sessionId 目标会话空闲/不存在 → 代跑），而非被任意会话捞走。生产方带 sessionId 后，
     * drainForQuery(本会话) 命中；无本会话 turn → 全局执行器经 mainThreadConsumable 消费。
     */
    @Test
    @DisplayName("BackgroundTaskRunner: 创建会话已结束 → 全局执行器（无会话主线程）消费")
    void backgroundTaskRunner_notification_fallsBackToGlobalWhenSessionEnded() {
        String createSession = "sess-notify-a3";
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(queue, new TaskFrameworkService(null));

        UUID agentId = UUID.randomUUID();
        BackgroundTask task = runner.registerAsyncAgent(agentId, "异步任务", "prompt", "general-purpose", null, createSession);
        runner.completeAsyncAgent(task.id(), AsyncAgentResult.success("summary", 1, 10L, task.id(), 5L, com.nexusai.application.agent.tool.AgentUsage.EMPTY));

        // 无会话主线程（currentSessionId=null，等价全局执行器 CronIdleExecutor）：捞 sessionId!=null 命令吗？
        // [3a] 具体会话 turn 才只捞本会话命令；无会话主线程只捞 sessionId==null 全局命令 → 通知仍在队列。
        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(true, null, null);
        assertThat(drained).as("无会话主线程不捞归会话命令（交 CronIdleExecutor 代跑，3c）").isEmpty();
        // 通知留在队列等 CronIdleExecutor（sessionId 归组目标会话空闲 → mainThreadConsumable 通过）
        assertThat(queue.hasCommandsInQueue()).isTrue();
    }

    // ─────────────────── 1b. 经真实 StreamingToolExecutor 派发路径（F1 返工） ───────────────────

    /**
     * WHY（F1 返工 · 规则九）: 原实现 {@code captureCreatingSessionId()} 读 MDC，但 BashTool/
     * PowerShellTool/SubagentTool/SubagentExecutor 全部在 {@link StreamingToolExecutor} 的 tool-exec
     * 池线程执行（{@code CompletableFuture.runAsync(..., executor)}），ThreadLocal MDC 不跨线程、
     * 生产恒 null → 通知全归全局、功能 NO-OP。修复 = 生产方从 {@code ToolUseContext.sessionId()}
     * （ctx 字段，可靠源）提取透传。本用例驱动<b>真实 StreamingToolExecutor 派发路径</b>：探针工具在
     * 池线程（无 MDC）经 ctx.sessionId() 注册后台任务 → 锁死「生产路径 MDC null 但创建会话仍正确注入
     * 并路由到创建会话回合」。
     */
    @Test
    @DisplayName("StreamingToolExecutor 真实派发: 池线程 MDC 恒 null，但 ctx.sessionId() 正确注入任务并路由")
    void backgroundTaskRunner_poolThread_noMdc_carriesCtxSession() throws Exception {
        // 用真实 UUID 作会话（ctx.sessionId() 是 UUID，task.sessionId() 存 toString 串，
        // 与 drainForQuery 的 canonicalUuid 归一化同源可比）。
        String sessionUuid = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String createSession = sessionUuid.toString();
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(queue, new TaskFrameworkService(null));

        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<String> taskSessionSeen = new AtomicReference<>();
        AtomicReference<String> taskIdSeen = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        ToolRegistry registry = new ToolRegistry().register(
            probeTaskTool(runner, threadName, taskSessionSeen, taskIdSeen));
        StreamingToolExecutor exec = new StreamingToolExecutor(registry, pool, context(sessionUuid));
        try {
            exec.add(call("c1", "probe"));
            List<ToolResult> results = exec.getRemainingResults();
            assertThat(results).hasSize(1);
            assertThat(threadName.get())
                .as("探针工具必须在 tool-exec 池线程执行（非测试线程）—— 验证真实生产派发路径")
                .isNotEqualTo(Thread.currentThread().getName());
            // [批 3c] 语义消失（已登记待裁定）：原此处断言「池线程的 ambient 会话读取必须为 null
            //   —— 证明生产路径不依赖 MDC（F1 根因）」。ambient 会话槽已整类删除 ⇒ 该观察点与断言删除
            //   （在结构上恒成立）；「任务必须携带 ctx.sessionId()（可靠源）」的断言即为其活等价物。
            assertThat(taskSessionSeen.get())
                .as("池线程注册的任务必须携带 ctx.sessionId()（唯一可靠源，非任何 ambient 读取）")
                .isEqualTo(createSession);

            // 路由验证：创建会话 turn drain 注入（3a canonicalUuid 匹配）
            NotificationQueue.QueueItem drained = drainTaskNotification(queue, createSession);
            assertThat(drained).as("创建会话 turn 必须捞到经 StreamingToolExecutor 派发的任务完成通知").isNotNull();
            assertThat(drained.value()).contains(taskIdSeen.get());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 池线程探针工具：在 tool-exec 线程记录线程名，然后按生产方模式
     * （ctx.sessionId() → registerAsyncAgent）注册并完成一个后台任务。
     *
     * <p>[批 3c] 原还记录该池线程上的 ambient 会话读取（用于「生产路径不依赖 MDC」的反向断言）；
     * ambient 会话槽已整类删除 ⇒ 该观察点与其参数移除。
     */
    private static Tool probeTaskTool(BackgroundTaskRunner runner,
                                      AtomicReference<String> threadName,
                                      AtomicReference<String> taskSessionSeen,
                                      AtomicReference<String> taskIdSeen) {
        return new Tool() {
            @Override public String name() { return "probe"; }
            @Override public String description() { return "probe ctx.sessionId capture on tool thread"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public boolean isConcurrencySafe(JsonNode input) { return true; }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                return execute(call, null);
            }
            @Override public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
                threadName.set(Thread.currentThread().getName());
                String sid = ctx != null && ctx.sessionId() != null ? ctx.sessionId().toString() : null;
                UUID agentId = UUID.randomUUID();
                BackgroundTask task = runner.registerAsyncAgent(agentId, "探针任务", "prompt", "general-purpose", null, sid);
                taskSessionSeen.set(task.sessionId());
                taskIdSeen.set(task.id());
                runner.completeAsyncAgent(task.id(),
                    AsyncAgentResult.success("done", 1, 10L, task.id(), 5L, com.nexusai.application.agent.tool.AgentUsage.EMPTY));
                return ToolResult.success(call.id(), task.id());
            }
        };
    }

    // ─────────────────── 3. MainSessionBackgroundService ───────────────────

    @Test
    @DisplayName("MainSessionBackgroundService: 主会话后台化完成通知带创建会话 sessionId")
    void mainSessionBackgroundService_notification_carriesCreatingSession() {
        String createSession = "sess-main-d1";
        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue queue = new NotificationQueue();
        TaskFrameworkService framework = new TaskFrameworkService(sdk);
        com.nexusai.application.agent.tasks.MainSessionBackgroundService service =
            new com.nexusai.application.agent.tasks.MainSessionBackgroundService();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "taskFrameworkService", framework);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sdkEventQueue", sdk);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "notificationQueue", queue);

        String taskId = service.registerMainSessionTask("Background session", createSession, null);
        // 直接调完成通知（绕开 backgroundExecutor loop，聚焦通知携带 sessionId）
        service.completeMainSessionTask(taskId, true);

        NotificationQueue.QueueItem item = queue.dequeueAll().stream()
            .filter(i -> NotificationQueue.MODE_TASK_NOTIFICATION.equals(i.mode()))
            .findFirst().orElseThrow();
        assertThat(SessionKeys.canonicalUuid(item.sessionId()))
            .as("主会话后台化通知必须携带创建会话 sessionId（registerMainSessionTask 透传）")
            .isEqualTo(SessionKeys.canonicalUuid(createSession));
    }

    // ─────────── 4. H1 负向断言：LOCAL_AGENT 终态通知不得带 agentId（否则谁都捞不到）───────────

    /**
     * WHY（H1 · 规则九）: async agent（LOCAL_AGENT，{@code taskId===agentId}）的 query loop 在终态
     * 通知入队之前<b>已经退出</b>（{@code SubagentTool.java:3334 execute} 阻塞返回 → {@code :3340-3357}
     * 才 finalize）。若通知仍携带该 agentId，{@link NotificationQueue#drainForQuery} 的主线程规则
     * 「{@code cmd.agentId() != null} ⇒ 跳过」会让它<b>在唯一活着的消费者（该会话 turn）侧被跳过</b>，
     * 而子代理侧已死、无人 drain ⇒ 通知永久滞留（CC {@code killShellTasks.ts:70-75} 自陈
     * 「no consumer matches a dead agentId」）。用户可观测症状与「投给已退出的 worker」完全一致 ——
     * 所以只断言「第 4 实参 = null」不足以证明修复。
     *
     * <p>本用例断言<b>后果</b>：① 入队项 agentId 为 null；② 该通知<b>真能被消费</b>
     * （本会话 turn drain 捞得到 —— 这是修复要恢复的能力）；③ 「worker 自己的 agentId」不再是
     * 收件人（按它 drain 捞不到，防「改成指向另一个死 id」的伪修）。
     */
    @Test
    @DisplayName("H1 负向: LOCAL_AGENT 完成通知不带 agentId → 本会话 turn 能消费、worker 自己的 agentId 捞不到")
    void localAgentCompleteNotification_noAgentId_claimableBySessionTurnOnly() {
        String createSession = "sess-notify-h1-complete";
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(queue, new TaskFrameworkService(null));

        UUID agentId = UUID.randomUUID();
        BackgroundTask task = runner.registerAsyncAgent(
            agentId, "异步任务", "prompt", "general-purpose", null, createSession);
        assertThat(task.agentId()).as("前置条件：async agent 任务的 agentId 即任务自身（taskId===agentId）")
            .isEqualTo(agentId);
        runner.completeAsyncAgent(task.id(), AsyncAgentResult.success(
            "summary", 1, 10L, task.id(), 5L, com.nexusai.application.agent.tool.AgentUsage.EMPTY));

        NotificationQueue.QueueItem item = queue
            .peek(q -> NotificationQueue.MODE_TASK_NOTIFICATION.equals(q.mode())).orElseThrow();
        assertThat(item.agentId())
            .as("LOCAL_AGENT 终态通知不得携带 agentId —— 该 agent 的 loop 已退出，携带即为死收件人，"
                + "主线程侧被 drainForQuery 跳过 ⇒ 通知永久滞留（谁都取不到）")
            .isNull();
        assertThat(SessionKeys.canonicalUuid(item.sessionId()))
            .as("创建会话 sessionId 必须保留 —— 它是通知能被该会话 turn 捞到的唯一凭据")
            .isEqualTo(SessionKeys.canonicalUuid(createSession));

        // 后果断言 1：该 agent 自己（worker agentId）不再是收件人 —— 防「换一个死 id 填上」
        assertThat(queue.drainForQuery(true, agentId.toString(), createSession))
            .as("按 worker 自己的 agentId drain 必须捞不到 —— 它已退出，正是原缺陷的收件人")
            .isEmpty();

        // 后果断言 2（正门）：该会话 turn 能真正消费到 → 通知可达，不滞留
        NotificationQueue.QueueItem drained = drainTaskNotification(queue, createSession);
        assertThat(drained)
            .as("主线程以创建会话 turn 消费必须捞到 —— 通知真正可达（否则只是把「投给死 worker」"
                + "换成「谁都收不到」，用户症状一模一样）")
            .isNotNull();
        assertThat(drained.value()).contains(task.id());
        assertThat(queue.hasCommandsInQueue())
            .as("通知已被消费出队 —— 不得滞留在队列里等一个永不出现的消费者")
            .isFalse();
    }

    /**
     * WHY: 同 {@link #localAgentCompleteNotification_noAgentId_claimableBySessionTurnOnly} 的根因，
     * 但走<b>另一条终态路径</b> —— {@code killAsyncAgent}（被 TaskStop / 团队解散 / stopAll 外部杀）。
     * 两条路径同源同修，必须各自有独立证据（「改了代码」≠「在每个入口都生效」）。
     */
    @Test
    @DisplayName("H1 负向: LOCAL_AGENT 被杀通知不带 agentId → 本会话 turn 能消费、worker 自己的 agentId 捞不到")
    void localAgentKilledNotification_noAgentId_claimableBySessionTurnOnly() {
        String createSession = "sess-notify-h1-killed";
        NotificationQueue queue = new NotificationQueue();
        BackgroundTaskRunner runner = new BackgroundTaskRunner(queue, new TaskFrameworkService(null));

        UUID agentId = UUID.randomUUID();
        BackgroundTask task = runner.registerAsyncAgent(
            agentId, "异步任务", "prompt", "general-purpose", null, createSession);
        assertThat(runner.killAsyncAgent(task.id()))
            .as("前置条件：RUNNING 的 async agent 任务可被真杀（终态通知走 killAsyncAgent 路径）").isTrue();

        NotificationQueue.QueueItem item = queue
            .peek(q -> NotificationQueue.MODE_TASK_NOTIFICATION.equals(q.mode())).orElseThrow();
        assertThat(item.agentId())
            .as("LOCAL_AGENT 被杀通知不得携带 agentId —— 与完成路径同一根因（收件人已死）")
            .isNull();

        assertThat(queue.drainForQuery(true, agentId.toString(), createSession))
            .as("按 worker 自己的 agentId drain 必须捞不到")
            .isEmpty();
        assertThat(drainTaskNotification(queue, createSession))
            .as("本会话 turn 必须能消费到被杀通知 —— 否则 kill 结果对用户永久不可见")
            .isNotNull();
    }

    // ─────────────────── helpers ───────────────────

    /** 以指定会话的 turn drain 一条 task-notification（3a 语义），无则返回 null。 */
    private static NotificationQueue.QueueItem drainTaskNotification(NotificationQueue queue, String sessionKey) {
        List<NotificationQueue.QueueItem> drained = queue.drainForQuery(true, null, sessionKey);
        return drained.stream().filter(i -> NotificationQueue.MODE_TASK_NOTIFICATION.equals(i.mode()))
            .findFirst().orElse(null);
    }

    private static ToolUseBlock call(String id, String name) {
        return new ToolUseBlock(id, name, JSON.createObjectNode());
    }

    /** 固定创建会话 sessionId 的 ToolUseContext（对齐 ProjectRootPropagationTest 构造形态）。 */
    private static ToolUseContext context(String sessionId) {
        return ToolUseContext.of(
            UUID.randomUUID(), sessionId, PermissionMode.DEFAULT,
            List.of(), "", new AbortController(), List.of(),
            null, PermissionMode.DEFAULT,
            Map.of(), false, "",
            java.nio.file.Paths.get("."),
            current -> java.util.Collections.unmodifiableSet(java.util.Set.of()));
    }
}
