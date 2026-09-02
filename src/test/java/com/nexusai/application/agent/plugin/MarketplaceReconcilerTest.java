package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.PluginInstallationManager.Marketplace;
import com.nexusai.application.agent.plugin.PluginInstallationManager.MarketplaceConfig;
import com.nexusai.application.agent.plugin.PluginInstallationManager.MarketplaceDiff;
import com.nexusai.application.agent.plugin.PluginInstallationManager.PluginRefresher;
import com.nexusai.application.agent.plugin.PluginInstallationManager.ReconcileEvent;
import com.nexusai.application.agent.plugin.PluginInstallationManager.ReconcileResult;
import com.nexusai.application.agent.plugin.PluginSchemas;
import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [MPL8] marketplace 后台安装层 reconcile 数据面 · 对齐 CC reconciler.ts:50-265 /
 * marketplaceManager.ts:1782-1923 / PluginInstallationManager.ts:60-184.
 *
 * <p>WHY（规则九 · 测试验证意图）：CC 的 reconcile 是 background install 的核心 ——
 * diff 把 settings 意图（declared）与 known_marketplaces.json（materialized）比较出
 * missing/sourceChanged/upToDate，reconcile 按 diff 安装/更新，addMarketplaceSource 源幂等
 * （同源不重复 clone）。旧 Java 无此数据面（PluginInstallationManager 只是注入骨架零调用方）。
 * 本测试锁定 6 组契约（任务 register MPL8 验收）：missing 安装 / sourceChanged 更新 /
 * 安装&gt;0 自动 refresh / declared 空提前 return / throw 降级 / known_marketplaces.json 落盘幂等。
 */
@DisplayName("[MPL8] marketplace 后台安装层（reconcile + startup 接线）对齐 CC")
class MarketplaceReconcilerTest {

    @TempDir
    Path tempDir;

    /** 内存 ConfigStorage fake · 记录 extraKnownMarketplaces（declared 意图层）。 */
    private static final class FakeConfigStorage implements ConfigStorage {
        final Map<String, Object> settings = new LinkedHashMap<>();

        @Override public Object readGlobal(String key) { return settings.get(key); }
        @Override public void writeGlobal(String key, Object value) { settings.put(key, value); }
        @Override public void unsetGlobal(String key) { settings.remove(key); }
        @Override public Object readSettings(List<String> path) {
            if (path.isEmpty()) { return settings.get(path.get(0)); }
            return settings.get(path.get(0));
        }
        @Override public void writeSettings(List<String> path, Object value) {
            settings.put(path.get(0), value);
        }
        @Override public void unsetSettings(List<String> path) { settings.remove(path.get(0)); }
        @Override public void addChangeListener(ConfigChangeListener listener) { }
        @Override public void removeChangeListener(ConfigChangeListener listener) { }
    }

    /** 记录 setAppState 更新的最小 state 载体（对齐 PluginInstallationManager Map idiom）。 */
    private static final class RecordingAppState {
        final Map<String, Object> root = new LinkedHashMap<>();
        final List<String> log = new ArrayList<>();
        final Map<String, List<String>> statusByName = new LinkedHashMap<>();

        Consumer<Object> updater() {
            return o -> {
                @SuppressWarnings("unchecked")
                Function<Map<String, Object>, Map<String, Object>> f =
                    (Function<Map<String, Object>, Map<String, Object>>) o;
                Map<String, Object> next = f.apply(root);
                root.clear();
                root.putAll(next);
                recordStatuses();
            };
        }

