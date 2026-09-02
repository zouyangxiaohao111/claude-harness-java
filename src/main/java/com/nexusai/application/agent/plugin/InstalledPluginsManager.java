package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import com.nexusai.application.agent.skill.ClaudePaths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Installed Plugins Manager · 对齐 CC utils/plugins/installedPluginsManager.ts (1268 行).
 *
 * <p>FIX-PLUGIN-INSTALLED: installed plugin persistence/migration/query.
 *
 * <p>[MPL5] installed_plugins.json 文件读/写/migrate 接线（对齐 CC :78-618）：启动
 * {@link #initialize()} 执行 {@code migrateToSinglePluginFile}（V2 rename 合并 + V1→V2 就地转换）
 * 并载盘进内存；install/uninstall/setEnabled 即时写盘（文件为真相源）；双态检测
 * {@link #hasPendingUpdates()}（磁盘 vs 内存，对齐 CC :595-618）。
 *
 * <p>[ODF-C3R] 生产 feed 数据源 · 对齐 CC loadAllPluginsCacheOnly 的
 * "installed + enabled" 枚举语义（pluginLoader.ts:3198 {@code allPlugins.filter(p => p.enabled)}）：
 * {@link InstalledRecord} 追加 {@code sourcePath}/{@code agentsPath}（插件本地安装目录 + manifest
 * agentsPath，对齐 loadPluginAgents.ts:250 plugin.agentsPath），使装配期
 * {@code PluginLoader.loadInstalledEnabledPlugins()} 能枚举已装 enabled plugins 调 4 参 load
 * 注册 agentsPath —— 生产 registry.listAgents() 因此可收 plugin agent。
 *
 * <h2>scope 语义差异（ODF §8 显式映射决策）</h2>
 * {@link InstalledRecord#scope()} 存 Java InstallSource（path/git/marketplace/npm，供
 * PluginLoader feed）；CC scope（managed/user/project/local，schemas.ts:1506-1508）在文件层
 * 统一映射为 {@code user}（对齐 CC migrateV1ToV2 :294），原始 InstallSource 经非 CC 扩展字段
 * {@code source} 持久化。
 */
@Component
public class InstalledPluginsManager {

    private static final Logger log = LoggerFactory.getLogger(InstalledPluginsManager.class);

    /** enabledPlugins settings 值反序列化（JsonNode/ObjectNode → Map<String,Boolean>）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * [MPL5] 文件层（V2 结构 + V1→V2 迁移）· CC installed_plugins.json 读/写/migrate。
     * 可为 null：未接线时内存-only（兼容无 Spring 直构测试，不落盘）。
     */
    private volatile InstalledPluginsFileStore fileStore;

    /**
     * [MPL5] enabledPlugins 设置源 · CC {@code getSettings_DEPRECATED().enabledPlugins}
     * （installedPluginsManager.ts:1050-1051）。缺省空 Map：未接线时 migrateFromEnabledPlugins 同步
     * no-op（兼容无 Spring 直构测试）。测试/生产经 {@link #setEnabledPluginsSupplier} 注入。
     */
    private Supplier<Map<String, Boolean>> enabledPluginsSupplier = Map::of;

    /** [MPL5-MIGRATE-WIRE] 设置源是否已显式注入（测试 stub 优先，防止产装配覆盖）。 */
    private boolean enabledPluginsSourceSet;

    /**
     * [MPL5-REWORK] 市场解析器 · CC {@code getPluginById}（marketplaceManager.ts:2238-2280），
     * migrateFromEnabledPlugins 添加未注册插件前先查市场（installedPluginsManager.ts:1179）。可空：
     * 未接线时视同解析失败（CC :1180-1184 not found → skip），绝不写 CC 不会写的幽灵记录。
     * 生产经 {@link #setMarketplaceManager}（Spring）注入，测试经 {@link #setPluginResolver} stub 注入。
     */
    private Function<String, PluginMarketplace.LookupResult> pluginResolver;

    public record InstalledRecord(String name, String version, String scope,
                                   boolean enabled, long installedAt,
                                   /** CC original: plugin.path（loadPluginAgents.ts:234）· 插件本地安装目录 */
                                   Path sourcePath,
                                   /** CC original: plugin.agentsPath（loadPluginAgents.ts:250）· 插件默认 agents 目录 */
                                   Path agentsPath,
                                   /** CC original: projectPath（schemas.ts:1520-1523）· project/local scope 项目路径 */
                                   String projectPath,
                                   /** CC original: installPath（schemas.ts:1524-1526）· 版本化插件目录绝对路径 */
                                   String installPath,
                                   /** CC original: lastUpdated（schemas.ts:1533-1535）· epoch millis */
                                   long lastUpdated,
                                   /** CC original: gitCommitSha（schemas.ts:1537-1540）· git 类插件 commit SHA */
                                   String gitCommitSha,
                                   /** [MPL6] CC original: commandsPath（pluginLoader.ts:1388-1391）· 插件默认 commands 目录 */
                                   Path commandsPath,
                                   /** [MPL6] CC original: commandsPaths（pluginLoader.ts:1463-1466）· manifest 附加命令路径 */
                                   List<String> commandsPaths,
                                   /** [MPL6] CC original: skillsPath（pluginLoader.ts:1558-1561）· 插件默认 skills 目录 */
                                   Path skillsPath,
                                   /** [MPL6] CC original: skillsPaths（pluginLoader.ts:1580-1582）· manifest 附加技能路径 */
                                   List<String> skillsPaths,
                                   /** [MPL6] CC original: outputStylesPath（pluginLoader.ts:1586-1589）· 插件默认 output-styles 目录 */
                                   Path outputStylesPath,
                                   /** [MPL6] CC original: outputStylesPaths（pluginLoader.ts:1608-1610）· manifest 附加样式路径 */
                                   List<String> outputStylesPaths,
                                   /** [P2-14] CC original: commandsMetadata（types/plugin.ts:59）· manifest object-mapping 命令元数据（transient，不落盘 V2） */
                                   Map<String, PluginLoader.CommandMetadata> commandsMetadata) {

        public InstalledRecord(String name, String version, String scope,
                               boolean enabled, long installedAt,
                               Path sourcePath, Path agentsPath, String projectPath, String installPath,
                               long lastUpdated, String gitCommitSha) {
            this(name, version, scope, enabled, installedAt, sourcePath, agentsPath, projectPath, installPath,
                lastUpdated, gitCommitSha,
                null, List.of(), null, List.of(), null, List.of(), Map.of());
        }

        /** 保留全部组件路径字段 + commandsMetadata（MPL6 / P2-14）翻转 enabled 标志。 */
        public InstalledRecord withEnabled(boolean newEnabled) {
            return new InstalledRecord(name, version, scope, newEnabled, installedAt,
                sourcePath, agentsPath, projectPath, installPath, lastUpdated, gitCommitSha,
                commandsPath, commandsPaths, skillsPath, skillsPaths, outputStylesPath, outputStylesPaths,
                commandsMetadata != null ? commandsMetadata : Map.of());
        }
    }

    /** [MPL5] 会话内存快照（CC :73 inMemoryInstalledPlugins 等价）· 启动载盘 + 运行时变更。 */
    private final Map<String, InstalledRecord> installed = new ConcurrentHashMap<>();

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
                log.warn("[InstalledPluginsManager] 读取 settings.plugin_claude_fallback 失败，回落默认 true: {}", e.toString());
            }
        }
        return true;
    }

    /**
     * [V61] enabledPlugins 优先读 DB settings 列（settings.enabled_plugins JSON 文本，前端插件管理页
     *  写入）· null/空白/未接线/解析失败 → 空 Map（调用方回落 ConfigStorage settings.json）。
     */
    private Map<String, Boolean> readDbEnabledPlugins() {
        SettingsMapper mapper = this.settingsMapper;
        if (mapper == null) {
            return Map.of();
        }
        try {
            SettingsRecord s = mapper.selectOneById(1);
            if (s == null || s.getEnabledPlugins() == null || s.getEnabledPlugins().isBlank()) {
                return Map.of();
            }
            Map<String, Boolean> enabled = JSON.readValue(s.getEnabledPlugins(),
                new TypeReference<LinkedHashMap<String, Boolean>>() { });
            if (log.isDebugEnabled()) {
                log.debug("settings.enabled_plugins DB 列读取到 {} 个插件（前端写，InstalledPluginsManager 读链优先）",
                    enabled.size());
            }
            return enabled;
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[InstalledPluginsManager] 读取 settings.enabled_plugins DB 列失败，视为空（回落 ConfigStorage settings.json）: {}", e.toString());
            }
            return Map.of();
        }
    }

    /**
     * [MPL5] 文件层注入（Spring 接线）。required=false：无 Spring 直构时保持内存-only，不落盘。
     */
    @Autowired(required = false)
    public void setFileStore(InstalledPluginsFileStore fileStore) {
        this.fileStore = fileStore;
        if (log.isDebugEnabled()) {
            log.debug("[InstalledPluginsManager] 文件层注入: {}", fileStore != null);
        }
    }

    /**
     * [MPL5] 启动初始化 · 对齐 CC {@code initializeVersionedPlugins}（:714-734）。
     *
     * <p>Step 1: migrateToSinglePluginFile（V2 rename 合并 + V1→V2 就地转换，:716）；
     * Step 2: migrateFromEnabledPlugins（enabledPlugins 同步未注册插件，:721）；
     * Step 3: 载盘进内存（文件为真相源，:727-730 getInMemoryInstalledPlugins 等价）——
     * 决策 D4 claude 读回落：经 {@code store.loadInstalledPluginsWithClaudeFallback()} 合并
     * nexusai installed_plugins.json（优先，同 name first-wins）+ claude ~/.claude/plugins
     * （仅补 nexusai 缺失名，只读不迁移）→ 已装 enabled 枚举（{@link #list()}）含 claude 已装插件，
     * PluginLoader feed / 命令 / agents / skills / output-styles / MCP 均可加载。
     */
    @PostConstruct
    public void initialize() {
        InstalledPluginsFileStore store = this.fileStore;
        if (store == null) {
            store = new InstalledPluginsFileStore();
            this.fileStore = store;
        }
        store.migrateToSinglePluginFile();
        migrateFromEnabledPlugins();
        InstalledPluginsFileStore.InstalledPluginsFileV2 data = store.loadInstalledPluginsWithClaudeFallback();
        installed.clear();
        for (Map.Entry<String, List<InstalledPluginsFileStore.PluginInstallation>> e : data.plugins().entrySet()) {
            for (InstalledPluginsFileStore.PluginInstallation pi : e.getValue()) {
                installed.put(e.getKey(), fromPluginInstallation(e.getKey(), pi));
            }
        }
        log.info("InstalledPluginsManager: 初始化完成, 载入 {} 个插件（nexusai 优先 + claude D4 兼容回落，CC initializeVersionedPlugins :714-734）",
            installed.size());
    }

    /**
     * [MPL5] enabledPlugins 设置源注入 · CC {@code getSettings_DEPRECATED().enabledPlugins}。
     * 缺省空 Map（未接线时同步 no-op）。null 注入忽略保留缺省。
     */
    public void setEnabledPluginsSupplier(Supplier<Map<String, Boolean>> enabledPluginsSupplier) {
        if (enabledPluginsSupplier != null) {
            this.enabledPluginsSupplier = enabledPluginsSupplier;
            this.enabledPluginsSourceSet = true;
        }
    }

    /**
     * [MPL5-MIGRATE-WIRE] 生产 enabledPlugins 设置源接线 · CC
     * {@code getSettings_DEPRECATED().enabledPlugins}（installedPluginsManager.ts:1050-1051）。
     *
     * <p>经 {@link ConfigStorage#readSettings(List)} 读 {@code enabledPlugins} 键转
     * {@code Map<String,Boolean>}，注入 {@link #setEnabledPluginsSupplier} —— 生产装配后
     * {@code migrateFromEnabledPlugins} 不再缺省 no-op（原 Map::of）。required=false：
     * 无 Spring 直构测试保持缺省；已显式 setEnabledPluginsSupplier（测试 stub）不覆盖
     * （测试优先，同 {@code setMarketplaceManager} :180）。FileConfigStorage 对嵌套对象返回原始
     * JsonNode（FileConfigStorage.java:447 jsonNodeToJavaValue），readEnabledPlugins 兼容
     * Map（FakeConfigStorage 测试）与 JsonNode（生产）两种返回值。
     */
    @Autowired(required = false)
    public void setConfigStorage(ConfigStorage configStorage) {
        if (configStorage == null || enabledPluginsSourceSet) {
            return;
        }
        setEnabledPluginsSupplier(() -> readEnabledPlugins(configStorage));
        if (log.isDebugEnabled()) {
            log.debug("[InstalledPluginsManager] enabledPlugins 设置源注入（ConfigStorage.readSettings，CC installedPluginsManager.ts:1050-1051）");
        }
    }

    /** enabledPlugins 读链（2026-09-01 用户拍板 DB 化）：DB settings.enabled_plugins（前端写）优先 →
     *  无则回落 ConfigStorage（settings.json）→ 最后 CC settings（~/.claude/settings.json）双读兜底。
     *  CC 双读保留：nexusai 优先 + 同 name nexusai 赢（Web 可显式关掉 CC 启用的插件，如 zjkycode）。 */
    private Map<String, Boolean> readEnabledPlugins(ConfigStorage configStorage) {
        // [V61] DB settings.enabled_plugins（前端插件管理页写）优先；无 → 回落 ConfigStorage settings.json
        Map<String, Boolean> nexusai = readDbEnabledPlugins();
        if (nexusai.isEmpty()) {
            nexusai = readNexusaiEnabledPlugins(configStorage);
        }
        if (!isPluginClaudeFallback()) {
            // 开关关（DB settings.plugin_claude_fallback=false）：只读 nexusai，不回落实 CC
            if (log.isDebugEnabled()) {
                log.debug("enabledPlugins 双读开关关 → 只读 nexusai（{} 个）", nexusai.size());
            }
            return nexusai;
        }
        Map<String, Boolean> cc = readClaudeEnabledPlugins();
        LinkedHashMap<String, Boolean> merged = new LinkedHashMap<>(cc);
        merged.putAll(nexusai); // nexusai 覆盖 CC（Web 可显式关掉 CC 启用的插件）
        if (log.isDebugEnabled()) {
            log.debug("enabledPlugins 读链合并: nexusai={} + CC={} → 共 {} 个（CC 兜底 + nexusai 覆盖）",
                nexusai.size(), cc.size(), merged.size());
        }
        return merged;
    }

    /** 读 nexusai settings enabledPlugins 键转 Map；无键 / JSON null / 反序列化失败 → 空 Map（CC :1051 || {}）。 */
    private static Map<String, Boolean> readNexusaiEnabledPlugins(ConfigStorage configStorage) {
        Object raw;
        try {
            raw = configStorage.readSettings(List.of("enabledPlugins"));
        } catch (Exception e) {
            log.warn("读取 settings.enabledPlugins 失败，视为空 Map：{}", e.getMessage());
            return Map.of();
        }
        if (raw == null || raw == ConfigStorage.NullMarker) {
            return Map.of(); // CC :1051 settings.enabledPlugins || {} → 无键/JSON null → 空
        }
        try {
            if (raw instanceof Map || raw instanceof JsonNode) {
                Map<String, Boolean> enabled = JSON.convertValue(raw,
                    new TypeReference<LinkedHashMap<String, Boolean>>() { });
                if (log.isDebugEnabled()) {
                    log.debug("settings.enabledPlugins 读取到 {} 个插件（CC installedPluginsManager.ts:1050-1051）",
                        enabled.size());
                }
                return enabled;
            }
        } catch (Exception e) {
            log.warn("settings.enabledPlugins 反序列化失败，视为空 Map：{}", e.getMessage());
        }
        return Map.of();
    }

    /** 读 CC settings（{@code ~/.claude/settings.json}）enabledPlugins 兜底；无文件 / 无键 → 空 Map。 */
    private static Map<String, Boolean> readClaudeEnabledPlugins() {
        try {
            Path claudeSettings = Paths.get(ClaudePaths.getClaudeConfigHomeDir(), "settings.json");
            if (!Files.exists(claudeSettings)) {
                return Map.of();
            }
            JsonNode root = JSON.readTree(Files.readString(claudeSettings));
            JsonNode ep = root != null ? root.get("enabledPlugins") : null;
            if (ep == null || !ep.isObject()) {
                return Map.of();
            }
            Map<String, Boolean> result = new LinkedHashMap<>();
            ep.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asBoolean()));
            return result;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("读取 CC settings enabledPlugins 失败（双读兜底）: {}", e.getMessage());
            }
            return Map.of();
        }
    }

    /**
     * [MPL5-REWORK] 市场解析器注入（stub/直构）· CC {@code getPluginById}。null 注入忽略保留缺省。
     */
    public void setPluginResolver(Function<String, PluginMarketplace.LookupResult> resolver) {
        if (resolver != null) {
            this.pluginResolver = resolver;
        }
    }

    /**
     * [MPL5-REWORK] 生产市场解析器接线（Spring）· 经 {@link MarketplaceManager#getPluginById} 解析。
     * required=false：无 Spring 直构测试保持缺省 null（未接线 = 解析失败跳过，CC :1180-1184）。
     * 已注入 stub 时不覆盖（测试优先）。
     */
    @Autowired(required = false)
    public void setMarketplaceManager(MarketplaceManager marketplaceManager) {
        if (marketplaceManager != null && this.pluginResolver == null) {
            this.pluginResolver = marketplaceManager::getPluginById;
            if (log.isDebugEnabled()) {
                log.debug("[InstalledPluginsManager] 市场解析器注入（MarketplaceManager::getPluginById）");
            }
        }
    }

    /**
     * [MPL5] Step2 迁移 · 对齐 CC {@code migrateFromEnabledPlugins}（installedPluginsManager.ts:1048-1268）。
     *
     * <p>从 enabledPlugins 设置同步 installed_plugins.json：settings 是插件是否已安装 + scope 的真相源
     * ——enabledPlugins 中出现即视为已安装（无论 true/false，CC :1045-1046）。逐插件处理：
     * 文件已有条目 → 以 settings 为真相源纠正 scope/projectPath（CC :1149-1164）；缺失条目 → 先
     * {@code getPluginById} 查市场（CC :1179-1185），解析失败（含未接线解析器）跳过，命中则以
     * entry.version 落盘（CC :1231-1233）并取 versioned cache path 为 installPath（CC :1241）。
     * 添加分支整体 try/catch（CC :1175/:1254-1255）：单插件 resolver.apply/落盘异常仅记录继续，
     * 防 @PostConstruct 启动级失败。幂等：文件已有或市场未命中均不重复添加；无变化不写盘
     * （CC :1260-1267）。内存由 {@link #initialize()} Step3 载盘填充（CC :727-730）。未接线文件层/
     * 设置源 → 同步 no-op。
     */
    public void migrateFromEnabledPlugins() {
        Map<String, Boolean> enabledPlugins = enabledPluginsSupplier.get();
        if (enabledPlugins == null || enabledPlugins.isEmpty()) {
            // CC :1054 无 enabledPlugins 直接 return
            return;
        }
        InstalledPluginsFileStore store = this.fileStore;
        if (store == null) {
            if (log.isDebugEnabled()) {
                log.debug("[InstalledPluginsManager] 未接线文件层，跳过 enabledPlugins 同步");
            }
            return;
        }
        InstalledPluginsFileStore.InstalledPluginsFileV2 diskData = store.loadFromDisk();
        Map<String, List<InstalledPluginsFileStore.PluginInstallation>> plugins =
            new LinkedHashMap<>(diskData.plugins());
        String now = Instant.now().toString();
        int updatedCount = 0;
        int addedCount = 0;
        for (String pluginId : enabledPlugins.keySet()) {
            if (pluginId == null || !pluginId.contains("@")) {
                continue; // 仅同步标准插件 ID（CC :1119）
            }
            List<InstalledPluginsFileStore.PluginInstallation> existing = plugins.get(pluginId);
            if (existing != null && !existing.isEmpty()) {
                // 既有条目：settings 为真相源，纠正 scope/projectPath（CC :1149-1164）
                InstalledPluginsFileStore.PluginInstallation first = existing.get(0);
                if (first != null && (!"user".equals(first.scope()) || first.projectPath() != null)) {
                    InstalledPluginsFileStore.PluginInstallation corrected = new InstalledPluginsFileStore.PluginInstallation(
                        "user", null, first.installPath(), first.version(),
                        first.installedAt(), now, first.gitCommitSha(),
                        first.source(), first.enabled(), first.agentsPath(), first.sourcePath());
                    List<InstalledPluginsFileStore.PluginInstallation> list = new ArrayList<>(existing);
                    list.set(0, corrected);
                    plugins.put(pluginId, list);
                    updatedCount++;
                    log.info("InstalledPluginsManager: 按 settings 真相源纠正插件 scope plugin={} → user（CC :1155）", pluginId);
                }
                continue;
            }
            PluginIdentifier.Parsed parsed = PluginIdentifier.parse(pluginId);
            if (parsed.name().isEmpty() || parsed.marketplace() == null || parsed.marketplace().isEmpty()) {
                continue; // 无法解析 name@marketplace（CC :1171-1173）
            }
            try {
                // [MPL5-REWORK2] 添加分支整体 try/catch（CC :1175-1255）：单插件失败仅记录继续，
                // 防 resolver.apply/落盘异常沿 @PostConstruct 传导致启动级失败（CC catch :1254-1255）。
                PluginMarketplace.LookupResult lookup =
                    pluginResolver != null ? pluginResolver.apply(pluginId) : null;
                if (lookup == null || lookup.entry() == null) {
                    // CC :1180-1184 市场找不到 → 跳过（未接线解析器视同找不到，不写幽灵记录）
                    if (log.isDebugEnabled()) {
                        log.debug("[InstalledPluginsManager] 插件 {} 在任何 marketplace 未命中，跳过同步（CC :1180-1184）",
                            pluginId);
                    }
                    continue;
                }
                String version = lookup.entry().version();
                if (version == null || version.isBlank()) {
                    version = "unknown"; // CC :1231-1233 version===unknown 且无 entry.version 时保持 unknown
                }
                String installPath = InstalledPluginsFileStore.getVersionedCachePath(pluginId, version); // CC :1241
                String sourcePath = null;
                if (lookup.entry().source() != null && lookup.entry().source().isTextual()
                    && lookup.marketplaceInstallLocation() != null && !lookup.marketplaceInstallLocation().isBlank()) {
                    // CC :1194 join(marketplaceInstallLocation, entry.source) · 插件真实本地安装目录（ODF-C3R feed）
                    sourcePath = Paths.get(lookup.marketplaceInstallLocation()).resolve(lookup.entry().source().asText()).toString();
                }
                plugins.put(pluginId, List.of(new InstalledPluginsFileStore.PluginInstallation(
                    "user", null, installPath, version, now, now, null, null, null, null, sourcePath)));
                addedCount++;
                log.info("InstalledPluginsManager: 从 enabledPlugins 同步安装记录 plugin={} version={} installPath={}（CC :1238-1250）",
                    pluginId, version, installPath);
            } catch (Exception e) {
                // CC :1254-1255 单插件添加失败仅记录继续，不阻断其余插件与启动
                log.warn("InstalledPluginsManager: 同步插件 {} 失败，跳过该插件继续（CC catch :1254-1255）", pluginId, e);
            }
        }
        if (updatedCount > 0 || addedCount > 0) {
            // Step 4: 单次写盘（CC :1260-1267，fileExists/updated>0/added>0）
            store.save(new InstalledPluginsFileStore.InstalledPluginsFileV2(2, plugins));
        }
    }

    public InstalledRecord install(String name, String version, String scope) {
        return install(name, version, scope, null, null);
    }

    /**
     * [ODF-C3R] 带安装路径/agentsPath 的安装 · 插件 manifest 解析出 agentsPath 后经本方法记录，
     * 装配期 {@code PluginLoader.loadInstalledEnabledPlugins()} 才能调 4 参 load 注册扫描配置。
     *
     * @param sourcePath 插件本地安装目录（可 null → 装配 feed 跳过该插件）
     * @param agentsPath 插件默认 agents 目录（可 null → 无 plugin agents 可扫）
     * @return 已登记 InstalledRecord
     */
    public InstalledRecord install(String name, String version, String scope,
                                   Path sourcePath, Path agentsPath) {
        long now = System.currentTimeMillis();
        String installPath = sourcePath != null ? sourcePath.toString() : null;
        InstalledRecord rec = new InstalledRecord(name, version, scope, true, now,
            sourcePath, agentsPath, null, installPath, now, null);
        installed.put(name, rec);
        writeThrough(rec);
        log.info("InstalledPluginsManager: installed plugin={} v={} scope={} sourcePath={} agentsPath={}",
            name, version, scope, sourcePath, agentsPath);
        return rec;
    }

    /**
     * [MPL6] 带 6 类组件路径的安装 · 插件 manifest 解析出全部组件目录后经本方法记录，
     * 装配期 {@code PluginLoader.loadInstalledEnabledPlugins()} 才能调
     * {@code loadFromInstalled} 注册 6 类路径字段 → 组件扫描（commands/skills/output-styles）
     * 真正生效。
     *
     * @param sourcePath        插件本地安装目录（可 null → 装配 feed 跳过该插件）
     * @param agentsPath        插件默认 agents 目录（可 null）
     * @param agentsPaths       manifest 附加 agents 路径（可空）
     * @param commandsPath      插件默认 commands 目录（可 null）
     * @param commandsPaths     manifest 附加命令路径（可空）
     * @param skillsPath        插件默认 skills 目录（可 null）
     * @param skillsPaths       manifest 附加技能路径（可空）
     * @param outputStylesPath  插件默认 output-styles 目录（可 null）
     * @param outputStylesPaths manifest 附加样式路径（可空）
     * @return 已登记 InstalledRecord
     */
    public InstalledRecord install(String name, String version, String scope,
                                   Path sourcePath, Path agentsPath,
                                   List<String> agentsPaths,
                                   Path commandsPath, List<String> commandsPaths,
                                   Path skillsPath, List<String> skillsPaths,
                                   Path outputStylesPath, List<String> outputStylesPaths) {
        return install(name, version, scope, sourcePath, agentsPath, agentsPaths,
            commandsPath, commandsPaths, skillsPath, skillsPaths,
            outputStylesPath, outputStylesPaths, Map.of());
    }

    /**
     * [P2-14] 带 6 类组件路径 + commandsMetadata 的安装 · 插件 manifest object-mapping
     * （pluginLoader.ts:1405-1470）解析出命令元数据后经本方法记录。
     *
     * @param commandsMetadata manifest object-mapping 命令元数据（可空 → 无 override/inline 命令）
     * @return 已登记 InstalledRecord
     */
    public InstalledRecord install(String name, String version, String scope,
                                   Path sourcePath, Path agentsPath,
                                   List<String> agentsPaths,
                                   Path commandsPath, List<String> commandsPaths,
                                   Path skillsPath, List<String> skillsPaths,
                                   Path outputStylesPath, List<String> outputStylesPaths,
                                   Map<String, PluginLoader.CommandMetadata> commandsMetadata) {
        long now = System.currentTimeMillis();
        String installPath = sourcePath != null ? sourcePath.toString() : null;
        InstalledRecord rec = new InstalledRecord(name, version, scope, true, now,
            sourcePath, agentsPath, null, installPath, now, null,
            commandsPath, commandsPaths, skillsPath, skillsPaths, outputStylesPath, outputStylesPaths,
            commandsMetadata != null ? commandsMetadata : Map.of());
        installed.put(name, rec);
        writeThrough(rec);
        if (log.isDebugEnabled()) {
            log.debug("[InstalledPluginsManager] installed plugin={} v={} scope={} commandsPath={} skillsPath={} outputStylesPath={} commandsMetadataKeys={}",
                name, version, scope, commandsPath, skillsPath, outputStylesPath,
                commandsMetadata != null ? commandsMetadata.size() : 0);
        }
        return rec;
    }

    /**
     * [MPL4] 注册表写入 · 对齐 CC {@code addInstalledPlugin}（installedPluginsManager.ts:874-912）。
     *
     * <p>安装链 cacheAndRegisterPlugin 收尾：version/installedAt/installPath/gitCommitSha 四字段持久化
     * （session MPL4 验收 #4）。scope/projectPath 语义：CC scope 文件层统一映射 user
     * （MPL5 §8 决策），projectPath 仅 project/local scope 非空（CC pluginInstallationHelpers.ts:447）。
     *
     * @param pluginId     CC original: pluginId（name@marketplace）· Java 键取裸名（MPL5 concern #3）
     * @param version      CC original: version（schemas.ts:1528）
     * @param installedAt  epoch millis（CC installedAt ISO 由 writeThrough 转换）
     * @param installPath  CC original: installPath（schemas.ts:1524-1526）· 版本化插件目录绝对路径
     * @param gitCommitSha CC original: gitCommitSha（schemas.ts:1537-1540）
     * @param scope        CC installable scope（user/project/local）· 落盘统一 user（MPL5 §8）
     * @param projectPath  project/local scope 项目路径（可 null）
     * @return 已登记 InstalledRecord
     */
    public InstalledRecord addInstalledPlugin(String pluginId, String version, long installedAt,
                                              String installPath, String gitCommitSha,
                                              String scope, String projectPath) {
        String name = PluginIdentifier.parse(pluginId).name();
        if (name.isEmpty()) {
            name = pluginId;
        }
        Path installPathPath = installPath != null ? Paths.get(installPath) : null;
        InstalledRecord rec = new InstalledRecord(name, version, "marketplace", true, installedAt,
            installPathPath, null, projectPath, installPath, installedAt, gitCommitSha);
        installed.put(name, rec);
        writeThrough(rec);
        if (log.isInfoEnabled()) {
            log.info("InstalledPluginsManager: 注册安装 plugin={} v={} installPath={} gitCommitSha={}（CC addInstalledPlugin :874-912）",
                name, version, installPath, gitCommitSha);
        }
        return rec;
    }

    public boolean uninstall(String name) {
        boolean removed = installed.remove(name) != null;
        if (removed) {
            InstalledPluginsFileStore store = this.fileStore;
            if (store != null) {
                store.removeInstallation(name, "user", null);
            }
            log.info("InstalledPluginsManager: uninstalled plugin={}", name);
        }
        return removed;
    }

    public InstalledRecord setEnabled(String name, boolean enabled) {
        InstalledRecord rec = installed.get(name);
        if (rec == null) return null;
        InstalledRecord updated = rec.withEnabled(enabled);
        installed.put(name, updated);
        writeThrough(updated);
        return updated;
    }

    public InstalledRecord get(String name) {
        return installed.get(name);
    }

    public List<InstalledRecord> list() {
        return List.copyOf(installed.values());
    }

    /**
     * [MPL5] 检测待处理更新 · 对齐 CC {@code hasPendingUpdates}（:595-618）。
     * 磁盘某条目（同 scope+projectPath）installPath 与内存不同 → true。
     * 未接线文件层 → false（无盘可比）。
     */
    public boolean hasPendingUpdates() {
        InstalledPluginsFileStore store = this.fileStore;
        if (store == null) {
            return false;
        }
        return store.hasPendingUpdates(toV2State());
    }

    /**
     * [MPL5] 仅改盘不改内存 · 对齐 CC {@code updateInstallationPathOnDisk}（:537-587）。
     * 后台更新器下载新版本后记录磁盘新版本，会话内存保持旧版本。
     *
     * @param name       插件名
     * @param newPath    新版本目录绝对路径
     * @param newVersion 新版本串
     * @param gitCommitSha git commit SHA（可 null → 保留原值）
     */
    public void updateInstallationPathOnDisk(String name, String newPath, String newVersion, String gitCommitSha) {
        InstalledPluginsFileStore store = this.fileStore;
        if (store == null) {
            return;
        }
        store.updateInstallationPathOnDisk(name, "user", null, newPath, newVersion, gitCommitSha);
    }

    // =========================================================================
    // 内部：内存 ↔ 文件双向映射
    // =========================================================================

    /** 每次变更写穿磁盘（文件为真相源，CC addPluginInstallation :406-443）。未接线文件层 → no-op。 */
    private void writeThrough(InstalledRecord rec) {
        InstalledPluginsFileStore store = this.fileStore;
        if (store == null) {
            return;
        }
        String installPath = rec.installPath() != null ? rec.installPath()
            : (rec.sourcePath() != null ? rec.sourcePath().toString()
            : InstalledPluginsFileStore.getVersionedCachePath(rec.name(), rec.version()));
        store.addInstallation(rec.name(), "user", installPath, new InstalledPluginsFileStore.PluginInstallation(
            "user", rec.projectPath(), installPath, rec.version(),
            iso(rec.installedAt()), iso(rec.lastUpdated()), rec.gitCommitSha(),
            rec.scope(), rec.enabled(),
            rec.agentsPath() != null ? rec.agentsPath().toString() : null,
            rec.sourcePath() != null ? rec.sourcePath().toString() : null), rec.projectPath());
    }

    /** 磁盘 V2 条目 → 内存记录（scope 缺省 marketplace、enabled 缺省 true）。 */
    private static InstalledRecord fromPluginInstallation(String name,
                                                          InstalledPluginsFileStore.PluginInstallation pi) {
        String installPath = pi.installPath();
        Path sourcePath = pi.sourcePath() != null ? Paths.get(pi.sourcePath())
            : (installPath != null ? Paths.get(installPath) : null);
        Path base = sourcePath != null ? sourcePath : (installPath != null ? Paths.get(installPath) : null);
        // [esc-cancel-ccalign] 对齐 CC pluginLoader.ts:1370-1395 + 1558-1561 Step 3/4b auto-detect：
        //   插件默认组件目录检测（manifest 未显式声明时）：<plugin.path>/commands、/skills、/output-styles
        //   存在即注册。WHY: 此前 11 参构造 skillsPath/commandsPath 恒 null → LoadPluginCommands.loadSkills
        //   空 → zjkycode 等插件技能命令（/zjkycode:brainstorming）不进入 GET /api/command，前端 /z
        //   斜杠补全按 pluginName 前缀匹配失败（CC 插件技能命令名 = pluginName:skillName）。
        Path commandsPath = dirExists(base, "commands");
        Path skillsPath = dirExists(base, "skills");
        Path outputStylesPath = dirExists(base, "output-styles");
        return new InstalledRecord(
            name,
            pi.version(),
            pi.source() != null ? pi.source() : "marketplace",
            pi.enabled() == null || pi.enabled(),
            parseEpoch(pi.installedAt()),
            sourcePath,
            pi.agentsPath() != null ? Paths.get(pi.agentsPath()) : null,
            pi.projectPath(),
            installPath,
            parseEpoch(pi.lastUpdated()),
            pi.gitCommitSha(),
            commandsPath, List.of(), skillsPath, List.of(), outputStylesPath, List.of(), Map.of());
    }

    /** <code>&lt;base&gt;/child</code> 存在且为目录 → 返回路径，否则 null（CC pathExists 等价）。 */
    private static Path dirExists(Path base, String child) {
        if (base == null) {
            return null;
        }
        Path p = base.resolve(child);
        return Files.isDirectory(p) ? p : null;
    }

    /** 内存 → V2 视图（供 hasPendingUpdates 与磁盘比对）。 */
    private InstalledPluginsFileStore.InstalledPluginsFileV2 toV2State() {
        Map<String, List<InstalledPluginsFileStore.PluginInstallation>> plugins = new LinkedHashMap<>();
        for (InstalledRecord rec : installed.values()) {
            String installPath = rec.installPath() != null ? rec.installPath()
                : (rec.sourcePath() != null ? rec.sourcePath().toString()
                : InstalledPluginsFileStore.getVersionedCachePath(rec.name(), rec.version()));
            plugins.put(rec.name(), List.of(new InstalledPluginsFileStore.PluginInstallation(
                "user", rec.projectPath(), installPath, rec.version(),
                iso(rec.installedAt()), iso(rec.lastUpdated()), rec.gitCommitSha(),
                rec.scope(), rec.enabled(),
                rec.agentsPath() != null ? rec.agentsPath().toString() : null,
                rec.sourcePath() != null ? rec.sourcePath().toString() : null)));
        }
        return new InstalledPluginsFileStore.InstalledPluginsFileV2(2, plugins);
    }

    private static String iso(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    private static long parseEpoch(String iso) {
        if (iso == null || iso.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
