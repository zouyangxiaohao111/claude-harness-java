package com.nexusai.domain.schedule;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.schedule.QuartzScheduleService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.schedule.dto.ScheduleCreateRequest;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.model.schedule.dto.ScheduleUpdateRequest;
import com.nexusai.model.schedule.dto.SessionMode;
import com.nexusai.repository.schedule.entity.ScheduleRecord;
import com.nexusai.repository.schedule.mapper.ScheduleMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * V317 · 定时任务指定「专家」（主线程 agent）的**服务层**契约。
 *
 * <p><b>WHY（规则九：测意图，不测行为）</b>：
 * <ul>
 *   <li><b>REUSE / NONE 传非空必须被拒</b>：用户选了专家、看到「保存成功」，跑起来却是默认人格 ——
 *       那比明确拒绝更坏（用户会以为设置生效了）。用户原话：「绑定现有的不行」。
 *       ⛔ 静默忽略 ⇒ 本用例红。</li>
 *   <li><b>NEW_EACH / NEW_ONCE 必须落库</b>：任务行不存，下次 fire 就不知道用哪个专家
 *       （专家最终写进 <b>新建会话行</b>，见 {@code ScheduleSessionResolverExpertTest}）。</li>
 *   <li><b>不指定 ⇒ 列留 NULL</b>：与 V317 之前的行为逐字节一致（存量行语义零变化）。</li>
 * </ul>
 *
 * <p>⛔ 不启 Spring（{@code @SpringBootTest} 会迁移用户真库）；DB 用
 * {@link MybatisFlexDbTestSupport} 的共享稳定 SQLite + Flyway 全量迁移（含 V317）。
 */
class ScheduleExpertMainThreadAgentTest {

    private static ScheduleMapper mapper;
    private static ScheduleService service;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        // 全量迁移（含 V317 schedules.main_thread_agent 列）
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
    void cleanSchedules(@TempDir Path tempHome) {
        // B/C 形态的项目锚 = cron 目录（真实 home 下的 ~/.nexusai/cron）⇒ 指向临时目录，
        // 防测试污染用户真目录（NexusaiPaths 是进程级 static，跑完必须复位）。
        NexusaiPaths.setConfigHomeDirOverride(tempHome.toAbsolutePath().toString());
        List<ScheduleRecord> rows = mapper.selectAll();
        if (!rows.isEmpty()) {
            mapper.deleteBatchByIds(rows.stream().map(ScheduleRecord::getId).toList());
        }
    }

