package com.nexusai.application.agent.permission.classifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S10] YoloTokenEstimator 测试 · 对齐 CC roughTokenCountEstimation
 * （tokenEstimation.ts:203-208）+ detectPromptTooLong / parsePromptTooLongTokenCounts
 * （yoloClassifier.ts:1463-1471 / errors.ts:85-96）。
 *
 * <p>O55（弱删除候选）随本任务对齐转保留：估算 /4 + round（CC 语义），
 * transcriptTooLong 检测双载体（预检 + API 错误解析）。
 */
@DisplayName("[S10] YoloTokenEstimator（CC roughTokenCountEstimation / detectPromptTooLong）")
class YoloTokenEstimatorTest {

    private final YoloTokenEstimator estimator = new YoloTokenEstimator();

    // ─────────────────── estimateTokens（CC roughTokenCountEstimation /4） ───────────────────

    @Test
    @DisplayName("估算 = Math.round(len/4)（CC tokenEstimation.ts:203-208 bytesPerToken=4）")
    void estimateTokens_ccRatio() {
        assertThat(estimator.estimateTokens("abcd")).isEqualTo(1);
        assertThat(estimator.estimateTokens("abcdefgh")).isEqualTo(2);
        assertThat(estimator.estimateTokens("abcde")).isEqualTo(1);   // round(1.25)
        assertThat(estimator.estimateTokens("abcdef")).isEqualTo(2);  // round(1.5)
        assertThat(estimator.estimateTokens("a")).isEqualTo(0);       // round(0.25) — CC 短文本可为 0
        assertThat(estimator.estimateTokens("")).isZero();
        assertThat(estimator.estimateTokens(null)).isZero();
    }

    // ─────────────────── parsePromptTooLongTokenCounts（CC errors.ts:85-96） ───────────────────

    @Test
    @DisplayName("解析 'prompt is too long: N tokens > M maximum'（CC errors.ts:89-95 正则）")
    void parsePromptTooLong_standard() {
        YoloTokenEstimator.PromptTooLongTokenCounts counts =
            estimator.parsePromptTooLongTokenCounts(
                "prompt is too long: 137500 tokens > 135000 maximum");

        assertThat(counts.actualTokens()).isEqualTo(137500);
        assertThat(counts.limitTokens()).isEqualTo(135000);
    }

    @Test
    @DisplayName("大小写不敏感 / 单复数 / 前后缀包裹均解析（CC 宽松匹配注释）")
    void parsePromptTooLong_lenient() {
        // 大小写不敏感（Vertex）
        YoloTokenEstimator.PromptTooLongTokenCounts upper =
            estimator.parsePromptTooLongTokenCounts("Prompt is too long: 10 tokens > 5 maximum");
        assertThat(upper.actualTokens()).isEqualTo(10);
        assertThat(upper.limitTokens()).isEqualTo(5);

        // 单数 token
        YoloTokenEstimator.PromptTooLongTokenCounts singular =
            estimator.parsePromptTooLongTokenCounts("prompt is too long: 7 token > 6 maximum");
        assertThat(singular.actualTokens()).isEqualTo(7);
        assertThat(singular.limitTokens()).isEqualTo(6);

        // SDK 前缀/JSON 包裹
        YoloTokenEstimator.PromptTooLongTokenCounts wrapped =
            estimator.parsePromptTooLongTokenCounts(
                "{\"error\": \"prompt is too long: 999 tokens > 512 maximum\"}");
        assertThat(wrapped.actualTokens()).isEqualTo(999);
        assertThat(wrapped.limitTokens()).isEqualTo(512);
    }

    @Test
    @DisplayName("不可解析 → 两字段 null（CC errors.ts:91-95）")
    void parsePromptTooLong_unparseable() {
        YoloTokenEstimator.PromptTooLongTokenCounts none =
            estimator.parsePromptTooLongTokenCounts("rate limit exceeded");
        assertThat(none.actualTokens()).isNull();
        assertThat(none.limitTokens()).isNull();

        YoloTokenEstimator.PromptTooLongTokenCounts nil =
            estimator.parsePromptTooLongTokenCounts(null);
        assertThat(nil.actualTokens()).isNull();
        assertThat(nil.limitTokens()).isNull();
    }

    // ─────────────────── detectPromptTooLong（CC yoloClassifier.ts:1463-1471） ───────────────────

    @Test
    @DisplayName("消息含 'prompt is too long'（大小写不敏感）→ 解析结果（CC :1466-1470）")
    void detectPromptTooLong_hit() {
        YoloTokenEstimator.PromptTooLongTokenCounts hit =
            estimator.detectPromptTooLong(
                "prompt is too long: 100 tokens > 50 maximum");

        assertThat(hit).isNotNull();
        assertThat(hit.actualTokens()).isEqualTo(100);
        assertThat(hit.limitTokens()).isEqualTo(50);

        YoloTokenEstimator.PromptTooLongTokenCounts upper =
            estimator.detectPromptTooLong("Prompt Is Too Long: 2 tokens > 1 maximum");
        assertThat(upper).isNotNull();
    }

    @Test
    @DisplayName("其他错误 / null → null（CC :1466-1468 undefined）")
    void detectPromptTooLong_miss() {
        assertThat(estimator.detectPromptTooLong("connection reset")).isNull();
        assertThat(estimator.detectPromptTooLong("429 Too Many Requests")).isNull();
        assertThat(estimator.detectPromptTooLong(null)).isNull();
    }
    // [S06 OPD-WF6-02] wouldOverflow 90% 预检已删除（⊕-04）—— CC 无预检/无 128000 常量，
    //   超长检测能力统一到 detectPromptTooLong（API 'prompt is too long' 承载），避免两套并存。
}
