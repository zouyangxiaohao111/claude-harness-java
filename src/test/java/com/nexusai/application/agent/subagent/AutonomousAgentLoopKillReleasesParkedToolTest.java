package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPromptDetails;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.WebSocketPermissionPrompter;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.team.InProcessTeammateTaskState;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.tool.impl.SubagentMessage;
import com.nexusai.infra.util.AbortControllerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [team-hang P-a] <b>kill / 生命周期中止必须真的解除「正在 park 的工具」</b>。
 *
 * <p><b>为什么必须有用例（规则九 · 验证意图）</b>：teammate 的工具若停在权限等待上，真实链路是
 * {@code WebSocketPermissionPrompter.prompt()} 末尾的 {@code future.get()}（<b>无超时</b>，有意对齐 CC
 * 无限等待）。而这条等待的逃逸通道只有一个 —— {@code ctx.abortController().onCancel}
 * （{@code WebSocketPermissionPrompter:673-686}），而工具上下文里的 abortController 是
 * {@link AutonomousAgentLoop#runOneTurn} 传出的<b>本轮 work 桥</b>
 * （经 {@code SubagentExecutor.executeStreaming(..., abortControllerOverride, ...)} 盖章，
 * SubagentExecutor:1862-1881）。kill 只 abort <b>生命周期</b> ref（{@code AutonomousAgentLoop.kill} :773-775）
 * ⇒ 两条控制器之间此前<b>没有边</b> ⇒ 工具线程永远 park，用户「手动 kill 也杀不死」（实机 jstack:
 * {@code tool-exec-*} 停在 {@code prompt:765 future.get()}，10+ 分钟不恢复）。
 *
 * <p><b>本用例锁定什么</b>：
 * <ol>
 *   <li>{@link AutonomousAgentLoop#kill()} 之后，park 的工具在超时窗口内被解除（返回 Deny），
 *       teammate 线程退出 —— 不再是「永久 park」；</li>
 *   <li>不经过 kill 端点的<b>生命周期 abort</b>（= {@code SpawnInProcess} 的 unregisterCleanup /
 *       会话销毁路径）同样能解除；</li>
 *   <li><b>反向控制</b>：中止本轮 work 控制器<b>不得</b>反向上行 abort 生命周期（「Escape 只停本轮
 *       不杀队友」这个 CC 语义 :1204-1219 必须保持不变）⇒ 级联是<b>单向</b>的。</li>
 * </ol>
 *
 * <p><b>刻意的非生产替身</b>：SubagentExecutor 被换成 {@link ParkingExecutor}（park 在<b>真实</b>
 * {@link WebSocketPermissionPrompter} 上，仅 STOMP 出口打桩）—— 不跑 LLM、不走网络，但「等 future」
 * 这一段与生产逐字相同。若把 {@link AutonomousAgentLoop} 里那条级联监听删掉，用例 1/2 必然变红
 * （线程 join 超时）。
 */
@DisplayName("[team-hang P-a] kill/生命周期中止 → 解除 park 的工具（且级联单向：停本轮不杀队友）")
class AutonomousAgentLoopKillReleasesParkedToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WORKER_AGENT_ID = "worker-d@teamtest-0920b";
    private static final String TASK_ID = "t-park";
    /** teammate 的 TUC sessionId 实机值 = 「确无会话」哨兵（与投递会话用例同一事实）。 */
    private static final String NO_SESSION_SENTINEL = "no-session";
    /** Leader 会话（teammate 身份的 parentSessionId）。 */
    private static final String LEADER_SESSION_ID = "sess-e5315213";

    /** 权限弹窗里的工具（仅需 name/description/schema/execute 满足 Tool 契约）。 */
    private static final class StubTool implements Tool {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return "bash"; }
        @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
        @Override public AgentToolResult execute(ToolUseBlock call) {
            return ToolResult.success(call.id(), "stub");
        }
    }

    /**
     * 真实 runAgent 替身：用<b>真</b> prompter 让本线程 park 在 {@code prompt()} 的 {@code future.get()}
     * 上（= 生产 teammate 卡住的那一段），只在 future 被解除后返回 aborted。
     */
    static final class ParkingExecutor extends SubagentExecutor {
        final WebSocketPermissionPrompter prompter;
        final CountDownLatch parked = new CountDownLatch(1);
        final AtomicReference<PermissionResult> released = new AtomicReference<>();
        volatile AbortController capturedBridge;
        /** [T1] runOneTurn 透传的 Leader 归属父 TUC（保持签名一致的捕获位，本用例不断言其值）。 */
        volatile ToolUseContext capturedLeaderParentTuc;

        ParkingExecutor(WebSocketPermissionPrompter prompter) {
            super(null, null, null, null, null, "fallback-model", "fallback-prompt");
            this.prompter = prompter;
        }

        @Override
        public SubagentResult executeTeammateTurn(String prompt, String subagentType, String modelOverride,
                                               ForkPathParams forkParams,
                                               Consumer<SubagentMessage> messageSink,
                                               AbortController abortControllerOverride,
                                               ToolUseContext parentTucOverride,
                                               TeammateIdentity teammateIdentityOverride) {
            // 捕获 runOneTurn 透传的 work 桥（= 工具 TUC 的 abortController）
            this.capturedBridge = abortControllerOverride;
            // [T1] 捕获 runOneTurn 透传的 Leader 归属父 TUC（本用例不依赖其值，仅保持签名一致）
            this.capturedLeaderParentTuc = parentTucOverride;
            AbortController bridge = abortControllerOverride != null
                ? abortControllerOverride : AbortController.NOOP;
            // ⚠ 本替身刻意仍用 NO_SESSION 哨兵构造工具 TUC：本用例验的是「kill 释放 park 住的工具」，
            //   与 teammate 会话归属无关；真实归属链由 TeammateLeaderSessionInheritanceTest 守。
            ToolUseContext toolTuc = ToolUseContext.of(UUID.randomUUID(), NO_SESSION_SENTINEL,
                PermissionMode.DEFAULT, List.of(), null, bridge);
            parked.countDown();
            // ↓↓↓ 这一段就是生产里 park 住的那一段（prompt 内部 future.get() 无超时）
            PermissionResult result = prompter.prompt(new StubTool(),
                JSON.createObjectNode().put("command", "ls -la"),
                new PermissionDecisionReason.Other("test"), toolTuc, "req-park-1");
            released.set(result);
            return SubagentResult.aborted("released:" + result.getClass().getSimpleName(), 0, 0L, "a-park");
        }
    }

    private static InProcessTeammateTaskState newState(AbortControllerFactory.AbortControllerRef lifecycle) {
        return new InProcessTeammateTaskState(
            TASK_ID,
            new TeammateIdentity(WORKER_AGENT_ID, "worker-d", "teamtest-0920b", null, false, LEADER_SESSION_ID),
            "do the task", null, false, "default", null,
            new ArrayList<>(), new HashSet<>(), new ArrayList<>(), false, false, 0, 0,
            lifecycle, null, null, new ArrayList<>());
    }

    private static BackgroundTask runningTeammateTask() {
        return new BackgroundTask(TASK_ID, TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            "worker-d: do the task", null, 0L, null, 0L, null, 0L, false, null, false);
    }

    private static AutonomousAgentLoop newLoop(ParkingExecutor exec,
                                               AbortControllerFactory.AbortControllerRef lifecycle,
                                               TaskFrameworkService tfs) {
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentId(WORKER_AGENT_ID);
        loop.setTaskId(TASK_ID);
        loop.setAbortController(lifecycle);
        loop.setSubagentExecutor(exec);
        loop.setTaskState(newState(lifecycle));
        if (tfs != null) {
            loop.setTaskFrameworkService(tfs);
        }
        return loop;
    }

    /** STOMP 打桩：抓 topic + 载荷，置 latch（不写任何网络）。 */
    private static SimpMessagingTemplate stubbingWs(AtomicReference<String> topic,
                                                    AtomicReference<Object> payload,
                                                    CountDownLatch published) {
        return mock(SimpMessagingTemplate.class, inv -> {
            if ("convertAndSend".equals(inv.getMethod().getName())) {
                topic.set((String) inv.getArgument(0));
                payload.set(inv.getArgument(1));
                published.countDown();
            }
            return null;
        });
    }

    private static WebSocketPermissionPrompter realPrompter(SimpMessagingTemplate ws) {
        // 真 prompter：hook/bridge/channel 均未注入 ⇒ 唯一逃逸通道 = 用户作答 / abort
        // （正是本用例要验的那条）
        return new WebSocketPermissionPrompter(ws, 60_000);
    }

    private static Thread startLoop(AutonomousAgentLoop loop) {
        Thread runner = new Thread(() -> loop.runTeammateLoop("do the task"), "teammate-worker-d");
        runner.setDaemon(true);
        runner.start();
        return runner;
    }

    @Test
    @DisplayName("kill() 之后：park 在权限等待的工具被解除（Deny），teammate 线程退出 —— 不再永久 park")
    void kill_releasesParkedTool() throws Exception {
        AtomicReference<String> topic = new AtomicReference<>();
        AtomicReference<Object> payload = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        ParkingExecutor exec = new ParkingExecutor(realPrompter(stubbingWs(topic, payload, published)));
        AbortControllerFactory.AbortControllerRef lifecycle = AbortControllerFactory.create();
        TaskFrameworkService tfs = mock(TaskFrameworkService.class);
        when(tfs.getTask(TASK_ID)).thenReturn(Optional.of(runningTeammateTask()));
        AutonomousAgentLoop loop = newLoop(exec, lifecycle, tfs);

        Thread runner = startLoop(loop);

        assertThat(published.await(5, TimeUnit.SECONDS))
            .as("工具必须先真的停在权限等待上（否则本用例测不到 kill 解除）").isTrue();
        assertThat(exec.parked.getCount()).as("工具已进入 prompt（park 前哨）").isZero();
        assertThat(exec.released.get()).as("kill 之前不得有任何决策（确实是 park 住的）").isNull();
        assertThat(runner.isAlive()).as("kill 之前 teammate 线程仍在等工具").isTrue();

        boolean killed = loop.kill();
        assertThat(killed).as("kill 必须真的推进终态（running → killed）").isTrue();

        runner.join(5_000);
        assertThat(runner.isAlive())
            .as("★ kill 之后 teammate 线程必须退出 —— 修复前它会永久 park（jstack: tool-exec-* 停在 "
                + "future.get()，10+ 分钟不恢复），这就是用户说的「手动 kill 也杀不死」")
            .isFalse();
        assertThat(exec.released.get())
            .as("★ kill 必须把 park 的工具解除（生命周期 abort → 级联本轮 work 控制器 → 权限提示 "
                + "onCancel → future 完成）")
            .isInstanceOf(PermissionResult.Deny.class);
        assertThat(exec.capturedBridge).as("runOneTurn 必须透传 work 桥作为工具上下文 abortController")
            .isNotNull();
        assertThat(exec.capturedBridge.isCancelled())
            .as("解除的机制 = 工具上下文的 abortController 被真的 abort（不是旁路塞结果）").isTrue();
    }

    @Test
    @DisplayName("不经 kill 端点的生命周期 abort（会话清理 unregisterCleanup 路径）同样解除 park 的工具")
    void lifecycleAbort_withoutKill_releasesParkedTool() throws Exception {
        AtomicReference<String> topic = new AtomicReference<>();
        AtomicReference<Object> payload = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        ParkingExecutor exec = new ParkingExecutor(realPrompter(stubbingWs(topic, payload, published)));
        AbortControllerFactory.AbortControllerRef lifecycle = AbortControllerFactory.create();
        // 无 taskFrameworkService ⇒ 不走 kill 端点的状态机，只验「生命周期 abort」这一条因
        AutonomousAgentLoop loop = newLoop(exec, lifecycle, null);

        Thread runner = startLoop(loop);
        assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(exec.released.get()).isNull();

        // = SpawnInProcess unregisterCleanup / 会话销毁所做的事（abort 生命周期控制器）
        lifecycle.abort();

        runner.join(5_000);
        assertThat(runner.isAlive())
            .as("★ 会话清理路径也必须能解除 park 的工具（否则删会话后工具线程随 thread 一起泄漏）")
            .isFalse();
        assertThat(exec.released.get()).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("反向控制：中止本轮 work 控制器不得上行 abort 生命周期（Escape 只停本轮不杀队友，CC :1204-1219）")
    void workAbort_isOneWay_doesNotKillTeammate() throws Exception {
        AtomicReference<String> topic = new AtomicReference<>();
        AtomicReference<Object> payload = new AtomicReference<>();
        CountDownLatch published = new CountDownLatch(1);
        ParkingExecutor exec = new ParkingExecutor(realPrompter(stubbingWs(topic, payload, published)));
        AbortControllerFactory.AbortControllerRef lifecycle = AbortControllerFactory.create();
        AutonomousAgentLoop loop = newLoop(exec, lifecycle, null);

        Thread runner = startLoop(loop);
        try {
            assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();

            AbortControllerFactory.AbortControllerRef work = loop.taskState().currentWorkAbortController();
            assertThat(work).as("本轮 work 控制器必须存入状态载体供 UI 追踪（CC :1059-1063）").isNotNull();

            // 模拟 Escape / 「停本轮」：中止本轮 work 控制器
            work.abort("interrupt");

            long deadline = System.currentTimeMillis() + 5_000;
            while (exec.released.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(exec.released.get()).as("work abort 同样要能解除本轮工具").isNotNull();
            assertThat(lifecycle.aborted().get())
                .as("★ 级联必须单向：work abort **不得**上行 abort 生命周期 ref（否则 Escape 会杀掉队友，"
                    + "违反 CC :1204-1219「只停本轮不杀队友」）").isFalse();
            assertThat(loop.isAborted()).as("生命周期仍存活 ⇒ 队友应留在 idle 等下一轮，而非退出").isFalse();
        } finally {
            // 收尾：生命周期 abort 让循环确定性退出（避免残留 daemon 轮询线程）
            lifecycle.abort();
            runner.join(5_000);
        }
        assertThat(runner.isAlive()).as("生命周期 abort 后循环必须退出").isFalse();
    }
}
