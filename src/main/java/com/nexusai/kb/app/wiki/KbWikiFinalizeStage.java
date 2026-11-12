package com.nexusai.kb.app.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.UsageCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wiki Finalize 阶段（KB 级）· W2-6。
 *
 * <p><b>源侧对照</b>：WeKnora {@code wiki_ingest.go:1063 ProcessWikiFinalize}。
 *
 * <h2>五个步骤（顺序不能换）</h2>
 * <ol>
 *   <li><b>auto_generate 守卫</b>：关掉 ⇒ 只排空队列，⛔ 不做任何变更；</li>
 *   <li><b>索引页介绍</b>（首次 / 增量）；</li>
 *   <li><b>清死链</b>：把指向不存在页面的 {@code [[slug|名]]} 还原成纯文本；</li>
 *   <li><b>补交叉链接</b>：正文提到但没链的实体名包成链接；</li>
 *   <li><b>剪空目录</b>：有 ingest 在跑则<b>延后</b>（否则会剪掉正在被写入的目录）。</li>
 * </ol>
 *
 * <h2>⭐⭐ 为什么 3 必须在 4 之前</h2>
 * 补交叉链接时用的是「现存页面」白名单。若先补链再清死链，
 * 刚补的链可能指向一个即将被删的页面（同一轮里删了页）⇒ 白补一次，还多一条死链。
 * <p>先清后补 ⇒ 补链用的是清理后的真实存活集。
 */
@Component
public class KbWikiFinalizeStage {

    private static final Logger log = LoggerFactory.getLogger(KbWikiFinalizeStage.class);

    /** 索引页固定 slug。 */
    public static final String INDEX_SLUG = "index/root";

    private final LlmSideCallClient sideCallClient;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    public KbWikiFinalizeStage(LlmSideCallClient sideCallClient) {
        this.sideCallClient = sideCallClient;
    }

    // ══════════════════════════════════════════════════════════
    // 数据载体
    // ══════════════════════════════════════════════════════════

    /**
     * 页面概览（Finalize 的输入快照）。
     *
     * @param slug     slug
     * @param title    标题
     * @param pageType 类型
     * @param content  正文
     * @param summary  一行摘要
     */
    public record PageView(String slug, String title, String pageType, String content, String summary) {
    }

    /**
     * 一次 Finalize 的结果。
     *
     * @param skipped          是否跳过（auto_generate 关 / 无页面）
     * @param skipReason       跳过原因
     * @param indexTitle       索引页标题（可能为空）
     * @param indexIntro       索引页介绍（可能为空）
     * @param pagesToUpdate    slug → 新正文（只含真正改动的页面）
     * @param deadLinksRemoved 清掉的死链数
     * @param crossLinksAdded  补的交叉链接数
     * @param pruneDeferred    剪空目录是否被延后
     */
    public record Result(
        boolean skipped, String skipReason,
        String indexTitle, String indexIntro,
        Map<String, String> pagesToUpdate,
        int deadLinksRemoved, int crossLinksAdded, boolean pruneDeferred
    ) {
        public static Result skipped(String reason) {
            return new Result(true, reason, null, null, Map.of(), 0, 0, false);
        }
    }

    // ══════════════════════════════════════════════════════════
    // 主流程
    // ══════════════════════════════════════════════════════════

