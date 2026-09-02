package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Plugin 目录解析 · 对齐 CC {@code utils/plugins/pluginDirectories.ts}.
 *
 * <h2>CC 对应（snake_case → camelCase，行号标注）</h2>
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #getPluginsDirectory()}</td><td>{@code getPluginsDirectory}</td><td>pluginDirectories.ts:53-63</td></tr>
 *   <tr><td>{@link #getPluginSeedDirs()}</td><td>{@code getPluginSeedDirs}</td><td>pluginDirectories.ts:85-90</td></tr>
 *   <tr><td>{@link #expandTilde(String)}</td><td>{@code expandTilde}</td><td>utils/permissions/pathValidation.ts:80-89</td></tr>
 * </table>
 *
 * <p><b>getPluginsDirectory</b>（pluginDirectories.ts:53-63）：优先级
 * {@code CLAUDE_CODE_PLUGIN_CACHE_DIR} env → {@code join(getNexusaiConfigHomeDir(), 'plugins')}
 * （决策 D4：nexusai 新装写 {@code ~/.{appName}/plugins}，读 nexusai 优先）；
 * cowork 变体 {@code CLAUDE_CODE_USE_COWORK_PLUGINS} truthy → {@code cowork_plugins}（:34-44）。
 * <p><b>D4 claude 读兼容</b>：{@link #getClaudePluginsDirectory()} 返回
 * {@code join(getClaudeConfigHomeDir(), 'plugins')}（=~/.claude/plugins）作只读回落源——
 * claude 已装插件兼容读（不迁移文件）；nexusai 新装只写 nexusai 侧。
 * <b>读侧已接线</b>：{@code InstalledPluginsFileStore.loadInstalledPluginsWithClaudeFallback()}
 * 在 nexusai installed_plugins.json 缺失名下回落读 {@code getClaudePluginsDirectory()}
 * 下的 installed_plugins.json → claude 已装插件经 PluginLoader feed 可枚举（命令/agents/skills/
 * output-styles/MCP）。
 * <p><b>getPluginSeedDirs</b>（pluginDirectories.ts:85-90）：{@code CLAUDE_CODE_PLUGIN_SEED_DIR}
 * 按平台路径分隔符（win={@code ;}）拆分、过滤空、逐项 expandTilde，PATH 序第一种子胜出。
 * <p><b>expandTilde</b>（pathValidation.ts:80-89）：{@code ~} / {@code ~/}（win 另含 {@code ~\}）→
 * {@code homedir() + slice(1)}；不支持 {@code ~user} 展开（安全考虑）。
 *
 * <p><b>测试覆写</b>：Java 无法进程内改 env，故提供 {@link #setPluginCacheDirOverride} /
 * {@link #setPluginSeedDirOverride} / {@link #setUseCoworkPluginsOverride} 静态覆写。
 */
public final class PluginDirectories {

    private static final Logger log = LoggerFactory.getLogger(PluginDirectories.class);

    /** CC pluginDirectories.ts:22 PLUGINS_DIR. */
    private static final String PLUGINS_DIR = "plugins";
    /** CC pluginDirectories.ts:23 COWORK_PLUGINS_DIR. */
    private static final String COWORK_PLUGINS_DIR = "cowork_plugins";

    /** 测试覆写：CLAUDE_CODE_PLUGIN_CACHE_DIR，null = 未覆写。 */
    private static volatile String pluginCacheDirOverride;
    /** 测试覆写：CLAUDE_CODE_PLUGIN_SEED_DIR，null = 未覆写。 */
    private static volatile String pluginSeedDirOverride;
    /** 测试覆写：CLAUDE_CODE_USE_COWORK_PLUGINS，null = 未覆写。 */
    private static volatile String useCoworkPluginsOverride;

    private PluginDirectories() {
        // 静态工具类
    }

    /**
     * 获取 plugins 根目录 · CC original: {@code getPluginsDirectory}（pluginDirectories.ts:53-63）
     * <pre>
     * const envOverride = process.env.CLAUDE_CODE_PLUGIN_CACHE_DIR
     * if (envOverride) return expandTilde(envOverride)
     * return join(getNexusaiConfigHomeDir(), getPluginsDirectoryName())
     * </pre>
     *
     * <p><b>决策 D4</b>：默认基址改 nexusai 自有根 {@code ~/.{appName}/plugins}（读优先 + 新装写 nexusai）；
     * claude 已装插件兼容读走 {@link #getClaudePluginsDirectory()}（=~/.claude/plugins 回落源，不迁移文件）。
     *
     * @return plugins 根目录绝对路径（env 覆写时 = expandTilde 结果）
     */
    public static String getPluginsDirectory() {
        String env = envOrOverride("CLAUDE_CODE_PLUGIN_CACHE_DIR", pluginCacheDirOverride);
        if (env != null && !env.isBlank()) {
            String expanded = expandTilde(env);
            if (log.isDebugEnabled()) {
                log.debug("插件根目录（CLAUDE_CODE_PLUGIN_CACHE_DIR）：{}", expanded);
            }
            return expanded;
        }
        String dir = Paths.get(NexusaiPaths.getAppConfigHomeDir(), getPluginsDirectoryName()).toString();
        if (log.isDebugEnabled()) {
            log.debug("插件根目录（默认 nexusai）：{}", dir);
        }
        return dir;
    }

    /**
     * claude 只读插件目录 · 决策 D4（nexusai 复刻版 .claude 改造）：claude 已装
     * {@code ~/.claude/plugins} 兼容读（仅回落读取源，不迁移文件）；nexusai 新装写
     * {@link #getPluginsDirectory()}（{@code ~/.{appName}/plugins}）。
     *
     * <p><b>读侧接线</b>：{@code InstalledPluginsFileStore.getClaudeInstalledPluginsFilePath()}
     * 经本方法定位 claude installed_plugins.json；nexusai installed_plugins.json 缺失名下回落
     * 读取 → claude 已装插件进入 PluginLoader feed（命令/agents/skills/output-styles/MCP 可加载）。
     * 本方法本身为纯路径解析，无文件副作用。
     *
     * <p>CC original: {@code join(getClaudeConfigHomeDir(), getPluginsDirectoryName())}
     * （pluginDirectories.ts:53-63 的 configHome 分支 · envUtils.ts:7-14 getClaudeConfigHomeDir）。
     *
     * @return claude 插件根目录绝对路径（=~/.claude/plugins，cowork 变体时 cowork_plugins）
     */
    public static String getClaudePluginsDirectory() {
        String dir = Paths.get(ClaudePaths.getClaudeConfigHomeDir(), getPluginsDirectoryName()).toString();
        if (log.isDebugEnabled()) {
            log.debug("claude 只读插件目录（D4 兼容读）：{}", dir);
        }
        return dir;
    }

    /**
     * 获取只读 plugin seed 目录列表（PATH 序，第一命中胜出）· CC original: {@code getPluginSeedDirs}
     * （pluginDirectories.ts:85-90）
     * <pre>
     * const raw = process.env.CLAUDE_CODE_PLUGIN_SEED_DIR
     * if (!raw) return []
     * return raw.split(delimiter).filter(Boolean).map(expandTilde)
     * </pre>
     *
     * @return 绝对路径 seed 目录列表（按优先级序，未配置 → 空列表）
     */
    public static List<String> getPluginSeedDirs() {
        String raw = envOrOverride("CLAUDE_CODE_PLUGIN_SEED_DIR", pluginSeedDirOverride);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String sep = File.pathSeparator; // win=';' unix=':'
        List<String> dirs = new ArrayList<>();
        for (String part : raw.split(Pattern.quote(sep))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                dirs.add(expandTilde(trimmed));
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("CLAUDE_CODE_PLUGIN_SEED_DIR 解析出 {} 个 seed 目录：{}", dirs.size(), dirs);
        }
        return dirs;
    }

    /**
     * 展开 {@code ~} 为用户 home · CC original: {@code expandTilde}（pathValidation.ts:80-89）
     * <pre>
     * if (path === '~' || path.startsWith('~/') ||
     *     (win32 && path.startsWith('~\\'))) return homedir() + path.slice(1)
     * return path
     * </pre>
     *
     * <p>不支持 {@code ~user} 展开（CC :78 安全考虑）。
     *
     * @param path 原始路径
     * @return 展开后路径；null 输入 → null
     */
    public static String expandTilde(String path) {
        if (path == null) {
            return null;
        }
        if (path.equals("~")
            || path.startsWith("~/")
            || (File.separatorChar == '\\' && path.startsWith("~\\"))) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    /** CC 会话状态（--cowork）无 Java 等价；env {@code CLAUDE_CODE_USE_COWORK_PLUGINS} truthy → cowork_plugins（:34-44）。 */
    private static String getPluginsDirectoryName() {
        String v = envOrOverride("CLAUDE_CODE_USE_COWORK_PLUGINS", useCoworkPluginsOverride);
        return isEnvTruthy(v) ? COWORK_PLUGINS_DIR : PLUGINS_DIR;
    }

    /**
     * Persistent per-plugin data 目录 · CC original: {@code getPluginDataDir}（pluginDirectories.ts:119-127）
     * + {@code pluginDataDirPath}（:98-99）。
     *
     * <p>{@code join(getPluginsDirectory(), 'data', sanitizePluginId(pluginId))}——与 version-scoped
     * install cache（${CLAUDE_PLUGIN_ROOT}）不同，data 目录在插件更新后仍存活（只随 uninstall 删除）。
     * 对齐 CC：{@code sanitizePluginId}（:92-96）把非 {@code [a-zA-Z0-9\-_]} 字符替换为 {@code '-'}。
     * <b>不 mkdir</b>（CC mkdir 在调用点 lazy；纯路径解析无副作用，供 ${CLAUDE_PLUGIN_DATA} 替换）。
     *
     * @param pluginId 插件 source/ID（CC plugin.source）
     * @return data 目录绝对路径（未创建）
     */
    public static String getPluginDataDir(String pluginId) {
        String dir = Paths.get(getPluginsDirectory(), "data", sanitizePluginId(pluginId)).toString();
        if (log.isDebugEnabled()) {
            log.debug("插件 data 目录（${CLAUDE_PLUGIN_DATA}）: {}", dir);
        }
        return dir;
    }

    /** CC original: {@code sanitizePluginId}（pluginDirectories.ts:92-96）· 非 [a-zA-Z0-9\-_] → '-'。 */
    private static String sanitizePluginId(String pluginId) {
        if (pluginId == null) {
            return "";
        }
        return pluginId.replaceAll("[^a-zA-Z0-9\\-_]", "-");
    }

    /**
     * CC original: {@code isEnvTruthy}（envUtils.ts:32-37）
     * {@code ['1','true','yes','on'].includes(value.toLowerCase().trim())}。
     */
    static boolean isEnvTruthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    private static String envOrOverride(String envName, String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return System.getenv(envName);
    }

    // ── 测试覆写 setter（Java 无法进程内改 env）──

    /** 覆写 CLAUDE_CODE_PLUGIN_CACHE_DIR（测试用）。传 null 清除覆写。 */
    public static void setPluginCacheDirOverride(String override) {
        pluginCacheDirOverride = override;
    }

    /** 覆写 CLAUDE_CODE_PLUGIN_SEED_DIR（测试用）。传 null 清除覆写。 */
    public static void setPluginSeedDirOverride(String override) {
        pluginSeedDirOverride = override;
    }

    /** 覆写 CLAUDE_CODE_USE_COWORK_PLUGINS（测试用）。传 null 清除覆写。 */
    public static void setUseCoworkPluginsOverride(String override) {
        useCoworkPluginsOverride = override;
    }
}
