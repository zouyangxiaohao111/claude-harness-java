package com.nexusai.application.agent.recovery;

import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CannotRetryException 契约测试 · 对齐 CC withRetry.ts:144-158 class CannotRetryError。
 *
 * <p>CC 真源（grep 自验 withRetry.ts）：
 * <ul>
 *   <li>:149 {@code const message = errorMessage(originalError)} — message 取原错误消息</li>
 *   <li>:151 {@code this.name = 'RetryError'} — name 显式 RetryError（与类名 CannotRetryError 不同）</li>
 *   <li>:153-156 {@code if (originalError instanceof Error && originalError.stack) this.stack = originalError.stack}
 *       — 保留原始 stack（用 original 的 stack 整体替换自身）</li>
 *   <li>:146-147 持有 {@code public readonly originalError} + {@code public readonly retryContext}</li>
 * </ul>
 *
 * <p><b>WHY (意图验证)</b>: 上层（query.ts:996 catch withRetry throw）必须能按
 * CannotRetryError 类型区分「重试彻底失败」与「普通流错误」，并拿到 originalError +
 * retryContext 做降级；name='RetryError' 是 CC 的显式契约（不是类名推断）。
 * 一旦实现改用类名当 name、或丢失 originalError / 混淆 stack，以下断言必须变红。
 */
class CannotRetryExceptionTest {

    private static final RetryContext CTX = new RetryContext(
        null, "claude-sonnet-4", ThinkingConfig.disabled(), null);

    @Test
    @DisplayName("name='RetryError'（CC withRetry.ts:151 显式 name，非类名推断）")
    void nameIsRetryError() {
        RuntimeException original = new RuntimeException("boom");
        CannotRetryException exc = new CannotRetryException(original, CTX);

        assertThat(exc.retryErrorName()).isEqualTo("RetryError");
        assertThat(CannotRetryException.RETRY_ERROR_NAME).isEqualTo("RetryError");
        // CC 类是 Error 子类；Java 用 RuntimeException 表达，可被普通异常捕获
        assertThat(exc).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("携带 originalError + retryContext（CC withRetry.ts:146-147）")
    void carriesOriginalErrorAndRetryContext() {
        RuntimeException original = new RuntimeException("rate limit");
        CannotRetryException exc = new CannotRetryException(original, CTX);

        assertThat(exc.getOriginalError()).isSameAs(original);
        assertThat(exc.getRetryContext()).isSameAs(CTX);
        assertThat(exc.getRetryContext().model()).isEqualTo("claude-sonnet-4");
    }

    @Test
    @DisplayName("message 取 originalError（CC withRetry.ts:149 errorMessage(originalError)）")
    void messageComesFromOriginalError() {
        RuntimeException original = new RuntimeException("529 Overloaded");
        CannotRetryException exc = new CannotRetryException(original, CTX);

        assertThat(exc.getMessage()).isEqualTo("529 Overloaded");
    }

    @Test
    @DisplayName("保留原始 stack（CC withRetry.ts:153-156 this.stack = originalError.stack）")
    void preservesOriginalStackTrace() {
        RuntimeException original = new RuntimeException("boom");
        // 在可识别的调用帧处创建 original，确保 stack 含测试方法帧、不含 CannotRetryException 构造帧
        CannotRetryException exc = new CannotRetryException(original, CTX);

        // 与 original 的 stack 完全一致（CC 整体替换）
        assertThat(exc.getStackTrace()).isEqualTo(original.getStackTrace());
        // CC 用 original 的 stack 替换自身 → 不得含 CannotRetryException 构造帧
        assertThat(exc.getStackTrace())
            .extracting(StackTraceElement::getClassName)
            .doesNotContain(CannotRetryException.class.getName());
    }

    @Test
    @DisplayName("originalError 非 Error 时（null）不 NPE，message 走兜底（CC errorMessage 兜底）")
    void nullOriginalErrorIsHandled() {
        CannotRetryException exc = new CannotRetryException(null, CTX);
        assertThat(exc.getOriginalError()).isNull();
        assertThat(exc.getMessage()).isNotNull();
        assertThat(exc.getRetryContext()).isSameAs(CTX);
    }
}
