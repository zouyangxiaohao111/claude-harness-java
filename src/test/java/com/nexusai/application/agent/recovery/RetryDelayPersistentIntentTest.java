package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久退避延迟公式意图测试 · 对齐 CC withRetry.ts:433-463 持久分支。
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>: 持久重试的延迟计算与普通重试不同——
 * 429 带 reset header 时等 reset 时间戳（窗口型限额场景，如 5hr Max/Pro），非 429 或无
 * reset header 时走指数退避但有 5min cap + 6h reset cap 双保险。任一偏差导致：
 * <ol>
 *   <li><b>窗口限额空轮询</b>：有 reset header 不走 reset 路径 → 每 5min 空轮询至窗口
 *       结束（CC withRetry.ts:433-447 设计初衷：精确等到 reset 时刻）</li>
 *   <li><b>病态 header 无限等待</b>：无 6h cap → reset header 含未来 1 年 → sleep 1 年
 *       （CC PERSISTENT_RESET_CAP_MS=6h 兜底）</li>
 *   <li><b>非 429 走 reset 路径</b>：503 带 reset header → 误读 reset（CC 仅 429 读 reset）</li>
 * </ol>
 *
 * <p>RED teeth：改 429 判定条件（如放宽到所有 5xx 读 reset）→ test_429OnlyReadsReset 失败；
 * 删 6h cap → test_persistentDelayCappedAtSixHours 失败；改 5min cap → test_non429CappedAtFiveMinutes 失败。
 */
class RetryDelayPersistentIntentTest {

    // ════════════════════════════════════════════════════════════════════
    // 429 + reset header → 直接返回 reset delay（CC withRetry.ts:433-447）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("429 + reset header → 返回 reset delay（CC:433-447 窗口限额精确等 reset）")
    void persistent429WithResetHeader_returnsResetDelay() {
        long futureSec = (System.currentTimeMillis() / 1000) + 120; // 2 分钟后 reset
        LlmApiException ex = new LlmApiException(429,
            Map.of("anthropic-ratelimit-unified-reset", List.of(String.valueOf(futureSec))),
            "rate");
        long delay = RetryDelayCalculator.calculatePersistentDelay(1, null, ex);
        // reset delay ≈ 120s（±2s 容差）
        assertThat(delay)
            .as("429 + reset header 必须走 reset 路径（CC:433-447），等 reset 时间戳")
            .isBetween(118_000L, 122_000L);
    }

    @Test
    @DisplayName("429 无 reset header → 走指数退避路径，cap 5min（CC:448-460）")
    void persistent429WithoutResetHeader_fallsToExponential() {
        LlmApiException ex = new LlmApiException(429, Map.of(), "rate");
        long delay = RetryDelayCalculator.calculatePersistentDelay(1, null, ex);
        // attempt=1 → base=min(500*2^0=500, PERSISTENT_MAX_BACKOFF_MS=300000)=500
        // delay ∈ [500, 624]（含 jitter）
        assertThat(delay)
            .as("429 无 reset header 走指数退避，cap 5min")
            .isBetween(500L, 624L);
    }

    @Test
    @DisplayName("非 429（503）+ reset header → 不读 reset，走指数退避（CC 仅 429 读 reset）")
    void non429IgnoresResetHeader() {
        long futureSec = (System.currentTimeMillis() / 1000) + 3600; // 1h 后 reset
        LlmApiException ex = new LlmApiException(503,
            Map.of("anthropic-ratelimit-unified-reset", List.of(String.valueOf(futureSec))),
            "unavailable");
        long delay = RetryDelayCalculator.calculatePersistentDelay(1, null, ex);
        // 503 不读 reset header → 走指数退避；attempt=1 → base=500
        assertThat(delay)
            .as("非 429 即使带 reset header 也不读（CC:433 仅 429），走指数退避")
            .isBetween(500L, 624L);
    }

    // ════════════════════════════════════════════════════════════════════
    // 双 cap 保护（CC PERSISTENT_MAX_BACKOFF_MS=5min / PERSISTENT_RESET_CAP_MS=6h）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("持久延迟 reset 超 6h → cap 6h（CC PERSISTENT_RESET_CAP_MS=6h 兜底病态 header）")
    void persistentDelayCappedAtSixHours() {
        long farFutureSec = (System.currentTimeMillis() / 1000) + 20 * 3600; // 20h 后 reset
        LlmApiException ex = new LlmApiException(429,
            Map.of("anthropic-ratelimit-unified-reset", List.of(String.valueOf(farFutureSec))),
            "rate");
        long delay = RetryDelayCalculator.calculatePersistentDelay(1, null, ex);
        assertThat(delay)
            .as("reset delay 超 6h 必须 cap（CC:814-822 getRateLimitResetDelayMs 内部 6h cap）")
            .isEqualTo(ApiErrors.PERSISTENT_RESET_CAP_MS);
    }

    @Test
    @DisplayName("非 429 指数退避有界：base cap 5min + jitter 25% → delay ≤ 6.25min，再 cap 6h（CC:448-460 min(...)）")
    void non429BoundedWithCap() {
        // attempt=20 → 500*2^19=262144000ms >> 5min=300000ms → base 被 cap 到 300000
        // jitter ∈ [0, 300000*0.25=75000) → delay ∈ [300000, 375000)
        // 外层 min(delay, 6h=21600000) = delay（因为 375000 < 21600000）
        LlmApiException ex = new LlmApiException(503, Map.of(), "unavailable");
        long delay = RetryDelayCalculator.calculatePersistentDelay(20, null, ex);
        // delay ≤ base + maxJitter = 300000 + 75000 = 375000（6.25min）
        assertThat(delay)
            .as("非 429 指数退避有界：base cap 5min + jitter ≤ 25%% → delay ≤ 6.25min")
            .isLessThanOrEqualTo(375_000L);
    }

    // ════════════════════════════════════════════════════════════════════
    // retry-after 在持久路径同样优先（CC withRetry.ts:448-460）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("持久路径 retry-after 优先：有 header → 用 header 值（CC:448-460 Retry-After 绕过 maxDelayMs 内部封顶）")
    void persistentRetryAfterPriority() {
        LlmApiException ex = new LlmApiException(429, Map.of(), "rate");
        long delay = RetryDelayCalculator.calculatePersistentDelay(10, 30L, ex);
        // retry-after=30s → calculate(10, 30, 300000) = 30000ms（retry-after 优先）
        assertThat(delay)
            .as("持久路径 retry-after=30s 必须优先于指数公式（CC:448-460）")
            .isEqualTo(30_000L);
    }
}
