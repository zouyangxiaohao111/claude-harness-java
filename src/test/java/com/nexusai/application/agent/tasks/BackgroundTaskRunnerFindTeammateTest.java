package com.nexusai.application.agent.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CRON-D4 · findTeammateByAgentId 对齐 CC findTeammateTaskByAgentId — 验证意图（WHY）：
 *
 * <p>CC InProcessTeammateTask.tsx:92-108：遍历 tasks 找 agentId 匹配的 IN_PROCESS_TEAMMATE
 * 任务，<b>running 优先</b>（:98-99，旧 killed 任务未 evict 时避免注入死任务），首匹配兜底
 * （:102-104），无则 undefined（:107）。terminal 过滤由调用方 onFireTask 承担
 * （useScheduledTasks.ts:97 !isTerminalTaskStatus），本方法<b>允许</b>返回 terminal 兜底
 * 供调用方判定。
 *
 * <p>断言哪些字段：
 * <ul>
 *   <li>running 优先：同 agentId 下 RUNNING 必须优先于已终态（CC :96-99 注释语义）</li>
 *   <li>terminal 兜底：仅终态匹配时返回该任务（调用方再判 terminal，CC :97 分工）</li>
 *   <li>无匹配返回 null（CC :107 undefined 等价）</li>
 *   <li>类型过滤：非 IN_PROCESS_TEAMMATE 任务即使 agentId 匹配也忽略（CC :95
 *       isInProcessTeammateTask）</li>
 *   <li>入参 null → null（fail-closed）</li>
 * </ul>
 */
class BackgroundTaskRunnerFindTeammateTest {

    private final BackgroundTaskRunner runner = new BackgroundTaskRunner(
        mock(NotificationQueue.class), mock(TaskFrameworkService.class));

    /** 直接注入 tasks map（不 spawn —— spawn 会覆盖终态/触发通知副作用，绕过用反射写私有 map） */
    private void putTask(BackgroundTask task) {
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, BackgroundTask> tasks =
            (ConcurrentMap<String, BackgroundTask>) ReflectionTestUtils.getField(runner, "tasks");
        tasks.put(task.id(), task);
    }

    private BackgroundTask teammate(String id, String agentId, BackgroundTaskStatus status) {
        return new BackgroundTask(
            id, TaskType.IN_PROCESS_TEAMMATE, status, "teammate-" + id,
            null, System.currentTimeMillis(), null, null,
            "/tmp/teammate-" + id + ".out", 0L, false,
            UUID.fromString(agentId), true);
    }

    @Test
    @DisplayName("running 优先：同 agentId 存在已终态 + running 时返回 running（CC :98-99）")
    void runningTaskPreferredOverTerminal() {
        String agentId = UUID.randomUUID().toString();
        putTask(teammate("t-old", agentId, BackgroundTaskStatus.COMPLETED));
        putTask(teammate("t-new", agentId, BackgroundTaskStatus.RUNNING));

        BackgroundTask found = runner.findTeammateByAgentId(agentId);

        assertThat(found).as("必须返回 running 任务而非已终态兜底（CC InProcessTeammateTask.tsx:98-99）")
            .isNotNull()
            .extracting(BackgroundTask::id)
            .isEqualTo("t-new");
    }

    @Test
    @DisplayName("terminal 兜底：仅终态匹配时返回该任务（CC :102-104，terminal 过滤在调用方）")
    void terminalTaskReturnedAsFallback() {
        String agentId = UUID.randomUUID().toString();
        putTask(teammate("t-killed", agentId, BackgroundTaskStatus.KILLED));

        BackgroundTask found = runner.findTeammateByAgentId(agentId);

        assertThat(found).as("terminal 任务允许作为首匹配兜底（调用方 onFireTask 判 terminal）")
            .isNotNull()
            .extracting(BackgroundTask::status)
            .isEqualTo(BackgroundTaskStatus.KILLED);
    }

    @Test
    @DisplayName("无匹配返回 null（CC :107 undefined 等价）")
    void noMatchReturnsNull() {
        putTask(teammate("t-a", UUID.randomUUID().toString(), BackgroundTaskStatus.RUNNING));

        BackgroundTask found = runner.findTeammateByAgentId(UUID.randomUUID().toString());

        assertThat(found).as("无 agentId 匹配必须返回 null").isNull();
    }

    @Test
    @DisplayName("类型过滤：agentId 匹配但非 IN_PROCESS_TEAMMATE 忽略（CC :95 isInProcessTeammateTask）")
    void nonTeammateTypeIgnored() {
        String agentId = UUID.randomUUID().toString();
        // LOCAL_BASH 任务带同 agentId —— 不是 teammate，必须忽略
        BackgroundTask bash = new BackgroundTask(
            "t-bash", TaskType.LOCAL_BASH, BackgroundTaskStatus.RUNNING, "bash",
            null, System.currentTimeMillis(), null, null,
            "/tmp/bash.out", 0L, false, UUID.fromString(agentId), true);
        putTask(bash);

        BackgroundTask found = runner.findTeammateByAgentId(agentId);

        assertThat(found).as("非 IN_PROCESS_TEAMMATE 类型必须被过滤（CC :95）").isNull();
    }

    @Test
    @DisplayName("入参 null → null（fail-closed）")
    void nullInputReturnsNull() {
        assertThat(runner.findTeammateByAgentId(null)).as("agentId=null 必须返回 null").isNull();
    }
}
