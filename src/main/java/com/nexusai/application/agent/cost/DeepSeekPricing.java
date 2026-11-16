package com.nexusai.application.agent.cost;

/**
 * DeepSeek 定价表 · 供 hook {@code estimated_cache_write_usd} / 成本估算使用。
 *
 * <p><b>默认值</b>：DeepSeek V3.2 公开列表价（USD / 1M tokens）——
 * cache miss input 0.28、cache hit input 0.028、output 0.42。
 * 价格会变，三个值均可用系统属性覆盖：
 * {@code nexusai.pricing.deepseek.cacheMissUsdPerM} /
 * {@code nexusai.pricing.deepseek.cacheHitUsdPerM} /
 * {@code nexusai.pricing.deepseek.outputUsdPerM}。
 *
 * <p><b>cache write 口径</b>：把上下文重新写入 prompt cache 时，按 cache-miss input 单价计费
 * （DeepSeek 对未命中缓存的输入 token 收费），即 {@code tokens / 1e6 * cacheMiss}。
 */
public final class DeepSeekPricing {

    public static final double DEFAULT_CACHE_MISS_USD_PER_M = 0.28d;
    public static final double DEFAULT_CACHE_HIT_USD_PER_M = 0.028d;
    public static final double DEFAULT_OUTPUT_USD_PER_M = 0.42d;

    private DeepSeekPricing() {}

    /** 重新缓存 {@code contextTokens} 的估算成本（USD）。 */
    public static double estimateCacheWriteUsd(long contextTokens) {
        if (contextTokens <= 0) {
            return 0.0d;
        }
        return contextTokens / 1_000_000.0d * cacheMissUsdPerM();
    }

    public static double cacheMissUsdPerM() {
        return readDouble("nexusai.pricing.deepseek.cacheMissUsdPerM",
            DEFAULT_CACHE_MISS_USD_PER_M);
    }

    public static double cacheHitUsdPerM() {
        return readDouble("nexusai.pricing.deepseek.cacheHitUsdPerM",
            DEFAULT_CACHE_HIT_USD_PER_M);
    }

    public static double outputUsdPerM() {
        return readDouble("nexusai.pricing.deepseek.outputUsdPerM",
            DEFAULT_OUTPUT_USD_PER_M);
    }

    private static double readDouble(String key, double fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(key.replace('.', '_').toUpperCase(java.util.Locale.ROOT));
        }
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            return v >= 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
