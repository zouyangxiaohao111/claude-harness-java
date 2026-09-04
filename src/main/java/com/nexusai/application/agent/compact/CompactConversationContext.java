package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 全量压缩上下文 · 对齐 CC {@code compactConversation} 的 {@code ToolUseContext} 依赖面
 * （compact.ts:387-395 context 参数 + 后续 context.* 调用点）。
 *
 * <p><b>WHY 存在（IMP-04）</b>: CC {@code compactConversation(messages, context, cacheSafeParams,
 * suppressFollowUpQuestions, customInstructions, isAutoCompact, recompactionInfo)} 经
 * {@code context} 消费事件流 / SDK 状态 / 摘要生产 / hooks / readFileState / 通知。
 * Java 端无 CC ToolUseContext 单对象，故本类把 CC 依赖面参数化为可注入字段
 * （默认 no-op / null 安全），生产调用方（IMP-07/10/12）按 Spring bean 接线。
 *
 * <h2>CC 依赖面 → 本字段（grep -n 自验 2026-08-04）</h2>
 * <table>
 *   <tr><th>CC 调用点</th><th>本字段</th></tr>
 *   <tr><td>context.onCompactProgress?.(event)（compact.ts:406/429/587/719/760）</td><td>onCompactProgress</td></tr>
 *   <tr><td>context.setSDKStatus?.(status)（compact.ts:412/761）</td><td>sdkStatusSetter</td></tr>
 *   <tr><td>context.setStreamMode?.(mode)（compact.ts:427/758）</td><td>streamModeSetter</td></tr>
 *   <tr><td>context.setResponseLength?.(len)（compact.ts:428/759）</td><td>responseLengthSetter</td></tr>
 *   <tr><td>context.abortController.signal（compact.ts:418）</td><td>abortController</td></tr>
 *   <tr><td>context.readFileState（compact.ts:518-521）</td><td>readFileState</td></tr>
 *   <tr><td>context.addNotification?.(notif)（compact.ts:1116）</td><td>notification</td></tr>
 *   <tr><td>context.options.querySource（compact.ts:648）</td><td>querySource</td></tr>
 *   <tr><td>context.agentId（compact.ts:545）</td><td>agentId</td></tr>
 *   <tr><td>context.options.mainLoopModel（compact.ts:594）</td><td>model</td></tr>
 * </table>
 *
 * <p><b>生产接线</b>: 摘要生产 {@link #setSummaryProducer}（默认接
 * {@link StreamCompactSummary#streamCompactSummary} 适配，IMP-01 产物）；
 * notifyCompaction（{@link com.nexusai.application.agent.lsp.PromptCacheBreakDetection#notifyCompaction}，
 * IMP-08 产物）；readFileState 由调用方传入当前文件状态缓存。
 */
public class CompactConversationContext {

    private static final Logger log = LoggerFactory.getLogger(CompactConversationContext.class);

    /** appState 中 toolPermissionContext 键 · CC original: toolPermissionContext（EnterPlanModeTool.ts:88-94）。 */
    private static final String KEY_TOOL_PERMISSION_CONTEXT = "toolPermissionContext";

    private String sessionId;
    private String agentId;
    private String model;
    private String agentType;
    private String querySource = "compact";
    private Path workspaceDir;
    private SessionStorage.SessionMetadata sessionMetadata;

    /** CC context.onCompactProgress?.(event) — 单流程 5 事件（INV-1） */
    private Consumer<CompactProgressEvent> onCompactProgress = event -> { };
    /** CC context.setSDKStatus?.(status) */
    private Consumer<SDKStatus> sdkStatusSetter = status -> { };
    /** CC context.setStreamMode?.(mode) */
    private Consumer<SpinnerMode> streamModeSetter = mode -> { };
    /** CC context.setResponseLength?.(len) */
    private Consumer<Integer> responseLengthSetter = len -> { };
    /** CC context.abortController（null → NOOP） */
    private AbortController abortController = AbortController.NOOP;

    /** 摘要生产（CC streamCompactSummary；必填，生产由 IMP-01 适配器提供） */
    private CompactConversation.SummaryProducer summaryProducer;

    /** PreCompact/PostCompact/SessionStart hooks 执行器（null → hooks 跳过） */
    private HookRegistry hookRegistry;

    /** CC context.readFileState（path → {content, timestamp}；压缩后清空 + 附件恢复） */
    private Map<String, CompactConversation.ReadFileState> readFileState;

    /** CC notifyCompaction（bootstrap/state.ts:704 / PROMPT_CACHE_BREAK_DETECTION gate） */
    private Runnable notifyCompaction = () -> { };

    /** CC context.addNotification?.(notif) — 错误通知（compact.ts:1108-1123） */
    private Consumer<CompactConversation.CompactionNotification> notification = n -> { };

    /**
     * 额外压缩后附件 · CC original: createPostCompactFileAttachments 之外的附件类型
     * （async-agent/plan/plan_mode/skill/3×delta，compact.ts:545-585）。
     * 由调用方按 {@link PostCompactAttachmentRestorer} 工厂方法填充（默认空）。
     */
    private List<ChatMessageDto> additionalPostCompactAttachments = List.of();

    /**
     * SessionStart hooks 返回的 watchPaths 出口 · CC original: updateWatchPaths(allWatchPaths)
     * （sessionStart.ts:158-160）。compact 流程无 FileChangedWatcher 引用，经本出口交给
     * 接线方（生产可接 FileChangedWatcher.updateWatchPaths；null 默认 no-op）。
     */
    private Consumer<java.util.List<String>> sessionStartWatchPathsConsumer = paths -> { };
    /**
     * plan 文件提供者 · CC original: {@code getPlan}/{@code getPlanFilePath}
     * （plans.ts:119-145，经 createPlanAttachmentIfNeeded / createPlanModeAttachmentIfNeeded
     * compact.ts:1470-1486 / 1542-1560 读侧）。
     *
     * <p>供 compact 重建链读磁盘 plan 文件注入 plan_file_reference / plan_mode 附件。
     * 生产调用方（IMP-07/10/12）未显式注入时，{@link PostCompactAttachmentRestorer#populatePlanAttachment}
     * 按 {@code sessionId} 回落构造 {@link PlanProviderImpl}（默认 plans 目录）。测试可注入假实现
     * 验证重建链路（对齐旧 PlanProvider @FunctionalInterface 测试注入模式）。
     */
    private PlanProvider planProvider;

    // ── [RES-OPD-SP33] fork 缓存共享原料（CC getCacheSharingParams compact.ts:250-287）──
    // partial/full 压缩触发摘要前，经 CacheSharingParamsBuilder.build 构建 CacheSafeParams →
    // CacheSafeParamsHolder.save → summarize → finally clear（对齐 R1 CompactCommand 契约）。
    // 均为 nullable（best-effort）：toolUseContext 或 sysPromptCtxProvider 缺失 → build 返回 null →
    // save(null) → StreamCompactSummary 跳过 fork 路径走流式 fallback（缓存共享为优化项，不阻断压缩）。

    /** 会话工具使用上下文 · CC original: {@code context}（compact.ts:285；null → 跳过 fork 缓存共享） */
    private ToolUseContext toolUseContext;

    /** 会话级 system/user 上下文提供者 · CC original: {@code getUserContext()}/{@code getSystemContext()}（compact.ts:277-281） */
    private SystemPromptContextProvider sysPromptCtxProvider;

    /** default system prompt 惰性组装 · CC original: {@code getSystemPrompt(tools, model, dirs, mcpClients)}（compact.ts:261-263） */
    private Supplier<SystemPrompt> defaultSysPromptAssemble;

    /** 自定义 system prompt · CC original: {@code context.options.customSystemPrompt}（compact.ts:269） */
    private String customSystemPrompt;

    /** 用户追加指令（恒末尾追加）· CC original: {@code context.options.appendSystemPrompt}（compact.ts:274） */
    private String appendSystemPrompt;

    /** firstParty gate · CC original: {@code shouldUseGlobalCacheScope()}（utils/betas.ts:227-233；Java 由调用方求值注入） */
    private boolean useGlobalCacheScope;

    // ── [IMP-CM-17] tengu_compact 结构化遥测原料（CC context.queryTracking / context.agentId 依赖面）──

    /**
     * 遥测发射器 · CC original: logEvent（compact.ts:650-695 tengu_compact 事件）。
     * null → 压缩成功路径 tengu_compact 事件静默跳过（测试/未接线场景，零行为变化，
     * 同 SessionMemoryService.emitTelemetry null 兜底惯例）。生产由调用方注入。
     */
    private Telemetry telemetry;

    /**
     * 查询链路 id · CC original: {@code context.queryTracking?.chainId ?? ''}
     * （compact.ts:664，tengu_compact 事件 queryChainId 属性）。未接线 → ''（CC 空串等价）。
     */
    private String queryChainId = "";

    /**
     * 查询深度 · CC original: {@code context.queryTracking?.depth ?? -1}
     * （compact.ts:665，tengu_compact 事件 queryDepth 属性）。未接线 → -1（CC 缺省等价）。
     */
    private int queryDepth = -1;

    /**
     * prompt 缓存共享开关 · CC original: {@code promptCacheSharingEnabled}
     * （compact.ts:676，tengu_compact 事件属性；Java 由调用方注入 StreamCompactSummary 同源值）。
     */
    private boolean promptCacheSharingEnabled;

    // ── getters ──

    public String getSessionId() { return sessionId; }
    public String getAgentId() { return agentId; }
    public String getModel() { return model; }
    public String getAgentType() { return agentType; }
    public String getQuerySource() { return querySource; }
    public Path getWorkspaceDir() { return workspaceDir; }
    public SessionStorage.SessionMetadata getSessionMetadata() { return sessionMetadata; }
    public Consumer<CompactProgressEvent> getOnCompactProgress() {
        // [compact-progress-push 2026-09-04] 显式 set（buildAutoContext 从 tuc、测试注入）优先；
        //   未显式设置时委托 CompactProgressState 线程注册（manual/auto 压缩期间注册 STOMP 推送，
        //   对齐 CC REPL onCompactProgress spinner）。无注册 → 回落字段默认 no-op（行为不回归）。
        Consumer<CompactProgressEvent> registered = CompactProgressState.current();
        return registered != null ? registered : onCompactProgress;
    }
    public Consumer<SDKStatus> getSdkStatusSetter() { return sdkStatusSetter; }
    public Consumer<SpinnerMode> getStreamModeSetter() { return streamModeSetter; }
    public Consumer<Integer> getResponseLengthSetter() { return responseLengthSetter; }
    public Consumer<java.util.List<String>> getSessionStartWatchPathsConsumer() { return sessionStartWatchPathsConsumer; }

    public CompactConversationContext setSessionStartWatchPathsConsumer(
            Consumer<java.util.List<String>> sessionStartWatchPathsConsumer) {
        this.sessionStartWatchPathsConsumer = sessionStartWatchPathsConsumer != null
            ? sessionStartWatchPathsConsumer : paths -> { };
        return this;
    }

    public AbortController getAbortController() { return abortController != null ? abortController : AbortController.NOOP; }
    public CompactConversation.SummaryProducer getSummaryProducer() { return summaryProducer; }
    public HookRegistry getHookRegistry() { return hookRegistry; }
    public Map<String, CompactConversation.ReadFileState> getReadFileState() { return readFileState; }
    public Runnable getNotifyCompaction() { return notifyCompaction; }
    public Consumer<CompactConversation.CompactionNotification> getNotification() { return notification; }
    public List<ChatMessageDto> getAdditionalPostCompactAttachments() { return additionalPostCompactAttachments; }
    public PlanProvider getPlanProvider() { return planProvider; }

    /**
     * 是否处于 plan 模式 · CC original: createPlanModeAttachmentIfNeeded 读
     * {@code appState.toolPermissionContext.mode === 'plan'}（compact.ts:1545-1547）。
     *
     * <p><b>派生而非存储</b>: 从 {@link #toolUseContext} 的 appState 读 toolPermissionContext.mode。
     * toolUseContext 未接线（AutoCompactor / ToolRegistrationConfig 路径，concern B）→ 返回 false
     * （plan_mode 附件安全跳过，不中断压缩成功路径）。
     */
    public boolean isInPlanMode() {
        ToolUseContext tuc = this.toolUseContext;
        if (tuc == null || tuc.getAppState() == null) {
            return false;
        }
        Map<String, Object> snapshot = tuc.getAppState().apply(null);
        if (snapshot == null) {
            return false;
        }
        Object tpc = snapshot.get(KEY_TOOL_PERMISSION_CONTEXT);
        return tpc instanceof ToolPermissionContext p && p.mode() == PermissionMode.PLAN;
    }

    // ── [RES-OPD-SP33] fork 缓存共享原料 getters ──
    public ToolUseContext getToolUseContext() { return toolUseContext; }
    public SystemPromptContextProvider getSysPromptCtxProvider() { return sysPromptCtxProvider; }
    public Supplier<SystemPrompt> getDefaultSysPromptAssemble() { return defaultSysPromptAssemble; }
    public String getCustomSystemPrompt() { return customSystemPrompt; }
    public String getAppendSystemPrompt() { return appendSystemPrompt; }
    public boolean isUseGlobalCacheScope() { return useGlobalCacheScope; }

    // ── [IMP-CM-17] tengu_compact 遥测原料 getters ──
    public Telemetry getTelemetry() { return telemetry; }
    public String getQueryChainId() { return queryChainId != null ? queryChainId : ""; }
    public int getQueryDepth() { return queryDepth; }
    public boolean isPromptCacheSharingEnabled() { return promptCacheSharingEnabled; }

    // ── fluent setters ──

    public CompactConversationContext setSessionId(String sessionId) { this.sessionId = sessionId; return this; }
    public CompactConversationContext setAgentId(String agentId) { this.agentId = agentId; return this; }
    public CompactConversationContext setModel(String model) { this.model = model; return this; }
    public CompactConversationContext setAgentType(String agentType) { this.agentType = agentType; return this; }
    public CompactConversationContext setQuerySource(String querySource) { this.querySource = querySource; return this; }
    public CompactConversationContext setWorkspaceDir(Path workspaceDir) { this.workspaceDir = workspaceDir; return this; }
    public CompactConversationContext setSessionMetadata(SessionStorage.SessionMetadata sessionMetadata) {
        this.sessionMetadata = sessionMetadata; return this;
    }
    public CompactConversationContext setOnCompactProgress(Consumer<CompactProgressEvent> onCompactProgress) {
        this.onCompactProgress = onCompactProgress != null ? onCompactProgress : event -> { }; return this;
    }
    public CompactConversationContext setSdkStatusSetter(Consumer<SDKStatus> sdkStatusSetter) {
        this.sdkStatusSetter = sdkStatusSetter != null ? sdkStatusSetter : status -> { }; return this;
    }
    public CompactConversationContext setStreamModeSetter(Consumer<SpinnerMode> streamModeSetter) {
        this.streamModeSetter = streamModeSetter != null ? streamModeSetter : mode -> { }; return this;
    }
    public CompactConversationContext setResponseLengthSetter(Consumer<Integer> responseLengthSetter) {
        this.responseLengthSetter = responseLengthSetter != null ? responseLengthSetter : len -> { }; return this;
    }
    public CompactConversationContext setAbortController(AbortController abortController) {
        this.abortController = abortController != null ? abortController : AbortController.NOOP; return this;
    }
    public CompactConversationContext setSummaryProducer(CompactConversation.SummaryProducer summaryProducer) {
        this.summaryProducer = summaryProducer; return this;
    }
    public CompactConversationContext setHookRegistry(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry; return this;
    }
    /**
     * 设置 readFileState · 对齐 CC context.readFileState（同一对象引用）：
     * 压缩流程对同一 map 做 snapshot（cacheToObject）→ clear()（compact.ts:518-521），
     * clear 对调用方传入的 map 生效（CC 语义）。调用方须传入可变 map（如 LinkedHashMap）。
     */
    public CompactConversationContext setReadFileState(Map<String, CompactConversation.ReadFileState> readFileState) {
        this.readFileState = readFileState; return this;
    }
    public CompactConversationContext setNotifyCompaction(Runnable notifyCompaction) {
        this.notifyCompaction = notifyCompaction != null ? notifyCompaction : () -> { }; return this;
    }
    public CompactConversationContext setNotification(Consumer<CompactConversation.CompactionNotification> notification) {
        this.notification = notification != null ? notification : n -> { }; return this;
    }
    public CompactConversationContext setAdditionalPostCompactAttachments(List<ChatMessageDto> additional) {
        this.additionalPostCompactAttachments = additional != null ? new ArrayList<>(additional) : List.of(); return this;
    }
    public CompactConversationContext setPlanProvider(PlanProvider planProvider) {
        this.planProvider = planProvider; return this;
    }
    public CompactConversationContext setToolUseContext(ToolUseContext toolUseContext) {
        this.toolUseContext = toolUseContext; return this;
    }
    public CompactConversationContext setSysPromptCtxProvider(SystemPromptContextProvider sysPromptCtxProvider) {
        this.sysPromptCtxProvider = sysPromptCtxProvider; return this;
    }
    public CompactConversationContext setDefaultSysPromptAssemble(Supplier<SystemPrompt> defaultSysPromptAssemble) {
        this.defaultSysPromptAssemble = defaultSysPromptAssemble; return this;
    }
    public CompactConversationContext setCustomSystemPrompt(String customSystemPrompt) {
        this.customSystemPrompt = customSystemPrompt; return this;
    }
    public CompactConversationContext setAppendSystemPrompt(String appendSystemPrompt) {
        this.appendSystemPrompt = appendSystemPrompt; return this;
    }
    public CompactConversationContext setUseGlobalCacheScope(boolean useGlobalCacheScope) {
        this.useGlobalCacheScope = useGlobalCacheScope; return this;
    }

    // ── [IMP-CM-17] tengu_compact 遥测原料 setters（fluent）──
    public CompactConversationContext setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry; return this;
    }
    public CompactConversationContext setQueryChainId(String queryChainId) {
        this.queryChainId = queryChainId != null ? queryChainId : ""; return this;
    }
    public CompactConversationContext setQueryDepth(int queryDepth) {
        this.queryDepth = queryDepth; return this;
    }
    public CompactConversationContext setPromptCacheSharingEnabled(boolean promptCacheSharingEnabled) {
        this.promptCacheSharingEnabled = promptCacheSharingEnabled; return this;
    }

    /** 清空 readFileState（CC context.readFileState.clear()，compact.ts:521） */
    public void clearReadFileState() {
        if (readFileState != null) {
            readFileState.clear();
        }
    }

}
