package com.nexusai.kb.api;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.app.wiki.KbWikiIngestService;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手动补生成 Wiki · 端点层测试（整库筛选 + 连点保护 + 响应映射）。
 *
 * <h2>WHY</h2>
 * <ol>
 *   <li><b>整库口径必须只取「可检索」的文档</b>：若把 {@code processing} / 未启用的文档也喂给
 *       Wiki，模型会对着半截分块编造条目 —— 而且不报错，页面看起来「生成成功了」。</li>
 *   <li><b>连点保护是真实成本闸</b>：每篇文档至少一次 LLM 调用，用户双击按钮就会翻倍烧钱。
 *       用真并发线程验证第二个请求确实被 {@code skipped} 挡下。</li>
 *   <li><b>响应字段必须逐字段映射</b>：{@code RunSummary} → snake_case DTO 若漏映射，
 *       前端只能显示「0 页」，用户会以为没生成。</li>
 * </ol>
 *
 * <p>⚠️ 不启动 Spring：{@code WikiController} 的 {@code @Autowired} 字段用反射注入，
 * {@code KbWikiIngestService} 用<b>子类桩</b>替代（真跑会调 LLM）。
 * 数据库用 {@link MybatisFlexDbTestSupport#sharedDbPath()} 的测试 SQLite（⛔ 不碰用户真库）。
 */
@DisplayName("[Wiki] 手动补生成端点（整库筛选 + 连点保护）")
class WikiRebuildEndpointTest {

    private static final String KB_ID = "kb-endpoint-test";
    private static Path dbPath;
    private static KbKnowledgeMapper knowledgeMapper;
    private static KbKnowledgeBaseMapper knowledgeBaseMapper;

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
            KbKnowledgeMapper.class, KbKnowledgeBaseMapper.class);

        knowledgeMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeMapper.class);
        knowledgeBaseMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeBaseMapper.class);
    }

    @BeforeEach
    void cleanAndSeed() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_knowledge WHERE knowledge_base_id = '" + KB_ID + "'");
            s.executeUpdate("DELETE FROM kb_knowledge_base WHERE id = '" + KB_ID + "'");
        }
        insertBase(KB_ID, "summary-model-from-kb", "{\"auto_generate\": false}");
    }

    // ── 用例 1：整库筛选口径 ──────────────────────────────────────────

    @Test
    @DisplayName("⭐⭐ 整库模式只取 completed + enabled 的文档（不含 processing / 已禁用）")
    void wholeKbSelectsOnlySearchableDocs() throws Exception {
        String ok1 = insertKnowledge(KB_ID, "completed", "enabled");
        String ok2 = insertKnowledge(KB_ID, "completed", "enabled");
        insertKnowledge(KB_ID, "completed", "disabled");   // 已禁用 ⇒ 不该喂给 Wiki
        insertKnowledge(KB_ID, "processing", "enabled");  // 解析中 ⇒ 分块不完整
        insertKnowledge(KB_ID, "finalizing", "enabled");  // 后处理未完 ⇒ 半成品
        insertKnowledge(KB_ID, "failed", "enabled");

        CapturingIngest ingest = new CapturingIngest();
        WikiController controller = newController(ingest);

        WikiController.WikiRebuildResponse r = controller.rebuild(KB_ID, null, null);

        assertThat(ingest.capturedIds)
            .as("⭐ 只有「解析完成且已启用」的文档可喂给 Wiki —— "
                + "否则模型会对着半截分块编造条目，且不报错")
            .containsExactlyInAnyOrder(ok1, ok2);
        assertThat(r.status()).isEqualTo("done");
        assertThat(r.docsProcessed()).isEqualTo(2);
        assertThat(r.createdPages()).containsExactly("concept/a");
        assertThat(r.updatedPages()).containsExactly("entity/b");
        assertThat(r.rejectedPages()).isEqualTo(3);
        assertThat(r.deadLinksRemoved()).isEqualTo(4);
        assertThat(r.crossLinksAdded()).isEqualTo(5);
    }

    @Test
    @DisplayName("⭐ 整库没有可处理文档 ⇒ 200 + status=skipped，且说清原因")
    void emptyWholeKbReturnsSkipped() throws Exception {
        CapturingIngest ingest = new CapturingIngest();
        WikiController controller = newController(ingest);

        WikiController.WikiRebuildResponse r = controller.rebuild(KB_ID, null, null);

        assertThat(r.status()).isEqualTo("skipped");
        assertThat(r.detail()).isEqualTo("该知识库没有已解析完成的文档");
        assertThat(ingest.callCount)
            .as("空库不该白跑一轮 LLM（runBatch 根本不该被调用）")
            .isZero();
    }

    @Test
    @DisplayName("⭐ 单篇模式：只传该文档 ID，且模型解析顺序 请求 > synthesis > summary")
    void singleDocUsesRequestedModel() throws Exception {
        String docId = insertKnowledge(KB_ID, "completed", "enabled");
        CapturingIngest ingest = new CapturingIngest();
        WikiController controller = newController(ingest);

        WikiController.WikiRebuildResponse r = controller.rebuild(KB_ID, docId, "requested-model");

        assertThat(ingest.capturedIds).containsExactly(docId);
        assertThat(ingest.capturedModel)
            .as("显式请求的模型优先")
            .isEqualTo("requested-model");
        assertThat(r.status()).isEqualTo("done");
    }

    @Test
    @DisplayName("⭐ 无请求模型 ⇒ 回落知识库 summary_model_id（wiki_config.synthesis 为空）")
    void modelFallsBackToSummaryModel() throws Exception {
        insertKnowledge(KB_ID, "completed", "enabled");
        CapturingIngest ingest = new CapturingIngest();
        WikiController controller = newController(ingest);

        controller.rebuild(KB_ID, null, null);

        assertThat(ingest.capturedModel).isEqualTo("summary-model-from-kb");
    }

    // ── 用例 2：连点保护 ──────────────────────────────────────────────

    @Test
    @DisplayName("⭐⭐ 同一知识库并发两次 ⇒ 第二次 skipped「正在生成」，不重复烧 LLM")
    void concurrentCallIsRejected() throws Exception {
        insertKnowledge(KB_ID, "completed", "enabled");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CapturingIngest slow = new CapturingIngest() {
            @Override
            public RunSummary runBatch(String kbId, List<String> ids, String model,
                                       boolean ingestRunning, boolean manual) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return super.runBatch(kbId, ids, model, ingestRunning, manual);
            }
        };
        WikiController controller = newController(slow);

        List<WikiController.WikiRebuildResponse> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Thread first = new Thread(() -> {
            try {
                results.add(controller.rebuild(KB_ID, null, null));
            } catch (Throwable t) {
                errors.add(t);
            }
        }, "wiki-rebuild-first");
        first.start();
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("第一个请求必须真的进入 runBatch").isTrue();

        // 第一个还在跑 ⇒ 第二个必须被挡（这就是用户「连点两次」的时刻）
        WikiController.WikiRebuildResponse second = controller.rebuild(KB_ID, null, null);

        release.countDown();
        first.join(10_000);
        assertThat(errors).as("首个请求不应抛异常: %s", errors).isEmpty();

        assertThat(second.status()).isEqualTo("skipped");
        assertThat(second.detail()).isEqualTo("该知识库正在生成 Wiki，请稍候");
        assertThat(slow.realCallCount)
            .as("⭐ 连点只允许真实跑一轮（第二轮的每篇文档都是一次 LLM 调用）")
            .isEqualTo(1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("done");
    }

    @Test
    @DisplayName("⭐ 一次生成结束后锁必须释放（否则该库永久无法再生成）")
    void lockReleasedAfterRun() throws Exception {
        insertKnowledge(KB_ID, "completed", "enabled");
        CapturingIngest ingest = new CapturingIngest();
        WikiController controller = newController(ingest);

        assertThat(controller.rebuild(KB_ID, null, null).status()).isEqualTo("done");
        assertThat(controller.rebuild(KB_ID, null, null).status())
            .as("⭐ finally 里 remove 若漏写：用户第一次点完，之后永远「请稍候」—— 而且不报错")
            .isEqualTo("done");
        assertThat(ingest.realCallCount).isEqualTo(2);
    }

    // ── 用例 3：校验失败 fail-loud ───────────────────────────────────

    @Test
    @DisplayName("⭐ 未配置任何模型 ⇒ 400 ValidationException（⛔ 不静默返回空）")
    void missingModelFailsLoud() throws Exception {
        String kbId = "kb-no-model";
        insertBase(kbId, "", "{\"auto_generate\": true}");
        WikiController controller = newController(new CapturingIngest());

        try {
            controller.rebuild(kbId, null, null);
            org.assertj.core.api.Assertions.fail("必须抛 ValidationException");
        } catch (com.nexusai.infra.exception.ValidationException e) {
            assertThat(e.getMessage()).contains("未配置 Wiki 生成模型");
        } finally {
            try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM kb_knowledge_base WHERE id = '" + kbId + "'");
            }
        }
    }

    @Test
    @DisplayName("⭐ 文档不属于该知识库 ⇒ 400（对齐 GraphController 的同名校验）")
    void foreignDocRejected() throws Exception {
        String otherKb = "kb-other";
        insertBase(otherKb, "m", "{}");
        String foreignDoc = insertKnowledge(otherKb, "completed", "enabled");
        WikiController controller = newController(new CapturingIngest());

        try {
            controller.rebuild(KB_ID, foreignDoc, null);
            org.assertj.core.api.Assertions.fail("必须抛 ValidationException");
        } catch (com.nexusai.infra.exception.ValidationException e) {
            assertThat(e.getMessage()).isEqualTo("该文档不属于此知识库");
        } finally {
            try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM kb_knowledge_base WHERE id = '" + otherKb + "'");
            }
        }
    }

    // ── 支撑 ──────────────────────────────────────────────────────────

    /** 记录入参、返回固定统计的 {@link KbWikiIngestService} 桩（真跑会调 LLM）。 */
    private static class CapturingIngest extends KbWikiIngestService {
        final List<String> capturedIds = new ArrayList<>();
        String capturedModel;
        int callCount;
        int realCallCount;

        CapturingIngest() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public RunSummary runBatch(String kbId, List<String> knowledgeIds, String model,
                                   boolean ingestRunning, boolean manual) {
            callCount++;
            realCallCount++;
            capturedIds.clear();
            capturedIds.addAll(knowledgeIds);
            capturedModel = model;
            return new RunSummary(false, null, knowledgeIds.size(), 0, 0,
                List.of("concept/a"), List.of("entity/b"), 3, 4, 5);
        }
    }

    private static WikiController newController(KbWikiIngestService ingest) throws Exception {
        WikiController c = new WikiController();
        set(c, "pageService", null);
        set(c, "ingestService", ingest);
        set(c, "knowledgeMapper", knowledgeMapper);
        set(c, "knowledgeBaseMapper", knowledgeBaseMapper);
        return c;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = WikiController.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }

    private static void insertBase(String id, String summaryModel, String wikiConfig) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO kb_knowledge_base "
                + "(id, name, type, embedding_model_id, summary_model_id, wiki_config) VALUES ('"
                + id + "', '测试库', 'document', 'embed-model', '" + summaryModel + "', '"
                + wikiConfig.replace("'", "''") + "')");
        }
    }

    private static String insertKnowledge(String kbId, String parseStatus, String enableStatus)
        throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO kb_knowledge "
                + "(id, knowledge_base_id, type, title, source, parse_status, enable_status) VALUES ('"
                + id + "', '" + kbId + "', 'document', '测试文档', 'manual', '"
                + parseStatus + "', '" + enableStatus + "')");
        }
        return id;
    }
}
