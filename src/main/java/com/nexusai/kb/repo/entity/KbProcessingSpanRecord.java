package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

/**
 * 知识库处理阶段 span 记录（{@code kb_processing_span}）· V284 建表。
 *
 * <p>对应源站 WeKnora 的 {@code knowledge_processing_spans}：把一篇文档在
 * 解析 / 分块 / 向量化 / 图片理解 / 后处理 各阶段的起止时间、状态、
 * 输入输出与错误信息落成 span 树（靠 {@code parentSpanId} 串联），
 * 用来回答「这篇文档卡在哪一阶段」。
 *
 * <p><b>为什么不是 {@link KbBaseRecord} 的子类</b>：本表是<b>流式观测日志</b>
 * 而不是业务实体 —— 写入只增不改（同一 span 按 upsert 覆盖自身），
 * 没有逻辑删除 / 租户隔离的业务语义。理由同
 * {@link KbIngestTaskRecord}。审计六列仍按 V284 建表默认值自动填，
 * 故这里保留字段映射，只是不继承基类。
 *
 * <p><b>状态与 kind 常量</b>：定义在
 * {@link com.nexusai.kb.app.ingest.span.KbSpanStatus} 与
 * {@link com.nexusai.kb.app.ingest.span.KbSpanKind}，
 * 本类<b>不重复定义</b>（两处定义必然分叉）。
 *
 * <p>{@code input / output / metadata} 一律是 <b>String（JSON 文本）</b>，
 * 不是反序列化后的对象：SQLite 无 JSONB，且 span 只做旁路展示，
 * 不该因为某个字段结构变化就让入库失败。
 */
@Table("kb_processing_span")
public class KbProcessingSpanRecord {

    /** 自增主键（SQLite INTEGER PRIMARY KEY AUTOINCREMENT）。 */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /** 关联知识条目 ID（kb_knowledge.id）。 */
    private String knowledgeId;

    /** 处理轮次：同一文档重跑 = attempt+1，旧轮的行原样保留。 */
    private Integer attempt;

    /** span 唯一标识（本仓用 UUID 字符串）。 */
    private String spanId;

    /** 父 span id；root span 为空。 */
    private String parentSpanId;

    /** 展示名（stage 名 / 子任务名）。源站已扩到 255，此处不截断。 */
    private String name;

    /** 节点类型（见 {@code KbSpanKind}）。 */
    private String kind;

    /** 状态（见 {@code KbSpanStatus}）。 */
    private String status;

    /** 输入快照（JSON 文本，可空）。 */
    private String input;

    /** 输出快照（JSON 文本，可空）。 */
    private String output;

    /** 附加元数据（JSON 文本，可空）。 */
    private String metadata;

    /** 错误码（失败时才有）。 */
    private String errorCode;

    /** 错误摘要（给用户看的短句）。 */
    private String errorMessage;

    /** 错误详情（堆栈 / 上游原文）。 */
    private String errorDetail;

    /** 开始时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String startedAt;

    /** 结束时间（{@code yyyy-MM-dd HH:mm:ss}）。 */
    private String finishedAt;

    /** 耗时（毫秒）。 */
    private Long durationMs;

    /** 创建人（审计列，V284 默认空串）。 */
    private String creator;

    /** 创建时间（审计列，V284 默认 datetime('now')）。 */
    private String createTime;

    /** 更新人（审计列，V284 默认空串）。 */
    private String updater;

    /** 更新时间（审计列，V284 默认 datetime('now')）。 */
    private String updateTime;

    /** 逻辑删除标记（审计列，V284 默认 0）。 */
    private Integer deleted;

    /** 租户 ID（审计列，V284 默认 0）。 */
    private Integer tenantId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String knowledgeId) { this.knowledgeId = knowledgeId; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getUpdater() { return updater; }
    public void setUpdater(String updater) { this.updater = updater; }
    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
    public Integer getTenantId() { return tenantId; }
    public void setTenantId(Integer tenantId) { this.tenantId = tenantId; }
}
