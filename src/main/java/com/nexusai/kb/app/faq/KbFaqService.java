package com.nexusai.kb.app.faq;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.image.KbImageService;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.app.retrieval.KbIndexWriter;
import com.nexusai.kb.app.tag.KbTagService;
import com.nexusai.kb.domain.KbActivityService;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FAQ 知识库服务 · 批次 W5（源侧「FAQ管理」14 端点）。
 *
 * <h2>⭐⭐ FAQ 条目 = {@code kb_chunk} 的一行，不是独立表</h2>
 * 见 O-3，与源侧一致：
 * <ul>
 *   <li>每个 FAQ 知识库下有一个 <b>type=faq 的容器 knowledge</b>（懒创建）；</li>
 *   <li>每个 FAQ 条目 = 该容器下的一个 chunk（{@code chunk_type='faq'}）；</li>
 *   <li>条目字段全在 {@code chunk.metadata}（JSON，见 {@link KbFaqMetadata}）；</li>
 *   <li>对外 {@code entry_id} = {@code kb_chunk.seq_id}（<b>整数</b>，不是 UUID）；</li>
 *   <li>标签 = {@code kb_chunk.tag_id}（存标签 UUID，对外暴露 seq_id）。</li>
 * </ul>
 *
 * <h2>⭐⭐ seq_id 必须全表唯一</h2>
 * 源侧按 {@code seq_id} 单独查条目（不带 kb 条件），所以本仓也用
 * {@code MAX(seq_id)+1} 生成 <b>全局</b> 值 —— 不是「每个库从 1 开始」。
 * 若按库重置，两个库的同号条目会互相串（查 A 库的 5 号可能拿到 B 库的 5 号）。
 *
 * <h2>⭐⭐ 索引重建必须在事务外</h2>
 * {@code KbIndexWriter} 用独立连接写 FTS。本类所有写方法都<b>不加</b>
 * {@code @Transactional}（与缺陷 5/15/16 同根因），改为「先写库、再重建索引」。
 * 代价是中途失败会留「条目在库、索引没更新」—— 可用重建索引端点修复，且幂等。
 */
@Service
public class KbFaqService {

    private static final Logger log = LoggerFactory.getLogger(KbFaqService.class);

    /** 单条标准问/相似问/答案的长度上限（源侧 swagger 写 8000 runes，这里按字符数近似）。 */
    private static final int MAX_TEXT_CHARS = 8_000;

    /** 单次批量导入条目上限。 */
    private static final int MAX_BATCH_ENTRIES = 500;

    /** FAQ 列表默认页大小。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    /** FAQ 容器 knowledge 的标题后缀（仅内部识别用，不展示）。 */
    private static final String FAQ_KNOWLEDGE_SOURCE = "faq";

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeBaseMapper baseMapper;
    private final KbChunkMapper chunkMapper;
    private final KbIndexWriter indexWriter;
    private final KbTagService tagService;
    private final KbActivityService activityService;
    private final JdbcTemplate jdbc;
    private final KbHybridSearcher searcher;

    @Autowired
    public KbFaqService(KbKnowledgeMapper knowledgeMapper,
                        KbKnowledgeBaseMapper baseMapper,
                        KbChunkMapper chunkMapper,
                        KbIndexWriter indexWriter,
                        KbTagService tagService,
                        KbActivityService activityService,
                        JdbcTemplate jdbc,
                        KbHybridSearcher searcher) {
        this.knowledgeMapper = knowledgeMapper;
        this.baseMapper = baseMapper;
        this.chunkMapper = chunkMapper;
        this.indexWriter = indexWriter;
        this.tagService = tagService;
        this.activityService = activityService;
        this.jdbc = jdbc;
        this.searcher = searcher;
    }

    // ── 列表 ────────────────────────────────────────────────────────

    /**
     * 分页列出 FAQ 条目。
     *
     * @param kbId         知识库（必须 type=faq）
     * @param page         页码（1 起）
     * @param pageSize     每页条数
     * @param tagSeqIds    标签 seq_id 过滤（可空；空 = 不过滤）
     * @param keyword      关键词（在标准问 / 相似问 / 答案里找）
     * @param searchField  搜索范围（standard / similar / answer / all）
     * @param sortOrder    asc | desc（按 seq_id）
     * @param isEnabled    启用过滤（null = 不过滤 —— 必须区分「没传」和「传 false」）
     */
    public Page list(String kbId, Integer page, Integer pageSize, List<Long> tagSeqIds,
                     String keyword, String searchField, String sortOrder, Boolean isEnabled) {
        KbKnowledgeBaseRecord kb = requireFaqBase(kbId);
        KbKnowledgeRecord container = findFaqKnowledge(kbId);
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE
            : Math.min(pageSize, MAX_PAGE_SIZE);
        if (container == null) {
            return new Page(List.of(), p, size, 0);
        }

        StringBuilder where = new StringBuilder(" FROM kb_chunk WHERE knowledge_id = ? "
            + "AND deleted = 0 AND chunk_type = 'faq'");
        List<Object> args = new ArrayList<>();
        args.add(container.getId());
        if (isEnabled != null) {
            where.append(" AND is_enabled = ?");
            args.add(isEnabled ? 1 : 0);
        }
        if (tagSeqIds != null && !tagSeqIds.isEmpty()) {
            List<String> tagUuids = new ArrayList<>(tagSeqIds.size());
            for (Long seq : tagSeqIds) {
                KbChunkTag tag = findTagBySeqId(kbId, seq);
                if (tag != null) {
                    tagUuids.add(tag.id());
                }
            }
            if (tagUuids.isEmpty()) {
                // 过滤的标签都不存在 ⇒ 结果必然为空（⛔ 不要退化成「不过滤」）
                return new Page(List.of(), p, size, 0);
            }
            where.append(" AND tag_id IN (")
                .append(String.join(",", java.util.Collections.nCopies(tagUuids.size(), "?")))
                .append(")");
            args.addAll(tagUuids);
        }

        // 关键词过滤：在 metadata JSON 上 LIKE（简单可靠；FTS 留给 /faq/search 的语义检索）
        String kw = keyword == null ? "" : keyword.trim();
        String field = searchField == null || searchField.isBlank()
            ? "all" : searchField.trim().toLowerCase(Locale.ROOT);
        if (!kw.isEmpty()) {
            String like = "%" + escapeLike(kw) + "%";
            switch (field) {
                case "standard" -> {
                    where.append(" AND json_extract(metadata, '$.standard_question') LIKE ? ESCAPE '\\'");
                    args.add(like);
                }
                case "similar" -> {
                    where.append(" AND json_extract(metadata, '$.similar_questions') LIKE ? ESCAPE '\\'");
                    args.add(like);
                }
                case "answer" -> {
                    where.append(" AND json_extract(metadata, '$.answers') LIKE ? ESCAPE '\\'");
                    args.add(like);
                }
                default -> {
                    where.append(" AND (json_extract(metadata, '$.standard_question') LIKE ? ESCAPE '\\'"
                        + " OR json_extract(metadata, '$.similar_questions') LIKE ? ESCAPE '\\'"
                        + " OR json_extract(metadata, '$.answers') LIKE ? ESCAPE '\\')");
                    args.add(like);
                    args.add(like);
                    args.add(like);
                }
            }
        }

        Integer total = jdbc.queryForObject("SELECT COUNT(*)" + where,
            Integer.class, args.toArray());
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        String sql = "SELECT *" + where + " ORDER BY seq_id " + (asc ? "ASC" : "DESC")
            + " LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((p - 1) * size);

        List<KbChunkRecord> chunks = jdbc.query(sql, (rs, i) -> mapChunk(rs), pageArgs.toArray());
        return new Page(toEntries(chunks, kb), p, size, total == null ? 0 : total);
    }

