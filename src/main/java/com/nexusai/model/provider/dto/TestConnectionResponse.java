package com.nexusai.model.provider.dto;

import java.util.Map;

/** Provider/MCP test 连接响应 */
public record TestConnectionResponse(
    boolean ok,
    Long latencyMs,
    String message,
    Map<String, Object> details
) {}
