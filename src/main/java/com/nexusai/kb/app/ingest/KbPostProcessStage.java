package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.KbKnowledgeStateRepository;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后处理 fan-out · 对齐 WeKnora {@code knowledge_post_process.go:200-330}。
 *
 * <p><b>它在链路中的位置</b>：解析 → 分块 → 建索引 → 置 enabled 之后，
 * 如果知识库开了增强能力，就把该文档的一批子任务入队，并进入 {@code finalizing}
 * （计数 = 子任务数）。每个子任务终态时递减，归零才提升为 {@code completed}。
 *
 * <p><b>⭐⭐ 三条「算错就静默出错」的铁律</b>：
 * <ol>
 *   <li><b>先算准再派发，且计数必须等于实际派发数</b>。
 *       算多 ⇒ 计数永远到不了 0 ⇒ 文档永久显示「处理中」；
 *       算少 ⇒ 提前「已完成」而增强还在跑。
 *       本类的做法：先组装完整的派发清单，再 {@code setFinalizing(list.size())}，
 *       然后按同一份清单入队 —— <b>只有一个真源</b>，不可能对不上。</li>
 *   <li><b>自动打标不占槽位</b>（源侧明确注释）。它失败不该把文档卡在 finalizing；
 *       代价是「文档显示已完成时标签可能还没写完」—— 这是故意的取舍。</li>
 *   <li><b>setFinalizing 失败 ⇒ 一个任务都不许入队</b>。失败意味着行已不在
 *       {@code processing}（用户取消/删除）。此时入队的任务会跑一轮、
 *       再因为状态不对而自我跳过，纯属浪费（模型调用要花钱）。</li>
 * </ol>
 */
@Component
public class KbPostProcessStage {

