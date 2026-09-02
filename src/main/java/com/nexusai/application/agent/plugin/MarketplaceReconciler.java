package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.plugin.PluginInstallationManager.Marketplace;
import com.nexusai.application.agent.plugin.PluginInstallationManager.MarketplaceConfig;
import com.nexusai.application.agent.plugin.PluginInstallationManager.MarketplaceDiff;
import com.nexusai.application.agent.plugin.PluginInstallationManager.ReconcileEvent;
import com.nexusai.application.agent.plugin.PluginInstallationManager.ReconcileResult;
import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Marketplace reconcile 数据面实现 · 对齐 CC {@code utils/plugins/reconciler.ts}
 * {@code diffMarketplaces}（:50-83）/ {@code reconcileMarketplaces}（:114-234）/
 * {@code normalizeSource}（:249-265）与 {@code marketplaceManager.ts}
 * {@code addMarketplaceSource}（:1782-1923）。
 *
 * <p>同时实现 {@link PluginInstallationManager.MarketplacesDiffer}（diff 阶段，
 * 供 performBackgroundPluginInstallations 计算 pending 状态）与
 * {@link PluginInstallationManager.MarketplacesReconciler}（reconcile 阶段，内部重取
 * declared + materialized 后独立 diff —— 对齐 CC reconcileMarketplaces 自身
 * getDeclaredMarketplaces + loadKnownMarketplacesConfig + diffMarketplaces 语义）。
 *
 * <p>关键不变量：
 * <ul>
 *   <li><b>diff 源归一化</b>：directory/file 相对路径 → 相对 canonical git root 解析
 *       （reconciler.ts:249-265，防 worktree 下反复 sourceChanged 误判重克隆）；</li>
 *   <li><b>skip 过滤 + 死路径跳过</b>：update 且 local 源路径不存在 → skip 保留已物化条目
 *       （reconciler.ts:158-181）；</li>
 *   <li><b>addMarketplaceSource 源幂等</b>：已存在等值 source → alreadyMaterialized，不重复 clone
 *       （marketplaceManager.ts:1834-1842）；</li>
 *   <li><b>幂等 additive</b>：只增不删（CC reconciler.ts:7 注释），reconcile 可重复执行。</li>
 * </ul>
 *
 * <p>url/npm/settings 源在 Java 侧尚无 L4/L9 实现 → 抛带指引错误，由 reconcile catch 转 failed
 * 事件（非阻断 startup，对齐 refreshMarketplace :2385-2390 的降级语义）。
 */
