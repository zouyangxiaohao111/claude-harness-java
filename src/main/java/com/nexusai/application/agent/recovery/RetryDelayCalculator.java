package com.nexusai.application.agent.recovery;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.infra.llm.LlmApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 指数退避延迟计算器 · 对齐 CC withRetry.ts:530-548 getRetryDelay / :814-822 getRateLimitResetDelayMs。
 *
 * <h2>CC 精确公式（withRetry.ts:542-547）</h2>
 * <pre>
 *   if (retryAfterHeader) {                 // :535 有 header（含 "0"）→ 秒数优先
 *     const seconds = parseInt(retryAfterHeader, 10)
 *     if (!isNaN(seconds)) return seconds * 1000   // retry-after=0 → 0ms，不拒 0
 *   }
 *   base = min(BASE_DELAY_MS * 2^(attempt-1), maxDelayMs)   // :543-544，attempt=0 → 2^-1=0.5 → 250
 *   jitter = Math.random() * 0.25 * base                    // :545
 *   return base + jitter                                    // :547
 * </pre>
 *
 * <p>关键对齐点（对应 ER-IMP-05）：
 * <ul>
 *   <li><b>maxDelayMs 参数化</b>：CC :533 默认 32000，Java 侧由调用方显式传
 *       {@link ApiErrors#MAX_DELAY_MS}（无默认参数，全调用点显式传值，无双轨）。</li>
 *   <li><b>retry-after=0 → 0ms</b>：判定从「非 null 且 &gt;0」改「非 null」（DC-06 相关，
 *       0 也返回 0ms，不回退指数退避）。</li>
 *   <li><b>2^(attempt-1) 用 Math.pow</b>：旧 {@code 1L << (attempt-1)} 在 attempt=0 时得
 *       Long.MIN_VALUE → jitterBound 为负 → nextLong 抛异常；guard 删除后必现。CC 允许 attempt=0。</li>
 *   <li><b>getRateLimitResetDelayMs</b>：读 {@code anthropic-ratelimit-unified-reset} header，
 *       严格 Number 解析（非 parseInt 前导数字），≤0 → null，cap 6h。</li>
 * </ul>
 *
 * <p>纯函数，无副作用（getRateLimitResetDelayMs 依赖当前时钟，属只读）。
 * P-30（2026-08-15）：命中路径日志由 INFO 降 DEBUG（{@code if(log.isDebugEnabled())} 包裹，文案
 * 保留），对齐 CC 纯函数无 INFO 日志（withRetry.ts:530-548 / :814-822）。
 */
public final class RetryDelayCalculator {

    private static final Logger log = LoggerFactory.getLogger(RetryDelayCalculator.class);

    private RetryDelayCalculator() {
        // 工具类不可实例化
    }

    /**
     * 计算重试延迟毫秒数 · 对齐 CC withRetry.ts:530-548 getRetryDelay。
     *
     * @param attempt            当前重试次数（从 1 开始；CC 允许 0 → base=250ms，不抛异常）
     * @param retryAfterSeconds  HTTP Retry-After header 秒数（null 表示不存在；0 表示立即重试 → 0ms）
     * @param maxDelayMs         指数公式封顶毫秒数 · CC original: maxDelayMs (Open-ClaudeCode/src/services/api/withRetry.ts:533)
     * @return 延迟毫秒数
     */
    public static long calculate(int attempt, Long retryAfterSeconds, long maxDelayMs) {
        // retryAfter 优先 · CC withRetry.ts:535-540（"0" 也命中 → 0ms，不拒 0）
        if (retryAfterSeconds != null) {
            long delayMs = retryAfterSeconds * 1000L;
            // P-30：CC getRetryDelay 纯函数无日志（withRetry.ts:530-548），INFO 降 DEBUG 保留可观测性
            if (log.isDebugEnabled()) {
                log.debug("RetryDelay: retry-after={}s → {}ms · CC withRetry.ts:535-540",
                    retryAfterSeconds, delayMs);
            }
            return delayMs;
        }

        // 指数退避公式 · CC withRetry.ts:542-547
        // base = min(500 * 2^(attempt-1), maxDelayMs)；attempt=0 → 500*0.5=250（CC Math.pow，不拒 0）
        long base = (long) Math.min(
            ApiErrors.BASE_DELAY_MS * Math.pow(2, attempt - 1),
            (double) maxDelayMs
        );

        // jitter = random(0, base * 0.25) · CC withRetry.ts:545 Math.random()*0.25*base（上限 exclusive）
        long jitterBound = (long) (base * 0.25);
        long jitter = jitterBound > 0
            ? ThreadLocalRandom.current().nextLong(jitterBound)
            : 0;

        long delay = base + jitter;

        if (log.isDebugEnabled()) {
            log.debug("RetryDelay: attempt={} base={}ms jitter={}ms → {}ms (maxDelayMs={}, CC 2^({}-1))",
                attempt, base, jitter, delay, maxDelayMs, attempt);
        }

        return delay;
    }

    /**
     * 计算 rate-limit reset 等待延迟 · 对齐 CC withRetry.ts:814-822 getRateLimitResetDelayMs。
     *
     * <p>读 {@code anthropic-ratelimit-unified-reset} header（CC original:
     * anthropic-ratelimit-unified-reset, withRetry.ts:815），值为 Unix 秒时间戳，换算到当前时刻的
     * 剩余毫秒；≤0 → null（已过 reset 时刻）；返回 min(delayMs, PERSISTENT_RESET_CAP_MS)（6h cap）。
     *
     * <p><b>与 retry-after 不同</b>：CC 用 {@code Number(resetHeader)}（严格全串数字，withRetry.ts:817），
     * 非 {@code parseInt} 前导数字。Java 等价为 {@link Double#parseDouble}，非数字/非有限 → null。
     *
     * <p>本 session 仅实现 + 单测；持久重试接线属 ER-IMP-06。
     *
     * @param ex LLM API 异常（含 HTTP headers）
     * @return 需等待毫秒数（有界 ≤ 6h），或 null（无 header / 非数字 / 已过期）
     */
    public static Long getRateLimitResetDelayMs(LlmApiException ex) {
        if (ex == null) {
            return null;
        }
        String resetHeader = ex.getHeader("anthropic-ratelimit-unified-reset");
        if (resetHeader == null || resetHeader.isEmpty()) {
            return null;
        }
        double resetUnixSec;
        try {
            resetUnixSec = Double.parseDouble(resetHeader.trim());
        } catch (NumberFormatException ignored) {
            if (log.isDebugEnabled()) {
                log.debug("RetryDelay: anthropic-ratelimit-unified-reset 非数字: {}", resetHeader);
            }
            return null;
        }
        if (!Double.isFinite(resetUnixSec)) {
            // CC :818 Number.isFinite(resetUnixSec) 为 false → null（覆盖 parseDouble("Infinity") 场景）
            return null;
        }
        long delayMs = (long) (resetUnixSec * 1000.0) - System.currentTimeMillis();
        if (delayMs <= 0) {
            // CC :820 delayMs <= 0 → null（reset 时刻已过，无需等待）
            return null;
        }
        long capped = Math.min(delayMs, ApiErrors.PERSISTENT_RESET_CAP_MS);
        // P-30：CC getRateLimitResetDelayMs 纯函数无日志（withRetry.ts:814-822），INFO 降 DEBUG 保留可观测性
        if (log.isDebugEnabled()) {
            log.debug("RetryDelay: anthropic-ratelimit-unified-reset={}s → 等待 {}ms (cap 6h) · CC withRetry.ts:814-822",
                resetUnixSec, capped);
        }
        return capped;
    }

    /**
     * 计算持久重试延迟 · 对齐 CC withRetry.ts:433-463 持久分支。
     *
     * <p>CC 双分支：
     * <ul>
     *   <li><b>429 且含 reset header</b>（:433-447）— {@code getRateLimitResetDelayMs ??
     *       min(getRetryDelay(persistentAttempt, retryAfter, PERSISTENT_MAX_BACKOFF_MS),
     *       PERSISTENT_RESET_CAP_MS)}。窗口型限额（如 5hr Max/Pro）带 reset 时间戳，等 reset 而
     *       非每 5min 空轮询。</li>
     *   <li><b>其余持久路径</b>（:448-460）— {@code min(getRetryDelay(persistentAttempt, retryAfter,
     *       PERSISTENT_MAX_BACKOFF_MS), PERSISTENT_RESET_CAP_MS)}。Retry-After 是服务端指令，绕过
     *       maxDelayMs 内部封顶（getRetryDelay 的 maxDelayMs 只封指数公式），在 6h reset-cap 兜底
     *       病态 header。</li>
     * </ul>
     *
     * <p>{@code getRateLimitResetDelayMs} 内部已自带 6h cap（CC :814-822），非 429 持久分支用
     * {@code min(..., PERSISTENT_RESET_CAP_MS)} 显式封顶。
     *
     * <p><b>ER-IMP-06 接线</b>：TransientErrorHandler 持久分支（state.setLastBackoffMs）与
     * LlmAgentLoop Path 3 实际 sleep 均经本方法取延迟，单一公式来源（无双轨）。
     *
     * @param persistentAttempt 持久重试计数（CC persistentAttempt，:188 独立于 attempt，持续增长至 5min cap）
     * @param retryAfterSeconds HTTP Retry-After header 秒数（null 表示不存在）
     * @param ex                LLM API 异常（持久 429 时读 anthropic-ratelimit-unified-reset header）
     * @return 持久重试等待毫秒数（有界 ≤ 6h）
     */
    public static long calculatePersistentDelay(int persistentAttempt, Long retryAfterSeconds, LlmApiException ex) {
        if (ex != null && ex.status() == 429) {
            Long resetDelay = getRateLimitResetDelayMs(ex);
            if (resetDelay != null) {
                return resetDelay;
            }
        }
        long base = Math.min(
            calculate(persistentAttempt, retryAfterSeconds, ApiErrors.PERSISTENT_MAX_BACKOFF_MS),
            ApiErrors.PERSISTENT_RESET_CAP_MS);
        if (log.isDebugEnabled()) {
            log.debug("RetryDelay: 持久退避 persistentAttempt={} retryAfter={}s → {}ms (cap 5min/6h) · CC withRetry.ts:433-463",
                persistentAttempt, retryAfterSeconds, base);
        }
        return base;
    }

    /**
     * 模拟 JS {@code parseInt(str, 10)} 的前导数字解析 · CC withRetry.ts:536。
     *
     * <p>JS parseInt 容忍「数字 + 尾随非数字」与「小数」：{@code parseInt("120.5",10)=120}、
     * {@code parseInt("120abc",10)=120}；Java {@link Long#parseLong} 严格语义会抛
     * NumberFormatException（旧实现 → null → 回退指数退避，产生 CC 不会产生的等待）。此 helper
     * 对齐 JS 语义：跳前导空白 → 可选正负号 → 连续数字，无数字 → null（对应 NaN）。
     *
     * <p>包内可见（package-private）：ErrorClassifier.extractRetryAfterSeconds 解析 retry-after
     * header 时调用（同包）。getRateLimitResetDelayMs 用严格 Number 解析，不调用此 helper。
     *
     * @param s header 原始字符串
     * @return 前导数字解析的整数值，或 null（空串/纯符号/无前导数字）
     */
    static Long jsParseInt(String s) {
        if (s == null) {
            return null;
        }
        int i = 0;
        int len = s.length();
        while (i < len && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= len) {
            return null;
        }
        boolean negative = false;
        char first = s.charAt(i);
        if (first == '+' || first == '-') {
            negative = first == '-';
            i++;
        }
        if (i >= len) {
            return null; // 只有符号 → NaN
        }
        long value = 0;
        boolean anyDigit = false;
        while (i < len) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            value = value * 10 + (c - '0');
            anyDigit = true;
            i++;
        }
        if (!anyDigit) {
            return null; // 无前导数字 → NaN
        }
        return negative ? -value : value;
    }
}
