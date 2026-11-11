package com.nexusai.kb.domain;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.dto.CreateKnowledgeBaseRequest;
import com.nexusai.kb.dto.KnowledgeBaseDto;
import com.nexusai.kb.dto.UpdateKnowledgeBaseRequest;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 知识库 CRUD 意图测试 · 批次 B1a。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：本批有四条「改坏了也不会立刻报错、但线上会静默出错」的性质，
 * 测试必须逐条钉死：
 * <ol>
 *   <li><b>时间列落库形态必须与 SQLite {@code datetime('now')} 同款</b>
 *       （{@code yyyy-MM-dd HH:mm:ss} 空格分隔）。若有人把 TypeHandler 摘掉，
 *       落库会变成 {@code 2026-11-03T09:05} ⇒ 与现有 19 张表混存时 ORDER BY 给错序
 *       （实测 09:05 排在 12:26:09 之后）。<b>本用例直接读 raw SQL，不读实体。</b></li>
 *   <li><b>逻辑删除真的过滤</b>：删掉之后 {@code list()} 与 {@code getById()} 都看不到。
 *       若 {@code @Column(isLogicDelete=true)} 被摘掉，删会变成物理删除（也是错的，但至少能被
 *       行数发现）；若查询侧没加条件，删完还能查到 ⇒ 本用例翻红。</li>
 *   <li><b>chunking_config 的 DB 默认值逐字等于源侧</b>：留空创建时必须拿到源侧那串默认 JSON，
 *       而不是 null 或空串。逐字比对（含空格差异 —— 源侧默认值里 {@code ": "} 带空格）。</li>
 *   <li><b>type 白名单</b>：源侧只有 document / faq / wiki；传 {@code "garbage"} 必须 400，
 *       不能静默存进库。</li>
 * </ol>
 */
@DisplayName("[B1a] 知识库 CRUD（audit 字段 + 逻辑删 + 源侧默认值）")
class KnowledgeBaseServiceTest {

    private static KbKnowledgeBaseMapper mapper;
    private static KnowledgeBaseService service;
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

