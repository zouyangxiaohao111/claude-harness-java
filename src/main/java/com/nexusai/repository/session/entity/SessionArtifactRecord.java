package com.nexusai.repository.session.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

/**
 * 会话「产物 / 来源」抽取结果行（表 {@code session_artifact} · 迁移 V205）。
 *
 * <p>由 {@code SessionArtifactService} 轮末异步抽取后 UPSERT 写入；前端只读
 * （{@code SessionArtifactController}）——本表无用户直接写入口。
 *
 * <p><b>唯一键</b> = (session_id, kind, title)（{@code uk_session_artifact}）：
 * 「同一交付物多版本只留最终版」，重复抽取更新本行而不是插新行。
 *
 * <p>时间列是<b>应用显式写入</b>的 ISO-8601 偏移文本（对齐 sessions / V105 时间列约定），
 * 不是 SQLite {@code datetime('now')} 的 UTC 短格式 —— 所以 Java 端按 String 存取。
 */
@Table("session_artifact")
public class SessionArtifactRecord {
    @Id private String id;
    private String sessionId;
    /** {@code deliverable}（产物）| {@code source}（来源）。 */
    private String kind;
    private String title;
    /** 产物文件绝对路径；source 恒 null。 */
    private String localPath;
    /** 来源链接；deliverable 恒 null。 */
    private String url;
    /** 产物文件字节数（目录 / 不可读 → null）。 */
    private Long sizeBytes;
    private String summary;
    /** 溯源消息 id（当前抽取链路不产逐条溯源 → 恒 null，不伪造）。 */
    private String sourceMessageId;
    private String createdAt;
    private String updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
