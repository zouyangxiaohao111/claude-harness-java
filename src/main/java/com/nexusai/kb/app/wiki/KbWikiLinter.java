package com.nexusai.kb.app.wiki;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wiki 健康检查 · W2-7（源侧 {@code wiki_lint.go} 427 行）。
 *
 * <p><b>纯函数</b>：输入页面快照，输出问题清单 + 健康分。⛔ 不碰数据库 —— 那样无法单测。
 *
 * <h2>六类问题（源侧实测）</h2>
 * <table>
 *   <tr><th>类型</th><th>严重度</th><th>可自动修</th><th>判据</th></tr>
 *   <tr><td>orphan_page</td><td>warning</td><td>否</td><td>无入链（index 页豁免）</td></tr>
 *   <tr><td>broken_link</td><td>error</td><td>是</td><td>出链指向不存在的 slug</td></tr>
 *   <tr><td>empty_content</td><td>warning</td><td>是</td><td>正文 &lt; 50 字符</td></tr>
 *   <tr><td>stale_ref</td><td>error</td><td>是</td><td>source_refs 指向已删文档</td></tr>
 *   <tr><td>missing_cross_ref</td><td>info</td><td>否</td><td>正文提到实体名但没链</td></tr>
 *   <tr><td>duplicate_slug</td><td>error</td><td>否</td><td>同库内 slug 重复</td></tr>
 * </table>
 *
 * <h2>⚠️ {@code duplicate_slug} 在源侧 DDL 里是唯一索引 ⇒ 本仓也该由库挡住</h2>
 * 但唯一索引只在「未删除范围内」生效，而 SQLite 的部分索引容易写错
 * （忘记 {@code WHERE deleted = 0} ⇒ 删过的页面永远挡住同名新建）。
 * ⇒ Linter 里<b>再查一遍</b>作为兜底，且这是唯一能在<b>不依赖数据库错误</b>的情况下
 * 报告它的地方。
 */
public final class KbWikiLinter {

    private static final Logger log = LoggerFactory.getLogger(KbWikiLinter.class);

    /** 空内容阈值（源侧 50 字符）。 */
    public static final int MIN_CONTENT_CHARS = 50;

    /** 问题类型（wire 值，与源侧逐字一致）。 */
    public static final String ORPHAN_PAGE = "orphan_page";
    public static final String BROKEN_LINK = "broken_link";
    public static final String EMPTY_CONTENT = "empty_content";
    public static final String STALE_REF = "stale_ref";
    public static final String MISSING_CROSS_REF = "missing_cross_ref";
    public static final String DUPLICATE_SLUG = "duplicate_slug";

    public static final String SEVERITY_ERROR = "error";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_INFO = "info";

    private KbWikiLinter() {
    }

