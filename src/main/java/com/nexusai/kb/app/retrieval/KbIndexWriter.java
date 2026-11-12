package com.nexusai.kb.app.retrieval;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbEmbeddingRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbEmbeddingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * 图片文字是否已写成<b>独立子块</b>（{@code image_caption} / {@code image_ocr}）。
     *
     * <p>⭐ 与 {@code KbImageEnrichService} 读<b>同一个配置键</b>（默认 true）：
     * <ul>
     *   <li>true ⇒ caption/OCR 由子块自己进索引，正文块<b>不再拼</b>（否则同一段文字被索引两遍）；</li>
     *   <li>false ⇒ 旧行为：拼进正文块的向量与 FTS 文本。</li>
     * </ul>
     */
    @Value("${nexusai.knowledge.image.sub-chunks-enabled:true}")
    private boolean imageSubChunksEnabled =
        com.nexusai.kb.app.image.KbImageEnrichService.DEFAULT_SUB_CHUNKS_ENABLED;

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
        // ⚠️ 一次取出该文档的**全部**块，再在内存里拆「启用 / 禁用」：
        //    · 进索引的只有启用块（V257 DDL：is_enabled 1 = 启用 / 0 = 禁用）；
        //    · 但「块是否还存在」要按全量判断（禁用块的向量不该被当孤儿删掉）。
        List<KbChunkRecord> all = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", knowledgeId)
                .orderBy("chunk_index", true));
        List<KbChunkRecord> chunks = new ArrayList<>(all.size());
        Set<String> existingIds = new HashSet<>(all.size() * 2);
        int parentSkipped = 0;
        for (KbChunkRecord c : all) {
            // ⭐ existingIds 必须含父块 id：父块本来就不该有向量，但若历史上误建过一行
            //    （例如本改动之前跑的索引），按「块还存在」保留更安全 —— 删错是数据损失，
            //    留着只是一行没人检索的向量（检索侧按 chunk_type 过滤）。
            existingIds.add(c.getId());
            // is_enabled 为 null 视为启用（DDL 是 NOT NULL DEFAULT 1，null 只可能是历史脏数据）
            if (c.getIsEnabled() != null && c.getIsEnabled() == 0) {
                continue;
            }
            // ⭐ 父块不进向量 / FTS 索引（源站 knowledge_process.go:613
            //    "Add parent chunks first (they go into DB but NOT into the vector index)"；
            //    过滤处 knowledge_process.go:3402 `if !chunk.IsEnabled || chunk.ChunkType == ChunkTypeParentText`）。
            //    父块只在「命中子块后展开上下文」时被读，把它也向量化会让同段内容被检索两次，
            //    结果里出现父子两份几乎相同的片段。
            if (KbChunk.CHUNK_TYPE_PARENT_TEXT.equals(c.getChunkType())) {
                parentSkipped++;
                continue;
            }
            chunks.add(c);
        }
        if (parentSkipped > 0) {
            log.info("[索引] 跳过父块 {} 个（parent_text 不进索引）knowledge={}", parentSkipped, knowledgeId);
        }
        if (chunks.isEmpty()) {
            log.info("[索引] 文档无启用分块，跳过 knowledge={} 总块数={}",
                knowledgeId, all.size());
            // 旧 FTS 必须清（全部禁用 / 重解析后变空）
            clearFts(knowledgeId);
            // 向量：只清「块已不存在」的孤儿；禁用块的向量留着
            // （检索侧已按 is_enabled 过滤，重新启用时不必再花一次 embedding）
            int purged = purgeStaleVectors(knowledgeId, List.of(), existingIds);
            log.info("[索引] 文档无启用分块 knowledge={} 清理孤儿向量={}", knowledgeId, purged);
            return 0;
        }

        boolean withVectors = modelId != null && !modelId.isBlank();
        if (!withVectors) {
            // ⭐ 只重建关键词索引：⛔ 不清 {@code kb_embedding}
            //    历史缺陷（P0）：这里原来先 clearIndex 再走本分支 ⇒ 编辑一个分块
            //    会把整篇文档的向量删光且不重建，语义检索永久失效，日志还写「已重建索引」。
            //    但也不能一味保留：{@link KbChunkService#update} 改过正文的块，
            //    其向量是旧正文算的（content_hash 对不上）⇒ 必须删掉那一行，
            //    否则「正文改了、向量还是旧的」，语义检索会命中错位内容。
            //    ⚠️ 本层只拿到 knowledgeId，拿不到「被编辑的 chunkId」；
            //    用 content_hash 与当前索引文本比对即可精确识别过期行（不需要额外参数）。
            int purged = purgeStaleVectors(knowledgeId, chunks, existingIds);
            clearFts(knowledgeId);
            writeFts(chunks);
            log.info("[索引] 仅重建关键词索引 knowledge={} 块数={} 清理过期向量={}",
                knowledgeId, chunks.size(), purged);
            return chunks.size();
        }

        // 1) 清旧索引（向量 + FTS）
        clearIndex(knowledgeId);

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
        clearFts(knowledgeId);
    }

    /**
     * 只清关键词索引（{@code kb_chunk_fts}），⛔ 不动 {@code kb_embedding}。
     *
     * <p><b>为什么单独抽出来</b>：「只重建关键词索引」的路径（{@code modelId == null}）
     * 如果复用 {@link #clearIndex} 会把向量一起删掉 —— 这正是 P0 缺陷的形态
     * （编辑一个分块 ⇒ 整篇文档语义检索失效）。
     *
     * @param knowledgeId 知识条目 ID
     */
    private void clearFts(String knowledgeId) {
        // ⚠️ FTS 是虚拟表，只能走原生 JDBC。这里**不在事务内**调用（见 reindexKnowledge 上方说明），
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

    /**
     * 清掉「已与当前正文不一致」的向量行（只重建关键词索引时用）。
     *
     * <p><b>⭐ 为什么要清</b>：{@code modelId == null} 意味着这次没有模型可用，
     * 无法重算向量 —— 但用户可能刚刚<b>改过某块的正文</b>，那一行的向量还是旧正文算出来的。
     * 留着它 ⇒ 语义检索把「旧内容的相似度」套在「新内容」上返回，用户看到的是错位结果，
     * 而且不报错、无从察觉。⇒ 删掉（宁可少一条向量，也不要一条错的）。
     *
     * <p><b>识别方式</b>：{@code kb_embedding.content_hash} 存的就是索引文本的 SHA-256
     * （见本类写向量分支），与当前 {@link #indexTextOf(KbChunkRecord)} 的哈希比对即可。
     * 另外清掉「块已不存在」的孤儿行（重解析后块被换掉）。
     *
     * <p><b>⚠️ 禁用块的向量要保留</b>：块的可见性由检索侧按 {@code kb_chunk.is_enabled} 过滤，
     * 向量本身没错。这里若把禁用块的向量也删了，用户重新启用时就再也建不回来
     * （重新启用的调用链同样传 {@code modelId=null}）⇒ 语义检索永久缺一块。
     *
     * @param knowledgeId  知识条目 ID
     * @param enabledChunks 当前<b>启用</b>的块（用于比对哈希；可为空 = 全部禁用）
     * @param existingIds  该文档当前<b>存在</b>的块 id（含禁用；用于识别孤儿向量）
     * @return 删掉的向量行数
     */
    private int purgeStaleVectors(
        String knowledgeId, List<KbChunkRecord> enabledChunks, Set<String> existingIds
    ) {
        List<KbEmbeddingRecord> rows;
        try {
            rows = embeddingMapper.selectListByQuery(
                QueryWrapper.create()
                    .where("knowledge_id = ?", knowledgeId)
                    .and("source_type = ?", "chunk"));
        } catch (RuntimeException e) {
            // ⭐ 降级：读不出来时不清（清错是数据损失，留着只是暂时陈旧）
            log.warn("[索引] 读旧向量失败，跳过过期向量清理 knowledge={} 原因={}",
                knowledgeId, e.getMessage());
            return 0;
        }
        if (rows.isEmpty()) {
            return 0;
        }
        // ⚠️ 需求要的是「不清全部」而不是「一条都不清」：
        //    · 块已不存在 ⇒ 孤儿向量，删；
        //    · 块存在但被禁用 ⇒ 向量保留（检索侧按 is_enabled 过滤；重新启用无需重算）；
        //    · 块启用但索引文本变了 ⇒ 向量已过期，删（宁可少一条，也不要一条错的）。
        Map<String, String> hashById = new HashMap<>(enabledChunks.size() * 2);
        for (KbChunkRecord c : enabledChunks) {
            hashById.put(c.getId(), sha256(indexTextOf(c)));
        }
        int purged = 0;
        for (KbEmbeddingRecord row : rows) {
            if (!existingIds.contains(row.getSourceId())) {
                embeddingMapper.deleteById(row.getId());
                purged++;
                continue;
            }
            String expect = hashById.get(row.getSourceId());
            if (expect == null) {
                continue;  // 禁用块：向量保留
            }
            // ⚠️ content_hash 为空 = 历史行，无法证明过期（也证明不了新鲜）⇒ 不动它。
            //    「不知道」≠「过期」，删错是数据损失；陈旧只影响排序质量。
            if (row.getContentHash() != null && !expect.equals(row.getContentHash())) {
                embeddingMapper.deleteById(row.getId());
                purged++;
            }
        }
        return purged;
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
    private String indexTextOf(KbChunkRecord c) {
        // ⭐ 图内文字（caption / OCR）必须能被检索到 —— 但只走一条路：
        //    · 子块模式（默认）：文字已写成 image_caption / image_ocr 独立块，由它们自己进索引；
        //    · 旧模式：文字只躺在 image_info 里，必须拼进本块的索引文本（否则模型白跑）。
        String imageText = imageSubChunksEnabled
            ? ""
            : com.nexusai.kb.app.image.KbImageEnrichService.imageTextForIndex(c.getImageInfo());
        return embeddingInputOf(c)
            + imageText
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
                // ⭐ 与向量输入同构：正文 + 图内文字（仅旧模式）+ 生成的问题
                //    （否则「向量能召回、关键词召回不到」）
                String imageText = imageSubChunksEnabled
                    ? ""
                    : com.nexusai.kb.app.image.KbImageEnrichService
                        .imageTextForIndex(c.getImageInfo());
                ps.setString(5, (c.getContent() == null ? "" : c.getContent())
                    + imageText
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
