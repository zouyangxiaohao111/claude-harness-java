package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.CommandLifecycleNotifier;
import com.nexusai.application.agent.agent.CwdResolution;
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
 * + override 事件通道），Subagent/Hook 经 {@link #shared(String)} 构造隔离 ctx。无 carrier 引用。
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

    /**
     * [fail-loud] 「模型未配置/查不到窗口 → 用默认值」的告警去重集合（每 model 只 warn 一次）。
     * 该解析器被阈值/blocking/展示多路复用，每 turn 调用多次，不去重会刷屏。
     */
    private final java.util.Set<String> unconfiguredWindowWarned =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** null 模型名在 {@link #unconfiguredWindowWarned} 中的占位键（ConcurrentHashMap 不允许 null 键）。 */
    private static final String NULL_MODEL_KEY = "<null>";

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
     * 按模型解析上下文窗口（DB model 元数据优先，DB {@code models.max_context_tokens}）。
     *
     * <p><b>「未配置 / 查不到」的回落 = {@link CompactConstants#CONTEXT_WINDOW_UNCONFIGURED_DEFAULT}
     * （1_048_576 = 1M）</b>，与前端契约「留空=1M」一致（2026-09-11 起；此前回落 CC 的 200_000，
     * 即 2026-09-11 事故路径 —— {@code models.deepseek-flash.max_context_tokens=NULL} → 窗口 20 万
     * → 阈值 179_000 → 会话在 92.6 万 tokens 就被压缩）。
     *
     * <p><b>[fail-loud]</b>：走到「未配置窗口 → 用默认值」这条路会打一条 WARN（含 model 名 +
     * 所用默认值 + 具体原因），每 model 只打一次（防每 turn 刷屏）。四种触发情形：
     * <ol>
     *   <li>{@code model} 为空 / mapper 未注入（无 DB 上下文）</li>
     *   <li>{@code ModelNameResolver.resolve} 返回 null（模型不存在 / 未命中）</li>
     *   <li>{@code max_context_tokens} 为 NULL（<b>本次事故情形</b>）或 ≤ 0</li>
     *   <li>解析抛异常</li>
     * </ol>
     *
     * @param model 模型名（全名/裸名；可 null）
     * @return DB 配置窗口（&gt; 0）或未配置默认值（恒 &gt; 0）
     */
    private int resolveModelContextWindow(String model) {
        if (model == null || model.isBlank() || modelMapper == null || providerMapper == null) {
            warnUnconfiguredWindow(model, "model 为空或 DB mapper 不可用（modelMapper="
                + (modelMapper != null) + ", providerMapper=" + (providerMapper != null) + "）");
            return CompactConstants.CONTEXT_WINDOW_UNCONFIGURED_DEFAULT;
        }
        try {
            // W1-2: 统一走全名解析器（providerName/modelName 联合查, 无 / 回退按 name 查第一条）
            ModelRecord modelRecord = ModelNameResolver.resolve(modelMapper, providerMapper, model);
            // W2-1: 模型级窗口优先（models.max_context_tokens）——provider 级不再读取（探查确认死源）
            if (modelRecord != null && modelRecord.getMaxContextTokens() != null
                    && modelRecord.getMaxContextTokens() > 0) {
                return modelRecord.getMaxContextTokens();
            }
            warnUnconfiguredWindow(model, modelRecord == null
                ? "ModelNameResolver 未命中该模型"
                : "max_context_tokens = " + modelRecord.getMaxContextTokens() + "（NULL/≤0）");
        } catch (Exception e) {
            warnUnconfiguredWindow(model, "窗口解析异常: " + e);
        }
        return CompactConstants.CONTEXT_WINDOW_UNCONFIGURED_DEFAULT;
    }

    /**
     * [fail-loud] 「模型未配置/查不到上下文窗口 → 使用默认值」告警（每 model 仅一次，
     * 防阈值/blocking 预检每 turn 多次调用刷屏）。
     *
     * @param model  模型名（可 null）
     * @param reason 具体原因（写入日志，便于定位是 NULL / 未命中 / mapper 缺失）
     */
    private void warnUnconfiguredWindow(String model, String reason) {
        String key = model != null ? model : NULL_MODEL_KEY;
        if (unconfiguredWindowWarned.add(key)) {
            log.warn("[IMP-06] 模型 {} 未配置/查不到上下文窗口（{}）→ 使用「未配置窗口」默认值 {}"
                    + "（前端契约 留空=1M，models.max_context_tokens）；如需其它窗口请显式配置",
                model, reason, CompactConstants.CONTEXT_WINDOW_UNCONFIGURED_DEFAULT);
        }
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
        // [批 3c] sessionId 传 null：本重载无会话形参（其会话值由调用方以 projectRoot 显式承载；
        //   projectRoot 非空时根本不走会话回落分支，见 freshSession）。
        return build(null, null, null, freshSession(projectRoot, null), null);
    }

    public AgentLoopContext forSession(String streamTopic, String streamSessionId, String streamUserMessageId) {
        // [批 3c] 会话标识取本方法已有的显式形参 streamSessionId（原经裸 MDC 会话槽在
        //   freshSession → resolveFallbackWorkspaceDir 内取，已改为形参穿透）。
        return build(streamTopic, streamSessionId, streamUserMessageId, freshSession(null, streamSessionId), null);
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
            // [批 3c] sessionId = streamSessionId（build 调用链上已有的显式会话来源）。
            session = freshSession(null, streamSessionId);
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

    /**
     * 全新会话级状态 · taskService / workspaceDir 注入（对齐 LlmAgentLoop 实例字段）。
     *
     * @param projectRoot 会话 projectRoot（非空 → 直接作为 workspaceDir；null/空白 → 走末级兜底
     *                    {@link #resolveFallbackWorkspaceDir(String)}）
     * @param sessionId   会话 ID（[批 3c] 显式会话来源；仅末级兜底分支消费，null = 无会话 →
     *                    兜底走无会话命名出口（进程 user.dir））
     */
    private AgentLoopContext.LoopSessionState freshSession(String projectRoot, String sessionId) {
        AgentLoopContext.LoopSessionState session = new AgentLoopContext.LoopSessionState();
        session.setTaskService(taskService);
        if (projectRoot != null && !projectRoot.isBlank()) {
            // [IMP-D F4/M-07] 子代理/Hook 会话级 workspaceDir = 会话 projectRoot
            //   （修 M-07 user.dir 兜底链：子代理 STOP hook transcript_path 指向 P/<session>/...）。
            session.setWorkspaceDir(Path.of(projectRoot));
        } else {
            // [批 P21 2026-09-16] 末级兜底锚 = 稳定会话项目根（CwdResolution.getProjectRoot，
            //   CC getProjectRoot() state.ts:498-508）；⛔ 原取 getOriginalCwdLayer 是 F1
            //   （CC gh-30217）同型 —— 该层 L1 被 EnterWorktreeTool 重锚，进 worktree 会漂移。
            //   [批 3c] sessionId 由本方法形参显式传入（原裸 MDC 会话槽读点已废），
            //   null（无会话）→ getProjectRoot 走无会话命名出口 = 进程 user.dir（零行为变化）。
            session.setWorkspaceDir(workspaceDir != null ? workspaceDir
                : Path.of(resolveFallbackWorkspaceDir(sessionId)));
        }
        return session;
    }

    /**
     * workspaceDir 末级兜底 · 锚 <b>稳定会话项目根</b>
     * （{@link CwdResolution#getProjectRoot(String)}，对齐 CC {@code getProjectRoot()}
     * {@code bootstrap/state.ts:498-508}）。
     *
     * <p><b>WHY 必须是稳定锚（⛔ 不是 {@code getOriginalCwdLayer}）</b>：本方法产出的
     * {@code workspaceDir} 被其<b>全部消费点</b>当作<b>会话绑定项目根</b>使用 —— transcript /
     * tool-results / content-replacement / per-project 记忆目录 / subagent transcript 载荷都经
     * {@code SessionStorage.getProjectDir(workspaceDir)} 派生一次（见 {@code SessionStorage:143}）。
     * 若取 {@link CwdResolution#getOriginalCwdLayer(String)}，其 L1 槽
     * （{@code SessionCwdHolder.getOriginalCwd}）会被 {@code EnterWorktreeTool.applySessionCwd}
     * <b>重锚</b> ⇒ worktree 会话的兜底值随「进入 worktree」漂移 = <b>与 F1（CC gh-30217）同型</b>
     * （批 P13 已就 transcript 存储根修过同一根因，本处是同一根因在 {@code workspaceDir} 兜底上的
     * 另一实例）。原 javadoc 以「对齐 CC getOriginalCwd()（subagent/hook transcript 锚）」为由解释
     * 本取值 —— <b>该理由已不成立</b>：transcript 锚已迁至稳定槽
     * （{@code SessionStorage.sessionProjectRoot}），本兜底与 transcript 锚无隶属关系。
     *
     * <p>[批 3c] sessionId 由 {@link #freshSession(String, String)} 调用点显式传入（build 链上的
     * {@code streamSessionId} / forSession 形参；shared() 无会话 → null）——原裸 MDC 会话槽读点已废。
     *
     * <p>⚠️ <b>[批 P21 实测] 可达性</b>：生产可达的调用形态<b>只有 {@code sessionId == null}</b>
     * 一种 —— {@link #shared(String)} 硬编码传 null，而会传非 null sessionId 的两条路径
     * （3 参 {@link #forSession(String, String, String)} / {@link #build} 收到 null session）经全仓
     * grep <b>无生产调用方</b>。故本行与 {@code AgentLoopContext.resolveDefaultWorkspaceDir()}
     * （同取无会话命名出口）在<b>可达路径上取值相同</b>；改用 {@code getProjectRoot} 是
     * <b>零行为变化</b>地消除「sessionId 一旦成为真值就锚进 worktree」的潜伏缺陷，
     * 同时保住「有会话却解析不出项目根 ⇒ fail-loud」语义（⛔ 不用无会话出口
     * {@code getOriginalCwdLayerForNonSession()} 冒充项目根 —— {@code CwdResolution:514}
     * 明文禁止「有 sessionId 的调用方」用它绕开 fail-loud）。
     *
     * @param sessionId 会话 ID（null = 无会话 → {@code getProjectRoot} 走无会话命名出口 = 进程 user.dir）
     */
    private static String resolveFallbackWorkspaceDir(String sessionId) {
        String projectRoot = CwdResolution.getProjectRoot(sessionId);
        return projectRoot != null && !projectRoot.isBlank() ? projectRoot
            : System.getProperty("user.dir", ".");
    }
}
