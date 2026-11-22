package com.nexusai.model.mcp.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 响应：MCP Server 完整信息 */
public record McpServerDto(
    String id,
    String name,
    String command,
    List<String> args,
    Map<String, String> env,
    McpStatus status,
    String lastError,
    boolean enabled,
    OffsetDateTime createdAt,
    String type,            // 'stdio'|'sse'|'sse-ide'|'ws-ide'|'http'|'ws'|'sdk'|'claudeai-proxy'
    String approvalStatus,  // 'approved'|'rejected'|'pending'
    // ── gap31 前端缺口 · server DTO 补 userFacingName / channelPermissions（纯派生，无新 DB 列）──
    // CC original: userFacingName()（client.ts:1972-1976，server 级 = client.name = server 名）
    String userFacingName,
    // CC original: isChannelPermissionRelayEnabled()；Java = settings.channels_permission_relay_enabled（V334，默认开）
    boolean channelPermissions,
    String url,             // 远程反解：type≠stdio 取 command 列
    Map<String, String> headers,   // 远程反解：env 列（剥保留键）
    Map<String, Object> oauth,     // 远程 oauth（来自 .mcp.json 条目，DB 无 oauth 列）
    String scope,           // 写目标 scope（create/update 特有）
    String filePath,        // = describeMcpConfigFilePath(scope)（create/update 特有）
    List<String> warnings,  // create/update 特有，stdio 非阻断警告
    // ── [V295] 启动自启（本仓扩展层）+ 运行时状态（不落库）──
    //   status 保留三态映射（running/stopped/error）供既有前端消费；
    //   runtimeStatus 是更细的运行时真值（connected/pending/failed/needsAuth/disabled/stopped）。
    boolean autoStart,
    McpRuntimeStatus runtimeStatus
) {
    public McpServerDto {
        warnings = warnings == null ? List.of() : warnings;
    }

    /** 既有 11 参便捷构造（list/get：userFacingName/channelPermissions + url/headers/oauth/scope/filePath/warnings 缺省）。 */
    public McpServerDto(String id, String name, String command, List<String> args,
                        Map<String, String> env, McpStatus status, String lastError,
                        boolean enabled, OffsetDateTime createdAt, String type,
                        String approvalStatus) {
        this(id, name, command, args, env, status, lastError, enabled, createdAt,
            type, approvalStatus, null, false, null, null, null, null, null, null,
            false, McpRuntimeStatus.stopped);
    }
}
