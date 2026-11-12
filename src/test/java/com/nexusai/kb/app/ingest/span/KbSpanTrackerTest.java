package com.nexusai.kb.app.ingest.span;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.entity.KbProcessingSpanRecord;
import com.nexusai.kb.repo.mapper.KbProcessingSpanMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KbSpanTracker} · 真实 SQLite 临时库（Flyway 建表 + MyBatis-Flex 真跑）。
 *
 * <p><b>⛔ 不用 {@code @SpringBootTest}</b>：那会启动整个应用上下文并被 Flyway
 * 迁移到<b>用户的真库</b>。这里沿用 {@code KbAgentServiceTest} 的
 * {@link MybatisFlexDbTestSupport} 写法 —— 只建自己的表、只读写自己的数据。
 *
 * <p><b>WHY 这些断言</b>：trace 是旁路观测层，它的错都是「静默的」——
 * 写失败被吞掉、状态没流转、级联漏了，界面只会表现为「一直转圈」，
 * 没有任何异常可查。所以这里全部断言<b>落库后的真实列值</b>，不看返回值自洽。
 */
@DisplayName("[R1-A] kb_processing_span · KbSpanTracker")
class KbSpanTrackerTest {

    /** 被测文档 ID（每个用例前清空自己的行）。 */
    private static final String KID = "kb-doc-trace-1";

    /** root span 的固定名字（源站 OpenAttempt 写死 knowledge_processing）。 */
    private static final String ROOT_NAME = "knowledge_processing";

