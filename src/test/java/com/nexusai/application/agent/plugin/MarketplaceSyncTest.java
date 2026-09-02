package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [MPL2] marketplace 同步层 · 对齐 CC marketplaceManager.ts:528-1177 / :2296-2575.
 *
 * <p>WHY（规则九）：CC 的 git 同步契约是 ①clone args 顺序精确（-c core.sshCommand /
 * --depth 1 / sparse 与全量分支互斥参数）②gitPull 对不存在目录返非零 → cacheMarketplaceFromGit
 * rm+reclone 兜底 ③sparse 物化目标路径、非目标缺席 ④refreshMarketplace 缓存目录 + installLocation
 * 越界守卫 + lastUpdated 持久化 + settings 跳过/seed 抛错。全部 git 命令经注入 Executor mock，
 * 无真实网络。
 */
@DisplayName("[MPL2] marketplace 同步层（gitClone/gitPull/sparse/refresh）对齐 CC")
class MarketplaceSyncTest {

    @TempDir
    Path tempDir;

    private static final String NOW = "2026-08-07T00:00:00.000Z";

    @BeforeEach
    void setUp() {
        PluginDirectories.setPluginCacheDirOverride(tempDir.toString());
        PluginDirectories.setPluginSeedDirOverride(null);
    }

    @AfterEach
    void tearDown() {
        PluginDirectories.setPluginCacheDirOverride(null);
        PluginDirectories.setPluginSeedDirOverride(null);
        PluginDirectories.setUseCoworkPluginsOverride(null);
    }

    // ════════════════════════════════════════════════════════════════════
    // mock executors
    // ════════════════════════════════════════════════════════════════════

    /** 记录型 executor · queue 非空时逐条弹出，否则 fallback。 */
    static final class RecordingExecutor implements GitProcessRunner.Executor {
        final List<List<String>> recorded = new ArrayList<>();
        final List<GitProcessRunner.Result> queue = new ArrayList<>();
        GitProcessRunner.Result fallback = new GitProcessRunner.Result(0, "", "", null);

        @Override
        public GitProcessRunner.Result exec(List<String> args, String cwd, Map<String, String> env,
                                            long timeoutMs) {
            recorded.add(List.copyOf(args));
            if (!queue.isEmpty()) {
                return queue.remove(0);
            }
            return fallback;
        }

        List<String> argsJoined() {
            return recorded.stream().map(a -> String.join(" ", a)).toList();
        }
    }

    /** 模拟 git clone 成功并物化 marketplace.json · 便于刷新后 readCachedMarketplace 校验通过。 */
    static final class MaterializingExecutor implements GitProcessRunner.Executor {
        final Path cacheDir;
        final List<String> recorded = new ArrayList<>();
        /** clone 失败子串（URL 含该子串 → 返 code 128）。 */
        final List<String> failUrls = new ArrayList<>();
        boolean failAllClones;

        MaterializingExecutor(Path cacheDir) {
            this.cacheDir = cacheDir;
        }

