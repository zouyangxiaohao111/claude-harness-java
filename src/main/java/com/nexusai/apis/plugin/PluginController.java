package com.nexusai.apis.plugin;

import com.nexusai.application.agent.plugin.PluginInstallProgressTracker;
import com.nexusai.application.agent.plugin.PluginInstallService;
import com.nexusai.model.market.dto.PluginInstallRequest;
import com.nexusai.model.market.dto.PluginInstallResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 插件安装 REST 入口 · 前端「建科数字插件市场」安装按钮数据源。
 *
 * <p>POST /api/plugins/install body {pluginId, marketplaceUrl, scope} →
 * 后端自动 reconcile 外部（url）市场 + 装配安装链安装插件。
 * GET /api/plugins/installed → 已安装且启用的插件名（前端「已安装」标记）。
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    @Autowired
    private PluginInstallService pluginInstallService;

    /** 安装进度台账（GET /api/plugins/install/progress 数据源）。 */
    @Autowired
    private PluginInstallProgressTracker pluginInstallProgressTracker;

    /** 安装插件（marketplaceUrl 未物化时自动下载市场）。 */
    @PostMapping("/install")
    public PluginInstallResult install(@RequestBody PluginInstallRequest req) {
        return pluginInstallService.install(req);
    }

    /** 已安装且启用的插件名列表。 */
    @GetMapping("/installed")
    public List<String> installed() {
        return pluginInstallService.listInstalled();
    }

    /**
     * 已安装插件明细（含内容指纹）· 前端判定「市场里有更新」。
     *
     * <p>语义：市场条目 fingerprint ≠ 本地安装目录 fingerprint ⇒ 即使名称、版本号都相同，
     * 也说明内容被重新上架过，应显示「更新」。
     */
    @GetMapping("/installed/detail")
    public List<PluginInstallService.InstalledPluginDetail> installedDetail() {
        return pluginInstallService.listInstalledDetails();
    }

    /**
     * 安装进度（前端环形进度圈轮询）。
     *
     * @param pluginId 插件标识（name@marketplace）
     * @return 进度快照；无记录或终态已过期 → 204 No Content（前端据此停止轮询）
     */
    @GetMapping("/install/progress")
    public org.springframework.http.ResponseEntity<PluginInstallProgressTracker.Progress> installProgress(
            @RequestParam("pluginId") String pluginId) {
        PluginInstallProgressTracker.Progress p = pluginInstallProgressTracker.get(pluginId);
        return p == null
            ? org.springframework.http.ResponseEntity.noContent().build()
            : org.springframework.http.ResponseEntity.ok(p);
    }
}
