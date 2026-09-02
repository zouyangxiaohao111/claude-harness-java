-- ===================================================================
-- V65: attachments 表去掉 session_id 外键（session 兜底 'unknown' 与 FK 冲突）
--
-- WHY（2026-09-02 实测 SQLITE_CONSTRAINT_FOREIGNKEY）：
--   V64 attachments.session_id 带 FOREIGN KEY REFERENCES sessions(id) ON DELETE CASCADE，
--   但 upload 端点 sessionId 为可选参数（前端 chat.ts uploadAttachment 仅传 file，
--   不传 sessionId）→ AttachmentController.resolveSessionIdOrUnknown 兜底 'unknown' →
--   sessions 表无 'unknown' 行 → INSERT 外键约束失败。
--
--   附件表（大文件 contentId 注册中心）应独立生命周期：
--     · session_id 仅作归属/分桶标识（store 既有 'unknown' 兜底同款语义），
--       不强制引用 sessions 行；
--     · session 删除时附件行清理由 SessionService 应用层负责（或按需后续接
--       清理钩子），不依赖 DB FK 级联。
--   /content 预览端点按 contentId（id 主键）定位，sessionId 仅路径装饰，
--   不依赖 session_id FK。
--
-- SQLite 不支持 ALTER DROP CONSTRAINT → 重建表（拷贝数据后 DROP + RENAME）。
-- attachments 无任何表 FK 引用它（FK 方向 = attachments→sessions），DROP 合法。
-- ===================================================================
CREATE TABLE attachments_no_fk (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL,
  path        TEXT NOT NULL,
  media_type  TEXT,
  filename    TEXT,
  size        INTEGER,
  source_type TEXT,
  created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
INSERT INTO attachments_no_fk (id, session_id, path, media_type, filename, size, source_type, created_at)
  SELECT id, session_id, path, media_type, filename, size, source_type, created_at FROM attachments;
DROP TABLE attachments;
ALTER TABLE attachments_no_fk RENAME TO attachments;
CREATE INDEX IF NOT EXISTS idx_attachments_session ON attachments(session_id);
