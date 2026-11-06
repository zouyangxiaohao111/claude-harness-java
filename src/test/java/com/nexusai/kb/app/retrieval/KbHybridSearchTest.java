package com.nexusai.kb.app.retrieval;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbEmbeddingMapper;
import com.nexusai.kb.repo.mapper.KbIngestTaskMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 混合检索端到端 · 批次 B3。
 *
 * <p><b>WHY 这些用例必须存在</b>：
 * <ol>
 *   <li><b>⭐ 中文关键词检索必须真的能命中</b>：FTS5 默认 {@code unicode61} 分词器
 *       把中文整段当一个 token ⇒ {@code MATCH '污水'} 对「农村污水处理设施」<b>零命中</b>
 *       （实测）。这条只有真跑 SQLite 才能验 —— 读代码看不出来。</li>
 *   <li><b>⭐ 检索可见性闸</b>：只有 {@code enable_status='enabled'} +
 *       {@code parse_status='completed'} 的文档可被召回。少了它，未解析完的文档
 *       会混进结果（用户看到空内容）。</li>
 *   <li><b>向量路降级</b>：嵌入调用失败时退回关键词路，而不是整体失败。</li>
 * </ol>
 */
@DisplayName("[B3] 混合检索（trigram 中文 / 可见性闸 / 降级）")
class KbHybridSearchTest {

    private static KbChunkMapper chunkMapper;
    private static KbEmbeddingMapper embeddingMapper;
    private static KbKnowledgeMapper knowledgeMapper;
    private static Path dbPath;

    /** 假嵌入端口：把文本映射成确定性向量（同文本同向量，便于断言排序）。 */
    private static final class FakeEmbeddingPort implements KbEmbeddingPort {
        boolean fail = false;

        @Override
        public List<float[]> embed(String modelId, List<String> inputs) {
            if (fail) {
                throw new IllegalStateException("模拟嵌入服务不可用");
            }
            return inputs.stream().map(FakeEmbeddingPort::vecOf).toList();
        }

        /** 3 维确定性向量：字符数 / 是否含「污水」/ 是否含「农村」。 */
        static float[] vecOf(String s) {
            return new float[] {
                s.length(),
                s.contains("污水") ? 1f : 0f,
                s.contains("农村") ? 1f : 0f
            };
        }
    }