    /** 取单条（按 seq_id）。 */
    public Entry get(String kbId, long entrySeqId) {
        KbKnowledgeBaseRecord kb = requireFaqBase(kbId);
        KbChunkRecord chunk = requireFaqChunk(kbId, entrySeqId);
        return toEntry(chunk, kb);
    }

    // ── 创建 / 更新 ─────────────────────────────────────────────────

    /**
     * 创建单条 FAQ（同步）。
     *
     * <p>⚠️ 同步索引：创建完立刻写 FTS，否则用户建完搜不到（缺陷 1 同族）。
     * 索引失败 ⇒ 删掉刚建的 chunk 并报错（不留「在库但搜不到」的残留）。
     */
    public Entry create(String kbId, EntryInput input) {
        KbKnowledgeBaseRecord kb = requireFaqBase(kbId);
        KbFaqMetadata.Entry meta = parseInput(input);
        checkSelfDuplicates(meta);
        checkCrossEntryDuplicates(kbId, null, meta);

        KbKnowledgeRecord container = ensureFaqKnowledge(kb);
        String tagUuid = resolveTag(kbId, input.tagId(), input.tagName());
        String indexMode = KbFaqMetadata.resolveIndexMode(kb.getFaqConfig());

        KbChunkRecord chunk = new KbChunkRecord();
        chunk.setId(UUID.randomUUID().toString());
        chunk.setKnowledgeBaseId(kbId);
        chunk.setKnowledgeId(container.getId());
        chunk.setContent(KbFaqMetadata.renderContent(meta, indexMode));
        chunk.setChunkIndex(0);
        chunk.setChunkType("faq");
        chunk.setMetadata(KbFaqMetadata.toJson(meta));
        chunk.setTagId(tagUuid);
        chunk.setIsEnabled(input.isEnabled() == null || input.isEnabled() ? 1 : 0);
        chunk.setFlags(input.isRecommended() == null || input.isRecommended()
            ? KbFaqMetadata.FLAG_RECOMMENDED : 0);
        chunk.setStatus(KbFaqMetadata.STATUS_INDEXED);
        // ── 全列显式赋值（缺陷 14：Flex 的 insert 会把 null 也写进 INSERT ⇒ DEFAULT 不生效）
        chunk.setStartAt(0);
        chunk.setEndAt(0);
        chunk.setSourceContent("");
        chunk.setContentRevision(0);
        chunk.setIndexStatus("ready");
        chunk.setLastEditorId("");
        chunk.setContextHeader("");
        chunk.setCreator("");
        chunk.setUpdater("");
        chunk.setDeleted(0);
        chunk.setTenantId(0L);
        chunk.setCreateTime(LocalDateTime.now());
        chunk.setUpdateTime(LocalDateTime.now());
        // seq_id 全局唯一（见类注释）
        chunk.setSeqId(nextSeqId());

        chunkMapper.insert(chunk);
        indexSafely(container, kb);

        activityService.record(kbId, "faq_create", "success", "chunk", chunk.getId(),
            "{\"seq_id\":" + chunk.getSeqId() + "}");
        log.info("[FAQ] 已创建 kb={} seq_id={} 标准问={}", kbId, chunk.getSeqId(),
            meta.standardQuestion());
        return toEntry(chunkMapper.selectOneById(chunk.getId()), kb);
    }

