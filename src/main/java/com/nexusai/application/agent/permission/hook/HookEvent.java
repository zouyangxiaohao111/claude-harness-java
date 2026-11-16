package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Hook 事件数据 · 对齐 CC coreTypes.ts:25-53 全部 27 种事件 +
 * [Session H4] BaseHookInput 6 顶层字段对齐 CC coreSchemas.ts:387-411.
 *
 * <p><b>[Session H4] 字段补全</b>: CC BaseHookInputSchema (coreSchemas.ts:387-411) 6 顶层字段:
 * session_id, transcript_path, cwd, permission_mode, agent_id, agent_type. 之前 Java 只
 * 承载 sessionId/agentId 2 字段, 把 transcript_path/cwd/permission_mode 塞 data Map.
 * H4 补全为 record 顶层字段 (transcriptPath/cwd/permissionMode), 位置镜像 CC 顺序:
 * session_id → transcript_path → cwd → permission_mode → agent_id → agent_type.
 *
 * <p><b>[Session H4] 工厂补参数</b>:
 * <ul>
 *   <li>PreToolUse/PostToolUse 加 tool_use_id (CC coreSchemas.ts:417/444 必传)</li>
 *   <li>PermissionRequest 加 permission_suggestions (CC coreSchemas.ts:431 optional)</li>
 *   <li>Elicitation full 加 requested_schema (CC coreSchemas.ts:642 optional)</li>
 *   <li>SessionEnd reason 改用 {@link ExitReasons} enum (CC coreSchemas.ts:747-754)</li>
 * </ul>
 *
 * @param type              事件类型
 * @param sessionId        CC original: {@code session_id} (coreSchemas.ts:388); 会话 ID
 * @param transcriptPath   CC original: {@code transcript_path} (coreSchemas.ts:389);
 *                         会话 transcript 文件路径, [H4] 提升为顶层字段
 * @param cwd              CC original: {@code cwd} (coreSchemas.ts:390);
 *                         当前工作目录, [H4] 提升为顶层字段
 * @param permissionMode   CC original: {@code permission_mode} (coreSchemas.ts:391);
 *                         权限模式 (default/acceptEdits/bypassPermissions/plan), [H4] 提升为顶层字段
 * @param agentId          CC original: {@code agent_id} (coreSchemas.ts:392-401);
 *                         agent ID (subagent 时非 null)
 * @param toolName         工具名（工具事件时非 null）
 * @param input            工具输入（工具事件时非 null）
 * @param result           工具结果（PostToolUse/PostToolUseFailure 时非 null）
 * @param toolUseId        CC original: {@code tool_use_id} (coreSchemas.ts:417/444);
 *                         工具调用 ID (PreToolUse/PostToolUse 必传), [H4] 提升为顶层字段
 * @param permissionSuggestions CC original: {@code permission_suggestions} (coreSchemas.ts:431);
 *                         PermissionRequest hook 的权限建议列表, optional, [H4] 提升为顶层字段
 * @param requestedSchema  CC original: {@code requested_schema} (coreSchemas.ts:642);
 *                         Elicitation hook 的请求 schema, optional, [H4] 提升为顶层字段
 * @param dataRecord       类型化输入 record（OPD-WF1-TY-02 补类型化，IMP-CF-01）· 承载事件特定
 *                         字段（对齐 CC coreSchemas.ts:414-765 各 HookInputSchema）；
 *                         {@link #data()} 提供兼容的 KV Map 视图
 * @param timestampMs      事件时间戳
 * @param promptId         CC original: {@code prompt_id}（claude.exe v2.1.325 base {@code j()}
 *                         off≈311,039,6xx）；同一用户提问引发的所有事件 + OTel {@code prompt.id}
 *                         的关联键。[Wave1-A] 追加字段；为 null 时序列化省略（CC 首个输入前 absent）
 * @param effortLevel      CC original: {@code effort.level}（同 base schema）；当前轮推理档位
 *                         （low/medium/high/xhigh/max），命令 hook 侧对应 {@code CLAUDE_EFFORT}
 *                         env。[Wave1-A] 追加字段；null = 该模型/事件无 effort
 */
public record HookEvent(
    HookEventType type,
    String sessionId,
    String transcriptPath,
    String cwd,
    String permissionMode,
    String agentId,
    String toolName,
    JsonNode input,
    JsonNode result,
    String toolUseId,
    List<Map<String, Object>> permissionSuggestions,
    Map<String, Object> requestedSchema,
    HookEventData dataRecord,
    long timestampMs,
    String promptId,
    String effortLevel
) {
    public HookEvent {
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        if (permissionSuggestions != null) {
            permissionSuggestions = List.copyOf(permissionSuggestions);
        }
        if (requestedSchema != null) {
            requestedSchema = Map.copyOf(requestedSchema);
        }
        if (timestampMs <= 0) {
            timestampMs = System.currentTimeMillis();
        }
    }

    /**
     * [Wave1-A] 旧 14 参转发构造器 · {@code promptId}/{@code effortLevel} 缺失。
     *
     * <p>WHY: 加两个 base 字段前本 record 为 14 字段（52 处构造点）；纯追加字段后用转发
     * 构造器承接（两字段 = null，序列化时省略 = CC absent 语义），不构成"兼容壳"——
     * 唯一真源仍是 16 字段 record。需要透传时走 canonical 16 参构造。
     */
    public HookEvent(HookEventType type, String sessionId, String transcriptPath, String cwd,
                     String permissionMode, String agentId, String toolName, JsonNode input,
                     JsonNode result, String toolUseId, List<Map<String, Object>> permissionSuggestions,
                     Map<String, Object> requestedSchema, HookEventData dataRecord, long timestampMs) {
        this(type, sessionId, transcriptPath, cwd, permissionMode, agentId, toolName, input, result,
            toolUseId, permissionSuggestions, requestedSchema, dataRecord, timestampMs, null, null);
    }

    /**
     * 兼容 KV Map 视图（对齐旧 {@code data()} Map 契约）· 由类型化 record 序列化。
     *
     * <p>OPD-WF1-TY-02 补类型化后，事件特定载荷的单一存储是 {@link #dataRecord()}
     * （类型化输入 record，对齐 CC HookInputSchema）；本方法提供其 snake_case KV 投影，
     * 供 {@code CommandHookExecutor.buildJsonInput} 序列化与既有 Map 读取方
     * （HookMatcherEngine matchQuery / HookRegistry agent_type 提取）使用，JSON 输出
     * 与旧 Map 载荷一致。
     *
     * @return 事件数据 KV map（无事件特定数据 → 空 map，恒非 null）
     */
    public Map<String, Object> data() {
        return dataRecord == null ? Map.of() : dataRecord.toMap();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 工具相关 (3)
    // ════════════════════════════════════════════════════════════════════════

    /** PreToolUse: tool_name, tool_input, tool_use_id · [H4] tool_use_id 必传 (CC coreSchemas.ts:417). */
    public static HookEvent toolPre(String toolName, JsonNode input, String sessionId, String agentId, String toolUseId) {
        return toolPre(toolName, input, sessionId, agentId, toolUseId, null);
    }

    /**
     * [Wave3-input] PreToolUse + {@code mcp_server}（v2.1.325 {@code {name, source}}）。
     *
     * @param mcpServer MCP 工具来源信息；非 MCP / 无来源 → null（序列化省略）
     */
    public static HookEvent toolPre(String toolName, JsonNode input, String sessionId, String agentId,
                                    String toolUseId, Map<String, Object> mcpServer) {
        return new HookEvent(HookEventType.PRE_TOOL_USE, sessionId, null, null, null, agentId,
            toolName, input, null, toolUseId, null, null, new HookEventData.PreToolUse(null, mcpServer), 0);
    }

    /** 测试/匹配场景便捷重载: 无 tool_use_id 上下文. [IMPL-03 X5] 生产路径禁止使用
     * 本重载 — 工具调用必须有 tool_use_id (CC coreSchemas.ts:417 必传), 生产调用点
     * 一律走 5 参 {@link #toolPre(String, JsonNode, String, String, String)}. */
    public static HookEvent toolPre(String toolName, JsonNode input, String sessionId, String agentId) {
        return toolPre(toolName, input, sessionId, agentId, null);
    }

    /** PostToolUse: tool_name, tool_input, tool_response, tool_use_id · [H4] tool_use_id 必传 (CC coreSchemas.ts:444). */
    public static HookEvent toolPost(String toolName, JsonNode input, JsonNode result, String sessionId, String agentId, String toolUseId) {
        return toolPost(toolName, input, result, sessionId, agentId, toolUseId, null, null);
    }

    /**
     * [Wave3-input] PostToolUse + {@code mcp_server}/{@code duration_ms}（v2.1.325）。
     *
     * @param mcpServer  MCP 工具来源信息；非 MCP → null
     * @param durationMs 工具本身耗时（不含权限询问/hook）；无计时 → null
     */
    public static HookEvent toolPost(String toolName, JsonNode input, JsonNode result, String sessionId,
                                     String agentId, String toolUseId,
                                     Map<String, Object> mcpServer, Long durationMs) {
        return new HookEvent(HookEventType.POST_TOOL_USE, sessionId, null, null, null, agentId,
            toolName, input, result, toolUseId, null, null,
            new HookEventData.PostToolUse(null, mcpServer, durationMs), 0);
    }

    /** 测试/匹配场景便捷重载: 无 tool_use_id 上下文. [IMPL-03 X5] 生产路径走 6 参重载. */
    public static HookEvent toolPost(String toolName, JsonNode input, JsonNode result, String sessionId, String agentId) {
        return toolPost(toolName, input, result, sessionId, agentId, null);
    }

    /**
     * [IMP-HOOKS-S6 ⊕3 + CCJ-T6-14] PostToolUseFailure 全参工厂 · 对齐 CC
     * executePostToolUseFailureHooks (hooks.ts:3492-3527) + PostToolUseFailureHookInputSchema
     * (coreSchemas.ts:448-459): hook_input = {tool_name, tool_input, tool_use_id, error,
     * is_interrupt} — 5 参/6 参旧工厂已删除 (T6-⊕3, CC 载荷无 error/is_interrupt 不完整).
     *
     * <p><b>tool_use_id 承载</b>: 按 permissionDenied 先例 (:198-206) 经 data map
     * {@code tool_use_id} key 承载 (CommandHookExecutor.buildJsonInput 把 data KV 全量
     * 并入载荷, :1946-1948) — 保证 CC coreSchemas.ts:448 tool_use_id 必传不丢.
     *
     * @param error       失败原因文本 (CC original: error, coreSchemas.ts:455; 工具 error
     *                    content, 可为 null)
     * @param isInterrupt 是否因用户中断导致失败 (CC original: is_interrupt, coreSchemas.ts:456;
     *                    optional boolean)
     * @param toolUseId   工具调用 ID (CC original: tool_use_id, coreSchemas.ts:448 必传)
     */
    public static HookEvent toolPostFailure(String toolName, JsonNode input, JsonNode result,
                                            String error, boolean isInterrupt, String toolUseId,
                                            String sessionId, String agentId) {
        return toolPostFailure(toolName, input, result, error, isInterrupt, toolUseId,
            sessionId, agentId, null, null);
    }

    /**
     * [Wave3-input] PostToolUseFailure + {@code mcp_server}/{@code duration_ms}（v2.1.325）。
     */
    public static HookEvent toolPostFailure(String toolName, JsonNode input, JsonNode result,
                                            String error, boolean isInterrupt, String toolUseId,
                                            String sessionId, String agentId,
                                            Map<String, Object> mcpServer, Long durationMs) {
        HookEventData data = new HookEventData.PostToolUseFailure(error, isInterrupt, toolUseId, null,
            mcpServer, durationMs);
        return new HookEvent(HookEventType.POST_TOOL_USE_FAILURE, sessionId, null, null, null, agentId,
            toolName, input, result, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 权限相关 (2)
    // ════════════════════════════════════════════════════════════════════════

    /** PermissionRequest: tool_name, tool_input, permission_suggestions · [H4] permission_suggestions optional (CC coreSchemas.ts:431). */
    public static HookEvent permissionRequest(String toolName, JsonNode input,
                                               List<Map<String, Object>> permissionSuggestions,
                                               String sessionId, String agentId) {
        return permissionRequest(toolName, input, permissionSuggestions, sessionId, agentId, null);
    }

    /** [Wave3-input] PermissionRequest + {@code mcp_server}（v2.1.325）。 */
    public static HookEvent permissionRequest(String toolName, JsonNode input,
                                               List<Map<String, Object>> permissionSuggestions,
                                               String sessionId, String agentId,
                                               Map<String, Object> mcpServer) {
        return new HookEvent(HookEventType.PERMISSION_REQUEST, sessionId, null, null, null, agentId,
            toolName, input, null, null, permissionSuggestions, null,
            new HookEventData.PermissionRequest(null, mcpServer), 0);
    }

    /**
     * [Session S07] PermissionRequest 全参工厂 · 对齐 CC executePermissionRequestHooks
     * (hooks.ts:4157-4192) hook_input: hook_event_name='PermissionRequest' + tool_name +
     * tool_input + permission_suggestions + base 字段 (permission_mode / tool_use_id).
     *
     * <p>WHY: 旧 5 参工厂 permissionMode/toolUseId 恒 null, coordinator/interactive 的
     * runHooks 等价实现无法把权限模式与工具调用 ID 传给 hook (CC 必传,
     * hooks.ts:4158-4160 / coreSchemas.ts:417). S07 接线后由两处消费链使用.
     *
     * @param toolName            工具名 (CC original: tool_name)
     * @param input               工具输入 (CC original: tool_input)
     * @param permissionSuggestions 权限建议列表 (CC original: permission_suggestions, optional)
     * @param permissionMode      权限模式 (CC original: permission_mode, optional)
     * @param toolUseId           工具调用 ID (CC original: tool_use_id, optional)
     * @param sessionId           会话 ID (CC original: session_id)
     * @param agentId             agent ID (CC original: agent_id)
     */
    public static HookEvent permissionRequest(String toolName, JsonNode input,
                                               List<Map<String, Object>> permissionSuggestions,
                                               String permissionMode, String toolUseId,
                                               String sessionId, String agentId) {
        return permissionRequest(toolName, input, permissionSuggestions, permissionMode, toolUseId,
            sessionId, agentId, null);
    }

    /** [Hook v2.1.325] PermissionRequest 全参 + {@code mcp_server}。 */
    public static HookEvent permissionRequest(String toolName, JsonNode input,
                                               List<Map<String, Object>> permissionSuggestions,
                                               String permissionMode, String toolUseId,
                                               String sessionId, String agentId,
                                               Map<String, Object> mcpServer) {
        return new HookEvent(HookEventType.PERMISSION_REQUEST, sessionId, null, null, permissionMode,
            agentId, toolName, input, null, toolUseId, permissionSuggestions, null,
            new HookEventData.PermissionRequest(null, mcpServer), 0);
    }

    /** PermissionDenied: tool_name, tool_input, tool_use_id, reason. */
    public static HookEvent permissionDenied(String toolName, JsonNode input, String reason, String sessionId, String agentId) {
        return permissionDenied(toolName, input, reason, null, sessionId, agentId);
    }

    /**
     * [R32-b13 B9 fix] PermissionDenied with tool_use_id · 对齐 CC PermissionDeniedHookInput 契约.
     *
     * <p>CC 真源 Open-ClaudeCode/src/entrypoints/sdk/coreSchemas.ts:454-462 要求 PermissionDenied
     * hook 接收 {@code tool_use_id} 字段. Java 端用 data map 的 {@code tool_use_id} key 承载
     * (避免扩 HookEvent record 字段, 与同模块的 stop_hook_active/is_interrupt/error 字段一致用 data map 模式)。
     *
     * @param toolUseId tool_use_id（CC PermissionDeniedHookInput 必传；null = 不写入 data map）
     */
    public static HookEvent permissionDenied(String toolName, JsonNode input, String reason,
                                             String toolUseId, String sessionId, String agentId) {
        return permissionDenied(toolName, input, reason, toolUseId, sessionId, agentId, null);
    }

    /** [Wave3-input] PermissionDenied + {@code mcp_server}（v2.1.325）。 */
    public static HookEvent permissionDenied(String toolName, JsonNode input, String reason,
                                             String toolUseId, String sessionId, String agentId,
                                             Map<String, Object> mcpServer) {
        HookEventData data = new HookEventData.PermissionDenied(reason, toolUseId, null, null, mcpServer);
        return new HookEvent(HookEventType.PERMISSION_DENIED, sessionId, null, null, null, agentId,
            toolName, input, null, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 会话相关 (5)
    // ════════════════════════════════════════════════════════════════════════

    /** SessionStart: source (startup/resume/clear/compact), agent_type, model. */
    public static HookEvent sessionStart(String sessionId, String agentId, String source, String agentType, String model) {
        return sessionStart(sessionId, agentId, source, agentType, model, null, null, null, null, null);
    }

    /**
     * [Wave3-input] SessionStart + v2.1.325 追加观测字段（source 含 {@code fork}）。
     *
     * @param sessionTitle             hook 可见的会话标题（无来源 → null）
     * @param secondsSinceLastResponse resume/fork：距 transcript 最后一条 assistant 响应的秒数
     * @param contextTokens            resume/fork：上次请求 input+cache_read+cache_creation+output
     * @param promptCacheLikelyExpired 距最后响应超过 prompt cache TTL
     * @param estimatedCacheWriteUsd   重缓存的估算成本（无定价来源 → null）
     */
    public static HookEvent sessionStart(String sessionId, String agentId, String source, String agentType,
                                         String model, String sessionTitle, Long secondsSinceLastResponse,
                                         Long contextTokens, Boolean promptCacheLikelyExpired,
                                         Double estimatedCacheWriteUsd) {
        HookEventData data = new HookEventData.SessionStart(source, agentType, model, sessionTitle,
            secondsSinceLastResponse, contextTokens, promptCacheLikelyExpired, estimatedCacheWriteUsd);
        return new HookEvent(HookEventType.SESSION_START, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * SessionEnd with reason · [H4] reason 改用 {@link ExitReasons} enum (破约改签名,
     * 对齐 CC coreSchemas.ts:747-754 EXIT_REASONS).
     */
    public static HookEvent sessionEnd(String sessionId, String agentId, ExitReasons reason) {
        return sessionEnd(sessionId, agentId, reason, null);
    }

    /**
     * SessionEnd with reason + agentType.
     *
     * <p>[对抗核验 H13-GAP-5 v3] 同 {@link #stop(String, String, boolean, String, String)}:
     * SessionEnd 事件 data 注入 agent_type（CC BaseHookInput agent_type, coreSchemas.ts:393），
     * 让 SessionEnd 触发的 agent hook 的 agentName analytics 在生产有真实载荷。
     */
    public static HookEvent sessionEnd(String sessionId, String agentId, ExitReasons reason, String agentType) {
        String reasonCc = reason != null ? reason.name().toLowerCase() : null;
        String agentTypeCc = agentType != null && !agentType.isBlank() ? agentType : null;
        HookEventData data = new HookEventData.SessionEnd(reasonCc, agentTypeCc);
        return new HookEvent(HookEventType.SESSION_END, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** Stop: stop_hook_active, last_assistant_message. */
    public static HookEvent stop(String sessionId, String agentId, boolean stopHookActive, String lastAssistantMessage) {
        return stop(sessionId, agentId, stopHookActive, lastAssistantMessage, null);
    }

    /**
     * Stop: stop_hook_active, last_assistant_message, agent_type.
     *
     * <p>[对抗核验 H13-GAP-5 v3] CC hooks.ts:2283-2286 的 hookInput.agent_type 在 STOP 事件
     * 生产仍为 null —— LlmAgentLoop 构造 STOP 事件 data 不填 agent_type, 导致 STOP 触发的
     * agent hook 的 agentName analytics 在生产无载荷。本重载由 LlmAgentLoop 注入
     * ToolUseContext.agentType()（子 Agent 循环非 null, 主循环 null = 对齐 CC agent_type undefined）。
     */
    public static HookEvent stop(String sessionId, String agentId, boolean stopHookActive,
                                 String lastAssistantMessage, String agentType) {
        String agentTypeCc = agentType != null && !agentType.isBlank() ? agentType : null;
        HookEventData data = new HookEventData.Stop(stopHookActive, lastAssistantMessage, agentTypeCc);
        return new HookEvent(HookEventType.STOP, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * Stop with permissionMode · 对齐 CC {@code executeStopHooks} 载荷恒带
     * {@code permission_mode}（createBaseHookInput(permissionMode)，hooks.ts:3681（Stop 分支；
     * hooks.ts:3672 为 SubagentStop 分支行，本工厂指 Stop）；
     * appState.toolPermissionContext.mode，stopHooks.ts:177-178）。
     *
     * <p>WHY（hooks_v3 决策 5-9/5-W4-2）：CC Stop hook 输入恒带 permission_mode；Java 旧 Stop
     * 工厂 permissionMode 顶层字段恒 null，且 CommandHookExecutor.enrichBaseFields 仅工具事件
     * 注入 ctx.permissionMode（CommandHookExecutor.java:1786-1788），非工具事件域省略 → Stop
     * hook 读不到 permission_mode（依赖它的 Stop hook 命令行为偏离 CC）。本重载在事件构造层
     * 承载 permissionMode（record 顶层，事件域优先），enrichBaseFields 事件顶层已有值优先
     * （:1786），buildJsonInput 序列化时写入（:1890）。
     *
     * @param permissionMode CC original: {@code permission_mode}（coreSchemas.ts:391；由调用方
     *                       ToolPermissionGate.modeToCcString 映射，null = 省略对齐 CC undefined）
     */
    public static HookEvent stop(String sessionId, String agentId, boolean stopHookActive,
                                 String lastAssistantMessage, String agentType, String permissionMode) {
        return stop(sessionId, agentId, stopHookActive, lastAssistantMessage, agentType, permissionMode,
            null, null);
    }

    /**
     * [Wave3-input] Stop + {@code background_tasks}/{@code session_crons}（v2.1.325）。
     *
     * @param backgroundTasks CC original: {@code background_tasks}（在飞后台工作）；无 → null
     * @param sessionCrons    CC original: {@code session_crons}（会话级 cron）；无 → null
     */
    public static HookEvent stop(String sessionId, String agentId, boolean stopHookActive,
                                 String lastAssistantMessage, String agentType, String permissionMode,
                                 List<Map<String, Object>> backgroundTasks,
                                 List<Map<String, Object>> sessionCrons) {
        String agentTypeCc = agentType != null && !agentType.isBlank() ? agentType : null;
        HookEventData data = new HookEventData.Stop(stopHookActive, lastAssistantMessage, agentTypeCc,
            backgroundTasks, sessionCrons);
        return new HookEvent(HookEventType.STOP, sessionId, null, null, permissionMode, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** StopFailure: error, error_details, last_assistant_message. · [IMP-HOOKS-S5 D-18]
     *  error 由 Map 改 String（CC hooks.ts:3612 `lastMessage.error ?? 'unknown'` 字符串载荷；
     *  matchQuery=error 按具体值匹配，旧 Map 载荷 String.valueOf 后永不命中）。 */
    public static HookEvent stopFailure(String sessionId, String agentId, String error, String errorDetails, String lastAssistantMessage) {
        HookEventData data = new HookEventData.StopFailure(error, errorDetails, lastAssistantMessage);
        return new HookEvent(HookEventType.STOP_FAILURE, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** Setup: trigger (init/maintenance). */
    public static HookEvent setup(String sessionId, String agentId, String trigger) {
        HookEventData data = new HookEventData.Setup(trigger);
        return new HookEvent(HookEventType.SETUP, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** InstructionsLoaded with full details · 调用方：ClaudemdEngine（memory 模块真实调用点，
     * 对齐 CC HookEvent.instructionsLoaded：file_path, memory_type, load_reason,
     * session_id, globs, trigger_file_path, parent_file_path）。 */
    public static HookEvent instructionsLoaded(String filePath, String memoryType, String loadReason,
                                                String sessionId, java.util.List<String> globs,
                                                String triggerFilePath, String parentFilePath) {
        HookEventData data = new HookEventData.InstructionsLoaded(filePath, memoryType, loadReason, globs,
            triggerFilePath, parentFilePath);
        return new HookEvent(HookEventType.INSTRUCTIONS_LOADED, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 用户交互 (2)
    // ════════════════════════════════════════════════════════════════════════

    /** UserPromptSubmit: prompt. */
    public static HookEvent userPromptSubmit(String sessionId, String agentId) {
        return new HookEvent(HookEventType.USER_PROMPT_SUBMIT, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, new HookEventData.UserPromptSubmit(null), 0);
    }

    /** UserPromptSubmit with prompt. */
    public static HookEvent userPromptSubmit(String sessionId, String agentId, String prompt) {
        return userPromptSubmit(sessionId, agentId, prompt, null, null);
    }

    /**
     * [Wave3-input] UserPromptSubmit + {@code source}/{@code session_title}（v2.1.325）。
     *
     * @param source       CC original: {@code source}（user/sdk/system/loop_wakeup/
     *                     schedule_wakeup/poll_event）；无法判定 → null
     * @param sessionTitle CC original: {@code session_title}；无来源 → null
     */
    public static HookEvent userPromptSubmit(String sessionId, String agentId, String prompt,
                                             String source, String sessionTitle) {
        HookEventData data = new HookEventData.UserPromptSubmit(prompt, source, sessionTitle);
        return new HookEvent(HookEventType.USER_PROMPT_SUBMIT, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** Notification: message, title, notification_type（Map 便捷构造 → 经 fromMap 转类型化 record）。 */
    public static HookEvent notification(String sessionId, String agentId, Map<String, Object> data) {
        return new HookEvent(HookEventType.NOTIFICATION, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, HookEventData.fromMap(HookEventType.NOTIFICATION, data), 0);
    }

    /** Notification with details. */
    public static HookEvent notification(String sessionId, String agentId, String message, String title, String notificationType) {
        HookEventData data = new HookEventData.Notification(message, title, notificationType);
        return new HookEvent(HookEventType.NOTIFICATION, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 子代理 (2)
    // ════════════════════════════════════════════════════════════════════════

    /** SubagentStart: agent_id, agent_type, session_id（任务 7：透传顶层 agent_id 三元组到 data） */
    public static HookEvent subagentStart(String agentId, String agentType, String sessionId) {
        HookEventData data = new HookEventData.SubagentStart(agentId, agentType, sessionId);
        return new HookEvent(HookEventType.SUBAGENT_START, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** SubagentStop: stop_hook_active, agent_id, agent_transcript_path, agent_type, session_id, last_assistant_message（任务 7：透传 session_id） */
    public static HookEvent subagentStop(String agentId, String agentType, String sessionId) {
        HookEventData data = new HookEventData.SubagentStop(agentId, agentType, null, null, null, sessionId);
        return new HookEvent(HookEventType.SUBAGENT_STOP, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * SubagentStop with details.
     *
     * <p>[IMP-LL-03 EX-01] record 顶层 {@code transcriptPath} 填充为子代理 transcript
     * （{@code agentTranscriptPath} 参数，CC original: {@code agent_transcript_path}）· 对齐 CC
     * execAgentHook.ts:54-56 {@code toolUseContext.agentId ? getAgentTranscriptPath(agentId) :
     * getTranscriptPath()}——subagent 场景 agent hook 的 transcript_path 必须指向子代理 transcript，
     * 否则（Java 旧行为 record transcriptPath=null → enrichBaseFields 会话级回退）subagent Stop/
     * SubagentStop 触发的 agent hook 读到主会话 transcript（EV-WF5-EX-003，HIGH）。设置后
     * {@code event.transcriptPath()} 非 null → {@code CommandHookExecutor.enrichBaseFields}
     * 不再回退，ExecAgentHook 读子代理 transcript（hooks_v3 反双轨：载荷顶层仍双载
     * {@code transcript_path}（子代理）+ data {@code agent_transcript_path}（子代理），CC
     * 载荷对应字段为 {@code transcript_path}（主会话）+ {@code agent_transcript_path}（子代理）——
     * Java record 单字段耦合，取可观测等价面：agent hook 读到子代理 transcript）。
     */
    public static HookEvent subagentStop(String agentId, String agentType, String sessionId,
                                          boolean stopHookActive, String agentTranscriptPath, String lastAssistantMessage) {
        HookEventData data = new HookEventData.SubagentStop(agentId, agentType, stopHookActive,
            agentTranscriptPath, lastAssistantMessage, null);
        return new HookEvent(HookEventType.SUBAGENT_STOP, sessionId, agentTranscriptPath, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * SubagentStop with permissionMode · 对齐 CC {@code executeSubagentStopHooks} 载荷恒带
     * {@code permission_mode}（createBaseHookInput(permissionMode)，hooks.ts:3672（SubagentStop
     * 分支；hooks.ts:3681 为 Stop 分支行，本工厂指 SubagentStop）。
     *
     * <p>WHY（hooks_v3 决策 5-9/5-W4-2）：同 {@link #stop(String, String, boolean, String, String, String)}
     * —— SubagentStop hook 输入 CC 恒带 permission_mode，Java 旧工厂省略。
     *
     * @param permissionMode CC original: {@code permission_mode}（coreSchemas.ts:391）
     */
    public static HookEvent subagentStop(String agentId, String agentType, String sessionId,
                                          boolean stopHookActive, String agentTranscriptPath, String lastAssistantMessage,
                                          String permissionMode) {
        return subagentStop(agentId, agentType, sessionId, stopHookActive, agentTranscriptPath,
            lastAssistantMessage, permissionMode, null, null);
    }

    /**
     * [Wave3-input] SubagentStop + {@code background_tasks}/{@code session_crons}（v2.1.325）。
     */
    public static HookEvent subagentStop(String agentId, String agentType, String sessionId,
                                          boolean stopHookActive, String agentTranscriptPath,
                                          String lastAssistantMessage, String permissionMode,
                                          List<Map<String, Object>> backgroundTasks,
                                          List<Map<String, Object>> sessionCrons) {
        HookEventData data = new HookEventData.SubagentStop(agentId, agentType, stopHookActive,
            agentTranscriptPath, lastAssistantMessage, null, backgroundTasks, sessionCrons);
        return new HookEvent(HookEventType.SUBAGENT_STOP, sessionId, agentTranscriptPath, null, permissionMode, agentId,
            null, null, null, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 压缩相关 (2)
    // ════════════════════════════════════════════════════════════════════════

    /** PreCompact: trigger (manual/auto), custom_instructions. */
    public static HookEvent preCompact(String sessionId, String trigger) {
        HookEventData data = new HookEventData.PreCompact(trigger, null);
        return new HookEvent(HookEventType.PRE_COMPACT, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    /** PreCompact with custom instructions. */
    public static HookEvent preCompact(String sessionId, String trigger, String customInstructions) {
        HookEventData data = new HookEventData.PreCompact(trigger, customInstructions);
        return new HookEvent(HookEventType.PRE_COMPACT, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    /** PostCompact: trigger (manual/auto), compact_summary. */
    public static HookEvent postCompact(String sessionId, String trigger, String compactSummary) {
        HookEventData data = new HookEventData.PostCompact(trigger, compactSummary);
        return new HookEvent(HookEventType.POST_COMPACT, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 团队/任务 (3)
    // ════════════════════════════════════════════════════════════════════════

    /** TaskCreated: task_id, task_subject, task_description, teammate_name, team_name. */
    public static HookEvent taskCreated(String taskId, String subject, String sessionId, String agentId) {
        HookEventData data = new HookEventData.TaskCreated(taskId, subject, null, null, null, null, null);
        return new HookEvent(HookEventType.TASK_CREATED, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** TaskCreated with full details. */
    public static HookEvent taskCreated(String taskId, String subject, String description,
                                         String teammateName, String teamName, String sessionId, String agentId) {
        HookEventData data = new HookEventData.TaskCreated(taskId, subject, description, teammateName, teamName,
            null, null);
        return new HookEvent(HookEventType.TASK_CREATED, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * TaskCreated with permissionMode + abortController · [hook-9args] 9 参补齐.
     *
     * <p>WHY: CC executeTaskCreatedHooks (utils/hooks.ts:3745-3755) 形参 9 个:
     * taskId, taskSubject, taskDescription, teammateName, teamName, permissionMode,
     * signal, timeoutMs, toolUseContext（TaskCreateTool.ts:93-103 实传 9 参：
     * 第 6 参 permissionMode=undefined、第 7 参 signal=context?.abortController?.signal、
     * 第 8 参 timeoutMs=undefined→默认 10 分钟 TOOL_HOOK_EXECUTION_TIMEOUT_MS(hooks.ts:166)、
     * 第 9 参 toolUseContext=context）。Java HookEvent 顶层已有 permissionMode 字段
     * （此前 taskCreated 工厂恒传 null），本重载承接 permissionMode；abortController
     * 状态（cancelled/reason）写入 data map（镜像 H6 taskCompleted 先例 :391-409）。
     * timeoutMs 由 HookRegistry 每 hook 超时承载（HookRegistry.java:393）；
     * toolUseContext 不可序列化进 hook 事件，调用方拿不到时传 null（H6 先例）。
     *
     * @param permissionMode CC original: {@code permission_mode}（coreSchemas.ts:391）
     * @param abortController 当前 turn 的 AbortController（null = 无；记录 cancelled/reason）
     */
    public static HookEvent taskCreated(String taskId, String subject, String description,
                                         String teammateName, String teamName, String sessionId, String agentId,
                                         String permissionMode,
                                         com.nexusai.application.agent.tool.AbortController abortController) {
        Boolean abortCancelled = abortController != null ? abortController.isCancelled() : null;
        String abortReason = abortController != null ? abortController.reason() : null;
        HookEventData data = new HookEventData.TaskCreated(taskId, subject, description, teammateName, teamName,
            abortCancelled, abortReason);
        return new HookEvent(HookEventType.TASK_CREATED, sessionId, null, null, permissionMode, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** TaskCompleted: task_id, task_subject, task_description, teammate_name, team_name. */
    public static HookEvent taskCompleted(String taskId, String subject, String sessionId, String agentId) {
        HookEventData data = new HookEventData.TaskCompleted(taskId, subject, null, null, null, null, null);
        return new HookEvent(HookEventType.TASK_COMPLETED, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** TaskCompleted with full details. */
    public static HookEvent taskCompleted(String taskId, String subject, String description,
                                           String teammateName, String teamName, String sessionId, String agentId) {
        HookEventData data = new HookEventData.TaskCompleted(taskId, subject, description, teammateName, teamName,
            null, null);
        return new HookEvent(HookEventType.TASK_COMPLETED, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * TaskCompleted with permissionMode + abortController · [Session H6] 9 参补齐.
     *
     * <p>WHY: CC executeTaskCompletedHooks (utils/hooks.ts:3789-3799) 形参 9 个:
     * taskId, taskSubject, taskDescription, teammateName, teamName, permissionMode,
     * signal, timeoutMs, toolUseContext（与 executeTaskCreatedHooks 同构）。
     * [hook-9args] 修正前误写"8 参"——实际 9 形参。Java HookEvent 顶层已有 permissionMode
     * 字段（此前 taskCompleted 工厂恒传 null），本重载承接 permissionMode；abortController
     * 状态（cancelled/reason）写入 data map（对齐同模块 stop_hook_active/is_interrupt
     * 的 data map 模式）。toolUseContext 不可序列化进 hook 事件，调用方拿不到时传 null
     * （concern H6-6）。
     *
     * @param permissionMode CC original: {@code permission_mode}（coreSchemas.ts:391）
     * @param abortController 当前 turn 的 AbortController（null = 无；记录 cancelled/reason）
     */
    public static HookEvent taskCompleted(String taskId, String subject, String description,
                                           String teammateName, String teamName, String sessionId, String agentId,
                                           String permissionMode,
                                           com.nexusai.application.agent.tool.AbortController abortController) {
        Boolean abortCancelled = abortController != null ? abortController.isCancelled() : null;
        String abortReason = abortController != null ? abortController.reason() : null;
        HookEventData data = new HookEventData.TaskCompleted(taskId, subject, description, teammateName, teamName,
            abortCancelled, abortReason);
        return new HookEvent(HookEventType.TASK_COMPLETED, sessionId, null, null, permissionMode, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /** TeammateIdle: teammate_name, team_name. · 对齐 CC {@code executeTeammateIdleHooks}
     * (hooks.ts:3709-3729) hook_input = {hook_event_name='TeammateIdle', teammate_name,
     * team_name} + base 字段（hooks_v3 决策 0-1 teammate 收尾段基建）。 */
    public static HookEvent teammateIdle(String teammateName, String teamName, String sessionId) {
        HookEventData data = new HookEventData.TeammateIdle(teammateName, teamName);
        return new HookEvent(HookEventType.TEAMMATE_IDLE, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * TeammateIdle with permissionMode · 对齐 CC {@code executeTeammateIdleHooks}
     * 载荷恒带 {@code permission_mode}（createBaseHookInput(permissionMode)，hooks.ts:3717）。
     *
     * @param permissionMode CC original: {@code permission_mode}（coreSchemas.ts:391）
     */
    public static HookEvent teammateIdle(String teammateName, String teamName, String permissionMode,
                                         String sessionId) {
        HookEventData data = new HookEventData.TeammateIdle(teammateName, teamName);
        return new HookEvent(HookEventType.TEAMMATE_IDLE, sessionId, null, null, permissionMode, null,
            null, null, null, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // MCP (2)
    // ════════════════════════════════════════════════════════════════════════

    /** Elicitation: mcp_server_name, message, mode, url, elicitation_id, requested_schema. */
    public static HookEvent elicitation(String mcpServerName, String message, String sessionId) {
        HookEventData data = new HookEventData.Elicitation(mcpServerName, message, null, null, null);
        return new HookEvent(HookEventType.ELICITATION, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * Elicitation with full details · [H4] 加 requested_schema (CC coreSchemas.ts:642 optional).
     */
    public static HookEvent elicitation(String mcpServerName, String message, String sessionId,
                                         String mode, String url, String elicitationId,
                                         Map<String, Object> requestedSchema) {
        HookEventData data = new HookEventData.Elicitation(mcpServerName, message, mode, url, elicitationId);
        return new HookEvent(HookEventType.ELICITATION, sessionId, null, null, null, null,
            null, null, null, null, null, requestedSchema, data, 0);
    }

    /** ElicitationResult: mcp_server_name, elicitation_id, mode, action, content. */
    public static HookEvent elicitationResult(String mcpServerName, String action, String sessionId) {
        HookEventData data = new HookEventData.ElicitationResult(mcpServerName, action, null, null, null);
        return new HookEvent(HookEventType.ELICITATION_RESULT, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    /** ElicitationResult with full details. */
    public static HookEvent elicitationResult(String mcpServerName, String action, String sessionId,
                                               String elicitationId, String mode, Map<String, Object> content) {
        HookEventData data = new HookEventData.ElicitationResult(mcpServerName, action, elicitationId, mode, content);
        return new HookEvent(HookEventType.ELICITATION_RESULT, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 配置/环境 (6)
    // ════════════════════════════════════════════════════════════════════════

    /** ConfigChange: source (user_settings/project_settings/local_settings/policy_settings/skills), file_path. */
    public static HookEvent configChange(String source, String filePath, String sessionId) {
        HookEventData data = new HookEventData.ConfigChange(source, filePath);
        return new HookEvent(HookEventType.CONFIG_CHANGE, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    // [IMPL-10] DEL-CCE-02: 原"配置/环境"区块的 instructionsLoaded 工厂已上移至 Setup 区块
    //   （L277，调用方 ClaudemdEngine），此处不保留重复定义。

    // [IMPL-10] DEL-CCE-01: worktreeCreate/worktreeRemove 工厂已删除（通知式发射删除后
    //   0 调用方 — CC 为工具层结果驱动 hooks，见 09 §2 登记）。

    /** CwdChanged: old_cwd, new_cwd. */
    public static HookEvent cwdChanged(String oldCwd, String newCwd, String sessionId) {
        HookEventData data = new HookEventData.CwdChanged(oldCwd, newCwd);
        return new HookEvent(HookEventType.CWD_CHANGED, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    /** FileChanged: file_path, event (change/add/unlink). */
    public static HookEvent fileChanged(String filePath, String event, String sessionId) {
        HookEventData data = new HookEventData.FileChanged(filePath, event);
        return new HookEvent(HookEventType.FILE_CHANGED, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [Wave1-A] v2.1.325 新增 6 事件工厂
    // ════════════════════════════════════════════════════════════════════════

    /**
     * PostToolBatch: tool_calls[] · 对齐 claude.exe v2.1.325 {@code R2} schema。
     *
     * <p>一批工具调用全部 resolved 后<b>只触发一次</b>（PostToolUse 是逐工具）。
     * 每个元素为 {@code {tool_name, tool_input, tool_use_id, tool_response?}} 的 KV map。
     *
     * @param toolCalls 本批调用列表（CC original: {@code tool_calls}）
     * @param sessionId 会话 ID（CC original: {@code session_id}）
     * @param agentId   agent ID（CC original: {@code agent_id}；主线程 null）
     */
    public static HookEvent postToolBatch(List<Map<String, Object>> toolCalls, String sessionId, String agentId) {
        HookEventData data = new HookEventData.PostToolBatch(toolCalls);
        return new HookEvent(HookEventType.POST_TOOL_BATCH, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * UserPromptExpansion: 斜杠命令 / MCP prompt 展开前 · 对齐 v2.1.325 {@code x2} schema。
     *
     * @param expansionType CC original: {@code expansion_type}（slash_command|mcp_prompt）
     * @param commandName   CC original: {@code command_name}
     * @param commandArgs   CC original: {@code command_args}
     * @param commandSource CC original: {@code command_source}（optional）
     * @param prompt        CC original: {@code prompt}（展开前原文）
     * @param sessionId     会话 ID
     * @param agentId       agent ID（主线程 null）
     */
    public static HookEvent userPromptExpansion(String expansionType, String commandName, String commandArgs,
                                                String commandSource, String prompt,
                                                String sessionId, String agentId) {
        HookEventData data = new HookEventData.UserPromptExpansion(expansionType, commandName,
            commandArgs, commandSource, prompt);
        return new HookEvent(HookEventType.USER_PROMPT_EXPANSION, sessionId, null, null, null, agentId,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * PreModelSwitch: 模型切换前 · 对齐 v2.1.325 {@code H2} + {@code lC(["command","picker","sdk"])}。
     *
     * @param fromModel              CC original: {@code from_model}
     * @param toModel                CC original: {@code to_model}（matcher 匹配字段）
     * @param requestedModel         CC original: {@code requested_model}（nullable）
     * @param source                 CC original: {@code source}（command|picker|sdk）
     * @param contextTokens          CC original: {@code context_tokens}
     * @param promptCacheWarm        CC original: {@code prompt_cache_warm}
     * @param cacheTtl               CC original: {@code cache_ttl}（5m|1h）
     * @param estimatedCacheWriteUsd CC original: {@code estimated_cache_write_usd}
     * @param pricing                CC original: {@code pricing}（configured|catalog|default）
     * @param sessionId              会话 ID
     */
    public static HookEvent preModelSwitch(String fromModel, String toModel, String requestedModel,
                                           String source, Long contextTokens, Boolean promptCacheWarm,
                                           String cacheTtl, Double estimatedCacheWriteUsd, String pricing,
                                           String sessionId) {
        HookEventData data = new HookEventData.PreModelSwitch(fromModel, toModel, requestedModel, source,
            contextTokens, promptCacheWarm, cacheTtl, estimatedCacheWriteUsd, pricing);
        return new HookEvent(HookEventType.PRE_MODEL_SWITCH, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * PostModelSwitch: 模型切换后 · 对齐 v2.1.325 {@code G2}
     * + {@code lC(["command","picker","sdk","auto","resume"])}（字段同 PreModelSwitch）。
     *
     * @param fromModel              CC original: {@code from_model}
     * @param toModel                CC original: {@code to_model}
     * @param requestedModel         CC original: {@code requested_model}（nullable）
     * @param source                 CC original: {@code source}
     * @param contextTokens          CC original: {@code context_tokens}
     * @param promptCacheWarm        CC original: {@code prompt_cache_warm}
     * @param cacheTtl               CC original: {@code cache_ttl}
     * @param estimatedCacheWriteUsd CC original: {@code estimated_cache_write_usd}
     * @param pricing                CC original: {@code pricing}
     * @param sessionId              会话 ID
     */
    public static HookEvent postModelSwitch(String fromModel, String toModel, String requestedModel,
                                            String source, Long contextTokens, Boolean promptCacheWarm,
                                            String cacheTtl, Double estimatedCacheWriteUsd, String pricing,
                                            String sessionId) {
        HookEventData data = new HookEventData.PostModelSwitch(fromModel, toModel, requestedModel, source,
            contextTokens, promptCacheWarm, cacheTtl, estimatedCacheWriteUsd, pricing);
        return new HookEvent(HookEventType.POST_MODEL_SWITCH, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * DirectoryAdded: 工作目录新增后 · 对齐 v2.1.325 {@code iX} schema。
     *
     * @param directory 新增目录绝对路径（CC original: {@code directory}）
     * @param source    CC original: {@code source}（slash_command=/add-dir；register_repo_root=SDK）
     * @param sessionId 会话 ID
     */
    public static HookEvent directoryAdded(String directory, String source, String sessionId) {
        HookEventData data = new HookEventData.DirectoryAdded(directory, source);
        return new HookEvent(HookEventType.DIRECTORY_ADDED, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    /**
     * MessageDisplay: 助手消息每批新行显示时 · 对齐 v2.1.325 {@code lX} schema。
     *
     * @param turnId     当前轮 UUID（CC original: {@code turn_id}）
     * @param messageId  被显示消息 UUID（CC original: {@code message_id}）
     * @param index      该消息内 delta 序号（CC original: {@code index}）
     * @param finalFlush 是否最后一次 flush（CC original: {@code final}；Java 关键字避让）
     * @param delta      自上次 flush 起新完成的行（CC original: {@code delta}）
     * @param sessionId  会话 ID
     */
    public static HookEvent messageDisplay(String turnId, String messageId, Integer index,
                                           Boolean finalFlush, String delta, String sessionId) {
        HookEventData data = new HookEventData.MessageDisplay(turnId, messageId, index, finalFlush, delta);
        return new HookEvent(HookEventType.MESSAGE_DISPLAY, sessionId, null, null, null, null,
            null, null, null, null, null, null, data, 0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 阻塞错误格式化（对齐 CC utils/hooks.ts:1894-1929 get*HookMessage）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 格式化 Stop hook 阻塞错误 · 对齐 CC {@code getStopHookMessage}
     * (hooks.ts:1894-1896)：{@code `Stop hook feedback:\n${blockingError.blockingError}`}。
     *
     * <p>WHY（hooks_v3 决策 5-8/5-W4-1 前缀）：CC 在 stopHooks.ts:258-260 消费 blockingError 时
     * 加 {@code 'Stop hook feedback:\n'} 前缀注入 user message；Java LlmAgentLoop 直接拼原始
     * blockingText（:4832/:5119），无前缀包装 → hook 反馈文本偏离 CC。调用方（patch-note 合并后
     * LlmAgentLoop）以此方法统一包装。
     */
    public static String getStopHookMessage(HookBlockingError blockingError) {
        return "Stop hook feedback:\n" + blockingError.blockingError();
    }

    /**
     * 格式化 TeammateIdle hook 阻塞错误 · 对齐 CC {@code getTeammateIdleHookMessage}
     * (hooks.ts:1903-1907)：{@code `TeammateIdle hook feedback:\n${blockingError.blockingError}`}。
     *
     * <p>WHY（hooks_v3 决策 0-1 teammate 收尾段）：CC stopHooks.ts:418-424 对 TeammateIdle
     * blockingError 加此前缀注入 user message（isMeta=true）。
     */
    public static String getTeammateIdleHookMessage(HookBlockingError blockingError) {
        return "TeammateIdle hook feedback:\n" + blockingError.blockingError();
    }

    /**
     * 格式化 TaskCompleted hook 阻塞错误 · 对齐 CC {@code getTaskCompletedHookMessage}
     * (hooks.ts:1925-1929)：{@code `TaskCompleted hook feedback:\n${blockingError.blockingError}`}。
     *
     * <p>WHY（hooks_v3 决策 0-1 teammate 收尾段）：CC stopHooks.ts:376-382 对 TaskCompleted
     * blockingError 加此前缀注入 user message（isMeta=true）。与 TaskUpdateTool 私有等价助手
     * （TaskUpdateTool.java:886-888）同源，本方法供 teammate 收尾段共享。
     */
    public static String getTaskCompletedHookMessage(HookBlockingError blockingError) {
        return "TaskCompleted hook feedback:\n" + blockingError.blockingError();
    }
}