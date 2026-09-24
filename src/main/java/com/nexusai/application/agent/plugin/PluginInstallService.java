package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import com.nexusai.model.market.dto.PluginInstallRequest;
import com.nexusai.model.market.dto.PluginInstallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 插件安装服务（web 入口）· 装配生产安装链 + 按需 reconcile 外部（url）市场。
 *
 * <p>背景：项目插件安装链（{@link PluginOperations} + {@link PluginInstaller}）在 CLI/测试外
 * 无 Spring 装配点；本服务为前端「建科数字插件市场」安装按钮提供 REST 入口。
 *
 * <p><b>按需下载策略</b>（用户拍板 2026-09-17 · 反对整包 zip 全量）：市场体量可到上千插件/几百 G，
 * 全量一次下载不可行。本服务采用「索引 + 每插件独立包」：
 * <ol>
 *   <li>ensureMarketplace 只下载 <b>marketplace.json 索引</b>（几 KB）物化到缓存目录；</li>
 *   <li>安装目标插件时，按 entry.source 相对目录（如 {@code experts/data-analyst/0.1.0}）
 *       拼接 {@code <base>/<source>/plugin.zip}，<b>只下载该插件包</b>（几十 KB）解压到缓存
 *       市场目录对应位置——source 目录内约定含 {@code plugin.zip}（每插件独立打包）；</li>
 *   <li>再装配安装链安装（Local 源从缓存市场目录定位）。</li>
 * </ol>
 */
@Service
public class PluginInstallService {

    private static final Logger log = LoggerFactory.getLogger(PluginInstallService.class);

    @Autowired
    private MarketplaceManager marketplaceManager;

    @Autowired
    private InstalledPluginsManager installedPluginsManager;

    /** 插件加载器（装完刷新 feed 缓存用）· required=false（未装配时跳过刷新，重启后生效）。 */
    @Autowired(required = false)
    private PluginLoader pluginLoader;

    /** Agent 调度（装完清 per-cwd registry 缓存用）· required=false。 */
    @Autowired(required = false)
    private com.nexusai.application.agent.tool.impl.SubagentTool subagentTool;

    /**
     * 安装插件（前端调用）。
     *
     * @param req pluginId（name@marketplace）+ marketplaceUrl（marketplace.json 索引 url）+ scope
     * @return 安装结果（success + message + 标识）
     */
    public PluginInstallResult install(PluginInstallRequest req) {
        if (req == null || req.pluginId() == null || req.pluginId().isBlank()) {
            return new PluginInstallResult(false, "pluginId 不能为空", null, null, null);
        }
        String pluginId = req.pluginId().trim();
        String scope = req.scope() == null || req.scope().isBlank() ? "user" : req.scope().trim();
        String marketplaceUrl = req.marketplaceUrl() == null || req.marketplaceUrl().isBlank()
            ? "" : req.marketplaceUrl().trim();

        try {
            // 1. 确保市场索引物化（下载 marketplace.json，几 KB；已物化则复用缓存目录）
            String marketName = PluginIdentifier.parse(pluginId).marketplace();
            String installLocation = ensureMarketplace(marketplaceUrl, marketName);

            // 2. 按需下载目标插件（source 目录下的 plugin.zip）→ 缓存市场目录对应位置
            if (!marketplaceUrl.isEmpty()) {
                downloadTargetPlugin(marketplaceUrl, pluginId, installLocation);
            }

            // 3. 装配安装链并安装
            PluginInstaller installer = new PluginInstaller();
            installer.setMarketplaceManager(marketplaceManager);
            installer.setInstalledPluginsManager(installedPluginsManager);
            installer.setCacheUtils(new PluginCacheUtils());
            installer.setDependencyResolver(new PluginDependencyResolver());
            PluginOperations ops = new PluginOperations();
            ops.wireInstallation(marketplaceManager, installer);

            PluginOperations.InstallResult r = ops.installPlugin(pluginId, scope);
            if (r.success()) {
                refreshPlugins();
            }
            if (log.isInfoEnabled()) {
                log.info("[PluginInstall] 安装结果 success={} plugin={} msg={}", r.success(), pluginId, r.message());
            }
            return new PluginInstallResult(r.success(), r.message(), r.pluginId(), r.pluginName(), r.scope());
        } catch (IllegalArgumentException e) {
            return new PluginInstallResult(false, "非法参数: " + e.getMessage(), null, null, null);
        } catch (IOException e) {
            log.error("[PluginInstall] 安装失败 plugin={}: {}", pluginId, e.getMessage());
            return new PluginInstallResult(false, "安装失败（" + e.getMessage() + "）", null, null, null);
        }
    }

    /**
     * 已安装且启用的插件名列表（前端「已安装」标记数据源）。
     *
     * @return 插件名（如 zjky-data-analyst）；仅 enabled 记录。
     */
    public java.util.List<String> listInstalled() {
        return installedPluginsManager.list().stream()
            .filter(InstalledPluginsManager.InstalledRecord::enabled)
            .map(InstalledPluginsManager.InstalledRecord::name)
            .toList();
    }

