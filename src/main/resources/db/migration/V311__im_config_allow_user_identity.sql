-- V311: im_config 增加 allow_user_identity（是否允许 AI 以用户本人名义在 IM 发消息）
--
-- 背景（为什么需要这次迁移）：
--   产品要让 AI 能「以授权真人的名义」在 IM 里发消息（对方看到的是用户本人说的，
--   而不是机器人说的）。这是**社会后果完全不同**的一类动作 —— 机器人通知是信息，
--   冒充本人发言可能让别人误以为用户本人说了某句话。
--   因此必须有**一个显式的全局开关**，由用户在 IM 频道页 / 对话框里自己打开，
--   默认关闭（AI 只能用机器人身份）。
--
-- 影响：
--   表 im_config 增加 1 列（单行表，不影响既有行语义）；既有行该列取默认值 0。
--   其它 6 个开关完全不受影响。
--
-- 注意：
--   · 迁移编号只增不减，已发布迁移的编号与文件名永不改动。
--   · SQLite 的 ALTER TABLE ADD COLUMN 加 NOT NULL 列必须带常量默认值；
--     这里 DEFAULT 0 = 默认关闭（最小放行方向）。
--   · 不写回预置行：预置行是 INSERT OR IGNORE 建的，新列自动取 DEFAULT 0。

ALTER TABLE im_config ADD COLUMN allow_user_identity INTEGER NOT NULL DEFAULT 0;
