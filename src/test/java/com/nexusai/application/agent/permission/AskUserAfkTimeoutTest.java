package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AskUserQuestion 超时档位解析（CC {@code askUserQuestionTimeout} 对齐）。
 *
 * <p>锁定三条语义（对应用户裁定与 exe 取证）：
 * <ol>
 *   <li>默认/never ⇒ 不启用，且<b>环境变量不能把它变成开启</b>（本仓有意差异，见类注释）；</li>
 *   <li>启用后时长 = {@code CLAUDE_AFK_TIMEOUT_MS} 覆盖设置值；非法 env 忽略；</li>
 *   <li>倒计时阈值默认 20000ms，env 可覆盖，且不超过总时长（CC {@code Math.min}）。</li>
 * </ol>
 */
@DisplayName("AskUserAfkTimeout · 档位解析与 env 覆盖")
class AskUserAfkTimeoutTest {

    /** 可控环境（名 → 值）。 */
    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("未设置 / never / 脏值 ⇒ 不启用（即使设了 CLAUDE_AFK_TIMEOUT_MS）")
    void neverNeverEnablesEvenWithEnv() {
        Map<String, String> withEnv = env(AskUserAfkTimeout.ENV_TIMEOUT_MS, "5000");

        assertThat(AskUserAfkTimeout.resolveTimeoutMs(null, withEnv::get)).isNull();
        assertThat(AskUserAfkTimeout.resolveTimeoutMs("", withEnv::get)).isNull();
        assertThat(AskUserAfkTimeout.resolveTimeoutMs("never", withEnv::get)).isNull();
        assertThat(AskUserAfkTimeout.resolveTimeoutMs("1h", withEnv::get)).isNull();
    }

    @Test
    @DisplayName("显式档位 ⇒ 对应毫秒数（60s/5m/10m）")
    void explicitTiersMapToMillis() {
        assertThat(AskUserAfkTimeout.resolveTimeoutMs("60s", k -> null)).isEqualTo(60_000L);
        assertThat(AskUserAfkTimeout.resolveTimeoutMs("5m", k -> null)).isEqualTo(300_000L);
        assertThat(AskUserAfkTimeout.resolveTimeoutMs("10M", k -> null)).isEqualTo(600_000L);
    }

    @Test
    @DisplayName("CLAUDE_AFK_TIMEOUT_MS 覆盖设置时长；非法/非正 env 忽略")
    void envOverridesDurationOnly() {
        assertThat(AskUserAfkTimeout.resolveTimeoutMs("60s",
            env(AskUserAfkTimeout.ENV_TIMEOUT_MS, "5000")::get)).isEqualTo(5_000L);
        assertThat(AskUserAfkTimeout.resolveTimeoutMs("60s",
            env(AskUserAfkTimeout.ENV_TIMEOUT_MS, "abc")::get)).isEqualTo(60_000L);
        assertThat(AskUserAfkTimeout.resolveTimeoutMs("60s",
            env(AskUserAfkTimeout.ENV_TIMEOUT_MS, "0")::get)).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("倒计时阈值：默认 20000，env 覆盖，且不超过总时长")
    void countdownThresholdDefaultsAndClamps() {
        assertThat(AskUserAfkTimeout.resolveCountdownMs(60_000L, k -> null)).isEqualTo(20_000L);
        assertThat(AskUserAfkTimeout.resolveCountdownMs(60_000L,
            env(AskUserAfkTimeout.ENV_COUNTDOWN_MS, "5000")::get)).isEqualTo(5_000L);
        // 总时长比默认阈值还短 ⇒ 取总时长（CC Math.min(L, C)）
        assertThat(AskUserAfkTimeout.resolveCountdownMs(8_000L, k -> null)).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("parse 只认四值，大小写不敏感，返回规范字面值")
    void parseCanonicalizes() {
        assertThat(AskUserAfkTimeout.parse(" 5M ").wireValue()).isEqualTo("5m");
        assertThat(AskUserAfkTimeout.parse("NEVER").wireValue()).isEqualTo("never");
        assertThat(AskUserAfkTimeout.parse("bogus").wireValue()).isEqualTo("never");
    }
}
