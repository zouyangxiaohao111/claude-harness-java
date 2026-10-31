-- ===================================================================
-- V251: computer_use_lock —— computer-use 跨会话互斥锁（单行表 · Phase 1a）
--
-- 背景：CCB 2.8.4 的 computer-use 锁是**进程级模块变量**
--   （src/utils/computerUse/computerUseLock.ts 的模块级 let + ~/.claude/computer-use.lock
--   文件，O_EXCL 原子创建）。本仓 Web 端一个后端进程同时服务**多个会话**
--   ⇒ 进程级变量挡不住「同一进程内两个会话同时控制桌面」，且桌面是**机器唯一资源** ⇒
--   锁必须跨进程、跨会话生效 ⇒ 落**数据库单行表**。
--
-- ⭐ 这是**有意增强**（比 CCB 更强，不是缺陷）：CCB 的锁只在本进程内互斥；本表让
--   「同一台机器上跑多个 nexusai 进程」也互斥。语义其余部分对齐 CCB：
--     - 持有者记 sessionId + pid + acquiredAt（CCB computerUseLock.ts:16-20 同款三字段）
--     - PID 存活探活 + 死 PID 陈旧锁回收（CCB computerUseLock.ts:110-122 / 148-195；
--       本仓探活写法照抄 application/agent/cli/PidLock.java:84-94 的
--       ProcessHandle.of(pid).isPresent()）
--     - 抢锁 = **条件 UPDATE CAS**，用 affected rows 判成败（⛔ SQLite 无
--       SELECT ... FOR UPDATE —— 本仓锁一律走 CAS）
--
-- 表结构（**单行**：id 恒为 1）：
--   id           INTEGER PRIMARY KEY CHECK (id = 1)  —— 单行约束
--   session_id   TEXT    持有者会话 id（NULL = 空闲）
--   pid          INTEGER 持有者进程 pid（NULL = 空闲）
--   acquired_at  INTEGER 取得时刻 epoch 毫秒（NULL = 空闲）
--
-- 写入路径（ComputerUseLockService）：
--   抢锁   UPDATE ... SET session_id=?,pid=?,acquired_at=? WHERE id=1
--            AND (session_id IS NULL OR session_id=?)      → affected=1 取得
--   回收   UPDATE ... SET ... WHERE id=1 AND session_id=? AND pid=?  → affected=1 抢到
--   释放   UPDATE ... SET session_id=NULL,pid=NULL,acquired_at=NULL
--            WHERE id=1 AND session_id=?                    → affected=1 释放
--
-- ⚠️⚠️ 号段分工（**主线 computer-use 迁移固定用 V250+ 号段**）：
--   场景分支（feat/scene-fusion）占用 **V201–V208**（agri 批次）并可继续 **V209–V249**；
--   主线 computer-use 用 **V250+**；两段不得交叉。理由见 V250 的头部注释
--   （同 jar 内两个同号文件 ⇒ Flyway 启动即拒；本仓已踩 V75 / V78 两次）。
--
-- ⚠️ 本文件注释**不得**出现「美元符 + 左大括号」的 Flyway 占位符字面量
--    （application.yml 未关 flyway.placeholder-replacement，SQL 注释也在替换范围内）。
-- ===================================================================
CREATE TABLE IF NOT EXISTS computer_use_lock (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    session_id  TEXT,
    pid         INTEGER,
    acquired_at INTEGER
);

-- 幂等播种：保证表内恒有 id=1 这一行（空闲态）。INSERT OR IGNORE 保证重复执行不报错。
INSERT OR IGNORE INTO computer_use_lock (id, session_id, pid, acquired_at)
VALUES (1, NULL, NULL, NULL);
