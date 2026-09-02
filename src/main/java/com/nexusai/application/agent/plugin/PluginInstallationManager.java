package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background plugin/marketplace installation manager · 对齐 CC services/plugins/PluginInstallationManager.ts.
 *
 * <p>L1 语义: 在 startup 后台检查并安装 marketplaces/plugins.
 *            - getDeclaredMarketplaces → 当前 app config 声明的 marketplaces
 *            - loadKnownMarketplacesConfig → 已缓存的 marketplaces config
 *            - diffMarketplaces → missing + sourceChanged
 *            - reconcileMarketplaces → install/update (event-based progress)
 *            - refreshActivePlugins → clear cache + reload
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: performBackgroundPluginInstallations(setAppState) → void;
 *       MarketplaceStatus 4 状态 (pending/installing/installed/failed);
 *       diff: missing + sourceChanged;reconcile: 4 状态结果 (installed/updated/upToDate/failed).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — diff → reconcile (按 event 触发 status update) →
 *       安装成功 → clearMarketplacesCache + refreshActivePlugins;
 *       更新成功 → set needsRefresh.</li>
 *   <li><b>A3</b>: 状态: PENDING → INSTALLING → INSTALLED/FAILED;
 *       全 up-to-date → return early (无 pendingNames).</li>
 *   <li><b>A4</b>: declared 空 → return early;
 *       loadKnownMarketplacesConfig throw → catch → {} (不阻断);
 *       reconcile throw → logError 但不 re-throw;
 *       refreshActivePlugins throw → fallback needsRefresh.</li>
 *   <li><b>A5</b>: 真实场景 — startup 加载 declared vs materialized → diff 3 marketplaces →
 *       reconcile (3 安装) → clear cache + refresh.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `(f: (prevState) => AppState) => void` (React setState) → 注入式 Consumer;
 *                    TS `prevState.plugins.installationStatus.marketplaces` → Java Map (setState impl).
 */
public final class PluginInstallationManager {

    private static final Logger log = LoggerFactory.getLogger(PluginInstallationManager.class);

    private final Supplier<List<Marketplace>> declaredSupplier;
    private final Supplier<Map<String, MarketplaceConfig>> materializedSupplier;
    private final MarketplacesDiffer differ;
    private final MarketplacesReconciler reconciler;
    private final Runnable cacheClearer;
    private final Runnable pluginCacheClearer;
    private final Consumer<Object> setAppState;
    private final PluginRefresher pluginRefresher;
    private final Consumer<String> errorLogger;

    /** 8 参便捷构造（pluginCacheClearer=null，未装配插件级缓存清空）。 */
    public PluginInstallationManager(Supplier<List<Marketplace>> declaredSupplier,
                                       Supplier<Map<String, MarketplaceConfig>> materializedSupplier,
                                       MarketplacesDiffer differ,
                                       MarketplacesReconciler reconciler,
                                       Runnable cacheClearer,
                                       Consumer<Object> setAppState,
                                       PluginRefresher pluginRefresher,
                                       Consumer<String> errorLogger) {
        this(declaredSupplier, materializedSupplier, differ, reconciler, cacheClearer,
            null, setAppState, pluginRefresher, errorLogger);
    }

    /** 9 参构造 · pluginCacheClearer 对齐 CC PluginInstallationManager.ts:155/:170 的 clearPluginCache。 */
    public PluginInstallationManager(Supplier<List<Marketplace>> declaredSupplier,
                                       Supplier<Map<String, MarketplaceConfig>> materializedSupplier,
                                       MarketplacesDiffer differ,
                                       MarketplacesReconciler reconciler,
                                       Runnable cacheClearer,
                                       Runnable pluginCacheClearer,
                                       Consumer<Object> setAppState,
                                       PluginRefresher pluginRefresher,
                                       Consumer<String> errorLogger) {
        this.declaredSupplier = Objects.requireNonNull(declaredSupplier);
        this.materializedSupplier = Objects.requireNonNull(materializedSupplier);
        this.differ = Objects.requireNonNull(differ);
        this.reconciler = Objects.requireNonNull(reconciler);
        this.cacheClearer = cacheClearer;
        this.pluginCacheClearer = pluginCacheClearer;
        this.setAppState = Objects.requireNonNull(setAppState);
        this.pluginRefresher = pluginRefresher;
        this.errorLogger = errorLogger != null ? errorLogger : e -> log.error("{}", e);
    }

    /** CC DeclaredMarketplace（marketplaceManager.ts:144-152）= {source, sourceIsFallback?}。 */
    public record Marketplace(String name, MarketplaceSource source, boolean sourceIsFallback) {

        public Marketplace(String name) {
            this(name, null, false);
        }
    }

