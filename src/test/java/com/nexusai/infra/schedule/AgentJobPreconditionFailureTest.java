package com.nexusai.infra.schedule;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.schedule.ScheduleRunsService;
import com.nexusai.application.schedule.ScheduleSessionResolver;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.model.schedule.dto.SessionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** [V330 · 第3类] 拿不到执行会话 ⇒ 失败终态可见，不得静默 headless 继续。 */
class AgentJobPreconditionFailureTest {

    private static final String SCHEDULE_ID = "sch-pre-001";

    private static ScheduleDto dto(SessionMode mode) {
        return new ScheduleDto(
            SCHEDULE_ID, "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            "跑", "desc", null, "ok", ScheduleScope.DURABLE,
            null, null, null, mode, List.of("imch-1"), null, null);
    }

    private static JobExecutionContext ctx() {
        JobDataMap data = new JobDataMap();
        data.put("scheduleId", SCHEDULE_ID);
        data.put("scheduleName", "测试任务");
        data.put("scheduleKind", "cron");
        JobDetail detail = mock(JobDetail.class);
        when(detail.getJobDataMap()).thenReturn(data);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getJobDetail()).thenReturn(detail);
        when(context.getFireTime()).thenReturn(java.util.Date.from(java.time.Instant.now()));
        return context;
    }

    private static AgentJob job(ScheduleService svc, NotificationQueue queue,
                                ScheduleSessionResolver resolver, ScheduleRunsService runs) {
        AgentJob job = new AgentJob();
        ReflectionTestUtils.setField(job, "scheduleService", svc);
        ReflectionTestUtils.setField(job, "notificationQueue", queue);
        ReflectionTestUtils.setField(job, "sessionResolver", resolver);
        ReflectionTestUtils.setField(job, "runsService", runs);
        return job;
    }

    @Test
    @DisplayName("NEW_EACH 拿不到会话 ⇒ 不入队，运行记录落 error")
    void newEachWithoutSessionRecordsErrorAndSkipsEnqueue() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(SessionMode.NEW_EACH));
        when(svc.deleteAfterFire(SCHEDULE_ID)).thenReturn(false);
        NotificationQueue queue = mock(NotificationQueue.class);
        ScheduleSessionResolver resolver = mock(ScheduleSessionResolver.class);
        when(resolver.resolve(any(), anyString())).thenReturn(null);
        ScheduleRunsService runs = mock(ScheduleRunsService.class);
        when(runs.start(any(), anyString(), any(), anyString())).thenReturn("run-1");

        job(svc, queue, resolver, runs).execute(ctx());

        verify(queue, never()).enqueue(any());
        verify(runs).finish(eq("run-1"), eq("error"), anyString(), anyString(), eq(null));
    }

    @Test
    @DisplayName("NONE（本来无会话）正常返 null ⇒ 仍按无头入队，不误判失败")
    void noneModeStillEnqueuesHeadless() throws Exception {
        ScheduleService svc = mock(ScheduleService.class);
        when(svc.getById(SCHEDULE_ID)).thenReturn(dto(SessionMode.NONE));
        when(svc.deleteAfterFire(SCHEDULE_ID)).thenReturn(false);
        NotificationQueue queue = mock(NotificationQueue.class);
        ScheduleSessionResolver resolver = mock(ScheduleSessionResolver.class);
        when(resolver.resolve(any(), anyString())).thenReturn(null);
        ScheduleRunsService runs = mock(ScheduleRunsService.class);
        when(runs.start(any(), anyString(), any(), anyString())).thenReturn("run-2");

        job(svc, queue, resolver, runs).execute(ctx());

        verify(queue).enqueue(any());
        verify(runs, never()).finish(anyString(), eq("error"), anyString(), anyString(), any());
    }
}
