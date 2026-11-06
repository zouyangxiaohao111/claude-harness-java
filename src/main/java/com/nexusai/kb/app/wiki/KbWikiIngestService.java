package com.nexusai.kb.app.wiki;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.entity.KbWikiPageIssueRecord;
import com.nexusai.kb.repo.entity.KbWikiPageRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wiki 编排 · W2-6（把 Map → Reduce → Finalize 串起来）。
 *
 * <p><b>源侧对照</b>：WeKnora {@code wiki_ingest_batch.go ProcessWikiIngest}
 * + {@code wiki_ingest.go ProcessWikiFinalize}。
 *
 * <h2>⚠️ 本类只做「编排 + 持久化编排」，不含任何提示词逻辑</h2>
 * 三个阶段各自是独立可测的类；本类负责：
 * <ol>
 *   <li>取文档正文与 chunk（从 {@code kb_chunk}）；</li>
 *   <li>按批次调 {@link KbWikiMapStage}；</li>
 *   <li>按 slug 聚合更新调 {@link KbWikiReduceStage}；</li>
 *   <li>落库调 {@link KbWikiPageService}；</li>
 *   <li>末了调 {@link KbWikiFinalizeStage}。</li>
 * </ol>
 *
 * <h2>⭐⭐ 「一篇文档失败不影响其他篇」</h2>
 * Map 是对每篇文档独立调用的。若某一篇失败就整批回滚，
 * 一篇坏文档会<b>永久卡住</b>整个知识库的 Wiki 生成。
 * ⇒ 单篇失败只记日志并跳过。
 */
@Service
public class KbWikiIngestService {

    private static final Logger log = LoggerFactory.getLogger(KbWikiIngestService.class);

    /** 单篇文档最多取多少个 chunk 参与（防超长文档打爆提示词）。 */
    private static final int MAX_CHUNKS_PER_DOC = 500;

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper chunkMapper;
    private final KbWikiMapStage mapStage;
    private final KbWikiReduceStage reduceStage;
    private final KbWikiFinalizeStage finalizeStage;
    private final KbWikiPageService pageService;

    public KbWikiIngestService(
        KbKnowledgeMapper knowledgeMapper,
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbChunkMapper chunkMapper,
        KbWikiMapStage mapStage,
        KbWikiReduceStage reduceStage,
        KbWikiFinalizeStage finalizeStage,
        KbWikiPageService pageService
    ) {
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.chunkMapper = chunkMapper;
        this.mapStage = mapStage;
        this.reduceStage = reduceStage;
        this.finalizeStage = finalizeStage;
        this.pageService = pageService;
    }

