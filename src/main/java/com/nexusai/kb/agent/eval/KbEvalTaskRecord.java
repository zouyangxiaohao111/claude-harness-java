package com.nexusai.kb.agent.eval;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;
import com.nexusai.kb.repo.entity.KbBaseRecord;

/**
 * 评测任务（{@code kb_eval_task}）· V275 建表 · 批次 W11。
 *
 * <p>⚠️ 源侧把任务只放内存（进程重启即丢）。本仓落表 ——
 * 评测要跑几十秒到几分钟，结果丢了用户既得重跑、也没法跟上一次对比。
 *
 * <p>状态：{@code pending} → {@code running} → {@code completed} / {@code failed}
 */
@Table(
    value = "kb_eval_task",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbEvalTaskRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String datasetId;

    private String knowledgeBaseId;

    private String status;

    private String errorMessage;

    private Integer total;

    private Integer finished;

    /** 汇总指标 JSON（recall@k / mrr / avgScore）。 */
    private String metrics;

    /** 逐条结果 JSON 数组。 */
    private String details;

    private String startedAt;

    private String finishedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String v) { this.datasetId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer v) { this.total = v; }
    public Integer getFinished() { return finished; }
    public void setFinished(Integer v) { this.finished = v; }
    public String getMetrics() { return metrics; }
    public void setMetrics(String v) { this.metrics = v; }
    public String getDetails() { return details; }
    public void setDetails(String v) { this.details = v; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String v) { this.startedAt = v; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String v) { this.finishedAt = v; }
}
