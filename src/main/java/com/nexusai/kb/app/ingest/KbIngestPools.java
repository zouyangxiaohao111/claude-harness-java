package com.nexusai.kb.app.ingest;

import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 入库并发池 · 承接源站 asynq 多池语义里与本仓相关的三池
 * （{@code D:/tmp/WeKnora/internal/types/task.go:17-19}：Core=8 / PostProcess=2 / Enrichment=12）。
 *
 * <h2>⭐⭐ 为什么必须「按池有界」而不是「一个大线程池」</h2>
 * 源站每池是一个独立 {@code asynq.Server} ⇒ 并发度硬隔离：12 个图片理解任务不会把 8 个解析
 * 名额吃光，2 个摘要任务也不会被 8 个解析任务挤在身后。一个大池做不到这一点 ——
 * 池满时后到的解析任务会排在图片任务后面，等于「按到达顺序」而不是「按池配额」。
 *
 * <h2>⭐⭐ 背压 = 提交前占位，不是提交后拒绝</h2>
 * 调度线程先 {@link #tryReserve(Kind)}：只有拿到名额才去 {@code claimNext}。名额在
 * <b>认领之前</b>占、在任务<b>真正跑完之后</b>才还 ⇒ 每池在途恒 &lt;= limit，
 * 任务不会堆在无界队列里（SQLite 场景下，堆在内存里的任务 = 同时存在 N 份解析产物，
 * 且把写锁冲突放大成重试风暴）。
 *
 * <p>线程池用容量 = limit 的 {@link LinkedBlockingQueue} 而不是
 * {@code Executors.newFixedThreadPool} 的无界队列：即便上层占位逻辑将来出 bug，
 * 队列上限也把内存占用封死（宁可拒绝，也不悄悄堆）。
 *
 * <h2>⚠️ 已知取舍：池之间不借名额</h2>
 * 源站还有 maintenance / shared / wiki 三池，其中 shared 会「借」core 与 enrichment 的空闲容量。
 * 本仓<b>不实现借用</b>：借用要跨池信号量，而 SQLite 单写锁下真正的瓶颈是写事务而不是名额；
 * 少一层借用就少一处并发缺陷面。若某池长期空转而另一池长期堵住，直接调配置即可
 * （比「借用」的语义更可预测）。
 */
public final class KbIngestPools {

    private static final Logger log = LoggerFactory.getLogger(KbIngestPools.class);

    /** 同一池「池满」日志的最小间隔（毫秒）· 防止每 1.5s 一条刷屏。 */
    private static final long BACKPRESSURE_LOG_INTERVAL_MS = 10_000L;

    /** 池分类（路由目标）。 */
    public enum Kind {
        /** 解析主流程 · 源站 Core 池（源站 8，本仓默认 4）。 */
        PARSE("parse", List.of(KbIngestTaskRecord.TYPE_PARSE)),
        /**
         * 后处理 · 源站 PostProcess 池（源站 2，本仓默认 2）。
         *
         * <p>⚠️ {@code noop} 也在这里：它唯一的职责是释放一个 finalizing 槽位，属于后处理
         * 账本的一部分；放进 parse 池会白占一个解析名额（解析才是长任务）。
         */
        POSTPROCESS("postprocess", List.of(
            KbIngestTaskRecord.TYPE_SUMMARY,
            KbIngestTaskRecord.TYPE_QUESTION,
            KbIngestTaskRecord.TYPE_AUTO_TAG,
            KbIngestTaskRecord.TYPE_WIKI,
            KbIngestTaskRecord.TYPE_NOOP)),
        /** 增强 · 源站 Enrichment 池（源站 12，本仓默认 8；每个任务都是一次外部模型调用）。 */
        ENRICHMENT("enrichment", List.of(
            KbIngestTaskRecord.TYPE_GRAPH,
            KbIngestTaskRecord.TYPE_IMAGE_UNDERSTAND));

        private final String id;
        private final List<String> taskTypes;

        Kind(String id, List<String> taskTypes) {
            this.id = id;
            this.taskTypes = taskTypes;
        }

        /** 日志/指标用的池名（与源站 pool 名对齐，便于两边日志对读）。 */
        public String id() {
            return id;
        }

        /** 本池承载的任务类型（白名单，用于带类型的原子认领）。 */
        public List<String> taskTypes() {
            return taskTypes;
        }
    }

    private final Map<Kind, Pool> pools = new EnumMap<>(Kind.class);

    /** 在途计数归零时唤醒 {@link #awaitIdle(long)} 的监视器。 */
    private final Object idleMonitor = new Object();

    /** 停机标志：置位后不再接受新名额（在途任务照常跑完）。 */
    private volatile boolean shutdown;

    /**
     * 构造三池。
     *
     * @param parseConcurrency       parse 池并发度（&lt;= 0 按 1）
     * @param postprocessConcurrency postprocess 池并发度（&lt;= 0 按 1）
     * @param enrichmentConcurrency  enrichment 池并发度（&lt;= 0 按 1）
     */
    public KbIngestPools(int parseConcurrency, int postprocessConcurrency,
                         int enrichmentConcurrency) {
        pools.put(Kind.PARSE, new Pool(Kind.PARSE, Math.max(1, parseConcurrency)));
        pools.put(Kind.POSTPROCESS,
            new Pool(Kind.POSTPROCESS, Math.max(1, postprocessConcurrency)));
        pools.put(Kind.ENRICHMENT,
            new Pool(Kind.ENRICHMENT, Math.max(1, enrichmentConcurrency)));
    }

    /**
     * 任务类型 → 池（路由）。
     *
     * <p>未知类型路由到 {@link Kind#POSTPROCESS}：改造前 {@code execute()} 对未知类型走
     * {@code markFailure("未知任务类型")}，这条路径必须仍然可达（否则库里脏类型的行会永远
     * {@code queued}，没人认领也就没人判死）。放后处理池不会影响解析/增强的隔离性。
     *
     * @param taskType 任务类型（可空）
     * @return 池（永不 null）
     */
    public static Kind kindOf(String taskType) {
        if (taskType != null) {
            for (Kind kind : Kind.values()) {
                if (kind.taskTypes.contains(taskType)) {
                    return kind;
                }
            }
        }
        return Kind.POSTPROCESS;
    }

    /**
     * 尝试占用一个名额（背压闸门）。
     *
     * @param kind 池
     * @return true = 拿到名额（调用方随后必须 {@link #release(Kind)}）；false = 池满或已停机
     */
    public boolean tryReserve(Kind kind) {
        return tryReserve(pool(kind));
    }

    private boolean tryReserve(Pool pool) {
        if (shutdown) {
            return false;
        }
        while (true) {
            int current = pool.inflight.get();
            if (current >= pool.limit) {
                return false;
            }
            if (pool.inflight.compareAndSet(current, current + 1)) {
                pool.maxObserved.accumulateAndGet(current + 1, Math::max);
                return true;
            }
        }
    }

    /**
     * 归还一个名额（任务结束 / 认领落空时调用）。
     *
     * <p>⛔ 必须与 {@link #tryReserve(Kind)} 严格配对：漏还 = 该池永久少一个名额，
     * 多还 = 池容量被悄悄放大（背压失效）。
     *
     * @param kind 池
     */
    public void release(Kind kind) {
        Pool pool = pool(kind);
        int left = pool.inflight.decrementAndGet();
        if (left < 0) {
            // 多还了：把计数夹回 0 并留痕（异常路径要能查出来，⛔ 不静默）
            pool.inflight.compareAndSet(left, 0);
            log.warn("[入库worker] 名额归还次数超过占位次数 pool={} 计数已夹回 0", kind.id());
        }
        synchronized (idleMonitor) {
            idleMonitor.notifyAll();
        }
    }

    /** 当前在途数。 */
    public int inflight(Kind kind) {
        return pool(kind).inflight.get();
    }

    /** 池容量。 */
    public int limit(Kind kind) {
        return pool(kind).limit;
    }

    /** 本次进程内该池同时达到过的最大在途数（并发度实测值 · 测试与排查用）。 */
    public int maxObservedInflight(Kind kind) {
        return pool(kind).maxObserved.get();
    }

    /** 三池在途总和。 */
    public int totalInflight() {
        int total = 0;
        for (Pool pool : pools.values()) {
            total += pool.inflight.get();
        }
        return total;
    }

    /** 各池 {@code inflight/limit} 快照（日志用）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Pool pool : pools.values()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(pool.kind.id()).append('=')
                .append(pool.inflight.get()).append('/').append(pool.limit);
        }
        return sb.toString();
    }

    /**
     * 「池满」日志是否需要打（同池 10s 节流）。
     *
     * @param kind 池
     * @return true = 该打
     */
    public boolean shouldLogBackpressure(Kind kind) {
        Pool pool = pool(kind);
        long now = System.currentTimeMillis();
        long previous = pool.lastBackpressureLogAt.get();
        return now - previous >= BACKPRESSURE_LOG_INTERVAL_MS
            && pool.lastBackpressureLogAt.compareAndSet(previous, now);
    }

    /**
     * 提交到该池执行（调用方必须已持有名额）。
     *
     * @param kind 池
     * @param task 任务体（内部负责在 finally 里 {@link #release(Kind)}）
     * @throws java.util.concurrent.RejectedExecutionException 池已停机
     */
    public void submit(Kind kind, Runnable task) {
        pool(kind).executor.execute(task);
    }

    /** 是否已停机。 */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * 等待所有池在途归零。
     *
     * <p>⚠️ 只在「测试 / 手动驱动」路径使用：{@code poll()} 绝不等待（调度线程不能被占住）。
     *
     * @param timeoutMs 超时（&lt;= 0 时立即返回当前是否空闲）
     * @return true = 已归零；false = 超时仍有在途
     */
    public boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (idleMonitor) {
            while (totalInflight() > 0) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    return false;
                }
                try {
                    idleMonitor.wait(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 停机：不再接受新名额，等待在途任务收尾（超时则中断），⛔ 不无限等。
     *
     * @param timeoutMs 最长等待
     * @return true = 在途全部正常收尾；false = 超时（已中断剩余线程）
     */
    public boolean shutdownAndAwait(long timeoutMs) {
        shutdown = true;
        for (Pool pool : pools.values()) {
            pool.executor.shutdown();
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        boolean terminated = true;
        for (Pool pool : pools.values()) {
            long remain = deadline - System.currentTimeMillis();
            try {
                if (remain <= 0 || !pool.executor.awaitTermination(remain, TimeUnit.MILLISECONDS)) {
                    terminated = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                terminated = false;
            }
        }
        if (!terminated) {
            // 超时：中断剩余任务（它们的 finally 仍会归名额），⛔ 不把停机拖成无限等
            for (Pool pool : pools.values()) {
                if (!pool.executor.isTerminated()) {
                    pool.executor.shutdownNow();
                }
            }
        }
        return terminated;
    }

    private Pool pool(Kind kind) {
        Pool pool = pools.get(kind);
        if (pool == null) {
            throw new IllegalArgumentException("未知池: " + kind);
        }
        return pool;
    }

    /** 单池：有界线程池 + 在途计数 + 观测值。 */
    private static final class Pool {
        private final Kind kind;
        private final int limit;
        private final ThreadPoolExecutor executor;
        private final AtomicInteger inflight = new AtomicInteger();
        private final AtomicInteger maxObserved = new AtomicInteger();
        private final AtomicLong lastBackpressureLogAt = new AtomicLong(0L);

        private Pool(Kind kind, int limit) {
            this.kind = kind;
            this.limit = limit;
            AtomicInteger seq = new AtomicInteger();
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable,
                    "kb-ingest-" + kind.id() + "-" + seq.incrementAndGet());
                // 守护线程：即便某个调用方忘了 destroy()，JVM 也能退出（测试/CLI 场景）
                thread.setDaemon(true);
                return thread;
            };
            this.executor = new ThreadPoolExecutor(
                limit, limit, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(1, limit)),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        }
    }

    /** 三池名的固定顺序（日志/测试断言用；⛔ 不用 EnumMap 的遍历顺序做契约）。 */
    public static List<String> poolIds() {
        List<String> ids = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            ids.add(kind.id());
        }
        return ids;
    }
}
