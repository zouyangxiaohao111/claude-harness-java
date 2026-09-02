package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.team.InProcessTeammateTaskState;
import com.nexusai.application.agent.team.TeamHelpers;
import com.nexusai.application.agent.team.TeammateIdentity;
import com.nexusai.application.agent.team.TeammateMailbox;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.infra.util.AbortControllerFactory;
import com.nexusai.infra.util.SwarmConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8-02 REWORK · InProcessTeammateTask 完整状态机缺陷修复验证。
 *
 * <p><b>WHY（规则九）</b>——每项缺陷被修复的<b>业务后果</b>：
 * <ol>
 *   <li><b>终端转换必须唤醒 onIdleCallbacks</b>（CC inProcessRunner.ts:1433/:1484 +
 *       spawnInProcess.ts:265）：leader 经 {@code engine.waitForIdle} 注册回调等 teammate
 *       空闲；若 teammate 被 kill/complete/fail（未走正常 idle 路径）而不触发回调，
 *       leader 会<b>永久阻塞</b>。</li>
 *   <li><b>fail 必须 error 落盘 + 发 failed idle 通知</b>（CC :1490/:1516-1525）：失败现场
 *       （errorMessage）必须持久，leader 必须感知 failed（idleReason:'failed'），否则
 *       失败静默丢失、leader 永远以为 teammate 还活着。</li>
 *   <li><b>kill 不得双发 evict</b>（CC spawnInProcess.ts:306-319 仅延迟 3s）：立即 evict 使
 *       STOPPED_DISPLAY_MS 展示窗口失效，延迟 evict 变 no-op。</li>
 *   <li><b>kill 必须移除 team 文件成员</b>（CC spawnInProcess.ts:302-304 removeMemberByAgentId）：
 *       否则 Team 文件成员残留，swarm 发现脏数据。</li>
 *   <li><b>messages 镜像必须包含初始 prompt / shutdown_request / 非 user 新消息</b>
 *       （CC :1023-1033/:1376-1380/:1402-1406）：转录视图（zoomed view）镜像不完整。</li>
 * </ol>
 */
@DisplayName("W8-02 REWORK · 状态机缺陷修复（terminal 唤醒回调 + fail 落盘/通知 + kill 去双 evict/成员移除 + 消息镜像）")
class AutonomousAgentLoopStateMachineReworkTest {

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

    /** 记录 updateTaskState 最后一次写入的 terminal state (evict 前捕获) */
    static final class RecordingFramework extends TaskFrameworkService {
        BackgroundTask lastTerminal;
        RecordingFramework(SdkEventQueue sdkQueue) { super(sdkQueue); }
        @Override
        public void updateTaskState(String taskId, BackgroundTask newState) {
            this.lastTerminal = newState;
            super.updateTaskState(taskId, newState);
        }
    }

    private RecordingFramework framework;
    private SdkEventQueue sdkQueue;
    private AutonomousAgentLoop loop;
    private AbortControllerFactory.AbortControllerRef abort;

