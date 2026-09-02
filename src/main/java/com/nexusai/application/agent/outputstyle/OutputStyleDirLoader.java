package com.nexusai.application.agent.outputstyle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Output-style 目录加载器 · 对齐 CC outputStyles/loadOutputStylesDir.ts.
 *
 * <p>L1 语义: 加载 output styles — nexusai 自有目录优先（${cwd}/.{appName}/output-styles 与
 *            ~/.{appName}/output-styles，appName 默认 nexusai），缺失回落 claude
 *            （${cwd}/.claude/output-styles 与 ~/.claude/output-styles，CC 读兼容 T3）。
 *            文件名 (无 .md) → styleName;frontmatter 提供 name/description;keep-coding-instructions 支持 bool+string.
 *            inner try/catch 跳过单文件错误 (logError + return null);outer try/catch 整体错误返回 [].
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: OutputStyle 5 字段 (name/description/prompt/source/keepCodingInstructions);
 *       MarkdownFile 4 字段 (filePath/frontmatter/content/source);
 *       getOutputStyleDirStyles(cwd) → List&lt;OutputStyle&gt;.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — 扫描 ${cwd}/.{appName}/output-styles（nexusai 优先，appName 默认
 *       nexusai，缺失回落 ${cwd}/.claude/output-styles 只读兼容 T3）→ 读所有 .md → 解析每个文件
 *       (basename 去 .md → name; frontmatter.name || basename; description 优先 frontmatter; keepCodingInstructions 三态解析)
 *       → 过滤 null → 返回 List.</li>
 *   <li><b>A3</b>: 内嵌 cache (path → List&lt;OutputStyle&gt;) + clearCaches() 用于刷新;
 *       纯函数 supplier 注入 (filesystem+frontmatter).</li>
 *   <li><b>A4</b>: 单文件解析失败 (catch error) → 跳过该文件;整体异常 → 返回 [] (不抛).</li>
 *   <li><b>A5</b>: 真实场景 — 3 文件混合 (frontmatter 完整 / frontmatter 缺 description / force-for-plugin 被警告).</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async/Promise → Java List (synchronous, file IO 注入避免阻塞);
 *                    TS lodash memoize → ConcurrentHashMap (key=cwd path);
 *                    TS Zod schema 解析 → 注入式 FrontmatterParser.
 */
public final class OutputStyleDirLoader {

    private static final Logger log = LoggerFactory.getLogger(OutputStyleDirLoader.class);

    private final Function<Path, List<MarkdownFile>> dirScanner;   // cwd → *.md 文件列表
    private final FrontmatterParser frontmatterParser;
    private final DescriptionExtractor descriptionExtractor;
    private final DebugLogger debugLog;

    private final Map<String, List<OutputStyle>> cache = new ConcurrentHashMap<>();

    public OutputStyleDirLoader(Function<Path, List<MarkdownFile>> dirScanner,
                                FrontmatterParser frontmatterParser,
                                DescriptionExtractor descriptionExtractor,
                                DebugLogger debugLog) {
        this.dirScanner = Objects.requireNonNull(dirScanner);
        this.frontmatterParser = Objects.requireNonNull(frontmatterParser);
        this.descriptionExtractor = Objects.requireNonNull(descriptionExtractor);
        this.debugLog = Objects.requireNonNull(debugLog);
    }

    /** CC OutputStyleConfig — 6 字段 output style 配置（MPL6 增 forceForPlugin）. */
    public record OutputStyle(
        String name,
        String description,
        String prompt,
        String source,
        Boolean keepCodingInstructions,
        /**
         * [MPL6] CC original: forceForPlugin（constants/outputStyles.ts:22）· 插件 output style
         * 是否强制为当前插件样式（三态：true/false/undefined）。非插件样式恒 null。
         */
        Boolean forceForPlugin
    ) {

        /** 5 字段兼容构造（forceForPlugin=null）· 对齐非插件 output style 无 force 声明。 */
        public OutputStyle(String name, String description, String prompt, String source,
                           Boolean keepCodingInstructions) {
            this(name, description, prompt, source, keepCodingInstructions, null);
        }
    }

    /** 中间类型 — 加载后的 markdown 文件 + 解析后的 frontmatter. */
    public record MarkdownFile(
        String filePath,
        Map<String, Object> frontmatter,
        String content,
        String source
    ) {}

    /** Frontmatter 解析器 (注入). */
    @FunctionalInterface
    public interface FrontmatterParser {
        Map<String, Object> parse(String content);
    }

    /** Description 提取器 (注入). */
    @FunctionalInterface
    public interface DescriptionExtractor {
        String extract(String content, String fallback);
    }

