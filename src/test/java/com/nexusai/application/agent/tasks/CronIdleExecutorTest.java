package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
import com.nexusai.application.chat.ChatService;
import com.nexusai.application.chat.SlashCommandInterceptor;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [CRON-D2] CronIdleExecutor · idle 自动执行（对齐 CC useQueueProcessor.ts + queueProcessor.ts:52-87）
 *
 * <p>WHY（规则九 · 测试验证意图）: WF-D 核心断裂 R-1 —— 队列有主线程命令且目标 session 空闲时
 * <b>必须</b> 自动启动 agent_loop；活动 turn 内<b>绝不</b>打断（对齐 CC isQueryActive 三闸）。
 * <ol>
 *   <li>队列非空 + 空闲 → poll 消费并启动（批量同 mode 语义）</li>
 *   <li>活动 turn（session 运行中）→ poll 跳过，队列不变</li>
 *   <li>slash/bash 单条 dequeue vs 同 mode 批量 dequeueAllMatching</li>
 * </ol>
 */
class CronIdleExecutorTest {

    private NotificationQueue queue;
    private CronIdleExecutor executor;

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        // 确保静态 RUNNING_SESSIONS 干净（GLOBAL_SESSION 归零）
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
        // CRON-D5 改2 测试会注入 MDC sessionId —— 清空防跨测试线程污染
        RequestContext.clear();
    }

    @Test
    @DisplayName("队列非空+空闲 → poll 消费批量同 mode 命令并启动")
    void pollConsumesAndStartsWhenIdle() {
        queue.enqueue(new QueueItem("提示词A", "prompt", Priority.LATER, null, true, NotificationQueue.WORKLOAD_CRON));
        queue.enqueue(new QueueItem("提示词B", "prompt", Priority.LATER, null, true, NotificationQueue.WORKLOAD_CRON));
        List<QueueItem> consumed = new ArrayList<>();
        AtomicInteger startCount = new AtomicInteger();

        boolean processed = executor.poll(commands -> {
            startCount.incrementAndGet();
            consumed.addAll(commands);
        });

        assertThat(processed).isTrue();
        assertThat(startCount.get()).isEqualTo(1);      // 批量一次性启动
        assertThat(consumed).extracting(QueueItem::value)
            .containsExactlyInAnyOrder("提示词A", "提示词B");
        assertThat(queue.size()).isZero();              // 消费后队列清空
    }

    @Test
    @DisplayName("活动 turn（session 运行中）→ poll 跳过，队列不变")
    void pollSkipsWhenSessionBusy() {
        queue.enqueue(new QueueItem("提示词A", "prompt", Priority.LATER, null, true, NotificationQueue.WORKLOAD_CRON));
        LlmAgentLoop.markRunning(CronIdleExecutor.GLOBAL_SESSION_KEY);  // 模拟活动 agent_loop
        AtomicInteger startCount = new AtomicInteger();

        boolean processed = executor.poll(commands -> startCount.incrementAndGet());

        assertThat(processed).isFalse();
        assertThat(startCount.get()).isZero();          // 活动 turn 不打断
        assertThat(queue.size()).isEqualTo(1);          // 队列原样保留
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    @Test
    @DisplayName("队列无主线程命令（仅 subagent）→ poll 跳过")
    void pollSkipsWhenOnlySubagentCommands() {
        queue.enqueue(new QueueItem("子agent任务", "prompt", Priority.LATER,
            java.util.UUID.randomUUID().toString(), false, null));
        AtomicInteger startCount = new AtomicInteger();

        boolean processed = executor.poll(commands -> startCount.incrementAndGet());

        assertThat(processed).isFalse();
        assertThat(startCount.get()).isZero();
        assertThat(queue.size()).isEqualTo(1);          // subagent 项留给对应 agent
    }

    @Test
    @DisplayName("slash 单条 dequeue → 每次 poll 只消费 1 条")
    void slashCommandProcessedOneAtATime() {
        queue.enqueue(new QueueItem("/status", "prompt", Priority.NOW, null, false, null));
        queue.enqueue(new QueueItem("/context", "prompt", Priority.NOW, null, false, null));
        AtomicInteger firstCallSize = new AtomicInteger(-1);
        AtomicInteger secondCallSize = new AtomicInteger(-1);

        boolean first = executor.poll(commands -> firstCallSize.set(commands.size()));
        boolean second = executor.poll(commands -> secondCallSize.set(commands.size()));

        assertThat(first).isTrue();
        assertThat(firstCallSize.get()).isEqualTo(1);   // slash 单条
        assertThat(second).isTrue();
        assertThat(secondCallSize.get()).isEqualTo(1);  // 第二次再消费 1 条
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("同 mode 批量 vs 异 mode 保留 → 批量只抽 targetMode")
    void batchConsumesSameModeOnly() {
        queue.enqueue(new QueueItem("prompt-A", "prompt", Priority.NOW, null, true, NotificationQueue.WORKLOAD_CRON));
        queue.enqueue(new QueueItem("prompt-B", "prompt", Priority.NOW, null, true, NotificationQueue.WORKLOAD_CRON));
        queue.enqueue(new QueueItem("通知X", "task-notification", Priority.LATER, null, true, null));
        List<QueueItem> consumed = new ArrayList<>();

        boolean processed = executor.poll(consumed::addAll);

        assertThat(processed).isTrue();
        assertThat(consumed).extracting(QueueItem::value)
            .containsExactlyInAnyOrder("prompt-A", "prompt-B");
        assertThat(queue.size()).isEqualTo(1);          // 异 mode 项留在队列
        assertThat(queue.peek(q -> true).orElseThrow().value()).isEqualTo("通知X");
    }

    @Test
    @DisplayName("队列空 → poll 返回 false 不启动")
    void pollNoOpsWhenQueueEmpty() {
        AtomicInteger startCount = new AtomicInteger();

        boolean processed = executor.poll(commands -> startCount.incrementAndGet());

        assertThat(processed).isFalse();
        assertThat(startCount.get()).isZero();
    }

    @Test
    @DisplayName("OD-D7 门关 + 仅 cron workload → poll 不消费 cron（逐条 skip，队列原样保留）")
    void pollSkipsWhenCronGateClosed() {
        // WHY（OD-D7 收窄后机制）: 原「门关 → poll() 开头整队列 return false」自创过宽 —— CC 队列消费
        // （queueProcessor.ts:52-87 / useQueueProcessor.ts:48-67）零 cron 引用，isKilled 只 gate 调度
        // tick（cronScheduler.ts:231）。收窄后 = mainThreadConsumable 谓词逐条跳过 WORKLOAD_CRON 项：
        // 队列只有 cron → peek 无可消费 → poll 返回 false，cron 项留队列（存量项等门开 / producer gate
        // 已停 fire）。生产方停止语义仍由 TestJob.fire producer gate（OPD-Cron-07-h）保证。
        queue.enqueue(new QueueItem("提示词A", "prompt", Priority.LATER, null, true, NotificationQueue.WORKLOAD_CRON));
        CronEnabledGates gates = new CronEnabledGates(false, true);   // agentTriggerCron=false → isKairosCronEnabled=false
        ReflectionTestUtils.setField(executor, "cronGates", gates);
        AtomicInteger startCount = new AtomicInteger();

        boolean processed = executor.poll(commands -> startCount.incrementAndGet());

        assertThat(processed).isFalse();
        assertThat(startCount.get()).isZero();          // 门关 cron 项不消费不启动
        assertThat(queue.size()).isEqualTo(1);          // cron 项原样保留（谓词 skip，非整队列冻结）
    }

    @Test
    @DisplayName("OD-D7 门关 + 仅 busy-queued/task-notification → 照常空闲消费（cron 开关与队列消费解耦）")
    void pollConsumesNonCronWhenCronGateClosed() {
        // WHY: OD-D7 核心意图 —— 关 cron 只停 cron 调度（producer gate），队列消费照常服务非 cron
        //   命令（CC queueProcessor.ts 零 cron 引用）。门关后 busy-queued（用户排队 prompt）与
        //   task-notification（后台任务完成通知）仍须空闲消费；若门关冻结整段 poll，用户排队消息会
        //   滞留到门重开才被处理（2026-09-04 拍板修复）。
        CronEnabledGates gates = new CronEnabledGates(false, true);   // agentTriggerCron=false → isKairosCronEnabled=false
        ReflectionTestUtils.setField(executor, "cronGates", gates);
        queue.enqueue(new QueueItem("排队用户消息", "prompt", Priority.LATER, null,
            false, "busy-queued"));                                   // busy-queued: workload != cron
        queue.enqueue(new QueueItem("后台完成通知", "task-notification", Priority.LATER, null,
            true, null));
        AtomicInteger startCount = new AtomicInteger();
        List<QueueItem> consumed = new ArrayList<>();

        // 两种 mode 分属不同 batch（peek 取最高优先 / FIFO 先插入者）→ 两次 poll 各消费一类
        boolean first = executor.poll(commands -> {
            startCount.incrementAndGet();
            consumed.addAll(commands);
        });
        boolean second = executor.poll(commands -> {
            startCount.incrementAndGet();
            consumed.addAll(commands);
        });

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(startCount.get()).isEqualTo(2);
        assertThat(consumed).extracting(QueueItem::value)
            .containsExactlyInAnyOrder("排队用户消息", "后台完成通知");
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("OD-D7 门关 + 混排 cron 队头 LATER → 非 cron 不被队头 cron 阻塞（peek 全队列扫无饿死）")
    void pollCronHeadDoesNotBlockNonCronWhenGateClosed() {
        // WHY: 原 poll 顶部整队列 gate 删除后，peek 仍须证明不被队头 cron 饿死 —— NotificationQueue.peek
        //   全队列线性扫「匹配谓词的最高优先级」，队头 cron（workload=cron）被谓词 skip → 后方非 cron
        //   照常命中。若谓词未正确 skip cron，peek 会返回队头 cron 而 poll 把它当可消费项启动（错）。
        CronEnabledGates gates = new CronEnabledGates(false, true);   // agentTriggerCron=false → isKairosCronEnabled=false
        ReflectionTestUtils.setField(executor, "cronGates", gates);
        // 先入队 cron（队头，LATER），后入队 task-notification（LATER 同级 FIFO 靠后）
        queue.enqueue(new QueueItem("队头cron", "prompt", Priority.LATER, null, true, NotificationQueue.WORKLOAD_CRON));
        queue.enqueue(new QueueItem("通知不被阻塞", "task-notification", Priority.LATER, null, true, null));
        List<QueueItem> consumed = new ArrayList<>();

        boolean processed = executor.poll(consumed::addAll);

        assertThat(processed).isTrue();
        assertThat(consumed).extracting(QueueItem::value).containsExactly("通知不被阻塞");
        assertThat(queue.size()).isEqualTo(1);          // cron 队头原样保留
        assertThat(queue.peek(q -> true).orElseThrow().value()).isEqualTo("队头cron");
    }

    // ============ CRON-F5: 启动表面 missed（ApplicationReadyEvent → surfaceMissedAtStartup） ============

    @Test
    @DisplayName("启动钩子：missed 通知入队 mode=prompt/isMeta=true/workload=WORKLOAD_CRON/priority=LATER（对齐 CC enqueueForLead useScheduledTasks.ts:71-82）")
    void startupSurfaceEnqueuesPromptMetaCron() {
        // WHY: CC load(initial) missed.length>0 → onFire(buildMissedTaskNotification) →
        // enqueueForLead（useScheduledTasks.ts:71-82）：mode='prompt', priority='later', isMeta=true,
        // workload=WORKLOAD_CRON。isMeta 系统生成消息（UI 隐藏但模型可见）；workload=WORKLOAD_CRON
        // 使 cron 发起请求按低 QoS 计费。若 Java 丢失任一字段，队列消费语义或计费归属即偏离 CC。
        ScheduleService fake = mock(ScheduleService.class);
        when(fake.surfaceMissedForStartup(anyLong()))
            .thenReturn(Optional.of(
                "The following one-shot scheduled task was missed while Claude was not running. "
                + "Do NOT execute this prompt yet. First use the AskUserQuestion tool to ask whether to run it now. "
                + "Only execute if the user confirms."));
        ReflectionTestUtils.setField(executor, "scheduleService", fake);

        executor.surfaceMissedAtStartup();

        assertThat(queue.size()).isEqualTo(1);
        QueueItem item = queue.peek(q -> true).orElseThrow();
        assertThat(item.value()).contains("AskUserQuestion");
        assertThat(item.mode()).isEqualTo(NotificationQueue.MODE_PROMPT);
        assertThat(item.priority()).isEqualTo(Priority.LATER);
        assertThat(item.isMeta()).isTrue();
        assertThat(item.workload()).isEqualTo(NotificationQueue.WORKLOAD_CRON);
        assertThat(item.agentId()).as("全局主线程队列（CC enqueueForLead agentId 缺省，无 session 路由）").isNull();
    }

    @Test
    @DisplayName("启动钩子：cronGates 关闭 → 不表面不入队（对齐 CC useScheduledTasks.ts:61 gate）")
    void startupSkipsWhenCronGateClosed() {
        // WHY: useScheduledTasks.ts:61 `if (!isKairosCronEnabled()) return` —— 启动 gate。
        // 功能关闭时不得启动调度器（含 missed 表面），否则已关闭的 cron 仍会在重启后骚扰用户。
        ScheduleService fake = mock(ScheduleService.class);
        ReflectionTestUtils.setField(executor, "scheduleService", fake);
        CronEnabledGates gates = new CronEnabledGates(false, true);   // agentTriggerCron=false → isKairosCronEnabled=false
        ReflectionTestUtils.setField(executor, "cronGates", gates);

        executor.surfaceMissedAtStartup();

        verify(fake, never()).surfaceMissedForStartup(anyLong());
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("启动钩子：无 missed → 不入队")
    void startupNoEnqueueWhenNoMissed() {
        ScheduleService fake = mock(ScheduleService.class);
        when(fake.surfaceMissedForStartup(anyLong())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(executor, "scheduleService", fake);

        executor.surfaceMissedAtStartup();

        assertThat(queue.size()).isZero();
        verify(fake).surfaceMissedForStartup(anyLong());
    }

    // ============ CRON-B3-1: 启动清扫 SESSION（ApplicationReadyEvent → sweepSessionTasksAtStartup） ============

    @Test
    @DisplayName("启动清扫：ScheduleService.sweepSessionTasksAtStartup(Set.of()) 被调用（全量清扫，不 gate cronGates）")
    void startupSweepCallsScheduleServiceWithEmptyActive() {
        // WHY: 决策 #7 OPD-Cron-D5 —— Java SESSION 仍落库，重启后残留行会复活 fire，启动必须全量清扫
        // （CC SESSION=随进程死，cronTasks.ts:211-213）。active=Set.of() = 启动时 RUNNING_SESSIONS 为空；
        // 不 gate cronGates（CC 进程死亡无 gate，孤儿必须清）。
        ScheduleService fake = mock(ScheduleService.class);
        ReflectionTestUtils.setField(executor, "scheduleService", fake);
        // cronGates 不注入（null）→ 不 gate（C1 判断点推荐语义）
        ReflectionTestUtils.setField(executor, "cronGates", null);

        executor.sweepSessionTasksAtStartup();

        verify(fake).sweepSessionTasksAtStartup(java.util.Set.of());
    }

    // ============ CRON-D5: 会话上下文归组（改2 MDC 恢复 + 改3 真实 UUID + poll gate 目标会话） ============

    @Test
    @DisplayName("CRON-D5 改2+改3+F2: 工具路径（派生 UUID 串）→ run 期间 MDC=原始键 sess-xxx + RunRequest=派生 UUID + finally 清理")
    void runOneAgentLoopRestoresSessionContext() throws Exception {
        // WHY: cronExecutor 线程无 MDC（ThreadLocal 不跨线程）→ cron 触发的 agent_loop 工作目录域
        // 全回落 user.dir（跨会话 cwd 错位）。CRON-D5 消费线程 setSession 恢复 → CwdResolution 解析到
        // 创建会话 boundProject；RunRequest.sessionId 用真实 UUID → markRunning/isSessionRunning 归组
        // 创建会话（对齐 CC 单进程 ambient"任务即属创建会话"）。finally 清理防 cronExecutor 线程串台。
        // F2 返工: CronCreateTool 落库的是 ToolUseContext.sessionId().toString()=派生 UUID，而
        // SessionProjectRoot（boundProject 层）以原始键 "sess-xxx" 为键 —— MDC 必须反解回原始键，
        // 否则 L3 boundProject 恒 MISS 回落 user.dir。旧 fixture 用随机 UUID 断言 MDC==UUID 恰固化了
        // 该错误行为（规则 9），本轮改用真实派生 UUID 断言 MDC==原始键。
        String originalKey = "sess-a1b2c3d4";
        String sessionId = originalKey;
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        // 捕获 loop.run(req) 调用时刻的 MDC sessionId（run 内 doRun 才会消费，此处只验调用前已注入）
        AtomicReference<String> mdcDuringRun = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringRun.set(RequestContext.sessionId());
            return null;
        }).when(loop).run(any(RunRequest.class));

        QueueItem cmd = new QueueItem("运行项目测试", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        RunRequest req = captor.getValue();
        // 改3: SESSION scope cron → 创建会话真实 UUID（非 GLOBAL 常量）
        assertThat(req.sessionId()).isEqualTo(sessionId);
        // F2: run() 调用期间 MDC 已恢复为原始键（boundProject/SessionProjectRoot 层可命中）
        assertThat(mdcDuringRun.get()).isEqualTo(originalKey);
        // 改2 finally: run 返回后 MDC 已清理（cronExecutor 线程复用防串台）
        assertThat(RequestContext.sessionId()).isNull();
    }

    @Test
    @DisplayName("CRON-D5 F2 零回归: 不可反解 UUID（随机 UUID）→ MDC 原值兜底（不改写既有行为）")
    void runOneAgentLoopFallsBackToRawWhenUuidNotReversible() throws Exception {
        // WHY: 非 "sess-xxx" 派生 UUID（测试/DB 脏行/兼容路径）无法反解回原始键 → MDC 用原值兜底，
        // 保证既有行为不变（不凭空注入 "sess-" 也不丢 MDC）。F2 只修复可反解的真实会话场景。
        String sessionId = UUID.randomUUID().toString();
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        AtomicReference<String> mdcDuringRun = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringRun.set(RequestContext.sessionId());
            return null;
        }).when(loop).run(any(RunRequest.class));

        QueueItem cmd = new QueueItem("运行项目测试", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        // 不可反解 → MDC 原值兜底（等于传入的随机 UUID 串），RunRequest 仍是该 UUID
        assertThat(mdcDuringRun.get()).isEqualTo(sessionId);
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("CRON-D5 F2: HTTP 创建路径（原始键 sess-xxx）→ resolveSessionUuid 归一为派生 UUID（非 GLOBAL 兜底）")
    void runOneAgentLoopHttpSessionKeyResolvesDerivedUuid() throws Exception {
        // WHY: ScheduleController.create 落库 req.sessionId()=前端原始键 "sess-xxx"；旧 resolveSessionUuid
        // 用裸 UUID.fromString 对 "sess-xxx" 抛异常 → warn + 回落 GLOBAL → RunRequest.sessionId=GLOBAL，
        // RUNNING_SESSIONS/markRunning 归组失效（退化为 R0 前旧行为）。F2 返工后走 parseSessionUuid
        // 归一化 → 派生 UUID，两条创建路径（工具派生 UUID / HTTP 原始键）消费侧行为一致。
        String originalKey = "sess-1234abcd";
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        AtomicReference<String> mdcDuringRun = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringRun.set(RequestContext.sessionId());
            return null;
        }).when(loop).run(any(RunRequest.class));

        QueueItem cmd = new QueueItem("HTTP 创建任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, originalKey);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        // F2: "sess-xxx" 归一为派生 UUID（非 GLOBAL_SESSION_UUID 兜底）→ RUNNING_SESSIONS 归组创建会话
        assertThat(captor.getValue().sessionId()).isEqualTo(originalKey);
        assertThat(captor.getValue().sessionId()).isNotEqualTo(CronIdleExecutor.GLOBAL_SESSION_KEY);
        // MDC 已是原始键，原样透传
        assertThat(mdcDuringRun.get()).isEqualTo(originalKey);
    }

    @Test
    @DisplayName("CRON-D5 F2: 仅绑定项目（未 cd）→ cron 消费 cwd 解析到创建会话 boundProject（非 user.dir）")
    void cronCwdResolvesBoundProjectForOnlyBoundSession() throws Exception {
        // WHY（规则九）: F2 核心 —— CronCreateTool 落库派生 UUID，SessionProjectRoot 以 "sess-xxx" 为键，
        // 旧消费 MDC=UUID → L3 boundProject 恒 MISS → 回落 user.dir（跨会话 cwd 错位）。F2 返工后：
        // ① runOneAgentLoop 把 MDC 反解回原始键 → CwdResolution.getCwd()（无参，MDC 基准）命中 boundProject；
        // ② CwdResolution.getCwd(派生 UUID)（BashTool 显式 ctx.sessionId().toString() 基准）经双键解析
        //    同样命中 boundProject —— 两条消费路径都归组创建会话 boundProject。
        String originalKey = "sess-abcd5678";
        String derivedUuid = originalKey;
        java.nio.file.Path tmp = Files.createTempDirectory("cron-d5-bound");
        // 用与 CwdResolution.normalizeCwd 相同的归一化结果登记，断言可比对
        String boundProject = CwdResolution.normalizeCwd(tmp.toAbsolutePath().toString());
        SessionProjectRoot.setForSession(originalKey, boundProject);
        try {
            LlmAgentLoop loop = mock(LlmAgentLoop.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
            when(provider.getObject()).thenReturn(loop);
            ReflectionTestUtils.setField(executor, "loopProvider", provider);

            AtomicReference<String> cwdByMdc = new AtomicReference<>();
            AtomicReference<String> cwdByUuid = new AtomicReference<>();
            doAnswer(inv -> {
                // 消费线程（无 override）经 MDC 解析 + BashTool 显式 UUID 解析两条路径
                cwdByMdc.set(CwdResolution.getCwd());
                cwdByUuid.set(CwdResolution.getCwd(derivedUuid));
                return null;
            }).when(loop).run(any(RunRequest.class));

            QueueItem cmd = new QueueItem("运行项目测试", "prompt", Priority.LATER, null,
                null, true, NotificationQueue.WORKLOAD_CRON, false, null, derivedUuid);

            ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

            assertThat(cwdByMdc.get())
                .as("MDC 基准：run 期间 CwdResolution.getCwd() 应解析到创建会话 boundProject（非 user.dir）")
                .isEqualTo(boundProject);
            assertThat(cwdByUuid.get())
                .as("BashTool 基准：CwdResolution.getCwd(派生 UUID) 应经双键解析命中 boundProject")
                .isEqualTo(boundProject);
        } finally {
            SessionProjectRoot.clearSession(originalKey);
            RequestContext.clear();
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("批次X Q2: DURABLE 任务（sessionId=null + boundProject 锚）→ run 期间 CwdResolution.getCwd 解析到创建项目（非 user.dir）")
    void runOneAgentLoopPersistentRestoresBoundProjectContext() throws Exception {
        // WHY（规则九）：CC durable 任务的项目锚=文件位置 <projectRoot>/.claude/scheduled_tasks.json
        // （cronTasks.ts:74-83），一个项目一个文件=项目级作用域；fire 复用已绑定会话 cwd（不重取）。
        // Java 全局单表把锚显式落 V23 bound_project 列，fire 时经 QueueItem.boundProject 透传，
        // runOneAgentLoop 用 CwdResolution.runWithCwdOverride（对齐 CC cwd.ts:12-14）注入执行线程
        // cwd override → CwdResolution.getCwd 四层解析（override→sessionCwd→boundProject→user.dir）
        // 命中 override 层解析到创建项目。若不加 override，sessionId=null → 回落 user.dir
        // （跨会话 cwd 错位：项目 A 下创建的 durable cron 重启后 fire 跑在 JVM 启动目录）。
        java.nio.file.Path tmp = Files.createTempDirectory("cron-x-persistent");
        String boundProject = CwdResolution.normalizeCwd(tmp.toAbsolutePath().toString());
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        AtomicReference<String> cwdDuringRun = new AtomicReference<>();
        AtomicReference<String> mdcDuringRun = new AtomicReference<>();
        doAnswer(inv -> {
            cwdDuringRun.set(CwdResolution.getCwd());
            mdcDuringRun.set(RequestContext.sessionId());
            return null;
        }).when(loop).run(any(RunRequest.class));

        // DURABLE: sessionId=null + boundProject 锚（TestJob fire 经 QueueItem 透传）
        QueueItem cmd = new QueueItem("持久化项目任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, null, boundProject);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        assertThat(cwdDuringRun.get())
            .as("DURABLE 任务 run 期间 CwdResolution.getCwd 必须解析到创建项目（非 user.dir）")
            .isEqualTo(boundProject);
        assertThat(mdcDuringRun.get())
            .as("DURABLE 任务 sessionId=null → MDC 不注入（项目锚走 boundProject override，不走 sessionId）")
            .isNull();
        assertThat(RequestContext.sessionId())
            .as("finally 清理后 MDC 仍为 null（防 cronExecutor 线程串台）")
            .isNull();
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("批次X Q2: DURABLE 无会话直建（boundProject=null）→ 兜底 user.dir 解析（已知差异登记）")
    void runOneAgentLoopPersistentNullBoundProjectFallsBackUserDir() throws Exception {
        // WHY（规则九）：CC 所有 durable 任务都在会话里创建（B 探查 §7.3），Java 无会话 REST 直建
        // DURABLE（sessionId=null + boundProject=null）是已知差异 → fire 兜底 user.dir（现状不变）。
        // 不加 override 不得抛异常，也不得凭空注入项目上下文（保持既有行为）。
        String userDir = CwdResolution.normalizeCwd(System.getProperty("user.dir"));
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        AtomicReference<String> cwdDuringRun = new AtomicReference<>();
        doAnswer(inv -> {
            cwdDuringRun.set(CwdResolution.getCwd());
            return null;
        }).when(loop).run(any(RunRequest.class));

        QueueItem cmd = new QueueItem("无会话持久化任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, null, null);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        assertThat(cwdDuringRun.get())
            .as("无会话直建 DURABLE（boundProject=null）→ CwdResolution 兜底 user.dir（已知差异）")
            .isEqualTo(userDir);
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    @Test
    @DisplayName("CRON-D5 改3: DURABLE 无项目锚直建（sessionId=null + boundProject=null）→ RunRequest 回落 GLOBAL_SESSION_UUID + MDC 不注入（零回归）")
    void runOneAgentLoopPersistentFallsBackToGlobalUuid() throws Exception {
        // WHY: DURABLE 无项目锚直建（REST 直建 sessionId=null + boundProject=null，无创建会话可归）→
        // 保持现状回落全局会话/user.dir（GLOBAL 兜底，CRON-D5 只解决 SESSION scope 归组）。改3 必须
        // 保证这条兼容路径不崩、不误注入 MDC。注意 [cron-durable-session-fire]：DURABLE 有项目锚
        // （boundProject!=null）时已关/无会话 → RunRequest.sessionId=null（headless 无 transcript），
        // 不经本 GLOBAL 兜底（见 runOneAgentLoopDurableCreatingSessionClosed_headlessNoTranscript）。
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        AtomicReference<String> mdcDuringRun = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringRun.set(RequestContext.sessionId());
            return null;
        }).when(loop).run(any(RunRequest.class));

        QueueItem cmd = new QueueItem("持久化任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, null);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(CronIdleExecutor.GLOBAL_SESSION_KEY);
        assertThat(mdcDuringRun.get()).isNull();
    }

    @Test
    @DisplayName("CRON-D5 改3: poll gate 按目标会话判定（peek 到的 cmd.sessionId 运行中 → 跳过）")
    void pollSkipsWhenTargetSessionBusy() {
        // WHY: 改3 后 cron loop 跑在创建会话 UUID，三闸 1 isQueryActive 必须判该会话是否运行中，
        // 避免该会话活跃 turn 时又起一轮 cron loop 打断（对齐 CC useQueueProcessor.ts:49，按实际
        // 执行会话而非全局常量）。F2: fixture 用真实派生 UUID（工具路径落库形态），resolveSessionUuid
        // 归一后与 markRunning 注册键一致。
        String sessionId = "sess-cafe1234";
        queue.enqueue(new QueueItem("提示词A", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId));
        LlmAgentLoop.markRunning(sessionId);  // 目标会话活跃 turn
        AtomicInteger startCount = new AtomicInteger();

        boolean processed = executor.poll(commands -> startCount.incrementAndGet());

        assertThat(processed).isFalse();
        assertThat(startCount.get()).isZero();
        assertThat(queue.size()).isEqualTo(1);          // 队列原样保留
        LlmAgentLoop.markIdle(sessionId);
    }

    @Test
    @DisplayName("CRON-D5 改3: 仅全局会话运行、目标会话空闲 → poll 放行（gate 按目标不按 GLOBAL）")
    void pollProceedsWhenOnlyGlobalBusy() {
        // WHY: 旧 gate 判 GLOBAL_SESSION_UUID —— 全局会话（另一会话 agent_loop）运行时会误阻断
        // 目标会话的 cron 消费。改3 后按目标会话判定，全局忙碌不应阻塞归组到创建会话的 cron 任务。
        // F2: fixture 用真实派生 UUID（工具路径落库形态）。
        String sessionId = "sess-beef5678";
        queue.enqueue(new QueueItem("提示词A", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId));
        LlmAgentLoop.markRunning(CronIdleExecutor.GLOBAL_SESSION_KEY);  // 仅全局会话运行
        List<QueueItem> consumed = new ArrayList<>();

        boolean processed = executor.poll(commands -> consumed.addAll(commands));

        assertThat(processed).isTrue();
        assertThat(consumed).extracting(QueueItem::value).containsExactly("提示词A");
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
    }

    // ============ 批次乙 cron-mem: DURABLE 项目身份注入（boundProject 整体注入 loop） =====


    @Test
    @DisplayName("批次乙 cron-mem: DURABLE fire（boundProject 锚）→ run 前注入项目身份 override + finally 清空")
    void runOneAgentLoopPersistentInjectsProjectIdentityOverride() throws Exception {
        // WHY（规则九）: CC durable cron fire 回合 memory/workspaceDir 归属创建项目（useScheduledTasks.ts:71-82
        // fire 注入创建会话 + paths.ts:223-235 getAutoMemPath=projectRoot git root）。批次X 只对齐了 cwd
        // （runWithCwdOverride）；批次乙补 boundProject 作为【项目身份】整体注入 loop（setCronProjectRootOverride
        // → resolveSessionProjectRoot 首行命中 → workspaceDir + AutoMemPaths ThreadLocal 锚 boundProject，
        // 不落 CLAUDE_PROJECT_DIR env ?? config-home 全局）。若漏注入，DURABLE fire 的 memory 读全局
        // （跨项目 memory 错位：项目 A 创建的 durable cron fire 读全局/项目 B 记忆）。顺序断言锁定
        // 「先注入 override → 再 run → finally 清空」（防线程池复用串台）。
        java.nio.file.Path tmp = Files.createTempDirectory("cron-mem-persistent");
        String boundProject = CwdResolution.normalizeCwd(tmp.toAbsolutePath().toString());
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        // DURABLE: sessionId=null + boundProject 锚（TestJob fire 经 QueueItem 透传）
        QueueItem cmd = new QueueItem("持久化项目任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, null, boundProject);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(loop);
        inOrder.verify(loop).setCronProjectRootOverride(boundProject);
        inOrder.verify(loop).run(any(RunRequest.class));
        inOrder.verify(loop).clearCronProjectRootOverride();
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("批次乙 cron-mem: SESSION fire（sessionId 非空、boundProject=null）→ 不注入项目身份 override（SESSION 零改动）")
    void runOneAgentLoopSessionDoesNotInjectProjectIdentityOverride() throws Exception {
        // WHY（规则九）: override 仅在 DURABLE boundProject 非空时置（ScheduleService:252-254 仅
        // DURABLE 存 bound_project 列，SESSION 任务 boundProject=null）。SESSION fire 走既有
        // sessionId 恢复路径（MDC/boundProject 层），不得触发 per-run override 注入 —— 否则 SESSION
        // 路径被 override 劫持，违反批次乙「SESSION 零改动」设计边界。clearCronProjectRootOverride 恒在
        // finally 执行（防御性清空，prototype 双保险）。
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        String sessionId = "sess-mem-session";
        QueueItem cmd = new QueueItem("会话任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        verify(loop, never()).setCronProjectRootOverride(any());
        verify(loop).run(any(RunRequest.class));
        verify(loop).clearCronProjectRootOverride();
    }

    @Test
    @DisplayName("批次乙 cron-mem: DURABLE 无会话直建（boundProject=null）→ 不注入 override（兜底 GLOBAL，已知差异零回归）")
    void runOneAgentLoopPersistentNullBoundProjectDoesNotInjectOverride() throws Exception {
        // WHY: 无会话 REST 直建 DURABLE（sessionId=null + boundProject=null）是已知差异（CC 所有
        // durable 任务都在会话里创建，B-memory-path-probe §4.4）→ 无项目锚可注入，override 不得凭空
        // 设置（保持既有兜底 GLOBAL 行为）。clear 恒在 finally 执行。
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        QueueItem cmd = new QueueItem("无会话持久化任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, null, null);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        verify(loop, never()).setCronProjectRootOverride(any());
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(CronIdleExecutor.GLOBAL_SESSION_KEY);
        verify(loop).clearCronProjectRootOverride();
    }

    // ============ [cron-durable-session-fire] DURABLE fire 归创建会话（去 per-task 虚拟键） ============

    @Test
    @DisplayName("DURABLE 创建会话存活 → RunRequest 用创建会话 UUID（transcript 归创建会话文件），不再注入虚拟键 override")
    void runOneAgentLoopDurableCreatingSessionAlive_usesCreatingSessionUuid() throws Exception {
        // WHY（规则九）: DURABLE fire 归创建会话（对齐 CC fire 注入活跃会话 useScheduledTasks.ts:71-82）。
        // 创建会话存活（sessionMapper 未注入 fail-open / DB 行存在）→ RunRequest.sessionId=创建会话
        // UUID → AgentState.sessionId=创建会话 UUID → SessionStorage 三 seam 纯 sessionId 解析自然命中
        // {boundProject}/{创建会话UUID}.jsonl。RED: 误用 GLOBAL_SESSION_UUID 或注入 per-task override
        // → 断言变红（去虚拟键是本次设计核心，transcript 必须归真实创建会话）。
        java.nio.file.Path tmp = Files.createTempDirectory("cron-durable-alive");
        String boundProject = CwdResolution.normalizeCwd(tmp.toAbsolutePath().toString());
        String creatingSession = "sess-abcdef12";
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        // sessionMapper 未注入 → fail-open 视为存活（非 Spring 单测语义，生产恒注入）

        // DURABLE: sessionId=创建会话 + boundProject 锚 + scheduleId（TestJob fire 经 QueueItem 透传）
        QueueItem cmd = new QueueItem("持久化项目任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, creatingSession, boundProject,
            "sch-alive-001");

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(loop);
        inOrder.verify(loop).setCronProjectRootOverride(boundProject);
        inOrder.verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId())
            .as("创建会话存活 → RunRequest.sessionId=创建会话 UUID（transcript 归创建会话文件）")
            .isEqualTo(creatingSession);
        inOrder.verify(loop).clearCronProjectRootOverride();
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("DURABLE 创建会话已关 → headless 无 transcript（RunRequest.sessionId=null，不写创建会话/GLOBAL 文件）")
    void runOneAgentLoopDurableCreatingSessionClosed_headlessNoTranscript() throws Exception {
        // WHY（规则九）: 创建会话已关（SessionService.delete 删行）→ fire 照常执行（headless）但不产生
        // 会话 transcript。sessionMapper.selectOneById 返回 null → isSessionAlive=false →
        // RunRequest.sessionId=null → SessionStorage.getTranscriptPath(workspaceDir, null) 返回 null →
        // 消费方跳过写 transcript（不产生创建会话文件，也不产生 GLOBAL.jsonl 共享污染）。
        // RED: 误用创建会话 UUID / GLOBAL_SESSION_UUID → transcript 落死会话文件或 GLOBAL 共享 → 变红。
        java.nio.file.Path tmp = Files.createTempDirectory("cron-durable-closed");
        String boundProject = CwdResolution.normalizeCwd(tmp.toAbsolutePath().toString());
        String creatingSession = "sess-feedbeef";
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(null);  // 创建会话已关 → DB 行不存在
        ReflectionTestUtils.setField(executor, "sessionMapper", sessionMapper);

        QueueItem cmd = new QueueItem("持久化项目任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, creatingSession, boundProject,
            "sch-closed-001");

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(loop);
        inOrder.verify(loop).setCronProjectRootOverride(boundProject);
        inOrder.verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId())
            .as("创建会话已关 → RunRequest.sessionId=null（headless 无 transcript）")
            .isNull();
        inOrder.verify(loop).clearCronProjectRootOverride();
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("DURABLE 无创建会话（missed 启动通知 / 无会话直建）→ headless 无 transcript（RunRequest.sessionId=null）")
    void runOneAgentLoopDurableNoCreatingSession_headlessNoTranscript() throws Exception {
        // WHY: missed 启动通知（surfaceMissedOneShots）或 DURABLE 无会话（REST 直建）无创建会话可归 →
        // headless 无 transcript：RunRequest.sessionId=null → SessionStorage.getTranscriptPath(workspaceDir,
        // null) 返回 null → 消费方跳过写 transcript（不产生 GLOBAL.jsonl 共享污染）。项目身份
        // （boundProject）仍注入（cwd/memory 归创建项目）。
        java.nio.file.Path tmp = Files.createTempDirectory("cron-durable-nosession");
        String boundProject = CwdResolution.normalizeCwd(tmp.toAbsolutePath().toString());
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        // DURABLE + boundProject 锚，但无 sessionId（missed 通知 / 无会话直建）
        QueueItem cmd = new QueueItem("持久化项目任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, null, boundProject);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(loop);
        inOrder.verify(loop).setCronProjectRootOverride(boundProject);
        inOrder.verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId())
            .as("无创建会话 → RunRequest.sessionId=null（headless 无 transcript）")
            .isNull();
        inOrder.verify(loop).clearCronProjectRootOverride();
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("SESSION fire（sessionId 非空）→ RunRequest 用真实创建会话 UUID（transcript 归创建会话），不注入 override")
    void runOneAgentLoopSessionUsesRealSessionUuid() throws Exception {
        // WHY: SESSION scope cron 走真实创建会话 UUID（CRON-D5 改3），transcript 归创建会话
        // （{workspaceDir}/{sessionId}.jsonl）——SESSION 路径不变（生命周期绑定由 scope 列承载，
        // CronIdleExecutor 只代跑，不注入任何 override）。
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        String sessionId = "sess-transcript-session";
        QueueItem cmd = new QueueItem("会话任务", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId, null, "sch-transcript-002");

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId())
            .as("SESSION → RunRequest.sessionId=真实创建会话 UUID（transcript 归创建会话）")
            .isEqualTo(sessionId);
        verify(loop, never()).setCronProjectRootOverride(any());
        verify(loop).clearCronProjectRootOverride();
    }

    @Test
    @DisplayName("poll: DURABLE 命令创建会话已关 → 照常消费；SESSION 命令会话已关 → 不消费（boundProject 判别）")
    void poll_durableClosedConsumed_sessionClosedNotConsumed() {
        // WHY（规则九）: drain 判别 DURABLE vs SESSION（boundProject!=null = DURABLE，SESSION 恒 null）。
        // DURABLE 创建会话已关也照常 fire（3c 空闲 drain 代跑 headless）；SESSION 必须会话存活
        // （会话已关 → 不消费，SESSION 随生命周期消亡，cleanupBySession 已删其调度，滞留队列项不代跑）。
        String closedDurableSession = "sess-d0000001";
        String closedSessionSession = "sess-s0000001";
        queue.enqueue(new QueueItem("DURABLE已关会话的cron", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, closedDurableSession,
            "C:/proj/closed-a", "sch-d-1"));
        queue.enqueue(new QueueItem("SESSION已关会话的cron", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, closedSessionSession, null,
            "sch-s-1"));
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(null);  // 两会话均已关
        ReflectionTestUtils.setField(executor, "sessionMapper", sessionMapper);
        List<QueueItem> consumed = new ArrayList<>();

        boolean processed = executor.poll(consumed::addAll);

        assertThat(processed).isTrue();
        assertThat(consumed).extracting(QueueItem::value).containsExactly("DURABLE已关会话的cron");
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.peek(q -> true).orElseThrow().value()).isEqualTo("SESSION已关会话的cron");
    }

    // ============ [3a-3e] drain 归属收敛：全局+空闲会话消费者 · 修饿死 · 不混会话批次 ============

    @Test
    @DisplayName("[3c 修饿死] 运行中会话 cron 逐条跳过，空闲会话 cron 不被饿死")
    void pollSkipsRunningSessionCommands_butProcessesIdleSessionCommands() {
        // WHY（规则九）: A-queue-ownership-probe §2.2 场景 C —— 旧实现 peek 首个主线程命令的会话
        // 在跑 → 整个 poll return false，把同优先级靠后的空闲会话 cron 一起饿死（"没活干"）。
        // 3c 修法 = 把「目标会话运行中」并入谓词逐条跳过，peek 返回首个可消费项（空闲会话 cron），
        // 运行中会话的命令留在队列，不再阻塞其余空闲会话命令。
        String runningSession = "sess-a1b2c3d4";
        String idleSession = "sess-e5f6a7b8";
        queue.enqueue(new QueueItem("运行中会话的cron", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, runningSession));
        queue.enqueue(new QueueItem("空闲会话的cron", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, idleSession));
        LlmAgentLoop.markRunning(runningSession);  // 首个命令的会话运行中
        List<QueueItem> consumed = new ArrayList<>();

        boolean processed = executor.poll(consumed::addAll);

        assertThat(processed).isTrue();
        assertThat(consumed).extracting(QueueItem::value).containsExactly("空闲会话的cron");
        // 运行中会话的命令不消费（留队列等该会话自身 turn），空闲会话 cron 正常处理（不饿死）
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.peek(q -> true).orElseThrow().value()).isEqualTo("运行中会话的cron");
        LlmAgentLoop.markIdle(runningSession);
    }

    @Test
    @DisplayName("[3c] 不捞真实会话用户 prompt（mode=prompt && workload==null && sessionId!=null → 留该会话自身 turn）")
    void pollSkipsRealSessionUserPrompt() {
        // WHY（规则九）: 3b 后用户 prompt 入队带 sessionId（真实会话）。若 CronIdleExecutor 也捞它，
        // 会与该会话自身 turn 的 drainForQuery 重复处理（double-processing，会话 A 的 prompt 跑进
        // 全局 loop）。3c 判别：真实会话用户 prompt 留给该会话自身 turn，本执行器跳过。
        String sessionId = "sess-f1e2d3c4";
        queue.enqueue(new QueueItem("用户prompt", "prompt", Priority.NEXT, null,
            null, false, null, false, null, sessionId));
        AtomicInteger startCount = new AtomicInteger();

        boolean processed = executor.poll(commands -> startCount.incrementAndGet());

        assertThat(processed).isFalse();
        assertThat(startCount.get()).isZero();
        assertThat(queue.size()).isEqualTo(1);  // 真实会话用户 prompt 留队列等会话自身 turn
    }

    @Test
    @DisplayName("[3d] 批量出队按 sessionId 归组，不混会话批次")
    void batchGroupsBySessionId_notMixingSessions() {
        // WHY（规则九）: executeQueuedInput 逐命令串行 runOneAgentLoop 恢复各自创建会话 MDC ——
        // 若把不同会话的 cron 混进一个 batch，逐命令恢复会话上下文会串台。3d:
        // dequeueAllMatching 追加 SessionKeys.canonicalUuid(c.sessionId()).equals(targetSessionCanonical)。
        String sessionA = "sess-a1b2c3d4";
        String sessionB = "sess-e5f6a7b8";
        queue.enqueue(new QueueItem("A-1", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionA));
        queue.enqueue(new QueueItem("A-2", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionA));
        queue.enqueue(new QueueItem("B-1", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionB));
        List<QueueItem> consumed = new ArrayList<>();

        boolean processed = executor.poll(consumed::addAll);

        assertThat(processed).isTrue();
        // 首轮只消费 A 会话批次（peek 命中 A-1 → 归组 canonical(sessionA)），B-1 留队列等下一轮
        assertThat(consumed).extracting(QueueItem::value).containsExactlyInAnyOrder("A-1", "A-2");
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.peek(q -> true).orElseThrow().value()).isEqualTo("B-1");
    }

    @Test
    @DisplayName("[3e] 批量归组归一化：原始键 sess-xxx 与派生 UUID 串归同一批次（canonicalUuid 两侧）")
    void batchGroupNormalizesSessRawKeyAndDerivedUuid() {
        // WHY（规则九）: QueueItem.sessionId 可能是原始键（HTTP 路径 ScheduleService.create 落库）或
        // 派生 UUID 串（工具路径 CronCreateTool 落库）。3e: 归组两侧都走 SessionKeys.canonicalUuid，
        // 否则同一会话的两个命令（不同形态）被分到不同批次，违反「同会话命令同批次」。
        String rawKey = "sess-abc12345";
        String derivedUuid = rawKey;
        queue.enqueue(new QueueItem("raw形态", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, rawKey));
        queue.enqueue(new QueueItem("uuid形态", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, derivedUuid));
        List<QueueItem> consumed = new ArrayList<>();

        boolean processed = executor.poll(consumed::addAll);

        assertThat(processed).isTrue();
        assertThat(consumed).extracting(QueueItem::value).containsExactlyInAnyOrder("raw形态", "uuid形态");
        assertThat(queue.size()).isZero();

    }

    // ============ R4: 已删会话的后台任务通知消费边界 ============

    @Test
    @DisplayName("R4: 已删会话的后台任务通知（task-notification）→ poll 照常消费（不滞留孤儿）+ runOneAgentLoop headless（sessionUuid=null）")
    void taskNotification_deletedSession_consumedAndHeadlessNoTranscript() throws Exception {
        // WHY（规则九 · R4）: 后台任务完成通知带创建会话 sessionId（CronNotifyProducerSessionRoutingTest
        //   锁死）。创建会话已删（SessionService.delete 删行）→ 旧 SESSION 语义拒消费 → 通知永久滞留
        //   队列（孤儿，无人消费也不进任何模型上下文）。R4: 通知类命令会话已删 → mainThreadConsumable
        //   放行（不滞留）+ runOneAgentLoop sessionUuid=null（headless 无 transcript，通知作为全局通知
        //   被模型消费，对齐 DURABLE 已关分支 headless 语义）。RED: 通知滞留队列 / RunRequest 用死会话
        //   UUID（写死会话 transcript）→ 变红。
        String deletedSession = "sess-deleted-01";
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(null);  // 会话已删（DB 行不存在）
        ReflectionTestUtils.setField(executor, "sessionMapper", sessionMapper);

        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        AtomicReference<RunRequest> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return null;
        }).when(loop).run(any(RunRequest.class));

        // 后台任务完成通知（task-notification）带创建会话 sessionId（已删）
        queue.enqueue(new QueueItem("后台任务完成通知XML", NotificationQueue.MODE_TASK_NOTIFICATION,
            Priority.LATER, null, null, false, null, false, null, deletedSession));

        boolean processed = executor.poll(commands ->
            commands.forEach(cmd -> ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd)));

        assertThat(processed)
            .as("已删会话的后台任务通知必须被消费（不滞留孤儿）")
            .isTrue();
        assertThat(queue.size())
            .as("通知已消费，队列清空")
            .isZero();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().sessionId())
            .as("已删会话通知 → RunRequest.sessionId=null（headless 无 transcript，不写死会话文件）")
            .isNull();
    }

    @Test
    @DisplayName("R4 边界: SESSION 普通 cron（mode=prompt）会话已删 → 仍不消费（R4 只放宽通知类，零回归）")
    void poll_sessionDeletedPromptStillNotConsumed() {
        // WHY（规则九 · R4 边界）: R4 只放宽"后台任务通知"（task-notification）——SESSION 普通 cron
        //   （mode=prompt）会话已删仍不消费（SESSION 随生命周期消亡，cleanupBySession 已删其调度）。
        //   RED: 放宽越界（prompt 也被消费）→ 已删会话的 cron 复活执行 → 变红。
        String deletedSession = "sess-deleted-02";
        queue.enqueue(new QueueItem("已删会话的cron", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, deletedSession));
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(any())).thenReturn(null);
        ReflectionTestUtils.setField(executor, "sessionMapper", sessionMapper);
        AtomicInteger startCount = new AtomicInteger();

        boolean processed = executor.poll(commands -> startCount.incrementAndGet());

        assertThat(processed).isFalse();
        assertThat(startCount.get()).isZero();
        assertThat(queue.size()).isEqualTo(1);  // 已删会话的 SESSION cron 仍滞留（不复活执行）
    }

    @Test
    @DisplayName("R4: 会话存活的后台任务通知照常消费（归创建会话回合，非 headless）")
    void taskNotification_aliveSession_routesToRealSessionUuid() throws Exception {
        // WHY（规则九 · R4 反例）: 创建会话存活（sessionMapper 未注入 fail-open 视为存活）→
        //   task-notification 照常归创建会话 UUID（transcript 归创建会话，R4 不改变正常路径）。
        //   RED: R4 误判活会话为已删 → RunRequest.sessionId=null → 活会话通知丢失归组 → 变红。
        String aliveSession = "sess-alive-01";
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        AtomicReference<RunRequest> captured = new AtomicReference<>();
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return null;
        }).when(loop).run(any(RunRequest.class));

        QueueItem cmd = new QueueItem("后台任务完成通知XML", NotificationQueue.MODE_TASK_NOTIFICATION,
            Priority.LATER, null, null, false, null, false, null, aliveSession);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId())
            .as("存活会话通知 → RunRequest.sessionId=真实创建会话 UUID（R4 不改变正常路径）")
            .isEqualTo(aliveSession);
    }

    // ============ [mid-turn-align] busy-queued 兜底回归（goal 4：CronIdleExecutor 保留兜底） ============

    @Test
    @DisplayName("会话空闲时队列残留 busy-queued（priority=NEXT）→ poll 照常消费（mainThreadConsumable 不看 priority）")
    void pollConsumesBusyQueuedWhenIdle() {
        // WHY（规则九 · 兜底回归）: mid-turn 注入优先（同轮回答），但「当前轮结束仍残留 busy-queued」
        //   （纯文本轮末无更多工具边界注入 / 最后一次 drain 之后入队）必须由本路径兜底起新轮
        //   （CC useQueueProcessor.ts:48-67 turn 结束兜底）。busy-queued priority 改 NEXT 不影响本路径
        //   —— mainThreadConsumable 按 workload 判别不按 priority。
        String sessionId = "sess-busy-idle";
        queue.enqueue(new QueueItem("忙时追问", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-queued-idle", false, "busy-queued", false, null, sessionId));
        List<QueueItem> consumed = new ArrayList<>();

        boolean processed = executor.poll(consumed::addAll);

        assertThat(processed).isTrue();
        assertThat(consumed).extracting(QueueItem::uuid).containsExactly("msg-queued-idle");
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("会话运行中 → busy-queued 不被 CronIdleExecutor 消费（与 mid-turn 注入互斥，无双发）")
    void pollSkipsBusyQueuedWhenSessionRunning() {
        // WHY（R4 竞态防护）: 运行中 turn 的工具边界会 mid-turn drain 消费 busy-queued（同轮回答），
        //   CronIdleExecutor 若此时也捞走就双发。mainThreadConsumable :279 isSessionRunning 逐条跳过
        //   → 运行中 busy-queued 留队列，只有 turn 结束（不再 mid-turn）后才兜底消费。
        String sessionId = "sess-busy-running";
        queue.enqueue(new QueueItem("忙时追问", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-queued-run", false, "busy-queued", false, null, sessionId));
        LlmAgentLoop.markRunning(sessionId);
        try {
            AtomicInteger startCount = new AtomicInteger();
            boolean processed = executor.poll(commands -> startCount.incrementAndGet());

            assertThat(processed).isFalse();
            assertThat(startCount.get()).isZero();
            assertThat(queue.size())
                .as("运行中 busy-queued 不被兜底捞走（留给当前轮 mid-turn 注入）")
                .isEqualTo(1);
        } finally {
            LlmAgentLoop.markIdle(sessionId);
        }
    }

    @Test
    @DisplayName("executeQueuedInput 兜底 busy-queued：emitDrained（streamTopic 恒会话 topic）+ createQueuedUserMessage + run 起新轮")
    void executeQueuedInput_consumesBusyQueuedEmitDrainedAndPersist() {
        // WHY（规则九 · goal 4 兜底回归）: 本路径保留既有行为不变——emitDrained 用 2-arg（drained[].streamTopic
        //   恒为会话 topic，前端已在会话 topic 单一订阅，无新 topic 派生）+ createQueuedUserMessage 落库
        //   + runOneAgentLoop 起新轮（CC useQueueProcessor.ts:48-67）。mid-turn 注入只发生在 running
        //   期间（工具边界），本路径只处理 turn 结束后的残留 → 时序互斥无双发。
        QueueEventPublisher mockPublisher = mock(QueueEventPublisher.class);
        ReflectionTestUtils.setField(executor, "queueEventPublisher", mockPublisher);
        com.nexusai.domain.session.MessageService mockMessageService =
            mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(executor, "messageService", mockMessageService);
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        String sessionId = "sess-busy-fallback";
        QueueItem cmd = new QueueItem("忙时兜底追问", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, "msg-queued-fallback", false, "busy-queued", false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        verify(mockPublisher).emitDrained(eq(sessionId), anyList());
        // [C1] 5 参重载落库 isMeta：busy-queued（workload!=WORKLOAD_CRON）→ isMeta=false（前端可见）
        verify(mockMessageService).createQueuedUserMessage(eq(sessionId), eq("msg-queued-fallback"),
            eq("忙时兜底追问"), any(), eq(false));
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(sessionId);
    }

    // ============ [P2 · slash 消费兜底] 排队 slash 命令走命令执行链（对齐 CC processSlashCommand） ============

    @Test
    @DisplayName("P2: prompt 型 slash 命令消费 → isMeta 技能内容落库 + userPrompt=cmd.value() 原文（防双注入）")
    void executeQueuedInput_promptSlashCommand_isMetaPersisted_rawCommandAsPrompt() {
        // WHY（规则九 · Fix-P2 Issue 2 完成标准）: CronIdleExecutor 对 slash 命令 dequeue 后调用 P1
        //   实现的命令执行链（SlashCommandInterceptor 共用分派）。prompt 型 → 技能内容必须先落 isMeta
        //   DB 消息（镜像 ChatService slashMetaId 模式，resume/压缩后仍可恢复，转录非裸 /cmd）；
        //   userPrompt 用 cmd.value() 原文 —— LlmAgentLoop.run 会从 DB 历史重载 isMeta 技能内容
        //   （listForResumeExcluding 排除 cmd.uuid() 保留 metaId），若仍传技能内容作 prompt → 双注入
        //   （技能内容在上下文出现两次）。注意：userPrompt=原文是 Java 自选简化（CC 可见 user 消息实为
        //   formatCommandLoadingMetadata 的 XML metadata，非用户原文，Fix-P2 Issue 3 措辞修正）。
        //   RED: isMeta 技能内容未落库 / RunRequest.userPrompt != cmd.value() 原文 → 变红。
        com.nexusai.domain.session.MessageService mockMessageService =
            mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(executor, "messageService", mockMessageService);
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        SlashCommandInterceptor slashMock = mock(SlashCommandInterceptor.class);
        SlashCommandInterceptor.SlashResolution res = new SlashCommandInterceptor.SlashResolution(
            true, true, "技能内容（SKILL.md 渲染）", null, null, "技能内容（isMeta）", null);
        when(slashMock.intercept(any(), any(), any(), any(), any())).thenReturn(res);
        ReflectionTestUtils.setField(executor, "slashInterceptor", slashMock);

        String sessionId = "sess-slash-prompt";
        QueueItem cmd = new QueueItem("/import-cc --skill=test", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        verify(slashMock).intercept(eq(sessionId), any(), eq("/import-cc --skill=test"), any(), any());
        // ① raw /command 落库（cron → isMeta=true，UI 隐藏模型可见）
        verify(mockMessageService).createQueuedUserMessage(eq(sessionId), isNull(), eq("/import-cc --skill=test"),
            any(), eq(true));
        // ② 技能内容 isMeta 落库（Fix-P2 Issue 2：resume/压缩后技能内容仍可恢复，镜像 P1 slashMetaId 模式）
        verify(mockMessageService).createQueuedUserMessage(eq(sessionId), any(), eq("技能内容（isMeta）"),
            any(), eq(true));
        // ③ 起 turn 的 userPrompt = cmd.value() 原文（非技能内容 —— 技能内容经 isMeta 历史重载，防双注入）
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().userPrompt()).isEqualTo("/import-cc --skill=test");
        assertThat(captor.getValue().sessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("Fix-P2 Issue 1: slash 技能级 model 覆盖命中 → config 随覆盖模型重解析（非主模型 config）")
    void runOneAgentLoop_modelOverrideResolved_reparsesConfig() {
        // WHY（规则九 · Fix-P2 Issue 1 完成标准）: 原实现 config 恒为主模型 config（先 resolveMainConfig
        //   再覆盖 modelName），覆盖模型若落在不同 provider，会拿覆盖模型名打主模型的 baseUrl/apiKey
        //   （错配，中危缺陷）。镜像 ChatService（buildConfigForModel(modelName) 在 modelOverride 之后
        //   重解析）：覆盖模型经 ModelConfigResolver.resolve 命中 → config 切到覆盖模型。
        //   RED: RunRequest.config 仍为主模型 config → 变红。
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        // 主模型可解析（settingsMapper + modelMapper + resolver 三件套）
        com.nexusai.repository.settings.mapper.SettingsMapper settingsMapper =
            mock(com.nexusai.repository.settings.mapper.SettingsMapper.class);
        com.nexusai.repository.settings.entity.SettingsRecord settings =
            new com.nexusai.repository.settings.entity.SettingsRecord();
        settings.setMainModelName("main-model");
        when(settingsMapper.selectOneById(any())).thenReturn(settings);
        ReflectionTestUtils.setField(executor, "settingsMapper", settingsMapper);
        com.nexusai.repository.provider.mapper.ModelMapper modelMapper =
            mock(com.nexusai.repository.provider.mapper.ModelMapper.class);
        com.nexusai.repository.provider.entity.ModelRecord mainRecord =
            new com.nexusai.repository.provider.entity.ModelRecord();
        mainRecord.setName("main-model");
        mainRecord.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenReturn(java.util.List.of(mainRecord));
        ReflectionTestUtils.setField(executor, "modelMapper", modelMapper);

        com.nexusai.infra.llm.ModelConfigResolver resolver =
            mock(com.nexusai.infra.llm.ModelConfigResolver.class);
        com.nexusai.infra.llm.ProviderConfig mainConfig =
            new com.nexusai.infra.llm.ProviderConfig("https://main.example.com", "main-key");
        com.nexusai.infra.llm.ProviderConfig overrideConfig =
            new com.nexusai.infra.llm.ProviderConfig("https://override.example.com", "override-key");
        when(resolver.resolve("main-model")).thenReturn(
            new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(mainConfig, "openai_compatible"));
        when(resolver.resolve("override-model")).thenReturn(
            new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(overrideConfig, "openai_compatible"));
        ReflectionTestUtils.setField(executor, "modelConfigResolver", resolver);

        String sessionId = "sess-override";
        QueueItem cmd = new QueueItem("/skill-override", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd, "override-model");

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().modelName()).isEqualTo("override-model");
        assertThat(captor.getValue().config()).isEqualTo(overrideConfig);
    }

    @Test
    @DisplayName("Fix-P2 Issue 1: slash 技能级 model 覆盖无法解析 → 回落主模型（覆盖不泄漏 modelName）")
    void runOneAgentLoop_modelOverrideUnresolvable_fallsBackToMainModel() {
        // WHY（规则九 · Fix-P2 Issue 1 完成标准）: modelConfigResolver.resolve(override) 返回 null
        //   （warn+skip 语义）→ 覆盖不生效，整体回落主模型（modelName+config 一起回退主模型解析结果，
        //   保持名配一致）——绝不拿覆盖模型名打主模型 config。RED: RunRequest.modelName 泄漏覆盖模型名
        //   或 config 与主模型不配 → 变红。
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        // 主模型可解析（settingsMapper + modelMapper + resolver）
        com.nexusai.repository.settings.mapper.SettingsMapper settingsMapper =
            mock(com.nexusai.repository.settings.mapper.SettingsMapper.class);
        com.nexusai.repository.settings.entity.SettingsRecord settings =
            new com.nexusai.repository.settings.entity.SettingsRecord();
        settings.setMainModelName("main-model");
        when(settingsMapper.selectOneById(any())).thenReturn(settings);
        ReflectionTestUtils.setField(executor, "settingsMapper", settingsMapper);
        com.nexusai.repository.provider.mapper.ModelMapper modelMapper =
            mock(com.nexusai.repository.provider.mapper.ModelMapper.class);
        com.nexusai.repository.provider.entity.ModelRecord mainRecord =
            new com.nexusai.repository.provider.entity.ModelRecord();
        mainRecord.setName("main-model");
        mainRecord.setEnabled(true);
        when(modelMapper.selectListByQuery(any())).thenReturn(java.util.List.of(mainRecord));
        ReflectionTestUtils.setField(executor, "modelMapper", modelMapper);

        com.nexusai.infra.llm.ModelConfigResolver resolver =
            mock(com.nexusai.infra.llm.ModelConfigResolver.class);
        com.nexusai.infra.llm.ProviderConfig mainConfig =
            new com.nexusai.infra.llm.ProviderConfig("https://main.example.com", "main-key");
        when(resolver.resolve("main-model")).thenReturn(
            new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(mainConfig, "openai_compatible"));
        when(resolver.resolve("override-model")).thenReturn(null);   // 覆盖模型不可用（warn+skip）
        ReflectionTestUtils.setField(executor, "modelConfigResolver", resolver);

        String sessionId = "sess-override";
        QueueItem cmd = new QueueItem("/skill-override", "prompt", Priority.LATER, null,
            null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd, "override-model");

        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().modelName()).isEqualTo("main-model");
        assertThat(captor.getValue().config()).isEqualTo(mainConfig);
    }

    @Test
    @DisplayName("P2: local 型 slash 命令消费 → 本地执行不起 LLM turn（结果消息落库+推送）")
    void executeQueuedInput_localSlashCommand_noAgentLoop() {
        // WHY（规则九 · P2 完成标准）: local/local-jsx/未知命令（shouldQuery=false）→ 非查询型终态，
        //   不起 LLM turn（CC processSlashCommand.tsx:657-722 local shouldQuery=false）。local handler
        //   由 intercept 内部 dispatchResult 本地执行，有结果文本 → 落库 + 推会话流（真实会话可见）。
        //   RED: loop.run 被调用 / 结果消息未落库 → 变红。
        com.nexusai.domain.session.MessageService mockMessageService =
            mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(executor, "messageService", mockMessageService);
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        SlashCommandInterceptor slashMock = mock(SlashCommandInterceptor.class);
        SlashCommandInterceptor.SlashResolution res = new SlashCommandInterceptor.SlashResolution(
            true, false, null, "<local-command-stdout>/status done</local-command-stdout>", null, null,
            "msg-slash-status");
        when(slashMock.intercept(any(), any(), any(), any(), any())).thenReturn(res);
        ReflectionTestUtils.setField(executor, "slashInterceptor", slashMock);

        String sessionId = "sess-slash-local";
        QueueItem cmd = new QueueItem("/status", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, null, false, "busy-queued", false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        // 不起 LLM turn
        verify(loop, never()).run(any());
        // 非查询型结果消息落库（isMeta=false 用户可见，id=resolution.resultMessageId）
        verify(mockMessageService).createQueuedUserMessage(eq(sessionId), eq("msg-slash-status"),
            eq("<local-command-stdout>/status done</local-command-stdout>"), any(), eq(false));
    }

    @Test
    @DisplayName("P2: slashInterceptor 未注入 → slash 命令回落原文起 turn（fail-open 不退化）")
    void executeQueuedInput_slashCommandWithoutInterceptor_fallsBackToRawPrompt() {
        // WHY（规则九 · P2 风险 §3 fail-open）: slashInterceptor 未注入（非 Spring 单测 / 旧上下文）
        //   → 回落旧行为（runOneAgentLoop 丢原文起 turn），不阻断、不退化。
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        // slashInterceptor 不注入 → null

        String sessionId = "sess-slash-fallback";
        QueueItem cmd = new QueueItem("/import-cc --skill=test", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        // fail-open: 原文进 LLM turn
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().userPrompt()).isEqualTo("/import-cc --skill=test");
    }

    // ============ [P0 · 谓词] 无限循环根治 + skipSlashCommands 同语义（对齐 CC messageQueueManager.ts:538-547） ============

    @Test
    @DisplayName("[P0 谓词] 真实会话 slash 命令（workload==null）→ 不消费（无限循环根治）")
    void pollSkipsRealSessionSlashCommand() {
        // WHY（规则九 · 本 worktree 无限循环根治）: 用户直接发送的 slash 命令（如 /import-cc --skill=test）
        //   入队 workload==null。旧谓词 `workload==null && !isSlashCommand(cmd)` 使 slash 命令绕过检查被
        //   本执行器消费 → runOneAgentLoop 起新 run → LlmAgentLoop.run 每次又入队用户 prompt（对齐 CC
        //   enqueue :2820）→ turn-0 提前 drain 后残留 → 3s poll 再消费 → 无限循环（联调实测每 3-8 秒一轮，
        //   DB 刷 ~20 条无占位 assistant）。对齐 CC：空闲用户 prompt 不入队（handlePromptSubmit 直接处理），
        //   queueProcessor 消费的都是排队命令（workload 非 null）——workload==null 必为用户直接发送，由本
        //   会话 turn 自身消费，本执行器绝不打捞。RED: poll 消费了 workload==null 的 slash 命令 → 变红。
        String sessionId = "sess-slash-infinite";
        queue.enqueue(new QueueItem("/import-cc --skill=test", NotificationQueue.MODE_PROMPT, Priority.NEXT,
            null, null, false, null, false, null, sessionId));
        AtomicInteger startCount = new AtomicInteger();

        boolean processed = executor.poll(commands -> startCount.incrementAndGet());

        assertThat(processed).isFalse();
        assertThat(startCount.get()).isZero();
        assertThat(queue.size()).isEqualTo(1);  // 留自身 turn，绝不 3s 轮询打捞
    }

    @Test
    @DisplayName("[P0 谓词] isSlashCommand: skipSlashCommands=true 的 '/'-开头（bridge/CCR）→ 非 slash（纯文本送模型）")
    void isSlashCommand_skipSlashCommandsTrue_returnsFalse() {
        // WHY（规则九 · 双实现防分叉）: 对齐 CC messageQueueManager.ts:538-547 isSlashCommand — value trim
        //   后以 '/' 开头且 skipSlashCommands=false。skipSlashCommands=true（bridge/CCR 消息，
        //   textInputTypes.ts:320）时 '/'-开头按纯文本送模型，不走命令链 —— 本执行器判别必须与
        //   NotificationQueue.isSlashCommand 同语义，否则 ChannelNotification 入队（isMeta +
        //   skipSlashCommands）的 '/'-开头消息被误判为 slash 消费，走命令执行链而非纯文本（双实现分叉）。
        //   RED: 误判为 slash → 变红。
        QueueItem bridgeMsg = new QueueItem("/bridge text", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, null, true, null, true, null, "sess-bridge");
        assertThat(CronIdleExecutor.isSlashCommand(bridgeMsg)).isFalse();
        QueueItem slashCmd = new QueueItem("/status", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, null, false, "busy-queued", false, null, "sess-slash");
        assertThat(CronIdleExecutor.isSlashCommand(slashCmd)).isTrue();
        assertThat(CronIdleExecutor.isSlashCommand(null)).isFalse();
    }

    // ============ [P0.5 · 落库失败推占位] 前端锚点不丢（对齐 CC cron user isMeta 占位） ============

    @Test
    @DisplayName("[P0.5] cron 消费落库失败（createQueuedUserMessage 抛异常）→ 仍推 message.user 占位保前端锚点")
    void executeQueuedInput_persistFailure_stillPushesUserPlaceholder() {
        // WHY（规则九 · 前端锚点不丢）: 落库失败（uuid 复用主键冲突等）不再阻断推送 —— 前端仍需 user
        //   占位锚点归属该轮 assistant 块，否则穿插对话（对齐 CC cron user isMeta 占位）。旧实现落库
        //   异常直接进 catch 跳过推送 → 前端 messages 缺锚点 → 该轮 assistant 流式块被后续用户 turn
        //   complete 混收口后按 flowKey 找不到锚点插末尾 → 顺序倒挂。RED: 落库失败后 publishUserMessageEvent
        //   未被调用（锚点丢失）→ 变红。
        com.nexusai.domain.session.MessageService mockMessageService =
            mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(executor, "messageService", mockMessageService);
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        ChatService mockChat = mock(ChatService.class);
        ReflectionTestUtils.setField(executor, "chatService", mockChat);
        org.springframework.messaging.simp.SimpMessagingTemplate mockWs =
            mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
        ReflectionTestUtils.setField(executor, "wsTemplate", mockWs);

        String sessionId = "sess-persist-fail";
        QueueItem cmd = new QueueItem("定时任务提示词", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, "msg-cron-uuid", true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);
        when(mockMessageService.createQueuedUserMessage(eq(sessionId), eq("msg-cron-uuid"), eq("定时任务提示词"),
            any(), eq(true))).thenThrow(new RuntimeException("duplicate key"));

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        // 落库失败仍推 message.user 占位（id=cmd.uuid() 兜底，前端锚点归属该轮 assistant 块）
        verify(mockChat).publishUserMessageEvent(eq(sessionId), eq("msg-cron-uuid"), eq("定时任务提示词"),
            eq(true), eq("/topic/sessions/" + sessionId + "/stream"), eq(mockWs));
        // 占位推送后主链不阻断 → 仍走 runOneAgentLoop
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(sessionId);
    }

    // ============ [P5-① · 排队消费复判] userInvocable=false 拒绝（对齐 CC processSlashCommand.tsx:526-548） ============

    @Test
    @DisplayName("[P5-①] 排队消费复判 userInvocable=false → 推拒绝文案 + continue（不起 agent loop）")
    void executeQueuedInput_userInvocableFalseRejected_noAgentLoop() {
        // WHY（规则九 · P5 排队路径复判）: busy 排队命令 dequeue 后重走 handlePromptSubmit，
        //   userInvocable=false 同样拒绝（对齐 CC processSlashCommand.tsx:526-548）——否则排队消费绕过
        //   ChatService 直连拒绝，userInvocable=false 技能经 cron/busy 队列被用户间接调用（SkillTool 模型
        //   主动调用不受此限，位置约束见 ChatService.rejectNonUserInvocable JavaDoc）。本测试锁定
        //   executeQueuedInput 消费时调用 rejectNonUserInvocable，命中 → continue 不起 agent loop。
        //   RED: loop.run 被调用 / rejectNonUserInvocable 未被调用 → 变红。
        com.nexusai.domain.session.MessageService mockMessageService =
            mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(executor, "messageService", mockMessageService);
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        ChatService mockChat = mock(ChatService.class);
        ReflectionTestUtils.setField(executor, "chatService", mockChat);
        org.springframework.messaging.simp.SimpMessagingTemplate mockWs =
            mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
        ReflectionTestUtils.setField(executor, "wsTemplate", mockWs);
        when(mockChat.rejectNonUserInvocable(eq("sess-reject"), eq("/private-cmd"), eq("msg-reject"),
            eq(mockWs))).thenReturn(true);

        String sessionId = "sess-reject";
        QueueItem cmd = new QueueItem("/private-cmd", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, "msg-reject", true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        verify(mockChat).rejectNonUserInvocable(eq(sessionId), eq("/private-cmd"), eq("msg-reject"), eq(mockWs));
        verify(loop, never()).run(any());   // 拒绝 → continue，不起 agent loop
    }

    // ============ [cron-fire-visible] cron 触发结果落库 + 前端可见（对齐 CC onFireTask） ============

    @Test
    @DisplayName("目标1: cron 触发消费（mode=prompt + workload=cron + sessionId）→ 落库 user 消息（isMeta=true），scheduled_task_fire 系统消息不复活")
    void executeQueuedInput_cronPersistsSystemAndUserMessage() {
        // WHY（规则九）: 2026-08-25 联调实测 cron 触发结果前端收不到——CC onFireTask（useScheduledTasks.ts:110-113）
        //   落 transcript = user prompt（isMeta）+ assistant 回复；Java 旧实现 cron 走 headless 不落库。
        //   本测试锁定 cron 消费时落 user 消息（目标1，createQueuedUserMessage 条件从 busy-queued 放宽到
        //   mode=prompt）→ 转录序 = [user, assistant]。scheduled_task_fire 可见系统消息（原目标3）已由
        //   14086c2c 按用户拍板移除（「任务执行中」不需要）——下方 verify(never()) 为回归防线：若该
        //   行为被误复活（或有人按旧 CC 注释重加），测试变红（fail loud 而非静默回退）。
        //   RED: cron user 消息未落库 / scheduled_task_fire 被重加 → 变红。
        com.nexusai.domain.session.MessageService mockMessageService =
            mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(executor, "messageService", mockMessageService);
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        String sessionId = "sess-cron-visible";
        QueueItem cmd = new QueueItem("定时任务提示词", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        // 目标1: cron uuid=null → createQueuedUserMessage 5-arg（内部生成兜底 id）· [C1] cron
        //   （workload=WORKLOAD_CRON）→ isMeta=true（前端隐藏、模型可见）
        verify(mockMessageService).createQueuedUserMessage(eq(sessionId), isNull(), eq("定时任务提示词"),
            any(), eq(true));
        // 回归防线：scheduled_task_fire 系统消息不得复活（14086c2c 用户拍板移除）
        verify(mockMessageService, never()).appendSystemSubtypeMessage(any(), any(), any());
        ArgumentCaptor<RunRequest> captor = ArgumentCaptor.forClass(RunRequest.class);
        verify(loop).run(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("目标2: run 结束返回 AgentState → 实时落库 SPI 武装 + complete 收口 + clearAppendListener（真实会话）")
    void runOneAgentLoop_persistsResultAfterRun() throws Exception {
        // WHY（规则九）: cron 结果必须落 transcript（assistant/tool/final，user 已落库）——CC onFireTask
        //   结果实时写 transcript。实时化后（2026-09-03）不再调 ChatService.replayAndPersist 批量：run 前经
        //   ChatService.armRealTimePersist 武装 appendListener（doRun 历史注入后逐条实时落库），run 后
        //   clearAppendListener + publishCompleteEvent 收口。mock loop 不消费 enabler → 捕获 consumer 手动
        //   accept 验证真实接线。streamTopic=会话级单 topic；wsTemplate 未注入 → sendAndLog 跳过推送仅落库。
        //   RED: 未武装 / 未收口 / sessionUuid 错（GLOBAL 兜底）/ runState 丢弃 → 断言变红。
        String sessionId = "sess-result-persist";
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        AgentState state = mock(AgentState.class);
        when(loop.run(any(RunRequest.class))).thenReturn(state);
        ChatService mockChat = mock(ChatService.class);
        ReflectionTestUtils.setField(executor, "chatService", mockChat);

        QueueItem cmd = new QueueItem("定时任务提示词", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        // ① run 前武装实时落库 SPI：setPostHistoryPersistEnabler 被调 → 捕获 consumer 手动 accept →
        //    chatService.armRealTimePersist 真实接线（initialUserMessageId = cmd.uuid() = null）。
        ArgumentCaptor<java.util.function.Consumer> enablerCaptor =
            ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(loop).setPostHistoryPersistEnabler(enablerCaptor.capture());
        enablerCaptor.getValue().accept(state);
        verify(mockChat).armRealTimePersist(eq(state), eq(sessionId),
            eq("/topic/sessions/" + sessionId + "/stream"), isNull(), isNull());
        // ② run 结束收口：clearAppendListener + publishCompleteEvent（userMessageId=cmd.uuid()=null）
        verify(state).clearAppendListener();
        verify(mockChat).publishCompleteEvent(eq(sessionId), isNull(), eq(state),
            eq("/topic/sessions/" + sessionId + "/stream"), isNull(), anyLong(), isNull());
    }

    @Test
    @DisplayName("[cron-complete] cron 触发消费落库后 → 调 chatService.publishUserMessageEvent 推 message.user（isMeta=true，id=落库后真实 id）")
    void executeQueuedInput_cronPushesUserMessageEvent() {
        // WHY（规则九 · 前端消息顺序倒挂修复）: cron user prompt 只落库不推前端 → 前端 messages 缺锚点
        //   → 该轮 assistant 流式块被后续用户 turn complete 混收口后按 flowKey 找不到锚点插末尾 → 倒挂。
        //   落库后立即推 message.user（id=落库后真实 id，前端 appendMetaUser 占位建立锚点）。
        com.nexusai.domain.session.MessageService mockMessageService =
            mock(com.nexusai.domain.session.MessageService.class);
        ReflectionTestUtils.setField(executor, "messageService", mockMessageService);
        java.util.concurrent.Executor syncExecutor = mock(java.util.concurrent.Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        ChatService mockChat = mock(ChatService.class);
        ReflectionTestUtils.setField(executor, "chatService", mockChat);
        org.springframework.messaging.simp.SimpMessagingTemplate mockWs = mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
        ReflectionTestUtils.setField(executor, "wsTemplate", mockWs);

        String sessionId = "sess-cron-complete";
        QueueItem cmd = new QueueItem("定时任务提示词", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, "msg-cron-uuid", true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);
        // 落库返回真实 id（mock 返回 cmd.uuid() 同值）
        when(mockMessageService.createQueuedUserMessage(eq(sessionId), eq("msg-cron-uuid"), eq("定时任务提示词"),
            any(), eq(true))).thenReturn(new com.nexusai.model.session.dto.MessageCreatedResponse(
            "msg-cron-uuid", "msg-stub-pending", "/topic/sessions/" + sessionId + "/stream", false));

        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", List.of(cmd));

        verify(mockChat).publishUserMessageEvent(eq(sessionId), eq("msg-cron-uuid"), eq("定时任务提示词"),
            eq(true), eq("/topic/sessions/" + sessionId + "/stream"), eq(mockWs));
    }

    @Test
    @DisplayName("[cron-complete] cron run 结束 → 实时落库 SPI 武装 + publishCompleteEvent 收口（userMessageId=cron user 消息 id）")
    void runOneAgentLoop_pushesCompleteAfterPersist() throws Exception {
        // WHY（规则九 · 前端消息顺序倒挂修复）: cron 轮 assistant 流式块无 complete 收口 → 残留 streams →
        //   被后续用户 turn complete 混收口倒挂。实时化后（2026-09-03）run 结果已由 appendListener 实时落库，
        //   run 结束复用 ChatService.publishCompleteEvent（正常 turn 同款装配）收口，前端 finalize cron 块。
        //   userMessageId=cron user 消息 id（=cmd.uuid()="msg-cron-uuid"，同时作为实时落库归属根传参）。
        String sessionId = "sess-cron-complete";
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
        AgentState state = mock(AgentState.class);
        when(loop.run(any(RunRequest.class))).thenReturn(state);
        ChatService mockChat = mock(ChatService.class);
        ReflectionTestUtils.setField(executor, "chatService", mockChat);

        QueueItem cmd = new QueueItem("定时任务提示词", NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, "msg-cron-uuid", true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);

        ReflectionTestUtils.invokeMethod(executor, "runOneAgentLoop", cmd);

        // run 前武装实时落库 SPI：捕获 consumer 手动 accept → armRealTimePersist 以 cmd.uuid() 作归属根
        ArgumentCaptor<java.util.function.Consumer> enablerCaptor =
            ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(loop).setPostHistoryPersistEnabler(enablerCaptor.capture());
        enablerCaptor.getValue().accept(state);
        verify(mockChat).armRealTimePersist(eq(state), eq(sessionId),
            eq("/topic/sessions/" + sessionId + "/stream"), isNull(), eq("msg-cron-uuid"));
        // run 结束收口：clearAppendListener + publishCompleteEvent
        verify(state).clearAppendListener();
        verify(mockChat).publishCompleteEvent(eq(sessionId), eq("msg-cron-uuid"), eq(state),
            eq("/topic/sessions/" + sessionId + "/stream"), isNull(), anyLong(), isNull());
    }
}
