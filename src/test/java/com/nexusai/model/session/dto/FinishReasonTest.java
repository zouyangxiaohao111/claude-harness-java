package com.nexusai.model.session.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [SM/trace] FinishReason 容错解析 · WHY：DB finish_reason 列 = 枚举名 + Anthropic 原始
 * stop_reason('max_tokens' 等) 混存，旧 toDto valueOf 遇 'max_tokens' 抛 IllegalArgumentException
 * → 整条消息读取失败（trace/分页 500，「当前无轨迹」）。parse 未知 → null 不抛。
 */
class FinishReasonTest {

    @Test
    @DisplayName("已知枚举名原样解析；Anthropic 原始 'max_tokens' 可解析为常量")
    void parse_knownAndMaxTokens() {
        assertThat(FinishReason.parse("stop")).isEqualTo(FinishReason.stop);
        assertThat(FinishReason.parse("tool_calls")).isEqualTo(FinishReason.tool_calls);
        assertThat(FinishReason.parse("max_tokens")).isEqualTo(FinishReason.max_tokens);
    }

    @Test
    @DisplayName("未知/空存储值 → null（不再抛 IllegalArgumentException）")
    void parse_unknownOrNull_returnsNull() {
        assertThat(FinishReason.parse("end_turn")).as("Anthropic 其它原始值应容错为 null 而非抛异常").isNull();
        assertThat(FinishReason.parse("bogus")).isNull();
        assertThat(FinishReason.parse(null)).isNull();
    }
}
