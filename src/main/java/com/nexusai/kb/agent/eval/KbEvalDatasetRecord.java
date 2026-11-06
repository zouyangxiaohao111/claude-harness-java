package com.nexusai.kb.agent.eval;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;
import com.nexusai.kb.repo.entity.KbBaseRecord;

/**
 * 评测数据集（{@code kb_eval_dataset}）· V275 建表 · 批次 W11。
 *
 * <p>⭐ 用户 2026-11-05 裁定「评测基于知识库真跑」⇒ 数据集落表由用户维护，
 * ⛔ 不复刻源侧「仓库内 parquet 样例」（本机没有，且源侧忽略入参恒返回同一份）。
 */
@Table(
    value = "kb_eval_dataset",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbEvalDatasetRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String name;

    private String description;

    /** 目标知识库（评测固定在这个库上跑）。 */
    private String knowledgeBaseId;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
}
