package com.nexusai.model.app.dto;

/**
 * 浏览器扩展连接态（真实 WS 连接，不是按工具数推测）。
 *
 * @param connected 是否有 open 的全局扩展连接
 * @param lastSeen  最近一次扩展活动（ISO-8601）；从未连接 → null
 */
public record BrowserCapabilityDto(boolean connected, String lastSeen) {}