    /**
     * 跑一次 Finalize。
     *
     * @param pages             当前全部页面
     * @param config            Wiki 配置（检查 autoGenerate）
     * @param changes           本轮变更描述（document_added / document_removed）
     * @param ingestRunning     是否还有 ingest 在跑（决定剪枝是否延后）
     * @param modelName         模型名（null ⇒ 跳过索引页介绍，但仍清死链）
     * @return 结果
     */
    public Result finalize(
        List<PageView> pages, KbWikiConfig config, List<String> changes,
        boolean ingestRunning, String modelName
    ) {
        // ── ① auto_generate 守卫 ────────────────────────────────
        if (config != null && !config.isAutoGenerateEnabled()) {
            log.info("[Wiki] Finalize 跳过：auto_generate 已关闭（只排空队列，不做变更）");
            return Result.skipped("auto_generate 已关闭");
        }
        if (pages == null || pages.isEmpty()) {
            log.info("[Wiki] Finalize 跳过：没有页面");
            return Result.skipped("没有页面");
        }

        Set<String> liveSlugs = new LinkedHashSet<>();
        Map<String, PageView> bySlug = new LinkedHashMap<>();
        for (PageView p : pages) {
            if (p.slug() != null && !p.slug().isBlank()) {
                liveSlugs.add(p.slug());
                bySlug.put(p.slug(), p);
            }
        }

        // ── ② 索引页介绍 ────────────────────────────────────────
        String indexTitle = null;
        String indexIntro = null;
        PageView existingIndex = bySlug.get(INDEX_SLUG);
        if (modelName != null && !modelName.isBlank()) {
            IndexIntro intro = generateIndexIntro(
                pages, existingIndex == null ? null : existingIndex.content(),
                changes, modelName);
            indexTitle = intro.title();
            indexIntro = intro.intro();
        }

        // ── ③ 清死链（必须在补链之前）────────────────────────────
        int deadRemoved = 0;
        Map<String, String> updated = new LinkedHashMap<>();
        for (PageView p : pages) {
            if (isIndex(p.pageType())) {
                continue;   // 索引页由系统重建，不参与清理
            }
            CleanResult c = cleanDeadLinks(p.content(), liveSlugs);
            if (c.removed() > 0) {
                updated.put(p.slug(), c.content());
                deadRemoved += c.removed();
            }
        }

        // ── ④ 补交叉链接 ────────────────────────────────────────
        List<KbWikiLinkifier.LinkRef> refs = buildLinkRefs(pages);
        int added = 0;
        for (PageView p : pages) {
            if (isIndex(p.pageType())) {
                continue;
            }
            String base = updated.getOrDefault(p.slug(), p.content());
            var r = KbWikiLinkifier.linkify(base, refs, p.slug());
            if (r.changed()) {
                // 数一下新增了几个 [[（补链数）
                added += countWikiLinks(r.content()) - countWikiLinks(base);
                updated.put(p.slug(), r.content());
            }
        }

        // ── ⑤ 剪枝（有 ingest 在跑则延后）──────────────────────
        boolean pruneDeferred = ingestRunning;
        if (pruneDeferred) {
            log.info("[Wiki] 剪空目录已延后：仍有 ingest 在跑（避免剪掉正在被写入的目录）");
        }

        if (log.isInfoEnabled()) {
            log.info("[Wiki] Finalize 完成 页面数={} 清死链={} 补链={} 更新页={}",
                pages.size(), deadRemoved, added, updated.size());
        }
        return new Result(false, null, indexTitle, indexIntro, updated,
            deadRemoved, added, pruneDeferred);
    }

    // ══════════════════════════════════════════════════════════
    // ③ 清死链
    // ══════════════════════════════════════════════════════════

    /**
     * 清死链结果。
     *
     * <p>⚠️ 包级可见（不是 private）：这是<b>可单独验证的纯函数输出</b>。
     * 做成 private 就只能通过整轮 finalize 间接断言，症状会变成
     * 「页面正文里有/没有某个词」，定位成本高得多。
     */
    public record CleanResult(String content, int removed) {
    }

