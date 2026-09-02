package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.settings.storage.ConfigStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * startup 接线 · 对齐 CC {@code utils/plugins/performStartupChecks.tsx:24-69 performStartupChecks}.
 *
 * <p>职责（CC 顺序）：
 * <ol>
 *   <li><b>seed 注册</b>（:42-60）：{@link MarketplaceManager#registerSeedMarketplaces()} 幂等注册
 *       CLAUDE_CODE_PLUGIN_SEED_DIR；若注册改变状态 → clearMarketplacesCache + clearPluginCache +
 *       AppState needsRefresh=true（否则早前插件加载的 "marketplace not found" 缓存残留）；</li>
 *   <li><b>后台安装</b>（:64）：{@link PluginInstallationManager#performBackgroundPluginInstallations()}
 *       不阻塞 startup；</li>
 *   <li><b>异常降级</b>（:65-68）：任何失败只记日志，不阻断 startup。</li>
 * </ol>
 *
 * <p><b>安全注释（CC :16-22）</b>：CC 仅在其 "trust this folder" 对话框确认后调用本函数。Java 端
 * trust 门禁不在本模块（会话提示词未列），装配方负责在已获授权路径调用；本类不做 trust 判定。
 */
public class PerformStartupChecks {

    private static final Logger log = LoggerFactory.getLogger(PerformStartupChecks.class);

    private final MarketplaceManager marketplaceManager;
    private final PluginLoader pluginLoader;
    private final MarketplaceConfigStore store;
    private final MarketplaceReconciler reconciler;
    private final ActivePluginRefresher refresher;

    public PerformStartupChecks(MarketplaceManager marketplaceManager,
                                PluginLoader pluginLoader,
                                MarketplaceConfigStore store,
                                MarketplaceReconciler reconciler,
                                ActivePluginRefresher refresher) {
        this.marketplaceManager = Objects.requireNonNull(marketplaceManager);
        this.pluginLoader = pluginLoader;
        this.store = Objects.requireNonNull(store);
        this.reconciler = Objects.requireNonNull(reconciler);
        this.refresher = refresher;
    }

    /**
     * 装配工厂：一键接线完整 reconcile 数据面 · CC 无对应（Java 装配 seam）。
     *
     * <p>build MarketplaceConfigStore → MarketplaceReconciler → ActivePluginRefresher →
     * PerformStartupChecks。startup 入口（REPL trust 之后）调用
     * {@link #performStartupChecks(Consumer)} 即可（CC performStartupChecks.tsx:24-69）。
     *
     * @param marketplaceManager 既有配置/同步/查找 manager
     * @param configStorage      settings 意图层（declared 读取；null → declared 空早期 return）
     * @param pluginLoader       L7 插件加载器（刷新原语依赖）
     * @param cwdSupplier        original cwd（diff 源归一化 projectRoot；null → user.dir）
     */
    public static PerformStartupChecks wire(MarketplaceManager marketplaceManager,
                                            ConfigStorage configStorage,
                                            PluginLoader pluginLoader,
                                            Supplier<String> cwdSupplier) {
        MarketplaceConfigStore store = new MarketplaceConfigStore(marketplaceManager, configStorage, cwdSupplier);
        MarketplaceReconciler reconciler = new MarketplaceReconciler(store);
        ActivePluginRefresher refresher = new ActivePluginRefresher(marketplaceManager, pluginLoader);
        return new PerformStartupChecks(marketplaceManager, pluginLoader, store, reconciler, refresher);
    }

    /**
     * 执行 startup 插件检查 + 后台安装 · CC {@code performStartupChecks}（performStartupChecks.tsx:24-69）。
     *
     * @param setAppState AppState 更新器（seedChanged → needsRefresh；后台安装 status 透出）。
     *                    null → status 不透出（headless，仅落盘）
     */
    public void performStartupChecks(Consumer<Object> setAppState) {
        if (log.isDebugEnabled()) {
            log.debug("performStartupChecks 调用（MPL8 startup 接线）");
        }
        try {
            boolean seedChanged = marketplaceManager.registerSeedMarketplaces();
            if (seedChanged) {
                if (log.isDebugEnabled()) {
                    log.debug("seed marketplaces 注册改变状态，清缓存 + needsRefresh");
                }
                marketplaceManager.clearMarketplacesCache();
                if (pluginLoader != null) {
                    pluginLoader.clearPluginCache("performStartupChecks: seed marketplaces changed");
                }
                setNeedsRefresh(setAppState);
            }
            // CC :64 —— setAppState 透传给后台安装（status pending/installing/installed 更新）；
            // pluginCacheClearer 对齐 PluginInstallationManager.ts:155/:170 的 clearPluginCache（仅更新/refresh 失败时触发）
            PluginInstallationManager installationManager =
                PluginInstallationManager.wire(store, reconciler, refresher, setAppState, null,
                    pluginLoader != null
                        ? () -> pluginLoader.clearPluginCache("performBackgroundPluginInstallations")
                        : null);
            installationManager.performBackgroundPluginInstallations();
        } catch (Exception error) {
            // CC :65-68 —— 即使失败也不阻断 startup
            log.warn("启动后台插件安装失败（不阻断 startup）：{}", error.getMessage());
        }
    }

    /** AppState needsRefresh=true（仅未置时置位，对齐 performStartupChecks.tsx:50-59）。 */
    @SuppressWarnings("unchecked")
    private void setNeedsRefresh(Consumer<Object> setAppState) {
        if (setAppState == null) {
            return;
        }
        setAppState.accept((Object) (java.util.function.Function<Map<String, Object>, Map<String, Object>>)
            prev -> {
                Map<String, Object> plugins = (Map<String, Object>) prev.get("plugins");
                if (plugins == null) {
                    return prev;
                }
                if (Boolean.TRUE.equals(plugins.get("needsRefresh"))) {
                    return prev;
                }
                Map<String, Object> newPlugins = new LinkedHashMap<>(plugins);
                newPlugins.put("needsRefresh", true);
                Map<String, Object> newState = new LinkedHashMap<>(prev);
                newState.put("plugins", newPlugins);
                return newState;
            });
    }
}
