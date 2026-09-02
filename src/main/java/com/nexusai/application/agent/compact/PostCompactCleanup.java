package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.loop.ContextCollapse;
import com.nexusai.application.agent.permission.ClassifierApprovals;
import com.nexusai.application.agent.permission.classifier.SpeculativeClassifier;
import com.nexusai.application.agent.prompt.SystemPromptInjection;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

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
 *   <li>{@link MicroCompactor#resetMicrocompactState()} —— 静态（module 态 pendingCacheEdits）</li>
 *   <li>{@link ContextCollapse#resetContextCollapse()} —— CONTEXT_COLLAPSE feature 门控 + main-thread gate</li>
 *   <li>{@link ClassifierApprovals#clearClassifierApprovals()} —— 静态</li>
 *   <li>{@link ClaudemdEngine} —— Spring bean（FIX-CL 接线，{@code STATIC_CLAUDE_MD}）</li>
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
 *       [FIX-CL + IMP-SP2-08 SP-07 △-6] 真接线：{@code SystemPromptInjection.clearUserOnlyProviderCaches()}
 *       （getUserContext.cache.clear 等价，只清已注册 provider 的 user 缓存，<b>保留</b>
 *       systemContext/gitStatus —— CC :51-60 只清 user 通道）+ {@code STATIC_CLAUDE_MD.resetGetMemoryFilesCache('compact')}
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
 * sentSkillNames 状态存于 {@code AgentLoopContext.LoopSessionState}，本序列不触碰。
 */
@Component
public class PostCompactCleanup {

    private static final Logger log = LoggerFactory.getLogger(PostCompactCleanup.class);

    /** CONTEXT_COLLAPSE 状态宿主 · CC original: contextCollapse（postCompactCleanup.ts:42-49）。 */
    private static volatile ContextCollapse STATIC_COLLAPSE;
    /** [IMP-SP-07] 会话 AgentState 注册表宿主 · clearSystemPromptSections（:62）真实失效接线用。 */
    private static volatile SessionAgentStateRegistry STATIC_SESSION_REGISTRY;
    /** [FIX-CL] claudemd 引擎宿主 · resetGetMemoryFilesCache('compact')（:52-59）真实失效接线用。 */
    private static volatile ClaudemdEngine STATIC_CLAUDE_MD;

    /**
     * Spring 装配入口：把协作器 bean 写入静态字段，供静态入口 {@link #runPostCompactCleanup(String)} 调用。
     *
     * <p>{@code required=false}：单测/无 bean 上下文下允许 null（对应操作降级跳过 + debug 日志）。
     * 非回调注册 —— 仅固定协作器，由容器创建本单例时注入一次。
     *
     * @param contextCollapse     CONTEXT_COLLAPSE 状态宿主（CC :42-49）
     * @param sessionAgentStateRegistry [IMP-SP-07] 会话 AgentState 注册表宿主（clearSystemPromptSections :62 失效接线）
     * @param claudemdEngine      [FIX-CL] claudemd 引擎宿主（resetGetMemoryFilesCache('compact') :52-59 失效接线）
     */
    public PostCompactCleanup(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ContextCollapse contextCollapse,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            SessionAgentStateRegistry sessionAgentStateRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ClaudemdEngine claudemdEngine) {
        STATIC_COLLAPSE = contextCollapse;
        STATIC_SESSION_REGISTRY = sessionAgentStateRegistry;
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
        runPostCompactCleanup(null);
    }

    /**
     * 执行压缩后清理 · 对齐 CC postCompactCleanup.ts:31-77 {@code runPostCompactCleanup(querySource?)}。
     *
     * <p><b>固定操作序列</b>（CC 顺序不可调换）：
     * <ol>
     *   <li>{@code resetMicrocompactState()}（:41）—— 复位 pendingCacheEdits / cached-MC 态（IMP-09 入口）</li>
     *   <li>{@code feature('CONTEXT_COLLAPSE')} 且 main-thread → {@code resetContextCollapse()}（:42-49）</li>
     *   <li>main-thread → getUserContext.cache.clear + resetGetMemoryFilesCache('compact')（:51-60，Java no-op）</li>
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
     */
    public static void runPostCompactCleanup(String querySource) {
        boolean isMainThread = isMainThreadCompact(querySource);
        log.info("[PostCompactCleanup] runPostCompactCleanup: querySource={} isMainThreadCompact={} · CC postCompactCleanup.ts:31-77",
            querySource, isMainThread);

        // 1. resetMicrocompactState（:41）—— 无条件
        MicroCompactor.resetMicrocompactState();

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
            SystemPromptInjection.clearUserOnlyProviderCaches();
            ClaudemdEngine claudemd = STATIC_CLAUDE_MD;
            if (claudemd != null) {
                claudemd.resetGetMemoryFilesCache("compact");
            } else if (log.isDebugEnabled()) {
                log.debug("[PostCompactCleanup] resetGetMemoryFilesCache('compact') 跳过：ClaudemdEngine 未接线 · CC postCompactCleanup.ts:52-59");
            }
        }

        // 4. clearSystemPromptSections（:62）—— [IMP-SP-07] 真实失效接线：清当前会话 section 缓存
        clearActiveSessionSystemPromptSections();
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
     * [IMP-SP-07] clearSystemPromptSections 失效接线 · 对齐 CC {@code clearSystemPromptSections}
     * （postCompactCleanup.ts:62 → systemPromptSections.ts:65-68）。
     *
     * <p>经 {@link RequestContext#sessionId()}（MDC）解析当前会话 UUID → {@link SessionAgentStateRegistry#get}
     * → {@link AgentState#systemPromptSectionCache()#clear()}。会话缺失 / 解析失败 / 无活跃状态 → warn 显式登记
     * （不静默跳过）；registry 未接线（无 @Component bean 的测试场景）→ debug skip。
     */
    private static void clearActiveSessionSystemPromptSections() {
        SessionAgentStateRegistry registry = STATIC_SESSION_REGISTRY;
        if (registry == null) {
            if (log.isDebugEnabled()) {
                log.debug("[PostCompactCleanup] clearSystemPromptSections 跳过：SessionAgentStateRegistry 未接线 · CC postCompactCleanup.ts:62");
            }
            return;
        }
        String sessionIdStr = RequestContext.sessionId();
        if (sessionIdStr == null) {
            log.warn("[PostCompactCleanup] clearSystemPromptSections 跳过：MDC 无 sessionId，无法定位会话级 section 缓存 · CC postCompactCleanup.ts:62");
            return;
        }
        // [session-id-short] MDC sessionId 已 short 直键 registry（UUID.fromString 硬边界删除）
        AgentState state = registry.get(sessionIdStr);
        if (state == null) {
            log.warn("[PostCompactCleanup] clearSystemPromptSections 跳过：会话 {} 无活跃 AgentState（注册表未注册？）", sessionIdStr);
            return;
        }
        state.systemPromptSectionCache().clear();
        log.info("[PostCompactCleanup] clearSystemPromptSections: 会话 {} 的 system prompt section 缓存已清空 · CC postCompactCleanup.ts:62",
            sessionIdStr);
    }

    /**
     * 复位静态协作器（仅测试用）。
     *
     * <p>各测试用例独立接线 {@link #PostCompactCleanup(ContextCollapse, SessionAgentStateRegistry, ClaudemdEngine)}
     * 时先经本方法复位，避免跨用例静态字段污染。
     */
    static void resetForTest() {
        STATIC_COLLAPSE = null;
        STATIC_SESSION_REGISTRY = null;
        STATIC_CLAUDE_MD = null;
    }
}
