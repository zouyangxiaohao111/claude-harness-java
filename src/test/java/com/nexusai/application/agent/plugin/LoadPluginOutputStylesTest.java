package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.outputstyle.OutputStyleDirLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MPL6] LoadPluginOutputStyles 等价类测试 · 对齐 CC loadPluginOutputStyles.ts
 * （name=plugin:base + forceForPlugin 三态）。
 *
 * <p>验证：① output-styles/ 目录扫出 plugin style，name=plugin:base（frontmatter.name ?? 文件名，:53-55）；
 * ② forceForPlugin 三态（boolean/string，:63-70）；③ outputStylesPaths 附加路径（目录+单文件）均被扫描；
 * ④ 合并链（OutputStyleDirLoader.mergeOutputStyles 对齐 CC getAllOutputStyles outputStyles.ts:137-175）。
 */
class LoadPluginOutputStylesTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("output-styles/ 目录扫描：name=plugin:base + forceForPlugin 三态（loadPluginOutputStyles.ts:53-70）")
    void output_styles_directory_namespaces_and_force_for_plugin() throws Exception {
        // WHY: CC loadOutputStyleFromFile — name = `${pluginName}:${frontmatter.name ?? 文件名}`（:55），
        //   source='plugin'（:76），forceForPlugin 三态（true/'true' → true，false/'false' → false，否则 undefined :63-70）。
        Path stylesDir = tempDir.resolve("style-plugin").resolve("output-styles");
        Files.createDirectories(stylesDir);
        Files.writeString(stylesDir.resolve("concise.md"),
            "---\nforce-for-plugin: true\n---\n\nbe concise");
        Files.writeString(stylesDir.resolve("verbatim.md"),
            "---\nname: Verbatim\nforce-for-plugin: \"false\"\n---\n\nquote exactly");
        Files.writeString(stylesDir.resolve("plain.md"),
            "---\ndescription: plain style\n---\n\nplain body");

        List<OutputStyleDirLoader.OutputStyle> styles = LoadPluginOutputStyles.load(List.of(
            plugin("style-plugin", stylesDir.getParent())));

        assertThat(styles.stream().map(OutputStyleDirLoader.OutputStyle::name))
            .as("name = plugin:base，base = frontmatter.name ?? 文件名（CC :55）")
            .containsExactlyInAnyOrder("style-plugin:concise", "style-plugin:Verbatim", "style-plugin:plain");
        OutputStyleDirLoader.OutputStyle concise = byName(styles, "style-plugin:concise");
        assertThat(concise.forceForPlugin())
            .as("force-for-plugin: true → true（CC :66-67）")
            .isEqualTo(Boolean.TRUE);
        assertThat(concise.prompt()).isEqualTo("be concise");
        assertThat(concise.source()).isEqualTo("plugin");
        assertThat(byName(styles, "style-plugin:Verbatim").forceForPlugin())
            .as("force-for-plugin: \"false\" 字符串 → false（CC :68-69）")
            .isEqualTo(Boolean.FALSE);
        assertThat(byName(styles, "style-plugin:plain").forceForPlugin())
            .as("未声明 → undefined/null（CC :70）")
            .isNull();
    }

    @Test
    @DisplayName("outputStylesPaths 附加路径：单 .md 文件被扫描（loadPluginOutputStyles.ts:147-160）")
    void output_styles_paths_single_file() throws Exception {
        // WHY: CC :147-160 — outputStylesPaths 单 .md 文件经 loadOutputStyleFromFile 加载
        Path pluginRoot = tempDir.resolve("extra-style");
        Path customStyle = pluginRoot.resolve("custom-styles").resolve("swift.md");
        Files.createDirectories(customStyle.getParent());
        Files.writeString(customStyle, "---\nforce-for-plugin: true\n---\n\nbe swift");

        List<OutputStyleDirLoader.OutputStyle> styles = LoadPluginOutputStyles.load(List.of(
            new PluginLoader.LoadedPlugin("extra-style", PluginLoader.InstallSource.PATH,
                pluginRoot, System.currentTimeMillis(), true,
                null, List.of(), null, List.of(), null, List.of(),
                null, List.of(customStyle.toString()))));

        assertThat(styles.stream().map(OutputStyleDirLoader.OutputStyle::name))
            .as("附加单文件样式名 = plugin:文件名（CC :147-160）")
            .containsExactly("extra-style:swift");
        assertThat(styles.get(0).forceForPlugin()).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("合并链：mergeOutputStyles 按 name 覆盖，plugin 最低优先级（outputStyles.ts:137-175）")
    void merge_output_styles_plugin_lowest_priority() throws Exception {
        // WHY: CC getAllOutputStyles — allStyles 起于内置，按 plugin→user→project→managed 优先级
        //   同名后者覆盖（outputStyles.ts:158-172）。Java 无常量内置源 → 合并面 = plugin + 目录样式，
        //   目录样式覆盖同名 plugin 样式。若覆盖序反了，plugin 样式会吃掉用户定制。
        OutputStyleDirLoader.OutputStyle pluginConcise = new OutputStyleDirLoader.OutputStyle(
            "style-plugin:concise", "plugin desc", "plugin prompt", "plugin", null, Boolean.TRUE);
        OutputStyleDirLoader.OutputStyle customConcise = new OutputStyleDirLoader.OutputStyle(
            "style-plugin:concise", "custom desc", "custom prompt", "userSettings", null, null);

        List<OutputStyleDirLoader.OutputStyle> merged =
            OutputStyleDirLoader.mergeOutputStyles(List.of(pluginConcise), List.of(customConcise));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).prompt())
            .as("同名后者覆盖 → 目录样式覆盖 plugin 样式（outputStyles.ts:159-172 优先级）")
            .isEqualTo("custom prompt");
        assertThat(merged.get(0).source()).isEqualTo("userSettings");
    }

    /** 构造含默认 output-styles 目录的 LoadedPlugin。 */
    private static PluginLoader.LoadedPlugin plugin(String name, Path localPath) {
        return new PluginLoader.LoadedPlugin(name, PluginLoader.InstallSource.PATH,
            localPath, System.currentTimeMillis(), true,
            null, List.of(),
            localPath.resolve("commands"), List.of(),
            localPath.resolve("skills"), List.of(),
            localPath.resolve("output-styles"), List.of());
    }

    private static OutputStyleDirLoader.OutputStyle byName(List<OutputStyleDirLoader.OutputStyle> styles, String name) {
        return styles.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }
}
