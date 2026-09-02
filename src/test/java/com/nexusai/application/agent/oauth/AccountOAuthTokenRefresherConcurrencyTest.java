package com.nexusai.application.agent.oauth;

import com.nexusai.domain.oauth_account.AccountOAuthTokenService;
import com.nexusai.model.oauth_account.AccountOAuthToken;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FIX-1 · {@link AccountOAuthTokenRefresher} 刷新锁 + 重试 + 竞态恢复（并发行为）验证。
 *
 * <p><b>WHY (规则九 · 意图验证)</b>: 刷新器的正确性在<b>并发</b>下才成立——无锁时两个线程会
 * 对同一过期 token 各自打 refresh grant（重复刷新 + 可能互相覆盖 write），有锁后串行化且锁内
 * 双检使后到者复用已刷新 token 不二次刷新且返回 false（本调用未刷新，对齐 CC auth.ts:1526-1527
 * race_resolved 返回 false）；刷新
 * 抛错但并发下 token 已恢复为不过期时应判成功（CC auth.ts:1545-1556 race_recovered）；锁被
 * 持有且不可获取时应在 MAX_RETRIES=5 次重试后放弃（CC auth.ts:1515 lock_retry_limit_reached）。
 * 这些「竞争下的对错」是刷新锁为何重要的核心，仅单线程行为测试无法覆盖。
 */
