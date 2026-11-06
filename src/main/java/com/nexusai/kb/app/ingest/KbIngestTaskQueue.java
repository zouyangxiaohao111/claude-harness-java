package com.nexusai.kb.app.ingest;

import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.mapper.KbIngestTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 入库任务队列 · SQLite 表承接源侧 Redis + asynq 的队列语义。
 *
 * <p><b>认领（claim）为什么是「带条件 UPDATE」而不是「先查后改」</b>：
 * 两个 worker 同时看到同一条 {@code queued}，若各自先 SELECT 再 UPDATE，
 * 两边都会认为自己拿到了任务 ⇒ 同一条任务被执行两次（重复写分块、重复递减槽位）。
 * 把 {@code state = 'queued'} 写进 UPDATE 的 WHERE，数据库保证只有一个调用影响行数为 1。
 *
 * <p><b>重试语义</b>：失败且未超上限 → 回到 {@code queued}（保留原 id，下轮重跑）；
 * 超上限 → {@code dead}（永久失败，不再自动重试）。
 */
@Component
public class KbIngestTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(KbIngestTaskQueue.class);

    /** 单次认领时最多探查多少条候选（并发下前几条可能已被别人抢走）。 */
    private static final int CLAIM_CANDIDATE_WINDOW = 8;

    private final KbIngestTaskMapper mapper;

    public KbIngestTaskQueue(KbIngestTaskMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 入队。
     *
     * @param knowledgeId 知识条目 ID
     * @param taskType    任务类型（{@link KbIngestTaskRecord#TYPE_PARSE} 等）
     * @param attempt     与 {@code kb_knowledge.parse_attempt} 对应
     * @param payload     任务参数（JSON 文本，可空）
     * @return 新任务 ID
     */
    public long enqueue(String knowledgeId, String taskType, int attempt, String payload) {
        KbIngestTaskRecord row = new KbIngestTaskRecord();
        row.setKnowledgeId(knowledgeId);
        row.setTaskType(taskType);
        row.setState(KbIngestTaskRecord.STATE_QUEUED);
        row.setAttempt(attempt);
        row.setRetryCount(0);
        row.setMaxRetry(3);
        row.setPayload(payload);
        row.setCreatedAt(KbTimeFormat.now());
        mapper.insert(row);
        log.info("[入库队列] 已入队 id={} knowledge={} type={} attempt={}",
            row.getId(), knowledgeId, taskType, attempt);
        return row.getId() == null ? -1L : row.getId();
    }

    /**
     * 认领一条待办任务（原子）。
     *
     * @return 已置为 {@code running} 的任务；无待办时 null
     */
    public KbIngestTaskRecord claimNext() {
        List<KbIngestTaskRecord> candidates = mapper.selectListByQuery(
            QueryWrapper.create()
                .where("state = ?", KbIngestTaskRecord.STATE_QUEUED)
                .orderBy("created_at", true)
                .orderBy("id", true)
                .limit(CLAIM_CANDIDATE_WINDOW));
        String now = KbTimeFormat.now();
        for (KbIngestTaskRecord candidate : candidates) {
            boolean claimed = UpdateChain.of(mapper)
                .set(new QueryColumn("state"), KbIngestTaskRecord.STATE_RUNNING)
                .set(new QueryColumn("started_at"), now)
                .where("id = ?", candidate.getId())
                .and("state = ?", KbIngestTaskRecord.STATE_QUEUED)
                .update();
            if (claimed) {
                candidate.setState(KbIngestTaskRecord.STATE_RUNNING);
                candidate.setStartedAt(now);
                return candidate;
            }
            // 被别的 worker 抢走了，试下一条
        }
        return null;
    }

    /**
     * 标记成功。
     *
     * @param taskId 任务 ID
     */
    public void markDone(long taskId) {
        UpdateChain.of(mapper)
            .set(new QueryColumn("state"), KbIngestTaskRecord.STATE_DONE)
            .set(new QueryColumn("finished_at"), KbTimeFormat.now())
            .set(new QueryColumn("error_message"), "")
            .where("id = ?", taskId)
            .and("state = ?", KbIngestTaskRecord.STATE_RUNNING)
            .update();
    }

    /**
     * 标记失败：未超上限则回 {@code queued}（下轮重试），超上限则 {@code dead}。
     *
     * <p>只对 {@code running} 生效 —— 防止把已被别处改写的行拽回来。
     *
     * @param task    当前任务（用其 retryCount / maxRetry）
     * @param message 失败原因
     * @return true = 还会重试；false = 永久失败
     */
    public boolean markFailure(KbIngestTaskRecord task, String message) {
        int used = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        int max = task.getMaxRetry() == null ? 0 : task.getMaxRetry();
        boolean willRetry = used < max;
        String msg = message == null ? "" : message;
        if (msg.length() > 2000) {
            msg = msg.substring(0, 2000);
        }

        UpdateChain<KbIngestTaskRecord> chain = UpdateChain.of(mapper)
            .set(new QueryColumn("retry_count"), used)
            .set(new QueryColumn("error_message"), msg)
            .where("id = ?", task.getId())
            .and("state = ?", KbIngestTaskRecord.STATE_RUNNING);
        if (willRetry) {
            chain.set(new QueryColumn("state"), KbIngestTaskRecord.STATE_QUEUED);
        } else {
            chain.set(new QueryColumn("state"), KbIngestTaskRecord.STATE_DEAD)
                .set(new QueryColumn("finished_at"), KbTimeFormat.now());
        }
        chain.update();

        if (willRetry) {
            log.warn("[入库队列] 任务失败，将重试 id={} 已试={}/{} 原因={}",
                task.getId(), used, max, msg);
        } else {
            log.error("[入库队列] 任务永久失败 id={} 已试={}/{} 原因={}",
                task.getId(), used, max, msg);
        }
        return willRetry;
    }

    /**
     * 作废某知识条目的全部待办任务（重新解析 / 删除时用）。
     *
     * <p>只动 {@code queued}：{@code running} 的任务由 attempt 比对自行放弃。
     *
     * @param knowledgeId 知识条目 ID
     * @return true = 至少作废了一条
     */
    public boolean cancelQueued(String knowledgeId) {
        return UpdateChain.of(mapper)
            .set(new QueryColumn("state"), KbIngestTaskRecord.STATE_DEAD)
            .set(new QueryColumn("error_message"), "已被新一轮解析取代")
            .set(new QueryColumn("finished_at"), KbTimeFormat.now())
            .where("knowledge_id = ?", knowledgeId)
            .and("state = ?", KbIngestTaskRecord.STATE_QUEUED)
            .update();
    }

    /**
     * 仅测试/诊断用：统计某状态的任务数。
     *
     * @param state 状态
     * @return 条数
     */
    public long countByState(String state) {
        return mapper.selectCountByQuery(
            QueryWrapper.create().where("state = ?", state));
    }
}
