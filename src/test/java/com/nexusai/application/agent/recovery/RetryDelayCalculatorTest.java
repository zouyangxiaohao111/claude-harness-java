package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退避延迟计算器测试 · 对齐 CC withRetry.ts:530-548 getRetryDelay / :803-812 getRetryAfterMs /
 * :814-822 getRateLimitResetDelayMs。
 *
 * <p><b>WHY (意图验证)</b>: 退避延迟是 withRetry 循环的"等待多久"裁决点。CC 语义三处必须锁定：
 * (1) retry-after header 秒数优先且 {@code parseInt("0")=0 非 NaN → 0ms}（不拒 0 走指数退避，
 * 否则服务器明确要求"立即重试"时被 Java 扭曲成指数等待，放大网关压力）；
 * (2) 指数公式 base=min(500×2^(attempt-1), maxDelayMs) 且 maxDelayMs 可参数化（CC :533 默认 32000，
 * attempt=0 合法 → 250ms，Java 旧 guard 抛异常属 DC-06 偏差）；
 * (3) anthropic-ratelimit-unified-reset 用严格 Number 解析（非 parseInt 前导数字），≤0 或超 6h cap 有界。
 * 任一条偏差都会导致「重试节奏与 CC 不一致」。
 */
class RetryDelayCalculatorTest {

    // ════════════════════════════════════════════════════════════════════
    // retry-after header 优先 · CC withRetry.ts:535-540
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("retry-after=0 → 0ms（CC parseInt(\"0\")=0 非 NaN → seconds*1000=0，不拒 0）")
    void retryAfterZeroReturnsZeroMs() {
        assertThat(RetryDelayCalculator.calculate(1, 0L, ApiErrors.MAX_DELAY_MS))
            .as("retry-after=0 必须返回 0ms，不得回退指数退避").isZero();
    }

    @Test
    @DisplayName("retry-after 正数优先于指数公式（即使 attempt 已到公式封顶区间）")
    void retryAfterHeaderPriorityOverFormula() {
        assertThat(RetryDelayCalculator.calculate(1, 2L, ApiErrors.MAX_DELAY_MS)).isEqualTo(2000L);
        assertThat(RetryDelayCalculator.calculate(10, 2L, ApiErrors.MAX_DELAY_MS)).isEqualTo(2000L);
    }