        @Override
        public GitProcessRunner.Result exec(List<String> args, String cwd, Map<String, String> env,
                                            long timeoutMs) {
            String joined = String.join(" ", args);
            recorded.add(joined);
            // reconcile 的 config --get core.sparseCheckout：非仓库 → code 1（CC reconcile 仍返 code 0，随后 pull 失败）
            if (args.contains("--get")) {
                return new GitProcessRunner.Result(1, "", "not a git repository", null);
            }
            // pull：目录非有效仓库 → 非零（CC :1106-1120 触发 rm+reclone 兜底）
            if (joined.contains(" pull ")) {
                return new GitProcessRunner.Result(128, "", "fatal: not a git repository", null);
            }
            if (joined.contains(" clone ")) {
                if (failAllClones || failUrls.stream().anyMatch(joined::contains)) {
                    return new GitProcessRunner.Result(128, "", "Permission denied (publickey)", null);
                }
                // 模拟 clone 成功：目标路径（最后一个 arg）物化 .claude-plugin/marketplace.json
                Path target = Paths.get(args.get(args.size() - 1));
                try {
                    Files.createDirectories(target.resolve(".claude-plugin"));
                    Files.writeString(target.resolve(".claude-plugin").resolve("marketplace.json"),
                        "{\"name\":\"mock\",\"owner\":\"mock\",\"plugins\":[]}",
                        StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return new GitProcessRunner.Result(0, "", "", null);
            }
            return new GitProcessRunner.Result(0, "", "", null);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. gitClone args 精确断言
    // ════════════════════════════════════════════════════════════════════

    @Test
    void gitClone_full_argsExactOrder() {
        RecordingExecutor ex = new RecordingExecutor();
        MarketplaceSyncService svc = new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false);

        svc.gitClone("https://github.com/o/r.git", "C:/cache/t", null, null);

        assertThat(ex.recorded).hasSize(1);
        // CC :810-832 精确顺序：-c core.sshCommand + clone --depth 1 + 全量 --recurse-submodules --shallow-submodules
        assertThat(ex.recorded.get(0)).containsExactly(
            "git",
            "-c", "core.sshCommand=ssh -o BatchMode=yes -o StrictHostKeyChecking=yes",
            "clone", "--depth", "1",
            "--recurse-submodules", "--shallow-submodules",
            "https://github.com/o/r.git", "C:/cache/t");
    }

    @Test
    void gitClone_sparseWithRef_argsExactOrder_thenSparseSetAndCheckout() {
        RecordingExecutor ex = new RecordingExecutor();
        MarketplaceSyncService svc = new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false);

        svc.gitClone("https://github.com/o/r.git", "C:/cache/t", "main",
            List.of("plugins/foo", "plugins/bar"));

        // CC :810-823 sparse：--filter=blob:none --no-checkout（替代 --recurse-submodules）；:828-830 --branch
        assertThat(ex.recorded.get(0)).containsExactly(
            "git",
            "-c", "core.sshCommand=ssh -o BatchMode=yes -o StrictHostKeyChecking=yes",
            "clone", "--depth", "1",
            "--filter=blob:none", "--no-checkout",
            "--branch", "main",
            "https://github.com/o/r.git", "C:/cache/t");
        // CC :861-895 sparse-checkout set --cone -- paths + checkout HEAD（cwd=targetPath）
        assertThat(ex.recorded).hasSize(3);
        assertThat(ex.recorded.get(1)).containsExactly(
            "git", "sparse-checkout", "set", "--cone", "--", "plugins/foo", "plugins/bar");
        assertThat(ex.recorded.get(2)).containsExactly("git", "checkout", "HEAD");
    }

    @Test
    void gitClone_redactsCredentialsInLoggedArgsAndPassesCredentialedUrlToGit() {
        RecordingExecutor ex = new RecordingExecutor();
        MarketplaceSyncService svc = new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false);

        svc.gitClone("https://user:secret@github.com/o/r.git", "C:/cache/t", null, null);

        // url 原样传给 git（带凭据），日志层才脱敏（本测试仅断言 args 透传）
        assertThat(ex.recorded.get(0)).contains("https://user:secret@github.com/o/r.git");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. gitPull
    // ════════════════════════════════════════════════════════════════════

    @Test
    void gitPull_withRef_runsFetchCheckoutPull() {
        RecordingExecutor ex = new RecordingExecutor();
        MarketplaceSyncService svc = new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false);

        svc.gitPull("C:/cache/t", "main", true, null);

        // CC :539-569 fetch origin ref → checkout ref → pull origin ref；disableCredentialHelper → -c credential.helper=
        assertThat(ex.argsJoined()).containsExactly(
            "git -c credential.helper= fetch origin main",
            "git -c credential.helper= checkout main",
            "git -c credential.helper= pull origin main");
    }

    @Test
    void gitPull_noRef_runsPullOriginHead() {
        RecordingExecutor ex = new RecordingExecutor();
        MarketplaceSyncService svc = new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false);

        svc.gitPull("C:/cache/t", null, false, null);

