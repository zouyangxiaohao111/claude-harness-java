package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.CommandLifecycleNotifier;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.api.PromptSuggestion;
import com.nexusai.application.agent.api.SpeculationEngine;
import com.nexusai.application.agent.compact.CompactConstants;
import com.nexusai.application.agent.compact.CompactThresholdSystem;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.diff.TraceRecorder;
import com.nexusai.application.agent.memory.AutoDreamConsolidator;
import com.nexusai.application.agent.memory.ExtractMemoriesAgent;
import com.nexusai.application.agent.memory.MemoryPrefetcher;
import com.nexusai.application.agent.memory.MemoryStorage;
import com.nexusai.application.agent.permission.BashClassifierFeature;
import com.nexusai.application.agent.permission.InputSanitizer;
import com.nexusai.application.agent.permission.PermissionContextBuilder;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.query.ToolUseSummaryGenerator;
import com.nexusai.application.agent.recovery.MaxTokensHandler;
import com.nexusai.application.agent.recovery.TransientErrorHandler;
import com.nexusai.application.agent.skill.SkillCatalog;
import com.nexusai.application.agent.skill.SkillChangeDetector;
import com.nexusai.application.agent.skill.SkillDiscoveryPrefetch;
import com.nexusai.application.agent.skillsearch.SkillSearchPrefetch;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * [H7-arch Phase 5-2 P3-②] {@link AgentLoopContext} 共享工厂 · @Component。
 *
 * <p><b>WHY 存在</b>: 主/Subagent/Hook 三路 deps 均需构造各自 {@link AgentLoopContext}。
 * P3-③ 后主循环 {@code LlmAgentLoop.run()} 调 {@link #forSession}（5 参重载携带会话级可变状态
 * + override 事件通道），Subagent/Hook 经 {@link #shared()} 构造隔离 ctx。无 carrier 引用。
 *
 * <p><b>注入策略</b>: 全部 {@code @Autowired(required=false)}（对齐 LlmAgentLoop 容错模式；
 * 与 LlmAgentLoop 的注入清单同源，二者共享同一批 bean 实例，无行为漂移）。[IMP-02 D-27]
 * ReactiveCompactor / ContextCollapse / FeatureFlags 已注册 @Bean（ToolRegistrationConfig /
 * FeatureFlags.FeatureFlagsConfig），生产注入非 null；SkillDiscoveryPrefetch /
 * ToolUseSummaryGenerator / TraceRecorder 等未注册 bean 类型注入不到 → 保留 null
 * （对齐 AgentLoopContext 可空语义，空值保护生效）。
 *
 * <p><b>会话状态</b>: 每次 {@code shared()} / {@code forSession(...)} 创建全新
 * {@link AgentLoopContext.LoopSessionState}（每 session 独立 per-run 状态，对齐 CC 各
 * agent 各自 sentSkillNames / taskSummary 时间门控等模块级状态）。主循环 run() 经 5 参重载
 * 传入实例引用共享的 sessionState（contentReplacementState rehydrate 等必须同对象可见）。
 */
@Component
public class AgentLoopContextFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopContextFactory.class);

    // ── 原 32 组件基础设施 bean（全部 required=false）──
    @Autowired(required = false) private ToolRegistry toolRegistry;
    @Autowired(required = false) private HookRegistry hookRegistry;
    @Autowired(required = false) private McpServerService mcpServerService;
    @Autowired(required = false) private NotificationQueue notificationQueue;
    @Autowired(required = false) private CommandLifecycleNotifier commandLifecycleNotifier;
    /** [OPD-TS-22 · WF3-01] SDK 事件队列（TaskConfiguration 单例 bean · turn 顶部 drain 出站） */
    @Autowired(required = false) private com.nexusai.application.agent.tasks.SdkEventQueue sdkEventQueue;
    /** [queue-first] 排队出站事件发布器（mid-turn drain 消费 busy-queued 后推 queue.drained） */
    @Autowired(required = false) private QueueEventPublisher queueEventPublisher;
    // [V-TOK] 模型计费纯函数（DeepSeek 双档 · 元/百万 tokens）· 注入 ctx 供 static loop() 累计 cost
    @Autowired(required = false) private com.nexusai.application.agent.cost.ModelCostCalculator modelCostCalculator;
    @Autowired(required = false) private SkillCatalog skillCatalog;
    @Autowired(required = false) private MemoryPrefetcher memoryPrefetcher;
    @Autowired(required = false) private MemoryStorage memoryStorage;
    @Autowired(required = false) private TokenBudgetChecker tokenBudgetChecker;
    @Autowired(required = false) private QueryConfig queryConfig;
    @Autowired(required = false) private LlmProviderFactory llmProviderFactory;
    @Autowired(required = false) private TransientErrorHandler transientErrorHandler;
    @Autowired(required = false) private MaxTokensHandler maxTokensHandler;
    @Autowired(required = false) private ExtractMemoriesAgent extractMemoriesAgent;
    @Autowired(required = false) private AutoDreamConsolidator autoDreamConsolidator;
    @Autowired(required = false) private SimpMessagingTemplate wsTemplate;
    @Autowired(required = false) private FeatureFlags featureFlags = FeatureFlags.ALL_DISABLED;
    @Autowired(required = false) private ReactiveCompactor reactiveCompactor;
    @Autowired(required = false) private ContextCollapse contextCollapse;
    @Autowired(required = false) private SkillDiscoveryPrefetch skillDiscoveryPrefetch;
    // [C-30] skill-search 预取模块 · 并行 skillDiscoveryPrefetch 模式；CC 源缺失 + 无 bean → null
    @Autowired(required = false) private SkillSearchPrefetch skillSearchPrefetch;
    @Autowired(required = false) private com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine;
    @Autowired(required = false) private ToolUseSummaryGenerator toolUseSummaryGenerator;
    // [RV14B-WIRE-04] 共享配置解析器 · Haiku 站点解析真实 (config, providerType)（null → 站点 warn+skip）
    @Autowired(required = false) private com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver;

    // ── D5 4 组件成员 bean ──
    @Autowired(required = false) private Telemetry telemetry;
    @Autowired(required = false) private ToolPermissionGate permissionGate;
    @Autowired(required = false) private PermissionPipeline permissionPipeline;
    @Autowired(required = false) private PermissionPrompter permissionPrompter;
    @Autowired(required = false) private InputSanitizer inputSanitizer;
    @Autowired(required = false) private ToolInputValidator inputValidator;
    // ── [canUseTool v2] Ask 分发链三 handler · 生产接线 ──
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.CoordinatorPermissionHandler coordinatorPermissionHandler;
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.SwarmWorkerPermissionHandler swarmWorkerPermissionHandler;
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.InteractiveHandler interactivePermissionHandler;
    @Value("${nexusai.classifier.transcript.enabled:true}")
    private boolean transcriptClassifierEnabled;
    @Autowired(required = false) private com.nexusai.application.agent.permission.sandbox.SandboxManager sandboxManager;
    // [U6-A1] BASH_CLASSIFIER 特性开关 · 投机竞速门控 (透传 ToolExecutionBeans → gate fallback)
    @Autowired(required = false) private BashClassifierFeature bashClassifierFeature;
    @Autowired(required = false) private TokenEstimator tokenEstimator;
    @Autowired(required = false) private ModelMapper modelMapper;
    @Autowired(required = false) private ProviderMapper providerMapper;
    @Autowired(required = false) private CompactThresholdSystem compactThresholdSystem;
    @Autowired(required = false) private ApplicationEventPublisher eventPublisher;
    @Autowired(required = false) private TraceRecorder traceRecorder;
    @Autowired(required = false) private PermissionContextBuilder permissionContextBuilder;
    @Autowired(required = false) private TaskService taskService;
    @Autowired(required = false) private Path workspaceDir;

    // [IMP-GP-03 · OPD-WF7-JS-03] promptSuggestion/speculation 生产接线（SpeculationEngine 非死代码）。
    //   懒加载单例（工厂为 @Component 单例）；enabledSupplier = env 门控（CC stopHooks.ts:136-140
    //   CLAUDE_CODE_ENABLE_PROMPT_SUGGESTION + speculation.ts:337-343 USER_TYPE==='ant'）。
    private volatile PromptSuggestion promptSuggestionBean;

    /** 懒加载 PromptSuggestion 生产 bean（含 SpeculationEngine 协作方）。 */
    private PromptSuggestion promptSuggestionBean() {
        if (promptSuggestionBean == null) {
            synchronized (this) {
                if (promptSuggestionBean == null) {
                    SpeculationEngine speculationEngine = new SpeculationEngine(
                        SpeculationEngine::isEnabledViaEnv,
                        this::runSpeculationFork,
                        inMemorySpeculationStore(),
                        this::logSuggestionTelemetry);
                    promptSuggestionBean = new PromptSuggestion(
                        PromptSuggestion::isEnabledViaEnv,
                        this::runPromptSuggestionFork,
                        this::logSuggestionTelemetry,
                        speculationEngine);
                    log.info("[IMP-GP-03] promptSuggestion/speculation 生产 bean 已接线"
                        + "（enabled=env 门控 · CC stopHooks.ts:136-140 + speculation.ts:337-343）");
                }
            }
        }
        return promptSuggestionBean;
    }

    /**
     * [IMP-GP-03] prompt suggestion fork agent · 对齐 CC runForkedAgent（promptSuggestion.ts:319-330，
     * 真实 LLM fork，工具全 deny）。Java 用 haiku 静态 chat 等价；provider 未接线 / 解析失败 → 空结果
     * （CC runForkedAgent 失败静默，suggestion null → tryGenerateSuggestion 'empty' 抑制）。
     */
    private PromptSuggestion.ForkResult runPromptSuggestionFork(
            String prompt, java.util.Map<String, Object> params, Object signal) {
        try {
            String output = runHaikuFork(prompt, params);
            if (output == null || output.isBlank()) {
                return new PromptSuggestion.ForkResult("", false, null);
            }
            return new PromptSuggestion.ForkResult(output.trim(), false, null);
        } catch (Exception ex) {
            log.warn("AgentLoopContextFactory: prompt suggestion fork 失败(静默): {} · CC runForkedAgent 静默",
                ex.getMessage());
            return new PromptSuggestion.ForkResult("", false, null);
        }
    }

    /** [IMP-GP-03] speculation fork agent · 对齐 CC runForkedAgent（speculation.ts:457-656）。 */
    private SpeculationEngine.SpeculationResult runSpeculationFork(
            String prompt, SpeculationEngine.CacheSafeParams params, Object signal) {
        try {
            String output = runHaikuFork(prompt,
                java.util.Map.of("system_prompt", PromptSuggestion.SUGGESTION_PROMPT));
            if (output == null) {
                return new SpeculationEngine.SpeculationResult("", 0.0);
            }
            return new SpeculationEngine.SpeculationResult(output.trim(), 0.0);
        } catch (Exception ex) {
            log.warn("AgentLoopContextFactory: speculation fork 失败(静默): {}", ex.getMessage());
            return new SpeculationEngine.SpeculationResult("", 0.0);
        }
    }

    /** [IMP-GP-03] haiku 静态 chat · 对齐 AgentLoopContext.resolveHaikuModelConfig（fast 模型解析）。 */
    private String runHaikuFork(String userMessage, java.util.Map<String, Object> params) {
        if (llmProviderFactory == null || modelConfigResolver == null) {
            return null;
        }
        String modelName = modelConfigResolver.resolveFastModelName("claude-haiku-4-5-20251001");
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            return null;
        }
        Object sys = params != null ? params.get("system_prompt") : null;
        String system = sys != null ? sys.toString() : PromptSuggestion.SUGGESTION_PROMPT;
        return llmProviderFactory.getProvider(resolved.config(), resolved.providerType())
            .chat(resolved.config(), modelName, system, userMessage);
    }

    /** [IMP-GP-03] speculation 状态内存 store（web 后端无 AppState；进程级单例即可）。 */
    private SpeculationEngine.StateStore inMemorySpeculationStore() {
        return new SpeculationEngine.StateStore() {
            private SpeculationEngine.SpeculationState s = SpeculationEngine.SpeculationState.idle();
            public SpeculationEngine.SpeculationState get() { return s; }
            public void set(SpeculationEngine.SpeculationState v) { s = v; }
        };
    }

    /** [IMP-GP-03] telemetry adapter · 对齐 CC logEvent（promptSuggestion.ts:466 / speculation.ts:133）。 */
    private void logSuggestionTelemetry(String event, java.util.Map<String, Object> fields) {
        if (telemetry != null) {
            telemetry.recordEvent(event, fields);
        }
    }

    /** 测试 / 非 Spring 场景注入 LlmProviderFactory（生产由 Spring 字段注入）。 */
    public void setLlmProviderFactory(LlmProviderFactory llmProviderFactory) {
        this.llmProviderFactory = llmProviderFactory;
    }

    /**
     * [CRON-F7] 测试 / 非 Spring 场景注入 NotificationQueue（生产由 Spring @Autowired 字段注入，
     * :78）。LlmAgentLoop 不再持有 notificationQueue 实例字段（fallback ctx 装配为 null no-op），
     * 统一队列生产同构路径必须经本 factory 注入 · 对齐 CC messageQueueManager.ts enqueue/drain。
     */
    public void setNotificationQueue(com.nexusai.application.agent.tasks.NotificationQueue notificationQueue) {
        this.notificationQueue = notificationQueue;
    }

    /**
     * [mid-turn-align] 测试 / 非 Spring 场景注入 QueueEventPublisher（生产由 Spring @Autowired 字段
     * 注入，:89）。mid-turn 注入 busy-queued 后 emitDrained（前端排队框移除 + 保持现订阅）测试
     * 需经本 factory 注入 mock；非注入场景 ctx.queueEventPublisher()=null → 跳过出站（no-op）。
     */
    public void setQueueEventPublisher(com.nexusai.application.agent.tasks.QueueEventPublisher queueEventPublisher) {
        this.queueEventPublisher = queueEventPublisher;
    }

    /**
     * [IMP-06] 启动时给阈值体系注入 DB model 上下文窗口解析器 · 对齐 CC
     * {@code getContextWindowForModel} 的 model-aware 语义（provider.maxContextTokens，
     * 等价旧 {@code computeBudgetFromGates} 的窗口来源，OD-12 同源）。
     *
     * <p>shared CompactThresholdSystem bean 被 AutoCompactor 使用，
     * 此处一次性注入解析器（幂等），生产 blocking 预检与 auto 阈值共用该窗口来源。
     */
    @PostConstruct
    public void wireThresholdSystemResolver() {
        if (compactThresholdSystem != null) {
            compactThresholdSystem.setModelContextWindowResolver(this::resolveModelContextWindow);
            log.info("[IMP-06] CompactThresholdSystem 注入 DB model 窗口解析器 (modelMapper={} providerMapper={})",
                modelMapper != null, providerMapper != null);
        }
    }

    /**
     * 按模型解析上下文窗口（DB model 元数据优先）· 回落 CC 默认 200_000。
     */
    private int resolveModelContextWindow(String model) {
        if (model == null || model.isBlank() || modelMapper == null || providerMapper == null) {
            return CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;
        }
        try {
            // W1-2: 统一走全名解析器（providerName/modelName 联合查, 无 / 回退按 name 查第一条）
            ModelRecord modelRecord = ModelNameResolver.resolve(modelMapper, providerMapper, model);
            // W2-1: 模型级窗口优先（models.max_context_tokens）——provider 级不再读取（探查确认死源）
            if (modelRecord != null && modelRecord.getMaxContextTokens() != null) {
                return modelRecord.getMaxContextTokens();
            }
        } catch (Exception e) {
            log.warn("[IMP-06] model 上下文窗口解析失败, 回落默认: model={} err={}", model, e.toString());
        }
        return CompactConstants.MODEL_CONTEXT_WINDOW_DEFAULT;
    }

    /**
     * 共享 ctx · stream 三字段 null（Subagent/Hook 用）。
     *
     * <p>[IMP-D F4/M-07] 增加会话 projectRoot 参数：子代理/Hook 的
     * {@link AgentLoopContext.LoopSessionState#workspaceDir()} 注入会话 projectRoot（修 M-07
     * user.dir 兜底链 —— 子代理 STOP hook transcript_path 因此指向
     * {@code P/<session>/subagents/...} 而非 {@code user.dir/...}）。调用方传
     * {@code AutoMemPaths.currentSessionProjectRoot()}（spawn/hook 线程经 IMP-C/IMP-D 注入后
     * 可见会话值）；null = 无会话上下文 → freshSession 回落 workspaceDir bean ?? user.dir
     * （与 forSession 3 参同构，非 Spring 测试场景保持旧行为）。
     */
    public AgentLoopContext shared(String projectRoot) {
        return build(null, null, null, freshSession(projectRoot), null);
    }

    public AgentLoopContext forSession(String streamTopic, String streamSessionId, String streamUserMessageId) {
        return build(streamTopic, streamSessionId, streamUserMessageId, freshSession(null), null);
    }
    /**
     * 会话 ctx · 主循环 run() 专用 5 参重载。
     *
     * <p><b>WHY 需要 session + overridePublisher</b>:
     * <ul>
     *   <li>{@code session} —— LlmAgentLoop 实例的会话级可变状态（contentReplacementState /
     *       sentSkillNames / todoReminderConfig 等集合引用共享 + 标量拷贝），run() rehydrate 的
     *       contentReplacementState 必须在同对象上对 static 方法可见（等价 P3-② 前 toLoopContext）。</li>
     *   <li>{@code overridePublisher} —— VerifyChatController.setEventPublisher 注入的 override
     *       事件通道；不传则主 publisher null 时 publishEvent 丢失 override 通道（P3-① 风险）。</li>
     * </ul>
     */
    public AgentLoopContext forSession(String streamTopic, String streamSessionId, String streamUserMessageId,
            AgentLoopContext.LoopSessionState session, ApplicationEventPublisher overridePublisher) {
        return build(streamTopic, streamSessionId, streamUserMessageId, session, overridePublisher);
    }

    private AgentLoopContext build(String streamTopic, String streamSessionId, String streamUserMessageId,
            AgentLoopContext.LoopSessionState session, ApplicationEventPublisher overridePublisher) {
        if (session == null) {
            session = freshSession(null);
        }
        // [FIX-B3 SU-△-1] 生产接线（拍板#5）：注册 per-run sentSkillNames / suppressNextSkillListing
        // 到 SkillChangeDetector 静态注册表，使 skill 文件变更时 resetSentSkillNames()（reload() →
        // CC skillChangeDetector.ts:276 → attachments.ts:2612-2615 sentSkillNames.clear() + suppressNext=false）
        // 生产生效。接线点选 build()（而非仅 freshSession()）：它是 shared()/forSession 3 参
        // （freshSession 创建）与 forSession 5 参（主循环 LlmAgentLoop.buildSessionStateFromInstance
        // 直接传入、不经 freshSession）的单一汇聚点 —— 主代理 + subagent/hook 全部会话路径均注册，
        // 否则主代理 sentSkillNames 生产仍不被 reset（NG-1 只解一半）。
        SkillChangeDetector.registerSentSkillNames(session.sentSkillNames());
        SkillChangeDetector.registerSuppressNextSkillListing(session.suppressNextSkillListing());
        return new AgentLoopContext(
            toolRegistry, hookRegistry, mcpServerService,
            notificationQueue, commandLifecycleNotifier,
            skillCatalog, memoryPrefetcher, memoryStorage, tokenBudgetChecker,
            queryConfig, llmProviderFactory,
            transientErrorHandler, maxTokensHandler,
            extractMemoriesAgent, autoDreamConsolidator, wsTemplate, streamTopic,
            streamSessionId, streamUserMessageId,
            featureFlags != null ? featureFlags : FeatureFlags.ALL_DISABLED,
            reactiveCompactor, contextCollapse, skillDiscoveryPrefetch, skillSearchPrefetch,
            toolUseSummaryGenerator,
            new AgentLoopContext.ToolExecutionBeans(telemetry, permissionGate, permissionPipeline,
                permissionPrompter, inputSanitizer, inputValidator, transcriptClassifierEnabled,
                sandboxManager, coordinatorPermissionHandler,
                swarmWorkerPermissionHandler, interactivePermissionHandler, bashClassifierFeature),
            new AgentLoopContext.TokenBudgetBeans(tokenEstimator, modelMapper, providerMapper),
            new AgentLoopContext.EventBridge(eventPublisher, traceRecorder, overridePublisher),
            permissionContextBuilder,
            // [H6-FIX][IMP-GP-03] promptSuggestion 生产 bean（env 门控；null 兜底仅测试/非 Spring 场景）
            promptSuggestionBean(),
            session,
            claudemdEngine,
            modelConfigResolver,
            sdkEventQueue,
            queueEventPublisher,
            modelCostCalculator);
    }

    /** 全新会话级状态 · taskService / workspaceDir 注入（对齐 LlmAgentLoop 实例字段）。 */
    private AgentLoopContext.LoopSessionState freshSession(String projectRoot) {
        AgentLoopContext.LoopSessionState session = new AgentLoopContext.LoopSessionState();
        session.setTaskService(taskService);
        if (projectRoot != null && !projectRoot.isBlank()) {
            // [IMP-D F4/M-07] 子代理/Hook 会话级 workspaceDir = 会话 projectRoot
            //   （修 M-07 user.dir 兜底链：子代理 STOP hook transcript_path 指向 P/<session>/...）。
            session.setWorkspaceDir(Path.of(projectRoot));
        } else {
            // cwd-align-ext：末级兜底改走会话 originalCwd（CC getOriginalCwd() subagent transcript 锚）；
            //   无 sessionId 回落 user.dir（方案 1，零行为变化）。
            session.setWorkspaceDir(workspaceDir != null ? workspaceDir
                : Path.of(resolveFallbackWorkspaceDir()));
        }
        return session;
    }

    /**
     * workspaceDir 末级兜底 · 对齐 CC getOriginalCwd()（subagent/hook transcript 锚）。
     *
     * <p>freshSession 运行期经 RequestContext 取会话 originalCwd；无 sessionId 回落 user.dir
     * （方案 1，零行为变化）。
     */
    private static String resolveFallbackWorkspaceDir() {
        String cwd = CwdResolution.getOriginalCwdLayer(RequestContext.sessionId());
        return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
    }
}