    @Test
    @DisplayName("extractRetryAfterSeconds: header '0' → 0L（下游 calculate 得 0ms）")
    void extractRetryAfterZeroHeader() {
        LlmApiException ex = new LlmApiException(429, Map.of("retry-after", List.of("0")), "rate");
        assertThat(ErrorClassifier.extractRetryAfterSeconds(ex)).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 指数退避公式 · CC withRetry.ts:542-547
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("无 header：attempt=1 → base=min(500×2^0, 32000)=500，delay ∈ [500, 624]")
    void exponentialBaseOnFirstAttempt() {
        for (int i = 0; i < 200; i++) {
            long delay = RetryDelayCalculator.calculate(1, null, ApiErrors.MAX_DELAY_MS);
            assertThat(delay).isBetween(500L, 624L);
        }
    }

    @Test
    @DisplayName("attempt=0 合法（DC-06 guard 已删）：base=min(500×2^-1, 32000)=250，delay ∈ [250, 311]")
    void attemptZeroAllowedNoGuard() {
        for (int i = 0; i < 200; i++) {
            long delay = RetryDelayCalculator.calculate(0, null, ApiErrors.MAX_DELAY_MS);
            assertThat(delay).isBetween(250L, 311L);
        }
    }

    @Test
    @DisplayName("maxDelayMs 参数化：base 被 maxDelayMs 封顶（CC :533 默认 32000，调用方可覆盖）")
    void maxDelayMsCapsBase() {
        // attempt=1 → min(500,100)=100；attempt=2 → min(1000,100)=100
        for (int i = 0; i < 200; i++) {
            assertThat(RetryDelayCalculator.calculate(1, null, 100L)).isBetween(100L, 124L);
            assertThat(RetryDelayCalculator.calculate(2, null, 100L)).isBetween(100L, 124L);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // getRateLimitResetDelayMs · CC withRetry.ts:814-822（严格 Number 解析，非 parseInt）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("unified-reset 未来 60s → delay ≈ 60s（有界正数）")
    void rateLimitResetValidHeader() {
        long futureSec = (System.currentTimeMillis() / 1000) + 60;
        LlmApiException ex = new LlmApiException(429,
            Map.of("anthropic-ratelimit-unified-reset", List.of(String.valueOf(futureSec))), "rate");
        assertThat(RetryDelayCalculator.getRateLimitResetDelayMs(ex)).isBetween(58_000L, 62_000L);
    }

    @Test
    @DisplayName("unified-reset 非数字/缺失/异常 → null（CC Number() 非 finite → null）")
    void rateLimitResetNonNumericOrMissingNull() {
        LlmApiException nonNumeric = new LlmApiException(429,
            Map.of("anthropic-ratelimit-unified-reset", List.of("abc")), "rate");
        assertThat(RetryDelayCalculator.getRateLimitResetDelayMs(nonNumeric)).isNull();
        // 前导数字但尾部有字母：JS Number(\"120abc\")=NaN → null（与 parseInt 不同，须严格）
        LlmApiException leadingDigit = new LlmApiException(429,
            Map.of("anthropic-ratelimit-unified-reset", List.of("120abc")), "rate");
        assertThat(RetryDelayCalculator.getRateLimitResetDelayMs(leadingDigit)).isNull();
        assertThat(RetryDelayCalculator.getRateLimitResetDelayMs(new LlmApiException(429, Map.of(), "rate"))).isNull();
        assertThat(RetryDelayCalculator.getRateLimitResetDelayMs(null)).isNull();
    }

    @Test
    @DisplayName("unified-reset 已过 reset 时刻 → null（CC delayMs<=0 → null）")
    void rateLimitResetExpiredNull() {
        long pastSec = (System.currentTimeMillis() / 1000) - 60;
        LlmApiException ex = new LlmApiException(429,
            Map.of("anthropic-ratelimit-unified-reset", List.of(String.valueOf(pastSec))), "rate");
        assertThat(RetryDelayCalculator.getRateLimitResetDelayMs(ex)).isNull();
    }

    @Test
    @DisplayName("unified-reset 20h 未来 → cap 6h（CC min(delayMs, PERSISTENT_RESET_CAP_MS)）")
    void rateLimitResetCappedAtSixHours() {
        long farSec = (System.currentTimeMillis() / 1000) + 20 * 3600;
        LlmApiException ex = new LlmApiException(429,
            Map.of("anthropic-ratelimit-unified-reset", List.of(String.valueOf(farSec))), "rate");
        assertThat(RetryDelayCalculator.getRateLimitResetDelayMs(ex))
            .isEqualTo(ApiErrors.PERSISTENT_RESET_CAP_MS);
    }

    // ════════════════════════════════════════════════════════════════════
    // extractRetryAfterSeconds parseInt 容忍 · CC withRetry.ts:536
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extractRetryAfterSeconds: '120.5' → 120（JS parseInt 前导数字容忍，Long.parseLong 会拒）")
    void extractRetryAfterLeadingDigitTolerance() {
        LlmApiException ex = new LlmApiException(429, Map.of("retry-after", List.of("120.5")), "rate");
        assertThat(ErrorClassifier.extractRetryAfterSeconds(ex)).isEqualTo(120L);
    }

    @Test
    @DisplayName("extractRetryAfterSeconds: '120abc' → 120（JS parseInt 截断容忍）")
    void extractRetryAfterDigitsThenText() {
        LlmApiException ex = new LlmApiException(429, Map.of("retry-after", List.of("120abc")), "rate");
        assertThat(ErrorClassifier.extractRetryAfterSeconds(ex)).isEqualTo(120L);
    }

    @Test
    @DisplayName("extractRetryAfterSeconds: 纯字母/空 → null（parseInt NaN → null）")
    void extractRetryAfterNonNumericNull() {
        LlmApiException alpha = new LlmApiException(429, Map.of("retry-after", List.of("abc")), "rate");
        assertThat(ErrorClassifier.extractRetryAfterSeconds(alpha)).isNull();
        assertThat(ErrorClassifier.extractRetryAfterSeconds(new LlmApiException(429, Map.of(), "rate"))).isNull();
        assertThat(ErrorClassifier.extractRetryAfterSeconds(null)).isNull();
    }
}