    /** CC 已物化 config 的 source（KnownMarketplace.source，schemas.ts:1592-1610）。 */
    public record MarketplaceConfig(MarketplaceSource source) {}

    /** CC MarketplaceDiff（reconciler.ts:30-41）：missing/sourceChanged/upToDate。 */
    public record MarketplaceDiff(List<String> missing,
                                    List<Marketplace> sourceChanged,
                                    List<String> upToDate) {}

    /** CC ReconcileResult（reconciler.ts:102-108）：installed/updated/failed/upToDate/skipped。 */
    public record ReconcileResult(List<String> installed,
                                    List<String> updated,
                                    List<String> upToDate,
                                    List<FailedInstall> failed,
                                    List<String> skipped) {
        public record FailedInstall(String name, String error) {}
    }

    /** CC DiffMarketplaces (注入). */
    @FunctionalInterface
    public interface MarketplacesDiffer {
        MarketplaceDiff diff(List<Marketplace> declared, Map<String, MarketplaceConfig> materialized);
    }

    /** CC ReconcileMarketplaces (注入). */
    @FunctionalInterface
    public interface MarketplacesReconciler {
        ReconcileResult reconcile(Consumer<ReconcileEvent> onProgress);
    }

    public record ReconcileEvent(String type, String name, String error) {}

    /** CC RefreshActivePlugins (注入). */
    @FunctionalInterface
    public interface PluginRefresher {
        void refresh(Consumer<Object> setAppState);
    }

    /**
     * 装配工厂：用具体实现接线（MPL8 数据面）· CC 无对应（Java 装配 seam）。
     *
     * <p>declaredSupplier ← {@link MarketplaceConfigStore#getDeclaredMarketplaces()}；
     * materializedSupplier ← {@link MarketplaceConfigStore#loadKnownMarketplacesConfig()}
     * （throw → 降级 {}，A4）；differ/reconciler ← {@link MarketplaceReconciler}；
     * cacheClearer ← clearMarketplacesCache；pluginRefresher ← {@link ActivePluginRefresher}；
     * errorLogger ← log.error。
     *
     * @param store        配置存储（declared + materialized + clear）
     * @param reconciler   diff/reconcile 数据面实现（需与 store 同实例）
     * @param refresher    刷新原语（可空 → 安装后不自动 refresh）
     * @param setAppState  AppState 更新器（status pending/installing/installed/failed 透出）
     * @param errorLogger  错误日志通道（可空 → 默认 log.error）
     */
    public static PluginInstallationManager wire(MarketplaceConfigStore store,
                                                 MarketplaceReconciler reconciler,
                                                 ActivePluginRefresher refresher,
                                                 Consumer<Object> setAppState,
                                                 Consumer<String> errorLogger) {
        return wire(store, reconciler, refresher, setAppState, errorLogger, null);
    }

