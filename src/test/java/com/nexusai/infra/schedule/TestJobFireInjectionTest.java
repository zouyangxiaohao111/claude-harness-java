package com.nexusai.infra.schedule;

import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CRON-D1 · TestJob fire 注入对齐 — 验证意图（WHY）：
 *
 * <p>CC enqueueForLead（useScheduledTasks.ts:71-82）把 cron 触发时的<b>原始 prompt</b>
 * 以 mode='prompt' + priority='later' + isMeta=true + workload=WORKLOAD_CRON 入队统一
 * notificationQueue；模型应收到<b>待执行 prompt</b> 而非通知摘要。
 *
 * <p>断言哪些字段：
 * <ul>
 *   <li>value == 原始 command，且不含 task-notification / [Scheduled] 摘要子串（C20 删除目标）</li>
 *   <li>mode == "prompt"（非 "scheduled"）</li>
 *   <li>priority == LATER（CC 'later'）</li>
 *   <li>isMeta == true（useScheduledTasks.ts:76；系统生成，UI 隐藏但模型可见）</li>
 *   <li>workload == "cron"（workloadContext.ts:26 WORKLOAD_CRON；低 QoS billing 标记）</li>
 *   <li>agentId == null（G-13：主线程 fire，queueProcessor.ts:61 isMainThread）</li>
 * </ul>
 */
class TestJobFireInjectionTest {

