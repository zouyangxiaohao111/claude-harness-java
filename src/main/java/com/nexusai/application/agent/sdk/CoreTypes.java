package com.nexusai.application.agent.sdk;

import java.util.List;

/**
 * SDK core types 常量 · 对齐 CC entrypoints/sdk/coreTypes.ts.
 *
 * <p>L1 语义: SDK 公共类型入口. 该文件 99% 是 TS type re-export (Zod 生成的类型),
 *            Java 不做 re-export — 运行时仅暴露 2 个 const 数组:
 *            HOOK_EVENTS (27 个 hook 事件名) + EXIT_REASONS (6 个退出原因).
 *            类型字段在 Java 端用对应 record 表示 (sandboxTypes / sdkUtilityTypes).
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: HOOK_EVENTS 27 字段 (PreToolUse/PostToolUse/.../FileChanged);EXIT_REASONS 6 字段 (clear/resume/logout/prompt_input_exit/other/bypass_permissions_disabled);
 *       均为不可变 List (Java List.of).</li>
 *   <li><b>A2 Golden Trace</b>: 顺序保持 CC TS const 顺序 (与 hook 调度 + exit classifier 一致).</li>
 *   <li><b>A3</b>: 纯常量;无状态.</li>
 *   <li><b>A4</b>: 空检查 + contains 检查安全 (不可变 List).</li>
 *   <li><b>A5</b>: 真实场景 — hook 注册遍历 HOOK_EVENTS;exit code classifier switch EXIT_REASONS.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `as const` → Java `List.of(...)` (unmodifiable);
 *                    TS type re-export → 不适用 (Java 端用对应 record).
 */
public final class CoreTypes {

    private CoreTypes() {}

    /** CC HOOK_EVENTS — 27 个 SDK hook 事件名 (CC 顺序). */
    public static final List<String> HOOK_EVENTS = List.of(
        "PreToolUse",
        "PostToolUse",
        "PostToolUseFailure",
        "Notification",
        "UserPromptSubmit",
        "SessionStart",
        "SessionEnd",
        "Stop",
        "StopFailure",
        "SubagentStart",
        "SubagentStop",
        "PreCompact",
        "PostCompact",
        "PermissionRequest",
        "PermissionDenied",
        "Setup",
        "TeammateIdle",
        "TaskCreated",
        "TaskCompleted",
        "Elicitation",
        "ElicitationResult",
        "ConfigChange",
        "WorktreeCreate",
        "WorktreeRemove",
        "InstructionsLoaded",
        "CwdChanged",
        "FileChanged"
    );

    /** CC EXIT_REASONS — 6 个 exit reason. */
    public static final List<String> EXIT_REASONS = List.of(
        "clear",
        "resume",
        "logout",
        "prompt_input_exit",
        "other",
        "bypass_permissions_disabled"
    );
}
