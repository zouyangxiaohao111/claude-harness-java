package com.nexusai.application.agent.oauth;

import com.nexusai.domain.provider.ProviderService;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Claude（Anthropic）订阅登录链路的纯函数 / 无 DB 部分。
 *
 * <p><b>⛔ 不启 Spring</b>：只覆盖静态工具方法，以及对 {@code ProviderService} 两个存取方法做了
 * 内存替身（{@code buildAuthorizeUrl} 的落库行为）。
 *
 * <p><b>为什么这些断言重要</b>：
 * <ul>
 *   <li>授权 URL 的 {@code state} 必须是 <b>PKCE verifier 本身</b>（上游协议），写成独立随机
 *       state 会让 token 端点拒绝，且回调里拿不到 verifier；</li>
 *   <li>{@code redirect_uri} 必须是 {@code localhost}（而监听绑 127.0.0.1）—— 统一即
 *       {@code redirect_uri_mismatch}；</li>
 *   <li>过期口径 {@code now + expires_in*1000 - 5min} 若写成不扣 5 分钟，请求高峰会 401。</li>
 * </ul>
 */
class ClaudeOAuthSupportTest {

    // ───────────────────────────── PKCE / 授权 URL ─────────────────────────────

    @Test
    @DisplayName("PKCE challenge = Base64URL(SHA-256(verifier))，对齐 RFC 7636 附录 B")
    void codeChallenge_matchesRfc7636Example() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertThat(ClaudeOAuthService.codeChallenge(verifier))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    @DisplayName("生成的 verifier 是 43 字符 Base64URL（无填充）")
    void newVerifier_is43UrlSafeChars() {
        for (int i = 0; i < 20; i++) {
            assertThat(ClaudeOAuthService.newVerifier())
                .hasSize(43)
                .matches("[A-Za-z0-9\\-_]+");
        }
    }

    @Test
    @DisplayName("授权 URL：state 就是 verifier，且含 S256 / localhost redirect_uri / 全部 scope")
    void authorizeUrl_hasExpectedParams() {
        String verifier = "Verifier_ABC-123";
        String url = ClaudeOAuthService.authorizeUrl(verifier);
        Map<String, String> query = queryOf(url);

        assertThat(url).startsWith(ClaudeOAuthProviderConfig.AUTHORIZATION_ENDPOINT + "?");
        assertThat(query)
            .containsEntry("client_id", ClaudeOAuthProviderConfig.CLIENT_ID)
            .containsEntry("response_type", "code")
            .containsEntry("code_challenge_method", "S256")
            .containsEntry("code_challenge", ClaudeOAuthService.codeChallenge(verifier))
            // ⭐ 刻意的不一致：监听 127.0.0.1，但 redirect_uri 必须写 localhost
            .containsEntry("redirect_uri", "http://localhost:53692/callback")
            .containsEntry("scope", String.join(" ", ClaudeOAuthProviderConfig.SCOPES))
            // ⭐ state 就是 verifier 本身（不是独立随机 state）
            .containsEntry("state", verifier);
    }

