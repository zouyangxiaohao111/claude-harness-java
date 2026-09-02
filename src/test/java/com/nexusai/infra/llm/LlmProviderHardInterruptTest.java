package com.nexusai.infra.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [对抗核验 H13-GAP-4 v3] LLM stream 硬中断 · 对齐 CC createCombinedAbortSignal(hookTimeoutMs)
 * (Open-ClaudeCode/src/utils/hooks/execAgentHook.ts:75-85) 硬性中断。
 *
 * <p>WHY (J.md H13-GAP-4 登记): CC 的 AbortSignal 透传 provider, abort 立即打断 LLM 请求。
 * Java 旧 {@code LlmProvider.stream} 无 abort 通道 → ExecAgentHook 超时是"软"的（loop 等 stream
 * 自然结束, 最长 300s）。本测试验证 15-arg stream 带 AbortController 时: abort 后 provider 以
 * {@link CancellationException} 终止（onError）, 不再走 onComplete —— 超时即硬打断 query。
 */
@DisplayName("[H13-GAP-4 v3] LlmProvider stream 硬中断")
class LlmProviderHardInterruptTest {

    private static ChatMessageDto userMsg(String text) {
        return new ChatMessageDto(null, null, Role.user, null, text,
            null, null, null, null, null, null, null, null, null,
            null, List.of(), List.of());
    }

    @Test
    @DisplayName("MockLlmProvider: abort 后 → onError(CancellationException), 无 onComplete（硬中断）")
    void mockStream_preAborted_terminatesWithCancellation() throws Exception {
        // WHY: ExecAgentHook 超时触发 hookAbort.abort("timeout") → provider stream 应硬中断,
        //       而不是等流自然结束（旧软超时最长 300s）。MockLlmProvider 15-arg 覆写验证 abort 语义。
        MockLlmProvider provider = new MockLlmProvider();
        AbortController abort = new AbortController();
        abort.abort("timeout");   // 模拟 ExecAgentHook 超时已触发

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        List<ChatMessageDto> history = List.of(userMsg("verify something"));

        provider.stream(null, "mock", (java.util.List<com.nexusai.application.agent.prompt.SystemPromptBlock>) null, history, null,
            null, null, null, null,   // maxOutputTokensOverride / taskBudget / effortValue / querySource
            chunk -> { /* 不应再收到 chunk */ },
            msg -> { /* 不应再收到 assistant message */ },
            null, null, null, abort,
            err -> {
                error.set(err);
                done.countDown();
            },
            () -> {
                completed.set(true);
                done.countDown();
            });

        assertThat(done.await(5, TimeUnit.SECONDS)).as("abort 后必须在 5s 内终止").isTrue();
        assertThat(completed.get()).as("abort 硬中断: 不触发 onComplete").isFalse();
        assertThat(error.get()).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(error.get()).hasMessageContaining("timeout");
    }

    @Test
    @DisplayName("MockLlmProvider: 无 abort → 正常完成 onComplete（非中断路径不回归）")
    void mockStream_noAbort_completesNormally() throws Exception {
        // WHY: 硬中断修复不能破坏正常 stream 完成路径（回归守卫）。
        MockLlmProvider provider = new MockLlmProvider();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<ChatMessageDto> history = List.of(userMsg("say hello"));

        provider.stream(null, "mock", (java.util.List<com.nexusai.application.agent.prompt.SystemPromptBlock>) null, history, null,
            null, null, null, null,   // maxOutputTokensOverride / taskBudget / effortValue / querySource
            chunk -> { },
            msg -> { },
            null, null, null, null,   // 无 abortController
            err -> {
                error.set(err);
                done.countDown();
            },
            () -> {
                completed.set(true);
                done.countDown();
            });

        assertThat(done.await(5, TimeUnit.SECONDS)).as("正常 stream 必须在 5s 内完成").isTrue();
        assertThat(completed.get()).isTrue();
        assertThat(error.get()).isNull();
    }

    @Test
    @DisplayName("MockLlmProvider: 流中途 abort → 后续 chunk 停止 + onError(CancellationException)")
    void mockStream_abortMidStream_stopsChunks() throws Exception {
        // WHY: 流式并行场景中 abort 到达时 provider 可能已发出部分 chunk; 之后必须停止消费,
        //       不能继续发 chunk 到 loop（否则 abort 后还有残留回调）。
        MockLlmProvider provider = new MockLlmProvider();
        AbortController abort = new AbortController();
        CountDownLatch firstChunk = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Boolean> completed = new AtomicReference<>(false);
        java.util.concurrent.atomic.AtomicInteger chunkCount = new java.util.concurrent.atomic.AtomicInteger(0);
        List<ChatMessageDto> history = List.of(userMsg(
            "a fairly long user message that will produce multiple stream chunks for the mock"));

        provider.stream(null, "mock", (java.util.List<com.nexusai.application.agent.prompt.SystemPromptBlock>) null, history, null,
            null, null, null, null,   // maxOutputTokensOverride / taskBudget / effortValue / querySource
            chunk -> {
                chunkCount.incrementAndGet();
                firstChunk.countDown();
                // 收到第一个 chunk 后立即 abort
                abort.abort("timeout");
            },
            msg -> { },
            null, null, null, abort,
            err -> {
                error.set(err);
                done.countDown();
            },
            () -> {
                completed.set(true);
                done.countDown();
            });

        assertThat(firstChunk.await(5, TimeUnit.SECONDS)).as("mock 必须在 5s 内发出首 chunk").isTrue();
        assertThat(done.await(5, TimeUnit.SECONDS)).as("abort 后必须在 5s 内终止").isTrue();
        assertThat(completed.get()).as("abort 硬中断: 不触发 onComplete").isFalse();
        assertThat(error.get()).isInstanceOf(java.util.concurrent.CancellationException.class);
        // abort 后 chunk 计数应远小于 mock 完整文本 chunk 数（证明中途停止）
        assertThat(chunkCount.get()).isLessThan(50);
    }
}
