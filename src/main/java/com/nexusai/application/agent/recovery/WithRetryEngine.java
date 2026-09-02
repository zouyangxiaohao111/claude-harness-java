package com.nexusai.application.agent.recovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * withRetry 核心引擎 · 对齐 CC withRetry.ts:170-517 async generator 重试循环。
 *
 * <p>CC 的 {@code AsyncGenerator<SystemAPIErrorMessage, T>}（yield 错误消息 + 最终返回 T）
 * 重试循环在 Java 侧由 {@code LlmAgentLoop} Path 3（s11）内联实现--该路径承载 CC withRetry 全量
 * 语义：attempt 计数（:189）、信号中止（:190-192/:491）、shouldRetry 分类闸（:377-382）、
 * 529 资格计数（:327-333）、fast-mode fallback/cooldown（:267-314）、max_tokens 溢出调整
 * （:388-427）、持久重试分片（:477-506）、耗尽抛 CannotRetryError（:370-371/:516）。
 *
 * <p>本类仅保留被 Path 3 调用的静态工具方法：
 * <ul>
 *   <li>{@link #getMaxRetries(RetryOptions)} - options.maxRetries ?? env ?? 10 解析链（CC:795-797）</li>
 *   <li>{@link #getDefaultMaxRetries()} - env CLAUDE_CODE_MAX_RETRIES 解析（CC:789-794）</li>
 *   <li>{@link #resolveMaxRetriesFromEnv(String)} - env 值解析（包内可见供测试）</li>
 * </ul>
 *
 * <p><b>V-WR-01 修复</b>：旧 {@code executeRetry} / {@code RetryOperation} 骨架仅被
 * {@code WithRetryEngineTest} 调用，生产无消费方，且 catch(Throwable) 无 shouldRetry 分类
 * （CC:377-382 对不可重试直接抛）、无 529 计数/fast-mode/max_tokens 溢出/持久重试/yield。
 * 测试固化骨架错误行为。已删除骨架 + 接口，测试改为验证 Path 3 真实重试行为所依赖的静态方法。
 */
public final class WithRetryEngine {

    private static final Logger log = LoggerFactory.getLogger(WithRetryEngine.class);

    /** CC original: DEFAULT_MAX_RETRIES = 10 (withRetry.ts:52) */
    static final int DEFAULT_MAX_RETRIES = 10;

    private WithRetryEngine() {
        // 工具类不可实例化
    }

    /**
     * CC original: getDefaultMaxRetries (withRetry.ts:789-794)。
     *
     * <p>env {@code CLAUDE_CODE_MAX_RETRIES} 存在则 parseInt，否则 {@link #DEFAULT_MAX_RETRIES}。
     */
    public static int getDefaultMaxRetries() {
        return resolveMaxRetriesFromEnv(System.getenv("CLAUDE_CODE_MAX_RETRIES"));
    }

    /**
     * env 值解析（包内可见供测试）· CC withRetry.ts:790-793。
     *
     * <p>CC {@code parseInt(env, 10)} 对非数字返回 {@code NaN}，导致 for-loop 条件
     * {@code attempt <= NaN + 1} 恒 false -> 循环零次 -> 立即抛 {@code CannotRetryError}
     * （withRetry.ts:516）。Java 无 NaN-int 语义，以返回 {@code 0}（零重试）等价对齐：
     * {@code maxRetries=0} 时 Path 3 耗尽闸 {@code attempt > 0} 首错即抛 CannotRetryException。
     *
     * <p>缺失/空白 -> 默认 10（CC env truthy 判定：空串 falsy -> 走 DEFAULT_MAX_RETRIES）。
     * 非数字 -> 0（零重试，对齐 CC NaN 零循环语义）。
     *
     * @param envValue CLAUDE_CODE_MAX_RETRIES 环境变量值（可能为 null）
     * @return 解析后的 maxRetries
     */
    static int resolveMaxRetriesFromEnv(String envValue) {
        if (envValue != null && !envValue.isBlank()) {
            try {
                return Integer.parseInt(envValue.trim());
            } catch (NumberFormatException nfe) {
                log.warn("CLAUDE_CODE_MAX_RETRIES 非数字 '{}'，零重试（对齐 CC parseInt NaN -> 循环零次）· CC withRetry.ts:790-793",
                    envValue);
                return 0;
            }
        }
        return DEFAULT_MAX_RETRIES;
    }

    /**
     * CC original: getMaxRetries(options) (withRetry.ts:795-797)。
     *
     * @param options 重试参数（maxRetries 可为 null）
     * @return {@code options.maxRetries ?? getDefaultMaxRetries()}
     */
    public static int getMaxRetries(RetryOptions options) {
        return options.maxRetries() != null ? options.maxRetries() : getDefaultMaxRetries();
    }
}
