package com.nexusai.domain.schedule;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRON-B3-2（决策 #8 / open-decisions.md R-1 补充）启动对账聚焦测试：DB schedules ↔ QRTZ 全量对账。
 *
 * <p>WHY（规则九 · 测试验证意图）：CC cronScheduler.ts:179-227 load(initial) 启动时以权威存储
 * （scheduled_tasks.json）全量重建内存调度器；Java 等价 = 启动时以 DB 为权威、对 QRTZ（持久
 * 调度器）全量对账。QRTZ JDBC 损坏 / 崩溃中间态会造成两侧不一致：
 * <ol>
 *   <li><b>DB 有任务 QRTZ 缺 trigger</b>（job 缺失 或 job 在 trigger 空，QuartzScheduleService:97-102
 *       已注释该异常态）→ 补 registerSchedule（防僵尸 = DB 有任务但 QRTZ 不 fire）</li>
 *   <li><b>QRTZ 有 job DB 无记录</b>（孤儿，如 delete 崩溃中间态）→ warn（决策 #8 字面只 warn，不删）</li>
 * </ol>
 * T1-T4 各自守护上述一条不变量。
 *
 * <p>真实 Quartz RAMJobStore Scheduler（镜像 QuartzScheduleServiceMisfireTest）+ 真实
 * QuartzScheduleService（ReflectionTestUtils 注入真实 Scheduler，非 mock）+ ScheduleService
 * （MybatisFlexBootstrap + 临时 SQLite + Flyway V1..V9 + 注入真实 QuartzScheduleService）。
 *
 * <p>注意：MybatisFlexBootstrap 是单例，本测试须独立运行
 * （{@code mvn -Dtest=ScheduleServiceReconcileTest test}），不与其它使用 Flex 的测试类混跑。
 */
class ScheduleServiceReconcileTest {

    @TempDir
    static Path tempDir;

    private static ScheduleMapper mapper;
    private Scheduler scheduler;
    private QuartzScheduleService quartzService;
    private ScheduleService service;

