package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * unassignTeammateTasks · 定向验证任务释放语义对齐 CC utils/tasks.ts:818-860 unassignTeammateTasks()
 *
 * <p><b>WHY (意图验证)</b>: CC 中 teammate 被 kill / 体面 shutdown 后（CC 接线点
 * print.ts:2572 shutdown_approved → 'shutdown'；TeamsDialog.tsx:573 teammate 移除 → 'terminated'），
 * 其<b>未完成任务</b>必须被释放回 pending 池、owner 置空，并生成通知消息供 UI/消费者刷新——
 * 否则任务会"死锁"在已消失的 teammate 名下（owner 匹配双键 id/name，tasks.ts:825-829），
 * 其他 idle teammate 永远无法认领。释放必须逐任务走 {@link TaskService#updateTask}
 * （tasks.ts:832-834 走 updateTask = 触发 notifyTasksUpdated），UI 才能感知任务池变化。
 *
 * <p>本测试在 {@code TaskService.unassignTeammateTasks} 缺失时（全仓 0 出现）应先红；
 * 实现后应绿，且任一行为（双键匹配 / completed 保留 / 文案 / notify）被破坏时变红。
 *
 * <p>参考 CC 真源（grep 自验，非注释）：
 * <ul>
 *   <li>filter：{@code t.status !== 'completed' && (t.owner === teammateId || t.owner === teammateName)} — tasks.ts:825-829</li>
 *   <li>释放：{@code await updateTask(teamName, task.id, { owner: undefined, status: 'pending' })} — tasks.ts:832-834</li>
 *   <li>文案：{@code actionVerb = reason === 'terminated' ? 'was terminated' : 'has shut down'} — tasks.ts:843-851</li>
 *   <li>返回：{@code { unassignedTasks: [{id, subject}], notificationMessage }} — tasks.ts:853-859</li>
 * </ul>
 */
class TaskServiceUnassignTeammateTasksTest {

    @TempDir
    Path tempDir;

    private TaskService newService() {
        // 显式 configHome 构造器：隔离真实 ~/.claude 目录，逐用例独立 @TempDir
        return new TaskService(tempDir);
    }

    @Test
    @DisplayName("a) 双键匹配释放：owner=teammateId 或 owner=teammateName 的未完成任务均释放为 pending + owner 置空")
    void releasesTasksOwnedByIdOrNameAndResetsToPending() {
        TaskService service = newService();
        String listId = "unassign-list";

        // teammateId 认领的任务
        String idById = service.createTask(listId, Task.create("任务一", "第一个任务"));
        service.updateTask(listId, idById, Map.of("owner", "teammate-1"));
        // teammateName 认领的任务
        String idByName = service.createTask(listId, Task.create("任务二", "第二个任务"));
        service.updateTask(listId, idByName, Map.of("owner", "AgentName"));

        UnassignTasksResult result =
            service.unassignTeammateTasks(listId, "teammate-1", "AgentName", "shutdown");

        // 双键匹配：两个任务都被释放
        assertThat(result.unassignedTasks())
            .extracting(UnassignTasksResult.UnassignedTask::id)
            .containsExactlyInAnyOrder(idById, idByName);
        // 释放后 status=pending、owner=null（CC tasks.ts:832-834 { owner: undefined, status: 'pending' }）
        Task t1 = service.getTask(listId, idById).orElseThrow();
        assertThat(t1.status()).isEqualTo(Task.TaskStatus.PENDING);
        assertThat(t1.owner()).isNull();
        Task t2 = service.getTask(listId, idByName).orElseThrow();
        assertThat(t2.status()).isEqualTo(Task.TaskStatus.PENDING);
        assertThat(t2.owner()).isNull();
    }

    @Test
    @DisplayName("b) completed 任务不释放（保持 completed + 原 owner）；他人任务不释放")
    void keepsCompletedAndOtherOwnersTasks() {
        TaskService service = newService();
        String listId = "unassign-keep-list";

        String completedId = service.createTask(listId, Task.create("已完成", "已完成任务"));
        service.updateTask(listId, completedId,
            Map.of("owner", "teammate-1", "status", Task.TaskStatus.COMPLETED));
        String otherId = service.createTask(listId, Task.create("他人任务", "不属于本 teammate"));
        service.updateTask(listId, otherId, Map.of("owner", "other-agent"));

        UnassignTasksResult result =
            service.unassignTeammateTasks(listId, "teammate-1", "AgentName", "shutdown");

        // 无释放任务
        assertThat(result.unassignedTasks()).isEmpty();
        // completed 任务原样保留（CC filter t.status !== 'completed'，tasks.ts:827）
        Task completed = service.getTask(listId, completedId).orElseThrow();
        assertThat(completed.status()).isEqualTo(Task.TaskStatus.COMPLETED);
        assertThat(completed.owner()).isEqualTo("teammate-1");
        // 他人任务原样保留
        Task other = service.getTask(listId, otherId).orElseThrow();
        assertThat(other.owner()).isEqualTo("other-agent");
    }

    @Test
    @DisplayName("c) 通知消息文案：reason=shutdown → 'has shut down'；terminated → 'was terminated'；含任务列表与重分配指引")
    void buildsNotificationMessagePerReason() {
        TaskService service = newService();
        String listId = "unassign-msg-list";

        String id1 = service.createTask(listId, Task.create("任务A", "第一个"));
        service.updateTask(listId, id1, Map.of("owner", "teammate-1"));
        String id2 = service.createTask(listId, Task.create("任务B", "第二个"));
        service.updateTask(listId, id2, Map.of("owner", "teammate-1"));

        UnassignTasksResult shutdown = service.unassignTeammateTasks(listId, "teammate-1", "AgentName", "shutdown");
        // CC tasks.ts:845 `has shut down` 动词
        assertThat(shutdown.notificationMessage()).startsWith("AgentName has shut down.");
        assertThat(shutdown.notificationMessage()).contains("2 task(s) were unassigned:");
        assertThat(shutdown.notificationMessage()).contains("#" + id1, "#" + id2);
        // CC tasks.ts:850 重分配指引文案
        assertThat(shutdown.notificationMessage())
            .endsWith(". Use TaskList to check availability and TaskUpdate with owner to reassign them to idle teammates.");

        UnassignTasksResult terminated = service.unassignTeammateTasks(listId, "teammate-1", "AgentName", "terminated");
        // CC tasks.ts:844 `was terminated` 动词
        assertThat(terminated.notificationMessage()).startsWith("AgentName was terminated.");

        // 无未分配任务时：仅基础句（CC tasks.ts:845-846，不追加任务列表）
        UnassignTasksResult empty = service.unassignTeammateTasks(listId, "ghost", "GhostName", "shutdown");
        assertThat(empty.notificationMessage()).isEqualTo("GhostName has shut down.");
    }

    @Test
    @DisplayName("d) 释放触发 notifyTasksUpdated：每个被释放任务触发一次信号（UI/消费者感知任务池刷新）")
    void triggersTasksUpdatedNotifyPerReleasedTask() {
        TaskService service = newService();
        String listId = "unassign-notify-list";

        String id1 = service.createTask(listId, Task.create("任务一", "第一个"));
        service.updateTask(listId, id1, Map.of("owner", "teammate-1"));
        String id2 = service.createTask(listId, Task.create("任务二", "第二个"));
        service.updateTask(listId, id2, Map.of("owner", "AgentName"));

        AtomicInteger notifyCount = new AtomicInteger(0);
        Runnable unsub = TaskService.addListener(notifyCount::incrementAndGet);
        try {
            // 清掉前面 createTask/updateTask 已触发的 notify，只统计 unassign 段
            notifyCount.set(0);
            service.unassignTeammateTasks(listId, "teammate-1", "AgentName", "shutdown");
            // 2 个被释放任务 → 2 次 notify（CC tasks.ts:832-834 逐任务 updateTask → notify）
            assertThat(notifyCount.get()).isEqualTo(2);
        } finally {
            unsub.run();
        }
    }
}
