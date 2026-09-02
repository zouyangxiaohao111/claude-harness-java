package com.nexusai.application.agent.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plugin delisting 检测 · 对齐 CC {@code utils/plugins/pluginBlocklist.ts}（127 行）。
 *
 * <p>比对已装插件与 marketplace manifest，找出已被移除（delisted）的插件并自动卸载。
 * security.json 拉取已移除（#25447，~29.5M/周 GitHub 命中）——若重引入须从 downloads.claude.ai 提供。
 *
 * <p><b>门槛</b>（:75-98）：仅当 marketplace 的 {@code forceRemoveDeletedPlugins} 为 true 才检测
 * delisted（否则上架列表漂移不算问题）；跳过已 flagged 的插件；跳过 managed-only 安装
 * （enterprise admin 处理）；只从 user/project/local scope 自动卸载。自动卸载后写 flagged
 * （:114），用户可在 /plugins 查看。
 *
 * <p><b>CC 行号索引</b>：
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #detectDelistedPlugins}</td><td>{@code detectDelistedPlugins}</td><td>pluginBlocklist.ts:34-53</td></tr>
 *   <tr><td>{@link #detectAndUninstallDelistedPlugins}</td><td>{@code detectAndUninstallDelistedPlugins}</td><td>pluginBlocklist.ts:64-127</td></tr>
 * </table>
 */
@Component
public class PluginBlocklist {

    private static final Logger log = LoggerFactory.getLogger(PluginBlocklist.class);

    private static final List<String> USER_CONTROLLABLE_SCOPES = List.of("user", "project", "local");

    /** 单个已装插件的 installation · CC InstalledPluginsFileV2.plugins[id] 元素（scope/projectPath/installPath 子集）。 */
    public record Installation(String scope, String projectPath, String installPath) {

        /** 便捷构造：无 installPath（旧数据面 / 测试）→ null 视为存在（兼容）。 */
        public Installation(String scope, String projectPath) {
            this(scope, projectPath, null);
        }
    }

    /** 已装插件注册表 provider（detectAndUninstallDelistedPlugins 数据源）。 */
    @FunctionalInterface
    public interface InstalledPluginsProvider {
        /** 返回 {@code pluginId -> installations}。 */
        Map<String, List<Installation>> load();
    }

    /** marketplace 按名加载 provider。 */
    @FunctionalInterface
    public interface MarketplaceProvider {
        PluginMarketplace.Marketplace get(String name);
    }

    /** known_marketplaces 全量加载 provider（no-arg detect 数据源）。 */
    @FunctionalInterface
    public interface KnownMarketplacesProvider {
        Map<String, PluginSchemas.KnownMarketplace> load();
    }

    /** 卸载操作 · CC {@code uninstallPluginOp}（pluginOperations.ts）。 */
    @FunctionalInterface
    public interface UninstallOp {
        void uninstall(String pluginId, String scope) throws Exception;
    }

    private final InstalledPluginsProvider installedPluginsProvider;
    private final MarketplaceProvider marketplaceProvider;
    private final KnownMarketplacesProvider marketplacesProvider;
    private final PluginFlagging flagging;
    private final UninstallOp uninstallOp;
    /** installPath 存在性谓词（Java 侧增强 · 非 CC 语义，见 09-open-decisions OPD-MPL9-N1N2-installPath）。 */
    private final java.util.function.Predicate<String> installPathExists;

    /**
     * @param installedPluginsProvider 已装插件数据源（null → 空 Map，detect 返回空）
     * @param marketplaceProvider      marketplace 加载（null → 空，检测无对象）
     * @param flagging                 flagged 存储（null → 内部 new，避免 null 语义）
     * @param uninstallOp              卸载回调（null → no-op，仅标记不真删）
     */
    public PluginBlocklist(InstalledPluginsProvider installedPluginsProvider,
                           MarketplaceProvider marketplaceProvider,
                           PluginFlagging flagging,
                           UninstallOp uninstallOp) {
        this(installedPluginsProvider, marketplaceProvider, flagging, uninstallOp, null, null);
    }

    public PluginBlocklist(InstalledPluginsProvider installedPluginsProvider,
                           MarketplaceProvider marketplaceProvider,
                           PluginFlagging flagging,
                           UninstallOp uninstallOp,
                           KnownMarketplacesProvider marketplacesProvider) {
        this(installedPluginsProvider, marketplaceProvider, flagging, uninstallOp, marketplacesProvider, null);
    }

    public PluginBlocklist(InstalledPluginsProvider installedPluginsProvider,
                           MarketplaceProvider marketplaceProvider,
                           PluginFlagging flagging,
                           UninstallOp uninstallOp,
                           KnownMarketplacesProvider marketplacesProvider,
                           java.util.function.Predicate<String> installPathExists) {
        this.installedPluginsProvider = installedPluginsProvider == null ? Map::of : installedPluginsProvider;
        this.marketplaceProvider = marketplaceProvider == null ? n -> null : marketplaceProvider;
        this.marketplacesProvider = marketplacesProvider == null ? Map::of : marketplacesProvider;
        this.flagging = flagging == null ? new PluginFlagging() : flagging;
        this.uninstallOp = uninstallOp == null ? (id, scope) -> {
        } : uninstallOp;
        // 默认：installPath 为 null 视为存在（兼容旧数据面）；非 null 判目录存在。
        this.installPathExists = installPathExists == null
            ? p -> p == null || java.nio.file.Files.isDirectory(java.nio.file.Paths.get(p))
            : installPathExists;
    }

    /** 默认装配（无注入 provider → 空数据源，检测空转）。 */
    public PluginBlocklist() {
        this(null, null, null, null, null);
    }

    /** 测试可观测：flagged 存储（验证自动卸载写 flagged）。 */
    PluginFlagging getFlagging() {
        return flagging;
    }

    /**
     * 检测某 marketplace 已装但不再列出的插件 · CC {@code detectDelistedPlugins}（:34-53）。
     *
     * @param installedPlugins 已装插件 {@code pluginId -> installations}
     * @param marketplace      待比对的 marketplace
     * @param marketplaceName  marketplace 名称后缀（如 "claude-plugins-official"）
     * @return delisted 插件 ID 列表（{@code name@marketplace}）
     */
    public List<String> detectDelistedPlugins(Map<String, List<Installation>> installedPlugins,
                                              PluginMarketplace.Marketplace marketplace,
                                              String marketplaceName) {
        Set<String> marketplacePluginNames = new HashSet<>();
        if (marketplace != null && marketplace.plugins() != null) {
            for (PluginMarketplace.Entry p : marketplace.plugins()) {
                marketplacePluginNames.add(p.name());
            }
        }
        String suffix = "@" + marketplaceName;

        List<String> delisted = new ArrayList<>();
        for (String pluginId : installedPlugins.keySet()) {
            if (!pluginId.endsWith(suffix)) {
                continue;
            }
            String pluginName = pluginId.substring(0, pluginId.length() - suffix.length());
            if (!marketplacePluginNames.contains(pluginName)) {
                delisted.add(pluginId);
            }
        }
        return delisted;
    }

    /**
     * 检测全部 marketplace 的 delisted 插件，自动卸载并写 flagged · CC
     * {@code detectAndUninstallDelistedPlugins}（:64-127）。
     *
     * <p>逐 marketplace：非 {@code forceRemoveDeletedPlugins} 跳过；delisted 且未 flagged 且
     * 有 user/project/local 安装 → 从这些 scope 卸载 + addFlaggedPlugin。单 marketplace 失败
     * 仅 warn 继续（marketplace 可能暂不可用，:117-123）。
     *
     * @param installedPlugins 已装插件
     * @param knownMarketplaces known_marketplaces.json 全量（name → entry）
     * @return 新 flagged 的插件 ID 列表
     */
    public List<String> detectAndUninstallDelistedPlugins(
            Map<String, List<Installation>> installedPlugins,
            Map<String, PluginSchemas.KnownMarketplace> knownMarketplaces) {
        flagging.loadFlaggedPlugins();
        Set<String> alreadyFlagged = new HashSet<>(flagging.getFlaggedPlugins().keySet());
        List<String> newlyFlagged = new ArrayList<>();

        for (Map.Entry<String, PluginSchemas.KnownMarketplace> e : knownMarketplaces.entrySet()) {
            String marketplaceName = e.getKey();
            PluginMarketplace.Marketplace marketplace;
            try {
                marketplace = marketplaceProvider.get(marketplaceName);
            } catch (Exception error) {
                log.warn("检测 \"{}\" 的 delisted 插件失败：{}", marketplaceName, error.getMessage());
                continue;
            }
            if (marketplace == null || !Boolean.TRUE.equals(marketplace.forceRemoveDeletedPlugins())) {
                continue;
            }
            // 仅此处执行 detectDelistedPlugins —— 全部 delisted 检测都受 forceRemoveDeletedPlugins 门槛约束

            List<String> delisted = detectDelistedPlugins(installedPlugins, marketplace, marketplaceName);
            for (String pluginId : delisted) {
                if (alreadyFlagged.contains(pluginId)) {
                    continue;
                }
                // 跳过 managed-only 安装 —— enterprise admin 处理（:90-96）
                List<Installation> installations = installedPlugins.getOrDefault(pluginId, List.of());
                boolean hasUserInstall = installations.stream().anyMatch(i ->
                    "user".equals(i.scope()) || "project".equals(i.scope()) || "local".equals(i.scope()));
                if (!hasUserInstall) {
                    continue;
                }
                // [N2 增强 · 非 CC 语义，见 09-open-decisions OPD-MPL9-N1N2-installPath]
                // flag 写入前判 installPath 存在：全部安装 installPath 均不存在（非 null 且目录缺失）→
                // 路径已消失无 flag 意义，跳过。installPath 为 null 视为存在（兼容旧数据面）。
                boolean anyInstallPathExists = installations.stream()
                    .anyMatch(i -> installPathExists.test(i.installPath()));
                if (!anyInstallPathExists) {
                    if (log.isDebugEnabled()) {
                        log.debug("delisted 插件 {} 的安装路径均不存在，跳过 flag：{}",
                            pluginId, installations.stream().map(Installation::installPath).toList());
                    }
                    continue;
                }
                // 从所有 user-controllable scope 自动卸载（:98-112）
                for (Installation installation : installations) {
                    String scope = installation.scope();
                    if (!USER_CONTROLLABLE_SCOPES.contains(scope)) {
                        continue;
                    }
                    try {
                        uninstallOp.uninstall(pluginId, scope);
                    } catch (Exception error) {
                        log.warn("自动卸载 delisted 插件 {} 从 {} 失败：{}", pluginId, scope, error.getMessage());
                    }
                }
                flagging.addFlaggedPlugin(pluginId);
                newlyFlagged.add(pluginId);
            }
        }
        return newlyFlagged;
    }

    /**
     * 无参入口 · 对齐 CC {@code detectAndUninstallDelistedPlugins}（:64）签名：内部经注入的
     * providers 加载已装插件 + known_marketplaces 全量。数据源未注入 → 空转返回空。
     */
    public List<String> detectAndUninstallDelistedPlugins() {
        Map<String, List<Installation>> installed = installedPluginsProvider.load();
        Map<String, PluginSchemas.KnownMarketplace> known = marketplacesProvider.load();
        return detectAndUninstallDelistedPlugins(installed, known);
    }

    /**
     * 生产装配 · InstalledPluginsManager 已装数据面 + MarketplaceManager 缓存读取接线。
     *
     * <p>uninstallOp 留 no-op（Java PluginOperations.uninstallPlugin 是 L4 占位 stub，未接真实
     * 文件卸载）；接线点已登记 progress，待 L4 卸载层就绪后替换。marketplace 用 cache-only 读取
     * （delisting 检测不该触发网络拉源，对齐 CC loadKnownMarketplacesConfigSafe + getMarketplace
     * 的 safe 路径意图）。
     */
    public static PluginBlocklist wire(MarketplaceManager marketplaceManager, PluginFlagging flagging,
                                       InstalledPluginsManager installedManager) {
        return new PluginBlocklist(
            () -> {
                Map<String, List<Installation>> result = new LinkedHashMap<>();
                if (installedManager != null) {
                    for (InstalledPluginsManager.InstalledRecord rec : installedManager.list()) {
                        result.computeIfAbsent(rec.name(), n -> new ArrayList<>())
                            .add(new Installation(rec.scope(), rec.projectPath(), rec.installPath()));
                    }
                }
                return result;
            },
            name -> {
                try {
                    return marketplaceManager.getMarketplaceCacheOnly(name);
                } catch (Exception e) {
                    log.warn("读取 marketplace 缓存失败（{}）：{}", name, e.getMessage());
                    return null;
                }
            },
            flagging,
            null,
            marketplaceManager::loadKnownMarketplacesConfigSafe);
    }
}