    /** 调试日志 (注入). */
    @FunctionalInterface
    public interface DebugLogger {
        void warn(String message);
    }

    /** CC getOutputStyleDirStyles — 主入口 (synchronous in Java). */
    public List<OutputStyle> getOutputStyleDirStyles(Path cwd) {
        String key = cwd.toAbsolutePath().toString();
        return cache.computeIfAbsent(key, k -> loadStyles(cwd));
    }

    /**
     * [MPL6] 合并链 · 对齐 CC getAllOutputStyles（outputStyles.ts:137-175）：
     * 内置 → plugin → managed → user → project 优先级（低→高），同名后者覆盖。
     *
     * <p>Java 无内置 OUTPUT_STYLE_CONFIG 常量（constants/outputStyles.ts:24-135 系统范围外）→
     * 合并面 = plugin 样式（最低优先级）+ 目录自定义样式（plugin 后被覆盖）。name 键去重，
     * 首见保留、后者覆盖（LinkedHashMap.put 覆盖语义，对齐 CC allStyles[style.name] = ...）。
     *
     * @param pluginStyles 插件 output styles（source='plugin'，LoadPluginOutputStyles 产出）
     * @param customStyles 目录自定义 styles（getOutputStyleDirStyles 产出）
     * @return 合并后不可变列表
     */
    public static List<OutputStyle> mergeOutputStyles(List<OutputStyle> pluginStyles,
                                                      List<OutputStyle> customStyles) {
        Map<String, OutputStyle> byName = new LinkedHashMap<>();
        if (pluginStyles != null) {
            for (OutputStyle s : pluginStyles) {
                if (s != null && s.name() != null) {
                    byName.put(s.name(), s);
                }
            }
        }
        if (customStyles != null) {
            for (OutputStyle s : customStyles) {
                if (s != null && s.name() != null) {
                    byName.put(s.name(), s);
                }
            }
        }
        return List.copyOf(byName.values());
    }

    /** CC clearOutputStyleCaches — 清缓存 (允许 hot reload). */
    public void clearOutputStyleCaches() {
        cache.clear();
    }

    private List<OutputStyle> loadStyles(Path cwd) {
        try {
            List<MarkdownFile> files = dirScanner.apply(cwd);
            List<OutputStyle> result = new ArrayList<>(files.size());
            for (MarkdownFile md : files) {
                try {
                    OutputStyle style = toOutputStyle(md);
                    if (style != null) {
                        result.add(style);
                    }
                } catch (Exception e) {
                    log.warn("[OutputStyleDirLoader] failed to load {}: {}",
                        md.filePath(), e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[OutputStyleDirLoader] scan failed for {}: {}", cwd, e.getMessage());
            return List.of();
        }
    }

    private OutputStyle toOutputStyle(MarkdownFile md) {
        String fileName = basename(md.filePath());
        String styleName = stripMdExtension(fileName);

        Map<String, Object> fm = md.frontmatter() != null ? md.frontmatter() : Map.of();
        String name = fm.get("name") instanceof String s && !s.isEmpty() ? s : styleName;
        String description = coerceDescriptionToString(fm.get("description"), styleName);
        if (description == null) {
            description = descriptionExtractor.extract(md.content(), "Custom " + styleName + " output style");
        }

        // keep-coding-instructions 三态: true | false | undefined (支持 bool + string)
        Object raw = fm.get("keep-coding-instructions");
        Boolean keepCodingInstructions = null;
        if (raw instanceof Boolean b) {
            keepCodingInstructions = b;
        } else if ("true".equals(raw)) {
            keepCodingInstructions = Boolean.TRUE;
        } else if ("false".equals(raw)) {
            keepCodingInstructions = Boolean.FALSE;
        }

        // force-for-plugin 在非 plugin output style 上 → warning (per CC)
        if (fm.containsKey("force-for-plugin")) {
            debugLog.warn("Output style \"" + name + "\" has force-for-plugin set, "
                + "but this option only applies to plugin output styles. Ignoring.");
        }

        return new OutputStyle(
            name,
            description,
            md.content() == null ? "" : md.content().trim(),
            md.source(),
            keepCodingInstructions
        );
    }

    /** coerceDescriptionToString — frontmatter description 可能为数组,取首项;否则 null. */
    private static String coerceDescriptionToString(Object value, String fallback) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        if (value instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            return first == null ? null : first.toString();
        }
        return value.toString();
    }

    private static String basename(String path) {
        int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return sep >= 0 ? path.substring(sep + 1) : path;
    }

    private static String stripMdExtension(String name) {
        return name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }
}
