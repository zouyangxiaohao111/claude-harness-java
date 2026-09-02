package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import com.nexusai.application.agent.plugin.PluginSchemas.ValidationResult;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marketplace Manager · 对齐 CC {@code utils/plugins/marketplaceManager.ts}.
 *
 * <p><b>L1 配置层</b>：文件态 known_marketplaces.json + 意图态 settings.extraKnownMarketplaces 双层配置。
 * 旧 Java 实现是纯内存 Map 孤儿（{@code add/remove/update/get/list} 零调用方），本版本按 CC 重写为
 * 文件读写 + schema 校验 + seed 注册 + settings 写入。
 *
 * <h2>CC 对应</h2>
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #getKnownMarketplacesFile()}</td><td>{@code getKnownMarketplacesFile}</td><td>marketplaceManager.ts:102-104</td></tr>
 *   <tr><td>{@link #getMarketplacesCacheDir()}</td><td>{@code getMarketplacesCacheDir}</td><td>marketplaceManager.ts:110-112</td></tr>
 *   <tr><td>{@link #loadKnownMarketplacesConfig()}</td><td>{@code loadKnownMarketplacesConfig}</td><td>marketplaceManager.ts:264-298</td></tr>
 *   <tr><td>{@link #loadKnownMarketplacesConfigSafe()}</td><td>{@code loadKnownMarketplacesConfigSafe}</td><td>marketplaceManager.ts:309-317</td></tr>
 *   <tr><td>{@link #saveKnownMarketplacesConfig(Map)}</td><td>{@code saveKnownMarketplacesConfig}</td><td>marketplaceManager.ts:327-350</td></tr>
 *   <tr><td>{@link #registerSeedMarketplaces()}</td><td>{@code registerSeedMarketplaces}</td><td>marketplaceManager.ts:380-434</td></tr>
 *   <tr><td>{@link #saveMarketplaceToSettings(String, KnownMarketplace, String)}</td><td>{@code saveMarketplaceToSettings}</td><td>marketplaceManager.ts:226-238</td></tr>
 * </table>
 *
 * <p><b>损坏保护</b>（CC :301-308 注释）：load→mutate→save 路径必须用抛错版
 * {@link #loadKnownMarketplacesConfig()}，返回 {} 的 Safe 版会覆盖损坏文件丢用户条目。
 */
@Component
public class MarketplaceManager {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceManager.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** settings 写通道（{@link #saveMarketplaceToSettings}）· CC updateSettingsForSource；null = 未接线（日志降级）。 */
    private ConfigStorage configStorage;

    /** git 同步服务（L2）· CC cacheMarketplaceFromGit/gitPull/gitClone；测试可注入 mock。 */
    private MarketplaceSyncService syncService;

    /**
     * getMarketplace per-name memoize · CC {@code getMarketplace = memoize(...)}（marketplaceManager.ts:2122）。
     * Java 无 lodash memoize，自实现 Map（会话提示词 §8）；refresh 删 key 失效（CC :2380）；
     * 并发经 computeIfAbsent 去重防重复拉取。
     */
    private final Map<String, PluginMarketplace.Marketplace> marketplaceMemoCache = new ConcurrentHashMap<>();

    /**
     * CLAUDE_CODE_REMOTE 判定 · CC refreshMarketplace:2476（CCR 恒 HTTPS，无 SSH key）。
     * 默认读 env，测试可覆写。
     */
    private java.util.function.BooleanSupplier remoteModeCheck = () ->
        PluginDirectories.isEnvTruthy(System.getenv("CLAUDE_CODE_REMOTE"));

    @Autowired(required = false)
    public void setConfigStorage(ConfigStorage configStorage) {
        this.configStorage = configStorage;
    }

    @Autowired(required = false)
    public void setSyncService(MarketplaceSyncService syncService) {
        this.syncService = syncService;
    }

    /** 测试覆写：设置 git 同步服务（替代 Spring 装配）。 */
    public void setSyncServiceForTest(MarketplaceSyncService syncService) {
        this.syncService = syncService;
    }

    /** 测试覆写：模拟 CLAUDE_CODE_REMOTE。 */
    public void setRemoteModeCheckForTest(java.util.function.BooleanSupplier check) {
        this.remoteModeCheck = check;
    }

    private MarketplaceSyncService sync() {
        if (syncService == null) {
            syncService = new MarketplaceSyncService();
        }
        return syncService;
    }

    // ── 文件路径 ─────────────────────────────────────────────────────────

    /** known_marketplaces.json 路径 · CC original: {@code getKnownMarketplacesFile}（:102-104）. */
    public String getKnownMarketplacesFile() {
        return Paths.get(PluginDirectories.getPluginsDirectory(), "known_marketplaces.json").toString();
    }

    /** marketplaces 缓存目录 · CC original: {@code getMarketplacesCacheDir}（:110-112）. */
    public String getMarketplacesCacheDir() {
        return Paths.get(PluginDirectories.getPluginsDirectory(), "marketplaces").toString();
    }

    // ── load ─────────────────────────────────────────────────────────────

    /**
     * 从磁盘读取 known_marketplaces.json · CC original: {@code loadKnownMarketplacesConfig}（:264-298）。
     *
     * <p>缺失（ENOENT）→ 空 Map；损坏（schema 不合法）→ {@link ConfigParseError}（不覆盖文件）；
     * JSON 语法错误 / I/O 错误 → {@link RuntimeException}（对齐 CC 非 ConfigParseError 分支 :292-296）。
     */
    public Map<String, KnownMarketplace> loadKnownMarketplacesConfig() {
        Path configFile = Paths.get(getKnownMarketplacesFile());
        String content;
        try {
            content = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            if (log.isDebugEnabled()) {
                log.debug("known_marketplaces.json 不存在，返回空配置：{}", configFile);
            }
            return new LinkedHashMap<>();
        } catch (IOException e) {
            String msg = "Failed to load marketplace configuration: " + e.getMessage();
            log.error(msg);
            throw new RuntimeException(msg, e);
        }
        return parseKnownMarketplacesFile(content, configFile.toString());
    }

    /**
     * 读取 known_marketplaces.json，任何错误 → 空 Map（不抛）· CC original:
     * {@code loadKnownMarketplacesConfigSafe}（:309-317）。
     *
     * <p>仅限只读路径使用（插件加载 / 特性开关）；load→mutate→save 路径禁止（会覆盖损坏文件）。
     */
    public Map<String, KnownMarketplace> loadKnownMarketplacesConfigSafe() {
        try {
            return loadKnownMarketplacesConfig();
        } catch (Exception e) {
            // 内层已日志；损坏的用户配置非 CC 缺陷，不升级为 error
            log.warn("读取 known_marketplaces.json 降级为空配置：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 解析 + schema 校验文件内容。
     *
     * @throws ConfigParseError JSON 结构合法但 schema 不合法（对齐 CC :275-281）
     */
    private Map<String, KnownMarketplace> parseKnownMarketplacesFile(String content, String filePath) {
        JsonNode node;
        try {
            node = JSON.readTree(content);
        } catch (IOException e) {
            String msg = "Failed to load marketplace configuration: " + e.getMessage();
            log.error(msg);
            throw new RuntimeException(msg, e);
        }
        if (node == null || !node.isObject()) {
            String msg = "Marketplace configuration file is corrupted: root is not an object";
            log.error(msg);
            throw new ConfigParseError(msg, filePath, null);
        }
        Map<String, KnownMarketplace> parsed;
        try {
            parsed = JSON.convertValue(node, new TypeReference<Map<String, KnownMarketplace>>() { });
        } catch (Exception conv) {
            String msg = "Marketplace configuration file is corrupted: " + conv.getMessage();
            log.error(msg);
            throw new ConfigParseError(msg, filePath, node);
        }
        ValidationResult vr = PluginSchemas.validateKnownMarketplaceFile(parsed);
        if (!vr.valid()) {
            String msg = "Marketplace configuration file is corrupted: " + vr.error();
            log.error(msg);
            throw new ConfigParseError(msg, filePath, node);
        }
        return parsed;
    }

    // ── save ─────────────────────────────────────────────────────────────

    /**
     * 保存 known_marketplaces.json（写前校验、父目录创建、缩进 2 JSON）· CC original:
     * {@code saveKnownMarketplacesConfig}（:327-350）。
     *
     * @throws ConfigParseError 待保存配置不合法（对齐 CC :334-339）
     */
    public void saveKnownMarketplacesConfig(Map<String, KnownMarketplace> config) {
        ValidationResult vr = PluginSchemas.validateKnownMarketplaceFile(config);
        if (!vr.valid()) {
            throw new ConfigParseError("Invalid marketplace config: " + vr.error(), getKnownMarketplacesFile(), config);
        }
        Path configFile = Paths.get(getKnownMarketplacesFile());
        try {
            Files.createDirectories(configFile.getParent());
            // CC jsonStringify(parsed.data, null, 2) → 缩进 2 空格 + 平台换行
            DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
            pp = pp.withObjectIndenter(new DefaultIndenter("  ", "\n"));
            String json = JSON.writer(pp).writeValueAsString(config);
            Files.writeString(configFile, json, StandardCharsets.UTF_8);
            if (log.isDebugEnabled()) {
                log.debug("已保存 known_marketplaces.json（{} 条目）：{}", config.size(), configFile);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save marketplace configuration: " + e.getMessage(), e);
        }
    }

    // ── seed 注册 ────────────────────────────────────────────────────────

    /**
     * 从只读 seed 目录把 marketplace 注册进 primary known_marketplaces.json · CC original:
     * {@code registerSeedMarketplaces}（:380-434）。
     *
     * <p>不变量：多 seed 目录 first-seed-wins（同名被早 seed 认领则跳过）；installLocation 按运行时
     * seedDir 重算（不信 seed JSON 内构建期路径）；autoUpdate 强制 false（seed 只读）；幂等
     * （changed=0 不写盘）。
     *
     * @return 是否有条目写入/变更（调用方应清缓存）
     */
    public boolean registerSeedMarketplaces() {
        List<String> seedDirs = PluginDirectories.getPluginSeedDirs();
        if (seedDirs.isEmpty()) {
            return false;
        }
        Map<String, KnownMarketplace> primary = loadKnownMarketplacesConfig();
        Set<String> claimed = new LinkedHashSet<>();
        int changed = 0;

        for (String seedDir : seedDirs) {
            Map<String, KnownMarketplace> seedConfig = readSeedKnownMarketplaces(seedDir);
            if (seedConfig == null) {
                continue;
            }
            for (Map.Entry<String, KnownMarketplace> e : seedConfig.entrySet()) {
                String name = e.getKey();
                if (claimed.contains(name)) {
                    continue;
                }
                KnownMarketplace seedEntry = e.getValue();
                String resolvedLocation = findSeedMarketplaceLocation(seedDir, name);
                if (resolvedLocation == null) {
                    // 种子内容缺失（构建不完整）——不认领名字，后续 seed 可能有可用内容
                    log.warn("Seed marketplace '{}' not found under {}/marketplaces/, skipping", name, seedDir);
                    continue;
                }
                claimed.add(name);
                KnownMarketplace desired = new KnownMarketplace(
                    seedEntry.source(), resolvedLocation, seedEntry.lastUpdated(), false);
                if (desired.equals(primary.get(name))) {
                    continue; // 幂等 no-op
                }
                primary.put(name, desired);
                changed++;
            }
        }
        if (changed > 0) {
            saveKnownMarketplacesConfig(primary);
            log.info("已从 seed 目录同步 {} 个 marketplace", changed);
            return true;
        }
        return false;
    }

    /**
     * 读取 seed 目录的 known_marketplaces.json · CC original: {@code readSeedKnownMarketplaces}（:436-462）。
     * 缺失/损坏 → null（损坏仅 warn，不升级为 error —— seed 是 admin 受管，读取失败不崩启动）。
     */
    private Map<String, KnownMarketplace> readSeedKnownMarketplaces(String seedDir) {
        Path seedJsonPath = Paths.get(seedDir, "known_marketplaces.json");
        try {
            String content = Files.readString(seedJsonPath, StandardCharsets.UTF_8);
            return parseKnownMarketplacesFile(content, seedJsonPath.toString());
        } catch (Exception e) {
            log.warn("Failed to read seed known_marketplaces.json at {}: {}", seedDir, e.getMessage());
            return null;
        }
    }

    /**
     * 探测 seedDir/marketplaces/{name} 与 {name}.json 两个候选位 · CC original:
     * {@code findSeedMarketplaceLocation}（:473-488）。
     *
     * <p>L1 只做存在性探测（读取/校验 marketplace manifest 属 L2/L3 层）。
     */
    private String findSeedMarketplaceLocation(String seedDir, String name) {
        String dirCandidate = Paths.get(seedDir, "marketplaces", name).toString();
        String jsonCandidate = Paths.get(seedDir, "marketplaces", name + ".json").toString();
        for (String candidate : List.of(dirCandidate, jsonCandidate)) {
            if (Files.exists(Paths.get(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    // ── settings 意图层 ──────────────────────────────────────────────────

    /**
     * 写 marketplace 条目到 settings.extraKnownMarketplaces（意图层，不碰文件态）·
     * CC original: {@code saveMarketplaceToSettings}（:226-238，默认 userSettings）。
     *
     * @param name         marketplace 名称
     * @param entry        marketplace 配置
     * @param settingSource 写目标 settings 源（'userSettings' | 'projectSettings' | 'localSettings'）
     */
    public void saveMarketplaceToSettings(String name, KnownMarketplace entry, String settingSource) {
        if (configStorage == null) {
            log.warn("saveMarketplaceToSettings：ConfigStorage 未接线，跳过 settings 写入 name={} source={}",
                name, settingSource);
            return;
        }
        String source = (settingSource == null || settingSource.isBlank()) ? "userSettings" : settingSource;
        // CC: existing = getSettingsForSource(source) ?? {}; current[name] = entry; updateSettingsForSource(source, {extraKnownMarketplaces: current})
        Object existing = configStorage.readSettings(List.of("extraKnownMarketplaces"));
        Map<String, Object> current = new LinkedHashMap<>();
        if (existing instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> existingMap = (Map<String, Object>) existing;
            current.putAll(existingMap);
        }
        current.put(name, entry);
        configStorage.writeSettings(List.of("extraKnownMarketplaces"), current);
        if (log.isDebugEnabled()) {
            log.debug("已写 settings.extraKnownMarketplaces[{}]（source={}）", name, source);
        }
    }

    /** 默认写 userSettings · CC :226-233 默认参数。 */
    public void saveMarketplaceToSettings(String name, KnownMarketplace entry) {
        saveMarketplaceToSettings(name, entry, "userSettings");
    }

    // ── L3 查找层 · cache-only / memoize / pluginById ─────────────────────

    /**
     * 从磁盘读取 marketplace.json · CC {@code readCachedMarketplace}（marketplaceManager.ts:2058-2074）。
     *
     * <p>git 源 → {@code installLocation/.claude-plugin/marketplace.json}；url/file 源 → installLocation 本身。
     * 嵌套路径缺失（ENOENT）/ 中间组件非目录（ENOTDIR）→ 回退直文件（CC :2068-2072）；
     * 嵌套存在但损坏（schema/JSON 错误）→ 抛出不回退。
     */
    private PluginMarketplace.Marketplace readCachedMarketplace(String installLocation) throws IOException {
        Path loc = Paths.get(installLocation);
        Path nested = loc.resolve(".claude-plugin").resolve("marketplace.json");
        try {
            return parseMarketplaceFile(nested);
        } catch (NoSuchFileException | NotDirectoryException e) {
            if (log.isDebugEnabled()) {
                log.debug("嵌套 marketplace.json 缺失（{}），回退直文件 {}", installLocation, e.getMessage());
            }
        }
        return parseMarketplaceFile(loc);
    }

    /**
     * 解析 + 轻量校验单个 marketplace.json · CC {@code parseFileWithSchema}（schema 宽松，忽略未知键）。
     *
     * @throws NoSuchFileException / {@link NotDirectoryException} 文件缺失/非目录（readCachedMarketplace 回退信号）
     * @throws RuntimeException      JSON 语法错误（对齐 CC 非 ConfigParseError 分支）
     * @throws IllegalArgumentException name 缺失（对齐 CC schema 校验失败）
     */
    private PluginMarketplace.Marketplace parseMarketplaceFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return JSON.readValue(content, PluginMarketplace.Marketplace.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse marketplace manifest " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * cache-only 单 marketplace 读取（无网络/拉源）· CC {@code getMarketplaceCacheOnly}
     * （marketplaceManager.ts:2081-2107）。未知 name / 配置文件缺失 / 缓存损坏 → null（:2092-2093 / :2097-2106）。
     */
    public PluginMarketplace.Marketplace getMarketplaceCacheOnly(String name) {
        try {
            String content = Files.readString(Paths.get(getKnownMarketplacesFile()), StandardCharsets.UTF_8);
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode entryNode = root.get(name);
            if (entryNode == null || entryNode.isNull()) {
                return null;
            }
            JsonNode il = entryNode.get("installLocation");
            if (il == null || !il.isTextual() || il.asText().isBlank()) {
                return null;
            }
            return readCachedMarketplace(il.asText());
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("读取 marketplace 缓存失败（{}）：{}", name, e.getMessage());
            }
            return null;
        }
    }

    /**
     * 单 marketplace 读取（per-name memoize）· CC {@code getMarketplace = memoize(...)}
     * （marketplaceManager.ts:2122-2178）。
     *
     * <p>内存缓存优先；miss 先读磁盘缓存，仍 miss 才从源拉取（github/git 经 L2 同步层）并写回
     * lastUpdated（仅实际拉取后，:2172-2174）。失效：{@link #clearMarketplaceCache(String)}
     * （refresh 入口调用，:2380）。拉取失败不缓存（computeIfAbsent 异常即时移出），下次可重试——
     * 偏差 lodash memoize 缓存 rejected promise，语义更安全（会话提示词 §8 授权自实现）。
     *
     * @throws IllegalStateException 未知 name / 本地源相对路径 / 拉源失败（对齐 CC :2128-2130/:2141-2147/:2167-2170）
     */
    public PluginMarketplace.Marketplace getMarketplace(String name) {
        return marketplaceMemoCache.computeIfAbsent(name, this::loadMarketplace);
    }

    private PluginMarketplace.Marketplace loadMarketplace(String name) {
        Map<String, KnownMarketplace> config = loadKnownMarketplacesConfig();
        KnownMarketplace entry = config.get(name);
        if (entry == null) {
            throw new IllegalStateException(
                "Marketplace '" + name + "' not found in configuration. Available marketplaces: "
                    + String.join(", ", config.keySet()));
        }
        // 本地源相对路径 = 旧版本遗留脏状态（CC :2137-2147），给可执行指引而非误导性 ENOENT
        if (isLocalMarketplaceSource(entry.source())) {
            String path = localSourcePath(entry.source());
            if (path != null && !Paths.get(path).isAbsolute()) {
                throw new IllegalStateException(
                    "Marketplace \"" + name + "\" has a relative source path (" + path
                        + ") in known_marketplaces.json — this is stale state from an older "
                        + "Claude Code version. Run 'claude marketplace remove " + name
                        + "' and re-add it from the original project directory.");
            }
        }

        // 磁盘缓存优先（CC :2149-2160）；损坏/缺失 → warn 后拉源
        try {
            return readCachedMarketplace(entry.installLocation());
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("缓存缺失或损坏，从源重新拉取 marketplace {}：{}", name, e.getMessage());
            }
        }

        // miss → 从源拉取（CC :2162-2170）；github/git 复用 L2 同步层，url/npm/local 暂不支持 → 抛
        PluginMarketplace.Marketplace marketplace;
        try {
            String cachePath = loadAndCacheMarketplaceForRefresh(entry.source());
            if (cachePath == null) {
                throw new IOException("source type not supported by L3 miss-fetch: "
                    + entry.source().getClass().getSimpleName());
            }
            marketplace = readCachedMarketplace(cachePath);
        } catch (Exception error) {
            throw new IllegalStateException(
                "Failed to load marketplace \"" + name + "\" from source (" + sourceKind(entry.source())
                    + "): " + error.getMessage(), error);
        }

        // lastUpdated 仅在实际拉取后更新（CC :2172-2174）；installLocation 不变（对齐 CC getMarketplace）
        config.put(name, new KnownMarketplace(
            entry.source(), entry.installLocation(), java.time.Instant.now().toString(), entry.autoUpdate()));
        saveKnownMarketplacesConfig(config);
        return marketplace;
    }

    /**
     * cache-only 插件查找 · CC {@code getPluginByIdCacheOnly}（marketplaceManager.ts:2188-2227）。
     * identifier 无 name/marketplace / config 缺 marketplace / 缓存缺失 / find 未命中 → null。
     */
    public PluginMarketplace.LookupResult getPluginByIdCacheOnly(String pluginId) {
        PluginIdentifier.Parsed id = PluginIdentifier.parse(pluginId);
        if (id.name().isEmpty() || id.marketplace() == null || id.marketplace().isEmpty()) {
            return null;
        }
        try {
            String content = Files.readString(Paths.get(getKnownMarketplacesFile()), StandardCharsets.UTF_8);
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode mktConfig = root.get(id.marketplace());
            if (mktConfig == null || mktConfig.isNull()) {
                return null;
            }
            JsonNode il = mktConfig.get("installLocation");
            if (il == null || !il.isTextual() || il.asText().isBlank()) {
                return null;
            }
            PluginMarketplace.Marketplace marketplace = getMarketplaceCacheOnly(id.marketplace());
            if (marketplace == null) {
                return null;
            }
            PluginMarketplace.Entry plugin = marketplace.plugins().stream()
                .filter(p -> id.name().equals(p.name()))
                .findFirst().orElse(null);
            if (plugin == null) {
                return null;
            }
            return new PluginMarketplace.LookupResult(plugin, il.asText());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 插件查找 · CC {@code getPluginById}（marketplaceManager.ts:2238-2280）。
     * cache-only 快路径命中即返回；miss → getMarketplace（拉源）+ find（:2242-2272）。
     */
    public PluginMarketplace.LookupResult getPluginById(String pluginId) {
        PluginMarketplace.LookupResult cached = getPluginByIdCacheOnly(pluginId);
        if (cached != null) {
            return cached;
        }
        PluginIdentifier.Parsed id = PluginIdentifier.parse(pluginId);
        if (id.name().isEmpty() || id.marketplace() == null || id.marketplace().isEmpty()) {
            return null;
        }
        try {
            Map<String, KnownMarketplace> config = loadKnownMarketplacesConfig();
            KnownMarketplace mktConfig = config.get(id.marketplace());
            if (mktConfig == null) {
                return null;
            }
            PluginMarketplace.Marketplace marketplace = getMarketplace(id.marketplace());
            PluginMarketplace.Entry plugin = marketplace.plugins().stream()
                .filter(p -> id.name().equals(p.name()))
                .findFirst().orElse(null);
            if (plugin == null) {
                return null;
            }
            return new PluginMarketplace.LookupResult(plugin, mktConfig.installLocation());
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Could not find plugin {}: {}", pluginId, e.getMessage());
            }
            return null;
        }
    }

    /** 单 name memoize 失效 · CC {@code getMarketplace.cache?.delete?.(name)}（marketplaceManager.ts:2380）。 */
    public void clearMarketplaceCache(String name) {
        marketplaceMemoCache.remove(name);
    }

    /** 全部 memoize 失效 · CC {@code clearMarketplacesCache}（marketplaceManager.ts:122-123）。 */
    public void clearMarketplacesCache() {
        marketplaceMemoCache.clear();
    }

    private static String sourceKind(MarketplaceSource source) {
        return switch (source) {
            case MarketplaceSource.Url u -> "url";
            case MarketplaceSource.Github g -> "github";
            case MarketplaceSource.Git g -> "git";
            case MarketplaceSource.Npm n -> "npm";
            case MarketplaceSource.File f -> "file";
            case MarketplaceSource.Directory d -> "directory";
            case MarketplaceSource.HostPattern h -> "hostPattern";
            case MarketplaceSource.PathPattern p -> "pathPattern";
            case MarketplaceSource.Settings s -> "settings";
        };
    }

    private static String localSourcePath(MarketplaceSource source) {
        return switch (source) {
            case MarketplaceSource.File f -> f.path();
            case MarketplaceSource.Directory d -> d.path();
            default -> null;
        };
    }

    // ── L2 同步层 · refresh ───────────────────────────────────────────────

    /**
     * 刷新单个 marketplace 缓存 · CC {@code refreshMarketplace}（:2365-2575）。
     *
     * <p>不变量（CC :2370-2426）：不存在 → 抛带可用列表错误；settings 源 → 跳过（无上游）；
     * seed 托管 → 抛带指引错误；远程源 installLocation 必须落在 marketplaces 缓存目录内
     * （损坏值 → 抛，拒绝在用户项目目录跑 git ops / fs.rm，gh-32793/gh-32661）。
     *
     * <p>github 源 SSH/HTTPS 回退（:2467-2514）：CCR 恒 HTTPS；否则探测 SSH 优先，失败回退。
     * git 源直用 url（无回退）。更新后校验 marketplace.json 仍在（:2526-2545）。
     *
     * <p>成功路径写回 lastUpdated（ISO UTC）+ saveKnownMarketplacesConfig（:2563-2565）。
     *
     * @param name marketplace 名称
     * @throws IOException 刷新失败 / 不存在 / seed 托管 / installLocation 损坏 / marketplace.json 缺失
     */
    public void refreshMarketplace(String name) throws IOException {
        refreshMarketplace(name, false);
    }

    /**
     * 刷新单个 marketplace（可选禁用凭据助手）· CC refreshMarketplace options.disableCredentialHelper。
     */
    public void refreshMarketplace(String name, boolean disableCredentialHelper) throws IOException {
        Map<String, KnownMarketplace> config = loadKnownMarketplacesConfig();
        KnownMarketplace entry = config.get(name);

        if (entry == null) {
            throw new IOException("Marketplace '" + name + "' not found. Available marketplaces: "
                + String.join(", ", config.keySet()));
        }

        // 清 memoization 缓存（CC :2380 getMarketplace.cache?.delete?.(name)）——refresh 后失效重拉
        clearMarketplaceCache(name);

        // settings 源无上游可拉，跳过（CC :2385-2390）
        if (entry.source() instanceof MarketplaceSource.Settings) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping refresh for settings-sourced marketplace '{}' — no upstream", name);
            }
            return;
        }

        // seed 托管：受 seed 镜像控制，刷新无意义且会被 registerSeedMarketplaces 覆盖（CC :2397-2406）
        String seedDir = seedDirFor(entry.installLocation());
        if (seedDir != null) {
            throw new IOException("Marketplace '" + name + "' is seed-managed (" + seedDir
                + ") and its content is controlled by the seed image. "
                + "To update: ask your admin to update the seed.");
        }

        MarketplaceSource source = entry.source();
        String installLocation = entry.installLocation();

        // 远程源 installLocation 越界守卫（CC :2414-2426）
        if (!isLocalMarketplaceSource(source)) {
            Path cacheDir = Paths.get(getMarketplacesCacheDir()).toAbsolutePath().normalize();
            Path resolvedLoc = Paths.get(installLocation).toAbsolutePath().normalize();
            String cachePrefix = cacheDir + File.separator;
            if (!resolvedLoc.equals(cacheDir) && !resolvedLoc.toString().startsWith(cachePrefix)) {
                throw new IOException("Marketplace '" + name + "' has a corrupted installLocation ("
                    + installLocation + ") — expected a path inside " + cacheDir + ". "
                    + "This can happen after cross-platform path writes or manual edits to "
                    + "known_marketplaces.json. "
                    + "Run: claude plugin marketplace remove \"" + name + "\" and re-add it.");
            }
        }

        try {
            if (source instanceof MarketplaceSource.Github gh) {
                refreshGithubSource(name, gh, installLocation, disableCredentialHelper);
                requireCachedMarketplaceReadable(name, installLocation, source);
            } else if (source instanceof MarketplaceSource.Git git) {
                // 显式 git URL：直接用，无回退（CC :2515-2525）
                sync().cacheMarketplaceFromGit(git.url(), installLocation, git.ref(),
                    git.sparsePaths(), disableCredentialHelper);
                requireCachedMarketplaceReadable(name, installLocation, source);
            } else if (isLocalMarketplaceSource(source)) {
                // 本地源：无远程可更新，仅校验文件仍在且可读（CC :2554-2558）
                if (log.isDebugEnabled()) {
                    log.debug("Validating local marketplace '{}'", name);
                }
                requireCachedMarketplaceReadable(name, installLocation, source);
            } else {
                // url/npm：重新下载属 L4 安装层（cacheMarketplaceFromUrl），MPL2 仅 git 源
                throw new IOException("Unsupported marketplace source type for refresh: "
                    + source.getClass().getSimpleName()
                    + "（URL 源重新下载待 L4 安装层实现）");
            }

            // 写回 lastUpdated + 持久化（CC :2563-2565）
            config.put(name, new KnownMarketplace(
                entry.source(), installLocation, java.time.Instant.now().toString(), entry.autoUpdate()));
            saveKnownMarketplacesConfig(config);
            log.info("成功刷新 marketplace：{}", name);
        } catch (IOException e) {
            log.error("Failed to refresh marketplace {}: {}", name, e.getMessage());
            throw new IOException("Failed to refresh marketplace '" + name + "': " + e.getMessage(), e);
        }
    }

    /**
     * github 源刷新（SSH/HTTPS 回退）· CC refreshMarketplace :2467-2514。
     * CCR（CLAUDE_CODE_REMOTE truthy）恒 HTTPS；否则探测 SSH 优先、失败回退另一协议。
     */
    private void refreshGithubSource(String name, MarketplaceSource.Github gh, String installLocation,
                                     boolean disableCredentialHelper) throws IOException {
        String sshUrl = "git@github.com:" + gh.repo() + ".git";
        String httpsUrl = "https://github.com/" + gh.repo() + ".git";

        if (remoteModeCheck.getAsBoolean()) {
            // CCR: always HTTPS (no SSH keys available)
            sync().cacheMarketplaceFromGit(httpsUrl, installLocation, gh.ref(), gh.sparsePaths(),
                disableCredentialHelper);
            return;
        }
        boolean sshConfigured = sync().isGitHubSshLikelyConfigured();
        String primaryUrl = sshConfigured ? sshUrl : httpsUrl;
        String fallbackUrl = sshConfigured ? httpsUrl : sshUrl;
        try {
            sync().cacheMarketplaceFromGit(primaryUrl, installLocation, gh.ref(), gh.sparsePaths(),
                disableCredentialHelper);
        } catch (IOException e) {
            log.info("Marketplace refresh failed with {} for {}, falling back to {}",
                sshConfigured ? "SSH" : "HTTPS", gh.repo(), sshConfigured ? "HTTPS" : "SSH");
            sync().cacheMarketplaceFromGit(fallbackUrl, installLocation, gh.ref(), gh.sparsePaths(),
                disableCredentialHelper);
        }
    }

    /**
     * 批量刷新全部 marketplace · CC {@code refreshAllMarketplaces}（:2296-2351）。
     *
     * <p>跳过 seed 托管（:2302-2307）与 settings 源（:2309-2311）；其余逐源 git 缓存并写回
     * lastUpdated + installLocation（:2336-2339）；单源失败仅 error 日志不中断（:2340-2347）；
     * 末尾统一 saveKnownMarketplacesConfig（:2350）。
     */
    public void refreshAllMarketplaces() throws IOException {
        Map<String, KnownMarketplace> config = loadKnownMarketplacesConfig();
        for (Map.Entry<String, KnownMarketplace> e : new LinkedHashMap<>(config).entrySet()) {
            String name = e.getKey();
            KnownMarketplace entry = e.getValue();

            if (seedDirFor(entry.installLocation()) != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping seed-managed marketplace '{}' in bulk refresh", name);
                }
                continue;
            }
            if (entry.source() instanceof MarketplaceSource.Settings) {
                continue;
            }
            try {
                String cachePath = loadAndCacheMarketplaceForRefresh(entry.source());
                if (cachePath == null) {
                    continue; // 非 git 源已 warn 跳过
                }
                config.put(name, new KnownMarketplace(
                    entry.source(), cachePath, java.time.Instant.now().toString(), entry.autoUpdate()));
            } catch (Exception error) {
                log.error("Failed to refresh marketplace {}: {}", name, error.getMessage());
            }
        }
        saveKnownMarketplacesConfig(config);
    }

    /**
     * git 源下载进缓存目录（refreshAll 专用）· CC loadAndCacheMarketplace（:1433+）github/git 分支。
     * 缓存路径：github → {@code cacheDir/repo.replace('/', '-')}，git → {@code cacheDir/temp_<ts>}
     * （CC getCachePathForSource :1355-1369）。解析 marketplace.json 并按 name 重命名属 MPL3。
     *
     * @return 缓存路径；非 git 源返回 null（warn 跳过，L4 安装层范围）
     */
    private String loadAndCacheMarketplaceForRefresh(MarketplaceSource source) throws IOException {
        String cacheDir = getMarketplacesCacheDir();
        Files.createDirectories(Paths.get(cacheDir));
        if (source instanceof MarketplaceSource.Github gh) {
            String tempName = gh.repo().replace('/', '-');
            String cachePath = Paths.get(cacheDir, tempName).toString();
            refreshGithubSource(gh.repo(), gh, cachePath, false);
            return cachePath;
        }
        if (source instanceof MarketplaceSource.Git git) {
            String tempName = "temp_" + System.currentTimeMillis();
            String cachePath = Paths.get(cacheDir, tempName).toString();
            sync().cacheMarketplaceFromGit(git.url(), cachePath, git.ref(), git.sparsePaths(), false);
            return cachePath;
        }
        log.warn("refreshAllMarketplaces 跳过非 git 源 {}（url/npm/local 属 L4 安装层范围）",
            source.getClass().getSimpleName());
        return null;
    }

    /**
     * 校验缓存 marketplace.json 仍在 · CC readCachedMarketplace（:2058-2074）。
     * git 源 → {@code installLocation/.claude-plugin/marketplace.json}；url/file 源 → installLocation 本身。
     * 缺失 → 抛带指引错误（仓库可能被重构/废弃，CC :2528-2544）。
     */
    private static void requireCachedMarketplaceReadable(String name, String installLocation,
                                                         MarketplaceSource source) throws IOException {
        Path loc = Paths.get(installLocation);
        if (Files.isDirectory(loc)) {
            Path nested = loc.resolve(".claude-plugin").resolve("marketplace.json");
            if (Files.isRegularFile(nested)) {
                return;
            }
        }
        if (Files.isRegularFile(loc)) {
            return;
        }
        String sourceDisplay = source instanceof MarketplaceSource.Github gh
            ? gh.repo()
            : source instanceof MarketplaceSource.Git
                ? GitProcessRunner.redactUrlCredentials(((MarketplaceSource.Git) source).url())
                : installLocation;
        throw new IOException("The marketplace.json file is no longer present in this repository.\n\n"
            + "This marketplace may have been deprecated or moved to a new location.\n"
            + "Source: " + sourceDisplay + "\n\n"
            + "You can remove this marketplace with: claude plugin marketplace remove \"" + name + "\"");
    }

    /**
     * 判定 installLocation 是否落在某 seed 目录 · CC seedDirFor（:496-502）。
     * seed 托管条目受 seed 镜像控制，禁止用户 remove/refresh/modify。
     * 包可见：MPL8 {@link MarketplaceReconciler#addMarketplaceSource} 覆盖 config 前守卫复用。
     */
    static String seedDirFor(String installLocation) {
        if (installLocation == null) {
            return null;
        }
        for (String d : PluginDirectories.getPluginSeedDirs()) {
            if (installLocation.equals(d) || installLocation.startsWith(d + File.separator)) {
                return d;
            }
        }
        return null;
    }

    /** CC isLocalMarketplaceSource（schemas.ts:1236-1241）：file / directory 源无远程可更新。 */
    private static boolean isLocalMarketplaceSource(MarketplaceSource source) {
        return source instanceof MarketplaceSource.File
            || source instanceof MarketplaceSource.Directory;
    }
}
