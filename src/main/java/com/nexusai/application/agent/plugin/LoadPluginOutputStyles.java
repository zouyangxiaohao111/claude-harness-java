package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.outputstyle.OutputStyleDirLoader;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [MPL6] Plugin output styles 加载器 · 对齐 CC utils/plugins/loadPluginOutputStyles.ts
 * （{@code loadPluginOutputStyles} memoize 导出符）。
 *
 * <p>CC 真源（loadPluginOutputStyles.ts）：
 * <ul>
 *   <li>{@code loadOutputStylesFromDirectory}（:15-34）— 目录递归扫描所有 .md</li>
 *   <li>{@code loadOutputStyleFromFile}（:36-85）— frontmatter 解析；name = {@code plugin:base}
 *       （base = frontmatter.name ?? 文件名去 .md，:53-55）；source='plugin'（:76）；
 *       forceForPlugin 三态解析（:63-70，boolean/string）</li>
 *   <li>outputStylesPath 默认目录 + outputStylesPaths 附加路径（目录/单 .md 文件）（:104-168）</li>
 * </ul>
 *
 * <p>生产接线：{@code PluginLoader.loadAllEnabledOutputStyles} 委托本类
 * （CC loadPluginOutputStyles → loadAllPluginsCacheOnly 产出 enabled plugins 逐插件扫描）。
 */
public final class LoadPluginOutputStyles {

    private static final Logger log = LoggerFactory.getLogger(LoadPluginOutputStyles.class);

    private static final ParseSkillFrontmatter PARSER = new ParseSkillFrontmatter();

    private LoadPluginOutputStyles() {
    }

    /**
     * [MPL6] 扫描全部 enabled plugins 的 output styles · 对齐 CC loadPluginOutputStyles
     * （loadPluginOutputStyles.ts:87-174）。
     *
     * <p>outputStylesPath 默认目录 + outputStylesPaths manifest 附加路径（目录/单 .md）均扫描；
     * name = {@code plugin:base}，forceForPlugin 三态（true/false/undefined）正确解析。
     *
     * @param enabledPlugins 已启用的插件列表
     * @return 扫描出的 plugin output styles 列表（source='plugin'）
     */
    public static List<OutputStyleDirLoader.OutputStyle> load(List<PluginLoader.LoadedPlugin> enabledPlugins) {
        List<OutputStyleDirLoader.OutputStyle> all = new ArrayList<>();
        for (PluginLoader.LoadedPlugin plugin : enabledPlugins) {
            if (plugin == null) {
                continue;
            }
            Set<String> loadedPaths = new HashSet<>();
            if (plugin.outputStylesPath() != null) {
                try {
                    loadOutputStylesFromDirectory(plugin.outputStylesPath(), plugin.name(), loadedPaths, all);
                } catch (Exception e) {
                    log.warn("[LoadPluginOutputStyles] 插件 {} 默认 output-styles 目录加载失败: {} (CC :118-123)",
                        plugin.name(), e.getMessage());
                }
            }
            if (plugin.outputStylesPaths() != null) {
                for (String stylePath : plugin.outputStylesPaths()) {
                    if (stylePath == null || stylePath.isBlank()) {
                        continue;
                    }
                    try {
                        Path p = Path.of(stylePath);
                        if (Files.isDirectory(p)) {
                            loadOutputStylesFromDirectory(p, plugin.name(), loadedPaths, all);
                        } else if (Files.isRegularFile(p) && stylePath.toLowerCase().endsWith(".md")) {
                            OutputStyleDirLoader.OutputStyle style =
                                loadOutputStyleFromFile(p, plugin.name(), loadedPaths);
                            if (style != null) {
                                all.add(style);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[LoadPluginOutputStyles] 插件 {} outputStylesPaths {} 加载失败: {} (CC :161-166)",
                            plugin.name(), stylePath, e.getMessage());
                    }
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[LoadPluginOutputStyles] 总 plugin output styles 加载数: {} (CC :171)", all.size());
        }
        return all;
    }

    /**
     * 目录递归扫描 · 对齐 CC loadOutputStylesFromDirectory（:15-34，walkPluginMarkdown 无 stopAtSkillDir）。
     */
    private static void loadOutputStylesFromDirectory(Path dir, String pluginName,
                                                      Set<String> loadedPaths,
                                                      List<OutputStyleDirLoader.OutputStyle> all) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        scanDir(dir, pluginName, loadedPaths, all);
    }

    private static void scanDir(Path dir, String pluginName, Set<String> loadedPaths,
                                List<OutputStyleDirLoader.OutputStyle> all) {
        List<Path> entries;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            entries = stream.toList();
        } catch (IOException e) {
            log.warn("[LoadPluginOutputStyles] 扫描目录失败 {}: {}（单个坏目录不中断，CC walkPluginMarkdown.ts:60-65）",
                dir, e.getMessage());
            return;
        }
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                scanDir(entry, pluginName, loadedPaths, all);
            } else if (Files.isRegularFile(entry) && entry.getFileName().toString().toLowerCase().endsWith(".md")) {
                OutputStyleDirLoader.OutputStyle style = loadOutputStyleFromFile(entry, pluginName, loadedPaths);
                if (style != null) {
                    all.add(style);
                }
            }
        }
    }

    /**
     * 单文件加载 · 对齐 CC loadOutputStyleFromFile（:36-85）。
     *
     * <p>name = {@code plugin:base}（base = frontmatter.name ?? 文件名去 .md，:53-55）；
     * description coerce ?? markdown 首行提取（:56-61）；forceForPlugin 三态（:63-70）；
     * prompt = markdown 正文 trim（:75）；source='plugin'（:76）；keepCodingInstructions 未解析
     * （CC 插件样式不产该字段 → null）。
     */
    private static OutputStyleDirLoader.OutputStyle loadOutputStyleFromFile(Path file, String pluginName,
                                                                            Set<String> loadedPaths) {
        if (!loadedPaths.add(file.toAbsolutePath().toString())) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> fm = PARSER.parse(content);
            String markdownContent = PARSER.extractBody(content);

            String fileName = fileNameWithoutMd(file);
            Object nameObj = fm.get("name");
            String baseStyleName = (nameObj != null && !nameObj.toString().isEmpty())
                ? nameObj.toString() : fileName;
            String name = pluginName + ":" + baseStyleName; // CC :55
            String validated = ParseSkillFrontmatter.coerceDescriptionToString(fm.get("description"), name, null);
            String description = validated != null
                ? validated
                : ParseSkillFrontmatter.extractDescriptionFromMarkdown(markdownContent,
                    "Output style from " + pluginName + " plugin");

            // CC :63-70 forceForPlugin 三态解析（boolean + string）
            Object forceRaw = fm.get("force-for-plugin");
            Boolean forceForPlugin = null;
            if (Boolean.TRUE.equals(forceRaw) || "true".equals(forceRaw)) {
                forceForPlugin = Boolean.TRUE;
            } else if (Boolean.FALSE.equals(forceRaw) || "false".equals(forceRaw)) {
                forceForPlugin = Boolean.FALSE;
            }

            return new OutputStyleDirLoader.OutputStyle(
                name, description, markdownContent.trim(), "plugin", null, forceForPlugin);
        } catch (Exception e) {
            log.warn("[LoadPluginOutputStyles] 加载 output style 失败 {}: {} (CC :79-84 catch → null)",
                file, e.getMessage());
            return null;
        }
    }

    private static String fileNameWithoutMd(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : "";
        return name.toLowerCase().endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }
}
