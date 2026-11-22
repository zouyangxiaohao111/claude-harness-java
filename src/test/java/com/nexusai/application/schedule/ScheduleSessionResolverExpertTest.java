package com.nexusai.application.schedule;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.domain.project.ProjectService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.project.dto.ProjectDto;
import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.model.schedule.dto.SessionMode;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
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
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * V317 · 「专家」（主线程 agent）落到**新建会话行**的落地验证。
 *
 * <p><b>WHY（规则九：测意图）</b>：读侧只认 {@code sessions.main_thread_agent}
 * （{@code LlmAgentLoop.buildEffectivePromptOptions} → {@code mainThreadAgentDefinition}
 * → 替换系统提示词）⇒ 任务上存了专家但**没写进会话** = 功能等于没做（用户选了专家，
 * 跑起来还是默认人格）。所以这里断言的是**真实 DB 列**，不是「调用过某个方法」。
 *
 * <p>⛔ 不启 Spring（{@code @SpringBootTest} 会迁移用户真库）。
 * 会话行由测试自己插入（等价 {@code SessionService.create} 的产物），
 * {@code SessionService}/{@code ProjectService} 用 mock（只提供 id）。
 */
class ScheduleSessionResolverExpertTest {

    private static final String SESSION_ID = "sess-auto-expert-1";

    private static SessionMapper sessionMapper;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, SessionMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
    }

    @BeforeEach
    void cleanSessions(@TempDir Path tempHome) {
        // B/C 形态的新会话项目锚 = cron 目录 ⇒ 指向临时目录（防污染真实 ~/.nexusai）
        NexusaiPaths.setConfigHomeDirOverride(tempHome.toAbsolutePath().toString());
        List<SessionRecord> rows = sessionMapper.selectAll();
        if (!rows.isEmpty()) {
            sessionMapper.deleteBatchByIds(rows.stream().map(SessionRecord::getId).toList());
        }
    }

    @AfterEach
    void restoreConfigHome() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    /** 插一行会话（等价 {@code SessionService.create} 的产物：NOT NULL 列齐）。 */
    private void insertSessionRow() {
        String now = OffsetDateTime.now().toString();
        SessionRecord row = new SessionRecord();
        row.setId(SESSION_ID);
        row.setModelTag("DS");
        row.setModelName("sonnet");
        row.setTitle("cron · 测试任务");
        row.setTime(now);
        row.setSessionGroup("current");
        row.setMessageCount(0);
        // created_at / updated_at 有 DEFAULT，但 MyBatis-Flex 的 insert 会显式写 NULL ⇒ 必须给值
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        sessionMapper.insert(row);
    }

    /** 造一个解析器：SessionService/ProjectService 用 mock（只回 id），SessionMapper 用真 mapper。 */
    private ScheduleSessionResolver resolver() {
        SessionDto created = Mockito.mock(SessionDto.class);
        when(created.id()).thenReturn(SESSION_ID);
        SessionService sessionService = Mockito.mock(SessionService.class);
        when(sessionService.create(any())).thenReturn(created);

        ProjectDto project = Mockito.mock(ProjectDto.class);
        when(project.id()).thenReturn("proj-cron-1");
        ProjectService projectService = Mockito.mock(ProjectService.class);
        when(projectService.register(any())).thenReturn(project);

        ScheduleSessionResolver r = new ScheduleSessionResolver();
        ReflectionTestUtils.setField(r, "sessionService", sessionService);
        ReflectionTestUtils.setField(r, "projectService", projectService);
        ReflectionTestUtils.setField(r, "sessionMapper", sessionMapper);
        return r;
    }

    /** 造一条任务 DTO（B 形态 + 可选专家）。 */
    private static ScheduleDto dto(String expert) {
        return new ScheduleDto(
            "sch-expert", "测试任务", ScheduleKind.cron, "0 9 * * *", null, null,
            "echo hi", null, null, null,
            ScheduleScope.DURABLE, null, null, null,
            SessionMode.NEW_EACH, List.of(), List.of(),
            expert);
    }

    // ------------------------------------------------------------------
    // ② 指定专家 ⇒ 会话行写入
    // ------------------------------------------------------------------

    @Test
    @DisplayName("② NEW_EACH + 非空专家 ⇒ 新建的会话行 main_thread_agent 被写入（读侧据此换人格）")
    void newEach_withExpert_writesSessionColumn() {
        insertSessionRow();

        String sessionId = resolver().resolve(dto("wb-analyst"), "test");

        assertThat(sessionId).isEqualTo(SESSION_ID);
        SessionRecord row = sessionMapper.selectOneById(SESSION_ID);
        assertThat(row.getMainThreadAgent())
            .as("专家必须真的写进会话行（只存任务上 = 功能没做）")
            .isEqualTo("wb-analyst");
    }

    // ------------------------------------------------------------------
    // ③ 不指定 ⇒ 不写（保持 null）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("③ 不指定专家 ⇒ 会话行 main_thread_agent 保持 null（默认人格，零副作用）")
    void newEach_withoutExpert_leavesSessionColumnNull() {
        insertSessionRow();

        resolver().resolve(dto(null), "test");

        SessionRecord row = sessionMapper.selectOneById(SESSION_ID);
        assertThat(row.getMainThreadAgent())
            .as("不指定 ⇒ 不写这一列（保持 null = 默认人格）")
            .isNull();
    }

    @Test
    @DisplayName("③-b 空白串 ⇒ 同样不写（归一成「不指定」）")
    void newEach_withBlankExpert_leavesSessionColumnNull() {
        insertSessionRow();

        resolver().resolve(dto("   "), "test");

        assertThat(sessionMapper.selectOneById(SESSION_ID).getMainThreadAgent()).isNull();
    }
}
