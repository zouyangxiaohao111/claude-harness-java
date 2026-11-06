-- ===================================================================
-- V272: 长期记忆（WeKnora 复刻 · 批次 W9）
--
-- 源侧对照（WeKnora v0.9.0 @ 80e8345）：
--   migrations/versioned/000084_memory.up.sql   → 6 张表的列语义
--   internal/types/memory.go                    → 五 kind / 四 status / 三 origin / 脱敏正则
--   internal/handler/memory.go                  → 16 个端点
--   internal/application/service/memory/service.go → 写入四道闸 / 双时态 / 墓碑
--
-- ⭐ 用户 2026-11-05 裁定：
--   ① 主体 = **单机单主体**（固定 local_user:default），表里保留 subject_id 列，
--      将来接多用户只改解析函数。
--   ② 范围 = **端点 + 后台抽取 + 注入**（⛔ 不只做 CRUD —— 那是空壳）。
--
-- ⚠️ 时间列一律 TEXT（本仓 KbTimeFormat 统一 yyyy-MM-dd HH:mm:ss），
--    与基类 create_time / update_time 同格式，否则 ORDER BY 会错序。
-- ===================================================================


-- ───────────────────────────────────────────────────────────────
-- 1. kb_memory_subject —— 每主体一行，缓存「常驻块」
--
-- 源侧注释：block_text 在写入时重算，读的时候是一次主键查询就够 ——
-- 每轮都要注入，读路径不能有额外计算。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_memory_subject (
    id                  TEXT PRIMARY KEY,

    -- 主体标识（单机固定 'local_user:default'；将来的多用户就是改这里）
    subject_id          TEXT NOT NULL,

    -- 用户自己的开关（工作区级开关在 settings.kb_memory_*）
    enabled             INTEGER NOT NULL DEFAULT 1,

    -- 渲染好的 profile/preference 常驻块
    block_text          TEXT NOT NULL DEFAULT '',
    block_updated_at    TEXT,

    item_count          INTEGER NOT NULL DEFAULT 0,
    last_extracted_at   TEXT,

    -- ⭐ 水位线：这个主体说到这一刻为止的内容都已被「考虑过」蒸馏。
    --    抽取任务从水位往后扫 ⇒ 一次定时器错过或一轮爆发都不会漏消息。
    extract_cursor      TEXT,

    -- 水位之后还有新消息的会话（抽取在跑又来了新轮次时记下来）
    pending_sessions    TEXT,

    -- 抽取任务已排队的时刻 —— 去抖用：并发轮次只排一个任务，不是每轮一个
    extract_scheduled_at TEXT,

    consolidated_at       TEXT,
    forced_consolidated_at TEXT,

    creator             TEXT NOT NULL DEFAULT '',
    create_time         TEXT NOT NULL DEFAULT (datetime('now')),
    updater             TEXT NOT NULL DEFAULT '',
    update_time         TEXT NOT NULL DEFAULT (datetime('now')),
    deleted             INTEGER NOT NULL DEFAULT 0,
    tenant_id           INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_memory_subject
    ON kb_memory_subject (subject_id) WHERE deleted = 0;


-- ───────────────────────────────────────────────────────────────
-- 2. kb_memory_item —— 记忆条目
--
-- ⭐ 矛盾不删除，用双时态 supersede：
--    旧行保留 content，打上 invalid_at + superseded_by
--    ⇒「上个月它以为是什么」仍可回答，用户看得到为什么变了。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_memory_item (
    id                  TEXT PRIMARY KEY,

    subject_id          TEXT NOT NULL,

    -- profile | preference | fact | task | interest
    -- ⚠️ 前两种进「常驻块」（每轮注入），后三种按查询匹配才拉进来
    kind                TEXT NOT NULL,

    content             TEXT NOT NULL,

    -- 这句话说的是「关于什么」（可读主题）。问题常常只提主题，
    -- 而语句本身只带取值 ⇒ 这个字段是唯一的检索抓手。
    topic               TEXT NOT NULL DEFAULT '',

    -- 归一化主题键。两条同键的 active 条目 = 同一个事实的两个时期 ⇒ 触发 supersede
    normalized_key      TEXT NOT NULL DEFAULT '',

    importance          INTEGER NOT NULL DEFAULT 3,

    -- explicit（用户明说）| extracted（后台蒸馏）| manual（记忆管理器里手工建/改）
    -- ⚠️ 只有 extracted 走墓碑拦截 —— 用户说「记住这个」必须永远能写进去
    origin              TEXT NOT NULL DEFAULT 'extracted',

    -- active | superseded | archived | pending
    -- ⚠️ pending = 系统推断的，绝不注入提示词，等用户确认
    status              TEXT NOT NULL DEFAULT 'active',

    source_session_id   TEXT,
    source_message_id   TEXT,

    valid_from          TEXT NOT NULL DEFAULT (datetime('now')),
    invalid_at          TEXT,

    -- 这条事实什么时候不再值得回忆（「本周把迁移做完」这类）
    -- 没有它，在办事项会永远留在上下文里，慢慢变成一张「早就做完的事」清单
    expires_at          TEXT,

    superseded_by       TEXT,

    last_used_at        TEXT,
    use_count           INTEGER NOT NULL DEFAULT 0,

    creator             TEXT NOT NULL DEFAULT '',
    create_time         TEXT NOT NULL DEFAULT (datetime('now')),
    updater             TEXT NOT NULL DEFAULT '',
    update_time         TEXT NOT NULL DEFAULT (datetime('now')),
    deleted             INTEGER NOT NULL DEFAULT 0,
    tenant_id           INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_kb_memory_item_scope
    ON kb_memory_item (subject_id, status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_kb_memory_item_key
    ON kb_memory_item (subject_id, normalized_key) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_kb_memory_item_session
    ON kb_memory_item (source_session_id);


-- ───────────────────────────────────────────────────────────────
-- 3. kb_memory_tombstone —— 用户删掉的（只存指纹）
--
-- ⚠️ **没有 content 列，这是刻意的**：
--    要求「忘掉」的东西不该在另一张表里以另一个名字留着。
--    指纹 = sha256(去掉大小写/空白/标点后的正文)，够用来拦重抽。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_memory_tombstone (
    id                  TEXT PRIMARY KEY,

    subject_id          TEXT NOT NULL,
    topic               TEXT NOT NULL DEFAULT '',
    fingerprint         TEXT NOT NULL,
    source_message_id   TEXT,

    creator             TEXT NOT NULL DEFAULT '',
    create_time         TEXT NOT NULL DEFAULT (datetime('now')),
    updater             TEXT NOT NULL DEFAULT '',
    update_time         TEXT NOT NULL DEFAULT (datetime('now')),
    deleted             INTEGER NOT NULL DEFAULT 0,
    tenant_id           INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_memory_tombstone
    ON kb_memory_tombstone (subject_id, fingerprint) WHERE deleted = 0;


-- ───────────────────────────────────────────────────────────────
-- 4. kb_memory_topic_stat —— 主题计数（提升为 interest 之前）
--
-- 单次提问是噪音，同一主题跨几次会话才是信号。
-- ⚠️ DeleteTopic 不是删行，而是标记「拒绝」—— 否则下次又会被自动提升。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_memory_topic_stat (
    id                  TEXT PRIMARY KEY,

    subject_id          TEXT NOT NULL,
    normalized_key      TEXT NOT NULL,
    topic               TEXT NOT NULL DEFAULT '',

    hits                INTEGER NOT NULL DEFAULT 0,
    last_seen_at        TEXT,
    promoted_at         TEXT,

    -- ⭐ 拒绝标记：非空 = 用户停止跟踪了这个主题，不再自动提升
    rejected_at         TEXT,

    -- 同一主题的不同说法（JSON 数组文本）
    aliases             TEXT NOT NULL DEFAULT '[]',

    creator             TEXT NOT NULL DEFAULT '',
    create_time         TEXT NOT NULL DEFAULT (datetime('now')),
    updater             TEXT NOT NULL DEFAULT '',
    update_time         TEXT NOT NULL DEFAULT (datetime('now')),
    deleted             INTEGER NOT NULL DEFAULT 0,
    tenant_id           INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_memory_topic
    ON kb_memory_topic_stat (subject_id, normalized_key) WHERE deleted = 0;


-- ───────────────────────────────────────────────────────────────
-- 5. kb_memory_doc_affinity —— 文档亲和
--
-- 「这个人的回答反复引用了哪份文档」。读它的地方是重排：
-- 优先他常回来看的材料。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_memory_doc_affinity (
    id                  TEXT PRIMARY KEY,

    subject_id          TEXT NOT NULL,
    knowledge_id        TEXT NOT NULL,
    knowledge_base_id   TEXT NOT NULL DEFAULT '',
    title               TEXT NOT NULL DEFAULT '',

    hits                INTEGER NOT NULL DEFAULT 0,
    last_used_at        TEXT,

    creator             TEXT NOT NULL DEFAULT '',
    create_time         TEXT NOT NULL DEFAULT (datetime('now')),
    updater             TEXT NOT NULL DEFAULT '',
    update_time         TEXT NOT NULL DEFAULT (datetime('now')),
    deleted             INTEGER NOT NULL DEFAULT 0,
    tenant_id           INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_kb_memory_doc_affinity
    ON kb_memory_doc_affinity (subject_id, knowledge_id) WHERE deleted = 0;


-- ───────────────────────────────────────────────────────────────
-- 6. kb_memory_item_embedding —— 向量（与条目分表）
--
-- 源侧注释：向量单独放，是为了让「列表 / 容量检查 / 常驻块」
-- 永远不付加载向量的代价。
--
-- ⚠️ 本仓第一版**不写向量**（语义召回暂不接）—— 表建好是为了将来接上
--    不用再改表；写路径留空不会让任何查询变慢。
-- ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kb_memory_item_embedding (
    item_id             TEXT PRIMARY KEY,

    subject_id          TEXT NOT NULL,
    model_id            TEXT NOT NULL DEFAULT '',
    dims                INTEGER NOT NULL DEFAULT 0,
    vector              BLOB,

    creator             TEXT NOT NULL DEFAULT '',
    create_time         TEXT NOT NULL DEFAULT (datetime('now')),
    updater             TEXT NOT NULL DEFAULT '',
    update_time         TEXT NOT NULL DEFAULT (datetime('now')),
    deleted             INTEGER NOT NULL DEFAULT 0,
    tenant_id           INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_kb_memory_embedding_scope
    ON kb_memory_item_embedding (subject_id);


-- ───────────────────────────────────────────────────────────────
-- 7. 记忆开关与写入模式 → **不放 settings**
--
-- ⭐ 用户 2026-11-05 最终裁定：知识库域配置**一个都不留在 settings**，
--    全部落 V273 建的 `kb_config`（含 memory_enabled / memory_write_mode /
--    memory_max_items / memory_interest_threshold 四列）。
--
-- ⚠️ 本迁移先于 V273 执行，此时 kb_config 还不存在 ⇒ 这里**不建列**，
--    由 V273 建表时自带这四列。历史值无迁移必要（这四列从未被写入过：
--    旧 SettingsService 的 memory 读写方法零调用点）。
--
-- ⚠️ write_mode 默认 'explicit_only'（源侧同）：
--    auto = 每轮后台调 LLM 蒸馏，会花钱。默认必须是「只记用户明说的」，
--    要自动化得由用户显式打开 —— 否则一装就烧 token。
-- ───────────────────────────────────────────────────────────────
-- （无 settings DDL —— 见 V273）
