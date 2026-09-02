package com.nexusai.repository.settings.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

@Table("settings")
public class SettingsRecord {
    @Id private Integer id;          // always 1 (singleton)
    private String theme;
    private String fontSize;
    private String accent;
    private Boolean animationsEnabled;
    // [FN2] 字段改名 xxxModelId → xxxModelName（V28 RENAME 同步改列 *_model_id → *_model_name）：
    //   settings 存全名/裸名（providerName/modelName 或裸名），全名反查唯一路径。
    private String mainModelName;
    private String fastModelName;
    // [W2-2] 档位模型四字段（V25 建列 weak_model_id/medium_model_id/strong_model_id/subagent_model_id）
    //   CC 语义：weak=defaultHaiku（model.ts:131-138）、medium=defaultSonnet（model.ts:119-128）、
    //   strong=defaultOpus（model.ts:105-116）、subagent=CLAUDE_CODE_SUBAGENT_MODEL DB 承载（agent.ts:43-45）。
    private String weakModelName;
    private String mediumModelName;
    private String strongModelName;
    private String subagentModelName;
    // [W3-1] 自动压缩窗口（V25 建列 auto_compact_window；CC 语义 = autoCompact.ts:40-42
    //   CLAUDE_CODE_AUTO_COMPACT_WINDOW env 的 DB 承载：数字窗口，null = 未配置不参与收窄）
    private Integer autoCompactWindow;
    // [F1] max output tokens 有界 override（V27 建列 max_output_tokens；CC 语义 =
    //   envValidation.ts:9-38 CLAUDE_CODE_MAX_OUTPUT_TOKENS 迁移为 settings 配置：
    //   >0 生效、> 模型 upperLimit 封顶到 upperLimit、null 用模型默认）
    private Integer maxOutputTokens;
    // [F4] fallback 模型名（V27 原建列 fallback_model_id，V28 RENAME → fallback_model_name；
    //   FALLBACK_MODEL_ID env → settings 迁移）。
    //   CC 无此 env（withRetry.ts:337-351 按调用传入 options.fallbackModel），Java 原以 env 提供
    //   默认值属自建（ApiErrors.java:156-162）；未配置（null/blank）→ null → 529 快速失败不降级。
    private String fallbackModelName;
    // [TN1] 多模态/TTS/ASR 档位模型名（V28 建列 multimodal_model_name/tts_model_name/asr_model_name，
    //   用户拍板 B：tts/asr 分开承载；[FN2] 存全名/裸名，全名反查唯一路径）。
    //   使用先不使用：仅 settings 层接线（可配置可读取），LLM 多模态/TTS/ASR 调用未接线不上发。
    private String multimodalModelName;
    private String ttsModelName;
    private String asrModelName;
    // [C-05] auto 记忆目录路径（V34 建列 auto_memory_directory；OPD-CM5-C-05 拍板）
    //   autoMemoryDirectory 存 DB setting，前端"模型配置页-环境配置"可配置，对齐 CC 给默认值
    //   （未配置 null → AutoMemPaths per-project 默认计算）。读链 AutoMemPaths.
    //   readAutoMemoryDirectorySetting DB 列优先 → settings 文件回落。
    private String autoMemoryDirectory;
    // [C-05] auto 记忆总开关（V34 建列 auto_memory_enabled；OPD-CM5-C-05 拍板）
    //   autoMemoryEnabled 也存 DB + 前端，仅自动记忆打开才能配置路径（联动，前端控制）。
    //   未配置 null → 对齐 CC 默认（isAutoMemoryEnabled 5 级链末位默认 true）。
    private Boolean autoMemoryEnabled;
    // [V56 autoDream DB 主控 · 用户 2026-08-30 拍板] auto-dream 总开关（V56 建列
    //   auto_dream_enabled；CC original: settings.autoDreamEnabled（autoDream/config.ts:14））
    //   统一走 DB 表列主控 + 默认开 + 弃用 settings.json 文件承载（不再读/写 autoDreamEnabled
    //   文件键）。null = 未配置 → 门控回落默认 true（AutoDreamConsolidator.
    //   isAutoDreamEnabledBySettingsOrEnv）。
    //   命名：autoDreamEnabled 大写 D 映射 auto_dream_enabled（MyBatis-Flex camelCase→snake，
    //   同 V34 autoMemoryEnabled → auto_memory_enabled 先例）。
    private Boolean autoDreamEnabled;
    // [websearch-ccalign V37] WebSearch 配置入 DB（用户 2026-08-23 拍板 改动5 · 配置统一入 DB settings）：
    //   4 列全部由 WebSearchTool 读链消费（前端可配置），不再用 @Value 配置文件。
    //   websearch_engine ↔ websearchEngine：引擎选择（anysearch / duckduckgo），缺省 "anysearch"（Java 端兜底）。
    //   api_key ↔ apiKey：WebSearch 引擎通用 API key（websearch_engine 判断走哪个引擎；anysearch 作
    //     Bearer 认证；空 → WebSearchTool 内置默认 as_sk_a95d63d2e77de587a95b88dd9e0de48b 兜底）。
    //   proxy ↔ proxy：WebSearch 引擎通用 HTTP 代理（String host:port；空 → 直连）。
    //   websearch_use_small_model ↔ websearchUseSmallModel：对齐 CC tengu_plum_vx3 flag
    //     （WebSearchTool.ts:262-265）——true → 注释模型走 fast 档（getSmallFastModel），
    //     false/缺省 → 走主循环模型（mainLoopModel）。null = 缺省 false。
    //     [R1 返工] 字段为 websearchUseSmallModel（小写 s）：MyBatis-Flex camelCase→snake 转换
    //     websearchUseSmallModel → websearch_use_small_model 精确匹配 V37 列；若写成 webSearchUseSmallModel
    //     会映射到 web_search_use_small_model（列不存在 → select/update 列错位）。
    private String websearchEngine;
    private String apiKey;
    private String proxy;
    private Boolean websearchUseSmallModel;
    // [websearch-resid R-B] websearch_base_url ↔ websearchBaseUrl：anysearch API base URL（V38）。
    //   空 → WebSearchTool 读链兜底 AnySearchEngine.DEFAULT_BASE_URL（AnySearchEngine.java:44）。
    private String websearchBaseUrl;
    // [websearch-domaincheck V39] websearch_domain_check_url ↔ websearchDomainCheckUrl：域预检端点
    //   （用户 2026-08-23 拍板「api.anthropic.com 不要预检 预检google」）。空 → WebFetchTool 读链
    //   resolveSecurity 跳过预检（skipDomainCheck=true）；配了 → 预检该端点。命名小写 s 的
    //   websearchDomainCheckUrl——MyBatis-Flex camelCase→snake 精确映射 websearch_domain_check_url，
    //   同 :60-62 教训（webSearchDomainCheckUrl 会映射到 web_search_domain_check_url，列不存在）。
    private String websearchDomainCheckUrl;
    // [agent-swarms-setting V42] agent_swarms_enabled ↔ agentSwarmsEnabled：前端「环境配置」Agent Swarms
    //   开关（设置页开关 → TaskSystemConfig.isAgentSwarmsEnabled() 判定链 OR settings）。
    //   命名：MyBatis-Flex camelCase→snake 精确映射 agentSwarmsEnabled → agent_swarms_enabled
    //   （同 websearchUseSmallModel 小写 s 教训 :60-62 —— S 大写会映射错列）。
    private Boolean agentSwarmsEnabled;
    // [V44] permission_mode ↔ permissionMode：全局默认权限模式（settings 单例行）。
    //   CC original: settings.permissions.defaultMode（permissionSetup.ts:743-771）· 对齐
    //   V34 auto_memory_directory 列范式。null = 未配置 → 回落磁盘 settings.json defaultMode。
    //   命名：MyBatis-Flex camelCase→snake 精确映射 permissionMode → permission_mode
    //   （'mode' 全小写，无大小写边界风险；同 agentSwarmsEnabled S 大写教训反向先例）。
    private String permissionMode;
    // [V45] classifier_model ↔ classifierModel：Yolo 权限分类器模型（DB settings，前端可配置）。
    //   CC original: tengu_auto_mode_config.model（yoloClassifier.ts:1354-1356）/ getClassifierModel()
    //   （yoloClassifier.ts:1345-1361）。null/空白 = 未配置 → YoloClassifierImpl 兜底主循环模型
    //   （resolveFastModelName，Java getMainLoopModel 近似）。
    //   命名：classifierModel 大写 M 映射 classifier_model（正确 snake；同 websearchUseSmallModel
    //   小写 s 教训 :60-62 反向先例——classifierModel → classifier_model 精确匹配 V45 列）。
    private String classifierModel;
    // [V52 token-compact-fix B1-1] 压缩配置 12 列（settings 单行多列；全部可空，
    //   null = 回落 CC 原判定链 env/FeatureFlags/硬编码默认）。各列 CC original：
    //   auto_compact_enabled ↔ autoCompactEnabled：CC 全局配置默认 true（config.ts:594，
    //     消费于 autoCompact.ts:157）。null = 回落默认 true。
    //   reactive_compact_enabled ↔ reactiveCompactEnabled：CC isReactiveCompactEnabled
    //     （reactiveCompact.ts:43-44，内部含 DISABLE_COMPACT 检查）。null = 回落 FeatureFlags。
    //   context_collapse_enabled ↔ contextCollapseEnabled：CC isContextCollapseEnabled
    //     （contextCollapse/index.ts:45）。null = 回落 FeatureFlags.contextCollapse()。
    //   history_snip_enabled ↔ historySnipEnabled：CC feature('HISTORY_SNIP')（query.ts:115）
    //     + snipCompactIfNeeded 门控（query.ts:401-405）。null = 回落 ctx.featureFlags().historySnip()。
    //   sm_session_memory_enabled ↔ smSessionMemoryEnabled：CC tengu_session_memory flag
    //     （sessionMemoryCompact.ts:410-413）。null = 回落 false。
    //   sm_compact_enabled ↔ smCompactEnabled：CC tengu_sm_compact flag
    //     （sessionMemoryCompact.ts:414-416）。null = 回落 false。
    //   cached_microcompact_enabled ↔ cachedMicrocompactEnabled：CC 缓存微压缩路径
    //     （microCompact.ts:296-302）。[R5] CC 外部构建 DCE 恒关——null = 回落 false（不启用）。
    //   time_based_mc_enabled ↔ timeBasedMcEnabled：CC TimeBasedMCConfig.enabled 主开关
    //     （timeBasedMCConfig.ts:19-27，GrowthBook tengu_slate_heron；默认 false）。
    //   time_based_mc_gap_minutes ↔ timeBasedMcGapMinutes：CC gapThresholdMinutes
    //     （timeBasedMCConfig.ts:22-25，默认 60）。
    //   time_based_mc_keep_recent ↔ timeBasedMcKeepRecent：CC keepRecent
    //     （timeBasedMCConfig.ts:26-27，默认 5）。
    //   disable_compact ↔ disableCompact：CC DISABLE_COMPACT 一票否决（autoCompact.ts:148/:253、
    //     reactiveCompact.ts:44、compact/index.ts:9）。env 仍优先；DB 列 = env 的 DB 承载。
    //   disable_auto_compact ↔ disableAutoCompact：CC DISABLE_AUTO_COMPACT（autoCompact.ts:152，
    //     保留手动 /compact）。env 仍优先；DB 列 = env 的 DB 承载。
    //   命名：MyBatis-Flex camelCase→snake 精确映射（timeBasedMcXxx → time_based_mc_xxx，
    //   'Mc' 大 M 小 c；同 websearchUseSmallModel 小写 s 教训反向先例）。
    private Boolean autoCompactEnabled;
    private Boolean reactiveCompactEnabled;
    private Boolean contextCollapseEnabled;
    private Boolean historySnipEnabled;
    private Boolean smSessionMemoryEnabled;
    private Boolean smCompactEnabled;
    private Boolean cachedMicrocompactEnabled;
    private Boolean timeBasedMcEnabled;
    private Integer timeBasedMcGapMinutes;
    private Integer timeBasedMcKeepRecent;
    private Boolean disableCompact;
    private Boolean disableAutoCompact;
    // [V54 token-compact-fix B1-1 续] 压缩数值 11 列（settings 单行多列；全部 INTEGER 可空，
    //   null = 回落 CC 硬编码默认，不加 DEFAULT 与 V52 一致）。各列 CC original：
    //   cached_microcompact_trigger_threshold ↔ cachedMicrocompactTriggerThreshold：CC
    //     TRIGGER_THRESHOLD（cachedMicrocompact.ts:19，默认 10）。
    //   cached_microcompact_keep_recent ↔ cachedMicrocompactKeepRecent：CC KEEP_RECENT
    //     （cachedMicrocompact.ts:20，默认 5）。
    //   sm_min_tokens ↔ smMinTokens：CC SessionMemoryCompactConfig.minTokens
    //     （sessionMemoryCompact.ts:57-61，默认 10000）。
    //   sm_min_text_block_messages ↔ smMinTextBlockMessages：CC minTextBlockMessages
    //     （sessionMemoryCompact.ts:59，默认 5）。
    //   sm_max_tokens ↔ smMaxTokens：CC maxTokens（sessionMemoryCompact.ts:60，默认 40000）。
    //   sm_minimum_message_tokens_to_init ↔ smMinimumMessageTokensToInit：CC
    //     minimumMessageTokensToInit（sessionMemoryUtils.ts:33，默认 10000）。
    //   sm_minimum_tokens_between_update ↔ smMinimumTokensBetweenUpdate：CC
    //     minimumTokensBetweenUpdate（sessionMemoryUtils.ts:34，默认 5000）。
    //   sm_tool_calls_between_updates ↔ smToolCallsBetweenUpdates：CC toolCallsBetweenUpdates
    //     （sessionMemoryUtils.ts:35，默认 3）。
    //   max_consecutive_autocompact_failures ↔ maxConsecutiveAutocompactFailures：CC
    //     MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES（autoCompact.ts:70，默认 3）。
    //   max_ptl_retries ↔ maxPtlRetries：CC MAX_PTL_RETRIES（compact.ts:227，默认 3）。
    //   max_compact_streaming_retries ↔ maxCompactStreamingRetries：CC
    //     MAX_COMPACT_STREAMING_RETRIES（compact.ts:131，默认 2）。
    //   命名：MyBatis-Flex camelCase→snake 精确映射（cachedMicrocompactXxx → cached_microcompact_xxx，
    //   smMinTokens → sm_min_tokens，maxPtlRetries → max_ptl_retries；同 V52 教训反向先例）。
    private Integer cachedMicrocompactTriggerThreshold;
    private Integer cachedMicrocompactKeepRecent;
    private Integer smMinTokens;
    private Integer smMinTextBlockMessages;
    private Integer smMaxTokens;
    private Integer smMinimumMessageTokensToInit;
    private Integer smMinimumTokensBetweenUpdate;
    private Integer smToolCallsBetweenUpdates;
    private Integer maxConsecutiveAutocompactFailures;
    private Integer maxPtlRetries;
    private Integer maxCompactStreamingRetries;
    // [V55 fix-transcript-nudge] snip_nudge_threshold ↔ snipNudgeThreshold：snip nudge
    //   消息数阈值（CC original: SNIP_NUDGE_THRESHOLD = 30, snipCompact.ts:11）。
    //   null = 回落窗口自适应算法（SnipCompactor.resolveSnipNudgeThreshold 按
    //   effectiveWindow 档位：≥800k → 150；>600k → 100；≥400k → 60；其他 → 30）；
    //   >0 = DB 值直接覆盖。命名：snipNudgeThreshold 大写 T 映射 snip_nudge_threshold
    //   （MyBatis-Flex camelCase→snake 精确映射，同 V45 classifierModel 大写 M 反向先例）。
    private Integer snipNudgeThreshold;
    // [prompt-align G0-02 V56] 提示词对齐门控 12 列（settings 单行多列；全部可空，
    //   null = 回落 CC 原判定链 env/FeatureFlags/硬编码默认/既有判定类）。各列 CC original
    //   （Open-ClaudeCode 真源，行号以 worktree HEAD 6fe89de61 锚定，grep -n 复验）：
    //   task_reminder_enabled ↔ taskReminderEnabled：CC isTodoV2Enabled()
    //     （utils/tasks.ts:133-139）决定 Task V2 工具集启用 → task_reminder 系统提示附件注入门
    //     （utils/messages.ts:3680-3698）。null = 回落 TaskSystemConfig.isTodoV2Enabled()
    //     （经 MDC isInteractive 会话感知，决策 #65；保留现状不迁移，DocReflect R2）。
    //   deferred_tools_delta_enabled ↔ deferredToolsDeltaEnabled：CC
    //     utils/messages.ts:4178-4195 case 'deferred_tools_delta'。null = 回落当前 gate
    //     （OPD-H-06 默认关）。
    //   system_prompt_boundary_enabled ↔ systemPromptBoundaryEnabled：CC
    //     constants/prompts.ts:572-573（BOUNDARY MARKER @572 + shouldUseGlobalCacheScope
    //     门 @573）+ utils/betas.ts:227-233。null = 回落 GlobalCacheScope.shouldUseGlobalCacheScope()。
    //   proactive_enabled ↔ proactiveEnabled：CC utils/systemPrompt.ts:105
    //     (feature('PROACTIVE') || feature('KAIROS')) && isProactiveActive。null = 回落 false。
    //   coordinator_mode_enabled ↔ coordinatorModeEnabled：CC utils/systemPrompt.ts:63-65
    //     feature('COORDINATOR_MODE') && CLAUDE_CODE_COORDINATOR_MODE。null = 回落
    //     CoordinatorMode.isCoordinatorMode()。
    //   skill_search_intent_enabled ↔ skillSearchIntentEnabled：CC
    //     services/skillSearch/intentNormalize.ts:80 SKILL_SEARCH_INTENT_ENABLED==='1'。
    //     null = 回落 env（默认关）。
    //   scratchpad_enabled ↔ scratchpadEnabled：CC constants/prompts.ts:797-819
    //     isScratchpadEnabled()。Java 无 Statsig 门 → null = 回落 false。
    //   frc_enabled ↔ frcEnabled：CC constants/prompts.ts:821-839
    //     feature('CACHED_MICROCOMPACT') && getCachedMCConfigForFRC。null = 回落 false。
    //   agent_main_thread_enabled ↔ agentMainThreadEnabled：CC utils/systemPrompt.ts:77-83
    //     mainThreadAgentDefinition 分支。null = 回落 false。
    //   verify_plan_reminder_enabled ↔ verifyPlanReminderEnabled：CC utils/messages.ts:4240-4251
    //     case 'verify_plan_reminder'。null = 回落 false。
    //   language ↔ language：CC constants/prompts.ts:142-149 getLanguageSection。null = 不注入。
    //   output_style ↔ outputStyle：CC constants/prompts.ts:151-158 getOutputStyleSection。
    //     null = 不注入。
    //   命名：MyBatis-Flex camelCase→snake 精确映射（skillSearchIntentEnabled →
    //   skill_search_intent_enabled、agentMainThreadEnabled → agent_main_thread_enabled；
    //   同 websearchUseSmallModel 小写 s 教训反向先例）。
    private Boolean taskReminderEnabled;
    private Boolean deferredToolsDeltaEnabled;
    private Boolean systemPromptBoundaryEnabled;
    private Boolean proactiveEnabled;
    private Boolean coordinatorModeEnabled;
    private Boolean skillSearchIntentEnabled;
    private Boolean scratchpadEnabled;
    private Boolean frcEnabled;
    private Boolean agentMainThreadEnabled;
    private Boolean verifyPlanReminderEnabled;
    private String language;
    private String outputStyle;
    // [V61 插件配置 DB 化 · 2026-09-01 用户拍板] 插件双读配置（settings 单行多列；可空，
    //   null = 未配置回落原判定链，与 V60 一致）：
    //   enabled_plugins ↔ enabledPlugins：插件启停映射 Map<String,Boolean> 的 JSON 文本
    //     （TEXT 列，前端插件管理页写入）。null = 未配置 → InstalledPluginsManager 读链回落
    //     ConfigStorage（settings.json）→ 最后 CC settings（~/.claude/settings.json）双读
    //     （nexusai 优先 + 同 name nexusai 赢）。
    //   plugin_claude_fallback ↔ pluginClaudeFallback：插件双读开关（INTEGER 0/1）。true =
    //     enabledPlugins/installed 合并 nexusai + CC（~/.claude settings/plugins 兜底）；false =
    //     只读 nexusai 不回落实 CC。null = 未配置回落默认 true（原 yml
    //     nexusai.feature.plugin-claude-fallback:true 语义迁移 DB）。
    //   命名：MyBatis-Flex camelCase→snake 精确映射（enabledPlugins → enabled_plugins、
    //   pluginClaudeFallback → plugin_claude_fallback；同 websearchUseSmallModel 小写 s 教训）。
    private String enabledPlugins;
    private Boolean pluginClaudeFallback;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public String getFontSize() { return fontSize; }
    public void setFontSize(String fontSize) { this.fontSize = fontSize; }
    public String getAccent() { return accent; }
    public void setAccent(String accent) { this.accent = accent; }
    public Boolean getAnimationsEnabled() { return animationsEnabled; }
    public void setAnimationsEnabled(Boolean animationsEnabled) { this.animationsEnabled = animationsEnabled; }
    public String getMainModelName() { return mainModelName; }
    public void setMainModelName(String mainModelName) { this.mainModelName = mainModelName; }
    public String getFastModelName() { return fastModelName; }
    public void setFastModelName(String fastModelName) { this.fastModelName = fastModelName; }
    public String getWeakModelName() { return weakModelName; }
    public void setWeakModelName(String weakModelName) { this.weakModelName = weakModelName; }
    public String getMediumModelName() { return mediumModelName; }
    public void setMediumModelName(String mediumModelName) { this.mediumModelName = mediumModelName; }
    public String getStrongModelName() { return strongModelName; }
    public void setStrongModelName(String strongModelName) { this.strongModelName = strongModelName; }
    public String getSubagentModelName() { return subagentModelName; }
    public void setSubagentModelName(String subagentModelName) { this.subagentModelName = subagentModelName; }
    public Integer getAutoCompactWindow() { return autoCompactWindow; }
    public void setAutoCompactWindow(Integer autoCompactWindow) { this.autoCompactWindow = autoCompactWindow; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public String getFallbackModelName() { return fallbackModelName; }
    public void setFallbackModelName(String fallbackModelName) { this.fallbackModelName = fallbackModelName; }
    public String getMultimodalModelName() { return multimodalModelName; }
    public void setMultimodalModelName(String multimodalModelName) { this.multimodalModelName = multimodalModelName; }
    public String getTtsModelName() { return ttsModelName; }
    public void setTtsModelName(String ttsModelName) { this.ttsModelName = ttsModelName; }
    public String getAsrModelName() { return asrModelName; }
    public void setAsrModelName(String asrModelName) { this.asrModelName = asrModelName; }
    public String getAutoMemoryDirectory() { return autoMemoryDirectory; }
    public void setAutoMemoryDirectory(String autoMemoryDirectory) { this.autoMemoryDirectory = autoMemoryDirectory; }
    public Boolean getAutoMemoryEnabled() { return autoMemoryEnabled; }
    public void setAutoMemoryEnabled(Boolean autoMemoryEnabled) { this.autoMemoryEnabled = autoMemoryEnabled; }
    // [V56] auto_dream_enabled ↔ autoDreamEnabled（MyBatis-Flex snake↔camel 映射；null = 回落默认 true）
    public Boolean getAutoDreamEnabled() { return autoDreamEnabled; }
    public void setAutoDreamEnabled(Boolean autoDreamEnabled) { this.autoDreamEnabled = autoDreamEnabled; }
    // [websearch-ccalign V37] WebSearch 4 列 getter/setter（MyBatis-Flex snake↔camel 映射）
    public String getWebsearchEngine() { return websearchEngine; }
    public void setWebsearchEngine(String websearchEngine) { this.websearchEngine = websearchEngine; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getProxy() { return proxy; }
    public void setProxy(String proxy) { this.proxy = proxy; }
    public Boolean getWebsearchUseSmallModel() { return websearchUseSmallModel; }
    public void setWebsearchUseSmallModel(Boolean websearchUseSmallModel) { this.websearchUseSmallModel = websearchUseSmallModel; }
    // [websearch-resid R-B] websearch_base_url ↔ websearchBaseUrl（V38 列，MyBatis-Flex snake↔camel 映射）
    public String getWebsearchBaseUrl() { return websearchBaseUrl; }
    public void setWebsearchBaseUrl(String websearchBaseUrl) { this.websearchBaseUrl = websearchBaseUrl; }
    // [websearch-domaincheck V39] websearch_domain_check_url ↔ websearchDomainCheckUrl（MyBatis-Flex snake↔camel 映射）
    public String getWebsearchDomainCheckUrl() { return websearchDomainCheckUrl; }
    public void setWebsearchDomainCheckUrl(String websearchDomainCheckUrl) { this.websearchDomainCheckUrl = websearchDomainCheckUrl; }
    // [agent-swarms-setting V42] agent_swarms_enabled ↔ agentSwarmsEnabled（MyBatis-Flex snake↔camel 映射）
    public Boolean getAgentSwarmsEnabled() { return agentSwarmsEnabled; }
    public void setAgentSwarmsEnabled(Boolean agentSwarmsEnabled) { this.agentSwarmsEnabled = agentSwarmsEnabled; }
    // [V44] permission_mode ↔ permissionMode（MyBatis-Flex snake↔camel 映射）
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    // [V45] classifier_model ↔ classifierModel（MyBatis-Flex snake↔camel 映射）
    public String getClassifierModel() { return classifierModel; }
    public void setClassifierModel(String classifierModel) { this.classifierModel = classifierModel; }
    // [V52] 压缩配置 12 列 getter/setter（MyBatis-Flex snake↔camel 映射）
    public Boolean getAutoCompactEnabled() { return autoCompactEnabled; }
    public void setAutoCompactEnabled(Boolean autoCompactEnabled) { this.autoCompactEnabled = autoCompactEnabled; }
    public Boolean getReactiveCompactEnabled() { return reactiveCompactEnabled; }
    public void setReactiveCompactEnabled(Boolean reactiveCompactEnabled) { this.reactiveCompactEnabled = reactiveCompactEnabled; }
    public Boolean getContextCollapseEnabled() { return contextCollapseEnabled; }
    public void setContextCollapseEnabled(Boolean contextCollapseEnabled) { this.contextCollapseEnabled = contextCollapseEnabled; }
    public Boolean getHistorySnipEnabled() { return historySnipEnabled; }
    public void setHistorySnipEnabled(Boolean historySnipEnabled) { this.historySnipEnabled = historySnipEnabled; }
    public Boolean getSmSessionMemoryEnabled() { return smSessionMemoryEnabled; }
    public void setSmSessionMemoryEnabled(Boolean smSessionMemoryEnabled) { this.smSessionMemoryEnabled = smSessionMemoryEnabled; }
    public Boolean getSmCompactEnabled() { return smCompactEnabled; }
    public void setSmCompactEnabled(Boolean smCompactEnabled) { this.smCompactEnabled = smCompactEnabled; }
    public Boolean getCachedMicrocompactEnabled() { return cachedMicrocompactEnabled; }
    public void setCachedMicrocompactEnabled(Boolean cachedMicrocompactEnabled) { this.cachedMicrocompactEnabled = cachedMicrocompactEnabled; }
    public Boolean getTimeBasedMcEnabled() { return timeBasedMcEnabled; }
    public void setTimeBasedMcEnabled(Boolean timeBasedMcEnabled) { this.timeBasedMcEnabled = timeBasedMcEnabled; }
    public Integer getTimeBasedMcGapMinutes() { return timeBasedMcGapMinutes; }
    public void setTimeBasedMcGapMinutes(Integer timeBasedMcGapMinutes) { this.timeBasedMcGapMinutes = timeBasedMcGapMinutes; }
    public Integer getTimeBasedMcKeepRecent() { return timeBasedMcKeepRecent; }
    public void setTimeBasedMcKeepRecent(Integer timeBasedMcKeepRecent) { this.timeBasedMcKeepRecent = timeBasedMcKeepRecent; }
    public Boolean getDisableCompact() { return disableCompact; }
    public void setDisableCompact(Boolean disableCompact) { this.disableCompact = disableCompact; }
    public Boolean getDisableAutoCompact() { return disableAutoCompact; }
    public void setDisableAutoCompact(Boolean disableAutoCompact) { this.disableAutoCompact = disableAutoCompact; }
    // [V54] 压缩数值 11 列 getter/setter（MyBatis-Flex snake↔camel 映射）
    public Integer getCachedMicrocompactTriggerThreshold() { return cachedMicrocompactTriggerThreshold; }
    public void setCachedMicrocompactTriggerThreshold(Integer cachedMicrocompactTriggerThreshold) { this.cachedMicrocompactTriggerThreshold = cachedMicrocompactTriggerThreshold; }
    public Integer getCachedMicrocompactKeepRecent() { return cachedMicrocompactKeepRecent; }
    public void setCachedMicrocompactKeepRecent(Integer cachedMicrocompactKeepRecent) { this.cachedMicrocompactKeepRecent = cachedMicrocompactKeepRecent; }
    public Integer getSmMinTokens() { return smMinTokens; }
    public void setSmMinTokens(Integer smMinTokens) { this.smMinTokens = smMinTokens; }
    public Integer getSmMinTextBlockMessages() { return smMinTextBlockMessages; }
    public void setSmMinTextBlockMessages(Integer smMinTextBlockMessages) { this.smMinTextBlockMessages = smMinTextBlockMessages; }
    public Integer getSmMaxTokens() { return smMaxTokens; }
    public void setSmMaxTokens(Integer smMaxTokens) { this.smMaxTokens = smMaxTokens; }
    public Integer getSmMinimumMessageTokensToInit() { return smMinimumMessageTokensToInit; }
    public void setSmMinimumMessageTokensToInit(Integer smMinimumMessageTokensToInit) { this.smMinimumMessageTokensToInit = smMinimumMessageTokensToInit; }
    public Integer getSmMinimumTokensBetweenUpdate() { return smMinimumTokensBetweenUpdate; }
    public void setSmMinimumTokensBetweenUpdate(Integer smMinimumTokensBetweenUpdate) { this.smMinimumTokensBetweenUpdate = smMinimumTokensBetweenUpdate; }
    public Integer getSmToolCallsBetweenUpdates() { return smToolCallsBetweenUpdates; }
    public void setSmToolCallsBetweenUpdates(Integer smToolCallsBetweenUpdates) { this.smToolCallsBetweenUpdates = smToolCallsBetweenUpdates; }
    public Integer getMaxConsecutiveAutocompactFailures() { return maxConsecutiveAutocompactFailures; }
    public void setMaxConsecutiveAutocompactFailures(Integer maxConsecutiveAutocompactFailures) { this.maxConsecutiveAutocompactFailures = maxConsecutiveAutocompactFailures; }
    public Integer getMaxPtlRetries() { return maxPtlRetries; }
    public void setMaxPtlRetries(Integer maxPtlRetries) { this.maxPtlRetries = maxPtlRetries; }
    public Integer getMaxCompactStreamingRetries() { return maxCompactStreamingRetries; }
    public void setMaxCompactStreamingRetries(Integer maxCompactStreamingRetries) { this.maxCompactStreamingRetries = maxCompactStreamingRetries; }
    // [V55] snip_nudge_threshold ↔ snipNudgeThreshold（MyBatis-Flex snake↔camel 映射）
    public Integer getSnipNudgeThreshold() { return snipNudgeThreshold; }
    public void setSnipNudgeThreshold(Integer snipNudgeThreshold) { this.snipNudgeThreshold = snipNudgeThreshold; }
    // [prompt-align G0-02 V56] 提示词对齐门控 12 列 getter/setter（MyBatis-Flex snake↔camel 映射）
    public Boolean getTaskReminderEnabled() { return taskReminderEnabled; }
    public void setTaskReminderEnabled(Boolean taskReminderEnabled) { this.taskReminderEnabled = taskReminderEnabled; }
    public Boolean getDeferredToolsDeltaEnabled() { return deferredToolsDeltaEnabled; }
    public void setDeferredToolsDeltaEnabled(Boolean deferredToolsDeltaEnabled) { this.deferredToolsDeltaEnabled = deferredToolsDeltaEnabled; }
    public Boolean getSystemPromptBoundaryEnabled() { return systemPromptBoundaryEnabled; }
    public void setSystemPromptBoundaryEnabled(Boolean systemPromptBoundaryEnabled) { this.systemPromptBoundaryEnabled = systemPromptBoundaryEnabled; }
    public Boolean getProactiveEnabled() { return proactiveEnabled; }
    public void setProactiveEnabled(Boolean proactiveEnabled) { this.proactiveEnabled = proactiveEnabled; }
    public Boolean getCoordinatorModeEnabled() { return coordinatorModeEnabled; }
    public void setCoordinatorModeEnabled(Boolean coordinatorModeEnabled) { this.coordinatorModeEnabled = coordinatorModeEnabled; }
    public Boolean getSkillSearchIntentEnabled() { return skillSearchIntentEnabled; }
    public void setSkillSearchIntentEnabled(Boolean skillSearchIntentEnabled) { this.skillSearchIntentEnabled = skillSearchIntentEnabled; }
    public Boolean getScratchpadEnabled() { return scratchpadEnabled; }
    public void setScratchpadEnabled(Boolean scratchpadEnabled) { this.scratchpadEnabled = scratchpadEnabled; }
    public Boolean getFrcEnabled() { return frcEnabled; }
    public void setFrcEnabled(Boolean frcEnabled) { this.frcEnabled = frcEnabled; }
    public Boolean getAgentMainThreadEnabled() { return agentMainThreadEnabled; }
    public void setAgentMainThreadEnabled(Boolean agentMainThreadEnabled) { this.agentMainThreadEnabled = agentMainThreadEnabled; }
    public Boolean getVerifyPlanReminderEnabled() { return verifyPlanReminderEnabled; }
    public void setVerifyPlanReminderEnabled(Boolean verifyPlanReminderEnabled) { this.verifyPlanReminderEnabled = verifyPlanReminderEnabled; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getOutputStyle() { return outputStyle; }
    public void setOutputStyle(String outputStyle) { this.outputStyle = outputStyle; }
    // [V61] enabled_plugins ↔ enabledPlugins（MyBatis-Flex snake↔camel 映射）
    public String getEnabledPlugins() { return enabledPlugins; }
    public void setEnabledPlugins(String enabledPlugins) { this.enabledPlugins = enabledPlugins; }
    // [V61] plugin_claude_fallback ↔ pluginClaudeFallback（MyBatis-Flex snake↔camel 映射）
    public Boolean getPluginClaudeFallback() { return pluginClaudeFallback; }
    public void setPluginClaudeFallback(Boolean pluginClaudeFallback) { this.pluginClaudeFallback = pluginClaudeFallback; }
}
