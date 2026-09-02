package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.chat.ChatService;

import java.util.function.Supplier;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.UserInputDispatcher;
import com.nexusai.application.agent.command.CompactCommand;
import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactConversationContext;
import com.nexusai.application.agent.compact.CompactWarningState;
import com.nexusai.application.agent.attachment.ImageAttachmentStore;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.PostCompactAttachmentRestorer;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.StreamCompactSummary;
import com.nexusai.application.agent.compact.TokenCounter;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.CacheSafeParamsHolder;
import com.nexusai.application.agent.lsp.LspManager;
import com.nexusai.application.agent.plugin.BuiltinPluginRegistry;
import com.nexusai.application.agent.plugin.PluginLoader;
import com.nexusai.application.agent.skill.CreateSkillCommand;
import com.nexusai.application.agent.skill.McpSkillBuilders;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.skill.ParseSkillFrontmatter;
import com.nexusai.application.agent.skill.PromptShellExecutor;
import com.nexusai.application.agent.subagent.SkillPreloader;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.skillsearch.DiscoverSkillsTool;
import com.nexusai.application.agent.tasks.MonitorMcpTaskRunner;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.EnableLspToolCondition;
import com.nexusai.application.agent.tool.impl.LspTool;
import com.nexusai.application.agent.tool.impl.SkillToolImpl;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.application.agent.prompt.AgentToolSection;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptAssemblyInput;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.PromptAlignSettingsResolver;
import com.nexusai.application.agent.prompt.UserContextProvider;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.impl.TodoWriteTool;
import com.nexusai.application.agent.tool.impl.VisionAnalyzeTool;
import com.nexusai.application.agent.tool.impl.CtxInspectTool;
import com.nexusai.application.agent.tool.impl.TerminalCaptureTool;
import com.nexusai.application.agent.tool.impl.VerifyPlanExecutionTool;
import com.nexusai.application.agent.tool.impl.MonitorTool;
import com.nexusai.application.agent.tool.impl.SnipTool;
import com.nexusai.application.agent.tool.impl.TestingPermissionTool;
import com.nexusai.application.agent.workflow.command.WorkflowCommandLoader;
import com.nexusai.application.agent.workflow.wiring.WorkflowToolWiring;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.mcp.McpServerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具注册配置 · 对齐 CC tools.ts:208-220
 *
 * <p>根据 {@link TaskSystemConfig#isTodoV2Enabled()} 决定注册哪些工具：
 * <ul>
 *   <li>V1 模式：注册 {@link TodoWriteTool}</li>
 *   <li>V2 模式：注册 {@link TaskCreateTool}, {@link TaskGetTool}, {@link TaskUpdateTool}, {@link TaskListTool}</li>
 * </ul>
 *
 * <h2>CC 对齐</h2>
 * <pre>
 * // CC tools.ts:218-220
 * ...(isTodoV2Enabled()
 *   ? [TaskCreateTool, TaskGetTool, TaskUpdateTool, TaskListTool]
 *   : []),
 * </pre>
 */
@Configuration
public class ToolRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistrationConfig.class);

    /**
     * P2-2: user（userSettings）技能源加载开关 · CC original: isSettingSourceEnabled('userSettings')
     * （settings/constants.ts:174-177，CLI --settings 状态驱动；Java Web 无 CLI，concern #2 补开关）。
     * 缺省 true 对齐 CC 全源启用（state.ts:313-319）；注入 SkillsLoader.setUserSkillsEnabledSupplier。
     */
    @Value("${nexusai.skill.sources.user-settings:true}")
    private boolean userSettingsSkillSourceEnabled = true;

    /**
     * P2-2: project（projectSettings）技能源加载开关 · CC original: isSettingSourceEnabled('projectSettings')
     * （settings/constants.ts:174-177）。缺省 true 对齐 CC 全源启用；注入
     * SkillsLoader.setProjectSkillsEnabledSupplier。project/additional/bare 三处共用（loadSkillsDir.ts:651-652）。
     */
    @Value("${nexusai.skill.sources.project-settings:true}")
    private boolean projectSettingsSkillSourceEnabled = true;

    /** LspManager 是 @Component Spring bean, 注入供 lspTool() 用. */
    @Autowired
    private LspManager lspManager;

    /**
     * P1-1: McpServerService 是 @Service Spring bean, 注入 skillRegistry() 激活 MCP 技能源
     * (对齐 CC commands.ts:547-559 getMcpSkillCommands).
     */
    @Autowired(required = false)
    private McpServerService mcpServerService;

    /**
     * P1-1: BuiltinPluginRegistry 当前<b>非</b> Spring bean (public final class, 无 @Component),
     * 且生产中无任何地方注册 builtin plugin. 此处 required=false 注入保持 null (setter null-safe),
     * 使接线"就绪"——后续 PR 若注册 {@code @Bean BuiltinPluginRegistry} 并 registerBuiltinPlugin
     * 即自动激活 builtin plugin 技能源. 当前该源保持 inactive (follow-up).
     */
    @Autowired(required = false)
    private BuiltinPluginRegistry builtinPluginRegistry;

    /**
     * MPL6: PluginLoader 是 @Component Spring bean · 注入 skillRegistry() 激活 plugin 命令/技能源
     * (对齐 CC loadPluginCommands.ts:414 getPluginCommands / :840 getPluginSkills feed 链)。
     * required=false 容错：非 Spring 直构测试 / 无 bean 场景 → null → SkillRegistry.setPluginLoader
     * 未接线 → getAllCommands 无 plugin 源，行为不变（POJO 测试兼容）。
     */
    @Autowired(required = false)
    private PluginLoader pluginLoader;

    /**
     * P2-9: BundledSkillFeatureFlags bean（BundledSkillFeatureFlagsConfig @EnableConfigurationProperties 注册）·
     * 供 skillRegistry() 给 McpServerService 注入 MCP_SKILLS 门控（CC commands.ts:550 feature('MCP_SKILLS')）。
     * required=false 容错：非 Spring 直构测试 / 无 bean 场景 → null → skillRegistry() 回退 DEFAULTS（mcpSkills=false，P1-9）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.skill.BundledSkillFeatureFlags bundledSkillFeatureFlags;

    /**
     * P3-5: skill-search 索引清除契约宿主 · CC original: {@code clearSkillIndexCache}
     * （commands.ts:96-99 {@code feature('EXPERIMENTAL_SKILL_SEARCH') ?
     * require('./services/skillSearch/localSearch.js').clearSkillIndexCache : undefined}）。
     *
     * <p>required=false 容错：SkillDiscoveryPrefetch 为 POJO（非 @Component/@Bean），生产注入
     * 不到 → null → skillRegistry() 接线 no-op（对齐 CC flag-off 时 clearSkillIndexCache 为
     * undefined、commands.ts:531 {@code clearSkillIndexCache?.()} 短路）。组合根职责 = 把
     * {@code clearSkillIndexCache()} 委托为 {@link SkillRegistry#setSkillIndexClearer} 的 Runnable。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.skill.SkillDiscoveryPrefetch skillDiscoveryPrefetch;

    /**
     * [C-30] EXPERIMENTAL_SKILL_SEARCH feature flag · 默认全关（对齐 CC flag-off → DiscoverSkillsTool 不注册）。
     *
     * <p>required=false + 默认 {@code ALL_DISABLED}：生产无 FeatureFlags bean 注入时 flag 关闭，
     * DiscoverSkillsTool 不注册（对齐 CC prompts.ts:90-93 {@code DISCOVER_SKILLS_TOOL_NAME = feature(...) ? ... : null}）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.loop.FeatureFlags featureFlags =
        com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;

    /**
     * [V52 X1-3] 压缩配置 DB 实时读源 · @Autowired(required=false) 字段（同 {@link #featureFlags}
     * 装配模式）：注入 SnipTool/CtxInspectTool 门控（DB settings.history_snip_enabled /
     * context_collapse_enabled 覆盖 FeatureFlags，null 回落）。CompactSettingsResolver 为
     * CompactThresholdConfig @Bean，required=false 容错直构测试。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver;

    /**
     * [monitor-rework] MONITOR_MCP 流式监控执行器（@Component）· MonitorTool 生产接线注入。
     *
     * <p>WHY: MonitorTool 从 stub 升级为真实现，execute 需经 {@code registerTask + monitor}
     * 接通 MCP 状态监控。required=false 容错：非 Spring 直构测试 / 无 bean 场景 → null →
     * MonitorTool 构造时 runner=null → execute fail-loud（DEC-3 错误处理，不假启动）。
     */
    @Autowired(required = false)
    private MonitorMcpTaskRunner monitorMcpTaskRunner;

    /**
     * [IMP2-03] 任务框架服务（async-agent 附件数据源）· CC appState.tasks local_agent
     * （compact.ts:1571-1574）。@Bean 在 TaskConfiguration；required=false 容错直构测试。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.tasks.TaskFrameworkService taskFrameworkService;

    /**
     * [IMP2-03] plan 文件提供者（plan_file_reference/plan_mode 数据源）· CC getPlan/
     * getPlanFilePath（plans.ts:119-145）。Java 无 plan 文件机制（PlanProvider javadoc）→
     * 生产无 bean → null → plan 附件降级不注入（concern B N/A）；测试可注入假实现。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.compact.PlanProvider planProvider;

    /**
     * 注册 TodoWrite 工具（V1 模式）
     * 注册「非 @Component/@Bean Tool 通道」的工具集合（{@code List<Tool>} 类型 bean）。
     *
     * <p>本 bean 返回类型是 {@code List<Tool>}（非 {@code Tool}），Spring 集合注入
     * {@code @Autowired List<Tool>} 不展开其元素 → 由 {@link ToolRegistry} 以
     * {@code @Resource(name="todoTaskTools")} 显式注入并在 {@code init()} 内
     * {@link ToolRegistry#registerAll(List)} 逐个注册（B5 OPD-TOOL-02-1）。
     *
     * <p>内容：V1 的 {@link TodoWriteTool} + feature 门控的 {@link DiscoverSkillsTool} + 8 个
     * feature/env 门控桩。SkillTool / LSPTool 不在此 List（已作独立 {@code @Bean Tool} 收集）。
     *
     * <p>s12-3.2: Task V2 工具（TaskCreate/TaskGet/TaskUpdate/TaskList）
     * 通过 @Component 自动注册到 ToolRegistry，不再在此处手动创建。
     */
    @Bean
    public List<Tool> todoTaskTools() {
        List<Tool> tools = new ArrayList<>();

        if (!TaskSystemConfig.isTodoV2Enabled()) {
            // V1 模式：注册 TodoWrite 工具
            // [todo-rest-stream] 注入 wsTemplate（STOMP 推流）+ sessionStateResolver（AgentState 同步/REST 读侧）
            //   —— 两者 @Autowired(required=false) 字段缺省 null 时不接线（测试/孤立运行降级 no-op）。
            log.info("Task V1 enabled: registering TodoWrite tool");
            TodoWriteTool todoWrite = new TodoWriteTool();
            if (wsTemplate != null) {
                todoWrite.setWsTemplate(wsTemplate);
            }
            if (sessionAgentStateRegistry != null) {
                todoWrite.setSessionStateResolver(sessionAgentStateRegistry::get);
            }
            if (sessionMapper != null) {
                todoWrite.setSessionMapper(sessionMapper);
            }
            log.info("[todo-rest-stream] TodoWrite 装配: wsTemplate={} sessionStateResolver={} sessionMapper={}"
                    + "（V1 分支接线，照抄 skillTool() setSessionStateResolver 模式；"
                    + "sessionMapper 为 [R3] sessions.todos DB 持久化通道）",
                wsTemplate != null, sessionAgentStateRegistry != null, sessionMapper != null);
            tools.add(todoWrite);
        } else {
            log.info("Task V2 enabled: Task tools registered via @Component auto-injection");
        }

        // B5 (OPD-TOOL-02-1): SkillTool / LSPTool 不再加入本 List —— 二者已作为独立
        // `@Bean Tool` 方法（skillTool() / lspTool()）被 ToolRegistry 构造器的
        // `@Autowired List<Tool>` 收集为 Tool bean（对齐 CC tools.ts:212 SkillTool / :224 LSPTool
        // 各自独立数组元素，非「List 嵌套再展开」）。保留在 List 内会在 registerAll 时同名重复
        // 覆盖产生误导 warn（同实例无害，但属错误接线残留），故移除以对齐 CC 扁平数组语义。

        // [C-30] DiscoverSkills 工具注册 · feature-gated（对齐 CC prompts.ts:90-93
        //   DISCOVER_SKILLS_TOOL_NAME = feature('EXPERIMENTAL_SKILL_SEARCH') ? require(...).name : null；
        //   flag-off → 工具不存在。默认 ALL_DISABLED → 不注册，生产行为零变化）。
        if (featureFlags != null && featureFlags.skillPrefetch()) {
            tools.add(new DiscoverSkillsTool(featureFlags));
            log.info("[C-30] EXPERIMENTAL_SKILL_SEARCH 开启 → 注册 DiscoverSkillsTool（CC prompts.ts:90-93）");
        } else if (log.isDebugEnabled()) {
            log.debug("[C-30] EXPERIMENTAL_SKILL_SEARCH flag 关闭 → DiscoverSkillsTool 不注册（对齐 CC prompt.js 缺失 + flag-off null）");
        }

        // ── [B5 OPD-10] CC feature/env 门控工具注册桩 ──
        // 对齐 CC tools.ts getAllBaseTools 各门控 spread（flag 关 → 工具 null/不注册）：
        //   CtxInspectTool:   tools.ts:110-111/222（CONTEXT_COLLAPSE）
        //   TerminalCaptureTool: tools.ts:113-115/223（TERMINAL_PANEL）
        //   VerifyPlanExecutionTool: tools.ts:91-94/231（CLAUDE_CODE_VERIFY_PLAN==='true'）
        //   WorkflowTool:     tools.ts:129-133/233（WORKFLOW_SCRIPTS）
        //   MonitorTool:      tools.ts:39-40/237（MONITOR_TOOL）
        //   SnipTool:         tools.ts:123-124/243（HISTORY_SNIP）
        //   TestingPermissionTool: tools.ts:244（NODE_ENV==='test'）
        // [G30⑫] OverflowTestTool 已删除 — CC 无功能 Tool（tools.ts:107-108/221 OVERFLOW_TEST_TOOL
        // 在 CC 为纯测试工具，Java 无对应测试通道；注册 + 常量 + AutoModeAllowlist 同步清理）。
        // [V52 X1-3] CtxInspectTool.isEnabled() DB-aware：注入 settingsResolver（null 回落 FeatureFlags）
        registerFeatureGatedStub(tools, featureFlags != null && featureFlags.contextCollapse(),
            featureFlags, "CONTEXT_COLLAPSE（CC tools.ts:110/222）", flags -> {
                CtxInspectTool t = new CtxInspectTool(flags);
                t.setSettingsResolver(settingsResolver);
                return t;
            });
        registerFeatureGatedStub(tools, featureFlags != null && featureFlags.terminalPanel(),
            featureFlags, "TERMINAL_PANEL（CC tools.ts:113/223）", TerminalCaptureTool::new);
        registerFeatureGatedStub(tools, featureFlags != null && featureFlags.verifyPlan(),
            featureFlags, "CLAUDE_CODE_VERIFY_PLAN（CC tools.ts:91/231）", VerifyPlanExecutionTool::new);
        registerFeatureGatedStub(tools, featureFlags != null && featureFlags.workflowScripts(),
            featureFlags, "WORKFLOW_SCRIPTS（CC tools.ts:129/233）", WorkflowToolWiring::createWorkflowToolCore);
        // [monitor-rework] MonitorTool 真实现注册：flag 开 → 注册并注入 MonitorMcpTaskRunner
        // （execute 生产接线 registerTask+monitor）；flag 关 → 不注册（对齐 CC flag-off null）。
        // 不走通用 registerFeatureGatedStub（其 factory 只收 FeatureFlags，无法传 runner）。
        if (featureFlags != null && featureFlags.monitorTool()) {
            tools.add(new MonitorTool(featureFlags, monitorMcpTaskRunner));
            log.info("[B5] MONITOR_TOOL（CC tools.ts:39/237）门控开启 → 注册 MonitorTool 真实现"
                + "（monitorMcpTaskRunner={}）", monitorMcpTaskRunner != null);
        } else if (log.isDebugEnabled()) {
            log.debug("[B5] MONITOR_TOOL（CC tools.ts:39/237）门控关闭 → 不注册（对齐 CC flag-off null）");
        }
        // [V52 X1-3] SnipTool.isEnabled() DB-aware：注入 settingsResolver（null 回落 FeatureFlags）
        registerFeatureGatedStub(tools, featureFlags != null && featureFlags.historySnip(),
            featureFlags, "HISTORY_SNIP（CC tools.ts:123/243）", flags -> {
                SnipTool s = new SnipTool(flags);
                s.setSettingsResolver(settingsResolver);
                return s;
            });
        registerFeatureGatedStub(tools, featureFlags != null && featureFlags.testingPermission(),
            featureFlags, "NODE_ENV==='test'（CC tools.ts:244）", TestingPermissionTool::new);

        // [IMP-SP-07] 工具注册失效接线：工具 @Bean 构建完成 → 对活跃会话清 system prompt section 缓存
        // （对齐 CC clearSystemPromptSections；启动期注册表空 → no-op，运行时工具集合变化触发点已登记，
        //  见 invalidateActiveSessionSystemPromptSections Javadoc）。
        invalidateActiveSessionSystemPromptSections("工具注册(todoTaskTools @Bean)");

        return tools;
    }

    /**
     * [B5 OPD-10] feature 门控桩注册 helper · 对齐 CC tools.ts getAllBaseTools spread。
     *
     * <p>flag 开 → 注册桩（{@code ...(Tool ? [Tool] : [])} 等价）；flag 关 → 不注册
     * （对齐 CC flag 关 → 工具 null，Java 端 FeatureFlags 默认 ALL_DISABLED → 生产零注册）。
     * 桩自身 isEnabled() 亦反射同一 flag（双保险，见各 impl）。
     */
    private void registerFeatureGatedStub(List<Tool> tools, boolean gateOn,
                                          com.nexusai.application.agent.loop.FeatureFlags featureFlags, String ccRef,
                                          java.util.function.Function<com.nexusai.application.agent.loop.FeatureFlags, Tool> factory) {
        if (gateOn) {
            tools.add(factory.apply(featureFlags));
            log.info("[B5] {} 门控开启 → 注册工具（feature 门控，VerifyPlan 等已真实现非 stub）", ccRef);
        } else if (log.isDebugEnabled()) {
            log.debug("[B5] {} 门控关闭 → 不注册（对齐 CC flag-off null）", ccRef);
        }
    }

    /**
     * [IMP-SP-07] 工具注册失效接线 · 对齐 CC {@code clearSystemPromptSections}（systemPromptSections.ts:65-68）
     * 的工具注册触发。
     *
     * <p><b>触发时机（concern 现场确认）</b>：本 Session 选择 ToolRegistrationConfig @Bean 构建后
     * （context-refresh 语义）接线 —— 启动期注册表为空 → no-op。真正的运行时工具集合变化
     * （MCP {@code ToolRegistry.assembleToolPool} 每轮刷新）不在 IMP-SP-07 §4 范围（ToolRegistry 禁改），
     * 待 IMP-SP-08 评估把失效点移到工具集合实际变化处；每轮 assemble 若直接 clear 会破坏缓存命中
     * （CC 工具集为启动期静态 + MCP 增删事件驱动，非每轮）。
     *
     * @param trigger 触发源描述（日志定位用）
     */
    private void invalidateActiveSessionSystemPromptSections(String trigger) {
        if (sessionAgentStateRegistry == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ToolRegistrationConfig] {} 失效接线：SessionAgentStateRegistry 未接线 → 跳过", trigger);
            }
            return;
        }
        String sessionIdStr = com.nexusai.common.RequestContext.sessionId();
        if (sessionIdStr == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ToolRegistrationConfig] {} 失效接线：启动期无会话（MDC 无 sessionId）→ no-op（CC 语义：工具清单变化后 sections 重算）", trigger);
            }
            return;
        }
        // [session-id-short] MDC sessionId 已 short 直键 registry（不再 UUID.fromString）
        AgentState state = sessionAgentStateRegistry.get(sessionIdStr);
        if (state == null) {
            if (log.isDebugEnabled()) {
                log.debug("[ToolRegistrationConfig] {} 失效接线：会话 {} 无活跃 AgentState → 跳过", trigger, sessionIdStr);
            }
            return;
        }
        state.systemPromptSectionCache().clear();
        log.info("[ToolRegistrationConfig] {} 失效接线：会话 {} 的 system prompt section 缓存已清空（工具清单变化 → sections 重算）",
            trigger, sessionIdStr);
    }

    /**
     * P1-2: 单一 SkillRegistry @Bean · SkillTool 与 SkillCatalog 共享同一实例.
     *
     * <p>此前 {@code skillTool()} 与 {@code skillCatalog()} 各自 {@code new SkillRegistry(...)}
     * (两个独立 POJO 实例, 旧注释谎称"共享"). 现抽出单一 bean, 二者消费同一实例, 保证
     * SkillTool 实际可调用的技能与 SkillCatalog 注入给 LLM 的技能列表一致.
     *
     * <p>P1-1: 在此注入 plugin/MCP 生产实例 (setter null-safe, 对齐 CC getCommands 4 源聚合):
     * <ul>
     *   <li>{@code mcpServerService} (@Service) → 真实注入, 激活 MCP 技能源</li>
     *   <li>{@code builtinPluginRegistry} (当前非 bean) → null, builtin plugin 源留 follow-up</li>
     * </ul>
     *
     * <p>SkillRegistry 本身非 Spring bean 类型 (POJO), 但此 @Bean 方法把它纳入容器单例管理.
     * skillsRoot 动态 = {@code NexusaiPaths.getProjectDirName() + "/skills"}（.{appName}/skills，
     * 决策 D1/D6 全动态，R12-3）。
     *
     * <p>方案1（用户拍板）: 注入 {@link #commandMapper}（DB enabled 主控源）—— 前端 PATCH
     * toggle / update 写 DB enabled → loadAllCommands 加载时覆盖文件默认 enabled（用户 skill
     * 启用/禁用真实生效）；CommandService 在 DB 变更后调 registry.refreshCommandsOnly()
     * （方案2）清缓存，下次 getAllCommands 重载读 DB 生效。
     */
    /**
     * P1-2: 动态技能管理器（@Component bean）· 注入 skillRegistry() 激活条件分离 + 动态技能叠加。
     * required=false 容错（非 Spring 直构测试 / 无 bean 场景 → null，skillRegistry 行为不变）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.skill.DynamicSkillsManager dynamicSkillsManager;

    /**
     * [P1-6] 会话级主 AgentState 注册表 · 经 setter 注入 SkillToolImpl.sessionStateResolver，
     * 供 doExecute inline 路径 addInvokedSkill（压缩存活写入侧）按 sessionId 解析主会话 AgentState。
     * {@code @Autowired(required=false)}：本类 bean 恒存在（@Component 无依赖），缺省 null 时
     * 写入侧 resolver 未接线 → debug 日志 skip（null-safe 降级，测试可手动注入）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.SessionAgentStateRegistry sessionAgentStateRegistry;

    /**
     * [MG-2 · IMP-BACK-3] STOMP 模板 · manual /compact 推送上下文 sender（decisions-log §32
     * token_warning 事件契约）。auto 路径经 LlmAgentLoop.setStreamContext 注入同一 bean
     * （LlmAgentLoop:1485-1499）；此处 required=false 注入，供 {@link #handleCompactCommand}
     * 注册 {@link CompactWarningState.SessionPushContext}。非 STOMP 路径 / 直构测试 → null →
     * 不注册推送上下文，CompactCommand 3 个 suppress 调用点仅写 store + 通知订阅者（行为不回归）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    /**
     * [R3 持久升级] 会话级 SessionMapper · TodoWrite Step5.6 sessions.todos DB 持久化通道
     * （V43 列，跨 send/重启会话 todo 真源）。required=false 容错：非 Spring 直构测试 /
     * 无 bean 场景 → null → TodoWriteTool Step5.6 warn+skip（AC-5 隔离，行为不变）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.repository.session.mapper.SessionMapper sessionMapper;

    /**
     * 方案1: CommandMapper · DB enabled 主控源（用户 skill 启用/禁用）。经
     * {@link #skillRegistry()} 注入 {@code registry.setCommandMapper} —— 前端 PATCH toggle /
     * update 写 DB enabled → loadAllCommands 加载时覆盖文件默认 enabled（前端禁用/启用真实生效）。
     * CommandMapper 是 MyBatis-Flex mapper（由 MyBatis 扫描注册为 bean）；required=false 容错
     * 直构测试（无 mapper → 不接线，SkillRegistry 行为不变）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.repository.command.mapper.CommandMapper commandMapper;

    @Bean
    public SkillRegistry skillRegistry() {
        // R12-3: POJO skillsRoot 改动态（决策 D1/D6 全动态）· 项目级 nexusai 目录名 = getProjectDirName()
        //   （.{appName}），缺 nexusai 变体 → appName 变（spring.application.name）时 skillsRoot 仍联动；
        //   仅 POJO/测试回退分支，appName=nexusai 时 = ".nexusai/skills"（原 ".claude/skills" 等价语义）。
        SkillRegistry registry = new SkillRegistry(NexusaiPaths.getProjectDirName() + "/skills");
        // P2-13: 注册 MCP skill builders（write-once leaf registry）· 等价 CC loadSkillsDir.ts:1083
        //   模块 init eager 注册（registerMCPSkillBuilders 在模块求值时执行）。Java 侧 SkillRegistry @Bean
        //   init 注册，保证任意 MCP 连接（McpServerService.start）前 builders 已注册。
        //   SkillsLoader 是 POJO（new SkillsLoader() SkillRegistry:73），不可作注册宿主。
        McpSkillBuilders.register(new McpSkillBuilders.Builders(
            CreateSkillCommand::create,
            ParseSkillFrontmatter::parseSkillFrontmatterFields));
        // P1-1: 注入 plugin/MCP 源 (setter 内部 null 安全)
        registry.setMcpServerService(mcpServerService);
        // P2-9: MCP_SKILLS feature 门控（CC commands.ts:550/:558）· Spring 配置源注入
        //   (nexusai.skill.features.mcp-skills → BundledSkillFeatureFlags.mcpSkills)；
        //   mcpServerService 为 null 时 gate 不接线（thread-in 源缺失，findCommandIncludingMcp 退化为本地）。
        if (mcpServerService != null) {
            com.nexusai.application.agent.skill.BundledSkillFeatureFlags skillFlags =
                bundledSkillFeatureFlags != null ? bundledSkillFeatureFlags
                    : com.nexusai.application.agent.skill.BundledSkillFeatureFlags.DEFAULTS;
            mcpServerService.setMcpSkillsGate(() -> skillFlags.mcpSkills());
        }
        registry.setBuiltinPluginRegistry(builtinPluginRegistry);
        // MPL6: 注入 PluginLoader → plugin 命令/技能源激活（对齐 CC loadPluginCommands.ts:414/:840
        //   getPluginCommands/getPluginSkills feed 链 · commands.ts:465-466 合并序）
        registry.setPluginLoader(pluginLoader);
        // P1-3: workflow 命令源（对齐 CC getWorkflowCommands commands.ts:401-406 + loadAllCommands
        //   第 4 源 :457/:464）· WORKFLOW_SCRIPTS feature 门控。
        //   W-4a 落地：feature on → 注入 WorkflowCommandLoader（namedWorkflowCommands.ts:10-34
        //   getWorkflowCommands 等价）；feature off → provider 不注入（null = 无 workflow 命令，
        //   对齐 CC :457 Promise.resolve([])）。projectRoot 经 AutoMemPaths.currentSessionProjectRoot
        //   每调用新鲜解析（与 getSkillDirCommands 的 cwdSupplier 同源 —— 会话绑定项目根，
        //   memory：session-bound-dir-is-cc-startup-dir；CC getWorkflowCommands(cwd=getProjectRoot())）。
        if (featureFlags != null && featureFlags.workflowScripts()) {
            registry.setWorkflowCommandProvider(() ->
                new WorkflowCommandLoader(
                    com.nexusai.application.agent.memory.AutoMemPaths.currentSessionProjectRoot()).load());
            log.info("P1-3: WORKFLOW_SCRIPTS feature 开启 → workflow 命令源已注入（WorkflowCommandLoader，"
                + "对齐 CC getWorkflowCommands namedWorkflowCommands.ts:10-34 + commands.ts:464 ...workflowCommands）");
        } else if (log.isDebugEnabled()) {
            log.debug("P1-3: WORKFLOW_SCRIPTS feature 关闭 → workflow 命令源不注入（对齐 CC commands.ts:457 空）");
        }
        // P1-2: 注入动态技能管理器（条件分离 + getAllCommands 叠加 + onChange→refresh 联动）
        registry.setDynamicSkillsManager(dynamicSkillsManager);
        // P2-20: 激活文件系统源五源加载（managed/user/project-up-to-home/additional/legacy）。
        //   cwdSupplier → getSkillDirCommands(cwd)（对齐 CC commands.ts:361-367）；
        //   additionalDirectoriesSupplier 等价 CC getAdditionalDirectoriesForClaudeMd（--add-dir，state.ts:206-207），
        //   Java 无 CLI 会话态 → env CLAUDE_CODE_ADDITIONAL_DIRECTORIES 供源（concern #1 option A）。
        // ODF-A1: 技能加载 cwd = 会话 projectRoot（CC getSkillDirCommands(cwd) · per-session）
        registry.setCwdSupplier(com.nexusai.application.agent.memory.AutoMemPaths::currentSessionProjectRoot);
        registry.setAdditionalDirectoriesSupplier(com.nexusai.application.agent.skill.ClaudePaths::getAdditionalDirectoriesFromEnv);
        // P2-2: user/project 技能源加载开关接线（CC isSettingSourceEnabled，settings/constants.ts:174-177；
        //   Java Web 无 CLI --settings，concern #2 补开关，yml nexusai.skill.sources.* 默认 true 对齐 CC 全源启用）
        registry.setUserSkillsEnabledSupplier(() -> userSettingsSkillSourceEnabled);
        registry.setProjectSkillsEnabledSupplier(() -> projectSettingsSkillSourceEnabled);
        // P3-5: 接线 clearSkillIndexCache 挂钩（对齐 CC commands.ts:531 clearCommandMemoizationCaches
        //   内 clearSkillIndexCache?.()）· prefetch 未注入 → no-op（concern #30 子系统范围外）
        registry.setSkillIndexClearer(skillDiscoveryPrefetch != null
            ? skillDiscoveryPrefetch::clearSkillIndexCache
            : null);
        // 方案1: 注入 DB enabled 主控源（CommandMapper）· 前端 toggle/update 写 DB enabled →
        //   loadAllCommands 加载时覆盖文件默认 enabled；CommandService.toggleEnabled/update 在
        //   DB 变更后调 registry.refreshCommandsOnly()（方案2）→ 下次 getAllCommands 重载读 DB 生效。
        registry.setCommandMapper(commandMapper);
        log.info("P1-1/P2-9/P2-20/P3-5/MPL6: 注册单一 SkillRegistry @Bean (skillsRoot={}, mcp={}, builtinPlugin={}, "
                + "pluginLoader={}, dynamicSkills={}, mcpSkillsGate={}, 五源激活: cwdSupplier+additionalDirectoriesSupplier={}, "
                + "skillIndexClearer 挂钩={}, commandMapper(DB enabled 主控)={})",
            NexusaiPaths.getProjectDirName() + "/skills",
            mcpServerService != null, builtinPluginRegistry != null, pluginLoader != null, dynamicSkillsManager != null,
            bundledSkillFeatureFlags != null ? bundledSkillFeatureFlags.mcpSkills() : false,
            com.nexusai.application.agent.memory.AutoMemPaths.currentSessionProjectRoot(), skillDiscoveryPrefetch != null,
            commandMapper != null);
        return registry;
    }

    /**
     * s07 P1-1: 注册 Skill tool (对齐 CC tools.ts:208-220).
     *
     * <p>SkillToolImpl 完整实现已存在 (inputSchema/outputSchema/execute/inputValidation).
     * 消费共享 {@link #skillRegistry()} bean (P1-2).
     *
     * <p>[Session H12 v2 Gap1 修复]: 注入 {@link RegisterSkillHooks} (组件扫描 @Component),
     * 让技能执行时注册 frontmatter hooks (对齐 CC processSlashCommand.tsx:877).
     *
     * <p>[P0-1]: 注入 {@link SubagentExecutor} @Bean → context='fork' 技能真实激活隔离子代理
     * 执行 (对齐 CC SkillTool.ts:622-632 executeForkedSkill), 不再恒 null 恒 inline 降级.
     */
    @Bean
    public Tool skillTool(com.nexusai.application.agent.permission.hook.RegisterSkillHooks registerSkillHooks,
                          SubagentExecutor subagentExecutor,
                          PromptShellExecutor promptShellExecutor,
                          com.nexusai.application.agent.permission.hook.ManagedPolicySettingsSupplier managedPolicySettingsSupplier) {
        log.info("s07 P1-1: 注册 SkillTool → ToolRegistry (共享 skillRegistry bean, "
                + "registerSkillHooks={}, subagentExecutor={}, promptShellExecutor={}, "
                + "managedPolicySettingsSupplier={})",
            registerSkillHooks != null, subagentExecutor != null, promptShellExecutor != null,
            managedPolicySettingsSupplier != null);
        SkillToolImpl tool = new SkillToolImpl(skillRegistry());
        tool.setRegisterSkillHooks(registerSkillHooks);
        tool.setSubagentExecutor(subagentExecutor);
        // [ALIGN-HOOKS-1 △-8] plugin-only 权限闸接线 · 对齐 CC processSlashCommand.tsx:874
        //   isRestrictedToPluginOnly('hooks') 读 policySettings.strictPluginOnlyCustomization.
        //   生产注入 ManagedPolicySettingsSupplier::all (policy 缺失返 Map.of() → 闸 false,
        //   等价 CC 无政策不锁). 模式照抄 subagentExecutor.setPluginOnlySettingsSupplier (:505-506).
        //   null-safe: lambda 包裹 (method ref 在 null receiver 上创建即 NPE, 测试直构传 null).
        if (managedPolicySettingsSupplier != null) {
            tool.setPluginOnlySettingsSupplier(managedPolicySettingsSupplier::all);
        }
        // [P0-5] 注入 PromptShellExecutor → skill 内联 shell 注入 (!`cmd` / ```! ```) 激活
        tool.setPromptShellExecutor(promptShellExecutor);
        // [P1-6] 注入 session AgentState resolver → doExecute inline 路径 addInvokedSkill（压缩存活写入侧）
        //   经 sessionId 解析主会话 AgentState（LlmAgentLoop 主会话入口注册）。
        //   null-safe 降级：registry 缺省（@Autowired(required=false)）时不接线，写入侧 debug skip。
        if (sessionAgentStateRegistry != null) {
            tool.setSessionStateResolver(sessionAgentStateRegistry::get);
        }
        return tool;
    }

    /**
     * [P0-5] 注册 PromptShellExecutor · 对齐 CC {@code promptShellExecution.ts:69-143}
     * {@code executeShellCommandsInPrompt} 的 Java 执行器。
     *
     * <p>注入 BashTool + PowerShellTool + PermissionPipeline（三者均 @Component），使 skill 内联
     * shell 注入（!`cmd` / ```! ```）真实激活：权限预检走 PermissionPipeline 10 层检查
     * （allowedTools 并入 COMMAND 桶 → 2b whole-tool allow 放行），执行走 BashTool.execute
     * （含 parseForSecurity + DANGEROUS 黑名单拦截，Windows cmd.exe 既有行为）。
     *
     * <p>SkillToolImpl setter 为 @Autowired(required=false) → 本 bean 缺失时构造器兜底
     * {@code new PromptShellExecutor()}（PermissionPipeline 空 → fail-closed）。
     */
    @Bean
    public PromptShellExecutor promptShellExecutor(
            com.nexusai.application.agent.tool.impl.BashTool bashTool,
            // [决策#65] PowerShellTool 现带注册期门控（@Conditional PowerShellToolRegistrationCondition）·
            //   非 Windows / 未启用时不注册 → 本 @Bean 注入必须 optional（null → PromptShellExecutor 容忍 null，
            //   对齐 CC tools.ts:242 getPowerShellTool() ? [PowerShellTool] : []，未注册即无 PowerShell 工具）。
            @Autowired(required = false)
            com.nexusai.application.agent.tool.impl.PowerShellTool powerShellTool,
            com.nexusai.application.agent.permission.PermissionPipeline permissionPipeline) {
        PromptShellExecutor executor = new PromptShellExecutor(bashTool, powerShellTool, permissionPipeline);
        log.info("P0-5: 注册 PromptShellExecutor → SkillToolImpl 注入 "
                + "(bashTool={}, powerShellTool={}, permissionPipeline={})",
            bashTool != null, powerShellTool != null, permissionPipeline != null);
        return executor;
    }

    /**
     * [P0-1] SubagentExecutor Spring bean · 使 fork mode 真实激活 (concern #29/DEC-4 路径 A).
     *
     * <p>CC 全量对齐要求 fork 技能真实执行 (SkillTool.ts:622-632 context==='fork' →
     * executeForkedSkill, 无 inline 降级). 此前生产 subagentExecutor 恒 null, fork 恒 inline
     * 降级 (探查-skill.md §4.2 X10 MAJOR). 本 bean 接线后 SkillToolImpl fork 分支真实跑隔离
     * sub-agent.
     *
     * <p>依赖对齐 SubagentTool.java:1369-1382 模式:
     * <ul>
     *   <li>subagentToolRegistry: 直接传主 {@link ToolRegistry} @Component (懒代理) —
     *       工具在 ApplicationReadyEvent 才注册 (ToolRegistry.init), bean 构造期不可复制;
     *       执行期 (会话中) 已填充, resolveAgentTools 内部 filterToolsForAgent 已剔除递归工具
     *       (Agent/TaskOutput/ExitPlanMode), Skill 保留 (CC 允许嵌套 skill)</li>
     *   <li>hookRegistry: @Component</li>
     *   <li>parentLoop: null — SubagentExecutor.java:270-274 注释确认 '不再用于子 Agent 主循环'</li>
     *   <li>llmProviderFactory: @Component</li>
     *   <li>providerConfig: null — 非 Spring bean (SkillImprovementHook:452/ChatService:484 均 new),
     *       对齐 SubagentTool 生产路径 (可能 null)</li>
     *   <li>contextFactory: {@link AgentLoopContextFactory} @Component → setContextFactory
     *       (Phase 2 queryLoop 必需, 缺则 fail loud IllegalStateException)</li>
     *   <li>summaryService: {@link com.nexusai.application.agent.subagent.AgentSummaryService}
     *       @Service + coordinatorMode: {@link com.nexusai.application.agent.coordinator.CoordinatorMode}
     *       @Component → setSummaryService/setCoordinatorMode (MS-✗1 消除生产死代码,
     *       CC agentToolUtils.ts:543-553 startAgentSummarization)</li>
     * </ul>
     */
    @Bean
    public SubagentExecutor subagentExecutor(
            @org.springframework.context.annotation.Lazy ToolRegistry toolRegistry,
            com.nexusai.application.agent.permission.hook.HookRegistry hookRegistry,
            com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
            com.nexusai.application.agent.loop.AgentLoopContextFactory contextFactory,
            SkillPreloader skillPreloader,
            com.nexusai.application.agent.permission.hook.ManagedPolicySettingsSupplier managedPolicySettingsSupplier,
            com.nexusai.domain.mcp.McpServerService mcpServerService,
            com.nexusai.application.agent.subagent.AgentSummaryService summaryService,
            com.nexusai.application.agent.coordinator.CoordinatorMode coordinatorMode,
            // [pull origin 循环依赖修复] backgroundTaskRunner 显式 @Lazy 打破循环：
            //   backgroundTaskRunner(TaskConfiguration @Bean, 参数 spawnInProcess) → spawnInProcess
            //   字段 @Autowired subagentExecutor → subagentExecutor(本方法, 参数 backgroundTaskRunner)。
            //   perm 侧 TaskConfiguration 引入 spawnInProcess 参数后此循环成真（memory_v2 基线该参数原无
            //   spawnInProcess），merge 后 5 个 @SpringBootTest 上下文加载失败。@Lazy 让本方法创建时不
            //   立即解析 backgroundTaskRunner（同 L537 toolRegistry 先例），运行时才取真 bean —— 无语义改变。
            @org.springframework.context.annotation.Lazy
            com.nexusai.application.agent.tasks.BackgroundTaskRunner backgroundTaskRunner,
            com.nexusai.application.agent.tasks.SdkEventQueue sdkEventQueue,
            @org.springframework.beans.factory.annotation.Value("${nexusai.agent.progress-summaries-enabled:false}")
            boolean sdkAgentProgressSummariesEnabled,
            // [冲突裁决·并集] HEAD=IMP-G4 analyticsTracker+agentNameRegistry · subagent_v3=IMP-SUB-25 yoloClassifier。
            //   两者互补（hard_metrics 归因 + name→agentId 路由 / handoff 安全分类），CC 两事件族均真实，
            //   故三参全部保留。位置对齐 CC：finalizeAgentTool hard_metrics（agentToolUtils.ts:322-357）
            //   + classifyHandoff gate（runAgent.ts handoff 分类）同 bean 装配。
            com.nexusai.application.agent.api.AnalyticsTracker analyticsTracker,
            com.nexusai.application.agent.subagent.AgentNameRegistry agentNameRegistry,
            com.nexusai.application.agent.permission.classifier.YoloClassifier yoloClassifier,
            // [OPD-CM5-F-21 · DC-V5-10 关闭] AgentMemoryDirectory 单例 · @Bean 方法参数注入
            //   （对齐 memoryPrefetcher 先例）。字段注入会形成 Spring 循环依赖：
            //   commandController→skillRegistry→ToolRegistrationConfig 实例创建→(字段注入)agentMemoryDirectory
            //   →agentMemoryDirectory() @Bean 需本类实例已就绪 → 死循环。方法参数注入在 @Bean 调用期解析，
            //   不依赖本类实例初始化完成，循环打破。生产必非 null；直构测试传 null → 下方
            //   setAgentMemoryDirectory 回落 productionDefault()（无状态 supplier 组合，行为等价）。
            com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory,
            // [prompt-align UP-01] 提示词对齐门控实时读源 · 同文件 :2389 @Bean（batch0），
            //   子代理 drain pending 消息 coordinator 包裹门（对齐 CC attachments.ts:1085-1102）。
            //   Spring 必注入非 null（@Bean）；直构测试传 null → setter 回落 coordinatorMode.isCoordinatorMode()。
            com.nexusai.application.agent.prompt.PromptAlignSettingsResolver promptAlignSettingsResolver) {
        // 懒代理直接作为 subagentToolRegistry — 执行期 all() 返回已填充工具集
        SubagentExecutor executor = new SubagentExecutor(
                toolRegistry, hookRegistry, null,
                llmProviderFactory, null,
                "gpt-4", AgentToolSection.get());
        if (log.isDebugEnabled()) {
            log.debug("subagentExecutor fallbackSystemPrompt ← AgentToolSection.get() (非 fork 变体, "
                    + "CC prompts.ts:319; 仅 agent 自身 prompt 空白时兜底, fork path 由 forkParentSystemPrompt 覆盖)");
        }
        executor.setContextFactory(contextFactory);
        // [IMP-SP2-08] PROMPT_CACHE_BREAK_DETECTION 门注入 → finally cleanupAgentTracking 真实接线
        //   （对齐 CC runAgent.ts:824-826；featureFlags 字段默认 ALL_DISABLED → flag 关时 no-op）
        executor.setFeatureFlags(featureFlags);
        // P1-11: 注入共享 SkillPreloader (@Component → 共享 SkillRegistry bean) —
        //   Step 14 预加载 skills 对齐 CC runAgent.ts:580 getSkillToolCommands 共享源.
        //   无循环依赖: SkillPreloader→SkillRegistry→(SkillsLoader/McpServerService/DynamicSkillsManager).
        executor.setSkillPreloader(skillPreloader);
        // [MCP-I-9 Q-29 R1] plugin-only 权限闸生产接线 · 对齐 CC runAgent.ts:117-127:
        //   isRestrictedToPluginOnly('mcp') 读 policySettings.strictPluginOnlyCustomization.
        //   生产注入 ManagedPolicySettingsSupplier::all (policy 缺失返 Map.of() → 闸 false,
        //   等价 CC 无政策不锁). 模式照抄 HooksSettings.setManagedPolicySettingsSupplier (:94-103).
        // [MCP-I-9 Q-29 R1] null-safe 注入（测试直构传 null；method ref 在 null receiver 上创建
        //   会立即 Objects.requireNonNull NPE，故用 lambda 包一层）。
        if (managedPolicySettingsSupplier != null) {
            executor.setPluginOnlySettingsSupplier(managedPolicySettingsSupplier::all);
        }
        // [MCP-I-9 Q-32] 注入 MCP server 按名解析器（DB 唯一运行时源 Q-09=C）·
        //   对齐 CC runAgent.ts:140-151 getMcpConfigByName + McpServerService.getServerConfigByName。
        //   config Map → McpServerSpec（stdio→command+args；远程→command 列存 url）。
        if (mcpServerService != null) {
            executor.setMcpServerNameResolver(name -> mcpServerService.getServerConfigByName(name)
                .map(cfg -> com.nexusai.application.agent.subagent.AgentMcpServers.fromConfig(name, cfg)));
        }
        // IMP-M-P2-2 + OPD-CM5-F-21（DC-V5-10 关闭）: 注入 AgentMemoryDirectory → buildAgentSystemPrompt
        //   生成期 agent-memory 注入（OPD-M-38，对齐 CC runAgent.ts:508-518 消费 agentDefinition.getSystemPrompt
        //   含记忆）。统一 @Bean 同实例（方法参数注入，与 memoryPrefetcher 参数注入同源单例；生产非 null）
        //   ——不再 {@code productionDefault()} 新建实例（CM-F4 ⊕-4：@Bean 与生产注入实例分离的接线不一致）。
        //   直构测试参数为 null → 回落 productionDefault()（无状态，行为等价）。
        executor.setAgentMemoryDirectory(agentMemoryDirectory != null
            ? agentMemoryDirectory
            : com.nexusai.application.agent.agent.AgentMemoryDirectory.productionDefault());
        // [R3-SUMMARY MS-✗1] 装配路径注入 summaryService + coordinatorMode · 对齐 CC
        //   agentToolUtils.ts:543-553 onCacheSafeParams → startAgentSummarization 生产启动。
        //   此前手动 new 后未调 setSummaryService/setCoordinatorMode → 两字段恒 null →
        //   maybeStartSummary 恒返回 null → AgentSummaryService.start() 永不触发（生产死代码）。
        //   summaryService=@Service, coordinatorMode=@Component, Spring 必注入非 null。
        executor.setSummaryService(summaryService);
        executor.setCoordinatorMode(coordinatorMode);
        // [prompt-align UP-01] 注入提示词对齐门控读源 → 子代理 drain pending 消息 coordinator 包裹门
        //   （读 settings.coordinator_mode_enabled，CC attachments.ts:1085-1102 origin={kind:'coordinator'}）
        executor.setPromptAlignSettingsResolver(promptAlignSettingsResolver);
        // [SP-14] PromptAlignSettingsResolver 静态槽位接线：SystemPromptAssembler 全构造点读 DB
        //   settings.system_prompt_boundary_enabled 覆盖 firstParty boundary 判定（同 BoundaryReader
        //   setSettingsResolver 先例；null → 回落 GlobalCacheScope firstParty 判定，零行为变化）。
        //   systemPromptBoundaryEnabled 静态 helper（PromptAlignSettingsResolver.staticSystemPromptBoundaryEnabled）
        //   经本接线与 resolver.systemPromptBoundaryEnabled() 同一数据源（DB 单行，无分叉）。
        //   本 @Bean 方法参数注入（同 :628-634 循环打破先例），生产必非 null。
        com.nexusai.application.agent.prompt.PromptAlignSettingsResolver.setStaticResolver(promptAlignSettingsResolver);
        // [R31-03 返工] SDK agentProgressSummaries 门同样注入（CC 三 flag 门之一，默认 false）
        executor.setSdkAgentProgressSummariesEnabled(sdkAgentProgressSummariesEnabled);
        // [D-3] fork 路径注入 SDK 事件队列 · 对称 SubagentTool.applySummaryWiring setSdkEventQueue。
        //   对齐 CC utils/sdkEventQueue.ts（进程级单例）—— fork 周期摘要回调经
        //   AgentProgressTracker.applySummary → emitTaskProgress 发射 task_progress SDK 事件
        //   （CC utils/task/sdkProgress.ts:10-36）。此前 subagentExecutor @Bean 未接线 → fork 路径
        //   sdkEventQueue==null → applySummary 只记录摘要不发射 SDK（前端无 task_progress）。
        executor.setSdkEventQueue(sdkEventQueue);
        // [RF-2 返工] fork 路径（skill fork 分支）同样注入 BackgroundTaskRunner → Step 19.7 前台登记
        //   （registerAgentForeground 等价）生产可达；此前 subagentExecutor @Bean 未接线（RF-2 反思 P0-②）。
        executor.setBackgroundTaskRunner(backgroundTaskRunner);
        // [冲突裁决·并集] HEAD=IMP-G4 hard_metrics + agentNameRegistry（tengu_agent_tool_* + name→agentId），
        //   subagent_v3=IMP-SUB-25 setYoloClassifier（handoff 安全分类，消灭生产惰性）。两者独立互补，
        //   均保留；顺序无依赖。CC 依据：finalizeAgentTool hard_metrics（agentToolUtils.ts:322-357）+
        //   classifyHandoff 分类器装配（runAgent.ts handoff 分类路径）。
        // [IMP-G4 组11-1] fork 路径（skill fork 分支）Subagent hard_metrics + 按名路由注册表注入 ·
        //   对齐 CC logEvent tengu_agent_*（AgentTool.tsx/agentToolUtils.ts）+ appState.agentNameRegistry。
        //   AnalyticsTracker/@Component + AgentNameRegistry/@Component 均为 Spring bean，直接注入。
        executor.setAnalyticsTracker(analyticsTracker);
        executor.setAgentNameRegistry(agentNameRegistry);
        // [IMP-SUB-25 返工 R2 接线归零] handoff 安全分类器注入 → 消灭 L1 惰性（yoloClassifier==null
        //   → 门 4 恒跳过 → handoff 分类永不运行）。对齐既有 11 setter 接线模式；YoloClassifierImpl
        //   @Component，Spring 必注入非 null。此前本 @Bean 未接线 setYoloClassifier = 项目历史
        //   R3-SUMMARY MS-✗1 / RF-2 P0-② 同型失败模式（生产死代码），返工一次性归零。
        executor.setYoloClassifier(yoloClassifier);
        if (log.isDebugEnabled()) {
            log.debug("[R3-SUMMARY] subagentExecutor @Bean 装配周期摘要: summaryService={} coordinatorMode={} "
                + "sdkAgentProgressSummariesEnabled={} sdkEventQueue={} backgroundTaskRunner={}",
                summaryService != null, coordinatorMode != null, sdkAgentProgressSummariesEnabled,
                sdkEventQueue != null, backgroundTaskRunner != null);
        }
        log.info("P0-1: 注册 SubagentExecutor @Bean → fork mode 激活 "
                + "(contextFactory={}, fallbackModel=gpt-4, subagentTools=懒代理主注册表, skillPreloader={}, "
                + "mcpServerNameResolver={}, summaryService={}, coordinatorMode={}, sdkEventQueue={})",
            contextFactory != null, skillPreloader != null, mcpServerService != null,
            summaryService != null, coordinatorMode != null, sdkEventQueue != null);
        return executor;
    }

    /**
     * LSP-P1: 注册 LSPTool 到 ToolRegistry · 对齐 CC LSPTool.ts:127-860 + tools.ts:224.
     *
     * <p><b>[RV-D-03 NG-1] ENABLE_LSP_TOOL 注册门控（两层门控）</b>：
     * CC tools.ts:224 {@code ...(isEnvTruthy(process.env.ENABLE_LSP_TOOL) ? [LSPTool] : [])}
     * —— LSPTool 仅在 ENABLE_LSP_TOOL truthy 时才进 getAllBaseTools 数组（注册层门控）；
     * 第二层是 LSPTool.ts:137-139 {@code isEnabled() = isLspConnected()}（运行时门控）。
     * Java 端 {@link EnableLspToolCondition} 实现注册层门控（isEnvTruthy 语义，CC
     * envUtils.ts:32-37 四值 {1,true,yes,on}）：ENABLE_LSP_TOOL 未设（默认）→ 本 @Bean
     * 不创建 → 不进 {@code @Autowired List<Tool>} → 不进 registry/schema（与 CC env 未设 →
     * LSPTool 不进 getAllBaseTools 等价）；ENABLE_LSP_TOOL truthy → 注册，再叠加既有
     * {@link LspTool#isEnabled()} 运行时门控（两层门控与 CC 逐字对齐，见 tools.ts:175-182
     * getEnabledTools 二次 filter isEnabled）。
     *
     * <p>isEnabled() 委托 LspManager.isLspConnected() — 无 server 时自动 disable, LLM 不会调用.
     * execute() 委托 LspManager.sendRequest (内部 ensureServerStarted 惰性启动真实 ProcessLspClient 子进程).
     */
    @Bean
    @Conditional(EnableLspToolCondition.class)
    public Tool lspTool() {
        log.info("LSP-P1: 注册 LSPTool → ToolRegistry (ENABLE_LSP_TOOL 门控通过; isEnabled=LspManager.isLspConnected)");
        return new LspTool(lspManager);
    }

    /**
     * [VisionAnalyze] 注册 VisionAnalyzeTool → ToolRegistry · 代理视觉模型（CC 无对应，自建工具）。
     *
     * <p><b>WHY</b>: 替代 MultimodalAttachmentTool（僵尸工具：读缓存图注入主模型 image block，
     * 逻辑矛盾——只在主模型不支持视觉时触发，但注入 image block 又需要主模型看得懂）。
     * 本工具把图片+prompt 发给独立视觉模型（settings.multimodalModelName）→ 返回纯文本，
     * 主模型收到占位符 {@code [image:contentId]}，绝不给 base64。旧名 multimodal_attachment
     * 经 {@link VisionAnalyzeTool#aliases()} 保留历史 transcript 派发。
     *
     * <p><b>注册通道</b>: 本工具<b>非</b> {@code @Component}，经本 {@code @Bean Tool} 注册
     * （同 SkillTool/LSPTool 模式），由 ToolRegistry 构造器 {@code @Autowired List<Tool>} 收集
     * （对齐 CC tools.ts:212 SkillTool / :224 LSPTool 独立数组元素，非 List 嵌套展开）。
     * {@link ImageAttachmentStore}（@Component）构造注入，读缓存惰性（仅模型 tool_use 时执行）；
     * {@link com.nexusai.infra.llm.ModelConfigResolver} 提供多模态档位模型名 + ProviderConfig
     * 单一来源解析；{@link com.nexusai.infra.llm.LlmProviderFactory} 按 providerType 路由。
     *
     * @param imageAttachmentStore 图片附件缓存存储（A0 实现 · 对齐 CC utils/imageStore.ts）
     * @param modelConfigResolver  多模态档位模型名 + ProviderConfig 解析（settings.multimodalModelName）
     * @param llmProviderFactory   provider 工厂（resolve 结果按 providerType 路由）
     * @param attachmentService    附件表统一 contentId → path 解析（&gt;5MB 大图注册中心 · 附件双模式）
     * @return 视觉分析工具实例
     */
    @Bean
    public Tool visionAnalyzeTool(ImageAttachmentStore imageAttachmentStore,
                                  com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver,
                                  com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
                                  com.nexusai.domain.session.AttachmentService attachmentService) {
        log.info("[VisionAnalyze] 注册 VisionAnalyzeTool → ToolRegistry（代理视觉模型：analyze 读缓存图/附件表 → image block+prompt；suggest 纯 prompt；返回纯文本+占位符；旧名 aliases 保留历史；CC 无对应，自建工具）");
        return new VisionAnalyzeTool(imageAttachmentStore, modelConfigResolver, llmProviderFactory, attachmentService);
    }

    /**
     * s07 P1-2: 注册 SkillCatalog bean, LlmAgentLoop 注入后 system prompt 自动 append 技能目录.
     * <p>P1-2 修复: 消费共享 {@link #skillRegistry()} bean (与 skillTool 同一实例),
     * 保证 SkillTool 可调用的技能与 SkillCatalog 注入 LLM 的技能列表一致 (此前是两个独立实例).
     */
    @Bean
    public com.nexusai.application.agent.skill.SkillCatalog skillCatalog() {
        log.info("s07 P1-2: 注册 SkillCatalog → LlmAgentLoop 注入 (共享 skillRegistry bean)");
        return new com.nexusai.application.agent.skill.SkillCatalog(skillRegistry());
    }

    /**
     * FIX-R11-3: 注册 TokenCounter bean (functional interface), 让 autoCompactor
     * 可注入. 委托 {@link TokenEstimator#tokenCountWithEstimation} — 对齐 CC
     * tokenCountWithEstimation（utils/tokens.ts usage-walk + sibling 回溯 + rough 尾段）.
     * AutoCompactor.shouldAutoCompact 经 tokenCounter.count(messages) 自动获得真实估算.
     */
    @Bean
    public TokenCounter tokenCounter(TokenEstimator tokenEstimator) {
        log.info("L4-A: 注册 TokenCounter bean → 委托 TokenEstimator.tokenCountWithEstimation（AutoCompactor 阈值走真实估算）");
        return messages -> tokenEstimator.tokenCountWithEstimation(messages);
    }

    /**
     * FIX-R2-2 + IMP-01 + GR-1: 注册 AutoCompactor (auto 自动压缩入口, s08-P1-2).
     * <p><b>[GR-1 返工]</b> 主自动压缩路径经 LlmAgentLoop 自动装配本 bean
     * （@Autowired autoCompactor）直接调用
     * {@link AutoCompactor#autoCompactIfNeeded(List, int, String, CompactConversationContext)}
     * → CC 对齐单函数 {@link CompactConversation#compactConversation}（autoCompact.ts:313），
     * 与 /compact manual 共用同一单函数，无手工 [boundary,summary] 组装双轨。
     * <p>本 bean 同时是 blocking 预检的阈值体系唯一生产载体（GR-3 删除旧编排器后，
     * AgentLoopContext.computeBlockingLimit 经 autoCompactor.getThresholdSystem() 取同源窗口）。
     * <p>AutoCompactor 未注入时降级 no-op, L4 LLM 摘要压缩路径不可达.
     * <p>IMP-01 删除 no-op 回调 lambda（D-10），改用真实 {@link StreamCompactSummary}
     * （CC streamCompactSummary 全量语义：fork 缓存共享 + 流式 fallback +
     * maxOutputTokensOverride + keepalive）。L4 摘要生产可达（INV-13）。
     */
    @Bean
    public AutoCompactor autoCompactor(
            TokenCounter tokenCounter,
            StreamCompactSummary streamCompactSummary,
            @Autowired(required = false) com.nexusai.application.agent.compact.CompactThresholdSystem compactThresholdSystem,
            @Autowired(required = false) com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService,
            com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        AutoCompactor autoCompactor = new AutoCompactor(tokenCounter, streamCompactSummary);
        if (compactThresholdSystem != null) {
            autoCompactor.setThresholdSystem(compactThresholdSystem);
        }
        // [V52 B1-6] DB settings 压缩开关实时读源注入（isAutoCompactEnabled / autoCompactIfNeeded 内
        //   disableCompact/disableAutoCompact/autoCompactEnabled 覆盖，null 回落原逻辑）
        autoCompactor.setSettingsResolver(settingsResolver);
        // [V54 token-compact-fix B1-2] compactConversation 静态槽位接线（PTL 重试上限
        //   settings.max_ptl_retries 实时读，null 回落常量；auto/reactive/manual 全路径共用
        //   同一全局槽位 → settings 单例注入一次覆盖所有调用方）
        com.nexusai.application.agent.compact.CompactConversation.setSettingsResolver(settingsResolver);
        // IMP-M-P0-3: SM 优先路径生产注入（autoCompact.ts:287-310 trySessionMemoryCompaction）
        if (sessionMemoryService != null) {
            autoCompactor.setSessionMemoryService(sessionMemoryService);
        }
        // [SM-07] notifyCompaction 门控接线（DRIFT-9）· CC autoCompact.ts:302-304
        //   feature('PROMPT_CACHE_BREAK_DETECTION') —— 从 FeatureFlags 单源接线
        autoCompactor.setPromptCacheBreakDetectionGate(
            () -> featureFlags != null && featureFlags.promptCacheBreakDetection());
        // [IMP-CM-12] SM 成功链 notifyCompaction 生产接线（OPD-CM3-04/A02 · 全局报告 §5 #2 △-2）
        //   CC autoCompact.ts:302-303 `notifyCompaction(querySource ?? 'compact', agentId)`
        //   feature 门控 —— 此前只 set gate 未 set notify → SM 成功链 :611-612
        //   notifyCompaction.accept 生产恒 no-op（默认 no-op :183）。与 /compact 分支
        //   notifyCompactionRunnable 同模式：gatedBy(featureFlags)（feature 关 → enabled=false →
        //   内部 no-op；feature 开 → 真实重置 cache-read 基线 promptCacheBreakDetection.ts:689-698）。
        //   门控在调用时求值；AutoCompactor 内 SM 成功链已先行按 promptCacheBreakDetectionGate
        //   门控（双门控幂等一致，均读同一 FeatureFlags.promptCacheBreakDetection()）。
        autoCompactor.setNotifyCompaction((querySource, agentId) ->
            com.nexusai.application.agent.lsp.PromptCacheBreakDetection.gatedBy(featureFlags)
                .notifyCompaction(querySource, agentId));
        // [IMP2-03] async-agent/plan 附件数据源注入（auto 路径附件生产接线，CC compact.ts:545-560）
        autoCompactor.setTaskFrameworkService(taskFrameworkService);
        autoCompactor.setPlanProvider(planProvider);
        // [IMP2-24 T-5] 四 feature 门接线（§7-13/29 裁决：默认建议接线，T-5 SUPERSEDED）·
        // 对齐 CC autoCompact.ts shouldAutoCompact 抑制门（autoCompact.ts:195-223）：
        //   reactiveCompactEnabled ← feature('REACTIVE_COMPACT')（:195）
        //   contextCollapseEnabled ← feature('CONTEXT_COLLAPSE')（:179 marble_origami 守卫 + :215 抑制门）
        //   reactiveOnlyMode ← getFeatureValue_CACHED_MAY_BE_STALE('tengu_cobalt_raccoon', false)（:196）
        //     —— Java 无 GrowthBook 接入，恒 false（对齐 CC GB 缺省），显式注入便于后续接线
        //   contextCollapseModeEnabled ← isContextCollapseEnabled()（:220）
        //     —— Java 端 = FeatureFlags.contextCollapse()（ContextCollapse.java:27 注记，无 env override）
        autoCompactor.setReactiveCompactEnabled(featureFlags != null && featureFlags.reactiveCompact());
        autoCompactor.setContextCollapseEnabled(featureFlags != null && featureFlags.contextCollapse());
        autoCompactor.setReactiveOnlyMode(false);
        autoCompactor.setContextCollapseModeEnabled(featureFlags != null && featureFlags.contextCollapse());
        // [RV-E-01 GAP-01 auto] 显式接线会话 AgentState 注册表 → CompactConversation 静态 holder 在
        //   auto 路径确定性注入（不再依赖 AutoCompactor @Autowired 字段隐式注入，两法并存无冲突）：
        //   使 invoked_skills 重注入（compactConversation step 10 populateInvokedSkillsAttachment）
        //   经 sessionId 解析主 AgentState 生产可达（对齐 CC 全局 STATE 读侧语义）。
        if (this.sessionAgentStateRegistry != null) {
            autoCompactor.setSessionAgentStateRegistry(this.sessionAgentStateRegistry);
        }
        log.info("注册 AutoCompactor → LlmAgentLoop 注入 (GR-1 auto 路径直调 compactConversation, "
            + "阈值体系 thresholdSystem={}, SM={}, CONTEXT_COLLAPSE门={}, REACTIVE_COMPACT门={}, sessionRegistry={})",
            compactThresholdSystem != null ? "注入" : "默认",
            sessionMemoryService != null ? "注入" : "未注入",
            featureFlags != null && featureFlags.contextCollapse(),
            featureFlags != null && featureFlags.reactiveCompact(),
            this.sessionAgentStateRegistry != null ? "已注入" : "未注入");
        return autoCompactor;
    }

    /**
     * [S3-B1] 注册 MicroCompactor（microcompact 链式入口）· 对齐 CC microCompact.ts
     * getMicroCompactModule BeanProvider（query.ts:414 主循环恒调用 deps.microcompact）。
     *
     * <p><b>WHY</b>: LlmAgentLoop 的 {@code @Autowired(required=false) MicroCompactor} 生产注入
     * 需要本 @Bean（B1 接线后主循环真实执行 microcompactMessages）。
     *
     * <p><b>[IMP2-04] time-based MC 配置注入（GrowthBook tengu_slate_heron 等价）</b>: CC 经
     * {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_slate_heron', DEFAULTS)}
     * （timeBasedMCConfig.ts:36-43）实时读取配置；Java 无 GB 接入，以
     * {@code nexusai.feature.time-based-mc.*} 属性为等价载体，默认
     * {enabled:false, gapThresholdMinutes:60, keepRecent:5} 对齐 CC TIME_BASED_MC_CONFIG_DEFAULTS
     * （timeBasedMCConfig.ts:30-34）。每次评估经 Supplier 实时读取（对齐 CC "hoist the GB read"）。
     * 开启后主循环 time-based 触发真实清除（microCompact.ts:267-270 短路）。
     */
    @Bean
    public MicroCompactor microCompactor(
            @Value("${nexusai.feature.time-based-mc.enabled:false}") boolean timeBasedMcEnabled,
            @Value("${nexusai.feature.time-based-mc.gap-threshold-minutes:60}") int timeBasedMcGapThresholdMinutes,
            @Value("${nexusai.feature.time-based-mc.keep-recent:5}") int timeBasedMcKeepRecent,
            com.nexusai.application.agent.telemetry.Telemetry telemetry,
            com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        // CC logEvent 遥测注入（microCompact.ts:341-356/:498-505；null 安全，Spring @Component 自动注入）
        MicroCompactor.setTelemetry(telemetry);
        // [V52 X1-3] BoundaryReader 读侧 snip 投影 DB 覆盖静态槽位接线（同 MicroCompactor 静态槽位
        //   同点；DB settings.history_snip_enabled 有值覆盖 FeatureFlags，null 回落）
        com.nexusai.application.agent.compact.BoundaryReader.setSettingsResolver(settingsResolver);
        // [token-compact-fix ①] cached-MC 开关实时化：注入 DB 实时读源静态槽位（同 BoundaryReader
        //   接线点）。注入后 MicroCompactor.isCachedMicrocompactFeatureEnabled() 每次调用实时读
        //   settings.cached_microcompact_enabled（前端 PUT settings 后下一轮生效，不再需重启）；
        //   下方 setCachedMicrocompactFeatureEnabled 仍保留作 resolver 为 null 时的静态回落初值。
        MicroCompactor.setSettingsResolver(settingsResolver);
        // [V52 B1-6/R5] cached-MC feature 门：DB settings.cached_microcompact_enabled 有值用之，
        //   null 回落 false（对齐 CC 外部构建 DCE 恒关；默认关）。
        Boolean dbCachedMc = settingsResolver != null ? settingsResolver.cachedMicrocompactEnabled() : null;
        MicroCompactor.setCachedMicrocompactFeatureEnabled(dbCachedMc != null ? dbCachedMc : false);
        log.info("注册 MicroCompactor @Bean → LlmAgentLoop 主循环 microcompact 接线 (S3-B1 · CC query.ts:414; "
                + "tengu_slate_heron 等价配置 enabled={}, gapThreshold={}min, keepRecent={}, telemetry={}, "
                + "cached-MC(DB)={})",
            timeBasedMcEnabled, timeBasedMcGapThresholdMinutes, timeBasedMcKeepRecent,
            telemetry != null ? "注入" : "未注入",
            dbCachedMc != null ? dbCachedMc : "null→false(R5)");
        // [V52 B1-6] time-based MC 配置叠加 DB settings：DB 有值覆盖 nexusai.feature.time-based-mc.* 属性，
        //   null 回落属性值。Supplier 每次评估实时读取（对齐 CC "hoist the GB read"）。
        return new MicroCompactor(() -> {
            Boolean dbEnabled = settingsResolver != null ? settingsResolver.timeBasedMcEnabled() : null;
            Integer dbGap = settingsResolver != null ? settingsResolver.gapThresholdMinutes() : null;
            Integer dbKeep = settingsResolver != null ? settingsResolver.keepRecent() : null;
            return new MicroCompactor.TimeBasedMCConfig(
                dbEnabled != null ? dbEnabled : timeBasedMcEnabled,
                dbGap != null ? dbGap : timeBasedMcGapThresholdMinutes,
                dbKeep != null ? dbKeep : timeBasedMcKeepRecent);
        });
    }

    /**
     * IMP-01: 注册 StreamCompactSummary（L4 摘要生产）· 对齐 CC compact.ts:1136-1396
     * streamCompactSummary。
     *
     * <p>供应商解析：
     * <ul>
     *   <li><b>provider</b> —— LlmProviderFactory 按 config 分发（config 不可用 → mock）</li>
     *   <li><b>model</b> —— 读 settings 持久层 {@code model}（对齐 LlmAgentLoop.getModelForCall
     *       settings 层）；无配置 → null（factory 回落）</li>
     *   <li><b>config</b> —— 按 model 经 ModelMapper/ProviderMapper/ProviderService 解析
     *       （对齐 ChatService.buildConfigForModel）；无 → ProviderConfig.empty()（mock）</li>
     * </ul>
     *
     * <p>[RES-②] fork 缓存共享已接线：cacheSafeParamsSupplier = {@code () -> CacheSafeParamsHolder.get()}
     * （LlmAgentLoop autoCompact 触发点经 CacheSharingParamsBuilder 构建 + Holder 保存，见
     * compact/fork/CacheSharingParamsBuilder.java）；promptCacheSharingEnabled=true。
     * 流式重试默认关闭（CC tengu_compact_streaming_retry 默认 false）。
     */
    @Bean
    public StreamCompactSummary streamCompactSummary(
            com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ModelMapper modelMapper,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ProviderMapper providerMapper,
            @Autowired(required = false) com.nexusai.domain.provider.ProviderService providerService,
            @Autowired(required = false) com.nexusai.application.agent.settings.storage.FileConfigStorage configStorage,
            com.nexusai.application.agent.compact.fork.ProductionForkedQuery productionForkedQuery,
            com.nexusai.application.agent.telemetry.Telemetry telemetry,
            com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {

        ForkSuppliers suppliers = buildForkSuppliers(
            llmProviderFactory, modelMapper, providerMapper, providerService, configStorage);
        // [RES-②] fork 缓存共享接线：cacheSafeParamsSupplier 读 ThreadLocal 槽位
        // （LlmAgentLoop autoCompact 触发点经 CacheSharingParamsBuilder 构建 + Holder 保存；
        //  get()=null 时 StreamCompactSummary 内部跳过 fork 路径 → 流式 fallback，不破坏 3 参语义）。
        java.util.function.Supplier<CacheSafeParams> cacheSafeParamsSupplier =
            () -> CacheSafeParamsHolder.get();
        // [IMP2-23 ⊕-7] fork 双实现收敛：注入 ProductionForkedQuery（生产 fork loop 单一实现，
        //   extract/auto-dream 同 seam）→ tryForkCacheSharing 委托 RunForkedAgent（maxTurns=1 +
        //   canUseTool=deny + skipCacheWrite=true 由参数表达，CC compact.ts:1188-1200）。
        StreamCompactSummary summary = new StreamCompactSummary(
            suppliers.providerSupplier(), suppliers.modelSupplier(), suppliers.configSupplier(),
            cacheSafeParamsSupplier,
            null,                 // abortControllerSupplier（NOOP 兜底）
            null,                 // sessionActivitySignalSupplier
            null,                 // keepaliveExecutor（不启动 keepalive）
            false,                // sessionActivityTrackingActive
            true,                 // promptCacheSharingEnabled（CC tengu_compact_cache_prefix 默认 true）
            false,                // retryEnabled（CC tengu_compact_streaming_retry 默认 false）
            null,                 // sdkStatusSetter
            null,                 // streamModeSetter
            null);                // responseLengthSetter
        summary.setForkedQuery(productionForkedQuery);
        // [IMP-A3-2 SCS-15/17 装配缺口修复 · MG-1] CC logEvent 遥测注入（compact.ts:1214/1235/1242/1364/1379
        //   tengu_compact_cache_sharing_success/fallback/streaming_retry/failed；null 安全，Spring @Component
        //   自动注入）。对齐 MicroCompactor.setTelemetry 先例（:833）——此前未调用 → 静态槽位恒 null →
        //   4 类遥测事件生产恒 no-op（emitCompactEvent 静默跳过）。
        StreamCompactSummary.setTelemetry(telemetry);
        // [V54 token-compact-fix B1-2] 流式重试上限静态槽位接线（settings.max_compact_streaming_retries
        //   实时读，null 回落常量 2；retryEnabled 门控仍在，DB 值仅修正重试次数）
        StreamCompactSummary.setSettingsResolver(settingsResolver);
        log.info("注册 StreamCompactSummary → AutoCompactor 注入 (L4 摘要生产可达, fork 缓存共享"
                + "已接线 ⊕-7 收敛: tryForkCacheSharing 委托 RunForkedAgent/ProductionForkedQuery, "
                + "telemetry={} MG-1 SCS-15/17)",
            telemetry != null ? "注入" : "未注入");
        return summary;
    }

    /**
     * RES-R5-1 + RES-C10: 注册 CountTokensClient · 按 provider 类型分发：
     * <ul>
     *   <li><b>anthropic</b> → {@link AnthropicCountTokensClient}（真实 count_tokens API，
     *       tokenEstimation.ts:140-201）；</li>
     *   <li><b>其余（openai_compatible / openai_sdk / 未知）</b> → {@link OpenAICountTokensClient}
     *       （tiktoken 本地估算，对齐 CC roughTokenCountEstimation tokenEstimation.ts:203-208 +
     *       countTokensWithFallback analyzeContext.ts:77-109；OpenAI 无 count_tokens 端点）。</li>
     * </ul>
     *
     * <p>provider 类型惰性解析（providerTypeSupplier 经 LlmProvider.type() 运行时求值），
     * 与 buildForkSuppliers 单一来源（model/config/provider 同源，无第二份解析逻辑）。
     */
    @Bean
    public com.nexusai.infra.llm.CountTokensClient countTokensClient(
            com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ModelMapper modelMapper,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ProviderMapper providerMapper,
            @Autowired(required = false) com.nexusai.domain.provider.ProviderService providerService,
            @Autowired(required = false) com.nexusai.application.agent.settings.storage.FileConfigStorage configStorage) {
        ForkSuppliers suppliers = buildForkSuppliers(
            llmProviderFactory, modelMapper, providerMapper, providerService, configStorage);
        // RES-C10: 按 provider 类型分发（运行时惰性求值，不双轨不伪造）
        String providerType = suppliers.providerTypeSupplier().get();
        log.info("RES-C10: 注册 CountTokensClient → providerType={} · 分发：anthropic → 真实 API，其余 → tiktoken 本地估算",
            providerType);
        if ("anthropic".equalsIgnoreCase(providerType)) {
            return new com.nexusai.infra.llm.AnthropicCountTokensClient(
                suppliers.configSupplier(), suppliers.modelSupplier());
        }
        // OpenAI / OpenAI-compatible / 未知 → tiktoken 本地估算（RES-C10，CC roughTokenCountEstimation 增强版）
        return new com.nexusai.infra.llm.OpenAICountTokensClient(suppliers.modelSupplier());
    }

    /**
     * IMP-M-P1-2 / ODF-A1: 注册 AutoMemPaths（per-project auto-memory 路径解析 · 对齐 CC
     * paths.ts getAutoMemPath）。memoryStorage / memoryScanner / MemoryPrefetcher / ReadFileTool
     * 共用同一实例。
     *
     * <p>ODF-A1 per-session：bean 单例，但 projectRoot 经
     * {@link AutoMemPaths#defaultInstance()} 的 supplier 惰性读取
     * {@link AutoMemPaths#currentSessionProjectRoot()}（LlmAgentLoop.run() 入口按会话注入）——
     * 同一 JVM 不同 cwd 会话解析出各自独立 memory 目录（对齐 CC per-project per-cwd）。
     */
    @Bean
    public com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths() {
        log.info("IMP-M-P1-2/ODF-A1: 注册 AutoMemPaths → memory 组件注入 (per-project per-session, DEL-M-06)");
        return com.nexusai.application.agent.memory.AutoMemPaths.defaultInstance();
    }

    /**
     * ODF-A1: 会话 projectRoot 解析器 bean（sessionId → 会话绑定项目本地路径）。
     *
     * <p>入参为会话 DB 主键字符串（{@code "sess-..."}）；解析链
     * {@code SessionRecord.mainProjectId → ProjectRecord.path} —— 这是 Web 后端唯一的
     * per-session 项目目录概念（CC per-project per-cwd 的 projectRoot 等价物）。
     * 会话未绑定项目 / 项目无 path → null（LlmAgentLoop 回落默认 workspaceDir）。
     * 由 LlmAgentLoop（prototype）{@code @Autowired(required=false)} 注入，run() 入口冻结。
     */
    @Bean
    public java.util.function.Function<String, String> sessionProjectRootResolver(
            com.nexusai.repository.session.mapper.SessionMapper sessionMapper,
            com.nexusai.repository.project.mapper.ProjectMapper projectMapper) {
        return sessionId -> {
            if (sessionId == null || sessionId.isBlank()) {
                return null;
            }
            com.nexusai.repository.session.entity.SessionRecord session;
            try {
                session = sessionMapper.selectOneById(sessionId);
            } catch (Exception e) {
                log.warn("[ToolRegistrationConfig] 解析会话 projectRoot 查询失败: {} - {}", sessionId, e.getMessage());
                return null;
            }
            if (session == null || session.getMainProjectId() == null) {
                return null;
            }
            com.nexusai.repository.project.entity.ProjectRecord project = projectMapper.selectOneById(session.getMainProjectId());
            if (project == null || project.getPath() == null || project.getPath().isBlank()) {
                return null;
            }
            return project.getPath();
        };
    }

    /**
     * IMP-M-P2-2 + [IMP-C-4 · OPD-CM5-C-08]: 注册 AgentMemoryDirectory（agent-memory 目录解析 +
     * 权限 carve-out 判定 + 生成期 prompt 注入）· 对齐 CC tools/AgentTool/agentMemory.ts。
     * SubagentExecutor（buildAgentSystemPrompt 注入）与 Write/Read 权限层共用同一实例。
     *
     * <p>[IMP-C-4 · OPD-CM5-C-08] 子代理 agent-memory 路径计数事件透传 telemetry：CC 端
     * agentMemory.ts:169-176 → buildMemoryPrompt:298 门控通过时<b>无条件</b>
     * logEvent('tengu_memdir_loaded')（CC logEvent 无 telemetry 前置条件）。Java 端
     * AgentMemoryDirectory 共享单例（DefaultHolder.INSTANCE，构造期无实例 telemetry）→
     * {@code MemoryPromptBuilder#emitMemdirLoaded} 回落生产静态兜底
     * （{@code MemoryPromptBuilder#setProductionTelemetry}，[IMP-C-4 · OPD-CM5-C-08] 设计）。
     * 本 @Bean 在 Spring 上下文启动时注入静态兜底 —— 共享单例与 SubagentTool/executor 各自装配实例
     * 共用同一发射通道，无需逐一构造器透传。
     *
     * @param telemetry 生产遥测（@Autowired(required=false) → 直构测试未注入 null，清除回落通道零行为变化）
     */
    @Bean
    public com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        // [IMP-C-4 · OPD-CM5-C-08] 生产静态遥测兜底注入 —— agent-memory 单例装配/子代理路径
        //   tengu_memdir_loaded 发射通道（对齐 CC 无条件 logEvent）；null → 清除回落通道
        com.nexusai.application.agent.memory.MemoryPromptBuilder.setProductionTelemetry(telemetry);
        // [IMP-C-6 · OPD-CM5-C-10] 生产静态 coralFern 兜底注入 —— AgentMemoryDirectory 共享单例
        //   agent-memory 变体 searching-past 段门控（CC memdir.ts:376 getFeatureValue_CACHED_MAY_BE_STALE
        //   ('tengu_coral_fern', false)）；featureFlags 缺省 ALL_DISABLED → flag 关时 holder 返回 false
        //   （对齐 CC GB 缺省），nexusai.feature.coral-fern:true → 段生产可达（C-6 单例缺口关闭）
        com.nexusai.application.agent.memory.MemoryPromptBuilder.setProductionCoralFern(
            () -> featureFlags != null && featureFlags.coralFern());
        log.info("IMP-M-P2-2/IMP-C-4/IMP-C-6: 注册 AgentMemoryDirectory → agent-memory 注入/权限层接线"
            + "（子代理路径 telemetry 兜底 {}，coralFern 兜底 {}）",
            telemetry != null ? "已接线" : "未接线(null)",
            featureFlags != null && featureFlags.coralFern() ? "开启" : "关闭(默认)");
        return com.nexusai.application.agent.agent.AgentMemoryDirectory.productionDefault();
    }

    /**
     * IMP-M-P1-2: 注册 MemoryScanner（记忆目录扫描 · 对齐 CC memoryScan.ts scanMemoryFiles）。
     * FindRelevantMemories 检索 + ExtractMemoriesAgent 提取共用。
     */
    @Bean
    public com.nexusai.application.agent.memory.MemoryScanner memoryScanner() {
        log.info("IMP-M-P1-2: 注册 MemoryScanner → FindRelevantMemories/MemoryPrefetcher 注入");
        return new com.nexusai.application.agent.memory.MemoryScanner();
    }

    /**
     * IMP-M-P1-2: 注册 MemoryAge（memoryAge 4 函数 · 对齐 CC memoryAge.ts，DEL-M-36 保留-接线）。
     * MemoryPrefetcher 注入头新鲜度 + ReadFileTool 新鲜度标记共用。
     */
    @Bean
    public com.nexusai.application.agent.memory.MemoryAge memoryAge() {
        log.info("IMP-M-P1-2: 注册 MemoryAge → MemoryPrefetcher/ReadFileTool 注入 (DEL-M-36 接线)");
        return new com.nexusai.application.agent.memory.MemoryAge();
    }

    /**
     * IMP-M-P1-2 + IMP-CM-09: 注册 MemoryFileDetection（isAutoMemFile/isAutoManagedMemoryFile 判定 ·
     * 对齐 CC memoryFileDetection.ts）。ReadFileTool 新鲜度标记注入 + SessionFileAccessHooks 共用。
     * team 双门控拆分（OPD-CM3-11/B04）：编译开关 feature('TEAMMEM') + 运行时开关 tengu_herring_clock
     * 从 FeatureFlags 注入（与 teamMemPaths bean 同源，消除双实例门控分裂）。
     */
    @Bean
    public com.nexusai.application.agent.memory.MemoryFileDetection memoryFileDetection(
            com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths,
            com.nexusai.application.agent.loop.FeatureFlags featureFlags) {
        log.info("IMP-M-P1-2 + IMP-CM-09: 注册 MemoryFileDetection → ReadFileTool 新鲜度注入 + team 双门控 (DEL-M-36)");
        return new com.nexusai.application.agent.memory.MemoryFileDetection(autoMemPaths,
            () -> featureFlags != null && featureFlags.teamMem(),
            () -> featureFlags != null && featureFlags.tenguHerringClock());
    }

    /**
     * IMP-M-P2-4: 注册 ClaudemdEngine（claudemd 引擎 · 对齐 CC utils/claudemd.ts）。
     * getMemoryFiles 加载序 Managed→User→Project→Local→AutoMem→TeamMem + memoize +
     * processMemoryFile/@include + processMdRules/processConditionedMdRules + getClaudeMds +
     * resetGetMemoryFilesCache（FIX-CL 压缩失效接线）+ 嵌套目录/外部 include 函数。
     * DEL-M-32 删除 ClaudemdParser 后唯一入口；FIX-CL 删 claudemd 侧 prepend 双轨
     * （前置渲染走 AgentLoopContext.prependUserContext）。
     */
    @Bean
    public com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine(
            com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths,
            com.nexusai.application.agent.memory.MemoryFileDetection memoryFileDetection,
            com.nexusai.application.agent.loop.FeatureFlags featureFlags) {
        // OPD-CM5-F-02（HIGH）: Engine teamMemoryEnabled 生产接线 FeatureFlags.teamMem() —— 不再走
        //   2 参构造（ClaudemdEngine:169-176 硬编码 () -> false）。对齐 CC claudemd.ts:995
        //   feature('TEAMMEM') && teamMemPaths!.isTeamMemoryEnabled()：teamMemoryEnabled = feature('TEAMMEM')
        //   = FeatureFlags.teamMem()（nexusai.feature.team-mem）；memoryFileDetection.isTeamMemoryEnabled()
        //   （TeamMemPaths.isTeamMemoryEnabled）已另行生产接线（:1008-1010，双门控与 CC 逐字对齐）。
        //   此前内部不一致：feature 开启时 enum 值域恢复（ClaudemdMemoryType.setTeamMemEnabled）+ MemoryFileDetection
        //   双门控开启，但 getMemoryFiles 入口恒不注入 TeamMem（△-5/T-1 根因）——本接线闭环。
        com.nexusai.application.agent.context.ClaudemdEngine engine =
            new com.nexusai.application.agent.context.ClaudemdEngine(autoMemPaths, memoryFileDetection,
                com.nexusai.application.agent.agent.CwdResolution::getOriginalCwdLayer,
                () -> true, () -> true, () -> true,
                () -> featureFlags != null && featureFlags.teamMem(),
                () -> List.of());
        // FIX-FR: tengu_moth_copse 真实门控（FeatureFlags bean → nexusai.feature.tengu-moth-copse 属性）·
        // filterInjectedMemoryFiles AutoMem/TeamMem 过滤 · CC attachments.ts:2367（startRelevantMemoryPrefetch）
        // + claudemd.ts:1146（claudemd 侧同 flag）；默认 false = 不注入（对齐 GB flag 缺省）
        engine.setMothCopseGate(() -> featureFlags != null && featureFlags.tenguMothCopse());
        // F1-4: tengu_paper_halyard 真实门控（FeatureFlags bean → nexusai.feature.tengu-paper-halyard 属性）·
        // getClaudeMds + getNestedMemoryAttachmentsForFile 跳过 Project/Local（skipProjectLevel）·
        // CC claudemd.ts:1158-1161（getClaudeMds :1165-1166）+ attachments.ts:1823-1826（:1833-1835/:1850-1852）；
        // 默认 false = 不跳过（对齐 GB flag 缺省，ClaudemdEngine.setPaperHalyardGate 未注入 → null → 恒 false）
        engine.setPaperHalyardGate(() -> featureFlags != null && featureFlags.tenguPaperHalyard());
        // IMP-CM-11（OPD-CM3-35/H1）: TEAMMEM 条件值域门控接线 —— CC memory/types.ts:9
        //   'TeamMem' 仅 feature('TEAMMEM') 开启时在 MEMORY_TYPE_VALUES；开关经 FeatureFlags.teamMem()
        //   （nexusai.feature.team-mem 属性，默认关）注入 enum 静态门控，关时 fromCcName("TeamMem")→null /
        //   activeValues() 不含 TEAM_MEM（值域对齐，消费点 TEAM_MEM 引用不变 55 处/14 文件）
        com.nexusai.application.agent.context.ClaudemdMemoryType.setTeamMemEnabled(
            featureFlags != null && featureFlags.teamMem());
        log.info("IMP-M-P2-4/FIX-CL/OPD-CM5-F-02/F1-4: 注册 ClaudemdEngine → claudemd 引擎 (claudemd.ts, DEL-M-32 替代, "
                + "mothCopseGate=tengu_moth_copse 真实门控 FIX-FR; "
                + "paperHalyardGate=tengu_paper_halyard 真实门控 F1-4=" + (featureFlags != null && featureFlags.tenguPaperHalyard())
                + "; teamMemoryEnabled=FeatureFlags.teamMem()=" + (featureFlags != null && featureFlags.teamMem())
                + " OPD-CM5-F-02 HIGH 接线; "
                + "teamMem=" + (featureFlags != null && featureFlags.teamMem()) + " IMP-CM-11 条件值域)");
        return engine;
    }

    /**
     * FIX-CL: 注册 AwaySummaryService（away-session recap）· 对齐 CC services/awaySummary.ts
     * generateAwaySummary。
     *
     * <p><b>WHY</b>: 此前 AwaySummaryService 无 @Bean 注册（生产不可达，0 调用方）。本 bean 用
     * {@link #buildForkSuppliers} 产惰性 provider/config supplier（对齐 ProductionForkedQuery 运行时
     * 解析模式，避免 bean 构造期锁定 mock provider）+ {@code SkillImprovementHook::getSmallFastModel}
     * （AS-01 rev2：共享 env 链，对齐 CC getSmallFastModel，model.ts:36-38/:131-138；旧硬编码
     * {@code () -> "haiku"} 已删）。sessionId 不在此注入（AS-05 rev2：REST 载体 resolveSessionId
     * 显式传参单轨，消除 MDC supplier 双轨）。
     *
     * <p><b>触发层 N/A（C12）</b>: CC useAwaySummary.ts 是 REPL blur 5min + feature('AWAY_SUMMARY')
     * + flag 'tengu_sedge_lantern' 默认 false 的前端钩子；Web 后端无 blur —— 本 bean 仅使服务
     * 生产可达，触发待前端接线（建议 POST /api/agent/away-summary）。
     *
     * @param llmProviderFactory LLM provider 工厂（按 config 分发）
     * @param sessionMemoryService session memory 读取（CC getSessionMemoryContent）
     * @param modelMapper        模型映射（buildForkSuppliers config 解析）
     * @param providerMapper     provider 映射（buildForkSuppliers config 解析）
     * @param providerService    provider 服务（decrypted api key）
     * @param configStorage      settings 持久层（model 读取）
     * @return away-summary 服务 bean（CompletableFuture&lt;String&gt; 两参契约 generate）
     */
    @Bean
    public com.nexusai.application.agent.memory.AwaySummaryService awaySummaryService(
            com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
            com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ModelMapper modelMapper,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ProviderMapper providerMapper,
            @Autowired(required = false) com.nexusai.domain.provider.ProviderService providerService,
            @Autowired(required = false) com.nexusai.application.agent.settings.storage.FileConfigStorage configStorage) {
        ForkSuppliers suppliers = buildForkSuppliers(
            llmProviderFactory, modelMapper, providerMapper, providerService, configStorage);
        com.nexusai.application.agent.memory.AwaySummaryService svc =
            new com.nexusai.application.agent.memory.AwaySummaryService(
                suppliers.providerSupplier(), suppliers.configSupplier(), sessionMemoryService,
                // AS-01（rev2）：small-fast 模型改接 SkillImprovementHook.getSmallFastModel 共享
                // env 链（ANTHROPIC_SMALL_FAST_MODEL → ANTHROPIC_DEFAULT_HAIKU_MODEL →
                // claude-haiku-4-5-20251001，对齐 CC model.ts:36-38/:131-138；旧硬编码 "haiku" 已删）
                com.nexusai.application.agent.permission.hook.SkillImprovementHook::getSmallFastModel);
        log.info("FIX-CL: 注册 AwaySummaryService → away-summary 生产可达 "
                + "(providerSupplier/configSupplier 惰性解析, smallFast=共享 env 链 "
                + "SkillImprovementHook.getSmallFastModel, sessionId=REST 显式单轨; "
                + "触发层 C12 N/A 待前端接线)");
        return svc;
    }

    /**
     * IMP-M-P1-4 + IMP-CM-07/09: 注册 TeamMemPaths（team 路径安全唯一 owner · 对齐 CC memdir/teamMemPaths.ts）。
     * 供 TeamMemorySyncService/TeamMemoryWatcher/TeamMemSecretGuard 注入。生产构造走双门控拆分
     * （OPD-CM3-11/B04）：编译开关 feature('TEAMMEM') = FeatureFlags.teamMem()（nexusai.feature.team-mem）
     * + 运行时开关 tengu_herring_clock = FeatureFlags.tenguHerringClock()（nexusai.feature.tengu-herring-clock，
     * 对齐 CC teamMemPaths.ts:73-78 + watcher.ts:253）。OAuth 可用性（isTeamMemorySyncAvailable）由
     * watcher/sync 层 {@code httpClient.isAuthAvailable()} 单独判定（对齐 watcher.ts:256），未登录/未授权时惰性。
     */
    @Bean
    public com.nexusai.application.agent.memory.TeamMemPaths teamMemPaths(
            com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths,
            com.nexusai.application.agent.loop.FeatureFlags featureFlags) {
        log.info("IMP-M-P1-4 + IMP-CM-09: 注册 TeamMemPaths → team 路径安全 + 双门控拆分 "
                + "(TEAMMEM=" + (featureFlags != null && featureFlags.teamMem())
                + " tengu_herring_clock=" + (featureFlags != null && featureFlags.tenguHerringClock()) + ")");
        return new com.nexusai.application.agent.memory.TeamMemPaths(autoMemPaths,
            () -> featureFlags != null && featureFlags.teamMem(),
            () -> featureFlags != null && featureFlags.tenguHerringClock());
    }

    /**
     * IMP-M-P1-4: 注册 TeamMemSecretGuard（Write/Edit validateInput checkTeamMemSecrets 注入 ·
     * 对齐 CC teamMemSecretGuard.ts）。直接复用 TeamMemorySecretScanner（DEL-M-19 删 Adapter）。
     */
    @Bean
    public com.nexusai.application.agent.memory.TeamMemSecretGuard teamMemSecretGuard(
            com.nexusai.application.agent.memory.TeamMemPaths teamMemPaths) {
        log.info("IMP-M-P1-4: 注册 TeamMemSecretGuard → Write/Edit validateInput 注入 (teamMemSecretGuard.ts)");
        return new com.nexusai.application.agent.memory.TeamMemSecretGuard(teamMemPaths);
    }

    /**
     * s09 P1-1: 注册 MemoryStorage (核心记忆存储)。
     * <p>之前 audit 偏差 (MemoryStorage.java:28): 8 个 memory 组件无 @Component / @Bean,
     * LlmAgentLoop setter 注入但无调用方, 整个 s09 特性 (~1500 行代码) 运行时不可达.
     * <p>修补: 核心 memory 组件以 @Bean 注册——MemoryStorage（本方法）、FindRelevantMemories
     * 与 MemoryPrefetcher（本类 findRelevantMemories/memoryPrefetcher @Bean）。LoadMemoryPrompt
     * 与 MemoryPromptBuilder 无 @Bean 且无组件注解：LoadMemoryPrompt 由 LlmAgentLoop 以
     * {@code new LoadMemoryPrompt(MemoryPromptBuilder.productionDefault(tel, kairos, team, mothCopse))}
     * 四参全量接线直接装配（kairos=NEW-6 部署标志 / team=IMP-MV2-19 FeatureFlags 双门控 /
     * mothCopse=IMP-MV2-12 tenguMothCopse，LlmAgentLoop buildSystemPromptAssemblyInput 生产装配点），
     * 经 SystemPromptAssemblyInput.memoryLoader 供
     * SystemPromptSections.memoryCompute 消费（prompts.ts:495-496）。
     * <p>[merge 适配 2026-08-14] 后续组件已全部补齐注册, 不再留 P1-2/3:
     * {@code SessionMemoryService}（本类 sessionMemoryService @Bean, IMP-M-P0-3/P1-3,
     * fork seam + SM 门控注入）、{@code ExtractMemoriesAgent} 与 {@code AutoDreamConsolidator}
     * （各自 @Bean, IMP-M-P0-3, 生产 fork seam + telemetry + fail-loud 接线）——原「留后续
     * (P1-2/3, 需 LLM call 集成)」注释已过时.
     */
    @Bean
    public com.nexusai.application.agent.memory.MemoryStorage memoryStorage(
            com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths) {
        log.info("s09 P1-1: 注册 MemoryStorage → LlmAgentLoop 注入 (autoMemPath=AutoMemPaths per-project, DEL-M-06)");
        // FIX-MC：CRUD 死层已删（CC 无程序化记忆写 API，模型用 Write/Edit 维护），
        // sectionCacheInvalidator 失效接线仅由 write/delete 触发 → 随 CRUD 一并移除。
        return new com.nexusai.application.agent.memory.MemoryStorage(autoMemPaths);
    }

    /**
     * IMP-M-P1-2: 注册 FindRelevantMemories (side-query LLM 查找相关记忆 · 对齐 CC findRelevantMemories.ts)
     * <p>sideModel 由 haiku 改 sonnet（对齐 CC getDefaultSonnetModel，findRelevantMemories.ts:99）+ 注入 MemoryScanner。
     */
    @Bean
    public com.nexusai.application.agent.memory.FindRelevantMemories findRelevantMemories(
            com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
            com.nexusai.application.agent.memory.MemoryScanner memoryScanner,
            com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver) {
        log.info("IMP-M-P1-2: 注册 FindRelevantMemories (sideModel=sonnet · CC getDefaultSonnetModel · RV14B-WIRE-02 注入 resolver)");
        // [RV14B-WIRE-02] sideModel "sonnet" 字面量非 DB models.name → 注入 ModelConfigResolver，
        //   selectRelevantMemories 内经 resolveFastModelName 映射 settings fast/main → DB 名再 resolve 真实 config，
        //   替换原 ProviderConfig.empty() 恒 mock（生产路径真实 LLM side-query）。
        return new com.nexusai.application.agent.memory.FindRelevantMemories(
            llmProviderFactory, "sonnet", memoryScanner, modelConfigResolver);
    }

    /**
     * IMP-M-P1-2: 注册 MemoryPrefetcher (每用户 turn 一次相关记忆预取 · 对齐 CC startRelevantMemoryPrefetch)
     * <p>注入 AutoMemPaths/MemoryAge + 门控（isAutoMemoryEnabled + tengu_moth_copse 属性）
     * + G-19/G-69 @-mention 检索隔离（AgentDefinitionRegistry + AgentMemoryDirectory）。
     */
    @Bean
    public com.nexusai.application.agent.memory.MemoryPrefetcher memoryPrefetcher(
            com.nexusai.application.agent.memory.FindRelevantMemories findRelevant,
            com.nexusai.application.agent.memory.AutoMemPaths autoMemPaths,
            com.nexusai.application.agent.memory.MemoryAge memoryAge,
            com.nexusai.application.agent.loop.FeatureFlags featureFlags,
            @org.springframework.context.annotation.Lazy com.nexusai.application.agent.tool.impl.SubagentTool subagentTool,
            com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDirectory) {
        return new com.nexusai.application.agent.memory.MemoryPrefetcher(
            findRelevant, autoMemPaths, memoryAge,
            com.nexusai.application.agent.skill.BundledSkillEnabledGates::isAutoMemoryEnabled,
            () -> featureFlags != null && featureFlags.tenguMothCopse(),   // FIX-FR 真实门控（nexusai.feature.tengu-moth-copse 属性）
            // 惰性 supplier：bean 装配期不得强制解析 subagentTool（@Lazy 代理 + AgentLoopContextFactory
            // 循环依赖防护）—— 预取运行时（bean 已就绪）才经代理取 registry
            () -> subagentTool != null ? subagentTool.agentRegistry() : null,
            agentMemoryDirectory);
    }

    /**
     * IMP-M-P0-3 + P1-3: 注册 SessionMemoryService（SM 生产接线 · DEL-M-48 bean null 消除 +
     * P1-3 提取管线 fork seam 注入）。
     *
     * <p><b>WHY</b>: 此前 AutoCompactor.setSessionMemoryService / /compact ctx SM 生产恒 null
     * （注释「留 P1-2/3」），SM 优先压缩路径不可达。本 @Bean 让 {@code @Autowired(required=false)
     * SessionMemoryService} 与 /compact handler 注入非 null。
     *
     * <p><b>P1-3 fork seam（sessionMemory.ts:318/:420 runForkedAgent）</b>: 注入 {@code setForkedQuery}
     * ({@link com.nexusai.application.agent.compact.fork.ProductionForkedQuery} 专用 fork loop，
     * canUseTool 受限门控真实生效) + {@code setCacheSafeParamsSupplier}（主线程工具集）+
     * {@code setSessionMemoryFeatureEnabled}（tengu_session_memory 部署标志，NEXUSAI_SESSION_MEMORY
     * env 等价，OPD-M-52 模式）。
     *
     * <p><b>[SM-DB-gate]</b> 本装配仅作<b>回落源</b>：DB settings.sm_session_memory_enabled 有值
     * 覆盖（实时读源 {@code settingsResolver} 注入 :1419，SessionMemoryService 提取门控
     * {@code resolveSessionMemoryFeatureEnabled()} DB 优先）——前端配 DB=true/false 即控制提取开关，
     * 无需重启。
     *
     * <p><b>IMP-CM-01（OPD-CM3-03/A01 · X3/X4 生产 seam 接线）</b>: 此前 Javadoc 声称注入
     * setForkedQuery/setCacheSafeParamsSupplier 但代码体未调（X3/X4 根因）——生产
     * SessionMemoryService.forkedQuery 恒 null → {@code doExtractSessionMemory} 每次 log.warn
     * 提前 return（跳过 fork + 跳过 markExtractionCompleted → extractionStartedAt 滞留，
     * SM 压缩 wait 阻塞满 15s）。CC extract/manual 两分支均恒执行 runForkedAgent
     * （sessionMemory.ts:318-325/:420-433，无 null 守卫）—— 下方代码体与 ExtractMemoriesAgent/
     * AutoDreamConsolidator（IMP-M-P0-3 模式）同源注入，闭环 X3/X4。
     *
     * <p>baseDir 用 projectDir（session-memory 落 {@code {projectDir}/{sessionId}/session-memory/summary.md}，
     * 对齐 CC filesystem.ts:261-271 getSessionMemoryPath）。
     */
    @Bean
    public com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService(
            @org.springframework.context.annotation.Lazy ToolRegistry toolRegistry,
            com.nexusai.application.agent.compact.fork.ProductionForkedQuery productionForkedQuery,
            com.nexusai.application.agent.telemetry.Telemetry telemetry,
            // [FIX-SM] ObjectProvider 懒解析 autoCompactor → isAutoCompactEnabled 门控。
            //   避免 autoCompactor(→sessionMemoryService) ↔ sessionMemoryService(→autoCompactor)
            //   构造期循环依赖：ObjectProvider 注入的是惰性代理，@PostConstruct 时才触发解析；
            //   autoCompactor.isAutoCompactEnabled() 只读 env + userConfig 字段（不依赖 SM），
            //   解析安全。解析失败/未就绪 → 默认 true（不门控，同 null supplier 语义）。
            org.springframework.beans.factory.ObjectProvider<AutoCompactor> autoCompactorProvider,
            // [OD-01 S4] SM 压缩双 flag（tengu_session_memory && tengu_sm_compact）· sessionMemoryCompact.ts:412-420。
            //   FeatureFlags bean → nexusai.feature.sm-session-memory / sm-compact 属性（FeatureFlags.java:110-115）。
            com.nexusai.application.agent.loop.FeatureFlags featureFlags,
            // [SM-02] SessionStart hooks 执行器（HookRegistry @Component；缺省 null → hookResults 空）
            @org.springframework.lang.Nullable com.nexusai.application.agent.permission.hook.HookRegistry hookRegistry,
            // [G-41] SM 文件读取通道（ReadFileTool @Component；缺省 null → setup fail-loud，无直读降级）
            @org.springframework.lang.Nullable com.nexusai.application.agent.tool.impl.ReadFileTool readFileTool,
            // [V52 B1-6] 压缩配置 DB 实时读源（settings.sm_session_memory_enabled / sm_compact_enabled）
            com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        // ODF-A1: SM 会话根 = 会话 projectRoot（per-session · 绝不读 JVM 进程工作目录）
        java.nio.file.Path baseDir = java.nio.file.Paths.get(
            com.nexusai.application.agent.memory.AutoMemPaths.currentSessionProjectRoot());
        com.nexusai.application.agent.memory.SessionMemoryService svc =
            new com.nexusai.application.agent.memory.SessionMemoryService(baseDir);
        // P1-3: 提取管线生产 fork seam 注入（runForkedAgent querySource='session_memory'）
        // [SM-06] 门控双源统一（DRIFT-8）· CC 提取与压缩读同一 tengu_session_memory flag
        //   （sessionMemory.ts:80-82 与 sessionMemoryCompact.ts:412-420 两处独立读取，恒同源）——
        //   旧实现提取 = env NEXUSAI_SESSION_MEMORY、压缩门控① = FeatureFlags.smSessionMemory
        //   （两独立开关可分裂：env on + flag off → 提取跑、压缩不跑，NOT_ALIGNED）。
        //   统一源 = 部署标志等价 NEXUSAI_SESSION_MEMORY（OPD-M-52 模式）OR
        //   nexusai.feature.sm-session-memory（FeatureFlags 映射同一 CC flag，Spring relaxed-binding
        //   双拼写 = 同一部署开关）；单值计算一次，两门控同源注入（CC 同一 flag 双读）。
        //   [SM-DB-gate] 本值仅作回落源：DB settings.sm_session_memory_enabled 有值由
        //   settingsResolver（:1419 注入）实时覆盖（提取门控 resolveSessionMemoryFeatureEnabled +
        //   压缩门控 shouldUseSessionMemoryCompaction 均 DB 优先）。
        boolean tenguSessionMemory = isEnvTruthy(System.getenv("NEXUSAI_SESSION_MEMORY"))
            || (featureFlags != null && featureFlags.smSessionMemory());
        svc.setSessionMemoryFeatureEnabled(tenguSessionMemory);
        // [SM-06] 压缩门控①与提取门控同一源（不再 FeatureFlags.smSessionMemory 独立分裂）
        svc.setSmSessionMemoryEnabled(tenguSessionMemory);
        // [OD-01 S4] SM 压缩双 flag ②（tengu_sm_compact 仍为独立 flag · sessionMemoryCompact.ts:416-419）
        if (featureFlags != null) {
            svc.setSmCompactEnabled(featureFlags.smCompact());
        }
        // [V52 B1-6] DB settings.sm_session_memory_enabled / sm_compact_enabled 实时读源注入
        //   （shouldUseSessionMemoryCompaction 内部 DB 有值覆盖上述 flag，null 回落）
        svc.setSettingsResolver(settingsResolver);
        // [SM-02] SessionStart hooks 执行器注入（sessionMemoryCompact.ts:583-586）· HookRegistry @Component
        svc.setSessionStartHookRegistry(hookRegistry);
        // [SM-02] 主循环模型供应器（hook input data.model · CC getMainLoopModel 等价；
        //   fork modelSupplier 同源 = configStorage settings model 解析）
        if (productionForkedQuery != null) {
            svc.setMainLoopModelSupplier(productionForkedQuery.modelSupplier());
        }
        // IMP-CM-01（OPD-CM3-03/A01 · X3/X4）: SM 提取 + manual fork 生产 seam 接线
        //   —— CC extractSessionMemory:318-325 / manuallyExtractSessionMemory:420-433 恒执行
        //   runForkedAgent（无 null 守卫）。此前代码体未调（X3/X4 根因），生产 forkedQuery 恒 null →
        //   doExtractSessionMemory:581 log.warn 提前 return（跳过 fork + markExtractionCompleted 跳过 →
        //   extractionStartedAt 滞留，SM 压缩 wait 阻塞满 15s）。与 ExtractMemoriesAgent/AutoDreamConsolidator
        //   同源（IMP-M-P0-3 模式，ExtractMemoriesAgent bean :1168/:1171）。
        svc.setForkedQuery(productionForkedQuery);
        // cache-safe params supplier：fork 消息前缀 + 主线程工具集（buildProductionCacheSafeParams
        //   唯一有效载荷 toolUseContext，SystemPrompt/Context 由 SessionMemoryService.mergeSystemPrompt/
        //   mergeContext 用会话原料补全 —— doExtractSessionMemory:537-562）
        svc.setCacheSafeParamsSupplier(() -> buildProductionCacheSafeParams(toolRegistry));
        // OPD-CM5-B-01: firstParty fork 缓存共享 gate 生产求值注入 —— 对齐 CC shouldUseGlobalCacheScope()
        //   （betas.ts:227-233）单实现 GlobalCacheScope，config 与 compact 同源（buildForkSuppliers →
        //   productionForkedQuery.configSupplier()）。此前 SM bean 未接线（默认 () -> false = 3P），
        //   与 ExtractMemoriesAgent/AutoDreamConsolidator（OPD-R2-EX-05/AD-04）不一致——SM fork 边界
        //   按 3P 处理，firstParty 部署缓存 key 与主循环可能不一致。productionForkedQuery 缺失（非 Spring
        //   直构测试）→ null-safe 回落 false（等价 setter null 语义，见 SessionMemoryService.java:314-318）。
        svc.setUseGlobalCacheScopeSupplier(() ->
            productionForkedQuery != null
                ? com.nexusai.application.agent.compact.fork.GlobalCacheScope
                    .shouldUseGlobalCacheScope(productionForkedQuery.configSupplier().get())
                : false);
        // [G-41] SM 文件读取经权限层（FileReadTool.call 等价）· ReadFileTool @Component
        svc.setReadFileTool(readFileTool);
        // [IMP-CM-04] SM 结果构造注入 plan_file_reference（CC sessionMemoryCompact.ts:484-485）·
        //   数据源与 AutoCompactor planProvider 同源（:667）；无 bean → null → SM 路径按 sessionId
        //   回落 PlanProviderImpl 读磁盘（对齐 PostCompactAttachmentRestorer.resolvePlanProvider）。
        svc.setPlanProvider(planProvider);
        // IMP-M-C-1: 遥测（tengu_session_memory_loaded · sessionMemoryUtils.ts:117）
        svc.setTelemetry(telemetry);
        // IMP-SUB-29（B10）: tengu_fork_agent_query 遥测静态注入 —— RunForkedAgent.run 单漏斗
        //   发射点（compact/extract/auto-dream/session-memory 全 fork 路径），本 bean 装配时
        //   注入一次；未装配（测试）→ null → 发射静默跳过。CC 真源 forkedAgent.ts:612-620。
        com.nexusai.application.agent.compact.fork.RunForkedAgent.setTelemetry(telemetry);
        // [FIX-SM] initSessionMemory isAutoCompactEnabled 门控（CC sessionMemory.ts:360-371）·
        //   SessionMemory 服务于压缩，autoCompact 关闭 → 不注册提取 hook
        svc.setAutoCompactEnabledSupplier(() -> {
            try {
                AutoCompactor ac = autoCompactorProvider.getIfAvailable();
                return ac != null && ac.isAutoCompactEnabled();
            } catch (Exception e) {
                log.warn("[sessionMemoryService bean] autoCompactorProvider 懒解析失败，默认启用 SM 提取: {}",
                    e.toString());
                return true;
            }
        });
        log.info("IMP-M-P0-3/P1-3/C-1/FIX-SM: 注册 SessionMemoryService → AutoCompactor /compact 注入 "
            + "(baseDir={}, forkSeam={}, smExtractionGate={}, telemetry={}, autoCompactGate=注入)",
            baseDir, productionForkedQuery != null,
            isEnvTruthy(System.getenv("NEXUSAI_SESSION_MEMORY")), telemetry != null);
        return svc;
    }

    /**
     * IMP-M-P0-3: 注册 ExtractMemoriesAgent（forked-agent 重建 · DEL-M-43..48）。
     *
     * <p><b>WHY</b>: 此前 extractAgent 是 {@code @Autowired(required=false)} 恒 null（生产双
     * no-op 接线缺口，DEL-M-48）。本 @Bean 用 MemoryStorage 重建为 CC forked-agent。
     *
     * <p><b>fork seam（IMP-M-P0-3 生产接线 · R9/IMP-18 收敛）</b>: 注入 {@code setForkedQuery}
     * ({@link com.nexusai.application.agent.compact.fork.ProductionForkedQuery} 专用多轮 fork loop，
     * canUseTool 受限门控 INV-6 真实生效) + {@code setCacheSafeParamsSupplier}(主线程工具集)。
     * 未注入为编程错误 → ExtractMemoriesAgent.extract fail-loud（不再静默跳过）。
     */
    @Bean
    public com.nexusai.application.agent.memory.ExtractMemoriesAgent extractMemoriesAgent(
            com.nexusai.application.agent.memory.MemoryStorage memoryStorage,
            @org.springframework.context.annotation.Lazy ToolRegistry toolRegistry,
            com.nexusai.application.agent.compact.fork.ProductionForkedQuery productionForkedQuery,
            com.nexusai.application.agent.telemetry.Telemetry telemetry,
            com.nexusai.application.agent.loop.FeatureFlags featureFlags) {
        com.nexusai.application.agent.memory.ExtractMemoriesAgent agent =
            new com.nexusai.application.agent.memory.ExtractMemoriesAgent(memoryStorage);
        // IMP-M-P0-3: 生产 fork seam 首接入（R9/IMP-18 收敛）——注入专用多轮 fork loop，
        // canUseTool 受限门控真实生效（INV-6）；此前 seam 缺失跳过分支已改 fail-loud。
        agent.setForkedQuery(productionForkedQuery);
        // cache-safe params supplier：toolUseContext 携带主线程工具集（fork 需要真实工具数组
        //   Read/Write/Edit/Bash 等 + abort/权限继承；createMinimalCacheSafeParams 无工具集 → 空 tools）
        agent.setCacheSafeParamsSupplier(() -> buildProductionCacheSafeParams(toolRegistry));
        // IMP-M-C-1: 遥测（tengu_extract_memories_* / tengu_auto_mem_tool_denied ·
        //   extractMemories.ts:356/473/500/156）
        agent.setTelemetry(telemetry);
        // OPD-R2-EX-05（G-81 · RES-C5 生产接线）: firstParty fork 缓存共享 gate 生产求值注入
        //   —— CC shouldUseGlobalCacheScope()（betas.ts:227-233）单实现 GlobalCacheScope，
        //   config 与 compact 同源（buildForkSuppliers → productionForkedQuery.configSupplier()）；
        //   生产 gate 不再恒 false（旧字段默认 () -> false = 3P 默认）
        agent.setUseGlobalCacheScopeSupplier(() ->
            com.nexusai.application.agent.compact.fork.GlobalCacheScope
                .shouldUseGlobalCacheScope(productionForkedQuery.configSupplier().get()));
        // IMP-MV2-12（单轨收敛）: skipIndexGate 接线 FeatureFlags.tenguMothCopse —— 提取 prompt
        //   skipIndex（CC extractMemories.ts:366-369 同源 GB flag）与 loadMemoryPrompt skipIndex /
        //   预取门控 / claudemd 过滤共用单一 flag 源；env 旁路 NEXUSAI_EXTRACT_MEMORIES_SKIP_INDEX
        //   仅作未注入兜底（测试注入/本接线均覆盖它）。
        agent.setSkipIndexGate(() -> featureFlags != null && featureFlags.tenguMothCopse());
        log.info("IMP-M-P0-3/C-1: 注册 ExtractMemoriesAgent (forked-agent 重建, DEL-M-43..48, "
            + "生产 fork seam 注入 + telemetry)");
        return agent;
    }

    /**
     * IMP-M-P0-3: 注册 AutoDreamConsolidator（isAutoDreamEnabled 门控 + fork 写文件 ·
     * DEL-M-21/22 mechanical/JSON 协议删除）。
     *
     * <p><b>WHY</b>: 此前 autoDreamConsolidator 恒 null（DEL-M-48），auto-dream 不可达。
     * 本 @Bean 注册后 StopHookPipeline 阶段 4 可达。isAutoDreamEnabled 走 <b>[V56 · 用户
     * 2026-08-30 拍板] DB settings 列 auto_dream_enabled 主控，默认开</b>：DB 列有值用之
     * （BundledSkillEnabledGates.readAutoDreamEnabledSetting 仅读 DB 列）→ env NEXUSAI_AUTO_DREAM
     * 可选覆盖 → 默认 true。弃用 settings.json 文件承载键（旧 OPD-CM3-24 Q1「默认关闭需显式
     * 开启」已废止）。
     *
     * <p><b>fork seam（IMP-M-P0-3 生产接线 · R9/IMP-18 收敛）</b>: 注入 {@code setForkedQuery}
     * ({@link com.nexusai.application.agent.compact.fork.ProductionForkedQuery}) +
     * {@code setCacheSafeParamsSupplier}。未注入为编程错误 → 由 {@code RunForkedAgent.run}
     * 的 query==null 守卫 fail-loud（IllegalArgumentException → catch 回滚锁 + failed 遥测，
     * 可观测；IMP-MV2-30 DC-8 守卫删除后由通用层承接）。
     */
    @Bean
    public com.nexusai.application.agent.memory.AutoDreamConsolidator autoDreamConsolidator(
            com.nexusai.application.agent.memory.MemoryStorage memoryStorage,
            @org.springframework.context.annotation.Lazy ToolRegistry toolRegistry,
            com.nexusai.application.agent.compact.fork.ProductionForkedQuery productionForkedQuery,
            com.nexusai.application.agent.telemetry.Telemetry telemetry,
            com.nexusai.application.agent.tasks.DreamTaskRegistry dreamTaskRegistry,
            // FIX-AD: 动态阈值（CC GB tengu_onyx_plover minHours/minSessions → Spring property 代偿，
            //   缺省 24/5 对齐 autoDream.ts:63-66 DEFAULTS；部署可改，无 GB 远程热推 infra）
            @Value("${nexusai.autodream.min-hours:24}") double minHours,
            @Value("${nexusai.autodream.min-sessions:5}") int minSessions) {
        com.nexusai.application.agent.memory.AutoDreamConsolidator consolidator =
            new com.nexusai.application.agent.memory.AutoDreamConsolidator(memoryStorage);
        // IMP-M-P0-3: 生产 fork seam 首接入——注入专用多轮 fork loop（autoDream.ts:224-233
        //   runForkedAgent({querySource:'auto_dream', skipTranscript:true})），canUseTool 受限门控
        //   真实生效（INV-6）；未注入由 RunForkedAgent.run 守卫 fail-loud（IMP-MV2-30 DC-8）。
        consolidator.setForkedQuery(productionForkedQuery);
        consolidator.setCacheSafeParamsSupplier(() -> buildProductionCacheSafeParams(toolRegistry));
        // IMP-M-P2-1: 遥测（tengu_auto_dream_fired/completed/failed · autoDream.ts:195/252/267）
        consolidator.setTelemetry(telemetry);
        // IMP-M-P2-1/D5-A (M-11): 会话门控数据源参数化 —— workspaceDir/sessionId 不再经
        //   @Bean 共享 volatile 注入（异步 consolidateIfNeeded 的跨会话交错窗口），由
        //   LlmAgentLoop stop-hook 注入处按会话捕获后显式传
        //   consolidateIfNeeded(workspaceDir, sessionId, ...)（D5-A 裁决，09-open-decisions）。
        // FIX-AD: 动态阈值接线（每轮 consolidateIfNeeded 经 configSupplier.get() 读取 ·
        //   autoDream.ts:126/73-91 getConfig 每轮读取语义；@Value 缺省 24/5 对齐 CC DEFAULTS）
        consolidator.setConfigSupplier(() ->
            new com.nexusai.application.agent.memory.AutoDreamConsolidator.AutoDreamConfig(minHours, minSessions));
        // OPD-R2-AD-04（G-81 · RES-C5 生产接线）: firstParty fork 缓存共享 gate 生产求值注入
        //   —— 同 extractMemoriesAgent（OPD-R2-EX-05）：GlobalCacheScope 单实现（betas.ts:227-233）
        //   + config 与 compact 同源（productionForkedQuery.configSupplier()）；生产 gate 不再恒 false
        consolidator.setUseGlobalCacheScopeSupplier(() ->
            com.nexusai.application.agent.compact.fork.GlobalCacheScope
                .shouldUseGlobalCacheScope(productionForkedQuery.configSupplier().get()));
        // OPD-TP-09: dream task registry 接线（register/addDreamTurn/complete/fail/kill ·
        //   setDreamTaskRegistry 内部把 kill 的锁回退 seam 注入 registry —— DreamTask.kill
        //   rollbackConsolidationLock 对齐）
        consolidator.setDreamTaskRegistry(dreamTaskRegistry);
        log.info("IMP-M-P0-3/P2-1/FIX-AD/OPD-TP-09: 注册 AutoDreamConsolidator (isAutoDreamEnabled 门控 + fork 写文件 + 锁/遥测 + 动态阈值 minHours={}h/minSessions={} + dream registry={}, DEL-M-21/22/23..28, 生产 fork seam 注入)",
            minHours, minSessions, dreamTaskRegistry != null);
        return consolidator;
    }

    /**
     * IMP-M-P0-3: 生产 ForkedQuery（专用多轮 fork loop）· 对齐 CC query() fork 语义
     * (Open-ClaudeCode/src/query.ts:181-199 + forkedAgent.ts:545-556)。
     *
     * <p><b>WHY</b>: extract-memories / auto-dream 后台 fork（querySource='extract_memories'/
     * 'auto_dream'）在生产必须真实执行多轮 LLM loop —— 这是 {@code setForkedQuery} 生产调用点
     * （DEL-M-48 接线缺口消除 + 先行者风险 R9/IMP-18 收敛）。
     *
     * <p><b>为什么专用 loop 而非 LlmAgentLoop.queryLoop（INV-6 破坏风险）</b>: 主循环权限消费点
     * 在内层 StreamingToolExecutor（继承主线程 permissionGate），QueryParams.canUseTool 无消费点
     * （H9-GAP-4，QueryParams.java:45 已删）——直接复用会让 fork 继承主线程权限，破坏 INV-6 的
     * 受限 canUseTool（Read/Grep/Glob + 只读 Bash + auto-memory 目录内 Edit/Write）。本 bean
     * 经 {@code HookPermissionResolver.resolve(canUseTool)} 直接消费受限 canUseTool。
     *
     * @param llmProviderFactory LLM provider 工厂（按 config 分发）
     * @param toolRegistry       主线程工具注册表（@Lazy 懒代理，执行期已填充；fork 工具集）
     * @return 生产 fork loop（逐轮 provider 调用 + ToolRegistry 执行 + canUseTool 门控）
     */
    @Bean
    public com.nexusai.application.agent.compact.fork.ProductionForkedQuery productionForkedQuery(
            com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
            @org.springframework.context.annotation.Lazy ToolRegistry toolRegistry,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ModelMapper modelMapper,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ProviderMapper providerMapper,
            @Autowired(required = false) com.nexusai.domain.provider.ProviderService providerService,
            @Autowired(required = false) com.nexusai.application.agent.settings.storage.FileConfigStorage configStorage) {
        ForkSuppliers suppliers = buildForkSuppliers(
            llmProviderFactory, modelMapper, providerMapper, providerService, configStorage);
        log.info("IMP-M-P0-3: 注册 ProductionForkedQuery（生产 fork loop · extract/auto-dream seam 首接入 R9/IMP-18）");
        return new com.nexusai.application.agent.compact.fork.ProductionForkedQuery(
            suppliers.providerSupplier(), suppliers.modelSupplier(), suppliers.configSupplier(), toolRegistry);
    }

    /**
     * provider/model/config 供应商组 · 供 streamCompactSummary 与 productionForkedQuery 共享
     * （避免双实现漂移 —— 两 bean 的 LLM 供应商解析必须同源）。
     */
    private record ForkSuppliers(
            java.util.function.Supplier<com.nexusai.infra.llm.LlmProvider> providerSupplier,
            java.util.function.Supplier<String> modelSupplier,
            java.util.function.Supplier<com.nexusai.infra.llm.ProviderConfig> configSupplier,
            java.util.function.Supplier<String> providerTypeSupplier) {}

    /**
     * 构建 LLM 供应商组 · 对齐 streamCompactSummary 既有解析（model/config 同源）。
     */
    private static ForkSuppliers buildForkSuppliers(
            com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
            com.nexusai.repository.provider.mapper.ModelMapper modelMapper,
            com.nexusai.repository.provider.mapper.ProviderMapper providerMapper,
            com.nexusai.domain.provider.ProviderService providerService,
            com.nexusai.application.agent.settings.storage.FileConfigStorage configStorage) {
        // [FIX-STRIP-PREFIX] 拆双 supplier：rawModelSupplier 读 settings 原始值（可能全名，
        //   供 configSupplier/providerTypeSupplier 精确反查跨 provider 同名模型）；
        //   modelSupplier 剥 provider 前缀为裸名（供 StreamCompactSummary / ProductionForkedQuery
        //   作 provider.stream 发送名 —— SDK model 参数必须裸名，deepseek/deepseek-v4-flash → 400）。
        java.util.function.Supplier<String> rawModelSupplier = () -> {
            String resolved = null;
            if (configStorage != null) {
                Object stored = configStorage.readSettings(java.util.List.of("model"));
                if (stored != null
                        && stored != com.nexusai.application.agent.settings.storage.ConfigStorage.NullMarker) {
                    String s = String.valueOf(stored);
                    if (!s.isBlank()) {
                        resolved = s;
                    }
                }
            }
            return resolved;
        };
        // 发送名剥名（对齐 ModelConfigResolver.resolveSdkModelName 语义：全名 → DB models.name 裸名）。
        // 未命中（裸名/别名/无 mapper）→ 回落原始值透传（CC 未知名直接传 API，失败即失败）。
        java.util.function.Supplier<String> modelSupplier = () -> {
            String raw = rawModelSupplier.get();
            if (raw == null || raw.isBlank() || modelMapper == null || providerMapper == null) {
                return raw;
            }
            try {
                com.nexusai.repository.provider.entity.ModelRecord rec =
                    com.nexusai.infra.llm.ModelNameResolver.resolve(modelMapper, providerMapper, raw);
                String bare = rec != null && rec.getName() != null ? rec.getName() : null;
                if (bare != null && !bare.isBlank()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[FIX-STRIP-PREFIX] fork 发送名剥前缀: {} → {}（SDK model 参数用裸名）",
                            raw, bare);
                    }
                    return bare;
                }
            } catch (Exception e) {
                log.warn("[FIX-STRIP-PREFIX] fork 发送名剥名失败, 回落原始值: {}", e.toString());
            }
            return raw;
        };

        java.util.function.Supplier<com.nexusai.infra.llm.ProviderConfig> configSupplier = () -> {
            // [FIX-STRIP-PREFIX] 解析用 settings 原始值（可能全名）精确反查跨 provider 同名模型，
            // 不随 modelSupplier 剥名（modelSupplier 现在返回裸名供发送，反查需要全名精确定位）。
            String model = rawModelSupplier.get();
            if (model == null || model.isBlank() || modelMapper == null || providerMapper == null
                    || providerService == null) {
                return com.nexusai.infra.llm.ProviderConfig.empty();
            }
            try {
                // W1-2: 统一走全名解析器（providerName/modelName 联合查, 无 / 回退按 name 查第一条）
                com.nexusai.repository.provider.entity.ModelRecord modelRecord =
                    com.nexusai.infra.llm.ModelNameResolver.resolve(modelMapper, providerMapper, model);
                if (modelRecord == null || modelRecord.getProviderId() == null) {
                    return com.nexusai.infra.llm.ProviderConfig.empty();
                }
                com.nexusai.repository.provider.entity.ProviderRecord provider =
                    providerMapper.selectOneById(modelRecord.getProviderId());
                if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
                    return com.nexusai.infra.llm.ProviderConfig.empty();
                }
                String rawKey = providerService.getDecryptedApiKey(provider.getId());
                if (rawKey == null || rawKey.isBlank()) {
                    return com.nexusai.infra.llm.ProviderConfig.empty();
                }
                return new com.nexusai.infra.llm.ProviderConfig(provider.getBaseUrl(), rawKey);
            } catch (Exception e) {
                log.warn("configSupplier 解析失败, 回落 mock: {}", e.toString());
                return com.nexusai.infra.llm.ProviderConfig.empty();
            }
        };

        // [E5-修复] providerType 独立从 DB 解析（model → provider → provider.type）。
        // 勿依赖 providerSupplier.get().type() —— 旧 1 参 getProvider 恒路由 openai_sdk，
        // anthropic 型 provider 被误路由（MAINCHAIN-01 同类缺陷），type() 亦随之外泄 openai_sdk。
        // 失败回落 null（2 参工厂默认 openai_sdk；countTokensClient 落 tiktoken 分支）。
        java.util.function.Supplier<String> providerTypeSupplier = () -> {
            try {
                // [FIX-STRIP-PREFIX] 同 configSupplier：解析用 settings 原始值（全名）精确反查，
                // 不随 modelSupplier 剥名（裸名只用于 SDK 发送名）。
                String model = rawModelSupplier.get();
                if (model == null || model.isBlank() || modelMapper == null || providerMapper == null) {
                    return null;
                }
                com.nexusai.repository.provider.entity.ModelRecord modelRecord =
                    com.nexusai.infra.llm.ModelNameResolver.resolve(modelMapper, providerMapper, model);
                if (modelRecord == null || modelRecord.getProviderId() == null) {
                    return null;
                }
                com.nexusai.repository.provider.entity.ProviderRecord provider =
                    providerMapper.selectOneById(modelRecord.getProviderId());
                if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
                    return null;
                }
                String type = provider.getType() != null ? provider.getType() : "openai_compatible";
                if (log.isInfoEnabled()) {
                    log.info("[E5] fork/后台 providerType 由 DB 解析 model={} → providerType={}", model, type);
                }
                return type;
            } catch (Exception e) {
                log.warn("providerTypeSupplier DB 解析失败, 回落 null(工厂默认 openai_sdk): {}", e.toString());
                return null;
            }
        };

        // [E5-修复] fork/后台 provider 按真实 providerType 2 参路由（对齐 LlmAgentLoop:3684 /
        // ModelCaller:67 的 MAINCHAIN-01 修正），消除 1 参 getProvider 恒 openai_sdk 的 anthropic 误路由。
        java.util.function.Supplier<com.nexusai.infra.llm.LlmProvider> providerSupplier = () -> {
            com.nexusai.infra.llm.ProviderConfig config = configSupplier.get();
            String providerType = providerTypeSupplier.get();
            com.nexusai.infra.llm.LlmProvider provider = llmProviderFactory.getProvider(config, providerType);
            if (log.isInfoEnabled()) {
                log.info("[E5] fork/后台 provider 路由 config.isUsable={} providerType={} → 实际 providerType={}",
                    config != null && config.isUsable(), providerType, provider.type());
            }
            return provider;
        };

        return new ForkSuppliers(providerSupplier, modelSupplier, configSupplier, providerTypeSupplier);
    }

    /**
     * 生产 cache-safe params 载荷构建 · toolUseContext 携带主线程工具集。
     *
     * <p><b>WHY</b>: fork 需要向 provider 传真实工具数组（Read/Write/Edit/Bash 等）——
     * {@code RunForkedAgent.createMinimalCacheSafeParams} 兜底是空工具集（availableTools 空），
     * fork 模型将无法调用任何工具（extract 无法写记忆 / auto-dream 无法读文件）。CC 后台 fork
     * 消费方为 {@code createCacheSafeParams(context)}
     * （services/extractMemories/extractMemories.ts:372 + services/autoDream/autoDream.ts:226，
     * 非 utils/extractMemories.ts / utils/autoDream.ts —— C5 路径勘误）。supplier 在
     * extract/autoDream 执行时求值（toolRegistry @Lazy 懒代理此时已填充）。
     *
     * <p><b>[IMP-MV2-09 T9] 本方法只承载 toolUseContext（主线程工具集）</b>：三段
     * systemPrompt/userContext/systemContext 与 forkContextMessages 由 LlmAgentLoop:5154
     * 捕获的 {@code ForkRawMaterial}（CC createCacheSafeParams(context) forkedAgent.ts:131-141）
     * 在 ExtractMemoriesAgent/AutoDreamConsolidator 侧合并注入 —— 此处空载荷不再代表最终
     * fork 载荷（旧 RES-C5 降级登记关闭：stop-hook 调用点现可访问当轮 fullSystemPrompt/
     * userContext/systemContext/消息快照）。null 原料（非主循环入口）→ 消费者侧既有兜底。
     *
     * @param toolRegistry 主线程工具注册表（@Lazy；null → 空工具集兜底）
     * @return CacheSafeParams（forkContextMessages 由 ExtractMemoriesAgent/AutoDreamConsolidator
     *         在构造 fork 参数时用实际消息覆写；三段原料由 ForkRawMaterial 合并注入）
     */
    private static com.nexusai.application.agent.compact.fork.CacheSafeParams buildProductionCacheSafeParams(
            ToolRegistry toolRegistry) {
        // [session-id-short] 兜底 forkBaseCtx sessionId 统一 short 形态（sess-xxx）
        com.nexusai.application.agent.tool.ToolUseContext forkBaseCtx =
            new com.nexusai.application.agent.tool.ToolUseContext(
                UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
                com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
                java.util.Map.of(),
                toolRegistry != null ? toolRegistry.all() : List.of(),
                "", new AbortController(), List.of());
        return new com.nexusai.application.agent.compact.fork.CacheSafeParams(
            List.of(), java.util.Map.of(), java.util.Map.of(), forkBaseCtx, List.of());
    }

    /**
     * s11 P1-1: 注册 Error Recovery Handler (MaxTokens + Transient).
     * <p>之前 audit 偏差 (recovery/): Handler 无 @Component / @Bean,
     * LlmAgentLoop setter 注入但无调用方, 恢复路径运行时完全不可达.
     * <p>[IMP-02 D-25] prompt-too-long 不再有独立 handler——PTL 恢复内联主循环
     * （collapse drain → reactive compact，CC query.ts:1086-1183）。
     * <p>对齐 CC query.ts:652-997 (withRetry + collapse drain + reactive compact + max_tokens recovery).
     */
    @Bean
    public com.nexusai.application.agent.recovery.MaxTokensHandler maxTokensHandler() {
        log.info("s11 P1-1: 注册 MaxTokensHandler → LlmAgentLoop 注入 (max_output_tokens recovery)");
        return new com.nexusai.application.agent.recovery.MaxTokensHandler();
    }

    @Bean
    public com.nexusai.application.agent.recovery.TransientErrorHandler transientErrorHandler(
            com.nexusai.repository.settings.mapper.SettingsMapper settingsMapper) {
        log.info("s11 P1-1: 注册 TransientErrorHandler → LlmAgentLoop 注入 (429/529 backoff + fallback model)");
        // [F4] FALLBACK_MODEL_ID env → settings.fallbackModelName（V27 原建列 fallback_model_id，
        //   [FN2] 字段改名 fallbackModelName · V28 RENAME → fallback_model_name，用户拍板）。
        //   CC 无此 env（withRetry.ts:337-351 按调用传入 options.fallbackModel）；Java 原以 env 提供
        //   默认值属自建，现迁移 settings 配置（注入 SettingsMapper 读单例行 id=1）。
        //   未配置（null/blank）→ null → 529 快速失败不降级（对齐 CC 无全局默认）。
        com.nexusai.application.agent.recovery.TransientErrorHandler.setSettingsFallbackProvider(() -> {
            com.nexusai.repository.settings.entity.SettingsRecord s =
                settingsMapper.selectOneById(1);
            return s == null ? null : s.getFallbackModelName();
        });
        return new com.nexusai.application.agent.recovery.TransientErrorHandler();
    }

    /**
     * [IMP-02 D-27] 注册 ReactiveCompactor（REACTIVE_COMPACT）· 对齐 CC query.ts:1119-1132。
     *
     * <p><b>WHY</b>: D-27 W1 接线缺口——{@code AgentLoopContextFactory} 的
     * {@code @Autowired(required=false) ReactiveCompactor} 生产恒 null（无 bean），
     * reactive compact 分支（PTL/media 恢复 · query.ts:1119）不可达。本 @Bean 经
     * {@code featureFlags.reactiveCompact()} 驱动 {@code enabled}，flag 关闭时
     * tryReactiveCompact 返回 null（对齐 CC flag 关闭模块为 null）。
     *
     * @param tokenCounter   TokenCounter bean（FIX-R11-3，TokenEstimator 口径）
     * @param featureFlags   FeatureFlags bean（{@code FeatureFlags.FeatureFlagsConfig}）
     */
    @Bean
    public com.nexusai.application.agent.compact.ReactiveCompactor reactiveCompactor(
            TokenCounter tokenCounter,
            com.nexusai.application.agent.loop.FeatureFlags featureFlags,
            StreamCompactSummary streamCompactSummary,
            com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        com.nexusai.application.agent.compact.ReactiveCompactor rc =
            new com.nexusai.application.agent.compact.ReactiveCompactor(tokenCounter, streamCompactSummary);
        rc.setEnabled(featureFlags != null && featureFlags.reactiveCompact());
        // [V52 B1-6] DB settings.reactive_compact_enabled / disable_compact 实时读源注入
        //   （isReactiveCompactEnabled 内部 DB 有值覆盖 FeatureFlags，null 回落）
        rc.setSettingsResolver(settingsResolver);
        log.info("IMP-02 D-27: 注册 ReactiveCompactor → AgentLoopContext 注入 (REACTIVE_COMPACT={}, CompactCallback={})",
            featureFlags != null && featureFlags.reactiveCompact(),
            streamCompactSummary != null ? "StreamCompactSummary" : "null");
        return rc;
    }

    // ════════════════════════════════════════════════════════════════════
    // R1 · /compact 生产 slash command 注册（UserInputDispatcher registerSlashCommandResult）
    // ════════════════════════════════════════════════════════════════════

    /**
     * R1: /compact 生产注册（副作用 bean）· 对齐 CC commands.ts:267/653（compact 命令注册）。
     *
     * <p><b>WHY</b>: {@link UserInputDispatcher#registerSlashCommandResult} 生产接线缺线
     * （原 registerSlashCommand void 仅测试可达）→ 前端 /compact 输入经拦截器 local 分支
     * （SlashCommandInterceptor local → dispatchResult）回传 skip、对用户静默。本 bean 在
     * Spring context refresh 时执行注册副作用：调用 {@code registerSlashCommandResult("compact", ...)}
     * 并把 handler 接线到 {@link CompactCommand#call}（对齐 CC commands/compact/index.ts 命令定义 +
     * compact.ts:40 {@code call(args, context)}），displayText 回传 text 结果
     * （<local-command-stdout> 落库 + 推 message.user）。
     *
     * <p>返回标记 record 而非 void：Spring 不注册 void 返回值 bean，标记 record 使副作用
     * 在上下文刷新时必然执行（单例预实例化）。
     *
     * @param userInputDispatcher         slash 分发器（@Component 单例）
     * @param sessionAgentStateRegistry   会话 AgentState 注册表（handler 按 session 解析消息）
     * @param reactiveCompactor           reactive-only 压缩（IMP-02 D-27 bean）
     * @param streamCompactSummary        L4 摘要生产（compactConversation summaryProducer 来源）
     * @return 副作用标记 record（无状态，仅保证注册副作用执行）
     */
    @Bean
    public CompactCommandRegistration compactCommandRegistration(
            UserInputDispatcher userInputDispatcher,
            SessionAgentStateRegistry sessionAgentStateRegistry,
            ReactiveCompactor reactiveCompactor,
            StreamCompactSummary streamCompactSummary,
            @Autowired(required = false) com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService,
            @Autowired(required = false) com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine,
            @Autowired(required = false) com.nexusai.application.agent.skill.SkillCatalog skillCatalog,
            com.nexusai.infra.llm.LlmProviderFactory llmProviderFactory,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ModelMapper modelMapper,
            @Autowired(required = false) com.nexusai.repository.provider.mapper.ProviderMapper providerMapper,
            @Autowired(required = false) com.nexusai.domain.provider.ProviderService providerService,
            @Autowired(required = false) com.nexusai.application.agent.settings.storage.FileConfigStorage configStorage,
            @Autowired(required = false) com.nexusai.application.agent.telemetry.Telemetry telemetry,
            @Autowired(required = false) com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        // [RES-R4-1] manual /compact firstParty gate 接线：复用 buildForkSuppliers 单一来源解析
        // configSupplier（与 streamCompactSummary 同源，无第二份解析逻辑），gate 判定经共享
        // GlobalCacheScope 求值（REQ-R4-1 验收 2/4）。
        ForkSuppliers suppliers = buildForkSuppliers(
            llmProviderFactory, modelMapper, providerMapper, providerService, configStorage);
        registerCompactSlashCommand(userInputDispatcher, sessionAgentStateRegistry,
            reactiveCompactor, streamCompactSummary, sessionMemoryService, claudemdEngine, skillCatalog,
            suppliers.configSupplier(), telemetry, settingsResolver);
        return new CompactCommandRegistration();
    }

    /** 副作用 bean 标记 record · 使 {@link #compactCommandRegistration} 的注册副作用在 context refresh 时执行。 */
    public record CompactCommandRegistration() {}

    /**
     * 执行 /compact 生产注册 · 对齐 CC getCommands 静态注册（commands.ts:267 compact 在
     * COMMANDS 数组）+ BRIDGE_SAFE_COMMANDS（commands.ts:653，移动端安全命令）。
     *
     * <p>DISABLE_COMPACT 门控（compact/index.ts:9 {@code isEnabled: () => !isEnvTruthy(...)}）
     * 不在注册时过滤，而在 handler 调用时早退（注册但门控跳过执行）——Java
     * {@code registerSlashCommand} 是普通 map 写入，无法像 CC getCommands 那样过滤命令列表；
     * 门控置位时 handler 内 log.warn 显式说明而非静默（fail loud）。
     *
     * @param dispatcher    slash 分发器（null → 注册跳过并 log.warn）
     * @param sessionRegistry 会话 AgentState 注册表
     * @param reactiveCompactor reactive-only 压缩
     * @param streamCompactSummary L4 摘要生产
     * @param sessionMemoryService SM 优先压缩（IMP-M-P0-3 注入，null → 空指令 SM 分支跳过）
     */
    private void registerCompactSlashCommand(UserInputDispatcher dispatcher,
                                             SessionAgentStateRegistry sessionRegistry,
                                             ReactiveCompactor reactiveCompactor,
                                             StreamCompactSummary streamCompactSummary,
                                             com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService,
                                             com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine,
                                             com.nexusai.application.agent.skill.SkillCatalog skillCatalog,
                                             java.util.function.Supplier<com.nexusai.infra.llm.ProviderConfig> configSupplier,
                                             com.nexusai.application.agent.telemetry.Telemetry telemetry,
                                             com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        if (dispatcher == null) {
            log.warn("[R1] UserInputDispatcher 未注入，/compact 生产注册跳过");
            return;
        }
        // [Fix-P1 HIGH] /compact 迁移到 result handler：type=local 经拦截器 local 分支 →
        //   dispatchResult 回传 text（compact 的 displayText），ChatService 落库 + 推 <local-command-stdout>，
        //   修复 void handler 下 /compact 对用户静默（用户只见气泡 + 状态 idle、零输出）。
        dispatcher.registerSlashCommandResult("compact", args ->
            UserInputDispatcher.LocalCommandResult.text(
                handleCompactCommand(args, sessionRegistry, reactiveCompactor, streamCompactSummary,
                    sessionMemoryService, claudemdEngine, skillCatalog, configSupplier, telemetry,
                    settingsResolver)));
        log.info("[R1] /compact 已注册为生产 slash command · "
                + "对齐 CC commands.ts:267 COMMANDS + commands.ts:653 BRIDGE_SAFE_COMMANDS "
                + "（handler=CompactCommand.call，sessionRegistry={}，reactiveCompactor={}，streamCompactSummary={}，SM={}，"
                + "claudemdEngine={}，skillCatalog={}，configSupplier={}）",
            sessionRegistry != null, reactiveCompactor != null, streamCompactSummary != null,
            sessionMemoryService != null, claudemdEngine != null, skillCatalog != null,
            configSupplier != null);
    }

    /**
     * /compact handler 执行体 · 对齐 CC compact/index.ts:9 isEnabled 门控 + compact.ts:40
     * {@code call(args, context)}。
     *
     * <p>CC context 构造（Java 等价）：
     * <ol>
     *   <li>session 解析：RequestContext.sessionId（ChatService 已 set MDC，short 直键）
     *       → SessionAgentStateRegistry.get（[session-id-short] 不再 parseSessionUuid）</li>
     *   <li>AgentState 未注册 → log.warn fail loud，不静默当压缩成功</li>
     *   <li>构造 {@link CompactCommandContext}：messages=state.messages()，SM=sessionMemoryService
     *       （IMP-M-P0-3 注入，生产非 null → 空指令 SM 优先可达；null 时 null-safe 跳过），
     *       microCompactor=new MicroCompactor()，
     *       reactiveCompactor 注入，summaryProducer=StreamCompactSummary 包装（对齐
     *       AutoCompactor.prepareAutoContext:712-724）</li>
     *   <li>call(args, ctx) → 成功/失败中文日志（异常不外抛，dispatch 契约 Consumer 无法返回）</li>
     * </ol>
     *
     * @param args   命令参数（/compact 后文本，可能为空串）
     * @param sessionRegistry 会话 AgentState 注册表
     * @param reactiveCompactor reactive-only 压缩
     * @param streamCompactSummary L4 摘要生产
     * @param sessionMemoryService SM 优先压缩（IMP-M-P0-3 注入，null → 空指令 SM 分支跳过）
     * @param claudemdEngine   claudemd 引擎（manual cacheSafeParams 的 userContext.claudeMd 走
     *                         完整 getClaudeMds 链，对齐 LlmAgentLoop:2187；null → 单文件子集）
     * @param skillCatalog     skill 目录（manual defaultAssemble 的 session_guidance 子弹；
     *                         对齐 LlmAgentLoop.buildSystemPromptAssemblyInput:2079）
     */
    private String handleCompactCommand(String args,
                                        SessionAgentStateRegistry sessionRegistry,
                                        ReactiveCompactor reactiveCompactor,
                                        StreamCompactSummary streamCompactSummary,
                                        com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService,
                                        com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine,
                                        com.nexusai.application.agent.skill.SkillCatalog skillCatalog,
                                        java.util.function.Supplier<com.nexusai.infra.llm.ProviderConfig> configSupplier,
                                        com.nexusai.application.agent.telemetry.Telemetry telemetry,
                                        com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        // ── 1. DISABLE_COMPACT 门控（compact/index.ts:9 isEnabled）──
        // [V52 X1-2] DB settings.disable_compact 覆盖：env 仍优先（CC 一票否决），DB 有值补一票
        // （settingsResolver null 回落仅 env 判定，零行为变化）。
        if (isEnvTruthy(System.getenv("DISABLE_COMPACT"))
                || (settingsResolver != null && Boolean.TRUE.equals(settingsResolver.disableCompact()))) {
            log.warn("[R1] /compact 被 DISABLE_COMPACT 门控关闭，跳过执行（CC isEnabled 语义，compact/index.ts:9；"
                    + "env={}, db={}）",
                isEnvTruthy(System.getenv("DISABLE_COMPACT")),
                settingsResolver != null ? settingsResolver.disableCompact() : "null");
            return "/compact 被 DISABLE_COMPACT 门控关闭，未执行（env="
                + isEnvTruthy(System.getenv("DISABLE_COMPACT"))
                + ", db=" + (settingsResolver != null ? settingsResolver.disableCompact() : "null") + "）。";
        }
        // ── 2. session 解析（RequestContext MDC，ChatService 已 set）──
        String rawSessionId = RequestContext.sessionId();
        if (rawSessionId == null || rawSessionId.isBlank()) {
            log.warn("[R1] /compact 无法解析当前 session（RequestContext.sessionId 为空），跳过压缩");
            return "/compact 无法解析当前 session（无请求上下文）。";
        }
        // [session-id-short] rawSessionId 已 short 直键 registry（不再 parseSessionUuid）
        AgentState state = sessionRegistry == null ? null : sessionRegistry.get(rawSessionId);
        if (state == null) {
            log.warn("[R1] /compact 会话未注册 AgentState: sessionId={}（LlmAgentLoop 主会话入口才注册，"
                    + "LlmAgentLoop.java:1543）", rawSessionId);
            return "/compact 会话未注册 AgentState（无进行中循环）。";
        }
        String sessionId = state.sessionId() != null ? state.sessionId() : rawSessionId;
        String agentId = state.agentId() != null ? state.agentId().toString() : null;
        if (state.messages() == null) {
            log.warn("[R1] /compact 会话消息为空（state.messages()=null）: sessionId={}，跳过压缩", sessionId);
            return "/compact 会话消息为空。";
        }
        // ── 3. 构造 CompactCommandContext + 调用（compact.ts:40 call）──
        // IMP-M-P0-3: ctx.sessionMemoryService() 非 null（SM @Bean 注入）→ 空指令 SM 优先可达
        // [RES-R1] manual fork 缓存共享通道（OPD-SP-24）: 复用主线程一致的 ToolUseContext
        // （state.currentToolUseContext()）+ 会话级 system prompt 组装链（sessionStartDate +
        // claudemdEngine + systemPromptSectionCache）构建 CacheSafeParams 原料 → CompactCommand
        // 传统路径压缩前 CacheSafeParamsHolder.save（对齐 CC compact.ts:101-108 getCacheSharingParams）。
        // [RES-R4-1] firstParty gate 接线（REQ-R4-1 验收 2）: 经共享 GlobalCacheScope 单实现求值
        // （对齐 CC shouldUseGlobalCacheScope() betas.ts:227-233 + LlmAgentLoop:2144-2154 语义），
        // configSupplier 与 streamCompactSummary 同源（buildForkSuppliers），无第二份解析逻辑。
        boolean useGlobalCacheScope = com.nexusai.application.agent.compact.fork.GlobalCacheScope
            .shouldUseGlobalCacheScope(configSupplier == null ? null : configSupplier.get());
        // [RES-C2] R5-4 注销通道（Java 内部卫生，非 CC 对齐项）：manual /compact 每次新建 provider
        //   → 压缩结束 finally close()（register/unregister 成对，CACHE_CLEAR_HOOKS 不再随 /compact
        //   次数有界累积）。成功/业务失败/异常三路均注销（close 幂等）。
        SystemPromptContextProvider manualProvider =
            buildManualSystemPromptCtxProvider(state, claudemdEngine);
        CompactCommand.CompactCommandContext ctx = buildCompactCommandContext(
            state.messages(), sessionId, agentId, reactiveCompactor, streamCompactSummary,
            sessionMemoryService,
            state.currentToolUseContext(),
            manualProvider,
            buildManualDefaultSysPromptAssemble(state, state.currentToolUseContext(), skillCatalog),
            state.systemPrompt(),
            state.appendSystemPrompt(),            // [RES-SP31] 接线：manual /compact fork 缓存共享 append 恒末尾
            useGlobalCacheScope,                   // [RES-R4-1] firstParty gate（REQ-R4-3 与主线程同一判定）
            telemetry);                            // [IMP-CM-17] tengu_compact 结构化遥测接线
        // [IMP2-17 △-7] 用户取消桥接：Java 用户取消是 AgentState.cancelled() 布尔信号
        // （AgentState:947，对齐 CC abortController 的入口注释），run 级 AbortController 未
        // 接线 state.cancel → 此处补桥：会话已取消 → 命令级取消信号立即置位，
        // 'Compaction canceled.' 分支生产可达（compact.ts:126-127）。
        if (state.cancelled() && !ctx.abortController().isCancelled()) {
            ctx.abortController().abort("user_cancelled");
        }
        // [RV-E-01 GAP-01 manual] 显式注入会话 AgentState 注册表 → CompactConversation 静态 holder
        //   （幂等，对齐 auto 路径 CompactConversation 注入对称性）：使 invoked_skills 重注入
        //   （compactConversation step 10 populateInvokedSkillsAttachment）经 sessionId 解析主
        //   AgentState 生产可达（对齐 CC 全局 STATE 读侧语义）。
        CompactCommand.setSessionAgentStateRegistry(sessionRegistry);
        // [MG-2 · IMP-BACK-3] manual /compact STOMP 推送上下文注册（decisions-log §32 触发点 1/2）：
        //   auto 路径由 LlmAgentLoop.run() 入口注册（LlmAgentLoop:1774-1788，sender=wsTemplate.
        //   convertAndSend("/topic/sessions/{S}/token-warning", TokenWarning)）；manual /compact 经
        //   UserInputDispatcher dispatch 在独立请求线程执行（非 LlmAgentLoop 线程）→ 无推送上下文 →
        //   CompactCommand 成功收尾链 3 个 suppressCompactWarning 调用点（:251 SM/:287 传统/:427
        //   reactive）STOMP no-op。此处按 LlmAgentLoop 同构补注册 + finally 清除（register/clear
        //   成对，ThreadLocal 防串台）。wsTemplate 未注入（非 STOMP 路径/直构测试）→ 不注册，
        //   推送安全跳过（store + 订阅者行为不回归）。
        CompactWarningState.SessionPushContext tokenWarningPushCtx = null;
        if (wsTemplate != null) {
            // [session-id-short] pushSessionId 已 short 直键，/topic/sessions/{sess-xxx}/token-warning
            // 与前端订阅一致（不再 parseSessionUuid 派生 UUID 段）
            String pushSessionId = state.sessionId() != null ? state.sessionId() : rawSessionId;
            if (pushSessionId != null) {
                tokenWarningPushCtx = new CompactWarningState.SessionPushContext(
                    pushSessionId,
                    warning -> wsTemplate.convertAndSend(
                        "/topic/sessions/" + pushSessionId + "/token-warning", warning));
                CompactWarningState.registerPushContext(tokenWarningPushCtx);
            }
        }
        try {
            CompactCommand.CompactCommandResult result = CompactCommand.call(args, ctx);
            log.info("[R1] /compact 压缩成功: session={} displayText={}",
                sessionId, result.displayText());
            // [Fix-P1 HIGH] displayText 作为 result 回传 → 拦截器 local 分支组装
            //   <local-command-stdout> 落库 + 推 message.user（CC local text 分支等价）。
            return (result.displayText() != null && !result.displayText().isBlank())
                ? result.displayText()
                : "/compact 压缩完成（无 displayText）。";
        } catch (IllegalArgumentException e) {
            log.warn("[R1] /compact 压缩失败（业务错误）: session={} error={}", sessionId, e.getMessage());
            return "/compact 压缩失败（业务错误）: " + e.getMessage();
        } catch (Exception e) {
            log.error("[R1] /compact 压缩异常: session={} error={}", sessionId, e.toString(), e);
            return "/compact 压缩异常: "
                + (e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            // [MG-2 · IMP-BACK-3] 推送上下文清除（register 成对，对齐 LlmAgentLoop:1786-1788）
            if (tokenWarningPushCtx != null) {
                CompactWarningState.clearPushContext();
            }
            // [RES-C2] R5-4：manual provider 生命周期终结（close 幂等）
            manualProvider.close();
        }
    }

    /**
     * 构造 /compact 命令上下文 · 供 {@link #handleCompactCommand} 与测试复用。
     *
     * <p>IMP-M-P0-3: SM 注入（production bean 非 null → ctx.sessionMemoryService() 非 null，
     * 空指令 SM 优先路径可达；测试可注入 mock/临时目录实例断言非 null）。
     *
     * <p>[RES-R1] cacheSafeParams 通道（CC getCacheSharingParams compact.ts:250-287）:
     * toolUseContext/sysPromptCtxProvider/defaultSysPromptAssemble/customSystemPrompt 四原料
     * 由调用方注入（生产 = handleCompactCommand 从 AgentState + beans 构建；测试可注入假实现）。
     * toolUseContext 或 sysPromptCtxProvider 为 null → CompactCommand 跳过 fork 缓存共享
     * （不阻断压缩，缓存共享为优化项）。
     *
     * @param messages            会话消息（boundary 剥离在 CompactCommand 内做）
     * @param sessionId           会话 ID
     * @param agentId             agent ID
     * @param reactiveCompactor   reactive-only 压缩（可为 null）
     * @param streamCompactSummary L4 摘要生产（可为 null → buildCompactConversationContext null-safe）
     * @param sessionMemoryService SM 优先压缩（可为 null → 空指令 SM 分支跳过）
     * @param toolUseContext      会话工具使用上下文（CC compact.ts:285；null → 跳过 fork 缓存共享）
     * @param sysPromptCtxProvider 会话级 system/user 上下文提供者（CC compact.ts:277）
     * @param defaultSysPromptAssemble default system prompt 惰性组装（CC compact.ts:261-263；
     *                             custom 非空时不被调用）
     * @param customSystemPrompt  自定义 system prompt（CC compact.ts:269；Java 取 state.systemPrompt()）
     * @param appendSystemPrompt  用户追加指令（CC compact.ts:274；Java 取 state.appendSystemPrompt()，
     *                            OPD-SP-31 接线）
     * @param useGlobalCacheScope firstParty fork 缓存共享 gate（REQ-R4-3；CC original:
     *                            shouldUseGlobalCacheScope() betas.ts:227-233）
     * @return CompactCommandContext（compact.ts:40 call 输入）
     */
    CompactCommand.CompactCommandContext buildCompactCommandContext(
            List<com.nexusai.model.session.dto.ChatMessageDto> messages,
            String sessionId,
            String agentId,
            ReactiveCompactor reactiveCompactor,
            StreamCompactSummary streamCompactSummary,
            com.nexusai.application.agent.memory.SessionMemoryService sessionMemoryService,
            ToolUseContext toolUseContext,
            SystemPromptContextProvider sysPromptCtxProvider,
            Supplier<SystemPrompt> defaultSysPromptAssemble,
            String customSystemPrompt,
            String appendSystemPrompt,
            boolean useGlobalCacheScope,
            com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        // [IMP2-17 △-7] 生产 AbortController 接线：不复用断开 new AbortController()（恒未取消 →
        // abort 分支生产不可达）。改复用会话 run 级 live 取消信号（toolUseContext.abortController()，
        // CC context.abortController 等价，Tool.ts:180 透传链）——权限拒绝/兄弟工具错误/流
        // fallback 级联取消均可达；toolUseContext 缺失（测试直构/未运行会话）回落独立控制器
        // （原行为）。
        AbortController abortController = toolUseContext != null && toolUseContext.abortController() != null
            ? toolUseContext.abortController()
            : new AbortController();
        return new CompactCommand.CompactCommandContext(
            messages, sessionId, agentId, "compact", false, abortController,
            sessionMemoryService, new MicroCompactor(), reactiveCompactor,
            () -> buildCompactConversationContext(sessionId, agentId, streamCompactSummary, toolUseContext, telemetry),
            notifyCompactionRunnable(agentId), clearUserContextCacheRunnable(),
            toolUseContext, sysPromptCtxProvider, defaultSysPromptAssemble, customSystemPrompt,
            appendSystemPrompt, useGlobalCacheScope,
            // [SM-10] notifyCompaction 门控（DRIFT-9 影响面）· CC compact.ts:67-72
            //   feature('PROMPT_CACHE_BREAK_DETECTION') —— 从 FeatureFlags 单源接线
            () -> featureFlags != null && featureFlags.promptCacheBreakDetection());
    }

    /**
     * notifyCompaction 真实接线 · 对齐 CC compact.ts:68-72
     * {@code if (feature('PROMPT_CACHE_BREAK_DETECTION')) { notifyCompaction(
     * context.options.querySource ?? 'compact', context.agentId) }}。
     *
     * <p><b>IMP2-02（S-9/△-2，P0）</b>: 旧实现注入 {@code () -> {}} no-op → 压缩后 cache-read
     * 基线不复位 → 下轮 LLM turn 消息数下降被误报为 cache break（或命中陈旧指令/记忆）。
     * 现改为 {@link PromptCacheBreakDetection#notifyCompaction} 真实接线：经
     * {@code gatedBy(featureFlags)}（MicroCompactor 同模式，V2-S5）——feature 关（默认）→
     * 实例 enabled=false → 内部 no-op；feature 开 → 真实重置 prevCacheReadTokens
     * （promptCacheBreakDetection.ts:689-698）。querySource 恒 "compact"（Java ctx 构造，
     * 对齐 CC {@code options.querySource ?? 'compact'}）。
     *
     * @param agentId CC context.agentId（compact.ts:70）
     * @return CC notifyCompaction Runnable（feature 门控在调用时求值）
     */
    private Runnable notifyCompactionRunnable(String agentId) {
        return () -> {
            if (log.isDebugEnabled()) {
                log.debug("[R1] notifyCompaction 触发: querySource=compact agentId={} feature={} · CC compact.ts:67-72",
                    agentId, featureFlags != null && featureFlags.promptCacheBreakDetection());
            }
            com.nexusai.application.agent.lsp.PromptCacheBreakDetection.gatedBy(featureFlags)
                .notifyCompaction("compact", agentId);
        };
    }

    /**
     * clearUserContextCache 真实接线 · 对齐 CC compact.ts:63/117/203
     * {@code getUserContext.cache.clear?.()}。
     *
     * <p><b>IMP2-02（△-2，P0）</b>: 旧实现注入 {@code () -> {}} no-op → 下轮 LLM turn 可能命中
     * 陈旧指令/记忆。现改为 {@link SystemPromptInjection#clearUserOnlyProviderCaches()}（Java
     * getUserContext.cache.clear 等价，FIX-CL：仅清已注册 provider 的 user 上下文缓存，
     * 与 {@code PostCompactCleanup} 序列内操作同实现；[merge 适配 2026-08-14] 清理面收敛为
     * user-only 通道，SP-07 △-6：CC postCompactCleanup.ts:51-60 只清 getUserContext.cache，
     * 不清 systemContext/gitStatus）。
     *
     * @return CC getUserContext.cache.clear Runnable
     */
    private Runnable clearUserContextCacheRunnable() {
        return () -> {
            // [merge 适配 2026-08-14] 清理面收敛为 user-only 通道（SP-07 △-6：CC
            //   postCompactCleanup.ts:51-60 只清 getUserContext.cache，不清 systemContext/gitStatus）
            //   —— 与 runPostCompactCleanup 内部一致，避免显式 clear 与序列内双通道不一致。
            int cleared = com.nexusai.application.agent.prompt.SystemPromptInjection.clearUserOnlyProviderCaches();
            if (log.isDebugEnabled()) {
                log.debug("[R1] clearUserContextCache 执行: 清空 {} 个 provider 的 user 上下文缓存 · CC getUserContext.cache.clear",
                    cleared);
            }
        };
    }

    /**
     * 构建 manual 压缩的系统/user 上下文提供者 · 对齐 LlmAgentLoop:2184-2188 会话级
     * SystemPromptContextProvider 构造（sessionStartDate + UserContextProvider(claudemdEngine)
     * + GitStatusProvider）。CC original: {@code getUserContext()}/{@code getSystemContext()}
     * （compact.ts:277）。
     *
     * @param state          会话状态（sessionStartDate 冻结日期）
     * @param claudemdEngine claudemd 引擎（null → UserContextProvider 回退单文件子集）
     * @return 会话级 SystemPromptContextProvider（同一会话内 memoize gitStatus/claudeMd/currentDate）
     */
    private SystemPromptContextProvider buildManualSystemPromptCtxProvider(
            AgentState state,
            com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine) {
        return new SystemPromptContextProvider(
            state.sessionStartDate(),
            new UserContextProvider(claudemdEngine),
            new GitStatusProvider());
    }

    /**
     * 构建 manual 压缩的 default system prompt 惰性组装 · 对齐 LlmAgentLoop
     * buildSystemPromptAssemblyInput:2069-2107 的输入组装（enabledTools 从 per-turn TUC 派生、
     * skillCommands 从 SkillCatalog 派生），SystemPromptAssembler 挂会话级
     * systemPromptSectionCache（对齐 LlmAgentLoop:2192-2195）。CC original:
     * {@code getSystemPrompt(tools, model, dirs, mcpClients)}（compact.ts:261-263）。
     *
     * <p><b>best-effort（已知偏差登记）</b>: manual 分发线程无 params.modelName()/memoryStorage，
     * model/language/memoryLoader/outputStyleConfig/mcpClients 传 null/空（对齐 Java 主循环 3P
     * 默认）→ default 组装产物与主循环在 intro model 名 / memory section 可能差字节 → fork cache
     * 前缀轻微偏移（缓存未命中但功能正确，不阻断压缩）。custom system prompt 非空时本 Supplier
     * 不被调用（I-13 短路）。
     *
     * @param state         会话状态（systemPromptSectionCache）
     * @param tuc           per-turn ToolUseContext（enabledTools 源，CC compact.ts:285 context）
     * @param skillCatalog  skill 目录（session_guidance 子弹源；null → 空）
     * @return default 组装惰性入口（custom 短路时不触发）
     */
    private Supplier<SystemPrompt> buildManualDefaultSysPromptAssemble(
            AgentState state,
            ToolUseContext tuc,
            com.nexusai.application.agent.skill.SkillCatalog skillCatalog) {
        final java.util.Set<String> enabledTools = (tuc != null && tuc.availableTools() != null)
            ? tuc.availableTools().stream().map(Tool::name).collect(java.util.stream.Collectors.toSet())
            : java.util.Set.of();
        final java.util.List<String> skillCommands;
        if (skillCatalog != null && skillCatalog.getModelInvocableCommands() != null) {
            skillCommands = skillCatalog.getModelInvocableCommands().stream()
                .map(com.nexusai.model.command.Command::getName)
                .collect(java.util.stream.Collectors.toList());
        } else {
            skillCommands = java.util.List.of();
        }
        final SystemPromptAssembler assembler = new SystemPromptAssembler(state.systemPromptSectionCache());
        return () -> assembler.assemble(new SystemPromptAssemblyInput(
            enabledTools,
            null,              // model（manual 线程无 params.modelName() · best-effort）
            java.util.List.of(),  // additionalWorkingDirs（Java 主循环单工作目录）
            java.util.List.of(),  // mcpClients（Java loop 无 McpClientInfo 通道）
            null,                 // outputStyleConfig（Java 无输出风格配置注入）
            skillCommands,
            null,                 // language（Java 无语言设置通道）
            null,                 // memoryLoader（manual 线程无 memoryStorage 通道 · best-effort）
            false,                // tokenBudgetEnabled（manual 线程无 TOKEN_BUDGET flag 通道 · 对齐 CC prompts.ts:538 关时恒不注册）
            state.sessionId()));  // [cwd-session 2026-08-25 修复] env_info_simple 会话 cwd（显式传 sessionId，绕 MDC）
    }

    /**
     * 构造 compactConversation 上下文 · 对齐 AutoCompactor.prepareAutoContext:712-724 的
     * summaryProducer 接线模式（StreamCompactSummary 包装为 SummaryProducer）。
     *
     * <p>model 不设（compactConversation 不读 getModel，CompactConversation.java grep 自验；
     * 3×delta 的 modelSupportsToolReference gate 以 ctx.model=null → 假定支持回落，见
     * PostCompactAttachmentRestorer.modelSupportsToolReference）；
     * notifyCompaction 用 no-op（CC PROMPT_CACHE_BREAK_DETECTION feature 默认关闭，
     * AutoCompactor.prepareAutoContext 同设 no-op）。
     *
     * <p><b>[IMP2-03]</b> 附件生产接线（✗-1..✗-4）：manual /compact 路径经
     * {@code PostCompactAttachmentRestorer.populatePostCompactAttachments} 填充
     * async-agent/plan/plan_mode 附件（数据源 = taskFrameworkService/planProvider 字段 +
     * toolUseContext）；3×delta 由 {@code PostCompactAttachmentRestorer.restore} 尾部
     * 重宣布（对齐 CC compact.ts:545-585）。
     *
     * @param sessionId 会话 ID
     * @param agentId   agent ID
     * @param streamCompactSummary L4 摘要生产（@Bean required 注入，生产恒非 null）
     * @return CompactConversationContext（summaryProducer 已接线）
     */
    private CompactConversationContext buildCompactConversationContext(String sessionId,
                                                                       String agentId,
                                                                       StreamCompactSummary streamCompactSummary,
                                                                       ToolUseContext toolUseContext,
                                                                       com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        CompactConversationContext cc = new CompactConversationContext();
        cc.setSessionId(sessionId);
        cc.setAgentId(agentId);
        cc.setQuerySource("compact");
        // [IMP-CM-17] tengu_compact 结构化遥测接线（compact.ts:650-695）：manual /compact 成功路径
        //   经 compactConversation 发射全字段事件。telemetry 未注入 → 事件静默跳过（零行为变化）。
        cc.setTelemetry(telemetry);
        // [IMP2-03] 附件生产接线（✗-1..✗-4，INV-15）：工具使用上下文承载 delta/plan_mode
        // 数据源（tools/mcpClients/permissionMode），async-agent/plan/plan_mode 附件经
        // PostCompactAttachmentRestorer.populatePostCompactAttachments 填充
        // additionalPostCompactAttachments（CC compact.ts:545-560）；3×delta 在 restore()
        // 尾部重宣布（compact.ts:563-585）。
        // [RV-E-01 GAP-03 manual] ToolUseContext 接线进 ctx（对齐 CC compact.ts:285 context 持有
        //   toolUseContext）——使 isInPlanMode() 读真实 plan mode → plan_mode 附件生产可达
        //   （null 保护安全降级，与 auto 路径对称）。
        if (toolUseContext != null) {
            cc.setToolUseContext(toolUseContext);
        }
        PostCompactAttachmentRestorer.populatePostCompactAttachments(cc, taskFrameworkService, planProvider);
        if (streamCompactSummary != null) {
            cc.setSummaryProducer((messagesToSummarize, compactPrompt, preCompactTokenCount) -> {
                try {
                    // [IMP-CM-14 F02] 透传 StreamCompactSummary.summarize 返回的 SummaryResult
                    //   （text + 压缩 API 真实 usage）——旧实现丢弃 usage 改包 new SummaryResult(text, null)
                    //   使 postCompactTokenCount/compactionInputTokens 恒 null/0（f4/f5 根因之一）。
                    return streamCompactSummary.summarize(compactPrompt, messagesToSummarize);
                } catch (Exception e) {
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
            });
        } else {
            // 生产恒非 null（@Bean required 注入）；缺省 fail loud —— compactConversation.java:225
            // getSummaryProducer().summarize 不可为 null，否则传统压缩路径 NPE
            cc.setSummaryProducer((messagesToSummarize, compactPrompt, preCompactTokenCount) -> {
                throw new IllegalStateException("StreamCompactSummary 未注入，无法生产压缩摘要");
            });
        }
        return cc;
    }

    /** CC isEnvTruthy · 薄委托 {@link TaskSystemConfig#isEnvTruthy(String)}（truthy 四值 {1,true,yes,on} + isBlank）。
     *  消除第 N 份私有拷贝漂移（旧三值缺 on），对齐 EnableLspToolCondition/MemoryBareModeConfig 复用先例。 */
    static boolean isEnvTruthy(String value) {
        return TaskSystemConfig.isEnvTruthy(value);
    }

    /**
     * [IMP-02 D-27] 注册 ContextCollapse 薄门面（CONTEXT_COLLAPSE）· 对齐 CC query.ts:1086-1117。
     *
     * <p><b>WHY</b>: 同 ReactiveCompactor——生产恒 null 使 collapse drain / applyCollapsesIfNeeded
     * 不可达（D-27 W1）。flag 关闭时 {@code isContextCollapseEnabled()=false}，所有方法 0 命中
     * （对齐 CC flag 关闭模块为 null）。
     *
     * <p>GR-3: drain 逻辑自旧编排器迁入本类（L2 Snip），构造不再依赖外部压缩组件。
     *
     * @param featureFlags    FeatureFlags bean
     */
    @Bean
    public com.nexusai.application.agent.loop.ContextCollapse contextCollapse(
            com.nexusai.application.agent.loop.FeatureFlags featureFlags,
            com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver) {
        log.info("IMP-02 D-27: 注册 ContextCollapse → AgentLoopContext 注入 (CONTEXT_COLLAPSE={})",
            featureFlags != null && featureFlags.contextCollapse());
        com.nexusai.application.agent.loop.ContextCollapse bean = new com.nexusai.application.agent.loop.ContextCollapse(
            featureFlags != null ? featureFlags : com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED);
        // [V52 B1-6] DB settings.context_collapse_enabled 实时读源注入（null = 回落 FeatureFlags）
        bean.setSettingsResolver(settingsResolver);
        return bean;
    }

    /**
     * 注册提示词对齐门控实时读源单例 · [prompt-align G0-03] 供后续批次 A/G 提示词装配链
     * （UP/CTX 域）注入。
     *
     * <p>SettingsMapper 经 {@code @Autowired(required=false) setSettingsMapper} 自动注入
     * （SettingsMapper 为 MyBatis-Flex mapper @Component）；无 Spring 上下文 / mapper 缺失
     * 时回落 null（各消费方零行为变化）。不含会话级 3 列（loop_mode_override /
     * non_interactive_session / auto_mode_enabled 走 sessions 会话列，SessionRecord）。
     */
    @Bean
    public PromptAlignSettingsResolver promptAlignSettingsResolver() {
        return new PromptAlignSettingsResolver();
    }

    /**
     * 注册分类器 CLAUDE.md 内容读源 Supplier · [prompt-align TOOLS-01] 镜像 CC
     * {@code getCachedClaudeMdContent}（bootstrap/state.ts 缓存，context.ts 填充；
     * yoloClassifier.ts:453-459 注释语义）。
     *
     * <p>Java 分类器侧无 UserContextProvider bean（其为 plain class，new 构造），
     * 此处以 {@code @Bean Supplier<String>} 承载，YoloClassifierImpl
     * {@code @Autowired(required=false)} 字段注入。读 {@code UserContextProvider.claudeMd()}
     * （经 ClaudemdEngine 完整 getClaudeMds 链或单文件子集；禁用/缺失 → null → 分类器
     * 无前缀，同 CC pre-PR 缓存未填充行为）。
     *
     * @param claudemdEngine claudemd 引擎（null → UserContextProvider 回退单文件子集；
     *                       claudemdEngine @Bean 同文件 :1172，无循环依赖）
     * @return CLAUDE.md 内容供应商（惰性，每次分类器调用求值）
     */
    @Bean
    public java.util.function.Supplier<String> claudeMdContentSupplier(
            com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine) {
        com.nexusai.application.agent.prompt.UserContextProvider provider =
            new com.nexusai.application.agent.prompt.UserContextProvider(claudemdEngine);
        return provider::claudeMd;
    }
}
