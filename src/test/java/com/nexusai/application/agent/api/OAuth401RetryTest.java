package com.nexusai.application.agent.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OAuth401Retry 主链单测 · 对齐 CC {@code src/utils/http.ts:115-136} withOAuth401Retry。
 *
 * <p><b>WHY（意图验证，规则九）</b>：OAuth 401 处理是"时钟漂移场景下 token 自愈"的核心
 * —— 服务端判 401 但本地过期检查未到，必须强制刷新后重试一次。若判定漏了 403-revoked
 * 分支（some endpoints signal revocation via 403 instead of 401），token 不会自愈；
 * 若把非 OAuth 错误也拦截重试，则掩盖真实故障。此测试固化该判定与重试一次的语义。
 */
class OAuth401RetryTest {

    private static final Integer STATUS_401 = 401;
    private static final Integer STATUS_403 = 403;
    private static final Integer STATUS_500 = 500;

    // ── 测试骨架：一个可抛异常的请求闭包 ──

    private static final class Request implements OAuth401Retry.OAuthRequestCall<Object> {
        int calls = 0;
        final Throwable firstError;
        final Object result;

        Request(Throwable firstError, Object result) {
            this.firstError = firstError;
            this.result = result;
        }

        @Override
        public Object call() throws Exception {
            calls++;
            if (calls == 1 && firstError != null) {
                if (firstError instanceof Exception e) {
                    throw e;
                }
                throw new RuntimeException(firstError);
            }
            return result;
        }
    }

    private static final class FixedExtractor implements OAuth401Retry.HttpStatusExtractor {
        final Integer status;
        final String body;

        FixedExtractor(Integer status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public Integer statusOf(Throwable error) {
            return status;
        }

        @Override
        public String bodyOf(Throwable error) {
            return body;
        }
    }

    /** 默认：当前 token 固定，refresh 成功。 */
    private static OAuth401Refresher okRefresher() {
        return new OAuth401Refresher(
            () -> new OAuth401Refresher.OAuthTokens("new-token", "refresh"),
            () -> true);
    }

    @Test
    @DisplayName("401 → 刷新 token 后重试一次，返回最终结果（CC http.ts:124-134 主链）")
    void retriesOnceOn401() throws Exception {
        Request req = new Request(new IllegalStateException("401 unauthorized"), "ok");
        OAuth401Retry.CurrentOAuthTokenProvider provider = () -> "failed-token";

        Object out = OAuth401Retry.withOAuth401Retry(
            req, new FixedExtractor(STATUS_401, null), provider, okRefresher(), null);

        assertThat(out).isEqualTo("ok");
        assertThat(req.calls).isEqualTo(2);  // 失败 1 次 + 重试 1 次
    }

