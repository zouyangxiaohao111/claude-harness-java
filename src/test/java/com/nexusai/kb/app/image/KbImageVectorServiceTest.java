package com.nexusai.kb.app.image;

import com.mybatisflex.core.mybatis.Mappers;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbEmbeddingPort;
import com.nexusai.kb.app.retrieval.KbVectorCodec;
import com.nexusai.kb.repo.entity.KbImageEmbeddingRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbImageEmbeddingMapper;
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
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link KbImageVectorService} 的真 SQLite 单测（⛔ 不启 Spring、⛔ 不连真模型）。
 *
 * <p>守四条：写入口径（dim/vector/source）、按 content_hash 去重（⛔ 重复编码）、
 * 余弦排序、未配置时「空 + 原因」（⛔ 不返回假数据）。
 */
@DisplayName("图片向量通道")
class KbImageVectorServiceTest {

    private static final String KB = "kb-image-vector-test";
    private static final String KNOWLEDGE = "doc-image-vector-test";
    private static final String MODEL = "stub-embedding-model";

    private static SQLiteDataSource ds;
    private static KbImageEmbeddingMapper embeddingMapper;
    private static StubPort port;
    private static KbImageVectorService service;

    @BeforeAll
    static void initDb() throws Exception {
        Path db = Path.of("target", "flex-dbtest", "kb-image-vector.db");
        Files.createDirectories(db.getParent());
        // 本测试独占一个库文件（⛔ 不污染其它测试共享的 flex.db）
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-wal"));
        Files.deleteIfExists(Path.of(db + "-shm"));

        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db.toAbsolutePath()
            + "?journal_mode=WAL&foreign_keys=on&busy_timeout=5000");

