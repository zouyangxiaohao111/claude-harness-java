package com.nexusai.repository.session.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

/**
 * 附件表（attachments）记录 · 大文件附件（PDF/媒体/大图）统一 contentId 注册中心。
 *
 * <p><b>净新增表，非 CC 对齐</b>（CC 无 DB 附件表——附件要么存 ImageAttachmentStore 缓存
 * 要么按路径读盘；本表为 Java 侧「附件双模式 + 统一 contentId」架构扩展，用户拍板
 * 2026-09-02：path/upload 大文件附件全部注册本表，contentId = attachments 自增 id
 * （全局唯一 + 持久化 DB，F5/重启可恢复预览）。≤5MB 图片 base64 imagePasteIds 链路
 * 不并入本表）。
 *
 * <p>V64 落库（列 snake_case，MyBatis-Flex camelCase → snake_case 自动映射）：
 * <table>
 *   <tr><th>字段</th><th>DB 列</th><th>说明</th></tr>
 *   <tr><td>{@link #id}</td><td>id</td><td>INTEGER PK AUTOINCREMENT = rowid 别名（SQLite 自增），
 *       出站即 contentId</td></tr>
 *   <tr><td>{@link #sessionId}</td><td>session_id</td><td>所属会话（FK sessions(id) CASCADE）</td></tr>
 *   <tr><td>{@link #path}</td><td>path</td><td>落盘/外部绝对路径（消费/预览读盘的唯一真源）</td></tr>
 *   <tr><td>{@link #mediaType}</td><td>media_type</td><td>MIME 类型（可空）</td></tr>
 *   <tr><td>{@link #filename}</td><td>filename</td><td>显示名（可空）</td></tr>
 *   <tr><td>{@link #size}</td><td>size</td><td>字节数（可空）</td></tr>
 *   <tr><td>{@link #sourceType}</td><td>source_type</td><td>'path'|'upload'（注册来源分流）</td></tr>
 *   <tr><td>{@link #createdAt}</td><td>created_at</td><td>注册时间（DB 默认 datetime('now')）</td></tr>
 * </table>
 *
 * <p>{@code @Id(keyType = KeyType.Auto)}：id 不进 INSERT 列清单（MyBatis-Flex 1.10.0 实测
 * TableInfo 对 KeyType.Auto + before()!=true 跳过 id 列），SQLite 自增分配；insert 后实体
 * id 是否回填见 {@code AttachmentService.register}（KeyType.Auto 走 MyBatis Jdbc3KeyGenerator
 * 回填，若某环境不回填则按 created 序兜底查询取回）。
 */
@Table("attachments")
public class AttachmentRecord {
    /** 自增主键（INTEGER PK AUTOINCREMENT）· 出站即附件 contentId（全局唯一）。 */
    @Id(keyType = KeyType.Auto)
    private Long id;
    /** 所属会话 ID（DB 键 "sess-xxx"；FK sessions(id) ON DELETE CASCADE）。 */
    private String sessionId;
    /** 附件落盘/外部绝对路径（消费与预览读盘的唯一真源；TEXT NOT NULL）。 */
    private String path;
    /** MIME 类型（如 'application/pdf'）；null = 未标注。 */
    private String mediaType;
    /** 显示名（如 'report.pdf'）；null = 未提供。 */
    private String filename;
    /** 字节数；null = 未提供。 */
    private Long size;
    /** 注册来源：'path'（local-read 外部绝对路径）| 'upload'（multipart store 落盘）。 */
    private String sourceType;
    /** 注册时间（ISO 文本；DB 默认 datetime('now')）。 */
    private String createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
