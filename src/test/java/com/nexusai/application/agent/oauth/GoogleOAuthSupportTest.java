package com.nexusai.application.agent.oauth;

import com.nexusai.domain.provider.ProviderService;
import com.nexusai.model.provider.dto.ProviderDto;
import com.nexusai.model.provider.dto.ProviderType;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Google 订阅登录链路的纯单元测试（无 Spring / 无 DB / 无真实 Google 请求）。
 *
 * <p>token 端点用 localhost {@link HttpServer} 桩；Cloud Code 解析用 Mockito 替身；ProviderService
 * 用内存子类；时间用 {@link MutableClock}、到期用 {@link ManualScheduler}，回调监听用真实
 * 127.0.0.1 监听。覆盖：授权 URL、state 一次性/TTL/多槽、尝试上下文 deadline、
 * 两参剩余时间、到期/退出/换代原子终态、换票失败 error、跨 provider 归属、监听端口与 redirect_uri 一致。
 */
class GoogleOAuthSupportTest {

    // ───────────────────────────── 授权 URL ─────────────────────────────

    @Test
    @DisplayName("authorize URL：无 PKCE、access_type=offline、prompt=consent、127.0.0.1 动态端口 redirect")
    void authorizeUrl_hasOfficialParamsWithoutPkce() {
        String url = GoogleOAuthService.authorizeUrl("s".repeat(64),
            "http://127.0.0.1:51234/oauth2callback");
        Map<String, String> q = queryOf(url);

        assertThat(url).startsWith(GoogleOAuthProviderConfig.AUTHORIZATION_ENDPOINT + "?");
        assertThat(q)
            .containsEntry("client_id", GoogleOAuthProviderConfig.CLIENT_ID)
            .containsEntry("response_type", "code")
            .containsEntry("redirect_uri", "http://127.0.0.1:51234/oauth2callback")
            .containsEntry("scope", String.join(" ", GoogleOAuthProviderConfig.SCOPES))
            .containsEntry("access_type", "offline")
            .containsEntry("prompt", "consent")
            .containsEntry("state", "s".repeat(64));
        assertThat(q).doesNotContainKeys("code_challenge", "code_challenge_method", "code_verifier");
    }

