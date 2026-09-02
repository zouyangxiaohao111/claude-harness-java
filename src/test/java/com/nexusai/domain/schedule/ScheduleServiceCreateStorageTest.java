package com.nexusai.domain.schedule;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.infra.exception.MaxJobsExceededException;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.Assertions;

/**
 * CRON-B2 聚焦测试：create() 对齐 CC addCronTask 后，createdAt/scope/sessionId 必须落
 * DB 列，而非仅存内存 sessionJobs —— 重启后 selectAll 可按 scope=SESSION 辨识（R-1 僵尸修复）。
 *
 * <p><b>WHY (意图验证)</b>: CC cronTasks.ts:208 创建必写 createdAt=Date.now()，
 * durable=false 语义 = session-scoped（cronTasks.ts:211-213）。Java 侧用户拍板维持
 * SQLite 只补字段：SESSION 任务仍落库 + Quartz 注册，但 scope/session_id 列必须写入，
 * 否则重启后 sessionJobs 内存索引清空，DB 行无法辨识属于哪个 session → 僵尸（R-1）。
 * 本测试断言：create 后直接读 DB（绕过 sessionJobs 反查）字段非 null，
 * 并模拟重启（清空 sessionJobs）后 listAll 仍能由 DB 列辨识 SESSION 任务。
 *
 * <p>用 MybatisFlexBootstrap（无 Spring）直连临时 SQLite + Flyway V1..V8，
 * 不启动完整应用上下文（避免 Quartz / WebSocket / MCP 等耗时依赖）。
 *
 * <p>注意：MybatisFlexBootstrap 是单例，本测试须独立运行
 * （{@code mvn -Dtest=ScheduleServiceCreateStorageTest test}），不与其它
 * 使用 Flex 的测试类混跑。
 */
class ScheduleServiceCreateStorageTest {

    @TempDir
    static Path tempDir;

    private static ScheduleMapper mapper;
    private static ScheduleService service;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        // 共享稳定 DB + 重置 MyBatis-Flex 全局状态（mapper 代理缓存/单例），避免跨测试类冲突（见 MybatisFlexDbTestSupport）。
        java.nio.file.Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        // Flyway 迁移 V1..V20（V20 表重建去 schedules.name UNIQUE，同 name 双插行为见
        // sameNameCreateTwice_bothPersist；V7 created_at/permanent、V8 scope/session_id、
        // V9 agent_id 列均在重建后的新表保留）
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

    private ScheduleDto createSessionJob(String name, String sessionId) {
        return createSessionJob(name, sessionId, null);
    }

    private ScheduleDto createSessionJob(String name, String sessionId, String agentId) {
        return service.create(new ScheduleCreateRequest(
            name, ScheduleKind.cron, "0 9 * * *", null, null,
            "echo " + name, "b2 desc", ScheduleScope.SESSION, sessionId, agentId, null, null));
    }

    private ScheduleDto createPersistentJob(String name, String boundProject, String sessionId) {
        return service.create(new ScheduleCreateRequest(
            name, ScheduleKind.cron, "0 9 * * *", null, null,
            "echo " + name, "q2 desc", ScheduleScope.DURABLE, sessionId, null, boundProject, null));
    }

    @Test
    @DisplayName("create(SESSION) 后 DB 行 createdAt/scope/sessionId 非 null（落库而非仅内存）")
    void createPersistsCreatedAtAndScope() {
        createSessionJob("b2-persist", "sess-1");

        // 直接读 DB（不经过 sessionJobs 反查）→ 证明列落库
        List<ScheduleRecord> rows = mapper.selectAll();
        assertThat(rows).hasSize(1);
        ScheduleRecord r = rows.get(0);
        assertThat(r.getCreatedAt()).as("createdAt 必须落库（CC cronTasks.ts:208 必写 Date.now()）")
            .isNotNull();
        assertThat(r.getScope()).as("scope 必须落库为 SESSION（CC durable=false 语义）")
            .isEqualTo(ScheduleScope.SESSION.name());
        assertThat(r.getSessionId()).as("sessionId 必须落库（重启后可辨识归属）")
            .isEqualTo("sess-1");
        assertThat(r.getPermanent()).as("permanent 默认 false（CC cronTasks.ts:57 工具不可设）")
            .isFalse();
    }

    @Test
    @DisplayName("create(SESSION + agentId) → DB agent_id 非 null + dto.agentId() == 传入值（D4 链路落库）")
    void createPersistsAgentIdToDbAndDto() {
        String agentId = "agent-d4-test-001";
        ScheduleDto created = createSessionJob("b2-agent", "sess-3", agentId);

        // create() 返回的 toDto 已透传 agentId（CC CronTask.agentId 由 create 填充）
        assertThat(created.agentId()).as("create 返回 dto.agentId() 必须等于传入 agentId（CC cronTasks.ts:69）")
            .isEqualTo(agentId);

        // 直接读 DB（绕过 sessionJobs 反查）→ agent_id 列已落库（V9 迁移）
        List<ScheduleRecord> rows = mapper.selectAll();
        assertThat(rows).hasSize(1);
        ScheduleRecord r = rows.get(0);
        assertThat(r.getAgentId()).as("DB agent_id 列必须非 null（V9 迁移 + create 填充）")
            .isEqualTo(agentId);
    }

