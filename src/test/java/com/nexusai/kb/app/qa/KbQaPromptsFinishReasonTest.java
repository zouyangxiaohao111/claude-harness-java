package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 11 补 · {@link KbQaPrompts#isLengthFinishReason} 与两种空回退文案。
 *
 * <p>守三件事：<b>各家 provider 的截断取值都要认</b>、<b>空截断与空回答是两套文案</b>、
 * <b>不把正常 stop 误判成截断</b>。
 */
class KbQaPromptsFinishReasonTest {

    @Test
    @DisplayName("⭐ OpenAI / DeepSeek 的 length")
    void openAiLength() {
        assertThat(KbQaPrompts.isLengthFinishReason("length")).isTrue();
        assertThat(KbQaPrompts.isLengthFinishReason("LENGTH")).isTrue();
    }

    @Test
    @DisplayName("⭐ Anthropic 的 max_tokens")
    void anthropicMaxTokens() {
        assertThat(KbQaPrompts.isLengthFinishReason("max_tokens")).isTrue();
        assertThat(KbQaPrompts.isLengthFinishReason("MAX_TOKENS")).isTrue();
    }

    @Test
    @DisplayName("其他截断取值：max_output_tokens / incomplete")
    void otherTruncationValues() {
        assertThat(KbQaPrompts.isLengthFinishReason("max_output_tokens")).isTrue();
        assertThat(KbQaPrompts.isLengthFinishReason("incomplete")).isTrue();
    }

    @Test
    @DisplayName("⛔ 正常结束不能被误判成截断")
    void normalFinishesAreNotTruncation() {
        assertThat(KbQaPrompts.isLengthFinishReason("stop")).isFalse();
        assertThat(KbQaPrompts.isLengthFinishReason("end_turn")).isFalse();
        assertThat(KbQaPrompts.isLengthFinishReason("tool_calls")).isFalse();
        assertThat(KbQaPrompts.isLengthFinishReason("stop_sequence")).isFalse();
    }

    @Test
    @DisplayName("null / 空 / 空白 ⇒ false（不抛）")
    void nullSafe() {
        assertThat(KbQaPrompts.isLengthFinishReason(null)).isFalse();
        assertThat(KbQaPrompts.isLengthFinishReason("")).isFalse();
        assertThat(KbQaPrompts.isLengthFinishReason("   ")).isFalse();
    }

    @Test
    @DisplayName("⭐ 两种空回退文案必须不同（一个换问法，一个要求更短）")
    void twoFallbacksDiffer() {
        assertThat(KbQaPrompts.EMPTY_ANSWER_FALLBACK)
            .isNotEqualTo(KbQaPrompts.EMPTY_TRUNCATED_FALLBACK);
        assertThat(KbQaPrompts.EMPTY_TRUNCATED_FALLBACK).contains("长度限制");
        assertThat(KbQaPrompts.EMPTY_ANSWER_FALLBACK).doesNotContain("长度限制");
    }

    @Test
    @DisplayName("截断提示是追加在后（不改写正文）")
    void truncatedSuffixIsAppendable() {
        String original = "这是已经推给用户的正文";
        assertThat(original + KbQaPrompts.TRUNCATED_SUFFIX).startsWith(original);
        assertThat(KbQaPrompts.TRUNCATED_SUFFIX).contains("截断");
    }
}
