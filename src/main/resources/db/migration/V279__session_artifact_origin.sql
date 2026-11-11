-- ===================================================================
-- V279 · session_artifact：加 origin 列 + 收敛重复行 + 唯一键去掉轮次
--
-- 背景（2026-11-10 用户裁定）：
--   ① 项目目录里的文档（Write/Edit 写的）也算产物 ⇒ 需要区分「写进交付物目录」与
--      「本轮改动的项目文档」⇒ 加 origin 列（'deliverables' | 'project-write'）。
--   ② V276 的唯一键含轮次（COALESCE(source_message_id,'')）⇒ 同一文件每轮一行。
--      实测某会话 28 个真实文件产生 889 行、横跨 108 个轮次 ⇒ 「按对话分组」会变成
--      上百份重复。⇒ 唯一键回到 (session_id, kind, title)，归属改为「首次产出时写入、之后不覆盖」。
--
-- ⚠️ 建唯一索引前必须先收敛存量，否则 CREATE UNIQUE INDEX 会因重复直接失败。
--   收敛规则（在真库副本上验证：28 个文件 → 7 组）：
--     同一 (session_id, kind, title) 组内，
--       · 若组内存在「归属能对上 messages 里 role='user' 的行」→ 保留其中 created_at 最早的一行
--         （= 首次真实产出，归属可信）
--       · 否则保留 updated_at 最新的一行（归属未知，只能取最新快照）
--   ⚠️ 不能简单「留最新」：实测那样会把归属错配到系统注入消息上（'A session-scoped Stop hook'
--      这类 is_meta=0 但非真人提问的消息）。
--
-- ⚠️ 本文件注释**不得**出现「美元符 + 左大括号」的 Flyway 占位符字面量
--    （V205/V276 同款警告：application.yml 未关 flyway.placeholder-replacement，
--     SQL 注释也在替换范围内 → 会当未配置占位符解析失败）。
-- ===================================================================

-- ── 1) 加 origin 列（历史行留 NULL = 未标注通道）──────────────────────
ALTER TABLE session_artifact ADD COLUMN origin TEXT;

-- ── 2) 收敛重复行 ──────────────────────────────────────────────────
-- 用临时表算「每个键要保留的那一行」，再删掉其余。
-- 分组键与即将建立的唯一索引严格一致（含 title 的 TRIM 语义差异不管：唯一索引是原值比较）。
CREATE TEMP TABLE _artifact_keep AS
SELECT id FROM (
    SELECT
        a.id,
        ROW_NUMBER() OVER (
            PARTITION BY a.session_id, a.kind, a.title
            ORDER BY
                -- ① 归属可信（能 join 到 role='user' 的真实提问）优先
                CASE WHEN u.id IS NULL THEN 1 ELSE 0 END ASC,
                -- ② 可信组内取最早（首次产出）；不可信组取最新
                CASE WHEN u.id IS NULL THEN a.updated_at END DESC,
                CASE WHEN u.id IS NULL THEN NULL ELSE a.created_at END ASC,
                a.id ASC
        ) AS rn
    FROM session_artifact a
    LEFT JOIN messages u
           ON u.id = a.source_message_id AND u.role = 'user'
) WHERE rn = 1;

-- 安全网：保留集合为空但表非空 ⇒ 说明上面 SQL 有 bug，绝不能执行删除（会清空整表）。
-- ⚠️ 不用 RAISE()（它只能在 trigger 里用），改用「保留集合为空时自己填充全部 id」——
--    效果等价于「什么都不删」，且不会静默丢数据。
INSERT INTO _artifact_keep (id)
SELECT a.id FROM session_artifact a
WHERE NOT EXISTS (SELECT 1 FROM _artifact_keep)
  AND EXISTS (SELECT 1 FROM session_artifact);

DELETE FROM session_artifact WHERE id NOT IN (SELECT id FROM _artifact_keep);

DROP TABLE _artifact_keep;

-- ── 3) 唯一键去掉轮次 ──────────────────────────────────────────────
DROP INDEX IF EXISTS uk_session_artifact_turn;

CREATE UNIQUE INDEX IF NOT EXISTS uk_session_artifact_file
    ON session_artifact (session_id, kind, title);

-- ── 4) 按轮次取产物的索引保留（轮级端点仍要用）────────────────────
-- idx_session_artifact_msg 已在 V276 建立，不重建。
