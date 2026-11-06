package com.nexusai.kb.app.qa;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索结果合并 · 移植 WeKnora {@code chat_pipeline/merge.go} + {@code merge_expand.go}
 * + {@code merge_overlap.go}。
 *
 * <h2>为什么检索到的块不能直接丢给模型</h2>
 * 检索返回的是<b>一个个孤立的块</b>，问题有三类：
 * <ol>
 *   <li><b>碎</b>：一个答案常横跨相邻的 2-3 块，单独看每块都不完整；</li>
 *   <li><b>短</b>：短块缺上下文（「该指标为 88.9%」—— 什么指标？哪个区？）；</li>
 *   <li><b>重复</b>：重叠分块会让同一段文字出现在多个块里，浪费上下文预算。</li>
 * </ol>
 * 合并阶段按这个顺序修这三件事（合并 → 扩展 → 去重）。
 *
 * <h2>⚠️ 顺序不能换</h2>
 * <ul>
 *   <li>截断必须<b>在扩展之前</b>：否则会给几百条候选都做邻居查询；</li>
 *   <li>扩展之后必须<b>再合并一次</b>：拉进来的邻居可能又和别的块相邻；</li>
 *   <li>最终去重必须<b>在最后</b>：前面每一步都可能引入新的重复。</li>
 * </ul>
 *
 * <h2>失败语义</h2>
 * 每一步的数据库查询失败都<b>只跳过该步</b>，不整体失败 —— 合并是增强，不是正确性前提。
 */
@Component
public class KbMergeStage {

    private static final Logger log = LoggerFactory.getLogger(KbMergeStage.class);

    /** 短块扩展的目标下限（码点）。低于此值才尝试拉邻居。 */
    static final int EXPAND_MIN_LEN = 350;

    /** 短块扩展的目标上限（码点）。 */
    static final int EXPAND_MAX_LEN = 850;

    /** 近似重复判定的 token 重叠阈值。 */
    static final double PARTIAL_OVERLAP_THRESHOLD = 0.85;

    /** 近似重复判定的长度比上限（近似重复的两段应该差不多长）。 */
    static final int PARTIAL_OVERLAP_SIZE_RATIO = 3;

    /**
     * 短文本独有 token 的比例上限。
     *
     * <p>⭐⭐ 这个判据不能省：只看重叠率时，共享样板文字的两段会双双过 0.85，
     * 但各自仍带着自己的要求 / 数字 / 结论 —— 删掉任何一条都是事实丢失。
     */
    static final double PARTIAL_OVERLAP_SHORTER_ONLY = 0.02;

    /** 文本块类型（源侧同名常量）。 */
    static final String CHUNK_TYPE_TEXT = "text";

    /** 父块类型（源侧同名常量）。 */
    static final String CHUNK_TYPE_PARENT_TEXT = "parent_text";

    private final KbChunkMapper chunkMapper;

    public KbMergeStage(KbChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    /**
     * 合并后的上下文片段。
     *
     * @param chunkId         代表块 ID（合并组的第一块）
     * @param knowledgeId     文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @param content         合并后的正文
     * @param contextHeader   上下文表头
     * @param chunkType       块类型
     * @param chunkIndex      块序号（合并组的起始序号）
     * @param score           组内最高分
     * @param subChunkIds     被合并进来的其它块 ID（引用溯源用）
     */
    public record MergedChunk(
        String chunkId, String knowledgeId, String knowledgeBaseId,
        String content, String contextHeader, String chunkType,
        int chunkIndex, double score, List<String> subChunkIds
    ) {
    }

    /**
     * 执行合并。
     *
     * @param hits 检索命中（应已重排）；null/空 ⇒ 空结果
     * @param topK 截断条数（&lt;= 0 不截断）
     * @return 合并后的片段，按分数降序
     */
    public List<MergedChunk> merge(List<KbHybridSearcher.Hit> hits, int topK) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        // 检索命中只有 id/正文/分数；序号、位置、父块这些在块表里 ⇒ 先批量补齐
        Map<String, KbChunkRecord> records = loadRecords(hits);
        List<Work> work = new ArrayList<>(hits.size());
        for (KbHybridSearcher.Hit h : hits) {
            if (h == null || h.chunkId() == null) {
                continue;
            }
            KbChunkRecord r = records.get(h.chunkId());
            if (r == null) {
                // 索引里有、块表已删（重解析竞态）⇒ 跳过，不拿脏数据凑数
                if (log.isDebugEnabled()) {
                    log.debug("[合并] 命中块已不存在，跳过 chunk={}", h.chunkId());
                }
                continue;
            }
            work.add(new Work(h, r));
        }
        if (work.isEmpty()) {
            return List.of();
        }

        // 1. 选择输入 + 截断（⚠️ 必须在扩展之前）
        work = selectInput(work, topK);
        // 2. 初始去重（id + 内容签名）
        work = dedup("初始去重", work);
        // 3. 父块解析
        work = resolveParents(work, records);
        // 4. 同文档同类型分组，顺序块合并
        work = groupAndMergeSequential(work);
        // 4.5 FAQ 答案回填（对齐源侧 merge_faq.go：用 metadata 里的标准问 + 答案替换正文）
        work = populateFaqAnswers(work);
        // 5. 短上下文扩展
        work = expandShortContext(work, records);
        // 6. 扩展可能又引入相邻块 ⇒ 再合并一次
        work = groupAndMergeSequential(work);
        // 7. 最终去重（签名 + 近似重复）
        work = dedup("最终去重", work);
        work = removePartialOverlaps(work);

        work.sort(Comparator
            .comparingDouble((Work w) -> w.score).reversed()
            .thenComparing(w -> w.chunkId));
        return toMergedChunks(work);
    }

