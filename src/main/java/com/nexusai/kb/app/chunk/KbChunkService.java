package com.nexusai.kb.app.chunk;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.domain.KbActivityService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbChunkRevisionRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbChunkRevisionMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
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
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbIndexWriter indexWriter;
    private final KbActivityService activityService;
    private final JdbcTemplate jdbc;
    /**
     * 问句重新生成复用入库后处理的同一套实现（取配置、调模型、重读 revision、重建索引）。
     *
     * <p>⚠️ 无循环依赖：{@code KbQuestionGenerationService} 只依赖 mapper / indexWriter /
     * sideCallClient，<b>不依赖本类</b>。
     */
    private final com.nexusai.kb.app.ingest.KbQuestionGenerationService questionGenerationService;

    @Autowired
    public KbChunkService(KbChunkMapper chunkMapper,
                          KbChunkRevisionMapper revisionMapper,
                          KbKnowledgeMapper knowledgeMapper,
                          KbKnowledgeBaseMapper knowledgeBaseMapper,
                          KbIndexWriter indexWriter,
                          KbActivityService activityService,
                          JdbcTemplate jdbc,
                          com.nexusai.kb.app.ingest.KbQuestionGenerationService questionGenerationService) {
        this.chunkMapper = chunkMapper;
        this.revisionMapper = revisionMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.indexWriter = indexWriter;
        this.activityService = activityService;
        this.jdbc = jdbc;
        this.questionGenerationService = questionGenerationService;
    }

    /**
     * 列出某文档的分块。
     *
     * @param knowledgeId 文档 ID
     * @param includeDisabled 是否含被禁用的块
     */
    /**
     * 列出某文档的分块。
     *
     * <p><b>⭐ 默认不含父块</b>（对齐源站 {@code handler/chunk.go:135}：
     * {@code chunkType := []types.ChunkType{types.ChunkTypeText}} —— 列表端点默认只列
     * {@code text}，其它类型要靠 {@code ?chunk_type=} 显式要）。父子模式下父块与子块共用
     * {@code chunk_index} 且正文高度重叠，混在一起会让「按 chunk_index 顺序拼回文档」的调用方
     * （分块管理列表 / 文档重建 / 预览）把同一段正文输出两遍。
     *
     * @param knowledgeId 文档 ID
     * @param includeDisabled 是否含被禁用的块
     */
    public List<KbChunkRecord> list(String knowledgeId, boolean includeDisabled) {
        return list(knowledgeId, includeDisabled, false);
    }

    /**
     * 列出某文档的分块（可控是否含父块）。
     *
     * @param knowledgeId     文档 ID
     * @param includeDisabled 是否含被禁用的块
     * @param includeParents  是否含 {@code chunk_type='parent_text'} 的父块
     *                        （诊断 / 分块管理需要看父块时才传 true）
     */
    public List<KbChunkRecord> list(String knowledgeId, boolean includeDisabled,
                                    boolean includeParents) {
        requireKnowledge(knowledgeId);
        QueryWrapper q = QueryWrapper.create().where("knowledge_id = ?", knowledgeId);
        if (!includeDisabled) {
            q.and("is_enabled = 1");
        }
        if (!includeParents) {
            // ⚠️ 必须同时放行 NULL：DDL 里 chunk_type 是 NOT NULL DEFAULT 'text'，
            //    但历史脏数据可能是 NULL；`NOT IN (...)` 对 NULL 求值为 NULL ⇒ 行被静默丢掉。
            // ⭐ 图片独立子块（image_caption / image_ocr）也在这里滤掉：它们与父块共用
            //    chunk_index、正文只是「一句描述 / 一段图内文字」，混进列表会让
            //    「按 chunk_index 拼回文档」的三个消费点（分块管理 / 预览 / agent 读文档）
            //    把同一段位置输出两遍。父块总包含这些文字所在的正文。
            q.and("(chunk_type IS NULL OR chunk_type NOT IN (?, ?, ?))",
                KbChunk.CHUNK_TYPE_PARENT_TEXT,
                KbChunk.CHUNK_TYPE_IMAGE_CAPTION,
                KbChunk.CHUNK_TYPE_IMAGE_OCR);
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

    /**
     * 新增 / 修改一条生成问句（源侧 {@code PUT /chunks/by-id/{id}/questions}）。
     *
     * <p><b>⭐⭐ 追加 vs 替换的判据就在 {@code questionId} 上</b>（见
     * {@link #upsertQuestionMetadata}）：<b>空 ⇒ 追加</b>，非空 ⇒ 按<b>问句文本</b>找到那条
     * 并<b>原地替换</b>。⛔ 找不到时抛 400，绝不「找不到就当新增」—— 那会让用户以为改掉了，
     * 实际列表里多出一条、旧的那条还在。
     *
     * <p>⚠️ 本仓问句没有独立 id（存在 metadata 的字符串/对象数组里）⇒ 前端编辑时要传
     * <b>旧问句文本</b>当 {@code question_id}。
     *
     * @param chunkId    分块 ID
     * @param questionId 空 = 新增；非空 = 要替换的旧问句文本
     * @param question   新问句
     * @return 更新后的分块 + 最新问句列表 + 是否新增
     */
    public QuestionUpsertResult upsertGeneratedQuestion(
        String chunkId, String questionId, String question
    ) {
        KbChunkRecord chunk = getById(chunkId);
        int revision = chunk.getContentRevision() == null ? 0 : chunk.getContentRevision();
        boolean added = questionId == null || questionId.isBlank();
        String merged = upsertQuestionMetadata(chunk.getMetadata(), questionId, question, revision);
        chunk.setMetadata(merged);
        // ⚠️ 只更新 metadata 这一列：content / start_at / end_at 的位置不变式不能被问句影响
        KbChunkRecord patch = new KbChunkRecord();
        patch.setId(chunk.getId());
        patch.setMetadata(merged);
        chunkMapper.update(patch);

        java.util.List<String> questions =
            com.nexusai.kb.app.ingest.KbGeneratedQuestions.read(merged);
        boolean indexOk = reindexQuietly(chunk);
        log.info("[分块] 已{}生成问句 chunk={} 文本={} 问句数={} 已重建索引={}",
            added ? "新增" : "修改", chunkId, question == null ? "" : question.trim(),
            questions.size(), indexOk);
        return new QuestionUpsertResult(chunk, questions, added, indexOk);
    }

    /**
     * 重新生成某分块的辅助问句（源侧 {@code POST /chunks/by-id/{id}/questions/regenerate}）。
     *
     * <p>复用入库后处理的 {@link com.nexusai.kb.app.ingest.KbQuestionGenerationService#generateBatch}：
     * 它内部会重读 contentRevision（生成期间用户编辑 ⇒ 跳过）、写回 metadata 并重建索引。
     *
     * <p>⚠️ 「跳过」不是错误：问句配置关闭 / 没有可用模型 / 无可生成文本时，
     * {@code generateBatch} 返回 SKIPPED 而不是抛异常 ⇒ 这里把状态与说明原样返回，
     * 前端照实展示，⛔ 不假装「已重新生成」。
     *
     * @param chunkId 分块 ID
     * @return 最新分块 + 问句列表 + 后处理结果（status/detail 用于如实告知用户）
     */
    public RegenerateResult regenerateGeneratedQuestions(String chunkId) {
        KbChunkRecord chunk = getById(chunkId);
        if (chunk.getChunkType() != null && !"text".equals(chunk.getChunkType())) {
            throw new ValidationException(
                "问句只能对文本分块生成（当前类型 " + chunk.getChunkType() + "）");
        }
        String knowledgeId = chunk.getKnowledgeId();
        KbKnowledgeRecord knowledge = knowledgeId == null || knowledgeId.isBlank()
            ? null : knowledgeMapper.selectOneById(knowledgeId);
        if (knowledge == null) {
            throw new NotFoundException("分块 " + chunkId + " 所属文档不存在");
        }
        int attempt = knowledge.getParseAttempt() == null ? 0 : knowledge.getParseAttempt();
        String kbId = chunk.getKnowledgeBaseId();
        KbKnowledgeBaseRecord kb = kbId == null || kbId.isBlank()
            ? null : knowledgeBaseMapper.selectOneById(kbId);
        int count = com.nexusai.kb.app.ingest.KbPostProcessConfig.resolve(kb)
            .question().questionCount();
        log.info("[分块API] 问句重新生成开始 chunk={} knowledge={} 每块条数={}",
            chunkId, knowledgeId, count);

        com.nexusai.kb.app.ingest.KbPostProcessOutcome outcome =
            questionGenerationService.generateBatch(
                knowledgeId, attempt, 0, java.util.List.of(chunkId), count);

        KbChunkRecord latest = getById(chunkId);
        java.util.List<String> questions =
            com.nexusai.kb.app.ingest.KbGeneratedQuestions.read(latest.getMetadata());
        log.info("[分块API] 问句重新生成结束 chunk={} 状态={} 说明={} 条数={}",
            chunkId, outcome.status(), outcome.detail(), questions.size());
        return new RegenerateResult(latest, questions,
            outcome.status().name(), outcome.detail());
    }

    /**
     * 问句「追加 / 替换」的<b>纯函数</b>（不碰库，单测直接打）。
     *
     * <p>⭐ 三条不可省的规则：
     * <ol>
     *   <li>{@code question} trim 后为空 ⇒ 400（源侧同款）；</li>
     *   <li>{@code questionId} 非空但<b>找不到</b> ⇒ 400，⛔ 不静默新增；</li>
     *   <li>替换要<b>保留该条的 id 与其它条目不动</b>（metadata 里还有 faq / image 等别的键）。</li>
     * </ol>
     *
     * @param metadataJson 原 metadata（可 null / 坏 JSON ⇒ 视为空对象）
     * @param questionId   空 = 新增；非空 = 旧问句文本
     * @param question     新问句
     * @param revision     当前 contentRevision（写进条目的 content_revision）
     * @return 新 metadata JSON
     */
    static String upsertQuestionMetadata(
        String metadataJson, String questionId, String question, int revision
    ) {
        String text = question == null ? "" : question.trim();
        if (text.isEmpty()) {
            throw new ValidationException("问句不能为空");
        }
        com.fasterxml.jackson.databind.ObjectMapper om =
            new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            com.fasterxml.jackson.databind.JsonNode parsed =
                (metadataJson == null || metadataJson.isBlank()) ? null : om.readTree(metadataJson);
            com.fasterxml.jackson.databind.node.ObjectNode root =
                parsed != null && parsed.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) parsed
                    : om.createObjectNode();
            com.fasterxml.jackson.databind.JsonNode existing =
                root.get(com.nexusai.kb.app.ingest.KbGeneratedQuestions.KEY);
            com.fasterxml.jackson.databind.node.ArrayNode arr =
                existing != null && existing.isArray()
                    ? (com.fasterxml.jackson.databind.node.ArrayNode) existing
                    : om.createArrayNode();

            if (questionId == null || questionId.isBlank()) {
                arr.add(newQuestionNode(om, text, revision));
            } else {
                String key = questionId.trim();
                boolean found = false;
                for (int i = 0; i < arr.size(); i++) {
                    com.fasterxml.jackson.databind.JsonNode n = arr.get(i);
                    String old = n.isTextual() ? n.asText() : n.path("question").asText("");
                    if (!found && old != null && old.trim().equals(key)) {
                        // ⭐ 原地替换：保留 id，只改文本与版本号
                        com.fasterxml.jackson.databind.node.ObjectNode item =
                            n.isObject()
                                ? (com.fasterxml.jackson.databind.node.ObjectNode) n
                                : om.createObjectNode();
                        item.put("question", text);
                        item.put("content_revision", revision);
                        if (!item.hasNonNull("id") || item.path("id").asText().isBlank()) {
                            item.put("id", generatedQuestionId(text, revision));
                        }
                        arr.set(i, item);
                        found = true;
                    }
                }
                if (!found) {
                    // ⛔ 不静默新增：用户以为在「改」，实际会多出一条
                    throw new ValidationException(
                        "该分块里没有这条问句（本仓问句无独立 id，question_id 需传旧问句文本）");
                }
            }
            root.set(com.nexusai.kb.app.ingest.KbGeneratedQuestions.KEY, arr);
            return om.writeValueAsString(root);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("问句元数据写入失败: " + e.getMessage());
        }
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode newQuestionNode(
        com.fasterxml.jackson.databind.ObjectMapper om, String question, int revision
    ) {
        com.fasterxml.jackson.databind.node.ObjectNode item = om.createObjectNode();
        item.put("id", generatedQuestionId(question, revision));
        item.put("question", question);
        item.put("content_revision", revision);
        return item;
    }

    /** 问句 id（与 {@code KbQuestionGenerationService#mergeQuestions} 同算法，便于对齐）。 */
    private static String generatedQuestionId(String question, int revision) {
        return "q" + Math.abs((question + revision).hashCode());
    }

    /**
     * 重建索引但<b>不否决写入</b>：问句已落 metadata，索引失败只影响「能不能搜到新问句」。
     * <p>⚠️ 必须打 ERROR —— 否则用户以为改好了，实际检索仍是旧的，且全程无提示。
     *
     * @return true = 索引重建成功
     */
    private boolean reindexQuietly(KbChunkRecord chunk) {
        try {
            reindexChunkKnowledge(chunk);
            return true;
        } catch (RuntimeException e) {
            log.error("[分块] 问句已保存但索引重建失败 chunk={} knowledge={} —— 搜索仍是旧文本",
                chunk.getId(), chunk.getKnowledgeId(), e);
            return false;
        }
    }

    /** 问句 upsert 结果。 */
    public record QuestionUpsertResult(
        KbChunkRecord chunk, java.util.List<String> questions,
        boolean added, boolean indexRebuilt) {
    }

    /** 问句重新生成结果。 */
    public record RegenerateResult(
        KbChunkRecord chunk, java.util.List<String> questions,
        String status, String detail) {
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
