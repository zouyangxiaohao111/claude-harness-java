package com.nexusai.application.chat;

import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.repository.provider.entity.ProviderRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatService 装配点（三路之一）的 Google 分支：与 ProviderConfigAssembler 同口径。
 * ⛔ 纯静态方法测试，不启 Spring / 不碰 DB。
 */
class ChatServiceGoogleConfigTest {

    @Test
    @DisplayName("google_oauth：强制 Cloud Code host + project 透传 + extraHeaders 原义保留")
    void googleForcesHostAndProject() {
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setBaseUrl("https://user-junk.example.com");
        p.setAuthMode("google_oauth");
        p.setOauthProject("  proj-7  ");
        p.setExtraHeaders("{\"X-Test\":\"v\"}");

        ProviderConfig cfg = ChatService.assembleProviderConfig(p, "tok");

        assertThat(cfg.baseUrl()).isEqualTo("https://cloudcode-pa.googleapis.com");
        assertThat(cfg.apiKey()).isEqualTo("tok");
        assertThat(cfg.oauthProject()).isEqualTo("proj-7");
        assertThat(cfg.extraHeaders()).containsEntry("X-Test", "v");
    }

    @Test
    @DisplayName("普通 api_key：baseUrl 原样，且不会凭空带 oauthProject")
    void apiKeyPathUnchanged() {
        ProviderRecord p = new ProviderRecord();
        p.setId("p2");
        p.setBaseUrl("https://api.example.com");
        p.setAuthMode(null);
        p.setOauthProject("stray-project"); // 脏数据也不得泄漏到普通路径

        ProviderConfig cfg = ChatService.assembleProviderConfig(p, "k");

        assertThat(cfg.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(cfg.oauthProject()).isNull();
    }
}
