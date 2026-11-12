package com.nexusai.kb.app.image;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.config.LocalFileStorage;
import com.nexusai.kb.app.ingest.KbChunkWriter;
import com.nexusai.kb.app.parser.KbParseContext;
import com.nexusai.kb.app.parser.KbVisionCaller;
import com.nexusai.kb.app.qa.KbMergeStage;
import com.nexusai.kb.app.retrieval.KbEmbeddingPort;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.repo.KbTimeFormat;
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

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片独立子块（V-E5）· {@code image_caption} / {@code image_ocr} 全链路。
 *
 * <h2>WHY（这条改动最容易「看起来做了、实际无效」）</h2>
 * <ol>
 *   <li><b>写了子块但没进索引</b> ⇒ 图内文字躺在库里搜不到（与改动前「拼正文块」一样无效）；</li>
 *   <li><b>子块没有 parent_chunk_id</b> ⇒ 命中后只能拿到一句 caption，无法展开正文；</li>
 *   <li><b>同一张图被多个块引用</b> ⇒ 不去重会写多份、检索结果重复；</li>
 *   <li><b>重解析 / 重试不清理旧子块</b> ⇒ 行数翻倍、越积越多；</li>
 *   <li><b>开关关闭不回落旧行为</b> ⇒ caption/OCR 彻底不可检索（静默功能丢失）。</li>
 * </ol>
 *
 * <p>⛔ 不启 Spring（{@code @SpringBootTest} 会迁移用户真库）；
 * 用 Flyway 在 {@code target/flex-dbtest/flex.db} 上建<b>测试库</b>，
 * 真跑 SQLite + FTS5（读代码看不出 trigram/FTS 的行为）。
 */
@DisplayName("[E5] 图片独立子块 image_caption / image_ocr")
class KbImageSubChunkTest {

    private static final String KB_ID = "kb-image-sub";
    private static final String DOC_ID = "doc-image-sub";
    private static final String MODEL_ID = "m-image-sub";

