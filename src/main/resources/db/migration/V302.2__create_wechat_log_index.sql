-- V302.2: 微信本地聊天记录索引（只存摘要，用户裁定 2026-11-08）
--
-- 版本号说明：本迁移最初取号 V282，与上游 V282（messages_thinking_signature）撞号；
--   已改为 V338（0.1.83 曾以 V301 发布过一版，但该版本从未随包发布到使用端；
--   2026-11-23 因 V302 号被 add_settings 占用，统一回滚到 ≥V338 的新号，避免与已发布编号冲突）。
--
-- 背景：从本机微信的加密数据库里读出聊天记录，供 AI 工具按关键词/会话/时间检索。
--   取密钥与解密见 com.nexusai.wechatlog 包；本表只承载「检索所需的摘要」。
--
-- ⚠️ 存储策略（用户明确裁定）：
--   · 不把微信消息整份复制进 NexusAI 库，只存摘要 + 关键字段；
--   · 正文按需从本机解密产物（~/.nexusai/wechat-log/<wxid>/）读取；
--   · 因此 content_preview 只是「给人看的一小段」，不是全文，不能当全文检索源。
--     真正的全文匹配走 search_text（截断后的可检索文本）。
--
-- ⚠️ 为什么不建 FTS5 虚表：
--   本机 sqlite-jdbc 带 FTS5，但微信消息的检索量级（单账号几十万条）用普通索引 +
--   LIKE 已足够；引入 FTS 会多一张影子表 + 触发器，且中文分词质量不可控。
--   （若将来要分词检索，另开一次迁移加 FTS5，不动本表结构。）

-- ── 会话列表 ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS wechat_conversation (
    id                  TEXT PRIMARY KEY,

    -- 账号 wxid（一台机器可能有多个账号目录）
    account_wxid        TEXT NOT NULL,

    -- 会话标识：单聊是对方 wxid，群聊是 xxx@chatroom
    conversation_id     TEXT NOT NULL,

    -- 展示名（备注 > 昵称 > 别名 > wxid，由 MessageReader.ContactInfo 算好）
    display_name        TEXT,

    is_group            INTEGER NOT NULL DEFAULT 0,

    -- 最近一条消息时间（Unix 秒）
    last_message_time   INTEGER,

    message_count       INTEGER NOT NULL DEFAULT 0,

    -- 最近一次同步到该会话的时间（Unix 秒）
    synced_at           INTEGER,

    creator             TEXT NOT NULL DEFAULT '',
    create_time         TEXT NOT NULL DEFAULT (datetime('now')),
    updater             TEXT NOT NULL DEFAULT '',
    update_time         TEXT NOT NULL DEFAULT (datetime('now')),
    deleted             INTEGER NOT NULL DEFAULT 0
);

-- 同一账号下同一会话唯一（同步时 upsert）
CREATE UNIQUE INDEX IF NOT EXISTS ux_wechat_conversation
    ON wechat_conversation (account_wxid, conversation_id);

CREATE INDEX IF NOT EXISTS ix_wechat_conversation_last_time
    ON wechat_conversation (account_wxid, last_message_time DESC);

-- ── 消息摘要 ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS wechat_message (
    id                  TEXT PRIMARY KEY,

    account_wxid        TEXT NOT NULL,
    conversation_id     TEXT NOT NULL,

    -- 微信自己的消息 id（server_id）· 用于去重与增量
    server_id           INTEGER NOT NULL DEFAULT 0,
    -- 排序序号（sort_seq：10 位时间戳 + 3 位序号）· 分片合并后的稳定顺序
    sort_seq            INTEGER NOT NULL DEFAULT 0,

    -- 微信自己的消息时间（Unix 秒）· 业务时间，不是审计时间
    msg_time            INTEGER NOT NULL DEFAULT 0,

    -- 发送者 wxid（群聊里区分是谁说的；单聊里可能是对方或自己）
    sender              TEXT,

    -- 归一化后的类型（1 文本 / 3 图片 / 34 语音 / 49 应用消息 / 10000 系统 ...）
    msg_type            INTEGER NOT NULL DEFAULT 0,

    -- 给人看的一小段（截断，仅用于展示，不参与检索）
    content_preview     TEXT,

    -- 可检索文本（截断到 500 字符）· 关键词检索走这一列
    search_text         TEXT,

    -- 是否为本人发送
    is_self             INTEGER NOT NULL DEFAULT 0,

    creator             TEXT NOT NULL DEFAULT '',
    create_time         TEXT NOT NULL DEFAULT (datetime('now')),
    updater             TEXT NOT NULL DEFAULT '',
    update_time         TEXT NOT NULL DEFAULT (datetime('now')),
    deleted             INTEGER NOT NULL DEFAULT 0
);

-- 去重键：同一账号、同一会话、同一条微信消息只存一份
CREATE UNIQUE INDEX IF NOT EXISTS ux_wechat_message
    ON wechat_message (account_wxid, conversation_id, server_id, sort_seq);

CREATE INDEX IF NOT EXISTS ix_wechat_message_time
    ON wechat_message (account_wxid, msg_time DESC);

CREATE INDEX IF NOT EXISTS ix_wechat_message_conv
    ON wechat_message (account_wxid, conversation_id, msg_time DESC);

CREATE INDEX IF NOT EXISTS ix_wechat_message_sender
    ON wechat_message (account_wxid, sender, msg_time DESC);
