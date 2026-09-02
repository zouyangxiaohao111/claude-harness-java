package com.nexusai.infra.schedule;

import com.nexusai.application.agent.subagent.AgentMessageBus;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.agent.telemetry.Telemetry;
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
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRON-F4 · TestJob fire 路径生命周期（fire-then-delete）聚焦测试。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: OPD-Cron-09-6 拍板「one-shot 任务 fire 后必须
 * auto-delete，释放 MAX_JOBS 配额」；recurring 未 aged 任务 fire 后必须保留并回写 lastRunAt
 * （对齐 CC markCronTasksFired，cronTasks.ts:261-278），否则下次进程启动无法从 lastFiredAt
 * 重建 newNext（cronScheduler.ts:322-323）。CC 真源 cronScheduler.ts:302-343：fire 分发后
 * 做 aged 判定，recurring && !aged → 保留 + lastFiredAt；否则删除。删除/保留对所有 fire
 * 生效（含 teammate 注入分支）。
 *
 * <p>断言哪些字段：
 * <ul>
 *   <li>one-shot（deleteAfterFire=true）→ deleteAfterFire 恰好 1 次 + markFired 0 次
 *       （删了就不再回写 lastRunAt，CC :325-344 else 分支）</li>
 *   <li>recurring 未 aged（deleteAfterFire=false）→ markFired(List.of(id), firedAt) 恰好 1 次
 *       （firedAt 非 null，Java 写 lastRunAt，CC :358-360）</li>
 *   <li>teammate 注入分支 fire 后同样接 deleteAfterFire（CC 对所有 fire 生效）</li>
 * </ul>
 */
class TestJobFireLifecycleTest {

    private static final String SCHEDULE_ID = "sch-f4-001";
    private static final String RAW_PROMPT = "运行项目测试并汇报覆盖率";

    private ScheduleDto dto(String agentId) {
        return dto(ScheduleKind.cron, agentId);
    }

    private ScheduleDto dto(ScheduleKind kind, String agentId) {
        return new ScheduleDto(
            SCHEDULE_ID, "测试任务", kind, "0 9 * * *", null, null,
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

    private TestJob newJob(ScheduleService svc, NotificationQueue queue) {
        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", svc);
        ReflectionTestUtils.setField(job, "notificationQueue", queue);
        return job;
    }

    @Test
    @DisplayName("one-shot fire（deleteAfterFire=true）→ deleteAfterFire 1 次 + markFired 0 次（OPD-Cron-09-6 释放 MAX_JOBS）")
    void oneShotFireDeletesAndSkipsMarkFired() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(null));
        when(svc.deleteAfterFire(SCHEDULE_ID)).thenReturn(true);

        newJob(svc, new NotificationQueue()).execute(ctx());

        verify(svc).deleteAfterFire(SCHEDULE_ID);
        verify(svc, never()).markFired(any(), any());
    }

    @Test
    @DisplayName("recurring 未 aged（deleteAfterFire=false）→ markFired(List.of(id), firedAt) 1 次写 lastRunAt（CC :358-360）")
    void recurringKeptMarksFired() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(null));
        when(svc.deleteAfterFire(SCHEDULE_ID)).thenReturn(false);
        when(svc.markFired(any(), any())).thenReturn(1);

        newJob(svc, new NotificationQueue()).execute(ctx());

        verify(svc).deleteAfterFire(SCHEDULE_ID);
        verify(svc).markFired(eq(List.of(SCHEDULE_ID)),
            argThat(firedAt -> firedAt != null));
    }

    @Test
    @DisplayName("teammate 注入分支 fire 后同样接 deleteAfterFire（CC 对所有 fire 生效）")
    void teammateInjectPathAlsoAppliesLifecycle() throws Exception {
        String agentId = UUID.randomUUID().toString();
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(agentId));
        when(svc.deleteAfterFire(SCHEDULE_ID)).thenReturn(false);
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.findTeammateByAgentId(agentId)).thenReturn(new BackgroundTask(
            "task-f4-001", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            "teammate", null, System.currentTimeMillis(), null, null,
            "/tmp/task-f4-001.out", 0L, false, UUID.fromString(agentId), true));
        AgentMessageBus bus = mock(AgentMessageBus.class);
        TestJob job = newJob(svc, new NotificationQueue());
        ReflectionTestUtils.setField(job, "backgroundTaskRunner", runner);
        ReflectionTestUtils.setField(job, "agentMessageBus", bus);

        job.execute(ctx());

        verify(bus).sendToAgent(eq(agentId), any());
        verify(svc).deleteAfterFire(SCHEDULE_ID);
    }

    // ============ IMPL-10 (NEW-12): fire 事件遥测 ============

    @Test
    @DisplayName("IMPL-10: cron kind fire → tengu_scheduled_task_fire 载荷 {recurring:true, taskId}（CC cronScheduler.ts:288-292）")
    void fireEventEmittedForCronKind() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(null));
        when(svc.deleteAfterFire(SCHEDULE_ID)).thenReturn(false);
        when(svc.markFired(any(), any())).thenReturn(1);
        Telemetry telemetry = mock(Telemetry.class);
        TestJob job = newJob(svc, new NotificationQueue());
        ReflectionTestUtils.setField(job, "telemetry", telemetry);

        job.execute(ctx());

        verify(telemetry).recordEvent(eq("tengu_scheduled_task_fire"),
            eq(Map.<String, Object>of("recurring", true, "taskId", SCHEDULE_ID)));
    }

    @Test
    @DisplayName("IMPL-10: once kind fire → 载荷 {recurring:false}（CC t.recurring ?? false）")
    void fireEventRecurringFalseForOnceKind() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(ScheduleKind.once, null));
        when(svc.deleteAfterFire(SCHEDULE_ID)).thenReturn(true);
        Telemetry telemetry = mock(Telemetry.class);
        TestJob job = newJob(svc, new NotificationQueue());
        ReflectionTestUtils.setField(job, "telemetry", telemetry);

        job.execute(ctx());

        verify(telemetry).recordEvent(eq("tengu_scheduled_task_fire"),
            eq(Map.<String, Object>of("recurring", false, "taskId", SCHEDULE_ID)));
    }

    @Test
    @DisplayName("IMPL-10: telemetry 未注入（null）→ fire 正常执行、不抛异常（静默跳过）")
    void fireWithNullTelemetrySkipsSilently() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(null));
        when(svc.deleteAfterFire(SCHEDULE_ID)).thenReturn(false);
        when(svc.markFired(any(), any())).thenReturn(1);
        // 不注入 telemetry（newJob 默认 null）→ emitFireTelemetry 静默 return，不改变 fire 行为

        newJob(svc, new NotificationQueue()).execute(ctx());

        verify(svc).deleteAfterFire(SCHEDULE_ID);
        verify(svc).markFired(any(), any());
    }
}
