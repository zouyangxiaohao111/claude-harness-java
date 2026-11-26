package com.nexusai.application.agent.subagent.external;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * key 注入的别名覆盖回归。
 *
 * <p><b>WHY</b>（2026-11-26 真机）：设置页填的 key 落在 {@code __default__}，旧实现只写进
 * {@code apiKeyEnvs().get(0)} ⇒ codex 的 config.toml 用 {@code env_key="OPENAI_API_KEY"}
 * 时读到的是空，报 {@code Missing environment variable: OPENAI_API_KEY}；claude 的
 * {@code ANTHROPIC_AUTH_TOKEN} 同理。同一个 key 本来就是这些别名的同义值，必须全写。
 *
 * <p>同时锁住边界：未配置 ⇒ 一个都不写；只写该预设声明的名字，绝不碰进程里的其它变量。
 */
@DisplayName("[external-agent] key 注入写入全部别名变量")
class ExternalRuntimeEnvStoreKeyAliasTest {

    @AfterEach
    void restoreSnapshot() {
        ExternalRuntimeEnvStore.replace(null);
    }

    @Test
    @DisplayName("codex：一个 key ⇒ CODEX_API_KEY 与 OPENAI_API_KEY 都设置")
    void codexWritesBothAliases() {
        withDefaultKey("codex", "k-123");

        Map<String, String> env = ExternalRuntimeEnvStore.envFor(ExternalRuntimeCatalog.find("codex"));

        assertThat(env)
                .containsEntry("CODEX_API_KEY", "k-123")
                .containsEntry("OPENAI_API_KEY", "k-123")
                .hasSize(2);
    }

    @Test
    @DisplayName("claude：一个 key ⇒ ANTHROPIC_API_KEY 与 ANTHROPIC_AUTH_TOKEN 都设置")
    void claudeWritesBothAliases() {
        withDefaultKey("claude", "k-456");

        Map<String, String> env = ExternalRuntimeEnvStore.envFor(ExternalRuntimeCatalog.find("claude"));

        assertThat(env)
                .containsEntry("ANTHROPIC_API_KEY", "k-456")
                .containsEntry("ANTHROPIC_AUTH_TOKEN", "k-456")
                .hasSize(2);
    }

    @Test
    @DisplayName("agy：只有 GEMINI_API_KEY 一个别名")
    void agyWritesSingleAlias() {
        withDefaultKey("agy", "k-789");

        Map<String, String> env = ExternalRuntimeEnvStore.envFor(ExternalRuntimeCatalog.find("agy"));

        assertThat(env).hasSize(1).containsEntry("GEMINI_API_KEY", "k-789");
    }

    @Test
    @DisplayName("未配置 ⇒ 空 map（不注入空串）")
    void unconfiguredWritesNothing() {
        ExternalRuntimeEnvStore.replace(Map.of());

        for (String runtime : new String[]{"codex", "claude", "agy"}) {
            assertThat(ExternalRuntimeEnvStore.envFor(ExternalRuntimeCatalog.find(runtime)))
                    .as("runtime=%s 未配置时不能写任何变量", runtime)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("显式指定别名 ⇒ 只写那一个（不扩散到别的别名）")
    void explicitAliasDoesNotFanOut() {
        ExternalRuntimeEnvStore.replace(Map.of("codex", new ExternalRuntimeEnvStore.Overlay(
                null, null, Map.of("OPENAI_API_KEY", "only-this"), null, null, null)));

        Map<String, String> env = ExternalRuntimeEnvStore.envFor(ExternalRuntimeCatalog.find("codex"));

        assertThat(env).hasSize(1).containsEntry("OPENAI_API_KEY", "only-this");
    }

    @Test
    @DisplayName("只写预设声明的变量名，不产生无关环境变量")
    void neverTouchesUnrelatedNames() {
        withDefaultKey("codex", "k-000");

        var preset = ExternalRuntimeCatalog.find("codex");
        Map<String, String> env = ExternalRuntimeEnvStore.envFor(preset);

        assertThat(env.keySet()).isSubsetOf(preset.apiKeyEnvs());
        assertThat(env).doesNotContainKeys("PATH", "HOME", "HTTPS_PROXY");
    }

    private static void withDefaultKey(String runtime, String key) {
        ExternalRuntimeEnvStore.replace(Map.of(runtime, new ExternalRuntimeEnvStore.Overlay(
                null, null, Map.of("__default__", key), null, null, null)));
    }
}