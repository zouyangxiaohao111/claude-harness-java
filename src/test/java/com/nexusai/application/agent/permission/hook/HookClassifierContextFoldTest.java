package com.nexusai.application.agent.permission.hook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Wave2-E] {@code PostToolUse.classifierContext} 多 hook 折叠 · 对齐 claude.exe v2.1.325
 * 原文："Capped at 2000 UTF-16 code units, a budget shared across all hooks that contribute
 * to one call (surrogate-pair-safe...). Honored on synchronous hook responses only."
 *
 * <p><b>WHY</b>: 该字段会进入 auto 模式权限分类器的判断依据。若不截断，超长断言会挤爆
 * 分类器输入；若在代理对中间截断，会产生非法 UTF-16 字符串。
 */
@DisplayName("[Wave2-E] classifierContext 累加 / 2000 UTF-16 预算 / 代理对安全")
class HookClassifierContextFoldTest {

    @Test
    @DisplayName("多段断言按顺序以换行拼接")
    void appendsWithNewline() {
        StringBuilder buf = new StringBuilder();
        HookRegistry.appendClassifierContext(buf, "first");
        HookRegistry.appendClassifierContext(buf, "second");

        assertThat(buf.toString()).isEqualTo("first\nsecond");
    }

    @Test
    @DisplayName("null / 空白断言整条跳过")
    void skipsBlank() {
        StringBuilder buf = new StringBuilder();
        HookRegistry.appendClassifierContext(buf, null);
        HookRegistry.appendClassifierContext(buf, "");
        HookRegistry.appendClassifierContext(buf, "  ");
        HookRegistry.appendClassifierContext(buf, "x");

        assertThat(buf.toString()).isEqualTo("x");
    }

    @Test
    @DisplayName("单条超 2000 → 截断到 2000")
    void truncatesAtBudget() {
        StringBuilder buf = new StringBuilder();
        HookRegistry.appendClassifierContext(buf, "a".repeat(2500));

        assertThat(buf.length()).isEqualTo(HookRegistry.CLASSIFIER_CONTEXT_MAX_UTF16);
    }

    @Test
    @DisplayName("预算耗尽后，后续 hook 的断言整条丢弃")
    void laterAssertionsDroppedWhenBudgetExhausted() {
        StringBuilder buf = new StringBuilder();
        HookRegistry.appendClassifierContext(buf, "a".repeat(HookRegistry.CLASSIFIER_CONTEXT_MAX_UTF16));
        HookRegistry.appendClassifierContext(buf, "tail");

        assertThat(buf.length()).isEqualTo(HookRegistry.CLASSIFIER_CONTEXT_MAX_UTF16);
        assertThat(buf.toString()).doesNotContain("tail");
    }

    @Test
    @DisplayName("截断点落在代理对中间时回退，不产生孤立 high surrogate")
    void surrogatePairNotSplit() {
        StringBuilder buf = new StringBuilder();
        // "😀" = 2 个 UTF-16 code unit（high + low）。先填到上限前 1 格
        HookRegistry.appendClassifierContext(buf,
            "a".repeat(HookRegistry.CLASSIFIER_CONTEXT_MAX_UTF16 - 1));
        HookRegistry.appendClassifierContext(buf, "\uD83D\uDE00");

        assertThat(buf.length()).isLessThanOrEqualTo(HookRegistry.CLASSIFIER_CONTEXT_MAX_UTF16);
        char last = buf.charAt(buf.length() - 1);
        assertThat(Character.isHighSurrogate(last))
            .as("末尾不得是孤立 high surrogate")
            .isFalse();
    }

    @Test
    @DisplayName("恰好放得下的代理对完整保留（high+low 两格都在）")
    void surrogatePairFullyKept() {
        StringBuilder buf = new StringBuilder();
        HookRegistry.appendClassifierContext(buf,
            "a".repeat(HookRegistry.CLASSIFIER_CONTEXT_MAX_UTF16 - 3));
        HookRegistry.appendClassifierContext(buf, "\uD83D\uDE00");

        // 拼接前先插入 1 个换行分隔符，故留 3 格才能完整容纳 2 格代理对
        assertThat(buf.length()).isEqualTo(HookRegistry.CLASSIFIER_CONTEXT_MAX_UTF16);
        assertThat(Character.isHighSurrogate(buf.charAt(buf.length() - 2))).isTrue();
        assertThat(Character.isLowSurrogate(buf.charAt(buf.length() - 1))).isTrue();
    }

    @Test
    @DisplayName("空 buffer + null 入参 → 保持空（防御性）")
    void nullBufferSafe() {
        HookRegistry.appendClassifierContext(null, "x");
        StringBuilder buf = new StringBuilder();
        HookRegistry.appendClassifierContext(buf, null);
        assertThat(buf.toString()).isEmpty();
    }
}
