package com.nexusai.application.agent.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexusai.application.agent.settings.storage.ConfigStorage;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.model.market.dto.PluginInstallResult;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PluginInstallService.uninstall 清理链测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：卸载插件原来只删目录 + 一条文件记录，于是有三个真实缺陷：
 * <ol>
 *   <li><b>卸载后重启插件会"复活"</b>：启动时 {@code migrateFromEnabledPlugins} 从
 *       {@code settings.enabled_plugins} 读回该插件并重新登记成已安装（installPath 指向已删目录）——
 *       所以启用条目必须一起清；</li>
 *   <li><b>裸名卸不掉 scoped 记录</b>：已安装记录的 key 空间不统一（{@code addInstalledPlugin}
 *       写裸名、{@code migrateFromEnabledPlugins} 写 {@code name@marketplace}）⇒ 按单一形态匹配会漏；</li>
 *   <li><b>插件贡献的 MCP server 变孤儿</b>：插件 MCP 落 {@code mcp_servers} 表（scope=dynamic、
 *       名字 {@code plugin:<name>:<server>}），插件卸了它还连着。</li>
 * </ol>
 *
 * <p>本测试用**不存在的插件名**（{@code zz-unit-test-no-such-plugin}）⇒ 数据目录候选路径必然不存在，
 * 不会碰到用户真实安装。
 */
@DisplayName("PluginInstallService.uninstall：卸载清理链")
class PluginInstallServiceUninstallTest {

    private static final String PLUGIN = "zz-unit-test-no-such-plugin";
    private static final String PLUGIN_AT_MARKET = PLUGIN + "@zz-market";

    /** 反射注入私有字段（生产用字段注入，测试不引 Spring 容器）。 */
    private static void inject(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("注入字段失败: " + field, e);
        }
    }

    /**
     * 构造一条已安装记录 · <b>installPath/sourcePath 传 null</b> ⇒ 不指向任何真实目录，
     * 卸载时不会去删用户机器上的东西。
     *
     * <p>用真实 record（11 参兼容构造器）而不是 mock：record 是 final，mock final record 的
     * accessor 会与 {@code when(...)} 嵌套 stubbing 撞车（UnfinishedStubbingException）。
     */
    private static InstalledPluginsManager.InstalledRecord record(String key) {
        return new InstalledPluginsManager.InstalledRecord(
            key, "0.1.0", "user", true, 0L,
            null, null, null, null, 0L, null);
    }

    private static PluginInstallService service(InstalledPluginsManager mgr,
                                                SettingsService settings,
                                                McpServerService mcp,
                                                ConfigStorage storage) {
        PluginInstallService svc = new PluginInstallService();
        inject(svc, "installedPluginsManager", mgr);
        inject(svc, "settingsService", settings);
        inject(svc, "mcpServerService", mcp);
        inject(svc, "configStorage", storage);
        MarketplaceManager markets = mock(MarketplaceManager.class);
        // 空 known-marketplaces ⇒ 不产生额外 data 目录候选（候选只有记录 key + 裸名）
        when(markets.loadKnownMarketplacesConfigSafe()).thenReturn(Map.of());
        inject(svc, "marketplaceManager", markets);
        return svc;
    }

    @Test
    @DisplayName("记录 key 是 name@marketplace 时，裸名也能卸掉（键空间不统一）")
    void bareNameMatchesScopedRecord() {
        InstalledPluginsManager mgr = mock(InstalledPluginsManager.class);
        when(mgr.list()).thenReturn(List.of(record(PLUGIN_AT_MARKET)));
        SettingsService settings = mock(SettingsService.class);
        McpServerService mcp = mock(McpServerService.class);
        ConfigStorage storage = mock(ConfigStorage.class);
        PluginInstallService svc = service(mgr, settings, mcp, storage);

        PluginInstallResult r = svc.uninstall(PLUGIN);

        assertThat(r.success()).as("裸名必须能命中 scoped 记录").isTrue();
        verify(mgr).uninstall(eq(PLUGIN_AT_MARKET));
    }

    @Test
    @DisplayName("卸载会清 DB enabledPlugins（不卸干净的根因：重启后插件复活）")
    void removesEnabledPluginFromDb() {
        InstalledPluginsManager mgr = mock(InstalledPluginsManager.class);
        when(mgr.list()).thenReturn(List.of(record(PLUGIN)));
        SettingsService settings = mock(SettingsService.class);
        McpServerService mcp = mock(McpServerService.class);
        ConfigStorage storage = mock(ConfigStorage.class);
        PluginInstallService svc = service(mgr, settings, mcp, storage);

        svc.uninstall(PLUGIN);

        verify(settings).removeEnabledPlugin(PLUGIN);
    }

    @Test
    @DisplayName("卸载会删该插件贡献的 MCP server 行（否则成孤儿）")
    void deletesPluginMcpServers() {
        InstalledPluginsManager mgr = mock(InstalledPluginsManager.class);
        when(mgr.list()).thenReturn(List.of(record(PLUGIN)));
        SettingsService settings = mock(SettingsService.class);
        McpServerService mcp = mock(McpServerService.class);
        ConfigStorage storage = mock(ConfigStorage.class);
        PluginInstallService svc = service(mgr, settings, mcp, storage);

        svc.uninstall(PLUGIN);

        verify(mcp).deletePluginMcpServers(PLUGIN);
    }

    @Test
    @DisplayName("卸载会清 ~/.nexusai.json 的 enabledPlugins 键（读链的另一环）")
    void removesEnabledPluginFromFile() {
        InstalledPluginsManager mgr = mock(InstalledPluginsManager.class);
        when(mgr.list()).thenReturn(List.of(record(PLUGIN)));
        SettingsService settings = mock(SettingsService.class);
        McpServerService mcp = mock(McpServerService.class);
        ConfigStorage storage = mock(ConfigStorage.class);
        Map<String, Boolean> onDisk = new LinkedHashMap<>();
        onDisk.put(PLUGIN, true);
        onDisk.put("other-plugin", true);
        when(storage.readSettings(List.of("enabledPlugins"))).thenReturn(onDisk);
        PluginInstallService svc = service(mgr, settings, mcp, storage);

        svc.uninstall(PLUGIN);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Object> cap = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(storage).writeSettings(eq(List.of("enabledPlugins")), cap.capture());
        @SuppressWarnings("unchecked")
        Map<String, Boolean> written = (Map<String, Boolean>) cap.getValue();
        assertThat(written).doesNotContainKey(PLUGIN);
        assertThat(written).as("别的插件的启用状态不能被误删").containsEntry("other-plugin", true);
    }

    @Test
    @DisplayName("找不到记录 → 失败且不碰任何清理（不误删）")
    void unknownPluginFailsWithoutSideEffects() {
        InstalledPluginsManager mgr = mock(InstalledPluginsManager.class);
        when(mgr.list()).thenReturn(List.of());
        SettingsService settings = mock(SettingsService.class);
        McpServerService mcp = mock(McpServerService.class);
        ConfigStorage storage = mock(ConfigStorage.class);
        PluginInstallService svc = service(mgr, settings, mcp, storage);

        PluginInstallResult r = svc.uninstall(PLUGIN);

        assertThat(r.success()).isFalse();
        verify(settings, never()).removeEnabledPlugin(anyString());
        verify(mcp, never()).deletePluginMcpServers(anyString());
        verify(storage, never()).writeSettings(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
    }
}
