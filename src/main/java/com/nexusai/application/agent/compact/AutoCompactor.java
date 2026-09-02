package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.tool.SDKStatus;
import com.nexusai.application.agent.tool.SpinnerMode;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 自动压缩编排器 · 对齐 CC autoCompact.ts shouldAutoCompact() + autoCompactIfNeeded()
 *
 * <h2>CC 对齐</h2>
 * <p>对齐 CC autoCompact.ts:
 * <ul>
 *   <li>{@code shouldAutoCompact(messages, model, querySource, snipTokensFreed)} — 判断是否需要压缩（autoCompact.ts:160-239）</li>
 *   <li>{@code autoCompactIfNeeded(messages, toolUseContext, cacheSafeParams, querySource, tracking, snipTokensFreed)}
 *       — 执行压缩（autoCompact.ts:241-351）</li>
 *   <li>{@code isAutoCompactEnabled()} — DISABLE_COMPACT/DISABLE_AUTO_COMPACT 早退（autoCompact.ts:147-158；
 *       [DB 主控] DB 列 disable_compact/disable_auto_compact 有值直接生效，env 仅 DB 无值时兜底）</li>
 *   <li>{@code MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES} — 熔断阈值（autoCompact.ts:70）</li>
 * </ul>
 *
 * <h2>执行流程（IMP-07 对齐 + GR-1 返工消除双轨）</h2>
 * <ol>
 *   <li>DISABLE_COMPACT 早退（autoCompact.ts:253；[DB 主控] DB disable_compact 有值生效，env 兜底）</li>
 *   <li>熔断器检查 consecutiveFailures ≥ 3（autoCompact.ts:260-265）</li>
 *   <li>shouldAutoCompact（递归守卫 querySource + DISABLE env + 阈值，autoCompact.ts:268-277）</li>
 *   <li>recompactionInfo 构建（autoCompact.ts:279-285）</li>
 *   <li>session-memory 优先判定 trySessionMemoryCompaction（autoCompact.ts:287-310）·
 *       SM 成功链 setLastSummarizedMessageId(null) + runPostCompactCleanup + [gate] notifyCompaction +
 *       markPostCompaction + <b>[IMP-CM-13] tracking.recordSuccess()</b>（query.ts:519-526 公共复位，
 *       熔断计数清零 + turnId 轮换 + turnCounter 归零——CC 对任何压缩成功统一复位，含 SM）</li>
 *   <li><b>[GR-1]</b> 直接调用 CC 对齐单函数 {@link CompactConversation#compactConversation}
 *       （suppressFollowUpQuestions=true / customInstructions=null / isAutoCompact=true /
 *       recompactionInfo，autoCompact.ts:313-321）——不再手工组装 boundary/summary，自动路径与
 *       /compact manual 共用同一单函数
 *       （附件恢复 / buildPostCompactMessages 顺序 / 错误通知 / CompactionResult 10 字段）</li>
 *   <li>legacy 成功链 setLastSummarizedMessageId(null) + runPostCompactCleanup + 成功复位 0
 *       （autoCompact.ts:325-326）</li>
 *   <li>失败计数（非 USER_ABORT 记 error 日志；计数无条件 +1，autoCompact.ts:334-349）</li>
 * </ol>
 *
 * <h2>压缩回调</h2>
 * <p>LLM 调用通过 {@link CompactCallback} 接口注入，解耦 LLM 提供者。
 * <b>[GR-1]</b> compactConversation 的摘要生产由 {@link CompactConversationContext#getSummaryProducer()}
 * 承担；auto 路径缺省时由 {@link #prepareAutoContext} 从本回调适配（生产 = {@link StreamCompactSummary}）。
 */
public class AutoCompactor {

    private static final Logger log = LoggerFactory.getLogger(AutoCompactor.class);

    /**
     * 压缩回调接口 · LLM 调用由外部注入
     *
     * <p>实现负责：用压缩提示词调用 LLM，返回含 usage 的摘要结果。
     *
     * <p><b>[IMP-CM-14 F02]</b>：返回值由 String 改为 {@link CompactConversation.SummaryResult}
     * （text + usage）——旧签名丢弃压缩 API 真实 token 用量（生产 adapter 恒
     * {@code new SummaryResult(text, null)}），使 {@code postCompactTokenCount}/
     * {@code compactionInputTokens} 等 metrics 恒 null/0。透传 usage 修复 f4/f5 metrics
     * 恒 null 根因之一（对齐 CC compact.ts:630-645 {@code compactionUsage = getTokenUsage(summaryResponse)}）。
     */
    @FunctionalInterface
    public interface CompactCallback {
        /**
         * 调用 LLM 生成压缩摘要
         *
         * @param prompt   压缩提示词（来自 {@link CompactPrompt#buildCompactPrompt()}）
         * @param messages 待压缩的消息列表（用于 LLM 上下文）
         * @return 含 usage 的摘要结果（text 含 &lt;analysis&gt; + &lt;summary&gt;；usage 非 null，可零值）
         * @throws Exception LLM 调用失败
         */
        CompactConversation.SummaryResult summarize(String prompt, List<ChatMessageDto> messages) throws Exception;
    }

    /** Token 计数器 */
    private final TokenCounter tokenCounter;

    /** 压缩回调 */
    private final CompactCallback compactCallback;

    /**
     * 阈值体系 · 对齐 CC autoCompact.ts:30-145（统一窗口来源，OD-12）。
     * 默认空 env 实例（无 override），生产经 {@link #setThresholdSystem} 注入共享 bean。
     */
    private CompactThresholdSystem thresholdSystem = new CompactThresholdSystem(null);

    /** 当前模型名（窗口 model-aware 计算用；null = 回落默认窗口）。
     * [IMP2-24 T-6] setter 已删：model 仅经 {@code autoCompactIfNeeded} 的
     * {@code ccContext.getModel()} 上下文注入（对齐 CC shouldAutoCompact(messages, model) 参数语义）。
     * [IMP-CM-06 G-2] ccContext.getModel() 由 LlmAgentLoop 传 effectiveModel（= CC mainLoopModel，
     * autoCompact.ts:267，可被 fallbackModel 改写 query.ts:922）——阈值体系吃 effectiveModel，
     * 非原始 modelName（fallback 场景模型源错位 Q-1）。 */
    private String model;

    /**
     * 压缩配置 DB 实时读源 · [V52 token-compact-fix B1-6] @Autowired(required=false)：
     * null = 无 Spring 上下文 / 未接线 → 回落 CC 原判定链（env/字段默认），零行为变化。
     */
    private CompactSettingsResolver settingsResolver;

    /** 跟踪状态 */
    private final AutoCompactTrackingState tracking;

    // ════════════════════════════════════════════════════════════════════
    // IMP-07 新增 CC 对齐字段
    // ════════════════════════════════════════════════════════════════════

    /**
     * 递归守卫 querySource · CC original: querySource（autoCompact.ts:163，QuerySource 字符串联合）。
     *
     * <p>CC 值域（query.ts:189 + 1568-1578）含 'session_memory'/'compact'/'marble_origami'。
     * Java 端以 {@link com.nexusai.application.agent.QuerySource} 枚举 name 形式传入
     * （LlmAgentLoop {@code params.querySource()}）；本字段保存当前调用来源，默认
     * 'user'（主线程）。守卫判定（INV-6）：
     * <pre>
     *   querySource === 'session_memory' || 'compact' → false（autoCompact.ts:171-173）
     *   CONTEXT_COLLAPSE 启用 && querySource === 'marble_origami' → false（autoCompact.ts:179-183）
     * </pre>
     */
    private String querySource = "user";

    /** SessionMemoryService · SM 优先路径消费方（D-11 SESSION_MEMORY 接线）。null = 无 SM 优先。 */
    private SessionMemoryService sessionMemoryService;

    /**
     * userConfig.autoCompactEnabled · CC original: getGlobalConfig().autoCompactEnabled（autoCompact.ts:156-157）。
     * DISABLE env 早退后剩余开关。默认 true。
     */
    private boolean autoCompactEnabled = true;

    /** CONTEXT_COLLAPSE feature 门控 · 守卫 marble_origami 用（autoCompact.ts:179）。默认 false。 */
    private boolean contextCollapseEnabled;

    // ════════════════════════════════════════════════════════════════════
    // GR-2 新增 CC 对齐 feature 门（shouldAutoCompact 两条抑制门，autoCompact.ts:195-223）
    // ════════════════════════════════════════════════════════════════════

    /**
     * REACTIVE_COMPACT feature 门 · CC original: feature('REACTIVE_COMPACT')（autoCompact.ts:195）。
     *
     * <p>reactive-only 模式抑制主动 autocompact：与 {@link #reactiveOnlyMode} 同时为 true 时
     * {@code shouldAutoCompact} 返回 false，让 reactive compact 承接 413 prompt-too-long
     * （autoCompact.ts:189-199）。默认 false。
     */
    private boolean reactiveCompactEnabled;

    /**
     * reactive-only 模式 growthbook 门 · CC original:
     * {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_cobalt_raccoon', false)}
     * （autoCompact.ts:196）。
     *
     * <p>GrowthBook 特性值（允许陈旧缓存）；GrowthBook 未配置 → false。仅当
     * {@link #reactiveCompactEnabled}（feature 门）与之一同为 true 时抑制主动 autocompact。
     * 默认 false（对齐 CC growthbook 缺省）。
     */
    private boolean reactiveOnlyMode;

    /**
     * context-collapse 运行时启用门 · CC original: {@code isContextCollapseEnabled()}
     * （autoCompact.ts:220）。
     *
     * <p>与 {@link #contextCollapseEnabled}（feature 门）同时为 true 时抑制主动 autocompact：
     * collapse 自身是上下文管理系统（90% commit / 95% blocking-spawn 拥有 headroom），autocompact
     * 在 effective-13k 触发会与 collapse 竞速并通常取胜，nuke 掉 collapse 即将保存的 granular
     * context（autoCompact.ts:201-223）。Java 端 {@code ContextCollapse.isContextCollapseEnabled()}
     * 当前即 featureFlags.contextCollapse()（无 env override），本字段供生产接线/测试注入。
     * 默认 false。
     */
    private boolean contextCollapseModeEnabled;

    /** env 读取器 · 可注入便于测试（默认 System::getenv）。 */
    private Function<String, String> envProvider = System::getenv;

    /** 会话 ID · SM 成功链 markPostCompaction 用（可 null → PostCompactionState 默认 key）。 */
    private String sessionId;

    /** agent ID · SM 成功链 notifyCompaction 用。 */
    private String agentId;

    /**
     * SM 成功链 runPostCompactCleanup · CC original: runPostCompactCleanup(querySource)（autoCompact.ts:297/326）。
     * 默认 {@link PostCompactCleanup#runPostCompactCleanup(String)}（IMP-19 固定序列入口），
     * 透传本压缩器的 querySource（main-thread gate · postCompactCleanup.ts:36-39）。
     */
    private Runnable runPostCompactCleanup =
        () -> PostCompactCleanup.runPostCompactCleanup(this.querySource);

    /**
     * SM 成功链 notifyCompaction · CC original: notifyCompaction(querySource ?? 'compact', agentId)
     * （autoCompact.ts:303，PROMPT_CACHE_BREAK_DETECTION feature 门控）。默认 no-op。
     */
    private BiConsumer<String, String> notifyCompaction = (qs, aid) -> {};

    /**
     * [SM-07] PROMPT_CACHE_BREAK_DETECTION 门控 · CC original: {@code feature('PROMPT_CACHE_BREAK_DETECTION')}
     * （autoCompact.ts:302-304）——SM 成功链 notifyCompaction 仅在 feature 开启时调用
     * （旧实现无条件调用，DRIFT-9）。生产由 ToolRegistrationConfig 从 FeatureFlags 接线；
     * 默认 false（对齐 CC feature 默认关）。
     */
    private volatile java.util.function.BooleanSupplier promptCacheBreakDetectionGate = () -> false;

    /**
     * [MF2-3] 会话 AgentState 注册表（invoked_skills 重注入数据源）·
     * CC STATE 读侧 Java 等价供给方。
     *
     * <p><b>注入方式</b>: @Autowired(required = false) 字段 —— 本类经
     * ToolRegistrationConfig#autoCompactor @Bean 方法返回实例，Spring 的
     * AutowiredAnnotationBeanPostProcessor 仍对其做字段注入（@Bean 返回实例照常
     * 走 BeanPostProcessor 链）。required=false 保证 registry 未定义时缺省安全 no-op。
     * 测试亦可经 {@link #setSessionAgentStateRegistry} 显式注入。
     *
     * <p><b>接线时机</b>: autoCompactIfNeeded 内、调用
     * {@link CompactConversation#compactConversation} 之前写入 CompactConversation
     * 静态 holder（见 compactConversation step 10 populateInvokedSkillsAttachment）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SessionAgentStateRegistry sessionAgentStateRegistry;

    /**
     * [IMP2-03] 任务框架服务（async-agent 附件数据源）· CC appState.tasks local_agent
     * （compact.ts:1571-1574）。由 ToolRegistrationConfig.autoCompactor @Bean 注入；
     * null（直构测试）→ populate 跳过 async-agent 附件。
     */
    private com.nexusai.application.agent.tasks.TaskFrameworkService taskFrameworkService;

    /**
     * [IMP2-03] plan 文件提供者（plan_file_reference/plan_mode 数据源）· CC getPlan/
     * getPlanFilePath（plans.ts:119-145）。生产无 bean → null → plan 附件降级不注入
     * （concern B N/A）；测试可注入假实现。
     */
    private PlanProvider planProvider;
    /**
     * [RV-E-01 GAP-03 兜底] 会话工具使用上下文 · CC original: {@code context}
     * （compact.ts:285）。auto 路径 ccContext==null 回落
     * {@link #buildDefaultCompactConversationContext()} 时，经 {@link #prepareAutoContext}
     * 把本字段接线进 ctx.toolUseContext，使 isInPlanMode() 读真实 plan mode →
     * populatePlanModeAttachment 生产可达（与 buildAutoContext 主路径对称）。
     */
    private ToolUseContext toolUseContext;

    /**
     * [MF2-3] 设置会话 AgentState 注册表（幂等）· 供测试/手动接线显式注入。
     *
     * @param sessionAgentStateRegistry 会话 AgentState 注册表（null → skill 重注入关闭）
     */
    public void setSessionAgentStateRegistry(SessionAgentStateRegistry sessionAgentStateRegistry) {
        this.sessionAgentStateRegistry = sessionAgentStateRegistry;
        log.info("[AutoCompactor] SessionAgentStateRegistry 注入状态: {}",
            sessionAgentStateRegistry != null ? "已注入" : "未注入");
    }

    /**
     * [IMP2-03] 注入任务框架服务（async-agent 附件数据源 · CC appState.tasks）。
     *
     * @param taskFrameworkService 任务框架（null → async-agent 附件跳过）
     */
    public void setTaskFrameworkService(com.nexusai.application.agent.tasks.TaskFrameworkService taskFrameworkService) {
        this.taskFrameworkService = taskFrameworkService;
    }

    /**
     * [IMP2-03] 注入 plan 文件提供者（plan_file_reference/plan_mode 数据源 · CC plans.ts）。
     *
     * @param planProvider plan 提供者（null → plan 附件降级不注入）
     */
    public void setPlanProvider(PlanProvider planProvider) {
        this.planProvider = planProvider;
    }

    /**
     * [RV-E-01 GAP-03 兜底] 设置会话工具使用上下文（幂等）· 供 tryAutoCompact（ccContext==null）
     * 回落路径把 plan mode 读侧接线进默认上下文。
     *
     * @param toolUseContext 会话工具使用上下文（null → plan mode 读侧跳过，安全降级）
     */
    public void setToolUseContext(ToolUseContext toolUseContext) {
        this.toolUseContext = toolUseContext;
        log.info("[AutoCompactor] ToolUseContext 注入状态: {}",
            toolUseContext != null ? "已注入" : "未注入");
    }

    /**
     * 构造自动压缩器
     *
     * @param tokenCounter    Token 计数器
     * @param compactCallback LLM 压缩回调
     */
    public AutoCompactor(TokenCounter tokenCounter, CompactCallback compactCallback) {
        this.tokenCounter = tokenCounter;
        this.compactCallback = compactCallback;
        this.tracking = new AutoCompactTrackingState();
    }

    /**
     * 注入阈值体系（共享 bean，含 DB model 窗口解析器）· 生产由 ToolRegistrationConfig 接线。
     */
    public void setThresholdSystem(CompactThresholdSystem thresholdSystem) {
        this.thresholdSystem = thresholdSystem != null ? thresholdSystem : new CompactThresholdSystem(null);
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-07 新增 CC 对齐 setter
    // ════════════════════════════════════════════════════════════════════

    /** 设置递归守卫 querySource（CC autoCompact.ts:163）。 */
    public void setQuerySource(String querySource) {
        this.querySource = querySource != null ? querySource : "user";
    }

    /** 注入 SessionMemoryService · SM 优先路径（D-11 SESSION_MEMORY 接线）。 */
    public void setSessionMemoryService(SessionMemoryService sessionMemoryService) {
        this.sessionMemoryService = sessionMemoryService;
    }

    /** 设置 userConfig.autoCompactEnabled（CC getGlobalConfig().autoCompactEnabled）。 */
    public void setAutoCompactEnabled(boolean autoCompactEnabled) {
        this.autoCompactEnabled = autoCompactEnabled;
    }

    /**
     * 注入压缩配置 DB 实时读源 · [V52 B1-6] @Autowired(required=false)，同
     * {@link CompactThresholdSystem#setSettingsMapper(SettingsMapper)} 回落语义（可 null）。
     *
     * @param settingsResolver 压缩配置实时读源（可 null）
     */
    public void setSettingsResolver(CompactSettingsResolver settingsResolver) {
        this.settingsResolver = settingsResolver;
    }

    /**
     * 熔断阈值 DB 实时解析 · CC original: MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES
     * （autoCompact.ts:70，默认 3）。
     *
     * <p>[V54 token-compact-fix B1-2] DB {@code settings.max_consecutive_autocompact_failures}
     * 有值（&gt; 0）覆盖常量（前端 PUT settings 后下一轮生效，对齐
     * {@link #isAutoCompactEnabled()} DB 覆盖范式），null 回落常量 3。
     * AutoCompactTrackingState 保持状态类（只存 consecutiveFailures 计数），阈值读 DB
     * 在本类判断处完成（autoCompact.ts:260-265 熔断检查同构）。
     *
     * @return 连续失败熔断阈值（DB 覆盖或常量默认）
     */
    private int resolveMaxConsecutiveAutocompactFailures() {
        Integer db = settingsResolver != null ? settingsResolver.maxConsecutiveAutocompactFailures() : null;
        return (db != null && db > 0) ? db : CompactConstants.MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES;
    }

    /** 设置 CONTEXT_COLLAPSE feature 门控（守卫 marble_origami 用）。 */
    public void setContextCollapseEnabled(boolean contextCollapseEnabled) {
        this.contextCollapseEnabled = contextCollapseEnabled;
    }

    /** 设置 REACTIVE_COMPACT feature 门 · CC feature('REACTIVE_COMPACT')（autoCompact.ts:195）。 */
    public void setReactiveCompactEnabled(boolean reactiveCompactEnabled) {
        this.reactiveCompactEnabled = reactiveCompactEnabled;
    }

    /** 设置 reactive-only 模式 growthbook 门 · CC tengu_cobalt_raccoon（autoCompact.ts:196）。 */
    public void setReactiveOnlyMode(boolean reactiveOnlyMode) {
        this.reactiveOnlyMode = reactiveOnlyMode;
    }

    /** 设置 context-collapse 运行时启用门 · CC isContextCollapseEnabled()（autoCompact.ts:220）。 */
    public void setContextCollapseModeEnabled(boolean contextCollapseModeEnabled) {
        this.contextCollapseModeEnabled = contextCollapseModeEnabled;
    }

    /**
     * CONTEXT_COLLAPSE feature 门 DB-aware 解析 · [V52 X1-3] 供 marble_origami 递归守卫
     * （:490）与抑制门（:523）复用。
     *
     * <p>DB {@code settings.context_collapse_enabled} 有值覆盖 {@link #contextCollapseEnabled}
     * （null 回落字段 = FeatureFlags.contextCollapse()），对齐 {@link #isAutoCompactEnabled()}
     * 的 DB 覆盖范式（零行为变化）。
     *
     * @return true = CONTEXT_COLLAPSE feature 门开启（含 DB 覆盖）
     */
    private boolean isContextCollapseFeatureEnabled() {
        Boolean dbCc = settingsResolver != null ? settingsResolver.contextCollapseEnabled() : null;
        if (dbCc != null) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] DB settings.context_collapse_enabled={} 覆盖 feature 门 contextCollapseEnabled={}",
                    dbCc, contextCollapseEnabled);
            }
            return dbCc;
        }
        return contextCollapseEnabled;
    }

    /** 注入 env 读取器（测试可注入 mock）。 */
    public void setEnvProvider(Function<String, String> envProvider) {
        this.envProvider = envProvider != null ? envProvider : System::getenv;
    }

    /** 设置会话 ID（SM 成功链 markPostCompaction 用）。 */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /** 设置 agent ID（SM 成功链 notifyCompaction 用）。 */
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /** 注入 SM 成功链 runPostCompactCleanup 执行器。 */
    public void setRunPostCompactCleanup(Runnable runPostCompactCleanup) {
        this.runPostCompactCleanup = runPostCompactCleanup != null
            ? runPostCompactCleanup
            : () -> PostCompactCleanup.runPostCompactCleanup(this.querySource);
    }

    /** 注入 SM 成功链 notifyCompaction 执行器。 */
    public void setNotifyCompaction(BiConsumer<String, String> notifyCompaction) {
        this.notifyCompaction = notifyCompaction != null ? notifyCompaction : (qs, aid) -> {};
    }

    /**
     * [SM-07] 注入 PROMPT_CACHE_BREAK_DETECTION 门控（CC feature('PROMPT_CACHE_BREAK_DETECTION')，
     * autoCompact.ts:302-304）。null → 默认 false（对齐 CC feature 默认关）。
     */
    public void setPromptCacheBreakDetectionGate(java.util.function.BooleanSupplier gate) {
        this.promptCacheBreakDetectionGate = gate != null ? gate : () -> false;
    }

    /**
     * 获取跟踪状态
     */
    public AutoCompactTrackingState getTracking() {
        return tracking;
    }

    /**
     * 当前自动压缩阈值 · 对齐 CC {@code autoCompact.ts:72 getAutoCompactThreshold(model)}
     * （effectiveWindow − 13_000 + env 覆盖；[IMP2-24 T-4/T-9] setter 通道已删，
     * 窗口统一经 {@link CompactThresholdSystem}（getContextWindowForModel），env 由 CompactEnvProperties 承载）。
     *
     * <p>[IMP-CM-06 G-2] model 源 = ccContext.getModel()（effectiveModel）· CC mainLoopModel
     * （autoCompact.ts:267 getAutoCompactThreshold(model)），与 blocking-limit 预检同源（query.ts:637-639）。
     */
    public int getAutoCompactThreshold() {
        return thresholdSystem.getAutoCompactThreshold(model);
    }

    /**
     * 访问阈值体系（共享 bean）· GR-3 供 blocking-limit 预检取同源窗口
     * （AutoCompactor 承载 CompactThresholdSystem，AgentLoopContext.computeBlockingLimit
     * 经此访问 {@code getBlockingLimit(model)}）。
     *
     * @return 阈值体系（恒非 null，默认空 env 实例）
     */
    public CompactThresholdSystem getThresholdSystem() {
        return thresholdSystem;
    }

    /**
     * 是否启用自动压缩 · 对齐 CC {@code isAutoCompactEnabled()}（autoCompact.ts:147-158）。
     *
     * <p><b>[DB 主控]（用户决策：DB 直接改库即生效）</b> DISABLE_COMPACT / DISABLE_AUTO_COMPACT
     * 判定优先级 = DB 列（settings.disable_compact / disable_auto_compact）&gt; env（部署级
     * 强制覆盖 fallback）&gt; 默认 false（不由此开关禁用）。DB 有值（含 false 显式放行）直接生效，
     * env 被覆盖——前端 PUT settings 后下一轮即生效（CC autoCompact.ts:147-158 无 DB 概念，
     * 纯 env；Java 扩展 DB 主控）。剩余由 {@link #autoCompactEnabled}（userConfig.autoCompactEnabled）
     * 判定，[V52 B1-6] DB settings.auto_compact_enabled 有值时覆盖之（null 回落字段默认 true）。
     *
     * @return true=启用
     */
    public boolean isAutoCompactEnabled() {
        // [DB 主控] disable_compact：DB 有值直接生效；无值回落 env DISABLE_COMPACT
        Boolean dbDisableCompact = settingsResolver != null ? settingsResolver.disableCompact() : null;
        if (isDisabledByDbOrEnv("DISABLE_COMPACT", "disable_compact", dbDisableCompact)) {
            return false;
        }
        // [DB 主控] disable_auto_compact：同上（保留手动 /compact，autoCompact.ts:152）
        Boolean dbDisableAuto = settingsResolver != null ? settingsResolver.disableAutoCompact() : null;
        if (isDisabledByDbOrEnv("DISABLE_AUTO_COMPACT", "disable_auto_compact", dbDisableAuto)) {
            return false;
        }
        Boolean dbAuto = settingsResolver != null ? settingsResolver.autoCompactEnabled() : null;
        if (dbAuto != null) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] DB settings.auto_compact_enabled={} 覆盖 userConfig.autoCompactEnabled",
                    dbAuto);
            }
            return dbAuto;
        }
        return autoCompactEnabled;
    }

    /**
     * [DB 主控] 一票否决开关判定 · DB settings 列优先，env 仅作部署级强制覆盖 fallback。
     *
     * <p>优先级（用户决策「DB 直接改库即生效」）：
     * <ol>
     *   <li>DB 列有值（非 null）→ 直接用 DB 值（true = 禁用；false = 显式放行，
     *       覆盖 env 真值）</li>
     *   <li>DB 无值 → env {@code isEnvTruthy}（部署级强制覆盖，对齐 CC autoCompact.ts:148/:152）</li>
     *   <li>再无 → false（不由此开关禁用）</li>
     * </ol>
     *
     * @param envKey  env 键（DISABLE_COMPACT / DISABLE_AUTO_COMPACT）
     * @param dbColumn DB settings 列名（仅日志用：disable_compact / disable_auto_compact）
     * @param dbValue  DB settings 列值（null = 未配置）
     * @return true = 应禁用
     */
    private boolean isDisabledByDbOrEnv(String envKey, String dbColumn, Boolean dbValue) {
        if (dbValue != null) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] DB settings.{}={} 主控一票否决（env {} 被覆盖）",
                    dbColumn, dbValue, envKey);
            }
            return dbValue;
        }
        boolean envTruthy = isEnvTruthy(envProvider.apply(envKey));
        if (envTruthy && log.isDebugEnabled()) {
            log.debug("[AutoCompactor] env {} 真值 → 一票否决（DB 未配置，回落部署级覆盖）", envKey);
        }
        return envTruthy;
    }

    /**
     * 判断是否应执行自动压缩 · 对齐 CC autoCompact.ts:160-239 shouldAutoCompact()
     *
     * <h2>判断条件（IMP-07 对齐 + GR-2 补全）</h2>
     * <ol>
     *   <li>递归守卫：querySource session_memory/compact → false（INV-6，autoCompact.ts:171-173）；
     *       CONTEXT_COLLAPSE 启用 && marble_origami → false（autoCompact.ts:179-183）</li>
     *   <li>isAutoCompactEnabled（DISABLE env + userConfig）→ false（autoCompact.ts:185-187）</li>
     *   <li>REACTIVE_COMPACT 抑制门：feature('REACTIVE_COMPACT') && tengu_cobalt_raccoon
     *       → false（autoCompact.ts:195-199，GR-2 补全）</li>
     *   <li>CONTEXT_COLLAPSE 抑制门：feature('CONTEXT_COLLAPSE') && isContextCollapseEnabled()
     *       → false（autoCompact.ts:215-223，GR-2 补全）</li>
     *   <li>tokenCount = count(messages) − snipTokensFreed；阈值比较（autoCompact.ts:225-238）</li>
     * </ol>
     *
     * <p><b>不含熔断器检查</b>——CC 熔断器在 autoCompactIfNeeded（autoCompact.ts:260-265），
     * 不在此处（旧 Java 实现放这里，属偏移）。
     *
     * @param messages         消息列表
     * @param querySource      查询来源（CC querySource；session_memory/compact/marble_origami → 守卫）
     * @param snipTokensFreed  L2 Snip 已释放的 token 数（默认 0）
     * @return true 表示需要自动压缩
     */
    public boolean shouldAutoCompact(List<ChatMessageDto> messages, String querySource, int snipTokensFreed) {
        // ── 递归守卫（INV-6，autoCompact.ts:169-183）──
        // IMP2-01（S-3）：判定入口 canonical 归一——生产传 name() 大写枚举名
        // （SESSION_MEMORY/COMPACT/MARBLE_ORIGAMI）先归一 CC 小写值域再比较；
        // 小写既有值域幂等。
        String canonical = com.nexusai.application.agent.QuerySource.canonicalize(querySource);
        if ("session_memory".equals(canonical) || "compact".equals(canonical)) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] 递归守卫: querySource={} 跳过自动压缩 (fork 死锁防护)",
                    querySource);
            }
            return false;
        }
        if (isContextCollapseFeatureEnabled() && "marble_origami".equals(canonical)) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] 递归守卫: querySource={} 跳过自动压缩 (ctx-agent, CONTEXT_COLLAPSE={})",
                    querySource, isContextCollapseFeatureEnabled());
            }
            return false;
        }

        // ── isAutoCompactEnabled（DISABLE env + userConfig，autoCompact.ts:185-187）──
        if (!isAutoCompactEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] isAutoCompactEnabled=false，跳过自动压缩");
            }
            return false;
        }

        // ── REACTIVE_COMPACT 抑制门（autoCompact.ts:195-199，GR-2 补全）──
        // reactive-only 模式：抑制主动 autocompact，让 reactive compact 承接 413
        // prompt-too-long（autoCompact.ts:189-199 注释）。feature 门 + growthbook 门同时为 true
        // 才抑制（tengu_cobalt_raccoon 默认 false，GrowthBook 未配置 → 不抑制）。
        if (reactiveCompactEnabled && reactiveOnlyMode) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] REACTIVE_COMPACT reactive-only 模式（tengu_cobalt_raccoon）"
                    + "启用，跳过自动压缩（autoCompact.ts:195-199）");
            }
            return false;
        }

        // ── CONTEXT_COLLAPSE 抑制门（autoCompact.ts:215-223，GR-2 补全）──
        // context-collapse 模式：collapse 自身是上下文管理系统（90% commit / 95% blocking-spawn
        // 拥有 headroom），autocompact 在 effective-13k 触发会与 collapse 竞速并通常取胜，
        // nuke 掉 collapse 即将保存的 granular context（autoCompact.ts:201-223 注释）。
        // feature 门（DB-aware）+ isContextCollapseEnabled() 运行时门同时为 true 才抑制。
        // [V52 X1-3] feature 门经 isContextCollapseFeatureEnabled() DB 覆盖（null 回落字段）。
        if (isContextCollapseFeatureEnabled() && contextCollapseModeEnabled) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] CONTEXT_COLLAPSE 模式启用，跳过自动压缩"
                    + "（collapse 拥有 headroom，autoCompact.ts:215-223）");
            }
            return false;
        }

        // ── 阈值比较（autoCompact.ts:225-238）──
        int tokenCount = tokenCounter.count(messages) - snipTokensFreed;

        // [F2/G-23] 四态统一来源 · 对齐 CC autoCompact.ts:233-236：shouldAutoCompact 经
        // calculateTokenWarningState(...).isAboveAutoCompactThreshold 判定（替代原阈值直连；
        // 本方法已过 isAutoCompactEnabled 门 → autoCompactEnabled=true，
        // isAboveAutoCompactThreshold = usage >= autoCompactThreshold，行为等价）。
        // percentLeft/warning/error/blocking 一并计算——CC TokenWarning.tsx 消费 warning/error
        // 作 UI 展示（Java 端数据流日志承载，供后续通知/前端接线）。
        CompactThresholdSystem.TokenWarningState warningState =
            thresholdSystem.calculateTokenWarningState(tokenCount, model, true);

        if (log.isDebugEnabled()) {
            log.debug("[AutoCompactor] tokens={} snipFreed={} model={} querySource={} 四态: percentLeft={} "
                    + "warn={} error={} auto={} blocking={} · CC autoCompact.ts:233-236/TokenWarning.tsx",
                tokenCount, snipTokensFreed, model, querySource,
                warningState.percentLeft(), warningState.isAboveWarningThreshold(),
                warningState.isAboveErrorThreshold(), warningState.isAboveAutoCompactThreshold(),
                warningState.isAtBlockingLimit());
        }

        return warningState.isAboveAutoCompactThreshold();
    }

    /**
     * 判断是否应执行自动压缩（默认 querySource 重载）· 向后兼容。
     *
     * @param messages         消息列表
     * @param snipTokensFreed  L2 Snip 已释放的 token 数
     * @return true 表示需要自动压缩
     */
    public boolean shouldAutoCompact(List<ChatMessageDto> messages, int snipTokensFreed) {
        return shouldAutoCompact(messages, this.querySource, snipTokensFreed);
    }

    /**
     * 尝试执行自动压缩 · 对齐 CC autoCompact.ts:241-351 autoCompactIfNeeded()
     *
     * <p><b>[GR-1 返工 · 消除双轨]</b> L4 压缩不再手工组装 [boundary,summary]，而是直接调用
     * CC 对齐单函数 {@link CompactConversation#compactConversation}
     * （autoCompact.ts:313 autoCompactIfNeeded 调 compactConversation）。迁移后自动路径与
     * /compact manual 共用同一单函数：附件恢复 / buildPostCompactMessages 顺序 / 错误通知
     * （auto 跳过）/ CompactionResult 10 字段。
     *
     * <p><b>snipTokensFreed 真实透传（IMP-21 / INV-9）</b>：CC query.ts:466 把 snip 释放的
     * token 数传给 {@code deps.autocompact}，autoCompact.ts:272 再传给 shouldAutoCompact，
     * 阈值比较 {@code tokenCount − snipTokensFreed}（autoCompact.ts:225）。本方法接收
     * snipTokensFreed 并转发给 {@link #shouldAutoCompact(List, String, int)}。
     *
     * <h2>执行步骤（IMP-07 对齐 + GR-1 返工）</h2>
     * <ol>
     *   <li>DISABLE_COMPACT env 早退（autoCompact.ts:253-255）</li>
     *   <li>熔断器 consecutiveFailures ≥ 3 → 跳过（autoCompact.ts:260-265，INV-5）</li>
     *   <li>shouldAutoCompact（递归守卫 + DISABLE env + 阈值 − snipTokensFreed，autoCompact.ts:268-277）</li>
     *   <li>recompactionInfo 构建（autoCompact.ts:279-285）</li>
     *   <li>SM 优先 trySessionMemoryCompaction（autoCompact.ts:287-310）：成功 →
     *       setLastSummarizedMessageId(undefined) + runPostCompactCleanup + notifyCompaction +
     *       markPostCompaction（INV-8），返回 SESSION_MEMORY 源</li>
     *   <li><b>[GR-1]</b> {@link CompactConversation#compactConversation}
     *       （suppressFollowUpQuestions=true / customInstructions=null / isAutoCompact=true /
     *       recompactionInfo，autoCompact.ts:313-321）</li>
     *   <li>legacy 成功链 setLastSummarizedMessageId(null) + runPostCompactCleanup +
     *       成功复位 0（autoCompact.ts:325-326）</li>
     *   <li>失败计数：非 USER_ABORT 记 error 日志、计数无条件 +1（autoCompact.ts:334-349，INV-5）</li>
     * </ol>
     *
     * @param messages        消息列表（post-snip/collapse 视图 · CC query.ts:454 传参）
     * @param snipTokensFreed L2 Snip 已释放的 token 数（CC query.ts:466 传参；默认 0）
     * @param querySource     查询来源（CC querySource；session_memory/compact/marble_origami → 守卫）
     * @param ccContext       compactConversation 上下文（per-session 接线，由 LlmAgentLoop 经
     *                        {@link CompactConversation#buildAutoContext} 构建；null → 默认上下文，
     *                        摘要生产回落 {@link #prepareAutoContext} 从 compactCallback 适配）
     * @return 压缩结果
     */
    public AutoCompactResult autoCompactIfNeeded(
            List<ChatMessageDto> messages, int snipTokensFreed, String querySource,
            CompactConversationContext ccContext) {
        if (messages == null || messages.isEmpty()) {
            return new AutoCompactResult(false, messages, null, 0, null, null);
        }
        this.querySource = querySource != null ? querySource : "user";
        if (ccContext != null && ccContext.getModel() != null) {
            this.model = ccContext.getModel();
        }
        // [FIX-SM] SM 压缩生产 sessionId/agentId 必须从 ccContext 取（LlmAgentLoop:2500-2501
        //   buildAutoContext 已把 params.toolUseContext() 的 sessionId/agentId 注入上下文）——
        //   此前 AutoCompactor 实例字段恒 null（生产无 setter 调用），SM 读 null 文件回落 legacy，
        //   SM 压缩生产不可达。ccContext 缺值回落实例字段（测试 setSessionId/setAgentId 依赖，
        //   AutoCompactorCcContractTest:202-203/233）。
        String effSessionId = ccContext != null && ccContext.getSessionId() != null
            ? ccContext.getSessionId() : this.sessionId;
        String effAgentId = ccContext != null && ccContext.getAgentId() != null
            ? ccContext.getAgentId() : this.agentId;

        // ── 1. DISABLE_COMPACT 早退（autoCompact.ts:253-255）──
        // [DB 主控] DB settings.disable_compact 有值直接生效（true=早退；false=放行覆盖 env）；
        // 无值回落 env DISABLE_COMPACT（部署级强制覆盖 fallback）。与 isAutoCompactEnabled 同源判定。
        if (isDisabledByDbOrEnv("DISABLE_COMPACT", "disable_compact",
                settingsResolver != null ? settingsResolver.disableCompact() : null)) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] DISABLE_COMPACT 早退，跳过自动压缩");
            }
            return new AutoCompactResult(false, messages, null, 0, null, null);
        }

        // ── 2. 熔断器 consecutiveFailures ≥ 阈值（autoCompact.ts:260-265，INV-5）──
        // [V54 token-compact-fix B1-2] 阈值 DB 实时读（settings.max_consecutive_autocompact_failures
        //   有值覆盖常量 3，null 回落；AutoCompactTrackingState 保持状态类，DB 读在本类判断处）。
        int circuitBreakerThreshold = resolveMaxConsecutiveAutocompactFailures();
        if (tracking.getConsecutiveFailures() >= circuitBreakerThreshold) {
            log.warn("[AutoCompactor] 熔断器打开: consecutiveFailures={} 阈值={}，跳过自动压缩（INV-5）",
                tracking.getConsecutiveFailures(), circuitBreakerThreshold);
            return new AutoCompactResult(false, messages, null, 0, null, null);
        }

        // ── 3. shouldAutoCompact（递归守卫 + DISABLE env + 阈值 − snipTokensFreed，autoCompact.ts:268-277）──
        // INV-9: tokenCount = count(messages) − snipTokensFreed（autoCompact.ts:225）
        if (!shouldAutoCompact(messages, querySource, snipTokensFreed)) {
            if (log.isDebugEnabled()) {
                log.debug("[AutoCompactor] 未达阈值（含 snipTokensFreed={} 减法），跳过自动压缩",
                    snipTokensFreed);
            }
            return new AutoCompactResult(false, messages, null, 0, null, null);
        }

        int originalTokens = tokenCounter.count(messages);
        int threshold = thresholdSystem.getAutoCompactThreshold(model);
        log.info("[AutoCompactor] autoCompactIfNeeded 开始: {} tokens, 阈值={}, querySource={}",
            originalTokens, threshold, querySource);

        // ── 4. SM 优先 trySessionMemoryCompaction（autoCompact.ts:287-310，REQ-12）──
        // [FIX-SM] effSessionId/effAgentId 来自 ccContext（生产非 null，见上方推导）
        CompactionResult smResult = trySessionMemoryCompaction(messages, effSessionId, effAgentId);
        if (smResult != null) {
            // SM 成功链：setLastSummarizedMessageId(undefined) + runPostCompactCleanup +
            // [gate] notifyCompaction + markPostCompaction（INV-8 · autoCompact.ts:287-310）。
            // [sm-cursor-sessionize P0-2] 只清本会话游标（旧 static volatile 语义会跨会话清空，
            // A 压缩成功 → B 的 lastSummarizedMessageId 被清 → B 的 SM 提取时机错乱）。
            SessionMemoryService.setLastSummarizedMessageId(effSessionId, null);
            runPostCompactCleanup.run();
            // [SM-07] notifyCompaction 按 PROMPT_CACHE_BREAK_DETECTION 门控（DRIFT-9）·
            //   CC autoCompact.ts:302-304 `if (feature('PROMPT_CACHE_BREAK_DETECTION'))`
            //   —— feature 关闭时不动 cache-read 基线（旧实现无条件调用）。
            if (promptCacheBreakDetectionGate.getAsBoolean()) {
                notifyCompaction.accept(
                    (querySource == null || querySource.isEmpty()) ? "compact" : querySource,
                    effAgentId);
            }
            PostCompactionState.markPostCompaction(effSessionId);
            // [IMP-CM-13] SM 成功复位 tracking · CC 对齐 query.ts:519-526 公共复位
            //   （consecutiveFailures: 0）——autoCompact.ts SM 分支自身不返回 consecutiveFailures，
            //   但调用方 query.ts:470 `if (compactionResult)` 对任何压缩成功（SM/legacy 同分支）
            //   执行公共复位 {compacted:true, turnId:uuid(), turnCounter:0, consecutiveFailures:0}，
            //   故 SM 成功同样复位熔断计数 + 轮换 turnId + 归零 turnCounter（旧注释 DRIFT-10 误读，
            //   仅看 autoCompact.ts 未见 query.ts 公共复位，属偏离 CC）。recordSuccess 复位后
            //   tengu_post_autocompact_turn 在 SM 成功后正常发射（LlmAgentLoop:4700-4712 以
            //   tracking.isCompacted() 为门，CC query.ts:1523-1533 同构）。
            tracking.recordSuccess();
            log.info("[AutoCompactor] SM 优先压缩成功: preTokens={} postTokens={} · source=SESSION_MEMORY",
                smResult.preCompactTokenCount(), smResult.postCompactTokenCount());
            return new AutoCompactResult(
                true,
                CompactionResult.buildPostCompactMessages(smResult),
                "SESSION_MEMORY",
                Math.max(0, smResult.preCompactTokenCount() - smResult.postCompactTokenCount()),
                smResult,
                // [IMP-A4-3] SM 成功不携带 consecutiveFailures（CC :306-309 SM 分支无该字段）
                null);
        }

        try {
            // ── 5. [GR-1] CC 单函数 compactConversation（autoCompact.ts:313-321，消除双轨）──
            CompactConversationContext ctx = ccContext != null ? ccContext : buildDefaultCompactConversationContext();
            prepareAutoContext(ctx);
            // [IMP2-03] auto 路径附件生产接线（✗-1..✗-4，INV-15）：async-agent/plan/plan_mode
            // 经 populatePostCompactAttachments 填充 ctx（数据源 taskFrameworkService/planProvider
            // + ctx.toolUseContext，由 LlmAgentLoop buildAutoContext 后 setToolUseContext 注入）；
            // 3×delta 在 compactConversation → restore() 尾部重宣布（CC compact.ts:545-585）。
            PostCompactAttachmentRestorer.populatePostCompactAttachments(ctx, taskFrameworkService, planProvider);
            CompactConversation.RecompactionInfo recompactionInfo = new CompactConversation.RecompactionInfo(
                tracking.isCompacted(),           // autoCompact.ts:280 isRecompactionInChain
                tracking.getTurnCounter(),        // autoCompact.ts:281 turnsSincePreviousCompact
                tracking.getTurnId(),             // autoCompact.ts:282 previousCompactTurnId
                getAutoCompactThreshold(),        // autoCompact.ts:283 autoCompactThreshold
                querySource);                     // autoCompact.ts:284 querySource

            // [MF2-3] auto 路径 registry 供给：把会话 AgentState 注册表写入 compactConversation
            // 静态 holder，保证压缩成功路径 step 10 能经 sessionId 解析主 AgentState 重注入
            // invoked_skills 附件（CC compact.ts:558 createSkillAttachmentIfNeeded 读全局 STATE）。
            if (sessionAgentStateRegistry != null) {
                CompactConversation.setSessionAgentStateRegistry(sessionAgentStateRegistry);
                if (log.isDebugEnabled()) {
                    log.debug("[AutoCompactor] 已把 SessionAgentStateRegistry 注入"
                        + " CompactConversation holder（auto 路径 skill 附件接线）");
                }
            }

            CompactionResult result = CompactConversation.compactConversation(
                messages, ctx, true, null, true, recompactionInfo);

            // ── 6. legacy 成功链（autoCompact.ts:325-326，GR-2 补全）──
            // [sm-cursor-sessionize P0-2] 只清本会话游标（旧 static volatile 语义跨会话清空）
            SessionMemoryService.setLastSummarizedMessageId(effSessionId, null);
            runPostCompactCleanup.run();
            // [IMP2-07] recordSuccess 内轮换 turnId + 归零 turnCounter + 复位熔断
            //   （CC query.ts:521-526 tracking 全量复位；DRIFT-4/S-6）
            tracking.recordSuccess();

            int tokensFreed = Math.max(0,
                result.preCompactTokenCount() - result.truePostCompactTokenCount());
            log.info("[AutoCompactor] autoCompactIfNeeded 完成: pre={} truePost={} freed={} · CC autoCompact.ts:313-333",
                result.preCompactTokenCount(), result.truePostCompactTokenCount(), tokensFreed);
            return new AutoCompactResult(
                true,
                CompactionResult.buildPostCompactMessages(result),
                "AUTO",
                tokensFreed,
                result,
                // [IMP-A4-3] legacy 成功返回 consecutiveFailures: 0（CC autoCompact.ts:332）
                //   ——调用方写回 0，与 recordSuccess 内部复位一致（幂等）。
                0);
        } catch (Exception e) {
            // ── 7. 失败计数（autoCompact.ts:334-349，INV-5）──
            // ⚠️ CC 实际源码（grep 自验 2026-08-04）：catch 内 consecutiveFailures 无条件 +1
            // （autoCompact.ts:341-342 nextFailures = prevFailures + 1）；hasExactErrorMessage
            // 仅门控 logError（:335-337），**不**门控计数。故 USER_ABORT 也计入熔断失败数。
            if (!isUserAbort(e)) {
                log.error("[AutoCompactor] autoCompactIfNeeded 压缩失败: {}", e.getMessage());
            } else {
                log.info("[AutoCompactor] 压缩被用户中止（USER_ABORT），跳过 error 日志（CC :335-337）");
            }
            // [IMP-A4-3 · OPD-CM5-A-31] 失败传播返回值通道：不再内部直写共享 tracking
            // （旧 tracking.recordFailure()），改为计算 nextFailures 放进返回对象，由调用方
            // （LlmAgentLoop / tryAutoCompact）写回 tracking（CC autoCompact.ts:341-349 返回
            // {wasCompacted:false, consecutiveFailures} + query.ts:536-542 调用方写回）——
            // 熔断计数经返回值承载，可跨 AutoCompactor 实例传递。
            int prevFailures = tracking.getConsecutiveFailures();
            int nextFailures = prevFailures + 1;
            // [V54 token-compact-fix B1-2] 熔断阈值 DB 实时读（同步骤 2，null 回落常量 3）
            if (nextFailures >= resolveMaxConsecutiveAutocompactFailures()) {
                log.warn("[AutoCompactor] 熔断器触发: 连续 {} 次失败，本会话跳过后续自动压缩尝试"
                    + "（CC autoCompact.ts:343-348）", nextFailures);
            }
            return new AutoCompactResult(false, messages, null, 0, null, nextFailures);
        }
    }

    /**
     * 尝试执行自动压缩（无 snip 透传的便捷重载）· 内部委托 {@link #tryAutoCompact(List, int)}
     * → {@link #autoCompactIfNeeded(List, int, String, CompactConversationContext)}。
     *
     * <p><b>不变量</b>：snipTokensFreed=0 时与 CC autoCompactIfNeeded 无 snip 场景等价
     * （INV-9 减法退化为 tokenCount − 0）。调用方若在 snip 后进入 autocompact，应使用
     * {@link #tryAutoCompact(List, int)} 透传真实 snipTokensFreed（CC query.ts:466）。
     *
     * @param messages 消息列表
     * @return 压缩结果
     */
    public AutoCompactResult tryAutoCompact(List<ChatMessageDto> messages) {
        return tryAutoCompact(messages, 0);
    }

    /**
     * 尝试执行自动压缩（带 snip 透传）· <b>[GR-1 返工]</b> 委托给
     * {@link #autoCompactIfNeeded(List, int, String, CompactConversationContext)}，
     * L4 压缩走 CC 对齐单函数 {@link CompactConversation#compactConversation}
     * （autoCompact.ts:313，消除手工 [boundary,summary] 组装双轨）。
     *
     * <p>无 per-session 上下文 → 默认上下文，摘要生产回落 {@link #prepareAutoContext}
     * 从 compactCallback 适配。
     *
     * @param messages        消息列表
     * @param snipTokensFreed L2 Snip 已释放的 token 数（CC query.ts:466 传参；默认 0）
     * @return 压缩结果
     */
    public AutoCompactResult tryAutoCompact(List<ChatMessageDto> messages, int snipTokensFreed) {
        AutoCompactResult result = autoCompactIfNeeded(messages, snipTokensFreed, this.querySource, null);
        // [IMP-A4-3 · OPD-CM5-A-31] Java 便捷重载承担 CC 调用方写回职责（query.ts:536-542）：
        //   autoCompactIfNeeded 失败路径经返回值承载 nextFailures，由本方法（作为该路径的调用方）
        //   写回 tracking——保证便捷路径（测试/手动接线）熔断计数持续累计，与生产 LlmAgentLoop
        //   写回行为一致。成功复位已由 recordSuccess 内部完成（query.ts:521-526 公共复位等价）。
        if (result.consecutiveFailures() != null) {
            tracking.setConsecutiveFailures(result.consecutiveFailures());
        }
        return result;
    }

    /**
     * SM 优先判定 · 对齐 CC {@code trySessionMemoryCompaction}
     * （sessionMemoryCompact.ts:514，autoCompact.ts:287-292）。
     *
     * <p>sessionMemoryService 未注入 → null（无 SM 路径，回落全量压缩）。
     * SM 压缩产生非空结果时返回，供调用方走 SM 成功链（INV-8）。
     *
     * @param messages  待压缩消息
     * @param sessionId 会话 ID（FIX-SM：生产来自 ccContext，避免 SM 读 null 文件回落 legacy）
     * @param agentId   agent ID（SM 成功链 notifyCompaction 审计）
     * @return SM 压缩结果；不可用 → null
     */
    private CompactionResult trySessionMemoryCompaction(
            List<ChatMessageDto> messages, String sessionId, String agentId) {
        if (sessionMemoryService == null) {
            return null;
        }
        // E02（OPD-CM3-27）删除外层 try/catch（双重 catch 冗余）：CC sessionMemoryCompact.ts:545/:621
        // 单层 catch 在 SessionMemoryService.trySessionMemoryCompaction 内部兜底（期望内错误 → null，
        // 回落全量压缩）；CC autoCompactIfNeeded（autoCompact.ts:287-292）对 SM 调用亦无外层包裹。
        // 仅保留 null-guard：sessionMemoryService 未注入 → null。前置读取 getSessionMemoryContent
        // 非五类 fs-inaccessible 错误按 CC 语义上抛（sessionMemoryUtils.ts:124-125 显式失败，不吞错）。
        return sessionMemoryService.trySessionMemoryCompaction(
            messages, sessionId, agentId, getAutoCompactThreshold());
    }

    /**
     * [GR-1] 构建默认 compactConversation 上下文 · 无 per-session 接线时的回落。
     *
     * <p>事件/通知保持 no-op（对齐 D-04：AutoCompactor 不自行 emit 进度事件；per-session
     * 事件/流/SDK 桥接由 LlmAgentLoop 构建的上下文经 {@link CompactConversation#buildAutoContext}
     * 注入）。摘要生产由 {@link #prepareAutoContext} 从 {@link CompactCallback} 适配
     * （CC streamCompactSummary，compact.ts:451）。
     *
     * <p><b>[IMP-CM-12]</b> f4 全量路径 notifyCompaction 不再 no-op —— 经
     * {@link #wireAutoNotifyCompaction} 按 PROMPT_CACHE_BREAK_DETECTION 门控真实接线
     * （CC compact.ts:698-699；门控关闭 → no-op 等价）。
     */
    CompactConversationContext buildDefaultCompactConversationContext() {
        CompactConversationContext ctx = new CompactConversationContext()
            .setSessionId(this.sessionId)
            .setAgentId(this.agentId)
            .setModel(this.model)
            .setQuerySource(this.querySource)
            .setReadFileState(new LinkedHashMap<>());
        wireAutoNotifyCompaction(ctx);
        // [RV-E-01 GAP-03 兜底] ccContext==null 回落路径接线 plan mode 读侧（对齐 CC compact.ts:285
        //   context 持有 toolUseContext），使 isInPlanMode() 读真实 plan mode → populatePlanModeAttachment
        //   生产可达（此前回落路径 toolUseContext 恒 null → isInPlanMode 恒 false）。
        if (this.toolUseContext != null) {
            ctx.setToolUseContext(this.toolUseContext);
        }
        return ctx;
    }

    /**
     * [GR-1] 补齐 compactConversation 上下文的摘要生产 · 对齐 CC compact.ts:451
     * streamCompactSummary。compactCallback 为 AutoCompactor 的 LLM 摘要回调
     * （生产 = {@link StreamCompactSummary}，IMP-01 产物）；上下文 summaryProducer
     * 已显式注入时跳过（per-session 显式接线优先）。
     */
    private void prepareAutoContext(CompactConversationContext ctx) {
        if (ctx == null) {
            return;
        }
        if (ctx.getSummaryProducer() == null && compactCallback != null) {
            ctx.setSummaryProducer((messagesToSummarize, compactPrompt, preCompactTokenCount) -> {
                try {
                    // [IMP-CM-14 F02] 直接透传回调返回的 SummaryResult（text + usage）——
                    //   旧实现丢弃 usage 改包 new SummaryResult(text, null) 是 f4/f5 metrics 恒 null 根因
                    return compactCallback.summarize(compactPrompt, messagesToSummarize);
                } catch (Exception e) {
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
            });
        }
        // [RV-E-01 GAP-03 兜底] ccContext==null（tryAutoCompact 回落）时，若 ctx 未显式接线
        //   toolUseContext，用本压缩器注入的 toolUseContext 兜底（对齐 CC compact.ts:285 context
        //   持有 toolUseContext）。buildAutoContext 主路径已显式接线时此处 no-op（幂等）。
        if (ctx.getToolUseContext() == null && this.toolUseContext != null) {
            ctx.setToolUseContext(this.toolUseContext);
        }
        // [IMP-CM-12] f4 全量路径 notifyCompaction 接线（CC compact.ts:698-699 feature 门控）·
        //   buildAutoContext（生产 per-session ctx，CompactConversation.java:423）不接线
        //   notifyCompaction → 生产全量路径恒 no-op（全局报告 §5 #1）。auto 路径统一在此接线
        //   （门控关闭 → no-op 等价；buildDefault 已接线时幂等覆盖，语义一致）。
        wireAutoNotifyCompaction(ctx);
    }

    /**
     * [IMP-CM-12] 补齐 compactConversation 上下文的 notifyCompaction（f4 全量路径）·
     * CC compact.ts:698-699 {@code if (feature('PROMPT_CACHE_BREAK_DETECTION')) {
     * notifyCompaction(context.options.querySource ?? 'compact', context.agentId) }}。
     *
     * <p><b>WHY（OPD-CM3-04/A02 · 全局报告 §5 #1/#2）</b>: 全量路径 ctx 默认 no-op
     * （CompactConversationContext:91），压缩后 cache-read 基线不复位 → 下轮 LLM turn 消息数
     * 下降被误报为 cache break（或命中陈旧指令/记忆）。接线后 feature 关（默认）→ 本方法先按
     * {@link #promptCacheBreakDetectionGate} 门控短路（与 SM 成功链 :611-612 同门，读同一
     * FeatureFlags.promptCacheBreakDetection()）→ no-op 等价；feature 开 → 调用
     * {@link #notifyCompaction}（ToolRegistrationConfig 单点注入 gatedBy(featureFlags)，
     * 真实重置 prevCacheReadTokens，promptCacheBreakDetection.ts:689-698）。
     *
     * @param ctx 目标压缩上下文（querySource/agentId 取自 ctx —— buildAutoContext 从 TUC 注入；
     *            null → no-op）
     */
    private void wireAutoNotifyCompaction(CompactConversationContext ctx) {
        if (ctx == null) {
            return;
        }
        String qs = ctx.getQuerySource() != null && !ctx.getQuerySource().isEmpty()
            ? ctx.getQuerySource() : "compact";
        String aid = ctx.getAgentId();
        ctx.setNotifyCompaction(() -> {
            if (promptCacheBreakDetectionGate.getAsBoolean()) {
                notifyCompaction.accept(qs, aid);
            }
        });
    }

    /** 是否 USER_ABORT 错误 · 对齐 CC hasExactErrorMessage（autoCompact.ts:335）。 */
    private static boolean isUserAbort(Exception e) {
        String msg = e != null && e.getMessage() != null ? e.getMessage() : "";
        return CompactConstants.ERROR_MESSAGE_USER_ABORT.equals(msg);
    }

    /** CC isEnvTruthy（envUtils.ts:32-37）· 真值集 {'1','true','yes','on'}（大小写不敏感+trim，IMP2-25 M-2 补 'on'）。 */
    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String s = value.trim().toLowerCase();
        return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    /**
     * 重置压缩状态（新会话）
     */
    public void reset() {
        tracking.reset();
    }

    /**
     * 自动压缩结果
     */
    public record AutoCompactResult(
        boolean wasCompacted,
        List<ChatMessageDto> messages,
        String source,
        int tokensFreed,
        // [IMP-CM-17] tengu_auto_compact_succeeded 遥测原料（CC query.ts:478-502 logEvent）：
        //   压缩成功时的 CompactionResult（SM 路径 smResult / legacy 路径 result）。
        //   未压缩（wasCompacted=false）→ null。字段可空 → 调用方（LlmAgentLoop）读侧 null 安全。
        CompactionResult compactionResult,
        // [IMP-A4-3 · OPD-CM5-A-31] 连续失败计数 · CC original: consecutiveFailures?
        //   （autoCompact.ts:248-252 autoCompactIfNeeded 返回类型）。
        //   失败传播返回值通道：失败路径返回 nextFailures（autoCompact.ts:341-349），由调用方
        //   写回 tracking（query.ts:536-542）——熔断计数经返回值承载，可跨 AutoCompactor 实例
        //   传递。语义：
        //   <ul>
        //     <li>失败 → nextFailures（autoCompact.ts:349，prevFailures+1）</li>
        //     <li>legacy 成功 → 0（autoCompact.ts:332 consecutiveFailures: 0）</li>
        //     <li>SM 成功 → null（CC :306-309 SM 分支自身不返回该字段）</li>
        //     <li>未压缩（早退/熔断/未达阈值）→ null（CC :254/:264/:276 仅 {wasCompacted:false}）</li>
        //   </ul>
        //   可空 Integer → 调用方读侧 null 安全（未携带 → 不写回 tracking）。
        Integer consecutiveFailures) {

        public AutoCompactResult {
            if (messages == null) {
                throw new IllegalArgumentException("AutoCompactResult.messages is null");
            }
        }
    }
}