    @Test
    @DisplayName("批次X Q2: create(DURABLE + boundProject) → DB bound_project 列非 null + dto.boundProject() 回填（V23 迁移生效）")
    void createPersistsBoundProjectToDbAndDto() {
        // WHY（规则九）：CC durable 任务的项目锚=文件位置 <projectRoot>/.claude/scheduled_tasks.json
        // （cronTasks.ts:74-83），一个项目一个文件=项目级作用域；Java 全局单表无法从存储位置推断
        // 项目锚，必须每任务一列显式存（V23 bound_project 列），fire 时（CronIdleExecutor）据此
        // 恢复项目上下文。若列未落库 → 重启后 selectAll 读不到锚 → DURABLE fire 回落 user.dir
        // （跨项目 cwd 错位）。
        String boundProject = "C:/proj/durable-cron-a";
        String creatingSessionId = "00000000-0000-0000-0000-aaaaaaa10000";  // 派生 UUID（工具路径形态）
        ScheduleDto created = createPersistentJob("q2-persist", boundProject, creatingSessionId);

        // create() 返回的 toDto 已透传 boundProject（CC durable 文件位置锚的 Java 列锚等价）
        assertThat(created.boundProject())
            .as("create 返回 dto.boundProject() 必须等于传入值（批次X Q2 列锚透传）")
            .isEqualTo(boundProject);

        // 直接读 DB（绕过 sessionJobs 反查）→ bound_project 列已落库（V23 迁移）
        List<ScheduleRecord> rows = mapper.selectAll();
        assertThat(rows).hasSize(1);
        ScheduleRecord r = rows.get(0);
        assertThat(r.getBoundProject()).as("DB bound_project 列必须非 null（V23 迁移 + create 填充）")
            .isEqualTo(boundProject);
        assertThat(r.getSessionId())
            .as("[cron-durable-session-fire] DURABLE 存创建会话 sessionId（归属对话/注入目标，"
                + "fire 存活时 transcript 归创建会话）——非 SESSION 生命周期绑定，cleanupBySession 按 scope 过滤不误删")
            .isEqualTo(creatingSessionId);
    }

    @Test
    @DisplayName("[cron-durable-session-fire] cleanupBySession 只删 SESSION-scope 行，DURABLE（带同 sessionId）不误删")
    void cleanupBySession_doesNotDeleteDurableRows_withSameSessionId() {
        // WHY（规则九）: DURABLE 现在也存创建会话 sessionId（归属对话/注入目标，非 SESSION 生命周期
        // 绑定）。若 cleanupBySession 按 session_id 无 scope 过滤，会误删跨会话持久化的 DURABLE 任务。
        // cleanupBySession 以 scope=SESSION && session_id=? 为权威（ScheduleService:941）→ DURABLE
        // 行带同 sessionId 必须存活（RED: 移除 scope 过滤 → 本测试 DURABLE 行消失 → 变红）。
        String sessionId = "sess-cleanup-dup";
        createPersistentJob("q2-cleanup-durable", "C:/proj/durable-cleanup", sessionId);
        createSessionJob("b2-cleanup-session", sessionId);

        int deleted = service.cleanupBySession(sessionId);

        assertThat(deleted).as("cleanupBySession 只删 SESSION-scope 行（1 条）").isEqualTo(1);
        List<ScheduleRecord> rows = mapper.selectAll();
        assertThat(rows).extracting(ScheduleRecord::getName)
            .as("DURABLE 行带同 sessionId 不误删（scope=SESSION 过滤保护）")
            .containsExactly("q2-cleanup-durable");
    }

    @Test
    @DisplayName("批次X Q2: create(SESSION + boundProject) → bound_project 列留 null（SESSION 项目锚走 sessionId 恢复路径）")
    void createSessionDoesNotPersistBoundProject() {
        // WHY（规则九）：SESSION 任务的项目锚由 sessionId 恢复路径承载（CronIdleExecutor SESSION 分支
        // RequestContext.setSession → CwdResolution 命中 boundProject 层），不写 bound_project 列
        // （两路径清晰分离，B 探查 §7.3）。即便请求透传 boundProject（防御性断言），列必须为 null。
        ScheduleDto created = createSessionJob("q2-session", "sess-q2-001", null);
        assertThat(created.boundProject())
            .as("SESSION 任务 dto.boundProject() 恒 null（项目锚走 sessionId 恢复路径）")
            .isNull();

        List<ScheduleRecord> rows = mapper.selectAll();
        assertThat(rows).hasSize(1);
        ScheduleRecord r = rows.get(0);
        assertThat(r.getBoundProject()).as("SESSION 任务 DB bound_project 列必须为 null（两路径分离）")
            .isNull();
    }

