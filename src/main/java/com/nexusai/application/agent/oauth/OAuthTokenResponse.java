package com.nexusai.application.agent.oauth;

/**
 * 账号级(first-party) OAuth token 响应解析产物 · nullable 语义。
 *
 * <p><b>为何不用 {@link com.nexusai.application.agent.mcp.McpAuth.Tokens}</b>：
 * {@code McpAuth.Tokens.expiresAt} 是 primitive {@code long}（无法表达『不过期』），且 MCP 域
 * {@code DefaultOAuthHttpClient.parseTokens} 强制 3600s 默认（对齐 CC saveTokens
 * {@code expiresAt: Date.now() + (tokens.expires_in || 3600) * 1000}，mcp/auth.ts:1724/1823 的
 * <b>MCP 域</b>语义）。账号级无此默认——expiresAt/refreshToken 的 null 语义由
 * {@link OAuthProviderConfig} 的 {@code accessTokenExpires()}/{@code supportsRefreshToken()} 声明决定，
 * <b>不再是「无 expires_in → 不过期」的全局默认</b>。
 * 若误复用 {@code McpAuth.Tokens} 会硬编码 0/默认值误判过期（即 S4 要修的 bug）。
 *
 * <p>CC 语义锚点（每个 null 分支的行为依据）：
 * <ul>
 *   <li>{@code expiresAt == null}（provider 声明不过期，如 GitHub）→ 永不判定过期：CC
 *       {@code isOAuthTokenExpired}（services/oauth/client.ts:344-353，
 *       {@code if (expiresAt === null) return false} 行345，bufferTime=5*60*1000 行349-351）；</li>
 *   <li>{@code refreshToken == null}（provider 声明不支持刷新，如 GitHub）→ 不刷新：CC
 *       {@code checkAndRefreshOAuthTokenIfNeededImpl}
 *       （utils/auth.ts:1459 {@code if (!tokens?.refreshToken || !isOAuthTokenExpired(...)) return false}
 *       + auth.ts:1464 {@code if (!tokens?.refreshToken) return false}）。</li>
 * </ul>
 *
 * @param accessToken  access_token · CC original: access_token（RFC 6749 §5.1 必需）
 * @param tokenType    token_type · CC original: token_type（可 null）
 * @param scope        scope · CC original: scope（可 null；CC parseScopes null→空数组 client.ts:42-44）
 * @param expiresAt    epoch millis · CC original: expires_at 语义；{@code null}=provider 声明不过期（GitHub）；
 *                     provider 声明会过期时必非 null（缺失 expires_in 由解析器 fail-loud 抛错）
 * @param refreshToken refresh_token · CC original: refresh_token；{@code null}=provider 声明不支持刷新（GitHub）
 */
public record OAuthTokenResponse(
    String accessToken,
    String tokenType,
    String scope,
    Long expiresAt,
    String refreshToken
) {}
