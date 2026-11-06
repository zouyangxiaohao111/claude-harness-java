package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 图谱关系（{@code kb_graph_relation}）· V270 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code types.Relationship} +
 * {@code graph.go calculateWeights/calculateDegrees}。
 *
 * <p><b>⭐ 两端存实体「名字」而不是 id</b>：LLM 返回的就是 title 字符串。
 * 存 id 的话，一旦实体被重抽（id 全变），所有关系立刻指向不存在的 id 且无报错。
 * 存 name 则可以用 {@code (knowledge_id, name)} 重新关联，
 * 且「关系引用了没抽到的实体」这类脏数据能被显式检出（见 {@code KbGraphService}）。
 */
@Table(
    value = "kb_graph_relation",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbGraphRelationRecord extends KbBaseRecord {

    @Id(keyType = KeyType.None)
    private String id;

    private String knowledgeBaseId;
    private String knowledgeId;

    /** 起点实体名。 */
    private String sourceName;
    /** 终点实体名。 */
    private String targetName;
    /** 关系类型（LLM 自由文本，如「属于」「负责」）。 */
    private String relationType;
    private String description;

    /** 强度 5..10（源侧由 LLM 按固定档位判定）。 */
    private Integer strength;
    /** 最终权重 1..10（PMI×0.6 + 强度×0.4 归一后缩放到 1-10）。 */
    private Double weight;
    /** 两端实体度数之和。 */
    private Integer combinedDegree;

    /** 支撑该关系的分块 ID JSON 数组。 */
    private String chunkIds;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String v) { this.sourceName = v; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String v) { this.targetName = v; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String v) { this.relationType = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Integer getStrength() { return strength; }
    public void setStrength(Integer v) { this.strength = v; }
    public Double getWeight() { return weight; }
    public void setWeight(Double v) { this.weight = v; }
    public Integer getCombinedDegree() { return combinedDegree; }
    public void setCombinedDegree(Integer v) { this.combinedDegree = v; }
    public String getChunkIds() { return chunkIds; }
    public void setChunkIds(String v) { this.chunkIds = v; }
}
