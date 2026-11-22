package com.nexusai.infra.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [保密 · 2026-11-22 第二批] 统一掩码工具：provider extraHeaders / MCP env+headers /
 * settings.proxy 共用同一套「标量掩码 + 敏感键判定 + 写侧哨兵」。
 *
 * <p>WHY 集中在这里测：三处各写一套必然漂移（一处改阈值、另一处没改 ⇒ 同一 token 两种掩码，
 * 哨兵对不上就会把真值覆盖）。规则由用户 2026-11-22 裁定：
 * 保留末四位；标量 &lt; 8 整体 {@code ****}；敏感键名 =
 * 后缀 KEY/TOKEN/SECRET/PASSWORD 或 AUTHORIZATION（大小写不敏感）。
 */
class ApiKeyHasherSecretMaskTest {

    @Test
    @DisplayName("maskSecret：≥8 保留末四位；<8 整体 ****；null/空 → ****")
    void maskSecret_rules() {
        assertThat(ApiKeyHasher.maskSecret("sk-live-abcdefgh1234")).isEqualTo("****1234");
        assertThat(ApiKeyHasher.maskSecret("12345678")).isEqualTo("****5678");
        assertThat(ApiKeyHasher.maskSecret("1234567")).as("<8 整体掩码").isEqualTo("****");
        assertThat(ApiKeyHasher.maskSecret("")).isEqualTo("****");
        assertThat(ApiKeyHasher.maskSecret(null)).isEqualTo("****");
    }

    @Test
    @DisplayName("isSensitiveName：后缀 KEY/TOKEN/SECRET/PASSWORD + AUTHORIZATION；普通名不命中")
    void sensitiveName_rules() {
        assertThat(ApiKeyHasher.isSensitiveName("Authorization")).isTrue();
        assertThat(ApiKeyHasher.isSensitiveName("X-API-KEY")).isTrue();
        assertThat(ApiKeyHasher.isSensitiveName("access_token")).isTrue();
        assertThat(ApiKeyHasher.isSensitiveName("CLIENT_SECRET")).isTrue();
        assertThat(ApiKeyHasher.isSensitiveName("DB_PASSWORD")).isTrue();
        assertThat(ApiKeyHasher.isSensitiveName("OPENAI_API_KEY")).isTrue();

        assertThat(ApiKeyHasher.isSensitiveName("LOG_LEVEL")).isFalse();
        assertThat(ApiKeyHasher.isSensitiveName("X-Trace-Id")).isFalse();
        assertThat(ApiKeyHasher.isSensitiveName("PATH")).isFalse();
        assertThat(ApiKeyHasher.isSensitiveName(null)).isFalse();
    }

    @Test
    @DisplayName("maskSensitiveMap：只掩敏感键，非敏感键与顺序原样，空值保持空")
    void maskSensitiveMap_onlySensitive() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("LOG_LEVEL", "debug");
        in.put("Authorization", "Bearer sk-live-abcdefgh1234");
        in.put("X-Trace-Id", "trace-1");
        in.put("ACCESS_TOKEN", "tok-1234567890");
        in.put("EMPTY_SECRET", "");

        Map<String, String> out = ApiKeyHasher.maskSensitiveMap(in);

        assertThat(out).containsEntry("LOG_LEVEL", "debug");
        assertThat(out).containsEntry("X-Trace-Id", "trace-1");
        assertThat(out).containsEntry("Authorization", "****1234");
        assertThat(out).containsEntry("ACCESS_TOKEN", "****7890");
        assertThat(out).containsEntry("EMPTY_SECRET", "").as("空值保持空，界面能看出没配");
        assertThat(out.keySet()).containsExactlyElementsOf(in.keySet());
        assertThat(ApiKeyHasher.maskSensitiveMap(null)).isNull();
    }

    @Test
    @DisplayName("mergeMaskedMap：掩码回传保留库中真值；库里没有该键 → 丢弃；其余按入参写")
    void mergeMaskedMap_sentinel() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("Authorization", "Bearer real-secret-9999");
        stored.put("LOG_LEVEL", "info");

        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("Authorization", "****9999");     // 掩码回传
        incoming.put("LOG_LEVEL", "debug");            // 普通键照改
        incoming.put("NEW_TOKEN", "****abcd");         // 库里没有 ⇒ 丢弃（不存掩码）

        Map<String, String> out = ApiKeyHasher.mergeMaskedMap(incoming, stored);

        assertThat(out).containsEntry("Authorization", "Bearer real-secret-9999");
        assertThat(out).containsEntry("LOG_LEVEL", "debug");
        assertThat(out).doesNotContainKey("NEW_TOKEN");
        assertThat(ApiKeyHasher.mergeMaskedMap(null, stored)).isNull();
    }

    @Test
    @DisplayName("maskProxyPassword / unmaskProxyPassword：只动口令段，主机端口可读且可只改主机")
    void proxyPassword_rules() {
        String stored = "http://user:s3cret-pass@127.0.0.1:7897";
        assertThat(ApiKeyHasher.maskProxyPassword(stored))
            .isEqualTo("http://user:****@127.0.0.1:7897");
        assertThat(ApiKeyHasher.maskProxyPassword("127.0.0.1:7897"))
            .as("无凭据的代理原样").isEqualTo("127.0.0.1:7897");
        assertThat(ApiKeyHasher.maskProxyPassword("http://user@host:1"))
            .as("只有用户名没有口令 → 原样").isEqualTo("http://user@host:1");

        // 用户只改主机、口令留掩码 ⇒ 换回真口令
        assertThat(ApiKeyHasher.unmaskProxyPassword("http://user:****@10.0.0.2:8080", stored))
            .isEqualTo("http://user:s3cret-pass@10.0.0.2:8080");
        // 没有可还原的口令 → 原样（不会把掩码写进库）
        assertThat(ApiKeyHasher.unmaskProxyPassword("http://user:****@h:1", null))
            .isEqualTo("http://user:****@h:1");
        // 明文口令 → 原样
        assertThat(ApiKeyHasher.unmaskProxyPassword("http://u:p@h:1", stored))
            .isEqualTo("http://u:p@h:1");
    }
}
