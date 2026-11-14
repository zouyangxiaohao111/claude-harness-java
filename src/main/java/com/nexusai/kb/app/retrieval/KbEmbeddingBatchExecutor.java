package com.nexusai.kb.app.retrieval;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 向量化子批的<b>进程级</b>执行池 · 对齐源站 {@code internal/models/embedding/batch.go}。
 *
 * <h2>源站结构（逐项对齐）</h2>
 * <pre>
 * BatchEmbedWithPool(texts)
 *   ├─ BATCH_EMBED_SIZE（env，默认 5）切块
 *   ├─ 每块丢进 **进程级** ants 池（CONCURRENCY_POOL_SIZE，默认 5）
 *   │    └─ worker 调 BatchEmbed(5 条) → 内部再按模型 max_batch_size 拆
 *   └─ 任一 worker 失败 ⇒ 置 firstErr，其余 worker 看到就跳过（不继续烧配额）
 * </pre>
 *
 * <p><b>⭐ 为什么池必须是进程级的</b>：源站的 ants 池在 container 里只建一次
 * （{@code internal/container/container.go:1873}），所有文档入库共用 ⇒ 全进程最多
 * {@code CONCURRENCY_POOL_SIZE} 个子批在途。若每个文档各建一个池，
 * 12 路文档并发 × 5 = 60 个请求同时打 provider，等于没限。
 *
 * <h2>与并发闸的关系</h2>
 * 池大小（默认 5）已经在 5 这层限住了；{@link KbEmbeddingConcurrencyGate}（默认 32）
 * 是第二道兜底，与源站「ants 池 + 每模型 governor 32」一致 —— 两道都在，不是二选一。
 *
 * <h2>顺序</h2>
 * 子批并发执行，结果按<b>输入顺序</b>拼接（与源站按 {@code text.results} 回填同一语义）。
 */
@Component
public class KbEmbeddingBatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(KbEmbeddingBatchExecutor.class);

    /** 子批大小 · 源站 {@code BATCH_EMBED_SIZE} 默认 5。 */
    static final int DEFAULT_SUB_BATCH = 5;

    /** 进程级池大小 · 源站 {@code CONCURRENCY_POOL_SIZE} 默认 5。 */
    static final int DEFAULT_POOL_SIZE = 5;

    private final int subBatchSize;
    private final ExecutorService executor;

    public KbEmbeddingBatchExecutor(
            @Value("${nexusai.knowledge.ingest.embed-sub-batch:5}") int subBatchSize,
            @Value("${nexusai.knowledge.ingest.embed-pool-size:5}") int poolSize) {
        this.subBatchSize = subBatchSize > 0 ? subBatchSize : DEFAULT_SUB_BATCH;
        int size = poolSize > 0 ? poolSize : DEFAULT_POOL_SIZE;
        // 有界固定池：与源站 ants.WithPreAlloc 同语义（预先建好 N 个 worker）。
        // 线程名前缀便于 jstack / 线程 dump 里认出这是向量化子批。
        this.executor = Executors.newFixedThreadPool(size, runnable -> {
            Thread t = new Thread(runnable, "kb-embed-batch");
            t.setDaemon(true);
            return t;
        });
        log.info("[向量化子批池] 已启动: 子批大小={} 并发={}（对齐源站 BATCH_EMBED_SIZE / CONCURRENCY_POOL_SIZE）",
            this.subBatchSize, size);
    }

    /**
     * 按子批切分并并发向量化。
     *
     * @param texts 本页全部文本（调用方已按页切好，通常 ≤ 64 条）
     * @param embed 实际嵌入函数：子批 → 向量列表（内部通常还过并发闸）
     * @return 与 {@code texts} 等长、同序的向量
     * @throws RuntimeException 任一子批失败时抛出（其余子批不再发起）
     */
    public List<float[]> embedInSubBatches(List<String> texts, Function<List<String>, List<float[]>> embed) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (texts.size() <= subBatchSize) {
            // 单子批：不必进池（线程切换无收益，且与源站「一个 chunk 也走池」行为等价）
            return embed.apply(texts);
        }

        List<List<String>> chunks = new ArrayList<>();
        for (int from = 0; from < texts.size(); from += subBatchSize) {
            chunks.add(texts.subList(from, Math.min(from + subBatchSize, texts.size())));
        }

        AtomicReference<RuntimeException> firstError = new AtomicReference<>();
        List<Future<List<float[]>>> futures = new ArrayList<>(chunks.size());
        for (List<String> chunk : chunks) {
            futures.add(executor.submit(() -> {
                // 任一子批已失败 ⇒ 不再发起新请求（对齐源站 firstErr 守卫，避免继续烧配额）
                if (firstError.get() != null) {
                    return null;
                }
                try {
                    return embed.apply(chunk);
                } catch (RuntimeException e) {
                    firstError.compareAndSet(null, e);
                    return null;
                }
            }));
        }

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int i = 0; i < futures.size(); i++) {
            List<float[]> got;
            try {
                got = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待向量化子批被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                throw cause instanceof RuntimeException re
                    ? re
                    : new IllegalStateException("向量化子批失败: " + cause, cause);
            }
            // 已置 firstErr 的 worker 返回 null（尚未发请求）
            if (got == null) {
                continue;
            }
            if (got.size() != chunks.get(i).size()) {
                throw new IllegalStateException("嵌入返回条数与请求不一致：请求 " + chunks.get(i).size()
                    + "，返回 " + got.size());
            }
            vectors.addAll(got);
        }
        RuntimeException error = firstError.get();
        if (error != null) {
            throw error;
        }
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("向量条数不足：期望 " + texts.size() + "，实际 " + vectors.size());
        }
        return vectors;
    }

    /** 子批大小（供日志/诊断）。 */
    public int subBatchSize() {
        return subBatchSize;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