    @AfterEach
    void restoreConfigHome() {
        // 复位（static 槽不得跨类残留）
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    /** 造一条创建请求（16 参 canonical）。 */
    private static ScheduleCreateRequest createReq(SessionMode mode, String expert, String sessionId) {
        return new ScheduleCreateRequest(
            "expert-task", ScheduleKind.cron, "0 9 * * *", null, null,
            "echo hi", "d", ScheduleScope.SESSION, sessionId, null, null,
            mode, null, null, expert, null);
    }

    // ------------------------------------------------------------------
    // ① REUSE / NONE + 非空 ⇒ 拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① REUSE（绑定已有会话）+ 非空专家 ⇒ ValidationException（不静默忽略）")
    void reuseMode_withExpert_isRejected() {
        Throwable ex = catchThrowable(() ->
            service.create(createReq(SessionMode.REUSE, "wb-analyst", "sess-1")));

        assertThat(ex)
            .as("REUSE 传非空专家必须被拒（用户原话「绑定现有的不行」）")
            .isInstanceOf(ValidationException.class);
        assertThat(ex.getMessage())
            .as("错误文案要能被人看懂：告诉用户该去会话里改自己的专家")
            .contains("专家")
            .contains("绑定已有会话");
        assertThat(mapper.selectAll())
            .as("被拒 ⇒ 不得落库（否则用户以为改了一半）")
            .isEmpty();
    }

    @Test
    @DisplayName("①-b NONE（不绑会话）+ 非空专家 ⇒ ValidationException（D 压根不产生会话，没地方放专家）")
    void noneMode_withExpert_isRejected() {
        Throwable ex = catchThrowable(() ->
            service.create(createReq(SessionMode.NONE, "wb-analyst", null)));

        assertThat(ex)
            .as("NONE 传非空专家必须被拒")
            .isInstanceOf(ValidationException.class);
        assertThat(mapper.selectAll()).isEmpty();
    }

    @Test
    @DisplayName("①-c update：REUSE 任务 + 非空专家 ⇒ ValidationException")
    void update_reuseMode_withExpert_isRejected() {
        ScheduleDto created = service.create(createReq(SessionMode.REUSE, null, "sess-1"));

        Throwable ex = catchThrowable(() -> service.update(created.id(), new ScheduleUpdateRequest(
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, "wb-analyst")));

        assertThat(ex)
            .as("update 也必须按形态拒绝（同一个能力不能两套判据）")
            .isInstanceOf(ValidationException.class);
        assertThat(mapper.selectOneById(created.id()).getMainThreadAgent())
            .as("被拒 ⇒ 原行的专家值不变")
            .isNull();
    }

    // ------------------------------------------------------------------
    // ② NEW_EACH / NEW_ONCE + 非空 ⇒ 落库
    // ------------------------------------------------------------------

    @Test
    @DisplayName("② NEW_EACH + 非空专家 ⇒ 落库（DB 列 + dto 都拿到）")
    void newEachMode_withExpert_persistsToDb() {
        ScheduleDto created = service.create(
            createReq(SessionMode.NEW_EACH, "wb-analyst", null));

        assertThat(created.mainThreadAgent())
            .as("create 返回的 dto 必须带上专家（前端回显靠它）")
            .isEqualTo("wb-analyst");

        ScheduleRecord row = mapper.selectOneById(created.id());
        assertThat(row.getMainThreadAgent())
            .as("schedules.main_thread_agent 必须落库（否则下次 fire 不知道用哪个专家）")
            .isEqualTo("wb-analyst");
    }

    @Test
    @DisplayName("②-b NEW_ONCE + 非空专家 ⇒ 落库")
    void newOnceMode_withExpert_persistsToDb() {
        ScheduleDto created = service.create(
            createReq(SessionMode.NEW_ONCE, "wb-analyst", null));

        assertThat(mapper.selectOneById(created.id()).getMainThreadAgent())
            .as("NEW_ONCE 同样能指定专家（它的会话也是任务自己造的）")
            .isEqualTo("wb-analyst");
    }

    @Test
    @DisplayName("②-c 专家值 trim 后落库（前后空格不算「指定了」）")
    void expert_isTrimmed() {
        ScheduleDto created = service.create(
            createReq(SessionMode.NEW_EACH, "  wb-analyst  ", null));

        assertThat(created.mainThreadAgent()).isEqualTo("wb-analyst");
    }

    // ------------------------------------------------------------------
    // ③ 空值 ⇒ 不写（保持 null）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("③ NEW_EACH + 空值 ⇒ DB 列保持 NULL（与 V317 之前行为逐字节一致）")
    void newEachMode_withoutExpert_leavesColumnNull() {
        ScheduleDto created = service.create(createReq(SessionMode.NEW_EACH, null, null));

        assertThat(created.mainThreadAgent()).isNull();
        assertThat(mapper.selectOneById(created.id()).getMainThreadAgent())
            .as("不指定 ⇒ 列必须留 NULL（存量行语义零变化）")
            .isNull();
    }

    @Test
    @DisplayName("③-b 空白串 ⇒ 归一成「不指定」（列留 NULL）")
    void blankExpert_isTreatedAsUnspecified() {
        ScheduleDto created = service.create(createReq(SessionMode.NEW_EACH, "   ", null));

        assertThat(created.mainThreadAgent()).isNull();
        assertThat(mapper.selectOneById(created.id()).getMainThreadAgent()).isNull();
    }

    // ------------------------------------------------------------------
    // 改形态 ⇒ 旧专家必须清掉（否则挂着一个永不生效的专家）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("形态从 NEW_EACH 改成 REUSE ⇒ 旧专家被清掉（REUSE 没有专家的载体）")
    void update_switchingToReuse_clearsExpert() {
        ScheduleDto created = service.create(
            createReq(SessionMode.NEW_EACH, "wb-analyst", null));

        service.update(created.id(), new ScheduleUpdateRequest(
            null, null, null, null, null, null, null, null, null, null,
            SessionMode.REUSE, null, null, null));

        ScheduleRecord row = mapper.selectOneById(created.id());
        assertThat(row.getMainThreadAgent())
            .as("REUSE 任务不能挂专家（永远不会被应用 ⇒ 用户会以为生效了）")
            .isNullOrEmpty();
    }

    @Test
    @DisplayName("update 传空串 ⇒ 清除专家（显式「不指定」）")
    void update_emptyString_clearsExpert() {
        ScheduleDto created = service.create(
            createReq(SessionMode.NEW_EACH, "wb-analyst", null));

        service.update(created.id(), new ScheduleUpdateRequest(
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, ""));

        assertThat(mapper.selectOneById(created.id()).getMainThreadAgent())
            .as("空串 = 清除")
            .isNullOrEmpty();
    }

    @Test
    @DisplayName("update 换专家 ⇒ DB 与 dto 都拿到新值")
    void update_changesExpert() {
        ScheduleDto created = service.create(
            createReq(SessionMode.NEW_EACH, "wb-analyst", null));

        ScheduleDto updated = service.update(created.id(), new ScheduleUpdateRequest(
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, "wb-writer"));

        assertThat(updated.mainThreadAgent()).isEqualTo("wb-writer");
        assertThat(mapper.selectOneById(created.id()).getMainThreadAgent()).isEqualTo("wb-writer");
    }
}
