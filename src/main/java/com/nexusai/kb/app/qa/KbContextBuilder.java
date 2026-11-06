package com.nexusai.kb.app.qa;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拼给模型的检索上下文 · 对应源侧 {@code chat_pipeline/references.go} 的
 * {@code prepareMessagesWithModelContext}（只保留「编号 + 正文」这一段）。
 *
 * <h2>⭐⭐ 编号是引用链的锚点，不是排版</h2>
 * 输出里的 {@code [1]}、{@code [2]} 就是模型答复里写引用的依据，
 * 也是 {@link KbCitationWriter} 落库时 {@code refIndex} 的来源。
 * ⇒ 编号<b>必须连续、从 1 开始、与返回的 {@code citedChunkIds} 严格对齐</b>。
 * 中间漏号 / 重排 / 过滤任何一条，用户点引用就会跳到别的段落 —— 而且不会报错。
 *
 * <h2>为什么正文要截断</h2>
 * 单段太长会把整个上下文预算吃掉（尤其是扩展过的块可能到 850 码点以上）。
 * 截到 2000 码点并显式写出「后略」，比静默截断好 —— 模型能知道这段不完整。
 */
@Component
public class KbContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(KbContextBuilder.class);

    /** 单段正文上限（码点）。 */
    static final int SEGMENT_MAX_CHARS = 2000;

    /** 文档标题拿不到时的占位（⛔ 不要留空括号）。 */
    static final String UNTITLED = "未命名文档";

    private final KbKnowledgeMapper knowledgeMapper;

    /**
     * wiki 页 mapper（阶段 5b 配套）：wiki 块的 knowledgeId 是**页 id**，
     * 不在 kb_knowledge 里 ⇒ 标题解析要回退查这张表。
     *
     * <p>⚠️ 用字段注入而非构造参数：构造器已被多处测试直接调用，
     * 加参数会波及所有调用点，而它只影响「标题可读性」这一件事。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.kb.repo.mapper.KbWikiPageMapper wikiPageMapper;

    public KbContextBuilder(KbKnowledgeMapper knowledgeMapper) {
        this.knowledgeMapper = knowledgeMapper;
    }

    /**
     * 一条可落库的引用（编号已定）。
     *
     * @param refIndex       引用编号（1 起，与上下文里的 {@code [n]} 一一对应）
     * @param chunkId        块 ID
     * @param knowledgeId    文档 ID
     * @param knowledgeBaseId 知识库 ID
     * @param knowledgeTitle 文档标题（渲染时已解析，可能为 {@link #UNTITLED}）
     * @param content        块正文
     * @param chunkIndex     块序号
     */
    public record CitationRef(
        int refIndex, String chunkId, String knowledgeId, String knowledgeBaseId,
        String knowledgeTitle, String content, Integer chunkIndex
    ) {
    }

    /**
     * 拼装结果。
     *
     * @param text            给模型的上下文文本（空串 = 没有可用资料）
     * @param citedChunkIds   按编号顺序的块 ID（下标 i 对应编号 i+1）
     * @param candidateCount  参与拼装的候选数（= citedChunkIds.size()）
     * @param citations       引用落库载荷（编号已定，顺序与 {@code citedChunkIds} 一致）
     */
    public record Rendered(
        String text, List<String> citedChunkIds, int candidateCount,
        List<CitationRef> citations
    ) {
        /** 空结果（无可用资料）。 */
        public static Rendered empty() {
            return new Rendered("", List.of(), 0, List.of());
        }
    }

    /**
     * 拼装上下文。
     *
     * @param chunks 合并后的片段；null/空 ⇒ 空结果
     * @return 渲染结果（永不 null）
     */
    public Rendered build(List<KbMergeStage.MergedChunk> chunks) {
        return build(chunks, null);
    }

    /**
     * 一段「外部资料」（当前只有联网检索结果）。
     *
     * <p><b>为什么单独一个类型</b>：网页没有 chunk 行 —— 它的「来源」是 URL，
     * 落 <b>来源</b>通道（{@code session_artifact.kind='source'}），不落 chunk 引用
     * （{@code kb_citation.chunk_id} 指向不存在的块会让前端点开是空的）。
     *
     * @param title 网页标题（可空）
     * @param url   链接
     * @param text  正文（已截断）
     */
    public record ExternalSegment(String title, String url, String text) {
    }

    /**
     * 拼装上下文（含外部资料段）。
     *
     * <p>外部段编号<b>接在</b>知识库段之后 → 模型看到的 {@code [n]} 是连续的一整套；
     * 但它们<b>不进</b> {@link Rendered#citations()}（没有 chunk 可落），
     * 由调用方单独记到「来源」通道。
     *
     * @param chunks   合并后的知识库片段（可空）
     * @param externals 外部资料段（可空）
     * @return 渲染结果（永不 null）
     */
    /**
     * FAQ 块类型（与 {@code KbFaqService} / {@code KbMergeStage} 写入时一致）。
     */
    static final String CHUNK_TYPE_FAQ = "faq";

    /**
     * 拼装上下文的可选参数（阶段 10）。
     *
     * @param faqPriority      是否把 FAQ 段单独前置（源侧 {@code FAQPriorityEnabled}）
     * @param faqExactThreshold 标准答案的「精确匹配」阈值（{@code score >= 阈值} 才标）；
     *                          &lt;= 0 ⇒ 不标
     */
    public record Options(boolean faqPriority, double faqExactThreshold) {

        /** 默认：不分离、不标（= 旧行为）。 */
        public static final Options NONE = new Options(false, 0.0);
    }

    /**
     * 拼装上下文（含阶段 10 的 FAQ 优先分离）。
     *
     * @param chunks   合并后的知识库片段（可空）
     * @param externals 外部资料段（可空）
     * @param options  可选行为（null ⇒ {@link Options#NONE}）
     * @return 渲染结果（永不 null）
     */
    public Rendered build(List<KbMergeStage.MergedChunk> chunks,
                          List<ExternalSegment> externals, Options options) {
        Options opt = options == null ? Options.NONE : options;
        List<KbMergeStage.MergedChunk> safeChunks = chunks == null ? List.of() : chunks;
        List<ExternalSegment> safeExt = externals == null ? List.of() : externals;
        if (safeChunks.isEmpty() && safeExt.isEmpty()) {
            return Rendered.empty();
        }
        // ⭐ 阶段 10：FAQ 段前置。为什么要分离 —— FAQ 是**人工确认过**的标准答案，
        //   和原始文档平铺时模型会把它当成又一段「参考资料」，从而自己再组织一遍语言；
        //   前置 + 明确标记，模型才会优先采用。
        List<KbMergeStage.MergedChunk> ordered = new ArrayList<>(safeChunks.size());
        List<Boolean> faqFlags = new ArrayList<>(safeChunks.size());
        int faqCount = 0;
        if (opt.faqPriority()) {
            for (KbMergeStage.MergedChunk c : safeChunks) {
                if (c != null && CHUNK_TYPE_FAQ.equalsIgnoreCase(c.chunkType())) {
                    ordered.add(c);
                    faqFlags.add(Boolean.TRUE);
                    faqCount++;
                }
            }
            for (KbMergeStage.MergedChunk c : safeChunks) {
                if (c != null && !CHUNK_TYPE_FAQ.equalsIgnoreCase(c.chunkType())) {
                    ordered.add(c);
                    faqFlags.add(Boolean.FALSE);
                }
            }
            // null 片段不丢：原顺序里它们是无效项，这里直接跳过（下面循环也不处理 null）
            if (ordered.size() != safeChunks.size()) {
                safeChunks = safeChunks.stream().filter(java.util.Objects::nonNull).toList();
            }
        } else {
            for (KbMergeStage.MergedChunk c : safeChunks) {
                if (c != null) {
                    ordered.add(c);
                    faqFlags.add(Boolean.FALSE);
                }
            }
        }
        safeChunks = ordered;

        Map<String, String> titles = loadTitles(safeChunks);

        int total = safeChunks.size() + safeExt.size();
        StringBuilder sb = new StringBuilder(4096);
        List<String> cited = new ArrayList<>(total);
        List<CitationRef> citations = new ArrayList<>(safeChunks.size());
        sb.append("## 检索到的资料（共 ").append(total).append(" 段）\n\n");
        if (faqCount > 0) {
            sb.append("其中 ").append(faqCount).append(" 段是知识库的标准问答，优先采用。\n\n");
        }
        // ⭐ 同文档标题只渲染一次：重复标题既费 token，也会让模型以为换了文档
        Set<String> titledDocs = new HashSet<>();
        for (int i = 0; i < safeChunks.size(); i++) {
            KbMergeStage.MergedChunk c = safeChunks.get(i);
            int no = i + 1;
            String title = titles.getOrDefault(c.knowledgeId(), UNTITLED);
            boolean repeatTitle = !titledDocs.add(c.knowledgeId());
            if (repeatTitle) {
                // 同文档的后续段：只标「同上」
                sb.append('[').append(no).append("] 《").append(title).append("》（续）");
            } else {
                sb.append('[').append(no).append("] 《").append(title).append('》');
            }
            if (c.chunkIndex() > 0) {
                sb.append("第 ").append(c.chunkIndex()).append(" 段");
            }
            if (Boolean.TRUE.equals(faqFlags.get(i))) {
                sb.append("（标准问答，优先采用）");
                if (opt.faqExactThreshold() > 0 && c.score() >= opt.faqExactThreshold()) {
                    // ⚠️ 偏离：源侧用 boost 前分数；本仓 MergedChunk.score 是合并后最高分。
                    //    按合并后分数判会偏乐观，但比不标更有用，且已登记。
                    sb.append("（精确匹配）");
                }
            }
            sb.append('\n');
            sb.append(truncate(c.content()));
            sb.append("\n\n");
            cited.add(c.chunkId());
            // ⭐ 编号在这里定稿，引用落库直接用 —— 两侧不再各自编号，避免错位
            citations.add(new CitationRef(no, c.chunkId(), c.knowledgeId(), c.knowledgeBaseId(),
                title, c.content(), c.chunkIndex()));
        }
        // 外部段（联网结果）编号接在后面，但⛔ 不进 citations（没有 chunk 可落）
        int base = safeChunks.size();
        for (int i = 0; i < safeExt.size(); i++) {
            ExternalSegment e = safeExt.get(i);
            int no = base + i + 1;
            String title = e.title() == null || e.title().isBlank() ? e.url() : e.title();
            sb.append('[').append(no).append("] 《").append(title).append("》\n");
            sb.append(truncate(e.text()));
            sb.append("\n\n");
            cited.add(null);  // 占位：保持「下标 i ↔ 编号 i+1」不变式
        }
        if (log.isInfoEnabled()) {
            log.info("[上下文] 拼装完成 库内段={} 外部段={} 总字符={}",
                safeChunks.size(), safeExt.size(), sb.length());
        }
        return new Rendered(sb.toString().stripTrailing() + "\n", cited, total, citations);
    }


    /**
     * 拼装上下文（旧签名，零改动兼容既有调用点）。
     *
     * @param chunks   合并后的知识库片段（可空）
     * @param externals 外部资料段（可空）
     * @return 渲染结果（永不 null）
     */
    public Rendered build(List<KbMergeStage.MergedChunk> chunks, List<ExternalSegment> externals) {
        // ⚠️ 只有外部段（知识库 0 命中但联网有结果）也走统一渲染 ——
        //    ⛔ 不能在这里返回 empty()，那会让系统提示词误判成「没有资料」，
        //    模型就会说「知识库里没找到」，而联网那边其实有内容。
        return build(chunks, externals, Options.NONE);
    }


    private Map<String, String> loadTitles(List<KbMergeStage.MergedChunk> chunks) {
        Set<String> ids = new LinkedHashSet<>();
        for (KbMergeStage.MergedChunk c : chunks) {
            if (c.knowledgeId() != null && !c.knowledgeId().isBlank()) {
                ids.add(c.knowledgeId());
            }
        }
        if (ids.isEmpty() || knowledgeMapper == null) {
            return Map.of();
        }
        List<String> list = new ArrayList<>(ids);
        String placeholders = String.join(",", list.stream().map(x -> "?").toList());
        Map<String, String> out = new HashMap<>(list.size());
        try {
            for (KbKnowledgeRecord r : knowledgeMapper.selectListByQuery(
                QueryWrapper.create().where("id IN (" + placeholders + ")", list.toArray()))) {
                String title = r.getTitle();
                if (title == null || title.isBlank()) {
                    title = r.getFileName();
                }
                if (title != null && !title.isBlank()) {
                    out.put(r.getId(), title);
                }
            }
        } catch (RuntimeException e) {
            // 标题只影响可读性 ⇒ 降级为「未命名文档」，⛔ 不打断问答
            log.warn("[上下文] 文档标题查询失败，统一显示「{}」数量={} 原因={}",
                UNTITLED, ids.size(), e.getMessage());
            return Map.of();
        }
        if (out.size() < ids.size() && log.isDebugEnabled()) {
            log.debug("[上下文] 有 {} 个文档取不到标题，将显示「{}」",
                ids.size() - out.size(), UNTITLED);
        }
        // ⭐ 阶段 5b 配套：wiki 页的 {@code knowledgeId} 是**页 id**，不在 kb_knowledge 里
        //   ⇒ 上面查不到，会显示「未命名文档」。回退查一次 kb_wiki_page。
        //   ⚠️ 不回退的话，用户看到的是「[2] 《未命名文档》」—— 引用了 wiki 内容却认不出来源。
        if (out.size() < ids.size()) {
            fillWikiTitles(ids, out);
        }
        return out;
    }

    /**
     * 为取不到标题的 id 回退查 wiki 页标题（阶段 5b 配套）。
     *
     * @param ids 全部需要的 id
     * @param out 已解析的（本方法就地向它补）
     */
    private void fillWikiTitles(Set<String> ids, Map<String, String> out) {
        if (wikiPageMapper == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            if (!out.containsKey(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        try {
            String placeholders = String.join(",", missing.stream().map(x -> "?").toList());
            for (com.nexusai.kb.repo.entity.KbWikiPageRecord r : wikiPageMapper.selectListByQuery(
                QueryWrapper.create().where("id IN (" + placeholders + ")", missing.toArray()))) {
                String title = r.getTitle();
                if (title != null && !title.isBlank()) {
                    out.put(r.getId(), title);
                }
            }
        } catch (RuntimeException e) {
            // 标题只影响可读性 ⇒ 忽略（保持「未命名文档」）
            log.warn("[上下文] wiki 页标题查询失败，相关段显示「{}」数量={} 原因={}",
                UNTITLED, missing.size(), e.getMessage());
        }
    }

    /** 截断到单段上限，按码点切（不切坏代理对）。 */
    private static String truncate(String content) {
        String s = content == null ? "" : content.strip();
        if (KbMergeText.runeLen(s) <= SEGMENT_MAX_CHARS) {
            return s;
        }
        int end = s.offsetByCodePoints(0, SEGMENT_MAX_CHARS);
        return s.substring(0, end) + "…（后略）";
    }
}
