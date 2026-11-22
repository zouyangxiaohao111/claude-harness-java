-- ===================================================================
-- V333: AskUserQuestion 的 AFK 自动继续（超时）· settings 单列
--
-- 对齐 CC claude.exe 2.1.328 的 settings 键 askUserQuestionTimeout：
--   值域 60s / 5m / 10m / never（CC 原文 @314820551）；未设置或 never ⇒ 不自动继续。
--   本仓列 NULL / 空 / 非法值 一律按 never 处理（默认关闭）。
--
-- 语义：设置非 never 时，前端权限卡在前端倒计时；到点以 allow 提交「已作答项」，
--   未答不计默认值（对齐 CC：answersToSubmit 只含已答，未答只记 skipped 遥测），
--   结果带 afkTimeoutMs，模型侧文案为 "No response after Ns — the user may be away from
--   keyboard…"。后端不设任何 deadline，计时只发生在前端卡片。
--
-- ⚠️ 与 CC 的一处有意差异（写清理由，勿改成 CC 行为）：
--   CC 允许 CLAUDE_AFK_TIMEOUT_MS 单独存在时启用 AFK；本仓要求「设置列非 never」才可能启用，
--   环境变量只能覆盖时长，不能把用户显式选择的 never 变成开启。
--
-- ⚠️ 文件一旦落盘，用户重启时 Flyway 可能立即应用 ⇒ 内容不可再改；要调整只能新开迁移。
-- ===================================================================
ALTER TABLE settings ADD COLUMN ask_user_afk_timeout TEXT;

-- 存量行显式写 never（与「未配置」同义，但让 UI 读取时能直接拿到默认值文案）
UPDATE settings SET ask_user_afk_timeout = 'never' WHERE ask_user_afk_timeout IS NULL;
