package com.nexusai.model.app.dto;

import java.util.List;

/**
 * {@code GET /api/v1/tools/capabilities} 响应：CU/BR 能力目录 + 浏览器扩展真实连接态。
 *
 * @param capabilities CU/BR 条目（顺序：CU → BR）
 * @param browser      浏览器扩展连接态（WS 真值；不再让前端按工具数 0 猜）
 */
public record ToolCapabilitiesResponse(
    List<ToolCapabilityDto> capabilities,
    BrowserCapabilityDto browser
) {
    public ToolCapabilitiesResponse {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
