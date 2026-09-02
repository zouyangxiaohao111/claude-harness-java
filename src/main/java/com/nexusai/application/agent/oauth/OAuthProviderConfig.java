package com.nexusai.application.agent.oauth;

import java.util.List;
import java.util.Map;

/**
 * 通用 OAuth provider 配置抽象 · 泛化自 CC {@code getOauthConfig()}「配置源 + env 覆盖」模式
 * （Open-ClaudeCode/src/constants/oauth.ts:186-234）。provider 配置实例承载
 * 各 provider 的语义声明，本接口不写死任何 provider 专用逻辑。
 *
 * <p><b>七核心</b>（CC 对应行号见各方法 Javadoc）：
 * provider / authorizationEndpoint / tokenEndpoint / userInfoEndpoint /
 * clientId / clientSecret / scopes / redirectUri。
 *
 * <p><b>三可选扩展点</b>（default 方法，真实授权码交换 / 身份解析在 S3/S4 编排层落地，
 * S1 仅声明契约）：
 * <ul>
 *   <li>{@link #isTokenExpired(Long)}：给 CC 5min-buffer 默认实现（client.ts:344-353）；</li>
 *   <li>{@link #exchangeParams(String, String, String)} / {@link #userInfo(String)}：
 *       default 抛 {@link UnsupportedOperationException}，provider 未实现时显式失败。</li>
 * </ul>
 *
 * <p><b>token 生命周期语义声明（provider-aware 解析的依据）</b>：
 * {@link #accessTokenExpires()} 与 {@link #supportsRefreshToken()} 两个 default 方法声明
 * 「本 provider 的 access token 是否会过期 / 是否支持刷新」——token 响应解析器
 * {@link OAuthTokenResponseParser} 依据这两个声明决定 expiresAt/refreshToken 是否解析，
 * <b>不再把「无 expires_in/无 refresh_token → 全局默认 null」当作无条件规则</b>。
 * 接口默认 = {@code true/true}（RFC 6749 标准：expires_in RECOMMENDED §5.1、refresh_token §6），
 * 永不过期无刷新的 provider（如 GitHub OAuth App）由配置实例显式覆写 {@code false/false}。
 *
 * <p>纯域接口（无 Spring 注解），与 mcp 包 provider 无关原语（McpOAuth / OauthPort）分层，
 * 不迁移既有类。
 */
public interface OAuthProviderConfig {

    /**
     * provider 标识 · CC original: 无直接对应（CC 单 provider 无此抽象）；
     * provider 配置实例返回唯一标识（未来多 provider 按名区分配置前缀）。
     *
     * @return provider 名（唯一标识，未来多 provider 按名区分配置前缀）
     */
    String provider();

    /**
     * 授权端点 · CC original: CONSOLE_AUTHORIZE_URL (oauth.ts:86) / CLAUDE_AI_AUTHORIZE_URL
     * (oauth.ts:89) 二选一的 provider 泛化。GitHub: {@code https://github.com/login/oauth/authorize}。
     *
     * @return 授权码流 authorization endpoint（RFC 6749 §4.1.1）
     */
    String authorizationEndpoint();

    /**
     * 令牌端点 · CC original: TOKEN_URL (oauth.ts:91)。GitHub:
     * {@code https://github.com/login/oauth/access_token}。
     *
     * @return token exchange endpoint（RFC 6749 §4.1.3）
     */
    String tokenEndpoint();

    /**
     * userinfo 端点 · provider 泛化（CC 无单一 userinfo 字段，经 getOauthProfileFromOauthToken
     * 取 profile，见 client.ts fetchProfileInfo）。GitHub: {@code https://api.github.com/user}。
     *
     * @return userinfo endpoint
     */
    String userInfoEndpoint();

    /**
     * 客户端 ID · CC original: CLIENT_ID (oauth.ts:99 prod / :138 staging / :169 local 三处
     * 硬编码，本项泛化掉) + env 覆盖 CLAUDE_CODE_OAUTH_CLIENT_ID (oauth.ts:225-231)。
     * Java 侧仅从 application.yml {@code ${GITHUB_OAUTH_CLIENT_ID:}} / env 注入，
     * <b>禁止硬编码字面量</b>。
     *
     * @return client id（env/yml 注入，未配置时为空串）
     */
    String clientId();

    /**
     * 客户端密钥 · provider 泛化（CC public client 走 PKCE 无 client_secret；GitHub confidential
     * client 需要）。仅 env/yml 注入，禁止硬编码字面量。
     *
     * @return client secret（env/yml 注入，未配置时为空串）
     */
    String clientSecret();

