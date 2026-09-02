package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.plugin.PluginInstaller.CacheResult;
import com.nexusai.application.agent.plugin.PluginInstaller.CoreResult;
import com.nexusai.application.agent.plugin.PluginInstaller.GitHub;
import com.nexusai.application.agent.plugin.PluginInstaller.GitSubdir;
import com.nexusai.application.agent.plugin.PluginInstaller.Local;
import com.nexusai.application.agent.plugin.PluginInstaller.Npm;
import com.nexusai.application.agent.plugin.PluginInstaller.Url;
import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Session MPL4 · 安装层（installPluginOp/cachePlugin 5源/cacheAndRegisterPlugin/installResolvedPlugin）。
 *
 * <p>验证意图（规则九）：CC 的安装契约是 ①cachePlugin 5 源分派各自落临时目录（local 复制删 .git /
 * npm install / git clone / git-subdir sparse）②git clone 失败 → tempPath 清理 + 中文失败消息 + 磁盘无残留
 * ③安装后 cache/marketplace/plugin/version 目录含合法 plugin.json ④注册表持久化
 * version/installedAt/installPath/gitCommitSha 四字段 ⑤marketplace 未找到 → success=false；scope 非法 → 抛
 * ⑥依赖闭包全部 enabled + 物化；org policy 阻断 → blocked-by-policy。
 *
 * <p>全部 git/npm 命令经注入 mock，无真实网络（对齐 MPL2/MPL3 测试纪律）。
 */
