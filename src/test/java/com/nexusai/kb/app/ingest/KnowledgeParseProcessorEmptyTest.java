package com.nexusai.kb.app.ingest;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.app.parser.KbDocumentParser;
import com.nexusai.kb.app.parser.KbImageRef;
import com.nexusai.kb.app.parser.KbOfficeParser;
import com.nexusai.kb.app.parser.KbParseContext;
import com.nexusai.kb.app.parser.KbParsedDocument;
import com.nexusai.kb.app.parser.KbParserRegistry;
import com.nexusai.kb.app.parser.KbTextParser;
import com.nexusai.kb.app.retrieval.KbEmbeddingPort;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.app.retrieval.engine.KbRetrievalEngine;
import com.nexusai.kb.app.retrieval.engine.KbRetrievalEngineRegistry;
import com.nexusai.kb.app.retrieval.engine.KbSqliteRetrievalEngine;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbEmbeddingMapper;
import com.nexusai.kb.repo.mapper.KbIngestTaskMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 空正文护栏 · 「解析成功但 0 字」不再标 completed。
 *
 * <p><b>WHY 必须有这一组用例</b>：改造前，解析器产出空正文时整条链仍然走完
 * （0 分块 → 建索引 → {@code markIndexed} → {@code COMPLETED}）。用户看到的是
 * 「已完成」却一条内容都搜不到，且没有任何报错。源站对空内容一律 {@code failed}。
 *
 * <p>判据是<b>回表读 {@code kb_knowledge.parse_status}</b> 与 {@code kb_chunk} 行数，
 * ⛔ 不是只看返回值：返回值与状态机是两条独立的写路径（一个改了另一个没改正是本批要防的形态）。
 *
 * <p>⚠️ 本测试<b>不</b>用 {@code @SpringBootTest}（项目硬约束：它会迁移用户真库）；
 * 真实 SQLite + 真实解析器 + 真实状态机，DB 落在 {@code target/flex-dbtest}（与其余 KB 测试共享）。
 */
@DisplayName("[空正文] 解析产出空文本 ⇒ failed（源站口径）+ 纯图文档例外")
class KnowledgeParseProcessorEmptyTest {

    private static KbKnowledgeMapper knowledgeMapper;
    private static KbKnowledgeBaseMapper knowledgeBaseMapper;
    private static KbChunkMapper chunkMapper;
    private static KbEmbeddingMapper embeddingMapper;
    private static KbIngestTaskMapper taskMapper;
    private static KbKnowledgeStateRepository stateRepository;
    private static KbIngestTaskQueue queue;
    private static SQLiteDataSource ds;

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

        MybatisFlexDbTestSupport.resetAndStart(ds,
            KbKnowledgeMapper.class,
            KbKnowledgeBaseMapper.class,
            KbChunkMapper.class,
            KbEmbeddingMapper.class,
            KbIngestTaskMapper.class);

        knowledgeMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeMapper.class);
        knowledgeBaseMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeBaseMapper.class);
        chunkMapper = MybatisFlexBootstrap.getInstance().getMapper(KbChunkMapper.class);
        embeddingMapper = MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class);
        taskMapper = MybatisFlexBootstrap.getInstance().getMapper(KbIngestTaskMapper.class);

        stateRepository = new KbKnowledgeStateRepository(knowledgeMapper);
        queue = new KbIngestTaskQueue(taskMapper);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_chunk");
            s.executeUpdate("DELETE FROM kb_ingest_task");
            s.executeUpdate("DELETE FROM kb_knowledge");
            s.executeUpdate("DELETE FROM kb_knowledge_base");
        }
    }

    // ── 用例 ①：空正文 docx ⇒ FAILED + parse_status=failed ───────────────

    /**
     * 真实空 docx 夹具（POI 造一个没有任何段落/正文的 .docx）：Tika 抽不出任何文本，
     * 归一化后为空 ⇒ 必须走失败路径，而不是「0 分块但已完成」。
     */
    @Test
    @DisplayName("⭐ 空文本 docx ⇒ Outcome.FAILED，kb_knowledge.parse_status=failed，0 分块、未转 enabled")
    void emptyDocxMarksFailed() throws Exception {
        Path docx = writeEmptyDocx();
        String knowledgeId = insertKnowledgeWithFile("pending", 0, docx);

        KnowledgeParseProcessor.Outcome outcome = runParse(docx, knowledgeId);

        assertThat(outcome)
            .as("⭐⭐ 空正文必须返回 FAILED（改造前是 COMPLETED + 0 分块）")
            .isEqualTo(KnowledgeParseProcessor.Outcome.FAILED);

        Row row = readKnowledge(knowledgeId);
        assertThat(row.parseStatus)
            .as("⭐⭐ kb_knowledge.parse_status 必须真的落成 failed —— 这是用户能看到的唯一证据")
            .isEqualTo("failed");
        assertThat(row.errorMessage)
            .as("失败原因必须是本批约定的空正文文案（用户据此知道该配视觉模型/换 OCR）")
            .contains("解析结果为空");
        assertThat(row.enableStatus)
            .as("失败时不得转 enabled（否则空文档会进检索面）")
            .isEqualTo("disabled");
        assertThat(countChunks(knowledgeId))
            .as("失败路径不得写任何分块")
            .isZero();
    }

    /**
     * 只有空白字符的纯文本（全角空格 + 制表 + 换行）：{@code trim()} 对全角空格无效，
     * 必须由「移除空白后长度为 0」这条判据兜住。
     */
    @Test
    @DisplayName("⭐ 只有空白字符的 txt（含全角空格 U+3000）⇒ FAILED")
    void whitespaceOnlyTextMarksFailed() throws Exception {
        Path file = tempFile(".txt");
        // U+3000 全角空格 + ASCII 空白：任何一个字符都不该被当成正文
        Files.writeString(file, "\u3000\u3000 \t\r\n\n  \u3000", StandardCharsets.UTF_8);
        String knowledgeId = insertKnowledgeWithFile("pending", 0, file);

        KnowledgeParseProcessor.Outcome outcome = runParse(file, knowledgeId);

        assertThat(outcome).isEqualTo(KnowledgeParseProcessor.Outcome.FAILED);
        assertThat(readKnowledge(knowledgeId).parseStatus).isEqualTo("failed");
        assertThat(countChunks(knowledgeId)).isZero();
    }

    // ── 用例 ②：正常文本 ⇒ COMPLETED（回归：护栏不得误伤正常路径）─────

    @Test
    @DisplayName("正常中文文本 ⇒ COMPLETED，parse_status=completed，有分块且转 enabled")
    void normalTextCompletes() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            body.append("第").append(i).append("段：这是一段用于验证空正文护栏没有误伤正常路径的中文正文内容。\n\n");
        }
        Path file = tempFile(".txt");
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
        String knowledgeId = insertKnowledgeWithFile("pending", 0, file);

        KnowledgeParseProcessor.Outcome outcome = runParse(file, knowledgeId);

        assertThat(outcome)
            .as("正常文本必须照旧 COMPLETED —— 护栏只对「无可提取文本」生效")
            .isEqualTo(KnowledgeParseProcessor.Outcome.COMPLETED);
        // 摘要后处理恒开（源侧 SummaryEnabled: true）⇒ 解析后文档先进入 finalizing，
        // 这里释放那 1 个槽位（等价于 worker 消费完摘要任务），才能看到最终 completed。
        assertThat(stateRepository.finalizeSubtask(knowledgeId).promoted())
            .as("有文本分块 ⇒ 应当派发了 1 个摘要槽位；释放后必须提升为 completed")
            .isTrue();
        Row row = readKnowledge(knowledgeId);
        assertThat(row.parseStatus).isEqualTo("completed");
        assertThat(row.errorMessage).isEmpty();
        assertThat(row.enableStatus)
            .as("正常路径仍必须转 enabled（检索可见性的唯一开关）")
            .isEqualTo("enabled");
        assertThat(countChunks(knowledgeId))
            .as("正常文本必须产出分块")
            .isPositive();
    }

    // ── 用例 ③：只有图片无文字 ⇒ 放行 ─────────────────────────────────

    /**
     * 纯图文档（扫描件/照片）：正文为空但解析器明确产出了图片 ⇒ 仍然放行，
     * 由图片理解链（caption + OCR）补文字。日志必须写明「正文为空但有 N 张图」。
     */
    @Test
    @DisplayName("⭐ 正文为空但有图片 ⇒ 放行（COMPLETED），不让纯图文档被判失败")
    void imagesOnlyPasses() throws Exception {
        Path image = tempFile(".png");
        Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        Path doc = tempFile(".imgonly");
        Files.writeString(doc, "", StandardCharsets.UTF_8);

        KbParserRegistry registry = new KbParserRegistry(
            List.of(new ImageOnlyStubParser(image), new KbTextParser()));
        KnowledgeParseProcessor processor = newProcessor(registry);
        String knowledgeId = insertKnowledgeWithFile("pending", 0, doc);

        KnowledgeParseProcessor.Outcome outcome = processor.processParse(taskOf(knowledgeId));

        assertThat(outcome)
            .as("⭐⭐ 有图片就不是「空文档」：图片理解链会补文字，不得判失败")
            .isEqualTo(KnowledgeParseProcessor.Outcome.COMPLETED);
        Row row = readKnowledge(knowledgeId);
        assertThat(row.parseStatus).isEqualTo("completed");
        assertThat(row.errorMessage).isEmpty();
    }

    // ── 判据本身：空白判定的真值表（纯函数，不起 DB）──────────────────

    /**
     * 空白判定必须覆盖到「看起来非空、实际没有任何文字」的字符：
     * 全角空格（U+3000）、不换行空格（U+00A0）。{@code trim()} 对这两类都无效。
     *
     * <p>⚠️ 这条不是重复用例 ①：① 证明链路接上了，这条证明<b>判据本身</b>没漏字符。
     */
    @Test
    @DisplayName("hasNoExtractableText 真值表：全角空格/不换行空格判空，含文字的串判非空")
    void blankJudgementTruthTable() {
        assertThat(KnowledgeParseProcessor.hasNoExtractableText(null)).isTrue();
        assertThat(KnowledgeParseProcessor.hasNoExtractableText("")).isTrue();
        assertThat(KnowledgeParseProcessor.hasNoExtractableText("   \t\r\n")).isTrue();
        assertThat(KnowledgeParseProcessor.hasNoExtractableText("\u3000\u3000")).isTrue();
        assertThat(KnowledgeParseProcessor.hasNoExtractableText("\u00A0")).isTrue();
        assertThat(KnowledgeParseProcessor.hasNoExtractableText("a")).isFalse();
        assertThat(KnowledgeParseProcessor.hasNoExtractableText("  \u3000 甲 \u3000 ")).isFalse();
    }

    // ── 支撑 ────────────────────────────────────────────────────────

    private static KnowledgeParseProcessor.Outcome runParse(Path file, String knowledgeId) {
        // Office + 文本：docx 夹具走真实 KbOfficeParser，txt 夹具走 KbTextParser
        KbParserRegistry registry = new KbParserRegistry(
            List.of(new KbOfficeParser(), new KbTextParser()));
        return newProcessor(registry).processParse(taskOf(knowledgeId));
    }

    /**
     * 直接构造编排器（与生产同一套真实组件）：
     * 真实 sqlite 检索引擎 + 真实分块写入 + 真实状态机，只有「模型」一侧传 null（不配嵌入模型）。
     */
    private static KnowledgeParseProcessor newProcessor(KbParserRegistry registry) {
        KbIndexWriter indexWriter = new KbIndexWriter(
            embeddingMapper, chunkMapper, new NoopEmbeddingPort(), ds);
        KbRetrievalEngineRegistry engineRegistry = new KbRetrievalEngineRegistry(
            List.<KbRetrievalEngine>of(new KbSqliteRetrievalEngine(
                new KbHybridSearcher(chunkMapper, embeddingMapper, null, ds),
                indexWriter, embeddingMapper, ds)));
        return new KnowledgeParseProcessor(
            knowledgeMapper, knowledgeBaseMapper, registry, stateRepository,
            new KbChunkWriter(chunkMapper), indexWriter,
            null,   // 全局默认模型：本测试不涉及，传 null 走「未配置」
            engineRegistry,
            new KbPostProcessStage(chunkMapper, stateRepository, queue));
    }

    private static KbIngestTaskRecord taskOf(String knowledgeId) {
        KbIngestTaskRecord task = new KbIngestTaskRecord();
        task.setId(1L);
        task.setKnowledgeId(knowledgeId);
        task.setAttempt(0);
        task.setTaskType(KbIngestTaskRecord.TYPE_PARSE);
        task.setState(KbIngestTaskRecord.STATE_RUNNING);
        return task;
    }

    private static Path tempFile(String suffix) throws Exception {
        return Files.createTempFile("kb-empty-", suffix);
    }

    /** 真实空 docx（无任何段落）：Tika 抽不出文本。 */
    private static Path writeEmptyDocx() throws Exception {
        Path file = tempFile(".docx");
        try (XWPFDocument doc = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            doc.write(out);
        }
        return file;
    }

    private static String insertKnowledgeWithFile(String parseStatus, int pending, Path file)
        throws Exception {
        String baseId = UUID.randomUUID().toString();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "INSERT INTO kb_knowledge_base (id, name, type, chunking_config, "
                    + "image_processing_config, cos_config, vlm_config, "
                    + "embedding_model_id, summary_model_id) VALUES ('" + baseId + "', '测试库', 'document', "
                    + "'{\"chunk_size\": 512, \"chunk_overlap\": 80, "
                    + "\"split_markers\": [\"\\n\\n\", \"\\n\", \"。\"]}', '{}', '{}', '{}', '', '')");
        }
        String id = UUID.randomUUID().toString();
        String path = file.toString().replace("'", "''");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                // enable_status 显式写 disabled（模拟创建路径）：否则 DDL 默认 enabled 会让
                // 「失败时不得转 enabled」永远成立（假绿）。
                "INSERT INTO kb_knowledge (id, knowledge_base_id, type, title, source, "
                    + "parse_status, pending_subtasks_count, file_path, parse_attempt, enable_status) "
                    + "VALUES ('" + id + "', '" + baseId + "', 'document', '测试文档', 'manual', '"
                    + parseStatus + "', " + pending + ", '" + path + "', 0, 'disabled')");
        }
        return id;
    }

    private static Row readKnowledge(String knowledgeId) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT parse_status, IFNULL(error_message, ''), enable_status "
                     + "FROM kb_knowledge WHERE id = '" + knowledgeId + "'")) {
            if (!rs.next()) {
                throw new IllegalStateException("知识条目不存在: " + knowledgeId);
            }
            return new Row(rs.getString(1), rs.getString(2), rs.getString(3));
        }
    }

    private static int countChunks(String knowledgeId) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*) FROM kb_chunk WHERE knowledge_id = '" + knowledgeId
                     + "' AND deleted = 0")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private record Row(String parseStatus, String errorMessage, String enableStatus) {
    }

    /** 本测试不配嵌入模型 ⇒ 该端口不会被调用。 */
    private static final class NoopEmbeddingPort implements KbEmbeddingPort {
        @Override
        public List<float[]> embed(String modelId, List<String> inputs) {
            throw new IllegalStateException("测试不应调用嵌入服务");
        }
    }

    /**
     * 纯图夹具解析器：正文恒为空、图片列表非空。
     *
     * <p>为什么用桩而不是真实扫描件 docx：真实 docx 里只要有图，Tika 会写出
     * {@code ![image]} 占位 —— 文本就不为空了，测不到「正文为空 + 有图」这条分支。
     * {@code @Order(1)} 必须压过兜底的 {@link KbTextParser}(30)，否则 .imgonly 会被文本解析器抢走。
     */
    @Order(1)
    static final class ImageOnlyStubParser implements KbDocumentParser {

        private final Path imagePath;

        ImageOnlyStubParser(Path imagePath) {
            this.imagePath = imagePath;
        }

        @Override
        public boolean supports(String fileName, String mimeType) {
            return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".imgonly");
        }

        @Override
        public KbParsedDocument parse(Path file, KbParseContext ctx) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put(KbParsedDocument.META_PARSER, name());
            meta.put(KbParsedDocument.META_FILE_NAME, file.getFileName().toString());
            meta.put(KbParsedDocument.META_TEXT_LENGTH, "0");
            return new KbParsedDocument("", List.of(
                KbImageRef.of(0, imagePath.toString(), KbImageRef.KIND_OFFICE_EMBEDDED)), meta);
        }
    }
}