    private void setupRunning(String taskId) {
        sdkQueue = new SdkEventQueue();
        framework = new RecordingFramework(sdkQueue);
        loop = new AutonomousAgentLoop();
        loop.setTaskFrameworkService(framework);
        loop.setSdkEventQueue(sdkQueue);
        loop.setTaskId(taskId);
        loop.setAgentId("alice@research-team");
        loop.setAgentName("alice");
        loop.setTeamName("research-team");
        loop.setTeamHelpers(new TeamHelpers());
        abort = AbortControllerFactory.create();
        loop.setAbortController(abort);
        // 状态载体（fail error 落盘载体，CC types.ts:47 error 字段）
        loop.setTaskState(new InProcessTeammateTaskState(
            taskId,
            new TeammateIdentity("alice@research-team", "alice", "research-team",
                null, false, "session-1"),
            "prompt", null, false, "default", null,
            new java.util.ArrayList<>(), new java.util.HashSet<>(),
            new java.util.ArrayList<>(), false, false, 0, 0,
            abort, null, null, new java.util.ArrayList<>()));
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            "alice teammate", null,
            System.currentTimeMillis(), null, null,
            "/tmp/" + taskId + ".out", 0L, false,
            null, true);
        framework.registerTask(task);
    }

    @Test
    @DisplayName("GAP-1: complete()/fail()/kill() 终端转换必须调用并清空 onIdleCallbacks（CC inProcessRunner.ts:1433/:1484 + spawnInProcess.ts:265）")
    void terminalTransitions_invokeAndClearOnIdleCallbacks() {
        // WHY: leader 经 onIdleCallbacks 等待 teammate 空闲（engine.waitForIdle）——若终端转换
        //      不触发回调，leader 永久阻塞。kill/complete/fail 三种终态都要唤醒。
        setupRunning("t-g1-complete");
        AtomicInteger calls = new AtomicInteger();
        loop.addOnIdleCallback(calls::incrementAndGet);
        loop.addOnIdleCallback(calls::incrementAndGet);
        loop.complete();
        assertThat(calls.get()).as("complete() 必须触发所有 idle 回调").isEqualTo(2);
        assertThat(loop.idleCallbackCount()).as("回调触发后必须清空").isZero();

        setupRunning("t-g1-kill");
        AtomicInteger killCalls = new AtomicInteger();
        loop.addOnIdleCallback(killCalls::incrementAndGet);
        loop.kill();
        assertThat(killCalls.get()).as("kill() 必须触发 idle 回调（CC spawnInProcess.ts:265）").isEqualTo(1);
        assertThat(loop.idleCallbackCount()).isZero();
    }

    @Test
    @DisplayName("GAP-2: fail() 必须 error 落盘（taskState.error + 描述）+ 置 isIdle + 发 failed idle 通知（CC :1490/:1491/:1516-1525）")
    void fail_writesErrorAndSendsFailedIdleNotification() {
        // WHY: 失败现场必须持久可查（error 落盘），且 leader 必须感知 failed——否则失败静默丢失。
        setupRunning("t-g2");
        loop.fail("boom");

        // error 落盘到状态载体
        assertThat(loop.taskState()).as("taskState 载体必须存在").isNotNull();
        assertThat(loop.taskState().error()).as("fail 必须把 error 写入状态载体").isEqualTo("boom");
        // error 落盘到 BackgroundTask 描述（持久层）
        assertThat(framework.lastTerminal).isNotNull();
        assertThat(framework.lastTerminal.description()).as("BackgroundTask 描述必须携带 error").contains("boom");
        assertThat(loop.isIdle()).as("fail 必须置 isIdle:true（CC failed:1491）").isTrue();

        // failed idle 通知写入 team-lead 收件箱
        List<TeammateMailbox.TeammateMessage> lead =
            TeammateMailbox.readMailbox(SwarmConstants.TEAM_LEAD_NAME, "research-team");
        assertThat(lead).as("fail 必须向 team-lead 写 idle_notification").anyMatch(m ->
            m.text().contains("\"idleReason\":\"failed\"")
                && m.text().contains("\"completedStatus\":\"failed\"")
                && m.text().contains("\"failureReason\":\"boom\""));
    }

    @Test
    @DisplayName("GAP-3: kill() 不得立即 evict（延迟 3s 展示窗口保留），complete()/fail() 才 eager evict（CC spawnInProcess.ts:306-319）")
    void kill_doesNotEagerlyEvict_delayedEvictOnly() {
        // WHY: CC kill 仅延迟 3s evict（STOPPED_DISPLAY_MS 展示窗口）；立即 evict 使窗口失效、
        //      延迟 evict 变 no-op。
        setupRunning("t-g3-kill");
        loop.kill();
        assertThat(framework.getTask("t-g3-kill")).as("kill 后任务必须保留（3s 展示窗口内不可 evict）").isPresent();

        // 对照：complete 是 eager evict（CC :1453）
        setupRunning("t-g3-complete");
        loop.complete();
        assertThat(framework.getTask("t-g3-complete")).as("complete 必须 eager evict（CC :1453）").isEmpty();
    }

    @Test
    @DisplayName("GAP-4: kill() 必须从 team 文件移除成员（removeMemberByAgentId，CC spawnInProcess.ts:302-304）")
    void kill_removesMemberFromTeamFile() {
        // WHY: 不移除成员 → Team 文件成员残留，swarm 发现脏数据（死 teammate 仍在成员列表）。
        setupRunning("t-g4");
        // team 配置含 members 数组（对齐 CC teamHelpers.ts TeamFile.members，TeamDiscovery 读取）
        Path config = TeammateMailbox.getTeamsDir().resolve("research-team").resolve("config.json");
        String cfg = "{\"team_name\":\"research-team\",\"members\":["
            + "{\"name\":\"alice\",\"agentId\":\"alice@research-team\"},"
            + "{\"name\":\"bob\",\"agentId\":\"bob@research-team\"}]}";
        try {
            Files.createDirectories(config.getParent());
            Files.writeString(config, cfg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        loop.kill();

        String after;
        try {
            after = new String(Files.readAllBytes(config));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(after).as("kill 必须从 members 数组移除 alice@research-team")
            .doesNotContain("alice@research-team");
        assertThat(after).as("非目标成员 bob 必须保留")
            .contains("bob@research-team");
    }

    @Test
    @DisplayName("GAP-5: 初始 prompt 必须进入 messages 镜像（CC inProcessRunner.ts:1006-1033）")
    void runTeammateLoop_messagesMirror_includesInitialPrompt() {
        // WHY: 转录视图（zoomed view）读 task.messages——初始 prompt 缺失则 transcript 开头空白。
        AutonomousAgentLoop l2 = new AutonomousAgentLoop();
        l2.setAgentId("alice@research-team");
        l2.setAgentName("alice");
        l2.setTeamName("research-team");
        AbortControllerFactory.AbortControllerRef preAborted = AbortControllerFactory.create();
        preAborted.abort();
        l2.setAbortController(preAborted);

        l2.runTeammateLoop("hello team");

        assertThat(l2.messages()).as("messages 镜像必须含初始 prompt").anyMatch(m -> m.contains("hello team"));
    }

    @Test
    @DisplayName("GAP-5: injectUserMessage 必须同时追加 messages 镜像（CC InProcessTeammateTask.tsx:79-82）")
    void injectUserMessage_appendsToMessagesMirror() {
        // WHY: CC injectUserMessageToTeammate 把消息加入 pending 队列 + task.messages（转录立即显示）。
        AutonomousAgentLoop l2 = new AutonomousAgentLoop();
        l2.setAgentName("alice");

        l2.injectUserMessage("hello from transcript");

        assertThat(l2.messages()).as("注入的用户消息必须进 messages 镜像").anyMatch(m -> m.contains("hello from transcript"));
    }

    @Test
    @DisplayName("GAP-5: shutdown_request / 非 user 新消息派发必须追加 messages 镜像（CC :1376-1380/:1402-1406）")
    void dispatch_appendsShutdownAndNonUserMessagesToMirror() {
        // WHY: CC appendTeammateMessage——转录视图必须看到 shutdown_request 与 teammate 消息。
        AutonomousAgentLoop l2 = new AutonomousAgentLoop();
        l2.setAgentName("alice");

        // shutdown_request → XML 交模型 + 镜像
        String shutdownPrompt = l2.nextPromptForWaitResult(new AutonomousAgentLoop.WaitResult(
            AutonomousAgentLoop.WaitResult.TYPE_SHUTDOWN_REQUEST, "team-lead",
            "{\"type\":\"shutdown_request\",\"requestId\":\"r1\"}", null, null));
        assertThat(shutdownPrompt).as("shutdown_request 必须格式化为 teammate-message XML").contains("teammate_id=\"team-lead\"");
        assertThat(l2.messages()).as("shutdown_request 必须进 messages 镜像").anyMatch(m -> m.contains("\"type\":\"shutdown_request\""));

        // 非 user 新消息 → XML + 镜像（CC :1402-1406）
        AutonomousAgentLoop l3 = new AutonomousAgentLoop();
        l3.setAgentName("alice");
        String peerPrompt = l3.nextPromptForWaitResult(new AutonomousAgentLoop.WaitResult(
            AutonomousAgentLoop.WaitResult.TYPE_NEW_MESSAGE, "bob", "need data", "red", null));
        assertThat(peerPrompt).as("peer 消息必须 XML 包裹").contains("teammate_id=\"bob\"");
        assertThat(l3.messages()).as("peer 消息必须进 messages 镜像").anyMatch(m -> m.contains("need data"));
    }
}
