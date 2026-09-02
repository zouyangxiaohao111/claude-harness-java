package com.nexusai.model.settings.dto;

import java.util.Map;

/** 响应 / 请求：用户全局设置（singleton）。
 *  <p>[C-05] autoMemoryEnabled 由 settings.json 文件承载键升级为 DB 列承载（V34 建列
 *  auto_memory_enabled，OPD-CM5-C-05 拍板「autoMemoryEnabled 也存 DB + 前端」）：读侧
 *  BundledSkillEnabledGates.readAutoMemoryEnabledSetting 先查 DB 列（前端配置值优先），无则
 *  回落 settings.json 三源真值；写侧 SettingsService 落 DB 列 + writeAutoMemoryToggles 落
 *  {configHome}/settings.json（对齐 CC updateSettingsForSource('userSettings') 双写）。
 *  <p>[V56 · 用户 2026-08-30 拍板] autoDreamEnabled 统一走 DB 表列（V56 建列
 *  auto_dream_enabled）+ 默认开 + 弃用 settings.json 文件承载：读侧 SettingsService.toDto /
 *  BundledSkillEnabledGates.readAutoDreamEnabledSetting 仅读 DB 列；写侧 SettingsService /
 *  writeAutoMemoryToggles 仅落 DB 列（不再读写 settings.json 的 autoDreamEnabled 键）。
 *  <p>[C-05] autoMemoryDirectory 为 DB 列承载（V34 建列 auto_memory_directory）：前端
 *  "模型配置页-环境配置"可配置，对齐 CC 给默认值（未配置 null → AutoMemPaths per-project 默认）。 */
