package com.nexusai.application.agent.recovery;

import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WithRetryEngine 静态工具方法测试 · 对齐 CC withRetry.ts:789-797。
 *
 * <p><b>WHY (意图验证, 规则九)</b>: withRetry 重试循环在 Java 侧由 LlmAgentLoop Path 3 内联实现
 * （承载 CC withRetry.ts:170-517 全量语义）。本类仅保留被 Path 3 调用的静态方法：
 * getMaxRetries 解析链（options ?? env ?? 10）、resolveMaxRetriesFromEnv（env 解析）。
 *
 * <p><b>V-WR-01 修复</b>：旧 executeRetry/RetryOperation 骨架仅被本测试调用，生产无消费方，
 * 且 catch(Throwable) 无 shouldRetry 分类（CC:377-382）、无 529 计数/fast-mode/max_tokens/
 * 持久重试/yield。已删骨架 + 接口，本测试改为验证 Path 3 依赖的静态方法 + 契约字段。
 *
 * <p><b>V-WR-03 修复</b>：CC parseInt('abc',10)=NaN -> for-loop 零次 -> 立即抛 CannotRetryError。
 * Java 无 NaN-int，以返回 0（零重试）等价对齐：maxRetries=0 时 Path 3 耗尽闸首错即抛。
 */
class WithRetryEngineTest {

    private static final ThinkingConfig TC = ThinkingConfig.disabled();

    // ════════════════════════════════════════════════════════════════════
    // RetryContext / RetryOptions 契约字段（CC withRetry.ts:120-142）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RetryContext 契约字段完整（maxTokensOverride/model/thinkingConfig/fastMode · CC:120-125）")
    void retryContextContractFields() {
        RetryContext ctx = new RetryContext(null, "claude-sonnet-4", TC, null);
        assertThat(ctx.maxTokensOverride()).isNull();
        assertThat(ctx.model()).isEqualTo("claude-sonnet-4");
        assertThat(ctx.thinkingConfig()).isSameAs(TC);
        assertThat(ctx.fastMode()).isNull();
    }

    @Test
    @DisplayName("RetryOptions 契约字段完整（含 initialConsecutive529Errors/fallbackModel/signal/querySource · CC:127-142）")
    void retryOptionsContractFields() {
        RetryOptions opts = new RetryOptions(
            3, "claude-sonnet-4", "claude-haiku", TC, false,
            () -> false, "repl_main_thread", 2);
        assertThat(opts.maxRetries()).isEqualTo(3);
        assertThat(opts.model()).isEqualTo("claude-sonnet-4");
        assertThat(opts.fallbackModel()).isEqualTo("claude-haiku");
        assertThat(opts.thinkingConfig()).isSameAs(TC);
        assertThat(opts.fastMode()).isFalse();
        assertThat(opts.aborted().get()).isFalse();
        assertThat(opts.querySource()).isEqualTo("repl_main_thread");
        assertThat(opts.initialConsecutive529Errors()).isEqualTo(2);
    }

    // ════════════════════════════════════════════════════════════════════
    // getMaxRetries（CC withRetry.ts:789-797 options.maxRetries ?? env ?? 10）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getMaxRetries：options.maxRetries 非 null 优先（CC:796）")
    void getMaxRetriesPrefersOptions() {
        RetryOptions opts = new RetryOptions(3, "claude-sonnet-4", null, TC, null,
            () -> false, null, null);
        assertThat(WithRetryEngine.getMaxRetries(opts)).isEqualTo(3);
    }

    @Test
    @DisplayName("getMaxRetries：options.maxRetries null -> env CLAUDE_CODE_MAX_RETRIES ?? 10（CC:789-797）")
    void getMaxRetriesFallsThroughToEnvThenDefault() {
        RetryOptions opts = new RetryOptions(null, "claude-sonnet-4", null, TC, null,
            () -> false, null, null);
        // 与 getDefaultMaxRetries() 自洽（env 或 10，具体值由 resolveMaxRetriesFromEnv 决定）
        assertThat(WithRetryEngine.getMaxRetries(opts))
            .isEqualTo(WithRetryEngine.getDefaultMaxRetries());
    }

    // ════════════════════════════════════════════════════════════════════
    // resolveMaxRetriesFromEnv（CC withRetry.ts:790-793 parseInt 语义对齐）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("env 解析：有效数字取之（CC:790-791 parseInt）")
    void envResolutionUsesParsedValue() {
        assertThat(WithRetryEngine.resolveMaxRetriesFromEnv("5")).isEqualTo(5);
    }

    @Test
    @DisplayName("env 解析：缺失/空白 -> 默认 10（CC:793 env falsy -> DEFAULT_MAX_RETRIES）")
    void envResolutionDefaultsOnMissing() {
        assertThat(WithRetryEngine.resolveMaxRetriesFromEnv(null)).isEqualTo(10);
        assertThat(WithRetryEngine.resolveMaxRetriesFromEnv("  ")).isEqualTo(10);
    }

    @Test
    @DisplayName("env 解析：非数字 -> 0 零重试（V-WR-03：CC parseInt NaN -> 循环零次 -> CannotRetryError）")
    void envResolutionZeroRetriesOnNonNumeric() {
        // CC: parseInt('abc',10) = NaN -> for(attempt=1; attempt<=NaN+1) 恒 false -> 零次循环
        // -> :516 throw CannotRetryError。Java 无 NaN-int，以 0 等价（maxRetries=0 首错即抛）。
        assertThat(WithRetryEngine.resolveMaxRetriesFromEnv("abc")).isEqualTo(0);
    }

    @Test
    @DisplayName("DEFAULT_MAX_RETRIES = 10（CC withRetry.ts:52）")
    void defaultMaxRetriesConstantMatchesCc() {
        // CC withRetry.ts:52 const DEFAULT_MAX_RETRIES = 10
        // 缺失 env 时 resolveMaxRetriesFromEnv 必须返回 10
        assertThat(WithRetryEngine.resolveMaxRetriesFromEnv(null)).isEqualTo(10);
    }
}
