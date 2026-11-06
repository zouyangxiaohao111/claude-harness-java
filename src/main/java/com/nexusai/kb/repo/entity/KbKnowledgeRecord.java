package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.nexusai.kb.repo.KbAuditInsertListener;
import com.nexusai.kb.repo.KbAuditUpdateListener;

/**
 * 知识条目表（{@code kb_knowledge}）记录 · V257 建表。
 *
 * <p><b>源侧对照</b>：WeKnora {@code knowledges} 表的<b>最终 29 列形态</b>
 * （初始 22 列 + 8 个迁移的增删，详见 V257 迁移文件头）。
 *
 * <p><b>与 knowledge_bases 的关系</b>：一个知识库（{@code kb_knowledge_base}）
 * 下挂多个知识条目（本表），每个条目再切成多个分块（{@code kb_chunk}）。
 *
 * <p><b>解析状态机</b>（源侧 {@code parse_status}）：
 * <pre>
 *   unprocessed → pending → processing → finalizing → completed
 *                                       ↘ failed
 *   任意态 → deleting（删除中）
 * </pre>
 * ⚠️ {@code finalizing} 是后处理槽位未排空时的中间态 —— 源侧用
 * {@code pending_subtasks_count} 计数，归零才升 {@code completed}。
 */
@Table(
    value = "kb_knowledge",
    onInsert = KbAuditInsertListener.class,
    onUpdate = KbAuditUpdateListener.class
)
public class KbKnowledgeRecord extends KbBaseRecord {

    /** 主键 · 应用层 UUID。 */
    @Id(keyType = KeyType.None)
    private String id;

    /** 所属知识库 ID。 */
    private String knowledgeBaseId;

    /** 类型（源侧 VARCHAR(50) NOT NULL；取值见 {@code KbKnowledgeType}）。 */
    private String type;

    /** 标题。 */
    private String title;

    /** 描述（可空）。 */
    private String description;

    /** 来源（URL / 文件路径 / 'manual' 等）。 */
    private String source;

    /** 解析状态：unprocessed|pending|processing|finalizing|completed|failed|deleting。 */
    private String parseStatus;

    /**
     * 解析尝试版本号（V260 增列）。
     *
     * <p>用户每触发一次（重）解析就 +1。异步任务携带入队时的 attempt，
     * 执行前与库里比对：不一致即「陈旧任务」，必须放弃 —— 否则旧任务会把
     * 上一轮的 chunk 与后处理计数写进新轮（源侧 {@code attemptSuperseded}）。
     */
    private Integer parseAttempt;

    /** 启用状态：enabled|disabled。 */
    private String enableStatus;

    /** 嵌入模型 ID（可空 = 继承知识库）。 */
    private String embeddingModelId;

    // ── 文件信息 ────────────────────────────────────────────────────
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String filePath;
    private String fileHash;
    /** 存储占用字节（NOT NULL DEFAULT 0）。 */
    private Long storageSize;

    // ── 后处理 ──────────────────────────────────────────────────────
    /** 摘要状态：none|pending|processing|completed|failed。 */
    private String summaryStatus;
    /** 待完成子任务计数（后处理槽位账）。 */
    private Integer pendingSubtasksCount;
    /** FAQ 上次导入结果 JSON。 */
    private String lastFaqImportResult;

    /** 来源渠道（默认 'web'）。 */
    private String channel;

    // ── 结构化（JSON 文本）──────────────────────────────────────────
    private String metadata;
    /** 用户自定义元数据（NOT NULL DEFAULT '{}'）。 */
    private String customMetadata;
    /** 知识库画像 JSON。 */
    private String profile;

    /** 文件夹路径（NOT NULL DEFAULT ''）。 */
    private String folderPath;

    /** 处理完成时间（业务时间，非 audit）。 */
    private String processedAt;

    /** 错误信息（失败时）。 */
    private String errorMessage;

    // ── getter / setter ──────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String v) { this.knowledgeBaseId = v; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public Integer getParseAttempt() { return parseAttempt; }
    public void setParseAttempt(Integer parseAttempt) { this.parseAttempt = parseAttempt; }
    public String getEnableStatus() { return enableStatus; }
    public void setEnableStatus(String enableStatus) { this.enableStatus = enableStatus; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String v) { this.embeddingModelId = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public Long getStorageSize() { return storageSize; }
    public void setStorageSize(Long storageSize) { this.storageSize = storageSize; }
    public String getSummaryStatus() { return summaryStatus; }
    public void setSummaryStatus(String summaryStatus) { this.summaryStatus = summaryStatus; }
    public Integer getPendingSubtasksCount() { return pendingSubtasksCount; }
    public void setPendingSubtasksCount(Integer v) { this.pendingSubtasksCount = v; }
    public String getLastFaqImportResult() { return lastFaqImportResult; }
    public void setLastFaqImportResult(String v) { this.lastFaqImportResult = v; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public String getCustomMetadata() { return customMetadata; }
    public void setCustomMetadata(String customMetadata) { this.customMetadata = customMetadata; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public String getFolderPath() { return folderPath; }
    public void setFolderPath(String folderPath) { this.folderPath = folderPath; }
    public String getProcessedAt() { return processedAt; }
    public void setProcessedAt(String processedAt) { this.processedAt = processedAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
