package com.nexusai.model.mcp.dto;

import java.util.Map;

/**
 * POST /api/v1/mcp/import 请求 · .mcp.json 导入（Q-09=C：.mcp.json 仅导入入口）。
 *
 * <p>{@code files} = scope → .mcp.json 绝对路径；多 scope 按 CC 优先级合并
 * （enterprise 独占 > local > project > user），同名 server local 版本胜出。
 */
public record McpImportRequest(
    Map<String, String> files
) {}
