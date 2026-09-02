package com.nexusai.application.agent.plugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core plugin operations · 对齐 CC services/plugins/pluginOperations.ts.
 *
 * <p>L1 语义: 核心 plugin 操作 (install/uninstall/enable/disable/update) — 纯 library;
 *            不调用 process.exit() 不写 console;返回 result objects;
 *            可被 CLI 和 UI 复用.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 5 result record (Install/Uninstall/Enable/Disable/Update); OperationResult sealed;
 *       5 method (installPlugin/uninstallPlugin/enablePlugin/disablePlugin/updatePlugin).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — installPlugin(scope, name) → result.success + message;
 *       失败 → throw or result.success=false.</li>
 *   <li><b>A3</b>: 注入式 (pluginFs + cacheUtils);pure functions returning result objects.</li>
 *   <li><b>A4</b>: 不存在 plugin → success=false;scope invalid → throw.</li>
 *   <li><b>A5</b>: 真实场景 — `claude plugin install formatter@marketplace` → 写盘 + cache invalidate.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS class mutable → Java final class + Supplier;
 *                    TS throw → Java return result (no exit);
 *                    TS cache invalidation → Java Consumer.
 */
public final class PluginOperations {

    private static final Logger log = LoggerFactory.getLogger(PluginOperations.class);

    public sealed interface OperationResult permits Success, Failure {
        boolean success();
        String message();
    }
    public record Success(String message) implements OperationResult {
        public boolean success() { return true; }
    }
    public record Failure(String message) implements OperationResult {
        public boolean success() { return false; }
    }

    /**
     * 安装操作结果 · CC {@code PluginOperationResult}（pluginOperations.ts:141-149）
     * {@code {success, message, pluginId?, pluginName?, scope?}}。
     */
    public record InstallResult(boolean success, String message, String pluginId,
                                String pluginName, String scope) {
        public static InstallResult failure(String message) {
            return new InstallResult(false, message, null, null, null);
        }
    }
    public record UninstallResult(boolean success, String message) {}
    public record EnableResult(boolean success, String message) {}
    public record DisableResult(boolean success, String message) {}
    public record UpdateResult(boolean success, String message, String oldVersion, String newVersion) {}

    public interface PluginFileSystem {
        boolean exists(String path);
        boolean isDirectory(String path);
        boolean isFile(String path);
        void mkdir(String path);
        void writeFile(String path, String content);
        String readFile(String path);
        void unlink(String path);
        java.util.List<String> list(String path);
    }

    public interface CacheInvalidator {
        void clearAll();
    }

    public interface ManifestLoader {
        Object load(String path);
    }

    private final PluginFileSystem fs;
    private final CacheInvalidator cacheInvalidator;
    private final Supplier<String> pluginRootSupplier;
    /** [MPL4] marketplace 查找（installPluginOp 定位 entry）· null = 未接线（installPlugin 直接失败）。 */
    private MarketplaceManager marketplaceManager;
    /** [MPL4] 安装链（installResolvedPlugin + cacheAndRegisterPlugin）· null = 未接线。 */
    private PluginInstaller installer;

    public PluginOperations(PluginFileSystem fs, CacheInvalidator cacheInvalidator,
            Supplier<String> pluginRootSupplier) {
        this.fs = fs == null ? new NullFileSystem() : fs;
        this.cacheInvalidator = cacheInvalidator == null ? () -> {} : cacheInvalidator;
        this.pluginRootSupplier = pluginRootSupplier == null ? () -> "/plugins" : pluginRootSupplier;
    }

    public PluginOperations() {
        this(null, null, null);
    }

    /** 测试注入：marketplace 查找 + 安装链（未注入 → installPlugin 返回未接线失败）。 */
    public void wireInstallation(MarketplaceManager marketplaceManager, PluginInstaller installer) {
        this.marketplaceManager = marketplaceManager;
        this.installer = installer;
    }

