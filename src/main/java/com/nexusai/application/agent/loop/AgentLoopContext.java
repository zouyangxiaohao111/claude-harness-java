package com.nexusai.application.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.CommandLifecycleNotifier;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.CompactConstants;
import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactThresholdSystem;
import com.nexusai.application.agent.compact.PlanModeAttachments;
import com.nexusai.application.agent.compact.PlanProvider;
import com.nexusai.application.agent.compact.PlanProviderImpl;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.SnipCompactor;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.AgentEvent;
import com.nexusai.application.agent.diff.TraceEvent;
import com.nexusai.application.agent.diff.TraceRecorder;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.common.RequestContext;
import com.nexusai.application.agent.api.PromptSuggestion;
import com.nexusai.application.agent.memory.AutoDreamConsolidator;
import com.nexusai.application.agent.memory.ExtractMemoriesAgent;
import com.nexusai.application.agent.memory.MemoryPrefetcher;
import com.nexusai.application.agent.memory.MemoryStorage;
import com.nexusai.application.agent.permission.InputSanitizer;
import com.nexusai.application.agent.permission.PermissionContextBuilder;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolInputValidator;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.AsyncHookRegistry;
import com.nexusai.application.agent.permission.hook.HookJSONOutput;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.HookSpecificOutput;
import com.nexusai.application.agent.subagent.ForkSubagentMessages;
import com.nexusai.application.agent.subagent.createSubagentContext.AgentOptions;
import com.nexusai.application.agent.query.QueryConfig;
import com.nexusai.application.agent.query.TokenBudgetChecker;
import com.nexusai.application.agent.query.ToolUseSummaryGenerator;
import com.nexusai.application.agent.recovery.MaxTokensHandler;
import com.nexusai.application.agent.recovery.TransientErrorHandler;
import com.nexusai.application.agent.skill.SkillCatalog;
import com.nexusai.application.agent.skill.SkillDiscoveryPrefetch;
import com.nexusai.application.agent.skillsearch.SkillSearchPrefetch;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.application.agent.tasks.SdkEventQueue;
import com.nexusai.application.agent.tasks.Task;
import com.nexusai.application.agent.tasks.TaskService;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ContentReplacementState;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolDecisionInfo;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResultApplier;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider.ChatRequestOptions.ThinkingConfig;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelNameResolver;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * [H7-arch Phase 5 P1] loop 基础设施依赖容器 · 不可变 record。
 *
 * <p><b>WHY 存在</b>: 原 loop 主体通过 {@code carrier.xxx}（LlmAgentLoop 实例）访问
 * 基础设施 bean。Phase 5 目标 ②（deps getter 真抽象）+ ④（删 carrier 模型）要求 loop 成为
 * 纯函数——基础设施作为不可变 context 显式传入。本 record 收敛 26 个基础设施字段 +
 * 3 个 feature-gated 组件（{@link #reactiveCompactor()} / {@link #contextCollapse()} /
 * {@link #skillDiscoveryPrefetch()}）+ {@link #featureFlags()}，与
 * {@link LoopDeps}（窄 IO）+ {@link com.nexusai.application.agent.tool.ToolUseContext}
 * （per-call 隔离输入）构成三载体模型，对齐 CC {@code query.ts} 的
 * {@code params}（窄 deps 注入 + toolUseContext 隔离 + 模块级基础设施）。
 *
 * <p><b>[H7-arch Phase 5-2 P3 D5] 组件扩展 32 → 37</b>: 新增 4 个内聚组件（3 个嵌套 holder
 * record + {@link PermissionContextBuilder}）承载原 LlmAgentLoop 实例字段（供
 * {@link #buildStreamingExecutor} / {@link #handleToolCallsTurn} P3-⑤ 消费）+ 1 个
 * 会话级可变状态容器 {@link LoopSessionState}（18 个轻方法 static 化后 per-run 可变状态
 * 的唯一载体）。{@code toLoopContext()} 与 {@link AgentLoopContextFactory} 负责装配。
 *
 * <p><b>P3-① + P3-⑤ static 化</b>: 原 {@code Behaviors} 门面（已删除）全部方法提升为本 record 的
 * static 方法（首参 {@code AgentLoopContext ctx}），loop 主体直接调用。两个重方法
 * （buildStreamingExecutor / handleToolCallsTurn）经 P3-⑤ base-TUC 线程化后 static 化
 * （工具来源 = per-turn TUC 的 {@code availableTools()}）。
 *
 * <p><b>三载体分工</b>:
 * <ol>
 *   <li>{@link LoopDeps} — IO 行为（callModel/microcompact/autocompact/uuid + 事件发射），可注入 fake</li>
 *   <li>{@link AgentLoopContext} — 基础设施 bean 引用（共享），不可变</li>
 *   <li>{@link com.nexusai.application.agent.tool.ToolUseContext} — per-call 隔离输入
 *       （availableTools/abortController/queryTracking），已有</li>
 * </ol>
 *
 * <p><b>可空语义</b>: 基础设施字段可空，对齐 {@code @Autowired(required=false)}
 * 容错（无 bean 场景 loop 走老路径跳过）。唯一特例：{@link #sessionState()} 在 compact ctor
 * 中 null → 新建（等价每次 queryLoop 全新 per-run 会话状态，对齐 LlmAgentLoop 原型实例字段
 * 初始化——这是"不得静默 null 兜底"的文档化例外：提供等价初始状态而非置空）。
 */
public record AgentLoopContext(
        ToolRegistry toolRegistry,
        HookRegistry hookRegistry,
        McpServerService mcpServerService,
        NotificationQueue notificationQueue,
        CommandLifecycleNotifier commandLifecycleNotifier,
        SkillCatalog skillCatalog,
        MemoryPrefetcher memoryPrefetcher,
        MemoryStorage memoryStorage,
        TokenBudgetChecker tokenBudgetChecker,
        QueryConfig queryConfig,
        LlmProviderFactory llmProviderFactory,
        TransientErrorHandler transientErrorHandler,
        MaxTokensHandler maxTokensHandler,
        ExtractMemoriesAgent extractMemoriesAgent,
        AutoDreamConsolidator autoDreamConsolidator,
        SimpMessagingTemplate wsTemplate,
        String streamTopic,
        String streamSessionId,
        String streamUserMessageId,
        FeatureFlags featureFlags,
        ReactiveCompactor reactiveCompactor,
        ContextCollapse contextCollapse,
        SkillDiscoveryPrefetch skillDiscoveryPrefetch,
        // [C-30] skill-search 预取模块 · 并行 skillDiscoveryPrefetch 模式（未来结构归宿；CC 源缺失 → 生产无 bean）
        SkillSearchPrefetch skillSearchPrefetch,
        ToolUseSummaryGenerator toolUseSummaryGenerator,
        // [H7-arch Phase 5-2 P3 D5] 新增 5 组件（4 个 holder / builder + 1 个会话级状态容器）
        ToolExecutionBeans toolExecutionBeans,
        TokenBudgetBeans tokenBudgetBeans,
        EventBridge eventBridge,
        PermissionContextBuilder permissionContextBuilder,
        // [H6-FIX] PromptSuggestion 注入点（@Autowired(required=false) 模式，无 bean 时 null → stop 路径
        // executePromptSuggestion 显式跳过，不再用静态 no-op 假触发 —— CHANGELOG 0.2.29 H6-2）
        PromptSuggestion promptSuggestion,
        LoopSessionState sessionState,
        // [IMP-M-P2-4] claudemd 引擎（getClaudeMds · 对齐 CC claudemd.ts；FIX-CL 删 claudemd 侧 prepend
        // 双轨，前置渲染走本类 prependUserContext）。可空：null → loop 跳过 claudeMd 注入（无 bean 容错）。
        com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine,
        // [RV14B-WIRE-04] 共享配置解析器 · Haiku 站点解析真实 (config, providerType)。
        //   可空：测试 / 无 Spring 场景 → null → 站点 warn+skip（不落 mock）。
        com.nexusai.infra.llm.ModelConfigResolver modelConfigResolver,
        // [OPD-TS-22 · WF3-01] SDK 事件队列（进程级单例）· turn 顶部 drain 出站 /topic/tasks。
        //   可空：非 Spring fallback（buildMainLoopContext 用 32 参 compat ctor 置 null）→ 跳过 SDK 出站。
        SdkEventQueue sdkEventQueue,
        // [queue-first] 排队出站事件发布器 · mid-turn drain 消费 busy-queued 后推 queue.drained
        //   （前端排队框移除该行）。可空：非 Spring fallback（buildMainLoopContext 用 32 参 compat
        //   ctor 置 null）→ 跳过排队事件出站（CronIdleExecutor 路径经其自身字段注入，不受影响）。
        QueueEventPublisher queueEventPublisher,
        // [V-TOK] 模型计费纯函数（DeepSeek 双档 · 元/百万 tokens）· static loop() 每 message_delta
        //   经 ctx 取用折算 cost 进 AgentState 会话累计（LlmAgentLoop E2/E3）。可空：非 Spring
        //   fallback / 单测 → null → 仅累计 input tokens，cost/桶跳过。
        com.nexusai.application.agent.cost.ModelCostCalculator modelCostCalculator
) {
    private static final Logger log = LoggerFactory.getLogger(AgentLoopContext.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // ── 常量（原 LlmAgentLoop 私有常量复制，static 化后独立于实例）──
    private static final int DEFAULT_TOKEN_BUDGET = 180_000;
    private static final int ANT_TOKEN_BUDGET = 8_000;
    private static final int FALLBACK_TOKEN_BUDGET = 200_000;
    /** [H7-arch Phase 5 P4 C1] blocking-limit 缓冲 · 对齐 CC autoCompact.ts:65 MANUAL_COMPACT_BUFFER_TOKENS=3_000。 */
    private static final int MANUAL_COMPACT_BUFFER_TOKENS = 3_000;
    /** s05-P1-3: reminder 文本 · 逐字对齐 CC utils/messages.ts:3668。 */
    private static final String TODO_REMINDER_TEXT =
        "The TodoWrite tool hasn't been used recently. If you're working on tasks that would "
      + "benefit from tracking progress, consider using the TodoWrite tool to track progress. "
      + "Also consider cleaning up the todo list if has become stale and no longer matches what "
      + "you are working on. Only use it if it's relevant to the current work. This is just a "
      + "gentle reminder - ignore if not applicable. Make sure that you NEVER mention this "
      + "reminder to the user\n";

    // record compact ctor：唯一特殊处理是 sessionState null → 新建（文档化例外，见类 javadoc）。
    // 基础设施字段为单例引用，无需防御性 copy（record 本身不可变）。
    public AgentLoopContext {
        if (sessionState == null) {
            sessionState = new LoopSessionState();
        }
    }

    /**
     * [RV14B-WIRE-04] 32 参兼容构造器 · modelConfigResolver 默认 null（测试 / 非 Spring 场景 →
     * 站点 warn+skip 不落 mock）。生产经 {@link AgentLoopContextFactory} 用 34 参 canonical ctor 注入 resolver。
     */
    public AgentLoopContext(
            ToolRegistry toolRegistry,
            HookRegistry hookRegistry,
            McpServerService mcpServerService,
            NotificationQueue notificationQueue,
            CommandLifecycleNotifier commandLifecycleNotifier,
            SkillCatalog skillCatalog,
            MemoryPrefetcher memoryPrefetcher,
            MemoryStorage memoryStorage,
            TokenBudgetChecker tokenBudgetChecker,
            QueryConfig queryConfig,
            LlmProviderFactory llmProviderFactory,
            TransientErrorHandler transientErrorHandler,
            MaxTokensHandler maxTokensHandler,
            ExtractMemoriesAgent extractMemoriesAgent,
            AutoDreamConsolidator autoDreamConsolidator,
            SimpMessagingTemplate wsTemplate,
            String streamTopic,
            String streamSessionId,
            String streamUserMessageId,
            FeatureFlags featureFlags,
            ReactiveCompactor reactiveCompactor,
            ContextCollapse contextCollapse,
            SkillDiscoveryPrefetch skillDiscoveryPrefetch,
            SkillSearchPrefetch skillSearchPrefetch,
            ToolUseSummaryGenerator toolUseSummaryGenerator,
            ToolExecutionBeans toolExecutionBeans,
            TokenBudgetBeans tokenBudgetBeans,
            EventBridge eventBridge,
            PermissionContextBuilder permissionContextBuilder,
            PromptSuggestion promptSuggestion,
            LoopSessionState sessionState,
            com.nexusai.application.agent.context.ClaudemdEngine claudemdEngine) {
        this(toolRegistry, hookRegistry, mcpServerService,
            notificationQueue, commandLifecycleNotifier,
            skillCatalog, memoryPrefetcher, memoryStorage, tokenBudgetChecker,
            queryConfig, llmProviderFactory,
            transientErrorHandler, maxTokensHandler,
            extractMemoriesAgent, autoDreamConsolidator, wsTemplate, streamTopic,
            streamSessionId, streamUserMessageId,
            featureFlags, reactiveCompactor, contextCollapse, skillDiscoveryPrefetch,
            skillSearchPrefetch, toolUseSummaryGenerator,
            toolExecutionBeans, tokenBudgetBeans, eventBridge, permissionContextBuilder,
            promptSuggestion, sessionState, claudemdEngine,
            null, // modelConfigResolver（32 参 compat 不传 → null）
            null, // sdkEventQueue（32 参 compat 不传 → null · 非 Spring fallback 跳过 SDK 出站）
            null, // queueEventPublisher（32 参 compat 不传 → null · 非 Spring fallback 跳过排队事件出站）
            null); // modelCostCalculator（32 参 compat 不传 → null · 非 Spring 单测跳过计费累计）
    }
    // [IMP-15] max_output_tokens 接线 · 对齐 CC claude.ts getMaxOutputTokensForModel + query.ts max_output_tokens_escalate
    // ════════════════════════════════════════════════════════════════════

    /**
     * [IMP-15] 按模型解析 maxOutputTokens · 对齐 CC {@code services/api/claude.ts:3399-3419
     * getMaxOutputTokensForModel}（完整解析：模型族 default + tengu_otk_slot_v1 cap +
     * CLAUDE_CODE_MAX_OUTPUT_TOKENS env override）。
     *
     * <p>[G-18] <b>单源委托</b> {@link com.nexusai.infra.llm.AnthropicSdkProvider#resolveMaxOutputTokensForModel(String)}
     * （DB 优先 models.max_tokens → CC 家族表回落，请求体/压缩链/恢复错误信息同源）。
     * 供 loop 恢复层 / 测试统一取模型解析值，避免各侧自建简化表（D-29 双实现漂移防线）。
     *
     * @param model 模型名
     * @return max_tokens 默认值
     */
    public static int getMaxOutputTokensForModel(String model) {
        // [G-18] 单源：DB 优先 → CC 家族表回落（恢复错误信息需反映请求体实际下发的 max_tokens，
        //   buildMessageParams 现为 DB 优先，家族表仅回落）
        return com.nexusai.infra.llm.AnthropicSdkProvider.resolveMaxOutputTokensForModel(model);
    }

    /**
     * [IMP-15] max_output_tokens 恢复接线 · 计算下一轮请求的 max_tokens · 对齐 CC query.ts:1213
     * {@code maxOutputTokensOverride: ESCALATED_MAX_TOKENS}。
     *
     * <p><b>WHY</b>: 升级后（override 非 null，CC query.ts:1201 maxOutputTokensOverride !==
     * undefined），下一请求必须携带该 override 值（DRIFT-10：旧实现只置标志、provider body
     * 恒 4096，升级值从未到达 API）。本方法收敛"override 非 null 取 override / 否则按模型解析"
     * 的接线决策。
     *
     * <p><b>[ER-IMP-07 / DC-22]</b>：升级信号从 RecoveryState.hasEscalated 粘性字段改为
     * {@code maxOutputTokensOverride} 参数（CC query.ts:1201 override===undefined re-arm）。
     *
     * @param model                   当前模型名
     * @param maxOutputTokensOverride 上一请求携带的 max_tokens 覆盖（CC query.ts:1213；null=未升级按模型解析）
     * @return 下一请求的 max_tokens
     */
    public static int resolveRecoveryMaxTokens(String model, Integer maxOutputTokensOverride) {
        if (maxOutputTokensOverride != null) {
            if (log.isInfoEnabled()) {
                log.info("AgentLoopContext max_output_tokens 接线: override 已设 → 下一请求 max_tokens={} · CC query.ts:1213",
                    maxOutputTokensOverride);
            }
            return maxOutputTokensOverride;
        }
        return getMaxOutputTokensForModel(model);
    }

    // ════════════════════════════════════════════════════════════════════
    // [H7-arch Phase 5-2 P3 D5] 嵌套 holder record
    // ════════════════════════════════════════════════════════════════════

    /**
     * [D5] 工具执行 beans · 供 buildStreamingExecutor / handleToolCallsTurn 使用
     * （P3-⑤ 才消费；本任务仅装配进 ctx）。
     */
    public record ToolExecutionBeans(
            Telemetry telemetry,
            ToolPermissionGate permissionGate,
            PermissionPipeline permissionPipeline,
            PermissionPrompter permissionPrompter,
            InputSanitizer inputSanitizer,
            ToolInputValidator inputValidator,
            boolean transcriptClassifierEnabled,
            // [H8 v2 补全 H8-GAP-1] Bash sandbox 管理器 · 透传给 StreamingToolExecutor →
            //   HookPermissionResolver.checkRuleBasedPermissions 的 sandbox auto-allow (CC permissions.ts:1186-1205)
            com.nexusai.application.agent.permission.sandbox.SandboxManager sandboxManager,
            // [canUseTool v2] Ask 分发链三 handler · 生产接线 (gate createSpringBean 6 参重载)
            com.nexusai.application.agent.permission.CoordinatorPermissionHandler coordinatorHandler,
            com.nexusai.application.agent.permission.SwarmWorkerPermissionHandler swarmWorkerHandler,
            com.nexusai.application.agent.permission.InteractiveHandler interactiveHandler,
            // [U6-A1] BASH_CLASSIFIER 特性开关 · 投机竞速门控 (gate fallback 路径透传,
            //   feature 恒 false → 竞速恒跳过回落 interactive)
            com.nexusai.application.agent.permission.BashClassifierFeature bashClassifierFeature) {}

    /**
     * [D5] token 预算 beans · 供 estimateTurnTokens / estimateMessagesTokens /
     * computeBudgetFromGates 使用。ModelMapper/ProviderMapper 是 MyBatis mapper 接口。
     */
    public record TokenBudgetBeans(
            TokenEstimator tokenEstimator,
            ModelMapper modelMapper,
            ProviderMapper providerMapper) {}

    /**
     * [D5] 事件桥 · 供 publishEvent / traceEmit 使用。
     *
     * <p><b>override 通道</b>: {@link #overridePublisher()} 捕获 {@code LlmAgentLoop}
     * 的 {@code overrideEventPublisher}（VerifyChatController.setEventPublisher 注入）。
     * 由于 toLoopContext() 在 queryLoop 入口（run() 完成 setEventPublisher 之后）构建 ctx，
     * 捕获即生效，无需可变 setter（record 不可变）。主 publisher null 时回落 override。
     */
    public record EventBridge(
            ApplicationEventPublisher publisher,
            TraceRecorder traceRecorder,
            ApplicationEventPublisher overridePublisher) {}

    /**
     * [D5] loop 会话级可变状态容器 · 18 个轻方法 static 化后 per-run 可变状态的唯一载体。
     *
     * <p><b>WHY</b>: AgentLoopContext 是不可变 record（final 引用），但这些引用指向的对象
     * 本身可变（ConcurrentHashMap / AtomicBoolean / ContentReplacementState）。原 LlmAgentLoop
     * 原型实例字段（sentSkillNames / todoReminderCache /
     * contentReplacementState 等）经 {@code toLoopContext()} 注入本容器（集合引用共享，
     * 标量拷贝），使 static 方法可读/写 per-run 状态而无需 LlmAgentLoop 实例。
     *
     * <p><b>生命周期</b>: ctx 每次 queryLoop 调用创建一次（MainLoopDeps.context() →
     * toLoopContext()），等价 LlmAgentLoop 原型实例每 run 全新字段。P3-③ factory 接入后由
     * factory 每 session 创建全新实例。
     */
    public static final class LoopSessionState {

        /**
         * workspaceDir 默认解析 · 对齐 CC getOriginalCwd()（sessionStorage.ts:202-205 subagent
         * transcript 锚 getProjectDir(getOriginalCwd())）。
         *
         * <p>LoopSessionState 创建时经 RequestContext 取会话 originalCwd；无 sessionId 回落 user.dir
         * （方案 1，零行为变化）。
         */
        private static String resolveDefaultWorkspaceDir() {
            String cwd = CwdResolution.getOriginalCwdLayer(RequestContext.sessionId());
            return cwd != null && !cwd.isBlank() ? cwd : System.getProperty("user.dir", ".");
        }

        /** [P1-10] 已发送 skill name 集合（按 agentKey，空串=主线程）· 对齐 CC attachments.ts:2607 sentSkillNames。 */
        private ConcurrentHashMap<String, Set<String>> sentSkillNames = new ConcurrentHashMap<>();
        /** [P1-10] resume 时抑制下一次 skill_listing 注入 · 一次性，消费后自动 reset · 对齐 CC attachments.ts:2633。 */
        private AtomicBoolean suppressNextSkillListing = new AtomicBoolean(false);
        /** [R27-8] todo reminder 文本 memoization 缓存。 */
        private ConcurrentHashMap<String, String> todoReminderCache = new ConcurrentHashMap<>();
        /** s05-P1-3: todo reminder 配置（默认 CC 值 10/10，可配置）。 */
        private LlmAgentLoop.TodoReminderConfig todoReminderConfig = LlmAgentLoop.TodoReminderConfig.DEFAULT;
        /**
         * [P2] task reminder 遗留总开关 · 默认 false。CTX-02 起不再作为唯一门：实际门控 =
         * DB task_reminder_enabled（{@link #promptAlignSettingsResolver} 实时读源，null 回落
         * {@link TaskSystemConfig#isTodoV2Enabled()} 交互会话默认开；对齐 CC messages.ts:3681）。
         * 本字段保留仅为兼容 setEnableTaskReminder/初始化路径，注入侧已不消费（静态
         * maybeInjectTaskReminder/computeTaskReminderAttachments 均走 DB/fallback 门控公式）。
         */
        private boolean enableTaskReminder = false;
        /** [P2] task reminder 配置（默认 CC 值 10/10，可配置）。 */
        private LlmAgentLoop.TaskReminderConfig taskReminderConfig = LlmAgentLoop.TaskReminderConfig.DEFAULT;
        /** [P2] 自上次 task 管理调用起的 assistant turn 数（仅日志契约）。 */
        private int turnsSinceLastTaskManagement = 0;
        /** [P2] 自上次 task_reminder 注入起的 assistant turn 数（仅日志契约）。 */
        private int turnsSinceLastTaskReminder = 0;
        /** [P2] Task service（Spring bean · null 时 listTasks 降级空列表）。 */
        private TaskService taskService;
        /**
         * [prompt-align CTX-02] settings 门控实时读源（DB task_reminder_enabled）· 对齐 taskService
         * 字段范式（同 :398 邻域）。null = 无 resolver（非 Spring / 工厂未接线）→ task_reminder
         * 门控回落 {@link TaskSystemConfig#isTodoV2Enabled()}（MDC isInteractive 会话感知）。
         */
        private com.nexusai.application.agent.prompt.PromptAlignSettingsResolver promptAlignSettingsResolver;
        /** [R28-3] per-conversation content replacement state。 */
        private ContentReplacementState contentReplacementState = ContentReplacementState.create();
        /** workspace dir · 默认 user.dir；测试可覆盖。
         *
         *  <p>cwd-align-ext：默认取会话 originalCwd（CC subagent transcript 锚 getProjectDir(getOriginalCwd())
         *  sessionStorage.ts:202-205）；无 sessionId 回落 user.dir（方案 1，零行为变化）。 */
        private Path workspaceDir = Path.of(resolveDefaultWorkspaceDir());
        /** [cache-hit-fix B] 会话级 GitStatusProvider · CC context.ts:97 会话开始一次快照、会话内不更新。
         *  doRun 建 mainCtx 后经 SessionGitStatusRegistry 注入（跨 run 共享同一实例，system 尾字节稳定 →
         *  保护 deepseek 单前缀缓存）；null = 未注入（非 Spring / 无 sessionId）→ loop() 回落每 run new。 */
        private com.nexusai.application.agent.prompt.GitStatusProvider gitStatusProvider;
        /** [S05] 会话级 appState 读通道 · 对齐 CC Tool.ts:182 getAppState（LlmAgentLoop.getAppStateSnapshot 接线）。 */
        private java.util.function.Function<java.util.Map<String, Object>, java.util.Map<String, Object>> appStateReader;

        public ConcurrentHashMap<String, Set<String>> sentSkillNames() { return sentSkillNames; }
        public void setSentSkillNames(ConcurrentHashMap<String, Set<String>> v) { this.sentSkillNames = v; }
        public AtomicBoolean suppressNextSkillListing() { return suppressNextSkillListing; }
        public void setSuppressNextSkillListing(AtomicBoolean v) { this.suppressNextSkillListing = v; }
        public ConcurrentHashMap<String, String> todoReminderCache() { return todoReminderCache; }
        public void setTodoReminderCache(ConcurrentHashMap<String, String> v) { this.todoReminderCache = v; }
        public LlmAgentLoop.TodoReminderConfig todoReminderConfig() { return todoReminderConfig; }
        public void setTodoReminderConfig(LlmAgentLoop.TodoReminderConfig v) { this.todoReminderConfig = v; }
        public boolean enableTaskReminder() { return enableTaskReminder; }
        public void setEnableTaskReminder(boolean v) { this.enableTaskReminder = v; }
        public LlmAgentLoop.TaskReminderConfig taskReminderConfig() { return taskReminderConfig; }
        public void setTaskReminderConfig(LlmAgentLoop.TaskReminderConfig v) { this.taskReminderConfig = v; }
        public int turnsSinceLastTaskManagement() { return turnsSinceLastTaskManagement; }
        public void setTurnsSinceLastTaskManagement(int v) { this.turnsSinceLastTaskManagement = v; }
        public int turnsSinceLastTaskReminder() { return turnsSinceLastTaskReminder; }
        public void setTurnsSinceLastTaskReminder(int v) { this.turnsSinceLastTaskReminder = v; }
        public TaskService taskService() { return taskService; }
        public void setTaskService(TaskService v) { this.taskService = v; }
        public com.nexusai.application.agent.prompt.PromptAlignSettingsResolver promptAlignSettingsResolver() {
            return promptAlignSettingsResolver;
        }
        public void setPromptAlignSettingsResolver(
                com.nexusai.application.agent.prompt.PromptAlignSettingsResolver v) {
            this.promptAlignSettingsResolver = v;
        }
        public ContentReplacementState contentReplacementState() { return contentReplacementState; }
        public void setContentReplacementState(ContentReplacementState v) { this.contentReplacementState = v; }
        public Path workspaceDir() { return workspaceDir; }
        public void setWorkspaceDir(Path v) { this.workspaceDir = v; }
        public com.nexusai.application.agent.prompt.GitStatusProvider gitStatusProvider() { return gitStatusProvider; }
        public void setGitStatusProvider(com.nexusai.application.agent.prompt.GitStatusProvider v) { this.gitStatusProvider = v; }
        public java.util.function.Function<java.util.Map<String, Object>, java.util.Map<String, Object>> appStateReader() {
            return appStateReader;
        }
        public void setAppStateReader(java.util.function.Function<java.util.Map<String, Object>, java.util.Map<String, Object>> v) {
            this.appStateReader = v;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [H7-arch Phase 5-2 P3-①a] 18 个轻方法 static 化（原 Behaviors 委托 → static-with-ctx，Behaviors 已删）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 工具结果 per-message aggregate budget 检查 · static 化自 LlmAgentLoop#applyPerMessageBudget。
     * <p>实例依赖映射：contentReplacementState / workspaceDir → {@link LoopSessionState}。
     *
     * <p><b>OD-01 S4（B5）</b>: 聚合预算 = {@code getPerMessageBudgetLimit()} =
     * {@code MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = 200_000}（CC toolLimits.ts:49 ·
     * getPerMessageBudgetLimit toolResultStorage.ts:421-434）。per-tool 50_000 持久化阈值
     * （CC {@code DEFAULT_MAX_RESULT_SIZE_CHARS}，toolLimits.ts:13，单工具结果超阈值即 persist 到磁盘 +
     * preview 替换）由 per-tool 路径 {@code applyToolResultBudget}（本类 :1678）承担 ——
     * 两常量语义不同（OD-10 曾把聚合预算误设为 per-tool 50K，S4 B5 修正）。
     *
     * <p><b>OD-01 S4（B7）</b>: {@code skipToolNames} = 工具集里 {@code maxResultSizeChars} 非有限
     * （Infinity）的工具名集合（CC query.ts:389-393 options.tools filter !finite）。此类工具（Read）
     * 结果仅 markSeen（frozen 化）不落盘，不参与 freshSize（CC toolResultStorage.ts:816-823）。
     *
     * <p><b>OD-01 S4（③ gate）</b>: budgetAggregateGate（tengu_hawthorn_steeple）默认关，
     * 关时整段跳过 = no-op（CC query.ts:369-372 contentReplacementState=undefined）。
     * persist gate {@code LlmAgentLoop.shouldPersistReplacements}（REPL_MAIN_THREAD 前缀 /
     * AGENT_ 前缀才写 sessionStorage）属实。
     */
    public static void applyPerMessageBudget(AgentLoopContext ctx, AgentState state, QuerySource querySource,
            java.util.Set<String> skipToolNames) {
        if (state == null || state.messages() == null || state.messages().isEmpty()) return;
        // OD-01 S4 ③ gate：budgetAggregateGate（tengu_hawthorn_steeple）关 → 整段跳过
        // （CC query.ts:369-372 contentReplacementState=undefined 时 applyToolResultBudget no-op）
        if (ctx == null || ctx.featureFlags() == null || !ctx.featureFlags().budgetAggregateGate()) {
            if (log.isDebugEnabled()) {
                log.debug("[applyPerMessageBudget] budgetAggregateGate=false（tengu_hawthorn_steeple 关）跳过聚合预算"
                    + "（CC query.ts:369-372 contentReplacementState=undefined no-op）");
            }
            return;
        }
        // B5：聚合预算 = MAX_TOOL_RESULTS_PER_MESSAGE_CHARS = 200K（CC toolLimits.ts:49 · getPerMessageBudgetLimit）
        int limit = com.nexusai.application.agent.tool.ToolResultStorage.getPerMessageBudgetLimit();
        LoopSessionState session = ctx.sessionState();
        ContentReplacementState contentReplacementState = session.contentReplacementState();
        Path workspaceDir = session.workspaceDir();

        // B7: skipToolNames 非空时构建 tool_use_id→tool_name map · 对齐 CC buildToolNameMap
        //（toolResultStorage.ts:536-549）· 仅按需构建（skipToolNames.size > 0，CC :780-782）。
        java.util.Map<String, String> nameByToolUseId = (skipToolNames != null && !skipToolNames.isEmpty())
            ? buildToolNameMap(state.messages()) : null;

        // 1. 收集 group: 连续 tool message 视为同一 API-level user message group
        // [IMP-22/IMP-13] 统一宿主：ToolResultStorage.collectCandidatesByMessage（D-05 迁移去重 +
        // D-17 宿主迁回 CC 真源同名类 toolResultStorage.ts，3 处留 1）
        java.util.List<java.util.List<ChatMessageDto>> groups =
            com.nexusai.application.agent.tool.ToolResultStorage.collectCandidatesByMessage(state.messages());
        if (groups.isEmpty()) return;

        // 2. 累积 total size
        int totalSize = 0;
        for (java.util.List<ChatMessageDto> group : groups) {
            for (ChatMessageDto m : group) {
                if (m.content() != null) totalSize += m.content().length();
            }
        }
        if (totalSize <= limit) return;

        // 3. 处理每个 group
        for (java.util.List<ChatMessageDto> group : groups) {
            // 3a. partition: mustReapply / frozen / fresh
            java.util.List<ChatMessageDto> mustReapply = new java.util.ArrayList<>();
            java.util.List<ChatMessageDto> frozen = new java.util.ArrayList<>();
            java.util.List<ChatMessageDto> fresh = new java.util.ArrayList<>();
            for (ChatMessageDto m : group) {
                if (contentReplacementState.isSeen(m.toolCallId())) {
                    String cached = contentReplacementState.getReplacement(m.toolCallId());
                    if (cached != null) {
                        mustReapply.add(m);
                    } else {
                        frozen.add(m);
                    }
                } else {
                    fresh.add(m);
                }
            }
            if (fresh.isEmpty()) continue;  // 全部已 seen

            // B7: skipped（toolName ∈ skipToolNames，如 Read）→ 仅 markSeen（frozen 化），
            // eligible = fresh − skipped；freshSize 只计 eligible · CC toolResultStorage.ts:816-823。
            java.util.List<ChatMessageDto> skipped = new java.util.ArrayList<>();
            java.util.List<ChatMessageDto> eligible = new java.util.ArrayList<>();
            for (ChatMessageDto m : fresh) {
                String toolName = nameByToolUseId != null ? nameByToolUseId.get(m.toolCallId()) : null;
                if (toolName != null && skipToolNames.contains(toolName)) {
                    skipped.add(m);
                } else {
                    eligible.add(m);
                }
            }
            skipped.forEach(m -> contentReplacementState.markSeen(m.toolCallId()));

            // 3b. 计算 size（freshSize 只计 eligible）
            int frozenSize = sumSize(frozen);
            int freshSize = sumSize(eligible);
            if (frozenSize + freshSize <= limit) continue;

            // 3c. selectFreshToReplace(eligible,...): 按 size 降序，累加超过 limit 选最大
            java.util.List<ChatMessageDto> selected = selectFreshToReplace(eligible, frozenSize, limit);

            // 3d. 标记 seen（同步）—— selected 之外的 fresh + frozen 都标记 seen
            java.util.Set<String> selectedIds = new java.util.HashSet<>();
            for (ChatMessageDto m : selected) selectedIds.add(m.toolCallId());
            for (ChatMessageDto m : group) {
                if (!selectedIds.contains(m.toolCallId())) {
                    contentReplacementState.markSeen(m.toolCallId());
                }
            }

            // 3e. R28-3.10 同步 await persist（对齐 CC Promise.all await）+ 替换 ChatMessageDto
            for (ChatMessageDto m : selected) {
                String toolUseId = m.toolCallId();
                String content = m.content();
                if (toolUseId == null || content == null) continue;
                String sessionId = state.sessionId() != null ? state.sessionId() : "default";
                com.nexusai.application.agent.tool.ToolResultStorage.PersistedToolResult persisted;
                try {
                    persisted = com.nexusai.application.agent.tool.ToolResultStorage.persistToolResult(
                        workspaceDir, sessionId, content, toolUseId).join();
                } catch (Exception e) {
                    log.warn("[R28-3.10] per-message persist join failed: toolUseId={} error={}",
                        toolUseId, e.toString());
                    continue;
                }
                if (persisted == null) {
                    log.warn("[R28-3.10] per-message persistence failed for toolUseId={} — leaving full content",
                        toolUseId);
                    continue;
                }
                String preview = com.nexusai.application.agent.tool.ToolResultStorage
                    .buildLargeToolResultMessage(persisted);
                contentReplacementState.recordReplacement(toolUseId, preview);
                // R28-3.6: 同步持久化到 sessionStorage（querySource gate）
                if (com.nexusai.application.agent.LlmAgentLoop.shouldPersistReplacements(querySource)) {
                    String agentIdStr = state.agentId() != null ? state.agentId().toString() : null;
                    com.nexusai.application.agent.tool.SessionStorage.writeContentReplacement(
                        workspaceDir, sessionId, agentIdStr, toolUseId, preview);
                }
                // 替换 state.messages 中的对应 message（CC replaceToolResultContents 镜像）
                replaceMessageContent(state, toolUseId, preview);
                log.info("[R28-3.5] per-message budget: persisted toolUseId={} path={}",
                    toolUseId, persisted.filepath());
            }
            // 3f. mustReapply 部分：同步替换为 cached preview（保证 prompt cache 稳定）
            for (ChatMessageDto m : mustReapply) {
                String cached = contentReplacementState.getReplacement(m.toolCallId());
                if (cached != null && !cached.equals(m.content())) {
                    replaceMessageContent(state, m.toolCallId(), cached);
                }
            }
        }
    }

    /** 计算 messages 的 content length 总和（null-safe） */
    private static int sumSize(java.util.List<ChatMessageDto> messages) {
        int total = 0;
        for (ChatMessageDto m : messages) {
            if (m.content() != null) total += m.content().length();
        }
        return total;
    }

    /**
     * 从 assistant 消息 tool_use 块构建 tool_use_id→tool_name map · 对齐 CC buildToolNameMap
     * （toolResultStorage.ts:536-549）。tool_use 先于 tool_result，预算执行时名字必已知。
     * Java 等价：ChatMessageDto(Role.assistant).toolCalls() 的 ToolCallDto.id/name。
     */
    private static java.util.Map<String, String> buildToolNameMap(java.util.List<ChatMessageDto> messages) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (messages == null) return map;
        for (ChatMessageDto m : messages) {
            if (m.role() != Role.assistant || m.toolCalls() == null) continue;
            for (com.nexusai.model.session.dto.ToolCallDto tc : m.toolCalls()) {
                if (tc.id() != null) map.put(tc.id(), tc.name());
            }
        }
        return map;
    }

    /** 选最大 fresh 累积超过 limit (CC selectFreshToReplace 镜像)。 */
    private static java.util.List<ChatMessageDto> selectFreshToReplace(
            java.util.List<ChatMessageDto> fresh, int frozenSize, int limit) {
        java.util.List<ChatMessageDto> sorted = new java.util.ArrayList<>(fresh);
        sorted.sort((a, b) -> Integer.compare(
            b.content() != null ? b.content().length() : 0,
            a.content() != null ? a.content().length() : 0));
        java.util.List<ChatMessageDto> selected = new java.util.ArrayList<>();
        int remaining = frozenSize + sumSize(fresh);
        for (ChatMessageDto m : sorted) {
            if (remaining <= limit) break;
            selected.add(m);
            int size = m.content() != null ? m.content().length() : 0;
            remaining -= size;
        }
        return selected;
    }

    /** 替换 state.messages 中指定 toolUseId 对应 ChatMessageDto 的 content。 */
    private static void replaceMessageContent(AgentState state, String toolUseId, String newContent) {
        if (state == null || toolUseId == null || newContent == null) return;
        java.util.List<ChatMessageDto> mutable = new java.util.ArrayList<>(state.messages());
        boolean changed = false;
        for (int i = 0; i < mutable.size(); i++) {
            ChatMessageDto m = mutable.get(i);
            if (m.toolCallId() != null && m.toolCallId().equals(toolUseId)) {
                mutable.set(i, new ChatMessageDto(
                    m.id(), m.sessionId(), m.role(), m.author(),
                    newContent, m.reasoning(), m.toolCalls(), m.finishReason(),
                    m.inputTokens(), m.outputTokens(), m.time(), m.createdAt(),
                    m.toolCallId(), m.assistantMessageId(),
                    m.acceptFeedback(), m.contentBlocks(), m.imagePasteIds()));
                changed = true;
            }
        }
        if (changed) {
            state.replaceMessages(mutable);
        }
    }

    /**
     * [P1-10] 按 skill name 增量计算本次应注入的 skill_listing 增量 · 对齐 CC attachments.ts:2699-2730
     * ({@code getSkillListingAttachments})。替代旧 isSkillCatalogAlreadySent/markSkillCatalogSent 双方法
     * （C-8 双实现漂移：旧版存 catalogText.hashCode() + enableSkillDedup 开关；CC 语义是恒开 + 按 name）。
     *
     * <p>CC 语义（Read 自验 E4）:
     * <pre>
     * const agentKey = toolUseContext.agentId ?? ''                    // :2699
     * let sent = sentSkillNames.get(agentKey); if (!sent) {...set...}  // :2700-2704
     * if (suppressNext) { 全量标 sent; return [] }                      // :2709-2715
     * const newSkills = allCommands.filter(cmd => !sent.has(cmd.name)) // :2718
     * if (newSkills.length === 0) return []                            // :2720-2722
     * const isInitial = sent.size === 0                                // :2725
     * for (cmd of newSkills) sent.add(cmd.name)                        // :2727-2730
     * </pre>
     *
     * <p>恒开启：CC sentSkillNames 恒生效，无 enableSkillDedup 开关（X22/dedup 语义偏移根源已删）。
     * agentKey null → ""（主线程，CC {@code agentId ?? ''}），每 agent 各自独立 sent 集合。
     *
     * @param ctx      AgentLoopContext（经 sessionState() 读写 sentSkillNames / suppressNextSkillListing）
     * @param agentKey agent 标识（主线程传 null 或 ""；subagent 传其 agentId 字符串）
     * @param commands 全量候选技能命令（CC allCommands，按 name 去重）
     * @return SkillListingDelta（newSkills 增量子集 + isInitial 是否首注）；无增量 → 空 delta
     */
    public static SkillListingDelta computeSkillListingDelta(AgentLoopContext ctx, String agentKey,
                                                             java.util.List<com.nexusai.model.command.Command> commands) {
        String key = agentKey != null ? agentKey : "";  // CC: agentId ?? ''
        // get-or-create sent 集合（CC :2700-2704 sentSkillNames.get(agentKey) ?? new Set）
        java.util.Set<String> sent = ctx.sessionState().sentSkillNames()
            .computeIfAbsent(key, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        // resume 抑制路径：全量标已发送再返回空（CC :2709-2715 --resume 已含 listing 时不重复注入）
        if (ctx.sessionState().suppressNextSkillListing().compareAndSet(true, false)) {
            for (com.nexusai.model.command.Command cmd : commands) {
                sent.add(cmd.getName());
            }
            return new SkillListingDelta(java.util.List.of(), false);
        }
        // newSkills = filter !sent.contains(name)（CC :2718）
        java.util.List<com.nexusai.model.command.Command> newSkills = commands.stream()
            .filter(cmd -> !sent.contains(cmd.getName()))
            .toList();
        if (newSkills.isEmpty()) {
            return new SkillListingDelta(java.util.List.of(), false);
        }
        // isInitial 必须在标 sent 前取（CC :2725 sent.size === 0）
        boolean isInitial = sent.isEmpty();
        for (com.nexusai.model.command.Command cmd : newSkills) {
            sent.add(cmd.getName());
        }
        if (log.isDebugEnabled()) {
            log.debug("[P1-10 computeSkillListingDelta] agentKey={} newSkills={} isInitial={} sent={} · CC attachments.ts:2699-2730",
                key, newSkills.size(), isInitial, sent.size());
        }
        return new SkillListingDelta(newSkills, isInitial);
    }

    /**
     * [P1-10] computeSkillListingDelta 结果载体 · 对齐 CC attachments.ts:2717-2730
     * {@code newSkills = allCommands.filter(...)} + {@code isInitial = sent.size === 0}。
     *
     * @param newSkills 本次应注入的增量子集（CC original: newSkills）
     * @param isInitial 是否首次全量注入（CC original: isInitial）
     */
    public record SkillListingDelta(java.util.List<com.nexusai.model.command.Command> newSkills, boolean isInitial) {}

    /** skill catalog 异步 Haiku 摘要 · static 化自 LlmAgentLoop#triggerSkillCatalogHaikuSummaryAsync。 */
    public static void triggerSkillCatalogHaikuSummaryAsync(AgentLoopContext ctx, AgentState state, String catalogText) {
        LlmProviderFactory llmProviderFactory = ctx.llmProviderFactory();
        if (llmProviderFactory == null || state == null) return;
        // [RV14B-WIRE-04] 调用方线程先 resolve 真实配置（DB/settings 访问不在 ForkJoinPool 公共池），
        //   捕获进 runAsync lambda；解析失败 → warn+skip 不落 mock（对齐 CC queryHaiku 失败即无结果）。
        com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolved = resolveHaikuModelConfig(ctx);
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            log.warn("[R25-6 A8 Haiku skill summary] 模型配置解析失败，跳过（warn+skip 不落 mock，RV14B-GATE-01）");
            return;
        }
        String modelName = resolveHaikuModelName(ctx);
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    String prompt = "请用 1-2 句话总结当前可用技能列表的核心能力分类:\n"
                        + catalogText
                        + "\n\n只输出 1-2 句中文摘要, 不要前缀.";
                    String summary = llmProviderFactory.getProvider(resolved.config(), resolved.providerType()).chat(
                        resolved.config(),
                        modelName,
                        "你是技能目录摘要专家. 用一句话总结可用技能的核心能力.",
                        prompt
                    );
                    if (summary != null && !summary.isBlank()) {
                        state.appendAttachment(new com.nexusai.application.agent.attachment.AttachmentMessageDto(
                            null, "attachment", "skill_catalog_summary",
                            summary, null, null, null));
                        log.info("[R25-6 A8 Haiku skill summary] turn={} chars={} model={}",
                            state.turnCount(), summary.length(), modelName);
                    }
                } catch (Exception e) {
                    log.warn("[R25-6 A8 Haiku skill summary] failed: {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[R25-6 A8 Haiku skill summary] trigger failed: {}", e.getMessage());
        }
    }

    /**
     * [RV14B-WIRE-04] 解析 Haiku 模型真实配置 · fast 模型名 → DB 名 → (config, providerType)。
     *
     * <p>对齐 CC claude.ts:3278 {@code queryHaiku({ model: getSmallFastModel() })} +
     * model.ts:36-37 {@code getSmallFastModel() = ANTHROPIC_SMALL_FAST_MODEL || getDefaultHaikuModel()}。
     * Java 端无 ANTHROPIC_SMALL_FAST_MODEL env → settings fast/main → DB 名（fallback "claude-haiku-4-5-20251001"）。
     * 解析失败 → null → 调用方 warn+skip。
     *
     * @return 真实 (config, providerType)；解析失败 / ctx 无 resolver → null
     */
    private static com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolveHaikuModelConfig(AgentLoopContext ctx) {
        com.nexusai.infra.llm.ModelConfigResolver resolver = ctx.modelConfigResolver();
        if (resolver == null) {
            log.warn("AgentLoopContext: ModelConfigResolver 未注入，跳过 Haiku 配置解析（warn+skip 不落 mock）");
            return null;
        }
        String modelName = resolveHaikuModelName(ctx);
        if (modelName == null || modelName.isBlank()) return null;
        return resolver.resolve(modelName);
    }

    /**
     * [RV14B-WIRE-04] 解析 Haiku 模型 DB 名。
     *
     * @return DB 可用 fast 模型名；ctx 无 resolver → fallback 字面量（测试兜底）
     */
    private static String resolveHaikuModelName(AgentLoopContext ctx) {
        com.nexusai.infra.llm.ModelConfigResolver resolver = ctx.modelConfigResolver();
        if (resolver == null) return "claude-haiku-4-5-20251001";
        String fastName = resolver.resolveFastModelName("claude-haiku-4-5-20251001");
        return fastName != null && !fastName.isBlank() ? fastName : "claude-haiku-4-5-20251001";
    }

    /** 从配置/模型元数据计算 token budget · static 化自 LlmAgentLoop#computeBudgetFromGates。 */
    public static Integer computeBudgetFromGates(AgentLoopContext ctx,
            com.nexusai.application.agent.query.QueryConfig cfg,
            String modelName) {
        if (cfg != null && cfg.gates() != null && cfg.gates().isAnt()) {
            return ANT_TOKEN_BUDGET;
        }
        ModelMapper modelMapper = ctx.tokenBudgetBeans() != null ? ctx.tokenBudgetBeans().modelMapper() : null;
        ProviderMapper providerMapper = ctx.tokenBudgetBeans() != null ? ctx.tokenBudgetBeans().providerMapper() : null;
        if (modelName != null && !modelName.isBlank() && modelMapper != null && providerMapper != null) {
            // W1-2: 统一走全名解析器（providerName/modelName 联合查, 无 / 回退按 name 查第一条）
            ModelRecord model = ModelNameResolver.resolve(modelMapper, providerMapper, modelName);
            // W2-1: 模型级窗口优先（models.max_context_tokens）——provider 级不再读取（探查确认死源）
            if (model != null && model.getMaxContextTokens() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("[LlmAgentLoop] token budget from model metadata: model={} budget={} default={}",
                        modelName, model.getMaxContextTokens(), DEFAULT_TOKEN_BUDGET);
                }
                return model.getMaxContextTokens();
            }
        }
        return FALLBACK_TOKEN_BUDGET;
    }

    /**
     * 获取最后一条 assistant 消息 · CC utils/messages.ts getLastAssistantMessage。
     *
     * <p>反向遍历 state.messages()，返回第一条 role=assistant 的消息。
     * 供 D1 消息级 PTL 判定（isWithheld413 = isApiErrorMessage && isPromptTooLongMessage）使用。
     *
     * @param state AgentState
     * @return 最后一条 assistant 消息（null = 无）
     */
    public static ChatMessageDto getLastAssistantMessage(AgentState state) {
        if (state == null || state.messages() == null) return null;
        for (int i = state.messages().size() - 1; i >= 0; i--) {
            ChatMessageDto m = state.messages().get(i);
            if (m.role() == Role.assistant) return m;
        }
        return null;
    }

    /** 估算当前 turn 的 token 用量 · static 化自 LlmAgentLoop#estimateTurnTokens。 */
    public static int estimateTurnTokens(AgentLoopContext ctx, AgentState state) {
        if (state == null || state.messages() == null) return 0;
        TokenEstimator tokenEstimator = ctx.tokenBudgetBeans() != null ? ctx.tokenBudgetBeans().tokenEstimator() : null;
        if (tokenEstimator != null) {
            int total = 0;
            for (ChatMessageDto m : state.messages()) {
                total += tokenEstimator.estimateMessageTokens(m);
            }
            return total;
        }
        int chars = 0;
        for (ChatMessageDto m : state.messages()) {
            if (m.content() != null) chars += m.content().length();
        }
        return chars / 4;
    }

    /** [H7-arch Phase 5 P4 C1] 估算指定消息列表 token 用量 · static 化自 LlmAgentLoop#estimateMessagesTokens。 */
    public static int estimateMessagesTokens(AgentLoopContext ctx, List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        TokenEstimator tokenEstimator = ctx.tokenBudgetBeans() != null ? ctx.tokenBudgetBeans().tokenEstimator() : null;
        if (tokenEstimator != null) {
            int total = 0;
            for (ChatMessageDto m : messages) {
                total += tokenEstimator.estimateMessageTokens(m);
            }
            return total;
        }
        int chars = 0;
        for (ChatMessageDto m : messages) {
            if (m.content() != null) chars += m.content().length();
        }
        return chars / 4;
    }

    /**
     * [IMP-06] blocking-limit 统一窗口来源 · 对齐 CC {@code autoCompact.ts:93-145 calculateTokenWarningState}
     * 的 blocking 分支（effectiveWindow − MANUAL_COMPACT_BUFFER_TOKENS(3_000) + CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE）。
     *
     * <p><b>WHY（D-18 局部解除）</b>: blocking 预检此前用 {@link #computeBudgetFromGates}（provider.maxContextTokens）
     * 与 auto-compact 固定 200k 两套窗口（探查 S-2/S-3/DRIFT-4），且 blocking-limit 消费方与
     * {@code estimateMessageTokens} 双用途耦合。本方法把 blocking 窗口统一到 {@link CompactThresholdSystem}
     * 的阈值体系（与 auto 阈值同源，OD-12）：先经 {@link CompactThresholdSystem#getBlockingLimit(String)}
     * 计算（effectiveWindow − 3_000）。阈值体系经 autoCompactor（承载共享 bean）访问——GR-3 删除
     * 旧编排器后，AutoCompactor 成为阈值体系的唯一生产载体。
     *
     * <p><b>null-safety</b>: autoCompactor 未接线（单测/无 bean）时回落旧预算窗口 − 3_000 ——
     * 仅防御性兜底（非双轨实现），生产 autoCompactor 恒由 ToolRegistrationConfig 注入。
     *
     * @param ctx           loop 基础设施（兜底 computeBudgetFromGates 窗口来源）
     * @param model         当前模型名（blocking 窗口 model-aware）
     * @param autoCompactor auto 自动压缩器（承载共享 CompactThresholdSystem；null → 兜底）
     * @return blocking-limit token 数（≥ 0）
     */
    public static int computeBlockingLimit(AgentLoopContext ctx, String model, AutoCompactor autoCompactor) {
        if (autoCompactor != null) {
            CompactThresholdSystem thresholdSystem = autoCompactor.getThresholdSystem();
            if (thresholdSystem != null) {
                // 与 auto 阈值同源: blocking = effectiveWindow − 3_000（含 CLAUDE_CODE_BLOCKING_LIMIT_OVERRIDE）
                return Math.max(0, thresholdSystem.getBlockingLimit(model));
            }
        }
        // 兜底（autoCompactor 未接线）: 旧预算窗口 − 3_000
        int window = computeBudgetFromGates(ctx, ctx.queryConfig(), model);
        return Math.max(0, window - CompactConstants.MANUAL_COMPACT_BUFFER_TOKENS);
    }

    /** 发布 Agent 事件（AgentTurnStarted/Completed）· static 化自 LlmAgentLoop#publishEvent。 */
    public static void publishEvent(AgentLoopContext ctx, Object event) {
        // [D5 EventBridge] 主 publisher 优先（EventBridge.publisher），null 时回落 override
        //（VerifyChatController.setEventPublisher 注入，经 toLoopContext 捕获）。EVENT_BUFFER
        // ThreadLocal 与 LlmAgentLoop 共享（runStream 消费），经 public static 助手访问。
        EventBridge bridge = ctx.eventBridge();
        ApplicationEventPublisher publisher = bridge != null ? bridge.publisher() : null;
        if (publisher == null && bridge != null) {
            publisher = bridge.overridePublisher();
        }
        if (publisher == null && LlmAgentLoop.isEventBufferEmpty()) return;
        try {
            if (publisher != null) {
                publisher.publishEvent(event);
            }
        } catch (Exception e) {
            log.warn("LlmAgentLoop event publish failed (listener threw): event={} err={}",
                event.getClass().getSimpleName(), e.toString());
        }
        // FIX-LOOP-7: 把 Spring event 适配为 AgentEvent sealed 形式 (供 runStream 消费)
        AgentEvent ae = LlmAgentLoop.adaptToAgentEvent(event);
        if (ae != null) LlmAgentLoop.bufferEvent(ae);
    }

    /** streaming 工具执行是否启用 · static 化自 LlmAgentLoop#isStreamingToolExecutionEnabled。 */
    public static boolean isStreamingToolExecutionEnabled(AgentLoopContext ctx) {
        if (ctx.queryConfig() == null || ctx.queryConfig().gates() == null) return true;
        return ctx.queryConfig().gates().streamingToolExecution();
    }

    /** todo reminder 注入 · static 化自 LlmAgentLoop#maybeInjectTodoReminder。 */
    public static List<ChatMessageDto> maybeInjectTodoReminder(AgentLoopContext ctx, AgentState state,
                                                         List<ChatMessageDto> messagesForLlm) {
        ToolRegistry toolRegistry = ctx.toolRegistry();
        if (toolRegistry == null) {
            return messagesForLlm;
        }
        if (state.turnsSinceLastTodoWrite() < ctx.sessionState().todoReminderConfig().turnsSinceWrite()
            || state.turnsSinceLastTodoReminder() < ctx.sessionState().todoReminderConfig().turnsBetweenReminders()) {
            return messagesForLlm;
        }
        Tool tool = toolRegistry.get("TodoWrite").orElse(null);
        if (!(tool instanceof com.nexusai.application.agent.tool.impl.TodoWriteTool todoTool)
            || !todoTool.isEnabled()) {
            // 对齐 CC attachments.ts:3270-3277: TodoWrite 不在工具集时不 nag
            return messagesForLlm;
        }

        // [S05] 读侧迁移：todo 桶从会话 appState 读取（对齐 CC attachments.ts:3304-3306
        //   const todoKey = toolUseContext.agentId ?? getSessionId()
        //   const todos = appState.todos[todoKey] ?? []）
        // [session-id-short] 类型拆分后无法合成单值（agentId=UUID、sessionId=String）→
        // 双参直传；readTodosFromAppState 内主线程回退键 = state.sessionId()（short，与写侧
        // TodoWriteTool resolveTodoKey 收敛，防 EV-TDV3-TV1-033 复发）。
        var todos = readTodosFromAppState(ctx, state.agentId(), state.sessionId());
        // [R27-8 / R26-2] memoization: turnsSinceWrite + todosHash 决定 reminder text
        int turnsSinceWrite = state.turnsSinceLastTodoWrite();
        String todosHash = Integer.toHexString(todos.hashCode());
        String cacheKey = turnsSinceWrite + ":" + todosHash;
        String reminderText = ctx.sessionState().todoReminderCache().get(cacheKey);
        if (reminderText == null) {
            StringBuilder sb = new StringBuilder(TODO_REMINDER_TEXT);
            if (!todos.isEmpty()) {
                // 对齐 CC messages.ts:3664-3671: `${index + 1}. [${todo.status}] ${todo.content}`
                sb.append("\n\nHere are the existing contents of your todo list:\n\n[");
                for (int i = 0; i < todos.size(); i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(i + 1).append(". [").append(todos.get(i).status().toValue())
                      .append("] ").append(todos.get(i).content());
                }
                sb.append(']');
            }
            reminderText = sb.toString();
            ctx.sessionState().todoReminderCache().put(cacheKey, reminderText);
        }

        List<ChatMessageDto> withReminder = new ArrayList<>(messagesForLlm);
        withReminder.add(new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "system",
            reminderText, null, java.util.List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, java.util.List.of(), java.util.List.of()));
        state.resetTurnsSinceLastTodoReminder();
        // 记录 attachment（对齐 CC createAttachmentMessage({type: 'todo_reminder', ...})）
        state.appendAttachment(new com.nexusai.application.agent.attachment.AttachmentMessageDto(
            null, "attachment", "todo_reminder", reminderText, null, null, null));
        log.info("LlmAgentLoop todo_reminder injected: turn={} turnsSinceWrite={} todos={}",
            state.turnCount(), state.turnsSinceLastTodoWrite(), todos.size());
        return withReminder;
    }

    /**
     * [S05] 读侧从会话 appState 读 todo 桶 · 对齐 CC attachments.ts:3304-3306。
     *
     * <p>todoKey 解析与 execute 一致（CC {@code context.agentId ?? getSessionId()}）：
     * 调用方已归一 effectiveAgentId = agentId != null ? agentId : state.sessionId()
     * （IM1：主线程回退 sessionUuid，对齐写侧 LlmAgentLoop.buildBaseToolUseContext effectiveAgentId）。
     * 此处 agentId 非 null → agentId 字符串；两者皆 null（shared/standalone ctx）→ 回退
     * streamSessionId，仍 null → 空列表。
     *
     * <p>appStateReader 由 LlmAgentLoop.buildSessionStateFromInstance 注入
     * {@code session.setAppStateReader(prev -> getAppStateSnapshot())}（per-session 隔离）；
     * null（单测/standalone 构造路径）→ 无 todo 历史，返回空列表。
     */
    private static java.util.List<com.nexusai.application.agent.tool.impl.TodoWriteTool.TodoItem> readTodosFromAppState(
            AgentLoopContext ctx, UUID agentId, String sessionId) {
        java.util.function.Function<java.util.Map<String, Object>, java.util.Map<String, Object>> reader =
            ctx.sessionState().appStateReader();
        if (reader == null) {
            return java.util.List.of();
        }
        // [session-id-short] 主线程回退键 = sessionId（short，与写侧 TodoWriteTool resolveTodoKey 收敛）
        String todoKey = agentId != null ? agentId.toString() : sessionId;
        if (todoKey == null) {
            return java.util.List.of();
        }
        return com.nexusai.application.agent.tool.impl.TodoWriteTool.readTodosFromAppState(
            reader.apply(java.util.Map.of()), todoKey);
    }

    /** fallback 后 per-turn 状态复位 · static 化自 LlmAgentLoop#resetPerTurnStateOnFallback。 */
    public static void resetPerTurnStateOnFallback(AgentLoopContext ctx) {
        // Java 端 per-turn 数组 (capturedMsg/errored/acc/reasoningBuf/seenToolCalls/seenToolIds)
        // 在 do-while 下一轮迭代由 loop 内自动重新初始化. 这里仅日志与契约文档.
        log.info("[R25-8 fallback reset] Java per-turn arrays auto-reset at next loop iteration");
    }

    /** diff engine trace 发射 · static 化自 LlmAgentLoop#traceEmit。 */
    public static void traceEmit(AgentLoopContext ctx, TraceEvent event) {
        EventBridge bridge = ctx.eventBridge();
        TraceRecorder traceRecorder = bridge != null ? bridge.traceRecorder() : null;
        if (traceRecorder != null) traceRecorder.record(event);
    }

    /** LLM 阶段日志 · static 化自 LlmAgentLoop#logLlmPhase。 */
    public static void logLlmPhase(AgentLoopContext ctx, int turn, AssistantMessage msg, String text, int chunkCount) {
        String reasoning = msg == null ? null : msg.reasoning();
        boolean hasReasoning = reasoning != null && !reasoning.isBlank();
        boolean hasText = text != null && !text.isEmpty();
        boolean hasTools = msg != null && msg.hasToolCalls();

        if (hasReasoning) {
            log.info("LLM call#{} phase=reasoning len={} preview=\"{}\"",
                turn, reasoning.length(), abbreviate(reasoning, 80));
        }
        if (hasTools) {
            List<ToolUseBlock> calls = msg.toolCalls();
            StringBuilder sb = new StringBuilder();
            sb.append("LLM call#").append(turn).append(" phase=tool_use calls=").append(calls.size());
            for (int i = 0; i < calls.size(); i++) {
                ToolUseBlock c = calls.get(i);
                if (i > 0) sb.append(", ");
                sb.append(c.name()).append("(").append(abbreviate(jsonNodeToString(c.input()), 60)).append(")");
            }
            if (hasText) {
                sb.append(" preamble=\"").append(abbreviate(text, 60)).append("\"");
            }
            log.info(sb.toString());
        } else if (hasText) {
            String label = hasReasoning ? "preamble" : "text";
            log.info("LLM call#{} phase={} len={} chunks={} preview=\"{}\"",
                turn, label, text.length(), chunkCount, abbreviate(text, 80));
        }
        // 既无 text 也无 tools 也无 reasoning → 空响应（前面 STREAM_ERROR 已处理）
    }

    /** 生成任务摘要 attachment · static 化自 LlmAgentLoop#generateTaskSummaryAttachment。 */
    public static void generateTaskSummaryAttachment(AgentLoopContext ctx, AgentState state) {
        if (state == null) return;
        AgentState.ExitReason reason = state.exitReason();
        if (reason == AgentState.ExitReason.MAX_TURNS || reason == AgentState.ExitReason.ABORTED
            || reason == AgentState.ExitReason.STOP_HOOK_PREVENTED
            || reason == AgentState.ExitReason.HOOK_STOPPED) {
            log.debug("[A11 task_summary] skipped (reason={} has own attachment)", reason);
            return;
        }
        try {
            String summary = String.format(
                "task_summary: turns=%d exit_reason=%s finished_at=%d",
                state.turnCount(),
                state.exitReason(),
                System.currentTimeMillis());
            state.appendAttachment(new com.nexusai.application.agent.attachment.AttachmentMessageDto(
                null, "attachment", "task_summary", summary, null, null, null));
            log.info("[A11 task_summary] generated: turns={} exit_reason={}",
                state.turnCount(), state.exitReason());
        } catch (Exception e) {
            log.warn("[A11 task_summary] failed: {}", e.getMessage());
        }
    }


    /** task reminder 注入 · static 化自 LlmAgentLoop#maybeInjectTaskReminder。 */
    public static List<ChatMessageDto> maybeInjectTaskReminder(AgentLoopContext ctx, AgentState state,
                                                         List<ChatMessageDto> messagesForLlm) {
        // [prompt-align CTX-02] 门控切 DB task_reminder_enabled（null→TaskSystemConfig.isTodoV2Enabled()，
        //   MDC isInteractive 会话感知，保留现状不迁移）。CC 真源 messages.ts:3680-3698 case 'task_reminder'
        //   先判 !isTodoV2Enabled() 直接返回 []；isTodoV2Enabled = env 强制 || !getIsNonInteractiveSession
        //   （utils/tasks.ts:133-139）。enableTaskReminder 遗留标志不再作为唯一门（LlmAgentLoop:1004 注释更新）。
        com.nexusai.application.agent.prompt.PromptAlignSettingsResolver r =
            ctx.sessionState().promptAlignSettingsResolver();
        Boolean v = (r == null) ? null : r.taskReminderEnabled();
        boolean gate = (v != null) ? v : TaskSystemConfig.isTodoV2Enabled();
        if (!gate) {
            return messagesForLlm;
        }
        if (log.isInfoEnabled()) {
            log.info("task_reminder 门控通过: gate={} source={} · CC messages.ts:3681 isTodoV2Enabled() 先判（DB task_reminder_enabled / fallback 会话交互判定）",
                gate, (v != null) ? "DB" : "fallback");
        }
        List<LlmAgentLoop.TaskAttachment> attachments = computeTaskReminderAttachments(ctx, state);
        if (attachments.isEmpty()) {
            return messagesForLlm;
        }
        LlmAgentLoop.TaskAttachment att = attachments.get(0);
        @SuppressWarnings("unchecked")
        List<Task> tasks = att.content() instanceof List
            ? (List<Task>) att.content()
            : List.of();

        // [prompt-align CTX-01] 文本与 item 格式对齐 CC messages.ts:3680-3698 case 'task_reminder'：
        //   message = CC 原串（TASK_CREATE_TOOL_NAME='TaskCreate' / TASK_UPDATE_TOOL_NAME='TaskUpdate'
        //   TaskCreateTool/constants.ts:1 + TaskUpdateTool/constants.ts:1）
        //   taskItems = tasks.map(t => `#${t.id}. [${t.status}] ${t.subject}`).join('\n')（:3682-3683）
        //   status 小写（TaskStatus.toValue() = pending/in_progress/completed，对齐 CC tasks.ts:69）
        //   message += `\n\nHere are the existing tasks:\n\n${taskItems}`（:3689-3691）——去 [] 括号包裹。
        StringBuilder sb = new StringBuilder();
        sb.append("The task tools haven't been used recently. If you're working on tasks that would benefit from tracking progress, consider using TaskCreate to add new tasks and TaskUpdate to update task status (set to in_progress when starting, completed when done). Also consider cleaning up the task list if it has become stale. Only use these if relevant to the current work. This is just a gentle reminder - ignore if not applicable. Make sure that you NEVER mention this reminder to the user\n");
        if (!tasks.isEmpty()) {
            sb.append("\n\nHere are the existing tasks:\n\n");
            for (int i = 0; i < tasks.size(); i++) {
                if (i > 0) sb.append('\n');
                Task t = tasks.get(i);
                // t.id() null 守卫：CC 模板渲染 undefined（:3682），Java 对 null id 落空串（listTasks
                //   返回已持久化任务 id 恒非空，防御即可）。
                sb.append('#').append(t.id() != null ? t.id() : "").append(". [")
                  .append(t.status().toValue()).append("] ").append(t.subject());
            }
        }
        String reminderText = sb.toString();

        List<ChatMessageDto> withReminder = new ArrayList<>(messagesForLlm);
        withReminder.add(new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "system",
            reminderText, null, java.util.List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, java.util.List.of(), java.util.List.of()));
        // reset counter so we don't nag for at least TURNS_BETWEEN_REMINDERS turns
        ctx.sessionState().setTurnsSinceLastTaskReminder(0);
        ctx.sessionState().setTurnsSinceLastTaskManagement(0);
        // record attachment（对齐 CC createAttachmentMessage({type: 'task_reminder', ...})）
        state.appendAttachment(new com.nexusai.application.agent.attachment.AttachmentMessageDto(
            null, "attachment", "task_reminder", reminderText, null, null, null));
        if (log.isInfoEnabled()) {
            log.info("LlmAgentLoop task_reminder injected: turnsSinceManagement={} turnsSinceReminder={} tasks={}",
                ctx.sessionState().turnsSinceLastTaskManagement(),
                ctx.sessionState().turnsSinceLastTaskReminder(), tasks.size());
        }
        return withReminder;
    }

    /** [P2] 核心 task_reminder 计算 · static 化自 LlmAgentLoop#computeTaskReminderAttachments。 */
    private static List<LlmAgentLoop.TaskAttachment> computeTaskReminderAttachments(AgentLoopContext ctx, AgentState state) {
        // [prompt-align CTX-02] 门控与 maybeInjectTaskReminder 同一公式（DB task_reminder_enabled →
        //   fallback isTodoV2Enabled）——防御双入口一致（CC messages.ts:3681 先判 isTodoV2Enabled）。
        com.nexusai.application.agent.prompt.PromptAlignSettingsResolver r =
            ctx.sessionState().promptAlignSettingsResolver();
        Boolean v = (r == null) ? null : r.taskReminderEnabled();
        boolean gate = (v != null) ? v : TaskSystemConfig.isTodoV2Enabled();
        if (!gate || state == null) {
            return List.of();
        }
        List<ChatMessageDto> messages = state.messages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        LlmAgentLoop.TaskReminderTurnCounts counts = getTaskReminderTurnCounts(state, messages);
        if (counts.turnsSinceManagement() < ctx.sessionState().taskReminderConfig().turnsSinceWrite()
            || counts.turnsSinceReminder() < ctx.sessionState().taskReminderConfig().turnsBetweenReminders()) {
            return List.of();
        }
        List<Task> tasks = listTasks(ctx.sessionState().taskService(), TaskSystemConfig.getDefaultTaskListId());
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<LlmAgentLoop.TaskAttachment> result = new ArrayList<>(1);
        result.add(new LlmAgentLoop.TaskAttachment("task_reminder", tasks, tasks.size()));
        return result;
    }

    /** [P2] 反向扫描 messages + attachments 计算 task reminder turn 计数 · static 化自 LlmAgentLoop。 */
    private static LlmAgentLoop.TaskReminderTurnCounts getTaskReminderTurnCounts(AgentState state, List<ChatMessageDto> messages) {
        int turnsSinceManagement = 0;
        int turnsSinceReminder = 0;
        boolean foundManagement = false;
        boolean foundReminder = false;

        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessageDto m = messages.get(i);
                if (m == null) {
                    continue;
                }
                if (m.role() == com.nexusai.model.session.dto.Role.assistant) {
                    // Check for TaskCreate/TaskUpdate BEFORE incrementing counter
                    if (!foundManagement && m.toolCalls() != null) {
                        for (com.nexusai.model.session.dto.ToolCallDto tc : m.toolCalls()) {
                            if (tc == null) continue;
                            String n = tc.name();
                            if ("TaskCreate".equals(n) || "TaskUpdate".equals(n)) {
                                foundManagement = true;
                                break;
                            }
                        }
                    }
                    if (!foundManagement) {
                        turnsSinceManagement++;
                    }
                    if (!foundReminder) {
                        turnsSinceReminder++;
                    }
                }
                if (foundManagement && foundReminder) {
                    break;
                }
            }
        }

        if (!foundReminder && state != null) {
            List<com.nexusai.application.agent.attachment.AttachmentMessageDto> attachments = state.attachments();
            for (int i = attachments.size() - 1; i >= 0; i--) {
                com.nexusai.application.agent.attachment.AttachmentMessageDto a = attachments.get(i);
                if (a != null && "task_reminder".equals(a.type())) {
                    foundReminder = true;
                    break;
                }
            }
        }

        return new LlmAgentLoop.TaskReminderTurnCounts(turnsSinceManagement, foundReminder ? 0 : turnsSinceReminder);
    }

    /** [P2] 列出 task 列表 · taskService 为 null 时返回空列表（降级不注入 reminder）。 */
    private static List<Task> listTasks(TaskService taskService, String taskListId) {
        if (taskService == null || taskListId == null) {
            return List.of();
        }
        try {
            List<Task> tasks = taskService.listTasks(taskListId);
            return tasks != null ? tasks : List.of();
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("listTasks({}) failed: {}", taskListId, e.getMessage());
            }
            return List.of();
        }
    }

    /** 截断字符串（static 化自 LlmAgentLoop#abbreviate）。 */
    private static String abbreviate(String s, int max) {
        if (s == null) return "(null)";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…(" + s.length() + ")";
    }

    /** JsonNode → String（static 化自 LlmAgentLoop#jsonNodeToString）。 */
    private static String jsonNodeToString(JsonNode node) {
        if (node == null) return "{}";
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [H7-arch Phase 5-2 P3-⑤] base-TUC 线程化 + 重方法 static 化
    //   原 Behaviors（已删）2 个重方法（buildStreamingExecutor / handleToolCallsTurn）+ 私有辅助
    //   （toolExecContext / runTools / applyToolResultBudget 等）从 LlmAgentLoop 提升为
    //   static-with-ctx。工具来源 = per-turn TUC 的 availableTools()（对齐 CC toolUseContext.
    //   options.tools），经 ToolRegistry.from 适配，不再读 ctx.toolRegistry()。
    // ════════════════════════════════════════════════════════════════════

    /**
     * 从 base TUC 派生每轮 per-turn TUC · static 化自 LlmAgentLoop#toolExecContext（P3-⑤ 线程化）。
     *
     * <p><b>每轮派生</b>：queryTracking stamp（A2 已有）+ messages 快照（state.messages()）+
     * permission context 重建（ctx.permissionContextBuilder()）。会话 UI/C2/session 回调、
     * abortController、availableTools、nonInteractiveSession、onCompactProgress 均从 base TUC
     * 继承（会话级不变）。派生结果经 {@code state.setCurrentToolUseContext} stamp（对齐 CC
     * state.toolUseContext query.ts:1715-1727），供 SubagentTool 读为 parentTUC。
     *
     * @param ctx           loop 基础设施（permissionContextBuilder 来源）
     * @param baseTuc       入口一次性构造的完整 base TUC（会话 UI/C2/session 回调载体）
     * @param state         AgentState（messages 快照来源 + stamp 目标）
     * @param queryTracking 本轮 queryTracking（loop 已递增，null-safe）
     * @return per-turn TUC（baseTuc null / state null → null）
     */
    public static com.nexusai.application.agent.tool.ToolUseContext toolExecContext(
            AgentLoopContext ctx,
            com.nexusai.application.agent.tool.ToolUseContext baseTuc,
            AgentState state,
            Map<String, Object> queryTracking) {
        if (baseTuc == null || state == null) {
            return null;
        }
        // [对抗核验 H13-GAP-1 v3] hook agent 专属 permCtx 保留（不重建）· 对齐 CC getAppState override。
        //   ExecAgentHook 构造的 base TUC 携带 mode=DONT_ASK 的专属 permCtx（父规则 ∪ Read + dontAsk）。
        //   若照常走 builder 重建（恒返回 DEFAULT mode 空规则）, hook 的 Read(transcriptPath) 规则会丢失
        //   → DONT_ASK 下 Read 被 R26 hook 层拒绝, hook 无法读 transcript 验证条件。
        //   识别信号: 仅 ExecAgentHook 构造 mode=DONT_ASK 的 ToolPermissionContext（builder 从不产 DONT_ASK）。
        com.nexusai.application.agent.permission.ToolPermissionContext basePermCtx = baseTuc.permissionContext();
        if (basePermCtx != null && basePermCtx.mode() == PermissionMode.DONT_ASK) {
            if (log.isDebugEnabled()) {
                log.debug("AgentLoopContext toolExecContext: hook agent DONT_ASK permCtx 保留 (不重建)");
            }
            com.nexusai.application.agent.tool.ToolUseContext hookTuc = baseTuc
                .withQueryTracking(queryTracking)
                .withMessages(state.messages() != null ? state.messages() : List.of());
            state.setCurrentToolUseContext(hookTuc);
            return hookTuc;
        }
        // [P3-⑤] permission context 每轮重建 · 对齐 CC 每轮 rebuildPermissionContext
        com.nexusai.application.agent.permission.ToolPermissionContext permCtx = null;
        PermissionMode permMode = baseTuc.permissionMode();
        if (ctx.permissionContextBuilder() != null) {
            try {
                // [H9-v2 未登记缺口] awaitAutomatedChecksBeforeDialog 生产来源 · 对齐 CC
                // runAgent.ts:457-464 — bubble mode (fork 子 agent 冒泡 = CC 异步后台 agent
                // 且能弹窗) 置 true: 先 await 自动化检查再弹窗. 非 bubble → false (主线程/同步子 agent).
                boolean awaitAutomatedChecks =
                    baseTuc.permissionMode() == PermissionMode.BUBBLE;
                // [H9 v3 Gap①] 把 base TUC 的 permissionMode 显式传入 buildPermissionContext,
                // 让重建后的 permCtx.mode() 携带 BUBBLE — 否则 permMode = permCtx.mode() 会把
                // BUBBLE 覆盖回 DEFAULT, per-turn TUC permissionMode() 恒 DEFAULT, gate 的
                // bubble 分支 (ToolPermissionGate L680) 与 coordinator 分支 (L522) 生产不可达.
                // [B-2] shouldAvoidPermissionPrompts 每轮保真 · 对齐 CC runAgent.ts:440-451:
                //   SubagentExecutor 已把 flag 写入子 base TUC permCtx (standalone 亦有最小载体),
                //   此处读 base 透传 builder — 防每轮重建 (旧实现硬编码 false) 把 flag 覆盖回 false.
                boolean shouldAvoidPrompts = baseTuc.permissionContext() != null
                    && baseTuc.permissionContext().shouldAvoidPermissionPrompts();
                // [F1-BY] isBypassPermissionsModeAvailable 保留 base 值（对齐 CC per-turn 保留语义：
                //   applyPermissionUpdate setMode {...context, mode} spread，PermissionUpdate.ts:60-67）。
                //   不再经 4 参重载硬编码 true，否则 org/settings 启动时禁用 bypass 会在 per-turn
                //   重建被翻回 true（CheckLayer2a_BypassMode:80 禁用门失效）。base permCtx==null → false
                //   （对齐 CC Tool.ts:147 默认 false）。
                boolean baseBypassAvailable = baseTuc.permissionContext() != null
                    && baseTuc.permissionContext().isBypassPermissionsModeAvailable();
                permCtx = ctx.permissionContextBuilder().buildPermissionContext(state,
                    awaitAutomatedChecks, baseTuc.permissionMode(), shouldAvoidPrompts,
                    baseBypassAvailable);
                // [P0-2] 合并 appState 的 skill allowedTools command 授权到 per-turn permCtx ·
                //   对齐 CC SkillTool.ts:790-801 (contextModifier 改 appState.toolPermissionContext).
                permCtx = mergeAppStateCommandRules(baseTuc, permCtx);
                if (permCtx != null) {
                    permMode = permCtx.mode();
                }
            } catch (Exception e) {
                log.warn("AgentLoopContext toolExecContext permission rebuild failed, fallback to base: {}",
                    e.getMessage());
            }
        }
        com.nexusai.application.agent.tool.ToolUseContext perTurnTuc = baseTuc
            .withQueryTracking(queryTracking)
            .withMessages(state.messages() != null ? state.messages() : List.of())
            .withPermissionContext(permCtx, permMode)
            // [openai-lazy] 注入当前 turn 有效模型名（ToolSearchTool 渲染分流：
            //   Anthropic 纯 tool_reference / openai_compatible 追加完整 JSONSchema 文本）。
            //   state.currentModel() = LlmAgentLoop doRun 入口 + 每轮 effectiveModel 覆盖写。
            .withEffectiveModelName(state.currentModel());
        state.setCurrentToolUseContext(perTurnTuc);
        if (log.isDebugEnabled()) {
            log.debug("AgentLoopContext toolExecContext stamp: sessionId={} queryTracking={} effectiveModel={}",
                state.sessionId(),
                perTurnTuc.queryTracking() != null ? perTurnTuc.queryTracking() : "(null)",
                perTurnTuc.effectiveModelName() != null ? perTurnTuc.effectiveModelName() : "(null)");
        }
        return perTurnTuc;
    }

    /**
     * [P0-2] 把 appState 中技能注入的 allowedTools command 授权合并进 per-turn permCtx ·
     * 对齐 CC SkillTool.ts:790-801 contextModifier 修改
     * {@code appState.toolPermissionContext.alwaysAllowRules.command} 的会话内存语义.
     *
     * <p><b>CC 真源</b>: SkillTool contextModifier 把技能 allowedTools 去重合入
     * {@code appState.toolPermissionContext.alwaysAllowRules.command}（SkillTool.ts:779-806）,
     * 后续 per-turn 权限上下文由 appState 派生 → 技能授权的工具在后续工具调用不再被权限层阻断.
     * Java 端 per-turn permCtx 由 {@link PermissionContextBuilder} 每轮重建（不读 appState）,
     * 故在此把 {@code appStateRef['toolPermissionContext']} 的 command 规则（
     * {@link PermissionRuleSource#COMMAND} 桶, 由 SkillToolImpl.buildContextModifier 写入）
     * 合并进重建结果的同一桶（去重, 保既有 COMMAND 规则）.
     *
     * <p><b>空安全</b>: appState 无 toolPermissionContext / 无 command 规则 / getAppState 抛异常
     * → 原样返回 permCtx（不阻断 per-turn 构建, fail-loud 以 warn 日志暴露）.
     *
     * @param baseTuc  loop base TUC（getAppState 桥接会话 appStateRef 快照）
     * @param permCtx  重建后的 per-turn permission context
     * @return 合并 command 规则后的 permCtx；无技能授权时原样返回
     */
    private static com.nexusai.application.agent.permission.ToolPermissionContext mergeAppStateCommandRules(
            com.nexusai.application.agent.tool.ToolUseContext baseTuc,
            com.nexusai.application.agent.permission.ToolPermissionContext permCtx) {
        if (baseTuc == null || permCtx == null) {
            return permCtx;
        }
        try {
            Map<String, Object> snapshot = baseTuc.getAppState().apply(null);
            if (snapshot == null) {
                return permCtx;
            }
            Object tpcObj = snapshot.get("toolPermissionContext");
            if (!(tpcObj instanceof com.nexusai.application.agent.permission.ToolPermissionContext tpc)) {
                return permCtx;
            }
            Set<com.nexusai.application.agent.permission.PermissionRule> commandRules =
                tpc.alwaysAllowRules().get(com.nexusai.application.agent.permission.PermissionRuleSource.COMMAND);
            if (commandRules == null || commandRules.isEmpty()) {
                return permCtx;
            }
            Map<com.nexusai.application.agent.permission.PermissionRuleSource,
                    Set<com.nexusai.application.agent.permission.PermissionRule>> allow =
                new EnumMap<>(permCtx.alwaysAllowRules());
            Set<com.nexusai.application.agent.permission.PermissionRule> merged = new LinkedHashSet<>(
                allow.getOrDefault(com.nexusai.application.agent.permission.PermissionRuleSource.COMMAND,
                    Set.of()));
            merged.addAll(commandRules);
            allow.put(com.nexusai.application.agent.permission.PermissionRuleSource.COMMAND, merged);
            if (log.isDebugEnabled()) {
                log.debug("AgentLoopContext mergeAppStateCommandRules: 合并 {} 条 skill command 授权规则",
                    commandRules.size());
            }
            return new com.nexusai.application.agent.permission.ToolPermissionContext(
                permCtx.mode(), allow, permCtx.alwaysDenyRules(), permCtx.alwaysAskRules(),
                permCtx.additionalWorkingDirectories(), permCtx.isBypassPermissionsModeAvailable(),
                permCtx.isAutoModeAvailable(), permCtx.strippedDangerousRules(),
                permCtx.shouldAvoidPermissionPrompts(), permCtx.awaitAutomatedChecksBeforeDialog(),
                permCtx.prePlanMode());
        } catch (Exception e) {
            log.warn("AgentLoopContext mergeAppStateCommandRules failed, fallback permCtx: {}",
                e.toString());
            return permCtx;
        }
    }

    /**
     * 构建 streaming 工具执行器 · static 化自 LlmAgentLoop#buildStreamingExecutor（P3-⑤）。
     *
     * <p>工具来源 = {@code perTurnTuc.availableTools()}（对齐 CC toolUseContext.options.tools），
     * 经 {@link ToolRegistry#from(List)} 适配为临时隔离 registry；守卫从原 {@code toolRegistry == null}
     * 改为 {@code perTurnTuc == null || perTurnTuc.availableTools().isEmpty()}。gate/hook/sanitizer/
     * validator/telemetry/transcriptClassifierEnabled 均从 {@link #toolExecutionBeans()} 读取。
     *
     * @param ctx              loop 基础设施（ToolExecutionBeans + hookRegistry）
     * @param perTurnTuc       本轮 per-turn TUC（executor 的 tool ctx + 工具来源）
     * @param state            AgentState（lineage + extended result apply）
     * @return StreamingToolExecutor；perTurnTuc 无工具时返回 null（调用方跳过）
     */
    public static StreamingToolExecutor buildStreamingExecutor(AgentLoopContext ctx,
            com.nexusai.application.agent.tool.ToolUseContext perTurnTuc,
            AgentState state,
            String turnAssistantId,
            java.util.function.BiConsumer<AgentToolResult<?>, String> extendedHandler,
            boolean deferredModifier,
            AgentOptions agentOptions,
            ForkSubagentMessages.Message assistantMessage) {
        if (perTurnTuc == null || perTurnTuc.availableTools().isEmpty()) {
            return null;
        }
        ToolExecutionBeans beans = ctx.toolExecutionBeans();
        ToolPermissionGate gate;
        if (beans != null && beans.permissionGate() != null) {
            gate = beans.permissionGate();
        } else if (beans != null && beans.permissionPipeline() != null && beans.permissionPrompter() != null) {
            // [Session H9] createSpringBean 3 参重载 · 让 gate 的 logPermissionDecision
            //   遥测 (tengu_* 事件 + code-edit counter) 持有真实 telemetry
            // [canUseTool v2] 6 参重载 · 生产接线三 handler
            //   (coordinator / swarm / interactive 不再是恒 null 死路径)
            // [REV-FIX-4 WF-3 缝隙2] 8 参重载 · 追加 ctx.hookRegistry() 让 headless 场景
            //   PermissionRequest hook 链生产接线（对齐 CC permissions.ts:932-951：headless
            //   恒先跑 hook 链，无 hook 决策才 auto-deny asyncAgent）
            gate = ToolPermissionGate.createSpringBean(
                beans.permissionPipeline(), beans.permissionPrompter(),
                beans.telemetry(),
                beans.coordinatorHandler(),
                beans.swarmWorkerHandler(),
                beans.interactiveHandler(),
                beans.bashClassifierFeature(),
                ctx.hookRegistry());
            if (log.isDebugEnabled()) {
                log.debug("AgentLoopContext buildStreamingExecutor: gate 走 createSpringBean fallback, "
                        + "hookRegistry={}（headless PermissionRequest hook 链{}）",
                    ctx.hookRegistry() != null,
                    ctx.hookRegistry() != null ? "已接线" : "未注入→auto-deny");
            }
        } else {
            gate = null;
        }
        StreamingToolExecutor exec = new StreamingToolExecutor(
            ToolRegistry.from(perTurnTuc.availableTools()),
            perTurnTuc,
            extendedHandler != null
                ? extendedHandler
                : (er, id) -> ToolResultApplier.apply(er, state.messages(), state, id),
            gate,
            ctx.hookRegistry());
        exec.setSubagentExecutionContext(agentOptions, assistantMessage);
        if (beans != null && beans.telemetry() != null) {
            exec.setTelemetry(beans.telemetry());
        }
        // [P2-1] hook 抛错时 telemetry 埋点注入到 HookRegistry 层（loop 外层 catch 不可达）
        if (beans != null && beans.telemetry() != null && ctx.hookRegistry() != null) {
            ctx.hookRegistry().setTelemetry(beans.telemetry());
        }
        exec.setAgentState(state);
        // [工具调用实时推] 注入 tool_stream 推送通道 · 仅按 wsTemplate 存在门控 (不依赖
        //   streamingToolExecution gate, executor 两路径都建). background task topic
        //   (/topic/tasks/{taskId}/stream, setTaskStreamContext) 同机制生效,
        //   streamUserMessageId=null 时事件字段被 @JsonInclude(NON_NULL) 省略.
        //   assistantMessageId 不在此注入 — 经 t.parent.assistantMessageId()
        //   (LlmAgentLoop turnAssistantId 逐工具注入 ToolParent) 取.
        if (ctx.wsTemplate() != null && ctx.streamTopic() != null) {
            exec.setToolStreamPublisher(ctx.wsTemplate(), ctx.streamTopic(),
                ctx.streamSessionId(), ctx.streamUserMessageId());
        }
        if (beans != null && beans.inputSanitizer() != null) {
            exec.setInputSanitizer(beans.inputSanitizer());
        }
        if (beans != null && beans.inputValidator() != null) {
            exec.setInputValidator(beans.inputValidator());
        }
        exec.setDeferContextModifier(deferredModifier);
        if (ctx.hookRegistry() != null) {
            exec.setPermissionDeniedHookExecutor(
                new com.nexusai.application.agent.permission.PermissionDeniedHookExecutor(ctx.hookRegistry()));
        }
        exec.setRetryMessageFactory(new com.nexusai.application.agent.permission.RetryMessageFactory());
        exec.setTranscriptClassifierEnabled(beans != null && beans.transcriptClassifierEnabled());
        // [H8 v2 补全 H8-GAP-1] Bash sandbox manager → HookPermissionResolver 的 sandbox auto-allow
        //   (CC permissions.ts:1186-1205); null-safe (无 bean 时 sandbox 语义关闭 = 登记前行为)
        if (beans != null && beans.sandboxManager() != null) {
            exec.setSandboxManager(beans.sandboxManager());
        }
        if (log.isDebugEnabled()) {
            log.debug("AgentLoopContext buildStreamingExecutor: telemetry={} gate={} hook={} sanitizer={} validator={} deferred={} assistantId={} tools={}",
                beans != null && beans.telemetry() != null, gate != null, ctx.hookRegistry() != null,
                beans != null && beans.inputSanitizer() != null, beans != null && beans.inputValidator() != null,
                deferredModifier, abbreviate(turnAssistantId, 12), perTurnTuc.availableTools().size());
        }
        return exec;
    }

    /**
     * [R32-b15 Stage 2 C4] 顶层 runTools 统一入口 · static 化自 LlmAgentLoop#runTools（P3-⑤）。
     *
     * <p>streaming 路径拿已 add 的 executor 同步收集 results + drain deferred modifier；
     * fallback 路径经 {@link #buildStreamingExecutor} 新建 executor 后按 tool_call 原序 add。
     */
    private static com.nexusai.application.agent.LlmAgentLoop.ToolRunOutcome runTools(
            AgentLoopContext ctx,
            com.nexusai.application.agent.tool.ToolUseContext perTurnTuc,
            AgentState state,
            List<ToolUseBlock> toolCalls,
            String turnAssistantId,
            StreamingToolExecutor streamingExec,
            java.util.function.BiConsumer<AgentToolResult<?>, String> extendedHandler,
            AgentOptions agentOptions,
            ForkSubagentMessages.Message assistantMessage,
            java.util.function.Consumer<Tool.ToolProgress> onToolProgress) {
        com.nexusai.application.agent.LlmAgentLoop.ToolRunOutcome outcome;
        if (streamingExec != null) {
            streamingExec.setSubagentExecutionContext(agentOptions, assistantMessage);
            log.info("AgentLoopContext runTools 顶层入口 [streaming path]: exec.size={} calls={}",
                streamingExec.size(), toolCalls.size());
            List<com.nexusai.application.agent.tool.ToolResult> results;
            try {
                // [DEC-2 / OPD-TOOL-EX-01] streaming 路径直接消费惰性 stream (增量实现核心),
                //   替代阻塞 getRemainingResults (allOf 一次性收集 → 慢工具拖快工具).
                results = streamingExec.getRemainingResultsStream().toList();
            } catch (Exception e) {
                state.setError("tool await failed: " + e.getMessage());
                state.setExitReason(AgentState.ExitReason.STREAM_ERROR);
                log.error("AgentLoopContext tool await failed", e);
                streamingExec.applyDeferredContextModifiers(perTurnTuc);
                return new com.nexusai.application.agent.LlmAgentLoop.ToolRunOutcome(List.of(),
                    java.util.Map.copyOf(state.assistantIdByToolUseId()), java.util.Map.of());
            }
            streamingExec.applyDeferredContextModifiers(perTurnTuc);
            outcome = new com.nexusai.application.agent.LlmAgentLoop.ToolRunOutcome(results,
                java.util.Map.copyOf(state.assistantIdByToolUseId()),
                streamingExec.getResultErrorFlags());
            if (log.isInfoEnabled()) {
                log.info("AgentLoopContext runTools [streaming]: results={} lineageSize={}",
                    outcome.results().size(), outcome.assistantIdByToolUseId().size());
            }
        } else {
            log.warn("AgentLoopContext runTools 顶层入口 [fallback path]: batch mode, calls={}",
                toolCalls.size());
            StreamingToolExecutor exec = buildStreamingExecutor(ctx, perTurnTuc, state, turnAssistantId,
                extendedHandler, true /* deferredModifier */, agentOptions, assistantMessage);
            if (exec == null) {
                return new com.nexusai.application.agent.LlmAgentLoop.ToolRunOutcome(List.of(), java.util.Map.of(), java.util.Map.of());
            }
            for (ToolUseBlock call : toolCalls) {
                state.bindToolUseIdToAssistantId(call.id(), turnAssistantId);
                com.nexusai.application.agent.tool.ToolParent parent =
                    com.nexusai.application.agent.tool.ToolParent.of(turnAssistantId);
                // [REW-PROGRESS R32-03] onToolProgress 注入 · CC original:
                //   toolExecution.ts:550 createProgressMessage（tool.call progress 回调 →
                //   query.ts:1380-1387 yield update.message → runAgent.ts:792-805 yield progress）。
                //   子 agent 流式路径非 null（构造 SubagentMessage.ProgressMessage 发射 messageSink）；
                //   主循环 null（CC 主循环不 yield progress 给上层）。
                exec.add(call, parent, onToolProgress);
            }
            List<com.nexusai.application.agent.tool.ToolResult> results;
            try {
                // [DEC-2 / OPD-TOOL-EX-01] fallback 路径直接消费惰性 stream (增量实现核心),
                //   与 streaming 路径一致, 避免阻塞 allOf 一次性收集.
                results = exec.getRemainingResultsStream().toList();
            } catch (Exception e) {
                state.setError("tool execution failed: " + e.getMessage());
                state.setExitReason(AgentState.ExitReason.STREAM_ERROR);
                log.error("AgentLoopContext tool execution failed", e);
                exec.applyDeferredContextModifiers(perTurnTuc);
                return new com.nexusai.application.agent.LlmAgentLoop.ToolRunOutcome(List.of(),
                    java.util.Map.copyOf(state.assistantIdByToolUseId()), java.util.Map.of());
            }
            exec.applyDeferredContextModifiers(perTurnTuc);
            outcome = new com.nexusai.application.agent.LlmAgentLoop.ToolRunOutcome(results,
                java.util.Map.copyOf(state.assistantIdByToolUseId()),
                exec.getResultErrorFlags());
            if (log.isInfoEnabled()) {
                log.info("AgentLoopContext runTools [fallback]: results={} lineageSize={}",
                    outcome.results().size(), outcome.assistantIdByToolUseId().size());
            }
        }
        return outcome;
    }

    /**
     * [R32-b15 Stage 2 C4] 唯一顶层 runTools 入口封装 · static 化自 LlmAgentLoop#handleToolCallsTurn（P3-⑤）。
     *
     * <p>处理一个含 tool_calls 的 turn：append assistant + 跑工具 + append tool results。
     * 返回 "continue" 表示让外层 loop 继续。telemetryContext 取 per-turn TUC（A2 queryTracking
     * 已 stamp + isNonInteractiveSession 继承 base TUC）。工具隔离守卫从原 {@code toolRegistry == null}
     * 改为 {@code perTurnTuc.availableTools().isEmpty()}。
     */
    public static String handleToolCallsTurn(AgentLoopContext ctx,
            com.nexusai.application.agent.tool.ToolUseContext perTurnTuc,
            AgentState state,
            AssistantMessage msg,
            String assistantText,
            int chunkCount,
            String turnAssistantId,
            StreamingToolExecutor streamingExec,
            List<ToolUseBlock> seenToolCalls,
            QuerySource querySource,
            ThinkingConfig thinkingConfig,
            Map<String, PermissionResult.Allow> allowedDecisions,
            Map<String, ToolDecisionInfo> toolDecisions,
            java.util.function.Consumer<Tool.ToolProgress> onToolProgress,
            Long reasoningDurationMs,
            Long decodeMs) {
        if (perTurnTuc == null || perTurnTuc.availableTools().isEmpty()) {
            state.setError("assistant returned tool_calls but per-turn TUC has no availableTools");
            state.setExitReason(AgentState.ExitReason.STREAM_ERROR);
            log.error("AgentLoopContext: {}", state.lastError());
            return "exit";
        }
        Telemetry telemetry = ctx.toolExecutionBeans() != null ? ctx.toolExecutionBeans().telemetry() : null;
        // [R32-b12 D-4] 注入 toolDecisions 到 StreamingToolExecutor（decision_source/type 埋点）
        if (streamingExec != null && toolDecisions != null && !toolDecisions.isEmpty()) {
            streamingExec.setToolDecisions(toolDecisions);
        }

        boolean hasStructuredOutputCall = msg.toolCalls().stream()
            .anyMatch(call -> com.nexusai.application.agent.tool.ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME
                .equals(call.name()));
        boolean structuredOutputNonInteractive = false;
        if (hasStructuredOutputCall) {
            structuredOutputNonInteractive = perTurnTuc != null
                && perTurnTuc.isNonInteractiveSession();
            if (telemetry != null) {
                telemetry.logOTelEvent("tengu_structured_output_enabled",
                    Map.of("isNonInteractiveSession", structuredOutputNonInteractive));
            }
        }

        log.info("AgentLoopContext turn {} got {} tool calls",
            state.turnCount(), msg.toolCalls().size());

        // 1. Append assistant message WITH toolCalls
        List<com.nexusai.model.session.dto.ToolCallDto> toolCallDtos = new ArrayList<>(msg.toolCalls().size());
        for (ToolUseBlock call : msg.toolCalls()) {
            toolCallDtos.add(LlmAgentLoop.toolCallDto(call));
        }
        // [DEC-04] tool-call assistant 消息携带 provider usage · CC message.usage 逐消息透传
        //   （agentToolUtils.ts:238-256; mid-turn 退出时 extractUsageFromMessages 取本消息 usage）
        // [reasoningDurationMs] 工具轮 reasoning 直接取 msg.reasoning()（不传 reasoningBuf），
        //   计时值由外层 run() 算好经 handleToolCallsTurn 第 15 参传入（null = 无 reasoning）。
        state.appendMessage(LlmAgentLoop.assistantMessageWithToolCalls(
            assistantText, toolCallDtos, msg.reasoning(), turnAssistantId)
            .withUsage(msg.usage())
            // [V52 B3] cache 用量透传（S4-2b）：Tokens.Usage.of 压缩基线/估算取真实 cache
            .withUsageCache(
                msg.usage() != null && msg.usage().cacheReadInputTokens() != null
                    ? Math.toIntExact(msg.usage().cacheReadInputTokens()) : null,
                msg.usage() != null && msg.usage().cacheCreationInputTokens() != null
                    ? Math.toIntExact(msg.usage().cacheCreationInputTokens()) : null)
            .withReasoningDurationMs(reasoningDurationMs)
            // [B7-R9] 输出解码耗时 decodeMs 挂载（工具轮 assistant 消息；外层 run() 算好传入，null = 无计时）
            .withDecodeMs(decodeMs));
        // [usage-push] 工具轮 assistant 消息逐条 usage 实时推 + run 级累计（append withUsage 后立即；
        //   对齐 CC claude.ts:2244-2248 message.usage 写回 UI）。static 本方法在
        //   com.nexusai.application.agent.loop 包 → publishMessageUsage 需 public（LlmAgentLoop
        //   assistantMessageWithToolCalls 先例）。模型 = state.currentModel()；userMessageId 派生 =
        //   state.lastUserMessageId() ?? ctx.streamUserMessageId()（同 turnUserMessageId 推导，消息链优先）。
        com.nexusai.application.agent.LlmAgentLoop.publishMessageUsage(
            ctx, state,
            state.currentModel(),
            state.lastUserMessageId() != null ? state.lastUserMessageId() : ctx.streamUserMessageId(),
            turnAssistantId,
            msg, decodeMs);

        // 2. runTools 顶层入口（streaming 已 add / fallback 新建统一收集）
        com.nexusai.application.agent.LlmAgentLoop.ToolRunOutcome outcome;
        AgentOptions subagentOptions = LlmAgentLoop.buildSubagentAgentOptions(querySource, thinkingConfig);
        ForkSubagentMessages.Message forkAssistantMessage =
            LlmAgentLoop.toForkAssistantMessage(turnAssistantId, msg);
        if (streamingExec != null && streamingExec.size() > 0) {
            outcome = runTools(ctx, perTurnTuc, state, msg.toolCalls(), turnAssistantId, streamingExec,
                (er, id) -> ToolResultApplier.apply(er, state.messages(), state, id),
                subagentOptions, forkAssistantMessage, onToolProgress);
        } else {
            outcome = runTools(ctx, perTurnTuc, state, msg.toolCalls(), turnAssistantId, null,
                (er, id) -> ToolResultApplier.apply(er, state.messages(), state, id),
                subagentOptions, forkAssistantMessage, onToolProgress);
        }
        List<com.nexusai.application.agent.tool.ToolResult> results = outcome.results();
        // [IMP-C2] toolUseId → isError 透传（ToolResult 4 字段契约删除 isError，执行器推导）
        java.util.Map<String, Boolean> resultErrorFlags = outcome.resultErrorFlags();

        // [A2-P0-1 修复] 删除外层 PostToolUse / PostToolUseFailure hook 调用块。
        //   WHY: handleToolCallsTurn 的所有 results 都来自 runTools → StreamingToolExecutor
        //   (streaming / fallback 两条路径均消费 getRemainingResultsStream().toList()
        //    [DEC-2 / OPD-TOOL-EX-01], fallback 路径经 buildStreamingExecutor 新建 executor).
        //   内层 StreamingToolExecutor.executeAsync 已覆盖完整 hook 串联语义。外层重复触发导致
        //   PostToolUse 副作用 (logging/metrics/外部调用) 双发 + PostToolUseFailure 失败事件双发。
        //   对齐 CC toolExecution.ts:1483 runPostToolUseHooks 只在 tool-execution 层跑一次。
        //   行为等价性: 内层 mutation t.result → 惰性 stream drain 已透传, 外层 results.set 是冗余的。

        // ── s11.x: aborted_tools 检测 · 对齐 CC query.ts:1485-1515 ──
        if (state.cancelled()) {
            // [IMP-C2] completedIds 改用 resultErrorFlags 键（ToolResult 4 字段契约删除
            //   toolUseId；执行器已在错误路径推导 isError 并经 ToolRunOutcome 透传）。
            //   未完成工具的 synthetic error result 由执行器 getRemainingResults 在 drain 时
            //   补齐（createSyntheticErrorMessage/streaming_fallback）；本处不再 append 到
            //   results（append 会破坏结果-调用位置配对），只登记 missing 计数日志。
            java.util.Set<String> completedIds = resultErrorFlags == null
                ? new java.util.HashSet<>() : new java.util.HashSet<>(resultErrorFlags.keySet());
            int syntheticCount = 0;
            for (ToolUseBlock call : msg.toolCalls()) {
                if (!completedIds.contains(call.id())) {
                    syntheticCount++;
                }
            }
            if (syntheticCount > 0) {
                log.info("AgentLoopContext aborted_tools: {} 个未完成工具（synthetic error 由执行器补齐）",
                    syntheticCount);
            }
            // [OD-3/OD-12] 读活通道 AbortController.reason()（CC AbortSignal.reason 字符串）
            // 不再用死字段 state.abortReason()（AbortReason 枚举已删除）。
            String abortReasonStr = state.currentToolUseContext() != null
                ? state.currentToolUseContext().abortController().reason()
                : null;
            // [ER-IMP-12] 门翻转 · 对齐 CC query.ts:1501 signal.reason !== 'interrupt'
            //   除 submit-interrupt（CC 'interrupt'）外，所有中断统一附加
            //   createUserInterruptionMessage({toolUse:true}）· messages.ts:545-554。
            if (!LlmAgentLoop.isSubmitInterrupt(abortReasonStr)) {
                state.appendMessage(LlmAgentLoop.createUserInterruptionMessage(true));
                log.info("AgentLoopContext aborted_tools: reason={} → 附加 user interruption (for tool use) 消息 · CC query.ts:1501",
                    abortReasonStr);
            }
        }
        // 3. Append tool result messages（按 add 顺序）
        java.util.Map<String, String> toolNameById = new java.util.HashMap<>();
        for (ToolUseBlock call : msg.toolCalls()) {
            toolNameById.put(call.id(), call.name());
        }
        // [IMP-C2] 结果-调用配对改按 add 顺序（ToolResult 已删除 toolUseId 字段，组 2-1 拍板）：
        //   getRemainingResultsStream 按 add 顺序 yield 结果，与 msg.toolCalls() 同序配对。
        //   aborted_tools 路径在 results 尾部追加 synthetic error（对齐 CC query.ts:1485-1515，
        //   该路径 results 数量可能 > toolCalls，尾部 synthetic 无对应 call，跳过配对）。
        int resultIdx = 0;
        for (ToolUseBlock call : msg.toolCalls()) {
            if (resultIdx >= results.size()) {
                // [fix-toolcalls-400 B] 配对防御：执行器结果数 < tool_calls 数（如混合批里空参工具
                //   未被流式回调加入，见根因 1.1）时不再静默 break —— 为每个未覆盖 tool_call 生成
                //   synthetic error tool_result，保证每个 tool_call 都有 tool 响应。否则 state.messages()
                //   变 [assistant(N calls), tool(S<N results)] → OpenAI 400 "insufficient tool messages
                //   following tool_calls message"（对齐 CC yieldMissingToolResultBlocks query.ts:123-149，
                //   handleModelFallback:7368-7380 同款；turnAssistantId = CC sourceToolAssistantUUID 等价位）。
                log.warn("AgentLoopContext handleToolCallsTurn 配对缺口: toolCalls={} results={} → 对剩余 {} 个 tool_call 生成 synthetic error tool_result",
                    msg.toolCalls().size(), results.size(), msg.toolCalls().size() - resultIdx);
                for (int k = resultIdx; k < msg.toolCalls().size(); k++) {
                    ToolUseBlock orphan = msg.toolCalls().get(k);
                    state.appendMessage(LlmAgentLoop.toolResultMessage(
                        com.nexusai.application.agent.tool.ToolResult.error(orphan.id(), "Tool result missing"),
                        orphan.id(), true, null, turnAssistantId, null, List.of(), List.of(), Map.of()));
                    traceEmit(ctx, new com.nexusai.application.agent.diff.TraceEvent(
                        com.nexusai.application.agent.diff.TraceEvent.Kind.TOOL_RESULT,
                        orphan.name(), System.currentTimeMillis(),
                        java.util.Map.of("id", orphan.id(), "isError", true)));
                }
                break;
            }
            com.nexusai.application.agent.tool.ToolResult r = results.get(resultIdx++);
            String toolName = call.name();
            // [G2] 按 toolName 解析 Tool 实例（per-turn TUC 可用工具表 + alias 兜底），
            // toolResultMessage 经 per-tool mapToToolResultBlockParam 构造 tool_result 块
            // （对齐 CC toolExecution.ts:1292）；MCP/未命中 → null 走默认兜底。
            Tool tool = resolveToolForResult(perTurnTuc, toolName);
            String toolUseId = call.id();
            boolean isError = resultErrorFlags != null
                ? Boolean.TRUE.equals(resultErrorFlags.get(toolUseId)) : false;
            com.nexusai.application.agent.tool.ToolResult applied =
                applyToolResultBudget(ctx, state, r, toolName, toolUseId, isError, querySource);
            PermissionResult.Allow allowDecision = allowedDecisions == null
                ? null : allowedDecisions.get(toolUseId);
            // [IT-6] 载体注入：structuredOutput 仅作 ChatMessageDto 内部载体（DB/hook/outbound），
            // provider 不再序列化发模型；structured_output attachment 已在 ToolResultApplier 落地
            // state.attachments()（对齐 CC toolExecution.ts:1272-1279 附件通道）。
            Map<String, Object> structuredOutput = state.takeStructuredOutput(toolUseId);
            String parentAssistantId = turnAssistantId;
            if (turnAssistantId != null && state.assistantIdByToolUseId().containsKey(toolUseId)) {
                parentAssistantId = state.assistantIdByToolUseId().get(toolUseId);
            }
            ChatMessageDto toolResultMsg;
            if (allowDecision != null
                    && (allowDecision.acceptFeedback() != null
                        || (allowDecision.contentBlocks() != null
                            && !allowDecision.contentBlocks().isEmpty()))) {
                int imageCount = LlmAgentLoop.countImageBlocks(allowDecision.contentBlocks());
                List<String> imageIds = LlmAgentLoop.generateImagePasteIds(
                    LlmAgentLoop.computeNextImagePasteId(state.messages()), imageCount);
                toolResultMsg = LlmAgentLoop.toolResultMessage(applied,
                    toolUseId, isError,
                    tool,
                    parentAssistantId,
                    allowDecision.acceptFeedback(),
                    allowDecision.contentBlocks(),
                    imageIds,
                    structuredOutput);
            } else {
                toolResultMsg = LlmAgentLoop.toolResultMessage(applied,
                    toolUseId, isError,
                    tool,
                    parentAssistantId,
                    null, List.of(), List.of(),
                    structuredOutput);
            }
            // [IMP-WF3-TC-01] 工具结果 payload 附带 matchedRule（前端显示自动批准规则）·
            //   对齐 CC UserToolSuccessMessage.tsx:47-50 —— 渲染点读取 classifier 自动批准规则
            //   （bash matchedRule，StreamingToolExecutor.releaseClassifierApproval 已按
            //   toolUseId 暂存 AgentState.classifierMatchedRules）→ 附带 ChatMessageDto.matchedRule
            //   → 前端"已自动批准（规则X）"。null = 非 classifier 放行（无规则）。
            String matchedRule = state.takeClassifierMatchedRule(toolUseId);
            if (matchedRule != null) {
                toolResultMsg = toolResultMsg.withMatchedRule(matchedRule);
                if (log.isInfoEnabled()) {
                    log.info("TOOL result payload attached matchedRule: toolUseId={} matchedRule={}",
                        abbreviate(toolUseId, 24), matchedRule);
                }
            }
            state.appendMessage(toolResultMsg);
            // [fix-toolcalls-400 C] 该工具 newMessages 紧跟其 tool_result flush ·
            //   对齐 CC toolExecution.ts:1478 addToolResult 先 / :1566-1570 newMessages 后。
            //   工具执行期 (StreamingToolExecutor dispatch → ToolResultApplier.apply) 只把 newMessages
            //   暂存进 AgentState (stashNewMessages, 不立即 addAll); 此处 tool_result 落地后才 flush,
            //   保证 state.messages 顺序 = assistant(tool_calls) → tool(tool_result) → user(newMessages),
            //   否则 Read pdf pages 等 isMeta image user 消息夹在 assistant tool_calls 与 tool_result
            //   之间 → provider 原序透传 → Anthropic 400 "assistant message with tool_calls must be
            //   followed by tool messages"。无 newMessages 的工具 (多数) 缓存空, 本步无操作。
            flushNewMessagesAfterToolResult(state, toolUseId, toolName);
            traceEmit(ctx, new com.nexusai.application.agent.diff.TraceEvent(
                com.nexusai.application.agent.diff.TraceEvent.Kind.TOOL_RESULT,
                toolName, System.currentTimeMillis(),
                java.util.Map.of("id", toolUseId, "isError", isError)));
        }
        // [fix-toolcalls-400 C] 兜底 drain: aborted/synthetic/配对缺口 等边缘路径下, 工具执行期已暂存
        //   但未在本轮任一 tool_result 后 flush 的 newMessages, 在全部 tool_result 之后统一落盘,
        //   保证仍不夹在 assistant(tool_calls) 与 tool_result 之间 (且不跨 turn 泄漏)。
        if (state.hasPendingNewMessages()) {
            drainLeftoverNewMessages(state);
        }

        if (hasStructuredOutputCall) {
            // [IMP-C2] isError 由执行器在错误路径推导（ToolResult 4 字段契约）；SyntheticOutput
            //   失败判定改按结果-调用位置配对 + 结果 data 为 error 语义（schema 失败 →
            //   execute 返回 error result）。
            boolean structuredFailure = false;
            for (int i = 0; i < results.size() && i < msg.toolCalls().size(); i++) {
                com.nexusai.application.agent.tool.ToolResult r = results.get(i);
                ToolUseBlock call = msg.toolCalls().get(i);
                if (com.nexusai.application.agent.tool.ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME
                        .equals(call.name()) && r != null) {
                    Object d = r.data();
                    String ds = d instanceof String s ? s : String.valueOf(d);
                    // schema 失败 / 中断 → 判定为 structured failure（fail-loud 语义）
                    if (ds.startsWith("Output does not match") || ds.startsWith("StructuredOutput")
                        || ds.startsWith("unknown error") || ds.startsWith("Interrupted")) {
                        structuredFailure = true;
                        break;
                    }
                }
            }
            if (structuredFailure && telemetry != null) {
                telemetry.logOTelEvent("tengu_structured_output_failure",
                    Map.of("isNonInteractiveSession", structuredOutputNonInteractive));
            }
            int maxRetries = ctx.queryConfig() == null
                ? com.nexusai.application.agent.query.QueryConfig.DEFAULT_MAX_STRUCTURED_OUTPUT_RETRIES
                : ctx.queryConfig().maxStructuredOutputRetries();
            int structuredCalls = LlmAgentLoop.countStructuredOutputToolCalls(state);
            if (structuredFailure && maxRetries >= 0 && structuredCalls >= maxRetries) {
                String message = "Failed to provide valid structured output after "
                    + maxRetries + " attempts";
                state.setError("error_max_structured_output_retries: " + message);
                // [ER-IMP-01] ExitReason.MAX_RETRIES 删除（DC-12）→ MODEL_ERROR：
                //   CC query.ts:996 catch withRetry throw 后 return { reason: 'model_error' }。
                //   TODO(ER-IMP-02)：若后续引入 CannotRetryError 需复查本映射。
                state.setExitReason(AgentState.ExitReason.MODEL_ERROR);
                log.error("StructuredOutput 重试次数达到上限: calls={} max={}",
                    structuredCalls, maxRetries);
                return "exit";
            }
        }

        state.setFinishReason("tool_calls");
        publishEvent(ctx, new com.nexusai.application.agent.event.AgentTurnCompletedEvent(
            state, state.turnCount(), chunkCount, assistantText.length(), "tool_calls"));

        log.info("TOOL batch done: {} calls · {} results", msg.toolCalls().size(), results.size());

        // ── A6: 工具用量 Haiku 摘要（对齐 CC query.ts:1411 pendingToolUseSummary）──
        if (isEmitToolUseSummariesEnabled(ctx)) {
            try {
                generateToolUseSummaryAsync(ctx, state, msg.toolCalls())
                    .thenAccept(summary -> log.debug("[A6 tool_use_summary] async result: turn={} total={}",
                        summary != null ? summary.turnCount() : -1,
                        summary != null ? summary.totalCalls() : 0))
                    .exceptionally(e -> {
                        log.warn("[A6 tool_use_summary] failed: {}", e.getMessage());
                        return null;
                    });
            } catch (Exception e) {
                log.warn("[A6 tool_use_summary] trigger failed: {}", e.getMessage());
            }
        }

        if (!state.needsFollowUp()) {
            state.markNeedsFollowUp();
        }
        return "continue";
    }

    /**
     * [fix-toolcalls-400 C] 单个工具 {@code tool_result} 落地后 flush 其暂存 newMessages ·
     * 对齐 CC toolExecution.ts:1478 addToolResult 先 / :1566-1570 newMessages 后。
     *
     * <p>newMessages（Read pdf pages isMeta image user 消息 / SkillTool 指令 / hook 普通消息 /
     * permission retry isMeta 消息等）由工具执行期 {@code ToolResultApplier.apply} 按 toolUseId
     * 暂存进 AgentState（不立即 addAll），此处取出并追加到 state.messages 尾 —— 顺序保证
     * assistant(tool_calls) → tool(tool_result) → user(newMessages)。无 newMessages 的工具
     * （多数）取到空 List，本方法 no-op。
     *
     * <p><b>落盘通道</b>: 直接 {@code state.messages().addAll}（与旧 {@code ToolResultApplier.apply}
     * 时期一致 —— newMessages 不经 {@code appendMessage} 监听器；tool_result 消息本身才走
     * appendMessage）。行为差异仅<b>顺序</b>后移，跨 turn 持久/next-turn 投影（SnipTool boundary）
     * 语义不变。
     */
    private static void flushNewMessagesAfterToolResult(AgentState state, String toolUseId, String toolName) {
        if (state == null || toolUseId == null) return;
        List<com.nexusai.model.session.dto.ChatMessageDto> pending = state.takeNewMessages(toolUseId);
        if (pending != null && !pending.isEmpty()) {
            state.messages().addAll(pending);
            if (log.isDebugEnabled()) {
                log.debug("TOOL newMessages flush after tool_result: toolName={} id={} count={} · CC toolExecution.ts:1478 addToolResult 先 / :1566 newMessages 后",
                    toolName, abbreviate(toolUseId, 24), pending.size());
            }
        }
    }

    /**
     * [fix-toolcalls-400 C] 兜底 drain：step 3 主循环结束后仍有未配对的暂存 newMessages（aborted /
     * synthetic / 配对缺口等边缘路径）→ 在全部 tool_result 之后统一落盘。
     *
     * <p>WHY: 即使个别工具没有对应 tool_result 配对，其 newMessages 也不能跨 turn 泄漏在暂存 map
     * 里；统一追加到末尾仍满足「newMessages 不夹在 assistant(tool_calls) 与 tool_result 之间」
     * 的 provider 顺序约束（此时 tool_result 已全部落地）。map 迭代序无依赖 —— 仅取剩余集合追加。
     */
    private static void drainLeftoverNewMessages(AgentState state) {
        if (state == null) return;
        List<com.nexusai.model.session.dto.ChatMessageDto> leftovers = new java.util.ArrayList<>();
        // 先快照 key（避免迭代同时 take 从并发 map 移除的边迭代边删风险；CHM 弱一致迭代虽不抛
        // CME，但快照更稳）。map 迭代序无依赖 —— 仅取剩余集合追加。
        java.util.List<String> pendingIds = new java.util.ArrayList<>(
            state.pendingNewMessagesByToolUseId().keySet());
        for (String toolUseId : pendingIds) {
            List<com.nexusai.model.session.dto.ChatMessageDto> msgs = state.takeNewMessages(toolUseId);
            if (msgs != null) leftovers.addAll(msgs);
        }
        if (!leftovers.isEmpty()) {
            state.messages().addAll(leftovers);
            if (log.isDebugEnabled()) {
                log.debug("TOOL newMessages leftover drain (无 tool_result 配对): count={}", leftovers.size());
            }
        }
    }

    /**
     * [G2] 按 toolName 解析 Tool 实例 · 对齐 CC {@code findToolByName}（Tool.ts:355-360）。
     *
     * <p>从 per-turn TUC 的 availableTools 按 name 精确匹配，再按 {@link Tool#aliases()} 兜底
     * （与 {@link ToolRegistry#get} 双路径语义一致）。未命中（MCP 动态工具 / TUC null）→ null，
     * 调用方走默认 mapper 兜底，不阻塞工具结果消息构造。
     *
     * @param perTurnTuc per-turn 工具调用上下文（可 null）
     * @param toolName   工具名（LLM tool_use 的 name）
     * @return 命中的 Tool 实例；未命中 → null
     */
    private static Tool resolveToolForResult(
            com.nexusai.application.agent.tool.ToolUseContext perTurnTuc, String toolName) {
        if (perTurnTuc == null || perTurnTuc.availableTools() == null || toolName == null) {
            return null;
        }
        for (Tool t : perTurnTuc.availableTools()) {
            if (toolName.equals(t.name())) {
                return t;
            }
            java.util.List<String> aliases = t.aliases();
            if (aliases != null && aliases.contains(toolName)) {
                return t;
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("AgentLoopContext: 未在 per-turn 可用工具表命中 {}（MCP/动态工具 → mapper 走默认兜底）",
                toolName);
        }
        return null;
    }

    /** emitToolUseSummaries gate · static 化自 LlmAgentLoop#isEmitToolUseSummariesEnabled。 */
    private static boolean isEmitToolUseSummariesEnabled(AgentLoopContext ctx) {
        if (ctx.queryConfig() == null || ctx.queryConfig().gates() == null) return false;
        return ctx.queryConfig().gates().emitToolUseSummaries();
    }

    /** 工具用量 Haiku 摘要 async · static 化自 LlmAgentLoop#generateToolUseSummaryAsync。 */
    private static java.util.concurrent.CompletableFuture<com.nexusai.application.agent.LlmAgentLoop.ToolUseSummary>
            generateToolUseSummaryAsync(AgentLoopContext ctx, AgentState state, List<ToolUseBlock> seenToolCalls) {
        if (seenToolCalls == null || seenToolCalls.isEmpty()) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            java.util.Map<String, Integer> byTool = new java.util.LinkedHashMap<>();
            for (ToolUseBlock call : seenToolCalls) {
                byTool.merge(call.name(), 1, Integer::sum);
            }
            String semanticSummary = null;
            try {
                semanticSummary = generateToolUseSemanticSummaryWithHaiku(ctx, state, seenToolCalls, byTool);
            } catch (Exception e) {
                log.warn("[R24-5 Haiku summary] failed: {}", e.getMessage());
            }
            log.info("[R24-5 A6 tool_use_summary] turn={} tools={} total={} haikuSummary={}",
                state.turnCount(), byTool.size(), seenToolCalls.size(),
                semanticSummary != null ? "yes" : "no");
            return new com.nexusai.application.agent.LlmAgentLoop.ToolUseSummary(state.turnCount(),
                byTool, seenToolCalls.size(), System.currentTimeMillis(), semanticSummary);
        });
    }

    /** Haiku 语义摘要 · static 化自 LlmAgentLoop#generateToolUseSemanticSummaryWithHaiku（ctx.llmProviderFactory）。 */
    private static String generateToolUseSemanticSummaryWithHaiku(AgentLoopContext ctx, AgentState state,
                                                                  List<ToolUseBlock> toolCalls,
                                                                  java.util.Map<String, Integer> byTool) {
        if (ctx.llmProviderFactory() == null) return null;
        StringBuilder prompt = new StringBuilder();
        prompt.append("请用 1-2 句话总结本轮工具调用, 重点说明高频工具的可能原因:\n");
        prompt.append("工具调用次数统计: ");
        byTool.forEach((name, count) -> prompt.append(name).append("=").append(count).append(" "));
        prompt.append("\n调用顺序: ");
        for (int i = 0; i < toolCalls.size() && i < 20; i++) {
            prompt.append(toolCalls.get(i).name()).append(",");
        }
        prompt.append("\n\n只输出 1-2 句中文摘要, 不要前缀. ");
        if (state != null) {
            prompt.append("\n当前 turn=").append(state.turnCount());
        }
        // [RV14B-WIRE-04] 真实配置解析：fast 模型名 → DB 名 → (config, providerType)；
        //   解析失败 → 返回 null（warn+skip 不落 mock，对齐 CC queryHaiku 失败即无结果）。
        com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolved = resolveHaikuModelConfig(ctx);
        if (resolved == null || resolved.config() == null || !resolved.config().isUsable()) {
            log.warn("[R24-5 Haiku summary] 模型配置解析失败，跳过（warn+skip 不落 mock，RV14B-GATE-01）");
            return null;
        }
        String modelName = resolveHaikuModelName(ctx);
        try {
            return ctx.llmProviderFactory().getProvider(resolved.config(), resolved.providerType()).chat(
                resolved.config(),
                modelName,
                "你是代码助手统计专家. 用一句话总结高频工具的使用模式.",
                prompt.toString()
            );
        } catch (Exception e) {
            return null;
        }
    }

    /** 单条工具结果按 CC persistence 语义处理 · static 化自 LlmAgentLoop#applyToolResultBudget。
     *  [IMP-C2] toolUseId/isError 由调用方从调用块推导透传（ToolResult 4 字段契约，组 2-1 拍板）。 */
    private static com.nexusai.application.agent.tool.ToolResult<?> applyToolResultBudget(AgentLoopContext ctx,
            AgentState state, com.nexusai.application.agent.tool.ToolResult<?> r, String toolName,
            String toolUseId, boolean isError, QuerySource querySource) {
        // [A1·退役 content()/metadata()] 只对 String data 做 budget 持久化
        // (CC toolResultStorage persist 的 content 是 stringified data; 结构化 data<T> 非 String 时跳过 budget)
        // [merge] data() 泛型化下按 CC toolResultStorage 语义收窄：CC enforceToolResultBudget (Open-ClaudeCode/src/utils/toolResultStorage.ts:924) 作用于 string content；结构化 data<T> 非 String 跳过 budget。Java 侧 instanceof 收窄，逻辑等价 CC 字符串工具结果处理。
        if (r == null || !(r.data() instanceof String dc0)) {
            return r;
        }
        if (dc0.trim().isEmpty()) {
            log.debug("[R28-3.7 §1.7] empty tool result: toolName={} toolUseId={}", toolName, toolUseId);
            return new com.nexusai.application.agent.tool.ToolResult<>(
                "(" + toolName + " completed with no output)", null, null, null);
        }
        int declaredMax = getDeclaredMaxResultSize(ctx, toolName);
        int threshold = com.nexusai.application.agent.tool.ToolResultStorage.getPersistenceThreshold(
            toolName, declaredMax);
        if (dc0.length() <= threshold) {
            return r;
        }
        ContentReplacementState contentReplacementState = ctx.sessionState().contentReplacementState();
        if (declaredMax == Integer.MAX_VALUE) {
            log.debug("[R28-3.8 §1.5] skipToolNames: toolName={} maxResultSizeChars=Infinity, mark seen only",
                toolName);
            contentReplacementState.markSeen(toolUseId);
            return r;
        }
        if (contentReplacementState.isSeen(toolUseId)) {
            String cached = contentReplacementState.getReplacement(toolUseId);
            if (cached != null) {
                return new com.nexusai.application.agent.tool.ToolResult<>(cached, null, null, null);
            }
        }
        contentReplacementState.markSeen(toolUseId);
        String sessionId = state.sessionId() != null ? state.sessionId() : "default";
        com.nexusai.application.agent.tool.ToolResultStorage.PersistedToolResult persisted;
        try {
            persisted = com.nexusai.application.agent.tool.ToolResultStorage.persistToolResult(
                ctx.sessionState().workspaceDir(), sessionId, dc0, toolUseId).join();
        } catch (Exception e) {
            log.warn("[R28-3.10] persist join failed: toolUseId={} error={}", toolUseId, e.toString());
            return r;
        }
        if (persisted == null) {
            log.warn("[R28-3.10] persistence failed for toolUseId={} — model sees full content", toolUseId);
            return r;
        }
        String preview = com.nexusai.application.agent.tool.ToolResultStorage
            .buildLargeToolResultMessage(persisted);
        contentReplacementState.recordReplacement(toolUseId, preview);
        if (LlmAgentLoop.shouldPersistReplacements(querySource)) {
            String agentIdStr = state.agentId() != null ? state.agentId().toString() : null;
            com.nexusai.application.agent.tool.SessionStorage.writeContentReplacement(
                ctx.sessionState().workspaceDir(), sessionId, agentIdStr, toolUseId, preview);
        }
        log.info("[R28-3] tool_result persisted: toolUseId={} path={} size={} querySource={}",
            toolUseId, persisted.filepath(), persisted.originalSize(), querySource);
        return new com.nexusai.application.agent.tool.ToolResult<>(preview, null, null, null);
    }

    /** getDeclaredMaxResultSize · static 化自 LlmAgentLoop（ctx.toolRegistry() 查表，null-safe 兜底 DEFAULT）。 */
    private static int getDeclaredMaxResultSize(AgentLoopContext ctx, String toolName) {
        if (ctx.toolRegistry() == null || toolName == null) {
            return com.nexusai.application.agent.tool.ToolResultStorage.DEFAULT_MAX_RESULT_SIZE_CHARS;
        }
        try {
            return ctx.toolRegistry().get(toolName)
                .map(t -> (int) Math.min(t.maxResultSizeChars(), Integer.MAX_VALUE))
                .orElse(com.nexusai.application.agent.tool.ToolResultStorage.DEFAULT_MAX_RESULT_SIZE_CHARS);
        } catch (Exception e) {
            return com.nexusai.application.agent.tool.ToolResultStorage.DEFAULT_MAX_RESULT_SIZE_CHARS;
        }
    }

    /**
     * [Session H10 · 对抗核验修复] async hook 响应注入 · 对齐 CC {@code getAsyncHookResponseAttachments}
     * (attachments.ts:3465) + {@code async_hook_response} 消息转换 (messages.ts:4026-4043).
     *
     * <p><b>WHY (GAP-修复)</b>: 对抗核验发现 async hook 响应<b>生产无消费方</b> — CC 主线程每 LLM 调用前
     * 经 {@code getAsyncHookResponseAttachments()} 调 {@code checkForAsyncHookResponses()} 并把响应
     * 转成 user message (systemMessage / hookSpecificOutput.additionalContext) 注入上下文; Java 端
     * HookRegistry 不再引用 AsyncHookRegistry → 响应静默丢失. 本方法补齐生产交付环路: 每轮 drain
     * {@link HookRegistry#collectAsyncHookResponses()} 并把每条响应 (systemMessage +
     * additionalContext) 注入 messagesForLlm 队首 (对齐 CC createUserMessage isMeta:true).
     *
     * <p>行为契约:
     * <ul>
     *   <li>hookRegistry 未接线 (null) → 原样返回 (不破坏老路径)</li>
     *   <li>无待交付响应 → 原样返回 (零行为变化)</li>
     *   <li>有响应 → 每条 (systemMessage / additionalContext) 各生成一条 user-role 元消息 prepend</li>
     * </ul>
     *
     * @param ctx            loop 基础设施 (hookRegistry 承载 AsyncHookRegistry 引用)
     * @param state          AgentState (供 appendAttachment 记录交付)
     * @param messagesForLlm 当前 LLM 请求消息列表
     * @return 注入后的消息列表; 无注入 → 原引用
     */
    public static List<ChatMessageDto> maybeInjectAsyncHookResponses(AgentLoopContext ctx, AgentState state,
                                                                     List<ChatMessageDto> messagesForLlm) {
        if (ctx == null || ctx.hookRegistry() == null) {
            return messagesForLlm;
        }
        List<AsyncHookRegistry.AsyncHookResponse> responses;
        try {
            responses = ctx.hookRegistry().collectAsyncHookResponses();
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("HOOK async hook 响应采集异常, 本轮跳过注入: {}", e.toString());
            }
            return messagesForLlm;
        }
        if (responses == null || responses.isEmpty()) {
            return messagesForLlm;
        }
        List<ChatMessageDto> withResponses = new ArrayList<>(messagesForLlm);
        for (AsyncHookRegistry.AsyncHookResponse r : responses) {
            // [prompt-align CTX-04] CC messages.ts:4026-4043：先 push systemMessage 再 push
            //   additionalContext → 顺序 [systemMessage, additionalContext]；return
            //   wrapMessagesInSystemReminder(messages) 每条包 `<system-reminder>\n...\n</system-reminder>`
            //   （:3097-3100 wrapInSystemReminder）。旧实现两条 add(0,..) 反转顺序 → 收局部 list
            //   按 CC 顺序整体插队首（addAll(0, asyncMsgs) 保持 systemMessage→additionalContext）。
            HookJSONOutput.SyncHookOutput sync = r.response();
            List<ChatMessageDto> asyncMsgs = new ArrayList<>(2);
            if (sync != null && sync.systemMessage() != null && !sync.systemMessage().isBlank()) {
                asyncMsgs.add(metaUserMessage("<system-reminder>\n" + sync.systemMessage() + "\n</system-reminder>"));
            }
            // CC messages.ts:4030: 'additionalContext' in hookSpecificOutput → user message
            String additionalContext = additionalContextOf(sync);
            if (additionalContext != null && !additionalContext.isBlank()) {
                asyncMsgs.add(metaUserMessage("<system-reminder>\n" + additionalContext + "\n</system-reminder>"));
            }
            withResponses.addAll(0, asyncMsgs);
            // 记录交付 (对齐 CC createAttachmentMessage({type:'async_hook_response'}))
            if (state != null) {
                state.appendAttachment(new com.nexusai.application.agent.attachment.AttachmentMessageDto(
                    null, "attachment", "async_hook_response",
                    (sync != null ? sync.systemMessage() : null),
                    null, null, null,
                    r.hookName(), null, r.hookEvent(), null));
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("HOOK async hook 响应注入 {} 条响应 → messagesForLlm", responses.size());
        }
        return withResponses;
    }

    /**
     * [Session H8 v2 对抗核验修复] hook attachment → LLM 上下文注入 · 对齐 CC
     * {@code utils/messages.ts normalizeAttachmentForAPI:4090-4136}.
     *
     * <p>WHY (修复对抗核验缺口): H8 交付只完成了<b>生产者侧</b> — StreamingToolExecutor 把
     * AHR.message()/additionalContext/blockingError/stoppedContinuation 转成
     * {@code hook_user_message/hook_additional_context/hook_blocking_error/
     * hook_stopped_continuation} attachment 存入 {@code state.attachments()}, 但消费者侧
     * (LlmAgentLoop 组装 messagesForLlm) 从不读取 attachments → 这些 hook 附件实际到不了 LLM,
     * "不再只写不读 / LLM 可见" 停止条件只满足一半. CC 端 hook attachment 是 transcript 内的
     * AttachmentMessage, 每次 LLM 调用经 normalizeAttachmentForAPI 渲染为 isMeta user message
     * 注入上下文 (attachments 常驻 transcript 每轮重渲染); 本方法等价: 每轮把
     * {@code state.attachments()} 中 LLM 可见的 hook_* attachment 渲染为 meta user message
     * 注入 messagesForLlm <b>队尾 (push tail)</b>.
     *
     * <p><b>[IMP-ST-02 TC-04] 送达位置对齐 CC push tail (OPD-TC-04 对齐)</b>:
     * CC hook 附件经 {@code toolExecution.ts:1483-1587} 的 {@code hookResults} 在
     * {@code resultingMessages} <b>末尾</b> flush (isMcpTool → addToolResult 之后;
     * toolHooks.ts:146-151 updatedMCPToolOutput 最后 yield) —— hook 附件在<b>工具结果之后</b>送达.
     * Java 旧实现 {@code prepend 队首} (附件在 tool_result 之前) 与 CC 可观测相对序相反
     * (X-PROBE EV-XP-W3-021/024). 改为 {@code append 队尾} 对齐 CC.
     *
     * <p>CC 渲染映射 (messages.ts):
     * <ul>
     *   <li>hook_user_message (Java 特有, CC result.message → 普通 user message) → <b>不走
     *       attachment 渲染</b>: 两端生产已改普通消息通道结算 (PreToolUse → newMessages;
     *       非 PreToolUse → state.messages() 一次性 user 消息), 渲染 case 已删除 (下 :2175)</li>
     *   <li>hook_blocking_error (:4090-4097) → "{hookName} hook blocking error: {content}"
     *       (CC 含 command 字段, Java AttachmentMessageDto 未承载 → 仅 error 文本)</li>
     *   <li>hook_stopped_continuation (:4130-4136) → "{hookName} hook stopped continuation: {content}"</li>
     *   <li>hook_additional_context (:4117-4128, content 非空) → "{hookName} hook additional context: {content}"</li>
     *   <li>hook_success (:4099-4115, 仅 SessionStart/UserPromptSubmit + content 非空) →
     *       "{hookName} hook success: {content}"</li>
     *   <li>[P1-6-READ-2] invoked_skills (:3644-3662, skills 非空) → per-skill
     *       {@code ### Skill: name\nPath: path\n\ncontent} join {@code \n\n---\n\n} +
     *       前导文本 + {@code <system-reminder>} 包裹;skills 空 → 不注入 (CC return [])</li>
     *   <li>hook_cancelled / hook_error_during_execution / hook_non_blocking_error /
     *       hook_system_message / hook_permission_decision (:4255-4260) → <b>不注入</b> (CC 返回 [])</li>
     * </ul>
     *
     * <p><b>不处理 async_hook_response</b>: 该类型由 {@link #maybeInjectAsyncHookResponses} 从
     * registry drain 注入 (fresh 消费), attachments 中的 async_hook_response 仅是交付记录,
     * 此处渲染会双发.
     *
     * @param ctx           上下文 (本方法不使用, 保持与 maybeInjectAsyncHookResponses 签名一致)
     * @param state         AgentState (供读取 state.attachments())
     * @param messagesForLlm 当前 LLM 请求消息列表
     * @return 注入后的消息列表; 无 LLM 可见 hook attachment → 原引用
     */
    public static List<ChatMessageDto> maybeInjectHookAttachments(AgentLoopContext ctx, AgentState state,
                                                                  List<ChatMessageDto> messagesForLlm) {
        if (state == null || state.attachments().isEmpty()) {
            return messagesForLlm;
        }
        List<String> renderedTexts = new ArrayList<>();
        for (AttachmentMessageDto a : state.attachments()) {
            if (a == null) {
                continue;
            }
            // [WF6 R1] 跳过 plan_mode/plan_mode_reentry/plan_mode_exit：三者由
            // maybeInjectPlanModeAttachments 专用注入路径每 tool 轮渲染（含节流 + full/sparse 周期），
            // 此处每轮 hook 重渲染会破坏 TURNS_BETWEEN_ATTACHMENTS=5 节流 + 双发。
            // 注意：plan_file_reference 不在跳过名单——它由 compact 重建链写入 state.attachments()，
            // 经本方法渲染为 system-reminder（对齐 CC compact.ts:545-548 createPlanAttachmentIfNeeded）。
            if (isPlanModeAttachmentType(a.type())) {
                continue;
            }
            // [CTX-10] 跳过 hook_stopped_continuation：终止信号跨 turn 不得由本方法重渲染注入
            //   （CC query.ts:1519-1520 shouldPreventContinuation → 同 query 内渲染结果随 toolResults
            //   丢弃，永不送达后续 LLM 调用；Java attachments() 跨 loop 常驻，若此处注入会把「终止」
            //   误当「续行」——ER-IMP-09 本应修复的'渲染注入继续'）。终止已由
            //   LlmAgentLoop.hasHookStoppedContinuation + ExitReason.HOOK_STOPPED 承载（:6408/:8488），
            //   renderHookAttachmentForLlm 的 case 产文本仅形状对齐 CC（messages.ts:4130-4136），
            //   此处 skip 保证其永不被 LLM 消费。
            if ("hook_stopped_continuation".equals(a.type())) {
                continue;
            }
            String text = renderHookAttachmentForLlm(a);
            if (text != null) {
                renderedTexts.add(text);
            }
        }
        if (renderedTexts.isEmpty()) {
            return messagesForLlm;
        }
        // CC hook attachment 常驻 transcript 每轮重渲染 → 本轮全部注入
        // [IMP-ST-02 TC-04] 送达位置对齐 CC push tail (OPD-TC-04 对齐):
        //   CC hook 附件经 hookResults → resultingMessages 末尾 flush (toolExecution.ts:1585-1587),
        //   在 addToolResult(tool_result) 之后送达; Java 旧实现 prepend 队首 (附件在 tool_result
        //   之前) 与 CC 可观测相对序相反 (X-PROBE EV-XP-W3-021/024). 改为 append 队尾.
        List<ChatMessageDto> hookMessages = new ArrayList<>(renderedTexts.size());
        for (String text : renderedTexts) {
            hookMessages.add(metaUserMessage(text));
        }
        List<ChatMessageDto> withHooks = new ArrayList<>(messagesForLlm.size() + hookMessages.size());
        withHooks.addAll(messagesForLlm);
        withHooks.addAll(hookMessages);
        if (log.isDebugEnabled()) {
            log.debug("HOOK hook attachment 注入 {} 条 → messagesForLlm (CC normalizeAttachmentForAPI)",
                renderedTexts.size());
        }
        return withHooks;
    }

    /**
     * [Batch2 B1] 每轮 LLM 调用前注入 leader inbox 未读 teammate 消息 · 对齐 CC
     * attachments.ts:959-960 {@code maybe('teammate_mailbox', getTeammateMailboxAttachments)} +
     * getTeammateMailboxAttachments :3614-3796。
     *
     * <p><b>WHY</b>：teammate→leader 消息无 attachment 注入队长 LLM loop —— leader 看不到队友回复
     * （探查 B1 P0 断链）。leader（isTeamLead）读自身 inbox 未读消息（过滤结构化协议消息 + idle 折叠），
     * 渲染为 {@code <teammate-message>} XML 的 meta user message 追加 messagesForLlm 队尾，
     * <b>构建后标已读</b>（build before mark read，attachments.ts:3758-3796）——使 leader 下一轮
     * LLM query 能看到 teammate 回复且不重复注入。
     *
     * <p><b>动态注入不持久化</b>（Batch2 设计决策）：每 query 读 inbox + 标已读（对齐 CC），避免
     * 经 {@code maybeInjectHookAttachments} 每轮重渲染同一 teammate 消息造成上下文重复膨胀；
     * transcript 持久化归 Batch4 A4/A5 会话级化。
     *
     * <p><b>会话隔离</b>：leader 判定基于 appState.teamContext（会话级），不依赖
     * {@link TaskSystemConfig} sysprop（teammate 上下文会污染 sysprop）。
     *
     * @param ctx            loop 基础设施（未使用，保持签名一致）
     * @param state          AgentState（未使用，保持签名一致）
     * @param tuc            本轮 per-turn ToolUseContext（appState 读 teamContext；可为 null → 跳过）
     * @param messagesForLlm 当前 LLM 请求消息列表
     * @return 注入后的消息列表；门控未过 / inbox 空 → 原引用
     */
    public static List<ChatMessageDto> maybeInjectTeammateMailbox(AgentLoopContext ctx, AgentState state,
            com.nexusai.application.agent.tool.ToolUseContext tuc,
            List<ChatMessageDto> messagesForLlm) {
        // 门控 1：agent-swarms 关闭 → 原样（CC attachments.ts:3617-3619 isAgentSwarmsEnabled）
        if (!TaskSystemConfig.isAgentSwarmsEnabled()) {
            return messagesForLlm;
        }
        // 门控 2：无 per-turn TUC / 无 appState → 原样（读不到 teamContext）
        if (tuc == null || tuc.getAppState() == null) {
            return messagesForLlm;
        }
        // 门控 3：in-process teammate 上下文 → 原样（防 teammate loop 误读 leader inbox，
        //   对齐 CC attachments.ts:3690-3692 isInProcessTeammate() 守卫 —— teammate 只经
        //   文件 mailbox + waitForNextPromptOrShutdown 收消息）
        if (com.nexusai.application.agent.team.TeammateContext.getTeammateContext() != null) {
            return messagesForLlm;
        }
        try {
            Map<String, Object> appState = tuc.getAppState().apply(null);
            if (appState == null) {
                return messagesForLlm;
            }
            Object tcObj = appState.get("teamContext");
            if (!(tcObj instanceof Map<?, ?> teamContext)) {
                return messagesForLlm;
            }
            Object nameObj = teamContext.get("teamName");
            if (!(nameObj instanceof String teamName) || teamName.isBlank()) {
                return messagesForLlm;
            }
            // leader 判定（对齐 CC isTeamLead，permissionSync.ts:581-591：agentName null/blank/'team-lead'
            //   → leader；主 loop 即 leader）
            if (!com.nexusai.application.agent.team.SwarmPermissionSync.isTeamLeader(teamName)) {
                return messagesForLlm;
            }
            // agentName = 读 team 配置 lead 成员 name（SwarmPermissionSync.getLeaderName，兼容未来改名）
            //   兜底 'team-lead'（CC attachments.ts:3643-3647 leadAgentId 查 teammates map 兜底 'team-lead'）
            String agentName = com.nexusai.application.agent.team.SwarmPermissionSync.getLeaderName(teamName);
            if (agentName == null || agentName.isBlank()) {
                agentName = com.nexusai.infra.util.SwarmConstants.TEAM_LEAD_NAME;
            }
            List<com.nexusai.application.agent.team.TeammateMailbox.TeammateMessage> all =
                com.nexusai.application.agent.team.TeammateMailbox.readUnreadMessages(agentName, teamName);
            // 过滤结构化协议消息（对齐 CC attachments.ts:3673-3675 —— permission_request 等留给
            //   useInboxPoller 路由，防附件生成竞速吞掉 handler 消息）
            List<com.nexusai.application.agent.team.TeammateMailbox.TeammateMessage> unread = all.stream()
                .filter(m -> !com.nexusai.application.agent.team.TeammateMailbox.isStructuredProtocolMessage(m.text()))
                .toList();
            if (unread.isEmpty()) {
                return messagesForLlm;
            }
            // idle 折叠：同 agent 多条 idle_notification 只保留最新（对齐 CC attachments.ts:3726-3747）
            List<com.nexusai.application.agent.team.TeammateMailbox.TeammateMessage> collapsed =
                collapseIdleNotifications(unread);
            if (collapsed.isEmpty()) {
                return messagesForLlm;
            }
            // 渲染 + 注入队尾（对齐 CC messages.ts:3847-3857 createUserMessage({content:
            //   formatTeammateMessages(...), isMeta:true})，不包 system-reminder）
            String content = com.nexusai.application.agent.team.TeammateMailbox.formatTeammateMessages(collapsed);
            List<ChatMessageDto> withMailbox = new ArrayList<>(messagesForLlm);
            withMailbox.add(metaUserMessage(content));
            // 构建后标已读：仅非结构化消息（对齐 CC attachments.ts:3769-3796
            //   markMessagesAsReadByPredicate(agentName, m => !isStructuredProtocolMessage(m.text), teamName)）
            com.nexusai.application.agent.team.TeammateMailbox.markMessagesAsReadByPredicate(agentName,
                m -> !com.nexusai.application.agent.team.TeammateMailbox.isStructuredProtocolMessage(m.text()),
                teamName);
            if (log.isDebugEnabled()) {
                log.debug("TEAMMATE_MBOX 注入 {} 条 teammate 消息 → messagesForLlm (CC getTeammateMailboxAttachments)",
                    collapsed.size());
            }
            return withMailbox;
        } catch (Exception e) {
            // 注入失败不阻断 LLM 调用（fail loud 仅 log.warn，CC 附件生成失败不抛 → 不阻断 query）
            log.warn("TEAMMATE_MBOX 注入 teammate 消息失败（跳过本轮）: {}", e.toString());
            return messagesForLlm;
        }
    }

    /**
     * 同 agent idle_notification 折叠 · 对齐 CC attachments.ts:3726-3747：单遍解析
     * {@code isIdleNotification}，同一 agent 多条仅保留最新（其余过滤掉）。
     */
    private static List<com.nexusai.application.agent.team.TeammateMailbox.TeammateMessage> collapseIdleNotifications(
            List<com.nexusai.application.agent.team.TeammateMailbox.TeammateMessage> messages) {
        Map<Integer, String> idleAgentByIndex = new java.util.LinkedHashMap<>();
        Map<String, Integer> latestIdleByAgent = new java.util.LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            com.nexusai.application.agent.team.TeammateMailbox.IdleNotificationMessage idle =
                com.nexusai.application.agent.team.TeammateMailbox.isIdleNotification(messages.get(i).text());
            if (idle != null) {
                idleAgentByIndex.put(i, idle.from());
                latestIdleByAgent.put(idle.from(), i);
            }
        }
        if (idleAgentByIndex.size() <= latestIdleByAgent.size()) {
            return messages;
        }
        List<com.nexusai.application.agent.team.TeammateMailbox.TeammateMessage> collapsed =
            new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            String agent = idleAgentByIndex.get(i);
            if (agent == null || latestIdleByAgent.get(agent).equals(i)) {
                collapsed.add(messages.get(i));
            }
        }
        return collapsed;
    }

    /**
     * [WF6 RC-2] tool 轮 plan_mode 附件注入 · 对齐 CC {@code getAttachmentMessages}
     * （utils/attachments.ts:881-882）的 {@code maybe('plan_mode', getPlanModeAttachments)} +
     * {@code maybe('plan_mode_exit', getPlanModeExitAttachment)} 生产分支。
     *
     * <p><b>WHY</b>: CC 在 plan 模式每个 tool 轮经 getPlanModeAttachments 注入 plan_mode 附件
     * （携带 planFilePath + planExists），模型据此知道 plan 文件应写到哪；Java 端 EnterPlanModeTool
     * 只置 mode=PLAN 无 planFilePath → 模型永不写 plan 文件 → getPlan 恒 null → plan_file_reference
     * 恒不注入（读侧依赖写侧）。本方法闭合 tool 轮生产侧：每轮读 appState 的 plan 模式 flag +
     * permission mode，经 {@link PlanModeAttachments} 生产 plan_mode/plan_mode_reentry/plan_mode_exit，
     * 渲染为 system-reminder 前置注入。
     *
     * <p><b>[WF6 R1] 计数源累积</b>: 生产出的 plan_mode 附件同步 append 进 {@code state.attachments()}
     * （对齐 CC transcript 累积语义），使 {@code countPlanModeAttachmentsSinceLastExit} 的计数源真正
     * 积累 plan_mode → reminderType 随 attachmentCount 在 full/sparse 间周期切换（%5===1→full）。
     * 配套守卫：{@link #maybeInjectHookAttachments} 渲染循环跳过 plan_mode/plan_mode_reentry/
     * plan_mode_exit（三者由本方法专用注入路径渲染，避免每轮 hook 重渲染破坏节流 + 双发）。
     *
     * @param ctx             loop 基础设施（未使用，保持签名一致）
     * @param state           AgentState（sessionId/agentId/turnCount/messages/attachments 数据源）
     * @param tuc             本轮 per-turn ToolUseContext（appState 读 mode + plan 模式 flag）
     * @param messagesForLlm  当前 LLM 请求消息列表
     * @return 注入后的消息列表；非 plan 模式 / 节流命中 → 原引用
     */
    public static List<ChatMessageDto> maybeInjectPlanModeAttachments(
            AgentLoopContext ctx, AgentState state,
            com.nexusai.application.agent.tool.ToolUseContext tuc,
            List<ChatMessageDto> messagesForLlm) {
        if (state == null || tuc == null) {
            return messagesForLlm;
        }
        try {
            Map<String, Object> appState = tuc.getAppState().apply(null);
            PermissionMode mode = readAppStatePlanMode(appState);
            // [AM-CC-20260825] 对齐 CC attachments.ts:933-934 maybe('plan_mode',...) 首行短路
            //   （getPlanModeAttachments:595-600 `if (permissionContext.mode !== 'plan') return []`）：
            //   非 plan 模式直接返回，避免空跑 getOrCreateFlags / getPlanModeExitAttachment 等
            //   深逻辑 NPE（e.getMessage()=null 的 NPE，2026-08-25 联调实测每 tool 轮 warn）。
            //   内层 getPlanModeAttachments 虽有同款短路，但 getOrCreateFlags(2424) /
            //   getPlanModeExitAttachment(2429) 在其外仍执行 —— 本短路是 CC 语义的完整对齐。
            if (mode != PermissionMode.PLAN) {
                return messagesForLlm;
            }
            PlanProvider provider = new PlanProviderImpl(state.sessionId());
            PlanModeAttachments.PlanModeFlags flags = PlanModeAttachments.getOrCreateFlags(appState);

            List<AttachmentMessageDto> produced = new ArrayList<>();
            produced.addAll(PlanModeAttachments.getPlanModeAttachments(
                state.messages(), state.attachments(), mode, state.agentId(), provider, flags, state.turnCount()));
            produced.addAll(PlanModeAttachments.getPlanModeExitAttachment(
                mode, state.agentId(), provider, flags));

            if (produced.isEmpty()) {
                return messagesForLlm;
            }
            // [WF6 R1] 累积进 state.attachments()（对齐 CC transcript 累积语义），使
            // countPlanModeAttachmentsSinceLastExit 的计数源真正积累 plan_mode →
            // attachmentCount 随轮次递增 → reminderType 在 full/sparse 间周期切换
            // （此前从不 append，计数源恒空 → attachmentCount 恒 1 → reminderType 恒 full，sparse 死代码）。
            for (AttachmentMessageDto a : produced) {
                state.appendAttachment(a);
            }
            if (log.isDebugEnabled()) {
                log.debug("[WF6 R1] tool 轮 plan_mode 附件累积到 state.attachments()：本次 {} 条（累计计数源，供 full/sparse 周期）",
                    produced.size());
            }
            List<ChatMessageDto> withPlan = new ArrayList<>(produced.size() + messagesForLlm.size());
            for (AttachmentMessageDto a : produced) {
                String text = renderHookAttachmentForLlm(a);
                if (text != null) {
                    withPlan.add(metaUserMessage(text));
                }
            }
            withPlan.addAll(messagesForLlm);
            return withPlan;
        } catch (Exception e) {
            log.warn("[WF6 RC-2] tool 轮 plan_mode 附件注入失败（跳过，不中断 LLM 调用）: {}", e.getMessage());
            return messagesForLlm;
        }
    }

    /**
     * [ER-IMP-2026-04 P-21] 每迭代 output_token_usage 附件注入 · 对齐 CC
     * {@code getAttachmentMessages} 的 {@code maybe('output_token_usage', () => Promise.resolve(getOutputTokenUsageAttachment()))}
     * （utils/attachments.ts:980-982，mainThreadAttachments 分支）+ {@code getOutputTokenUsageAttachment()}
     * （utils/attachments.ts:3828-3844）。
     *
     * <p><b>门控（对齐 CC 真源）</b>：
     * <ol>
     *   <li>{@code feature('TOKEN_BUDGET')}（attachments.ts:3829）→ ctx.featureFlags().tokenBudget()</li>
     *   <li>{@code budget === null || budget <= 0 → []}（attachments.ts:3831-3833）→ turnTokenBudget 判空</li>
     *   <li>mainThreadAttachments = isMainThread 才注入（attachments.ts:944）；CC isMainThread =
     *       querySource.startsWith('repl_main_thread')||'sdk'（query.ts:1567-1568）。Java 以
     *       state.agentId()==null 近似（与 checkTokenBudget agentId 语义同源，LlmAgentLoop:4923-4933：
     *       主线程 agentId=undefined）。</li>
     * </ol>
     *
     * <p><b>不持久化</b>: CC getOutputTokenUsageAttachment 为纯函数每迭代重算（值随累计变化），
     * 不写 transcript；Java 同样动态生成注入，不 append state.attachments()（避免跨迭代累积 +
     * 每轮重渲染陈旧值）。注入位置：消息流队尾（对齐 CC query.ts:1588 yield attachment 在既有消息之后；
     * 与 maybeInjectHookAttachments 的队尾 append 路径一致——[IMP-ST-02 TC-04] hook 附件已改 push tail）。
     *
     * @param ctx                   loop 上下文（featureFlags 门控源）
     * @param state                 AgentState（turnTokenBudget/sessionOutputTokens/agentId 数据源）
     * @param cumulativeOutputTokens 本 turn 累计输出 tokens（= CC getTurnOutputTokens()，经 LlmAgentLoop 两累计点）
     * @param messagesForLlm        当前 LLM 请求消息列表
     * @return 注入后的消息列表；门控未过 → 原引用
     */
    public static List<ChatMessageDto> maybeInjectOutputTokenUsage(
            AgentLoopContext ctx, AgentState state,
            int cumulativeOutputTokens,
            List<ChatMessageDto> messagesForLlm) {
        if (state == null || ctx == null || ctx.featureFlags() == null
                || !ctx.featureFlags().tokenBudget()) {
            return messagesForLlm;
        }
        Integer budget = state.turnTokenBudget();
        if (budget == null || budget <= 0) {
            return messagesForLlm;
        }
        if (state.agentId() != null) {
            return messagesForLlm;
        }
        AttachmentMessageDto usage = AttachmentMessageDto.outputTokenUsage(
            cumulativeOutputTokens, (int) state.sessionOutputTokens(), budget);
        String text = renderHookAttachmentForLlm(usage);
        if (text == null) {
            return messagesForLlm;
        }
        List<ChatMessageDto> withUsage = new ArrayList<>(messagesForLlm.size() + 1);
        withUsage.addAll(messagesForLlm);
        withUsage.add(metaUserMessage(text));
        if (log.isDebugEnabled()) {
            log.debug("[LlmAgentLoop] output_token_usage 注入 LLM: turn={} session={} budget={} · CC attachments.ts:980-982/:3828-3844",
                cumulativeOutputTokens, state.sessionOutputTokens(), budget);
        }
        return withUsage;
    }

    /**
     * [prompt-align CTX-06] 每迭代 token_usage 附件注入 · 对齐 CC
     * {@code getAttachmentMessages} 的 {@code maybe('token_usage', () => Promise.resolve(getTokenUsageAttachment(messages, mainLoopModel)))}
     * （utils/attachments.ts:976-978，mainThreadAttachments 分支）+ {@code getTokenUsageAttachment()}
     * （utils/attachments.ts:3806-3821）+ 渲染（messages.ts:4058-4064）。
     *
     * <p><b>门控（对齐 CC 真源）</b>：
     * <ol>
     *   <li>{@code isEnvTruthy(CLAUDE_CODE_ENABLE_TOKEN_USAGE_ATTACHMENT)}（attachments.ts:3808）→
     *       Java 读 sysprop {@code nexusai.enable-token-usage-attachment} + env 双通道</li>
     *   <li>mainThreadAttachments = isMainThread 才注入（attachments.ts:944）；Java 以
     *       state.agentId()==null 近似（与 maybeInjectOutputTokenUsage 同源）</li>
     * </ol>
     *
     * <p><b>数据源</b>：used = {@link TokenEstimator#tokenCountFromLastAPIResponse}（最近消息 usage 反向
     * 扫描，tokens.ts:55-66）；total = {@link #computeBudgetFromGates}（模型 metadata max_context_tokens，
     * = CC getEffectiveContextWindowSize 的 Java 等价）；remaining = total - used（clamp ≥0）。
     *
     * <p><b>不持久化</b>: CC 纯函数每迭代重算，不写 transcript；Java 同 maybeInjectOutputTokenUsage
     * 动态生成注入队尾，不 append state.attachments()。
     *
     * @param ctx           loop 上下文（tokenEstimator / queryConfig 数据源）
     * @param state         AgentState（agentId 主线程守卫）
     * @param model         主循环模型名（context window 解析）
     * @param messagesForLlm 当前 LLM 请求消息列表
     * @return 注入后的消息列表；门控未过 / 数据源缺失 → 原引用
     */
    public static List<ChatMessageDto> maybeInjectTokenUsage(
            AgentLoopContext ctx, AgentState state, String model,
            List<ChatMessageDto> messagesForLlm) {
        if (ctx == null || state == null
                || !envUtilsIsTruthy("CLAUDE_CODE_ENABLE_TOKEN_USAGE_ATTACHMENT", "nexusai.enable-token-usage-attachment")) {
            return messagesForLlm;
        }
        if (state.agentId() != null) {
            return messagesForLlm;
        }
        com.nexusai.application.agent.compact.TokenEstimator te =
            ctx.tokenBudgetBeans() != null ? ctx.tokenBudgetBeans().tokenEstimator() : null;
        if (te == null) {
            return messagesForLlm;
        }
        // [A5-2] 求和 provider 分派：token_usage 注入 used 按 model 判 anthropic（deepseek input 已含
        //   cache hit，4 项和双计 → 注入给模型的 used 虚高）。mapper 不可得（测试）→ 回落 anthropic 语义。
        AgentLoopContext.TokenBudgetBeans beans = ctx.tokenBudgetBeans();
        boolean anthropic = (beans != null && beans.modelMapper() != null && beans.providerMapper() != null)
            ? ContextUsageCalculator.isAnthropic(beans.modelMapper(), beans.providerMapper(), model)
            : true;
        int used = te.tokenCountFromLastAPIResponse(messagesForLlm, anthropic);
        Integer total = computeBudgetFromGates(ctx, ctx.queryConfig(), model);
        if (total == null || total <= 0) {
            return messagesForLlm;
        }
        int remaining = Math.max(0, total - used);
        AttachmentMessageDto usage = AttachmentMessageDto.tokenUsage(used, total, remaining);
        String text = renderHookAttachmentForLlm(usage);
        if (text == null) {
            return messagesForLlm;
        }
        List<ChatMessageDto> withUsage = new ArrayList<>(messagesForLlm.size() + 1);
        withUsage.addAll(messagesForLlm);
        withUsage.add(metaUserMessage(text));
        if (log.isDebugEnabled()) {
            log.debug("[prompt-align CTX-06] token_usage 注入 LLM: used={} total={} remaining={} · CC attachments.ts:976-978/:3806-3821",
                used, total, remaining);
        }
        return withUsage;
    }

    /** 读 env/sysprop 双通道布尔 · CC isEnvTruthy（envUtils.ts:32-37，1/true/yes/on 不区分大小写）。 */
    private static boolean envUtilsIsTruthy(String envName, String syspropName) {
        String sysprop = System.getProperty(syspropName);
        if (sysprop != null && !sysprop.isBlank()) {
            return com.nexusai.application.agent.tasks.TaskSystemConfig.isEnvTruthy(sysprop);
        }
        String env = System.getenv(envName);
        return com.nexusai.application.agent.tasks.TaskSystemConfig.isEnvTruthy(env);
    }

    /**
     * [snip nudge] context_efficiency nudge 注入 · 对齐 CC attachments.ts:929-937（getAttachments 中
     * {@code maybe('context_efficiency', () => getContextEfficiencyAttachment(messages ?? []))}）+
     * attachments.ts:3963-3983（getContextEfficiencyAttachment）+ messages.ts:4148-4161（渲染）。
     *
     * <p><b>CC 触发链（四门 AND）</b>:
     * <ol>
     *   <li>getAttachments 门：{@code feature('HISTORY_SNIP')}（attachments.ts:934）——context_efficiency
     *       在 {@code allThreadAttachments} 共享数组（attachments.ts:915-941），<b>非 mainThread 也评估</b>
     *       （不在 mainThreadAttachments），故不加 agentId 守卫。</li>
     *   <li>getContextEfficiencyAttachment 门：{@code feature('HISTORY_SNIP')}（attachments.ts:3966）</li>
     *   <li>{@code isSnipRuntimeEnabled()}（attachments.ts:3974 · 恒 true，snipCompact.ts:154-156）</li>
     *   <li>{@code shouldNudgeForSnips(messages)}（attachments.ts:3978 · 消息数 ≥ 30，snipCompact.ts:163-165）</li>
     * </ol>
     * Java 端单一 {@code historySnip()} 门覆盖第 1/2 门（同一 CC flag）；isSnipRuntimeEnabled 保留显式调用
     * （对齐 CC 两函数引用，恒 true）。
     *
     * <p><b>渲染</b>（messages.ts:4148-4161）: {@code wrapMessagesInSystemReminder([
     * createUserMessage({ content: SNIP_NUDGE_TEXT, isMeta: true })])} → Java =
     * {@code "<system-reminder>\n" + SNIP_NUDGE_TEXT + "\n</system-reminder>"} 的 isMeta user 消息
     * （{@link #metaUserMessage}，对齐 CC wrapInSystemReminder messages.ts:3097-3099）。
     *
     * <p><b>计数源</b>: CC getAttachments 收到 {@code [...messagesForQuery, ...assistantMessages,
     * ...toolResults]}（query.ts:1585）；Java 侧取 {@code state.messages()}（持久会话，含本 turn 已累计
     * 的 assistant/tool 消息，与 snip 步骤 query.ts:401-410 同源）。注入位置 = 消息流队尾（对齐 CC
     * query.ts:1588 yield attachment 在既有消息之后）。动态生成不持久化（CC 纯函数每迭代重算，同
     * {@link #maybeInjectOutputTokenUsage}）。
     *
     * <p><b>WHY nudge 是给模型看的 isMeta 消息</b>: CC SNIP_NUDGE_TEXT 提示「模型考虑 /force-snip 或 snip
     * 工具」压缩（snipCompact.ts:17-18），createUserMessage isMeta:true（messages.ts:4154-4156）——
     * isMeta 消息不污染用户转录、渲染层按 isMeta 隐藏，Java ChatMessageDto.isMeta 同语义（R32-c-1）。
     *
     * @param ctx           loop 上下文（featureFlags.historySnip 门控源）
     * @param settingsResolver 压缩配置 DB 实时读源（可 null；DB settings.history_snip_enabled 覆盖
     *                          FeatureFlags，null 回落；snipNudgeThreshold 门 4 阈值 DB 覆盖 + 窗口自适应）
     * @param thresholdSystem 有效上下文窗口计算源（CompactThresholdSystem#getEffectiveContextWindowSize；
     *                        可 null → effectiveWindow=0 → 回落 CC 默认阈值 30）
     * @param model         当前有效模型名（窗口计算入参；可 null）
     * @param state         AgentState（state.messages() 计数源，对齐 CC messages 参数）
     * @param messagesForLlm 当前 LLM 请求消息列表（注入目标）
     * @return 注入后的消息列表；任一门未过 → 原引用
     */
    public static List<ChatMessageDto> maybeInjectContextEfficiencyNudge(
            AgentLoopContext ctx,
            com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver,
            CompactThresholdSystem thresholdSystem,
            String model,
            AgentState state,
            List<ChatMessageDto> messagesForLlm) {
        // [V52 X1-3] HISTORY_SNIP 门 DB-aware：DB settings.history_snip_enabled 有值覆盖
        // ctx.featureFlags().historySnip()（null 回落 FeatureFlags，零行为变化）。单次 DB 读。
        boolean historySnipEnabled;
        Boolean dbSnip = settingsResolver != null ? settingsResolver.historySnipEnabled() : null;
        if (dbSnip != null) {
            historySnipEnabled = dbSnip;
        } else {
            historySnipEnabled = ctx != null && ctx.featureFlags() != null && ctx.featureFlags().historySnip();
        }
        if (state == null || ctx == null || ctx.featureFlags() == null || !historySnipEnabled) {
            // CC 门 1/2：attachments.ts:934 + attachments.ts:3966 同一 feature('HISTORY_SNIP')
            if (log.isDebugEnabled()) {
                log.debug("[LlmAgentLoop] context_efficiency nudge 跳过: HISTORY_SNIP 关 · CC attachments.ts:934/:3966");
            }
            return messagesForLlm;
        }
        // CC 门 3：isSnipRuntimeEnabled()（attachments.ts:3974，恒 true，snipCompact.ts:154-156）
        if (!SnipCompactor.isSnipRuntimeEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[LlmAgentLoop] context_efficiency nudge 跳过: isSnipRuntimeEnabled=false · CC attachments.ts:3974");
            }
            return messagesForLlm;
        }
        // CC 门 4：shouldNudgeForSnips(messages)（attachments.ts:3978，消息数 ≥ 阈值）——
        // [V55 fix-transcript-nudge] nudge 阈值入 DB + 上下文窗口自适应：DB
        //   settings.snip_nudge_threshold > 0 直接覆盖；null → SnipCompactor.resolveSnipNudgeThreshold
        //   按 effectiveWindow 档位（≥800k→150 / >600k→100 / ≥400k→60 / 其他→30，CC 默认
        //   snipCompact.ts:11）。effectiveWindow = thresholdSystem.getEffectiveContextWindowSize(model)；
        //   thresholdSystem 未接线（单测/无 bean）→ effectiveWindow=0 → 回落 30（CC 默认，零行为变化）。
        Integer dbNudgeThreshold = settingsResolver != null ? settingsResolver.snipNudgeThreshold() : null;
        int effectiveWindow = (thresholdSystem != null && model != null)
            ? thresholdSystem.getEffectiveContextWindowSize(model)
            : 0;
        int snipNudgeThreshold = SnipCompactor.resolveSnipNudgeThreshold(dbNudgeThreshold, effectiveWindow);
        if (!SnipCompactor.shouldNudgeForSnips(state.messages(), snipNudgeThreshold)) {
            if (log.isDebugEnabled()) {
                log.debug("[LlmAgentLoop] context_efficiency nudge 跳过: state.messages()={} 条 < 阈值{}（db={} effectiveWindow={}）· CC attachments.ts:3978/snipCompact.ts:163-165",
                    state.messages() != null ? state.messages().size() : 0,
                    snipNudgeThreshold, dbNudgeThreshold, effectiveWindow);
            }
            return messagesForLlm;
        }
        // CC 渲染（messages.ts:4148-4161）：SNIP_NUDGE_TEXT 包 <system-reminder> 的 isMeta user 消息
        String text = "<system-reminder>\n" + SnipCompactor.SNIP_NUDGE_TEXT + "\n</system-reminder>";
        List<ChatMessageDto> withNudge = new ArrayList<>(messagesForLlm.size() + 1);
        withNudge.addAll(messagesForLlm);
        withNudge.add(metaUserMessage(text));
        if (log.isInfoEnabled()) {
            log.info("[LlmAgentLoop] context_efficiency nudge 注入 LLM 队尾: state.messages()={} 条 ≥阈值{}（db={} effectiveWindow={}）, isMeta=true · CC attachments.ts:929-937/:3963-3983 + messages.ts:4148-4161",
                state.messages().size(), snipNudgeThreshold, dbNudgeThreshold, effectiveWindow);
        }
        return withNudge;
    }

    /**
     * [snip-ccb-align] 对齐 CCB messages.ts:2667-2686 + appendMessageTagToUserMessage
     * （messages.ts:1913-1968）：HISTORY_SNIP 门控给 user 消息（非 isMeta）的 API-bound 副本
     * 末尾追加 {@code \n[id:<6位短id>]} tag，让模型能引用消息 ID 调用 SnipTool
     * （CCB "This lets Claude reference message IDs when calling the snip tool"）。
     *
     * <p><b>只改 API-bound 副本，不污染 state.messages()</b>（CCB messages.ts:1914-1916
     * "Only mutates the API-bound copy, not the stored message"）：ChatMessageDto 不可变，
     * 用 {@code withContent(content + tag)} 构造副本。门控与 CCB messages.ts:2673-2685 一致：
     * HISTORY_SNIP 开 + isSnipRuntimeEnabled() —— "don't inject [id:] tags when the tool
     * isn't available (confuses the model and wastes tokens on every non-meta user message)"。
     *
     * <p><b>发送边界</b>：LlmAgentLoop ModelRequest 构造前（:5194）调用 —— 与 CCB 在
     * normalizeMessagesForAPI（API 发送前）注入同位置。
     *
     * @param ctx           loop 上下文（featureFlags.historySnip 门控源）
     * @param settingsResolver 压缩配置 DB 实时读源（可 null；DB settings.history_snip_enabled
     *                          覆盖 FeatureFlags，null 回落）
     * @param messagesForLlm 当前 LLM 请求消息列表（注入目标；只改副本）
     * @return 注入后的列表；任一门未过 / 无 user 消息 → 原引用
     */
    public static List<ChatMessageDto> maybeAppendSnipIdTags(
            AgentLoopContext ctx,
            com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver,
            List<ChatMessageDto> messagesForLlm) {
        if (messagesForLlm == null || messagesForLlm.isEmpty()) {
            return messagesForLlm;
        }
        boolean historySnipEnabled;
        Boolean dbSnip = settingsResolver != null ? settingsResolver.historySnipEnabled() : null;
        if (dbSnip != null) {
            historySnipEnabled = dbSnip;
        } else {
            historySnipEnabled = ctx != null && ctx.featureFlags() != null && ctx.featureFlags().historySnip();
        }
        if (!historySnipEnabled || !SnipCompactor.isSnipRuntimeEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[LlmAgentLoop] snip [id:] tag 注入跳过: HISTORY_SNIP={} isSnipRuntime={} · CCB messages.ts:2673-2685",
                    historySnipEnabled, SnipCompactor.isSnipRuntimeEnabled());
            }
            return messagesForLlm;
        }
        List<ChatMessageDto> out = null;
        for (int i = 0; i < messagesForLlm.size(); i++) {
            ChatMessageDto m = messagesForLlm.get(i);
            if (m == null || m.role() != Role.user || Boolean.TRUE.equals(m.isMeta())) {
                continue;   // 非目标消息：out 已初始化时元素已在复制中，无需额外处理
            }
            String tag = "\n[id:" + SnipCompactor.deriveShortMessageId(m.id()) + "]";
            String content = m.content() == null ? tag : m.content() + tag;
            if (out == null) {
                // 惰性复制：首个待注入 user 才复制（否则返回原引用，零行为变化）
                out = new java.util.ArrayList<>(messagesForLlm);
            }
            out.set(i, m.withContent(content));
        }
        return out != null ? out : messagesForLlm;
    }

    /** 读 appState.toolPermissionContext.mode（对齐 CompactConversationContext.isInPlanMode 读侧）。 */
    private static PermissionMode readAppStatePlanMode(Map<String, Object> appState) {
        if (appState == null) {
            return PermissionMode.DEFAULT;
        }
        Object tpc = appState.get("toolPermissionContext");
        if (tpc instanceof ToolPermissionContext p) {
            return p.mode();
        }
        return PermissionMode.DEFAULT;
    }

    /**
     * [P1-2] per-turn 动态技能 attachment 装配 · 对齐 CC utils/attachments.ts:2547-2601
     * {@code getDynamicSkillAttachments}。
     *
     * <p>读文件工具（Write/Edit/Read）写入的 {@code dynamicSkillDirTriggers}（共享可变 Set），
     * 逐目录 readdir 子目录 + stat SKILL.md 存在过滤（CC :2565-2573），构建
     * {@code type='dynamic_skill'} attachment（CC :2588-2593）append 到 state.attachments()
     * （transcript/UI 记录），最后 clear()（CC :2597）。
     *
     * <p><b>不注入 LLM</b>：CC messages.ts:3723-3727 明确 {@code case 'dynamic_skill': return []} ——
     * 动态技能仅供 UI，技能本身已加载并可经 Skill tool 使用。故渲染层
     * {@link #renderHookAttachmentForLlm} default 分支返回 null，本方法仅写 transcript。
     *
     * <p>try-catch 不阻塞主链（CC :2579-2582 忽略目录读取失败）+ 中文日志。
     *
     * @param state                  AgentState（appendAttachment 记录）
     * @param dynamicSkillDirTriggers 共享可变触发 Set（文件工具 .add / 本方法 clear）
     * @param cwd                    展示路径基准（CC getCwd() 等价 · displayPath = relative(cwd, skillDir)）
     */
    public static void collectDynamicSkillAttachments(AgentState state,
                                                      java.util.Set<String> dynamicSkillDirTriggers,
                                                      Path cwd) {
        if (state == null || dynamicSkillDirTriggers == null || dynamicSkillDirTriggers.isEmpty()) {
            return;
        }
        java.util.List<String> dirs = new java.util.ArrayList<>(dynamicSkillDirTriggers);
        try {
            for (String skillDir : dirs) {
                try {
                    java.util.List<String> skillNames = new java.util.ArrayList<>();
                    try (java.util.stream.Stream<Path> entries = Files.list(Paths.get(skillDir))) {
                        java.util.List<Path> children = entries.collect(java.util.stream.Collectors.toList());
                        for (Path child : children) {
                            if (!Files.isDirectory(child)) {
                                continue;
                            }
                            // stat(resolve(skillDir, name, 'SKILL.md')) 存在过滤（CC :2568）
                            if (Files.isRegularFile(child.resolve("SKILL.md"))) {
                                skillNames.add(child.getFileName().toString());
                            }
                        }
                    }
                    if (!skillNames.isEmpty()) {
                        String displayPath = displayPathOf(cwd, skillDir);
                        state.appendAttachment(AttachmentMessageDto.dynamicSkill(skillDir, skillNames, displayPath));
                        if (log.isInfoEnabled()) {
                            log.info("[P1-2] 动态技能 attachment 装配: dir={} skills={} display={} · CC attachments.ts:2547-2601",
                                skillDir, skillNames.size(), displayPath);
                        }
                    }
                } catch (Exception e) {
                    // 忽略目录读取失败（CC :2579-2582 catch → {skillDir, skillNames:[]}）
                    if (log.isDebugEnabled()) {
                        log.debug("[P1-2] 动态技能目录读取失败, 跳过: dir={} cause={}", skillDir, e.toString());
                    }
                }
            }
        } finally {
            // 每 turn 装配后 clear · 对齐 CC attachments.ts:2597（per-turn 语义，避免跨 turn 残留）
            dynamicSkillDirTriggers.clear();
        }
    }

    /** displayPath = relative(cwd, skillDir) · CC attachments.ts:2592 relative(getCwd(), skillDir)。 */
    private static String displayPathOf(Path cwd, String skillDir) {
        if (cwd == null) {
            return skillDir;
        }
        try {
            return cwd.toAbsolutePath().normalize()
                .relativize(Paths.get(skillDir).toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return skillDir; // 跨盘符 → 绝对路径兜底
        }
    }

    /**
     * [WF6 R1] 判定是否 tool 轮 plan 类型附件（由 maybeInjectPlanModeAttachments 专用路径渲染）。
     * 仅 plan_mode / plan_mode_reentry / plan_mode_exit —— 三者经 tool 轮生产器每轮渲染（含节流 +
     * full/sparse 周期），持久化进 state.attachments() 仅作计数源，不得再由 hook 路径重渲染。
     * plan_file_reference 不在此列（compact 重建链写入，经 hook 路径渲染为 system-reminder）。
     */
    private static boolean isPlanModeAttachmentType(String type) {
        return "plan_mode".equals(type) || "plan_mode_reentry".equals(type) || "plan_mode_exit".equals(type);
    }

    /**
     * 单个 attachment → LLM 可见文本 · 对齐 CC {@code normalizeAttachmentForAPI}
     * (utils/messages.ts:4090-4136; invoked_skills 见 :3644-3662). 返回 null 表示该类型不注入 LLM
     * (CC 返回 []).
     */
    /**
     * [prompt-align CTX-05] plan_mode V2 agent 数 · 对齐 CC {@code getPlanModeV2AgentCount()}
     * （utils/planModeV2.ts:5-17）：env {@code CLAUDE_CODE_PLAN_V2_AGENT_COUNT} 优先（parseInt 1-10），
     * 否则按订阅制（max/enterprise/team=3）——Java 无订阅模型 → 默认 1（CC 非 max/enterprise/team 默认）。
     * env 读 sysprop + env 双通道（isEnvTruthy 前例）。
     *
     * @return Plan agent 并行数（1-10）
     */
    private static int planModeV2AgentCount() {
        String v = readPlanModeV2Env("CLAUDE_CODE_PLAN_V2_AGENT_COUNT", "nexusai.plan-v2-agent-count");
        int count = parsePlanModeV2Count(v);
        return count > 0 ? count : 1;
    }

    /**
     * [prompt-align CTX-05] plan_mode V2 explore agent 数 · 对齐 CC {@code getPlanModeV2ExploreAgentCount()}
     * （utils/planModeV2.ts:19-31）：env {@code CLAUDE_CODE_PLAN_V2_EXPLORE_AGENT_COUNT} 优先（1-10），
     * 否则默认 3（CC :43）。Java 无订阅模型 → 默认 3。
     *
     * @return Explore agent 并行数（1-10）
     */
    private static int planModeV2ExploreAgentCount() {
        String v = readPlanModeV2Env("CLAUDE_CODE_PLAN_V2_EXPLORE_AGENT_COUNT", "nexusai.plan-v2-explore-agent-count");
        int count = parsePlanModeV2Count(v);
        return count > 0 ? count : 3;
    }

    /** 读 plan_mode V2 env · sysprop 优先、env 兜底（CC process.env 读 env；Java 双通道）。 */
    private static String readPlanModeV2Env(String envName, String syspropName) {
        String sysprop = System.getProperty(syspropName);
        if (sysprop != null && !sysprop.isBlank()) {
            return sysprop;
        }
        return System.getenv(envName);
    }

    /** [GLB-07] 读 CLAUDE_CODE_VERIFY_PLAN env · sysprop+env 双通道（对齐 readPlanModeV2Env 前例）。 */
    private static String readVerifyPlanEnv() {
        return readPlanModeV2Env("CLAUDE_CODE_VERIFY_PLAN", "nexusai.verify-plan");
    }

    /** 解析 plan_mode V2 agent 数 · CC parseInt 1-10（NaN/越界 → 无效返回 -1，调用方回落默认）。 */
    private static int parsePlanModeV2Count(String v) {
        if (v == null || v.isBlank()) {
            return -1;
        }
        try {
            int count = Integer.parseInt(v.trim());
            return (count > 0 && count <= 10) ? count : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String renderHookAttachmentForLlm(AttachmentMessageDto a) {
        String type = a.type();
        String hookName = a.hookName() != null ? a.hookName() : "?";
        String content = a.content();
        switch (type) {
            // [hook message 普通消息通道] hook_user_message 渲染 case 已删除: CC 中 hook
            //   message 是普通 user 消息 (toolHooks.ts:478-480 → toolExecution.ts:815
            //   resultingMessages → query.ts:1395 filter type==='user'), 非 attachment.
            //   两端生产均已改普通消息通道结算, 不再产生 hook_user_message attachment:
            //   - PreToolUse: StreamingToolExecutor injectPreToolUseHookAttachments →
            //     pendingHookUserMessages → newMessages 桥 (与 tool_result 同批, 一次性).
            //   - 非 PreToolUse (SessionStart/Setup/UserPromptSubmit/SessionEnd/failure):
            //     LlmAgentLoop.injectHookResultMessage → appendPlainHookMessage →
            //     state.messages() 一次性 user 消息 (对齐 CC sessionStart.ts:141-142).
            //   若仍残留 hook_user_message attachment (防御), 走 default → 不渲染, 避免
            //   常驻 attachment 每轮重渲染成 isMeta 消息.
            case "hook_blocking_error":
                // [prompt-align CTX-03] CC :4090-4097 `{hookName} hook blocking error from command:
                //   "{command}": {error}`（wrapInSystemReminder + isMeta）。Java AttachmentMessageDto 已承载
                //   command（AttachmentMessageDto.java:76-78，[H3 v3 修复]；生产方 HookRegistry:2867/2960/
                //   3189/3281 与 HookOutputParser:464 均传 blockingError.command()）→ 补 `from command:` 段。
                //   保留 content 空→null 守卫（CC 渲染外 isMeta 包裹不变，走 maybeInjectHookAttachments 队尾注入）。
                return (content == null || content.isBlank()) ? null
                    : hookName + " hook blocking error from command: \""
                        + (a.command() != null ? a.command() : "") + "\": " + content;
            case "hook_stopped_continuation":
                // [V-SH-2 · 修订 CTX-10] 渲染形状对齐 CC normalizeAttachmentForAPI
                //   （messages.ts:4130-4136）：`{hookName} hook stopped continuation: {message}`
                //   wrapInSystemReminder（<system-reminder>\n{content}\n</system-reminder>, :3097-3100）
                //   + isMeta:true。终止语义不变：CC 同一 query() 内 shouldPreventContinuation → 立即
                //   return {reason:'hook_stopped'}（query.ts:1519-1520），渲染结果随 toolResults 丢弃，
                //   同次 query() 后续 LLM 调用永不送达模型。Java 跨 turn 防重注入由
                //   maybeInjectHookAttachments 跳过名单承担（下 :2288 之后补 hook_stopped_continuation
                //   continue）——终止已由 LlmAgentLoop.hasHookStoppedContinuation + ExitReason.HOOK_STOPPED
                //   承载（:6408/:8488），渲染可产文本（对齐 CC 写法）但永不被 LLM 消费（对齐 CC 同 query
                //   丢弃）。content 空 → null 守卫保留（CC 渲染 message 恒非空，防御）。原 return null
                //   亦为防跨 turn 注入，现等价由跳过名单承担。
                return (content == null || content.isBlank()) ? null
                    : "<system-reminder>\n" + hookName + " hook stopped continuation: " + content + "\n</system-reminder>";
            case "hook_additional_context":
                // CC :4117-4128 content 为空 → []
                return (content == null || content.isBlank()) ? null
                    : hookName + " hook additional context: " + content;
            case "hook_success":
                // CC :4099-4115 仅 SessionStart/UserPromptSubmit 且 content 非空
                String ev = a.hookEvent();
                boolean sessionEvent = "SessionStart".equals(ev) || "UserPromptSubmit".equals(ev);
                if (!sessionEvent || content == null || content.isBlank()) {
                    return null;
                }
                return hookName + " hook success: " + content;
            case "skill_listing":
                // [P1-10] 对齐 CC utils/messages.ts:3728-3738 normalizeAttachmentForAPI case 'skill_listing':
                //   wrapMessagesInSystemReminder([createUserMessage({content: `The following skills are
                //   available for use with the Skill tool:\n\n${attachment.content}`, isMeta:true})])
                //   wrapInSystemReminder = `<system-reminder>\n${content}\n</system-reminder>`（:3097-3100）
                //   content 空 → return []（:3729-3731）
                return (content == null || content.isBlank()) ? null
                    : "<system-reminder>\nThe following skills are available for use with the Skill tool:\n\n"
                        + content + "\n</system-reminder>";
            case "skill_discovery":
                // [C-30] 对齐 CC utils/messages.ts:3503-3520 normalizeAttachmentForAPI case 'skill_discovery'
                //   （在 feature('EXPERIMENTAL_SKILL_SEARCH') 守卫块内处理，Java 端 type 恒由 C-30 消费点注入）:
                //   - attachment.skills.length === 0 → return [] (:3507)
                //   - lines = skills.map(s => `- ${s.name}: ${s.description}`) (:3508)
                //   - content = `Skills relevant to your task:\n\n${lines.join('\n')}\n\n` +
                //     `These skills encode project-specific conventions. ` +
                //     `Invoke via Skill("<name>") for complete instructions.` (:3511-3515)
                //   - wrapInSystemReminder `<system-reminder>\n${content}\n</system-reminder>` (:3097-3100)
                java.util.List<AttachmentMessageDto.SkillDiscoveryRef> discovered = a.discoveredSkills();
                if (discovered == null || discovered.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[C-30] skill_discovery 渲染: skills 为空 → 不注入 (CC messages.ts:3507 skills.length===0 return [])");
                    }
                    return null;
                }
                String discoveryLines = discovered.stream()
                    .map(s -> "- " + s.name() + ": " + (s.description() != null ? s.description() : ""))
                    .collect(java.util.stream.Collectors.joining("\n"));
                String discoveryBody = "Skills relevant to your task:\n\n" + discoveryLines
                    + "\n\nThese skills encode project-specific conventions. "
                    + "Invoke via Skill(\"<name>\") for complete instructions.";
                if (log.isDebugEnabled()) {
                    log.debug("[C-30] skill_discovery 渲染: skills={} 注入 LLM (CC messages.ts:3503-3520)",
                        discovered.size());
                }
                return "<system-reminder>\n" + discoveryBody + "\n</system-reminder>";
            case "invoked_skills":
                // [P1-6-READ-2] 对齐 CC utils/messages.ts:3644-3662 normalizeAttachmentForAPI
                // case 'invoked_skills' (CC 真源, 非注释):
                //   - skills 空 → return [] (:3645-3646)
                //   - per-skill `### Skill: ${name}\nPath: ${path}\n\n${content}` join `\n\n---\n\n` (:3652-3654)
                //   - 前导 `The following skills were invoked in this session. Continue to follow these guidelines:\n\n` (:3658)
                //   - wrapInSystemReminder `<system-reminder>\n${content}\n</system-reminder>` (:3656-3661 + :3097-3100)
                java.util.List<AttachmentMessageDto.SkillRef> invokedSkills = a.skills();
                if (invokedSkills == null || invokedSkills.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[P1-6-READ-2] invoked_skills 渲染: skills 为空 → 不注入 (CC messages.ts:3645-3646 return [])");
                    }
                    return null;
                }
                String skillsContent = invokedSkills.stream()
                    .map(s -> "### Skill: " + s.name() + "\nPath: " + s.path() + "\n\n"
                        + (s.content() != null ? s.content() : ""))
                    .collect(java.util.stream.Collectors.joining("\n\n---\n\n"));
                String skillsBody = "The following skills were invoked in this session. Continue to follow these guidelines:\n\n"
                    + skillsContent;
                if (log.isInfoEnabled()) {
                    log.info("[P1-6-READ-2] invoked_skills 渲染首次命中: skills={} 注入 LLM (CC messages.ts:3644-3662)",
                        invokedSkills.size());
                }
                if (log.isDebugEnabled()) {
                    log.debug("[P1-6-READ-2] invoked_skills 渲染: skills={} 已注入 LLM", invokedSkills.size());
                }
                return "<system-reminder>\n" + skillsBody + "\n</system-reminder>";
            case "file": {
                // [P3-CROSS-1] 对齐 CC utils/messages.ts:3545-3590 normalizeAttachmentForAPI case 'file'
                //   (tool_use(FileRead)+tool_result 对) — Java 端降级为 meta user message 内嵌截断后文件内容
                //   (Java provider 不支持经 attachment 通道注入 tool_use/tool_result 对, 降级路径在 plan 内明示)
                AttachmentMessageDto.FileRef fileRef = a.file();
                if (fileRef == null || fileRef.content() == null || fileRef.content().isBlank()) {
                    return null;
                }
                String fileNote = fileRef.truncated()
                    ? " Note: The file " + fileRef.filename() + " was too large and has been truncated to the first "
                        + CompactConstants.POST_COMPACT_MAX_TOKENS_PER_FILE
                        + " tokens. Don't tell the user about this truncation. Use Read to read more of the file if you need."
                    : "";
                return "<system-reminder>\nThe following file was read before the last conversation was summarized:\n\n### "
                    + fileRef.filename() + "\n\n" + fileRef.content() + fileNote + "\n</system-reminder>";
            }
            case "compact_file_reference": {
                // [prompt-align GLB-08] 对齐 CC utils/messages.ts:3592-3599 case 'compact_file_reference'：
                //   content = `Note: ${attachment.filename} was read before the last conversation was summarized,
                //   but the contents are too large to include. Use ${FileReadTool.name} tool if you need to access
                //   it.`（:3595，FileReadTool.name='Read' 常量化 FILE_READ_TOOL_NAME）。wrapInSystemReminder + isMeta。
                //   filename null/blank → 不注入（Java 防御）。归 compact 域：Java 无 compact_file_reference
                //   producer（压缩后大文件引用走既有 file case）→ render 防御纯渲染。
                String cfFilename = a.referenceFilename();
                if (cfFilename == null || cfFilename.isBlank()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[GLB-08] compact_file_reference 渲染: filename 空 → 不注入 (CC messages.ts:3595)");
                    }
                    return null;
                }
                String cfContent = "Note: " + cfFilename
                    + " was read before the last conversation was summarized, but the contents are too large to include. Use "
                    + ToolNameConstants.FILE_READ_TOOL_NAME + " tool if you need to access it.";
                if (log.isDebugEnabled()) {
                    log.debug("[GLB-08] compact_file_reference 渲染: filename={} 注入 LLM (CC messages.ts:3592-3599)",
                        cfFilename);
                }
                return "<system-reminder>\n" + cfContent + "\n</system-reminder>";
            }
            case "pdf_reference": {
                // [prompt-align GLB-09] 对齐 CC utils/messages.ts:3600-3612 case 'pdf_reference'：
                //   content = `PDF file: ${filename} (${pageCount} pages, ${formatFileSize(fileSize)}). ` +
                //   `This PDF is too large to read all at once. You MUST use the ${FILE_READ_TOOL_NAME} tool
                //   with the pages parameter to read specific page ranges (e.g., pages: "1-5"). Do NOT call
                //   ${FILE_READ_TOOL_NAME} without the pages parameter or it will fail. Start by reading the
                //   first few pages to understand the structure, then read more as needed. Maximum 20 pages per
                //   request.`（:3604-3608，FILE_READ_TOOL_NAME='Read'）。wrapInSystemReminder + isMeta。
                //   pdf null → 不注入（Java 防御）。归 attachments/pdf 域：Java 有 PDF 上传通道但无该
                //   注入提示 → render 防御保留，producer 接线登记 pdf 域未来。
                AttachmentMessageDto.PdfRef pdfRef = a.pdfReference();
                if (pdfRef == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("[GLB-09] pdf_reference 渲染: pdf 引用空 → 不注入 (CC messages.ts:3600-3612)");
                    }
                    return null;
                }
                String pdfContent = "PDF file: " + pdfRef.filename() + " (" + pdfRef.pageCount()
                    + " pages, " + formatFileSize(pdfRef.fileSize()) + "). "
                    + "This PDF is too large to read all at once. You MUST use the "
                    + ToolNameConstants.FILE_READ_TOOL_NAME + " tool with the pages parameter "
                    + "to read specific page ranges (e.g., pages: \"1-5\"). Do NOT call "
                    + ToolNameConstants.FILE_READ_TOOL_NAME + " without the pages parameter "
                    + "or it will fail. Start by reading the first few pages to understand the structure, then read more as needed. "
                    + "Maximum 20 pages per request.";
                if (log.isDebugEnabled()) {
                    log.debug("[GLB-09] pdf_reference 渲染: filename={} pages={} 注入 LLM (CC messages.ts:3600-3612)",
                        pdfRef.filename(), pdfRef.pageCount());
                }
                return "<system-reminder>\n" + pdfContent + "\n</system-reminder>";
            }
            case "directory": {
                // [prompt-align GLB-01] 对齐 CC utils/messages.ts:3525-3537 case 'directory'：
                //   CC 原为 Bash ls tool_use + tool_result 对（:3527-3535，command=`ls ${quote([path])}`，
                //   stdout=attachment.content）；Java provider 不支持经 attachment 通道注入
                //   tool_use/tool_result 对 → 降级为 meta user message（P3-CROSS-1 file case 降级范式，
                //   CC quote([path]) shell 引号在降级路径不适用）。文案镜像 file case 结构
                //   （'The following directory was listed before the last conversation was summarized:'）。
                //   path/content null/blank → 不注入（Java 防御）。Java 无目录附件通道（生产零 producer）
                //   → 防御纯渲染，登记 N/A。
                AttachmentMessageDto.DirectoryRef dir = a.directory();
                if (dir == null || dir.path() == null || dir.path().isBlank()
                    || dir.content() == null || dir.content().isBlank()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[GLB-01] directory 渲染: path/content 空 → 不注入 (CC messages.ts:3525-3537)");
                    }
                    return null;
                }
                String dirContent = "The following directory was listed before the last conversation was summarized:\n\n### "
                    + dir.path() + "\n\n" + dir.content();
                if (log.isDebugEnabled()) {
                    log.debug("[GLB-01] directory 渲染: path={} 降级注入 LLM (CC messages.ts:3525-3537)",
                        dir.path());
                }
                return "<system-reminder>\n" + dirContent + "\n</system-reminder>";
            }
            case "diagnostics": {
                // [prompt-align GLB-02] 对齐 CC utils/messages.ts:3812-3825 case 'diagnostics'：
                //   files.length===0 → return []（:3813）→ Java null 不注入；否则
                //   summary = DiagnosticTrackingService.formatDiagnosticsSummary(files)（:3816-3817）→
                //   content = `<new-diagnostics>The following new diagnostic issues were detected:\n\n${summary}
                //   </new-diagnostics>`（:3821）wrapInSystemReminder + isMeta。formatDiagnosticsSummary
                //   等价见本类私有助手（对齐 services/diagnosticTracking.ts:352-394，含 figures 符号映射 +
                //   MAX_DIAGNOSTICS_SUMMARY_CHARS=4000 截断 + '…[truncated]' U+2026）。
                //   归 LSP 域：Java 有 LspDiagnosticRegistry 基建但无该 attachment 渲染与 producer →
                //   render 防御保留，producer 接线登记 LSP 域未来。
                java.util.List<AttachmentMessageDto.DiagnosticsFileRef> diagFiles = a.diagnosticsFiles();
                if (diagFiles == null || diagFiles.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[GLB-02] diagnostics 渲染: files 空 → 不注入 (CC messages.ts:3813 files.length===0 return [])");
                    }
                    return null;
                }
                String diagSummary = formatDiagnosticsSummary(diagFiles);
                String diagContent = "<new-diagnostics>The following new diagnostic issues were detected:\n\n"
                    + diagSummary + "</new-diagnostics>";
                if (log.isDebugEnabled()) {
                    log.debug("[GLB-02] diagnostics 渲染: files={} 注入 LLM (CC messages.ts:3812-3825)",
                        diagFiles.size());
                }
                return "<system-reminder>\n" + diagContent + "\n</system-reminder>";
            }
            case "agent_listing_delta": {
                // [prompt-align GLB-04] 对齐 CC utils/messages.ts:4194-4215 case 'agent_listing_delta'：
                //   三段拼装 parts.join('\n\n')（:4195-4214）：
                //   - addedLines 非空 → header = isInitial ? 'Available agent types for the Agent tool:'
                //     : 'New agent types are now available for the Agent tool:'，+ '\n' + addedLines.join('\n')
                //   - removedTypes 非空 → 'The following agent types are no longer available:\n' +
                //     removedTypes.map(t=>'- '+t).join('\n')
                //   - isInitial && showConcurrencyNote → 并发提示段
                //   wrapInSystemReminder + isMeta。addedLines 空 且 removedTypes 空 → 不注入（Java 防御；
                //   CC 不守卫空 parts 会渲染空串，Java 以 null 等价不注入）。
                //   归 subagent/agent 域：Java 无 agent_listing_delta producer → 防御纯渲染。
                AttachmentMessageDto.AgentListingDeltaRef ald = a.agentListingDelta();
                if (ald == null) {
                    return null;
                }
                java.util.List<String> aldAdded = ald.addedLines() != null ? ald.addedLines() : java.util.List.of();
                java.util.List<String> aldRemoved = ald.removedTypes() != null ? ald.removedTypes() : java.util.List.of();
                if (aldAdded.isEmpty() && aldRemoved.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[GLB-04] agent_listing_delta 渲染: added/removed 均空 → 不注入 (CC messages.ts:4194-4215)");
                    }
                    return null;
                }
                java.util.List<String> aldParts = new java.util.ArrayList<>();
                if (!aldAdded.isEmpty()) {
                    String aldHeader = ald.isInitial()
                        ? "Available agent types for the Agent tool:"
                        : "New agent types are now available for the Agent tool:";
                    aldParts.add(aldHeader + "\n" + String.join("\n", aldAdded));
                }
                if (!aldRemoved.isEmpty()) {
                    aldParts.add("The following agent types are no longer available:\n"
                        + aldRemoved.stream().map(t -> "- " + t).collect(java.util.stream.Collectors.joining("\n")));
                }
                if (ald.isInitial() && ald.showConcurrencyNote()) {
                    aldParts.add("Launch multiple agents concurrently whenever possible, to maximize performance; to do that, use a single message with multiple tool uses.");
                }
                String aldContent = String.join("\n\n", aldParts);
                if (log.isDebugEnabled()) {
                    log.debug("[GLB-04] agent_listing_delta 渲染: added={} removed={} 注入 LLM (CC messages.ts:4194-4215)",
                        aldAdded.size(), aldRemoved.size());
                }
                return "<system-reminder>\n" + aldContent + "\n</system-reminder>";
            }
            case "mcp_instructions_delta": {
                // [prompt-align GLB-05] 对齐 CC utils/messages.ts:4216-4231 case 'mcp_instructions_delta'：
                //   两段拼装 parts.join('\n\n')（:4217-4230）：
                //   - addedBlocks 非空 → '# MCP Server Instructions\n\nThe following MCP servers have provided
                //     instructions for how to use their tools and resources:\n\n' + addedBlocks.join('\n\n')
                //   - removedNames 非空 → 'The following MCP servers have disconnected. Their instructions above
                //     no longer apply:\n' + removedNames.join('\n')
                //   wrapInSystemReminder + isMeta。addedBlocks/removedNames 均空 → 不注入（Java 防御）。
                //   与 SP-30 静态 mcp_instructions section（SystemPromptSections，已对齐）区分——本 case 是
                //   MCP 指令增删事件 attachment 的动态渲染。归 mcp 域：Java 无 mcp_instructions_delta
                //   producer → 防御纯渲染。
                AttachmentMessageDto.McpInstructionsDeltaRef mid = a.mcpInstructionsDelta();
                if (mid == null) {
                    return null;
                }
                java.util.List<String> midAdded = mid.addedBlocks() != null ? mid.addedBlocks() : java.util.List.of();
                java.util.List<String> midRemoved = mid.removedNames() != null ? mid.removedNames() : java.util.List.of();
                if (midAdded.isEmpty() && midRemoved.isEmpty()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[GLB-05] mcp_instructions_delta 渲染: added/removed 均空 → 不注入 (CC messages.ts:4216-4231)");
                    }
                    return null;
                }
                java.util.List<String> midParts = new java.util.ArrayList<>();
                if (!midAdded.isEmpty()) {
                    midParts.add("# MCP Server Instructions\n\nThe following MCP servers have provided instructions for how to use their tools and resources:\n\n"
                        + String.join("\n\n", midAdded));
                }
                if (!midRemoved.isEmpty()) {
                    midParts.add("The following MCP servers have disconnected. Their instructions above no longer apply:\n"
                        + String.join("\n", midRemoved));
                }
                String midContent = String.join("\n\n", midParts);
                if (log.isDebugEnabled()) {
                    log.debug("[GLB-05] mcp_instructions_delta 渲染: added={} removed={} 注入 LLM (CC messages.ts:4216-4231)",
                        midAdded.size(), midRemoved.size());
                }
                return "<system-reminder>\n" + midContent + "\n</system-reminder>";
            }
            case "plan_file_reference": {
                // [P3-CROSS-1] 对齐 CC utils/messages.ts:3636-3643 case 'plan_file_reference'
                //   content: `A plan file exists from plan mode at: ${attachment.planFilePath}\n\nPlan contents:\n\n
                //   ${attachment.planContent}\n\nIf this plan is relevant to the current work and not already
                //   complete, continue working on it.`
                AttachmentMessageDto.PlanRef plan = a.plan();
                if (plan == null || plan.planFilePath() == null
                    || plan.planContent() == null || plan.planContent().isBlank()) {
                    return null;
                }
                return "<system-reminder>\nA plan file exists from plan mode at: " + plan.planFilePath()
                    + "\n\nPlan contents:\n\n" + plan.planContent()
                    + "\n\nIf this plan is relevant to the current work and not already complete, continue working on it.\n</system-reminder>";
            }
            case "plan_mode": {
                // [prompt-align CTX-05] 对齐 CC getPlanModeV2Instructions（messages.ts:3207-3300）——
                //   头部（Plan mode is active...+## Plan File Info+planFileInfo+READ-ONLY note :3227-3232 已对齐）
                //   之后补全 `## Plan Workflow` Phase 1-5 全文（含 agentCount/exploreAgentCount 动态插值 +
                //   getPlanPhase4Section PLAN_PHASE4_CONTROL + AskUserQuestion/ExitPlanMode/Explore/Plan 常量）。
                //   Java 常量：BuiltInAgents.EXPLORE/PLAN 已存在（:36-37），工具名用字面量 'AskUserQuestion'/
                //   'ExitPlanMode'（AskUserQuestionTool/prompt.ts:3 + ExitPlanModeTool/constants.ts:1）。
                //   CC isPlanModeInterviewPhaseEnabled（3P 默认关）与 getPewterLedgerVariant trim/cut/cap 实验臂
                //   （planModeV2.ts:5-38，Java 无 Statsig 通道）不实现，登记注释。
                //   isSubAgent 守卫：CC getPlanModeV2Instructions 对 subAgent 返回 []（:3214-3216）；Java
                //   plan_mode attachment 仅主线程生产（PermissionMode.PLAN 判定），防御返回 null。
                if (a.isSubAgent()) {
                    return null;
                }
                String planFileInfo;
                String planFilePath = a.plan() != null ? a.plan().planFilePath() : null;
                if (a.planExists() && planFilePath != null) {
                    planFileInfo = "A plan file already exists at " + planFilePath
                        + ". You can read it and make incremental edits using the Edit tool.";
                } else if (planFilePath != null) {
                    planFileInfo = "No plan file exists yet. You should create your plan at " + planFilePath
                        + " using the Write tool.";
                } else {
                    planFileInfo = "No plan file exists yet. You should create your plan and write it to the plan file.";
                }
                // CC getPlanModeV2AgentCount / getPlanModeV2ExploreAgentCount（planModeV2.ts:5-38）：
                //   env 覆盖（1-10）优先；Java 无订阅模型（max/enterprise/team=3）→ 默认 agentCount=1、
                //   exploreAgentCount=3（CC 非 max/enterprise/team 默认值）。env 读 sysprop+env 双通道。
                int agentCount = planModeV2AgentCount();
                int exploreAgentCount = planModeV2ExploreAgentCount();
                StringBuilder wf = new StringBuilder();
                wf.append("### Phase 1: Initial Understanding\n");
                wf.append("Goal: Gain a comprehensive understanding of the user's request by reading through code and asking them questions. Critical: In this phase you should only use the Explore subagent type.\n\n");
                wf.append("1. Focus on understanding the user's request and the code associated with their request. Actively search for existing functions, utilities, and patterns that can be reused — avoid proposing new code when suitable implementations already exist.\n\n");
                wf.append("2. **Launch up to ").append(exploreAgentCount).append(" Explore agents IN PARALLEL** (single message, multiple tool calls) to efficiently explore the codebase.\n");
                wf.append("   - Use 1 agent when the task is isolated to known files, the user provided specific file paths, or you're making a small targeted change.\n");
                wf.append("   - Use multiple agents when: the scope is uncertain, multiple areas of the codebase are involved, or you need to understand existing patterns before planning.\n");
                wf.append("   - Quality over quantity - ").append(exploreAgentCount).append(" agents maximum, but you should try to use the minimum number of agents necessary (usually just 1)\n");
                wf.append("   - If using multiple agents: Provide each agent with a specific search focus or area to explore. Example: One agent searches for existing implementations, another explores related components, a third investigating testing patterns\n\n");
                wf.append("### Phase 2: Design\n");
                wf.append("Goal: Design an implementation approach.\n\n");
                wf.append("Launch Plan agent(s) to design the implementation based on the user's intent and your exploration results from Phase 1.\n\n");
                wf.append("You can launch up to ").append(agentCount).append(" agent(s) in parallel.\n\n");
                wf.append("**Guidelines:**\n");
                wf.append("- **Default**: Launch at least 1 Plan agent for most tasks - it helps validate your understanding and consider alternatives\n");
                wf.append("- **Skip agents**: Only for truly trivial tasks (typo fixes, single-line changes, simple renames)\n");
                if (agentCount > 1) {
                    // CC :3257-3272 agentCount>1 才拼接的多 agent 段
                    wf.append("- **Multiple agents**: Use up to ").append(agentCount).append(" agents for complex tasks that benefit from different perspectives\n\n");
                    wf.append("Examples of when to use multiple agents:\n");
                    wf.append("- The task touches multiple parts of the codebase\n");
                    wf.append("- It's a large refactor or architectural change\n");
                    wf.append("- There are many edge cases to consider\n");
                    wf.append("- You'd benefit from exploring different approaches\n\n");
                    wf.append("Example perspectives by task type:\n");
                    wf.append("- New feature: simplicity vs performance vs maintainability\n");
                    wf.append("- Bug fix: root cause vs workaround vs prevention\n");
                    wf.append("- Refactoring: minimal change vs clean architecture\n");
                }
                wf.append("\nIn the agent prompt:\n");
                wf.append("- Provide comprehensive background context from Phase 1 exploration including filenames and code path traces\n");
                wf.append("- Describe requirements and constraints\n");
                wf.append("- Request a detailed implementation plan\n\n");
                wf.append("### Phase 3: Review\n");
                wf.append("Goal: Review the plan(s) from Phase 2 and ensure alignment with the user's intentions.\n");
                wf.append("1. Read the critical files identified by agents to deepen your understanding\n");
                wf.append("2. Ensure that the plans align with the user's original request\n");
                wf.append("3. Use AskUserQuestion to clarify any remaining questions with the user\n\n");
                // CC getPlanPhase4Section（messages.ts:3190-3204）：getPewterLedgerVariant()=null 默认 → PLAN_PHASE4_CONTROL（:3156-3164）
                wf.append("### Phase 4: Final Plan\n");
                wf.append("Goal: Write your final plan to the plan file (the only file you can edit).\n");
                wf.append("- Begin with a **Context** section: explain why this change is being made — the problem or need it addresses, what prompted it, and the intended outcome\n");
                wf.append("- Include only your recommended approach, not all alternatives\n");
                wf.append("- Ensure that the plan file is concise enough to scan quickly, but detailed enough to execute effectively\n");
                wf.append("- Include the paths of critical files to be modified\n");
                wf.append("- Reference existing functions and utilities you found that should be reused, with their file paths\n");
                wf.append("- Include a verification section describing how to test the changes end-to-end (run the code, use MCP tools, run tests)\n\n");
                wf.append("### Phase 5: Call ExitPlanMode\n");
                wf.append("At the very end of your turn, once you have asked the user questions and are happy with your final plan file - you should always call ExitPlanMode to indicate to the user that you are done planning.\n");
                wf.append("This is critical - your turn should only end with either using the AskUserQuestion tool OR calling ExitPlanMode. Do not stop unless it's for these 2 reasons\n\n");
                wf.append("**Important:** Use AskUserQuestion ONLY to clarify requirements or choose between approaches. Use ExitPlanMode to request plan approval. Do NOT ask about plan approval in any other way - no text questions, no AskUserQuestion. Phrases like \"Is this plan okay?\", \"Should I proceed?\", \"How does this plan look?\", \"Any changes before we start?\", or similar MUST use ExitPlanMode.\n\n");
                wf.append("NOTE: At any point in time through this workflow you should feel free to ask the user questions or clarifications using the AskUserQuestion tool. Don't make large assumptions about user intent. The goal is to present a well researched plan to the user, and tie any loose ends before implementation begins.");

                String planModeContent = "Plan mode is active. The user indicated that they do not want you to execute yet "
                    + "-- you MUST NOT make any edits (with the exception of the plan file mentioned below), run any "
                    + "non-readonly tools (including changing configs or making commits), or otherwise make any changes "
                    + "to the system. This supercedes any other instructions you have received.\n\n## Plan File Info:\n"
                    + planFileInfo
                    + "\nYou should build your plan incrementally by writing to or editing this file. NOTE that this is "
                    + "the only file you are allowed to edit - other than this you are only allowed to take READ-ONLY actions."
                    + "\n\n## Plan Workflow\n\n"
                    + wf;
                return "<system-reminder>\n" + planModeContent + "\n</system-reminder>";
            }
            case "plan_mode_reentry": {
                // [WF6 RC-2] 对齐 CC utils/messages.ts:3829-3846 case 'plan_mode_reentry'：
                //   一次性提示模型重新进入 plan 模式，先读旧 plan 再决定覆盖/续写。
                AttachmentMessageDto.PlanRef reentryPlan = a.plan();
                String reentryPath = reentryPlan != null ? reentryPlan.planFilePath() : null;
                if (reentryPath == null) {
                    return null;
                }
                String reentryContent = "## Re-entering Plan Mode\n\n"
                    + "You are returning to plan mode after having previously exited it. A plan file exists at "
                    + reentryPath + " from your previous planning session.\n\n"
                    + "**Before proceeding with any new planning, you should:**\n"
                    + "1. Read the existing plan file to understand what was previously planned\n"
                    + "2. Evaluate the user's current request against that plan\n"
                    + "3. Decide how to proceed:\n"
                    + "   - **Different task**: If the user's request is for a different task—even if it's similar or related—start fresh by overwriting the existing plan\n"
                    + "   - **Same task, continuing**: If this is explicitly a continuation or refinement of the exact same task, modify the existing plan while cleaning up outdated or irrelevant sections\n"
                    + "4. Continue on with the plan process and most importantly you should always edit the plan file one way or the other before calling ExitPlanMode\n\n"
                    + "Treat this as a fresh planning session. Do not assume the existing plan is relevant without evaluating it first.";
                return "<system-reminder>\n" + reentryContent + "\n</system-reminder>";
            }
            case "plan_mode_exit": {
                // [WF6 RC-2] 对齐 CC utils/messages.ts:3848-3857 case 'plan_mode_exit'：
                //   一次性提示模型已退出 plan 模式可执行变更；planExists → 附 plan 文件路径。
                AttachmentMessageDto.PlanRef exitPlan = a.plan();
                String exitPath = exitPlan != null ? exitPlan.planFilePath() : null;
                String planReference = a.planExists() && exitPath != null
                    ? " The plan file is located at " + exitPath + " if you need to reference it."
                    : "";
                String exitContent = "## Exited Plan Mode\n\n"
                    + "You have exited plan mode. You can now make edits, run tools, and take actions."
                    + planReference;
                return "<system-reminder>\n" + exitContent + "\n</system-reminder>";
            }
            case "auto_mode": {
                // [prompt-align GLB-03] 对齐 CC getAutoModeInstructions（utils/messages.ts:3419-3451）：
                //   attachment.reminderType 'full'|'sparse' 分派（CC original: attachment.reminderType,
                //   :3419-3426）—— sparse 前缀 → getAutoModeSparseInstructions（:3446 单行）；其余（含 null）
                //   → getAutoModeFullInstructions（:3428-3438 全文：'## Auto Mode Active' + 6 条 bullet，
                //   em dash U+2014）。wrapInSystemReminder `<system-reminder>\n${content}\n</system-reminder>`
                //   （:3097-3100）+ isMeta。
                //   门控归 producer 侧（permissions 域未来接线）：CC getAutoModeAttachments
                //   （utils/attachments.ts:1336-1373）按 runtime permissionContext.mode==='auto' ||
                //   (mode==='plan' && isAutoModeActive())（:1341-1347）+ turn 节流（:1349-1361）+ full/sparse
                //   周期（:1363-1371）产出；Java 无 auto_mode producer（生产零命中）→ 本 case 为防御纯渲染。
                String autoContent = "sparse".equals(a.reminderType())
                    ? "Auto mode still active (see full instructions earlier in conversation). Execute autonomously, minimize interruptions, prefer action over planning."
                    : "## Auto Mode Active\n\n"
                        + "Auto mode is active. The user chose continuous, autonomous execution. You should:\n\n"
                        + "1. **Execute immediately** — Start implementing right away. Make reasonable assumptions and proceed on low-risk work.\n"
                        + "2. **Minimize interruptions** — Prefer making reasonable assumptions over asking questions for routine decisions.\n"
                        + "3. **Prefer action over planning** — Do not enter plan mode unless the user explicitly asks. When in doubt, start coding.\n"
                        + "4. **Expect course corrections** — The user may provide suggestions or course corrections at any point; treat those as normal input.\n"
                        + "5. **Do not take overly destructive actions** — Auto mode is not a license to destroy. Anything that deletes data or modifies shared or production systems still needs explicit user confirmation. If you reach such a decision point, ask and wait, or course correct to a safer method instead.\n"
                        + "6. **Avoid data exfiltration** — Post even routine messages to chat platforms or work tickets only if the user has directed you to. You must not share secrets (e.g. credentials, internal documentation) unless the user has explicitly authorized both that specific secret and its destination.";
                if (log.isDebugEnabled()) {
                    log.debug("[GLB-03] auto_mode 渲染: reminderType={} 注入 LLM (CC messages.ts:3419-3451)",
                        a.reminderType());
                }
                return "<system-reminder>\n" + autoContent + "\n</system-reminder>";
            }
            case "auto_mode_exit": {
                // [prompt-align GLB-03] 对齐 CC utils/messages.ts:3863-3871 case 'auto_mode_exit'：
                //   静态文本 '## Exited Auto Mode'（:3864-3866 逐字），无 payload 守卫。wrapInSystemReminder
                //   + isMeta。门控归 producer 侧：CC getAutoModeExitAttachment（utils/attachments.ts:1380-1399）
                //   需 needsAutoModeExitAttachment() 且非 auto 活动态；Java 无 producer（生产零命中）→ 防御纯渲染。
                String autoExitContent = "## Exited Auto Mode\n\n"
                    + "You have exited auto mode. The user may now want to interact more directly. "
                    + "You should ask clarifying questions when the approach is ambiguous rather than making assumptions.";
                if (log.isDebugEnabled()) {
                    log.debug("[GLB-03] auto_mode_exit 渲染: 静态文本注入 LLM (CC messages.ts:3863-3871)");
                }
                return "<system-reminder>\n" + autoExitContent + "\n</system-reminder>";
            }
            case "task_status": {
                // [P3-CROSS-1] 对齐 CC utils/messages.ts:3954-4024 case 'task_status' 三状态分支
                //   (killed 简短 / running 防重复 spawn 提示 / completed 含 deltaSummary+outputFilePath)
                AttachmentMessageDto.TaskStatusRef t = a.taskStatus();
                if (t == null) {
                    return null;
                }
                String displayStatus = "killed".equals(t.status()) ? "stopped" : t.status();
                if ("killed".equals(t.status())) {
                    // CC:3958-3969 — 中断的工作, 原始 transcript delta 无用 → 简短提示
                    return "<system-reminder>\nTask \"" + t.description() + "\" (" + t.taskId()
                        + ") was stopped by the user.\n</system-reminder>";
                }
                if ("running".equals(t.status())) {
                    // CC:3973-3995 — 防重复 spawn (该 attachment 仅压缩后 emit, 原始 spawn 消息已消失)
                    // [prompt-align CTX-08] 工具名细节：running 分支补 `with ${SEND_MESSAGE_TOOL_NAME}`（'SendMessage'，
                    //   SendMessageTool/constants.ts:1）；TASK_OUTPUT_TOOL_NAME 全名 'TaskOutput'
                    //   （TaskOutputTool/constants.ts:1）替代 Java 'task_output tool' 缩写。
                    StringBuilder parts = new StringBuilder("Background agent \"" + t.description() + "\" ("
                        + t.taskId() + ") is still running.");
                    if (t.deltaSummary() != null && !t.deltaSummary().isBlank()) {
                        parts.append(" Progress: ").append(t.deltaSummary());
                    }
                    if (t.outputFilePath() != null && !t.outputFilePath().isBlank()) {
                        parts.append(" Do NOT spawn a duplicate. You will be notified when it completes. You can read partial output at ")
                            .append(t.outputFilePath()).append(" or send it a message with SendMessage.");
                    } else {
                        parts.append(" Do NOT spawn a duplicate. You will be notified when it completes. You can check its progress with the TaskOutput tool or send it a message with SendMessage.");
                    }
                    return "<system-reminder>\n" + parts + "\n</system-reminder>";
                }
                // CC:3997-4024 — completed/failed 含完整 delta
                StringBuilder msg = new StringBuilder("Task " + t.taskId());
                msg.append(" (type: ").append(t.taskType()).append(")");
                msg.append(" (status: ").append(displayStatus).append(")");
                msg.append(" (description: ").append(t.description()).append(")");
                if (t.deltaSummary() != null && !t.deltaSummary().isBlank()) {
                    msg.append(" Delta: ").append(t.deltaSummary());
                }
                if (t.outputFilePath() != null && !t.outputFilePath().isBlank()) {
                    msg.append(" Read the output file to retrieve the result: ").append(t.outputFilePath());
                } else {
                    // [prompt-align CTX-08] completed 无文件分支补 ${TASK_OUTPUT_TOOL_NAME} 全名（'TaskOutput'）
                    msg.append(" You can check its output using the TaskOutput tool.");
                }
                return "<system-reminder>\n" + msg + "\n</system-reminder>";
            }
            case "token_usage": {
                // [prompt-align CTX-06] 对齐 CC utils/messages.ts:4058-4064 normalizeAttachmentForAPI
                //   case 'token_usage'：`Token usage: ${used}/${total}; ${remaining} remaining`（:4062）
                //   wrapInSystemReminder（:3097-3100）+ isMeta:true。生产方 CC getTokenUsageAttachment
                //   （attachments.ts:3806-3821）门控 env CLAUDE_CODE_ENABLE_TOKEN_USAGE_ATTACHMENT；
                //   used=tokenCountFromLastAPIResponse / total=getEffectiveContextWindowSize /
                //   remaining=total-used。缺任一数值 → null（Java 防御；CC 恒三值齐备）。
                Integer tokUsed = a.tokenUsed();
                Integer tokTotal = a.tokenTotal();
                Integer tokRemaining = a.tokenRemaining();
                if (tokUsed == null || tokTotal == null || tokRemaining == null) {
                    return null;
                }
                return "<system-reminder>\nToken usage: " + tokUsed + "/" + tokTotal
                    + "; " + tokRemaining + " remaining\n</system-reminder>";
            }
            case "budget_usd": {
                // [prompt-align CTX-07] 对齐 CC utils/messages.ts:4066-4074 normalizeAttachmentForAPI
                //   case 'budget_usd'：`USD budget: $${used}/$${total}; $${remaining} remaining`（:4070）
                //   wrapInSystemReminder + isMeta:true。生产方 CC getMaxBudgetUsdAttachment
                //   （attachments.ts:3846-3858）门控 maxBudgetUsd!==undefined；used=getTotalCostUSD /
                //   total=maxBudgetUsd / remaining=maxBudgetUsd-used。Java 无 maxBudgetUsd 配置源
                //   （全仓 grep 零命中）→ producer 接线登记 N/A，本 render case 防御保留。缺值 → null。
                Integer budUsed = a.budgetUsed();
                Integer budTotal = a.budgetTotal();
                Integer budRemaining = a.budgetRemaining();
                if (budUsed == null || budTotal == null || budRemaining == null) {
                    return null;
                }
                return "<system-reminder>\nUSD budget: $" + budUsed + "/$" + budTotal
                    + "; $" + budRemaining + " remaining\n</system-reminder>";
            }
            case "output_token_usage": {
                // [ER-IMP-2026-04 P-21] 对齐 CC utils/messages.ts:4076-4089
                // normalizeAttachmentForAPI case 'output_token_usage':
                //   turnText = budget !== null ? `${formatNumber(turn)} / ${formatNumber(budget)}`
                //                             : formatNumber(turn)（:4078-4080）
                //   content = `Output tokens — turn: ${turnText} · session: ${formatNumber(session)}`
                //   wrapInSystemReminder + isMeta: true（:4083-4086）
                //   formatNumber = compact 小写 k/m/b（utils/format.ts:124-131），非千分位；
                //   与 AttachmentMessageDto.outputTokenUsage 工厂共用 formatOutputTokenNumber 单源。
                Integer outTurn = a.outputTokenTurn();
                Integer outSession = a.outputTokenSession();
                Integer outBudget = a.outputTokenBudget();
                if (outTurn == null || outSession == null) {
                    return null;
                }
                String turnText = outBudget != null
                    ? AttachmentMessageDto.formatOutputTokenNumber(outTurn) + " / "
                        + AttachmentMessageDto.formatOutputTokenNumber(outBudget)
                    : AttachmentMessageDto.formatOutputTokenNumber(outTurn);
                return "<system-reminder>\nOutput tokens \u2014 turn: " + turnText
                    + " \u00b7 session: " + AttachmentMessageDto.formatOutputTokenNumber(outSession)
                    + "\n</system-reminder>";
            }
            case "verify_plan_reminder": {
                // [prompt-align GLB-07] 对齐 CC utils/messages.ts:4240-4251 case 'verify_plan_reminder'：
                //   toolName = CLAUDE_CODE_VERIFY_PLAN==='true' ? 'VerifyPlanExecution' : ''（:4243-4246，
                //   外部构建恒 ''；Java env 读 sysprop+env 双通道，对齐 readPlanModeV2Env 前例）→
                //   content = `You have completed implementing the plan. Please call the "${toolName}" tool
                //   directly (NOT the ${AGENT_TOOL_NAME} tool or an agent) to verify that all plan items were
                //   completed correctly.`（:4247，AGENT_TOOL_NAME='Agent' 常量化）。wrapInSystemReminder + isMeta。
                //   恒渲染（无 payload 守卫）。门控归 producer 侧（plan 域未来接线）：Java 无
                //   verify_plan_reminder producer（生产零命中）→ 防御纯渲染；全局 settings 列门控
                //   resolver.verifyPlanReminderEnabled()（PromptAlignSettingsResolver:194，G0-03
                //   settings 全局 12 列 verify_plan_reminder_enabled，非会话列）接线点属
                //   producer 注入，render 不读。
                String vpToolName = "true".equals(readVerifyPlanEnv())
                    ? ToolNameConstants.VERIFY_PLAN_EXECUTION_TOOL_NAME : "";
                String vpContent = "You have completed implementing the plan. Please call the \""
                    + vpToolName + "\" tool directly (NOT the " + AgentToolConstants.AGENT_TOOL_NAME
                    + " tool or an agent) to verify that all plan items were completed correctly.";
                if (log.isDebugEnabled()) {
                    log.debug("[GLB-07] verify_plan_reminder 渲染: toolName='{}' 注入 LLM (CC messages.ts:4240-4251)",
                        vpToolName);
                }
                return "<system-reminder>\n" + vpContent + "\n</system-reminder>";
            }
            // ─── [prompt-align GLB-10] CTX-S1..S9 九渲染分支 · 对齐 CC messages.ts 各 case ───
            //   全部 wrapInSystemReminder 形状（<system-reminder>\n{content}\n</system-reminder>,
            //   messages.ts:3097-3100）；九分支 Java 均无 producer（Web N/A）→ 防御纯渲染
            //   （对齐批次G directory/diagnostics 范式），producer 接线登记各域未来。
            case "date_change": {
                // CC messages.ts:4163-4167 case 'date_change'：content = `The date has changed. Today's
                //   date is now ${attachment.newDate}. DO NOT mention this to the user explicitly because
                //   they are already aware.`（wrapMessagesInSystemReminder + isMeta）。撇号 's 逐字。
                String dc = a.dateChange();
                return (dc == null || dc.isBlank()) ? null
                    : "<system-reminder>\nThe date has changed. Today's date is now " + dc
                        + ". DO NOT mention this to the user explicitly because they are already aware.\n</system-reminder>";
            }
            case "ultrathink_effort": {
                // CC messages.ts:4168-4172 case 'ultrathink_effort'：content = `The user has requested
                //   reasoning effort level: ${attachment.level}. Apply this to the current turn.`
                String ul = a.ultrathinkLevel();
                return (ul == null || ul.isBlank()) ? null
                    : "<system-reminder>\nThe user has requested reasoning effort level: " + ul
                        + ". Apply this to the current turn.\n</system-reminder>";
            }
            case "critical_system_reminder": {
                // CC messages.ts:3872-3875 case 'critical_system_reminder'：content = attachment.content
                //   （wrapMessagesInSystemReminder + isMeta）。复用 a.content()（空 → null 守卫）。
                return (content == null || content.isBlank()) ? null
                    : "<system-reminder>\n" + content + "\n</system-reminder>";
            }
            case "mcp_resource": {
                // CC messages.ts:3877-3952 case 'mcp_resource'：空 contents → `<mcp-resource server=".."
                //   uri="..">(No content)</mcp-resource>`；text 块 → 'Full contents of resource:' +
                //   itemText + 'Do NOT read this resource again...'；blob → '[Binary content: {mimeType}]'
                //   （Java 无 mimeType 承载 → producer 侧常量 application/octet-stream，已知差异）。
                //   Java McpResourceRef.contents 单 String 承载，render 走 text 块形状（无 producer，
                //   防御纯渲染，blob 占位由 producer 决定）。
                AttachmentMessageDto.McpResourceRef mcp = a.mcpResource();
                if (mcp == null || mcp.server() == null || mcp.uri() == null) {
                    return null;
                }
                String mcpContents = mcp.contents();
                if (mcpContents == null || mcpContents.isBlank()) {
                    return "<system-reminder>\n<mcp-resource server=\"" + mcp.server() + "\" uri=\""
                        + mcp.uri() + "\">(No content)</mcp-resource>\n</system-reminder>";
                }
                return "<system-reminder>\nFull contents of resource:\n" + mcpContents
                    + "\nDo NOT read this resource again unless you think it may have changed, since you already have the full contents.\n</system-reminder>";
            }
            case "agent_mention": {
                // CC messages.ts:3947-3952 case 'agent_mention'：content = `The user has expressed a
                //   desire to invoke the agent "${attachment.agentType}". Please invoke the agent
                //   appropriately, passing in the required context to it. `（句尾含空格，逐字对齐）。
                String agt = a.agentType();
                return (agt == null || agt.isBlank()) ? null
                    : "<system-reminder>\nThe user has expressed a desire to invoke the agent \"" + agt
                        + "\". Please invoke the agent appropriately, passing in the required context to it. \n</system-reminder>";
            }
            case "compaction_reminder":
                // CC messages.ts:4139-4146 case 'compaction_reminder'：静态长文本（无 payload），
                //   em dash U+2014 逐字对齐。恒渲染。
                return "<system-reminder>\nAuto-compact is enabled. When the context window is nearly full, older messages will be automatically summarized so you can continue working seamlessly. There is no need to stop or rush — you have unlimited context through automatic compaction.\n</system-reminder>";
            case "selected_lines_in_ide": {
                // CC messages.ts:3613-3621 case 'selected_lines_in_ide'：content >2000 截断 +
                //   '\n... (truncated)'（ASCII 三点）；content = `The user selected the lines ${lineStart}
                //   to ${lineEnd} from ${filename}:\n${content}\n\nThis may or may not be related to the
                //   current task.`
                AttachmentMessageDto.LineSelectionRef sel = a.lineSelection();
                if (sel == null || sel.filename() == null || sel.lineStart() == null || sel.lineEnd() == null) {
                    return null;
                }
                String selContent = sel.content() != null ? sel.content() : "";
                if (selContent.length() > 2000) {
                    selContent = selContent.substring(0, 2000) + "\n... (truncated)";
                }
                return "<system-reminder>\nThe user selected the lines " + sel.lineStart() + " to "
                    + sel.lineEnd() + " from " + sel.filename() + ":\n" + selContent
                    + "\n\nThis may or may not be related to the current task.\n</system-reminder>";
            }
            case "opened_file_in_ide": {
                // CC messages.ts:3622-3627 case 'opened_file_in_ide'：content = `The user opened the file
                //   ${filename} in the IDE. This may or may not be related to the current task.`（复用
                //   LineSelectionRef.filename）
                AttachmentMessageDto.LineSelectionRef sel = a.lineSelection();
                if (sel == null || sel.filename() == null) {
                    return null;
                }
                return "<system-reminder>\nThe user opened the file " + sel.filename()
                    + " in the IDE. This may or may not be related to the current task.\n</system-reminder>";
            }
            case "edited_text_file": {
                // CC messages.ts:3538-3543 case 'edited_text_file'：content = `Note: ${filename} was
                //   modified, either by the user or by a linter. This change was intentional, so make sure
                //   to take it into account as you proceed (ie. don't revert it unless the user asks you
                //   to). Don't tell the user this, since they are already aware. Here are the relevant
                //   changes (shown with line numbers):\n${snippet}`（复用 LineSelectionRef.filename/snippet）
                AttachmentMessageDto.LineSelectionRef sel = a.lineSelection();
                if (sel == null || sel.filename() == null || sel.snippet() == null) {
                    return null;
                }
                return "<system-reminder>\nNote: " + sel.filename()
                    + " was modified, either by the user or by a linter. This change was intentional, so make sure to take it into account as you proceed (ie. don't revert it unless the user asks you to). Don't tell the user this, since they are already aware. Here are the relevant changes (shown with line numbers):\n"
                    + sel.snippet() + "\n</system-reminder>";
            }
            // ─── [prompt-align GLB-06] companion_intro 修复对齐 · CC messages.ts:4232-4239 +
            //     buddy/prompt.ts:7-14 companionIntroText 全文（em dash U+2014 + 撇号逐字对齐）───
            case "companion_intro": {
                // CC buddy/prompt.ts:7-14 companionIntroText 全文；name/species null → 防御不注入。
                //   Java companion producer 未接线（Web 前端 companion 场景未来）→ 防御纯渲染 +
                //   登记 producer 待前端对接.md。
                AttachmentMessageDto.CompanionRef comp = a.companion();
                if (comp == null || comp.name() == null || comp.species() == null) {
                    return null;
                }
                String compName = comp.name();
                return "<system-reminder>\n# Companion\n\nA small " + comp.species() + " named " + compName
                    + " sits beside the user's input box and occasionally comments in a speech bubble. You're not " + compName
                    + " — it's a separate watcher.\n\nWhen the user addresses " + compName
                    + " directly (by name), its bubble will answer. Your job in that moment is to stay out of the way: respond in ONE line or less, or just answer any part of the message meant for you. Don't explain that you're not " + compName
                    + " — they know. Don't narrate what " + compName
                    + " might say — the bubble handles that.\n</system-reminder>";
            }
            default:
                // hook_cancelled / hook_error_during_execution / hook_non_blocking_error /
                // hook_system_message / hook_permission_decision / async_hook_response /
                // todo_reminder / task_reminder / max_turns_reached / dynamic_skill → 不渲染 (CC :4255-4260 返回 [])
                return null;
        }
    }

    /**
     * [prompt-align GLB-09] 文件大小人类可读格式化 · 对齐 CC utils/format.ts:9-23
     * {@code formatFileSize}：{@code kb = size/1024}；kb<1 → '{n} bytes'；kb<1024 →
     * '{kb.toFixed(1).replace(/\.0$/,'')}KB'；mb<1024 → MB；否则 GB。
     * JS toFixed(1) 四舍五入 + replace 去尾 '.0' → Java {@code String.format("%.1f")}
     * （HALF_UP）+ 去尾 '.0' 等价。
     *
     * @param sizeInBytes 文件字节数（≥0）
     * @return 人类可读大小（如 '2.0KB'→'2KB'、'900 bytes'、'1.5MB'、'3GB'）
     */
    private static String formatFileSize(long sizeInBytes) {
        double kb = sizeInBytes / 1024.0;
        if (kb < 1) {
            return sizeInBytes + " bytes";
        }
        if (kb < 1024) {
            return trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", kb)) + "KB";
        }
        double mb = kb / 1024;
        if (mb < 1024) {
            return trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", mb)) + "MB";
        }
        double gb = mb / 1024;
        return trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", gb)) + "GB";
    }

    /** 去尾 '.0' · 对齐 CC {@code .replace(/\.0$/, '')}（仅末尾一处，非全局）。 */
    private static String trimTrailingZero(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /**
     * [prompt-align GLB-02] LSP 诊断摘要格式化 · 对齐 CC
     * services/diagnosticTracking.ts:352-380 {@code DiagnosticTrackingService.formatDiagnosticsSummary}：
     * <pre>
     *   per file:   {filename}:\n{diagnostics}            filename = uri.split('/').pop() || uri
     *   per diag:   '  {severitySymbol} [Line {l+1}:{c+1}] {message}{ [code]}{(source)}' join '\n'
     *   files:      join '\n\n'
     *   >4000(MAX_DIAGNOSTICS_SUMMARY_CHARS) → slice(0, 4000-13) + '…[truncated]'（U+2026）
     * </pre>
     *
     * @param files 诊断文件列表（uri + diagnostics[]）
     * @return 格式化摘要（空列表 → 空串，调用方已守卫）
     */
    private static String formatDiagnosticsSummary(
            java.util.List<AttachmentMessageDto.DiagnosticsFileRef> files) {
        final int maxChars = 4000; // CC MAX_DIAGNOSTICS_SUMMARY_CHARS=4000 (diagnosticTracking.ts:12)
        final String truncationMarker = "…[truncated]"; // CC '…[truncated]' (diagnosticTracking.ts:353)
        String result = files.stream()
            .map(f -> {
                String uri = f.uri() != null ? f.uri() : "";
                // CC uri.split('/').pop() || uri（无 '/' 分段时回退 uri 全串）
                String filename = uri.contains("/") ? uri.substring(uri.lastIndexOf('/') + 1) : uri;
                StringBuilder diagLines = new StringBuilder();
                if (f.diagnostics() != null) {
                    for (AttachmentMessageDto.DiagnosticItemRef d : f.diagnostics()) {
                        if (diagLines.length() > 0) {
                            diagLines.append('\n');
                        }
                        diagLines.append("  ").append(getSeveritySymbol(d.severity()))
                            .append(" [Line ").append(d.line() + 1)
                            .append(":").append(d.character() + 1).append("] ")
                            .append(d.message() != null ? d.message() : "")
                            .append(d.code() != null && !d.code().isBlank() ? " [" + d.code() + "]" : "")
                            .append(d.source() != null && !d.source().isBlank() ? " (" + d.source() + ")" : "");
                    }
                }
                return filename + ":\n" + diagLines;
            })
            .collect(java.util.stream.Collectors.joining("\n\n"));
        if (result.length() > maxChars) {
            return result.substring(0, maxChars - truncationMarker.length()) + truncationMarker;
        }
        return result;
    }

    /**
     * [prompt-align GLB-02] 诊断严重级别符号映射 · 对齐 CC
     * services/diagnosticTracking.ts:385-393 {@code getSeveritySymbol}（npm figures 包常量，
     * Java 喂 LLM 用 Unicode 原值）：Error→✖ / Warning→⚠ / Info→ℹ / Hint→★ / 其他→·。
     *
     * @param severity CC original: d.severity
     * @return figures 符号
     */
    private static String getSeveritySymbol(String severity) {
        if ("Error".equals(severity)) {
            return "✖";   // figures.cross ✖
        }
        if ("Warning".equals(severity)) {
            return "⚠";   // figures.warning ⚠
        }
        if ("Info".equals(severity)) {
            return "ℹ";   // figures.info ℹ
        }
        if ("Hint".equals(severity)) {
            return "★";   // figures.star ★
        }
        return "·";       // figures.bullet ·
    }

    /** isMeta=true 的 user-role 系统消息 · 对齐 CC createUserMessage({content, isMeta:true}). */
    private static ChatMessageDto metaUserMessage(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "system",
            content, null, java.util.List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, java.util.List.of(), java.util.List.of(), null, true);
    }

    /**
     * userContext 前置 meta user 消息 · 对齐 CC {@code prependUserContext}
     * （CC original: utils/api.ts:449-474）。
     *
     * <p>把 user 通道上下文（claudeMd? / currentDate）渲染为 {@code <system-reminder>} 包裹的
     * isMeta user message 置于消息队首（CLAUDE.md 顶部上下文即由此通道注入）。空 map → 原样返回。
     *
     * @param messages    当前 LLM 调用消息（memory/todo/hook attachment 已注入后）
     * @param userContext user 通道上下文 map（CC getUserContext 产物：claudeMd? + currentDate）
     * @return 前置后的消息列表（空 context → 原列表）
     */
    public static java.util.List<ChatMessageDto> prependUserContext(
            java.util.List<ChatMessageDto> messages,
            java.util.Map<String, String> userContext) {
        if (userContext == null || userContext.isEmpty()) {
            return messages;
        }
        StringBuilder content = new StringBuilder("<system-reminder>\n"
            + "As you answer the user's questions, you can use the following context:\n");
        int idx = 0;
        for (java.util.Map.Entry<String, String> e : userContext.entrySet()) {
            if (idx++ > 0) {
                content.append('\n');
            }
            content.append("# ").append(e.getKey()).append('\n').append(e.getValue());
        }
        content.append("\n\n      IMPORTANT: this context may or may not be relevant to your tasks. "
            + "You should not respond to this context unless it is highly relevant to your task.\n</system-reminder>\n");
        java.util.List<ChatMessageDto> result = new java.util.ArrayList<>(messages);
        result.add(0, metaUserMessage(content.toString()));
        return result;
    }

    /**
     * 提取 hookSpecificOutput.additionalContext · 对齐 CC messages.ts:4030
     * {@code 'additionalContext' in response.hookSpecificOutput}.
     *
     * <p>15 子类型中 8 个含 additionalContext 字段 (CC coreSchemas.ts:806-862).
     */
    private static String additionalContextOf(HookJSONOutput.SyncHookOutput sync) {
        if (sync == null || sync.hookSpecificOutput() == null) {
            return null;
        }
        HookSpecificOutput hso = sync.hookSpecificOutput();
        return switch (hso) {
            case HookSpecificOutput.PreToolUse pre -> pre.additionalContext();
            case HookSpecificOutput.UserPromptSubmit up -> up.additionalContext();
            case HookSpecificOutput.SessionStart ss -> ss.additionalContext();
            case HookSpecificOutput.Setup setup -> setup.additionalContext();
            case HookSpecificOutput.SubagentStart sa -> sa.additionalContext();
            case HookSpecificOutput.PostToolUse post -> post.additionalContext();
            case HookSpecificOutput.PostToolUseFailure pf -> pf.additionalContext();
            case HookSpecificOutput.Notification n -> n.additionalContext();
            default -> null;
        };
    }
}
