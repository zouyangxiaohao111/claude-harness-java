package com.nexusai.kb.app.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wiki Map 阶段（每文档一次）· W2-4。
 *
 * <p><b>源侧对照</b>：WeKnora {@code wiki_ingest_batch.go:1342 mapOneDocument}。
 *
 * <h2>流程</h2>
 * <pre>
 * Pass 0：抽候选 slug                       ← KbWikiPrompts.CANDIDATE_SLUG_SYSTEM
 * 并行：
 *   ├─ 摘要页                                ← SUMMARY_SYSTEM
 *   └─ chunk 引用分类（N 批）                ← CHUNK_CITATION_SYSTEM
 * 去重（新条目 vs 已有页面）                 ← DEDUPLICATION_SYSTEM
 * </pre>
 *
 * <h2>⭐⭐ 三个必须守住的语义</h2>
 * <ol>
 *   <li><b>空内容守卫</b>：扫描件没有可读文本时必须<b>跳过</b>，⛔ 不能让模型对着
 *       图片标记编造实体。返回值里带 {@code skipped=true} + 原因，调用方据此记账。</li>
 *   <li><b>文件名永不喂给模型</b>：本类的方法签名里<b>根本没有</b> title/filename 参数 ——
 *       这不是疏忽，是让「不小心传进去」在编译期就失败。</li>
 *   <li><b>模型调用失败不是致命错误</b>：一个文档失败只该让这一篇没有 Wiki 页面，
 *       ⛔ 不该让整批入库回滚（文档本身已经解析好、可检索了）。</li>
 * </ol>
 */
@Component
public class KbWikiMapStage {