public class MarketplaceReconciler implements PluginInstallationManager.MarketplacesDiffer,
        PluginInstallationManager.MarketplacesReconciler {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceReconciler.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MarketplaceConfigStore store;
    private final MarketplaceSyncService sync;
    private final Supplier<String> projectRoot;
    private final Predicate<ReconcileSkipKey> skipFilter;

    /** reconcile skip 判定入参 · CC ReconcileOptions.skip(name, source)。 */
    public record ReconcileSkipKey(String name, MarketplaceSource source) {}

    public MarketplaceReconciler(MarketplaceConfigStore store, MarketplaceSyncService sync,
                                 Supplier<String> projectRoot, Predicate<ReconcileSkipKey> skipFilter) {
        this.store = Objects.requireNonNull(store);
        this.sync = sync != null ? sync : new MarketplaceSyncService();
        // 方案1 接线：经 CwdResolution.getOriginalCwdLayer(RequestContext.sessionId()) 取会话 original cwd
        //   （对齐 CC reconciler.ts:131 diffMarketplaces 传 projectRoot: getOriginalCwd() + :257
        //    normalizeSource 的 base = projectRoot ?? getOriginalCwd()）。startup 无会话回落 user.dir 零变化。
        this.projectRoot = projectRoot != null ? projectRoot
            : () -> CwdResolution.getOriginalCwdLayer(RequestContext.sessionId());
        this.skipFilter = skipFilter;
    }

    public MarketplaceReconciler(MarketplaceConfigStore store) {
        this(store, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // Differ —— diffMarketplaces（reconciler.ts:50-83）
    // ════════════════════════════════════════════════════════════════════

    @Override
    public MarketplaceDiff diff(List<Marketplace> declared, Map<String, MarketplaceConfig> materialized) {
        List<String> missing = new ArrayList<>();
        List<Marketplace> sourceChanged = new ArrayList<>();
        List<String> upToDate = new ArrayList<>();

        for (Marketplace intent : declared) {
            MarketplaceConfig state = materialized.get(intent.name());
            MarketplaceSource normalizedIntent = normalizeSource(intent.source());

            if (state == null) {
                missing.add(intent.name());
            } else if (intent.sourceIsFallback()) {
                // 兜底：只要已物化即够，不比 source（reconciler.ts:65-70），防重克隆踩掉已物化内容
                upToDate.add(intent.name());
            } else if (!Objects.equals(normalizedIntent, state.source())) {
                sourceChanged.add(new Marketplace(intent.name(), normalizedIntent, false));
            } else {
                upToDate.add(intent.name());
            }
        }
        return new MarketplaceDiff(missing, sourceChanged, upToDate);
    }

    // ════════════════════════════════════════════════════════════════════
    // Reconciler —— reconcileMarketplaces（reconciler.ts:114-234）
    // ════════════════════════════════════════════════════════════════════

    @Override
    public ReconcileResult reconcile(Consumer<ReconcileEvent> onProgress) {
        Map<String, MarketplaceConfigStore.DeclaredMarketplace> declared = store.getDeclaredMarketplaces();
        if (declared.isEmpty()) {
            return new ReconcileResult(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        Map<String, KnownMarketplace> materialized;
        try {
            materialized = store.loadKnownMarketplacesConfig();
        } catch (Exception e) {
            log.error("加载 known_marketplaces.json 失败，降级 {} 继续 reconcile：{}", "{}", e.getMessage());
            materialized = new LinkedHashMap<>();
        }

        MarketplaceDiff diff = diffMarketplaces(declared, materialized);

        List<WorkItem> work = new ArrayList<>();
        for (String name : diff.missing()) {
            work.add(new WorkItem(name, normalizeSource(declared.get(name).source()), "install"));
        }
        for (Marketplace c : diff.sourceChanged()) {
            work.add(new WorkItem(c.name(), c.source(), "update"));
        }

        List<String> skipped = new ArrayList<>();
        List<WorkItem> toProcess = new ArrayList<>();
        for (WorkItem item : work) {
            if (skipFilter != null && skipFilter.test(new ReconcileSkipKey(item.name(), item.source()))) {
                skipped.add(item.name());
                continue;
            }
            // 死路径跳过：sourceChanged 且 local 源路径不存在 → 保留已物化条目（reconciler.ts:169-179）
            if ("update".equals(item.action()) && isLocalSource(item.source())
                && !Files.exists(Paths.get(localSourcePath(item.source())))) {
                if (log.isDebugEnabled()) {
                    log.debug("[reconcile] '{}' declared path 不存在，保留已物化条目", item.name());
                }
                skipped.add(item.name());
                continue;
            }
            toProcess.add(item);
        }

        if (toProcess.isEmpty()) {
            return new ReconcileResult(List.of(), List.of(), diff.upToDate(), List.of(), skipped);
        }

        List<String> installed = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<ReconcileResult.FailedInstall> failed = new ArrayList<>();

        for (int i = 0; i < toProcess.size(); i++) {
            WorkItem item = toProcess.get(i);
            if (onProgress != null) {
                onProgress.accept(new ReconcileEvent("installing", item.name(), null));
            }
            try {
                AddMarketplaceResult result = addMarketplaceSource(item.source());
                if ("install".equals(item.action())) {
                    installed.add(item.name());
                } else {
                    updated.add(item.name());
                }
                if (onProgress != null) {
                    onProgress.accept(new ReconcileEvent("installed", item.name(), null));
                }
            } catch (Exception e) {
                String error = e.getMessage();
                failed.add(new ReconcileResult.FailedInstall(item.name(), error));
                if (onProgress != null) {
                    onProgress.accept(new ReconcileEvent("failed", item.name(), error));
                }
                log.error("[reconcile] '{}' 安装失败: {}", item.name(), error);
            }
        }

        return new ReconcileResult(installed, updated, diff.upToDate(), failed, skipped);
    }

    private record WorkItem(String name, MarketplaceSource source, String action) {}

    /** CC diffMarketplaces 富类型版（reconciler.ts:50-83）：declared 为 Map（含 sourceIsFallback）。 */
    public MarketplaceDiff diffMarketplaces(Map<String, MarketplaceConfigStore.DeclaredMarketplace> declared,
                                            Map<String, KnownMarketplace> materialized) {
        Map<String, MarketplaceConfig> matSources = new LinkedHashMap<>();
        for (Map.Entry<String, KnownMarketplace> e : materialized.entrySet()) {
            matSources.put(e.getKey(), new MarketplaceConfig(e.getValue().source()));
        }
        List<Marketplace> declaredList = new ArrayList<>();
        for (Map.Entry<String, MarketplaceConfigStore.DeclaredMarketplace> e : declared.entrySet()) {
            MarketplaceConfigStore.DeclaredMarketplace d = e.getValue();
            declaredList.add(new Marketplace(e.getKey(), d.source(), d.sourceIsFallback()));
        }
        return diff(declaredList, matSources);
    }

    // ════════════════════════════════════════════════════════════════════
    // addMarketplaceSource（marketplaceManager.ts:1782-1923）
    // ════════════════════════════════════════════════════════════════════

    public record AddMarketplaceResult(String name, boolean alreadyMaterialized,
                                       MarketplaceSource resolvedSource) {}

    /**
     * 安装/更新单个 marketplace 源 · CC {@code addMarketplaceSource}（:1782-1923）。
     *
     * <p>源幂等：config 中已存在等值 source → {@code alreadyMaterialized=true} 直接返回不 clone
     * （:1834-1842）。否则 loadAndCacheMarketplace 物化（clone/本地路径）→ 解析 marketplace.json
     * 取真实 name → config[name]={source, installLocation, lastUpdated} 落盘（:1913-1919）。
     * 同名不同源 → 覆盖（settings 意图胜，:1859-1875）。
     */
    public AddMarketplaceResult addMarketplaceSource(MarketplaceSource source) throws IOException {
        MarketplaceSource resolved = resolveLocalPath(source);

        Map<String, KnownMarketplace> existing = store.loadKnownMarketplacesConfig();
        for (Map.Entry<String, KnownMarketplace> e : existing.entrySet()) {
            if (Objects.equals(e.getValue().source(), resolved)) {
                if (log.isDebugEnabled()) {
                    log.debug("源已物化为 '{}'，跳过 clone", e.getKey());
                }
                return new AddMarketplaceResult(e.getKey(), true, resolved);
            }
        }

        LoadedMarketplace loaded = loadAndCacheMarketplace(resolved);

        // CC :1851-1860 —— 覆盖前官方名源守卫：保留官方名（claude-code-marketplace 等）只能来自 anthropics/
        // GitHub 组织，否则拒绝（对齐 CC validateOfficialNameSource schemas.ts:119-157）
        String officialGuard = com.nexusai.application.agent.plugin.PluginSchemas.validateOfficialNameSource(
            loaded.marketplace().name(), resolved);
        if (officialGuard != null) {
            throw new IOException(officialGuard);
        }

        // CC :1863-1872 —— 覆盖前 seed 托管守卫：同名条目落在 seed 目录 → 拒绝覆盖（admin 控制）
        Map<String, KnownMarketplace> config = store.loadKnownMarketplacesConfig();
        KnownMarketplace oldEntry = config.get(loaded.marketplace().name());
        if (oldEntry != null) {
            String seedDir = MarketplaceManager.seedDirFor(oldEntry.installLocation());
            if (seedDir != null) {
                throw new IOException("Marketplace '" + loaded.marketplace().name()
                    + "' is seed-managed (" + seedDir
                    + "). To use a different source, ask your admin to update the seed, "
                    + "or use a different marketplace name.");
            }
        }
        config.put(loaded.marketplace().name(), new KnownMarketplace(
            resolved,
            loaded.cachePath(),
            Instant.now().toString(),
            null));
        store.saveKnownMarketplacesConfig(config);
        log.info("已添加 marketplace 源：{}", loaded.marketplace().name());
        return new AddMarketplaceResult(loaded.marketplace().name(), false, resolved);
    }

    private record LoadedMarketplace(PluginMarketplace.Marketplace marketplace, String cachePath) {}

    /**
     * 物化 marketplace 到缓存目录 · CC {@code loadAndCacheMarketplace}（:1433-1735）。
     * github/git → git clone；directory/file → 本地路径（不 clone）；url/npm/settings → 抛指引错误。
     */
    private LoadedMarketplace loadAndCacheMarketplace(MarketplaceSource source) throws IOException {
        String cacheDir = store.getMarketplacesCacheDir();
        Files.createDirectories(Paths.get(cacheDir));

        String tempName = getCachePathForSource(source);
        String marketplacePath;
        String temporaryCachePath;
        boolean cleanupNeeded;
        boolean localSource = isLocalSource(source);

        switch (source) {
            case MarketplaceSource.Github gh -> {
                temporaryCachePath = Paths.get(cacheDir, tempName).toString();
                cleanupNeeded = true;
                cloneGithub(gh, temporaryCachePath);
                marketplacePath = Paths.get(temporaryCachePath,
                    gh.path() != null ? gh.path() : ".claude-plugin/marketplace.json").toString();
            }
            case MarketplaceSource.Git git -> {
                temporaryCachePath = Paths.get(cacheDir, tempName).toString();
                cleanupNeeded = true;
                sync.cacheMarketplaceFromGit(git.url(), temporaryCachePath, git.ref(), git.sparsePaths(), false);
                marketplacePath = Paths.get(temporaryCachePath,
                    git.path() != null ? git.path() : ".claude-plugin/marketplace.json").toString();
            }
            case MarketplaceSource.File f -> {
                String abs = ((MarketplaceSource.File) resolveLocalPath(f)).path();
                marketplacePath = abs;
                temporaryCachePath = Paths.get(abs).getParent().getParent().toString();
                cleanupNeeded = false;
            }
            case MarketplaceSource.Directory d -> {
                String abs = ((MarketplaceSource.Directory) resolveLocalPath(d)).path();
                marketplacePath = Paths.get(abs, ".claude-plugin", "marketplace.json").toString();
                temporaryCachePath = abs;
                cleanupNeeded = false;
            }
            case MarketplaceSource.Url u ->
                throw new IOException("URL marketplace 源重新下载属 L4 安装层（MPL4），暂不支持 reconcile: " + u.url());
            case MarketplaceSource.Npm n ->
                throw new IOException("NPM marketplace 源尚未实现（CC loadAndCacheMarketplace :1625-1628 同样 throw）: "
                    + n.npmPackage());
            case MarketplaceSource.Settings s ->
                throw new IOException("settings 内联 marketplace 合成属 L9 官方市场层，暂不支持 reconcile: " + s.name());
            case MarketplaceSource.HostPattern h ->
                throw new IOException("hostPattern 源不用于 reconcile 安装: " + h.hostPattern());
            case MarketplaceSource.PathPattern p ->
                throw new IOException("pathPattern 源不用于 reconcile 安装: " + p.pathPattern());
        }

        PluginMarketplace.Marketplace marketplace = parseMarketplaceFile(Paths.get(marketplacePath));

        String finalCachePath = Paths.get(cacheDir, marketplace.name()).toString();
        // 防御：marketplace.name 必须解析到 cacheDir 严格子目录（CC :1696-1706）
        Path resolvedFinal = Paths.get(finalCachePath).toAbsolutePath().normalize();
        Path resolvedCache = Paths.get(cacheDir).toAbsolutePath().normalize();
        if (resolvedFinal.equals(resolvedCache)
            || !resolvedFinal.startsWith(resolvedCache)
            || marketplace.name().contains("..") || marketplace.name().contains("/")
            || marketplace.name().contains("\\")) {
            throw new IOException("Marketplace name '" + marketplace.name() + "' 解析到缓存目录之外，拒绝物化");
        }

        if (!temporaryCachePath.equals(finalCachePath) && !localSource) {
            try {
                Path finalPath = Paths.get(finalCachePath);
                if (Files.exists(finalPath)) {
                    Files.walk(finalPath).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
                Files.move(Paths.get(temporaryCachePath), finalPath, StandardCopyOption.ATOMIC_MOVE);
                temporaryCachePath = finalCachePath;
                cleanupNeeded = false;
            } catch (IOException error) {
                throw new IOException("Failed to finalize marketplace cache. Please manually delete the directory at "
                    + finalCachePath + " if it exists and try again.\n\nTechnical details: " + error.getMessage(), error);
            }
        }

        return new LoadedMarketplace(marketplace, temporaryCachePath);
    }

    /** github 源 SSH/HTTPS 回退 clone（对齐 CC loadAndCacheMarketplace :1476-1564）。 */
    private void cloneGithub(MarketplaceSource.Github gh, String cachePath) throws IOException {
        String sshUrl = "git@github.com:" + gh.repo() + ".git";
        String httpsUrl = "https://github.com/" + gh.repo() + ".git";
        boolean sshConfigured = sync.isGitHubSshLikelyConfigured();

        if (sshConfigured) {
            try {
                sync.cacheMarketplaceFromGit(sshUrl, cachePath, gh.ref(), gh.sparsePaths(), false);
                return;
            } catch (IOException sshErr) {
                log.warn("SSH clone 失败，回退 HTTPS：{}", sshErr.getMessage());
                deleteTree(cachePath);
                sync.cacheMarketplaceFromGit(httpsUrl, cachePath, gh.ref(), gh.sparsePaths(), false);
                return;
            }
        }
        try {
            sync.cacheMarketplaceFromGit(httpsUrl, cachePath, gh.ref(), gh.sparsePaths(), false);
        } catch (IOException httpsErr) {
            log.warn("HTTPS clone 失败，回退 SSH：{}", httpsErr.getMessage());
            deleteTree(cachePath);
            sync.cacheMarketplaceFromGit(sshUrl, cachePath, gh.ref(), gh.sparsePaths(), false);
        }
    }

    /** CC getCachePathForSource（:1355-1369）：github→repo 下划线化；directory/file→basename；git→temp_ts。 */
    private static String getCachePathForSource(MarketplaceSource source) {
        return switch (source) {
            case MarketplaceSource.Github gh -> gh.repo().replace('/', '-');
            case MarketplaceSource.Git g -> "temp_" + System.currentTimeMillis();
            case MarketplaceSource.File f -> Paths.get(f.path()).getFileName().toString().replace(".json", "");
            case MarketplaceSource.Directory d -> Paths.get(d.path()).getFileName().toString();
            default -> "temp_" + System.currentTimeMillis();
        };
    }

    /**
     * 源归一化 · CC {@code normalizeSource}（reconciler.ts:249-265）。
     * directory/file 相对路径 → 相对 canonical git root（worktree 主 checkout）解析，保证跨 worktree 稳定。
     */
    private MarketplaceSource normalizeSource(MarketplaceSource source) {
        if (source instanceof MarketplaceSource.Directory d && !Paths.get(d.path()).isAbsolute()) {
            String base = projectRoot.get();
            String canonical = AutoMemPaths.findCanonicalGitRoot(base);
            String resolved = Paths.get(canonical != null ? canonical : base).resolve(d.path()).normalize().toString();
            return new MarketplaceSource.Directory(resolved);
        }
        if (source instanceof MarketplaceSource.File f && !Paths.get(f.path()).isAbsolute()) {
            String base = projectRoot.get();
            String canonical = AutoMemPaths.findCanonicalGitRoot(base);
            String resolved = Paths.get(canonical != null ? canonical : base).resolve(f.path()).normalize().toString();
            return new MarketplaceSource.File(resolved);
        }
        return source;
    }

    /** 相对本地路径 → 绝对（cwd 无关，对齐 addMarketplaceSource :1790-1794）。 */
    private MarketplaceSource resolveLocalPath(MarketplaceSource source) {
        if (source instanceof MarketplaceSource.Directory d && !Paths.get(d.path()).isAbsolute()) {
            return new MarketplaceSource.Directory(Paths.get(store.getOriginalCwd()).resolve(d.path()).normalize().toString());
        }
        if (source instanceof MarketplaceSource.File f && !Paths.get(f.path()).isAbsolute()) {
            return new MarketplaceSource.File(Paths.get(store.getOriginalCwd()).resolve(f.path()).normalize().toString());
        }
        return source;
    }

    /** CC isLocalMarketplaceSource（schemas.ts:1236-1241）：file / directory。 */
    private static boolean isLocalSource(MarketplaceSource source) {
        return source instanceof MarketplaceSource.File || source instanceof MarketplaceSource.Directory;
    }

    private static String localSourcePath(MarketplaceSource source) {
        if (source instanceof MarketplaceSource.File f) {
            return f.path();
        }
        if (source instanceof MarketplaceSource.Directory d) {
            return d.path();
        }
        return null;
    }

    private PluginMarketplace.Marketplace parseMarketplaceFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return JSON.readValue(content, PluginMarketplace.Marketplace.class);
        } catch (IOException e) {
            throw new IOException("Failed to parse marketplace manifest " + file + ": " + e.getMessage(), e);
        }
    }

    private static void deleteTree(String path) {
        try {
            Path p = Paths.get(path);
            if (Files.exists(p)) {
                Files.walk(p).sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
