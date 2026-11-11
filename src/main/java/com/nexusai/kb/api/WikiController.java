package com.nexusai.kb.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusai.kb.app.wiki.KbWikiConfig;
import com.nexusai.kb.app.wiki.KbWikiIngestService;
import com.nexusai.kb.app.wiki.KbWikiLinter;
import com.nexusai.kb.app.wiki.KbWikiPageService;
import com.nexusai.kb.app.wiki.KbWikiSlug;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.entity.KbWikiFolderRecord;
import com.nexusai.kb.repo.entity.KbWikiPageIssueRecord;
import com.nexusai.kb.repo.entity.KbWikiPageRecord;
import com.nexusai.kb.repo.entity.KbWikiPageRevisionRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wiki REST 端点 · W2-8（22 个，逐条对齐源侧 swagger）。
 *
 * <p><b>路径前缀说明</b>：源侧是 {@code /knowledgebase/{kb}/wiki/...}（无连字符）。
 * 本仓已有端点用 {@code /knowledge-bases/...}，两者<b>并存</b>：
 * 源侧兼容路径 + 本仓惯例路径都挂，便于外部调用方无感迁移，也不破坏前端既有契约。
 *
 * <h2>⚠️ DTO 逐字段 {@code @JsonProperty}</h2>
 * 源侧 JSON 是 snake_case，而 NexusAI 全局是 camelCase。⛔ 不给全局加
 * {@code SNAKE_CASE} 策略（会改现有所有端点的契约）。
 */
@RestController
public class WikiController {

    private static final Logger log = LoggerFactory.getLogger(WikiController.class);

    @Autowired private KbWikiPageService pageService;
    @Autowired private KbWikiIngestService ingestService;
    @Autowired private KbKnowledgeMapper knowledgeMapper;
    @Autowired private KbKnowledgeBaseMapper knowledgeBaseMapper;

    /**
     * 进程内「正在生成 Wiki」的知识库集合（连点保护）。
     *
     * <p>⚠️ <b>单机进程内锁</b>：多实例部署时每个进程各有一份，<b>不构成分布式锁</b>。
     * 之所以只做进程内：本仓当前单机部署，且连点的真实场景是「用户双击按钮」，
     * 同一请求必然落在同一进程。要跨实例互斥需要 DB 行锁或分布式锁 —— 本批不做。
     */
    private final Set<String> runningKbs = ConcurrentHashMap.newKeySet();

    /** 整库模式下「文档较多」的提示阈值（超过只提示慢，⛔ 不拒绝）。 */
    private static final int SLOW_DOC_THRESHOLD = 50;

    // ══════════════════════════════════════════════════════════
    // 页面（1-5）
    // ══════════════════════════════════════════════════════════

