-- ===================================================================
-- V71: messages.seq 的「重入安全回填守卫」+「NULL 拒绝触发器」。
--
-- 【为什么是 V71 而不是直接改 V70】（必读，别把这两条挪回 V70）
--   已有库的 flyway_schema_history 已记录 V70 checksum=510404363；Flyway 校验默认开启
--   （application.yml 的 flyway 段只设了 enabled/locations/baseline-on-migrate，**没有**
--   validateOnMigrate:false）→ 改动 V70 文件会让所有已有库 checksum mismatch，**应用起不来**。
--   故 V70 一个字都不动，缺陷修复以新迁移 V71 追加承担。
--
-- 【缺陷一（重入性）· V70 的三处不可重入】
--   V70:31-33 三条裸 `ALTER TABLE messages ADD COLUMN ...`（SQLite 不支持 ADD COLUMN IF NOT EXISTS，
--   重复执行报 duplicate column name）；V70:40-45 回填 UPDATE **没有 WHERE seq IS NULL 守卫**。
--   两种触发路径：
--     (a) 中断重试：迁移执行中途进程被杀 → 重启 Flyway 重跑 → 第 1 条 ALTER 报
--         `duplicate column name: seq` → 应用起不来；
--     (b) 人工/repair 重跑：只重跑 UPDATE → 把所有行 seq（**含已写入的雪花号**）重算成 1..N，
--         而 compact 重挂的 kept 行 created_at 是刻意保持原值的 → kept 行 seq 掉回 boundary 之前
--         → 下轮 BoundaryReader 取「最后一个 boundary 之后」切片时把 kept 段**整段剪掉** →
--         模型静默丢失本应保留的近期上下文。
--   V71 只修 (b) 的一半（回填守卫）与「不回退 (a)」：本文件全部语句幂等（重跑不报错）。
--   注：(a) 的最终处置取决于 Flyway 在 SQLite 上是否把单个迁移包在一个事务里 —— 已实测，
--   见 backend/src/test/java/com/nexusai/domain/session/FlywaySqliteMigrationAtomicityTest.java
--   （结论回填在该测试的 javadoc 里）。
--
-- 【缺陷三（根因入口）· seq 可空】
--   V70 的 seq 是 `INTEGER NULL`（无 NOT NULL / 无 DEFAULT / 无 CHECK / 无触发器）；写侧
--   MessageService.nextSeq 恒写非 NULL，但**结构上**没有任何东西拦住 NULL。NULL 在
--   `ORDER BY seq ASC` 里排最前（先于第一条真实消息 → 被送进模型上下文顶部）、在
--   `ORDER BY seq DESC ... LIMIT pageSize+1` 里排最后（被挤出尾页 → 前端分页通道看不到它）
--   → 同一份数据两条通道顺序相反，且全仓无任何 NULL 告警。
--   本文件用 BEFORE INSERT / BEFORE UPDATE 触发器把 NULL 挡在写入口（读侧兜底见
--   MessageService.listBySession / listPageBySession 的 `seq IS NULL` 排序 + log.error）。
-- ===================================================================

