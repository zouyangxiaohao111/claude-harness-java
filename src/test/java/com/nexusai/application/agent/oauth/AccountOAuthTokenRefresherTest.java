package com.nexusai.application.agent.oauth;

import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-7 · {@link AccountOAuthTokenRefresher} 账号级 401 强制刷新器（provider-aware）行为验证。
 *
 * <p><b>WHY (规则九 · 意图验证)</b>: WF-7 的核心是把账号级 401 刷新通道从「硬编码 no-op」改为
 * 「数据驱动的 provider 感知刷新」——有 refresh_token 的 provider（Google）走真实 refresh_token grant
 * 刷新并写回存储；无 refresh_token 的 provider（GitHub）保持 401 重新授权。测试锁定的是
 * <b>「刷新完全由存储中 refresh_token 是否存在驱动」</b>这一 CC 数据驱动语义
 * （utils/auth.ts:1459/1464 {@code if (!tokens?.refreshToken) return false}），而非某个 provider enum：
 * <ul>
 *   <li>GREEN ① 有 refresh_token → {@link OAuthTokenClient#refreshTokens} 携带 refresh grant 参数，
 *       写回新 accessToken/expiresAt/refreshToken（激活真实刷新通道）；</li>
 *   <li>GREEN ② 刷新响应<b>缺 refresh_token</b> → 保留旧 refreshToken（CC client.ts:178
 *       {@code refresh_token: newRefreshToken = refreshToken}——Google 刷新通常不再下发 refresh_token，
 *       必须保留旧值否则「一次性刷新」）；</li>
 *   <li>GREEN ③ 存储无 refresh_token（GitHub）→ false，<b>不调用</b> tokenClient（无刷新即重新授权）；</li>
 *   <li>GREEN ④ 刷新抛错 → false（fail-soft 降级，CC 刷新失败返回 false）。</li>
 * </ul>
 */
class AccountOAuthTokenRefresherTest {

    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";

    private static TestOAuthProviderConfig googleConfig() {
        // S09 迁移：原构造的 provider 配置类已随登录镜像删除（D-OA-07），改用 TestOAuthProviderConfig
        // 夹具（provider=google、tokenEndpoint=GOOGLE_TOKEN、clientId/scopes 沿用测试桩值）。
        return TestOAuthProviderConfig.googleLike();
    }

    private static AccountOAuthToken storedToken(String refreshToken) {
        AccountOAuthToken t = new AccountOAuthToken();
        t.setProvider("google");
        t.setIdentity("alice@example.com");
        t.setAccessToken("old-at");
        t.setRefreshToken(refreshToken);
        t.setExpiresAt(System.currentTimeMillis() - 1000L); // 已过期
        t.setScope("openid email profile");
        return t;
    }

    @Test
    @DisplayName("GREEN ① 有 refresh_token → refresh grant 刷新并写回新 token（激活真实刷新通道，非 no-op）")
    void refreshWithRefreshToken_refreshesAndSaves() {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        when(tokenService.readLatest("google")).thenReturn(storedToken("old-rt"));

        OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);
        long newExpiresAt = System.currentTimeMillis() + 3599_000L;
        when(tokenClient.refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class)))
            .thenReturn(new OAuthTokenResponse("new-at", "Bearer", "openid", newExpiresAt, "new-rt"));

        AccountOAuthTokenRefresher refresher = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService);

        assertThat(refresher.forceRefresh()).as("有 refresh_token 应刷新成功").isTrue();

        // refresh grant 参数对齐 CC client.ts:150-163（grant_type/refresh_token/client_id/scope）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
        verify(tokenClient).refreshTokens(eq(GOOGLE_TOKEN), params.capture(), any(OAuthProviderConfig.class));
        assertThat(params.getValue())
            .containsEntry("grant_type", "refresh_token")
            .containsEntry("refresh_token", "old-rt")
            .containsEntry("client_id", "google-client-id")
            .containsEntry("client_secret", "google-client-secret")
            .containsEntry("scope", "openid email profile");

        // 写回：新 accessToken + 轮换 refreshToken + 新 expiresAt，保留 provider|identity 复合键
        ArgumentCaptor<AccountOAuthToken> saved = ArgumentCaptor.forClass(AccountOAuthToken.class);
        verify(tokenService).save(saved.capture());
        assertThat(saved.getValue().getProvider()).isEqualTo("google");
        assertThat(saved.getValue().getIdentity()).isEqualTo("alice@example.com");
        assertThat(saved.getValue().getAccessToken()).isEqualTo("new-at");
        assertThat(saved.getValue().getRefreshToken()).isEqualTo("new-rt");
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(newExpiresAt);
    }

    @Test
    @DisplayName("GREEN ② 刷新响应缺 refresh_token → 保留旧 refreshToken（CC client.ts:178，防一次性刷新）")
    void refreshWithoutRefreshToken_preservesOldRefreshToken() {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        when(tokenService.readLatest("google")).thenReturn(storedToken("old-rt"));

        OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);
        long newExpiresAt = System.currentTimeMillis() + 3599_000L;
        // Google refresh 通常不再下发 refresh_token → resp.refreshToken()==null
        when(tokenClient.refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class)))
            .thenReturn(new OAuthTokenResponse("new-at", "Bearer", "openid", newExpiresAt, null));

        AccountOAuthTokenRefresher refresher = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService);

        assertThat(refresher.forceRefresh()).isTrue();

        ArgumentCaptor<AccountOAuthToken> saved = ArgumentCaptor.forClass(AccountOAuthToken.class);
        verify(tokenService).save(saved.capture());
        assertThat(saved.getValue().getRefreshToken())
            .as("刷新响应缺 refresh_token 必须保留旧值（CC client.ts:178 refresh_token 默认旧值），"
                + "否则旧 refresh_token 被 null 覆盖 → 后续刷新链断裂")
            .isEqualTo("old-rt");
        assertThat(saved.getValue().getAccessToken()).isEqualTo("new-at");
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(newExpiresAt);
    }

    @Test
    @DisplayName("GREEN ③ 存储无 refresh_token（GitHub 语义）→ false，不调用 tokenClient（保持重新授权）")
    void noRefreshToken_returnsFalseWithoutRefresh() {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        when(tokenService.readLatest("google")).thenReturn(storedToken(null));

        OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);

        AccountOAuthTokenRefresher refresher = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService);

        assertThat(refresher.forceRefresh())
            .as("无 refresh_token → 无法刷新（CC auth.ts:1464 !tokens?.refreshToken → false，重新授权）")
            .isFalse();
        verify(tokenClient, never()).refreshTokens(any(), anyMap(), any(OAuthProviderConfig.class));
        verify(tokenService, never()).save(any(AccountOAuthToken.class));
    }

    @Test
    @DisplayName("GREEN ④ 刷新抛错 → false（fail-soft 降级，CC 刷新失败返回 false）")
    void refreshFailure_returnsFalse() {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        when(tokenService.readLatest("google")).thenReturn(storedToken("old-rt"));

        OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);
        when(tokenClient.refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class)))
            .thenThrow(new OAuthTokenExchangeError("invalid_grant", "expired refresh token",
                OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED));

        AccountOAuthTokenRefresher refresher = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService);

        assertThat(refresher.forceRefresh()).as("刷新抛错应降级返回 false").isFalse();
        verify(tokenService, never()).save(any(AccountOAuthToken.class));
    }
}
