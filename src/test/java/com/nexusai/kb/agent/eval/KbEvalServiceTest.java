package com.nexusai.kb.agent.eval;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.agent.eval.mapper.KbEvalCaseMapper;
import com.nexusai.kb.agent.eval.mapper.KbEvalDatasetMapper;
import com.nexusai.kb.agent.eval.mapper.KbEvalTaskMapper;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.domain.KnowledgeBaseService;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识库评测（{@code kb_eval_*}）· 指标计算与失败语义。
 *
 * <p><b>WHY 这些断言</b>：评测最危险的不是算错，而是**把「算不出来」显示成「0 分」**：
 * <ul>
 *   <li>用例没配期望 chunk 却显示 recall=0 ⇒ 用户以为检索坏了，实际是没配真值；</li>
 *   <li>检索抛异常被当成「未命中」⇒ 真实故障被稀释进分数里，没人会去看；</li>
 *   <li>空数据集跑出全 0 ⇒ 看起来「跑过了」。</li>
 * </ul>
 * ⇒ 因此 {@code null}（算不出）与 {@code 0.0}（真的没命中）必须分开断言。
 */
@DisplayName("[W11] 知识库评测指标")
class KbEvalServiceTest {

    private static KbEvalDatasetMapper datasetMapper;
    private static KbEvalCaseMapper caseMapper;
    private static KbEvalTaskMapper taskMapper;
    private static Path dbPath;

    private KbEvalService service;
    private KbHybridSearcher searcher;

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

        MybatisFlexDbTestSupport.resetAndStart(ds,
            KbEvalDatasetMapper.class, KbEvalCaseMapper.class, KbEvalTaskMapper.class);
        datasetMapper = MybatisFlexBootstrap.getInstance().getMapper(KbEvalDatasetMapper.class);
        caseMapper = MybatisFlexBootstrap.getInstance().getMapper(KbEvalCaseMapper.class);
        taskMapper = MybatisFlexBootstrap.getInstance().getMapper(KbEvalTaskMapper.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_eval_case");
            s.executeUpdate("DELETE FROM kb_eval_task");
            s.executeUpdate("DELETE FROM kb_eval_dataset");
        }

