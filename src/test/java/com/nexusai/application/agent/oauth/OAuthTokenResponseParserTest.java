package com.nexusai.application.agent.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S4 · {@link OAuthTokenResponseParser} 账号级 token 响应解析（provider 感知，规则九 WHY）。
 *
 * <p><b>WHY (意图验证)</b>: token 解析的 expiresAt/refreshToken 语义必须<b>由 provider 声明决定</b>，
 * 而非「无 expires_in → 不过期、无 refresh_token → 无刷新」的全局默认。测试锁定这些<b>为何重要</b>：
 * <ul>
 *   <li>① GitHub config 下<b>即使响应含 expires_in/refresh_token 也强制 null</b>——provider 语义
 *       覆盖字段存在性（GitHub OAuth App 永不过期无刷新，G-8）；</li>
 *   <li>② Google config 下 expires_in 存在→计算过期；缺失→fail-loud 抛 TOKEN_EXCHANGE_FAILED
 *       （绝不静默当「不过期」否则 Google token 永不刷新，对齐 CC formatTokens index.ts:178 无默认值）；</li>
 *   <li>③ Google config 下 refresh_token 存在→透传，缺失→null（不过期即重新授权）；</li>
 *   <li>④ config=null → fail-loud 抛 IllegalArgumentException（无语义声明无法判定）；</li>
 *   <li>⑤ error/access_token 缺失为 provider-agnostic 专项解析（RFC 6749 §5.1/§5.2）。</li>
 * </ul>
 */
class OAuthTokenResponseParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode node(String json) throws Exception {
        return JSON.readTree(json);
    }

    /** GitHub 语义 config：accessTokenExpires=false、supportsRefreshToken=false（永不过期无刷新）。 */
    private static TestOAuthProviderConfig githubConfig() {
        // S09 迁移：原构造的 provider 配置类已随登录镜像删除（D-OA-05/07），改用 TestOAuthProviderConfig 夹具。
        return TestOAuthProviderConfig.githubLike();
    }

    /** Google 语义 config：accessTokenExpires=true、supportsRefreshToken=true（会过期有刷新）。 */
    private static TestOAuthProviderConfig googleConfig() {
        // S09 迁移：原构造的 provider 配置类已随登录镜像删除（D-OA-05/07），改用 TestOAuthProviderConfig 夹具。
        return TestOAuthProviderConfig.googleLike();
    }

    @Test
    @DisplayName("GitHub config：响应含 expires_in/refresh_token 也强制 expiresAt/refreshToken=null（provider 语义覆盖字段存在性）")
    void githubConfig_ignoresExpiresInAndRefreshTokenEvenIfPresent() throws Exception {
        OAuthTokenResponse r = OAuthTokenResponseParser.parse(node(
            "{\"access_token\":\"gho_at\",\"token_type\":\"bearer\",\"scope\":\"read:user\","
                + "\"expires_in\":3600,\"refresh_token\":\"ghr_rt\"}"), githubConfig());

        assertThat(r.accessToken()).isEqualTo("gho_at");
        assertThat(r.expiresAt())
            .as("GitHub 声明不过期（accessTokenExpires=false）→ 即使响应含 expires_in 也恒 null，"
                + "不得读 expires_in 落过期时间")
            .isNull();
        assertThat(r.refreshToken())
            .as("GitHub 声明无刷新（supportsRefreshToken=false）→ 即使响应含 refresh_token 也恒 null")
            .isNull();
        assertThat(r.tokenType()).isEqualTo("bearer");
        assertThat(r.scope()).isEqualTo("read:user");
    }

    @Test
    @DisplayName("Google config：expires_in 存在 → expiresAt = now + expires_in*1000，refresh_token 透传")
    void googleConfig_presentExpiresInYieldsComputedExpiresAtAndRefreshToken() throws Exception {
        long before = System.currentTimeMillis();
        OAuthTokenResponse r = OAuthTokenResponseParser.parse(node(
            "{\"access_token\":\"ya29_at\",\"expires_in\":120,\"refresh_token\":\"1//rt\","
                + "\"token_type\":\"Bearer\",\"scope\":\"openid email profile\"}"), googleConfig());
        long after = System.currentTimeMillis();

        assertThat(r.accessToken()).isEqualTo("ya29_at");
        assertThat(r.expiresAt())
            .as("Google 会过期（accessTokenExpires=true）→ 读 expires_in 计算过期时间戳")
            .isBetween(before + 120 * 1000L, after + 120 * 1000L);
        assertThat(r.refreshToken())
            .as("Google 有刷新（supportsRefreshToken=true）→ refresh_token 透传")
            .isEqualTo("1//rt");
        assertThat(r.tokenType()).isEqualTo("Bearer");
        assertThat(r.scope()).isEqualTo("openid email profile");
    }

    @Test
    @DisplayName("Google config：expires_in 缺失 → 抛 TOKEN_EXCHANGE_FAILED（绝不静默当「不过期」）")
    void googleConfig_missingExpiresIn_throwsTokenExchangeFailed() throws Exception {
        assertThatThrownBy(() -> OAuthTokenResponseParser.parse(node(
            "{\"access_token\":\"ya29_at\",\"refresh_token\":\"1//rt\"}"), googleConfig()))
            .isInstanceOf(OAuthTokenExchangeError.class)
            .satisfies(e -> {
                OAuthTokenExchangeError err = (OAuthTokenExchangeError) e;
                assertThat(err.reason()).isEqualTo(OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
            });
    }

    @Test
    @DisplayName("Google config：refresh_token 缺失 → refreshToken=null（有刷新语义但本次未发放，非抛错）")
    void googleConfig_missingRefreshToken_yieldsNullRefreshToken() throws Exception {
        OAuthTokenResponse r = OAuthTokenResponseParser.parse(node(
            "{\"access_token\":\"ya29_at\",\"expires_in\":3600}"), googleConfig());

        assertThat(r.refreshToken())
            .as("Google 支持刷新但响应未带 refresh_token → 透传 null（对齐 CC client.ts:177 refreshToken nullable）")
            .isNull();
        assertThat(r.expiresAt()).isNotNull();
    }

    @Test
    @DisplayName("config=null → 抛 IllegalArgumentException（无 provider 语义声明，fail-loud）")
    void nullConfig_throwsIllegalArgumentException() throws Exception {
        assertThatThrownBy(() -> OAuthTokenResponseParser.parse(
            node("{\"access_token\":\"gho_at\"}"), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("{\"error\":\"bad_verification_code\"} → 抛 TOKEN_EXCHANGE_FAILED（provider-agnostic）")
    void badVerificationCode_throwsTokenExchangeFailed() throws Exception {
        assertThatThrownBy(() -> OAuthTokenResponseParser.parse(node(
            "{\"error\":\"bad_verification_code\",\"error_description\":\"The code passed is incorrect or expired.\"}"),
            githubConfig()))
            .isInstanceOf(OAuthTokenExchangeError.class)
            .satisfies(e -> {
                OAuthTokenExchangeError err = (OAuthTokenExchangeError) e;
                assertThat(err.errorCode()).isEqualTo("bad_verification_code");
                assertThat(err.reason()).isEqualTo(OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
            });
    }

    @Test
    @DisplayName("{\"error\":\"access_denied\"} → 抛 PROVIDER_DENIED（provider-agnostic）")
    void accessDenied_throwsProviderDenied() throws Exception {
        assertThatThrownBy(() -> OAuthTokenResponseParser.parse(node(
            "{\"error\":\"access_denied\",\"error_description\":\"The user has denied the request.\"}"),
            googleConfig()))
            .isInstanceOf(OAuthTokenExchangeError.class)
            .satisfies(e -> {
                OAuthTokenExchangeError err = (OAuthTokenExchangeError) e;
                assertThat(err.errorCode()).isEqualTo("access_denied");
                assertThat(err.reason()).isEqualTo(OAuthTokenExchangeFailure.PROVIDER_DENIED);
            });
    }

    @Test
    @DisplayName("access_token 缺失 → 抛 TOKEN_EXCHANGE_FAILED（RFC 6749 §5.1 必需，provider-agnostic）")
    void missingAccessToken_throwsTokenExchangeFailed() throws Exception {
        assertThatThrownBy(() -> OAuthTokenResponseParser.parse(node(
            "{\"token_type\":\"bearer\",\"expires_in\":120}"), githubConfig()))
            .isInstanceOf(OAuthTokenExchangeError.class)
            .satisfies(e -> {
                OAuthTokenExchangeError err = (OAuthTokenExchangeError) e;
                assertThat(err.reason()).isEqualTo(OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
            });
    }
}