        @SuppressWarnings("unchecked")
        private void recordStatuses() {
            Map<String, Object> plugins = (Map<String, Object>) root.get("plugins");
            if (plugins == null) { return; }
            Map<String, Object> is = (Map<String, Object>) plugins.get("installationStatus");
            if (is == null) { return; }
            Object mkt = is.get("marketplaces");
            if (mkt instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        String name = String.valueOf(m.get("name"));
                        String status = String.valueOf(m.get("status"));
                        statusByName.computeIfAbsent(name, k -> new ArrayList<>()).add(status);
                        log.add(name + "=" + status);
                    }
                }
            }
        }

        Boolean needsRefresh() {
            @SuppressWarnings("unchecked")
            Map<String, Object> plugins = (Map<String, Object>) root.get("plugins");
            if (plugins == null) { return null; }
            Object v = plugins.get("needsRefresh");
            return v == null ? null : (Boolean) v;
        }
    }

    private MarketplaceManager manager() {
        return new MarketplaceManager();
    }

    @BeforeEach
    void setUp() {
        PluginDirectories.setPluginCacheDirOverride(tempDir.toString());
        PluginDirectories.setPluginSeedDirOverride(null);
        pluginCacheClearCount = 0;
    }

    @AfterEach
    void tearDown() {
        PluginDirectories.setPluginCacheDirOverride(null);
        PluginDirectories.setPluginSeedDirOverride(null);
        PluginDirectories.setUseCoworkPluginsOverride(null);
    }

    /** 建一个含 .claude-plugin/marketplace.json 的本地目录 marketplace（directory 源，无 git）。 */
    private Path makeDirectoryMarketplace(String name) throws IOException {
        return makeDirectoryMarketplaceAt("mkt-" + name, name);
    }

    /** 目录名与 marketplace.json 内 name 可分离（sourceChanged 测试：新位置同名 marketplace）。 */
    private Path makeDirectoryMarketplaceAt(String dirName, String marketplaceName) throws IOException {
        Path dir = tempDir.resolve(dirName);
        Files.createDirectories(dir.resolve(".claude-plugin"));
        Files.writeString(dir.resolve(".claude-plugin/marketplace.json"),
            "{\"name\":\"" + marketplaceName + "\",\"owner\":\"test\",\"plugins\":[]}", StandardCharsets.UTF_8);
        return dir;
    }

    private MarketplaceConfigStore store(FakeConfigStorage cfg) {
        return new MarketplaceConfigStore(manager(), cfg, () -> tempDir.toString());
    }

    private void declare(FakeConfigStorage cfg, String name, MarketplaceSource source) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) cfg.settings.computeIfAbsent(
            "extraKnownMarketplaces", k -> new LinkedHashMap<>());
        map.put(name, new KnownMarketplace(source, "unused-install-location", "2026-01-01T00:00:00Z", null));
    }

    // ════════════════════════════════════════════════════════════════════
    // diff —— missing / sourceChanged / upToDate + 源归一化
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("diff：missing 未物化、sourceChanged 源不同、upToDate 源相同（reconciler.ts:50-83）")
    void diff_classifiesMissingSourceChangedUpToDate() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        Path mktB = makeDirectoryMarketplace("b");

        MarketplaceReconciler reconciler = new MarketplaceReconciler(store(new FakeConfigStorage()));
        List<Marketplace> declared = List.of(
            new Marketplace("a", new MarketplaceSource.Directory(mktA.toString()), false),
            new Marketplace("b", new MarketplaceSource.Directory(mktB.toString()), false),
            new Marketplace("c", new MarketplaceSource.Directory(mktB.toString()), false));
        Map<String, MarketplaceConfig> materialized = Map.of(
            "b", new MarketplaceConfig(new MarketplaceSource.Directory(mktB.toString())));

        MarketplaceDiff diff = reconciler.diff(declared, materialized);

        assertThat(diff.missing()).containsExactly("a", "c");
        assertThat(diff.sourceChanged()).isEmpty();
        assertThat(diff.upToDate()).containsExactly("b");
    }

    @Test
    @DisplayName("diff：源不同 → sourceChanged；sourceIsFallback 已物化即 upToDate（reconciler.ts:65-79）")
    void diff_sourceChangedAndFallbackPresence() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        Path mktB = makeDirectoryMarketplace("b");

        MarketplaceReconciler reconciler = new MarketplaceReconciler(store(new FakeConfigStorage()));
        List<Marketplace> declared = List.of(
            new Marketplace("a", new MarketplaceSource.Directory(mktA.toString()), false),
            new Marketplace("fb", new MarketplaceSource.Directory(mktA.toString()), true));
        Map<String, MarketplaceConfig> materialized = Map.of(
            "a", new MarketplaceConfig(new MarketplaceSource.Directory(mktB.toString())),
            "fb", new MarketplaceConfig(new MarketplaceSource.Directory(mktB.toString())));

        MarketplaceDiff diff = reconciler.diff(declared, materialized);

        // WHY：fallback 语义（reconciler.ts:65-70）——兜底源不比 source，防重克隆踩掉已物化内容
        assertThat(diff.sourceChanged()).extracting(Marketplace::name).containsExactly("a");
        assertThat(diff.upToDate()).containsExactly("fb");
    }

    @Test
    @DisplayName("diff：相对路径目录源归一化到 canonical 根，防 sourceChanged 误判（reconciler.ts:249-265）")
    void diff_normalizesRelativeDirectorySource() throws IOException {
        // declared 用相对路径（projectRoot 下的 git 仓库），materialized 存归一化后绝对路径
        Path mktA = makeDirectoryMarketplace("a");
        MarketplaceReconciler reconciler =
            new MarketplaceReconciler(store(new FakeConfigStorage()), null, () -> mktA.toString(), null);

        List<Marketplace> declared = List.of(
            new Marketplace("a", new MarketplaceSource.Directory("relative/mkt"), false));
        Map<String, MarketplaceConfig> materialized = Map.of(
            "a", new MarketplaceConfig(new MarketplaceSource.Directory(
                mktA.resolve("relative/mkt").toString())));

        MarketplaceDiff diff = reconciler.diff(declared, materialized);

        // WHY：归一化后相对路径 → canonical/绝对，两 source 相等 → upToDate；不归一化会误报 sourceChanged → 反复重克隆
        assertThat(diff.missing()).isEmpty();
        assertThat(diff.sourceChanged()).isEmpty();
        assertThat(diff.upToDate()).containsExactly("a");
    }

    // ════════════════════════════════════════════════════════════════════
    // addMarketplaceSource —— 源幂等（alreadyMaterialized 不重复 clone）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("addMarketplaceSource：首次安装落盘，同源二次 alreadyMaterialized 不重复 clone（:1834-1842）")
    void addMarketplaceSource_sourceIdempotent() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        MarketplaceManager mm = manager();
        MarketplaceConfigStore store = store(new FakeConfigStorage());
        MarketplaceReconciler reconciler = new MarketplaceReconciler(store);

        MarketplaceSource.Directory src = new MarketplaceSource.Directory(mktA.toString());
        MarketplaceReconciler.AddMarketplaceResult first = reconciler.addMarketplaceSource(src);
        MarketplaceReconciler.AddMarketplaceResult second = reconciler.addMarketplaceSource(src);

        assertThat(first.name()).isEqualTo("a");
        assertThat(first.alreadyMaterialized()).isFalse();
        assertThat(second.alreadyMaterialized()).isTrue();
        // known_marketplaces.json 落盘正确（验收 #6）
        Map<String, KnownMarketplace> config = mm.loadKnownMarketplacesConfig();
        assertThat(config).containsKey("a");
        assertThat(config.get("a").source()).isEqualTo(src);
        assertThat(config.get("a").installLocation()).isEqualTo(mktA.toString());
    }

    // ════════════════════════════════════════════════════════════════════
    // reconcile —— 验收 1/2/4/6
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reconcile：missing 安装 + 状态 pending→installing→installed（验收 1）")
    void reconcile_installsMissingMarketplaces() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        Path mktB = makeDirectoryMarketplace("b");
        FakeConfigStorage cfg = new FakeConfigStorage();
        declare(cfg, "a", new MarketplaceSource.Directory(mktA.toString()));
        declare(cfg, "b", new MarketplaceSource.Directory(mktB.toString()));

        MarketplaceReconciler reconciler = new MarketplaceReconciler(store(cfg));
        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.installed()).containsExactlyInAnyOrder("a", "b");
        assertThat(result.failed()).isEmpty();
        // 落盘正确（验收 #6）
        Map<String, KnownMarketplace> config = manager().loadKnownMarketplacesConfig();
        assertThat(config).containsKeys("a", "b");
    }

    @Test
    @DisplayName("reconcile：sourceChanged 更新覆盖 JSON；重复 reconcile 幂等不删条目（验收 2/6）")
    void reconcile_updatesSourceChangedAndIsIdempotent() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        Path mktB = makeDirectoryMarketplaceAt("mkt-a-new", "a");
        FakeConfigStorage cfg = new FakeConfigStorage();

        // declared 意图 = mktB（同名 marketplace 新位置）；materialized 已存在旧源 mktA → sourceChanged
        declare(cfg, "a", new MarketplaceSource.Directory(mktB.toString()));
        MarketplaceManager mm = manager();
        mm.saveKnownMarketplacesConfig(Map.of(
            "a", new KnownMarketplace(new MarketplaceSource.Directory(mktA.toString()), mktA.toString(),
                "2026-01-01T00:00:00Z", null)));

        MarketplaceReconciler reconciler = new MarketplaceReconciler(store(cfg));
        ReconcileResult first = reconciler.reconcile(null);
        ReconcileResult second = reconciler.reconcile(null);

        assertThat(first.updated()).containsExactly("a");
        // WHY：sourceChanged 是 update（reconciler.ts:147-153）——新源覆盖旧 JSON 条目
        Map<String, KnownMarketplace> config = mm.loadKnownMarketplacesConfig();
        assertThat(config.get("a").source()).isEqualTo(new MarketplaceSource.Directory(mktB.toString()));
        assertThat(config.get("a").installLocation()).isEqualTo(mktB.toString());
        // 幂等：二次 reconcile 全 upToDate，不删条目不重克隆
        assertThat(second.updated()).isEmpty();
        assertThat(second.installed()).isEmpty();
        assertThat(manager().loadKnownMarketplacesConfig()).containsOnlyKeys("a");
    }

    @Test
    @DisplayName("reconcile：declared 为空 → 提前返回空结果，无 side effect（验收 4 / reconciler.ts:118-120）")
    void reconcile_emptyDeclared_returnsEarlyNoSideEffect() {
        MarketplaceManager mm = manager();
        MarketplaceReconciler reconciler = new MarketplaceReconciler(store(new FakeConfigStorage()));

        ReconcileResult result = reconciler.reconcile(null);

        assertThat(result.installed()).isEmpty();
        assertThat(result.updated()).isEmpty();
        assertThat(mm.loadKnownMarketplacesConfig()).isEmpty();
    }

    @Test
    @DisplayName("reconcile：materialized 损坏 throw → 降级 {} 不 re-throw（验收 5 / reconciler.ts:122-128）")
    void reconcile_corruptedMaterialized_degradesToEmpty() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        FakeConfigStorage cfg = new FakeConfigStorage();
        declare(cfg, "a", new MarketplaceSource.Directory(mktA.toString()));
        // 损坏 known_marketplaces.json（load→throw ConfigParseError）
        Files.writeString(Paths.get(tempDir.toString(), "known_marketplaces.json"),
            "not-json", StandardCharsets.UTF_8);

        MarketplaceReconciler reconciler = new MarketplaceReconciler(store(cfg));
        // WHY：reconciler.ts:122-128 —— load 抛错降级 {}，绝不让 corrupted 配置拖垮整个 reconcile；
        //       addMarketplaceSource 内部再读配置仍抛 → 记 failed 事件，不 re-throw（验收 5）
        ReconcileResult result = reconciler.reconcile(null);
        assertThatCode(() -> reconciler.reconcile(null)).doesNotThrowAnyException();
        assertThat(result.failed()).extracting(f -> f.name()).containsExactly("a");
        assertThat(result.installed()).isEmpty();
    }

    @Test
    @DisplayName("reconcile：update + 本地源路径不存在 → skipped 不触发 failed（reconciler.ts:169-179 死路径跳过）")
    void reconcile_updateLocalSourceMissingPath_isSkippedNotFailed() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        Path deadDir = tempDir.resolve("dead-dir"); // 故意不创建 → 本地路径不存在
        FakeConfigStorage cfg = new FakeConfigStorage();
        declare(cfg, "a", new MarketplaceSource.Directory(deadDir.toString()));
        MarketplaceManager mm = manager();
        mm.saveKnownMarketplacesConfig(Map.of(
            "a", new KnownMarketplace(new MarketplaceSource.Directory(mktA.toString()), mktA.toString(),
                "2026-01-01T00:00:00Z", null)));

        MarketplaceReconciler reconciler = new MarketplaceReconciler(store(cfg));
        ReconcileResult result = reconciler.reconcile(null);

        // WHY：reconciler.ts:169-179 —— sourceChanged 且 local 源路径不存在（如多 checkout 下 normalizeSource
        //       归一化失败的死路径），addMarketplaceSource 必失败 → 跳过保留已物化条目，避免噪音 failed 事件
        assertThat(result.skipped()).containsExactly("a");
        assertThat(result.updated()).isEmpty();
        assertThat(result.failed()).isEmpty();
        Map<String, KnownMarketplace> config = mm.loadKnownMarketplacesConfig();
        assertThat(config.get("a").source()).isEqualTo(new MarketplaceSource.Directory(mktA.toString()));
    }

    @Test
    @DisplayName("reconcile：同名条目 seed 托管 → addMarketplaceSource 拒绝覆盖转 failed（marketplaceManager.ts:1864-1872）")
    void reconcile_seedManagedEntry_rejectsOverwrite() throws IOException {
        Path seedDir = tempDir.resolve("seed");
        Files.createDirectories(seedDir.resolve("marketplaces"));
        Path mktB = makeDirectoryMarketplace("b"); // 新源（存在）→ 能走到 seed 守卫（非死路径跳过）
        FakeConfigStorage cfg = new FakeConfigStorage();
        declare(cfg, "b", new MarketplaceSource.Directory(mktB.toString()));
        MarketplaceManager mm = manager();
        // seed 托管的已物化条目：installLocation 落在 seedDir 下
        mm.saveKnownMarketplacesConfig(Map.of(
            "b", new KnownMarketplace(new MarketplaceSource.Directory(tempDir.resolve("other").toString()),
                seedDir.resolve("marketplaces").resolve("b").toString(),
                "2026-01-01T00:00:00Z", null)));
        PluginDirectories.setPluginSeedDirOverride(seedDir.toString());

        MarketplaceReconciler reconciler = new MarketplaceReconciler(store(cfg));
        ReconcileResult result = reconciler.reconcile(null);

        // WHY：marketplaceManager.ts:1864-1872 —— seed 托管条目 admin 控制，settings 意图不能覆盖；
        //       addMarketplaceSource 抛 seed-managed 错误 → reconcile 记 failed，config 条目不被覆盖
        assertThat(result.failed()).extracting(f -> f.name()).containsExactly("b");
        assertThat(result.updated()).isEmpty();
        Map<String, KnownMarketplace> config = mm.loadKnownMarketplacesConfig();
        assertThat(config.get("b").source())
            .isEqualTo(new MarketplaceSource.Directory(tempDir.resolve("other").toString()));
    }

    // ════════════════════════════════════════════════════════════════════
    // performBackgroundPluginInstallations —— 验收 3/4/5（安装>0 自动 refresh）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("performBackgroundPluginInstallations：安装>0 → 清 marketplace 缓存 + 自动 refresh（验收 3 / PluginInstallationManager.ts:135-165）")
    void backgroundInstall_installedTriggersAutoRefresh() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        FakeConfigStorage cfg = new FakeConfigStorage();
        declare(cfg, "a", new MarketplaceSource.Directory(mktA.toString()));
        RecordingAppState state = new RecordingAppState();

        PluginInstallationManager manager = PluginInstallationManager.wire(
            store(cfg), new MarketplaceReconciler(store(cfg)), refresherSpy(),
            state.updater(), msg -> { });

        manager.performBackgroundPluginInstallations();

        assertThat(state.statusByName.get("a")).contains("pending", "installing", "installed");
        assertThat(refreshCount).isEqualTo(1);
    }

    @Test
    @DisplayName("performBackgroundPluginInstallations：仅更新 → 置 needsRefresh 不刷新（验收 3 / :166-180）")
    void backgroundInstall_updatedOnly_setsNeedsRefreshNoRefresh() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        Path mktB = makeDirectoryMarketplace("b");
        FakeConfigStorage cfg = new FakeConfigStorage();
        declare(cfg, "a", new MarketplaceSource.Directory(mktB.toString()));
        MarketplaceManager mm = manager();
        mm.saveKnownMarketplacesConfig(Map.of(
            "a", new KnownMarketplace(new MarketplaceSource.Directory(mktA.toString()), mktA.toString(),
                "2026-01-01T00:00:00Z", null)));
        RecordingAppState state = new RecordingAppState();

        PluginInstallationManager manager = PluginInstallationManager.wire(
            store(cfg), new MarketplaceReconciler(store(cfg)), refresherSpy(),
            state.updater(), msg -> { }, pluginCacheClearerSpy());

        manager.performBackgroundPluginInstallations();

        assertThat(state.needsRefresh()).isTrue();
        assertThat(refreshCount).isZero();
        // WHY：PluginInstallationManager.ts:169-172 —— 仅更新也清插件缓存，供 /reload-plugins 应用新源
        assertThat(pluginCacheClearCount).isEqualTo(1);
    }

    @Test
    @DisplayName("performBackgroundPluginInstallations：无 pending → 提前 return 无 side effect（验收 4 / :93-95）")
    void backgroundInstall_noPending_returnsEarly() {
        FakeConfigStorage cfg = new FakeConfigStorage();
        RecordingAppState state = new RecordingAppState();

        PluginInstallationManager manager = PluginInstallationManager.wire(
            store(cfg), new MarketplaceReconciler(store(cfg)), refresherSpy(),
            state.updater(), msg -> { });

        manager.performBackgroundPluginInstallations();

        assertThat(state.statusByName).isEmpty();
        assertThat(refreshCount).isZero();
    }

    @Test
    @DisplayName("performBackgroundPluginInstallations：refresh 失败 → fallback needsRefresh 不 re-throw（验收 5 / :145-165）")
    void backgroundInstall_refreshFails_fallbackNeedsRefresh() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        FakeConfigStorage cfg = new FakeConfigStorage();
        declare(cfg, "a", new MarketplaceSource.Directory(mktA.toString()));
        RecordingAppState state = new RecordingAppState();

        PluginRefresher failing = setAppState -> { throw new RuntimeException("refresh boom"); };
        PluginInstallationManager manager = PluginInstallationManager.wire(
            store(cfg), new MarketplaceReconciler(store(cfg)), null, state.updater(), msg -> { });
        // 用 failing refresher 重装配（wire 用 null refresher 会跳过刷新；这里直接构造验证 fallback）
        PluginInstallationManager mgr2 = new PluginInstallationManager(
            () -> store(cfg).getDeclaredMarketplaces().entrySet().stream()
                .map(e -> new Marketplace(e.getKey(), e.getValue().source(), e.getValue().sourceIsFallback())).toList(),
            () -> store(cfg).loadKnownMarketplacesConfig().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                    e -> new MarketplaceConfig(e.getValue().source()), (a, b) -> a, LinkedHashMap::new)),
            new MarketplaceReconciler(store(cfg)), new MarketplaceReconciler(store(cfg)),
            store(cfg)::clearMarketplacesCache, pluginCacheClearerSpy(), state.updater(), failing, msg -> { });

        assertThatCode(() -> mgr2.performBackgroundPluginInstallations()).doesNotThrowAnyException();
        assertThat(state.needsRefresh()).isTrue();
        // WHY：PluginInstallationManager.ts:155-157 —— refresh 失败 fallback needsRefresh 前也清插件缓存
        assertThat(pluginCacheClearCount).isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // PerformStartupChecks —— startup 接线（验收 1：seed 注册 + 后台安装）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PerformStartupChecks.wire：seed 未配置 + missing 市场 → 后台安装落盘（验收 1 / performStartupChecks.tsx:24-69）")
    void performStartupChecks_backgroundInstallsMissing() throws IOException {
        Path mktA = makeDirectoryMarketplace("a");
        FakeConfigStorage cfg = new FakeConfigStorage();
        declare(cfg, "a", new MarketplaceSource.Directory(mktA.toString()));
        RecordingAppState state = new RecordingAppState();

        PerformStartupChecks startup = PerformStartupChecks.wire(
            manager(), cfg, null, () -> tempDir.toString());

        startup.performStartupChecks(state.updater());

        assertThat(state.statusByName.get("a")).contains("pending", "installing", "installed");
        assertThat(manager().loadKnownMarketplacesConfig()).containsKey("a");
    }

    // ── 记录 refresh / pluginCacheClearer 调用 ──

    private int refreshCount = 0;
    private int pluginCacheClearCount = 0;

    private ActivePluginRefresher refresherSpy() {
        return new ActivePluginRefresher(manager(), null) {
            @Override
            public void refresh(Consumer<Object> setAppState) {
                refreshCount++;
            }
        };
    }

    /** pluginCacheClearer spy · 对齐 PluginInstallationManager.ts:155/:170 的 clearPluginCache。 */
    private Runnable pluginCacheClearerSpy() {
        return () -> pluginCacheClearCount++;
    }

    // ── F1：validateOfficialNameSource 官方名源守卫（schemas.ts:119-157 / marketplaceManager.ts:1851-1860）──

    @Test
    @DisplayName("validateOfficialNameSource：保留名 + 非 anthropics github 源 → 拒绝消息")
    void validateOfficialNameSource_reservedName_nonOfficialGithub_rejects() {
        // WHY：schemas.ts:125-133 —— 保留名只能来自 anthropics/ 组织；非官方 repo 前缀必须拒绝，
        //      否则 declared 可用保留名 + 恶意源静默安装，CC 会 throw（F1 修复）。
        String msg = PluginSchemas.validateOfficialNameSource(
            "claude-plugins-official",
            new PluginSchemas.MarketplaceSource.Github("evil/repo", null, null, null));
        assertThat(msg).contains("reserved for official Anthropic marketplaces");
        assertThat(msg).contains("github.com/anthropics/");
    }

    @Test
    @DisplayName("validateOfficialNameSource：保留名 + 非 anthropics git URL → 拒绝")
    void validateOfficialNameSource_reservedName_nonOfficialGitUrl_rejects() {
        String msg = PluginSchemas.validateOfficialNameSource(
            "claude-plugins-official",
            new PluginSchemas.MarketplaceSource.Git("https://github.com/evil/repo.git", null, null, null));
        assertThat(msg).contains("reserved for official Anthropic marketplaces");
    }

    @Test
    @DisplayName("validateOfficialNameSource：保留名 + anthropics github/git 官方源 → 通过")
    void validateOfficialNameSource_reservedName_officialSources_pass() {
        // github source：repo 前缀 anthropics/
        assertThat(PluginSchemas.validateOfficialNameSource(
            "claude-plugins-official",
            new PluginSchemas.MarketplaceSource.Github("anthropics/claude-plugins-official", null, null, null)))
            .isNull();
        // git source：HTTPS github.com/anthropics/
        assertThat(PluginSchemas.validateOfficialNameSource(
            "claude-plugins-official",
            new PluginSchemas.MarketplaceSource.Git("https://github.com/anthropics/claude-plugins-official.git", null, null, null)))
            .isNull();
        // git source：SSH git@github.com:anthropics/
        assertThat(PluginSchemas.validateOfficialNameSource(
            "claude-plugins-official",
            new PluginSchemas.MarketplaceSource.Git("git@github.com:anthropics/claude-plugins-official.git", null, null, null)))
            .isNull();
        // 非保留名不校验（schemas.ts:123-124）
        assertThat(PluginSchemas.validateOfficialNameSource(
            "my-custom-market",
            new PluginSchemas.MarketplaceSource.Url("https://example.com/marketplace.json", null)))
            .isNull();
    }

    @Test
    @DisplayName("addMarketplaceSource：保留名 + 非官方源 → 抛 IOException（对齐 marketplaceManager.ts:1851-1860）")
    void addMarketplaceSource_reservedName_nonOfficialSource_throws() throws IOException {
        Path mktOfficial = makeDirectoryMarketplace("claude-plugins-official"); // 目录源（非 github/git）
        FakeConfigStorage cfg = new FakeConfigStorage();
        declare(cfg, "claude-plugins-official", new MarketplaceSource.Directory(mktOfficial.toString()));
        MarketplaceReconciler reconciler = new MarketplaceReconciler(store(cfg));

        ReconcileResult result = reconciler.reconcile(null);

        // WHY：marketplaceManager.ts:1851-1860 —— 保留官方名从非 GitHub 源 declared 会被静默安装，
        //       CC 在 addMarketplaceSource 前 validateOfficialNameSource 抛错 → reconcile 记 failed。
        assertThat(result.failed()).extracting(f -> f.name()).containsExactly("claude-plugins-official");
        assertThat(manager().loadKnownMarketplacesConfig()).doesNotContainKey("claude-plugins-official");
    }
}
