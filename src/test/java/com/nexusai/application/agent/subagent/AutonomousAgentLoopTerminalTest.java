package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskFrameworkService;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.infra.util.AbortControllerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W8-01 · AutonomousAgentLoop 终端转换（completed/failed/killed）+ notified/endTime/evict/SDK 链 +
 * tryAutoClaimAndExecute 接线 · 对齐 CC inProcessRunner.ts:1419-1533 + spawnInProcess.ts:227-328。
 *
 * <p><b>WHY（规则九）</b>：CC 终端转换的核心价值是<b>防双发</b>——killInProcessTeammate
 * 已置 killed + notified:true 后，runner 的 completed/failed 转换必须通过
 * {@code status !== 'running'} 守卫跳过（inProcessRunner.ts:1428/:1479 alreadyTerminal），
 * 否则会 killed→completed 翻转 + 重复 emitTaskTerminatedSdk 双发 bookend。本测试验证 Java
 * 侧同样守卫：terminal 后再调用转换必须 no-op 且不重复发 SDK 事件。
 */
@DisplayName("W8-01 · AutonomousAgentLoop 终端转换 + notified/endTime/evict/SDK 链 + claim 接线")
class AutonomousAgentLoopTerminalTest {

    @TempDir
    Path tempDir;

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
    private TaskService taskService;
    private AutonomousAgentLoop loop;

    private void setUp(String taskId) {
        sdkQueue = new SdkEventQueue();
        framework = new RecordingFramework(sdkQueue);
        taskService = new TaskService(tempDir);
        loop = new AutonomousAgentLoop();
        loop.setTaskFrameworkService(framework);
        loop.setSdkEventQueue(sdkQueue);
        loop.setTaskService(taskService);
        loop.setTaskId(taskId);
        loop.setAgentId("alice");
        loop.setAgentName("alice");
        loop.setTeamName("research-team");
        // 注册一个 running 的 in_process_teammate 任务
        BackgroundTask task = new BackgroundTask(
            taskId, TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            "alice teammate", null,
            System.currentTimeMillis(), null, null,
            "/tmp/" + taskId + ".out", 0L, false,
            null, true);
        framework.registerTask(task);
    }

    private SdkEventQueue.TaskNotificationEvent onlyNotification(String session) {
        return sdkQueue.drainSdkEvents(session).stream()
            .map(SdkEventQueue.DrainedSdkEvent::event)
            .filter(e -> e instanceof SdkEventQueue.TaskNotificationEvent)
            .map(e -> (SdkEventQueue.TaskNotificationEvent) e)
            .findFirst()
            .orElse(null);
    }

    @Test
    @DisplayName("complete(): 仅 running 可转换; 已 terminal 时 no-op 且不重复发 SDK（inProcessRunner.ts:1420-1461）")
    void complete_onlyRunning_guardPreventsDoubleEmit() {
        // WHY: CC :1428 alreadyTerminal 守卫 —— kill 后 completed 不应翻转/双发
        setUp("t1");
        loop.setAbortController(AbortControllerFactory.create());

        boolean first = loop.complete();
        assertThat(first).as("running 任务应转换成功").isTrue();

        // 状态 + notified + endTime（evict 前捕获）
        BackgroundTask after = framework.lastTerminal;
        assertThat(after).as("updateTaskState 必须被调用").isNotNull();
        assertThat(after.status()).isEqualTo(BackgroundTaskStatus.COMPLETED);
        assertThat(after.notified()).as("completed 必须 notified:true").isTrue();
        assertThat(after.endTime()).as("completed 必须设置 endTime").isNotNull();

        // SDK bookend 只发一次 completed
        SdkEventQueue.TaskNotificationEvent evt = onlyNotification("sess-1");
        assertThat(evt).as("必须发出 task_notification").isNotNull();
        assertThat(evt.status()).isEqualTo("completed");

        // 再次调用 → no-op（alreadyTerminal）
        boolean second = loop.complete();
        assertThat(second).as("已 terminal 时 complete 必须 no-op").isFalse();
        assertThat(onlyNotification("sess-1")).as("不得重复发 SDK").isNull();
    }

    @Test
    @DisplayName("fail(): FAILED + notified + endTime + SDK 'failed' + alreadyTerminal 守卫")
    void fail_setsFailedState() {
        // WHY: CC :1465-1533 —— 异常退出也必须闭合 bookend, 否则 task_started 无对偶
        setUp("t2");

        boolean ok = loop.fail("boom");
        assertThat(ok).isTrue();

        BackgroundTask after = framework.lastTerminal;
        assertThat(after).isNotNull();
        assertThat(after.status()).isEqualTo(BackgroundTaskStatus.FAILED);
        assertThat(after.notified()).isTrue();
        assertThat(after.endTime()).isNotNull();

        SdkEventQueue.TaskNotificationEvent evt = onlyNotification("sess-2");
        assertThat(evt).as("必须发出 task_notification").isNotNull();
        assertThat(evt.status()).isEqualTo("failed");

        // alreadyTerminal 守卫
        assertThat(loop.fail("again")).as("已 failed 时 fail 必须 no-op").isFalse();
        assertThat(onlyNotification("sess-2")).as("不得重复发 SDK").isNull();
    }

