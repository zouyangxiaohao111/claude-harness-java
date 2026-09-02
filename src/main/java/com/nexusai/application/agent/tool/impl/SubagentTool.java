package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.coordinator.CoordinatorMode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.bubble.PermissionBubbleService;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentDefinitionRegistry;
import com.nexusai.application.agent.subagent.AgentSummaryService;
import com.nexusai.application.agent.subagent.AgentMcpServers;
import com.nexusai.application.agent.subagent.AgentMessage;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.subagent.ForkChildBoilerplate;
import com.nexusai.application.agent.subagent.ForkSubagent;
import com.nexusai.application.agent.subagent.ForkSubagentAgentDefinition;
import com.nexusai.application.agent.subagent.ForkSubagentConfig;
import com.nexusai.application.agent.subagent.ForkSubagentMessages;
import com.nexusai.application.agent.subagent.ForkWorktreePaths;
import com.nexusai.application.agent.subagent.isolation.IsolationResolver;
import com.nexusai.application.agent.subagent.loadAgentsDir;
import com.nexusai.application.agent.subagent.SkillPreloader;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.plugin.PluginLoader;
import com.nexusai.application.agent.tasks.AsyncAgentFinalizer;
import com.nexusai.application.agent.tasks.AsyncAgentResult;
import com.nexusai.application.agent.tasks.BackgroundTask;
import com.nexusai.application.agent.tasks.BackgroundTaskRunner;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.team.SpawnInProcess;
import com.nexusai.application.agent.team.Teammate;
import com.nexusai.application.agent.team.TeammateContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.prompt.AgentToolSection;
import com.nexusai.application.agent.prompt.EffectiveSystemPromptBuilder;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptAssemblyInput;
import com.nexusai.application.agent.prompt.SystemPromptAssemblyInput.McpClientInfo;
import com.nexusai.application.agent.subagent.createSubagentContext;
import com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions;
import com.nexusai.application.agent.worktree.WorktreeCwdTracker;
import com.nexusai.application.agent.tool.ContentReplacementState;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Subagent 工具 · 对齐 CC AgentTool.tsx (call() + schema + 异步模式)
 *
 * <p>关键语义（对齐 CC AgentTool.tsx）：
 * <ul>
 *   <li>Agent 定义查找（find by subagent_type）</li>
 *   <li>Fork 模式检测（isForkSubagentEnabled）</li>
 *   <li>General-Purpose fallback（默认通用）</li>
 *   <li>权限模式处理（bubble for fork）</li>
 *   <li>requiredMcpServers 检查</li>
 *   <li>background 模式（字段默认后台运行）</li>
 *   <li>完整 schema（10 个字段）</li>
 * </ul>
 *
 * <h2>s12 方案 C 值传递架构</h2>
 * <p>打破 ToolRegistry↔SubagentTool 循环依赖：工具列表以值传递（{@code List<Tool>}）
 * 而非服务注入（{@code ToolRegistry}）。
 * <ul>
 *   <li>无参构造器 → Spring 创建 bean（无循环依赖）</li>
 *   <li>{@code @Autowired setAvailableTools(List<Tool>)} → Spring setter 注入工具列表（延迟注入）</li>
 *   <li>{@code createSubagentToolRegistry()} 直接用 {@code this.availableTools}，不再调
 *       {@code mainToolRegistry.all()}</li>
 * </ul>
 */
@org.springframework.stereotype.Component
public class SubagentTool implements Tool {

