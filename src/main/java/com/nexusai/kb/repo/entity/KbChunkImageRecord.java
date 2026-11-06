package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbSqliteLocalDateTimeTypeHandler;

import java.time.LocalDateTime;

/**
 * 分块图片投影（{@code kb_chunk_image}）· V269 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code chunk_images}（000113_chunk_images）。
 * 源侧用触发器从 {@code chunks.image_info} 增量同步；本仓改为<b>应用层重投影</b>
 * （见 {@code KbChunkImageProjector}）：写 chunk 的 image_info 之后整块重算，
 * 表本身的定位不变 —— <b>只做查询加速，真相仍在 {@code kb_chunk.image_info}</b>。
 *
 * <p><b>⚠️ 复合主键</b>：{@code (chunk_id, image_index)}。实体只标 {@code chunkId} 为 {@code @Id}
 * 且 {@code KeyType.None}；写入一律走参数化原生 SQL（{@code delete where chunk_id=?} 再批量 insert），
 * ⛔ 不要用 Flex 的 {@code insertOrUpdate}。
 */
@Table("kb_chunk_image")
public class KbChunkImageRecord {

    @Id(keyType = com.mybatisflex.annotation.KeyType.None)
    private String chunkId;

    /** 图片在 chunk.image_info 数组里的下标（从 0 起）。 */
    private Integer imageIndex;

    private String knowledgeBaseId;
    private String knowledgeId;
    private String chunkType;

    /** 去重键：{@code url} → {@code original_url} → {@code "<chunk_id>#<index>"}。 */
    private String imageKey;

    private String url;
    private String originalUrl;
    private String caption;
    private String ocrText;

    /** 观测属性 map 的 JSON 文本。 */
    private String attrs;

    private Integer isEnabled;
    private Integer status;

    @Column(typeHandler = KbSqliteLocalDateTimeTypeHandler.class)
    private LocalDateTime createdAt;

    @Column(typeHandler = KbSqliteLocalDateTimeTypeHandler.class)
    private LocalDateTime updatedAt;

    @Column(tenantId = true)
    private Long tenantId;

    public String getChunkId() { return chunkId; }
    public void setChunkId(String v) { this.chunkId = v; }
    public Integer getImageIndex() { return imageIndex; }
    public void setImageIndex(Integer v) { this.imageIndex = v; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getChunkType() { return chunkType; }
    public void setChunkType(String v) { this.chunkType = v; }
    public String getImageKey() { return imageKey; }
    public void setImageKey(String v) { this.imageKey = v; }
    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }
    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String v) { this.originalUrl = v; }
    public String getCaption() { return caption; }
    public void setCaption(String v) { this.caption = v; }
    public String getOcrText() { return ocrText; }
    public void setOcrText(String v) { this.ocrText = v; }
    public String getAttrs() { return attrs; }
    public void setAttrs(String v) { this.attrs = v; }
    public Integer getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Integer v) { this.isEnabled = v; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long v) { this.tenantId = v; }
}