    @Test
    @DisplayName("state 是新随机串：64 hex，且两次授权不同")
    void newState_is64HexAndUnique() {
        String a = GoogleOAuthService.newState();
        String b = GoogleOAuthService.newState();
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("parseCallback：整条 URL 取 code+state；裸 code 无 state；error 参数 → 固定安全文案（不回显）")
    void parseCallback_variants() {
        GoogleOAuthService.CallbackInput full = GoogleOAuthService.parseCallback(
            "http://127.0.0.1:5000/oauth2callback?code=abc%2F1&state=deadbeef");
        assertThat(full.code()).isEqualTo("abc/1");
        assertThat(full.state()).isEqualTo("deadbeef");

        GoogleOAuthService.CallbackInput bare = GoogleOAuthService.parseCallback(" 4/0AXxx ");
        assertThat(bare.code()).isEqualTo("4/0AXxx");
        assertThat(bare.state()).isNull();

        assertThatThrownBy(() -> GoogleOAuthService.parseCallback(
            "http://127.0.0.1:5000/oauth2callback?error=access_denied"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(GoogleOAuthService.safeOAuthErrorMessage("access_denied"))
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("access_denied"));
        assertThatThrownBy(() -> GoogleOAuthService.parseCallback("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("裸串判定：已知 error token / error= 形式不算授权码；合法 opaque code（含相似单词）照旧")
    void parseCallback_bareErrorTokensNotTreatedAsCode() {
        assertThat(GoogleOAuthService.parseCallbackDetailed("access_denied").error())
            .isEqualTo("access_denied");
        assertThat(GoogleOAuthService.parseCallbackDetailed("ACCESS_DENIED").error())
            .isEqualTo("access_denied");
        assertThat(GoogleOAuthService.parseCallbackDetailed("error=access_denied").error())
            .isEqualTo("access_denied");
        assertThat(GoogleOAuthService.parseCallbackDetailed("login_required").error())
            .isEqualTo("login_required");

        // 合法授权码：即使包含相似单词也不能误判（整串精确匹配）
        GoogleOAuthService.ParsedCallback legit =
            GoogleOAuthService.parseCallbackDetailed("4/0invalid_request_abcXYZ");
        assertThat(legit.error()).isNull();
        assertThat(legit.code()).isEqualTo("4/0invalid_request_abcXYZ");
        assertThat(GoogleOAuthService.parseCallbackDetailed("4/0AX4XfWh").error()).isNull();
    }

    @Test
    @DisplayName("state JSON 往返：数组/单对象兼容；坏 JSON 抛错；非 Google 条目忽略")
    void stateJson_roundTrip() {
        List<GoogleOAuthService.PendingState> states = List.of(
            new GoogleOAuthService.PendingState("s1", 1000L, "http://127.0.0.1:1/oauth2callback"),
            new GoogleOAuthService.PendingState("s2", 2000L, "http://127.0.0.1:2/oauth2callback"));
        String json = GoogleOAuthService.encodeStates(states);
        assertThat(GoogleOAuthService.decodeStates(json)).isEqualTo(states);
        assertThat(GoogleOAuthService.decodeStates(
            "{\"state\":\"s1\",\"createdAt\":1,\"redirectUri\":\"http://127.0.0.1:9/oauth2callback\"}"))
            .hasSize(1);
        assertThat(GoogleOAuthService.decodeStates(null)).isEmpty();
        assertThat(GoogleOAuthService.decodeStates("[{\"state\":\"x\",\"verifier\":\"x\"}]")).isEmpty();
        assertThatThrownBy(() -> GoogleOAuthService.decodeStates("not-json"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ───────────────────────────── authorize 多槽 / TTL ─────────────────────────────

    @Test
    @DisplayName("连续两次 authorize：两条 state 都保留且不同；超过上限只留最近 5 条")
    void buildAuthorize_keepsMultipleStatesAndTrims() {
        FakeProviderService store = new FakeProviderService();
        GoogleOAuthService service = new GoogleOAuthService(store, null, null, "http://127.0.0.1:1/token");

        GoogleOAuthService.AuthorizeResult first = service.buildAuthorizeUrl("p1");
        GoogleOAuthService.AuthorizeResult second = service.buildAuthorizeUrl("p1");
        List<GoogleOAuthService.PendingState> pending =
            GoogleOAuthService.decodeStates(store.states.get("p1"));
        assertThat(pending).hasSize(2);
        assertThat(queryOf(first.url()).get("state"))
            .isNotEqualTo(queryOf(second.url()).get("state"));
        assertThat(queryOf(first.url()).get("redirect_uri"))
            .isEqualTo(pending.get(0).redirectUri());

        String lastState = null;
        for (int i = 0; i < GoogleOAuthProviderConfig.MAX_PENDING_STATES + 2; i++) {
            lastState = queryOf(service.buildAuthorizeUrl("p1").url()).get("state");
        }
        pending = GoogleOAuthService.decodeStates(store.states.get("p1"));
        assertThat(pending).hasSize(GoogleOAuthProviderConfig.MAX_PENDING_STATES);
        assertThat(pending.get(pending.size() - 1).state()).isEqualTo(lastState);
    }

    @Test
    @DisplayName("pending deadline 稳定：多次 status 的 expiresAt 恒等于 authorize 初始值（不续命）")
    void status_pendingDeadlineStable() {
        FakeProviderService store = new FakeProviderService();
        GoogleOAuthService service = new GoogleOAuthService(store, null, null, "http://127.0.0.1:1/token");
        GoogleOAuthService.AuthorizeResult auth = service.buildAuthorizeUrl("p1");

        for (int i = 0; i < 3; i++) {
            GoogleOAuthService.Status s = service.status("p1");
            assertThat(s.status()).isEqualTo("pending");
            assertThat(s.expiresAt()).isEqualTo(auth.expiresAt());
        }
    }

    @Test
    @DisplayName("已过 TTL 的 state：complete 拒绝并消费（不能重放）")
    void expiredState_rejected() {
        FakeProviderService store = new FakeProviderService();
        GoogleOAuthService service = new GoogleOAuthService(store, null, null, "http://127.0.0.1:1/token");
        String url = service.buildAuthorizeUrl("p1").url();
        Map<String, String> q = queryOf(url);
        long old = System.currentTimeMillis() - GoogleOAuthProviderConfig.LOGIN_STATE_TTL_MILLIS - 1000L;
        store.states.put("p1", GoogleOAuthService.encodeStates(List.of(
            new GoogleOAuthService.PendingState(q.get("state"), old, q.get("redirect_uri")))));

        assertThatThrownBy(() -> service.complete("p1",
            "http://127.0.0.1:1/oauth2callback?code=c&state=" + q.get("state")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("过期");
        assertThat(store.states.get("p1")).isNull();
    }

    @Test
    @DisplayName("错误 state：拒绝且不消费其它在途 state")
    void wrongState_rejectedAndKeepsOthers() {
        FakeProviderService store = new FakeProviderService();
        GoogleOAuthService service = new GoogleOAuthService(store, null, null, "http://127.0.0.1:1/token");
        service.buildAuthorizeUrl("p1");
        String before = store.states.get("p1");

        assertThatThrownBy(() -> service.complete("p1", "http://127.0.0.1:1/oauth2callback?code=c&state=bogus"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不一致");
        assertThat(store.states.get("p1")).isEqualTo(before);
    }

    // ───────────────────────────── 首次缺 refresh_token ─────────────────────────────

    @Test
    @DisplayName("首次换 token 无 refresh_token：fail-loud，绝不落库（不静默 ready）")
    void firstTokenWithoutRefresh_failsLoudAndSavesNothing() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.NO_REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            GoogleOAuthService service = new GoogleOAuthService(store,
                mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
                token.url());
            String url = service.buildAuthorizeUrl("p1").url();

            assertThatThrownBy(() -> service.complete("p1", callbackUrl(url, "code1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refresh_token");
            assertThat(store.saved).isEmpty();
            assertThat(store.row("p1").getOauthAccessEncrypted()).isNull();
        }
    }

    // ───────────────────────────── 成功 / 重放 / 退出 ─────────────────────────────

    @Test
    @DisplayName("成功：token+project 同次落库，state 清空，status=ready；重放被拒")
    void complete_success_thenReplayRejected() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenReturn("proj-9");
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url());
            String url = service.buildAuthorizeUrl("p1").url();
            String callback = callbackUrl(url, "code1");

            service.complete("p1", callback);

            assertThat(store.saved).hasSize(1);
            Saved saved = store.saved.get(0);
            assertThat(saved.access).isEqualTo("access-1");
            assertThat(saved.refresh).isEqualTo("refresh-1");
            assertThat(saved.project).isEqualTo("proj-9");
            assertThat(saved.expiresAt).isNotNull();
            assertThat(store.states.get("p1")).isNull();

            GoogleOAuthService.Status status = service.status("p1");
            assertThat(status.status()).isEqualTo("ready");
            assertThat(status.oauthProject()).isEqualTo("proj-9");
            assertThat(status.expiresAt()).isEqualTo(saved.expiresAt);
            assertThat(status.message()).isNull();

            // 重放同一条回调：state 已消费
            assertThatThrownBy(() -> service.complete("p1", callback))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或已被使用");
            assertThat(store.saved).hasSize(1);
        }
    }

    @Test
    @DisplayName("status：project 解析期间可观察 onboarding（expiresAt=authorize 硬 deadline），完成后 ready")
    void status_onboardingObservableThenReady() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            CountDownLatch resolving = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenAnswer(inv -> {
                resolving.countDown();
                release.await(10, TimeUnit.SECONDS);
                return "proj-9";
            });
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url());
            GoogleOAuthService.AuthorizeResult auth = service.buildAuthorizeUrl("p1");

            assertThat(service.status("p1").status()).isEqualTo("pending");
            assertThat(service.status("p1").expiresAt()).isEqualTo(auth.expiresAt());

            AtomicReference<Throwable> err = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    service.complete("p1", callbackUrl(auth.url(), "code1"));
                } catch (Throwable t) {
                    err.set(t);
                }
            }, "google-onboard-test");
            worker.start();
            assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

            GoogleOAuthService.Status onboarding = service.status("p1");
            assertThat(onboarding.status()).isEqualTo("onboarding");
            assertThat(onboarding.expiresAt()).as("onboarding 到期值 = authorize 硬 deadline（不续命）")
                .isEqualTo(auth.expiresAt());
            assertThat(onboarding.message()).contains("项目初始化");

            release.countDown();
            worker.join(5_000);
            assertThat(err.get()).isNull();
            GoogleOAuthService.Status ready = service.status("p1");
            assertThat(ready.status()).isEqualTo("ready");
            assertThat(ready.oauthProject()).isEqualTo("proj-9");
        }
    }

    @Test
    @DisplayName("退出：清 token/project/state，回落 logged_out；迟到回调不能复活凭据")
    void logout_clearsAndLateCallbackCannotResurrect() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenReturn("proj-9");
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url());
            String callback = callbackUrl(service.buildAuthorizeUrl("p1").url(), "code1");

            service.logout("p1");
            assertThat(store.row("p1").getOauthAccessEncrypted()).isNull();
            assertThat(store.row("p1").getOauthProject()).isNull();
            assertThat(store.states.get("p1")).isNull();
            assertThat(service.status("p1").status()).isEqualTo("logged_out");

            assertThatThrownBy(() -> service.complete("p1", callback))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(store.saved).isEmpty();
            assertThat(store.row("p1").getOauthAccessEncrypted()).isNull();
        }
    }

