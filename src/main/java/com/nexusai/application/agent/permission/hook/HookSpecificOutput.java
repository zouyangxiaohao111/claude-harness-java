package com.nexusai.application.agent.permission.hook;

import java.util.List;
import java.util.Map;

/**
 * [Session H4] Hook 特定输出 sealed interface + 22 子类型 record · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/entrypoints/sdk/coreSchemas.ts:806-970} hookSpecificOutput
 * (grep 自验 2026-07-30) + {@code Open-ClaudeCode/src/types/hooks.ts:70-163}
 * + <b>claude.exe v2.1.325 新增 7 变体 / 6 字段</b>（exe off≈311,053,0xx–311,060,xxx）。
 *
 * <p><b>CC v2.1.325 {@code NX.hookSpecificOutput} union 22 成员</b>（exe 字面量顺序）:
 * <pre>
 *   PreToolUse UserPromptSubmit UserPromptExpansion SessionStart Setup
 *   PreModelSwitch PostModelSwitch SubagentStart PostToolUse PostToolUseFailure
 *   PostToolBatch Stop SubagentStop PermissionDenied Notification PermissionRequest
 *   Elicitation ElicitationResult CwdChanged FileChanged WorktreeCreate MessageDisplay
 * </pre>
 * Wave1-B 新增 7 个：{@link Stop}/{@link SubagentStop}(非错误反馈 additionalContext)、
 * {@link PostToolBatch}、{@link UserPromptExpansion}、{@link PreModelSwitch}、
 * {@link PostModelSwitch}、{@link MessageDisplay}；4 个既有变体补字段：
 * PreToolUse.permissionDisplay、UserPromptSubmit.sessionTitle/suppressOriginalPrompt、
 * SessionStart.sessionTitle/reloadSkills、PostToolUse.classifierContext/updatedToolOutput。
 *
 * <p>WHY (规则三 + Pattern #4): CC 是 discriminated union 15 变体, Java 用 sealed interface
 * + 15 record 表达. 之前 Java 把 hookSpecificOutput 字段散落到 GenericHook.HookResult /
 * AggregatedHookResult 顶层 (hookPermissionResult/hookUpdatedInput/hookSource 3 非 CC 顶层字段),
 * H4 收敛到 sealed 类型, HookResult 顶层只留 CC 14 字段.
 *
 * <p>每个 record 字段 JavaDoc 标注 CC 原名 + 行号/偏移 (未来审计无需重跑).
 *
 * @since Session H4 (Wave1-B 扩到 22 变体)
 */
