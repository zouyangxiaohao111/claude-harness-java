package com.nexusai.application.agent.permission.hook;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hook 配置 UI 展示层 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/hooksConfigManager.ts} (401 行).
 *
 * <p>WHY (决策 4-2, open-decisions.md): CC 有一整套 hook 配置展示函数（按事件分组、按优先级
 * 排序、来源显示等 15 项），Java 全未实现（判 N/A）。用户拍板「后端直接实现」（open-decisions.md
 * 4-2: "后端实现 15 项展示函数对齐 CC（WF1-02 U4），不管前端是否消费"）—— 本类承载之，
 * 供未来前端 hook 配置展示/编辑面板消费（register 备注 "后端实现展示函数"）。
 *
 * <p><b>CC 真源函数 (hooksConfigManager.ts)</b>:
 * <ul>
 *   <li>{@link #getHookEventMetadata(List)} (:26-267) — memoize 缓存（缓存键
 *       {@code toolNames.slice().sort().join(',')}），返回 27 事件元数据表</li>
 *   <li>{@link #groupHooksByEventAndMatcher(List, List)} (:270-365) — 按事件→matcherKey→hooks 分组</li>
 *   <li>{@link #getSortedMatchersForEvent(Map, HookEventType)} (:368-377) — 取 matcher 键数组 → 按优先级排序</li>
 *   <li>{@link #getHooksForMatcher(Map, HookEventType, String)} (:380-392) — 取指定 event+matcher 的 hooks</li>
 *   <li>{@link #getMatcherMetadata(HookEventType, List)} (:395-400) — 返回某事件 matcher 元数据</li>
 * </ul>
 *
 * <p><b>类型 (hooksConfigManager.ts:11-20)</b>:
 * <ul>
 *   <li>{@link MatcherMetadata} (:11-14) — {@code {fieldToMatch, values}}</li>
 *   <li>{@link HookEventMetadata} (:16-20) — {@code {summary, description, matcherMetadata?}}</li>
 * </ul>
 *
 * <p><b>Java 适配</b>:
 * <ul>
 *   <li>CC 模块级 memoize → bean 实例 {@link ConcurrentHashMap} 缓存（同缓存键语义）</li>
 *   <li>CC {@code appState} 承载的 settings+session hooks → 方法入参 {@code List<IndividualHookConfig>}
 *       （纯函数，可测性优先；session 合并见 {@link HooksSettings#getAllHooks(String)}）</li>
 *   <li>CC {@code getRegisteredHooks()}（插件 hook / ant-only 内置）→ WF-7 SDK 注册域 / 组织内部开关，
 *       Java 端经 PluginLoader 独立链，不在本类注入（EV-WF1-CFG-009/016 已登记）</li>
 * </ul>
 *
 * <p><b>local-only 约束</b>: 本类仅本地 hook 配置查询，不外发。
 */
@Component
public class HooksConfigManager {

    /** getHookEventMetadata 缓存 · 键 = toolNames 排序后 join(",")（CC :266-267 缓存键等价）. */
    private final Map<String, Map<HookEventType, HookEventMetadata>> metadataCache = new ConcurrentHashMap<>();

    /**
     * [OPD-WF1-CFG-01 接线] HooksSettings · 生产 session 合并入口的载体.
     *
     * <p>WHY: CC groupHooksByEventAndMatcher (hooksConfigManager.ts:270-365) 内部调用
     * getAllHooks(appState)（settings+session 合并, hooksSettings.ts:92-161）; Java 纯函数版
     * {@link #groupHooksByEventAndMatcher(List, List)} 由调用方传入已合并 hooks 列表。本字段 +
     * {@link #groupHooksByEventAndMatcher(String, List)} 便捷入口把「getAllHooks(sessionId) → 分组」
     * 的生产接线收敛在本类（对齐 CC 内部调用链）。sessionHooksProvider 由 {@link HookRegistry}
     * 生产注入（SessionHookStore 读取器）。
     *
     * <p>{@code @Autowired(required=false)}: 手动 new 场景为 null → 便捷入口返回 settings 未接线
     * 的空分组（27 事件键仍存在, 无 hooks）。
     */
    private volatile HooksSettings hooksSettings;

    @Autowired(required = false)
    public void setHooksSettings(HooksSettings hooksSettings) {
        this.hooksSettings = hooksSettings;
    }

    /**
     * Matcher 元数据 · 对齐 CC {@code hooksConfigManager.ts:11-14} {@code MatcherMetadata}.
     *
     * @param fieldToMatch CC original: fieldToMatch (hooksConfigManager.ts:12); 匹配字段名
     * @param values       CC original: values (hooksConfigManager.ts:13); 可选匹配值（如工具名列表）
     */
    public record MatcherMetadata(String fieldToMatch, List<String> values) {
        public MatcherMetadata {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /**
     * Hook 事件元数据 · 对齐 CC {@code hooksConfigManager.ts:16-20} {@code HookEventMetadata}.
     *
     * @param summary        CC original: summary (hooksConfigManager.ts:17); 事件摘要
     * @param description    CC original: description (hooksConfigManager.ts:18); 事件描述
     * @param matcherMetadata CC original: matcherMetadata (hooksConfigManager.ts:19); 可选 matcher 元数据
     */
    public record HookEventMetadata(String summary, String description, MatcherMetadata matcherMetadata) {
    }

    /**
     * 获取 27 事件元数据表 · 对齐 CC {@code hooksConfigManager.ts:26-267}
     * {@code getHookEventMetadata(toolNames)}（memoize + 排序 join 缓存键）。
     *
     * <p><b>memoize 语义 (CC :266-267)</b>: 缓存键 = {@code toolNames.slice().sort().join(',')} —
     * 调用方每次渲染传新数组也命中缓存（避免每次新建表）。Java 端 {@link ConcurrentHashMap}
     * computeIfAbsent 等价。toolNames 为工具名列表（PreToolUse/PostToolUse/PostToolUseFailure/
     * PermissionDenied/PermissionRequest 的 matcherMetadata.values）。
     *
     * @param toolNames 可用工具名列表（null → 空列表）
     * @return 27 事件 → HookEventMetadata 不可变映射
     */
    public Map<HookEventType, HookEventMetadata> getHookEventMetadata(List<String> toolNames) {
        // CC :266-267 缓存键: toolNames.slice().sort().join(',')
        String key = (toolNames == null ? List.<String>of() : toolNames).stream()
            .sorted().collect(java.util.stream.Collectors.joining(","));
        return metadataCache.computeIfAbsent(key, k -> buildHookEventMetadata(toolNames));
    }

    /**
     * 构建 27 事件元数据表 · 逐条对齐 CC {@code hooksConfigManager.ts:28-264} 返回对象字面量
     * （summary/description 逐字；matcherMetadata 仅 CC 定义的 18 个事件有：
     * PreToolUse/PostToolUse/PostToolUseFailure/PermissionDenied/Notification/SessionStart/
     * StopFailure/SubagentStart/SubagentStop/PreCompact/PostCompact/SessionEnd/PermissionRequest/
     * Setup/Elicitation/ElicitationResult/ConfigChange/InstructionsLoaded）。
     *
     * <p><b>无 matcherMetadata 的 9 事件</b>: UserPromptSubmit/Stop/TeammateIdle/TaskCreated/
     * TaskCompleted/WorktreeCreate/WorktreeRemove/CwdChanged/FileChanged（CC 真源缺省 undefined）。
     *
     * @param toolNames 工具名列表（tool_name 类事件的 values）
     * @return 27 事件 → HookEventMetadata（不可变）
     */
    private Map<HookEventType, HookEventMetadata> buildHookEventMetadata(List<String> toolNames) {
        Map<HookEventType, HookEventMetadata> table = new LinkedHashMap<>();
        table.put(HookEventType.PRE_TOOL_USE, new HookEventMetadata(
            "Before tool execution",
            "Input to command is JSON of tool call arguments.\nExit code 0 - stdout/stderr not shown\nExit code 2 - show stderr to model and block tool call\nOther exit codes - show stderr to user only but continue with tool call",
            new MatcherMetadata("tool_name", toolNames)));
        table.put(HookEventType.POST_TOOL_USE, new HookEventMetadata(
            "After tool execution",
            "Input to command is JSON with fields \"inputs\" (tool call arguments) and \"response\" (tool call response).\nExit code 0 - stdout shown in transcript mode (ctrl+o)\nExit code 2 - show stderr to model immediately\nOther exit codes - show stderr to user only",
            new MatcherMetadata("tool_name", toolNames)));
        table.put(HookEventType.POST_TOOL_USE_FAILURE, new HookEventMetadata(
            "After tool execution fails",
            "Input to command is JSON with tool_name, tool_input, tool_use_id, error, error_type, is_interrupt, and is_timeout.\nExit code 0 - stdout shown in transcript mode (ctrl+o)\nExit code 2 - show stderr to model immediately\nOther exit codes - show stderr to user only",
            new MatcherMetadata("tool_name", toolNames)));
        table.put(HookEventType.PERMISSION_DENIED, new HookEventMetadata(
            "After auto mode classifier denies a tool call",
            "Input to command is JSON with tool_name, tool_input, tool_use_id, and reason.\nReturn {\"hookSpecificOutput\":{\"hookEventName\":\"PermissionDenied\",\"retry\":true}} to tell the model it may retry.\nExit code 0 - stdout shown in transcript mode (ctrl+o)\nOther exit codes - show stderr to user only",
            new MatcherMetadata("tool_name", toolNames)));
        table.put(HookEventType.NOTIFICATION, new HookEventMetadata(
            "When notifications are sent",
            "Input to command is JSON with notification message and type.\nExit code 0 - stdout/stderr not shown\nOther exit codes - show stderr to user only",
            new MatcherMetadata("notification_type", List.of(
                "permission_prompt", "idle_prompt", "auth_success",
                "elicitation_dialog", "elicitation_complete", "elicitation_response"))));
        table.put(HookEventType.USER_PROMPT_SUBMIT, new HookEventMetadata(
            "When the user submits a prompt",
            "Input to command is JSON with original user prompt text.\nExit code 0 - stdout shown to Claude\nExit code 2 - block processing, erase original prompt, and show stderr to user only\nOther exit codes - show stderr to user only",
            null));
        table.put(HookEventType.SESSION_START, new HookEventMetadata(
            "When a new session is started",
            "Input to command is JSON with session start source.\nExit code 0 - stdout shown to Claude\nBlocking errors are ignored\nOther exit codes - show stderr to user only",
            new MatcherMetadata("source", List.of("startup", "resume", "clear", "compact"))));
        table.put(HookEventType.STOP, new HookEventMetadata(
            "Right before Claude concludes its response",
            "Exit code 0 - stdout/stderr not shown\nExit code 2 - show stderr to model and continue conversation\nOther exit codes - show stderr to user only",
            null));
        table.put(HookEventType.STOP_FAILURE, new HookEventMetadata(
            "When the turn ends due to an API error",
            "Fires instead of Stop when an API error (rate limit, auth failure, etc.) ended the turn. Fire-and-forget — hook output and exit codes are ignored.",
            new MatcherMetadata("error", List.of(
                "rate_limit", "authentication_failed", "billing_error",
                "invalid_request", "server_error", "max_output_tokens", "unknown"))));
        table.put(HookEventType.SUBAGENT_START, new HookEventMetadata(
            "When a subagent (Agent tool call) is started",
            "Input to command is JSON with agent_id and agent_type.\nExit code 0 - stdout shown to subagent\nBlocking errors are ignored\nOther exit codes - show stderr to user only",
            new MatcherMetadata("agent_type", List.of())));
        table.put(HookEventType.SUBAGENT_STOP, new HookEventMetadata(
            "Right before a subagent (Agent tool call) concludes its response",
            "Input to command is JSON with agent_id, agent_type, and agent_transcript_path.\nExit code 0 - stdout/stderr not shown\nExit code 2 - show stderr to subagent and continue having it run\nOther exit codes - show stderr to user only",
            new MatcherMetadata("agent_type", List.of())));
        table.put(HookEventType.PRE_COMPACT, new HookEventMetadata(
            "Before conversation compaction",
            "Input to command is JSON with compaction details.\nExit code 0 - stdout appended as custom compact instructions\nExit code 2 - block compaction\nOther exit codes - show stderr to user only but continue with compaction",
            new MatcherMetadata("trigger", List.of("manual", "auto"))));
        table.put(HookEventType.POST_COMPACT, new HookEventMetadata(
            "After conversation compaction",
            "Input to command is JSON with compaction details and the summary.\nExit code 0 - stdout shown to user\nOther exit codes - show stderr to user only",
            new MatcherMetadata("trigger", List.of("manual", "auto"))));
        table.put(HookEventType.SESSION_END, new HookEventMetadata(
            "When a session is ending",
            "Input to command is JSON with session end reason.\nExit code 0 - command completes successfully\nOther exit codes - show stderr to user only",
            new MatcherMetadata("reason", List.of("clear", "logout", "prompt_input_exit", "other"))));
        table.put(HookEventType.PERMISSION_REQUEST, new HookEventMetadata(
            "When a permission dialog is displayed",
            "Input to command is JSON with tool_name, tool_input, and tool_use_id.\nOutput JSON with hookSpecificOutput containing decision to allow or deny.\nExit code 0 - use hook decision if provided\nOther exit codes - show stderr to user only",
            new MatcherMetadata("tool_name", toolNames)));
        table.put(HookEventType.SETUP, new HookEventMetadata(
            "Repo setup hooks for init and maintenance",
            "Input to command is JSON with trigger (init or maintenance).\nExit code 0 - stdout shown to Claude\nBlocking errors are ignored\nOther exit codes - show stderr to user only",
            new MatcherMetadata("trigger", List.of("init", "maintenance"))));
        table.put(HookEventType.TEAMMATE_IDLE, new HookEventMetadata(
            "When a teammate is about to go idle",
            "Input to command is JSON with teammate_name and team_name.\nExit code 0 - stdout/stderr not shown\nExit code 2 - show stderr to teammate and prevent idle (teammate continues working)\nOther exit codes - show stderr to user only",
            null));
        table.put(HookEventType.TASK_CREATED, new HookEventMetadata(
            "When a task is being created",
            "Input to command is JSON with task_id, task_subject, task_description, teammate_name, and team_name.\nExit code 0 - stdout/stderr not shown\nExit code 2 - show stderr to model and prevent task creation\nOther exit codes - show stderr to user only",
            null));
        table.put(HookEventType.TASK_COMPLETED, new HookEventMetadata(
            "When a task is being marked as completed",
            "Input to command is JSON with task_id, task_subject, task_description, teammate_name, and team_name.\nExit code 0 - stdout/stderr not shown\nExit code 2 - show stderr to model and prevent task completion\nOther exit codes - show stderr to user only",
            null));
        table.put(HookEventType.ELICITATION, new HookEventMetadata(
            "When an MCP server requests user input (elicitation)",
            "Input to command is JSON with mcp_server_name, message, and requested_schema.\nOutput JSON with hookSpecificOutput containing action (accept/decline/cancel) and optional content.\nExit code 0 - use hook response if provided\nExit code 2 - deny the elicitation\nOther exit codes - show stderr to user only",
            new MatcherMetadata("mcp_server_name", List.of())));
        table.put(HookEventType.ELICITATION_RESULT, new HookEventMetadata(
            "After a user responds to an MCP elicitation",
            "Input to command is JSON with mcp_server_name, action, content, mode, and elicitation_id.\nOutput JSON with hookSpecificOutput containing optional action and content to override the response.\nExit code 0 - use hook response if provided\nExit code 2 - block the response (action becomes decline)\nOther exit codes - show stderr to user only",
            new MatcherMetadata("mcp_server_name", List.of())));
        table.put(HookEventType.CONFIG_CHANGE, new HookEventMetadata(
            "When configuration files change during a session",
            "Input to command is JSON with source (user_settings, project_settings, local_settings, policy_settings, skills) and file_path.\nExit code 0 - allow the change\nExit code 2 - block the change from being applied to the session\nOther exit codes - show stderr to user only",
            new MatcherMetadata("source", List.of(
                "user_settings", "project_settings", "local_settings", "policy_settings", "skills"))));
        table.put(HookEventType.INSTRUCTIONS_LOADED, new HookEventMetadata(
            "When an instruction file (CLAUDE.md or rule) is loaded",
            "Input to command is JSON with file_path, memory_type (User, Project, Local, Managed), load_reason (session_start, nested_traversal, path_glob_match, include, compact), globs (optional — the paths: frontmatter patterns that matched), trigger_file_path (optional — the file Claude touched that caused the load), and parent_file_path (optional — the file that @-included this one).\nExit code 0 - command completes successfully\nOther exit codes - show stderr to user only\nThis hook is observability-only and does not support blocking.",
            new MatcherMetadata("load_reason", List.of(
                "session_start", "nested_traversal", "path_glob_match", "include", "compact"))));
        table.put(HookEventType.WORKTREE_CREATE, new HookEventMetadata(
            "Create an isolated worktree for VCS-agnostic isolation",
            "Input to command is JSON with name (suggested worktree slug).\nStdout should contain the absolute path to the created worktree directory.\nExit code 0 - worktree created successfully\nOther exit codes - worktree creation failed",
            null));
        table.put(HookEventType.WORKTREE_REMOVE, new HookEventMetadata(
            "Remove a previously created worktree",
            "Input to command is JSON with worktree_path (absolute path to worktree).\nExit code 0 - worktree removed successfully\nOther exit codes - show stderr to user only",
            null));
        table.put(HookEventType.CWD_CHANGED, new HookEventMetadata(
            "After the working directory changes",
            "Input to command is JSON with old_cwd and new_cwd.\nCLAUDE_ENV_FILE is set — write bash exports there to apply env to subsequent BashTool commands.\nHook output can include hookSpecificOutput.watchPaths (array of absolute paths) to register with the FileChanged watcher.\nExit code 0 - command completes successfully\nOther exit codes - show stderr to user only",
            null));
        table.put(HookEventType.FILE_CHANGED, new HookEventMetadata(
            "When a watched file changes",
            "Input to command is JSON with file_path and event (change, add, unlink).\nCLAUDE_ENV_FILE is set — write bash exports there to apply env to subsequent BashTool commands.\nThe matcher field specifies filenames to watch in the current directory (e.g. \".envrc|.env\").\nHook output can include hookSpecificOutput.watchPaths (array of absolute paths) to dynamically update the watch list.\nExit code 0 - command completes successfully\nOther exit codes - show stderr to user only",
            null));
        return Map.copyOf(table);
    }

    /**
     * 按事件→matcherKey→hooks 分组 · 对齐 CC {@code hooksConfigManager.ts:270-365}
     * {@code groupHooksByEventAndMatcher}（settings+session hooks 分组段 :304-320）。
     *
     * <p><b>matcherKey 语义 (CC :311-314)</b>: 有 matcherMetadata 的事件用 {@code hook.matcher || ''}
     * 作 key；无 matcherMetadata 的事件一律空串 key（如 Stop 无 matcher）。空检查 :308-309
     * （eventGroup 不存在 → 跳过）。
     *
     * <p><b>CC 分组键序</b>: 27 事件按 {@code HookEventType.HOOK_EVENTS_ORDER}（CC HOOK_EVENTS
     * 固定序, coreTypes.ts:25-53）初始化空 map —— 返回结构含全部 27 事件键（值为空 map 也保留，
     * CC :274-302 grouped 初始化）。
     *
     * <p><b>Java 入参适配</b>: CC 从 {@code appState} 读 getAllHooks(appState)（settings+session）,
     * Java 端由调用方传入已合并 hooks 列表（如 {@link HooksSettings#getAllHooks(String)}）。
     * 插件/注册 hooks 段（CC :323-362, getRegisteredHooks）归 WF-7 SDK 注册域（EV-WF1-CFG-016）。
     *
     * @param hooks    已合并的 hooks 列表（settings+session; 可为空）
     * @param toolNames 工具名列表（getHookEventMetadata 用）
     * @return 27 事件 → (matcherKey → hooks) 映射；事件键恒存在（空 map）
     */
    public Map<HookEventType, Map<String, List<IndividualHookConfig>>> groupHooksByEventAndMatcher(
            List<IndividualHookConfig> hooks, List<String> toolNames) {
        // CC :274-302: 27 事件 key → {} 初始化（CC HOOK_EVENTS 固定序）
        Map<HookEventType, Map<String, List<IndividualHookConfig>>> grouped = new LinkedHashMap<>();
        for (HookEventType evt : HookEventType.HOOK_EVENTS_ORDER) {
            grouped.put(evt, new LinkedHashMap<>());
        }
        Map<HookEventType, HookEventMetadata> metadata = getHookEventMetadata(toolNames);
        // CC :307-320: getAllHooks.forEach 分组
        if (hooks != null) {
            for (IndividualHookConfig hook : hooks) {
                Map<String, List<IndividualHookConfig>> eventGroup = grouped.get(hook.event());
                if (eventGroup == null) {
                    continue; // CC :309: eventGroup 空检查
                }
                HookEventMetadata evtMeta = metadata.get(hook.event());
                // CC :311-313: matcherMetadata !== undefined ? (hook.matcher || '') : ''
                String matcherKey = (evtMeta != null && evtMeta.matcherMetadata() != null)
                    ? (hook.matcher() == null ? "" : hook.matcher())
                    : "";
                eventGroup.computeIfAbsent(matcherKey, k -> new ArrayList<>()).add(hook);
            }
        }
        return grouped;
    }

    /**
     * 按事件→matcherKey→hooks 分组（session 合并生产入口）· 对齐 CC hooksConfigManager.ts:270-365
     * {@code groupHooksByEventAndMatcher(appState, toolNames)}。
     *
     * <p><b>生产接线 (OPD-WF1-CFG-01)</b>: CC 内部先 {@code getAllHooks(appState)}
     * （settings+session 合并, hooksSettings.ts:92-161, session 段 :144-158 无条件）再分组;
     * Java 端本入口经 {@link HooksSettings#getAllHooks(String)} 取已合并 hooks
     * （含 session, source=sessionHook），再委托 {@link #groupHooksByEventAndMatcher(List, List)}。
     * sessionHooksProvider 由 {@link HookRegistry} 生产注入（SessionHookStore 读取器,
     * {@code sessionId -> sessionHookStore.getSessionHooks(sessionId, null)}）。
     *
     * @param sessionId 会话 ID（null/blank → 仅 settings, 不合并 session）
     * @param toolNames 工具名列表（getHookEventMetadata 用）
     * @return 27 事件 → (matcherKey → hooks) 映射；事件键恒存在（空 map）
     */
    public Map<HookEventType, Map<String, List<IndividualHookConfig>>> groupHooksByEventAndMatcher(
            String sessionId, List<String> toolNames) {
        HooksSettings settings = this.hooksSettings;
        List<IndividualHookConfig> hooks = settings != null ? settings.getAllHooks(sessionId) : List.of();
        return groupHooksByEventAndMatcher(hooks, toolNames);
    }

    /**
     * 取某事件排序后的 matcher 键数组 · 对齐 CC {@code hooksConfigManager.ts:368-377}
     * {@code getSortedMatchersForEvent} —— 取 {@code hooksByEventAndMatcher[event]} 的键数组
     * → {@code sortMatchersByPriority}（CC 委托 hooksSettings.ts:230-271）。
     *
     * @param hooksByEventAndMatcher groupHooksByEventAndMatcher 的返回
     * @param event                 目标事件
     * @return 按优先级排序的 matcher 键数组（CC :375-376）
     */
    public List<String> getSortedMatchersForEvent(
            Map<HookEventType, Map<String, List<IndividualHookConfig>>> hooksByEventAndMatcher,
            HookEventType event) {
        Map<String, List<IndividualHookConfig>> eventGroup = hooksByEventAndMatcher.getOrDefault(event, Map.of());
        List<String> matchers = new ArrayList<>(eventGroup.keySet());
        return HooksSettings.sortMatchersByPriority(matchers, hooksByEventAndMatcher, event);
    }

    /**
     * 取指定 event+matcher 的 hooks · 对齐 CC {@code hooksConfigManager.ts:380-392}
     * {@code getHooksForMatcher} —— {@code matcherKey = matcher ?? ''}；返回
     * {@code hooksByEventAndMatcher[event]?.[matcherKey] ?? []}。
     *
     * @param hooksByEventAndMatcher groupHooksByEventAndMatcher 的返回
     * @param event                 目标事件
     * @param matcher               matcher 键（null → 空串 key, CC :388-389 无 matcher 事件存储键）
     * @return 该 event+matcher 的 hooks（可能为空, 永不 null）
     */
    public List<IndividualHookConfig> getHooksForMatcher(
            Map<HookEventType, Map<String, List<IndividualHookConfig>>> hooksByEventAndMatcher,
            HookEventType event, String matcher) {
        String matcherKey = matcher == null ? "" : matcher;
        return hooksByEventAndMatcher.getOrDefault(event, Map.of()).getOrDefault(matcherKey, List.of());
    }

    /**
     * 取某事件 matcher 元数据 · 对齐 CC {@code hooksConfigManager.ts:395-400}
     * {@code getMatcherMetadata} —— {@code getHookEventMetadata(toolNames)[event].matcherMetadata}。
     *
     * @param event     目标事件（27 事件之一）
     * @param toolNames 工具名列表（getHookEventMetadata 用）
     * @return matcher 元数据；无 matcherMetadata 的事件 → null
     */
    public MatcherMetadata getMatcherMetadata(HookEventType event, List<String> toolNames) {
        HookEventMetadata meta = getHookEventMetadata(toolNames).get(event);
        return meta != null ? meta.matcherMetadata() : null;
    }
}
