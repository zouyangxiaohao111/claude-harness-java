package com.nexusai.application.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ToolNameConstants · 对齐 CC tools/*Tool/constants.ts (聚合多个 2-LOC constants file).
 *
 * <p>L1 语义: 每个 tool 的 {@code <TOOL_NAME> = '<Name>'} 全局命名常量集 — 跨 tool
 * 派单 / permission rules / hooks / wire 序列化的标识符。
 * <ul>
 *   <li>{@code AGENT_TOOL_NAME='Agent'} + LEGACY_AGENT_TOOL_NAME='Task' 在 agent/AgentToolConstants.java</li>
 *   <li>本类聚合其余 tool names: SendMessage / Skill / Task* / Team* / TodoWrite / 31+ 通用 tool</li>
 * </ul>
 *
 * <p><b>[R32-#13]</b> 批次 3 补 21 个常量: Bash / PowerShell / Read / Edit / Write /
 * Glob / Grep / NotebookEdit / WebFetch / WebSearch / EnterPlanMode / ExitPlanMode /
 * EnterWorktree / ExitWorktree / AskUserQuestion / Brief / ListMcpResources /
 * ReadMcpResource / SyntheticOutput / ToolSearch / LSP / Config / REPL / Workflow /
 * Cron* (3) / Monitor / Sleep. <b>[R32-b7a-3]</b> 补 5 个 MCP-dependent stub: WebBrowser /
 * ListPeers / SendUserFile / PushNotification / SubscribePR. 共 37 个 unique tool name.
 *
 * <p>L2 契约 (Release Gate):
 * <ul>
 *   <li><b>A1</b>: 37 public static final String 常量</li>
 *   <li><b>A2 Golden Trace</b>: 字段值严格对齐 CC source files</li>
 *   <li><b>A3 不可变</b>: 全部 {@code final} 编译期常量;Set 不可变</li>
 *   <li><b>A4 边界</b>: 常量非空（[IMP-C4 DC-A1-03] ALL_NAMES 聚合集已删）</li>
 *   <li><b>A5 业务场景</b>: tool dispatcher 路由 by NAME;permission rules 引用 NAME</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS {@code export const X = 'Y'} →
 * Java {@code public static final String};聚合多个 2-LOC constants file →
 * 单个聚合类便于管理。
 */
public final class ToolNameConstants {

    private static final Logger log = LoggerFactory.getLogger(ToolNameConstants.class);

    // ── Shell tools (对齐 CC utils/shell/shellToolUtils.ts) ──
    public static final String BASH_TOOL_NAME = "Bash";
    public static final String POWER_SHELL_TOOL_NAME = "PowerShell";

    // ── File tools (对齐 CC FileReadTool / FileEditTool / FileWriteTool) ──
    public static final String FILE_READ_TOOL_NAME = "Read";
    public static final String FILE_EDIT_TOOL_NAME = "Edit";
    public static final String FILE_WRITE_TOOL_NAME = "Write";
    public static final String NOTEBOOK_EDIT_TOOL_NAME = "NotebookEdit";

    // ── Search tools (对齐 CC GlobTool / GrepTool) ──
    public static final String GLOB_TOOL_NAME = "Glob";
    public static final String GREP_TOOL_NAME = "Grep";

    // ── Web tools (对齐 CC WebFetchTool / WebSearchTool) ──
    public static final String WEB_FETCH_TOOL_NAME = "WebFetch";
    public static final String WEB_SEARCH_TOOL_NAME = "WebSearch";

    // ── Plan mode (对齐 CC EnterPlanModeTool / ExitPlanModeTool) ──
    public static final String ENTER_PLAN_MODE_TOOL_NAME = "EnterPlanMode";
    public static final String EXIT_PLAN_MODE_TOOL_NAME = "ExitPlanMode";

    // ── Worktree (对齐 CC EnterWorktreeTool / ExitWorktreeTool) ──
    public static final String ENTER_WORKTREE_TOOL_NAME = "EnterWorktree";
    public static final String EXIT_WORKTREE_TOOL_NAME = "ExitWorktree";

    // ── User interaction (对齐 CC AskUserQuestionTool) ──
    public static final String ASK_USER_QUESTION_TOOL_NAME = "AskUserQuestion";

    // ── Brief (对齐 CC BriefTool) ──
    /**
     * CC original: BRIEF_TOOL_NAME (BriefTool/prompt.ts:1) = 'SendUserMessage'。
     * [IMP-H3] 由旧值 'Brief' 改为 CC 真名 —— BriefTool.name() 重写为 'SendUserMessage'，
     * 旧名 'Brief' 走 aliases()（LEGACY_BRIEF_TOOL_NAME）回退（Tool.ts:346-360 toolMatchesName）。
     */
    public static final String BRIEF_TOOL_NAME = "SendUserMessage";

