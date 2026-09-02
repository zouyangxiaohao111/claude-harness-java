package com.nexusai.application.agent.mcp;

import com.nexusai.domain.mcp_oauth.McpOAuthTokenService;
import com.nexusai.model.mcp_oauth.McpOAuthToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * [S03 R2-04 X-3] _doRefresh 重试/退避 + invalid_grant 清凭据测试 · 对齐 CC auth.ts:2177-2359
 * （MAX_ATTEMPTS=3，auth.ts:2180；退避 1000*2^(attempt-1)ms，auth.ts:2349；invalid_grant →
 * 重读存储 expiresIn&gt;300 复用 / 否则 invalidateCredentials('tokens')，auth.ts:2289-2324）。
 *
 * <p><b>WHY（意图验证）</b>: 旧路径（D-S03-2）单次刷新失败即抛 MCPRefreshFailed —— 无重试、
 * 无 invalid_grant 识别，旧 token 永留 DB（I-5 违反）。本测试断言：
 * <ol>
 *   <li>HTTP 429/5xx（transient）→ 3 次重试（MAX_ATTEMPTS=3），退避 1s/2s，重试耗尽返回 null</li>
 *   <li>invalid_grant → 清凭据（DB 无残留：accessToken=''/refreshToken=null/expiresAt=0）+ 返回 null</li>
 *   <li>invalid_grant 但重读发现另一进程已刷新（expiresIn&gt;300）→ 复用其 token 不重复刷新</li>
 *   <li>非 transient（如 400/连接拒绝）→ 不重试，立即返回 null（McpAuthHeaderProvider 降级语义不变）</li>
 * </ol>
 */
class McpAuthRefreshRetryTest {

    private static final String SERVER_URL = "https://mcp.example.com/mcp";
    private static final String TOKEN_ENDPOINT = "https://as.example.com/token";

    private McpAuth.Tokens tokens(String at) {
        return new McpAuth.Tokens(at, "rt-new", System.currentTimeMillis() + 3600_000L, "read");
    }

    private McpAuth.AuthServerMetadata metadata() {
        return new McpAuth.AuthServerMetadata(
            "https://as.example.com/authorize", TOKEN_ENDPOINT, "https://as.example.com");
    }

    /** 带 refreshToken + clientId + 已过期 access 的 DB 记录（expiresIn ≤ 300 触发主动刷新）。 */
    private McpOAuthToken expiredToken() {
        McpOAuthToken t = new McpOAuthToken();
        t.setServerKey("key-1");
        t.setServerName("srv");
        t.setAccessToken("at-old");
        t.setRefreshToken("rt-1");
        t.setClientId("cid");
        t.setExpiresAt(System.currentTimeMillis() - 1000L);
        return t;
    }

    private McpAuth authWith(McpOAuthTokenService store, McpAuth.OAuthHttpClient http) {
        return new McpAuth(null, http, u -> {}, (s, t) -> null, null, store, null);
    }

