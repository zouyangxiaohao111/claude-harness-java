-- ===================================================================
-- V70: messages 表新增 seq（会话内单调排序键）+ is_compact_summary /
--      is_visible_in_transcript_only（CC 摘要消息可观察性标志）。
--
-- 背景（SM/compact 对齐 CC · 根治「created_at 既是时间又是位置」）：
--   `messages.created_at` 此前同时承担两种语义 —— ① 展示时间（前端「X 分钟前」）
--   ② 会话内排序位置（listBySession / listPageBySession ORDER BY created_at ASC）。
--   compact 的 append-only 落库需要把 kept 段「重挂」到 boundary 之后（位置变化），
--   但 kept 消息的真实产生时间不该被改写（展示语义）—— 同一列承担两种语义时必然
--   二选一冲突，重挂位置就会污染展示时间。
--
--   本迁移引入独立的单调排序键 `seq`：位置语义归 seq（读侧 ORDER BY seq），
--   时间语义归 created_at（读侧展示不再受 compact 重挂影响）。对齐 CC transcript
--   的插入序（recordTranscript 追加序 = 会话内位置），created_at 仅作时间戳。
--
-- 列语义：
--   seq（INTEGER，可空）：单调 long（雪花 ID）位置键 —— 类型保持 INTEGER（SQLite INTEGER 即
--     64 位，可容纳 hutool Snowflake long，无需改型）。新写入经 MessageService.nextSeq(sessionId)
--     取 hutool 雪花号（全局单调），免 seed 查询 / 免 per-JVM 重复 / 天然多实例安全。
--     回填 = 按 (session_id, created_at, id) 稳定序生成行号（1..N）；回填值只须保持「相对顺序」
--     ——新雪花号恒大于 1..N，故 1..N 与雪花号混排后全局序仍正确（旧行在前、新行在后）。
--   is_compact_summary（INTEGER 0/1）：CC original: isCompactSummary（messages.ts:465/480）·
--     compact 摘要 user 消息标记（CompactConversation.buildCompactSummaryMessage →
--     isCompactSummary=true）。存量旧行 NULL（toDto Boolean.TRUE.equals 容错 null→false）。
--   is_visible_in_transcript_only（INTEGER 0/1）：CC original: isVisibleInTranscriptOnly
--     （messages.ts:464/479）· 仅 transcript 可见（不进模型上下文）。
--
-- 布尔列范式对齐 V51 is_meta（SQLite 无 BOOLEAN → INTEGER NULL，MyBatis-Flex
--   camelCase↔snake_case 自动映射：isCompactSummary → is_compact_summary）。
-- ===================================================================
ALTER TABLE messages ADD COLUMN seq INTEGER NULL;
ALTER TABLE messages ADD COLUMN is_compact_summary INTEGER NULL;
ALTER TABLE messages ADD COLUMN is_visible_in_transcript_only INTEGER NULL;

-- 回填：按 (session_id, created_at, id) 稳定序生成会话内行号（1..N）。
--   窗口函数一次扫描（SQLite 3.46 支持；源表 SCAN 一次，行号物化后按 id 定位回写）——
--   旧相关子查询 COUNT(*) 写法对每行重扫全表（O(N²)，大会话迁移阻塞）。
--   created_at NOT NULL（V1 schema），故每个会话最小行 seq=1（>0）；id 兜底同 created_at 并列。
--   回填只需相对顺序正确（1..N 单调）；新行雪花号恒大于 1..N → 混排全局序仍正确。
UPDATE messages SET seq = (
  SELECT rn FROM (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) AS rn
    FROM messages
  ) t WHERE t.id = messages.id
);

-- 排序键索引（listBySession / listPageBySession 按 seq 升降序；对齐 idx_messages_session 先例）。
--   IF NOT EXISTS：幂等（迁移重入/索引已存在不报错）。
CREATE INDEX IF NOT EXISTS idx_messages_session_seq ON messages(session_id, seq);
