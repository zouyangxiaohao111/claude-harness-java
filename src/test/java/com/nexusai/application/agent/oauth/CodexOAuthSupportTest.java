package com.nexusai.application.agent.oauth;

import com.nexusai.domain.provider.ProviderService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2b · ChatGPT 订阅（Codex）登录链路的纯函数部分（PKCE / state / JWT / 回调解析）。
 *
 * <p><b>⛔ 不启 Spring</b>：本测试只覆盖不依赖 DB / HTTP 的静态工具方法，
 * 登录落库与 token 交换由人工真机验证。
 */
class CodexOAuthSupportTest {

    // ───────────────────────────── PKCE ─────────────────────────────

    @Test
    @DisplayName("PKCE challenge = Base64URL(SHA-256(verifier))，对齐 RFC 7636 附录 B 示例")
    void codeChallenge_matchesRfc7636Example() {
        // RFC 7636 附录 B 的官方示例值
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertThat(CodexOAuthService.codeChallenge(verifier))
            .as("授权服务器会按同一算法校验 code_verifier，算法错则换 token 必失败")
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    @DisplayName("生成的 verifier 是 43 字符 Base64URL 字符集（无填充）")
    void newVerifier_is43UrlSafeChars() {
        for (int i = 0; i < 20; i++) {
            String verifier = CodexOAuthService.newVerifier();
            assertThat(verifier)
                .as("32 字节 Base64URL 无填充 = 43 字符，且只能含 RFC 7636 §4.1 允许的 unreserved 字符")
                .hasSize(43)
                .matches("[A-Za-z0-9\\-_]+");
        }
    }

    @Test
    @DisplayName("state 是 32 位 hex（16 字节随机）")
    void newState_is32HexChars() {
        assertThat(CodexOAuthService.newState()).hasSize(32).matches("[0-9a-f]{32}");
    }

    // ───────────────────────────── JWT ─────────────────────────────

    @Test
    @DisplayName("从 access token 的 JWT payload 取 chatgpt_account_id")
    void accountIdFromAccessToken_readsClaim() {
        String token = jwt("""
            {"https://api.openai.com/auth":{"chatgpt_account_id":"acct_1"}}
            """);
        assertThat(CodexOAuthService.accountIdFromAccessToken(token)).isEqualTo("acct_1");
    }

    @Test
    @DisplayName("缺 chatgpt_account_id → 抛错（不存残缺凭据）")
    void accountIdFromAccessToken_missingClaim_throws() {
        String token = jwt("""
            {"https://api.openai.com/auth":{}}
            """);
        assertThatThrownBy(() -> CodexOAuthService.accountIdFromAccessToken(token))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chatgpt_account_id");
    }

    @Test
    @DisplayName("非 JWT 形态 → 抛错")
    void accountIdFromAccessToken_notJwt_throws() {
        assertThatThrownBy(() -> CodexOAuthService.accountIdFromAccessToken("opaque-token"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ───────────────────────────── state JSON ─────────────────────────────

    @Test
    @DisplayName("state JSON 编解码往返一致")
    void stateJson_roundTrip() {
        CodexOAuthService.AuthState original =
            new CodexOAuthService.AuthState("state-abc", "verifier-xyz");
        String json = CodexOAuthService.encodeState(original);
        assertThat(json)
            .as("oauth_state 列存 JSON，两个字段名固定")
            .contains("\"state\"").contains("\"verifier\"");
        assertThat(CodexOAuthService.decodeState(json)).isEqualTo(original);
    }

    @Test
    @DisplayName("state JSON 缺字段 → 抛错（fail-loud，不让空 verifier 去换 token）")
    void stateJson_missingField_throws() {
        assertThatThrownBy(() -> CodexOAuthService.decodeState("{\"state\":\"s\"}"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ───────────────────────────── 回调解析 ─────────────────────────────

    @Test
    @DisplayName("整条回调 URL → 解析出 code/state")
    void parseCallback_fullUrl() {
        CodexOAuthService.CallbackInput input = CodexOAuthService.parseCallback(
            "http://localhost:1455/auth/callback?code=C1&state=S1");
        assertThat(input.code()).isEqualTo("C1");
        assertThat(input.state()).isEqualTo("S1");
    }

    @Test
    @DisplayName("裸 code → state 为 null（由已存的 oauth_state 兜底）")
    void parseCallback_bareCode() {
        CodexOAuthService.CallbackInput input = CodexOAuthService.parseCallback("C1");
        assertThat(input.code()).isEqualTo("C1");
        assertThat(input.state()).isNull();
    }

    @Test
    @DisplayName("回调 URL 带 error 参数 → 抛中文可读错误")
    void parseCallback_errorParam_throws() {
        assertThatThrownBy(() -> CodexOAuthService.parseCallback(
            "http://localhost:1455/auth/callback?error=access_denied&state=S1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("access_denied");
    }

    // ───────────────────────── exchangeParams ─────────────────────────

    @Test
    @DisplayName("授权码交换表单含 grant_type 且不重复（客户端不自动注入）")
    void exchangeParams_containsGrantTypeOnce() {
        CodexOAuthProviderConfig config = new CodexOAuthProviderConfig();
        Map<String, String> params = config.exchangeParams("C1", config.redirectUri(), "V1");

        assertThat(params).containsEntry("grant_type", "authorization_code");
        assertThat(params).containsEntry("code", "C1");
        assertThat(params).containsEntry("code_verifier", "V1");
        assertThat(params).containsEntry("redirect_uri", CodexOAuthProviderConfig.REDIRECT_URI);
        assertThat(params).containsEntry("client_id", CodexOAuthProviderConfig.CLIENT_ID);
        assertThat(params).containsOnlyKeys(
            "grant_type", "code", "redirect_uri", "client_id", "code_verifier");
        // 超集检查：只有 5 个键，故表单里 grant_type 只出现一次
        assertThat(params.entrySet().stream().filter(e -> "grant_type".equals(e.getKey())).count())
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("provider 配置常量：端点/scope/public client 无 secret")
    void providerConfig_constantValues() {
        CodexOAuthProviderConfig config = new CodexOAuthProviderConfig();
        assertThat(config.provider()).isEqualTo("openai-codex");
        assertThat(config.authorizationEndpoint())
            .isEqualTo("https://auth.openai.com/oauth/authorize");
        assertThat(config.tokenEndpoint()).isEqualTo("https://auth.openai.com/oauth/token");
        assertThat(config.scopes())
            .containsExactly("openid", "profile", "email", "offline_access");
        assertThat(config.userInfoEndpoint()).isEmpty();
        assertThat(config.clientSecret()).isEmpty();
        assertThat(config.accessTokenExpires()).isTrue();
        assertThat(config.supportsRefreshToken()).isTrue();
    }

    @Test
    @DisplayName("刷新器是独立的静态 @Component（与 ProviderService 无循环依赖）")
    void refresher_isSeparateSpringComponent() {
        Class<?> type = CodexOAuthService.CodexOAuthRefresher.class;
        assertThat(java.lang.reflect.Modifier.isStatic(type.getModifiers()))
            .as("非静态内部类不能被组件扫描注册")
            .isTrue();
        assertThat(type.isAnnotationPresent(org.springframework.stereotype.Component.class))
            .as("不在 ProviderService 上做 @Lazy 闭环，故本类必须是独立 @Component")
            .isTrue();
        assertThat(ProviderService.OAuthRefresher.class.isAssignableFrom(type)).isTrue();
        assertThat(type.getDeclaredConstructors())
            .as("Spring 需要无参构造（OAuthTokenClient 不是 bean）")
            .anyMatch(c -> c.getParameterCount() == 0);
    }

    /** 手工拼 {@code header.payload.sig}（signature 内容无关，被测代码不校验签名）。 */
    private static String jwt(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }
}
