package com.nexusai.kb.app.chunk;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbAuditLogRecord;
import com.nexusai.kb.repo.mapper.KbAuditLogMapper;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbChunkRevisionMapper;
import com.nexusai.kb.repo.mapper.KbEmbeddingMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
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
 * 分块列表 · 默认不含父块（对齐源站 {@code handler/chunk.go:135}）。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：父块与子块共用 {@code chunk_index}、正文高度重叠。
 * 列表若不滤父块，「按 chunk_index 顺序拼回文档」的三个消费点会输出双份正文：
 * <ul>
 *   <li>{@code ChunkController#list} —— 分块管理列表（用户看到每条都出现两遍）</li>
 *   <li>{@code KnowledgeExtController#preview} —— 预览正文被复制两遍</li>
 *   <li>{@code KbReadDocumentTool#readDoc} —— agent 读文档时重复上下文，白烧 token</li>
 * </ul>
 * 这三处都读 {@link KbChunkService#list(String, boolean)}，故在这一层钉住即可。
 *
 * <p>⚠️ 只跑本地 SQLite（Flyway 建表 + Flex mapper），⛔ 不用 {@code @SpringBootTest}。
 */
@DisplayName("[B0] 分块列表 · 默认不含父块（含 includeParents 开关）")
class KbChunkServiceListParentFilterTest {

    private static KbChunkMapper chunkMapper;
    private static KbKnowledgeMapper knowledgeMapper;
    private static Path dbPath;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
            .baselineOnMigrate(true).load().migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds, KbChunkMapper.class, KbKnowledgeMapper.class,
            KbKnowledgeBaseMapper.class, KbChunkRevisionMapper.class, KbAuditLogMapper.class,
            KbEmbeddingMapper.class);
        chunkMapper = MybatisFlexBootstrap.getInstance().getMapper(KbChunkMapper.class);
        knowledgeMapper = MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeMapper.class);
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_chunk");
            s.executeUpdate("DELETE FROM kb_knowledge");
        }
    }

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }

    private KbChunkService service() {
        return new KbChunkService(chunkMapper,
            MybatisFlexBootstrap.getInstance().getMapper(KbChunkRevisionMapper.class),
            knowledgeMapper,
            MybatisFlexBootstrap.getInstance().getMapper(KbKnowledgeBaseMapper.class),
            null,   // KbIndexWriter：列表不建索引
            null,   // KbActivityService：列表不写审计
            new org.springframework.jdbc.core.JdbcTemplate(sqlite()),
            null);
    }

    /**
     * 造一条 kb_knowledge 行。
     *
     * <p>⚠️ 必须走<b>裸 JDBC</b> 而不是 {@code knowledgeMapper.insert(record)}：
     * MyBatis-Flex 的 insert 会把 null 字段也列进 INSERT 列清单 ⇒ DDL 的
     * {@code DEFAULT} 全部不生效，{@code parse_status} / {@code enable_status} /
     * {@code channel} / {@code custom_metadata} 等一串 NOT NULL 列直接撞约束。
     * 这里只显式给 4 个「NOT NULL 且无 DEFAULT」的列，其余交给 DDL 默认值。
     */
    private void knowledge(String id) {
        try (Connection c = sqlite().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO kb_knowledge (id, knowledge_base_id, type, title, source) "
                     + "VALUES (?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, "kb-list");
            ps.setString(3, "file");
            ps.setString(4, "t");
            ps.setString(5, "upload");
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("造 kb_knowledge 夹具失败 id=" + id, e);
        }
    }

    private void chunk(String knowledgeId, int index, String type, String content) {
        KbChunkRecord row = new KbChunkRecord();
        row.setId(UUID.randomUUID().toString());
        row.setKnowledgeBaseId("kb-list");
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
    }

    @Test
    @DisplayName("默认 list 只返回 text 块；includeParents=true 才带父块")
    void listExcludesParentsByDefault() {
        String knowledgeId = "k-list-filter";
        knowledge(knowledgeId);
        chunk(knowledgeId, 0, KbChunk.CHUNK_TYPE_PARENT_TEXT, "父块正文");
        chunk(knowledgeId, 0, KbChunk.CHUNK_TYPE_TEXT, "子块甲");
        chunk(knowledgeId, 1, KbChunk.CHUNK_TYPE_TEXT, "子块乙");

        List<KbChunkRecord> visible = service().list(knowledgeId, true);
        assertThat(visible).hasSize(2);
        assertThat(visible).allSatisfy(r ->
            assertThat(r.getChunkType()).isEqualTo(KbChunk.CHUNK_TYPE_TEXT));
        assertThat(visible).extracting(KbChunkRecord::getContent)
            .containsExactly("子块甲", "子块乙");

        List<KbChunkRecord> withParents = service().list(knowledgeId, true, true);
        assertThat(withParents).hasSize(3);
        assertThat(withParents).anySatisfy(r ->
            assertThat(r.getChunkType()).isEqualTo(KbChunk.CHUNK_TYPE_PARENT_TEXT));
    }
}
