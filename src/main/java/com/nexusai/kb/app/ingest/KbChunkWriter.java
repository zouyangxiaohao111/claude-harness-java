package com.nexusai.kb.app.ingest;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.chunk.KbChunk;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分块落库 · 单个事务内「清旧 + 写新 + 串顺序链」。
 *
 * <p><b>为什么必须单独一个 bean</b>：{@code @Transactional} 靠 Spring 代理生效，
 * 同类内部自调用不会开启事务。解析与分块是 CPU 活，不能包在写事务里（SQLite 会长时间锁库）；
 * 只有落库这一小段需要事务 ⇒ 拆成独立 bean，由编排器在解析完成后调用。
 *
 * <p><b>为什么先逻辑删旧块再插新块</b>：重新解析会产出全新分块集合，旧块的
 * {@code chunk_index} / 顺序链与新集合完全不对应；留着会让「取某文档全部分块」
 * 同时返回两轮结果。用逻辑删（{@code deleted=1}）而非物理删，与本域其余表一致。
 */
@Component
public class KbChunkWriter {

    private static final Logger log = LoggerFactory.getLogger(KbChunkWriter.class);

    private final KbChunkMapper chunkMapper;

    public KbChunkWriter(KbChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    /**
     * 用新分块替换该文档的全部分块。
     *
     * @param knowledge 知识条目（取 id / knowledgeBaseId）
     * @param chunks    分块器产物（要求按 {@code seq} 升序）
     * @return 写入结果（条数 + 内容字节总和）
     */
    @Transactional
    public WriteResult replaceChunks(KbKnowledgeRecord knowledge, List<KbChunk> chunks) {
        String knowledgeId = knowledge.getId();
        String knowledgeBaseId = knowledge.getKnowledgeBaseId();

        // 1) 逻辑删旧块（Flex 对 @Column(isLogicDelete=true) 会把 DELETE 改写成 UPDATE deleted=1）
        chunkMapper.deleteByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));

        if (chunks.isEmpty()) {
            log.info("[入库] 文档无分块可写 knowledge={}", knowledgeId);
            return new WriteResult(0, 0L);
        }

        // 2) 先分配 ID，再串双向链（顺序链需要前后两块同时存在）
        List<String> ids = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }

        String now = KbTimeFormat.now();
        long contentBytes = 0L;
        for (int i = 0; i < chunks.size(); i++) {
            KbChunk chunk = chunks.get(i);
            contentBytes += chunk.content() == null
                ? 0L
                : chunk.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            KbChunkRecord row = new KbChunkRecord();
            row.setId(ids.get(i));
            row.setKnowledgeBaseId(knowledgeBaseId);
            row.setKnowledgeId(knowledgeId);
            row.setContent(chunk.content());
            row.setChunkIndex(chunk.seq());
            row.setStartAt(chunk.start());
            row.setEndAt(chunk.end());
            row.setPreChunkId(i > 0 ? ids.get(i - 1) : null);
            row.setNextChunkId(i + 1 < ids.size() ? ids.get(i + 1) : null);
            row.setChunkType("text");
            row.setContextHeader(chunk.contextHeader() == null ? "" : chunk.contextHeader());

            // ⚠️ MyBatis-Flex 的 insert 会把 null 字段也列进 INSERT 列清单 ⇒
            //    DB DEFAULT 不生效。所有带 DEFAULT 的 NOT NULL 列必须显式赋值。
            row.setIsEnabled(1);
            row.setSourceContent("");
            row.setContentRevision(0);
            row.setIndexStatus("ready");
            row.setLastEditorId("");
            row.setStatus(0);
            row.setFlags(1);

            // 审计列（Listener 只在 null 时补，这里显式写死更可控）
            row.setCreator("");
            row.setUpdater("");
            row.setDeleted(0);
            row.setTenantId(0L);
            row.setCreateTime(KbTimeFormat.parse(now));
            row.setUpdateTime(KbTimeFormat.parse(now));

            chunkMapper.insert(row);
        }

        log.info("[入库] 已写入分块 knowledge={} 条数={} 内容字节={}",
            knowledgeId, chunks.size(), contentBytes);
        return new WriteResult(chunks.size(), contentBytes);
    }

    /**
     * 落库结果。
     *
     * @param count        写入的分块数
     * @param contentBytes 全部块内容的 UTF-8 字节总和（近似 {@code storage_size}）
     */
    public record WriteResult(int count, long contentBytes) {
    }

    /**
     * 删除某文档的全部分块（逻辑删）。
     *
     * @param knowledgeId 知识条目 ID
     */
    @Transactional
    public void deleteChunks(String knowledgeId) {
        chunkMapper.deleteByQuery(
            QueryWrapper.create().where("knowledge_id = ?", knowledgeId));
    }
}
