package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.PluginBlocklist.Installation;
import com.nexusai.application.agent.plugin.PluginMarketplace.Entry;
import com.nexusai.application.agent.plugin.PluginMarketplace.Marketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MPL9] PluginBlocklist · 对齐 CC utils/plugins/pluginBlocklist.ts（127 行）。
 *
 * <p>WHY（规则九）：CC 的下架检测只在 {@code forceRemoveDeletedPlugins} 门槛内执行，跳过 managed-only
 * 与已 flagged 插件，自动卸载 user/project/local scope 后写 flagged。旧 Java 无此层 —— 上架列表漂移
 * 时损坏插件会带着 stale manifest 加载。本测试锁定门槛 + 跳过 + 卸载/flag 五组契约。
 */
@DisplayName("[MPL9] PluginBlocklist 对齐 CC pluginBlocklist.ts")
class PluginBlocklistTest {

    @TempDir
    Path tempDir;

    private static final String MKT = "claude-plugins-official";
    private static final String SUFFIX = "@" + MKT;

    /** 构造 marketplace（forceRemoveDeletedPlugins 显式可控）。 */
    private static Marketplace marketplace(boolean forceRemove, String... pluginNames) {
        List<Entry> entries = new ArrayList<>();
        for (String n : pluginNames) {
            entries.add(new Entry(n, null, null, null, null, null, null));
        }
        return new Marketplace("marketplace-mock", "anthropics", entries, forceRemove, null, List.of());
    }

    private static Map<String, List<Installation>> installed(String pluginId, String... scopes) {
        Map<String, List<Installation>> map = new LinkedHashMap<>();
        List<Installation> list = new ArrayList<>();
        for (String s : scopes) {
            list.add(new Installation(s, null));
        }
        map.put(pluginId, list);
        return map;
    }

    private static Map<String, KnownMarketplace> known(String name) {
        Map<String, KnownMarketplace> map = new LinkedHashMap<>();
        map.put(name, new KnownMarketplace(
            new MarketplaceSource.Github("anthropics/claude-plugins-official", null, null, null),
            "/tmp/mkt", "2026-08-07T00:00:00Z", null));
        return map;
    }

    // ── detectDelistedPlugins ────────────────────────────────────────────

    @Test
    @DisplayName("detectDelistedPlugins：manifest 已删除的插件 → delisted")
    void detectDelistedWhenPluginMissingFromManifest() {
        Map<String, List<Installation>> installed =
            installed("format" + SUFFIX, "user");
        Marketplace mkt = marketplace(true, "other-plugin");

        List<String> delisted = new PluginBlocklist().detectDelistedPlugins(installed, mkt, MKT);

        assertThat(delisted).containsExactly("format" + SUFFIX);
    }

    @Test
    @DisplayName("detectDelistedPlugins：manifest 仍存在的插件 → 不 delisted；其他 marketplace 前缀被忽略")
    void detectKeepsPresentAndIgnoresOtherMarketplace() {
        Map<String, List<Installation>> installed = new LinkedHashMap<>();
        installed.put("format" + SUFFIX, List.of(new Installation("user", null)));
        installed.put("other@epic", List.of(new Installation("user", null))); // 前缀不匹配
        Marketplace mkt = marketplace(true, "format");

        List<String> delisted = new PluginBlocklist().detectDelistedPlugins(installed, mkt, MKT);

        assertThat(delisted).isEmpty();
    }

    // ── detectAndUninstallDelistedPlugins ────────────────────────────────

    @Test
    @DisplayName("delist：forceRemoveDeletedPlugins=false 的 marketplace 不检测（门槛）")
    void delistSkipsMarketplaceWithoutForceRemove() {
        List<String> uninstalled = new ArrayList<>();
        PluginBlocklist blocklist = new PluginBlocklist(
            () -> installed("format" + SUFFIX, "user"),
            name -> marketplace(false, "other-plugin"),
            new PluginFlagging(tempDir),
            (id, scope) -> uninstalled.add(id + ":" + scope));

        List<String> newlyFlagged = blocklist.detectAndUninstallDelistedPlugins(
            installed("format" + SUFFIX, "user"), known(MKT));

        assertThat(newlyFlagged).isEmpty();
        assertThat(uninstalled).isEmpty();
    }

    @Test
    @DisplayName("delist：user-scope delisted 插件被卸载 + 写 flagged")
    void delistUninstallsUserScopeAndFlags() {
        List<String> uninstalled = new ArrayList<>();
        PluginBlocklist blocklist = new PluginBlocklist(
            () -> installed("format" + SUFFIX, "user"),
            name -> marketplace(true, "other-plugin"),
            new PluginFlagging(tempDir),
            (id, scope) -> uninstalled.add(id + ":" + scope));

        List<String> newlyFlagged = blocklist.detectAndUninstallDelistedPlugins(
            installed("format" + SUFFIX, "user"), known(MKT));

        assertThat(newlyFlagged).containsExactly("format" + SUFFIX);
        assertThat(uninstalled).containsExactly("format" + SUFFIX + ":user");
        assertThat(blocklist.getFlagging().getFlaggedPlugins()).containsKey("format" + SUFFIX);
    }