        String migrations = Path.of("src", "main", "resources", "db", "migration")
            .toAbsolutePath().toString().replace('\\', '/');
        Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:" + migrations)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds,
            KbChunkMapper.class, KbImageEmbeddingMapper.class);

        KbChunkMapper chunkMapper = Mappers.ofMapperClass(KbChunkMapper.class);
        embeddingMapper = Mappers.ofMapperClass(KbImageEmbeddingMapper.class);
        port = new StubPort();
        service = new KbImageVectorService(chunkMapper, embeddingMapper, port, MODEL);
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM kb_image_embedding WHERE knowledge_base_id = ?")) {
                ps.setString(1, KB);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM kb_chunk WHERE knowledge_id = ?")) {
                ps.setString(1, KNOWLEDGE);
                ps.executeUpdate();
            }
        }
        port.calls.clear();
        port.vectors.clear();
        port.failTexts.clear();
    }

    @Test
    @DisplayName("写入：caption 与 OCR 分别落行，dim/vector/source 正确")
    void writesCaptionAndOcr() throws Exception {
        insertChunk("c-1", """
            [{"url":"img://a","caption":"方案总体布置图","ocr_text":"图A内文字"},
             {"url":"img://b","caption":"投资构成饼图","ocr_text":""}]
            """);
        port.vectors.put("方案总体布置图", new float[]{1f, 0f, 0f});
        port.vectors.put("图A内文字", new float[]{0.8f, 0.6f, 0f});
        port.vectors.put("投资构成饼图", new float[]{0f, 1f, 0f});

        KbImageVectorService.IndexOutcome outcome = service.indexKnowledge(KNOWLEDGE, KB, null);

        assertThat(outcome.configured()).isTrue();
        assertThat(outcome.images()).isEqualTo(2);
        assertThat(outcome.written()).isEqualTo(3);
        assertThat(outcome.skipped()).isZero();
        assertThat(outcome.failed()).isZero();

        List<KbImageEmbeddingRecord> rows = rows();
        assertThat(rows).hasSize(3);

        KbImageEmbeddingRecord captionA = row(rows, "img://a", KbImageVectorService.SOURCE_CAPTION);
        assertThat(captionA.getKnowledgeBaseId()).isEqualTo(KB);
        assertThat(captionA.getKnowledgeId()).isEqualTo(KNOWLEDGE);
        assertThat(captionA.getChunkId()).isEqualTo("c-1");
        assertThat(captionA.getModelId()).isEqualTo(MODEL);
        assertThat(captionA.getDim()).isEqualTo(3);
        assertThat(KbVectorCodec.decode(captionA.getVector()))
            .containsExactly(1f, 0f, 0f);
        assertThat(captionA.getContentHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(captionA.getCreatedAt())
            .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

        KbImageEmbeddingRecord ocrA = row(rows, "img://a", KbImageVectorService.SOURCE_OCR);
        assertThat(ocrA.getDim()).isEqualTo(3);
        assertThat(KbVectorCodec.decode(ocrA.getVector()))
            .containsExactly(0.8f, 0.6f, 0f);

        // 第二张图只有 caption（ocr 为空 ⇒ 不写空向量）
        assertThat(row(rows, "img://b", KbImageVectorService.SOURCE_CAPTION)).isNotNull();
    }

    @Test
    @DisplayName("⭐ 去重：content_hash 未变 ⇒ 第二次不重写、不重复调模型")
    void dedupeByContentHash() throws Exception {
        insertChunk("c-2", """
            [{"url":"img://same","caption":"同一张图的描述","ocr_text":""}]
            """);
        port.vectors.put("同一张图的描述", new float[]{1f, 0f, 0f});

        KbImageVectorService.IndexOutcome first = service.indexKnowledge(KNOWLEDGE, KB, null);
        assertThat(first.written()).isEqualTo(1);
        int callsAfterFirst = port.calls.size();
        String idAfterFirst = rows().get(0).getId();

        KbImageVectorService.IndexOutcome second = service.indexKnowledge(KNOWLEDGE, KB, null);

        assertThat(second.written()).isZero();
        assertThat(second.skipped()).isEqualTo(1);
        // ⭐ 关键：不仅行数不变，而且**没有再次调模型**、没有重写同一行
        assertThat(port.calls).hasSize(callsAfterFirst);
        assertThat(rows()).hasSize(1);
        assertThat(rows().get(0).getId()).isEqualTo(idAfterFirst);
    }

    @Test
    @DisplayName("文本变了 ⇒ 重算并覆盖（唯一键不允许并存两行）")
    void changedTextIsReencoded() throws Exception {
        insertChunk("c-3", """
            [{"url":"img://c","caption":"旧描述","ocr_text":""}]
            """);
        port.vectors.put("旧描述", new float[]{1f, 0f, 0f});
        service.indexKnowledge(KNOWLEDGE, KB, null);
        String oldHash = rows().get(0).getContentHash();

        insertChunk("c-3", """
            [{"url":"img://c","caption":"新描述","ocr_text":""}]
            """, true);
        port.vectors.put("新描述", new float[]{0f, 1f, 0f});
        KbImageVectorService.IndexOutcome outcome = service.indexKnowledge(KNOWLEDGE, KB, null);

        assertThat(outcome.written()).isEqualTo(1);
        List<KbImageEmbeddingRecord> rows = rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getContentHash()).isNotEqualTo(oldHash);
        assertThat(KbVectorCodec.decode(rows.get(0).getVector())).containsExactly(0f, 1f, 0f);
    }

    @Test
    @DisplayName("⭐ 查询：按余弦降序返回，topK 截断")
    void searchRanksByCosine() throws Exception {
        insertChunk("c-4", """
            [{"url":"img://a","caption":"方案总体布置图","ocr_text":"图A内文字"},
             {"url":"img://b","caption":"投资构成饼图","ocr_text":""}]
            """);
        port.vectors.put("方案总体布置图", new float[]{1f, 0f, 0f});
        port.vectors.put("图A内文字", new float[]{0.8f, 0.6f, 0f});
        port.vectors.put("投资构成饼图", new float[]{0f, 1f, 0f});
        // 查询向量与第一张图的 caption 同向
        port.vectors.put("布置方案怎么画", new float[]{1f, 0f, 0f});
        service.indexKnowledge(KNOWLEDGE, KB, null);

        List<KbImageVectorService.ImageVectorHit> hits =
            service.searchImageVectors(KB, "布置方案怎么画", 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).imageKey()).isEqualTo("img://a");
        assertThat(hits.get(0).source()).isEqualTo(KbImageVectorService.SOURCE_CAPTION);
        assertThat(hits.get(0).score()).isCloseTo(1.0, within(1e-6));
        assertThat(hits.get(1).imageKey()).isEqualTo("img://a");
        assertThat(hits.get(1).source()).isEqualTo(KbImageVectorService.SOURCE_OCR);
        assertThat(hits.get(1).score()).isCloseTo(0.8, within(1e-6));
    }

    @Test
    @DisplayName("⭐⭐ 未配置 ⇒ 空 + 原因；⛔ 不写假向量、不调模型")
    void unconfiguredReturnsEmptyWithReason() throws Exception {
        insertChunk("c-5", """
            [{"url":"img://x","caption":"某描述","ocr_text":"某些字"}]
            """);
        KbImageVectorService unconfigured = new KbImageVectorService(
            Mappers.ofMapperClass(KbChunkMapper.class), embeddingMapper, port, "");

        assertThat(unconfigured.isConfigured()).isFalse();
        KbImageVectorService.IndexOutcome outcome =
            unconfigured.indexKnowledge(KNOWLEDGE, KB, null);
        assertThat(outcome.configured()).isFalse();
        assertThat(outcome.written()).isZero();
        assertThat(rows()).isEmpty();
        assertThat(port.calls).isEmpty();

        KbImageVectorService.SearchOutcome search =
            unconfigured.searchImageVectorsWithReason(KB, "某描述", 5);
        assertThat(search.hits()).isEmpty();
        assertThat(search.reason()).contains("未配置");
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("空查询 / topK<=0 ⇒ 空 + 原因（不是「查过没命中」）")
    void emptyQueryReportsReason() {
        KbImageVectorService.SearchOutcome blank =
            service.searchImageVectorsWithReason(KB, "  ", 5);
        assertThat(blank.hits()).isEmpty();
        assertThat(blank.reason()).contains("查询文本为空");

        KbImageVectorService.SearchOutcome badTopK =
            service.searchImageVectorsWithReason(KB, "正常查询", 0);
        assertThat(badTopK.hits()).isEmpty();
        assertThat(badTopK.reason()).contains("topK");
    }

    @Test
    @DisplayName("单条编码失败 ⇒ 只跳过该条，其余照写（fail-soft）")
    void singleFailureDoesNotAbortOthers() throws Exception {
        insertChunk("c-6", """
            [{"url":"img://f1","caption":"会失败的描述","ocr_text":"正常文字"},
             {"url":"img://f2","caption":"另一张图","ocr_text":""}]
            """);
        port.failTexts.add("会失败的描述");
        port.vectors.put("正常文字", new float[]{0f, 1f, 0f});
        port.vectors.put("另一张图", new float[]{0f, 0f, 1f});

        KbImageVectorService.IndexOutcome outcome = service.indexKnowledge(KNOWLEDGE, KB, null);

        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(outcome.written()).isEqualTo(2);
        assertThat(row(rows(), "img://f1", KbImageVectorService.SOURCE_CAPTION)).isNull();
        assertThat(row(rows(), "img://f1", KbImageVectorService.SOURCE_OCR)).isNotNull();
        assertThat(row(rows(), "img://f2", KbImageVectorService.SOURCE_CAPTION)).isNotNull();
    }

    // ── 测试辅助 ────────────────────────────────────────────────────

    private static List<KbImageEmbeddingRecord> rows() {
        return embeddingMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_base_id = ?", KB));
    }

    private static KbImageEmbeddingRecord row(List<KbImageEmbeddingRecord> rows,
                                             String imageKey, String source) {
        for (KbImageEmbeddingRecord r : rows) {
            if (imageKey.equals(r.getImageKey()) && source.equals(r.getSource())) {
                return r;
            }
        }
        return null;
    }

    /** 插入/更新一个含图分块（原生 JDBC，避开实体审计监听器）。 */
    private static void insertChunk(String chunkId, String imageInfo) throws Exception {
        insertChunk(chunkId, imageInfo, false);
    }

    private static void insertChunk(String chunkId, String imageInfo, boolean replace) throws Exception {
        try (Connection c = ds.getConnection()) {
            if (replace) {
                try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM kb_chunk WHERE id = ?")) {
                    del.setString(1, chunkId);
                    del.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kb_chunk(id, knowledge_base_id, knowledge_id, content, chunk_index,"
                    + " start_at, end_at, image_info) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, chunkId);
                ps.setString(2, KB);
                ps.setString(3, KNOWLEDGE);
                ps.setString(4, "正文");
                ps.setInt(5, 0);
                ps.setInt(6, 0);
                ps.setInt(7, 2);
                ps.setString(8, imageInfo);
                ps.executeUpdate();
            }
        }
    }

    /** 确定性桩：按文本查表给向量；可指定某些文本抛异常。 */
    private static final class StubPort implements KbEmbeddingPort {

        final List<String> calls = new ArrayList<>();
        final Map<String, float[]> vectors = new HashMap<>();
        final List<String> failTexts = new ArrayList<>();

        @Override
        public List<float[]> embed(String modelId, List<String> inputs) {
            calls.addAll(inputs);
            List<float[]> out = new ArrayList<>(inputs.size());
            for (String text : inputs) {
                if (failTexts.contains(text)) {
                    throw new IllegalStateException("桩模型拒绝编码: " + text);
                }
                float[] v = vectors.get(text);
                out.add(v == null ? new float[]{0f, 0f, 1f} : v.clone());
            }
            return out;
        }
    }
}
