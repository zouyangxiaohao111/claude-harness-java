package com.nexusai.model.session.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import com.nexusai.model.provider.dto.ModelTag;

/** 响应：Session 完整信息 */
public record SessionDto(
    String id,
    ModelTag model,
    String modelName,
    String title,
    String time,
    SessionGroup group,
    String tabId,
    String mainProjectId,
    Integer messageCount,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    /**
     * 会话级 effort 档位（可空）· CC original: appState.effortValue（effort.ts:152-167
     * resolveAppliedEffort = env ?? appState.effortValue ?? getDefaultEffortForModel）· V31 列 effort_level。
     * 前端展示/回显当前会话档位（getDisplayedEffortLevel 兜底 'high'，effort.ts:178）。
     */
    String effortLevel,
    /**
     * ultracode 模式会话级开关（V32 列 ultracode_enabled，可空 0/1）· ultracode = xhigh effort +
     * workflows 编排启用（用户拍板后端应有此概念）。effortLevel 同步落 xhigh。
     */
    Boolean ultracodeEnabled,
    /**
     * bare（精简）模式会话级开关（V33 列 bare_mode，可空 0/1）· CC original: isBareMode()
     * （envUtils.ts:60-65 CLAUDE_CODE_SIMPLE）的 Web 会话级等价（用户拍板：bareMode 随会话走）。
     * 前端「精简模式」开关读写本字段；null = 会话未显式设置，判定回落 env / nexusai.memory.bare-mode / false
     * （MemoryBareModeConfig.isBareMode(String)）。
     */
    Boolean bareMode,
    /**
     * 会话级 swarm teamContext（sessions.team_context 列解析态）· CC original: appState.teamContext
     * （TeamCreateTool.ts:201-216 setAppState(teamContext)）。结构 {teamName, teamFilePath, leadAgentId,
     * leadSessionId, teammates:{...}}；未建 team / 列 null / 解析失败 → null（fail-soft，对齐
     * getTeamContext 容错）。前端会话详情头「团队：{teamName} · 队长 {leadAgentId}」pill 读本字段；
     * leadSessionId 供订阅会话级 team topic {@code /topic/sessions/{leadSessionId}/team-...}
     * （stomp-lead-session 方案 3）。
     */
    Map<String, Object> teamContext,
    /**
     * 会话级 todo 桶（sessions.todos 列解析态）· CC original: appState.todos
     * {todoKey: TodoItem[]}（TodoWriteTool.ts:65-94）。规范形 {todoKey:[{content,status,activeForm}]}，
     * status 小写 pending|in_progress|completed（CC types.ts:4-6 值域）。未写 todo / 列 null /
     * 解析失败 → null（fail-soft，对齐 teamContext）。前端会话 todo 面板刷新/重开拉取读本字段
     * （R3 持久读，跨 send/重启存活）。
     */
    Map<String, Object> todos,
    /**
     * 会话级权限模式覆盖（V44 列 sessions.permission_mode，可空）· CC original:
     * settings.permissions.defaultMode（permissionSetup.ts:743-771）的会话级等价
     * （Web 多会话无 appState 单例，multi-session-vs-cc-single-session 铁律）。
     * null = 该会话未显式覆盖 → 回落全局 settings.permission_mode → 磁盘 settings.json
     * defaultMode → default。前端 SessionDto types.ts:435 已建模。
     */
    String permissionMode,
    /**
     * 会话累计花费（人民币元 · sessions.total_cost_yuan，V48 列）· 供前端会话底部 token/金额汇总展示。
     * null = 从未有 usage 落库。
     */
    Double totalCostYuan,
    /**
     * 会话累计 token（model_usage_json 各模型 inputTokens+outputTokens 求和）· 供前端汇总展示。
     * 无数据 → 0。
     */
    Long totalTokens,
    /**
     * 会话指定主线程 agent（V58 列 main_thread_agent，可空）· CC original: appState.agent +
     * mainThreadAgentDefinition（resumeAgent.ts:121-124，systemPrompt.ts:77-83）。null = 该会话
     * 未指定（agent 分支休眠，走默认系统提示组装链）。前端会话级设置读写本字段。
     */
    String mainThreadAgent
) {}
