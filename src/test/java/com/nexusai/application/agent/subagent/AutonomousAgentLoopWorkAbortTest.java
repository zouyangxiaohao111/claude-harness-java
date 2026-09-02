package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.team.InProcessTeammateTaskState;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.tool.impl.SubagentMessage;
import com.nexusai.infra.util.AbortControllerFactory;
import com.nexusai.infra.util.SwarmConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8-02 REWORK (GAP-6) · currentWorkAbortController 透传 executeStreaming · 对齐 CC inProcessRunner.ts:1056/:1175-1219。
 *
 * <p><b>WHY（规则九）</b>——GAP-6 修复的<b>业务后果</b>：
 * <ol>
 *   <li><b>per-turn Escape 必须到得了执行器</b>（CC :1197 {@code override: { abortController:
 *       currentWorkAbortController }}）：teammate 循环为每轮创建独立 work 控制器（:1056），若不透传，
 *       Escape/中止信号对当前轮执行器无效，用户无法只停本轮。</li>
 *   <li><b>只停本轮、不杀队友</b>（CC :1204-1219）：work abort 只取消当前轮 query loop
 *       （state.cancel），生命周期 abortController 不受影响——队友返回 idle 等待下一条指令，
 *       而非整体退出（对比 lifecycle abort → 循环退出）。</li>
 *   <li><b>work abort 的 idle 通知语义</b>（CC :1339 idleReason: workWasAborted ? 'interrupted' :
 *       'available'）：leader 需要区分「队友被中断」与「队友空闲」。</li>
 *   <li><b>work 控制器存状态载体供 UI 追踪</b>（CC :1059-1063 存入 / :1280-1284 轮末清空）。</li>
 * </ol>
 */
