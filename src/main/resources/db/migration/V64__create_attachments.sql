-- ===================================================================
-- V64: attachments 表（大文件附件统一 contentId 注册中心）
--
-- 背景（附件双模式 + 统一附件表 contentId · 用户拍板 2026-09-02）：
--   本地桌面（前后端同机 Tauri）>5MB 附件传本地 path 后端直读（省 upload）；
--   远程走 upload。附件表（attachments）成为所有大文件附件（PDF/媒体/大图）
--   的统一 contentId 注册中心：contentId = attachments 自增 id（全局唯一 +
--   持久化 DB，F5/重启可恢复预览 url）。≤5MB 图片 base64 的 imagePasteIds 链路
--   保持现状（不并入本表）。
--
-- WHY（统一解析轴）：
--   1) path 附件（local-read）→ 注册本表（path=外部绝对路径），模型消费/预览
--      统一经 contentId → 查表 → path 读盘；
--   2) upload 附件（multipart）→ store 落盘(cache) → 注册本表（path=落盘路径），
--      消费同一条 contentId → path 解析（跨 store 无差别）；
--   3) 重启后 attachments 表 = DB 永存，F5 预览 / 模型消费不受内存 store 影响。
--
-- source_type 取值：'path'（外部绝对路径 local-read 注册）| 'upload'（multipart
--   store 落盘注册）。列（MyBatis-Flex snake↔camel 自动映射）：
--   id ↔ id（INTEGER PK AUTOINCREMENT = rowid 别名，SQLite 自增）
--   session_id ↔ sessionId（TEXT；FK sessions(id) ON DELETE CASCADE）
--   path ↔ path（TEXT NOT NULL，落盘/外部绝对路径）
--   media_type ↔ mediaType（TEXT，MIME 类型；可空）
--   filename ↔ filename（TEXT，显示名；可空）
--   size ↔ size（INTEGER，字节数；可空）
--   source_type ↔ sourceType（TEXT，'path'|'upload'；可空）
--   created_at ↔ createdAt（TEXT，默认 datetime('now')）
-- ===================================================================
CREATE TABLE IF NOT EXISTS attachments (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id  TEXT NOT NULL,
  path        TEXT NOT NULL,
  media_type  TEXT,
  filename    TEXT,
  size        INTEGER,
  source_type TEXT,
  created_at  TEXT NOT NULL DEFAULT (datetime('now')),
  FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_attachments_session ON attachments(session_id);
