package com.nexusai.application.agent.plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer-3 refresh 原语 · 对齐 CC {@code utils/plugins/refresh.ts:72-191 refreshActivePlugins}.
 *
 * <p>三层层模型（reconciler.ts:3-7 注释）：L1 intent（settings）→ L2 物化（known_marketplaces.json）→
 * L3 active components（AppState）。本类是 L3 刷新：清所有插件缓存 → 重载插件 → AppState
 * {@code needsRefresh=false}（消费）。
 *
 * <p>实现 {@link PluginInstallationManager.PluginRefresher} 契约（refresh(setAppState)），
 * 由 performBackgroundPluginInstallations 在安装 &gt;0 时调用（CC PluginInstallationManager.ts:135-165）。
 *
 * <p><b>Java 降级</b>（会话提示词 §8）：MCP/LSP/hooks 重载在 Java 端由 {@link PluginLoader#refreshActivePlugins()}
 * 承载（MPL7 已实现 clearPluginCache + loadAllPlugins + loadPluginHooks）；本类只做 clearMarketplacesCache
 * 级联 + 重载 + AppState 标记，避免 startup 阻塞。插件 MCP/LSP 的精确重连（pluginReconnectKey）属后续 Session。
 */
public class ActivePluginRefresher implements PluginInstallationManager.PluginRefresher {

    private static final Logger log = LoggerFactory.getLogger(ActivePluginRefresher.class);

    private final MarketplaceManager marketplaceManager;
    private final PluginLoader pluginLoader;

    public ActivePluginRefresher(MarketplaceManager marketplaceManager, PluginLoader pluginLoader) {
        this.marketplaceManager = Objects.requireNonNull(marketplaceManager);
        this.pluginLoader = pluginLoader; // 可空：测试/未装配时跳过插件重载，仅清缓存
    }

    /**
     * 刷新启用插件 · CC {@code refreshActivePlugins}（refresh.ts:72-191）。
     *
     * <p>clearMarketplacesCache（CC 由调用方 clearMarketplacesCache + refreshActivePlugins 内 clearAllCaches
     * 双重清）→ 插件重载（Java：PluginLoader.refreshActivePlugins 已含 clearPluginCache + loadAllPlugins +
     * loadPluginHooks）→ AppState needsRefresh=false。
     *
     * @param setAppState AppState 更新器（CC SetAppState）
     */
    @Override
    @SuppressWarnings("unchecked")
    public void refresh(Consumer<Object> setAppState) {
        if (log.isDebugEnabled()) {
            log.debug("refreshActivePlugins: 清 marketplace 缓存 + 重载插件");
        }
        marketplaceManager.clearMarketplacesCache();
        if (pluginLoader != null) {
            pluginLoader.refreshActivePlugins();
        } else {
            if (log.isWarnEnabled()) {
                log.warn("ActivePluginRefresher: PluginLoader 未装配，跳过插件重载（仅清 marketplace 缓存）");
            }
        }
        // CC refresh.ts:123-138 —— 成功刷新消费 needsRefresh（置 false）
        if (setAppState != null) {
            setAppState.accept((Object) (java.util.function.Function<Map<String, Object>, Map<String, Object>>)
                prev -> {
                    Map<String, Object> plugins = (Map<String, Object>) prev.get("plugins");
                    if (plugins == null) {
                        return prev;
                    }
                    Map<String, Object> newPlugins = new LinkedHashMap<>(plugins);
                    newPlugins.put("needsRefresh", false);
                    Map<String, Object> newState = new LinkedHashMap<>(prev);
                    newState.put("plugins", newPlugins);
                    return newState;
                });
        }
    }
}
