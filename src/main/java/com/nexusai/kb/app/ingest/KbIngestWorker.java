package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.compact.SqliteBusyRetry;
import com.nexusai.kb.app.ingest.span.KbSpan;
import com.nexusai.kb.app.ingest.span.KbSpanKind;
import com.nexusai.kb.app.ingest.span.KbSpanTracker;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 入库任务 worker · 单调度线程认领 + 三池并发执行（承接源侧 Redis asynq 的消费端）。
 *
 * <h2>⭐⭐ 并发模型（源站 asynq 多池 → 本仓三池）</h2>
 * 源站每池是独立 {@code asynq.Server}，并发度硬隔离
 * （{@code D:/tmp/WeKnora/internal/types/task.go:17-19}：Core=8 / PostProcess=2 / Enrichment=12）。
 * 本仓映射为三个<b>有界线程池</b>（见 {@link KbIngestPools}）：
 * <ul>
 *   <li>{@code parse} ← {@code parse}（源站 8，本仓默认 8）</li>
 *   <li>{@code postprocess} ← {@code summary / question / auto_tag / wiki / noop}（源站 2，默认 2）</li>
 *   <li>{@code enrichment} ← {@code graph / image_understand}（源站 12，默认 12）</li>
 * </ul>
 * <b>为什么默认值与源站逐字一致</b>（用户裁定 2026-11-12）：源站写 MySQL/Postgres（多写者行级锁），
 * SQLite 同一时刻只允许一个写事务 —— 但<b>并发度不是靠降下来解决锁冲突的</b>。写冲突由两条兜底吸收：
 * BUSY 任务经 {@link KbIngestTaskQueue#requeue} 回 {@code queued}（<b>不消耗 {@code retry_count}</b>，
 * 见 {@link #handleBusy}），单条 UPDATE 由 {@code SqliteBusyRetry} 原地退避重试。
 * 降并发只会把「同时在做的解析/增强」一起砍掉，锁冲突却依然存在。
 *
 * <h2>⭐ 三条不可动摇的边界</h2>
 * <ol>
 *   <li><b>只有调度线程认领</b>：{@link #poll()} 是唯一调用
 *       {@link KbIngestTaskQueue#claimNextOfTypes(java.util.Collection)} 的地方；
 *       任务线程永远不认领（否则「认领」与「执行」的并发面会叠乘）。</li>
 *   <li><b>先占名额再认领</b>：每池在途达到池大小就<b>停止认领</b>（背压），任务不会堆在
 *       内存队列里。{@link #drainBatch(int)} 供测试/手动驱动（提交后等待收尾），
 *       {@link #poll()} 只提交、绝不等待（调度线程不能被占住）。</li>
 *   <li><b>BUSY 不回退成 dead</b>：{@code SQLITE_BUSY} / {@code SQLITE_BUSY_SNAPSHOT} 一律
 *       按「可重试失败」回 {@code queued}（{@link KbIngestTaskQueue#requeue}），
 *       <b>不消耗 {@code retry_count}</b>，由独立 {@code busyRetry} 计数留痕。</li>
 * </ol>
 *
 * <p><b>为什么是轮询而不是事件驱动</b>：本仓是单机 SQLite，没有 Redis/Broker；
 * 任务来源只有本进程写入的行。轮询间隔默认 1.5s（可配），对单机知识入库足够。
 *
 * <h2>⭐⭐ W6：槽位递减必须按 {@link KbPostProcessOutcome.Status} 分流</h2>
 * 源侧 {@code finalizeSubtaskDetached(..., superseded, ...)} 的第二个开关就是「不递减」。
 * 这里把四种状态映射成两种落点，<b>任何一条写错都会让文档永久卡在 finalizing（计数减不到 0）
 * 或提前完成（计数被多减）</b>：
 * <ul>
 *   <li>{@code DONE} / {@code SKIPPED} ⇒ 递减一次</li>
 *   <li>{@code SUPERSEDED} ⇒ <b>不递减</b>（这是新一轮的账，不是我的）</li>
 *   <li>{@code FAILED} ⇒ 还有重试次数就不递减；最后一次失败才递减（否则最终失败会永久卡住）</li>
 * </ul>
 */
@Component
public class KbIngestWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KbIngestWorker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 停机等待在途任务收尾的上限（毫秒）· 超时打 warn 并中断，⛔ 不无限等。 */
    private static final long SHUTDOWN_WAIT_MS = 30_000L;

    /** {@link #drainBatch(int)} 等待在途归零的上限（毫秒）· 仅测试/手动驱动路径。 */
    private static final long DRAIN_WAIT_MS = 120_000L;

    /**
     * 同一任务连续命中 BUSY 的上限。
     *
     * <p>正常 BUSY 一两次就散；真的 50 次不散说明库被长期写锁占住（或磁盘故障），
     * 继续回队只会刷屏。超限后按普通失败处理（此时才允许消耗 {@code retry_count}），
     * 并打 error 留痕。
     */
    private static final int MAX_BUSY_REQUEUE_PER_TASK = 50;

    private final KbIngestTaskQueue queue;
    private final KnowledgeParseProcessor parseProcessor;
    private final KbSummaryService summaryService;
    private final KbQuestionGenerationService questionService;
    private final KbAutoTagService autoTagService;
    private final KbWikiTaskHandler wikiHandler;
    private final com.nexusai.kb.app.graph.KbGraphExtractionService graphService;
    private final com.nexusai.kb.app.image.KbImageEnrichService imageEnrichService;
    private final com.nexusai.kb.app.retrieval.KbIndexWriter indexWriter;
    private final com.nexusai.kb.repo.mapper.KbKnowledgeMapper knowledgeMapper;
    private final com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbKnowledgeStateRepository stateRepository;

    /**
     * span 树写入口（trace 观测层）。
     *
     * <p>{@code required = false}：单测直构 worker（兼容构造器）时不装配 tracker ——
     * 子 span 的 begin / 收口全部 no-op，队列与槽位账的行为与改造前逐步一致。
     */
    @Autowired(required = false)
    private KbSpanTracker spanTracker;

    /**
     * 图片向量索引（{@code kb_image_embedding}）。
     *
     * <p>{@code required = false}：单测直构 worker 时不装配 ⇒ 整条图片向量链 no-op，
     * 与加这条链之前的行为逐步一致。
     */
    @Autowired(required = false)
    private com.nexusai.kb.app.image.KbImageVectorService imageVectorService;

    /** 三池（有界并发 + 背压 + 停机收尾）。 */
    private final KbIngestPools pools;

    /** 单轮最多认领多少条（配置值，&gt;= 1）。 */
    private final int maxTasksPerTick;

    /** 停机标志：置位后不再认领（在途任务照常跑完）。 */
    private volatile boolean shuttingDown;

    /** BUSY 回队总次数（诊断/测试断言用；与任务的 {@code retry_count} 完全独立）。 */
    private final AtomicLong busyRetryCount = new AtomicLong();

    /** 每个任务连续命中 BUSY 的次数（超过 {@link #MAX_BUSY_REQUEUE_PER_TASK} 才退回普通失败）。 */
    private final ConcurrentHashMap<Long, AtomicInteger> busyHits = new ConcurrentHashMap<>();

    /**
     * 生产构造器 · 读 {@code nexusai.knowledge.ingest.*} 装配三池。
     *
     * <p>⚠️ 本类有<b>两个公开</b>构造器 ⇒ Spring 必须靠 {@code @Autowired} 选中这一个。
     *
     * @param properties 并发池/轮询配置（yml 缺键时字段默认值即 8/2/12/1500/5）
     */
    @Autowired
    public KbIngestWorker(
        KbIngestTaskQueue queue,
        KnowledgeParseProcessor parseProcessor,
        KbSummaryService summaryService,
        KbQuestionGenerationService questionService,
        KbAutoTagService autoTagService,
        KbWikiTaskHandler wikiHandler,
        com.nexusai.kb.app.graph.KbGraphExtractionService graphService,
        com.nexusai.kb.app.image.KbImageEnrichService imageEnrichService,
        com.nexusai.kb.app.retrieval.KbIndexWriter indexWriter,
        com.nexusai.kb.repo.mapper.KbKnowledgeMapper knowledgeMapper,
        com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbKnowledgeStateRepository stateRepository,
        KbIngestPoolProperties properties
    ) {
        this(queue, parseProcessor, summaryService, questionService, autoTagService, wikiHandler,
            graphService, imageEnrichService, indexWriter, knowledgeMapper, knowledgeBaseMapper,
            stateRepository,
            new KbIngestPools(
                properties.getParseConcurrency(),
                properties.getPostprocessConcurrency(),
                properties.getEnrichmentConcurrency()),
            properties.getMaxTasksPerTick());
    }

    /**
     * 兼容构造器（单测直构 · 不启 Spring）· 用三池默认值 8/2/12 与每轮 5 条。
     *
     * <p>保留它与改造前逐字同签名，避免既有直构测试点被迫改动（改动点越多，
     * 越容易把「测的是旧路径」的假绿带进来）。
     */
    public KbIngestWorker(
        KbIngestTaskQueue queue,
        KnowledgeParseProcessor parseProcessor,
        KbSummaryService summaryService,
        KbQuestionGenerationService questionService,
        KbAutoTagService autoTagService,
        KbWikiTaskHandler wikiHandler,
        com.nexusai.kb.app.graph.KbGraphExtractionService graphService,
        com.nexusai.kb.app.image.KbImageEnrichService imageEnrichService,
        com.nexusai.kb.app.retrieval.KbIndexWriter indexWriter,
        com.nexusai.kb.repo.mapper.KbKnowledgeMapper knowledgeMapper,
        com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbKnowledgeStateRepository stateRepository
    ) {
        this(queue, parseProcessor, summaryService, questionService, autoTagService, wikiHandler,
            graphService, imageEnrichService, indexWriter, knowledgeMapper, knowledgeBaseMapper,
            stateRepository,
            new KbIngestPools(
                KbIngestPoolProperties.DEFAULT_PARSE_CONCURRENCY,
                KbIngestPoolProperties.DEFAULT_POSTPROCESS_CONCURRENCY,
                KbIngestPoolProperties.DEFAULT_ENRICHMENT_CONCURRENCY),
            KbIngestPoolProperties.DEFAULT_MAX_TASKS_PER_TICK);
    }

    /** 全参构造（唯一真正赋值的构造器）。 */
    private KbIngestWorker(
        KbIngestTaskQueue queue,
        KnowledgeParseProcessor parseProcessor,
        KbSummaryService summaryService,
        KbQuestionGenerationService questionService,
        KbAutoTagService autoTagService,
        KbWikiTaskHandler wikiHandler,
        com.nexusai.kb.app.graph.KbGraphExtractionService graphService,
        com.nexusai.kb.app.image.KbImageEnrichService imageEnrichService,
        com.nexusai.kb.app.retrieval.KbIndexWriter indexWriter,
        com.nexusai.kb.repo.mapper.KbKnowledgeMapper knowledgeMapper,
        com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbKnowledgeStateRepository stateRepository,
        KbIngestPools pools,
        int maxTasksPerTick
    ) {
        this.queue = queue;
        this.parseProcessor = parseProcessor;
        this.summaryService = summaryService;
        this.questionService = questionService;
        this.autoTagService = autoTagService;
        this.wikiHandler = wikiHandler;
        this.graphService = graphService;
        this.imageEnrichService = imageEnrichService;
        this.indexWriter = indexWriter;
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.stateRepository = stateRepository;
        this.pools = pools;
        this.maxTasksPerTick = Math.max(1, maxTasksPerTick);
        log.info("[入库worker] 并发池已装配 parse={} postprocess={} enrichment={} 每轮最多认领={}"
                + "（与源站 8/2/12 逐字一致；SQLite 锁冲突由 BUSY 回队 + SqliteBusyRetry 兜底，"
                + "不靠降并发，可在 nexusai.knowledge.ingest.* 覆盖）",
            pools.limit(KbIngestPools.Kind.PARSE),
            pools.limit(KbIngestPools.Kind.POSTPROCESS),
            pools.limit(KbIngestPools.Kind.ENRICHMENT),
            this.maxTasksPerTick);
    }

    /**
     * 定时轮询入口 · <b>只做「认领 + 提交」，绝不等待</b>。
     *
     * <p>轮询间隔可配（{@code nexusai.knowledge.ingest.poll-interval-ms}，默认 1500ms）。
     * 单轮最多认领 {@code max-tasks-per-tick} 条（默认 5）—— 这个上限约束的是
     * <b>调度线程一次 tick 的工作量</b>（认领是写库，每条要一次 UPDATE），而不是吞吐上限：
     * 任务一旦提交就并行跑，下个 tick 继续填。
     */
    @Scheduled(fixedDelayString = "${nexusai.knowledge.ingest.poll-interval-ms:1500}")
    public void poll() {
        if (shuttingDown) {
            return;
        }
        try {
            submitUpTo(maxTasksPerTick);
        } catch (RuntimeException e) {
            // 调度线程抛异常会被静默吞掉且后续 tick 可能停止 ⇒ 必须自己兜住并记录
            log.error("[入库worker] 本轮轮询异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 认领并提交至多 {@code maxTasks} 条任务，然后<b>等待在途任务收尾</b>
     * （测试与手动驱动用 · 生产走 {@link #poll()}，不阻塞）。
     *
     * @param maxTasks 上限（&lt;= 0 时按 1 处理；实际提交数还会被各池空位截断）
     * @return 实际提交条数
     */
    public int drainBatch(int maxTasks) {
        int submitted = submitUpTo(maxTasks);
        if ((submitted > 0 || pools.totalInflight() > 0) && !pools.awaitIdle(DRAIN_WAIT_MS)) {
            log.warn("[入库worker] drainBatch 等待在途任务超时（{}ms）在途={} 各池={}",
                DRAIN_WAIT_MS, pools.totalInflight(), pools.describe());
        }
        return submitted;
    }

    /**
     * 按池空位轮流认领并提交（唯一认领入口）。
     *
     * <p><b>为什么按池轮转</b>：如果固定「先把 parse 池填满再看后处理池」，持续有解析任务进来时
     * 后处理/增强任务会被饿死（文档永远卡在 finalizing）。轮转 = 每池每轮最多拿一条，
     * 三类任务共享 {@code maxTasksPerTick} 这个每轮预算。
     *
     * <p><b>背压</b>：每池先 {@link KbIngestPools#tryReserve}，拿不到名额就跳过该池
     * （并节流打「池满」日志）—— 任务留在库里排队，⛔ 不进内存队列。
     *
     * @param maxTasks 本轮最多认领条数
     * @return 实际认领并提交的条数
     */
    private int submitUpTo(int maxTasks) {
        int limit = maxTasks <= 0 ? 1 : maxTasks;
        if (shuttingDown) {
            return 0;
        }
        int claimed = 0;
        boolean progress = true;
        boolean busyAbort = false;
        while (progress && claimed < limit && !shuttingDown && !busyAbort) {
            progress = false;
            for (KbIngestPools.Kind kind : KbIngestPools.Kind.values()) {
                if (claimed >= limit || shuttingDown || busyAbort) {
                    break;
                }
                if (!pools.tryReserve(kind)) {
                    int inflight = pools.inflight(kind);
                    int poolLimit = pools.limit(kind);
                    if (inflight >= poolLimit && pools.shouldLogBackpressure(kind)) {
                        log.info("[入库worker] 池满，暂停认领 pool={} inflight={} limit={} 各池={}",
                            kind.id(), inflight, poolLimit, pools.describe());
                    }
                    continue;
                }
                KbIngestTaskRecord task;
                try {
                    task = queue.claimNextOfTypes(kind.taskTypes());
                } catch (RuntimeException e) {
                    pools.release(kind);
                    if (SqliteBusyRetry.isSqliteBusy(e)) {
                        // 认领是写库：撞锁就本轮收手（下个 tick 再来），⛔ 不要原地重试刷屏
                        busyAbort = true;
                        log.warn("[入库worker] 认领撞 BUSY，本轮停止认领 pool={} 原因={}",
                            kind.id(), e.getMessage());
                        break;
                    }
                    throw e;
                }
                if (task == null) {
                    pools.release(kind);
                    continue;
                }
                progress = true;
                claimed++;
                log.info("[入库worker] 认领 task={} type={} 池={} 在途={} 知识={}",
                    task.getId(), task.getTaskType(), kind.id(),
                    pools.inflight(kind), task.getKnowledgeId());
                dispatch(kind, task);
            }
        }
        return claimed;
    }

    /** 提交到池；提交失败必须还名额 + 回队（⛔ 不能让任务留在 running 没人管）。 */
    private void dispatch(KbIngestPools.Kind kind, KbIngestTaskRecord task) {
        try {
            pools.submit(kind, () -> runTask(kind, task));
        } catch (RuntimeException e) {
            pools.release(kind);
            log.error("[入库worker] 提交执行池失败，任务回队 task={} type={} 池={} 原因={}",
                task.getId(), task.getTaskType(), kind.id(), e.getMessage());
            try {
                SqliteBusyRetry.executeWithBusyRetry(
                    "[入库worker] 提交失败回队 task=" + task.getId(),
                    () -> queue.requeue(task, "提交执行池失败: " + e.getMessage()));
            } catch (RuntimeException requeueFailure) {
                log.error("[入库worker] 提交失败回队也失败，任务保持 running 等回收 task={} 原因={}",
                    task.getId(), requeueFailure.getMessage());
            }
        }
    }

    /** 池内单条任务的执行外壳：开始/结束日志 + 名额归还（幂等路径）。 */
    private void runTask(KbIngestPools.Kind kind, KbIngestTaskRecord task) {
        long startedAt = System.currentTimeMillis();
        String type = task.getTaskType();
        log.info("[入库worker] 任务开始 task={} type={} 池={} knowledge={} 在途={}",
            task.getId(), type, kind.id(), task.getKnowledgeId(), pools.inflight(kind));
        try {
            execute(task);
        } catch (RuntimeException e) {
            // execute 内部已兜住常规异常；这里是最后一道网（例如日志/包装层抛错）
            log.error("[入库worker] 任务执行框架异常 task={} type={} 池={} 原因={}",
                task.getId(), type, kind.id(), e.getMessage(), e);
        } finally {
            long costMs = System.currentTimeMillis() - startedAt;
            pools.release(kind);
            log.info("[入库worker] 任务结束 task={} type={} 池={} knowledge={} 耗时={}ms 剩余在途={}",
                task.getId(), type, kind.id(), task.getKnowledgeId(), costMs, pools.inflight(kind));
        }
    }

    /**
     * 执行单条任务。
     *
     * <p><b>⭐⭐ 锁冲突（BUSY）≠ 任务失败</b>：并发后 {@code SQLITE_BUSY} /
     * {@code SQLITE_BUSY_SNAPSHOT} 是<b>预期内</b>的瞬时现象。三条来源都必须走
     * {@link #handleBusy}（回 {@code queued}、不消耗 {@code retry_count}）：
     * <ol>
     *   <li>本方法体里直接抛出的 BUSY（后处理服务/状态写库）；</li>
     *   <li>{@link KnowledgeParseProcessor#processParse} <b>自己 catch 后返回 FAILED</b>
     *       —— 异常被吞进 {@code kb_knowledge.error_message}，只能回读判别；</li>
     *   <li>后处理服务把 BUSY 包成 {@code KbPostProcessOutcome.failed(详情)}。</li>
     * </ol>
     * 漏掉任何一条，都会把一次并发抖动写成 {@code retry_count+1} —— 3 次就永久 {@code dead}。
     *
     * @param task 已认领的任务
     */
    private void execute(KbIngestTaskRecord task) {
        String type = task.getTaskType();
        boolean busyRequeued = false;
        try {
            if (KbIngestTaskRecord.TYPE_PARSE.equals(type)) {
                KnowledgeParseProcessor.Outcome outcome = parseProcessor.processParse(task);
                if (outcome == KnowledgeParseProcessor.Outcome.FAILED) {
                    if (busyFailureOfKnowledge(task)) {
                        busyRequeued = handleBusy(task, "解析失败，knowledge.error_message 含 SQLITE_BUSY");
                        return;
                    }
                    markFailureWithRetry(task, "解析失败（详见 knowledge.error_message）");
                    return;
                }
                // COMPLETED / SKIPPED 都算任务结束：跳过是幂等结果，重试只会重复跳过
                markDoneWithRetry(task.getId());
                return;
            }

            if (KbIngestTaskRecord.TYPE_NOOP.equals(type)) {
                // 空操作子任务：唯一职责是释放一个 finalizing 槽位。
                settle(task, KbPostProcessOutcome.done("noop"));
                return;
            }

            KbSpan subSpan = beginPostprocessSubSpan(task, type);
            KbPostProcessOutcome outcome;
            try {
                outcome = dispatchPostProcess(task, type);
            } catch (RuntimeException e) {
                // 子任务抛异常也必须把子 span 收掉，否则时间线上会永远转圈
                failSubSpanQuietly(subSpan, e.getMessage(), e);
                throw e;
            }
            if (outcome != null) {
                if (outcome.status() == KbPostProcessOutcome.Status.FAILED
                    && outcome.detail() != null && outcome.detail().contains("SQLITE_BUSY")) {
                    // BUSY 会重新入队再跑一次 ⇒ 本次的子 span 不是失败，标 skipped
                    skipSubSpanQuietly(subSpan, "SQLITE_BUSY 回队重试");
                    busyRequeued = handleBusy(task, "后处理失败，详情含 SQLITE_BUSY: " + outcome.detail());
                    return;
                }
                settle(task, outcome, subSpan);
                return;
            }
            failSubSpanQuietly(subSpan, "未知任务类型: " + type, null);

            markFailureWithRetry(task, "未知任务类型: " + type);
        } catch (RuntimeException e) {
            if (SqliteBusyRetry.isSqliteBusy(e)) {
                busyRequeued = handleBusy(task, e.getMessage());
                return;
            }
            log.error("[入库worker] 任务执行异常 id={} type={} 原因={}",
                task.getId(), type, e.getMessage(), e);
            markFailureWithRetry(task, e.getMessage());
        } finally {
            // 连续 BUSY 计数只在「本次不是 BUSY 回队」时清零（回队后要累计，
            // 否则 MAX_BUSY_REQUEUE_PER_TASK 永远达不到 = 无限回队）
            if (!busyRequeued) {
                busyHits.remove(task.getId());
            }
        }
    }

    /**
     * 回读 {@code kb_knowledge.error_message} 判「解析失败是不是锁冲突造成的」。
     *
     * <p>为什么必须回读：{@link KnowledgeParseProcessor#processParse} 把异常 catch 掉并
     * {@code markFailed(describe(e))}，只返回一个 {@code FAILED} 枚举 —— 异常对象已经没了。
     * 消息里带 {@code SQLITE_BUSY} 是这条链上唯一还活着的证据。
     *
     * @param task 当前任务
     * @return true = 失败原因是 BUSY 类错误（应回队而不是计失败）
     */
    private boolean busyFailureOfKnowledge(KbIngestTaskRecord task) {
        if (knowledgeMapper == null) {
            return false;
        }
        try {
            KbKnowledgeRecord knowledge = knowledgeMapper.selectOneById(task.getKnowledgeId());
            String message = knowledge == null ? null : knowledge.getErrorMessage();
            return message != null && message.contains("SQLITE_BUSY");
        } catch (RuntimeException e) {
            // 连回读都撞 BUSY ⇒ 本身就是可重试错误
            return SqliteBusyRetry.isSqliteBusy(e);
        }
    }

    /**
     * BUSY 处置：回 {@code queued}（不消耗重试次数）并单独计数。
     *
     * @param task   当前任务
     * @param reason 原因（进日志与 {@code error_message}）
     * @return true = 已按 BUSY 处理（调用方不要再走普通失败路径）
     */
    private boolean handleBusy(KbIngestTaskRecord task, String reason) {
        long taskId = task.getId();
        int hits = busyHits.computeIfAbsent(taskId, k -> new AtomicInteger()).incrementAndGet();
        long total = busyRetryCount.incrementAndGet();
        if (hits > MAX_BUSY_REQUEUE_PER_TASK) {
            busyHits.remove(taskId);
            log.error("[入库worker] BUSY 回队超过上限 task={} type={} 连续次数={} 全局busyRetry={}"
                    + " ⇒ 按普通失败处理（会消耗 retry_count）原因={}",
                taskId, task.getTaskType(), hits, total, reason);
            markFailureWithRetry(task, "SQLITE_BUSY 回队超过上限（" + hits + " 次）: " + reason);
            return false;
        }
        boolean requeued;
        try {
            requeued = SqliteBusyRetry.executeWithBusyRetry(
                "[入库worker] BUSY 回队 task=" + taskId,
                () -> queue.requeue(task, "SQLITE_BUSY 回队重试（不消耗重试次数）: " + reason));
        } catch (RuntimeException e) {
            // 连回队都写不进去：任务保持 running，交给 KbIngestHousekeeping 的超期回收
            log.error("[入库worker] BUSY 回队也失败，任务保持 running 等回收 task={} type={} 原因={}",
                taskId, task.getTaskType(), e.getMessage());
            return true;
        }
        log.warn("[入库worker] BUSY 重试 task={} type={} 池={} 第{}次 全局busyRetry={} 已回队={} 原因={}",
            taskId, task.getTaskType(), KbIngestPools.kindOf(task.getTaskType()).id(),
            hits, total, requeued, reason);
        if (!requeued) {
            // 行已不在 running（业务已写完、只有槽位收尾没落）⇒ 兜底补一次递减
            recoverSlotQuietly(task);
        }
        return true;
    }

    /**
     * BUSY 后任务已终结但槽位账没落时的兜底：补一次 {@code finalizeSubtask}。
     *
     * <p>只有 {@code DONE/SKIPPED} 与「最终失败」两条分支会「先写任务终态再改槽位」，
     * 也只有它们可能落到这里（{@code SUPERSEDED} 在 markDone 之后无写操作）。
     * 不补的话文档会永久停在 {@code finalizing}。
     */
    private void recoverSlotQuietly(KbIngestTaskRecord task) {
        if (KbIngestTaskRecord.TYPE_PARSE.equals(task.getTaskType())) {
            return;     // parse 任务不参与 finalizing 槽位账
        }
        try {
            KbKnowledgeStateRepository.FinalizeResult result =
                finalizeWithRetry(task.getKnowledgeId());
            log.warn("[入库worker] 任务已终结但槽位账未落，已补一次递减 task={} type={} knowledge={}"
                    + " 递减={} 提升={}",
                task.getId(), task.getTaskType(), task.getKnowledgeId(),
                result.decremented(), result.promoted());
        } catch (RuntimeException e) {
            log.error("[入库worker] 槽位兜底失败（需人工核查 pending_subtasks_count）task={} knowledge={} 原因={}",
                task.getId(), task.getKnowledgeId(), e.getMessage());
        }
    }

    /** {@code markDone} + BUSY 换新语句重试（单条 UPDATE，重试安全）。 */
    private void markDoneWithRetry(long taskId) {
        SqliteBusyRetry.executeWithBusyRetry("[入库worker] markDone task=" + taskId, () -> {
            queue.markDone(taskId);
            return Boolean.TRUE;
        });
    }

    /** {@code markFailure} + BUSY 重试（条件写 running，重试安全）。 */
    private boolean markFailureWithRetry(KbIngestTaskRecord task, String message) {
        return SqliteBusyRetry.executeWithBusyRetry(
            "[入库worker] markFailure task=" + task.getId(),
            () -> queue.markFailure(task, message));
    }

    /** {@code finalizeSubtask} + BUSY 重试（{@code @Transactional}，每次调用天然新事务）。 */
    private KbKnowledgeStateRepository.FinalizeResult finalizeWithRetry(String knowledgeId) {
        return SqliteBusyRetry.executeWithBusyRetry(
            "[入库worker] finalizeSubtask knowledge=" + knowledgeId,
            () -> stateRepository.finalizeSubtask(knowledgeId));
    }

    /**
     * 按类型分派后处理子任务。
     *
     * @return 执行结果；类型未知 ⇒ null
     */
    private KbPostProcessOutcome dispatchPostProcess(KbIngestTaskRecord task, String type) {
        int attempt = task.getAttempt() == null ? 0 : task.getAttempt();
        String knowledgeId = task.getKnowledgeId();

        switch (type) {
            case KbIngestTaskRecord.TYPE_SUMMARY:
                return summaryService.generate(knowledgeId, attempt);
            case KbIngestTaskRecord.TYPE_QUESTION: {
                JsonNode p = readPayload(task);
                if (p == null) {
                    return KbPostProcessOutcome.skipped("payload 无法解析");
                }
                int batchIndex = p.path("batch_index").asInt(0);
                int count = p.path("question_count").asInt(KbPostProcessConfig.DEFAULT_QUESTION_COUNT);
                List<String> chunkIds = new ArrayList<>();
                for (JsonNode n : p.path("chunk_ids")) {
                    if (n.isTextual()) {
                        chunkIds.add(n.asText());
                    }
                }
                return questionService.generateBatch(knowledgeId, attempt, batchIndex, chunkIds, count);
            }
            case KbIngestTaskRecord.TYPE_AUTO_TAG:
                return autoTagService.classify(knowledgeId, attempt);
            case KbIngestTaskRecord.TYPE_GRAPH: {
                JsonNode p = readPayload(task);
                String model = p == null ? null : p.path("model").asText(null);
                return graphService.extract(knowledgeId, attempt, model).toOutcome();
            }
            case KbIngestTaskRecord.TYPE_WIKI: {
                JsonNode p = readPayload(task);
                String model = p == null ? null : p.path("model").asText(null);
                return wikiHandler.run(knowledgeId, attempt, model);
            }
            case KbIngestTaskRecord.TYPE_IMAGE_UNDERSTAND: {
                // ⭐ 与源侧同构：图片理解失败不阻塞文档完成（正文早已可检索），
                //    但必须留痕 —— 失败计数会出现在返回的 detail 里。
                //
                // ⭐ 模型（用户裁定 2026-11-11）：payload.model 是**知识库设置里的 VLLM 视觉模型**。
                //    空 ⇒ 视觉调用器回落 `settings.multimodalModelName`（默认行为）；
                //    有值 ⇒ 该库专属模型，透传给 `enrichKnowledge` 真正生效。
                JsonNode p = readPayload(task);
                String model = p == null ? null : p.path("model").asText(null);
                // ⭐ V286：图片管线三项（caption 开关 / OCR 开关 / default|smartocr）在派发处
                //    读一次并冻结成快照传下去 —— 不让每张图各读一次库（且入库途中改配置
                //    会导致前几批与后几批走不同管线）。
                com.nexusai.kb.app.image.KbImageSettings imageSettings = resolveImageSettings(knowledgeId);
                int done = imageEnrichService.enrichKnowledge(knowledgeId, model, imageSettings);
                if (done == 0) {
                    return KbPostProcessOutcome.skipped("无可理解的图片（或视觉模型未配置）");
                }
                // ⭐ 图内文字变了 ⇒ 索引文本变了 ⇒ 必须重建索引，否则 OCR 白跑
                //    （文字在库里，但向量/FTS 还是旧的）。失败只 WARN，不判任务失败。
                reindexQuietly(knowledgeId);
                // ⭐ caption / OCR 已写回 ⇒ 图片向量的输入文本才稳定（失败只 WARN，不判任务失败）
                indexImageVectorsQuietly(knowledgeId);
                return KbPostProcessOutcome.done("图片=" + done);
            }
            default:
                return null;
        }
    }

    /**
     * 解析该文档所属知识库的图片管线配置（V286）。
     *
     * <p>文档 → 知识库 → 三列；任何一环缺失（mapper 未注入 / 行不存在 / 读库异常）
     * 一律回落 {@link com.nexusai.kb.app.image.KbImageSettings#DEFAULTS}（全开 + default），
     * 即「与改造前行为一致」，⛔ 不因为一次读库失败就把图片理解静默关掉。
     *
     * @param knowledgeId 文档 ID
     * @return 配置快照（永不 null）
     */
    private com.nexusai.kb.app.image.KbImageSettings resolveImageSettings(String knowledgeId) {
        com.nexusai.kb.app.image.KbImageSettings settings =
            com.nexusai.kb.app.image.KbImageSettings.DEFAULTS;
        try {
            com.nexusai.kb.repo.entity.KbKnowledgeRecord knowledge = knowledgeMapper == null
                ? null : knowledgeMapper.selectOneById(knowledgeId);
            com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord kb =
                knowledge == null || knowledge.getKnowledgeBaseId() == null || knowledgeBaseMapper == null
                    ? null : knowledgeBaseMapper.selectOneById(knowledge.getKnowledgeBaseId());
            settings = com.nexusai.kb.app.image.KbImageSettings.from(kb);
        } catch (RuntimeException e) {
            log.warn("[图片] 读取图片管线配置失败，按默认全开处理 knowledge={} 原因={}",
                knowledgeId, e.getMessage());
        }
        log.info("[图片] 管线配置 knowledge={} caption={} ocr={} pipeline={}",
            knowledgeId, settings.captionEnabled(), settings.ocrEnabled(), settings.pipeline());
        return settings;
    }

    /** 按结果结算（无 span 版本 · 供 noop 等不记 span 的路径使用）。 */
    private void settle(KbIngestTaskRecord task, KbPostProcessOutcome outcome) {
        settle(task, outcome, null);
    }

    /**
     * 按结果结算「任务状态 + 槽位账 + span」。
     *
     * <p>⚠️ 两个动作的先后无所谓，但<b>缺任何一个都会出错</b>：
     * 少了槽位递减 ⇒ 文档卡 finalizing；少了 {@code markDone} ⇒ 任务被反复认领、重复调模型。
     *
     * <p><b>⭐ span 收口规则（派单 R2-A §2.3）</b>：
     * <ul>
     *   <li>{@code DONE} ⇒ {@code endSpan(subSpan, {"detail": …})}</li>
     *   <li>{@code SKIPPED} ⇒ {@code skipSpan(subSpan, detail)}</li>
     *   <li>{@code SUPERSEDED} ⇒ {@code skipSpan(subSpan, "陈旧任务已放弃")}
     *       （tracker 没有单 span 的 cancelled 方法，见报告里的偏离说明）</li>
     *   <li>{@code FAILED} ⇒ {@code failSpan(subSpan, "SUBTASK_FAILED", detail, null)}</li>
     *   <li>槽位账 {@code promoted()} 为真 ⇒ 本轮全部子任务跑完 ⇒
     *       {@code finalizeAttempt(...)} 收口 root（⭐ 这是唯一可靠信号，
     *       ⛔ 不按 planned.size() 或时间猜）。</li>
     * </ul>
     *
     * @param task    当前任务（attempt 与 knowledge 取自这里）
     * @param outcome 子任务结果
     * @param subSpan 对应的子 span（可 null：父 stage 不存在 ⇒ 后续全 no-op）
     */
    private void settle(KbIngestTaskRecord task, KbPostProcessOutcome outcome, KbSpan subSpan) {
        String knowledgeId = task.getKnowledgeId();
        int attempt = task.getAttempt() == null ? 0 : task.getAttempt();
        switch (outcome.status()) {
            case DONE, SKIPPED -> {
                markDoneWithRetry(task.getId());
                if (outcome.status() == KbPostProcessOutcome.Status.DONE) {
                    if (spanTracker != null) {
                        spanTracker.endSpan(subSpan, detailJson(outcome.detail()));
                    }
                } else {
                    skipSubSpanQuietly(subSpan, outcome.detail());
                }
                KbKnowledgeStateRepository.FinalizeResult r = finalizeWithRetry(knowledgeId);
                // ⭐ promoted() 是「本轮全部后台任务跑完」的唯一可靠信号
                if (spanTracker != null && r.promoted()) {
                    spanTracker.finalizeAttempt(knowledgeId, attempt, "done", null);
                }
                log.info("[入库worker] 子任务结束 type={} knowledge={} 结果={} 递减={} 提升={} 说明={}",
                    task.getTaskType(), knowledgeId, outcome.status(),
                    r.decremented(), r.promoted(), outcome.detail());
            }
            case SUPERSEDED -> {
                // ⭐ 不递减：这是上一轮的账，减了会把新一轮的计数打掉
                markDoneWithRetry(task.getId());
                skipSubSpanQuietly(subSpan, "陈旧任务已放弃");
                log.info("[入库worker] 陈旧子任务已放弃（不递减槽位）type={} knowledge={} 说明={}",
                    task.getTaskType(), knowledgeId, outcome.detail());
            }
            case FAILED -> {
                boolean willRetry = markFailureWithRetry(task, outcome.detail());
                if (spanTracker != null) {
                    spanTracker.failSpan(subSpan, "SUBTASK_FAILED", outcome.detail(), null);
                }
                if (!willRetry) {
                    // 最终失败也必须释放槽位 —— 否则文档永远停在 finalizing
                    KbKnowledgeStateRepository.FinalizeResult r = finalizeWithRetry(knowledgeId);
                    if (spanTracker != null && r.promoted()) {
                        spanTracker.finalizeAttempt(knowledgeId, attempt, "failed", null);
                    }
                    log.warn("[入库worker] 子任务最终失败，已释放槽位 type={} knowledge={} 原因={}",
                        task.getTaskType(), knowledgeId, outcome.detail());
                }
            }
        }
    }

    /**
     * 在 postprocess stage 下面开一条子 span（{@code postprocess.<type>}）。
     *
     * <p>⭐ 源站同语义：拿不到 postprocess stage（解析早于 span 功能 / 派发阶段写失败）
     * ⇒ 返回 null，调用方后续全部 no-op。子任务照常干完活，trace 不记而已。
     *
     * @param task 子任务
     * @param type 任务类型
     * @return 子 span；不可记录时 null
     */
    private KbSpan beginPostprocessSubSpan(KbIngestTaskRecord task, String type) {
        if (spanTracker == null) {
            return null;
        }
        int attempt = task.getAttempt() == null ? 0 : task.getAttempt();
        String knowledgeId = task.getKnowledgeId();
        if (attempt <= 0 || knowledgeId == null || knowledgeId.isBlank() || type == null) {
            return null;
        }
        try {
            KbSpan parent = spanTracker.lookupStage(knowledgeId, attempt, "postprocess");
            if (parent == null) {
                return null;
            }
            return spanTracker.beginSubSpan(parent, "postprocess." + type,
                KbSpanKind.SUBSPAN, task.getPayload());
        } catch (RuntimeException e) {
            // trace 是旁路：开子 span 失败绝不影响子任务本身
            log.warn("[入库worker] 开子 span 失败 type={} knowledge={} 原因={}",
                type, knowledgeId, e.getMessage());
            return null;
        }
    }

    private void skipSubSpanQuietly(KbSpan subSpan, String reason) {
        if (spanTracker == null) {
            return;
        }
        try {
            spanTracker.skipSpan(subSpan, reason);
        } catch (RuntimeException e) {
            log.warn("[入库worker] 跳过子 span 失败 span={} 原因={}",
                subSpan == null ? null : subSpan.spanId, e.getMessage());
        }
    }

    private void failSubSpanQuietly(KbSpan subSpan, String message, Throwable detail) {
        if (spanTracker == null) {
            return;
        }
        try {
            spanTracker.failSpan(subSpan, "SUBTASK_FAILED", message, detail);
        } catch (RuntimeException e) {
            log.warn("[入库worker] 失败收口子 span 出错 span={} 原因={}",
                subSpan == null ? null : subSpan.spanId, e.getMessage());
        }
    }

    /** {@code {"detail": "..."}}（手写转义，避免为一行输出引入额外依赖）。 */
    private static String detailJson(String detail) {
        if (detail == null) {
            return null;
        }
        String escaped = detail.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "{\"detail\":\"" + escaped + "\"}";
    }

    /**
     * 图片向量索引 · 把已写回 caption / OCR 的图片编码进 {@code kb_image_embedding}。
     *
     * <p><b>为什么在图片理解之后而不是解析阶段</b>：向量输入是 caption + OCR 文本，
     * 它们只有图片理解跑完才落库 —— 解析阶段调用会把「文件名式 caption」编码进向量空间。
     *
     * <p>失败只 WARN：向量是派生物，下次图片理解/重建索引会补；⛔ 不回滚已落库的 caption。
     * 未配置图片向量模型时服务内部直接返回（{@code configured=false}），零开销。
     */
    private void indexImageVectorsQuietly(String knowledgeId) {
        if (imageVectorService == null || knowledgeMapper == null) {
            return;
        }
        try {
            com.nexusai.kb.repo.entity.KbKnowledgeRecord knowledge =
                knowledgeMapper.selectOneById(knowledgeId);
            String kbId = knowledge == null ? null : knowledge.getKnowledgeBaseId();
            if (kbId == null || kbId.isBlank()) {
                return;
            }
            com.nexusai.kb.app.image.KbImageVectorService.IndexOutcome outcome =
                imageVectorService.indexKnowledge(knowledgeId, kbId, null);
            if (!outcome.configured()) {
                return;
            }
            log.info("[图片向量] knowledge={} 库={} 图片={} 写入={} 跳过={} 失败={} 耗时={}ms",
                knowledgeId, kbId, outcome.images(), outcome.written(),
                outcome.skipped(), outcome.failed(), outcome.elapsedMs());
        } catch (RuntimeException e) {
            log.warn("[图片向量] 索引失败（不影响文档完成）knowledge={} 原因={}",
                knowledgeId, e.toString());
        }
    }

    /**
     * 重建该文档索引（图内文字变化后必须调用）。
     *
     * <p><b>⚠️ 必在事务外</b>：{@code KbIndexWriter} 的 FTS 写入走独立 JDBC 连接，
     * 外层事务会自锁 SQLITE_BUSY。本方法在 worker 的 settle 之外调用，天然无事务。
     *
     * <p><b>失败只 WARN</b>：图内文字已落库，索引是派生物 —— 失败不该把图片理解判失败
     * （下次编辑/重建索引会补上）。但要留痕，⛔ 不静默。
     */
    private void reindexQuietly(String knowledgeId) {
        if (indexWriter == null || knowledgeMapper == null) {
            return;
        }
        try {
            com.nexusai.kb.repo.entity.KbKnowledgeRecord knowledge =
                knowledgeMapper.selectOneById(knowledgeId);
            if (knowledge == null) {
                return;
            }
            com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord kb = knowledgeBaseMapper == null
                ? null : knowledgeBaseMapper.selectOneById(knowledge.getKnowledgeBaseId());
            String modelId = knowledge.getEmbeddingModelId();
            if (modelId == null || modelId.isBlank()) {
                modelId = kb == null ? null : kb.getEmbeddingModelId();
            }
            indexWriter.reindexKnowledge(knowledgeId, knowledge.getKnowledgeBaseId(), modelId);
            log.info("[图片] 图内文字已进索引 knowledge={} 模型={}", knowledgeId, modelId);
        } catch (RuntimeException e) {
            log.warn("[图片] 重建索引失败（图内文字已落库，可稍后编辑触发重索引）knowledge={} 原因={}",
                knowledgeId, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 优雅停机 + 观测（测试/排查用）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 优雅停机：置停机位（不再认领）→ 等在途任务收尾。
     *
     * <p>⛔ <b>不取消在途任务</b>：一条解析任务做到一半被中断，会留下「分块写了一半」
     * 或「图片已落盘但分块未写」的中间态。等它跑完再停，代价是停机最多多等
     * {@link #SHUTDOWN_WAIT_MS}；超时后打 warn 并中断剩余线程（不无限等）。
     */
    @Override
    public void destroy() {
        shuttingDown = true;
        log.info("[入库worker] 开始停机：停止认领，等待在途任务收尾 在途={} 各池={}",
            pools.totalInflight(), pools.describe());
        boolean drained = pools.shutdownAndAwait(SHUTDOWN_WAIT_MS);
        if (drained) {
            log.info("[入库worker] 停机完成：在途任务已全部收尾 各池={} 累计busyRetry={}",
                pools.describe(), busyRetryCount.get());
        } else {
            log.warn("[入库worker] 停机超时（上限 {}ms）：仍有 {} 个任务未收尾，已中断剩余线程 各池={}",
                SHUTDOWN_WAIT_MS, pools.totalInflight(), pools.describe());
        }
    }

    /** 是否已进入停机流程。 */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /** 三池在途总和。 */
    public int inflightTotal() {
        return pools.totalInflight();
    }

    /** 某池当前在途数。 */
    public int inflightOf(KbIngestPools.Kind kind) {
        return pools.inflight(kind);
    }

    /** 某池容量。 */
    public int poolLimit(KbIngestPools.Kind kind) {
        return pools.limit(kind);
    }

    /** 本次进程内某池同时达到过的最大在途数（并发度实测值）。 */
    public int maxObservedInflight(KbIngestPools.Kind kind) {
        return pools.maxObservedInflight(kind);
    }

    /** BUSY 回队总次数（独立于 {@code retry_count}）。 */
    public long busyRetryTotal() {
        return busyRetryCount.get();
    }

    private static JsonNode readPayload(KbIngestTaskRecord task) {
        String raw = task.getPayload();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            log.warn("[入库worker] 任务 payload 解析失败 id={} 原因={}", task.getId(), e.getMessage());
            return null;
        }
    }
}
