package com.nexusai.application.agent.oauth;

/**
 * 账号级 OAuth token 交换失败原因稳定码 · 同义映射 CC {@code MCPOAuthFlowErrorReason} 的
 * {@code provider_denied}/{@code token_exchange_failed}
 * （Open-ClaudeCode/src/services/mcp/auth.ts:87-91），但<b>独立</b>于 MCP 域枚举
 * {@link com.nexusai.application.agent.mcp.McpAuth.MCPOAuthFlowErrorReason}——账号级 OAuth 不依赖
 * MCP 编排包（泛化方案 §7 高风险：不得把 GitHub 伪装成 MCP server）。
 *
 * <p>CC 归因语义（mcp/auth.ts:1271-1280）：
 * <ul>
 *   <li>{@code token_exchange_failed}：token endpoint 出错
 *       （{@code authorizationCodeObtained → 'token_exchange_failed'} 行1271-1272）；</li>
 *   <li>{@code provider_denied}：授权回调 error 参数
 *       （{@code msg.includes('OAuth error:') → 'provider_denied'} 行1278-1280）。</li>
 * </ul>
 *
 * <p><b>注意</b>：CC 源码中 {@code provider_denied} 仅由授权回调 error 参数触发（非 token endpoint）；
 * 本枚举保留 PROVIDER_DENIED 供回调归因复用，token 响应解析器
 * （{@link OAuthTokenResponseParser}）仅对 {@code access_denied} 特例映射 PROVIDER_DENIED
 * （见该类 Javadoc 的 CC 冲突标注）。
 */
public enum OAuthTokenExchangeFailure {
    /** 授权被拒绝（资源所有者/授权服务器拒绝授权）· CC original: provider_denied。 */
    PROVIDER_DENIED,
    /** token 交换失败 · CC original: token_exchange_failed。 */
    TOKEN_EXCHANGE_FAILED
}