        assertThat(ex.argsJoined()).containsExactly("git pull origin HEAD");
    }

    @Test
    void gitPull_withSparsePaths_skipsSubmoduleUpdate() {
        RecordingExecutor ex = new RecordingExecutor();
        MarketplaceSyncService svc = new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false);

        svc.gitPull("C:/cache/t", null, false, List.of("plugins/a"));

        // sparse 跳过 gitSubmoduleUpdate（CC :615）→ 仅 pull origin HEAD
        assertThat(ex.argsJoined()).containsExactly("git pull origin HEAD");
    }

    @Test
    void gitPull_error_enhancedWithGuidance() {
        RecordingExecutor ex = new RecordingExecutor();
        ex.fallback = new GitProcessRunner.Result(128, "", "Permission denied (publickey)", null);
        MarketplaceSyncService svc = new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false);

        MarketplaceSyncService.GitResult r = svc.gitPull("C:/cache/t", null, false, null);

        assertThat(r.code()).isEqualTo(128);
        assertThat(r.stderr()).contains("SSH authentication failed"); // CC :686-695
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. reconcileSparseCheckout
    // ════════════════════════════════════════════════════════════════════

    @Test
    void reconcile_sparsePaths_runsSparseSet() {
        RecordingExecutor ex = new RecordingExecutor();
        MarketplaceSyncService svc = new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false);

        svc.reconcileSparseCheckout("C:/cache/t", List.of("plugins/a"));

        assertThat(ex.argsJoined()).containsExactly(
            "git sparse-checkout set --cone -- plugins/a");
    }

    @Test
    void reconcile_sparseToFull_returnsNonZeroSoCallerReclones() {
        RecordingExecutor ex = new RecordingExecutor();
        ex.fallback = new GitProcessRunner.Result(0, "true", "", null); // core.sparseCheckout=true
        MarketplaceSyncService svc = new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false);

        MarketplaceSyncService.GitResult r = svc.reconcileSparseCheckout("C:/cache/t", null);

        // CC :1053-1057 全量→sparse 配置残留 → code 1 触发 rm+reclone（避免 partial clone disable 整仓 fetch）
        assertThat(r.code()).isEqualTo(1);
        assertThat(r.stderr()).contains("re-cloning");
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 集成：本地 file:// 裸仓库（真实 git，无网络）
    // ════════════════════════════════════════════════════════════════════

    private boolean gitAvailable() {
        GitProcessRunner.Result r = new GitProcessRunner().run(List.of("--version"), null, null, 8000);
        return r.ok();
    }

    private void runGit(Path cwd, List<String> args) {
        List<String> full = new ArrayList<>();
        full.add("-c");
        full.add("user.name=test");
        full.add("-c");
        full.add("user.email=test@example.com");
        full.addAll(args);
        GitProcessRunner.Result r = new GitProcessRunner().run(full, cwd.toString(),
            GitProcessRunner.gitNoPromptEnv(), 30_000);
        assertThat(r.ok()).as("git %s 失败: %s", args, r.stderr()).isTrue();
    }

    /** 建一个含 marketplace.json 的裸仓库（源仓 commit → git clone --bare）。 */
    private Path makeBareMarketplaceRepo() throws IOException {
        Path src = tempDir.resolve("src-repo");
        Files.createDirectories(src.resolve(".claude-plugin"));
        Files.writeString(src.resolve(".claude-plugin/marketplace.json"),
            "{\"name\":\"it\",\"owner\":\"it\",\"plugins\":[]}", StandardCharsets.UTF_8);
        Files.createDirectories(src.resolve("plugins/foo"));
        Files.writeString(src.resolve("plugins/foo/plugin.json"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(src.resolve("plugins/bar"));
        Files.writeString(src.resolve("plugins/bar/other.txt"), "non-target", StandardCharsets.UTF_8);
        runGit(src, List.of("init"));
        runGit(src, List.of("add", "."));
        runGit(src, List.of("commit", "-m", "init"));
        Path bare = tempDir.resolve("bare.git");
        runGit(tempDir, List.of("clone", "--bare", src.toString(), bare.toString()));
        return bare;
    }

    @Test
    void gitClone_sparse_localBareRepo_materializesTargetOnly() throws IOException {
        Assumptions.assumeTrue(gitAvailable(), "git 不可用，跳过集成测试");
        Path bare = makeBareMarketplaceRepo();

        Path target = tempDir.resolve("sparse-checkout");
        MarketplaceSyncService svc = new MarketplaceSyncService(new GitProcessRunner(), null);

        MarketplaceSyncService.GitResult r =
            svc.gitClone(bare.toUri().toString(), target.toString(), null, List.of("plugins/foo"));

        assertThat(r.ok()).as(r.stderr()).isTrue();
        // 目标已物化、非目标缺席（验收 #3）。
        // 注意 cone 模式根级文件恒物化、非 cone 子目录缺席，故非目标放 plugins/bar
        assertThat(target.resolve("plugins/foo/plugin.json")).isRegularFile();
        assertThat(target.resolve("plugins/bar/other.txt")).doesNotExist();
    }

    @Test
    void cacheMarketplaceFromGit_pullFailsOnMissingRepo_thenRmAndReclone() throws IOException {
        Assumptions.assumeTrue(gitAvailable(), "git 不可用，跳过集成测试");
        Path bare = makeBareMarketplaceRepo();

        // cachePath 是真实仓库但无 remote → reconcile 成功、gitPull 非零 → rm+reclone 兜底（验收 #4）
        Path cachePath = tempDir.resolve("stale-cache");
        Files.createDirectories(cachePath);
        runGit(cachePath, List.of("init"));

        MarketplaceSyncService svc = new MarketplaceSyncService(new GitProcessRunner(), null);
        svc.cacheMarketplaceFromGit(bare.toUri().toString(), cachePath.toString(), null, null, false);

        // 最终可读 marketplace.json（reclone 已物化）
        assertThat(cachePath.resolve(".claude-plugin/marketplace.json")).isRegularFile();
        assertThat(cachePath.resolve(".git")).isDirectory();
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. refreshMarketplace
    // ════════════════════════════════════════════════════════════════════

    private MarketplaceManager managerWith(MarketplaceSyncService svc) {
        MarketplaceManager m = new MarketplaceManager();
        m.setSyncServiceForTest(svc);
        return m;
    }

    private void seedConfig(Map<String, KnownMarketplace> config) throws IOException {
        new MarketplaceManager().saveKnownMarketplacesConfig(config);
    }

    private Map<String, KnownMarketplace> configWith(
        String name, MarketplaceSource source, String installLocation) {
        Map<String, KnownMarketplace> config = new LinkedHashMap<>();
        config.put(name, new KnownMarketplace(source, installLocation, NOW, false));
        return config;
    }

    private static KnownMarketplace reload(String name) throws IOException {
        return new MarketplaceManager().loadKnownMarketplacesConfig().get(name);
    }

    @Test
    void refreshMarketplace_settingsSource_skipped_noLastUpdatedChange() throws IOException {
        Map<String, KnownMarketplace> config =
            configWith("inline", new MarketplaceSource.Settings("inline"), tempDir.resolve("x").toString());
        seedConfig(config);

        RecordingExecutor ex = new RecordingExecutor();
        MarketplaceManager m = managerWith(new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false));

        m.refreshMarketplace("inline");

        // settings 源无上游：不执行任何 git 命令、不写 lastUpdated（CC :2385-2390）
        assertThat(ex.recorded).isEmpty();
        assertThat(reload("inline").lastUpdated()).isEqualTo(NOW);
    }

    @Test
    void refreshMarketplace_seedManaged_throwsWithGuidance() throws IOException {
        Path seedDir = tempDir.resolve("seed");
        PluginDirectories.setPluginSeedDirOverride(seedDir.toString());
        Map<String, KnownMarketplace> config = configWith(
            "seedmkt", new MarketplaceSource.Github("o/r", null, null, null),
            seedDir.resolve("marketplaces/seedmkt").toString());
        seedConfig(config);

        MarketplaceManager m = managerWith(new MarketplaceSyncService(
            new GitProcessRunner(new RecordingExecutor(), "git"), () -> false));

        assertThatThrownBy(() -> m.refreshMarketplace("seedmkt"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("seed-managed")
            .hasMessageContaining("seed image");
    }

    @Test
    void refreshMarketplace_notFound_throwsWithAvailableList() throws IOException {
        seedConfig(new LinkedHashMap<>());

        MarketplaceManager m = managerWith(new MarketplaceSyncService(
            new GitProcessRunner(new RecordingExecutor(), "git"), () -> false));

        assertThatThrownBy(() -> m.refreshMarketplace("ghost"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Marketplace 'ghost' not found");
    }

    @Test
    void refreshMarketplace_corruptedInstallLocationOutsideCacheDir_throws() throws IOException {
        Path outside = tempDir.resolve("outside");
        Map<String, KnownMarketplace> config = configWith(
            "badloc", new MarketplaceSource.Github("o/r", null, null, null),
            outside.resolve("project").toString());
        seedConfig(config);

        MarketplaceManager m = managerWith(new MarketplaceSyncService(
            new GitProcessRunner(new RecordingExecutor(), "git"), () -> false));

        // 越界守卫（CC :2414-2426）：拒绝在用户项目目录跑 git ops / fs.rm
        assertThatThrownBy(() -> m.refreshMarketplace("badloc"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("corrupted installLocation");
    }

    @Test
    void refreshMarketplace_gitSource_success_updatesLastUpdatedAndPersists() throws IOException {
        String cachePath = Paths.get(getCacheDir(), "o-r").toString();
        Map<String, KnownMarketplace> config = configWith(
            "repo", new MarketplaceSource.Github("o/r", null, null, null), cachePath);
        seedConfig(config);

        MaterializingExecutor ex = new MaterializingExecutor(Paths.get(getCacheDir()));
        MarketplaceManager m = managerWith(new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false));

        m.refreshMarketplace("repo");

        // 成功路径写回 lastUpdated + 持久化（CC :2563-2565）
        KnownMarketplace after = reload("repo");
        assertThat(after.lastUpdated()).isNotEqualTo(NOW);
        assertThat(after.installLocation()).isEqualTo(cachePath);
        // 更新后 marketplace.json 校验通过（CC :2526-2545）
        assertThat(Paths.get(cachePath, ".claude-plugin", "marketplace.json")).isRegularFile();
    }

    @Test
    void refreshMarketplace_github_ccrMode_usesHttpsOnly() throws IOException {
        String cachePath = Paths.get(getCacheDir(), "o-r").toString();
        Map<String, KnownMarketplace> config = configWith(
            "repo", new MarketplaceSource.Github("o/r", null, null, null), cachePath);
        seedConfig(config);

        MaterializingExecutor ex = new MaterializingExecutor(Paths.get(getCacheDir()));
        ex.failAllClones = true; // 验证协议选择，不关心 clone 结果
        MarketplaceManager m = managerWith(new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> true));
        m.setRemoteModeCheckForTest(() -> true); // CLAUDE_CODE_REMOTE truthy

        assertThatThrownBy(() -> m.refreshMarketplace("repo"))
            .isInstanceOf(IOException.class);

        // CCR 恒 HTTPS，绝不尝试 SSH（CC :2476-2485）
        assertThat(ex.recorded.stream().anyMatch(s -> s.contains("git@github.com"))).isFalse();
        assertThat(ex.recorded.stream().anyMatch(s -> s.contains("https://github.com/o/r.git"))).isTrue();
    }

    @Test
    void refreshMarketplace_github_sshConfigured_sshThenHttpsFallback() throws IOException {
        String cachePath = Paths.get(getCacheDir(), "o-r").toString();
        Map<String, KnownMarketplace> config = configWith(
            "repo", new MarketplaceSource.Github("o/r", null, null, null), cachePath);
        seedConfig(config);

        MaterializingExecutor ex = new MaterializingExecutor(Paths.get(getCacheDir()));
        ex.failUrls.add("git@github.com"); // SSH clone 失败 → HTTPS 回退
        MarketplaceManager m = managerWith(new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> true)); // SSH 探测返回 configured

        m.refreshMarketplace("repo");

        // SSH 优先、失败回退 HTTPS（CC :2487-2513）
        int sshIdx = indexOfContaining(ex.recorded, "git@github.com:o/r.git");
        int httpsIdx = indexOfContaining(ex.recorded, "https://github.com/o/r.git");
        assertThat(sshIdx).isGreaterThanOrEqualTo(0);
        assertThat(httpsIdx).isGreaterThan(sshIdx);
        assertThat(Paths.get(cachePath, ".claude-plugin", "marketplace.json")).isRegularFile();
    }

    @Test
    void refreshMarketplace_github_sshNotConfigured_httpsFirstThenSshFallback() throws IOException {
        String cachePath = Paths.get(getCacheDir(), "o-r").toString();
        Map<String, KnownMarketplace> config = configWith(
            "repo", new MarketplaceSource.Github("o/r", null, null, null), cachePath);
        seedConfig(config);

        MaterializingExecutor ex = new MaterializingExecutor(Paths.get(getCacheDir()));
        ex.failUrls.add("https://github.com/o/r.git"); // HTTPS clone 失败 → SSH 回退
        MarketplaceManager m = managerWith(new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false)); // SSH 探测返回未配置

        m.refreshMarketplace("repo");

        int httpsIdx = indexOfContaining(ex.recorded, "https://github.com/o/r.git");
        int sshIdx = indexOfContaining(ex.recorded, "git@github.com:o/r.git");
        assertThat(httpsIdx).isGreaterThanOrEqualTo(0);
        assertThat(sshIdx).isGreaterThan(httpsIdx);
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. refreshAllMarketplaces
    // ════════════════════════════════════════════════════════════════════

    @Test
    void refreshAllMarketplaces_skipsSeedAndSettings_refreshesGit_saves() throws IOException {
        Path seedDir = tempDir.resolve("seed");
        PluginDirectories.setPluginSeedDirOverride(seedDir.toString());
        Map<String, KnownMarketplace> config = new LinkedHashMap<>();
        config.put("seedmkt", new KnownMarketplace(
            new MarketplaceSource.Github("seed/o", null, null, null),
            seedDir.resolve("marketplaces/seedmkt").toString(), NOW, false));
        config.put("inline", new KnownMarketplace(
            new MarketplaceSource.Settings("inline"), tempDir.resolve("x").toString(), NOW, false));
        config.put("repogit", new KnownMarketplace(
            new MarketplaceSource.Git("https://example.com/repo.git", null, null, null),
            Paths.get(getCacheDir(), "old").toString(), NOW, false));
        seedConfig(config);

        MaterializingExecutor ex = new MaterializingExecutor(Paths.get(getCacheDir()));
        MarketplaceManager m = managerWith(new MarketplaceSyncService(
            new GitProcessRunner(ex, "git"), () -> false));

        m.refreshAllMarketplaces();

        KnownMarketplace after = reload("repogit");
        // git 源已刷新：lastUpdated 更新 + installLocation 指向缓存目录（CC :2336-2339）
        assertThat(after.lastUpdated()).isNotEqualTo(NOW);
        assertThat(after.installLocation()).startsWith(getCacheDir());
        // seed / settings 源跳过：不被触及
        assertThat(reload("seedmkt").lastUpdated()).isEqualTo(NOW);
        assertThat(reload("inline").lastUpdated()).isEqualTo(NOW);
        assertThat(ex.recorded.stream().anyMatch(s -> s.contains("seedmkt"))).isFalse();
    }

    private static int indexOfContaining(List<String> list, String sub) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).contains(sub)) {
                return i;
            }
        }
        return -1;
    }

    private String getCacheDir() {
        return Paths.get(tempDir.toString(), "marketplaces").toString();
    }
}
