package com.nexusai.model.mcp.dto;

/**
 * OAuth 配置入参 · 对齐 CC {@code McpOAuthConfigSchema}（types.ts:43-56）。
 *
 * <p>字段契约：clientId? / callbackPort?(int>0) / authServerMetadataUrl?(https url) / xaa?(bool)
 * + [2026-11-23] scopes（空格分隔）/ authorizationEndpoint / tokenEndpoint（后两者给定时跳过自动发现）。
 * 与 Octop CustomMcpOAuthClient 对齐（client_id/client_secret/scope/authorization_endpoint/token_endpoint）。
 *
 * <p><b>callbackPort 用 String</b>（非 Integer）：对齐 CC addCommand.ts:156-166 ——
 * {@code parseInt(options.callbackPort, 10)} 得 NaN 为 falsy → oauth 条件与展开均短路 →
 * NaN 静默丢弃、不进 schema、不报错。Java 端 {@code Integer.parseInt} 抛
 * {@code NumberFormatException} 时在 Service 层静默置空（不报错），仅非 NaN 的非正整数
 * （如 -5 / 3.5）才触发 schema 报错。若此处用 Integer，Jackson 反序列化 "abc" 会直接 400，
 * 违反 CC「字母串被丢弃」语义（门禁修正 1）。
 */
public record McpOAuthRequest(
    String clientId,
    String callbackPort,              // 原始字符串；NaN 丢弃、非正整数 schema 拒绝
    String authServerMetadataUrl,
    Boolean xaa,
    String scopes,                    // 空格分隔（RFC 6749 §3.3），可空
    String authorizationEndpoint,     // 可空；与 tokenEndpoint 同时给出时跳过 discovery
    String tokenEndpoint              // 可空
) {
    /** 旧 4 参构造（既有调用方/测试）：新增 3 字段缺省 null。 */
    public McpOAuthRequest(String clientId, String callbackPort,
                           String authServerMetadataUrl, Boolean xaa) {
        this(clientId, callbackPort, authServerMetadataUrl, xaa, null, null, null);
    }
}
