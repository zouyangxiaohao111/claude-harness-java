package com.nexusai.infra.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Wn · 非流式回退超时重试] 判据纯函数单测（对齐 CC exe 223,010,874 · 规格 §2.4）。
 *
 * <p><b>WHY（规则九）</b>：这套门控的默认态是"<b>不介入</b>"（两 env 均未设 ⇒ Wn 无值 → 走通用
 * 重试）——若默认算反，等于给所有非流式回退超时套了一个 0 次重试的枷锁（回归）；若门控算错，
 * 用户开了 gate 却拿不到 2 次预算（静默失效）。三个纯函数把"预算解析 / 超时类判据 / 回退超时值"
 * 钉死；循环内"封顶"动作（超限 return false / 否则计数后照常重试）由
 * {@code AnthropicSdkProviderStreamingNonStreamingFallbackTest} 与代码审查覆盖。
 */
@DisplayName("[Wn] 非流式回退超时重试预算门控（默认不介入 · env > gate+outlasted）")
class AnthropicSdkProviderNonStreamingTimeoutRetryTest {

    @Test
    @DisplayName("预算解析矩阵：env 优先（含显式 0）；否则 gate && outlasted → 2；否则不介入（-1）")
    void budgetResolutionMatrix() {
        assertThat(AnthropicSdkProvider.resolveNonStreamingTimeoutRetryBudget(null, false, true))
            .as("默认（无 env、gate 关）→ 不介入（CC 原样：Wn=undefined）").isEqualTo(-1);
        assertThat(AnthropicSdkProvider.resolveNonStreamingTimeoutRetryBudget(null, false, false))
            .isEqualTo(-1);
        assertThat(AnthropicSdkProvider.resolveNonStreamingTimeoutRetryBudget(null, true, false))
            .as("gate 开但流未 outlasted → 不介入").isEqualTo(-1);
        assertThat(AnthropicSdkProvider.resolveNonStreamingTimeoutRetryBudget(null, true, true))
            .as("gate 开 + outlasted → 2（CC zgr=2）").isEqualTo(2);
        assertThat(AnthropicSdkProvider.resolveNonStreamingTimeoutRetryBudget("3", false, false))
            .as("env 覆盖（无门控也生效）").isEqualTo(3);
        assertThat(AnthropicSdkProvider.resolveNonStreamingTimeoutRetryBudget("0", true, true))
            .as("env 显式 0 → 立即中止（首超时即 throw）").isZero();
        assertThat(AnthropicSdkProvider.resolveNonStreamingTimeoutRetryBudget("abc", true, true))
            .as("非法值按未设处理 → 回落门控链").isEqualTo(2);
        assertThat(AnthropicSdkProvider.resolveNonStreamingTimeoutRetryBudget("-1", true, true))
            .as("负值按未设处理 → 回落门控链").isEqualTo(2);
    }

    @Test
    @DisplayName("超时类判据：cause 链 ≤6 层 SocketTimeoutException / InterruptedIOException:timeout")
    void timeoutChainDetection() {
        assertThat(AnthropicSdkProvider.isTimeoutInCauseChain(new SocketTimeoutException("timeout")))
            .as("直接 SocketTimeoutException").isTrue();
        assertThat(AnthropicSdkProvider.isTimeoutInCauseChain(
                new RuntimeException("wrap", new IOException("wrap2", new SocketTimeoutException("timeout")))))
            .as("两层包装仍命中（实测 okhttp 形态：InterruptedIOException→SocketException 系）").isTrue();
        assertThat(AnthropicSdkProvider.isTimeoutInCauseChain(new InterruptedIOException("timeout")))
            .as("InterruptedIOException:timeout（okhttp RealCall.timeoutExit 形态）").isTrue();
        assertThat(AnthropicSdkProvider.isTimeoutInCauseChain(new InterruptedIOException("boom")))
            .as("InterruptedIOException 但非 timeout 文案 → 否").isFalse();
        assertThat(AnthropicSdkProvider.isTimeoutInCauseChain(new IOException("conn reset")))
            .as("普通 IO 错误 → 否").isFalse();
        assertThat(AnthropicSdkProvider.isTimeoutInCauseChain(null)).isFalse();
    }

    @Test
    @DisplayName("回退请求自身超时值 = CC rln()：API_TIMEOUT_MS 优先，否则 300s（本进程未设 env）")
    void rlnDefault() {
        assertThat(AnthropicSdkProvider.nonStreamingTimeoutRlnMs())
            .as("API_TIMEOUT_MS 未设 → 300000（CC rln 本地分支）").isEqualTo(300_000L);
    }
}