    private static final Logger log = LoggerFactory.getLogger(KbPostProcessStage.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 问题生成每批的块数（源侧 {@code knowledge_post_process.go questionGenChunkBatchSize = 20}）。
     *
     * <p>⚠️ 分批的意义：一篇 500 块的长文档若一块一个任务，会产生 500 次 LLM 调用
     * 和 500 个槽位 —— 计数、重试、取消全都被放大 500 倍，且大部分块内容极短、
     * 问句质量差。按 20 块一批是源侧调过的平衡点。
     */
    static final int QUESTION_BATCH_SIZE = 20;

    private final KbChunkMapper chunkMapper;
    private final KbKnowledgeStateRepository stateRepository;
    private final KbIngestTaskQueue queue;

    public KbPostProcessStage(
        KbChunkMapper chunkMapper,
        KbKnowledgeStateRepository stateRepository,
        KbIngestTaskQueue queue
    ) {
        this.chunkMapper = chunkMapper;
        this.stateRepository = stateRepository;
        this.queue = queue;
    }

    /**
     * 一道待派发的子任务。
     *
     * @param taskType 任务类型（{@link KbIngestTaskRecord#TYPE_SUMMARY} 等）
     * @param countsSlot 是否占 finalizing 槽位
     * @param payload  任务参数（JSON 文本）
     */
    record Planned(String taskType, boolean countsSlot, String payload) {
    }

    /**
     * 解析完成后调用：按配置决定是否进入 finalizing 并派发子任务。
     *
     * @param knowledge 知识条目（调用方已确认状态/attempt）
     * @param kb        所属知识库（可 null）
     * @param attempt   本轮解析 attempt
     */
    public void fanOut(KbKnowledgeRecord knowledge, KbKnowledgeBaseRecord kb, int attempt) {
        String knowledgeId = knowledge.getId();
        KbPostProcessConfig config = KbPostProcessConfig.resolve(kb);

        List<Planned> planned = plan(knowledge, config, textChunksOf(knowledgeId));

        if (planned.isEmpty()) {
            // 没有任何增强能力 ⇒ 直接完成（源侧 CompleteProcessingWithoutSubtasks）
            stateRepository.completeWithoutSubtasks(knowledgeId);
            log.info("[后处理] 无子任务，直接完成 knowledge={}", knowledgeId);
            return;
        }

        long counted = planned.stream().filter(Planned::countsSlot).count();
        if (counted > 0) {
            // ⚠️ 必须先播种计数再派发：反过来的话任务可能跑得比播种快，
            //    递减打在 0 上 ⇒ 计数被 clamp，最后永远到不了 0。
            if (!stateRepository.setFinalizing(knowledgeId, (int) counted)) {
                log.info("[后处理] 未能进入 finalizing（状态已变），不派发 knowledge={} 计划={}",
                    knowledgeId, planned.size());
                return;
            }
        }

        int enqueued = 0;
        for (Planned p : planned) {
            try {
                queue.enqueue(knowledgeId, p.taskType(), attempt, p.payload());
                enqueued++;
            } catch (RuntimeException e) {
                log.error("[后处理] 子任务入队失败 knowledge={} type={} 原因={}",
                    knowledgeId, p.taskType(), e.getMessage());
                // ⭐ 入队失败必须释放它占的槽位，否则计数永远差 1 ⇒ 文档卡在 finalizing
                if (p.countsSlot()) {
                    stateRepository.finalizeSubtask(knowledgeId);
                }
            }
        }
        log.info("[后处理] 已派发 knowledge={} 计划={} 占槽={} 实际入队={}",
            knowledgeId, planned.size(), counted, enqueued);
    }

    /**
     * 组装派发清单（纯逻辑，便于单测不用 mock 数据库）。
     *
     * <p>⚠️ 分块列表是<b>入参</b>而不是在方法里查库 —— 这样这个「算账」逻辑是纯函数，
     * 单测能直接断言各种配置下的派发清单，不用假装一个 BaseMapper。
     *
     * @param knowledge  知识条目（当前只用其 id，保留以备后续按文档定制）
     * @param config     配置
     * @param textChunks 该文档的文本块（按 chunkIndex 升序）
     * @return 清单（顺序 = 摘要 → 问题批次 → Wiki → 打标）
     */
    List<Planned> plan(KbKnowledgeRecord knowledge, KbPostProcessConfig config,
                       List<KbChunkRecord> textChunks) {
        List<Planned> out = new ArrayList<>();
        if (textChunks == null || textChunks.isEmpty()) {
            return out;
        }

        // ① 摘要：1 个槽位
        if (config.summaryEnabled()) {
            out.add(new Planned(KbIngestTaskRecord.TYPE_SUMMARY, true, "{}"));
        }

        // ② 问题生成：按批（每批 QUESTION_BATCH_SIZE 个可抽取文本的块）
        if (config.question().runnable()) {
            List<String> candidates = new ArrayList<>();
            for (KbChunkRecord c : textChunks) {
                if (KbChunkText.hasExtractableText(c.getContent())) {
                    candidates.add(c.getId());
                }
            }
            int size = QUESTION_BATCH_SIZE;
            int count = config.question().questionCount();
            for (int start = 0, batch = 0; start < candidates.size(); start += size, batch++) {
                List<String> slice = candidates.subList(start, Math.min(start + size, candidates.size()));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("batch_index", batch);
                payload.put("chunk_ids", slice);
                payload.put("question_count", count);
                out.add(new Planned(KbIngestTaskRecord.TYPE_QUESTION, true, toJson(payload)));
            }
        }

        // ③ Wiki：1 个槽位（知识库级批量任务，但每次上传只入队一个 op）
        if (config.wikiEnabled()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", config.wikiModelId());
            out.add(new Planned(KbIngestTaskRecord.TYPE_WIKI, true, toJson(payload)));
        }

        // ③.5 图谱抽取：1 个槽位（每篇文档一轮，⛔ 不按块拆 —— 图谱是文档内的关系图）
        if (config.graphEnabled()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", config.summaryModelId());
            out.add(new Planned(KbIngestTaskRecord.TYPE_GRAPH, true, toJson(payload)));
        }

        // ④ 自动打标：不占槽位（失败不能把文档卡在 finalizing）
        if (config.autoTag().runnable()) {
            out.add(new Planned(KbIngestTaskRecord.TYPE_AUTO_TAG, false, "{}"));
        }
        return out;
    }

    /** 取该文档的全部文本块（按 chunkIndex 升序）。 */
    private List<KbChunkRecord> textChunksOf(String knowledgeId) {
        return chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", knowledgeId)
                .and("chunk_type = ?", "text")
                .and("is_enabled = ?", 1)
                .orderBy("chunk_index", true));
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            // 序列化自己构造的 Map 不会失败；真失败了也不该静默变成 null payload
            throw new IllegalStateException("后处理任务参数序列化失败: " + e.getMessage(), e);
        }
    }
}
