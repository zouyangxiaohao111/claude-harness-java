package com.nexusai.repository.session.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

@Table("sessions")
public class SessionRecord {
    @Id private String id;
    private String modelTag;
    private String modelName;
    private String title;
    private String time;
    private String sessionGroup;
    private String tabId;
    private String mainProjectId;
    private Integer messageCount;
    private String createdAt;
    private String updatedAt;
    /**
     * 会话级 conversationId · CC original: conversationId（REPL.tsx:1481 初始 randomUUID()，
     * :1831 load session 后 setConversationId(sessionId)，:4971 partial 压缩后
     * setConversationId(randomUUID())）。V6 前为 NULL=初始未定（前端可用 sessionId 兜底），
     * partial 压缩后落新 randomUUID()。
     */
    private String conversationId;
    /**
     * 会话级 effort 档位 · CC original: appState.effortValue（effort.tsx ApplyEffortAndClose
     * setAppState，effort.ts:152-167 resolveAppliedEffort = env ?? appState.effortValue ??
     * getDefaultEffortForModel）的 DB 承载（V31 建列 effort_level）。
     * 用户拍板（multi-session-vs-cc-single-session）：Web 多会话 → effort 会话级，/effort 写本会话。
     * 可空：null = 该会话未显式设置，effort 注入走模型默认 / env override（getDisplayedEffortLevel
     * 兜底 'high'，effort.ts:178）。
     */
    private String effortLevel;
    /** ultracode 模式会话级开关（V32 列 ultracode_enabled，0/1 可空）· ultracode = xhigh effort + workflows 编排启用。 */
    private Integer ultracodeEnabled;
    /**
     * bare（精简）模式会话级开关（V33 列 bare_mode，0/1 可空）· CC original: isBareMode()
     * （envUtils.ts:60-65 CLAUDE_CODE_SIMPLE / --bare argv）的 Web 会话级等价（用户 2026-08-23
     * 拍板：bareMode 随会话走）。可空：null = 该会话未显式设置，判定回落 env CLAUDE_CODE_SIMPLE /
     * nexusai.memory.bare-mode → 默认 false（MemoryBareModeConfig.isBareMode(String) 会话级重载）。
     */
    private Integer bareMode;
    /**
     * 会话级禁用工具集合（V34 列 disabled_tools，TEXT JSON 数组，如 ["Bash","WebSearch"]）·
     * 待前端对接 §29「点 × 临时禁用 → 该工具从模型 schema 移除，会话内生效」——CC 无内置机制
     * （G24 用户拍板），Java 端复刻 CC blanket deny 的 schema 阶段剔除效果（tools.ts:262-269）。
     * 可空：null = 未禁用；空集合 → 存 null（读回空集合）。JSON 序列化走 fastjson2
     * （SessionService.get/setDisabledTools，对齐 MessageService serializeMap/parseMap 通道）。
     */
    private String disabledTools;
    /**
     * 会话级 swarm teamContext（V39 列 team_context，TEXT JSON 对象，可空）· CC original:
     * appState.teamContext（TeamCreateTool.ts:201-216 setAppState(teamContext)，session-global 稳定态）。
     * Web 多会话 → 会话列承载（multi-session-vs-cc-single-session 铁律，effort_level V31 / bare_mode
     * V33 / disabled_tools V35 同款范式）。结构 {teamName, teamFilePath, leadAgentId,
     * teammates:{[leadAgentId]:{name,agentType,cwd,...}}}（与 TeamCreateTool.APPSTATE_TEAM_CONTEXT
     * 常量键同构）。可空：null = 该会话未建 team；TeamCreateTool 写 / TeamDeleteTool 清 /
     * SendMessageTool 读，跨工具/跨回合/重开会话存活。
     */
    private String teamContext;
    /**
     * 会话级 todo 桶（V43 列 todos，TEXT JSON 对象，可空）· CC original: appState.todos
     * {todoKey: TodoItem[]}（TodoWriteTool.ts:65-94 的 React useState 内存态）。Web 多会话 →
     * 会话列承载（multi-session-vs-cc-single-session 铁律，effort_level V31 / bare_mode V33 /
     * disabled_tools V35 / team_context V40 同款范式）。规范形 {todoKey:[{content,status,
     * activeForm}]}，status 小写 pending|in_progress|completed（CC types.ts:4-6 值域）。
     * 可空：null = 该会话从未 TodoWrite（读侧 skip / DTO null / 前端隐藏面板）。
     * 读写解析放 TodoWriteTool.todosMapToJson/todosJsonToMap（Jackson 规范形）+
     * SessionService.parseTodos（fastjson2 解析态）——本列仅存 JSON 串，解析在读侧
     * （照抄 disabledTools L51 先例：String 存 JSON，解析在读侧）。
     */
    private String todos;
    /**
     * 会话级权限模式覆盖（V44 列 permission_mode，可空）· CC original:
     * settings.permissions.defaultMode（permissionSetup.ts:743-771）的会话级等价——
     * Web 多会话无 appState 单例（multi-session-vs-cc-single-session 铁律，effort_level
     * V31 / bare_mode V33 同款会话列范式）。可空：null = 该会话未显式覆盖 → 回落全局
     * settings.permission_mode → 磁盘 settings.json defaultMode → default。
     * ChatService 解析 effectiveMode 读本列（per-call ?? 会话 override 共享 CLI 槽）。
     */
    private String permissionMode;
    /**
     * 会话累计花费（元）· CC original: {@code total_cost_usd}（result 事件 / state.ts:704-710），
     * 值用人民币元（用户拍板：字段名对齐 CC、不换算 USD）。V48 列 total_cost_yuan。
     * 可空：null = 该会话从未计费（restore 时按零累计）。
     * 读写：CostTracker.saveCurrentSessionCosts 写 / restoreCostStateForSession 读
     * （multi-session-vs-cc-single-session 铁律：Web 多会话 → 会话列承载）。
     */
    private Double totalCostYuan;
    /**
     * 会话累计按模型 8 字段 JSON 快照 · CC original: project config {@code lastModelUsage}
     * （state.ts:704-710 / CostTracker.ModelUsage cost-tracker.ts:29-38）。V48 列 model_usage_json。
     * String 存 JSON（camelCase 8 字段 Map），解析在读侧（照抄 disabledTools L51 / todos L73 先例）。
     * 可空：null = 该会话从未计费（restore 时零累计）。
     */
    private String modelUsageJson;
    /**
     * 会话级 loop 模式系统提示覆盖（V57 列 loop_mode_override，TEXT 可空）· CC original:
     * overrideSystemPrompt（systemPrompt.ts:56-58 早退——override 非空 → 直接
     * asSystemPrompt([overrideSystemPrompt]) 替换全部系统提示，--loop CLI 运行模式承载）。
     * Web 多会话 → 会话列承载（multi-session-vs-cc-single-session 铁律，effort_level V31 /
     * bare_mode V33 / todos V43 同款会话列范式；若入全局 settings(id=1) 跨会话泄漏）。
     * 可空：null = 该会话不触发 override（走默认系统提示组装链）。
     * 本批次仅加实体字段；写侧（会话创建/恢复链）与读侧消费（SP-01）属后续批次。
     */
    private String loopModeOverride;
    /**
     * 会话级非交互标志（V57 列 non_interactive_session，INTEGER 0/1 可空）· CC original:
     * getIsNonInteractiveSession() = !STATE.isInteractive（bootstrap/state.ts:1057-1059），
     * 消费于 constants/prompts.ts:368-370（非交互会话不注入 '!' 前缀 shell 命令建议子弹）。
     * Web 多会话 → 会话列承载（同款铁律）。可空：null = false 交互式（Web 后端会话默认交互）。
     * 本批次仅加实体字段；写侧与读侧消费（SP-10）属后续批次。
     */
    private Integer nonInteractiveSession;
    /**
     * 会话级 auto 模式（V57 列 auto_mode_enabled，INTEGER 0/1 可空）· CC original:
     * auto_mode 系统提示附件（utils/messages.ts:3860-3870 case 'auto_mode' →
     * getAutoModeInstructions，:3419-3432）+ permissions GetNextPermissionMode 联动
     * （auto 档连续自主执行）。Web 多会话 → 会话列承载（同款铁律）。可空：
     * null = feature 门默认关（不注入 auto mode 指令）。
     * 本批次仅加实体字段；写侧与读侧消费（GLB-03）属后续批次。
     */
    private Integer autoModeEnabled;
    /**
     * 会话指定主线程 agent（V58 列 main_thread_agent，TEXT 可空）· CC original:
     * appState.agent（用户经 /init --agent 设置，CLI 单会话进程内存态）+
     * mainThreadAgentDefinition = activeAgents.find(a => a.agentType === appState.agent)
     * （resumeAgent.ts:121-124）：非空 → getSystemPrompt 生成 agentSystemPrompt
     * 替换 custom/default（systemPrompt.ts:77-83/:115-122）。
     * Web 多会话 → 会话列承载（multi-session-vs-cc-single-session 铁律，同款范式）。
     * 可空：null = 该会话未指定（agent 分支休眠，走默认组装链）。
     * 值 = agentType 串（AgentDefinitionRegistry.findAgent 等价 lookup，SP-03）。
     */
    private String mainThreadAgent;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModelTag() { return modelTag; }
    public void setModelTag(String modelTag) { this.modelTag = modelTag; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    public String getSessionGroup() { return sessionGroup; }
    public void setSessionGroup(String sessionGroup) { this.sessionGroup = sessionGroup; }
    public String getTabId() { return tabId; }
    public void setTabId(String tabId) { this.tabId = tabId; }
    public String getMainProjectId() { return mainProjectId; }
    public void setMainProjectId(String mainProjectId) { this.mainProjectId = mainProjectId; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getEffortLevel() { return effortLevel; }
    public void setEffortLevel(String effortLevel) { this.effortLevel = effortLevel; }
    public Integer getUltracodeEnabled() { return ultracodeEnabled; }
    public void setUltracodeEnabled(Integer ultracodeEnabled) { this.ultracodeEnabled = ultracodeEnabled; }
    public Integer getBareMode() { return bareMode; }
    public void setBareMode(Integer bareMode) { this.bareMode = bareMode; }
    public String getDisabledTools() { return disabledTools; }
    public void setDisabledTools(String disabledTools) { this.disabledTools = disabledTools; }
    public String getTeamContext() { return teamContext; }
    public void setTeamContext(String teamContext) { this.teamContext = teamContext; }
    public String getTodos() { return todos; }
    public void setTodos(String todos) { this.todos = todos; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
    public Double getTotalCostYuan() { return totalCostYuan; }
    public void setTotalCostYuan(Double totalCostYuan) { this.totalCostYuan = totalCostYuan; }
    public String getModelUsageJson() { return modelUsageJson; }
    public void setModelUsageJson(String modelUsageJson) { this.modelUsageJson = modelUsageJson; }
    // [prompt-align G0-02 V57] 会话级门控 3 列 getter/setter（V57 会话列，MyBatis-Flex snake↔camel 映射）
    public String getLoopModeOverride() { return loopModeOverride; }
    public void setLoopModeOverride(String loopModeOverride) { this.loopModeOverride = loopModeOverride; }
    public Integer getNonInteractiveSession() { return nonInteractiveSession; }
    public void setNonInteractiveSession(Integer nonInteractiveSession) { this.nonInteractiveSession = nonInteractiveSession; }
    public Integer getAutoModeEnabled() { return autoModeEnabled; }
    public void setAutoModeEnabled(Integer autoModeEnabled) { this.autoModeEnabled = autoModeEnabled; }
    // [prompt-align SP-03 V58] 会话指定主线程 agent getter/setter（V58 会话列，MyBatis-Flex snake↔camel 映射）
    public String getMainThreadAgent() { return mainThreadAgent; }
    public void setMainThreadAgent(String mainThreadAgent) { this.mainThreadAgent = mainThreadAgent; }
}
