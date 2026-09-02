package com.nexusai.application.agent.plugin;

import com.nexusai.application.agent.plugin.PluginAutoupdate.UpdateOutcome;
import com.nexusai.application.agent.plugin.PluginBlocklist.Installation;
import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [MPL9] PluginAutoupdate · 对齐 CC utils/plugins/pluginAutoupdate.ts（284 行）。
 *
 * <p>WHY（规则九）：CC 的自动更新只刷新 autoUpdate 启用市场、只更新来自这些市场的已装插件
 * （non-inplace 需重启生效），且 pendingNotification 竞态缓存保证 REPL 挂载前完成的更新不丢。
 * 旧 Java 无此层。本测试锁定门槛 + 竞态 + 更新范围三组契约。
 */
@DisplayName("[MPL9] PluginAutoupdate 对齐 CC pluginAutoupdate.ts")
class PluginAutoupdateTest {

    private static final String TS = "2026-08-07T00:00:00Z";

    private static KnownMarketplace km(boolean autoUpdate) {
        return new KnownMarketplace(
            new MarketplaceSource.Github("anthropics/x", null, null, null),
            "/tmp/mkt", TS, autoUpdate);
    }

    private static KnownMarketplace km(Boolean autoUpdate) {
        return new KnownMarketplace(
            new MarketplaceSource.Github("anthropics/x", null, null, null),
            "/tmp/mkt", TS, autoUpdate);
    }

    @Test
    @DisplayName("getAutoUpdateEnabledMarketplaces：仅 autoUpdate=true 的市场")
    void autoUpdateEnabledMarketplacesFiltersFlag() {
        PluginAutoupdate au = new PluginAutoupdate(null, null, null, null,
            () -> Map.of("mkt1", km(true), "mkt2", km(false)));

        assertThat(au.getAutoUpdateEnabledMarketplaces()).containsExactly("mkt1");
    }

    @Test
    @DisplayName("getAutoUpdateEnabledMarketplaces：官方市场 autoUpdate=null 默认入选（isMarketplaceAutoUpdate 默认语义）")
    void officialMarketplaceDefaultsToEnabledWithoutExplicitFlag() {
        // WHY（规则九 · 返工 F1）：CC isMarketplaceAutoUpdate（schemas.ts:48-58）对官方名单内市场
        // （含 claude-plugins-official）无显式 autoUpdate 字段时默认 true（NO_AUTO_UPDATE 名单除外）。
        // MarketplaceReconciler.addMarketplaceSource 写 autoUpdate=null → 若 Java 用
        // Boolean.TRUE.equals(...) 判定，官方市场自动安装后永不进 autoupdate（生产特性空转）。
        PluginAutoupdate au = new PluginAutoupdate(null, null, null, null,
            () -> Map.of(
                "claude-plugins-official", km((Boolean) null),
                "knowledge-work-plugins", km((Boolean) null),
                "some-third-party", km((Boolean) null),
                "disabled-official", km(false)));

        // 官方市场不经显式字段即入选；NO_AUTO_UPDATE 名单与第三方市场默认不入选；显式 false 覆盖默认
        assertThat(au.getAutoUpdateEnabledMarketplaces())
            .containsExactlyInAnyOrder("claude-plugins-official");
    }

    @Test
    @DisplayName("declared 优先层：settings 声明 autoUpdate=false 覆盖 JSON 态 autoUpdate=true")
    void declaredAutoUpdateOverridesJsonState() {
        // WHY（规则九）：CC pluginAutoupdate.ts:90-95 —— "Settings-declared autoUpdate takes precedence
        // over JSON state"。用户经 /plugins 逐市场关闭 autoUpdate 时写入 settings（declared）声明源，
        // 若 Java 只读 known_marketplaces.json（JSON 态 autoUpdate=true），用户关闭即被忽略，
        // 生产特性会继续自动更新该市场 → 用户意图失效。本用例锁定 declared 优先语义。
        MarketplaceConfigStore.DeclaredMarketplace declared = new MarketplaceConfigStore.DeclaredMarketplace(
            new MarketplaceSource.Github("anthropics/x", null, null, null), false, false);
        PluginAutoupdate au = new PluginAutoupdate(null, null, null, null,
            () -> Map.of("mkt1", km(true)),           // JSON 态 autoUpdate=true
            () -> Map.of("mkt1", declared));           // declared 声明 autoUpdate=false → 覆盖

        assertThat(au.getAutoUpdateEnabledMarketplaces()).doesNotContain("mkt1");
    }

