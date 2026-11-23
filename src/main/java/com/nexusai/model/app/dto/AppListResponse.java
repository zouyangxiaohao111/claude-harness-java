package com.nexusai.model.app.dto;

import java.util.List;

/**
 * {@code GET /api/v1/apps} 响应：应用目录（MCP server + 内置静态应用）。
 *
 * <p>前端同时接受裸数组与 {@code {apps:[...]}} 两种形状；这里用强类型包装（对齐本仓 DTO 约定）。
 *
 * @param apps 应用条目（顺序：MCP → 浏览器 → 桌面）
 */
public record AppListResponse(List<AppDto> apps) {
    public AppListResponse {
        apps = apps == null ? List.of() : List.copyOf(apps);
    }
}