    /**
     * 把指向不存在页面的 {@code [[slug|名]]} 还原成纯文本「名」。
     *
     * <p>⚠️ 保留显示名而不是删掉整段 —— 死链的<b>文字内容通常是对的</b>，
     * 只是目标页没了。删文字会丢事实。
     *
     * @param content   正文
     * @param liveSlugs 现存 slug 集
     * @return 清理结果
     */
    public static CleanResult cleanDeadLinks(String content, Set<String> liveSlugs) {
        if (content == null || content.isEmpty()) {
            return new CleanResult("", 0);
        }
        StringBuilder sb = new StringBuilder(content.length());
        int removed = 0;
        int i = 0;
        while (i < content.length()) {
            int start = content.indexOf("[[", i);
            if (start < 0) {
                sb.append(content, i, content.length());
                break;
            }
            sb.append(content, i, start);
            int close = content.indexOf("]]", start + 2);
            if (close < 0) {
                sb.append(content.substring(start));
                break;
            }
            String inner = content.substring(start + 2, close);
            String slug = KbWikiLinkifier.extractWikiSlug(inner);
            if (slug.isEmpty()) {
                sb.append(content, start, close + 2);
                i = close + 2;
                continue;
            }
            if (liveSlugs.contains(slug)) {
                sb.append(content, start, close + 2);   // 活着，原样保留
            } else {
                // 死链 ⇒ 换成显示名（[[slug|名]] → 名；[[slug]] → slug 的末段）
                int pipe = inner.indexOf('|');
                String display = pipe >= 0 ? inner.substring(pipe + 1).trim()
                    : slug.substring(slug.indexOf('/') + 1);
                sb.append(display);
                removed++;
            }
            i = close + 2;
        }
        return new CleanResult(sb.toString(), removed);
    }

    // ══════════════════════════════════════════════════════════
    // ② 索引页介绍
    // ══════════════════════════════════════════════════════════

    private record IndexIntro(String title, String intro) {
    }

    private IndexIntro generateIndexIntro(
        List<PageView> pages, String existingIntro, List<String> changes, String modelName
    ) {
        ModelConfigResolver.ResolvedModel _r = resolveModel(modelName);
        ProviderConfig config = _r == null ? null : _r.config();
        // ⚠️ 往下传的必须是 **models.name**：入参 modelName 往往只是 models.id，
        //    直接发出去会被对方 API 400（实测 `but you passed model-0823a98d`）。
        if (_r != null && _r.apiModelName() != null && !_r.apiModelName().isBlank()) {
            modelName = _r.apiModelName();
        }
        if (config == null) {
            return new IndexIntro(null, null);
        }
        boolean isUpdate = existingIntro != null && !existingIntro.isBlank();
        String system = isUpdate
            ? KbWikiPrompts.INDEX_INTRO_UPDATE_SYSTEM
            : KbWikiPrompts.INDEX_INTRO_SYSTEM;

        StringBuilder overview = new StringBuilder();
        for (PageView p : pages) {
            if (isIndex(p.pageType()) || p.title() == null || p.title().isBlank()) {
                continue;
            }
            overview.append("- ").append(p.title());
            if (p.summary() != null && !p.summary().isBlank()) {
                overview.append("：").append(p.summary());
            }
            overview.append('\n');
        }

        String user = KbWikiPrompts.INDEX_INTRO_USER
            .replace("{{EXISTING_INTRO}}", isUpdate ? existingIntro : "（首次生成）")
            .replace("{{CHANGES}}", changes == null || changes.isEmpty()
                ? "（无变更）" : String.join("\n", changes))
            .replace("{{PAGES_OVERVIEW}}", overview.toString());

        LlmProvider.LlmInvocationResult r = invoke(
            config, modelName, system, user, 0.3, "wiki_index_intro");
        if (r == null || r.content() == null) {
            return new IndexIntro(null, null);
        }
        JsonNode root = KbWikiMapStage.parseJsonLoose(r.content());
        if (root == null) {
            log.warn("[Wiki] 索引页介绍解析失败，保留原样");
            return new IndexIntro(null, null);
        }
        String title = root.path("title").asText(null);
        String intro = root.path("intro").asText(null);
        return new IndexIntro(
            title == null || title.isBlank() ? null : title.trim(),
            intro == null || intro.isBlank() ? null : intro.trim());
    }

    // ══════════════════════════════════════════════════════════
    // ④ 补链用的引用表
    // ══════════════════════════════════════════════════════════

