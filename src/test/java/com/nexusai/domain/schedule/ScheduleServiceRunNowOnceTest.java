package com.nexusai.domain.schedule;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.schedule.TestJob;
import com.nexusai.model.schedule.dto.RunNowResponse;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
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
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRON-B4-4（决策 #15 / OPD-EL-04）· runNow 同步返回 fire-then-delete 结果 聚焦测试。
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: OPD-EL-04 拍板「REST runNow 同步返回触发+删除结果，与工具路径
 * 一致」。工具路径 one-shot 语义 = 同步 fire 后自动删（CronCreateTool.ts:152 "fire once then
 * auto-delete"），调用方需立即感知删除，且 fire 后删行释放 MAX_JOBS 配额。本测试锁定：
 * <ul>
 *   <li>once → executed=true + deleted=true + DB 行消失 + 不触发 triggerNow（同步 fire 内联，避免
 *       worker 读行已删的竞态）+ unregisterSchedule 已删 Quartz job（无双发）</li>
 *   <li>recurring → deleted=false + triggerNow 异步触发 + 行保留（CC cronScheduler.ts:315
 *       recurring &amp;&amp; !aged → 保留 reschedule）</li>
 *   <li>not-found → NotFoundException（与既有 getById/delete 一致）</li>
 * </ul>
 *
 * <p>用 MybatisFlexBootstrap（无 Spring）直连临时 SQLite + Flyway V1..V9 + mock Quartz + real
 * TestJob（mock NotificationQueue），不启动完整应用上下文。ScheduleService 注入 real TestJob
 * 验证 once 同步 fire 内联（gate→getById→enqueueLead→deleteAfterFire 全链路真实执行）。
 *
 * <p>注意：MybatisFlexBootstrap 是单例，本测试须独立运行
 * （{@code mvn -Dtest=ScheduleServiceRunNowOnceTest test}），不与其它使用 Flex 的测试类混跑。
 */
class ScheduleServiceRunNowOnceTest {

    @TempDir
    static Path tempDir;

    private static ScheduleMapper mapper;
    private QuartzScheduleService quartz;
    private ScheduleService service;

    @BeforeAll
    static void setUpDatabase() {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("cron-b4-4.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(dbUrl);
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexBootstrap.getInstance()
            .setDataSource(ds)
            .addMapper(ScheduleMapper.class)
            .start();
        mapper = MybatisFlexBootstrap.getInstance().getMapper(ScheduleMapper.class);
    }

    @BeforeEach
    void setUp() {
        List<ScheduleRecord> rows = mapper.selectAll();
        if (!rows.isEmpty()) {
            mapper.deleteBatchByIds(rows.stream().map(ScheduleRecord::getId).toList());
        }
        quartz = mock(QuartzScheduleService.class);
        service = new ScheduleService();
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(service, "quartzScheduleService", quartz);
        // 注入 real TestJob（mock NotificationQueue）→ once 同步 fire 全链路真实执行
        TestJob job = new TestJob();
        ReflectionTestUtils.setField(job, "scheduleService", service);
        ReflectionTestUtils.setField(job, "notificationQueue", mock(NotificationQueue.class));
        ReflectionTestUtils.setField(service, "testJob", job);
    }

    /** once 任务 runAt：未来某日 :mm（runNow 不依赖到点，避免 jitter lead 把 runAt 推到过去） */
    private static String futureRunAt(int minute) {
        ZonedDateTime pinned = ZonedDateTime.now()
            .plusDays(1).withHour(15).withMinute(minute).withSecond(0).withNano(0);
        return pinned.toOffsetDateTime().toString();
    }

    @Test
    @DisplayName("once → executed=true deleted=true 行消失，不触发 triggerNow（OPD-EL-04 同步 fire-then-delete）")
    void onceFiresAndDeletesSynchronously() {
        // WHY: 用户 runNow 一个 one-shot 任务 → 同步 fire 且 fire-then-delete 释放 MAX_JOBS，
        // 调用方可立即感知删除（对齐 CronCreateTool.ts:152 "fire once then auto-delete"）。
        String id = service.create(new ScheduleCreateRequest(
            "run-now-once", ScheduleKind.once, null, null, futureRunAt(5),
            "echo run-now-once", "b4-4 desc",
            ScheduleScope.DURABLE, null, null, null, null)).id();

        RunNowResponse resp = service.runNow(id);

        assertThat(resp.executed()).as("once 必须同步 fire（OPD-EL-04）").isTrue();
        assertThat(resp.deleted()).as("once fire 后必须删除（fire-then-delete）").isTrue();
        assertThat(mapper.selectOneById(id)).as("DB 行必须已删除（释放 MAX_JOBS 配额）").isNull();
        verify(quartz, never()).triggerNow(id);   // once 不走异步 triggerNow（同步 fire 内联）
        verify(quartz).unregisterSchedule(id);    // deleteRowAndUnregister 已 unregister → 无双发
    }

    @Test
    @DisplayName("recurring → deleted=false + triggerNow 异步 + 行保留（CC cronScheduler.ts:315 保留 reschedule）")
    void recurringTriggersAsyncAndKeepsRow() {
        // WHY: recurring 任务保持异步触发（Quartz triggerNow），fire-then-delete 仅删 one-shot/aged；
        // CC cronScheduler.ts:315 recurring && !aged → 保留。deleted=false 供调用方区分语义。
        String id = service.create(new ScheduleCreateRequest(
            "run-now-cron", ScheduleKind.cron, "0 9 * * *", null, null,
            "echo cron", "b4-4 desc",
            ScheduleScope.DURABLE, null, null, null, null)).id();
        when(quartz.triggerNow(id)).thenReturn(true);

        RunNowResponse resp = service.runNow(id);

        assertThat(resp.executed()).as("recurring triggerNow 成功 → executed=true").isTrue();
        assertThat(resp.deleted()).as("recurring 必须保留（CC cronScheduler.ts:315）").isFalse();
        assertThat(mapper.selectOneById(id)).as("recurring 行必须保留").isNotNull();
        verify(quartz).triggerNow(id);
    }

    @Test
    @DisplayName("not-found → NotFoundException（与 getById/delete 一致）")
    void notFoundThrows() {
        assertThatThrownBy(() -> service.runNow("sch-missing"))
            .isInstanceOf(NotFoundException.class);
    }
}
