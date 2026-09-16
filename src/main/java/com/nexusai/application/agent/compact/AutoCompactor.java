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
         * @param ctx      压缩上下文（[批 5a] 显式载体：CC {@code compactConversation(…, context,
         *                 cacheSafeParams, …)} 的 context/cacheSafeParams 对应物 compact.ts:414）
         *                 —— abortController / fork 缓存共享参数 / 进度 sink 经此读取
         * @return 含 usage 的摘要结果（text 含 &lt;analysis&gt; + &lt;summary&gt;；usage 非 null，可零值）
         * @throws Exception LLM 调用失败
         */
        CompactConversation.SummaryResult summarize(String prompt, List<ChatMessageDto> messages,
                                                    CompactConversationContext ctx) throws Exception;
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

    // [P2-7 · 2026-09-11] 原 private String model 实例字段已删除（跨会话串台）。
    //   WHY: 该字段由 autoCompactIfNeeded 每次调用覆写，但 ccContext.getModel()==null 时【不覆写】
    //   → 保留上一会话的 model。AutoCompactor 是 Spring 单例 bean，多会话并发共享 → A 会话用 B 的
    //   窗口算阈值（200k vs 1M 差 5 倍）：该压的不压（撞 413）或过早压。
    //   CC 真源 autoCompact.ts:267 `const model = toolUseContext.options.mainLoopModel` —— 调用内
    //   局部变量，从不落实例状态（同模式前科：autoCompactTracking 曾串台，见 defaultTracking javadoc）。
    //   现 model 为 autoCompactIfNeeded 的调用内局部变量，逐参传给 shouldAutoCompact /
    //   getAutoCompactThreshold / calculateTokenWarningState / trySessionMemoryCompaction /
    //   buildDefaultCompactConversationContext（对齐 CC 各函数 (…, model) 显式参数）。

    /**
     * 压缩配置 DB 实时读源 · [V52 token-compact-fix B1-6] @Autowired(required=false)：
     * null = 无 Spring 上下文 / 未接线 → 回落 CC 原判定链（env/字段默认），零行为变化。
     */
    private CompactSettingsResolver settingsResolver;

    /**
     * 便捷路径跟踪状态 · <b>生产路径不消费</b>（测试 / 手动接线的 {@code tryAutoCompact} 重载专用）。
     *
     * <p><b>[P4-4 tracking 生命周期对齐 CC] WHY（历史事故根因）</b>：本字段原为<b>唯一</b>跟踪状态源，
     * 而 AutoCompactor 是 Spring 单例 bean（跨会话共享 JVM 单实例）——多会话并发时 A 会话的
     * {@code compacted/turnCounter/turnId/consecutiveFailures} 会被 B 会话的压缩/失败覆盖：
     * <ul>
     *   <li>B 会话可继承 A 会话的<b>已熔断</b>计数 → B 的自动压缩被无故短路（上下文不再被压，
     *       最终撞 blocking-limit / 413）；</li>
     *   <li>A 的 runtime reset（run 入口）会把 B 正在累计的熔断计数清零 → 熔断器形同虚设；</li>
     *   <li>{@code compacted=true} 跨会话泄漏 → {@code isRecompactionInChain} / 回合计数失真。</li>
     * </ul>
     * CC 的 {@code autoCompactTracking} 是 {@code query()} 调用的<b>局部 State 字段</b>
     * （query.ts:264 声明 / :425 初始化为 undefined），随 query() 调用创建、随调用结束丢弃——
     * 既不跨会话也不跨 query。
     *
     * <p><b>本仓对齐</b>：生产路径由调用方（{@code LlmAgentLoop.queryLoop} = CC query() 等价入口）
     * 每 query() 创建一个 {@link AutoCompactTrackingState}，经
     * {@link #autoCompactIfNeeded(List, int, String, CompactConversationContext, AutoCompactTrackingState)}
     * 第 5 参显式传入（对齐 CC {@code deps.autocompact(..., tracking, ...)} 第 5 参）。本字段仅供
     * 无 per-session 上下文的便捷重载（测试 / 手动接线）持有，<b>不是</b>生产状态源。
     */
    private final AutoCompactTrackingState defaultTracking;

    // ════════════════════════════════════════════════════════════════════
    // IMP-07 新增 CC 对齐字段
    // ════════════════════════════════════════════════════════════════════

    /**
     * 递归守卫 querySource（<b>便捷路径</b>来源）· CC original: querySource（autoCompact.ts:163，
     * QuerySource 字符串联合）。
     *
     * <p>CC 值域（query.ts:189 + 1568-1578）含 'session_memory'/'compact'/'marble_origami'。
     * Java 端以 {@link com.nexusai.application.agent.QuerySource} 枚举 name 形式传入
     * （LlmAgentLoop {@code params.querySource()}）。守卫判定（INV-6）：
     * <pre>
     *   querySource ∈ {session_memory, compact} → false（autoCompact.ts:171-173）
     *   CONTEXT_COLLAPSE 启用 && querySource === 'marble_origami' → false（autoCompact.ts:179-183）
     * </pre>
     *
     * <p><b>[E-1a fork 屏蔽档]</b>：第一条豁免值域扩到
     * {@link com.nexusai.application.agent.QuerySource#isBackgroundForkSource}
     * （+ extract_memories / auto_dream）—— 后两者今天不走主循环（E-1b 才收敛），现网行为零变化。
     *
     * <p><b>[S1 轨 III · T9 2026-09-14] 原 {@code private String querySource = "user"} 字段与
     * {@code setQuerySource} 已删除</b>：生产 0 写入方（grep 全仓：仅测试文案命中；同文件
     * {@code autoCompactIfNeeded} 的 querySource 早已是调用内局部变量 {@code effQuerySource}），
     * 唯一读取点是便捷重载 {@link #tryAutoCompact(List, int)} 的回落实参 ⇒ 改传字面量 "user"
     * （与字段默认值逐字等价，零行为变化）。CC 侧 querySource 是<b>函数形参</b>、无 per-instance
     * 状态 ⇒ 无「保留对应物」可留。
     */
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

    // [S1 轨 III · T9 2026-09-14] 原 `private String sessionId` / `private String agentId` 已删除：
    //   生产 0 写入方（grep 全仓：AutoCompactor.setSessionId/setAgentId 无生产调用点，仅测试调），
    //   唯一读取点是 buildDefaultCompactConversationContext 的回落分支 ⇒ 两字段生产恒 null
    //   （该分支代码自陈「SM 压缩生产不可达」）。删除后回落分支不再填这两项 ⇒ 与删除前**逐字等价**
    //   （删除前填的也是 null）。生产 sessionId/agentId 由 LlmAgentLoop 的 ccContext 显式携带。

    /**
     * SM 成功链 runPostCompactCleanup 执行器 · CC original: runPostCompactCleanup(querySource)
     * （claude-code-best/src/services/compact/autoCompact.ts:326/:355；Open-ClaudeCode 同文件 :297/:326）。
     *
     * <p><b>[P1a F-08 · 单例可变会话字段修复]</b> 类型 {@code BiConsumer<String,String>} =
     * {@code (querySource, sessionId)} —— 两参由<b>调用点显式传入</b>（对齐 CC 的「函数 + 实参」
     * 形态：CC 的 session 维度不存在，Java 端第 2 参是本仓 per-session section 缓存所需的会话标识）。
     *
     * <p><b>旧实现为何错</b>：原为 {@code Runnable}，默认实现是捕获 {@code this} 的 lambda
     * （{@code () -> PostCompactCleanup.runPostCompactCleanup(this.querySource, this.sessionId)}），
     * 而 {@code sessionId} 字段在<b>生产零写入点</b>（无 setter 调用）⇒ 恒传 null ⇒
     * {@link PostCompactCleanup#clearActiveSessionSystemPromptSections(String)} 的「调用方未传会话标识」
     * WARN 分支恒命中 ⇒ 第 4 项 clearSystemPromptSections 与第 1 项 resetMicrocompactState 的
     * <b>per-session 效果双失</b>（会话 section 缓存永不失效 + 本会话 microcompact 桶永不复位）。
     * 默认值改为<b>方法引用</b>（不含 {@code this.*}），配合调用点显式传参 ⇒ 结构上不可能再读到
     * 单例字段。CC 侧无此维度（{@code clearSystemPromptSections()} 无参、数据源是进程级 STATE，
     * claude-code-best/src/constants/systemPromptSections.ts:1-6 import 自 bootstrap/state.js）。
     */
    private BiConsumer<String, String> postCompactCleanup =
        PostCompactCleanup::runPostCompactCleanup;

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
    // [S1 轨 III · T9 2026-09-14] 原 `private ToolUseContext toolUseContext` 与 setToolUseContext
    //   已删除：生产 0 写入方（唯一调用点是测试 PlanModeCompactContextWiringTest）。
    //   生产 plan mode 读侧的**唯一**来源是主路径
    //   （{@code ccCtx.setToolUseContext(params.toolUseContext())}，LlmAgentLoop:5723 —— 该调用写的是
    //   CompactConversationContext，**不是**本类字段；本类字段此前被误当成它的接线点，
    //   是「注释与代码不一致」的又一例）。删除后回落分支不再有 plan mode 读侧（原本也恒 null）。

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
     * 构造自动压缩器
     *
     * @param tokenCounter    Token 计数器
     * @param compactCallback LLM 压缩回调
     */
    public AutoCompactor(TokenCounter tokenCounter, CompactCallback compactCallback) {
        this.tokenCounter = tokenCounter;
        this.compactCallback = compactCallback;
        this.defaultTracking = new AutoCompactTrackingState();
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

    /**
     * 注入 SM 成功链 runPostCompactCleanup 执行器（显式两参：querySource + 会话标识）。
     *
     * <p>[P1a F-08] 旧签名为 {@code Runnable}，且 null 回落分支把「读 this.* 的闭包」原地重建
     * （等于把缺陷重造一遍）；现为 {@code BiConsumer<String,String>}，null → 回落方法引用
     * {@link PostCompactCleanup#runPostCompactCleanup(String, String)}（无 {@code this.*}）。
     *
     * @param postCompactCleanup 执行器（null → 回落 {@code PostCompactCleanup::runPostCompactCleanup}）
     */
    public void setPostCompactCleanup(BiConsumer<String, String> postCompactCleanup) {
        this.postCompactCleanup = postCompactCleanup != null
            ? postCompactCleanup
            : PostCompactCleanup::runPostCompactCleanup;
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
     * 获取<b>便捷路径</b>跟踪状态（测试 / 手动接线）· 生产路径不消费。
     *
     * <p>[P4-4] 生产路径的跟踪状态由 {@code LlmAgentLoop.queryLoop} 每 query() 新建并经
     * {@link #autoCompactIfNeeded(List, int, String, CompactConversationContext, AutoCompactTrackingState)}
     * 第 5 参显式传入（对齐 CC query() 局部 State.autoCompactTracking，query.ts:264/:425）——
     * 本方法暴露的字段跨会话共享，<b>绝不可</b>作为多会话生产状态载体（见 {@link #defaultTracking}）。
     */
    public AutoCompactTrackingState getTracking() {
        return defaultTracking;
    }

    /**
     * 自动压缩阈值 · 对齐 CC {@code autoCompact.ts:101-120 getAutoCompactThreshold(model)}
     * （effectiveWindow − 13_000 + env 覆盖；[IMP2-24 T-4/T-9] setter 通道已删，
     * 窗口统一经 {@link CompactThresholdSystem}（getContextWindowForModel），env 由 CompactEnvProperties 承载）。
     *
     * <p>[IMP-CM-06 G-2] model 源 = ccContext.getModel()（effectiveModel）· CC mainLoopModel
     * （autoCompact.ts:267 getAutoCompactThreshold(model)），与 blocking-limit 预检同源（query.ts:637-639）。
     *
     * <p><b>[P2-7 · 2026-09-11] model 改为显式入参</b>：原无参版本读 AutoCompactor.model 实例字段
     * （跨会话串台，见字段处注释）。对齐 CC 的 {@code getAutoCompactThreshold(model)} 单参签名。
     *
     * @param model 有效模型名（null/空 → 回落默认窗口 · CC context.ts:9 MODEL_CONTEXT_WINDOW_DEFAULT）
     * @return 自动压缩阈值（token）
     */
    public int getAutoCompactThreshold(String model) {
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
     * <p><b>[P2-7 · 2026-09-11] model 改为显式入参</b>（CC 签名 {@code shouldAutoCompact(messages, model,
     * querySource?, snipTokensFreed = 0)}，autoCompact.ts:189-197）。原实现读 AutoCompactor.model
     * 实例字段 → 单例 bean 多会话串台（见字段处注释）；CC 的 model 是 query() 调用的调用内局部变量
     * （autoCompact.ts:267）。
     *
     * @param messages         消息列表
     * @param model            有效模型名（窗口 model-aware 计算用；null → 回落默认窗口）
     * @param querySource      查询来源（CC querySource；session_memory/compact/marble_origami → 守卫）
     * @param snipTokensFreed  L2 Snip 已释放的 token 数（默认 0）
     * @return true 表示需要自动压缩
     */
    public boolean shouldAutoCompact(List<ChatMessageDto> messages, String model, String querySource,
                                     int snipTokensFreed) {
        // ── 递归守卫（INV-6，autoCompact.ts:169-183）──
        // IMP2-01（S-3）：判定入口 canonical 归一——生产传 name() 大写枚举名
        // （SESSION_MEMORY/COMPACT/MARBLE_ORIGAMI）先归一 CC 小写值域再比较；
        // 小写既有值域幂等。
        // [E-1a fork 屏蔽档] 豁免值域改由单点判据 {@link QuerySource#isBackgroundForkSource}
        //   承担（compact / session_memory / extract_memories / auto_dream）—— 前两者与旧写死
        //   比较逐位等价；新增的两来源今天不走主循环（E-1b 才收敛），故现网行为零变化。
        //   （marble_origami 不是 fork 来源，其 CONTEXT_COLLAPSE 门在下方单独保留。）
        String canonical = com.nexusai.application.agent.QuerySource.canonicalize(querySource);
        if (com.nexusai.application.agent.QuerySource.isBackgroundForkSource(
                com.nexusai.application.agent.QuerySource.fromString(canonical))) {
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
        // [compact-realusage A 2026-09-09] 对齐 CC autoCompact.ts:225 tokenCount = tokenCountWithEstimation(messages)
        //   − snipTokensFreed（tokens.ts:251 usage-walk：最近真实 API usage 打底 + 尾部粗估）——与 blocking
        //   预检（LlmAgentLoop:5621-5628 tokenCountWithEstimation − snipTokensFreed）同源。旧实现
        //   tokenCounter.count 为纯 4 字符/token 粗估，中文/代码/长工具输出高估 ~2×（实测 56.5 万估成 113 万
        //   → 越 90% 误触 AUTO 压缩，见 2026-09-09 sess-fcdcdc68 日志）。deepseek/openai_compatible 走
        //   resolveAnthropic=false（input 已含 cache hit，双计已修）。无 model（单测/未接线）→ 回落 estimate，
        //   零行为变化。
        int tokenCount;
        if (model != null && !model.isBlank() && hasAssistantUsage(messages)) {
            // 会话含真实 assistant usage（运行时 appendMessage(...withUsage...) 回填 input/output）
            // → usage-walk（CC autoCompact.ts:225 tokenCountWithEstimation）：最近真实 API usage 打底 +
            // 尾部粗估，不再纯估虚高误触（2026-09-09 sess-fcdcdc68：真实 56.5 万被估 113 万误触）。
            tokenCount = Tokens.tokenCountWithEstimation(
                messages, CompactConversation.resolveAnthropic(model)) - snipTokensFreed;
        } else {
            // model null / 无真实 usage（单测 mock tokenCounter / 纯文本小会话）→ 回落 estimate（零行为变化）
            tokenCount = tokenCounter.count(messages) - snipTokensFreed;
        }

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

    /** [compact-realusage] 会话是否含真实 assistant usage（input/output 均已回填）→ 才走 usage-walk 判定。 */
    private static boolean hasAssistantUsage(List<ChatMessageDto> messages) {
        if (messages == null) {
            return false;
        }
        for (ChatMessageDto m : messages) {
            if (m != null && m.role() == com.nexusai.model.session.dto.Role.assistant
                    && m.inputTokens() != null && m.outputTokens() != null) {
                return true;
            }
        }
        return false;
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
     * snipTokensFreed 并转发给 {@link #shouldAutoCompact(List, String, String, int)}。
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
     * <p><b>[P4-4 tracking 生命周期对齐 CC] 本重载为便捷路径</b>（测试 / 手动接线，无 per-session
     * 上下文）：跟踪状态回落实例字段 {@link #defaultTracking}（跨会话共享，<b>生产禁用</b>）。
     * 生产路径（{@code LlmAgentLoop}）必须走
     * {@link #autoCompactIfNeeded(List, int, String, CompactConversationContext, AutoCompactTrackingState)}
     * 显式传入本 query() 的跟踪状态 —— 对齐 CC 把 {@code tracking} 作为
     * {@code deps.autocompact} 第 5 参（query.ts:852-865）而非模块/单例状态。
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
        return autoCompactIfNeeded(messages, snipTokensFreed, querySource, ccContext, this.defaultTracking);
    }

    /**
     * 尝试执行自动压缩（<b>生产路径</b> · 显式跟踪状态）· 对齐 CC
     * {@code autoCompactIfNeeded(messages, toolUseContext, cacheSafeParams, querySource, tracking, snipTokensFreed)}
     * （autoCompact.ts:275-278）——跟踪状态由调用方持有并作为参数传入，<b>不是</b> AutoCompactor 实例状态。
     *
     * <p><b>[P4-4 tracking 生命周期对齐 CC] WHY</b>：CC 的 {@code autoCompactTracking} 属于
     * {@code query()} 调用的循环局部 State（query.ts:264 声明 / :425 初始化 undefined / :555
     * {@code let tracking = autoCompactTracking}），随 query() 调用创建、随调用结束丢弃——多会话
     * 共享进程时天然互不污染。Java 端 AutoCompactor 为单例 bean，若沿用实例字段则跨会话/跨 run 串台
     * （B 会话继承 A 的熔断计数或 compacted 标志 → 该压的不压、不该压的反复压，即历史「同一会话反复
     * autoCompact」事故的判据错乱面）。本重载把状态生命周期交还调用方：
     * {@code LlmAgentLoop.queryLoop} 每 query() 新建一个 {@link AutoCompactTrackingState}，经
     * {@code loop(...)} 参数透传（含 stop-hook 递归重入帧，对齐 CC transition 携带
     * {@code autoCompactTracking: tracking} 的 7 处 continue 站点）。
     *
     * @param messages        消息列表（post-snip/collapse 视图 · CC query.ts:454 传参）
     * @param snipTokensFreed L2 Snip 已释放的 token 数（CC query.ts:466 传参；默认 0）
     * @param querySource     查询来源（CC querySource；session_memory/compact/marble_origami → 守卫）
     * @param ccContext       compactConversation 上下文（per-session 接线，由 LlmAgentLoop 经
     *                        {@link CompactConversation#buildAutoContext} 构建；null → 默认上下文，
     *                        摘要生产回落 {@link #prepareAutoContext} 从 compactCallback 适配）
     * @param tracking        本 query() 调用的跟踪状态（CC original: tracking，autoCompact.ts:275）·
     *                        非 null（调用方每 query() 新建）；读写本参数，绝不触碰实例字段
     * @return 压缩结果
     */
    public AutoCompactResult autoCompactIfNeeded(
            List<ChatMessageDto> messages, int snipTokensFreed, String querySource,
            CompactConversationContext ccContext, AutoCompactTrackingState tracking) {
        if (messages == null || messages.isEmpty()) {
            return new AutoCompactResult(false, messages, null, 0, null, null);
        }
        // [P1a F-08 · 单例可变会话字段] querySource = 调用内局部变量（对齐 CC autoCompact.ts:163
        //   的形参 querySource），绝不写回实例字段（旧实现 `this.querySource = …` 会把 A 会话的
        //   调用来源留在单例上，B 会话的回落路径/清理门读到别人的来源）。归一语义与旧写点逐字
        //   一致：null → "user"，空串原样保留（守卫对空串的行为继承旧语义，零漂移）。
        String effQuerySource = querySource != null ? querySource : "user";
        // [P2-7 · 2026-09-11] model = 调用内局部变量（对齐 CC autoCompact.ts:267
        //   `const model = toolUseContext.options.mainLoopModel`）——绝不写入实例状态。
        //   旧实现写 this.model 且 ccContext.getModel()==null 时不覆写 → 保留上一会话的 model
        //   （单例 bean 多会话并发串台：A 会话吃 B 会话的窗口算阈值）。
        //   [IMP-CM-06 G-2] 源 = ccContext.getModel()（effectiveModel = CC mainLoopModel，可被
        //   fallbackModel 改写 query.ts:922）；ccContext 为 null（便捷重载）→ null = 默认窗口。
        String model = ccContext != null ? ccContext.getModel() : null;
        // [FIX-SM] SM 压缩生产 sessionId/agentId 必须从 ccContext 取（LlmAgentLoop:2500-2501
        //   buildAutoContext 已把 params.toolUseContext() 的 sessionId/agentId 注入上下文）。
        // [S1 轨 III · T9 2026-09-14] 原「ccContext 缺值回落实例字段」已删——那两字段生产 0 写入方
        //   （恒 null）⇒ 回落分支等价于直接 null；现显式写 null ⇒ ccContext 缺值时 SM 读 null 文件
        //   回落 legacy（与删除前逐字等价），且调用方已在 :928 一带收到 ≥WARN。
        String effSessionId = ccContext != null ? ccContext.getSessionId() : null;
        String effAgentId = ccContext != null ? ccContext.getAgentId() : null;

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
        if (!shouldAutoCompact(messages, model, querySource, snipTokensFreed)) {
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
        CompactionResult smResult = trySessionMemoryCompaction(messages, effSessionId, effAgentId, model);
        if (smResult != null) {
            // SM 成功链：setLastSummarizedMessageId(undefined) + runPostCompactCleanup +
            // [gate] notifyCompaction + markPostCompaction（INV-8 · autoCompact.ts:287-310）。
            // [sm-cursor-sessionize P0-2] 只清本会话游标（旧 static volatile 语义会跨会话清空，
            // A 压缩成功 → B 的 lastSummarizedMessageId 被清 → B 的 SM 提取时机错乱）。
            SessionMemoryService.setLastSummarizedMessageId(effSessionId, null);
            // [P1a F-08] 显式两参传参（querySource + 会话标识）—— 旧 `runPostCompactCleanup.run()`
            //   读默认闭包的 this.querySource/this.sessionId ⇒ 生产 sessionId 字段恒 null（零 setter
            //   调用）⇒ clearSystemPromptSections 与 resetMicrocompactState 的 per-session 效果双失。
            postCompactCleanup.accept(effQuerySource, effSessionId);
            // [SM-07] notifyCompaction 按 PROMPT_CACHE_BREAK_DETECTION 门控（DRIFT-9）·
            //   CC autoCompact.ts:302-304 `if (feature('PROMPT_CACHE_BREAK_DETECTION'))`
            //   —— feature 关闭时不动 cache-read 基线（旧实现无条件调用）。
            if (promptCacheBreakDetectionGate.getAsBoolean()) {
                notifyCompaction.accept(
                    (querySource == null || querySource.isEmpty()) ? "compact" : querySource,
                    effAgentId);
            }
            // [G3] 传 effAgentId（ccContext.getAgentId()：主线程=null · 子代理=UUID 串）。
            PostCompactionState.markPostCompaction(effSessionId, effAgentId);
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
            // [S1 轨 III · T9] ccContext 缺值 ⇒ 回落默认上下文并 **≥WARN**（禁静默 null）：
            //   回落 ctx 不再携带 sessionId/agentId/toolUseContext（原 AutoCompactor 单例字段已删）
            //   ⇒ 调用方若走此路径必须接受「无会话标识的压缩」。生产路径恒有 ccContext
            //   （LlmAgentLoop:5713 buildAutoContext）。
            if (ccContext == null) {
                log.warn("[AutoCompactor] autoCompactIfNeeded: ccContext 缺值 → 回落"
                    + " buildDefaultCompactConversationContext（该 ctx 无 sessionId/agentId/toolUseContext；"
                    + "生产路径恒有 ccContext，本路径仅便捷重载/测试可达）model={} querySource={}",
                    model, effQuerySource);
            }
            CompactConversationContext ctx = ccContext != null ? ccContext
                : buildDefaultCompactConversationContext(model, effQuerySource);
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
                getAutoCompactThreshold(model),   // autoCompact.ts:283 autoCompactThreshold
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
            // [P1a F-08] 显式两参传参（querySource + 会话标识），同 SM 链（见上方说明）。
            postCompactCleanup.accept(effQuerySource, effSessionId);
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
        // [T9] 原传 this.querySource（字段已删）→ 传其默认值字面量（逐字等价）
        AutoCompactResult result = autoCompactIfNeeded(messages, snipTokensFreed, "user", null);
        // [IMP-A4-3 · OPD-CM5-A-31] Java 便捷重载承担 CC 调用方写回职责（query.ts:536-542）：
        //   autoCompactIfNeeded 失败路径经返回值承载 nextFailures，由本方法（作为该路径的调用方）
        //   写回 tracking——保证便捷路径（测试/手动接线）熔断计数持续累计，与生产 LlmAgentLoop
        //   写回行为一致。成功复位已由 recordSuccess 内部完成（query.ts:521-526 公共复位等价）。
        if (result.consecutiveFailures() != null) {
            defaultTracking.setConsecutiveFailures(result.consecutiveFailures());
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
     * @param model     本调用的有效模型名（[P2-7] 调用内局部变量透传，用于 SM 阈值 · 不读实例字段）
     * @return SM 压缩结果；不可用 → null
     */
    private CompactionResult trySessionMemoryCompaction(
            List<ChatMessageDto> messages, String sessionId, String agentId, String model) {
        if (sessionMemoryService == null) {
            return null;
        }
        // E02（OPD-CM3-27）删除外层 try/catch（双重 catch 冗余）：CC sessionMemoryCompact.ts:545/:621
        // 单层 catch 在 SessionMemoryService.trySessionMemoryCompaction 内部兜底（期望内错误 → null，
        // 回落全量压缩）；CC autoCompactIfNeeded（autoCompact.ts:287-292）对 SM 调用亦无外层包裹。
        // 仅保留 null-guard：sessionMemoryService 未注入 → null。前置读取 getSessionMemoryContent
        // 非五类 fs-inaccessible 错误按 CC 语义上抛（sessionMemoryUtils.ts:124-125 显式失败，不吞错）。
        return sessionMemoryService.trySessionMemoryCompaction(
            messages, sessionId, agentId, getAutoCompactThreshold(model));
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
     *
     * <p><b>[P1a F-08]</b> {@code querySource} 由调用方显式传入（对齐同文件 {@code model} 的
     * 显式入参手法 · P2-7）：旧实现读实例字段 {@code this.querySource}（由 autoCompactIfNeeded
     * 写回，单例多会话串台）——该字段已随 [S1 轨 III · T9 2026-09-14] 删除。便捷重载
     * {@link #tryAutoCompact(List, int)} 改传字面量 {@code "user"}（见本文件 :164）。
     *
     * <p><b>会话字段（S-route 残差）</b>：{@code sessionId}/{@code agentId} 仍回落实例字段
     * <p><b>[S1 轨 III · T9 2026-09-14] 本方法不再填 sessionId/agentId/toolUseContext</b>：
     * 那三项原读 AutoCompactor 的单例字段（生产 0 写入方 ⇒ 恒 null）。删除后本回落 ctx 的这三项
     * 保持 CompactConversationContext 默认（null），与删除前**逐字等价**。调用方（仅便捷重载 /
     * 测试）如需这三项，只能走 {@code ccContext}（LlmAgentLoop:5713 {@code buildAutoContext}）显式接线。
     *
     * @param model       有效模型名（null → 默认窗口）
     * @param querySource 本调用的查询来源（调用内已归一，非 null）
     */
    CompactConversationContext buildDefaultCompactConversationContext(String model, String querySource) {
        CompactConversationContext ctx = new CompactConversationContext()
            .setModel(model)
            .setQuerySource(querySource)
            .setReadFileState(new LinkedHashMap<>());
        wireAutoNotifyCompaction(ctx);
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
                    // [批 5a] ctx 显式下传（原经 ThreadLocal 隐式通道）
                    return compactCallback.summarize(compactPrompt, messagesToSummarize, ctx);
                } catch (Exception e) {
                    throw e instanceof RuntimeException re ? re : new RuntimeException(e);
                }
            });
        }
        // [S1 轨 III · T9 2026-09-14] 原「用本压缩器注入的 toolUseContext 兜底」已删除：
        //   兜底源（AutoCompactor.toolUseContext 字段）生产 0 写入方 ⇒ 恒 null ⇒ 该兜底此前永远 no-op。
        //   toolUseContext 的唯一生产来源是主路径（LlmAgentLoop:5723 对 ccContext 显式接线）。
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
     * 重置<b>便捷路径</b>跟踪状态（测试 / 手动接线）· 生产路径已无共享状态可重置。
     *
     * <p>[P4-4] 生产路径的跟踪状态是 per-query() 局部对象（{@code LlmAgentLoop.queryLoop} 每
     * query() 新建并经 {@link #autoCompactIfNeeded(List, int, String, CompactConversationContext,
     * AutoCompactTrackingState)} 第 5 参传入），随调用结束自然丢弃 —— 对齐 CC query.ts:425
     * {@code autoCompactTracking: undefined} 的每 query() 初值，无需（也不应）由单例 bean 显式
     * reset 来"隔离会话"（旧 {@code doRun} 入口 reset 正是跨会话串台隐患的补丁）。
     */
    public void reset() {
        defaultTracking.reset();
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
