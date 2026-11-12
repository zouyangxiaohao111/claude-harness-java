package com.nexusai.kb.app.ingest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.mapper.KbIngestTaskMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [V288] 入库任务失败重试<b>指数退避</b>意图测试。
 *
 * <p><b>WHY（这批必须有用例）</b>：源站 WeKnora 的重试由 asynq 的指数退避承载，
 * 本仓改造前 {@code markFailure} 直接把 {@code state} 改回 {@code queued}
 * ⇒ worker 1.5s 轮询下一轮立刻又捞起来，<b>3 次重试可以在 3 秒内全部打完</b>；
 * 对 429 / 上游超时这类「等一会儿才会好」的失败，重试上限被瞬间耗光。
 * 下面四条性质每一条写错了都<b>不会报错</b>，只会在线上表现为「重试无效」：
 * <ol>
 *   <li>失败后必须真的<b>认领不到</b>（否则退避只是写了个没人看的列）；</li>
 *   <li>延迟必须<b>随次数递增</b>（2 / 4 / 8 秒），且能按配置改；</li>
 *   <li>BUSY 回队必须<b>立即</b>可再认领、不消耗 retry_count（锁冲突等一等只会更糟）；</li>
 *   <li>迁移必须把旧行回填成过去时刻 / NULL 视为可认领（否则升级后存量积压全冻住）。</li>
 * </ol>
 *
 * <p>⛔ 不用 {@code @SpringBootTest}（会迁移用户真库）；用真 SQLite 测试库 + 真 Flyway。
 * 时间断言留了「秒精度截断」的余量：{@code KbTimeFormat} 只到秒，写入值是
 * {@code floor(失败时刻 + 延迟)}，所以下界比理论值早最多 1 秒。
 */
@DisplayName("[V288] 入库任务失败重试指数退避")
class KbIngestRetryBackoffTest {

    private static SQLiteDataSource ds;
    private static KbIngestTaskMapper taskMapper;