        service = new KbEvalService();
        setField(service, "datasetMapper", datasetMapper);
        setField(service, "caseMapper", caseMapper);
        setField(service, "taskMapper", taskMapper);
        setField(service, "knowledgeBaseService", permissiveKnowledgeBaseService());
        setField(service, "kbConfigService", null);
        setField(service, "searcher", null); // 每个用例自己装
    }

    // ── 指标 ───────────────────────────────────────────────

    @Test
    @DisplayName("⭐ recall / mrr / hitRate 按真实命中位次计算")
    void computesMetricsFromRealRanks() {
        // 用例 1：期望 c1，实际排第 1 ⇒ recall=1, rr=1
        // 用例 2：期望 c9，实际排第 3 ⇒ recall=0, rr=0
        installSearcher(List.of(
            List.of(hit("c1", 0.9), hit("c2", 0.8)),
            List.of(hit("c7", 0.9), hit("c8", 0.7), hit("c9", 0.6))
        ));
        // ⚠️ 上面第二组改成「期望 c9 且排第 3」需要真命中才计入 —— 见下：c9 在列表里 = 命中
        var ds = service.createDataset("测试集", null, "kb-1", List.of(
            new KbEvalService.CaseInput("问题1", null, List.of("c1")),
            new KbEvalService.CaseInput("问题2", null, List.of("c9"))
        ));

        var task = service.run(ds.getId());

        assertThat(task.getStatus()).isEqualTo("completed");
        Map<String, Object> m = service.getTask(task.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) m.get("metrics");
        assertThat(metrics.get("scored_case_count")).isEqualTo(2);
        assertThat(metrics.get("hit_rate")).isEqualTo(1.0);
        assertThat(metrics.get("recall")).isEqualTo(1.0);
        // 位次 1 + 位次 3 ⇒ (1/1 + 1/3) / 2 = 0.6667
        assertThat(metrics.get("mrr")).isEqualTo(0.6667);
    }

    @Test
    @DisplayName("⭐ 未命中是 0.0，不是 null（区分「没配真值」）")
    void missIsZeroNotAbsent() {
        installSearcher(List.of(List.of(hit("other", 0.9))));
        var ds = service.createDataset("测试集", null, "kb-1", List.of(
            new KbEvalService.CaseInput("问题1", null, List.of("c1"))
        ));

        var task = service.run(ds.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>)
            service.getTask(task.getId()).get("metrics");

        assertThat(metrics.get("hit_rate")).isEqualTo(0.0);
        assertThat(metrics.get("recall")).isEqualTo(0.0);
        assertThat(metrics.get("mrr")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("⭐ 没配期望 chunk ⇒ 指标为 null 并说明原因（不是 0 分）")
    void noExpectedChunksMeansNullNotZero() {
        installSearcher(List.of(List.of(hit("c1", 0.9))));
        var ds = service.createDataset("只评检索", null, "kb-1", List.of(
            new KbEvalService.CaseInput("问题1", "标准答案", List.of())
        ));

        var task = service.run(ds.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>)
            service.getTask(task.getId()).get("metrics");

        assertThat(metrics.get("hit_rate")).isNull();
        assertThat(metrics.get("recall")).isNull();
        assertThat(metrics.get("mrr")).isNull();
        assertThat(metrics.get("note")).asString().contains("expect_chunk_ids");
    }

    @Test
    @DisplayName("⭐ 单条检索抛异常 ⇒ 记进明细，不静默算成未命中")
    void searchFailureRecordedInDetails() {
        searcher = new KbHybridSearcher(null, null, null, null) {
            @Override
            public List<Hit> search(String kbId, String q, int limit, String modelId) {
                throw new IllegalStateException("向量服务挂了");
            }
        };
        setFieldQuietly(service, "searcher", searcher);

        var ds = service.createDataset("故障集", null, "kb-1", List.of(
            new KbEvalService.CaseInput("问题1", null, List.of("c1"))
        ));
        var task = service.run(ds.getId());

        assertThat(task.getStatus()).as("单条失败不应让整轮变 failed").isEqualTo("completed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>)
            service.getTask(task.getId()).get("details");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).get("error")).asString().contains("向量服务挂了");
    }

    // ── 边界 ───────────────────────────────────────────────

    @Test
    @DisplayName("空用例集 ⇒ 400（不是跑出全 0）")
    void emptyDatasetRejected() {
        var ds = service.createDataset("空集", null, "kb-1", List.of());
        assertThatThrownBy(() -> service.run(ds.getId()))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("没有用例");
    }

    @Test
    @DisplayName("数据集不存在 ⇒ 404")
    void unknownDatasetRejected() {
        assertThatThrownBy(() -> service.run("nope"))
            .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getDataset("nope"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("创建数据集缺 knowledge_base_id ⇒ 400")
    void createRequiresKnowledgeBase() {
        assertThatThrownBy(() -> service.createDataset("x", null, "  ", List.of()))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("knowledge_base_id");
    }

    @Test
    @DisplayName("用例问题为空 ⇒ 400（说明是第几条）")
    void blankQuestionRejectedWithIndex() {
        assertThatThrownBy(() -> service.createDataset("x", null, "kb-1", List.of(
            new KbEvalService.CaseInput("ok", null, List.of()),
            new KbEvalService.CaseInput("  ", null, List.of())
        ))).isInstanceOf(ValidationException.class)
            .hasMessageContaining("第 2 条");
    }

    @Test
    @DisplayName("创建后能读回用例（顺序按 seq）")
    void createThenReadBack() {
        var ds = service.createDataset("回读", "描述", "kb-1", List.of(
            new KbEvalService.CaseInput("Q1", "A1", List.of("c1", "c2")),
            new KbEvalService.CaseInput("Q2", null, List.of())
        ));

        Map<String, Object> got = service.getDataset(ds.getId());
        assertThat(got.get("name")).isEqualTo("回读");
        assertThat(got.get("knowledge_base_id")).isEqualTo("kb-1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) got.get("cases");
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).get("question")).isEqualTo("Q1");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) cases.get(0).get("expect_chunk_ids");
        assertThat(ids).containsExactly("c1", "c2");
    }

    // ── 辅助 ───────────────────────────────────────────────

    private void installSearcher(List<List<KbHybridSearcher.Hit>> perCall) {
        searcher = new KbHybridSearcher(null, null, null, null) {
            private int i;

            @Override
            public List<Hit> search(String kbId, String q, int limit, String modelId) {
                int idx = i++;
                return idx < perCall.size() ? perCall.get(idx) : List.of();
            }
        };
        setFieldQuietly(service, "searcher", searcher);
    }

    private static KbHybridSearcher.Hit hit(String chunkId, double score) {
        return new KbHybridSearcher.Hit(chunkId, "k-" + chunkId, "正文", "", score, 1, 1);
    }

    /**
     * 只要库 id 非空就算存在。
     *
     * <p>评测服务只把 {@code getById} 当**存在性检查**用（返回值不消费），
     * 所以这里返回 null 即可；真实「库不存在 ⇒ 404」的行为由
     * {@code KnowledgeBaseService} 自己负责，本测试不重复验证。
     */
    private static KnowledgeBaseService permissiveKnowledgeBaseService() {
        return new KnowledgeBaseService((KbKnowledgeBaseMapper) null) {
            @Override
            public com.nexusai.kb.dto.KnowledgeBaseDto getById(String id) {
                return null;
            }
        };
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入 " + name + " 失败", e);
        }
    }

    private static void setFieldQuietly(Object target, String name, Object value) {
        setField(target, name, value);
    }

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }
}