    @Test
    @DisplayName("delist：managed-only 安装被跳过（enterprise admin 处理）")
    void delistSkipsManagedOnlyInstall() {
        List<String> uninstalled = new ArrayList<>();
        PluginBlocklist blocklist = new PluginBlocklist(
            () -> installed("format" + SUFFIX, "managed"),
            name -> marketplace(true, "other-plugin"),
            new PluginFlagging(tempDir),
            (id, scope) -> uninstalled.add(id + ":" + scope));

        List<String> newlyFlagged = blocklist.detectAndUninstallDelistedPlugins(
            installed("format" + SUFFIX, "managed"), known(MKT));

        assertThat(newlyFlagged).isEmpty();
        assertThat(uninstalled).isEmpty();
    }

    @Test
    @DisplayName("delist：已 flagged 插件不重复卸载（幂等）")
    void delistSkipsAlreadyFlagged() {
        List<String> uninstalled = new ArrayList<>();
        PluginFlagging flagging = new PluginFlagging(tempDir);
        flagging.addFlaggedPlugin("format" + SUFFIX); // 预置 flagged
        PluginBlocklist blocklist = new PluginBlocklist(
            () -> installed("format" + SUFFIX, "user"),
            name -> marketplace(true, "other-plugin"),
            flagging,
            (id, scope) -> uninstalled.add(id + ":" + scope));

        List<String> newlyFlagged = blocklist.detectAndUninstallDelistedPlugins(
            installed("format" + SUFFIX, "user"), known(MKT));

        assertThat(newlyFlagged).isEmpty();
        assertThat(uninstalled).isEmpty();
    }

    @Test
    @DisplayName("delist：installPath 全部不存在 → 不写 flag（Java 侧增强，非 CC 语义）")
    void delistSkipsFlagWhenInstallPathMissing() throws Exception {
        // WHY（规则九）：09-open-decisions OPD-MPL9-N1N2-installPath 裁决 —— CC detectDelistedPlugins
        // （pluginBlocklist.ts:34-53）grep -n 无任何路径存在性检查，本校验是 Java 侧增强。delisted 插件
        // 的版本化安装目录（schemas.ts:1524-1526 installPath）已从磁盘消失时，flag 语义"插件曾被移除
        // 需留意"无实质意义，写了反而误导 /plugins 展示。本用例锁定：全部 installPath 缺失 → 不 flag。
        Path missing = tempDir.resolve("never-created");
        Map<String, List<Installation>> installed = new LinkedHashMap<>();
        installed.put("format" + SUFFIX,
            List.of(new Installation("user", null, missing.toString())));
        PluginBlocklist blocklist = new PluginBlocklist(
            () -> installed("format" + SUFFIX, "user"),
            name -> marketplace(true, "other-plugin"),
            new PluginFlagging(tempDir),
            (id, scope) -> {
            });

        List<String> newlyFlagged = blocklist.detectAndUninstallDelistedPlugins(
            installed, known(MKT));

        assertThat(newlyFlagged).isEmpty();
        assertThat(blocklist.getFlagging().getFlaggedPlugins()).doesNotContainKey("format" + SUFFIX);
    }

    @Test
    @DisplayName("delist：installPath 存在 → 正常写 flag")
    void delistFlagsWhenInstallPathExists() throws Exception {
        // 对照用例：installPath 指向真实存在的目录 → 维持既有 flag 语义（installPath 存在才判定）。
        Path existing = Files.createDirectories(tempDir.resolve("present"));
        Map<String, List<Installation>> installed = new LinkedHashMap<>();
        installed.put("format" + SUFFIX,
            List.of(new Installation("user", null, existing.toString())));

        PluginBlocklist blocklist = new PluginBlocklist(
            () -> installed("format" + SUFFIX, "user"),
            name -> marketplace(true, "other-plugin"),
            new PluginFlagging(tempDir),
            (id, scope) -> {
            });

        List<String> newlyFlagged = blocklist.detectAndUninstallDelistedPlugins(
            installed, known(MKT));

        assertThat(newlyFlagged).containsExactly("format" + SUFFIX);
        assertThat(blocklist.getFlagging().getFlaggedPlugins()).containsKey("format" + SUFFIX);
    }

    @Test
    @DisplayName("delist：单 marketplace 加载失败仅 warn，不中断其他 marketplace")
    void delistContinuesOnMarketplaceLoadFailure() {
        List<String> uninstalled = new ArrayList<>();
        Map<String, KnownMarketplace> knownMap = known(MKT);
        knownMap.put("other-mkt", new KnownMarketplace(
            new MarketplaceSource.Github("x/y", null, null, null),
            "/tmp/mkt2", "2026-08-07T00:00:00Z", null));
        PluginBlocklist blocklist = new PluginBlocklist(
            () -> installed("format" + SUFFIX, "user"),
            name -> {
                if ("other-mkt".equals(name)) {
                    throw new RuntimeException("marketplace unavailable");
                }
                return marketplace(true, "other-plugin");
            },
            new PluginFlagging(tempDir),
            (id, scope) -> uninstalled.add(id + ":" + scope));

        List<String> newlyFlagged = blocklist.detectAndUninstallDelistedPlugins(
            installed("format" + SUFFIX, "user"), knownMap);

        assertThat(newlyFlagged).containsExactly("format" + SUFFIX);
        assertThat(uninstalled).containsExactly("format" + SUFFIX + ":user");
    }
}
