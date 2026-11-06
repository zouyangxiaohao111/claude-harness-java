package com.nexusai.kb.app.chunk;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.domain.KbActivityService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbChunkRevisionRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbChunkRevisionMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分块编辑 / 删除 / 修订历史 · 批次 W4（源侧「分块管理」8 端点）· V269。
 *
 * <h2>⭐⭐ 「先写快照，再改正文」的固定顺序</h2>
 * 每次编辑都：
 * <ol>
 *   <li>把<b>当前</b>内容写成一条 revision（version = max+1）；</li>
 *   <li>再把新内容写回 {@code kb_chunk}，并把 {@code content_revision} +1。</li>
 * </ol>
 * 顺序反了就意味着「第一次编辑没有可回滚的点」。源侧同样如此。
 *
 * <h2>⭐⭐ 回滚 = 新增一条 revision，不是删历史</h2>
 * 「回滚到 v2」= 把 v2 的内容作为<b>新版本 v5</b> 写回。这样回滚本身也可回滚。
 *
 * <h2>⭐⭐ 分块改了必须重建索引</h2>
 * FTS 表 {@code kb_chunk_fts} 存的是 content 的副本；只改 {@code kb_chunk.content}
 * 不重建索引 ⇒ 搜索仍返回旧文本，且**不报错**。这是本仓第 1 个静默缺陷的同族问题。
 * ⇒ 编辑后调 {@link KbIndexWriter#reindexKnowledge}，并确保它不在本方法的事务里
 * （否则 SQLITE_BUSY，见缺陷 5）。
 */
@Service
public class KbChunkService {

    private static final Logger log = LoggerFactory.getLogger(KbChunkService.class);

    /** 单次编辑内容上限（源侧无硬限，这里防超大内容打爆 FTS）。 */
    private static final int MAX_CONTENT_CHARS = 200_000;

    private final KbChunkMapper chunkMapper;
    private final KbChunkRevisionMapper revisionMapper;
    private final KbKnowledgeMapper knowledgeMapper;
    private final KbIndexWriter indexWriter;
    private final KbActivityService activityService;
    private final JdbcTemplate jdbc;

    @Autowired
    public KbChunkService(KbChunkMapper chunkMapper,
                          KbChunkRevisionMapper revisionMapper,
                          KbKnowledgeMapper knowledgeMapper,
                          KbIndexWriter indexWriter,
                          KbActivityService activityService,
                          JdbcTemplate jdbc) {
        this.chunkMapper = chunkMapper;
        this.revisionMapper = revisionMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.indexWriter = indexWriter;
        this.activityService = activityService;
        this.jdbc = jdbc;
    }

    /**
     * 列出某文档的分块。
     *
     * @param knowledgeId 文档 ID
     * @param includeDisabled 是否含被禁用的块
     */
    public List<KbChunkRecord> list(String knowledgeId, boolean includeDisabled) {
        requireKnowledge(knowledgeId);
        QueryWrapper q = QueryWrapper.create().where("knowledge_id = ?", knowledgeId);
        if (!includeDisabled) {
            q.and("is_enabled = 1");
        }
        q.orderBy("chunk_index", true);
        return new ArrayList<>(chunkMapper.selectListByQuery(q));
    }

    /** 按 ID 取块（不需知道文档 ID）。 */
    public KbChunkRecord getById(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            throw new ValidationException("分块 ID 不能为空");
        }
        KbChunkRecord row = chunkMapper.selectOneById(chunkId);
        if (row == null) {
            throw new NotFoundException("分块 " + chunkId + " 不存在");
        }
        return row;
    }

    /**
     * 更新分块内容。
     *
     * @param knowledgeId 文档 ID（路径参数；与块的 knowledge_id 不符则 404，
     *                    防止用别的文档的 ID 改到本块）
     * @param chunkId     分块 ID
     * @param content     新内容（null = 不改）
     * @param isEnabled   新启用状态（null = 不改）
     * @return 更新后的块
     */
    @Transactional
    public KbChunkRecord update(String knowledgeId, String chunkId, String content, Boolean isEnabled) {
        KbChunkRecord chunk = requireChunkOfKnowledge(knowledgeId, chunkId);
        if (content == null && isEnabled == null) {
            return chunk;
        }
        if (content != null) {
            if (content.isBlank()) {
                throw new ValidationException("分块内容不能为空（如需隐藏请用 is_enabled=false）");
            }
            if (content.length() > MAX_CONTENT_CHARS) {
                throw new ValidationException("分块内容超过上限 " + MAX_CONTENT_CHARS + " 字符");
            }
        }

        // ① 先写快照（当前值），再改正文
        writeSnapshot(chunk, "user");

        if (content != null) {
            chunk.setContent(content);
            chunk.setContentRevision((chunk.getContentRevision() == null ? 0 : chunk.getContentRevision()) + 1);
            // 正文变了 ⇒ 原 source_content 已不是「解析原始值」，但保留它作为溯源基线
            if (chunk.getSourceContent() == null) {
                chunk.setSourceContent(content);
            }
        }
        if (isEnabled != null) {
            chunk.setIsEnabled(isEnabled ? 1 : 0);
        }
        chunk.setLastEditorId("system");
        chunkMapper.update(chunk, false);

        activityService.record(chunk.getKnowledgeBaseId(), KbActivityService.A_CHUNK_EDIT,
            "success", "chunk", chunkId, "{\"revision\":" + chunk.getContentRevision() + "}");
        log.info("[分块] 已更新 chunk={} revision={} enabled={}",
            chunkId, chunk.getContentRevision(), chunk.getIsEnabled());
        return chunkMapper.selectOneById(chunkId);
    }

    /**
     * 回滚到指定版本。
     *
     * <p>⭐ 回滚 = 把目标版本的内容作为<b>新版本</b>写回（不删历史）。
     *
     * @param knowledgeId 文档 ID
     * @param chunkId     分块 ID
     * @param revision    要回滚到的版本号
     * @return 更新后的块
     */
    @Transactional
    public KbChunkRecord revert(String knowledgeId, String chunkId, int revision) {
        KbChunkRecord chunk = requireChunkOfKnowledge(knowledgeId, chunkId);
        KbChunkRevisionRecord target = revisionMapper.selectOneByQuery(
            QueryWrapper.create()
                .where("chunk_id = ?", chunkId)
                .and("revision = ?", revision));
        if (target == null) {
            throw new NotFoundException("分块 " + chunkId + " 没有版本 " + revision);
        }
        // ① 当前内容先进历史
        writeSnapshot(chunk, "rollback");
        // ② 目标版本内容写回正文
        chunk.setContent(target.getContent());
        chunk.setIsEnabled(target.getIsEnabled() == null ? 1 : target.getIsEnabled());
        chunk.setContentRevision((chunk.getContentRevision() == null ? 0 : chunk.getContentRevision()) + 1);
        chunk.setLastEditorId("system");
        chunkMapper.update(chunk, false);

        activityService.record(chunk.getKnowledgeBaseId(), KbActivityService.A_CHUNK_REVERT,
            "success", "chunk", chunkId,
            "{\"restored_from\":" + revision + ",\"new_revision\":" + chunk.getContentRevision() + "}");
        log.info("[分块] 已回滚 chunk={} 从 v{} 到新版本 v{}", chunkId, revision, chunk.getContentRevision());
        return chunkMapper.selectOneById(chunkId);
    }

    /** 版本历史（倒序）。 */
    public List<KbChunkRevisionRecord> listRevisions(String knowledgeId, String chunkId) {
        requireChunkOfKnowledge(knowledgeId, chunkId);
        return new ArrayList<>(revisionMapper.selectListByQuery(
            QueryWrapper.create()
                .where("chunk_id = ?", chunkId)
                .orderBy("revision", false)));
    }

    /** 删除单个分块（物理删 + 删投影 + 删修订历史）。 */
    @Transactional
    public void delete(String knowledgeId, String chunkId) {
        KbChunkRecord chunk = requireChunkOfKnowledge(knowledgeId, chunkId);
        // ⚠️ 三处都用 jdbc：同一事务里混用 Flex mapper 与 JdbcTemplate 写库会各持一条连接
        //    ⇒ SQLITE_BUSY（实测）。本方法是物理删，用 jdbc 更直白。
        jdbc.update("DELETE FROM kb_chunk_image WHERE chunk_id = ?", chunkId);
        jdbc.update("DELETE FROM kb_chunk_revision WHERE chunk_id = ?", chunkId);
        jdbc.update("DELETE FROM kb_chunk WHERE id = ?", chunkId);
        activityService.record(chunk.getKnowledgeBaseId(), KbActivityService.A_CHUNK_DELETE,
            "success", "chunk", chunkId, null);
        log.info("[分块] 已删除 chunk={} knowledge={}", chunkId, knowledgeId);
    }

    /**
     * 删除某文档下所有分块。
     *
     * <p>⚠️ 物理删而不是软删：源侧同端点语义是「清空分块以便重新解析」。
     * 保留 deleted=1 的行会让重新解析后出现新旧两批块混在一起。
     *
     * @return 删除行数
     */
    @Transactional
    public int deleteByKnowledge(String knowledgeId) {
        requireKnowledge(knowledgeId);
        int images = jdbc.update(
            "DELETE FROM kb_chunk_image WHERE knowledge_id = ?", knowledgeId);
        int revs = jdbc.update(
            "DELETE FROM kb_chunk_revision WHERE knowledge_id = ?", knowledgeId);
        int chunks = jdbc.update("DELETE FROM kb_chunk WHERE knowledge_id = ?", knowledgeId);
        log.info("[分块] 已清空 knowledge={} 分块={} 图片投影={} 修订={}",
            knowledgeId, chunks, images, revs);
        return chunks;
    }

    /**
     * 重建某文档的索引（FTS + 向量）。
     *
     * <p>⚠️ 分块编辑后必须调用 —— FTS 存正文副本，只改 chunk 表 ⇒ 搜索命中旧文本，且不报错。
     * <p>⛔ 本方法<b>不在调用方事务里</b>执行：{@code KbIndexWriter.reindexKnowledge}
     * 用独立 JDBC 连接写 FTS，在 SQLite 外层写事务里会自锁（缺陷 5）。
     * 因此 Controller 在事务提交之后再调它。
     */
    public void reindexChunkKnowledge(KbChunkRecord chunk) {
        if (chunk == null) {
            return;
        }
        try {
            indexWriter.reindexKnowledge(chunk.getKnowledgeId(), chunk.getKnowledgeBaseId(),
                null);
            log.info("[分块] 已重建索引 knowledge={}（因分块编辑）", chunk.getKnowledgeId());
        } catch (RuntimeException e) {
            // ⚠️ 不吞：索引没重建 = 搜索是旧的，必须让调用方知道。
            log.warn("[分块] 重建索引失败 knowledge={} 原因={}", chunk.getKnowledgeId(), e.getMessage());
            throw e;
        }
    }

    /**
     * 删除分块里的一条生成问句（源侧 {@code DELETE /chunks/by-id/{id}/questions}）。
     *
     * <p><b>⚠️ 与源侧的差异（如实）</b>：源侧每条问句有独立 {@code question_id}；
     * 本仓按 W6 的设计存在 {@code kb_chunk.metadata.generated_questions} 的<b>字符串数组</b>里，
     * 没有 id ⇒ {@code questionId} 按<b>问句文本</b>匹配。找不到时返回 false，⛔ 不静默成功。
     *
     * <p><b>⚠️ 必须重建索引</b>：问句会被拼进索引文本（{@code KbGeneratedQuestions.indexText}），
     * 只改 metadata 不重建 ⇒ 搜索仍能命中已删除的问句。重建在事务外（缺陷 5 教训）。
     *
     * @param chunkId    分块 ID
     * @param questionId 问句 id 或文本
     * @return true = 删掉了一条
     */
    public boolean deleteGeneratedQuestion(String chunkId, String questionId) {
        if (questionId == null || questionId.isBlank()) {
            throw new ValidationException("问题 ID（本仓为问句文本）不能为空");
        }
        // getById 自己抛 404（不存在），这里不需要再判空
        KbChunkRecord chunk = getById(chunkId);
        java.util.List<String> current =
            com.nexusai.kb.app.ingest.KbGeneratedQuestions.read(chunk.getMetadata());
        String key = questionId.trim();
        java.util.List<String> remaining = new java.util.ArrayList<>();
        boolean removed = false;
        for (String q : current) {
            if (!removed && q.equals(key)) {
                removed = true;   // 只删第一条匹配（同文本重复是数据问题，不在这里「顺手」扩权）
                continue;
            }
            remaining.add(q);
        }
        if (!removed) {
            return false;
        }
        chunk.setMetadata(writeGeneratedQuestions(chunk.getMetadata(), remaining));
        chunkMapper.update(chunk);
        log.info("[分块] 已删除生成问句 chunk={} 剩余={}", chunkId, remaining.size());
        reindexChunkKnowledge(chunk);
        return true;
    }

    /** 把问句数组写回 metadata（保留其它键；空数组时删掉该键）。 */
    private static String writeGeneratedQuestions(String metadataJson, java.util.List<String> questions) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode parsed =
                (metadataJson == null || metadataJson.isBlank()) ? null : om.readTree(metadataJson);
            com.fasterxml.jackson.databind.node.ObjectNode root =
                parsed != null && parsed.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) parsed
                    : om.createObjectNode();
            if (questions.isEmpty()) {
                root.remove(com.nexusai.kb.app.ingest.KbGeneratedQuestions.KEY);
            } else {
                com.fasterxml.jackson.databind.node.ArrayNode arr = om.createArrayNode();
                questions.forEach(arr::add);
                root.set(com.nexusai.kb.app.ingest.KbGeneratedQuestions.KEY, arr);
            }
            return root.isEmpty() ? null : om.writeValueAsString(root);
        } catch (Exception e) {
            throw new ValidationException("问句元数据写入失败: " + e.getMessage());
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /** 把当前块状态写成一条新 revision。 */
    private void writeSnapshot(KbChunkRecord chunk, String source) {
        Integer maxRev = jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision), 0) FROM kb_chunk_revision WHERE chunk_id = ?",
            Integer.class, chunk.getId());
        KbChunkRevisionRecord rev = new KbChunkRevisionRecord();
        rev.setId(UUID.randomUUID().toString());
        rev.setKnowledgeBaseId(chunk.getKnowledgeBaseId());
        rev.setKnowledgeId(chunk.getKnowledgeId());
        rev.setChunkId(chunk.getId());
        rev.setRevision((maxRev == null ? 0 : maxRev) + 1);
        rev.setContent(chunk.getContent() == null ? "" : chunk.getContent());
        rev.setIsEnabled(chunk.getIsEnabled() == null ? 1 : chunk.getIsEnabled());
        // ⚠️ editor_id / edit_source 都是 NOT NULL（无 DEFAULT 的列靠 Service 填）
        rev.setEditorId(chunk.getLastEditorId() == null ? "" : chunk.getLastEditorId());
        rev.setEditSource(source == null ? "user" : source);
        rev.setEditedAt(LocalDateTime.now());
        rev.setCreator("");
        rev.setUpdater("");
        rev.setDeleted(0);
        rev.setTenantId(0L);
        rev.setCreateTime(LocalDateTime.now());
        rev.setUpdateTime(LocalDateTime.now());
        revisionMapper.insert(rev);
    }

    private KbChunkRecord requireChunkOfKnowledge(String knowledgeId, String chunkId) {
        requireKnowledge(knowledgeId);
        KbChunkRecord chunk = getById(chunkId);
        if (!knowledgeId.equals(chunk.getKnowledgeId())) {
            // ⚠️ 不返回 403：跨文档访问按「不存在」处理，不泄露该块属于别的文档。
            throw new NotFoundException("分块 " + chunkId + " 不存在于文档 " + knowledgeId);
        }
        return chunk;
    }

    private KbKnowledgeRecord requireKnowledge(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            throw new ValidationException("文档 ID 不能为空");
        }
        KbKnowledgeRecord row = knowledgeMapper.selectOneById(knowledgeId);
        if (row == null) {
            throw new NotFoundException("文档 " + knowledgeId + " 不存在");
        }
        return row;
    }

    /** 时间格式化（出站）。 */
    public static String formatTime(LocalDateTime t) {
        return KbTimeFormat.format(t);
    }
}
