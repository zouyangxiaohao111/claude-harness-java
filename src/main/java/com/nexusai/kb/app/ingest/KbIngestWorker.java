package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库任务 worker · 定时轮询领取并执行任务（承接源侧 Redis asynq 的消费端）。
 *
 * <p><b>为什么是轮询而不是事件驱动</b>：本仓是单机 SQLite，没有 Redis/Broker；
 * 任务来源只有本进程写入的行。轮询间隔 1.5s 与现有 {@code SwarmPermissionPoller}
 * 同量级，对单机知识入库足够。
 *
 * <p><b>⛔ 不要并发跑同一个 worker 实例</b>：{@link #drainBatch(int)} 内部是串行执行的。
 * 需要并发时由调度线程池多实例化，靠 {@link KbIngestTaskQueue#claimNext()} 的原子认领去重。
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
public class KbIngestWorker {

    private static final Logger log = LoggerFactory.getLogger(KbIngestWorker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 轮询间隔（毫秒）。 */
    private static final long POLL_INTERVAL_MS = 1500L;

    /** 单轮最多处理多少条（避免一次 tick 跑太久）。 */
    private static final int MAX_TASKS_PER_TICK = 5;

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
    }

    /** 定时轮询入口。 */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    public void poll() {
        try {
            drainBatch(MAX_TASKS_PER_TICK);
        } catch (RuntimeException e) {
            // 调度线程抛异常会被静默吞掉且后续 tick 可能停止 ⇒ 必须自己兜住并记录
            log.error("[入库worker] 本轮轮询异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 处理至多 {@code maxTasks} 条任务（测试与手动驱动用）。
     *
     * @param maxTasks 上限（&lt;= 0 时按 1 处理）
     * @return 实际处理条数
     */
    public int drainBatch(int maxTasks) {
        int limit = maxTasks <= 0 ? 1 : maxTasks;
        int processed = 0;
        for (int i = 0; i < limit; i++) {
            KbIngestTaskRecord task = queue.claimNext();
            if (task == null) {
                break;
            }
            processed++;
            execute(task);
        }
        return processed;
    }

    /**
     * 执行单条任务。
     *
     * @param task 已认领的任务
     */
    private void execute(KbIngestTaskRecord task) {
        String type = task.getTaskType();
        try {
            if (KbIngestTaskRecord.TYPE_PARSE.equals(type)) {
                KnowledgeParseProcessor.Outcome outcome = parseProcessor.processParse(task);
                if (outcome == KnowledgeParseProcessor.Outcome.FAILED) {
                    queue.markFailure(task, "解析失败（详见 knowledge.error_message）");
                    return;
                }
                // COMPLETED / SKIPPED 都算任务结束：跳过是幂等结果，重试只会重复跳过
                queue.markDone(task.getId());
                return;
            }

            if (KbIngestTaskRecord.TYPE_NOOP.equals(type)) {
                // 空操作子任务：唯一职责是释放一个 finalizing 槽位。
                settle(task, KbPostProcessOutcome.done("noop"));
                return;
            }

            KbPostProcessOutcome outcome = dispatchPostProcess(task, type);
            if (outcome != null) {
                settle(task, outcome);
                return;
            }

            queue.markFailure(task, "未知任务类型: " + type);
        } catch (RuntimeException e) {
            log.error("[入库worker] 任务执行异常 id={} type={} 原因={}",
                task.getId(), type, e.getMessage(), e);
            queue.markFailure(task, e.getMessage());
        }
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
                int done = imageEnrichService.enrichKnowledge(knowledgeId);
                if (done == 0) {
                    return KbPostProcessOutcome.skipped("无可理解的图片（或视觉模型未配置）");
                }
                // ⭐ 图内文字变了 ⇒ 索引文本变了 ⇒ 必须重建索引，否则 OCR 白跑
                //    （文字在库里，但向量/FTS 还是旧的）。失败只 WARN，不判任务失败。
                reindexQuietly(knowledgeId);
                return KbPostProcessOutcome.done("图片=" + done);
            }
            default:
                return null;
        }
    }

    /**
     * 按结果结算「任务状态 + 槽位账」。
     *
     * <p>⚠️ 两个动作的先后无所谓，但<b>缺任何一个都会出错</b>：
     * 少了槽位递减 ⇒ 文档卡 finalizing；少了 {@code markDone} ⇒ 任务被反复认领、重复调模型。
     */
    private void settle(KbIngestTaskRecord task, KbPostProcessOutcome outcome) {
        String knowledgeId = task.getKnowledgeId();
        switch (outcome.status()) {
            case DONE, SKIPPED -> {
                queue.markDone(task.getId());
                KbKnowledgeStateRepository.FinalizeResult r = stateRepository.finalizeSubtask(knowledgeId);
                log.info("[入库worker] 子任务结束 type={} knowledge={} 结果={} 递减={} 提升={} 说明={}",
                    task.getTaskType(), knowledgeId, outcome.status(),
                    r.decremented(), r.promoted(), outcome.detail());
            }
            case SUPERSEDED -> {
                // ⭐ 不递减：这是上一轮的账，减了会把新一轮的计数打掉
                queue.markDone(task.getId());
                log.info("[入库worker] 陈旧子任务已放弃（不递减槽位）type={} knowledge={} 说明={}",
                    task.getTaskType(), knowledgeId, outcome.detail());
            }
            case FAILED -> {
                boolean willRetry = queue.markFailure(task, outcome.detail());
                if (!willRetry) {
                    // 最终失败也必须释放槽位 —— 否则文档永远停在 finalizing
                    stateRepository.finalizeSubtask(knowledgeId);
                    log.warn("[入库worker] 子任务最终失败，已释放槽位 type={} knowledge={} 原因={}",
                        task.getTaskType(), knowledgeId, outcome.detail());
                }
            }
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