    @Test
    @DisplayName("403 + revoked body + also403Revoked=true → 刷新重试一次（CC http.ts:126-129）")
    void retriesOn403Revoked() throws Exception {
        Request req = new Request(new IllegalStateException("revoked"), "ok");
        OAuth401Retry.CurrentOAuthTokenProvider provider = () -> "failed-token";

        Object out = OAuth401Retry.withOAuth401Retry(
            req,
            new FixedExtractor(STATUS_403, "OAuth token has been revoked"),
            provider,
            okRefresher(),
            new OAuth401Retry.Options(true));

        assertThat(out).isEqualTo("ok");
        assertThat(req.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("403 + revoked body + also403Revoked=false → 透传不重试（CC http.ts:126-127 分支关闭）")
    void passthrough403WhenFlagOff() {
        Request req = new Request(new IllegalStateException("revoked"), "ok");
        OAuth401Retry.CurrentOAuthTokenProvider provider = () -> "failed-token";

        assertThatThrownBy(() -> OAuth401Retry.withOAuth401Retry(
            req,
            new FixedExtractor(STATUS_403, "OAuth token has been revoked"),
            provider,
            okRefresher(),
            null))  // Options.defaults() → also403Revoked=false
            .isInstanceOf(IllegalStateException.class);
        assertThat(req.calls).isEqualTo(1);  // 未重试
    }

    @Test
    @DisplayName("非 OAuth 错误（500）→ 透传，不刷新不重试（CC http.ts:130）")
    void passthroughNonAuthError() {
        Request req = new Request(new IllegalStateException("server error"), "ok");
        OAuth401Retry.CurrentOAuthTokenProvider provider = () -> "failed-token";

        assertThatThrownBy(() -> OAuth401Retry.withOAuth401Retry(
            req, new FixedExtractor(STATUS_500, null), provider, okRefresher(), null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(req.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("无当前 access token → 重抛不重试（CC http.ts:131-132）")
    void rethrowWhenNoToken() {
        Request req = new Request(new IllegalStateException("401"), "ok");
        OAuth401Retry.CurrentOAuthTokenProvider provider = () -> null;

        assertThatThrownBy(() -> OAuth401Retry.withOAuth401Retry(
            req, new FixedExtractor(STATUS_401, null), provider, okRefresher(), null))
            .isInstanceOf(IllegalStateException.class);
        assertThat(req.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("isAuthError 纯函数：401 真 / 403+revoked+flag 真 / 其它假（CC http.ts:124-129）")
    void isAuthErrorMatrix() {
        OAuth401Retry.Options off = new OAuth401Retry.Options(false);
        OAuth401Retry.Options on = new OAuth401Retry.Options(true);

        assertThat(OAuth401Retry.isAuthError(STATUS_401, null, off)).isTrue();
        assertThat(OAuth401Retry.isAuthError(STATUS_403, "OAuth token has been revoked", on)).isTrue();
        assertThat(OAuth401Retry.isAuthError(STATUS_403, "OAuth token has been revoked", off)).isFalse();
        assertThat(OAuth401Retry.isAuthError(STATUS_500, null, on)).isFalse();
        assertThat(OAuth401Retry.isAuthError(STATUS_403, null, on)).isFalse();  // body 非 revoked
    }

    @Test
    @DisplayName("OAuth401Refresher：无 refreshToken → false（CC auth.ts:1380-1382）")
    void refresherNoRefreshToken() {
        OAuth401Refresher refresher = new OAuth401Refresher(
            () -> new OAuth401Refresher.OAuthTokens("same-token", null),
            () -> { throw new AssertionError("不应触发强制刷新"); });

        assertThat(refresher.handle401("same-token")).isFalse();
    }

    @Test
    @DisplayName("OAuth401Refresher：当前 token 已异于 failedToken → true 不刷新（CC auth.ts:1385-1388）")
    void refresherAlreadyRefreshedElsewhere() {
        boolean[] forceCalled = {false};
        OAuth401Refresher refresher = new OAuth401Refresher(
            () -> new OAuth401Refresher.OAuthTokens("new-token", "refresh"),
            () -> { forceCalled[0] = true; return true; });

        assertThat(refresher.handle401("old-failed-token")).isTrue();
        assertThat(forceCalled[0]).isFalse();  // 他处已刷，不触发强制刷新
    }

    @Test
    @DisplayName("OAuth401Refresher：同 token 仍失败 → 强制刷新（CC auth.ts:1391 checkAndRefreshOAuthTokenIfNeeded(0,true)）")
    void refresherForceRefresh() {
        OAuth401Refresher refresher = new OAuth401Refresher(
            () -> new OAuth401Refresher.OAuthTokens("same-token", "refresh"),
            () -> true);

        assertThat(refresher.handle401("same-token")).isTrue();
    }

    @Test
    @DisplayName("OAuth401Refresher：刷新在途时并发同 failedAccessToken → 单 keychain 读 + 单刷新（CC auth.ts:1363-1369 dedup）")
    void refresherConcurrentDedupSingleKeychainRead() throws Exception {
        // WHY：CC 依赖 JS 单线程使 pending401Handlers get→set 原子，Java 多线程暴露 TOCTOU
        // （两 caller 均 get==null → 各提交 handle401Impl → 双 keychain 读/刷新；loser 的
        // whenComplete remove 还会清掉 winner 在途条目）。dedup 只对"在途刷新"生效（CC
        // auth.ts:1363-1369）：刷新未完成期间同 token 并发必须共享同一 promise —— computeIfAbsent
        // 原子化保证单 keychain 读 + 单刷新（CC auth.ts:1354-1355 "deduplicated to a single keychain read"）。
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger forceRefreshes = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        OAuth401Refresher refresher = new OAuth401Refresher(
            () -> {
                reads.incrementAndGet();
                return new OAuth401Refresher.OAuthTokens("same-token", "refresh");
            },
            () -> {
                forceRefreshes.incrementAndGet();
                refreshStarted.countDown();
                try {
                    releaseRefresh.await();  // 保持刷新在途，验证并发 dedup
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            });

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return refresher.handle401("same-token");
                }));
            }
            ready.await();
            go.countDown();
            // 等首个刷新真实在途（forceRefresh 已阻塞），其余 caller 应复用同一 promise
            assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);  // 让其余 caller 完成 computeIfAbsent 并 join 共享 promise
            releaseRefresh.countDown();
            for (Future<Boolean> f : futures) {
                assertThat(f.get(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(reads.get())
            .as("刷新在途期间同 token 只读一次 keychain（CC auth.ts:1354-1355 single keychain read）")
            .isEqualTo(1);
        assertThat(forceRefreshes.get())
            .as("刷新在途期间同 token 只触发一次强制刷新")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("refresher 为 null → 快速失败（CC http.ts:133 handleOAuth401Error 恒在，不可静默跳过刷新）")
    void nullRefresherFailsLoud() {
        // WHY：CC http.ts:133 无条件 await handleOAuth401Error(failedAccessToken) —— handler 恒在；
        // Java if(refresher!=null) 守卫会静默跳过刷新，同 token 重试必再 401。要求非 null，
        // 无 OAuth 调用方显式失败而非静默降级（规则十二 fail loud）。
        Request req = new Request(new IllegalStateException("401"), "ok");
        OAuth401Retry.CurrentOAuthTokenProvider provider = () -> "failed-token";

        assertThatThrownBy(() -> OAuth401Retry.withOAuth401Retry(
            req, new FixedExtractor(STATUS_401, null), provider, null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("refresher is required");
        assertThat(req.calls).isEqualTo(0);  // 参数校验在请求前，一次也未调用
    }
}
