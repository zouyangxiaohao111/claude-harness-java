package com.nexusai.kb.app.ingest;

import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbIngestTaskRecord;
import com.nexusai.kb.repo.mapper.KbIngestTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
 *
 * <p><b>重试为什么要退避（V288）</b>：源站 asynq 的 {@code RetryDelayFunc} 是<b>指数退避</b>，
 * 失败任务不会立刻再投递。本仓原先把 {@code state} 直接改回 {@code queued} ⇒ worker 1.5s
 * 轮询下一轮就把它捞起来，3 次重试可以在 3 秒内全部打完，对 429 / 上游超时 / 5xx
 * 这类「等一会儿才会好」的失败等于没重试。现在 {@code markFailure} 写
 * {@code available_at = now + min(base * 2^(n-1), max)}（默认 base=2s / max=300s，可配
 * {@code nexusai.knowledge.ingest.retry-base-ms} / {@code retry-max-ms}），认领 SQL 只捞
 * {@code available_at IS NULL OR available_at <= now} 的行。
 * ⛔ {@link #requeue}（SQLITE_BUSY 瞬时锁冲突）<b>不</b>退避 —— 那种失败等一等只会更糟。
 */
@Component
public class KbIngestTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(KbIngestTaskQueue.class);

    /** 单次认领时最多探查多少条候选（并发下前几条可能已被别人抢走）。 */
    private static final int CLAIM_CANDIDATE_WINDOW = 8;

    /** 重试退避基数默认值（毫秒）· 源站 asynq 默认首轮延迟同样是秒级。 */
    public static final long DEFAULT_RETRY_BASE_MS = 2000L;

    /** 重试退避上限默认值（毫秒）· 5 分钟：超过这个时长用户已经在等结果了。 */
    public static final long DEFAULT_RETRY_MAX_MS = 300_000L;

    private final KbIngestTaskMapper mapper;

    /**
     * 重试退避基数（毫秒）。{@code @Value} 的默认值与 {@link #DEFAULT_RETRY_BASE_MS} 同值，
     * 且 yml 缺键时也能起（老部署的外部配置文件不含这两个键）。
     */
    @Value("${nexusai.knowledge.ingest.retry-base-ms:2000}")
    private long retryBaseMs = DEFAULT_RETRY_BASE_MS;

    /** 重试退避上限（毫秒）· 见 {@link #DEFAULT_RETRY_MAX_MS}。 */
    @Value("${nexusai.knowledge.ingest.retry-max-ms:300000}")
    private long retryMaxMs = DEFAULT_RETRY_MAX_MS;

    public KbIngestTaskQueue(KbIngestTaskMapper mapper) {
        this.mapper = mapper;
    }

    /** 测试/诊断用：改退避基数（Spring 环境下由 {@code @Value} 注入）。 */
    void setRetryBaseMs(long retryBaseMs) {
        this.retryBaseMs = retryBaseMs;
    }

    /** 测试/诊断用：改退避上限（Spring 环境下由 {@code @Value} 注入）。 */
    void setRetryMaxMs(long retryMaxMs) {
        this.retryMaxMs = retryMaxMs;
    }

    /**
     * 第 {@code failedAttempt} 次失败后的退避时长：{@code min(base * 2^(n-1), max)}。
     *
     * <p>n=1 ⇒ base；n=2 ⇒ 2*base；n=3 ⇒ 4*base …… 到 max 后封顶。
     * 左移/乘法都做了越界保护（n 很大时直接返回 max，不会溢出成负数）。
     *
     * @param failedAttempt 这是第几次失败（1 起）
     * @return 退避毫秒数（>= 0）
     */
    long retryDelayMs(int failedAttempt) {
        long base = Math.max(0L, retryBaseMs);
        long max = Math.max(base, Math.max(0L, retryMaxMs));
        int shift = Math.max(0, failedAttempt - 1);
        // 2^40 已经远超任何合理 max ⇒ 直接封顶，避免移位溢出
        if (shift >= 40) {
            return max;
        }
        long delay = base << shift;
        if (delay < 0) {
            return max;
        }
        return Math.min(delay, max);
    }

    /** 退避时长的可读形态（日志用）：整秒不带小数，非整秒保留 1 位。 */
    static String formatSeconds(long delayMs) {
        if (delayMs % 1000L == 0L) {
            return String.valueOf(delayMs / 1000L);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", delayMs / 1000.0);
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
        String now = KbTimeFormat.now();
        row.setCreatedAt(now);
        // 新任务立即可认领（与旧行 NULL 语义等价，显式写 now 便于排查）
        row.setAvailableAt(now);
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
        String now = KbTimeFormat.now();
        List<KbIngestTaskRecord> candidates = mapper.selectListByQuery(
            QueryWrapper.create()
                .where("state = ?", KbIngestTaskRecord.STATE_QUEUED)
                .and("(available_at IS NULL OR available_at <= ?)", now)
                .orderBy("created_at", true)
                .orderBy("id", true)
                .limit(CLAIM_CANDIDATE_WINDOW));
        return claimOne(candidates, now);
    }

    /**
     * 认领一条<b>指定类型白名单</b>内的待办任务（原子）。
     *
     * <h2>⭐⭐ 为什么认领要带类型，而不是「认领完再挑池」</h2>
     * 并发池按池有界背压：若无条件 {@code claimNext()} 拿到一条 parse 任务、却发现 parse 池已满，
     * 只剩两条烂路 —— ①占着名额阻塞（调度线程被占住）；②写回 {@code queued}
     * （多一次写 = 多一次 SQLITE_BUSY 机会，且刷新 {@code started_at}）。
     * 把类型白名单下沉到<b>认领 SQL 的 WHERE</b>，就只认领「有池位可跑」的那类任务，代价为零。
     *
     * <p>原子性与 {@link #claimNext()} 逐字同源：仍是「带 {@code state = 'queued'} 条件的
     * UPDATE + 影响行数」判胜负，⛔ 不要改成先查后改。
     *
     * @param taskTypes 允许认领的任务类型（空 ⇒ 直接返回 null，不发 SQL）
     * @return 已置为 {@code running} 的任务；无待办时 null
     */
    public KbIngestTaskRecord claimNextOfTypes(java.util.Collection<String> taskTypes) {
        if (taskTypes == null || taskTypes.isEmpty()) {
            return null;
        }
        List<String> types = new java.util.ArrayList<>(taskTypes);
        // 占位符个数由<b>本进程内固定白名单</b>决定（不是外部输入），值仍走参数绑定
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                in.append(',');
            }
            in.append('?');
        }
        Object[] args = new Object[types.size() + 2];
        args[0] = KbIngestTaskRecord.STATE_QUEUED;
        for (int i = 0; i < types.size(); i++) {
            args[i + 1] = types.get(i);
        }
        String now = KbTimeFormat.now();
        args[types.size() + 1] = now;
        List<KbIngestTaskRecord> candidates = mapper.selectListByQuery(
            QueryWrapper.create()
                .where("state = ? AND task_type IN (" + in + ")"
                    + " AND (available_at IS NULL OR available_at <= ?)", args)
                .orderBy("created_at", true)
                .orderBy("id", true)
                .limit(CLAIM_CANDIDATE_WINDOW));
        return claimOne(candidates, now);
    }

    /** 在候选窗口里用条件 UPDATE 抢占一条（{@link #claimNext()} 与类型认领共用）。 */
    private KbIngestTaskRecord claimOne(List<KbIngestTaskRecord> candidates, String now) {
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
     * 把一条已认领的任务<b>原样放回</b> {@code queued}（不消耗重试次数）。
     *
     * <h2>为什么必须与 {@link #markFailure} 分开</h2>
     * 这里的失败原因不是「任务本身做不成」，而是<b>数据库忙</b>（{@code SQLITE_BUSY} /
     * {@code SQLITE_BUSY_SNAPSHOT}）或执行池拒绝提交 —— 都与任务语义无关。
     * 走 {@link #markFailure} 会让 {@code retry_count} 被锁冲突吃掉，3 次并发抖动就能把一条
     * 完全正常的解析任务判成 permanent {@code dead}（用户看到「处理失败」，重传也救不回来）。
     *
     * <p>因此：<b>{@code retry_count} 一个字节都不动</b>；{@code error_message} 只写本次原因
     * （第一次成功写结果时会被 {@link #markDone} 清空）。BUSY 的重试次数由 worker 侧
     * 独立计数并单独打日志（{@code busyRetry}）。
     *
     * <p>仍带 {@code AND state = 'running'} 条件：如果这行已被别人改成 done/dead
     * （失败发生在「业务已完成之后」的收尾写入里），这里返回 false —— 调用方必须据此
     * 走槽位兜底，⛔ 不要把已终结的任务拽回来重跑。
     *
     * @param task   当前任务
     * @param reason 回队原因（截断到 2000 字符）
     * @return true = 真的回到了 queued；false = 行已不在 running
     */
    public boolean requeue(KbIngestTaskRecord task, String reason) {
        String msg = reason == null ? "" : reason;
        if (msg.length() > 2000) {
            msg = msg.substring(0, 2000);
        }
        boolean requeued = UpdateChain.of(mapper)
            .set(new QueryColumn("state"), KbIngestTaskRecord.STATE_QUEUED)
            .set(new QueryColumn("error_message"), msg)
            // ⭐ BUSY 是瞬时锁冲突：写 now ⇒ 立即可再认领，⛔ 不走 markFailure 的指数退避
            .set(new QueryColumn("available_at"), KbTimeFormat.now())
            .where("id = ?", task.getId())
            .and("state = ?", KbIngestTaskRecord.STATE_RUNNING)
            .update();
        if (!requeued) {
            log.warn("[入库队列] 任务已不在 running，未回队（可能已在收尾写入中终结）id={} 原因={}",
                task.getId(), msg);
        }
        return requeued;
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
     * 标记失败：未超上限则回 {@code queued} 并<b>按指数退避延后</b>可认领时刻
     * （{@code available_at = now + min(base * 2^(n-1), max)}），超上限则 {@code dead}。
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
            long delayMs = retryDelayMs(used);
            String availableAt = KbTimeFormat.format(
                LocalDateTime.now().plusNanos(delayMs * 1_000_000L));
            chain.set(new QueryColumn("state"), KbIngestTaskRecord.STATE_QUEUED)
                // ⭐ 指数退避：认领 SQL 只捞 available_at <= now（NULL 视为立即可认领）
                .set(new QueryColumn("available_at"), availableAt);
            log.info("[入库队列] 第 {} 次失败，{} 秒后可重试 id={} 原因={}",
                used, formatSeconds(delayMs), task.getId(), msg);
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
