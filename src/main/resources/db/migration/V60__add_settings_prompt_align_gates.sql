-- ===================================================================
-- V56: settings 表新增提示词对齐门控 12 列（提示词门控入 DB settings，
--   前端「环境配置」可配 + 实时读源 PromptAlignSettingsResolver 消费）
-- [prompt-align G0-01] 全部不加 DEFAULT，null = 未配置回落原判定链
--   （env / FeatureFlags / 硬编码默认 / 既有判定类），零行为变化。
--   对齐 V52/V55 先例（MyBatis-Flex 自动 snake_case↔camelCase 转换；
--   camelCase 字段 xxxEnabled → 列 xxx_enabled；不加 DEFAULT，null =
--   未配置回落原链，与 V52/V54/V55 一致）。
--
-- 各列 CC original（Open-ClaudeCode 真源，不信注释看行为；行号以
--   worktree HEAD 6fe89de61 锚定，均 grep -n 复验）：
--   task_reminder_enabled ↔ taskReminderEnabled：
--     CC isTodoV2Enabled()（utils/tasks.ts:133-139）决定 Task V2 工具集启用
--     → task_reminder 系统提示附件注入门（utils/messages.ts:3680-3698
--     case 'task_reminder'，先判 !isTodoV2Enabled() 直接返回 []）。
--     null = 回落 TaskSystemConfig.isTodoV2Enabled()（经 MDC isInteractive
--     会话感知，决策 #65：Web 请求默认交互 → V2 开；cron/后台默认非交互 →
--     V1；保留现状不迁移，见 DocReflect R2）。
--   deferred_tools_delta_enabled ↔ deferredToolsDeltaEnabled：
--     CC utils/messages.ts:4178-4195 case 'deferred_tools_delta'（deferred
--     工具新增/移除 delta 系统提示附件注入）。null = 回落当前 gate
--     （OPD-H-06 默认关）。
--   system_prompt_boundary_enabled ↔ systemPromptBoundaryEnabled：
--     CC constants/prompts.ts:572（BOUNDARY MARKER - DO NOT MOVE OR REMOVE）+ 门@573
--     shouldUseGlobalCacheScope()（utils/betas.ts:227-233 = getAPIProvider()===
--     'firstParty' && !CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS → 注入
--     SYSTEM_PROMPT_DYNAMIC_BOUNDARY）。null = 回落
--     GlobalCacheScope.shouldUseGlobalCacheScope()（firstParty 判定链）。
--   proactive_enabled ↔ proactiveEnabled：
--     CC utils/systemPrompt.ts:105 (feature('PROACTIVE') || feature('KAIROS'))
--     && isProactiveActive（主线程 agent 时自定义 agent 指令追加模式，
--     非替换默认 prompt）。null = 回落 false。
--   coordinator_mode_enabled ↔ coordinatorModeEnabled：
--     CC utils/systemPrompt.ts:63-65 feature('COORDINATOR_MODE') &&
--     isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE) && !mainThreadAgentDefinition
--     → 走 coordinator 专用 prompt。null = 回落 CoordinatorMode.
--     isCoordinatorMode()（feature + env 双真）。
--   skill_search_intent_enabled ↔ skillSearchIntentEnabled：
--     CC services/skillSearch/intentNormalize.ts:80 process.env.
--     SKILL_SEARCH_INTENT_ENABLED === '1'（查询意图归一化，TF-IDF 见英文任务词）。
--     null = 回落 env（默认关）。
--   scratchpad_enabled ↔ scratchpadEnabled：
--     CC constants/prompts.ts:797-819 getScratchpadInstructions() 内
--     isScratchpadEnabled()（scratchpad 目录使用指令，非空才注入）。Java 无
--     Statsig 门 → null = 回落 false。
--   frc_enabled ↔ frcEnabled：
--     CC constants/prompts.ts:821-839 getFunctionResultClearingSection()
--     feature('CACHED_MICROCOMPACT') && getCachedMCConfigForFRC（Function
--     Result Clearing 段，工具结果自动清场提示）。null = 回落 false。
--   agent_main_thread_enabled ↔ agentMainThreadEnabled：
--     CC utils/systemPrompt.ts:77-83 mainThreadAgentDefinition 分支（主线程
--     agent 定义非空 → 其 getSystemPrompt() 作为系统提示而非默认段）。
--     null = 回落 false。
--   verify_plan_reminder_enabled ↔ verifyPlanReminderEnabled：
--     CC utils/messages.ts:4240-4251 case 'verify_plan_reminder'（
--     CLAUDE_CODE_VERIFY_PLAN==='true' → VerifyPlanExecution 校验提示注入）。
--     null = 回落 false。
--   language ↔ language：
--     CC constants/prompts.ts:142-149 getLanguageSection(languagePreference)
--     （# Language 段，空 preference → null 不注入）。null = 不注入。
--   output_style ↔ outputStyle：
--     CC constants/prompts.ts:151-158 getOutputStyleSection(outputStyleConfig)
--     （# Output Style 段，null 配置 → 不注入）。null = 不注入。
--
-- 读链：SettingsService.get()/update() 读写 DB 列（merge 策略，null 不覆盖）；
--   PromptAlignSettingsResolver 每次 selectOneById(1) 实时读（10 Boolean +
--   2 String 方法，null = 回落原逻辑）。消费点见后续批次 A/G（UP/CTX 域
--   提示词对齐）。
-- ===================================================================
ALTER TABLE settings ADD COLUMN task_reminder_enabled INTEGER;
ALTER TABLE settings ADD COLUMN deferred_tools_delta_enabled INTEGER;
ALTER TABLE settings ADD COLUMN system_prompt_boundary_enabled INTEGER;
ALTER TABLE settings ADD COLUMN proactive_enabled INTEGER;
ALTER TABLE settings ADD COLUMN coordinator_mode_enabled INTEGER;
ALTER TABLE settings ADD COLUMN skill_search_intent_enabled INTEGER;
ALTER TABLE settings ADD COLUMN scratchpad_enabled INTEGER;
ALTER TABLE settings ADD COLUMN frc_enabled INTEGER;
ALTER TABLE settings ADD COLUMN agent_main_thread_enabled INTEGER;
ALTER TABLE settings ADD COLUMN verify_plan_reminder_enabled INTEGER;
ALTER TABLE settings ADD COLUMN language TEXT;
ALTER TABLE settings ADD COLUMN output_style TEXT;
