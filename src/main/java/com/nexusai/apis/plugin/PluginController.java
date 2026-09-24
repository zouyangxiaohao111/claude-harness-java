package com.nexusai.apis.plugin;

import com.nexusai.application.agent.plugin.PluginInstallService;
import com.nexusai.model.market.dto.PluginInstallRequest;
import com.nexusai.model.market.dto.PluginInstallResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