        MybatisFlexDbTestSupport.resetAndStart(ds, KbKnowledgeBaseMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeBaseMapper.class);
        service = new KnowledgeBaseService(mapper);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        // 物理清表（绕开逻辑删）—— 用原生 SQL 才能真删干净
        try (Connection c = new SQLiteDataSource() {{ setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath()); }}.getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_knowledge_base");
        }
    }

    // ── 用例 1：时间列落库形态（raw SQL 读，不读实体）────────────────────

    @Test
    @DisplayName("create_time 落库为 yyyy-MM-dd HH:mm:ss（空格分隔，与 SQLite datetime('now') 同款）")
    void createTimePersistedInSqliteFormat() throws Exception {
        KnowledgeBaseDto created = service.create(new CreateKnowledgeBaseRequest(
            "时间格式测试库", null, null, null, "emb-1", "sum-1"));

        try (Connection c = new SQLiteDataSource() {{
                setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
            }}.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT create_time, update_time FROM kb_knowledge_base WHERE id = '"
                     + created.id() + "'")) {
            assertThat(rs.next()).isTrue();
            String createTime = rs.getString(1);
            String updateTime = rs.getString(2);

            // 关键断言：必须是空格分隔的 19 字符串
            assertThat(createTime)
                .as("create_time 必须是 SQLite datetime('now') 同款形态")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
                .hasSize(19)
                .doesNotContain("T");

            assertThat(updateTime)
                .as("update_time 同款")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
                .doesNotContain("T");
        }
    }

    @Test
    @DisplayName("混入 SQLite datetime('now') 行后，ORDER BY create_time 时间序正确")
    void orderByCreateTimeIsChronologicalAcrossMixedWriters() throws Exception {
        // 手工插一行，时间用 SQLite datetime('now') 写法（模拟现有 19 张表的写入方式）
        String mixedId = "mixed-sqlite-writer";
        try (Connection c = new SQLiteDataSource() {{
                setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
            }}.getConnection();
             Statement s = c.createStatement()) {
            // 用一个明确的「早」时间，保证排序有区分度
            s.executeUpdate("INSERT INTO kb_knowledge_base(id, name, embedding_model_id, summary_model_id,"
                + " create_time, update_time) VALUES ('" + mixedId + "', 'sqlite写法', 'e', 's',"
                + " '2020-01-01 00:00:00', '2020-01-01 00:00:00')");
        }

        // 再经服务创建一行（时间必然是 now，晚于 2020）
        KnowledgeBaseDto newer = service.create(new CreateKnowledgeBaseRequest(
            "java写法", null, null, null, "e", "s"));

        List<KnowledgeBaseDto> all = service.list();
        assertThat(all).hasSize(2);
        // 倒序 ⇒ Java 新建的那条必须排第一（时间晚）
        assertThat(all.get(0).id())
            .as("晚创建的必须排在前：若时间格式混用（T vs 空格），字典序会让 2020 排到 now 前面")
            .isEqualTo(newer.id());
        assertThat(all.get(1).id()).isEqualTo(mixedId);
    }

    // ── 用例 2：逻辑删除 ──────────────────────────────────────────────

    @Test
    @DisplayName("删除后 list/getById 均不可见，但物理行仍在（deleted=1）")
    void deleteIsLogical() throws Exception {
        KnowledgeBaseDto created = service.create(new CreateKnowledgeBaseRequest(
            "待删除库", null, null, null, "e", "s"));

        service.delete(created.id());

        assertThat(service.list()).isEmpty();
        assertThatThrownBy(() -> service.getById(created.id()))
            .isInstanceOf(NotFoundException.class);

        // 物理行仍在，且 deleted = 1
        try (Connection c = new SQLiteDataSource() {{
                setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
            }}.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT deleted FROM kb_knowledge_base WHERE id = '" + created.id() + "'")) {
            assertThat(rs.next())
                .as("逻辑删除必须保留物理行（物理删会让本断言落空）")
                .isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    // ── 用例 3：源侧默认值 ────────────────────────────────────────────

    @Test
    @DisplayName("chunking_config 留空创建 → 落库为源侧默认 JSON（逐字）")
    void chunkingConfigDefaultsToSourceValue() {
        KnowledgeBaseDto created = service.create(new CreateKnowledgeBaseRequest(
            "默认值库", null, null, null, "e", "s"));

        // 源侧 000000_init.up.sql 的默认值原文（注意 ": " 带空格）
        assertThat(created.chunkingConfig())
            .isEqualTo("{\"chunk_size\": 512, \"chunk_overlap\": 50, "
                + "\"split_markers\": [\"\\n\\n\", \"\\n\", \"。\"], \"keep_separator\": true}");
    }

    @Test
    @DisplayName("image_processing_config 落库为源侧默认 JSON")
    void imageProcessingConfigDefaultsToSourceValue() {
        KnowledgeBaseDto created = service.create(new CreateKnowledgeBaseRequest(
            "图片默认值库", null, null, null, "e", "s"));
        assertThat(created.imageProcessingConfig())
            .isEqualTo("{\"enable_multimodal\": false, \"model_id\": \"\"}");
    }

    @Test
    @DisplayName("type 留空 → document")
    void typeDefaultsToDocument() {
        KnowledgeBaseDto created = service.create(new CreateKnowledgeBaseRequest(
            "类型默认库", null, null, null, "e", "s"));
        assertThat(created.type()).isEqualTo("document");
    }

    // ── 用例 4：type 白名单 ───────────────────────────────────────────

    @Test
    @DisplayName("type 传非法值 → ValidationException（400），不落库")
    void invalidTypeRejected() {
        assertThatThrownBy(() -> service.create(new CreateKnowledgeBaseRequest(
            "非法类型库", null, "garbage", null, "e", "s")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("document / faq / wiki");

        assertThat(service.list()).isEmpty();
    }

    @Test
    @DisplayName("type 传 faq / wiki 合法；大小写归一")
    void validTypesAccepted() {
        assertThat(service.create(new CreateKnowledgeBaseRequest("faq库", null, "faq", null, "e", "s")).type())
            .isEqualTo("faq");
        assertThat(service.create(new CreateKnowledgeBaseRequest("wiki库", null, "WIKI", null, "e", "s")).type())
            .isEqualTo("wiki");
    }

    // ── 用例 5：基础 CRUD ─────────────────────────────────────────────

    /**
     * ⭐ 回归守护：`indexing_strategy` / `wiki_config` 必须**出站**。
     *
     * <p><b>WHY 这条必须存在</b>：前端靠 {@code indexing_strategy.wiki_enabled} 决定
     * Wiki / 图谱 tab 是否显示（源站 `KnowledgeBase.vue:99` 的 isWiki 判据）。
     * DTO 一旦漏掉这个字段，那两个 tab 就**永远隐藏**，而且：
     * 接口 200、字段缺失、控制台零报错 —— 只是「功能不见了」。
     * 这正是本仓栽过多次的「配置字段零生产者 / 半截接线」型静默缺陷。
     */
    @Test
    @DisplayName("⭐ indexing_strategy 与 wiki_config 必须出站（否则 Wiki/图谱 tab 永远隐藏）")
    void indexingStrategyIsReturned() {
        KnowledgeBaseDto created = service.create(new CreateKnowledgeBaseRequest(
            "wiki开关库", null, "document", null, "e", "s"));

        // 新建时两列为空 ⇒ DTO 值为 null，但**字段必须在**（不是被 DTO 丢掉）
        assertThat(created.indexingStrategy()).isNull();
        assertThat(created.wikiConfig()).isNull();

        // 写进去再读回来 —— 证明 toDto 真的搬运了这两列
        String strategy = "{\"wiki_enabled\":true,\"graph_enabled\":false}";
        com.fasterxml.jackson.databind.node.ObjectNode body =
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        body.put("indexing_strategy", strategy);
        service.patch(created.id(), body);

        KnowledgeBaseDto after = service.list().stream()
            .filter((b) -> created.id().equals(b.id()))
            .findFirst()
            .orElseThrow();
        assertThat(after.indexingStrategy())
            .as("indexing_strategy 读不回来 ⇒ 前端拿不到 wiki_enabled ⇒ Wiki/图谱 tab 永久隐藏")
            .isEqualTo(strategy);
    }

    @Test
    @DisplayName("⭐ 创建时就带 indexing_strategy ⇒ Wiki 开关落库（源侧创建流程，不靠事后 patch）")
    void createAcceptsIndexingStrategy() {
        String strategy = "{\"vector_enabled\":true,\"keyword_enabled\":true,"
            + "\"wiki_enabled\":true,\"graph_enabled\":false}";

        KnowledgeBaseDto created = service.create(new CreateKnowledgeBaseRequest(
            "建库即开Wiki", null, "document", null, "e", "s", strategy));

        assertThat(created.indexingStrategy())
            .as("源侧创建时同一个请求就带 indexing_strategy（KnowledgeBaseEditorModal.vue:1690）；"
                + "只能事后 patch 的话，前端那个「Wiki 知识库」勾选框就只能灰置")
            .isEqualTo(strategy);

        // 回读确认真的落库了，不是只在返回的 DTO 上
        KnowledgeBaseDto reread = service.list().stream()
            .filter((b) -> created.id().equals(b.id()))
            .findFirst()
            .orElseThrow();
        assertThat(reread.indexingStrategy()).isEqualTo(strategy);
    }

    @Test
    @DisplayName("创建时 indexing_strategy 不是 JSON 对象 ⇒ 400（⛔ 不静默存脏数据）")
    void createRejectsNonJsonIndexingStrategy() {
        assertThatThrownBy(() -> service.create(new CreateKnowledgeBaseRequest(
            "坏策略库", null, "document", null, "e", "s", "not-json")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("JSON 对象");
    }

    @Test
    @DisplayName("创建 → 查询 → 更新 → 查询，update_time 会变")
    void createReadUpdate() {
        KnowledgeBaseDto created = service.create(new CreateKnowledgeBaseRequest(
            "原始名", "原始描述", null, null, "emb-1", "sum-1"));
        assertThat(created.name()).isEqualTo("原始名");
        assertThat(created.description()).isEqualTo("原始描述");
        assertThat(created.embeddingModelId()).isEqualTo("emb-1");

        LocalDateTime beforeUpdate = LocalDateTime.now().minusSeconds(1);

        // 只改名字，其余 null = 不改
        KnowledgeBaseDto updated = service.update(created.id(),
            new UpdateKnowledgeBaseRequest("新名", null, null, null, null, null));

        assertThat(updated.name()).isEqualTo("新名");
        assertThat(updated.description())
            .as("未传的字段必须保持原值（局部更新语义）")
            .isEqualTo("原始描述");
        assertThat(updated.embeddingModelId()).isEqualTo("emb-1");
        assertThat(updated.updatedAt())
            .as("update_time 必须被自动刷新")
            .isNotBlank();

        KnowledgeBaseDto reread = service.getById(created.id());
        assertThat(reread.name()).isEqualTo("新名");
    }

    @Test
    @DisplayName("名称空白 → ValidationException；创建时空白名被拒")
    void blankNameRejected() {
        assertThatThrownBy(() -> service.create(new CreateKnowledgeBaseRequest(
            "   ", null, null, null, "e", "s")))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("查询不存在的 ID → NotFoundException")
    void getMissingThrows() {
        assertThatThrownBy(() -> service.getById("no-such-id"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("audit 字段：creator/updater 默认空串，tenant_id 默认 0，deleted 默认 0")
    void auditDefaults() throws Exception {
        KnowledgeBaseDto created = service.create(new CreateKnowledgeBaseRequest(
            "审计字段库", null, null, null, "e", "s"));

        try (Connection c = new SQLiteDataSource() {{
                setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
            }}.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT creator, updater, deleted, tenant_id FROM kb_knowledge_base"
                     + " WHERE id = '" + created.id() + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("creator")).isEqualTo("");
            assertThat(rs.getString("updater")).isEqualTo("");
            assertThat(rs.getInt("deleted")).isZero();
            assertThat(rs.getLong("tenant_id")).isZero();
        }
    }

    @Test
    @DisplayName("置顶库排在列表最前")
    void pinnedFirst() throws Exception {
        KnowledgeBaseDto older = service.create(new CreateKnowledgeBaseRequest(
            "较早的库", null, null, null, "e", "s"));
        KnowledgeBaseDto newer = service.create(new CreateKnowledgeBaseRequest(
            "较新的库", null, null, null, "e", "s"));

        // 把「较早的」置顶
        try (Connection c = new SQLiteDataSource() {{
                setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
            }}.getConnection();
             Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE kb_knowledge_base SET is_pinned = 1 WHERE id = '" + older.id() + "'");
        }

        List<KnowledgeBaseDto> all = service.list();
        assertThat(all.get(0).id())
            .as("置顶优先于创建时间")
            .isEqualTo(older.id());
        assertThat(all.get(1).id()).isEqualTo(newer.id());
    }

    // ── W1：检索驱动（每库可覆盖）────────────────────────────────────

    @Test
    @DisplayName("⭐⭐ 保存未接入的检索驱动 ⇒ 400（⛔ 不让它落库后才在检索时炸）")
    void rejectsUnavailableRetrieverEngine() {
        KnowledgeBaseDto kb = service.create(new CreateKnowledgeBaseRequest(
            "驱动校验库", null, null, null, "e", "s"));

        // 注入真实注册表（只含 sqlite 可用）
        injectRegistry(service);

        assertThatThrownBy(() -> service.update(kb.id(), new UpdateKnowledgeBaseRequest(
            null, null, null, null, null, "milvus")))
            .as("⭐⭐ 若这里放行：用户上传并索引完文档，直到第一次检索才炸；"
                + "而那时文档状态已经是 completed，排查成本高得多")
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("milvus");

        assertThat(service.getById(kb.id()).retrieverEngine())
            .as("被拒后库里的值必须保持原样（不能写进去）")
            .isNull();
    }

    @Test
    @DisplayName("⭐ sqlite 可设置，且能读回（知识库级覆盖全局）")
    void acceptsAvailableRetrieverEngine() {
        KnowledgeBaseDto kb = service.create(new CreateKnowledgeBaseRequest(
            "驱动覆盖库", null, null, null, "e", "s"));
        injectRegistry(service);

        KnowledgeBaseDto updated = service.update(kb.id(), new UpdateKnowledgeBaseRequest(
            null, null, null, null, null, "sqlite"));
        assertThat(updated.retrieverEngine()).isEqualTo("sqlite");
        assertThat(service.getById(kb.id()).retrieverEngine())
            .as("必须真落库，不能只在返回值里")
            .isEqualTo("sqlite");
    }

    @Test
    @DisplayName("传空串 ⇒ 清空覆盖，回落全局默认")
    void emptyStringClearsOverride() {
        KnowledgeBaseDto kb = service.create(new CreateKnowledgeBaseRequest(
            "清空驱动库", null, null, null, "e", "s"));
        injectRegistry(service);
        service.update(kb.id(), new UpdateKnowledgeBaseRequest(
            null, null, null, null, null, "sqlite"));

        KnowledgeBaseDto cleared = service.update(kb.id(), new UpdateKnowledgeBaseRequest(
            null, null, null, null, null, ""));
        assertThat(cleared.retrieverEngine())
            .as("⭐ 「清空」与「不改」必须是两种效果：null=不改，\"\"=清空")
            .isNull();
    }

    /** 给手工 new 的 service 注入真实注册表（该类用字段注入）。 */
    private static void injectRegistry(KnowledgeBaseService target) {
        // 只声明 sqlite 可用 + milvus 不可用，贴近真实构建
        com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry registry =
            new com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry(
                java.util.List.of(
                    acceptAll("sqlite"),
                    unavailable("milvus")));
        try {
            java.lang.reflect.Field f = KnowledgeBaseService.class
                .getDeclaredField("engineRegistry");
            f.setAccessible(true);
            f.set(target, registry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一个「永远可用」的假驱动，只为让注册表能解析 sqlite。 */
    private static com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine
    acceptAll(String type) {
        return new com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine() {
            @Override public String engineType() { return type; }
            @Override public boolean available() { return true; }
            @Override public boolean supports(Feature feature) { return true; }
            @Override public void index(String kbId, java.util.List<IndexDocument> docs, String modelId) { }
            @Override public void deleteByKnowledgeIds(String kbId, java.util.List<String> ids) { }
            @Override public java.util.List<Hit> retrieve(RetrieveParams params) {
                return java.util.List.of();
            }
        };
    }

    /** 一个「已声明但不可用」的假驱动（模拟未接入的外部库）。 */
    private static com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine
    unavailable(String type) {
        return new com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine() {
            @Override public String engineType() { return type; }
            @Override public boolean available() { return false; }
            @Override public boolean supports(Feature feature) { return false; }
            @Override public void index(String kbId, java.util.List<IndexDocument> docs, String modelId) { }
            @Override public void deleteByKnowledgeIds(String kbId, java.util.List<String> ids) { }
            @Override public java.util.List<Hit> retrieve(RetrieveParams params) {
                return java.util.List.of();
            }
        };
    }
}