    @Test
    @DisplayName("[X-3] transient 429 → 3 次重试（MAX_ATTEMPTS=3）退避 1s/2s，耗尽返回 null")
    void refresh_http429_retriesThreeTimes_withBackoff() throws InterruptedException {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(expiredToken());
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchAuthServer(anyString(), anyLong())).thenReturn(metadata());
        // 429 Too Many Requests（oauth4webapi TooManyRequestsError，auth.ts:2333）——恒 transient
        when(http.refreshTokens(anyString(), anyMap())).thenThrow(
            new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                "HTTP 429: rate limited", 429));
        McpAuth mcpAuth = authWith(store, http);

        long t0 = System.currentTimeMillis();
        McpAuth.Tokens result = mcpAuth.refreshServerToken("key-1", SERVER_URL, null);
        long elapsedMs = System.currentTimeMillis() - t0;

        // 3 次尝试（1+2 退避 ≈ 3s）：attempt1 失败 → sleep 1s → attempt2 → sleep 2s → attempt3 失败 → null
        verify(http, times(3)).refreshTokens(anyString(), anyMap());
        assertThat(result).as("重试耗尽返回 null（transient_retries_exhausted，auth.ts:2342-2346）").isNull();
        assertThat(elapsedMs).as("退避 1s+2s（1000*2^(attempt-1)，auth.ts:2349）").isGreaterThanOrEqualTo(2900L);
    }

    @Test
    @DisplayName("[X-3] invalid_grant → 清凭据（DB 无残留）+ 返回 null（auth.ts:2318-2324）")
    void refresh_invalidGrant_clearsCredentials() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(expiredToken());
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchAuthServer(anyString(), anyLong())).thenReturn(metadata());
        when(http.refreshTokens(anyString(), anyMap()))
            .thenThrow(new McpAuth.InvalidGrantError("invalid_grant"));
        McpAuth mcpAuth = authWith(store, http);

        McpAuth.Tokens result = mcpAuth.refreshServerToken("key-1", SERVER_URL, null);

        assertThat(result).as("invalid_grant → 返回 null（无有效 token）").isNull();
        // 单次尝试（invalid_grant 不重试）；清凭据落库 = tokens scope（accessToken=''/refreshToken=null/expiresAt=0）
        verify(http, times(1)).refreshTokens(anyString(), anyMap());
        ArgumentCaptor<McpOAuthToken> captor = ArgumentCaptor.forClass(McpOAuthToken.class);
        verify(store).save(captor.capture());
        McpOAuthToken cleared = captor.getValue();
        assertThat(cleared.getAccessToken()).as("accessToken 必须清空").isEqualTo("");
        assertThat(cleared.getRefreshToken()).as("refreshToken 必须清空（旧 token 不永留 DB，I-5）").isNull();
        assertThat(cleared.getExpiresAt()).as("expiresAt 必须归零").isEqualTo(0L);
    }

    @Test
    @DisplayName("[X-3] invalid_grant 但另一进程已刷新（重读 expiresIn>300）→ 复用其 token 不重复刷新（auth.ts:2301-2315）")
    void refresh_invalidGrant_anotherProcessRefreshed_reusesTokens() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        // 第一次 read：取锁后重读（旧过期 token）→ 进入刷新；invalid_grant 后 reReader 第二次
        // read：另一进程已刷新成功（expiresIn ~600s > 300）→ 复用
        McpOAuthToken fresh = new McpOAuthToken();
        fresh.setServerKey("key-1");
        fresh.setServerName("srv");
        fresh.setAccessToken("at-fresh");
        fresh.setRefreshToken("rt-fresh");
        fresh.setExpiresAt(System.currentTimeMillis() + 600_000L);
        fresh.setScope("read");
        when(store.read(anyString()))
            .thenReturn(expiredToken())
            .thenReturn(fresh);
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchAuthServer(anyString(), anyLong())).thenReturn(metadata());
        when(http.refreshTokens(anyString(), anyMap()))
            .thenThrow(new McpAuth.InvalidGrantError("invalid_grant"));
        McpAuth mcpAuth = authWith(store, http);

        McpAuth.Tokens result = mcpAuth.refreshServerToken("key-1", SERVER_URL, null);

        assertThat(result).isNotNull();
        assertThat(result.accessToken()).as("复用另一进程刷新后的 token").isEqualTo("at-fresh");
        assertThat(result.refreshToken()).isEqualTo("rt-fresh");
        // 复用路径不清凭据（verify save 未被调）
        verify(store, times(0)).save(any(McpOAuthToken.class));
    }

    @Test
    @DisplayName("[X-3] 非 transient 失败（400/连接拒绝）→ 不重试，单次即返回 null")
    void refresh_nonTransient_doesNotRetry() {
        McpOAuthTokenService store = mock(McpOAuthTokenService.class);
        when(store.read(anyString())).thenReturn(expiredToken());
        McpAuth.OAuthHttpClient http = mock(McpAuth.OAuthHttpClient.class);
        when(http.fetchAuthServer(anyString(), anyLong())).thenReturn(metadata());
        // 400 invalid_request：非 transient（auth.ts:2331-2335 仅 timeout/ServerError/
        // TemporarilyUnavailable/TooManyRequests 重试）
        when(http.refreshTokens(anyString(), anyMap())).thenThrow(
            new McpAuth.MCPRefreshFailed(McpAuth.MCPRefreshFailureReason.REQUEST_FAILED,
                "HTTP 400: invalid_request", 400));
        McpAuth mcpAuth = authWith(store, http);

        McpAuth.Tokens result = mcpAuth.refreshServerToken("key-1", SERVER_URL, null);

        verify(http, times(1)).refreshTokens(anyString(), anyMap());
        assertThat(result).isNull();
    }
}
