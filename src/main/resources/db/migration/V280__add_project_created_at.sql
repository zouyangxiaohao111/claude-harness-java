-- ===================================================================
-- V280 · projects 表加 created_at 列（左栏项目分组序 = 注册先后）
--
-- 背景（2026-11-10 用户裁定）：
--   左栏「任务」列的项目分组标题序，原口径是「组内最新会话 updatedAt 倒序」
--   ⇒ 在后面的项目里新建一个会话，该项目就被顶到最前。用户判为错误。
--   新口径 = 项目分组序按**项目自己**的注册先后，排完不再随会话活动变化；
--   只有组内会话序按 updatedAt 动。
--
-- 为什么必须加列：projects 建表（V1）只有 id/name/path/branch/dirty/agents/
--   last_indexed_at/bound，V69 重建同样没有时间列；id 是 proj- + UUID 前 8 位
--   （随机、不带时序）⇒ 「谁先注册」在当前数据里根本取不到。
--
-- 回填规则：用 SQLite 隐式 rowid 生成**逐个不同**的递增时间。
--   逐个不同是必需的 —— 同值会让组间序塌到「组键升序」兜底，用户看到的就是
--   项目名首字母序而不是注册序。rowid 在 V69 的 INSERT ... SELECT 全表扫描中
--   按原表顺序重新分配，与注册先后同向。
--   形态统一用 UTC 的 Z 结尾 ISO-8601（无小数）⇒ 字符串序 == 时间序。
--   新写入侧（ProjectService.register）用 Instant.now()，同为 Z 结尾、带小数；
--   两者年份不同（回填基准 2020），不存在同秒跨精度比较。
--
-- 可空：NULL = 迁移前未回填的异常行；读写侧按「未知时间」兜底（排在已知之后）。
-- ===================================================================

ALTER TABLE projects ADD COLUMN created_at TEXT;

UPDATE projects
   SET created_at = strftime('%Y-%m-%dT%H:%M:%SZ', '2020-01-01', '+' || rowid || ' seconds')
 WHERE created_at IS NULL;