@DisplayName("W8-02 REWORK · GAP-6 work abort 透传（Escape 只停本轮不杀队友）")
class AutonomousAgentLoopWorkAbortTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("nexusai.task.config-dir", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        TaskSystemConfig.clearForTest();
    }

    /** 记录透传的 abort controller 并模拟 Escape（abort work 控制器 + 返回 aborted 结果）。 */
    static final class RecordingExecutor extends SubagentExecutor {
        final AbortControllerFactory.AbortControllerRef workAbortRef;
        volatile com.nexusai.application.agent.tool.AbortController capturedAbort;
        volatile boolean workAbortObserved;
        /** Fix A: 捕获 runOneTurn 透传的 subagentType（teammate 必须 null，不得为 agentName）。 */
        volatile String capturedSubagentType;

        RecordingExecutor(AbortControllerFactory.AbortControllerRef workAbortRef) {
            super(null, null, null, null, null, "fallback-model", "fallback-prompt");
            this.workAbortRef = workAbortRef;
        }

        @Override
        public SubagentExecutor.SubagentResult executeStreaming(String prompt, String subagentType,
                                                                String modelOverride,
                                                                SubagentExecutor.ForkPathParams forkParams,
                                                                Consumer<SubagentMessage> messageSink,
                                                                com.nexusai.application.agent.tool.AbortController abortControllerOverride) {
            this.capturedSubagentType = subagentType;
            this.capturedAbort = abortControllerOverride;
            if (workAbortRef != null) {
                workAbortRef.abort(); // 模拟 Escape：中止本轮 work 控制器（经桥转发到透传的 abort）
            }
            if (abortControllerOverride != null && abortControllerOverride.isCancelled()) {
                this.workAbortObserved = true; // 透传的 abort 已响应 workAbort.abort()
            }
            return SubagentExecutor.SubagentResult.aborted("work interrupted by Escape", 0, 0, "t-g6");
        }
    }

    private TeammateIdentity identity() {
        return new TeammateIdentity("alice@research-team", "alice", "research-team",
            null, false, "session-1");
    }

    private InProcessTeammateTaskState newState(String taskId,
                                                AbortControllerFactory.AbortControllerRef lifecycle) {
        return new InProcessTeammateTaskState(
            taskId, identity(), "prompt", null, false, "default", null,
            new java.util.ArrayList<>(), new java.util.HashSet<>(),
            new java.util.ArrayList<>(), false, false, 0, 0,
            lifecycle, null, null, new java.util.ArrayList<>());
    }

    @Test
    @DisplayName("GAP-6: runOneTurn 把 work abort 透传 executeStreaming，abort 只停本轮不触发生命周期（CC :1197/:1204-1219）")
    void runOneTurn_passesWorkAbortController_stopsTurnOnlyNotTeammate() {
        // WHY: CC :1197 override.abortController = currentWorkAbortController——Escape 必须能停当前轮执行器；
        //      且 :1204-1219 work abort ≠ lifecycle abort（队友存活进 idle）。
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentName("alice");
        AbortControllerFactory.AbortControllerRef lifecycle = AbortControllerFactory.create();
        loop.setAbortController(lifecycle);
        AbortControllerFactory.AbortControllerRef workAbort = AbortControllerFactory.create();
        RecordingExecutor executor = new RecordingExecutor(workAbort);
        loop.setSubagentExecutor(executor);

        boolean workWasAborted = loop.runOneTurn("do research", workAbort);

        assertThat(executor.capturedAbort).as("work abort 必须透传到 executeStreaming（非 null）").isNotNull();
        assertThat(executor.workAbortObserved).as("透传的 abort 必须响应 workAbort.abort()").isTrue();
        assertThat(workWasAborted).as("work abort 后本轮必须返回 workWasAborted=true（CC :1213-1219）").isTrue();
        assertThat(loop.isAborted()).as("work abort 不得触发生命周期 abort（只停本轮不杀队友）").isFalse();
    }

    @Test
    @DisplayName("Fix A: teammate 名（'alice'）不得当 subagentType —— 恒 null 落 general-purpose（CC agentType 仅标签不查注册表）")
    void runOneTurn_teammateName_doesNotBecomeSubagentType() {
        // WHY: 修复前 agentName（如 'alice'）被当 subagentType 传给 SubagentExecutor →
        //   effectiveType='alice' → resolveAgentDefinition → null → AgentNotFoundException
        //   （SubagentExecutor:1286-1300）→ teammate 线程 failed（主工作区 20:33 实证）。
        //   CC teammate runAgent 用通用 iterationAgentDefinition（inProcessRunner.ts:975-1001，
        //   agentType 仅标签不查注册表）→ subagentType 必须 null，SubagentExecutor:1271 fallback
        //   BuiltInAgents.GENERAL_PURPOSE（非 null）→ teammate 才能完成首轮而非 failed。
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentName("alice");
        loop.setAbortController(AbortControllerFactory.create());
        RecordingExecutor executor = new RecordingExecutor(null);   // 无 workAbort：只验证 subagentType 捕获
        loop.setSubagentExecutor(executor);

        loop.runOneTurn("do research", AbortControllerFactory.create());

        assertThat(executor.capturedSubagentType)
            .as("teammate 不得把 agentName 当 subagentType（CC agentType 仅标签不查表，恒通用 general-purpose）")
            .isNull();
    }

    @Test
    @DisplayName("GAP-6: runTeammateLoop work abort → 'interrupted' idle 通知 + 状态载体 work 控制器轮末清空（CC :1339/:1059-1063/:1280-1284）")
    void runTeammateLoop_workAbort_sendsInterruptedIdle_andClearsWorkControllerFromState() {
        // WHY: CC :1339 idleReason: workWasAborted ? 'interrupted' : 'available'——leader 需区分中断与空闲；
        //      :1059-1063 轮初存 work 控制器（UI 追踪），:1280-1284 轮末清空（失效）。
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentId("alice@research-team");
        loop.setAgentName("alice");
        loop.setTeamName("research-team");
        loop.setTaskId("t-g6");
        AbortControllerFactory.AbortControllerRef lifecycle = AbortControllerFactory.create();
        loop.setAbortController(lifecycle);
        AbortControllerFactory.AbortControllerRef workAbort = AbortControllerFactory.create();
        RecordingExecutor executor = new RecordingExecutor(workAbort);
        loop.setSubagentExecutor(executor);
        loop.setTaskState(newState("t-g6", lifecycle));

        // 首轮 work abort 后，等 waitForNextPromptOrShutdown 轮询时中止生命周期 → 循环退出（确定性收尾）
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            lifecycle.abort();
        });
        stopper.setDaemon(true);
        stopper.start();

        loop.runTeammateLoop("hello team");

        List<TeammateMailbox.TeammateMessage> lead =
            TeammateMailbox.readMailbox(SwarmConstants.TEAM_LEAD_NAME, "research-team");
        assertThat(lead).as("work abort 必须向 team-lead 发 idleReason='interrupted' 通知（CC :1339）").anyMatch(m ->
            m.text().contains("\"idleReason\":\"interrupted\""));
        assertThat(loop.taskState()).isNotNull();
        assertThat(loop.taskState().currentWorkAbortController()).as("轮末必须清空 work 控制器（CC :1280-1284）").isNull();
        assertThat(loop.messages()).as("work abort 必须加中断消息到镜像（CC :1298-1308）")
            .anyMatch(m -> m.contains("[interrupt]"));
    }

    @Test
    @DisplayName("GAP-6: 未注入 subagentExecutor 时 runOneTurn 仅记录、不抛错（教学/测试回退）")
    void runOneTurn_withoutExecutor_doesNotThrow() {
        // WHY: subagentExecutor 可选注入（CC runAgent 等价委托缺失时仅记录），GAP-6 不透传也应无副作用。
        AutonomousAgentLoop loop = new AutonomousAgentLoop();
        loop.setAgentName("alice");
        AbortControllerFactory.AbortControllerRef workAbort = AbortControllerFactory.create();

        boolean workWasAborted = loop.runOneTurn("hello", workAbort);

        assertThat(workWasAborted).as("无 executor 时不得误报 work abort").isFalse();
        assertThat(loop.messages()).as("无 executor 时仍追加 stub 记录").anyMatch(m -> m.contains("[stub]"));
    }
}