    /** 装完触发插件刷新：PluginLoader feed 缓存失效 + <b>重新扫描插件 agents 并入 registry</b>
     *  + per-cwd registry 重建。让插件 agents/commands/skills 立即可见（否则要重启后端才生效）。
     *  失败仅 warn 不阻断。 */
    private void refreshPlugins() {
        try {
            if (pluginLoader != null) {
                pluginLoader.refreshActivePlugins();
            }
            if (subagentTool != null) {
                // 关键：mergePluginAgents 只在 Spring 装配期执行一次（当时无插件 → globalAdditions 空）；
                // 装完后必须重新扫描插件 agents 并入，再清 registry 缓存让 per-cwd registry 重建时合并。
                subagentTool.mergePluginAgents();
                subagentTool.clearRegistryCache();
            }
            if (log.isInfoEnabled()) {
                log.info("[PluginInstall] 插件缓存已刷新（agents/commands/skills 重载）");
            }
        } catch (Exception e) {
            log.warn("[PluginInstall] 插件刷新失败（重启后端后生效）: {}", e.getMessage());
        }
    }

    /**
     * 确保市场索引可读：已物化 → 复用缓存目录，但<b>每次安装都刷新 marketplace.json 索引</b>
     * （几 KB，保证拿到最新 source——用户可能更新过市场，避免旧索引缺 source）；未物化 →
     * 下载 marketplaceUrl（marketplace.json 索引）物化并返回缓存目录。
     */
    private String ensureMarketplace(String marketplaceUrl, String marketName) throws IOException {
        Map<String, KnownMarketplace> known = marketplaceManager.loadKnownMarketplacesConfig();
        KnownMarketplace existing = marketName != null ? known.get(marketName) : null;
        if (existing != null && existing.installLocation() != null) {
            if (!marketplaceUrl.isEmpty()) {
                // 刷新索引（几 KB）：覆盖缓存 marketplace.json，保证 source 与远端一致
                new MarketplaceHttpDownloader().downloadMarketplace(marketplaceUrl, null, existing.installLocation());
                if (log.isDebugEnabled()) {
                    log.debug("[PluginInstall] 已刷新市场索引 {}（{}）", marketName, existing.installLocation());
                }
            }
            return existing.installLocation();
        }
        if (marketplaceUrl.isEmpty()) {
            throw new IOException("市场未物化且未提供 marketplaceUrl");
        }
        MarketplaceConfigStore store = new MarketplaceConfigStore(marketplaceManager);
        MarketplaceReconciler reconciler = new MarketplaceReconciler(store);
        reconciler.addMarketplaceSource(new MarketplaceSource.Url(marketplaceUrl, null));
        KnownMarketplace after = marketplaceManager.loadKnownMarketplacesConfig().get(marketName);
        if (after == null) {
            throw new IOException("市场物化失败: " + marketName);
        }
        if (log.isInfoEnabled()) {
            log.info("[PluginInstall] 市场索引已物化: {} → {}", marketName, after.installLocation());
        }
        return after.installLocation();
    }

    /**
     * 按需下载目标插件包：从缓存 marketplace.json 取插件 entry.source（相对目录），
     * 拼接 {@code <base>/<source>/plugin.zip}，下载解压到缓存市场目录的 source 位置。
     * 只下载目标插件一个包（几十 KB），不碰其他插件。
     */
    private void downloadTargetPlugin(String marketplaceUrl, String pluginId, String installLocation) throws IOException {
        PluginIdentifier.Parsed id = PluginIdentifier.parse(pluginId);
        String marketName = id.marketplace();
        String pluginName = id.name();
        if (marketName == null || marketName.isBlank()) {
            throw new IOException("插件标识缺市场名: " + pluginId);
        }
        PluginMarketplace.Marketplace mp = marketplaceManager.getMarketplaceCacheOnly(marketName);
        if (mp == null) {
            throw new IOException("市场未缓存（无法定位插件 source）: " + marketName);
        }
        PluginMarketplace.Entry entry = mp.plugins().stream()
            .filter(e -> pluginName.equals(e.name()))
            .findFirst().orElse(null);
        if (entry == null) {
            throw new IOException("市场 '" + marketName + "' 中没有插件: " + pluginName);
        }
        JsonNode src = entry.source();
        if (src == null || !src.isTextual() || src.asText().isBlank()) {
            throw new IOException("插件 '" + pluginName + "' 缺少 source（相对目录，含 plugin.zip）");
        }
        String source = src.asText();
        // 插件包 URL：<marketplace 目录 base>/<source>/plugin.zip（每插件独立打包约定）
        String base = marketplaceUrl.substring(0, marketplaceUrl.lastIndexOf('/') + 1);
        String pluginUrl = base + source + "/plugin.zip";
        java.nio.file.Path target = Paths.get(installLocation, source);
        new MarketplaceHttpDownloader().downloadArchive(pluginUrl, null, target.toString());
        if (log.isInfoEnabled()) {
            log.info("[PluginInstall] 已按需下载插件包 {} → {}", pluginName, target);
        }
    }
}