public sealed interface HookSpecificOutput
    permits HookSpecificOutput.PreToolUse, HookSpecificOutput.UserPromptSubmit,
                HookSpecificOutput.UserPromptExpansion, HookSpecificOutput.SessionStart,
                HookSpecificOutput.Setup, HookSpecificOutput.PreModelSwitch,
                HookSpecificOutput.PostModelSwitch, HookSpecificOutput.SubagentStart,
                HookSpecificOutput.PostToolUse, HookSpecificOutput.PostToolUseFailure,
                HookSpecificOutput.PostToolBatch, HookSpecificOutput.Stop,
                HookSpecificOutput.SubagentStop, HookSpecificOutput.PermissionDenied,
                HookSpecificOutput.Notification, HookSpecificOutput.PermissionRequest,
                HookSpecificOutput.Elicitation, HookSpecificOutput.ElicitationResult,
                HookSpecificOutput.CwdChanged, HookSpecificOutput.FileChanged,
                HookSpecificOutput.WorktreeCreate, HookSpecificOutput.MessageDisplay {

    /** CC original: {@code hookEventName} (coreSchemas.ts:807+). discriminator 常量. */
    String hookEventName();

    /**
     * PreToolUse 的 {@code permissionDisplay} · v2.1.325 内部字段
     * （exe off≈311,053,0xx {@code sZs}: {@code {title, description, displayName}}）。
     *
     * <p><b>用途</b>: Desktop 宿主给"询问权限"卡片自定义文案。仅当
     * {@code permissionDecision='ask'} 时读取，且仅 SDK 宿主在 stdio initialize 声明
     * {@code host_worded_asks} 时生效（CC 描述原文）。
     *
     * <p><b>CC 值域（原文）</b>: title ≤ 200、description ≤ 2000、displayName ≤ 64
     * UTF-16 code unit；description 内不得含除换行外的控制/不可见字符。
     * 本仓无 Desktop 卡片消费方（不裁值），保留原始结构供透传。
     *
     * @param title       CC original: {@code title}
     * @param description CC original: {@code description}
     * @param displayName CC original: {@code displayName}
     */
    record PermissionDisplay(String title, String description, String displayName) {
    }

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
        String additionalContext,
        PermissionDisplay permissionDisplay
    ) implements HookSpecificOutput {
        public PreToolUse {
            if (updatedInput != null) {
                updatedInput = Map.copyOf(updatedInput);
            }
        }

        /** [Wave1-B] 4 参转发构造器 · {@code permissionDisplay} 缺失（旧调用点零改动）. */
        public PreToolUse(String permissionDecision, String permissionDecisionReason,
                          Map<String, Object> updatedInput, String additionalContext) {
            this(permissionDecision, permissionDecisionReason, updatedInput, additionalContext, null);
        }

        @Override public String hookEventName() { return "PreToolUse"; }
    }

    /**
     * CC UserPromptSubmit hookSpecificOutput (coreSchemas.ts:815-819 + v2.1.325 两字段).
     *
     * @param additionalContext      CC original: {@code additionalContext} (coreSchemas.ts:817); 注入上下文, optional
     * @param sessionTitle           CC original: {@code sessionTitle} (exe off≈311,054,230);
     *                               hook 给会话起名, optional
     * @param suppressOriginalPrompt CC original: {@code suppressOriginalPrompt} (exe off≈311,054,230);
     *                               {@code decision:'block'} 时不在拦截消息里回显原 prompt（隐私）, optional
     */
    record UserPromptSubmit(
        String additionalContext,
        String sessionTitle,
        Boolean suppressOriginalPrompt
    ) implements HookSpecificOutput {

        /** [Wave1-B] 1 参转发构造器 · 新两字段缺失（旧调用点零改动）. */
        public UserPromptSubmit(String additionalContext) {
            this(additionalContext, null, null);
        }

        @Override public String hookEventName() { return "UserPromptSubmit"; }
    }

    /**
     * CC UserPromptExpansion hookSpecificOutput（v2.1.325 新增，exe off≈311,054,466）.
     *
     * <p>斜杠命令 / MCP prompt <b>展开前</b>触发，可拦下展开。
     *
     * @param hookEventName          CC original: {@code hookEventName} = 'UserPromptExpansion'
     * @param additionalContext      CC original: {@code additionalContext}; optional
     * @param suppressOriginalPrompt CC original: {@code suppressOriginalPrompt};
     *                               {@code decision:'block'} 时不回显原 prompt, optional
     */
    record UserPromptExpansion(
        String additionalContext,
        Boolean suppressOriginalPrompt
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "UserPromptExpansion"; }
    }

    /**
     * CC SessionStart hookSpecificOutput (coreSchemas.ts:821-826 + v2.1.325 两字段).
     *
     * @param additionalContext    CC original: {@code additionalContext} (coreSchemas.ts:823); optional
     * @param initialUserMessage   CC original: {@code initialUserMessage} (coreSchemas.ts:824);
     *                             重注入原 user message, optional
     * @param watchPaths           CC original: {@code watchPaths} (coreSchemas.ts:825);
     *                             文件监听路径列表, optional
     * @param sessionTitle         CC original: {@code sessionTitle} (exe off≈311,054,677);
     *                             hook 给会话起名, optional
     * @param reloadSkills         CC original: {@code reloadSkills} (exe off≈311,054,677);
     *                             hook 装完技能后<b>同一会话</b>重新扫描技能/命令目录，
     *                             使本次会话立即可用, optional
     */
    record SessionStart(
        String additionalContext,
        String initialUserMessage,
        List<String> watchPaths,
        String sessionTitle,
        Boolean reloadSkills
    ) implements HookSpecificOutput {
        public SessionStart {
            if (watchPaths != null) {
                watchPaths = List.copyOf(watchPaths);
            }
        }

        /** [Wave1-B] 3 参转发构造器 · 新两字段缺失（旧调用点零改动）. */
        public SessionStart(String additionalContext, String initialUserMessage, List<String> watchPaths) {
            this(additionalContext, initialUserMessage, watchPaths, null, null);
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
     * CC PreModelSwitch（v2.1.325 新增，exe off≈311,055,100）。permissionDecision 与
     * PreToolUse 同契约：allow 继续（跳过交互式 cache-miss 确认）/ deny 取消切换 /
     * ask 让用户确认（headless 直接拒）。
     *
     * @param hookEventName            CC original: {@code hookEventName} = 'PreModelSwitch'
     * @param permissionDecision       CC original: {@code permissionDecision}; allow|deny|ask, optional
     * @param permissionDecisionReason CC original: {@code permissionDecisionReason}; optional
     */
    record PreModelSwitch(
        String permissionDecision,
        String permissionDecisionReason
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "PreModelSwitch"; }
    }

    /**
     * CC PostModelSwitch（v2.1.325 新增，exe off≈311,055,439）。
     *
     * @param hookEventName     CC original: {@code hookEventName} = 'PostModelSwitch'
     * @param additionalContext CC original: {@code additionalContext}——进新模型的下一次请求, optional
     */
    record PostModelSwitch(
        String additionalContext
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "PostModelSwitch"; }
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
     * CC PostToolUse hookSpecificOutput (coreSchemas.ts:840-844 + v2.1.325 两字段).
     *
     * @param additionalContext    CC original: {@code additionalContext} (coreSchemas.ts:842); optional
     * @param updatedMCPToolOutput CC original: {@code updatedMCPToolOutput} (coreSchemas.ts:843);
     *                             <b>仅 MCP 工具</b>输出替换, optional
     * @param classifierContext    CC original: {@code classifierContext} (exe off≈311,055,676);
     *                             给 auto 模式权限分类器的"宿主断言上下文"（≤2000 UTF-16，
     *                             多 hook 共享一份预算；仅同步响应生效）, optional
     * @param updatedToolOutput    CC original: {@code updatedToolOutput} (exe off≈311,055,676);
     *                             <b>所有工具</b>输出替换，优先于 {@code updatedMCPToolOutput}, optional
     */
    record PostToolUse(
        String additionalContext,
        Object updatedMCPToolOutput,
        String classifierContext,
        Object updatedToolOutput
    ) implements HookSpecificOutput {

        /** [Wave1-B] 2 参转发构造器 · 新两字段缺失（旧调用点零改动）. */
        public PostToolUse(String additionalContext, Object updatedMCPToolOutput) {
            this(additionalContext, updatedMCPToolOutput, null, null);
        }

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
     * CC PostToolBatch（v2.1.325 新增）。一批并行工具全部 resolved 后<b>只触发一次</b>，
     * 是"整批看完再判"的唯一时机。
     *
     * @param hookEventName     CC original: {@code hookEventName} = 'PostToolBatch'
     * @param additionalContext CC original: {@code additionalContext}; optional
     */
    record PostToolBatch(
        String additionalContext
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "PostToolBatch"; }
    }

    /**
     * CC Stop（v2.1.325 新增）。<b>additionalContext 是非错误反馈</b>：送给模型、对话继续，
     * 与 exit 2 的 blockingError 语义相反。
     *
     * @param hookEventName     CC original: {@code hookEventName} = 'Stop'
     * @param additionalContext CC original: {@code additionalContext}; optional
     */
    record Stop(
        String additionalContext
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "Stop"; }
    }

    /**
     * CC SubagentStop（v2.1.325 新增，exe off≈311,058,552）。<b>additionalContext 是非错误反馈</b>：
     * 送给<b>子代理</b>并让它继续行动。
     *
     * @param hookEventName     CC original: {@code hookEventName} = 'SubagentStop'
     * @param additionalContext CC original: {@code additionalContext}; optional
     */
    record SubagentStop(
        String additionalContext
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "SubagentStop"; }
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

    /**
     * CC MessageDisplay（v2.1.325 新增）。<b>仅显示层</b>：替换屏幕上的 delta，
     * 不改存储消息、也不改模型看到的内容（屏幕级脱敏的唯一通道）。
     *
     * @param hookEventName  CC original: {@code hookEventName} = 'MessageDisplay'
     * @param displayContent CC original: {@code displayContent};
     *                       替代 delta 显示；省略或原样返回 → 显示原始内容, optional
     */
    record MessageDisplay(
        String displayContent
    ) implements HookSpecificOutput {
        @Override public String hookEventName() { return "MessageDisplay"; }
    }
}