class AccountOAuthTokenRefresherConcurrencyTest {

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
    @DisplayName("两线程并发 forceRefresh 同一过期 token → refreshTokens 只调 1 次（锁串行化 + 锁内双检复用已刷新 token）")
    void concurrentForceRefresh_serializesToSingleRefresh() throws Exception {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        AtomicReference<AccountOAuthToken> stored = new AtomicReference<>(storedToken("old-rt"));
        when(tokenService.readLatest("google")).thenAnswer(i -> stored.get());
        doAnswer(i -> { stored.set(i.getArgument(0)); return null; })
            .when(tokenService).save(any(AccountOAuthToken.class));

        OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        when(tokenClient.refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class)))
            .thenAnswer(i -> {
                refreshEntered.countDown();
                if (!releaseRefresh.await(10, TimeUnit.SECONDS)) {
                    throw new OAuthTokenExchangeError("timeout", "test timeout",
                        OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
                }
                return new OAuthTokenResponse("new-at", "Bearer", "openid",
                    System.currentTimeMillis() + 3599_000L, "new-rt");
            });

        // backoff 50ms → 5 次重试预算 250ms，覆盖第一线程持锁区间
        AccountOAuthTokenRefresher refresher = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService, () -> 50L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(refresher::forceRefresh);
            assertThat(refreshEntered.await(10, TimeUnit.SECONDS))
                .as("第一线程须已进入刷新（持锁）").isTrue();
            Future<Boolean> second = pool.submit(refresher::forceRefresh);
            // 让第二线程撞锁并进入 backoff 循环，再释放第一线程
            Thread.sleep(50);
            releaseRefresh.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).as("第一线程刷新成功").isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS))
                .as("第二线程应经锁内双检发现 token 已刷新，返回 false 不二次刷新（race_resolved，CC auth.ts:1526-1527）")
                .isFalse();
        } finally {
            pool.shutdownNow();
        }
        verify(tokenClient, times(1))
            .refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class));
        verify(tokenService, times(1)).save(any(AccountOAuthToken.class));
    }

    @Test
    @DisplayName("刷新抛 OAuthTokenExchangeError 但并发下 readLatest 已返回非过期 token → forceRefresh 返回 true（失败竞态恢复）")
    void refreshFailure_recoversFromConcurrentRefresh_returnsTrue() {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        AccountOAuthToken expired = storedToken("old-rt");
        AccountOAuthToken recovered = storedToken("new-rt");
        recovered.setAccessToken("new-at");
        recovered.setExpiresAt(System.currentTimeMillis() + 3599_000L); // 非过期
        // 调用序：pre-lock 读 → 锁内双检 → catch 重读（三次 readLatest）
        when(tokenService.readLatest("google")).thenReturn(expired, expired, recovered);

        OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);
        when(tokenClient.refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class)))
            .thenThrow(new OAuthTokenExchangeError("invalid_grant", "expired refresh token",
                OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED));

        AccountOAuthTokenRefresher refresher = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService);

        assertThat(refresher.forceRefresh())
            .as("刷新抛错但并发已恢复非过期 token → true（CC auth.ts:1545-1556 race_recovered）")
            .isTrue();
        verify(tokenService, never()).save(any(AccountOAuthToken.class));
    }

    @Test
    @DisplayName("锁被并发持有且不可获取 → MAX_RETRIES=5 次重试后返回 false（backoff 注入 0 避免真睡）")
    void lockContention_exhaustsMaxRetries_returnsFalse() throws Exception {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        when(tokenService.readLatest("google")).thenReturn(storedToken("old-rt"));

        OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);
        CountDownLatch holderEntered = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        when(tokenClient.refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class)))
            .thenAnswer(i -> {
                holderEntered.countDown();
                if (!releaseHolder.await(10, TimeUnit.SECONDS)) {
                    throw new OAuthTokenExchangeError("timeout", "test timeout",
                        OAuthTokenExchangeFailure.TOKEN_EXCHANGE_FAILED);
                }
                return new OAuthTokenResponse("new-at", "Bearer", "openid",
                    System.currentTimeMillis() + 3599_000L, "new-rt");
            });

        AccountOAuthTokenRefresher holder = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService, () -> 0L);
        AccountOAuthTokenRefresher contender = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService, () -> 0L);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> holderFuture = pool.submit(holder::forceRefresh);
            assertThat(holderEntered.await(10, TimeUnit.SECONDS))
                .as("持锁线程须已进入刷新（持锁）").isTrue();

            assertThat(contender.forceRefresh())
                .as("锁被并发持有且 5 次重试后应返回 false（CC lock_retry_limit_reached）")
                .isFalse();

            releaseHolder.countDown();
            assertThat(holderFuture.get(10, TimeUnit.SECONDS)).as("持锁线程最终刷新成功").isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("锁内双检读非过期 token → forceRefresh 返回 false 且不调 refreshTokens（race_resolved，本调用未刷新）")
    void lockDoubleCheck_notExpired_returnsFalseWithoutRefresh() {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        AccountOAuthToken expired = storedToken("old-rt");
        AccountOAuthToken alreadyRefreshed = storedToken("new-rt");
        alreadyRefreshed.setAccessToken("new-at");
        alreadyRefreshed.setExpiresAt(System.currentTimeMillis() + 3599_000L); // 非过期
        // 调用序：pre-lock 读（过期）→ 锁内双检（非过期）
        when(tokenService.readLatest("google")).thenReturn(expired, alreadyRefreshed);

        OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);

        AccountOAuthTokenRefresher refresher = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService, () -> 0L);

        assertThat(refresher.forceRefresh())
            .as("锁内双检已不过期 → false（race_resolved，本调用未二次刷新，CC auth.ts:1526-1527）")
            .isFalse();
        verify(tokenClient, never())
            .refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class));
    }

    @Test
    @DisplayName("锁内双检 accessToken 已变但 expiresAt 仍过期 → 仍刷新 1 次并返回 true（accessToken 变化≠race resolved）")
    void accessTokenChanged_butStillExpired_stillRefreshes() {
        AccountOAuthTokenService tokenService = mock(AccountOAuthTokenService.class);
        AccountOAuthToken preLock = storedToken("old-rt"); // accessToken="old-at" 已过期
        AccountOAuthToken changedAtStillExpired = storedToken("new-rt");
        changedAtStillExpired.setAccessToken("changed-at");
        changedAtStillExpired.setExpiresAt(System.currentTimeMillis() - 1000L); // 仍过期
        // 调用序：pre-lock 读（old-at 过期）→ 锁内双检（changed-at 仍过期）
        when(tokenService.readLatest("google")).thenReturn(preLock, changedAtStillExpired);

        OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);
        when(tokenClient.refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class)))
            .thenReturn(new OAuthTokenResponse("final-at", "Bearer", "openid",
                System.currentTimeMillis() + 3599_000L, "final-rt"));

        AccountOAuthTokenRefresher refresher = new AccountOAuthTokenRefresher(
            googleConfig(), tokenClient, tokenService, () -> 0L);

        assertThat(refresher.forceRefresh())
            .as("accessToken 已变但 expiresAt 仍过期 → 仍刷新并返回 true（仅 notExpired 才是 race resolved）")
            .isTrue();
        verify(tokenClient, times(1))
            .refreshTokens(eq(GOOGLE_TOKEN), anyMap(), any(OAuthProviderConfig.class));
    }
}