    /**
     * installPluginOp 等价 · CC pluginOperations.ts:321-418。
     *
     * <ol>
     *   <li>assertInstallableScope（scope 非法 → 抛，:325）</li>
     *   <li>parsePluginIdentifier → name + marketplace（:327-328）</li>
     *   <li>marketplace 定位：带 @ → getPluginById 快路径；裸名 → 遍历 known marketplaces find（:330-359）</li>
     *   <li>未找到 → success=false not-found（:361-369）</li>
     *   <li>installResolvedPlugin + 错误映射（:374-409）</li>
     * </ol>
     *
     * @param plugin 插件标识（name 或 name@marketplace，CC :317）
     * @param scope  安装 scope（user/project/local，默认 user）
     * @return InstallResult（success + message + pluginId/pluginName/scope）
     */
    public InstallResult installPlugin(String plugin, String scope) {
        String effScope = scope == null || scope.isBlank() ? "user" : scope;
        assertInstallableScope(effScope);

        if (installer == null || marketplaceManager == null) {
            return InstallResult.failure("安装链未接线（PluginInstaller/MarketplaceManager 未注入）");
        }

        PluginIdentifier.Parsed id = PluginIdentifier.parse(plugin);
        String pluginName = id.name();
        String marketplaceName = id.marketplace();

        // ── marketplace 定位（CC :330-359）──
        PluginMarketplace.Entry foundPlugin = null;
        String foundMarketplace = null;
        String marketplaceInstallLocation = null;

        if (marketplaceName != null && !marketplaceName.isBlank()) {
            PluginMarketplace.LookupResult info = marketplaceManager.getPluginById(plugin);
            if (info != null) {
                foundPlugin = info.entry();
                foundMarketplace = marketplaceName;
                marketplaceInstallLocation = info.marketplaceInstallLocation();
            }
        } else {
            Map<String, PluginSchemas.KnownMarketplace> marketplaces =
                marketplaceManager.loadKnownMarketplacesConfigSafe();
            for (Map.Entry<String, PluginSchemas.KnownMarketplace> e : marketplaces.entrySet()) {
                String mktName = e.getKey();
                try {
                    PluginMarketplace.Marketplace marketplace = marketplaceManager.getMarketplace(mktName);
                    PluginMarketplace.Entry entry = marketplace.plugins().stream()
                        .filter(p -> pluginName.equals(p.name()))
                        .findFirst().orElse(null);
                    if (entry != null) {
                        foundPlugin = entry;
                        foundMarketplace = mktName;
                        marketplaceInstallLocation = e.getValue().installLocation();
                        break;
                    }
                } catch (Exception error) {
                    // 单个 marketplace 加载失败 → 继续（CC :354-357 logError + continue）
                    log.warn("marketplace {} 加载失败，跳过: {}", mktName, error.getMessage());
                }
            }
        }

        if (foundPlugin == null || foundMarketplace == null) {
            String location = (marketplaceName != null && !marketplaceName.isBlank())
                ? "marketplace \"" + marketplaceName + "\""
                : "any configured marketplace";
            String message = "Plugin \"" + pluginName + "\" not found in " + location;
            if (log.isWarnEnabled()) {
                log.warn("{}（CC installPluginOp :361-369）", message);
            }
            return InstallResult.failure(message);
        }

        PluginMarketplace.Entry entry = foundPlugin;
        String pluginId = entry.name() + "@" + foundMarketplace;

        PluginInstaller.CoreResult result = installer.installResolvedPlugin(
            pluginId, entry, effScope, marketplaceInstallLocation);

        if (!result.ok()) {
            switch (result.reason()) {
                case "local-source-no-location":
                    return InstallResult.failure("无法安装本地插件 \"" + result.pluginName()
                        + "\"（缺少 marketplace 安装位置）");
                case "settings-write-failed":
                    return InstallResult.failure("更新设置失败: " + result.message());
                case "resolution-failed":
                    return InstallResult.failure(result.message());
                case "blocked-by-policy":
                    return InstallResult.failure("插件 \"" + result.pluginName()
                        + "\" 被组织 policy 阻断，无法安装");
                case "dependency-blocked-by-policy":
                    return InstallResult.failure("插件 \"" + result.pluginName() + "\" 依赖 \""
                        + result.blockedDependency() + "\"，被组织 policy 阻断");
                case "materialize-failed":
                    return InstallResult.failure("插件物化失败: " + result.message());
                default:
                    return InstallResult.failure("插件安装失败: " + result.message());
            }
        }

        String message = "Successfully installed plugin: " + pluginId + " (scope: " + effScope + ")"
            + result.depNote();
        if (log.isInfoEnabled()) {
            log.info("{}（CC installPluginOp :411-417）", message);
        }
        return new InstallResult(true, message, pluginId, entry.name(), effScope);
    }

    /**
     * assertInstallableScope · CC pluginOperations.ts:90-98
     * （VALID_INSTALLABLE_SCOPES = user/project/local，非法 → throw）。
     */
    public static void assertInstallableScope(String scope) {
        if (!List.of("user", "project", "local").contains(scope)) {
            throw new IllegalArgumentException(
                "非法 scope \"" + scope + "\"。必须是: user, project, local");
        }
    }

    public OperationResult uninstallPlugin(String scope, String name) {
        if (scope == null || name == null) return new Failure("invalid args");
        String path = pluginRootSupplier.get() + "/" + scope + "/" + name;
        if (!fs.exists(path)) {
            return new Failure("plugin not found: " + path);
        }
        fs.unlink(path);
        cacheInvalidator.clearAll();
        return new Success("uninstalled " + name);
    }

    public OperationResult enablePlugin(String scope, String name) {
        if (scope == null || name == null) return new Failure("invalid args");
        cacheInvalidator.clearAll();
        return new Success("enabled " + name);
    }

    public OperationResult disablePlugin(String scope, String name) {
        if (scope == null || name == null) return new Failure("invalid args");
        cacheInvalidator.clearAll();
        return new Success("disabled " + name);
    }

    public OperationResult updatePlugin(String scope, String name) {
        if (scope == null || name == null) return new Failure("invalid args");
        cacheInvalidator.clearAll();
        return new Success("updated " + name);
    }

    private static class NullFileSystem implements PluginFileSystem {
        public boolean exists(String p) { return false; }
        public boolean isDirectory(String p) { return false; }
        public boolean isFile(String p) { return false; }
        public void mkdir(String p) {}
        public void writeFile(String p, String c) {}
        public String readFile(String p) { return ""; }
        public void unlink(String p) {}
        public java.util.List<String> list(String p) { return List.of(); }
    }
}