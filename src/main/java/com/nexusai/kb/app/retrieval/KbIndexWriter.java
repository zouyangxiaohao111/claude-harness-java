package com.nexusai.kb.app.retrieval;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbEmbeddingRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbEmbeddingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 索引写入器 · 把分块写入 {@code kb_embedding}（向量）与 {@code kb_chunk_fts}（关键词）。
 *
 * <p><b>⭐ 为什么两路都要写</b>：源侧 HybridSearch 是「向量召回 ∪ 关键词召回 → RRF 融合」。
 * 只写向量会让精确词匹配（人名、型号、代码）大幅退化 —— 向量检索对这类
 * 「低频但精确」的查询天然弱。两路互补是源侧的核心设计，不是可选项。
 *
 * <p><b>嵌入用的是 {@code embeddingContent()} 而不是 {@code content}</b>：
 * 前者 = {@code context_header + "\n\n" + 正文.strip()}（见 {@link com.nexusai.kb.app.chunk.KbChunk#embeddingContent}）。
 * 把标题面包屑一起嵌入能让「某章节下的段落」被章节语义召回。⛔ 但落库的 content 不含表头
 * （位置不变式），两者不能混用。
 *
 * <p><b>批量策略</b>：{@link EmbeddingService} 单请求上限 64 条，此处按 64 分批；
 * 任一批失败 ⇒ 整次索引失败（fail loud），⛔ 不静默跳过 —— 「部分块没索引」
 * 会让检索结果出现无法解释的空洞。
 */
@Component
public class KbIndexWriter {

    private static final Logger log = LoggerFactory.getLogger(KbIndexWriter.class);

    /** 单次 embedding 请求的块数上限（与 EmbeddingService.MAX_INPUTS 一致）。 */
    private static final int EMBED_BATCH = 64;

    private final KbEmbeddingMapper embeddingMapper;
    private final KbChunkMapper chunkMapper;
    private final KbEmbeddingPort embeddingPort;
    private final DataSource dataSource;

    public KbIndexWriter(
        KbEmbeddingMapper embeddingMapper,
        KbChunkMapper chunkMapper,
        KbEmbeddingPort embeddingPort,
        DataSource dataSource
    ) {
        this.embeddingMapper = embeddingMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingPort = embeddingPort;
        this.dataSource = dataSource;
    }

    /**
     * 为一个文档的全部块建索引（若已有旧索引先清掉）。
     *
     * <p><b>⭐ {@code modelId} 为空时只建关键词索引</b>：没有嵌入模型不等于不能检索
     * —— FTS 关键词检索仍然可用。⛔ 不要因为缺模型就整体失败（那会让文档完全搜不到）；
     * 也不要静默假装建了向量索引。
     *
     * @param knowledgeId     知识条目 ID
     * @param knowledgeBaseId 知识库 ID
     * @param modelId         嵌入模型 ID（null/空 = 只建 FTS）
     * @return 实际索引的块数
     *
     * <p><b>⭐⭐ 为什么这个方法不能包在 {@code @Transactional} 里</b>
     * （真机实测缺陷，不是风格选择）：
     * 本仓的事务管理器是 MyBatis-Flex 的 {@code FlexTransactionManager}，它把事务绑定在
     * <b>SqlSession 的连接</b>上；而 FTS5 是虚拟表、只能走原生 JDBC。
     * 若给本方法加 {@code @Transactional}：
     * <pre>
     *   事务开启 → Flex 持连接A（已拿 SQLite 写锁）
     *            → 原生 JDBC 从池里再取连接B
     *            → B 想写 FTS ⇒ 等 A 的锁，而 A 要等本方法返回才提交
     *            ⇒ 自锁 [SQLITE_BUSY]，文档解析永久失败（日志实证 levels=3/3 dead）
     * </pre>
     * ⇒ 改为**每步一个短写**：清 FTS / 清向量 / 写向量 / 写 FTS 各自提交。
     * 代价：中途失败会留下部分索引 —— 用「失败即抛异常让入库任务重试」兜底
     * （重试会先清干净再重建，最终一致）。
     */
    public int reindexKnowledge(
        String knowledgeId, String knowledgeBaseId, String modelId
    ) {
        List<KbChunkRecord> chunks = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", knowledgeId)
                .orderBy("chunk_index", true));
        if (chunks.isEmpty()) {
            log.info("[索引] 文档无分块，跳过 knowledge={}", knowledgeId);
            // 旧索引仍要清（重解析后可能变空）
            clearIndex(knowledgeId);
            return 0;
        }

        // 1) 清旧索引（向量 + FTS）
        clearIndex(knowledgeId);

        boolean withVectors = modelId != null && !modelId.isBlank();
        if (!withVectors) {
            writeFts(chunks);
            log.info("[索引] 无嵌入模型，仅建关键词索引 knowledge={} 块数={}", knowledgeId, chunks.size());
            return chunks.size();
        }

        // 2) 向量化：按批调用
        List<String> inputs = new ArrayList<>(chunks.size());
        for (KbChunkRecord c : chunks) {
            // ⭐ 索引文本含 Doc2Query 问句 ⇒ 用户用问句搜也能命中这段正文
            inputs.add(indexTextOf(c));
        }

        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (int start = 0; start < inputs.size(); start += EMBED_BATCH) {
            int end = Math.min(start + EMBED_BATCH, inputs.size());
            List<String> batch = inputs.subList(start, end);
            List<float[]> got = embeddingPort.embed(modelId, batch);
            if (got == null || got.size() != batch.size()) {
                throw new IllegalStateException(
                    "嵌入返回条数与请求不一致：请求 " + batch.size()
                        + "，返回 " + (got == null ? 0 : got.size()));
            }
            vectors.addAll(got);
        }

        // 3) 写向量
        String now = KbTimeFormat.now();
        for (int i = 0; i < chunks.size(); i++) {
            KbChunkRecord chunk = chunks.get(i);
            float[] vec = vectors.get(i);

            KbEmbeddingRecord row = new KbEmbeddingRecord();
            row.setId(UUID.randomUUID().toString());
            row.setKnowledgeBaseId(knowledgeBaseId);
            row.setKnowledgeId(knowledgeId);
            row.setSourceId(chunk.getId());
            row.setSourceType("chunk");
            row.setModelId(modelId);
            row.setDimensions(vec.length);
            row.setVector(KbVectorCodec.encode(vec));
            row.setContentHash(sha256(inputs.get(i)));

            // ⚠️ Flex 的 insert 会把 null 也列进 INSERT 清单 ⇒ 带 DEFAULT 的 NOT NULL 列必须显式赋值
            row.setCreator("");
            row.setUpdater("");
            row.setDeleted(0);
            row.setTenantId(0L);
            row.setCreateTime(KbTimeFormat.parse(now));
            row.setUpdateTime(KbTimeFormat.parse(now));

            embeddingMapper.insert(row);
        }

        // 4) 写 FTS（JDBC 原生：FTS5 是虚拟表，Flex 无法映射）
        writeFts(chunks);

        log.info("[索引] 文档索引完成 knowledge={} 块数={} 模型={} 维度={}",
            knowledgeId, chunks.size(), modelId, vectors.isEmpty() ? 0 : vectors.get(0).length);
        return chunks.size();
    }

    /**
     * 清掉一个文档的全部索引。
     *
     * @param knowledgeId 知识条目 ID
     */
    public void clearIndex(String knowledgeId) {
        embeddingMapper.deleteByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));
        // ⚠️ FTS 是虚拟表，只能走原生 JDBC。这里**不在事务内**调用（见 clearIndex 上方说明），
        //    用独立连接 + 自身自动提交即可；若将来有人给调用链加 @Transactional，
        //    必须改回 DataSourceUtils 语义，否则会自锁。
        Connection conn = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try (var ps = conn.prepareStatement(
                 "DELETE FROM kb_chunk_fts WHERE knowledge_id = ?")) {
            ps.setString(1, knowledgeId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("清理 FTS 索引失败: " + e.getMessage(), e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 嵌入输入 = 表头 + 正文（对齐 {@code KbChunk.embeddingContent()}）。
     *
     * <p>⚠️ 这里**必须**与 {@code KbChunk.embeddingContent()} 同构：
     * 否则「索引时用的文本」与「展示给用户的文本」语义不一致，排查时对不上。
     */
    private static String embeddingInputOf(KbChunkRecord c) {
        String body = c.getContent() == null ? "" : c.getContent().strip();
        String header = c.getContextHeader();
        if (header == null || header.isEmpty()) {
            return body;
        }
        return header + "\n\n" + body;
    }

    /**
     * 索引文本 = 嵌入输入 + 该块生成的问题（Doc2Query）。
     *
     * <p><b>为什么问题要进索引</b>：问句是「用户会怎么搜这段内容」的改写。只存 metadata
     * 不进索引 ⇒ 对召回率零贡献，功能看起来做完了实际无效。
     *
     * <p><b>⭐⭐ 为什么拼在索引文本而不是 {@code kb_chunk.content}</b>：正文与
     * {@code start_at/end_at} 有位置不变式，塞进正文会破坏文档重建与高亮。
     * 索引文本是派生物，可以随便拼。
     */
    private static String indexTextOf(KbChunkRecord c) {
        return embeddingInputOf(c)
            + com.nexusai.kb.app.ingest.KbGeneratedQuestions.indexText(c.getMetadata());
    }

    private void writeFts(List<KbChunkRecord> chunks) {
        // 同上：必须复用事务连接（见 clearIndex 的说明）
        Connection conn = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
        try (var ps = conn.prepareStatement(
                 "INSERT INTO kb_chunk_fts(chunk_id, knowledge_id, knowledge_base_id, header, content) "
                     + "VALUES (?, ?, ?, ?, ?)")) {
            for (KbChunkRecord c : chunks) {
                ps.setString(1, c.getId());
                ps.setString(2, c.getKnowledgeId());
                ps.setString(3, c.getKnowledgeBaseId());
                ps.setString(4, c.getContextHeader() == null ? "" : c.getContextHeader());
                // ⭐ 与向量输入同构：正文 + 生成的问题（否则「向量能召回、关键词召回不到」）
                ps.setString(5, (c.getContent() == null ? "" : c.getContent())
                    + com.nexusai.kb.app.ingest.KbGeneratedQuestions
                        .indexText(c.getMetadata()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("写入 FTS 索引失败: " + e.getMessage(), e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
