package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.loop.ContextCollapse;
import com.nexusai.application.agent.permission.ClassifierApprovals;
import com.nexusai.application.agent.permission.classifier.SpeculativeClassifier;
import com.nexusai.application.agent.prompt.PromptCacheGroup;
import com.nexusai.application.agent.prompt.SessionPromptCacheRegistry;
import com.nexusai.application.agent.prompt.SystemPromptInjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// [步骤 4] 原 `import java.util.Locale;` / `import java.util.UUID;` 已删：两者在步骤 4 后
//   全类无引用（Locale 的实际使用处写的是全限定名 java.util.Locale.ROOT，见本类 isMainThreadCompact；
//   UUID 随 STATIC_SESSION_REGISTRY 的删除一并失效）= 未使用导入，属噪声。

/**
 * 压缩后清理 · 对齐 CC postCompactCleanup.ts {@code runPostCompactCleanup()}（:31-77）。
 *
 * <h2>WHY 存在</h2>
 * <p>CC 在每次压缩（auto / reactive / /compact / partial）后调用模块级函数
 * {@code runPostCompactCleanup(querySource?)} 释放被压缩失效的缓存与跟踪态：
 * <pre>
 *   resetMicrocompactState()                                  // :41
 *   if (feature('CONTEXT_COLLAPSE')) {                        // :42-49
 *     if (isMainThreadCompact) resetContextCollapse()         // :43-49
 *   }
 *   if (isMainThreadCompact) {                                // :51-60
 *     getUserContext.cache.clear?.()
 *     resetGetMemoryFilesCache('compact')
 *   }
 *   clearSystemPromptSections()                               // :62
 *   clearClassifierApprovals()                                // :63
 *   clearSpeculativeChecks()                                  // :64（CC 有 speculativeChecks 结构但外部构建恒禁用；Java 对齐恒禁用 —— 见 SpeculativeClassifier）
 *   // 不 resetSentSkillNames（:65-69，skill_listing 不重注入）  // :65-69
 *   clearBetaTracingState()                                   // :70
 *   clearSessionMessagesCache()                               // :76
 * </pre>
 *
 * <h2>【IMP-19 D-08】回调注册模式 → 固定操作序列</h2>
 * <p><b>旧实现（D-08）</b>: {@code registerHook(CleanupHook)} 让各服务把清理逻辑注册进
 * {@code hooks} 列表，{@code run()} 遍历执行 —— 与 CC 固定操作序列偏移（resetMicrocompactState /
 * cache 清除 / sections / speculative / betaTracing / sessionMessagesCache 多数操作缺席），
 * 仅 LlmAgentLoop 注册 1 个 {@code clearClassifierApprovals} hook（04 M23）。
 *
 * <p><b>新实现（本类）</b>: 重建为 CC {@code runPostCompactCleanup} 固定操作序列，删除
 * {@code registerHook}/{@code CleanupHook}/{@code clearHooks}（D-08）。序列为<b>硬编码</b>
 * 顺序（对齐 CC :31-77），非回调列表。
 *
 * <h2>协作器装配（非回调注册）</h2>
 * <p>CC 的 {@code runPostCompactCleanup} 是模块级函数，直接调用各模块的清理函数。
 * Java 端对应状态宿主为：
 * <ul>
 *   <li>{@link MicroCompactor#resetMicrocompactState(String)} —— 静态（会话桶 pendingCacheEdits）</li>
 *   <li>{@link ContextCollapse#resetContextCollapse()} —— CONTEXT_COLLAPSE feature 门控 + main-thread gate</li>
 *   <li>{@link ClassifierApprovals#clearClassifierApprovals()} —— 静态</li>
 *   <li>{@link ClaudemdEngine} —— Spring bean（FIX-CL 接线，{@code STATIC_CLAUDE_MD}）</li>
 *   <li>[步骤 4] 集合 A/B 的缓存失效 —— {@link SessionPromptCacheRegistry}
 *       （会话级 store 静态表，按 sessionId 分区；集合成员见 {@link PromptCacheGroup}）</li>
 * </ul>
 * 后者（ContextCollapse / ClaudemdEngine）为 Spring bean，本类以
 * {@code @Component} 构造注入并在启动时写入静态字段
 * （{@code STATIC_COLLAPSE}/{@code STATIC_CLAUDE_MD}），
 * 供静态入口 {@link #runPostCompactCleanup(String)} 调用 —— 这是<b>固定协作器的 Spring 装配</b>，
 * 不是 D-08 的回调注册列表。
 *
 * <h2>Java 无对应缓存的操作（诚实 no-op）</h2>
 * <p>以下 CC 操作在 Java 端<b>无对应缓存结构</b>（教学版简化），序列中保留位置并以日志说明：
 * <ul>
 *   <li>{@code getUserContext.cache.clear()} + {@code resetGetMemoryFilesCache('compact')} ——
 *       [FIX-CL + IMP-SP2-08 SP-07 △-6 + 步骤 4] <b>真接线分两支</b>：有会话标识 ⇒
 *       {@code SessionPromptCacheRegistry.clearPromptCaches(sessionId,
 *       PromptCacheGroup.POST_COMPACT_USER_CONTEXT, …)}（按会话精确清，只清该会话 provider 的
 *       user 缓存，<b>保留</b> systemContext/gitStatus —— CC :51-60 只清 user 通道）；
 *       <b>无会话标识</b> ⇒ 回落到 CC 进程级广播
 *       {@code SystemPromptInjection.clearUserOnlyProviderCaches()} 并打
 *       {@code SystemPromptInjection.NO_SESSION_BROADCAST_WARNING}（跨会话隐患留痕）。
 *       其后 {@code STATIC_CLAUDE_MD.resetGetMemoryFilesCache('compact')}
 *       （getMemoryFiles one-shot 发射态 + 缓存清空）</li>
 *   <li>{@code clearSystemPromptSections()} —— [IMP-SP-07] 已接线：清当前会话的 per-section 缓存
 *       （对齐 CC systemPromptSections.ts:65-68，/compact 后不命中旧缓存）</li>
 *   <li>{@code clearBetaTracingState()} —— Java 无 beta session tracing 子系统</li>
 *   <li>{@code clearSessionMessagesCache()} —— Java SessionStorage 无 getSessionMessages memoize cache</li>
 * </ul>
 *
 * <h2>不 reset sentSkillNames</h2>
 * <p>CC 显式<b>不</b>调 {@code resetSentSkillNames()}（:65-69）：压缩后重注入完整 skill_listing
 * 是纯 cache_creation；模型仍有 SkillTool schema，invoked_skills 保留已用技能。Java 端
 * skill_listing 去重状态存于进程级 {@code SkillListingSentRegistry}（2026-09-10 起，键
 * sessionId+agentKey），本序列<b>不触碰</b>它 → 压缩后 sent 集合跨压缩存活 → 天然不重注、新技能
 * 仍走增量（对齐 CC compact.ts:548-553「刻意不 reset」）。
 */
