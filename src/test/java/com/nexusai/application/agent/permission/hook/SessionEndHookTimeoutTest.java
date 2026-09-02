package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S5 D-01] SessionEnd hook 超时预算解析聚焦测试 · 对齐 CC hooks.ts:176-182
 * {@code getSessionEndHookTimeoutMs()}。
 *
 * <p>值域：env CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS 缺失/非法/≤0 → 1500；
 * 合法正整数 → 原值。测试直接注入原始 env 字符串（{@link HookRegistry#parseSessionEndHookTimeoutMs}，
 * 纯函数，JDK 25 模块封装下无需反射改 env）。
 */
@DisplayName("[IMP-HOOKS-S5 D-01] getSessionEndHookTimeoutMs 值域")
class SessionEndHookTimeoutTest {

    @Test
    @DisplayName("env 缺失（null）→ 1500（CC 缺省 SESSION_END_HOOK_TIMEOUT_MS_DEFAULT）")
    void missingEnv_defaultsTo1500() {
        assertThat(HookRegistry.parseSessionEndHookTimeoutMs(null)).isEqualTo(1500L);
    }

    @Test
    @DisplayName("env 空串 → 1500")
    void blankEnv_defaultsTo1500() {
        assertThat(HookRegistry.parseSessionEndHookTimeoutMs("  ")).isEqualTo(1500L);
    }

    @Test
    @DisplayName("env 非法（非数字）→ 1500")
    void invalidEnv_defaultsTo1500() {
        assertThat(HookRegistry.parseSessionEndHookTimeoutMs("abc")).isEqualTo(1500L);
        assertThat(HookRegistry.parseSessionEndHookTimeoutMs("1.5s")).isEqualTo(1500L);
    }

    @Test
    @DisplayName("env 0 / 负数 → 1500（CC parsed > 0 判定）")
    void nonPositiveEnv_defaultsTo1500() {
        assertThat(HookRegistry.parseSessionEndHookTimeoutMs("0")).isEqualTo(1500L);
        assertThat(HookRegistry.parseSessionEndHookTimeoutMs("-5")).isEqualTo(1500L);
    }

    @Test
    @DisplayName("env 合法正整数 → 原值（用户放宽 teardown 预算）")
    void validEnv_returnsValue() {
        assertThat(HookRegistry.parseSessionEndHookTimeoutMs("10000")).isEqualTo(10000L);
        assertThat(HookRegistry.parseSessionEndHookTimeoutMs("1500")).isEqualTo(1500L);
    }

    @Test
    @DisplayName("env 首尾空白容忍（parseInt 语义）")
    void validEnv_withWhitespace_returnsValue() {
        assertThat(HookRegistry.parseSessionEndHookTimeoutMs(" 3000 ")).isEqualTo(3000L);
    }
}
