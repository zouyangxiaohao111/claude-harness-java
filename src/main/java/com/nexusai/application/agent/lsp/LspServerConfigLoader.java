package com.nexusai.application.agent.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * LSP 服务器发现 (来自 plugins) · 对齐 CC services/lsp/config.ts getAllLspServers.
 *
 * <p>L1 语义: 加载所有 plugin 的 LSP server 配置 → 合并为 scoped-server-name → ScopedLspServerConfig map.
 *            单 plugin 抛错不影响其他 plugin 的结果 (CC 注释明示).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `loadAllServers(PluginProvider) → AllLspServersResult` 签名 (servers map)</li>
 *   <li><b>A2 Golden Trace</b>: 0 plugin → servers={}; 1 plugin → servers=plugin's scopedServers; 多 plugin → 合并 (后者覆盖前者)</li>
 *   <li><b>A3</b>: 单 plugin 抛错 → 该 plugin's servers 跳过, 其余继续; 顶层 catch → 返回空 map 不抛</li>
 *   <li><b>A4</b>: 空 scopedServers → 该 plugin 不贡献任何 entry (CC serverCount > 0 检查)</li>
 *   <li><b>A5</b>: 真实场景 — 2 plugin × 2 servers each = 4 servers (无 key 冲突); 1 key 冲突 → 后者覆盖前者</li>
 * </ul>
 *
 * <p>L3 (Java idiom): CompletableFuture&lt;List&lt;Plugin&gt;&gt; 替代 CC async loadAllPlugins;
 *                    LinkedHashMap 保序 (CC Object.assign 顺序); Function 注入 plugin→servers 映射.
 */
public class LspServerConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(LspServerConfigLoader.class);

    /** Plugin 占位. */
    public record Plugin(String name) {}

    /** CC ScopedLspServerConfig 简化版. */
    public record ScopedLspServerConfig(String command, List<String> args) {}

    /** CC getAllLspServers 返回. */
    public record AllLspServersResult(Map<String, ScopedLspServerConfig> servers) {}

    private final Function<Plugin, Map<String, ScopedLspServerConfig>> pluginLspLoader;

    public LspServerConfigLoader(Function<Plugin, Map<String, ScopedLspServerConfig>> pluginLspLoader) {
        this.pluginLspLoader = pluginLspLoader;
    }

    /**
     * 同步加载所有 plugin 的 LSP server 配置 (CC getAllLspServers).
     *
     * @param plugins 所有 enabled plugins
     * @return AllLspServersResult (servers map)
     */
    public AllLspServersResult loadAllServers(List<Plugin> plugins) {
        Map<String, ScopedLspServerConfig> allServers = new LinkedHashMap<>();
        if (plugins == null || plugins.isEmpty()) {
            return new AllLspServersResult(Map.of());
        }
        for (Plugin plugin : plugins) {
            Map<String, ScopedLspServerConfig> scopedServers;
            try {
                scopedServers = pluginLspLoader.apply(plugin);
            } catch (Exception e) {
                log.warn("[LspServerConfigLoader] failed for plugin {}: {}",
                    plugin.name(), e.getMessage());
                continue;
            }
            if (scopedServers == null || scopedServers.isEmpty()) continue;
            // CC Object.assign 顺序合并: 后者覆盖前者
            allServers.putAll(scopedServers);
            log.info("[LspServerConfigLoader] loaded {} LSP servers from plugin {}",
                scopedServers.size(), plugin.name());
        }
        log.info("[LspServerConfigLoader] total {} LSP servers", allServers.size());
        return new AllLspServersResult(Map.copyOf(allServers));
    }

    /** 异步版本: 并行加载所有 plugin (CC Promise.all). */
    public CompletableFuture<AllLspServersResult> loadAllServersAsync(
            CompletableFuture<List<Plugin>> pluginsFuture) {
        return pluginsFuture.thenCompose(plugins -> {
            if (plugins == null || plugins.isEmpty()) {
                return CompletableFuture.completedFuture(new AllLspServersResult(Map.of()));
            }
            List<CompletableFuture<Map<String, ScopedLspServerConfig>>> tasks = plugins.stream()
                .map(p -> CompletableFuture.supplyAsync(() -> {
                    try { return pluginLspLoader.apply(p); }
                    catch (Exception e) { return Map.<String, ScopedLspServerConfig>of(); }
                }))
                .toList();
            return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, ScopedLspServerConfig> all = new LinkedHashMap<>();
                    for (var t : tasks) all.putAll(t.join());
                    return new AllLspServersResult(Map.copyOf(all));
                });
        });
    }
}