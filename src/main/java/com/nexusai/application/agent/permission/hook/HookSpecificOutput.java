package com.nexusai.application.agent.permission.hook;

import java.util.List;
import java.util.Map;

/**
 * [Session H4] Hook 特定输出 sealed interface + 15 子类型 record · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/entrypoints/sdk/coreSchemas.ts:806-970} hookSpecificOutput
 * (grep 自验 2026-07-30) + {@code Open-ClaudeCode/src/types/hooks.ts:70-163}.
 *
 * <p>CC 真源 15 子类型 (coreSchemas.ts:806-970, 每个子类型 z.object 含 {@code hookEventName}
 * discriminator + 类型特定字段):
 * <ol>
 *   <li>PreToolUse (806-813): permissionDecision?, permissionDecisionReason?, updatedInput?, additionalContext?</li>
 *   <li>UserPromptSubmit (815-819): additionalContext?</li>
 *   <li>SessionStart (821-826): additionalContext?, initialUserMessage?, watchPaths?</li>
 *   <li>Setup (828-832): additionalContext?</li>
 *   <li>SubagentStart (834-838): additionalContext?</li>
 *   <li>PostToolUse (840-844): additionalContext?, updatedMCPToolOutput?</li>
 *   <li>PostToolUseFailure (846-850): additionalContext?</li>
 *   <li>PermissionDenied (852-856): retry?</li>
 *   <li>Notification (858-862): additionalContext?</li>
 *   <li>PermissionRequest (864-878): decision union (allow: updatedInput?/updatedPermissions? | deny: message?/interrupt?)</li>
 *   <li>Elicitation (940-950): action?, content?</li>
 *   <li>ElicitationResult (952-962): action?, content?</li>
 *   <li>CwdChanged (880-884): watchPaths?</li>
 *   <li>FileChanged (886-890): watchPaths?</li>
 *   <li>WorktreeCreate (972-977): worktreePath (必传)</li>
 * </ol>
 *
 * <p>WHY (规则三 + Pattern #4): CC 是 discriminated union 15 变体, Java 用 sealed interface
 * + 15 record 表达. 之前 Java 把 hookSpecificOutput 字段散落到 GenericHook.HookResult /
 * AggregatedHookResult 顶层 (hookPermissionResult/hookUpdatedInput/hookSource 3 非 CC 顶层字段),
 * H4 收敛到 sealed 类型, HookResult 顶层只留 CC 14 字段.
 *
 * <p>每个 record 字段 JavaDoc 标注 CC 原名 + 行号 (未来审计无需重跑).
 *
 * @since Session H4
 */
