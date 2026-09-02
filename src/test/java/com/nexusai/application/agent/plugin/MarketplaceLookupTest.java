package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.PluginIdentifier.Parsed;
import com.nexusai.application.agent.plugin.PluginMarketplace.Entry;
import com.nexusai.application.agent.plugin.PluginMarketplace.Marketplace;
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

/**
 * [MPL3] marketplace 查找层 · 对齐 CC marketplaceManager.ts:2058-2280（readCachedMarketplace /
 * getMarketplaceCacheOnly / getMarketplace memoize / getPluginByIdCacheOnly / getPluginById）+
 * pluginIdentifier.ts:51-57。
 *
 * <p>WHY（规则九）：CC 的查找契约是 ①cache-only 未知 name/缺失缓存/损坏 → null（启动路径绝不能
 * 阻塞网络）②getMarketplace 内存 memoize，refresh 删 key 后必须重拉 ③getPluginById 快路径缓存命中
 * 零 git，miss 才拉源 ④name@marketplace 解析只认首个 @。旧 Java 无查找层（仅 L1 配置/L2 同步），
 * 本测试锁定 L3 五组契约 + 磁盘 config 即时重读。
 */
@DisplayName("[MPL3] marketplace 查找层（getMarketplace/getPluginById + memoize）对齐 CC")
class MarketplaceLookupTest {

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

    private MarketplaceManager manager() {
        return new MarketplaceManager();
    }

    /** 注入 git 执行器（真实 git 命令零调用）。 */
    private MarketplaceManager managerWith(GitProcessRunner.Executor ex) {
        MarketplaceManager m = new MarketplaceManager();
        m.setSyncServiceForTest(new MarketplaceSyncService(new GitProcessRunner(ex, "git"), () -> false));
        return m;
    }

    private void seedConfig(Map<String, KnownMarketplace> config) throws IOException {
        manager().saveKnownMarketplacesConfig(config);
    }

    private static Map<String, KnownMarketplace> configWith(
        String name, MarketplaceSource source, String installLocation) {
        Map<String, KnownMarketplace> config = new LinkedHashMap<>();
        config.put(name, new KnownMarketplace(source, installLocation, NOW, false));
        return config;
    }

    private void writeMarketplace(Path installLocation, String pluginsJson) throws IOException {
        Files.createDirectories(installLocation.resolve(".claude-plugin"));
        Files.writeString(installLocation.resolve(".claude-plugin").resolve("marketplace.json"),
            "{\"name\":\"mock\",\"owner\":\"mock\",\"plugins\":" + pluginsJson + "}",
            StandardCharsets.UTF_8);
    }

    /** 纯记录 executor · 任何 git 调用即失败（用于证明快路径零 git）。 */
    static final class RecordingExecutor implements GitProcessRunner.Executor {
        final List<String> recorded = new ArrayList<>();

        @Override
        public GitProcessRunner.Result exec(List<String> args, String cwd, Map<String, String> env,
                                            long timeoutMs) {
            recorded.add(String.join(" ", args));
            return new GitProcessRunner.Result(128, "", "should not be called", null);
        }
    }

    /**
     * 版本化物化 executor · 每次 clone 写出递增插件名（p1/p2/...），用于区分"重拉"与"读旧缓存"。
     * 对齐 MarketplaceSyncTest.MaterializingExecutor 的 clone 物化行为。
     */
    static final class VersionedMaterializingExecutor implements GitProcessRunner.Executor {
        final List<String> recorded = new ArrayList<>();
        int cloneCount;

