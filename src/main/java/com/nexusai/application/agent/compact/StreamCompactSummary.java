package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.api.ApiErrors;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.ForkedAgentParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.prompt.SystemPromptSplitter;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 流式压缩摘要生产 · 对齐 CC compact.ts:1136-1396 {@code streamCompactSummary}。
 *
 * <p><b>WHY 存在（全域根因）</b>: L4 摘要此前是 no-op 假可用
 * （ToolRegistrationConfig no-op CompactCallback 返回 null → {@link CompactSummary#isValid}
 * 失败 → recordFailure）。本类重建为 CC {@code streamCompactSummary} 全量语义：
 * <ol>
 *   <li><b>fork 缓存共享</b>（compact.ts:1155-1248）——复用主线程 prompt cache 前缀：
 *       {@code tengu_compact_cache_prefix}（默认 true）→ [IMP2-23 ⊕-7] 委托
 *       {@link RunForkedAgent}（forkedAgent.ts:489-626 单一实现；seam 经
 *       {@link #setForkedQuery} 注入，生产 = ProductionForkedQuery）。
 *       参数契约（INV-7）：<b>不设 maxOutputTokens</b>（破坏 cache key，compact.ts:1181-1187）、
 *       skipCacheWrite=true（fork 不写缓存）、maxTurns=1、querySource=compact、
 *       abortController 透传、canUseTool=deny（compact.ts:1191）。
 *       无 assistant 文本 / API 错误 → 落到流式 fallback。</li>
 *   <li><b>流式 fallback</b>（compact.ts:1250-1389）——{@code maxOutputTokensOverride =
 *       min(COMPACT_MAX_OUTPUT_TOKENS, getMaxOutputTokensForModel)}；strip 图片/重注入附件；
 *       keepalive 30s（compact.ts:1167-1176）；无流式响应 → 抛
 *       {@link #ERROR_MESSAGE_INCOMPLETE_RESPONSE}（compact.ts:1388/1392）；流式重试
 *       （{@code MAX_COMPACT_STREAMING_RETRIES=2}，compact.ts:1255）。</li>
 * </ol>
 *
 * <p><b>类归属（IMP-01 实施 Agent 决定，已登记进度文件）</b>: 本类归 compact 域，
 * 实现 {@link AutoCompactor.CompactCallback}（L4 auto 注入点），并暴露丰富的
 * {@link #streamCompactSummary(List, String, int, String, LlmProvider, ProviderConfig)}
 * 方法供测试与下游（IMP-04 compactConversation / IMP-07 autoCompactIfNeeded）直接调用。
 *
 * <p><b>CC 对齐注记</b>：
 * <ul>
 *   <li>[IMP2-23 ⊕-7] CC {@code runForkedAgent}（forkedAgent.ts:489）在 Java 端由
 *       {@link #tryForkCacheSharing} 委托执行（旧内联 buildForkRequest + streamOnce 双轨
 *       已删除，收敛为 RunForkedAgent 单一实现）；userContext 前置（query.ts:660）经
 *       {@link #withUserContextPrepended} 保留在 forkContextMessages 副本队首。</li>
 *   <li>CC {@code isSessionActivityTrackingActive} 对应 {@code sessionActivityTrackingActive}
 *       标志 + {@code sessionActivitySignal} 回调。</li>
 * </ul>
 */
public class StreamCompactSummary implements AutoCompactor.CompactCallback {

    private static final Logger log = LoggerFactory.getLogger(StreamCompactSummary.class);

    // ════════════════════════════════════════════════════════════════════
    // CC 常量 · utils/context.ts:12 + compact.ts:131/296-297/1173
    // ════════════════════════════════════════════════════════════════════

    /** CC original: COMPACT_MAX_OUTPUT_TOKENS (Open-ClaudeCode/src/utils/context.ts:12) = 20_000 */
    public static final int COMPACT_MAX_OUTPUT_TOKENS = 20_000;

    /** CC original: MAX_COMPACT_STREAMING_RETRIES (Open-ClaudeCode/src/services/compact/compact.ts:131) = 2 */
    public static final int MAX_COMPACT_STREAMING_RETRIES = 2;

    /** CC original: setInterval 30_000 (compact.ts:1173) —— keepalive 间隔 ms */
    public static final long KEEPALIVE_INTERVAL_MS = 30_000L;

    /** CC original: ERROR_MESSAGE_INCOMPLETE_RESPONSE (compact.ts:296-297) */
    public static final String ERROR_MESSAGE_INCOMPLETE_RESPONSE =
        "Compaction interrupted · This may be due to network issues — please try again.";

    /** CC original: 流式 fallback systemPrompt (compact.ts:1302-1304) */
    static final String SUMMARY_SYSTEM_PROMPT =
        "You are a helpful AI assistant tasked with summarizing conversations.";

    /** CC original: BASE_DELAY_MS (Open-ClaudeCode/src/services/api/withRetry.ts:55) = 500 —— 流式重试基础延迟 */
    static final long BASE_DELAY_MS = 500L;

    /** CC original: getRetryDelay maxDelayMs 默认 (withRetry.ts:532) = 32000 —— 重试延迟上限 */
    static final long MAX_RETRY_DELAY_MS = 32_000L;

    /** CC original: FILE_READ_TOOL_NAME='Read' (tools/FileReadTool/prompt.ts:5) —— fallback 受限工具集恒含 */
    static final String FILE_READ_TOOL_NAME = "Read";

    /** CC original: ToolSearchTool name 'ToolSearch' —— fallback 工具集 tool search 开启时追加 */
    static final String TOOL_SEARCH_TOOL_NAME = "ToolSearch";

    // [IMP2-15 △-15] CC 无 300s 级硬超时（compact.ts 全程：靠 abortController + SDK 状态；
    //   流一直持续则等待，§7-10 默认建议对齐 CC 移除）。旧 STREAM_AWAIT_TIMEOUT_MS=300s
    //   为 Java 独有（△-15，大会话慢网络提前失败风险 13）——已删除，等待改为无界
    //   （future.get() 无超时），取消路径仍经 abortController → CancellationException。

    // ════════════════════════════════════════════════════════════════════
    // 注入依赖（Supplier 形式 · 便于生产接线与单测隔离）
    // ════════════════════════════════════════════════════════════════════

    /** provider 解析（生产：llmProviderFactory 按 config 分发；单测：fake） */
    private final Supplier<LlmProvider> providerSupplier;

    /** 当前模型（对齐 CC context.options.mainLoopModel；null → 由 provider 默认） */
    private final Supplier<String> modelSupplier;

    /** provider 运行时配置（baseUrl + apiKey；对齐 CC queryModelWithStreaming config） */
    private final Supplier<ProviderConfig> configSupplier;

    /** fork cache-safe 前缀（CC CacheSafeParams 5 字段：systemPrompt/userContext/systemContext/
     *  toolUseContext/forkContextMessages · forkedAgent.ts:57-68；null → 跳过 fork 路径）。
     *  [IMP-SP-08 DEL-SP-26] 由嵌套 3 字段 record 改引 CC 对齐的 fork.CacheSafeParams（消除双 record 漂移）。 */
    private final Supplier<CacheSafeParams> cacheSafeParamsSupplier;

    /** abortController（对齐 CC context.abortController.signal；null → NOOP） */
    private final Supplier<AbortController> abortControllerSupplier;

    /** keepalive 信号（对齐 CC sendSessionActivitySignal） */
    private final Supplier<Runnable> sessionActivitySignalSupplier;

    /** keepalive 调度器（null → 不启动 keepalive，sessionActivityTrackingActive=false） */
    private final ScheduledExecutorService keepaliveExecutor;

    /** sessionActivity 跟踪是否激活（对齐 CC isSessionActivityTrackingActive） */
    private final boolean sessionActivityTrackingActive;

    /** prompt cache sharing 开关（对齐 CC tengu_compact_cache_prefix，默认 true） */
    private final boolean promptCacheSharingEnabled;

    /** 流式重试开关（对齐 CC tengu_compact_streaming_retry，默认 false） */
    private final boolean retryEnabled;

    /** SDK status setter（对齐 CC context.setSDKStatus）· 数据流日志/UI */
    private final Consumer<SDKStatus> sdkStatusSetter;

    /** spinner setter（对齐 CC context.setStreamMode） */
    private final Consumer<SpinnerMode> streamModeSetter;

    /**
     * [IMP2-23 ⊕-7] fork 查询 seam · 收敛后 tryForkCacheSharing 委托
     * {@link RunForkedAgent#run}，经本 seam 执行 fork 查询（CC runForkedAgent 调全局
     * query() 的 Java 等价；生产注入 ProductionForkedQuery，测试注入 RecordingQuery）。
     * 未注入 → fork 路径不可用，直落流式 fallback（fail-loud 日志，不静默假成功）。
     */
    private volatile RunForkedAgent.ForkedQuery forkedQuery;

    /** response length setter（对齐 CC context.setResponseLength） */
    private final Consumer<Integer> responseLengthSetter;

    /**
     * [IMP2-15 △-12] feature 门（CC {@code feature('EXPERIMENTAL_SKILL_SEARCH')}，
     * compact.ts:211-223）· 默认全关（对齐 CC 外部构建 flag-off）。
     *
     * <p>与 {@link com.nexusai.application.agent.compact.MicroCompactor} 同模式
     * （static volatile + 测试 setter，IMP2-01 先例）：stripReinjectedAttachments 为静态
     * 纯函数，生产 bean 无 FeatureFlags 注入面，以静态槽位承载门控；默认
     * {@code ALL_DISABLED}（skillPrefetch=false）→ 剥离恒 no-op（CC flag-off 等价）。
     */
    private static volatile com.nexusai.application.agent.loop.FeatureFlags featureFlags =
        com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;

    /** [IMP2-15 △-12] 测试注入 feature 门（对齐 MicroCompactor.setFeatureFlags 先例）。 */
    public static void setFeatureFlags(com.nexusai.application.agent.loop.FeatureFlags flags) {
        featureFlags = flags != null
            ? flags
            : com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;
        if (log.isDebugEnabled()) {
            log.debug("[StreamCompactSummary] setFeatureFlags: skillPrefetch={}"
                    + "（stripReinjectedAttachments 门控，CC EXPERIMENTAL_SKILL_SEARCH）",
                featureFlags.skillPrefetch());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-A3-2 SCS-15/17] 结构化遥测（CC logEvent 等价 · 对齐 MicroCompactor/CompactConversation 惯例）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 遥测发射器 · CC original: logEvent（compact.ts:1214/1235/1242/1364/1379
     * tengu_compact_cache_sharing_success/fallback / streaming_retry / failed）。
     *
     * <p>与 {@link com.nexusai.application.agent.compact.MicroCompactor} 同模式
     * （static + 测试 setter）：StreamCompactSummary 为 Spring 单例 bean，以静态槽位承载
     * 注入（生产由 ToolRegistrationConfig.streamCompactSummary() 注入，null 安全）。
     * 未注入 → emitCompactEvent no-op（遥测降级为日志，不崩 · 同 SessionMemoryService
     * emitTelemetry null 兜底惯例）。
     */
    private static volatile Telemetry telemetry;

    /**
     * [IMP-A3-2] 注入 Telemetry（CC logEvent 等价 · 1P/Statsig 适配层 Telemetry.recordEvent +
     * OTel 转发 logOTelEvent）。null → 复位默认（emitCompactEvent no-op）。
     *
     * @param t Telemetry 实例（null → 复位）
     */
    public static void setTelemetry(Telemetry t) {
        telemetry = t;
        if (log.isDebugEnabled()) {
            log.debug("[StreamCompactSummary] setTelemetry: {}（SCS-15/17 结构化遥测）",
                t != null ? "注入" : "复位");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // [V54 token-compact-fix B1-2] 压缩配置 DB 实时读源静态槽位（流式重试上限）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 压缩配置 DB 实时读源 · [V54 token-compact-fix B1-2] 静态 volatile 槽位
     * （同 {@link com.nexusai.application.agent.compact.MicroCompactor} 先例：本类为 Spring
     * 单例 bean，静态槽位承载注入）。流式重试上限 {@code settings.max_compact_streaming_retries}
     * 实时读；未注入 → 回落常量默认 2。
     *
     * <p>注入：ToolRegistrationConfig.streamCompactSummary bean（settingsResolver 单例）。
     */
    private static volatile com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver;

    /**
     * 注入压缩配置 DB 实时读源（幂等）· 同 {@link AutoCompactor#setSettingsResolver} 回落语义
     * （null → 复位回落常量默认）。
     *
     * @param resolver 压缩配置实时读源（null → 复位回落常量默认）
     */
    public static void setSettingsResolver(com.nexusai.application.agent.compact.CompactSettingsResolver resolver) {
        settingsResolver = resolver;
        if (log.isDebugEnabled()) {
            log.debug("[StreamCompactSummary] setSettingsResolver: 注入={}（流式重试上限 DB 实时覆盖，"
                + "null 回落常量）", resolver != null ? "已注入" : "复位");
        }
    }

    /**
     * 发射紧凑压缩结构化遥测 · 对齐 CC {@code logEvent}（compact.ts:1214/1235/1242/1364/1379）。
     *
     * <p>双发射（recordEvent 1P 计数 + logOTelEvent OTel 转发 · CompactConversation:842-843 惯例）。
     * telemetry 未注入（setTelemetry(null)）→ 静默跳过（测试/未接线零行为变化）。
     *
     * @param event CC 事件名（tengu_compact_cache_sharing_success / _fallback /
     *              tengu_compact_streaming_retry / tengu_compact_failed）
     * @param attrs 事件属性（与 CC logEvent 字段逐项一致）
     */
    private static void emitCompactEvent(String event, java.util.Map<String, Object> attrs) {
        Telemetry t = telemetry;
        if (t == null) {
            return;
        }
        t.recordEvent(event, attrs);
        t.logOTelEvent(event, attrs);
    }

    /**
     * 构造（测试友好）· 全部上下文显式传入。
     *
     * @param providerSupplier             provider 解析
     * @param modelSupplier                model 解析
     * @param configSupplier               provider 配置解析
     * @param cacheSafeParamsSupplier      fork cache-safe 前缀（null → 跳过 fork 路径）
     * @param abortControllerSupplier      abortController（null → NOOP）
     * @param sessionActivitySignalSupplier keepalive 信号（可 null）
     * @param keepaliveExecutor            keepalive 调度器（null → 不启动 keepalive）
     * @param sessionActivityTrackingActive 会话活动跟踪是否激活
     * @param promptCacheSharingEnabled    fork cache 共享开关
     * @param retryEnabled                 流式重试开关
     * @param sdkStatusSetter              SDK status setter
     * @param streamModeSetter             spinner setter
     * @param responseLengthSetter         response length setter
     */
    public StreamCompactSummary(
            Supplier<LlmProvider> providerSupplier,
            Supplier<String> modelSupplier,
            Supplier<ProviderConfig> configSupplier,
            Supplier<CacheSafeParams> cacheSafeParamsSupplier,
            Supplier<AbortController> abortControllerSupplier,
            Supplier<Runnable> sessionActivitySignalSupplier,
            ScheduledExecutorService keepaliveExecutor,
            boolean sessionActivityTrackingActive,
            boolean promptCacheSharingEnabled,
            boolean retryEnabled,
            Consumer<SDKStatus> sdkStatusSetter,
            Consumer<SpinnerMode> streamModeSetter,
            Consumer<Integer> responseLengthSetter) {
        this.providerSupplier = providerSupplier;
        this.modelSupplier = modelSupplier;
        this.configSupplier = configSupplier;
        this.cacheSafeParamsSupplier = cacheSafeParamsSupplier;
        this.abortControllerSupplier = abortControllerSupplier;
        this.sessionActivitySignalSupplier = sessionActivitySignalSupplier;
        this.keepaliveExecutor = keepaliveExecutor;
        this.sessionActivityTrackingActive = sessionActivityTrackingActive;
        this.promptCacheSharingEnabled = promptCacheSharingEnabled;
        this.retryEnabled = retryEnabled;
        this.sdkStatusSetter = sdkStatusSetter == null ? s -> { } : sdkStatusSetter;
        this.streamModeSetter = streamModeSetter == null ? m -> { } : streamModeSetter;
        this.responseLengthSetter = responseLengthSetter == null ? n -> { } : responseLengthSetter;
    }

    /**
     * 构造（默认参数）· 供 ToolRegistrationConfig 生产接线。
     *
     * @param providerSupplier provider 解析
     * @param modelSupplier    model 解析
     * @param configSupplier   provider 配置解析
     */
    public StreamCompactSummary(
            Supplier<LlmProvider> providerSupplier,
            Supplier<String> modelSupplier,
            Supplier<ProviderConfig> configSupplier) {
        this(providerSupplier, modelSupplier, configSupplier,
            null, null, null, null, false, true, false,
            null, null, null);
    }

    /** [RES-C3] provider 配置解析暴露 · 等价源（partial 组装链 gate 求值复用本 bean 同源 supplier，避免第二份解析逻辑）。 */
    public Supplier<ProviderConfig> configSupplier() {
        return configSupplier;
    }

    /**
     * [IMP2-23 ⊕-7] 注入 fork 查询 seam（对齐 ExtractMemoriesAgent/AutoDreamConsolidator
     * 同模式 setter）· 生产接 {@link ProductionForkedQuery}（ToolRegistrationConfig 接线）；
     * 未注入 → fork 缓存共享路径直落流式 fallback。
     *
     * @param query fork 查询实现（CC query() 等价 seam）
     */
    public void setForkedQuery(RunForkedAgent.ForkedQuery query) {
        this.forkedQuery = query;
        if (log.isDebugEnabled()) {
            log.debug("[StreamCompactSummary] fork 查询 seam 注入完成: query={}（⊕-7 收敛 · "
                    + "tryForkCacheSharing 委托 RunForkedAgent）",
                query == null ? "null" : query.getClass().getSimpleName());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // AutoCompactor.CompactCallback 实现（L4 auto 注入点）
    // ════════════════════════════════════════════════════════════════════

    /**
     * CompactCallback 契约 · L4 auto 路径入口（AutoCompactor.tryAutoCompact 调用）。
     *
     * <p>构建 summaryRequest（user 消息，content=prompt）+ 调用全量
     * {@link #streamCompactSummary}，返回含 usage 的摘要结果（text 含 &lt;analysis&gt;/&lt;summary&gt;
     * 标签，供 {@link CompactSummary#isValid} 与 {@link CompactSummary#buildUserMessage}；
     * usage 供 metrics/tengu_compact 事件消费，对齐 CC compact.ts:630-645
     * {@code compactionUsage = getTokenUsage(summaryResponse)}）。
     *
     * <p><b>[IMP-CM-14 F02] WHY usage 透传</b>: 旧实现返回 String 丢弃压缩 API 真实 token 用量，
     * 生产 adapter（ToolRegistrationConfig）恒 {@code new SummaryResult(text, null)} →
     * {@code compactionCallTotalTokens}/{@code compactionInputTokens} 等 metrics 恒 null/0
     * （f4/f5 恒 null 根因之一）。本方法透传 {@link CompactConversation.TokenUsage}，下游
     * {@code CompactConversation.tokenCountFromLastAPIResponse(summaryResult)} 生产不再恒 0。
     *
     * @param prompt   compact prompt（{@link CompactPrompt#buildCompactPrompt()}）
     * @param messages 待摘要消息（AutoCompactor L1-L3 后）
     * @return 含 usage 的摘要结果（text = LLM 原始摘要文本；usage = 压缩 API 真实用量，
     *         可零值但非 null · CC getTokenUsage 对真实 assistant 响应恒返回 usage）
     */
    @Override
    public CompactConversation.SummaryResult summarize(String prompt, List<ChatMessageDto> messages) {
        String model = modelSupplier.get();
        ProviderConfig config = configSupplier.get();
        LlmProvider provider = providerSupplier.get();
        int preCompactTokenCount = estimateTokens(messages);
        try {
            CompactConversation.SummaryResult summary =
                streamCompactSummary(messages, prompt, preCompactTokenCount, model, provider, config);
            if (log.isInfoEnabled()) {
                log.info("[StreamCompactSummary] L4 摘要生产成功: preTokens={} summaryChars={} usage={} model={}",
                    preCompactTokenCount, summary == null ? 0 : summary.text().length(),
                    summary == null ? null : summary.usage(), model);
            }
            return summary;
        } catch (Exception e) {
            log.error("[StreamCompactSummary] L4 摘要生产失败: preTokens={} model={} error={}",
                preCompactTokenCount, model, e.toString());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // streamCompactSummary 全量语义（CC compact.ts:1136-1396）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 全量流式摘要生产 · 对齐 CC {@code streamCompactSummary}。
     *
     * <ol>
     *   <li>keepalive 30s（sessionActivityTrackingActive 时启动，finally 清理）</li>
     *   <li>fork 缓存共享路径（promptCacheSharingEnabled && cacheSafeParams 就绪）——
     *       不设 maxOutputTokens / skipCacheWrite=true / abortController 透传</li>
     *   <li>流式 fallback（maxOutputTokensOverride=min(20000, modelMax) + 剥离 + 重试）</li>
     *   <li>无流式响应 → {@link #ERROR_MESSAGE_INCOMPLETE_RESPONSE}</li>
     * </ol>
     *
     * <p><b>[IMP-CM-14 F02] 返回值含 usage</b>: CC {@code streamCompactSummary} 返回
     * {@code AssistantMessage}（携带 {@code message.usage}），调用点 compact.ts:630-645 经
     * {@code tokenCountFromLastAPIResponse([summaryResponse])} +
     * {@code getTokenUsage(summaryResponse)} 提取用量。Java 端返回
     * {@link CompactConversation.SummaryResult}（text + usage）：fork 路径 usage 源自
     * {@code ForkedAgentResult.totalUsage}（forkedAgent.ts:119），流式 fallback 源自
     * {@code AssistantMessage.usage}（provider 解析的 4 token 字段，DEC-04/R32-06）。
     *
     * @param messages          待摘要消息（已过 compact boundary 切片）
     * @param summaryRequest    摘要请求文本（成为末尾 user 消息，CC summaryRequest）
     * @param preCompactTokenCount 压缩前 token 估算（日志/遥测）
     * @param model             当前模型（CC context.options.mainLoopModel）
     * @param provider          LLM provider
     * @param config            provider 运行时配置
     * @return 含 usage 的摘要结果（text 恒非 null；usage 可零值但非 null · CC 对真实
     *         assistant 响应恒返回 usage）
     * @throws StreamCompactSummaryException 无流式响应时抛 {@link #ERROR_MESSAGE_INCOMPLETE_RESPONSE}
     */
    public CompactConversation.SummaryResult streamCompactSummary(
            List<ChatMessageDto> messages,
            String summaryRequest,
            int preCompactTokenCount,
            String model,
            LlmProvider provider,
            ProviderConfig config) throws StreamCompactSummaryException {
        AbortController abortController =
            abortControllerSupplier != null ? abortControllerSupplier.get() : AbortController.NOOP;
        if (abortController == null) {
            abortController = AbortController.NOOP;
        }
        if (abortController.isCancelled()) {
            log.info("[StreamCompactSummary] abortController 已取消，跳过摘要生产");
            throw new StreamCompactSummaryException(ERROR_MESSAGE_INCOMPLETE_RESPONSE);
        }

        ScheduledFuture<?> keepaliveFuture = startKeepalive();
        try {
            // ── 2. fork 缓存共享（CC compact.ts:1155-1248）──
            if (promptCacheSharingEnabled && cacheSafeParamsSupplier != null) {
                CacheSafeParams cacheSafeParams = cacheSafeParamsSupplier.get();
                if (cacheSafeParams != null && cacheSafeParams.forkContextMessages() != null
                        && !cacheSafeParams.forkContextMessages().isEmpty()) {
                    CompactConversation.SummaryResult forkResult = tryForkCacheSharing(
                        summaryRequest, cacheSafeParams, preCompactTokenCount, model, provider, config, abortController);
                    if (forkResult != null) {
                        return forkResult;
                    }
                }
            }

            // ── 3. 流式 fallback（CC compact.ts:1250-1389）──
            return streamingFallback(
                messages, summaryRequest, preCompactTokenCount, model, provider, config, abortController);
        } finally {
            // ── keepalive finally 清理（CC compact.ts:1394 clearInterval）──
            if (keepaliveFuture != null) {
                keepaliveFuture.cancel(false);
                log.debug("[StreamCompactSummary] keepalive 定时已清理");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // fork 缓存共享（CC compact.ts:1155-1248 → runForkedAgent 委托 · ⊕-7 收敛）
    // ════════════════════════════════════════════════════════════════════

    /**
     * [IMP2-23 ⊕-7] userContext 前置后的 cache-safe params 副本 · 对齐 CC query.ts:660
     * {@code prependUserContext(messagesForQuery, userContext)}。
     *
     * <p><b>[RES-② F1] WHY</b>: 用户上下文（claudeMd?/currentDate）是 Anthropic prompt cache
     * 前缀的一部分，fork 不前置则前缀与主线程不一致、消息前缀缓存永不命中。旧内联实现
     * buildForkRequest 在消息队首前置 {@code <system-reminder>} meta user 消息；收敛后
     * RunForkedAgent 的 initialMessages = [...forkContextMessages, ...promptMessages]
     * （forkedAgent.ts:524），故前置语义迁移为「forkContextMessages 副本队首前置」——
     * 发送消息序列不变（[meta, ...forkCtx, summaryRequest]）。userContext 空 map 时原样
     * 返回（对齐 CC api.ts:457-459 空 context 不污染前缀）。
     *
     * @param cs 原 cache-safe params
     * @return 副本（forkContextMessages 已前置）或原对象（无 userContext / 空 map）
     */
    private static CacheSafeParams withUserContextPrepended(CacheSafeParams cs) {
        if (cs == null || cs.userContext() == null || cs.userContext().isEmpty()) {
            return cs;
        }
        List<ChatMessageDto> forkMessages = new ArrayList<>();
        if (cs.forkContextMessages() != null) {
            forkMessages.addAll(cs.forkContextMessages());
        }
        // [RES-② F1] 复用主 loop 同款实现（LlmAgentLoop:2802 同一实现，渲染字节一致 → fork
        //   前缀与主线程 cache key 对齐）；返回新 CacheSafeParams（record 不可变 → 副本）。
        List<ChatMessageDto> prepended = AgentLoopContext.prependUserContext(forkMessages, cs.userContext());
        if (log.isDebugEnabled()) {
            log.debug("[StreamCompactSummary] fork 消息 userContext 前置: keys={} 消息 {} 条 → {} 条"
                    + "（forkCtx={} + summary=1）",
                cs.userContext().size(), forkMessages.size(), prepended.size(),
                cs.forkContextMessages() == null ? 0 : cs.forkContextMessages().size());
        }
        return new CacheSafeParams(cs.systemPrompt(), cs.userContext(), cs.systemContext(),
            cs.toolUseContext(), prepended, cs.useGlobalCacheScope());
    }

    /**
     * fork 缓存共享路径 · [IMP2-23 ⊕-7] 委托 {@link RunForkedAgent#run} 单一实现
     * （forkedAgent.ts:489-626），对齐 CC compact.ts:1188-1200：
     * {@code runForkedAgent({promptMessages:[summaryRequest], cacheSafeParams,
     * canUseTool: createCompactCanUseTool(), querySource:'compact', forkLabel:'compact',
     * maxTurns:1, skipCacheWrite:true, overrides:{abortController}})}。
     *
     * <p><b>不变量（INV-7）</b>：fork 路径<b>不设 maxOutputTokens</b>（CC 注释 1181-1187：
     * 设 maxOutputTokens 会经 Math.min(budget, maxOutputTokens-1) 改变 budget_tokens，
     * 破坏主线程 cache key）；skipCacheWrite=true；abortController 透传；canUseTool=deny
     * （createCompactCanUseTool，compact.ts:1125-1133）。
     *
     * <p><b>结果提取（CC compact.ts:1201-1230）</b>：getLastAssistantMessage →
     * getAssistantMessageText → 非 API 错误文本才作为摘要返回；无 assistant / 无文本 /
     * isApiErrorMessage / API 错误前缀 → null 落流式 fallback（no_text_response）。
     *
     * <p><b>[IMP-CM-14 F02 + IMP-MV2-10] usage 透传</b>: 结果 usage 源自 {@code result.totalUsage()}
     * （CC forkedAgent.ts:119 totalUsage 累计；Java ProductionForkedQuery 逐轮从
     * AssistantMessage.usage（AgentUsage）全量累计 input/output/cacheRead/cacheCreate
     * 四字段，[IMP-MV2-10] 修复 input/cache 恒 0 —— ProductionForkedQuery.java:259-282）。
     * 语义对齐 CC compact.ts:645 {@code compactionUsage = getTokenUsage(summaryResponse)}：
     * 有效摘要必带 usage（可零值但非 null）。
     *
     * @return 含 usage 的有效摘要结果（无文本 / API 错误 → null，落流式 fallback）
     */
    private CompactConversation.SummaryResult tryForkCacheSharing(
            String summaryRequest,
            CacheSafeParams cacheSafeParams,
            int preCompactTokenCount,
            String model,
            LlmProvider provider,
            ProviderConfig config,
            AbortController abortController) {
        if (forkedQuery == null) {
            log.warn("[StreamCompactSummary] fork 查询 seam 未注入（setForkedQuery），fork 缓存共享不可用，落流式 fallback");
            return null;
        }
        try {
            ForkedAgentParams params = new ForkedAgentParams(
                List.of(CompactConversation.buildSummaryRequestMessage(summaryRequest)),  // promptMessages = [summaryRequest]（compact.ts:1189）
                withUserContextPrepended(cacheSafeParams),            // F1：userContext 前置（query.ts:660）
                RunForkedAgent.createCompactCanUseTool(),             // compact.ts:1191 deny
                QuerySource.COMPACT,                                  // compact.ts:1192
                "compact",                                            // compact.ts:1193 forkLabel
                null,                                                 // maxOutputTokens（INV-7 · compact.ts:1181-1187）
                1,                                                    // maxTurns（compact.ts:1194）
                false,                                                // skipTranscript（CC 未传 → undefined/false）
                true,                                                 // skipCacheWrite（compact.ts:1195）
                abortController,                                      // overrides.abortController（compact.ts:1196-1199）
                null,                                                 // onMessage
                null);                                                // readFileState
            if (log.isDebugEnabled()) {
                log.debug("[StreamCompactSummary] fork 缓存共享触发: forkMsgs={} userContextKeys={}"
                        + " maxOutputTokens=null skipCacheWrite=true maxTurns=1 querySource=compact",
                    cacheSafeParams.forkContextMessages().size(), cacheSafeParams.userContext().keySet());
            }
            ForkedAgentResult result = RunForkedAgent.run(params, forkedQuery);

            // compact.ts:1201-1210: getLastAssistantMessage → getAssistantMessageText →
            //   !isApiErrorMessage 守卫（abort 合成的 API 错误消息不得作为摘要成功返回）
            ChatMessageDto assistantMsg = lastAssistantMessage(result.messages());
            String text = assistantText(assistantMsg);
            if (assistantMsg != null && text != null
                    && !assistantMsg.isApiErrorMessage()
                    && !ApiErrors.startsWithApiErrorPrefix(text)) {
                // [IMP-CM-14 F02] usage 透传：fork 有效摘要必带 totalUsage 映射的 TokenUsage
                //   （非 null，可零值 · 对齐 CC getTokenUsage 对真实 assistant 响应恒返回 usage）
                CompactConversation.SummaryResult success =
                    new CompactConversation.SummaryResult(text, toTokenUsage(result.totalUsage()));
                // [IMP-A3-2 SCS-17] CC compact.ts:1213-1228 tengu_compact_cache_sharing_success：
                //   PTL 错误文本（PROMPT_TOO_LONG_ERROR_MESSAGE 前缀）跳过 success 事件
                //   （CC 返回给调用方 PTL 重试循环，不视为成功摘要）。
                if (!text.startsWith(ApiErrors.PROMPT_TOO_LONG_ERROR_MESSAGE)) {
                    // [A 命中率口径] 传 ForkedAgentResult（携带 providerType → isAnthropic() 分派
                    //   cacheHitRate 分母），不再传 usage（丢失 provider 判定）。
                    emitCompactEvent("tengu_compact_cache_sharing_success",
                        cacheSharingSuccessAttrs(preCompactTokenCount, result));
                }
                if (log.isInfoEnabled()) {
                    log.info("[StreamCompactSummary] fork 缓存共享成功: forkMsgs={} summaryChars={} usage={} · CC compact.ts:1201-1230",
                        params.promptMessages().size()
                            + (cacheSafeParams.forkContextMessages() == null
                                ? 0 : cacheSafeParams.forkContextMessages().size()),
                        text.length(), success.usage());
                }
                return success;
            }
            // [IMP-A3-2 SCS-17] CC compact.ts:1235-1239 tengu_compact_cache_sharing_fallback：
            //   无文本 / API 错误 → reason='no_text_response'
            emitCompactEvent("tengu_compact_cache_sharing_fallback", Map.of(
                "reason", "no_text_response", "preCompactTokenCount", preCompactTokenCount));
            log.warn("[StreamCompactSummary] fork 缓存共享无有效文本（assistant={} apiError={}），落流式 fallback · CC compact.ts:1231-1234",
                assistantMsg != null, assistantMsg != null && assistantMsg.isApiErrorMessage());
            return null;
        } catch (Exception e) {
            // [IMP-A3-2 SCS-17] CC compact.ts:1242-1246 tengu_compact_cache_sharing_fallback：
            //   fork 异常 → reason='error'
            emitCompactEvent("tengu_compact_cache_sharing_fallback", Map.of(
                "reason", "error", "preCompactTokenCount", preCompactTokenCount));
            log.warn("[StreamCompactSummary] fork 缓存共享失败，落流式 fallback: {} · CC compact.ts:1240-1247", e.toString());
            return null;
        }
    }

    /**
     * [IMP-A3-2 SCS-17] tengu_compact_cache_sharing_success 事件属性 · 对齐 CC
     * compact.ts:1214-1227 logEvent 字段逐项：preCompactTokenCount / outputTokens /
     * cacheReadInputTokens / cacheCreationInputTokens / cacheHitRate。
     *
     * <p>cacheHitRate（<b>A 命中率口径协议分派</b> · ContextUsageCalculator.computeCacheHitRate）：
     * anthropic → {@code cache_read/(cache_read + cache_creation + input)}（CC compact.ts:1220-1226）；
     * 非 anthropic（openai_sdk/deepseek，prompt_tokens 已含 cache hit）→ {@code cache_read/input}
     * （旧恒三字段分母对 deepseek 双计 → 命中率恒为真实一半）。read ≤ 0 / 分母 ≤ 0 → 0。
     *
     * @param preCompactTokenCount 压缩前 token 估算
     * @param result fork 结果（ForkedAgentResult 携带 providerType → isAnthropic() 分派；
     *               null → token 字段 0 + cacheHitRate 0，防异常吞成功路径）
     * @return CC 事件属性 map（LinkedHashMap 保序）
     */
    private static java.util.Map<String, Object> cacheSharingSuccessAttrs(
            int preCompactTokenCount, ForkedAgentResult result) {
        ForkedAgentResult.ForkUsage usage = result == null ? null : result.totalUsage();
        long output = usage == null ? 0 : usage.outputTokens();
        long cacheRead = usage == null ? 0 : usage.cacheReadInputTokens();
        long cacheCreation = usage == null ? 0 : usage.cacheCreationInputTokens();
        long input = usage == null ? 0 : usage.inputTokens();
        boolean anthropic = result != null && result.isAnthropic();
        double cacheHitRate = ContextUsageCalculator.computeCacheHitRate(
            input, cacheRead, cacheCreation, anthropic);
        java.util.Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("preCompactTokenCount", preCompactTokenCount);
        attrs.put("outputTokens", output);
        attrs.put("cacheReadInputTokens", cacheRead);
        attrs.put("cacheCreationInputTokens", cacheCreation);
        attrs.put("cacheHitRate", cacheHitRate);
        return attrs;
    }

    /**
     * 最后一条 assistant 消息 · 对齐 CC {@code getLastAssistantMessage}
     * （messages.ts:331-339 findLast type==='assistant'）。
     */
    private static ChatMessageDto lastAssistantMessage(List<ChatMessageDto> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && m.role() == Role.assistant) {
                return m;
            }
        }
        return null;
    }

    /**
     * assistant 消息文本 · 对齐 CC {@code getAssistantMessageText}
     * （messages.ts:2843-2859 文本块拼接 + trim；Java ChatMessageDto 单 content 字符串）。
     *
     * @return trim 后文本；无内容 / 全空白 → null
     */
    private static String assistantText(ChatMessageDto assistantMsg) {
        if (assistantMsg == null || assistantMsg.content() == null) {
            return null;
        }
        String text = assistantMsg.content().trim();
        return text.isEmpty() ? null : text;
    }

    // ════════════════════════════════════════════════════════════════════
    // 流式 fallback（CC compact.ts:1250-1389）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 流式重试上限 DB 实时解析 · CC original: MAX_COMPACT_STREAMING_RETRIES（compact.ts:131，
     * 默认 2）。
     *
     * <p>[V54 token-compact-fix B1-2] DB {@code settings.max_compact_streaming_retries}
     * 有值（&gt; 0）覆盖常量（前端 PUT settings 后下一轮生效），null 回落常量 2。
     * retryEnabled 门控（CC tengu_compact_streaming_retry）仍在前，DB 值仅修正重试次数。
     *
     * @return 流式重试上限（DB 覆盖或常量默认）
     */
    private static int resolveMaxCompactStreamingRetries() {
        com.nexusai.application.agent.compact.CompactSettingsResolver r = settingsResolver;
        if (r != null) {
            Integer db = r.maxCompactStreamingRetries();
            if (db != null && db > 0) {
                return db;
            }
        }
        return MAX_COMPACT_STREAMING_RETRIES;
    }

    /**
     * 流式 fallback · 对齐 CC compact.ts:1250-1389。
     *
     * <p>参数断言（INV-7）：{@code maxOutputTokensOverride = min(20000, getMaxOutputTokensForModel(model))}。
     * 消息构造：{@code stripImagesFromMessages(stripReinjectedAttachments(
     * [getMessagesAfterCompactBoundary(messages), summaryRequest]))}（compact.ts:1292-1301）。
     *
     * <p><b>[IMP-CM-14 F02] usage 透传</b>: 成功响应 usage 源自 {@code AssistantMessage.usage}
     * （provider 解析的 4 token 字段，DEC-04/R32-06；CC message.usage → getTokenUsage 等价）。
     */
    private CompactConversation.SummaryResult streamingFallback(
            List<ChatMessageDto> messages,
            String summaryRequest,
            int preCompactTokenCount,
            String model,
            LlmProvider provider,
            ProviderConfig config,
            AbortController abortController) throws StreamCompactSummaryException {

        int maxOutputTokensOverride = maxOutputTokensOverride(model);
        // [V54 token-compact-fix B1-2] 流式重试上限 DB 实时读（settings.max_compact_streaming_retries
        //   有值覆盖常量 2，null 回落；retryEnabled 门控仍在前，DB 值仅修正重试次数）
        int maxAttempts = retryEnabled ? resolveMaxCompactStreamingRetries() : 1;

        List<ChatMessageDto> stripped = stripReinjectedAttachments(stripImagesFromMessages(
            BoundaryReader.getMessagesAfterCompactBoundary(messages)));
        List<ChatMessageDto> apiMessages = new ArrayList<>(stripped);
        apiMessages.add(CompactConversation.buildSummaryRequestMessage(summaryRequest));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            boolean hasStartedStreaming = false;
            responseLengthSetter.accept(0);

            if (abortController.isCancelled()) {
                throw new StreamCompactSummaryException(ERROR_MESSAGE_INCOMPLETE_RESPONSE);
            }

            log.info("[StreamCompactSummary] 流式 fallback attempt={}/{} preTokens={} maxOutputTokens={} apiMsgs={}",
                attempt, maxAttempts, preCompactTokenCount, maxOutputTokensOverride, apiMessages.size());

            // 流式调用（provider.stream 阻塞桥接；fallback 设 maxOutputTokensOverride）
            AssistantMessage response;
            try {
                // [IMP-A3-2 SCS-15] streamingStartedSink 回传首个文本块门（CC content_block_start
                //   type=text → hasStartedStreaming，compact.ts:1333-1341），供 retry/failed 遥测字段
                boolean[] streamingStartedSink = new boolean[1];
                response = streamOnce(provider, config, model, List.of(SUMMARY_SYSTEM_PROMPT),
                    false, /* 3P 默认 · fallback 无 boundary（streamingFallback 不参与缓存共享） */
                    apiMessages, fallbackToolsArray() /* 受限工具集 · 对齐 CC compact.ts:1265-1290
                        [FileReadTool]（canUseTool=deny 白名单只读；旧实现传 null 空工具集） */,
                    maxOutputTokensOverride, abortController, streamingStartedSink);
                hasStartedStreaming = streamingStartedSink[0];
            } catch (StreamCompactSummaryException e) {
                throw e;
            } catch (Exception e) {
                log.warn("[StreamCompactSummary] 流式 fallback attempt={} 调用异常: {}",
                    attempt, e.toString());
                response = null;
            }

            if (response != null && response.content() != null && !response.content().isBlank()) {
                // [IMP-CM-14 F02] usage 透传：fallback 成功响应带 provider 解析的真实 usage
                //   （AssistantMessage.usage，非 null · CC getTokenUsage(message.usage) 等价）
                CompactConversation.SummaryResult success =
                    new CompactConversation.SummaryResult(response.content(), toTokenUsage(response));
                if (log.isInfoEnabled()) {
                    log.info("[StreamCompactSummary] 流式 fallback 成功: attempt={} summaryChars={} usage={}",
                        attempt, response.content().length(), success.usage());
                }
                return success;
            }

            if (attempt < maxAttempts) {
                // [IMP-A3-2 SCS-15] CC compact.ts:1364-1368 tengu_compact_streaming_retry：
                //   重试前发射（attempt/preCompactTokenCount/hasStartedStreaming）
                emitCompactEvent("tengu_compact_streaming_retry", Map.of(
                    "attempt", attempt,
                    "preCompactTokenCount", preCompactTokenCount,
                    "hasStartedStreaming", hasStartedStreaming));
                log.warn("[StreamCompactSummary] 流式 fallback 无响应，重试: attempt={} hasStartedStreaming={}",
                    attempt, hasStartedStreaming);
                sleepRetryDelay(attempt, abortController);
                continue;
            }

            // [IMP-A3-2 SCS-15] CC compact.ts:1379-1387 tengu_compact_failed：全部尝试失败 →
            //   reason='no_streaming_response' + hasStartedStreaming/retryEnabled/attempts/
            //   promptCacheSharingEnabled
            emitCompactEvent("tengu_compact_failed", Map.of(
                "reason", "no_streaming_response",
                "preCompactTokenCount", preCompactTokenCount,
                "hasStartedStreaming", hasStartedStreaming,
                "retryEnabled", retryEnabled,
                "attempts", attempt,
                "promptCacheSharingEnabled", promptCacheSharingEnabled));
            log.error("[StreamCompactSummary] 流式 fallback 多次尝试失败: attempts={} hasStartedStreaming={} retryEnabled={}",
                attempt, hasStartedStreaming, retryEnabled);
            throw new StreamCompactSummaryException(ERROR_MESSAGE_INCOMPLETE_RESPONSE);
        }
        throw new StreamCompactSummaryException(ERROR_MESSAGE_INCOMPLETE_RESPONSE);
    }

    // ════════════════════════════════════════════════════════════════════
    // provider.stream 阻塞桥接（异步回调 → 同步 AssistantMessage）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 把 provider.stream（void + 回调异步）桥接为阻塞返回 AssistantMessage。
     *
     * <p>对齐 CC queryModelWithStreaming：onAssistantMessage 累积完整 assistant message；
     * onComplete 表示流结束。无 assistant message → 返回 null（无流式响应）。
     *
     * @param maxOutputTokensOverride fork 路径传 null（不设）；fallback 传 min(20000, modelMax)
     */
    AssistantMessage streamOnce(
            LlmProvider provider,
            ProviderConfig config,
            String model,
            List<String> systemPrompt,
            boolean useGlobalCacheScope,
            List<ChatMessageDto> history,
            com.fasterxml.jackson.databind.node.ArrayNode tools,
            Integer maxOutputTokensOverride,
            AbortController abortController) throws StreamCompactSummaryException {
        return streamOnce(provider, config, model, systemPrompt, useGlobalCacheScope, history,
            tools, maxOutputTokensOverride, abortController, null);
    }

    /**
     * streamOnce（含 hasStartedStreaming 回传）· 同 9 参重载，额外经 {@code hasStartedStreamingOut[0]}
     * 回传首个文本块门（CC compact.ts:1333-1341 content_block_start type=text →
     * hasStartedStreaming），供 streamingFallback 的 tengu_compact_streaming_retry/failed
     * 遥测字段消费。
     *
     * @param hasStartedStreamingOut 单元素回传槽（可 null · 兼容旧 9 参调用；streamingFallback 传数组）
     */
    AssistantMessage streamOnce(
            LlmProvider provider,
            ProviderConfig config,
            String model,
            List<String> systemPrompt,
            boolean useGlobalCacheScope,
            List<ChatMessageDto> history,
            com.fasterxml.jackson.databind.node.ArrayNode tools,
            Integer maxOutputTokensOverride,
            AbortController abortController,
            boolean[] hasStartedStreamingOut) throws StreamCompactSummaryException {
        if (provider == null) {
            throw new StreamCompactSummaryException(ERROR_MESSAGE_INCOMPLETE_RESPONSE);
        }
        CompletableFuture<AssistantMessage> future = new CompletableFuture<>();
        final boolean[] onAssistantFired = {false};
        final AtomicInteger chunkCount = new AtomicInteger(0);
        // [IMP2-15 △-11] 累计已流式字符数（CC setResponseLength(length => length + delta)，
        // compact.ts:1345-1347 累加语义；旧实现每次以 chunk.length 覆盖 = last-chunk 语义）
        final AtomicInteger streamedChars = new AtomicInteger(0);
        // [IMP2-15 △-11] 首个文本块门（CC 首个 content_block_start type=text →
        // setStreamMode('responding')，compact.ts:1331-1337；旧实现全程不切 responding）。
        // Java provider 桥接回调粒度只有 text_delta（无 block_start 事件），以首个非空
        // text chunk 为等价触发点。
        final boolean[] hasStartedStreaming = {false};

        Consumer<String> chunkHandler = chunk -> {
            chunkCount.incrementAndGet();
            if (chunk != null) {
                if (!hasStartedStreaming[0]) {
                    hasStartedStreaming[0] = true;
                    streamModeSetter.accept(SpinnerMode.RESPONDING);
                }
                int total = streamedChars.addAndGet(chunk.length());
                responseLengthSetter.accept(total);
            }
        };

        Consumer<AssistantMessage> onAssistant = msg -> {
            onAssistantFired[0] = true;
            future.complete(msg);
        };
        Consumer<Throwable> onError = e -> future.completeExceptionally(e);
        Runnable onComplete = () -> {
            if (!future.isDone()) {
                future.complete(null); // 流结束但无 assistant message → 无响应
            }
        };

        // [IMP-SP-06] 发送边界同步：systemPrompt 数组 → blocks 重载（发送边界契约）。
        //   [RES-R4] fork 用 CacheSafeParams 携带的发送前数组（含 boundary 元素）+ 与主线程同一
        //   useGlobalCacheScope gate 走 SystemPromptSplitter：firstParty/boundary → boundary 剥离、
        //   静态→global / 动态→null（REQ-R4-1）；3P 默认 → defaultMode 单 block ORG（字节与旧
        //   flat String 一致，REQ-R4-4）。fallback 传单元素数组 + gate=false（默认模式）。
        //   [IMP-SP2-07 G1] 第三参 = needsToolBasedCacheMarker 等价物（gate && 发送工具集存在 MCP
        //   工具 · CC claude.ts:1212-1214 + claude.ts:1377；Java 无 tool-search → willDefer 恒
        //   false，等价论证见 hasMcpTool）。tools 即发送 ArrayNode（[i].function.name mcp__ 前缀，
        //   McpServerScope.MCP_PREFIX 不变式）；fallback 传 tools=null → false → 模式 3 不变。
        List<SystemPromptBlock> blocks = SystemPromptSplitter.splitSysPromptPrefix(
            systemPrompt == null ? List.of() : systemPrompt, useGlobalCacheScope,
            useGlobalCacheScope && hasMcpTool(tools));

        try {
            if (provider instanceof com.nexusai.infra.llm.AnthropicSdkProvider anthropic
                    && maxOutputTokensOverride != null) {
                // IMP-01 L4: AnthropicSdkProvider 支持 maxOutputTokensOverride 重载
                // (对齐 CC queryModelWithStreaming maxOutputTokensOverride · [DEC-RV-07] SDK 实现)
                anthropic.stream(
                    config, model, blocks, history, tools, maxOutputTokensOverride, null, null, null,
                    chunkHandler,
                    onAssistant,
                    toolCall -> { /* canUseTool=deny · 摘要生产不允许工具调用 */ },
                    reasoning -> { /* 摘要生产关闭 thinking */ },
                    () -> { /* onStreamingFallback · Java provider 内部 fallback 通知 */ },
                    abortController,
                    onError,
                    onComplete);
            } else {
                provider.stream(
                    config, model, blocks, history, tools, null, null, null, null,
                    chunkHandler,
                    onAssistant,
                    toolCall -> { /* canUseTool=deny · 摘要生产不允许工具调用 */ },
                    reasoning -> { /* 摘要生产关闭 thinking */ },
                    () -> { /* onStreamingFallback · Java provider 内部 fallback 通知 */ },
                    abortController,
                    onError,
                    onComplete);
            }
            // [IMP2-15 △-15] CC 无 300s 级硬超时（compact.ts 全程：靠 abortController + SDK
            //   状态，流一直持续则等待）→ future.get() 无超时等待；取消路径仍经
            //   provider abort → CancellationException → INCOMPLETE_RESPONSE。
            AssistantMessage result = future.get();
            // [IMP-A3-2 SCS-15] 回传首个文本块门（chunkHandler 已同步置位）· CC compact.ts:1333-1341
            if (hasStartedStreamingOut != null) {
                hasStartedStreamingOut[0] = hasStartedStreaming[0];
            }
            if (log.isDebugEnabled()) {
                log.debug("[StreamCompactSummary] streamOnce 完成: chunks={} assistantFired={} maxOutputTokens={}",
                    chunkCount.get(), onAssistantFired[0], maxOutputTokensOverride);
            }
            return result;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof java.util.concurrent.CancellationException) {
                log.info("[StreamCompactSummary] 流式调用被取消 (abort)");
                throw new StreamCompactSummaryException(ERROR_MESSAGE_INCOMPLETE_RESPONSE);
            }
            log.warn("[StreamCompactSummary] 流式调用异常: {}", cause.toString());
            throw new StreamCompactSummaryException(ERROR_MESSAGE_INCOMPLETE_RESPONSE);
        } catch (Exception e) {
            log.warn("[StreamCompactSummary] 流式调用异常: {}", e.toString());
            throw new StreamCompactSummaryException(ERROR_MESSAGE_INCOMPLETE_RESPONSE);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // keepalive（CC compact.ts:1167-1176）
    // ════════════════════════════════════════════════════════════════════

    private ScheduledFuture<?> startKeepalive() {
        if (!sessionActivityTrackingActive || keepaliveExecutor == null) {
            return null;
        }
        ScheduledFuture<?> future = keepaliveExecutor.scheduleAtFixedRate(() -> {
            try {
                if (sessionActivitySignalSupplier != null) {
                    Runnable signal = sessionActivitySignalSupplier.get();
                    if (signal != null) {
                        signal.run();
                    }
                }
                sdkStatusSetter.accept(SDKStatus.COMPACTING);
                log.debug("[StreamCompactSummary] keepalive 信号已发送 (30s)");
            } catch (Exception e) {
                log.warn("[StreamCompactSummary] keepalive 信号发送异常: {}", e.toString());
            }
        }, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("[StreamCompactSummary] keepalive 定时已启动: interval={}ms", KEEPALIVE_INTERVAL_MS);
        return future;
    }

    // ════════════════════════════════════════════════════════════════════
    // 剥离（CC compact.ts:145-223）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 图片/文档剥离 · 对齐 CC {@code stripImagesFromMessages}（compact.ts:145-200）。
     *
     * <p>仅 Role.user 消息含图片（直接附加或 tool_result 内容内）；assistant 消息为
     * text/tool_use/thinking 不含图片。image/document 块替换为文本标记
     * {@code [image]} / {@code [document]}。
     */
    public static List<ChatMessageDto> stripImagesFromMessages(List<ChatMessageDto> messages) {
        if (messages == null) {
            return List.of();
        }
        List<ChatMessageDto> out = new ArrayList<>(messages.size());
        for (ChatMessageDto m : messages) {
            if (m == null || m.role() != Role.user || m.contentBlocks() == null || m.contentBlocks().isEmpty()) {
                out.add(m);
                continue;
            }
            boolean hasMedia = false;
            List<Object> newBlocks = new ArrayList<>();
            for (Object blockObj : m.contentBlocks()) {
                if (!(blockObj instanceof com.fasterxml.jackson.databind.JsonNode block) || !block.isObject()) {
                    newBlocks.add(blockObj);
                    continue;
                }
                String type = block.path("type").asText("");
                if ("image".equals(type)) {
                    hasMedia = true;
                    newBlocks.add(textBlock("[image]"));
                } else if ("document".equals(type)) {
                    hasMedia = true;
                    newBlocks.add(textBlock("[document]"));
                } else if ("tool_result".equals(type) && block.path("content").isArray()) {
                    // 嵌套 tool_result 内的 image/document 也剥离
                    com.fasterxml.jackson.databind.node.ArrayNode nested =
                        (com.fasterxml.jackson.databind.node.ArrayNode) block.path("content");
                    boolean nestedMedia = false;
                    com.fasterxml.jackson.databind.node.ArrayNode newNested =
                        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
                    for (com.fasterxml.jackson.databind.JsonNode item : nested) {
                        String it = item.path("type").asText("");
                        if ("image".equals(it)) {
                            nestedMedia = true;
                            newNested.add(textBlock("[image]"));
                        } else if ("document".equals(it)) {
                            nestedMedia = true;
                            newNested.add(textBlock("[document]"));
                        } else {
                            newNested.add(item);
                        }
                    }
                    if (nestedMedia) {
                        hasMedia = true;
                        com.fasterxml.jackson.databind.node.ObjectNode rewritten =
                            (com.fasterxml.jackson.databind.node.ObjectNode) block.deepCopy();
                        rewritten.set("content", newNested);
                        newBlocks.add(rewritten);
                    } else {
                        newBlocks.add(blockObj);
                    }
                } else {
                    newBlocks.add(blockObj);
                }
            }
            if (!hasMedia) {
                out.add(m);
                continue;
            }
            out.add(new ChatMessageDto(
                m.id(), m.sessionId(), m.role(), m.author(), m.content(), m.reasoning(),
                m.toolCalls(), m.finishReason(), m.inputTokens(), m.outputTokens(), m.time(),
                m.createdAt(), m.toolCallId(), m.assistantMessageId(), m.acceptFeedback(),
                newBlocks, m.imagePasteIds(), m.structuredOutput(), m.isMeta(), m.isError()));
        }
        return out;
    }


    // ════════════════════════════════════════════════════════════════════
    // 模型 maxOutputTokens + 工具
    // ════════════════════════════════════════════════════════════════════
    //
    /**
     * 重注入附件剥离 · 对齐 CC {@code stripReinjectedAttachments}（compact.ts:211-223）。
     *
     * <p>CC 语义：
     * <ol>
     *   <li><b>feature 门</b>：{@code feature('EXPERIMENTAL_SKILL_SEARCH')} 开启时才过滤
     *       （skill_discovery/skill_listing 附件仅存在于内部构建，flag 关时 no-op，
     *       compact.ts:214-222 注释）；</li>
     *   <li><b>精确匹配</b>：{@code m.type === 'attachment' && (m.attachment.type ===
     *       'skill_discovery' || 'skill_listing')}——attachment 类型精确相等，非内容子串。</li>
     * </ol>
     *
     * <p>Java 映射（[IMP2-15 △-12]）：attachment 消息在消息链中 author='attachment'、
     * 类型落 subtype（PostCompactAttachmentRestorer:877 同判别）；feature 门 =
     * {@link #setFeatureFlags} 注入的 {@code FeatureFlags.skillPrefetch()}（EXPERIMENTAL_
     * SKILL_SEARCH）。旧实现<b>无门控恒执行 + author/content 子串匹配</b>（content 含
     * 'skill_discovery' 字样的任意附件被误剥）——已对齐：门关恒 no-op，门开仅剥
     * subtype 精确等于 skill_discovery/skill_listing 的 attachment 消息。
     */
    public static List<ChatMessageDto> stripReinjectedAttachments(List<ChatMessageDto> messages) {
        if (messages == null) {
            return List.of();
        }
        // CC compact.ts:213 feature('EXPERIMENTAL_SKILL_SEARCH') 门 —— 关时直接返回原消息
        if (!featureFlags.skillPrefetch()) {
            return messages;
        }
        List<ChatMessageDto> out = new ArrayList<>(messages.size());
        for (ChatMessageDto m : messages) {
            if (m == null) {
                continue;
            }
            // CC compact.ts:217-222：m.type==='attachment' && attachment.type 精确匹配
            boolean skillAttachment = "attachment".equals(m.author())
                && ("skill_discovery".equals(m.subtype()) || "skill_listing".equals(m.subtype()));
            if (skillAttachment) {
                if (log.isDebugEnabled()) {
                    log.debug("[StreamCompactSummary] stripReinjectedAttachments 剥离重注入附件:"
                            + " subtype={}（CC compact.ts:217-222 attachment.type 精确匹配）",
                        m.subtype());
                }
                continue; // 剥离重注入附件
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 流式 fallback 的 maxOutputTokensOverride · 对齐 CC compact.ts:1317-1320：
     * {@code Math.min(COMPACT_MAX_OUTPUT_TOKENS, getMaxOutputTokensForModel(model))}。
     *
     * @param model 当前模型
     * @return min(20000, getMaxOutputTokensForModel(model))
     */
    public static int maxOutputTokensOverride(String model) {
        return Math.min(COMPACT_MAX_OUTPUT_TOKENS, getMaxOutputTokensForModel(model));
    }

    /**
     * 模型最大输出 token · 对齐 CC {@code getMaxOutputTokensForModel}
     * （services/api/claude.ts:3399-3419）——完整解析：模型族 default → {@code tengu_otk_slot_v1}
     * cap(8k) → {@code CLAUDE_CODE_MAX_OUTPUT_TOKENS} 有界 override（IMP2-25 M-1 收敛）。
     *
     * <p><b>[E4-B] 单一来源委托</b> {@link com.nexusai.infra.llm.AnthropicSdkProvider#resolveMaxOutputTokensForModel(String)}
     * （G-18 DB 优先单源，与请求体 buildMessageParams / CompactThresholdSystem reserved 同源）——
     * 修复前委托 {@code getMaxOutputTokensForModel(String)}（纯家族表链，含 settings override），与
     * CompactThresholdSystem 的 DB 优先路径同模型两值（压缩链内双源 · E4 发现 B）。无 DB（mapper 未接线）
     * → 回落家族表，语义不变；旧 Java 简化表为 default-only、缺 cap/env 且遗漏 3-5-sonnet/3-5-haiku
     * 族（回落 32k vs CC 8192），随收敛删除。
     *
     * @param model 模型名
     * @return max_tokens 解析值（DB 命中值 / cap+env 全链）
     */
    public static int getMaxOutputTokensForModel(String model) {
        return com.nexusai.infra.llm.AnthropicSdkProvider.resolveMaxOutputTokensForModel(model);
    }

    // ════════════════════════════════════════════════════════════════════
    // 小工具
    // ════════════════════════════════════════════════════════════════════

    /** 粗略 token 估算（字符/4 · 对齐 CompactSummary.estimateTokens）。 */
    static int estimateTokens(List<ChatMessageDto> messages) {
        if (messages == null) {
            return 0;
        }
        int chars = 0;
        for (ChatMessageDto m : messages) {
            if (m != null && m.content() != null) {
                chars += m.content().length();
            }
        }
        return (int) Math.ceil(chars / 4.0);
    }

    // ════════════════════════════════════════════════════════════════════
    // [IMP-CM-14 F02] usage 提取 · 对齐 CC getTokenUsage（utils/tokens.ts:7-21）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 流式 fallback 响应 usage → {@link CompactConversation.TokenUsage} · 对齐 CC
     * {@code getTokenUsage(summaryResponse)}（utils/tokens.ts:7-21，读取真实 assistant
     * 响应的 {@code message.usage}）。Java {@code AssistantMessage.usage()} 为 provider
     * 解析的 4 token 字段（DEC-04/R32-06，Anthropic 从 message_start/message_delta
     * usage 提取），恒非 null（无上报时零初始化 EMPTY）。
     *
     * @param response 流式 fallback 成功响应（content 非空）
     * @return 4 元组 TokenUsage（非 null；无上报时零值）
     */
    static CompactConversation.TokenUsage toTokenUsage(AssistantMessage response) {
        if (response == null) {
            return null;
        }
        AgentUsage u = response.usage();
        return new CompactConversation.TokenUsage(
            toInt(u == null ? 0L : u.inputTokens()),
            toInt(u == null ? 0L : u.outputTokens()),
            toInt(u == null || u.cacheReadInputTokens() == null ? 0L : u.cacheReadInputTokens()),
            toInt(u == null || u.cacheCreationInputTokens() == null ? 0L : u.cacheCreationInputTokens()));
    }

    /**
     * fork 结果 usage → {@link CompactConversation.TokenUsage} · 对齐 CC
     * {@code result.totalUsage}（forkedAgent.ts:119，loop 内 API 调用累计 usage；
     * Java ProductionForkedQuery 逐轮全量累计 4 字段（input/output/cacheRead/cacheCreate，
     * [IMP-MV2-10]），fork 路径 input/cache 为真实用量非恒 0）。
     *
     * @param u fork 累计 usage（非 null，可零值 empty）
     * @return 4 元组 TokenUsage（非 null）
     */
    static CompactConversation.TokenUsage toTokenUsage(ForkedAgentResult.ForkUsage u) {
        if (u == null) {
            return null;
        }
        return new CompactConversation.TokenUsage(
            toInt(u.inputTokens()), toInt(u.outputTokens()),
            toInt(u.cacheReadInputTokens()), toInt(u.cacheCreationInputTokens()));
    }

    /** long → int 防溢出收敛（token 数实际远小于 int 上限；越界钳到 MAX_VALUE）。 */
    private static int toInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /** 文本块（CC 图片剥离替换为 text 块）。 */
    private static com.fasterxml.jackson.databind.node.ObjectNode textBlock(String text) {
        com.fasterxml.jackson.databind.node.ObjectNode block =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    /**
     * [IMP-SP2-07 G1] 发送工具集 MCP 判定 · CC claude.ts:1212-1214
     * {@code filteredTools.some(t => t.isMcp === true && !willDefer(t))} 的 Java 等价物：
     * Java 无 tool-search（useToolSearch/deferredToolNames/shouldDeferLspTool 0 命中）→
     * {@code willDefer} 恒 false → 等价于发送 ArrayNode 存在 {@code mcp__} 前缀工具
     * （McpServerScope.MCP_PREFIX 不变式：MCP 工具名形如 {@code mcp__<server>__<tool>}，
     * 与 isMcpTool name 判定同源）。fork 路径 tools=forkTools（TUC availableTools 派生）、
     * fallback 传 null → false（模式 3 不变）。
     *
     * @param tools 发送工具 ArrayNode（OpenAI function-calling 格式，可为 null）
     * @return true 时 splitSysPromptPrefix 走模式 1（skipGlobalCache）
     */
    private static boolean hasMcpTool(com.fasterxml.jackson.databind.node.ArrayNode tools) {
        if (tools == null) {
            return false;
        }
        for (com.fasterxml.jackson.databind.JsonNode tool : tools) {
            String name = tool.path("function").path("name").asText(null);
            if (name != null
                    && name.startsWith(com.nexusai.application.agent.mcp.McpServerScope.MCP_PREFIX)) {
                return true;
            }
        }
        return false;
    }


    /**
     * 重试基础延迟 · 对齐 CC {@code getRetryDelay}（withRetry.ts:535-541）：
     * {@code min(BASE_DELAY_MS · 2^(attempt-1), maxDelayMs=32000)}。
     * [IMP2-13 △-13] 旧实现 base=1000ms·2^(n-1) 无 jitter → 对齐 CC base=500ms·2^(n-1)（探查 EV-01-12）。
     *
     * @param attempt 重试序号（1-based）
     * @return 基础延迟 ms
     */
    static long baseRetryDelayMs(int attempt) {
        return Math.min(BASE_DELAY_MS * (1L << Math.max(0, attempt - 1)), MAX_RETRY_DELAY_MS);
    }

    /**
     * 重试 jitter · 对齐 CC withRetry.ts:542-543：{@code jitter = Math.random() · 0.25 · baseDelay}。
     *
     * @param baseDelay 基础延迟 ms
     * @param random    随机源（测试注入固定 seed；生产 ThreadLocalRandom）
     * @return jitter ms ∈ [0, 0.25·baseDelay]
     */
    static long jitterMs(long baseDelay, Random random) {
        return (long) (random.nextDouble() * 0.25 * baseDelay);
    }

    /**
     * 重试总延迟 · 对齐 CC {@code getRetryDelay}（withRetry.ts:530-548）：{@code baseDelay + jitter}。
     *
     * @param attempt 重试序号（1-based）
     * @param random  随机源
     * @return base + jitter ms
     */
    static long retryDelayMs(int attempt, Random random) {
        long baseDelay = baseRetryDelayMs(attempt);
        return baseDelay + jitterMs(baseDelay, random);
    }

    /**
     * 流式重试睡眠 · 对齐 CC {@code sleep(getRetryDelay(attempt), signal, ...)}（compact.ts:1363-1373）：
     * 总时长 = base + jitter；分段 100ms 轮询 abortController（CC signal 中断的 Java 等价）。
     */
    private static void sleepRetryDelay(int attempt, AbortController abortController) {
        long totalDelay = retryDelayMs(attempt, ThreadLocalRandom.current());
        try {
            for (long elapsed = 0; elapsed < totalDelay; elapsed += 100L) {
                if (abortController != null && abortController.isCancelled()) {
                    return;
                }
                Thread.sleep(Math.min(100L, totalDelay - elapsed));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 流式 fallback 受限工具集 · 对齐 CC compact.ts:1265-1290：
     * {@code useToolSearch ? uniqBy([FileReadTool, ToolSearchTool, ...MCP tools], 'name')
     * : [FileReadTool]}。
     *
     * <p>[IMP2-13 △-10] 旧实现传 null（空工具集，探查 EV-01-12）→ 对齐为受限只读工具集
     * （deny 语义保留：canUseTool=deny 白名单只读工具，CC compact.ts:1125-1134）。
     *
     * @param available    工具源（主循环 merged tools 的 Java 同源 —— CacheSafeParams
     *                     toolUseContext.availableTools，permission-filtered 含 MCP）
     * @param useToolSearch tool search 是否启用（CC isToolSearchEnabled 判定；Java 侧
     *                      由 {@link com.nexusai.application.agent.toolsearch.ToolSearchService
     *                      #isToolSearchEnabledOptimistic()} 表达第一道 feature 门）
     * @return OpenAI tools 数组；无工具源或源中无 Read → null（canUseTool=deny 等价退化）
     */
    static com.fasterxml.jackson.databind.node.ArrayNode buildFallbackToolsArray(
            List<Tool> available, boolean useToolSearch) {
        if (available == null || available.isEmpty()) {
            return null;
        }
        // uniqBy name（CC :1281-1289）：[FileReadTool] 恒含；tool search 开 → +ToolSearchTool + MCP
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool t : available) {
            if (FILE_READ_TOOL_NAME.equals(t.name())) {
                byName.put(t.name(), t);
            } else if (useToolSearch
                    && (TOOL_SEARCH_TOOL_NAME.equals(t.name()) || t.isMcp())) {
                byName.put(t.name(), t);
            }
        }
        if (byName.isEmpty()) {
            return null;
        }
        return com.nexusai.application.agent.tool.ToolRegistry.from(new ArrayList<>(byName.values()))
            .toOpenAiToolsArray();
    }

    /**
     * fallback 工具源解析：压缩 fork 槽位（CacheSafeParams.toolUseContext.availableTools，
     * 主循环 merged tools 的 Java 同源，forkedAgent.ts:65 CC options.tools 同源）；
     * 槽位空 → null（无工具源，退化传 null）。
     */
    private com.fasterxml.jackson.databind.node.ArrayNode fallbackToolsArray() {
        CacheSafeParams params = cacheSafeParamsSupplier != null ? cacheSafeParamsSupplier.get() : null;
        List<Tool> available = params != null && params.toolUseContext() != null
            ? params.toolUseContext().availableTools() : null;
        return buildFallbackToolsArray(available,
            com.nexusai.application.agent.toolsearch.ToolSearchService.isToolSearchEnabledOptimistic());
    }

    // ════════════════════════════════════════════════════════════════════
    // 内部类型
    // ════════════════════════════════════════════════════════════════════

    // [IMP2-23 ⊕-7] 内联 ForkRequest 载体已删除 —— fork 缓存共享统一委托 fork/RunForkedAgent
    //   （ForkedAgentParams，forkedAgent.ts:83-113；旧 buildForkRequest/ForkRequest 双轨移除）。

    /**
     * 流式摘要生产异常 · 语义对齐 CC {@code ERROR_MESSAGE_INCOMPLETE_RESPONSE}（不吞错）。
     */
    public static class StreamCompactSummaryException extends RuntimeException {
        public StreamCompactSummaryException(String message) {
            super(message);
        }
    }
}