@Component
public class PostCompactCleanup {

    private static final Logger log = LoggerFactory.getLogger(PostCompactCleanup.class);

    /** CONTEXT_COLLAPSE 状态宿主 · CC original: contextCollapse（postCompactCleanup.ts:42-49）。 */
    private static volatile ContextCollapse STATIC_COLLAPSE;
    /** [FIX-CL] claudemd 引擎宿主 · resetGetMemoryFilesCache('compact')（:52-59）真实失效接线用。 */
    private static volatile ClaudemdEngine STATIC_CLAUDE_MD;
    // [步骤 4] 原 STATIC_SESSION_REGISTRY（会话 AgentState 注册表宿主）已<b>删除</b>：
    //   集合 A/B 的失效改走**会话级 store 单点** SessionPromptCacheRegistry（静态工具表，按 sessionId
    //   分区寻址），不再需要经「会话 AgentState」这一跳 —— AgentState.systemPromptSectionCache()
    //   本就转发到同一份会话级 store（AgentState.java:1245）。删除理由（⛔ 非重构噪声）：
    //   ① 该字段步骤 4 后**只写不读**（assigned-but-never-read = 误导性死状态）；
    //   ② CC 的 runPostCompactCleanup(querySource) 是模块函数、**没有任何注册表形参**
    //      （services/compact/postCompactCleanup.ts:31）⇒ 该参数本仓特有，删掉更贴近 CC。

