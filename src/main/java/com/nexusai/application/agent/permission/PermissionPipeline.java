package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.permission.check.CheckLayer;
import com.nexusai.application.agent.permission.check.CheckLayer1a_DenyRule;
import com.nexusai.application.agent.permission.check.CheckLayer1b_AskRule;
import com.nexusai.application.agent.permission.check.CheckLayer1c_ToolCheck;
import com.nexusai.application.agent.permission.check.CheckLayer1d_ToolDeny_Immune;
import com.nexusai.application.agent.permission.check.CheckLayer1e_RequiresUserInteraction;
import com.nexusai.application.agent.permission.check.CheckLayer1f_ContentSpecificAskRule;
import com.nexusai.application.agent.permission.check.CheckLayer1g_SafetyCheck;
import com.nexusai.application.agent.permission.check.CheckLayer2a_BypassMode;
import com.nexusai.application.agent.permission.check.CheckLayer2b_ToolAlwaysAllowed;
import com.nexusai.application.agent.permission.check.CheckLayer3_PassthroughToAsk;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.ClassifierUsage;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.classifier.SafeToolWhitelist;
import com.nexusai.application.agent.permission.classifier.TurnClassifierStats;
import com.nexusai.application.agent.permission.classifier.YoloClassifier;
import com.nexusai.application.agent.permission.classifier.YoloClassifierResult;
import com.nexusai.application.agent.permission.explainer.PermissionMessageGenerator;
import com.nexusai.application.agent.permission.hook.AbortException;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.telemetry.McpServerToolSanitizer;
import com.nexusai.application.agent.tool.AgentToolConstants;
import com.nexusai.application.agent.tool.Notification;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 权限管线 · 对齐 CC {@code utils/permissions/permissions.ts:1158-1319}
 * 中 {@code hasPermissionsToUseToolInner} 的 10 层规则检查编排。
 *
 * <h2>职责</h2>
 * <p>编排 10 层规则检查，按顺序调用，第一个非 null 结果即最终决策。
 * 本类不持有任何状态（10 个 layer 都是无状态对象），线程安全。
 *
 * <h2>10 层顺序（CC 源码严格对齐）</h2>
 * <ol>
 *   <li><strong>1a</strong> 整个工具 deny（先于 bypass）</li>
 *   <li><strong>1b</strong> 整个工具 ask（先于 bypass）</li>
 *   <li><strong>1c</strong> 工具自己 checkPermissions（先于 bypass）</li>
 *   <li><strong>1d</strong> 工具 deny（<strong>bypass-immune</strong> ✅）</li>
 *   <li><strong>1e</strong> requiresUserInteraction + ask（<strong>bypass-immune</strong> ✅）</li>
 *   <li><strong>1f</strong> 内容特定 ask rule（<strong>bypass-immune</strong> ✅）</li>
 *   <li><strong>1g</strong> safetyCheck 违规（<strong>bypass-immune</strong> ✅）</li>
 *   <li><strong>2a</strong> bypass mode（不是 bypass-immune —— 这就是 bypass 检查）</li>
 *   <li><strong>2b</strong> 整个工具 allow（不是 bypass-immune）</li>
 *   <li><strong>3</strong> passthrough → ask 兜底（不是 bypass-immune）</li>
 * </ol>
 *
 * <h2>执行语义</h2>
 * <p>按 1a → 1b → ... → 3 严格顺序检查，第一个非 null PermissionResult 即返回。
 * 由于第 3 层是兜底（永远返回 Ask），Pipeline 实际不可能返回 null。
 * 如果全部返回 null（异常），抛 IllegalStateException（CLAUDE.md 规则十二）。
 *
 * <h2>[S12] auto-mode 决策链</h2>
 * <p>Ask 结果在返回前进入 {@link #tryAutoModeDecision}（对齐 CC hasPermissionsToUseTool
 * permissions.ts:520-927）：入口门（mode==auto / plan+active + feature 门）→
 * safetyCheck/requiresUserInteraction/PowerShell 豁免 → allowlist → 分类器
 * （fail-closed + iron-gate deny）。任意 allow 事件断连拒链（R4 恢复）。
 *
 * <h2>为什么不直接堆叠 if-else</h2>
 * <p>10 层独立类：
 * <ul>
 *   <li>每层单测独立（{@code CheckLayer1a_DenyRuleTest} 等）</li>
 *   <li>Pipeline 仅做"按顺序调用"的编排，逻辑清晰</li>
 *   <li>未来插入新层（如 1h post-tool check）只需扩展 layers list</li>
 * </ul>
 */
@Component
public class PermissionPipeline {

    /**
     * SLF4J logger。仅在 debug 时打印，便于排查"哪一层命中"。
     */
    private static final Logger log = LoggerFactory.getLogger(PermissionPipeline.class);

    /** CC AGENT_TOOL_NAME（AgentTool/constants.ts:1，AgentToolConstants.java:30）· acceptEdits fast-path 排除用。 */
    private static final String AGENT_TOOL_NAME = AgentToolConstants.AGENT_TOOL_NAME;
    /** CC REPL_TOOL_NAME（REPLTool/constants.ts:37-46，ToolNameConstants.java:111）· acceptEdits fast-path 排除用。 */
    private static final String REPL_TOOL_NAME = ToolNameConstants.REPL_TOOL_NAME;
    /** CC POWERSHELL_TOOL_NAME（tools/PowerShellTool/toolName.ts:2）· PowerShell 守卫用。 */
    private static final String POWERSHELL_TOOL_NAME = "PowerShell";

    /**
     * 10 层按顺序排列的不可变列表。
     *
     * <p>不可变（{@code List.of}）：防止外部 mutate 改变执行顺序。
     */
    private final List<CheckLayer> layers;

    /** 1b 层实例（保留引用，供 Spring {@code @PostConstruct} 接线 messageGenerator）。 */
    private final CheckLayer1b_AskRule layer1b;

    /** 1c 层实例（保留引用，供 Spring {@code @PostConstruct} 接线 inputValidator + messageGenerator）。 */
    private final CheckLayer1c_ToolCheck layer1c;

    /** 3 兜底层实例（保留引用，供 Spring {@code @PostConstruct} 接线 messageGenerator）。 */
    private final CheckLayer3_PassthroughToAsk layer3;

    /**
     * 权限弹窗消息生成器（@Autowired 字段注入）· 对齐 CC {@code createPermissionRequestMessage}
     * (permissions.ts:137-211)。字段注入后于构造器执行，故由 {@link #wireMessageGenerator()}
     * 在 {@code @PostConstruct} 阶段接线到 1b/3 层；未注入（测试/manual new）时层内默认实例兜底。
     */
    @Autowired
    private PermissionMessageGenerator messageGenerator;

    /**
     * 无参构造（SandboxManager 为 null，sandbox 语义关闭）。
     *
     * <p>1b 层 sandbox fall-through 通过 null 检查关闭（ask 一律 Ask）。
     */
    public PermissionPipeline() {
        this(null);
    }

    /**
     * 构造器注入 SandboxManager（生产路径）。
     *
     * <p>[S02] SandboxManager 通过构造器注入到 1b 层（{@link CheckLayer1b_AskRule}），
     * 对齐 CC permissions.ts:1189-1193 的 canSandboxAutoAllow 四条件——
     * 仅 Bash + sandbox auto-allow 场景 fall-through 到工具 checkPermissions。
     *
     * @param sandboxManager Bash 沙箱管理器（可为 null，null = sandbox 语义关闭）
     */
    @Autowired(required = false)
    public PermissionPipeline(SandboxManager sandboxManager) {
        this.layer1b = new CheckLayer1b_AskRule();
        this.layer1b.setSandboxManager(sandboxManager);
        this.layer1c = new CheckLayer1c_ToolCheck();
        this.layer3 = new CheckLayer3_PassthroughToAsk();
        this.layers = List.of(
            new CheckLayer1a_DenyRule(),            // 1a — whole-tool deny
            layer1b,                                // 1b — whole-tool ask（S02: Bash+sandbox fall-through）
            layer1c,                                // 1c — tool.checkPermissions（safeParseSchema 门 + 无 Allow 早返）
            new CheckLayer1d_ToolDeny_Immune(),     // 1d — tool deny (bypass-immune)
            new CheckLayer1e_RequiresUserInteraction(), // 1e — requires UI (bypass-immune)
            new CheckLayer1f_ContentSpecificAskRule(),  // 1f — content-specific ask (bypass-immune)
            new CheckLayer1g_SafetyCheck(),         // 1g — safetyCheck (bypass-immune)
            new CheckLayer2a_BypassMode(),          // 2a — BYPASS_PERMISSIONS mode
            new CheckLayer2b_ToolAlwaysAllowed(),   // 2b — whole-tool allow rule
            layer3                                  // 3  — fallback passthrough -> ask
        );
    }

    /**
     * [F4a] 接线消息生成器到 1b/1c/3 层 · 对齐 CC {@code createPermissionRequestMessage}
     * (permissions.ts:137-211)。字段注入后于构造器执行，故在此阶段接线。
     * 未注入（测试/manual new）时 messageGenerator=null，层内默认实例兜底，行为一致。
     */
    @jakarta.annotation.PostConstruct
    void wireMessageGenerator() {
        layer1b.setMessageGenerator(messageGenerator);
        layer1c.setMessageGenerator(messageGenerator);
        layer3.setMessageGenerator(messageGenerator);
        if (log.isDebugEnabled()) {
            log.debug("PermissionPipeline: PermissionMessageGenerator 已接线到 1b/1c/3 层 (messageGenerator={})",
                messageGenerator != null);
        }
    }


    // ── s04: Auto Mode 组件（@Autowired(required=false) 向后兼容）──
    @Autowired(required = false)
    AutoModeGate autoModeGate;
    @Autowired(required = false)
    SafeToolWhitelist safeToolWhitelist;
    @Autowired(required = false)
    YoloClassifier yoloClassifier;

    /**
     * 全局拒绝追踪器（appState.denialTracking 等价 · 主 agent 路径）。
     *
     * <p>[IMP-9 / OPD-WF3-01-14] 子代理 per-agent 隔离：CC 双态解析
     * {@code context.localDenialTracking ?? appState.denialTracking}
     * （permissions.ts:556-558），本字段仅在 {@code ctx.localDenialTracking()==null}
     * （主 agent / 无本地态）时经 {@link #resolveDenialTracker} 回落；子代理经
     * localDenialTracking 独立计数，不污染本 bean（forkedAgent.ts:420-422）。
     */
    @Autowired(required = false)
    DenialTracker denialTracker;

    /**
     * 解析当前调用的拒绝追踪器 · 对齐 CC permissions.ts:556-558
     * {@code context.localDenialTracking ?? appState.denialTracking ?? createDenialTrackingState()}。
     *
     * <p>子代理 TUC 携带非 null localDenialTracking（with()/createSubagentContext 注入，
     * forkedAgent.ts:420-422 非 share 子代理新建独立状态）→ 返回绑定该 Map 的独立
     * DenialTracker（子代理独立计数，不污染全局 bean）；主 agent TUC localDenialTracking
     * 为 null（LlmAgentLoop:6291）→ 回落全局 bean（appState.denialTracking 等价）。
     *
     * @param ctx 当前工具调用上下文（可为 null）
     * @return 解析出的 tracker（可能为 null —— 无全局 bean 且无本地状态）
     */
    private DenialTracker resolveDenialTracker(ToolUseContext ctx) {
        if (ctx != null && ctx.localDenialTracking() != null) {
            return DenialTracker.forLocalState(ctx.localDenialTracking());
        }
        return denialTracker;
    }

    /**
     * [P3 · OPD-WF3-01-09] ant 分类器错误通知 · 对齐 CC permissions.ts:704-716
     * {@code context.addNotification({key: 'auto-mode-error-dump', text: 'Auto mode classifier error — prompts dumped to ${errorDumpPath} (included in /share)', priority: 'immediate', color: 'error'})}。
     *
     * <p>Java Notification 为 payload 契约（前端渲染，ToolPermissionGate.notifyAutoModeDenied 同款），
     * title/body/level 对齐 CC 文本与 error 级别；id 带 nanoTime 后缀防 React dedupe 吞新通知。
     *
     * @param ctx           工具调用上下文（addNotification 回调）
     * @param errorDumpPath 分类器错误 prompts dump 路径（CC errorDumpPath，非空才推送）
     */
    void pushClassifierErrorDumpNotification(ToolUseContext ctx, String errorDumpPath) {
        try {
            ctx.addNotification().accept(new Notification(
                "auto-mode-error-dump-" + System.nanoTime(),
                "Auto mode classifier error",
                "Auto mode classifier error — prompts dumped to " + errorDumpPath + " (included in /share)",
                Notification.Level.ERROR));
            if (log.isInfoEnabled()) {
                log.info("AUTO MODE: ant 分类器错误通知已推送（CC permissions.ts:705-716）: errorDumpPath={}", errorDumpPath);
            }
        } catch (Throwable th) {
            if (log.isWarnEnabled()) {
                log.warn("AUTO MODE: 分类器错误通知推送失败: {}", th.toString());
            }
        }
    }

    /**
     * [P3] 统一 ant 标签门控 · 复用 MockRateLimits/SpeculationEngine 既有先例
     * （{@code System.getenv("USER_TYPE")==='ant'}，拍板不新建独立 feature 配置）。
     *
     * @return true = USER_TYPE 环境变量为 'ant'
     */
    private static boolean isAntUserType() {
        return "ant".equals(System.getenv("USER_TYPE"));
    }

    /**
     * [S10] 接线 ClassifierApprovals 的 TRANSCRIPT_CLASSIFIER 静态门 · 对齐 CC
     * feature('TRANSCRIPT_CLASSIFIER')（classifierApprovals.ts:45/63-69）。
     * S12 确认 AutoModeGate.isEnabled()（nexusai.auto-mode.enabled）为 Java 等价门；
     * 未注入 AutoModeGate（测试/手动构造）→ null（门开，与既有 wireBashClassifierGate 约定一致）。
     */
    @jakarta.annotation.PostConstruct
    void wireTranscriptClassifierGate() {
        ClassifierApprovals.wireTranscriptClassifierGate(
            autoModeGate != null ? v -> autoModeGate.isEnabled() : null);
        if (log.isDebugEnabled()) {
            log.debug("PermissionPipeline: ClassifierApprovals TRANSCRIPT_CLASSIFIER 门已接线 (autoModeGate={})",
                autoModeGate != null);
        }
    }

    // ── [OPD-WF3-01-08] 1c inputSchema.parse 门校验器 + [OPD-WF3-01-05] 遥测 bean ──
    /**
     * 工具输入验证器 · {@code null} = 未注入（测试/手动构造）。
     *
     * <p>[OPD-WF3-01-08] 1c 层 safeParseSchema 门依赖（对齐 CC permissions.ts:1215
     * {@code tool.inputSchema.parse}）；由容器注入到 {@link CheckLayer1c_ToolCheck}。
     */
    @Autowired(required = false)
    private ToolInputValidator inputValidator = new ToolInputValidator();

    /**
     * 遥测 bean · {@code null} = 未注入（测试/手动构造）。
     *
     * <p>[OPD-WF3-01-05 / OPD-WF6-06] 补发 {@code tengu_auto_mode_decision} /
     * {@code tengu_auto_mode_denial_limit_exceeded} 遥测事件（对齐 CC permissions.ts:626/666/733/1009）。
     *
     * <p>package-private（无修饰符）：与 autoModeGate/safeToolWhitelist 等 s04 字段一致，
     * 便于同包测试注入 spy 验证发射字段（PermissionPipelineTelemetryTest）。
     */
    @Autowired(required = false)
    com.nexusai.application.agent.telemetry.Telemetry telemetry;

    /**
     * 回合分类器耗时统计 · {@code null} = 未注入（测试/手动构造）。
     *
     * <p>[OPD-WF3-01-16] 补耗时遥测：对齐 CC permissions.ts:814-816
     * {@code if (classifierResult.durationMs !== undefined) { addToTurnClassifierDuration(classifierResult.durationMs) }}
     * —— 每次 auto-mode 分类器调用后累计到会话回合级耗时统计（CC state.ts:627-630）。
     */
    @Autowired(required = false)
    TurnClassifierStats turnClassifierStats;

    /**
     * [OPD-WF3-01-08] 接线输入验证器到 1c 层 · 对齐 CC zod {@code inputSchema.parse} 门。
     * 字段注入后于构造器执行，故在此阶段接线；未注入（测试/manual new）时 1c 层默认实例兜底。
     */
    @jakarta.annotation.PostConstruct
    void wireInputValidator() {
        layer1c.setInputValidator(inputValidator);
        if (log.isDebugEnabled()) {
            log.debug("PermissionPipeline: ToolInputValidator 已接线到 1c 层 (inputValidator={})",
                inputValidator != null);
        }
    }

    /**
     * 执行 10 层检查。
     *
     * @param tool     工具实例
     * @param call     LLM 的工具调用
     * @param input    已解析 JSON 输入
     * @param ctx      工具调用上下文
     * @param permCtx  权限上下文（8 source 合并结果）
     * @return         第一个非 null 的 PermissionResult（实际必非 null，因第 3 层兜底）
     * @throws IllegalStateException 若 10 层全部返回 null（理论上不可能）
     */
    public PermissionResult check(
            Tool tool,
            ToolUseBlock call,
            JsonNode input,
            ToolUseContext ctx,
            ToolPermissionContext permCtx
    ) {
        if (log.isDebugEnabled()) {
            log.debug("PermissionPipeline.check start: tool={} callId={}",
                tool.name(), call.id());
        }
        // [IMP-9 / OPD-WF3-DC-v4-02] ToolCheckCache.clear() 生产接入 · 对齐 ToolCheckCache Javadoc
        //   「applyPermissionFilter 入口调用,清 per-call cache」声明 + 防 multi-agent 跨调用污染
        //   （?-DC-2：CC 单函数 in-scope 共享无缓存概念，Java ThreadLocal per-call cache 必须
        //   在每次 check 入口清空，确保 1c/1d/1e 仅共享当前 call 的 toolPermissionResult，不跨
        //   call / 跨子代理泄漏）。
        ToolCheckCache.clear();
        // [OPD-WF3-DC-v4-07] 入口 abort 预检 · 对齐 CC permissions.ts:1163-1165
        //   hasPermissionsToUseToolInner 入口 `if (context.abortController.signal.aborted)
        //   { throw new AbortError() }` —— 收到中止信号直接抛 AbortException 中止 agent，
        //   不再跑 10 层管线（纯 Allow 路径不感知 abort 的现状风险，M-118/?-DC-3）。
        //   Java 映射：ToolUseContext.abortController()（构造器 null 回落 NOOP，
        //   NOOP.isCancelled() 恒 false，见 ToolUseContext:311-312）。
        if (ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled()) {
            if (log.isWarnEnabled()) {
                log.warn("PermissionPipeline.check: 检测到 abort 信号 → 抛 AbortException 中止 "
                        + "(tool={} callId={})",
                    tool.name(), call.id());
            }
            throw new AbortException(
                "Agent aborted: abort signal received before permission check");
        }
        // 严格按顺序调用 10 层 —— 第 i 层（0-indexed）对应 CC 编号 i+1
        for (int i = 0; i < layers.size(); i++) {
            CheckLayer layer = layers.get(i);
            // 第 i 层执行检查；返回 null 表示未命中，继续下一层
            PermissionResult result = layer.check(tool, call, input, ctx, permCtx);
            if (result != null) {
                if (log.isDebugEnabled()) {
                    log.debug("PermissionPipeline.check hit layer {}: tool={} decision={}",
                        i + 1, tool.name(), result.getClass().getSimpleName());
                }
                // [S12 R1] Ask 结果 → 尝试 auto-mode 自动决策（入口门 + 豁免 + 分类器）。
                //   对齐 CC hasPermissionsToUseTool：入口门/豁免 permissions.ts:520-591，
                //   分类器结果处理 :688-927。非 auto/plan+active 模式 → 不咨询分类器。
                if (result instanceof PermissionResult.Ask) {
                    result = tryAutoModeDecision(result, tool, call, input, ctx, permCtx);
                }
                // [S12 R4] CC permissions.ts:486-499 — auto 模式下任意 allow 事件断连拒链
                //   （recordSuccess 只清 consecutive；任意 allow 恢复 CIRCUIT_BROKEN 熔断）。
                //   [IMP-9] resolveDenialTracker：子代理经 localDenialTracking 独立记录，
                //   不污染全局 bean（CC permissions.ts:490 local 优先）。
                if (result instanceof PermissionResult.Allow
                        && isStrictAutoMode(permCtx)) {
                    DenialTracker tracker = resolveDenialTracker(ctx);
                    if (tracker != null) {
                        tracker.recordSuccess();
                        if (log.isDebugEnabled()) {
                            log.debug("AUTO MODE: allow 事件断连拒链 recordSuccess (tool={})", tool.name());
                        }
                    }
                }
                return result;
            }
        }
        // 理论上不会到这里（第 3 层兜底永远返回 Ask），但 fail loud（CLAUDE.md 规则十二）
        throw new IllegalStateException("No layer decided — all " + layers.size() + " layers returned null");
    }

    // ─────────────────── [S12] auto-mode 决策链（R1 入口门 + 豁免 + R3 fail-closed + R4 恢复）───────────────

    /**
     * auto-mode 入口门 · 对齐 CC permissions.ts:520-525
     * {@code feature('TRANSCRIPT_CLASSIFIER') && (mode==='auto' || (mode==='plan' && isAutoModeActive()))}。
     *
     * <p>Java 映射：feature 门 = {@link AutoModeGate#isEnabled()}（nexusai.auto-mode.enabled，
     * 与既有接线一致）；plan+active 判定 = {@code infra.util.AutoModeState.isAutoModeActive()}
     * （CC autoModeState.ts 模块级单例的 Java 静态移植，S10 负责 transition 写入）。
     */
    private boolean isAutoModeEntry(ToolPermissionContext permCtx) {
        if (autoModeGate == null || !autoModeGate.isEnabled()) {
            return false;
        }
        PermissionMode mode = permCtx != null ? permCtx.mode() : null;
        return mode == PermissionMode.AUTO
            || (mode == PermissionMode.PLAN
                && com.nexusai.infra.util.AutoModeState.isAutoModeActive());
    }

    /**
     * 严格 auto 模式判定（不含 plan+active）· 对齐 CC permissions.ts:492
     * {@code toolPermissionContext.mode === 'auto'}（recordSuccess 恢复仅限严格 auto）。
     */
    private boolean isStrictAutoMode(ToolPermissionContext permCtx) {
        if (autoModeGate == null || !autoModeGate.isEnabled()) {
            return false;
        }
        PermissionMode mode = permCtx != null ? permCtx.mode() : null;
        return mode == PermissionMode.AUTO;
    }

    /**
     * [WF2-R2] 5 参重载 · 反射测试 {@code R32B12_AutoClassifierR1R4Test} 依赖此签名
     * 直调免疫分支 —— 委托 6 参（call=null，仅免疫/守卫早退分支可达，不触及分类器）。
     */
    private PermissionResult tryAutoModeDecision(
            PermissionResult result, Tool tool, JsonNode input,
            ToolUseContext ctx, ToolPermissionContext permCtx) {
        return tryAutoModeDecision(result, tool, null, input, ctx, permCtx);
    }

    /**
     * auto-mode 自动决策 · 对齐 CC hasPermissionsToUseTool 的 auto 分支
     * （permissions.ts:520-591 入口/豁免 + :688-927 分类器结果处理）。
     *
     * <p>决策链顺序（CC 源码严格顺序；Java 无 acceptEdits fast-path —— X-14 未接线，
     * 06-deletion-manifest O49 登记）：
     * <ol>
     *   <li>入口门（{@link #isAutoModeEntry}）</li>
     *   <li>熔断门：CIRCUIT_BROKEN → 回退 prompting（Java 对 CC shouldFallbackToPrompting
     *       派生查询的门控载体，R4 恢复见 recordSuccess 接线）</li>
     *   <li>1g safetyCheck 非 classifierApprovable → 免疫全部自动放行路径（:532-548）</li>
     *   <li>1e requiresUserInteraction → 保留 ask（:549-551）</li>
     *   <li>PowerShell 守卫：跳过分类器、保留 ask（:572-591，POWERSHELL_AUTO_MODE 默认 off）</li>
     *   <li>安全工具白名单 → allow（:658-686）</li>
     *   <li>分类器调用 + 结果处理（:688-927）</li>
     * </ol>
     *
     * <p>[WF2-R2] 6 参（生产路径）· call 提供 toolUseID，对齐 CC permissions.ts:690-701
     * setClassifierChecking/clearClassifierChecking 成对语义。
     *
     * @return 原 Ask（不自动放行）或新的 Allow/Deny/回退 Ask
     */
    private PermissionResult tryAutoModeDecision(
            PermissionResult result, Tool tool, ToolUseBlock call, JsonNode input,
            ToolUseContext ctx, ToolPermissionContext permCtx) {
        // 1. 入口门（R1）：仅 auto / plan+autoActive + feature 门
        if (!isAutoModeEntry(permCtx)) {
            return result;
        }
        // [OPD-AM-02] 删除预熔断门——CC 只在分类器 block 后的 handleDenialLimitExceeded
        //   内查 shouldFallbackToPrompting（permissions.ts:995），分类器前无熔断检查
        //   （permissions.ts:688-702 无条件咨询分类器）。Java 原前置熔断（:318）偏离 CC，
        //   熔断判定收敛到下方 recordDenial 后的 snapshot.fallback()（等价 CC :995 后置）。
        // 2. 1g safetyCheck 非 classifierApprovable → 免疫自动放行（CC permissions.ts:532-548）
        if (((PermissionResult.Ask) result).reason() instanceof PermissionDecisionReason.SafetyCheck safetyCheck
                && !safetyCheck.classifierApprovable()) {
            if (log.isDebugEnabled()) {
                log.debug("AUTO MODE: safetyCheck 非 classifierApprovable → 免疫自动放行 (tool={})",
                    tool.name());
            }
            return result;
        }
        // 4. 1e requiresUserInteraction → 保留 ask（CC :549-551）
        if (tool.requiresUserInteraction()) {
            if (log.isDebugEnabled()) {
                log.debug("AUTO MODE: requiresUserInteraction → 保留 ask (tool={})", tool.name());
            }
            return result;
        }
        // 5. PowerShell 守卫（CC :572-591，POWERSHELL_AUTO_MODE 默认 off → 跳过分类器）
        if (POWERSHELL_TOOL_NAME.equals(tool.name())) {
            if (log.isDebugEnabled()) {
                log.debug("AUTO MODE: PowerShell 工具 → 跳过分类器，保留 ask (tool={})", tool.name());
            }
            return result;
        }

        // 5.5 [S10 X-14] acceptEdits fast-path（CC permissions.ts:600-656）—— 用真实
        //   tool.checkPermissions({mode:'acceptEdits'})：acceptEdits 模式下会放行的动作
        //   不浪费分类器 API 调用（CC :593-595 注释 "check if acceptEdits mode would allow
        //   this action"）。旧硬编码启发式已由本路径取代（O49，删除归 S13）。
        //   Agent/REPL 排除（CC :600-604）：其 checkPermissions 在 acceptEdits 模式返回
        //   'allow'，会静默绕过分类器（REPL 代码可在内部工具调用间做 VM 逃逸）。
        if (ctx != null
                && !AGENT_TOOL_NAME.equals(tool.name())
                && !REPL_TOOL_NAME.equals(tool.name())) {
            try {
                ToolUseContext acceptEditsCtx = withAcceptEditsContext(ctx, permCtx);
                PermissionResult acceptEditsResult = tool.checkPermissions(input, acceptEditsCtx);
                if (acceptEditsResult instanceof PermissionResult.Allow allow) {
                    // CC :620-622 —— recordSuccess 断连拒链（R4 恢复语义；
                    //   resolveDenialTracker null-safe，测试/手动构造无 tracker）
                    DenialTracker tracker = resolveDenialTracker(ctx);
                    if (tracker != null) {
                        tracker.recordSuccess();
                    }
                    if (log.isInfoEnabled()) {
                        log.info("AUTO MODE: acceptEdits fast-path allow tool={}（跳过分类器）",
                            tool.name());
                    }
                    // [OPD-WF3-01-05] 补发 tengu_auto_mode_decision（CC :626-640）
                    emitAutoModeDecision(tool.name(), "allowed", "acceptEdits", null, null);
                    // CC :641-648 —— allow + updatedInput（acceptEditsResult.updatedInput ?? input，
                    //   Java Allow.updatedInput 非空）+ decisionReason = Mode(auto)
                    return new PermissionResult.Allow(
                        allow.updatedInput(),
                        new PermissionDecisionReason.Mode(PermissionMode.AUTO),
                        null, false, null, null);
                }
            } catch (AbortException e) {
                // [OPD-WF3-DC-v4-07] CC :650-655 —— AbortError/APIUserAbortError 显式重抛
                //   （中止 agent），不落入分类器（M-079/△-20）。旧实现 :412 catch(Exception)
                //   吞掉 AbortException 落分类器，用户中止意图丢失。
                if (log.isWarnEnabled()) {
                    log.warn("AUTO MODE: acceptEdits fast-path AbortException 透传中止 (tool={}): {}",
                        tool.name(), e.getMessage());
                }
                throw e;
            } catch (Exception e) {
                // CC :650-655 —— acceptEdits 检查失败 → fall through 到分类器
                if (log.isDebugEnabled()) {
                    log.debug("AUTO MODE: acceptEdits fast-path 失败，落入分类器 (tool={}): {}",
                        tool.name(), e.getMessage());
                }
            }
        }
        // 6. 安全工具白名单 → auto allow（CC :658-686 allowlist fast path；
        //    decisionReason = {type:'mode', mode:'auto'}，CC :678-685）
        if (safeToolWhitelist != null && safeToolWhitelist.isSafe(tool.name())) {
            if (log.isInfoEnabled()) {
                log.info("AUTO MODE: 安全工具 {} → allow (allowlist)", tool.name());
            }
            // [OPD-WF3-01-05] 补发 tengu_auto_mode_decision（CC :666-677）
            emitAutoModeDecision(tool.name(), "allowed", "allowlist", null, null);
            return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Mode(PermissionMode.AUTO),
                null, false, null, null);
        }
        // 7. 分类器（CC :688-702 classifyYoloAction + :818-927 结果处理）
        if (yoloClassifier == null || !yoloClassifier.isAvailable()) {
            return result;
        }
        // [WF2-R2] toolUseID 取 call.id()：CC 端 permissions.ts:690/701 以同一 toolUseID
        //   作 set/clear 键；Java 主链 ToolUseContext 构造处 toolUseId 恒 null
        //   （LlmAgentLoop.java:4977），故不能用 ctx.toolUseId()。5 参反射路径 call=null
        //   → 回退 ctx.toolUseId()（测试/兼容，免疫早退前不触及）。
        String toolUseId = call != null ? call.id() : (ctx != null ? ctx.toolUseId() : null);
        try {
            // [S12 R2] 生产调用传全量 messages（CC permissions.ts:694-699 context.messages），
            //   替换旧 java.util.List.of() 空转录（探查 R2 空洞化）。
            List<ChatMessageDto> messages = fullTranscript(ctx);
            // [WF2-R2] CC permissions.ts:690 —— classifyYoloAction 前 setClassifierChecking
            //   （auto-mode 活路径；checking 指示器仅覆盖 classify 网络调用期间）。
            if (log.isDebugEnabled()) {
                log.debug("AUTO MODE: classifier checking 开始（数据流: toolUseId 写入 CHECKING 集）tool={} toolUseId={}",
                    tool.name(), toolUseId);
            }
            ClassifierApprovals.setClassifierChecking(toolUseId, null);
            var classifierResult = yoloClassifier.classify(
                tool.name(), input, messages, ctx).get();
            // [P3 OPD-WF3-01-09] 分类器出错 ant 通知 · 对齐 CC permissions.ts:704-716
            //   `if (USER_TYPE==='ant' && classifierResult.errorDumpPath && context.addNotification)`
            //   → addNotification({key:'auto-mode-error-dump', ...})。错误 prompts 已由
            //   YoloClassifierImpl 在非 abort 分类错误时 dump（yoloClassifier.ts:961-965），
            //   本处仅消费 errorDumpPath 推前端通知（/share 收集提示）。
            if (isAntUserType() && classifierResult.errorDumpPath() != null
                    && ctx != null && ctx.addNotification() != null) {
                pushClassifierErrorDumpNotification(ctx, classifierResult.errorDumpPath());
            }
            if (log.isInfoEnabled()) {
                // [S10] PromptLengths 分桶消费（CC permissions.ts:759-763 —— classifier 遥测
                //   classifierSystemPromptLength/classifierToolCallsLength/classifierUserPromptsLength；
                //   Java 无遥测事件系统，以数据流日志消费）
                log.info("AUTO MODE: classifier 决策 shouldBlock={} unavailable={} transcriptTooLong={} "
                        + "promptLengths.system={} toolCalls={} userPrompts={} tool={}",
                    classifierResult.shouldBlock(), classifierResult.unavailable(),
                    classifierResult.transcriptTooLong(),
                    classifierResult.promptLengths() != null
                        ? classifierResult.promptLengths().systemPromptLength() : -1L,
                    classifierResult.promptLengths() != null
                        ? classifierResult.promptLengths().toolCallsLength() : -1L,
                    classifierResult.promptLengths() != null
                        ? classifierResult.promptLengths().userPromptsLength() : -1L,
                    tool.name());
            }
            // [OPD-WF3-01-05] 补发 tengu_auto_mode_decision（CC permissions.ts:733-812，
            //   每次 classify 后发一次，yoloDecision = unavailable ? 'unavailable' :
            //   shouldBlock ? 'blocked' : 'allowed'）
            String yoloDecision = Boolean.TRUE.equals(classifierResult.unavailable())
                ? "unavailable"
                : (Boolean.TRUE.equals(classifierResult.shouldBlock())
                    || Boolean.TRUE.equals(classifierResult.transcriptTooLong())
                    ? "blocked" : "allowed");
            emitAutoModeDecision(tool.name(), yoloDecision, null, classifierResult,
                resolveDenialTracker(ctx));
            // [OPD-WF3-01-16] 补耗时遥测 · 对齐 CC permissions.ts:814-816
            //   `if (classifierResult.durationMs !== undefined) { addToTurnClassifierDuration(classifierResult.durationMs) }`
            //   —— 每次 auto-mode 分类器调用后累计到会话回合级耗时（CC state.ts:627-630）。
            //   Java durationMs 恒非 null（long），null-safe 由 TurnClassifierStats.add 承担。
            if (turnClassifierStats != null) {
                turnClassifierStats.add(sessionId(ctx), classifierResult.durationMs());
            }

            if (!classifierResult.shouldBlock()) {
                // [WF-1 · DEL-WF1-01] classifier 字段承载 'auto-mode'，触发 CC
                //   toolExecution.ts:1078 `decisionReason.classifier === 'auto-mode'` retry hook。
                //   对齐 CC permissions.ts:921-925 构造侧（classifier: 'auto-mode'）。
                //   reason 对齐 CC :918-925（decisionReason.reason = classifierResult.reason）。
                return new PermissionResult.Allow(
                    input,
                    new PermissionDecisionReason.Classifier("auto-mode",
                        classifierResult.reason()),
                    null, false, null, null);
            }
            // CC :818-842 — transcriptTooLong → headless 抛 AbortError / 非 headless 回退 prompting
            if (Boolean.TRUE.equals(classifierResult.transcriptTooLong())) {
                if (permCtx != null && permCtx.shouldAvoidPermissionPrompts()) {
                    // CC :823-829 —— headless：永久条件（transcript 只增不减），deny-retry-deny
                    //   浪费 token 且永远到不了 denial-limit abort，直接中断（OPD-WF3-01-03）
                    throw new AbortException(
                        "Agent aborted: auto mode classifier transcript exceeded context window in headless mode");
                }
                if (log.isWarnEnabled()) {
                    log.warn("AUTO MODE: transcript 超长 → 回退用户确认 (tool={})", tool.name());
                }
                // CC :834-841 —— 非 headless：{...result, decisionReason:{type:'other',
                //   reason:'Auto mode classifier transcript exceeded context window — falling back to manual approval'}}
                if (result instanceof PermissionResult.Ask ask) {
                    return new PermissionResult.Ask(ask.message(),
                        new PermissionDecisionReason.Other(
                            "Auto mode classifier transcript exceeded context window — falling back to manual approval"),
                        ask.suggestions(), ask.blockedPath(), ask.updatedInput(), ask.metadata(),
                        ask.isBashSecurityCheckForMisparsing(), ask.pendingClassifierCheck(),
                        ask.contentBlocks());
                }
                return result;
            }
            // CC :843-876 — unavailable → iron-gate deny（fail-closed；
            //   tengu_iron_gate_closed 默认 true，Java 无 feature 服务 → 恒 fail-closed，
            //   OPD-PERM-25 RESOLVED）
            if (Boolean.TRUE.equals(classifierResult.unavailable())) {
                if (log.isWarnEnabled()) {
                    log.warn("AUTO MODE: classifier 不可用 → iron-gate deny (tool={})", tool.name());
                }
                return new PermissionResult.Deny(
                    buildClassifierUnavailableMessage(tool.name(), classifierResult.model()),
                    new PermissionDecisionReason.Classifier("auto-mode",
                        "Classifier unavailable"),
                    null);
            }
            // CC :878-911 — 普通 block → recordDenial + 超限回退 / deny
            //   [OPD-AM-02] 熔断检查后置到 deny 后（对齐 CC permissions.ts:995
            //   handleDenialLimitExceeded 内 shouldFallbackToPrompting）；resolveDenialTracker
            //   可能为 null（测试/手动构造，无 pre-check 兜底后需 null-safe）→ 直接 deny
            //   [IMP-9] 子代理经 localDenialTracking 独立计数（CC :556-558 local 优先）
            DenialTracker tracker = resolveDenialTracker(ctx);
            if (tracker != null) {
                DenialTracker.FallbackSnapshot snapshot = tracker.recordDenial();
                if (snapshot.fallback()) {
                    // CC handleDenialLimitExceeded（permissions.ts:984-1058）→ 回退 prompting
                    boolean hitTotal = snapshot.totalDenials() >= tracker.getMaxTotal();
                    boolean isHeadless = permCtx != null && permCtx.shouldAvoidPermissionPrompts();
                    // [OPD-WF3-01-05] 补发 tengu_auto_mode_denial_limit_exceeded（CC :1009）
                    emitDenialLimitExceeded(tool.name(), hitTotal ? "total" : "consecutive",
                        isHeadless ? "headless" : "cli",
                        snapshot.consecutiveDenials(), snapshot.totalDenials());
                    String warning = hitTotal
                        ? snapshot.totalDenials()
                            + " actions were blocked this session. Please review the transcript before continuing."
                        : snapshot.consecutiveDenials()
                            + " consecutive actions were blocked. Please review the transcript before continuing.";
                    if (isHeadless) {
                        // [OPD-AM-03] CC :1023-1027 —— headless + 拒绝超限抛 AbortError 中断，
                        //   不再僵持无人响应的 Ask（对齐 CC 中断语义）
                        throw new AbortException(
                            "Agent aborted: too many classifier denials in headless mode");
                    }
                    if (log.isWarnEnabled()) {
                        log.warn("AUTO MODE: 拒绝次数超限，回退用户确认 (tool={}): {}", tool.name(), warning);
                    }
                    return fallbackAsk((PermissionResult.Ask) result, warning, classifierResult.reason());
                }
            }
            // CC :903-911 — deny（decisionReason.reason = classifierResult.reason）
            return new PermissionResult.Deny(
                buildYoloRejectionMessage(classifierResult.reason()),
                new PermissionDecisionReason.Classifier("auto-mode",
                    classifierResult.reason()),
                null);
        } catch (AbortException e) {
            // [OPD-WF3-DC-v4-07] CC :826-828/:1024-1026 —— headless 中止信号
            //   （transcriptTooLong / denial-limit）显式重抛中止 agent，不转 iron-gate deny。
            //   CC 抛 AbortError 中止 agent；Java 旧实现 :565 catch(Exception) 吞掉转
            //   iron-gate deny（不中止），headless 中止场景 agent 继续运行（M-090/M-112/△-21）。
            //   finally 仍执行（clearClassifierChecking 成对清理），随后异常透传。
            if (log.isWarnEnabled()) {
                log.warn("AUTO MODE: classifier 链 AbortException 透传中止 agent (tool={}): {}",
                    tool.name(), e.getMessage());
            }
            throw e;
        } catch (Exception e) {
            // [S12 R3] 分类器异常（含调用中断）→ iron-gate deny（fail-closed）。
            //   CC classifyYoloAction 全路径不抛（错误收敛为 unavailable 结果），Java 兜底。
            if (log.isWarnEnabled()) {
                log.warn("AUTO MODE: classifier 调用失败 → iron-gate deny (tool={}): {}",
                    tool.name(), e.getMessage());
            }
            return new PermissionResult.Deny(
                buildClassifierUnavailableMessage(tool.name(), null),
                new PermissionDecisionReason.Classifier("auto-mode",
                    "Classifier unavailable"),
                null);
        } finally {
            // [WF2-R2] CC permissions.ts:701 —— finally clearClassifierChecking（与 :690
            //   set 成对，确保 ALLOW/DENY/异常 任何路径都清掉 checking 指示器）。
            ClassifierApprovals.clearClassifierChecking(toolUseId, null);
        }
    }

    /**
     * 提取 ctx 全量消息 · 对齐 CC {@code context.messages}（permissions.ts:694-699）。
     * Java ToolUseContext.messages 为 {@code List<?>}，此处过滤出 ChatMessageDto。
     */
    private static List<ChatMessageDto> fullTranscript(ToolUseContext ctx) {
        if (ctx == null || ctx.messages() == null) {
            return List.of();
        }
        return ctx.messages().stream()
            .filter(m -> m instanceof ChatMessageDto)
            .map(m -> (ChatMessageDto) m)
            .toList();
    }

    /**
     * acceptEdits 模式上下文 · 对齐 CC permissions.ts:607-619 的 context 覆写
     * （{@code {...context, getAppState: () => ({...state, toolPermissionContext:
     * {...state.toolPermissionContext, mode: 'acceptEdits'}})}}）。
     *
     * <p>Java {@link ToolUseContext} 以 {@code permissionMode} / {@code permissionContext}
     * 承载 mode（BashTool.checkPermissions 读 {@code ctx.permissionMode()}，
     * BashTool.java:619-622），故两个字段都覆写为 ACCEPT_EDITS。
     *
     * @param ctx     当前工具调用上下文（fast-path 调用点已保证非 null）
     * @param permCtx 当前权限上下文（8 source 合并结果）
     * @return mode=ACCEPT_EDITS 的上下文（其余字段不变，CC spread 语义）
     */
    private static ToolUseContext withAcceptEditsContext(
            ToolUseContext ctx, ToolPermissionContext permCtx) {
        ToolPermissionContext acceptEditsPermCtx = new ToolPermissionContext(
            PermissionMode.ACCEPT_EDITS,
            permCtx.alwaysAllowRules(),
            permCtx.alwaysDenyRules(),
            permCtx.alwaysAskRules(),
            permCtx.additionalWorkingDirectories(),
            permCtx.isBypassPermissionsModeAvailable(),
            permCtx.isAutoModeAvailable(),
            permCtx.strippedDangerousRules(),
            permCtx.shouldAvoidPermissionPrompts(),
            permCtx.awaitAutomatedChecksBeforeDialog(),
            permCtx.prePlanMode());
        return ctx.withPermissionContext(acceptEditsPermCtx, PermissionMode.ACCEPT_EDITS);
    }

    /**
     * 超限回退 Ask · 对齐 CC handleDenialLimitExceeded 返回
     * {@code {...result, decisionReason: {type:'classifier', classifier, reason: warning + '\n\nLatest blocked action: ' + reason}}}
     * （permissions.ts:1050-1057）。
     */
    private static PermissionResult fallbackAsk(
            PermissionResult.Ask ask, String warning, String classifierReason) {
        // [OPD-WF3-01-03] 对齐 CC handleDenialLimitExceeded 保留 originalClassifier
        //   （permissions.ts:1042-1048）：result.decisionReason?.type === 'classifier'
        //   ? result.decisionReason.classifier : 'auto-mode'。旧实现恒硬编码 'auto-mode'
        //   （MIS：classifier 值如 'dangerous-agent-action' 被丢弃），现在保留原始值。
        String originalClassifier =
            ask.reason() instanceof PermissionDecisionReason.Classifier c
                ? c.classifier() : "auto-mode";
        return new PermissionResult.Ask(
            ask.message(),
            new PermissionDecisionReason.Classifier(originalClassifier,
                warning + "\n\nLatest blocked action: " + classifierReason),
            ask.suggestions(), ask.blockedPath(), ask.updatedInput(), ask.metadata(),
            ask.isBashSecurityCheckForMisparsing(), ask.pendingClassifierCheck(),
            ask.contentBlocks());
    }

    // ─────────────────── [OPD-WF3-01-05] 遥测事件发射 ───────────────────

    /**
     * 补发 {@code tengu_auto_mode_decision} 遥测事件 · 对齐 CC permissions.ts:626/666/733。
     *
     * <p><b>[IMP-7] 字段补齐（OPD-WF3-01-13 拍板：补全字段）</b>：主分类器路径（CC :733-812）
     * 逐字段对齐 —— decision/toolName/inProtectedNamespace/classifierModel/consecutiveDenials/
     * totalDenials/usage 4 字段/durationMs/promptLengths 3 字段/stage/stage1+stage2 各
     * usage 4 字段+durationMs+requestId+msgId。fast-path 路径（acceptEdits :626-640 /
     * allowlist :666-677）对齐 confidence='high' + fastPath（无 classifier 字段）。
     *
     * <p><b>CC 字段映射说明</b>：
     * <ul>
     *   <li>{@code toolName} ← CC {@code sanitizeToolNameForAnalytics(tool.name)}
     *       （metadata.ts:70-77）：mcp__* → mcp_tool（PII 防护，OPD-WF7-01-03）</li>
     *   <li>{@code inProtectedNamespace} ← CC {@code isInProtectedNamespace()}（envUtils.ts:136-148）：
     *       Java 无 ant/COO 概念 → 恒 false（非 ant 构建等价）</li>
     *   <li>{@code consecutiveDenials/totalDenials} ← CC :744-749（shouldBlock 时 pre-recordDenial
     *       语义：+1 前瞻；不 block → 0 / 原 total）</li>
     *   <li><b>未发射（Java 无等价源，登记 gap）</b>：{@code agentMsgId}（assistantMessage.message.id，
     *       pipeline 作用域无 assistantMessage）、{@code sessionInputTokens/OutputTokens/Cache*}
     *       （CC getTotal* 会话累计，Java CostTracker 不暴露 live 累计）、{@code classifierCostUSD}
     *       /{@code classifierStage1/2CostUSD}（需模型成本表，Java 无）</li>
     * </ul>
     *
     * @param toolName         工具名（CC sanitizeToolNameForAnalytics 后入 telemetry）
     * @param decision         'allowed' / 'blocked' / 'unavailable'
     * @param fastPath         'acceptEdits' / 'allowlist' / null（分类器主路径）
     * @param classifierResult 分类器结果（fast-path 传 null；主路径传真实结果）
     * @param tracker          当前调用的拒绝追踪器（resolveDenialTracker 解析结果；
     *                         fast-path 传 null；主路径传 resolved per-agent/global tracker）
     */
    private void emitAutoModeDecision(String toolName, String decision, String fastPath,
            YoloClassifierResult classifierResult, DenialTracker tracker) {
        if (telemetry == null) {
            return;
        }
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        // CC 公共字段（3 个事件皆有：permissions.ts:626/666/733）
        attrs.put("decision", decision);
        // CC sanitizeToolNameForAnalytics（metadata.ts:70-77）：mcp__* → mcp_tool（PII 防护）
        attrs.put("toolName", McpServerToolSanitizer.sanitize(toolName));
        // CC isInProtectedNamespace（envUtils.ts:136-148）：非 ant 构建恒 false
        attrs.put("inProtectedNamespace", false);
        if (fastPath != null) {
            // CC acceptEdits/allowlist fast-path 事件（permissions.ts:626-640/:666-677）
            attrs.put("confidence", "high");
            attrs.put("fastPath", fastPath);
        } else if (classifierResult != null) {
            // CC 主分类器事件（permissions.ts:733-812）——逐字段对齐
            attrs.put("classifierModel", classifierResult.model());
            // denial 计数（CC :744-749）：shouldBlock ? current+1 : 0 / total+1
            //   [IMP-9] 用 resolveDenialTracker 解析的 tracker（per-agent/global），与计数路径一致
            if (tracker != null) {
                boolean blocked = Boolean.TRUE.equals(classifierResult.shouldBlock());
                attrs.put("consecutiveDenials", blocked
                    ? tracker.getConsecutiveDenials() + 1 : 0);
                attrs.put("totalDenials", blocked
                    ? tracker.getTotalDenials() + 1 : tracker.getTotalDenials());
            }
            // usage 4 字段（CC :751-756）
            putClassifierUsage(attrs, "classifier", classifierResult.usage());
            // 耗时（CC :757 classifierDurationMs）
            attrs.put("classifierDurationMs", classifierResult.durationMs());
            // prompt 分桶长度（CC :759-763）
            if (classifierResult.promptLengths() != null) {
                attrs.put("classifierSystemPromptLength", classifierResult.promptLengths().systemPromptLength());
                attrs.put("classifierToolCallsLength", classifierResult.promptLengths().toolCallsLength());
                attrs.put("classifierUserPromptsLength", classifierResult.promptLengths().userPromptsLength());
            }
            // stage（CC :772-773 classifierStage）
            attrs.put("classifierStage", classifierResult.stage());
            // stage1（CC :774-792）
            putClassifierUsage(attrs, "classifierStage1", classifierResult.stage1Usage());
            if (classifierResult.stage1DurationMs() != null) {
                attrs.put("classifierStage1DurationMs", classifierResult.stage1DurationMs());
            }
            if (classifierResult.stage1RequestId() != null) {
                attrs.put("classifierStage1RequestId", classifierResult.stage1RequestId());
            }
            if (classifierResult.stage1MsgId() != null) {
                attrs.put("classifierStage1MsgId", classifierResult.stage1MsgId());
            }
            // stage2（CC :793-811）
            putClassifierUsage(attrs, "classifierStage2", classifierResult.stage2Usage());
            if (classifierResult.stage2DurationMs() != null) {
                attrs.put("classifierStage2DurationMs", classifierResult.stage2DurationMs());
            }
            if (classifierResult.stage2RequestId() != null) {
                attrs.put("classifierStage2RequestId", classifierResult.stage2RequestId());
            }
            if (classifierResult.stage2MsgId() != null) {
                attrs.put("classifierStage2MsgId", classifierResult.stage2MsgId());
            }
        }
        telemetry.logOTelEvent("tengu_auto_mode_decision", attrs);
        telemetry.recordEvent("tengu_auto_mode_decision", attrs);
        if (log.isDebugEnabled()) {
            log.debug("AUTO MODE: 遥测事件 tengu_auto_mode_decision 已补发 (tool={} decision={} fastPath={} attrs={})",
                toolName, decision, fastPath, attrs.keySet());
        }
    }

    /**
     * 填充 usage 4 字段到 attrs · 对齐 CC :751-756/:774-792/:793-811。
     *
     * @param attrs 目标 attrs
     * @param prefix 字段前缀（'classifier' / 'classifierStage1' / 'classifierStage2'）
     * @param usage 分类器 usage（null → 不填，CC usage 可缺省）
     */
    private static void putClassifierUsage(java.util.Map<String, Object> attrs, String prefix,
            ClassifierUsage usage) {
        if (usage == null) {
            return;
        }
        attrs.put(prefix + "InputTokens", usage.inputTokens());
        attrs.put(prefix + "OutputTokens", usage.outputTokens());
        attrs.put(prefix + "CacheReadInputTokens", usage.cacheReadInputTokens());
        attrs.put(prefix + "CacheCreationInputTokens", usage.cacheCreationInputTokens());
    }

    /**
     * 提取 ctx 会话 UUID · 供 TurnClassifierStats 会话分桶（null-safe）。
     *
     * @param ctx 工具调用上下文（可为 null）
     * @return ctx.sessionId()；ctx null → null
     */
    private static String sessionId(ToolUseContext ctx) {
        return ctx != null ? ctx.sessionId() : null;
    }

    /**
     * 补发 {@code tengu_auto_mode_denial_limit_exceeded} 遥测事件 · 对齐 CC permissions.ts:1009-1021
     * （limit/mode/consecutiveDenials/totalDenials/toolName）。
     */
    private void emitDenialLimitExceeded(String toolName, String limit, String mode,
            int consecutiveDenials, int totalDenials) {
        if (telemetry == null) {
            return;
        }
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("limit", limit);
        attrs.put("mode", mode);
        attrs.put("consecutiveDenials", consecutiveDenials);
        attrs.put("totalDenials", totalDenials);
        attrs.put("toolName", toolName);
        telemetry.logOTelEvent("tengu_auto_mode_denial_limit_exceeded", attrs);
        telemetry.recordEvent("tengu_auto_mode_denial_limit_exceeded", attrs);
        if (log.isDebugEnabled()) {
            log.debug("AUTO MODE: 遥测事件 tengu_auto_mode_denial_limit_exceeded 已补发 (tool={} limit={} mode={})",
                toolName, limit, mode);
        }
    }

    /**
     * 分类器拒绝消息 · 对齐 CC {@code buildYoloRejectionMessage}
     * （Open-ClaudeCode/src/utils/messages.ts:267-282）。
     *
     * <p>ruleHint 取 BASH_CLASSIFIER feature 关闭分支（Java 无该 feature，
     * 与 CC 非 ant 构建默认一致）。
     */
    private static String buildYoloRejectionMessage(String reason) {
        String prefix = "Permission for this action has been denied. Reason: ";
        String workaround =
            "IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomplish this goal, "
                + "e.g. using head instead of cat. But you *should not* attempt to work around this denial in malicious ways, "
                + "e.g. do not use your ability to run tests to execute non-test actions. "
                + "You should only try to work around this restriction in reasonable ways that do not attempt to bypass the intent behind this denial. "
                + "If you believe this capability is essential to complete the user's request, STOP and explain to the user "
                + "what you were trying to do and why you need this permission. Let the user decide how to proceed.";
        String ruleHint =
            "To allow this type of action in the future, the user can add a Bash permission rule to their settings.";
        return prefix + reason + ". "
            + "If you have other tasks that don't depend on this action, continue working on those. "
            + workaround + " " + ruleHint;
    }

    /**
     * 分类器不可用消息 · 对齐 CC {@code buildClassifierUnavailableMessage}
     * （Open-ClaudeCode/src/utils/messages.ts:288-298）。
     *
     * <p>model 缺失（Java 异常兜底路径）时以 "The auto mode classifier" 占位。
     */
    private static String buildClassifierUnavailableMessage(String toolName, String classifierModel) {
        String model = classifierModel != null && !classifierModel.isBlank()
            ? classifierModel : "The auto mode classifier";
        return model + " is temporarily unavailable, so auto mode cannot determine the safety of "
            + toolName + " right now. "
            + "Wait briefly and then try this action again. "
            + "If it keeps failing, continue with other tasks that don't require this action and come back to it later. "
            + "Note: reading files, searching code, and other read-only operations do not require the classifier and can still be used.";
    }

    /**
     * 返回 10 层 layer 的快照（不可变 view）。
     *
     * <p>用于：
     * <ul>
     *   <li>测试断言 layer 顺序</li>
     *   <li>调试 / 监控展示当前生效的 10 层</li>
     * </ul>
     *
     * @return 不可变 layer 列表
     */
    public List<CheckLayer> layers() {
        return layers;
    }
}