    private static KbProcessingSpanMapper mapper;
    private static KbSpanTracker tracker;
    private static Path dbPath;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, KbProcessingSpanMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(KbProcessingSpanMapper.class);
        tracker = new KbSpanTracker(mapper);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_processing_span");
        }
    }

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }

    /** 按 id ASC 读本文件的全部 span 行（与 tracker 的 ListByAttempt 同序）。 */
    private static List<KbProcessingSpanRecord> rows() {
        return mapper.selectListByQuery(QueryWrapper.create()
            .where("knowledge_id = ?", KID)
            .orderBy("id", true));
    }

    private static KbProcessingSpanRecord byName(String name) {
        return rows().stream()
            .filter(r -> name.equals(r.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("没有名为 " + name + " 的 span 行"));
    }

    // ── 1. 开 attempt ─────────────────────────────────────────────

    @Test
    @DisplayName("1. openAttempt 首次 ⇒ attempt=1，root 行 kind=root / status=running")
    void firstAttemptIsOne() {
        KbSpanTracker.AttemptRoot result = tracker.openAttempt(KID);

        assertThat(result.attempt()).isEqualTo(1);
        assertThat(result.root()).isNotNull();
        assertThat(result.root().spanId).isNotBlank();

        List<KbProcessingSpanRecord> stored = rows();
        assertThat(stored).hasSize(1);
        KbProcessingSpanRecord root = stored.get(0);
        assertThat(root.getAttempt()).isEqualTo(1);
        assertThat(root.getKind()).isEqualTo("root");
        assertThat(root.getStatus()).isEqualTo("running");
        assertThat(root.getName()).isEqualTo(ROOT_NAME);
        assertThat(root.getParentSpanId())
            .as("root 不能有父")
            .isNull();
        assertThat(root.getSpanId())
            .as("返回的句柄必须指向真正落库的那一行")
            .isEqualTo(result.root().spanId);
        assertThat(root.getStartedAt())
            .as("时间列必须是 yyyy-MM-dd HH:mm:ss（与 SQLite datetime('now') 同款）")
            .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("2. openAttempt 第二次 ⇒ attempt=2，且 attempt=1 的行原样保留")
    void secondAttemptIncrements() {
        tracker.openAttempt(KID);
        KbSpanTracker.AttemptRoot second = tracker.openAttempt(KID);

        assertThat(second.attempt()).isEqualTo(2);

        List<KbProcessingSpanRecord> stored = rows();
        assertThat(stored)
            .as("⭐ 旧 attempt 的行不能被覆盖（重跑要留下历史，供事后复盘）")
            .hasSize(2);
        assertThat(stored).extracting(KbProcessingSpanRecord::getAttempt)
            .containsExactly(1, 2);
        assertThat(stored.get(0).getStatus())
            .as("新 attempt 顶掉旧轮：旧轮残留的 running root 必须被置 cancelled")
            .isEqualTo("cancelled");
        assertThat(stored.get(0).getErrorCode()).isEqualTo("ATTEMPT_SUPERSEDED");
        assertThat(stored.get(1).getStatus()).isEqualTo("running");
    }

    // ── 3-4. stage 起止 ───────────────────────────────────────────

    @Test
    @DisplayName("3. beginStage 的 parent_span_id == root 的 span_id")
    void stageParentIsRoot() {
        KbSpanTracker.AttemptRoot attempt = tracker.openAttempt(KID);
        KbSpan stage = tracker.beginStage(KID, attempt.attempt(), "docreader", "{\"file\":\"a.pdf\"}");

        assertThat(stage).isNotNull();
        assertThat(stage.parentSpanId).isEqualTo(attempt.root().spanId);

        KbProcessingSpanRecord stored = byName("docreader");
        assertThat(stored.getKind()).isEqualTo("stage");
        assertThat(stored.getStatus()).isEqualTo("running");
        assertThat(stored.getParentSpanId())
            .as("⭐ 挂错父 ⇒ 前端树里这一段会跑到根节点外面")
            .isEqualTo(attempt.root().spanId);
        assertThat(stored.getInput()).isEqualTo("{\"file\":\"a.pdf\"}");
        assertThat(stored.getAttempt()).isEqualTo(attempt.attempt());
    }

    @Test
    @DisplayName("4. endSpan 填 finished_at 且 duration_ms >= 0")
    void endSpanClosesStage() {
        KbSpanTracker.AttemptRoot attempt = tracker.openAttempt(KID);
        KbSpan stage = tracker.beginStage(KID, attempt.attempt(), "chunking", null);

        tracker.endSpan(stage, "{\"chunks\":12}");

        KbProcessingSpanRecord stored = byName("chunking");
        assertThat(stored.getStatus()).isEqualTo("done");
        assertThat(stored.getFinishedAt()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(stored.getDurationMs()).isNotNull();
        assertThat(stored.getDurationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(stored.getOutput()).isEqualTo("{\"chunks\":12}");
        assertThat(stored.getStartedAt())
            .as("endSpan 不能把 beginStage 写的 started_at 丢掉")
            .isNotNull();
    }

    // ── 5. 失败与级联（核心判据）─────────────────────────────────

    @Test
    @DisplayName("5. failSpan ⇒ failed + 三个 error 字段 + 下游 stage 变 cancelled")
    void failSpanCascadesToDependentStages() {
        KbSpanTracker.AttemptRoot attempt = tracker.openAttempt(KID);
        int n = attempt.attempt();
        tracker.beginStage(KID, n, "docreader", null);
        KbSpan chunking = tracker.beginStage(KID, n, "chunking", null);
        // chunking 已经开跑的子任务：阶段失败时它也必须被取消，否则时间线上
        // 「已取消的父节点下面还挂着一个转圈的孤儿」
        KbSpan inFlightBatch = tracker.beginSubSpan(chunking, "chunking.batch[0]", "subspan", "{}");
        assertThat(inFlightBatch).isNotNull();
        tracker.beginStage(KID, n, "embedding", null);
        tracker.beginStage(KID, n, "multimodal", null);
        tracker.beginStage(KID, n, "postprocess", null);

        tracker.failSpan(chunking, "CHUNK_FAILED", "分块失败", new IllegalStateException("boom"));

        KbProcessingSpanRecord failed = byName("chunking");
        assertThat(failed.getStatus()).isEqualTo("failed");
        assertThat(failed.getErrorCode()).isEqualTo("CHUNK_FAILED");
        assertThat(failed.getErrorMessage()).isEqualTo("分块失败");
        assertThat(failed.getErrorDetail())
            .as("error_detail 存异常的 toString()")
            .contains("boom");
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThat(failed.getDurationMs()).isGreaterThanOrEqualTo(0L);

        // ⭐ 本用例的核心判据：chunking 挂掉 ⇒ 依赖它的三个阶段一步都跑不了
        assertThat(byName("embedding").getStatus()).isEqualTo("cancelled");
        assertThat(byName("multimodal").getStatus()).isEqualTo("cancelled");
        assertThat(byName("postprocess").getStatus()).isEqualTo("cancelled");
        assertThat(byName("embedding").getErrorCode()).isEqualTo("UPSTREAM_FAILED");
        assertThat(byName("embedding").getErrorMessage()).contains("chunking");

        // ⭐ 子树级联：失败阶段下已经开跑的子 span 也必须 cancelled（源站的第二次 walk）
        assertThat(byName("chunking.batch[0]").getStatus()).isEqualTo("cancelled");
        assertThat(byName("chunking.batch[0]").getErrorCode()).isEqualTo("UPSTREAM_FAILED");

        // 主流水线 stage 终态失败 ⇒ root 收口成 failed（源站 isMainPipelineStage 分支）
        assertThat(byName(ROOT_NAME).getStatus()).isEqualTo("failed");
        // 注：docreader 在上游、不受级联影响，但 finalize 会把仍 running 的兄弟一并收口
        // （派单书 2.5 的要求，见 tracker 类注释偏离 5）⇒ 这里不断言它的状态。
    }

    // ── 6. 跳过 ───────────────────────────────────────────────────

    @Test
    @DisplayName("6. skipSpan ⇒ skipped 且 output 里带 reason")
    void skipSpanRecordsReason() {
        KbSpanTracker.AttemptRoot attempt = tracker.openAttempt(KID);
        KbSpan stage = tracker.beginStage(KID, attempt.attempt(), "multimodal", null);

        tracker.skipSpan(stage, "纯文本文档，无需图片理解");

        KbProcessingSpanRecord stored = byName("multimodal");
        assertThat(stored.getStatus()).isEqualTo("skipped");
        assertThat(stored.getOutput()).contains("\"reason\"");
        assertThat(stored.getOutput()).contains("纯文本文档");
        assertThat(stored.getFinishedAt()).isNotNull();
    }

    // ── 7. 无父 ⇒ 不写库 ─────────────────────────────────────────

    @Test
    @DisplayName("7. beginSubSpan(null, ...) 返回 null 且库里零新增行")
    void subSpanWithoutParentWritesNothing() {
        KbSpanTracker.AttemptRoot attempt = tracker.openAttempt(KID);
        tracker.beginStage(KID, attempt.attempt(), "chunking", null);
        int before = rows().size();

        KbSpan sub = tracker.beginSubSpan(null, "chunking.batch[0]", "subspan", "{}");

        assertThat(sub)
            .as("源站明确：调用方必须容忍 null（拿不到父就照常干活，只是不记 span）")
            .isNull();
        assertThat(rows()).hasSize(before);
    }

    // ── 8. 收口 ───────────────────────────────────────────────────

    @Test
    @DisplayName("8. finalizeAttempt 把 root 与仍 running 的子 span 一起收口")
    void finalizeClosesRootAndOpenChildren() {
        KbSpanTracker.AttemptRoot attempt = tracker.openAttempt(KID);
        KbSpan chunking = tracker.beginStage(KID, attempt.attempt(), "chunking", null);
        KbSpan runningBatch = tracker.beginSubSpan(chunking, "chunking.batch[0]", "subspan", "{}");
        KbSpan finishedBatch = tracker.beginSubSpan(chunking, "chunking.batch[1]", "subspan", "{}");
        assertThat(runningBatch).isNotNull();
        tracker.endSpan(finishedBatch, "{\"ok\":true}");

        tracker.finalizeAttempt(KID, attempt.attempt(), "done", "{\"parse\":\"ok\"}");

        KbProcessingSpanRecord root = byName(ROOT_NAME);
        assertThat(root.getStatus()).isEqualTo("done");
        assertThat(root.getOutput()).isEqualTo("{\"parse\":\"ok\"}");
        assertThat(root.getFinishedAt()).isNotNull();
        assertThat(root.getDurationMs()).isGreaterThanOrEqualTo(0L);

        assertThat(byName("chunking").getStatus()).isEqualTo("done");
        assertThat(byName("chunking").getFinishedAt()).isNotNull();
        assertThat(byName("chunking.batch[0]").getStatus())
            .as("⭐ 仍 running 的子 span 必须一并收口，否则时间线上留下永远转圈的孤儿")
            .isEqualTo("done");
        assertThat(byName("chunking.batch[0]").getFinishedAt()).isNotNull();
        assertThat(byName("chunking.batch[1]").getStatus())
            .as("已经 done 的子 span 不该被改写")
            .isEqualTo("done");
    }

    // ── 9. 空库 ───────────────────────────────────────────────────

    @Test
    @DisplayName("9. latestAttempt 无记录 ⇒ 0")
    void latestAttemptWithoutRowsIsZero() {
        assertThat(tracker.latestAttempt("kb-doc-never-parsed")).isZero();
        assertThat(tracker.latestAttempt(null)).isZero();
    }
}
