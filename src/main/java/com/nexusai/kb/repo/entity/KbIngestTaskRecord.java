package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

/**
 * 知识入库任务记录（{@code kb_ingest_task}）· V260 建表。
 *
 * <p><b>为什么不是 {@link KbBaseRecord} 的子类</b>：本表是<b>队列</b>不是业务实体，
 * 没有逻辑删除 / 租户 / creator-updater 语义（任务完成后靠状态而非删除来表达）。
 * 时间列也只有 created/started/finished 三个业务时间，没有 audit 对。
 * ⇒ 直接用普通类，避免把 zjky 审计字段硬套到队列表上。
 *
 * <p><b>状态机</b>：
 * <pre>
 *   queued ──claim──▶ running ──成功──▶ done
 *     ▲                  │
 *     └──重试(未超限)────┤
 *                        └──失败(超限)──▶ dead
 * </pre>
 */
@Table("kb_ingest_task")
public class KbIngestTaskRecord {

    /** 任务类型：解析主流程。 */
    public static final String TYPE_PARSE = "parse";
    /** 任务类型：空操作（仅用于验证后处理槽位计数机制）。 */
    public static final String TYPE_NOOP = "noop";
    /** 任务类型：文档摘要 / 画像生成（W6 · 占 1 槽）。 */
    public static final String TYPE_SUMMARY = "summary";
    /** 任务类型：问题生成（Doc2Query，W6 · 每批占用 1 槽）。 */
    public static final String TYPE_QUESTION = "question";
    /** 任务类型：Wiki 自动生成（W2/W6 · 占 1 槽）。 */
    public static final String TYPE_WIKI = "wiki";
    /** 任务类型：自动打标（W6 · <b>不占槽</b>，失败不影响文档完成）。 */
    public static final String TYPE_AUTO_TAG = "auto_tag";
    /** 任务类型：图谱抽取（W7 · 每篇文档 1 槽）。 */
    public static final String TYPE_GRAPH = "graph";

    /** 状态：待认领。 */
    public static final String STATE_QUEUED = "queued";
    /** 状态：已认领、执行中。 */
    public static final String STATE_RUNNING = "running";
    /** 状态：成功。 */
    public static final String STATE_DONE = "done";
    /** 状态：本次失败，仍可重试。 */
    public static final String STATE_FAILED = "failed";
    /** 状态：超过重试上限，永久失败。 */
    public static final String STATE_DEAD = "dead";

    /** 自增主键（SQLite INTEGER PRIMARY KEY AUTOINCREMENT）。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /** 关联知识条目 ID。 */
    private String knowledgeId;

    /** 任务类型（见 {@code TYPE_*}）。 */
    private String taskType;

    /** 状态（见 {@code STATE_*}）。 */
    private String state;

    /**
     * 与 {@code kb_knowledge.parse_attempt} 对应的尝试版本号。
     * <p>执行前比对：库里已变 ⇒ 本任务陈旧，直接放弃。
     */
    private Integer attempt;

    /** 已重试次数。 */
    private Integer retryCount;

    /** 最大重试次数。 */
    private Integer maxRetry;

    /** 失败原因（最近一次）。 */
    private String errorMessage;

    /** 任务参数（JSON 文本，可空）。 */
    private String payload;

    /** 入队时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String createdAt;

    /** 开始执行时间。 */
    private String startedAt;

    /** 结束时间（成功或永久失败）。 */
    private String finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String knowledgeId) { this.knowledgeId = knowledgeId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Integer getMaxRetry() { return maxRetry; }
    public void setMaxRetry(Integer maxRetry) { this.maxRetry = maxRetry; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }

    /** 是否还能重试（未超上限）。 */
    public boolean canRetry() {
        int used = retryCount == null ? 0 : retryCount;
        int max = maxRetry == null ? 0 : maxRetry;
        return used < max;
    }
}