    /**
     * Spring 装配入口：把协作器 bean 写入静态字段，供静态入口 {@link #runPostCompactCleanup(String)} 调用。
     *
     * <p>{@code required=false}：单测/无 bean 上下文下允许 null（对应操作降级跳过 + debug 日志）。
     * 非回调注册 —— 仅固定协作器，由容器创建本单例时注入一次。
     *
     * @param contextCollapse CONTEXT_COLLAPSE 状态宿主（CC :42-49）
     * @param claudemdEngine  [FIX-CL] claudemd 引擎宿主（resetGetMemoryFilesCache('compact') :52-59 失效接线）
     */
    public PostCompactCleanup(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ContextCollapse contextCollapse,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ClaudemdEngine claudemdEngine) {
        STATIC_COLLAPSE = contextCollapse;
        STATIC_CLAUDE_MD = claudemdEngine;
    }

    /**
     * main-thread 压缩判定 · 对齐 CC postCompactCleanup.ts:36-39
     * {@code isMainThreadCompact}（同 startsWith 模式，index.ts:188 isMainThread）。
     *
     * <p>Subagent（{@code agent:*}）与 main-thread 同进程共享模块级状态（context-collapse store、
     * getUserContext cache、getMemoryFiles one-shot hook flag）；subagent 压缩若重置这些状态会
     * 破坏 main-thread 状态，故 main-thread 独占操作必须经本 gate。
     *
     * @param querySource CC QuerySource（null = 无源，等价 undefined）
     * @return true = main-thread compact（可重置模块级状态）
     */
    public static boolean isMainThreadCompact(String querySource) {
        // IMP2-01（S-12/EV2-040）：判定入口 canonical 归一——生产传 name() 大写
        // （REPL_MAIN_THREAD/SDK）亦命中；小写既有值域幂等（canonicalize 未知名原样）。
        // [merge 回归修复 2026-08-14] 生产值域含带前缀形态（querySource().name() + ":…"，
        //   如 "REPL_MAIN_THREAD:turn-3"）——canonicalize 只归一精确枚举名，带前缀值原样返回，
        //   大小写敏感 startsWith 会漏判生产大写前缀 → 前缀匹配降为大小写不敏感（对齐 CC
        //   postCompactCleanup.ts:36-39 startsWith('repl_main_thread') 前缀语义 + Java 大写生产值域）。
        String canonical = com.nexusai.application.agent.QuerySource.canonicalize(querySource);
        if (canonical == null) {
            return true;
        }
        String lower = canonical.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("repl_main_thread") || "sdk".equals(lower);
    }

    /**
     * 无参入口 · 对齐 CC {@code runPostCompactCleanup()}（compact.ts:64/118/201、caches.ts:74）。
     *
     * <p>querySource=undefined（null）→ {@link #isMainThreadCompact(String)}=TRUE → 3 项
     * main-thread 操作（resetContextCollapse + getUserContext.cache.clear +
     * resetGetMemoryFilesCache('compact')）<b>全执行</b>。CC 注释（postCompactCleanup.ts:28-29）：
     * undefined 只对 genuinely main-thread-only 调用方（/compact、/clear）安全——subagent
     * （agent:*）同进程共享模块级状态，必须经有参入口传真实 querySource（autoCompact.ts:297/326）。
     *
     * <p><b>IMP2-02（S-13/OD-20，△-1/△-2）</b>: Java 旧实现 manual 路径传
     * {@code effectiveQuerySource()}（/compact 恒 "compact"）→ gate=false → 缓存残留（P0）；
     * 现按 CC 无参调用语义修复。
     */
    public static void runPostCompactCleanup() {
        runPostCompactCleanup(null, null);
    }

    /**
     * 有源入口（无会话形参）· 等价 {@code runPostCompactCleanup(querySource, null)}。
     *
     * <p><b>⚠ 调用方待接线</b>：本入口不携带会话标识 ⇒ 第 4 项 {@code clearSystemPromptSections}
     * 会 WARN 跳过（无法定位会话级 store），第 3 项集合B 退化为 CC 进程级广播（<b>清全部会话</b>）
     * 并打 {@code SystemPromptInjection.NO_SESSION_BROADCAST_WARNING}（跨会话隐患留痕 ·
     * 主 agent 已裁定保留该 CC-parity 语义、仅加告警，见
     * {@code SystemPromptInjection#clearUserOnlyProviderCaches()} 的 javadoc）。
     * 调用方（{@code CompactCommand} /
     * {@code AutoCompactor} / {@code CommandController}）应改用
     * {@link #runPostCompactCleanup(String, String)} 显式传入会话；本批（3c）内
     * {@code PartialCompactService} 已切换为显式会话。
     *
     * @param querySource 压缩 query 的来源（见 {@link #runPostCompactCleanup(String, String)}）
     */
    public static void runPostCompactCleanup(String querySource) {
        runPostCompactCleanup(querySource, null);
    }

