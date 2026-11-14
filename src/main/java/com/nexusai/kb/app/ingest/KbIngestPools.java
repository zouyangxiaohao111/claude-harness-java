package com.nexusai.kb.app.ingest;

import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
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
            KbIngestTaskRecord.TYPE_NOOP)),
        /**
         * Wiki 生成 · 源站独立 {@code wiki} 池（并发 8）。
         *
         * <p>⭐ 为什么单独一池：源站把它从 postprocess 拆出来就是因为 Wiki 生成是**长任务**
         * （一个库可能要生成几十页），挤在 postprocess 的 2 并发里会同时拖慢摘要/问题生成，
         * 而后者才是「文档可用」的前置条件。
         */
        WIKI("wiki", List.of(KbIngestTaskRecord.TYPE_WIKI)),
        /**
         * 维护任务池 · 源站 {@code maintenance}=4（物理队列名仍是 {@code low}）。
         *
         * <p>本仓暂无对应任务类型（空列表 ⇒ 不路由任何任务，线程池不预创建线程、零开销）。
         * 保留它的目的：① 并发配置项与源站同名可对照；② 将来加清理/补偿类任务时有现成归属。
         */
        MAINTENANCE("maintenance", List.of()),
        /**
         * 弹性池 · 源站 {@code shared}=6（可借 core / enrichment 的空闲容量）。
         *
         * <p>⚠️ 本仓**不实现借用**（跨池信号量在 SQLite 单写锁下收益小于缺陷面，见类注释）；
         * 这里建池是为了「池并发合计」与源站口径一致，任务为空。
         */
        SHARED("shared", List.of()),
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

    /**
     * 热调后被替换下来的旧池。
     *
     * <p>⛔ 换池时<b>不立即 shutdown</b>（虽然 {@code shutdown()} 本身不打断在途任务，但保留旧池
     * 可以让我们按「该池自己的在途数归零」这一精确信号关闭它，而不是靠猜测）。
     * 等池内 {@code inflight == 0} 时调 {@code executor.shutdown()}；⛔ 全程不 {@code shutdownNow}。
     */
    private final List<Pool> retired = new ArrayList<>();

    /**
     * 名额归属（占位 / 归还必须落到<b>同一个池实例</b>）。
     *
     * <p>⭐ 这是热调最容易漏的一处：任务在旧池上占的名额，若 {@link #release(Kind)} 按
     * {@code pools.get(kind)} 归还，就会记到<b>新池</b>上 ⇒ 旧池在途永远 &gt; 0（永不关闭），
     * 新池在途被减成负数（背压容量被悄悄放大）。所以占位时把 Pool 记在本线程上，
     * 归还时优先按它归还。调度线程（占位后认领失败）与任务线程（{@link #submit} 包装）都覆盖。
     */
    private final ThreadLocal<Pool> reservationPool = new ThreadLocal<>();

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
                         int enrichmentConcurrency,
                         int wikiConcurrency, int maintenanceConcurrency, int sharedConcurrency) {
        pools.put(Kind.PARSE, new Pool(Kind.PARSE, Math.max(1, parseConcurrency)));
        pools.put(Kind.POSTPROCESS,
            new Pool(Kind.POSTPROCESS, Math.max(1, postprocessConcurrency)));
        pools.put(Kind.ENRICHMENT,
            new Pool(Kind.ENRICHMENT, Math.max(1, enrichmentConcurrency)));
        pools.put(Kind.WIKI, new Pool(Kind.WIKI, Math.max(1, wikiConcurrency)));
        pools.put(Kind.MAINTENANCE, new Pool(Kind.MAINTENANCE, Math.max(1, maintenanceConcurrency)));
        pools.put(Kind.SHARED, new Pool(Kind.SHARED, Math.max(1, sharedConcurrency)));
    }

    /**
     * 兼容构造器（3 池）· 既有单测与直构点用，缺省值取源站同名并发。
     */
    public KbIngestPools(int parseConcurrency, int postprocessConcurrency,
                         int enrichmentConcurrency) {
        this(parseConcurrency, postprocessConcurrency, enrichmentConcurrency,
            KbIngestPoolProperties.DEFAULT_WIKI_CONCURRENCY,
            KbIngestPoolProperties.DEFAULT_MAINTENANCE_CONCURRENCY,
            KbIngestPoolProperties.DEFAULT_SHARED_CONCURRENCY);
    }

    /**
     * 热调六池并发度（{@code /api/v1/settings/ingest-concurrency} PUT / 启动时应用 DB 配置）。
     *
     * <p><b>语义</b>：
     * <ul>
     *   <li>新 limit == 旧 limit ⇒ <b>不动</b>（不换池 = 不产生任何抖动）；</li>
     *   <li>否则新建 {@link Pool} 替换，旧池放进 {@link #retired}；</li>
     *   <li>旧池<b>不立即关闭</b>，等它自己的在途数归零再 {@code executor.shutdown()}
     *       （在途任务必须跑完整，⛔ 不 {@code shutdownNow}）。</li>
     * </ul>
     *
     * <p>⚠️ 换池后「旧池在途」仍被 {@link #totalInflight()} / {@link #awaitIdle(long)} /
     * {@link #describe()} 计入（见各自实现），否则 drain 判断会在旧任务还没跑完时就返回。
     *
     * @param parseConcurrency       parse 池并发度（&lt;= 0 按 1）
     * @param postprocessConcurrency postprocess 池并发度（&lt;= 0 按 1）
     * @param enrichmentConcurrency  enrichment 池并发度（&lt;= 0 按 1）
     * @param wikiConcurrency        wiki 池并发度（&lt;= 0 按 1）
     * @param maintenanceConcurrency maintenance 池并发度（&lt;= 0 按 1）
     * @param sharedConcurrency      shared 池并发度（&lt;= 0 按 1）
     */
    public synchronized void resize(int parseConcurrency, int postprocessConcurrency,
                                    int enrichmentConcurrency, int wikiConcurrency,
                                    int maintenanceConcurrency, int sharedConcurrency) {
        if (shutdown) {
            // 停机后换池会造出一个谁也收不到的线程池 ⇒ 直接拒绝（配置下次启动生效）
            log.warn("[入库worker] 已停机，忽略池并发热调");
            return;
        }
        resizeKind(Kind.PARSE, parseConcurrency);
        resizeKind(Kind.POSTPROCESS, postprocessConcurrency);
        resizeKind(Kind.ENRICHMENT, enrichmentConcurrency);
        resizeKind(Kind.WIKI, wikiConcurrency);
        resizeKind(Kind.MAINTENANCE, maintenanceConcurrency);
        resizeKind(Kind.SHARED, sharedConcurrency);
        reapRetiredLocked();
    }

    /** 换单个池（调用方持有 {@code this} 锁）。 */
    private void resizeKind(Kind kind, int requested) {
        int limit = Math.max(1, requested);
        Pool current = pools.get(kind);
        if (current != null && current.limit == limit) {
            return;
        }
        Pool replacement = new Pool(kind, limit);
        if (current != null) {
            // 并发度实测值跨代保留：maxObserved 是「本次进程内见过的最多同时在做几件」，换池不改写这个事实
            replacement.maxObserved.set(current.maxObserved.get());
            retired.add(current);
            log.info("[入库worker] 池并发热调 kind={} {} → {}（旧池在途={}，归零后关闭）",
                kind.id(), current.limit, limit, current.inflight.get());
        } else {
            log.info("[入库worker] 池并发初始 kind={} limit={}", kind.id(), limit);
        }
        pools.put(kind, replacement);
    }

    /**
     * 关闭已经排空的旧池（调用方持有 {@code this} 锁）。
     *
     * <p>判定用<b>旧池自己的</b> in-flight 计数，而不是全局计数 —— 别的池还在跑并不妨碍
     * 这个旧池已经没人了。{@code shutdown()} 只停止接收新任务、不打断在途任务。
     */
    private void reapRetiredLocked() {
        Iterator<Pool> it = retired.iterator();
        while (it.hasNext()) {
            Pool pool = it.next();
            if (pool.inflight.get() <= 0) {
                pool.executor.shutdown();
                it.remove();
                log.info("[入库worker] 旧池已排空并关闭 kind={} limit={}", pool.kind.id(), pool.limit);
            }
        }
    }

    /** 关闭已排空旧池（无锁入口 · 归还会员时调用）。 */
    private void reapRetired() {
        synchronized (this) {
            reapRetiredLocked();
        }
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
                // 记下名额归属：热调换池后，归还必须落到这个（可能已退休的）池实例上
                reservationPool.set(pool);
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
        // ⭐ 优先还给「占位时的那个池」：热调换池后 pools.get(kind) 已是新池，
        //    按新池归还会把旧池的在途永久留存（永不排空）+ 新池计数减成负数。
        Pool reserved = reservationPool.get();
        Pool pool;
        if (reserved != null && reserved.kind == kind) {
            reservationPool.remove();
            pool = reserved;
        } else {
            pool = pool(kind);
        }
        int left = pool.inflight.decrementAndGet();
        if (left < 0) {
            // 多还了：把计数夹回 0 并留痕（异常路径要能查出来，⛔ 不静默）
            pool.inflight.compareAndSet(left, 0);
            log.warn("[入库worker] 名额归还次数超过占位次数 pool={} 计数已夹回 0", kind.id());
        }
        // 旧池可能刚排空 ⇒ 顺手关闭（在途归零 → shutdown，不打断任何任务）
        reapRetired();
        synchronized (idleMonitor) {
            idleMonitor.notifyAll();
        }
    }

    /**
     * 当前在途数（含该池热调前旧池里还没跑完的任务）。
     *
     * <p>⚠️ 不含 retired 的话，日志会显示「在途 0」但实际还有任务在跑（热调窗口内）。
     */
    public int inflight(Kind kind) {
        int total = pool(kind).inflight.get();
        synchronized (this) {
            for (Pool pool : retired) {
                if (pool.kind == kind) {
                    total += pool.inflight.get();
                }
            }
        }
        return total;
    }

    /** 池容量。 */
    public int limit(Kind kind) {
        return pool(kind).limit;
    }

    /** 本次进程内该池同时达到过的最大在途数（并发度实测值 · 测试与排查用）。 */
    public int maxObservedInflight(Kind kind) {
        return pool(kind).maxObserved.get();
    }

    /**
     * 六池在途总和（<b>含热调后 retired 旧池的在途</b>）。
     *
     * <p>⚠️ 漏掉 retired 会让 {@link #awaitIdle(long)} 在旧池任务还没跑完时就返回 true ——
     * 测试里表现为「drain 完成但任务还在写库」，生产里表现为停机过早。
     */
    public int totalInflight() {
        int total = 0;
        synchronized (this) {
            for (Pool pool : pools.values()) {
                total += pool.inflight.get();
            }
            for (Pool pool : retired) {
                total += pool.inflight.get();
            }
        }
        return total;
    }

    /** 各池 {@code inflight/limit} 快照（日志用；热调后未排空的旧池带 {@code @retired} 后缀）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        synchronized (this) {
            for (Pool pool : pools.values()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(pool.kind.id()).append('=')
                    .append(pool.inflight.get()).append('/').append(pool.limit);
            }
            for (Pool pool : retired) {
                sb.append(',').append(pool.kind.id()).append("@retired=")
                    .append(pool.inflight.get()).append('/').append(pool.limit);
            }
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
        // ⭐ 提交也按「占位时的池」走：调度线程可能刚在旧池上占好名额、还没提交就撞上热调换池。
        //    若这里改用当前池，名额会留在旧池（旧池永不排空）而新池被多减一次。
        //    旧池此时 inflight >= 1（本次占位）⇒ 一定还没被 reap 关闭，提交安全。
        Pool reserved = reservationPool.get();
        final Pool target = reserved != null && reserved.kind == kind ? reserved : pool(kind);
        target.executor.execute(() -> {
            Pool previous = reservationPool.get();
            reservationPool.set(target);
            try {
                task.run();
            } finally {
                if (previous == null) {
                    reservationPool.remove();
                } else {
                    reservationPool.set(previous);
                }
            }
        });
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
        // 快照当前池 + 所有旧池：旧池里可能还有在途任务（刚热调过），停机必须一并等
        List<Pool> all;
        synchronized (this) {
            all = new ArrayList<>(pools.values());
            all.addAll(retired);
        }
        for (Pool pool : all) {
            pool.executor.shutdown();
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        boolean terminated = true;
        for (Pool pool : all) {
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
            for (Pool pool : all) {
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