    // ── MCP resource tools (对齐 CC ListMcpResourcesTool / ReadMcpResourceTool) ──
    /**
     * CC original: LIST_MCP_RESOURCES_TOOL_NAME (ListMcpResourcesTool/prompt.ts:1) =
     * 'ListMcpResourcesTool' — 含 Tool 后缀，与 {@code ListMcpResourcesTool.name} 一致，
     * 使 SPECIAL_TOOLS 过滤（toOpenAiToolsArray:410-415 / getTools:518-520）按真名命中。
     * [D3 修正] 旧值 'ListMcpResources'（缺后缀）与 D2 改名的真工具名不匹配 → 过滤失效。
     */
    public static final String LIST_MCP_RESOURCES_TOOL_NAME = "ListMcpResourcesTool";
    /**
     * CC original: name (ReadMcpResourceTool.ts:60) = 'ReadMcpResourceTool' — 含 Tool 后缀，
     * 与 {@code ReadMcpResourceTool.name} 一致。同上 D3 修正。
     */
    public static final String READ_MCP_RESOURCE_TOOL_NAME = "ReadMcpResourceTool";
    /**
     * CC SyntheticOutputTool.ts:20 {@code SYNTHETIC_OUTPUT_TOOL_NAME = 'StructuredOutput'} —
     * CC 常量名是 SYNTHETIC_OUTPUT_TOOL_NAME, 值是真名 'StructuredOutput'.
     *
     * <p>[D session] 修正: 原 Java 端该常量值误写为 'SyntheticOutput' (R32 早期旧名),
     * 与 CC 真值不符, 导致 {@link com.nexusai.application.agent.tool.AgentToolUtils#ASYNC_AGENT_ALLOWED_TOOLS}
     * 白名单永远匹配不到真实工具. 已修正为 CC 真值; 同值重复常量
     * {@code STRUCTURED_OUTPUT_TOOL_NAME} 已删除 (Pattern #12 命名单一权威).
     */
    public static final String SYNTHETIC_OUTPUT_TOOL_NAME = "StructuredOutput";

    // ── CC 真源工具名（G11 改名 snake_case→PascalCase · 原注册桩 WFI-R1 失效注释已清）──
    /** CC original: WEB_BROWSER_TOOL_NAME（WebBrowserTool.ts:6）= 'WebBrowser' · feature('WEB_BROWSER_TOOL') tools.ts:117/217。 */
    public static final String WEB_BROWSER_TOOL_NAME = "WebBrowser";
    /** CC original: LIST_PEERS_TOOL_NAME（ListPeersTool.ts:6）= 'ListPeers' · feature('UDS_INBOX') tools.ts:126/227。 */
    public static final String LIST_PEERS_TOOL_NAME = "ListPeers";
    /** CC original: SEND_USER_FILE_TOOL_NAME（SendUserFileTool/prompt.ts）= 'SendUserFile' ·
     *  CC 注册 feature('KAIROS') tools.ts:42-43/239 —— Java 侧用户拍板默认启用（不 KAIROS 门控，2026-08-23）。 */
    public static final String SEND_USER_FILE_TOOL_NAME = "SendUserFile";
    /** CC original: PUSH_NOTIFICATION_TOOL_NAME（PushNotificationTool.ts:9）= 'PushNotification' · feature('KAIROS') || feature('KAIROS_PUSH_NOTIFICATION') tools.ts:45-48/240。 */
    public static final String PUSH_NOTIFICATION_TOOL_NAME = "PushNotification";
    /** CC original: SUBSCRIBE_PR_TOOL_NAME（SubscribePRTool.ts:6）= 'SubscribePR' · feature('KAIROS_GITHUB_WEBHOOKS') tools.ts:50-51/241。 */
    public static final String SUBSCRIBE_PR_TOOL_NAME = "SubscribePR";

    // ── Tool discovery (对齐 CC ToolSearchTool) ──
    public static final String TOOL_SEARCH_TOOL_NAME = "ToolSearch";

