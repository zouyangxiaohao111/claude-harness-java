package com.nexusai.model.connector.dto;

import com.nexusai.model.connector.ConnectorToolInfo;

import java.util.List;

/**
 * 连接测试结果 · {@code POST /api/market/connectors/{id}/test}。
 *
 * <p>「测试连接」必须是一次真实探测：本接口会带 Bearer 实际请求连接器 MCP 端点，
 * 返回可达性与凭据有效性，而不是只看本地有没有存 token。
 *
 * @param ok         是否通过
 * @param message    人类可读说明（含 HTTP 状态码/协议版本/工具数等事实）
 * @param latencyMs  本次探测耗时（毫秒）
 * @param probeLevel {@code mcp_initialize}=initialize+tools/list 成功；
 *                   {@code connect_only}=能发起请求但 MCP 握手失败（含 401）；
 *                   {@code none}=尚未连接。前端按此区分文案，⛔ 不用 GET 探活冒充「测试连接」。
 * @param tools      真实 tools/list 结果（name + description）；未握手成功时为空数组。
 * @param toolCount  [2026-11-23] 工具数量（= tools.length；前端「工具 N 个」分支依赖它）
 * @param errorCode  [2026-11-23] 结构化错误码（可空）：no_credentials / auth_failed /
 *                   handshake_failed / unavailable / credential_only
 */
public record TestResponse(boolean ok, String message, long latencyMs, String probeLevel,
                           List<ConnectorToolInfo> tools, Integer toolCount, String errorCode) {

    public TestResponse {
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (toolCount == null) {
            toolCount = tools.size();
        }
    }

    /** 旧 5 参构造：toolCount 自动取 tools.length，errorCode 为空。 */
    public TestResponse(boolean ok, String message, long latencyMs, String probeLevel,
                        List<ConnectorToolInfo> tools) {
        this(ok, message, latencyMs, probeLevel, tools, null, null);
    }
}