    @Test
    @DisplayName("kill(): abortController 触发 + KILLED + notified + SDK 'stopped'（spawnInProcess.ts:227-328）")
    void kill_abortsAndSetsKilled() {
        // WHY: CC :256 abort + :280-296 status:'killed'+notified:true+endTime —— 中断路径
        //      必须闭合 bookend, 且 abort 必须真实触发以停止运行中的 worker
        setUp("t3");
        AbortControllerFactory.AbortControllerRef abort = AbortControllerFactory.create();
        loop.setAbortController(abort);

        boolean killed = loop.kill();
        assertThat(killed).isTrue();
        assertThat(abort.aborted().get()).as("kill 必须触发 abortController.abort()").isTrue();

        BackgroundTask after = framework.lastTerminal;
        assertThat(after).isNotNull();
        assertThat(after.status()).isEqualTo(BackgroundTaskStatus.KILLED);
        assertThat(after.notified()).isTrue();
        assertThat(after.endTime()).isNotNull();

        SdkEventQueue.TaskNotificationEvent evt = onlyNotification("sess-3");
        assertThat(evt).as("必须发出 task_notification").isNotNull();
        assertThat(evt.status()).isEqualTo("stopped");

        // 重复 kill → no-op
        assertThat(loop.kill()).isFalse();
        assertThat(onlyNotification("sess-3")).as("不得重复发 SDK").isNull();
    }

    @Test
    @DisplayName("tryAutoClaimAndExecute(): 认领 pending 未 owner 任务并置 in_progress（inProcessRunner.ts:624-657）")
    void tryAutoClaimAndExecute_claimsPendingTask() {
        // WHY: CC tryClaimNextTask —— 轮询 task-list 认领未认领任务; Java 当前恒 false (W8-J-01)
        //      接线后应真实认领, 否则 teammate 永远不干活
        sdkQueue = new SdkEventQueue();
        framework = new RecordingFramework(sdkQueue);
        taskService = new TaskService(tempDir);
        loop = new AutonomousAgentLoop();
        loop.setTaskService(taskService);
        loop.setTaskListId("team-list");
        loop.setAgentName("alice");

        taskService.createTask("team-list", new com.nexusai.application.agent.tasks.Task(
            "9", "do thing", "desc", null, null,
            com.nexusai.application.agent.tasks.Task.TaskStatus.PENDING,
            List.of(), List.of(), Map.of()));

        Optional<String> claimed = loop.tryAutoClaimAndExecute();
        assertThat(claimed).as("应认领一个 pending 任务").isPresent();

        com.nexusai.application.agent.tasks.Task after =
            taskService.listTasks("team-list").get(0);
        assertThat(after.owner()).as("认领后 owner 应写入 alice").isEqualTo("alice");
        assertThat(after.status()).as("认领后应置 in_progress").isEqualTo(
            com.nexusai.application.agent.tasks.Task.TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("W8-04 REWORK E4: complete() 经 outboundSink 投递 task_status attachment（通知链真实闭环）")
    void complete_deliversTaskStatusAttachmentToOutboundSink() {
        // WHY: 反射器 E4 —— 之前 outboundSink 无生产/测试调用方，终端转换产出的 task_status
        //      attachment 被丢弃（if(outboundSink!=null) 恒不成立），不进入会话消息库 → 折叠链
        //      GET /messages 无 in_process_teammate 输入。本测试验证接线后 complete() 真实经
        //      outboundSink 投递 task_status attachment（生产接线 = SpawnInProcess 指向
        //      MessageService.appendMessage 落库，反射器要求通知链闭环有输入）。
        setUp("t4");
        loop.setAbortController(AbortControllerFactory.create());
        List<com.nexusai.model.session.dto.ChatMessageDto> received = new java.util.ArrayList<>();
        loop.setOutboundSink(received::add);

        boolean advanced = loop.complete();
        assertThat(advanced).as("running 任务应转换成功").isTrue();

        assertThat(received).as("complete() 必须经 outboundSink 投递 1 条 task_status attachment").hasSize(1);
        com.nexusai.model.session.dto.ChatMessageDto att = received.get(0);
        assertThat(att.author()).as("attachment.author = 'attachment'").isEqualTo("attachment");
        assertThat(att.subtype()).as("attachment.subtype = 'task_status'").isEqualTo("task_status");
        assertThat(att.content()).as("载荷含 taskType=in_process_teammate + status=completed")
            .contains("\"taskType\":\"in_process_teammate\"")
            .contains("\"status\":\"completed\"");
        // 折叠链可命中：两条该产物 → 折叠为 teammate_shutdown_batch（完成通知链 = 折叠链输入）
        List<com.nexusai.model.session.dto.ChatMessageDto> collapsed =
            com.nexusai.application.agent.team.TeammateMessageFoldingChain.collapse(
                List.of(att, att));
        assertThat(collapsed).as("两条 teammate shutdown attachment 折叠为 1 条 batch").hasSize(1);
        assertThat(collapsed.get(0).subtype()).isEqualTo("teammate_shutdown_batch");
    }
}
