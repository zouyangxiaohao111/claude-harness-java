package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.outputstyle.OutputStyleDirLoader;
import com.nexusai.application.agent.skill.MarkdownConfigLoader;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 输出风格名 → {@link OutputStyleConfig} 轻量解析 · [SP-09] 批次 F 新增。
 *
 * <p>对齐 CC {@code getOutputStyleConfig()}（constants/outputStyles.ts:181-213）：
 * <ol>
 *   <li>{@code allStyles = await getAllOutputStyles(getCwd())} —— 复合注册表（built-in +
 *       plugin + managed + user + project）；<b>Java 简化</b>：plugin（LoadPluginOutputStyles）+
 *       目录（OutputStyleDirLoader）两源合并为 name→style 表（forceForPlugin 优先级/完整
 *       registered 表语义简化，OPD-SP-29 待拍板登记）</li>
 *   <li>{@code allStyles[outputStyle] ?? null}（:213）—— 未命中 → null → 不注入 Output Style 段；
 *       CC built-in OUTPUT_STYLE_CONFIG 默认名（如 default）Java 无内置注册表 → 映射 null</li>
 * </ol>
 *
 * <p>静态桥接线（BoundaryReader/MicroCompactor 先例）：{@link #setPluginStylesSupplier} 由
 * LlmAgentLoop.setPluginLoader 或 ToolRegistrationConfig 注入 plugin 样式源；目录加载走
 * {@link MarkdownConfigLoader#loadMarkdownFilesForSubdir}（output-styles）+ ParseSkillFrontmatter
 * 默认工厂。未接线 → 空注册表 → 恒 null（零行为变化，settings.output_style 默认未配置）。
 */
public final class PromptOutputStyleResolver {

    private static final Logger log = LoggerFactory.getLogger(PromptOutputStyleResolver.class);

    /** plugin 样式提供者 · 静态桥（null 注入 → 复位空源）。 */
    private static volatile Supplier<List<OutputStyleDirLoader.OutputStyle>> pluginStylesSupplier = () -> List.of();

    /** 目录样式加载器 · 静态桥（null 注入 → 复位默认工厂）。 */
    private static volatile OutputStyleDirLoader dirLoader = createDefaultDirLoader();

    private PromptOutputStyleResolver() {
    }

    /**
     * 注入 plugin 样式源（静态桥）· 由 LlmAgentLoop.setPluginLoader 接线
     * （{@code PluginLoader.loadAllEnabledOutputStyles} 产出 source='plugin' 样式）。
     *
     * @param supplier plugin 样式提供者（null → 复位空源）
     */
    public static void setPluginStylesSupplier(Supplier<List<OutputStyleDirLoader.OutputStyle>> supplier) {
        pluginStylesSupplier = supplier != null ? supplier : () -> List.of();
    }

    /**
     * 注入目录样式加载器（静态桥，测试可注入临时目录）· null → 复位默认工厂。
     *
     * @param loader OutputStyleDirLoader（null → 默认）
     */
    public static void setDirLoader(OutputStyleDirLoader loader) {
        dirLoader = loader != null ? loader : createDefaultDirLoader();
    }

    /**
     * 解析风格名 → OutputStyleConfig · 对齐 CC {@code allStyles[outputStyle] ?? null}
     * （outputStyles.ts:213）。
     *
     * <p>plugin 样式（低优先级）+ 目录样式（后覆盖）合并为 name→style 表；同名后者覆盖
     * （对齐 CC allStyles[style.name] = ... 语义）。未命中 → null（不注入 Output Style 段）。
     *
     * @param styleName 风格名（DB settings.output_style · 可空）
     * @param cwd       会话工作目录（目录样式扫描起点 · CC getCwd()）
     * @return 命中 → OutputStyleConfig；未命中/异常 → null
     */
    public static OutputStyleConfig resolve(String styleName, String cwd) {
        if (styleName == null || styleName.isBlank()) {
            return null;
        }
        try {
            List<OutputStyleDirLoader.OutputStyle> all = new ArrayList<>();
            List<OutputStyleDirLoader.OutputStyle> plugins = pluginStylesSupplier.get();
            if (plugins != null) {
                all.addAll(plugins);
            }
            if (dirLoader != null && cwd != null && !cwd.isBlank()) {
                List<OutputStyleDirLoader.OutputStyle> dirs = dirLoader.getOutputStyleDirStyles(Path.of(cwd));
                if (dirs != null) {
                    all.addAll(dirs);
                }
            }
            Map<String, OutputStyleDirLoader.OutputStyle> byName = new LinkedHashMap<>();
            for (OutputStyleDirLoader.OutputStyle s : all) {
                if (s != null && s.name() != null) {
                    byName.put(s.name(), s);
                }
            }
            OutputStyleDirLoader.OutputStyle hit = byName.get(styleName);
            if (hit == null) {
                if (log.isDebugEnabled()) {
                    log.debug("[PromptOutputStyleResolver] 风格 '{}' 未命中注册表（plugin+dir 共 {} 项）→ null（CC allStyles[name] ?? null 等价）",
                        styleName, byName.size());
                }
                return null;
            }
            if (log.isDebugEnabled()) {
                log.debug("[PromptOutputStyleResolver] 风格 '{}' 命中（source={}）→ 注入 Output Style 段", styleName, hit.source());
            }
            return new OutputStyleConfig(
                hit.name(), hit.description(), hit.prompt(), hit.source(), hit.keepCodingInstructions());
        } catch (Exception e) {
            log.warn("[PromptOutputStyleResolver] 输出风格解析失败，返回 null（不注入 Output Style 段）: {}", e.toString());
            return null;
        }
    }

    /**
     * 默认目录样式加载器 · MarkdownConfigLoader.loadMarkdownFilesForSubdir('output-styles', cwd)
     * （CC loadMarkdownFilesForSubdir markdownConfigLoader.ts:297）+ ParseSkillFrontmatter
     * frontmatter/description 解析（output-styles/*.md 骨架）。
     *
     * <p>[T4 核对] output-styles 内容读兼容（决策 D1/D3/D6）由 MarkdownConfigLoader 统一承载
     * （T3 改造）：user 源拆两层（{@code ~/.{appName}/output-styles} 优先 + {@code ~/.claude/output-styles}
     * 回落）；project 逐层 {@code .nexusai/output-styles} 优先 + {@code .claude/output-styles} 回落；
     * name first-wins 去重 nexusai 赢。本 dirScanner 仅委托，无需改动。
     *
     * @return 默认 OutputStyleDirLoader
     */
    private static OutputStyleDirLoader createDefaultDirLoader() {
        return new OutputStyleDirLoader(
            cwd -> {
                List<MarkdownConfigLoader.MarkdownFile> files =
                    MarkdownConfigLoader.loadMarkdownFilesForSubdir("output-styles", cwd.toString());
                List<OutputStyleDirLoader.MarkdownFile> mapped = new ArrayList<>(files.size());
                for (MarkdownConfigLoader.MarkdownFile md : files) {
                    mapped.add(new OutputStyleDirLoader.MarkdownFile(
                        md.filePath(), md.frontmatter(), md.content(), md.source()));
                }
                return mapped;
            },
            new ParseSkillFrontmatter()::parse,
            ParseSkillFrontmatter::extractDescriptionFromMarkdown,
            msg -> log.warn("[OutputStyleDirLoader] {}", msg));
    }
}
