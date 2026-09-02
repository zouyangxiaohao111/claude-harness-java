package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import com.nexusai.common.RequestContext;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MPL8 后台安装层的生产装配点 · 对齐 CC {@code screens/REPL.tsx:799}
 * {@code void performStartupChecks(setAppState)} 的启动调用点语义。
 *
 * <p><b>WHY（D6 反射闭环）</b>：MPL8 数据面（PerformStartupChecks/MarketplaceReconciler/
 * ActivePluginRefresher/MarketplaceConfigStore）此前零生产引用，startup reconcile 仅测试手动调用。
 * 本类补齐生产装配：{@link #runStartupChecks()}（@PostConstruct）在 Spring 容器装配完成时
 * 真实触发 {@link PerformStartupChecks#performStartupChecks(Consumer)} —— 使生产 startup 下
 * missing 市场被 reconcile 安装（CC performStartupChecks.tsx:24-69 顺序：registerSeedMarketplaces
 * → performBackgroundPluginInstallations），异常降级不阻断启动（:65-68）。
 *
 * <p><b>装配时序（参照 C3R PluginLoader @PostConstruct 标准，PluginLoader.java:306-326）</b>：
 * 构造注入三依赖（MarketplaceManager / PluginLoader / ConfigStorage），容器解析依赖时三者已
 * 完成自身 @Autowired 注入与 @PostConstruct，本类 @PostConstruct 晚于它们执行 → 看到完全就绪的
 * manager/loader/storage。文件配置（settings.json）由 FileConfigStorage @PostConstruct warmUp
 * 预载（FileConfigStorage.java:107），declared 意图层可读。
 *
 * <p><b>setAppState 语义（MPL8-D6c 闭环）</b>：CC 的 setAppState 是 REPL 全局 AppState
 *（status + needsRefresh 透出给 useManagePlugins UI，对齐 AppStateStore.ts:196-206
 * installationStatus.marketplaces）。Java 端无全局 AppState bean（session AppState 属
 * LlmAgentLoop，prototype 逐会话），本类内置 {@link PluginAppStateStore} 作为 Java 等价全局
 * 插件状态存储（Consumer&lt;Object&gt; = setAppState），生产启动装配传真实消费者 → reconcile 状态
 * pending/installing/installed/failed 可观察；{@link #performStartupChecks(Consumer)} 仍容忍
 * null（headless 降级，仅落盘 + 清缓存，status 不透出）。
 *
 * <p><b>trust 门禁</b>：CC performStartupChecks.tsx:26-28 仅 trust 后调用；Java trust 门禁不在本
 * 模块（会话提示词未列），本装配不判定 trust，由部署方在已授权环境启用。
 */
@Component
public class PluginStartupAssembler {

    private static final Logger log = LoggerFactory.getLogger(PluginStartupAssembler.class);

    private final MarketplaceManager marketplaceManager;
    private final PluginLoader pluginLoader;
    private final ConfigStorage configStorage;

    /** [MPL8-D6c] Java 等价全局插件状态存储 = setAppState 真实消费者（构造期就绪，@PostConstruct 前可用）。 */
    private final PluginAppStateStore appStateStore = new PluginAppStateStore();

    /** [MPL9] L9 后台任务依赖 · InstalledPluginsManager（autoupdate/blocklist 已装数据面）。 */
    private InstalledPluginsManager installedPluginsManager;

    /** @Autowired(required=false)：单测/无 bean 场景下缺依赖不阻断容器启动（fail loud 不抛）。 */
    @Autowired(required = false)
    public PluginStartupAssembler(MarketplaceManager marketplaceManager,
                                  PluginLoader pluginLoader,
                                  ConfigStorage configStorage) {
        this.marketplaceManager = marketplaceManager;
        this.pluginLoader = pluginLoader;
        this.configStorage = configStorage;
    }

    @Autowired(required = false)
    public void setInstalledPluginsManager(InstalledPluginsManager installedPluginsManager) {
        this.installedPluginsManager = installedPluginsManager;
    }

    /** 启动 reconcile 后台线程池（daemon · 不阻塞 JVM 退出）· 复用 PluginAutoupdate 单线程池先例。 */
    private final ExecutorService startupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-startup-reconcile");
        t.setDaemon(true);
        return t;
    });

    /** 最近一次异步 startup 任务（@PostConstruct 提交 · 测试 await 钩子）。 */
    private volatile Future<?> startupTask;

    /**
     * 生产 startup 装配入口 · @PostConstruct · 对齐 CC REPL.tsx:799 调用点
     * （应用启动触发，非逐会话）+ CC main.tsx 后台任务（official marketplace 自动安装 /
     * autoUpdate 后台刷新 / blocklist delisting 检测）。
     *
     * <p>[MPL8-D6c] setAppState 传真实消费者 {@link #appStateStore}（非 null headless）→ reconcile
     * 状态 pending/installing/installed/failed 可观察（对齐 CC performStartupChecks.tsx:24/64
     * setAppState 真实参数语义）。
     *
     * <p>[MPL8-D6d] reconcile 转异步：启动不再同步阻塞 —— CC REPL.tsx:799
     * {@code void performStartupChecks(setAppState)} 是 fire-and-forget（useEffect 内不 await；
     * performStartupChecks.tsx:24 为 {@code async}: Promise&lt;void&gt; + :65-68 catch 明确"不阻断启动"）。
     * Java 侧 @PostConstruct 立即返回，reconcile 提交 daemon executor 后台执行（github 源 clone
     * 不阻塞启动窗口）；异常在异步任务内 catch + log.error（验收 #3 不吞不阻断）。
     */
    @PostConstruct
    public void runStartupChecks() {
        startupTask = startupExecutor.submit(this::runStartupChecksAsync);
    }

    /** 异步任务体：完整 startup 检查链 + L9 后台任务；异常 catch + log.error（不吞，CC :65-68 不阻断启动）。 */
    private void runStartupChecksAsync() {
        try {
            performStartupChecks(appStateStore);
            runL9BackgroundHousekeeping();
        } catch (Exception error) {
            log.error("[PluginStartupAssembler] 启动插件检查异步任务失败（不阻塞启动）：{}", error.getMessage(), error);
        }
    }

    /**
     * [MPL8-D6d] 测试钩子：阻塞等待异步 startup reconcile 最终完成（最终一致断言）。
     * 生产无调用（CC fire-and-forget 不 await）；供装配级测试确认"最终落盘 / 状态透出"。
     */
    public void awaitStartupChecks(Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        Future<?> task = startupTask;
        if (task != null) {
            task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** [MPL8-D6c] 暴露全局插件状态存储（setAppState 消费者），供状态面观察（测试 / 未来 UI/REST）。 */
    public PluginAppStateStore pluginAppStateStore() {
        return appStateStore;
    }

    /**
     * L9 后台任务（backgroundHousekeeping 等价）· 对齐 CC main.tsx 启动后台作业：
     * official marketplace checkAndInstall + autoUpdateMarketplacesAndPluginsInBackground +
     * blocklist delisting 检测。全部 fire-and-forget / 失败仅日志不阻断 startup。
     */
    private void runL9BackgroundHousekeeping() {
        if (marketplaceManager == null || configStorage == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PluginStartupAssembler] L9 后台任务依赖缺失，跳过（official/autoupdate/blocklist）");
            }
            return;
        }
        try {
            MarketplaceConfigStore store = new MarketplaceConfigStore(marketplaceManager, configStorage,
                () -> CwdResolution.getOriginalCwdLayer(RequestContext.sessionId()));
            MarketplaceReconciler reconciler = new MarketplaceReconciler(store);
            // 1) 官方 marketplace 首次启动自动安装（幂等跳过已装）· CC officialMarketplaceStartupCheck.ts:147
            OfficialMarketplace.wire(marketplaceManager, reconciler).checkAndInstallOfficialMarketplace();
            // 2) 后台自动更新 autoUpdate 市场 + 已装插件 · CC pluginAutoupdate.ts:227
            PluginAutoupdate.wire(marketplaceManager, installedPluginsManager, store)
                .autoUpdateMarketplacesAndPluginsInBackground();
            // 3) delisted 插件自动卸载 + 写 flagged · CC pluginBlocklist.ts:64
            PluginBlocklist.wire(marketplaceManager, new PluginFlagging(), installedPluginsManager)
                .detectAndUninstallDelistedPlugins();
        } catch (Exception error) {
            log.warn("L9 后台任务失败（不阻断 startup）：{}", error.getMessage());
        }
    }

    /**
     * 触发完整 startup 检查链 · {@link PerformStartupChecks#performStartupChecks(Consumer)}。
     *
     * <p>依赖缺失（marketplaceManager/configStorage 未装配）→ debug 日志跳过（headless 部署/单测），
     * 不抛异常不阻断 startup。装配齐 → 真实 reconcile：registerSeedMarketplaces（幂等）→
     * performBackgroundPluginInstallations（missing 安装 / sourceChanged 更新 / 安装&gt;0 自动 refresh）。
     *
     * @param setAppState AppState 更新器（null = headless 降级，仅落盘、status 不透出）；
     *                    生产装配传 {@link #appStateStore}（MPL8-D6c 真实消费者）
     */
    public void performStartupChecks(Consumer<Object> setAppState) {
        if (marketplaceManager == null || configStorage == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PluginStartupAssembler] 装配依赖缺失（marketplaceManager/configStorage），跳过 startup reconcile");
            }
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("[PluginStartupAssembler] 启动插件检查：触发 marketplace reconcile（对齐 CC performStartupChecks.tsx:24-69 / REPL.tsx:799）");
        }
        PerformStartupChecks.wire(marketplaceManager, configStorage, pluginLoader,
                () -> CwdResolution.getOriginalCwdLayer(RequestContext.sessionId()))
            .performStartupChecks(setAppState);
    }

    /**
     * [MPL8-D6c] Java 等价全局插件状态存储 · 对齐 CC AppStateStore.ts:196-206
     * {@code installationStatus.marketplaces: Array<{name, status: 'pending'|'installing'|
     * 'installed'|'failed', error?}>}。
     *
     * <p><b>WHY</b>：CC 的 setAppState 更新 REPL 全局 AppState（performStartupChecks.tsx:24/64），
     * 后台安装经 updateMarketplaceStatus（PluginInstallationManager.ts:30-46）把 reconcile 状态写入
     * installationStatus。Java 端无全局 AppState bean（session AppState 属 LlmAgentLoop prototype
     * 逐会话），本存储即 setAppState 消费者：接受 PluginInstallationManager 产出的
     * {@code Function<Map,Map>} 更新（TS React setState 的 Java 等价），保存状态并暴露可观察视图。
     * 生产装配（{@link PluginStartupAssembler#runStartupChecks()}）传本存储实例 → reconcile 状态
     * 面可观察（验收 #2）。
     *
     * <p><b>headless 降级保留</b>：本存储仅在生产装配路径注入；{@code performStartupChecks(null)}
     * 仍可用（PluginInstallationManager.wire null→no-op，仅落盘，验收 #3）。
     */
    public static final class PluginAppStateStore implements Consumer<Object> {

        private final Map<String, Object> root = new LinkedHashMap<>();
        private final Map<String, String> marketplaceStatuses = new LinkedHashMap<>();
        private final List<String> statusLog = new ArrayList<>();

        /** setAppState 消费者入口：应用 Function 更新到当前状态（对齐 TS setState(f)）。 */
        @Override
        public synchronized void accept(Object update) {
            @SuppressWarnings("unchecked")
            Function<Map<String, Object>, Map<String, Object>> f =
                (Function<Map<String, Object>, Map<String, Object>>) update;
            Map<String, Object> next = f.apply(new LinkedHashMap<>(root));
            root.clear();
            root.putAll(next);
            recordStatuses(next);
        }

        /** 提取 installationStatus.marketplaces 的 name→status（对齐 AppStateStore.ts:196-206）。 */
        private void recordStatuses(Map<String, Object> state) {
            Object pluginsRaw = state.get("plugins");
            if (!(pluginsRaw instanceof Map<?, ?> plugins)) {
                return;
            }
            Object isRaw = plugins.get("installationStatus");
            if (!(isRaw instanceof Map<?, ?> is)) {
                return;
            }
            Object mktRaw = is.get("marketplaces");
            if (mktRaw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        String name = String.valueOf(m.get("name"));
                        String status = String.valueOf(m.get("status"));
                        marketplaceStatuses.put(name, status);
                        statusLog.add(name + "=" + status);
                    }
                }
            }
        }

        /** 当前各市场 reconcile 终态（name→pending/installing/installed/failed），不可变快照。 */
        public synchronized Map<String, String> marketplaceStatuses() {
            return new LinkedHashMap<>(marketplaceStatuses);
        }

        /** 各市场 reconcile 状态变更日志（chronological，name=status），不可变快照。 */
        public synchronized List<String> statusLog() {
            return new ArrayList<>(statusLog);
        }

        /** 当前 AppState 快照（含 plugins.needsRefresh），不可变拷贝。 */
        public synchronized Map<String, Object> snapshot() {
            return new LinkedHashMap<>(root);
        }

        /** plugins.needsRefresh 标记（未置位 → null）。 */
        public synchronized Boolean needsRefresh() {
            Object pluginsRaw = root.get("plugins");
            if (!(pluginsRaw instanceof Map<?, ?> plugins)) {
                return null;
            }
            Object v = plugins.get("needsRefresh");
            return v == null ? null : (Boolean) v;
        }
    }
}