public sealed interface HookSpecificOutput
    permits HookSpecificOutput.PreToolUse, HookSpecificOutput.UserPromptSubmit,
                HookSpecificOutput.SessionStart, HookSpecificOutput.Setup,
                HookSpecificOutput.SubagentStart, HookSpecificOutput.PostToolUse,
                HookSpecificOutput.PostToolUseFailure, HookSpecificOutput.PermissionDenied,
                HookSpecificOutput.Notification, HookSpecificOutput.PermissionRequest,
                HookSpecificOutput.Elicitation, HookSpecificOutput.ElicitationResult,
                HookSpecificOutput.CwdChanged, HookSpecificOutput.FileChanged,
                HookSpecificOutput.WorktreeCreate {

    /** CC original: {@code hookEventName} (coreSchemas.ts:807+). discriminator 常量. */
    String hookEventName();

    /**
     * CC PreToolUse hookSpecificOutput (coreSchemas.ts:806-813).
     *
     * @param hookEventName              CC original: {@code hookEventName} = 'PreToolUse' (coreSchemas.ts:807)
     * @param permissionDecision         CC original: {@code permissionDecision} (coreSchemas.ts:808);
 *                                   'allow'|'deny'|'ask', optional (CC PermissionRule.ts:25-27, 无第四态)
     * @param permissionDecisionReason   CC original: {@code permissionDecisionReason} (coreSchemas.ts:809);
     *                                   权限决策原因文本, optional
     * @param updatedInput               CC original: {@code updatedInput} (coreSchemas.ts:810);
     *                                   修改后的工具输入, optional
     * @param additionalContext           CC original: {@code additionalContext} (coreSchemas.ts:811);
     *                                   注入 LLM 的附加上下文, optional
     */
    record PreToolUse(
        String permissionDecision,
        String permissionDecisionReason,
        Map<String, Object> updatedInput,
        String additionalContext
    ) implements HookSpecificOutput {
        public PreToolUse {
            if (updatedInput != null) {
                updatedInput = Map.copyOf(updatedInput);
            }
        }
        @Override public String hookEventName() { return "PreToolUse"; }
    }

    /**
     * CC UserPromptSubmit hookSpecificOutput (coreSchemas.ts:815-819).
     *
     * @param additionalContext CC original: {@code additionalContext} (coreSchemas.ts:817); 注入上下文, optional
     */
    record UserPromptSubmit(
        String additionalContext
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "UserPromptSubmit"; }
    }

    /**
     * CC SessionStart hookSpecificOutput (coreSchemas.ts:821-826).
     *
     * @param additionalContext    CC original: {@code additionalContext} (coreSchemas.ts:823); optional
     * @param initialUserMessage   CC original: {@code initialUserMessage} (coreSchemas.ts:824);
     *                             重注入原 user message, optional
     * @param watchPaths           CC original: {@code watchPaths} (coreSchemas.ts:825);
     *                             文件监听路径列表, optional
     */
    record SessionStart(
        String additionalContext,
        String initialUserMessage,
        List<String> watchPaths
    ) implements HookSpecificOutput {
        public SessionStart {
            if (watchPaths != null) {
                watchPaths = List.copyOf(watchPaths);
            }
        }
        @Override public String hookEventName() { return "SessionStart"; }
    }

    /**
     * CC Setup hookSpecificOutput (coreSchemas.ts:828-832).
     *
     * @param additionalContext CC original: {@code additionalContext} (coreSchemas.ts:830); optional
     */
    record Setup(
        String additionalContext
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "Setup"; }
    }

    /**
     * CC SubagentStart hookSpecificOutput (coreSchemas.ts:834-838).
     *
     * @param additionalContext CC original: {@code additionalContext} (coreSchemas.ts:836); optional
     */
    record SubagentStart(
        String additionalContext
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "SubagentStart"; }
    }

    /**
     * CC PostToolUse hookSpecificOutput (coreSchemas.ts:840-844).
     *
     * @param additionalContext    CC original: {@code additionalContext} (coreSchemas.ts:842); optional
     * @param updatedMCPToolOutput CC original: {@code updatedMCPToolOutput} (coreSchemas.ts:843);
     *                             MCP 工具输出替换, optional
     */
    record PostToolUse(
        String additionalContext,
        Object updatedMCPToolOutput
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "PostToolUse"; }
    }

    /**
     * CC PostToolUseFailure hookSpecificOutput (coreSchemas.ts:846-850).
     *
     * @param additionalContext CC original: {@code additionalContext} (coreSchemas.ts:848); optional
     */
    record PostToolUseFailure(
        String additionalContext
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "PostToolUseFailure"; }
    }

    /**
     * CC PermissionDenied hookSpecificOutput (coreSchemas.ts:852-856).
     *
     * @param retry CC original: {@code retry} (coreSchemas.ts:854); 允许重试, optional
     */
    record PermissionDenied(
        Boolean retry
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "PermissionDenied"; }
    }

    /**
     * CC Notification hookSpecificOutput (coreSchemas.ts:858-862).
     *
     * @param additionalContext CC original: {@code additionalContext} (coreSchemas.ts:860); optional
     */
    record Notification(
        String additionalContext
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "Notification"; }
    }

    /**
     * CC PermissionRequest hookSpecificOutput (coreSchemas.ts:864-878).
     *
     * <p>decision 是 union 2 变体 (对齐 CC coreSchemas.ts:866-877):
     * <ul>
     *   <li>allow: { updatedInput?, updatedPermissions? }</li>
     *   <li>deny: { message?, interrupt? }</li>
     * </ul>
     * Java 端复用 {@link PermissionRequestResult} sealed 类型承载 decision (类型等价).
     *
     * @param decision CC original: {@code decision} (coreSchemas.ts:865);
     *                 allow/deny union, 类型为 {@link PermissionRequestResult}
     */
    record PermissionRequest(
        PermissionRequestResult decision
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "PermissionRequest"; }
    }

    /**
     * CC Elicitation hookSpecificOutput (coreSchemas.ts:940-950).
     *
     * @param action  CC original: {@code action} (coreSchemas.ts:943);
     *                'accept'|'decline'|'cancel', optional
     * @param content CC original: {@code content} (coreSchemas.ts:944);
     *                elicitation 响应内容, optional
     */
    record Elicitation(
        String action,
        Map<String, Object> content
    ) implements HookSpecificOutput {
        public Elicitation {
            if (content != null) {
                content = Map.copyOf(content);
            }
        }
        @Override public String hookEventName() { return "Elicitation"; }
    }

    /**
     * CC ElicitationResult hookSpecificOutput (coreSchemas.ts:952-962).
     *
     * @param action  CC original: {@code action} (coreSchemas.ts:955);
     *                'accept'|'decline'|'cancel', optional
     * @param content CC original: {@code content} (coreSchemas.ts:956);
     *                elicitation result 响应内容, optional
     */
    record ElicitationResult(
        String action,
        Map<String, Object> content
    ) implements HookSpecificOutput {
        public ElicitationResult {
            if (content != null) {
                content = Map.copyOf(content);
            }
        }
        @Override public String hookEventName() { return "ElicitationResult"; }
    }

    /**
     * CC CwdChanged hookSpecificOutput (coreSchemas.ts:880-884).
     *
     * @param watchPaths CC original: {@code watchPaths} (coreSchemas.ts:882); 文件监听路径, optional
     */
    record CwdChanged(
        List<String> watchPaths
    ) implements HookSpecificOutput {
        public CwdChanged {
            if (watchPaths != null) {
                watchPaths = List.copyOf(watchPaths);
            }
        }
        @Override public String hookEventName() { return "CwdChanged"; }
    }

    /**
     * CC FileChanged hookSpecificOutput (coreSchemas.ts:886-890).
     *
     * @param watchPaths CC original: {@code watchPaths} (coreSchemas.ts:888); 文件监听路径, optional
     */
    record FileChanged(
        List<String> watchPaths
    ) implements HookSpecificOutput {
        public FileChanged {
            if (watchPaths != null) {
                watchPaths = List.copyOf(watchPaths);
            }
        }
        @Override public String hookEventName() { return "FileChanged"; }
    }

    /**
     * CC WorktreeCreate hookSpecificOutput (coreSchemas.ts:972-977).
     *
     * @param worktreePath CC original: {@code worktreePath} (coreSchemas.ts:974);
     *                     创建的 worktree 绝对路径, <b>必传</b> (无 optional)
     */
    record WorktreeCreate(
        String worktreePath
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "WorktreeCreate"; }
    }
}