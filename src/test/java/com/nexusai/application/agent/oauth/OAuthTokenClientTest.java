package com.nexusai.application.agent.oauth;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4 · {@link OAuthTokenClient} 账号级 token 交换/刷新客户端（provider-aware）行为验证。
 *
 * <p><b>WHY (意图验证，规则九)</b>: 账号级 token 交换与 MCP 域 {@code DefaultOAuthHttpClient} 的本质区别是
 * 产物语义——MCP 域返回 primitive-long {@code McpAuth.Tokens}（expires_in 缺失强制 3600s 默认），
 * 账号级返回 {@link OAuthTokenResponse}（nullable expiresAt/refreshToken，由 provider 声明决定）。测试锁定：
 * <ul>
 *   <li>① Google 形态 token JSON（expires_in + refresh_token）→ exchange 解析出非 null 过期 + 刷新，
 *       而非 3600s 默认（无 expires_in 时 provider 语义才决定）；</li>
 *   <li>② refreshTokens 走同一 provider-aware 解析链路（refresh grant 也是 form-urlencoded）；</li>
 *   <li>③ Content-Type 必须 {@code application/x-www-form-urlencoded}（CC client.ts:167 用 JSON 是
 *       Claude.ai 专属，Google/GitHub token endpoint 仅接受 form-urlencoded，属 provider 正确偏离）；</li>
 *   <li>④ 非 2xx → 抛 {@link OAuthTokenExchangeError}(TOKEN_EXCHANGE_FAILED)，fail-loud 不静默。</li>
 * </ul>
 */
class OAuthTokenClientTest {

    private static TestOAuthProviderConfig googleConfig() {
        // S09 迁移：原构造的 provider 配置类已随登录镜像删除（D-OA-05/07），改用 TestOAuthProviderConfig
        // 夹具（provider 语义矩阵：会过期有刷新），断言语义不变。
        return TestOAuthProviderConfig.googleLike();
    }

    private static TestOAuthProviderConfig githubConfig() {
        // S09 迁移：原构造的 provider 配置类已随登录镜像删除（D-OA-05/07），改用 TestOAuthProviderConfig
        // 夹具（provider 语义矩阵：永不过期无刷新），断言语义不变。
        return TestOAuthProviderConfig.githubLike();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(status);
        when(resp.body()).thenReturn(body);
        return resp;
    }

    @Test
    @DisplayName("exchange：Google 形态 JSON（expires_in+refresh_token）→ 解析非 null 过期 + 刷新，Content-Type form-urlencoded")
    void exchange_googleForm_parsesExpiresAndRefresh() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = response(200,
            "{\"access_token\":\"ya29_at\",\"token_type\":\"Bearer\",\"expires_in\":3599,"
                + "\"refresh_token\":\"1//rt\",\"scope\":\"openid email profile\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        OAuthTokenClient client = new OAuthTokenClient(http);
        long before = System.currentTimeMillis();
        OAuthTokenResponse r = client.exchangeCodeForTokens(
            "https://oauth2.googleapis.com/token", Map.of(
                "grant_type", "authorization_code",
                "code", "google-code",
                "client_id", "google-client-id",
                "client_secret", "google-client-secret"), googleConfig());
        long after = System.currentTimeMillis();

        assertThat(r.accessToken()).isEqualTo("ya29_at");
        assertThat(r.expiresAt())
            .as("Google 会过期（accessTokenExpires=true）→ expires_in 计算过期时间戳，非 3600s 默认")
            .isBetween(before + 3599 * 1000L, after + 3599 * 1000L);
        assertThat(r.refreshToken())
            .as("Google 有刷新（supportsRefreshToken=true）→ refresh_token 透传")
            .isEqualTo("1//rt");
        assertThat(r.scope()).isEqualTo("openid email profile");

        // Content-Type 必须 form-urlencoded（CC client.ts:167 JSON 是 Claude.ai 专属，Google 仅接受表单）
        ArgumentCaptor<HttpRequest> reqCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(reqCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(reqCaptor.getValue().headers().firstValue("Content-Type"))
            .hasValue("application/x-www-form-urlencoded");
    }

    @Test
    @DisplayName("refresh：走同一 provider-aware 解析链路（refresh grant form-urlencoded → 解析 expires_in/refresh_token）")
    void refresh_parsesExpiresAndRefresh() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = response(200,
            "{\"access_token\":\"ya29_new\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
                + "\"refresh_token\":\"1//rt2\",\"scope\":\"openid\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        OAuthTokenClient client = new OAuthTokenClient(http);
        OAuthTokenResponse r = client.refreshTokens(
            "https://oauth2.googleapis.com/token", Map.of(
                "grant_type", "refresh_token",
                "refresh_token", "1//rt",
                "client_id", "google-client-id"), googleConfig());

        assertThat(r.accessToken()).isEqualTo("ya29_new");
        assertThat(r.expiresAt()).isNotNull();
        assertThat(r.refreshToken()).isEqualTo("1//rt2");
    }

    @Test
    @DisplayName("非 2xx → 抛 OAuthTokenExchangeError(TOKEN_EXCHANGE_FAILED)，fail-loud 不静默")
    void non2xx_throwsTokenExchangeFailed() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = response(400,
            "{\"error\":\"invalid_grant\",\"error_description\":\"Bad Request\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        OAuthTokenClient client = new OAuthTokenClient(http);
        assertThatThrownBy(() -> client.exchangeCodeForTokens(
            "https://oauth2.googleapis.com/token", Map.of(
                "grant_type", "authorization_code", "code", "bad"), googleConfig()))
            .isInstanceOf(OAuthTokenExchangeError.class)
            .satisfies(e -> {
                OAuthTokenExchangeError err = (OAuthTokenExchangeError) e;
                assertThat(err.reason()).isEqualTo(OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
            });
    }

    @Test
    @DisplayName("2xx 但 body 含 error 字段 → 解析器抛 OAuthTokenExchangeError 原样上抛（200+error 语义）")
    void http200WithErrorBody_propagatesParserError() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = response(200,
            "{\"error\":\"bad_verification_code\",\"error_description\":\"expired\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        OAuthTokenClient client = new OAuthTokenClient(http);
        assertThatThrownBy(() -> client.exchangeCodeForTokens(
            "https://github.com/login/oauth/access_token", Map.of(
                "grant_type", "authorization_code", "code", "bad"), githubConfig()))
            .isInstanceOf(OAuthTokenExchangeError.class)
            .satisfies(e -> {
                OAuthTokenExchangeError err = (OAuthTokenExchangeError) e;
                assertThat(err.errorCode()).isEqualTo("bad_verification_code");
            });
    }
}
