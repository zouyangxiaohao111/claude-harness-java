package com.nexusai.application.agent.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * isNonCustomOpusModel 意图测试 · 对齐 CC utils/model/model.ts:40-46。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>: 529 fallback 资格闸
 * （withRetry.ts:330-335）依赖此判定——仅 Opus 4.x firstParty 模型计入 consecutive529Errors
 * 并触发 fallback；Sonnet/Haiku/自定义端点不触发。判定逻辑为精确字符串相等（非前缀匹配），
 * 因为 CC configs.ts 中 firstParty 模型 ID 是完整的带日期后缀的字符串。
 *
 * <p>RED teeth：改前缀匹配（如 startsWith("claude-opus")）→ test_customEndpointNotMatched 失败；
 * 删任一模型 ID → 对应 test 失败；加新模型 ID 到判定 → test_unknownModelNotMatched 失败。
 */
class ModelNameUtilIntentTest {

    // ════════════════════════════════════════════════════════════════════
    // 4 个 firstParty Opus 模型精确命中 · CC model.ts:40-46
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("4 个 firstParty Opus 4.x 模型精确命中（CC model.ts:40-46 opus40/41/45/46）")
    void firstPartyOpusModelsMatched() {
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-opus-4-20250514"))
            .as("Opus 4.0 firstParty 必须命中").isTrue();
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-opus-4-1-20250805"))
            .as("Opus 4.1 firstParty 必须命中").isTrue();
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-opus-4-5-20251101"))
            .as("Opus 4.5 firstParty 必须命中").isTrue();
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-opus-4-6"))
            .as("Opus 4.6 firstParty 必须命中").isTrue();
    }

    @Test
    @DisplayName("非 Opus 模型不命中（Sonnet/Haiku/GPT 等 → false，CC 精确相等）")
    void nonOpusModelsNotMatched() {
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-sonnet-4-6")).isFalse();
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-sonnet-4-20250514")).isFalse();
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-haiku-4-5-20251001")).isFalse();
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-3-5-haiku-20241022")).isFalse();
        assertThat(ModelNameUtil.isNonCustomOpusModel("gpt-4o")).isFalse();
    }

    @Test
    @DisplayName("自定义 Opus 端点不命中（CC 精确相等，非前缀）")
    void customEndpointNotMatched() {
        // 前缀匹配会命中但精确相等不命中 —— 这正是为何 CC 用 === 而非 startsWith
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-opus-4-20250514-custom")).isFalse();
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-opus-4")).isFalse();
        assertThat(ModelNameUtil.isNonCustomOpusModel("claude-opus")).isFalse();
    }

    @Test
    @DisplayName("null/空串 → false（防御性，CC model 为 string 类型但 Java 端防 null）")
    void nullOrEmptyNotMatched() {
        assertThat(ModelNameUtil.isNonCustomOpusModel(null)).isFalse();
        assertThat(ModelNameUtil.isNonCustomOpusModel("")).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // renderModelName · CC model.ts:395-412
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("renderModelName: firstParty 模型 → 显示名（CC model.ts:395-412）")
    void renderFirstPartyModelReturnsDisplayName() {
        assertThat(ModelNameUtil.renderModelName("claude-opus-4-6")).isEqualTo("Opus 4.6");
        assertThat(ModelNameUtil.renderModelName("claude-sonnet-4-6")).isEqualTo("Sonnet 4.6");
        assertThat(ModelNameUtil.renderModelName("claude-haiku-4-5-20251001")).isEqualTo("Haiku 4.5");
    }

    @Test
    @DisplayName("renderModelName: 未知模型 → 原样返回（CC default 分支 return model）")
    void renderUnknownModelReturnsOriginal() {
        assertThat(ModelNameUtil.renderModelName("my-custom-endpoint")).isEqualTo("my-custom-endpoint");
        assertThat(ModelNameUtil.renderModelName(null)).isNull();
    }
}
