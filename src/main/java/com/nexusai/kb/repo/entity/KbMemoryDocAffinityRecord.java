package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 文档亲和（{@code kb_memory_doc_affinity}）· V272 建表 · 批次 W9。
 *
 * <p><b>源侧对照</b>：WeKnora {@code memory_doc_affinity}（{@code 000084_memory.up.sql}）。
 *
 * <p>记录「这个人的回答反复引用了哪份文档」。读它的地方是重排：
 * 优先他常回来看的材料。
 *
 * <p><b>⚠️ 这是唯一一个「不问任何问题就能拿到的个性化检索信号」</b>
 * （源侧注释：the only per-person retrieval signal available without asking anything）。
 */
@Table(
    value = "kb_memory_doc_affinity",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbMemoryDocAffinityRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String subjectId;
    private String knowledgeId;
    private String knowledgeBaseId;
    private String title;

    private Integer hits;
    private String lastUsedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String v) { this.subjectId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public Integer getHits() { return hits; }
    public void setHits(Integer v) { this.hits = v; }
    public String getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(String v) { this.lastUsedAt = v; }
}
