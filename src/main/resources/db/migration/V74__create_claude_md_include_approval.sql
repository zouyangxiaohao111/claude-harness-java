-- ===================================================================
-- V74: CLAUDE.md 外部 @import 审批态持久化（批 acc2）
-- 对齐 CC project config 的 hasClaudeMdExternalIncludesApproved / hasClaudeMdExternalIncludesWarningShown
--   （字段声明 config.ts:115 / :116，缺省 false config.ts:146 / :147；消费点 claudemd.ts:796 / :1420
--    `getCurrentProjectConfig()`；门控 claudemd.ts:798-801）
-- CC 的宿主是**项目配置**（跨进程存盘）⇒ 重启后不再重复弹审批；Java 原实现是纯内存
--   ConcurrentHashMap（进程重启即丢 ⇒ 每次重启重复弹窗），本表补上跨重启事实源。
--
-- config_key = 归一化 canonical 项目键 = ProjectService.normalizePathKey(
--              AutoMemPaths.findCanonicalGitRoot(会话项目根) ?? 会话项目根)
--   ⇒ 与 projects.path **不是同一个东西**：canonical 键是 git 仓根（子目录/worktree 折叠到仓根），
--     且归一含斜杠方向统一（Windows Path.toString 产反斜杠）与大小写折叠。
--     故**不复用 projects 表加列**（那会让「注册成子目录的项目」与「斜杠形式不同」两种情形
--     变成「批准了但不加载」的静默失效）—— 独立表，键语义自洽。
-- external_includes_approved      = Boolean 审批态（0/1）
-- external_includes_warning_shown = Boolean 已示警态（0/1；CC Dialog onDone 批准/拒绝**均**置位，
--                                   config.ts:123-131）
-- 时间统一 TEXT（ISO 8601），与 V13 mcp_needs_auth_cache 风格一致。
-- ⚠️ 编号必须 V74：V73 已被未合 master 的分支 feat/scene-fusion 占用（d0d2918），
--    同 version 73 会让 Flyway 启动即炸。
-- ===================================================================
CREATE TABLE claude_md_include_approval (
  config_key                       TEXT PRIMARY KEY,   -- 归一化 canonical 项目键（见上）
  external_includes_approved       INTEGER NOT NULL DEFAULT 0,
  external_includes_warning_shown  INTEGER NOT NULL DEFAULT 0,
  created_at                       TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at                       TEXT NOT NULL DEFAULT (datetime('now'))
);
