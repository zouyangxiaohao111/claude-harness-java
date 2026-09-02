package com.nexusai.infra.util;

/**
 * AgentIdFormatter · 对齐 CC utils/agentId.ts.
 *
 * <p>L1 语义: swarm/teammate 系统使用的确定性 ID 解析/构造。
 * <ul>
 *   <li>{@code formatAgentId(agentName, teamName)} → {@code "agentName@teamName"}</li>
 *   <li>{@code parseAgentId(agentId)} → 拆 (agentName, teamName) 或 null if 缺 {@code @}</li>
 *   <li>{@code generateRequestId(requestType, agentId)} → {@code "requestType-{ts}@agentId"}</li>
 *   <li>{@code parseRequestId(requestId)} → 拆 (requestType, timestamp, agentId) 或 null</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 静态方法;@ 字符作为分隔符</li>
 *   <li><b>A2 Golden Trace</b>: format "researcher" + "my-project" → "researcher@my-project";
 *       parseAgentId → null if 缺 @;request ID 含 timestamp parseInt 失败 → null</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output (timestamp 由调用方传入)</li>
 *   <li><b>A4 边界</b>: null agentId → null;空字符串 → null;timestamp 非 number → null (request)</li>
 *   <li><b>A5 业务场景</b>: teammate shutdown request "shutdown-1702500000000@researcher@my-project" → parseRequestId 解析</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS string.indexOf + slice → Java indexOf + substring;
 * TS Date.now() → Java long 入参 (test 注入);parseInt → NumberFormatException 防 守。
 */
public final class AgentIdFormatter {

    public static final char SEPARATOR = '@';

    private AgentIdFormatter() {}

    public static String formatAgentId(String agentName, String teamName) {
        return agentName + SEPARATOR + teamName;
    }

    public record AgentId(String agentName, String teamName) {}

    public static AgentId parseAgentId(String agentId) {
        if (agentId == null) return null;
        int atIdx = agentId.indexOf(SEPARATOR);
        if (atIdx == -1) return null;
        return new AgentId(
            agentId.substring(0, atIdx),
            agentId.substring(atIdx + 1));
    }

    public static String generateRequestId(String requestType, String agentId, long timestampMs) {
        return requestType + "-" + timestampMs + SEPARATOR + agentId;
    }

    public record RequestId(String requestType, long timestampMs, String agentId) {}

    public static RequestId parseRequestId(String requestId) {
        if (requestId == null) return null;
        int atIdx = requestId.indexOf(SEPARATOR);
        if (atIdx == -1) return null;
        String prefix = requestId.substring(0, atIdx);
        String agentId = requestId.substring(atIdx + 1);
        int lastDash = prefix.lastIndexOf('-');
        if (lastDash == -1) return null;
        String requestType = prefix.substring(0, lastDash);
        String timestampStr = prefix.substring(lastDash + 1);
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            return null;
        }
        return new RequestId(requestType, timestamp, agentId);
    }
}