    private Logger scheduleLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeAll
    static void setUpDatabase() {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("cron-b3-2.db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(dbUrl);

        // Flyway 迁移 V1..V9（含 V7 created_at/permanent、V8 scope/session_id、V9 agent_id）
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
    void setUp() throws Exception {
        // 真实 Quartz RAMJobStore（镜像 QuartzScheduleServiceMisfireTest）—— 对账断言依赖真实
        // checkExists/getTriggersOfJob/getJobKeys，mock 无法表达「QRTZ 损坏」与「孤儿」两个维度。
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "ReconcileTestScheduler");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        props.setProperty("org.quartz.scheduler.skipUpdateCheck", "true");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.start();

        quartzService = new QuartzScheduleService();
        // 字段注入为 @Autowired，单测直接 ReflectionTestUtils 注入真实 Scheduler
        ReflectionTestUtils.setField(quartzService, "scheduler", scheduler);

        service = new ScheduleService();
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(service, "quartzScheduleService", quartzService);

        // Flex 单例跨用例：清 DB + QRTZ 残留
        List<ScheduleRecord> rows = mapper.selectAll();
        if (!rows.isEmpty()) {
            mapper.deleteBatchByIds(rows.stream().map(ScheduleRecord::getId).toList());
        }
        for (JobKey key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
            scheduler.deleteJob(key);
        }

        // 捕获 ScheduleService warn 日志（T3 孤儿断言）
        scheduleLogger = (Logger) LoggerFactory.getLogger(ScheduleService.class);
        appender = new ListAppender<>();
        appender.start();
        scheduleLogger.addAppender(appender);
        scheduleLogger.setLevel(ch.qos.logback.classic.Level.WARN);
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduleLogger.detachAppender(appender);
        appender.stop();
        scheduler.shutdown();
    }

    /** 经 create 完整落库 + 注册 QRTZ 的 DURABLE cron 任务（Quartz 6 段表达式，镜像 QuartzScheduleServiceMisfireTest）。 */
    private String createPersistentCron(String id, String name) {
        return service.create(new ScheduleCreateRequest(
            name, ScheduleKind.cron, "0 0 9 * * ?", null, null,
            "echo " + name, "b3-2 desc", ScheduleScope.DURABLE, null, null, null, null)).id();
    }

    private boolean hasTriggers(String id) throws Exception {
        return !scheduler.getTriggersOfJob(JobKey.jobKey("schedule-" + id)).isEmpty();
    }

    @Test
    @DisplayName("T1 启动对账：DB 有任务 QRTZ job 缺失 → 补注册（防僵尸）")
    void reconcileReRegistersWhenJobMissing() throws Exception {
        // WHY: QRTZ 损坏（job 丢失）→ DB 有任务但 QRTZ 永不 fire（僵尸）。CC load() 启动全量重建
        // 语义（cronScheduler.ts:179-227）要求以 DB 为权威补齐 QRTZ，否则任务静默失效。
        String id = createPersistentCron("sch-rec-t1", "t1");

        quartzService.unregisterSchedule(id);   // 模拟 QRTZ 损坏：job + trigger 全丢

        int reRegistered = service.reconcileQuartzAtStartup();

        assertThat(reRegistered).as("缺失 job 的任务必须被补注册").isEqualTo(1);
        assertThat(quartzService.hasRegistered(id))
            .as("补注册后 hasRegistered 必须为 true（job+trigger 双存在）").isTrue();
        assertThat(hasTriggers(id)).as("补注册后 QRTZ 必须挂上 trigger").isTrue();
    }

    @Test
    @DisplayName("T2 启动对账：job 在但 trigger 空（半僵尸态）→ 补注册重挂 trigger")
    void reconcileReRegistersWhenTriggersEmpty() throws Exception {
        // WHY: QuartzScheduleService.registerSchedule 幂等注释（:97-102）明确「job 存在但 trigger 缺失
        // 的异常态 getTriggersOfJob 为空 → 直接重挂」——对账必须识别这种半僵尸态并补 trigger，否则
        // job 空转不 fire（deleteAfterFire 任务不存在 warn no-op，形成持续僵尸，plan risks #4）。
        String id = createPersistentCron("sch-rec-t2", "t2");

        // 手动删 trigger 留 job（模拟半损坏态：unschedule 全部 trigger，job storeDurably 仍在）
        for (Trigger t : scheduler.getTriggersOfJob(JobKey.jobKey("schedule-" + id))) {
            scheduler.unscheduleJob(t.getKey());
        }

        int reRegistered = service.reconcileQuartzAtStartup();

        assertThat(reRegistered).as("trigger 空的任务必须被补注册重挂 trigger").isEqualTo(1);
        assertThat(hasTriggers(id)).as("补注册后 trigger 必须重新挂上").isTrue();
        assertThat(quartzService.hasRegistered(id)).as("半僵尸态修复后 hasRegistered 为 true").isTrue();
    }

    @Test
    @DisplayName("T3 启动对账：QRTZ 孤儿 job（DB 无记录）→ warn 不删（决策 #8 字面）")
    void reconcileWarnsOrphanNotDelete() throws Exception {
        // WHY: 决策 #8 对孤儿只报 warn 不自动删（owner 拍板前不破坏数据）。孤儿来源 = delete 崩溃
        // 中间态（DB 删行后、QRTZ unregister 前崩溃）或手工误删 DB。若对账自动删孤儿，即超出决策
        // 字面授权（本 session concerns 登记）。
        String id = createPersistentCron("sch-rec-t3", "t3");

        mapper.deleteById(id);   // 模拟 DB 行先失（QRTZ job 仍在 = 孤儿）

        int reRegistered = service.reconcileQuartzAtStartup();

        assertThat(reRegistered).as("孤儿无 DB 记录，不应补注册").isZero();
        assertThat(scheduler.checkExists(JobKey.jobKey("schedule-" + id)))
            .as("决策 #8 只 warn 不删，孤儿 job 必须仍在 QRTZ").isTrue();
        assertThat(appender.list)
            .as("孤儿必须报 warn 日志（决策 #8 只报 warn）")
            .anyMatch(e -> e.getFormattedMessage().contains("孤儿"));
    }

    @Test
    @DisplayName("T4 启动对账：两侧已一致 → 补注册数 0 且 trigger 不翻倍（幂等）")
    void reconcileNoopWhenConsistent() throws Exception {
        // WHY: 对账必须幂等（对齐 CC load() 每次启动全量重建的确定性）；已一致时重复执行不得
        // 产生额外注册 / 重复 trigger（否则每次启动都翻倍 trigger，重注册即缺陷）。
        String id = createPersistentCron("sch-rec-t4", "t4");
        int countAfterFirst = scheduler.getTriggersOfJob(JobKey.jobKey("schedule-" + id)).size();

        int first = service.reconcileQuartzAtStartup();
        int second = service.reconcileQuartzAtStartup();

        assertThat(first).as("已一致首次对账补注册 0").isZero();
        assertThat(second).as("重复对账补注册 0（幂等）").isZero();
        assertThat(scheduler.getTriggersOfJob(JobKey.jobKey("schedule-" + id)))
            .as("重复对账不得翻倍 trigger 数").hasSize(countAfterFirst);
    }
}
