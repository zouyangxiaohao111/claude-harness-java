package com.nexusai.application.agent.hook;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S7 H5/D2/D9/D10] PostSamplingHookRegistry 串行语义契约测试。
 * <p>CC 真源 {@code Open-ClaudeCode/src/utils/hooks/postSamplingHooks.ts:45-70}
 * {@code executePostSamplingHooks}：
 * <ul>
 *   <li><b>注册序串行</b>：{@code for (const hook of postSamplingHooks) { await hook(context) }}
 *       —— 批内 hook 逐条 await，互不重叠（H5）；</li>
 *   <li><b>无条件隔离</b>：{@code catch (error) { logError(toError(error)) }} 后 continue
 *       —— 任何异常（含 Error 子类）都不中断后续 hook、不向调用方传播（D2）；</li>
 *   <li><b>活数组遍历</b>：JS ArrayIterator 在迭代中 push 的元素本轮可见 —— Java 端
 *       size() 逐次重读对齐（D9）；</li>
 *   <li><b>跨批串行</b>：单线程执行器使多个 executeAll 批次按到达序排队、互不重叠
 *       （CC sequential 语义的 Java 表达，sessionMemory.ts:272 注册序队列等价；D10）。</li>
 * </ul>
 *
 * <p>旧实现（并行 runAsync + allOf + 快照 + RuntimeException-only + null 传播分支）在
 * 区间重叠 / Error 隔离 / 活数组注册 / null errorLogger 上均违反 CC 语义 —— 本测试先行红，
 * 随 executeAll 重写转绿。
 */
@DisplayName("PostSamplingHookRegistry 串行语义（CC postSamplingHooks.ts:45-70）")
class PostSamplingHookRegistrySerialTest {

    /** 区间记录：hook 执行起止（nanoTime 单调时钟），顺序 = 追加序。 */
    private static final class Interval {
        final int hookIndex;
        final long startNs;
        final long endNs;

        Interval(int hookIndex, long startNs, long endNs) {
            this.hookIndex = hookIndex;
            this.startNs = startNs;
            this.endNs = endNs;
        }
    }

