package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 向量表记录（{@code kb_embedding}）· V263 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code embeddings}（11 列，{@code halfvec}）。
 * 目标侧 {@code halfvec → BLOB}（float32 小端字节序列）。
 *
 * <p><b>为什么独立表而不是 kb_chunk 加列</b>：源侧就是独立多态表
 * （{@code source_id} + {@code source_type}）；一个 chunk 将来可能有多种向量
 * （正文 / 摘要 / 生成的问题各自一个）⇒ 加列会锁死「一块一向量」。
 *
 * <p><b>⚠️ 换模型必须重算</b>：{@code model_id} 是向量可比性的前提。
 * 不同模型产出的向量空间不可比，混在一起算余弦相似度**没有意义**（不会报错，只会给出垃圾排序）。
 * 检索时必须按 {@code model_id} 过滤。
 */
@Table(
    value = "kb_embedding",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbEmbeddingRecord extends KbBaseRecord {

    /** 主键 · 应用层 UUID。 */
    @Id(keyType = KeyType.None)
    private String id;

    /** 所属知识库 ID。 */
    private String knowledgeBaseId;

    /** 所属知识条目 ID。 */
    private String knowledgeId;

    /** 多态指向的源 ID（当前 = kb_chunk.id）。 */
    private String sourceId;

    /** 源类型（当前只有 'chunk'；将来可能有 'knowledge_summary' / 'chunk_question'）。 */
    private String sourceType;

    /** 计算该向量的模型 ID。⚠️ 不同模型的向量不可比。 */
    private String modelId;

    /** 向量维度（= vector 字节数 / 4）。 */
    private Integer dimensions;

    /** float32 小端字节序列。 */
    private byte[] vector;

    /** 向量化时用的原文哈希（排查「向量与文本对不上」用）。 */
    private String contentHash;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String v) { this.sourceId = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { this.sourceType = v; }
    public String getModelId() { return modelId; }
    public void setModelId(String v) { this.modelId = v; }
    public Integer getDimensions() { return dimensions; }
    public void setDimensions(Integer v) { this.dimensions = v; }
    public byte[] getVector() { return vector; }
    public void setVector(byte[] v) { this.vector = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { this.contentHash = v; }
}
