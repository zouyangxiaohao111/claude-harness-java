package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

/**
 * 图片向量记录（{@code kb_image_embedding}）· V287 建表。
 *
 * <p><b>本表存的是图片相关<b>文本</b>的向量，不是伪造的图片语义向量</b>：
 * {@code source} 区分 {@code caption_text} / {@code ocr_text}（{@code multimodal} 目前只预留）。
 * 本仓没有多模态 embedding 模型，因此 caption / OCR 文本按文本模型编码；
 * 模型未配置时服务不写任何行（见 {@code KbImageVectorService}）。
 *
 * <p><b>⚠️ 换模型必须重算</b>：{@code model_id} 是向量可比性的前提，
 * 唯一键含 {@code model_id}；检索按 {@code model_id} 过滤，⛔ 不混算不同模型的向量。
 */
@Table("kb_image_embedding")
public class KbImageEmbeddingRecord {

    /** 主键 · 应用层 UUID。 */
    @Id(keyType = KeyType.None)
    private String id;

    private String knowledgeBaseId;
    private String knowledgeId;

    /** 图片所在分块（可空：历史行或块已删）。 */
    private String chunkId;

    /** 图片资源键（url → original_url → "chunkId#index"，与 kb_chunk_image 同口径）。 */
    private String imageKey;

    /** 文本来源：{@code caption_text} | {@code ocr_text} | {@code multimodal}（预留）。 */
    private String source;

    /** 编码所用模型 ID。⚠️ 不同模型的向量不可比。 */
    private String modelId;

    /** 向量维度（= vector 字节数 / 4）。 */
    private Integer dim;

    /** float32 小端序字节序列。 */
    private byte[] vector;

    /** 参与编码的文本 SHA-256（用于跳过重复编码）。 */
    private String contentHash;

    /** 写入时间（{@code yyyy-MM-dd HH:mm:ss}，与 SQLite datetime('now') 同款）。 */
    private String createdAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String v) { this.chunkId = v; }
    public String getImageKey() { return imageKey; }
    public void setImageKey(String v) { this.imageKey = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getModelId() { return modelId; }
    public void setModelId(String v) { this.modelId = v; }
    public Integer getDim() { return dim; }
    public void setDim(Integer v) { this.dim = v; }
    public byte[] getVector() { return vector; }
    public void setVector(byte[] v) { this.vector = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String v) { this.contentHash = v; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String v) { this.createdAt = v; }
}