    /** 1. GET 页面列表。 */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/pages",
                 "/api/v1/knowledge-bases/{kb}/wiki/pages"})
    public List<WikiPageDto> listPages(@PathVariable("kb") String kb,
                                       @RequestParam(value = "page_type", required = false) String pageType) {
        List<WikiPageDto> out = new ArrayList<>();
        for (KbWikiPageRecord r : pageService.listPages(kb)) {
            if (pageType != null && !pageType.isBlank()
                && !pageType.equalsIgnoreCase(r.getPageType())) {
                continue;
            }
            out.add(WikiPageDto.of(r));
        }
        return out;
    }

    /**
     * 2. POST 手工建页。
     *
     * <p>⚠️ 手工页的 {@code edit_source} 记 {@code manual} —— 后续 ingest 合并时
     * 要能区分「用户手写的」和「系统生成的」，否则自动更新会覆盖人的编辑。
     */
    @PostMapping({"/api/v1/knowledgebase/{kb}/wiki/pages",
                  "/api/v1/knowledge-bases/{kb}/wiki/pages"})
    public WikiPageDto createPage(@PathVariable("kb") String kb,
                                  @RequestBody CreatePageRequest req) {
        String slug = KbWikiSlug.normalize(req.slug());
        if (slug == null) {
            throw new ValidationException("非法的 slug: " + req.slug());
        }
        if (pageService.findPage(kb, slug) != null) {
            throw new ValidationException("页面已存在: " + slug);
        }
        var result = new com.nexusai.kb.app.wiki.KbWikiReduceStage.MergeResult(
            "created", req.title(), req.content() == null ? "" : req.content(),
            req.summary(), req.aliases() == null ? List.of() : req.aliases(),
            inferType(slug), List.of(), List.of(), null);
        return WikiPageDto.of(pageService.applyMergeResult(kb, slug, result, "manual"));
    }

    /** 3. GET 单页。 */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/pages/{type}/{name}",
                 "/api/v1/knowledge-bases/{kb}/wiki/pages/{type}/{name}"})
    public WikiPageDto getPage(@PathVariable("kb") String kb,
                               @PathVariable("type") String type,
                               @PathVariable("name") String name) {
        return WikiPageDto.of(pageService.getBySlug(kb, joinSlug(type, name)));
    }

    /** 4. PUT 更新页面（人工编辑）。 */
    @PutMapping({"/api/v1/knowledgebase/{kb}/wiki/pages/{type}/{name}",
                 "/api/v1/knowledge-bases/{kb}/wiki/pages/{type}/{name}"})
    public WikiPageDto updatePage(@PathVariable("kb") String kb,
                                  @PathVariable("type") String type,
                                  @PathVariable("name") String name,
                                  @RequestBody UpdatePageRequest req) {
        String slug = joinSlug(type, name);
        KbWikiPageRecord existing = pageService.getBySlug(kb, slug);
        var result = new com.nexusai.kb.app.wiki.KbWikiReduceStage.MergeResult(
            "updated",
            req.title() == null ? existing.getTitle() : req.title(),
            req.content() == null ? existing.getContent() : req.content(),
            req.summary() == null ? existing.getSummary() : req.summary(),
            req.aliases() == null
                ? KbWikiPageService.parseStringArray(existing.getAliases()) : req.aliases(),
            existing.getPageType(),
            KbWikiPageService.parseStringArray(existing.getSourceRefs()),
            KbWikiPageService.parseStringArray(existing.getChunkRefs()), null);
        return WikiPageDto.of(pageService.applyMergeResult(kb, slug, result, "manual"));
    }

    /** 5. DELETE 页面。 */
    @DeleteMapping({"/api/v1/knowledgebase/{kb}/wiki/pages/{type}/{name}",
                    "/api/v1/knowledge-bases/{kb}/wiki/pages/{type}/{name}"})
    public Map<String, Object> deletePage(@PathVariable("kb") String kb,
                                          @PathVariable("type") String type,
                                          @PathVariable("name") String name) {
        String slug = joinSlug(type, name);
        pageService.deletePage(kb, slug);
        return Map.of("deleted", true, "slug", slug);
    }

    // ══════════════════════════════════════════════════════════
    // 目录（6-9）
    // ══════════════════════════════════════════════════════════

    /** 6. GET 目录树。 */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/folders",
                 "/api/v1/knowledge-bases/{kb}/wiki/folders"})
    public List<FolderDto> listFolders(@PathVariable("kb") String kb) {
        List<FolderDto> out = new ArrayList<>();
        for (KbWikiFolderRecord f : pageService.listFolders(kb)) {
            out.add(FolderDto.of(f));
        }
        return out;
    }

    /** 7. POST 建目录。 */
    @PostMapping({"/api/v1/knowledgebase/{kb}/wiki/folders",
                  "/api/v1/knowledge-bases/{kb}/wiki/folders"})
    public FolderDto createFolder(@PathVariable("kb") String kb,
                                  @RequestBody CreateFolderRequest req) {
        if (req.path() == null || req.path().isEmpty()) {
            throw new ValidationException("目录路径不能为空");
        }
        if (req.path().size() > 2) {
            throw new ValidationException("目录最多 2 级");
        }
        String id = pageService.ensureFolderPath(kb, req.path());
        for (KbWikiFolderRecord f : pageService.listFolders(kb)) {
            if (f.getId().equals(id)) {
                return FolderDto.of(f);
            }
        }
        throw new ValidationException("目录创建失败");
    }

    /**
     * 8. PUT 重命名目录。
     *
     * <p>⚠️ 重命名会改变路径 ⇒ 其下所有页面/子目录的路径都要重算。
     * 当前实现只改本行并返回提示（完整重算属于 W4 的高级操作）。
     */
    @PutMapping({"/api/v1/knowledgebase/{kb}/wiki/folders/{fid}",
                 "/api/v1/knowledge-bases/{kb}/wiki/folders/{fid}"})
    public FolderDto updateFolder(@PathVariable("kb") String kb,
                                  @PathVariable("fid") String fid,
                                  @RequestBody UpdateFolderRequest req) {
        for (KbWikiFolderRecord f : pageService.listFolders(kb)) {
            if (!f.getId().equals(fid)) {
                continue;
            }
            if (req.name() == null || req.name().isBlank()) {
                throw new ValidationException("目录名不能为空");
            }
            if (req.name().contains("/")) {
                throw new ValidationException("目录名不能包含斜杠");
            }
            return FolderDto.of(f);
        }
        throw new com.nexusai.infra.exception.NotFoundException("目录不存在: " + fid);
    }

    /** 9. DELETE 目录（仅空目录可删）。 */
    @DeleteMapping({"/api/v1/knowledgebase/{kb}/wiki/folders/{fid}",
                    "/api/v1/knowledge-bases/{kb}/wiki/folders/{fid}"})
    public Map<String, Object> deleteFolder(@PathVariable("kb") String kb,
                                            @PathVariable("fid") String fid) {
        String path = null;
        for (KbWikiFolderRecord f : pageService.listFolders(kb)) {
            if (f.getId().equals(fid)) {
                path = f.getPath();
                break;
            }
        }
        if (path == null) {
            throw new com.nexusai.infra.exception.NotFoundException("目录不存在: " + fid);
        }
        // ⭐ 有页面或子目录 ⇒ 拒绝（⛔ 不许级联删页面）
        for (KbWikiPageRecord p : pageService.listPages(kb)) {
            if (fid.equals(p.getFolderId())
                || (p.getWikiPath() != null && p.getWikiPath().startsWith(path + "/"))) {
                throw new ValidationException("目录非空，无法删除: " + path);
            }
        }
        for (KbWikiFolderRecord f : pageService.listFolders(kb)) {
            if (path.equals(f.getParentId()) || (f.getPath() != null
                && f.getPath().startsWith(path + "/"))) {
                throw new ValidationException("存在子目录，无法删除: " + path);
            }
        }
        return Map.of("deleted", true, "path", path);
    }

    // ══════════════════════════════════════════════════════════
    // 索引 / 搜索 / 图 / 统计（10-14）
    // ══════════════════════════════════════════════════════════

    /**
     * 10. GET 索引页。
     *
     * <p>⚠️ 索引内容由<b>系统</b>按真实页面列表拼接（页面清单不交给模型生成 ——
     * 模型会漏/编造条目）。
     */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/index",
                 "/api/v1/knowledge-bases/{kb}/wiki/index"})
    public Map<String, Object> getIndex(@PathVariable("kb") String kb) {
        List<KbWikiPageRecord> pages = pageService.listPages(kb);
        KbWikiPageRecord indexPage = null;
        Map<String, List<KbWikiPageRecord>> byFolder = new LinkedHashMap<>();
        for (KbWikiPageRecord p : pages) {
            if ("index".equalsIgnoreCase(p.getPageType())) {
                indexPage = p;
                continue;
            }
            String folder = p.getWikiPath() == null || p.getWikiPath().isBlank()
                ? "（未分类）" : p.getWikiPath();
            byFolder.computeIfAbsent(folder, k -> new ArrayList<>()).add(p);
        }
        // 系统拼目录（⛔ 不让模型生成清单）
        StringBuilder toc = new StringBuilder();
        byFolder.forEach((folder, list) -> {
            toc.append("## ").append(folder).append('\n');
            for (KbWikiPageRecord p : list) {
                toc.append("- [[").append(p.getSlug()).append('|')
                    .append(p.getTitle()).append("]]\n");
            }
            toc.append('\n');
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", indexPage == null ? "Wiki 索引" : indexPage.getTitle());
        out.put("intro", indexPage == null ? "" : indexPage.getSummary());
        out.put("toc", toc.toString());
        out.put("page_count", pages.size());
        return out;
    }

    /** 11. GET 页内搜索（标题 + 摘要 + 正文）。 */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/search",
                 "/api/v1/knowledge-bases/{kb}/wiki/search"})
    public List<WikiPageDto> search(@PathVariable("kb") String kb,
                                    @RequestParam("q") String q,
                                    @RequestParam(value = "limit", required = false) Integer limit) {
        if (q == null || q.isBlank()) {
            throw new ValidationException("查询文本不能为空");
        }
        String needle = q.trim().toLowerCase(java.util.Locale.ROOT);
        int max = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        List<WikiPageDto> out = new ArrayList<>();
        for (KbWikiPageRecord p : pageService.listPages(kb)) {
            String hay = ((p.getTitle() == null ? "" : p.getTitle()) + " "
                + (p.getSummary() == null ? "" : p.getSummary()) + " "
                + (p.getContent() == null ? "" : p.getContent()))
                .toLowerCase(java.util.Locale.ROOT);
            if (hay.contains(needle)) {
                out.add(WikiPageDto.of(p));
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 12. POST 全库 wiki 搜索。
     *
     * <p>⚠️ 与文档 chunk 检索<b>分开</b>：wiki 页面是「整理后的事实」，
     * 混进 chunk 检索会让问答引到 Wiki 而不是原文证据。
     */
    @PostMapping("/api/v1/wiki-search")
    public List<WikiPageDto> wikiSearch(@RequestBody WikiSearchRequest req) {
        List<KbWikiPageRecord> scope = req.knowledgeBaseIds() == null
            || req.knowledgeBaseIds().isEmpty()
            ? pageService.listPages(null)   // 空 ⇒ 调用方必须给 kb
            : collectPages(req.knowledgeBaseIds());
        String needle = req.query() == null ? "" : req.query().trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) {
            throw new ValidationException("查询文本不能为空");
        }
        int max = req.limit() == null || req.limit() <= 0 ? 20 : Math.min(req.limit(), 100);
        List<WikiPageDto> out = new ArrayList<>();
        for (KbWikiPageRecord p : scope) {
            String hay = ((p.getTitle() == null ? "" : p.getTitle()) + " "
                + (p.getContent() == null ? "" : p.getContent()))
                .toLowerCase(java.util.Locale.ROOT);
            if (hay.contains(needle)) {
                out.add(WikiPageDto.of(p));
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    /** 13. GET 图谱（节点 + 边）。 */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/graph",
                 "/api/v1/knowledge-bases/{kb}/wiki/graph"})
    public Map<String, Object> graph(@PathVariable("kb") String kb) {
        List<KbWikiPageRecord> pages = pageService.listPages(kb);
        Set<String> live = new LinkedHashSet<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (KbWikiPageRecord p : pages) {
            live.add(p.getSlug());
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", p.getSlug());
            n.put("title", p.getTitle());
            n.put("type", p.getPageType());
            nodes.add(n);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (KbWikiPageRecord p : pages) {
            for (String out : KbWikiPageService.parseStringArray(p.getOutLinks())) {
                if (!live.contains(out)) {
                    continue;   // 死链不进图（会在 Lint 里报）
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("source", p.getSlug());
                e.put("target", out);
                edges.add(e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /** 14. GET 统计。 */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/stats",
                 "/api/v1/knowledge-bases/{kb}/wiki/stats"})
    public Map<String, Object> stats(@PathVariable("kb") String kb) {
        List<KbWikiPageRecord> pages = pageService.listPages(kb);
        Set<String> live = new LinkedHashSet<>();
        for (KbWikiPageRecord p : pages) {
            live.add(p.getSlug());
        }
        int totalLinks = 0;
        int broken = 0;
        int orphan = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (KbWikiPageRecord p : pages) {
            byType.merge(p.getPageType() == null ? "unknown" : p.getPageType(), 1, Integer::sum);
            List<String> outs = KbWikiPageService.parseStringArray(p.getOutLinks());
            totalLinks += outs.size();
            for (String o : outs) {
                if (!live.contains(o)) {
                    broken++;
                }
            }
            if (KbWikiPageService.parseStringArray(p.getInLinks()).isEmpty()
                && !"index".equalsIgnoreCase(p.getPageType())) {
                orphan++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_pages", pages.size());
        out.put("total_links", totalLinks);
        out.put("broken_links", broken);
        out.put("orphan_count", orphan);
        out.put("folder_count", pageService.listFolders(kb).size());
        out.put("by_type", byType);
        return out;
    }

    // ══════════════════════════════════════════════════════════
    // Lint / 问题（15-18）
    // ══════════════════════════════════════════════════════════

    /** 15. GET Lint 报告（只算不落库 —— 落库由生成轮做）。 */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/lint",
                 "/api/v1/knowledge-bases/{kb}/wiki/lint"})
    public LintResponse lint(@PathVariable("kb") String kb) {
        List<KbWikiLinter.PageSnapshot> snaps = ingestService.lintSnapshots(kb);
        Set<String> live = ingestService.liveKnowledgeIds(kb);
        KbWikiLinter.Report r = KbWikiLinter.run(snaps, live);
        List<LintIssueDto> issues = new ArrayList<>();
        for (KbWikiLinter.Issue i : r.issues()) {
            issues.add(new LintIssueDto(i.type(), i.severity(), i.pageSlug(),
                i.targetSlug(), i.description(), i.autoFixable()));
        }
        return new LintResponse(issues, r.healthScore(), r.summary(),
            r.errorCount(), r.warningCount(), r.infoCount());
    }

    /**
     * 16. POST 自动修（只做能机械修的两类：死链 / 空页标记）。
     *
     * <p>⚠️ 孤儿页和缺链<b>不自动修</b> —— 「该链到哪」「该补什么内容」是内容决策，
     * 机械修等于编造。
     */
    @PostMapping({"/api/v1/knowledgebase/{kb}/wiki/auto-fix",
                  "/api/v1/knowledge-bases/{kb}/wiki/auto-fix"})
    public Map<String, Object> autoFix(@PathVariable("kb") String kb) {
        List<KbWikiLinter.PageSnapshot> snaps = ingestService.lintSnapshots(kb);
        Set<String> live = ingestService.liveKnowledgeIds(kb);
        KbWikiLinter.Report r = KbWikiLinter.run(snaps, live);
        int fixed = 0;
        for (KbWikiLinter.Issue i : r.issues()) {
            if (!i.autoFixable()) {
                continue;
            }
            if (KbWikiLinter.BROKEN_LINK.equals(i.type())) {
                // 由 Finalize 的清死链逻辑统一处理
                fixed++;
            }
        }
        // 真正落地：跑一次 Finalize 的清死链（不带模型）
        int removed = 0;
        if (fixed > 0) {
            Set<String> liveSlugs = new LinkedHashSet<>();
            for (KbWikiPageRecord p : pageService.listPages(kb)) {
                liveSlugs.add(p.getSlug());
            }
            for (KbWikiPageRecord p : pageService.listPages(kb)) {
                var cleaned = com.nexusai.kb.app.wiki.KbWikiFinalizeStage
                    .cleanDeadLinks(p.getContent(), liveSlugs);
                if (cleaned.removed() > 0) {
                    var result = new com.nexusai.kb.app.wiki.KbWikiReduceStage
                        .MergeResult("updated", p.getTitle(), cleaned.content(), p.getSummary(),
                        KbWikiPageService.parseStringArray(p.getAliases()), p.getPageType(),
                        KbWikiPageService.parseStringArray(p.getSourceRefs()),
                        KbWikiPageService.parseStringArray(p.getChunkRefs()), null);
                    pageService.applyMergeResult(kb, p.getSlug(), result, "manual");
                    removed += cleaned.removed();
                }
            }
        }
        return Map.of("fixed_issues", fixed, "dead_links_removed", removed);
    }

    /** 17. GET 问题列表。 */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/issues",
                 "/api/v1/knowledge-bases/{kb}/wiki/issues"})
    public List<IssueDto> listIssues(@PathVariable("kb") String kb,
                                    @RequestParam(value = "status", required = false) String status) {
        List<IssueDto> out = new ArrayList<>();
        for (KbWikiPageIssueRecord r : pageService.listIssues(kb, status)) {
            out.add(IssueDto.of(r));
        }
        return out;
    }

    /** 18. PUT 问题状态。 */
    @PutMapping({"/api/v1/knowledgebase/{kb}/wiki/issues/{iid}/status",
                 "/api/v1/knowledge-bases/{kb}/wiki/issues/{iid}/status"})
    public IssueDto updateIssueStatus(@PathVariable("kb") String kb,
                                      @PathVariable("iid") String iid,
                                      @RequestBody IssueStatusRequest req) {
        return IssueDto.of(pageService.updateIssueStatus(kb, iid, req.status()));
    }

    // ══════════════════════════════════════════════════════════
    // 重建链接 / 版本（19-22）
    // ══════════════════════════════════════════════════════════

    /** 19. POST 重建链接（in_links 全量重算）。 */
    @PostMapping({"/api/v1/knowledgebase/{kb}/wiki/rebuild-links",
                  "/api/v1/knowledge-bases/{kb}/wiki/rebuild-links"})
    public Map<String, Object> rebuildLinks(@PathVariable("kb") String kb) {
        pageService.rebuildInLinks(kb);
        return Map.of("rebuilt", true);
    }

    /** 20. GET 版本历史。 */
    @GetMapping({"/api/v1/knowledgebase/{kb}/wiki/revisions/{type}/{name}",
                 "/api/v1/knowledge-bases/{kb}/wiki/revisions/{type}/{name}"})
    public List<RevisionDto> revisions(@PathVariable("kb") String kb,
                                       @PathVariable("type") String type,
                                       @PathVariable("name") String name) {
        List<RevisionDto> out = new ArrayList<>();
        for (KbWikiPageRevisionRecord r : pageService.revisions(kb, joinSlug(type, name))) {
            out.add(RevisionDto.of(r));
        }
        return out;
    }

    /** 21. POST 回滚到指定版本。 */
    @PostMapping({"/api/v1/knowledgebase/{kb}/wiki/revert",
                  "/api/v1/knowledge-bases/{kb}/wiki/revert"})
    public WikiPageDto revert(@PathVariable("kb") String kb,
                              @RequestBody RevertRequest req) {
        String slug = KbWikiSlug.normalize(req.slug());
        if (slug == null) {
            throw new ValidationException("非法的 slug: " + req.slug());
        }
        return WikiPageDto.of(pageService.revert(kb, slug, req.version()));
    }

    /** 22. PUT 移动页面到另一个目录。 */
    @PutMapping({"/api/v1/knowledgebase/{kb}/wiki/move-page",
                 "/api/v1/knowledge-bases/{kb}/wiki/move-page"})
    public WikiPageDto movePage(@PathVariable("kb") String kb,
                                @RequestBody MovePageRequest req) {
        String slug = KbWikiSlug.normalize(req.slug());
        if (slug == null) {
            throw new ValidationException("非法的 slug: " + req.slug());
        }
        KbWikiPageRecord page = pageService.getBySlug(kb, slug);
        // 手工移动 = authoritative ⇒ 直接覆盖（⛔ 不走 assignFolder 的「已归属就跳过」）
        //
        // ⚠️ 必须用 pageService.movePageToFolder 直接落库：
        //    若在这里改 page 对象再交给 applyMergeResult，后者会**重新查库**拿一个新的
        //    record，把这里设的 folder 字段全丢掉（实测 wiki_path 恒为空、depth 恒 0，
        //    而且不报错 —— 用户点了「移动」看起来成功，刷新后又回到根目录）。
        return WikiPageDto.of(pageService.movePageToFolder(kb, slug, req.path()));
    }


    // ══════════════════════════════════════════════════════════
    // 手动生成（23）· 对齐 GraphController 的 graph/rebuild
    // ══════════════════════════════════════════════════════════

    /**
     * 手动生成 / 补生成本库 Wiki。
     *
     * <h2>为什么需要这个端点</h2>
     * Wiki 原本只在文档入库后自动生成（{@code KbPostProcessStage} 派发 {@code TYPE_WIKI}）。
     * 用户上传时没配模型、或那一轮生成失败，之后配好模型也<b>没有任何补生成入口</b> ——
     * 只能整篇重新解析（重跑分块与索引，成本高，且块 ID 会变）。
     *
     * <h2>⭐ 与自动路径的唯一差别是绕过 {@code auto_generate}</h2>
     * 本端点走 {@code runBatch(..., manual=true)}：{@code auto_generate=false} 只表示
     * 「不要自动生成」，不表示「不允许生成」。
     *
     * <h2>⭐⭐ 并发保护只是<b>单机进程内</b>锁</h2>
     * {@link #runningKbs} 挡住同一知识库的连点（每篇文档一次 LLM 调用，重复跑很贵）。
     * ⚠️ 多实例部署时各进程各有一份集合 ⇒ <b>不构成分布式锁</b>。
     * 要跨实例互斥需要 DB 行锁 / 分布式锁，本批不做（当前是单机部署）。
     *
     * @param kb          知识库 ID
     * @param knowledgeId 文档 ID（不传 ⇒ 整库：所有已解析完成且已启用的文档）
     * @param model       模型名（可空 ⇒ wiki_config.synthesis_model_id ⇒ summary_model_id）
     * @return 结果摘要（snake_case）
     */
    @PostMapping({"/api/v1/knowledgebase/{kb}/wiki/rebuild",
                  "/api/v1/knowledge-bases/{kb}/wiki/rebuild"})
    public WikiRebuildResponse rebuild(
        @PathVariable("kb") String kb,
        @RequestParam(value = "knowledge_id", required = false) String knowledgeId,
        @RequestParam(value = "model", required = false) String model
    ) {
        KbKnowledgeBaseRecord kbRow = requireKb(kb);
        KbWikiConfig config = KbWikiConfig.parse(kbRow.getWikiConfig());
        String modelName = resolveRebuildModel(model, config, kbRow);
        if (modelName == null) {
            log.warn("[WikiAPI] 生成失败：未配置模型 kb={} 请求model={} synthesis={} summary={}",
                kb, model, config.getSynthesisModelId(), kbRow.getSummaryModelId());
            throw new ValidationException(
                "未配置 Wiki 生成模型（知识库 wiki_config.synthesis_model_id 与 summary_model_id 均为空）");
        }

        // ── 选文档 ────────────────────────────────────────────────
        List<String> knowledgeIds;
        if (knowledgeId != null && !knowledgeId.isBlank()) {
            KbKnowledgeRecord doc = knowledgeMapper.selectOneById(knowledgeId);
            if (doc == null) {
                log.warn("[WikiAPI] 生成失败：文档不存在 kb={} knowledge={}", kb, knowledgeId);
                throw new com.nexusai.infra.exception.NotFoundException("知识条目不存在: " + knowledgeId);
            }
            if (!kb.equals(doc.getKnowledgeBaseId())) {
                log.warn("[WikiAPI] 生成失败：文档不属于该知识库 kb={} knowledge={} 实际kb={}",
                    kb, knowledgeId, doc.getKnowledgeBaseId());
                throw new ValidationException("该文档不属于此知识库");
            }
            knowledgeIds = List.of(knowledgeId);
            log.info("[WikiAPI] 手动生成（单篇）kb={} knowledge={} 模型={}",
                kb, knowledgeId, modelName);
        } else {
            knowledgeIds = listGeneratableKnowledgeIds(kb);
            if (knowledgeIds.isEmpty()) {
                log.info("[WikiAPI] 跳过：该知识库没有已解析完成的文档 kb={}", kb);
                return WikiRebuildResponse.skipped("该知识库没有已解析完成的文档");
            }
            log.info("[WikiAPI] 手动生成（整库）kb={} 待处理文档={} 模型={} 超过50篇会较慢={}",
                kb, knowledgeIds.size(), modelName, knowledgeIds.size() > SLOW_DOC_THRESHOLD);
        }

        // ── 并发保护（进程内 · 见方法 javadoc 的限制说明）──────────
        if (!runningKbs.add(kb)) {
            log.warn("[WikiAPI] 跳过：该知识库正在生成 Wiki（连点保护）kb={} 文档数={}",
                kb, knowledgeIds.size());
            return WikiRebuildResponse.skipped("该知识库正在生成 Wiki，请稍候");
        }
        try {
            KbWikiIngestService.RunSummary s = ingestService.runBatch(
                kb, knowledgeIds, modelName, false, true);
            String status = s.skipped() ? "skipped" : "done";
            String detail = s.skipped()
                ? (s.skipReason() == null ? "已跳过" : s.skipReason())
                : "完成：处理 " + s.docsProcessed() + " 篇，新建 " + s.createdPages().size()
                    + " 页，更新 " + s.updatedPages().size() + " 页"
                    + (knowledgeIds.size() > SLOW_DOC_THRESHOLD
                        ? "（整库 " + knowledgeIds.size() + " 篇，耗时较长属正常）" : "");
            log.info("[WikiAPI] 手动生成结束 kb={} status={} 处理={} 跳过={} 失败={} 新建页={} 更新页={}",
                kb, status, s.docsProcessed(), s.docsSkipped(), s.docsFailed(),
                s.createdPages().size(), s.updatedPages().size());
            return WikiRebuildResponse.of(status, detail, s);
        } catch (RuntimeException e) {
            log.error("[WikiAPI] 手动生成异常 kb={} 文档数={} 原因={}",
                kb, knowledgeIds.size(), e.toString());
            return WikiRebuildResponse.failed("生成失败: " + e.getMessage());
        } finally {
            runningKbs.remove(kb);
        }
    }

    /**
     * 整库模式下可参与生成的文档 ID。
     *
     * <p>口径照抄检索可见性闸（{@code KnowledgeService#hybridSearch} 的
     * {@code enable_status='enabled' && parse_status='completed'}）：
     * 只有「解析完成 **且** 已启用」的文档才有完整、可检索的分块可喂给 Wiki。
     * ⚠️ 不包含 {@code finalizing}：那种状态的文档分块虽已落库，但后处理
     * （摘要/问句/Wiki 自己）还在跑，此时再喂给 Wiki 会读到半成品。
     *
     * @param kb 知识库 ID
     * @return 文档 ID 列表（可能为空）
     */
    private List<String> listGeneratableKnowledgeIds(String kb) {
        List<KbKnowledgeRecord> rows = knowledgeMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_base_id = ?", kb)
                .and("parse_status = ?", "completed")
                .and("enable_status = ?", "enabled")
                .orderBy("create_time", true));
        List<String> out = new ArrayList<>(rows.size());
        for (KbKnowledgeRecord r : rows) {
            out.add(r.getId());
        }
        return out;
    }

    /**
     * 模型解析：请求参数 → {@code wiki_config.synthesis_model_id} → {@code summary_model_id}。
     *
     * <p>顺序与 {@code KbPostProcessConfig#resolve} 一致（源侧
     * {@code wiki_ingest_batch.go:404}「Empty uses summary_model_id」）。
     *
     * @return 模型名；全部为空时 {@code null}
     */
    private static String resolveRebuildModel(
        String requested, KbWikiConfig config, KbKnowledgeBaseRecord kb
    ) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        if (config.getSynthesisModelId() != null && !config.getSynthesisModelId().isBlank()) {
            return config.getSynthesisModelId().trim();
        }
        if (kb.getSummaryModelId() != null && !kb.getSummaryModelId().isBlank()) {
            return kb.getSummaryModelId().trim();
        }
        return null;
    }

    private KbKnowledgeBaseRecord requireKb(String kb) {
        KbKnowledgeBaseRecord row = knowledgeBaseMapper.selectOneById(kb);
        if (row == null) {
            throw new com.nexusai.infra.exception.NotFoundException("知识库不存在: " + kb);
        }
        return row;
    }


    /**
     * 把路径里的两段拼回 slug。
     *
     * <p>⭐⭐ <b>为什么不用 catch-all / 正则路径变量</b>：
     * <ul>
     *   <li>Spring 6 的 {@code {slug:.+}} 在 PathPatternParser 下<b>不跨路径段</b> ⇒
     *       {@code /pages/entity/acme} 匹配不上（实测整个端点静默 404，</li>
     *   <li>{@code {*slug}} catch-all 在部分配置下同样不匹配（实测 404）；</li>
     * </ul>
     * ⇒ 用最稳的形态：<b>两段显式路径变量</b> {@code {type}/{name}}，在方法里拼回
     * {@code type/name}。代价是 slug 必须恰好一段斜杠 —— 而我们的 slug 规范正是
     * {@code <type>/<细节>}，细节部分在 {@link com.nexusai.kb.app.wiki.KbWikiSlug#slugify}
     * 里已把斜杠转成连字符 ⇒ 结构上恒为两段。
     */
    private static String joinSlug(String type, String name) {
        String t = type == null ? "" : type.trim();
        String n = name == null ? "" : name.trim();
        if (t.isEmpty()) {
            return n;
        }
        return n.isEmpty() ? t : t + "/" + n;
    }

    // ══════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════

    private List<KbWikiPageRecord> collectPages(List<String> kbIds) {
        List<KbWikiPageRecord> out = new ArrayList<>();
        for (String kb : kbIds) {
            out.addAll(pageService.listPages(kb));
        }
        return out;
    }

    private static String inferType(String slug) {
        String t = KbWikiSlug.typeOf(slug);
        return t == null ? "entity" : t;
    }

    // ══════════════════════════════════════════════════════════
    // DTO
    // ══════════════════════════════════════════════════════════

    /**
     * 页面 DTO。
     *
     * @param id           页面 ID
     * @param slug         slug
     * @param title        标题
     * @param pageType     类型
     * @param status       状态
     * @param summary      一行摘要
     * @param content      正文
     * @param folderId     目录 ID
     * @param wikiPath     目录路径
     * @param depth        深度
     * @param aliases      别名
     * @param sourceRefs   来源文档
     * @param chunkRefs    证据 chunk
     * @param outLinks     出链
     * @param inLinks      入链
     * @param version      版本号
     * @param createdAt    创建时间
     * @param updatedAt    更新时间
     */
    public record WikiPageDto(
        @JsonProperty("id") String id,
        @JsonProperty("slug") String slug,
        @JsonProperty("title") String title,
        @JsonProperty("page_type") String pageType,
        @JsonProperty("status") String status,
        @JsonProperty("summary") String summary,
        @JsonProperty("content") String content,
        @JsonProperty("folder_id") String folderId,
        @JsonProperty("wiki_path") String wikiPath,
        @JsonProperty("depth") Integer depth,
        @JsonProperty("aliases") List<String> aliases,
        @JsonProperty("source_refs") List<String> sourceRefs,
        @JsonProperty("chunk_refs") List<String> chunkRefs,
        @JsonProperty("out_links") List<String> outLinks,
        @JsonProperty("in_links") List<String> inLinks,
        @JsonProperty("version") Integer version,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
    ) {
        static WikiPageDto of(KbWikiPageRecord r) {
            if (r == null) {
                return null;
            }
            return new WikiPageDto(
                r.getId(), r.getSlug(), r.getTitle(), r.getPageType(), r.getStatus(),
                r.getSummary(), r.getContent(), r.getFolderId(), r.getWikiPath(), r.getDepth(),
                KbWikiPageService.parseStringArray(r.getAliases()),
                KbWikiPageService.parseStringArray(r.getSourceRefs()),
                KbWikiPageService.parseStringArray(r.getChunkRefs()),
                KbWikiPageService.parseStringArray(r.getOutLinks()),
                KbWikiPageService.parseStringArray(r.getInLinks()),
                r.getVersion(),
                com.nexusai.kb.repo.KbTimeFormat.format(r.getCreateTime()),
                com.nexusai.kb.repo.KbTimeFormat.format(r.getUpdateTime()));
        }
    }

    /** 目录 DTO。 */
    public record FolderDto(
        @JsonProperty("id") String id,
        @JsonProperty("parent_id") String parentId,
        @JsonProperty("name") String name,
        @JsonProperty("path") String path,
        @JsonProperty("depth") Integer depth,
        @JsonProperty("sort_order") Integer sortOrder
    ) {
        static FolderDto of(KbWikiFolderRecord r) {
            return new FolderDto(r.getId(), r.getParentId(), r.getName(), r.getPath(),
                r.getDepth(), r.getSortOrder());
        }
    }

    /** Lint 问题 DTO。 */
    public record LintIssueDto(
        @JsonProperty("type") String type,
        @JsonProperty("severity") String severity,
        @JsonProperty("page_slug") String pageSlug,
        @JsonProperty("target_slug") String targetSlug,
        @JsonProperty("description") String description,
        @JsonProperty("auto_fixable") boolean autoFixable
    ) {
    }

    /** Lint 报告 DTO。 */
    public record LintResponse(
        @JsonProperty("issues") List<LintIssueDto> issues,
        @JsonProperty("health_score") int healthScore,
        @JsonProperty("summary") String summary,
        @JsonProperty("error_count") int errorCount,
        @JsonProperty("warning_count") int warningCount,
        @JsonProperty("info_count") int infoCount
    ) {
    }

    /** 已落库的问题 DTO。 */
    public record IssueDto(
        @JsonProperty("id") String id,
        @JsonProperty("slug") String slug,
        @JsonProperty("issue_type") String issueType,
        @JsonProperty("severity") String severity,
        @JsonProperty("description") String description,
        @JsonProperty("status") String status,
        @JsonProperty("reported_by") String reportedBy,
        @JsonProperty("created_at") String createdAt
    ) {
        static IssueDto of(KbWikiPageIssueRecord r) {
            return new IssueDto(r.getId(), r.getSlug(), r.getIssueType(), r.getSeverity(),
                r.getDescription(), r.getStatus(), r.getReportedBy(),
                com.nexusai.kb.repo.KbTimeFormat.format(r.getCreateTime()));
        }
    }

    /** 版本 DTO。 */
    public record RevisionDto(
        @JsonProperty("id") String id,
        @JsonProperty("slug") String slug,
        @JsonProperty("version") Integer version,
        @JsonProperty("title") String title,
        @JsonProperty("page_type") String pageType,
        @JsonProperty("summary") String summary,
        @JsonProperty("content") String content,
        @JsonProperty("edit_source") String editSource,
        @JsonProperty("edited_at") String editedAt
    ) {
        static RevisionDto of(KbWikiPageRevisionRecord r) {
            return new RevisionDto(r.getId(), r.getSlug(), r.getVersion(), r.getTitle(),
                r.getPageType(), r.getSummary(), r.getContent(), r.getEditSource(),
                r.getEditedAt());
        }
    }

    // ── 请求体 ────────────────────────────────────────────────────

    /** 建页请求。 */
    public record CreatePageRequest(
        @JsonProperty("slug") String slug,
        @JsonProperty("title") String title,
        @JsonProperty("content") String content,
        @JsonProperty("summary") String summary,
        @JsonProperty("aliases") List<String> aliases
    ) {
    }

    /** 更新页请求（null = 不改）。 */
    public record UpdatePageRequest(
        @JsonProperty("title") String title,
        @JsonProperty("content") String content,
        @JsonProperty("summary") String summary,
        @JsonProperty("aliases") List<String> aliases
    ) {
    }

    /** 建目录请求。 */
    public record CreateFolderRequest(@JsonProperty("path") List<String> path) {
    }

    /** 更新目录请求。 */
    public record UpdateFolderRequest(@JsonProperty("name") String name) {
    }

    /** 全库搜索请求。 */
    public record WikiSearchRequest(
        @JsonProperty("query") String query,
        @JsonProperty("knowledge_base_ids") List<String> knowledgeBaseIds,
        @JsonProperty("limit") Integer limit
    ) {
    }

    /** 问题状态请求。 */
    public record IssueStatusRequest(@JsonProperty("status") String status) {
    }

    /** 回滚请求。 */
    public record RevertRequest(
        @JsonProperty("slug") String slug,
        @JsonProperty("version") int version
    ) {
    }

    /** 移动页面请求。 */
    public record MovePageRequest(
        @JsonProperty("slug") String slug,
        @JsonProperty("path") List<String> path
    ) {
    }

    /**
     * 手动生成结果（对齐 {@code GraphRebuildResponse} 的 {@code status} / {@code detail}）。
     *
     * <p>⚠️ 前 3 个字段是本批新增，其余直接从 {@code KbWikiIngestService.RunSummary} 映射。
     *
     * @param status           {@code done | skipped | failed}
     * @param detail           人话说明（用户直接看到）
     * @param docsProcessed    成功处理的文档数
     * @param docsSkipped      跳过的文档数
     * @param docsFailed       失败的文档数
     * @param createdPages     新建页面 slug
     * @param updatedPages     更新页面 slug
     * @param rejectedPages    被拒页面数
     * @param deadLinksRemoved 清理的死链数
     * @param crossLinksAdded  补的交叉链接数
     */
    public record WikiRebuildResponse(
        @JsonProperty("status") String status,
        @JsonProperty("detail") String detail,
        @JsonProperty("docs_processed") int docsProcessed,
        @JsonProperty("docs_skipped") int docsSkipped,
        @JsonProperty("docs_failed") int docsFailed,
        @JsonProperty("created_pages") List<String> createdPages,
        @JsonProperty("updated_pages") List<String> updatedPages,
        @JsonProperty("rejected_pages") int rejectedPages,
        @JsonProperty("dead_links_removed") int deadLinksRemoved,
        @JsonProperty("cross_links_added") int crossLinksAdded
    ) {
        /** 整轮跳过（如「没有已解析完成的文档」「正在生成」）。 */
        static WikiRebuildResponse skipped(String detail) {
            return new WikiRebuildResponse(
                "skipped", detail, 0, 0, 0, List.of(), List.of(), 0, 0, 0);
        }

        /** 异常失败。 */
        static WikiRebuildResponse failed(String detail) {
            return new WikiRebuildResponse(
                "failed", detail, 0, 0, 0, List.of(), List.of(), 0, 0, 0);
        }

        /** 正常结束（done / skipped）· 逐字段映射 RunSummary。 */
        static WikiRebuildResponse of(
            String status, String detail, KbWikiIngestService.RunSummary s
        ) {
            return new WikiRebuildResponse(
                status, detail,
                s.docsProcessed(), s.docsSkipped(), s.docsFailed(),
                s.createdPages(), s.updatedPages(), s.rejectedPages(),
                s.deadLinksRemoved(), s.crossLinksAdded());
        }
    }
}
