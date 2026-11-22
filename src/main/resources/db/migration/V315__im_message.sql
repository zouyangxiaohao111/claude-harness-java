-- V315 · 统一消息表 + 扫描渠道配置
--
-- ══════════════════════════════════════════════════════════════════════════
-- 一、为什么需要 im_message
-- ══════════════════════════════════════════════════════════════════════════
-- 三平台的「读历史」能力差得很远，靠现查做不到统一体验：
--   · 飞书  GET /open-apis/im/v1/messages        50条/页、能翻到底、无时间墙、有 message_id
--   · 企微  POST /cli/chat/messages/list         begin_time 逐字「仅支持拉取最近 7 天」、无 message_id
--   · 微信  本机 SQLite（wechat_message, V301）   全量、有自增 msg_id
--
-- 两个只有落库才能解决的问题：
--   1. 企微 7 天窗口 —— 不主动捞，过期后**平台侧永久没有**
--   2. 企微读不到机器人自己发的消息 —— `chat/messages/list` 返回的是「用户视角的消息流」，
--      通过 `/cli/message/aibot/send` 发出去的不在这个流里 ⇒ 必须我们自己补 `direction='out'`
--
-- 读路径永远是「本地表 ∪ 官方接口」，按 dedup_key 去重；对 agent 只暴露一个工具。
--
-- ══════════════════════════════════════════════════════════════════════════
-- 二、为什么 dedup_key 而不是依赖 message_id
-- ══════════════════════════════════════════════════════════════════════════
-- 飞书有 `om_xxx`、微信有自增 id，但**企微的 ChatMessage 没有 messageId**
-- （逐字字段集：msg_type / send_time / text / userid / user_name / image / file /
--   voice / video / mixed），而且 `send_time` 只精确到**秒**。
-- ⇒ 统一用「有平台 id 用平台 id，没有就用 (chat_id|send_time|userid|content) 的 hash」。
-- ⚠️ 已知副作用：同一人**同一秒**发**完全相同**的两条，会被当成一条。
--    概率极低，换来的是三平台一套幂等逻辑，值得。
--
-- ══════════════════════════════════════════════════════════════════════════
-- 三、为什么扫描开关是独立表而不是实例上的一个布尔列
-- ══════════════════════════════════════════════════════════════════════════
-- 「要不要定时扫」是**渠道级的运营决策**，不是实例的属性：
--   · 用户可能只想扫企微（群消息收不到），不想扫飞书（入站事件已经实时推了）；
--   · 以后可能出现「同一实例只扫部分会话」这种更细的配置；
--   · 独立表可以在不重建实例时随开随关，也能记录「上次扫到哪」。
-- ⇒ 单独一张 im_scan_channel，一行 = 一个要扫的实例 + 它的游标。

-- ─────────────────────────── 消息表 ───────────────────────────
CREATE TABLE IF NOT EXISTS im_message (
    id          TEXT PRIMARY KEY,

    -- 归属：哪个实例收到的
    instance_id TEXT NOT NULL,
    -- wecom | feishu | wechat（与 im_channel_instance.platform 同口径）
    platform    TEXT NOT NULL,
    -- 平台侧会话 id（微信 wxid / 企微加密 chatid / 飞书 oc_xxx）
    chat_id     TEXT NOT NULL,

    -- 平台消息 id（飞书 om_xxx / 微信 msg_id）；⚠️ 企微恒为 NULL
    message_id  TEXT,

    -- 幂等键：见文件头「二」。UNIQUE(instance_id, dedup_key) 保证重复拉取不会插两条
    dedup_key   TEXT NOT NULL,

    sender_id   TEXT,
    sender_name TEXT,
    -- text | image | file | voice | video | mixed（平台值直接用，不重命名）
    msg_type    TEXT,

    -- 文本原文；媒体消息只存平台引用（image_key / media_id / file_key），⛔ 不存二进制
    content     TEXT,
    -- 媒体引用（JSON 数组字符串，与 InboundMessage.Media 同结构：kind/fileName/ref）
    media_json  TEXT,

    -- 平台侧发送时间（ISO-8601）。企微只有秒级精度，与它对齐
    sent_at     TEXT NOT NULL,
    -- in = 别人发的 / out = 我们自己发的（补企微读不到 bot 自己消息的缺口）
    direction   TEXT NOT NULL DEFAULT 'in',

    -- 这条数据是怎么来的：official = 从平台接口拉的 / local = 我们自己发送时写的
    -- ⚠️ 与 platform 是两个维度，UI 与排障都要能分开看
    origin      TEXT NOT NULL DEFAULT 'official',

    fetched_at  TEXT NOT NULL
);

-- 按会话查（工具主路径：给定 chatId + 时间区间）
CREATE INDEX IF NOT EXISTS idx_im_message_chat_time
    ON im_message (instance_id, chat_id, sent_at);

-- 按发送者筛（「看群里某个人的消息」三平台都走本地 SQL，因为：
--   飞书 search 接口要额外 scope 且限流 100 次/分钟；企微根本没有 sender 参数）
CREATE INDEX IF NOT EXISTS idx_im_message_sender
    ON im_message (instance_id, chat_id, sender_id, sent_at);

-- 幂等：同一实例内 dedup_key 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uq_im_message_dedup
    ON im_message (instance_id, dedup_key);

-- ──────────────────────── 扫描渠道配置 ────────────────────────
CREATE TABLE IF NOT EXISTS im_scan_channel (
    -- 一个实例一行（PRIMARY KEY 即 instance_id）
    instance_id TEXT PRIMARY KEY,
    -- 1 = 定时扫；0 = 不扫
    enabled     INTEGER NOT NULL DEFAULT 1,
    -- 上次成功扫描到的时间（ISO-8601），下次只拉这个时间之后的增量
    last_scan_at TEXT,
    -- 上次扫描的会话数 / 新增消息数（给 UI 显示「上次扫了 N 个会话、M 条新消息」）
    last_chat_count INTEGER,
    last_new_count  INTEGER,
    -- 上次失败原因（成功时置 NULL）—— 让用户能看到「为什么没扫到」
    last_error  TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);
