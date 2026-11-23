package com.nexusai.application.agent.bundled;

import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置专家目录 · 只读 classpath {@code experts/octop/<division>/<slug>.md}。
 *
 * <p>这 272 个专家随安装包内置，但**默认不激活**（不写 {@code ~/.nexusai/agents}、不进
 * {@code BuiltInAgents}）：只作为「可安装目录」展示，用户按需安装单个（P7-A 裁定）。
 *
 * <p>解析复用生产同一条 frontmatter 管线（{@link ParseSkillFrontmatter}），保证「列表里看到的」
 * 就是「安装后加载器能解析的」。
 *
 * <p>首次 {@link #all()} 惰性扫描并缓存（资源是静态的，进程内只需一次）。
 */
@Component
public class BundledExpertsCatalog {

    private static final Logger log = LoggerFactory.getLogger(BundledExpertsCatalog.class);

    /** classpath 资源匹配式（与生成脚本的落盘结构一致）。 */
    static final String RESOURCE_PATTERN = "classpath*:experts/octop/*/*.md";

    /** 从资源 URL 中提取分类目录（兼容 file: 目录与 jar:!/… 两种形态）。 */
    private static final Pattern DIVISION_IN_URL =
        Pattern.compile("experts/octop/([^/]+)/[^/]+\\.md$");

    private final PathMatchingResourcePatternResolver resolver;

    /** 目录快照（惰性构建；volatile 双检锁）。 */
    private volatile List<BundledExpert> cache;

    public BundledExpertsCatalog() {
        this(new PathMatchingResourcePatternResolver());
    }

    /** 测试/装配可注入 resolver。 */
    BundledExpertsCatalog(PathMatchingResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 全部内置专家（按 division、name 排序，顺序稳定）。
     *
     * @return 只读列表；classpath 无资源时为空列表
     */
    public List<BundledExpert> all() {
        List<BundledExpert> snapshot = cache;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (cache == null) {
                cache = loadAll();
            }
            return cache;
        }
    }

    /**
     * 目录过滤：可分别按分类与关键字（name / description / slug，大小写不敏感 contains）筛选。
     *
     * @param division 分类目录（null/blank = 全部分类）
     * @param query    搜索词（null/blank = 不过滤）
     * @return 过滤后的列表（顺序同 {@link #all()}）
     */
    public List<BundledExpert> list(String division, String query) {
        String div = division == null ? "" : division.trim();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (div.isEmpty() && q.isEmpty()) {
            return all();
        }
        List<BundledExpert> out = new ArrayList<>();
        for (BundledExpert e : all()) {
            if (!div.isEmpty() && !div.equalsIgnoreCase(e.division())) {
                continue;
            }
            if (!q.isEmpty()
                && !containsIgnoreCase(e.name(), q)
                && !containsIgnoreCase(e.description(), q)
                && !containsIgnoreCase(e.slug(), q)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /** 按 slug 查找（安装入口用）。 */
    public Optional<BundledExpert> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        String target = slug.trim();
        for (BundledExpert e : all()) {
            if (e.slug().equals(target)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private static boolean containsIgnoreCase(String value, String lowerCaseNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
    }

    /** 扫描 classpath 并解析全部专家；单个文件失败只 warn 跳过（不让一个坏文件打挂整个目录）。 */
    private List<BundledExpert> loadAll() {
        List<BundledExpert> list = new ArrayList<>();
        Resource[] resources;
        try {
            resources = resolver.getResources(RESOURCE_PATTERN);
        } catch (Exception e) {
            log.warn("[BundledExperts] 扫描内置专家资源失败: {}", e.toString());
            return List.of();
        }
        for (Resource resource : resources) {
            try {
                String url = resource.getURL().toString();
                String fileName = resource.getFilename();
                if (fileName == null || !fileName.endsWith(".md")) {
                    continue;
                }
                Matcher m = DIVISION_IN_URL.matcher(url);
                String division = m.find() ? m.group(1) : "unknown";
                String slug = fileName.substring(0, fileName.length() - 3);
                String text;
                try (InputStream in = resource.getInputStream()) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                ParseSkillFrontmatter.ParsedMarkdown parsed =
                    ParseSkillFrontmatter.parseFrontmatterStatic(text, url);
                Object nameObj = parsed.frontmatter().get("name");
                Object descObj = parsed.frontmatter().get("description");
                if (!(nameObj instanceof String name) || name.isBlank()
                    || !(descObj instanceof String description) || description.isBlank()) {
                    log.warn("[BundledExperts] 跳过缺 name/description 的内置专家: {}", url);
                    continue;
                }
                list.add(new BundledExpert(
                    slug,
                    division,
                    name.trim(),
                    description.replace("\\n", "\n"),
                    stringOrNull(parsed.frontmatter().get("emoji")),
                    stringOrNull(parsed.frontmatter().get("color")),
                    url,
                    text));
            } catch (Exception e) {
                log.warn("[BundledExperts] 解析内置专家失败 {}: {}", resource, e.toString());
            }
        }
        list.sort(Comparator.comparing(BundledExpert::division)
            .thenComparing(BundledExpert::name));
        log.info("[BundledExperts] 内置专家目录加载完成: {} 个（divisions={}）",
            list.size(), list.stream().map(BundledExpert::division).distinct().count());
        return List.copyOf(list);
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
