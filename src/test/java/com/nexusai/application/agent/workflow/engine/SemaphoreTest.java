package com.nexusai.application.agent.workflow.engine;

import com.nexusai.application.agent.tool.AbortController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 并发信号量语义测试 · 对齐 CC {@code engine/concurrency.ts:10-56} + P0-core-doc §8.2-B。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：
 * <ol>
 *   <li><b>permit 守恒</b> — CC release 把 permit <b>直接转移</b>给下一 waiter（available 不变），
 *       无 waiter 才 available+1（concurrency.ts:48-55）。若 Java 实现误用「先 available+1 再唤醒」，
 *       在排队场景下会凭空多出许可 → 并发槽超过 maxConcurrency，导致 agent 扇出失控。
 *       测试断言：N 个并发 acquire/release 后，再 acquire 仍能拿到满额（permit 没丢也没多）。</li>
 *   <li><b>abort 不占槽</b> — 等待中 abort → 立即失败 + 出队 + <b>不消耗 permit</b>
 *       （concurrency.ts:30-34）。若实现误把等待者也算占槽，取消的 agent 会永久占用并发槽，
 *       后续 agent 全部排队饿死。测试断言：唯一 permit 被持有时，第二个 acquire 等待中 abort，
 *       释放首个 permit 后第三个 acquire 立即成功（被取消的 waiter 未占槽）。</li>
 *   <li><b>clampMaxConcurrency</b> — 用户输入归一化（concurrency.ts:70-73）：
 *       null→3、&lt;1→1、&gt;CAP(16)→16、否则 trunc。超限值会扇出过量 agent 或死锁。</li>
 * </ol>
 */
class SemaphoreTest {

    /** B.1 permit 守恒：N 个并发 acquire/release 后许可数守恒，再 acquire 仍拿满额。 */
    @Test
    @DisplayName("B.1 permit 守恒：并发 acquire/release 后许可不丢失（concurrency.ts:48-55）")
    void permitConservedAfterConcurrentAcquireRelease() throws Exception {
        Semaphore sem = new Semaphore(3);

        // 首轮 3 个 acquire 立即拿满额
        List<Semaphore.Permit> first = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            first.add(sem.acquire(null).get(2, TimeUnit.SECONDS));
        }
        // 第 4 个 acquire 入队等待
        CompletableFuture<Semaphore.Permit> queued = sem.acquire(null);
        assertThat(queued.isDone()).isFalse();

        // 释放全部 3 个 permit：直传队列 waiter → 第 4 个 acquire 被唤醒拿到 permit
        first.forEach(p -> p.release().run());
        Semaphore.Permit fourth = queued.get(2, TimeUnit.SECONDS);
        assertThat(fourth).isNotNull();

        // 释放第 4 个 permit（此时无 waiter → 归还 available）
        fourth.release().run();
        // 再 acquire 必须立即成功（permit 守恒：既没丢也没多）
        Semaphore.Permit again = sem.acquire(null).get(2, TimeUnit.SECONDS);
        assertThat(again).isNotNull();
        again.release().run();
    }

    /** B.2 abort 不占槽：唯一 permit 被持有，等待者 abort 后不消耗 permit，释放后下个 acquire 立即成功。 */
    @Test
    @DisplayName("B.2 abort 不占槽：等待中 abort 不消耗 permit（concurrency.ts:21-45）")
    void abortedWaiterDoesNotConsumePermit() throws Exception {
        Semaphore sem = new Semaphore(1);
        AbortController signal = new AbortController();

        // 唯一 permit 被持有
        Semaphore.Permit held = sem.acquire(null).get(2, TimeUnit.SECONDS);

        // 第二个 acquire 带 signal 入队等待
        CompletableFuture<Semaphore.Permit> waiting = sem.acquire(signal);
        assertThat(waiting.isDone()).isFalse();

        // 等待中 abort → acquire 立即失败
        signal.abort("test-abort");
        assertThatThrownBy(waiting::get)
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("Semaphore.acquire aborted");

        // 释放唯一 permit；随后第三个 acquire（无 signal）应<b>立即</b>成功 ——
        // 证明被取消的 waiter 未消耗 permit（否则此处需等第二个释放）。
        held.release().run();
        Semaphore.Permit third = sem.acquire(null).get(2, TimeUnit.SECONDS);
        assertThat(third).isNotNull();
        third.release().run();
    }

    /** 信号量已 abort 时 acquire 立即失败（concurrency.ts:22-24）。 */
    @Test
    @DisplayName("acquire 传入已 abort 的 signal → 立即失败不占槽")
    void acquireWithAlreadyAbortedSignalFailsImmediately() {
        Semaphore sem = new Semaphore(1);
        AbortController aborted = new AbortController();
        aborted.abort("pre-aborted");

        assertThatThrownBy(() -> sem.acquire(aborted).get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("Semaphore.acquire aborted");

        // 未消耗 permit：无 signal 的 acquire 仍立即成功
        Semaphore.Permit p = sem.acquire(null).join();
        assertThat(p).isNotNull();
        p.release().run();
    }

    /** clampMaxConcurrency 归一化：null→3、0→1、1→1、5→5、100→16（concurrency.ts:70-73）。 */
    @Test
    @DisplayName("clampMaxConcurrency：null→3、<1→1、>CAP→16、否则 trunc")
    void clampMaxConcurrencyNormalizesUserInput() {
        assertThat(Semaphore.clampMaxConcurrency(null)).isEqualTo(3);
        assertThat(Semaphore.clampMaxConcurrency(0)).isEqualTo(1);
        assertThat(Semaphore.clampMaxConcurrency(-5)).isEqualTo(1);
        assertThat(Semaphore.clampMaxConcurrency(1)).isEqualTo(1);
        assertThat(Semaphore.clampMaxConcurrency(5)).isEqualTo(5);
        assertThat(Semaphore.clampMaxConcurrency(100)).isEqualTo(16);
        assertThat(Semaphore.clampMaxConcurrency(4)).isEqualTo(4);
    }
}
