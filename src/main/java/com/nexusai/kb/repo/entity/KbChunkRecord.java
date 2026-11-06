package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 分块表（{@code kb_chunk}）记录 · V257 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code chunks} 表的<b>最终 32 列形态</b>
 * （初始 19 列 + 9 个迁移的增列，详见 V257 迁移文件头）。
 *
 * <p><b>⭐ 块间只有两种关系</b>（源侧实证，别想象更多）：
 * <ol>
 *   <li><b>顺序链</b>：{@link #preChunkId} / {@link #nextChunkId} —— 普通切分按
 *       {@code chunk_index} 串双向链。消费点：命中后沿链拉邻居补上下文。</li>
 *   <li><b>层级</b>：{@link #parentChunkId} —— 父子块模式下子块指向父块。
 *       命中子块时展开父块内容。</li>
 * </ol>
 * ⚠️ <b>父子块模式下子块不串顺序链</b>（源侧注释：children don't need prev/next），
 * 只有<b>父块之间</b>串；父块只存库、不进向量索引。
 *
 * <p><b>⭐ Start/End 不变式</b>（源侧 {@code splitter.go:20-22} 明文）：
 * {@code content} 恰好是原文 {@code [startAt, endAt)} 区间的文本，
 * 且 {@code endAt - startAt == content 的 rune 数}。
 * ⛔ 这条不变式被文档重建（摘要生成、UI 高亮）依赖，不能破坏。
 * 例外：{@code contextHeader} 是<b>独立字段</b>，embedding 时前置，
 * <b>不并入 content</b>（否则破坏位置不变式）。
 */
@Table(
    value = "kb_chunk",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbChunkRecord extends KbBaseRecord {

    /** 主键 · 应用层 UUID。 */
    @Id(keyType = KeyType.None)
    private String id;

    /** 所属知识库 ID。 */
    private String knowledgeBaseId;

    /** 所属知识条目 ID。 */
    private String knowledgeId;

    /** 内容（NOT NULL）。 */
    private String content;

    /** 该块在文档内的序号（0 起，NOT NULL）。 */
    private Integer chunkIndex;

    /** 是否启用（1 = 启用）。 */
    private Integer isEnabled;

    /** 在原文中的起始 rune 偏移（NOT NULL）。 */
    private Integer startAt;

    /** 在原文中的结束 rune 偏移（NOT NULL，开区间）。 */
    private Integer endAt;

    // ── 块间关系（只有这两种）──────────────────────────────────────
    /** 前一块 ID（普通切分串链；父子模式下子块不串）。 */
    private String preChunkId;
    /** 后一块 ID。 */
    private String nextChunkId;
    /** 父块 ID（父子模式下子块指向父块）。 */
    private String parentChunkId;

    /** 分块类型：text | image | table | ...（默认 'text'）。 */
    private String chunkType;

    // ── 多模态与关联 ────────────────────────────────────────────────
    private String imageInfo;
    private String relationChunks;
    private String indirectRelationChunks;
    private String videoInfo;

    // ── 编修 ────────────────────────────────────────────────────────
    /** 用户编辑前的原文（NOT NULL DEFAULT ''）。 */
    private String sourceContent;
    /** 内容版本号（NOT NULL DEFAULT 0）。 */
    private Integer contentRevision;
    /** 索引状态：ready|pending|stale（NOT NULL DEFAULT 'ready'）。 */
    private String indexStatus;
    /** 最后编辑者 ID（NOT NULL DEFAULT ''）。 */
    private String lastEditorId;
    /**
     * 标题面包屑（NOT NULL DEFAULT ''）。
     * <p>embedding 时前置到内容之前，⛔ 不并入 {@code content}（保位置不变式）。
     */
    private String contextHeader;

    // ── 状态与标识 ──────────────────────────────────────────────────
    /** 状态（NOT NULL DEFAULT 0）。 */
    private Integer status;
    /** 内容哈希。 */
    private String contentHash;
    /** 位标志（NOT NULL DEFAULT 1）。 */
    private Integer flags;
    /** 全局单调序号。 */
    private Long seqId;
    /** 旧版标签 ID（已被 knowledge_tag_relations 取代，列仍在）。 */
    private String tagId;

    // ── 结构化 ──────────────────────────────────────────────────────
    private String metadata;
    /** 源位置定位信息 JSON。 */
    private String sourceLocators;

    // ── getter / setter ──────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(String v) { this.knowledgeId = v; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public Integer getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Integer isEnabled) { this.isEnabled = isEnabled; }
    public Integer getStartAt() { return startAt; }
    public void setStartAt(Integer startAt) { this.startAt = startAt; }
    public Integer getEndAt() { return endAt; }
    public void setEndAt(Integer endAt) { this.endAt = endAt; }
    public String getPreChunkId() { return preChunkId; }
    public void setPreChunkId(String preChunkId) { this.preChunkId = preChunkId; }
    public String getNextChunkId() { return nextChunkId; }
    public void setNextChunkId(String nextChunkId) { this.nextChunkId = nextChunkId; }
    public String getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(String parentChunkId) { this.parentChunkId = parentChunkId; }
    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }
    public String getImageInfo() { return imageInfo; }
    public void setImageInfo(String imageInfo) { this.imageInfo = imageInfo; }
    public String getRelationChunks() { return relationChunks; }
    public void setRelationChunks(String v) { this.relationChunks = v; }
    public String getIndirectRelationChunks() { return indirectRelationChunks; }
    public void setIndirectRelationChunks(String v) { this.indirectRelationChunks = v; }
    public String getVideoInfo() { return videoInfo; }
    public void setVideoInfo(String videoInfo) { this.videoInfo = videoInfo; }
    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String v) { this.sourceContent = v; }
    public Integer getContentRevision() { return contentRevision; }
    public void setContentRevision(Integer v) { this.contentRevision = v; }
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    public String getLastEditorId() { return lastEditorId; }
    public void setLastEditorId(String v) { this.lastEditorId = v; }
    public String getContextHeader() { return contextHeader; }
    public void setContextHeader(String v) { this.contextHeader = v; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Integer getFlags() { return flags; }
    public void setFlags(Integer flags) { this.flags = flags; }
    public Long getSeqId() { return seqId; }
    public void setSeqId(Long seqId) { this.seqId = seqId; }
    public String getTagId() { return tagId; }
    public void setTagId(String tagId) { this.tagId = tagId; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public String getSourceLocators() { return sourceLocators; }
    public void setSourceLocators(String v) { this.sourceLocators = v; }
}
