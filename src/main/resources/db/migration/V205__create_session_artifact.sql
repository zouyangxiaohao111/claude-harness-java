-- ===================================================================
-- V205: session_artifact —— 会话「产物 / 来源」抽取结果表（新表，无既有 owner）
--
-- 背景：前端要展示本次会话「最终交付物」与「引用的外部来源」。二者不靠前端解析消息得出，
--   而是轮末由旁路 LLM 从**全部历史消息**里抽取（类记忆压缩），结果落本表，前端只做只读轮询。
--
-- 语义：
--   kind = 'deliverable'（产物：用户最终要的结果文件 —— 报告/文档/设计稿/导出物/生成的 html 等。
--          ⛔ 不含源码改动、临时脚本、测试文件）| 'source'（来源：用户给的 URL / 网页搜索结果 /
--          知识库文档 / 被 @ 引用的文件。⛔ 不含模型自己生成的链接）
--   title        = 展示名（抽取结果里的标题）；清洗后为空的行整条丢弃，不入库
--   local_path   = 产物文件绝对路径（相对路径按会话工作目录解析后落绝对路径）；source 恒 NULL
--   url          = 来源链接；deliverable 恒 NULL
--   size_bytes   = 产物文件字节数（目录/不可读 → NULL）
--   summary      = 一句话说明（可空）
--   source_message_id = 预留的溯源消息 id；当前抽取链路没有逐条溯源信息，恒 NULL（不伪造）
--   created_at / updated_at = 应用显式写入的 ISO-8601 带偏移文本（对齐 V101/V105 时间列约定）
--
-- 唯一键 (session_id, kind, title)：同一交付物多版本只留最终版 —— 重复抽取走 UPSERT
--   （更新 local_path/url/size_bytes/summary/updated_at），不堆积历史版本。
--   索引 idx_session_artifact_sid 支撑「按会话 + kind 取全部，updated_at 倒序」的只读查询。
--
-- 级联：会话删除即产物/来源失去归属 ⇒ 随 sessions 级联删除
--   （V105/V106/V112/V114/V115 同款 FOREIGN KEY ... ON DELETE CASCADE；连接串已开 foreign_keys=on）。
--
-- ⚠️ 本文件注释**不得**出现「美元符 + 左大括号」的 Flyway 占位符字面量
--    （V73/V78/V92..V204 同款警告：application.yml 未关 flyway.placeholder-replacement，
--     SQL 注释也在替换范围内 → Flyway 会把它当未配置占位符并解析失败）。
-- ===================================================================

CREATE TABLE IF NOT EXISTS session_artifact (
    id                TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL,
    kind              TEXT NOT NULL,
    title             TEXT NOT NULL,
    local_path        TEXT,
    url               TEXT,
    size_bytes        INTEGER,
    summary           TEXT,
    source_message_id TEXT,
    created_at        TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at        TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

-- 幂等键：同会话 + 同类型 + 同标题只有一行（重复抽取 = UPSERT，不堆历史版本）
CREATE UNIQUE INDEX IF NOT EXISTS uk_session_artifact
    ON session_artifact (session_id, kind, title);

-- 只读列表：GET /api/v1/sessions/{sessionId}/artifacts
CREATE INDEX IF NOT EXISTS idx_session_artifact_sid
    ON session_artifact (session_id, kind, updated_at);