    /**
     * Tool search beta header · 1P（Claude API / Foundry）。
     * CC original: TOOL_SEARCH_BETA_HEADER_1P (Open-ClaudeCode/src/constants/betas.ts:13)
     * = 'advanced-tool-use-2025-11-20'。defer_loading 发射时随请求 anthropic-beta 头推送
     * （CC claude.ts:1174-1177「required for defer_loading to be accepted」）。
     * 3P 变体 TOOL_SEARCH_BETA_HEADER_3P='tool-search-tool-2025-10-19'（betas.ts:14）
     * 仅供 Vertex/Bedrock，Java 无 API-provider 抽象 → N/A，不引入。
     */
    public static final String TOOL_SEARCH_BETA_HEADER_1P = "advanced-tool-use-2025-11-20";

    // ── LSP (对齐 CC LSPTool) ──
    public static final String LSP_TOOL_NAME = "LSP";

    // ── Ant-only tools (对齐 CC ConfigTool / TungstenTool, USER_TYPE='ant') ──
    public static final String CONFIG_TOOL_NAME = "Config";
    /** CC original: name（TungstenTool.js:2）= 'TungstenTool'（G11 改名 Tungsten→TungstenTool）。 */
    public static final String TUNGSTEN_TOOL_NAME = "TungstenTool";
    public static final String SUGGEST_BACKGROUND_PR_TOOL_NAME = "SuggestBackgroundPR";

    // ── REPL (对齐 CC REPLTool) ──
    public static final String REPL_TOOL_NAME = "REPL";

    /**
     * CC REPLTool/constants.ts:37-46 {@code REPL_ONLY_TOOLS} — REPL 模式开启时,
     * 这些基础工具对 LLM 直接调用隐藏 (强制走 REPL VM 上下文批量操作).
     *
     * <p>[D session] 补全常量集 (V2/D.md 声称的旧 5 集 ALL_ALLOWED_TOOLS 等在当前 CC
     * 源码不存在 — Pattern #9 实证; 真源是 constants/tools.ts 5 集 + 本 REPL 集).
     * Java 端当前无 REPL 工具 (ant-only), 本集合保留为 CC 结构对齐 +
     * {@link com.nexusai.application.agent.tool.ToolRegistry#getTools} 的 REPL 分支预留.
     */
    public static final Set<String> REPL_ONLY_TOOLS = Set.of(
        FILE_READ_TOOL_NAME,    // Read
        FILE_WRITE_TOOL_NAME,   // Write
        FILE_EDIT_TOOL_NAME,    // Edit
        GLOB_TOOL_NAME,         // Glob
        GREP_TOOL_NAME,         // Grep
        BASH_TOOL_NAME,         // Bash
        NOTEBOOK_EDIT_TOOL_NAME,// NotebookEdit
        com.nexusai.application.agent.tool.AgentToolConstants.AGENT_TOOL_NAME // Agent
    );

    // ── Workflow (对齐 CC WorkflowTool) ──
    public static final String WORKFLOW_TOOL_NAME = "Workflow";

    // ── [B5] CC feature/env 门控 7 桩工具名（对齐 CC tools.ts 门控段，见各 Javadoc）──
    // [G30⑫] OVERFLOW_TEST_TOOL_NAME 已删除 — CC 无功能 Tool（OverflowTestTool 类 + 注册 + AutoModeAllowlist 同步清理）。
    /** CC CtxInspectTool 工具名 · feature('CONTEXT_COLLAPSE') 门 · CC tools.ts:110/222 + CtxInspectTool.ts:12（G32③ 修正：CC 真源已就位）。 */
    public static final String CTX_INSPECT_TOOL_NAME = "CtxInspect";
    /** CC TerminalCaptureTool 工具名 · feature('TERMINAL_PANEL') 门 · CC tools.ts:113/223 + TerminalCaptureTool/prompt.ts:1（G32③ 修正：CC 真源已就位）。 */
    public static final String TERMINAL_CAPTURE_TOOL_NAME = "TerminalCapture";
    /** CC VerifyPlanExecutionTool 工具名 · env CLAUDE_CODE_VERIFY_PLAN==='true' 门 · CC tools.ts:91/231 + VerifyPlanExecutionTool/constants.ts:1（G32③ 修正：CC 真源已就位）。 */
    public static final String VERIFY_PLAN_EXECUTION_TOOL_NAME = "VerifyPlanExecution";
    /** CC SnipTool 工具名 · feature('HISTORY_SNIP') 门 · CC tools.ts:123/243 + SnipTool/prompt.ts:1（G32③ 修正：CC 真源已就位）。 */
    public static final String SNIP_TOOL_NAME = "Snip";
    /** CC TestingPermissionTool 工具名 · env NODE_ENV==='test' 门 · CC tools.ts:244 + TestingPermissionTool.tsx:13 NAME='TestingPermission'。 */
    public static final String TESTING_PERMISSION_TOOL_NAME = "TestingPermission";

