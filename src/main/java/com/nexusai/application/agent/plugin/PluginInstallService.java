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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
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

    /** 命令/技能注册表（惰性，避免装配期循环依赖）· 安装成功后清缓存，新插件技能立即可召唤。 */
    @Autowired(required = false)
    private com.nexusai.application.agent.skill.SkillRegistry skillRegistry;

    /** 安装进度台账（前端环形进度圈轮询数据源）。 */
    @Autowired
    private PluginInstallProgressTracker progressTracker;

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
            progressTracker.begin(pluginId);
            // 1. 确保市场索引物化（下载 marketplace.json，几 KB；已物化则复用缓存目录）
            String marketName = PluginIdentifier.parse(pluginId).marketplace();
            String installLocation = ensureMarketplace(marketplaceUrl, marketName);

            // 2. 按需下载目标插件（source 目录下的 plugin.zip）→ 缓存市场目录对应位置
            java.nio.file.Path downloadedSource = null;
            if (!marketplaceUrl.isEmpty()) {
                downloadedSource = downloadTargetPlugin(marketplaceUrl, pluginId, installLocation);
            }
            progressTracker.stage(pluginId, PluginInstallProgressTracker.Stage.EXTRACTING, 80, "解压完成");

            // 3. 装配安装链并安装
            PluginInstaller installer = new PluginInstaller();
            installer.setMarketplaceManager(marketplaceManager);
            installer.setInstalledPluginsManager(installedPluginsManager);
            installer.setCacheUtils(new PluginCacheUtils());
            installer.setDependencyResolver(new PluginDependencyResolver());
            PluginOperations ops = new PluginOperations();
            ops.wireInstallation(marketplaceManager, installer);

            progressTracker.stage(pluginId, PluginInstallProgressTracker.Stage.INSTALLING, 85, "安装注册");
            PluginOperations.InstallResult r = ops.installPlugin(pluginId, scope);
            if (r.success()) {
                // 安装成功后清掉市场缓存里的解压副本（真正安装目录在 plugins/cache/<market>/<plugin>/<version>）
                cleanupDownloadedSource(downloadedSource, installLocation, pluginId);
                progressTracker.stage(pluginId, PluginInstallProgressTracker.Stage.REFRESHING, 95, "刷新技能与命令");
                refreshPlugins();
                progressTracker.done(pluginId);
            } else {
                progressTracker.fail(pluginId, r.message());
            }
            if (log.isInfoEnabled()) {
                log.info("[PluginInstall] 安装结果 success={} plugin={} msg={}", r.success(), pluginId, r.message());
            }
            return new PluginInstallResult(r.success(), r.message(), r.pluginId(), r.pluginName(), r.scope());
        } catch (IllegalArgumentException e) {
            progressTracker.fail(pluginId, "非法参数: " + e.getMessage());
            return new PluginInstallResult(false, "非法参数: " + e.getMessage(), null, null, null);
        } catch (IOException e) {
            log.error("[PluginInstall] 安装失败 plugin={}: {}", pluginId, e.getMessage());
            progressTracker.fail(pluginId, "安装失败（" + e.getMessage() + "）");
            return new PluginInstallResult(false, "安装失败（" + e.getMessage() + "）", null, null, null);
        } catch (RuntimeException e) {
            // 显式失败：运行时异常不吞（原逻辑交由上层 500），但进度台账必须落终态，
            // 否则前端轮询会一直停在中间百分比，看不出安装已经失败。
            progressTracker.fail(pluginId, "安装异常: " + e.getMessage());
            throw e;
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

    /**
     * 已安装插件的明细（含**内容指纹**）· 前端「市场里有更新」判定数据源。
     *
     * <p>为什么要指纹而不是版本号：市场与本地可能同名同版本、但内容已被重新上架
     * （用户 2026-10-21：「名称一致 但是指纹不一致 需要显示更新」）。版本号相同不代表内容相同，
     * 只有按内容算出的 SHA-256 能判定。
     *
     * @return 已启用插件明细（name/version/installPath/fingerprint）；目录不可读时 fingerprint=null
     */
    public List<InstalledPluginDetail> listInstalledDetails() {
        List<InstalledPluginDetail> out = new java.util.ArrayList<>();
        for (InstalledPluginsManager.InstalledRecord rec : installedPluginsManager.list()) {
            if (rec == null || !rec.enabled()) {
                continue;
            }
            String fp = null;
            String path = rec.installPath();
            if (path != null && !path.isBlank()) {
                try {
                    java.nio.file.Path dir = java.nio.file.Paths.get(path);
                    if (java.nio.file.Files.isDirectory(dir)) {
                        fp = com.nexusai.application.market.MarketPublishService.fingerprint(dir);
                    }
                } catch (Exception e) {
                    log.warn("[PluginInstall] 计算已装插件指纹失败 plugin={} path={}: {}",
                        rec.name(), path, e.getMessage());
                }
            }
            out.add(new InstalledPluginDetail(rec.name(), rec.version(), path, fp));
        }
        return out;
    }

    /**
     * 已安装插件明细。
     *
     * @param name        插件名（市场条目名）
     * @param version     已装版本
     * @param installPath 安装目录
     * @param fingerprint 内容指纹 {@code sha256:...}（不可读时为 null）
     */
    public record InstalledPluginDetail(String name, String version, String installPath, String fingerprint) {
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
            if (skillRegistry != null) {
                // 清命令聚合三层缓存（allCommands/modelInvocable/slashToolSkills）：
                // 否则 GET /api/command 仍返回安装前的列表，前端「召唤」会报
                // 「该技能未注册成斜杠命令」，必须重启后端才恢复。
                skillRegistry.refresh();
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
                syncManifestToDotClaude(existing.installLocation());
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
        syncManifestToDotClaude(after.installLocation());
        if (log.isInfoEnabled()) {
            log.info("[PluginInstall] 市场索引已物化: {} → {}", marketName, after.installLocation());
        }
        return after.installLocation();
    }

    /**
     * 同步市场索引到 CC 规范读取路径。
     *
     * <p>下载器写 {@code <market>/marketplace.json}，而 {@link PluginMarketplace} 的缓存读取用
     * {@code <market>/.claude-plugin/marketplace.json}。两份不同步时：磁盘上新清单已含某插件，
     * 安装仍按旧清单报「市场 'zjky-market' 中没有插件: xxx」（2026-10-19 实测
     * zjky-software-design-skill-chain 安装失败）。
     */
    private static void syncManifestToDotClaude(String installLocation) throws IOException {
        Path root = Paths.get(installLocation, "marketplace.json");
        if (!Files.isRegularFile(root)) {
            return;
        }
        Path dotClaudeDir = Paths.get(installLocation, ".claude-plugin");
        Files.createDirectories(dotClaudeDir);
        Files.copy(root, dotClaudeDir.resolve("marketplace.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 按需下载目标插件包：从缓存 marketplace.json 取插件 entry.source（相对目录），
     * 拼接 {@code <base>/<source>/plugin.zip}，下载解压到缓存市场目录的 source 位置。
     * 只下载目标插件一个包（几十 KB），不碰其他插件。
     */
    private java.nio.file.Path downloadTargetPlugin(String marketplaceUrl, String pluginId,
                                                    String installLocation) throws IOException {
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
        new MarketplaceHttpDownloader().downloadArchive(pluginUrl, null, target.toString(),
            (read, total) -> progressTracker.downloading(pluginId, read, total));
        if (log.isInfoEnabled()) {
            log.info("[PluginInstall] 已按需下载插件包 {} → {}", pluginName, target);
        }
        return target;
    }

    /**
     * 安装成功后清理市场缓存里的解压副本。
     *
     * <p><b>WHY</b>（2026-10-26 用户实测）：插件真正安装目录是
     * {@code plugins/cache/<market>/<plugin>/<version>}；{@code plugins/marketplaces/<market>/<source>}
     * 只是下载中转。不清理会每装一个版本就在市场缓存里留一份整包（与已装目录重复占用）。
     * 只删本次下载且确认位于市场缓存根内的目录；失败仅 warn，不影响安装结果。
     */
    private void cleanupDownloadedSource(java.nio.file.Path downloadedSource,
                                         String installLocation, String pluginId) {
        if (downloadedSource == null) {
            return;
        }
        try {
            java.nio.file.Path root = Paths.get(installLocation).toAbsolutePath().normalize();
            java.nio.file.Path target = downloadedSource.toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                log.warn("[PluginInstall] 跳过清理：下载目录不在市场缓存内 target={} root={}", target, root);
                return;
            }
            PluginInstaller.deleteRecursively(target);
            if (log.isInfoEnabled()) {
                log.info("[PluginInstall] 已清理市场缓存解压副本 plugin={} path={}", pluginId, target);
            }
        } catch (Exception e) {
            log.warn("[PluginInstall] 清理市场缓存解压副本失败（不影响安装） plugin={} path={}: {}",
                pluginId, downloadedSource, e.getMessage());
        }
    }
}
