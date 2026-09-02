-- ===================================================================
-- V25: ScheduleScope.PERSISTENT → DURABLE 改名（对齐 CC CronTask.durable）
--
-- 背景：ScheduleScope 枚举名从 PERSISTENT 改为 DURABLE（用户拍板「PERSISTENT 名字改成
-- CC 的 durable，不要自行定义」· CC original: CronTask.durable, cronTasks.ts:63）。
-- ScheduleService.create 以 scope.name() 落库（DB 存字符串），lookupScope 用
-- ScheduleScope.valueOf(scope) 读回 —— 改名后存量行存 "PERSISTENT" 会抛
-- IllegalArgumentException 走 fallback（行为等价于按 DURABLE 处理），但为保持存量数据
-- 与契约一致（枚举名 ↔ DB 字符串同步），主动迁移存量行。
--
-- 存量行 PERSISTENT → DURABLE；SESSION 不动。
-- ===================================================================

UPDATE schedules SET scope = 'DURABLE' WHERE scope = 'PERSISTENT';