@DisplayName("[MPL4] 安装层（cachePlugin 5源/installPluginOp/cacheAndRegisterPlugin）对齐 CC")
class PluginInstallerTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOW = "2026-08-07T00:00:00.000Z";

    /** git 可执行标记（mock executor 构造函数注入，便于断言 gitExecutable 透传）。 */
    private InstalledPluginsFileStore fileStore;
    private InstalledPluginsManager installedManager;

    @BeforeEach
    void setUp() {
        PluginDirectories.setPluginCacheDirOverride(tempDir.toString());
        PluginDirectories.setPluginSeedDirOverride(null);
        fileStore = new InstalledPluginsFileStore();
        installedManager = new InstalledPluginsManager();
        installedManager.setFileStore(fileStore);
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

    /**
     * 物化型 git executor · clone 在目标路径写入 plugin.json；git-subdir 在 clone 目标写子目录；
     * rev-parse 返回固定 sha。failClone=true 模拟 clone 失败。
     */
    static final class MaterializingGitExecutor implements GitProcessRunner.Executor {
        final List<String> recorded = new ArrayList<>();
        boolean failClone;
        boolean failSparse;
        /** 上次 clone 的目标路径（git-subdir 场景在其下建子目录）。 */
        Path lastCloneTarget;
        String sha = "0123456789abcdef0123456789abcdef01234567";

        @Override
        public GitProcessRunner.Result exec(List<String> args, String cwd, Map<String, String> env,
                                            long timeoutMs) {
            String joined = String.join(" ", args);
            recorded.add(joined);
            if (args.contains("--version")) {
                return new GitProcessRunner.Result(0, "git version 2.30.0", "", null);
            }
            if (args.contains("clone")) {
                if (failClone) {
                    return new GitProcessRunner.Result(128, "", "fatal: could not read Username for 'https://github.com'", null);
                }
                String target = args.get(args.size() - 1);
                lastCloneTarget = Paths.get(target);
                try {
                    Files.createDirectories(lastCloneTarget.resolve(".claude-plugin"));
                    Files.writeString(lastCloneTarget.resolve(".claude-plugin").resolve("plugin.json"),
                        "{\"name\":\"cloned-plugin\",\"version\":\"1.2.0\"}", StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return new GitProcessRunner.Result(0, "", "", null);
            }
            if (args.contains("sparse-checkout")) {
                if (failSparse) {
                    return new GitProcessRunner.Result(128, "", "git sparse-checkout set failed", null);
                }
                // 在 clone 目标的子目录（arg 末位）建 plugin.json
                String subdir = args.get(args.size() - 1);
                try {
                    Path sub = lastCloneTarget.resolve(subdir);
                    Files.createDirectories(sub.resolve(".claude-plugin"));
                    Files.writeString(sub.resolve(".claude-plugin").resolve("plugin.json"),
                        "{\"name\":\"subdir-plugin\",\"version\":\"1.3.0\"}", StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return new GitProcessRunner.Result(0, "", "", null);
            }
            if (args.contains("rev-parse")) {
                return new GitProcessRunner.Result(0, sha, "", null);
            }
            return new GitProcessRunner.Result(0, "", "", null);
        }
    }

    /** npm runner mock · 在 npm-cache/node_modules/{pkg} 建 plugin.json（模拟 npm install 产物）。 */
    static final class MaterializingNpmRunner implements PluginInstaller.NpmRunner {
        final List<String> recorded = new ArrayList<>();
        boolean fail;

        @Override
        public GitProcessRunner.Result exec(List<String> args, String cwd, long timeoutMs) {
            recorded.add(String.join(" ", args));
            if (fail) {
                return new GitProcessRunner.Result(1, "", "npm error", null);
            }
            try {
                Path npmCache = Paths.get(PluginDirectories.getPluginsDirectory(), "npm-cache");
                // args: [install, <packageSpec>, --prefix, <cachePath>, ...] · 包名 = spec 去掉 @version 后缀（scoped 包 @scope/pkg 除外）
                String spec = args.get(1);
                String pkg = (spec.startsWith("@") && spec.contains("@") && spec.indexOf("@", 1) > 0)
                    ? spec.substring(0, spec.indexOf("@", 1))
                    : spec.contains("@") ? spec.substring(0, spec.indexOf("@")) : spec;
                Path pkgDir = npmCache.resolve("node_modules").resolve(pkg);
                Files.createDirectories(pkgDir.resolve(".claude-plugin"));
                Files.writeString(pkgDir.resolve(".claude-plugin").resolve("plugin.json"),
                    "{\"name\":\"npm-plugin\",\"version\":\"1.4.0\"}", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return new GitProcessRunner.Result(0, "", "", null);
        }
    }

    /** 构造带 mock git/npm 的 installer。 */
    private PluginInstaller installer(GitProcessRunner.Executor gitEx, PluginInstaller.NpmRunner npmEx) {
        PluginInstaller pi = new PluginInstaller();
        pi.setGitRunner(new GitProcessRunner(gitEx, "git"));
        if (npmEx != null) {
            pi.setNpmRunner(npmEx);
        }
        pi.setInstalledPluginsManager(installedManager);
        pi.setCwdProvider(v -> tempDir.toString());
        return pi;
    }

    private Path cacheDir() {
        return Paths.get(PluginDirectories.getPluginsDirectory(), "cache");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1：5 源分派（cachePlugin 落临时目录）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("local 源：复制到临时目录 + 删 .git（CC installFromLocal :856-868）")
    void cachePlugin_local_copiesAndRemovesGit() throws IOException {
        Path src = tempDir.resolve("local-plugin");
        Files.createDirectories(src.resolve(".claude-plugin"));
        Files.createDirectories(src.resolve(".git"));
        Files.writeString(src.resolve(".claude-plugin").resolve("plugin.json"),
            "{\"name\":\"my-local\",\"version\":\"0.1.0\"}", StandardCharsets.UTF_8);

        PluginInstaller pi = installer(new MaterializingGitExecutor(), null);
        CacheResult r = pi.cachePlugin(new Local(src.toString()), null);

        assertThat(Files.exists(Paths.get(r.path(), ".claude-plugin", "plugin.json"))).isTrue();
        assertThat(Files.exists(Paths.get(r.path(), ".git"))).isFalse();
        assertThat(r.manifest().path("name").asText()).isEqualTo("my-local");
        assertThat(Paths.get(r.path()).getParent()).isEqualTo(cacheDir());
    }

    @Test
    @DisplayName("npm 源：npm install --prefix 后复制 node_modules/pkg（CC installFromNpm :492-524）")
    void cachePlugin_npm_installsAndCopies() throws IOException {
        MaterializingNpmRunner npm = new MaterializingNpmRunner();
        PluginInstaller pi = installer(new MaterializingGitExecutor(), npm);
        CacheResult r = pi.cachePlugin(new Npm("some-pkg", "1.4.0", null), null);

        assertThat(npm.recorded).anyMatch(a -> a.contains("install") && a.contains("some-pkg@1.4.0"));
        assertThat(Files.exists(Paths.get(r.path(), ".claude-plugin", "plugin.json"))).isTrue();
        assertThat(r.manifest().path("name").asText()).isEqualTo("npm-plugin");
    }

    @Test
    @DisplayName("github 源：owner/repo 校验 + git clone（CC installFromGitHub :662-678）")
    void cachePlugin_github_clones() throws IOException {
        MaterializingGitExecutor git = new MaterializingGitExecutor();
        PluginInstaller pi = installer(git, null);
        CacheResult r = pi.cachePlugin(new GitHub("owner/repo", "main", null), null);

        assertThat(git.recorded).anyMatch(a -> a.contains("clone"));
        assertThat(Files.exists(Paths.get(r.path(), ".claude-plugin", "plugin.json"))).isTrue();
        assertThat(r.manifest().path("name").asText()).isEqualTo("cloned-plugin");
    }

    @Test
    @DisplayName("url 源：git clone（CC installFromGit :645-657）")
    void cachePlugin_url_clones() throws IOException {
        MaterializingGitExecutor git = new MaterializingGitExecutor();
        PluginInstaller pi = installer(git, null);
        CacheResult r = pi.cachePlugin(new Url("https://github.com/x/y.git", "v1", null), null);

        assertThat(git.recorded).anyMatch(a -> a.contains("clone"));
        assertThat(Files.exists(Paths.get(r.path(), ".claude-plugin", "plugin.json"))).isTrue();
    }

    @Test
    @DisplayName("git-subdir 源：partial clone + sparse-checkout + 提取子目录 + 捕获 SHA（CC :718-851）")
    void cachePlugin_gitSubdir_sparseExtractsAndCapturesSha() throws IOException {
        MaterializingGitExecutor git = new MaterializingGitExecutor();
        PluginInstaller pi = installer(git, null);
        CacheResult r = pi.cachePlugin(new GitSubdir("owner/repo", "tools/claude-plugin", "main", null), null);

        assertThat(git.recorded).anyMatch(a -> a.contains("sparse-checkout") && a.contains("--cone"));
        assertThat(git.recorded).anyMatch(a -> a.contains("rev-parse"));
        assertThat(Files.exists(Paths.get(r.path(), ".claude-plugin", "plugin.json"))).isTrue();
        assertThat(r.gitCommitSha()).isEqualTo(git.sha);
        // 临时 clone 目录已清理（磁盘无 .clone 残留）
        assertThat(Files.exists(Paths.get(r.path() + ".clone"))).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2：git clone 失败 → tempPath 清理 + 中文失败消息 + 磁盘无残留
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("git clone 失败：临时目录清理 + 无残留（CC :965-977）")
    void cachePlugin_cloneFailure_cleansTempPath() {
        MaterializingGitExecutor git = new MaterializingGitExecutor();
        git.failClone = true;
        PluginInstaller pi = installer(git, null);

        assertThatThrownBy(() -> pi.cachePlugin(new Url("https://github.com/x/y.git", null, null), null))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("克隆仓库失败");

        // 磁盘无残留：cache 目录下无 temp_ 前缀临时目录
        try (var s = Files.list(cacheDir())) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("temp_")).toList()).isEmpty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3 + 4：versioned 缓存 + 注册表四字段
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("cacheAndRegisterPlugin：cache/marketplace/plugin/version 含合法 plugin.json（CC :165-202）")
    void cacheAndRegister_createsVersionedCache() throws Exception {
        Path src = tempDir.resolve("local-plugin");
        Files.createDirectories(src.resolve(".claude-plugin"));
        Files.writeString(src.resolve(".claude-plugin").resolve("plugin.json"),
            "{\"name\":\"my-plugin\",\"version\":\"1.0.0\"}", StandardCharsets.UTF_8);

        PluginInstaller pi = installer(new MaterializingGitExecutor(), null);
        JsonNode entrySource = JSON.readTree("\"./local-plugin\"");
        PluginMarketplace.Entry entry = new PluginMarketplace.Entry("my-plugin", entrySource,
            "dev", List.of("tag"), true, null, List.of());

        String finalPath = pi.cacheAndRegisterPlugin("my-plugin@mkt", entry, "user",
            null, src.toString());

        // versionedPath: cache/mkt/my-plugin/1.0.0
        assertThat(finalPath).isEqualTo(
            Paths.get(cacheDir().toString(), "mkt", "my-plugin", "1.0.0").toString());
        assertThat(Files.exists(Paths.get(finalPath, ".claude-plugin", "plugin.json"))).isTrue();
    }

    @Test
    @DisplayName("addInstalledPlugin：注册表持久化 version/installedAt/installPath/gitCommitSha 四字段")
    void cacheAndRegister_persistsRegistryFourFields() throws Exception {
        Path src = tempDir.resolve("local-plugin");
        Files.createDirectories(src.resolve(".claude-plugin"));
        Files.writeString(src.resolve(".claude-plugin").resolve("plugin.json"),
            "{\"name\":\"my-plugin\",\"version\":\"1.0.0\"}", StandardCharsets.UTF_8);

        PluginInstaller pi = installer(new MaterializingGitExecutor(), null);
        JsonNode entrySource = JSON.readTree("\"./local-plugin\"");
        PluginMarketplace.Entry entry = new PluginMarketplace.Entry("my-plugin", entrySource,
            null, null, null, null, List.of());

        MaterializingGitExecutor git = new MaterializingGitExecutor();
        pi.setGitRunner(new GitProcessRunner(git, "git"));
        pi.cacheAndRegisterPlugin("my-plugin@mkt", entry, "user", null, src.toString());

        InstalledPluginsManager.InstalledRecord rec = installedManager.get("my-plugin");
        assertThat(rec).isNotNull();
        assertThat(rec.version()).isEqualTo("1.0.0");
        assertThat(rec.installPath()).isNotBlank();
        // pathForGitSha = 本地源路径（CC :151）；mock rev-parse 返回 sha → 注册表携带 gitCommitSha
        assertThat(rec.gitCommitSha()).isEqualTo(git.sha);
        assertThat(rec.installedAt()).isPositive();

        // 文件层持久化（V2 文件可读回）
        InstalledPluginsFileStore.InstalledPluginsFileV2 disk = fileStore.loadFromDisk();
        assertThat(disk.plugins().keySet()).contains("my-plugin");
        assertThat(disk.plugins().get("my-plugin")).hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 5：installPluginOp marketplace 定位 + not-found + scope 非法抛
    // ════════════════════════════════════════════════════════════════════

    private MarketplaceManager marketplaceWith(String name, String installLocation,
                                               String pluginsJson) throws IOException {
        MarketplaceManager m = new MarketplaceManager();
        Path loc = Paths.get(installLocation);
        Files.createDirectories(loc.resolve(".claude-plugin"));
        Files.writeString(loc.resolve(".claude-plugin").resolve("marketplace.json"),
            "{\"name\":\"mock\",\"owner\":\"mock\",\"plugins\":" + pluginsJson + "}", StandardCharsets.UTF_8);
        Map<String, KnownMarketplace> config = new LinkedHashMap<>();
        config.put(name, new KnownMarketplace(
            new MarketplaceSource.Directory(installLocation), installLocation, NOW, false));
        m.saveKnownMarketplacesConfig(config);
        return m;
    }

    @Test
    @DisplayName("installPluginOp：marketplace 找到 → success + pluginId/scope（CC :321-418）")
    void installPluginOp_found_returnsSuccess() throws Exception {
        Path localPlugin = tempDir.resolve("mkt").resolve("p1");
        Files.createDirectories(localPlugin.resolve(".claude-plugin"));
        Files.writeString(localPlugin.resolve(".claude-plugin").resolve("plugin.json"),
            "{\"name\":\"p1\",\"version\":\"2.0.0\"}", StandardCharsets.UTF_8);

        MarketplaceManager m = marketplaceWith("mkt", tempDir.resolve("mkt").toString(),
            "[{\"name\":\"p1\",\"source\":\"./p1\"}]");

        PluginInstaller pi = installer(new MaterializingGitExecutor(), null);
        PluginOperations ops = new PluginOperations();
        ops.wireInstallation(m, pi);

        PluginOperations.InstallResult result = ops.installPlugin("p1@mkt", "user");

        assertThat(result.success()).isTrue();
        assertThat(result.pluginId()).isEqualTo("p1@mkt");
        assertThat(result.pluginName()).isEqualTo("p1");
        assertThat(result.scope()).isEqualTo("user");
        assertThat(result.message()).contains("Successfully installed");
        // 注册表 + 版本化缓存都已生成
        assertThat(installedManager.get("p1")).isNotNull();
        assertThat(Files.exists(Paths.get(cacheDir().toString(), "mkt", "p1", "2.0.0"))).isTrue();
    }

    @Test
    @DisplayName("installPluginOp：marketplace 未找到 → success=false not-found（CC :361-369）")
    void installPluginOp_notFound_returnsFailure() throws Exception {
        MarketplaceManager m = marketplaceWith("mkt", tempDir.resolve("mkt").toString(), "[]");
        PluginInstaller pi = installer(new MaterializingGitExecutor(), null);
        PluginOperations ops = new PluginOperations();
        ops.wireInstallation(m, pi);

        PluginOperations.InstallResult result = ops.installPlugin("nope@mkt", "user");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("not found");
    }

    @Test
    @DisplayName("installPluginOp：scope 非法 → 抛异常（CC assertInstallableScope :90-98）")
    void installPluginOp_invalidScope_throws() throws Exception {
        MarketplaceManager m = marketplaceWith("mkt", tempDir.resolve("mkt").toString(), "[]");
        PluginInstaller pi = installer(new MaterializingGitExecutor(), null);
        PluginOperations ops = new PluginOperations();
        ops.wireInstallation(m, pi);

        assertThatThrownBy(() -> ops.installPlugin("p1@mkt", "bogus"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("user, project, local");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 6：依赖闭包全部 enabled + 物化；org policy 阻断 → blocked-by-policy
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("installResolvedPlugin：依赖闭包全部 enabled + 物化（CC :348-481）")
    void installResolvedPlugin_closureAllEnabledAndMaterialized() throws Exception {
        // marketplace mkt: root 依赖 dep（同 marketplace）
        Path mkt = tempDir.resolve("mkt");
        Files.createDirectories(mkt.resolve("root").resolve(".claude-plugin"));
        Files.writeString(mkt.resolve("root").resolve(".claude-plugin").resolve("plugin.json"),
            "{\"name\":\"root\",\"version\":\"1.0.0\"}", StandardCharsets.UTF_8);
        Files.createDirectories(mkt.resolve("dep").resolve(".claude-plugin"));
        Files.writeString(mkt.resolve("dep").resolve(".claude-plugin").resolve("plugin.json"),
            "{\"name\":\"dep\",\"version\":\"0.5.0\"}", StandardCharsets.UTF_8);

        MarketplaceManager m = marketplaceWith("mkt", mkt.toString(),
            "[{\"name\":\"root\",\"source\":\"./root\",\"dependencies\":[\"dep\"]},"
                + "{\"name\":\"dep\",\"source\":\"./dep\"}]");

        PluginInstaller pi = installer(new MaterializingGitExecutor(), null);
        pi.setMarketplaceManager(m);
        PluginMarketplace.LookupResult rootInfo = m.getPluginById("root@mkt");

        CoreResult result = pi.installResolvedPlugin("root@mkt", rootInfo.entry(), "user", mkt.toString());

        assertThat(result.ok()).isTrue();
        // CC 闭包是后序 DFS：先 walk 依赖再 push 自身 → dep 在前、root 在后（dependencyResolver.ts:144-152）
        assertThat(result.closure()).containsExactly("dep@mkt", "root@mkt");
        // 依赖闭包全部物化（缓存目录 + 注册表）
        assertThat(installedManager.get("root")).isNotNull();
        assertThat(installedManager.get("dep")).isNotNull();
        assertThat(installedManager.get("root").enabled()).isTrue();
        assertThat(installedManager.get("dep").enabled()).isTrue();
        assertThat(Files.exists(Paths.get(cacheDir().toString(), "mkt", "dep", "0.5.0"))).isTrue();
    }

    @Test
    @DisplayName("installResolvedPlugin：org policy 阻断 → blocked-by-policy（CC pluginPolicy.ts:17）")
    void installResolvedPlugin_policyBlocked_returnsBlocked() throws Exception {
        Path mkt = tempDir.resolve("mkt");
        Files.createDirectories(mkt.resolve("root").resolve(".claude-plugin"));
        Files.writeString(mkt.resolve("root").resolve(".claude-plugin").resolve("plugin.json"),
            "{\"name\":\"root\",\"version\":\"1.0.0\"}", StandardCharsets.UTF_8);
        MarketplaceManager m = marketplaceWith("mkt", mkt.toString(),
            "[{\"name\":\"root\",\"source\":\"./root\"}]");

        PluginInstaller pi = installer(new MaterializingGitExecutor(), null);
        pi.setMarketplaceManager(m);
        pi.setPolicyGate(id -> id.equals("root@mkt"));
        PluginMarketplace.LookupResult rootInfo = m.getPluginById("root@mkt");

        CoreResult result = pi.installResolvedPlugin("root@mkt", rootInfo.entry(), "user", mkt.toString());

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo("blocked-by-policy");
    }

    @Test
    @DisplayName("installResolvedPlugin：inline 根 + 裸依赖 null marketplace → 跨市场阻断（MPL9 返工 F2 同步，CC dependencyResolver.ts:121-132）")
    void installResolvedPlugin_inlineRootNullMarketplaceDepBlockedAsCrossMarketplace() throws Exception {
        // 场景：root= a@inline（--plugin-dir 合成 marketplace，非 null），裸依赖 "b" 无 @ → idMarketplace=null。
        // CC 判式 null !== 'inline' 且 !(null && allowed.has()) → cross-marketplace 阻断；null 不再放行。
        // 生产说明：marketplace 安装流的 root 恒为 name@marketplace（非 --plugin-dir），本分支实际不可达，
        // 但判式语义与 CC / PluginDependencyResolver 对齐，避免 inline 场景 null-id 漏拦（F2 已同步 walk:858）。
        JsonNode entrySource = JSON.readTree("\"./a\"");
        PluginMarketplace.Entry entry = new PluginMarketplace.Entry("a", entrySource, null, null, null, null, List.of("b"));

        PluginInstaller pi = installer(new MaterializingGitExecutor(), null);
        // 不注入 marketplaceManager → allowedCross 为空集；root inline 依赖裸名 b → 阻断为 cross-marketplace
        CoreResult result = pi.installResolvedPlugin("a@inline", entry, "user", tempDir.resolve("mkt").toString());

        assertThat(result.ok()).isFalse();
        assertThat(result.reason()).isEqualTo("resolution-failed");
        assertThat(result.blockedDependency()).isEqualTo("b");
        assertThat(result.message())
            .contains("b")
            .contains("a@inline")
            .contains("跨 marketplace 依赖默认阻断");
    }
}
