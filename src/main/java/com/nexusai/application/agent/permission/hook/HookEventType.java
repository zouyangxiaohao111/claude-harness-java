package com.nexusai.application.agent.permission.hook;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hook 事件类型 · 对齐 CC coreTypes.ts:25-53 全部 27 种事件 +
 * [IMP-CF-03] 2 个 CC coreTypes 之外的 execCommandHook marker 事件
 * (StatusLine/FileSuggestion，不进 HOOK_EVENTS_ORDER)。
 *
 * <p>9 大类：
 * <ul>
 *   <li>工具相关（3）：PreToolUse, PostToolUse, PostToolUseFailure</li>
 *   <li>权限相关（2）：PermissionRequest, PermissionDenied</li>
 *   <li>会话相关（5）：SessionStart, SessionEnd, Stop, StopFailure, Setup</li>
 *   <li>用户交互（2）：UserPromptSubmit, Notification</li>
 *   <li>子代理（2）：SubagentStart, SubagentStop</li>
 *   <li>压缩相关（2）：PreCompact, PostCompact</li>
 *   <li>团队/任务（3）：TeammateIdle, TaskCreated, TaskCompleted</li>
 *   <li>MCP（2）：Elicitation, ElicitationResult</li>
 *   <li>配置/环境（6）：ConfigChange, InstructionsLoaded, WorktreeCreate, WorktreeRemove, CwdChanged, FileChanged</li>
 * </ul>
 *
 * <p><b>[Session H10] ccName() 映射</b>: CC 事件名是 PascalCase 字符串 (HOOK_EVENTS,
 * coreTypes.ts:25-52), Java 端枚举是 SNAKE_CASE. {@link #ccName()} 提供双向映射的
 * Java→CC 方向 (PRE_TOOL_USE → "PreToolUse"), 供 HookEventBus 白名单 /
 * AsyncHookRegistry 事件广播使用; 每个常量 JavaDoc 标注 CC 原名 + coreTypes.ts 行号
 * (Pattern #8).
 */
public enum HookEventType {

    // ==================== 工具相关 (3) ====================

    /** CC original: PreToolUse (coreTypes.ts:26) */
    PRE_TOOL_USE,
    /** CC original: PostToolUse (coreTypes.ts:27) */
    POST_TOOL_USE,
    /** CC original: PostToolUseFailure (coreTypes.ts:28) */
    POST_TOOL_USE_FAILURE,

    // ==================== 权限相关 (2) ====================

    /** CC original: PermissionRequest (coreTypes.ts:39) */
    PERMISSION_REQUEST,
    /** CC original: PermissionDenied (coreTypes.ts:40) */
    PERMISSION_DENIED,

    // ==================== 会话相关 (5) ====================

    /** CC original: SessionStart (coreTypes.ts:31) */
    SESSION_START,
    /** CC original: SessionEnd (coreTypes.ts:32) */
    SESSION_END,
    /** CC original: Stop (coreTypes.ts:33) */
    STOP,
    /** CC original: StopFailure (coreTypes.ts:34) */
    STOP_FAILURE,
    /** CC original: Setup (coreTypes.ts:41) */
    SETUP,

    // ==================== 用户交互 (2) ====================

    /** CC original: UserPromptSubmit (coreTypes.ts:30) */
    USER_PROMPT_SUBMIT,
    /** CC original: Notification (coreTypes.ts:29) */
    NOTIFICATION,

    // ==================== 子代理 (2) ====================

    /** CC original: SubagentStart (coreTypes.ts:35) */
    SUBAGENT_START,
    /** CC original: SubagentStop (coreTypes.ts:36) */
    SUBAGENT_STOP,

    // ==================== 压缩相关 (2) ====================

    /** CC original: PreCompact (coreTypes.ts:37) */
    PRE_COMPACT,
    /** CC original: PostCompact (coreTypes.ts:38) */
    POST_COMPACT,

    // ==================== 团队/任务 (3) ====================

    /** CC original: TeammateIdle (coreTypes.ts:42) */
    TEAMMATE_IDLE,
    /** CC original: TaskCreated (coreTypes.ts:43) */
    TASK_CREATED,
    /** CC original: TaskCompleted (coreTypes.ts:44) */
    TASK_COMPLETED,

    // ==================== MCP (2) ====================

    /** CC original: Elicitation (coreTypes.ts:45) */
    ELICITATION,
    /** CC original: ElicitationResult (coreTypes.ts:46) */
    ELICITATION_RESULT,

    // ==================== 配置/环境 (6) ====================

    /** CC original: ConfigChange (coreTypes.ts:47) */
    CONFIG_CHANGE,
    /** CC original: InstructionsLoaded (coreTypes.ts:50) */
    INSTRUCTIONS_LOADED,
    /** CC original: WorktreeCreate (coreTypes.ts:48) */
    WORKTREE_CREATE,
    /** CC original: WorktreeRemove (coreTypes.ts:49) */
    WORKTREE_REMOVE,
    /** CC original: CwdChanged (coreTypes.ts:51) */
    CWD_CHANGED,
    /** CC original: FileChanged (coreTypes.ts:52) */
    FILE_CHANGED,

    // ==================== 状态行 / 文件建议 marker 事件 (2) ====================

    /**
     * [IMP-CF-03] StatusLine marker 事件 · CC execCommandHook 的 hookEvent 参数允许
     * {@code 'StatusLine'} 字面量 (utils/hooks.ts:749, {@code HookEvent | 'StatusLine' |
     * 'FileSuggestion'})，由 {@code executeStatusLineCommand} 传入 (utils/hooks.ts:4626)。
     *
     * <p><b>非 CC coreTypes HOOK_EVENTS 事件</b>：StatusLine/FileSuggestion 不在
     * coreTypes.ts:25-53 的 27 项事件内，是 execCommandHook 的专用 marker 字面量。
     * 因此本枚举值<b>不进入 {@link #HOOK_EVENTS_ORDER}</b>（保持 27 项 CC 序不变），
     * 仅作 CommandHookExecutor 执行 statusLine/fileSuggestion command 时的 hookEvent
     * 载体（{@link #ccName()} 返回 "StatusLine" 供 hook 响应事件名对齐 CC）。
     */
    STATUS_LINE,
    /** CC original: 'FileSuggestion' (utils/hooks.ts:749) · 同 {@link #STATUS_LINE} marker 语义. */
    FILE_SUGGESTION;

    /**
     * CC HOOK_EVENTS 27 项固定顺序 (coreTypes.ts:25-53) · 供无 event 的全量查询遍历
     * (D-05, sessionHooks.ts:322-327 / :381-389 {@code for (const evt of HOOK_EVENTS)}).
     *
     * <p><b>WHY 不用 {@code values()}</b>: Java 枚举声明序 ≠ CC 序 — 自验差异:
     * NOTIFICATION/USER_PROMPT_SUBMIT 在 CC #4/#5 而 Java #12/#11; PERMISSION_REQUEST/
     * PERMISSION_DENIED 在 CC #14/#15 而 Java #4/#5; SETUP CC #16 而 Java #9;
     * INSTRUCTIONS_LOADED CC #25 而 Java #23. 遍历序是可观察契约 (getAllHooks 展示序),
     * 必须硬编码 CC 原文序; 集合一致性由 SessionHookStoreTest 的
     * {@code HOOK_EVENTS_ORDER 与 ccEventNames() 同源} 用例兜底.
     */
    public static final List<HookEventType> HOOK_EVENTS_ORDER = List.of(
        PRE_TOOL_USE, POST_TOOL_USE, POST_TOOL_USE_FAILURE,
        NOTIFICATION, USER_PROMPT_SUBMIT,
        SESSION_START, SESSION_END, STOP, STOP_FAILURE,
        SUBAGENT_START, SUBAGENT_STOP,
        PRE_COMPACT, POST_COMPACT,
        PERMISSION_REQUEST, PERMISSION_DENIED, SETUP,
        TEAMMATE_IDLE, TASK_CREATED, TASK_COMPLETED,
        ELICITATION, ELICITATION_RESULT,
        CONFIG_CHANGE, WORKTREE_CREATE, WORKTREE_REMOVE,
        INSTRUCTIONS_LOADED, CWD_CHANGED, FILE_CHANGED
    );

    /**
     * 全部 27 个 CC 事件名 (coreTypes.ts:25-52 HOOK_EVENTS) · 供 HookEventBus 白名单.
     *
     * <p><b>[IMP-CF-03]</b>: 推导自 {@link #HOOK_EVENTS_ORDER} 而非 {@link #values()}
     * —— 枚举新增 {@link #STATUS_LINE}/{@link #FILE_SUGGESTION} 两个 CC coreTypes
     * 之外的 marker 事件后，values() 为 29 项；HookEventBus 白名单必须仍为 CC 的
     * 27 项（SessionHookStoreTest "HOOK_EVENTS_ORDER 与 ccEventNames() 同源" 契约）。
     */
    private static final Set<String> CC_EVENT_NAMES = HOOK_EVENTS_ORDER.stream()
        .map(HookEventType::ccName)
        .collect(Collectors.toUnmodifiableSet());

    /**
     * 枚举 → CC 事件名 (PRE_TOOL_USE → "PreToolUse") · 对齐 CC coreTypes.ts:25-52
     * HOOK_EVENTS 27 项 PascalCase 常量.
     *
     * <p>WHY (Session H10): CC 的 hookEvent 是 PascalCase 字符串 (hookEvents.ts:22-49
     * HookStartedEvent.hookEvent 等), 白名单 (hookEvents.ts:18 ALWAYS_EMITTED) 与
     * SessionStart 判定 (AsyncHookRegistry.ts:220 {@code hook.hookEvent === 'SessionStart'})
     * 都按字符串比较 — Java 端必须能拿到等价字符串才能接入事件总线.
     *
     * <p>算法与 {@link CommandHookExecutor#toCcEventName(HookEventType)} 等价
     * (SNAKE_CASE 分词首字母大写), 27 项逐一比对 coreTypes.ts:25-52 均一致.
     *
     * @return CC 事件名, 如 {@code "PreToolUse"}
     */
    public String ccName() {
        StringBuilder sb = new StringBuilder();
        for (String part : name().split("_")) {
            sb.append(Character.toUpperCase(part.charAt(0)))
                .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /**
     * 全部 27 个 CC 事件名集合 · 对齐 CC {@code HOOK_EVENTS} (coreTypes.ts:25-52).
     *
     * <p>WHY (Session H10): HookEventBus.shouldEmit 白名单判定 (hookEvents.ts:83-91
     * {@code HOOK_EVENTS.includes(hookEvent)}) 需要 Java 端等价集合 — 不硬编码字符串
     * 数组, 由枚举推导保证 27 项与 CC 同步.
     *
     * @return 不可变集合, 含全部 27 个 CC 事件名
     */
    public static Set<String> ccEventNames() {
        return CC_EVENT_NAMES;
    }

    /**
     * CC 事件名 → 枚举 · {@link #ccName()} 的逆映射 ("PreToolUse" → PRE_TOOL_USE).
     *
     * <p>WHY (Session H12): 前端 / 数据库传入的 hooks 配置按 CC 事件名 (PascalCase,
     * registerSkillHooks.ts:29 {@code HOOK_EVENTS}) 组织, Java 端需把 CC 名映射回枚举
     * 才能注册 session hook. 未知事件名 (CC 无此项) → null, 调用方跳过.
     *
     * @param ccName CC 事件名, 如 {@code "PreToolUse"}
     * @return 对应枚举, 未知 → null
     */
    public static HookEventType fromCcName(String ccName) {
        if (ccName == null || ccName.isBlank()) {
            return null;
        }
        for (HookEventType t : values()) {
            if (t.ccName().equals(ccName)) {
                return t;
            }
        }
        return null;
    }

}
