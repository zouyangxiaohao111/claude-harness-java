package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.worktree.WorktreeCreateResult;
import com.nexusai.application.agent.worktree.WorktreePaths;
import com.nexusai.application.agent.permission.hook.FileChangedWatcher;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.worktree.WorktreeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * s18 EnterWorktree 工具 — 对齐 CC EnterWorktreeTool.ts.
 *
 * <p>L1 行为:
 * <ul>
 *   <li>输入: {@code name} (slug, 每段 regex [a-zA-Z0-9._-]+, max 64 chars, max 8 段)</li>
 *   <li>输出: {@code {worktreePath, worktreeBranch, message}}</li>
 *   <li>副作用: 当前会话 process.chdir(worktreePath) — 对齐 CC setCwd</li>
 *   <li>Created/Resumed 都允许 — Resumed 时 message 注明"快速恢复"</li>
 * </ul>
 *
 * <p>对齐 CC EnterWorktreeTool.ts:77-100, worktree.ts:702-778 createWorktreeForSession.
 */
@Component
public class EnterWorktreeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(EnterWorktreeTool.class);

    public static final String NAME = "EnterWorktree";

    private final WorktreeService worktreeService;

    /** [H14-FIX] FileChanged watcher · 对齐 CC Shell.ts:409 onCwdChangedForHooks(cwd, newCwd). */
    @Autowired(required = false)
    private FileChangedWatcher fileChangedWatcher;

    /**
     * [R5 结果驱动] Worktree hooks 执行器 · 对齐 CC hasWorktreeCreateHook /
     * executeWorktreeCreateHook (hooks.ts:4910-4958)。
     *
     * <p>setter 注入（对齐 SubagentTool.setHookRegistry）：null = 未装配（测试/降级）
     * → hasHookForEvent 短路，恒走 git worktree 路径。
     */
    @Autowired(required = false)
    private HookRegistry hookRegistry;

    /**
     * [G22③] 会话 AgentState 注册表宿主 · 对齐 CC {@code clearSystemPromptSections()}
     * （EnterWorktreeTool.ts:99）——进入 worktree 后清空会话级 system prompt section 缓存，
     * 使 env_info_simple 等以新 worktree 上下文重算。null = 未装配（测试/非 Spring）→ 跳过。
     */
    @Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    /**
     * [G22③] claudemd 记忆文件缓存引擎宿主 · 对齐 CC {@code clearMemoryFileCaches()}
     * （EnterWorktreeTool.ts:101，claudemd.ts:1119-1122）——清空依赖 CWD 的 getMemoryFiles
     * memoize 缓存。null = 未装配 → 跳过。
     */
    @Autowired(required = false)
    private ClaudemdEngine claudemdEngine;

    /**
     * [IMP-T G15] AnalyticsTracker 遥测统一通道 · 对齐 CC logEvent('tengu_worktree_created')
     * （EnterWorktreeTool.ts:104 + setup.ts:246）。
     *
     * <p>null → no-op（未注入/测试场景不破坏既有调用）。
     */
    @Autowired(required = false)
    private AnalyticsTracker analyticsTracker;

    /** [IMP-T G15] 遥测通道注入（非 Spring 场景 / 测试）。 */
    public void setAnalyticsTracker(AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    public EnterWorktreeTool(WorktreeService worktreeService) {
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
        return "Create a new git worktree and switch the current session into it. "
                + "Use this when you need an isolated working directory for parallel work, "
                + "to test changes safely, or to work on multiple branches simultaneously. "
                + "The worktree lives under " + NexusaiPaths.getProjectDirName() + "/worktrees/<name>/. "
                + "Use ExitWorktree to leave (keep or remove the worktree).";
    }

    /**
     * 用户可见名称 · 对齐 CC EnterWorktreeTool.ts:68-70 {@code userFacingName()}
     * {@code return 'Creating worktree'}（UI 进行态提示）。
     */
    @Override
    public String userFacingName() {
        return "Creating worktree"; // CC EnterWorktreeTool.ts:69
    }

    /**
     * 工具提示词 · 对齐 CC {@code EnterWorktreeTool/prompt.ts:1-29}
     * {@code getEnterWorktreeToolPrompt()} 逐字移植（JSDoc 反引号 → Java 文本块反引号，
     * {@code \`} 转义保留）。
     */
    @Override
    public String prompt() {
        // [T3/#21] .nexusai → 动态 appName（决策 D1/D6）：worktree 目录指引随 appName 联动
        String prompt = """
            Use this tool ONLY when the user explicitly asks to work in a worktree. This tool creates an isolated git worktree and switches the current session into it.

            ## When to Use

            - The user explicitly says "worktree" (e.g., "start a worktree", "work in a worktree", "create a worktree", "use a worktree")

            ## When NOT to Use

            - The user asks to create a branch, switch branches, or work on a different branch — use git commands instead
            - The user asks to fix a bug or work on a feature — use normal git workflow unless they specifically mention worktrees
            - Never use this tool unless the user explicitly mentions "worktree"

            ## Requirements

            - Must be in a git repository, OR have WorktreeCreate/WorktreeRemove hooks configured in settings.json
            - Must not already be in a worktree

            ## Behavior

            - In a git repository: creates a new git worktree inside `.nexusai/worktrees/` with a new branch based on HEAD
            - Outside a git repository: delegates to WorktreeCreate/WorktreeRemove hooks for VCS-agnostic isolation
            - Switches the session's working directory to the new worktree
            - Use ExitWorktree to leave the worktree mid-session (keep or remove). On session exit, if still in the worktree, the user will be prompted to keep or remove it

            ## Parameters

            - `name` (optional): A name for the worktree. If not provided, a random name is generated.
            """.replace(".nexusai", NexusaiPaths.getProjectDirName());
        return prompt;
    }

    /**
     * [WF-B4] 恒启用 — Worktree 模式无条件对所有用户开放.
     *
     * <p>对齐 CC {@code utils/worktreeModeEnabled.ts:11} {@code return true}（恒真）;
     * 且 EnterWorktreeTool.ts/ExitWorktreeTool.ts 均无 {@code isEnabled} override,
     * 继承 {@code tool.ts:758} 基类默认 {@code isEnabled: () => true}.
     *
     * <p>删除了原先"env 变量或 sysprop 默认 false 的 opt-in"门控（Java 端自造，
     * CC 无此逻辑）.
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /** 是否延迟执行 · 对齐 CC EnterWorktreeTool.ts:71 shouldDefer: true（常量，与 input 无关）。 */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode name = props.putObject("name");
        name.put("type", "string");
        // [IMP-F1] slug 校验对齐 CC：总长 ≤ 64（MAX_WORKTREE_SLUG_LENGTH，worktree.ts:49），
        //   每 / 分隔段匹配 [a-zA-Z0-9._-]+（worktree.ts:48/66-87）。不再有每段 64 / 8 段上限。
        // [G22①] name 恢复可选（对齐 CC EnterWorktreeTool.ts:24-38 z.string().superRefine(...).optional()，
        //   :35-37 'A random name is generated if not provided'）——execute 缺省生成随机 word slug。
        name.put("description",
                "Optional name for the worktree. Each \"/\"-separated segment may contain only "
                        + "letters, digits, dots, underscores, and dashes; max 64 chars total. "
                        + "A random name is generated if not provided.");
        // [WF-3] originalCwd 工具输入参数 · 对齐 CC worktree.ts:712 createWorktreeForSession
        //   入口 {@code const originalCwd = getCwd()}（就地捕获进程 cwd）。Web 架构适配：
        //   服务端 user.dir ≠ 浏览器用户目录，故由前端在调用 Enter worktree 工具时传入
        //   用户真实目录；缺失回退 user.dir（无 DB 反查，DB 列已在 WF-5 删除）。
        ObjectNode originalCwd = props.putObject("originalCwd");
        originalCwd.put("type", "string");
        originalCwd.put("description",
                "Optional absolute path to the user's real working directory before entering the "
                        + "worktree. In a web deployment the server's working directory differs from "
                        + "the browser's, so the frontend passes it here. When omitted, the server's "
                        + "user.dir is used as fallback.");
        // [G22①] name 不再必填（对齐 CC z.string().optional()）——schema 无 required 列表
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        // [G22①] name 可选：缺省生成随机 word slug · 对齐 CC EnterWorktreeTool.ts:90
        //   {@code const slug = input.name ?? getPlanSlug()}（getPlanSlug → plans.ts:32-49
        //   generateWordSlug，adjective-verb-noun）。Java 无 plans word slug 词库（PlanProviderImpl
        //   sessionId-as-slug 登记偏差），此处内联 CC words.ts 紧凑子集（同 TeamCreateTool 先例，
        //   数据面非行为面）。
        String slug = readSlug(call);
        if (slug == null) {
            slug = generateWordSlug();
            if (log.isDebugEnabled()) {
                log.debug("[EnterWorktreeTool] name 未提供，生成随机 slug={}（对齐 CC EnterWorktreeTool.ts:90 input.name ?? getPlanSlug()）", slug);
            }
        }
        // [OD-2A-6] 会话 worktree 嵌套守卫 · 对齐 CC EnterWorktreeTool.ts:79-81
        //   `if (getCurrentWorktreeSession()) throw new Error('Already in a worktree session')`。
        // CC 真源（自验，不信注释）：CC 在 call() 入口检查 currentWorktreeSession，非空即抛
        //   "Already in a worktree session" —— 会话维度单 worktree，中途中止会话再 Enter 属
        //   非法路径（worktree 嵌套在 CC 中不存在，亦无 pre-worktree originalCwd 栈）。Java 等价
        //   = 经 WorktreeCwdTracker.getWorktreeSession(sessionKey)（对齐 CC getCurrentWorktreeSession
        //   worktree.ts:158-160）守卫，非空 → error 拒绝（对齐 CC throw 语义，ToolResult.error 承载）。
        // 锁定的 WHY（规则九）：SessionCwdHolder.originalCwd 是单槽（对齐 CC 单 STATE.originalCwd），
        // 若无此守卫，worktree 内再 Enter 会覆盖 originalCwd/WorktreeCwdTracker 单槽、退出后丢外层
        // worktree 路径（回落到 boundProject 主仓根而非外层 worktree）——与 CC 的「无嵌套」契约相悖。
        // 单层 worktree 退出恢复已对齐（ExitWorktreeTool 清 originalCwd 槽回落 boundProject =
        // pre-worktree 语义）；嵌套被本守卫显式拒绝，故无需 pre-worktree originalCwd 栈。
        String sessionKey = (ctx != null && ctx.sessionId() != null)
                ? ctx.sessionId() : null;
        if (sessionKey != null
                && com.nexusai.application.agent.worktree.WorktreeCwdTracker.getWorktreeSession(sessionKey) != null) {
            log.warn("[EnterWorktreeTool] 会话已在 worktree 中，拒绝嵌套 Enter（对齐 CC EnterWorktreeTool.ts:79-81 "
                    + "'Already in a worktree session'）sessionKey={}", sessionKey);
            return ToolResult.error(call.id(),
                    "Already in a worktree session: this session is already in a worktree. "
                            + "Exit the current worktree with ExitWorktree before entering another one.");
        }
        Path gitRoot = currentGitRoot();
        try {
            // CC worktree.ts:716-719 — validateWorktreeSlug 先行（hook 分支与 git 分支共用；
            //   hooks 收到的是原始 slug，分支名由 git 路径从 slug 推导）
            worktreeService.validateSlug(slug);
            // [gap1-originalCwd] CC worktree.ts:712 在 createWorktreeForSession 入口捕获
            //   originalCwd（进入前目录），hook/git 分支共用 → 此处提前捕获会话 originalCwd
            //   （sessionKey 已在 execute 入口 [OD-2A-6 守卫] 处计算，此处复用不重复声明）
            // gap1-originalCwd / WF-3: CC worktree.ts:712 在 createWorktreeForSession 入口捕获
            //   originalCwd（进入前目录），hook/git 分支共用 → 此处提前捕获会话 originalCwd。
            //   只取工具参数 originalCwd（前端传入），缺失静默跳过（Exit 回退 user.dir）。
            String originalCwdParam = readOriginalCwd(call);
            captureSessionOriginalCwd(ctx, sessionKey, originalCwdParam);
            // [IMP-HOOKS-S5 H4 / D-06b] 三源门控 → 双源门控：CC hasWorktreeCreateHook()
            //   （hooks.ts:4910-4920）仅查 settings 快照 + registered（session 源不参与，
            //   managedOnly 过滤 plugin hooks）。旧三源门控（WorktreeCreate 事件 + sessionKey）
            //   误报 → 仅 session hook 配置时空执行 throw（本应 git 回退）。
            if (hookRegistry != null && hookRegistry.hasWorktreeCreateHook()) {
                String hookPath = hookRegistry.executeWorktreeCreateHook(slug);
                Path worktreePath = Paths.get(hookPath);
                worktreeService.registerHookBasedWorktree(slug, worktreePath);
                // s18 P1-10: 设置 session cwd 覆盖 (wt_ctx) — 与 git 路径一致
                if (ctx != null && ctx.sessionId() != null) {
                    applySessionCwd(ctx, worktreePath);
                    // [SP-11] 会话级 worktree 绑定（对齐 CC EnterWorktreeTool.ts 写 currentWorktreeSession
                    //   worktree.ts:156-158；prompts.ts:675-681 isWorktree 消费 '!' 子弹）
                    com.nexusai.application.agent.agent.SessionCwdHolder.markWorktree(ctx.sessionId());
                }
                // [WF-2] 写 transcript worktree-state（对齐 CC EnterWorktreeTool.ts:97
                //   saveWorktreeState(worktreeSession)）· hookBased 无 git 分支 → branch=null
                persistWorktreeState(ctx, sessionKey, slug, worktreePath, null, true);
                // [G22③] 进入 worktree 后清 CWD 依赖缓存（对齐 CC :98-102
                //   clearSystemPromptSections + clearMemoryFileCaches + getPlansDirectory.cache.clear）
                clearWorktreeCaches(ctx);
                String message = "Created hook-based worktree at " + worktreePath;
                log.info("[EnterWorktreeTool] {}", message);
                // [IMP-T G15] 遥测 tengu_worktree_created（对齐 CC EnterWorktreeTool.ts:104）
                emitWorktreeCreated();
                return ToolResult.success(call.id(), buildHookOutput(worktreePath, message));
            }
            WorktreeCreateResult r = worktreeService.createWorktree(gitRoot, slug);
            String message = (r instanceof WorktreeCreateResult.Resumed)
                    ? "Resumed existing worktree at " + r.worktreePath()
                    : "Created new worktree at " + r.worktreePath();
            // s18 P1-10: 设置 session cwd 覆盖 (wt_ctx) — 对齐 CC utils/worktree.ts:156 currentWorktreeSession
            if (ctx != null && ctx.sessionId() != null) {
                applySessionCwd(ctx, r.worktreePath());
                // [SP-11] 会话级 worktree 绑定（对齐 CC EnterWorktreeTool.ts 写 currentWorktreeSession
                //   worktree.ts:156-158；prompts.ts:675-681 isWorktree 消费 '!' 子弹）
                com.nexusai.application.agent.agent.SessionCwdHolder.markWorktree(ctx.sessionId());
            }
            // [WF-2] 写 transcript worktree-state（对齐 CC EnterWorktreeTool.ts:97
            //   saveWorktreeState(worktreeSession)）· git 路径携带 branch
            persistWorktreeState(ctx, sessionKey, slug, r.worktreePath(), r.worktreeBranch(), false);
            // [G22③] 进入 worktree 后清 CWD 依赖缓存（对齐 CC :98-102）
            clearWorktreeCaches(ctx);
            log.info("[EnterWorktreeTool] {}", message);
            // [IMP-T G15] 遥测 tengu_worktree_created（对齐 CC EnterWorktreeTool.ts:104）
            emitWorktreeCreated();
            return ToolResult.success(call.id(), buildOutput(r, message));
        } catch (WorktreeService.WorktreeException e) {
            log.warn("[EnterWorktreeTool] failed: {}", e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.id(), e.getMessage());
        }
    }

    /**
     * [IMP-T G15] tengu_worktree_created 遥测 · 对齐 CC EnterWorktreeTool.ts:104
     * {@code logEvent('tengu_worktree_created', {mid_session: true})} +
     * setup.ts:246 {@code {tmux_enabled}}（CLI 启动创建场景）。Java 为会话中工具调用 →
     * {@code mid_session:true}；Java in-process 无 tmux 会话能力 → {@code tmux_enabled:false}
     * （对齐 CC tmuxEnabled 真实值，非 ant 构建等价）。
     */
    private void emitWorktreeCreated() {
        if (analyticsTracker == null) {
            return;
        }
        analyticsTracker.logEvent("tengu_worktree_created",
            Map.<String, Object>of("tmux_enabled", false, "mid_session", true));
        if (log.isDebugEnabled()) {
            log.debug("[EnterWorktreeTool] [IMP-T G15] 遥测 tengu_worktree_created（CC EnterWorktreeTool.ts:104）");
        }
    }

    /**
     * 设置 session cwd 覆盖 (wt_ctx) + [H14-FIX] watcher 通知 — 对齐 CC utils/worktree.ts:156
     * currentWorktreeSession + Shell.ts:409 onCwdChangedForHooks(cwd, newCwd)。
     *
     * <p>[Fix-R1] worktree 入口与 bash cd <b>共用 SessionCwdHolder</b>（对齐 CC 单 STATE.cwd：
     * EnterWorktreeTool.ts:95 setCwd(worktreePath) 与 Shell.ts:407 setCwd(newCwd) 均写同一
     * STATE.cwd，后者覆盖前者）。SessionCwdHolder 是 getCwd 的 sessionCwd 优先层；WorktreeCwdTracker
     * 仅记录 worktree 基路径供 ExitWorktreeTool 退出恢复，<b>不作</b> CwdResolution.getCwd() 优先层
     * （否则活跃 worktree 内 cd 后 getCwd() 返回 worktree 基路径而非 cd 子目录，违反 INV-2）。
     *
     * <p><b>[INV-3] originalCwd 重锚</b>：对齐 CC {@code EnterWorktreeTool.ts:94-96} 三连
     * {@code process.chdir(worktreePath) + setCwd(worktreePath) + setOriginalCwd(getCwd())}。CC 端
     * {@code setOriginalCwd(getCwd())} 时 getCwd() 已=worktreePath（setCwd 先写），故 originalCwd 重锚到
     * worktreePath。Java 端等价：cwd 槽 + originalCwd 槽<b>两槽同写 worktreePath</b>（对齐 CC
     * :95-96 setCwd+setOriginalCwd 均写 worktreePath），使 worktree 会话内 CLAUDE.md 扫描/存档锚
     * （{@code CwdResolution.getOriginalCwdLayer}）走 worktreePath 非 boundProject。两槽独立
     * （对齐 CC STATE.cwd/STATE.originalCwd 双字段），活跃 worktree 内 bash cd 仅写 cwd 槽，originalCwd 不受冲。
     */
    private void applySessionCwd(ToolUseContext ctx, Path worktreePath) {
        String sessionKey = ctx.sessionId();
        java.nio.file.Path oldCwd =
                com.nexusai.application.agent.worktree.WorktreeCwdTracker.getCwd(sessionKey);
        // [Fix-R1] worktree 入口写 SessionCwdHolder cwd 槽（与 bash cd 同槽，对齐 CC setCwd 均写 STATE.cwd）
        com.nexusai.application.agent.agent.SessionCwdHolder.set(
                sessionKey, worktreePath.toString());
        // [INV-3] worktree 入口写 SessionCwdHolder originalCwd 槽（对齐 CC :96 setOriginalCwd(getCwd())=worktreePath；
        //   两槽同写 worktreePath，originalCwd 层重锚，使 worktree 会话内 CLAUDE.md/存档走 worktreePath）
        com.nexusai.application.agent.agent.SessionCwdHolder.setOriginalCwd(
                sessionKey, worktreePath.toString());
        // WorktreeCwdTracker 仅记录基路径供退出恢复（不作 getCwd 优先层）
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.setCwd(sessionKey, worktreePath);
        if (fileChangedWatcher != null) {
            fileChangedWatcher.onCwdChangedForHooks(
                    oldCwd != null ? oldCwd.toString() : null,
                    worktreePath.toString());
        }
    }

    /**
     * [WF-2] 写 transcript worktree-state · 对齐 CC EnterWorktreeTool.ts:97
     * {@code saveWorktreeState(worktreeSession)}（sessionStorage.ts:2889-2916）。
     *
     * <p>worktreeSession JSON 形状对齐 CC {@code PersistedWorktreeSession}
     * （types/logs.ts:149-158，剔除了 Java 无对应物的 originalBranch/originalHeadCommit/
     * tmuxSessionName 三个 optional 字段）: {@code originalCwd/worktreePath/worktreeName/
     * worktreeBranch/sessionId/hookBased}。originalCwd 取
     * {@code com.nexusai.application.agent.worktree.WorktreeCwdTracker} 里
     * {@link #captureSessionOriginalCwd} 已捕获的前端传入目录（缺失回退 user.dir，与
     * Exit 侧 resolveOriginalCwd 回退一致）。
     *
     * <p>transcript 定位：workspaceDir = {@code AutoMemPaths.currentSessionProjectRoot()}
     * （会话 projectRoot 冻结 ThreadLocal，对齐 CommandHookExecutor.enrichBaseFields 的
     * transcript_path 解析），sessionId = ctx.sessionId() UUID 串。
     */
    private void persistWorktreeState(ToolUseContext ctx, String sessionKey, String slug,
                                      Path worktreePath, String worktreeBranch, boolean hookBased) {
        if (ctx == null || sessionKey == null) {
            return;
        }
        String originalCwd =
                com.nexusai.application.agent.worktree.WorktreeCwdTracker.getOriginalCwd(sessionKey);
        if (originalCwd == null || originalCwd.isBlank()) {
            // cwd-align-ext：兜底 = 会话 cwd（CC EnterWorktreeTool.ts:96 setOriginalCwd(getCwd()) 就地捕获）；
            //   无 sessionId 回落 user.dir（方案 1，零行为变化）。
            originalCwd = CwdResolution.getCwd(RequestContext.sessionId());
            if (originalCwd == null || originalCwd.isBlank()) {
                originalCwd = System.getProperty("user.dir", ".");
            }
        }
        ObjectNode session = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        session.put("originalCwd", originalCwd);
        session.put("worktreePath", worktreePath.toString());
        session.put("worktreeName", slug);
        if (worktreeBranch != null) {
            session.put("worktreeBranch", worktreeBranch);
        }
        session.put("sessionId", sessionKey);
        session.put("hookBased", hookBased);
        java.nio.file.Path workspaceDir = java.nio.file.Paths.get(
            com.nexusai.application.agent.memory.AutoMemPaths.currentSessionProjectRoot());
        com.nexusai.application.agent.tool.SessionStorage.writeWorktreeState(
            workspaceDir, sessionKey, session);
        // [RESIDUAL-FIX 残留 2] 同步写 WorktreeCwdTracker 完整 worktree 会话对象
        //   （对齐 CC EnterWorktreeTool.ts:97 saveWorktreeState(worktreeSession) 同时更新
        //   currentWorktreeSession，Java 端 session 维度落 WorktreeCwdTracker.sessionWorktree）。
        com.nexusai.application.agent.worktree.WorktreeCwdTracker.setWorktreeSession(sessionKey,
            new com.nexusai.application.agent.worktree.WorktreeCwdTracker.WorktreeSession(
                worktreePath.toString(), worktreeBranch, slug, hookBased, sessionKey));
        log.info("[EnterWorktreeTool] 写 transcript worktree-state: session={} worktreePath={} "
                + "worktreeName={} hookBased={}", sessionKey, worktreePath, slug, hookBased);
    }

    /**
     * [G22③] 进入 worktree 后清 CWD 依赖缓存 · 对齐 CC EnterWorktreeTool.ts:98-102：
     * <pre>
     * clearSystemPromptSections()      // 清会话级 section 缓存，env_info_simple 以 worktree 上下文重算
     * clearMemoryFileCaches()          // claudemd.ts:1119-1122 清 getMemoryFiles memoize
     * getPlansDirectory.cache.clear?.()// plans 目录 memoize（Java N/A：PlanProviderImpl 每会话构造确定，
     *                                   //   无共享 memoize cache 可清）
     * </pre>
     *
     * <p>Java 等价：
     * <ul>
     *   <li>{@code clearSystemPromptSections()} → {@link SessionAgentStateRegistry#get(UUID)} 会话
     *       AgentState → {@code systemPromptSectionCache().clear()}（对齐 systemPromptSections.ts:65-68，
     *       同 PostCompactCleanup.clearActiveSessionSystemPromptSections 接线模式）。</li>
     *   <li>{@code clearMemoryFileCaches()} → {@link ClaudemdEngine#clearMemoryFileCaches()}（对齐
     *       claudemd.ts:1119-1122）。</li>
     *   <li>{@code getPlansDirectory.cache.clear?.()} → Java 无等价（PlanProviderImpl 每会话构造，
     *       plans 目录非 cwd 依赖）——登记 N/A。</li>
     * </ul>
     *
     * <p>null 守卫：registry / claudemdEngine 未注入（测试 / 非 Spring）→ 跳过（debug 记录），
     * 不阻断 worktree 进入。
     *
     * @param ctx 工具调用上下文（用于解析当前会话 UUID；null → 无法定位会话级缓存，仅清全局）
     */
    private void clearWorktreeCaches(ToolUseContext ctx) {
        // [session-id-short] ctx.sessionId() 已 String（short）
        String sessionId = (ctx != null) ? ctx.sessionId() : null;
        if (sessionAgentStateRegistry != null) {
            if (sessionId != null) {
                com.nexusai.application.agent.AgentState state = sessionAgentStateRegistry.get(sessionId);
                if (state != null) {
                    state.systemPromptSectionCache().clear();
                    log.info("[EnterWorktreeTool] 清空会话 {} 的 system prompt section 缓存（对齐 CC EnterWorktreeTool.ts:99 clearSystemPromptSections）",
                        sessionId);
                } else if (log.isDebugEnabled()) {
                    log.debug("[EnterWorktreeTool] clearSystemPromptSections 跳过：会话 {} 无活跃 AgentState（注册表未注册）", sessionId);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("[EnterWorktreeTool] clearSystemPromptSections 跳过：无 sessionId（无法定位会话级缓存）");
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[EnterWorktreeTool] clearSystemPromptSections 跳过：SessionAgentStateRegistry 未接线（测试/非 Spring）");
        }
        if (claudemdEngine != null) {
            claudemdEngine.clearMemoryFileCaches();
            log.info("[EnterWorktreeTool] 清空 claudemd getMemoryFiles 缓存（对齐 CC EnterWorktreeTool.ts:101 clearMemoryFileCaches）");
        } else if (log.isDebugEnabled()) {
            log.debug("[EnterWorktreeTool] clearMemoryFileCaches 跳过：ClaudemdEngine 未接线（测试/非 Spring）");
        }
        // getPlansDirectory.cache.clear?.() → Java N/A（PlanProviderImpl 每会话构造，plans 目录非 cwd 依赖）
    }

    /**
     * gap1-originalCwd / WF-3: 捕获会话 originalCwd（进入 worktree 前用户真实目录）存入
     * WorktreeCwdTracker，供 ExitWorktreeTool 退出时回显。CC original: worktree.ts:712
     * 入口 getCwd() 就地捕获（不从任何地方读回）。
     *
     * <p>[WF-3] 只取工具参数 {@code toolOriginalCwd}（前端调用 Enter worktree 工具时传入，
     * 对齐 CC 就地 getCwd() 语义）——有则落值；未传则静默跳过（Enter 不存，Exit 回退
     * user.dir）。[WF-5] 原 DB 反查兜底链路已删除（对齐 CC：持久化走 transcript
     * worktree-state，DB sessions.original_cwd 列已 DROP）。
     */
    private void captureSessionOriginalCwd(ToolUseContext ctx, String sessionKey, String toolOriginalCwd) {
        if (ctx == null || ctx.sessionId() == null || sessionKey == null) {
            return;
        }
        // [WF-3] 工具参数 originalCwd（前端传入，对齐 CC worktree.ts:712 getCwd() 就地捕获）；
        //   未传则静默跳过（Exit 回退 user.dir）
        if (toolOriginalCwd != null && !toolOriginalCwd.isBlank()) {
            com.nexusai.application.agent.worktree.WorktreeCwdTracker.setOriginalCwd(sessionKey, toolOriginalCwd);
            log.info("[EnterWorktreeTool] 捕获 originalCwd（工具参数）: session={} originalCwd={}",
                    sessionKey, toolOriginalCwd);
        } else if (log.isDebugEnabled()) {
            log.debug("[EnterWorktreeTool] 捕获 originalCwd 跳过: 未传工具参数（Exit 回退 user.dir）session={}", sessionKey);
        }
    }

    private String readSlug(ToolUseBlock call) {
        JsonNode input = call.input();
        if (input == null || !input.has("name")) {
            return null;
        }
        return input.get("name").asText();
    }

    /**
     * [WF-3] 读取工具输入参数 originalCwd（可选）· 对齐 CC worktree.ts:712 getCwd() 就地
     * 捕获语义的 Web 适配：前端调用 Enter worktree 工具时传入用户真实目录。null = 未传
     * （Exit 侧回退 user.dir）。
     */
    private String readOriginalCwd(ToolUseBlock call) {
        JsonNode input = call.input();
        if (input == null || !input.has("originalCwd") || input.get("originalCwd").isNull()) {
            return null;
        }
        String originalCwd = input.get("originalCwd").asText();
        return (originalCwd == null || originalCwd.isBlank()) ? null : originalCwd;
    }

    /**
     * 当前 git 仓库根目录 — 对齐 CC findCanonicalGitRoot(getCwd())（EnterWorktreeTool.ts:84）。
     * cwd-align-ext：user.dir 硬编码 → 会话 cwd + 复用 {@link AutoMemPaths#findCanonicalGitRoot}
     * （worktree/submodule .git 文件解析）；无 sessionId 回落 user.dir（方案 1，零行为变化）。
     */
    private Path currentGitRoot() {
        String cwd = CwdResolution.getCwd(RequestContext.sessionId());
        if (cwd == null || cwd.isBlank()) {
            cwd = System.getProperty("user.dir", ".");
        }
        String canonical = AutoMemPaths.findCanonicalGitRoot(cwd);
        return Paths.get(canonical != null && !canonical.isBlank() ? canonical : cwd);
    }

    private String buildOutput(WorktreeCreateResult r, String message) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("worktreePath", r.worktreePath().toString());
        map.put("worktreeBranch", r.worktreeBranch());
        map.put("message", message);
        // 序列化为 JSON 字符串 (CC 工具返回 JSON object)
        ObjectNode node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        map.forEach(node::put);
        return node.toString();
    }

    /**
     * hook-based 输出 — 对齐 CC outputSchema (EnterWorktreeTool.ts:40-44)：
     * {@code worktreeBranch: z.string().optional()} — hookBased 无 git 分支，省略该键。
     */
    private String buildHookOutput(Path worktreePath, String message) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("worktreePath", worktreePath.toString());
        map.put("message", message);
        ObjectNode node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        map.forEach(node::put);
        return node.toString();
    }

    /**
     * tool_result 块 · 对齐 CC EnterWorktreeTool.ts:120-126
     * {@code mapToolResultToToolResultBlockParam({message}, toolUseID)} —— 仅回传 message 作
     * content（worktreePath/worktreeBranch 走 data 结构化通道，不进 LLM）。data 为 JSON 字符串
     * （{@link #buildOutput} / {@link #buildHookOutput} 产物），此处解析抽 message；解析失败
     * 回退原 data（fail-loud 不吞）。isError / 非 ToolResult → null（默认渲染器回退显错）。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (!(result instanceof ToolResult<?> tr) || isError) {
            if (log.isDebugEnabled()) {
                log.debug("[EnterWorktreeTool] mapToToolResultBlockParam 跳过: isError 或非 ToolResult（fail-loud 路径回退默认渲染器）");
            }
            return null;
        }
        Object data = tr.data();
        String content = (data != null) ? extractMessage(String.valueOf(data)) : "";
        if (log.isDebugEnabled()) {
            log.debug("[EnterWorktreeTool] mapToToolResultBlockParam 生成 tool_result content长度={}（CC EnterWorktreeTool.ts:120-126 仅 message 作 content）",
                content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    /** 从 JSON 字符串 data 抽 "message" 键（CC :120-126 {@code ({message}) 解构} 等价）；解析失败回退原串。 */
    private static String extractMessage(String dataJson) {
        try {
            JsonNode node = JSON.readTree(dataJson);
            if (node != null && node.isObject() && node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception e) {
            if (LoggerFactory.getLogger(EnterWorktreeTool.class).isDebugEnabled()) {
                LoggerFactory.getLogger(EnterWorktreeTool.class).debug(
                    "[EnterWorktreeTool] extractMessage 解析失败，回退原 data: {}", e.getMessage());
            }
        }
        return dataJson;
    }

    /**
     * [G22①] 随机 word slug · 对齐 CC {@code words.ts:783-791 generateWordSlug}：
     * {@code `${adjective}-${verb}-${noun}`}（如 "gleaming-brewing-phoenix"）。
     * 词库为 CC words.ts 词条的紧凑子集（同 TeamCreateTool.java:280-319 先例，数据面非行为面；
     * 随机 worktree 名无需逐词一致）。
     */
    private static String generateWordSlug() {
        String adjective = ADJECTIVES[RANDOM.nextInt(ADJECTIVES.length)];
        String verb = VERBS[RANDOM.nextInt(VERBS.length)];
        String noun = NOUNS[RANDOM.nextInt(NOUNS.length)];
        return adjective + "-" + verb + "-" + noun;
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    // ── CC words.ts 词库子集（ADJECTIVES 219 取 44 / NOUNS 409 取 46 / VERBS 109 取 55，等距抽样）──
    private static final String[] ADJECTIVES = {
        "abundant", "clever", "deep", "fancy", "graceful", "joyful", "lucky", "merry",
        "playful", "quirky", "silly", "snuggly", "sprightly", "tender", "valiant",
        "whimsical", "zany", "buzzing", "crystalline", "ethereal", "fluttering",
        "glimmering", "groovy", "jaunty", "mossy", "purring", "shimmying", "ticklish",
        "wobbly", "agile", "compiled", "curried", "eager", "expressive", "hashed",
        "inherited", "linked", "nested", "piped", "refactored", "scalable", "staged",
        "synchronous", "unified",
    };

    private static final String[] VERBS = {
        "baking", "booping", "brewing", "chasing", "coalescing", "cooking", "crunching",
        "dancing", "discovering", "dreaming", "enchanting", "finding", "fluttering",
        "forging", "gathering", "gliding", "growing", "herding", "hopping", "humming",
        "inventing", "juggling", "kindling", "launching", "mapping", "meandering",
        "moseying", "napping", "noodling", "painting", "petting", "pondering", "prancing",
        "puzzling", "riding", "rolling", "scribbling", "shimmying", "skipping", "snacking",
        "snuggling", "sparking", "splashing", "squishing", "stirring", "swimming",
        "tickling", "toasting", "twirling", "wandering", "weaving", "wibbling", "wishing",
        "wondering", "zooming",
    };

    private static final String[] NOUNS = {
        "aurora", "clover", "dusk", "forest", "island", "moonbeam", "planet", "shore",
        "stream", "valley", "bear", "crane", "falcon", "hedgehog", "llama", "otter",
        "platypus", "raven", "squid", "whale", "beacon", "castle", "dream", "globe",
        "kettle", "map", "noodle", "pillow", "pumpkin", "scroll", "swing", "treasure",
        "whistle", "bachman", "cerf", "cray", "feigenbaum", "hellman", "kahan", "lecun",
        "minsky", "pascal", "rivest", "stallman", "thompson", "wilkinson",
    };
}