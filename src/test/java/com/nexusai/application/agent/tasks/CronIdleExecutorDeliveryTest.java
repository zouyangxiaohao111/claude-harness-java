package com.nexusai.application.agent.tasks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tasks.NotificationQueue.Priority;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.chat.ChatService;
import com.nexusai.application.chat.SlashCommandInterceptor;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [M1–M4 · 2026-09-18 子代理投递修复] 新增测试 —— 修复的<b>正确性判据</b>。
 *
 * <p><b>WHY 必须有本类（规则九 + 施工单 §二）</b>：现有 {@code CronIdleExecutorTest} <b>覆盖不到这条
 * 链路</b> —— 它直调 {@code runOneAgentLoop}（不经 {@code cronExecutor}，23 处 {@code executor.poll}
 * 直调走 fake consumer）⇒ 改动后的正确性<b>不能靠旧测试变绿</b>，必须由本类独立立证。事故形态
 * （「异步子代理跑完了但主代理收不到结果」）的全部机制都在这条链路上：
 * <ul>
 *   <li>(a) <b>跨会话隔离</b>：{@code cronExecutor} core=1/max=1 = 一条可被永久占住的 OS 线程
 *       （真因 L1：事故日志 {@code cron-idle-1} 7829 次、{@code cron-idle-2} 及以后 <b>0 次</b>）</li>
 *   <li>(b) <b>M2 保留-释放</b>：{@code QueryGuard} dispatching 保留态；漏释放 = 该会话<b>永久不再被
 *       消费</b>（本批最需要防的<b>新静默卡死</b>）</li>
 *   <li>(c) <b>M3 原子性</b>：出队只发生在「跑得起来」的地方（原 {@code pollScheduled} 绕过
 *       {@code processing} 闸直接出队 = 事故链起点）</li>
 * </ul>
 *
 * <p>⛔ 反向实验（不可省）：把 M1 改回 core=1 ⇒ (a) 必红；把 M2 的释放注释掉 ⇒ (b) 必红。
 */
class CronIdleExecutorDeliveryTest {

    /** (a) 会话 A/B 标识（独立命名空间，防与其他测试类共用的静态 RUNNING/DISPATCHING 表串台）。 */
    private static final String SESSION_A = "sess-m1-a-stuck";
    private static final String SESSION_B = "sess-m1-b-freed";

