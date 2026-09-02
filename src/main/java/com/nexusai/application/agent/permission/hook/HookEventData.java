package com.nexusai.application.agent.permission.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 事件输入载荷 · 对齐 CC {@code coreSchemas.ts:414-765} 全部 27 种事件的
 * {@code HookInputSchema} 类型化输入 record（OPD-WF1-TY-02 补类型化，实施 IMP-CF-01）。
 *
 * <p>CC 端每事件定义类型化 Zod schema（PreToolUse/PostToolUse/SessionStart/…），事件特定字段
 * 命名 + 类型受约束；Java 旧实现把事件特定字段塞 {@code Map<String,Object>}（弱类型，拼写错误
 * 静默丢失）。本 sealed interface 为 27 事件各建一个 record，字段名 camelCase（CC snake_case
 * 原名在 JavaDoc 标注），运行时载荷仍经 {@link #toMap()} 序列化为 snake_case KV（对齐
 * {@code CommandHookExecutor.buildJsonInput} 逐项透传路径，JSON 输出与旧 Map 一致）。
 *
 * <p><b>字段归属</b>：base 字段（session_id/transcript_path/cwd/permission_mode/agent_id/
 * agent_type，coreSchemas.ts:387-411）由 {@link HookEvent} 顶层 record 承载；本 record 只承载
 * 各事件特定字段。例外：{@code agent_type} 因 Java 历史接线（enrichBaseFields 注入 + 事件工厂
 * 直填）仍由事件 data 承载（SessionStart/SessionEnd/Stop/SubagentStart/SubagentStop/工具事件），
 * 序列化时对齐 CC BaseHookInput agent_type。
 *
 * <p><b>base field 注入（工具事件 agent_type）</b>：{@link HookEventData.PreToolUse} 等 5 个工具
 * 事件 record 携带可空 {@code agentType}，供 {@link CommandHookExecutor#enrichBaseFields} 在序列化
 * 前注入 ctx.agentType()（CC createBaseHookInput，hooks.ts:309-327）；事件域已有值优先。
 *
 * @see HookEvent
 * @since IMP-CF-01 (TY-02)
 */
public sealed interface HookEventData {

    /** 序列化为 snake_case KV map（对齐旧 {@code HookEvent.data()} Map 契约；null 字段省略）。 */
    Map<String, Object> toMap();

    // ════════════════════════════════════════════════════════════════════════
    // 工具相关 (3)
    // ════════════════════════════════════════════════════════════════════════

    /** PreToolUse 事件数据 · CC PreToolUseHookInputSchema (coreSchemas.ts:414-423)。
     *  tool_name/tool_input/tool_use_id 由 HookEvent 顶层承载；agent_type 由 enrichBaseFields 注入。 */
    record PreToolUse(String agentType) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("agent_type", agentType);
        }
    }

    /** PostToolUse 事件数据 · CC PostToolUseHookInputSchema (coreSchemas.ts:436-446)。
     *  tool_name/tool_input/tool_response/tool_use_id 由 HookEvent 顶层承载。 */
    record PostToolUse(String agentType) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("agent_type", agentType);
        }
    }

    /** PostToolUseFailure 事件数据 · CC PostToolUseFailureHookInputSchema (coreSchemas.ts:448-459)。
     *  tool_name/tool_input 由 HookEvent 顶层承载；error/is_interrupt/tool_use_id 事件特定。
     *  {@code is_interrupt} 恒序列化（旧工厂无条件 put）。 */
    record PostToolUseFailure(String error, boolean isInterrupt, String toolUseId, String agentType)
            implements HookEventData {
        public Map<String, Object> toMap() {
            return map("error", error, "is_interrupt", isInterrupt, "tool_use_id", toolUseId,
                "agent_type", agentType);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 权限相关 (2)
    // ════════════════════════════════════════════════════════════════════════

    /** PermissionRequest 事件数据 · CC PermissionRequestHookInputSchema (coreSchemas.ts:425-434)。
     *  tool_name/tool_input/permission_suggestions 由 HookEvent 顶层承载。 */
    record PermissionRequest(String agentType) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("agent_type", agentType);
        }
    }

    /** PermissionDenied 事件数据 · CC PermissionDeniedHookInputSchema (coreSchemas.ts:461-471)。
     *  tool_name/tool_input 由 HookEvent 顶层承载；reason/tool_use_id/permission_mode 事件特定。
     *  {@code permissionMode} 支持 PermissionDeniedHookExecutor 直构（permission_mode 同时顶层+data）。 */
    record PermissionDenied(String reason, String toolUseId, String permissionMode, String agentType)
            implements HookEventData {
        public Map<String, Object> toMap() {
            return map("reason", reason, "tool_use_id", toolUseId, "permission_mode", permissionMode,
                "agent_type", agentType);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 会话相关 (5)
    // ════════════════════════════════════════════════════════════════════════

    /** SessionStart 事件数据 · CC SessionStartHookInputSchema (coreSchemas.ts:493-502)：
     *  source (startup/resume/clear/compact) / agent_type? / model?。 */
    record SessionStart(String source, String agentType, String model) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("source", source, "agent_type", agentType, "model", model);
        }
    }

    /** SessionEnd 事件数据 · CC SessionEndHookInputSchema (coreSchemas.ts:758-765)：
     *  reason (EXIT_REASONS 6 值) + base agent_type。 */
    record SessionEnd(String reason, String agentType) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("reason", reason, "agent_type", agentType);
        }
    }

    /** Stop 事件数据 · CC StopHookInputSchema (coreSchemas.ts:513-527)：
     *  stop_hook_active / last_assistant_message? + base agent_type。
     *  {@code stopHookActive} 恒序列化（旧工厂无条件 put）。 */
    record Stop(boolean stopHookActive, String lastAssistantMessage, String agentType)
            implements HookEventData {
        public Map<String, Object> toMap() {
            return map("stop_hook_active", stopHookActive, "last_assistant_message", lastAssistantMessage,
                "agent_type", agentType);
        }
    }

    /** StopFailure 事件数据 · CC StopFailureHookInputSchema (coreSchemas.ts:529-538)：
     *  error / error_details? / last_assistant_message?。 */
    record StopFailure(String error, String errorDetails, String lastAssistantMessage)
            implements HookEventData {
        public Map<String, Object> toMap() {
            return map("error", error, "error_details", errorDetails,
                "last_assistant_message", lastAssistantMessage);
        }
    }

    /** Setup 事件数据 · CC SetupHookInputSchema (coreSchemas.ts:504-511)：trigger (init/maintenance)。 */
    record Setup(String trigger) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("trigger", trigger);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 用户交互 (2)
    // ════════════════════════════════════════════════════════════════════════

    /** UserPromptSubmit 事件数据 · CC UserPromptSubmitHookInputSchema (coreSchemas.ts:484-491)：prompt。 */
    record UserPromptSubmit(String prompt) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("prompt", prompt);
        }
    }

    /** Notification 事件数据 · CC NotificationHookInputSchema (coreSchemas.ts:473-482)：
     *  message / title? / notification_type。 */
    record Notification(String message, String title, String notificationType)
            implements HookEventData {
        public Map<String, Object> toMap() {
            return map("message", message, "title", title, "notification_type", notificationType);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 子代理 (2)
    // ════════════════════════════════════════════════════════════════════════

    /** SubagentStart 事件数据 · CC SubagentStartHookInputSchema (coreSchemas.ts:540-548)：
     *  agent_id / agent_type（session_id 为 Java 3 参便捷工厂透传冗余，序列化对齐旧载荷）。 */
    record SubagentStart(String agentId, String agentType, String sessionId) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("agent_id", agentId, "agent_type", agentType, "session_id", sessionId);
        }
    }

    /** SubagentStop 事件数据 · CC SubagentStopHookInputSchema (coreSchemas.ts:550-567)：
     *  stop_hook_active / agent_id / agent_transcript_path? / agent_type / last_assistant_message?。
     *  {@code stopHookActive} 可空 Boolean：3 参便捷工厂省略（对齐旧载荷），6/7 参恒写。 */
    record SubagentStop(String agentId, String agentType, Boolean stopHookActive,
                        String agentTranscriptPath, String lastAssistantMessage, String sessionId)
            implements HookEventData {
        public Map<String, Object> toMap() {
            return map("agent_id", agentId, "agent_type", agentType, "stop_hook_active", stopHookActive,
                "agent_transcript_path", agentTranscriptPath, "last_assistant_message", lastAssistantMessage,
                "session_id", sessionId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 压缩相关 (2)
    // ════════════════════════════════════════════════════════════════════════

    /** PreCompact 事件数据 · CC PreCompactHookInputSchema (coreSchemas.ts:569-577)：
     *  trigger (manual/auto) / custom_instructions? (nullable)。 */
    record PreCompact(String trigger, String customInstructions) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("trigger", trigger, "custom_instructions", customInstructions);
        }
    }

    /** PostCompact 事件数据 · CC PostCompactHookInputSchema (coreSchemas.ts:579-589)：
     *  trigger (manual/auto) / compact_summary。 */
    record PostCompact(String trigger, String compactSummary) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("trigger", trigger, "compact_summary", compactSummary);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 团队/任务 (3)
    // ════════════════════════════════════════════════════════════════════════

    /** TaskCreated 事件数据 · CC TaskCreatedHookInputSchema (coreSchemas.ts:601-612)：
     *  task_id / task_subject / task_description? / teammate_name? / team_name?
     *  + Java abortController 状态（abort_signal_cancelled/abort_signal_reason，OPD-WF4-LC-01
     *  收敛 null 前保留）。 */
    record TaskCreated(String taskId, String taskSubject, String taskDescription,
                       String teammateName, String teamName, Boolean abortSignalCancelled,
                       String abortSignalReason) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("task_id", taskId, "task_subject", taskSubject, "task_description", taskDescription,
                "teammate_name", teammateName, "team_name", teamName,
                "abort_signal_cancelled", abortSignalCancelled, "abort_signal_reason", abortSignalReason);
        }
    }

    /** TaskCompleted 事件数据 · CC TaskCompletedHookInputSchema (coreSchemas.ts:614-625)：
     *  同 TaskCreated。 */
    record TaskCompleted(String taskId, String taskSubject, String taskDescription,
                         String teammateName, String teamName, Boolean abortSignalCancelled,
                         String abortSignalReason) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("task_id", taskId, "task_subject", taskSubject, "task_description", taskDescription,
                "teammate_name", teammateName, "team_name", teamName,
                "abort_signal_cancelled", abortSignalCancelled, "abort_signal_reason", abortSignalReason);
        }
    }

    /** TeammateIdle 事件数据 · CC TeammateIdleHookInputSchema (coreSchemas.ts:591-599)：
     *  teammate_name / team_name。 */
    record TeammateIdle(String teammateName, String teamName) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("teammate_name", teammateName, "team_name", teamName);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MCP (2)
    // ════════════════════════════════════════════════════════════════════════

    /** Elicitation 事件数据 · CC ElicitationHookInputSchema (coreSchemas.ts:627-643)：
     *  mcp_server_name / message / mode (form|url)? / url? / elicitation_id?
     *  （requested_schema 由 HookEvent 顶层承载）。 */
    record Elicitation(String mcpServerName, String message, String mode, String url, String elicitationId)
            implements HookEventData {
        public Map<String, Object> toMap() {
            return map("mcp_server_name", mcpServerName, "message", message, "mode", mode,
                "url", url, "elicitation_id", elicitationId);
        }
    }

    /** ElicitationResult 事件数据 · CC ElicitationResultHookInputSchema (coreSchemas.ts:645-660)：
     *  mcp_server_name / elicitation_id? / mode? / action (accept|decline|cancel) / content?。 */
    record ElicitationResult(String mcpServerName, String action, String elicitationId, String mode,
                             Map<String, Object> content) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("mcp_server_name", mcpServerName, "action", action, "elicitation_id", elicitationId,
                "mode", mode, "content", content);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 配置/环境 (6)
    // ════════════════════════════════════════════════════════════════════════

    /** ConfigChange 事件数据 · CC ConfigChangeHookInputSchema (coreSchemas.ts:670-678)：
     *  source (CONFIG_CHANGE_SOURCES 5 值) / file_path?。 */
    record ConfigChange(String source, String filePath) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("source", source, "file_path", filePath);
        }
    }

    /** InstructionsLoaded 事件数据 · CC InstructionsLoadedHookInputSchema (coreSchemas.ts:695-707)：
     *  file_path / memory_type / load_reason / globs? / trigger_file_path? / parent_file_path?。 */
    record InstructionsLoaded(String filePath, String memoryType, String loadReason, List<String> globs,
                              String triggerFilePath, String parentFilePath) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("file_path", filePath, "memory_type", memoryType, "load_reason", loadReason,
                "globs", globs, "trigger_file_path", triggerFilePath, "parent_file_path", parentFilePath);
        }
    }

    /** WorktreeCreate 事件数据 · CC WorktreeCreateHookInputSchema (coreSchemas.ts:709-716)：name。 */
    record WorktreeCreate(String name) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("name", name);
        }
    }

    /** WorktreeRemove 事件数据 · CC WorktreeRemoveHookInputSchema (coreSchemas.ts:718-725)：
     *  worktree_path。 */
    record WorktreeRemove(String worktreePath) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("worktree_path", worktreePath);
        }
    }

    /** CwdChanged 事件数据 · CC CwdChangedHookInputSchema (coreSchemas.ts:727-735)：
     *  old_cwd / new_cwd。 */
    record CwdChanged(String oldCwd, String newCwd) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("old_cwd", oldCwd, "new_cwd", newCwd);
        }
    }

    /** FileChanged 事件数据 · CC FileChangedHookInputSchema (coreSchemas.ts:737-745)：
     *  file_path / event (change|add|unlink)。 */
    record FileChanged(String filePath, String event) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("file_path", filePath, "event", event);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 转换工具
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Map (snake_case KV) → 类型化 record · 供 {@code CommandHookExecutor.enrichBaseFields}
     * 在 base 字段合并（agent_type 注入）后重建类型化数据，以及兼容 Map 形式构造
     * （如 3 参 notification 便捷工厂）。null map → null。
     *
     * @param type 事件类型（决定 record 变体）
     * @param map  snake_case KV 载荷（与 {@link #toMap()} 同形状）
     * @return 对应类型化 record；map 为 null 时返回 null
     */
    static HookEventData fromMap(HookEventType type, Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return switch (type) {
            case PRE_TOOL_USE -> new PreToolUse(str(map.get("agent_type")));
            case POST_TOOL_USE -> new PostToolUse(str(map.get("agent_type")));
            case POST_TOOL_USE_FAILURE -> new PostToolUseFailure(str(map.get("error")),
                bool(map.get("is_interrupt")), str(map.get("tool_use_id")), str(map.get("agent_type")));
            case PERMISSION_REQUEST -> new PermissionRequest(str(map.get("agent_type")));
            case PERMISSION_DENIED -> new PermissionDenied(str(map.get("reason")),
                str(map.get("tool_use_id")), str(map.get("permission_mode")), str(map.get("agent_type")));
            case NOTIFICATION -> new Notification(str(map.get("message")), str(map.get("title")),
                str(map.get("notification_type")));
            case USER_PROMPT_SUBMIT -> new UserPromptSubmit(str(map.get("prompt")));
            case SESSION_START -> new SessionStart(str(map.get("source")), str(map.get("agent_type")),
                str(map.get("model")));
            case SESSION_END -> new SessionEnd(str(map.get("reason")), str(map.get("agent_type")));
            case STOP -> new Stop(bool(map.get("stop_hook_active")), str(map.get("last_assistant_message")),
                str(map.get("agent_type")));
            case STOP_FAILURE -> new StopFailure(str(map.get("error")), str(map.get("error_details")),
                str(map.get("last_assistant_message")));
            case SUBAGENT_START -> new SubagentStart(str(map.get("agent_id")), str(map.get("agent_type")),
                str(map.get("session_id")));
            case SUBAGENT_STOP -> new SubagentStop(str(map.get("agent_id")), str(map.get("agent_type")),
                boolOrNull(map.get("stop_hook_active")), str(map.get("agent_transcript_path")),
                str(map.get("last_assistant_message")), str(map.get("session_id")));
            case PRE_COMPACT -> new PreCompact(str(map.get("trigger")), str(map.get("custom_instructions")));
            case POST_COMPACT -> new PostCompact(str(map.get("trigger")), str(map.get("compact_summary")));
            case SETUP -> new Setup(str(map.get("trigger")));
            case TEAMMATE_IDLE -> new TeammateIdle(str(map.get("teammate_name")), str(map.get("team_name")));
            case TASK_CREATED -> new TaskCreated(str(map.get("task_id")), str(map.get("task_subject")),
                str(map.get("task_description")), str(map.get("teammate_name")), str(map.get("team_name")),
                boolOrNull(map.get("abort_signal_cancelled")), str(map.get("abort_signal_reason")));
            case TASK_COMPLETED -> new TaskCompleted(str(map.get("task_id")), str(map.get("task_subject")),
                str(map.get("task_description")), str(map.get("teammate_name")), str(map.get("team_name")),
                boolOrNull(map.get("abort_signal_cancelled")), str(map.get("abort_signal_reason")));
            case ELICITATION -> new Elicitation(str(map.get("mcp_server_name")), str(map.get("message")),
                str(map.get("mode")), str(map.get("url")), str(map.get("elicitation_id")));
            case ELICITATION_RESULT -> new ElicitationResult(str(map.get("mcp_server_name")),
                str(map.get("action")), str(map.get("elicitation_id")), str(map.get("mode")),
                mapOf(map.get("content")));
            case CONFIG_CHANGE -> new ConfigChange(str(map.get("source")), str(map.get("file_path")));
            case INSTRUCTIONS_LOADED -> new InstructionsLoaded(str(map.get("file_path")),
                str(map.get("memory_type")), str(map.get("load_reason")), strList(map.get("globs")),
                str(map.get("trigger_file_path")), str(map.get("parent_file_path")));
            case WORKTREE_CREATE -> new WorktreeCreate(str(map.get("name")));
            case WORKTREE_REMOVE -> new WorktreeRemove(str(map.get("worktree_path")));
            case CWD_CHANGED -> new CwdChanged(str(map.get("old_cwd")), str(map.get("new_cwd")));
            case FILE_CHANGED -> new FileChanged(str(map.get("file_path")), str(map.get("event")));
            // [IMP-CF-03] execCommandHook marker 事件（StatusLine/FileSuggestion）：CC coreTypes
            //  27 项之外，无类型化输入 record（markerEvent 恒 null data）→ null。
            case STATUS_LINE, FILE_SUGGESTION -> null;
        };
    }

    /** 构造 snake_case KV map · null 值省略（对齐旧工厂 {@code if (v != null) data.put(...)}）。 */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object key = kv[i];
            Object value = kv[i + 1];
            if (value != null) {
                m.put((String) key, value);
            }
        }
        return m;
    }

    private static String str(Object v) {
        return v instanceof String s ? s : null;
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b && b;
    }

    private static Boolean boolOrNull(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object v) {
        return v instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : null;
    }
}
