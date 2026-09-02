package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.lsp.PromptCacheBreakDetection;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Micro 压缩器（链式入口）· 对齐 CC microCompact.ts（530 行）。
 *
 * <p><b>WHY 重建（IMP-09，D-12..D-16/D-19 删除）</b>: 旧实现是 L3 内容清除主路径
 * （compact 内容清除方法 + 固定系数 200 + 消息数门 + timeBased 清空全部 + 触发判定 static
 * + CompactResult(MICRO) 边界产出，[IMP2-23 D-19] 过渡面已删）——全部与 CC 语义偏移
 * （探查 03 ⊕1..⊕8）。本类重建为 CC {@code microcompactMessages} 链式入口：
 * <ol>
 *   <li>{@link CompactWarningState#clearCompactWarningSuppression()} —— 压缩开始复位警告抑制
 *       （microCompact.ts:259）</li>
 *   <li>time-based 短路 —— gap 超阈值则内容清除后返回（microCompact.ts:267-270，
 *       {@code maybeTimeBasedMicrocompact}:446）</li>
 *   <li>cached 门控 —— 四条件：feature('CACHED_MICROCOMPACT') && isCachedMicrocompactEnabled() &&
 *       isModelSupportedForCacheEditing(model) && isMainThreadSource（microCompact.ts:276-286）</li>
 *   <li>默认 no-op —— legacy 路径已移除，返回 {@code {messages}}（microCompact.ts:288-292，
 *       INV-10：无 time-based/cached 门控下不内容清除）</li>
 * </ol>
 *
 * <h2>CC 对齐（grep -n 自验 2026-08-04，microCompact.ts）</h2>
 * <table>
 *   <tr><th>本符号</th><th>CC original</th><th>行号</th></tr>
 *   <tr><td>microcompactMessages</td><td>microcompactMessages()</td><td>:253</td></tr>
 *   <tr><td>evaluateTimeBasedTrigger</td><td>evaluateTimeBasedTrigger()</td><td>:422</td></tr>
 *   <tr><td>maybeTimeBasedMicrocompact</td><td>maybeTimeBasedMicrocompact()</td><td>:446</td></tr>
 *   <tr><td>keepRecent = max(1, config.keepRecent)</td><td>Math.max(1, config.keepRecent)</td><td>:461</td></tr>
 *   <tr><td>resetMicrocompactState</td><td>resetMicrocompactState()</td><td>:130</td></tr>
 *   <tr><td>consumePendingCacheEdits</td><td>consumePendingCacheEdits()</td><td>:88</td></tr>
 *   <tr><td>maybeCreateMicrocompactBoundaryMessage</td><td>query.ts:866-892 延迟 boundary yield</td><td>:870-890</td></tr>
 *   <tr><td>notifyCacheDeletion</td><td>notifyCacheDeletion(querySource)</td><td>:366/:526</td></tr>
 *   <tr><td>isMainThreadSource</td><td>isMainThreadSource()</td><td>:249-251</td></tr>
 *   <tr><td>cachedMicrocompactPath</td><td>cachedMicrocompactPath()</td><td>:305-399</td></tr>
 * </table>
 *
 * <h2>cached-MC 状态机（cachedMicrocompact.ts 112 行真源全量对齐）</h2>
 * <p>cachedMicrocompactPath 内部算法已按 CC 真源 {@code cachedMicrocompact.ts}（112 行）实现：
 * {@link #ensureCachedMCState()}（懒初始化模块态单例，microCompact.ts:71-81）→
 * {@link #registerToolResult} / {@link #registerToolMessage}（:313-330 分组注册）→
 * {@link #getToolResultsToDelete}（:332 超阈值触发）→ {@link #createCacheEditsBlock}
 * （:336-339 构建 cache_edits 块并入队模块态）→ 返回
 * {@code compactionInfo.pendingCacheEdits{trigger,deletedToolIds,baseline}}（:385-394）。
 * 全部状态机函数/类型以 JavaDoc 标注 CC original 函数名 + 行号。
 *
 * <p><b>Java 模型映射</b>：CC 的 tool_result 内嵌于 user 消息 content 数组；Java 中
 * tool_result 为 {@code Role.tool} 独立消息（{@code toolCallId()} 承载 block.tool_use_id）。
 * cachedMicrocompactPath 以「连续 Role.tool 消息段」为一组对齐 CC 的 per-user-message 分组。
 *
 * <h2>cached 门控四条件（V2-S3，microCompact.ts:276-282）</h2>
 * <p>CC 门控：
 * {@code feature('CACHED_MICROCOMPACT') && mod.isCachedMicrocompactEnabled() &&
 * mod.isModelSupportedForCacheEditing(model) && isMainThreadSource(querySource)}。
 * Java 镜像：{@link #cachedMicrocompactFeatureEnabled}（feature 编译期 flag 模块态）/
 * {@link #isCachedMicrocompactEnabled()}（env 真谓词 {@code CLAUDE_CACHED_MICROCOMPACT==='1'}，
 * cachedMicrocompact.ts:26-28）/{@link #isModelSupportedForCacheEditing(String)}（regex 真谓词
 * {@code /claude-[a-z]+-4[-\d]/}，cachedMicrocompact.ts:33-35）/ {@link #isMainThreadSource(String)}。
 * CC 的 model 来自 {@code toolUseContext?.options.mainLoopModel ?? getMainLoopModel()}
 * （microCompact.ts:278），Java 以当前会话桶 {@link MicroCompactSessionState#mainLoopModel}
 * 承载——生产由 LlmAgentLoop 主循环每轮 turn 起始经 {@link #setMainLoopModel(String)}
 * 注入（OD-01 已闭环；OPD-CM5-A-10 会话级隔离，会话间不互串），
 * 签名保持 {@code microcompactMessages(messages, querySource)} 二参。
 */
public class MicroCompactor {

    private static final Logger log = LoggerFactory.getLogger(MicroCompactor.class);

    /** TokenEstimator（IMP-13 estimateMessageTokens block 口径 · microCompact.ts:164）。 */
    private final TokenEstimator tokenEstimator;

    /**
     * time-based MC 配置源 · 对齐 CC {@code getTimeBasedMCConfig()}（timeBasedMCConfig.ts:36-43）：
     * GrowthBook feature {@code tengu_slate_heron} 读取的 Java 等价注入面。每次评估实时读取
     * （对齐 CC "Hoist the GB read so exposure fires on every eval path"，timeBasedMCConfig.ts:37-38）。
     * 生产由 {@code ToolRegistrationConfig.microCompactor()} 以 {@code nexusai.feature.time-based-mc.*}
     * 属性注入（GB 未接入，属性为等价载体）；默认 {@link TimeBasedMCConfig#DEFAULTS}。
     */
    private final Supplier<TimeBasedMCConfig> timeBasedMCConfigSource;

    /**
     * (1) {@code feature('CACHED_MICROCOMPACT')} · microCompact.ts:276（外部构建消除门）。
     * CC feature() 为编译期 flag：ant 构建恒 true（bun:bundle 外部构建剔除该代码段，ant-only
     * 构建保留代码）；Java 无 ant/外部构建之分，视为恒保留代码。<b>[V52 B1-6/R5]</b>：对齐
     * CC 外部构建（bun:bundle）DCE 语义，默认 <b>false</b>（外部构建恒关）；DB
     * {@code settings.cached_microcompact_enabled}=true 经
     * {@link #setCachedMicrocompactFeatureEnabled(boolean)}（Spring 装配驱动）重新开启。
     * 运行时开关仍由 {@link #isCachedMicrocompactEnabled()}（env
     * {@code CLAUDE_CACHED_MICROCOMPACT==='1'}，cachedMicrocompact.ts:26-28）承担。
     * <b>[token-compact-fix ①]</b>：本字段现为<b>回落槽位</b>——真正判定经
     * {@link #isCachedMicrocompactFeatureEnabled()} 实时读 {@link #settingsResolver}
     * （DB settings.cached_microcompact_enabled，前端 PUT 后下一轮生效）；resolver 为 null
     * （未接线 / 读取失败）时回落本字段（启动初值）。
     */
    private static volatile boolean cachedMicrocompactFeatureEnabled = false;

    /**
     * (2) {@code mod.isCachedMicrocompactEnabled()} 的<b>测试 override</b> · microCompact.ts:280。
     * 真谓词为 {@link #isCachedMicrocompactEnabled()}（env {@code CLAUDE_CACHED_MICROCOMPACT==='1'}，
     * cachedMicrocompact.ts:26-28）；null = 用真谓词，非 null = 测试强制值（保留测试缝兼容）。
     */
    private static volatile Boolean cachedMicrocompactModuleEnabledOverride = null;

    /**
     * (3) {@code mod.isModelSupportedForCacheEditing(model)} 的<b>测试 override</b> · microCompact.ts:281。
     * 真谓词为 {@link #isModelSupportedForCacheEditing(String)}（regex {@code /claude-[a-z]+-4[-\d]/}，
     * cachedMicrocompact.ts:33-35）；null = 用真谓词，非 null = 测试强制值（保留测试缝兼容）。
     */
    private static volatile Boolean cachedMicrocompactModelSupportedOverride = null;

    /** CC 支持 cache_edits 模型正则 /claude-[a-z]+-4[-\d]/ · cachedMicrocompact.ts:33-35（.test = 非锚定 find）。 */
    private static final Pattern CACHE_EDITING_MODEL_PATTERN = Pattern.compile("claude-[a-z]+-4[-\\d]");

    /**
     * 会话级 cached-MC 状态表 · OPD-CM5-A-10 cachedMCState 会话级隔离（消除多会话并发污染）。
     *
     * <p><b>WHY 从静态单例改会话级</b>: CC 为单进程每查询（module-level 单例，microCompact.ts:57），
     * 进程即会话，天然隔离；Java 后端多会话共享 JVM，静态单例使会话 A 注册的工具 / 待下发块 /
     * 主循环模型泄漏到会话 B（v5 探查风险 §10）。改为按 {@code RequestContext.sessionId()}（MDC）
     * 键控的会话级状态表：每会话独立 {@link MicroCompactSessionState}，跨 turn 存活，
     * {@link #resetMicrocompactState()} 只复位当前会话。
     *
     * <p><b>会话键来源</b>: {@link com.nexusai.common.RequestContext#sessionId()}（MDC，ChatService/
     * CommandController 请求入口已设；STREAM_EXECUTOR 虚拟线程 MDC 已由 LlmAgentLoop:4619-4621
     * 回放）。MDC null（测试 / 非请求线程）→ {@link #DEFAULT_SESSION_KEY} 默认桶（fail-loud debug）。
     */
    private static final ConcurrentHashMap<String, MicroCompactSessionState> SESSION_STATES =
        new ConcurrentHashMap<>();

    /** 无会话上下文（MDC null）时的默认桶键（测试 / 非请求线程路径，fail-loud debug 日志披露）。 */
    private static final String DEFAULT_SESSION_KEY = "<default-session>";

    /**
     * 单会话 cached-MC 状态 · OPD-CM5-A-10 会话级隔离（替代静态单例的会话桶）。
     *
     * <p><b>成员对应原模块态字段</b>（均从静态单例迁移为会话键控）：
     * <ul>
     *   <li>{@code cachedMCState} —— 原 {@code static volatile CachedMCState cachedMCState}，
     *       {@link #ensureCachedMCState()} 懒初始化（会话桶内创建，跨 turn 存活）</li>
     *   <li>{@code pendingCacheEditsBlock} —— 原 {@code static volatile CacheEditsBlock}
     *       （microCompact.ts:58-60，provider 层取走注入 API 请求）</li>
     *   <li>{@code pendingCacheEdits} —— 原 {@code static volatile PendingCacheEdits}
     *       （microCompact.ts:385-394，流结束 boundary yield 消费）</li>
     *   <li>{@code mainLoopModel} —— 原 {@code static volatile String mainLoopModel}
     *       （microCompact.ts:278 门控 model 谓词入参，LlmAgentLoop 每轮 turn 注入）</li>
     * </ul>
     */
    private static final class MicroCompactSessionState {
        CachedMCState cachedMCState = null;
        MicroCompactResult.CacheEditsBlock pendingCacheEditsBlock = null;
        MicroCompactResult.PendingCacheEdits pendingCacheEdits = null;
        String mainLoopModel = null;
    }

    /**
     * 解析当前会话的 cached-MC 状态桶 · OPD-CM5-A-10 会话级隔离。
     *
     * <p>会话键 = {@link com.nexusai.common.RequestContext#sessionId()}（MDC 原始 'sess-xxx' 键，
     * 与 ChatService/CommandController 入口同一会话标识）。MDC null（测试 / 非请求线程路径）
     * → {@link #DEFAULT_SESSION_KEY} 默认桶（fail-loud：debug 日志披露，生产正常路径 MDC 恒非 null）。
     *
     * @return 当前会话状态桶（非空，computeIfAbsent 懒建）
     */
    private static MicroCompactSessionState currentSessionState() {
        String sessionId = com.nexusai.common.RequestContext.sessionId();
        if (sessionId == null) {
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] RequestContext.sessionId()=null（测试 / 非请求线程路径），"
                    + "回落默认桶 {} · OPD-CM5-A-10 会话级隔离", DEFAULT_SESSION_KEY);
            }
            sessionId = DEFAULT_SESSION_KEY;
        }
        return SESSION_STATES.computeIfAbsent(sessionId, k -> new MicroCompactSessionState());
    }

    /**
     * 移除某会话的 cached-MC 状态桶 · OPD-CM5-A-10 会话级隔离（会话结束清理入口）。
     *
     * <p>CC 进程随会话结束退出，无泄漏；Java 多会话常驻 JVM，会话结束时由外层（/clear、
     * 会话删除）调用以释放桶内存。null / 未知会话为 no-op。
     *
     * @param sessionId 会话 ID（MDC 原始 'sess-xxx' 键；null → no-op）
     */
    public static void removeSessionState(String sessionId) {
        if (sessionId != null) {
            SESSION_STATES.remove(sessionId);
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] removeSessionState: 移除会话 {} 的 cached-MC 状态桶 · "
                    + "OPD-CM5-A-10 会话级隔离", sessionId);
            }
        }
    }

    /** 测试时钟覆盖 · 0 = System.currentTimeMillis()（对齐 CC Date.now()）。 */
    private static volatile long fixedNowMs = 0L;

    /** 最近一次 time-based MC 实际节省 token（测试观察口；CC 仅 logEvent，不返回）。 */
    private int lastTimeBasedTokensSaved;

    /**
     * PROMPT_CACHE_BREAK_DETECTION notifyCacheDeletion 执行器（默认经 gatedBy 门控，
     * V2-S5：defaultInstance 恒 enabled=true 绕过 feature 门 → 改 gatedBy(FeatureFlags)）。
     */
    private BiConsumer<String, String> notifyCacheDeletion = MicroCompactor::notifyCacheDeletionGated;

    /**
     * PROMPT_CACHE_BREAK_DETECTION feature 值（CC microCompact.ts:362/525
     * {@code if (feature('PROMPT_CACHE_BREAK_DETECTION'))}）· 默认全关（对齐 CC 默认
     * flag 关闭 → notify no-op）。生产由 LlmAgentLoop.run 注入
     * {@code FeatureFlags.promptCacheBreakDetection()} 值（IMP2-01 接线）。
     */
    private static volatile com.nexusai.application.agent.loop.FeatureFlags featureFlags =
        com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;

    /**
     * [token-compact-fix ①] 压缩配置 DB 实时读源静态槽位（null = 未接线 → 回落
     * {@link #cachedMicrocompactFeatureEnabled}）。
     *
     * <p><b>cached-MC 开关实时化</b>：同 {@link BoundaryReader#setSettingsResolver} 静态槽位先例
     * （MicroCompactor 门控方法为静态，无实例注入面）。生产在
     * {@code ToolRegistrationConfig.microCompactor} @Bean 接线（与既有静态槽位同点）。
     * 注入后 {@link #isCachedMicrocompactFeatureEnabled()} 每次调用实时读
     * {@code settings.cached_microcompact_enabled} —— 前端 PUT settings 后下一轮即生效（不再需重启）。
     */
    private static volatile com.nexusai.application.agent.compact.CompactSettingsResolver settingsResolver;

    /**
     * 遥测注入面（CC logEvent 等价 · 1P/Statsig 适配层 Telemetry.recordEvent）。
     * 生产由 {@code ToolRegistrationConfig.microCompactor()} 经 {@link #setTelemetry(Telemetry)}
     * 注入（Spring @Component）；null → 遥测降级为日志（不崩，对齐 emitTelemetry no-op）。
     * cached/time-based MC 触发时 emit（microCompact.ts:341-356 / :498-505 logEvent）。
     */
    private static volatile com.nexusai.application.agent.telemetry.Telemetry telemetry = null;

    /** 默认 notifier：以当前 feature 值 gatedBy（feature 关 → no-op，对齐 CC 门控）。 */
    private static void notifyCacheDeletionGated(String querySource, String agentId) {
        com.nexusai.application.agent.lsp.PromptCacheBreakDetection.gatedBy(featureFlags)
            .notifyCacheDeletion(querySource, agentId);
    }

    public MicroCompactor() {
        this(new TokenEstimator());
    }

    public MicroCompactor(TokenEstimator tokenEstimator) {
        this(tokenEstimator, () -> TimeBasedMCConfig.DEFAULTS);
    }

    /** 生产配置注入 · 对齐 CC {@code getTimeBasedMCConfig()}（timeBasedMCConfig.ts:36-43）
     * —— 每次评估经 {@code source.get()} 实时读取（GB feature 值 / Spring 属性等价）。 */
    public MicroCompactor(Supplier<TimeBasedMCConfig> timeBasedMCConfigSource) {
        this(new TokenEstimator(), timeBasedMCConfigSource);
    }

    public MicroCompactor(TokenEstimator tokenEstimator,
                          Supplier<TimeBasedMCConfig> timeBasedMCConfigSource) {
        this.tokenEstimator = tokenEstimator != null ? tokenEstimator : new TokenEstimator();
        this.timeBasedMCConfigSource =
            timeBasedMCConfigSource != null ? timeBasedMCConfigSource : () -> TimeBasedMCConfig.DEFAULTS;
    }

    // ════════════════════════════════════════════════════════════════════
    // 链式入口 · CC microcompactMessages (microCompact.ts:253-293)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Micro 压缩链式入口 · 对齐 CC {@code microcompactMessages(messages, querySource)}
     * （microCompact.ts:253）。
     *
     * <p><b>执行顺序不变量（REQ-13，IMP-09 §5 验收 1）</b>:
     * <ol>
     *   <li>clearCompactWarningSuppression —— 新压缩尝试开始复位警告抑制（:259）</li>
     *   <li>time-based 短路 —— 触发即返回，cached 被跳过（:267-270）</li>
     *   <li>cached 门控 —— 四条件（V2-S3，:276-286）</li>
     *   <li>默认 no-op —— 返回原消息（:288-292，INV-10）</li>
     * </ol>
     *
     * <p><b>状态机已实现</b>: cached-MC 内部算法已按 CC 真源 cachedMicrocompact.ts（112 行）对齐
     * （registerToolResult/createCacheEditsBlock 等，见 {@link #cachedMicrocompactPath}）。
     * <p><b>provider 接线已闭环（OD-01 leftover）</b>:
     * <ol>
     *   <li>{@link #markToolsSentToAPIState()} 由 LlmAgentLoop 流结束点经
     *       {@link #cachedMicrocompactEnabledForModel(String)} 门控调用（claude.ts:2834-2836）</li>
     *   <li>{@link #consumePendingCacheEditsBlock()} 由 AnthropicSdkProvider 请求构造点
     *       经 {@link #cachedMicrocompactEnabledForModel(String)} 门控消费一次（claude.ts:1528-1535；
     *       SDK cache_edits 内容块序列化受 SDK 结构限制，见 leftover）</li>
     *   <li>{@link #setMainLoopModel(String)} 由 LlmAgentLoop 主循环每轮 turn 起始注入
     *       （microCompact.ts:278 toolUseContext.options.mainLoopModel 等价）</li>
     * </ol>
     *
     * @param messages   待处理消息列表（非空）
     * @param querySource CC QuerySource（主循环传 "repl_main_thread:..."；/compact 传 null
     *                    = CC undefined，V2-S4 对齐 compact.ts:98；null = 无源）
     * @return microcompact 结果（{@code {messages, compactionInfo?}}）
     */
    public MicroCompactResult microcompactMessages(List<ChatMessageDto> messages, String querySource) {
        if (messages == null) {
            throw new IllegalArgumentException("MicroCompactor.microcompactMessages: messages is null");
        }

        // ── 1. 压缩开始复位警告抑制（microCompact.ts:259 clearCompactWarningSuppression）──
        CompactWarningState.clearCompactWarningSuppression();

        // ── 2. time-based 短路（microCompact.ts:267-270 maybeTimeBasedMicrocompact）──
        MicroCompactResult timeBased = maybeTimeBasedMicrocompact(messages, querySource);
        if (timeBased != null) {
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] microcompactMessages: time-based 触发短路，返回清除结果");
            }
            return timeBased;
        }

        // ── 3. cached 门控（microCompact.ts:276-286 四条件）──
        // CC: feature('CACHED_MICROCOMPACT') && mod.isCachedMicrocompactEnabled() &&
        //     mod.isModelSupportedForCacheEditing(model) && isMainThreadSource(querySource)
        //     model = toolUseContext?.options.mainLoopModel ?? getMainLoopModel()（microCompact.ts:278）
        if (isCachedMicrocompactFeatureEnabled()
                && isCachedMicrocompactEnabled()
                && isModelSupportedForCacheEditing(currentSessionState().mainLoopModel)
                && isMainThreadSource(querySource)) {
            return cachedMicrocompactPath(messages, querySource);
        }

        // ── 4. 默认 no-op（legacy 路径已移除，microCompact.ts:288-292，INV-10）──
        if (log.isDebugEnabled()) {
            log.debug("[MicroCompactor] microcompactMessages: 默认 no-op（无 time-based/cached 触发），返回原消息");
        }
        return new MicroCompactResult(messages, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // time-based MC · CC evaluateTimeBasedTrigger / maybeTimeBasedMicrocompact
    // ════════════════════════════════════════════════════════════════════

    /**
     * 判断 time-based 触发是否应响 · 对齐 CC {@code evaluateTimeBasedTrigger(messages, querySource)}
     * （microCompact.ts:422-444）。
     *
     * <p><b>触发条件（REQ-15，microCompact.ts:426-443）</b>:
     * <ol>
     *   <li>config.enabled —— 主开关（timeBasedMCConfig.ts:20）</li>
     *   <li>querySource 非空且 main-thread —— 显式 main-thread（microCompact.ts:431）：
     *       isMainThreadSource 将 undefined 视为 main-thread（cached-MC 向后兼容），但 /context、
     *       /compact、analyzeContext 无 source 仅做分析，不应触发</li>
     *   <li>存在最后一条 assistant 消息且时间戳可解析（microCompact.ts:434-437）</li>
     *   <li>gapMinutes >= gapThresholdMinutes（microCompact.ts:438-442）</li>
     * </ol>
     *
     * @param messages    消息列表
     * @param querySource CC QuerySource
     * @return 触发时返回 {gapMinutes, config}，否则 null
     */
    public TimeBasedTriggerResult evaluateTimeBasedTrigger(List<ChatMessageDto> messages, String querySource) {
        TimeBasedMCConfig config = timeBasedMCConfigSource.get();
        if (!config.enabled() || querySource == null || !isMainThreadSource(querySource)) {
            return null;
        }
        ChatMessageDto lastAssistant = findLastAssistant(messages);
        if (lastAssistant == null || lastAssistant.createdAt() == null) {
            return null;
        }
        double gapMinutes =
            (nowMs() - lastAssistant.createdAt().toInstant().toEpochMilli()) / 60_000.0;
        if (!Double.isFinite(gapMinutes) || gapMinutes < config.gapThresholdMinutes()) {
            return null;
        }
        return new TimeBasedTriggerResult(gapMinutes, config);
    }

    /**
     * time-based MC 执行 · 对齐 CC {@code maybeTimeBasedMicrocompact}（microCompact.ts:446-530）。
     *
     * <p><b>语义（REQ-15）</b>:
     * <ul>
     *   <li>keepRecent = {@code Math.max(1, config.keepRecent)} floor 1 —— 禁止清空全部（:461）</li>
     *   <li>清空 content 为 {@link CompactConstants#TIME_BASED_MC_CLEARED_MESSAGE}（:483）</li>
     *   <li>tokensSaved 真实统计（V2-S2）—— 对每条被清除工具消息以
     *       {@link TokenEstimator#calculateToolResultTokens}（CC calculateToolResultTokens 的
     *       Java 镜像，microCompact.ts:138-157，<b>无 ×4/3</b>）累计（microCompact.ts:481），
     *       非固定系数（D-13 已删）、非 estimateMessageTokens 的 padding 口径（高估 ~33%）</li>
     *   <li>成功收尾：suppressCompactWarning（:511）→ resetMicrocompactState（:517，清 pendingCacheEdits）
     *       → notifyCacheDeletion(querySource)（:526，cache break 误报防护）</li>
     * </ul>
     *
     * @param messages    消息列表
     * @param querySource CC QuerySource（触发时必为 main-thread）
     * @return 触发并清除后返回 {@code {messages}}；未触发返回 null
     */
    private MicroCompactResult maybeTimeBasedMicrocompact(List<ChatMessageDto> messages, String querySource) {
        TimeBasedTriggerResult trigger = evaluateTimeBasedTrigger(messages, querySource);
        if (trigger == null) {
            return null;
        }
        double gapMinutes = trigger.gapMinutes();
        TimeBasedMCConfig config = trigger.config();

        List<String> compactableIds = collectCompactableToolIds(messages);

        // Floor at 1：slice(-0) 返回全量（悖论保留全部），清除全部会留下零工作上下文——两者
        // 都是退化态；至少保留最后一条（microCompact.ts:458-461）。
        int keepRecent = Math.max(1, config.keepRecent());
        int keepStart = Math.max(0, compactableIds.size() - keepRecent);
        Set<String> keepSet = new HashSet<>(compactableIds.subList(keepStart, compactableIds.size()));
        Set<String> clearSet = new HashSet<>();
        for (String id : compactableIds) {
            if (!keepSet.contains(id)) {
                clearSet.add(id);
            }
        }

        if (clearSet.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] time-based MC：无旧工具结果可清除（keepRecent={}），跳过",
                    keepRecent);
            }
            return null;
        }

        int tokensSaved = 0;
        List<ChatMessageDto> result = new ArrayList<>(messages.size());
        for (ChatMessageDto message : messages) {
            if (message.role() == Role.tool && message.toolCallId() != null
                    && clearSet.contains(message.toolCallId())
                    && !CompactConstants.TIME_BASED_MC_CLEARED_MESSAGE.equals(message.content())) {
                // V2-S2：CC tokensSaved += calculateToolResultTokens(block)（microCompact.ts:481），
                // 无 ×4/3 padding（estimateMessageTokens 单消息重载高估 ~33%，已弃用本处）
                int saved = tokenEstimator.calculateToolResultTokens(message);
                tokensSaved += saved;
                if (log.isDebugEnabled()) {
                    log.debug("[MicroCompactor] time-based MC：清除工具结果 toolCallId={}，节省约 {} tokens"
                            + "（CC calculateToolResultTokens 口径，无 ×4/3）",
                        message.toolCallId(), saved);
                }
                result.add(clearContent(message));
            } else {
                result.add(message);
            }
        }

        if (tokensSaved == 0) {
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] time-based MC：tokensSaved=0（均已清除/空内容），跳过");
            }
            return null;
        }
        this.lastTimeBasedTokensSaved = tokensSaved;

        log.info("[MicroCompactor] time-based MC：gap {}min > 阈值 {}min，清除 {} 个工具结果（节省约 {} tokens，"
                + "CC calculateToolResultTokens 口径无 ×4/3），保留最近 {} 个 · CC tengu_time_based_microcompact",
            Math.round(gapMinutes), config.gapThresholdMinutes(), clearSet.size(), tokensSaved, keepSet.size());

        // 遥测 logEvent（对齐 CC microCompact.ts:498-505 logEvent('tengu_time_based_microcompact')，
        // 字段名逐一对齐：gapMinutes/toolsCleared/toolsKept/tokensSaved）
        emitTelemetry("tengu_time_based_microcompact", java.util.Map.of(
            "gapMinutes", Math.round(gapMinutes),
            "gapThresholdMinutes", config.gapThresholdMinutes(),
            "toolsCleared", clearSet.size(),
            "toolsKept", keepSet.size(),
            "keepRecent", config.keepRecent(),
            "tokensSaved", tokensSaved));

        // 压缩成功抑制警告（microCompact.ts:511 suppressCompactWarning）
        CompactWarningState.suppressCompactWarning();
        // 刚内容清除 + 服务端缓存失效 → 若 next turn cached-MC 带陈旧 state 运行会删不存在的工具
        // → 重置（microCompact.ts:513-517 resetMicrocompactState）
        resetMicrocompactState();
        // 刚改了 prompt 内容 → 下一次响应 cache read 会低，但那是我们，不是 break →
        // 通知检测器预期下降（microCompact.ts:520-527 notifyCacheDeletion）
        if (querySource != null) {
            notifyCacheDeletion.accept(querySource, null);
        }

        return new MicroCompactResult(result, null);
    }

    // ════════════════════════════════════════════════════════════════════
    // cached MC · CC cachedMicrocompactPath (microCompact.ts:305-399) · 状态机已实现
    // ════════════════════════════════════════════════════════════════════

    /**
     * cached-MC 路径 · 对齐 CC {@code cachedMicrocompactPath(messages, querySource)}
     * （microCompact.ts:305-399），内部算法按 cachedMicrocompact.ts 真源实现。
     *
     * <p><b>数据流（microCompact.ts:305-399）</b>:
     * <ol>
     *   <li>{@link #ensureCachedMCState()} 取当前会话状态桶 + {@link #getCachedMCConfig()}（:310-311）</li>
     *   <li>collectCompactableToolIds → compactableToolIds（:313）</li>
     *   <li>二次遍历按 user 消息分组注册（:315-330）——Java 结构映射：tool_result 为
     *       {@code Role.tool} 独立消息（等价 CC 内嵌于 user 消息 content 的块），以「连续
     *       Role.tool 消息段」为一组；经 {@link #registerToolResult}/{@link #registerToolMessage}</li>
     *   <li>{@link #getToolResultsToDelete} 超阈值触发（:332），空 → {@code {messages}}（:397-398）</li>
     *   <li>toolsToDelete&gt;0 → {@link #createCacheEditsBlock} 构建块并入队模块态
     *       {@code pendingCacheEditsBlock}（:334-339）+ logEvent（:341-356）+
     *       {@code suppressCompactWarning()}（:359）+ notifyCacheDeletion（:361-367）</li>
     *   <li>baseline = 最后一条 assistant 的累计 cache_deleted_input_tokens（:372-383）</li>
     *   <li>返回 {@code {messages, compactionInfo:{pendingCacheEdits}}}（:385-394）</li>
     * </ol>
     *
     * @param messages    消息列表
     * @param querySource CC QuerySource（门控已保证 main-thread）
     * @return 无删除时 {@code {messages}}；删除时带 compactionInfo
     */
    private MicroCompactResult cachedMicrocompactPath(List<ChatMessageDto> messages, String querySource) {
        if (log.isDebugEnabled()) {
            log.debug("[MicroCompactor] cached-MC 路径进入（CC cachedMicrocompactPath，microCompact.ts:305-399）· source={}",
                querySource);
        }
        CachedMCState state = ensureCachedMCState();
        MicroCompactResult.CachedMCConfig config = getCachedMCConfig();

        // ── ① compactable 工具集（microCompact.ts:313 collectCompactableToolIds）──
        Set<String> compactableToolIds = new HashSet<>(collectCompactableToolIds(messages));

        // ── ② 二次遍历：按 user 消息分组注册（microCompact.ts:315-330）──
        // Java 结构：tool_result 为 Role.tool 独立消息（等价 CC 内嵌于 user 消息 content 的块），
        // 以「连续 Role.tool 消息段」为一组对齐 CC 的 per-user-message 分组语义。
        List<String> group = null;
        for (ChatMessageDto message : messages) {
            if (message.role() == Role.tool && message.toolCallId() != null
                    && compactableToolIds.contains(message.toolCallId())
                    && !state.registeredTools.contains(message.toolCallId())) {
                if (group == null) {
                    group = new ArrayList<>();
                }
                group.add(message.toolCallId());
            } else if (group != null) {
                registerToolMessage(state, group);
                group = null;
            }
        }
        if (group != null) {
            registerToolMessage(state, group);
        }

        // ── ③ 触发判定（microCompact.ts:332 getToolResultsToDelete）──
        List<String> toolsToDelete = getToolResultsToDelete(state);

        if (toolsToDelete.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] cached-MC：无超阈值工具（active={} ≤ triggerThreshold={}），"
                        + "无编辑产出，返回原消息 · CC microCompact.ts:397-398",
                    state.toolOrder.size() - state.deletedRefs.size(), config.triggerThreshold());
            }
            return new MicroCompactResult(messages, null);
        }

        // ── ④ 构建 cache_edits 块并入队模块态（microCompact.ts:334-339）──
        MicroCompactResult.CacheEditsBlock cacheEdits = createCacheEditsBlock(state, toolsToDelete);
        if (cacheEdits != null) {
            currentSessionState().pendingCacheEditsBlock = cacheEdits;
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] cached-MC：cache_edits 块已入队当前会话（type={}, edits={}）"
                        + "· CC microCompact.ts:336-339", cacheEdits.type(), cacheEdits.edits().size());
            }
        }

        // ── ⑤ baseline：最后一条 assistant 的累计 cache_deleted_input_tokens（microCompact.ts:372-383）──
        long baseline = baselineCacheDeletedTokens(messages);

        // ── ⑥ boundary 消费引用面入队（microCompact.ts:385-394 compactionInfo.pendingCacheEdits）──
        currentSessionState().pendingCacheEdits =
            new MicroCompactResult.PendingCacheEdits("auto", toolsToDelete, baseline);

        log.info("[MicroCompactor] cached-MC：删除 {} 个工具（{}），activeToolCount={}, triggerType=auto,"
                + " threshold={}, keepRecent={} · CC tengu_cached_microcompact（microCompact.ts:341-356）",
            toolsToDelete.size(), toolsToDelete,
            state.toolOrder.size() - state.deletedRefs.size(),
            config.triggerThreshold(), config.keepRecent());

        // 遥测 logEvent（对齐 CC microCompact.ts:341-356 logEvent('tengu_cached_microcompact')，
        // 字段名逐一对齐：deletedToolIds 逗号拼接等价 CC array）
        emitTelemetry("tengu_cached_microcompact", java.util.Map.of(
            "toolsDeleted", toolsToDelete.size(),
            "deletedToolIds", String.join(",", toolsToDelete),
            "activeToolCount", state.toolOrder.size() - state.deletedRefs.size(),
            "triggerType", "auto",
            "threshold", config.triggerThreshold(),
            "keepRecent", config.keepRecent()));

        // ── ⑦ 压缩成功抑制警告（microCompact.ts:359 suppressCompactWarning）──
        CompactWarningState.suppressCompactWarning();

        // ── ⑧ 通知 cache break 检测预期下降（microCompact.ts:361-367，feature 门在 notifier 内 gatedBy）──
        notifyCacheDeletion.accept(querySource != null ? querySource : "repl_main_thread", null);

        return new MicroCompactResult(messages,
            new MicroCompactResult.MicroCompactCompactionInfo(
                new MicroCompactResult.PendingCacheEdits("auto", toolsToDelete, baseline)));
    }

    // ════════════════════════════════════════════════════════════════════
    // cached-MC 状态机 · CC cachedMicrocompact.ts 全量对齐（112 行真源）
    // ════════════════════════════════════════════════════════════════════

    /** 触发阈值 · CC TRIGGER_THRESHOLD（cachedMicrocompact.ts:19）。active 工具数超过即触发删除。 */
    static final int TRIGGER_THRESHOLD = 10;

    /** 保留最近 N 个工具结果 · CC KEEP_RECENT（cachedMicrocompact.ts:20）。 */
    static final int KEEP_RECENT = 5;

    /**
     * env 判定 cached-MC 是否启用 · 对齐 CC {@code isCachedMicrocompactEnabled()}
     * （cachedMicrocompact.ts:26-28）：{@code process.env.CLAUDE_CACHED_MICROCOMPACT === '1'}。
     *
     * <p>测试 override：{@link #cachedMicrocompactModuleEnabledOverride} 非 null 时强制返回该值
     * （{@code setCachedMicrocompactModuleEnabledForTest} 兼容缝）；null 时用真 env 判定。
     *
     * @return CLAUDE_CACHED_MICROCOMPACT 环境变量为 "1" 时 true
     */
    static boolean isCachedMicrocompactEnabled() {
        if (cachedMicrocompactModuleEnabledOverride != null) {
            return cachedMicrocompactModuleEnabledOverride;
        }
        return "1".equals(System.getenv("CLAUDE_CACHED_MICROCOMPACT"));
    }

    /**
     * 模型是否支持 cache_edits · 对齐 CC {@code isModelSupportedForCacheEditing(model)}
     * （cachedMicrocompact.ts:33-35）：{@code /claude-[a-z]+-4[-\d]/.test(model)}（.test = 非锚定 find）。
     *
     * <p>测试 override：{@link #cachedMicrocompactModelSupportedOverride} 非 null 时强制返回该值
     * （{@code setCachedMicrocompactModelSupportedForTest} 兼容缝）；null 时用真 regex 判定。
     *
     * @param model 主循环模型（null → 不匹配）
     * @return 模型匹配 Claude 4.x 正则时 true
     */
    static boolean isModelSupportedForCacheEditing(String model) {
        if (cachedMicrocompactModelSupportedOverride != null) {
            return cachedMicrocompactModelSupportedOverride;
        }
        return model != null && CACHE_EDITING_MODEL_PATTERN.matcher(model).find();
    }

    /**
     * cached-MC 配置 · 对齐 CC {@code getCachedMCConfig()}（cachedMicrocompact.ts:37-42）：
     * 返回 {@code {triggerThreshold: TRIGGER_THRESHOLD, keepRecent: KEEP_RECENT}}。
     *
     * <p><b>[token-compact-settings-fix] DB 数值实时化</b>：触发阈值/保留条数从
     * {@link #settingsResolver} 实时读 DB {@code settings.cached_microcompact_trigger_threshold}
     * / {@code settings.cached_microcompact_keep_recent}（前端 PUT settings 后下一轮即生效，
     * 无需重启）；DB 值非 null 且 &gt; 0 用之（resolver 方法内部已滤掉非正 → null），
     * 否则回落 {@link #TRIGGER_THRESHOLD}（10）/ {@link #KEEP_RECENT}（5）。resolver 为 null
     * （未接线 / 读取失败）时整条回落默认 {@code {10, 5}}，零行为变化。对齐
     * {@link #isCachedMicrocompactFeatureEnabled()} 的 DB-aware 回落范式（V52 同款）。
     *
     * @return {@code {triggerThreshold, keepRecent}}（DB 有值实时覆盖，否则默认 10/5）
     */
    static MicroCompactResult.CachedMCConfig getCachedMCConfig() {
        Integer triggerThreshold = null;
        Integer keepRecent = null;
        if (settingsResolver != null) {
            triggerThreshold = settingsResolver.cachedMicrocompactTriggerThreshold();
            keepRecent = settingsResolver.cachedMicrocompactKeepRecent();
        }
        int threshold = triggerThreshold != null ? triggerThreshold : TRIGGER_THRESHOLD;
        int keep = keepRecent != null ? keepRecent : KEEP_RECENT;
        if (log.isDebugEnabled()) {
            log.debug("[MicroCompactor] getCachedMCConfig: triggerThreshold={}, keepRecent={}"
                    + "（DB 实时读，resolver 已注入={}；null 回落默认 10/5）"
                    + "· CC cachedMicrocompact.ts:37-42",
                threshold, keep, settingsResolver != null);
        }
        return new MicroCompactResult.CachedMCConfig(threshold, keep);
    }

    /**
     * 新建空 cached-MC 状态 · 对齐 CC {@code createCachedMCState()}
     * （cachedMicrocompact.ts:44-52）：registeredTools/toolOrder/deletedRefs/pinnedEdits 空，
     * toolsSentToAPI=false。
     *
     * @return 全新空状态
     */
    static CachedMCState createCachedMCState() {
        return new CachedMCState();
    }

    /**
     * 标记工具已下发 API · 对齐 CC {@code markToolsSentToAPI(state)}
     * （cachedMicrocompact.ts:54-56）：{@code state.toolsSentToAPI = true}。
     * API 层在成功响应后经 {@link #markToolsSentToAPIState()} 调用（对齐 claude.ts:2835）。
     *
     * @param state 模块态（非空）
     */
    static void markToolsSentToAPI(CachedMCState state) {
        state.toolsSentToAPI = true;
    }

    /**
     * 复位 cached-MC 状态 · 对齐 CC {@code resetCachedMCState(state)}
     * （cachedMicrocompact.ts:58-64）：清空全部 5 字段。
     * 由 {@link #resetMicrocompactState()}（microCompact.ts:131-133）调用。
     *
     * @param state 模块态（非空）
     */
    static void resetCachedMCState(CachedMCState state) {
        state.registeredTools.clear();
        state.toolOrder.clear();
        state.deletedRefs.clear();
        state.pinnedEdits.clear();
        state.toolsSentToAPI = false;
    }

    /**
     * 注册单个工具结果 · 对齐 CC {@code registerToolResult(state, toolId)}
     * （cachedMicrocompact.ts:66-71）：{@code if (!state.registeredTools.has(toolId)) {
     * state.registeredTools.add(toolId); state.toolOrder.push(toolId) }}。
     *
     * @param state  模块态（非空）
     * @param toolId 工具结果 tool_use_id（已注册则 no-op 去重）
     */
    static void registerToolResult(CachedMCState state, String toolId) {
        if (state.registeredTools.add(toolId)) {
            state.toolOrder.add(toolId);
        }
    }

    /**
     * 按组注册工具结果 · 对齐 CC {@code registerToolMessage(state, groupIds)}
     * （cachedMicrocompact.ts:73-80）：对组内每个 id 循环 {@code registerToolResult}。
     *
     * @param state    模块态（非空）
     * @param groupIds 同一 user 消息分组的 tool_use_id 列表（可为空）
     */
    static void registerToolMessage(CachedMCState state, List<String> groupIds) {
        if (groupIds == null) {
            return;
        }
        for (String id : groupIds) {
            registerToolResult(state, id);
        }
    }

    /**
     * 计算应删除的工具 id（最旧优先）· 对齐 CC {@code getToolResultsToDelete(state)}
     * （cachedMicrocompact.ts:87-94）。
     *
     * <p><b>算法</b>：active = toolOrder 过滤 deletedRefs；active ≤ triggerThreshold → 空；
     * 否则保留最近 keepRecent 个，返回 {@code slice(0, active.length - keepRecent)}（最旧优先）。
     *
     * @param state 模块态（非空）
     * @return 待删除工具 id 列表（空 = 未超阈值）
     */
    static List<String> getToolResultsToDelete(CachedMCState state) {
        MicroCompactResult.CachedMCConfig config = getCachedMCConfig();
        List<String> active = new ArrayList<>(state.toolOrder.size());
        for (String id : state.toolOrder) {
            if (!state.deletedRefs.contains(id)) {
                active.add(id);
            }
        }
        if (active.size() <= config.triggerThreshold()) {
            return List.of();
        }
        int end = active.size() - config.keepRecent();
        if (end <= 0) {
            return List.of();
        }
        return new ArrayList<>(active.subList(0, end));
    }

    /**
     * 构建 cache_edits 块 · 对齐 CC {@code createCacheEditsBlock(state, toolIds)}
     * （cachedMicrocompact.ts:100-112）：空 → null；否则映射
     * {@code {type:'cache_edits', edits:[{type:'delete_tool_result', tool_use_id}]}}。
     *
     * @param state   模块态（CC 参数，仅对齐签名）
     * @param toolIds 待删除工具 id 列表
     * @return cache_edits 块；toolIds 空时 null
     */
    static MicroCompactResult.CacheEditsBlock createCacheEditsBlock(CachedMCState state,
                                                                     List<String> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return null;
        }
        List<MicroCompactResult.CacheEditsBlock.CacheEdit> edits = new ArrayList<>(toolIds.size());
        for (String id : toolIds) {
            edits.add(new MicroCompactResult.CacheEditsBlock.CacheEdit(
                MicroCompactResult.CacheEditsBlock.CacheEdit.TYPE_DELETE_TOOL_RESULT, id));
        }
        return new MicroCompactResult.CacheEditsBlock(
            MicroCompactResult.CacheEditsBlock.TYPE_CACHE_EDITS, edits);
    }

    /**
     * 懒初始化当前会话的 cachedMCState · 对齐 CC {@code ensureCachedMCState()}
     * （microCompact.ts:71-81）。首次调用经 {@code createCachedMCState} 新建，此后复用
     * （会话内跨 turn 存活，resetMicrocompactState 复位）。OPD-CM5-A-10：每会话独立桶，
     * 不同会话互不污染（原静态单例跨会话泄漏）。
     *
     * @return 当前会话 cachedMCState（非空）
     */
    private static CachedMCState ensureCachedMCState() {
        MicroCompactSessionState ss = currentSessionState();
        if (ss.cachedMCState == null) {
            ss.cachedMCState = createCachedMCState();
        }
        return ss.cachedMCState;
    }

    /**
     * baseline 累计 cache_deleted_input_tokens · 对齐 CC microCompact.ts:372-383
     * （{@code lastAsst.message.usage.cache_deleted_input_tokens ?? 0}）。
     *
     * <p><b>OD-01 已闭环（provider 接线）</b>: AnthropicSdkProvider 已从 message_start usage 的
     * {@code additionalProperties()} 提取 {@code cache_deleted_input_tokens} 到 AgentUsage →
     * ChatMessageDto.usage()（微压缩前最后一条 assistant 消息承载上一次 API 响应的累计值）。
     * 此处读取真实值；usage 缺失/字段缺失 → {@code ?? 0} 等价（CC 同）。
     *
     * @param messages 消息列表
     * @return 最后一条 assistant 的累计 cache_deleted_input_tokens（usage 缺失时 0）
     */
    private long baselineCacheDeletedTokens(List<ChatMessageDto> messages) {
        ChatMessageDto lastAsst = findLastAssistant(messages);
        if (lastAsst == null || lastAsst.usage() == null
                || lastAsst.usage().cacheDeletedInputTokens() == null) {
            return 0L;
        }
        return lastAsst.usage().cacheDeletedInputTokens();
    }

    /**
     * cached-MC 请求级门控 · 对齐 CC {@code cachedMCEnabled}（claude.ts:1198-1200）：
     * {@code feature('CACHED_MICROCOMPACT') && isCachedMicrocompactEnabled() &&
     * isModelSupportedForCacheEditing(options.model)}。
     *
     * <p>CC 在 async 上下文每请求计算一次（claude.ts:1188-1200），供
     * markToolsSentToAPIState（claude.ts:2834）、consumePendingCacheEdits（claude.ts:1531）、
     * useCachedMC（claude.ts:1675-1678）共用。Java 生产接线：LlmAgentLoop 流结束点
     * markToolsSentToAPIState 门控 + AnthropicSdkProvider 请求构造 consume 门控。
     *
     * @param model 请求模型（CC options.model；null → model 谓词 false，门关）
     * @return 三条件全真时 true
     */
    public static boolean cachedMicrocompactEnabledForModel(String model) {
        return isCachedMicrocompactFeatureEnabled()
            && isCachedMicrocompactEnabled()
            && isModelSupportedForCacheEditing(model);
    }

    /**
     * 标记已注册工具全部下发 API · 对齐 CC {@code markToolsSentToAPIState()}
     * （microCompact.ts:124-128）。成功响应后调用（claude.ts:2835）；生产接线为受控残留。
     */
    public static void markToolsSentToAPIState() {
        CachedMCState state = currentSessionState().cachedMCState;
        if (state != null) {
            markToolsSentToAPI(state);
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] markToolsSentToAPIState：toolsSentToAPI=true · CC microCompact.ts:124-128 / claude.ts:2835");
            }
        }
    }

    /**
     * 取走已钉住 cache_edits 块 · 对齐 CC {@code getPinnedCacheEdits()}
     * （microCompact.ts:96-105）：返回 {@code cachedMCState.pinnedEdits}（未初始化时空）。
     * API 层在构造请求时重发原始位置块（claude.ts:1532）。
     *
     * @return 已钉住块列表（不可变快照语义）
     */
    public static List<MicroCompactResult.PinnedCacheEdits> getPinnedCacheEdits() {
        CachedMCState state = currentSessionState().cachedMCState;
        if (state == null) {
            return List.of();
        }
        return new ArrayList<>(state.pinnedEdits);
    }

    /**
     * 钉住新 cache_edits 块 · 对齐 CC {@code pinCacheEdits(userMessageIndex, block)}
     * （microCompact.ts:111-118）：块插入消息后钉住，后续请求在原始位置重发（claude.ts:3153）。
     *
     * @param userMessageIndex 块插入的用户消息下标
     * @param block            cache_edits 块（非空）
     */
    public static void pinCacheEdits(int userMessageIndex, MicroCompactResult.CacheEditsBlock block) {
        CachedMCState state = currentSessionState().cachedMCState;
        if (state != null && block != null) {
            state.pinnedEdits.add(
                new MicroCompactResult.PinnedCacheEdits(userMessageIndex, block));
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] pinCacheEdits：userMessageIndex={}, edits={} · CC microCompact.ts:111-118",
                    userMessageIndex, block.edits().size());
            }
        }
    }

    /**
     * 取走待下发 cache_edits 块并清空 · CC 真源 {@code consumePendingCacheEdits()}
     * （microCompact.ts:88-94）返回 {@code CacheEditsBlock}。API 层构造请求时取走注入
     * cache_edits（claude.ts:1528-1535）；Java provider 接线为受控残留。
     *
     * @return 未捕获时 null；否则返回待下发块并清空
     */
    public static MicroCompactResult.CacheEditsBlock consumePendingCacheEditsBlock() {
        MicroCompactSessionState ss = currentSessionState();
        MicroCompactResult.CacheEditsBlock block = ss.pendingCacheEditsBlock;
        ss.pendingCacheEditsBlock = null;
        return block;
    }

    // ════════════════════════════════════════════════════════════════════
    // 引用面（cached-MC）· CC 模块级导出 (microCompact.ts:88-135)
    // ════════════════════════════════════════════════════════════════════

    /**
     * 取走 boundary 引用面 pendingCacheEdits 并清空 · 对齐 CC
     * {@code compactionInfo.pendingCacheEdits} 消费（microCompact.ts:385-394 → query.ts:866-892）。
     *
     * <p><b>WHY 双通道</b>: CC 模块态 {@code pendingCacheEdits} 实为 {@code CacheEditsBlock}
     * （provider 注入请求用，microCompact.ts:58-60/336-339），经
     * {@link #consumePendingCacheEditsBlock()} 取走；本函数消费的是 compactionInfo 形状
     * {@code {trigger,deletedToolIds,baselineCacheDeletedTokens}}（boundary yield 用，
     * 供 {@link #maybeCreateMicrocompactBoundaryMessage(long)}）。cachedMicrocompactPath 删除
     * 触发时写入，测试缝也可注入。本函数保证消费契约（返回 + 清空）。
     *
     * @return 未捕获时 null；否则返回待下发 edits 并清空
     */
    public static MicroCompactResult.PendingCacheEdits consumePendingCacheEdits() {
        MicroCompactSessionState ss = currentSessionState();
        MicroCompactResult.PendingCacheEdits edits = ss.pendingCacheEdits;
        ss.pendingCacheEdits = null;
        return edits;
    }

    /**
     * 延迟 microcompact boundary yield · 对齐 CC query.ts:866-892。
     *
     * <p><b>WHY（MISS-1，IMP2-11）</b>: cached-MC 的 boundary 不在 microcompactMessages 内产出，
     * 而在 API 流结束后以真实上报的 {@code cache_deleted_input_tokens} 计算 delta 再 yield
     * （query.ts:866-868 注释 "Yield deferred microcompact boundary message using actual
     * API-reported token deletion count instead of client-side estimates"）。本方法为
     * LlmAgentLoop 流结束点的生产入口（MISS-1 生产接线，query.ts:866-892），同时是
     * {@link CompactBoundaryMessage#createMicrocompactBoundaryMessage} 的唯一生产调用方。
     *
     * <p><b>数据流（query.ts:870-890）</b>:
     * <ol>
     *   <li>feature('CACHED_MICROCOMPACT') 门（:870）——关 → 不消费直接返回 null（外部构建等价）</li>
     *   <li>{@link #consumePendingCacheEdits()} 取走模块态 pendingCacheEdits（microCompact.ts:88-94）</li>
     *   <li>delta = max(0, cumulative − baseline)（:879-882 —— API 字段 sticky/cumulative，
     *       减基线得本次操作增量）</li>
     *   <li>delta &gt; 0 → {@code createMicrocompactBoundaryMessage(trigger, 0, deletedTokens,
     *       deletedToolIds, [])}（:884-890）</li>
     * </ol>
     *
     * @param cumulativeCacheDeletedTokens 最近一次 API 响应的累计 cache_deleted_input_tokens
     *     （CC :874-878 lastAssistant.message.usage 读取）。AnthropicSdkProvider 已提取该字段到
     *     AgentUsage.cacheDeletedInputTokens()（AnthropicSdkProvider:770 parseCacheDeletedInputTokens，
     *     message_start 与 message 双源），LlmAgentLoop 经 cumulativeCacheDeletedTokens
     *     （LlmAgentLoop:2915-2930）在流结束点（:5070）传入真实值；OpenAI/Mock 无等价 → 0
     *     （等价 CC ?? 0）。TODO[OD-01] 已闭环。
     * @return delta &gt; 0 时 microcompact_boundary 消息；否则 null
     */
    public static CompactBoundaryMessage maybeCreateMicrocompactBoundaryMessage(long cumulativeCacheDeletedTokens) {
        // feature 门（query.ts:870）：关 → 不消费（外部构建等价，字符串被消除）
        if (!isCachedMicrocompactFeatureEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] 流结束: CACHED_MICROCOMPACT feature 关，跳过 pendingCacheEdits 消费与 boundary yield · CC query.ts:870");
            }
            return null;
        }
        MicroCompactResult.PendingCacheEdits edits = consumePendingCacheEdits();
        if (edits == null) {
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] 流结束: 无 pendingCacheEdits（cached-MC 未产出 cache_edits），跳过 boundary yield · CC query.ts:870");
            }
            return null;
        }
        // API 字段累计/sticky，减基线得本次操作 delta（query.ts:872-882）
        long deletedTokens = Math.max(0L, cumulativeCacheDeletedTokens - edits.baselineCacheDeletedTokens());
        if (deletedTokens <= 0L) {
            if (log.isDebugEnabled()) {
                log.debug("[MicroCompactor] 流结束: cache_deleted_input_tokens delta={}（累计={}, 基线={}）≤ 0，跳过 boundary yield · CC query.ts:879-883",
                    deletedTokens, cumulativeCacheDeletedTokens, edits.baselineCacheDeletedTokens());
            }
            return null;
        }
        log.info("[MicroCompactor] 流结束: cache_deleted_input_tokens delta={} > 0（累计={}, 基线={}），"
                + "yield microcompact_boundary（trigger={}, 删除工具 {} 个）· CC query.ts:884-890",
            deletedTokens, cumulativeCacheDeletedTokens, edits.baselineCacheDeletedTokens(),
            edits.trigger(), edits.deletedToolIds().size());
        return CompactBoundaryMessage.createMicrocompactBoundaryMessage(
            edits.trigger(), 0, (int) deletedTokens, edits.deletedToolIds(), List.of());
    }

    /**
     * 重置当前会话的 microcompact 状态 · 对齐 CC {@code resetMicrocompactState()}（microCompact.ts:130-135）。
     *
     * <p><b>CC 重置范围</b>: ① cachedMCState.resetCachedMCState（microCompact.ts:131-133 →
     * cachedMicrocompact.ts:58-64 清 5 字段）；② {@code pendingCacheEdits = null}（:134）。
     * 范围<b>外</b>：cached 门控配置（feature/module override 全局态）非 reset 对象
     * （CC reset 不触碰模块配置）。OPD-CM5-A-10：只复位当前会话桶（MDC 解析），不波及他会话。
     *
     * <p><b>WHY 存在</b>: time-based MC 内容清除 + 服务端缓存失效后，若 next turn cached-MC 带陈旧
     * 工具注册态运行，会尝试 cache_edit 已不存在的工具（microCompact.ts:513-517）；同时压缩后
     * （IMP-19 PostCompactCleanup 固定操作序列第一步）也需要复位。
     */
    public static void resetMicrocompactState() {
        MicroCompactSessionState ss = currentSessionState();
        if (ss.cachedMCState != null) {
            resetCachedMCState(ss.cachedMCState);
        }
        ss.pendingCacheEdits = null;
        ss.pendingCacheEditsBlock = null;
        if (log.isDebugEnabled()) {
            log.debug("[MicroCompactor] resetMicrocompactState：当前会话 cachedMCState 已复位"
                    + "（resetCachedMCState 清 5 字段）+ pendingCacheEdits/pendingCacheEditsBlock 已清空；"
                    + "门控配置不受影响");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 辅助方法
    // ════════════════════════════════════════════════════════════════════

    /**
     * 收集可压缩工具 tool_use id（按 encounter 顺序）· 对齐 CC {@code collectCompactableToolIds}
     * （microCompact.ts:226-241）。
     *
     * <p>Java 模型映射：assistant 消息的 tool_use block → {@code toolCalls()}（ToolCallDto），
     * 可压缩判定为 {@code name ∈ COMPACTABLE_TOOLS}（IMP-13 对齐 CC 9 成员）。
     */
    private List<String> collectCompactableToolIds(List<ChatMessageDto> messages) {
        List<String> ids = new ArrayList<>();
        for (ChatMessageDto message : messages) {
            if (message.role() != Role.assistant || message.toolCalls() == null) {
                continue;
            }
            for (ToolCallDto toolCall : message.toolCalls()) {
                if (toolCall != null && toolCall.name() != null
                        && TokenEstimator.COMPACTABLE_TOOLS.contains(toolCall.name())) {
                    ids.add(toolCall.id());
                }
            }
        }
        return ids;
    }

    /** 定位最后一条 assistant 消息 · 对齐 CC {@code messages.findLast(m => m.type === 'assistant')}（microCompact.ts:434）。 */
    private ChatMessageDto findLastAssistant(List<ChatMessageDto> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.assistant) {
                return messages.get(i);
            }
        }
        return null;
    }

    /**
     * main-thread 判定 · 对齐 CC {@code isMainThreadSource}（microCompact.ts:249-251）。
     *
     * <p>prefix 匹配：promptCategory.ts 将非默认 outputStyle 的 querySource 置为
     * {@code 'repl_main_thread:outputStyle:<style>'}，裸 {@code 'repl_main_thread'} 仅默认风格。
     * null 视为 main-thread（cached-MC 向后兼容，microCompact.ts:250）。
     *
     * <p><b>IMP2-01（V2-M1）</b>：判定入口经 {@link com.nexusai.application.agent.QuerySource#canonicalize}
     * 归一——生产传 {@code name()} 大写（{@code REPL_MAIN_THREAD}）亦命中（原实现仅小写
     * {@code startsWith("repl_main_thread")} → 生产恒不触发）。
     */
    static boolean isMainThreadSource(String querySource) {
        return querySource == null
            || com.nexusai.application.agent.QuerySource.canonicalize(querySource).startsWith("repl_main_thread");
    }

    /**
     * 注入 PROMPT_CACHE_BREAK_DETECTION feature 值（V2-S5 门控接线）。
     *
     * <p>默认 notifier 经 {@code gatedBy(featureFlags)} 构造：feature 关（默认，对齐 CC
     * flag 关闭）→ notifyCacheDeletion no-op；开 → 生效。生产由 LlmAgentLoop.run 注入
     * 当前 FeatureFlags（IMP2-01），测试经本方法控制。
     *
     * @param flags feature flags（null → 默认全关）
     */
    public static void setFeatureFlags(com.nexusai.application.agent.loop.FeatureFlags flags) {
        featureFlags = flags != null
            ? flags
            : com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;
        if (log.isDebugEnabled()) {
            log.debug("[MicroCompactor] setFeatureFlags: promptCacheBreakDetection={}（notifyCacheDeletion 门控）",
                featureFlags.promptCacheBreakDetection());
        }
    }

    /**
     * [token-compact-fix ①] 压缩配置 DB 实时读源静态注入（可 null，null = 未接线回落静态字段）。
     *
     * <p>同 {@link BoundaryReader#setSettingsResolver} 静态槽位先例；生产在
     * {@code ToolRegistrationConfig.microCompactor} @Bean 接线（与既有静态槽位同点）。
     *
     * @param resolver 压缩配置实时读源（可 null）
     */
    public static void setSettingsResolver(com.nexusai.application.agent.compact.CompactSettingsResolver resolver) {
        settingsResolver = resolver;
        if (log.isDebugEnabled()) {
            log.debug("[MicroCompactor] setSettingsResolver: 注入={}（cached-MC 开关 DB 实时覆盖，"
                + "null 回落静态字段）", resolver != null);
        }
    }

    /**
     * [token-compact-fix ①] feature('CACHED_MICROCOMPACT') DB-aware 实时判定 · 前端 PUT
     * settings.cached_microcompact_enabled 后下一轮即生效（不再需重启）。
     *
     * <p>DB {@code settings.cached_microcompact_enabled} 有值覆盖静态字段
     * {@link #cachedMicrocompactFeatureEnabled}（启动时 DB 初值，默认 false）；
     * null（未配置/读取失败）→ 回落静态字段。对齐 {@link BoundaryReader#isHistorySnipEnabled}
     * 的 DB-aware 回落范式（V52 X1-3 同款）。
     *
     * @return true = CACHED_MICROCOMPACT 开启（含 DB 实时覆盖）
     */
    private static boolean isCachedMicrocompactFeatureEnabled() {
        Boolean dbCachedMc = settingsResolver != null ? settingsResolver.cachedMicrocompactEnabled() : null;
        if (dbCachedMc != null) {
            return dbCachedMc;
        }
        return cachedMicrocompactFeatureEnabled;
    }

    /**
     * 注入 Telemetry（CC logEvent 等价 · 1P/Statsig 适配层 Telemetry.recordEvent）。
     * null → 遥测降级为日志（不崩）。生产由 ToolRegistrationConfig.microCompactor() 注入；
     * 测试可注入 mock 验证 emit 接线。
     *
     * @param t Telemetry 实例（null → 复位默认，emitTelemetry no-op）
     */
    public static void setTelemetry(com.nexusai.application.agent.telemetry.Telemetry t) {
        telemetry = t;
    }

    /**
     * 发射 MC 遥测事件（对齐 CC logEvent，microCompact.ts:341-356 cached / :498-505 time-based）。
     * telemetry 未注入 → no-op（遥测降级为日志，不崩）。
     *
     * @param name  事件名（如 tengu_cached_microcompact / tengu_time_based_microcompact）
     * @param attrs 事件属性（全字符串键，值非 null）
     */
    private static void emitTelemetry(String name, java.util.Map<String, Object> attrs) {
        if (telemetry != null) {
            telemetry.recordEvent(name, attrs);
        }
    }

    /** 将工具消息 content 清空为占位文本（保留其余全部字段）。 */
    private ChatMessageDto clearContent(ChatMessageDto original) {
        return new ChatMessageDto(
            original.id(), original.sessionId(), original.role(), original.author(),
            CompactConstants.TIME_BASED_MC_CLEARED_MESSAGE,
            original.reasoning(), original.toolCalls(), original.finishReason(),
            original.inputTokens(), original.outputTokens(), original.time(), original.createdAt(),
            original.toolCallId(), original.assistantMessageId(), original.acceptFeedback(),
            original.contentBlocks(), original.imagePasteIds(), original.structuredOutput(),
            original.isMeta(), original.isError(), original.subtype());
    }

    // ════════════════════════════════════════════════════════════════════
    // 配置 / 测试钩子
    // ════════════════════════════════════════════════════════════════════


    /**
     * 测试钩子：cached 门控三条件（feature / isCachedMicrocompactEnabled /
     * isModelSupportedForCacheEditing）一并开关 · 默认关闭
     * （对齐 CC microCompact.ts:276-282 四条件中的模块侧三项，置 override 强制值）。
     */
    static void setCachedMicrocompactEnabled(boolean enabled) {
        cachedMicrocompactFeatureEnabled = enabled;
        cachedMicrocompactModuleEnabledOverride = enabled;
        cachedMicrocompactModelSupportedOverride = enabled;
    }

    /** 测试钩子：单独开关 (1) feature('CACHED_MICROCOMPACT') · microCompact.ts:276。 */
    static void setCachedMicrocompactFeatureEnabledForTest(boolean enabled) {
        cachedMicrocompactFeatureEnabled = enabled;
    }

    /**
     * 生产 setter：(1) feature('CACHED_MICROCOMPACT') · [V52 B1-6/R5]。
     *
     * <p><b>WHY</b>: 默认（=CC 外部构建 DCE 恒关）false；DB {@code settings.cached_microcompact_enabled}
     * 有值则用之（经 {@link CompactSettingsResolver#cachedMicrocompactEnabled()} 实时读），null 回落 false。
     * Spring 装配（{@code ToolRegistrationConfig.microCompactor}）启动时注入 resolver 后调用。
     *
     * @param enabled DB 值（null 已由调用方回落 false；true 重新开启 cached-MC 路径）
     */
    public static void setCachedMicrocompactFeatureEnabled(boolean enabled) {
        cachedMicrocompactFeatureEnabled = enabled;
        if (log.isDebugEnabled()) {
            log.debug("[MicroCompactor] feature('CACHED_MICROCOMPACT') 生产 setter: {} (V52 B1-6/R5)",
                enabled ? "开启" : "关闭");
        }
    }

    /** 测试钩子：单独开关 (2) isCachedMicrocompactEnabled() · microCompact.ts:280（override 强制值）。 */
    static void setCachedMicrocompactModuleEnabledForTest(boolean enabled) {
        cachedMicrocompactModuleEnabledOverride = enabled;
    }

    /** 测试钩子：单独开关 (3) isModelSupportedForCacheEditing(model) · microCompact.ts:281（override 强制值）。 */
    static void setCachedMicrocompactModelSupportedForTest(boolean enabled) {
        cachedMicrocompactModelSupportedOverride = enabled;
    }

    /**
     * 注入主循环模型 · CC {@code toolUseContext?.options.mainLoopModel ?? getMainLoopModel()}
     * （microCompact.ts:278）——门控 model 谓词入参。生产由 LlmAgentLoop 注入（受控残留）；
     * null 重置为默认（regex 不匹配 → cached 门控不进入）。OPD-CM5-A-10：写入当前会话桶，
     * 会话间互不覆盖。
     *
     * @param model 主循环模型名（如 "claude-opus-4-20250514"）
     */
    public static void setMainLoopModel(String model) {
        currentSessionState().mainLoopModel = model;
    }

    /**
     * 读取主循环模型 · CC {@code toolUseContext?.options.mainLoopModel ?? getMainLoopModel()}
     * （microCompact.ts:278 / contextWindowUpgradeCheck.ts:14 getUserSpecifiedModelSetting 等价）。
     *
     * <p>[IMP-A3-4] 读取侧：CompactCommand.buildDisplayText 生产默认取当前模型设置
     * （upgradeMessage 判定入参）；null = 未注入（非主循环场景）→ 升级提示不产生。
     * OPD-CM5-A-10：读取当前会话桶（MDC 解析），与会话 A 的模型注入互不串扰。
     *
     * @return 最近一轮注入的主循环模型（可能 null）
     */
    public static String getMainLoopModel() {
        return currentSessionState().mainLoopModel;
    }

    /** 测试钩子：固定时钟（对齐 CC Date.now()），0 = 系统时钟。 */
    static void setNowForTest(long nowMs) {
        fixedNowMs = nowMs;
    }

    /** 当前时间戳（对齐 CC Date.now()）。 */
    private static long nowMs() {
        return fixedNowMs > 0 ? fixedNowMs : System.currentTimeMillis();
    }

    /** 测试缝：注入当前会话的 boundary 引用面 pendingCacheEdits（compactionInfo 形状；生产由 cachedMicrocompactPath 写入）。 */
    static void setPendingCacheEditsForTest(MicroCompactResult.PendingCacheEdits edits) {
        currentSessionState().pendingCacheEdits = edits;
    }

    /**
     * 注入 PROMPT_CACHE_BREAK_DETECTION notifyCacheDeletion 执行器 · 对齐 CC
     * {@code notifyCacheDeletion(querySource)}（microCompact.ts:366/:526）。
     *
     * <p>默认实现为 {@link PromptCacheBreakDetection#defaultInstance()}（getTrackingKey 不匹配
     * 时内部 no-op，等效 feature 关闭）；测试可注入 spy 验证接线。
     *
     * @param notifier 通知执行器（null → 复位默认）
     */
    public void setNotifyCacheDeletion(BiConsumer<String, String> notifier) {
        this.notifyCacheDeletion =
            notifier != null ? notifier : (qs, aid) -> PromptCacheBreakDetection.defaultInstance().notifyCacheDeletion(qs, aid);
    }

    /** 最近一次 time-based MC 实际节省 token（测试观察口；CC 仅 logEvent，不返回）。 */
    int lastTimeBasedTokensSaved() {
        return lastTimeBasedTokensSaved;
    }

    // ════════════════════════════════════════════════════════════════════
    // 内嵌类型
    // ════════════════════════════════════════════════════════════════════

    /**
     * cached-MC 状态机可变状态 · 对齐 CC {@code CachedMCState}（cachedMicrocompact.ts:1-7）。
     *
     * <p><b>WHY 可变类而非 record</b>: CC 在函数内就地 mutate（Set.clear/add、array push、
     * {@code toolsSentToAPI = true}，resetCachedMicrocompact.ts:54-64）；record 字段 final 无法镜像
     * 原地复位语义，故为可变静态内嵌类。由 {@link #createCachedMCState()} 新建、
     * {@link #resetCachedMCState(CachedMCState)} 清 5 字段复位（OPD-CM5-A-10：实例归属当前会话桶
     * {@link MicroCompactSessionState#cachedMCState}，会话内跨 turn 存活，跨会话隔离）。
     *
     * <ul>
     *   <li>{@code registeredTools} — CC original: registeredTools (cachedMicrocompact.ts:2) ·
     *       Set&lt;string&gt; 已注册工具 id（去重键）</li>
     *   <li>{@code toolOrder} — CC original: toolOrder (:3) · string[] 注册顺序（getToolResultsToDelete 遍历序）</li>
     *   <li>{@code deletedRefs} — CC original: deletedRefs (:4) · Set&lt;string&gt; 已删除引用
     *       （active 过滤；CC 侧从未 add，由 API 层未来维护，Java 保持同语义）</li>
     *   <li>{@code pinnedEdits} — CC original: pinnedEdits (:5) · PinnedCacheEdits[] 已钉住块
     *       （后续请求原始位置重发）</li>
     *   <li>{@code toolsSentToAPI} — CC original: toolsSentToAPI (:6) · boolean 工具是否已下发 API
     *       （成功响应后 true，reset 回 false）</li>
     * </ul>
     */
    static final class CachedMCState {

        /** CC original: registeredTools (cachedMicrocompact.ts:2)。 */
        final Set<String> registeredTools = new HashSet<>();
        /** CC original: toolOrder (cachedMicrocompact.ts:3)。 */
        final List<String> toolOrder = new ArrayList<>();
        /** CC original: deletedRefs (cachedMicrocompact.ts:4)。 */
        final Set<String> deletedRefs = new HashSet<>();
        /** CC original: pinnedEdits (cachedMicrocompact.ts:5)。 */
        final List<MicroCompactResult.PinnedCacheEdits> pinnedEdits = new ArrayList<>();
        /** CC original: toolsSentToAPI (cachedMicrocompact.ts:6)。 */
        boolean toolsSentToAPI;
    }

    /**
     * time-based MC 配置 · 对齐 CC {@code TimeBasedMCConfig}
     * （timeBasedMCConfig.ts:18-28）。
     *
     * @param enabled            CC original: enabled (timeBasedMCConfig.ts:19) · 主开关
     * @param gapThresholdMinutes CC original: gapThresholdMinutes (:23) · 距上次 assistant 超此分钟数触发
     * @param keepRecent          CC original: keepRecent (:27) · 保留最近 N 条可压缩工具结果
     */
    public record TimeBasedMCConfig(boolean enabled, int gapThresholdMinutes, int keepRecent) {

        /**
         * CC 默认配置 · CC original: TIME_BASED_MC_CONFIG_DEFAULTS
         * （timeBasedMCConfig.ts:30-34）: {@code {enabled:false, gapThresholdMinutes:60, keepRecent:5}}。
         */
        public static final TimeBasedMCConfig DEFAULTS = new TimeBasedMCConfig(false, 60, 5);

        public TimeBasedMCConfig {
            if (gapThresholdMinutes < 0) {
                throw new IllegalArgumentException("TimeBasedMCConfig.gapThresholdMinutes must be >= 0");
            }
        }
    }

    /**
     * time-based 触发结果 · 对齐 CC {@code evaluateTimeBasedTrigger} 返回值
     * {@code {gapMinutes, config}}（microCompact.ts:425）。
     *
     * @param gapMinutes CC original: gapMinutes (:438) · 距最后 assistant 的分钟数（触发时非负有限）
     * @param config     CC original: config (:426) · 触发的 time-based 配置
     */
    public record TimeBasedTriggerResult(double gapMinutes, TimeBasedMCConfig config) {

        public TimeBasedTriggerResult {
            if (config == null) {
                throw new IllegalArgumentException("TimeBasedTriggerResult.config is null");
            }
        }
    }
}