    private static final Logger log = LoggerFactory.getLogger(KbWikiMapStage.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 源侧 {@code maxContentForWiki}：超长文档截断（32768 rune）。 */
    public static final int MAX_CONTENT_CHARS = 32768;

    /** 源侧 {@code maxRunesPerCitationBatch}：单个引用批的字符预算（12000）。 */
    public static final int MAX_CHARS_PER_CITATION_BATCH = 12000;

    /** 候选抽取的温度：需要稳定（同文档同结果），但不能 0（否则枚举僵化）。 */
    private static final double TEMP_CANDIDATE = 0.2;

    /** 摘要的温度：偏低，减少润色倾向。 */
    private static final double TEMP_SUMMARY = 0.3;

    /** 引用分类的温度：越确定越好（这是分类任务）。 */
    private static final double TEMP_CITATION = 0.0;

    /** 去重判同的温度：0，它本质是二分类。 */
    private static final double TEMP_DEDUPE = 0.0;

    private static final int MAX_TOKENS_CANDIDATE = 3000;
    private static final int MAX_TOKENS_SUMMARY = 4000;
    private static final int MAX_TOKENS_CITATION = 2000;
    private static final int MAX_TOKENS_DEDUPE = 1200;

    private final LlmSideCallClient sideCallClient;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    public KbWikiMapStage(LlmSideCallClient sideCallClient) {
        this.sideCallClient = sideCallClient;
    }

    // ══════════════════════════════════════════════════════════
    // 数据载体
    // ══════════════════════════════════════════════════════════

    /**
     * 一个候选条目。
     *
     * @param type        entity / concept
     * @param slug        规范化后的完整 slug
     * @param name        显示名
     * @param aliases     别名
     * @param description 15-40 词自包含描述
     * @param details     ≤300 字符兜底
     * @param chunkIds    实质性讨论它的 chunk（Map 阶段为空，引用分类后填）
     */
    public record Candidate(
        String type, String slug, String name, List<String> aliases,
        String description, String details, List<String> chunkIds
    ) {
        public Candidate withChunks(List<String> ids) {
            return new Candidate(type, slug, name, aliases, description, details, ids);
        }
    }

    /**
     * Map 阶段结果。
     *
     * @param summaryContent 摘要页正文（首行 {@code SUMMARY: ...}）
     * @param summarySlug    摘要页 slug（{@code summary/<knowledgeId>}）
     * @param candidates     候选条目（已去重、已带 chunk 引用）
     * @param skipped        是否跳过（无可用文本）
     * @param skipReason     跳过原因（人可读）
     */
    public record MapResult(
        String summarySlug, String summaryContent,
        List<Candidate> candidates, boolean skipped, String skipReason
    ) {
        public static MapResult skipped(String reason) {
            return new MapResult(null, null, List.of(), true, reason);
        }

        public static MapResult empty(String summarySlug, String summaryContent) {
            return new MapResult(summarySlug, summaryContent, List.of(), false, null);
        }
    }

    /** 引用分类的输入单元。 */
    public record CitationChunk(String id, String content) {
    }

    /**
     * 已有页面（用于去重与 wiki 链接白名单）。
     *
     * @param slug    页面 slug
     * @param title   标题
     * @param aliases 别名（可空）
     */
    public record ExistingPage(String slug, String title, List<String> aliases) {
    }

    // ══════════════════════════════════════════════════════════
    // 主流程
    // ══════════════════════════════════════════════════════════

    /**
     * 处理一篇文档。
     *
     * <p><b>⚠️ 没有 title / filename 参数</b> —— 见类注释铁律 2。
     *
     * @param knowledgeId  文档 ID（用于摘要页 slug 与引用来源）
     * @param fullText     重建后的全文（chunk 拼接）
     * @param chunks       带 id 的 chunk 列表（引用分类用）
     * @param existingPages 已有页面（去重用；可空）
     * @param oldSlugs     该文档上次生成的 slug 集（复用；可空）
     * @param modelName    模型名（null ⇒ 跳过并记账）
     * @param granularity  focused / standard / exhaustive
     * @param contentInstructions 文风说明（可空）
     * @return Map 结果（永不 null）
     */
    public MapResult mapOneDocument(
        String knowledgeId, String fullText, List<CitationChunk> chunks,
        List<ExistingPage> existingPages, List<String> oldSlugs,
        String modelName, String granularity, String contentInstructions
    ) {
        // ── 守卫 1：无可用文本 ⇒ 跳过（⛔ 不许让模型凭空编造）──────────
        if (!hasSufficientText(fullText)) {
            log.info("[Wiki] Map 跳过（无可用文本） knowledge={} 字符数={}",
                knowledgeId, fullText == null ? 0 : fullText.length());
            return MapResult.skipped("无可用文本（可能是扫描件，未提取到文字）");
        }
        if (modelName == null || modelName.isBlank()) {
            log.warn("[Wiki] Map 跳过（未配置模型） knowledge={}", knowledgeId);
            return MapResult.skipped("未配置 Wiki 生成模型");
        }
        ProviderConfig config = resolveConfig(modelName);
        if (config == null) {
            log.warn("[Wiki] Map 跳过（provider 配置解析失败） knowledge={} model={}",
                knowledgeId, modelName);
            return MapResult.skipped("模型 provider 配置无法解析");
        }

        String content = truncate(fullText, MAX_CONTENT_CHARS);
        String summarySlug = KbWikiSlug.summarySlug(knowledgeId);
        List<ExistingPage> pages = existingPages == null ? List.of() : existingPages;
        List<String> prevSlugs = oldSlugs == null ? List.of() : oldSlugs;

        // ── Pass 0：抽候选 ─────────────────────────────────────────
        List<Candidate> candidates = extractCandidates(
            config, modelName, content, pages, prevSlugs, granularity, contentInstructions);
        if (log.isInfoEnabled()) {
            log.info("[Wiki] Map Pass0 候选 knowledge={} 条数={}", knowledgeId, candidates.size());
        }

        // ── 摘要 与 引用分类 并行（Pass 0 已产出候选，两者互不依赖）────
        SummaryAndCitations both = runSummaryAndCitations(
            config, modelName, content, chunks, candidates, pages);

        // ── 去重判同 ──────────────────────────────────────────────
        List<Candidate> deduped = deduplicate(config, modelName, both.candidates(), pages);

        return new MapResult(summarySlug, both.summary(), deduped, false, null);
    }

    // ══════════════════════════════════════════════════════════
    // Pass 0：候选抽取
    // ══════════════════════════════════════════════════════════

    List<Candidate> extractCandidates(
        ProviderConfig config, String modelName, String content,
        List<ExistingPage> pages, List<String> prevSlugs,
        String granularity, String contentInstructions
    ) {
        String granularityBlock = KbWikiPrompts.granularityBlock(granularity);
        if (contentInstructions != null && !contentInstructions.isBlank()) {
            granularityBlock = granularityBlock + "\n<content_instructions>\n"
                + contentInstructions.trim() + "\n</content_instructions>\n";
        }
        String user = KbWikiPrompts.CANDIDATE_SLUG_USER
            .replace("{{GRANULARITY_BLOCK}}", granularityBlock)
            .replace("{{PREVIOUS_SLUGS}}", prevSlugs.isEmpty() ? "（无，这是首次生成）" : String.join("\n", prevSlugs))
            .replace("{{DOCUMENT_CONTENT}}", content);

        String raw = callModel(config, modelName, KbWikiPrompts.CANDIDATE_SLUG_SYSTEM, user,
            MAX_TOKENS_CANDIDATE, TEMP_CANDIDATE, "wiki_candidate_slug", null);
        return parseCandidates(raw);
    }

    /**
     * 解析候选清单。
     *
     * <p>容错：markdown 围栏 / 前后废话 / 未知类型一律跳过（⛔ 不猜）。
     */
    static List<Candidate> parseCandidates(String raw) {
        JsonNode root = parseJsonLoose(raw);
        if (root == null) {
            return List.of();
        }
        // 去重按 slug；同一 slug 出现两次时保留第一次（后出现的通常是模型重复输出）
        Map<String, Candidate> bySlug = new LinkedHashMap<>();
        collectCandidates(root.get("entities"), "entity", bySlug);
        collectCandidates(root.get("concepts"), "concept", bySlug);
        return new ArrayList<>(bySlug.values());
    }

    private static void collectCandidates(JsonNode arr, String type, Map<String, Candidate> out) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode n : arr) {
            String name = text(n, "name");
            String rawSlug = text(n, "slug");
            // ⭐ 优先用模型给的 slug，但必须规范化
            String slug = KbWikiSlug.normalize(rawSlug);
            if (slug == null) {
                // ⚠️ 只有「压根没给 slug」才允许用 name 兜底；
                //    给了 slug 但类型非法（如 weird/x）说明模型理解错了 ——
                //    ⛔ 用 name 重新生成会把它「救」成一条合法条目，那是猜。
                if (rawSlug == null || rawSlug.isBlank()) {
                    if (name != null && !name.isBlank()) {
                        slug = KbWikiSlug.of(type, name);
                    }
                }
                if (slug == null) {
                    continue;
                }
            }
            // 类型以前缀为准（模型常把 concept 放进 entities）
            String actualType = KbWikiSlug.typeOf(slug);
            List<String> aliases = stringList(n.get("aliases"));
            out.putIfAbsent(slug, new Candidate(
                actualType, slug,
                name == null || name.isBlank() ? slug.substring(slug.indexOf('/') + 1) : name.trim(),
                aliases,
                text(n, "description"),
                text(n, "details"),
                List.of()));
        }
    }

    // ══════════════════════════════════════════════════════════
    // 摘要 + 引用分类（并行）
    // ══════════════════════════════════════════════════════════

    private record SummaryAndCitations(String summary, List<Candidate> candidates) {
    }

    private SummaryAndCitations runSummaryAndCitations(
        ProviderConfig config, String modelName, String content,
        List<CitationChunk> chunks, List<Candidate> candidates, List<ExistingPage> pages
    ) {
        // ⚠️ 用虚拟线程并行：两个调用都是阻塞 IO，不需要 CPU 池。
        //    Java 21+ 的虚拟线程开销极低，且不会因池大小限制串行化。
        String summary;
        List<Candidate> cited;
        try (var scope = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var summaryFuture = scope.submit(() ->
                generateSummary(config, modelName, content, pages));
            var citationFuture = scope.submit(() ->
                classifyCitations(config, modelName, chunks, candidates));
            summary = futureGet(summaryFuture, "摘要生成");
            cited = futureGet(citationFuture, "引用分类");
        }
        // 任一路失败 ⇒ 用降级值（不是抛）：summary 为 null 时用摘要页占位，
        // candidates 为 null 时用 Pass 0 的结果且不带 chunk 引用。
        if (summary == null) {
            log.warn("[Wiki] 摘要生成失败，使用占位摘要");
            summary = "SUMMARY: 摘要生成失败，本页仅保留结构。\n\n## 要点\n- （摘要生成失败）\n";
        }
        if (cited == null) {
            cited = candidates;
        }
        return new SummaryAndCitations(summary, cited);
    }

    String generateSummary(ProviderConfig config, String modelName, String content,
                           List<ExistingPage> pages) {
        String user = KbWikiPrompts.SUMMARY_USER
            .replace("{{AVAILABLE_PAGES}}", renderAvailablePages(pages))
            .replace("{{DOCUMENT_CONTENT}}", content);
        String raw = callModel(config, modelName, KbWikiPrompts.SUMMARY_SYSTEM, user,
            MAX_TOKENS_SUMMARY, TEMP_SUMMARY, "wiki_summary", null);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * 引用分类：按字符预算切成多批，逐批调用。
     *
     * <p>⚠️ <b>不并行</b>这些批次：源侧用 4 并发，但那是 Go 的 goroutine 池。
     * 本仓若同时打 4 个 LLM 请求，会显著提高触发 provider 限流的概率，
     * 而限流失败会静默丢引用（页面看起来正常，只是没法溯源）。
     * ⇒ 串行更慢但更稳；并发度作为常量暴露，将来要调只改一处。
     */
    List<Candidate> classifyCitations(
        ProviderConfig config, String modelName,
        List<CitationChunk> chunks, List<Candidate> candidates
    ) {
        if (chunks == null || chunks.isEmpty() || candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        List<List<CitationChunk>> batches = splitBatches(chunks, MAX_CHARS_PER_CITATION_BATCH);
        // 累积：slug → chunkId 集
        Map<String, Set<String>> citations = new LinkedHashMap<>();
        Map<String, Candidate> extras = new LinkedHashMap<>();

        String candidateBlock = renderCandidateSlugs(candidates);
        for (int i = 0; i < batches.size(); i++) {
            List<CitationChunk> batch = batches.get(i);
            String user = KbWikiPrompts.CHUNK_CITATION_USER
                .replace("{{CANDIDATE_SLUGS}}", candidateBlock)
                .replace("{{CHUNKS}}", renderChunks(batch));
            String raw = callModel(config, modelName, KbWikiPrompts.CHUNK_CITATION_SYSTEM, user,
                MAX_TOKENS_CITATION, TEMP_CITATION, "wiki_chunk_citation", "batch" + i);
            if (raw == null) {
                log.warn("[Wiki] 引用分类第 {} 批失败，跳过该批（不阻塞其他批）", i);
                continue;
            }
            mergeCitationResult(raw, citations, extras);
        }

        List<Candidate> out = new ArrayList<>(candidates.size() + extras.size());
        for (Candidate c : candidates) {
            Set<String> ids = citations.get(c.slug());
            out.add(ids == null || ids.isEmpty() ? c : c.withChunks(new ArrayList<>(ids)));
        }
        // 模型新发现的条目：补在最后
        for (Map.Entry<String, Candidate> e : extras.entrySet()) {
            Set<String> ids = citations.get(e.getKey());
            out.add(ids == null || ids.isEmpty() ? e.getValue()
                : e.getValue().withChunks(new ArrayList<>(ids)));
        }
        return out;
    }

    private static void mergeCitationResult(
        String raw, Map<String, Set<String>> citations, Map<String, Candidate> extras
    ) {
        JsonNode root = parseJsonLoose(raw);
        if (root == null) {
            return;
        }
        JsonNode cites = root.get("citations");
        if (cites != null && cites.isObject()) {
            cites.fieldNames().forEachRemaining(key -> {
                String slug = KbWikiSlug.normalize(key);
                if (slug == null) {
                    return; // 模型返回的非法 slug 直接丢
                }
                List<String> ids = stringList(cites.get(key));
                citations.computeIfAbsent(slug, k -> new LinkedHashSet<>()).addAll(ids);
            });
        }
        JsonNode news = root.get("new_slugs");
        if (news != null && news.isArray()) {
            for (JsonNode n : news) {
                String rawSlug = text(n, "slug");
                String name = text(n, "name");
                String slug = KbWikiSlug.normalize(rawSlug);
                if (slug == null && name != null) {
                    String t = text(n, "type");
                    String type = "concept".equalsIgnoreCase(t) ? "concept" : "entity";
                    slug = KbWikiSlug.of(type, name);
                }
                if (slug == null) {
                    continue;
                }
                List<String> ids = stringList(n.get("source_chunks"));
                if (!ids.isEmpty()) {
                    citations.computeIfAbsent(slug, k -> new LinkedHashSet<>()).addAll(ids);
                }
                extras.putIfAbsent(slug, new Candidate(
                    KbWikiSlug.typeOf(slug), slug,
                    name == null ? slug : name, stringList(n.get("aliases")),
                    text(n, "description"), text(n, "details"), List.of()));
            }
        }
    }

    /**
     * 按字符预算切批。
     *
     * <p>⚠️ 单个 chunk 超过预算时也要独占一批（⛔ 不能跳过 —— 跳了会丢引用）。
     */
    static List<List<CitationChunk>> splitBatches(List<CitationChunk> chunks, int maxChars) {
        List<List<CitationChunk>> out = new ArrayList<>();
        List<CitationChunk> cur = new ArrayList<>();
        int size = 0;
        for (CitationChunk c : chunks) {
            int len = c.content() == null ? 0 : c.content().codePointCount(0, c.content().length());
            if (!cur.isEmpty() && size + len > maxChars) {
                out.add(cur);
                cur = new ArrayList<>();
                size = 0;
            }
            cur.add(c);
            size += len;
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════
    // 去重判同
    // ══════════════════════════════════════════════════════════

    /**
     * 去重：把「与已有页面指同一事物」的候选合并掉。
     *
     * <p>⚠️ 合并的语义是<b>把 slug 换成已有页面的 slug</b>（并合并 chunk 引用），
     * 而不是丢候选 —— 候选里的事实还要并进那个已有页面。
     */
    List<Candidate> deduplicate(
        ProviderConfig config, String modelName,
        List<Candidate> candidates, List<ExistingPage> pages
    ) {
        if (candidates.isEmpty() || pages.isEmpty()) {
            return candidates;
        }
        Map<String, ExistingPage> pageBySlug = new LinkedHashMap<>();
        for (ExistingPage p : pages) {
            pageBySlug.put(p.slug(), p);
        }

        // ⭐ 只对「可能相关」的条目做判同：把已有页面里同类型、名字或别名有交集的列出来。
        //    全量塞给模型会让 prompt 爆炸，且极容易误并。
        Map<String, List<ExistingPage>> candidatesBySlug = new LinkedHashMap<>();
        List<Candidate> needDedupe = new ArrayList<>();
        for (Candidate c : candidates) {
            List<ExistingPage> related = relatedPages(c, pageBySlug.values());
            if (!related.isEmpty()) {
                candidatesBySlug.put(c.slug(), related);
                needDedupe.add(c);
            }
        }
        if (needDedupe.isEmpty()) {
            return candidates;
        }

        String user = KbWikiPrompts.DEDUPLICATION_USER
            .replace("{{NEW_SLUGS}}", renderNewSlugs(needDedupe))
            .replace("{{CANDIDATES_BY_SLUG}}", renderCandidatesBySlug(candidatesBySlug));
        String raw = callModel(config, modelName, KbWikiPrompts.DEDUPLICATION_SYSTEM, user,
            MAX_TOKENS_DEDUPE, TEMP_DEDUPE, "wiki_dedup", null);

        Map<String, String> merges = parseMerges(raw);
        if (merges.isEmpty()) {
            return candidates;
        }

        // 应用合并：slug 换成目标，chunk 引用取并集
        Map<String, Candidate> bySlug = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            String target = merges.get(c.slug());
            if (target == null) {
                bySlug.putIfAbsent(c.slug(), c);
                continue;
            }
            // 只能并到「该条目自己的 candidates」里（源侧硬约束）
            List<ExistingPage> allowed = candidatesBySlug.get(c.slug());
            if (allowed == null || allowed.stream().noneMatch(p -> p.slug().equals(target))) {
                log.warn("[Wiki] 去重目标不在候选集内，忽略该合并 {} → {}", c.slug(), target);
                bySlug.putIfAbsent(c.slug(), c);
                continue;
            }
            Candidate prev = bySlug.get(target);
            Set<String> ids = new LinkedHashSet<>();
            if (prev != null && prev.chunkIds() != null) {
                ids.addAll(prev.chunkIds());
            }
            if (c.chunkIds() != null) {
                ids.addAll(c.chunkIds());
            }
            if (prev == null) {
                ExistingPage p = pageBySlug.get(target);
                bySlug.put(target, new Candidate(
                    KbWikiSlug.typeOf(target), target,
                    p == null ? target : p.title(),
                    p == null ? List.of() : p.aliases(),
                    c.description(), c.details(), new ArrayList<>(ids)));
            } else {
                bySlug.put(target, prev.withChunks(new ArrayList<>(ids)));
            }
            log.info("[Wiki] 去重合并 {} → {}（同事物）", c.slug(), target);
        }
        return new ArrayList<>(bySlug.values());
    }

    /**
     * 找「可能有交集」的已有页面。
     *
     * <p>判据刻意宽松（同类型 + 名字/别名任一同名或互为子串）——
     * 这一步只是<b>缩小候选</b>，真正的判同交给模型。
     */
    private static List<ExistingPage> relatedPages(Candidate c, Iterable<ExistingPage> pages) {
        List<ExistingPage> out = new ArrayList<>();
        String cType = c.type();
        String cName = normalizeName(c.name());
        for (ExistingPage p : pages) {
            if (!cType.equals(KbWikiSlug.typeOf(p.slug()))) {
                continue;
            }
            String pName = normalizeName(p.title());
            if (!cName.isEmpty() && (cName.equals(pName)
                || cName.contains(pName) || pName.contains(cName))) {
                out.add(p);
                continue;
            }
            for (String alias : p.aliases() == null ? List.<String>of() : p.aliases()) {
                String a = normalizeName(alias);
                if (!a.isEmpty() && (a.equals(cName) || cName.contains(a) || a.contains(cName))) {
                    out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    private static String normalizeName(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    static Map<String, String> parseMerges(String raw) {
        JsonNode root = parseJsonLoose(raw);
        if (root == null) {
            return Map.of();
        }
        JsonNode arr = root.get("merges");
        if (arr == null || !arr.isArray()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode n : arr) {
            String from = KbWikiSlug.normalize(text(n, "slug"));
            String into = KbWikiSlug.normalize(text(n, "into"));
            if (from != null && into != null && !from.equals(into)) {
                out.put(from, into);
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════
    // 渲染辅助
    // ══════════════════════════════════════════════════════════

    static String renderAvailablePages(List<ExistingPage> pages) {
        if (pages == null || pages.isEmpty()) {
            return "（暂无已有页面）";
        }
        StringBuilder sb = new StringBuilder();
        for (ExistingPage p : pages) {
            sb.append("[[").append(p.slug()).append("]] = ").append(p.title());
            if (p.aliases() != null && !p.aliases().isEmpty()) {
                sb.append(" (Aliases: ").append(String.join(", ", p.aliases())).append(")");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String renderCandidateSlugs(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : candidates) {
            sb.append("- ").append(c.slug()).append(" = ").append(c.name());
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(" — ").append(c.description());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String renderChunks(List<CitationChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (CitationChunk c : chunks) {
            sb.append("<c id=\"").append(c.id()).append("\">\n")
                .append(c.content() == null ? "" : c.content())
                .append("\n</c>\n");
        }
        return sb.toString();
    }

    private static String renderNewSlugs(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Candidate c : candidates) {
            sb.append("- ").append(c.slug()).append(" (").append(c.type()).append(") = ")
                .append(c.name());
            if (c.aliases() != null && !c.aliases().isEmpty()) {
                sb.append(" [aliases: ").append(String.join(", ", c.aliases())).append(']');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String renderCandidatesBySlug(Map<String, List<ExistingPage>> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((slug, pages) -> {
            sb.append("<candidates for=\"").append(slug).append("\">\n");
            for (ExistingPage p : pages) {
                sb.append("  - ").append(p.slug()).append(" = ").append(p.title());
                if (p.aliases() != null && !p.aliases().isEmpty()) {
                    sb.append(" [aliases: ").append(String.join(", ", p.aliases())).append(']');
                }
                sb.append('\n');
            }
            sb.append("</candidates>\n");
        });
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════
    // 基础设施
    // ══════════════════════════════════════════════════════════

    /**
     * 判断是否有足够文本。
     *
     * <p>⭐ 阈值 20 个非空白码点：低于这个量，模型只能编造。
     * 源侧的 {@code hasSufficientTextContent} 逻辑相同（防扫描件幻觉）。
     *
     * <h2>⭐⭐ 必须先剔除 Markdown 图片标记</h2>
     * 扫描件的 OCR 结果常常<b>只有</b> {@code ![图1](http://…/1.png)} 这类标记。
     * 若按「非空白字符数」计数，一行图片链接就有几十个字符 ⇒ <b>守卫失效</b>，
     * 模型照样被喂进去、照样编造。所以计数前先去掉 {@code ![alt](url)}。
     */
    static boolean hasSufficientText(String text) {
        if (text == null) {
            return false;
        }
        // ⚠️ 去掉 Markdown 图片：![alt](url) —— 这些不是可读内容
        String stripped = text.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ");
        int meaningful = 0;
        for (int i = 0; i < stripped.length(); ) {
            int cp = stripped.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isWhitespace(cp)) {
                meaningful++;
                if (meaningful >= 20) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 按码点截断（⛔ 不用 substring，会切断代理对）。 */
    static String truncate(String s, int maxCodePoints) {
        if (s == null) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= maxCodePoints) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxCodePoints));
    }

    private ProviderConfig resolveConfig(String modelName) {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            ModelConfigResolver.ResolvedModel r = modelConfigResolver.resolve(modelName);
            return r == null ? null : r.config();
        } catch (RuntimeException e) {
            log.warn("[Wiki] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    /** 单发调用（失败返回 null，由调用方降级）。 */
    private String callModel(
        ProviderConfig config, String modelName, String system, String user,
        int maxTokens, double temperature, String label, String suffix
    ) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("[Wiki] 调用模型 label={} model={} max_tokens={} 温度={}",
                    label, modelName, maxTokens, temperature);
            }
            return sideCallClient.chat(
                config, modelName, system, user,
                new LlmProvider.ChatRequestOptions(
                    null, null, null,
                    // Wiki 生成是「整理已有事实」，不需要推理链（省 token、更稳定）
                    LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
                    temperature,
                    suffix == null ? label : label + ":" + suffix,
                    null, maxTokens, null, null,
                    null, null, null, null,
                    null, null),
                // 无会话上下文（后台异步任务）⇒ standalone 归 UNKNOWN，仍统计
                LlmSideCallClient.SideCallUsage.standalone(UsageCategory.SIDE_CALL, label));
        } catch (RuntimeException e) {
            log.warn("[Wiki] 模型调用失败 label={} model={} 原因={}", label, modelName, e.getMessage());
            return null;
        }
    }

    private static <T> T futureGet(java.util.concurrent.Future<T> f, String what) {
        try {
            return f.get();
        } catch (Exception e) {
            log.warn("[Wiki] {} 并行任务失败: {}", what, e.toString());
            return null;
        }
    }

    /**
     * 宽松解析 JSON：容忍 markdown 围栏与前后废话。
     *
     * @param raw 模型原始输出
     * @return 根节点；无法解析返回 null
     */
    static JsonNode parseJsonLoose(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        // 去 markdown 围栏
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        try {
            return JSON.readTree(s);
        } catch (Exception ignored) {
            // 退一步：截取第一个 { 到最后一个 }
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return JSON.readTree(s.substring(start, end + 1));
                } catch (Exception ignored2) {
                    return null;
                }
            }
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            if (n != null && !n.isNull()) {
                String v = n.asText();
                if (v != null && !v.isBlank()) {
                    out.add(v.trim());
                }
            }
        }
        return out;
    }
}