    @Test
    @DisplayName("批次X Q2: 模拟重启（清空 sessionJobs 内存索引）后 listAll 仍由 DB bound_project 列回填（V23 列重启存活）")
    void listAllReadsBoundProjectFromDbAfterRestart() {
        // WHY（规则九）：DURABLE 任务的项目锚必须持久化在 DB 列（非进程内存），重启后
        // listAll → toDto 由 DB 列回填，CronIdleExecutor fire 才拿得到锚（否则重启后 DURABLE
        // fire 回落 user.dir，项目 A 的 durable cron 跑在 JVM 启动目录）。
        String boundProject = "C:/proj/durable-cron-b";
        createPersistentJob("q2-restart", boundProject, "00000000-0000-0000-0000-aaaaaab20000");

        // 模拟重启：sessionJobs 是进程内存索引，重启即清空
        @SuppressWarnings("unchecked")
        Map<String, ?> jobs = (Map<String, ?>) ReflectionTestUtils.getField(service, "sessionJobs");
        jobs.clear();

        List<ScheduleDto> all = service.listAll();
        assertThat(all).hasSize(1);
        ScheduleDto d = all.get(0);
        assertThat(d.boundProject()).as("重启后 listAll 必须由 DB bound_project 列回填（批次X Q2）")
            .isEqualTo(boundProject);
    }

    @Test
    @DisplayName("MAX_JOBS 上限：拒绝消息 == CC 精确文本且不含源码行号泄漏（REST 直达用户，CRON-F6）")
    void createRejectsBeyondMaxJobsWithCcExactMessage() {
        // 填满 MAX_JOBS 配额（直插 DB 行，绕过 create 的 Quartz 注册与文件锁）
        for (int i = 0; i < ScheduleService.MAX_JOBS; i++) {
            ScheduleRecord r = new ScheduleRecord();
            r.setId("max-fill-" + i);
            r.setName("max-fill-" + i);
            r.setKind(ScheduleKind.cron.name());
            r.setCron("0 9 * * *");
            r.setCommand("echo " + i);
            r.setCreatedAt(System.currentTimeMillis());
            r.setScope(ScheduleScope.DURABLE.name());
            r.setPermanent(Boolean.FALSE);
            mapper.insert(r);
        }
        assertThat(mapper.selectAll()).hasSize(ScheduleService.MAX_JOBS);

        // WHY (意图·规则 9)：CC CronCreateTool.ts:101 errorCode3 消息
        // "Too many scheduled jobs (max 50). Cancel one first." 经 ScheduleController
        // REST 直达用户，Java 侧必须逐字一致，且不得泄漏内部 CC 行号
        // （旧实现 "Schedule job limit reached ... . CC CronCreateTool.ts:25" 已移除）。
        ScheduleCreateRequest req = new ScheduleCreateRequest(
            "over-limit", ScheduleKind.cron, "0 9 * * *", null, null,
            "echo over", "over desc", ScheduleScope.DURABLE, null, null, null, null);
        Throwable ex = Assertions.catchThrowable(() -> service.create(req));
        assertThat(ex)
            .as("MAX_JOBS 拒绝必须抛 MaxJobsExceededException（ConflictException 子类 → 409 + errorCode3，CRON-B4-3 决策 #13）")
            .isInstanceOf(MaxJobsExceededException.class);
        assertThat(((MaxJobsExceededException) ex).errorCode())
            .as("errorCode 必须为 \"3\"（对齐 CC CronCreateTool.ts:101 三元错误码）")
            .isEqualTo("3");
        assertThat(ex.getMessage())
            .as("MAX_JOBS 拒绝消息必须逐字对齐 CC CronCreateTool.ts:101 errorCode3")
            .isEqualTo("Too many scheduled jobs (max " + ScheduleService.MAX_JOBS + "). Cancel one first.");
        assertThat(ex.getMessage())
            .as("拒绝消息不得泄漏内部 CC 源码行号（旧 'CC CronCreateTool.ts:25' 已移除）")
            .doesNotContain("CronCreateTool.ts", "cronTasks.ts", "cronScheduler.ts");
    }