    /**
     * 更新条目（<b>整体替换</b>语义）。
     *
     * <p>⚠️ 源侧明确写着「Replaces the whole entry」：前端必须先 GET 再 PUT 全字段，
     * 漏传的字段会被清空。本仓照抄 —— 这是前后端契约，不是缺陷。
     */
    public Entry update(String kbId, long entrySeqId, EntryInput input) {
        KbKnowledgeBaseRecord kb = requireFaqBase(kbId);
        KbChunkRecord chunk = requireFaqChunk(kbId, entrySeqId);
        KbFaqMetadata.Entry meta = parseInput(input);
        checkSelfDuplicates(meta);
        checkCrossEntryDuplicates(kbId, chunk.getId(), meta);

        String tagUuid = resolveTag(kbId, input.tagId(), input.tagName());
        String indexMode = KbFaqMetadata.resolveIndexMode(kb.getFaqConfig());

        chunk.setContent(KbFaqMetadata.renderContent(meta, indexMode));
        chunk.setMetadata(KbFaqMetadata.toJson(meta));
        chunk.setTagId(tagUuid);
        if (input.isEnabled() != null) {
            chunk.setIsEnabled(input.isEnabled() ? 1 : 0);
        }
        if (input.isRecommended() != null) {
            chunk.setFlags(input.isRecommended() ? KbFaqMetadata.FLAG_RECOMMENDED : 0);
        }
        chunk.setUpdateTime(LocalDateTime.now());
        chunkMapper.update(chunk, false);

        KbKnowledgeRecord container = knowledgeMapper.selectOneById(chunk.getKnowledgeId());
        indexSafely(container, kb);

        activityService.record(kbId, "faq_update", "success", "chunk", chunk.getId(), null);
        log.info("[FAQ] 已更新 kb={} seq_id={}", kbId, entrySeqId);
        return toEntry(chunkMapper.selectOneById(chunk.getId()), kb);
    }

    /** 追加相似问（不整体替换，只往列表里加）。 */
    public Entry addSimilarQuestions(String kbId, long entrySeqId, List<String> questions) {
        KbKnowledgeBaseRecord kb = requireFaqBase(kbId);
        KbChunkRecord chunk = requireFaqChunk(kbId, entrySeqId);
        List<String> incoming = KbFaqMetadata.sanitizeList(questions);
        if (incoming.isEmpty()) {
            throw new ValidationException("相似问列表不能为空");
        }
        KbFaqMetadata.Entry meta = readMeta(chunk);

        LinkedHashSet<String> existing = new LinkedHashSet<>();
        existing.add(meta.standardQuestion());
        existing.addAll(meta.similarQuestions());
        LinkedHashSet<String> negatives = new LinkedHashSet<>(meta.negativeQuestions());

        List<String> merged = new ArrayList<>(meta.similarQuestions());
        for (String q : incoming) {
            if (existing.contains(q)) {
                // 已有（或与标准问相同）⇒ 跳过，不报错（源侧同样是静默去重）
                continue;
            }
            if (negatives.contains(q)) {
                throw new ValidationException("相似问「" + q + "」与已有反例相同");
            }
            merged.add(q);
            existing.add(q);
        }
        if (merged.size() == meta.similarQuestions().size()) {
            // 一条都没加进去 ⇒ 直接返回（避免无谓的重建索引）
            return toEntry(chunk, kb);
        }

        KbFaqMetadata.Entry updated = new KbFaqMetadata.Entry(meta.standardQuestion(), merged,
            meta.negativeQuestions(), meta.answers(), meta.answerStrategy(),
            meta.version(), meta.source());
        String indexMode = KbFaqMetadata.resolveIndexMode(kb.getFaqConfig());
        chunk.setContent(KbFaqMetadata.renderContent(updated, indexMode));
        chunk.setMetadata(KbFaqMetadata.toJson(updated));
        chunk.setUpdateTime(LocalDateTime.now());
        chunkMapper.update(chunk, false);
        indexSafely(knowledgeMapper.selectOneById(chunk.getKnowledgeId()), kb);
        log.info("[FAQ] 已追加相似问 kb={} seq_id={} 新增={}", kbId, entrySeqId,
            merged.size() - meta.similarQuestions().size());
        return toEntry(chunkMapper.selectOneById(chunk.getId()), kb);
    }

    // ── 删除 ────────────────────────────────────────────────────────

    /**
     * 批量删除。
     *
     * <p>⚠️ 物理删（不是逻辑删）：FAQ 条目的 seq_id 会永久占位，
     * 留着软删行会让「同一标准问」的判重永远命中已删条目。
     */
    public int delete(String kbId, List<Long> entrySeqIds) {
        requireFaqBase(kbId);
        if (entrySeqIds == null || entrySeqIds.isEmpty()) {
            throw new ValidationException("请选择需要删除的 FAQ 条目");
        }
        List<KbChunkRecord> chunks = new ArrayList<>();
        for (Long seq : entrySeqIds) {
            KbChunkRecord c = requireFaqChunk(kbId, seq);
            chunks.add(c);
        }
        int deleted = 0;
        for (KbChunkRecord c : chunks) {
            jdbc.update("DELETE FROM kb_chunk WHERE id = ? AND chunk_type = 'faq'", c.getId());
            deleted++;
        }
        if (!chunks.isEmpty()) {
            indexSafely(knowledgeMapper.selectOneById(chunks.get(0).getKnowledgeId()),
                requireFaqBase(kbId));
        }
        activityService.record(kbId, "faq_delete", "success", "chunk", null,
            "{\"deleted\":" + deleted + "}");
        log.info("[FAQ] 已删除 kb={} 条数={}", kbId, deleted);
        return deleted;
    }

    // ── 批量导入 / 导出 ─────────────────────────────────────────────

