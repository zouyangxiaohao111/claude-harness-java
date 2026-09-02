package com.nexusai.infra.schedule;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import com.nexusai.application.agent.subagent.AgentMessageBus;
import com.nexusai.application.agent.subagent.AgentMessageBus.InboxMessage;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.BackgroundTaskStatus;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.NotificationQueue.QueueItem;
import com.nexusai.application.agent.tasks.TaskType;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.domain.schedule.ScheduleService;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * CRON-D4 端到端：create → toDto → fire 真实数据流（agentId 全链路接通）。
 *
 * <p><b>WHY (意图验证)</b>: 现有 9 个 fire 分发用例（TestJobTeammateRoutingTest 4 +
 * TestJobFireInjectionTest 1 + 其它）均绕过 toDto 直构 {@link ScheduleDto}，且 mock
 * {@link ScheduleService#getById} —— 它们验证 fire 分发本身，但<b>没有</b>验证
 * {@code dto.agentId()} 生产到底有没有值。D4 返工目标 = 打通
 * {@code create → ScheduleRecord.agentId → DB agent_id 列 → toDto 透传 → dto.agentId()}
 * 全链路（CC CronTask.agentId, cronTasks.ts:69）。本测试用真实 ScheduleService + 真实
 * SQLite（MybatisFlexBootstrap 无 Spring）驱动 create→getById→TestJob.execute，
 * 断言 dto.agentId() 有值且 fire 按 CC onFireTask（useScheduledTasks.ts:92-114）路由。
 *
 * <p>用例 A：create(SESSION, agentId=AGENT_ID) → getById().agentId()==AGENT_ID
 * → TestJob fire（真实 service + mock runner.findTeammateByAgentId 返回 running teammate
 * + mock bus + 真实 queue）→ verify sendToAgent(AGENT_ID, user_message=原始 prompt) 恰好
 * 1 次 + lead queue 空（CC :97-99 短路，teammate 分支绝不入 lead 队列）。
 *
 * <p>用例 B：create(SESSION, agentId=null) → TestJob fire → lead 入队 1 次 +
 * sendToAgent/delete 0（CC :110-114 lead 路径）。
 *
 * <p>注意：MybatisFlexBootstrap 是单例，本测试须独立运行
 * （{@code mvn -Dtest=TestJobAgentIdE2ETest test}），不与其它使用 Flex 的测试类混跑
 * （与 ScheduleServiceCreateStorageTest 同款约束）。
 */
class TestJobAgentIdE2ETest {

    @TempDir
    static Path tempDir;

    private static final String SESSION_ID = "sess-d4-e2e";
    private static final String AGENT_ID = UUID.randomUUID().toString();
    private static final String RAW_PROMPT = "运行项目测试并汇报覆盖率";

    private static ScheduleMapper mapper;
    private static ScheduleService service;
    private static NotificationQueue queue;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // 共享稳定 DB + 重置 MyBatis-Flex 全局状态（mapper 代理缓存/单例），避免跨测试类冲突（见 MybatisFlexDbTestSupport）。
        java.nio.file.Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        // Flyway 迁移 V1..V9（V9 建 schedules.agent_id 列，CRON-D4）
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, ScheduleMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(ScheduleMapper.class);

        service = new ScheduleService();
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(service, "quartzScheduleService",
            Mockito.mock(QuartzScheduleService.class));

        queue = new NotificationQueue();
    }

    @BeforeEach
    void cleanSchedules() {
        List<ScheduleRecord> rows = mapper.selectAll();
        if (!rows.isEmpty()) {
            mapper.deleteBatchByIds(rows.stream().map(ScheduleRecord::getId).toList());
        }
    }

    private ScheduleDto createSessionJob(String agentId) {
        return service.create(new ScheduleCreateRequest(
            "d4-e2e", ScheduleKind.cron, "0 9 * * *", null, null,
            RAW_PROMPT, "d4 e2e desc", ScheduleScope.SESSION, SESSION_ID, agentId, null, null));
    }

    private JobExecutionContext ctx(String scheduleId) {
        JobDataMap data = new JobDataMap();
        data.put("scheduleId", scheduleId);
        data.put("scheduleName", "d4-e2e");
        data.put("scheduleKind", "cron");
        JobDetail jobDetail = mock(JobDetail.class);
        when(jobDetail.getJobDataMap()).thenReturn(data);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(context.getFireTime()).thenReturn(java.util.Date.from(java.time.Instant.now()));
        return context;
    }

    private TestJob newJob(BackgroundTaskRunner runner, AgentMessageBus bus) {
        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", service);
        ReflectionTestUtils.setField(job, "backgroundTaskRunner", runner);
        ReflectionTestUtils.setField(job, "agentMessageBus", bus);
        ReflectionTestUtils.setField(job, "notificationQueue", queue);
        return job;
    }

    private BackgroundTask runningTeammate() {
        return new BackgroundTask(
            "task-d4-e2e", TaskType.IN_PROCESS_TEAMMATE, BackgroundTaskStatus.RUNNING,
            "teammate", null, System.currentTimeMillis(), null, null,
            "/tmp/task-d4-e2e.out", 0L, false, UUID.fromString(AGENT_ID), true);
    }

    @Test
    @DisplayName("E2E-A: create(SESSION,agentId)→getById().agentId()==AGENT_ID→fire 走 teammate 路由 sendToAgent 1 次 + lead 空（CC :92-99）")
    void createToDtoToFireRoutesToTeammate() throws Exception {
        // ── create：真实 ScheduleService 落库 + toDto 透传 agentId ──
        ScheduleDto created = createSessionJob(AGENT_ID);
        assertThat(created.agentId())
            .as("create 返回 dto.agentId() 必须为传入 agentId（CRON-D4 数据链起点）")
            .isEqualTo(AGENT_ID);

        // ── getById：真实 DB 读取，agentId 由 agent_id 列回填 ──
        ScheduleDto loaded = service.getById(created.id());
        assertThat(loaded).isNotNull();
        assertThat(loaded.agentId())
            .as("getById 必须由 DB agent_id 列回填 agentId（toDto 透传）")
            .isEqualTo(AGENT_ID);

        // ── fire：真实 service + mock runner（running teammate）+ mock bus + 真实 queue ──
        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        when(runner.findTeammateByAgentId(AGENT_ID)).thenReturn(runningTeammate());
        AgentMessageBus bus = mock(AgentMessageBus.class);

        newJob(runner, bus).execute(ctx(created.id()));

        // CC :98 injectUserMessageToTeammate —— prompt 注入 teammate inbox
        verify(bus).sendToAgent(eq(AGENT_ID),
            org.mockito.ArgumentMatchers.argThat(m ->
                m.type().equals(InboxMessage.TYPE_USER_MESSAGE)
                    && RAW_PROMPT.equals(m.payload())));
        assertThat(queue.dequeueAll()).as("teammate cron 绝不入 lead 队列（CC :99 return 短路）").isEmpty();
    }

    @Test
    @DisplayName("E2E-B: create(SESSION,agentId=null)→fire 走 lead 入队 1 次 + sendToAgent/delete 0（CC :110-114）")
    void createWithoutAgentIdRoutesToLead() throws Exception {
        ScheduleDto created = createSessionJob(null);
        assertThat(created.agentId()).as("主线程 SESSION create agentId 必须为 null").isNull();

        BackgroundTaskRunner runner = mock(BackgroundTaskRunner.class);
        AgentMessageBus bus = mock(AgentMessageBus.class);

        newJob(runner, bus).execute(ctx(created.id()));

        List<QueueItem> items = queue.dequeueAll();
        assertThat(items).as("agentId 空必须走 lead 入队（CC :110-114 enqueueForLead）").hasSize(1);
        QueueItem item = items.get(0);
        assertThat(item.value()).as("value 必须是原始 prompt").isEqualTo(RAW_PROMPT);
        assertThat(item.mode()).as("mode 必须为 'prompt'（CC :74）").isEqualTo("prompt");
        assertThat(item.workload()).as("workload 必须为 WORKLOAD_CRON").isEqualTo("cron");
        verify(bus, never()).sendToAgent(any(), any());
    }
}
