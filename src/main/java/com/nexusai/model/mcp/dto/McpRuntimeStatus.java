package com.nexusai.model.mcp.dto;

/**
 * MCP Server <b>运行时</b>连接状态（不落库）。
 *
 * <p>对齐 CC {@code MCPServerConnection.type} 五态（types.ts:221-226）并补一个本仓
 * 才需要的 {@link #stopped}：
 * <ul>
 *   <li>{@link #connected}：transport CONNECTED（CC connected）</li>
 *   <li>{@link #pending}：本进程正在连接（CC pending；连接窗口极短）</li>
 *   <li>{@link #failed}：本进程尝试失败（CC failed）</li>
 *   <li>{@link #needsAuth}：401 / 需 OAuth（CC needs-auth）</li>
 *   <li>{@link #disabled}：{@code enabled=false}（CC disabled）</li>
 *   <li>{@link #stopped}：启用但本进程没连（auto_start=false 或手动 stop；CC 无此态）</li>
 * </ul>
 *
 * <p>真源 = {@code McpConnectivityStatusRegistry}（连接生命周期驱动）+ {@code enabled} 列；
 * ⛔ 不再读写 {@code mcp_servers.status}（V295 已删该列）——旧值会跨进程残留成假 running。
 */
public enum McpRuntimeStatus {
    connected,
    pending,
    failed,
    needsAuth,
    disabled,
    stopped
}