    private static KbChunkMapper chunkMapper;
    private static KbEmbeddingMapper embeddingMapper;
    private static Path dbPath;
    private static SQLiteDataSource ds;
    private static Path tmp;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dbPath = MybatisFlexDbTestSupport.sharedDbPath();
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
            KbChunkMapper.class, KbEmbeddingMapper.class, KbKnowledgeMapper.class,
            KbKnowledgeBaseMapper.class, KbIngestTaskMapper.class);
        chunkMapper = MybatisFlexBootstrap.getInstance().getMapper(KbChunkMapper.class);
        embeddingMapper = MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class);
        tmp = Files.createTempDirectory("kb-image-sub-");
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_embedding");
            s.executeUpdate("DELETE FROM kb_chunk_fts");
            s.executeUpdate("DELETE FROM kb_chunk");
        }
    }

    // ── ① 每张图两条子块，parent 正确 ────────────────────────────────

    @Test
    @DisplayName("① 2 张图 ⇒ 2 个 image_caption + 2 个 image_ocr，parent_chunk_id = 所属正文块")
    void writesCaptionAndOcrSubChunksPerImage() throws Exception {
        createImageFiles("img0.png", "img1.png");
        String parentId = "p-" + UUID.randomUUID();
        insertChunk(parentId, 0, KbChunk.CHUNK_TYPE_TEXT, "父块正文",
            imageInfo("img0.png", "img1.png"), 10, 20, "loc-parent");

        int done = service().enrichKnowledge(DOC_ID, null);

        assertThat(done).as("两张图都成功").isEqualTo(2);
        Map<String, List<Row>> byType = subChunkRows();
        assertThat(byType.get(KbChunk.CHUNK_TYPE_IMAGE_CAPTION))
            .as("2 张图 ⇒ 2 条 image_caption（⛔ 不是 0，也不是 4）").hasSize(2);
        assertThat(byType.get(KbChunk.CHUNK_TYPE_IMAGE_OCR))
            .as("2 张图 ⇒ 2 条 image_ocr").hasSize(2);
        for (Row r : allRows()) {
            assertThat(r.parentChunkId)
                .as("子块必须挂到所属正文块（缺了它命中后无法展开正文）")
                .isEqualTo(parentId);
            assertThat(r.startAt).as("start_at 复制父块区间").isEqualTo(10);
            assertThat(r.endAt).as("end_at 复制父块区间").isEqualTo(20);
            assertThat(r.sourceLocators).as("source_locators 复制父块").isEqualTo("loc-parent");
            assertThat(r.metadata).as("metadata 必须带 image_key（合并阶段带回前端）")
                .contains("image_key");
        }
        assertThat(byType.get(KbChunk.CHUNK_TYPE_IMAGE_CAPTION))
            .allSatisfy(r -> assertThat(r.content).contains("第 1 张图"))
            .isNotEmpty();
    }

    // ── ② 同一张图被两个块引用 ⇒ 只写一份 ──────────────────────────

    @Test
    @DisplayName("⭐⭐ 同一张图被两个正文块引用 ⇒ 只写一份，挂第一个块（chunk_index 最小）")
    void duplicateImageAcrossChunksWrittenOnce() throws Exception {
        createImageFiles("same.png");
        String firstId = "p-first-" + UUID.randomUUID();
        String secondId = "p-second-" + UUID.randomUUID();
        insertChunk(firstId, 0, KbChunk.CHUNK_TYPE_TEXT, "第一个引用它的块",
            imageInfo("same.png"), 0, 5, null);
        insertChunk(secondId, 3, KbChunk.CHUNK_TYPE_TEXT, "第二个引用它的块",
            imageInfo("same.png"), 20, 25, null);

        CountingVision vision = new CountingVision();
        service(vision).enrichKnowledge(DOC_ID, null);

        assertThat(allRows()).as("同一张图只写 caption + ocr 各一条").hasSize(2);
        assertThat(allRows()).allSatisfy(r ->
            assertThat(r.parentChunkId).as("挂第一个块（chunk_index 0）").isEqualTo(firstId));
        assertThat(vision.calls.get())
            .as("⚠️ 模型调用仍按「块内出现次数」走（2 个块 × caption+OCR = 4）——"
                + "第二个块的 image_info 也要拿到描述/OCR，否则同一张图在两处显示不一致；"
                + "去重只发生在<b>子块写入</b>这一层")
            .isEqualTo(4);
    }

    // ── ③ 检索命中 image_ocr ⇒ 合并展开父块正文 ─────────────────────

    @Test
    @DisplayName("⭐⭐ 检索命中 image_ocr 子块，合并后拿到父块正文并保留 imageKey")
    void searchHitsOcrSubChunkAndMergeExpandsParent() throws Exception {
        createImageFiles("ocr.png");
        String parentId = "p-ocr-" + UUID.randomUUID();
        insertChunk(parentId, 5, KbChunk.CHUNK_TYPE_TEXT,
            "父块正文：某项指标为 88.9%，适用于农村生活污水处理设施",
            imageInfo("ocr.png"), 0, 0, null);

        // ⭐ 子块必须由**生产代码**写入（不是测试手插）——否则「子块不写 parent_chunk_id」
        //    这类变异在用例里根本不可见
        service().enrichKnowledge(DOC_ID, null);
        String ocrId = allRows().stream()
            .filter(r -> KbChunk.CHUNK_TYPE_IMAGE_OCR.equals(r.chunkType))
            .findFirst().orElseThrow(() -> new AssertionError("没有写出 image_ocr 子块"))
            .id;

        KbIndexWriter indexWriter = indexWriter(true);
        indexWriter.reindexKnowledge(DOC_ID, KB_ID, MODEL_ID);

        KbHybridSearcher searcher = new KbHybridSearcher(chunkMapper, embeddingMapper,
            new FakeEmbeddingPort(), ds);
        List<KbHybridSearcher.Hit> hits = searcher.search(KB_ID, "污水排放限值", 10, MODEL_ID);
        assertThat(hits).as("FTS/向量必须能命中 image_ocr 子块本身")
            .anySatisfy(h -> assertThat(h.chunkId()).isEqualTo(ocrId));

        List<KbMergeStage.MergedChunk> merged = new KbMergeStage(chunkMapper).merge(hits, 10);
        // ⚠️ 必须定位「由 image_ocr 子块变成的那条结果」——只断言「结果里某条含父块正文」
        //    会被「父块本身也被召回」那一行混过去（变异时不红）。
        KbMergeStage.MergedChunk viaImage = merged.stream()
            .filter(c -> ocrId.equals(c.chunkId()) || c.subChunkIds().contains(ocrId))
            .findFirst()
            .orElseThrow(() -> new AssertionError("合并结果里找不到来自 image_ocr 子块的片段"));
        assertThat(viaImage.content())
            .as("⛔ 不能只返回一句 OCR，必须展开父块正文（父块指针缺失时这一条会红）")
            .contains("父块正文：某项指标为 88.9%");
        assertThat(viaImage.imageKey())
            .as("命中图片子块时带回 imageKey 供前端显示原图")
            .endsWith("/ocr.png");
        assertThat(merged).allSatisfy(c ->
            assertThat(c.content()).as("同一父块只出现一次，不能重复拼正文")
                .doesNotContain("父块正文：某项指标为 88.9%\n\n父块正文：某项指标为 88.9%"));
    }

    // ── ④ 重解析 / 重试清理旧子块 ──────────────────────────────────

    @Test
    @DisplayName("⭐⭐ 重解析（replaceChunks）删掉旧子块；重复 enrich 不写重复行")
    void replaceChunksCleansOldSubChunksAndReenrichDoesNotDuplicate() throws Exception {
        createImageFiles("img0.png", "img1.png");
        insertChunk("p-replace-" + UUID.randomUUID(), 0, KbChunk.CHUNK_TYPE_TEXT,
            "父块正文", imageInfo("img0.png", "img1.png"), 0, 4, null);

        service().enrichKnowledge(DOC_ID, null);
        assertThat(allRows()).hasSize(4);

        // 再跑一次（重试 / 重新理解）⇒ 必须先删旧再写，行数不翻倍
        service().enrichKnowledge(DOC_ID, null);
        assertThat(allRows()).as("重复 enrich 不能变成 8 条").hasSize(4);

        // 重解析：整篇文档的旧块（含 image_* 子块）都必须被逻辑删干净
        KbKnowledgeRecord knowledge = new KbKnowledgeRecord();
        knowledge.setId(DOC_ID);
        knowledge.setKnowledgeBaseId(KB_ID);
        new KbChunkWriter(chunkMapper).replaceChunks(knowledge,
            List.of(new KbChunk("重解析后的正文", "", 0, 0, 7)));

        assertThat(allRows()).as("重解析后不应残留可见的 image_* 子块").isEmpty();
        assertThat(countRows(false)).as("只剩重解析新写的 1 条正文块").isEqualTo(1);
        // ⚠️ 判据是「可见行不残留」，不是「物理删除」：本域统一逻辑删（deleted=1），
        //    因此 deleted=1 的行会累积（两次 enrich 各 4 条旧子块 + 重解析删掉的 5 条），
        //    这不影响检索（所有查询都带 deleted=0）。
        assertThat(countRows(true)).as("旧子块应被逻辑删（不是物理删）").isGreaterThanOrEqualTo(4);
    }

    // ── ⑤ 开关关闭 ⇒ 旧行为 ───────────────────────────────────────

    @Nested
    @DisplayName("开关 nexusai.knowledge.image.sub-chunks-enabled")
    class Switch {

        @Test
        @DisplayName("⭐⭐ 关闭 ⇒ 不写子块，caption/OCR 仍拼进正文块索引文本（旧行为）")
        void offFallsBackToIndexTextConcatenation() throws Exception {
            createImageFiles("img0.png");
            String parentId = "p-off-" + UUID.randomUUID();
            insertChunk(parentId, 0, KbChunk.CHUNK_TYPE_TEXT, "父块正文",
                imageInfo("img0.png"), 0, 4, null);

            KbImageEnrichService svc = service();
            svc.setImageSubChunksEnabled(false);
            svc.enrichKnowledge(DOC_ID, null);

            assertThat(allRows()).as("开关关闭 ⇒ 一条子块都不写").isEmpty();

            indexWriter(false).reindexKnowledge(DOC_ID, KB_ID, null);
            assertThat(ftsContent(parentId))
                .as("旧行为：图内文字必须仍在正文块的 FTS 索引文本里，否则检索不到")
                .contains("识别文字：污水排放限值 50mg/L")
                .contains("第 1 张图");
        }

        @Test
        @DisplayName("⭐⭐ 开启（默认）⇒ 正文块索引文本<b>不再</b>拼图内文字，由子块自己进索引")
        void onDoesNotDoubleIndexImageText() throws Exception {
            createImageFiles("img0.png");
            String parentId = "p-on-" + UUID.randomUUID();
            insertChunk(parentId, 0, KbChunk.CHUNK_TYPE_TEXT, "父块正文",
                imageInfo("img0.png"), 0, 4, null);

            service().enrichKnowledge(DOC_ID, null);
            assertThat(allRows()).hasSize(2);

            indexWriter(true).reindexKnowledge(DOC_ID, KB_ID, null);
            assertThat(ftsContent(parentId))
                .as("子块模式：正文块索引文本不能拼图内文字（否则同一段文字被索引两遍）")
                .doesNotContain("识别文字：污水排放限值 50mg/L");
            String ocrId = allRows().stream()
                .filter(r -> KbChunk.CHUNK_TYPE_IMAGE_OCR.equals(r.chunkType))
                .findFirst().orElseThrow().id;
            assertThat(ftsContent(ocrId))
                .as("子块必须自己进索引，否则「图片理解」等于白跑")
                .contains("识别文字：污水排放限值 50mg/L");
        }
    }

    // ── 组装 / 夹具 ─────────────────────────────────────────────────

    private KbImageEnrichService service() {
        return service(new CountingVision());
    }

    private KbImageEnrichService service(CountingVision vision) {
        KbImageStorage storage = new KbImageStorage((LocalFileStorage) null) {
            @Override
            public Path localPath(String storageKey) {
                return tmp.resolve(storageKey);
            }
        };
        return new KbImageEnrichService(chunkMapper, storage, vision);
    }

    private KbIndexWriter indexWriter(boolean subChunksEnabled) throws Exception {
        KbIndexWriter writer = new KbIndexWriter(embeddingMapper, chunkMapper,
            new FakeEmbeddingPort(), ds);
        Field f = KbIndexWriter.class.getDeclaredField("imageSubChunksEnabled");
        f.setAccessible(true);
        f.setBoolean(writer, subChunksEnabled);
        return writer;
    }

    private void createImageFiles(String... names) throws Exception {
        for (String name : names) {
            Files.write(tmp.resolve(name), new byte[]{1, 2, 3});
        }
    }

    /** {@code image_info} 夹具：文件名式占位 caption（真实 production 解析器给的就是这个）+ 空 OCR。 */
    private static String imageInfo(String... storageKeys) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < storageKeys.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String key = storageKeys[i];
            sb.append("{\"url\":\"/api/v1/kb/images/").append(KB_ID).append('/').append(key).append("\",")
                .append("\"original_url\":\"").append(key).append("\",")
                .append("\"caption\":\"").append(key).append("\",")
                .append("\"ocr_text\":\"\",")
                .append("\"attrs\":{\"storage_key\":\"").append(key).append("\"}}");
        }
        return sb.append(']').toString();
    }

    private String insertChunk(String id, int index, String type, String content,
                               String imageInfo, int startAt, int endAt, String sourceLocators) {
        KbChunkRecord row = new KbChunkRecord();
        row.setId(id);
        row.setKnowledgeBaseId(KB_ID);
        row.setKnowledgeId(DOC_ID);
        row.setContent(content);
        row.setChunkIndex(index);
        row.setIsEnabled(1);
        row.setStartAt(startAt);
        row.setEndAt(endAt);
        row.setChunkType(type);
        row.setImageInfo(imageInfo);
        row.setContextHeader("");
        row.setSourceLocators(sourceLocators);
        row.setSourceContent("");
        row.setContentRevision(0);
        row.setIndexStatus("ready");
        row.setLastEditorId("");
        row.setStatus(0);
        row.setFlags(1);
        row.setCreator("");
        row.setUpdater("");
        row.setDeleted(0);
        row.setTenantId(0L);
        String now = KbTimeFormat.now();
        row.setCreateTime(KbTimeFormat.parse(now));
        row.setUpdateTime(KbTimeFormat.parse(now));
        chunkMapper.insert(row);
        return id;
    }

    /** 读出全部可见 image_* 子块（绕过 Flex 逻辑删，直查 deleted=0）。 */
    private Map<String, List<Row>> subChunkRows() throws Exception {
        Map<String, List<Row>> out = new LinkedHashMap<>();
        out.put(KbChunk.CHUNK_TYPE_IMAGE_CAPTION, new ArrayList<>());
        out.put(KbChunk.CHUNK_TYPE_IMAGE_OCR, new ArrayList<>());
        for (Row r : allRows()) {
            out.computeIfAbsent(r.chunkType, k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private List<Row> allRows() throws Exception {
        List<Row> out = new ArrayList<>();
        String sql = "SELECT id, chunk_type, content, parent_chunk_id, start_at, end_at,"
            + " source_locators, metadata FROM kb_chunk"
            + " WHERE knowledge_id = ? AND chunk_type IN (?, ?) AND deleted = 0"
            + " ORDER BY chunk_type, content";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, DOC_ID);
            ps.setString(2, KbChunk.CHUNK_TYPE_IMAGE_CAPTION);
            ps.setString(3, KbChunk.CHUNK_TYPE_IMAGE_OCR);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getString(7),
                        rs.getString(8)));
                }
            }
        }
        return out;
    }

    /** 统计 kb_chunk 行数（含已逻辑删）。 */
    private int countRows(boolean deleted) throws Exception {
        String sql = deleted
            ? "SELECT COUNT(*) FROM kb_chunk WHERE knowledge_id = ? AND deleted = 1"
            : "SELECT COUNT(*) FROM kb_chunk WHERE knowledge_id = ? AND deleted = 0";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, DOC_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String ftsContent(String chunkId) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT content FROM kb_chunk_fts WHERE chunk_id = ? AND knowledge_id = ?")) {
            ps.setString(1, chunkId);
            ps.setString(2, DOC_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private record Row(String id, String chunkType, String content, String parentChunkId,
                       int startAt, int endAt, String sourceLocators, String metadata) {
    }

    /** 视觉桩：caption 回真实描述（不能是文件名形态）、OCR 回固定文字。 */
    private static final class CountingVision implements KbVisionCaller {
        final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String describeImage(Path imageFile, String prompt, KbParseContext ctx) {
            throw new UnsupportedOperationException("生产代码应走 describeImageWithModel");
        }

        @Override
        public String describeImageWithModel(Path imageFile, String prompt, KbParseContext ctx,
                                             String modelIdOrName) {
            calls.incrementAndGet();
            return prompt == KbImagePrompts.OCR
                ? "识别文字：污水排放限值 50mg/L"
                : "第 1 张图：某场景的投入占比示意图";
        }
    }

    /** 假嵌入端口：确定性向量（同文本同向量）。 */
    private static final class FakeEmbeddingPort implements KbEmbeddingPort {
        @Override
        public List<float[]> embed(String modelId, List<String> inputs) {
            List<float[]> out = new ArrayList<>(inputs.size());
            for (String s : inputs) {
                out.add(new float[]{
                    s.length(),
                    s.contains("污水") ? 1f : 0f,
                    s.contains("农村") ? 1f : 0f
                });
            }
            return out;
        }
    }
}