    @Test
    void fire_injectsRawPromptWithPromptModeAndCronWorkload() throws Exception {
        // ── 准备 ──
        String scheduleId = "sch-test-001";
        String rawPrompt = "运行项目测试并汇报覆盖率";
        ScheduleDto dto = new ScheduleDto(
            scheduleId, "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            rawPrompt, "desc", OffsetDateTime.now(), "ok",
            ScheduleScope.DURABLE, null, null, null);

        JobDataMap data = new JobDataMap();
        data.put("scheduleId", scheduleId);
        data.put("scheduleName", "测试任务");
        data.put("scheduleKind", "cron");
        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(data);
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getJobDetail()).thenReturn(jobDetail);
        when(ctx.getFireTime()).thenReturn(java.util.Date.from(java.time.Instant.now()));

        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.getById(scheduleId)).thenReturn(dto);
        NotificationQueue notificationQueue = new NotificationQueue();

        TestJob job = new TestJob();
        // @Autowired(required=false) 无 setter → 反射注入（spring-boot-starter-test）
        ReflectionTestUtils.setField(job, "scheduleService", scheduleService);
        ReflectionTestUtils.setField(job, "notificationQueue", notificationQueue);

        // ── 执行 ──
        job.execute(ctx);

        // ── 断言 ──
        List<QueueItem> items = notificationQueue.dequeueAll();
        assertEquals(1, items.size(), "fire 应恰好入队 1 条负载");
        QueueItem item = items.get(0);
        assertEquals(rawPrompt, item.value(), "value 必须是原始 prompt（非 XML 摘要）");
        assertFalse(item.value().contains("task-notification"), "不得再包 <task-notification> XML（C20）");
        assertFalse(item.value().contains("[Scheduled]"), "不得再包 [Scheduled] 摘要前缀");
        assertEquals("prompt", item.mode(), "mode 必须为 'prompt'（CC useScheduledTasks.ts:74）");
        assertEquals(NotificationQueue.Priority.LATER, item.priority(), "priority 必须为 LATER（CC 'later'）");
        assertTrue(item.isMeta(), "isMeta 必须为 true（CC useScheduledTasks.ts:76）");
        assertEquals("cron", item.workload(), "workload 必须为 WORKLOAD_CRON='cron'（workloadContext.ts:26）");
        assertNull(item.agentId(), "主线程 fire agentId=null（G-13）");
    }

    @Test
    void fire_passesThroughBoundProjectToQueueItem() throws Exception {
        // WHY (批次X Q2): DURABLE scope cron 触发时把创建会话绑定项目（ScheduleDto.boundProject，
        // V23 bound_project 列）写入 QueueItem —— 队列是跨线程异步边界，boundProject 必须随队列项
        // 存活，消费线程（CronIdleExecutor.runOneAgentLoop）才能恢复项目上下文（CwdResolution
        // 解析到创建项目而非 user.dir，对齐 CC durable 文件位置锚项目 cronTasks.ts:74-83）。
        String scheduleId = "sch-test-003";
        String rawPrompt = "运行项目测试并汇报覆盖率";
        String boundProject = "C:/proj/durable-cron-a";
        ScheduleDto dto = new ScheduleDto(
            scheduleId, "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            rawPrompt, "desc", OffsetDateTime.now(), "ok",
            ScheduleScope.DURABLE, null, null, boundProject);

        JobDataMap data = new JobDataMap();
        data.put("scheduleId", scheduleId);
        data.put("scheduleName", "测试任务");
        data.put("scheduleKind", "cron");
        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(data);
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getJobDetail()).thenReturn(jobDetail);
        when(ctx.getFireTime()).thenReturn(java.util.Date.from(java.time.Instant.now()));

        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.getById(scheduleId)).thenReturn(dto);
        NotificationQueue notificationQueue = new NotificationQueue();

        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", scheduleService);
        ReflectionTestUtils.setField(job, "notificationQueue", notificationQueue);

        job.execute(ctx);

        List<QueueItem> items = notificationQueue.dequeueAll();
        assertEquals(1, items.size(), "fire 应恰好入队 1 条负载");
        QueueItem item = items.get(0);
        assertEquals(boundProject, item.boundProject(),
            "DURABLE scope cron 的 QueueItem 必须携带创建会话绑定项目（批次X Q2）");
    }

    @Test
    void fire_passesThroughScheduleIdToQueueItem() throws Exception {
        // WHY (cron-transcript 方案 a): DURABLE scope cron 触发时把调度 id（TestJob.execute
        // JobDataMap:99 取到）写入 QueueItem —— 队列是跨线程异步边界，scheduleId 必须随队列项存活，
        // 消费线程（CronIdleExecutor.runOneAgentLoop）才能派生确定性 per-task 虚拟会话键作
        // transcript 会话键（消除现状 GLOBAL 共享污染：同项目所有 DURABLE fire 不再共写
        // {boundProject}/0000...c001.jsonl，对齐 CC「每会话一文件」）。scheduleId 是 TestJob
        // 已取到的源头参数（:99 JobDataMap），透传零新增查询。
        String scheduleId = "sch-test-004";
        String rawPrompt = "运行项目测试并汇报覆盖率";
        ScheduleDto dto = new ScheduleDto(
            scheduleId, "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            rawPrompt, "desc", OffsetDateTime.now(), "ok",
            ScheduleScope.DURABLE, null, null, null);

        JobDataMap data = new JobDataMap();
        data.put("scheduleId", scheduleId);
        data.put("scheduleName", "测试任务");
        data.put("scheduleKind", "cron");
        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(data);
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getJobDetail()).thenReturn(jobDetail);
        when(ctx.getFireTime()).thenReturn(java.util.Date.from(java.time.Instant.now()));

        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.getById(scheduleId)).thenReturn(dto);
        NotificationQueue notificationQueue = new NotificationQueue();

        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", scheduleService);
        ReflectionTestUtils.setField(job, "notificationQueue", notificationQueue);

        job.execute(ctx);

        List<QueueItem> items = notificationQueue.dequeueAll();
        assertEquals(1, items.size(), "fire 应恰好入队 1 条负载");
        QueueItem item = items.get(0);
        assertEquals(scheduleId, item.scheduleId(),
            "DURABLE scope cron 的 QueueItem 必须携带调度 id（cron-transcript 方案 a）");
    }

    @Test
    void fire_passesThroughSessionIdToQueueItem() throws Exception {
        // WHY (CRON-D5 改1): SESSION scope cron 触发时必须把创建会话 sessionId 写入 QueueItem ——
        // 队列是跨线程异步边界（Quartz worker 线程 MDC 到消费线程已丢失），sessionId 必须随队列项
        // 存活，消费线程（CronIdleExecutor）才能恢复 MDC + cwd 归组创建会话（对齐 CC 单进程
        // ambient"任务即属创建会话"）。DURABLE scope（sessionId=null）不携带（零回归）。
        String scheduleId = "sch-test-002";
        String rawPrompt = "运行项目测试并汇报覆盖率";
        String sessionId = UUID.randomUUID().toString();
        ScheduleDto dto = new ScheduleDto(
            scheduleId, "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            rawPrompt, "desc", OffsetDateTime.now(), "ok",
            ScheduleScope.SESSION, sessionId, null, null);

        JobDataMap data = new JobDataMap();
        data.put("scheduleId", scheduleId);
        data.put("scheduleName", "测试任务");
        data.put("scheduleKind", "cron");
        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(data);
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getJobDetail()).thenReturn(jobDetail);
        when(ctx.getFireTime()).thenReturn(java.util.Date.from(java.time.Instant.now()));

        ScheduleService scheduleService = mock(ScheduleService.class);
        when(scheduleService.getById(scheduleId)).thenReturn(dto);
        NotificationQueue notificationQueue = new NotificationQueue();

        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", scheduleService);
        ReflectionTestUtils.setField(job, "notificationQueue", notificationQueue);

        job.execute(ctx);

        List<QueueItem> items = notificationQueue.dequeueAll();
        assertEquals(1, items.size(), "fire 应恰好入队 1 条负载");
        QueueItem item = items.get(0);
        assertEquals(sessionId, item.sessionId(),
            "SESSION scope cron 的 QueueItem 必须携带创建会话 sessionId（CRON-D5 改1）");
    }
}
