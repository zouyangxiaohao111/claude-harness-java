package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDK 事件发射接线定向测试 · 对齐 CC framework.ts:104-116（task_started）+
 * sdkEventQueue.ts:114-134 emitTaskTerminatedSdk（终态 bookend）。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>task_started（framework.ts:104-116）</b>——前端任务面板靠它出现任务卡；
 *       replacement（resume 替换）非新开始必须跳过，否则同一任务发两次 started
 *       （前端按 task_id 重建会错乱）。</li>
 *   <li><b>task_terminated 终态 bookend（sdkEventQueue.ts:103-113）</b>——registerTask
 *       发 started 后若终态无通知，前端面板任务永不关闭（scmuxd bg-task dot / VS Code
 *       subagent panel 依赖该信号）。</li>
 * </ul>
 */
@DisplayName("[OPD-TS-22] SDK 事件发射接线（task_started + 终态 task_notification）")
class SdkEventQueueWiringTest {

    @TempDir
    Path tempDir;

    private BackgroundTask runningBashTask(String id) {
        return new BackgroundTask(id, TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING,
            "desc-" + id, "tu-" + id, System.currentTimeMillis(), null, null,
            tempDir.resolve(id + ".out").toString(), 0L, false, null, false);
    }

    @Test
    @DisplayName("registerTask 发 task_started（CC framework.ts:104-116）")
    void registerTask_emitsTaskStartedWithCcFields() {
        SdkEventQueue sdk = new SdkEventQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);

        service.registerTask(runningBashTask("t1"));

        SdkEventQueue.TaskStartedEvent evt =
            (SdkEventQueue.TaskStartedEvent) sdk.drainSdkEvents("sess").get(0).event();
        assertThat(evt.taskId()).isEqualTo("t1");
        assertThat(evt.toolUseId()).isEqualTo("tu-t1");
        assertThat(evt.description()).isEqualTo("desc-t1");
        assertThat(evt.taskType()).isEqualTo("local_bash"); // CC TaskType 枚举小写值
    }

    @Test
    @DisplayName("replacement（resume 替换）跳过 task_started 防双发（framework.ts:101-102）")
    void registerTask_replacementSkipsTaskStarted() {
        SdkEventQueue sdk = new SdkEventQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        service.registerTask(runningBashTask("t1"));

        // resume 替换同一 taskId —— 非新开始
        service.registerTask(runningBashTask("t1"));

        List<SdkEventQueue.DrainedSdkEvent> drained = sdk.drainSdkEvents("sess");
        assertThat(drained).hasSize(1); // 只发一次 task_started
    }

    @Test
    @DisplayName("completeAsyncAgent 终态发 task_notification(completed) bookend")
    void completeAsyncAgent_emitsTaskNotificationCompleted() {
        SdkEventQueue sdk = new SdkEventQueue();
        NotificationQueue nq = new NotificationQueue();
        TaskFrameworkService service = new TaskFrameworkService(sdk);
        BackgroundTaskRunner runner = new BackgroundTaskRunner(nq, service, sdk);

        String taskId = UUID.randomUUID().toString();
        runner.registerAsyncAgent(UUID.fromString(taskId), "调研任务", "prompt", "general-purpose", null, null);
        // register 已发 task_started（先 drain 掉）
        assertThat(sdk.drainSdkEvents("sess")).hasSize(1);

        runner.completeAsyncAgent(taskId, AsyncAgentResult.success("结论", 3, 100L, "agent-x"));

        List<SdkEventQueue.DrainedSdkEvent> drained = sdk.drainSdkEvents("sess");
        assertThat(drained).hasSize(1);
        SdkEventQueue.TaskNotificationEvent evt = (SdkEventQueue.TaskNotificationEvent) drained.get(0).event();
        assertThat(evt.status()).isEqualTo("completed");
        assertThat(evt.taskId()).isEqualTo(taskId);
        // CC stopTask.ts:90 summary=task.description
        assertThat(evt.summary()).isEqualTo("调研任务");
    }
}
