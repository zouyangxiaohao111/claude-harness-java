package com.nexusai.infra.schedule;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.agent.tool.config.CronEnabledGates;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRON-F2 · TestJob fire 路径 killswitch gate（对齐 CC isKilled 每 tick 检查）。
 *
 * <p>WHY（规则九 · 验证意图）：OPD-Cron-07-h 拍板「会话中途可关闭定时功能，关闭后已注册任务
 * 立即停止」。CC 真源：
 * <ul>
 *   <li>{@code cronScheduler.ts:231} {@code if (isKilled?.()) return} —— check() 每 tick（
 *       CHECK_INTERVAL_MS=1000, :456）顶部 gate，关闭后 bail before firing anything；
 *       已注册任务不再 fire（任务仍注册，但每 tick 被 gate 拦下）。</li>
 *   <li>{@code useScheduledTasks.ts:119} {@code isKilled: () => !isKairosCronEnabled()} ——
 *       REPL 注入的 killswitch，即「定时功能关闭」就是 isKilled 为 true。</li>
 * </ul>
 *
 * <p>Java 等价：{@link CronEnabledGates#isKairosCronEnabled()} 关闭 → TestJob.execute()
 * 开头 return（不查 dto、不入队、不触发 teammate 路由/孤儿清理）。
 *
 * <p>门控 null 语义：cronGates 未注入（非 Spring 环境/Quartz 反射早于 bean 就绪）→ fail-open
 * （视为开，保持现状）；生产 CronEnabledGates 由 CronEnabledGatesConfig 恒注册不会 null。
 */
class TestJobKillswitchGateTest {

    private JobExecutionContext buildCtx(String scheduleId, String scheduleName, String scheduleKind) {
        JobDataMap data = new JobDataMap();
        data.put("scheduleId", scheduleId);
        data.put("scheduleName", scheduleName);
        data.put("scheduleKind", scheduleKind);
        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(data);
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getJobDetail()).thenReturn(jobDetail);
        when(ctx.getFireTime()).thenReturn(java.util.Date.from(java.time.Instant.now()));
        return ctx;
    }

    @Test
    @DisplayName("门关（isKairosCronEnabled=false）→ execute 不 fire：不入队、不查 dto、不删孤儿")
    void gateClosed_skipsFire_noEnqueueNoServiceCall() throws Exception {
        // ── 准备：门关 + 正常 mock dto（若 gate 失效会走 fire 入队路径）──
        String scheduleId = "sch-kill-001";
        ScheduleDto dto = new ScheduleDto(
            scheduleId, "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            "运行项目测试", "desc", OffsetDateTime.now(), "ok",
            ScheduleScope.DURABLE, null, null, null);
        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.getById(scheduleId)).thenReturn(dto);
        NotificationQueue notificationQueue = new NotificationQueue();

        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", scheduleService);
        ReflectionTestUtils.setField(job, "notificationQueue", notificationQueue);
        // 门关：agentTriggerCron=false → isKairosCronEnabled=false（CronEnabledGatesTest 已证）
        ReflectionTestUtils.setField(job, "cronGates", new CronEnabledGates(false, true));

        // ── 执行 ──
        job.execute(buildCtx(scheduleId, "测试任务", "cron"));

        // ── 断言：不 fire = 队列 0 条 + dto 从未被查 + delete 从未被调 ──
        List<QueueItem> items = notificationQueue.dequeueAll();
        assertThat(items).as("门关后已注册任务 fire 必须被 gate 拦下（队列 0 条）").isEmpty();
        verify(scheduleService, never()).getById(scheduleId);
        verify(scheduleService, never()).delete(scheduleId);
    }

    @Test
    @DisplayName("门开（默认）→ execute 正常 fire 入队（对照，复用 TestJobFireInjectionTest 反射注入模式）")
    void gateOpen_normalFireEnqueues() throws Exception {
        // ── 准备：门开（DEFAULTS = agentTriggerCron=true）──
        String scheduleId = "sch-kill-open";
        ScheduleDto dto = new ScheduleDto(
            scheduleId, "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            "运行项目测试", "desc", OffsetDateTime.now(), "ok",
            ScheduleScope.DURABLE, null, null, null);
        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.getById(scheduleId)).thenReturn(dto);
        NotificationQueue notificationQueue = new NotificationQueue();

        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", scheduleService);
        ReflectionTestUtils.setField(job, "notificationQueue", notificationQueue);
        ReflectionTestUtils.setField(job, "cronGates", CronEnabledGates.DEFAULTS);

        // ── 执行 ──
        job.execute(buildCtx(scheduleId, "测试任务", "cron"));

        // ── 断言：门开 → 正常入队 1 条（对齐 CC onFireTask lead 路径）──
        List<QueueItem> items = notificationQueue.dequeueAll();
        assertThat(items).as("门开应正常 fire 入队").hasSize(1);
        assertThat(items.get(0).value()).isEqualTo("运行项目测试");
    }

    @Test
    @DisplayName("cronGates 未注入（null）→ fail-open：保持现状正常 fire")
    void nullGate_failOpen_fireContinues() throws Exception {
        // ── 准备：不注入 cronGates（null），其余注入同 TestJobFireInjectionTest ──
        String scheduleId = "sch-kill-null";
        ScheduleDto dto = new ScheduleDto(
            scheduleId, "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            "运行项目测试", "desc", OffsetDateTime.now(), "ok",
            ScheduleScope.DURABLE, null, null, null);
        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.getById(scheduleId)).thenReturn(dto);
        NotificationQueue notificationQueue = new NotificationQueue();

        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", scheduleService);
        ReflectionTestUtils.setField(job, "notificationQueue", notificationQueue);
        // 故意不注入 cronGates

        // ── 执行 ──
        job.execute(buildCtx(scheduleId, "测试任务", "cron"));

        // ── 断言：null 视为开 → 正常入队 ──
        List<QueueItem> items = notificationQueue.dequeueAll();
        assertThat(items).as("cronGates null → fail-open 正常 fire").hasSize(1);
    }
}
