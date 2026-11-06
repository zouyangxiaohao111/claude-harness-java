package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 检索扇出 · 移植 WeKnora {@code chat_pipeline/search_parallel.go} 的「并行检索 + 结果合并」。
 *
 * <h2>为什么并行</h2>
 * 一次问答可能要查多个知识库 / 多条改写查询。串行时总耗时 = 各库耗时之和；
 * 并行时 ≈ 最慢的那一个。检索是纯 IO 等待，串行没有收益。
 *
 * <h2>⭐ 单路失败不影响其它路</h2>
 * 一个库挂了 / 一个模型超时，不该让整轮问答空手而归 —— 部分结果仍然有用。
 * 源侧同样是「收集各自的错误，合并已经拿到的结果」。
 *
 * <h2>⚠️ 线程池为什么不能用 {@code ForkJoinPool.commonPool()}</h2>
 * 检索里有<b>阻塞的 JDBC 调用</b>。公共池的并行度按 CPU 核数算，
 * 阻塞几个任务就会把整个 JVM 里所有用公共池的地方（包括其它并行流）一起拖死。
 * ⇒ 自建固定池 + {@link ThreadPoolExecutor.CallerRunsPolicy}：
 * 队列满时提交者自己跑，代价是慢一点，但不会丢任务、不会 OOM。
 */
@Component
public class KbSearchFanOut {

    private static final Logger log = LoggerFactory.getLogger(KbSearchFanOut.class);

    /** 并行度上限。检索是 IO 型，但再大也只是把 SQLite 打得更散，4 是个保守值。 */
    private static final int MAX_PARALLELISM = 4;

    private final KbHybridSearcher searcher;

    /** 懒建：只有真的要用并行时才创建线程（单库单查询的常见路径不额外开线程）。 */
    private volatile ThreadPoolExecutor executor;

    public KbSearchFanOut(KbHybridSearcher searcher) {
        this.searcher = searcher;
    }

    /**
     * 一个检索计划（一条「查哪个库、查什么」）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询文本（已改写）
     * @param limit           本路返回条数上限
     * @param embeddingModelId 向量模型 ID（null/空 ⇒ 只走关键词）
     */
    public record QueryPlan(String knowledgeBaseId, String query, int limit, String embeddingModelId) {
    }

    /**
     * 带阈值的检索计划（源侧 {@code SearchParams.VectorThreshold / KeywordThreshold}）。
     *
     * @param plan            基础计划
     * @param vectorThreshold 向量余弦下限（&lt;= 0 = 不过滤）
     * @param keywordThreshold 关键词分下限（&lt;= 0 = 不过滤）
     */
    public record ScopedPlan(QueryPlan plan, double vectorThreshold, double keywordThreshold) {
    }

    /**
     * 并行执行全部检索计划并按 chunkId 合并。
     *
     * <p><b>合并规则</b>：同一个 chunk 可能被多条查询命中（分数不同）⇒ 保留<b>分数最高</b>那条。
     * 其他 chunk 按分数降序，同分按 chunkId 升序（保证同输入同输出，便于回归）。
     *
     * @param plans      检索计划（null/空 ⇒ 空结果）
     * @param totalLimit 合并后总条数上限（&lt;= 0 不限制）
     * @return 合并去重后的命中
     */
    /**
     * 带阈值的并行检索（阈值作用在每一路的原始分上，见 {@link KbHybridSearcher#search} 的重载）。
     *
     * @param plans      带阈值的计划
     * @param totalLimit 合并后总条数上限
     * @return 合并去重后的命中
     */
    public List<KbHybridSearcher.Hit> searchAllScoped(List<ScopedPlan> plans, int totalLimit) {
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        List<ScopedPlan> valid = plans.stream()
            .filter(p -> p != null && p.plan() != null
                && p.plan().knowledgeBaseId() != null && !p.plan().knowledgeBaseId().isBlank()
                && p.plan().query() != null && !p.plan().query().isBlank())
            .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        if (valid.size() == 1) {
            return dedupeAndCut(runOneScoped(valid.get(0)), totalLimit);
        }
        ThreadPoolExecutor pool = executor();
        List<CompletableFuture<List<KbHybridSearcher.Hit>>> futures = new ArrayList<>(valid.size());
        for (ScopedPlan p : valid) {
            futures.add(CompletableFuture.supplyAsync(() -> runOneScoped(p), pool));
        }
        List<KbHybridSearcher.Hit> all = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < futures.size(); i++) {
            try {
                all.addAll(futures.get(i).join());
            } catch (RuntimeException e) {
                failed++;
                log.warn("[检索扇出] 某一路异常 kb={} 原因={}",
                    valid.get(i).plan().knowledgeBaseId(), e.getMessage());
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[检索扇出] 完成(带阈值) 计划={} 失败={} 命中合计={}", valid.size(), failed, all.size());
        }
        return dedupeAndCut(all, totalLimit);
    }