    /**
     * 构建可链接的引用（slug + 显示名 + 别名）。
     *
     * <p>⭐ 只取 entity / concept：summary 页和 index 页不该被正文结构性地引用
     * （summary 页是文档级概览，被链进去会让「实体」和「文档」的概念混淆）。
     */
    static List<KbWikiLinkifier.LinkRef> buildLinkRefs(List<PageView> pages) {
        List<KbWikiLinkifier.LinkRef> out = new ArrayList<>();
        for (PageView p : pages) {
            if (!"entity".equalsIgnoreCase(p.pageType())
                && !"concept".equalsIgnoreCase(p.pageType())) {
                continue;
            }
            if (p.title() != null && !p.title().isBlank()) {
                out.add(new KbWikiLinkifier.LinkRef(p.slug(), p.title().trim()));
            }
        }
        return out;
    }

    /** 数正文里的 wiki 链接个数。 */
    static int countWikiLinks(String content) {
        if (content == null) {
            return 0;
        }
        int count = 0;
        int i = 0;
        while (true) {
            int start = content.indexOf("[[", i);
            if (start < 0) {
                return count;
            }
            int close = content.indexOf("]]", start + 2);
            if (close < 0) {
                return count;
            }
            count++;
            i = close + 2;
        }
    }

    // ══════════════════════════════════════════════════════════
    // 空目录剪枝
    // ══════════════════════════════════════════════════════════

    /**
     * 算出「可以删掉」的空目录（含其下无页面的父目录）。
     *
     * <p>⚠️ 纯计算：真正删除由调用方在同一事务里做。
     *
     * @param folderPaths 全部目录路径（如 "地点"、"地点/地址"）
     * @param usedPaths   仍被页面使用的目录路径
     * @return 应删的目录路径（从深到浅，避免删父目录时子目录还在）
     */
    public static List<String> prunableFolders(List<String> folderPaths, Set<String> usedPaths) {
        if (folderPaths == null || folderPaths.isEmpty()) {
            return List.of();
        }
        // 先用集合本身把「有子目录的」也算成在用
        Set<String> keep = new LinkedHashSet<>(usedPaths == null ? Set.of() : usedPaths);
        List<String> sorted = new ArrayList<>(folderPaths);
        sorted.sort((a, b) -> Integer.compare(depth(b), depth(a)));  // 深→浅

        List<String> prunable = new ArrayList<>();
        for (String path : sorted) {
            if (keep.contains(path)) {
                continue;
            }
            // 有子目录且子目录被保留 ⇒ 这个也不能删
            boolean hasKeptChild = false;
            for (String k : keep) {
                if (k.startsWith(path + "/")) {
                    hasKeptChild = true;
                    break;
                }
            }
            if (hasKeptChild) {
                continue;
            }
            prunable.add(path);
        }
        return prunable;
    }

    private static int depth(String path) {
        return path == null ? 0 : path.split("/").length;
    }

    // ══════════════════════════════════════════════════════════
    // 基础设施
    // ══════════════════════════════════════════════════════════

    private static boolean isIndex(String pageType) {
        return "index".equalsIgnoreCase(pageType);
    }

    /**
     * 解析「模型 ID / 名」→ 配置 + 真实模型名。
     *
     * <p>⚠️ 必须整份返回 {@link ModelConfigResolver.ResolvedModel}：调用点要的是
     * {@code apiModelName}（= {@code models.name}），而库里 {@code *_model_id} 存的是
     * {@code models.id}。只带 config 出去，调用点就只好把 ID 当名字发 ——
     * 实测被对方 400 拒绝（{@code but you passed model-0823a98d}）。
     */
    private ModelConfigResolver.ResolvedModel resolveModel(String modelName) {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            return modelConfigResolver.resolve(modelName);
        } catch (RuntimeException e) {
            log.warn("[Wiki] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    private LlmProvider.LlmInvocationResult invoke(
        ProviderConfig config, String modelName, String system, String user,
        double temperature, String label
    ) {
        try {
            return sideCallClient.message(
                config, modelName, system, user,
                new LlmProvider.ChatRequestOptions(
                    null, null, null,
                    LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
                    temperature, label,
                    null, null, null, null,
                    null, null, null, null,
                    null, null),
                LlmSideCallClient.SideCallUsage.standalone(UsageCategory.SIDE_CALL, label));
        } catch (RuntimeException e) {
            log.warn("[Wiki] 模型调用失败 label={} 原因={}", label, e.getMessage());
            return null;
        }
    }
}