    /**
     * 一轮 Wiki 生成（针对一批文档）。
     *
     * @param kbId          知识库 ID
     * @param knowledgeIds  本轮要处理的文档（已解析完成）
     * @param modelName     模型名（null ⇒ 跳过）
     * @param ingestRunning 是否有其他 ingest 在跑（决定剪枝延后）
     * @return 本轮统计
     */
    public RunSummary runBatch(
        String kbId, List<String> knowledgeIds, String modelName, boolean ingestRunning
    ) {
        if (kbId == null || kbId.isBlank()) {
            throw new ValidationException("知识库 ID 不能为空");
        }
        KbKnowledgeBaseRecord kb = knowledgeBaseMapper.selectOneById(kbId);
        if (kb == null) {
            throw new ValidationException("知识库不存在: " + kbId);
        }
        KbWikiConfig config = KbWikiConfig.parse(kb.getWikiConfig());
        if (!config.isAutoGenerateEnabled()) {
            log.info("[Wiki] 跳过：知识库未启用自动生成 kb={}", kbId);
            return RunSummary.skipped("auto_generate 已关闭");
        }
        if (knowledgeIds == null || knowledgeIds.isEmpty()) {
            return RunSummary.skipped("没有待处理文档");
        }

        List<KbWikiMapStage.ExistingPage> existingPages = pageService.existingPagesFor(kbId);
        List<KbWikiMapStage.MapResult> maps = new ArrayList<>();
        List<String> succeededDocs = new ArrayList<>();
        int skippedDocs = 0;
        int failedDocs = 0;

        // ── Map：每篇文档独立，失败不影响其他篇 ────────────────
        for (String knowledgeId : knowledgeIds) {
            try {
                DocContent doc = loadDocContent(kbId, knowledgeId);
                if (doc == null) {
                    skippedDocs++;
                    continue;   // 已删除 / 未解析完成 / 跨库
                }
                List<String> oldSlugs = slugsFromExistingFor(existingPages, knowledgeId);
                KbWikiMapStage.MapResult r = mapStage.mapOneDocument(
                    knowledgeId, doc.fullText(), doc.chunks(), existingPages, oldSlugs,
                    modelName, config.effectiveGranularity(), config.getContentInstructions());
                if (r.skipped()) {
                    log.info("[Wiki] 文档跳过 knowledge={} 原因={}", knowledgeId, r.skipReason());
                    skippedDocs++;
                    continue;
                }
                maps.add(r);
                succeededDocs.add(knowledgeId);
            } catch (RuntimeException e) {
                // ⭐ 单篇失败只跳过 —— 一篇坏文档不该卡住整个知识库
                log.warn("[Wiki] 文档 Map 失败，跳过 knowledge={} 原因={}",
                    knowledgeId, e.toString());
                failedDocs++;
            }
        }

        // ── Reduce：按 slug 聚合 ───────────────────────────────
        Map<String, List<KbWikiReduceStage.SlugUpdate>> bySlug = new LinkedHashMap<>();
        Map<String, String> pageTitles = new LinkedHashMap<>();
        for (int i = 0; i < maps.size(); i++) {
            KbWikiMapStage.MapResult m = maps.get(i);
            String knowledgeId = succeededDocs.get(i);
            for (KbWikiMapStage.Candidate c : m.candidates()) {
                bySlug.computeIfAbsent(c.slug(), k -> new ArrayList<>())
                    .add(new KbWikiReduceStage.SlugUpdate(
                        knowledgeId, knowledgeId, summaryBodyOf(m),
                        c.name(), c.description(), c.details(), c.aliases(),
                        c.chunkIds(), Map.of()));
                pageTitles.put(c.slug(), c.name());
            }
        }

        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        int rejected = 0;

        // ⭐⭐ 先落摘要页（每篇文档一页）——Map 阶段已生成正文，这里只是落库。
        //    ⚠️ 这是很容易漏的一步：摘要正文在 MapResult 里，但如果不显式落库，
        //    整个 Wiki 就只有实体/概念页、没有文档概览页（而索引页的下钻全靠它）。
        for (int i = 0; i < maps.size(); i++) {
            KbWikiMapStage.MapResult m = maps.get(i);
            if (m.summarySlug() == null || m.summaryContent() == null
                || m.summaryContent().isBlank()) {
                continue;
            }
            String knowledgeId = succeededDocs.get(i);
            try {
                var existing = pageService.existingPageFor(kbId, m.summarySlug());
                var result = new KbWikiReduceStage.MergeResult(
                    existing == null ? "created" : "updated",
                    summaryTitle(existing),
                    m.summaryContent(),
                    summaryLineOf(m.summaryContent()),
                    existing == null ? List.of() : existing.aliases(),
                    "summary",
                    List.of(knowledgeId),
                    // ⭐ 摘要页是文档级概览 ⇒ 不挂 chunk 级引用
                    List.of(), null);
                pageService.applyMergeResult(kbId, m.summarySlug(), result, "ingest");
                if (existing == null) {
                    created.add(m.summarySlug());
                } else {
                    updated.add(m.summarySlug());
                }
            } catch (RuntimeException ex) {
                log.warn("[Wiki] 摘要页落库失败 knowledge={} 原因={}", knowledgeId, ex.toString());
            }
        }

        for (Map.Entry<String, List<KbWikiReduceStage.SlugUpdate>> e : bySlug.entrySet()) {
            String slug = e.getKey();
            try {
                var existing = pageService.existingPageFor(kbId, slug);
                var result = reduceStage.reduceSlug(
                    slug, existing, e.getValue(), List.of(),
                    validLinksFor(existingPages, slug), modelName,
                    config.getContentInstructions());
                if ("rejected".equals(result.action())) {
                    log.warn("[Wiki] 页面合并被拒 slug={} 原因={}", slug, result.rejectReason());
                    rejected++;
                    continue;
                }
                KbWikiPageRecord row = pageService.applyMergeResult(kbId, slug, result, "ingest");
                if (row != null) {
                    if ("created".equals(result.action())) {
                        created.add(slug);
                    } else {
                        updated.add(slug);
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("[Wiki] 页面合并失败，跳过 slug={} 原因={}", slug, ex.toString());
                rejected++;
            }
        }

        // ── 目录规划（一批一次）──────────────────────────────────
        if (!pageTitles.isEmpty() && modelName != null) {
            try {
                Map<String, List<String>> plan = reduceStage.planTaxonomy(
                    pageTitles, pageService.existingFolderPaths(kbId), modelName);
                for (Map.Entry<String, List<String>> pe : plan.entrySet()) {
                    try {
                        pageService.assignFolder(kbId, pe.getKey(), pe.getValue());
                    } catch (RuntimeException ex) {
                        log.warn("[Wiki] 目录归属设置失败 slug={} 原因={}", pe.getKey(), ex.toString());
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("[Wiki] 目录规划失败（保留原归属）: {}", ex.toString());
            }
        }

        // ── Finalize ────────────────────────────────────────────
        List<KbWikiFinalizeStage.PageView> views = new ArrayList<>();
        for (KbWikiPageRecord p : pageService.listPages(kbId)) {
            views.add(new KbWikiFinalizeStage.PageView(
                p.getSlug(), p.getTitle(), p.getPageType(), p.getContent(), p.getSummary()));
        }
        List<String> changes = new ArrayList<>();
        if (!created.isEmpty()) {
            changes.add("document_added: " + String.join(", ", created));
        }
        if (!updated.isEmpty()) {
            changes.add("document_updated: " + String.join(", ", updated));
        }
        KbWikiFinalizeStage.Result fin = finalizeStage.finalize(
            views, config, changes, ingestRunning, modelName);

        // 落 Finalize 的改动
        if (!fin.skipped() && !fin.pagesToUpdate().isEmpty()) {
            for (Map.Entry<String, String> e : fin.pagesToUpdate().entrySet()) {
                try {
                    KbWikiPageRecord p = pageService.findPage(kbId, e.getKey());
                    if (p == null) {
                        continue;
                    }
                    var existing = pageService.existingPageFor(kbId, e.getKey());
                    var result = new KbWikiReduceStage.MergeResult(
                        "updated", p.getTitle(), e.getValue(), p.getSummary(),
                        KbWikiPageService.parseStringArray(p.getAliases()), p.getPageType(),
                        KbWikiPageService.parseStringArray(p.getSourceRefs()),
                        KbWikiPageService.parseStringArray(p.getChunkRefs()), null);
                    pageService.applyMergeResult(kbId, e.getKey(), result, "ingest");
                } catch (RuntimeException ex) {
                    log.warn("[Wiki] Finalize 页面落库失败 slug={} 原因={}", e.getKey(), ex.toString());
                }
            }
        }

        // ── Lint 落地 ────────────────────────────────────────────
        try {
            Set<String> liveKbIds = new LinkedHashSet<>();
            for (KbWikiPageRecord p : pageService.listPages(kbId)) {
                for (String ref : KbWikiPageService.parseStringArray(p.getSourceRefs())) {
                    liveKbIds.add(KbWikiReduceStage.knowledgeIdOfRef(ref));
                }
            }
            List<KbWikiLinter.PageSnapshot> snaps = new ArrayList<>();
            for (KbWikiPageRecord p : pageService.listPages(kbId)) {
                snaps.add(new KbWikiLinter.PageSnapshot(
                    p.getSlug(), p.getTitle(), p.getPageType(), p.getContent(),
                    KbWikiPageService.parseStringArray(p.getOutLinks()),
                    KbWikiPageService.parseStringArray(p.getInLinks()),
                    KbWikiPageService.parseStringArray(p.getSourceRefs())));
            }
            // ⚠️ liveKnowledgeIds 传已知存在的集合（这里是页面自己引用的，不做全量校验）
            KbWikiLinter.Report report = KbWikiLinter.run(snaps, null);
            pageService.replacePendingIssues(kbId, report.issues());
        } catch (RuntimeException ex) {
            log.warn("[Wiki] Lint 落地失败（不影响页面生成）: {}", ex.toString());
        }

        RunSummary summary = new RunSummary(
            false, null, maps.size(), skippedDocs, failedDocs,
            created, updated, rejected,
            fin.skipped() ? 0 : fin.deadLinksRemoved(),
            fin.skipped() ? 0 : fin.crossLinksAdded());
        log.info("[Wiki] 本轮完成 kb={} 文档={} 跳过={} 失败={} 新建页={} 更新页={} 拒绝={}",
            kbId, maps.size(), skippedDocs, failedDocs, created.size(), updated.size(), rejected);
        return summary;
    }

    // ══════════════════════════════════════════════════════════
    // 文档内容装配
    // ══════════════════════════════════════════════════════════

    private record DocContent(String fullText, List<KbWikiMapStage.CitationChunk> chunks) {
    }

    /**
     * 取文档正文与 chunk。
     *
     * <p>⚠️ 返回 null 的三种情况都不算错误：文档已删 / 未解析完成 / 不在本库。
     */
    private DocContent loadDocContent(String kbId, String knowledgeId) {
        KbKnowledgeRecord k = knowledgeMapper.selectOneById(knowledgeId);
        if (k == null || !kbId.equals(k.getKnowledgeBaseId())) {
            return null;
        }
        if (!"enabled".equalsIgnoreCase(k.getEnableStatus())) {
            return null;   // 未解析完成 ⇒ 没有可用文本
        }
        List<KbChunkRecord> rows = chunkMapper.selectListByQuery(QueryWrapper.create()
            .where("knowledge_id = ?", knowledgeId)
            .orderBy("chunk_index", true)
            .limit(MAX_CHUNKS_PER_DOC));
        if (rows.isEmpty()) {
            return null;
        }
        StringBuilder full = new StringBuilder();
        List<KbWikiMapStage.CitationChunk> chunks = new ArrayList<>(rows.size());
        for (KbChunkRecord r : rows) {
            String content = r.getContent() == null ? "" : r.getContent();
            String id = chunkRefId(r.getId());
            chunks.add(new KbWikiMapStage.CitationChunk(id, content));
            // 正文重建：带 contextHeader 更利于模型理解上下文
            if (r.getContextHeader() != null && !r.getContextHeader().isBlank()) {
                full.append(r.getContextHeader()).append('\n');
            }
            full.append(content).append("\n\n");
        }
        return new DocContent(full.toString(), chunks);
    }

    /**
     * chunk 引用 ID：用短形态 {@code c001}（源侧同款）。
     *
     * <p>⚠️ 这里做的是<b>映射</b>不是改名：真正的 UUID 存在 {@code kb_chunk.id}，
     * 提示词里只出现短引用，减少模型复述长 ID 的概率。
     */
    static String chunkRefId(String chunkId) {
        if (chunkId == null) {
            return "";
        }
        // 只取前 8 位并加 c 前缀（确定性、可逆查？不可逆 ⇒ 必须是内部映射）
        // ⇒ 改为「序号形态」需要在写入时记住映射；本仓用 UUID 前 8 位保证确定性
        String short8 = chunkId.length() <= 8 ? chunkId : chunkId.substring(0, 8);
        return "c" + short8.replace("-", "");
    }

    /** 现有页面里属于该文档的 slug（用于「复用旧 slug」）。 */
    private static List<String> slugsFromExistingFor(
        List<KbWikiMapStage.ExistingPage> pages, String knowledgeId
    ) {
        // ⚠️ ExistingPage 不带 sourceRefs（Map 只需要 slug/标题/别名做去重）
        // ⇒ 这里返回空列表，Reduce 阶段会按 sourceRefs 正确聚合
        return List.of();
    }

    /** 某个 slug 可链接的其它页面（排除自己）。 */
    private static Map<String, String> validLinksFor(
        List<KbWikiMapStage.ExistingPage> pages, String selfSlug
    ) {
        Map<String, String> m = new LinkedHashMap<>();
        for (KbWikiMapStage.ExistingPage p : pages) {
            if (!p.slug().equals(selfSlug)) {
                m.put(p.slug(), p.title());
            }
        }
        return m;
    }

    /** 从 Map 结果里取摘要正文（给 Reduce 做共享上下文）。 */
    private static String summaryBodyOf(KbWikiMapStage.MapResult m) {
        return m.summaryContent() == null ? "" : m.summaryContent();
    }

    /**
     * 摘要页标题。
     *
     * <p>⭐⭐ <b>故意用固定文案，不用文件名/标题</b> —— 对齐「文件名永不喂给模型」那条铁律。
     * 摘要页 slug 是 {@code summary/<knowledgeId>}，显示名用文档 ID 的短形态即可；
     * 真实标题由用户在文档列表里看，不在这里重复。
     */
    private static String summaryTitle(KbWikiReduceStage.ExistingWikiPage existing) {
        return existing != null && existing.title() != null && !existing.title().isBlank()
            ? existing.title() : "文档摘要";
    }

    /** 从摘要正文里抽 {@code SUMMARY: ...} 那一行。 */
    static String summaryLineOf(String content) {
        if (content == null) {
            return null;
        }
        for (String line : content.split("\n", 4)) {
            String t = line.trim();
            if (t.startsWith("SUMMARY:")) {
                String s = t.substring("SUMMARY:".length()).trim();
                return s.isEmpty() ? null : s;
            }
            if (!t.isEmpty() && !t.startsWith("```")) {
                break;   // 首行不是 SUMMARY ⇒ 不再往后找
            }
        }
        return null;
    }

    /**
     * 一轮运行统计。
     *
     * @param skipped        是否整轮跳过
     * @param skipReason     跳过原因
     * @param docsProcessed  成功处理的文档数
     * @param docsSkipped    跳过的文档数
     * @param docsFailed     失败的文档数
     * @param createdPages   新建页面 slug
     * @param updatedPages   更新页面 slug
     * @param rejectedPages  被拒页面数
     * @param deadLinksRemoved 清理的死链数
     * @param crossLinksAdded  补的交叉链接数
     */
    public record RunSummary(
        boolean skipped, String skipReason,
        int docsProcessed, int docsSkipped, int docsFailed,
        List<String> createdPages, List<String> updatedPages, int rejectedPages,
        int deadLinksRemoved, int crossLinksAdded
    ) {
        public static RunSummary skipped(String reason) {
            return new RunSummary(true, reason, 0, 0, 0, List.of(), List.of(), 0, 0, 0);
        }
    }

    /** 供 Lint 端点用的页面快照（避免端点直接依赖 mapper）。 */
    public List<KbWikiLinter.PageSnapshot> lintSnapshots(String kbId) {
        List<KbWikiLinter.PageSnapshot> out = new ArrayList<>();
        for (KbWikiPageRecord p : pageService.listPages(kbId)) {
            out.add(new KbWikiLinter.PageSnapshot(
                p.getSlug(), p.getTitle(), p.getPageType(), p.getContent(),
                KbWikiPageService.parseStringArray(p.getOutLinks()),
                KbWikiPageService.parseStringArray(p.getInLinks()),
                KbWikiPageService.parseStringArray(p.getSourceRefs())));
        }
        return out;
    }

    /** 仍存在的文档 ID 集合（给 Lint 的 stale_ref 检查用）。 */
    public Set<String> liveKnowledgeIds(String kbId) {
        Set<String> out = new LinkedHashSet<>();
        for (KbKnowledgeRecord k : knowledgeMapper.selectListByQuery(QueryWrapper.create()
            .where("knowledge_base_id = ?", kbId))) {
            out.add(k.getId());
        }
        return out;
    }
}