    @Test
    @DisplayName("授权 URL：verifier 为空 → 抛 IllegalArgumentException（不能发一条无 PKCE 的链接）")
    void authorizeUrl_blankVerifier_throws() {
        assertThatThrownBy(() -> ClaudeOAuthService.authorizeUrl(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaudeOAuthService.authorizeUrl("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ───────────────────────────── state 多槽 ─────────────────────────────

    @Test
    @DisplayName("state 多槽：连续点两次登录 → 两条待完成登录都保留（各自的 verifier 不互相覆盖）")
    void buildAuthorizeUrl_keepsMultiplePendingStates() {
        InMemoryOAuthStateStore store = new InMemoryOAuthStateStore();
        ClaudeOAuthService service = new ClaudeOAuthService(store.service(), HttpClient.newHttpClient());

        String url1 = service.buildAuthorizeUrl("p1");
        String url2 = service.buildAuthorizeUrl("p1");

        List<ClaudeOAuthService.AuthState> pending =
            ClaudeOAuthService.decodeStates(store.raw("p1"));
        assertThat(pending)
            .as("单槽会让第一次点击的 verifier 被覆盖 ⇒ 第一次授权回来时换 token 必失败（Codex 踩过）")
            .hasSize(2);
        // 每条都是 state == verifier，且与两次 URL 里的 state 一一对应
        assertThat(pending.get(0).state()).isEqualTo(pending.get(0).verifier());
        assertThat(pending.get(1).state()).isEqualTo(pending.get(1).verifier());
        assertThat(queryOf(url1)).containsEntry("state", pending.get(0).state());
        assertThat(queryOf(url2)).containsEntry("state", pending.get(1).state());
        assertThat(pending.get(0).state()).isNotEqualTo(pending.get(1).state());
    }

    @Test
    @DisplayName("state 多槽：超过 MAX_PENDING_STATES 只保留最近 N 条")
    void buildAuthorizeUrl_trimsToMaxPendingStates() {
        InMemoryOAuthStateStore store = new InMemoryOAuthStateStore();
        ClaudeOAuthService service = new ClaudeOAuthService(store.service(), HttpClient.newHttpClient());

        String lastUrl = null;
        for (int i = 0; i < ClaudeOAuthService.MAX_PENDING_STATES + 3; i++) {
            lastUrl = service.buildAuthorizeUrl("p1");
        }

        List<ClaudeOAuthService.AuthState> pending =
            ClaudeOAuthService.decodeStates(store.raw("p1"));
        assertThat(pending).hasSize(ClaudeOAuthService.MAX_PENDING_STATES);
        assertThat(pending.get(pending.size() - 1).state())
            .as("最近一次的 state 必须还在（用户刚点的登录不能被挤掉）")
            .isEqualTo(queryOf(lastUrl).get("state"));
    }

    @Test
    @DisplayName("state JSON 编解码往返：数组 / 单对象两种形态都认")
    void stateJson_roundTripAndLegacySingleObject() {
        List<ClaudeOAuthService.AuthState> states = List.of(
            new ClaudeOAuthService.AuthState("s1", "s1"),
            new ClaudeOAuthService.AuthState("s2", "s2"));
        String json = ClaudeOAuthService.encodeStates(states);
        assertThat(ClaudeOAuthService.decodeStates(json)).isEqualTo(states);
        // 旧格式（单对象）
        assertThat(ClaudeOAuthService.decodeStates("{\"state\":\"s1\",\"verifier\":\"s1\"}"))
            .containsExactly(new ClaudeOAuthService.AuthState("s1", "s1"));
        // 空 / null → 空列表
        assertThat(ClaudeOAuthService.decodeStates(null)).isEmpty();
        assertThat(ClaudeOAuthService.decodeStates("  ")).isEmpty();
    }

    // ───────────────────────────── 过期 / token 解析 ─────────────────────────────

    @Test
    @DisplayName("过期口径：now + expires_in*1000 - 5min（提前 5 分钟，防边界 401）")
    void expiresAtFrom_subtractsFiveMinutes() {
        long now = 1_700_000_000_000L;
        assertThat(ClaudeOAuthService.expiresAtFrom(now, 3600))
            .as("3600 秒有效期必须换算成 now + 3600000 - 300000")
            .isEqualTo(now + 3_600_000L - 5 * 60 * 1000L);
        assertThat(ClaudeOAuthProviderConfig.EXPIRY_SKEW_MILLIS).isEqualTo(5 * 60 * 1000L);
    }

    @Test
    @DisplayName("token 响应解析：access_token / refresh_token / expires_in")
    void parseTokens_readsFields() {
        long before = System.currentTimeMillis();
        ClaudeOAuthService.OAuthTokens tokens = ClaudeOAuthService.parseTokens(
            "{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":3600}");
        long after = System.currentTimeMillis();

        assertThat(tokens.accessToken()).isEqualTo("at");
        assertThat(tokens.refreshToken()).isEqualTo("rt");
        assertThat(tokens.expiresAt())
            .isBetween(before + 3_600_000L - 300_000L, after + 3_600_000L - 300_000L);
    }

    @Test
    @DisplayName("token 响应缺 access_token / expires_in → fail-loud（不落残缺凭据）")
    void parseTokens_missingFields_throws() {
        assertThatThrownBy(() -> ClaudeOAuthService.parseTokens("{\"expires_in\":3600}"))
            .isInstanceOf(OAuthTokenExchangeError.class)
            .hasMessageContaining("access_token");
        assertThatThrownBy(() -> ClaudeOAuthService.parseTokens(
            "{\"access_token\":\"at\",\"refresh_token\":\"rt\"}"))
            .isInstanceOf(OAuthTokenExchangeError.class)
            .hasMessageContaining("expires_in");
        assertThatThrownBy(() -> ClaudeOAuthService.parseTokens("not-json"))
            .isInstanceOf(OAuthTokenExchangeError.class);
    }

    @Test
    @DisplayName("回调解析：整条 URL 取 code/state；裸 code → state=null")
    void parseCallback_fullUrlAndBareCode() {
        ClaudeOAuthService.CallbackInput full = ClaudeOAuthService.parseCallback(
            "http://localhost:53692/callback?code=C1&state=V1");
        assertThat(full.code()).isEqualTo("C1");
        assertThat(full.state()).isEqualTo("V1");

        ClaudeOAuthService.CallbackInput bare = ClaudeOAuthService.parseCallback("C1");
        assertThat(bare.code()).isEqualTo("C1");
        assertThat(bare.state()).isNull();

        assertThatThrownBy(() -> ClaudeOAuthService.parseCallback(
            "http://localhost:53692/callback?error=access_denied&state=V1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("access_denied");
    }

    @Test
    @DisplayName("刷新器是独立的静态 @Component，且只服务 claude_oauth")
    void refresher_isSeparateComponentForClaudeMode() {
        Class<?> type = ClaudeOAuthService.ClaudeOAuthRefresher.class;
        assertThat(java.lang.reflect.Modifier.isStatic(type.getModifiers())).isTrue();
        assertThat(type.isAnnotationPresent(org.springframework.stereotype.Component.class)).isTrue();
        assertThat(ProviderService.OAuthRefresher.class.isAssignableFrom(type)).isTrue();
        assertThat(type.getDeclaredConstructors())
            .as("Spring 需要无参构造（HttpClient 不是 bean）")
            .anyMatch(c -> c.getParameterCount() == 0);
        assertThat(new ClaudeOAuthService.ClaudeOAuthRefresher().authMode())
            .as("Codex 刷新器默认 codex_oauth；Claude 必须显式声明自己的形态")
            .isEqualTo(ProviderService.AUTH_MODE_CLAUDE_OAUTH);
    }

    // ───────────────────────────── 测试辅助 ─────────────────────────────

    /** 只重写 oauth_state 两个存取方法的 ProviderService 替身（不碰 mapper / DB）。 */
    private static final class InMemoryOAuthStateStore {
        private final Map<String, String> states = new HashMap<>();

        ProviderService service() {
            return new ProviderService() {
                @Override
                public String readOAuthState(String providerId) {
                    return states.get(providerId);
                }

                @Override
                public void writeOAuthState(String providerId, String state) {
                    states.put(providerId, state);
                }
            };
        }

        String raw(String providerId) {
            return states.get(providerId);
        }
    }

    /** 解析 authorize URL 的 query（值做百分号解码）。 */
    private static Map<String, String> queryOf(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        String rawQuery = URI.create(url).getRawQuery();
        if (rawQuery == null) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            result.put(key, URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return result;
    }
}
