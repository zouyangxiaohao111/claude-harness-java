package com.nexusai.application.chat;

import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.provider.entity.ProviderRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [C2c] {@link ChatService#assembleProviderConfig} —— codex_oauth 的请求装配判据。
 *
 * <p>纯 JUnit 5 + AssertJ，<b>不启 Spring</b>：被测方法是 package-private static，
 * 只吃 {@link ProviderRecord} 与明文凭据，无任何容器依赖。
 *
 * <p>两类防线：
 * <ul>
 *   <li>codex_oauth：baseUrl 必须被<b>强制</b>成 {@code /backend-api/codex}（用户填的值不生效），
 *       且注入 {@code chatgpt-account-id}（有 accountId 时）与 {@code originator}；</li>
 *   <li>api_key（含 auth_mode 为 null 的历史行）：baseUrl / headers <b>原样</b>，零 codex 头 ——
 *       防止改造把既有出站行为带偏。</li>
 * </ul>
 */
class CodexOAuthConfigTest {

    private static final String CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";

    private static ProviderRecord provider(String authMode, String accountId) {
        ProviderRecord r = new ProviderRecord();
        r.setId("prov-codex-test");
        r.setName("codex-test");
        r.setType("openai_response");
        // 故意填一个"错"的 baseUrl：codex_oauth 下必须被忽略
        r.setBaseUrl("https://user-filled.example.com/v1");
        r.setAuthMode(authMode);
        r.setOauthAccountId(accountId);
        return r;
    }

    @Test
    @DisplayName("T1 codex_oauth 强制 baseUrl 到 /backend-api/codex")
    void codexOauth_forcesBaseUrl() {
        ProviderConfig config = ChatService.assembleProviderConfig(
            provider("codex_oauth", "acct_1"), "access-token");

        assertThat(config.baseUrl()).isEqualTo(CODEX_BASE_URL);
        assertThat(config.apiKey()).isEqualTo("access-token");
    }

    @Test
    @DisplayName("T2 codex_oauth 注入 chatgpt-account-id 与 originator")
    void codexOauth_injectsAccountIdAndOriginator() {
        ProviderConfig config = ChatService.assembleProviderConfig(
            provider("codex_oauth", "acct_1"), "access-token");

        assertThat(config.extraHeaders())
            .containsEntry("chatgpt-account-id", "acct_1")
            .containsEntry("originator", "codex_cli_rs");
    }

    @Test
    @DisplayName("T3 codex_oauth 无 accountId → 不注入 chatgpt-account-id，仍注入 originator")
    void codexOauth_withoutAccountId_skipsAccountHeader() {
        ProviderConfig config = ChatService.assembleProviderConfig(
            provider("codex_oauth", ""), "access-token");

        assertThat(config.extraHeaders())
            .doesNotContainKey("chatgpt-account-id")
            .containsEntry("originator", "codex_cli_rs");
    }

    @Test
    @DisplayName("T4 api_key 模式 baseUrl / headers 原样，零 codex 头")
    void apiKeyMode_passesThroughUntouched() {
        ProviderRecord r = provider("api_key", "acct_should_be_ignored");
        r.setExtraHeaders("{\"X-Custom\":\"v1\"}");

        ProviderConfig config = ChatService.assembleProviderConfig(r, "sk-test");

        assertThat(config.baseUrl()).isEqualTo("https://user-filled.example.com/v1");
        assertThat(config.extraHeaders()).isEqualTo(Map.of("X-Custom", "v1"));
        assertThat(config.extraHeaders())
            .doesNotContainKey("chatgpt-account-id")
            .doesNotContainKey("originator");
    }

    @Test
    @DisplayName("T5 auth_mode 为 null → 按 api_key 处理")
    void nullAuthMode_behavesLikeApiKey() {
        ProviderRecord r = provider(null, "acct_should_be_ignored");
        r.setExtraHeaders(null);

        ProviderConfig config = ChatService.assembleProviderConfig(r, "sk-test");

        assertThat(config.baseUrl()).isEqualTo("https://user-filled.example.com/v1");
        assertThat(config.extraHeaders()).isNull();
    }
}
