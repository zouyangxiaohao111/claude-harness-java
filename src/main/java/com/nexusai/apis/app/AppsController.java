package com.nexusai.apis.app;

import com.nexusai.application.agent.app.AppCatalogService;
import com.nexusai.model.app.dto.AppActionResult;
import com.nexusai.model.app.dto.AppListResponse;
import com.nexusai.model.app.dto.AppSessionActionRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用目录 REST · {@code GET /api/v1/apps}。
 *
 * <p><b>应用层只含 MCP</b>（浏览器 / computer-use 回工具面板，不在此列出）。工具可见性的门 =
 * 会话级 {@code sessions.enabled_apps}（持久）；每轮 {@code selectedApps} 仅是「强调」，
 * 不改 schema。本接口只提供目录与可用性，不改任何状态。
 */
@RestController
@RequestMapping("/api/v1/apps")
public class AppsController {

    private final AppCatalogService appCatalogService;

    public AppsController(AppCatalogService appCatalogService) {
        this.appCatalogService = appCatalogService;
    }

    @GetMapping
    public AppListResponse list() {
        return new AppListResponse(appCatalogService.listApps());
    }

    /**
     * 启动应用（会话级）：启用并连接 MCP server + 写入该会话 enabled_apps。
     * 失败（pending 审批 / 连接失败）如实返回 {@code started=false + message}，不改会话门。
     */
    @PostMapping("/{key}/start")
    public AppActionResult start(@PathVariable String key,
                                 @Valid @RequestBody AppSessionActionRequest req) {
        return appCatalogService.startApp(key, req.sessionId());
    }

    /** 停止应用（会话级）：仅从 enabled_apps 移除，⛔ 不全局停 server。 */
    @PostMapping("/{key}/stop")
    public AppActionResult stop(@PathVariable String key,
                                @Valid @RequestBody AppSessionActionRequest req) {
        return appCatalogService.stopApp(key, req.sessionId());
    }
}
