package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 图谱实体（{@code kb_graph_entity}）· V270 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code types.Entity}（{@code internal/types/graph.go}）+
 * Neo4j 的 {@code apoc.merge.node(..., {name, kg: knowledge_id})}。
 *
 * <p><b>⭐ 粒度：一个实体属于一篇文档</b>（{@code knowledge_id}），跨文档<b>不合并</b>。
 * 两篇文档都提到「浙江省」⇒ 两行。合并会把「A 文档里的定义」和「B 文档里的定义」
 * 搅成一个节点，而它们在语义上可能是不同的东西（例：不同年份的政策口径）。
 */
@Table(
    value = "kb_graph_entity",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbGraphEntityRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String knowledgeBaseId;
    private String knowledgeId;

    /** 实体名（源侧 {@code Entity.Title}）· 同文档内唯一。 */
    private String name;
    /** 实体类型（11 种之一，见 {@code KbGraphPrompts.ENTITY_TYPES}）。 */
    private String entityType;
    private String description;

    /** 属性 JSON 数组。 */
    private String attributes;
    /** 出现过的分块 ID JSON 数组 —— 图谱检索的证据来源。 */
    private String chunkIds;

    /** 出现次数（跨块累加）。 */
    private Integer frequency;
    /** 度数 = 入度 + 出度。 */
    private Integer degree;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String v) { this.entityType = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String v) { this.attributes = v; }
    public String getChunkIds() { return chunkIds; }
    public void setChunkIds(String v) { this.chunkIds = v; }
    public Integer getFrequency() { return frequency; }
    public void setFrequency(Integer v) { this.frequency = v; }
    public Integer getDegree() { return degree; }
    public void setDegree(Integer v) { this.degree = v; }
}