    /** 每个用例用全新队列实例（配置字段不跨用例泄漏）。 */
    private KbIngestTaskQueue queue;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, KbIngestTaskMapper.class);
        taskMapper = MybatisFlexBootstrap.getInstance().getMapper(KbIngestTaskMapper.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        exec("DELETE FROM kb_ingest_task");
        queue = new KbIngestTaskQueue(taskMapper);
    }

    // ── 用例 ①：失败 1 次 ⇒ 退避 base（默认 2s）期间认领不到，到点能拿到 ──

    @Test
    @DisplayName("① 失败 1 次后 available_at ≈ now+2s：期间 claimNext/claimNextOfTypes 都拿不到，到点能拿到")
    void firstFailureDelaysClaim() throws Exception {
        long id = queue.enqueue("k-backoff-1", KbIngestTaskRecord.TYPE_PARSE, 0, null);
        KbIngestTaskRecord claimed = queue.claimNext();
        assertThat(claimed).isNotNull();
        assertThat(claimed.getId()).isEqualTo(id);

        Logger queueLog = (Logger) LoggerFactory.getLogger(KbIngestTaskQueue.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        queueLog.addAppender(appender);

        LocalDateTime before;
        LocalDateTime after;
        try {
            before = LocalDateTime.now();
            assertThat(queue.markFailure(claimed, "429 Too Many Requests")).isTrue();
            after = LocalDateTime.now();
        } finally {
            queueLog.detachAppender(appender);
        }

        // 5) 日志 info 留痕：「第 n 次失败，X 秒后可重试」
        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(m -> m.contains("第 1 次失败，2 秒后可重试"));

        KbIngestTaskRecord row = taskMapper.selectOneById(id);
        assertThat(row.getState()).isEqualTo(KbIngestTaskRecord.STATE_QUEUED);
        assertThat(row.getRetryCount()).isEqualTo(1);
        LocalDateTime availableAt = KbTimeFormat.parse(row.getAvailableAt());
        assertThat(availableAt).isNotNull();
        // 秒精度截断：写入值 ∈ [before + 2s - 1s, after + 2s]
        assertThat(availableAt)
            .isBetween(before.plusSeconds(2).minusSeconds(1), after.plusSeconds(2).plusSeconds(1));

        // 退避期内认领不到（两条认领入口都要覆盖）
        assertThat(queue.claimNext()).isNull();
        assertThat(queue.claimNextOfTypes(List.of(KbIngestTaskRecord.TYPE_PARSE))).isNull();

        // 等够退避时长后能拿到（类型认领入口）
        KbIngestTaskRecord reclaimed = awaitClaimByType(6000);
        assertThat(reclaimed).isNotNull();
        assertThat(reclaimed.getId()).isEqualTo(id);
    }

    // ── 用例 ②：延迟随次数递增（2 / 4 / 8 秒），到 max 封顶，可配 ──

    @Test
    @DisplayName("② 第 1/2/3 次失败延迟 = 2/4/8 秒（到 max 封顶），且 retry-base-ms/retry-max-ms 可配")
    void delaysGrowWithAttempts() throws Exception {
        // 2a) 默认策略：2 / 4 / 8 秒，n 很大时封顶 max
        assertThat(queue.retryDelayMs(1)).isEqualTo(2000L);
        assertThat(queue.retryDelayMs(2)).isEqualTo(4000L);
        assertThat(queue.retryDelayMs(3)).isEqualTo(8000L);
        assertThat(queue.retryDelayMs(20)).isEqualTo(KbIngestTaskQueue.DEFAULT_RETRY_MAX_MS);

        // 2b) 可配：base=500、max=1500 ⇒ 500 / 1000 / 1500（第三次被 max 封顶）
        KbIngestTaskQueue configured = new KbIngestTaskQueue(taskMapper);
        configured.setRetryBaseMs(500L);
        configured.setRetryMaxMs(1500L);
        assertThat(configured.retryDelayMs(1)).isEqualTo(500L);
        assertThat(configured.retryDelayMs(2)).isEqualTo(1000L);
        assertThat(configured.retryDelayMs(3)).isEqualTo(1500L);

        // 2c) 真库写入路径：连续 3 次失败，available_at 各自落在 2/4/8 秒后
        long id = queue.enqueue("k-backoff-2", KbIngestTaskRecord.TYPE_PARSE, 0, null);
        // 默认 max_retry=3 ⇒ 第 3 次失败就转 dead，看不到第 3 段退避 ⇒ 本用例放开到 5
        exec("UPDATE kb_ingest_task SET max_retry = 5 WHERE id = " + id);
        long[] expectedDelaysMs = {2000L, 4000L, 8000L};
        for (int attempt = 1; attempt <= 3; attempt++) {
            KbIngestTaskRecord task = queue.claimNext();
            assertThat(task).as("第 %d 次认领", attempt).isNotNull();
            assertThat(task.getRetryCount()).isEqualTo(attempt - 1);

            LocalDateTime before = LocalDateTime.now();
            assertThat(queue.markFailure(task, "第 " + attempt + " 次上游失败")).isTrue();
            LocalDateTime after = LocalDateTime.now();

            KbIngestTaskRecord row = taskMapper.selectOneById(id);
            assertThat(row.getRetryCount()).isEqualTo(attempt);
            LocalDateTime availableAt = KbTimeFormat.parse(row.getAvailableAt());
            long expectedSeconds = expectedDelaysMs[attempt - 1] / 1000L;
            assertThat(availableAt)
                .as("第 %d 次失败后的可认领时刻", attempt)
                .isBetween(
                    before.plusSeconds(expectedSeconds).minusSeconds(1),
                    after.plusSeconds(expectedSeconds).plusSeconds(1));

            // 退避期内不可认领
            assertThat(queue.claimNext()).as("第 %d 次失败后仍不可认领", attempt).isNull();

            // 模拟「时间已到」（不真等 8 秒）：把 available_at 拨回过去
            exec("UPDATE kb_ingest_task SET available_at = datetime('now', '-1 hour') WHERE id = " + id);
        }
    }

    // ── 用例 ③：BUSY 回队不退避 ──

    @Test
    @DisplayName("③ BUSY requeue 立即回队：available_at=now、retry_count 不动、立刻能再认领")
    void busyRequeueIsImmediate() throws Exception {
        long id = queue.enqueue("k-backoff-busy", KbIngestTaskRecord.TYPE_PARSE, 0, null);
        KbIngestTaskRecord task = queue.claimNext();
        assertThat(task).isNotNull();

        assertThat(queue.requeue(task, "SQLITE_BUSY_SNAPSHOT: database is locked")).isTrue();

        KbIngestTaskRecord row = taskMapper.selectOneById(id);
        assertThat(row.getState()).isEqualTo(KbIngestTaskRecord.STATE_QUEUED);
        // ⛔ BUSY 不吃重试次数
        assertThat(row.getRetryCount()).isZero();
        assertThat(row.getAvailableAt()).isNotNull();
        assertThat(KbTimeFormat.parse(row.getAvailableAt()))
            .isBeforeOrEqualTo(LocalDateTime.now());

        // 立即可再认领（同一条，不是新的）
        KbIngestTaskRecord again = queue.claimNext();
        assertThat(again).isNotNull();
        assertThat(again.getId()).isEqualTo(id);
        assertThat(again.getState()).isEqualTo(KbIngestTaskRecord.STATE_RUNNING);
    }

    // ── 用例 ④a：迁移回填 ──

    @Test
    @DisplayName("④a 迁移回填：V288 之前已存在的 queued 行 available_at = created_at（立即可认领）")
    void migrationBackfillsLegacyRows() throws Exception {
        Path legacyDb = Path.of("target", "flex-dbtest",
            "legacy-backfill-" + UUID.randomUUID() + ".db");
        Files.createDirectories(legacyDb.getParent());
        SQLiteDataSource legacyDs = new SQLiteDataSource();
        legacyDs.setUrl("jdbc:sqlite:" + legacyDb.toAbsolutePath());
        try {
            // 1) 只迁到 V287（= V288 之前的世界）
            Flyway.configure()
                .dataSource(legacyDs)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("287"))
                .baselineOnMigrate(true)
                .load()
                .migrate();

            // 2) 造一条「旧行」（当时还没有 available_at 列）
            try (Connection c = legacyDs.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO kb_ingest_task "
                    + "(knowledge_id, task_type, state, attempt, created_at) "
                    + "VALUES ('legacy-row', 'parse', 'queued', 0, '2020-01-02 03:04:05')");
            }

            // 3) 再迁到最新 ⇒ V288 加列 + 回填
            Flyway.configure()
                .dataSource(legacyDs)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();

            try (Connection c = legacyDs.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT available_at FROM kb_ingest_task WHERE knowledge_id = 'legacy-row'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("2020-01-02 03:04:05");
            }
        } finally {
            Files.deleteIfExists(legacyDb);
        }
    }

    // ── 用例 ④b：NULL 视为立即可认领 ──

    @Test
    @DisplayName("④b available_at 为 NULL 的行立即可认领（认领 SQL 显式放行 NULL）")
    void nullAvailableAtIsClaimable() throws Exception {
        exec("INSERT INTO kb_ingest_task "
            + "(knowledge_id, task_type, state, attempt, created_at, available_at) "
            + "VALUES ('legacy-null', 'parse', 'queued', 0, '2020-01-02 03:04:05', NULL)");

        KbIngestTaskRecord claimed = queue.claimNext();
        assertThat(claimed).isNotNull();
        assertThat(claimed.getKnowledgeId()).isEqualTo("legacy-null");
    }

    // ── helpers ──

    private KbIngestTaskRecord awaitClaimByType(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            KbIngestTaskRecord claimed =
                queue.claimNextOfTypes(List.of(KbIngestTaskRecord.TYPE_PARSE));
            if (claimed != null) {
                return claimed;
            }
            Thread.sleep(100L);
        }
        return null;
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