    /**
     * 作用域列表 · CC original: ALL_OAUTH_SCOPES (oauth.ts:56-58) + parseScopes
     * (client.ts:42-44：空格分隔字符串→数组，null→空数组)。Java 侧 {@link List}{@code <String>}，
     * yml 单值 {@code read:user} 绑定为单元素列表。
     *
     * @return 请求 scope 列表
     */
    List<String> scopes();

    /**
     * 回调 URI · RFC 6749 §4.1.1 必填参数 + CC buildAuthUrl 无条件 append（client.ts:76-80：
     * {@code isManual ? MANUAL_REDIRECT_URL (oauth.ts:98) : http://localhost:${port}/callback}）。
     *
     * @return redirect_uri（授权码流必填）
     */
    String redirectUri();

    /**
     * token 过期判定（可选扩展点，默认实现逐字对齐 CC isOAuthTokenExpired client.ts:344-353）：
     * {@code expiresAt == null} → 不过期（:345-347）；否则 {@code now + 5min buffer >= expiresAt}
     * 视为过期（:349-352，提前 5min bufferTime 判定，防边界抖动）。
     *
     * @param expiresAt token 过期时间戳（epoch ms，null 表示永不过期）
     * @return true 已过期（或即将在 5min buffer 内过期）
     */
    default boolean isTokenExpired(Long expiresAt) {
        if (expiresAt == null) {
            return false;
        }
        return System.currentTimeMillis() + 5 * 60 * 1000L >= expiresAt;
    }

    /**
     * access token 是否会过期（provider 级语义声明）· 接口默认 {@code true}。
     *
     * <p><b>CC original</b>：RFC 6749 §5.1 将 {@code expires_in} 标为 RECOMMENDED，first-party CC
     * {@code formatTokens}（services/oauth/index.ts:178）无条件 {@code Date.now() + response.expires_in * 1000}
     * —— 无 3600 默认，且过期判定 {@code isOAuthTokenExpired}（services/oauth/client.ts:344-353）
     * 只在 {@code expiresAt === null} 时返回 false（不过期）。故「会过期」是标准 provider 的默认语义，
     * 「不过期」须由 provider（如 GitHub）显式覆写 {@code false} 声明。
     *
     * @return true=会过期（token 响应应含 expires_in，缺失时解析器 fail-loud）；false=永不过期（expiresAt 恒 null）
     */
    default boolean accessTokenExpires() {
        return true;
    }

    /**
     * 是否支持 refresh_token（provider 级语义声明）· 接口默认 {@code true}。
     *
     * <p><b>CC original</b>：RFC 6749 §6 refresh token grant 定义 refresh_token 为可选发放；
     * first-party CC 刷新链 {@code checkAndRefreshOAuthTokenIfNeededImpl}
     * （utils/auth.ts:1459/1464）在 {@code !tokens?.refreshToken} 时直接 return false（不刷新）。
     * 故「支持刷新」是标准 provider 的默认语义，「不支持刷新」须由 provider（如 GitHub）显式覆写
     * {@code false} 声明（GitHub OAuth App 不发放 refresh_token，见 G-8）。
     *
     * @return true=支持刷新（token 响应应含 refresh_token，缺失时透传 null）；false=无刷新（refreshToken 恒 null）
     */
    default boolean supportsRefreshToken() {
        return true;
    }

    /**
     * 授权码交换请求参数（可选扩展点，S4 落地）· CC original: exchangeCodeForTokens 请求体
     * (client.ts:115-124)：{@code grant_type=authorization_code + code + redirect_uri + client_id
     * + code_verifier + state}。S1 仅声明契约，未实现网络编排。
     *
     * @param code         授权码
     * @param redirectUri  回调 URI
     * @param codeVerifier PKCE verifier
     * @return 请求体参数 map
     * @throws UnsupportedOperationException provider 未实现授权码交换参数时显式失败（fail-loud）
     */
    default Map<String, String> exchangeParams(String code, String redirectUri, String codeVerifier) {
        throw new UnsupportedOperationException("provider 未实现授权码交换参数");
    }

    /**
     * userInfo 解析（可选扩展点，S4 落地）· CC original: fetchProfileInfo
     * (client.ts:355-420，经 getOauthProfileFromOauthToken 取 profile)。S1 仅声明契约。
     *
     * @param accessToken 访问令牌
     * @return userinfo 解析结果
     * @throws UnsupportedOperationException provider 未实现 userInfo 解析时显式失败（fail-loud）
     */
    default String userInfo(String accessToken) {
        throw new UnsupportedOperationException("provider 未实现 userInfo 解析");
    }
}