    /** 6 参装配：pluginCacheClearer 对齐 CC :155/:170 的 clearPluginCache（可空 → 仅更新/refresh 失败时跳过）。 */
    public static PluginInstallationManager wire(MarketplaceConfigStore store,
                                                 MarketplaceReconciler reconciler,
                                                 ActivePluginRefresher refresher,
                                                 Consumer<Object> setAppState,
                                                 Consumer<String> errorLogger,
                                                 Runnable pluginCacheClearer) {
        Consumer<Object> effSetState = setAppState != null ? setAppState : ignored -> { };
        return new PluginInstallationManager(
            () -> store.getDeclaredMarketplaces().entrySet().stream()
                .map(e -> new Marketplace(e.getKey(), e.getValue().source(), e.getValue().sourceIsFallback()))
                .toList(),
            () -> store.loadKnownMarketplacesConfig().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                    e -> new MarketplaceConfig(e.getValue().source()), (a, b) -> a, java.util.LinkedHashMap::new)),
            reconciler,
            reconciler,
            store::clearMarketplacesCache,
            pluginCacheClearer,
            effSetState,
            refresher,
            errorLogger);
    }

    /** CC updateMarketplaceStatus. */
    @SuppressWarnings("unchecked")
    public void updateMarketplaceStatus(String name, String status, String error) {
        setAppState.accept((Object) (java.util.function.Function<Map<String, Object>, Map<String, Object>>)
            prev -> {
                Map<String, Object> plugins = (Map<String, Object>) prev.get("plugins");
                if (plugins == null) plugins = new java.util.LinkedHashMap<>();
                Map<String, Object> installationStatus =
                    (Map<String, Object>) plugins.get("installationStatus");
                if (installationStatus == null) installationStatus = new java.util.LinkedHashMap<>();
                List<Map<String, Object>> markets =
                    (List<Map<String, Object>>) installationStatus.get("marketplaces");
                if (markets == null) markets = new ArrayList<>();
                List<Map<String, Object>> updated = new ArrayList<>();
                boolean found = false;
                for (Map<String, Object> m : markets) {
                    if (name.equals(m.get("name"))) {
                        updated.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                            "name", name, "status", status, "error", error != null ? error : "")));
                        found = true;
                    } else {
                        updated.add(m);
                    }
                }
                if (!found) {
                    updated.add(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "name", name, "status", status, "error", error != null ? error : "")));
                }
                Map<String, Object> newInstallStatus = new java.util.LinkedHashMap<>(installationStatus);
                newInstallStatus.put("marketplaces", updated);
                Map<String, Object> newPlugins = new java.util.LinkedHashMap<>(plugins);
                newPlugins.put("installationStatus", newInstallStatus);
                Map<String, Object> newState = new java.util.LinkedHashMap<>(prev);
                newState.put("plugins", newPlugins);
                return newState;
            });
    }

    /** CC performBackgroundPluginInstallations. */
    public void performBackgroundPluginInstallations() {
        try {
            List<Marketplace> declared = declaredSupplier.get();
            Map<String, MarketplaceConfig> materialized;
            try {
                materialized = materializedSupplier.get();
            } catch (Exception e) {
                materialized = new java.util.LinkedHashMap<>();
            }
            MarketplaceDiff diff = differ.diff(declared, materialized);

            List<String> pendingNames = new ArrayList<>();
            pendingNames.addAll(diff.missing());
            for (Marketplace m : diff.sourceChanged()) pendingNames.add(m.name());

            // Init pending status
            setAppState.accept((Object) (java.util.function.Function<Map<String, Object>, Map<String, Object>>)
                prev -> {
                    List<Map<String, Object>> pendingList = new ArrayList<>();
                    for (String n : pendingNames) {
                        pendingList.add(java.util.Map.of("name", n, "status", "pending"));
                    }
                    Map<String, Object> newPlugins = new java.util.LinkedHashMap<>((Map<String, Object>) prev.getOrDefault("plugins", java.util.Map.of()));
                    Map<String, Object> newInstallStatus = new java.util.LinkedHashMap<>();
                    newInstallStatus.put("marketplaces", pendingList);
                    newInstallStatus.put("plugins", java.util.List.of());
                    newPlugins.put("installationStatus", newInstallStatus);
                    Map<String, Object> newState = new java.util.LinkedHashMap<>(prev);
                    newState.put("plugins", newPlugins);
                    return newState;
                });

            if (pendingNames.isEmpty()) return;

            ReconcileResult result = reconciler.reconcile(event -> {
                switch (event.type()) {
                    case "installing":
                        updateMarketplaceStatus(event.name(), "installing", null);
                        break;
                    case "installed":
                        updateMarketplaceStatus(event.name(), "installed", null);
                        break;
                    case "failed":
                        updateMarketplaceStatus(event.name(), "failed", event.error());
                        break;
                }
            });

            if (!result.installed().isEmpty()) {
                if (cacheClearer != null) cacheClearer.run();
                if (pluginRefresher != null) {
                    try { pluginRefresher.refresh(setAppState); }
                    catch (Exception refreshErr) {
                        errorLogger.accept("Auto-refresh failed: " + refreshErr.getMessage());
                        // CC :155-157 —— refresh 失败 fallback 前先清插件缓存，供 /reload-plugins 重载
                        if (pluginCacheClearer != null) {
                            pluginCacheClearer.run();
                        }
                        setNeedsRefresh();
                    }
                }
            } else if (!result.updated().isEmpty()) {
                // CC :169-172 —— 仅更新：清 marketplace + 插件缓存后置 needsRefresh（不自动刷新）
                if (cacheClearer != null) cacheClearer.run();
                if (pluginCacheClearer != null) {
                    pluginCacheClearer.run();
                }
                setNeedsRefresh();
            }
        } catch (Exception error) {
            errorLogger.accept("performBackgroundPluginInstallations failed: " + error.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void setNeedsRefresh() {
        setAppState.accept((Object) (java.util.function.Function<Map<String, Object>, Map<String, Object>>) prev -> {
            Map<String, Object> plugins = (Map<String, Object>) prev.get("plugins");
            if (plugins == null) plugins = new java.util.LinkedHashMap<>();
            if (Boolean.TRUE.equals(plugins.get("needsRefresh"))) return prev;
            Map<String, Object> newPlugins = new java.util.LinkedHashMap<>(plugins);
            newPlugins.put("needsRefresh", true);
            Map<String, Object> newState = new java.util.LinkedHashMap<>(prev);
            newState.put("plugins", newPlugins);
            return newState;
        });
    }
}