        @Override
        public GitProcessRunner.Result exec(List<String> args, String cwd, Map<String, String> env,
                                            long timeoutMs) {
            String joined = String.join(" ", args);
            recorded.add(joined);
            if (args.contains("--get")) {
                return new GitProcessRunner.Result(1, "", "not a git repository", null);
            }
            if (joined.contains(" pull ")) {
                return new GitProcessRunner.Result(128, "", "fatal: not a git repository", null);
            }
            if (joined.contains(" clone ")) {
                cloneCount++;
                Path target = Paths.get(args.get(args.size() - 1));
                try {
                    Files.createDirectories(target.resolve(".claude-plugin"));
                    Files.writeString(target.resolve(".claude-plugin").resolve("marketplace.json"),
                        "{\"name\":\"mock\",\"owner\":\"mock\",\"plugins\":[{\"name\":\"p"
                            + cloneCount + "\",\"version\":\"1.0.0\"}]}",
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
    // 1. parsePluginIdentifier（pluginIdentifier.ts:51-57）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("parsePluginIdentifier：无@→name/null；多@取首；@后空→空串（pluginIdentifier.ts:51-57）")
    void identifier_parseVariants() {
        // WHY: 只认首个 @ —— marketplace 名不允许含 @；@ 后空 / name 空在调用方判 null。
        assertThat(PluginIdentifier.parse("plugin@market")).isEqualTo(new Parsed("plugin", "market"));
        assertThat(PluginIdentifier.parse("plugin@market@place")).isEqualTo(new Parsed("plugin", "market"));
        assertThat(PluginIdentifier.parse("plugin@")).isEqualTo(new Parsed("plugin", ""));
        assertThat(PluginIdentifier.parse("@market")).isEqualTo(new Parsed("", "market"));
        assertThat(PluginIdentifier.parse("plugin")).isEqualTo(new Parsed("plugin", null));
        assertThat(PluginIdentifier.parse(null)).isEqualTo(new Parsed("", null));
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. getMarketplaceCacheOnly（marketplaceManager.ts:2081-2107）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getMarketplaceCacheOnly：未知 name → null（CC :2092-2093）")
    void cacheOnly_unknownName_null() throws IOException {
        seedConfig(configWith("mkt",
            new MarketplaceSource.Directory(tempDir.resolve("m").toString()),
            tempDir.resolve("m").toString()));
        assertThat(manager().getMarketplaceCacheOnly("nope")).isNull();
    }

    @Test
    @DisplayName("getMarketplaceCacheOnly：缓存缺失（ENOENT）→ null（CC :2098-2099）")
    void cacheOnly_missingCache_null() throws IOException {
        Path loc = tempDir.resolve("mkt");
        Files.createDirectories(loc);
        seedConfig(configWith("mkt", new MarketplaceSource.Directory(loc.toString()), loc.toString()));
        assertThat(manager().getMarketplaceCacheOnly("mkt")).isNull();
    }

    @Test
    @DisplayName("getMarketplaceCacheOnly：缓存损坏（JSON 非法）→ null（CC :2097-2106 全量 catch）")
    void cacheOnly_corruptedCache_null() throws IOException {
        Path loc = tempDir.resolve("mkt");
        Files.createDirectories(loc.resolve(".claude-plugin"));
        Files.writeString(loc.resolve(".claude-plugin").resolve("marketplace.json"), "not-json{");
        seedConfig(configWith("mkt", new MarketplaceSource.Directory(loc.toString()), loc.toString()));
        assertThat(manager().getMarketplaceCacheOnly("mkt")).isNull();
    }

    @Test
    @DisplayName("getMarketplaceCacheOnly：缓存存在 → 返回完整 marketplace（:2096）")
    void cacheOnly_hit_returnsMarketplace() throws IOException {
        Path loc = tempDir.resolve("mkt");
        writeMarketplace(loc, "[{\"name\":\"p1\",\"version\":\"1.0.0\"},{\"name\":\"p2\"}]");
        seedConfig(configWith("mkt", new MarketplaceSource.Directory(loc.toString()), loc.toString()));

        Marketplace m = manager().getMarketplaceCacheOnly("mkt");
        assertThat(m).isNotNull();
        assertThat(m.name()).isEqualTo("mock");
        assertThat(m.plugins()).extracting(Entry::name).containsExactly("p1", "p2");
    }

    @Test
    @DisplayName("readCachedMarketplace：git 源读嵌套 .claude-plugin/marketplace.json（:2065-2067）")
    void cacheOnly_gitSource_nestedPath() throws IOException {
        Path loc = tempDir.resolve("gitmkt");
        writeMarketplace(loc, "[{\"name\":\"gp1\"}]");
        seedConfig(configWith("gitmkt",
            new MarketplaceSource.Github("o/r", null, null, null), loc.toString()));
        assertThat(manager().getMarketplaceCacheOnly("gitmkt").plugins())
            .extracting(Entry::name).containsExactly("gp1");
    }

    @Test
    @DisplayName("readCachedMarketplace：file 源 installLocation 即直文件（ENOENT/ENOTDIR 回退 :2068-2073）")
    void cacheOnly_fileSource_directFile() throws IOException {
        Path file = tempDir.resolve("local-mkt.json");
        Files.writeString(file, "{\"name\":\"local\",\"owner\":\"o\",\"plugins\":[{\"name\":\"lp1\"}]}");
        seedConfig(configWith("local", new MarketplaceSource.File(file.toString()), file.toString()));
        assertThat(manager().getMarketplaceCacheOnly("local").plugins())
            .extracting(Entry::name).containsExactly("lp1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. getMarketplace memoize（marketplaceManager.ts:2122-2178 / :2380）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getMarketplace：二次调用命中 memoize 不重拉；refresh 删 key 后重拉（CC :2122/:2380）")
    void getMarketplace_memoizeThenRefreshInvalidates() throws Exception {
        VersionedMaterializingExecutor ex = new VersionedMaterializingExecutor();
        MarketplaceManager m = managerWith(ex);
        Path cacheDir = tempDir.resolve("marketplaces");
        seedConfig(configWith("ghmkt",
            new MarketplaceSource.Github("acme/plugins", null, null, null),
            cacheDir.resolve("acme-plugins").toString()));

        // 第一次：miss → 拉源（clone 1 次，写出 p1）
        Marketplace first = m.getMarketplace("ghmkt");
        assertThat(first.plugins()).extracting(Entry::name).containsExactly("p1");
        int clonesAfterFirst = ex.cloneCount;
        assertThat(clonesAfterFirst).isEqualTo(1);

        // 第二次：memoize 命中，零新增拉源
        m.getMarketplace("ghmkt");
        assertThat(ex.cloneCount).isEqualTo(clonesAfterFirst);

        // refresh：清 memoize key + 重拉（写出 p2），随后 getMarketplace 读新缓存
        m.refreshMarketplace("ghmkt");
        Marketplace afterRefresh = m.getMarketplace("ghmkt");
        assertThat(ex.cloneCount).isGreaterThan(clonesAfterFirst);
        assertThat(afterRefresh.plugins()).extracting(Entry::name).containsExactly("p2");
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. getPluginById / getPluginByIdCacheOnly（marketplaceManager.ts:2188-2280）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getPluginById：缓存命中快路径，零 git 调用（CC :2242-2246）")
    void pluginById_cacheHit_fastPath_noGit() throws Exception {
        RecordingExecutor ex = new RecordingExecutor();
        MarketplaceManager m = managerWith(ex);
        Path loc = tempDir.resolve("mkt");
        writeMarketplace(loc, "[{\"name\":\"my-plugin\",\"version\":\"2.0.0\"}]");
        seedConfig(configWith("mkt", new MarketplaceSource.Directory(loc.toString()), loc.toString()));

        PluginMarketplace.LookupResult r = m.getPluginById("my-plugin@mkt");
        assertThat(r).isNotNull();
        assertThat(r.entry().name()).isEqualTo("my-plugin");
        assertThat(r.marketplaceInstallLocation()).isEqualTo(loc.toString());
        assertThat(ex.recorded).isEmpty(); // 快路径绝不触发 git
    }

    @Test
    @DisplayName("getPluginByIdCacheOnly：find 未命中 → null（:2216-2218）")
    void pluginByIdCacheOnly_notFound_null() throws IOException {
        Path loc = tempDir.resolve("mkt");
        writeMarketplace(loc, "[{\"name\":\"other\"}]");
        seedConfig(configWith("mkt", new MarketplaceSource.Directory(loc.toString()), loc.toString()));
        assertThat(manager().getPluginByIdCacheOnly("ghost@mkt")).isNull();
    }

    @Test
    @DisplayName("getPluginById：cache miss → getMarketplace 拉源 + find（CC :2248-2272）")
    void pluginById_miss_fallsBackToSource() throws Exception {
        VersionedMaterializingExecutor ex = new VersionedMaterializingExecutor();
        MarketplaceManager m = managerWith(ex);
        Path cacheDir = tempDir.resolve("marketplaces");
        seedConfig(configWith("ghmkt",
            new MarketplaceSource.Github("acme/plugins", null, null, null),
            cacheDir.resolve("acme-plugins").toString()));

        PluginMarketplace.LookupResult r = m.getPluginById("p1@ghmkt");
        assertThat(r).isNotNull();
        assertThat(r.entry().name()).isEqualTo("p1");
        assertThat(r.marketplaceInstallLocation()).isEqualTo(cacheDir.resolve("acme-plugins").toString());
        assertThat(ex.cloneCount).isEqualTo(1);
    }

    @Test
    @DisplayName("pluginId 无@/@后空/name空 → null 且零拉源；多@取首可命中（pluginIdentifier.ts:51-57）")
    void pluginById_invalidIdentifier_null_noFetch() throws Exception {
        VersionedMaterializingExecutor ex = new VersionedMaterializingExecutor();
        MarketplaceManager m = managerWith(ex);
        Path cacheDir = tempDir.resolve("marketplaces");
        seedConfig(configWith("ghmkt",
            new MarketplaceSource.Github("acme/plugins", null, null, null),
            cacheDir.resolve("acme-plugins").toString()));

        assertThat(m.getPluginById("p1")).isNull();      // 无 @
        assertThat(m.getPluginById("p1@")).isNull();     // @ 后空
        assertThat(m.getPluginById("@ghmkt")).isNull();  // name 空
        assertThat(ex.cloneCount).isZero();              // 均未触发拉源

        // 多 @ 取首：p1@ghmkt@extra 按 marketplace=ghmkt 解析并命中
        assertThat(m.getPluginById("p1@ghmkt@extra")).isNotNull();
        assertThat(ex.cloneCount).isEqualTo(1);
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. 磁盘 config 即时重读（known_marketplaces.json 唯一配置源）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("磁盘 config 增删后 cache-only 重读反映最新（:2088-2096 每次重读文件）")
    void cacheOnly_readsFreshConfigEachCall() throws Exception {
        MarketplaceManager m = manager();
        Path loc1 = tempDir.resolve("m1");
        writeMarketplace(loc1, "[{\"name\":\"a1\"}]");
        seedConfig(configWith("m1", new MarketplaceSource.Directory(loc1.toString()), loc1.toString()));
        assertThat(m.getMarketplaceCacheOnly("m1")).isNotNull();

        // 另一调用方改盘：删 m1、增 m2
        Path loc2 = tempDir.resolve("m2");
        writeMarketplace(loc2, "[{\"name\":\"a2\"}]");
        Map<String, KnownMarketplace> updated = new LinkedHashMap<>();
        updated.put("m2", new KnownMarketplace(
            new MarketplaceSource.Directory(loc2.toString()), loc2.toString(), NOW, false));
        manager().saveKnownMarketplacesConfig(updated);

        assertThat(m.getMarketplaceCacheOnly("m1")).isNull();     // 删除即时反映
        assertThat(m.getMarketplaceCacheOnly("m2")).isNotNull();  // 新增即时反映
    }
}