    /** 执行单路（带阈值）；任何异常降级为空结果 + WARN。 */
    private List<KbHybridSearcher.Hit> runOneScoped(ScopedPlan p) {
        try {
            List<KbHybridSearcher.Hit> hits = searcher.search(
                p.plan().knowledgeBaseId(), p.plan().query(), p.plan().limit(),
                p.plan().embeddingModelId(), p.vectorThreshold(), p.keywordThreshold());
            return hits == null ? List.of() : hits;
        } catch (RuntimeException e) {
            log.warn("[检索扇出] 单路检索失败，跳过 kb={} 查询={} 原因={}",
                p.plan().knowledgeBaseId(), p.plan().query(), e.getMessage());
            return List.of();
        }
    }

    public List<KbHybridSearcher.Hit> searchAll(List<QueryPlan> plans, int totalLimit) {
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        List<QueryPlan> valid = plans.stream()
            .filter(p -> p != null && p.knowledgeBaseId() != null && !p.knowledgeBaseId().isBlank()
                && p.query() != null && !p.query().isBlank())
            .toList();
        if (valid.isEmpty()) {
            return List.of();
        }
        // 单条直接同步跑：常见路径（单库单查询）不必付线程调度成本
        if (valid.size() == 1) {
            QueryPlan p = valid.get(0);
            List<KbHybridSearcher.Hit> one = runOne(p);
            return dedupeAndCut(one, totalLimit);
        }

        ThreadPoolExecutor pool = executor();
        List<CompletableFuture<List<KbHybridSearcher.Hit>>> futures = new ArrayList<>(valid.size());
        for (QueryPlan p : valid) {
            futures.add(CompletableFuture.supplyAsync(() -> runOne(p), pool));
        }

        List<KbHybridSearcher.Hit> all = new ArrayList<>();
        int failed = 0;
        for (int i = 0; i < futures.size(); i++) {
            try {
                all.addAll(futures.get(i).join());
            } catch (RuntimeException e) {
                // runOne 本身已捕获业务异常；走到这里说明是线程池层面的问题
                failed++;
                QueryPlan p = valid.get(i);
                log.warn("[检索扇出] 某一路异常 kb={} 原因={}", p.knowledgeBaseId(), e.getMessage());
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[检索扇出] 完成 计划={} 失败={} 命中合计={}", valid.size(), failed, all.size());
        }
        return dedupeAndCut(all, totalLimit);
    }

    /** 执行单路；任何异常都降级为空结果 + WARN。 */
    private List<KbHybridSearcher.Hit> runOne(QueryPlan p) {
        try {
            List<KbHybridSearcher.Hit> hits = searcher.search(
                p.knowledgeBaseId(), p.query(), p.limit(), p.embeddingModelId());
            return hits == null ? List.of() : hits;
        } catch (RuntimeException e) {
            // ⭐ 一个库挂了不该让整轮问答失败 —— 其它库的结果仍然有用
            log.warn("[检索扇出] 单路检索失败，跳过 kb={} 查询={} 原因={}",
                p.knowledgeBaseId(), p.query(), e.getMessage());
            return List.of();
        }
    }

    /** 按 chunkId 去重（保留最高分）→ 分数降序 / id 升序 → 截断。 */
    private static List<KbHybridSearcher.Hit> dedupeAndCut(
        List<KbHybridSearcher.Hit> hits, int totalLimit
    ) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        Map<String, KbHybridSearcher.Hit> best = new LinkedHashMap<>();
        for (KbHybridSearcher.Hit h : hits) {
            if (h == null || h.chunkId() == null) {
                continue;
            }
            KbHybridSearcher.Hit prev = best.get(h.chunkId());
            // 同分也换成后来的？不 —— 保持第一次出现的，避免输出随并发完成顺序抖动
            if (prev == null || h.score() > prev.score()) {
                best.put(h.chunkId(), h);
            }
        }
        List<KbHybridSearcher.Hit> out = new ArrayList<>(best.values());
        out.sort(Comparator
            .comparingDouble(KbHybridSearcher.Hit::score).reversed()
            .thenComparing(KbHybridSearcher.Hit::chunkId));
        if (totalLimit > 0 && out.size() > totalLimit) {
            return new ArrayList<>(out.subList(0, totalLimit));
        }
        return out;
    }

    private ThreadPoolExecutor executor() {
        ThreadPoolExecutor e = executor;
        if (e != null) {
            return e;
        }
        synchronized (this) {
            if (executor == null) {
                executor = new ThreadPoolExecutor(
                    MAX_PARALLELISM, MAX_PARALLELISM,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(64),
                    r -> {
                        Thread t = new Thread(r, "kb-search-fanout");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy());
                executor.allowCoreThreadTimeOut(true);
            }
            return executor;
        }
    }

    /** 关闭线程池（Spring 容器销毁时）。 */
    @PreDestroy
    public void shutdown() {
        ThreadPoolExecutor e = executor;
        if (e != null) {
            e.shutdown();
            try {
                if (!e.awaitTermination(5, TimeUnit.SECONDS)) {
                    e.shutdownNow();
                }
            } catch (InterruptedException ie) {
                e.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