    /**
     * CC wire name · 对齐 AgentTool/constants.ts:1 AGENT_TOOL_NAME='Agent'.
     * <p>原值 "task" (lowercase) 与 CC 漂移 → LLM tool_use block name 锚定的
     * permission rules / hooks / resumed sessions 全链断. 破约不留兼容壳
     * (项目 AGENTS.md 授权), 旧名 'Task' 经 {@link #aliases()} 保留为 legacy alias
     * (CC AgentTool/constants.ts:3 LEGACY_AGENT_TOOL_NAME).
     */
    private static final String NAME = com.nexusai.application.agent.tool.AgentToolConstants.AGENT_TOOL_NAME;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SubagentTool.class);

    // ── s12 方案 C：工具列表以值传递（非 ToolRegistry 服务引用）──
    /** 所有可用工具列表（s12 方案 C：值传递，非服务注入） */
    private List<Tool> availableTools = new ArrayList<>();

    // ── Spring bean 依赖（setter 注入，非构造器避免循环）──
    private LlmProviderFactory llmProviderFactory;

    private HookRegistry hookRegistry;

    /**
     * 权限冒泡服务 · 对齐 CC AgentTool.tsx:342-353 (filterDeniedAgents + getDenyRuleForAgent).
     * <p>L1 实现已存在于 PermissionBubbleService.java:130-163, 此处仅 wire 调用.
     * <p>s06 P1-3 修补: 真实权限过滤 (审计原状态为 TODO 空壳, 见 SubagentTool.java:356-371 旧版).
     */
    private PermissionBubbleService permissionBubbleService;

    /**
     * Phase 7 修复: required MCP server 真实化. Spring setter 注入 McpToolPool,
     * 取代 Phase 5 之前永远返回 List.of() 的 stub. 对齐 CC AgentTool.tsx:395-404
     * (extract serverName from 'mcp__X__Y').
     */
    private McpToolPool mcpToolPool;

    /**
     * P2.3: MCP transport factory · 注入到 SubagentExecutor 让 sub-agent 走 3 参
     * 重载真接入 tools/list + tools/call (vs 2 参 stub).
     * 可选注入, 未注入时回退到 2 参 stub（向后兼容测试 / 早期启动）.
     */
    private McpTransportFactory mcpTransportFactory;

    /**
     * [MCP-I-9 Q-29 R1] plugin-only policy settings supplier · 对齐 CC runAgent.ts:118
     * isRestrictedToPluginOnly('mcp'). 注入到 SubagentExecutor 让子 agent frontmatter MCP
     * 走真实权限闸（strictPluginOnlyCustomization 锁 MCP 时 USER-CONTROLLED agent 跳过）。
     * 由 ToolRegistrationConfig.subagentExecutor @Bean 注入 (ManagedPolicySettingsSupplier::all)。
     * 未注入 → Map::of（不锁，对齐 CC 无政策默认）。
     */
    private java.util.function.Supplier<java.util.Map<String, Object>> pluginOnlySettingsSupplier = Map::of;

    /**
     * [MCP-I-9 返工 R1] MCP server 按名解析器 · 对齐 CC runAgent.ts:140-151 getMcpConfigByName。
     *
     * <p>首轮实现只在 {@code ToolRegistrationConfig.subagentExecutor @Bean} 接线 resolver
     * （fork-skill 路径），本工具 4 个 executor 构造点（executeSync/async worker/降级 sync/resume）
     * 从不注入 → 模型调用子代理主路径（StreamingToolExecutor:1515 分发）自建 executor 的
     * {@code mcpServerNameResolver=null}，string-ref mcpServers fall-through 成空 command inline
     * → 连接失败。本字段由 {@link #setMcpServerService} 从 {@code McpServerService} 构建，
     * 经 {@link #applyMcpWiring} 对称注入 4 构造点。
     */
    private volatile java.util.function.Function<String, java.util.Optional<AgentMcpServers.McpServerSpec>>
        mcpServerNameResolver;

    /**
     * [MCP-I-9 返工 R1] 生产 MCP server 数据源（DB 唯一运行时源 Q-09=C）· 构建 name resolver。
     * 由 Spring setter 注入（@Autowired(required=false) + @Lazy 断 ToolRegistry 循环）。
     */
    private com.nexusai.domain.mcp.McpServerService mcpServerService;

    /**
     * P1-11: 共享 SkillPreloader (@Component) · 注入到 SubagentExecutor Step 14 预加载 skills.
     *
     * <p>CC 真源：runAgent.ts:580 subagent preload 用模块级共享 memoized getSkillToolCommands
     * （非 per-call 新建）。Java 侧 SkillPreloader 为 @Component（内部共享 SkillRegistry bean），
     * {@code @Autowired(required=false)} 字段注入（对齐 :314-437 现有 setter 注入范式，
     * 此处字段注入保持 3 个 SubagentExecutor 构造站点统一消费同一实例）。
     * 未注入（测试 / 手动直构）→ 3 个构造站点透传 null，SubagentExecutor Step 14 对
     * 声明了 skills 的 agent fail loud（抛 IllegalStateException）。
     */
    @Autowired(required = false)
    private SkillPreloader skillPreloader;

    /**
     * [IMP-SP2-08] PROMPT_CACHE_BREAK_DETECTION feature 门 · 注入到 SubagentExecutor 4 个构造
     * 站点（sync / async / 降级 sync / resume async），供 finally cleanupAgentTracking 门控
     * （对齐 CC runAgent.ts:824-826）。{@code @Autowired(required=false)} 字段注入（对齐
     * skillPreloader 先例，多构造站点统一消费同一实例）；未注入（测试/手动直构）→ 透传 null →
     * SubagentExecutor feature 恒关（默认关行为保持）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.loop.FeatureFlags featureFlags;

    /**
     * [W2-2] settings 档位模型数据源 · subagent 档位模型（settings.subagentModelId，V25 列）。
     * {@code @Autowired(required=false)} 字段注入（对齐 featureFlags 先例）；未注入（测试/手动
     * 直构）→ 读取返回 null → AgentModelResolver 回落 env/原解析链（CC agent.ts:43-45）。
     */
    @Autowired(required = false)
    private com.nexusai.repository.settings.mapper.SettingsMapper settingsMapper;

    /** [2026-08-24 子代理 provider 运行时解析 · 对齐 CC] 模型→(ProviderConfig,providerType) 单一来源
     *  （与 ChatService.buildConfigForModel 同源，ModelConfigResolver:20-42）。@Autowired(required=false)；
     *  未注入（测试/手动直构）→ providerConfig 保持 null（子代理 mock fallback 旧行为，生产已注入）。
     *  子代理 spawn 时按 model 运行时解析 ProviderConfig（对齐 CC runAgent 按 model 解析 provider）。 */
    @Autowired(required = false)
    private com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver;

    /**
     * [IMP-PA-FORK-03] 会话 AgentState 注册表 · fork fallback 重建「父完整有效 system prompt」时
     * 读取父 custom/append/model（对齐 CC AgentTool.tsx:499-511
     * {@code toolUseContext.options.{customSystemPrompt,appendSystemPrompt}} + runAgent.ts:131
     * {@code options.mainLoopModel}）。{@code @Autowired(required=false)} 字段注入（对齐
     * featureFlags/skillPreloader 先例）；未注入（测试/手动直构）→ null → fork fallback 回落旧路径
     * （getEffectiveSystemPrompt，现行为保持）。{@link #setSessionAgentStateRegistry} 供测试注入。
     */
    @Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    /** [IMP-PA-FORK-03] 测试注入入口 · 生产走字段注入（同上）；手动直构 SubagentTool 时经此接线。 */
    public void setSessionAgentStateRegistry(SessionAgentStateRegistry sessionAgentStateRegistry) {
        this.sessionAgentStateRegistry = sessionAgentStateRegistry;
    }

    /** [W2-2] settings 单例 id（settings 表恒 id=1，与 ModelConfigResolver/Service 一致）。 */
    private static final int SETTINGS_SINGLETON_ID = 1;

    // ── 非 bean 依赖（手动 set / 默认值）──
    private LlmAgentLoop mainLoop;

    /**
     * [H7-arch Phase 5-2 P3-③] AgentLoopContext 共享工厂 · 供 SubagentExecutor 构造隔离 ctx
     * （替代 fresh LlmAgentLoop carrier）。对齐 CC runAgent 共享 deps（LoopDeps）+ 隔离
     * agentToolUseContext（tools/messages/abort）。null = SubagentExecutor fail loud。
     */
    private com.nexusai.application.agent.loop.AgentLoopContextFactory contextFactory;

    private ProviderConfig providerConfig;

    private final String defaultModel;

    private final String systemPrompt;

    private final Path workspaceDir;

    /**
     * [C-方案3][DEC-C-01/02][REWORK-1] per-cwd agent-defs 缓存视图 · 对齐 CC
     * {@code getAgentDefinitionsWithOverrides = memoize(cwd)}（loadAgentsDir.ts:296，缓存键=cwd）。
     *
     * <p><b>形态（用户拍板：单例 + per-cwd 缓存视图，DEC-C-03 建议）</b>：SubagentTool 仍为
     * Spring 单例，但 agent-defs 表按<b>会话冻结的发现 cwd</b>（{@link #sessionDiscoveryCwd}，
     * 首访问捕获一次，锚 L3 boundProject）惰性构建 —— key = 会话启动目录（CC startup-cwd-fixed），
     * value = 该 cwd 的 {@link AgentDefinitionRegistry}（built-in + 该 cwd 全源 custom + 全局
     * flag/plugin additions）。<b>会话首调一次载入，非 per-call 重建</b>（DEC-C-02 反模式禁令）；
     * 跨会话同 cwd 共享同一 registry（更省内存）。清空经 {@link #clearRegistryCache()}（对齐 CC
     * clearAgentDefinitionsCache loadAgentsDir.ts:395-398，由 /clear + 插件刷新触发）。
     *
     * <p><b>[REWORK-1] 为什么发现键冻结（而非动态 sessionCwd）</b>：CC agent-defs 进程启动期一次捕获
     * （main.tsx:1929 {@code getAgentDefinitionsWithOverrides(preSetupCwd)}），bash {@code cd}
     * （Shell.ts:407 setCwd → STATE.cwd）与 worktree 进入（EnterWorktreeTool.ts:95 setCwd）
     * <b>从不重载</b>（AgentTool.tsx:286/338/341 只读 options；runAgent.ts:687 子 agent 继承父表）。
     * Java 若缓存键走 {@link CwdResolution#getCwd(String)}（含动态 L2 SessionCwdHolder）→ 会话内
     * cd/worktree 会按新 cwd 重建 registry、可能载入另一项目 agent —— 违背 CC startup-cwd-fixed
     * （REWORK-1 根因）。故发现键 = 会话首访问冻结值（boundProject = CC 启动目录 Java 等价物，
     * memory/session-bound-dir-is-cc-startup-dir），cd/worktree 不再改键。
     *
     * <p><b>为什么 per-cwd 而非单表</b>：CC agent-defs 的 project 源（cwd 向上至 git root/home 的
     * {@code .claude/agents/*.md}）per-cwd 不同（loadAgentsDir.ts:357-372）；Java 多会话 web 架构下
     * 会话可绑不同项目（CwdResolution boundProject/sessionCwd/override），各会话 project agent-defs
     * 应不同 —— 旧构造期 user.dir 单表使 cwd≠user.dir 的会话拿不到自己 project 的自定义 agent
     * （project agent 错乱/缺失，DEC-C-01 依据）。
     */
    private final ConcurrentHashMap<String, AgentDefinitionRegistry> registriesByCwd = new ConcurrentHashMap<>();

    /**
     * [C-方案3][REWORK-1] per-session agent-defs 发现 cwd 冻结表 · 对齐 CC startup-cwd-fixed。
     *
     * <p>会话首次访问 agent-defs 时捕获一次（computeIfAbsent 首写胜），此后该会话恒定用首捕获值作
     * {@link #registryFor} 键 —— 会话内 bash cd / worktree 进入退出 / 解绑重绑 <b>不</b> 改变
     * agent-defs 表（对齐 CC 会话期固定一张表，main.tsx:1929 启动一次 + AgentTool.tsx 只读 options +
     * runAgent.ts:687 子 agent 继承父表）。{@link #clearRegistryCache()} 只清 registry 组装层、
     * <b>不清本冻结表</b>（发现键是会话身份锚，清缓存仅拾取磁盘变更，不重锚）。
     *
     * <p>捕获值锚 L3 boundProject（{@link SessionProjectRoot#getForSession}，setForSession 首写胜
     * 冻结 = CC 启动目录 Java 等价物），跳过动态 L2 SessionCwdHolder 与 L1 override（CC agent-defs
     * 与 cwd override / cd 无关）。
     */
    private final ConcurrentHashMap<String, String> sessionDiscoveryCwd = new ConcurrentHashMap<>();

    /**
     * [C-方案3][DEC-C-03] 全局 flag/plugin agent 追加（--agents flag + 插件目录扫描）。
     *
     * <p>对齐 CC main.tsx:2035-2044（--agents flag）与 loadPluginAgents.ts:234-331（插件 agents）：
     * 这两类 source 跨项目相同（非 per-cwd），并入所有 per-cwd registry。经
     * {@link #addGlobalAdditions} 原子追加 + 合并到既有 registry；新建 per-cwd registry 时
     * 在同一 critical section 内快照合并（registryFor 与 addGlobalAdditions 共用
     * {@code synchronized(globalAdditions)}，保证新 registry 不 miss 已追加的 additions）。
     */
    private final List<AgentDefinition> globalAdditions = new ArrayList<>();

    /**
     * [ODF-C3 返工#4] PluginLoader bean · plugin agents 目录扫描生产调用点。
     * 对齐 CC loadPluginAgents.ts:234-331（每 enabled plugin 扫 agentsPath/agentsPaths）。
     * 可选注入 (required=false)：未注入时 mergePluginAgents() 短路，不破坏既有装配。
     */
    private volatile PluginLoader pluginLoader;

    /**
     * Phase 3: BackgroundTaskRunner · async 路径统一走 runner (CC registerAsyncAgent).
     * <p>替代之前的私有 {@code backgroundTasks: Map} 双轨方案 (Phase 2 反模式).
     */
    private BackgroundTaskRunner backgroundTaskRunner;

    /**
     * CC isBackgroundTasksDisabled 对等开关 · 对齐 AgentTool.tsx:70-73
     * (CLAUDE_AUTO_BACKGROUND_TASKS / tengu_auto_background_agents 时后台任务禁用).
     * <p>ATS-13 sync-background race: run_in_background=true 但后台任务被禁用时,
     * CC shouldRunAsync 整段被 {@code && !isBackgroundTasksDisabled} 短路 → 实际同步执行;
     * Java 端若不 gate, executeAsync 仍尝试注册后台任务, 语义与 CC 漂移.
     */
    private boolean backgroundTasksDisabled = false;

    /**
     * [ALI-3] Telemetry bean · 透传给 SubagentExecutor → MultiTurnRequest →
     * StreamingToolExecutor.setTelemetry. 子 Agent 路径 telemetry 短路修复.
     * 可选注入 (required=false), 未注入时埋点短路 (与 StreamingToolExecutor 一致).
     */
    private volatile com.nexusai.application.agent.telemetry.Telemetry telemetry;

    /**
     * [ALI-3] transcript classifier 开关 · 透传给 SubagentExecutor (默认 true,
     * 对齐 LlmAgentLoop @Value 默认). 关闭时 PermissionDenied retry hook 早返.
     */
    private volatile boolean transcriptClassifierEnabled = true;

    /**
     * [IMP-SUB-25 返工 R2 接线归零] handoff 安全分类器 bean · 经 4 个 SubagentExecutor 构造点
     * 透传（executeSync / asyncWorker / 降级 sync / resume），对齐 CC classifyHandoffIfNeeded
     * 运行时依赖 toolPermissionContext + auto-mode 分类器。
     *
     * <p>此前主 Agent-tool spawn 路径（本类 4 构造点）与 subagentExecutor @Bean 全仓无一处调用
     * setYoloClassifier → yoloClassifier==null → handoff 门 4 恒跳过 → 分类器永不运行（L1 惰性，
     * 项目历史 R3-SUMMARY MS-✗1 / RF-2 P0-② 同型失败模式）。本字段 + setter 对称注入。
     * {@code @Autowired(required=false)}：未注入（测试/手动直构）→ executor 透传 null → handoff 跳过。
     */
    private volatile com.nexusai.application.agent.permission.classifier.YoloClassifier yoloClassifier;

    /**
     * [W8-GAP-01] teammate spawn 生产入口 · 对齐 CC AgentTool.tsx:287-320 spawnTeammate →
     * spawnMultiAgent.ts:899 spawnInProcessTeammate.
     *
     * <p>SpawnInProcess 为 @Component，setter 注入（同 TaskStopTool.java:31-37 模式）。
     * 未注入（测试 / 手动直构）时 teammate 分支 fail loud（返回 error，不静默降级）。
     */
    private volatile SpawnInProcess spawnInProcess;

    /**
     * [R31-03 返工] 周期摘要服务 bean · 对齐 CC agentToolUtils.ts:543-553 startAgentSummarization。
     *
     * <p>主 Agent-tool spawn 路径（executeSync / asyncWorker / 降级 sync / resume 共 4 个
     * {@code new SubagentExecutor} 构造点）此前手动 new 后不调 setSummaryService →
     * summaryService 恒 null → maybeStartSummary 恒 null → AgentSummaryService.start() 主链不可达
     * （R31-03 EV-R31-009）。现经 {@link #applySummaryWiring} 对称注入。
     * {@code @Autowired(required=false)}：未注入（测试/手动直构）→ executor 透传 null → 摘要短路。
     */
    private volatile AgentSummaryService summaryService;

    /**
     * [R31-03 返工][D6] coordinator mode gate bean · 对齐 CC coordinatorMode.ts:36-41
     * isCoordinatorMode()（feature('COORDINATOR_MODE') && isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE)，
     * 动态 env 判定）。与 {@link #summaryService} 对称注入 4 构造点（CC 三 flag 门 isCoordinator 一项）。
     *
     * <p>[D6] 收敛单一 CoordinatorMode bean：本 bean 是 CC isCoordinatorMode 唯一真源。
     * 旧实现 prompt()/shouldRunAsync 的 isCoordinator 用 fork-gate 布尔字段
     * （nexusai.fork.coordinator-mode）→ 两源并存可能分叉（WF1-01 EV-WF1-AE-068/102，HIGH）。
     * 收敛后 {@link #isCoordinatorMode()} helper 优先本 bean（动态 env 判定），bean 未注入
     * （测试/直构）回退 fork-gate 布尔字段。
     */
    private volatile CoordinatorMode coordinatorModeBean;

    /**
     * [R31-03 返工] SDK agentProgressSummaries 门 · 对齐 CC bootstrap/state.ts:1077-1079
     * {@code getSdkAgentProgressSummariesEnabled()}（默认 false · state.ts:303）。
     * {@code @Value} 配置注入（{@code nexusai.agent.progress-summaries-enabled}），经
     * {@link #applySummaryWiring} 透传 SubagentExecutor（CC sync/backgrounded 路径专用门，
     * async/resume 三 flag 合取之一）。
     */
    private volatile boolean sdkAgentProgressSummariesEnabled = false;

    /**
     * [RF-2 ①] SDK 事件队列 bean · 对齐 CC utils/sdkEventQueue.ts（进程级单例）。
     * 经 {@link #applySummaryWiring} 透传 SubagentExecutor；周期摘要回调据此发射
     * {@code task_progress} SDK 事件（CC updateAgentSummary → emitTaskProgress）。
     * 未注入（测试/手动直构）→ executor 透传 null → 摘要仅记录不发射 SDK。
     */
    private volatile SdkEventQueue sdkEventQueue;

    /**
     * [IMP-G4 组11-1] Subagent hard_metrics 遥测 · 对齐 CC {@code logEvent}（AgentTool.tsx /
     * agentToolUtils.ts 的 tengu_agent_* 事件）。可选注入（required=false），未注入 → 事件不发射
     * （与 AnalyticsTracker 短路语义一致）。透传给 4 个 {@code new SubagentExecutor} 构造点。
     */
    private volatile com.nexusai.application.agent.api.AnalyticsTracker analyticsTracker;

    /**
     * [IMP-G4 组11-1] 会话级 name→agentId 注册表（C7）· 对齐 CC appState.agentNameRegistry
     * （AgentTool.tsx:703-712 写点 / SendMessageTool.ts:804 读点）。可选注入（required=false），
     * 未注入 → async spawn 不注册（路由降级 mailbox）。透传给 SubagentExecutor（待办消息消费）。
     */
    private volatile com.nexusai.application.agent.subagent.AgentNameRegistry agentNameRegistry;

    /**
     * Agent color 注册表 · 对齐 CC setAgentColor / agentColorManager
     */
    private final Map<String, String> agentColors = new ConcurrentHashMap<>();

    /**
     * 可用 agent 颜色清单 · CC original: AGENT_COLORS (agentColorManager.ts:14-23)
     * 读侧（D11）共享常量真源，替代旧 env.agentColors 注入（探查 △-3）。
     */
    public static final List<String> AGENT_COLORS =
        List.of("red", "blue", "green", "yellow", "purple", "orange", "pink", "cyan");

    /**
     * agent 颜色 → 主题色映射 · CC original: AGENT_COLOR_TO_THEME_COLOR (agentColorManager.ts:25-34)。
     * getAgentColor 读侧返回主题色 key（如 "red_FOR_SUBAGENTS_ONLY"），对齐 CC
     * {@code return AGENT_COLOR_TO_THEME_COLOR[existingColor]} 的返回面。
     */
    public static final Map<String, String> AGENT_COLOR_TO_THEME_COLOR = Map.of(
        "red", "red_FOR_SUBAGENTS_ONLY",
        "blue", "blue_FOR_SUBAGENTS_ONLY",
        "green", "green_FOR_SUBAGENTS_ONLY",
        "yellow", "yellow_FOR_SUBAGENTS_ONLY",
        "purple", "purple_FOR_SUBAGENTS_ONLY",
        "orange", "orange_FOR_SUBAGENTS_ONLY",
        "pink", "pink_FOR_SUBAGENTS_ONLY",
        "cyan", "cyan_FOR_SUBAGENTS_ONLY");

    /**
     * [Phase A 任务 4] isolation/cwd 解析器 · 决定子 Agent 有效工作目录.
     * <p>Spring setter 注入 (可选, 未注入时回退到无 WorktreeService 的 stub, 命中 isolation=worktree 时降级到 user.dir).
     * <p>手动创建 (测试) 时也用 setter 注入, 避免污染现有 9 参构造签名.
     */
    private IsolationResolver isolationResolver;

    /**
     * [Session M1.2] Fork subagent feature gate · 对齐 CC
     * {@code forkSubagent.ts:32-39 isForkSubagentEnabled}:
     * {@code feature('FORK_SUBAGENT') && !isCoordinatorMode() && !getIsNonInteractiveSession()}
     * (grep 实证 — 注意: AgentTool.tsx:323-325/553-557 的 feature('COORDINATOR_MODE')
     * + isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE) 是 coordinator gate, 不是 fork gate).
     *
     * <p>3 个 boolean 决定 {@link ForkSubagent#isEnabled} 决策:
     * <ul>
     *   <li>{@code featureOn} - 主开关 (默认 true) — CC feature('FORK_SUBAGENT')</li>
     *   <li>{@code coordinatorMode} - coordinator 模式 (默认 false) — CC isCoordinatorMode()
     *       （[D6] 主源为 {@link #isCoordinatorMode()} helper → CoordinatorMode bean；
     *       本字段仅作 bean 未注入时的回退 + {@link ForkSubagent} 静态门槽同步源）</li>
     *   <li>{@code nonInteractive} - 非交互式 (默认 false) — CC getIsNonInteractiveSession()</li>
     * </ul>
     *
     * <p><b>[RES-SP23-1] 配置源</b>: 由 {@link ForkSubagentConfig}（@ConfigurationProperties
     * prefix={@code nexusai.fork}）承载单一配置源，构造器经 {@link #syncForkGateFromConfig()} 读取
     * {@link ForkSubagentConfig#current()} 同步本实例字段 + {@link ForkSubagent} 运行时门槽。
     * 已删除旧 @Value 单字段 setter（双轨）。兼容路径: 无配置时 (手动创建/测试) 默认值
     * true/false/false (与原硬编码一致)。
     */
    private boolean featureOn = true;
    private boolean coordinatorMode = false;
    private boolean nonInteractive = false;

    /**
     * [Session S2] KAIROS feature gate · 对齐 CC {@code feature('KAIROS')} (AgentTool.tsx:111).
     *
     * <p>CC 真源: {@code inputSchema = feature('KAIROS') ? fullInputSchema() : fullInputSchema().omit({cwd:true})}.
     * 外部 build {@code feature('KAIROS')} 恒 false → cwd 始终 omit。Java 默认 false（对齐外部 build），
     * 由 @Value {@code nexusai.feature.kairos} 覆盖。
     */
    private boolean kairosEnabled = false;

    /**
     * [Session S2] agent list attachment 模式 · 对齐 CC {@code shouldInjectAgentListInMessages()}
     * (prompt.ts:59-64)。CC 用 GrowthBook {@code tengu_agent_list_attach}（默认 false）+ env override；
     * Java 无 GrowthBook SDK → @Value boolean 默认 false（attachment 关闭 → inline agent list）。
     */
    private boolean listViaAttachment = false;

    /**
     * [Session S2] pro 订阅标志 · 对齐 CC {@code getSubscriptionType() !== 'pro'} (prompt.ts:246)。
     * Java 无订阅服务 → @Value boolean 默认 false（非 pro，concurrencyNote 生效）。
     */
    private boolean isProSubscription = false;

    /**
     * 无参构造器（Spring bean 创建入口）。
     *
     * <p>s12 方案 C：Spring 用此构造器创建 {@code SubagentTool} bean，
     * 避免通过含 {@code ToolRegistry} 的旧构造器引发循环依赖。
     * 工具列表通过 {@link #setAvailableTools(List)} setter 注入，
     * {@code LlmProviderFactory / HookRegistry} 通过 setter 注入。
     */
    public SubagentTool() {
        this.availableTools = new ArrayList<>();
        this.llmProviderFactory = null;
        this.hookRegistry = null;
        this.mainLoop = null;
        this.providerConfig = null;
        this.defaultModel = "gpt-4";
        this.systemPrompt = "";
        // [C-方案3][DEC-C-02] workspaceDir 仅作无会话兜底 cwd（生产=user.dir，测试=注入 temp），
        //   agent-defs 表不再构造期载入 —— 改运行期 per-session 惰性载入（registryFor(sessionCwdFor(ctx))，
        //   对齐 CC getAgentDefinitionsWithOverrides = memoize(cwd) 启动期一次/会话，非 per-call）。
        this.workspaceDir = Path.of(System.getProperty("user.dir", "."));
        // [Phase A 任务 4] 默认 resolver: 不接 WorktreeService, isolation=worktree 时降级到 user.dir
        this.isolationResolver = new IsolationResolver(null);
        // [RES-SP23-1] 配置类驱动门槽：读 ForkSubagentConfig.current() 同步运行时门槽
        //   （生产 new 构造 @Value 不触发 → 由配置类承载 nexusai.fork.* 单一配置源）
        syncForkGateFromConfig();
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] 无参构造器创建（s12 方案 C Spring bean）");
        }
    }

    /**
     * 完整构造器（手动创建 / 测试用）。
     *
     * <p>s12 方案 C：{@code availableTools} 直接以值传入，不再通过 {@code ToolRegistry} 间接获取。
     * <p>[Phase A 任务 5] 兼容路径: 收 List&lt;AgentDefinition&gt; 内部 new AgentDefinitionRegistry(builtIn, custom) 包装,
     *   保持 6 个测试 (SubagentToolFilterDeniedAgentsTest 等) 既有调用点零改动.
     *
     * @param availableTools  所有可用工具（值传递，非服务引用）
     * @param mainLoop        父 Agent 循环（可 null）
     * @param llmProviderFactory LLM 工厂
     * @param providerConfig   Provider 配置
     * @param defaultModel     默认模型名
     * @param systemPrompt     系统提示
     * @param hookRegistry     Hook 注册中心
     * @param workspaceDir     工作目录
     * @param availableAgents  可用 Agent 定义列表（兼容旧调用，内部包装为 AgentDefinitionRegistry）
     */
    public SubagentTool(
            List<Tool> availableTools,
            LlmAgentLoop mainLoop,
            LlmProviderFactory llmProviderFactory,
            ProviderConfig providerConfig,
            String defaultModel,
            String systemPrompt,
            HookRegistry hookRegistry,
            Path workspaceDir,
            List<AgentDefinition> availableAgents) {
        this.availableTools = availableTools != null ? availableTools : new ArrayList<>();
        this.mainLoop = mainLoop;
        this.llmProviderFactory = llmProviderFactory;
        this.providerConfig = providerConfig;
        this.defaultModel = defaultModel;
        this.systemPrompt = systemPrompt;
        this.hookRegistry = hookRegistry;
        this.workspaceDir = workspaceDir;
        // [C-方案3][DEC-C-02] 兼容路径 agent-defs 不再构造期载入 —— 运行期 per-session 惰性载入
        //   （registryFor(sessionCwdFor(ctx))：一会话一项目 → agent-defs 从会话项目载入，对齐 CC
        //   getAgentDefinitionsWithOverrides = memoize(cwd) 会话首调一次，非 per-call）。
        //   availableAgents（测试/装配方显式传入的自定义 agents）并入全局 additions
        //   （addGlobalAdditions），应用到所有 per-cwd registry（含无会话 workspaceDir 兜底视图）。
        if (availableAgents != null && !availableAgents.isEmpty()) {
            addGlobalAdditions(availableAgents);
        }
        // [Phase A 任务 4] 全构造器也保持 resolver 默认值, 测试/手注走 setter
        this.isolationResolver = new IsolationResolver(null);
        // [RES-SP23-1] 配置类驱动门槽：读 ForkSubagentConfig.current() 同步运行时门槽
        syncForkGateFromConfig();
    }

    /**
     * [RES-R6] 暴露当前会话的 Agent 定义注册中心 · 对齐 CC {@code toolUseContext.options.agentDefinitions.activeAgents}
     * （内置 + 自定义合并，custom 覆盖 builtIn，loadAgentsDir.ts:216）。
     *
     * <p>[C-方案3][DEC-C-02][REWORK-1] 从单例 registry 改为 per-session 视图：按当前会话
     * <b>冻结的发现 cwd</b>（{@link #sessionCwdFor}，首访问捕获锚 L3 boundProject，非动态
     * {@link CwdResolution#getCwd(String)} —— 后者含 L2 SessionCwdHolder 会随 cd/worktree 漂移；
     * 无会话 → {@code workspaceDir} 兜底）惰性载入一次（对齐 CC memoize(cwd) + startup-cwd-fixed），
     * 非 per-call。
     * 供 {@link ResumeService#resolveSelectedAgent}（resume 命中自定义 agent，
     * CC resumeAgent.ts:106-109）、PostCompactAttachmentRestorer（compact 后 agent list delta）、
     * MemoryPrefetcher（agent-memory 预取）消费 —— 均随当前会话冻结启动目录得到各自 project 的 agent 表。
     *
     * @return 当前会话的 Agent 定义注册中心（恒非 null）
     */
    public AgentDefinitionRegistry agentRegistry() {
        return currentRegistry();
    }

    // ════════════════════════════════════════════════════════════════════
    // [C-方案3] per-session 惰性载入核心（DEC-C-01/02/03）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 解析当前会话的 agent-defs 发现 cwd（<b>冻结</b>）· 对齐 CC {@code getAgentDefinitionsWithOverrides(cwd)}
     * memoize 键（loadAgentsDir.ts:296）。
     *
     * <p>优先级：① ToolUseContext.sessionId（当前 turn 直接源）；② {@link RequestContext#sessionId()}
     * （MDC，无 ctx 场景）；③ {@code workspaceDir} 兜底（无会话：JUnit/无 MDC 后台，生产=user.dir，
     * 测试=注入 temp）。
     *
     * <p><b>[REWORK-1] 有会话 → 冻结发现键（非动态 sessionCwd）</b>：{@link #sessionDiscoveryCwdFor} 会话
     * 首访问捕获一次（锚 L3 boundProject = CC 启动目录），此后恒定 —— 会话内 bash cd / worktree 进入
     * 不改 agent-defs 表（对齐 CC startup-cwd-fixed；不用 {@link CwdResolution#getCwd(String)} 因含
     * 动态 L2 SessionCwdHolder，会触发 cd 重载）。
     *
     * @param ctx 当前 turn 的 ToolUseContext（可为 null → 回落 MDC/workspaceDir）
     * @return 归一化发现 cwd（恒非 null，首访问后冻结）
     */
    private String sessionCwdFor(ToolUseContext ctx) {
        String sessionId = ctx != null && ctx.sessionId() != null
            ? ctx.sessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = RequestContext.sessionId();
        }
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionDiscoveryCwdFor(sessionId);
        }
        // 无会话兜底：workspaceDir（生产=user.dir，测试=注入 temp）
        return workspaceDir.toAbsolutePath().normalize().toString();
    }

    /**
     * [C-方案3][REWORK-1] 会话 agent-defs 发现 cwd 冻结读 · 首写胜（对齐 CC startup-cwd-fixed：
     * agent-defs 会话期固定一张表，cd/worktree/解绑重绑不重载）。
     *
     * @param sessionId 会话 ID（恒非空）
     * @return 该会话首访问捕获的发现 cwd（恒非 null）
     */
    private String sessionDiscoveryCwdFor(String sessionId) {
        return sessionDiscoveryCwd.computeIfAbsent(sessionId, this::resolveDiscoveryCwd);
    }

    /**
     * 解析会话发现 cwd 的捕获值 · 锚 L3 boundProject 冻结层（CC 启动目录 Java 等价物，
     * memory/session-bound-dir-is-cc-startup-dir；{@link SessionProjectRoot#setForSession} 首写胜冻结）。
     *
     * <p><b>不读 {@link CwdResolution#getCwd(String)}</b>：其 L2 SessionCwdHolder 是动态 cd/worktree
     * 层，作为发现源会导致会话内 cd 重载 agent-defs（REWORK-1 根因）；L1 override 亦非 CC agent-defs
     * 源（启动表与 cwd override 无关）。未绑定会话 → workspaceDir 兜底（生产=user.dir，对齐 CC 进程
     * 启动 cwd）。<b>身份域红线不动</b>：不触 {@code SessionProjectRoot.resolve()} / AutoMemPaths 回落链。
     *
     * @param sessionId 会话 ID
     * @return 归一化发现 cwd（恒非 null）
     */
    private String resolveDiscoveryCwd(String sessionId) {
        String boundProject = SessionProjectRoot.getForSession(sessionId);
        if (boundProject != null && !boundProject.isBlank()) {
            return CwdResolution.normalizeCwd(boundProject);
        }
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] C-方案3/REWORK-1: 会话 {} 未绑定项目 → 发现 cwd 回落 workspaceDir={}"
                    + "（对齐 CC 进程启动 cwd 兜底）",
                sessionId, workspaceDir.toAbsolutePath().normalize());
        }
        return workspaceDir.toAbsolutePath().normalize().toString();
    }

    /**
     * 当前会话 registry · {@link #registryFor(String)} 的便捷重载（无 ctx 场景，
     * 会话从 MDC 解析；无 MDC → workspaceDir 兜底）。
     */
    private AgentDefinitionRegistry currentRegistry() {
        return registryFor(sessionCwdFor(null));
    }

    /**
     * per-cwd registry 惰性载入 + 缓存 · 对齐 CC {@code getAgentDefinitionsWithOverrides = memoize(cwd)}
     * （loadAgentsDir.ts:296，memoize 键=cwd）。
     *
     * <p><b>DEC-C-02 反模式禁令</b>：会话首调一次载入（computeIfAbsent），后续复用缓存 ——
     * <b>非每次 Agent tool 调用都 loadAllSources 重建</b>（CC AgentTool.tsx:286/338/341 只读
     * options 不重载）。跨会话同 cwd 共享同一 registry（单例 + per-cwd 缓存视图，更省内存）。
     *
     * <p><b>[REWORK-1] 发现键 = 会话冻结启动 cwd</b>：入参来自 {@link #sessionCwdFor}（首访问冻结，
     * 锚 L3 boundProject），会话内 cd/worktree 不改键 → 不重建 registry（对齐 CC startup-cwd-fixed）。
     *
     * <p><b>并发安全</b>：构建 + 全局 additions 快照合并 + map insert 在同一
     * {@code synchronized(globalAdditions)} critical section 内完成（与 {@link #addGlobalAdditions}
     * 互斥）—— 保证新 registry 不 miss 并发追加的 flag/plugin additions（airtight 无竞态窗口）。
     *
     * @param cwd 会话冻结的发现 cwd（null → workspaceDir 兜底）
     * @return 该 cwd 的 Agent 定义注册中心（恒非 null）
     */
    private AgentDefinitionRegistry registryFor(String cwd) {
        String key = cwd != null ? cwd : workspaceDir.toAbsolutePath().normalize().toString();
        AgentDefinitionRegistry reg = registriesByCwd.get(key);
        if (reg != null) {
            return reg;
        }
        // 双检锁：构建 + additions 快照合并 + insert 原子（对 addGlobalAdditions 同锁互斥）
        synchronized (globalAdditions) {
            reg = registriesByCwd.get(key);
            if (reg == null) {
                reg = buildRegistry(key);
                if (!globalAdditions.isEmpty()) {
                    reg.merge(new ArrayList<>(globalAdditions));
                }
                registriesByCwd.put(key, reg);
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentTool] C-方案3: 惰性载入 per-cwd registry cwd={} agents={} "
                            + "(对齐 CC getAgentDefinitionsWithOverrides memoize(cwd)，会话首调一次)",
                        key, reg.size());
                }
            }
        }
        return reg;
    }

    /**
     * 构建某 cwd 的 AgentDefinitionRegistry · built-in + 该 cwd 全源 custom（managed/user/project）。
     * 对齐 CC getAgentDefinitionsWithOverrides 主链路（loadAgentsDir.ts:308 +
     * loadMarkdownFilesForSubdir 三源，markdownConfigLoader.ts:297-430）。
     */
    private AgentDefinitionRegistry buildRegistry(String cwd) {
        List<AgentDefinition> custom = new ArrayList<>(loadAgentsDir.loadAllSources(Path.of(cwd)));
        return new AgentDefinitionRegistry(toMap(BuiltInAgents.getBuiltInAgents()), custom);
    }

    /**
     * 全局 flag/plugin additions 追加 · 对齐 CC main.tsx:2035-2044（--agents flag）+
     * loadPluginAgents.ts:234-331（插件 agents，source 跨项目同）。
     *
     * <p>同一 {@code synchronized(globalAdditions)} critical section 内：追加到 {@link #globalAdditions}
     * 快照（供未来新建 per-cwd registry 合并）+ 合并到<b>既有</b> per-cwd registry
     * （6 组覆盖优先级 managed>flag>project>user>plugin>builtIn）。与 {@link #registryFor} 互斥
     * → 无 "registry 构建中途 miss additions" 竞态。
     *
     * @param additions 新增 agents（flagSettings/plugin 等来源）；null/空 → no-op
     */
    private void addGlobalAdditions(List<AgentDefinition> additions) {
        if (additions == null || additions.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] addGlobalAdditions no-op: 空 additions");
            }
            return;
        }
        synchronized (globalAdditions) {
            globalAdditions.addAll(additions);
            for (AgentDefinitionRegistry reg : registriesByCwd.values()) {
                reg.merge(new ArrayList<>(additions));
            }
            if (log.isInfoEnabled()) {
                log.info("[SubagentTool] C-方案3: 追加 {} 个全局 additions 到 {} 个既有 per-cwd registry"
                        + "（对齐 CC --agents flag / loadPluginAgents.ts）",
                    additions.size(), registriesByCwd.size());
            }
        }
    }

    /**
     * [C-方案3][DEC-C-03] 清空 per-cwd registry 缓存 · 对齐 CC {@code clearAgentDefinitionsCache()}
     * （loadAgentsDir.ts:395-398，memoize cache.clear + clearPluginAgentCache）。
     *
     * <p><b>必须与 {@link loadAgentsDir#clearCache()} 成对调用</b>（后者清 LOAD_CACHE +
     * MarkdownConfigLoader memoize = 文件发现层；本方法清 per-cwd registry 视图 = 组装层），
     * 否则磁盘 agent 变更不可见。触发点（对齐 CC）：/clear 命令（caches.ts:138）+ 插件刷新
     * （cacheUtils.ts:47 clearAllCaches）。清空后下次访问按会话冻结发现 cwd 惰性重建
     * （对齐 CC cache.clear?.() 后下次请求重建）。
     *
     * <p><b>[REWORK-1] 不清 {@link #sessionDiscoveryCwd} 冻结表</b>：发现键是会话身份锚（对齐 CC
     * startup cwd 恒定），清缓存仅拾取磁盘 agent 变更、不重锚启动目录。
     */
    public void clearRegistryCache() {
        registriesByCwd.clear();
        if (log.isInfoEnabled()) {
            log.info("[SubagentTool] C-方案3: per-cwd registry 缓存已清空"
                    + "（对齐 CC clearAgentDefinitionsCache loadAgentsDir.ts:395-398；会话发现 cwd 冻结表保留）");
        }
    }

    /**
     * s12 方案 C：Spring setter 注入工具列表。
     *
     * <p>此 setter 在 bean 构造完成后调用，此时 {@code SubagentTool} 自身已存在，
     * 可作为 {@code Tool} bean 被包含在注入列表中。打破循环依赖的关键：
     * 构造时不依赖列表，注入时 bean 已就绪。
     *
     * @param tools Spring 自动装配的所有 {@link Tool} bean
     */
    @Autowired
    public void setAvailableTools(List<Tool> tools) {
        this.availableTools = tools != null ? new ArrayList<>(tools) : new ArrayList<>();
        log.info("[SubagentTool] s12 方案 C：setter 注入 {} 个工具", this.availableTools.size());
    }

    /** s12 方案 C：Spring setter 注入 LLM 工厂。 */
    @Autowired(required = false)
    public void setLlmProviderFactory(LlmProviderFactory llmProviderFactory) {
        this.llmProviderFactory = llmProviderFactory;
    }

    /**
     * [IMP-G4 组11-1] Spring 注入 Subagent hard_metrics 遥测 · 对齐 CC logEvent tengu_agent_*
     * （AgentTool.tsx:419-428/522-531 + agentToolUtils.ts:322-357）。null → 事件不发射。
     */
    @Autowired(required = false)
    public void setAnalyticsTracker(com.nexusai.application.agent.api.AnalyticsTracker analyticsTracker) {
        this.analyticsTracker = analyticsTracker;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] [IMP-G4] analyticsTracker 注入={} (CC tengu_agent_* logEvent)",
                analyticsTracker != null);
        }
    }

    /**
     * [IMP-G4 组11-1] Spring 注入会话级 name→agentId 注册表（C7）· 对齐 CC
     * appState.agentNameRegistry（AgentTool.tsx:703-712 / SendMessageTool.ts:804）。null →
     * async spawn 不注册（路由降级 mailbox）。
     */
    @Autowired(required = false)
    public void setAgentNameRegistry(com.nexusai.application.agent.subagent.AgentNameRegistry agentNameRegistry) {
        this.agentNameRegistry = agentNameRegistry;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] [IMP-G4] agentNameRegistry 注入={} (CC agentNameRegistry)",
                agentNameRegistry != null);
        }
    }

    /**
     * [H7-arch Phase 5-2 P3-③] Spring 注入 AgentLoopContextFactory · 供 SubagentExecutor 构造隔离 ctx
     * （替代 fresh LlmAgentLoop carrier）。SubagentExecutor.contextFactory null → fail loud。
     */
    @Autowired
    public void setContextFactory(com.nexusai.application.agent.loop.AgentLoopContextFactory contextFactory) {
        this.contextFactory = contextFactory;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] [H7-arch Phase 5-2 P3-③] 注入 AgentLoopContextFactory");
        }
    }

    /** s12 方案 C：Spring setter 注入 Hook 注册中心。 */
    @Autowired(required = false)
    public void setHookRegistry(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    /**
     * Phase 7: setter 注入 McpToolPool. null 时退化为 List.of (与 Phase 5 行为等价,
     * 保留向后兼容).
     *
     * <p>WHY @Lazy: McpToolPool 构造依赖 ToolRegistry, 而 ToolRegistry 注册 SubagentTool,
     * 形成循环依赖. @Lazy 推迟实际解析到第一次 getter 调用, 打破启动期循环.
     */
    @Autowired(required = false)
    public void setMcpToolPool(@org.springframework.context.annotation.Lazy McpToolPool mcpToolPool) {
        this.mcpToolPool = mcpToolPool;
    }

    /**
     * P2.3: setter 注入 McpTransportFactory · 测试可手动注入（无 Spring 容器）.
     * Spring 自动装配时通过 {@code @Autowired(required=false)} 注入, 未注入时回退到
     * 2 参 stub（向后兼容）.
     */
    @Autowired(required = false)
    public void setMcpTransportFactory(McpTransportFactory factory) {
        this.mcpTransportFactory = factory;
    }

    /**
     * [MCP-I-9 Q-29 R1] setter 注入 plugin-only policy settings supplier ·
     * 对齐 CC runAgent.ts:118 isRestrictedToPluginOnly('mcp') 读取.
     * 测试可手动注入（无 Spring 容器）。null → 保持默认 Map::of（不锁）。
     */
    public void setPluginOnlySettingsSupplier(
            java.util.function.Supplier<java.util.Map<String, Object>> pluginOnlySettingsSupplier) {
        if (pluginOnlySettingsSupplier != null) {
            this.pluginOnlySettingsSupplier = pluginOnlySettingsSupplier;
        }
    }

    /**
     * [MCP-I-9 返工 R2] 生产接线真实 ManagedPolicySettingsSupplier · 对齐 CC runAgent.ts:118
     * {@code isRestrictedToPluginOnly('mcp')} 读取 policySettings.strictPluginOnlyCustomization。
     *
     * <p>首轮实现 {@code setPluginOnlySettingsSupplier(Supplier<Map>)} 参数无匹配 bean 且全仓
     * 无外部 setter 调用 → 字段恒 {@code Map::of} → {@code isRestrictedToPluginOnly} 恒 false
     * （Q-29 权限闸死字段，strictPluginOnlyCustomization 锁 MCP 时 USER-CONTROLLED agent 的
     * frontmatter MCP 主路径不被跳过）。本 setter 参数 {@link ManagedPolicySettingsSupplier} 为
     * @Component bean → Spring 自动装配 {code ::all}，经 {@link #applyMcpWiring} 注入 4 构造点。
     */
    @Autowired(required = false)
    public void setManagedPolicySettingsSupplier(
            com.nexusai.application.agent.permission.hook.ManagedPolicySettingsSupplier managedPolicySettingsSupplier) {
        if (managedPolicySettingsSupplier != null) {
            this.pluginOnlySettingsSupplier = managedPolicySettingsSupplier::all;
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] MCP-I-9 返工 R2: ManagedPolicySettingsSupplier::all 接线完成"
                        + "（plugin-only 权限闸主路径生效）");
            }
        }
    }

    /**
     * [MCP-I-9 返工 R1] 生产 MCP server 数据源（DB 唯一运行时源 Q-09=C）· 构建 name resolver。
     * 对齐 CC runAgent.ts:140-151 getMcpConfigByName + McpServerService.getServerConfigByName。
     * @Lazy 断 ToolRegistry 循环（同 setMcpToolPool :412 模式）。
     */
    @Autowired(required = false)
    public void setMcpServerService(
            @org.springframework.context.annotation.Lazy com.nexusai.domain.mcp.McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
        if (mcpServerService != null) {
            this.mcpServerNameResolver = name -> mcpServerService.getServerConfigByName(name)
                .map(cfg -> AgentMcpServers.fromConfig(name, cfg));
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] MCP-I-9 返工 R1: MCP server 按名解析器已接线（McpServerService）");
            }
        }
    }

    /**
     * [MCP-I-9 返工 R1] 直接注入 name resolver（测试 / 装配方）· 与 {@link #setMcpServerService}
     * 二选一（后者为生产路径）。
     */
    public void setMcpServerNameResolver(
            java.util.function.Function<String, java.util.Optional<AgentMcpServers.McpServerSpec>> resolver) {
        this.mcpServerNameResolver = resolver;
    }

    /**
     * [MCP-I-9 返工 R1+R2] MCP 装配 helper · 4 个 executor 构造点统一调用.
     *
     * <p>注入 McpTransportFactory + plugin-only supplier + name resolver（对齐 CC runAgent.ts:
     * 117-151）。首轮实现只在构造点注入 supplier，从不注入 resolver → 模型调用子代理主路径
     * 的 executor resolver=null（双轨缺口）。本 helper 对称注入三者，单一装配点可测试。
     */
    void applyMcpWiring(SubagentExecutor executor) {
        if (mcpTransportFactory != null) {
            executor.setMcpTransportFactory(mcpTransportFactory);
        }
        executor.setPluginOnlySettingsSupplier(pluginOnlySettingsSupplier);
        if (mcpServerNameResolver != null) {
            executor.setMcpServerNameResolver(mcpServerNameResolver);
        }
    }

    /**
     * [R31-03 返工] 周期摘要装配 helper · 4 个 SubagentExecutor 构造点统一调用。
     *
     * <p>对齐 CC agentToolUtils.ts:543-553 {@code onCacheSafeParams → startAgentSummarization}：
     * 主 Agent-tool spawn 路径（executeSync / asyncWorker / 降级 sync / resume 共 4 处手动 new）
     * 此前未调 setSummaryService/setCoordinatorMode → maybeStartSummary 恒 null →
     * AgentSummaryService.start() 主链不可达（R31-03 EV-R31-009）。本 helper 对称注入
     * summaryService + coordinatorMode + sdkAgentProgressSummariesEnabled + spawn 路径（CC 四生产点
     * 三套门）+ sdkEventQueue + backgroundTaskRunner，单一装配点可测试。
     *
     * <p>[RF-2 返工] 追加注入 {@code backgroundTaskRunner}：RF-2 反思 P0-② 认定
     * {@code SubagentExecutor.backgroundTaskRunner} 全仓 0 注入点 → Step 19.7 前台登记守卫
     * （{@code SYNC && backgroundTaskRunner != null}）恒 false → summaryTaskId 恒 null → sync 摘要门
     * 生产不可达（假接线）。此处经 applySummaryWiring 对称注入（同 applyMcpWiring 模式），使 4 构造点
     * （executeSync / asyncWorker / 降级 sync / resume）全部接通 backgroundTaskRunner。
     *
     * @param spawnPath spawn 路径类型（ASYNC/SYNC/BACKGROUNDED/RESUME）· 对齐 CC AgentTool.tsx:750/:852/:934
     *                  + resumeAgent.ts:250-253，决定 maybeStartSummary 分路径门语义（规则三禁止统一三 flag 或）
     */
    void applySummaryWiring(SubagentExecutor executor, SubagentExecutor.SummarySpawnPath spawnPath) {
        executor.setSummaryService(summaryService);
        executor.setCoordinatorMode(coordinatorModeBean);
        executor.setSdkAgentProgressSummariesEnabled(sdkAgentProgressSummariesEnabled);
        executor.setSummarySpawnPath(spawnPath);
        // [RF-2 ①] 注入 SDK 事件队列 → 周期摘要回调经 AgentProgress 通道发射 task_progress。
        executor.setSdkEventQueue(sdkEventQueue);
        // [RF-2 返工] 注入 BackgroundTaskRunner → Step 19.7 sync 前台登记（registerAgentForeground
        //   等价）据此写入 summaryTaskId；否则守卫恒 false（RF-2 反思 P0-② 假接线根因）。
        executor.setBackgroundTaskRunner(backgroundTaskRunner);
        // sync 路径 CC 用 summaryTaskId && sdk 门（AgentTool.tsx:852）；[RF-2 ②] summaryTaskId 由
        //   executeStreaming 内前台登记（registerAgentForeground 等价）后写入，此处初始化为 null。
        executor.setSummaryTaskId(null);
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] R31-03: 周期摘要装配注入 summaryService={} coordinatorModeBean={} "
                + "sdkAgentProgressSummariesEnabled={} sdkEventQueue={} backgroundTaskRunner={} spawnPath={} (对齐 CC agentToolUtils.ts:543-553)",
                summaryService != null, coordinatorModeBean != null, sdkAgentProgressSummariesEnabled,
                sdkEventQueue != null, backgroundTaskRunner != null, spawnPath);
        }
    }

    /**
     * [R31-03 返工] setter 注入 AgentSummaryService · Spring 自动装配（@Service）。
     * 测试可手动注入。
     */
    @Autowired(required = false)
    public void setSummaryService(AgentSummaryService summaryService) {
        this.summaryService = summaryService;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] R31-03: 注入 AgentSummaryService={}", summaryService != null);
        }
    }

    /**
     * [R31-03 返工][D6] setter 注入 CoordinatorMode bean · Spring 自动装配（@Component）。
     * 命名 coordinatorModeBean 避免与既有 fork gate 布尔 coordinatorMode 冲突。
     */
    @Autowired(required = false)
    public void setCoordinatorModeBean(CoordinatorMode coordinatorMode) {
        this.coordinatorModeBean = coordinatorMode;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] R31-03: 注入 CoordinatorModeBean={}", coordinatorMode != null);
        }
    }

    /**
     * [D6] 单一 coordinator mode 判定 · 对齐 CC coordinatorMode.ts:36-41 isCoordinatorMode()
     * （feature('COORDINATOR_MODE') && isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE)，动态 env 判定）。
     *
     * <p><b>WHY（D6 收敛单一 CoordinatorMode bean）</b>: 旧实现 prompt()/shouldRunAsync 的
     * isCoordinator 用 fork-gate 布尔字段（nexusai.fork.coordinator-mode），与 summary wiring 的
     * CoordinatorMode bean 两源并存可能分叉（WF1-01 EV-WF1-AE-068/102，HIGH）。CC 真源是单一
     * isCoordinatorMode() 函数（coordinatorMode.ts:36-41），被 prompt（AgentTool.tsx:223）、
     * shouldRunAsync（:553）、fork gate（forkSubagent.ts:34 {@code !isCoordinatorMode()}）三处共用。
     * 收敛：优先 CoordinatorMode bean（动态 env 判定，CC 真源）；bean 未注入（测试/直构）回退
     * fork-gate 布尔字段（默认 false，与既有测试契约一致）。
     *
     * @return 当前是否 coordinator 模式
     */
    private boolean isCoordinatorMode() {
        if (coordinatorModeBean != null) {
            return coordinatorModeBean.isCoordinatorMode();
        }
        return coordinatorMode;
    }

    /**
     * [R31-03 返工] SDK agentProgressSummaries 门注入 · 对齐 CC bootstrap/state.ts:1077-1079
     * {@code getSdkAgentProgressSummariesEnabled()}（默认 false，print.ts:2904-2909 SDK 消费者请求时置 true）。
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.agent.progress-summaries-enabled:false}")
    public void setSdkAgentProgressSummariesEnabled(boolean sdkAgentProgressSummariesEnabled) {
        this.sdkAgentProgressSummariesEnabled = sdkAgentProgressSummariesEnabled;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] R31-03: sdkAgentProgressSummariesEnabled 注入={} "
                + "(CC getSdkAgentProgressSummariesEnabled, 默认 false)",
                sdkAgentProgressSummariesEnabled);
        }
    }

    /**
     * [RF-2 ①] SDK 事件队列注入 · 对齐 CC utils/sdkEventQueue.ts（进程级单例）。
     * Spring 自动装配（@Component）；测试可手动注入。
     */
    @Autowired(required = false)
    public void setSdkEventQueue(SdkEventQueue sdkEventQueue) {
        this.sdkEventQueue = sdkEventQueue;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] RF-2: 注入 SdkEventQueue={}", sdkEventQueue != null);
        }
    }

    /**
     * Phase 3: Spring setter 注入 BackgroundTaskRunner · async 路径统一.
     * <p>CC AgentTool.tsx:686-764: 异步 agent task 注册到 task store, taskId===agentId.
     */
    @Autowired(required = false)
    public void setBackgroundTaskRunner(BackgroundTaskRunner backgroundTaskRunner) {
        this.backgroundTaskRunner = backgroundTaskRunner;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] Phase 3: BackgroundTaskRunner 注入完成");
        }
    }

    /**
     * CC isBackgroundTasksDisabled 对等开关注入 · 对齐 BashTool.java:151-152 模式.
     * <p>ATS-13: 后台任务禁用时 isAsync 决策短路 → 实际同步执行 (CC AgentTool.tsx:567).
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.agent.background-tasks-disabled:false}")
    public void setBackgroundTasksDisabled(boolean backgroundTasksDisabled) {
        this.backgroundTasksDisabled = backgroundTasksDisabled;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] ATS-13: backgroundTasksDisabled 注入={}", backgroundTasksDisabled);
        }
    }

    /**
     * [ALI-3] Telemetry bean 注入 · 透传给 SubagentExecutor → MultiTurnRequest.
     * 可选注入 (required=false), 未注入时子 Agent 路径埋点短路 (向后兼容).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTelemetry(com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        this.telemetry = telemetry;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] ALI-3: Telemetry 注入={}", telemetry != null);
        }
    }

    /**
     * [ALI-3] transcript classifier 开关注入 · 对齐 LlmAgentLoop
     * {@code ${nexusai.classifier.transcript.enabled:true}} 同源配置.
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.classifier.transcript.enabled:true}")
    public void setTranscriptClassifierEnabled(boolean transcriptClassifierEnabled) {
        this.transcriptClassifierEnabled = transcriptClassifierEnabled;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] ALI-3: transcriptClassifierEnabled 注入={}",
                transcriptClassifierEnabled);
        }
    }

    /**
     * [IMP-SUB-25 返工 R2 接线归零] setter 注入 handoff 安全分类器（同 applySummaryWiring /
     * applyMcpWiring 模式）· Spring 自动装配（YoloClassifierImpl @Component）。测试可手动注入。
     */
    @Autowired(required = false)
    public void setYoloClassifier(com.nexusai.application.agent.permission.classifier.YoloClassifier yoloClassifier) {
        this.yoloClassifier = yoloClassifier;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] IMP-SUB-25 R2: 注入 handoff YoloClassifier={}",
                yoloClassifier != null);
        }
    }

    /**
     * [W8-GAP-01] setter 注入 SpawnInProcess · 对齐 TaskStopTool.java:31-37 模式。
     * 可选注入 (required=false)：未注入（测试 / 早期启动）时 teammate 分支 fail loud。
     */
    @Autowired(required = false)
    public void setSpawnInProcess(SpawnInProcess spawnInProcess) {
        this.spawnInProcess = spawnInProcess;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] W8-GAP-01: 注入 SpawnInProcess={}", spawnInProcess != null);
        }
    }

    /**
     * [Phase A 任务 4] 注入 IsolationResolver · Spring 自动装配或测试手动注入.
     * <p>Spring 路径: 可选注入 (required=false), 未注入时回退到无 WorktreeService 的 stub
     *   (无参构造已初始化); 测试路径: 用 Mockito mock 注入验证调用次数.
     */
    @Autowired(required = false)
    public void setIsolationResolver(IsolationResolver isolationResolver) {
        if (isolationResolver != null) {
            this.isolationResolver = isolationResolver;
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] [Phase A 任务 4] 注入 IsolationResolver: {}",
                        isolationResolver.getClass().getSimpleName());
            }
        }
    }

    /**
     * s06 P1-3: 注入 PermissionBubbleService (Spring bean).
     * <p>委托给 {@link PermissionBubbleService#filterDeniedAgents} 和
     * {@link PermissionBubbleService#getDenyRuleForAgent} — L1 实现已存在,
     * 此处仅 wire. 对齐 CC AgentTool.tsx:342-353.
     */
    @Autowired(required = false)
    public void setPermissionBubbleService(PermissionBubbleService permissionBubbleService) {
        this.permissionBubbleService = permissionBubbleService;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] s06 P1-3: 注入 PermissionBubbleService");
        }
    }

    /**
     * [Session M1.2] 程序化注入 Fork subagent feature gate 三参（测试/装配方显式覆盖用）。
     *
     * <p><b>[RES-SP23-1] 配置源说明</b>: 生产路径默认经 {@link ForkSubagentConfig}（@ConfigurationProperties
     * prefix={@code nexusai.fork}，yml 配置 nexusai.fork.feature-on/coordinator-mode/non-interactive）
     * 驱动，构造器 {@link #syncForkGateFromConfig()} 同步。本 setter 保留为程序化覆盖钩子
     * （测试如 SubagentToolSchemaTest / ForkSubagentIsEnabledInjectionTest 直接注入三参），
     * 与配置类不双写（配置类为默认源，setter 为显式覆盖）。
     *
     * @param featureOn        主开关 (默认 true) · CC feature('FORK_SUBAGENT')
     * @param coordinatorMode  coordinator 模式 (默认 false) · CC isCoordinatorMode()
     * @param nonInteractive   非交互式 (默认 false) · CC getIsNonInteractiveSession()
     */
    public void setForkGate(boolean featureOn, boolean coordinatorMode, boolean nonInteractive) {
        this.featureOn = featureOn;
        this.coordinatorMode = coordinatorMode;
        this.nonInteractive = nonInteractive;
        // [RES-SP23] 同步到 ForkSubagent 运行时门槽（prompt 链 session_guidance 选变体共用此判定）
        ForkSubagent.syncRuntimeGate(featureOn, coordinatorMode, nonInteractive);
        if (log.isInfoEnabled()) {
            log.info("[SubagentTool] M1.2: Fork gate 注入完成 featureOn={}, coordinatorMode={}, nonInteractive={}",
                featureOn, coordinatorMode, nonInteractive);
        }
    }

    /**
     * [RES-SP23-1] 从配置类读取 fork gate 三参并同步实例字段 + {@link ForkSubagent} 运行时门槽。
     *
     * <p><b>WHY</b>（用户拍板 SP23-1，09-open-decisions.md §十一）：生产 SubagentTool 为 {@code new}
     * 构造 → 旧 @Value setter 注入不触发 → 门停默认 {true,false,false}，无法反映 yml 配置。
     * CC 真源 forkSubagent.ts:32-39 的 feature('FORK_SUBAGENT') 是进程级全局值，由配置类承载
     * （{@link ForkSubagentConfig} @ConfigurationProperties prefix={@code nexusai.fork}，
     * Bootstrap 注册静态 current，对齐 PromptCachingTtlConfigBootstrap 模式）。
     *
     * <p>幂等；yml 未配置时 {@link ForkSubagentConfig#current()} 返回 DEFAULTS {true,false,false}，
     * 与硬编码基线一致。
     */
    private void syncForkGateFromConfig() {
        ForkSubagentConfig cfg = ForkSubagentConfig.current();
        this.featureOn = cfg.isFeatureOn();
        this.coordinatorMode = cfg.isCoordinatorMode();
        this.nonInteractive = cfg.isNonInteractive();
        // 同步运行时门槽（prompt 链 session_guidance 选变体共用此判定）
        ForkSubagent.syncRuntimeGate(this.featureOn, this.coordinatorMode, this.nonInteractive);
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] RES-SP23-1: 经 ForkSubagentConfig 同步运行时门槽 featureOn={}, "
                    + "coordinatorMode={}, nonInteractive={}（配置类驱动，非 @Value 注入）",
                this.featureOn, this.coordinatorMode, this.nonInteractive);
        }
    }

    // [RES-SP23-1] 已删除旧 @Value 单字段 setter（setFeatureOn/setCoordinatorMode/setNonInteractive）——
    //   nexusai.fork.* 双轨收敛为单一配置源 ForkSubagentConfig，构造器经 syncForkGateFromConfig() 同步。

    /**
     * [Session S2] KAIROS feature gate 注入 · 对齐 CC {@code feature('KAIROS')} (AgentTool.tsx:111).
     * 默认 false（外部 build 恒 false → cwd 条件 omit）。
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.feature.kairos:false}")
    public void setKairosEnabled(boolean kairosEnabled) {
        this.kairosEnabled = kairosEnabled;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] S2: kairosEnabled 注入={} (对齐 CC feature('KAIROS') AgentTool.tsx:111)", kairosEnabled);
        }
    }

    /**
     * [ODF-C3] JSON producer 配置入口 · 对齐 CC main.tsx:2035-2044
     * ({@code --agents} flag → {@code safeParseJSON} + {@code parseAgentsFromJson(parsedAgents,'flagSettings')}
     * → 并入 allAgents + {@code getActiveAgentsFromList} 覆盖合并)。
     *
     * <p>Java 端 {@code --agents} 的等价配置入口：装配方把 agents JSON map 传入，
     * 本方法经 {@code loadAgentsDir.parseAgentsFromJson(json, "flagSettings")} 解析出
     * source='flagSettings' 的 AgentDefinition，再并入 {@link AgentDefinitionRegistry}
     * （merge 内部走 6 组覆盖优先级 managed>flag>project>user>plugin>builtIn，同 type 覆盖内置）。
     *
     * <p>Fail loud: 非法 JSON（缺必填/非 Map 值）经 parseAgentsFromJson 跳过，不抛异常破坏装配；
     * 解析出 0 个 agent 时 log.warn 记录（对齐 CC main.tsx:2041-2043 catch → logError）。
     *
     * @param agentsJson agents JSON map（key=agentType，value=agent 定义）· 可为 null/空
     */
    public void setJsonAgents(Map<String, Object> agentsJson) {
        if (agentsJson == null || agentsJson.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] setJsonAgents no-op: agents JSON 为空");
            }
            return;
        }
        List<AgentDefinition> flagAgents =
            loadAgentsDir.parseAgentsFromJson(agentsJson, "flagSettings");
        if (flagAgents.isEmpty()) {
            log.warn("[SubagentTool] setJsonAgents: agents JSON 解析出 0 个合法 agent (size={})，"
                + "忽略并保持既有 registry（对齐 CC main.tsx:2041-2043 catch）", agentsJson.size());
            return;
        }
        // [C-方案3][DEC-C-03] 全局 flag additions：source=flagSettings 跨项目同，并入所有 per-cwd
        //   registry（对齐 CC main.tsx:2035-2044 --agents flag → getActiveAgentsFromList 覆盖合并）。
        addGlobalAdditions(flagAgents);
        if (log.isInfoEnabled()) {
            log.info("[SubagentTool] ODF-C3 JSON producer 接入: 解析 {} 个 source=flagSettings agent "
                + "并入 registry (对齐 CC --agents flag main.tsx:2035-2044)",
                flagAgents.size());
        }
    }

    /**
     * [ODF-C3] 当前会话 registry 全部 agents · 对齐 CC {@code agentDefinitions.activeAgents} 列表。
     * <p>验证/装配用访问器（含 flag/plugin 来源，占位 N/A 移除后的可见性）。
     * <p>[C-方案3][DEC-C-02] 从单例 registry 改为 per-session 视图（按 sessionCwd 惰性载入，
     *   对齐 CC memoize(cwd)）；无会话 → workspaceDir 兜底。
     */
    public List<AgentDefinition> listAgents() {
        return currentRegistry().listAgents();
    }

    /**
     * [ODF-C3 返工#1] 装配级 JSON producer 生产接线 · 对齐 CC main.tsx:2035-2044
     * ({@code agentsJson} 启动参数 → {@code safeParseJSON(agentsJson)} → {@code parseAgentsFromJson(parsed,'flagSettings')})。
     *
     * <p>这是 {@code --agents} flag 的 Java 等价配置入口：装配方/启动方把 agents JSON
     * <b>字符串</b>传入（等价 CC CLI 的 {@code agentsJson} 变量），本方法经 {@link ObjectMapper}
     * safeParseJSON 等价解析出 {@code Map<String,Object>}，再委托 {@link #setJsonAgents(Map)}
     * 解析 source='flagSettings' 并并入 registry —— 生产路径（非仅方法级单测）可并入 registry。
     *
     * <p>Fail loud: JSON 字符串非法（null/空白/非对象）→ log.warn 记录并忽略（对齐
     * CC main.tsx:2041-2043 safeParseJSON catch → logError）。
     *
     * @param agentsJsonJson agents JSON 字符串（{@code {"agentType": {description,prompt}}}）
     */
    public void setAgentsJson(String agentsJsonJson) {
        if (agentsJsonJson == null || agentsJsonJson.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] setAgentsJson no-op: agents JSON 字符串为空");
            }
            return;
        }
        try {
            JsonNode node = JSON.readTree(agentsJsonJson);
            if (node == null || !node.isObject()) {
                log.warn("[SubagentTool] setAgentsJson: agents JSON 顶层非对象, 忽略 (对齐 CC main.tsx:2041-2043 catch)");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.convertValue(node, Map.class);
            setJsonAgents(parsed);
        } catch (Exception e) {
            log.warn("[SubagentTool] setAgentsJson: agents JSON 解析失败, 忽略 (对齐 CC main.tsx:2041-2043 safeParseJSON catch): {}",
                e.getMessage());
        }
    }

    /**
     * [ODF-C3 返工#1] Spring 装配级配置入口 · 从 {@code nexusai.agent.agents-json} 配置
     * 读取 {@code --agents} flag 等价的 agents JSON 字符串（对齐 main.tsx:2035-2044）。
     *
     * <p>这是生产装配路径：Spring 启动时注入配置值 → {@link #setAgentsJson(String)}
     * safeParseJSON 等价解析 → {@link #setJsonAgents(Map)} 并入 registry。
     * 装配级测试可直接调 {@link #setAgentsJson(String)} 验证生产路径可并入 registry。
     *
     * @param agentsJsonConfig agents JSON 字符串（Spring 配置 {@code nexusai.agent.agents-json}）
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.agent.agents-json:}")
    public void setAgentsJsonConfig(String agentsJsonConfig) {
        setAgentsJson(agentsJsonConfig);
    }

    /**
     * [ODF-C3 返工#4] PluginLoader bean 注入 · plugin agents 目录扫描生产调用点。
     * <p>对齐 CC loadPluginAgents.ts:234-331（{@code loadAllPluginsCacheOnly} → 每 enabled
     * plugin 扫 {@code agentsPath}/{@code agentsPaths} → 并入 allAgents）。Java 端装配方
     * 注入 PluginLoader 后，由 {@link #mergePluginAgents()} 并入 registry（6 组覆盖合并）。
     */
    @Autowired(required = false)
    public void setPluginLoader(PluginLoader pluginLoader) {
        this.pluginLoader = pluginLoader;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] ODF-C3 plugin producer 接线: PluginLoader={}", pluginLoader != null);
        }
        // 装配期立即扫描（PluginLoader.load() 在 bean 装配前已加载 → 此处可扫到）
        mergePluginAgents();
    }

    /**
     * [ODF-C3 返工#4] plugin agents 生产并入 registry · 对齐 CC loadPluginAgents.ts:234-331。
     * <p>遍历 enabled plugins 扫描全部 plugin agents（pluginName 前缀 + source='plugin'），
     * 经 {@link AgentDefinitionRegistry#merge} 6 组覆盖优先级并入（managed>flag>project>user>plugin>builtIn）。
     * pluginLoader 未注入 → no-op（不破坏既有装配）。Fail loud: 扫描结果为空不报错。
     */
    public void mergePluginAgents() {
        PluginLoader loader = this.pluginLoader;
        if (loader == null) {
            return;
        }
        java.util.List<AgentDefinition> pluginAgents;
        try {
            pluginAgents = loader.loadAllEnabledAgents();
        } catch (Exception e) {
            log.warn("[SubagentTool] mergePluginAgents: plugin agents 扫描失败, 忽略 (Fail loud 记录不破坏装配): {}",
                e.getMessage());
            return;
        }
        if (pluginAgents == null || pluginAgents.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] mergePluginAgents: 无 enabled plugin agents, skip");
            }
            return;
        }
        // [C-方案3][DEC-C-03] 全局 plugin additions：source=plugin 跨项目同，并入所有 per-cwd
        //   registry（对齐 CC loadPluginAgents.ts:234-331 → getActiveAgentsFromList 覆盖合并）。
        addGlobalAdditions(pluginAgents);
        if (log.isInfoEnabled()) {
            log.info("[SubagentTool] ODF-C3 plugin producer 接入: 并入 {} 个 source=plugin agent "
                + "并入 registry (对齐 CC loadPluginAgents.ts:234-331)",
                pluginAgents.size());
        }
    }

    /**
     * [Session S2] agent list attachment 注入 · 对齐 CC {@code shouldInjectAgentListInMessages()}
     * (prompt.ts:59-64) GrowthBook {@code tengu_agent_list_attach} 默认 false → inline agent list。
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.agent.agent-list-in-messages:false}")
    public void setListViaAttachment(boolean listViaAttachment) {
        this.listViaAttachment = listViaAttachment;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] S2: listViaAttachment 注入={} (对齐 CC tengu_agent_list_attach 默认 false)", listViaAttachment);
        }
    }

    /**
     * [Session S2] pro 订阅注入 · 对齐 CC {@code getSubscriptionType() !== 'pro'} (prompt.ts:246)。
     * Java 无订阅服务 → 默认 false（非 pro）。
     */
    @org.springframework.beans.factory.annotation.Value("${nexusai.subscription.pro:false}")
    public void setIsProSubscription(boolean isProSubscription) {
        this.isProSubscription = isProSubscription;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] S2: isProSubscription 注入={} (对齐 CC getSubscriptionType()!=='pro' prompt.ts:246)", isProSubscription);
        }
    }

    // [Phase 2 PR 1 删除] setCurrentPermissionContext 已删除, 由 ToolUseContext.permissionContext()
