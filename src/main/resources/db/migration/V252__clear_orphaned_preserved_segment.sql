-- ===================================================================
-- V252: 清理「悬挂保留段」—— 一次性存量数据修复
--
-- 背景（2026-11-01 用户实测）：
--   messages 表 compact_boundary 行的 compact_metadata 里带 preservedSegment
--     { headUuid, anchorUuid, tailUuid }
--   这是压缩时记下的「保留段接线图」，读侧 BoundaryReader.applyPreservedSegmentRelink
--   靠它在切片后把 kept 行接回 anchor 之后。
--
--   但 trimSessionAfter / replaceSessionMessages / delete 三个删行路径此前**从不回头看
--   这张图** ⇒ 裁剪一旦删到 head/tail/anchor 之一，boundary 上就留下悬挂指针：
--     · 读侧每次投影都判「区间不合法」→ **整段 kept 放弃重挂**（压缩白做，保留段丢失）
--     · 每次 post-sampling hook 都刷一行 ERROR（本机实测单个 boundary 刷了 763 行，
--       把真错误淹掉）
--
--   代码侧已在 V252 同期修复（MessageService.clearPreservedSegmentsPointingAtDeletedRows，
--   挂在 trimSessionAfter / replaceSessionMessages / delete 之后）。但**存量脏数据不会
--   自愈** —— 修复只在「下次删行」时触发，而用户不一定会再裁一次那个会话 ⇒ 本迁移做一次。
--
-- 处置：三个引用**任一**已不在 messages 表 ⇒ 从 compact_metadata 摘除整段 preservedSegment
--   （其余元数据原样保留，boundary 行本身与 seq 一律不动）。
--   为什么整段摘而不是「收窄区间」：接线图描述一个连续区间与它的锚点，缺任一端就无法
--   确定 kept 段边界，收窄等于臆造一段用户没保留的内容。摘除后行为 = 「无保留段」=
--   读侧原样返回切片（与全量压缩同路径），**不会**少加载任何仍在库的消息。
--
-- ⛔ 本迁移只删 metadata 里的一段 JSON 键：
--     · 不删任何 messages 行
--     · 不改任何 seq（排序权威只有 seq，红线不变）
--     · 不碰非 compact_boundary 行
--
-- 幂等：JSON_REMOVE 对不存在的键是 no-op；已无 preservedSegment 的行不满足 WHERE 条件。
-- ===================================================================

UPDATE messages
SET compact_metadata = json_remove(compact_metadata, '$.preservedSegment')
WHERE subtype = 'compact_boundary'
  AND compact_metadata IS NOT NULL
  AND json_extract(compact_metadata, '$.preservedSegment') IS NOT NULL
  AND (
        -- 任一引用行已不存在于 messages 表 ⇒ 悬挂
        NOT EXISTS (SELECT 1 FROM messages h
                    WHERE h.id = json_extract(messages.compact_metadata, '$.preservedSegment.headUuid'))
     OR NOT EXISTS (SELECT 1 FROM messages a
                    WHERE a.id = json_extract(messages.compact_metadata, '$.preservedSegment.anchorUuid'))
     OR NOT EXISTS (SELECT 1 FROM messages t
                    WHERE t.id = json_extract(messages.compact_metadata, '$.preservedSegment.tailUuid'))
  );
