package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Installed Plugins 文件层 · 对齐 CC {@code utils/plugins/installedPluginsManager.ts} 的
 * installed_plugins.json 读/写/migrate（V1→V2 单一文件）。
 *
 * <p>CC 对应（snake_case → camelCase，行号标注）：
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #getInstalledPluginsFilePath()}</td><td>{@code getInstalledPluginsFilePath}</td><td>installedPluginsManager.ts:78-80</td></tr>
 *   <tr><td>{@link #getInstalledPluginsV2FilePath()}</td><td>{@code getInstalledPluginsV2FilePath}</td><td>installedPluginsManager.ts:86-88</td></tr>
 *   <tr><td>{@link #loadFromDisk()}</td><td>{@code loadInstalledPluginsFromDisk}</td><td>installedPluginsManager.ts:502-524</td></tr>
 *   <tr><td>{@link #loadCached()}</td><td>{@code loadInstalledPluginsV2}</td><td>installedPluginsManager.ts:315-364</td></tr>
 *   <tr><td>{@link #save(InstalledPluginsFileV2)}</td><td>{@code saveInstalledPluginsV2}</td><td>installedPluginsManager.ts:370-394</td></tr>
 *   <tr><td>{@link #migrateToSinglePluginFile()}</td><td>{@code migrateToSinglePluginFile}</td><td>installedPluginsManager.ts:115-182</td></tr>
 *   <tr><td>{@link #migrateV1ToV2(Map)}</td><td>{@code migrateV1ToV2}</td><td>installedPluginsManager.ts:284-305</td></tr>
 *   <tr><td>{@link #addInstallation}</td><td>{@code addPluginInstallation}</td><td>installedPluginsManager.ts:406-443</td></tr>
 *   <tr><td>{@link #removeInstallation}</td><td>{@code removePluginInstallation}</td><td>installedPluginsManager.ts:452-475</td></tr>
 *   <tr><td>{@link #updateInstallationPathOnDisk}</td><td>{@code updateInstallationPathOnDisk}</td><td>installedPluginsManager.ts:537-587</td></tr>
 *   <tr><td>{@link #hasPendingUpdates(InstalledPluginsFileV2)}</td><td>{@code hasPendingUpdates}</td><td>installedPluginsManager.ts:595-618</td></tr>
 * </table>
 *
 * <h2>V2 文件结构（schemas.ts:1506-1568）</h2>
 * <pre>
 * {
 *   "version": 2,
 *   "plugins": {
 *     "plugin@marketplace": [
 *       { "scope": "user", "installPath": "...", "version": "1.0.0",
 *         "installedAt": "...", "lastUpdated": "...", "gitCommitSha": "..." }
 *     ]
 *   }
 * }
 * </pre>
 * 每条 {@link PluginInstallation} 是 CC {@code PluginInstallationEntrySchema}
 * （schemas.ts:1517-1542）的 Java 映射。
 *
 * <h2>scope 语义差异（ODF §8 显式映射决策）</h2>
 * CC {@code PluginScopeSchema}（schemas.ts:1506-1508）枚举 managed/user/project/local；
 * Java {@link InstalledPluginsManager.InstalledRecord#scope()} 存的是 InstallSource
 * （path/git/marketplace/npm，供 PluginLoader feed 用）。Java 无 CC scope 层级概念，
 * 落盘统一映射为 {@code user}（对齐 CC migrateV1ToV2 :294 “default all existing installs to user scope”），
 * 原始 InstallSource 经非 CC 扩展字段 {@code source} 持久化以保重启回读一致。
 *
 * <p>Java ODF 特有扩展字段（非 CC 契约，§8）：{@code source}/{@code enabled}/{@code agentsPath}/
 * {@code sourcePath}，均 {@code @JsonInclude(NON_NULL)}，无值时不出现在文件中。
 *
 * <h2>决策 D4 claude 读回落（Java 特有扩展，非 CC 契约）</h2>
 * CC 仅单一 plugins 目录（{@code join(getClaudeConfigHomeDir(), 'plugins')}）；nexusai 自有根
 * 分拆后，读侧经 {@link #loadInstalledPluginsWithClaudeFallback()} 合并：nexusai
 * {@link #getInstalledPluginsFilePath()} 优先（同 name first-wins），缺失名回落读 claude
 * {@link #getClaudeInstalledPluginsFilePath()}（=~/.claude/plugins，只读不迁移）。写侧
 * （{@link #save} 系列）仍只写 nexusai。
 */
@Component
public class InstalledPluginsFileStore {

    private static final Logger log = LoggerFactory.getLogger(InstalledPluginsFileStore.class);

    /**
     * [V61 · 2026-09-01 用户拍板] 插件双读开关 DB settings 列读源（settings.plugin_claude_fallback）。
     *  required=false：无 Spring 直构（POJO 测试）保持 null → {@link #isPluginClaudeFallback()} 回落
     *  默认 true（原 yml {@code nexusai.feature.plugin-claude-fallback:true} 语义，零行为变化）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SettingsMapper settingsMapper;

    /**
     * [V61] 插件双读开关实时读 DB settings 列：selectOneById(1) 读 plugin_claude_fallback；
     *  null / 未接线 / 异常 → 回落默认 true（原 yml :true 语义）。每次调用读 DB（前端改开关即生效，
     *  对齐 BundledSkillEnabledGates 静态 DB 读源惯例；POJO 单测零行为变化）。
     */
    private boolean isPluginClaudeFallback() {
        SettingsMapper mapper = this.settingsMapper;
        if (mapper == null) {
            return true;
        }
        try {
            SettingsRecord s = mapper.selectOneById(1);
            if (s != null && s.getPluginClaudeFallback() != null) {
                return s.getPluginClaudeFallback();
            }
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[InstalledPluginsFileStore] 读取 settings.plugin_claude_fallback 失败，回落默认 true: {}", e.toString());
            }
        }
        return true;
    }

    /** zod 默认 strip 未知字段（MPL1 progress concern #3），关闭 FAIL_ON_UNKNOWN_PROPERTIES 对齐。 */
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** CC installedPluginsManager.ts:58-59 migrationCompleted · 每次启动仅迁移一次。 */
    private volatile boolean migrationCompleted = false;

    /** CC :66 installedPluginsCacheV2 · 文件读缓存，磁盘变更时清空。 */
    private volatile InstalledPluginsFileV2 cacheV2;

    /**
     * V2 单条安装记录 · CC {@code PluginInstallationEntrySchema}（schemas.ts:1517-1542）。
     *
     * <p>非 CC 契约扩展字段：{@code source}（Java InstallSource）、{@code enabled}（Java feed 标志）、
     * {@code agentsPath}/{@code sourcePath}（Java ODF-C3R 特有），仅非空时序列化。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PluginInstallation(
        /** CC original: scope（schemas.ts:1519）· managed/user/project/local（Java 恒 user） */
        String scope,
        /** CC original: projectPath（schemas.ts:1520-1523）· project/local scope 必填 */
        String projectPath,
        /** CC original: installPath（schemas.ts:1524-1526）· 版本化插件目录绝对路径 */
        String installPath,
        /** CC original: version（schemas.ts:1528）· 已装版本 */
        String version,
        /** CC original: installedAt（schemas.ts:1529-1531）· ISO 8601 安装时间 */
        String installedAt,
        /** CC original: lastUpdated（schemas.ts:1533-1535）· ISO 8601 最后更新时间 */
        String lastUpdated,
        /** CC original: gitCommitSha（schemas.ts:1537-1540）· git 类插件 commit SHA */
        String gitCommitSha,
        /** [非 CC 契约] Java InstallSource：path/git/marketplace/npm */
        String source,
        /** [非 CC 契约] Java 生产 feed enabled 标志 */
        Boolean enabled,
        /** [非 CC 契约] Java ODF-C3R agents 目录 */
        String agentsPath,
        /** [非 CC 契约] Java ODF-C3R 插件本地安装目录 */
        String sourcePath) {

        public PluginInstallation {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(installPath, "installPath");
        }
    }

    /**
     * installed_plugins.json V2 文件结构 · CC {@code InstalledPluginsFileSchemaV2}（schemas.ts:1562-1569）
     * {@code {version:2, plugins: record(pluginId, array(PluginInstallationEntry))}}。
     */
    public record InstalledPluginsFileV2(int version, Map<String, List<PluginInstallation>> plugins) {

        public InstalledPluginsFileV2 {
            plugins = plugins == null ? new LinkedHashMap<>() : new LinkedHashMap<>(plugins);
        }

        /** 空 V2 结构（CC :351/:516 文件不存在或读失败 → 空 V2）。 */
        public static InstalledPluginsFileV2 empty() {
            return new InstalledPluginsFileV2(2, new LinkedHashMap<>());
        }
    }

    /**
     * V1 单条安装记录 · CC {@code InstalledPluginSchema}（schemas.ts:1446-1464）。
     * V1 的 plugins 是 pluginId → 单对象（非数组）。
     */
    public record V1Entry(
        String version, String installedAt, String lastUpdated, String installPath, String gitCommitSha) {
    }

    /**
     * installed_plugins.json 路径 · CC {@code getInstalledPluginsFilePath}（:78-80）
     * {@code join(getPluginsDirectory(), 'installed_plugins.json')}。
     *
     * <p>决策 D4（nexusai 复刻版 .claude 改造）：nexusai 新装写本文件（{@code ~/.{appName}/plugins}）；
     * claude 已装插件经 {@link #loadInstalledPluginsWithClaudeFallback()} 读
     * {@link #getClaudeInstalledPluginsFilePath()}（=~/.claude/plugins）只读兼容（不迁移文件）。
     */
    public static String getInstalledPluginsFilePath() {
        return Paths.get(PluginDirectories.getPluginsDirectory(), "installed_plugins.json").toString();
    }

    /**
     * claude 只读 installed_plugins.json 路径 · 决策 D4 读回落源
     * {@code join(getClaudePluginsDirectory(), 'installed_plugins.json')}（=~/.claude/plugins/
     * installed_plugins.json）。
     *
     * <p>仅作读取回落（兼容 claude 已装插件枚举，不迁移文件）；nexusai 写侧一律走
     * {@link #getInstalledPluginsFilePath()}。
     */
    public static String getClaudeInstalledPluginsFilePath() {
        return Paths.get(PluginDirectories.getClaudePluginsDirectory(), "installed_plugins.json").toString();
    }

    /**
     * legacy installed_plugins_v2.json 路径 · CC {@code getInstalledPluginsV2FilePath}（:86-88）。
     * 仅迁移期使用（合并进主文件后删除）。
     */
    public static String getInstalledPluginsV2FilePath() {
        return Paths.get(PluginDirectories.getPluginsDirectory(), "installed_plugins_v2.json").toString();
    }

    // =========================================================================
    // 读 / 写
    // =========================================================================

    /**
     * 直接读盘（绕过全部缓存）· CC {@code loadInstalledPluginsFromDisk}（:502-524）。
     * 后台更新器用来检测磁盘变化，不影响会话内存视图。
     *
     * <p>V2 → 校验返回；V1 → 内存转 V2 返回；文件缺失 → 空 V2；读失败 → 空 V2（不抛，fail loud 日志）。
     * 仅读 nexusai {@link #getInstalledPluginsFilePath()}（写侧/迁移侧真相源）；
     * claude 兼容读走 {@link #loadInstalledPluginsWithClaudeFallback()}。
     */
    public InstalledPluginsFileV2 loadFromDisk() {
        return readV2(Paths.get(getInstalledPluginsFilePath()), "installed_plugins.json");
    }

    /**
     * 决策 D4 读回落 · nexusai installed_plugins.json 优先 + claude ~/.claude/plugins 回落。
     *
     * <p>读侧合并语义（对齐 D4『claude 已装 ~/.claude/plugins 兼容读；nexusai 新装写
     * {@code ~/.{appName}/plugins}；读取 nexusai 优先』）：先读 nexusai
     * {@link #getInstalledPluginsFilePath()}（自有安装真相源），对 nexusai 缺失的插件名
     * 回落读 claude {@link #getClaudeInstalledPluginsFilePath()}（=~/.claude/plugins/
     * installed_plugins.json）作只读兼容源——不迁移文件，claude 插件代码仍在 claude cache，
     * 读侧直接引用其 installPath。同 name first-wins：nexusai 赢
     * （对齐内容读兼容去重优先级 managed &gt; nexusai &gt; claude）。
     *
     * <p>仅影响读取/枚举侧（PluginLoader feed / 命令 / agents / skills / output-styles / MCP）；
     * 写侧（{@link #save} / {@link #addInstallation} / {@link #removeInstallation} /
     * {@link #updateInstallationPathOnDisk}）仍只写 nexusai，claude 侧绝不写。
     *
     * @return 合并后 V2（nexusai 优先，claude 仅补 nexusai 缺失名）
     */
    public InstalledPluginsFileV2 loadInstalledPluginsWithClaudeFallback() {
        InstalledPluginsFileV2 nexusaiData = loadFromDisk();
        if (!isPluginClaudeFallback()) {
            // 开关关（DB settings.plugin_claude_fallback=false）：只读 nexusai 安装记录，不回落实 CC
            if (log.isDebugEnabled()) {
                log.debug("决策 D4 读回落开关关 → 仅 nexusai installed_plugins（{} 个插件）", nexusaiData.plugins().size());
            }
            return nexusaiData;
        }
        Map<String, List<PluginInstallation>> merged = new LinkedHashMap<>(nexusaiData.plugins());
        Path claudePath = Paths.get(getClaudeInstalledPluginsFilePath());
        if (!Files.exists(claudePath)) {
            if (log.isDebugEnabled()) {
                log.debug("决策 D4 读回落: claude installed_plugins.json 不存在，仅 nexusai（{} 个插件）",
                    nexusaiData.plugins().size());
            }
            return nexusaiData;
        }
        InstalledPluginsFileV2 claudeData = readV2(claudePath, "claude installed_plugins.json（D4 兼容读）");
        int added = 0;
        for (Map.Entry<String, List<PluginInstallation>> e : claudeData.plugins().entrySet()) {
            if (!merged.containsKey(e.getKey())) {
                merged.put(e.getKey(), e.getValue());
                added++;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("决策 D4 读回落: nexusai {} 插件 + claude 补 {} 插件 = 共 {}（~/.claude/plugins 兼容读，同 name nexusai 赢）",
                nexusaiData.plugins().size(), added, merged.size());
        }
        return new InstalledPluginsFileV2(2, merged);
    }

    /** 读取指定路径 installed_plugins 文件 → V2（V1 就地转 V2；缺失/读失败 → 空 V2，不抛）。 */
    private InstalledPluginsFileV2 readV2(Path path, String label) {
        try {
            String content;
            try {
                content = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // ENOENT 等 → 空 V2
                if (!Files.exists(path)) {
                    if (log.isDebugEnabled()) {
                        log.debug("{} 不存在，返回空 V2（CC :516）", label);
                    }
                    return InstalledPluginsFileV2.empty();
                }
                throw e;
            }
            int version = detectVersion(content);
            if (version == 2) {
                return JSON.readValue(content, InstalledPluginsFileV2.class);
            }
            Map<String, V1Entry> v1 = parseV1(content);
            return migrateV1ToV2(v1);
        } catch (Exception e) {
            log.warn("读取 {} 失败: {}，返回空 V2（CC :517-522）", label, e.getMessage());
            return InstalledPluginsFileV2.empty();
        }
    }

    /**
     * 带缓存的加载 · CC {@code loadInstalledPluginsV2}（:315-364）。
     * 缓存由 {@link #save} 更新、由 {@link #clearCache} 清空（磁盘被外部/后台变更时）。
     */
    public synchronized InstalledPluginsFileV2 loadCached() {
        if (cacheV2 != null) {
            return cacheV2;
        }
        cacheV2 = loadFromDisk();
        return cacheV2;
    }

    /**
     * V2 写盘（2 空格缩进 JSON，UTF-8）· CC {@code saveInstalledPluginsV2}（:370-394）。
     * 写盘后更新缓存。plugins 目录不存在则先创建（CC :375 mkdirSync）。
     */
    public synchronized void save(InstalledPluginsFileV2 data) {
        try {
            Path mainPath = Paths.get(getInstalledPluginsFilePath());
            Files.createDirectories(mainPath.getParent());
            DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
            pp = pp.withObjectIndenter(new DefaultIndenter("  ", "\n"));
            String json = JSON.writer(pp).writeValueAsString(data);
            Files.writeString(mainPath, json, StandardCharsets.UTF_8);
            cacheV2 = data;
            if (log.isDebugEnabled()) {
                log.debug("已写盘 installed_plugins.json：{} 个插件（CC :386-388）", data.plugins().size());
            }
        } catch (Exception e) {
            log.error("写盘 installed_plugins.json 失败: {}", e.getMessage(), e);
            throw new IllegalStateException("无法保存 installed_plugins.json: " + e.getMessage(), e);
        }
    }

    /** 清空文件读缓存（磁盘被外部/后台修改后强制重读）。 */
    public synchronized void clearCache() {
        cacheV2 = null;
    }

    // =========================================================================
    // 迁移（V1→V2 单一文件）
    // =========================================================================

    /**
     * 启动迁移到单一文件格式 · CC {@code migrateToSinglePluginFile}（:115-182）。
     *
     * <p>1. installed_plugins_v2.json 存在 → rename 合并进主文件 + 清理 legacy cache；
     * 2. 仅主文件且 version=1 → 就地转 V2（version=2、全 user scope、versioned cache path）；
     * 3. 均不存在 → 无事。每次启动仅执行一次（:116-118）。
     */
    public synchronized void migrateToSinglePluginFile() {
        if (migrationCompleted) {
            return;
        }
        Path mainPath = Paths.get(getInstalledPluginsFilePath());
        Path v2Path = Paths.get(getInstalledPluginsV2FilePath());
        try {
            // Case 1: v2 存在 → rename 进主文件（CC :125-138）
            if (Files.exists(v2Path)) {
                Files.move(v2Path, mainPath, StandardCopyOption.REPLACE_EXISTING);
                log.info("已将 installed_plugins_v2.json 重命名为 installed_plugins.json（CC :127）");
                InstalledPluginsFileV2 v2Data = loadFromDisk();
                cleanupLegacyCache(v2Data);
                migrationCompleted = true;
                return;
            }
            // Case 2: v2 缺 → 读主文件（CC :140-149）
            if (!Files.exists(mainPath)) {
                // Case 3: 均不存在，无事（CC :146-149）
                migrationCompleted = true;
                return;
            }
            String content = Files.readString(mainPath, StandardCharsets.UTF_8);
            if (detectVersion(content) == 1) {
                Map<String, V1Entry> v1 = parseV1(content);
                InstalledPluginsFileV2 v2Data = migrateV1ToV2(v1);
                save(v2Data); // 就地覆盖主文件为 V2（CC :159-162）
                log.info("已就地转换 installed_plugins.json V1→V2：{} 个插件（CC :163-165）", v1.size());
                cleanupLegacyCache(v2Data);
            }
            // version=2 无需动作（CC :170）
            migrationCompleted = true;
        } catch (Exception e) {
            log.warn("迁移 installed_plugins.json 失败: {}（标记完成避免重试，CC :173-181）", e.getMessage());
            migrationCompleted = true;
        }
    }

    /**
     * V1 → V2 转换 · CC {@code migrateV1ToV2}（:284-305）。
     * V1 每插件 → 数组单条：scope=user（V1 无 scope 概念）、installPath=versioned cache path、
     * 保留 version/installedAt/lastUpdated/gitCommitSha。
     */
    public InstalledPluginsFileV2 migrateV1ToV2(Map<String, V1Entry> v1Plugins) {
        Map<String, List<PluginInstallation>> v2 = new LinkedHashMap<>();
        for (Map.Entry<String, V1Entry> e : v1Plugins.entrySet()) {
            V1Entry p = e.getValue();
            String versionedCachePath = getVersionedCachePath(e.getKey(), p.version());
            v2.put(e.getKey(), List.of(new PluginInstallation(
                "user", null, versionedCachePath,
                p.version(), p.installedAt(), p.lastUpdated(), p.gitCommitSha(),
                null, null, null, null)));
        }
        if (log.isDebugEnabled()) {
            log.debug("V1→V2 转换：{} 个插件（CC :284-305）", v2.size());
        }
        return new InstalledPluginsFileV2(2, v2);
    }

    /**
     * 清理 legacy 非版本化 cache 目录 · CC {@code cleanupLegacyCache}（:192-245）。
     *
     * <p>legacy 结构：{@code plugins/cache/{plugin}}（扁平）；版本化结构：
     * {@code plugins/cache/{marketplace}/{plugin}/{version}}（嵌套 3 层）。未被任何安装引用
     * 的扁平目录删除；版本化结构跳过。
     */
    void cleanupLegacyCache(InstalledPluginsFileV2 v2Data) {
        Path cachePath = Paths.get(PluginDirectories.getPluginsDirectory(), "cache");
        if (!Files.isDirectory(cachePath)) {
            return;
        }
        Set<String> referenced = new java.util.HashSet<>();
        for (List<PluginInstallation> installs : v2Data.plugins().values()) {
            for (PluginInstallation e : installs) {
                if (e.installPath() != null) {
                    referenced.add(e.installPath());
                }
            }
        }
        List<Path> toDelete = new ArrayList<>();
        try (var entries = Files.list(cachePath)) {
            for (Path dir : (Iterable<Path>) entries.filter(Files::isDirectory)::iterator) {
                String dirPath = dir.toString();
                if (referenced.contains(dirPath)) {
                    continue;
                }
                if (isVersionedStructure(dir)) {
                    continue;
                }
                toDelete.add(dir);
            }
        } catch (IOException e) {
            log.warn("清理 legacy cache 失败: {}（CC :239-244）", e.getMessage());
        }
        // 先收集后删除，避免迭代 DirectoryStream 时删除导致并发修改异常
        for (Path dir : toDelete) {
            deleteRecursively(dir);
            log.info("已清理 legacy 非版本化 cache 目录: {}", dir.getFileName());
        }
    }

    /**
     * 判断 cache 顶层目录是否为版本化结构（含 marketplace/plugin/version 嵌套）。
     * 对齐 CC :217-224（顶层下有子目录，且某子目录下还有目录）。
     */
    private static boolean isVersionedStructure(Path top) {
        try (var subDirs = Files.list(top)) {
            for (Path sub : (Iterable<Path>) subDirs.filter(Files::isDirectory)::iterator) {
                try (var versionDirs = Files.list(sub)) {
                    if (versionDirs.anyMatch(Files::isDirectory)) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
            // 无法读 → 保守视为版本化，跳过删除
            return true;
        }
        return false;
    }

    private static void deleteRecursively(Path dir) {
        try {
            Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException e) {
            log.warn("删除 legacy cache 目录失败: {}（{}）", dir, e.getMessage());
        }
    }

    // =========================================================================
    // 增 / 删 / 改（写穿磁盘）
    // =========================================================================

    /**
     * 新增/更新某 scope 的安装记录 · CC {@code addPluginInstallation}（:406-443）。
     *
     * <p>从磁盘加载，按 scope+projectPath 匹配已存在条目（同插件不同 scope/版本共存），
     * 命中更新、未命中追加，随后写盘。metadata.version/installedAt 可选；lastUpdated 恒为 now。
     *
     * @param pluginId    插件 ID（Java 侧为插件名）
     * @param scope       CC scope（managed/user/project/local；Java 映射恒 user）
     * @param installPath 版本化插件目录绝对路径
     * @param metadata    额外安装元数据（version/installedAt/gitCommitSha 可空）
     * @param projectPath project/local scope 的项目路径（可空）
     */
    public void addInstallation(String pluginId, String scope, String installPath,
                                PluginInstallation metadata, String projectPath) {
        InstalledPluginsFileV2 data = loadFromDisk();
        List<PluginInstallation> installations = data.plugins().get(pluginId);
        List<PluginInstallation> list = installations == null ? new ArrayList<>() : new ArrayList<>(installations);

        int existingIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            PluginInstallation entry = list.get(i);
            if (Objects.equals(entry.scope(), scope) && Objects.equals(entry.projectPath(), projectPath)) {
                existingIndex = i;
                break;
            }
        }

        String now = java.time.Instant.now().toString();
        PluginInstallation newEntry = new PluginInstallation(
            scope,
            projectPath,
            installPath,
            metadata.version(),
            metadata.installedAt() != null ? metadata.installedAt() : now,
            now,
            metadata.gitCommitSha(),
            metadata.source(),
            metadata.enabled(),
            metadata.agentsPath(),
            metadata.sourcePath());

        if (existingIndex >= 0) {
            list.set(existingIndex, newEntry);
            if (log.isDebugEnabled()) {
                log.debug("已更新安装记录 {} scope={}（CC :433-435）", pluginId, scope);
            }
        } else {
            list.add(newEntry);
            if (log.isDebugEnabled()) {
                log.debug("已新增安装记录 {} scope={}（CC :436-438）", pluginId, scope);
            }
        }

        Map<String, List<PluginInstallation>> plugins = new LinkedHashMap<>(data.plugins());
        plugins.put(pluginId, list);
        save(new InstalledPluginsFileV2(2, plugins));
    }

    /**
     * 删除某 scope 的安装记录 · CC {@code removePluginInstallation}（:452-475）。
     * 该插件无剩余安装则整键删除（CC :468-471）。
     */
    public void removeInstallation(String pluginId, String scope, String projectPath) {
        InstalledPluginsFileV2 data = loadFromDisk();
        List<PluginInstallation> installations = data.plugins().get(pluginId);
        if (installations == null) {
            return;
        }
        List<PluginInstallation> remaining = installations.stream()
            .filter(e -> !(Objects.equals(e.scope(), scope) && Objects.equals(e.projectPath(), projectPath)))
            .collect(Collectors.toCollection(ArrayList::new));

        Map<String, List<PluginInstallation>> plugins = new LinkedHashMap<>(data.plugins());
        if (remaining.isEmpty()) {
            plugins.remove(pluginId);
            if (log.isDebugEnabled()) {
                log.debug("已删除安装记录 {}（无剩余安装整键删除，CC :468-471）", pluginId);
            }
        } else {
            plugins.put(pluginId, remaining);
        }
        save(new InstalledPluginsFileV2(2, plugins));
    }

    /**
     * 仅改盘不改内存 · CC {@code updateInstallationPathOnDisk}（:537-587）。
     *
     * <p>后台更新器下载新版本后，把磁盘上的 installPath/version/lastUpdated 指向新版本目录，
     * 会话内存视图（manager 内存 Map）保持旧版本。写盘后清缓存但不动内存（CC :575-576/:586）。
     */
    public void updateInstallationPathOnDisk(String pluginId, String scope, String projectPath,
                                             String newPath, String newVersion, String gitCommitSha) {
        InstalledPluginsFileV2 diskData = loadFromDisk();
        List<PluginInstallation> installations = diskData.plugins().get(pluginId);
        if (installations == null) {
            if (log.isDebugEnabled()) {
                log.debug("无法更新 {}：磁盘无此插件（CC :548-553）", pluginId);
            }
            return;
        }
        int idx = -1;
        for (int i = 0; i < installations.size(); i++) {
            PluginInstallation e = installations.get(i);
            if (Objects.equals(e.scope(), scope) && Objects.equals(e.projectPath(), projectPath)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            if (log.isDebugEnabled()) {
                log.debug("无法更新 {}：scope={} 无安装记录（CC :581-585）", pluginId, scope);
            }
            return;
        }
        PluginInstallation old = installations.get(idx);
        String now = java.time.Instant.now().toString();
        PluginInstallation updated = new PluginInstallation(
            old.scope(), old.projectPath(), newPath, newVersion,
            old.installedAt(), now,
            gitCommitSha != null ? gitCommitSha : old.gitCommitSha(),
            old.source(), old.enabled(), old.agentsPath(), old.sourcePath());
        List<PluginInstallation> list = new ArrayList<>(installations);
        list.set(idx, updated);
        Map<String, List<PluginInstallation>> plugins = new LinkedHashMap<>(diskData.plugins());
        plugins.put(pluginId, list);
        save(new InstalledPluginsFileV2(2, plugins));
        // 注意：save 已更新 cacheV2，但 manager 内存视图（inMemory）不受影响（CC :586）
        log.info("已更新 {} 磁盘版本 {} → {}（CC :578-580）", pluginId, old.version(), newVersion);
    }

    /**
     * 检测待处理更新（磁盘 ≠ 内存）· CC {@code hasPendingUpdates}（:595-618）。
     * 磁盘某条目在内存中存在同 scope+projectPath 匹配且 installPath 不同 → true。
     */
    public boolean hasPendingUpdates(InstalledPluginsFileV2 memoryState) {
        InstalledPluginsFileV2 diskState = loadFromDisk();
        for (Map.Entry<String, List<PluginInstallation>> diskEntry : diskState.plugins().entrySet()) {
            List<PluginInstallation> memoryInstalls = memoryState.plugins().get(diskEntry.getKey());
            if (memoryInstalls == null) {
                continue;
            }
            for (PluginInstallation diskInstall : diskEntry.getValue()) {
                for (PluginInstallation memoryInstall : memoryInstalls) {
                    if (Objects.equals(memoryInstall.scope(), diskInstall.scope())
                        && Objects.equals(memoryInstall.projectPath(), diskInstall.projectPath())
                        && !Objects.equals(memoryInstall.installPath(), diskInstall.installPath())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // =========================================================================
    // 辅助
    // =========================================================================

    /**
     * 版本化 cache 路径 · CC {@code getVersionedCachePath}（pluginLoader.ts:172-176）：
     * {@code pluginsDir/cache/{marketplace}/{plugin}/{version}}，各段 sanitize。
     */
    public static String getVersionedCachePath(String pluginId, String version) {
        PluginIdentifier.Parsed parsed = PluginIdentifier.parse(pluginId);
        String marketplace = parsed.marketplace() == null || parsed.marketplace().isEmpty()
            ? "unknown" : parsed.marketplace();
        String sanitizedMarketplace = marketplace.replaceAll("[^a-zA-Z0-9\\-_]", "-");
        String sanitizedPlugin = (parsed.name() == null || parsed.name().isEmpty() ? pluginId : parsed.name())
            .replaceAll("[^a-zA-Z0-9\\-_]", "-");
        String sanitizedVersion = version == null ? "unknown" : version.replaceAll("[^a-zA-Z0-9\\-_.]", "-");
        return Paths.get(PluginDirectories.getPluginsDirectory(), "cache",
            sanitizedMarketplace, sanitizedPlugin, sanitizedVersion).toString();
    }

    /** 探测文件版本：version 数值型 → 该值；否则 V1（CC :276）。 */
    private static int detectVersion(String content) {
        try {
            Object root = JSON.readValue(content, Object.class);
            if (root instanceof Map<?, ?> map && map.get("version") instanceof Number n) {
                return n.intValue();
            }
        } catch (IOException e) {
            // 解析失败走调用方 catch → 空 V2
        }
        return 1;
    }

    /** 解析 V1 文件体 → {@code pluginId → V1Entry}（结构校验 CC InstalledPluginsFileSchemaV1 schemas.ts:1482-1491）。 */
    private static Map<String, V1Entry> parseV1(String content) throws IOException {
        Map<String, V1Entry> out = new LinkedHashMap<>();
        Object root = JSON.readValue(content, Object.class);
        if (!(root instanceof Map<?, ?> map) || !(map.get("plugins") instanceof Map<?, ?> plugins)) {
            return out;
        }
        for (Map.Entry<?, ?> e : plugins.entrySet()) {
            String id = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> p)) {
                continue;
            }
            out.put(id, new V1Entry(
                str(p.get("version")), str(p.get("installedAt")),
                str(p.get("lastUpdated")), str(p.get("installPath")), str(p.get("gitCommitSha"))));
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
