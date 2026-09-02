package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Plugin 安装器 · 对齐 CC {@code utils/plugins/pluginLoader.ts:911} {@code cachePlugin}（5 源分派）
 * + {@code utils/plugins/pluginInstallationHelpers.ts:128} {@code cacheAndRegisterPlugin}
 * + {@code :348} {@code installResolvedPlugin}。
 *
 * <p><b>cachePlugin 5 源分派</b>（pluginLoader.ts:911-1098）：
 * <pre>
 * string        → installFromLocal   （复制 + 删 .git，:856-868）
 * {npm}         → installFromNpm     （npm install --prefix npm-cache + 复制，:492-524）
 * {github}      → installFromGitHub  （owner/repo 校验 + git clone，:662-678）
 * {url}         → installFromGit     （git clone，:645-657）
 * {git-subdir}  → installFromGitSubdir（partial clone + sparse-checkout，:718-851）
 * {pip}         → throw               （不支持，:959-960）
 * </pre>
 *
 * <p><b>安装链</b>（本类职责，session MPL4 唯一目标）：
 * <ol>
 *   <li>{@link #cachePlugin} — 各源落临时目录 → 读 manifest → 改名落 cache/{name}</li>
 *   <li>{@link #cacheAndRegisterPlugin} — cachePlugin → 版本计算 → versionedPath → 移动 → 注册表</li>
 *   <li>{@link #installResolvedPlugin} — policy 守卫 → 依赖闭包 → enabledPlugins 写 → 逐成员 cacheAndRegisterPlugin</li>
 * </ol>
 *
 * <p><b>失败清理</b>（:965-977）：任何源失败 → 临时目录 rm -rf（递归 force），磁盘无残留。
 *
 * <p><b>中文日志</b>：所有 log 用 slf4j + logback，中文，debug 用 if(log.isDebugEnabled())。
 */
public class PluginInstaller {

    private static final Logger log = LoggerFactory.getLogger(PluginInstaller.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── 依赖（可注入，测试 mock）─────────────────────────────────────────

    private GitProcessRunner gitRunner;
    private MarketplaceManager marketplaceManager;
    private InstalledPluginsManager installedPluginsManager;
    private PluginCacheUtils cacheUtils;
    /** npm 执行器（可注入 mock，规避真实网络）；默认 ProcessBuilder 实现。 */
    private NpmRunner npmRunner;
    /** org policy 守卫 · CC pluginPolicy.ts:17 isPluginBlockedByPolicy（policySettings.enabledPlugins[id]===false）。 */
    private Predicate<String> policyGate;

    /** cwd 提供者（project/local scope 需 projectPath= getCwd，CC pluginInstallationHelpers.ts:447）。 */
    private Function<Void, String> cwdProvider;

    /** 依赖闭包解析器（MPL9-UNIFY：统一走 PluginDependencyResolver 实例方法，消除 :830 static 双实现）。 */
    private PluginDependencyResolver dependencyResolver = new PluginDependencyResolver();

    // ── 源模型 ─────────────────────────────────────────────────────────

    /** PluginSource 判别联合 · CC PluginSourceSchema（schemas.ts:1062-1161）。 */
    public sealed interface PluginSource permits Local, Npm, GitHub, Url, GitSubdir {
    }

    /** string 相对路径 → 本地源 · CC schemas.ts:1064 RelativePath。 */
    public record Local(String path) implements PluginSource {
    }

    /** {@code {source:'npm', package, version?, registry?}} · CC schemas.ts:1068-1087。 */
    public record Npm(String pkg, String version, String registry) implements PluginSource {
    }

    /** {@code {source:'github', repo, ref?, sha?}} · CC schemas.ts:1120-1130。 */
    public record GitHub(String repo, String ref, String sha) implements PluginSource {
    }

    /** {@code {source:'url', url, ref?, sha?}} · CC schemas.ts:1107-1119。 */
    public record Url(String url, String ref, String sha) implements PluginSource {
    }

    /** {@code {source:'git-subdir', url, path, ref?, sha?}} · CC schemas.ts:1131-1157。 */
    public record GitSubdir(String url, String subdirPath, String ref, String sha) implements PluginSource {
    }

    // ── 结果模型 ─────────────────────────────────────────────────────────

    /** cachePlugin 结果 · CC :1093-1097 {@code {path, manifest, gitCommitSha?}}。 */
    public record CacheResult(String path, JsonNode manifest, String gitCommitSha) {
    }

    /** installResolvedPlugin 核心结果 · CC :282-297 InstallCoreResult。 */
    public record CoreResult(boolean ok, List<String> closure, String depNote,
                             String reason, String pluginName, String blockedDependency, String message) {
        public static CoreResult ok(List<String> closure, String depNote) {
            return new CoreResult(true, closure, depNote, null, null, null, null);
        }

        public static CoreResult fail(String reason, String pluginName) {
            return new CoreResult(false, List.of(), "", reason, pluginName, null, null);
        }

        public static CoreResult fail(String reason, String pluginName, String blockedDependency) {
            return new CoreResult(false, List.of(), "", reason, pluginName, blockedDependency, null);
        }
    }

    /** 依赖闭包解析结果 · CC dependencyResolver.ts:60-70 ResolutionResult。 */
    public record ResolutionResult(boolean ok, String reason, List<String> closure, String missing,
                                   String requiredBy, List<String> chain) {
        static ResolutionResult ok(List<String> closure) {
            return new ResolutionResult(true, null, closure, null, null, null);
        }

        static ResolutionResult fail(String reason, String missing, String requiredBy, List<String> chain) {
            return new ResolutionResult(false, reason, List.of(), missing, requiredBy, chain);
        }
    }

    // ── npm 执行接口 ──────────────────────────────────────────────────────

    /** npm 命令执行器 · CC execFileNoThrow('npm', args)（pluginLoader.ts:513）。 */
    @FunctionalInterface
    public interface NpmRunner {
        GitProcessRunner.Result exec(List<String> args, String cwd, long timeoutMs);
    }

    // ── 构造 ─────────────────────────────────────────────────────────────

    public PluginInstaller() {
        this.gitRunner = new GitProcessRunner();
        this.npmRunner = new ProcessBuilderNpmRunner();
        this.policyGate = id -> false;
        // 方案1 接线：安装 projectPath 写入用会话当前 cwd（对齐 CC pluginInstallationHelpers.ts:447
        //   installResolvedPlugin projectPath = scope !== 'user' ? getCwd() : undefined）。
        //   经 CwdResolution.getCwd(RequestContext.sessionId())（override ?? sessionCwd ?? boundProject
        //   ?? user.dir），无会话回落 user.dir 零变化。
        this.cwdProvider = v -> CwdResolution.getCwd(RequestContext.sessionId());
    }

    public PluginInstaller(GitProcessRunner gitRunner, MarketplaceManager marketplaceManager,
                           InstalledPluginsManager installedPluginsManager, PluginCacheUtils cacheUtils,
                           NpmRunner npmRunner, Predicate<String> policyGate) {
        this.gitRunner = gitRunner != null ? gitRunner : new GitProcessRunner();
        this.marketplaceManager = marketplaceManager;
        this.installedPluginsManager = installedPluginsManager;
        this.cacheUtils = cacheUtils;
        this.npmRunner = npmRunner != null ? npmRunner : new ProcessBuilderNpmRunner();
        this.policyGate = policyGate != null ? policyGate : id -> false;
        // 方案1 接线：同无参构造（CC getCwd，pluginInstallationHelpers.ts:447）。
        this.cwdProvider = v -> CwdResolution.getCwd(RequestContext.sessionId());
    }

    // ── 测试 setter ──────────────────────────────────────────────────────

    public void setGitRunner(GitProcessRunner gitRunner) {
        this.gitRunner = gitRunner;
    }

    public void setMarketplaceManager(MarketplaceManager marketplaceManager) {
        this.marketplaceManager = marketplaceManager;
    }

    public void setInstalledPluginsManager(InstalledPluginsManager installedPluginsManager) {
        this.installedPluginsManager = installedPluginsManager;
    }

    public void setCacheUtils(PluginCacheUtils cacheUtils) {
        this.cacheUtils = cacheUtils;
    }

    public void setNpmRunner(NpmRunner npmRunner) {
        this.npmRunner = npmRunner;
    }

    public void setPolicyGate(Predicate<String> policyGate) {
        this.policyGate = policyGate;
    }

    public void setCwdProvider(Function<Void, String> cwdProvider) {
        this.cwdProvider = cwdProvider;
    }

    public void setDependencyResolver(PluginDependencyResolver dependencyResolver) {
        this.dependencyResolver = dependencyResolver;
    }

    // ════════════════════════════════════════════════════════════════════
    // 安装链
    // ════════════════════════════════════════════════════════════════════

    /**
     * installResolvedPlugin 等价 · CC pluginInstallationHelpers.ts:348-481。
     *
     * <ol>
     *   <li>policy 守卫（blocked-by-policy，:365-367）</li>
     *   <li>local-source-no-location 守卫（:380-386）</li>
     *   <li>resolveDependencyClosure（:398-412）</li>
     *   <li>transitive policy 守卫（:418-427）</li>
     *   <li>写 enabledPlugins 闭包（:429-444）→ Java 映射 InstalledPluginsManager.setEnabled</li>
     *   <li>逐成员 cacheAndRegisterPlugin（:446-473）</li>
     *   <li>clearAllCaches（:475）</li>
     * </ol>
     */
    public CoreResult installResolvedPlugin(String pluginId, PluginMarketplace.Entry entry, String scope,
                                            String marketplaceInstallLocation) {
        String pluginName = entry.name();

        // ── policy 守卫 ──
        if (isPluginBlockedByPolicy(pluginId)) {
            if (log.isDebugEnabled()) {
                log.debug("插件 {} 被 org policy 阻断（blocked-by-policy，CC :365-367）", pluginId);
            }
            return CoreResult.fail("blocked-by-policy", pluginName);
        }

        // ── local source 无 installLocation 守卫 ──
        if (isLocalSource(entry.source()) && (marketplaceInstallLocation == null || marketplaceInstallLocation.isBlank())) {
            if (log.isDebugEnabled()) {
                log.debug("本地源插件 {} 无 marketplace install location（local-source-no-location，CC :380-386）", pluginId);
            }
            return CoreResult.fail("local-source-no-location", pluginName);
        }

        // ── 依赖闭包 ──
        Map<String, PluginMarketplace.LookupResult> depInfo = new LinkedHashMap<>();
        if (marketplaceInstallLocation != null && !marketplaceInstallLocation.isBlank()) {
            depInfo.put(pluginId, new PluginMarketplace.LookupResult(entry, marketplaceInstallLocation));
        }

        String rootMarketplace = PluginIdentifier.parse(pluginId).marketplace();
        Set<String> allowedCross = new HashSet<>();
        if (rootMarketplace != null && marketplaceManager != null) {
            PluginMarketplace.Marketplace root = marketplaceManager.getMarketplaceCacheOnly(rootMarketplace);
            if (root != null && root.allowCrossMarketplaceDependenciesOn() != null) {
                allowedCross.addAll(root.allowCrossMarketplaceDependenciesOn());
            }
        }

        ResolutionResult resolution = toInstallerResult(dependencyResolver.resolveDependencyClosure(pluginId,
            id -> {
                if (depInfo.containsKey(id)) {
                    return new PluginDependencyResolver.DependencyLookupResult(depInfo.get(id).entry().dependencies());
                }
                if (id.equals(pluginId)) {
                    return new PluginDependencyResolver.DependencyLookupResult(entry.dependencies());
                }
                if (marketplaceManager == null) {
                    return null;
                }
                PluginMarketplace.LookupResult info = marketplaceManager.getPluginById(id);
                if (info != null) {
                    depInfo.put(id, info);
                }
                return info == null ? null : new PluginDependencyResolver.DependencyLookupResult(info.entry().dependencies());
            },
            getEnabledPluginIds(),
            allowedCross));

        if (!resolution.ok()) {
            if (log.isWarnEnabled()) {
                log.warn("插件 {} 依赖闭包解析失败: reason={} missing={} requiredBy={} chain={}",
                    pluginId, resolution.reason(), resolution.missing(), resolution.requiredBy(), resolution.chain());
            }
            return new CoreResult(false, List.of(), "", "resolution-failed", pluginName,
                resolution.missing(), formatResolutionError(resolution));
        }

        // ── transitive policy 守卫 ──
        for (String id : resolution.closure()) {
            if (!id.equals(pluginId) && isPluginBlockedByPolicy(id)) {
                if (log.isWarnEnabled()) {
                    log.warn("依赖 {} 被 org policy 阻断（dependency-blocked-by-policy，CC :418-427）", id);
                }
                return CoreResult.fail("dependency-blocked-by-policy", pluginName, id);
            }
        }

        // ── 写 enabledPlugins 闭包（Java 映射 InstalledPluginsManager 的 enabled 标志）──
        for (String id : resolution.closure()) {
            markEnabled(pluginIdName(id));
        }

        // ── 逐成员 cacheAndRegisterPlugin ──
        String projectPath = !"user".equals(scope) ? cwdProvider.apply(null) : null;
        for (String id : resolution.closure()) {
            PluginMarketplace.LookupResult info = depInfo.get(id);
            if (info == null && id.equals(pluginId) && marketplaceManager != null) {
                info = marketplaceManager.getPluginById(id);
                if (info != null) {
                    depInfo.put(id, info);
                }
            }
            if (info == null) {
                if (log.isWarnEnabled()) {
                    log.warn("依赖 {} 未找到 entry，跳过物化（CC :456 continue）", id);
                }
                continue;
            }
            String localSourcePath = null;
            if (isLocalSource(info.entry().source())) {
                localSourcePath = validatePathWithinBase(info.marketplaceInstallLocation(), info.entry().source().asText());
            }
            try {
                cacheAndRegisterPlugin(id, info.entry(), scope, projectPath, localSourcePath);
            } catch (IOException e) {
                if (log.isWarnEnabled()) {
                    log.warn("插件 {} 物化失败: {}", id, e.getMessage());
                }
                return new CoreResult(false, List.of(), "", "materialize-failed", id, null, e.getMessage());
            }
        }

        if (cacheUtils != null) {
            cacheUtils.clearAllCaches();
        } else {
            PluginCacheUtils c = new PluginCacheUtils();
            c.clearAllCaches();
        }

        List<String> deps = resolution.closure().stream().filter(id -> !id.equals(pluginId)).toList();
        String depNote = formatDependencyCountSuffix(deps);
        if (log.isInfoEnabled()) {
            log.info("插件 {} 安装完成: 依赖闭包 {} 个成员全部 enabled + 物化（CC installResolvedPlugin :348-481）",
                pluginId, resolution.closure().size());
        }
        return CoreResult.ok(resolution.closure(), depNote);
    }

    /**
     * cacheAndRegisterPlugin 等价 · CC pluginInstallationHelpers.ts:128-226。
     *
     * <ol>
     *   <li>cachePlugin（:142-144）</li>
     *   <li>gitCommitSha 计算（:151-153）</li>
     *   <li>calculatePluginVersion（:156-163）</li>
     *   <li>versionedPath 移动（:165-202，含子目录特判 :178-200）</li>
     *   <li>addInstalledPlugin（:212-223）→ Java InstalledPluginsManager</li>
     * </ol>
     */
    public String cacheAndRegisterPlugin(String pluginId, PluginMarketplace.Entry entry, String scope,
                                         String projectPath, String localSourcePath) throws IOException {
        PluginSource source = isLocalSource(entry.source())
            ? new Local(localSourcePath != null ? localSourcePath : entry.source().asText())
            : parseSource(entry.source());

        CacheResult cacheResult = cachePlugin(source, JSON.valueToTree(entry));

        // gitCommitSha：cachePlugin 已捕获（git-subdir）→ 用；否则从安装目录 rev-parse（CC :151-153）
        String pathForGitSha = localSourcePath != null ? localSourcePath : cacheResult.path();
        String gitCommitSha = cacheResult.gitCommitSha();
        if (gitCommitSha == null) {
            gitCommitSha = getGitCommitSha(pathForGitSha);
        }

        long now = System.currentTimeMillis();
        String version = calculatePluginVersion(pluginId, source, cacheResult.manifest(),
            pathForGitSha, entry.version(), gitCommitSha);

        // versionedPath：cache/marketplace/plugin/version（CC :166）
        String versionedPath = InstalledPluginsFileStore.getVersionedCachePath(pluginId, version);
        String finalPath = cacheResult.path();

        if (!cacheResult.path().equals(versionedPath)) {
            Path versionedDir = Paths.get(versionedPath).getParent();
            if (versionedDir != null) {
                Files.createDirectories(versionedDir);
            }
            // Remove existing versioned path if present（force: no-op if missing，CC :175）
            deleteRecursively(Paths.get(versionedPath));

            // versionedPath 是 cacheResult.path 的子目录特判（marketplace 名 == 插件名，CC :178-200）
            String normalizedCache = cacheResult.path().endsWith(java.io.File.separator)
                ? cacheResult.path() : cacheResult.path() + java.io.File.separator;
            boolean isSubdirectory = versionedPath.startsWith(normalizedCache);
            if (isSubdirectory) {
                Path tempPath = Paths.get(Paths.get(cacheResult.path()).getParent().toString(),
                    ".claude-plugin-temp-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
                Files.move(Paths.get(cacheResult.path()), tempPath, StandardCopyOption.ATOMIC_MOVE);
                Files.createDirectories(Paths.get(versionedPath).getParent());
                Files.move(tempPath, Paths.get(versionedPath), StandardCopyOption.ATOMIC_MOVE);
            } else {
                moveDirectory(Paths.get(cacheResult.path()), Paths.get(versionedPath));
            }
            finalPath = versionedPath;
        }

        // 注册表（version/installedAt/installPath/gitCommitSha 四字段，验收 #4）
        if (installedPluginsManager != null) {
            installedPluginsManager.addInstalledPlugin(pluginId, version, now, finalPath, gitCommitSha,
                scope, projectPath);
        }

        if (log.isInfoEnabled()) {
            log.info("插件 {} 已缓存并注册: version={} path={} gitCommitSha={}（CC cacheAndRegisterPlugin :128-226）",
                pluginId, version, finalPath, gitCommitSha);
        }
        return finalPath;
    }

    /**
     * cachePlugin 等价 · CC pluginLoader.ts:911-1098。5 源分派 + 失败清理 + manifest 读取 + 改名。
     */
    public CacheResult cachePlugin(PluginSource source, JsonNode manifest) throws IOException {
        String cachePath = Paths.get(PluginDirectories.getPluginsDirectory(), "cache").toString();
        Files.createDirectories(Paths.get(cachePath));

        String tempName = generateTemporaryCacheNameForPlugin(source);
        String tempPath = Paths.get(cachePath, tempName).toString();

        boolean shouldCleanup = false;
        String gitCommitSha = null;
        try {
            if (log.isDebugEnabled()) {
                log.debug("缓存插件源 {} 到临时路径 {}（CC cachePlugin :928-930）", source, tempPath);
            }
            shouldCleanup = true;
            switch (source) {
                case Local l -> installFromLocal(l.path(), tempPath);
                case Npm n -> installFromNpm(n.pkg(), tempPath, n.version(), n.registry());
                case GitHub g -> installFromGitHub(g.repo(), tempPath, g.ref(), g.sha());
                case Url u -> installFromGit(u.url(), tempPath, u.ref(), u.sha());
                case GitSubdir gs ->
                    gitCommitSha = installFromGitSubdir(gs.url(), tempPath, gs.subdirPath(), gs.ref(), gs.sha());
            }
        } catch (Exception e) {
            if (shouldCleanup && Files.exists(Paths.get(tempPath))) {
                if (log.isDebugEnabled()) {
                    log.debug("清理失败的安装临时目录 {}（CC :965-976）", tempPath);
                }
                try {
                    deleteRecursively(Paths.get(tempPath));
                } catch (Exception cleanupError) {
                    if (log.isWarnEnabled()) {
                        log.warn("清理失败: {}（CC :970-974）", cleanupError.getMessage());
                    }
                }
            }
            throw e;
        }

        // manifest 读取：.claude-plugin/plugin.json 或 plugin.json，均无 → 用入参 manifest 或默认（CC :979-1079）
        JsonNode loadedManifest = loadManifest(tempPath, manifest, tempName, source);

        // 改名到 cache/{name}（CC :1081-1089）
        String finalName = loadedManifest.path("name").isTextual()
            ? loadedManifest.path("name").asText().replaceAll("[^a-zA-Z0-9-_]", "-")
            : tempName;
        String finalPath = Paths.get(cachePath, finalName).toString();
        if (Files.exists(Paths.get(finalPath))) {
            if (log.isDebugEnabled()) {
                log.debug("删除旧缓存版本 {}（CC :1084-1087）", finalPath);
            }
            deleteRecursively(Paths.get(finalPath));
        }
        moveDirectory(Paths.get(tempPath), Paths.get(finalPath));

        if (log.isDebugEnabled()) {
            log.debug("成功缓存插件 {} 到 {}（CC :1091）", loadedManifest.path("name").asText(finalName), finalPath);
        }
        return new CacheResult(finalPath, loadedManifest, gitCommitSha);
    }

    // ════════════════════════════════════════════════════════════════════
    // 5 源安装
    // ════════════════════════════════════════════════════════════════════

    /** 本地源复制 · CC installFromLocal（:856-868）：复制 + 删 .git。 */
    void installFromLocal(String sourcePath, String targetPath) throws IOException {
        Path src = Paths.get(sourcePath);
        if (!Files.exists(src)) {
            throw new IOException("源路径不存在: " + sourcePath);
        }
        copyDirectory(src, Paths.get(targetPath));
        Path gitPath = Paths.get(targetPath, ".git");
        deleteRecursively(gitPath);
        if (log.isDebugEnabled()) {
            log.debug("本地源插件从 {} 复制到 {}（CC installFromLocal :856-868）", sourcePath, targetPath);
        }
    }

    /** npm 源安装 · CC installFromNpm（:492-524）：npm install --prefix npm-cache + 复制 node_modules/pkg。 */
    void installFromNpm(String packageName, String targetPath, String version, String registry) throws IOException {
        String npmCachePath = Paths.get(PluginDirectories.getPluginsDirectory(), "npm-cache").toString();
        Files.createDirectories(Paths.get(npmCachePath));

        String packageSpec = (version != null && !version.isBlank()) ? packageName + "@" + version : packageName;
        String packagePath = Paths.get(npmCachePath, "node_modules", packageName).toString();
        boolean needsInstall = !Files.exists(Paths.get(packagePath));

        if (needsInstall) {
            List<String> args = new ArrayList<>();
            args.add("install");
            args.add(packageSpec);
            args.add("--prefix");
            args.add(npmCachePath);
            if (registry != null && !registry.isBlank()) {
                args.add("--registry");
                args.add(registry);
            }
            GitProcessRunner.Result result = npmRunner.exec(args, null, GitProcessRunner.getPluginGitTimeoutMs());
            if (result.exitCode() != 0) {
                throw new IOException("npm 包安装失败: " + result.stderr());
            }
        }
        copyDirectory(Paths.get(packagePath), Paths.get(targetPath));
        if (log.isDebugEnabled()) {
            log.debug("npm 包 {} 从缓存复制到 {}（CC installFromNpm :520-523）", packageName, targetPath);
        }
    }

    /** git 源（url）克隆 · CC installFromGit（:645-657）：validateGitUrl + gitClone。 */
    void installFromGit(String gitUrl, String targetPath, String ref, String sha) throws IOException {
        gitClone(gitUrl, targetPath, ref, sha);
        if (log.isDebugEnabled()) {
            log.debug("从 {} 克隆到 {}（CC installFromGit :645-657）",
                GitProcessRunner.redactUrlCredentials(gitUrl), targetPath);
        }
    }

    /** github 源克隆 · CC installFromGitHub（:662-678）：owner/repo 校验 + HTTPS/SSH 切换。 */
    void installFromGitHub(String repo, String targetPath, String ref, String sha) throws IOException {
        if (!repo.matches("^[a-zA-Z0-9-_.]+/[a-zA-Z0-9-_.]+$")) {
            throw new IOException("GitHub 仓库格式非法: " + repo + "，期望 owner/repo");
        }
        boolean remote = PluginDirectories.isEnvTruthy(System.getenv("CLAUDE_CODE_REMOTE"));
        String gitUrl = remote ? "https://github.com/" + repo + ".git" : "git@github.com:" + repo + ".git";
        installFromGit(gitUrl, targetPath, ref, sha);
    }

    /**
     * git-subdir 源安装 · CC installFromGitSubdir（:718-851）：partial clone + sparse-checkout + 提取子目录。
     * 返回解析后的 commit SHA（clone 被丢弃，调用方需在丢弃前捕获）。
     */
    String installFromGitSubdir(String url, String targetPath, String subdirPath, String ref, String sha)
        throws IOException {
        // git >= 2.25（sparse-checkout cone mode 需要）· CC :725-730
        GitProcessRunner.Result versionResult = gitRunner.run(
            List.of("--version"), null, GitProcessRunner.gitNoPromptEnv());
        if (!versionResult.ok()) {
            throw new IOException(
                "git-subdir 插件源需要安装 git（2.25 及以上支持 sparse-checkout cone mode）后重试（CC :725-730）");
        }

        String gitUrl = resolveGitSubdirUrl(url);
        String cloneDir = targetPath + ".clone";

        List<String> cloneArgs = new ArrayList<>();
        cloneArgs.add("clone");
        cloneArgs.add("--depth");
        cloneArgs.add("1");
        cloneArgs.add("--filter=tree:0");
        cloneArgs.add("--no-checkout");
        if (ref != null && !ref.isBlank()) {
            cloneArgs.add("--branch");
            cloneArgs.add(ref);
        }
        cloneArgs.add(gitUrl);
        cloneArgs.add(cloneDir);

        GitProcessRunner.Result cloneResult = gitRunner.run(cloneArgs, null,
            GitProcessRunner.gitNoPromptEnv());
        if (!cloneResult.ok()) {
            throw new IOException("git-subdir 源克隆失败: " + cloneResult.stderr());
        }

        String resolvedSha = null;
        try {
            GitProcessRunner.Result sparseResult = gitRunner.run(
                List.of("sparse-checkout", "set", "--cone", "--", subdirPath), cloneDir,
                GitProcessRunner.gitNoPromptEnv());
            if (!sparseResult.ok()) {
                throw new IOException(
                    "git sparse-checkout set 失败（需 git>=2.25 cone mode）: " + sparseResult.stderr());
            }

            if (sha != null && !sha.isBlank()) {
                GitProcessRunner.Result fetchSha = gitRunner.run(
                    List.of("fetch", "--depth", "1", "origin", sha), cloneDir,
                    GitProcessRunner.gitNoPromptEnv());
                if (!fetchSha.ok()) {
                    GitProcessRunner.Result unshallow = gitRunner.run(
                        List.of("fetch", "--unshallow"), cloneDir, GitProcessRunner.gitNoPromptEnv());
                    if (!unshallow.ok()) {
                        throw new IOException("获取 commit " + sha + " 失败: " + unshallow.stderr());
                    }
                }
                GitProcessRunner.Result checkout = gitRunner.run(
                    List.of("checkout", sha), cloneDir, GitProcessRunner.gitNoPromptEnv());
                if (!checkout.ok()) {
                    throw new IOException("checkout commit " + sha + " 失败: " + checkout.stderr());
                }
                resolvedSha = sha;
            } else {
                GitProcessRunner.Result checkout = gitRunner.run(
                    List.of("checkout", "HEAD"), cloneDir, GitProcessRunner.gitNoPromptEnv());
                if (!checkout.ok()) {
                    throw new IOException("sparse-checkout 后 checkout 失败: " + checkout.stderr());
                }
                GitProcessRunner.Result revParse = gitRunner.run(
                    List.of("rev-parse", "HEAD"), cloneDir, GitProcessRunner.gitNoPromptEnv());
                if (revParse.ok()) {
                    resolvedSha = revParse.stdout().trim();
                }
            }

            // 路径穿越守卫：解析+校验子目录仍在 cloneDir 内（CC :829）
            String resolvedSubdir = validatePathWithinBase(cloneDir, subdirPath);
            try {
                moveDirectory(Paths.get(resolvedSubdir), Paths.get(targetPath));
            } catch (java.nio.file.NoSuchFileException e) {
                throw new IOException("仓库中未找到子目录 '" + subdirPath + "'（ref: "
                    + (ref == null ? "default" : ref) + "）。请检查路径是否正确（CC :832-840）", e);
            }

            if (log.isDebugEnabled()) {
                log.debug("从 {} 提取子目录 {} 到 {} sha={}（CC installFromGitSubdir :842-847）",
                    GitProcessRunner.redactUrlCredentials(gitUrl), subdirPath, targetPath, resolvedSha);
            }
            return resolvedSha;
        } finally {
            // 临时克隆目录丢弃（CC :848-850）
            deleteRecursively(Paths.get(cloneDir));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部 helpers
    // ════════════════════════════════════════════════════════════════════

    /** git clone · CC gitClone（:534-640）：--depth 1 --recurse-submodules [--branch] [sha 则 --no-checkout]。 */
    private void gitClone(String gitUrl, String targetPath, String ref, String sha) throws IOException {
        List<String> args = new ArrayList<>();
        args.add("clone");
        args.add("--depth");
        args.add("1");
        args.add("--recurse-submodules");
        args.add("--shallow-submodules");
        if (ref != null && !ref.isBlank()) {
            args.add("--branch");
            args.add(ref);
        }
        if (sha != null && !sha.isBlank()) {
            args.add("--no-checkout");
        }
        args.add(gitUrl);
        args.add(targetPath);

        GitProcessRunner.Result cloneResult = gitRunner.run(args, null, GitProcessRunner.gitNoPromptEnv());
        if (!cloneResult.ok()) {
            throw new IOException("克隆仓库失败: " + cloneResult.stderr());
        }

        if (sha != null && !sha.isBlank()) {
            GitProcessRunner.Result fetchSha = gitRunner.run(
                List.of("fetch", "--depth", "1", "origin", sha), targetPath,
                GitProcessRunner.gitNoPromptEnv());
            if (!fetchSha.ok()) {
                GitProcessRunner.Result unshallow = gitRunner.run(
                    List.of("fetch", "--unshallow"), targetPath, GitProcessRunner.gitNoPromptEnv());
                if (!unshallow.ok()) {
                    throw new IOException("获取 commit " + sha + " 失败: " + unshallow.stderr());
                }
            }
            GitProcessRunner.Result checkout = gitRunner.run(
                List.of("checkout", sha), targetPath, GitProcessRunner.gitNoPromptEnv());
            if (!checkout.ok()) {
                throw new IOException("checkout commit " + sha + " 失败: " + checkout.stderr());
            }
        }
    }

    /** resolveGitSubdirUrl · CC :686-693：owner/repo 简写 → HTTPS/SSH；否则原样（validateGitUrl 由调用方校验）。 */
    private static String resolveGitSubdirUrl(String url) {
        if (url.matches("^[a-zA-Z0-9-_.]+/[a-zA-Z0-9-_.]+$")) {
            boolean remote = PluginDirectories.isEnvTruthy(System.getenv("CLAUDE_CODE_REMOTE"));
            return remote ? "https://github.com/" + url + ".git" : "git@github.com:" + url + ".git";
        }
        return url;
    }

    /** 临时缓存名 · CC generateTemporaryCacheNameForPlugin（:873-906）：temp_{prefix}_{ts}_{rand6}。 */
    static String generateTemporaryCacheNameForPlugin(PluginSource source) {
        long timestamp = System.currentTimeMillis();
        String random = java.util.UUID.randomUUID().toString().replaceAll("-", "").substring(0, 6);
        String prefix = switch (source) {
            case Local l -> "local";
            case Npm n -> "npm";
            case GitHub g -> "github";
            case Url u -> "git";
            case GitSubdir gs -> "subdir";
        };
        return "temp_" + prefix + "_" + timestamp + "_" + random;
    }

    /** manifest 读取 · CC :979-1079：.claude-plugin/plugin.json → plugin.json → options.manifest → 默认。 */
    static JsonNode loadManifest(String tempPath, JsonNode provided, String tempName, PluginSource source) throws IOException {
        String manifestPath = Paths.get(tempPath, ".claude-plugin", "plugin.json").toString();
        String legacyPath = Paths.get(tempPath, "plugin.json").toString();

        if (Files.exists(Paths.get(manifestPath))) {
            return parseManifestFile(manifestPath, false);
        }
        if (Files.exists(Paths.get(legacyPath))) {
            return parseManifestFile(legacyPath, true);
        }
        if (provided != null && provided.isObject()) {
            return provided;
        }
        return JSON.createObjectNode()
            .put("name", tempName)
            .put("description", "插件从 " + source + " 缓存");
    }

    private static JsonNode parseManifestFile(String path, boolean legacy) throws IOException {
        String content = Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        try {
            return JSON.readTree(content);
        } catch (IOException e) {
            throw new IOException("插件清单文件损坏（" + path + "）。JSON 解析错误: " + e.getMessage(), e);
        }
    }

    /** calculatePluginVersion · CC pluginVersioning.ts:36-106。 */
    String calculatePluginVersion(String pluginId, PluginSource source, JsonNode manifest,
                                  String installPath, String providedVersion, String gitCommitSha) {
        // 1. manifest.version 最高优先（:45-50）
        if (manifest != null && manifest.path("version").isTextual()) {
            return manifest.path("version").asText();
        }
        // 2. 提供的版本（marketplace entry，:53-58）
        if (providedVersion != null && !providedVersion.isBlank()) {
            return providedVersion;
        }
        // 3. 预解析 git SHA（:61-91）
        if (gitCommitSha != null && !gitCommitSha.isBlank()) {
            String shortSha = gitCommitSha.substring(0, Math.min(12, gitCommitSha.length()));
            if (source instanceof GitSubdir gs) {
                String normPath = gs.subdirPath()
                    .replace("\\", "/")
                    .replaceFirst("^\\./", "")
                    .replaceAll("/+$", "");
                String pathHash = sha256Hex(normPath).substring(0, 8);
                return shortSha + "-" + pathHash;
            }
            return shortSha;
        }
        // 4. 从安装路径取 git SHA（:94-101）
        if (installPath != null) {
            String sha = getGitCommitSha(installPath);
            if (sha != null) {
                return sha.substring(0, Math.min(12, sha.length()));
            }
        }
        // 5. 'unknown'（:103-105）
        return "unknown";
    }

    /** getGitCommitSha · CC installedPluginsManager.ts:1002 getHeadForDir（git rev-parse HEAD）。 */
    String getGitCommitSha(String dirPath) {
        if (gitRunner == null || dirPath == null) {
            return null;
        }
        GitProcessRunner.Result result = gitRunner.run(List.of("rev-parse", "HEAD"), dirPath,
            GitProcessRunner.gitNoPromptEnv());
        if (result.ok() && result.stdout() != null && !result.stdout().isBlank()) {
            return result.stdout().trim();
        }
        return null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 从 entry.source JsonNode 解析 PluginSource · CC PluginSourceSchema（schemas.ts:1062-1161）。 */
    static PluginSource parseSource(JsonNode source) {
        if (source == null || source.isNull()) {
            return new Local("");
        }
        if (source.isTextual()) {
            return new Local(source.asText());
        }
        String kind = source.path("source").asText("");
        switch (kind) {
            case "npm":
                return new Npm(source.path("package").asText(""),
                    str(source.get("version")), str(source.get("registry")));
            case "github":
                return new GitHub(source.path("repo").asText(""),
                    str(source.get("ref")), str(source.get("sha")));
            case "url":
                return new Url(source.path("url").asText(""),
                    str(source.get("ref")), str(source.get("sha")));
            case "git-subdir":
                return new GitSubdir(source.path("url").asText(""),
                    source.path("path").asText(""), str(source.get("ref")), str(source.get("sha")));
            case "pip":
                throw new IllegalArgumentException("Python 包插件暂不支持（CC :959-960）");
            default:
                throw new IllegalArgumentException("不支持的插件源类型: " + kind);
        }
    }

    private static String str(JsonNode node) {
        return (node != null && node.isTextual()) ? node.asText() : null;
    }

    /** isLocalPluginSource · CC schemas.ts:1221：string 相对路径 = 本地源。 */
    static boolean isLocalSource(JsonNode source) {
        return source != null && source.isTextual();
    }

    /**
     * MPL9-UNIFY：PluginDependencyResolver 实例方法结果 → PluginInstaller 安装流 flat record。
     * 双实现统一后仅 PluginDependencyResolver.resolveDependencyClosure 一处实现，本方法是薄适配层。
     */
    private static ResolutionResult toInstallerResult(PluginDependencyResolver.ResolutionResult r) {
        if (r.ok()) {
            return ResolutionResult.ok(((PluginDependencyResolver.ResolutionResult.Resolved) r).closure());
        }
        PluginDependencyResolver.ResolutionResult.Failed f = (PluginDependencyResolver.ResolutionResult.Failed) r;
        // sealed Failed 的 cross-marketplace 用 dependency 字段、not-found 用 missing 字段；
        // 安装流 flat record 统一走 missing（formatResolutionError :909 分支共用）。
        String missing = f.dependency() != null ? f.dependency() : f.missing();
        return ResolutionResult.fail(f.reason(), missing, f.requiredBy(), f.chain());
    }

    /** formatResolutionError · CC pluginInstallationHelpers.ts:304-327。 */
    static String formatResolutionError(ResolutionResult r) {
        switch (r.reason()) {
            case "cycle":
                return "依赖环: " + String.join(" → ", r.chain());
            case "cross-marketplace": {
                String depMkt = PluginIdentifier.parse(r.missing()).marketplace();
                return "依赖 \"" + r.missing() + "\"（required by " + r.requiredBy()
                    + "）在 " + (depMkt != null ? "marketplace \"" + depMkt + "\"" : "其他 marketplace")
                    + "，不在 allowlist — 跨 marketplace 依赖默认阻断。请先手动安装它。";
            }
            case "not-found": {
                String depMkt = PluginIdentifier.parse(r.missing()).marketplace();
                return depMkt != null
                    ? "依赖 \"" + r.missing() + "\"（required by " + r.requiredBy()
                        + "）未找到。是否已添加 \"" + depMkt + "\" marketplace?"
                    : "依赖 \"" + r.missing() + "\"（required by " + r.requiredBy() + "）未在任何已配置 marketplace 找到";
            }
            default:
                return "依赖解析失败: " + r.reason();
        }
    }

    /** formatDependencyCountSuffix · CC dependencyResolver.ts:291-298：" (+ N dependencies)"。 */
    static String formatDependencyCountSuffix(List<String> installedDeps) {
        if (installedDeps.isEmpty()) {
            return "";
        }
        int n = installedDeps.size();
        return " (+ " + n + (n == 1 ? " dependency" : " dependencies") + ")";
    }

    /** isPluginBlockedByPolicy · CC pluginPolicy.ts:17（policySettings.enabledPlugins[id]===false）。 */
    boolean isPluginBlockedByPolicy(String pluginId) {
        return policyGate != null && policyGate.test(pluginId);
    }

    /** 当前已 enabled 的 pluginId 集合（Java 映射 InstalledPluginsManager.list 中 enabled=true 的 name）。 */
    private Set<String> getEnabledPluginIds() {
        Set<String> ids = new LinkedHashSet<>();
        if (installedPluginsManager != null) {
            for (InstalledPluginsManager.InstalledRecord rec : installedPluginsManager.list()) {
                if (rec.enabled()) {
                    ids.add(rec.name());
                }
            }
        }
        return ids;
    }

    private void markEnabled(String pluginName) {
        if (installedPluginsManager == null) {
            return;
        }
        InstalledPluginsManager.InstalledRecord existing = installedPluginsManager.get(pluginName);
        if (existing == null) {
            installedPluginsManager.install(pluginName, "unknown", "marketplace");
        } else if (!existing.enabled()) {
            installedPluginsManager.setEnabled(pluginName, true);
        }
    }

    private static String pluginIdName(String pluginId) {
        return PluginIdentifier.parse(pluginId).name();
    }

    // ── 文件系统 helpers ─────────────────────────────────────────────────

    /** 递归复制目录（对齐 CC copyDir）。 */
    static void copyDirectory(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) {
            throw new IOException("源不存在: " + src);
        }
        Files.createDirectories(dst);
        try (Stream<Path> stream = Files.walk(src)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 递归删除（对齐 CC rm -rf force）。 */
    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("递归删除失败 {}: {}", path, e.getMessage());
            }
        }
    }

    /** 目录移动（rename；跨文件系统失败 → 复制+删源）。 */
    static void moveDirectory(Path src, Path dst) throws IOException {
        try {
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileSystemException e) {
            // AtomicMoveNotSupportedException 是 FileSystemException 子类 → 单 catch 覆盖两者（CC rename 跨 fs 回退复制）
            copyDirectory(src, dst);
            deleteRecursively(src);
        }
    }

    /**
     * 路径穿越守卫 · CC pathValidation（validatePathWithinBase）：解析+校验目标路径仍在 base 内。
     */
    static String validatePathWithinBase(String base, String target) {
        if (base == null || base.isBlank()) {
            throw new IllegalArgumentException("base 路径为空");
        }
        Path basePath = Paths.get(base).toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(target).normalize();
        if (!targetPath.startsWith(basePath)) {
            throw new IllegalArgumentException("路径越界: " + target + " 不在 base " + base + " 内");
        }
        return targetPath.toString();
    }

    // ── ProcessBuilder npm runner ────────────────────────────────────────

    /** 默认 npm runner：ProcessBuilder 直跑 npm（win32 解析 npm.cmd）。 */
    static final class ProcessBuilderNpmRunner implements NpmRunner {
        @Override
        public GitProcessRunner.Result exec(List<String> args, String cwd, long timeoutMs) {
            String npmExe = resolveNpmExecutable();
            List<String> full = new ArrayList<>(args.size() + 1);
            full.add(npmExe);
            full.addAll(args);
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(full);
                if (cwd != null && !cwd.isBlank()) {
                    pb.directory(new java.io.File(cwd));
                }
                pb.environment().put("GIT_TERMINAL_PROMPT", "0");
                final Process proc = pb.start();
                p = proc;
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    return new GitProcessRunner.Result(-1, out, err,
                        "npm 命令超时 (" + timeoutMs + "ms)");
                }
                return new GitProcessRunner.Result(proc.exitValue(), out, err, null);
            } catch (IOException e) {
                return new GitProcessRunner.Result(-1, "", "", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (p != null) {
                    p.destroyForcibly();
                }
                return new GitProcessRunner.Result(-1, "", "", "Interrupted: " + e.getMessage());
            }
        }

        private static String resolveNpmExecutable() {
            boolean win = java.io.File.separatorChar == '\\';
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                String[] names = win ? new String[] {"npm.cmd", "npm.exe", "npm"} : new String[] {"npm"};
                for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    if (dir.isBlank()) {
                        continue;
                    }
                    for (String name : names) {
                        Path cand = Paths.get(dir).resolve(name);
                        if (Files.isRegularFile(cand)) {
                            return cand.toString();
                        }
                    }
                }
            }
            return win ? "npm.cmd" : "npm";
        }
    }
}
