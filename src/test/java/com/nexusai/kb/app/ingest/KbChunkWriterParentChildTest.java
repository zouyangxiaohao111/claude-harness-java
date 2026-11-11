package com.nexusai.kb.app.ingest;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.app.chunk.KbSplitterConfig;
import com.nexusai.kb.app.chunk.KbTextSplitter;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 父子分块落库 · 对齐 WeKnora {@code knowledge_process.go:578-674}。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：写入侧有四个字段口径，任一处写错都<b>不报错</b>，
 * 但会让检索侧静默失效：
 * <ol>
 *   <li>父块 {@code chunk_type != parent_text} ⇒ {@code KbMergeStage.resolveParents} 的判据
 *       （{@code "parent_text".equals(...)}）永不命中 ⇒ 父块展开彻底失效，用户只拿到子块碎片。</li>
 *   <li>子块 {@code parent_chunk_id} 为空 ⇒ 同上，子块指不到父块。</li>
 *   <li>子块串了 {@code pre/next} ⇒ 源站明文禁止（{@code :664}）；串上后「按顺序链拼接文档」
 *       的既有逻辑会把不相干的子块连起来。</li>
 *   <li>父块之间没串链 ⇒ 与源站 {@code :600-606} 不一致。</li>
 * </ol>
 *
 * <p>⚠️ 只跑本地 SQLite（Flyway 建表 + Flex mapper），⛔ 不用 {@code @SpringBootTest}。
 */
@DisplayName("[B0] 父子分块落库（parent_text + parent_chunk_id + 子块不串链）")
class KbChunkWriterParentChildTest {

    private static KbChunkMapper chunkMapper;
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
    }

    @BeforeEach
    void cleanRows() throws Exception {
        try (Connection c = sqlite().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM kb_chunk");
        }
    }

    private static SQLiteDataSource sqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        return ds;
    }

    private static KbKnowledgeRecord knowledge(String id) {
        KbKnowledgeRecord k = new KbKnowledgeRecord();
        k.setId(id);
        k.setKnowledgeBaseId("kb-parent-child");
        return k;
    }

    private static String longText() {
        String para = "Parent child chunking deterministic text for offset assertions. ";
        StringBuilder sb = new StringBuilder(12_000);
        while (sb.length() < 12_000) {
            sb.append(para).append("\n\n");
        }
        return sb.toString();
    }

    private static KbSplitterConfig cfg(int size, int overlap) {
        return new KbSplitterConfig(size, overlap, KbSplitterConfig.DEFAULT_SEPARATORS, "", 0,
            List.of());
    }

    @Test
    @DisplayName("父子落库：父块 parent_text 且无父指针；子块 text 且指向父块；子块无 pre/next")
    void writesParentChildRows() {
        String knowledgeId = "k-writer-pc";
        String text = longText();
        KbTextSplitter.ParentChildResult pc = KbTextSplitter.splitParentChild(
            text, cfg(2048, 0), cfg(256, 51));
        assertThat(pc.parents()).as("前提：长文本必须有保留父块").isNotEmpty();
        assertThat(pc.children()).isNotEmpty();

        KbChunkWriter writer = new KbChunkWriter(chunkMapper);
        KbChunkWriter.WriteResult result =
            writer.replaceChunks(knowledge(knowledgeId), pc.children(), pc.parents());

        assertThat(result.count()).isEqualTo(pc.parents().size() + pc.children().size());

        List<KbChunkRecord> rows = chunkMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId)
                .orderBy("chunk_type", true).orderBy("chunk_index", true));

        List<KbChunkRecord> parents = rows.stream()
            .filter(r -> KbChunk.CHUNK_TYPE_PARENT_TEXT.equals(r.getChunkType())).toList();
        List<KbChunkRecord> children = rows.stream()
            .filter(r -> KbChunk.CHUNK_TYPE_TEXT.equals(r.getChunkType())).toList();

        assertThat(parents).hasSize(pc.parents().size());
        assertThat(children).hasSize(pc.children().size());

        // 父块：parent_chunk_id 必须为 null；父块之间串链
        for (int i = 0; i < parents.size(); i++) {
            KbChunkRecord p = parents.get(i);
            assertThat(p.getParentChunkId()).as("父块不该有父指针").isNull();
            assertThat(p.getPreChunkId()).isEqualTo(i > 0 ? parents.get(i - 1).getId() : null);
            assertThat(p.getNextChunkId())
                .isEqualTo(i + 1 < parents.size() ? parents.get(i + 1).getId() : null);
        }

        // 子块：parent_chunk_id 指向真实父块行；⛔ 不串 pre/next
        int withParent = 0;
        for (KbChunkRecord c : children) {
            assertThat(c.getPreChunkId()).as("父子模式下子块不建前链").isNull();
            assertThat(c.getNextChunkId()).as("父子模式下子块不建后链").isNull();
            if (c.getParentChunkId() != null) {
                withParent++;
                assertThat(parents).extracting(KbChunkRecord::getId)
                    .contains(c.getParentChunkId());
            }
        }
        assertThat(withParent).as("必须有子块真的挂到父块上").isPositive();
    }

    @Test
    @DisplayName("普通落库（无父块）⇒ 全部 text、串 pre/next、无 parent_chunk_id（旧行为不变）")
    void flatWriteUnchanged() {
        String knowledgeId = "k-writer-flat";
        List<KbChunk> chunks = List.of(
            new KbChunk("第一块", "", 0, 0, 3),
            new KbChunk("第二块", "", 1, 3, 6));

        new KbChunkWriter(chunkMapper).replaceChunks(knowledge(knowledgeId), chunks);

        List<KbChunkRecord> rows = chunkMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId)
                .orderBy("chunk_index", true));
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getChunkType()).isEqualTo(KbChunk.CHUNK_TYPE_TEXT);
            assertThat(r.getParentChunkId()).isNull();
        });
        assertThat(rows.get(0).getNextChunkId()).isEqualTo(rows.get(1).getId());
        assertThat(rows.get(1).getPreChunkId()).isEqualTo(rows.get(0).getId());
    }

    @Test
    @DisplayName("父块下标越界 ⇒ 按无父块写入（留 warn，不炸整个文档）")
    void outOfRangeParentIndexFallsBackToNull() {
        String knowledgeId = "k-writer-oob";
        KbChunk parent = new KbChunk("父块", "", 0, 0, 2);
        KbChunk child = new KbChunk("子块", "", 0, 0, 2).withParentIndex(5);

        new KbChunkWriter(chunkMapper)
            .replaceChunks(knowledge(knowledgeId), List.of(child), List.of(parent));

        List<KbChunkRecord> text = chunkMapper.selectListByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId)
                .and("chunk_type = ?", KbChunk.CHUNK_TYPE_TEXT));
        assertThat(text).hasSize(1);
        assertThat(text.get(0).getParentChunkId()).isNull();
    }
}
