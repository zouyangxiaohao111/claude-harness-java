package com.nexusai.application.agent.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 后台插件自动更新 · 对齐 CC {@code utils/plugins/pluginAutoupdate.ts}（284 行）。
 *
 * <p>启动时模块：1) 先刷新 autoUpdate 启用的 marketplace；2) 再检查来自这些 marketplace 的已装插件
 * 并更新（:3-11）。更新是 non-inplace（仅磁盘，需重启生效）。官方 marketplace 默认 autoUpdate 启用，
 * 用户可逐 marketplace 关闭。
 *
 * <p><b>pendingNotification 竞态</b>（:38-65）：回调（REPL 挂载）注册前更新已完成 → 暂存
 * {@code pendingNotification}，注册时立即投递。{@link #onPluginsAutoUpdated} 返回取消函数。
 *
 * <p><b>CC 行号索引</b>：
 * <table>
 *   <tr><th>Java 方法</th><th>CC original</th><th>CC 行号</th></tr>
 *   <tr><td>{@link #onPluginsAutoUpdated}</td><td>{@code onPluginsAutoUpdated}</td><td>pluginAutoupdate.ts:51-65</td></tr>
 *   <tr><td>{@link #getAutoUpdateEnabledMarketplaces}</td><td>{@code getAutoUpdateEnabledMarketplaces}</td><td>pluginAutoupdate.ts:84-102</td></tr>
 *   <tr><td>{@link #updatePluginsForMarketplaces}</td><td>{@code updatePluginsForMarketplaces}</td><td>pluginAutoupdate.ts:161-200</td></tr>
 *   <tr><td>{@link #autoUpdateMarketplacesAndPluginsInBackground}</td><td>{@code autoUpdateMarketplacesAndPluginsInBackground}</td><td>pluginAutoupdate.ts:227-284</td></tr>
 * </table>
 *
 * <p>非 @Component：由 {@link PluginStartupAssembler} 经 {@link #wire} 显式装配
 * （daemon 线程 + 未接线 default 避免产生孤儿 bean）。
 */
public class PluginAutoupdate {

    private static final Logger log = LoggerFactory.getLogger(PluginAutoupdate.class);

    /** CC {@code PluginAutoUpdateCallback}（:35）· 更新通知回调。 */
    @FunctionalInterface
    public interface PluginAutoUpdateCallback extends Consumer<List<String>> {
    }

    /** CC {@code updatePluginOp} 结果形状（pluginOperations.ts updatePlugin 结果）。 */
    public record UpdateOutcome(boolean success, boolean alreadyUpToDate, String message,
                                String oldVersion, String newVersion) {
        public static UpdateOutcome upToDate() {
            return new UpdateOutcome(true, true, "already up to date", null, null);
        }
    }

    /** 更新单个插件单 scope · CC {@code updatePluginOp}。 */
    @FunctionalInterface
    public interface UpdatePluginOp {
        UpdateOutcome update(String pluginId, String scope);
    }

    /** 已装插件 provider · {@code pluginId -> installations}。 */
    @FunctionalInterface
    public interface InstalledPluginsProvider {
        Map<String, List<PluginBlocklist.Installation>> load();
    }

    /** marketplace 刷新 · CC {@code refreshMarketplace}（disableCredentialHelper:true）。 */
    @FunctionalInterface
    public interface MarketplaceRefreshOp {
        void refresh(String name) throws IOException;
    }

    /** 自动更新总开关 · CC {@code shouldSkipPluginAutoupdate}（config.ts）。 */
    private final Supplier<Boolean> skipCheck;

    private final InstalledPluginsProvider installedPluginsProvider;
    private final MarketplaceRefreshOp refreshOp;
    private final UpdatePluginOp updateOp;
    private final Supplier<Map<String, PluginSchemas.KnownMarketplace>> marketplacesProvider;
    /** settings 声明层 · CC {@code getDeclaredMarketplaces}（marketplaceManager.ts:161-193）。 */
    private final Supplier<Map<String, MarketplaceConfigStore.DeclaredMarketplace>> declaredMarketplacesProvider;

    // 回调 + pendingNotification 竞态缓存（CC :38-42）
    private volatile PluginAutoUpdateCallback pluginUpdateCallback;
    private volatile List<String> pendingNotification;

    /** 后台线程池（daemon · 不阻塞 JVM 退出）。 */
    private final java.util.concurrent.ExecutorService background =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "plugin-autoupdate-background");
            t.setDaemon(true);
            return t;
        });

    public PluginAutoupdate(Supplier<Boolean> skipCheck,
                            InstalledPluginsProvider installedPluginsProvider,
                            MarketplaceRefreshOp refreshOp,
                            UpdatePluginOp updateOp,
                            Supplier<Map<String, PluginSchemas.KnownMarketplace>> marketplacesProvider) {
        this(skipCheck, installedPluginsProvider, refreshOp, updateOp, marketplacesProvider, null);
    }

    public PluginAutoupdate(Supplier<Boolean> skipCheck,
                            InstalledPluginsProvider installedPluginsProvider,
                            MarketplaceRefreshOp refreshOp,
                            UpdatePluginOp updateOp,
                            Supplier<Map<String, PluginSchemas.KnownMarketplace>> marketplacesProvider,
                            Supplier<Map<String, MarketplaceConfigStore.DeclaredMarketplace>> declaredMarketplacesProvider) {
        this.skipCheck = skipCheck == null ? () -> false : skipCheck;
        this.installedPluginsProvider = installedPluginsProvider == null ? Map::of : installedPluginsProvider;
        this.refreshOp = refreshOp == null ? name -> {
        } : refreshOp;
        this.updateOp = updateOp == null ? (id, scope) -> UpdateOutcome.upToDate() : updateOp;
        this.marketplacesProvider = marketplacesProvider == null ? Map::of : marketplacesProvider;
        this.declaredMarketplacesProvider = declaredMarketplacesProvider == null ? Map::of : declaredMarketplacesProvider;
    }

    public PluginAutoupdate() {
        this(null, null, null, null, null, null);
    }

    /**
     * 注册更新回调 · CC {@code onPluginsAutoUpdated}（:51-65）。
     * 若注册前已有 pending 更新，立即投递并清空。返回取消函数。
     */
    public Runnable onPluginsAutoUpdated(PluginAutoUpdateCallback callback) {
        pluginUpdateCallback = callback;
        List<String> pending = pendingNotification;
        if (pending != null && !pending.isEmpty()) {
            callback.accept(pending);
            pendingNotification = null;
        }
        return () -> pluginUpdateCallback = null;
    }

    /**
     * 检查 pending 更新是否来自 autoupdate（通知用途）· CC {@code getAutoUpdatedPluginNames}（:71-78）。
     * Java 侧 pending 明细依赖 InstalledPluginsManager 数据面（MPL5 hasPendingUpdates 布尔，无明细），
     * 返回最近一次 autoupdate 已投递/暂存的插件名。
     */
    public List<String> getAutoUpdatedPluginNames() {
        List<String> pending = pendingNotification;
        if (pending == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String pluginId : pending) {
            names.add(PluginIdentifier.parse(pluginId).name());
        }
        return names;
    }

    /**
     * 获取 autoUpdate 启用的 marketplace 名集 · CC {@code getAutoUpdateEnabledMarketplaces}（:84-102）。
     *
     * <p><b>declared 优先层</b>（CC :89-99）：settings 声明源（extraKnownMarketplaces 条目的
     * {@code autoUpdate} 字段，CC :141）优先于 known_marketplaces.json 的 JSON 态 ——
     * {@code declaredAutoUpdate != null ? declaredAutoUpdate : isMarketplaceAutoUpdate(name, entry)}。
     * declared 无该市场或无 autoUpdate 时回退 JSON 态默认语义（schemas.ts:48-58：官方名单内市场
     * autoUpdate=null → true，其余 false）。
     */
    public Set<String> getAutoUpdateEnabledMarketplaces() {
        Map<String, PluginSchemas.KnownMarketplace> config = marketplacesProvider.get();
        Map<String, MarketplaceConfigStore.DeclaredMarketplace> declared = declaredMarketplacesProvider.get();
        Set<String> enabled = new HashSet<>();
        for (Map.Entry<String, PluginSchemas.KnownMarketplace> e : config.entrySet()) {
            MarketplaceConfigStore.DeclaredMarketplace decl = declared.get(e.getKey());
            Boolean declaredAutoUpdate = decl == null ? null : decl.autoUpdate();
            boolean autoUpdate = declaredAutoUpdate != null
                ? declaredAutoUpdate
                : PluginSchemas.isMarketplaceAutoUpdate(e.getKey(), e.getValue());
            if (autoUpdate) {
                enabled.add(e.getKey().toLowerCase(Locale.ROOT));
            }
        }
        return enabled;
    }

    /**
     * 更新给定 marketplaces 的项目相关已装插件 · CC {@code updatePluginsForMarketplaces}（:161-200）。
     *
     * <p>迭代 installed_plugins，过滤 marketplace 在集合内的插件，再过滤每插件与当前项目相关的
     * installations（user/managed scope 或 project/local scope 匹配 cwd），逐 installation 调
     * updatePluginOp。already-up-to-date 静默跳过。返回实际更新的插件 ID。
     */
    public List<String> updatePluginsForMarketplaces(Set<String> marketplaceNames) {
        Map<String, List<PluginBlocklist.Installation>> installedPlugins = installedPluginsProvider.load();
        if (installedPlugins.isEmpty()) {
            return List.of();
        }
        Set<String> names = marketplaceNames == null ? Set.of() : marketplaceNames;
        List<String> updated = new ArrayList<>();
        for (Map.Entry<String, List<PluginBlocklist.Installation>> e : installedPlugins.entrySet()) {
            String pluginId = e.getKey();
            String marketplace = PluginIdentifier.parse(pluginId).marketplace();
            if (marketplace == null || !names.contains(marketplace.toLowerCase(Locale.ROOT))) {
                continue;
            }
            List<PluginBlocklist.Installation> all = e.getValue();
            if (all == null || all.isEmpty()) {
                continue;
            }
            // CC isInstallationRelevantToCurrentProject：user scope 恒相关；project/local 需匹配 cwd。
            // Java 无 cwd 注入，project/local 视为相关（简化），登记 concerns。
            if (updatePlugin(pluginId, all)) {
                updated.add(pluginId);
            }
        }
        return updated;
    }

    /** 更新单插件全部相关 installations · CC {@code updatePlugin}（:108-138）。 */
    private boolean updatePlugin(String pluginId, List<PluginBlocklist.Installation> installations) {
        boolean wasUpdated = false;
        for (PluginBlocklist.Installation installation : installations) {
            try {
                UpdateOutcome result = updateOp.update(pluginId, installation.scope());
                if (result.success() && !result.alreadyUpToDate()) {
                    wasUpdated = true;
                    if (log.isDebugEnabled()) {
                        log.debug("插件自动更新: {} 从 {} → {}", pluginId, result.oldVersion(), result.newVersion());
                    }
                } else if (!result.alreadyUpToDate()) {
                    log.warn("插件自动更新: {} 失败: {}", pluginId, result.message());
                }
            } catch (Exception error) {
                log.warn("插件自动更新: {} 更新出错: {}", pluginId, error.getMessage());
            }
        }
        return wasUpdated;
    }

    /**
     * 后台自动更新 marketplace + 插件 · CC {@code autoUpdateMarketplacesAndPluginsInBackground}（:227-284）。
     *
     * <p>daemon 线程 fire-and-forget：skip 检查 → autoUpdate marketplace 集 → 仅刷新这些 marketplace
     * （disableCredentialHelper:true）→ 更新已装插件 → 有更新则投递回调或暂存 pending。
     */
    public void autoUpdateMarketplacesAndPluginsInBackground() {
        background.submit(this::runBackgroundAutoUpdate);
    }

    /** 同步执行后台逻辑（测试直接调用；入口异步包装）。 */
    void runBackgroundAutoUpdate() {
        if (Boolean.TRUE.equals(skipCheck.get())) {
            if (log.isDebugEnabled()) {
                log.debug("插件自动更新: 跳过（自动更新已禁用）");
            }
            return;
        }
        try {
            Set<String> enabledMarketplaces = getAutoUpdateEnabledMarketplaces();
            if (enabledMarketplaces.isEmpty()) {
                return;
            }
            // 仅刷新 autoUpdate 启用的 marketplace（disableCredentialHelper:true，CC :244-257）
            for (String name : enabledMarketplaces) {
                try {
                    refreshOp.refresh(name);
                } catch (Exception error) {
                    log.warn("插件自动更新: 刷新 marketplace {} 失败: {}", name, error.getMessage());
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("插件自动更新: 检查已装插件");
            }
            List<String> updatedPlugins = updatePluginsForMarketplaces(enabledMarketplaces);
            if (!updatedPlugins.isEmpty()) {
                PluginAutoUpdateCallback callback = pluginUpdateCallback;
                if (callback != null) {
                    callback.accept(updatedPlugins);
                } else {
                    pendingNotification = updatedPlugins;
                }
            }
        } catch (Exception error) {
            log.error("插件自动更新失败：{}", error.getMessage());
        }
    }

    /** 生产装配 · MarketplaceManager + InstalledPluginsManager 数据面 + declared 意图层。 */
    public static PluginAutoupdate wire(MarketplaceManager marketplaceManager,
                                        InstalledPluginsManager installedManager,
                                        MarketplaceConfigStore store) {
        return new PluginAutoupdate(
            () -> false,
            () -> {
                Map<String, List<PluginBlocklist.Installation>> result = new LinkedHashMap<>();
                if (installedManager != null) {
                    for (InstalledPluginsManager.InstalledRecord rec : installedManager.list()) {
                        result.computeIfAbsent(rec.name(), n -> new ArrayList<>())
                            .add(new PluginBlocklist.Installation(rec.scope(), rec.projectPath()));
                    }
                }
                return result;
            },
            name -> marketplaceManager.refreshMarketplace(name, true),
            (pluginId, scope) -> {
                // Java PluginOperations.updatePlugin 是 L4 占位 stub（无真实版本比较）——接线点登记
                return UpdateOutcome.upToDate();
            },
            marketplaceManager::loadKnownMarketplacesConfig,
            store == null ? null : store::getDeclaredMarketplaces);
    }

    /** 供 shutdown hook 关闭后台线程（不阻塞 JVM）。 */
    void shutdownBackground() {
        background.shutdownNow();
    }
}
