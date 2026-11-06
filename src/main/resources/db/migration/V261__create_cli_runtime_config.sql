-- [external-agent 2026-11-04] 外部 CLI 运行时配置（每个 runtime 一行）
-- 语义（用户 2026-11-04 拍板）：md 里只写 sub-runtime: claude；
--   **连接信息（配置目录 / base URL / API key）全部来自这张表**，不进 md。
--   ① 未配置 ⇒ 不注入任何环境变量，CLI 用自己的默认配置/登录态；
--   ② 配了某项 ⇒ 用对应环境变量覆盖该项（其余仍走默认）。
-- ⛔ 密钥不进 md（md 会进版本库）；这里存 AES/GCM 密文，接口只回显掩码。
-- 一个 runtime 一行（PK = runtime），多套配置用「配置目录」隔离（如 ~/.claude-2）。
CREATE TABLE cli_runtime_config (
    runtime            TEXT PRIMARY KEY,
    -- 覆盖该 CLI 的 home 目录（如 ~/.claude-2）；空 = 用 CLI 自己的默认
    config_dir         TEXT,
    -- 覆盖 API base URL（代理）；空 = 用 CLI 自己的默认接口地址
    base_url           TEXT,
    -- AES/GCM 加密后的 key（CryptoUtil.encrypt）
    api_key_encrypted  TEXT,
    -- 给用户看的掩码（sk-****d23d），永不含明文
    api_key_masked     TEXT,
    -- key 的指纹，用于「值是否变过」比较（不存明文）
    api_key_hash       TEXT,
    updated_at         TEXT
);