    // ── Cron (对齐 CC ScheduleCronTool / CronCreate / CronDelete / CronList) ──
    public static final String CRON_CREATE_TOOL_NAME = "CronCreate";
    public static final String CRON_DELETE_TOOL_NAME = "CronDelete";
    public static final String CRON_LIST_TOOL_NAME = "CronList";

    // ── Misc (对齐 CC MonitorTool / SleepTool) ──
    public static final String MONITOR_TOOL_NAME = "Monitor";
    public static final String SLEEP_TOOL_NAME = "Sleep";

    // ── Vision analyze (自建工具 · 代理视觉模型 · CC 无对应) ──
    /**
     * 视觉分析工具名 · 自建工具（任务 VisionAnalyze）。
     *
     * <p><b>CC 无对应</b>：CC 多模态为<b>模型内建能力</b>（FileReadTool 读图 → 模型直接
     * 视觉理解，prompt.ts:40 "Claude Code is a multimodal LLM"），不存在「多模态工具」。
     * Java 后端主模型不支持多模态（type=multimodal/vision 缺失）时，主模型收到
     * 「多模态提示（含缓存 id）」→ 调用本工具 → 读 ImageAttachmentStore 缓存 → 图片+prompt
     * 发给独立视觉模型（settings.multimodalModelName）→ 返回纯文本（图片以占位符表示）。
     * 旧名 multimodal_attachment 经 VisionAnalyzeTool.aliases() 保留历史 transcript 派发。
     */
    public static final String VISION_ANALYZE_TOOL_NAME = "vision_analyze";

    // ── Subagent tools (对齐 CC SendMessageTool / SkillTool / Task* / Team* / TodoWrite) ──
    public static final String SEND_MESSAGE_TOOL_NAME = "SendMessage";
    public static final String SKILL_TOOL_NAME = "Skill";

    public static final String TASK_CREATE_TOOL_NAME = "TaskCreate";
    public static final String TASK_GET_TOOL_NAME = "TaskGet";
    public static final String TASK_LIST_TOOL_NAME = "TaskList";
    public static final String TASK_OUTPUT_TOOL_NAME = "TaskOutput";
    public static final String TASK_UPDATE_TOOL_NAME = "TaskUpdate";
    public static final String TASK_STOP_TOOL_NAME = "TaskStop";

    public static final String TEAM_CREATE_TOOL_NAME = "TeamCreate";
    public static final String TEAM_DELETE_TOOL_NAME = "TeamDelete";

    public static final String TODO_WRITE_TOOL_NAME = "TodoWrite";

    /**
     * [R32-#12] CC specialTools 集合的 Java 端对齐 · 3 个内部 dispatch 工具,
     * 不暴露给 LLM schema (在 {@link ToolRegistry#all} / {@link ToolRegistry#toOpenAiToolsArray}
     * 中被过滤).
     *
     * <p>[D3 修正] 集合值 = CC <b>真名</b>（tools.ts:302-304 用 {@code ListMcpResourcesTool.name}
     * 等工具真名构造集合）。旧值 'ListMcpResources'/'ReadMcpResource'（缺 Tool 后缀）与 D2
     * 改名的真工具名不匹配 → toOpenAiToolsArray/getTools 的 SPECIAL_TOOLS.contains(name()) 过滤
     * 失效，内部工具泄漏进 LLM schema。
     *
     * <p>CC tools.ts:302-304 定义（本 session grep 自验）:
     * <pre>
     * const specialTools = new Set([
     *   ListMcpResourcesTool.name,  // 'ListMcpResourcesTool'
     *   ReadMcpResourceTool.name,   // 'ReadMcpResourceTool'
     *   SYNTHETIC_OUTPUT_TOOL_NAME, // 'StructuredOutput'
     * ])
     * </pre>
     */
    public static final Set<String> SPECIAL_TOOLS = Set.of(
        LIST_MCP_RESOURCES_TOOL_NAME,
        READ_MCP_RESOURCE_TOOL_NAME,
        SYNTHETIC_OUTPUT_TOOL_NAME
    );

