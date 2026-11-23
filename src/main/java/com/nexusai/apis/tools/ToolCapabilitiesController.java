package com.nexusai.apis.tools;

import com.nexusai.application.agent.app.ToolCapabilitiesService;
import com.nexusai.model.app.dto.ToolCapabilitiesResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具能力目录 REST · {@code GET /api/v1/tools/capabilities}。
 *
 * <p>返回 CU / BR 的已知工具名、未注册原因，以及浏览器扩展的真实 WS 连接态。
 * ⛔ 只读：不改变「flag 关不注册」的注册语义。
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolCapabilitiesController {

    private final ToolCapabilitiesService toolCapabilitiesService;

    public ToolCapabilitiesController(ToolCapabilitiesService toolCapabilitiesService) {
        this.toolCapabilitiesService = toolCapabilitiesService;
    }

    @GetMapping("/capabilities")
    public ToolCapabilitiesResponse capabilities() {
        return toolCapabilitiesService.capabilities();
    }
}