    @Test
    @DisplayName("declared 优先层：settings 声明 autoUpdate=true 覆盖 JSON 态 false（显式开启）")
    void declaredAutoUpdateOverridesJsonStateEnabling() {
        MarketplaceConfigStore.DeclaredMarketplace declared = new MarketplaceConfigStore.DeclaredMarketplace(
            new MarketplaceSource.Github("anthropics/x", null, null, null), false, true);
        PluginAutoupdate au = new PluginAutoupdate(null, null, null, null,
            () -> Map.of("mkt1", km(false)),          // JSON 态 autoUpdate=false
            () -> Map.of("mkt1", declared));           // declared 声明 autoUpdate=true → 覆盖

        assertThat(au.getAutoUpdateEnabledMarketplaces()).contains("mkt1");
    }

    @Test
    @DisplayName("declared 无该市场/autoUpdate 为 null → 回退 JSON 态默认语义")
    void declaredWithoutAutoUpdateFallsBackToJsonState() {
        // CC :92-95：declaredAutoUpdate !== undefined 才优先；declared 无该市场或无 autoUpdate → 回退
        // isMarketplaceAutoUpdate（官方名单默认 true）。
        MarketplaceConfigStore.DeclaredMarketplace declaredNoAutoUpdate = new MarketplaceConfigStore.DeclaredMarketplace(
            new MarketplaceSource.Github("anthropics/x", null, null, null), false, null);
        PluginAutoupdate au = new PluginAutoupdate(null, null, null, null,
            () -> Map.of(
                "claude-plugins-official", km((Boolean) null),  // JSON 态无显式 → 官方默认 true
                "mkt1", km(true)),                              // 第三方 JSON 态 true
            () -> Map.of("claude-plugins-official", declaredNoAutoUpdate, "other", declaredNoAutoUpdate));

        assertThat(au.getAutoUpdateEnabledMarketplaces())
            .containsExactlyInAnyOrder("claude-plugins-official", "mkt1");
    }

    @Test
    @DisplayName("updatePluginsForMarketplaces：仅更新匹配市场、忽略其他市场")
    void updateOnlyForMatchingMarketplaces() {
        PluginAutoupdate au = new PluginAutoupdate(null,
            () -> Map.of(
                "a@mkt1", List.of(new Installation("user", null)),
                "b@mkt2", List.of(new Installation("user", null))),
            null,
            (id, scope) -> new UpdateOutcome(true, false, "ok", "1.0.0", "2.0.0"),
            null);

        assertThat(au.updatePluginsForMarketplaces(Set.of("mkt1"))).containsExactly("a@mkt1");
    }

    @Test
    @DisplayName("updatePluginsForMarketplaces：already-up-to-date 静默跳过（不计入 updated）")
    void updateSkipsAlreadyUpToDate() {
        PluginAutoupdate au = new PluginAutoupdate(null,
            () -> Map.of("a@mkt1", List.of(new Installation("user", null))),
            null,
            (id, scope) -> UpdateOutcome.upToDate(),
            null);

        assertThat(au.updatePluginsForMarketplaces(Set.of("mkt1"))).isEmpty();
    }

    @Test
    @DisplayName("后台刷新仅 autoUpdate 市场；回调未注册 → pendingNotification 竞态缓存")
    void backgroundRefreshesEnabledMarketsAndCachesPending() {
        List<String> refreshed = new ArrayList<>();
        PluginAutoupdate au = new PluginAutoupdate(
            () -> false,
            () -> Map.of("a@mkt1", List.of(new Installation("user", null))),
            refreshed::add,
            (id, scope) -> new UpdateOutcome(true, false, "ok", "1.0.0", "2.0.0"),
            () -> Map.of("mkt1", km(true), "mkt2", km(false)));

        // 同步执行后台逻辑（回调未注册）
        au.runBackgroundAutoUpdate();

        // 仅刷新 autoUpdate 启用的市场（mkt2 不刷新）
        assertThat(refreshed).containsExactly("mkt1");
        // 更新已发生但回调未注册 → 暂存 pending，getAutoUpdatedPluginNames 可读
        assertThat(au.getAutoUpdatedPluginNames()).containsExactly("a");

        // 注册回调 → 立即投递 pending 并清空
        List<String>[] delivered = new List[]{null};
        au.onPluginsAutoUpdated(list -> delivered[0] = list);
        assertThat(delivered[0]).containsExactly("a@mkt1");
        assertThat(au.getAutoUpdatedPluginNames()).isEmpty();
    }

    @Test
    @DisplayName("skip 开关：自动更新禁用 → 不刷新不更新")
    void backgroundSkippedWhenDisabled() {
        List<String> refreshed = new ArrayList<>();
        PluginAutoupdate au = new PluginAutoupdate(
            () -> true,
            () -> Map.of("a@mkt1", List.of(new Installation("user", null))),
            refreshed::add,
            (id, scope) -> new UpdateOutcome(true, false, "ok", "1", "2"),
            () -> Map.of("mkt1", km(true)));

        au.runBackgroundAutoUpdate();

        assertThat(refreshed).isEmpty();
    }
}