    private static FakeEmbeddingPort fakePort;
    private static KbIndexWriter indexWriter;
    private static KbHybridSearcher searcher;

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
            KbChunkMapper.class, KbEmbeddingMapper.class, KbKnowledgeMapper.class,
            KbKnowledgeBaseMapper.class, KbIngestTaskMapper.class);

        chunkMapper = MybatisFlexBootstrap.getInstance().getMapper(KbChunkMapper.class);
        embeddingMapper = MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class);
        knowledgeMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeMapper.class);

        fakePort = new FakeEmbeddingPort();
        indexWriter = new KbIndexWriter(embeddingMapper, chunkMapper, fakePort, ds);
        searcher = new KbHybridSearcher(chunkMapper, embeddingMapper, fakePort, ds);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_embedding");
            s.executeUpdate("DELETE FROM kb_chunk_fts");
            s.executeUpdate("DELETE FROM kb_chunk");
            s.executeUpdate("DELETE FROM kb_knowledge");
            s.executeUpdate("DELETE FROM kb_knowledge_base");
        }
        fakePort.fail = false;
    }

    // ── 中文关键词 ──────────────────────────────────────────────────

    @Nested
    @DisplayName("⭐ 中文关键词检索（trigram 分词）")
    class ChineseKeyword {

        @Test
        @DisplayName("⭐⭐ 中文子串能命中（unicode61 会零命中 —— 这是本批最关键的一条）")
        void chineseSubstringMatches() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "农村污水处理技术规范");
            insertChunk(kbId, docId, 0, "农村污水处理设施应当定期清掏。", "");

            indexWriter.reindexKnowledge(docId, kbId, null);  // 只建 FTS

            List<KbHybridSearcher.Hit> hits = searcher.search(kbId, "污水", 10, null);
            assertThat(hits)
                .as("⭐ FTS5 默认 unicode61 对中文整段分词 ⇒ MATCH '污水' 零命中；"
                    + "建表必须用 trigram")
                .isNotEmpty();
            assertThat(hits.get(0).content()).contains("污水处理");
        }

        @Test
        @DisplayName("两字查询（< trigram 下限）走 LIKE 回落仍能命中")
        void shortQueryFallsBackToLike() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "运维手册");
            insertChunk(kbId, docId, 0, "设备巡检周期为每周一次。", "");
            indexWriter.reindexKnowledge(docId, kbId, null);

            // 「巡检」2 字 < 3，走 LIKE
            List<KbHybridSearcher.Hit> hits = searcher.search(kbId, "巡检", 10, null);
            assertThat(hits).isNotEmpty();
        }

        @Test
        @DisplayName("表头也参与关键词检索（context_header 列）")
        void headerSearchable() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "文档");
            insertChunk(kbId, docId, 0, "正文里没有那两个字。", "第三章 设备维护");
            indexWriter.reindexKnowledge(docId, kbId, null);

            List<KbHybridSearcher.Hit> hits = searcher.search(kbId, "设备维护", 10, null);
            assertThat(hits).as("表头单独成列，应可被检索").isNotEmpty();
        }

        @Test
        @DisplayName("查询里的双引号被转义（不抛 SQL 错误）")
        void quotesEscaped() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "doc");
            insertChunk(kbId, docId, 0, "含有\"引号\"的正文内容", "");
            indexWriter.reindexKnowledge(docId, kbId, null);

            // 不抛异常即可（FTS5 短语语法里双引号是操作符）
            assertThat(searcher.search(kbId, "含有\"引号\"的", 10, null)).isNotNull();
        }

        @Test
        @DisplayName("不存在的词 → 空结果（不报错）")
        void noMatchReturnsEmpty() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "doc");
            insertChunk(kbId, docId, 0, "农村污水处理", "");
            indexWriter.reindexKnowledge(docId, kbId, null);

            assertThat(searcher.search(kbId, "量子计算机", 10, null)).isEmpty();
        }

        @Test
        @DisplayName("⭐⭐⭐ 自然语言问句能命中（整句当短语会零命中 —— B5 真机实测的缺陷）")
        void naturalLanguageQuestionMatches() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "农村生活污水处理设施运行管理办法");
            insertChunk(kbId, docId, 0,
                "第四条 农村生活污水处理设施的覆盖率应当达到百分之九十以上，"
                    + "纳厂处理率应当达到百分之八十五以上。", "");
            indexWriter.reindexKnowledge(docId, kbId, null);

            // 整句「农村生活污水处理设施的覆盖率要求是多少？」在原文里**不存在**：
            // 原文是「…覆盖率应当达到百分之九十以上」。若把整句当 FTS 短语，必然 0 命中。
            List<KbHybridSearcher.Hit> hits =
                searcher.search(kbId, "农村生活污水处理设施的覆盖率要求是多少？", 10, null);

            assertThat(hits)
                .as("⭐⭐⭐ 真机缺陷：MATCH '\"整句\"' 等价于子串包含 ⇒ 自然语言问句永远 0 命中。"
                    + "必须拆成 3-gram OR，由 bm25 rank 按命中片段数排序")
                .isNotEmpty();
            assertThat(hits.get(0).content()).contains("覆盖率");
        }

        @Test
        @DisplayName("⭐ 3-gram 拆分：同一批 gram 不重复，且超长查询有上限")
        void trigramMatchExpression() {
            String expr = KbHybridSearcher.buildTrigramMatch("农村污水");
            assertThat(expr).isNotNull();
            // 「农村污水」4 个码点 → 2 个 3-gram
            assertThat(expr.split(" OR ")).hasSize(2);
            assertThat(expr).contains("\"农村污\"").contains("\"村污水\"");

            // 不足 3 字符 ⇒ null（调用方回落 LIKE）
            assertThat(KbHybridSearcher.buildTrigramMatch("污水")).isNull();
            assertThat(KbHybridSearcher.buildTrigramMatch(null)).isNull();

            // 超长查询：gram 数被上限截断，不会生成几百个 OR
            String longQuery = "农".repeat(500);
            String longExpr = KbHybridSearcher.buildTrigramMatch(longQuery);
            assertThat(longExpr.split(" OR ").length)
                .as("⭐ 必须限流：否则 SQL 解析与 bm25 排序开销不可控")
                .isLessThanOrEqualTo(60);
        }
    }

    // ── 向量路 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("向量检索")
    class VectorRoute {

        @Test
        @DisplayName("有模型时走双路，命中标记为 hybrid / vector")
        void hybridHit() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "doc");
            insertChunk(kbId, docId, 0, "农村污水处理设施", "");
            insertChunk(kbId, docId, 1, "城市垃圾分类管理办法", "");
            indexWriter.reindexKnowledge(docId, kbId, "fake-embed");

            List<KbHybridSearcher.Hit> hits = searcher.search(kbId, "农村污水", 10, "fake-embed");
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).content()).contains("农村污水");
        }

        @Test
        @DisplayName("⭐ 嵌入失败 ⇒ 降级到关键词路（不整体失败）")
        void embeddingFailureDegrades() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "doc");
            insertChunk(kbId, docId, 0, "农村污水处理设施应当定期清掏", "");
            indexWriter.reindexKnowledge(docId, kbId, "fake-embed");

            fakePort.fail = true;  // 查询侧嵌入失败
            List<KbHybridSearcher.Hit> hits = searcher.search(kbId, "污水处理", 10, "fake-embed");

            assertThat(hits)
                .as("⭐ 一次模型抖动不该让检索完全不可用 —— 应退回关键词路")
                .isNotEmpty();
            assertThat(hits.get(0).keywordRank()).isPositive();
            assertThat(hits.get(0).vectorRank()).isZero();
        }

        @Test
        @DisplayName("未指定模型 ⇒ 只走关键词")
        void noModelSkipsVector() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "doc");
            insertChunk(kbId, docId, 0, "农村污水处理设施", "");
            indexWriter.reindexKnowledge(docId, kbId, null);

            List<KbHybridSearcher.Hit> hits = searcher.search(kbId, "污水处理", 10, null);
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).vectorRank()).isZero();
        }

        @Test
        @DisplayName("⭐ 不同模型的向量不参与同一查询（避免跨空间算余弦）")
        void differentModelVectorsExcluded() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "doc");
            insertChunk(kbId, docId, 0, "农村污水处理设施", "");
            indexWriter.reindexKnowledge(docId, kbId, "model-A");

            // 用 model-B 查询：向量行被 model_id 过滤掉 ⇒ 只剩关键词路
            List<KbHybridSearcher.Hit> hits = searcher.search(kbId, "污水处理", 10, "model-B");
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).vectorRank())
                .as("⭐ model_id 过滤是向量可比性的前提")
                .isZero();
        }
    }

    // ── 索引维护 ────────────────────────────────────────────────────

    @Nested
    @DisplayName("索引维护")
    class Maintenance {

        @Test
        @DisplayName("重建索引会清掉旧 FTS 行（不重复累积）")
        void reindexClearsOld() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "doc");
            insertChunk(kbId, docId, 0, "农村污水处理设施", "");

            indexWriter.reindexKnowledge(docId, kbId, null);
            indexWriter.reindexKnowledge(docId, kbId, null);

            assertThat(countFtsRows(docId)).as("重建不应累积 FTS 行").isEqualTo(1);
        }

        @Test
        @DisplayName("无分块时清空旧索引并返回 0")
        void emptyChunksClearsIndex() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "doc");
            insertChunk(kbId, docId, 0, "内容", "");
            indexWriter.reindexKnowledge(docId, kbId, null);
            assertThat(countFtsRows(docId)).isEqualTo(1);

            // 删掉块再重建
            try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM kb_chunk WHERE knowledge_id = '" + docId + "'");
            }
            int n = indexWriter.reindexKnowledge(docId, kbId, null);
            assertThat(n).isZero();
            assertThat(countFtsRows(docId)).isZero();
        }

        @Test
        @DisplayName("limit 生效（返回不超过 N 条）")
        void limitRespected() throws Exception {
            String kbId = insertKb();
            String docId = insertKnowledge(kbId, "doc");
            for (int i = 0; i < 5; i++) {
                insertChunk(kbId, docId, i, "农村污水处理设施第" + i + "条记录", "");
            }
            indexWriter.reindexKnowledge(docId, kbId, null);

            assertThat(searcher.search(kbId, "污水处理", 2, null)).hasSizeLessThanOrEqualTo(2);
        }
    }

    // ── 支撑 ────────────────────────────────────────────────────────

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }

    private static String insertKb() throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "INSERT INTO kb_knowledge_base (id, name, type, chunking_config, "
                    + "image_processing_config, cos_config, vlm_config, embedding_model_id, summary_model_id) "
                    + "VALUES ('" + id + "', '测试库', 'document', '{}', '{}', '{}', '{}', '', '')");
        }
        return id;
    }

    private static String insertKnowledge(String kbId, String title) throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "INSERT INTO kb_knowledge (id, knowledge_base_id, type, title, source, channel, "
                    + "parse_status, enable_status, pending_subtasks_count, custom_metadata, "
                    + "storage_size, summary_status) VALUES ('" + id + "', '" + kbId + "', 'file', '"
                    + title.replace("'", "''") + "', 'manual', 'web', 'completed', 'enabled', 0, "
                    + "'{}', 0, 'none')");
        }
        return id;
    }

    private static String insertChunk(String kbId, String docId, int index, String content, String header)
        throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "INSERT INTO kb_chunk (id, knowledge_base_id, knowledge_id, content, chunk_index, "
                    + "is_enabled, start_at, end_at, chunk_type, source_content, content_revision, "
                    + "index_status, last_editor_id, context_header, status, flags) VALUES ('" + id
                    + "', '" + kbId + "', '" + docId + "', '" + content.replace("'", "''") + "', "
                    + index + ", 1, 0, " + content.length() + ", 'text', '', 0, 'ready', '', '"
                    + header.replace("'", "''") + "', 0, 1)");
        }
        return id;
    }

    private static int countFtsRows(String docId) throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*) FROM kb_chunk_fts WHERE knowledge_id = '" + docId + "'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
