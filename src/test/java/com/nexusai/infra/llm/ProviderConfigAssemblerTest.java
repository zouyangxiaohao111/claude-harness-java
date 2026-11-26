package com.nexusai.infra.llm;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ProviderConfigAssembler：固定 Cloud Code host + project 透传 + headers/extraBody 不丢。 */
class ProviderConfigAssemblerTest {

    @Test
    @DisplayName("forGoogleOAuth：baseUrl 强制 cloudcode-pa，project 去空白，token 原样")
    void forcesHostAndPassesProject() {
        ProviderConfig config = ProviderConfigAssembler.forGoogleOAuth("tok",
            "  proj-1  ", Map.of("X-A", "1"), Map.of("temperature", 0.2));

        assertThat(config.baseUrl()).isEqualTo("https://cloudcode-pa.googleapis.com");
        assertThat(config.apiKey()).isEqualTo("tok");
        assertThat(config.oauthProject()).isEqualTo("proj-1");
        assertThat(config.extraHeaders()).containsEntry("X-A", "1");
        assertThat(config.extraBody()).containsEntry("temperature", 0.2);
    }

    @Test
    @DisplayName("project 为空 → null（由 provider 快速失败，⛔ 不伪造）")
    void blankProjectBecomesNull() {
        assertThat(ProviderConfigAssembler.forGoogleOAuth("tok", "  ", null, null).oauthProject())
            .isNull();
        assertThat(ProviderConfigAssembler.forGoogleOAuth("tok", null, null, null).oauthProject())
            .isNull();
    }

    @Test
    @DisplayName("headers 复制为可写 map：调用方传不可变视图也不会在后续注入时炸")
    void copiesHeadersIntoMutableMap() {
        Map<String, String> immutable = Map.of("A", "1");
        ProviderConfig config = ProviderConfigAssembler.forGoogleOAuth("tok", "p", immutable, null);

        assertThat(config.extraHeaders()).isNotSameAs(immutable);
        Map<String, String> mutable = new HashMap<>(config.extraHeaders());
        mutable.put("B", "2");
        assertThat(mutable).containsEntry("B", "2");
    }

    @Test
    @DisplayName("普通构造器不凭空补 project；5 参显式传入才生效")
    void legacyConstructorsKeepNullProject() {
        Map<String, String> headers = new LinkedHashMap<>();
        assertThat(new ProviderConfig("u", "k").oauthProject()).isNull();
        assertThat(new ProviderConfig("u", "k", headers).oauthProject()).isNull();
        assertThat(new ProviderConfig("u", "k", headers, Map.of()).oauthProject()).isNull();
        assertThat(new ProviderConfig("u", "k", headers, Map.of(), "p").oauthProject()).isEqualTo("p");
    }

    @Test
    @DisplayName("isGoogleOAuth 只认 google_oauth")
    void authModeMatch() {
        assertThat(ProviderConfigAssembler.isGoogleOAuth("google_oauth")).isTrue();
        assertThat(ProviderConfigAssembler.isGoogleOAuth("codex_oauth")).isFalse();
        assertThat(ProviderConfigAssembler.isGoogleOAuth(null)).isFalse();
    }
}
