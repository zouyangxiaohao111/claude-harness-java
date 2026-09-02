package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.permission.hook.FileChangedWatcher;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.worktree.WorktreeCwdTracker;
import com.nexusai.application.agent.worktree.WorktreePaths;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * s18 ExitWorktree 工具 — 对齐 CC ExitWorktreeTool.ts:190-320.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>输入: {@code action} ("keep" | "remove"), 可选 {@code discard_changes} (默认 false)。
 *       <b>无 name 参数</b>（IMP-F1 / DC-F1-01：CC ExitWorktreeTool.ts:30-44 inputSchema 仅
 *       action + discard_changes，操作 currentWorktreeSession 单会话作用域）。</li>
 *   <li><b>会话作用域守卫</b>（IMP-F1 / DC-F1-01）：slug 从
 *       {@code WorktreeCwdTracker.getWorktreeSession(sessionKey)} 解析（对齐 CC
 *       {@code getCurrentWorktreeSession()}），无会话 → errorCode 1 no-op（CC :174-188）。
 *       跨会话 / 手工 worktree（未由本会话 EnterWorktree 创建）受守卫保护，不可被本工具操作。</li>
 *   <li>discard 守卫 errorCode 1/2/3（对齐 CC validateInput :174-224）：无会话=1 /
 *       有变更=2 / 无法验证=3。</li>
 *   <li>action=keep: 保留 worktree + branch (供 review), 恢复 originalCwd (P1-4)</li>
 *   <li>action=remove: 删除 worktree + branch, discardChanges=true 强制删除有变更的 worktree</li>
 *   <li>输出: 8 字段 structuredOutput {@code {action, originalCwd, worktreePath, worktreeBranch,
 *       tmuxSessionName, discardedFiles, discardedCommits, message}}（对齐 CC ExitWorktreeTool.ts:47-58
 *       outputSchema）— keep 分支不含 discarded 字段、remove 分支不含 tmuxSessionName；content 仅
 *       message（对齐 :322-328 mapToolResultToToolResultBlockParam），8 字段不进 LLM。</li>
 * </ul>
 */