    @Test
    @DisplayName("并发退出：logout 打断在途 project 解析，结果不落库")
    void logoutDuringOnboarding_abortsAndSavesNothing() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            CountDownLatch resolving = new CountDownLatch(1);
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenAnswer(inv -> {
                resolving.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    throw new GoogleCodeAssistClient.GoogleCodeAssistException(
                        GoogleCodeAssistClient.GoogleCodeAssistException.Code.ABORTED, "登录已取消");
                }
                return "proj-9";
            });
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url());
            String callback = callbackUrl(service.buildAuthorizeUrl("p1").url(), "code1");

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    service.complete("p1", callback);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "google-complete-test");
            worker.start();
            assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

            service.logout("p1");
            worker.join(5_000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.saved).isEmpty();
            assertThat(store.row("p1").getOauthAccessEncrypted()).isNull();
        }
    }

    @Test
    @DisplayName("退出赢后：不响应中断的迟到 project 结果仍写不回来（epoch+latch 原子终态）")
    void logoutWins_lateResultCannotWrite() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            CountDownLatch resolving = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenAnswer(inv -> {
                resolving.countDown();
                boolean done = false;
                while (!done) {
                    try {
                        release.await();
                        done = true;
                    } catch (InterruptedException ignored) {
                        // 模拟「晚到结果」：忽略 logout 的打断，继续等到被显式放行
                    }
                }
                return "proj-9";
            });
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url());
            String callback = callbackUrl(service.buildAuthorizeUrl("p1").url(), "code1");

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    service.complete("p1", callback);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "google-late-test");
            worker.start();
            assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

            service.logout("p1");
            release.countDown();
            worker.join(5_000);

            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.saved).as("logout 赢后迟到结果不得写回").isEmpty();
            assertThat(store.row("p1").getOauthAccessEncrypted()).isNull();
        }
    }

    @Test
    @DisplayName("新 authorize 赢后：旧 attempt 迟到结果不覆盖新 attempt（仍 pending，无落库）")
    void newAuthorizeWins_lateOldResultCannotOverride() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            CountDownLatch resolving = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenAnswer(inv -> {
                resolving.countDown();
                boolean done = false;
                while (!done) {
                    try {
                        release.await();
                        done = true;
                    } catch (InterruptedException ignored) {
                        // 忽略打断，模拟晚到
                    }
                }
                return "proj-9";
            });
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url());
            GoogleOAuthService.AuthorizeResult first = service.buildAuthorizeUrl("p1");

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    service.complete("p1", callbackUrl(first.url(), "code1"));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "google-reopen-test");
            worker.start();
            assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();

            GoogleOAuthService.AuthorizeResult reopened = service.buildAuthorizeUrl("p1");
            release.countDown();
            worker.join(5_000);

            assertThat(failure.get()).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.saved).isEmpty();
            GoogleOAuthService.Status status = service.status("p1");
            assertThat(status.status()).isEqualTo("pending");
            assertThat(status.expiresAt()).isEqualTo(reopened.expiresAt());
        }
    }

    // ───────────────────────────── deadline 与两参剩余时间 ─────────────────────────────

    @Test
    @DisplayName("剩 5s：走两参 resolveProject 且 Duration ≤ 5s（不再用默认 90s）")
    void remainingBudget_twoArgWithinFiveSeconds() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            MutableClock clock = new MutableClock(1_000_000L);
            ManualScheduler scheduler = new ManualScheduler();
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenReturn("proj-9");
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url(), clock, scheduler);
            GoogleOAuthService.AuthorizeResult auth = service.buildAuthorizeUrl("p1");
            clock.set(auth.expiresAt() - 5_000L);

            service.complete("p1", callbackUrl(auth.url(), "code1"));

            ArgumentCaptor<Duration> budget = ArgumentCaptor.forClass(Duration.class);
            verify(assist).resolveProject(anyString(), budget.capture());
            verify(assist, never()).resolveProject(anyString());
            assertThat(budget.getValue().toMillis()).isGreaterThan(0L).isLessThanOrEqualTo(5_000L);
            assertThat(store.saved).hasSize(1);
        }
    }

    @Test
    @DisplayName("到期：定时器置 error + 实际释放回调监听（不依赖 status 轮询）")
    void expiry_setsErrorAndReleasesListener() throws Exception {
        FakeProviderService store = new FakeProviderService();
        MutableClock clock = new MutableClock(1_000_000L);
        ManualScheduler scheduler = new ManualScheduler();
        GoogleOAuthService service = new GoogleOAuthService(store,
            mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
            "http://127.0.0.1:1/token", clock, scheduler);
        GoogleOAuthCallbackServer server = new GoogleOAuthCallbackServer(service);
        service.bindCallbackServer(() -> server);
        try {
            GoogleOAuthService.AuthorizeResult auth = service.buildAuthorizeUrl("p1");
            int port = server.runningPort();
            assertThat(port).isGreaterThan(0);
            assertThat(queryOf(auth.url()).get("redirect_uri"))
                .isEqualTo("http://127.0.0.1:" + port + "/oauth2callback");

            clock.set(auth.expiresAt() + 1L);
            scheduler.fireAll();

            GoogleOAuthService.Status status = service.status("p1");
            assertThat(status.status()).isEqualTo("error");
            assertThat(status.message()).contains("过期");
            awaitReleased(server, port);
            assertThat(server.runningPort()).isEqualTo(-1);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("独立 timer 取消 p1 不误关 p2：共享 listener 在仍有活 attempt 时保持运行")
    void sharedListener_notClosedWhileOtherProviderAlive() throws Exception {
        FakeProviderService store = new FakeProviderService();
        store.addProvider("p2");
        MutableClock clock = new MutableClock(1_000_000L);
        ManualScheduler scheduler = new ManualScheduler();
        GoogleOAuthService service = new GoogleOAuthService(store,
            mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
            "http://127.0.0.1:1/token", clock, scheduler);
        GoogleOAuthCallbackServer server = new GoogleOAuthCallbackServer(service);
        service.bindCallbackServer(() -> server);
        try {
            service.buildAuthorizeUrl("p1");
            int port = server.runningPort();
            service.buildAuthorizeUrl("p2");
            assertThat(server.runningPort()).as("p2 复用同一监听端口").isEqualTo(port);

            service.logout("p1");
            assertThat(server.runningPort()).as("p2 仍存活 ⇒ 不得关闭共享监听").isEqualTo(port);

            service.logout("p2");
            awaitReleased(server, port);
            assertThat(server.runningPort()).isEqualTo(-1);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("回调真实监听端口与 token 请求的 redirect_uri 完全一致")
    void callbackPort_matchesTokenRedirectUri() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenReturn("proj-9");
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url());
            GoogleOAuthCallbackServer server = new GoogleOAuthCallbackServer(service);
            service.bindCallbackServer(() -> server);
            try {
                GoogleOAuthService.AuthorizeResult auth = service.buildAuthorizeUrl("p1");
                int port = server.runningPort();
                service.complete("p1", callbackUrl(auth.url(), "code1"));

                String expected = "redirect_uri="
                    + URLEncoder.encode("http://127.0.0.1:" + port + "/oauth2callback",
                        StandardCharsets.UTF_8).replace("+", "%20");
                assertThat(token.lastBody()).contains(expected);
                assertThat(store.saved).hasSize(1);
            } finally {
                server.stop();
            }
        }
    }

    @Test
    @DisplayName("换票失败：落脱敏 error，status=error（不再 logged_out），不写凭据")
    void tokenExchangeFailure_recordsError() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.ERROR)) {
            FakeProviderService store = new FakeProviderService();
            GoogleOAuthService service = new GoogleOAuthService(store,
                mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
                token.url());
            String url = service.buildAuthorizeUrl("p1").url();

            assertThatThrownBy(() -> service.complete("p1", callbackUrl(url, "code1")))
                .isInstanceOf(OAuthTokenExchangeError.class);

            GoogleOAuthService.Status status = service.status("p1");
            assertThat(status.status()).isEqualTo("error");
            assertThat(status.message()).isNotBlank();
            assertThat(store.saved).isEmpty();
            assertThat(store.row("p1").getOauthAccessEncrypted()).isNull();
        }
    }

    @Test
    @DisplayName("跨 provider state 归属：p1 的 state 只完成 p1，不去动 p2")
    void crossProvider_stateAttribution() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            store.addProvider("p2");
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenReturn("proj-9");
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url());
            String url = service.buildAuthorizeUrl("p1").url();
            String state = queryOf(url).get("state");
            String p2StateBefore = store.states.get("p2");

            service.onCallback(state, "code1");

            assertThat(store.saved).hasSize(1);
            assertThat(store.saved.get(0).providerId).isEqualTo("p1");
            assertThat(store.row("p1").getOauthAccessEncrypted()).isNotNull();
            assertThat(store.row("p2").getOauthAccessEncrypted()).isNull();
            assertThat(store.states.get("p2")).isEqualTo(p2StateBefore);
        }
    }

    // ───────────────────────────── 回调 error（冻结红例） ─────────────────────────────

    @Test
    @DisplayName("A 监听器 error=access_denied：当前有效 attempt 立即 error 终态")
    void listenerError_validState_terminatesAttempt() throws Exception {
        FakeProviderService store = new FakeProviderService();
        GoogleOAuthService service = new GoogleOAuthService(store,
            mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
            "http://127.0.0.1:1/token");
        GoogleOAuthCallbackServer server = new GoogleOAuthCallbackServer(service);
        service.bindCallbackServer(() -> server);
        try {
            GoogleOAuthService.AuthorizeResult auth = service.buildAuthorizeUrl("p1");
            int port = server.runningPort();
            assertThat(port).isGreaterThan(0);

            httpGet("http://127.0.0.1:" + port + "/oauth2callback?error=access_denied&state="
                + queryOf(auth.url()).get("state"));

            GoogleOAuthService.Status status = service.status("p1");
            assertThat(status.status()).isEqualTo("error");
            assertThat(status.message())
                .isEqualTo(GoogleOAuthService.safeOAuthErrorMessage("access_denied"))
                .doesNotContain("access_denied");
            assertThat(store.row("p1").getOauthAccessEncrypted()).isNull();
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("A2 手动 complete 完整 error URL：当前有效 attempt 立即 error 终态，且不发换票")
    void manualErrorUrl_validState_terminatesAttempt() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            GoogleOAuthService service = new GoogleOAuthService(store,
                mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
                token.url());
            GoogleOAuthService.AuthorizeResult auth = service.buildAuthorizeUrl("p1");
            Map<String, String> q = queryOf(auth.url());

            assertThatThrownBy(() -> service.complete("p1",
                q.get("redirect_uri") + "?error=access_denied&state=" + q.get("state")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(GoogleOAuthService.safeOAuthErrorMessage("access_denied"));

            assertThat(service.status("p1").status()).isEqualTo("error");
            assertThat(token.count()).as("error 回调不得触发换票").isZero();
            assertThat(store.saved).isEmpty();
        }
    }

    @Test
    @DisplayName("B 未知/缺 state/过期 state/他 provider 的 error：都不取消合法 attempt")
    void listenerError_nonMatchingState_leavesAttemptPending() throws Exception {
        FakeProviderService store = new FakeProviderService();
        store.addProvider("p2");
        GoogleOAuthService service = new GoogleOAuthService(store,
            mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
            "http://127.0.0.1:1/token");
        GoogleOAuthCallbackServer server = new GoogleOAuthCallbackServer(service);
        service.bindCallbackServer(() -> server);
        try {
            service.buildAuthorizeUrl("p1");
            GoogleOAuthService.AuthorizeResult p2 = service.buildAuthorizeUrl("p2");
            int port = server.runningPort();
            String base = "http://127.0.0.1:" + port + "/oauth2callback?error=access_denied";

            httpGet(base + "&state=bogus");
            assertThat(service.status("p1").status()).as("B1 未知 state").isEqualTo("pending");
            assertThat(service.status("p2").status()).as("B1 不动他 provider").isEqualTo("pending");

            httpGet(base);
            assertThat(service.status("p1").status()).as("B2 无 state").isEqualTo("pending");

            long old = System.currentTimeMillis()
                - GoogleOAuthProviderConfig.LOGIN_STATE_TTL_MILLIS - 1000L;
            store.states.put("p1", GoogleOAuthService.encodeStates(List.of(
                new GoogleOAuthService.PendingState("expired-s0", old,
                    "http://127.0.0.1:" + port + "/oauth2callback"))));
            httpGet(base + "&state=expired-s0");
            assertThat(service.status("p1").status()).as("B3 过期 state").isEqualTo("pending");

            httpGet(base + "&state=" + queryOf(p2.url()).get("state"));
            assertThat(service.status("p1").status()).as("B4 他 provider state").isEqualTo("pending");
            assertThat(service.status("p2").status()).as("B4 仅影响其自身").isEqualTo("error");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("C ready/logout 后迟到 error 不覆盖、不复活")
    void lateError_afterReadyOrLogout_doesNotOverride() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            GoogleCodeAssistClient assist = mock(GoogleCodeAssistClient.class);
            when(assist.resolveProject(anyString(), any(Duration.class))).thenReturn("proj-9");
            GoogleOAuthService service = new GoogleOAuthService(store, assist,
                new OAuthTokenClient(HttpClient.newHttpClient()), token.url());
            GoogleOAuthService.AuthorizeResult auth = service.buildAuthorizeUrl("p1");
            Map<String, String> q = queryOf(auth.url());
            service.complete("p1", q.get("redirect_uri") + "?code=c1&state=" + q.get("state"));
            assertThat(service.status("p1").status()).as("C0 前置成功").isEqualTo("ready");

            assertThatThrownBy(() -> service.complete("p1",
                q.get("redirect_uri") + "?error=access_denied&state=" + q.get("state")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.status("p1").status()).as("C1 不覆盖 ready").isEqualTo("ready");

            service.logout("p1");
            assertThatThrownBy(() -> service.complete("p1",
                q.get("redirect_uri") + "?error=access_denied&state=" + q.get("state")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.status("p1").status()).as("C2 不复活").isEqualTo("logged_out");
            assertThat(store.row("p1").getOauthAccessEncrypted()).isNull();
        }
    }

    @Test
    @DisplayName("D error_description/HTML 不被反射进异常文案或状态")
    void errorMessage_doesNotReflectProviderText() {
        FakeProviderService store = new FakeProviderService();
        GoogleOAuthService service = new GoogleOAuthService(store,
            mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
            "http://127.0.0.1:1/token");
        GoogleOAuthService.AuthorizeResult auth = service.buildAuthorizeUrl("p1");
        Map<String, String> q = queryOf(auth.url());
        String bad = q.get("redirect_uri") + "?error=access_denied"
            + "&error_description=%3Cscript%3Ealert(1)%3C%2Fscript%3E&state=" + q.get("state");

        String thrown = "";
        try {
            service.complete("p1", bad);
        } catch (RuntimeException e) {
            thrown = e.getMessage() == null ? "" : e.getMessage();
        }
        GoogleOAuthService.Status status = service.status("p1");
        String statusMessage = status.message() == null ? "" : status.message();
        for (String text : List.of(thrown, statusMessage)) {
            assertThat(text)
                .doesNotContain("access_denied")
                .doesNotContain("script")
                .doesNotContain("error_description")
                .doesNotContain("<");
        }
        assertThat(status.status()).isEqualTo("error");
    }

    @Test
    @DisplayName("E 裸 error 串不当授权码送换票：只拒绝，不取消真实 pending")
    void bareErrorToken_notExchangedAndDoesNotCancelPending() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH)) {
            FakeProviderService store = new FakeProviderService();
            GoogleOAuthService service = new GoogleOAuthService(store,
                mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
                token.url());
            service.buildAuthorizeUrl("p1");

            assertThatThrownBy(() -> service.complete("p1", "access_denied"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(token.count()).as("裸 error 不得送 token 端点").isZero();
            assertThat(service.status("p1").status())
                .as("无 state ⇒ 无法归属，不取消真实 pending").isEqualTo("pending");
        }
    }

    @Test
    @DisplayName("新 authorize 替换后：旧 state 的迟到 error 不覆盖新 attempt")
    void supersededAttempt_lateErrorCannotOverrideNewAttempt() {
        FakeProviderService store = new FakeProviderService();
        GoogleOAuthService service = new GoogleOAuthService(store,
            mock(GoogleCodeAssistClient.class), new OAuthTokenClient(HttpClient.newHttpClient()),
            "http://127.0.0.1:1/token");
        String oldState = queryOf(service.buildAuthorizeUrl("p1").url()).get("state");
        service.buildAuthorizeUrl("p1");
        assertThat(service.status("p1").status()).isEqualTo("pending");

        service.onCallbackError(oldState, "access_denied");

        assertThat(service.status("p1").status())
            .as("旧 attempt 已被替换 ⇒ 迟到 error 不得终结新 attempt").isEqualTo("pending");
    }

    // ───────────────────────────── 刷新表单 ─────────────────────────────

    @Test
    @DisplayName("refresh grant：form-urlencoded 含 grant_type=refresh_token；响应无新 refresh → null（调用方保留旧值）")
    void refresh_formAndMissingNewRefresh() throws Exception {
        try (TokenStub token = TokenStub.start(TokenStub.Mode.REFRESH_TOKEN)) {
            OAuthTokenClient client = new OAuthTokenClient(HttpClient.newHttpClient());
            Map<String, String> params = new LinkedHashMap<>();
            params.put("grant_type", "refresh_token");
            params.put("refresh_token", "old-refresh");
            params.put("client_id", GoogleOAuthProviderConfig.CLIENT_ID);
            params.put("client_secret", GoogleOAuthProviderConfig.INSTANCE.clientSecret());

            OAuthTokenResponse refreshed = client.refreshTokens(token.url(), params,
                GoogleOAuthProviderConfig.INSTANCE);

            assertThat(token.lastBody()).contains("grant_type=refresh_token")
                .contains("refresh_token=old-refresh");
            assertThat(refreshed.accessToken()).isEqualTo("access-r");
            assertThat(refreshed.refreshToken()).as("无新 refresh_token → null，由调用方沿用旧值").isNull();
            assertThat(refreshed.expiresAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("Google 刷新器声明 google_oauth（ProviderService 按它选刷新器）")
    void refresherAuthMode() {
        assertThat(new GoogleOAuthService.GoogleOAuthRefresher().authMode())
            .isEqualTo(ProviderService.AUTH_MODE_GOOGLE_OAUTH);
    }

    // ───────────────────────────── 测试辅助 ─────────────────────────────

    /** 轮询等待监听被异步释放（最多 3s），避免脆弱短 sleep。 */
    private static void awaitReleased(GoogleOAuthCallbackServer server, int port) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            if (server.runningPort() != port) {
                return;
            }
            Thread.sleep(25L);
        }
    }

    /** 对本机回调监听发一次 GET（只读触发，断言走 service.status）。 */
    private static void httpGet(String url) throws Exception {
        HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(URI.create(url)).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private record Saved(String providerId, String access, String refresh, Long expiresAt,
                         String project) {}

    /** 内存 ProviderService 替身：多 provider、只覆盖登录链需要的读写。 */
    private static final class FakeProviderService extends ProviderService {
        final Map<String, String> states = new ConcurrentHashMap<>();
        final Map<String, ProviderRecord> rows = new ConcurrentHashMap<>();
        final List<Saved> saved = Collections.synchronizedList(new ArrayList<>());

        FakeProviderService() {
            addProvider("p1");
        }

        void addProvider(String id) {
            ProviderRecord r = new ProviderRecord();
            r.setId(id);
            r.setName("Google");
            r.setType("gemini_code_assist");
            r.setBaseUrl("https://cloudcode-pa.googleapis.com");
            r.setAuthMode(ProviderService.AUTH_MODE_API_KEY);
            r.setEnabled(Boolean.TRUE);
            rows.put(id, r);
        }

        ProviderRecord row(String id) {
            return rows.get(id);
        }

        @Override public String readOAuthState(String providerId) {
            return states.get(providerId);
        }

        @Override public void writeOAuthState(String providerId, String state) {
            if (state == null) {
                states.remove(providerId);
            } else {
                states.put(providerId, state);
            }
        }

        @Override public OAuthSnapshot getOAuthSnapshot(String providerId) {
            ProviderRecord r = rows.get(providerId);
            if (r == null) {
                return null;
            }
            return new OAuthSnapshot(providerId, r.getType(), r.getAuthMode(), r.getOauthExpiresAt(),
                r.getOauthProject() == null || r.getOauthProject().isBlank() ? null
                    : r.getOauthProject(),
                r.getOauthAccessEncrypted() != null && !r.getOauthAccessEncrypted().isBlank(),
                r.getOauthRefreshEncrypted() != null && !r.getOauthRefreshEncrypted().isBlank());
        }

        @Override public void saveGoogleOAuthCredential(String providerId, String access,
                String refresh, Long expiresAt, String project) {
            ProviderRecord r = rows.get(providerId);
            r.setAuthMode(ProviderService.AUTH_MODE_GOOGLE_OAUTH);
            r.setOauthAccessEncrypted(access);
            if (refresh != null && !refresh.isBlank()) {
                r.setOauthRefreshEncrypted(refresh);
            }
            r.setOauthExpiresAt(expiresAt);
            r.setOauthProject(project);
            saved.add(new Saved(providerId, access, refresh, expiresAt, project));
        }

        @Override public void clearOAuth(String providerId) {
            ProviderRecord r = rows.get(providerId);
            r.setAuthMode(ProviderService.AUTH_MODE_API_KEY);
            r.setOauthAccessEncrypted(null);
            r.setOauthRefreshEncrypted(null);
            r.setOauthExpiresAt(null);
            r.setOauthProject(null);
        }

        @Override public List<ProviderDto> listAll() {
            List<ProviderDto> out = new ArrayList<>();
            for (ProviderRecord r : rows.values()) {
                out.add(new ProviderDto(r.getId(), r.getName(), ProviderType.gemini_code_assist,
                    r.getBaseUrl(), null, Map.of(), true, List.of(), null, null,
                    r.getAuthMode(), null, r.getOauthExpiresAt(), null));
            }
            return out;
        }
    }

    /** 可推进的时钟（单测不用真实等待）。 */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis;

        MutableClock(long start) {
            this.millis = new AtomicLong(start);
        }

        void set(long value) {
            millis.set(value);
        }

        @Override public long millis() {
            return millis.get();
        }

        @Override public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** 手动调度器：到期由测试显式 fireAll 触发（确定性）。 */
    private static final class ManualScheduler implements GoogleOAuthService.DeadlineScheduler {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override public synchronized Cancellable schedule(long delayMillis, Runnable task) {
            tasks.add(task);
            return () -> {
                synchronized (ManualScheduler.this) {
                    tasks.remove(task);
                }
            };
        }

        void fireAll() {
            List<Runnable> copy;
            synchronized (this) {
                copy = new ArrayList<>(tasks);
                tasks.clear();
            }
            copy.forEach(Runnable::run);
        }
    }

    private static String callbackUrl(String authorizeUrl, String code) {
        Map<String, String> q = queryOf(authorizeUrl);
        return q.get("redirect_uri") + "?code=" + code + "&state=" + q.get("state");
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = URI.create(url).getRawQuery();
        if (raw == null) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            result.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    /** 本地 token 端点桩（仅 127.0.0.1）。 */
    private static final class TokenStub implements AutoCloseable {
        enum Mode { REFRESH, NO_REFRESH, ERROR, REFRESH_TOKEN }

        private final HttpServer server;
        private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());

        private TokenStub(HttpServer server) {
            this.server = server;
        }

        static TokenStub start(Mode mode) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TokenStub stub = new TokenStub(server);
            server.createContext("/token", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
                stub.bodies.add(requestBody);
                int status = 200;
                String json;
                switch (mode) {
                    case NO_REFRESH:
                        json = "{\"access_token\":\"access-1\",\"expires_in\":3600,"
                            + "\"token_type\":\"Bearer\"}";
                        break;
                    case ERROR:
                        status = 400;
                        json = "{\"error\":\"invalid_grant\"}";
                        break;
                    case REFRESH_TOKEN:
                        json = "{\"access_token\":\"access-r\",\"expires_in\":3600,"
                            + "\"token_type\":\"Bearer\"}";
                        break;
                    case REFRESH:
                    default:
                        json = "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\","
                            + "\"expires_in\":3600,\"token_type\":\"Bearer\"}";
                        break;
                }
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return stub;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
        }

        String lastBody() {
            synchronized (bodies) {
                return bodies.isEmpty() ? "" : bodies.get(bodies.size() - 1);
            }
        }

        int count() {
            synchronized (bodies) {
                return bodies.size();
            }
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