// per-call 注入取代. 见 SubagentTool.execute(call, ctx) override.

// [Phase 2 PR 1] 保留: 手动注入父 Agent 循环（非 Spring bean）。
    public void setMainLoop(LlmAgentLoop mainLoop) {
        this.mainLoop = mainLoop;
    }

    /** s12 方案 C：手动注入 Provider 配置（非 Spring bean）。 */
    public void setProviderConfig(ProviderConfig providerConfig) {
        this.providerConfig = providerConfig;
    }

    /**
     * 子代理有效 Provider 配置 · [2026-08-24 对齐 CC 运行时解析]。
     *
     * <p><b>WHY</b>：SubagentTool 无参构造 providerConfig=null（:482），生产未注入 → 子代理 spawn
     * 传 null → queryLoop QueryParams.config=null → MockLlmProvider（"mock final"，子代理未真实探索）。
     * CC runAgent 按子代理 model 运行时解析 provider（无装配时注入概念）。本方法：显式注入
     * providerConfig → 用之；null → 按子代理 model 运行时解析（ModelConfigResolver.resolve →
     * config，ChatService.buildConfigForModel 同源）；不可解析 → null（保持 mock fallback）。
     *
     * @param modelName 子代理生效模型名（可能 null/blank）
     * @return 有效 ProviderConfig（可能 null → 子代理 mock fallback）
     */
    private ProviderConfig effectiveProviderConfig(String modelName) {
        if (providerConfig != null) {
            return providerConfig;
        }
        if (modelConfigResolver != null && modelName != null && !modelName.isBlank()) {
            ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
            if (resolved != null) {
                return resolved.config();
            }
        }
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * CC aliases · 对齐 AgentTool.tsx:228 {@code aliases: [LEGACY_AGENT_TOOL_NAME]}.
     * <p>ToolRegistry 查表按 name + alias 双路径反查, 历史 transcript 老名 'Task'
     * 仍可命中 (CC Tool.ts:368-371 aliases?: string[]).
     */
    @Override
    public java.util.List<String> aliases() {
        return java.util.List.of(
            com.nexusai.application.agent.tool.AgentToolConstants.LEGACY_AGENT_TOOL_NAME);
    }

    /**
     * CC 覆写 true · 对齐 AgentTool.tsx:1273-1275.
     * <p>WHY: AgentTool 委托权限检查给底层工具 (isReadOnly=true), 自身可并行调度.
     * Tool.java:132 默认 false (与 CC Tool.ts:759 TOOL_DEFAULTS 一致) — 若不覆写,
     * StreamingToolExecutor 把 SubagentTool 当不可并发串行调度, 与 CC 多 tool_use
     * 并行语义相反 (ATS-8).
     */
    @Override
    public boolean isConcurrencySafe(com.fasterxml.jackson.databind.JsonNode input) {
        return true;
    }

    /**
     * CC prompt() 覆写 · 对齐 AgentTool.tsx:197-225 async prompt({agents,tools,getToolPermissionContext,allowedAgentTypes}).
     *
     * <p>WHY: Java Tool.prompt() 无参 default null（Tool.java:541），LLM 看不到 Agent 工具使用指南
     * （agent 列表 / fork 语义 / when NOT to use / usage notes / examples）→ 在不该 spawn 时 spawn
     * / 不知道有哪些 agent 可用（探查 §8.3 第 1 条）。本方法从注入字段拉取上下文：
     * <ul>
     *   <li>agents = {@code currentRegistry().listAgents()}（per-session 视图，C-方案3）— CC prompt() 入参 agents</li>
     *   <li>MCP filter = {@code loadAgentsDir.filterAgentsByMcpRequirements} — CC AgentTool.tsx:218</li>
     *   <li>permission filter = {@link #filterDeniedAgents} — CC AgentTool.tsx:219</li>
     *   <li>isCoordinator = {@link #isCoordinatorMode()}（[D6] 单一 CoordinatorMode bean 源）— CC AgentTool.tsx:223</li>
     *   <li>allowedAgentTypes = null（Java 无 Agent(x,y) 限制）</li>
     * </ul>
     *
     * <p>S2-2 决策: 不改 Tool.prompt() 签名（改则破坏所有 Tool 实现），SubagentTool 从注入字段拉取
     * （CC buildTool 注入 4 参是 TS 框架特性，Java 用 DI 字段等价）。
     */
    @Override
    public String prompt() {
        // [C-方案3][DEC-C-02] per-session 视图：一会话一项目 → agent-defs 从会话项目载入
        //   （对齐 CC AgentTool.tsx:197-225 prompt({agents: options.agentDefinitions.activeAgents})）
        List<AgentDefinition> agents = currentRegistry().listAgents();
        // CC AgentTool.tsx:206-215 mcpServersWithTools 收集 + :218 filterAgentsByMcpRequirements
        List<AgentDefinition> agentsWithMcpMet = com.nexusai.application.agent.subagent.loadAgentsDir
                .filterAgentsByMcpRequirements(agents, getMcpServersWithTools());
        // CC AgentTool.tsx:219 filterDeniedAgents（permCtx 不可用时 fallback 全量）
        ToolPermissionContext permCtx = mainLoop != null && mainLoop.getCurrentToolUseContext() != null
                ? mainLoop.getCurrentToolUseContext().permissionContext()
                : null;
        List<AgentDefinition> filteredAgents = filterDeniedAgents(agentsWithMcpMet, permCtx);
        // CC AgentTool.tsx:223 isCoordinator = feature('COORDINATOR_MODE') && isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE)
        //   [D6] 收敛单一 CoordinatorMode bean（coordinatorMode.ts:36-41 真源）；旧实现用 fork-gate
        //   布尔字段（nexusai.fork.coordinator-mode）→ 两源可能分叉（EV-WF1-AE-068/102）
        boolean isCoordinator = isCoordinatorMode();
        String promptText = SubagentToolPrompt.getPrompt(filteredAgents, isCoordinator, null,
            SubagentToolPrompt.PromptOptions.of(
                ForkSubagent.isEnabled(featureOn, isCoordinatorMode(), nonInteractive),
                listViaAttachment,
                backgroundTasksDisabled,
                isProSubscription));
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] prompt() 生成: agentCount={}, filteredAgents={}, isCoordinator={}, "
                    + "forkEnabled={}, promptLen={}",
                agents.size(), filteredAgents.size(), isCoordinator,
                ForkSubagent.isEnabled(featureOn, isCoordinatorMode(), nonInteractive),
                promptText == null ? 0 : promptText.length());
        }
        return promptText;
    }

    /**
     * CC isReadOnly 覆写 · 对齐 AgentTool.tsx:1264-1266 {@code isReadOnly() { return true; }}.
     *
     * <p>WHY: AgentTool 委托权限检查给底层工具（isReadOnly=true），自身可并行调度。Tool.java:270
     * default false — 若不覆写，StreamingToolExecutor 把 SubagentTool 当不可并发串行调度，与 CC
     * isConcurrencySafe=true 并行语义冲突（探查 §8.3 第 2 条）。
     */
    @Override
    public boolean isReadOnly(com.fasterxml.jackson.databind.JsonNode input) {
        return true;
    }

    /**
     * CC maxResultSizeChars 覆写 · 对齐 AgentTool.tsx:229 {@code maxResultSizeChars: 100_000}.
     *
     * <p>WHY: Tool.java:376 default 50_000L — 减半导致长结果被截断（探查 §4.3 第 1 项）。
     */
    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }

    /**
     * CC outputSchema 覆写 · 对齐 AgentTool.tsx:141-155 outputSchema union(sync+async).
     *
     * <p>CC 真源:
     * <ul>
     *   <li>sync: {@code agentToolResultSchema().extend({status: 'completed', prompt: string})}
     *       — agentToolResultSchema 字段见 agentToolUtils.ts:227-258</li>
     *   <li>async: {@code {status:'async_launched', agentId, description, prompt, outputFile, canReadOutputFile?}}</li>
     *   <li>返回 {@code z.union([sync, async])} — Java JSON Schema 无 z.union, 用 anyOf 物化</li>
     * </ul>
     *
     * <p>WHY: Tool.java:206 default null — LLM 不知 Agent 返回结构（探查 §4.1 第 2 项）。
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode root = JSON.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode anyOf = root.putArray("anyOf");

        // ── sync 分支: agentToolResultSchema + status:'completed' + prompt ──
        ObjectNode sync = JSON.createObjectNode();
        sync.put("type", "object");
        ObjectNode syncProps = sync.putObject("properties");
        syncProps.put("agentId", stringType());                    // CC agentToolUtils.ts:229
        syncProps.put("agentType", stringType());                  // CC :233 (optional)
        com.fasterxml.jackson.databind.node.ArrayNode contentArr = syncProps.putArray("content"); // CC :234
        ObjectNode contentItem = contentArr.addObject();
        contentItem.put("type", "object");
        ObjectNode contentProps = contentItem.putObject("properties");
        ObjectNode contentType = contentProps.putObject("type");
        contentType.put("type", "string");
        contentType.put("const", "text");
        contentProps.put("text", stringType());
        syncProps.put("totalToolUseCount", numberType());          // CC :235
        syncProps.put("totalDurationMs", numberType());            // CC :236
        syncProps.put("totalTokens", numberType());                // CC :237
        ObjectNode usage = syncProps.putObject("usage");           // CC :238-256
        usage.put("type", "object");
        ObjectNode usageProps = usage.putObject("properties");
        usageProps.put("input_tokens", numberType());
        usageProps.put("output_tokens", numberType());
        usageProps.put("cache_creation_input_tokens", numberType()); // CC :241 (nullable)
        usageProps.put("cache_read_input_tokens", numberType());     // CC :242 (nullable)
        usageProps.put("server_tool_use", numericObjectType("web_search_requests", "web_fetch_requests"));
            // CC agentToolUtils.ts:243-248 server_tool_use: z.object({web_search_requests, web_fetch_requests}).nullable() —— Java 不建模 null (决策点 B)
        usageProps.put("service_tier", enumType(new String[]{"standard", "priority", "batch"}));
            // CC agentToolUtils.ts:249 service_tier: z.enum(['standard','priority','batch']).nullable() —— Java 不建模 null (决策点 B)
        usageProps.put("cache_creation", numericObjectType("ephemeral_1h_input_tokens", "ephemeral_5m_input_tokens"));
            // CC agentToolUtils.ts:250-255 cache_creation: z.object({ephemeral_1h_input_tokens, ephemeral_5m_input_tokens}).nullable() —— Java 不建模 null (决策点 B)
        ObjectNode syncStatus = syncProps.putObject("status");
        syncStatus.put("type", "string");
        syncStatus.put("const", "completed");
        syncProps.put("prompt", stringType());
        sync.putArray("required")
            .add("agentId").add("content").add("totalToolUseCount").add("totalDurationMs")
            .add("totalTokens").add("usage").add("status").add("prompt");
        anyOf.add(sync);

        // ── async 分支: {status:'async_launched', agentId, description, prompt, outputFile, canReadOutputFile?} ──
        ObjectNode async = JSON.createObjectNode();
        async.put("type", "object");
        ObjectNode asyncProps = async.putObject("properties");
        ObjectNode asyncStatus = asyncProps.putObject("status");
        asyncStatus.put("type", "string");
        asyncStatus.put("const", "async_launched");
        asyncProps.put("agentId", stringType());
        asyncProps.put("description", stringType());
        asyncProps.put("prompt", stringType());
        asyncProps.put("outputFile", stringType());
        ObjectNode canRead = asyncProps.putObject("canReadOutputFile");
        canRead.put("type", "boolean");
        async.putArray("required")
            .add("status").add("agentId").add("description").add("prompt").add("outputFile");
        anyOf.add(async);

        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] outputSchema() 构建完成: anyOf 2 分支 (sync completed + async async_launched)");
        }
        return root;
    }

    private ObjectNode stringType() {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", "string");
        return n;
    }

    private ObjectNode numberType() {
        ObjectNode n = JSON.createObjectNode();
        n.put("type", "number");
        return n;
    }

    /**
     * 构建 zod z.object({field: z.number(), ...}) 等价的 JSON Schema object 节点。
     * CC 原名: server_tool_use (agentToolUtils.ts:243-248) / cache_creation (agentToolUtils.ts:250-255)。
     * 两个子字段在 CC 中均为 z.number()，因此本 helper 直接产出 {type:'object', properties:{每个字段: {type:'number'}}}。
     */
    private ObjectNode numericObjectType(String... fieldNames) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "object");
        ObjectNode props = node.putObject("properties");
        for (String name : fieldNames) {
            props.put(name, numberType());
        }
        return node;
    }

    /**
     * 构建 zod z.enum([...]) 等价的 JSON Schema enum 节点（{type:'string', enum:[...]}）。
     * CC 原名: service_tier (agentToolUtils.ts:249)。
     */
    private ObjectNode enumType(String[] values) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "string");
        com.fasterxml.jackson.databind.node.ArrayNode arr = node.putArray("enum");
        for (String v : values) {
            arr.add(v);
        }
        return node;
    }

    /** 搜索提示 · 对齐 CC AgentTool.tsx:227 searchHint。 */
    @Override
    public String searchHint() {
        return "delegate work to a subagent";
    }

    @Override
    public String description() {
        // 对齐 CC AgentTool.tsx:230-232 description() → 'Launch a new agent'
        return "Launch a new agent";
    }

    /**
     * 工具活动描述 · 对齐 CC AgentTool.tsx:1278-1280
     * {@code getActivityDescription(input) { return input?.description ?? 'Running task' }}。
     *
     * <p>消费方（Tool.java:431-432 对齐 CC Tool.ts:546-548）在无 getActivityDescription 时回退
     * getToolUseSummary / userFacingName / name；本 override 提供 CC 语义：input 有 description
     * → 用之，否则 'Running task'。
     */
    @Override
    public String getActivityDescription(com.fasterxml.jackson.databind.JsonNode input) {
        if (input != null && input.has("description") && !input.get("description").isNull()
                && !input.get("description").asText().isBlank()) {
            return input.get("description").asText();
        }
        return "Running task";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = JSON.createObjectNode();

        // 必需字段
        addProperty(properties, "description", "string", "A short (3-5 word) description of the task");
        addProperty(properties, "prompt", "string", "The task for the agent to perform");

        // 可选字段
        addProperty(properties, "subagent_type", "string", "The type of specialized agent to use");
        addEnumProperty(properties, "model", new String[]{"sonnet", "opus", "haiku"},
            "Optional model override for this agent");
        // CC AgentTool.tsx:122-124 run_in_background 条件 omit:
        //   isBackgroundTasksDisabled || isForkSubagentEnabled() 时省略 (LLM 看不到无效字段)
        addProperty(properties, "run_in_background", "boolean",
            "Set to true to run this agent in the background");

        // CC fullInputSchema 额外字段（对齐 CC AgentTool.tsx:91-101）
        addProperty(properties, "name", "string", "Name for the spawned agent");
        addProperty(properties, "team_name", "string", "Team name for spawning");
        // CC AgentTool.tsx:96 mode: permissionModeSchema().optional() —
        //   PERMISSION_MODES = ['acceptEdits','bypassPermissions','default','dontAsk','plan']
        //   (types/permissions.ts:16-22, 外部 build 无 TRANSCRIPT_CLASSIFIER 故无 'auto')
        addEnumProperty(properties, "mode", new String[]{"acceptEdits", "bypassPermissions", "default", "dontAsk", "plan"},
            "Permission mode for spawned teammate (e.g., \"plan\" to require plan approval)");
        // CC AgentTool.tsx:99 isolation: ("external" === 'ant' ? z.enum(['worktree','remote']) : z.enum(['worktree']))
        //   外部 build ("external" === 'ant') 恒 false → 只暴露 ['worktree'], remote 是 ant-only (Java 无 CCR 实现)
        addEnumProperty(properties, "isolation", new String[]{"worktree"},
            "Isolation mode. \"worktree\" creates a temporary git worktree so the agent works on an isolated copy of the repo.");
        // CC AgentTool.tsx:111-113 cwd 条件 omit: feature('KAIROS') ? full : full.omit({cwd:true})
        //   外部 build feature('KAIROS') 恒 false → cwd 始终省略
        addProperty(properties, "cwd", "string", "Absolute path to run the agent in. Overrides the working directory for all filesystem and shell operations within this agent. Mutually exclusive with isolation: \"worktree\".");

        // CC AgentTool.tsx:122-124 run_in_background 条件 omit — backgroundTasksDisabled || fork gate on
        //   [D6] fork gate 的 coordinator 项收敛单一 CoordinatorMode bean（forkSubagent.ts:34
        //   !isCoordinatorMode() 同源；旧实现用 nexusai.fork.coordinator-mode 静态布尔 → 两源分叉）
        if (backgroundTasksDisabled || ForkSubagent.isEnabled(featureOn, isCoordinatorMode(), nonInteractive)) {
            properties.remove("run_in_background");
        }
        // CC AgentTool.tsx:111-113 cwd 条件 omit — !feature('KAIROS') → omit (外部 build 默认 false)
        if (!kairosEnabled) {
            properties.remove("cwd");
        }

        // 构建 required（只 description 和 prompt 必需）
        com.fasterxml.jackson.databind.node.ArrayNode required = JSON.createArrayNode();
        required.add("description");
        required.add("prompt");

        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] inputSchema() 构建完成: backgroundTasksDisabled={}, forkGateOn={}, "
                    + "kairosEnabled={}, run_in_background={}, cwd={}",
                backgroundTasksDisabled,
                ForkSubagent.isEnabled(featureOn, isCoordinatorMode(), nonInteractive),
                kairosEnabled,
                properties.has("run_in_background"),
                properties.has("cwd"));
        }
        return schema;
    }

    /**
     * [IT-5] 未知键运行时策略 = STRIP · 对齐 CC AgentTool.tsx:82-88
     * {@code baseInputSchema = lazySchema(() => z.object({...}))} + :91-102
     * {@code fullInputSchema = baseInputSchema().merge(...).extend(...)} ——
     * merge().extend() 结果仍为 z.object（zod v4.4.3 实测 safeParse strip 未知键）。
     *
     * <p>:1019 广告 {@code additionalProperties=false} 保留：zod v4 toJSONSchema 对
     * z.object 实测输出 additionalProperties:false，广告层与 CC 逐字一致；
     * 运行时放行由本策略承担（广告与运行时分离）。
     */
    @Override
    public Tool.UnknownKeysPolicy unknownKeysPolicy() {
        return Tool.UnknownKeysPolicy.STRIP;
    }

    private void addProperty(ObjectNode parent, String name, String type, String description) {
        ObjectNode prop = JSON.createObjectNode();
        prop.put("type", type);
        prop.put("description", description);
        parent.set(name, prop);
    }

    private void addEnumProperty(ObjectNode parent, String name, String[] values, String description) {
        ObjectNode prop = JSON.createObjectNode();
        prop.put("type", "string");
        com.fasterxml.jackson.databind.node.ArrayNode enumArr = JSON.createArrayNode();
        for (String v : values) enumArr.add(v);
        prop.set("enum", enumArr);
        prop.put("description", description);
        parent.set(name, prop);
    }

    @Override
    public ToolResult execute(ToolUseBlock call) {
        // [Session J 方案 A] 单参兼容路径: 从父 Loop 提取 ctx, fork 参数走默认值.
        ToolUseContext parentCtx = mainLoop != null ? mainLoop.getCurrentToolUseContext() : null;
        return execute(call, parentCtx, null, AgentOptions.defaultOptions(), null);
    }

    /**
     * [Session J 方案 A] CC 对齐主路径 · 对齐 AgentTool.tsx:250 五参 call 签名.
     * querySource 经 {@link AgentOptions} 透传, assistantMessage 保持独立方法参数.
     *
     * <p>[IMP-SUB-28 A5] onProgress 现透传至 doExecute → executeSync/executeAsync（降级 sync），
     * 不再丢弃：父 caller（StreamingToolExecutor 注入）据此观测子 Agent 流式消息
     * （对齐 CC AgentTool.tsx:783-810 sync 路径 onProgress 上报；原残余 = execute 4 参传 null sink）。
     */
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx,
                              java.util.function.Consumer<Tool.ToolProgress> onProgress,
                              AgentOptions agentOptions,
                              ForkSubagentMessages.Message assistantMessage) {
        return doExecute(call, ctx, onProgress, agentOptions, assistantMessage);
    }

    /** [Session J 方案 A] 四参兼容壳: 未提供 assistantMessage 时传 null. */
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx,
                              java.util.function.Consumer<Tool.ToolProgress> onProgress,
                              AgentOptions agentOptions) {
        return execute(call, ctx, onProgress, agentOptions, null);
    }

    /**
     * [Phase 2 PR 1] per-call 注入 ctx 的 execute · 对齐 CC runAgent.ts:700-714.
     *
     * <p>StreamingToolExecutor 普通 Tool 接口仍调用三参签名; SubagentTool 特化主路径
     * 由 StreamingToolExecutor 调五参重载.
     */
    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx,
                              java.util.function.Consumer<Tool.ToolProgress> onProgress) {
        return execute(call, ctx, onProgress, AgentOptions.defaultOptions(), null);
    }

    /**
     * [Phase 2 PR 1] 旧 2 参 execute 兼容路径.
     */
    @Override
    public ToolResult execute(ToolUseBlock call, ToolUseContext ctx) {
        return execute(call, ctx, null);
    }

    /**
     * 实际执行逻辑 (Phase 2 PR 1 重构: 从 execute(call) 抽出, 接受 per-call permCtx).
     *
     * <p>[Session E] 进一步重构: 改为接受完整 {@link ToolUseContext}, 内部按需派生
     * {@link ToolPermissionContext} (兼容旧 per-call ctx 注入路径). 同时新增 fork path
     * 字段读取 (querySource / assistantMessage / messages), 对齐 CC AgentTool.tsx:318-635.
     *
     * <p>[IMP-SUB-28 A5] 新增 {@code onProgress} 透传 —— 父 caller（StreamingToolExecutor 注入）
     * 经 executeSync / executeAsync（降级 sync）到达 {@code SubagentExecutor.executeStreaming}
     * 的 messageSink，使父可逐消息观测子 Agent 流式产出（原残余 = 4 参 execute 传 null sink）。
     *
     * @param call 当前 tool_use 块
     * @param ctx  当前 turn 的 ToolUseContext (可为 null → fallback 走旧路径)
     * @param onProgress 父 caller 进度回调 (StreamingToolExecutor 注入; null = 非流式)
     */
    private ToolResult doExecute(ToolUseBlock call, ToolUseContext ctx,
                                 java.util.function.Consumer<Tool.ToolProgress> onProgress,
                                 AgentOptions agentOptions,
                                 ForkSubagentMessages.Message assistantMessage) {
        // [Session M1.3] fork path 派生 forkParentSystemPrompt (CC AgentTool.tsx:493-511)
        //   fork path 必须复用父 ctx.renderedSystemPrompt (或 fallback recompute),
        //   保证 prompt cache prefix byte-identical. 此处派生后透传到 executeSync/Async.
        String forkParentSystemPrompt = null;
        String currentCwd = null;
        JsonNode input = call.input();
        String description = getString(input, "description");
        String prompt = getString(input, "prompt");
        String subagentType = getStringOrNull(input, "subagent_type");
        String model = getStringOrNull(input, "model");
        boolean runInBackground = input.has("run_in_background") && input.get("run_in_background").asBoolean();

        // [Session E] 派生 permCtx from ctx (兼容 null ctx fallback 路径)
        ToolPermissionContext permCtx = ctx != null ? ctx.permissionContext() : null;
        // [C-方案3][DEC-C-02] per-session agent-defs 视图：一会话一项目 → agent-defs 从会话项目载入
        //   （对齐 CC getAgentDefinitionsWithOverrides = memoize(cwd)，会话首调一次非 per-call）。
        //   在 doExecute 顶部捕获一次，同步/异步/降级路径统一消费（asyncWorker 跨线程需在此捕获）。
        final AgentDefinitionRegistry sessionRegistry = registryFor(sessionCwdFor(ctx));

        // s31 R-P1-1: Schema 5 字段显式读取 + isolation/cwd 真正生效.
        //   schema 暴露 name/team_name/mode/isolation/cwd 5 字段 (对齐 CC AgentTool.tsx:91-101),
        //   之前审计偏差: execute 不读, isolation/cwd 构成隔离能力误导风险.
        //   现在: 显式读取并记录日志, isolation/cwd 真正接入 → IsolationResolver 解析 + WorktreeCwdTracker.setCwd.
        //   name/team_name/mode 仍仅记录日志 (s23 实施后接入 team spawn).
        String name = getStringOrNull(input, "name");
        String teamName = getStringOrNull(input, "team_name");
        String mode = getStringOrNull(input, "mode");
        String isolation = getStringOrNull(input, "isolation");
        String cwd = getStringOrNull(input, "cwd");
        if (name != null || teamName != null || mode != null) {
            log.info("[SubagentTool] doExecute: CC 对齐字段 name='{}' team_name='{}' mode='{}' "
                    + "— name+team_name 触发 teammate spawn 分支 (W8-GAP-01, CC AgentTool.tsx:287), "
                    + "mode 用于 plan_mode_required (CC AgentTool.tsx:296)",
                    name, teamName, mode);
        }
        // [Phase A 任务 4] 移除原 isolation/cwd "当前未生效" warn 分支, 改为下面 step 5 之后的真正解析.
        // 这里只读取字段, 不再 warn.

        if (description.isBlank()) return ToolResult.error(call.id(), "description cannot be empty");
        if (prompt.isBlank()) return ToolResult.error(call.id(), "prompt cannot be empty");

        // ═══════════════════════════════════════════════════════════════════
        // [W8-GAP-01] teammate spawn 生产分支 · 对齐 CC AgentTool.tsx:263-320
        //   (spawnTeammate → spawnMultiAgent.ts:899 spawnInProcessTeammate)
        // ═══════════════════════════════════════════════════════════════════
        // CC :263-264: team_name 传入但 swarms 未启用 → throw（"Agent Teams is not yet available on your plan"）。
        //   [2026-08-24 用户拍板] 注释掉此套餐/门控过滤——当前无套餐概念，暂时不需要 swarms 开关拦截
        //   team spawn。恢复时取消注释下方块 + resolvedTeamName 改回三元。
        // if (teamName != null && !TaskSystemConfig.isAgentSwarmsEnabled()) {
        //     return ToolResult.error(call.id(),
        //         "Agent Teams is not yet available on your plan.");
        // }
        // CC :268 + :1388-1396 resolveTeamName: Java 无 appState.teamContext → teamName = input.team_name。
        //   [去门控] 原 isAgentSwarmsEnabled() ? teamName : null —— swarms 关时 teamName 置 null 导致
        //   后续 team 分支（isTeammate/guardTeammateTeamName）失效；用户拍板暂时不过滤 → 直接透传 teamName。
        String resolvedTeamName = teamName;
        // CC :272-276: isTeammate() && teamName && name → teammate 不能 spawn teammate
        //   （独立顶层守卫；CC 同序 :272 先于 :278 — 带 name 的 in-process teammate 后台
        //     spawn 先抛此消息，逐字对齐 CC AgentTool.tsx:272-273）
        if (isTeammate() && resolvedTeamName != null && name != null) {
            return ToolResult.error(call.id(),
                "Teammates cannot spawn other teammates — the team roster is flat. "
                + "To spawn a subagent instead, omit the `name` parameter.");
        }
        // CC :278-280: isInProcessTeammate() && teamName && run_in_background===true → throw
        //   （独立顶层守卫，不依赖 name — CC:279 真触发场景「无 name 后台 spawn from
        //     in-process teammate」亦触发；GAP-R3: 原实现嵌套于 name 分支(:1189)且被
        //     isTeammate ⊇ isInProcessTeammate 遮蔽，结构不可达 → 移出为独立守卫）
        //   teamName 对齐 CC resolveTeamName :1396 = input.team_name || appState.teamContext?.teamName：
        //     Java appState.teamContext 等价 = TeammateContext.teamName（SpawnInProcess.java:211
        //     以 config.teamName() 构造；R1 线程传播后工具执行线程同线程可见）。
        if (isInProcessTeammate() && guardTeammateTeamName(resolvedTeamName) != null && runInBackground) {
            log.warn("[SubagentTool] in-process teammate 后台 spawn 被守卫拒绝: team={} name={} "
                    + "run_in_background=true（对齐 CC AgentTool.tsx:278-280）",
                guardTeammateTeamName(resolvedTeamName), name);
            return ToolResult.error(call.id(),
                "In-process teammates cannot spawn background agents. "
                + "Use run_in_background=false for synchronous subagents.");
        }
        // CC :284-285: teamName && name → spawnTeammate 分支
        if (resolvedTeamName != null && name != null) {
            return spawnTeammate(call, ctx, name, resolvedTeamName, prompt, mode, subagentType, model);
        }

        // §14.3 Fork subagent experiment routing (对齐 CC AgentTool.tsx:322-323)
        // [Session E] 完整 fork gate 恢复: effectiveType = subagent_type ?? (isForkSubagentEnabled() ? undefined : GENERAL_PURPOSE)
        //   - subagentType 显式传入 → 原样使用 (explicit wins, CC line 319)
        //   - subagentType 缺省 + fork gate on → effectiveType = undefined (走 fork path, CC line 322)
        //   - subagentType 缺省 + fork gate off → fallback GENERAL_PURPOSE (CC line 322)
        //
        //   fork gate: ForkSubagent.isEnabled(featureOn, isCoordinatorMode(), nonInteractive)
        //   [RES-SP23-1] featureOn/nonInteractive 由 ForkSubagentConfig（nexusai.fork.* 配置类）经构造器
        //   syncForkGateFromConfig() 同步（取代旧版硬编码 + @Value 注入双轨）.
        //   [D6] coordinator 项收敛单一 CoordinatorMode bean（forkSubagent.ts:34 !isCoordinatorMode() 同源；
        //   旧实现用 nexusai.fork.coordinator-mode 静态布尔 → 两源分叉 EV-WF1-AE-068/102）.
        boolean forkGateOn = ForkSubagent.isEnabled(this.featureOn, isCoordinatorMode(), this.nonInteractive);
        String effectiveType = subagentType != null ? subagentType : (forkGateOn ? null : BuiltInAgents.GENERAL_PURPOSE);
        boolean isForkPath = (effectiveType == null); // null = fork path (CC line 323)
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] fork routing: subagentType='{}', forkGateOn={}, effectiveType={}, isForkPath={}",
                subagentType, forkGateOn, effectiveType == null ? "<undefined/fork>" : effectiveType, isForkPath);
        }

        // §14.3.1 fork path: 递归 guard + 直接选 FORK_AGENT (对齐 CC AgentTool.tsx:332-356)
        //   跳过 filterDeniedAgents + findAgent + hasRequiredMcpServers (FORK_AGENT 必然存在 + 无 MCP 需求)
        AgentDefinition selectedAgent;
        if (isForkPath) {
            // CC AgentTool.tsx:332-334: querySource 检查 + messages 扫描 (防递归 fork)
            String querySource = agentOptions != null ? agentOptions.querySource() : null;
            ForkSubagentMessages.AssistantMessage forkAssistantMessage =
                toForkAssistantMessage(assistantMessage);
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] Session J fork 参数透传: querySource={}, assistantMessage={}",
                    querySource, forkAssistantMessage != null ? "已透传" : "null");
            }
            List<String> userTextBlocks = collectUserTextBlocks(ctx);
            if ("agent:builtin:fork".equals(querySource)
                    || ForkSubagent.isInForkChild(userTextBlocks)) {
                if (log.isWarnEnabled()) {
                    log.warn("[SubagentTool] 递归 fork 阻断: querySource={}, isInForkChild={} "
                            + "— 提示父 agent 直接完成子任务",
                        querySource, ForkSubagent.isInForkChild(userTextBlocks));
                }
                return ToolResult.error(call.id(),
                    "Fork is not available inside a forked worker. Complete your task directly using your tools.");
            }
            // CC AgentTool.tsx:335: selectedAgent = FORK_AGENT (内置 + 不可拒绝, 不走 filterDeniedAgents)
            selectedAgent = ForkSubagentAgentDefinition.create();
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] fork path 启用: selectedAgent={}", selectedAgent.agentType());
            }
            // [Session M1.3] CC AgentTool.tsx:493-511 forkParentSystemPrompt 派生
            //   优先用 ctx.renderedSystemPrompt (父 cache 字节); fallback recompute (recompute
            //   可能因 GrowthBook 状态变化与父 cache 字节漂移, 但语义等价).
            if (ctx != null && ctx.renderedSystemPrompt() != null
                    && !ctx.renderedSystemPrompt().isBlank()) {
                forkParentSystemPrompt = ctx.renderedSystemPrompt();
            } else {
                // [IMP-PA-FORK-03] fallback 对齐 CC AgentTool.tsx:499-511: 用
                //   EffectiveSystemPromptBuilder 重建「父完整有效 system prompt」
                //   （default 组装 + custom/append），而非 selectedAgent + AgentToolSection。
                //   父 AgentState 不可得（罕见/测试）→ 回落旧 getEffectiveSystemPrompt（现行为）。
                forkParentSystemPrompt = buildForkParentFallbackSystemPrompt(ctx, selectedAgent);
            }
            // [Session M1.3] CC AgentTool.tsx:582-593 worktree 检测
            //   [Session E 修正] 本段只做两件事: ① 解析 worktreePath 供日志/结果 trailer 的
            //   currentCwd 透传 (buildResultTrailer hasWorktreeInfo 判定); ② isolation 值经
            //   executeSync/Async 第 10 参透传给 SubagentExecutor (setEffectiveIsolation) —
            //   真实 worktree 创建 + buildWorktreeNotice 注入由 executor Step 18 决策完成
            //   (CC AgentTool.tsx:590-602 语义, 本段不再声称"→ buildWorktreeNotice").
            //   isolation 来自 input.isolation (CC AgentTool.tsx:431 effectiveIsolation).
            if (isolation != null && "worktree".equals(isolation)) {
                Path explicitCwd = cwd == null ? null : Paths.get(cwd);
                Path worktreePath = isolationResolver.resolve(isolation, explicitCwd, selectedAgent,
                    Paths.get(System.getProperty("user.dir")));
                currentCwd = System.getProperty("user.dir", ".");
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentTool] M1.3 fork path 派生: forkParentSystemPrompt.length={}, "
                            + "worktreePath={}, currentCwd={}",
                        forkParentSystemPrompt == null ? 0 : forkParentSystemPrompt.length(),
                        worktreePath, currentCwd);
                }
            }
            // [Session E] CC AgentTool.tsx:609 fork parent system prompt 透传 + line 512 buildForkedMessages
            //   跳过 filterDeniedAgents + findAgent + hasRequiredMcpServers (CC line 367-410 在 fork path 不执行)
            //   透传到后续 executeSync / executeAsync (修改对应方法签名)
        } else {
            // §14.3.1: filterDeniedAgents — 过滤被权限规则拒绝的 Agent
            // 对齐 CC AgentTool.tsx:342-355: filterDeniedAgents + denyRule 检查
            // [C-方案3][DEC-C-02] 读 per-session 视图（sessionRegistry，doExecute 顶部捕获）
            List<AgentDefinition> allowedAgents = filterDeniedAgents(sessionRegistry.listAgents(), permCtx);
            selectedAgent = allowedAgents.stream()
                    .filter(a -> a.agentType().equals(effectiveType)).findFirst().orElse(null);
            if (selectedAgent == null) {
                // 对齐 CC AgentTool.tsx:346-353: agent type 不存在 → 区分"不存在"vs"被拒绝"
                AgentDefinition existsButDenied = sessionRegistry.findAgent(effectiveType);
                if (existsButDenied != null) {
                    String denyRule = getDenyRuleForAgent(effectiveType, permCtx);
                    return ToolResult.error(call.id(),
                        "Agent type '" + effectiveType + "' has been denied by permission rule '"
                        + com.nexusai.application.agent.tool.AgentToolConstants.AGENT_TOOL_NAME + "("
                        + effectiveType + ")' from " + (denyRule != null ? denyRule : "settings") + ".");
                }
                return ToolResult.error(call.id(),
                    "Agent type '" + effectiveType + "' not found. Available agents: " +
                    allowedAgents.stream().map(AgentDefinition::agentType).reduce((a, b) -> a + ", " + b).orElse("none"));
            }

            // §14.3.2: requiredMcpServers 检查（对齐 CC AgentTool.tsx:369-410）
            if (!hasRequiredMcpServers(selectedAgent)) {
                List<String> missing = getMissingMcpServers(selectedAgent);
                return ToolResult.error(call.id(),
                    "Agent '" + selectedAgent.agentType() + "' requires MCP servers matching: "
                    + String.join(", ", missing) + ". Use /mcp to configure and authenticate the required MCP servers.");
            }
        }

        // [Phase A 任务 4] isolation/cwd 真正接入: 通过 IsolationResolver 解析有效 cwd,
        //   并按 toolUseId 前缀 ('tool-' + call.id) 写入 WorktreeCwdTracker (session 维度追溯).
        //   放在 filterDeniedAgents + hasRequiredMcpServers 之后, 只对真正启动的子 Agent 解析.
        //
        //   P0-2 修复: 原 key 为裸 call.id (toolUseId) 与 WorktreeCwdTracker L1 契约 (per sessionId) 冲突,
        //     导致 activeSessionCount 单调递增且 getCwd(sessionId) 拿不到. 现统一加 'tool-' 前缀表明
        //     "本 turn 内 tool 维度追踪" 区别于 session 维度. 子 Agent 工具链真正读取 effective cwd 走
        //     subagentCtx.toolUseContext().effectiveCwd() (SubagentExecutor Step 18 + withEffectiveCwd 透传),
        //     本处 setCwd 仅作 session 维度可观测性写入 (监控 / 调试), 不在生产路径消费.
        if (isolation != null || cwd != null) {
            Path explicitCwd = cwd == null ? null : Paths.get(cwd);
            Path effective = isolationResolver.resolve(isolation, explicitCwd, selectedAgent,
                    Paths.get(System.getProperty("user.dir")));
            String trackerKey = "tool-" + call.id();
            WorktreeCwdTracker.setCwd(trackerKey, effective);
            log.info("[SubagentTool] [Phase A 任务 4] effectiveCwd={} (isolation={}, cwd={}, agent={}, trackerKey={})",
                    effective, isolation, cwd, selectedAgent.agentType(), trackerKey);
        }

        // §14.3.3: setAgentColor（对齐 CC AgentTool.tsx:413-414 条件写：`if (selectedAgent.color)`）
        // [D4/D11] 路由到公共 setAgentColor(agentType, color) 单一真源（agentColorManager.ts:52-66）。
        //   缺省（agent 定义无预置色）→ 不调用 setAgentColor（CC 守卫），保留 map 既有色、不删旧色。
        //   [IMP-SUB-04 REWORK] 原无条件调用 + color.orElse(null) → setAgentColor null 语义删旧色，
        //   与 CC 守卫语义不符（CC 缺省时 map 条目保留）。
        if (selectedAgent.color().isPresent() && !selectedAgent.color().get().isEmpty()) {
            setAgentColor(selectedAgent.agentType(), selectedAgent.color().get());
        }

        // §14.3.4: getAgentModel — 解析 effective model（对齐 CC AgentTool.tsx:418）
        // [D12] 统一一条链：tool model 经 AgentModelResolver 完整解析链。CC AgentTool.tsx:418
        //   getAgentModel(selectedAgent.model, mainLoopModel, isForkPath ? undefined : model, permissionMode)
        //   —— 原实现 tool model 非空时直接用原始串，绕过 aliasMatchesParentTier（父档位继承防降级，
        //   issue #30815）+ parseUserSpecifiedModel；fork path tool model 恒 undefined（CC :418）。
        // [G-7] 透传父当前 permissionMode（CC AgentTool.tsx:418 4 参 getAgentModel）——
        //   'inherit' + plan 模式的 opusplan→Opus / haiku→Sonnet 升级由 AgentModelResolver 判定。
        String resolvedModel = getAgentModel(selectedAgent, isForkPath ? null : model,
            currentPermissionMode(ctx));
        // [D12 数据流日志] resolvedModel 是后续 executeSync/Async 注入 executor 的 effectiveModel——
        //   暴露 isForkPath/toolModel/permissionMode/resolvedModel 四元组，供线上排查 "子 agent 用了哪个模型"
        //   （CC AgentTool.tsx:418 resolvedAgentModel 同源）。
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] D12 模型解析: isForkPath={}, toolModel={}, permissionMode={}, resolvedModel={} "
                    + "(CC AgentTool.tsx:418 getAgentModel)",
                isForkPath, isForkPath ? null : model, currentPermissionMode(ctx), resolvedModel);
        }

        // [IMP-G4 B12] Subagent hard_metrics: tengu_agent_tool_selected · 对齐 CC AgentTool.tsx:419-428
        //   logEvent('tengu_agent_tool_selected', {agent_type, model, source, color, is_built_in_agent,
        //   is_resume:false, is_async:(run_in_background||background)&&!backgroundTasksDisabled, is_fork})。
        //   位置对齐 CC：setAgentColor + getAgentModel 之后（:413-418）、isolation/async 决策之前。
        //   is_async 用 CC :426 两字段公式（非完整 shouldRunAsync 公式），is_fork=isForkPath。
        if (analyticsTracker != null) {
            // [IMP-T REWORK] track(EventName,Map) 丢弃 metadata → 迁移 logEvent + verified() 包装
            //   （CC AgentTool.tsx:419-428 各 String 值均带 AnalyticsMetadata_I_VERIFIED 标记）。
            java.util.Map<String, Object> selectedProps = new java.util.LinkedHashMap<>();
            selectedProps.put("agent_type",
                com.nexusai.application.agent.api.AnalyticsTracker.verified(selectedAgent.agentType()));
            selectedProps.put("model",
                com.nexusai.application.agent.api.AnalyticsTracker.verified(resolvedModel != null ? resolvedModel : ""));
            selectedProps.put("source",
                com.nexusai.application.agent.api.AnalyticsTracker.verified(selectedAgent.source()));
            selectedProps.put("color",
                selectedAgent.color().map(com.nexusai.application.agent.api.AnalyticsTracker::verified).orElse(null));
            selectedProps.put("is_built_in_agent",
                com.nexusai.application.agent.subagent.BuiltInAgents.isBuiltIn(selectedAgent));
            selectedProps.put("is_resume", false);
            boolean agentBackgroundNow = selectedAgent.background().isPresent()
                && Boolean.TRUE.equals(selectedAgent.background().get());
            selectedProps.put("is_async",
                (runInBackground || agentBackgroundNow) && !backgroundTasksDisabled);
            selectedProps.put("is_fork", isForkPath);
            analyticsTracker.logEvent("tengu_agent_tool_selected", selectedProps);
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] [IMP-G4 B12] 发射 tengu_agent_tool_selected: agent_type={} "
                        + "model={} is_fork={} (CC AgentTool.tsx:419-428)",
                    selectedAgent.agentType(), resolvedModel, isForkPath);
            }
        }

        // §14.3.5: isInProcessTeammate + isTeammate 检查（对齐 CC AgentTool.tsx:274-282）
        // Team spawn 特性未启用（isAgentSwarmsEnabled = false），仅做进程内检查
        if (isInProcessTeammate()) {
            if (selectedAgent.background().isPresent() && Boolean.TRUE.equals(selectedAgent.background().get())) {
                return ToolResult.error(call.id(),
                    "In-process teammates cannot spawn background agents. Agent '"
                    + selectedAgent.agentType() + "' has background: true in its definition.");
            }
        }

        // §14.4 background 字段（对齐 CC AgentTool.tsx:426）
        boolean agentBackground = selectedAgent.background().isPresent()
            && Boolean.TRUE.equals(selectedAgent.background().get());
        // [Session E 返工 R-1] isAsync 决策委派 shouldRunAsync (CC AgentTool.tsx:553-567):
        //   forceAsync = isForkSubagentEnabled() = forkGateOn (:945) — fork gate 开启时
        //   所有 spawn 强制异步 (not just fork spawns — all of them), 与是否 fork 路径无关.
        //   旧实现 forceAsync = isForkPath 收窄了 CC 语义: 显式 subagent_type + gate on 时
        //   isForkPath=false 但 CC 仍强制异步; 且旧注释 "isForkPath=true ⟺ isForkSubagentEnabled()"
        //   等价声明不实, 已一并修正.
        //   [Session E 返工 F3] forkGateOn 已纳入 && !isBackgroundTasksDisabled 约束
        //   (CC :567 括号结构: forceAsync 在括号内受尾段短路) — D=true&&F=true 时
        //   fork 强制异步降级为同步, 与 run_in_background 同等待遇. 详见 shouldRunAsync.
        //   [P-AL-03 补全] shouldRunAsync 扩 8 参接入 CC :567 另三项:
        //   isCoordinator = isCoordinatorMode() (CC :553 feature('COORDINATOR_MODE') &&
        //   isEnvTruthy(CLAUDE_CODE_COORDINATOR_MODE); [D6] 收敛单一 CoordinatorMode bean,
        //   prompt()/spawn 同源) /
        //   assistantForceAsync = kairosEnabled 字段 (CC :566 feature('KAIROS') ?
        //   appState.kairosEnabled : false; 与 inputSchema cwd omit :801 同源) /
        //   proactiveActive = false 兜底 (CC :567 proactiveModule?.isProactiveActive() ?? false —
        //   Java 无 proactive 概念, CC 基线 74923950 无 src/proactive/ 目录, 模块恒 null → 项恒 false).
        //   [Re-think REWORK-1] shouldRunAsync 已移除 inProcessTeammate 项 (CC:567 公式无该项;
        //   R1 后 tool 线程 context 可见若保留会误伤 in-process teammate 的 fork 异步 spawn).
        boolean isAsync = shouldRunAsync(runInBackground, agentBackground,
            backgroundTasksDisabled, forkGateOn,
            isCoordinatorMode(),      // CC isCoordinator (AgentTool.tsx:553) — [D6] 单一 CoordinatorMode bean 源
            this.kairosEnabled,        // CC assistantForceAsync (AgentTool.tsx:566)
            false);                    // CC proactiveModule?.isProactiveActive() ?? false (:567) — Java N/A
        if (log.isDebugEnabled()) {
            // WHY: isAsync 决策可观测性 — 对齐 CC shouldRunAsync 全 6 项输入, 供线上排查
            // "子 agent 意外同步/异步" 时直接对照真值表定位 (CC AgentTool.tsx:567).
            log.debug("[SubagentTool] isAsync 决策 (CC AgentTool.tsx:567 shouldRunAsync): "
                    + "run_in_background={}, agent.background={}, isCoordinator={}, forceAsync(forkGate)={}, "
                    + "assistantForceAsync(KAIROS)={}, proactiveActive={}, "
                    + "backgroundTasksDisabled={} → isAsync={}",
                runInBackground, agentBackground, isCoordinatorMode(), forkGateOn,
                this.kairosEnabled, false, backgroundTasksDisabled, isAsync);
        }

        // [S3] fork 缓存共享参数装配 · 对齐 CC AgentTool.tsx:630 forkContextMessages:
        //   isForkPath ? toolUseContext.messages : undefined + :632 useExactTools (继承父 thinkingConfig).
        //   非 fork path 返回 null (向后兼容). forkParams 承载 assistantMessage + forkParentSystemPrompt,
        //   替换原独立参数透传 (SubagentExecutor 4 参 execute 签名).
        SubagentExecutor.ForkPathParams forkParams = buildForkParams(isForkPath, ctx,
            agentOptions, assistantMessage, forkParentSystemPrompt);

        // [RF-1] 从父 assistant message 提取 requestId（CC AgentTool.tsx:723/:778
        //   invokingRequestId: assistantMessage?.requestId）→ 透传给 SubagentExecutor.setInvokingRequestId
        //   覆盖 sync / async worker / 降级 sync 三构造点（fork 与显式 subagent 均适用）。
        String invokingRequestId = (assistantMessage instanceof ForkSubagentMessages.AssistantMessage a)
            ? a.requestId() : null;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] [RF-1] invokingRequestId 提取={} (CC AgentTool.tsx:723/:778)",
                invokingRequestId);
        }

        // §14.5 异步模式（对齐 CC AgentTool.tsx:686-764）
        // [Session E] effectiveIsolation 透传 (CC AgentTool.tsx:431 isolation ?? selectedAgent.isolation):
        //   fork AgentDefinition isolation 恒为空 → 仅 input.isolation="worktree" 时 executor
        //   Step 18 真创建 worktree + 注入 buildWorktreeNotice (CC AgentTool.tsx:590-602).
        // [IMP-SUB-28 A5] onProgress 透传 executeAsync（async worker 不转发父 onProgress，仅降级 sync
        //   路径接线 —— 对齐 CC async 路径返回 async_launched，进度走 task panel 而非父 onProgress）。
        if (isAsync) {
            // [IMP-G4 C7] name→agentId 注册需 name 输入透传（CC AgentTool.tsx:703-712 仅 async
            //   spawn 注册，sync 无 name 注册语义）
            return executeAsync(call.id(), prompt, description, selectedAgent, resolvedModel,
                // [冲突裁决·并集] HEAD=IMP-G4 C7 name（async spawn name→agentId 注册，CC AgentTool.tsx:703-712）；
                //   subagent_v3=IMP-SUB-28 A5 onProgress+ctx（降级 sync 流式 sink 接线，CC AgentTool.tsx:783-810）。
                //   两族互补（name 仅 async 语义 / onProgress 仅降级 sync 语义），顺序 name→onProgress→ctx 与
                //   executeAsync 签名一致。
                forkParams, currentCwd, isolation, invokingRequestId, name, onProgress, ctx);
        }

        // §14.6 同步模式（对齐 CC AgentTool.tsx:783-810）
        // [IMP-SUB-28 A5] onProgress 透传 executeSync → executeStreaming 接流式（原残余 sink=null）。
        return executeSync(call.id(), prompt, description, selectedAgent, resolvedModel,
            forkParams, currentCwd, isolation, invokingRequestId, onProgress, ctx);
    }

    /**
     * isAsync 决策纯函数 · 对齐 CC AgentTool.tsx:553-567 shouldRunAsync.
     *
     * <p>CC 真源 (AgentTool.tsx:567): {@code shouldRunAsync = (run_in_background === true
     * || selectedAgent.background === true || isCoordinator || forceAsync || assistantForceAsync
     * || proactiveActive) && !isBackgroundTasksDisabled} — 公式无 inProcessTeammate 项
     * (Re-think REWORK-1 删除, 见方法体).
     *
     * <p>Java 覆盖项 (Re-think REWORK-1 后 7 参 6 项):
     * <ul>
     *   <li>runInBackground — CC run_in_background (AgentTool.tsx:426)</li>
     *   <li>agentBackground — CC selectedAgent.background (AgentTool.tsx:426)</li>
     *   <li>isCoordinator — CC :553 {@code feature('COORDINATOR_MODE') ? isEnvTruthy(
     *       CLAUDE_CODE_COORDINATOR_MODE) : false}（与 coordinatorMode.ts:36-41
     *       isCoordinatorMode() 同式）; [D6] Java 由 {@link #isCoordinatorMode()} helper
     *       （单一 CoordinatorMode bean 源，prompt()/spawn 同源）承载</li>
     *   <li>forkGateOn — CC forceAsync = isForkSubagentEnabled() (AgentTool.tsx:557;
     *       forkSubagent.ts:32-39 = feature && !coordinator && !nonInteractive)</li>
     *   <li>assistantForceAsync — CC :566 {@code feature('KAIROS') ? appState.kairosEnabled
     *       : false}; Java 由 kairosEnabled 字段承载 (inputSchema cwd omit :801 同源)</li>
     *   <li>proactiveActive — CC :567 {@code proactiveModule?.isProactiveActive() ?? false};
     *       Java 无 proactive 概念 (CC 基线 74923950 无 src/proactive/ 目录, 模块恒 null →
     *       项恒 false), 生产调用方传 false 兜底, 登记 N/A</li>
     * </ul>
     * 6 项全在括号内, 同受尾段 {@code && !isBackgroundTasksDisabled} 短路
     * (CC :567 括号结构; F3 修正 2026-08-05: E#2 旧实现 {@code isAsync = isAsync || forkGateOn}
     * 使 forkGateOn 脱离该约束, D=true&&F=true 组合与 CC 相反, 已修正为括号内联).
     *
     * @param runInBackground         CC original: run_in_background (AgentTool.tsx:426)
     * @param agentBackground         CC original: selectedAgent.background (AgentTool.tsx:426)
     * @param backgroundTasksDisabled CC original: isBackgroundTasksDisabled (AgentTool.tsx:567)
     * @param forkGateOn              CC original: forceAsync = isForkSubagentEnabled()
     *                                (AgentTool.tsx:557; forkSubagent.ts:32-39 =
     *                                feature && !coordinator && !nonInteractive)
     * @param isCoordinator           CC original: isCoordinator (AgentTool.tsx:553)
     * @param assistantForceAsync     CC original: assistantForceAsync (AgentTool.tsx:566)
     * @param proactiveActive         CC original: proactiveModule?.isProactiveActive() ?? false
     *                                (AgentTool.tsx:567)
     * @return 是否异步执行子 agent
     */
    static boolean shouldRunAsync(boolean runInBackground, boolean agentBackground,
            boolean backgroundTasksDisabled, boolean forkGateOn,
            boolean isCoordinator, boolean assistantForceAsync, boolean proactiveActive) {
        // 对齐 CC AgentTool.tsx:567 括号结构: (A||B||C||F||G||P) && !D —
        //   forkGateOn(=forceAsync, CC :557 isForkSubagentEnabled()) 与 isCoordinator /
        //   assistantForceAsync / proactiveActive 均在括号内, 同受尾段 && !isBackgroundTasksDisabled
        //   短路: 后台任务被禁用时所有异步触发器一律降级为同步执行 (CC :66-68 env
        //   CLAUDE_CODE_DISABLE_BACKGROUND_TASKS + :122 run_in_background omit 同源开关).
        //   [F3 修正] E#2 曾实现为 isAsync = ((A||B)&&!E&&!D) || F — forkGateOn 脱离
        //   !D 约束, D=true&&F=true 全 4 组合 Java=异步 / CC=同步 (reflection E-E-R05
        //   真值表 4 差异组合) → 现改为括号内联, 与 CC 真值表逐组合一致.
        //   [P-AL-03 补全] isCoordinator (CC :553) / assistantForceAsync (CC :566) /
        //   proactiveActive (CC :567) 同处括号内, 受 !D 短路 — 补全前 Java 缺这三项,
        //   coordinator 模式或 KAIROS 开启时 spawn 仍同步, 与 CC 相反.
        //   [Re-think REWORK-1] 删除 inProcessTeammate 项: CC:567 公式无该项 (grep -n 自验)。
        //   R1 前 tool 执行线程 context 恒 null → 该项 no-op; R1 线程传播后 context 可见 →
        //   该项恒 false, 误伤 in-process teammate 的 fork 异步 spawn (CC forceAsync
        //   对 teammate 亦生效, 无排除项). 后台两条路径已由 CC:279 守卫 (:1196
        //   run_in_background=true) + §14.3.5 (:1344 selectedAgent.background=true)
        //   先于本函数拦截 (throw), 删除该项不影响后台拦截.
        return (runInBackground || agentBackground || isCoordinator || forkGateOn
                || assistantForceAsync || proactiveActive)
            && !backgroundTasksDisabled;
    }

    /**
     * [S3] fork 缓存共享参数装配 · 对齐 CC AgentTool.tsx:630 forkContextMessages:
     * {@code isForkPath ? toolUseContext.messages : undefined} + :632 useExactTools
     * (继承父 thinkingConfig).
     *
     * <p>WHY: fork path 时把 fork 专属上下文封装成 {@link SubagentExecutor.ForkPathParams}
     * 传入 SubagentExecutor (4 参 execute). 非 fork path 返回 null (向后兼容
     * executeForkedSkill). parentThinkingConfig 取自
     * {@code agentOptions.thinkingConfig()} (父 AgentOptions, 对齐 CC runAgent.ts:682-683
     * {@code toolUseContext.options.thinkingConfig}). 决策 S3-6 B: 经 ForkPathParams 直带,
     * 绕开 LlmAgentLoop.buildSubagentAgentOptions 硬编码 null.
     *
     * @param isForkPath         fork 路径标志 (CC AgentTool.tsx:323 effectiveType==null)
     * @param ctx                当前 turn 的 ToolUseContext (forkContextMessages 来源)
     * @param agentOptions       父 AgentOptions (parentThinkingConfig 来源)
     * @param assistantMessage   父 assistant message (buildForkedMessages 入参, CC AgentTool.tsx:512)
     * @param forkParentSystemPrompt 父 rendered system prompt 字节 (CC AgentTool.tsx:493-497 + runAgent.ts:508-509)
     * @return ForkPathParams 或 null (非 fork path)
     */
    private SubagentExecutor.ForkPathParams buildForkParams(boolean isForkPath, ToolUseContext ctx,
            AgentOptions agentOptions, ForkSubagentMessages.Message assistantMessage,
            String forkParentSystemPrompt) {
        if (!isForkPath) {
            return null;
        }
        // forkContextMessages = toolUseContext.messages (CC AgentTool.tsx:630) — 父对话历史
        List<?> forkContextMessages = ctx != null && ctx.messages() != null
            ? ctx.messages()
            : List.of();
        // parentThinkingConfig = 父 AgentOptions.thinkingConfig (CC runAgent.ts:682-683)
        Object parentThinkingConfig = agentOptions != null ? agentOptions.thinkingConfig() : null;
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] buildForkParams: forkContextMessages.size={}, "
                    + "parentThinkingConfig={}, forkParentSystemPrompt.length={}, assistantMessage={}",
                forkContextMessages.size(),
                parentThinkingConfig != null ? "已继承" : "null",
                forkParentSystemPrompt == null ? 0 : forkParentSystemPrompt.length(),
                assistantMessage != null ? "已透传" : "null");
        }
        return new SubagentExecutor.ForkPathParams(
            assistantMessage, forkContextMessages, forkParentSystemPrompt, parentThinkingConfig);
    }

    /**
     * 过滤被拒绝的 Agent · 对齐 CC filterDeniedAgents (AgentTool.tsx:342-344)
     *
     * <p>s06 P1-3 修补: 委托给 {@link PermissionBubbleService#filterDeniedAgents},
     * 该服务已有真实 L1 实现 (PermissionBubbleService.java:155-163).
     *
     * <p>Phase 2 PR 1: 接受 per-call {@code permCtx} 参数, 替换原 volatile {@code currentPermissionContext}
     * 字段 + setter 反模式. 调用路径: {@link #execute(ToolUseBlock, ToolUseContext, java.util.function.Consumer)}
     * 从 ctx.permissionContext() 提取 → 透传到本方法.
     *
     * <p>行为:
     * <ul>
     *   <li>{@code permissionBubbleService == null} 或 {@code permCtx == null}
     *       → fallback 返回原列表 (向后兼容 Spring 未注入 / caller 未传 ctx)</li>
     *   <li>正常路径 → 把 List&lt;AgentDefinition&gt; map 成 List&lt;String&gt; agentType,
     *       调用 PermissionBubbleService.filterDeniedAgents(ids, ctx) 过滤, 再 map 回 AgentDefinition</li>
     * </ul>
     */
    /**
     * [Session E] 从 ToolUseContext.messages() 抽取所有 user 消息的文本块 · 对齐 CC
     * forkSubagent.ts:83-95 isInForkChild 输入语义.
     *
     * <p>WHY: CC {@code toolUseContext.messages} 是完整对话历史 (含 user / assistant / tool 角色).
     * ForkSubagent.isInForkChild 只关心 user 消息的 text block 是否有 {@code <fork-boilerplate>} 标签,
     * 因为 fork boilerplate 总是注入在 fork child 第一条 user message 里 (CC forkSubagent.ts:158-166).
     *
     * <p>Java 端 ctx.messages() 当前类型 {@code List<?>} (CC 端 BetaBlock[]).
     * 此处安全降级: 不识别的消息类型 → 返回空 List (不会误判递归).
     *
     * @param ctx 当前 turn 的 ToolUseContext (可为 null)
     * @return user 消息的纯文本内容列表 (按出现顺序)
     */
    static List<String> collectUserTextBlocks(ToolUseContext ctx) {
        if (ctx == null || ctx.messages() == null || ctx.messages().isEmpty()) {
            return List.of();
        }
        List<String> userTextBlocks = new ArrayList<>();
        for (Object m : ctx.messages()) {
            if (m == null) continue;
            // 简单启发式: 任何含 getContent() 方法的对象 → 取 content
            // Java 端 messages 类型不固定 (List<?>), 仅做尽力而为的字符串提取
            try {
                java.lang.reflect.Method getContent = m.getClass().getMethod("getContent");
                Object content = getContent.invoke(m);
                if (content != null) {
                    userTextBlocks.add(content.toString());
                }
            } catch (Exception ignored) {
                // 不识别的消息类型 — 跳过
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] collectUserTextBlocks: 抽取 {} 条 user 文本块", userTextBlocks.size());
        }
        return userTextBlocks;
    }

    /**
     * [Session J 方案 A] 将 caller 独立透传的 assistantMessage 收窄为 fork assistant 消息.
     *
     * @param assistantMessage AgentTool.call 独立参数 (可为 null)
     * @return ForkSubagentMessages.AssistantMessage 或 null
     */
    static ForkSubagentMessages.AssistantMessage toForkAssistantMessage(
            ForkSubagentMessages.Message assistantMessage) {
        if (assistantMessage instanceof ForkSubagentMessages.AssistantMessage assistant) {
            return assistant;
        }
        return null;
    }

    private List<AgentDefinition> filterDeniedAgents(List<AgentDefinition> agents, ToolPermissionContext permCtx) {
        if (permissionBubbleService == null || permCtx == null) {
            // Fallback: 无 ctx 或无 service → 返回全部 (向后兼容, 与 s06 P1-3 修补前的 TODO 行为一致)
            return new ArrayList<>(agents);
        }
        if (permissionBubbleService == null || permCtx == null) {
            // Fallback: 无 ctx 或无 service → 返回全部 (向后兼容, 与 s06 P1-3 修补前的 TODO 行为一致)
            return new ArrayList<>(agents);
        }
        // CC: agents.filter(agent => !isAgentDenied(appState.toolPermissionContext, AGENT_TOOL_NAME, agent.agentType))
        List<String> agentIds = agents.stream().map(AgentDefinition::agentType).toList();
        List<String> allowedIds = permissionBubbleService.filterDeniedAgents(agentIds, permCtx);
        Set<String> allowedSet = new HashSet<>(allowedIds);
        return agents.stream()
                .filter(a -> allowedSet.contains(a.agentType()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * 获取 Agent 的 deny rule 来源 · 对齐 CC getDenyRuleForAgent (AgentTool.tsx:350)
     *
     * <p>s06 P1-3 修补: 委托给 {@link PermissionBubbleService#getDenyRuleForAgent}.
     *
     * <p>Phase 2 PR 1: 接受 per-call {@code permCtx} 参数.
     *
     * @param agentType agent type 名称
     * @param permCtx   当前 execute 调用的权限上下文 (可为 null, null 时返回 null)
     * @return deny rule 来源名 (如 "SESSION"), 无匹配返回 null
     */
    private String getDenyRuleForAgent(String agentType, ToolPermissionContext permCtx) {
        if (permissionBubbleService == null || permCtx == null) {
            return null;
        }
        // CC: getDenyRuleForAgent(appState.toolPermissionContext, AGENT_TOOL_NAME, agentType)
        PermissionRule rule = permissionBubbleService.getDenyRuleForAgent(agentType, permCtx);
        return rule != null ? rule.source().name() : null;
    }

    /**
     * 检查 Agent 所需的 MCP servers 是否都可用 · 对齐 CC hasRequiredMcpServers (AgentTool.tsx:406)
     *
     * <p>CC: 检查 requiredMcpServers 中的每个 pattern 是否在 serversWithTools 中有匹配。
     * 当前 MCP tools/list 未集成（getTools() 返回空），返回 true 避免阻断 agent 启动。
     */
    private boolean hasRequiredMcpServers(AgentDefinition agent) {
        if (agent.requiredMcpServers().isEmpty() || agent.requiredMcpServers().get().isEmpty()) {
            return true;
        }
        // CC: requiredMcpServers.some(pattern => !serversWithTools.some(server => server.includes(pattern)))
        List<String> serversWithTools = getMcpServersWithTools();
        for (String pattern : agent.requiredMcpServers().get()) {
            boolean found = serversWithTools.stream()
                    .anyMatch(server -> server.toLowerCase().contains(pattern.toLowerCase()));
            if (!found) {
                if (log.isDebugEnabled()) {
                    log.debug("MCP server '{}' required by agent '{}' has no tools available",
                            pattern, agent.agentType());
                }
                return false;
            }
        }
        return true;
    }

    /**
     * 获取缺失的 MCP server patterns · 对齐 CC AgentTool.tsx:407
     */
    private List<String> getMissingMcpServers(AgentDefinition agent) {
        if (agent.requiredMcpServers().isEmpty() || agent.requiredMcpServers().get().isEmpty()) {
            return List.of();
        }
        List<String> serversWithTools = getMcpServersWithTools();
        List<String> missing = new ArrayList<>();
        for (String pattern : agent.requiredMcpServers().get()) {
            boolean found = serversWithTools.stream()
                    .anyMatch(server -> server.toLowerCase().contains(pattern.toLowerCase()));
            if (!found) missing.add(pattern);
        }
        return missing;
    }

    /**
     * 获取有 tools 的 MCP server 列表 · 对齐 CC AgentTool.tsx:395-404
     *
     * <p>Phase 7 真实化: 从 {@link McpToolPool#getServersWithTools()} 提取当前已连接
     * 且至少注册 1 个 tool 的 server name。McpToolPool 未注入时 (单测/早期启动)
     * 返回 List.of, 保留原 TODO 行为.
     *
     * <p>[F3 rework] 不再用 {@link McpToolPool#activeServers()}：该集已合并 degraded
     * （failed/needs-auth），会把无工具降级 server 视为 'servers with tools'，使 required-MCP
     * 门控（hasRequiredMcpServers/getMissingMcpServers/filterAgentsByMcpRequirements）错误放行。
     * CC 的 serversWithTools 仅含注册了 {@code mcp__} 工具的 server（连接且已注册工具）。
     */
    private List<String> getMcpServersWithTools() {
        if (mcpToolPool == null) {
            return List.of();
        }
        return mcpToolPool.getServersWithTools();
    }

    /**
     * 读取 Agent 主题色 · CC original: getAgentColor (agentColorManager.ts:36-50)。
     *
     * <p>读侧（D11）：general-purpose → undefined；agentColorMap 命中且颜色在 {@link #AGENT_COLORS}
     * 清单内 → 返回主题色 key（如 "red_FOR_SUBAGENTS_ONLY"）；否则 undefined。
     *
     * <p>WHY（探查 GAP-5/S5/U14/U16）：旧 Java agentColors 只写不读，颜色不进入任何
     * UI/事件/DTO。本方法补齐 CC agentColorManager.ts:36-50 读侧真源；web 后端当前无
     * 对应 UI 消费通道（CC 消费方为 AgentDetail.tsx:40 / useSwarmBanner.ts:120 /
     * UI.tsx:696,786 等前端组件）——【IMP-SUB-04 REWORK】显式登记为"数据源就位、待前端
     * 消费"（经未来 API/DTO 暴露），不再声称"前端可经本方法展示"。
     *
     * @param agentType agent type（可为 null）
     * @return 主题色 key；general-purpose / 未命中 / 非法色 → null
     */
    public String getAgentColor(String agentType) {
        if (agentType == null || BuiltInAgents.GENERAL_PURPOSE.equals(agentType)) {
            return null;
        }
        String existingColor = agentColors.get(agentType);
        if (existingColor != null && AGENT_COLORS.contains(existingColor)) {
            return AGENT_COLOR_TO_THEME_COLOR.get(existingColor);
        }
        return null;
    }

    /**
     * 读取 agent 颜色注册表 · CC original: getAgentColorMap (bootstrap/state.ts:1128-1130)。
     *
     * <p>读侧（D11）：返回当前 {@code agentType → 颜色} 注册表（不可变视图，读侧统一入口）。
     * 【IMP-SUB-04 REWORK】当前无生产消费方（AgentsHandler/AgentColorCommand 均未调用本
     * getter）——与 {@link #getAgentColor} 一同显式登记为"数据源就位、待前端消费"。
     *
     * @return agentType → 颜色 的只读视图
     */
    public Map<String, String> getAgentColorMap() {
        return Collections.unmodifiableMap(agentColors);
    }

    /**
     * 设置 Agent 颜色 · 对齐 CC setAgentColor (agentColorManager.ts:52-66)。
     *
     * <p>CC 语义：color 为空（undefined / ''）→ 删除该 agentType 的颜色（CC `if (!color)`
     * agentColorManager.ts:58）；合法色（在 AGENT_COLORS 清单内）→ 写入；非法色 → 忽略（不写）。
     * 空白串（如 " "）非空 → 走 {@code AGENT_COLORS.includes} 判非法 → 忽略（不删、不写），
     * 对齐 CC truthy 分支 [IMP-SUB-04 REWORK：isBlank→isEmpty，CC `!color` 只把空串视为删除]。
     * 与旧 {@code if (selectedAgent.color)} 写路径（AgentTool.tsx:413-414）合并为单一真源
     * （去重②/③，探查 △-4/△-5 只写不读）。
     *
     * @param agentType agent type（null → no-op）
     * @param color     颜色名（null/空串 → 删除，对齐 CC `!color`；空白串/非法色 → 忽略）
     */
    public void setAgentColor(String agentType, String color) {
        if (agentType == null) {
            return;
        }
        if (color == null || color.isEmpty()) {
            agentColors.remove(agentType);
            if (log.isDebugEnabled()) {
                log.debug("Remove agent color: type={}", agentType);
            }
            return;
        }
        if (AGENT_COLORS.contains(color)) {
            agentColors.put(agentType, color);
            if (log.isDebugEnabled()) {
                log.debug("Set agent color: type={} color={}", agentType, color);
            }
        }
    }

    /**
     * 解析 agent 模型 · 对齐 CC getAgentModel (utils/model/agent.ts:37-95)
     *
     * <p>[H7-arch Phase 5 P5 D1] 原 2 行实现（agent.model().orElse(defaultModel)）严重简化，
     * 缺失 CLAUDE_CODE_SUBAGENT_MODEL env / 'inherit' 运行时解析 / aliasMatchesParentTier。
     * 现委托 {@link AgentModelResolver} 完整解析链。parentModel 取主循环当前模型（mainLoop.getModelForCall()），
     * 不可用回落到 defaultModel。
     *
     * <p>[D12] 新增 toolSpecifiedModel 参数：CC AgentTool.tsx:418 / runAgent.ts:340 把 tool model
     * （sonnet/opus/haiku）传入 getAgentModel 走完整解析链（aliasMatchesParentTier 父档位继承防降级
     * + parseUserSpecifiedModel），而非直接使用原始串。fork path 由调用方传 null
     * （CC :418 {@code isForkPath ? undefined : model}）。
     *
     * <p>[G-7] 新增 permissionMode 参数：CC AgentTool.tsx:418 / runAgent.ts:340 把
     * {@code appState.toolPermissionContext.mode} 透传 getAgentModel → 'inherit' 分支
     * getRuntimeMainLoopModel 的 plan-mode 升级（opusplan+plan→Opus / haiku+plan→Sonnet，
     * model.ts:145-167）。Java 等价 = 父上下文当前 PermissionMode（{@link #currentPermissionMode}）。
     */
    private String getAgentModel(AgentDefinition agent, String toolSpecifiedModel, PermissionMode permissionMode) {
        String parentModel = resolveParentModel();
        return com.nexusai.application.agent.subagent.AgentModelResolver.resolve(
            agent.model().orElse(null), parentModel, toolSpecifiedModel,
            permissionMode == null ? null : permissionMode.name(),
            settingsSubagentModelId());
    }

    /**
     * 父上下文当前权限模式 · 对齐 CC {@code appState.toolPermissionContext.mode}
     * （AgentTool.tsx:256 / runAgent.ts:333）→ 透传 {@link AgentModelResolver} plan-mode 升级判断。
     *
     * <p>优先 {@code permissionContext().mode()}（当前 mode，ToolPermissionContext 字段），兜底
     * {@code ctx.permissionMode()}（构造器注入的父模式，SubagentExecutor:3844 parentMode 同源）；
     * ctx 为 null（旧 execute 路径/测试）→ null（等价 CC permissionMode undefined → 'default'，无升级）。
     */
    private static PermissionMode currentPermissionMode(ToolUseContext ctx) {
        if (ctx == null) {
            return null;
        }
        if (ctx.permissionContext() != null) {
            return ctx.permissionContext().mode();
        }
        return ctx.permissionMode();
    }

    /**
     * [W2-2] 读取 settings.subagentModelName（DB subagent 档位模型，[FN2] 存全名/裸名）· 供
     * {@link AgentModelResolver} 解析链 DB 优先（env 路 W4 清理）。
     *
     * <p>settingsMapper 未注入（测试/手动直构）/ 读取异常 → 吞并返回 null（回落 env/原解析链），
     * 不向上传播进 async 线程（对齐 ChatService.resolveSettingsModelName 防御模式）。
     *
     * @return settings.subagentModelName；未配置 / 未注入 / 异常 → null
     */
    private String settingsSubagentModelId() {
        try {
            if (settingsMapper == null) {
                return null;
            }
            com.nexusai.repository.settings.entity.SettingsRecord s =
                settingsMapper.selectOneById(SETTINGS_SINGLETON_ID);
            String subagentRaw = s != null ? s.getSubagentModelName() : null;
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] settings.subagentModelName 读取: {} (W2-2 DB 优先)", subagentRaw);
            }
            return subagentRaw;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] settings.subagentModelName 读取异常，回落 null: {}", e.toString());
            }
            return null;
        }
    }

    /**
     * 主循环当前模型（CC toolUseContext.options.mainLoopModel 语义）。
     *
     * <p>mainLoop 为 null（单测/无循环场景）或 getModelForCall 返回 null（回落到 caller-fallback）
     * 时用 defaultModel 兜底，保持向后兼容。
     */
    private String resolveParentModel() {
        if (mainLoop != null) {
            String m = mainLoop.getModelForCall();
            if (m != null && !m.isBlank()) {
                return m;
            }
        }
        return defaultModel;
    }

    /**
     * 是否为进程内队友 · 对齐 CC utils/teammateContext.ts:70-74 isInProcessTeammate
     * （{@code teammateContextStorage.getStore() !== undefined}）。
     *
     * <p>Java 等价：{@link TeammateContext#isInProcessTeammate()}（ThreadLocal CURRENT 非空）。
     * leader（主会话）线程 CURRENT 未设 → false；teammate 线程由 runWithTeammateContext
     * 设置（GAP-02 接线后生效，CC :279/:361 守卫自动就位）。
     */
    private boolean isInProcessTeammate() {
        return TeammateContext.isInProcessTeammate();
    }

    /**
     * 是否运行在 teammate 上下文 · 对齐 CC utils/teammate.ts:125-137 isTeammate。
     *
     * <p>RF-3 ② 双实现统一：本方法此前自行复制 {@link Teammate#isTeammate()} 逻辑（in-process
     * ThreadLocal + sysprop 双条件），与 {@link Teammate} 存在双实现漂移（sysprop 回退在
     * {@link Teammate#isTeammate()} 已随启动接线移除，此处若不同步会造成身份判定分叉）。
     * 现统一委托 {@link Teammate#isTeammate()}（单一真源，CC teammate.ts:125-131）。
     */
    private boolean isTeammate() {
        return Teammate.isTeammate();
    }

    /**
     * CC :279 守卫的 teamName 解析 · 对齐 AgentTool.tsx:1388-1396 resolveTeamName
     * {@code input.team_name || appState.teamContext?.teamName}。
     *
     * <p>input.team_name 由调用方 {@code resolvedTeamName}（swarms 启用时的 team_name）承载；
     * appState.teamContext?.teamName 的 Java 等价 = {@link TeammateContext} 的 teamName
     * （SpawnInProcess.java:211 以 config.teamName() 构造；R1 线程传播后工具执行线程
     * 同线程可见）。team_name 缺省但处于 in-process teammate 上下文时，从 teammate
     * 上下文取 teamName — 使无 name 后台 spawn（CC:279 真触发场景）也能命中守卫。
     *
     * @param resolvedTeamName input.team_name 解析结果（swarms 关闭时恒 null）
     * @return 守卫用 teamName（null = 无 teamName，守卫不触发）
     */
    private String guardTeammateTeamName(String resolvedTeamName) {
        if (resolvedTeamName != null) {
            return resolvedTeamName;
        }
        TeammateContext teammateCtx = TeammateContext.getTeammateContext();
        return teammateCtx != null ? teammateCtx.getData().teamName() : null;
    }

    /**
     * [W8-GAP-01] teammate spawn · 对齐 CC AgentTool.tsx:290-308 spawnTeammate 调用 +
     * spawnMultiAgent.ts:840-940 handleSpawnInProcess（Java 恒 in-process）。
     *
     * <p>入参对齐 CC :290-299：name/prompt/team_name/plan_mode_required(mode=='plan')/
     * model(model ?? agentDef.model)/agent_type(subagent_type)。返回对齐 :302-308：
     * {@code {status:'teammate_spawned', prompt, ...result.data}}（data 对齐
     * handleSpawnInProcess :1018-1030 teammate_id/agent_id/.../is_splitpane:false/
     * plan_mode_required）。
     *
     * @param call         当前 tool_use 块（toolUseId）
     * @param ctx          当前 turn 上下文（parentSessionId 来源，CC :125 getSessionId）
     * @param name         teammate 显示名（CC input.name）
     * @param teamName     团队名（CC resolveTeamName 结果）
     * @param prompt       初始任务（CC input.prompt）
     * @param mode         权限模式（CC input.mode → spawnMode，:296 plan_mode_required）
     * @param subagentType agent 类型（CC input.subagent_type，:284 agentDef 查找）
     * @param model        模型覆盖（CC input.model）
     * @return teammate_spawned 结果或 error
     */
    private ToolResult spawnTeammate(ToolUseBlock call, ToolUseContext ctx, String name,
            String teamName, String prompt, String mode, String subagentType, String model) {
        if (spawnInProcess == null) {
            log.error("[SubagentTool] teammate spawn 但 SpawnInProcess 未注入 (fail loud): "
                + "name={} team={}", name, teamName);
            return ToolResult.error(call.id(),
                "SpawnInProcess not injected; cannot spawn in-process teammate.");
        }
        // CC AgentTool.tsx:284-285: agentDef = subagent_type ?
        //   activeAgents.find(a => a.agentType === subagent_type) : undefined
        // [C-方案3][DEC-C-02] per-session 视图：agent-defs 从会话项目载入（对齐 CC memoize(cwd)）
        AgentDefinition agentDef = subagentType != null
            ? registryFor(sessionCwdFor(ctx)).findAgent(subagentType) : null;
        // CC :296 plan_mode_required = spawnMode === 'plan'（spawnMode = input.mode，:247）
        boolean planModeRequired = "plan".equals(mode);
        // CC :297 model = model ?? agentDef?.model
        String resolvedModel = model != null ? model
            : (agentDef != null ? agentDef.model().orElse(null) : null);
        // CC :285-288 setAgentColor(subagent_type, agentDef.color)（分组 UI 显示用）
        //   对齐 CC `if (agentDef?.color)` 守卫（AgentTool.tsx:287-288）：缺省（agentDef 无预置色）
        //   → 不调用 setAgentColor，保留 map 既有色、不删旧色。
        //   [IMP-SUB-04 REWORK] 原无条件调用 + color=null → setAgentColor null 语义删旧色，
        //   与 CC 守卫语义不符（反思曾断言 :2244 守卫等价，实读 CC AgentTool.tsx:287-288 为条件写）。
        String color = agentDef != null && agentDef.color().isPresent()
            ? agentDef.color().get() : null;
        if (color != null && !color.isEmpty()) {
            // [D4/D11] 路由到公共 setAgentColor(agentType, color) 单一真源（agentColorManager.ts:52-66）
            setAgentColor(subagentType, color);
        }
        String parentSessionId = ctx != null && ctx.sessionId() != null
            ? ctx.sessionId() : null;
        // [Batch2 S1] cwd = 当前会话 cwd（对齐 CC spawnMultiAgent.ts:337 workingDir = cwd || getCwd()，
        //   落盘 config.json members.cwd）；无 sessionId 回落 user.dir。
        String spawnCwd = CwdResolution.getCwd(parentSessionId);
        if (spawnCwd == null || spawnCwd.isBlank()) {
            spawnCwd = System.getProperty("user.dir", ".");
        }
        SpawnInProcess.InProcessSpawnConfig config = new SpawnInProcess.InProcessSpawnConfig(
            name, teamName, prompt, color, planModeRequired, resolvedModel, subagentType, spawnCwd);
        SpawnInProcess.SpawnContext spawnCtx = new SpawnInProcess.SpawnContext(parentSessionId, call.id());
        SpawnInProcess.InProcessSpawnOutput out = spawnInProcess.spawnInProcessTeammate(config, spawnCtx);
        if (!out.success()) {
            String err = out.error() != null ? out.error() : "unknown error";
            log.warn("[SubagentTool] teammate spawn 失败: name={} team={} error={}", name, teamName, err);
            return ToolResult.error(call.id(), "Failed to spawn in-process teammate: " + err);
        }
        // 返回对齐 CC AgentTool.tsx:302-308 + spawnMultiAgent.ts:1018-1030（in-process data 形状）
        ObjectNode data = JSON.createObjectNode();
        data.put("status", "teammate_spawned");
        data.put("prompt", prompt);
        data.put("teammate_id", out.agentId());
        data.put("agent_id", out.agentId());
        if (subagentType != null) data.put("agent_type", subagentType);
        if (resolvedModel != null) data.put("model", resolvedModel);
        data.put("name", name);
        if (color != null && !color.isBlank()) data.put("color", color);
        data.put("tmux_session_name", "in-process");
        data.put("tmux_window_name", "in-process");
        data.put("tmux_pane_id", "in-process");
        data.put("team_name", teamName);
        data.put("is_splitpane", false);
        data.put("plan_mode_required", planModeRequired);
        if (out.taskId() != null) data.put("task_id", out.taskId());
        log.info("[SubagentTool] teammate spawned: agentId={} team={} taskId={}",
            out.agentId(), teamName, out.taskId());
        return ToolResult.success(call.id(), data);
    }

    /**
     * 同步执行（对齐 CC AgentTool.tsx:783-810）
     *
     * @param effectiveIsolation CC original: effectiveIsolation (AgentTool.tsx:431) —
     *                           input.isolation 透传给 SubagentExecutor Step 18 (worktree 创建决策)
     * @param onProgress 父 caller 进度回调（StreamingToolExecutor 注入；null = 非流式）。
     *                   [IMP-SUB-28 A5] 非 null 时经 {@link #buildSyncStreamingSink} 转
     *                   {@code Consumer<SubagentMessage>} 传给 {@code executeStreaming} —— 原残余
     *                   {@code executor.execute(...)} 内部 sink=null 不达父 caller（WF1-01 D18-D22）。
     * @param parentCtx  当前 turn 的父 ToolUseContext（D21 setResponseLength 累加源；可为 null）
     */
    private ToolResult executeSync(String toolUseId, String prompt, String description,
                                    AgentDefinition selectedAgent, String model,
                                    SubagentExecutor.ForkPathParams forkParams,
                                    String currentCwd,
                                    String effectiveIsolation,
                                    String invokingRequestId,
                                    java.util.function.Consumer<Tool.ToolProgress> onProgress,
                                    ToolUseContext parentCtx) {
        // 创建子 Agent 工具池 (4-SET 过滤; sync 路径 isAsync=false 不过 async 白名单)
        ToolRegistry subagentToolRegistry = createSubagentToolRegistry(selectedAgent, false);

        // [C-方案3][DEC-C-02] per-session agent-defs 视图：一会话一项目 → 子 agent 解析用会话项目表
        //   （对齐 CC runAgent.ts:687 agentDefinitions 继承父启动期表；parentCtx 当前 turn 父 ctx）
        AgentDefinitionRegistry reg = registryFor(sessionCwdFor(parentCtx));

        // 创建 SubagentExecutor（方案 1：复用 LlmAgentLoop）
        String effectiveSystemPrompt = getEffectiveSystemPrompt(selectedAgent);
        // s06 P2-2 修补: 从 mainLoop 提取 parentToolUseContext (对齐 CC AgentTool.tsx:700)
        // 之前 audit 偏差: parentTUC 硬编码 null, 子 Agent 完全独立于父 (cold start)
        // [2026-08-26 父TUC 修复] parentCtx（executeSync/Async 从 doExecute 的 ctx 透传 =
        //   StreamingToolExecutor:1906 tool.execute(call, ctx) 的当前轮 TUC）恒可用且含 sessionId/
        //   permissionMode；旧 mainLoop.getCurrentToolUseContext() 依赖 setMainLoop 注入（全项目无接线，
        //   恒 null）→ 子代理恒 standalone（permissionMode=DEFAULT）→ 权限 ask 推子代理会话卡住。
        ToolUseContext parentTUC = parentCtx != null
                ? parentCtx
                : (mainLoop != null ? mainLoop.getCurrentToolUseContext() : null);
        // [2026-08-26 父TUC 确认端点] 日志确认父 TUC 是否拿到（用户要求必须拿到）——
        //   null 时子代理走 standalone（permissionMode=DEFAULT）→ 子代理 Bash 权限 ask 卡住
        if (log.isInfoEnabled()) {
            log.info("[SubagentTool] 父 TUC 提取端点: mainLoop注入={} parentTUC={} sessionId={} permissionMode={}",
                mainLoop != null,
                parentTUC != null,
                parentTUC != null && parentTUC.sessionId() != null ? parentTUC.sessionId() : "null",
                parentTUC != null ? String.valueOf(parentTUC.permissionMode()) : "null(无父TUC)");
        }
        SubagentExecutor executor = new SubagentExecutor(
            subagentToolRegistry, hookRegistry, mainLoop,
            llmProviderFactory, effectiveProviderConfig(model != null ? model : defaultModel),
            model != null ? model : defaultModel,
            effectiveSystemPrompt,
            parentTUC
        );
        // [IMP-SP2-08] PROMPT_CACHE_BREAK_DETECTION 门注入 → finally cleanupAgentTracking 真实接线
        //   （对齐 CC runAgent.ts:824-826；null-safe：未注入 → feature 恒关 = 默认关行为）
        executor.setFeatureFlags(featureFlags);
        // [FIX-AM REQ-M-19] 生产接线：注入自定义 agent 解析器 + agent-memory 目录 →
        //   resolveAgentDefinition 命中自定义 memory agent（CC AgentTool.tsx:286 activeAgents.find）
        //   + buildAgentSystemPrompt(:1854) 生成期真实注入 agentMemoryPrompt（CC loadAgentsDir.ts:481-488）。
        executor.setAgentDefinitionResolver(reg::findAgent);
        // [ODF-C3 返工#2] 注入 registry 全量 map → 子 loop 经 resolveAgentDefinition 最局部可达
        //   （对齐 print.ts:4381-4383 SDK request.agents 语义：flag/plugin agents 并入子 Agent 解析链）。
        executor.setAdditionalAgentDefinitions(reg.asMap());
        executor.setAgentMemoryDirectory(com.nexusai.application.agent.agent.AgentMemoryDirectory.productionDefault());
        // [MCP-I-9 返工 R1+R2] MCP 装配：McpTransportFactory + plugin-only supplier + name resolver
        //   （对称注入三者，修复首轮只注 supplier 不注 resolver 的双轨缺口）
        applyMcpWiring(executor);
        // [R31-03 返工] 周期摘要装配：summaryService + coordinatorMode + SDK 门 + spawn 路径
        //   （对齐 CC agentToolUtils.ts:543-553；sync 路径 AgentTool.tsx:852 门 = summaryTaskId && sdk）
        applySummaryWiring(executor, SubagentExecutor.SummarySpawnPath.SYNC);
        // [RF-2 ①/②] 前台登记 + AgentProgress 通道上下文：toolUseId（CC toolUseContext.toolUseId）
        //   + description（CC registerAgentForeground description，AgentTool.tsx:821）。
        executor.setSummaryToolUseId(toolUseId);
        executor.setSummaryDescription(description);
        // P1-11: 注入共享 SkillPreloader（共享 SkillRegistry bean）→ Step 14 预加载 skills
        executor.setSkillPreloader(skillPreloader);
        // [ALI-3] telemetry + transcript classifier + deferred modifier 注入 (与主路径对称)
        if (telemetry != null) executor.setTelemetry(telemetry);
        executor.setTranscriptClassifierEnabled(transcriptClassifierEnabled);
        // [IMP-SUB-25 返工 R2 接线归零] handoff 安全分类器注入（4 构造点对称，消灭 L1 惰性；
        //   对齐 R31-03 applySummaryWiring 模式 —— 此前全仓无 setYoloClassifier 调用 → 门 4 恒跳过）
        executor.setYoloClassifier(yoloClassifier);
        executor.setDeferContextModifier(true);
        // [H7-arch Phase 2] 注入 fresh carrier 工厂（subagent 走 queryLoop 单一循环源）
        executor.setContextFactory(contextFactory);
        // [RF-1] 透传父 assistant message 的 requestId（CC AgentTool.tsx:778 syncAgentContext.invokingRequestId）
        executor.setInvokingRequestId(invokingRequestId);
        // [IMP-G4 组11-1] Subagent hard_metrics + 按名路由注册表注入（sync 路径 is_async=false）
        if (analyticsTracker != null) executor.setAnalyticsTracker(analyticsTracker);
        if (agentNameRegistry != null) executor.setAgentNameRegistry(agentNameRegistry);

        // [Session M1.3 + S3] fork path 完整透传 (CC AgentTool.tsx:512/557/600)
        //   [S3] setSubagentExecutionContext 已删除 (S3-4 决策 B) — 父 assistantMessage /
        //   forkParentSystemPrompt 改由 forkParams 承载 (SubagentExecutor 4 参 execute).
        //   buildForkedMessages 与 buildWorktreeNotice 的完整调用由 SubagentExecutor 内部决策
        //   (executor 拿到完整 forkParams 后才能决定是否走 cache-identical prefix 路径).
        // [Session E] effectiveIsolation 透传 (CC AgentTool.tsx:431) —
        //   Step 18 worktree 创建决策 + fork worktree notice 注入
        executor.setEffectiveIsolation(effectiveIsolation);
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] executeSync S3 透传: prompt.length={}, forkParams={}, "
                    + "currentCwd={}",
                prompt == null ? 0 : prompt.length(),
                forkParams != null ? ("fork path: forkParentSystemPrompt.length="
                    + (forkParams.forkParentSystemPrompt() == null ? 0
                        : forkParams.forkParentSystemPrompt().length())) : "非 fork path",
                currentCwd);
        }

        // [IMP-D F4/M-05] 同步 spawn 作用域注入会话 projectRoot（修 M-05/M-06 · 模板
        //   AgentContext.runWithAgentContext :154-166 同款 capture/set/restore）。sync 在工具
        //   线程执行（IMP-C 已传播）→ 同值成对；多嵌套子代理（子再 spawn）restore 外层原值，
        //   嵌套链不串台。
        final String prevSyncProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
        AutoMemPaths.setCurrentProjectRoot(prevSyncProjectRoot);
        // [IMP-SUB-28 A5] sync 路径接流式（原残余 executor.execute → sink=null 不达父 caller）。
        //   父 caller（StreamingToolExecutor 注入 onProgress）现可逐消息观测子 Agent 产出。
        //   CC 真源 AgentTool.tsx:783-810 同步路径 for-await + onProgress 上报。
        java.util.function.Consumer<SubagentMessage> streamingSink = null;
        if (onProgress != null) {
            // parentMsgId = 父 assistant message id（fork path）· CC AgentTool.tsx:797/:1113
            //   toolUseID 'agent_'+id；非 fork（forkParams=null）回落 toolUseId。
            String parentMsgId = toolUseId;
            if (forkParams != null && forkParams.assistantMessage()
                    instanceof ForkSubagentMessages.AssistantMessage forkAssistant
                    && forkAssistant.uuid() != null && !forkAssistant.uuid().isBlank()) {
                parentMsgId = forkAssistant.uuid();
            }
            // [D18] sync 路径初始 agent_progress（CC AgentTool.tsx:791-806）—— prompt 承载任务元数据
            //   （CC data.message=首条 user 消息，Java 无归一化消息载体，以 prompt 串近似；
            //    agentId=null vs CC syncAgentId 已披露）。
            onProgress.accept(buildInitialAgentProgress(parentMsgId, prompt));
            // setResponseLength 累加源（D21，CC AgentTool.tsx:1097-1102）——父 TUC 未接线 → null 跳过
            java.util.function.Consumer<String> responseLength =
                parentCtx != null ? parentCtx.setResponseLength() : null;
            streamingSink = buildSyncStreamingSink(onProgress, parentMsgId, prompt, responseLength);
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] IMP-SUB-28 A5: sync 流式 sink 接线 parentMsgId={} promptLen={} "
                        + "responseLengthWired={}（CC AgentTool.tsx:783-810 onProgress）",
                    parentMsgId, prompt == null ? 0 : prompt.length(), responseLength != null);
            }
        }
        // [IMP-SUB-28 A5 返工 R5] 非流式回落分支（streamingSink==null，即 onProgress==null）标注 dead-path：
        //   生产路径经 StreamingToolExecutor 五参特化（:1756-1761）+ 三参通用派发（:1771）注入的
        //   wrappedCallback 恒非 null（StreamingToolExecutor:1719-1732 无条件包装）→ onProgress 恒非 null
        //   → 本分支生产不可达。按决策 2 保留（非流式 caller 直接 execute 时安全回落，对齐 CC
        //   AgentTool.tsx sync 路径非流式语义），不删除；若未来判定无非流式 caller 可清理。
        try {
            SubagentExecutor.SubagentResult result = streamingSink != null
                ? executor.executeStreaming(prompt, selectedAgent.agentType(), model, forkParams, streamingSink)
                : executor.execute(prompt, selectedAgent.agentType(), model, forkParams);
            // s06 P1-2: 把结构化 metrics 暴露给父 Agent (对齐 CC AgentTool.tsx:1253-1260)
            // [A1·退役 metadata] 旧 success(id, summary, Map metadata) 旁路退役, 结构化 metrics
            // 改走 ToolResult.structuredOutput (summary 仍为 LLM 可见 data).
            // [ATS-12] CC AgentTool.tsx:1356-1373: 非 one-shot agent 在 LLM 可见 data
            //   尾部追加 agentId/usage trailer (SendMessage 续接提示); one-shot
            //   (Explore/Plan) 或 worktree 场景跳过.
            String trailer = buildResultTrailer(
                selectedAgent.agentType(), result.agentId(),
                result.totalToolUseCount(), result.totalDurationMs(),
                result.totalTokens(),
                currentCwd != null && !currentCwd.isBlank());
            String llmData = trailer.isEmpty()
                ? result.summaryText()
                : result.summaryText() + "\n" + trailer;
            // [S4 P1 差异项 2] structuredOutput 补 totalTokens/usage 透传 (对齐 CC AgentTool.tsx:1253-1260
            //   agentToolResultSchema totalTokens + usage{7 子字段})
            return ToolResult.successWithStructuredOutput(toolUseId, llmData,
                java.util.Map.of(
                    "status", result.status(),
                    "agentId", result.agentId(),
                    "totalToolUseCount", String.valueOf(result.totalToolUseCount()),
                    "totalDurationMs", String.valueOf(result.totalDurationMs()),
                    "totalTokens", String.valueOf(result.totalTokens()),
                    "usageInputTokens", result.usage() != null ? String.valueOf(result.usage().inputTokens()) : "0",
                    "usageOutputTokens", result.usage() != null ? String.valueOf(result.usage().outputTokens()) : "0"));
        } catch (Exception e) {
            log.error("Subagent {} failed", selectedAgent.agentType(), e);
            return ToolResult.error(toolUseId, "Subagent failed: " + e.getMessage());
        } finally {
            // P0-2 修复: 与 doExecute 中的 setCwd('tool-' + toolUseId, ...) 配对的清理,
            //   避免 activeSessionCount 单调递增 (清理到对应前缀 key, 无 entry 时 no-op).
            WorktreeCwdTracker.clearCwd("tool-" + toolUseId);
            // [IMP-D F4/M-05] 成对 restore（null → 移除回落生效 · 线程池复用防串台）。
            AutoMemPaths.restoreCurrentProjectRoot(prevSyncProjectRoot);
        }
    }

    /**
     * [ATS-12] 构造 CC 结果 trailer · 对齐 AgentTool.tsx:1356-1373.
     *
     * <p>CC 真源:
     * <ul>
     *   <li>skip 条件 (:1356): {@code data.agentType && ONE_SHOT_BUILTIN_AGENT_TYPES.has(agentType)
     *       && !worktreeInfoText}</li>
     *   <li>trailer 文本 (:1363-1373): {@code agentId: ... (use SendMessage with to: '...' to
     *       continue this agent)\n<usage>total_tokens: ...\ntool_uses: ...\nduration_ms: ...</usage>}</li>
     * </ul>
     *
     * <p>[S4 P1 差异项 2 收尾] total_tokens 改取真实值 · 对齐 CC finalizeAgentTool
     * (agentToolUtils.ts:319/355): totalTokens 来自末尾 assistant message usage. 旧 "N/A" 占位
     * (Fail loud 约束, 规则十二) 已删除 — 父 Agent 需真实 token 数做 budget 决策, "N/A" = 信息丢失.
     * worktreeInfoText Java 端不在 trailer 内拼装, 仅以 hasWorktreeInfo 参与 skip 决策.
     *
     * @param agentType        子 Agent 类型 (CC data.agentType)
     * @param agentId          子 Agent ID (CC data.agentId)
     * @param totalToolUseCount 工具调用计数 (CC data.totalToolUseCount)
     * @param totalDurationMs   耗时 (CC data.totalDurationMs)
     * @param totalTokens       总 token 数 (CC data.totalTokens, agentToolUtils.ts:237/319)
     * @param hasWorktreeInfo  是否有 worktree 信息 (CC worktreeInfoText 非空)
     * @return trailer 文本; one-shot && !worktree → 空串 (skip)
     */
    static String buildResultTrailer(String agentType, String agentId,
                                     int totalToolUseCount, long totalDurationMs,
                                     long totalTokens, boolean hasWorktreeInfo) {
        if (agentType != null
                && com.nexusai.application.agent.tool.AgentToolConstants.ONE_SHOT_BUILTIN_AGENT_TYPES
                    .contains(agentType)
                && !hasWorktreeInfo) {
            return "";
        }
        if (agentId == null || agentId.isBlank()) {
            return "";
        }
        return "agentId: " + agentId
            + " (use SendMessage with to: '" + agentId + "' to continue this agent)"
            + "\n<usage>total_tokens: " + totalTokens
            + "\ntool_uses: " + totalToolUseCount
            + "\nduration_ms: " + totalDurationMs
            + "</usage>";
     }


    /**
     * 异步执行（对齐 CC AgentTool.tsx:686-764）· Phase 3: 统一走 BackgroundTaskRunner
     *
     * @param effectiveIsolation CC original: effectiveIsolation (AgentTool.tsx:431) —
     *                           input.isolation 透传给 SubagentExecutor Step 18 (worktree 创建决策)
     * @param onProgress 父 caller 进度回调。[IMP-SUB-28 A5] async worker 路径<b>不</b>转发父 onProgress
     *                   （CC async 返回 async_launched，进度走 task panel）；仅 backgroundTaskRunner 未注入
     *                   的降级同步路径接线（sync 语义）。
     * @param parentCtx  当前 turn 的父 ToolUseContext（降级 sync 路径 setResponseLength 累加源；可为 null）
     */
    private ToolResult executeAsync(String toolUseId, String prompt, String description,
                                    AgentDefinition selectedAgent, String model,
                                    SubagentExecutor.ForkPathParams forkParams,
                                    String currentCwd,
                                    String effectiveIsolation,
                                    String invokingRequestId,
                                    String name,
                                    // [冲突裁决·并集] name（HEAD=IMP-G4 C7 name→agentId 注册）+
                                    //   onProgress+parentCtx（subagent_v3=IMP-SUB-28 A5 降级 sync 流式 sink）。
                                    //   两者互补、async 主路径与降级 sync 分支各取所需，全部保留。
                                    java.util.function.Consumer<Tool.ToolProgress> onProgress,
                                    ToolUseContext parentCtx) {
        // [C-方案3][DEC-C-02] per-session agent-defs 视图：在调用线程（MDC/ctx 可解析）捕获一次，
        //   asyncWorker 新线程（ThreadLocal/MDC 不跨线程）与降级 sync 复用 —— 子 agent 解析用父会话
        //   项目表（对齐 CC runAgent.ts:687 agentDefinitions 原样继承父启动期表，无 per-cwd 重载）。
        final AgentDefinitionRegistry sessionRegistry = registryFor(sessionCwdFor(parentCtx));
        // Phase 3: CC 语义 — taskId === agentId (合一), 生成 agentId 作为 taskId
        // [R-A45 A-5 D18/B2] async 生成点对齐 CC createAgentId (uuid.ts:24-27)：
        //   a+16hex 为单一身份源（sync/async/fork 全链路一致），经 S-12 可逆桥 packAgentId
        //   存入 UUID 字段，不再独立 UUID.randomUUID()。taskId===agentId（agentId.toString()）
        //   保持（BackgroundTaskRunner 键匹配 / kill / abort / outputFile 语义不变，
        //   CC LocalAgentTask.tsx:197-262）。
        UUID agentId = AgentContext.packAgentId(AgentContext.createAgentId());
        String taskId = agentId.toString();
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] executeAsync a+16hex 生成点: taskId={}, a+16hex={} "
                    + "(R-A45 A-5 对齐 CC createAgentId)",
                taskId, AgentContext.unpackAgentId(agentId));
        }

        // Phase 3: 优先走 BackgroundTaskRunner.registerAsyncAgent (CC AgentTool.tsx:686-764)
        // 替代之前私有 Map<String, CompletableFuture<String>> 双轨方案.
        // [Session M1.3 + S3] fork path 完整透传: buildForkedMessages + buildWorktreeNotice 由
        //   SubagentExecutor 内部基于 forkParams / currentCwd 决策; doExecute 父层只负责捕获透传.
        //   [S3] setSubagentExecutionContext 已删除 (S3-4 决策 B) — forkParams 承载父上下文.
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] executeAsync S3 透传: prompt.length={}, forkParams={}, "
                    + "currentCwd={}",
                prompt == null ? 0 : prompt.length(),
                forkParams != null ? ("fork path: forkParentSystemPrompt.length="
                    + (forkParams.forkParentSystemPrompt() == null ? 0
                        : forkParams.forkParentSystemPrompt().length())) : "非 fork path",
                currentCwd);
        }
        if (backgroundTaskRunner != null) {
            try {
                BackgroundTaskRunner.TaskOutput taskOutput = null;
                BackgroundTaskRunner runnerRef = backgroundTaskRunner;
                // Phase 4 (cron-notify): 创建会话 sessionId 从 execute(call, ctx) 传入的 parentCtx 提取
                // （tool-exec 池线程无 MDC，读 MDC 恒 null）——注册时透传，完成通知注入创建会话回合（drain 3a）。
                // [hooks-plugin-display 修复] 不用 mainLoop.getCurrentToolUseContext()：SubagentTool 生产
                //   无参构造 mainLoop=null（setMainLoop 主代码 0 调用），该调用恒返回 null → async 子代理
                //   sessionId=null → GET /api/v1/tasks?sessionId= 会话过滤排除 → 前端任务 tab 不显示。
                //   对齐 CC STATE.sessionId（全局恒有值）语义：parentCtx.sessionId() 由 ToolUseContext
                //   compact ctor 强制非 null（ToolUseContext.java:305-307），是 CC 全局值的 Java 多会话等价。
                ToolUseContext parentTUCForSession = parentCtx;
                String parentSessionId = parentTUCForSession != null && parentTUCForSession.sessionId() != null
                    ? parentTUCForSession.sessionId() : null;
                BackgroundTask task = runnerRef.registerAsyncAgent(
                    agentId, description, prompt,
                    selectedAgent.agentType(), null, parentSessionId);
                // [IMP-G4 C7] name→agentId 注册 · 对齐 CC AgentTool.tsx:703-712
                //   （registerAsyncAgent 之后、spawn 启动之前 —— spawn 失败不残留 stale entry；
                //   sync agents skipped：coordinator 被阻塞，SendMessage 路由不适用）。
                //   仅 async spawn 注册（CC :703 位置在 runAsyncAgentLifecycle 前）。
                final String registeredName = name;
                if (registeredName != null && agentNameRegistry != null) {
                    agentNameRegistry.register(registeredName, agentId.toString());
                    if (log.isDebugEnabled()) {
                        log.debug("[SubagentTool] [IMP-G4 C7] name→agentId 注册: name='{}' agentId={} "
                                + "(CC AgentTool.tsx:703-712)",
                            registeredName, agentId);
                    }
                }
                // OPD-TP-21: 取 task-scoped AbortController（runner registerAsyncAgent 创建并保存）·
                //   killAsyncAgent → abort() 经本实例直达 worker（对齐 CC AgentTool.tsx:735 + runAgent.ts:524-525）
                final AbortController taskAbort = runnerRef.taskAbortController(task.id());
                // 异步执行 (后置启动, 不阻塞当前 turn)
                // [2026-08-26 父TUC 修复] parentCtx（doExecute ctx 透传）非 null，替代 mainLoop（恒 null）
                final ToolUseContext parentTUC = parentCtx != null
                        ? parentCtx
                        : (mainLoop != null ? mainLoop.getCurrentToolUseContext() : null);
                final String effectiveModel = model != null ? model : defaultModel;
                final AgentDefinition sel = selectedAgent;
                final UUID ag = agentId;
                // [Phase A 任务 6] 构造 AsyncAgentFinalizer (注入 runner, 集中收敛终态化逻辑)
                AsyncAgentFinalizer finalizer = new AsyncAgentFinalizer(runnerRef);
                // [IMP-D F4/M-05] 调度线程（工具线程 · IMP-C 已注入）捕获父会话 projectRoot →
                //   asyncWorker 新线程（ThreadLocal 不跨线程）注入：子代理 agent-memory / hook 载荷 /
                //   transcript / userContext 读会话值而非回落（修 M-05/M-06/M-12）。restore 线程原值
                //   成对，多嵌套子代理不串台。
                final String parentProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
                // [reqId MDC 传播] 调度线程（工具池线程，经 StreamingToolExecutor 回放已含父 MDC）捕获
                //   MDC context map → 子代理异步线程回放（实测：logback MDC 不随 new Thread 继承，须显式回放）。
                //   WHY: 子代理线程 RequestContext.requestId()=null → isTodoV2Enabled()=false → 子代理回落
                //   V1 TodoWrite、父 V2/子 V1 工具集分叉（决策 #65）。回放后子代理线程同帧 requestId 可见。
                final java.util.Map<String, String> mdcCtx = MDC.getCopyOfContextMap();
                Thread asyncWorker = new Thread(() -> {
                    // [reqId MDC 传播] 线程体开头回放父 MDC → 任务结束 restore 线程原值（成对，防泄漏）。
                    java.util.Map<String, String> prevAsyncMdc = MDC.getCopyOfContextMap();
                    if (mdcCtx != null) {
                        MDC.setContextMap(mdcCtx);
                    }
                    // [IMP-D F4/M-05] 线程体注入：capture 线程原值 → set 父值 → finally restore（成对）。
                    String prevAsyncProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
                    try {
                        if (parentProjectRoot != null && !parentProjectRoot.isBlank()) {
                            AutoMemPaths.setCurrentProjectRoot(parentProjectRoot);
                        }
                        try {
                        ToolRegistry subagentToolRegistry = createSubagentToolRegistry(sel, true);
                        String effectiveSystemPrompt = getEffectiveSystemPrompt(sel);
                        SubagentExecutor exec = new SubagentExecutor(
                            subagentToolRegistry, hookRegistry, mainLoop,
                            llmProviderFactory, effectiveProviderConfig(effectiveModel),
                            effectiveModel,
                            effectiveSystemPrompt,
                            parentTUC
                        );
                        // [IMP-SP2-08] PROMPT_CACHE_BREAK_DETECTION 门注入（异步 worker 路径同样
                        //   命中 finally cleanupAgentTracking；null-safe 默认关）
                        exec.setFeatureFlags(featureFlags);
                        // OPD-TP-21: 注入 task-scoped AbortController → SubagentExecutor 决策层
                        //   override 优先（对齐 CC runAgent.ts:524-525）；killAsyncAgent abort 直达 worker
                        exec.setTaskAbortController(taskAbort);
                        // [FIX-AM REQ-M-19] 生产接线：自定义 agent 解析器 + agent-memory 目录注入
                        //   （异步 worker 路径同样命中 resolveAgentDefinition / buildAgentSystemPrompt 注入）
                        //   [C-方案3][DEC-C-02] 用调用线程捕获的 per-session registry（会话项目表）
                        exec.setAgentDefinitionResolver(sessionRegistry::findAgent);
                        // [ODF-C3 返工#2] 异步路径同样注入 registry 全量 map（对齐 print.ts:4381-4383）
                        exec.setAdditionalAgentDefinitions(sessionRegistry.asMap());
                        exec.setAgentMemoryDirectory(com.nexusai.application.agent.agent.AgentMemoryDirectory.productionDefault());
                        // [MCP-I-9 返工 R1+R2] MCP 装配：McpTransportFactory + supplier + name resolver
                        applyMcpWiring(exec);
                        // [R31-03 返工] 周期摘要装配（async worker 路径 · AgentTool.tsx:750 三 flag 或）
                        applySummaryWiring(exec, SubagentExecutor.SummarySpawnPath.ASYNC);
                        // [RF-2 ①] AgentProgress 通道上下文（async 路径 task_progress toolUseId）
                        exec.setSummaryToolUseId(toolUseId);
                        exec.setSummaryDescription(description);
                        // [Session E] effectiveIsolation 透传 (CC AgentTool.tsx:431) —
                        //   Step 18 worktree 创建决策 + fork worktree notice 注入
                        exec.setEffectiveIsolation(effectiveIsolation);
                        // P1-11: 注入共享 SkillPreloader（共享 SkillRegistry bean）→ Step 14 预加载 skills
                        exec.setSkillPreloader(skillPreloader);
                        // [ALI-3] telemetry + classifier + deferred modifier 注入 (async worker)
                        //   [S3-4 决策 B] setSubagentExecutionContext 已删除 — forkParams 承载父上下文
                        if (telemetry != null) exec.setTelemetry(telemetry);
                        exec.setTranscriptClassifierEnabled(transcriptClassifierEnabled);
                        // [IMP-SUB-25 返工 R2 接线归零] handoff 安全分类器注入（4 构造点对称）
                        exec.setYoloClassifier(yoloClassifier);
                        exec.setDeferContextModifier(true);
                        // [H7-arch Phase 2] 注入 fresh carrier 工厂（subagent 走 queryLoop 单一循环源）
                        exec.setContextFactory(contextFactory);
                        // [RF-1] 透传父 assistant message 的 requestId（CC AgentTool.tsx:723 asyncAgentContext.invokingRequestId）
                        exec.setInvokingRequestId(invokingRequestId);
                        // [IMP-G4 组11-1] Subagent hard_metrics + 按名路由注册表注入（async worker is_async=true，
                        //   完成遥测 is_async 字段 + 待办消息消费）
                        if (analyticsTracker != null) exec.setAnalyticsTracker(analyticsTracker);
                        if (agentNameRegistry != null) exec.setAgentNameRegistry(agentNameRegistry);
                        exec.setAsyncExecution(true);
                        // [agentId 统一返工] 注入统一 agentId（= taskId 对应 packed UUID）→ SubagentExecutor
                        //   Step 5 createSubagentContext 优先用此键（CC forkedAgent.ts:448 overrides?.agentId ??
                        //   createAgentId()）→ subagentCtx.agentId === taskId → agentIdHex（transcript 键）===
                        //   unpackAgentId(taskId)，根治"执行记录找不到"（此前 create() 内部重新 createAgentId()
                        //   导致 transcript 键 ≠ unpack(taskId)）。
                        exec.setAgentIdOverride(agentId);
                        SubagentExecutor.SubagentResult res = exec.execute(
                            prompt, sel.agentType(), effectiveModel, forkParams);
                        // [S4 P1 差异项 6] runAsyncAgentLifecycle 三态路由 (CC agentToolUtils.ts:624/659/673):
                        //   status='aborted' (SubagentExecutor abort 早停 / state.cancel) → killed 路径,
                        //   partialResult = res.summaryText() (对齐 CC extractPartialResult :658);
                        //   否则 → completed (usage/totalTokens 随 AsyncAgentResult 透传).
                        if ("aborted".equals(res.status())) {
                            log.info("Async subagent {} 被 kill: agentId={} partialLen={}",
                                sel.agentType(), ag,
                                res.summaryText() != null ? res.summaryText().length() : 0);
                            AsyncAgentResult killed = AsyncAgentResult.killed(
                                res.summaryText(), res.usage(), res.totalTokens(),
                                res.totalDurationMs(), ag.toString());
                            finalizer.finalizeKilled(ag.toString(), killed);
                        } else {
                            log.info("Async subagent {} 完成: agentId={}", sel.agentType(), ag);
                            // [Phase A 任务 6] 成功路径: 走 AsyncAgentFinalizer 写入 COMPLETED
                            // (替代原 Phase 3 的 enqueueAgentNotification 路径 — 现在由 completeAsyncAgent
                            //  内部 enqueue 通知 + 写 outputFile, 统一收敛终态)
                            AsyncAgentResult success = AsyncAgentResult.success(
                                res.summaryText(), res.totalToolUseCount(),
                                res.totalDurationMs(), ag.toString(),
                                res.totalTokens(), res.usage());
                            finalizer.finalize(ag.toString(), success);
                        }
                    } catch (Exception e) {
                        log.error("Async subagent {} 失败: {}", sel.agentType(), e.getMessage());
                        // [Phase A 任务 6] 失败路径: 同样走 AsyncAgentFinalizer (失败结果)
                        // summary 字段作为错误描述写入 outputFile
                        AsyncAgentResult failure = AsyncAgentResult.failure(
                            "Subagent " + sel.agentType() + " failed: " + e.getMessage(),
                            ag.toString());
                        finalizer.finalize(ag.toString(), failure);
                    } finally {
                        // P0-2 修复: 异步线程结束时清理 tracker key (避免 activeSessionCount 单调递增).
                        WorktreeCwdTracker.clearCwd("tool-" + toolUseId);
                        // [IMP-G4 C7] 终态注销 name→agentId（避免映射残留指向已终止 agentId ·
                        //   CC React 状态随会话结束 GC，Java 显式注销等价位）
                        if (registeredName != null && agentNameRegistry != null) {
                            agentNameRegistry.unregister(registeredName);
                            if (log.isDebugEnabled()) {
                                log.debug("[SubagentTool] [IMP-G4 C7] 终态注销 name→agentId: name='{}' "
                                        + "agentId={} (CC agentNameRegistry 会话结束清理)",
                                    registeredName, ag);
                            }
                        }
                    }
                    } finally {
                        // [IMP-D F4/M-05] 成对 restore 线程原值（null → 移除回落生效）。
                        AutoMemPaths.restoreCurrentProjectRoot(prevAsyncProjectRoot);
                        // [reqId MDC 传播] 成对 restore 线程原值（null → 清理，防线程复用泄漏）。
                        if (prevAsyncMdc != null) {
                            MDC.setContextMap(prevAsyncMdc);
                        } else {
                            MDC.clear();
                        }
                    }
                }, "async-subagent-" + ag);
                asyncWorker.setDaemon(true);
                asyncWorker.start();

                log.info("Async subagent started: taskId={} agentId={} type={}",
                    taskId, agentId, selectedAgent.agentType());

                // 异步模式输出 (对齐 CC AgentTool.tsx:756-764 / :1041-1051 async_launched data 契约)
                //   CC data = {status, agentId, description, prompt, outputFile, canReadOutputFile}
                //   (AgentTool.tsx:146-153 asyncOutputSchema). taskId===agentId 合一
                //   (LocalAgentTask.tsx:197-262), 故 agentId 复用 taskId 值.
                // [D5] canReadOutputFile: 判定调用方 agent 是否持有 Read/Bash 工具
                //   (CC AgentTool.tsx:753/:1040 toolUseContext.options.tools.some(...)).
                // [D5 返工] 数据形状构建抽为 package-private helper（buildAsyncLaunchedData），
                //   聚焦测试可直接断言 == CC asyncOutputSchema (AgentTool.tsx:146-153) 六字段。
                List<Tool> parentTools = parentTUC != null ? parentTUC.availableTools() : availableTools;
                boolean canReadOutputFile = hasReadOrBashTool(parentTools);
                return ToolResult.success(toolUseId, buildAsyncLaunchedData(
                    taskId, description, prompt, task.outputFile(), canReadOutputFile));
            } catch (Exception e) {
                log.error("Phase 3 async path via runner 失败: {}", e.getMessage());
                // 失败时降级到 s13 老路径 (sync execution in current thread)
            }
        }

        // 降级路径 (backgroundTaskRunner 未注入或失败): 同步执行
        log.warn("[SubagentTool] Phase 3: BackgroundTaskRunner 未注入, 降级到同步执行 (临时)");
        ToolRegistry subagentToolRegistry = createSubagentToolRegistry(selectedAgent, false);
        String effectiveSystemPrompt = getEffectiveSystemPrompt(selectedAgent);
        // [2026-08-26 父TUC 修复] parentCtx 替代 mainLoop（恒 null）
        ToolUseContext parentTUC = parentCtx != null
                ? parentCtx
                : (mainLoop != null ? mainLoop.getCurrentToolUseContext() : null);
        SubagentExecutor executor = new SubagentExecutor(
            subagentToolRegistry, hookRegistry, mainLoop,
            llmProviderFactory, effectiveProviderConfig(model != null ? model : defaultModel),
            model != null ? model : defaultModel,
            effectiveSystemPrompt,
            parentTUC
        );
        // [IMP-SP2-08] PROMPT_CACHE_BREAK_DETECTION 门注入（降级 sync 路径同样命中 finally
        //   cleanupAgentTracking；null-safe 默认关）
        executor.setFeatureFlags(featureFlags);
        // [FIX-AM REQ-M-19] 生产接线：自定义 agent 解析器 + agent-memory 目录注入（降级 sync 路径）
        //   [C-方案3][DEC-C-02] 复用 executeAsync 调用线程捕获的 per-session registry（会话项目表）
        executor.setAgentDefinitionResolver(sessionRegistry::findAgent);
        // [ODF-C3 返工#2] 降级 sync 路径同样注入 registry 全量 map（对齐 print.ts:4381-4383）
        executor.setAdditionalAgentDefinitions(sessionRegistry.asMap());
        executor.setAgentMemoryDirectory(com.nexusai.application.agent.agent.AgentMemoryDirectory.productionDefault());
        // [MCP-I-9 返工 R1+R2] MCP 装配：McpTransportFactory + supplier + name resolver（降级 sync 路径）
        applyMcpWiring(executor);
        // [R31-03 返工] 周期摘要装配（降级 sync 路径 · AgentTool.tsx:852 门 = summaryTaskId && sdk）
        applySummaryWiring(executor, SubagentExecutor.SummarySpawnPath.SYNC);
        // [RF-2 ①/②] 前台登记 + AgentProgress 通道上下文（降级 sync 路径）
        executor.setSummaryToolUseId(toolUseId);
        executor.setSummaryDescription(description);
        // P1-11: 注入共享 SkillPreloader（共享 SkillRegistry bean）→ Step 14 预加载 skills
        executor.setSkillPreloader(skillPreloader);
        // [ALI-3] telemetry + classifier + deferred modifier 注入 (降级 sync 路径)
        //   [S3-4 决策 B] setSubagentExecutionContext 已删除 — forkParams 承载父上下文
        if (telemetry != null) executor.setTelemetry(telemetry);
        executor.setTranscriptClassifierEnabled(transcriptClassifierEnabled);
        // [IMP-SUB-25 返工 R2 接线归零] handoff 安全分类器注入（4 构造点对称，消灭 L1 惰性；
        //   对齐 R31-03 applySummaryWiring 模式 —— 此前全仓无 setYoloClassifier 调用 → 门 4 恒跳过）
        executor.setYoloClassifier(yoloClassifier);
        // [Session E] effectiveIsolation 透传 (CC AgentTool.tsx:431) —
        //   Step 18 worktree 创建决策 + fork worktree notice 注入
        executor.setEffectiveIsolation(effectiveIsolation);
        executor.setDeferContextModifier(true);
        // [H7-arch Phase 2] 注入 fresh carrier 工厂（subagent 走 queryLoop 单一循环源）
        executor.setContextFactory(contextFactory);
        // [RF-1] 透传父 assistant message 的 requestId（CC AgentTool.tsx:778 syncAgentContext.invokingRequestId）
        executor.setInvokingRequestId(invokingRequestId);
        // [agentId 统一返工] 降级 sync 同样注入统一 agentId（executeAsync :3033 生成，taskId===agentId）——
        //   使降级路径 identity 与 async 生成点一致（CC AgentTool.tsx:580 earlyAgentId），
        //   无 BackgroundTask 时也不影响（无 taskId 引用，纯一致性）。
        executor.setAgentIdOverride(agentId);
        // [IMP-D F4/M-05] 降级 sync 同样注入会话 projectRoot（工具线程 IMP-C 已传播 → 同值成对；
        //   多嵌套 restore 外层原值）。
        // [IMP-SUB-28 A5] 降级 sync 路径同样接流式（同步语义 → 父 onProgress 可观测）。
        final String prevFallbackProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
        AutoMemPaths.setCurrentProjectRoot(prevFallbackProjectRoot);
        // [冲突裁决·并集] HEAD=IMP-G4 组11-1 analytics+agentNameRegistry 注入（降级 sync 同样 hard_metrics
        //   归因 + name→agentId）；subagent_v3=IMP-SUB-28 A5 fallbackStreamingSink 降级 sync 流式接线
        //   （CC AgentTool.tsx:783-810 onProgress）。两组语句独立互补、无顺序依赖，全部保留。
        // [IMP-G4 组11-1] Subagent hard_metrics + 按名路由注册表注入（降级 sync is_async=false）
        if (analyticsTracker != null) executor.setAnalyticsTracker(analyticsTracker);
        if (agentNameRegistry != null) executor.setAgentNameRegistry(agentNameRegistry);
        java.util.function.Consumer<SubagentMessage> fallbackStreamingSink = null;
        if (onProgress != null) {
            String fallbackParentMsgId = toolUseId;
            if (forkParams != null && forkParams.assistantMessage()
                    instanceof ForkSubagentMessages.AssistantMessage forkAssistant
                    && forkAssistant.uuid() != null && !forkAssistant.uuid().isBlank()) {
                fallbackParentMsgId = forkAssistant.uuid();
            }
            // [D18] 初始 agent_progress（CC AgentTool.tsx:791-806）
            onProgress.accept(buildInitialAgentProgress(fallbackParentMsgId, prompt));
            java.util.function.Consumer<String> fallbackResponseLength =
                parentCtx != null ? parentCtx.setResponseLength() : null;
            fallbackStreamingSink = buildSyncStreamingSink(
                onProgress, fallbackParentMsgId, prompt, fallbackResponseLength);
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] IMP-SUB-28 A5: 降级 sync 流式 sink 接线 parentMsgId={} "
                        + "promptLen={} responseLengthWired={}（CC AgentTool.tsx:783-810 onProgress）",
                    fallbackParentMsgId, prompt == null ? 0 : prompt.length(),
                    fallbackResponseLength != null);
            }
        }
        // [IMP-SUB-28 A5 返工 R5] 非流式回落分支（fallbackStreamingSink==null，即 onProgress==null）同样
        //   标注 dead-path：降级 sync 由 executeAsync 在 backgroundTaskRunner==null 时同步调用，经
        //   StreamingToolExecutor 注入的 wrappedCallback 恒非 null（:1719-1732 无条件包装）→ 生产不可达。
        //   按决策 2 保留（与非流式 caller 安全回落对称），不删除。
        try {
            SubagentExecutor.SubagentResult result = fallbackStreamingSink != null
                ? executor.executeStreaming(prompt, selectedAgent.agentType(), model, forkParams,
                    fallbackStreamingSink)
                : executor.execute(prompt, selectedAgent.agentType(), model, forkParams);
            // [A1·退役 metadata] metrics 折入 data JSON (CC data:T 通道).
            com.fasterxml.jackson.databind.node.ObjectNode syncData =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            syncData.put("summary", result.summaryText());
            syncData.put("status", result.status());
            syncData.put("agentId", result.agentId());
            syncData.put("totalToolUseCount", String.valueOf(result.totalToolUseCount()));
            syncData.put("totalDurationMs", String.valueOf(result.totalDurationMs()));
            return ToolResult.success(toolUseId, syncData);
        } catch (Exception e) {
            log.error("Subagent {} failed", selectedAgent.agentType(), e);
            return ToolResult.error(toolUseId, "Subagent failed: " + e.getMessage());
        } finally {
            // P0-2 修复: executeAsync 降级到同步执行的清理 (与 doExecute 中的 setCwd 配对).
            WorktreeCwdTracker.clearCwd("tool-" + toolUseId);
            // [IMP-D F4/M-05] 成对 restore（null → 移除回落生效 · 线程池复用防串台）。
            AutoMemPaths.restoreCurrentProjectRoot(prevFallbackProjectRoot);
        }
    }

    /**
     * [D5] 构建 async_launched data — 对齐 CC AgentTool.tsx:146-153 asyncOutputSchema 六字段契约
     * {@code {status, agentId, description, prompt, outputFile, canReadOutputFile}}。
     *
     * <p>抽为 package-private static（含注释说明）以便聚焦测试直接断言数据形状；D5 是 HIGH 前端契约缺口
     * （前端按 CC 契约取 agentId/description/outputFile），数据形状必须 == CC asyncOutputSchema，防回归为
     * 旧 Java 自定义形状 {@code {summary(JSON串), status, taskId, agentType}}。
     *
     * @param taskId            agentId 复用值（CC 语义 taskId===agentId 合一, LocalAgentTask.tsx:197-262）
     * @param description       任务描述 CC original: description (AgentTool.tsx:149/:759)
     * @param prompt            任务提示词 CC original: prompt (AgentTool.tsx:150/:760)
     * @param outputFile        输出文件路径 CC original: outputFile = getTaskOutputPath(agentId)
     *                          (AgentTool.tsx:151/:761 · diskOutput.ts:72-74)
     * @param canReadOutputFile 调用方 agent 是否持有 Read/Bash 工具
     *                          CC original: canReadOutputFile (AgentTool.tsx:152/:753)
     * @return 六字段 ObjectNode（status='async_launched'）
     */
    static com.fasterxml.jackson.databind.node.ObjectNode buildAsyncLaunchedData(
            String taskId, String description, String prompt,
            String outputFile, boolean canReadOutputFile) {
        com.fasterxml.jackson.databind.node.ObjectNode asyncData =
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        asyncData.put("status", "async_launched");
        asyncData.put("agentId", taskId);
        asyncData.put("description", description);
        asyncData.put("prompt", prompt);
        asyncData.put("outputFile", outputFile);
        asyncData.put("canReadOutputFile", canReadOutputFile);
        return asyncData;
    }

    /**
     * [D5] CC original: canReadOutputFile (AgentTool.tsx:753/:1040) —
     * {@code toolUseContext.options.tools.some(t => toolMatchesName(t, FILE_READ_TOOL_NAME)
     * || toolMatchesName(t, BASH_TOOL_NAME))}。判定调用方 agent 是否持有 Read/Bash 工具
     * （提示是否可直接读取 outputFile 查进度）。toolMatchesName = name === name || aliases?.includes(name)
     * (Tool.ts:348-352)。
     *
     * <p>package-private 便于聚焦测试直接验证 Read/Bash name|alias 匹配语义。
     *
     * @param tools 调用方 agent 当前可用工具列表（可空 → false）
     * @return 含 Read 或 Bash 工具 → true
     */
    static boolean hasReadOrBashTool(List<Tool> tools) {
        if (tools == null) return false;
        return tools.stream().anyMatch(t ->
            ToolNameConstants.FILE_READ_TOOL_NAME.equals(t.name())
                || t.aliases().contains(ToolNameConstants.FILE_READ_TOOL_NAME)
                || ToolNameConstants.BASH_TOOL_NAME.equals(t.name())
                || t.aliases().contains(ToolNameConstants.BASH_TOOL_NAME));
    }

    /**
     * [IMP-SUB-28 A5] 构建 sync 路径流式 sink · 对齐 CC AgentTool.tsx:783-810 同步路径 onProgress 上报。
     *
     * <p>CC 真源（sync for-await 循环）:
     * <ul>
     *   <li><b>D20</b> :1084-1089 bash/powershell_progress 转发父 onProgress
     *       {@code onProgress({toolUseID: message.toolUseID, data: message.data})} —— Java 端
     *       ProgressMessage 仅载 description（SubagentExecutor:4281-4284 toProgressMessage
     *       description = String.valueOf(data)），toolUseID 不保留 → 以 'agent_'+parentMsgId 近似
     *       （描述中不含原始 toolUseID 的结构信息，Java 数据面限制，concerns 披露）。</li>
     *   <li><b>D19/D22</b> :1103-1125 tool_use/tool_result 块 → agent_progress
     *       {@code onProgress({toolUseID: 'agent_'+id, data:{message, type:'agent_progress',
     *       prompt, agentId}})} —— Java 端 {@link SubagentMessage#toolContent()} 由
     *       SubagentExecutor.toSubagentMessage 判定（assistant tool_use / role=tool result）。</li>
     *   <li><b>D21</b> :1097-1102 assistant 消息 content 长度累加 setResponseLength（CC
     *       getAssistantMessageContentLength）—— Java 父 TUC {@code Consumer<String>} 累加总长
     *       （对齐 CompactConversation.java:484-485 accept(String.valueOf(len))）。</li>
     * </ul>
     *
     * <p>onProgress == null → 返回 null（非流式，调用方回落 {@code executor.execute}）。
     *
     * @param onProgress    父 caller 进度回调（StreamingToolExecutor 注入；null = 非流式）
     * @param parentMsgId   父消息 id → toolUseID 'agent_'+id（CC AgentTool.tsx:797/:1113）
     * @param prompt        agent 任务提示词 → data.prompt（CC AgentTool.tsx:800/:1119）
     * @param responseLength 父 TUC setResponseLength（D21 累加源；null = 父未接线 → 跳过）
     * @return SubagentMessage 消费端 sink；onProgress=null 返回 null
     */
    static java.util.function.Consumer<SubagentMessage> buildSyncStreamingSink(
            java.util.function.Consumer<Tool.ToolProgress> onProgress,
            String parentMsgId,
            String prompt,
            java.util.function.Consumer<String> responseLength) {
        if (onProgress == null) {
            return null;
        }
        // [D21] assistant 消息 content 长度累加（CC AgentTool.tsx:1097-1102 逐消息 +contentLength）
        java.util.concurrent.atomic.AtomicLong responseLengthTotal =
            new java.util.concurrent.atomic.AtomicLong(0);
        return message -> {
            // D21: setResponseLength 累加（仅 assistant 文本消息，CC getAssistantMessageContentLength）
            if (message instanceof SubagentMessage.AssistantMessage am && responseLength != null) {
                int contentLen = am.content() != null ? am.content().length() : 0;
                if (contentLen > 0) {
                    responseLength.accept(String.valueOf(responseLengthTotal.addAndGet(contentLen)));
                }
            }
            // D20: 工具进度（bash/powershell/mcp 等 ProgressMessage）→ 父 onProgress
            if (message instanceof SubagentMessage.ProgressMessage pm) {
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentTool] [IMP-SUB-28 A5] sink 转发 ProgressMessage: "
                            + "toolUseId='agent_{}' desc={}（CC AgentTool.tsx:1084-1089）",
                        parentMsgId,
                        pm.description() != null && pm.description().length() > 80
                            ? pm.description().substring(0, 80) + "..." : pm.description());
                }
                onProgress.accept(new Tool.ToolProgress("agent_" + parentMsgId, pm.description()));
                return;
            }
            // D19/D22: tool_use/tool_result 块 → agent_progress（CC AgentTool.tsx:1103-1125）
            if (message.toolContent()) {
                if (log.isDebugEnabled()) {
                    log.debug("[SubagentTool] [IMP-SUB-28 A5] sink 转发 agent_progress: "
                            + "msgType={} toolUseId='agent_{}' agentId={} promptLen={} "
                            + "（CC AgentTool.tsx:1103-1125）",
                        message.getClass().getSimpleName(), parentMsgId, message.agentId(),
                        prompt == null ? 0 : prompt.length());
                }
                onProgress.accept(new Tool.ToolProgress(
                    "agent_" + parentMsgId,
                    new AgentToolProgressData("agent_progress", prompt, message.agentId(), message)));
            }
        };
    }

    /**
     * [IMP-SUB-28 A5] D18 初始 agent_progress 发射 · 对齐 CC AgentTool.tsx:791-806
     * {@code onProgress({toolUseID: 'agent_'+id, data:{message: 首条 user, type:'agent_progress',
     * prompt, agentId: syncAgentId}})}。
     *
     * <p>抽为 package-private static（含注释说明）以便聚焦测试直接断言 D18 初始发射的形状
     * （对齐 D5 buildAsyncLaunchedData 可测性抽取模式）。Java 端 message 载体以 prompt 串近似
     * （CC data.message=首条归一化 user 消息，sink 构建期不可得）；agentId=null（CC syncAgentId
     * 为子 agent 新建 id，Java 端构建期不可得）——两处偏差已披露（concerns）。
     *
     * @param parentMsgId 父消息 id → toolUseID 'agent_'+id（CC AgentTool.tsx:797/:1113）
     * @param prompt      agent 任务提示词 → data.prompt + data.message 近似载体（CC :800/:1119）
     * @return D18 初始 agent_progress ToolProgress
     */
    static Tool.ToolProgress buildInitialAgentProgress(String parentMsgId, String prompt) {
        return new Tool.ToolProgress(
            "agent_" + parentMsgId,
            new AgentToolProgressData("agent_progress", prompt, null, prompt));
    }

    /**
     * [IMP-SUB-28 A5] agent_progress 数据载体 · 对齐 CC AgentTool.tsx:1113-1122 onProgress.data 结构
     * {@code {message, type: 'agent_progress', prompt, agentId}}。
     *
     * @param type    CC original: type ('agent_progress', AgentTool.tsx:1116)
     * @param prompt  CC original: prompt（初始消息携带完整 prompt，其后空串，AgentTool.tsx:1119/:800）
     * @param agentId CC original: agentId（子 agent id，AgentTool.tsx:1120；SubagentMessage.agentId 载体透传）
     * @param message CC original: message（归一化消息，AgentTool.tsx:1115；Java 端为 SubagentMessage 载体）
     */
    public record AgentToolProgressData(String type, String prompt, String agentId, Object message) {}

    /**
     * resume 异步续跑子 Agent · 对齐 CC resumeAgent.ts:198-258
     * (registerAsyncAgent + runAsyncAgentLifecycle + wrapWithCwd)。
     *
     * <p>复用 {@link #executeAsync} 的 worker 线程 + AsyncAgentFinalizer 三态路由模式，差异:
     * <ul>
     *   <li>forkParams 携带 resume 专属字段（{@code resumedMessages} / {@code forkParentSystemPrompt} /
     *       {@code resumedWorktreePath}）→ SubagentExecutor Step 10/18 装配 resumed 消息链 + 复用原 worktree</li>
     *   <li>taskId === agentId 复用（resume 不换 ID）；[RES-R2] ForkPathParams.agentIdOverride 透传原
     *       agentId → SubagentExecutor Step 5 续写原键（对齐 CC resumeAgent.ts:240 override.agentId），
     *       二次 resume 读到 pre-resume transcript</li>
     *   <li>background=true 恒后台（CC resumeAgent.ts isAsync:true），不降级同步</li>
     * </ul>
     *
     * <p>本方法同步返回（注册 + 线程启动后立即返回），实际 LLM 续跑在 daemon worker 中异步执行，
     * 终态由 {@link AsyncAgentFinalizer} 写入 BackgroundTaskRunner（completed/killed/failed 三态）。
     *
     * @param agentId             原 sub-agent UUID（resume 复用，不新生成）
     * @param prompt              resume 追加的用户指令
     * @param selectedAgent       解析后的 AgentDefinition（fork → ForkSubagentAgentDefinition；否则 GENERAL_PURPOSE）
     * @param description         人类可读描述（CC resumeAgent.ts:114 uiDescription = meta?.description ?? '(resumed)'）
     * @param forkParentSystemPrompt fork resume 父 system prompt（非 fork resume 传 null；CC :183-185 override.systemPrompt）
     * @param resumedMessages     三层过滤后的 transcript 消息链（CC :166-171 promptMessages）
     * @param resumedWorktreePath 原 worktree 路径（stat 通过；缺失 → null 回退父 cwd，CC :82-92）
     * @param contentReplacementState resume 重建的 ContentReplacementState · CC original:
     *                             resumeAgent.ts:194 {@code contentReplacementState: resumedReplacementState}
     *                             — 透传 ForkPathParams → SubagentExecutor Step 20 注入 query loop;
     *                             null = 父 live state 不可得（web 端点）→ loop 保持默认 create
     *                             （CC :1006 feature off）
     */
    public void executeResumeAsync(UUID agentId, String prompt, AgentDefinition selectedAgent,
                                   String description, String forkParentSystemPrompt,
                                   List<AgentMessage> resumedMessages, String resumedWorktreePath,
                                   ContentReplacementState contentReplacementState) {
        // 8 参兼容重载 → 委托 9 参（sessionId=null → 任务无会话归属；仅测试/手动调用走此路径，
        // 生产 ResumeService 走 9 参透传真实 sessionId）。
        executeResumeAsync(agentId, prompt, selectedAgent, description, forkParentSystemPrompt,
            resumedMessages, resumedWorktreePath, contentReplacementState, null);
    }

    /**
     * [hooks-plugin-display 修复] resume 后台续跑 · 9 参重载（含创建会话 sessionId）。
     *
     * <p>Phase 4 (cron-notify): 创建会话 sessionId 由调用方（ResumeService）透传——不用
     * {@code mainLoop.getCurrentToolUseContext()}（SubagentTool 生产 mainLoop=null 恒 null → async
     * 子代理 sessionId=null → GET /api/v1/tasks?sessionId= 会话过滤排除 → 前端任务 tab 不显示）。
     * 对齐 CC STATE.sessionId（全局恒有值）语义：resume 入口（ResumeService.resumeAgentBackground
     * :153-154）已有 sessionId 参数，Java 多会话显式透传。
     *
     * @param createSessionId 创建此 resume 任务的会话 sessionId（null = 无会话归属 → V1 列表会话过滤排除）
     */
    public void executeResumeAsync(UUID agentId, String prompt, AgentDefinition selectedAgent,
                                   String description, String forkParentSystemPrompt,
                                   List<AgentMessage> resumedMessages, String resumedWorktreePath,
                                   ContentReplacementState contentReplacementState,
                                   String createSessionId) {
        if (backgroundTaskRunner == null) {
            log.error("[SubagentTool] executeResumeAsync: BackgroundTaskRunner 未注入, 无法后台续跑 resume agent={}",
                agentId);
            throw new IllegalStateException(
                "BackgroundTaskRunner not injected into SubagentTool; cannot run resume async agent");
        }
        BackgroundTaskRunner runnerRef = backgroundTaskRunner;
        // Phase 4 (cron-notify): 创建会话 sessionId 由调用方透传（tool-exec 池线程无 MDC）——
        // resume 续跑任务完成通知须注入创建会话回合。
        ToolUseContext parentTUCForSession = createSessionId != null
            ? ToolUseContext.of(null, createSessionId) : null;
        String parentSessionId = parentTUCForSession != null && parentTUCForSession.sessionId() != null
            ? parentTUCForSession.sessionId() : null;
        BackgroundTask task = runnerRef.registerAsyncAgent(
            agentId, description, prompt, selectedAgent.agentType(), null, parentSessionId);
        // OPD-TP-21: 取 task-scoped AbortController → 注入 worker（kill 直达；对齐 CC runAgent.ts:524-525）
        final AbortController taskAbort = runnerRef.taskAbortController(task.id());
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] executeResumeAsync: taskId={} type={} resumed.size={} fork={} worktree={} crsSeen={} crsRepl={}",
                task.id(), selectedAgent.agentType(),
                resumedMessages == null ? 0 : resumedMessages.size(),
                forkParentSystemPrompt != null && !forkParentSystemPrompt.isBlank(),
                resumedWorktreePath,
                contentReplacementState != null ? contentReplacementState.seenIds().size() : 0,
                contentReplacementState != null ? contentReplacementState.replacements().size() : 0);
        }
        final ToolUseContext parentTUC = mainLoop != null ? mainLoop.getCurrentToolUseContext() : null;
        final AgentDefinition sel = selectedAgent;
        final UUID ag = agentId;
        final String fp = forkParentSystemPrompt;
        final String rw = resumedWorktreePath;
        final List<AgentMessage> rm = resumedMessages;
        final ContentReplacementState crs = contentReplacementState;
        // [D12/#25] resume 模型现算 · 对齐 CC resumeAgent.ts:151-156 + :179：
        //   resolvedAgentModel = getAgentModel(selectedAgent.model, mainLoopModel, undefined, permissionMode)，
        //   runAgent model:undefined 内部解析 → Java effectiveModel = resolve(agent.model, mainLoopModel, null, null)。
        //   原实现 sel.model().orElse(defaultModel) 直接用 agent 定义模型串，绕过继承/别名解析链；
        //   parentModel = resolveParentModel()（mainLoop.getModelForCall() ?? defaultModel，CC options.mainLoopModel 语义）。
        //   [G-7] 透传父当前 permissionMode（CC resumeAgent.ts:179 4 参 getAgentModel）——'inherit' + plan
        //   模式的 opusplan→Opus / haiku→Sonnet 升级由 AgentModelResolver 判定。
        final PermissionMode resumePermissionMode = currentPermissionMode(parentTUC);
        final String effectiveModel = com.nexusai.application.agent.subagent.AgentModelResolver.resolve(
            sel.model().orElse(null), resolveParentModel(), null,
            resumePermissionMode == null ? null : resumePermissionMode.name(),
            settingsSubagentModelId());
        // [D12 数据流日志] resume 模型现算结果（CC resumeAgent.ts:151-156 resolvedAgentModel）——
        //   暴露 agentModel/parentModel/permissionMode/effectiveModel 供线上排查 + 聚焦测试可观测 seam。
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] executeResumeAsync D12 模型解析: agentModel={}, parentModel={}, "
                    + "permissionMode={}, effectiveModel={} (CC resumeAgent.ts:151-156)",
                sel.model().orElse(null), resolveParentModel(), resumePermissionMode, effectiveModel);
        }
        AsyncAgentFinalizer finalizer = new AsyncAgentFinalizer(runnerRef);
        // [IMP-D F4/M-05] resume 调度线程（调用线程）捕获父会话 projectRoot → 注入
        //   asyncWorker 新线程（ThreadLocal 不跨线程 · 修 M-05/M-06）。restore 成对。
        final String parentResumeProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
        // [reqId MDC 传播] 调度线程（工具池线程，经 StreamingToolExecutor 回放已含父 MDC）捕获
        //   MDC context map → resume 子代理异步线程回放（实测：logback MDC 不随 new Thread 继承，须显式回放）。
        //   WHY: 同 executeAsync —— 子代理线程 RequestContext.requestId()=null → isTodoV2Enabled()=false
        //   → 子代理回落 V1 TodoWrite、父 V2/子 V1 工具集分叉（决策 #65）。
        final java.util.Map<String, String> mdcCtx = MDC.getCopyOfContextMap();
        Thread asyncWorker = new Thread(() -> {
            // [reqId MDC 传播] 线程体开头回放父 MDC → 任务结束 restore 线程原值（成对，防泄漏）。
            java.util.Map<String, String> prevResumeMdc = MDC.getCopyOfContextMap();
            if (mdcCtx != null) {
                MDC.setContextMap(mdcCtx);
            }
            // [IMP-D F4/M-05] 线程体注入：capture 线程原值 → set 父值 → finally restore（成对）。
            String prevResumeProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
            try {
                if (parentResumeProjectRoot != null && !parentResumeProjectRoot.isBlank()) {
                    AutoMemPaths.setCurrentProjectRoot(parentResumeProjectRoot);
                }
            try {
                // [FORK-05] fork-resume 判定 = forkParentSystemPrompt 非空（CC isResumedFork；
                //   resumeAgent.ts:161-164 workerTools 分支 + :190 useExactTools）——fork-resume 走父精确
                //   工具池（绕过 4-SET 过滤），非 fork resume 走常规过滤（行为不变）
                boolean isResumedFork = fp != null && !fp.isBlank();
                ToolRegistry subagentToolRegistry = createSubagentToolRegistry(sel, true, isResumedFork);
                String effectiveSystemPrompt = getEffectiveSystemPrompt(sel);
                SubagentExecutor exec = new SubagentExecutor(
                    subagentToolRegistry, hookRegistry, mainLoop,
                    llmProviderFactory, effectiveProviderConfig(effectiveModel),
                    effectiveModel,
                    effectiveSystemPrompt,
                    parentTUC
                );
                // [IMP-SP2-08] PROMPT_CACHE_BREAK_DETECTION 门注入（resume 续跑路径同样命中
                //   finally cleanupAgentTracking；null-safe 默认关）
                exec.setFeatureFlags(featureFlags);
                // OPD-TP-21: 注入 task-scoped AbortController（resume 续跑同样可被 kill 中断）
                exec.setTaskAbortController(taskAbort);
                // [MCP-I-9 返工 R1+R2] MCP 装配：McpTransportFactory + supplier + name resolver（resume 路径）
                applyMcpWiring(exec);
                // [R31-03 返工] 周期摘要装配（resume 路径 · resumeAgent.ts:250-253 三 flag 或）
                applySummaryWiring(exec, SubagentExecutor.SummarySpawnPath.RESUME);
                // [RF-2 ①] AgentProgress 通道上下文（resume 无父 tool_use block → toolUseId=null）
                exec.setSummaryToolUseId(null);
                exec.setSummaryDescription(description);
                // 注意: resume 不设 effectiveIsolation (worktree) — Step 18 按 resumedWorktreePath
                //   复用原 worktree, 不应触发新建隔离 worktree (CC resumeAgent.ts:82-97/192).
                exec.setSkillPreloader(skillPreloader);
                if (telemetry != null) exec.setTelemetry(telemetry);
                exec.setTranscriptClassifierEnabled(transcriptClassifierEnabled);
                // [IMP-SUB-25 返工 R2 接线归零] handoff 安全分类器注入（4 构造点对称）
                exec.setYoloClassifier(yoloClassifier);
                exec.setDeferContextModifier(true);
                exec.setContextFactory(contextFactory);
                // [IMP-G4 组11-1] Subagent hard_metrics + 按名路由注册表注入（resume is_async=true；
                //   resume 不 re-register name→agentId，CC resumeAgent.ts:198-205 skip name-registry）
                if (analyticsTracker != null) exec.setAnalyticsTracker(analyticsTracker);
                if (agentNameRegistry != null) exec.setAgentNameRegistry(agentNameRegistry);
                exec.setAsyncExecution(true);
                // fork resume: forkParentSystemPrompt 透传; 非 fork: 传 null → SubagentExecutor 自算系统提示
                // [RES-R2] agentIdOverride=ag 透传原键 · 对齐 CC resumeAgent.ts:240 override.agentId:
                //   SubagentExecutor Step 5 优先用原 agentId 续写 transcript/metadata (二次 resume 读到
                //   pre-resume transcript, 而非新键空 transcript).
                // [RES-R6] crs 透传 · 对齐 CC resumeAgent.ts:194 contentReplacementState:
                //   SubagentExecutor Step 20 注入 query loop session state（null = feature off 默认 create）。
                // [RES-SP31-1 返工] append 不在此透传（fork-only，CC 真源修正）：fork resume 的
                //   forkParentSystemPrompt（fp）已含 append（ResumeService rendered/重建路径），
                //   非 fork resume 系统提示不含 append（runAgent.ts:508-518）→ 无 append 通道。
                SubagentExecutor.ForkPathParams resumeForkParams = new SubagentExecutor.ForkPathParams(
                    null, null, fp, null, rm, rw, ag, crs);
                SubagentExecutor.SubagentResult res = exec.execute(
                    prompt, sel.agentType(), effectiveModel, resumeForkParams);
                if ("aborted".equals(res.status())) {
                    log.info("[SubagentTool] Resume async agent {} 被 kill: agentId={} partialLen={}",
                        sel.agentType(), ag, res.summaryText() != null ? res.summaryText().length() : 0);
                    AsyncAgentResult killed = AsyncAgentResult.killed(
                        res.summaryText(), res.usage(), res.totalTokens(),
                        res.totalDurationMs(), ag.toString());
                    finalizer.finalizeKilled(ag.toString(), killed);
                } else {
                    log.info("[SubagentTool] Resume async agent {} 完成: agentId={}", sel.agentType(), ag);
                    AsyncAgentResult success = AsyncAgentResult.success(
                        res.summaryText(), res.totalToolUseCount(),
                        res.totalDurationMs(), ag.toString(),
                        res.totalTokens(), res.usage());
                    finalizer.finalize(ag.toString(), success);
                }
            } catch (Exception e) {
                log.error("[SubagentTool] Resume async agent {} 失败: {}", sel.agentType(), e.getMessage(), e);
                AsyncAgentResult failure = AsyncAgentResult.failure(
                    "Subagent " + sel.agentType() + " resume failed: " + e.getMessage(),
                    ag.toString());
                finalizer.finalize(ag.toString(), failure);
            }
            } finally {
                // [IMP-D F4/M-05] 成对 restore 线程原值（null → 移除回落生效）。
                AutoMemPaths.restoreCurrentProjectRoot(prevResumeProjectRoot);
                // [reqId MDC 传播] 成对 restore 线程原值（null → 清理，防线程复用泄漏）。
                if (prevResumeMdc != null) {
                    MDC.setContextMap(prevResumeMdc);
                } else {
                    MDC.clear();
                }
            }
        }, "resume-subagent-" + ag);
        asyncWorker.setDaemon(true);
        asyncWorker.start();
        log.info("[SubagentTool] Resume async agent started: taskId={} agentId={} type={}",
            task.id(), agentId, selectedAgent.agentType());
    }

    /**
     * 创建子 Agent 工具池 · 对齐 CC resolveAgentTools (agentToolUtils.ts:122-225)
     * 的 4-SET 过滤 (ATS-14).
     *
     * <p>s12 方案 C：直接从 {@link #availableTools} 构建（值传递），
     * 不再通过 {@code ToolRegistry.all()} 间接获取，打破循环依赖。
     *
     * <p>[OPD-SP-32] 传子代理真实 {@link PermissionMode}（对齐 CC resolveAgentTools
     * agentToolUtils.ts:144-147 透传 permissionMode）：plan-mode agent 保留 ExitPlanMode
     * （agentToolUtils.ts:88-93 放行，绕过 ALL_AGENT_DISALLOWED_TOOLS + async 双过滤）。
     * 此前传 null → PLAN 分支永不命中 → plan agent 丢失 ExitPlanMode（S4-6 实锤）。
     *
     * <p>CC 真源 (Pattern #9): constants/tools.ts:36-88 4-SET +
     * agentToolUtils.ts:70-116 filterToolsForAgent 5 段过滤 +
     * :150-160 AgentDefinition.disallowedTools 精确剔除。
     *
     * @param selectedAgent 选中的 agent 定义
     * @param isAsync       async agent → ASYNC_AGENT_ALLOWED_TOOLS 白名单过滤
     */
    private ToolRegistry createSubagentToolRegistry(AgentDefinition selectedAgent, boolean isAsync) {
        return createSubagentToolRegistry(selectedAgent, isAsync, false);
    }

    /**
     * [IMP-PA-H-FORK-05] 3 参重载 · fork-resume 工具池构建。
     *
     * <p>CC 真源（resumeAgent.ts:161-164）：{@code workerTools = isResumedFork ? toolUseContext.options.tools
     * : assembleToolPool(...)} + :190 {@code ...(isResumedFork && { useExactTools: true })} —— fork-resume
     * 直接用父精确工具数组（不重滤），非 fork-resume 才按 agent 4-SET 组装。Java 旧实现 :3683 对 fork /
     * non-fork resume 走同一 createSubagentToolRegistry(sel, true) 4-SET 过滤 —— 差异确认：fork-resume
     * 被 4-SET 过滤（builtIn/async/disallowed/permissionMode）可能剔掉父拥有的工具 → 与父 cache prefix
     * 字节不一致（API tools 亦参与 prompt prefix）→ cache 命中率下降 + 工具语义漂移。
     *
     * <p>{@code useParentExactTools=true} 时绕过 4-SET 过滤直接注册 {@link #availableTools}（父精确工具池，
     * Java availableTools = 全量注册工具 = 主循环 buildBaseToolUseContext baseTools 同源）。
     *
     * @param selectedAgent       子代理定义
     * @param isAsync             是否异步（4-SET async 档，仅 useParentExactTools=false 时参与）
     * @param useParentExactTools true = fork-resume 父精确工具池（CC useExactTools）；false = 常规 4-SET 过滤
     * @return 子代理工具注册表
     */
    private ToolRegistry createSubagentToolRegistry(AgentDefinition selectedAgent, boolean isAsync,
                                                    boolean useParentExactTools) {
        ToolRegistry registry = new ToolRegistry();
        if (useParentExactTools) {
            // [FORK-05] fork-resume：父精确工具池（CC workerTools = toolUseContext.options.tools，
            //   resumeAgent.ts:161-164 + :190 useExactTools:true）——不重滤，保 cache prefix 与父一致
            if (availableTools != null) {
                for (Tool tool : availableTools) {
                    registry.register(tool);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[SubagentTool] FORK-05 fork-resume 用父精确工具池（不 4-SET 重滤）: {} 个工具"
                        + "（CC resumeAgent.ts:161-164 workerTools=options.tools + useExactTools）", registry.size());
            }
            return registry;
        }
        if (availableTools != null && !availableTools.isEmpty()) {
            boolean isBuiltIn = "built-in".equals(selectedAgent.source());
            java.util.List<Tool> filtered = com.nexusai.application.agent.tool.AgentToolUtils.filterToolsForAgent(
                availableTools,
                isBuiltIn,
                isAsync,
                resolvePermissionMode(selectedAgent), // CC original: permissionMode (agentToolUtils.ts:146) — plan 放行 ExitPlanMode
                selectedAgent.disallowedTools());
            for (Tool tool : filtered) {
                registry.register(tool);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] s12 方案 C：子 Agent 工具池创建完成，共 {} 个工具"
                    + " (4-SET 过滤: builtIn={} isAsync={} disallowed={})",
                registry.size(), "built-in".equals(selectedAgent.source()), isAsync,
                selectedAgent.disallowedTools().orElse(java.util.List.of()).size());
        }
        return registry;
    }

    /**
     * 解析 agent 定义的 permissionMode → {@link PermissionMode} · 对齐
     * {@code SubagentExecutor.resolvePermissionMode}（同一语义：mode 字符串大写化匹配
     * 枚举；空或非法 → DEFAULT）。
     *
     * <p>[OPD-SP-32] createSubagentToolRegistry 用它把真实 mode 传入
     * {@code AgentToolUtils.filterToolsForAgent}，使 plan-mode agent 命中
     * {@code agentToolUtils.ts:88-93} 的 ExitPlanMode 放行分支。
     *
     * @param agentDefinition 子代理定义
     * @return 解析后的权限模式（空/非法 → {@link PermissionMode#DEFAULT}）
     */
    private static PermissionMode resolvePermissionMode(AgentDefinition agentDefinition) {
        String mode = agentDefinition.permissionMode().orElse(null);
        if (mode == null) {
            return PermissionMode.DEFAULT;
        }
        try {
            return PermissionMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return PermissionMode.DEFAULT;
        }
    }

    /**
     * [IMP-PA-FORK-03] fork fallback：重建「父完整有效 system prompt」 · 对齐 CC AgentTool.tsx:499-511。
     *
     * <p>CC fallback 真源（AgentTool.tsx:499-511）：{@code defaultSystemPrompt = getSystemPrompt(...)}
     * 后 {@code buildEffectiveSystemPrompt({mainThreadAgentDefinition, toolUseContext,
     * customSystemPrompt: toolUseContext.options.customSystemPrompt, defaultSystemPrompt,
     * appendSystemPrompt: toolUseContext.options.appendSystemPrompt})} —— 即「default 组装 + custom/append」
     * 优先级组装父完整有效提示，而非子代理自身提示。
     *
     * <p>Java 原料映射（与 FORK-13 ResumeService.rebuildForkParentSystemPrompt 同构）：
     * <ul>
     *   <li>{@code customSystemPrompt} ← {@code AgentState.systemPrompt()}（CC
     *       {@code toolUseContext.options.customSystemPrompt}，Java 主循环 base TUC 等价承载）</li>
     *   <li>{@code appendSystemPrompt} ← {@code AgentState.appendSystemPrompt()}（CC
     *       {@code toolUseContext.options.appendSystemPrompt}，systemPrompt.ts:121 恒末尾）</li>
     *   <li>{@code defaultSystemPrompt} ← {@link SystemPromptAssembler#assemble}（CC
     *       {@code getSystemPrompt(toolUseContext.options.tools, options.mainLoopModel, ...)}，
     *       prompts.ts:444-449；enabledTools 取父 per-turn TUC availableTools，model 取
     *       {@code state.currentModel()}）</li>
     * </ul>
     *
     * <p>父 AgentState 不可得（registry 未注入 / 会话无状态，罕见/测试）→ 回落旧路径
     * {@link #getEffectiveSystemPrompt(AgentDefinition)}（子代理自身提示 + AgentToolSection，现行为
     * 保持）。重建异常同样回落（fail loud 以 warn 暴露，不阻断 fork spawn）。
     *
     * @param ctx          父 ToolUseContext（fork 触发时的 per-turn TUC；可为 null）
     * @param selectedAgent 当前 fork 子代理定义（仅回落旧路径时消费）
     * @return 父完整有效 system prompt；不可重建 → 旧路径产物
     */
    private String buildForkParentFallbackSystemPrompt(ToolUseContext ctx, AgentDefinition selectedAgent) {
        String sessionId = ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = RequestContext.sessionId();
        }
        AgentState state = (sessionAgentStateRegistry != null && sessionId != null && !sessionId.isBlank())
            ? sessionAgentStateRegistry.get(sessionId) : null;
        if (state != null) {
            try {
                String rebuilt = rebuildForkParentSystemPrompt(state, ctx);
                if (rebuilt != null && !rebuilt.isBlank()) {
                    return rebuilt;
                }
                log.warn("[IMP-PA-FORK-03] fork 父提示重建返回空（session={}）→ 回落旧路径", sessionId);
            } catch (Exception e) {
                log.warn("[IMP-PA-FORK-03] fork 父提示重建异常（session={}）→ 回落旧路径: {}",
                    sessionId, e.getMessage(), e);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[IMP-PA-FORK-03] fork 父提示重建跳过: 会话 {} 无 AgentState（registry 注入={}）"
                        + " → 回落旧 getEffectiveSystemPrompt",
                    sessionId, sessionAgentStateRegistry != null);
            }
        }
        return getEffectiveSystemPrompt(selectedAgent);
    }

    /**
     * [IMP-PA-FORK-03] fork 父提示实际重建 · 对齐 CC AgentTool.tsx:499-511 + FORK-13
     * ResumeService.rebuildForkParentSystemPrompt（:412-489）同构实现。
     *
     * <p>{@code getSystemPrompt(tools, mainLoopModel, additionalWorkingDirectories, mcpClients)}
     * Java 等价 = {@link SystemPromptAssembler#assemble}（enabledTools 取自父 per-turn TUC
     * {@code ctx.availableTools()}，回落本工具 availableTools；model = {@code state.currentModel()}
     * （runAgent.ts:131 等价）；additionalWorkingDirs 取 {@code ctx.additionalWorkingDirectories()}
     * keySet；mcpClients 取 {@code ctx.mcpClients()} 转 List&lt;McpClientInfo&gt;，connected=true），
     * 再经 {@link EffectiveSystemPromptBuilder#build}（custom=state.systemPrompt()、
     * append=state.appendSystemPrompt()，systemPrompt.ts:118-121 等价）。结果元素以 {@code "\n\n"}
     * 连接（ResumeService:482 同款）。
     *
     * <p><b>fail loud</b>：父模型不可得（state.currentModel() 空）→ null → 调用方回落旧路径
     * （不伪造字节，对齐 ResumeService:424-428）。
     *
     * @param state 父会话 AgentState（custom/append/model/sessionId 源，恒非 null）
     * @param ctx   父 ToolUseContext（可为 null → 回落 state.currentToolUseContext() / 本工具工具池）
     * @return 重建后的父完整有效 system prompt；父模型不可得 → null
     */
    private String rebuildForkParentSystemPrompt(AgentState state, ToolUseContext ctx) {
        ToolUseContext tuc = (ctx != null) ? ctx : state.currentToolUseContext();
        // enabledTools · CC getSystemPrompt(toolUseContext.options.tools, ...) 等价（prompts.ts:444-449）
        List<Tool> tools = (tuc != null && tuc.availableTools() != null && !tuc.availableTools().isEmpty())
            ? tuc.availableTools()
            : (this.availableTools != null ? this.availableTools : List.of());
        Set<String> enabledTools = new LinkedHashSet<>();
        for (Tool t : tools) {
            if (t != null && t.name() != null && !t.name().isBlank()) {
                enabledTools.add(t.name());
            }
        }
        // model · CC options.mainLoopModel 等价（runAgent.ts:131）；不可得 → null → 回落旧路径
        String model = state.currentModel();
        if (model == null || model.isBlank()) {
            log.warn("[IMP-PA-FORK-03] fork 父提示重建跳过: 父模型不可得（state.currentModel()=null）→ 回落旧路径");
            return null;
        }
        // additionalWorkingDirs · CC additionalWorkingDirectories（resumeAgent.ts:126-128 等价）
        java.util.List<String> additionalWorkingDirs =
            (tuc != null && tuc.additionalWorkingDirectories() != null)
                ? new java.util.ArrayList<>(tuc.additionalWorkingDirectories().keySet())
                : java.util.List.of();
        // mcpClients · CC toolUseContext.options.mcpClients → List<McpClientInfo>（connected=true）
        java.util.List<McpClientInfo> mcpClients =
            (tuc != null && tuc.mcpClients() != null)
                ? tuc.mcpClients().entrySet().stream()
                    .map(e -> new McpClientInfo(e.getKey(),
                        e.getValue() != null ? e.getValue().instructions() : null, true))
                    .collect(java.util.stream.Collectors.toList())
                : java.util.List.of();
        SystemPromptAssembler assembler = new SystemPromptAssembler(state.systemPromptSectionCache());
        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(
            enabledTools, model, additionalWorkingDirs, mcpClients,
            null,           // outputStyleConfig（Java 无输出风格配置注入）
            List.of(),      // skillToolCommands（无 SkillCatalog 通道）
            null,           // language（Java 无语言设置通道）
            null,           // memoryLoader（无 memoryStorage 通道）
            false,          // tokenBudgetEnabled（fork 无 TOKEN_BUDGET flag 通道 · CC prompts.ts:538）
            state.sessionId());  // [cwd-session] env_info_simple 会话 cwd
        SystemPrompt effective = EffectiveSystemPromptBuilder.build(
            () -> assembler.assemble(input),
            null,                                   // overrideSystemPrompt（CC AgentTool.tsx:504-510 调用点不传）
            state.systemPrompt(),                   // customSystemPrompt（:118-119 替换 default）
            state.appendSystemPrompt());            // appendSystemPrompt（:121 恒末尾）
        String rebuilt = String.join("\n\n", effective.elements());
        if (log.isDebugEnabled()) {
            log.debug("[IMP-PA-FORK-03] fork 父提示已重建: session={}, custom={}, append={}, model={}, "
                    + "tools={}, additionalWorkingDirs={}, mcpClients={}, 元素数={}, 长度={}",
                state.sessionId(), state.systemPrompt() != null, state.appendSystemPrompt() != null,
                model, enabledTools.size(), additionalWorkingDirs.size(), mcpClients.size(),
                effective.elements().size(), rebuilt.length());
        }
        return rebuilt;
    }

    /**
     * 获取有效系统提示（对齐 CC AgentTool.tsx:534）
     *
     * <p>[IMP-SP-SUB] basePrompt 改指 {@link AgentToolSection#get()}（CC getAgentToolSection
     * prompts.ts:316-320 非 fork 变体），替代已删除的伪真源（CC 无 code.py/SUB_SYSTEM）。
     * 非 fork 消费点 executeSync(:2795)/executeAsync(:3122)/executeResumeAsync(:3262)/降级
     * (:3660) 均经本方法落到新源（子代理自身提示 + AgentToolSection）。
     *
     * <p>[IMP-PA-FORK-03] fork fallback（:1974 区）不再直调本方法：优先走
     * {@link #buildForkParentFallbackSystemPrompt(ToolUseContext, AgentDefinition)} 重建父完整有效
     * system prompt（default 组装 + custom/append，CC AgentTool.tsx:499-511）；仅父 AgentState
     * 不可得（罕见/测试）时经其回落本方法（现行为保持）。本方法本体不改，非 fork 消费点语义零变化。
     */
    private String getEffectiveSystemPrompt(AgentDefinition selectedAgent) {
        String agentPrompt = selectedAgent.getSystemPrompt(null, List.of());
        // [FIX-AM REQ-M-19] 补 memory 注入路径（对齐 CC loadAgentsDir.ts:481-488/726-732
        //   getSystemPrompt 闭包：systemPrompt + '\n\n' + loadAgentMemoryPrompt(...)）。
        //   forkParentSystemPrompt（:990）与 fallbackSystemPrompt（:1392/1564/1654）均经本方法，
        //   memory agent 的真实注入在 SubagentExecutor.buildAgentSystemPrompt(:1854) 生成期追加；
        //   本处补注入覆盖 fork path（父 rendered bytes 直接透传，不经 buildAgentSystemPrompt）。
        //   [OPD-CM5-F-25] isAutoMemoryEnabled 门控在调用方（CC if (isAutoMemoryEnabled() && memory)）；
        //   本处补注入覆盖 fork path + 非空检查，正常 agent（prompt min1 非空）单次注入。
        if (com.nexusai.application.agent.skill.BundledSkillEnabledGates.isAutoMemoryEnabled()
                && selectedAgent.memory() != null && selectedAgent.memory().isPresent()
                && !selectedAgent.memory().get().isBlank()) {
            com.nexusai.application.agent.agent.AgentMemoryDirectory.AgentMemoryScope scope =
                com.nexusai.application.agent.agent.AgentMemoryDirectory.fromName(selectedAgent.memory().get());
            if (scope != null) {
                String memoryPrompt = com.nexusai.application.agent.agent.AgentMemoryDirectory
                        .productionDefault()
                        .loadAgentMemoryPrompt(selectedAgent.agentType(), scope);
                if (memoryPrompt != null && !memoryPrompt.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[SubagentTool] getEffectiveSystemPrompt 补 agent-memory 注入: agentType={} scope={}",
                            selectedAgent.agentType(), scope);
                    }
                    agentPrompt = agentPrompt + "\n\n" + memoryPrompt;
                }
            }
        }
        String basePrompt = AgentToolSection.get();
        if (log.isDebugEnabled()) {
            log.debug("[SubagentTool] getEffectiveSystemPrompt 构建: agentPrompt.len={}, AgentToolSection.len={}",
                agentPrompt == null ? 0 : agentPrompt.length(), basePrompt.length());
        }
        return agentPrompt + "\n\n" + basePrompt;
    }

    /**
     * [Session S2] List&lt;AgentDefinition&gt; → Map&lt;String,AgentDefinition&gt; · 供 AgentDefinitionRegistry
     * 构造（构造器签名收 Map builtIn）。消费方改调 {@link BuiltInAgents#getBuiltInAgents()} 动态列表
     * （对齐 CC builtInAgents.ts:22-72），toMap 做 List→Map 适配。
     */
    private static java.util.Map<String, AgentDefinition> toMap(List<AgentDefinition> agents) {
        java.util.Map<String, AgentDefinition> m = new java.util.HashMap<>();
        if (agents != null) {
            for (AgentDefinition a : agents) {
                if (a != null) m.put(a.agentType(), a);
            }
        }
        return m;
    }

    private String getString(JsonNode input, String field) {
        return input.has(field) ? input.get(field).asText() : "";
    }

    private String getStringOrNull(JsonNode input, String field) {
        return input.has(field) ? input.get(field).asText() : null;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
