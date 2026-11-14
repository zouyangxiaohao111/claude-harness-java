-- V294: 知识库对话「会话临时附件」（对齐源站 TemporaryDocument）
--
-- <p>为什么单独一张表、⛔ 不复用主会话附件链路：
--   主会话的 `/attachments/upload` 只存字节（图片走 base64、pdf/媒体走各自的 store），
--   模型靠 `<attachments>` 清单 + Read 工具按路径读。而知识库对话要的是
--   「上传即解析成正文、发消息时按预算内联」——解析产物必须持久化到会话上。
--   两条链路互不影响，是本表存在的唯一理由。
--
-- <p>作用域与生命周期：按 session_id 归属，会话删除时一并清理（软删标记 deleted）。
CREATE TABLE IF NOT EXISTS kb_chat_attachment (
    id            VARCHAR(36)  PRIMARY KEY,
    session_id    VARCHAR(36)  NOT NULL,
    file_name     VARCHAR(1024) NOT NULL,
    file_type     VARCHAR(32)  NOT NULL DEFAULT '',
    file_size     INTEGER      NOT NULL DEFAULT 0,
    -- uploaded / processing / ready / failed（对齐源站 TemporaryDocumentStatus*）
    status        VARCHAR(16)  NOT NULL,
    -- 解析后的正文（ready 时才有；failed 时为空，原因写 error_message）
    content       TEXT,
    token_count   INTEGER      NOT NULL DEFAULT 0,
    error_message TEXT,
    create_time   VARCHAR(40)  NOT NULL,
    deleted       INTEGER      NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_kb_chat_attachment_session
    ON kb_chat_attachment(session_id);
