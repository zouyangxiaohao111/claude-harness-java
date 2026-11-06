package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 知识库标签（{@code kb_knowledge_tag}）· V269 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code knowledge_tags}（000001）。标签是<b>知识库级</b>的定义，
 * 文档与标签是多对多（见 {@link KbKnowledgeTagRelationRecord}）。
 *
 * <p><b>⚠️ 与 {@code kb_chunk.tag_id} 的关系</b>：源侧 000063 已 DROP {@code knowledges.tag_id}，
 * 本仓 {@code kb_knowledge} 本来就没有该列，只有 {@code kb_chunk.tag_id} 是另一套（分块级标签）。
 * 文档打标一律走 {@link KbKnowledgeTagRelationRecord}，⛔ 不要写 {@code kb_chunk.tag_id}。
 */
@Table(
    value = "kb_knowledge_tag",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbKnowledgeTagRecord extends KbBaseRecord {

    /** 主键 · 应用层 UUID。 */
    @Id(keyType = KeyType.None)
    private String id;

    /** 所属知识库。 */
    private String knowledgeBaseId;

    /** 标签名（同一知识库内唯一）。 */
    private String name;

    /** 展示色（十六进制，可空）。 */
    private String color;

    /** 手工排序值。 */
    private Integer sortOrder;

    /** 自增序号（源侧 seq_id，新建时取 max+1）。 */
    private Integer seqId;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getColor() { return color; }
    public void setColor(String v) { this.color = v; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer v) { this.sortOrder = v; }
    public Integer getSeqId() { return seqId; }
    public void setSeqId(Integer v) { this.seqId = v; }
}
