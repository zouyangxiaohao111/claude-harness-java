package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 6 并发抓取护栏 —— 直接打生产代码 {@link KbQaPipeline#fetchAllConcurrently}。
 *
 * <p>⚠️ 这个类只测「并发编排」这一件事：<b>真的并行</b>、<b>按索引保序</b>、
 * <b>单页失败不传染</b>。业务语义（哪些条该抓、抓到什么进上下文）在流水线其它测试里。
 *
 * <p>⭐ 判据要点：<b>不能</b>另写一份并发的复刻来测（那样验的是 {@code CompletableFuture} 本身）；
 * 必须调生产方法 —— 否则把实现改回串行时测试照样绿，等于没测。
 */
class KbQaPipelineConcurrencyTest {

    /** 单页模拟耗时（毫秒）。 */
    private static final long PAGE_LATENCY_MS = 120;

    @Test
    @DisplayName("前 N 页并发抓取：总耗时 ≈ 单页，而不是 N 倍")
    void fetchRunsInParallel() throws Exception {
        int pages = 3;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.nanoTime();
            List<String> got = KbQaPipeline.fetchAllConcurrently(pool, pages, i -> {
                sleep(PAGE_LATENCY_MS);
                return "page-" + i;
            });
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(List.of("page-0", "page-1", "page-2"), got);
            // 串行 = 360ms；并行应 ≈120ms。阈值取 2 倍单页（240ms）：留 CI 抖动余量，
            // 但仍能把「改回串行」判红（360ms > 240ms）。
            assertTrue(elapsedMs < PAGE_LATENCY_MS * 2,
                "应并发执行，实测 " + elapsedMs + "ms（串行约 " + (PAGE_LATENCY_MS * pages) + "ms）");
        }
    }

    @Test
    @DisplayName("结果按索引保序：越靠前的页越慢也不会被排到后面")
    void resultsKeepIndexOrder() throws Exception {
        // ⭐ 故意让前面的页更慢：按完成顺序收集必然反转。
        long[] latency = {160, 90, 30};
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> got = KbQaPipeline.fetchAllConcurrently(pool, latency.length, i -> {
                sleep(latency[i]);
                return "page-" + i;
            });
            assertEquals(List.of("page-0", "page-1", "page-2"), got,
                "必须按原索引取，⛔ 不能按完成先后");
        }
    }

    @Test
    @DisplayName("单页失败不传染：返回 null 占位，其余页照常返回")
    void singlePageFailureDoesNotBreakOthers() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> got = KbQaPipeline.fetchAllConcurrently(pool, 3, i -> {
                if (i == 1) {
                    throw new IllegalStateException("模拟抓取失败");
                }
                return "page-" + i;
            });
            assertEquals(3, got.size(), "长度必须等于请求条数（失败位留 null）");
            assertEquals("page-0", got.get(0));
            assertNull(got.get(1), "失败的那页必须是 null，由调用方决定回落/丢弃");
            assertEquals("page-2", got.get(2));
        }
    }

    @Test
    @DisplayName("count <= 0 返回空列表，且一次都不调 fetcher")
    void zeroCountDoesNotCallFetcher() {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            assertTrue(KbQaPipeline.fetchAllConcurrently(pool, 0, i -> {
                calls.incrementAndGet();
                return "x";
            }).isEmpty());
            assertTrue(KbQaPipeline.fetchAllConcurrently(pool, -3, i -> {
                calls.incrementAndGet();
                return "x";
            }).isEmpty());
        }
        assertEquals(0, calls.get(), "0 条时不该发起任何抓取");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