    /**
     * [B6] ALL_AGENT_DISALLOWED_TOOLS · 单一权威 · 对齐 CC
     * {@code Open-ClaudeCode/src/constants/tools.ts:36-46}（DEL-WFB-03 双定义合并）。
     *
     * <p>CC 真源（B6 实读 tools.ts:36-46，非抄报告）：
     * <pre>
     * export const ALL_AGENT_DISALLOWED_TOOLS = new Set([
     *   TASK_OUTPUT_TOOL_NAME,
     *   EXIT_PLAN_MODE_V2_TOOL_NAME,
     *   ENTER_PLAN_MODE_TOOL_NAME,
     *   ...(process.env.USER_TYPE === 'ant' ? [] : [AGENT_TOOL_NAME]),
     *   ASK_USER_QUESTION_TOOL_NAME,
     *   TASK_STOP_TOOL_NAME,
     *   ...(feature('WORKFLOW_SCRIPTS') ? [WORKFLOW_TOOL_NAME] : []),
     * ])
     * </pre>
     *
     * <p>WHY: CC filterToolsForAgent（agentToolUtils.ts:94-96）hook agent 工具集 = 父工具过滤掉
     * 此集合 + StructuredOutput。防止 hook agent 递归 spawn subagent / 进入 plan mode /
     * 询问用户 / 递归执行 workflow。Java 端 ExecAgentHook:383 与 AgentToolUtils#filterToolsForAgent
     * 均以此集合过滤。
     *
     * <p>条件项镜像 CC（B6）：
     * <ul>
     *   <li>{@code USER_TYPE === 'ant'} → 放行 Agent（tools.ts:40，ant 启用嵌套 agent）</li>
     *   <li>{@code feature('WORKFLOW_SCRIPTS')} → 拦截 Workflow（tools.ts:43，防子 agent 递归 workflow）</li>
     * </ul>
     * 集合内容由 {@link #buildAllAgentDisallowed(boolean, boolean)} 静态工厂构建（条件分支可测试）。
     *
     * <p>静态集 class-load 固化：常量在类加载时以保守默认分支冻结
     * {@code buildAllAgentDisallowed(false, true)}（对齐 CC bundle 常量折叠语义，可接受）。
     * 默认含 Workflow 为防御性不变量：WORKFLOW_SCRIPTS 开启、WorkflowTool 注册时 hook agent
     * 仍被拦截（WorkflowTool.java:26-27 既有设计）；flag 关闭时该条目为死重（工具未注册），
     * 无运行时影响——与 CC 全状态可观察行为等价。
     */
    public static final Set<String> ALL_AGENT_DISALLOWED_TOOLS =
        buildAllAgentDisallowed(false, true);

    /**
     * 镜像 CC constants/tools.ts:36-46 条件集合的静态工厂（DEL-WFB-03 单一权威）。
     *
     * @param isAntUser         {@code process.env.USER_TYPE === 'ant'}（tools.ts:40）· true 放行 Agent
     * @param isWorkflowScripts {@code feature('WORKFLOW_SCRIPTS')}（tools.ts:43）· true 拦截 Workflow
     * @return 不可变有序集合，元素顺序对齐 CC 源码字面量
     */
    public static Set<String> buildAllAgentDisallowed(boolean isAntUser, boolean isWorkflowScripts) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(TASK_OUTPUT_TOOL_NAME);            // TaskOutput — 防递归
        set.add(EXIT_PLAN_MODE_TOOL_NAME);         // ExitPlanMode — 主线程抽象
        set.add(ENTER_PLAN_MODE_TOOL_NAME);        // EnterPlanMode — 主线程抽象
        if (!isAntUser) {
            set.add(AgentToolConstants.AGENT_TOOL_NAME);   // 非 ant 才拦截 Agent（tools.ts:40）
        }
        set.add(ASK_USER_QUESTION_TOOL_NAME);      // AskUserQuestion — 需主线程交互
        set.add(TASK_STOP_TOOL_NAME);              // TaskStop — 需主线程 task state
        if (isWorkflowScripts) {
            set.add(WORKFLOW_TOOL_NAME);           // Workflow — 防递归 workflow（tools.ts:43）
        }
        if (log.isDebugEnabled()) {
            log.debug("[ToolNameConstants] buildAllAgentDisallowed(isAntUser={}, isWorkflowScripts={}) 生成集合 size={}（含 Agent={}, 含 Workflow={}）· CC tools.ts:36-46",
                isAntUser, isWorkflowScripts, set.size(),
                set.contains(AgentToolConstants.AGENT_TOOL_NAME), set.contains(WORKFLOW_TOOL_NAME));
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * <b>[IMP-C4] DC-A1-03</b>: {@code ALL_NAMES} / {@code SOURCE_TO_CONSTANT} 已删除 —
     * CC 无聚合映射表（EV-A1-064），生产零消费者，属零消费者死代码。
     * 删除对象: 06-deletion-manifest DC-A1-03（验证: grep "SOURCE_TO_CONSTANT\|ALL_NAMES" src/main 0 命中）。
     */

    private ToolNameConstants() {
        // 常量容器
    }
}