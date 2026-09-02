package com.nexusai.infra.schedule;

import com.nexusai.application.agent.subagent.AgentMessageBus;
import com.nexusai.application.agent.subagent.AgentMessageBus.InboxMessage;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRON-D4 · TestJob fire teammate 路由 — 验证意图（WHY）：
 *
 * <p>CC onFireTask（useScheduledTasks.ts:91-108）：agentId 非空 → 只走 teammate 路由
 * （findTeammateTaskByAgentId → 非 terminal → injectUserMessageToTeammate；teammate
 * 消失/终态 → removeCronTasks 孤儿清理），<b>绝不</b>入 lead 队列；agentId 空 → lead
 * enqueueForLead。
 *
 * <p>断言哪些字段：
 * <ul>
 *   <li>agentId 非空 + teammate 存在非 terminal → sendToAgent 恰好 1 次（type=user_message、
 *       payload=原始 prompt），enqueue 0 次</li>
 *   <li>agentId 非空 + teammate 不存在 → scheduleService.delete 恰好 1 次（孤儿清理，
 *       CC :106 removeCronTasks 等价），enqueue 0 次</li>
 *   <li>agentId 非空 + teammate 终态 → 同孤儿清理，delete 1 次 + enqueue 0（CC :97
 *       !isTerminalTaskStatus 不成立）</li>
 *   <li>agentId 空 → 保持 lead 入队 1 次（CRON-D1），sendToAgent/delete 0 次</li>
 * </ul>
 */
class TestJobTeammateRoutingTest {

    private static final String SCHEDULE_ID = "sch-d4-001";
    private static final String RAW_PROMPT = "运行项目测试并汇报覆盖率";
    private static final String AGENT_ID = UUID.randomUUID().toString();

    private ScheduleDto dto(String agentId) {
        return new ScheduleDto(
            SCHEDULE_ID, "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            RAW_PROMPT, "desc", OffsetDateTime.now(), "ok",
            ScheduleScope.SESSION, null, agentId, null);
    }

    private JobExecutionContext ctx() {
        JobDataMap data = new JobDataMap();
        data.put("scheduleId", SCHEDULE_ID);
        data.put("scheduleName", "测试任务");
        data.put("scheduleKind", "cron");
        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(data);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(context.getFireTime()).thenReturn(java.util.Date.from(java.time.Instant.now()));
        return context;
    }

    private TestJob newJob(ScheduleService svc, BackgroundTaskRunner runner,
                           AgentMessageBus bus, NotificationQueue queue) {
        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", svc);
        ReflectionTestUtils.setField(job, "backgroundTaskRunner", runner);
        ReflectionTestUtils.setField(job, "agentMessageBus", bus);
        ReflectionTestUtils.setField(job, "notificationQueue", queue);
        return job;
    }

    private BackgroundTask runningTeammate() {
        return new BackgroundTask(
            "task-d4-001", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            "teammate", null, System.currentTimeMillis(), null, null,
            "/tmp/task-d4-001.out", 0L, false, UUID.fromString(AGENT_ID), true);
    }

    @Test
    @DisplayName("agentId 非空 + teammate 存在非 terminal → sendToAgent 1 次 + lead enqueue 0（CC :97-99）")
    void agentIdWithLiveTeammateInjectsPrompt() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(AGENT_ID));
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.findTeammateByAgentId(AGENT_ID)).thenReturn(runningTeammate());
        AgentMessageBus bus = mock(AgentMessageBus.class);
        NotificationQueue queue = new NotificationQueue();

        newJob(svc, runner, bus, queue).execute(ctx());

        // CC :98 injectUserMessageToTeammate —— prompt 注入 teammate，type=user_message
        verify(bus).sendToAgent(eq(AGENT_ID),
            org.mockito.ArgumentMatchers.argThat(m ->
                m.type().equals(InboxMessage.TYPE_USER_MESSAGE)
                    && RAW_PROMPT.equals(m.payload())));
        assertThat(queue.dequeueAll()).as("teammate cron 绝不入 lead 队列（C5）").isEmpty();
        verify(svc, never()).delete(any());
    }

    @Test
    @DisplayName("agentId 非空 + teammate 不存在 → scheduleService.delete 1 次 + enqueue 0（CC :101-108 孤儿清理）")
    void agentIdWithMissingTeammateDeletesOrphan() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(AGENT_ID));
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.findTeammateByAgentId(AGENT_ID)).thenReturn(null);
        AgentMessageBus bus = mock(AgentMessageBus.class);
        NotificationQueue queue = new NotificationQueue();

        newJob(svc, runner, bus, queue).execute(ctx());

        // CC :106 removeCronTasks 等价 —— Java ScheduleService.delete = unregister+deleteById
        verify(svc).delete(SCHEDULE_ID);
        assertThat(queue.dequeueAll()).as("孤儿清理不得入 lead 队列").isEmpty();
        verify(bus, never()).sendToAgent(any(), any());
    }

    @Test
    @DisplayName("agentId 非空 + teammate 终态 → scheduleService.delete 1 次 + enqueue 0（CC :97 !isTerminal 不成立）")
    void agentIdWithTerminalTeammateDeletesOrphan() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(AGENT_ID));
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        BackgroundTask killed = new BackgroundTask(
            "task-d4-killed", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.KILLED,
            "teammate", null, System.currentTimeMillis(), null, null,
            "/tmp/task-d4-killed.out", 0L, false, UUID.fromString(AGENT_ID), true);
        when(runner.findTeammateByAgentId(AGENT_ID)).thenReturn(killed);
        AgentMessageBus bus = mock(AgentMessageBus.class);
        NotificationQueue queue = new NotificationQueue();

        newJob(svc, runner, bus, queue).execute(ctx());

        verify(svc).delete(SCHEDULE_ID);
        assertThat(queue.dequeueAll()).as("teammate 终态不得注入也不得入 lead 队列").isEmpty();
        verify(bus, never()).sendToAgent(any(), any());
    }

    @Test
    @DisplayName("agentId 空 → 保持 lead 入队 1 次（CRON-D1，CC :110-114 lead 路径）")
    void emptyAgentIdEnqueuesLead() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(null));
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        AgentMessageBus bus = mock(AgentMessageBus.class);
        NotificationQueue queue = new NotificationQueue();

        newJob(svc, runner, bus, queue).execute(ctx());

        List<QueueItem> items = queue.dequeueAll();
        assertThat(items).as("agentId 空必须走 CRON-D1 lead 入队").hasSize(1);
        QueueItem item = items.get(0);
        assertThat(item.value()).as("value 必须是原始 prompt（CC useScheduledTasks.ts:74）")
            .isEqualTo(RAW_PROMPT);
        assertThat(item.mode()).as("mode 必须为 'prompt'").isEqualTo("prompt");
        assertThat(item.isMeta()).as("isMeta 必须为 true（CC :76）").isTrue();
        assertThat(item.workload()).as("workload 必须为 WORKLOAD_CRON").isEqualTo("cron");
        verify(bus, never()).sendToAgent(any(), any());
        verify(svc, never()).delete(any());
    }
}
