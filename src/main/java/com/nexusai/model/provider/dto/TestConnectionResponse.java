package com.nexusai.model.provider.dto;

import java.util.List;
import java.util.Map;

/**
 * Provider/MCP test 连接响应。
 *
 * @param ok        是否通过
 * @param latencyMs 耗时（毫秒，可空）
 * @param message   人类可读说明
 * @param details   附加细节（可空）
 * @param tools     [2026-11-23] MCP 测试连接的真实 {@code tools/list} 工具名（非 MCP/失败时为空数组）。
 *                  前端 P2 按 {@code tools: string[]} 渲染真实工具清单（对齐 Octop 探测结果）。
 */
public record TestConnectionResponse(
    boolean ok,
    Long latencyMs,
    String message,
    Map<String, Object> details,
    List<String> tools
) {

    public TestConnectionResponse {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** 旧 4 参构造（Provider/DB 等非 MCP 测试）：tools 缺省空数组。 */
    public TestConnectionResponse(boolean ok, Long latencyMs, String message,
                                  Map<String, Object> details) {
        this(ok, latencyMs, message, details, List.of());
    }
}
