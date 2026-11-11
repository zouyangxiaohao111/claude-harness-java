package com.nexusai.kb.app.retrieval;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbEmbeddingRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbEmbeddingMapper;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 索引写入器 · 父块不进索引（对齐 WeKnora {@code knowledge_process.go:613/3402}）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：父子模式下父块与子块内容<b>高度重叠</b>
 * （子块就是父块的切片）。若父块也进向量 / FTS 索引：
 * <ol>
 *   <li>同一段内容被召回两次，结果里出现父子两份近乎相同的片段（用户看到「重复答案」）；</li>
 *   <li>父块没有子块精细，成块的粗粒度内容会挤掉子块的排名。</li>
 * </ol>
 * 而这条写错<b>不会报错</b>：索引照建，只是检索质量变差 —— 故用「回表数向量行 / FTS 行」钉死。
 *
 * <p>⚠️ 本类只跑本地 SQLite（Flyway 建表 + Flex mapper），⛔ 不用 {@code @SpringBootTest}。
 */
@DisplayName("[B0] 索引写入器 · 父块不进索引")
class KbIndexWriterParentSkipTest {

    private static KbChunkMapper chunkMapper;
    private static KbEmbeddingMapper embeddingMapper;
    private static Path dbPath;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
            .baselineOnMigrate(true).load().migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds, KbChunkMapper.class, KbEmbeddingMapper.class);
        chunkMapper = MybatisFlexBootstrap.getInstance().getMapper(KbChunkMapper.class);
        embeddingMapper = MybatisFlexBootstrap.getInstance().getMapper(KbEmbeddingMapper.class);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_chunk");
            s.executeUpdate("DELETE FROM kb_embedding");
            s.executeUpdate("DELETE FROM kb_chunk_fts");
        }
    }

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }

    /** 直接构造一行 chunk（只填本用例关心的列，其余按 DDL 默认语义显式给 NOT NULL 值）。 */
    private static KbChunkRecord chunk(String knowledgeId, int index, String type, String content) {
        KbChunkRecord row = new KbChunkRecord();
        row.setId(UUID.randomUUID().toString());
        row.setKnowledgeBaseId("kb-1");
        row.setKnowledgeId(knowledgeId);
        row.setContent(content);
        row.setChunkIndex(index);
        row.setIsEnabled(1);
        row.setStartAt(0);
        row.setEndAt(content.length());
        row.setChunkType(type);
        row.setContextHeader("");
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
        return row;
    }

    private KbIndexWriter writer() {
        return new KbIndexWriter(embeddingMapper, chunkMapper,
            (modelId, inputs) -> {
                throw new IllegalStateException("本用例走 modelId=null 分支，不该调用嵌入");
            },
            sqlite());
    }

    @Test
    @DisplayName("parent_text 块不进 FTS（只建关键词索引分支）")
    void parentTextSkippedInFtsOnlyPath() throws Exception {
        String knowledgeId = "k-parent-fts";
        chunk(knowledgeId, 0, "parent_text", "父块内容（不该进索引）");
        chunk(knowledgeId, 1, "text", "子块内容甲");
        chunk(knowledgeId, 2, "text", "子块内容乙");

        int indexed = writer().reindexKnowledge(knowledgeId, "kb-1", null);

        assertThat(indexed).as("只索引 2 个子块").isEqualTo(2);
        assertThat(ftsCount(knowledgeId)).as("FTS 只能有子块 2 行").isEqualTo(2);
        assertThat(ftsHasContent(knowledgeId, "父块内容（不该进索引）"))
            .as("父块正文绝不能出现在 FTS 里 ⇒ 否则关键词召回会直接命中父块")
            .isFalse();
    }

    @Test
    @DisplayName("父块 id 留在 existingIds ⇒ 不被当孤儿（口径一致，不误删向量）")
    void parentIdKeptInExistingIds() throws Exception {
        String knowledgeId = "k-parent-orphan";
        KbChunkRecord parent = chunk(knowledgeId, 0, "parent_text", "父块");
        chunk(knowledgeId, 1, "text", "子块");

        // 手工塞一行父块向量（模拟历史上误建 / 其它路径写入）
        KbEmbeddingRecord vec = new KbEmbeddingRecord();
        vec.setId(UUID.randomUUID().toString());
        vec.setKnowledgeBaseId("kb-1");
        vec.setKnowledgeId(knowledgeId);
        vec.setSourceId(parent.getId());
        vec.setSourceType("chunk");
        vec.setModelId("m");
        vec.setDimensions(2);
        vec.setVector(KbVectorCodec.encode(new float[]{0f, 1f}));
        vec.setContentHash("deadbeef");
        vec.setCreator("");
        vec.setUpdater("");
        vec.setDeleted(0);
        vec.setTenantId(0L);
        String now = KbTimeFormat.now();
        vec.setCreateTime(KbTimeFormat.parse(now));
        vec.setUpdateTime(KbTimeFormat.parse(now));
        embeddingMapper.insert(vec);

        // modelId=null 分支会走 purgeStaleVectors（enabledChunks 里没有父块 ⇒ hash 对不上）
        writer().reindexKnowledge(knowledgeId, "kb-1", null);

        long remaining = embeddingMapper.selectCountByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));
        assertThat(remaining)
            .as("父块 id 必须在 existingIds 里 ⇒ 不被孤儿清理删掉（删错是数据损失）")
            .isEqualTo(1);
    }

    private static int ftsCount(String knowledgeId) throws Exception {
        try (Connection c = sqlite().getConnection();
             var ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM kb_chunk_fts WHERE knowledge_id = ?")) {
            ps.setString(1, knowledgeId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean ftsHasContent(String knowledgeId, String needle) throws Exception {
        try (Connection c = sqlite().getConnection();
             var ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM kb_chunk_fts WHERE knowledge_id = ? AND content = ?")) {
            ps.setString(1, knowledgeId);
            ps.setString(2, needle);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
