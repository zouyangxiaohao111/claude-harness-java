-- ===================================================================
-- V52: settings 表新增压缩配置 12 列（压缩开关入 DB settings，B1/R4+R5）
-- [token-compact-fix B1-1] 压缩开关入 DB settings，前端「环境配置」可配，实时读源
--   （CompactSettingsResolver）消费。全部 INTEGER 可空，null = 回落 CC 原判定链
--   （env / FeatureFlags / 硬编码默认）。对齐 V42 agent_swarms_enabled 布尔列先例
--   （MyBatis-Flex 自动 snake_case↔camelCase 转换；INTEGER 0/1 ↔ Boolean）。
--
-- 各列 CC original（Open-ClaudeCode 真源，不信注释看行为）：
--   auto_compact_enabled ↔ autoCompactEnabled：
--     CC 全局配置默认 true（src/utils/config.ts:594，isEnvTruthy 链消费于
--     src/services/compact/autoCompact.ts:157 userConfig.autoCompactEnabled）。
--     null = 回落默认 true。
--   reactive_compact_enabled ↔ reactiveCompactEnabled：
--     CC isReactiveCompactEnabled（src/services/compact/reactiveCompact.ts:43-44，
--     内部含 DISABLE_COMPACT 检查）。null = 回落 FeatureFlags.reactiveCompact()。
--   context_collapse_enabled ↔ contextCollapseEnabled：
--     CC isContextCollapseEnabled（src/services/contextCollapse/index.ts:45）。
--     null = 回落 FeatureFlags.contextCollapse()。
--   history_snip_enabled ↔ historySnipEnabled：
--     CC feature('HISTORY_SNIP')（src/query.ts:115）+ snipCompactIfNeeded 门控
--     （src/query.ts:401-405）。null = 回落 ctx.featureFlags().historySnip()。
--   sm_session_memory_enabled ↔ smSessionMemoryEnabled：
--     CC tengu_session_memory flag（src/services/compact/sessionMemoryCompact.ts:410-413）。
--   sm_compact_enabled ↔ smCompactEnabled：
--     CC tengu_sm_compact flag（src/services/compact/sessionMemoryCompact.ts:414-416）。
--   cached_microcompact_enabled ↔ cachedMicrocompactEnabled：
--     CC 缓存微压缩路径（src/services/compact/microCompact.ts:296-302，cache editing API
--     删除 tool results，优先于常规 microcompact）。[R5] CC 外部构建 DCE 恒关——
--     DB 默认 null = 回落 false（不启用），前端可显式开。
--   time_based_mc_enabled ↔ timeBasedMcEnabled：
--     CC TimeBasedMCConfig.enabled 主开关（src/services/compact/timeBasedMCConfig.ts:19-27，
--     GrowthBook tengu_slate_heron；默认 false）。
--   time_based_mc_gap_minutes ↔ timeBasedMcGapMinutes：
--     CC TimeBasedMCConfig.gapThresholdMinutes（timeBasedMCConfig.ts:22-25，默认 60：
--     服务端 1h cache TTL 必然过期，不强迫 miss）。
--   time_based_mc_keep_recent ↔ timeBasedMcKeepRecent：
--     CC TimeBasedMCConfig.keepRecent（timeBasedMCConfig.ts:26-27，默认 5：保留最近
--     可压缩 tool results 数）。
--   disable_compact ↔ disableCompact：
--     CC DISABLE_COMPACT 一票否决（src/services/compact/autoCompact.ts:148 / :253、
--     src/services/compact/reactiveCompact.ts:44、src/commands/compact/index.ts:9）。
--     env 仍优先；DB 列为 env 的 DB 承载（前端可配）。
--   disable_auto_compact ↔ disableAutoCompact：
--     CC DISABLE_AUTO_COMPACT（src/services/compact/autoCompact.ts:152，保留手动
--     /compact）。env 仍优先；DB 列为 env 的 DB 承载。
--
-- 读链：SettingsService.get()/update() 读写 DB 列；CompactSettingsResolver 每次
--   selectOneById(1) 实时读（null = 回落原逻辑）。消费点见 B1-6。
-- ===================================================================
ALTER TABLE settings ADD COLUMN auto_compact_enabled INTEGER;
ALTER TABLE settings ADD COLUMN reactive_compact_enabled INTEGER;
ALTER TABLE settings ADD COLUMN context_collapse_enabled INTEGER;
ALTER TABLE settings ADD COLUMN history_snip_enabled INTEGER;
ALTER TABLE settings ADD COLUMN sm_session_memory_enabled INTEGER;
ALTER TABLE settings ADD COLUMN sm_compact_enabled INTEGER;
ALTER TABLE settings ADD COLUMN cached_microcompact_enabled INTEGER;
ALTER TABLE settings ADD COLUMN time_based_mc_enabled INTEGER;
ALTER TABLE settings ADD COLUMN time_based_mc_gap_minutes INTEGER;
ALTER TABLE settings ADD COLUMN time_based_mc_keep_recent INTEGER;
ALTER TABLE settings ADD COLUMN disable_compact INTEGER;
ALTER TABLE settings ADD COLUMN disable_auto_compact INTEGER;
