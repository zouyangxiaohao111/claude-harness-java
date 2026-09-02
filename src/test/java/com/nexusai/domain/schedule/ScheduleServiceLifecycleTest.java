package com.nexusai.domain.schedule;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.schedule.QuartzScheduleService;
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
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CRON-B3-1（决策 #7 / OPD-Cron-D5）生命周期聚焦测试：SESSION = 随进程死。
 *
 * <p>WHY（规则九 · 测试验证意图）: CC cronTasks.ts:59-63 durable=false → session-scoped
 * （仅内存，cronTasks.ts:211-213 addSessionCronTask），进程死亡 session 任务即消失，无重启残留
 * （cronScheduler.ts:376-378 每 tick 读内存 + :329 fire 后同步内存删）。Java 侧用户拍板
 * （OPD-Cron-02）SESSION 仍落 SQLite，故需补偿两条：
 * <ol>
 *   <li><b>cleanupBySession DB 回退</b>：重启后进程内存 sessionJobs 索引为空，closeSession
 *       只读内存索引会漏删 → DB 行残留（复验版 §13 R-2）。改造后以 DB 列
 *       scope=SESSION &amp;&amp; session_id=? 为权威逐行删除（unregister + deleteById）。</li>
 *   <li><b>启动清扫</b>：启动时清扫所有 scope=SESSION 任务（delete + unregister），
 *       对齐 CC「SESSION=随进程死」语义（OPD-Cron-D5 启动清扫 + DB 回退）。</li>
 * </ol>
 *
 * <p>用 MybatisFlexBootstrap（无 Spring）直连临时 SQLite + Flyway V1..V9 + mock Quartz，
 * 不启动完整应用上下文（避免 Quartz / WebSocket / MCP 等耗时依赖）。
 *
 * <p>注意：MybatisFlexBootstrap 是单例，本测试须独立运行
 * （{@code mvn -Dtest=ScheduleServiceLifecycleTest test}），不与其它使用 Flex 的测试类混跑。
 */
class ScheduleServiceLifecycleTest {

    @TempDir
    static Path tempDir;

    private static ScheduleMapper mapper;
    private QuartzScheduleService quartz;
    private ScheduleService service;

