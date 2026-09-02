package com.nexusai.model.mcp.dto;

/**
 * OAuth 配置入参 · 对齐 CC {@code McpOAuthConfigSchema}（types.ts:43-56）。
 *
 * <p>字段契约：clientId? / callbackPort?(int>0) / authServerMetadataUrl?(https url) / xaa?(bool)。
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
    Boolean xaa
) {}
