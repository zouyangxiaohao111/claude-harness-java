-- ===================================================================
-- V54: settings 表新增压缩数值 11 列（压缩阈值/限额入 DB settings，token-compact-fix B1 续）
--   [token-compact-fix B1-1] V52 已把 12 个压缩开关列入 DB settings；本迁移补齐压缩
--   数值型列（阈值/保留数/限额/重试次数），与开关同一 settings 单行多列，前端
--   「环境配置」可配，实时读源（CompactSettingsResolver）消费。全部 INTEGER 可空，
--   null = 回落 CC 原硬编码默认（常量值见下列 CC original 行号）。
--   对齐 V52 先例（MyBatis-Flex 自动 snake_case↔camelCase 转换；不加 DEFAULT，
--   null = 未配置回落硬编码默认，与 V52 一致）。
--
-- 各列 CC original（Open-ClaudeCode 真源，不信注释看行为）：
--   cached_microcompact_trigger_threshold ↔ cachedMicrocompactTriggerThreshold：
--     CC 缓存微压缩触发阈值（src/services/compact/cachedMicrocompact.ts:19
--     const TRIGGER_THRESHOLD = 10）。null = 回落 10。
--   cached_microcompact_keep_recent ↔ cachedMicrocompactKeepRecent：
--     CC 缓存微压缩保留最近可压缩 tool results 数（cachedMicrocompact.ts:20
--     const KEEP_RECENT = 5）。null = 回落 5。
--   sm_min_tokens ↔ smMinTokens：
--     CC SessionMemoryCompactConfig.minTokens（src/services/compact/sessionMemoryCompact.ts:57-61
--     DEFAULT_SM_COMPACT_CONFIG = { minTokens: 10_000, ... }）。null = 回落 10000。
--   sm_min_text_block_messages ↔ smMinTextBlockMessages：
--     CC minTextBlockMessages（sessionMemoryCompact.ts:59，默认 5：保留的最小含文本块
--     消息数）。null = 回落 5。
--   sm_max_tokens ↔ smMaxTokens：
--     CC maxTokens（sessionMemoryCompact.ts:60，默认 40_000：压缩后保留 token 硬上限）。
--     null = 回落 40000。
--   sm_minimum_message_tokens_to_init ↔ smMinimumMessageTokensToInit：
--     CC SessionMemoryConfig.minimumMessageTokensToInit（src/services/SessionMemory/
--     sessionMemoryUtils.ts:33，默认 10000：初始化 Session Memory 的最小消息 token 阈值）。
--     null = 回落 10000。
--   sm_minimum_tokens_between_update ↔ smMinimumTokensBetweenUpdate：
--     CC minimumTokensBetweenUpdate（sessionMemoryUtils.ts:34，默认 5000：两次内存更新间
--     最小 token 增长）。null = 回落 5000。
--   sm_tool_calls_between_updates ↔ smToolCallsBetweenUpdates：
--     CC toolCallsBetweenUpdates（sessionMemoryUtils.ts:35，默认 3：两次内存更新间最小
--     tool call 数）。null = 回落 3。
--   max_consecutive_autocompact_failures ↔ maxConsecutiveAutocompactFailures：
--     CC MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES（src/services/compact/autoCompact.ts:70，
--     默认 3：连续失败达该值停 auto compact 至窗口恢复）。null = 回落 3。
--   max_ptl_retries ↔ maxPtlRetries：
--     CC MAX_PTL_RETRIES（src/services/compact/compact.ts:227，默认 3：分段截断重试上限）。
--     null = 回落 3。
--   max_compact_streaming_retries ↔ maxCompactStreamingRetries：
--     CC MAX_COMPACT_STREAMING_RETRIES（src/services/compact/compact.ts:131，默认 2：
--     压缩流式调用重试上限）。null = 回落 2。
--
-- 读链：SettingsService.get()/update() 读写 DB 列；CompactSettingsResolver 每次
--   selectOneById(1) 实时读（null = 回落硬编码默认）。消费点见 token-compact-fix B1。
-- ===================================================================
ALTER TABLE settings ADD COLUMN cached_microcompact_trigger_threshold INTEGER;
ALTER TABLE settings ADD COLUMN cached_microcompact_keep_recent INTEGER;
ALTER TABLE settings ADD COLUMN sm_min_tokens INTEGER;
ALTER TABLE settings ADD COLUMN sm_min_text_block_messages INTEGER;
ALTER TABLE settings ADD COLUMN sm_max_tokens INTEGER;
ALTER TABLE settings ADD COLUMN sm_minimum_message_tokens_to_init INTEGER;
ALTER TABLE settings ADD COLUMN sm_minimum_tokens_between_update INTEGER;
ALTER TABLE settings ADD COLUMN sm_tool_calls_between_updates INTEGER;
ALTER TABLE settings ADD COLUMN max_consecutive_autocompact_failures INTEGER;
ALTER TABLE settings ADD COLUMN max_ptl_retries INTEGER;
ALTER TABLE settings ADD COLUMN max_compact_streaming_retries INTEGER;