-- ── 1. 幂等回填守卫（只填补空行；重跑安全，绝不重算已有号）────────────────
--   ⚠️ 与 V70:40-45 的回填**有意不同**（差两处，都是实测驱动的）：
--     (i) 外层 `WHERE seq IS NULL`：只补空行 → 重跑不重算已有 seq（V70 的无守卫版本会把
--         已写入的号重算成 1..N → kept 行掉回 boundary 之前 → 下轮切片静默丢近期上下文）；
--     (ii) 行号 = `base + rn`（base = 该会话已有 max(seq)），把空行补到**该会话已有行之后**，
--          而不是 1..k。WHY 必须有 base：实测 ~/.nexusai/nexusai.db（2026-09-11 取样）存在
--          **混合态**会话 —— sess-c72a825a 有 239 行已定位（V70 回填 1..239，created_at ≤ 20:48）
--          与 **548 行 seq=NULL**（created_at 21:00~22:52，**更晚**，role=tool/assistant/user）。
--          若无 base，这 548 条<b>最新</b>行会被编号成 1..548 → 落到该会话**最前** → 一旦该会话
--          产生 compact boundary，它们就会被当「boundary 之前的旧消息」整段剪掉（正是本轮要根治的
--          静默丢上下文）。加 base 后它们落在 479..1026（= base 239 + 全表 rn 240..787，见下 dry-run
--          实测；值大小不重要，相对序才是契约）= 保持「更晚写入」的真实相对位置，
--          与读侧把 NULL 兜到末尾（MessageService.SEQ_NULLS_LAST_ORDER）的语义一致 →
--          **本语句对该会话的可见顺序是中性变更**（补位前后读侧顺序相同），只是把「未知位置」
--          变成「确定位置」。
--   会话内无任何已定位行（全 NULL 会话）→ base=0 → rn=1..N（与 V70 回填同结果）。
--   真实库上的规模（实测）：4909 行 messages 中 548 行 seq IS NULL（全在 sess-c72a825a），
--   故本语句在真实库上**不是 no-op**，而是一次真实修复（重跑则为 no-op）。
--   真库副本 dry-run 实测（2026-09-11，flyway-core + sqlite-jdbc 3.46 生产同栈）：
--     NULL 548 → 0；总行数 4909 不变；**4361 行已定位行的 seq 逐一未变**（0 条被重算）；
--     sess-c72a825a 的位置序首尾与补位前完全一致（补位 = 顺序中性，只是把「未知」变「确定」）；
--     flyway_schema_history V71 success=1（V70 checksum 未动，validate 通过）；两个触发器建成。
--   [求值语义说明] `base` 取自同一条 UPDATE 的相关子查询（读的正是被改写的 seq 列）——
--     实测该派生表被**整体物化一次**，base 恒为「更新前」的该会话 max(seq)，不随逐行 UPDATE 漂移
--     （sqlite-jdbc 3.46 真库 548 行 + sqlite CLI 3.51 合成 1000 行两种规模均验证）。
--     该语义已由 V71MessagesSeqNullGuardMigrationTest 钉死具体数值；若引擎改为逐行重算而变红，
--     应改为「先物化快照到临时表，再按快照 UPDATE」。
UPDATE messages SET seq = (
  SELECT base + rn FROM (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) AS rn,
           COALESCE(MAX(seq) OVER (PARTITION BY session_id), 0) AS base
    FROM messages
  ) t WHERE t.id = messages.id
) WHERE seq IS NULL;

-- ── 2. NULL 拒绝触发器（BEFORE INSERT / BEFORE UPDATE）──────────────────────
--   WHEN NEW.seq IS NULL → RAISE(ABORT, ...)：写入直接失败并抛错（fail loud），
--   而不是先落一行位置未知的数据、再由读侧去猜它的位置。
--   IF NOT EXISTS：重跑安全（V71 存在的意义就是可重入）。
--   ⚠️ 上线影响（实测驱动，必须知情）：真实库里 4361 行已定位 seq **全是 V70 回填的 1..N、
--   无一行雪花号** → 那个写出 548 行 NULL 的后端构建**没有给 seq 取号**。触发器生效后，
--   这类「不取号的旧构建」在已迁移的库上写消息会被 ABORT（有声失败，而不是静默落 NULL）
--   → **旧构建不能再对这个库写入**（这是 fail-loud 的预期行为，也是「不许回退到不取号的构建」
--   的硬约束）。当前源码的全部写路径（MessageService 五个写方法 + ChatService 实时落库四处
--   直写）都已取号，故新构建不受影响。
--   UPDATE 侧已知边界（有意取舍）：`NEW.seq IS NULL` 对「seq 本来就为 NULL 的存量行」的任何
--   UPDATE 也成立 → 这类行的非 seq 列（如 image_paste_ids / user_attachments 回写）也会被拒。
--   这是有意为之的 fail-loud：位置键未知的行不许被静默改动；修法是先把它补齐（本节回填 UPDATE
--   写的是非 NULL 值 → 不被本触发器拦）。真实库那 548 行 NULL 正是被本节回填补齐的存量行，
--   补完（= V71 跑完）后该类行不再存在 → 本边界只在「触发器已建但回填未跑」的中间态可见。
--   错误信息带 'messages.seq must not be NULL' 前缀，便于日志/异常检索。
CREATE TRIGGER IF NOT EXISTS trg_messages_seq_not_null_insert
BEFORE INSERT ON messages
FOR EACH ROW
WHEN NEW.seq IS NULL
BEGIN
  SELECT RAISE(ABORT, 'messages.seq must not be NULL (V71 位置键守卫): 会话内位置键缺失会让读侧 ORDER BY seq 顺序不稳（ASC 排最前/DESC 排最后）');
END;

CREATE TRIGGER IF NOT EXISTS trg_messages_seq_not_null_update
BEFORE UPDATE ON messages
FOR EACH ROW
WHEN NEW.seq IS NULL
BEGIN
  SELECT RAISE(ABORT, 'messages.seq must not be NULL (V71 位置键守卫): 不许把位置键改回 NULL（compact 重挂只允许写入非 NULL 的 seq）');
END;
