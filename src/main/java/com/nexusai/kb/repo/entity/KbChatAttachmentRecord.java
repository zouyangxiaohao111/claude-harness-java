package com.nexusai.kb.repo.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * 知识库对话「会话临时附件」记录 · V294 建表。
 *
 * <p><b>对齐源站</b>：{@code internal/types/temporary_document.go} 的 {@code TemporaryDocument}
 * —— 会话级、上传即解析、正文随消息内联（{@code temporary_document_prompt.go} 的
 * {@code temporaryDocumentPromptBudget = 12000} / {@code agentAttachmentInlineBudget = 6000}）。
 *
 * <p><b>⛔ 与主会话附件的边界</b>：本表只服务知识库对话的独立上传端点
 * （{@code POST /api/v1/kb/chat/{sessionId}/attachments}）；主会话的
 * {@code /api/v1/attachments/upload} 链路一行不改。
 */
@Table("kb_chat_attachment")
public class KbChatAttachmentRecord {

    @Id
    private String id;

    /** 归属会话。 */
    private String sessionId;

    private String fileName;

    /** 扩展名（不含点，小写）。 */
    private String fileType;

    private Integer fileSize;

    /** uploaded / processing / ready / failed。 */
    private String status;

    /** 解析后的正文（ready 时才有）。 */
    private String content;

    private Integer tokenCount;

    private String errorMessage;

    private String createTime;

    /** 0 = 正常，1 = 已删除（会话/附件清理）。 */
    private Integer deleted;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public Integer getFileSize() { return fileSize; }
    public void setFileSize(Integer fileSize) { this.fileSize = fileSize; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
