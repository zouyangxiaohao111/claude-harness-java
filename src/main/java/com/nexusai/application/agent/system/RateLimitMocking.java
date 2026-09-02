package com.nexusai.application.agent.system;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rate limit mocking facade · 对齐 CC services/rateLimitMocking.ts.
 *
 * <p>L1 语义: 隔离 mock 逻辑与生产代码 (CC ant 用户 /mock-limits 命令).
 *            - processRateLimitHeaders: mock 模式 → applyMockHeaders(headers).
 *            - shouldProcessRateLimits: subscriber 或 mock 模式.
 *            - checkMockRateLimitError: 构建 429 APIError (含 rate limit headers).
 *            - isMockRateLimitError: 标记 mock 错误 (status 429 + mock 模式).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: 4 functions;MockApiError (status 429 + body + headers);
 *       2 阶段 (headerless message + headers);Opus 限制只在用 Opus model 时 throw.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — checkMockRateLimitError → shouldProcessMockLimits=true →
 *       headerless/headers → throw 429;Opus limit + 非 Opus model → skip;
 *       status=rejected + overage 非 rejected → throw.</li>
 *   <li><b>A3</b>: 状态: MOCK_INACTIVE → MOCK_ACTIVE;Opus/non-Opus 检测.</li>
 *   <li><b>A4</b>: !shouldProcessMockLimits → null;
 *       status='rejected' + overage='allowed' → throw 429;
 *       isOpus + !isUsingOpus → null (skip);
 *       fast mode scenario → throw 429 with fast mode headers.</li>
 *   <li><b>A5</b>: 真实场景 — ant user + mock 模式 + Opus limit + 用 Sonnet model →
 *       返回 null (Opus limit 不触发 Sonnet);weekly limit + claude-3-5 → throw 429.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `APIError` → Java MockApiError record;
 *                    TS `globalThis.Headers` → Java Map;
 *                    TS `shouldProcessMockLimits` → 注入式 BooleanSupplier;
 *                    TS `getMockHeaders` → 注入式 MockHeadersSupplier;
 *                    TS `getMockHeaderless429Message` → 注入式 Supplier;
 *                    TS `isMockFastModeRateLimitScenario` → 注入式 BooleanSupplier;
 *                    TS `checkMockFastModeRateLimit` → 注入式 FastModeSupplier.
 */
public final class RateLimitMocking {

    private static final Logger log = LoggerFactory.getLogger(RateLimitMocking.class);

    public static final int STATUS_429 = 429;
    public static final String HEADER_STATUS = "anthropic-ratelimit-unified-status";
    public static final String HEADER_OVERAGE_STATUS = "anthropic-ratelimit-unified-overage-status";
    public static final String HEADER_RATE_LIMIT_TYPE = "anthropic-ratelimit-unified-representative-claim";

    private final BooleanSupplier shouldProcessMockLimits;
    private final Supplier<String> mockHeaderlessMessageSupplier;
    private final MockHeadersSupplier mockHeadersSupplier;
    private final BooleanSupplier isFastModeRateLimitScenario;
    private final FastModeSupplier fastModeRateLimitChecker;

    public RateLimitMocking(BooleanSupplier shouldProcessMockLimits,
                              Supplier<String> mockHeaderlessMessageSupplier,
                              MockHeadersSupplier mockHeadersSupplier,
                              BooleanSupplier isFastModeRateLimitScenario,
                              FastModeSupplier fastModeRateLimitChecker) {
        this.shouldProcessMockLimits = Objects.requireNonNull(shouldProcessMockLimits);
        this.mockHeaderlessMessageSupplier = Objects.requireNonNull(mockHeaderlessMessageSupplier);
        this.mockHeadersSupplier = Objects.requireNonNull(mockHeadersSupplier);
        this.isFastModeRateLimitScenario = Objects.requireNonNull(isFastModeRateLimitScenario);
        this.fastModeRateLimitChecker = Objects.requireNonNull(fastModeRateLimitChecker);
    }

    /** Mock API error (CC APIError 最小子集). */
    public record MockApiError(int status, String body, Map<String, String> headers) {
        public boolean isRateLimit() { return status == STATUS_429; }
    }

    /** Mock headers supplier (注入). */
    @FunctionalInterface
    public interface MockHeadersSupplier {
        Map<String, String> get();
    }

    /** Fast mode supplier (注入). */
    @FunctionalInterface
    public interface FastModeSupplier {
        /** Returns null if fast mode not applicable; else headers to add. */
        Map<String, String> check(boolean isFastModeActive);
    }

    @FunctionalInterface
    public interface BooleanSupplier { boolean getAsBoolean(); }

    /** CC shouldProcessRateLimits. */
    public boolean shouldProcessRateLimits(boolean isSubscriber) {
        return isSubscriber || shouldProcessMockLimits.getAsBoolean();
    }

    /** CC isMockRateLimitError. */
    public boolean isMockRateLimitError(MockApiError error) {
        return shouldProcessMockLimits.getAsBoolean() && error.isRateLimit();
    }

    /** CC checkMockRateLimitError — 主链. */
    public MockApiError checkMockRateLimitError(String currentModel, boolean isFastModeActive) {
        if (!shouldProcessMockLimits.getAsBoolean()) {
            return null;
        }

        // 1. Headerless message override
        String headerlessMessage = mockHeaderlessMessageSupplier.get();
        if (headerlessMessage != null && !headerlessMessage.isEmpty()) {
            return new MockApiError(STATUS_429, headerlessMessage, new LinkedHashMap<>());
        }

        // 2. Use mock headers
        Map<String, String> mockHeaders = mockHeadersSupplier.get();
        if (mockHeaders == null) {
            return null;
        }

        String status = mockHeaders.get(HEADER_STATUS);
        String overageStatus = mockHeaders.get(HEADER_OVERAGE_STATUS);
        String rateLimitType = mockHeaders.get(HEADER_RATE_LIMIT_TYPE);

        boolean isOpusLimit = "seven_day_opus".equals(rateLimitType);
        boolean isUsingOpus = currentModel != null && currentModel.contains("opus");

        // Opus 限制 + 用 Sonnet → 不 throw (Sonnet 没限制)
        if (isOpusLimit && !isUsingOpus) {
            return null;
        }

        // Fast mode scenario
        if (isFastModeRateLimitScenario.getAsBoolean()) {
            Map<String, String> fastModeHeaders = fastModeRateLimitChecker.check(isFastModeActive);
            if (fastModeHeaders == null) {
                return null;
            }
            return new MockApiError(STATUS_429, "Rate limit exceeded", fastModeHeaders);
        }

        // 标准 429 (status=rejected + overage 非 allowed)
        boolean shouldThrow429 = "rejected".equals(status)
            && (overageStatus == null || "rejected".equals(overageStatus));
        if (shouldThrow429) {
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : mockHeaders.entrySet()) {
                if (e.getValue() != null) headers.put(e.getKey(), e.getValue());
            }
            return new MockApiError(STATUS_429, "Rate limit exceeded", headers);
        }
        return null;
    }
}
