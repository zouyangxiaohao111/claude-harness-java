package com.nexusai.domain.schedule;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.infra.exception.NotFoundException;
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
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * IMPL-05（✗-C P2 读容错）聚焦测试：非法 kind 脏行不阻塞读路径。
 *
 * <p><b>WHY（意图验证）</b>: CC cronTasks.ts:91-140 readCronTasks 逐条守卫 —— 坏条目
 * （缺字段 / 非法 cron）logForDebugging 后 continue 跳过，绝不因单条脏数据打挂整个文件
 * （:108-126），仅合法条目 push 进结果（:127-138）。Java 旧实现 toDto:1004 裸
 * {@code ScheduleKind.valueOf(kind)} 遇脏行抛 IAE → listAll/getById 整链 500。
 * 本测试守护修复后语义：listAll 跳过脏行照常返回其余行（CC 坏条目不进列表消费面），
 * getById/update 对脏行返回 404（脏行 = 不可见资源），全部不抛 500。
 *
 * <p>脏行构造：V1:118 {@code kind TEXT NOT NULL} 仅 NOT NULL、无 CHECK 约束，直插
 * {@code kind="HOURLY"}（非 cron/once/interval）即模拟人工干预/旧数据可达的脏行。
 *
 * <p>用 MybatisFlexBootstrap（无 Spring）直连临时 SQLite + Flyway V1..V9，
 * 不启动完整应用上下文（避免 Quartz / WebSocket / MCP 等耗时依赖）。
 *
 * <p>注意：MybatisFlexBootstrap 是单例，本测试须独立运行
 * （{@code mvn -Dtest=ScheduleServiceDirtyKindReadTest test}），不与其它
 * 使用 Flex 的测试类混跑。
 */
class ScheduleServiceDirtyKindReadTest {

    @TempDir
    static Path tempDir;

    private static ScheduleMapper mapper;
    private static ScheduleService service;

    private Logger scheduleLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // 共享稳定 DB + 重置 MyBatis-Flex 全局状态（mapper 代理缓存/单例），避免跨测试类冲突（见 MybatisFlexDbTestSupport）。
        java.nio.file.Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        // Flyway 迁移 V1..V9（含 V7 created_at/permanent、V8 scope/session_id、V9 agent_id）
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
    }

    @BeforeEach
    void cleanSchedules() {
        List<ScheduleRecord> rows = mapper.selectAll();
        if (!rows.isEmpty()) {
            mapper.deleteBatchByIds(rows.stream().map(ScheduleRecord::getId).toList());
        }
    }

    /** 直插合法行（kind=cron，绕过 create 的 Quartz 注册）。 */
    private void insertValidRow(String id) {
        ScheduleRecord r = new ScheduleRecord();
        r.setId(id);
        r.setName(id);
        r.setKind(ScheduleKind.cron.name());
        r.setCron("0 9 * * *");
        r.setCommand("echo " + id);
        r.setCreatedAt(System.currentTimeMillis());
        r.setScope(ScheduleScope.DURABLE.name());
        r.setPermanent(Boolean.FALSE);
        mapper.insert(r);
    }

    /**
     * 直插脏行：kind 为非法枚举值（非 cron/once/interval）· V1:118 kind TEXT NOT NULL
     * 无 CHECK 约束，可直插 —— 模拟人工干预/旧数据可达的脏行（CC 读容错针对的坏条目）。
     */
    private void insertDirtyRow(String id) {
        ScheduleRecord r = new ScheduleRecord();
        r.setId(id);
        r.setName(id);
        r.setKind("HOURLY");
        r.setCron("0 9 * * *");
        r.setCommand("echo " + id);
        r.setCreatedAt(System.currentTimeMillis());
        r.setScope(ScheduleScope.DURABLE.name());
        r.setPermanent(Boolean.FALSE);
        mapper.insert(r);
    }

    /** 捕获 ScheduleService warn 日志（脏行跳过断言，镜像 ScheduleServiceReconcileTest:120-126）。 */
    @BeforeEach
    void attachLogAppender() {
        scheduleLogger = (Logger) LoggerFactory.getLogger(ScheduleService.class);
        appender = new ListAppender<>();
        appender.start();
        scheduleLogger.addAppender(appender);
        scheduleLogger.setLevel(ch.qos.logback.classic.Level.WARN);
    }

    @AfterEach
    void detachLogAppender() {
        scheduleLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("listAll：合法行 + 脏行共存 → 返回 1 条合法行、不含脏行、不抛（CC cronTasks.ts:108-137 坏条目不阻塞整文件）")
    void listAllSkipsDirtyKindRow() {
        insertValidRow("valid-1");
        insertDirtyRow("dirty-1");

        List<ScheduleDto> all = service.listAll();

        assertThat(all).as("脏行必须被跳过，合法行照常返回（CC 坏条目不进列表消费面）")
            .hasSize(1);
        assertThat(all.get(0).id()).as("返回的必须是合法行").isEqualTo("valid-1");
        assertThat(all).as("脏行 id 不得出现在列表").noneMatch(d -> d.id().equals("dirty-1"));
    }

    @Test
    @DisplayName("listAll：全表脏行 → 返回空列表不抛（CC 空列表兜底语义 cronTasks.ts:91-105/:127-138）")
    void listAllAllDirtyRowsReturnsEmpty() {
        insertDirtyRow("dirty-1");
        insertDirtyRow("dirty-2");

        List<ScheduleDto> all = service.listAll();

        assertThat(all).as("全脏行列表必须返回空列表而非 500（CC 坏条目跳过 → 空列表）").isEmpty();
    }

    @Test
    @DisplayName("getById：脏行 → NotFoundException(404)（脏行 = 不可见资源，与 listAll 跳过语义一致）")
    void getByIdDirtyKindThrowsNotFound() {
        insertDirtyRow("dirty-1");

        Throwable ex = catchThrowable(() -> service.getById("dirty-1"));

        assertThat(ex)
            .as("脏行 getById 必须抛 NotFoundException（404），不得 500")
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Schedule dirty-1 not found");
    }

    @Test
    @DisplayName("getById：合法行正常返回（回归，404 分支不得误伤合法行）")
    void getByIdValidRowStillWorks() {
        insertValidRow("valid-1");

        ScheduleDto dto = service.getById("valid-1");

        assertThat(dto).as("合法行 getById 必须正常返回").isNotNull();
        assertThat(dto.id()).isEqualTo("valid-1");
        assertThat(dto.kind()).as("合法行 kind 必须解析为枚举值").isEqualTo(ScheduleKind.cron);
    }

    @Test
    @DisplayName("脏行跳过必须落 warn 日志（含 id + kind 值，可观测人工干预/旧数据脏行）")
    void dirtyKindSkipLogsWarnWithIdAndKind() {
        insertValidRow("valid-1");
        insertDirtyRow("dirty-1");

        service.listAll();

        List<ILoggingEvent> warns = appender.list.stream()
            .filter(e -> e.getFormattedMessage().contains("非法 kind 脏行"))
            .toList();
        assertThat(warns).as("脏行跳过必须记录 warn 日志（对齐 CC logForDebugging 逐条守卫）")
            .hasSize(1);
        assertThat(warns.get(0).getFormattedMessage())
            .as("warn 消息必须含 id 与非法 kind 值，便于定位脏行")
            .contains("dirty-1", "HOURLY");
    }
}