@Component
public class ExitWorktreeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ExitWorktreeTool.class);

    public static final String NAME = "ExitWorktree";

    private final WorktreeService worktreeService;

    /** [H14-FIX] FileChanged watcher · 对齐 CC Shell.ts:409 onCwdChangedForHooks(cwd, newCwd). */
    @Autowired(required = false)
    private FileChangedWatcher fileChangedWatcher;
    /** [IMPL-08 D8-1] 多来源 hooks 配置加载链 · 对齐 CC ExitWorktreeTool.ts:140
     *  {@code updateHooksConfigSnapshot()}（restoreSessionToOriginalCwd 后重读 hooks，
     *  worktree 退出后 project settings 恢复生效）。null = 无 bean（跳过）。 */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.hook.MultiSourceHooksConfigLoader hooksConfigLoader;

    /**
     * [R5 结果驱动] Worktree hooks 执行器 · 对齐 CC executeWorktreeRemoveHook
     * (hooks.ts:4967-5003)。
     *
     * <p>setter 注入（对齐 SubagentTool.setHookRegistry）：null = 未装配 → hookBased remove
     * 视为 hookRan=false（warn 保留，不 git remove）。
     */
    @Autowired(required = false)
    private HookRegistry hookRegistry;

    /**
     * [G22③] 会话 AgentState 注册表宿主 · 对齐 CC ExitWorktreeTool.ts:143
     * {@code clearSystemPromptSections()}（restoreSessionToOriginalCwd :143）——退出 worktree
     * 后清会话级 system prompt section 缓存。null = 未装配 → 跳过。
     */
    @Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    /**
     * [G22③] claudemd 记忆文件缓存引擎宿主 · 对齐 CC ExitWorktreeTool.ts:144
     * {@code clearMemoryFileCaches()}（restoreSessionToOriginalCwd :144）。null = 未装配 → 跳过。
     */
    @Autowired(required = false)
    private ClaudemdEngine claudemdEngine;

    /**
     * [IMP-T G15] AnalyticsTracker 遥测统一通道 · 对齐 CC logEvent('tengu_worktree_kept' /
     * 'tengu_worktree_removed')（ExitWorktreeTool.ts:265/:293 + WorktreeExitDialog.tsx:102-135）。
     *
     * <p>null → no-op（未注入/测试场景不破坏既有调用）。
     */
    @Autowired(required = false)
    private AnalyticsTracker analyticsTracker;

    /** [IMP-T G15] 遥测通道注入（非 Spring 场景 / 测试）。 */
    @Autowired(required = false)
    public void setAnalyticsTracker(AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
    }

    @Autowired
    public ExitWorktreeTool(WorktreeService worktreeService) {
        this.worktreeService = worktreeService;
    }

    @Autowired(required = false)
    public void setHookRegistry(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    @Autowired(required = false)
    public void setSessionAgentStateRegistry(SessionAgentStateRegistry sessionAgentStateRegistry) {
        this.sessionAgentStateRegistry = sessionAgentStateRegistry;
    }

    @Autowired(required = false)
    public void setClaudemdEngine(ClaudemdEngine claudemdEngine) {
        this.claudemdEngine = claudemdEngine;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Exit a worktree session created by EnterWorktree. "
                + "Use action='keep' to preserve the worktree and branch for review, "
                + "or action='remove' to discard both. If the worktree has uncommitted changes, "
                + "remove will refuse unless discard_changes=true.";
    }

    /**
     * 用户可见名称 · 对齐 CC ExitWorktreeTool.ts:164-166 {@code userFacingName()}
     * {@code return 'Exiting worktree'}（UI 进行态提示）。
     */
    @Override
    public String userFacingName() {
        return "Exiting worktree"; // CC ExitWorktreeTool.ts:165
    }

    /**
     * 工具提示词 · 对齐 CC {@code ExitWorktreeTool/prompt.ts:1-30}
     * {@code getExitWorktreeToolPrompt()} 逐字移植（反引号转义保留）。
     */
    @Override
    public String prompt() {
        return """
            Exit a worktree session created by EnterWorktree and return the session to the original working directory.

            ## Scope

            This tool ONLY operates on worktrees created by EnterWorktree in this session. It will NOT touch:
            - Worktrees you created manually with `git worktree add`
            - Worktrees from a previous session (even if created by EnterWorktree then)
            - The directory you're in if EnterWorktree was never called

            If called outside an EnterWorktree session, the tool is a **no-op**: it reports that no worktree session is active and takes no action. Filesystem state is unchanged.

            ## When to Use

            - The user explicitly asks to "exit the worktree", "leave the worktree", "go back", or otherwise end the worktree session
            - Do NOT call this proactively — only when the user asks

            ## Parameters

            - `action` (required): `"keep"` or `"remove"`
              - `"keep"` — leave the worktree directory and branch intact on disk. Use this if the user wants to come back to the work later, or if there are changes to preserve.
              - `"remove"` — delete the worktree directory and its branch. Use this for a clean exit when the work is done or abandoned.
            - `discard_changes` (optional, default false): only meaningful with `action: "remove"`. If the worktree has uncommitted files or commits not on the original branch, the tool will REFUSE to remove it unless this is set to `true`. If the tool returns an error listing changes, confirm with the user before re-invoking with `discard_changes: true`.

            ## Behavior

            - Restores the session's working directory to where it was before EnterWorktree
            - Clears CWD-dependent caches (system prompt sections, memory files, plans directory) so the session state reflects the original directory
            - If a tmux session was attached to the worktree: killed on `remove`, left running on `keep` (its name is returned so the user can reattach)
            - Once exited, EnterWorktree can be called again to create a fresh worktree
            """;
    }

    /**
     * [WF-B4] 恒启用 — 对齐 CC {@code utils/worktreeModeEnabled.ts:11} {@code return true}
     * 恒真 + {@code tool.ts:758} 基类默认 {@code isEnabled: () => true}.
     *
     * <p>删除了原先委托 EnterWorktreeTool 静态门控方法的环境变量/sysprop 守卫
     * （该门控已随 WF-B4 删除，CC 无此逻辑）.
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /** 是否延迟执行 · 对齐 CC ExitWorktreeTool.ts:167 shouldDefer: true（常量，与 input 无关）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    /**
     * [Q-3] 是否破坏性操作 · 对齐 CC ExitWorktreeTool.ts:168-170
     * {@code isDestructive(input) { return input.action === 'remove' }}.
     *
     * <p>action='remove'（删除 worktree + branch）为不可逆破坏性操作 → true；
     * action='keep'（保留）及其余情况 → false。CC input 为 z.infer&lt;Input&gt; 恒非空，
     * Java 侧 execute 之外无强类型保证，input 为 null 或 action 缺失/非字符串时
     * 显式兜底 false（fail-closed 对齐 Tool.java:338-340 默认 false）。
     */
    @Override
    public boolean isDestructive(JsonNode input) {
        if (input == null) {
            return false;
        }
        JsonNode action = input.get("action");
        if (action == null || !action.isTextual()) {
            return false;
        }
        return "remove".equals(action.asText());
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.putArray("enum").add("keep").add("remove");
        action.put("description", "Whether to keep or remove the worktree.");

        ObjectNode discard = props.putObject("discard_changes");
        discard.put("type", "boolean");
        discard.put("default", false);
        discard.put("description",
                "Required only when action='remove'. If true, force-remove even with uncommitted "
                        + "changes. If false (default), the tool errors when there are changes.");

        // [IMP-F1 / DC-F1-01] 删除 name 参数：对齐 CC ExitWorktreeTool.ts:30-44 inputSchema
        //   （strictObject 仅 action + discard_changes，无 name）。工具只操作当前会话
        //   worktree（getWorktreeSession 解析），不按 name 操作跨会话/手工 worktree。
        schema.putArray("required").add("action");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * [IMP-F1 / DC-F1-01] 会话作用域守卫 + discard 守卫 · 对齐 CC ExitWorktreeTool.ts:174-224
     * {@code validateInput}。
     *
     * <p><b>会话作用域守卫（errorCode 1）</b>（CC :180-188）：{@code getCurrentWorktreeSession()}
     * 为 null（除非 EnterWorktree 在本会话创建过 worktree）→ no-op 拒绝。跨会话 / 手工
     * {@code git worktree add} 创建的 worktree 不进该会话作用域，不可被本工具操作（防误删）。
     *
     * <p><b>discard 守卫（errorCode 2/3）</b>（CC :190-221）：action=remove 且未
     * {@code discard_changes} → 计数变更：无法验证（未知）→ errorCode 3；有变更 →
     * errorCode 2。
     *
     * @return {@link Tool.ValidationResult}：pass() 通过；fail(code,msg) 注入 LLM 自纠
     */
    @Override
    public Tool.ValidationResult validateInput(JsonNode input, ToolUseContext ctx) {
        // 会话作用域守卫：slug 必须来自当前会话 worktree（CC ExitWorktreeTool.ts:180-188）
        String sessionKey = (ctx != null && ctx.sessionId() != null)
                ? ctx.sessionId() : null;
        WorktreeCwdTracker.WorktreeSession session = (sessionKey != null)
                ? WorktreeCwdTracker.getWorktreeSession(sessionKey) : null;
        if (session == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ExitWorktreeTool] validateInput 会话守卫拦截: 无活跃 EnterWorktree 会话 sessionKey={}",
                        sessionKey);
            }
            return Tool.ValidationResult.fail("1",
                    "No-op: there is no active EnterWorktree session to exit. "
                            + "This tool only operates on worktrees created by EnterWorktree in the "
                            + "current session — it will not touch worktrees created manually or in a "
                            + "previous session. No filesystem changes were made.");
        }
        String action = readString(input, "action");
        boolean discardChanges = readBool(input, "discard_changes", false);
        if ("remove".equals(action) && !discardChanges) {
            // [G22④ / OPD-PW-11] countChanges 走 path 级重载（对齐 CC ExitWorktreeTool.ts:191-193
            //   countWorktreeChanges(session.worktreePath, session.originalHeadCommit)）——真实
            //   worktree 路径计数（hook-based 亦命中），不再经 gitRoot+slug 推导（推导路径与
            //   hookBasedPath 不一致 → 误判 0/0 clean）。
            WorktreeService.WorktreeChanges changes = worktreeService.countChangesByPath(
                    Path.of(session.worktreePath()), session.worktreeName());
            if (changes.unknown()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ExitWorktreeTool] validateInput discard 守卫: 无法验证 worktree 状态 "
                            + "worktreeName={} → errorCode 3", session.worktreeName());
                }
                return Tool.ValidationResult.fail("3",
                        "Could not verify worktree state at " + session.worktreePath()
                                + ". Refusing to remove without explicit confirmation. "
                                + "Re-invoke with discard_changes: true to proceed — or use action: "
                                + "\"keep\" to preserve the worktree.");
            }
            if (changes.hasAny()) {
                List<String> parts = new ArrayList<>();
                if (changes.modifiedFileCount() > 0) {
                    parts.add(changes.modifiedFileCount() + " uncommitted "
                            + (changes.modifiedFileCount() == 1 ? "file" : "files"));
                }
                if (changes.unpushedCommitCount() > 0) {
                    parts.add(changes.unpushedCommitCount() + " "
                            + (changes.unpushedCommitCount() == 1 ? "commit" : "commits") + " on "
                            + (session.worktreeBranch() != null ? session.worktreeBranch()
                                    : "the worktree branch"));
                }
                if (log.isDebugEnabled()) {
                    log.debug("[ExitWorktreeTool] validateInput discard 守卫: worktree 有变更 "
                            + "worktreeName={} parts={} → errorCode 2",
                            session.worktreeName(), String.join(" and ", parts));
                }
                return Tool.ValidationResult.fail("2",
                        "Worktree has " + String.join(" and ", parts)
                                + ". Removing will discard this work permanently. "
                                + "Confirm with the user, then re-invoke with discard_changes: true — "
                                + "or use action: \"keep\" to preserve the worktree.");
            }
        }
        return Tool.ValidationResult.pass();
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        String action = readString(call, "action");
        if (action == null) {
            return ToolResult.error(call.id(), "missing required input: action");
        }
        // [IMP-F1 / DC-F1-01] slug 不再来自 name 参数：从当前会话 worktree 会话对象解析
        //   （对齐 CC ExitWorktreeTool.ts:227-233 call() 用 getCurrentWorktreeSession()）。
        //   validateInput 已做会话守卫；此处再兜底（CC :229-233 竞态防御 throw）。
        String sessionKey = (ctx != null && ctx.sessionId() != null)
                ? ctx.sessionId() : null;
        WorktreeCwdTracker.WorktreeSession session = (sessionKey != null)
                ? WorktreeCwdTracker.getWorktreeSession(sessionKey) : null;
        if (session == null) {
            log.warn("[ExitWorktreeTool] 无会话作用域守卫拦截（竞态/直调）：sessionKey={}",
                    sessionKey);
            return ToolResult.error(call.id(),
                    "No-op: there is no active EnterWorktree session to exit. "
                            + "This tool only operates on worktrees created by EnterWorktree in the "
                            + "current session — it will not touch worktrees created manually or in a "
                            + "previous session. No filesystem changes were made.");
        }
        String slug = session.worktreeName();
        boolean discardChanges = readBool(call, "discard_changes", false);
        Path gitRoot = currentGitRoot();
        // gap1-originalCwd: CC ExitWorktreeTool.ts:47-58 outputSchema originalCwd 必填。
        //   Java 端退出时回显用户真实目录（前端传入），缺失回退 getOriginalCwdLayer
        //   （对齐 CC session.originalCwd，零行为变化——不再用 gitRoot，见 resolveOriginalCwd）。
        String originalCwd = resolveOriginalCwd(ctx);
        try {
            switch (action) {
                case "keep" -> {
                    // [IMP-T G15] 对齐 CC ExitWorktreeTool.ts:256-259 countWorktreeChanges 入口计数
                    //   （keep/remove 共用）→ commits/changed_files 供 tengu_worktree_kept 遥测（:265）
                    WorktreeService.WorktreeChanges changes = worktreeService.countChanges(gitRoot, slug);
                    int keptCommits = changes != null ? changes.unpushedCommitCount() : 0;
                    int keptChangedFiles = changes != null ? changes.modifiedFileCount() : 0;
                    worktreeService.keepWorktree(gitRoot, slug);
                    // [R5] CC keepWorktree (worktree.ts:780-799) 清 currentWorktreeSession —
                    //   hookBased 登记同步消费清除（worktree 保留，返回值丢弃）
                    worktreeService.consumeHookBasedWorktree(slug);
                    // s18 P1-10: 退出 worktree → 清除 session cwd
                    if (sessionKey != null) {
                        java.nio.file.Path oldCwd =
                                com.nexusai.application.agent.worktree.WorktreeCwdTracker.getCwd(sessionKey);
                        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearCwd(sessionKey);
                        // [Fix-R1] AC-7: 退出 worktree → 清 SessionCwdHolder（恢复 pre-worktree cwd，
                        //   worktree 入口写入被清，getCwd 回落 boundProject/user.dir；对齐 CC 退出 worktree
                        //   后 STATE.cwd 回到 pre-worktree）
                        com.nexusai.application.agent.agent.SessionCwdHolder.clear(sessionKey);
                        // [SP-11] 退出 worktree → 清会话级 worktree 绑定（对齐 CC ExitWorktreeTool.ts 置
                        //   currentWorktreeSession=null worktree.ts:156-158；isWorktree 子弹随之消失）
                        com.nexusai.application.agent.agent.SessionCwdHolder.clearWorktree(sessionKey);
                        // [INV-3] 退出 worktree → 清 originalCwd 重锚层（对齐 CC ExitWorktreeTool.ts:129
                        //   setOriginalCwd(originalCwd) 退出恢复）。Java 无 pre-worktree originalCwd 持久化
                        //   （boundProject 是稳定身份不变），clear 回落 boundProject = pre-worktree originalCwd 语义。
                        com.nexusai.application.agent.agent.SessionCwdHolder.clearOriginalCwd(sessionKey);
                        // gap1-originalCwd: 退出 worktree → 清除会话 originalCwd（对齐 CC ExitWorktreeTool.ts:47-58
                        //   call() 在清 currentWorktreeSession 前解构 originalCwd，退出后会话状态清空）
                        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearOriginalCwd(sessionKey);
                        // [WF-2] 清 transcript worktree-state（对齐 CC ExitWorktreeTool.ts:142
                        //   saveWorktreeState(null)：exit 写 null → resume 不回 cd 进 worktree）
                        clearWorktreeState(sessionKey);
                        // [H14-FIX] 对齐 CC Shell.ts:409 — 退出 worktree 即 cwd 切回工作区
                        // [H14 v3 Gap②] newCwd 传真实工作区目录 (workspaceDir = gitRoot = user.dir)，
                        //   不再传 null — watcher 才能基于新 cwd 重解析监听路径 (CC Shell.ts:409
                        //   onCwdChangedForHooks(cwd, newCwd)，退出 worktree 时 setCwd(originalCwd))。
                        if (fileChangedWatcher != null) {
                            fileChangedWatcher.onCwdChangedForHooks(
                                    oldCwd != null ? oldCwd.toString() : null,
                                    gitRoot.toString());
                        }
                    }
                    // [IMPL-08 D8-1] 对齐 CC ExitWorktreeTool.ts:140 — 退出 worktree 后
                    //   updateHooksConfigSnapshot(): 重读 user/project/local/policy hooks
                    //   （project settings 随 cwd 恢复重新解析，运行中配置变更生效）
                    if (hooksConfigLoader != null) {
                        hooksConfigLoader.updateHooksConfigSnapshot();
                    }
                    // [G22③] 退出 worktree 后清 CWD 依赖缓存 · 对齐 CC ExitWorktreeTool.ts:143-145
                    //   restoreSessionToOriginalCwd（clearSystemPromptSections + clearMemoryFileCaches
                    //   + getPlansDirectory.cache.clear）
                    clearWorktreeCaches(ctx);
                    // [exitworktree-contract] keep data 对齐 CC ExitWorktreeTool.ts:274-283：
                    //   action/originalCwd/worktreePath/worktreeBranch/tmuxSessionName/message 六键，
                    //   不含 discardedFiles/discardedCommits；tmuxSessionName 恒 null（RETAIN-gap，
                    //   Java 无 tmux 会话能力，tmuxNote 随 null 自然省略）。
                    Path keptPath = WorktreePaths.worktreePathFor(gitRoot, slug);
                    String keptBranch = WorktreePaths.worktreeBranchName(slug);
                    String keepMessage = "Exited worktree. Your work is preserved at " + keptPath
                            + " on branch " + keptBranch + ". Session is now back in "
                            + originalCwd + ".";
                    Map<String, Object> keepOutput = new LinkedHashMap<>();
                    keepOutput.put("action", "keep");
                    keepOutput.put("originalCwd", originalCwd);
                    keepOutput.put("worktreePath", keptPath.toString());
                    keepOutput.put("worktreeBranch", keptBranch);
                    keepOutput.put("tmuxSessionName", null);
                    keepOutput.put("message", keepMessage);
                    log.info("[ExitWorktreeTool] action=keep worktreePath={} discardedFiles=0 discardedCommits=0",
                            keptPath);
                    // [IMP-T G15] 遥测 tengu_worktree_kept（CC ExitWorktreeTool.ts:265）
                    emitWorktreeKept(keptCommits, keptChangedFiles);
                    return ToolResult.successWithStructuredOutput(call.id(), keepMessage, keepOutput);
                }
                case "remove" -> {
                    // [R5 结果驱动] CC cleanupWorktree (worktree.ts:815-855)：hookBased →
                    //   executeWorktreeRemoveHook(worktreePath) → hookRan=true 跳过 git remove；
                    //   hookRan=false → warn 'No WorktreeRemove hook configured, hook-based
                    //   worktree left'（保留）；非 hookBased → git worktree remove --force。
                    //   （Java 无 CC validateInput 守卫层，守卫在 WorktreeService git 路径；
                    //     hook 路径直接委派 remove hook，对齐 CC cleanupWorktree 本体。）
                    Path hookBasedPath = worktreeService.consumeHookBasedWorktree(slug);
                    String message;
                    String removedPath;
                    String removedBranch;
                    int discardedFiles;
                    int discardedCommits;
                    if (hookBasedPath != null) {
                        boolean hookRan = hookRegistry != null
                                && hookRegistry.executeWorktreeRemoveHook(hookBasedPath.toString());
                        if (hookRan) {
                            message = "removed hook-based worktree at " + hookBasedPath;
                        } else {
                            // CC worktree.ts:826-830 — logForDebugging warn 后保留
                            log.warn("[ExitWorktreeTool] No WorktreeRemove hook configured, "
                                    + "hook-based worktree left at {}", hookBasedPath);
                            message = "no WorktreeRemove hook configured, "
                                    + "hook-based worktree left at " + hookBasedPath;
                        }
                        // [G22④ / OPD-PW-11] hook 路径计数改走 path 级重载（对齐 CC
                        // countWorktreeChanges(session.worktreePath, session.originalHeadCommit)）：
                        // countChangesByPath(hookBasedPath, slug) 用真实 hook 路径计数。hook-based
                        // 无 originalHeadCommit 基线（worktree.ts:525-532 不登记）→ 返回 unknown
                        // （fail-closed），call() 语义同 CC ?? {0,0}（ExitWorktreeTool.ts:256-259）：
                        // discardedFiles=真实 modified 数、discardedCommits=0；null（mock / 异常）
                        // 回落 0/0（CC countWorktreeChanges null → fallback {0,0} 同语义）。
                        WorktreeService.WorktreeChanges hookChanges = worktreeService.countChangesByPath(
                                hookBasedPath, slug);
                        removedPath = hookBasedPath.toString();
                        removedBranch = null;
                        discardedFiles = hookChanges != null ? hookChanges.modifiedFileCount() : 0;
                        discardedCommits = hookChanges != null ? hookChanges.unpushedCommitCount() : 0;
                    } else {
                        // CC countWorktreeChanges 在 cleanupWorktree 前计数（call() 入口 :256-259）；
                        // Java 侧 removeWorktree 内部会二次计数 + 拒绝守卫，此处先计数捕获 discarded 值。
                        WorktreeService.WorktreeChanges changes = worktreeService.countChanges(gitRoot, slug);
                        discardedFiles = changes.modifiedFileCount();
                        discardedCommits = changes.unpushedCommitCount();
                        worktreeService.removeWorktree(gitRoot, slug, discardChanges);
                        removedPath = WorktreePaths.worktreePathFor(gitRoot, slug).toString();
                        removedBranch = WorktreePaths.worktreeBranchName(slug);
                        message = buildRemoveMessage(removedPath, discardedFiles, discardedCommits,
                                originalCwd);
                    }
                    if (sessionKey != null) {
                        java.nio.file.Path oldCwd =
                                com.nexusai.application.agent.worktree.WorktreeCwdTracker.getCwd(sessionKey);
                        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearCwd(sessionKey);
                        // [Fix-R1] AC-7: 退出 worktree → 清 SessionCwdHolder（恢复 pre-worktree cwd；
                        //   对齐 CC 退出 worktree 后 STATE.cwd 回到 pre-worktree）
                        com.nexusai.application.agent.agent.SessionCwdHolder.clear(sessionKey);
                        // [SP-11] 退出 worktree → 清会话级 worktree 绑定（对齐 CC ExitWorktreeTool.ts 置
                        //   currentWorktreeSession=null worktree.ts:156-158；isWorktree 子弹随之消失）
                        com.nexusai.application.agent.agent.SessionCwdHolder.clearWorktree(sessionKey);
                        // [INV-3] 退出 worktree → 清 originalCwd 重锚层（对齐 CC ExitWorktreeTool.ts:129
                        //   setOriginalCwd(originalCwd) 退出恢复；clear 回落 boundProject = pre-worktree originalCwd 语义）
                        com.nexusai.application.agent.agent.SessionCwdHolder.clearOriginalCwd(sessionKey);
                        // gap1-originalCwd: 退出 worktree → 清除会话 originalCwd（对齐 CC ExitWorktreeTool.ts:47-58
                        //   call() 在清 currentWorktreeSession 前解构 originalCwd，退出后会话状态清空）
                        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearOriginalCwd(sessionKey);
                        // [WF-2] 清 transcript worktree-state（对齐 CC ExitWorktreeTool.ts:142
                        //   saveWorktreeState(null)：exit 写 null → resume 不回 cd 进 worktree）
                        clearWorktreeState(sessionKey);
                        // [H14-FIX] 对齐 CC Shell.ts:409 — 退出 worktree 即 cwd 切回工作区
                        // [H14 v3 Gap②] newCwd 传真实工作区目录 (workspaceDir = gitRoot = user.dir)，
                        //   不再传 null — watcher 才能基于新 cwd 重解析监听路径 (CC Shell.ts:409
                        //   onCwdChangedForHooks(cwd, newCwd)，退出 worktree 时 setCwd(originalCwd))。
                        if (fileChangedWatcher != null) {
                            fileChangedWatcher.onCwdChangedForHooks(
                                    oldCwd != null ? oldCwd.toString() : null,
                                    gitRoot.toString());
                        }
                    }
                    // [IMPL-08 D8-1] 同 keep 分支 — 对齐 CC ExitWorktreeTool.ts:140
                    if (hooksConfigLoader != null) {
                        hooksConfigLoader.updateHooksConfigSnapshot();
                    }
                    // [G22③] 同 keep 分支 — 退出 worktree 后清 CWD 依赖缓存（对齐 CC :143-145）
                    clearWorktreeCaches(ctx);
                    // [exitworktree-contract] remove data 对齐 CC ExitWorktreeTool.ts:310-320：
                    //   action/originalCwd/worktreePath/worktreeBranch/discardedFiles/discardedCommits/message
                    //   七键，不含 tmuxSessionName。
                    Map<String, Object> removeOutput = new LinkedHashMap<>();
                    removeOutput.put("action", "remove");
                    removeOutput.put("originalCwd", originalCwd);
                    removeOutput.put("worktreePath", removedPath);
                    removeOutput.put("worktreeBranch", removedBranch);
                    removeOutput.put("discardedFiles", discardedFiles);
                    removeOutput.put("discardedCommits", discardedCommits);
                    removeOutput.put("message", message);
                    log.info("[ExitWorktreeTool] action=remove worktreePath={} discardedFiles={} discardedCommits={}",
                            removedPath, discardedFiles, discardedCommits);
                    // [IMP-T G15] 遥测 tengu_worktree_removed（CC ExitWorktreeTool.ts:293）
                    emitWorktreeRemoved(discardedCommits, discardedFiles);
                    return ToolResult.successWithStructuredOutput(call.id(), message, removeOutput);
                }
                default -> {
                    return ToolResult.error(call.id(),
                            "action must be 'keep' or 'remove', got: " + action);
                }
            }
        } catch (WorktreeService.WorktreeException e) {
            log.warn("[ExitWorktreeTool] action={} failed: {}", action, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.id(), e.getMessage());
        }
    }

    private String readString(ToolUseBlock call, String key) {
        return readString(call.input(), key);
    }

    private boolean readBool(ToolUseBlock call, String key, boolean defaultValue) {
        return readBool(call.input(), key, defaultValue);
    }

    /** JsonNode 版（validateInput 入参为裸 JsonNode，无 ToolUseBlock 包装）。 */
    private String readString(JsonNode input, String key) {
        if (input == null || !input.has(key)) {
            return null;
        }
        JsonNode v = input.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** JsonNode 版（validateInput 入参为裸 JsonNode，无 ToolUseBlock 包装）。 */
    private boolean readBool(JsonNode input, String key, boolean defaultValue) {
        if (input == null || !input.has(key) || input.get(key).isNull()) {
            return defaultValue;
        }
        return input.get(key).asBoolean(defaultValue);
    }

    /** 当前 git 仓库根目录 · 对齐 CC worktree.ts findCanonicalGitRoot(getCwd()).
     *  cwd-align-ext：user.dir 硬编码 → 会话 cwd + 复用 {@link AutoMemPaths#findCanonicalGitRoot}；
     *  无 sessionId 回落 user.dir（方案 1，零行为变化）。 */
    private Path currentGitRoot() {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        if (cwd == null || cwd.isBlank()) {
            cwd = System.getProperty("user.dir", ".");
        }
        String canonical = AutoMemPaths.findCanonicalGitRoot(cwd);
        return Paths.get(canonical != null && !canonical.isBlank() ? canonical : cwd);
    }

    /**
     * gap1-originalCwd: 解析退出时回显的 originalCwd。优先取 WorktreeCwdTracker 里 Enter 捕获的
     * 用户真实目录（前端传入）；缺失/空白回退 {@code CwdResolution.getOriginalCwdLayer(sessionId)}
     * （boundProject → user.dir）——对齐 CC ExitWorktreeTool.ts:47-58 outputSchema originalCwd 必填
     * + call() 退出前解构 session.originalCwd。
     *
     * <p><b>cwd-align Rework</b>：兜底不再用 {@code gitRoot.toString()}（canonical 主仓根 ≠ 用户
     * 工作目录，worktree 部署/测试环境下 findCanonicalGitRoot 返回主仓根，回显即错误）；改走统一
     * CwdResolution API（探查 loop-api-worktree-probe #8 处置决策：resolveOriginalCwd 兜底改
     * {@code getOriginalCwdLayer}，对齐 CC session.originalCwd）。无会话/无绑定回落 user.dir，
     * 零行为变化。
     */
    private String resolveOriginalCwd(ToolUseContext ctx) {
        String stored = (ctx != null && ctx.sessionId() != null)
                ? com.nexusai.application.agent.worktree.WorktreeCwdTracker.getOriginalCwd(ctx.sessionId())
                : null;
        if (stored == null || stored.isBlank()) {
            String sessionId = (ctx != null && ctx.sessionId() != null)
                    ? ctx.sessionId() : null;
            String fallback = CwdResolution.getOriginalCwdLayer(sessionId);
            if (log.isDebugEnabled()) {
                log.debug("[ExitWorktreeTool] originalCwd 未设置，回退 getOriginalCwdLayer={}", fallback);
            }
            return fallback;
        }
        if (log.isDebugEnabled()) {
            log.debug("[ExitWorktreeTool] originalCwd 命中 session={} originalCwd={}",
                    ctx.sessionId(), stored);
        }
        return stored;
    }

    /**
     * [WF-2] 清 transcript worktree-state · 对齐 CC ExitWorktreeTool.ts:142
     * {@code saveWorktreeState(null)}（sessionStorage.ts:2889-2916）—— exit 写 null，
     * resume 据此不回 cd 进 worktree（对齐 CC sessionRestore.ts:332-366 restoreWorktreeForResume
     * 对 {@code worktreeSession == null} 的短路）。
     *
     * <p>transcript 定位同 Enter 侧 {@code persistWorktreeState}：workspaceDir =
     * {@code AutoMemPaths.currentSessionProjectRoot()}，sessionId = ctx.sessionId() UUID 串。
     */
    private void clearWorktreeState(String sessionKey) {
        if (sessionKey == null) {
            return;
        }
        java.nio.file.Path workspaceDir = java.nio.file.Paths.get(
            com.nexusai.application.agent.memory.AutoMemPaths.currentSessionProjectRoot());
        com.nexusai.application.agent.tool.SessionStorage.writeWorktreeState(
            workspaceDir, sessionKey, null);
        // [RESIDUAL-FIX 残留 2] 同步清 WorktreeCwdTracker 完整 worktree 会话对象
        //   （对齐 CC ExitWorktreeTool.ts:142 saveWorktreeState(null) + 清 currentWorktreeSession）。
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.clearWorktreeSession(sessionKey);
        log.info("[ExitWorktreeTool] 清 transcript worktree-state: session={}", sessionKey);
    }

    /**
     * [G22③] 退出 worktree 后清 CWD 依赖缓存 · 对齐 CC ExitWorktreeTool.ts:143-145
     * {@code restoreSessionToOriginalCwd}（逆 Enter 侧 :98-102）：
     * <pre>
     * clearSystemPromptSections()      // 清会话级 section 缓存，回到原目录后重算
     * clearMemoryFileCaches()          // claudemd.ts:1119-1122 清 getMemoryFiles memoize
     * getPlansDirectory.cache.clear?.()// plans 目录 memoize（Java N/A：PlanProviderImpl 每会话构造）
     * </pre>
     *
     * <p>Java 等价同 Enter 侧 {@link EnterWorktreeTool#clearWorktreeCaches}（复用接线模式）：
     * {@link SessionAgentStateRegistry#get(UUID)} → {@code systemPromptSectionCache().clear()} +
     * {@link ClaudemdEngine#clearMemoryFileCaches()}；registry / engine 未注入 → 跳过（null 守卫）。
     *
     * @param ctx 工具调用上下文（用于解析当前会话 UUID；null → 无法定位会话级缓存）
     */
    private void clearWorktreeCaches(ToolUseContext ctx) {
        // [session-id-short] ctx.sessionId() 已 String（short）
        String sessionId = (ctx != null) ? ctx.sessionId() : null;
        if (sessionAgentStateRegistry != null) {
            if (sessionId != null) {
                com.nexusai.application.agent.AgentState state = sessionAgentStateRegistry.get(sessionId);
                if (state != null) {
                    state.systemPromptSectionCache().clear();
                    log.info("[ExitWorktreeTool] 清空会话 {} 的 system prompt section 缓存（对齐 CC ExitWorktreeTool.ts:143 clearSystemPromptSections）",
                        sessionId);
                } else if (log.isDebugEnabled()) {
                    log.debug("[ExitWorktreeTool] clearSystemPromptSections 跳过：会话 {} 无活跃 AgentState", sessionId);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("[ExitWorktreeTool] clearSystemPromptSections 跳过：无 sessionId");
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[ExitWorktreeTool] clearSystemPromptSections 跳过：SessionAgentStateRegistry 未接线");
        }
        if (claudemdEngine != null) {
            claudemdEngine.clearMemoryFileCaches();
            log.info("[ExitWorktreeTool] 清空 claudemd getMemoryFiles 缓存（对齐 CC ExitWorktreeTool.ts:144 clearMemoryFileCaches）");
        } else if (log.isDebugEnabled()) {
            log.debug("[ExitWorktreeTool] clearMemoryFileCaches 跳过：ClaudemdEngine 未接线");
        }
        // getPlansDirectory.cache.clear?.() → Java N/A（PlanProviderImpl 每会话构造，plans 目录非 cwd 依赖）
    }

    /**
     * [IMP-T G15] tengu_worktree_kept 遥测 · 对齐 CC ExitWorktreeTool.ts:265
     * {@code logEvent('tengu_worktree_kept', {mid_session:true, commits, changed_files})}。
     * Java 工具调用退出 = CC ExitWorktreeTool 中会话路径 → {@code mid_session:true}。
     *
     * @param commits      unpushed commit 数（CC commits，countWorktreeChanges 产物）
     * @param changedFiles modified 文件数（CC changed_files）
     */
    private void emitWorktreeKept(int commits, int changedFiles) {
        if (analyticsTracker == null) {
            return;
        }
        analyticsTracker.logEvent("tengu_worktree_kept",
            Map.<String, Object>of(
                "mid_session", true,
                "commits", commits,
                "changed_files", changedFiles));
        if (log.isDebugEnabled()) {
            log.debug("[ExitWorktreeTool] [IMP-T G15] 遥测 tengu_worktree_kept: commits={} changed_files={}",
                commits, changedFiles);
        }
    }

    /**
     * [IMP-T G15] tengu_worktree_removed 遥测 · 对齐 CC ExitWorktreeTool.ts:293
     * {@code logEvent('tengu_worktree_removed', {mid_session:true, commits, changed_files})}。
     *
     * @param commits      discarded/unpushed commit 数（CC commits）
     * @param changedFiles discarded modified 文件数（CC changed_files）
     */
    private void emitWorktreeRemoved(int commits, int changedFiles) {
        if (analyticsTracker == null) {
            return;
        }
        analyticsTracker.logEvent("tengu_worktree_removed",
            Map.<String, Object>of(
                "mid_session", true,
                "commits", commits,
                "changed_files", changedFiles));
        if (log.isDebugEnabled()) {
            log.debug("[ExitWorktreeTool] [IMP-T G15] 遥测 tengu_worktree_removed: commits={} changed_files={}",
                commits, changedFiles);
        }
    }

    /**
     * remove 分支 message · 对齐 CC ExitWorktreeTool.ts:299-318（discardParts commits 先、files 后，
     * discardNote = " Discarded ... and ..."，无变更时为空串）。
     */
    private static String buildRemoveMessage(String worktreePath, int discardedFiles,
                                              int discardedCommits, String originalCwd) {
        StringBuilder sb = new StringBuilder("Exited and removed worktree at ")
                .append(worktreePath).append(".");
        if (discardedCommits > 0 || discardedFiles > 0) {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (discardedCommits > 0) {
                parts.add(discardedCommits + (discardedCommits == 1 ? " commit" : " commits"));
            }
            if (discardedFiles > 0) {
                parts.add(discardedFiles + (discardedFiles == 1 ? " uncommitted file" : " uncommitted files"));
            }
            sb.append(" Discarded ").append(String.join(" and ", parts)).append(".");
        }
        return sb.append(" Session is now back in ").append(originalCwd).append(".").toString();
    }

    /**
     * mapToToolResultBlockParam · 对齐 CC ExitWorktreeTool.ts:322-328。
     *
     * <p>CC {@code mapToolResultToToolResultBlockParam({message}, toolUseID)} 仅抽取 message 作
     * content，8 字段走 data 结构化通道（Java = structuredOutput）不进 LLM。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        // 对齐 RemoteTriggerTool/AskUserQuestionTool 惯例：isError / 非 ToolResult → 返回 null，
        // 让默认渲染器回退输出 error message（fail-loud 路径正确显错）。
        if (!(result instanceof ToolResult<?> tr) || isError) {
            if (log.isDebugEnabled()) {
                log.debug("[ExitWorktreeTool] mapToToolResultBlockParam 跳过: isError 或非 ToolResult（fail-loud 路径回退默认渲染器）");
            }
            return null;
        }
        Object messageObj = ToolResult.presentationMeta(tr).get("message");
        String content = messageObj instanceof String s ? s : "";
        if (log.isDebugEnabled()) {
            log.debug("[ExitWorktreeTool] mapToToolResultBlockParam 生成 tool_result content长度={}（CC ExitWorktreeTool.ts:322-328 仅 message 作 content）",
                    content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }
}