    private NotificationQueue queue;
    private CronIdleExecutor executor;
    private final List<String> reservedKeysTouched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        queue = new NotificationQueue();
        executor = new CronIdleExecutor();
        ReflectionTestUtils.setField(executor, "notificationQueue", queue);
        // 静态表清洗（防跨测试类泄漏；本类每个用例自己收尾，这里只兜底）
        LlmAgentLoop.markIdle(SESSION_A);
        LlmAgentLoop.markIdle(SESSION_B);
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(SESSION_A);
        LlmAgentLoop.markIdle(SESSION_B);
        LlmAgentLoop.markIdle(CronIdleExecutor.GLOBAL_SESSION_KEY);
        for (String key : reservedKeysTouched) {
            LlmAgentLoop.cancelReservation(key);
        }
        reservedKeysTouched.clear();
    }

    // ==================================================================================
    // (a) M1 · 跨会话隔离 —— 会话 A 占住执行通道时，会话 B 的通知仍能在 N 秒内开跑
    // ==================================================================================

    @Test
    @DisplayName("(a) M1 跨会话隔离：会话 A 长阻塞占住运行时，会话 B 的通知仍能在 5s 内开跑")
    void crossSessionIsolation_sessionAStuck_sessionBStillStarts() throws Exception {
        // WHY（规则九 · 真因 L1）: 异步子代理「跑完了但主代理收不到结果」的真因 = 执行通道 core=1
        //   = 一条可被永久占住的 OS 线程：一个卡在无限等待（权限弹窗设计上无超时）或长阻塞的 run
        //   会饿死**所有会话**的空闲代跑。
        // RED（反向实验 · 有鉴别力）: 把 AsyncConfig.cronExecutor 改回 core=1/max=1 ⇒ 会话 A 的 run
        //   占满唯一 OS 线程、会话 B 的提交只能躺在队列里 ⇒ 下方 5s await 超时 ⇒ 本用例变红。
        //   ⚠️ 判据用**生产 bean 工厂**（new AsyncConfig().cronExecutor()）而非测试自建池 ——
        //   否则本用例对 M1 的改动零鉴别力。
        Executor productionExecutor = new com.nexusai.infra.config.AsyncConfig().cronExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", productionExecutor);

        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        doAnswer(inv -> {
            RunRequest req = inv.getArgument(0);
            if (SESSION_A.equals(req.sessionId())) {
                aStarted.countDown();
                releaseA.await(60, TimeUnit.SECONDS);   // 模拟「卡在权限弹窗/长 run」的会话 A
            } else if (SESSION_B.equals(req.sessionId())) {
                bStarted.countDown();
            }
            return null;
        }).when(loop).run(any(RunRequest.class));
        installLoop(loop);

        try {
            // ① 会话 A 的通知出队并执行 → 占住执行通道
            invokeExecuteQueuedInput(List.of(cmd(SESSION_A, "会话A的异步子代理完成通知")));
            assertThat(aStarted.await(5, TimeUnit.SECONDS))
                .as("前置判据：会话 A 必须真的开跑（否则「A 占住」不成立，本用例无意义）").isTrue();

            // ② 会话 B 的通知出队并执行 → 必须在 5s 内开跑（M1 跨会话隔离）
            invokeExecuteQueuedInput(List.of(cmd(SESSION_B, "会话B的异步子代理完成通知")));
            assertThat(bStarted.await(5, TimeUnit.SECONDS))
                .as("M1 跨会话隔离：会话 A 占住执行通道时，会话 B 的通知仍必须在 5s 内开跑"
                    + "（core=1 时代 B 只能躺在队列里饿死 = 本事故症状）").isTrue();
        } finally {
            releaseA.countDown();               // 放行会话 A，防测试挂死
            if (productionExecutor instanceof ExecutorService es) {
                es.shutdownNow();
            }
        }
    }

    // ==================================================================================
    // (b) M2 · 保留-释放 —— executeQueuedInput 的**全部早退分支**后该会话仍可被再次消费
    // ==================================================================================

    @Test
    @DisplayName("(b1) M2 保留-释放：纯 task-notification 批早退分支（:531 return）后该会话仍可被消费")
    void reservationReleased_pureTaskNotificationBatchEarlyReturn() {
        String session = "sess-m2-b1";
        reservedKeysTouched.add(session);
        installSyncExecutorAndLoop(mock(LlmAgentLoop.class));

        invokeExecuteQueuedInput(List.of(
            notificationCmd(session, "子代理A完成", "n-1"),
            notificationCmd(session, "子代理B完成", "n-2")));

        assertSessionReConsumable(session);
    }

    @Test
    @DisplayName("(b2) M2 保留-释放：空白 value 的 continue 分支（:610）后该会话仍可被消费")
    void reservationReleased_blankValueContinue() {
        String session = "sess-m2-b2";
        reservedKeysTouched.add(session);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installSyncExecutorAndLoop(loop);

        invokeExecuteQueuedInput(List.of(cmd(session, "   ")));

        verify(loop, never()).run(any());                 // 该分支确实早退（未起 turn）
        assertSessionReConsumable(session);
    }

    @Test
    @DisplayName("(b3) M2 保留-释放：userInvocable=false 拒绝 continue 分支（:617）后该会话仍可被消费")
    void reservationReleased_userInvocableFalseContinue() {
        String session = "sess-m2-b3";
        reservedKeysTouched.add(session);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installSyncExecutorAndLoop(loop);
        ChatService chatService = mock(ChatService.class);
        when(chatService.rejectNonUserInvocable(any(), any(), any(), any())).thenReturn(true);
        ReflectionTestUtils.setField(executor, "chatService", chatService);

        invokeExecuteQueuedInput(List.of(cmd(session, "/not-invocable")));

        verify(loop, never()).run(any());                 // 该分支确实早退（未起 turn）
        assertSessionReConsumable(session);
    }

    @Test
    @DisplayName("(b4) M2 保留-释放：slash 非查询型终态 continue 分支（:673）后该会话仍可被消费")
    void reservationReleased_nonQueryingSlashContinue() {
        String session = "sess-m2-b4";
        reservedKeysTouched.add(session);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installSyncExecutorAndLoop(loop);
        SlashCommandInterceptor slashMock = mock(SlashCommandInterceptor.class);
        when(slashMock.intercept(any(), any(), any(), any(), any())).thenReturn(
            new SlashCommandInterceptor.SlashResolution(
                true, false, null, "<local-command-stdout>done</local-command-stdout>", null, null, "m-1"));
        ReflectionTestUtils.setField(executor, "slashInterceptor", slashMock);

        invokeExecuteQueuedInput(List.of(cmd(session, "/status")));

        verify(loop, never()).run(any());                 // 该分支确实早退（未起 turn）
        assertSessionReConsumable(session);
    }

    @Test
    @DisplayName("(b5) M2 保留-释放：正常跑完（走 runOneAgentLoop 主路径）后该会话仍可被消费")
    void reservationReleased_normalRun() {
        String session = "sess-m2-b5";
        reservedKeysTouched.add(session);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installSyncExecutorAndLoop(loop);

        invokeExecuteQueuedInput(List.of(cmd(session, "正常通知")));

        verify(loop, times(1)).run(any());
        assertSessionReConsumable(session);
    }

    @Test
    @DisplayName("(b6) M2 保留-释放：任务体抛异常（逐条 catch 分支 :686）后该会话仍可被消费")
    void reservationReleased_runThrows() {
        String session = "sess-m2-b6";
        reservedKeysTouched.add(session);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installSyncExecutorAndLoop(loop);
        doThrow(new IllegalStateException("模拟 run 内部炸")).when(loop).run(any(RunRequest.class));

        invokeExecuteQueuedInput(List.of(cmd(session, "会炸的通知")));

        assertSessionReConsumable(session);
    }

    @Test
    @DisplayName("(b7) M2 保留-释放：依赖未注入早退（:504，reserve 之前）后该会话状态干净且可被消费")
    void reservationReleased_depsMissingEarlyReturn() {
        String session = "sess-m2-b7";
        reservedKeysTouched.add(session);
        // cronExecutor/loopProvider 均不注入 → 早退（reserve 之前，压根不该占位）
        invokeExecuteQueuedInput(List.of(cmd(session, "依赖缺失")));

        assertThat(LlmAgentLoop.isSessionDispatching(session))
            .as("早退发生在 reserve 之前 ⇒ 不得留下任何占位").isFalse();
        assertSessionReConsumable(session);
    }

    @Test
    @DisplayName("(b8) M2 保留-释放：提交执行器抛异常（catch 就地释放分支）后该会话仍可被消费")
    void reservationReleased_submitThrows() {
        String session = "sess-m2-b8";
        reservedKeysTouched.add(session);
        Executor throwing = mock(Executor.class);
        doThrow(new java.util.concurrent.RejectedExecutionException("模拟执行器拒绝"))
            .when(throwing).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", throwing);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installLoop(loop);

        assertThatThrownBy(() -> invokeExecuteQueuedInput(List.of(cmd(session, "会被拒绝"))))
            .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        assertSessionReConsumable(session);
    }

    // ==================================================================================
    // (b12–b13) B2 · [2026-09-18 返工] 保留后到开跑前的 Throwable 窗口不得泄漏保留态
    // ==================================================================================

    @Test
    @DisplayName("(b12) B2：提交抛 Error（虚拟线程 Thread.start OOME）必须就地释放保留态（只捕 RuntimeException ⇒ 必红）")
    void reservationReleased_whenSubmitThrowsError() {
        // WHY（规则九 · B2 的第二个洞）: M1 后每任务新建虚拟线程 —— Thread.start() 失败抛的是
        //   OutOfMemoryError（Error，不是 RuntimeException）。原 catch (RuntimeException) 漏捕 ⇒
        //   任务体永不执行、其 finally 永不运行 ⇒ reserveKey **永久留在 DISPATCHING_SESSIONS**
        //   ⇒ 该会话 isSessionActive 恒 true ⇒ 该会话的 cron/子代理完成通知**永不再出队**。
        String session = "sess-b2-error";
        reservedKeysTouched.add(session);
        Executor throwing = mock(Executor.class);
        doThrow(new OutOfMemoryError("模拟虚拟线程 Thread.start 失败"))
            .when(throwing).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", throwing);
        installLoop(mock(LlmAgentLoop.class));

        assertThatThrownBy(() -> invokeExecuteQueuedInput(List.of(cmd(session, "提交抛 Error"))))
            .isInstanceOf(OutOfMemoryError.class);

        assertThat(LlmAgentLoop.isSessionDispatching(session))
            .as("B2：提交抛 Error 时任务体永不执行 ⇒ 保留态必须就地释放（catch 只捕 RuntimeException 时"
                + "泄漏成真，该会话通知永不再出队）").isFalse();
        assertThat(LlmAgentLoop.isSessionActive(session)).isFalse();
    }

    @Test
    @DisplayName("(b13) B2：保留之后、提交之前的日志抛错也必须释放保留态（try 起点在 reserve 后 ⇒ 必红）")
    void reservationReleased_whenSubmitSideLogThrows() {
        // WHY（规则九 · B2 的第一个洞）: 原实现 try 起点在「提交侧 INFO」**之后** —— 该行（OOME /
        //   appender 异常）抛错时 reserve 已成功但没有任何释放点 ⇒ 保留态永久泄漏。
        String session = "sess-b2-logthrow";
        reservedKeysTouched.add(session);
        ReflectionTestUtils.setField(executor, "cronExecutor", new ManualExecutor());  // 提交侧永不执行
        installLoop(mock(LlmAgentLoop.class));
        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        ThrowingAppender throwing = new ThrowingAppender();
        throwing.start();
        logger.addAppender(throwing);
        try {
            assertThatThrownBy(() -> invokeExecuteQueuedInput(List.of(cmd(session, "提交侧日志抛错"))))
                .isInstanceOf(SimulatedSubmitLogError.class)
                .hasMessageContaining("模拟提交侧日志抛错");

            assertThat(LlmAgentLoop.isSessionDispatching(session))
                .as("B2：reserve 已成功、提交尚未成功时抛错 ⇒ 保留态必须就地释放（try 起点在 reserve"
                    + " 之后 ⇒ 泄漏成真，该会话通知永不再出队）").isFalse();
        } finally {
            logger.detachAppender(throwing);
        }
    }

    // ==================================================================================
    // (b9–b11) T1 · [2026-09-18 返工补测] M2「保留**真的生效过**」
    //
    // 缺口（变异实证）：把 reserve 改成永不占位 ⇒ 上面 b1–b8 全绿 —— 它们只证
    // 「一旦占位就一定释放」，**不证占位真的生效过**（占位=0 时「释放」恒真）。
    // 下列三条分别把三种变异钉红：
    //   · reserve 永不占位        → (b9) 红（运行中新条目会被取走）
    //   · 保留键换成共享常量      → (b10) 红（本会话不再 dispatching）
    //   · isSessionActive 摘 dispatching 臂 → (b11) 红（谓词不再跳过该会话）
    // ==================================================================================

    @Test
    @DisplayName("(b9) T1 保留生效·批次运行中：同会话新条目不得被取走（reserve 永不占位 ⇒ 必红）")
    void reservationHoldsSession_whileBatchInFlight() throws Exception {
        // WHY（规则九 · M2 的正面判据）: 保留态的**唯一价值**是「同会话条目在保留期内不被取走」。
        //   若只断言「结束后已释放」（b1–b8），永不占位的实现照样全绿 —— 保留成了纯装饰。
        String session = "sess-t1-hold";
        reservedKeysTouched.add(session);
        Executor productionExecutor = new com.nexusai.infra.config.AsyncConfig().cronExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", productionExecutor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        installBlockingLoop(started, release);

        try {
            invokeExecuteQueuedInput(List.of(cmd(session, "第一批（长跑中）")));
            assertThat(started.await(5, TimeUnit.SECONDS))
                .as("前置判据：第一批必须真的在跑（否则「运行中」不成立，本用例无意义）").isTrue();

            // 运行中：该会话的**新**条目到达 —— 必须留在队列（取走 = 同会话并发 + 本事故同类）
            queue.enqueue(cmd(session, "运行中到达的第二批"));
            List<QueueItem> consumed = new ArrayList<>();
            boolean processed = executor.poll(consumed::addAll);

            assertThat(processed)
                .as("T1：保留期内该会话的新条目**不得被取走**（reserve 永不占位 ⇒ 本断言变红）").isFalse();
            assertThat(consumed).as("T1：不得有任何条目被消费").isEmpty();
            assertThat(queue.size()).as("T1：条目必须留在队列（延迟送达，而非「取出即删」的静默丢）")
                .isEqualTo(1);
        } finally {
            release.countDown();
            shutdownQuietly(productionExecutor);
        }
    }

    @Test
    @DisplayName("(b10) T1 per-session：批次运行中本会话 dispatching=true、另一会话=false（键换常量 ⇒ 必红）")
    void reservationIsPerSession_notSharedConstant() throws Exception {
        // WHY（规则九）: CC QueryGuard 是 **per-session** 的三态；若保留键退化成共享常量，
        //   则「任意会话在跑」都会把**所有**会话的条目一起冻结（比不占位更坏：跨会话饿死）。
        String session = "sess-t1-key-a";
        String other = "sess-t1-key-b";
        reservedKeysTouched.add(session);
        reservedKeysTouched.add(other);
        Executor productionExecutor = new com.nexusai.infra.config.AsyncConfig().cronExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", productionExecutor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        installBlockingLoop(started, release);

        try {
            invokeExecuteQueuedInput(List.of(cmd(session, "本会话长跑中")));
            assertThat(started.await(5, TimeUnit.SECONDS)).as("前置判据：批次必须真的在跑").isTrue();

            assertThat(LlmAgentLoop.isSessionDispatching(session))
                .as("T1：运行中本会话必须处于 dispatching（键换共享常量时本断言变红）").isTrue();
            assertThat(LlmAgentLoop.isSessionDispatching(other))
                .as("T1 per-session：另一会话**不得**被占位（共享常量键会把它也占住 ⇒ 跨会话饿死）")
                .isFalse();
            assertThat(LlmAgentLoop.isSessionActive(session)).isTrue();
            assertThat(LlmAgentLoop.isSessionActive(other)).isFalse();
        } finally {
            release.countDown();
            shutdownQuietly(productionExecutor);
        }
    }

    @Test
    @DisplayName("(b11) T1 poll 谓词跳过 dispatching 会话（isSessionActive 摘 dispatching 臂 ⇒ 必红）")
    void pollPredicateSkipsDispatchingSession() {
        // WHY（规则九 · M2 与 poll 的接线）: 保留态**只有**被 poll 谓词读到才有意义。
        //   手工置 dispatching（不依赖执行器时序）后，该会话的条目必须留在队列。
        String session = "sess-t1-pred";
        reservedKeysTouched.add(session);
        assertThat(LlmAgentLoop.reserve(session))
            .as("前置判据：手工占位必须成功（否则本用例不成立）").isTrue();

        queue.enqueue(cmd(session, "该会话的待消费通知"));
        List<QueueItem> consumed = new ArrayList<>();
        boolean processed = executor.poll(consumed::addAll);

        assertThat(processed)
            .as("T1：poll 谓词必须跳过 dispatching 会话（isSessionActive 摘掉 dispatching 臂时本断言变红）")
            .isFalse();
        assertThat(consumed).as("T1：该会话的条目不得被取走").isEmpty();
        assertThat(queue.size()).as("T1：条目必须留在队列").isEqualTo(1);
    }

    // ==================================================================================
    // (c) M3 · 原子性 —— 并发触发 pollScheduled 与事件驱动 ⇒ 同一批项只被取走一次
    // ==================================================================================

    @Test
    @DisplayName("(c) M3 原子性：在飞 poll 未跑完时，3s 兜底轮询不得绕过闸把同一批项取走")
    void inFlightPollBlocksScheduledPollFromDequeuing() {
        // WHY（规则九 · 事故链起点）: 原 pollScheduled **直接**调 poll、绕过 processing 闸 ⇒
        //   「3s 线程出队（取出即删）→ 提交给已死的 cron-idle-1 → 无 ack 无回队 → 永久静默丢」。
        //   改为与事件驱动同构（闸内提交到执行器后再 poll）后：执行通道不空时条目**根本不会被取走**
        //   ⇒ 症状从「永久静默丢」变成「延迟送达」。
        // RED（反向实验）: 把 pollScheduled 还原为 `poll(this::executeQueuedInput)` 直调 ⇒ 第二次
        //   调用会把条目取走（queue 清空）而没有任何线程在跑它们 ⇒ 下方第一条断言变红。
        ManualExecutor manual = new ManualExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", manual);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installLoop(loop);

        String session = "sess-m3-atomic";
        reservedKeysTouched.add(session);
        for (int i = 0; i < 3; i++) {
            queue.enqueue(cmd(session, "同批通知-" + i));
        }

        // ① 事件驱动触发：闸 CAS 成功 → 提交 poll（Runnable 尚未执行 = 在飞）
        ReflectionTestUtils.invokeMethod(executor, "onQueueChanged");
        // ② 3s 兜底轮询触发：闸已被占 ⇒ 必须跳过，绝不绕过闸出队
        executor.pollScheduled();

        assertThat(queue.size())
            .as("M3：闸被占（已有 poll 在飞）时，3s 兜底轮询不得出队 —— 同一批项只能被取走一次")
            .isEqualTo(3);

        // ③ 在飞 poll 真正开跑后才出队（且只出队一次）
        manual.runAll();

        assertThat(queue.size()).as("在飞 poll 开跑后应把该批全部取走").isZero();
        verify(loop, times(3)).run(any(RunRequest.class));
    }

    @Test
    @DisplayName("(c2) M3 原子性：两入口并发高频触发 ⇒ 每条恰好被消费一次（不重复、不丢失）")
    void concurrentTriggers_dequeueEachItemExactlyOnce() throws Exception {
        // 同步执行器（提交即跑）⇒ 每次成功取得闸的触发就地完成「出队 + 提交 + 跑」全流程，
        //   故「消费次数」可由 mock loop.run 的入参（userPrompt）逐一核对。
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installSyncExecutorAndLoop(loop);

        String session = "sess-m3-concurrent";
        reservedKeysTouched.add(session);
        int items = 20;
        List<String> values = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            String v = "并发通知-" + i;
            values.add(v);
            queue.enqueue(cmd(session, v));
        }

        // 两个入口并发高频触发：事件驱动（onQueueChanged，私有） vs 3s 兜底（pollScheduled，@Scheduled）
        // ⚠️ 两入口都经 processing 闸 → 同一时刻最多一个 poll ⇒ 同一批项只被取走一次。
        CountDownLatch start = new CountDownLatch(1);
        Thread eventThread = new Thread(() -> {
            awaitQuietly(start);
            for (int i = 0; i < 200; i++) {
                ReflectionTestUtils.invokeMethod(executor, "onQueueChanged");
            }
        }, "m3-event");
        Thread scheduledThread = new Thread(() -> {
            awaitQuietly(start);
            for (int i = 0; i < 200; i++) {
                executor.pollScheduled();
            }
        }, "m3-scheduled");
        eventThread.start();
        scheduledThread.start();
        start.countDown();
        eventThread.join(30_000);
        scheduledThread.join(30_000);

        assertThat(queue.size()).as("全部消费后队列应为空").isZero();
        org.mockito.ArgumentCaptor<RunRequest> captor = org.mockito.ArgumentCaptor.forClass(RunRequest.class);
        verify(loop, times(items)).run(captor.capture());
        List<String> prompts = captor.getAllValues().stream().map(RunRequest::userPrompt).toList();
        assertThat(prompts).as("每条恰好被消费一次（不重复、不丢失）")
            .containsExactlyInAnyOrderElementsOf(values);
        assertThat(prompts.stream().distinct().count())
            .as("不得有任意一条被重复出队/重复消费").isEqualTo(items);
    }

    // ==================================================================================
    // (d) M4 · 未开跑 WARN（诚实化判据：该批 uuid + submittedAtMs，非闸时长）
    // ==================================================================================

    @Test
    @DisplayName("(c3) M3 守卫：提交 poll 失败必须释放 processing 闸（否则两条入口同时永久停摆）")
    void submitFailureReleasesGate() {
        // WHY（规则九 · M3 引入的**新**静默失效面）: M3 后 3s 兜底也走 processing 闸 ⇒ 闸若因
        //   「提交失败但任务体永不执行」而恒 true，则事件驱动与兜底**同时**永久停摆 —— 原实现
        //   兜底不看闸，尚能自愈。故提交侧必须有释放守卫。
        // RED: 删掉两处 catch 的 processing.set(false) ⇒ 本用例变红。
        //
        // [P4 · 2026-09-18 返工] ⚠️ 本用例 phase-1 原来靠**空队列**跑（先触发、后入队）。P4 给事件驱动
        //   分支补上「空队列短路」后，空队列连提交都不会发生 ⇒ phase-1 走不到「提交抛错」分支，
        //   判别力静默归零（正是本用例自身注释警告的失效模式：空转绿）。故改为**先入队再触发**，
        //   让提交失败分支真实命中；顺带把「失败提交后条目仍留在队列（延迟送达，非丢弃）」升级为显式断言。
        Executor throwing = mock(Executor.class);
        doThrow(new java.util.concurrent.RejectedExecutionException("模拟执行器拒绝"))
            .when(throwing).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", throwing);
        // [M4 补] 必须补上 loopProvider：出队前守卫已收敛为 canDispatch()（两条通道都要在），
        //   否则 loopProvider 缺失会让本用例**走不到「提交抛错」分支**而变成空转绿（判别力静默归零）。
        installLoop(mock(LlmAgentLoop.class));
        String session = "sess-m3-gate-guard";
        reservedKeysTouched.add(session);
        queue.enqueue(cmd(session, "提交失败时留在队列的通知"));

        ReflectionTestUtils.invokeMethod(executor, "onQueueChanged");
        executor.pollScheduled();

        java.util.concurrent.atomic.AtomicBoolean gate =
            (java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(executor, "processing");
        assertThat(gate).isNotNull();
        assertThat(gate.get())
            .as("提交失败后 processing 闸必须已释放（否则事件驱动+3s 兜底两条入口同时永久停摆）")
            .isFalse();
        assertThat(queue.size())
            .as("提交失败发生在**入口守卫**（poll 任务从未启动 ⇒ 未出队）⇒ 条目必须原样留在队列（延迟送达）")
            .isEqualTo(1);

        // 端到端：闸释放后，换成可用执行器 ⇒ 该会话的通知必须还能被消费（入口未停摆，且不丢件）
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installSyncExecutorAndLoop(loop);
        queue.enqueue(cmd(session, "闸释放后的通知"));
        executor.pollScheduled();
        assertThat(queue.size()).as("闸已释放 ⇒ 兜底轮询必须恢复正常消费").isZero();
        // 2 条 = phase-1 留下的那条 + 新入队的那条（都在同一个批次里被同一轮 poll 取走）
        verify(loop, times(2)).run(any(RunRequest.class));
    }

    @Test
    @DisplayName("(d) M4：提交后 N 秒未见开跑必须 WARN（判据=该批 uuid + submittedAtMs）")
    void submittedBatchNeverStarted_logsWarnWithBatchUuid() throws Exception {
        // WHY（规则九 · 盲区 L5）: 事故里「轮询消费队列并启动 agent_loop」出现 5 次而真正启动 0 次
        //   —— 假绿日志让「已提交」被误读成「已开跑」。本用例锁死诚实的反面判据：提交后 N 秒仍未
        //   开跑必须留下带**该批 uuid** 的 WARN。
        // RED: 删掉 DISPATCH_WATCHDOG 的未开跑告警 ⇒ 本用例变红。
        ManualExecutor manual = new ManualExecutor();   // 提交了但永不执行
        ReflectionTestUtils.setField(executor, "cronExecutor", manual);
        installLoop(mock(LlmAgentLoop.class));

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String session = "sess-m4-warn";
        reservedKeysTouched.add(session);
        try {
            invokeExecuteQueuedInput(List.of(cmdWithUuid(session, "永不开跑的通知", "uuid-m4-probe")));

            // 已提交但未开跑的即时证据（提交 ≠ 开跑）
            assertThat(appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("[M4]") && m.contains("尚未开跑") && m.contains("uuid-m4-probe")))
                .as("提交侧必须如实标注「尚未开跑」并携带该批 uuid").isTrue();

            // 等到看门狗判定窗口（DISPATCH_STALL_WARN_SECONDS = 10s）之后
            List<String> warns = waitForWarnContaining(appender, "未见开跑", 25_000);
            assertThat(warns)
                .as("同批提交后 N 秒未见开跑必须 WARN，且判据必须落到该批 uuid（而非 processing 闸时长）")
                .isNotEmpty();
            assertThat(warns.get(0)).contains("uuid-m4-probe");
        } finally {
            logger.detachAppender(appender);
            LlmAgentLoop.cancelReservation(session);
        }
    }

    // ==================================================================================
    // (e) T2 · [2026-09-18 返工补测] C1「请求边界锚 msgs= 必须为 INFO」
    // ==================================================================================

    @Test
    @DisplayName("(e) T2 C1：请求边界锚 msgs= 必须为 INFO 级（改回 debug ⇒ 本用例必红）")
    void requestBoundaryMsgsLog_isInfoLevel() {
        // WHY（规则九 · 事故盲区 L5）: 事故里「drain 注入 N 条」有日志、同 turn 的 msgs= 无日志
        //   ⇒「注入到底进没进请求」无法闭合。C1 把该行 debug → INFO 就是为了让它**可观测**。
        //   判据钉在**级别**上：先把 logger 有效级别设成 INFO 再收 appender 事件 —— call site 若是
        //   debug，事件根本到不了 appender ⇒「存在 msgs= 事件」必红。
        //   （这是级别判据，不是「源码里有这行」的文本判据。）
        Logger logger = (Logger) LoggerFactory.getLogger(LlmAgentLoop.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);          // ⭐ 关键：debug 级事件在此级别下被过滤掉
        logger.addAppender(appender);
        try {
            driveOneTurnThroughQueryLoop();

            List<ILoggingEvent> msgsEvents = appender.list.stream()
                .filter(e -> e.getFormattedMessage() != null
                    && e.getFormattedMessage().contains("msgs="))
                .collect(java.util.stream.Collectors.toList());
            assertThat(msgsEvents)
                .as("C1：每个 turn 的请求边界锚 msgs= 必须可观测（call site 是 debug 时事件到不了"
                    + " appender ⇒ 本断言红）")
                .isNotEmpty();
            assertThat(msgsEvents.get(0).getLevel())
                .as("C1：msgs= 必须为 INFO 级（改回 log.debug 时本用例必红）")
                .isEqualTo(Level.INFO);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================
    // (f) B1 · [2026-09-18 返工] 空队列不得成为噪声源（本批新引入的 INFO 回归）
    // ==================================================================================

    @Test
    @DisplayName("(f1) B1：空队列的 3s 兜底轮询不得打任何 INFO（噪声回归）+ 闸必须释放")
    void emptyQueueScheduledPoll_logsNoInfoAndReleasesGate() {
        // WHY（规则九 + 本批自身目的）: 固定 3s 节奏里的「提交侧 INFO + 任务体首行 INFO」在**空队列**下
        //   也每 3s 打 2 行 = 57600 行/天（约 8MB）；改前旧实现空队列**一行不打**。
        //   同仓先例 WebSocketDeliveryDiagnostics 逐字写「本类自身绝不能变成噪声源」。
        // RED: 删掉空队列短路 ⇒「零 INFO」断言变红。
        ManualExecutor manual = new ManualExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", manual);
        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);          // INFO 及以上都收 → 只要打了 INFO 就必被捕获
        logger.addAppender(appender);
        try {
            executor.pollScheduled();          // 空队列（固定 3s 节奏的一拍）
            executor.pollScheduled();          // 下一拍

            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .as("B1：空队列不得产生任何 INFO（改前旧实现空队列一行不打；每拍 2 行 = 57600 行/天）")
                .isEmpty();
            assertThat(manual.pendingCount())
                .as("B1：空队列不得向执行器提交空转的 poll 任务").isZero();
            Object gate = ReflectionTestUtils.getField(executor, "processing");
            assertThat(gate).isInstanceOf(java.util.concurrent.atomic.AtomicBoolean.class);
            assertThat(((java.util.concurrent.atomic.AtomicBoolean) gate).get())
                .as("B1：空队列短路 return **不得带着闸** return（否则两条入口永久停摆）").isFalse();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    @DisplayName("(f2) B1：队列非空时仍必须留下**入口级**「提交→开跑」成对 INFO（防过度静音）")
    void nonEmptyQueueScheduledPoll_keepsSubmitStartedInfoPair() {
        // WHY: 修噪声的**正确边界** = 只压掉空转，不得把「真实投递的可观测性」一起静音
        //   （M4 的存在理由就是「提交 ≠ 开跑」必须可判）。
        // [F2 · 2026-09-18 返工] ⛔ 原断言是**假守护**：判据只写 contains("[M4]") && contains("已开跑")
        //   —— 而内层 executeQueuedInput 的任务体首行（:980）同样打「[M4] 已开跑」⇒ **删掉入口级
        //   那行，两用例照样绿**，而 DisplayName 声称守护的正是**入口级**配对（「提交≠开跑」在此
        //   可判）。现收紧为**入口标识**（每入口一条唯一措辞）：
        //   · 3s 兜底：「3s 兜底轮询 → 提交 poll 到执行器」/「poll 已开跑（3s 兜底轮询）」
        //   · 事件驱动：「队列变更 → 提交 poll 到执行器」/「poll 已开跑（事件驱动）」
        //   反向实验：注释掉入口级那行 ⇒ 本用例必红（见交付说明）。
        ManualExecutor manual = new ManualExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", manual);
        installLoop(mock(LlmAgentLoop.class));
        String session = "sess-b1-info";
        reservedKeysTouched.add(session);
        queue.enqueue(cmd(session, "非空队列探针"));

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            executor.pollScheduled();          // 队列非空 → 提交
            manual.runAll();                   // 任务体真的开跑（含 poll 消费 + 转交下一步）

            List<String> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.toList());
            assertThat(infos.stream().anyMatch(
                    m -> m.contains("[M4]") && m.contains("3s 兜底轮询 → 提交 poll 到执行器")))
                .as("B1：非空队列仍须留下**入口级**「已提交（尚未开跑）」INFO（3s 兜底入口）"
                    + "—— 提交 ≠ 开跑必须可判；⛔ 内层 executeQueuedInput 的同名措辞不算数（假守护）")
                .isTrue();
            assertThat(infos.stream().anyMatch(
                    m -> m.contains("[M4]") && m.contains("poll 已开跑（3s 兜底轮询）")))
                .as("B1：任务体开跑后仍须留下**入口级**「已开跑」INFO（3s 兜底入口）").isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================
    // (g) [M3 补 · 2026-09-18 返工] cronExecutor 未注入 ⇒ 绝不出队
    //     （原实现出队即丢弃 + 一条声称「已出队并提交」的假绿 INFO）
    // ==================================================================================

    @Test
    @DisplayName("(g) M3 补：cronExecutor 未注入时，3s 兜底轮询不得出队（出队即丢弃 = 静默丢件）")
    void nullExecutorScheduledPoll_mustNotDequeue() {
        // WHY（规则九 + 本批自身目的「日志即证据」）: cronExecutor 未注入 ⇒ executeQueuedInput 顶部
        //   恒早退（:661 的 `cronExecutor == null || loopProvider == null`）⇒ 本分支下**任何** poll
        //   都是必丢件：出队（取出即删）后无人跑得起来，条目既不在队列、也不会被送达。原实现还在
        //   此处打 INFO 声称「本批已出队并提交」= 假绿（与本批修的事故盲区 L5 同类）。
        //   判据钉在**队列有没有被取走**（行为层）——措辞可以再改，丢件不可逆。
        // RED（反向实验）: 把本分支还原为 `boolean processed = poll(this::executeQueuedInput);`
        //   ⇒ 条目被取走（queue 清空）且出现 [M4] INFO ⇒ 下方 queue.size 与 WARN 两条断言同时变红。
        String session = "sess-m3-nullexec";
        reservedKeysTouched.add(session);
        queue.enqueue(cmd(session, "未注入执行器时的通知"));
        installLoop(mock(LlmAgentLoop.class));     // ⛔ 故意**不注入** cronExecutor

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            executor.pollScheduled();

            assertThat(queue.size())
                .as("[M3 补] cronExecutor 未注入 ⇒ 本分支永远跑不起来 ⇒ 不得出队（出队即丢弃）")
                .isEqualTo(1);
            assertThat(LlmAgentLoop.isSessionActive(session))
                .as("[M3 补] 未出队 ⇒ 不得留下任何 dispatching 保留态占位").isFalse();
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("[M4]")))
                .as("[M3 补] 未提交也未出队 ⇒ 绝不得留下「[M4] 本批已出队并提交」式假绿 INFO")
                .isEmpty();
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("cronExecutor 未注入")))
                .as("[M3 补] fail-loud：未注入必须留下如实 WARN（本批不提交、条目留队列）")
                .hasSize(1);
            Object gate = ReflectionTestUtils.getField(executor, "processing");
            assertThat(((java.util.concurrent.atomic.AtomicBoolean) gate).get())
                .as("[M3 补] 早退 return 不得带着 processing 闸（否则两条入口永久停摆）").isFalse();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================
    // (h) [M4 补 · 2026-09-18 返工] loopProvider 未注入 ⇒ 绝不出队
    //     复核发现「同一处二元判据的两个拷贝漂移」：出队前守卫只判 cronExecutor，而出队回调
    //     executeQueuedInput 顶部判 `cronExecutor == null || loopProvider == null` ⇒ loopProvider
    //     缺失时条目**先被取走（取出即删）再被丢弃**，而 [M4] INFO 照样声称「已出队并提交」。
    //     真实执行需要两条通道：cronExecutor（提交轮询任务）+ loopProvider（runAgentLoop :1039）。
    // ==================================================================================

    @Test
    @DisplayName("(h1) M4 补：loopProvider 未注入（cronExecutor 在）时，3s 兜底轮询不得出队")
    void nullLoopProviderScheduledPoll_mustNotDequeue() {
        // WHY（规则九 + M3「出队只发生在跑得起来的地方」）: 出队前守卫若只判 cronExecutor，则
        //   loopProvider 缺失时：poll 出队（取出即删）→ 回调顶部命中早退 ⇒ 该批**既不在队列、也永不
        //   被送达** = 静默丢件；且日志照样报「[M4] poll 已开跑 / 本批已出队并提交执行器」= 假绿
        //   （与本批修的事故盲区 L5 同类：日志即证据，但它证明的是错误结论）。
        //   判据钉在**队列有没有被取走**（行为层）：条目可以晚送，不可消失。
        // RED（反向实验）: 把出队前守卫改回只判 cronExecutor ⇒ 条目被取走、manual 收到空转任务 ⇒
        //   下方 queue.size / pendingCount / WARN 三条断言同时变红。
        String session = "sess-m4-nullloop";
        reservedKeysTouched.add(session);
        queue.enqueue(cmd(session, "loopProvider 未注入时的通知"));
        ManualExecutor manual = new ManualExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", manual);   // ⛔ 故意**不注入** loopProvider

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            executor.pollScheduled();
            manual.runAll();               // 即便硬跑已提交的任务体，也不得把条目取走

            assertThat(queue.size())
                .as("[M4 补] loopProvider 未注入 ⇒ 本批永远跑不起来 ⇒ 不得出队（出队即丢弃）")
                .isEqualTo(1);
            assertThat(manual.pendingCount())
                .as("[M4 补] 跑不起来则连轮询任务都不该提交（M3：出队只发生在跑得起来的地方）")
                .isZero();
            assertThat(LlmAgentLoop.isSessionActive(session))
                .as("[M4 补] 未出队 ⇒ 不得留下任何 dispatching 保留态占位").isFalse();
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("[M4]")))
                .as("[M4 补] 未提交也未出队 ⇒ 绝不得留下「[M4] 已出队并提交 / poll 已开跑」式假绿 INFO")
                .isEmpty();
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("loopProvider 未注入")))
                .as("[M4 补] fail-loud：未注入必须留下如实 WARN（本批不提交、条目留队列）")
                .hasSize(1);
            Object gate = ReflectionTestUtils.getField(executor, "processing");
            assertThat(((java.util.concurrent.atomic.AtomicBoolean) gate).get())
                .as("[M4 补] 早退 return 不得带着 processing 闸（否则两条入口永久停摆）").isFalse();

            // 正面判据「延迟送达 ≠ 丢弃」：补上缺失通道后，留在队列的条目必须仍能被消费。
            LlmAgentLoop loop = mock(LlmAgentLoop.class);
            installSyncExecutorAndLoop(loop);
            executor.pollScheduled();
            assertThat(queue.size())
                .as("[M4 补] 补上 loopProvider 后，留在队列的条目必须被送达（不丢件）").isZero();
            verify(loop, times(1)).run(any(RunRequest.class));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    @DisplayName("(h2) M4 补：loopProvider 未注入（cronExecutor 在）时，事件驱动入口不得出队")
    void nullLoopProviderQueueChanged_mustNotDequeue() {
        // WHY: 与 h1 同一判据的另一半 —— onQueueChanged 是**主路径**（0 延迟，P3 事件驱动），
        //   原文同样只判 cronExecutor ⇒ 同样「先取走再丢」。两个入口 + 回调兜底必须判**同一条件**
        //   （判决单点 canDispatch ⇒ 不再有第二份拷贝可以漂移）。
        // RED: 把 onQueueChanged 的守卫改回只判 cronExecutor ⇒ pendingCount==1、runAll 后队列被清空
        //   ⇒ 下方两条断言同时变红。
        String session = "sess-m4-nullloop-event";
        reservedKeysTouched.add(session);
        queue.enqueue(cmd(session, "事件驱动入口的通知"));
        ManualExecutor manual = new ManualExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", manual);   // ⛔ 故意**不注入** loopProvider

        ReflectionTestUtils.invokeMethod(executor, "onQueueChanged");
        manual.runAll();

        assertThat(manual.pendingCount())
            .as("[M4 补] 事件驱动入口同样不得向执行器提交「跑不起来」的轮询任务").isZero();
        assertThat(queue.size())
            .as("[M4 补] 事件驱动入口同样不得出队 —— 条目可以晚送，不可消失").isEqualTo(1);
        assertThat(LlmAgentLoop.isSessionActive(session))
            .as("[M4 补] 未出队 ⇒ 不得留下任何 dispatching 保留态占位").isFalse();
        Object gate = ReflectionTestUtils.getField(executor, "processing");
        assertThat(((java.util.concurrent.atomic.AtomicBoolean) gate).get())
            .as("[M4 补] 未提交 ⇒ 闸必须已就地释放（否则两条入口永久停摆）").isFalse();
    }

    // ==================================================================================
    // (i) [P1 · 2026-09-18 返工] 「出队之后才失败」⇒ 整批**回队**（不丢件）+ 事件驱动自触发抑制
    //
    //   复核实测缺陷：内层 `cronExecutor.execute(...)` 抛 RejectedExecutionException ⇒
    //   [P1] queue.size=0 / runCalled=0 ⇒ 条目永久消失（只有 log.error，**无回队**）。
    //   ⚠️ 与「出队前守卫判 cronExecutor 字段非 null」是两件事 —— 字段非 null ≠ 执行器此刻仍接受任务。
    // ==================================================================================

    @Test
    @DisplayName("(i) P1：内层提交被拒（出队之后才失败）⇒ 整批必须回队（不丢件）+ 抑制事件驱动自触发")
    void innerSubmitRejected_requeuesBatchInsteadOfLosingIt() {
        String session = "sess-p1-requeue";
        reservedKeysTouched.add(session);
        RejectInnerSubmitExecutor rejecting = new RejectInnerSubmitExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", rejecting);
        installLoop(mock(LlmAgentLoop.class));
        queue.enqueue(cmdWithUuid(session, "内层提交会被拒的通知A", "uuid-p1-a"));
        queue.enqueue(cmdWithUuid(session, "内层提交会被拒的通知B", "uuid-p1-b"));

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            // ① 入口提交（第 1 次）就地跑：poll 出队 → executeQueuedInput → 内层提交（第 2 次）被拒
            executor.pollScheduled();

            assertThat(rejecting.submitCount())
                .as("前置判据：必须真的走到「内层提交」这一步（否则本用例对 P1 零鉴别力）")
                .isGreaterThanOrEqualTo(2);
            assertThat(queue.size())
                .as("[P1] 内层提交失败 ⇒ 整批必须回队（原实现 queue.size=0：条目永久消失 = 静默丢件）")
                .isEqualTo(2);
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("已**回队**") && m.contains("uuid-p1-a")))
                .as("[P1] 回队必须显式可观测（ERROR 携带该批 uuids），不得静默")
                .isNotEmpty();
            // ② 抑制窗口（防毫秒级热循环）：回队那次 enqueue 会 fireOnChange ⇒ 事件驱动若不被抑制，会
            //    **立即**把同一批再取走 → 再失败 → 再回队（每轮 2 INFO + 1 ERROR，无上限）。
            int submitsBefore = rejecting.submitCount();
            ReflectionTestUtils.invokeMethod(executor, "onQueueChanged");
            assertThat(rejecting.submitCount())
                .as("[P1] 回队后的事件驱动自触发必须在抑制窗口内被丢弃（否则 = 毫秒级热循环）")
                .isEqualTo(submitsBefore);
            assertThat(queue.size()).as("[P1] 抑制窗口内条目必须仍在队列（延迟送达 ≠ 丢弃）").isEqualTo(2);
            Object gate = ReflectionTestUtils.getField(executor, "processing");
            assertThat(((java.util.concurrent.atomic.AtomicBoolean) gate).get())
                .as("[P1] 所有出口（含回队后的 rethrow）都必须释放 processing 闸").isFalse();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================
    // (j) [P2 · 2026-09-18 返工] 通道对象存在 ≠ 通道可用
    //     复核实测：loopProvider 非 null（mock）但 getObject() 抛 NoSuchBeanDefinitionException
    //     ⇒ canDispatch() 为 true ⇒ 出队 ⇒ 逐条 catch 吞掉 ⇒ 永久消失。
    // ==================================================================================

    @Test
    @DisplayName("(j) P2：loopProvider 非 null 但 getObject() 抛 ⇒ 出队前即判「不可用」⇒ 绝不出队；硬跑也不丢件")
    void loopProviderCannotProduceInstance_mustNotDequeue() {
        String session = "sess-p2-nobean";
        reservedKeysTouched.add(session);
        queue.enqueue(cmd(session, "无 LlmAgentLoop bean 时的通知"));
        ManualExecutor manual = new ManualExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", manual);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenThrow(
            new org.springframework.beans.factory.NoSuchBeanDefinitionException("No bean of type LlmAgentLoop"));
        // ⛔ 关键：字段**非 null**（Spring 恒注入 ObjectProvider）—— 旧判据在此为 true ⇒ 出队即丢
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            executor.pollScheduled();
            manual.runAll();      // 即便硬跑「已提交的任务体」，也不得把条目取走（旧判据下这里必丢）

            assertThat(queue.size())
                .as("[P2] 通道产出不了实例 ⇒ 不得出队（条目可以晚送，不可消失）").isEqualTo(1);
            assertThat(manual.pendingCount())
                .as("[P2] 跑不起来则连轮询任务都不该提交（M3：出队只发生在跑得起来的地方）").isZero();
            assertThat(LlmAgentLoop.isSessionActive(session))
                .as("[P2] 未出队 ⇒ 不得留下任何 dispatching 保留态占位").isFalse();
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("[M4]")))
                .as("[P2] 未提交也未出队 ⇒ 绝不得留下「[M4] 已出队并提交 / poll 已开跑」式假绿 INFO")
                .isEmpty();
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("本批不提交、也不出队")
                        && m.contains("无法产出 LlmAgentLoop 实例")))
                .as("[P2] fail-loud：必须留下如实 WARN 点出「字段在但产出不了实例」（⛔ 不得笼统说「未注入」）")
                .hasSize(1);

            // 正面判据「延迟送达 ≠ 丢弃」：通道恢复后，留在队列的条目必须仍能被消费。
            LlmAgentLoop loop = mock(LlmAgentLoop.class);
            installSyncExecutorAndLoop(loop);
            executor.pollScheduled();
            assertThat(queue.size())
                .as("[P2] 通道恢复后，留在队列的条目必须被送达（不丢件）").isZero();
            verify(loop, times(1)).run(any(RunRequest.class));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================
    // (k) [P2 前提实跑 · 2026-09-18 返工] `@Autowired(required=false) ObjectProvider` 在**无候选 bean**
    //     时是否仍注入非 null —— P2 缺陷的**可达性前提**，本轮用 Spring 上下文实跑确认（不是推断）：
    //     Spring 的 resolveDependency 对 ObjectProvider 依赖类型直接返回 DependencyObjectProvider，
    //     **不查候选** ⇒ 字段恒非 null，而 getObject() 抛 NoSuchBeanDefinitionException。
    // ==================================================================================

    @Test
    @DisplayName("(k) P2 前提实跑：无 LlmAgentLoop bean 时 ObjectProvider 仍注入非 null（getObject() 抛"
        + " NoSuchBeanDefinition）⇒ canDispatch() 必须为 false")
    void springContext_objectProviderInjectedNonNullWithoutCandidateBean() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean("cronExecutorProbe", Executor.class,
                () -> java.util.concurrent.Executors.newSingleThreadExecutor())
            .withUserConfiguration(ProbeConfig.class)
            .run(ctx -> {
                // 前提：容器里确实**没有**任何 LlmAgentLoop bean
                assertThat(ctx.getBeanNamesForType(LlmAgentLoop.class)).isEmpty();

                ProbeHolder holder = ctx.getBean(ProbeHolder.class);
                assertThat(holder.loopProvider)
                    .as("前提实跑：@Autowired(required=false) ObjectProvider 在无候选 bean 时仍注入非 null"
                        + "（Spring resolveDependency 对 ObjectProvider 类型直接构造 DependencyObjectProvider，"
                        + "不查候选 bean）—— 这正是「字段非 null ≠ 通道可用」的来源")
                    .isNotNull();
                assertThatThrownBy(() -> holder.loopProvider.getObject())
                    .as("无候选 bean ⇒ getObject() 抛 NoSuchBeanDefinition（若这里不抛，说明前提被证伪）")
                    .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);

                // 生产对象：CronIdleExecutor 在同一上下文里 loopProvider 字段同样非 null（旧判据的输入）
                CronIdleExecutor ctxExecutor = ctx.getBean(CronIdleExecutor.class);
                assertThat(ReflectionTestUtils.getField(ctxExecutor, "loopProvider"))
                    .as("Spring 注入的 loopProvider 字段非 null（旧判据 `cronExecutor != null && loopProvider != null`"
                        + " 在此上下文里为 true ⇒ 出队 ⇒ 逐条吞 ⇒ 丢件）")
                    .isNotNull();
                assertThat(ReflectionTestUtils.getField(ctxExecutor, "cronExecutor"))
                    .as("前置判据：cronExecutor 必须已注入（否则本用例退化为「缺另一条通道」，对 P2 无鉴别力）")
                    .isNotNull();
                assertThat((Boolean) ReflectionTestUtils.invokeMethod(ctxExecutor, "canDispatch"))
                    .as("[P2] canDispatch() 必须把「字段在但产出不了实例」判为 false（旧判据在此为 true）")
                    .isFalse();
            });
    }

    /** [P2 前提实跑] 最小上下文：一个 Executor bean + CronIdleExecutor + 只含 ObjectProvider 字段的探针。 */
    @org.springframework.context.annotation.Configuration
    static class ProbeConfig {
        @org.springframework.context.annotation.Bean
        CronIdleExecutor probeCronIdleExecutor() {
            return new CronIdleExecutor();
        }

        @org.springframework.context.annotation.Bean
        ProbeHolder probeHolder() {
            return new ProbeHolder();
        }
    }

    /** [P2 前提实跑] 只用于观察「无候选 bean 时 ObjectProvider 是否被注入」的探针。 */
    static class ProbeHolder {
        @org.springframework.beans.factory.annotation.Autowired(required = false)
        ObjectProvider<LlmAgentLoop> loopProvider;
    }

    // ==================================================================================
    // (l) [P4 · 2026-09-18 返工] B1 只修了一半：事件驱动的空队列噪声
    //     B1 的空队列短路只加在 pollScheduled，**事件驱动分支没加** ⇒ 空队列连发 3 次
    //     onQueueChanged ⇒ 6 行 [M4] INFO（复核实测）。
    // ==================================================================================

    @Test
    @DisplayName("(l) P4：空队列的事件驱动入口（onQueueChanged）不得打任何 [M4] INFO + 闸必须释放")
    void emptyQueueQueueChanged_logsNoInfoAndReleasesGate() {
        ManualExecutor manual = new ManualExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", manual);
        installLoop(mock(LlmAgentLoop.class));
        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            // 空队列连发 3 次（队列变更事件在空队列下也可能被 fire：mid-turn drain 消费后 remove 等）
            for (int i = 0; i < 3; i++) {
                ReflectionTestUtils.invokeMethod(executor, "onQueueChanged");
            }

            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("[M4]")))
                .as("[P4] 空队列的事件驱动入口不得产生任何 [M4] INFO（B1 只改了 3s 兜底入口，事件驱动漏了）")
                .isEmpty();
            assertThat(manual.pendingCount())
                .as("[P4] 空队列不得向执行器提交空转的 poll 任务").isZero();
            Object gate = ReflectionTestUtils.getField(executor, "processing");
            assertThat(((java.util.concurrent.atomic.AtomicBoolean) gate).get())
                .as("[P4] 空队列短路 return **不得带着闸** return（否则两条入口永久停摆）").isFalse();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    @Test
    @DisplayName("(l2) P4：非空队列的事件驱动入口仍保留**入口级**「提交→开跑」成对 INFO（防过度静音）")
    void nonEmptyQueueQueueChanged_keepsSubmitStartedInfoPair() {
        // [F2 · 2026-09-18 返工] ⛔ 原断言同 (f2) 是假守护（内层同名措辞即可满足）—— 现收紧为
        //   事件驱动入口的唯一措辞：「队列变更 → 提交 poll 到执行器」/「poll 已开跑（事件驱动）」。
        ManualExecutor manual = new ManualExecutor();
        ReflectionTestUtils.setField(executor, "cronExecutor", manual);
        installLoop(mock(LlmAgentLoop.class));
        String session = "sess-p4-info";
        reservedKeysTouched.add(session);
        queue.enqueue(cmd(session, "事件驱动非空探针"));

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            ReflectionTestUtils.invokeMethod(executor, "onQueueChanged");
            manual.runAll();

            List<String> infos = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.toList());
            assertThat(infos.stream().anyMatch(
                    m -> m.contains("[M4]") && m.contains("队列变更 → 提交 poll 到执行器")))
                .as("[P4] 非空队列仍须留下**入口级**「已提交（尚未开跑）」INFO（事件驱动入口）"
                    + "—— 提交 ≠ 开跑必须可判；⛔ 内层同名措辞不算数（假守护）")
                .isTrue();
            assertThat(infos.stream().anyMatch(
                    m -> m.contains("[M4]") && m.contains("poll 已开跑（事件驱动）")))
                .as("[P4] 任务体开跑后仍须留下**入口级**「已开跑」INFO（事件驱动入口）").isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================
    // (m) [F1 · 2026-09-18 返工] P2 第三层探针必须**零噪声**
    //     复核实测：探针用 loopProvider.getObject() 探测，而 LlmAgentLoop 是
    //     @Component @Scope("prototype") ⇒ 每次 getObject() 新实例化一个 bean 并触发一轮
    //     @Autowired 注入（setSessionMapper → SessionToolDisableConfig 一行 INFO）
    //     ⇒ 队列非空时 ≈1 行/3s ≈ 2.9 万行/天（与本批修掉的 57600 行/天 同量级）。
    //     修法（已定）= 源头静音（SessionToolDisableConfig.setSessionMapper 只在桥接**变化**时记录）
    //     ⇒ 探针输出 0 行。本用例把两个方向都钉住（各自单点变异都能变红）：
    //       · 回退源头静音            ⇒ 「重复 30 次零 INFO」变红（30 行）
    //       · 把探针改成不实例化/加缓存 ⇒ 「31 次 = 31 次实例化」变红（那会把 P2 的覆盖换成
    //         「只有 bean 定义」的廉价判据 ⇒ 答不了「定义在但创建失败」，见 CronIdleExecutor javadoc）
    // ==================================================================================

    @Test
    @DisplayName("(m) F1：队列非空时 canDispatch() 重复 30 次零 INFO（源头静音），且探针仍每次都真的问容器")
    void canDispatchProbe_noInfoNoiseWhileStillProbingForReal() {
        NoiseProbeConfig.loopCreations.set(0);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean("mNoiseExecutor", Executor.class,
                () -> java.util.concurrent.Executors.newSingleThreadExecutor())
            // ⛔ 不把 mock(LlmProviderFactory) 注册成 bean —— LlmProviderFactory 自身有 3 个
            //   `@Autowired` **required** 字段（mockLlmProvider/openAiSdkProvider/anthropicProvider），
            //   注册成 bean 会让容器去解析它们（UnsatisfiedDependencyException，实测）。本用例只需要
            //   「LlmAgentLoop 实例的 @Autowired setter 被调用」⇒ 在 @Bean 方法内联 new 一个 mock 即可。
            .withBean(SessionMapper.class, () -> mock(SessionMapper.class))
            .withUserConfiguration(NoiseProbeConfig.class)
            .run(ctx -> {
                assertThat(ctx.getBeanNamesForType(LlmAgentLoop.class))
                    .as("前置判据：容器里必须真的有 LlmAgentLoop prototype bean（否则探针走不到实例化面，"
                        + "本用例对「探针噪声」零鉴别力）").isNotEmpty();
                CronIdleExecutor ctxExecutor = ctx.getBean(CronIdleExecutor.class);
                assertThat(ReflectionTestUtils.getField(ctxExecutor, "cronExecutor"))
                    .as("前置判据：cronExecutor 已注入（canDispatch 第一条通道）").isNotNull();

                // 收集窗口内全部 INFO（root appender）：logback-test 把 com.nexusai 升到 DEBUG
                //   ⇒ call site 只要打了 INFO 就必然发出并冒泡到 root appender（不会因级别被吞）。
                Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                ListAppender<ILoggingEvent> appender = new ListAppender<>();
                appender.start();
                root.addAppender(appender);
                try {
                    java.util.function.Supplier<List<String>> infoMessages = () -> appender.list.stream()
                        .filter(e -> e.getLevel() == Level.INFO)
                        .map(ILoggingEvent::getFormattedMessage)
                        .collect(java.util.stream.Collectors.toList());

                    // warm-up：第 1 次探针 = prototype 首次实例化 = 「桥接从未装 → 装上」这个**真实且
                    //   一次性**的事件（值得留痕），不计入噪声窗口（生产里这一行属于启动期桥接安装，
                    //   探针本身一行不打）。改前此处也是 1 行 —— 鉴别力在下方**重复**窗口。
                    assertThat((Boolean) ReflectionTestUtils.invokeMethod(ctxExecutor, "canDispatch"))
                        .as("前置判据：canDispatch() 必须为 true（否则本用例测不到探针路径）").isTrue();
                    assertThat(infoMessages.get())
                        .as("F1：首次探测只允许「桥接装上」这一次性事件（1 行）").hasSize(1);
                    appender.list.clear();

                    // 噪声窗口：重复 30 次（≈3s 兜底轮询 90 秒的量；队列非空时生产即按此频率调用）
                    for (int i = 0; i < 30; i++) {
                        assertThat((Boolean) ReflectionTestUtils.invokeMethod(ctxExecutor, "canDispatch"))
                            .as("前置判据：canDispatch() 必须为 true（重复调用同样走探针路径）").isTrue();
                    }
                    assertThat(infoMessages.get())
                        .as("F1：重复探测 30 次不得产生任何 INFO（把源头静音回退成无条件 INFO ⇒ 30 行变红；"
                            + "队列非空时 ≈2.9 万行/天）")
                        .isEmpty();
                } finally {
                    root.detachAppender(appender);
                    appender.stop();
                }

                assertThat(NoiseProbeConfig.loopCreations.get())
                    .as("F1：31 次探针 = 31 次真实例化（每次都真的问容器一次）——**这是有意的代价**："
                        + "把探针换成「只查 bean 定义/加产出缓存」会让本断言变红，而那会丢掉 P2 的"
                        + "「定义在但创建失败」覆盖（见 CronIdleExecutor.loopChannelProducesInstance 的 WHY）")
                    .isEqualTo(31);
            });
    }

    /** [F1] 零噪声探针上下文：Executor + SessionMapper + **真** prototype LlmAgentLoop。 */
    @org.springframework.context.annotation.Configuration
    static class NoiseProbeConfig {
        /** [F1] prototype 实例化计数 —— 「探针是否每次都真的问容器」的硬证据。 */
        static final AtomicInteger loopCreations = new AtomicInteger();

        @org.springframework.context.annotation.Bean
        CronIdleExecutor noiseCronIdleExecutor() {
            return new CronIdleExecutor();
        }

        /** 与生产同形状：@Scope("prototype") ⇒ 每次 getObject() 新实例 + Spring 自动注入 @Autowired setter。 */
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Scope("prototype")
        LlmAgentLoop noiseLlmAgentLoop() {
            loopCreations.incrementAndGet();
            return new LlmAgentLoop(mock(LlmProviderFactory.class));
        }
    }

    // ==================================================================================
    // (n) [F3 · P-g · 2026-09-18 返工] 任务体抛 **Error**（非 Exception）⇒ 未消费条目回队
    //     复核实测：逐条 catch (Exception) 兜不住 Error ⇒ 带着未执行的条目逃逸、无回队。
    // ==================================================================================

    @Test
    @DisplayName("(n) F3：任务体抛 Error ⇒ 未进入处理的条目回队、已进入处理的不回队（无外层 catch ⇒ 必红）")
    void taskBodyError_requeuesOnlyNotYetConsumedEntries() {
        String session = "sess-f3-err";
        reservedKeysTouched.add(session);
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        installSyncExecutorAndLoop(loop);
        // 第 1 条（已进入处理块）抛 Error —— 逐条 catch (Exception) 兜不住 ⇒ 逃出任务体
        doThrow(new StackOverflowError("模拟任务体内 Error 级失败"))
            .when(loop).run(any(RunRequest.class));
        queue.enqueue(cmdWithUuid(session, "会抛 Error 的通知-1", "uuid-f3-1"));
        queue.enqueue(cmdWithUuid(session, "会抛 Error 的通知-2", "uuid-f3-2"));
        queue.enqueue(cmdWithUuid(session, "会抛 Error 的通知-3", "uuid-f3-3"));

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            // 走真链路：3s 兜底入口提交 → 同步执行器就地跑任务体 → poll 出队 3 条 → 任务体抛 Error
            executor.pollScheduled();

            assertThat(queue.size())
                .as("[F3 · P-g] 任务体抛 Error ⇒ **未进入处理**的 2 条必须回队。把外层 catch (Throwable) "
                    + "收窄回 Exception 级（= 变异）时本断言变红，且失败形态随执行模型分叉："
                    + "①真异步（虚拟线程，生产）= Error 逃出任务体线程 ⇒ 谁也不回队 ⇒ 条目永久消失"
                    + "（静默丢件）；②本用例的**同步**执行器 = Error 从提交点逃出 ⇒ 走 P1 的整批回队 ⇒ "
                    + "连**已消费**的 uuid-f3-1 也一起重投（重复落库/重复推送）。两种都不对，而本 catch "
                    + "用游标把「未消费段」择出来，恰好避开两者")
                .isEqualTo(2);
            List<String> requeuedUuids = queue.getCommandsByMaxPriority(Priority.LATER).stream()
                .map(QueueItem::uuid).toList();
            assertThat(requeuedUuids)
                .as("[F3 · P-g] 回队的必须是「未进入处理」的两条（uuid-f3-2/3）；"
                    + "已进入处理的 uuid-f3-1 **不得**回队（它可能已落库/已推流，重投会重复）")
                .containsExactlyInAnyOrder("uuid-f3-2", "uuid-f3-3");
            assertThat(requeuedUuids)
                .as("[F3 · P-g] 已消费的 uuid-f3-1 绝不回队（把 consumedCursor 的推进改成恒 0 = 变异 ⇒ "
                    + "本断言变红：P1 的整批回队会把已落库的条目重投一次）").doesNotContain("uuid-f3-1");
            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("[F3]") && m.contains("uuid-f3-2")))
                .as("[F3 · P-g] 逃逸必须留**带未消费条目 uuid 的显式 ERROR**（绝不静默吞）").isNotEmpty();
            assertThat(LlmAgentLoop.isSessionDispatching(session))
                .as("[F3 · P-g] 逃逸路径同样必须释放保留态（finally 收口）").isFalse();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================
    // (o) [F3 · P-h · 2026-09-18 返工] 探测通过后 runAgentLoop 内 loopProvider.getObject() 变不可用
    //     ⇒ 原日志只有前 20 字符（无 uuid）且无回队。**核查结论：此时不回队**（该条 user 消息可能
    //     已落库 + message.user 已推流 ⇒ 回队 = 重复落库/重复推送），改为**带 uuid 的显式 ERROR**。
    // ==================================================================================

    @Test
    @DisplayName("(o) F3：runAgentLoop 内 getObject() 失败 ⇒ 带 uuid 的显式 ERROR 且**不回队**（回队会重复落库/推送）")
    void loopProviderFailsInsideRunAgentLoop_explicitErrorWithUuid_noRequeue() {
        String session = "sess-f3-ph";
        reservedKeysTouched.add(session);
        Executor syncExecutor = mock(Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        // 第 1 次 getObject() = 出队后复核探针（通过）；第 2 次 = runAgentLoop 真正取实例（失败）
        when(provider.getObject())
            .thenReturn(mock(LlmAgentLoop.class))
            .thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                "模拟「探测通过后通道变不可用」"));
        ReflectionTestUtils.setField(executor, "loopProvider", provider);

        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            invokeExecuteQueuedInput(List.of(cmdWithUuid(session, "P-h 探针通知", "uuid-f3-ph")));

            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("uuid-f3-ph") && m.contains("本条不回队")))
                .as("[F3 · P-h] 必须留下**带 uuid** 的显式 ERROR（原日志只有前 20 字符 ⇒ 无法与 [M4] "
                    + "提交/开跑行按 uuid 对齐；去掉 uuid ⇒ 本断言变红）").isNotEmpty();
            assertThat(queue.size())
                .as("[F3 · P-h] ⛔ 该条**不得回队** —— 此刻它的 user 消息可能已落库（指定 id=cmd.uuid()）"
                    + "且 message.user 可能已推流 ⇒ 回队 = 同 uuid 重复落库 + 重复推送（已核实）")
                .isZero();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================
    // (p) [F3 · P-b · 2026-09-18 返工] 任务体**前置求值**抛 Throwable ⇒ 条目已出队但从未被处理
    //     ⇒ 必须回队 + 显式留痕（原实现在任何 try 之外 ⇒ 直接逃出方法 = 静默丢件）。
    //     ⚠️ 夹具是**人工**的：resolveSessionUuid / uuidsOf / new AtomicBoolean 三条实际都不抛业务
    //     异常（只剩 OOME 级，无法在单测里自然触发）⇒ 用一个 stream() 抛 Error 的 List 模拟
    //     「前置求值这一步抛 Throwable」这个**形状**。
    // ==================================================================================

    @Test
    @DisplayName("(p) F3：任务体前置求值抛 Throwable ⇒ 显式 ERROR 留痕（前置求值不包 try ⇒ 必红）")
    void taskBodyPreludeThrows_logsExplicitError() {
        // ⛔ 只装队列，不装 cronExecutor/loopProvider —— 失败必须发生在前置求值这一步（更早）
        Logger logger = (Logger) LoggerFactory.getLogger(CronIdleExecutor.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            List<QueueItem> evilPrelude = new java.util.AbstractList<>() {
                @Override public int size() { return 1; }
                @Override public QueueItem get(int index) { return null; }
                @Override public java.util.stream.Stream<QueueItem> stream() {
                    throw new StackOverflowError("模拟前置求值抛 Throwable（OOME 级）");
                }
            };

            assertThatThrownBy(() -> invokeExecuteQueuedInput(evilPrelude))
                .as("[F3 · P-b] 前置求值抛出的 Throwable 必须继续上抛（fail-loud），"
                    + "⛔ 不得被吞成「命令已丢弃」")
                .isInstanceOf(StackOverflowError.class);

            assertThat(appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m != null && m.contains("任务体前置求值失败")))
                .as("[F3 · P-b] 前置求值失败必须显式 ERROR 留痕（把前置求值还原成裸在 try 之外 ⇒ "
                    + "Error 直接逃出方法、一行日志都没有 ⇒ 本断言变红：条目「已出队、从未处理」且不可观测）")
                .isNotEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }

    // ==================================================================================
    // helpers
    // ==================================================================================

    /** 生产形状的命令（cron 通知：mode=prompt + workload=cron + 会话锚）。 */
    private static QueueItem cmd(String sessionId, String value) {
        return new QueueItem(value, NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, null, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);
    }

    private static QueueItem cmdWithUuid(String sessionId, String value, String uuid) {
        return new QueueItem(value, NotificationQueue.MODE_PROMPT, Priority.LATER,
            null, uuid, true, NotificationQueue.WORKLOAD_CRON, false, null, sessionId);
    }

    /** 空闲 task-notification（异步子代理完成通知的形状）。 */
    private static QueueItem notificationCmd(String sessionId, String value, String uuid) {
        return new QueueItem(value, NotificationQueue.MODE_TASK_NOTIFICATION, Priority.LATER,
            null, uuid, true, null, false, null, sessionId);
    }

    private void installLoop(LlmAgentLoop loop) {
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmAgentLoop> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loop);
        ReflectionTestUtils.setField(executor, "loopProvider", provider);
    }

    /** 同步执行器（execute 就地跑 Runnable）—— 让「提交→开跑」在测试线程内可确定地完成。 */
    private void installSyncExecutorAndLoop(LlmAgentLoop loop) {
        Executor syncExecutor = mock(Executor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(syncExecutor).execute(any(Runnable.class));
        ReflectionTestUtils.setField(executor, "cronExecutor", syncExecutor);
        installLoop(loop);
    }

    private void invokeExecuteQueuedInput(List<QueueItem> commands) {
        ReflectionTestUtils.invokeMethod(executor, "executeQueuedInput", commands);
    }

    /** [T1] 阻塞型 loop：任务真的开跑（countDown started）后一直等 release —— 制造「批次运行中」窗口。 */
    private void installBlockingLoop(CountDownLatch started, CountDownLatch release) {
        LlmAgentLoop loop = mock(LlmAgentLoop.class);
        doAnswer(inv -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(loop).run(any(RunRequest.class));
        installLoop(loop);
    }

    /** 静默关闭测试自建执行器（生产虚拟线程执行器）。 */
    private static void shutdownQuietly(Executor exec) {
        if (exec instanceof ExecutorService es) {
            es.shutdownNow();
        }
    }

    /**
     * [T2] 驱动一次**真实** {@code queryLoop}（单 turn；provider 立即回不可恢复 400 → 快退），
     * 目的是让 {@code LlmAgentLoop} 的 {@code msgs=} 请求边界锚行**真的被执行到**再落 appender
     * —— ⛔ 不可 mock 掉该行（照抄 {@code LlmAgentLoopTenguQueryErrorCountTest} 的已验形状）。
     */
    private static void driveOneTurnThroughQueryLoop() {
        AgentState state = new AgentState("sys",
            "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(new ChatMessageDto(
            "m1", null, Role.user, "user", "question", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of()));

        LlmProvider provider = mock(LlmProvider.class);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            onErr.accept(new LlmApiException(400, Map.of(), "bad request"));   // 不可恢复 → 快退
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);

        AgentLoopContext ctx = new AgentLoopContext(
            mock(ToolRegistry.class),
            null, null, null, null, null, null, null,
            null,
            null, factory,
            null, null, null, null, null, null, null, null,
            FeatureFlags.ALL_DISABLED,
            null, null, null, null,
            null, null, null, null, null, null, null, null);
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return "test-model"; }
        };
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(),
                "sess-" + UUID.randomUUID().toString().substring(0, 8)),
            QuerySource.USER, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>());
    }

    /**
     * 「该会话仍可被再次消费」的端到端判据（M2 的核心风险 = 保留没释放 ⇒ 该会话永久不再被消费）：
     * ① 保留态必须已释放；② 该会话的通知必须仍能被 {@code poll} 取走（谓词 {@code isSessionActive}
     * 不再跳过它）—— ② 让「漏释放」必然表现为可观测的队列不再被消费，而非只断言内部字段。
     */
    private void assertSessionReConsumable(String sessionId) {
        // ① 端到端（先判）：该会话的通知必须仍能被 poll 取走 —— 保留泄漏 ⇒ 谓词 isSessionActive
        //   恒 true ⇒ 该会话的通知永久留在队列再也不会被消费（正是本批要防的新静默卡死）。
        queue.enqueue(cmd(sessionId, "再次消费探针"));
        boolean processed = executor.poll(commands -> { });
        assertThat(processed)
            .as("[M2] 保留泄漏会让 poll 谓词永久跳过该会话 ⇒ 该会话的通知再也不会被消费（新静默卡死）")
            .isTrue();
        assertThat(queue.size()).isZero();

        // ② 状态层：保留态必须已释放（根因直判，失败信息更短更准）
        assertThat(LlmAgentLoop.isSessionDispatching(sessionId))
            .as("[M2] 该会话 dispatching 保留态必须已释放（否则该会话永久不再被消费 = 新静默卡死）")
            .isFalse();
        assertThat(LlmAgentLoop.isSessionActive(sessionId))
            .as("[M2] isSessionActive 必须回到 false（CC QueryGuard isActive）").isFalse();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> waitForWarnContaining(ListAppender<ILoggingEvent> appender,
                                                      String needle, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<String> hits = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(needle))
                .collect(java.util.stream.Collectors.toList());
            if (!hits.isEmpty()) {
                return hits;
            }
            Thread.sleep(200);
        }
        return List.of();
    }

    /**
     * [B2 反向实验夹具] 只对含 {@code [M4] 出队} 的日志抛 {@link Error} 的 appender ——
     * 精确制造「reserve 已成功、try 起点尚未到达时日志抛错」这个 B2 窗口（其余日志不受影响）。
     * ⚠️ 必须抛 {@code Error} 而非 RuntimeException：logback {@code AppenderBase.doAppender} 会
     *   {@code catch (Exception)} 吞掉 appender 异常（只记 addError），Error 才会上抛到调用点。
     */
    private static final class ThrowingAppender extends ch.qos.logback.core.AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            String msg = event == null ? null : event.getFormattedMessage();
            if (msg != null && msg.contains("[M4] 出队")) {
                throw new SimulatedSubmitLogError("模拟提交侧日志抛错（reserve 之后 · 提交之前）");
            }
        }
    }

    /** [B2 夹具] 模拟「提交侧日志抛出的 Error」（Error 才会穿透 logback appender 边界）。 */
    private static final class SimulatedSubmitLogError extends Error {
        SimulatedSubmitLogError(String message) {
            super(message);
        }
    }

    /**
     * [P1 夹具] 只拒绝**内层提交**（第 2 次及以后）的执行器：第 1 次（入口的 poll 任务）就地跑，
     * 制造「入口提交成功 → 已出队 → **内层**提交被拒」这个缺陷类（= 出队之后才失败）。
     * 生产对应物：`cronExecutor` 已 shutdown（虚拟线程执行器只在 shutdown 后拒绝新任务）。
     */
    private static final class RejectInnerSubmitExecutor implements Executor {
        private final AtomicInteger submits = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            if (submits.incrementAndGet() == 1) {
                command.run();      // 入口提交（poll 任务）：就地跑，让 poll 真的出队
            } else {
                throw new java.util.concurrent.RejectedExecutionException(
                    "模拟内层提交被拒（执行器已 shutdown）");
            }
        }

        int submitCount() {
            return submits.get();
        }
    }

    /** 手动执行器：提交的 Runnable 只入列不自动跑（模拟「执行通道尚未开跑 / 已被占住」）。 */
    private static final class ManualExecutor implements Executor {
        private final List<Runnable> pending = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        void runAll() {
            int guard = 0;
            while (!pending.isEmpty() && guard++ < 1000) {
                List<Runnable> snapshot = new ArrayList<>(pending);
                pending.clear();
                snapshot.forEach(Runnable::run);
            }
        }

        /** 待跑 Runnable 数（B1 判据：空队列不得提交空转任务）。 */
        int pendingCount() {
            return pending.size();
        }
    }
}
