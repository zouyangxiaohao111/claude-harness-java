package com.nexusai.domain.schedule;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.tool.config.CronJitterProperties;
import com.nexusai.application.agent.tool.cron.CronJitter;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRON-B2-2 聚焦测试：REST 直建 one-shot（kind=once, req.id()==null）统一应用 one-shot jitter
 * （决策 #2 / OPD-Cron-F1-b）。
 *
 * <p><b>WHY (意图验证 · CLAUDE.md 规则九)</b>: 工具路径（CronCreateTool:368）已对 one-shot 施加
 * one-shot jitter，但 REST 直建 once 在 ScheduleService.create:129 直接 {@code s.setRunAt(req.runAt())}
 * 落精确墙钟 —— 多用户 REST 排同一整点 runAt（如 15:30）会在 :30 同时打爆推理（thundering herd，
 * 与 CC 决策 oneShotJitteredNextCronRunMs cronTasks.ts:421-445 的动机相同）。本测试锁定：REST 直建
 * once 的落库 runAt 必须经 {@link CronJitter#jitterOneShotFireTime} 施抖（整点 mark 提前 lead），
 * 非整点不抖，且工具路径（req.id()!=null，已 jitter）不得双抖。
 *
 * <p>用 MybatisFlexBootstrap（无 Spring）直连临时 SQLite + Flyway V1..V9，不启动完整应用上下文
 * （避免 Quartz / WebSocket / MCP 等耗时依赖）。jitterProps 刻意不注入（null）→ 走
 * {@code CronJitterProperties.DEFAULTS} fail-open 分支（对齐 QuartzScheduleService:183-185 模式）。
 *
 * <p>注意：MybatisFlexBootstrap 是单例，本测试须独立运行
 * （{@code mvn -Dtest=ScheduleServiceOnceJitterTest test}），不与其它使用 Flex 的测试类混跑。
 */
class ScheduleServiceOnceJitterTest {

    @TempDir
    static Path tempDir;

    private static ScheduleMapper mapper;
    private static ScheduleService service;

    @BeforeAll
    static void setUpDatabase() {
        String dbUrl = "jdbc:sqlite:" + tempDir.resolve("cron-b2-2.db");
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

        service = new ScheduleService();
        ReflectionTestUtils.setField(service, "scheduleMapper", mapper);
        ReflectionTestUtils.setField(service, "quartzScheduleService",
            Mockito.mock(QuartzScheduleService.class));
        // jitterProps 刻意不注入：null → DEFAULTS fail-open（非 Spring 单测命中该守卫）
    }

    @BeforeEach
    void cleanSchedules() {
        List<ScheduleRecord> rows = mapper.selectAll();
        if (!rows.isEmpty()) {
            mapper.deleteBatchByIds(rows.stream().map(ScheduleRecord::getId).toList());
        }
    }

    /**
     * 以系统默认时区构造未来某日 :mm 的 runAt（ISO 8601 with offset）· 与实现同用
     * ZoneId.systemDefault，避免 TZ 敏感（同 CronJitterTest#localMs）。返回时刻选在未来
     * （≥1 天）保证 pinned - lead >> now，max(t1-lead, now) 不咬钳 → 期望值确定性可算。
     */
    private static String futureRunAt(int minute) {
        ZonedDateTime pinned = ZonedDateTime.now()
            .plusDays(1)
            .withHour(15)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0);
        return pinned.toOffsetDateTime().toString();
    }

    private ScheduleDto createOnce(String name, String runAt, String id) {
        return service.create(new ScheduleCreateRequest(
            name, ScheduleKind.once, null, null, runAt,
            "echo " + name, "b2-2 desc", ScheduleScope.DURABLE, null, null, null, id));
    }

    @Test
    @DisplayName("REST once runAt 落整点(:30) → 落库 runAt = jitterOneShotFireTime(pinned,now,taskId,DEFAULTS)（决策 #2 施抖）")
    void restOnce_onHotMark_jittersRunAt() {
        String runAt = futureRunAt(30); // 本地 :30，%30==0 → hot mark
        Instant pinned = OffsetDateTime.parse(runAt).toInstant();

        // 当前代码（基线 RED）：:129 直接 s.setRunAt(req.runAt())，runAt 原样落库
        ScheduleDto dto = createOnce("b2-2-hot", runAt, null);
        ScheduleRecord stored = mapper.selectOneById(dto.id());

        // 落库 id = 服务端 generateId（REST req.id()==null）→ taskId = id 后 8 hex
        String taskId = stored.getId().substring(stored.getId().length() - 8);
        long expectedMs = CronJitter.jitterOneShotFireTime(
            pinned.toEpochMilli(),
            System.currentTimeMillis(),
            taskId,
            CronJitterProperties.DEFAULTS.toConfig());
        Instant expected = Instant.ofEpochMilli(expectedMs);

        Instant storedInstant = OffsetDateTime.parse(stored.getRunAt()).toInstant();
        assertThat(storedInstant)
            .as("REST once 整点 mark 落库 runAt 必须等于 jitterOneShotFireTime 施抖结果（决策 #2）")
            .isEqualTo(expected);

        // lead = ceil(frac*90000)；frac>0 → 必然提前；frac=0（非 hex/零散列）→ 无抖动（CC 语义）
        double frac = CronJitter.jitterFrac(taskId);
        long lead = (long) Math.ceil(frac * CronJitter.CronJitterConfig.DEFAULT.oneShotMaxMs());
        if (lead > 0) {
            assertThat(storedInstant)
                .as("整点 mark + lead>0 → 落库 runAt 必须早于钉死时刻（提前 lead，摊开 :30 推理尖峰）")
                .isBefore(pinned);
        } else {
            assertThat(storedInstant).isEqualTo(pinned);
        }
        // 提前量上界：lead ≤ oneShotMaxMs（90s），且不早于钳制点 now
        assertThat(storedInstant)
            .isAfterOrEqualTo(pinned.minusMillis(
                CronJitter.CronJitterConfig.DEFAULT.oneShotMaxMs()));
    }

    @Test
    @DisplayName("REST once runAt 落非整点(:33，%30≠0) → 落库 runAt == pinned（不抖，CC cronTasks.ts:435）")
    void restOnce_offHotMark_keepsPinnedRunAt() {
        String runAt = futureRunAt(33); // 本地 :33，%30=3≠0 → 非 hot mark
        Instant pinned = OffsetDateTime.parse(runAt).toInstant();

        ScheduleDto dto = createOnce("b2-2-off", runAt, null);
        ScheduleRecord stored = mapper.selectOneById(dto.id());

        assertThat(OffsetDateTime.parse(stored.getRunAt()).toInstant())
            .as("非整点分钟（%oneShotMinuteMod≠0）不得抖动，落库 runAt 必须等于钉死时刻（cronTasks.ts:435）")
            .isEqualTo(pinned);
    }

    @Test
    @DisplayName("工具路径（req.id()!=null，CronCreateTool 已 jitter）→ 落库 runAt 原样，不双 jitter（决策 #2 判别符）")
    void toolPath_withPregeneratedId_skipsDoubleJitter() {
        String runAt = futureRunAt(30); // 整点 mark；若双 jitter 会二次提前
        Instant pinned = OffsetDateTime.parse(runAt).toInstant();

        // req.id()!=null = CronCreateTool:366-369 预生成 id + 已 jitter 后的 runAt → 服务端必须原样落库
        ScheduleDto dto = createOnce("b2-2-tool", runAt, "sch-12345678");
        ScheduleRecord stored = mapper.selectOneById(dto.id());

        assertThat(stored.getId()).isEqualTo("sch-12345678");
        assertThat(OffsetDateTime.parse(stored.getRunAt()).toInstant())
            .as("工具路径已 jitter（req.id()!=null），不得二次施抖（否则 runAt 双提前）")
            .isEqualTo(pinned);
    }
}