public record SettingsDto(
    Theme theme,
    FontSize fontSize,
    String accent,
    Boolean animationsEnabled,
    String mainModelName,
    String fastModelName,
    // [W2-2] 档位模型四字段（可空）· CC 语义：weak=defaultHaiku（model.ts:131-138）、
    //   medium=defaultSonnet（model.ts:119-128）、strong=defaultOpus（model.ts:105-116）、
    //   subagent=CLAUDE_CODE_SUBAGENT_MODEL DB 承载（agent.ts:43-45，W4 清理 env 路后为唯一来源）
    //   [FN2] 字段改名 xxxModelName：settings 存全名/裸名（前端 ModelPickerModal 传 providerName/modelName）。
    String weakModelName,
    String mediumModelName,
    String strongModelName,
    String subagentModelName,
    // [F1] max output tokens 有界 override（V27 列 max_output_tokens，可空；CC 语义 =
    //   envValidation.ts:9-38 CLAUDE_CODE_MAX_OUTPUT_TOKENS 迁移为 settings 配置：
    //   >0 生效、> 模型 upperLimit 封顶到 upperLimit、null 用模型默认）
    Integer maxOutputTokens,
    // [F4] 回落模型（V27 原列 fallback_model_id，V28 RENAME → fallback_model_name，可空）
    String fallbackModelName,
    // [TN1] 多模态/TTS/ASR 档位模型（V28 建列 multimodal_model_name/tts_model_name/asr_model_name，
    //   可空；用户拍板 B：tts/asr 分开承载）· [FN2] 存全名/裸名（前端 ModelPickerModal 传
    //   providerName/modelName）。使用先不使用：仅 settings 层接线（可配置可读取），
    //   LLM 多模态/TTS/ASR 调用未接线不上发。
    String multimodalModelName,
    String ttsModelName,
    String asrModelName,
    // [W3-1] 压缩窗口上限（V26 列 auto_compact_window，可空；CC 语义 = autoCompact.ts:40-46
    //   CLAUDE_CODE_AUTO_COMPACT_WINDOW 迁移为 settings：>0 时 Math.min(模型窗口, 值) 只缩不扩、
    //   null = 不限制。CompactThresholdSystem 直读 DB 列，本字段经 settings API 暴露/写入）
    Integer autoCompactWindow,
    // [C-05] auto 记忆目录路径（V34 列 auto_memory_directory，DB 承载；前端"模型配置页-环境配置"
    //   可配置，对齐 CC 给默认值；null = 未配置 → AutoMemPaths per-project 默认计算）
    String autoMemoryDirectory,
    // [C-05] auto 记忆总开关（V34 列 auto_memory_enabled，DB 承载 + settings.json 双写；null = 不覆盖）
    Boolean autoMemoryEnabled,
    // [V56 · 用户 2026-08-30 拍板] auto-dream 总开关（V56 列 auto_dream_enabled，DB 承载；
    //   默认开——null = 门控回落默认 true（AutoDreamConsolidator.isAutoDreamEnabledBySettingsOrEnv）；
    //   弃用 settings.json 文件承载键，不再读写文件）
    Boolean autoDreamEnabled,
    // [websearch-ccalign V37] WebSearch 配置（DB 承载，前端可配置；null = 不覆盖/兜底默认）。
    //   websearchEngine：引擎选择（anysearch/duckduckgo，缺省 "anysearch" Java 端兜底）。
    //   apiKey：WebSearch 引擎通用 API key（anysearch Bearer；空 → WebSearchTool 内置默认兜底）。
    //   proxy：WebSearch 引擎通用 HTTP 代理（String host:port；空 → 直连）。
    //   websearchUseSmallModel：对齐 CC tengu_plum_vx3（WebSearchTool.ts:262-265）——true → fast 档
    //     注释模型；false/缺省 → 主循环模型。
    //     [R1 返工] 组件名 websearchUseSmallModel（小写 s），匹配 SettingsRecord 字段
    //     websearchUseSmallModel → V37 列 websearch_use_small_model（MyBatis-Flex snake 映射）。
    String websearchEngine,
    String apiKey,
    String proxy,
    Boolean websearchUseSmallModel,
    // [websearch-resid R-B] anysearch API base URL（V38 列 websearch_base_url，DB 承载；null = 兜底默认
    //   AnySearchEngine.DEFAULT_BASE_URL）。WebSearchTool readBaseUrl 消费（空 → 默认）。
    String websearchBaseUrl,
    // [websearch-domaincheck V39] 域预检端点（V39 列 websearch_domain_check_url，DB 承载；null = 跳过预检，
    //   skipDomainCheck=true，不依赖 api.anthropic.com）。WebFetchTool.resolveSecurity 消费——配了 → 预检
    //   该端点（can_fetch JSON 语义不变）；空 → 跳过（用户 2026-08-23 拍板「api.anthropic.com 不要预检」）。
    String websearchDomainCheckUrl,
    // [agent-swarms-setting V42] Agent Swarms 开关（V42 列 agent_swarms_enabled，DB 承载；
    //   null = 未配置 → TaskSystemConfig 静态覆盖标志不生效，维持 CC 原判定链）
    Boolean agentSwarmsEnabled,
    // [V44] 全局默认权限模式（V44 列 settings.permission_mode，DB 承载，可空）· CC original:
    //   settings.permissions.defaultMode（permissionSetup.ts:743-771）；null = 未配置 →
    //   回落 settings.json defaultMode（InitialPermissionModeSource 磁盘三源 user<project<local）→
    //   default。前端 AppSettings types.ts:347 已建模，GET /api/v1/settings 期望返回该字段。
    String permissionMode,
    // [V45] Yolo 权限分类器模型（V45 列 settings.classifier_model，DB 承载，可空）· CC original:
    //   tengu_auto_mode_config.model（yoloClassifier.ts:1354-1356）/ getClassifierModel()（:1345-1361）。
    //   null = 未配置 → YoloClassifierImpl 兜底主循环模型（yml 覆写 nexusai.classifier.model 仍优先）。
    String classifierModel,
    // [V52 token-compact-fix B1-1] 压缩配置 12 列（V52 建列，DB 承载，前端「环境配置」可配；
    //   全部可空 = 回落 CC 原判定链）。各列 CC original（详见 SettingsRecord.java V52 JavaDoc）：
    //   autoCompactEnabled → CC config.ts:594；reactiveCompactEnabled → CC reactiveCompact.ts:43-44；
    //   contextCollapseEnabled → CC contextCollapse/index.ts:45；historySnipEnabled → CC query.ts:115/401-405；
    //   smSessionMemoryEnabled → CC sessionMemoryCompact.ts:410-413；smCompactEnabled → CC sessionMemoryCompact.ts:414-416；
    //   cachedMicrocompactEnabled → CC microCompact.ts:296-302（[R5] null = 回落 false）；
    //   timeBasedMcEnabled → CC timeBasedMCConfig.ts:19-27；timeBasedMcGapMinutes → CC timeBasedMCConfig.ts:22-25（默认 60）；
    //   timeBasedMcKeepRecent → CC timeBasedMCConfig.ts:26-27（默认 5）；
    //   disableCompact → CC DISABLE_COMPACT（autoCompact.ts:148/253）；disableAutoCompact → CC DISABLE_AUTO_COMPACT（autoCompact.ts:152）。
    //   null = 不覆盖（回落 env/FeatureFlags/硬编码默认）。
    Boolean autoCompactEnabled,
    Boolean reactiveCompactEnabled,
    Boolean contextCollapseEnabled,
    Boolean historySnipEnabled,
    Boolean smSessionMemoryEnabled,
    Boolean smCompactEnabled,
    Boolean cachedMicrocompactEnabled,
    Boolean timeBasedMcEnabled,
    Integer timeBasedMcGapMinutes,
    Integer timeBasedMcKeepRecent,
    Boolean disableCompact,
    Boolean disableAutoCompact,
    // [V54 token-compact-fix B1-1 续] 压缩数值 11 列（V54 建列，DB 承载，前端「环境配置」可配；
    //   全部可空 = 回落 CC 硬编码默认）。各列 CC original（详见 SettingsRecord.java V54 JavaDoc）：
    //   cachedMicrocompactTriggerThreshold → CC cachedMicrocompact.ts:19（默认 10）；
    //   cachedMicrocompactKeepRecent → CC cachedMicrocompact.ts:20（默认 5）；
    //   smMinTokens → CC sessionMemoryCompact.ts:57-61（默认 10000）；
    //   smMinTextBlockMessages → CC sessionMemoryCompact.ts:59（默认 5）；
    //   smMaxTokens → CC sessionMemoryCompact.ts:60（默认 40000）；
    //   smMinimumMessageTokensToInit → CC sessionMemoryUtils.ts:33（默认 10000）；
    //   smMinimumTokensBetweenUpdate → CC sessionMemoryUtils.ts:34（默认 5000）；
    //   smToolCallsBetweenUpdates → CC sessionMemoryUtils.ts:35（默认 3）；
    //   maxConsecutiveAutocompactFailures → CC autoCompact.ts:70（默认 3）；
    //   maxPtlRetries → CC compact.ts:227（默认 3）；
    //   maxCompactStreamingRetries → CC compact.ts:131（默认 2）。
    //   null = 不覆盖（回落硬编码默认）。
    Integer cachedMicrocompactTriggerThreshold,
    Integer cachedMicrocompactKeepRecent,
    Integer smMinTokens,
    Integer smMinTextBlockMessages,
    Integer smMaxTokens,
    Integer smMinimumMessageTokensToInit,
    Integer smMinimumTokensBetweenUpdate,
    Integer smToolCallsBetweenUpdates,
    Integer maxConsecutiveAutocompactFailures,
    Integer maxPtlRetries,
    Integer maxCompactStreamingRetries,
    // [V55 fix-transcript-nudge] snip nudge 消息数阈值（V55 列 snip_nudge_threshold，DB 承载；
    //   前端「环境配置」可配；null = 回落窗口自适应算法——SnipCompactor.resolveSnipNudgeThreshold
    //   按 effectiveWindow 档位：≥800k → 150；>600k → 100；≥400k → 60；其他 → 30（CC 默认））·
    //   CC original: SNIP_NUDGE_THRESHOLD（snipCompact.ts:11，默认 30）。>0 = DB 值直接覆盖窗口自适应。
    //   消费点：CompactSettingsResolver.snipNudgeThreshold() 实时读 + AgentLoopContext nudge 门。
    Integer snipNudgeThreshold,
    // [prompt-align G0-02 V56] 提示词对齐门控 12 列（V56 建列，DB 承载，前端「环境配置」可配；
    //   全部可空 = 回落 CC 原判定链）。各列 CC original（详见 SettingsRecord.java V56 JavaDoc）：
    //   taskReminderEnabled → CC isTodoV2Enabled（utils/tasks.ts:133-139）+ task_reminder
    //     附件（utils/messages.ts:3680-3698）；deferredToolsDeltaEnabled → CC messages.ts:4178-4195；
    //   systemPromptBoundaryEnabled → CC constants/prompts.ts:572-573（BOUNDARY MARKER
    //     @572 + shouldUseGlobalCacheScope 门 @573）+ utils/betas.ts:227-233；
    //   proactiveEnabled → CC utils/systemPrompt.ts:105；coordinatorModeEnabled → CC
    //     systemPrompt.ts:63-65；skillSearchIntentEnabled → CC intentNormalize.ts:80；
    //   scratchpadEnabled → CC constants/prompts.ts:797-819；frcEnabled → CC prompts.ts:821-839；
    //   agentMainThreadEnabled → CC systemPrompt.ts:77-83；verifyPlanReminderEnabled → CC
    //     messages.ts:4240-4251；language → CC prompts.ts:142-149；outputStyle → CC prompts.ts:151-158。
    //   null = 不覆盖（回落 env/FeatureFlags/硬编码默认/既有判定类）。消费点经
    //   PromptAlignSettingsResolver 实时读，写库即生效。
    Boolean taskReminderEnabled,
    Boolean deferredToolsDeltaEnabled,
    Boolean systemPromptBoundaryEnabled,
    Boolean proactiveEnabled,
    Boolean coordinatorModeEnabled,
    Boolean skillSearchIntentEnabled,
    Boolean scratchpadEnabled,
    Boolean frcEnabled,
    Boolean agentMainThreadEnabled,
    Boolean verifyPlanReminderEnabled,
    String language,
    String outputStyle,
    // [V61 插件配置 DB 化 · 2026-09-01 用户拍板] 插件双读配置（V61 建列，DB 承载，前端可配；
    //   可空 = 回落原判定链）：
    //   enabledPlugins：插件启停映射（Map<String,Boolean>，settings.enabled_plugins JSON 文本，
    //     前端插件管理页写入；null = 未配置 → InstalledPluginsManager 读链回落 ConfigStorage
    //     settings.json → CC settings（~/.claude/settings.json）双读，nexusai 优先 + 同 name nexusai 赢）。
    //   pluginClaudeFallback：插件双读开关（settings.plugin_claude_fallback 0/1；null = 未配置 →
    //     插件双读回落默认 true，原 yml nexusai.feature.plugin-claude-fallback:true 语义迁移 DB）。
    Map<String, Boolean> enabledPlugins,
    Boolean pluginClaudeFallback
) {}
