package com.nexusai.kb.app.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 入库 worker 并发池配置 · {@code nexusai.knowledge.ingest.*}。
 *
 * <h2>⭐⭐ 默认值逐字对齐源站 8 / 2 / 12（用户裁定 2026-11-12）</h2>
 * 源站 WeKnora 用 asynq 的多 Server 做「池硬隔离」，每池一个独立并发度
 * （{@code D:/tmp/WeKnora/internal/types/task.go:17-19}）：
 * <pre>
 *   DefaultCoreWorkerConcurrency        = 8    // 本仓 parse 池
 *   DefaultPostProcessWorkerConcurrency = 2    // 本仓 postprocess 池
 *   DefaultEnrichmentWorkerConcurrency  = 12   // 本仓 enrichment 池
 * </pre>
 * 本仓三个默认值<b>照抄源站 8 / 2 / 12，不取保守值</b>：并发度是「同时在做几件事」，
 * 而不是「同时能写几行库」—— SQLite 同一时刻只允许一个写事务，这件事<b>不靠降并发解决</b>，
 * 而是靠两处兜底：
 * <ol>
 *   <li>{@link KbIngestTaskQueue#requeue} —— 撞 {@code SQLITE_BUSY} /
 *       {@code SQLITE_BUSY_SNAPSHOT} 的任务直接回 {@code queued}，<b>不消耗 {@code retry_count}</b>，
 *       由 worker 的 {@code busyRetry} 独立计数留痕（见 {@code KbIngestWorker#handleBusy}）；</li>
 *   <li>单条 UPDATE 的 {@code SqliteBusyRetry} 兜底 —— 认领 / markDone / markFailure /
 *       finalizeSubtask 这类「换新语句重试天然安全」的写，按退避原地重试。</li>
 * </ol>
 * 所以并发度可以按源站给足：真正的串行点是写事务，而写事务由上面两层吸收掉抖动，
 * 不需要用「把池调小」这种把吞吐一起砍掉的办法。三个值仍全部可配
 * （{@code nexusai.knowledge.ingest.*}）。
 *
 * <h2>为什么用 {@code @Component} + {@code @ConfigurationProperties}</h2>
 * 本包没有 {@code @EnableConfigurationProperties} 注册点，且本类<b>只被
 * {@link KbIngestWorker} 构造器消费一次</b>，没有「静态全局 current」的需求
 * （对比 {@code PromptCachingTtlConfig} 那种被静态工具类消费的配置）。
 * Spring Boot 的 {@code ConfigurationPropertiesBindingPostProcessor} 对
 * {@code @Component} 标注的属性类同样生效 ⇒ 一个文件搞定，不必再加 bootstrap 配置类。
 *
 * <p><b>字段默认值必须与 {@code application.yml} 同值</b>：yml 键缺失（例如老部署的
 * 外部配置文件）时按这里的默认值起，两者不一致会让「文档写的」与「实际跑的」分叉。
 */
@Component
@ConfigurationProperties(prefix = "nexusai.knowledge.ingest")
public class KbIngestPoolProperties {

    /** parse 池默认并发（与源站 Core=8 逐字一致 · 用户裁定 2026-11-12 不取保守值）。 */
    public static final int DEFAULT_PARSE_CONCURRENCY = 8;

    /** postprocess 池默认并发（与源站 PostProcess=2 一致）。 */
    public static final int DEFAULT_POSTPROCESS_CONCURRENCY = 2;

    /** enrichment 池默认并发（与源站 Enrichment=12 逐字一致 · 用户裁定 2026-11-12 不取保守值）。 */
    public static final int DEFAULT_ENRICHMENT_CONCURRENCY = 12;

    /** Wiki 池默认并发（与源站 {@code DefaultWikiWorkerConcurrency=8} 一致）。 */
    public static final int DEFAULT_WIKI_CONCURRENCY = 8;

    /** 维护池默认并发（与源站 {@code DefaultMaintenanceWorkerConcurrency=4} 一致）。 */
    public static final int DEFAULT_MAINTENANCE_CONCURRENCY = 4;

    /** 弹性池默认并发（与源站 {@code DefaultSharedWorkerConcurrency=6} 一致）。 */
    public static final int DEFAULT_SHARED_CONCURRENCY = 6;

    /**
     * 上游池并发合计（源站 {@code DefaultUpstreamWorkerConcurrency} = core+postprocess+
     * enrichment+maintenance+shared = 32，**不含 wiki**）。仅用于日志/诊断对读。
     */
    public static final int DEFAULT_UPSTREAM_CONCURRENCY =
        DEFAULT_PARSE_CONCURRENCY + DEFAULT_POSTPROCESS_CONCURRENCY + DEFAULT_ENRICHMENT_CONCURRENCY
            + DEFAULT_MAINTENANCE_CONCURRENCY + DEFAULT_SHARED_CONCURRENCY;

    /** 轮询间隔默认值（毫秒）· 与改造前 {@code POLL_INTERVAL_MS} 逐字相同。 */
    public static final long DEFAULT_POLL_INTERVAL_MS = 1500L;

    /** 单轮最多认领多少条 · 与改造前 {@code MAX_TASKS_PER_TICK} 逐字相同。 */
    public static final int DEFAULT_MAX_TASKS_PER_TICK = 5;

    /** parse 池并发度（默认 8，与源站一致）。 */
    private int parseConcurrency = DEFAULT_PARSE_CONCURRENCY;

    /** postprocess 池并发度（默认 2，与源站一致）。 */
    private int postprocessConcurrency = DEFAULT_POSTPROCESS_CONCURRENCY;

    /** enrichment 池并发度（默认 12，与源站一致）。 */
    private int enrichmentConcurrency = DEFAULT_ENRICHMENT_CONCURRENCY;

    /** wiki 池并发度（默认 8，与源站独立池一致）。 */
    private int wikiConcurrency = DEFAULT_WIKI_CONCURRENCY;

    /** 维护池并发度（默认 4，与源站一致）。 */
    private int maintenanceConcurrency = DEFAULT_MAINTENANCE_CONCURRENCY;

    /** 弹性池并发度（默认 6，与源站一致；本仓不实现借用）。 */
    private int sharedConcurrency = DEFAULT_SHARED_CONCURRENCY;

    /**
     * 轮询间隔（毫秒，默认 1500）。
     *
     * <p>实际生效值由 {@link KbIngestWorker#poll()} 的
     * {@code @Scheduled(fixedDelayString = "${nexusai.knowledge.ingest.poll-interval-ms:1500}")}
     * 读取（同一个 yml 键）；本字段只用于启动日志把「实际值」打出来，便于排查
     * 「配置改了但没生效」。
     */
    private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

    /** 单轮最多认领多少条（默认 5，防止一次 tick 占住调度线程太久）。 */
    private int maxTasksPerTick = DEFAULT_MAX_TASKS_PER_TICK;

    public int getParseConcurrency() {
        return parseConcurrency;
    }

    public void setParseConcurrency(int parseConcurrency) {
        this.parseConcurrency = parseConcurrency;
    }

    public int getPostprocessConcurrency() {
        return postprocessConcurrency;
    }

    public void setPostprocessConcurrency(int postprocessConcurrency) {
        this.postprocessConcurrency = postprocessConcurrency;
    }

    public int getEnrichmentConcurrency() {
        return enrichmentConcurrency;
    }

    public void setEnrichmentConcurrency(int enrichmentConcurrency) {
        this.enrichmentConcurrency = enrichmentConcurrency;
    }

    public int getWikiConcurrency() {
        return wikiConcurrency;
    }

    public void setWikiConcurrency(int wikiConcurrency) {
        this.wikiConcurrency = wikiConcurrency;
    }

    public int getMaintenanceConcurrency() {
        return maintenanceConcurrency;
    }

    public void setMaintenanceConcurrency(int maintenanceConcurrency) {
        this.maintenanceConcurrency = maintenanceConcurrency;
    }

    public int getSharedConcurrency() {
        return sharedConcurrency;
    }

    public void setSharedConcurrency(int sharedConcurrency) {
        this.sharedConcurrency = sharedConcurrency;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getMaxTasksPerTick() {
        return maxTasksPerTick;
    }

    public void setMaxTasksPerTick(int maxTasksPerTick) {
        this.maxTasksPerTick = maxTasksPerTick;
    }
}