    private final List<String> order = new ArrayList<>();
    private final List<Interval> intervals = new ArrayList<>();
    private final List<Object[]> errors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PostSamplingHookRegistry.clearAll();
    }

    @AfterEach
    void tearDown() {
        PostSamplingHookRegistry.clearAll();
    }

    private static PostSamplingContext ctx() {
        return new PostSamplingContext(List.<ChatMessageDto>of(), List.of("system"), Map.of(), Map.of(),
            null, QuerySource.REPL_MAIN_THREAD);
    }

    private static CompletableFuture<Void> awaitDone(CompletableFuture<Void> f) {
        assertThat(f).as("executeAll 返回的 future 必须在超时内完成")
            .succeedsWithin(10, TimeUnit.SECONDS);
        return f;
    }

    /** 记录式 hook：入参 hookIndex，执行时记 start/end 区间 + 完成序。 */
    private PostSamplingHookRegistry.PostSamplingHook recordingHook(int hookIndex) {
        return context -> {
            long start = System.nanoTime();
            synchronized (this) {
                order.add("hook" + hookIndex + "-start");
            }
            // 无阻塞，立即结束
            long end = System.nanoTime();
            synchronized (this) {
                intervals.add(new Interval(hookIndex, start, end));
                order.add("hook" + hookIndex + "-end");
            }
        };
    }

    @Test
    @DisplayName("H5：注册序串行执行，批内区间两两不重叠")
    void serialExecution_registrationOrderNoOverlap() {
        PostSamplingHookRegistry.register(recordingHook(0));
        PostSamplingHookRegistry.register(recordingHook(1));
        PostSamplingHookRegistry.register(recordingHook(2));

        awaitDone(PostSamplingHookRegistry.executeAll(ctx(), null));

        // 完成序 == 注册序（hook0-end → hook1-end → hook2-end）
        synchronized (this) {
            assertThat(order).containsExactly(
                "hook0-start", "hook0-end",
                "hook1-start", "hook1-end",
                "hook2-start", "hook2-end");
            assertThat(intervals).hasSize(3);
            // 批内区间互不重叠且按注册序排列：前一条的 end <= 后一条的 start
            for (int i = 1; i < intervals.size(); i++) {
                assertThat(intervals.get(i).startNs)
                    .as("hook#%d 不得在 hook#%d 完成前启动（CC await 串行）",
                        intervals.get(i).hookIndex, intervals.get(i - 1).hookIndex)
                    .isGreaterThanOrEqualTo(intervals.get(i - 1).endNs);
            }
        }
    }

    @Test
    @DisplayName("H5：前一条未完成时，后一条不得启动（latch 确定性验证）")
    void serialExecution_nextHookWaitsForPrevious() throws Exception {
        CountDownLatch hook0Started = new CountDownLatch(1);
        CountDownLatch releaseHook0 = new CountDownLatch(1);
        AtomicReference<Boolean> hook1Started = new AtomicReference<>(false);

        PostSamplingHookRegistry.register(ctx -> {
            hook0Started.countDown();
            try {
                assertThat(releaseHook0.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("latch await interrupted", e);
            }
        });
        PostSamplingHookRegistry.register(ctx -> hook1Started.set(true));

        CompletableFuture<Void> batch = PostSamplingHookRegistry.executeAll(ctx(), null);

        // hook0 阻塞期间，hook1 必须尚未启动（并行实现会立即触发 → 红）
        assertThat(hook0Started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(hook1Started.get())
            .as("hook#1 不得在 hook#0 完成前启动（CC for...of + await）")
            .isFalse();
        releaseHook0.countDown();
        awaitDone(batch);
        assertThat(hook1Started.get()).isTrue();
    }

    @Test
    @DisplayName("D2：RuntimeException → 后续 hook 仍执行，errorLogger 收到 (index, ex)")
    void runtimeExceptionIsolated_restStillRuns() {
        PostSamplingHookRegistry.register(ctx -> {
            throw new RuntimeException("boom");
        });
        PostSamplingHookRegistry.register(recordingHook(1));

        awaitDone(PostSamplingHookRegistry.executeAll(ctx(), (i, ex) -> {
            synchronized (errors) {
                errors.add(new Object[] {i, ex});
            }
        }));

        synchronized (this) {
            assertThat(order).as("hook#0 抛异常后 hook#1 必须仍执行")
                .contains("hook1-start", "hook1-end");
        }
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)[0]).isEqualTo(0);
        assertThat((Throwable) errors.get(0)[1]).isInstanceOf(RuntimeException.class)
            .hasMessage("boom");
    }

    @Test
    @DisplayName("D2：AssertionError（Error 子类）同样隔离，后续 hook 仍执行")
    void errorSubclassIsolated_restStillRuns() {
        PostSamplingHookRegistry.register(ctx -> {
            throw new AssertionError("explode");
        });
        PostSamplingHookRegistry.register(recordingHook(1));

        awaitDone(PostSamplingHookRegistry.executeAll(ctx(), (i, ex) -> {
            synchronized (errors) {
                errors.add(new Object[] {i, ex});
            }
        }));

        synchronized (this) {
            assertThat(order).as("hook#0 抛 Error 后 hook#1 必须仍执行（CC catch(error) 全捕获）")
                .contains("hook1-start", "hook1-end");
        }
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)[0]).isEqualTo(0);
        assertThat((Throwable) errors.get(0)[1]).isInstanceOf(AssertionError.class)
            .hasMessage("explode");
    }

    @Test
    @DisplayName("D9：执行中 register 的新 hook 本轮可见（活数组遍历）")
    void liveArrayRegistrationVisibleThisRound() {
        AtomicReference<Boolean> lateRan = new AtomicReference<>(false);
        // hook#0 在执行中注册新 hook（CC 迭代中 push → ArrayIterator 本轮可见）
        PostSamplingHookRegistry.register(ctx -> {
            PostSamplingHookRegistry.register(c -> lateRan.set(true));
        });
        PostSamplingHookRegistry.register(recordingHook(1));

        awaitDone(PostSamplingHookRegistry.executeAll(ctx(), null));

        assertThat(lateRan.get())
            .as("执行中注册的 hook 必须在本轮执行（JS ArrayIterator 活数组语义；快照实现会红）")
            .isTrue();
        // 新 hook 排在已有 hook#1 之后（追加序）
        synchronized (this) {
            assertThat(order).contains("hook1-start", "hook1-end");
        }
    }

    @Test
    @DisplayName("D2：errorLogger=null → 内部兜底日志 + 后续 hook 仍执行（无传播分支）")
    void nullErrorLoggerStillIsolates() {
        PostSamplingHookRegistry.register(ctx -> {
            throw new RuntimeException("silent-boom");
        });
        PostSamplingHookRegistry.register(recordingHook(1));

        // CC 无 errorLogger 概念：Java 注入器 null → 内部 slf4j 兜底（logError 恒记日志等价）
        awaitDone(PostSamplingHookRegistry.executeAll(ctx(), null));

        synchronized (this) {
            assertThat(order).as("errorLogger=null 也必须隔离（CC 永远 logError+continue，无传播）")
                .contains("hook1-start", "hook1-end");
        }
    }

    @Test
    @DisplayName("D10：跨批串行 —— 批次按到达序排队，互不重叠")
    void crossBatchSerialization() throws Exception {
        CountDownLatch batch1Started = new CountDownLatch(1);
        CountDownLatch releaseBatch1 = new CountDownLatch(1);
        AtomicReference<Boolean> batch2Started = new AtomicReference<>(false);

        PostSamplingHookRegistry.register(ctx -> {
            batch1Started.countDown();
            try {
                assertThat(releaseBatch1.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("latch await interrupted", e);
            }
        });
        PostSamplingHookRegistry.register(ctx -> batch2Started.set(true));

        CompletableFuture<Void> batch1 = PostSamplingHookRegistry.executeAll(ctx(), null);
        // 第二个批次的 hook 与第一批次共用注册表（同 hook 列表）——此处用两次 executeAll
        // 模拟连续两轮 post-sampling：第二轮排队在第一轮之后。
        assertThat(batch1Started.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> batch2 = PostSamplingHookRegistry.executeAll(ctx(), null);

        assertThat(batch2Started.get())
            .as("第二批次不得与第一批次重叠（单线程执行器 FIFO）")
            .isFalse();
        releaseBatch1.countDown();
        awaitDone(batch1);
        awaitDone(batch2);
        assertThat(batch2Started.get()).isTrue();
    }
}