    // ── 0. 批量补块记录 ──────────────────────────────────────────────

    private Map<String, KbChunkRecord> loadRecords(List<KbHybridSearcher.Hit> hits) {
        Set<String> ids = new LinkedHashSet<>();
        for (KbHybridSearcher.Hit h : hits) {
            if (h != null && h.chunkId() != null) {
                ids.add(h.chunkId());
            }
        }
        if (ids.isEmpty() || chunkMapper == null) {
            return Map.of();
        }
        try {
            Map<String, KbChunkRecord> out = new HashMap<>(ids.size());
            for (KbChunkRecord r : queryByIds(ids)) {
                out.put(r.getId(), r);
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("[合并] 批量读取块记录失败（将只保留检索层给到的字段）数量={} 原因={}",
                ids.size(), e.getMessage());
            return Map.of();
        }
    }

    // ── 1. 选择输入 ─────────────────────────────────────────────────

    private List<Work> selectInput(List<Work> work, int topK) {
        List<Work> out = new ArrayList<>(work);
        out.sort(Comparator
            .comparingDouble((Work w) -> w.score).reversed()
            .thenComparing(w -> w.chunkId));
        if (topK > 0 && out.size() > topK) {
            if (log.isInfoEnabled()) {
                log.info("[合并] 输入截断 {} → {}（⚠️ 截断在扩展之前，避免为未入选块做邻居查询）",
                    out.size(), topK);
            }
            return new ArrayList<>(out.subList(0, topK));
        }
        return out;
    }

    // ── 2/7. 去重 ───────────────────────────────────────────────────

    private List<Work> dedup(String label, List<Work> work) {
        Set<String> seenIds = new HashSet<>();
        Map<String, Work> bySignature = new HashMap<>();
        List<Work> out = new ArrayList<>(work.size());
        for (Work w : work) {
            if (!seenIds.add(w.chunkId)) {
                continue;
            }
            String sig = KbMergeText.contentSignature(w.content);
            if (sig != null) {
                Work first = bySignature.get(sig);
                if (first != null) {
                    // 内容完全一样 ⇒ 保留先到的，把 id 记进子块（引用不丢）
                    first.addSubChunk(w.chunkId);
                    continue;
                }
                bySignature.put(sig, w);
            }
            out.add(w);
        }
        if (out.size() < work.size() && log.isInfoEnabled()) {
            log.info("[合并] {} {} → {}", label, work.size(), out.size());
        }
        return out;
    }

    /**
     * 近似重复剔除（源侧 {@code removePartialOverlaps}）。
     *
     * <p>两条判据（满足任一即视为同一事实）：
     * <ol>
     *   <li>规范化后短的是长的<b>子串</b>；</li>
     *   <li>token 重叠 ≥ 0.85、长度比 ≤ 3、且短文本独有 token ≤ 2%。</li>
     * </ol>
     * 同一对里分数低的被删；分数相同时删短的、留长的。
     */
    private List<Work> removePartialOverlaps(List<Work> work) {
        int n = work.size();
        if (n <= 1) {
            return work;
        }
        String[] norm = new String[n];
        for (int i = 0; i < n; i++) {
            norm[i] = KbMergeText.normalizeContent(work.get(i).content);
        }
        Set<String>[] tokens = newTokenArray(n);
        boolean[] removed = new boolean[n];

        for (int i = 0; i < n; i++) {
            if (removed[i]) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                if (removed[j]) {
                    continue;
                }
                int shortIdx = i;
                int longIdx = j;
                if (KbMergeText.runeLen(norm[i]) > KbMergeText.runeLen(norm[j])) {
                    shortIdx = j;
                    longIdx = i;
                }
                boolean contained = KbMergeText.isContentContained(norm[shortIdx], norm[longIdx]);
                if (!contained) {
                    if (tokens[shortIdx] == null) {
                        tokens[shortIdx] = KbMergeText.tokenizeSimple(work.get(shortIdx).content);
                    }
                    if (tokens[longIdx] == null) {
                        tokens[longIdx] = KbMergeText.tokenizeSimple(work.get(longIdx).content);
                    }
                    int small = Math.min(tokens[shortIdx].size(), tokens[longIdx].size());
                    int large = Math.max(tokens[shortIdx].size(), tokens[longIdx].size());
                    if (small * PARTIAL_OVERLAP_SIZE_RATIO < large
                        || KbMergeText.tokenOverlapRatio(tokens[shortIdx], tokens[longIdx])
                            < PARTIAL_OVERLAP_THRESHOLD) {
                        continue;
                    }
                    // ⭐ 重叠率高还不够：长的必须真的扛得住短的独占信息
                    if (KbMergeText.shorterOnlyFraction(tokens[shortIdx], tokens[longIdx])
                        > PARTIAL_OVERLAP_SHORTER_ONLY) {
                        continue;
                    }
                }
                int victim = shortIdx;
                if (work.get(shortIdx).score > work.get(longIdx).score) {
                    victim = longIdx;
                }
                removed[victim] = true;
                int keptIdx = victim == i ? j : i;
                work.get(keptIdx).addSubChunk(work.get(victim).chunkId);
                if (log.isDebugEnabled()) {
                    log.debug("[合并] 近似重复剔除 留在={} 删掉={} 子串判定={}",
                        work.get(keptIdx).chunkId, work.get(victim).chunkId, contained);
                }
            }
        }
        List<Work> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!removed[i]) {
                out.add(work.get(i));
            }
        }
        if (out.size() < n && log.isInfoEnabled()) {
            log.info("[合并] 近似重复剔除 {} → {}", n, out.size());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String>[] newTokenArray(int n) {
        return new Set[n];
    }

    // ── 4.5 FAQ 答案回填 ────────────────────────────────────────────

    /** FAQ 块类型（与 {@code KbFaqService} 写入时一致）。 */
    static final String CHUNK_TYPE_FAQ = "faq";

    /**
     * FAQ 条目回填「回答用正文」（对齐源侧 {@code merge_faq.go:populateFAQAnswers}）。
     *
     * <p><b>为什么必须做</b>：FAQ 块的 {@code content} 是<b>索引内容</b>
     * （标准问 + 相似问法，刻意包含相似问法以便被不同问法检索到）。
     * 直接把它当资料喂给模型，模型看到的是：
     * <pre>
     * Q: 报销流程是什么？
     * Similar Questions: - 怎么报销 / - 报销怎么走
     * Answers: - ...
     * </pre>
     * 相似问法属于检索侧的东西，进了上下文只会让模型把它们当答案的一部分复述出来。
     * ⇒ 命中后换成 {@link com.nexusai.kb.app.faq.KbFaqMetadata#answerContent}（只有问 + 答）。
     *
     * <p><b>失败语义</b>：metadata 解析失败 / 没有答案 ⇒ 保留原正文（不删这条命中）。
     */
    private List<Work> populateFaqAnswers(List<Work> work) {
        int updated = 0;
        for (Work w : work) {
            if (!CHUNK_TYPE_FAQ.equals(w.chunkType)) {
                continue;
            }
            KbChunkRecord record = w.record;
            if (record == null || record.getMetadata() == null || record.getMetadata().isBlank()) {
                continue;
            }
            try {
                com.nexusai.kb.app.faq.KbFaqMetadata.Entry entry =
                    com.nexusai.kb.app.faq.KbFaqMetadata.fromJson(record.getMetadata());
                String content = com.nexusai.kb.app.faq.KbFaqMetadata.answerContent(entry);
                if (content == null || content.isBlank()) {
                    continue;
                }
                w.content = content;
                w.contentRewritten = true;
                updated++;
            } catch (RuntimeException e) {
                // 单条 metadata 坏了不该影响其它 FAQ 条目
                log.warn("[合并] FAQ 元数据解析失败，保留原正文 chunk={} 原因={}",
                    w.chunkId, e.getMessage());
            }
        }
        if (updated > 0) {
            log.info("[合并] FAQ 答案回填 {} 条", updated);
        }
        return work;
    }

    // ── 3. 父块解析 ─────────────────────────────────────────────────

    private List<Work> resolveParents(List<Work> work, Map<String, KbChunkRecord> cache) {
        Set<String> parentIds = new LinkedHashSet<>();
        for (Work w : work) {
            if (w.parentChunkId != null && !w.parentChunkId.isBlank()) {
                parentIds.add(w.parentChunkId);
            }
        }
        if (parentIds.isEmpty()) {
            return work;
        }
        for (String id : parentIds) {
            if (cache.containsKey(id)) {
                continue;
            }
            try {
                cache.put(id, chunkMapper == null ? null : chunkMapper.selectOneById(id));
            } catch (RuntimeException e) {
                // 父块是增强上下文，取不到就原子块继续，⛔ 不整体失败
                log.warn("[合并] 父块查询失败 id={} 原因={}", id, e.getMessage());
                cache.put(id, null);
            }
        }
        for (Work w : work) {
            if (w.parentChunkId == null || w.parentChunkId.isBlank()) {
                continue;
            }
            KbChunkRecord parent = cache.get(w.parentChunkId);
            if (parent == null || parent.getContent() == null || parent.getContent().isEmpty()
                || !CHUNK_TYPE_PARENT_TEXT.equals(parent.getChunkType())) {
                continue;
            }
            int childLen = KbMergeText.runeLen(w.content);
            w.content = KbMergeText.joinChunkContent(parent.getContent(), w.content, "\n\n");
            w.addSubChunk(w.chunkId);
            // 拼了父块后坐标不再可信 ⇒ 后续必须走文本匹配
            w.contentRewritten = true;
            if (log.isDebugEnabled()) {
                log.debug("[合并] 父块展开 child={} parent={} 前长={} 后长={}",
                    w.chunkId, parent.getId(), childLen, KbMergeText.runeLen(w.content));
            }
        }
        return work;
    }

    // ── 4/6. 分组顺序合并 ────────────────────────────────────────────

    /**
     * 按 {@code (knowledgeId, chunkType)} 分组，组内按 chunkIndex 升序做顺序块合并。
     *
     * <p>合并不是「只要挨着就拼」—— 要分清「同一段被切开」（该拼）、
     * 「后者完全包含在前者里」（只记录）、还是「位置相邻但内容无关」（不拼）。
     */
    private List<Work> groupAndMergeSequential(List<Work> work) {
        if (work.isEmpty()) {
            return work;
        }
        Map<String, List<Work>> groups = new LinkedHashMap<>();
        for (Work w : work) {
            String key = (w.knowledgeId == null ? "" : w.knowledgeId) + "\u0000"
                + (w.chunkType == null ? "" : w.chunkType);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
        }
        List<Work> out = new ArrayList<>(work.size());
        for (List<Work> group : groups.values()) {
            group.sort(Comparator
                .comparingInt((Work w) -> w.chunkIndex)
                .thenComparing(w -> w.chunkId));
            out.addAll(mergeGroup(group));
        }
        out.sort(Comparator
            .comparingDouble((Work w) -> w.score).reversed()
            .thenComparing(w -> w.chunkId));
        return out;
    }

    private List<Work> mergeGroup(List<Work> chunks) {
        List<Work> groups = new ArrayList<>();
        Work last = null;
        int lastIndex = -1;
        for (Work current : chunks) {
            if (last == null) {
                last = current;
                lastIndex = current.chunkIndex;
                groups.add(last);
                continue;
            }
            switch (classify(last, lastIndex, current)) {
                case SEPARATE -> {
                    groups.add(current);
                    last = current;
                    lastIndex = current.chunkIndex;
                }
                case EXTEND -> {
                    int positionOverlap = last.endAt - current.startAt;
                    String joined = last.contentRewritten
                        ? null
                        : KbMergeText.appendWithExactOverlap(last.content, current.content, positionOverlap);
                    if (joined == null) {
                        joined = KbMergeText.appendWithOverlap(
                            last.content, current.content, positionOverlap);
                    }
                    last.content = joined;
                    last.endAt = current.endAt;
                    // 拼过之后长度不再等于坐标差 ⇒ 后续必须降级到文本匹配
                    last.contentRewritten = true;
                    last.addSubChunk(current.chunkId);
                }
                case SUBSUME -> last.addSubChunk(current.chunkId);
                case JOIN_DISTINCT, JOIN_TEXT -> {
                    last.content = KbMergeText.joinChunkContent(
                        last.content, current.content, "\n\n");
                    last.contentRewritten = true;
                    last.addSubChunk(current.chunkId);
                }
            }
            if (current.chunkIndex > lastIndex) {
                lastIndex = current.chunkIndex;
            }
            if (current.score > last.score) {
                last.score = current.score;
            }
        }
        return groups;
    }

    /** 块关系判定结果。 */
    private enum MergeSituation {
        /** 不可合并，另起一组。 */
        SEPARATE,
        /** 坐标可信、范围有部分重叠/相邻 ⇒ 追加非重叠后缀。 */
        EXTEND,
        /** 坐标可信、被前者完全包含且文本已覆盖 ⇒ 只记录来源。 */
        SUBSUME,
        /** 坐标可信、范围被包含但文本不同 ⇒ 文本拼接。 */
        JOIN_DISTINCT,
        /** 坐标不可信，但文本包含或序号连续 ⇒ 纯文本拼接。 */
        JOIN_TEXT
    }

    private MergeSituation classify(Work last, int lastIndex, Work current) {
        if (trusted(last) && trusted(current) && current.startAt >= last.startAt) {
            if (current.startAt > last.endAt) {
                return MergeSituation.SEPARATE;
            }
            if (current.endAt > last.endAt) {
                return MergeSituation.EXTEND;
            }
            if (KbMergeText.containsChunkContent(last.content, current.content)) {
                return MergeSituation.SUBSUME;
            }
            return MergeSituation.JOIN_DISTINCT;
        }
        boolean textContained = KbMergeText.containsChunkContent(last.content, current.content)
            || KbMergeText.containsChunkContent(current.content, last.content);
        boolean sequential = current.chunkIndex == lastIndex + 1;
        if (!textContained && !sequential) {
            return MergeSituation.SEPARATE;
        }
        return MergeSituation.JOIN_TEXT;
    }

    /**
     * 坐标是否可用于按位置合并。
     *
     * <p>四个条件缺一不可：没被编辑过、没被流水线改写、范围有效、
     * 且<b>正文码点数恰好等于 {@code endAt - startAt}</b>。
     * 最后一条最容易被忽略 —— 补写表头 / HTML 实体都会让它不成立。
     */
    private static boolean trusted(Work w) {
        return w.contentRevision == 0
            && !w.contentRewritten
            && w.endAt > w.startAt
            && KbMergeText.runeLen(w.content) == w.endAt - w.startAt;
    }

    // ── 5. 短上下文扩展 ──────────────────────────────────────────────

    /**
     * 短块沿 {@code preChunkId}/{@code nextChunkId} 双向扩展。
     *
     * <p>只对 {@code text} 类型、长度 &lt; 350 码点的块做，且<b>邻居必须属于同一文档</b>
     * —— 跨文档拼接会把两个不同来源的内容缝成一句话。
     */
    private List<Work> expandShortContext(List<Work> work, Map<String, KbChunkRecord> cache) {
        List<Work> targets = new ArrayList<>();
        for (Work w : work) {
            if (w.content == null || w.content.isEmpty()) {
                continue;
            }
            if (!CHUNK_TYPE_TEXT.equals(w.chunkType)) {
                continue;
            }
            if (KbMergeText.runeLen(w.content) >= EXPAND_MIN_LEN) {
                continue;
            }
            targets.add(w);
        }
        if (targets.isEmpty()) {
            return work;
        }

        for (Work target : targets) {
            KbChunkRecord base = cache.get(target.chunkId);
            if (base == null || base.getContent() == null || base.getContent().isEmpty()
                || !CHUNK_TYPE_TEXT.equals(base.getChunkType())) {
                continue;
            }
            String prev = "";
            String next = "";
            List<String> prevIds = new ArrayList<>();
            List<String> nextIds = new ArrayList<>();
            String prevCursor = base.getPreChunkId();
            String nextCursor = base.getNextChunkId();

            KbChunkRecord prevChunk = fetchIfMissing(cache, prevCursor);
            if (prevChunk != null && sameKnowledge(prevChunk, base)) {
                prev = nz(prevChunk.getContent());
                prevIds.add(prevChunk.getId());
                prevCursor = prevChunk.getPreChunkId();
            } else {
                prevCursor = null;
            }
            KbChunkRecord nextChunk = fetchIfMissing(cache, nextCursor);
            if (nextChunk != null && sameKnowledge(nextChunk, base)) {
                next = nz(nextChunk.getContent());
                nextIds.add(nextChunk.getId());
                nextCursor = nextChunk.getNextChunkId();
            } else {
                nextCursor = null;
            }

            /*
             * ⚠️⚠️ base 必须用 target.content（**已经合并过的当前正文**），
             * 不能用 base.getContent()（块表里那块单独的原文）。
             *
             * 走到这里时 target 可能已经是「c1 + c2 顺序合并」的结果；
             * 若再用数据库原文，扩展会把刚合并进来的内容整个丢掉 ——
             * 表现为「明明检索到两段，答案里只剩第一段」，而且不报错。
             */
            String baseContent = target.content;
            String merged = mergeOrderedContent(prev, baseContent, next, EXPAND_MAX_LEN);
            int guard = 0;
            while (merged != null && !merged.isEmpty()
                && KbMergeText.runeLen(merged) < EXPAND_MIN_LEN
                && (prevCursor != null || nextCursor != null)
                && guard++ < 20) {
                boolean expanded = false;
                if (prevCursor != null) {
                    KbChunkRecord p = fetchIfMissing(cache, prevCursor);
                    if (p != null && sameKnowledge(p, base)) {
                        prev = KbMergeText.joinChunkContent(nz(p.getContent()), prev, "\n\n");
                        prevIds.add(0, p.getId());
                        prevCursor = p.getPreChunkId();
                        expanded = true;
                    } else {
                        prevCursor = null;
                    }
                }
                merged = mergeOrderedContent(prev, baseContent, next, EXPAND_MAX_LEN);
                if (KbMergeText.runeLen(merged) >= EXPAND_MIN_LEN) {
                    break;
                }
                if (nextCursor != null) {
                    KbChunkRecord n = fetchIfMissing(cache, nextCursor);
                    if (n != null && sameKnowledge(n, base)) {
                        next = KbMergeText.joinChunkContent(next, nz(n.getContent()), "\n\n");
                        nextIds.add(n.getId());
                        nextCursor = n.getNextChunkId();
                        expanded = true;
                    } else {
                        nextCursor = null;
                    }
                }
                if (!expanded) {
                    break;
                }
            }
            if (merged == null || merged.isEmpty()) {
                continue;
            }
            int beforeLen = KbMergeText.runeLen(target.content);
            target.content = merged;
            target.contentRewritten = true;
            for (String id : prevIds) {
                target.addSubChunk(id);
            }
            for (String id : nextIds) {
                target.addSubChunk(id);
            }
            if (log.isDebugEnabled()) {
                log.debug("[合并] 短块扩展 chunk={} 长度={}→{} 前邻={} 后邻={}",
                    target.chunkId, beforeLen, KbMergeText.runeLen(merged), prevIds, nextIds);
            }
        }
        return work;
    }

    /**
     * 按「文档顺序」拼 prev + base + next，总量控制在 {@code maxLen} 附近。
     *
     * <p>⭐⭐ <b>base（检索命中的那块）永不被裁</b>：剩余预算
     * {@code max(maxLen - runeLen(base), 0)} 先一半给 prev 的尾部、剩下给 next 的头部，
     * 再按「谁没用完」互补。
     *
     * <p>⛔ 不能「先拼成一整段再截断」—— 那样一个很长的前邻会把命中块本身挤出上下文。
     */
    static String mergeOrderedContent(String prev, String base, String next, int maxLen) {
        if (base == null) {
            return "";
        }
        int[] prevCp = nz(prev).codePoints().toArray();
        int[] nextCp = nz(next).codePoints().toArray();
        int budget = Math.max(maxLen - KbMergeText.runeLen(base), 0);
        int prevKeep = Math.min(prevCp.length, budget / 2);
        int nextKeep = Math.min(nextCp.length, budget - prevKeep);
        prevKeep = Math.min(prevCp.length, budget - nextKeep);

        String content = base;
        if (prevKeep > 0) {
            content = KbMergeText.joinChunkContent(
                new String(prevCp, prevCp.length - prevKeep, prevKeep), content, "\n\n");
        }
        if (nextKeep > 0) {
            content = KbMergeText.joinChunkContent(
                content, new String(nextCp, 0, nextKeep), "\n\n");
        }
        return content;
    }

    // ── 数据库访问 ──────────────────────────────────────────────────

    private List<KbChunkRecord> queryByIds(Set<String> ids) {
        List<String> list = new ArrayList<>(ids);
        String placeholders = String.join(",", list.stream().map(x -> "?").toList());
        return chunkMapper.selectListByQuery(
            QueryWrapper.create().where("id IN (" + placeholders + ")", list.toArray()));
    }

    private KbChunkRecord fetchIfMissing(Map<String, KbChunkRecord> cache, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        KbChunkRecord row;
        try {
            row = chunkMapper == null ? null : chunkMapper.selectOneById(id);
        } catch (RuntimeException e) {
            log.warn("[合并] 邻居块查询失败 id={} 原因={}", id, e.getMessage());
            row = null;
        }
        cache.put(id, row);
        return row;
    }

    private static boolean sameKnowledge(KbChunkRecord a, KbChunkRecord b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getKnowledgeId() != null && a.getKnowledgeId().equals(b.getKnowledgeId());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private List<MergedChunk> toMergedChunks(List<Work> work) {
        List<MergedChunk> out = new ArrayList<>(work.size());
        for (Work w : work) {
            out.add(new MergedChunk(w.chunkId, w.knowledgeId, w.knowledgeBaseId,
                w.content, w.contextHeader, w.chunkType, w.chunkIndex, w.score,
                List.copyOf(w.subChunkIds)));
        }
        return out;
    }

    // ── 内部可变工作对象 ─────────────────────────────────────────────

    /**
     * 合并过程中需要改写的命中。
     *
     * <p>{@link KbHybridSearcher.Hit} 是 record（不可变），而合并要反复改 content/score，
     * 且块表里的位置/父块字段检索层没带出来 ⇒ 内部转成可变工作对象。
     * ⚠️ 不暴露到包外，避免调用方误以为它是契约。
     */
    private static final class Work {
        final String chunkId;
        final String knowledgeId;
        String knowledgeBaseId;
        String content;
        final String contextHeader;
        final String chunkType;
        final int chunkIndex;
        double score;
        final List<String> subChunkIds;

        int startAt;
        int endAt;
        int contentRevision;
        boolean contentRewritten;
        String parentChunkId;
        /** 原始块记录（FAQ 需要从 metadata 回填答案，⛔ 不要重复查库）。 */
        final KbChunkRecord record;

        Work(KbHybridSearcher.Hit hit, KbChunkRecord record) {
            this.record = record;
            this.chunkId = hit.chunkId();
            this.knowledgeId = record.getKnowledgeId() != null
                ? record.getKnowledgeId() : hit.knowledgeId();
            this.knowledgeBaseId = record.getKnowledgeBaseId();
            // ⚠️ 用块表正文而不是 hit.content()：合并要基于完整原文，检索层可能已裁剪
            this.content = nz(record.getContent() != null ? record.getContent() : hit.content());
            this.contextHeader = record.getContextHeader() != null
                ? record.getContextHeader() : hit.contextHeader();
            this.chunkType = record.getChunkType() == null ? CHUNK_TYPE_TEXT : record.getChunkType();
            this.chunkIndex = record.getChunkIndex() == null ? 0 : record.getChunkIndex();
            this.score = hit.score();
            this.subChunkIds = new ArrayList<>();
            this.startAt = record.getStartAt() == null ? 0 : record.getStartAt();
            this.endAt = record.getEndAt() == null ? 0 : record.getEndAt();
            this.contentRevision = record.getContentRevision() == null ? 0 : record.getContentRevision();
            this.parentChunkId = record.getParentChunkId();
        }

        void addSubChunk(String id) {
            if (id != null && !id.isBlank() && !subChunkIds.contains(id)) {
                subChunkIds.add(id);
            }
        }
    }
}