    /**
     * 批量 upsert。
     *
     * <p>两种模式（源侧 {@code FAQBatchUpsertPayload.mode}）：
     * <ul>
     *   <li>{@code append}（默认）：按标准问判重，已存在则<b>跳过</b>（不覆盖）；</li>
     *   <li>{@code replace}：先清空该库全部 FAQ 条目，再全量写入。</li>
     * </ul>
     *
     * <p>⚠️ 逐条独立：一条失败不影响其它条；返回成功 / 失败明细，
     * 让用户能精确修正后重试（源侧同样如此）。
     */
    public ImportResult upsertBatch(String kbId, List<EntryInput> entries, String mode) {
        KbKnowledgeBaseRecord kb = requireFaqBase(kbId);
        if (entries == null || entries.isEmpty()) {
            throw new ValidationException("entries 不能为空");
        }
        if (entries.size() > MAX_BATCH_ENTRIES) {
            throw new ValidationException("单次最多导入 " + MAX_BATCH_ENTRIES + " 条");
        }
        boolean replace = mode != null && mode.trim().equalsIgnoreCase("replace");
        if (replace) {
            List<String> ids = jdbc.queryForList(
                "SELECT id FROM kb_chunk WHERE knowledge_id IN "
                    + "(SELECT id FROM kb_knowledge WHERE knowledge_base_id = ? AND type = 'faq') "
                    + "AND chunk_type = 'faq'", String.class, kbId);
            for (String id : ids) {
                jdbc.update("DELETE FROM kb_chunk WHERE id = ?", id);
            }
            log.info("[FAQ] replace 模式先清空 kb={} 条数={}", kbId, ids.size());
        }

        KbKnowledgeRecord container = ensureFaqKnowledge(kb);
        String indexMode = KbFaqMetadata.resolveIndexMode(kb.getFaqConfig());
        List<Long> created = new ArrayList<>();
        Map<Integer, String> failed = new LinkedHashMap<>();

        for (int i = 0; i < entries.size(); i++) {
            EntryInput in = entries.get(i);
            try {
                KbFaqMetadata.Entry meta = parseInput(in);
                checkSelfDuplicates(meta);
                if (!replace) {
                    // append 模式：重复就跳过（⛔ 不覆盖已有条目）
                    try {
                        checkCrossEntryDuplicates(kbId, null, meta);
                    } catch (ConflictException dup) {
                        continue;
                    }
                }
                String tagUuid = resolveTag(kbId, in.tagId(), in.tagName());
                KbChunkRecord chunk = new KbChunkRecord();
                chunk.setId(UUID.randomUUID().toString());
                chunk.setKnowledgeBaseId(kbId);
                chunk.setKnowledgeId(container.getId());
                chunk.setContent(KbFaqMetadata.renderContent(meta, indexMode));
                chunk.setChunkIndex(0);
                chunk.setChunkType("faq");
                chunk.setMetadata(KbFaqMetadata.toJson(meta));
                chunk.setTagId(tagUuid);
                chunk.setIsEnabled(in.isEnabled() == null || in.isEnabled() ? 1 : 0);
                chunk.setFlags(in.isRecommended() == null || in.isRecommended()
                    ? KbFaqMetadata.FLAG_RECOMMENDED : 0);
                chunk.setStatus(KbFaqMetadata.STATUS_INDEXED);
                chunk.setStartAt(0);
                chunk.setEndAt(0);
                chunk.setSourceContent("");
                chunk.setContentRevision(0);
                chunk.setIndexStatus("ready");
                chunk.setLastEditorId("");
                chunk.setContextHeader("");
                chunk.setCreator("");
                chunk.setUpdater("");
                chunk.setDeleted(0);
                chunk.setTenantId(0L);
                chunk.setCreateTime(LocalDateTime.now());
                chunk.setUpdateTime(LocalDateTime.now());
                chunk.setSeqId(nextSeqId());
                chunkMapper.insert(chunk);
                created.add(chunk.getSeqId());
            } catch (RuntimeException e) {
                failed.put(i, e.getMessage());
            }
        }
        indexSafely(container, kb);
        activityService.record(kbId, "faq_import", failed.isEmpty() ? "success" : "failure",
            "chunk", null, "{\"created\":" + created.size() + ",\"failed\":" + failed.size() + "}");
        log.info("[FAQ] 批量导入 kb={} 模式={} 成功={} 失败={}", kbId,
            replace ? "replace" : "append", created.size(), failed.size());
        return new ImportResult(created, failed);
    }