    /**
     * 页面快照（Lint 输入）。
     *
     * @param slug      页面 slug
     * @param title     标题
     * @param pageType  类型（index 页豁免孤儿检查）
     * @param content   正文
     * @param outLinks  出链 slug
     * @param inLinks   入链 slug
     * @param sourceRefs 来源引用（含 knowledgeId）
     */
    public record PageSnapshot(
        String slug, String title, String pageType, String content,
        List<String> outLinks, List<String> inLinks, List<String> sourceRefs
    ) {
        public PageSnapshot {
            outLinks = outLinks == null ? List.of() : List.copyOf(outLinks);
            inLinks = inLinks == null ? List.of() : List.copyOf(inLinks);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }

    /**
     * 一条 Lint 问题。
     *
     * @param type        问题类型
     * @param severity    error / warning / info
     * @param pageSlug    出问题的页面
     * @param targetSlug  涉及的另一页面 / 文档 ID（可空）
     * @param description 人可读描述
     * @param autoFixable 能否自动修
     */
    public record Issue(
        String type, String severity, String pageSlug, String targetSlug,
        String description, boolean autoFixable
    ) {
    }

    /**
     * Lint 报告。
     *
     * @param issues      问题清单
     * @param healthScore 0-100
     * @param summary     人可读总结
     * @param errorCount  error 级数量
     * @param warningCount warning 级数量
     * @param infoCount   info 级数量
     */
    public record Report(
        List<Issue> issues, int healthScore, String summary,
        int errorCount, int warningCount, int infoCount
    ) {
    }

    /**
     * 跑一遍 Lint。
     *
     * @param pages            全部页面快照
     * @param liveKnowledgeIds 仍存在的文档 ID 集合（用于 stale_ref；null ⇒ 跳过该检查）
     * @return 报告
     */
    public static Report run(List<PageSnapshot> pages, Set<String> liveKnowledgeIds) {
        if (pages == null || pages.isEmpty()) {
            return new Report(List.of(), 100, "Wiki 为空，无内容可检查。", 0, 0, 0);
        }

        // 现存 slug 集（用于死链判定）
        Set<String> liveSlugs = new LinkedHashSet<>();
        for (PageSnapshot p : pages) {
            if (p.slug() != null && !p.slug().isBlank()) {
                liveSlugs.add(p.slug());
            }
        }

        // 实体/概念的「slug → 标题」（用于缺链检查）
        Map<String, String> entityTitles = new LinkedHashMap<>();
        for (PageSnapshot p : pages) {
            if (isEntityOrConcept(p.pageType()) && p.title() != null && !p.title().isBlank()) {
                entityTitles.put(p.slug(), p.title());
            }
        }

        List<Issue> issues = new ArrayList<>();
        // 重复 slug 统计
        Map<String, Integer> slugCounts = new LinkedHashMap<>();

        for (PageSnapshot p : pages) {
            if (p.slug() != null && !p.slug().isBlank()) {
                slugCounts.merge(p.slug(), 1, Integer::sum);
            }

            // ① 孤儿页：无入链；index 页豁免
            if (!isIndex(p.pageType()) && p.inLinks().isEmpty()) {
                issues.add(new Issue(ORPHAN_PAGE, SEVERITY_WARNING, p.slug(), null,
                    "页面「" + safeTitle(p) + "」没有任何入链 —— 它游离在 Wiki 之外", false));
            }

            // ② 死链：出链指向不存在的页面
            for (String out : p.outLinks()) {
                if (out != null && !out.isBlank() && !liveSlugs.contains(out)) {
                    issues.add(new Issue(BROKEN_LINK, SEVERITY_ERROR, p.slug(), out,
                        "页面「" + safeTitle(p) + "」链接到 [[" + out + "]]，但该页面不存在", true));
                }
            }

            // ③ 空内容
            String content = p.content() == null ? "" : p.content().trim();
            if (content.codePointCount(0, content.length()) < MIN_CONTENT_CHARS) {
                issues.add(new Issue(EMPTY_CONTENT, SEVERITY_WARNING, p.slug(), null,
                    "页面「" + safeTitle(p) + "」内容过少（"
                        + content.codePointCount(0, content.length()) + " 字符）", true));
            }

            // ④ 过期来源：source_refs 指向已删文档
            if (liveKnowledgeIds != null && !isIndex(p.pageType())) {
                for (String ref : p.sourceRefs()) {
                    String kid = KbWikiReduceStage.knowledgeIdOfRef(ref);
                    if (kid.isEmpty()) {
                        continue;
                    }
                    if (!liveKnowledgeIds.contains(kid)) {
                        issues.add(new Issue(STALE_REF, SEVERITY_ERROR, p.slug(), kid,
                            "页面「" + safeTitle(p) + "」引用了已删除的文档 " + kid, true));
                    }
                }
            }

            // ⑤ 缺链：正文提到实体名但没链
            Set<String> outSet = new LinkedHashSet<>(p.outLinks());
            String lower = content.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> e : entityTitles.entrySet()) {
                String slug = e.getKey();
                String title = e.getValue();
                if (slug.equals(p.slug())) {
                    continue;       // 不检查自己
                }
                if (title.isBlank() || !lower.contains(title.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (outSet.contains(slug)) {
                    continue;
                }
                issues.add(new Issue(MISSING_CROSS_REF, SEVERITY_INFO, p.slug(), slug,
                    "页面「" + safeTitle(p) + "」提到了「" + title + "」但没有链接到 [[" + slug + "]]",
                    false));
            }
        }

        // ⑥ 重复 slug
        for (Map.Entry<String, Integer> e : slugCounts.entrySet()) {
            if (e.getValue() > 1) {
                issues.add(new Issue(DUPLICATE_SLUG, SEVERITY_ERROR, e.getKey(), e.getKey(),
                    "slug「" + e.getKey() + "」出现了 " + e.getValue() + " 次（未删除范围内必须唯一）",
                    false));
            }
        }

        int score = healthScore(pages, issues);
        int errors = 0;
        int warnings = 0;
        int infos = 0;
        for (Issue i : issues) {
            switch (i.severity()) {
                case SEVERITY_ERROR -> errors++;
                case SEVERITY_WARNING -> warnings++;
                default -> infos++;
            }
        }
        String summary = issues.isEmpty()
            ? "Wiki 状态良好，未发现问题。"
            : "发现 " + issues.size() + " 个问题：" + errors + " 个错误、"
                + warnings + " 个警告、" + infos + " 条建议。";

        if (log.isInfoEnabled()) {
            log.info("[Wiki] Lint 完成 页面数={} 问题数={} 健康分={}", pages.size(), issues.size(), score);
        }
        return new Report(issues, score, summary, errors, warnings, infos);
    }

    /**
     * 健康分（逐条对齐源侧扣分规则）。
     *
     * <p>⚠️ 扣分项是<b>绝对数量</b>而非比例（死链 5 分/条、空页 3 分/条）——
     * 这与「按比例」在小库上完全不同：3 页里 1 条死链，按比例是 33%，
     * 按绝对值是 5 分。源侧选后者，本仓照抄（否则小库分数会剧烈跳动）。
     */
    static int healthScore(List<PageSnapshot> pages, List<Issue> issues) {
        int score = 100;
        int total = pages.size();

        long orphanCount = issues.stream().filter(i -> ORPHAN_PAGE.equals(i.type())).count();
        double orphanPct = total == 0 ? 0 : (double) orphanCount / total * 100;
        if (orphanPct > 50) {
            score -= 25;
        } else if (orphanPct > 25) {
            score -= 10;
        }

        long brokenCount = issues.stream().filter(i -> BROKEN_LINK.equals(i.type())).count();
        score -= (int) brokenCount * 5;

        long totalLinks = pages.stream().mapToLong(p -> p.outLinks().size()).sum();
        if (totalLinks == 0 && total > 2) {
            score -= 15;
        }

        long emptyCount = issues.stream().filter(i -> EMPTY_CONTENT.equals(i.type())).count();
        score -= (int) emptyCount * 3;

        return Math.max(0, score);
    }

    private static boolean isIndex(String pageType) {
        return "index".equalsIgnoreCase(pageType);
    }

    private static boolean isEntityOrConcept(String pageType) {
        return "entity".equalsIgnoreCase(pageType) || "concept".equalsIgnoreCase(pageType);
    }

    private static String safeTitle(PageSnapshot p) {
        return p.title() == null || p.title().isBlank() ? p.slug() : p.title();
    }
}