    /**
     * 执行压缩后清理 · 对齐 CC postCompactCleanup.ts:31-77 {@code runPostCompactCleanup(querySource?)}。
     *
     * <p><b>固定操作序列</b>（CC 顺序不可调换）：
     * <ol>
     *   <li>{@code resetMicrocompactState()}（:41）—— 复位 pendingCacheEdits / cached-MC 态（IMP-09 入口）</li>
     *   <li>{@code feature('CONTEXT_COLLAPSE')} 且 main-thread → {@code resetContextCollapse()}（:42-49）</li>
     *   <li>main-thread → getUserContext.cache.clear + resetGetMemoryFilesCache('compact')（:51-60；
     *       Java <b>真接线</b> · 非 no-op —— 有会话 ⇒ 按会话精确清集合B，无会话 ⇒ 广播清 + 隐患 WARN）</li>
     *   <li>{@code clearSystemPromptSections()}（:62，[IMP-SP-07] 真实失效接线 —— 清当前会话 section 缓存）</li>
     *   <li>{@code clearClassifierApprovals()}（:63）</li>
 *   <li>{@code clearSpeculativeChecks()}（:64，CC 有 speculativeChecks 结构但外部构建恒禁用；Java 对齐恒禁用 —— 见 SpeculativeClassifier）</li>
     *   <li>不 resetSentSkillNames（:65-69）</li>
     *   <li>{@code clearBetaTracingState()}（:70，Java no-op）</li>
     *   <li>{@code clearSessionMessagesCache()}（:76，Java no-op）</li>
     * </ol>
     *
     * @param querySource 压缩 query 的来源（/compact 等传 "compact"；主循环传 "repl_main_thread:…"；
     *                    subagent 传 "agent:…"；null = 无源视为 main-thread，对齐 CC undefined 语义）
     * @param sessionId   显式会话标识（[批 3c] 供集合 A/B 定位<b>会话级 store</b>
     *                    —— {@link SessionPromptCacheRegistry} 按 sessionId 分区；原由裸 MDC 会话槽
     *                    承载。null/空白 ⇒ 集合A 项 WARN 跳过 / 集合B 项退化为 CC 进程级广播，
     *                    见 {@link #clearActiveSessionSystemPromptSections(String)}）
     */
    public static void runPostCompactCleanup(String querySource, String sessionId) {
        boolean isMainThread = isMainThreadCompact(querySource);
        log.info("[PostCompactCleanup] runPostCompactCleanup: querySource={} isMainThreadCompact={} · CC postCompactCleanup.ts:31-77",
            querySource, isMainThread);

        // 1. resetMicrocompactState（:41）—— 无条件
        //    [批 3c] 会话键 = 本方法显式形参 sessionId（只复位该会话桶；null/空白 → MicroCompactor
        //    侧一次性 WARN 回落默认桶，见 MicroCompactor.currentSessionState）。
        MicroCompactor.resetMicrocompactState(sessionId);

        // 2. CONTEXT_COLLAPSE feature && main-thread → resetContextCollapse（:42-49）
        ContextCollapse cc = STATIC_COLLAPSE;
        if (cc != null && cc.isContextCollapseEnabled() && isMainThread) {
            cc.resetContextCollapse();
        } else if (log.isDebugEnabled()) {
            log.debug("[PostCompactCleanup] resetContextCollapse 跳过: collapseWired={} feature={} isMainThread={} · CC postCompactCleanup.ts:42-49",
                cc != null, cc != null && cc.isContextCollapseEnabled(), isMainThread);
        }

        // 3. main-thread → getUserContext.cache.clear + resetGetMemoryFilesCache('compact')（:51-60）
        //    [IMP-SP2-08 SP-07 △-6] 清理面收敛：只清 user 通道（clearUserOnlyProviderCaches，
        //    getUserContext.cache.clear 等价）+ 重置 getMemoryFiles one-shot 发射态（InstructionsLoaded
        //    hook 上报 'compact' 而非误报 'session_start'）。CC :52-59 说明：只清内层 getMemoryFiles
        //    缓存会命中外层 getUserContext 缓存导致 InstructionsLoaded hook 不触发 → 必须清 user 缓存。
        //    旧 Java 实现经 clearAllProviderCaches 双清 system/user 为多清偏差（CC 不清
        //    getSystemContext.cache，systemContext/gitStatus 缓存 /compact 后保留）。
        if (isMainThread) {
            // [步骤 4 · 集合B] CC postCompactCleanup.ts:59 `getUserContext.cache.clear?.()` ——
            //   该行在 isMainThreadCompact 守卫**之内**（:51）。CC 侧是进程级 memoize（一进程一会话）
            //   ⇒ 本仓必须映射为**按会话清**（PromptCacheGroup.POST_COMPACT_USER_CONTEXT）。
            //   ⛔ 不再无条件广播 SystemPromptInjection.clearUserOnlyProviderCaches()：那会
            //   「会话 A 压缩 ⇒ 打掉会话 B 的 claudeMd 头部」（跨会话串味，且恰恰是本批要修的
            //    每轮重建类的浪费）。仅在**无会话来源**（历史无参入口 / 测试）时才退化为广播 ——
            //    那种调用方在 JS 世界等价于 CC 的「进程级」，广播是它的忠实翻译。
            if (sessionId != null && !sessionId.isBlank()) {
                SessionPromptCacheRegistry.clearPromptCaches(sessionId,
                    PromptCacheGroup.POST_COMPACT_USER_CONTEXT, "compact:main-thread");
            } else {
                // ⚠ 醒目 WARN（跨会话隐患留痕 · 文案单点在 SystemPromptInjection.NO_SESSION_BROADCAST_WARNING）：
                //   本分支 = 「无会话标识 ⇒ 广播清全部已注册 provider」，在本仓多会话 + provider 跨 run
                //   长期存活下会打掉**全部会话**的 claudeMd 头部（跨会话串味）。主 agent 已裁定保留该
                //   CC-parity 语义（CC 一进程=一会话 ⇒ 广播 == 清本会话，见 SystemPromptInjection javadoc），
                //   仅告警留痕；正确调用方式 = runPostCompactCleanup(querySource, sessionId)。
                int broadcastCleared = SystemPromptInjection.clearUserOnlyProviderCaches();
                log.warn("[PostCompactCleanup] {} · CC postCompactCleanup.ts:59；本次实际广播清 {} 个 provider；"
                    + "调用方应改用 runPostCompactCleanup(querySource, sessionId)",
                    SystemPromptInjection.NO_SESSION_BROADCAST_WARNING, broadcastCleared);
            }
            ClaudemdEngine claudemd = STATIC_CLAUDE_MD;
            if (claudemd != null) {
                claudemd.resetGetMemoryFilesCache("compact", sessionId);
            } else if (log.isDebugEnabled()) {
                log.debug("[PostCompactCleanup] resetGetMemoryFilesCache('compact') 跳过：ClaudemdEngine 未接线 · CC postCompactCleanup.ts:52-59");
            }
        } else if (log.isDebugEnabled()) {
            log.debug("[PostCompactCleanup] 集合B 跳过（子代理 compact）：CC postCompactCleanup.ts:59 在 "
                + "isMainThreadCompact 守卫内 ⇒ 子代理**刻意不清**主会话 userContext（否则破坏主线程状态）"
                + "· querySource={}", querySource);
        }

        // 4. clearSystemPromptSections（:62）—— [IMP-SP-07] 真实失效接线：清本会话 section 缓存
        //    ⭐ 集合A 在守卫**之外**（无守卫）⇒ 子代理 compact 也执行，对齐 CC :62。
        //    [批 3c] 会话标识由**显式形参**传入（原读裸 MDC 的会话槽；载体已随本批删除）。
        clearActiveSessionSystemPromptSections(sessionId);
        // 5. clearClassifierApprovals（:63）—— 审批只对当前会话有效，compact 后失效
        ClassifierApprovals.clearClassifierApprovals();
        log.info("[PostCompactCleanup] clearClassifierApprovals: 分类器审批缓存已清空 · CC postCompactCleanup.ts:63");
        // 5.5 [U6-A1] clearSpeculativeChecks（:64）—— 对齐 CC 释放压缩失效的投机分类器检查
        //   （外部构建恒禁用 stub 下 speculativeChecks 恒空，clear 为 no-op，但接线保留可随时开启）
        SpeculativeClassifier.clearSpeculativeChecks();

        // 6. 不 resetSentSkillNames（:65-69 显式禁止）—— skill_listing 不重注入（cache_creation 浪费），
        //    模型仍有 SkillTool schema，invoked_skills 保留已用技能。

        // 7. clearBetaTracingState（:70）—— Java 无 beta session tracing → no-op
        // 8. COMMIT_ATTRIBUTION 门控 → sweepFileContentCache（:72-75）
        //    CC：feature('COMMIT_ATTRIBUTION') 时清 file-content 归属缓存。Java 无
        //    COMMIT_ATTRIBUTION feature / fileContentCache 结构 → 门控 no-op（诚实登记）。
        if (isEnvTruthy(System.getenv("COMMIT_ATTRIBUTION"))) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactCleanup] sweepFileContentCache 跳过：Java 无 COMMIT_ATTRIBUTION "
                    + "feature 对应的 fileContentCache 结构 · CC postCompactCleanup.ts:72-75");
            }
        }
        // 9. clearSessionMessagesCache（:76）—— Java SessionStorage 无 getSessionMessages memoize cache → no-op
    }

    /** CC isEnvTruthy（envUtils.ts:32-37）· '1'/'true'/'yes'/'on' 视为真。 */
    private static boolean isEnvTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }

    /**
     * [IMP-SP-07 · 步骤 4 已按集合语义重写] clearSystemPromptSections 失效接线 · 对齐 CC
     * {@code clearSystemPromptSections()}（services/compact/postCompactCleanup.ts:62 →
     * constants/systemPromptSections.ts:65-68）—— <b>只有集合 A</b>
     * （{@link PromptCacheGroup#POST_COMPACT_SECTIONS}）。
     *
     * <p><b>⭐ 为什么集合 A 在守卫之外</b>：CC 的 {@code :62} 在 {@code isMainThreadCompact} 守卫
     * <b>之外</b>（无守卫）⇒ 子代理 compact 也执行。这与同函数 {@code :59}（集合B，守卫之内）
     * 形成<b>刻意的不对称</b>：分段缓存重算是无害且正确的，而重置主会话的 userContext 缓存
     * 会破坏主线程模块级状态（CC :36-40 的原话注释）。本方法<u>不</u>判 isMainThread —— 正是对齐点。
     *
     * <p>失效经<b>会话级单点</b> {@link SessionPromptCacheRegistry#clearPromptCaches}（按 sessionId 分区，
     * ⛔ 不广播清全部会话；⛔ 不走 {@code forSession} 以免为清一个未跑过的会话凭空建 store）。
     * 会话缺失 → WARN 显式登记（不静默跳过）。
     *
     * <p>[批 3c] 会话标识由调用方显式传入（原读裸 MDC 的会话槽 —— 该槽的第三态会读到上一请求残留的
     * <b>别的会话</b> id ⇒ 清掉别的会话的 section 缓存）。无来源时必须 ≥WARN（禁只 DEBUG）。
     *
     * @param sessionId 显式会话标识（{@link #runPostCompactCleanup(String, String)} 透传）；
     *                  null/空白 ⇒ WARN 跳过本项（其余清理项照常执行）
     */
    private static void clearActiveSessionSystemPromptSections(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("[PostCompactCleanup] clearSystemPromptSections 跳过：调用方未传会话标识"
                + "（无参 runPostCompactCleanup() 入口，无会话来源）→ 无法定位会话级 section 缓存 · "
                + "CC postCompactCleanup.ts:62；调用方应改用 runPostCompactCleanup(querySource, sessionId)");
            return;
        }
        boolean cleared = SessionPromptCacheRegistry.clearPromptCaches(
            sessionId, PromptCacheGroup.POST_COMPACT_SECTIONS, "compact:clearSystemPromptSections");
        if (cleared) {
            log.info("[PostCompactCleanup] clearSystemPromptSections: 会话 {} 的集合A 分段缓存已清空"
                + "（无守卫 ⇒ 子代理也清，对齐 CC postCompactCleanup.ts:62）", sessionId);
        } else {
            log.warn("[PostCompactCleanup] clearSystemPromptSections 未命中：会话 {} 尚无会话级 store"
                + "（该会话未跑过材料收集 ⇒ 分段缓存本为空），其余清理项照常执行 · CC postCompactCleanup.ts:62",
                sessionId);
        }
    }

    /**
     * 复位静态协作器（仅测试用）。
     *
     * <p>各测试用例独立接线 {@link #PostCompactCleanup(ContextCollapse, ClaudemdEngine)}
     * 时先经本方法复位，避免跨用例静态字段污染。
     */
    static void resetForTest() {
        STATIC_COLLAPSE = null;
        STATIC_CLAUDE_MD = null;
    }
}