    /** 导出全部条目（供「导出 → 编辑 → 重新导入」闭环）。 */
    public List<Entry> exportAll(String kbId) {
        KbKnowledgeBaseRecord kb = requireFaqBase(kbId);
        KbKnowledgeRecord container = findFaqKnowledge(kbId);
        if (container == null) {
            return List.of();
        }
        List<KbChunkRecord> chunks = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", container.getId())
                .and("chunk_type = 'faq'")
                .orderBy("seq_id", true));
        return toEntries(chunks, kb);
    }

    // ── 批量改字段 / 改标签 ─────────────────────────────────────────

    /**
     * 批量改字段（按条目 ID 或按标签）。
     *
     * @param byEntrySeqId  entrySeqId → 更新（可空）
     * @param byTagSeqId    tagSeqId → 更新（可空）
     * @param excludeSeqIds 按标签批量时排除的条目
     * @return 实际更新条数
     */
    public int updateFieldsBatch(String kbId, Map<Long, FieldUpdate> byEntrySeqId,
                                 Map<Long, FieldUpdate> byTagSeqId, List<Long> excludeSeqIds) {
        KbKnowledgeBaseRecord kb = requireFaqBase(kbId);
        if ((byEntrySeqId == null || byEntrySeqId.isEmpty())
            && (byTagSeqId == null || byTagSeqId.isEmpty())) {
            return 0;
        }
        List<KbChunkRecord> targets = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        if (byEntrySeqId != null) {
            for (Map.Entry<Long, FieldUpdate> e : byEntrySeqId.entrySet()) {
                KbChunkRecord c = requireFaqChunk(kbId, e.getKey());
                if (seen.add(c.getId())) {
                    applyFieldUpdate(c, e.getValue(), kbId);
                    targets.add(c);
                }
            }
        }
        if (byTagSeqId != null && !byTagSeqId.isEmpty()) {
            LinkedHashSet<Long> excluded = excludeSeqIds == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(excludeSeqIds);
            for (Map.Entry<Long, FieldUpdate> e : byTagSeqId.entrySet()) {
                KbChunkTag tag = findTagBySeqId(kbId, e.getKey());
                if (tag == null) {
                    throw new NotFoundException("标签 " + e.getKey() + " 不存在");
                }
                List<KbChunkRecord> byTag = new ArrayList<>(chunkMapper.selectListByQuery(
                    QueryWrapper.create().where("tag_id = ?", tag.id())
                        .and("chunk_type = 'faq'")));
                for (KbChunkRecord c : byTag) {
                    if (c.getSeqId() != null && excluded.contains(c.getSeqId())) {
                        continue;
                    }
                    if (!seen.add(c.getId())) {
                        continue;
                    }
                    applyFieldUpdate(c, e.getValue(), kbId);
                    targets.add(c);
                }
            }
        }

        for (KbChunkRecord c : targets) {
            c.setUpdateTime(LocalDateTime.now());
            chunkMapper.update(c, false);
        }
        if (!targets.isEmpty()) {
            indexSafely(knowledgeMapper.selectOneById(targets.get(0).getKnowledgeId()), kb);
        }
        log.info("[FAQ] 批量改字段 kb={} 更新条数={}", kbId, targets.size());
        return targets.size();
    }

    /** 批量改标签（entrySeqId → tagSeqId；null = 解除标签）。 */
    public int updateTagsBatch(String kbId, Map<Long, Long> updates) {
        if (updates == null || updates.isEmpty()) {
            return 0;
        }
        Map<Long, FieldUpdate> byId = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : updates.entrySet()) {
            byId.put(e.getKey(), new FieldUpdate(null, null, e.getValue()));
        }
        return updateFieldsBatch(kbId, byId, null, null);
    }

    private void applyFieldUpdate(KbChunkRecord chunk, FieldUpdate u, String kbId) {
        if (u == null) {
            return;
        }
        if (u.isEnabled() != null) {
            chunk.setIsEnabled(u.isEnabled() ? 1 : 0);
        }
        if (u.isRecommended() != null) {
            chunk.setFlags(u.isRecommended() ? KbFaqMetadata.FLAG_RECOMMENDED : 0);
        }
        if (u.tagSeqId() != null) {
            // 0 = 解除标签
            chunk.setTagId(u.tagSeqId() == 0 ? "" : resolveTagUuid(kbId, u.tagSeqId()));
        }
    }

    // ── 搜索 ────────────────────────────────────────────────────────

    /**
     * FAQ 语义搜索。
     *
     * <p>⚠️ 与 {@code /knowledge-bases/{id}/hybrid-search} 的区别：
     * 这个只在 FAQ 容器内搜、返回 FAQ 条目结构；那个搜全文档。
     *
     * @param query           问句
     * @param matchCount      返回条数（默认 10，上限 50）
     * @param onlyRecommended 只要推荐条目
     * @param firstTagSeqIds  第一优先级标签（限范围）
     * @param secondTagSeqIds 第二优先级标签
     */
    public List<Entry> search(String kbId, String query, Integer matchCount,
                              boolean onlyRecommended,
                              List<Long> firstTagSeqIds, List<Long> secondTagSeqIds) {
        KbKnowledgeBaseRecord kb = requireFaqBase(kbId);
        if (query == null || query.isBlank()) {
            throw new ValidationException("query_text 不能为空");
        }
        KbKnowledgeRecord container = findFaqKnowledge(kbId);
        if (container == null) {
            return List.of();
        }
        int limit = matchCount == null || matchCount <= 0 ? 10 : Math.min(matchCount, 50);

        // 用既有混合检索（FTS trigram + 向量），限定在 FAQ 容器对应的文档上
        List<KbHybridSearcher.Hit> hits;
        try {
            hits = searcher.search(kbId, query.trim(), limit * 3,
                kb.getEmbeddingModelId() == null || kb.getEmbeddingModelId().isBlank()
                    ? null : kb.getEmbeddingModelId());
        } catch (RuntimeException e) {
            log.warn("[FAQ] 检索失败 kb={} 原因={}，返回空", kbId, e.getMessage());
            return List.of();
        }

        LinkedHashSet<String> firstTagUuids = resolveTagUuids(kbId, firstTagSeqIds);
        LinkedHashSet<String> secondTagUuids = resolveTagUuids(kbId, secondTagSeqIds);

        List<Entry> out = new ArrayList<>();
        for (KbHybridSearcher.Hit hit : hits) {
            KbChunkRecord chunk = chunkMapper.selectOneById(hit.chunkId());
            if (chunk == null || !"faq".equals(chunk.getChunkType())) {
                continue;
            }
            if (chunk.getIsEnabled() != null && chunk.getIsEnabled() == 0) {
                continue;
            }
            if (onlyRecommended && (chunk.getFlags() == null
                || (chunk.getFlags() & KbFaqMetadata.FLAG_RECOMMENDED) == 0)) {
                continue;
            }
            // 标签优先级：一优先命中就收；否则二优先；都没有则不过滤
            String tag = chunk.getTagId() == null ? "" : chunk.getTagId();
            if (!firstTagUuids.isEmpty()) {
                if (!firstTagUuids.contains(tag)) {
                    continue;
                }
            } else if (!secondTagUuids.isEmpty() && !secondTagUuids.contains(tag)) {
                continue;
            }
            out.add(toEntry(chunk, kb, (double) hit.score()));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    // ── 导入结果展示状态 ────────────────────────────────────────────

    /**
     * 更新「最后一次导入结果」的展示状态。
     *
     * <p>⚠️ 存在 {@code kb_knowledge.last_faq_import_result}（FAQ 容器上），
     * 不是新建表 —— 源侧同样如此。没有容器时返回 false（还没导入过，无需展示）。
     *
     * @param displayed true = 已展示（前端可隐藏红点）
     * @return 实际写入的值
     */
    public boolean markImportResultDisplayed(String kbId, Boolean displayed) {
        requireFaqBase(kbId);
        KbKnowledgeRecord container = findFaqKnowledge(kbId);
        if (container == null) {
            return false;
        }
        boolean value = Boolean.TRUE.equals(displayed);
        // 源侧该列是 JSON，形如 {"displayed": true}
        jdbc.update("UPDATE kb_knowledge SET last_faq_import_result = ?, update_time = ? WHERE id = ?",
            "{\"displayed\":" + value + "}",
            KbTimeFormat.format(LocalDateTime.now()), container.getId());
        log.info("[FAQ] 导入结果展示状态 kb={} displayed={}", kbId, value);
        return value;
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /** 解析输入 → 元数据；长度校验在这里统一做。 */
    private static KbFaqMetadata.Entry parseInput(EntryInput in) {
        if (in == null) {
            throw new ValidationException("请求体不能为空");
        }
        KbFaqMetadata.Entry meta;
        try {
            meta = KbFaqMetadata.fromPayload(in.standardQuestion(), in.similarQuestions(),
                in.negativeQuestions(), in.answers(), in.answerStrategy());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        checkLength("标准问", meta.standardQuestion());
        for (String q : meta.similarQuestions()) {
            checkLength("相似问", q);
        }
        for (String q : meta.negativeQuestions()) {
            checkLength("反例问题", q);
        }
        for (String a : meta.answers()) {
            checkLength("答案", a);
        }
        return meta;
    }

    private static void checkLength(String label, String text) {
        if (text != null && text.length() > MAX_TEXT_CHARS) {
            throw new ValidationException(label + "超过 " + MAX_TEXT_CHARS + " 字符上限");
        }
    }

    /**
     * 条目内部自检（对齐源侧 1–3 步）：
     * 相似问 ≠ 标准问、相似问互不重复、反例不与正例重复、反例互不重复。
     */
    static void checkSelfDuplicates(KbFaqMetadata.Entry meta) {
        LinkedHashSet<String> similar = new LinkedHashSet<>();
        for (String q : meta.similarQuestions()) {
            if (q.equals(meta.standardQuestion())) {
                throw new ValidationException("相似问「" + q + "」不能与标准问相同");
            }
            if (!similar.add(q)) {
                throw new ValidationException("相似问「" + q + "」重复");
            }
        }
        LinkedHashSet<String> positive = new LinkedHashSet<>(similar);
        positive.add(meta.standardQuestion());
        LinkedHashSet<String> negatives = new LinkedHashSet<>();
        for (String q : meta.negativeQuestions()) {
            if (positive.contains(q)) {
                throw new ValidationException("反例问题「" + q + "」不能与标准问或相似问相同");
            }
            if (!negatives.add(q)) {
                throw new ValidationException("反例问题「" + q + "」重复");
            }
        }
    }

    /**
     * 跨条目判重：标准问与相似问都不能与<b>其它条目</b>的标准问/相似问重复。
     *
     * <p>⚠️ 用<b>归一化</b>后比较（大小写、全半角、标点、空白都不影响），
     * 否则「如何申请？」和「如何申请」会被当成两条不同条目 —— 用户会觉得判重失灵。
     *
     * @param excludeChunkId 更新时排除自己
     * @throws ConflictException 有重复
     */
    private void checkCrossEntryDuplicates(String kbId, String excludeChunkId,
                                           KbFaqMetadata.Entry meta) {
        KbKnowledgeRecord container = findFaqKnowledge(kbId);
        if (container == null) {
            return;
        }
        QueryWrapper q = QueryWrapper.create()
            .where("knowledge_id = ?", container.getId())
            .and("chunk_type = 'faq'");
        if (excludeChunkId != null) {
            q.and("id <> ?", excludeChunkId);
        }
        List<KbChunkRecord> existing = chunkMapper.selectListByQuery(q);

        // 把所有已有问法的归一化形式 → 原文，便于报错时给用户看原文
        Map<String, String> existingNormalized = new HashMap<>();
        for (KbChunkRecord c : existing) {
            KbFaqMetadata.Entry e = KbFaqMetadata.fromJson(c.getMetadata());
            if (e == null) {
                continue;
            }
            String n = KbFaqMetadata.normalize(e.standardQuestion());
            if (!n.isEmpty()) {
                existingNormalized.putIfAbsent(n, e.standardQuestion());
            }
            for (String s : e.similarQuestions()) {
                String sn = KbFaqMetadata.normalize(s);
                if (!sn.isEmpty()) {
                    existingNormalized.putIfAbsent(sn, s);
                }
            }
        }

        String stdNorm = KbFaqMetadata.normalize(meta.standardQuestion());
        if (existingNormalized.containsKey(stdNorm)) {
            throw new ConflictException("标准问「" + meta.standardQuestion() + "」已存在");
        }
        for (String s : meta.similarQuestions()) {
            String sn = KbFaqMetadata.normalize(s);
            if (existingNormalized.containsKey(sn)) {
                throw new ConflictException("相似问「" + s + "」已存在");
            }
        }
    }

    /** 全局 seq_id（MAX+1，见类注释）。 */
    private long nextSeqId() {
        Long max = jdbc.queryForObject(
            "SELECT COALESCE(MAX(seq_id), 0) FROM kb_chunk", Long.class);
        return (max == null ? 0L : max) + 1L;
    }

    /** 标签解析：seq_id 优先，其次 name，都没有 ⇒「未分类」。 */
    private String resolveTag(String kbId, Long tagSeqId, String tagName) {
        if (tagSeqId != null && tagSeqId > 0) {
            return resolveTagUuid(kbId, tagSeqId);
        }
        if (tagName != null && !tagName.isBlank()) {
            return tagService.findOrCreateByName(kbId, tagName.trim()).id();
        }
        return tagService.findOrCreateByName(kbId, KbFaqMetadata.UNTAGGED_TAG_NAME).id();
    }

    private String resolveTagUuid(String kbId, long tagSeqId) {
        KbChunkTag tag = findTagBySeqId(kbId, tagSeqId);
        if (tag == null) {
            throw new NotFoundException("标签 " + tagSeqId + " 不存在");
        }
        return tag.id();
    }

    private LinkedHashSet<String> resolveTagUuids(String kbId, List<Long> seqIds) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (seqIds == null) {
            return out;
        }
        for (Long seq : seqIds) {
            KbChunkTag tag = findTagBySeqId(kbId, seq);
            if (tag != null) {
                out.add(tag.id());
            }
        }
        return out;
    }

    /** 按 seq_id 找标签（注意：标签的 seq_id 是<b>库内</b>的，与 chunk 的全局 seq_id 不同）。 */
    private KbChunkTag findTagBySeqId(String kbId, long tagSeqId) {
        var r = tagService.findBySeqId(kbId, tagSeqId);
        if (r == null) {
            return null;
        }
        return new KbChunkTag(r.getId(), r.getSeqId() == null ? 0L : r.getSeqId(), r.getName());
    }

    /** 找（或建）FAQ 容器 knowledge。 */
    private KbKnowledgeRecord findFaqKnowledge(String kbId) {
        List<KbKnowledgeRecord> rows = knowledgeMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .and("type = 'faq'")
                .orderBy("create_time", true));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 确保 FAQ 容器存在。
     *
     * <p>⚠️ 容器的 {@code parse_status} 必须是 {@code completed}、
     * {@code enable_status} 必须是 {@code enabled} —— 否则检索会把它当「未完成文档」跳过，
     * 表现为「FAQ 建好了但永远搜不到」。
     */
    private KbKnowledgeRecord ensureFaqKnowledge(KbKnowledgeBaseRecord kb) {
        KbKnowledgeRecord existing = findFaqKnowledge(kb.getId());
        if (existing != null) {
            return existing;
        }
        KbKnowledgeRecord row = new KbKnowledgeRecord();
        row.setId(UUID.randomUUID().toString());
        row.setKnowledgeBaseId(kb.getId());
        row.setType("faq");
        row.setChannel("web");
        row.setTitle(kb.getName() == null || kb.getName().isBlank() ? "FAQ" : kb.getName());
        row.setDescription("FAQ 条目容器");
        row.setSource(FAQ_KNOWLEDGE_SOURCE);
        row.setParseStatus("completed");
        row.setEnableStatus("enabled");
        row.setEmbeddingModelId(kb.getEmbeddingModelId() == null ? "" : kb.getEmbeddingModelId());
        // ── 全列显式赋值（缺陷 14）
        row.setFileName("");
        row.setFileType("");
        row.setFileSize(0L);
        row.setFilePath("");
        row.setFileHash("");
        row.setStorageSize(0L);
        row.setSummaryStatus("none");
        row.setPendingSubtasksCount(0);
        row.setCustomMetadata("{}");
        row.setFolderPath("");
        row.setParseAttempt(0);
        row.setMetadata("{}");
        row.setCreator("");
        row.setUpdater("");
        row.setDeleted(0);
        row.setTenantId(0L);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        knowledgeMapper.insert(row);
        log.info("[FAQ] 已创建 FAQ 容器 knowledge={} kb={}", row.getId(), kb.getId());
        return row;
    }

    /**
     * 重建该 FAQ 容器的索引。
     *
     * <p>⚠️ 吞异常但打 ERROR：索引没更新 = 搜索是旧的，必须让人知道。
     * 不回滚业务写 —— 条目已经建好了，用户可点「重建索引」修。
     */
    private void indexSafely(KbKnowledgeRecord container, KbKnowledgeBaseRecord kb) {
        if (container == null) {
            return;
        }
        try {
            indexWriter.reindexKnowledge(container.getId(), kb.getId(),
                kb.getEmbeddingModelId() == null || kb.getEmbeddingModelId().isBlank()
                    ? null : kb.getEmbeddingModelId());
        } catch (RuntimeException e) {
            log.error("[FAQ] 索引重建失败 kb={} —— 搜索仍是旧数据（可手动重建）原因={}",
                kb.getId(), e.getMessage());
        }
    }

    private KbKnowledgeBaseRecord requireFaqBase(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
        KbKnowledgeBaseRecord kb = baseMapper.selectOneById(kbId);
        if (kb == null) {
            throw new NotFoundException("知识库 " + kbId + " 不存在");
        }
        if (!"faq".equalsIgnoreCase(kb.getType())) {
            throw new ValidationException("仅 FAQ 知识库支持该操作（当前类型 " + kb.getType() + "）");
        }
        return kb;
    }

    private KbChunkRecord requireFaqChunk(String kbId, long entrySeqId) {
        if (entrySeqId <= 0) {
            throw new ValidationException("条目 ID 必须是正整数");
        }
        List<KbChunkRecord> rows = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("seq_id = ?", entrySeqId)
                .and("chunk_type = 'faq'"));
        if (rows.isEmpty()) {
            throw new NotFoundException("FAQ 条目 " + entrySeqId + " 不存在");
        }
        KbChunkRecord c = rows.get(0);
        if (!kbId.equals(c.getKnowledgeBaseId())) {
            // ⚠️ 跨库访问按「不存在」处理，不泄露该条目属于别的库
            throw new NotFoundException("FAQ 条目 " + entrySeqId + " 不存在于知识库 " + kbId);
        }
        return c;
    }

    private static KbFaqMetadata.Entry readMeta(KbChunkRecord chunk) {
        KbFaqMetadata.Entry meta = KbFaqMetadata.fromJson(chunk.getMetadata());
        if (meta == null) {
            // 坏数据：用 content 兜底（源侧 chunkToFAQEntry 同样如此）
            meta = new KbFaqMetadata.Entry(chunk.getContent() == null ? "" : chunk.getContent(),
                List.of(), List.of(), List.of(), KbFaqMetadata.STRATEGY_ALL, 1, "faq");
        }
        return meta;
    }

    private List<Entry> toEntries(List<KbChunkRecord> chunks, KbKnowledgeBaseRecord kb) {
        List<Entry> out = new ArrayList<>(chunks.size());
        Map<String, KbChunkTag> tagCache = new HashMap<>();
        for (KbChunkRecord c : chunks) {
            out.add(toEntry(c, kb, null, tagCache));
        }
        return out;
    }

    private Entry toEntry(KbChunkRecord chunk, KbKnowledgeBaseRecord kb) {
        return toEntry(chunk, kb, null, new HashMap<>());
    }

    private Entry toEntry(KbChunkRecord chunk, KbKnowledgeBaseRecord kb, Double score) {
        return toEntry(chunk, kb, score, new HashMap<>());
    }

    private Entry toEntry(KbChunkRecord chunk, KbKnowledgeBaseRecord kb, Double score,
                          Map<String, KbChunkTag> tagCache) {
        KbFaqMetadata.Entry meta = readMeta(chunk);
        KbChunkTag tag = null;
        String tagUuid = chunk.getTagId() == null ? "" : chunk.getTagId();
        if (!tagUuid.isEmpty()) {
            tag = tagCache.computeIfAbsent(tagUuid, id -> {
                var rec = tagService.findById(id);
                return rec == null ? null
                    : new KbChunkTag(rec.getId(), rec.getSeqId() == null ? 0L : rec.getSeqId(),
                        rec.getName());
            });
        }
        return new Entry(
            chunk.getSeqId() == null ? 0L : chunk.getSeqId(),
            chunk.getId(),
            chunk.getKnowledgeId(),
            chunk.getKnowledgeBaseId(),
            tag == null ? 0L : tag.seqId(),
            tag == null ? "" : tag.name(),
            chunk.getIsEnabled() != null && chunk.getIsEnabled() != 0,
            chunk.getFlags() != null && (chunk.getFlags() & KbFaqMetadata.FLAG_RECOMMENDED) != 0,
            meta.standardQuestion(),
            meta.similarQuestions(),
            meta.negativeQuestions(),
            meta.answers(),
            meta.answerStrategy(),
            KbFaqMetadata.resolveIndexMode(kb.getFaqConfig()),
            KbTimeFormat.format(chunk.getCreateTime()),
            KbTimeFormat.format(chunk.getUpdateTime()),
            score);
    }

    /** 从 {@code ResultSet} 映射出 chunk（避免 Flex 在原生查询里的类型处理差异）。 */
    private static KbChunkRecord mapChunk(java.sql.ResultSet rs) throws java.sql.SQLException {
        KbChunkRecord c = new KbChunkRecord();
        c.setId(rs.getString("id"));
        c.setKnowledgeBaseId(rs.getString("knowledge_base_id"));
        c.setKnowledgeId(rs.getString("knowledge_id"));
        c.setContent(rs.getString("content"));
        c.setChunkIndex(rs.getInt("chunk_index"));
        c.setIsEnabled(rs.getInt("is_enabled"));
        c.setChunkType(rs.getString("chunk_type"));
        c.setMetadata(rs.getString("metadata"));
        c.setTagId(rs.getString("tag_id"));
        long seq = rs.getLong("seq_id");
        c.setSeqId(rs.wasNull() ? null : seq);
        c.setFlags(rs.getInt("flags"));
        c.setStatus(rs.getInt("status"));
        String created = rs.getString("create_time");
        String updated = rs.getString("update_time");
        c.setCreateTime(created == null ? null : KbTimeFormat.parse(created));
        c.setUpdateTime(updated == null ? null : KbTimeFormat.parse(updated));
        return c;
    }

    /** LIKE 里的通配符转义（用户搜 "50%" 时不该匹配一切）。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ── DTO ─────────────────────────────────────────────────────────

    /**
     * 创建/更新入参。
     *
     * <p>⚠️ 所有字段都可为 null：更新时 null = 不改（与「整体替换」的差异见
     * {@link #update} 的说明 —— 本仓对未传的 {@code is_enabled}/{@code is_recommended}
     * 保留原值，但文本字段会按传入值整体替换）。
     */
    public record EntryInput(
        String standardQuestion,
        List<String> similarQuestions,
        List<String> negativeQuestions,
        List<String> answers,
        String answerStrategy,
        Long tagId,
        String tagName,
        Boolean isEnabled,
        Boolean isRecommended
    ) {
    }

    /** 字段批量更新的单项。 */
    public record FieldUpdate(Boolean isEnabled, Boolean isRecommended, Long tagSeqId) {
    }

    /** 列表分页。 */
    public record Page(List<Entry> data, int page, int pageSize, int total) {
    }

    /** 导入结果。 */
    public record ImportResult(List<Long> createdSeqIds, Map<Integer, String> failed) {
    }

    /** 标签的最小视图（内部用）。 */
    private record KbChunkTag(String id, long seqId, String name) {
    }

    /** 出站 FAQ 条目（对齐源侧 {@code types.FAQEntry}）。 */
    public record Entry(
        @com.fasterxml.jackson.annotation.JsonProperty("id") long id,
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_id") String chunkId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_id") String knowledgeId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @com.fasterxml.jackson.annotation.JsonProperty("tag_id") long tagId,
        @com.fasterxml.jackson.annotation.JsonProperty("tag_name") String tagName,
        @com.fasterxml.jackson.annotation.JsonProperty("is_enabled") boolean isEnabled,
        @com.fasterxml.jackson.annotation.JsonProperty("is_recommended") boolean isRecommended,
        @com.fasterxml.jackson.annotation.JsonProperty("standard_question") String standardQuestion,
        @com.fasterxml.jackson.annotation.JsonProperty("similar_questions") List<String> similarQuestions,
        @com.fasterxml.jackson.annotation.JsonProperty("negative_questions") List<String> negativeQuestions,
        @com.fasterxml.jackson.annotation.JsonProperty("answers") List<String> answers,
        @com.fasterxml.jackson.annotation.JsonProperty("answer_strategy") String answerStrategy,
        @com.fasterxml.jackson.annotation.JsonProperty("index_mode") String indexMode,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") String createdAt,
        @com.fasterxml.jackson.annotation.JsonProperty("updated_at") String updatedAt,
        @com.fasterxml.jackson.annotation.JsonProperty("score") Double score
    ) {
    }
}
