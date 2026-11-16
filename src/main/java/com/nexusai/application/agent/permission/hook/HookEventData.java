package com.nexusai.application.agent.permission.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hook 事件输入载荷 · 对齐 claude.exe v2.1.325 全部 33 种事件的
 * {@code HookInputSchema} 类型化输入 record（OPD-WF1-TY-02 补类型化，实施 IMP-CF-01；
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
    // 工具相关 (4)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * PreToolUse 事件数据 · CC PreToolUseHookInputSchema (coreSchemas.ts:414-423)。
     * tool_name/tool_input/tool_use_id 由 HookEvent 顶层承载；agent_type 由 enrichBaseFields 注入。
     *
     * @param mcpServer CC original: {@code mcp_server}（v2.1.325 新增，{@code {name, source}}）;
     *                  MCP 工具才有，非 MCP → null（序列化省略）
     */
    record PreToolUse(String agentType, Map<String, Object> mcpServer) implements HookEventData {
        /** 旧 1 参构造器（mcp_server 未知 → null）。 */
        public PreToolUse(String agentType) {
            this(agentType, null);
        }

        public Map<String, Object> toMap() {
            return map("agent_type", agentType, "mcp_server", mcpServer);
        }
    }

    /**
     * PostToolUse 事件数据 · CC PostToolUseHookInputSchema (coreSchemas.ts:436-446)。
     * tool_name/tool_input/tool_response/tool_use_id 由 HookEvent 顶层承载。
     *
     * @param mcpServer  CC original: {@code mcp_server}（v2.1.325，{@code {name, source}}）
     * @param durationMs CC original: {@code duration_ms}（v2.1.325，工具执行耗时，不含权限询问/hook）
     */
    record PostToolUse(String agentType, Map<String, Object> mcpServer, Long durationMs)
            implements HookEventData {
        /** 旧 1 参构造器。 */
        public PostToolUse(String agentType) {
            this(agentType, null, null);
        }

        public Map<String, Object> toMap() {
            return map("agent_type", agentType, "mcp_server", mcpServer, "duration_ms", durationMs);
        }
    }

    /** PostToolUseFailure 事件数据 · CC PostToolUseFailureHookInputSchema (coreSchemas.ts:448-459)。
     *  tool_name/tool_input 由 HookEvent 顶层承载；error/is_interrupt/tool_use_id 事件特定。
     *  {@code is_interrupt} 恒序列化（旧工厂无条件 put）。 */
    record PostToolUseFailure(String error, boolean isInterrupt, String toolUseId, String agentType,
                              Map<String, Object> mcpServer, Long durationMs)
            implements HookEventData {
        /** 旧 4 参构造器（mcp_server/duration_ms 未知 → null）。 */
        public PostToolUseFailure(String error, boolean isInterrupt, String toolUseId, String agentType) {
            this(error, isInterrupt, toolUseId, agentType, null, null);
        }

        public Map<String, Object> toMap() {
            return map("error", error, "is_interrupt", isInterrupt, "tool_use_id", toolUseId,
                "agent_type", agentType, "mcp_server", mcpServer, "duration_ms", durationMs);
        }
    }

    /**
     * PostToolBatch 事件数据 · [Wave1-A] 对齐 claude.exe v2.1.325 {@code R2} schema
     * （off≈311,04x,xxx）：{@code hook_event_name, tool_calls[]}。
     *
     * <p>一批工具调用全部 resolved 后只触发一次（PostToolUse 逐工具并发触发）。
     * 每个元素字段：{@code tool_name, tool_input, tool_use_id, tool_response?}
     * （CC 内部 {@code v2()} schema）。
     *
     * @param toolCalls CC original: {@code tool_calls}（CC 必传数组；元素为上面的 KV map）
     */
    record PostToolBatch(List<Map<String, Object>> toolCalls) implements HookEventData {
        public PostToolBatch {
            if (toolCalls != null) {
                toolCalls = List.copyOf(toolCalls);
            }
        }
        public Map<String, Object> toMap() {
            return map("tool_calls", toolCalls);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 权限相关 (2)
    // ════════════════════════════════════════════════════════════════════════

    /** PermissionRequest 事件数据 · CC PermissionRequestHookInputSchema (coreSchemas.ts:425-434)。
     *  tool_name/tool_input/permission_suggestions 由 HookEvent 顶层承载。 */
    record PermissionRequest(String agentType, Map<String, Object> mcpServer) implements HookEventData {
        /** 旧 1 参构造器。 */
        public PermissionRequest(String agentType) {
            this(agentType, null);
        }

        public Map<String, Object> toMap() {
            return map("agent_type", agentType, "mcp_server", mcpServer);
        }
    }

    /** PermissionDenied 事件数据 · CC PermissionDeniedHookInputSchema (coreSchemas.ts:461-471)。
     *  tool_name/tool_input 由 HookEvent 顶层承载；reason/tool_use_id/permission_mode 事件特定。
     *  {@code permissionMode} 支持 PermissionDeniedHookExecutor 直构（permission_mode 同时顶层+data）。 */
    record PermissionDenied(String reason, String toolUseId, String permissionMode, String agentType,
                            Map<String, Object> mcpServer)
            implements HookEventData {
        /** 旧 4 参构造器。 */
        public PermissionDenied(String reason, String toolUseId, String permissionMode, String agentType) {
            this(reason, toolUseId, permissionMode, agentType, null);
        }

        public Map<String, Object> toMap() {
            return map("reason", reason, "tool_use_id", toolUseId, "permission_mode", permissionMode,
                "agent_type", agentType, "mcp_server", mcpServer);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 会话相关 (5)
    // ════════════════════════════════════════════════════════════════════════

    /** SessionStart 事件数据 · CC SessionStartHookInputSchema (coreSchemas.ts:493-502)：
     *  source (startup/resume/clear/compact) / agent_type? / model?。 */
    record SessionStart(String source, String agentType, String model,
                        String sessionTitle, Long secondsSinceLastResponse, Long contextTokens,
                        Boolean promptCacheLikelyExpired, Double estimatedCacheWriteUsd)
            implements HookEventData {
        /** 旧 3 参构造器（v2.1.325 追加字段未知 → null）。 */
        public SessionStart(String source, String agentType, String model) {
            this(source, agentType, model, null, null, null, null, null);
        }

        public Map<String, Object> toMap() {
            return map("source", source, "agent_type", agentType, "model", model,
                "session_title", sessionTitle,
                "seconds_since_last_response", secondsSinceLastResponse,
                "context_tokens", contextTokens,
                "prompt_cache_likely_expired", promptCacheLikelyExpired,
                "estimated_cache_write_usd", estimatedCacheWriteUsd);
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
    record Stop(boolean stopHookActive, String lastAssistantMessage, String agentType,
                List<Map<String, Object>> backgroundTasks,
                List<Map<String, Object>> sessionCrons)
            implements HookEventData {
        /** 旧 3 参构造器（v2.1.325 追加字段未知 → null）。 */
        public Stop(boolean stopHookActive, String lastAssistantMessage, String agentType) {
            this(stopHookActive, lastAssistantMessage, agentType, null, null);
        }

        public Stop {
            if (backgroundTasks != null) {
                backgroundTasks = List.copyOf(backgroundTasks);
            }
            if (sessionCrons != null) {
                sessionCrons = List.copyOf(sessionCrons);
            }
        }

        public Map<String, Object> toMap() {
            return map("stop_hook_active", stopHookActive, "last_assistant_message", lastAssistantMessage,
                "agent_type", agentType, "background_tasks", backgroundTasks,
                "session_crons", sessionCrons);
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
    // 用户交互 (3)
    // ════════════════════════════════════════════════════════════════════════

    /** UserPromptSubmit 事件数据 · CC UserPromptSubmitHookInputSchema (coreSchemas.ts:484-491)：prompt。 */
    record UserPromptSubmit(String prompt, String source, String sessionTitle)
            implements HookEventData {
        /** 旧 1 参构造器（source/session_title 未知 → null）。 */
        public UserPromptSubmit(String prompt) {
            this(prompt, null, null);
        }

        public Map<String, Object> toMap() {
            return map("prompt", prompt, "source", source, "session_title", sessionTitle);
        }
    }

    /**
     * UserPromptExpansion 事件数据 · [Wave1-A] 对齐 claude.exe v2.1.325 {@code x2} schema
     * （off≈311,04x,xxx）：{@code expansion_type(slash_command|mcp_prompt), command_name,
     * command_args, command_source?, prompt}。
     *
     * <p>斜杠命令 / MCP prompt 展开成 prompt 之前触发；exit 2 可拦住展开。
     * matcher 匹配字段是 {@code command_name}（CC {@code Eon} off≈323,293,8xx）。
     *
     * @param expansionType   CC original: {@code expansion_type}
     * @param commandName     CC original: {@code command_name}
     * @param commandArgs     CC original: {@code command_args}
     * @param commandSource   CC original: {@code command_source}（optional）
     * @param prompt          CC original: {@code prompt}（展开前的原始 prompt 文本）
     */
    record UserPromptExpansion(String expansionType, String commandName, String commandArgs,
                               String commandSource, String prompt) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("expansion_type", expansionType, "command_name", commandName,
                "command_args", commandArgs, "command_source", commandSource, "prompt", prompt);
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
                        String agentTranscriptPath, String lastAssistantMessage, String sessionId,
                        List<Map<String, Object>> backgroundTasks,
                        List<Map<String, Object>> sessionCrons)
            implements HookEventData {
        /** 旧 6 参构造器（v2.1.325 追加字段未知 → null）。 */
        public SubagentStop(String agentId, String agentType, Boolean stopHookActive,
                            String agentTranscriptPath, String lastAssistantMessage, String sessionId) {
            this(agentId, agentType, stopHookActive, agentTranscriptPath, lastAssistantMessage,
                sessionId, null, null);
        }

        public SubagentStop {
            if (backgroundTasks != null) {
                backgroundTasks = List.copyOf(backgroundTasks);
            }
            if (sessionCrons != null) {
                sessionCrons = List.copyOf(sessionCrons);
            }
        }

        public Map<String, Object> toMap() {
            return map("agent_id", agentId, "agent_type", agentType, "stop_hook_active", stopHookActive,
                "agent_transcript_path", agentTranscriptPath, "last_assistant_message", lastAssistantMessage,
                "session_id", sessionId, "background_tasks", backgroundTasks,
                "session_crons", sessionCrons);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 压缩/模型 (4)
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

    /**
     * PreModelSwitch 事件数据 · [Wave1-A] 对齐 claude.exe v2.1.325 {@code H2}（off≈311,04x,xxx）
     * 复用 {@code lC(["command","picker","sdk"])} 字段集。
     *
     * <p>模型切换前触发；输出可带 {@code permissionDecision}(allow/deny/ask) ——
     * deny 取消切换、ask 让用户确认（headless 直接拒）。
     *
     * @param fromModel              CC original: {@code from_model}（切换前解析后的模型 id）
     * @param toModel                CC original: {@code to_model}（切换后模型 id；matcher 匹配字段）
     * @param requestedModel         CC original: {@code requested_model}（nullable：别名 / 全 id / null=默认）
     * @param source                 CC original: {@code source}（command|picker|sdk）
     * @param contextTokens          CC original: {@code context_tokens}（下次请求重发的 token 数）
     * @param promptCacheWarm        CC original: {@code prompt_cache_warm}（缓存是否仍热）
     * @param cacheTtl               CC original: {@code cache_ttl}（5m|1h）
     * @param estimatedCacheWriteUsd CC original: {@code estimated_cache_write_usd}（重缓存估算成本）
     * @param pricing                CC original: {@code pricing}（configured|catalog|default）
     */
    record PreModelSwitch(String fromModel, String toModel, String requestedModel, String source,
                          Long contextTokens, Boolean promptCacheWarm, String cacheTtl,
                          Double estimatedCacheWriteUsd, String pricing) implements HookEventData {
        public Map<String, Object> toMap() {
            // CC schema 的 requested_model 是 nullable 但**键始终存在**（显式 null）；
            // 通用 map() 会省略 null 值，故这里单独 put。
            Map<String, Object> m = map("from_model", fromModel, "to_model", toModel,
                "source", source, "context_tokens", contextTokens, "prompt_cache_warm", promptCacheWarm,
                "cache_ttl", cacheTtl, "estimated_cache_write_usd", estimatedCacheWriteUsd,
                "pricing", pricing);
            m.put("requested_model", requestedModel);
            return m;
        }
    }

    /**
     * PostModelSwitch 事件数据 · [Wave1-A] 同 {@link PreModelSwitch} 字段集
     * （CC {@code G2} + {@code lC(["command","picker","sdk","auto","resume"])}）。
     *
     * <p>切换后触发；输出 {@code additionalContext} 进新模型下一次请求。
     */
    record PostModelSwitch(String fromModel, String toModel, String requestedModel, String source,
                           Long contextTokens, Boolean promptCacheWarm, String cacheTtl,
                           Double estimatedCacheWriteUsd, String pricing) implements HookEventData {
        public Map<String, Object> toMap() {
            // 同 PreModelSwitch：requested_model 键始终存在（显式 null）
            Map<String, Object> m = map("from_model", fromModel, "to_model", toModel,
                "source", source, "context_tokens", contextTokens, "prompt_cache_warm", promptCacheWarm,
                "cache_ttl", cacheTtl, "estimated_cache_write_usd", estimatedCacheWriteUsd,
                "pricing", pricing);
            m.put("requested_model", requestedModel);
            return m;
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
    // 目录/展示 (2)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * DirectoryAdded 事件数据 · [Wave1-A] 对齐 claude.exe v2.1.325 {@code iX} schema
     * （off≈311,04x,xxx）：{@code directory, source(slash_command|register_repo_root)}。
     *
     * <p>{@code /add-dir} 或 SDK {@code register_repo_root} 注册新工作目录后、沙箱配置已刷新时触发。
     * matcher 匹配字段是 {@code source}（CC {@code Eon} off≈323,293,8xx），不是 directory。
     *
     * @param directory CC original: {@code directory}（新增目录的绝对路径）
     * @param source    CC original: {@code source}（slash_command=/add-dir；register_repo_root=SDK）
     */
    record DirectoryAdded(String directory, String source) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("directory", directory, "source", source);
        }
    }

    /**
     * MessageDisplay 事件数据 · [Wave1-A] 对齐 claude.exe v2.1.325 {@code lX} schema
     * （off≈311,04x,xxx）：{@code turn_id, message_id, index, final, delta}。
     *
     * <p>助手消息流式显示时每批新完成行触发一次；输出 {@code displayContent} 只替换屏幕上的
     * 文本，不改存储消息、也不改模型看到的内容。
     *
     * @param turnId     CC original: {@code turn_id}（当前轮 UUID）
     * @param messageId  CC original: {@code message_id}（被显示助手消息 UUID，跨 flush 稳定）
     * @param index      CC original: {@code index}（该消息内第几个 delta，从 0 起）
     * @param finalFlush CC original: {@code final}（Java 关键字避让；消息最后一次 flush=true）
     * @param delta      CC original: {@code delta}（自上次 flush 起新完成的行；末次可能半行）
     */
    record MessageDisplay(String turnId, String messageId, Integer index, Boolean finalFlush,
                          String delta) implements HookEventData {
        public Map<String, Object> toMap() {
            return map("turn_id", turnId, "message_id", messageId, "index", index,
                "final", finalFlush, "delta", delta);
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
            case PRE_TOOL_USE -> new PreToolUse(str(map.get("agent_type")),
                mapOf(map.get("mcp_server")));
            case POST_TOOL_USE -> new PostToolUse(str(map.get("agent_type")),
                mapOf(map.get("mcp_server")), longOf(map.get("duration_ms")));
            case POST_TOOL_USE_FAILURE -> new PostToolUseFailure(str(map.get("error")),
                bool(map.get("is_interrupt")), str(map.get("tool_use_id")), str(map.get("agent_type")),
                mapOf(map.get("mcp_server")), longOf(map.get("duration_ms")));
            case POST_TOOL_BATCH -> new PostToolBatch(listOfMaps(map.get("tool_calls")));
            case USER_PROMPT_EXPANSION -> new UserPromptExpansion(str(map.get("expansion_type")),
                str(map.get("command_name")), str(map.get("command_args")),
                str(map.get("command_source")), str(map.get("prompt")));
            case PERMISSION_REQUEST -> new PermissionRequest(str(map.get("agent_type")),
                mapOf(map.get("mcp_server")));
            case PERMISSION_DENIED -> new PermissionDenied(str(map.get("reason")),
                str(map.get("tool_use_id")), str(map.get("permission_mode")), str(map.get("agent_type")),
                mapOf(map.get("mcp_server")));
            case NOTIFICATION -> new Notification(str(map.get("message")), str(map.get("title")),
                str(map.get("notification_type")));
            case USER_PROMPT_SUBMIT -> new UserPromptSubmit(str(map.get("prompt")),
                str(map.get("source")), str(map.get("session_title")));
            case SESSION_START -> new SessionStart(str(map.get("source")), str(map.get("agent_type")),
                str(map.get("model")), str(map.get("session_title")),
                longOf(map.get("seconds_since_last_response")),
                longOf(map.get("context_tokens")),
                boolOrNull(map.get("prompt_cache_likely_expired")),
                doubleOf(map.get("estimated_cache_write_usd")));
            case SESSION_END -> new SessionEnd(str(map.get("reason")), str(map.get("agent_type")));
            case STOP -> new Stop(bool(map.get("stop_hook_active")), str(map.get("last_assistant_message")),
                str(map.get("agent_type")), listOfMaps(map.get("background_tasks")),
                listOfMaps(map.get("session_crons")));
            case STOP_FAILURE -> new StopFailure(str(map.get("error")), str(map.get("error_details")),
                str(map.get("last_assistant_message")));
            case SUBAGENT_START -> new SubagentStart(str(map.get("agent_id")), str(map.get("agent_type")),
                str(map.get("session_id")));
            case SUBAGENT_STOP -> new SubagentStop(str(map.get("agent_id")), str(map.get("agent_type")),
                boolOrNull(map.get("stop_hook_active")), str(map.get("agent_transcript_path")),
                str(map.get("last_assistant_message")), str(map.get("session_id")),
                listOfMaps(map.get("background_tasks")), listOfMaps(map.get("session_crons")));
            case PRE_COMPACT -> new PreCompact(str(map.get("trigger")), str(map.get("custom_instructions")));
            case POST_COMPACT -> new PostCompact(str(map.get("trigger")), str(map.get("compact_summary")));
            case PRE_MODEL_SWITCH -> new PreModelSwitch(str(map.get("from_model")),
                str(map.get("to_model")), str(map.get("requested_model")), str(map.get("source")),
                longOf(map.get("context_tokens")), boolOrNull(map.get("prompt_cache_warm")),
                str(map.get("cache_ttl")), doubleOf(map.get("estimated_cache_write_usd")),
                str(map.get("pricing")));
            case POST_MODEL_SWITCH -> new PostModelSwitch(str(map.get("from_model")),
                str(map.get("to_model")), str(map.get("requested_model")), str(map.get("source")),
                longOf(map.get("context_tokens")), boolOrNull(map.get("prompt_cache_warm")),
                str(map.get("cache_ttl")), doubleOf(map.get("estimated_cache_write_usd")),
                str(map.get("pricing")));
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
            case DIRECTORY_ADDED -> new DirectoryAdded(str(map.get("directory")), str(map.get("source")));
            case MESSAGE_DISPLAY -> new MessageDisplay(str(map.get("turn_id")),
                str(map.get("message_id")), intOf(map.get("index")), boolOrNull(map.get("final")),
                str(map.get("delta")));
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

    /** 值为 {@code List<Map<String,Object>>}（如 PostToolBatch.tool_calls）→ 拷贝列表；否则 null。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object v) {
        if (!(v instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add(new LinkedHashMap<>((Map<String, Object>) m));
            }
        }
        return out;
    }

    /** 数值（Integer/Long/Short）→ Long；其他 → null（模型切换 context_tokens）。 */
    private static Long longOf(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    /** 数值（Integer/Long/Double/Float）→ Double；其他 → null（重缓存成本估算）。 */
    private static Double doubleOf(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    /** 数值 → Integer；其他 → null（MessageDisplay.index）。 */
    private static Integer intOf(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