    @BeforeAll
    static void setUpDatabase() {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("cron-b3-1.db");
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
    void setUp() {
        // 每个用例独立 mock Quartz（verify unregister）+ 全新 service（sessionJobs 空索引）
        quartz = mock(QuartzScheduleService.class);
        service = newScheduleService(quartz);
        List<ScheduleRecord> rows = mapper.selectAll();
        if (!rows.isEmpty()) {
            mapper.deleteBatchByIds(rows.stream().map(ScheduleRecord::getId).toList());
        }
    }

    /** 构造 ScheduleService：注入共享 DB mapper + 指定 Quartz mock（模拟重启 = 新实例 + 空 sessionJobs）。 */
    private static ScheduleService newScheduleService(QuartzScheduleService q) {
        ScheduleService svc = new ScheduleService();
        ReflectionTestUtils.setField(svc, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(svc, "quartzScheduleService", q);
        return svc;
    }

    /** 直插 DB 行（绕过 create 的 sessionJobs 登记，验证以 DB 列为权威）。 */
    private String insertRow(String id, ScheduleScope scope, String sessionId) {
        ScheduleRecord r = new ScheduleRecord();
        r.setId(id);
        r.setName(id);
        r.setKind(ScheduleKind.cron.name());
        r.setCron("0 9 * * *");
        r.setCommand("echo " + id);
        r.setDescription("b3-1 seed");
        r.setCreatedAt(System.currentTimeMillis());
        r.setPermanent(Boolean.FALSE);
        r.setScope(scope.name());
        r.setSessionId(sessionId);
        mapper.insert(r);
        return id;
    }

    private ScheduleDto createSessionJob(String name, String sessionId) {
        return service.create(new ScheduleCreateRequest(
            name, ScheduleKind.cron, "0 9 * * *", null, null,
            "echo " + name, "b3-1 desc", ScheduleScope.SESSION, sessionId, null, null, null));
    }

    @Test
    @DisplayName("T1 重启后 cleanupBySession 以 DB 为权威删除 SESSION 任务（返回 1 + unregister + 行删）")
    void cleanupBySessionDeletesFromDbAfterRestart() {
        // WHY: 重启后进程内存 sessionJobs 为空（CC session 任务仅内存，Java 拍板落库 OPD-Cron-02）。
        // 旧实现只读 sessionJobs → 返回 0 → DB 行残留、closeSession 无法清理（复验 §13 R-2）。
        // 改造后 cleanupBySession 必须扫 DB scope=SESSION && session_id=? 逐行 unregister+deleteById。
        ScheduleDto created = createSessionJob("b3-1-t1", "sess-X");
        String id = created.id();

        // 模拟重启：新 service 实例 + 全新空 sessionJobs 索引（CC 进程死亡 session 任务即消失）
        QuartzScheduleService restartQuartz = mock(QuartzScheduleService.class);
        ScheduleService restarted = newScheduleService(restartQuartz);

        int deleted = restarted.cleanupBySession("sess-X");

        assertThat(deleted)
            .as("重启后 cleanupBySession 必须由 DB 回退删除（修复仅内存索引缺陷）")
            .isEqualTo(1);
        assertThat(mapper.selectOneById(id))
            .as("DB 行必须已删除（closeSession 清理闭环，不留僵尸）")
            .isNull();
        verify(restartQuartz).unregisterSchedule(id);
    }

    @Test
    @DisplayName("T2 未知 session 的 cleanupBySession → noop 返回 0，无 unregister")
    void cleanupBySessionUnknownSessionNoop() {
        // WHY: 未知 session 不应有任何副作用（CC removeSessionCronTasks 无命中返回 0 且不删，
        // state.ts:1307-1315 removed===0 → return 0）。误删其它 session 任务即数据破坏。
        createSessionJob("b3-1-t2", "sess-other");

        int deleted = service.cleanupBySession("ghost");

        assertThat(deleted).as("未知 session 必须 noop 返回 0").isZero();
        verify(quartz, never()).unregisterSchedule(anyString());
        assertThat(mapper.selectAll()).as("其它 session 任务必须保留").hasSize(1);
    }

    @Test
    @DisplayName("T3 启动清扫（active 空）→ 删所有 SESSION，保 DURABLE，unregister 仅对 SESSION")
    void sweepDeletesAllSessionKeepsPersistent() {
        // WHY: CC 进程死亡 session 任务即消失，启动清扫是对 Java「SESSION 仍落库」的补偿
        // （OPD-Cron-D5）。scope=SESSION 过滤兜底 DURABLE 恒不动（破坏性删库安全闸，
        // 同 surfaceMissedForStartup ScheduleService:513 同款过滤）。
        String sessionId = insertRow("b3-1-s", ScheduleScope.SESSION, "sess-sw");
        String persistentId = insertRow("b3-1-p", ScheduleScope.DURABLE, null);

        int deleted = service.sweepSessionTasksAtStartup(Set.of());

        assertThat(deleted).as("仅 SESSION 被清扫").isEqualTo(1);
        assertThat(mapper.selectOneById(sessionId)).as("SESSION 任务必须被启动清扫删除").isNull();
        assertThat(mapper.selectOneById(persistentId)).as("DURABLE 任务必须保留（R1 安全闸）").isNotNull();
        verify(quartz).unregisterSchedule(sessionId);
        verify(quartz, never()).unregisterSchedule(persistentId);
    }

    @Test
    @DisplayName("T4 启动清扫尊重 activeSessionIds：A 保留、B 删除")
    void sweepKeepsActiveSession() {
        // WHY: 决策原文「非活动会话」——启动时 RUNNING_SESSIONS 为空（Set.of() → 全量清扫），
        // 参数化 Set 供测试注入受控集合，兼顾未来会话注册表出现时只清孤儿。
        String a = insertRow("b3-1-a", ScheduleScope.SESSION, "sess-A");
        String b = insertRow("b3-1-b", ScheduleScope.SESSION, "sess-B");

        int deleted = service.sweepSessionTasksAtStartup(Set.of("sess-A"));

        assertThat(deleted).as("仅非活动会话 B 被清扫").isEqualTo(1);
        assertThat(mapper.selectOneById(a)).as("活动会话 A 的任务必须保留").isNotNull();
        assertThat(mapper.selectOneById(b)).as("非活动会话 B 的任务必须删除").isNull();
        verify(quartz).unregisterSchedule(b);
        verify(quartz, never()).unregisterSchedule(a);
    }
}
