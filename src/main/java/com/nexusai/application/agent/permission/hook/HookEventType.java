package com.nexusai.application.agent.permission.hook;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hook 事件类型 · 对齐 claude.exe v2.1.325 全部 33 种事件（事件数组 {@code E2}
 * off≈309,279,7xx）+ [IMP-CF-03] 2 个 CC 事件枚举之外的 execCommandHook marker 事件
 * (StatusLine/FileSuggestion，不进 HOOK_EVENTS_ORDER)。
 *
 * <p>10 大类：
 * <ul>
 *   <li>工具相关（4）：PreToolUse, PostToolUse, PostToolUseFailure, PostToolBatch</li>
 *   <li>权限相关（2）：PermissionRequest, PermissionDenied</li>
 *   <li>会话相关（5）：SessionStart, SessionEnd, Stop, StopFailure, Setup</li>
 *   <li>用户交互（3）：UserPromptSubmit, UserPromptExpansion, Notification</li>
 *   <li>子代理（2）：SubagentStart, SubagentStop</li>
 *   <li>压缩/模型（4）：PreCompact, PostCompact, PreModelSwitch, PostModelSwitch</li>
 *   <li>团队/任务（3）：TeammateIdle, TaskCreated, TaskCompleted</li>
 *   <li>MCP（2）：Elicitation, ElicitationResult</li>
 *   <li>配置/环境（6）：ConfigChange, InstructionsLoaded, WorktreeCreate, WorktreeRemove, CwdChanged, FileChanged</li>
 *   <li>目录/展示（2）：DirectoryAdded, MessageDisplay</li>
 * </ul>
 *
 * <p><b>[Session H10] ccName() 映射</b>: CC 事件名是 PascalCase 字符串 (HOOK_EVENTS,
 * coreTypes.ts:25-52), Java 端枚举是 SNAKE_CASE. {@link #ccName()} 提供双向映射的
 * Java→CC 方向 (PRE_TOOL_USE → "PreToolUse"), 供 HookEventBus 白名单 /
 * AsyncHookRegistry 事件广播使用; 每个常量 JavaDoc 标注 CC 原名 + coreTypes.ts 行号
 * (Pattern #8).
 */
public enum HookEventType {

    // ==================== 工具相关 (4) ====================

    /** CC original: PreToolUse (exe 事件数组 E2 off≈309,279,7xx) */
    PRE_TOOL_USE,
    /** CC original: PostToolUse */
    POST_TOOL_USE,
    /** CC original: PostToolUseFailure */
    POST_TOOL_USE_FAILURE,
    /**
     * CC original: PostToolBatch · [Wave1-A] 一批工具调用全部 resolved 后只触发一次
     * （输入 {@code tool_calls[]}，exe off≈311,040,xxx {@code R2} schema）。
     *
     * <p>matcher 语义：CC {@code Eon} switch（off≈323,293,8xx）无此分支 → matchQuery undefined
     * ⇒ 全部 matcher 放行（matcherMetadata 缺失，不支持 matcher）。
     */
    POST_TOOL_BATCH,

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

    // ==================== 用户交互 (3) ====================

    /** CC original: UserPromptSubmit */
    USER_PROMPT_SUBMIT,
    /**
     * CC original: UserPromptExpansion · [Wave1-A] 斜杠命令 / MCP prompt 展开前触发
     * （输入 {@code expansion_type, command_name, command_args, command_source?, prompt}，
     * exe off≈311,040,xxx {@code x2} schema）。
     *
     * <p>matchQuery = {@code command_name}（exe {@code Eon} off≈323,293,8xx
     * {@code case"UserPromptExpansion":return e.command_name}）。
     */
    USER_PROMPT_EXPANSION,
    /** CC original: Notification */
    NOTIFICATION,

    // ==================== 子代理 (2) ====================

    /** CC original: SubagentStart (coreTypes.ts:35) */
    SUBAGENT_START,
    /** CC original: SubagentStop (coreTypes.ts:36) */
    SUBAGENT_STOP,

    // ==================== 压缩/模型 (4) ====================

    /** CC original: PreCompact */
    PRE_COMPACT,
    /** CC original: PostCompact */
    POST_COMPACT,
    /**
     * CC original: PreModelSwitch · [Wave1-A] 模型切换前（输入 {@code from_model, to_model,
     * requested_model, source, context_tokens, prompt_cache_warm, cache_ttl,
     * estimated_cache_write_usd, pricing}，exe {@code H2}/{@code lC} off≈311,04x,xxx）。
     * 输出可给 {@code permissionDecision}(allow/deny/ask) 拦/放行切换。
     *
     * <p>matchQuery = {@code to_model}（exe {@code Eon} off≈323,293,8xx；CC 另有 model-id
     * 形状门控 {@code L_n}，本仓未复刻 ⇒ 见 HookMatcherEngine 注释）。
     */
    PRE_MODEL_SWITCH,
    /**
     * CC original: PostModelSwitch · [Wave1-A] 模型切换后（同 PreModelSwitch 字段；
     * 输出 {@code additionalContext} 进新模型下一次请求）。
     */
    POST_MODEL_SWITCH,

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
    /** CC original: CwdChanged */
    CWD_CHANGED,
    /** CC original: FileChanged */
    FILE_CHANGED,
    /**
     * CC original: DirectoryAdded · [Wave1-A] {@code /add-dir} 或 SDK {@code register_repo_root}
     * 注册新工作目录后触发（输入 {@code directory, source(slash_command|register_repo_root)}，
     * exe off≈311,04x,xxx {@code iX} schema）。
     *
     * <p>matchQuery = {@code source}（exe {@code Eon} off≈323,293,8xx
     * {@code case"DirectoryAdded":return e.source}）—— <b>不是 directory</b>。
     */
    DIRECTORY_ADDED,
    /**
     * CC original: MessageDisplay · [Wave1-A] 助手消息每批新行显示时触发
     * （输入 {@code turn_id, message_id, index, final, delta}，输出 {@code displayContent}
     * 只改屏幕、不改存储与模型上下文，exe off≈311,04x,xxx {@code lX} schema）。
     *
     * <p>matcher 语义：CC {@code Eon} 无此分支 → matchQuery undefined ⇒ 全部 matcher 放行。
     */
    MESSAGE_DISPLAY,

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
     * CC 事件枚举 33 项固定顺序（claude.exe v2.1.325 事件数组 {@code E2} off≈309,279,7xx）·
     * 供无 event 的全量查询遍历 (D-05, sessionHooks.ts:322-327 / :381-389
     * {@code for (const evt of HOOK_EVENTS)}).
     *
     * <p><b>WHY 不用 {@code values()}</b>: Java 枚举声明序 ≠ CC 序 — 自验差异:
     * NOTIFICATION/USER_PROMPT_SUBMIT 在 CC #4/#5 而 Java #12/#11; PERMISSION_REQUEST/
     * PERMISSION_DENIED 在 CC #14/#15 而 Java #4/#5; SETUP CC #16 而 Java #9;
     * INSTRUCTIONS_LOADED CC #25 而 Java #23. 遍历序是可观察契约 (getAllHooks 展示序),
     * 必须硬编码 CC 原文序; 集合一致性由 SessionHookStoreTest 的
     * {@code HOOK_EVENTS_ORDER 与 ccEventNames() 同源} 用例兜底.
     */
    public static final List<HookEventType> HOOK_EVENTS_ORDER = List.of(
        PRE_TOOL_USE, POST_TOOL_USE, POST_TOOL_USE_FAILURE, POST_TOOL_BATCH,
        NOTIFICATION, USER_PROMPT_SUBMIT, USER_PROMPT_EXPANSION,
        SESSION_START, SESSION_END, STOP, STOP_FAILURE,
        SUBAGENT_START, SUBAGENT_STOP,
        PRE_COMPACT, POST_COMPACT, PRE_MODEL_SWITCH, POST_MODEL_SWITCH,
        PERMISSION_REQUEST, PERMISSION_DENIED, SETUP,
        TEAMMATE_IDLE, TASK_CREATED, TASK_COMPLETED,
        ELICITATION, ELICITATION_RESULT,
        CONFIG_CHANGE, WORKTREE_CREATE, WORKTREE_REMOVE,
        INSTRUCTIONS_LOADED, CWD_CHANGED, FILE_CHANGED, DIRECTORY_ADDED, MESSAGE_DISPLAY
    );

    /**
     * 全部 33 个 CC 事件名 · 供 HookEventBus 白名单.
     *
     * <p><b>[IMP-CF-03]</b>: 推导自 {@link #HOOK_EVENTS_ORDER} 而非 {@link #values()}
     * —— 枚举新增 {@link #STATUS_LINE}/{@link #FILE_SUGGESTION} 两个 CC 事件枚举
     * 之外的 marker 事件后，values() 为 35 项；HookEventBus 白名单必须仍为 CC 的
     * 33 项（SessionHookStoreTest "HOOK_EVENTS_ORDER 与 ccEventNames() 同源" 契约）。
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