    @Test
    @DisplayName("模拟重启（清空 sessionJobs 内存索引）后 listAll 仍按 DB 列辨识 SESSION 任务（R-1 僵尸修复）+ agentId 由 DB 列回填")
    void listAllReadsScopeFromDbAfterRestart() {
        String agentId = "agent-d4-restart-001";
        createSessionJob("b2-restart", "sess-2", agentId);

        // 模拟重启：sessionJobs 是进程内存索引，重启即清空
        @SuppressWarnings("unchecked")
        Map<String, ?> jobs = (Map<String, ?>) ReflectionTestUtils.getField(service, "sessionJobs");
        jobs.clear();

        List<ScheduleDto> all = service.listAll();
        assertThat(all).hasSize(1);
        ScheduleDto d = all.get(0);
        assertThat(d.scope()).as("重启后 listAll 必须由 DB scope 列辨识 SESSION（R-1 僵尸修复）")
            .isEqualTo(ScheduleScope.SESSION);
        assertThat(d.sessionId()).as("重启后 listAll 必须由 DB session_id 列回填归属")
            .isEqualTo("sess-2");
        assertThat(d.agentId()).as("重启后 listAll 必须由 DB agent_id 列回填（CC CronTask.agentId 持久化）")
            .isEqualTo(agentId);
    }

    /** 直插 DB 行（绕过 create 的 Quartz 注册），供 nextAvailableName 占用集构造。 */
    private void insertRow(String name, ScheduleKind kind) {
        ScheduleRecord r = new ScheduleRecord();
        r.setId("row-" + UUID.randomUUID());
        r.setName(name);
        r.setKind(kind.name());
        r.setCron("0 9 * * *");
        r.setCommand("echo " + name);
        r.setCreatedAt(System.currentTimeMillis());
        r.setScope(ScheduleScope.DURABLE.name());
        r.setPermanent(Boolean.FALSE);
        mapper.insert(r);
    }

    @Test
    @DisplayName("IMPL-06: 同 name 连续两次 create → 两行均落库（id 互异、listAll size=2），REST 同名不再 500（V20 去 UNIQUE）")
    void sameNameCreateTwice_bothPersist() {
        // WHY (意图·NEW-5)：CC addCronTask 无 name/无去重（cronTasks.ts:194-219 按 id 追加，
        // 无任何 name 写入/去重检查）→ 同 cron 二次创建 CC 两条均 fire。V20 迁移以表重建
        // 去除 schedules.name UNIQUE（SQLite 无 DROP CONSTRAINT），同 name 双插必须成功
        // —— 本用例同时是 V20 在 fresh SQLite 生效的行为级验证（迁移可应用 + 约束已去）。
        ScheduleCreateRequest req = new ScheduleCreateRequest(
            "dup-name", ScheduleKind.cron, "0 9 * * *", null, null,
            "echo dup", "dup desc", ScheduleScope.DURABLE, null, null, null, null);

        ScheduleDto first = service.create(req);
        ScheduleDto second = service.create(req);

        assertThat(first.id()).as("同 name 双插必须生成不同 id（身份语义 = id，对齐 CC）")
            .isNotEqualTo(second.id());
        List<ScheduleDto> all = service.listAll();
        assertThat(all).as("同 name 两行必须都在列表中（旧 UNIQUE 撞约束 → 第二行 500，已随 V20 消除）")
            .hasSize(2);
        assertThat(all).extracting(ScheduleDto::name).containsExactly("dup-name", "dup-name");
    }

    @Test
    @DisplayName("IMPL-06: nextAvailableName 无占用返 base、占用返 -2/-3 递增、超长 base 截断后仍 ≤64")
    void nextAvailableName_suffixAndTruncation() {
        // 无占用 → 原样返回
        assertThat(service.nextAvailableName("free-name"))
            .as("未占用 base 必须原样返回（无后缀）")
            .isEqualTo("free-name");

        // 占用 → -2/-3 递增（NEW-5 拍板方向：递增后缀，展示稳定可读）
        insertRow("dup-name", ScheduleKind.cron);
        assertThat(service.nextAvailableName("dup-name"))
            .as("占用 → -2 后缀")
            .isEqualTo("dup-name-2");
        insertRow("dup-name-2", ScheduleKind.cron);
        assertThat(service.nextAvailableName("dup-name"))
            .as("二次占用 → -3 后缀（每次从 base 重算）")
            .isEqualTo("dup-name-3");

        // 超长 base 占用 → 截断 + 后缀，恒 ≤64（对齐 ScheduleCreateRequest @Size(max=64)）
        String longBase = "cron:" + "0 9 * * * ".repeat(8);
        assertThat(longBase.length()).isGreaterThan(64);
        insertRow(longBase, ScheduleKind.cron);
        String suffixed = service.nextAvailableName(longBase);
        assertThat(suffixed).as("超长 base 碰撞 → 必须带 -N 后缀")
            .endsWith("-2");
        assertThat(suffixed.length()).as("截断 + 后缀后必须 ≤64（@Size(max=64) 契约）")
            .isLessThanOrEqualTo(64);
        assertThat(suffixed).isEqualTo(longBase.substring(0, 62) + "-2");
    }
}
