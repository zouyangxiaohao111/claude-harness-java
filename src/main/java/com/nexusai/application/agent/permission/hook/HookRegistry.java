package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.mcp.HeadersHelper;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.explainer.PermissionMessageGenerator;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Hook 注册中心 · 管理 PreToolUse / PostToolUse / GenericHook 的注册、注销、执行。
 *
 * <p>P0-3 全量对齐 CC §3.6 (PreToolUse) + §3.10 (PostToolUse) + §14 (GenericHook) hook 系统.
 * Spring {@code @Component}，由 {@code LlmAgentLoop} 自动注入.
 *
 * <h2>注册模型</h2>
 * <p>按 hook <b>name</b> 注册（支持多个 hook）。name 必须唯一.
 *
 * <h2>执行语义 (P0-3 全量对齐 CC AggregatedHookResult)</h2>
 * <ul>
 *   <li><b>PreToolUse</b>: 全量执行所有已注册 hook, 按 16 字段 {@link AggregatedHookResult}
 *       聚合 ([IMPL-07 r3] reason/source last-wins 配对、message/additionalContexts
 *       concat 全保留、blockingError last-wins, 其余字段首非空, boolean any-true).
 *       permissionBehavior 特殊处理:
 *       按 deny > ask > allow 优先级聚合 (对齐 CC hooks.ts:2820-2847).
 *       返回 16 字段 {@link AggregatedHookResult} (对齐 CC toolHooks.ts:435-461 7 类 case union).</li>
 *   <li><b>PostToolUse</b>: 全量执行所有已注册 hook, 按 {@link GenericHook.HookResult}
 *       字段级首非空聚合 (HookResult 层, IMPL-07 未改; 消费端 last-wins 差异已登记
 *       09 §9 后续任务候选). updatedMCPToolOutput 用于 MCP-specific 分流
 *       (对齐 CC toolExecution.ts:1494-1530).</li>
 *   <li><b>GenericHook</b>: 全量执行, 第一个 preventContinuation 的结果被报告.</li>
 * </ul>
 *
 * <h2>循环防护</h2>
 * <p>[R27] 对齐 CC hooks.ts:3634-3643 stopHookActive 透传风格 — hook 自治决定行为.
 * Java 端 hook 不调 hook (无嵌套), stopHookActive 实参永远为 false.
 *
 * <h2>异常策略 (best-effort)</h2>
 * <p>hook 抛异常 → warn 日志 + 跳过 (PreToolUse) 或继续 (PostToolUse / GenericHook).
 * AbortException 透传 (用户中止意图不可吞, 对齐 CC errors.ts:12-17 AbortError +
 * hooks.ts:4812-4818 outcome 'cancelled').
 *
 * @see PreToolUseHook
 * @see PostToolUseHook
 * @see GenericHook
 * @see AggregatedHookResult
 */
@Component
public class HookRegistry implements SessionFileAccessHooks.PostToolUseRegistrar {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    /**
     * [IMP-HR-07 · OPD-WF6-01-05 · 返工 R-1/R-2] session hooks 参与判定 · 取代静态
     * SESSION_HOOK_EVENTS 白名单。
     *
     * <p>CC 真源 getHooksConfig (hooks.ts:1541)：{@code !managedOnly && appState !== undefined}
     * 才并入 session command + function hooks。appState 由发射点传入（hooks.ts:2001
     * {@code const appState = toolUseContext ? toolUseContext.getAppState() : undefined}，
     * 或 executeHooksOutsideREPL :3015 {@code const appState = getAppState ? getAppState() : undefined}）。
     * 发射点<b>传 toolUseContext</b>（PreToolUse/PostToolUse/PostToolUseFailure/PermissionRequest/
     * PermissionDenied/Stop/SubagentStop/StopFailure/TaskCreated/TaskCompleted/UserPromptSubmit）
     * 时 appState 定义；发射点<b>不传</b>（SessionStart/Setup/SubagentStart 无 toolUseContext 参）
     * → appState 恒 undefined → session hooks 排除。<b>SessionEnd 是 appState 发射点</b>：
     * executeSessionEndHooks（hooks.ts:4097-4141）把 {@code getAppState} 传入
     * executeHooksOutsideREPL（:4118）→ appState 可定义；主循环调用方（clear conversation.ts:69
     * / REPL resume :1774 / gracefulShutdown.ts:473）均传非空 getAppState → session hooks 并入
     * —— 与 StopFailure（executeStopFailureHooks hooks.ts:3594-3624 传
     * {@code getAppState: toolUseContext?.getAppState}）同机制。其余 executeHooksOutsideREPL
     * 事件（Notification/ConfigChange/Worktree/Env/Elicitation/PreCompact/PostCompact/
     * InstructionsLoaded 等）调用方不传 getAppState → appState undefined → 排除。
     *
     * <p><b>返工修正（IMP-HR-07 R-1 · 反思 F1）</b>：原实现仅用「会话运行中（RUNNING_SESSIONS）」
     * 近似 CC appState —— 但 Java 在 doRun 内（markRunning 窗口内）发射了 SessionStart
     * （LlmAgentLoop:1896）/Setup（LlmAgentLoop:1943）/SessionEnd（LlmAgentLoop:2200），仅按
     * 「会话运行」会把 SessionStart/Setup 也纳入（相对 CC 与旧白名单双重回归）。R-1 把「事件类型
     * ∈ CC appState 发射点集合（{@link #CC_APP_STATE_PRESENT_EVENTS}）」并入判定 ——
     * SessionStart/Setup/SubagentStart（SubagentExecutor:2873 以主会话 id 发射）显式排除。
     *
     * <p><b>返工修正（IMP-HR-07 R-2 · 反思 F-A）</b>：R-1 把 SessionEnd 一并归为「appState 恒
     * undefined → 排除」，与 CC 真源相反（SessionEnd 是 appState 发射点，见上）。R-2 把
     * {@code SESSION_END} 加入 {@link #CC_APP_STATE_PRESENT_EVENTS}：Java 主 SessionEnd
     * （LlmAgentLoop:2200-2204，doRun 内 isSessionRunning=true）→ session hooks 参与，对齐 CC
     * 主循环 appState 定义行为（clear conversation.ts:69 / resume REPL.tsx:1774 均传 getAppState）。
     *
     * <p>Java 运行时等价 = 双条件：事件类型 ∈ {@link #CC_APP_STATE_PRESENT_EVENTS} + 事件对应
     * sessionId 有活跃 agent 循环（{@link LlmAgentLoop#isSessionRunning}，对齐 CC QueryGuard
     * isQueryActive）。USER_PROMPT_SUBMIT 在 LlmAgentLoop:1962-1993 发射（doRun 内 markRunning
     * 后，且恒传 toolUseContext）→ 自然触发（修复原白名单遗漏；X-PROBE 01-05）。
     */
    private static final Set<HookEventType> CC_APP_STATE_PRESENT_EVENTS = Set.of(
        HookEventType.PRE_TOOL_USE, HookEventType.POST_TOOL_USE,
        HookEventType.POST_TOOL_USE_FAILURE, HookEventType.PERMISSION_REQUEST,
        HookEventType.PERMISSION_DENIED, HookEventType.STOP, HookEventType.SUBAGENT_STOP,
        HookEventType.STOP_FAILURE, HookEventType.TASK_CREATED, HookEventType.TASK_COMPLETED,
        HookEventType.USER_PROMPT_SUBMIT, HookEventType.SESSION_END);

    private boolean isSessionHookEligible(HookEvent event) {
        // CC appState 发射点集合：发射点不传 toolUseContext / getAppState 的事件
        //   （SessionStart/Setup/SubagentStart/Notification/ConfigChange/Worktree/Env/Elicitation/
        //   PreCompact/PostCompact/InstructionsLoaded/TeammateIdle 等，hooks.ts:2001/:3015）→
        //   appState undefined → session hooks 排除。SESSION_END 是 appState 发射点
        //   （executeSessionEndHooks → executeHooksOutsideREPL 传 getAppState，hooks.ts:4118）；
        //   StopFailure 同理（executeStopFailureHooks hooks.ts:3621-3626）→ 均在集合内。事件类型
        //   不在集合内 → 直接排除（即使会话在运行，因 doRun 内也发射了 SessionStart/Setup）。
        if (event == null || !CC_APP_STATE_PRESENT_EVENTS.contains(event.type())) {
            return false;
        }
        // 解析事件对应主会话（RUNNING_SESSIONS 以主会话 UUID 为 key；子代理事件 sessionId 恒主会话）
        String sessionId = event.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = event.agentId();
        }
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        // [session-id-short] RUNNING_SESSIONS 已 String（short）直键，不再 parseSessionUuid
        return com.nexusai.application.agent.LlmAgentLoop.isSessionRunning(sessionId);
    }

    /**
     * [IMP-HR-08 · OPD-WF6-01-06-?-3] 主循环 structured output enforcement function hook 稳定 id。
     *
     * <p>非 CC 常量：CC 每 query() 调用注册一次、跨 query 在 sessionHooks 累积（sessionHooks.ts:93-115
     * addFunctionHook 无按 id 去重）。Java run()=一次用户回合，remove-then-add 用本稳定 id
     * 保证同会话任意时刻至多一条 enforcement hook，避免 Stop 重复注入相同强制提示。
     */
    static final String ENFORCEMENT_FUNCTION_HOOK_ID = "structured-output-enforcement";


    /**
     * [P2-1] hook 错误结构化埋点 · 对齐 CC toolHooks.ts:152-176 (tengu_post_tool_hook_error) +
     * toolHooks.ts:281-304 (tengu_post_tool_failure_hook_error) +
     * toolHooks.ts:604-629 (tengu_pre_tool_hook_error).
     */
    private volatile Telemetry telemetry;

    /**
     * [H1] 配置驱动 hook 匹配引擎 · 对齐 CC getMatchingHooks 执行入口.
     *
     * <p>WHY: settings 多来源配置的 hook 经 {@link MultiSourceHooksConfigLoader} → {@link HooksConfigSnapshot}
     * → 本引擎在 {@link #getMatchingHooks(HookEvent)} 暴露给执行入口. 用 volatile + setter
     * (镜像 {@link #setTelemetry(Telemetry)} 模式), 保持构造器签名不变 (纯增量).
     * {@code @Autowired(required=false)}: Spring 上下文自动注入 (HookMatcherEngine 是
     * {@code @Component}); 手动 new 场景为 null → {@link #getMatchingHooks(HookEvent)} 返回空.
     */
    private volatile HookMatcherEngine hookMatcherEngine;

    @Autowired(required = false)
    public void setHookMatcherEngine(HookMatcherEngine hookMatcherEngine) {
        this.hookMatcherEngine = hookMatcherEngine;
        if (log.isDebugEnabled()) {
            log.debug("HOOK hookMatcherEngine injected: present={}", hookMatcherEngine != null);
        }
    }

    /**
     * [IMPL-01 D1-1] 策略门控快照层 · 提供 shouldDisableAllHooksIncludingManaged /
     * shouldAllowManagedHooksOnly 判定 (对齐 CC hooksConfigSnapshot.ts:62-88).
     *
     * <p>用 volatile + setter (镜像 {@link #setHookMatcherEngine(HookMatcherEngine)} 模式),
     * 保持构造器签名不变. {@code @Autowired(required=false)}: Spring 上下文自动注入
     * (HooksConfigSnapshot 是 {@code @Component}); 手动 new 场景为 null → 门控跳过 (旧行为).
     */
    private volatile HooksConfigSnapshot hooksConfigSnapshot;

    @Autowired(required = false)
    public void setHooksConfigSnapshot(HooksConfigSnapshot hooksConfigSnapshot) {
        this.hooksConfigSnapshot = hooksConfigSnapshot;
        if (log.isDebugEnabled()) {
            log.debug("HOOK hooksConfigSnapshot injected: present={}", hooksConfigSnapshot != null);
        }
    }

    /**
     * [IMPL-01 D1-1] 政策闸门: 是否禁用全部 hook (含 managed) · 对齐 CC
     * shouldDisableAllHooksIncludingManaged (hooksConfigSnapshot.ts:83-88).
     *
     * @return true = policySettings.disableAllHooks==true (未接线快照 → false, 旧行为)
     */
    private boolean shouldDisableAllHooksIncludingManaged() {
        HooksConfigSnapshot snapshot = this.hooksConfigSnapshot;
        return snapshot != null && snapshot.shouldDisableAllHooksIncludingManaged();
    }

    /**
     * [IMPL-01 D1-2/OD-10] 政策闸门: 是否仅允许 managed hooks · 对齐 CC
     * shouldAllowManagedHooksOnly (hooksConfigSnapshot.ts:62-76).
     *
     * @return true = managedOnly 生效 (未接线快照 → false, 旧行为)
     */
    private boolean shouldAllowManagedHooksOnly() {
        HooksConfigSnapshot snapshot = this.hooksConfigSnapshot;
        return snapshot != null && snapshot.shouldAllowManagedHooksOnly();
    }

    /**
     * [IMPL-04 D9-1 / INV-9 / OD-13] workspace trust 门控 supplier · 对齐 CC
     * shouldSkipHookDueToTrust (hooks.ts:286-296) 的两个判定源:
     * <ul>
     *   <li>{@link #nonInteractiveSupplier}: CC {@code getIsNonInteractiveSession()}
     *       (bootstrap/state.ts:1057-1059) 的注入式等价 — true = 非交互会话 (SDK/-p), trust 隐式;</li>
     *   <li>{@link #trustDialogAcceptedSupplier}: CC {@code checkHasTrustDialogAccepted()}
     *       (utils/config.ts) 的注入式等价 — true = 用户已接受 workspace trust dialog.</li>
     * </ul>
     * 类型复用 mcp 域 {@link HeadersHelper.BooleanSupplier} (EV-L01-030: trust 概念已建模于
     * mcp 域 HeadersHelper 但 hook 链未接入 — 本任务接入). 用 volatile + 双参 setter (镜像
     * {@link #setHooksConfigSnapshot(HooksConfigSnapshot)} 模式), 保持构造器签名不变 (纯增量).
     * {@code @Autowired(required=false)}: 生产由 {@code com.nexusai.application.agent.mcp.WorkspaceTrustState}
     * 的 @Bean 提供; 手动 new 场景为 null → 门控跳过 (旧行为, 与策略快照 null 语义一致).
     */
    private volatile HeadersHelper.BooleanSupplier nonInteractiveSupplier;
    private volatile HeadersHelper.BooleanSupplier trustDialogAcceptedSupplier;

    @Autowired(required = false)
    public synchronized void setTrustGateSuppliers(
            @org.springframework.beans.factory.annotation.Qualifier("workspaceNonInteractiveSupplier")
            HeadersHelper.BooleanSupplier nonInteractiveSupplier,
            @org.springframework.beans.factory.annotation.Qualifier("workspaceTrustDialogAcceptedSupplier")
            HeadersHelper.BooleanSupplier trustDialogAcceptedSupplier) {
        if (nonInteractiveSupplier != null) {
            this.nonInteractiveSupplier = nonInteractiveSupplier;
        }
        if (trustDialogAcceptedSupplier != null) {
            this.trustDialogAcceptedSupplier = trustDialogAcceptedSupplier;
        }
        if (log.isDebugEnabled()) {
            log.debug("HOOK trust 门控 supplier 注入: nonInteractive={} trustDialogAccepted={}",
                this.nonInteractiveSupplier != null, this.trustDialogAcceptedSupplier != null);
        }
    }

    /**
     * [IMPL-04 D9-1 / INV-9 / OD-13] trust 门控: 交互模式全部 hook 要求 workspace trust ·
     * 对齐 CC shouldSkipHookDueToTrust (hooks.ts:286-296).
     *
     * <p>CC 真源行为 (hooks.ts:286-296, 不信注释看行为):
     * <pre>
     *   const isInteractive = !getIsNonInteractiveSession()  // 非交互 (SDK/-p) → trust 隐式
     *   if (!isInteractive) return false
     *   const hasTrust = checkHasTrustDialogAccepted()
     *   return !hasTrust                                     // 交互模式: 未接受 trust → 跳过
     * </pre>
     * 历史漏洞 (SessionEnd 在拒绝 trust 后执行, hooks.ts:280-283 注释) 防 RCE 的安全门 —
     * 本方法是集中式检查, 覆盖当前与未来全部 hook 执行入口 (CC 门控位于 executeHooks
     * hooks.ts:1994 与 executeHooksOutsideREPL hooks.ts:3031 两个中央入口).
     *
     * @return true = 应跳过 hook (交互模式且未接受 trust); false = 正常执行
     *         (未接线 supplier → false, 旧行为)
     */
    private boolean shouldSkipHookDueToTrust() {
        HeadersHelper.BooleanSupplier nonInteractive = this.nonInteractiveSupplier;
        HeadersHelper.BooleanSupplier trustAccepted = this.trustDialogAcceptedSupplier;
        if (nonInteractive == null || trustAccepted == null) {
            return false;
        }
        boolean isInteractive = !nonInteractive.getAsBoolean();
        if (!isInteractive) {
            return false;
        }
        return !trustAccepted.getAsBoolean();
    }

    /**
     * [H2] 配置驱动 CommandHook 执行器 · 等价 CC execCommandHook 接线入口.
     *
     * <p>WHY: settings.json 配置的 {@link CommandHook} (type='command') 由
     * {@link #executeEvent(HookEvent)} 分发到本执行器 (CC runHook 等价). 用 volatile + setter
     * (镜像 {@link #setHookMatcherEngine(HookMatcherEngine)} 模式), 保持构造器签名不变 (纯增量).
     * {@code @Autowired(required=false)}: Spring 上下文自动注入 (CommandHookExecutor 是
     * {@code @Component}); 手动 new 场景为 null → 配置驱动 command hook 不执行 (不破坏现有路径).
     */
    private volatile CommandHookExecutor commandHookExecutor;

    @Autowired(required = false)
    public void setCommandHookExecutor(CommandHookExecutor commandHookExecutor) {
        this.commandHookExecutor = commandHookExecutor;
        if (log.isDebugEnabled()) {
            log.debug("HOOK commandHookExecutor injected: present={}", commandHookExecutor != null);
        }
    }

    /**
     * [IMP-CF-03] HooksSettings · statusLine/fileSuggestion 执行器的 policy 配置读取源
     * (CC {@code getSettingsForSource('policySettings')?.statusLine/fileSuggestion},
     * utils/hooks.ts:4608/4698)。
     *
     * <p>[IMP-CF-02 合并 · OPD-WF1-CFG-01] 本 setter 同时承接 getAllHooks(sessionId)
     * 生产接线: 注入 sessionHooksProvider（{@code sessionId -> sessionHookStore.getSessionHooks(sessionId, null)}，
     * 对齐 CC getAllHooks session 合并段 hooksSettings.ts:144-158; 探查 EV-WF1-CFG-061 确认
     * 生产未接线 → 用户拍板接线）。接线后 {@code HooksSettings.getAllHooks(sessionId)} 返回
     * settings + session 合并（UI 展示语义, source=sessionHook）。
     *
     * <p>用 volatile + setter (镜像 {@link #setCommandHookExecutor(CommandHookExecutor)} 模式),
     * 保持构造器签名不变 (纯增量). {@code @Autowired(required=false)}: Spring 上下文自动注入
     * (HooksSettings 是 {@code @Component}); 手动 new 场景为 null → managedOnly 分支 policy
     * statusLine/fileSuggestion 读不到 (返回 null, 不破坏现有路径), sessionHooksProvider 亦不注入
     * (getAllHooks(sessionId) 保持 settings-only, 非 UI 调用不受影响).
     */
    private volatile HooksSettings hooksSettings;

    @Autowired(required = false)
    public void setHooksSettings(HooksSettings hooksSettings) {
        this.hooksSettings = hooksSettings;
        if (hooksSettings != null) {
            // [IMP-CF-02 OPD-WF1-CFG-01] 生产接线 sessionHooksProvider = SessionHookStore 读取器
            //   (CC getAllHooks hooksSettings.ts:144-158, getSessionHooks(appState, sessionId) 无 event 过滤)
            hooksSettings.setSessionHooksProvider(sessionId ->
                sessionHookStore.getSessionHooks(sessionId, null));
        }
        if (log.isDebugEnabled()) {
            log.debug("HOOK hooksSettings injected: present={}, sessionHooksProvider 已接线 (getAllHooks(sessionId) session 合并启用)",
                hooksSettings != null);
        }
    }

    /**
     * [IMP-CF-03] MultiSourceHooksConfigLoader · statusLine/fileSuggestion 执行器的
     * merged settings 读取源 (CC {@code getSettings_DEPRECATED()?.statusLine/fileSuggestion},
     * utils/hooks.ts:4610/4700)。
     *
     * <p>用 volatile + setter (镜像 {@link #setCommandHookExecutor(CommandHookExecutor)} 模式),
     * 保持构造器签名不变 (纯增量). {@code @Autowired(required=false)}: Spring 上下文自动注入
     * (MultiSourceHooksConfigLoader 是 {@code @Component}, 且不依赖本类, 无循环);
     * 手动 new 场景为 null → 非 managedOnly 分支 merged statusLine/fileSuggestion 读不到
     * (返回 null, 不破坏现有路径).
     */
    private volatile MultiSourceHooksConfigLoader hooksConfigLoader;

    @Autowired(required = false)
    public void setHooksConfigLoader(MultiSourceHooksConfigLoader hooksConfigLoader) {
        this.hooksConfigLoader = hooksConfigLoader;
        if (log.isDebugEnabled()) {
            log.debug("HOOK hooksConfigLoader injected: present={}", hooksConfigLoader != null);
        }
    }

    /**
     * [H4] 配置驱动 PromptHook 执行器 · 对齐 CC execPromptHook 接线入口.
     *
     * <p>WHY: settings.json 配置的 {@link PromptHook} (type='prompt') 由
     * {@link #executeEvent(HookEvent)} 分发到本执行器 (CC executeHooks prompt 分支,
     * hooks.ts:2224-2254). 镜像 {@link #setCommandHookExecutor(CommandHookExecutor)} 模式,
     * {@code @Autowired(required=false)}: 手动 new 场景为 null → 配置驱动 prompt hook 不执行
     * (不破坏现有路径).
     */
    private volatile ExecPromptHook execPromptHook;

    @Autowired(required = false)
    public void setExecPromptHook(ExecPromptHook execPromptHook) {
        this.execPromptHook = execPromptHook;
        if (log.isDebugEnabled()) {
            log.debug("HOOK execPromptHook injected: present={}", execPromptHook != null);
        }
    }

    /**
     * [H4] 配置驱动 AgentHook 执行器 · 对齐 CC execAgentHook 接线入口.
     *
     * <p>WHY: settings.json 配置的 {@link AgentHook} (type='agent') 由
     * {@link #executeEvent(HookEvent)} 分发到本执行器 (CC executeHooks agent 分支,
     * hooks.ts:2256-2294). {@code @Autowired(required=false)} 镜像现有模式, null → 不执行.
     */
    private volatile ExecAgentHook execAgentHook;

    @Autowired(required = false)
    public void setExecAgentHook(@org.springframework.context.annotation.Lazy ExecAgentHook execAgentHook) {
        this.execAgentHook = execAgentHook;
        if (log.isDebugEnabled()) {
            log.debug("HOOK execAgentHook injected: present={}", execAgentHook != null);
        }
    }

    /**
     * [H4] 配置驱动 HttpHook 执行器 · 对齐 CC execHttpHook 接线入口.
     *
     * <p>WHY: settings.json 配置的 {@link HttpHook} (type='http') 由
     * {@link #executeEvent(HookEvent)} 分发到本执行器 (CC executeHooks http 分支,
     * hooks.ts:2296-2444). {@code @Autowired(required=false)} 镜像现有模式, null → 不执行.
     */
    private volatile ExecHttpHook execHttpHook;

    @Autowired(required = false)
    public void setExecHttpHook(ExecHttpHook execHttpHook) {
        this.execHttpHook = execHttpHook;
        if (log.isDebugEnabled()) {
            log.debug("HOOK execHttpHook injected: present={}", execHttpHook != null);
        }
    }

    /**
     * [H10 · 对抗核验修复] async hook 注册表 · 生产交付环路接线 (CC attachments.ts:3465).
     *
     * <p><b>WHY (GAP-修复)</b>: 对抗核验发现 {@code checkForAsyncHookResponses} 生产无消费方 —
     * AsyncHookRegistry 无生产消费者, LLM loop 不引用它 → async hook 响应静默丢失.
     * 修复: 本注册中心持有 AsyncHookRegistry 引用, 暴露 {@link #collectAsyncHookResponses()}
     * 让 LLM loop 每轮 drain (对齐 CC 主线程 attachments 每 LLM 调用前调
     * {@code checkForAsyncHookResponses()}). 轮询注入机制已删除 (T3-⊕1), 主动 drain 是
     * 唯一消费通道.
     *
     * <p>用 volatile + setter (镜像 {@link #setCommandHookExecutor(CommandHookExecutor)} 模式),
     * {@code @Autowired(required=false)}: 手动 new 场景为 null → {@link #collectAsyncHookResponses()}
     * 返回空列表 (不破坏老路径).
     */
    private volatile AsyncHookRegistry asyncHookRegistry;

    @Autowired(required = false)
    public void setAsyncHookRegistry(AsyncHookRegistry asyncHookRegistry) {
        this.asyncHookRegistry = asyncHookRegistry;
        if (log.isDebugEnabled()) {
            log.debug("HOOK asyncHookRegistry injected: present={}", asyncHookRegistry != null);
        }
    }

    /**
     * [H10 · 对抗核验修复] 采集已完成的 async hook 响应 · 委托
     * {@link AsyncHookRegistry#checkForAsyncHookResponses()} (CC attachments.ts:3465).
     *
     * <p><b>WHY</b>: CC 主线程每 LLM 调用前经 {@code getAsyncHookResponseAttachments} 调
     * {@code checkForAsyncHookResponses()} 消费 pending 池; Java 端由 LLM loop 每轮经本方法
     * drain — 这是 async hook 响应回到 LLM 上下文的唯一生产通道 (修复"生产无消费方"缺口).
     *
     * @return 已交付的响应列表; registry 未注入 → 空列表 (null-safe)
     */
    public List<AsyncHookRegistry.AsyncHookResponse> collectAsyncHookResponses() {
        AsyncHookRegistry registry = asyncHookRegistry;
        if (registry == null) {
            return List.of();
        }
        return registry.checkForAsyncHookResponses();
    }

    /**
     * [H4 + IMPL-05] LLM Provider 工厂 · prompt hook 真实 provider 路由.
     *
     * <p>WHY: prompt hook 需 LLM provider 单轮评估条件 (CC execPromptHook.ts:112-122).
     * [IMPL-05 D10-1 / OD-EX-05] 真实 provider 由 {@link #resolvePromptProvider(String)}
     * 按模型解析（ProviderService 通道），本工厂只做 config → provider 路由
     * （openai_compatible / anthropic / …），不再构造 ProviderConfig.empty() 降级 mock。
     * {@code @Autowired(required=false)}: 手动 new 场景为 null → prompt hook 跳过.
     */
    private volatile LlmProviderFactory llmProviderFactory;

    @Autowired(required = false)
    public void setLlmProviderFactory(LlmProviderFactory llmProviderFactory) {
        this.llmProviderFactory = llmProviderFactory;
        if (log.isDebugEnabled()) {
            log.debug("HOOK llmProviderFactory injected: present={}", llmProviderFactory != null);
        }
    }

    /**
     * [RV-FOLLOWUP DEDUP-01] 共享配置解析器 · {@link #resolvePromptProvider} 薄委托单一来源。
     *
     * <p>WHY: 消重副本#2（原 resolvePromptProvider 手写 ProviderDto 遍历 → modelMapper 全局
     * enabled model 查询），保留 warn+skip null 语义（ModelConfigResolver 与副本#2 1:1 对齐，
     * 见 reverify/rv-followup/plan.md §2）。{@code @Autowired(required=false)}: 手动 new 场景为
     * null → resolvePromptProvider 走 warn+null 兜底（不落 mock）。
     */
    private volatile ModelConfigResolver modelConfigResolver;

    @Autowired(required = false)
    public void setModelConfigResolver(ModelConfigResolver modelConfigResolver) {
        this.modelConfigResolver = modelConfigResolver;
        if (log.isDebugEnabled()) {
            log.debug("HOOK modelConfigResolver injected: present={}", modelConfigResolver != null);
        }
    }

    /** [H4] prompt hook 默认 fast model · 对齐 CC getSmallFastModel. 空 → 占位串 (真实解析留 H6/H7/H8). */
    @Value("${nexusai.hook.fastModel:}")
    private String defaultFastModel = "";

    /**
     * [H1] 配置驱动 hook 主链路入口 · 等价 CC getMatchingHooks (hooks.ts:1603-1874).
     *
     * <p>[IMPL-07 OD-11] session 与 settings 同命令 hook 去重并入统一匹配链 ·
     * 对齐 CC getHooksConfig (hooks.ts:1492-1566) 把 snapshot+registered+session 合并成
     * 单链 + hookDedupKey 全集合去重 (hooks.ts:1453-1455, 同 '' 前缀折叠, session last-wins):
     * 本方法把 session 作用域 command hooks (SessionHookStore, matcher 已按
     * matchesSessionMatcher 过滤) 并入 engine 的匹配产物<b>去重之前</b>, 同命令
     * settings+session 折叠为一条且 session 胜出 (与 settings 分链执行导致的同命令
     * 双执行消除).
     *
     * <p>WHY: 未接线 (engine==null) 时仍返回 session 匹配结果 (session hook 执行不依赖
     * settings 引擎; 旧 executeSessionHooks 分链语义等价).
     *
     * @param event hook 事件
     * @return 匹配的 MatchedHook 列表 (settings + session 去重后; 可能为空)
     */
    public List<MatchedHook> getMatchingHooks(HookEvent event) {
        HookMatcherEngine engine = this.hookMatcherEngine;
        // [IMP-HR-07 · OPD-WF6-01-05 · 返工 R-1/R-2] session command hooks 仅 CC appState 发射点并入 ·
        //   CC getHooksConfig（hooks.ts:1541）`appState !== undefined` 才合并 session hooks；appState
        //   由发射点 toolUseContext.getAppState 派生（hooks.ts:2001）或 executeHooksOutsideREPL
        //   getAppState 解析（hooks.ts:3015）。[返工 R-1] 双条件（isSessionHookEligible）：事件类型 ∈
        //   CC appState 发射点集合 + 事件 sessionId 有活跃 agent 循环。SessionStart(:1896)/
        //   Setup(:1943) 发射于 doRun 内（运行态窗口内）但 CC 不传 toolUseContext → appState
        //   undefined → 排除；SubagentStart（SubagentExecutor:2873）同理。[返工 R-2] SessionEnd
        //   (:2200) 是 CC appState 发射点（executeSessionEndHooks → executeHooksOutsideREPL 传
        //   getAppState，hooks.ts:4118 → appState 可定义）→ 保留在集合内参与；Notification/
        //   ConfigChange/Worktree/Env/Elicitation/InstructionsLoaded/PreCompact/PostCompact 等
        //   executeHooksOutsideREPL 事件调用方不传 getAppState → appState undefined → 排除。
        List<MatchedHook> sessionMatched = isSessionHookEligible(event)
            ? sessionCommandMatched(event)
            : List.of();
        // [MT-02 / OPD-WF2-MT-02] registered 源（PluginLoader 插件 native hooks + programmatic）
        //   并入统一单链 · CC getHooksConfig (hooks.ts:1519-1529) 把 registered matchers 并入
        //   getMatchingHooks 的 matcher 列表, 随后统一 matcher 过滤/去重/if (hooks.ts:1681-1848).
        //   managedOnly 门控在构造 registered 源时应用 (CC hooks.ts:1524 跳过插件 matchers).
        List<HookMatcherEngine.RegisteredHookMatcher> registered = registeredMatchersFor(event);
        if (engine == null) {
            return sessionMatched;
        }
        // 无 session hooks + 无 registered 源 → 走 1 参重载 (测试 stub 引擎/既有调用方只覆写
        //   1 参版本, 行为不变); 仅 session → 2 参 (既有 stub 覆写路径); 含 registered → 3 参.
        if (registered.isEmpty()) {
            return sessionMatched.isEmpty()
                ? engine.getMatchingHooks(event)
                : engine.getMatchingHooks(event, sessionMatched);
        }
        return engine.getMatchingHooks(event, sessionMatched, registered);
    }

    /**
     * [MT-02] 构建当前事件的 registered 源 matchers · 对齐 CC getHooksConfig registered 段
     * （hooks.ts:1519-1529 {@code getRegisteredHooks()?.[hookEvent]}）。
     *
     * <p><b>managedOnly 门控</b>（CC hooks.ts:1524 {@code managedOnly && 'pluginRoot' in matcher
     * → skip}）: shouldAllowManagedHooksOnly() 时排除插件 matchers（有 pluginRoot），SDK
     * callback matchers（无 pluginRoot）保留 —— 与 {@link #hasWorktreeCreateHook()} 的
     * registered 源 managedOnly 过滤同语义。
     *
     * @param event hook 事件
     * @return registered 源 matcher 列表（可能为空，永不 null）
     */
    private List<HookMatcherEngine.RegisteredHookMatcher> registeredMatchersFor(HookEvent event) {
        List<HookMatcherEngine.RegisteredHookMatcher> all = registeredHookMatchers.get(event.type());
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (!shouldAllowManagedHooksOnly()) {
            return all;
        }
        List<HookMatcherEngine.RegisteredHookMatcher> filtered = new ArrayList<>();
        for (HookMatcherEngine.RegisteredHookMatcher rm : all) {
            if (rm.pluginRoot() != null) {
                continue;
            }
            filtered.add(rm);
        }
        return filtered;
    }

    /**
     * [IMPL-07 OD-11] 构建当前事件的 session 作用域 command MatchedHook 列表 ·
     * 对齐 CC getHooksConfig session hooks 合并段 (hooks.ts:1541-1563).
     *
     * <p>门控与旧 executeSessionHooks 分链一致: managedOnly 跳过 (CC :1541 注释
     * "Skip session hooks entirely when allowManagedHooksOnly is set") + trust 跳过
     * (防御纵深, executeEvent 入口已门控) + sessionId 空跳过. matcher 过滤复用
     * {@link HookMatcherEngine#matchesSessionMatcher} (CC :1684 matchesPattern 等价).
     *
     * @param event hook 事件
     * @return session command MatchedHook 列表 (可能为空); hookSource="settings"（对齐 CC
     *         hooks.ts:1694-1702 三元: session 派生 matcher 无 pluginRoot → 'settings'）
     */
    private List<MatchedHook> sessionCommandMatched(HookEvent event) {
        if (shouldAllowManagedHooksOnly()) {
            if (log.isDebugEnabled()) {
                log.debug("getMatchingHooks: allowManagedHooksOnly=true, 跳过 session hooks (事件 {})",
                    event.type());
            }
            return List.of();
        }
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("getMatchingHooks: workspace trust 未接受, 跳过 session hooks (事件 {})",
                    event.type());
            }
            return List.of();
        }
        // [EX-HOOK R7 修正] 匹配 key = agentId ?? sessionId · 对齐 CC hooks.ts:2003
        //   （getMatchingHooks sessionId 参数 = toolUseContext?.agentId ?? getSessionId()）。
        //   事件 sessionId 承载载荷（主会话），匹配侧必须 agentId 优先，否则按 agentId
        //   注册的 session hooks（frontmatter hooks, key=agentId）永不命中。
        String sessionId = event.agentId() != null && !event.agentId().isBlank()
            ? event.agentId()
            : event.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        HookMatcherEngine engine = this.hookMatcherEngine;
        Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> sessionHooks =
            sessionHookStore.getSessionHooks(sessionId, event.type());
        List<SessionHookStore.SessionDerivedHookMatcher> matchers = sessionHooks.get(event.type());
        if (matchers == null || matchers.isEmpty()) {
            return List.of();
        }
        List<MatchedHook> out = new ArrayList<>();
        for (SessionHookStore.SessionDerivedHookMatcher m : matchers) {
            if (m.hooks() == null || m.hooks().isEmpty()) {
                continue;
            }
            if (engine != null && !engine.matchesSessionMatcher(event, m.matcher())) {
                if (log.isDebugEnabled()) {
                    log.debug("session command hook 跳过 (matcher 不匹配): matcher={}",
                        m.matcher());
                }
                continue;
            }
            for (HookCommand hook : m.hooks()) {
                out.add(new MatchedHook(hook, null, null, m.skillRoot(), "settings"));
            }
        }
        return out;
    }

    /** PreToolUse hooks · LinkedHashMap 保证按注册顺序遍历 */
    private final Map<String, PreToolUseHook> preToolUseHooks = new LinkedHashMap<>();

    /** PostToolUse hooks · LinkedHashMap 保证按注册顺序遍历 */
    private final Map<String, PostToolUseHook> postToolUseHooks = new LinkedHashMap<>();

    /** Generic hooks · LinkedHashMap 保证按注册顺序遍历 */
    private final Map<String, GenericHook> genericHooks = new LinkedHashMap<>();

    /** [MPL7] 已注册插件 hook 名 → 所属 plugin 名（HookSource.PLUGIN_HOOK 跟踪，供 clear/prune）。 */
    private final Map<String, String> pluginHookOwners = new LinkedHashMap<>();

    /**
     * [D01] internal hook 名集合 · CC isInternalHook (hooks.ts:1440-1442):
     *   matched.hook.type === 'callback' && matched.hook.internal === true.
     *   internal callback (sessionFileAccess 等) 不入 tengu_run_hook 的 userHooks 计数/门控
     *   (hooks.ts:2019-2035). Java 端注册时标记 (X-WF7-02 D01).
     */
    private final java.util.Set<String> internalHookNames =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry;
        if (log.isDebugEnabled()) {
            log.debug("HOOK telemetry injected: telemetryPresent={}", telemetry != null);
        }
    }

    public Telemetry getTelemetry() {
        return this.telemetry;
    }

    /**
     * [IMP-HOOKS-S6 CCJ-T6-06] hook 事件总线 · 工具链预执行进度事件 (started/progress)
     * 发射通道 · 对齐 CC executeHooks progress yield (hooks.ts:2094-2116).
     *
     * <p>镜像 {@link #setCommandHookExecutor(CommandHookExecutor)} 注入模式
     * (CommandHookExecutor.java:187-190 同款): {@code @Autowired(required=false)}
     * Spring 自动注入, 手动 new 场景为 null → 工具链进度事件不发射 (不破坏现有路径).
     */
    private volatile HookEventBus hookEventBus;

    @Autowired(required = false)
    public void setHookEventBus(HookEventBus hookEventBus) {
        this.hookEventBus = hookEventBus;
        if (log.isDebugEnabled()) {
            log.debug("HOOK event bus injected: busPresent={}", hookEventBus != null);
        }
    }

    public HookEventBus getHookEventBus() {
        return this.hookEventBus;
    }

    /**
     * 消息流 hook_progress 载荷消费者 · 对齐 CC hooks.ts:2094-2116
     * {@code yield {message: {type:'progress', data:{type:'hook_progress', hookEvent, hookName,
     * command, promptText?, statusMessage?}}}}。
     *
     * <p>[IMP-CF-04 OPD-WF1-TY-05] 每匹配 hook 预执行时把 {@link HookProgress} 消息流载荷
     * （command/promptText/statusMessage，非事件总线 HookProgressEvent 六字段）经本消费者发出。
     * 默认 no-op：前端 SSE/API 接线（决策 0-4 / FNT-02-01）注入后生效；null 注册回退 no-op
     * （镜像 {@link LlmAgentLoop#setAppendSystemMessage} no-op 兜底模式）。
     *
     * @see HookProgress
     */
    private volatile Consumer<HookProgress> hookProgressMessageSink = p -> {
    };

    /** 注入消息流 hook_progress 消费者 · null → 回退 no-op（不改变既有发射语义）。
     *  {@code @Autowired(required=false)}: 前端 SSE/API 接线（决策 0-4 / FNT-02-01）落 bean 后
     *  Spring 自动注入; 手动 new 场景为 null → no-op（镜像 {@link #setHookEventBus} 模式）. */
    @Autowired(required = false)
    public void setHookProgressMessageSink(Consumer<HookProgress> sink) {
        this.hookProgressMessageSink = sink != null ? sink : p -> {
        };
        if (log.isDebugEnabled()) {
            log.debug("HOOK 消息流 hook_progress 消费者注入: present={}", sink != null);
        }
    }

    private void emitHookErrorTelemetry(String hookName, String eventName,
                                       long durationMs, Throwable th) {
        if (telemetry == null || eventName == null) return;
        try {
            java.util.Map<String, Object> attrs = new java.util.HashMap<>();
            if (hookName != null) attrs.put("toolName", hookName);
            if (th != null && th.getMessage() != null) attrs.put("error", th.getMessage());
            attrs.put("isMcp", false);
            attrs.put("duration", durationMs);
            telemetry.recordEvent(eventName, attrs);
            telemetry.logOTelEvent(eventName, attrs);
        } catch (Throwable telemetryTh) {
            log.warn("HOOK hook_error telemetry 失败: event={} err={}",
                eventName, telemetryTh.toString());
        }
    }

    /**
     * [Session H5] hook cancelled 结构化埋点 · 对齐 CC toolHooks.ts:583 (tengu_pre_tool_hooks_cancelled) +
     * toolHooks.ts:72 (tengu_post_tool_hooks_cancelled) + toolHooks.ts:228 (tengu_post_tool_failure_hooks_cancelled).
     *
     * <p>WHY: CC 在 abort 期间 (abortController.signal.aborted 或 executeHooks yield hook_cancelled result)
     * 发 cancelled telemetry (区别于 hook 抛错的 error telemetry). Java 端同步聚合无 per-hook yield,
     * 在 execute* 方法内检测 {@code ctx.abortController().isCancelled()} 后调本方法发 cancelled 事件.
     *
     * @param toolName  工具名 (CC sanitizeToolNameForAnalytics(tool.name))
     * @param eventName CC 事件名 (tengu_pre_tool_hooks_cancelled / tengu_post_tool_hooks_cancelled /
     *                  tengu_post_tool_failure_hooks_cancelled)
     */
    private void emitHookCancelledTelemetry(String toolName, String eventName) {
        if (telemetry == null || eventName == null) return;
        try {
            java.util.Map<String, Object> attrs = new java.util.HashMap<>();
            if (toolName != null) attrs.put("toolName", toolName);
            telemetry.recordEvent(eventName, attrs);
            telemetry.logOTelEvent(eventName, attrs);
            if (log.isDebugEnabled()) {
                log.debug("HOOK cancelled telemetry 发出: event={} tool={}", eventName, toolName);
            }
        } catch (Throwable t) {
            log.warn("HOOK cancelled telemetry 失败: event={} err={}", eventName, t.toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-HOOKS-S6 CCJ-T6-06/11] 工具链批级进度事件 + 遥测辅助
    // 对齐 CC executeHooks (hooks.ts:2023-2035 tengu_run_hook 批首 + :2094-2116
    // 每匹配 hook 预执行 progress yield + :2935-2944 tengu_repl_hook_finished 批尾).
    // ════════════════════════════════════════════════════════════════════════

    /** 工具链批 outcome 计数 · 对齐 CC outcomes (hooks.ts:2734-2739). */
    private static final class ToolChainOutcomes {
        int success;
        int blocking;
        int nonBlockingError;
        int cancelled;

        int total() {
            return success + blocking + nonBlockingError + cancelled;
        }

        void add(GenericHook.HookOutcome outcome) {
            switch (outcome) {
                case BLOCKING -> blocking++;
                case NON_BLOCKING_ERROR -> nonBlockingError++;
                case CANCELLED -> cancelled++;
                case SUCCESS -> success++;
            }
        }
    }

    /**
     * 工具链批首遥测 · tengu_run_hook (CC hooks.ts:2023-2035). [D01] userHooks>0 门控 +
     *   numCommands/hookTypeCounts 排除 internal (CC hooks.ts:2019-2029); [D03]
     *   pluginHookCounts 条件载荷 (CC hooks.ts:2030-2034, 无插件 hook → 不发该字段).
     *
     * @param ccEventName      CC 事件名 (PreToolUse/PostToolUse/PostToolUseFailure)
     * @param numCommands      userHooks 数 (配置驱动 + 非 internal programmatic)
     * @param typeCounts       hook 类型计数 (userHooks 范围)
     * @param pluginHookCounts 插件 hook 计数 (null/空 → 载荷省略; CC hooks.ts:2030-2034)
     */
    private void emitToolHookRunTelemetry(String ccEventName, int numCommands,
                                          java.util.Map<String, Integer> typeCounts,
                                          java.util.Map<String, Integer> pluginHookCounts) {
        if (telemetry == null || ccEventName == null || numCommands <= 0) {
            return;
        }
        try {
            java.util.Map<String, Object> attrs = new java.util.HashMap<>();
            attrs.put("hookName", ccEventName);
            attrs.put("numCommands", numCommands);
            attrs.put("hookTypeCounts", jsonStringify(typeCounts));
            if (pluginHookCounts != null && !pluginHookCounts.isEmpty()) {
                attrs.put("pluginHookCounts", jsonStringify(pluginHookCounts));
            }
            telemetry.recordEvent("tengu_run_hook", attrs);
            telemetry.logOTelEvent("tengu_run_hook", attrs);
            if (log.isDebugEnabled()) {
                log.debug("HOOK tengu_run_hook 发出: event={} numCommands={} types={} plugins={}",
                    ccEventName, numCommands, typeCounts, pluginHookCounts);
            }
        } catch (Throwable t) {
            log.warn("HOOK tengu_run_hook telemetry 失败: event={} err={}", ccEventName, t.toString());
        }
    }

    /**
     * 工具链批尾遥测 · tengu_repl_hook_finished (CC hooks.ts:2935-2944).
     *
     * <p>计数语义 (Java 表达差异登记): programmatic 段逐 hook outcome 精确计数;
     * 配置驱动段经 executeEvent 折叠为单结果, 折叠结果按"整段同 outcome"计入
     * matched 数 (proceed → 全部 success; blockingError/preventContinuation → 全部
     * blocking; NON_BLOCKING_ERROR → 全部 nonBlockingError), 保持
     * numCommands == numSuccess+numBlocking+numNonBlockingError+numCancelled 不变量.
     */
    private void emitToolHookFinishedTelemetry(String ccEventName, ToolChainOutcomes outcomes,
                                               long totalDurationMs) {
        if (telemetry == null || ccEventName == null || outcomes.total() <= 0) {
            return;
        }
        try {
            java.util.Map<String, Object> attrs = new java.util.HashMap<>();
            attrs.put("hookName", ccEventName);
            attrs.put("numCommands", outcomes.total());
            attrs.put("numSuccess", outcomes.success);
            attrs.put("numBlocking", outcomes.blocking);
            attrs.put("numNonBlockingError", outcomes.nonBlockingError);
            attrs.put("numCancelled", outcomes.cancelled);
            attrs.put("totalDurationMs", totalDurationMs);
            telemetry.recordEvent("tengu_repl_hook_finished", attrs);
            telemetry.logOTelEvent("tengu_repl_hook_finished", attrs);
            if (log.isDebugEnabled()) {
                log.debug("HOOK tengu_repl_hook_finished 发出: event={} outcomes={}",
                    ccEventName, attrs);
            }
        } catch (Throwable t) {
            log.warn("HOOK tengu_repl_hook_finished telemetry 失败: event={} err={}",
                ccEventName, t.toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [JS-05 GAP-9] beta-tracing OTEL hook 通道 · 对齐 CC executeHooks
    //   hooks.ts:2070-2084 (hook_execution_start) + :2946-2970
    //   (hook_execution_complete) + :5005-5022 (getHookDefinitionsForTelemetry)
    //   + sessionTracing.ts:844-918 (startHookSpan/endHookSpan).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [JS-05 GAP-9] beta-tracing 门控 · 对齐 CC isBetaTracingEnabled
     * (betaSessionTracing.ts:78-98).
     *
     * <p>基础条件 = {@code isEnvTruthy(ENABLE_BETA_TRACING_DETAILED)} &&
     * {@code BETA_TRACING_ENDPOINT} 非空 (betaSessionTracing.ts:80-81). CC 另对
     * 外部用户追加 USER_TYPE/allowlist GrowthBook 门控 (非 ant 用户需非交互或
     * tengu_trace_lantern allowlist, :84-92) — Java 后端为服务端非交互进程,
     * USER_TYPE 概念不适用, 仅保留环境变量基础条件 (登记为表达差异).
     *
     * <p>测试缝: {@link #setBetaTracingEnvOverride(String, String)} 覆盖两个 env
     * (null → 读真实 env), 镜像 {@code MemoryBareModeConfig.setEnvOverride} 模式.
     *
     * @return true = 开启 beta-tracing OTEL hook 通道
     */
    private boolean isBetaTracingEnabled() {
        String detailed = betaDetailedEnvOverride != null
            ? betaDetailedEnvOverride : System.getenv("ENABLE_BETA_TRACING_DETAILED");
        String endpoint = betaEndpointEnvOverride != null
            ? betaEndpointEnvOverride : System.getenv("BETA_TRACING_ENDPOINT");
        return isEnvTruthy(detailed) && endpoint != null && !endpoint.isBlank();
    }

    /**
     * [JS-05 GAP-9] beta-tracing env 覆盖缝 (测试用) · Java 无法进程内改 env,
     * 镜像 {@code MemoryBareModeConfig.setEnvOverride} 模式. 传 null 表示该值
     * 回落真实 {@code System.getenv}. 生产路径两字段恒 null → 读真实 env, 无行为改变.
     *
     * @param detailed ENABLE_BETA_TRACING_DETAILED 覆盖值; null → 真实 env
     * @param endpoint BETA_TRACING_ENDPOINT 覆盖值; null → 真实 env
     */
    void setBetaTracingEnvOverride(String detailed, String endpoint) {
        this.betaDetailedEnvOverride = detailed;
        this.betaEndpointEnvOverride = endpoint;
    }

    /** [JS-05 GAP-9] 测试缝字段: null → 读真实 env (见 {@link #isBetaTracingEnabled()}). */
    private volatile String betaDetailedEnvOverride;

    /** [JS-05 GAP-9] 测试缝字段: null → 读真实 env (见 {@link #isBetaTracingEnabled()}). */
    private volatile String betaEndpointEnvOverride;

    /**
     * [JS-05 GAP-9] CC original: isEnvTruthy (utils/envUtils.ts:32) —
     * 值 ∈ {1,true,yes,on} 为 true. 与 ApiErrors.isEnvTruthy 同语义 (独立副本,
     * 避免跨包依赖).
     */
    private static boolean isEnvTruthy(String envVar) {
        if (envVar == null) {
            return false;
        }
        String normalized = envVar.trim().toLowerCase();
        return normalized.equals("1") || normalized.equals("true")
            || normalized.equals("yes") || normalized.equals("on");
    }

    /**
     * [JS-05 GAP-9] hook_definitions 载荷 · 对齐 CC getHookDefinitionsForTelemetry
     * (hooks.ts:5005-5022): matchedHooks → {@code [{type, command?, prompt?, name?}]} —
     * command→{type:'command', command} / prompt→{type:'prompt', prompt} /
     * http→{type:'http', command:url} / function→{type:'function', name:'function'} /
     * callback→{type:'callback', name:'callback'} / 其他(含 agent)→{type:'unknown'}
     * (CC 无 agent 分支 falls through).
     *
     * <p>Java 表达: {@code matched}（配置驱动 HookCommand 4 持久化类型）走 command/
     * prompt/http/agent 分支; {@code snapshot}（programmatic Java hook）按 internal
     * 标记走 callback/function 分支（对齐 CC 注册 function/callback hook 在
     * matchingHooks 中）。
     *
     * @param matched  配置驱动匹配集 (getMatchingHooks 结果)
     * @param snapshot programmatic hook 快照 (含 internal)
     * @return JSON 数组字符串; 空集 → "[]"
     */
    private String getHookDefinitionsForTelemetry(
            List<MatchedHook> matched,
            java.util.List<? extends Map.Entry<String, ?>> snapshot) {
        java.util.List<java.util.Map<String, Object>> defs = new ArrayList<>();
        if (matched != null) {
            for (MatchedHook mh : matched) {
                java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
                switch (mh.hook().hookType()) {
                    case COMMAND -> {
                        d.put("type", "command");
                        d.put("command", ((CommandHook) mh.hook()).command());
                    }
                    case PROMPT -> {
                        d.put("type", "prompt");
                        d.put("prompt", ((PromptHook) mh.hook()).prompt());
                    }
                    case HTTP -> {
                        d.put("type", "http");
                        d.put("command", ((HttpHook) mh.hook()).url());
                    }
                    // CC getHookDefinitionsForTelemetry 无 agent 分支 → {type:'unknown'}
                    default -> d.put("type", "unknown");
                }
                defs.add(d);
            }
        }
        if (snapshot != null) {
            for (Map.Entry<String, ?> e : snapshot) {
                java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
                if (internalHookNames.contains(e.getKey())) {
                    d.put("type", "callback");
                    d.put("name", "callback");
                } else {
                    d.put("type", "function");
                    d.put("name", "function");
                }
                defs.add(d);
            }
        }
        try {
            return new ObjectMapper().writeValueAsString(defs);
        } catch (Exception ex) {
            return String.valueOf(defs);
        }
    }

    /**
     * [JS-05 GAP-9] hook_execution_start OTEL 事件 (CC hooks.ts:2076-2084) +
     * hook span start (CC sessionTracing.ts:844-879 startHookSpan).
     *
     * <p>属性集对齐 CC: hook_event / hook_name / num_hooks(全量匹配 hook 数,
     * 含 internal, CC matchingHooks.length) / managed_only / hook_definitions /
     * hook_source (managedOnly ? 'policySettings' : 'merged'). 门控: beta-tracing
     * 关闭 → 不发射事件、返回 disabled span (CC isBetaTracingEnabled() false 时
     * startHookSpan 返回 dummy span, endHookSpan 早退).
     *
     * <p>hook span: 经 {@code telemetry.getOpenTelemetry().getTracer(
     * "com.anthropic.claude_code.tracing", "1.0.0")} 创建 {@code claude_code.hook}
     * span (对齐 CC sessionTracing.ts:152 getTracer 同名 instrumentation +
     * startHookSpan :864 span 名 + :857 span.type='hook' 属性). Java OTel SDK
     * 未配置 trace exporter (仅 logs pipeline) → span 不导出, API 级结构等价
     * (登记差异).
     *
     * @param hookEvent           CC hook_event (PreToolUse/PostToolUse/PostToolUseFailure)
     * @param hookName            CC hook_name (如 "PreToolUse:Bash", hooks.ts:1986)
     * @param numHooks            CC num_hooks = matchingHooks.length (含 internal)
     * @param hookDefinitionsJson CC hook_definitions JSON (getHookDefinitionsForTelemetry)
     * @return hook span holder (beta-tracing 关闭/OTel 不可用 → disabled no-op)
     */
    private HookSpan emitHookExecutionStartTelemetry(String hookEvent, String hookName,
                                                     int numHooks, String hookDefinitionsJson) {
        if (telemetry == null || !isBetaTracingEnabled()) {
            return HookSpan.disabled();
        }
        try {
            java.util.Map<String, Object> attrs = new java.util.HashMap<>();
            attrs.put("hook_event", hookEvent);
            attrs.put("hook_name", hookName);
            attrs.put("num_hooks", String.valueOf(numHooks));
            attrs.put("managed_only", String.valueOf(shouldAllowManagedHooksOnly()));
            attrs.put("hook_definitions", hookDefinitionsJson);
            attrs.put("hook_source",
                shouldAllowManagedHooksOnly() ? "policySettings" : "merged");
            telemetry.logOTelEvent("hook_execution_start", attrs);
            if (log.isDebugEnabled()) {
                log.debug("HOOK hook_execution_start 发出: event={} hookName={} numHooks={}",
                    hookEvent, hookName, numHooks);
            }
            return HookSpan.start(telemetry, hookEvent, hookName, numHooks,
                hookDefinitionsJson);
        } catch (Throwable t) {
            log.warn("HOOK hook_execution_start telemetry 失败: event={} err={}",
                hookEvent, t.toString());
            return HookSpan.disabled();
        }
    }

    /**
     * [JS-05 GAP-9] hook_execution_complete OTEL 事件 (CC hooks.ts:2946-2963) +
     * hook span end (CC sessionTracing.ts:887-918 endHookSpan).
     *
     * <p>属性集对齐 CC: hook_event / hook_name / num_hooks / num_success /
     * num_blocking / num_non_blocking_error / num_cancelled / managed_only /
     * hook_definitions / hook_source (全 String, CC String(...) 表达). span end
     * 追加 duration_ms + num_* 计数后 end (对齐 endHookSpan).
     *
     * @param hookEvent           CC hook_event
     * @param hookName            CC hook_name
     * @param numHooks            CC num_hooks = matchingHooks.length
     * @param outcomes            批 outcome 计数 (CC outcomes, hooks.ts:2734-2739)
     * @param hookDefinitionsJson CC hook_definitions JSON
     * @param span                批首 start 返回的 hook span holder
     */
    private void emitHookExecutionCompleteTelemetry(String hookEvent, String hookName,
                                                    int numHooks, ToolChainOutcomes outcomes,
                                                    String hookDefinitionsJson, HookSpan span) {
        if (telemetry == null || !isBetaTracingEnabled()) {
            span.end();
            return;
        }
        try {
            java.util.Map<String, Object> attrs = new java.util.HashMap<>();
            attrs.put("hook_event", hookEvent);
            attrs.put("hook_name", hookName);
            attrs.put("num_hooks", String.valueOf(numHooks));
            attrs.put("num_success", String.valueOf(outcomes.success));
            attrs.put("num_blocking", String.valueOf(outcomes.blocking));
            attrs.put("num_non_blocking_error", String.valueOf(outcomes.nonBlockingError));
            attrs.put("num_cancelled", String.valueOf(outcomes.cancelled));
            attrs.put("managed_only", String.valueOf(shouldAllowManagedHooksOnly()));
            attrs.put("hook_definitions", hookDefinitionsJson);
            attrs.put("hook_source",
                shouldAllowManagedHooksOnly() ? "policySettings" : "merged");
            telemetry.logOTelEvent("hook_execution_complete", attrs);
            if (log.isDebugEnabled()) {
                log.debug("HOOK hook_execution_complete 发出: event={} hookName={} outcomes={}",
                    hookEvent, hookName, attrs);
            }
        } catch (Throwable t) {
            log.warn("HOOK hook_execution_complete telemetry 失败: event={} err={}",
                hookEvent, t.toString());
        } finally {
            // span end 带 num_*/duration_ms 属性 (对齐 CC endHookSpan :897-917),
            // 非 no-arg end — disabled span 内 no-op.
            span.end(outcomes);
        }
    }

    /**
     * [JS-05 GAP-9] hook span holder · 对齐 CC startHookSpan/endHookSpan
     * (sessionTracing.ts:844-918) 的配对语义. Java OTel SDK 未配置 trace exporter
     * (仅 logs pipeline) → span 不导出, 仅 API 级结构等价 (登记差异).
     *
     * <p>startHookSpan (CC :844-879): beta-tracing 开启时创建 {@code claude_code.hook}
     * span, 属性 = base + {@code span.type:'hook'} + hook_event/hook_name/num_hooks/
     * hook_definitions. endHookSpan (CC :887-918): 追加 duration_ms + num_success/
     * num_blocking/num_non_blocking_error/num_cancelled 后 end.
     */
    private static final class HookSpan {
        private final io.opentelemetry.api.trace.Span otelSpan;
        private final long startTimeMs;

        private HookSpan(io.opentelemetry.api.trace.Span otelSpan) {
            this.otelSpan = otelSpan;
            this.startTimeMs = System.currentTimeMillis();
        }

        static HookSpan disabled() {
            return new HookSpan(null);
        }

        static HookSpan start(Telemetry telemetry, String hookEvent, String hookName,
                              int numHooks, String hookDefinitionsJson) {
            io.opentelemetry.api.OpenTelemetry otel = telemetry != null
                ? telemetry.getOpenTelemetry() : null;
            if (otel == null) {
                return disabled();
            }
            try {
                io.opentelemetry.api.common.Attributes attrs =
                    io.opentelemetry.api.common.Attributes.builder()
                        .put("span.type", "hook")
                        .put("hook_event", hookEvent)
                        .put("hook_name", hookName)
                        .put("num_hooks", (long) numHooks)
                        .put("hook_definitions", hookDefinitionsJson)
                        .build();
                io.opentelemetry.api.trace.Span span = otel
                    .getTracer("com.anthropic.claude_code.tracing", "1.0.0")
                    .spanBuilder("claude_code.hook")
                    .setAllAttributes(attrs)
                    .startSpan();
                return new HookSpan(span);
            } catch (Throwable t) {
                log.warn("HOOK hook span start 失败: event={} err={}", hookEvent, t.toString());
                return disabled();
            }
        }

        void end(ToolChainOutcomes outcomes) {
            if (otelSpan == null) {
                return;
            }
            try {
                otelSpan.setAttribute("num_success", (long) outcomes.success);
                otelSpan.setAttribute("num_blocking", (long) outcomes.blocking);
                otelSpan.setAttribute("num_non_blocking_error", (long) outcomes.nonBlockingError);
                otelSpan.setAttribute("num_cancelled", (long) outcomes.cancelled);
                otelSpan.setAttribute("duration_ms",
                    System.currentTimeMillis() - startTimeMs);
                otelSpan.end();
            } catch (Throwable t) {
                log.warn("HOOK hook span end 失败: err={}", t.toString());
            }
        }

        void end() {
            if (otelSpan != null) {
                try {
                    otelSpan.end();
                } catch (Throwable t) {
                    log.warn("HOOK hook span end 失败: err={}", t.toString());
                }
            }
        }
    }

    /**
     * 每匹配 hook 预执行 started + progress 事件 (CC hooks.ts:2094-2116).
     *
     * <p>[hooks_v3 决策 2-4 / D-WF5-06] 事件总线 progress 回缩为 CC 六字段
     * (hookEvents.ts:29-37): command/promptText/statusMessage 属<b>消息流</b> hook_progress
     * 载荷 (hooks.ts:2094-2116 {@code {type:'hook_progress', hookEvent, hookName, command,
     * promptText?, statusMessage?}}), 经消息流通道发出 (对齐 CC {@code yield {message:
     * {type:'progress', data:{type:'hook_progress',...}}}}), 具体接线随决策 0-4 (前端 SSE/API)
     * 一起做; 事件总线只承载 async 轮询通道字段 (stdout/stderr/output, 恒 null).
     */
    private void emitToolHookStartedProgress(String hookName, String ccEventName,
                                             HookCommand hook, String programmaticName) {
        if (ccEventName == null) {
            return;
        }
        // 事件总线 progress（async 轮询通道字段 stdout/stderr/output · D-WF5-06）——
        // command/promptText/statusMessage 属消息流载荷, 不进事件总线.
        HookEventBus bus = hookEventBus;
        if (bus != null) {
            String hookId = UUID.randomUUID().toString();
            bus.emitHookStarted(hookId, hookName, ccEventName);
            bus.emitHookProgress(new HookEventBus.HookProgressData(
                hookId, hookName, ccEventName, null, null, null));
        }
        // [IMP-CF-04 OPD-WF1-TY-05] 消息流 hook_progress 载荷 (hooks.ts:2094-2116):
        //   {type:'hook_progress', hookEvent, hookName, command: getHookDisplayText(hook),
        //    promptText? (仅 prompt hook), statusMessage? ('statusMessage' in hook && 非 null)}
        //   经消息流 sink 发出 (前端 SSE/API 接线随决策 0-4 / FNT-02-01; 默认 no-op).
        //   消息流载荷与事件总线独立（事件总线 null 不阻断消息流发出）.
        Consumer<HookProgress> sink = hookProgressMessageSink;
        if (sink != null) {
            String command = stopHookCommandOf(hook);
            if (command == null) {
                command = programmaticName;
            }
            String promptText = (hook instanceof PromptHook promptHook) ? promptHook.prompt() : null;
            String statusMessage = (hook != null && hook.statusMessage() != null
                && !hook.statusMessage().isBlank()) ? hook.statusMessage() : null;
            HookProgress progress = HookProgress.of(ccEventName, hookName, command, promptText, statusMessage);
            sink.accept(progress);
            if (log.isDebugEnabled()) {
                log.debug("HOOK 消息流 hook_progress 发出: event={} hookName={} command={} promptText={} statusMessage={}",
                    ccEventName, hookName, command, promptText, statusMessage);
            }
        }
    }

    /**
     * 工具链批 hook 类型计数 · CC getHookTypeCounts (hooks.ts:2027) 等价.
     * 配置驱动按 HookCommand.hookType 计; programmatic (function 形态) 计 "function".
     */
    private static java.util.Map<String, Integer> toolHookTypeCounts(
            List<MatchedHook> matched, int programmaticCount) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        if (matched != null) {
            for (MatchedHook mh : matched) {
                counts.merge(mh.hook().type(), 1, Integer::sum);
            }
        }
        if (programmaticCount > 0) {
            counts.merge("function", programmaticCount, Integer::sum);
        }
        return counts;
    }

    /**
     * [D01] 用户 (非 internal) programmatic hook 数 · CC userHooks (hooks.ts:2019):
     *   matchingHooks.filter(h => !isInternalHook(h)). snapshot 仅统计非 internal 项,
     *   供 tengu_run_hook 门控 + numCommands (hooks.ts:2026).
     *
     * <p>[H-WF7-01 REWORK] 泛型不变性修复: {@code List<Map.Entry<String, PreToolUseHook>>}
     * 非 {@code List<Map.Entry<String, ?>>} 子类型 (Java 泛型不变) → 实参用
     * {@code ? extends} 上界通配, 三处调用点 (PreToolUse/PostToolUse/PostToolUseFailure)
     * 方可传 {@code List<Map.Entry<String, *Hook>>}. 内部 {@code e.getKey()} 不受影响.
     */
    private long userProgrammaticCount(java.util.List<? extends Map.Entry<String, ?>> snapshot) {
        return snapshot.stream().filter(e -> !internalHookNames.contains(e.getKey())).count();
    }

    /**
     * [D03] 插件 hook 计数 · CC getPluginHookCounts (hooks.ts:1461-1478).
     *   过滤 h.pluginId; 无 → null (载荷不发该字段). 有 → pluginId 末尾 '@' 后段
     *   ∈ ALLOWED_OFFICIAL_MARKETPLACE_NAMES → 官方名, 否则 'third-party', 逐插件计数.
     */
    private static java.util.Map<String, Integer> pluginHookCounts(
            java.util.List<MatchedHook> hooks) {
        java.util.List<MatchedHook> pluginHooks = hooks == null ? java.util.List.of()
            : hooks.stream().filter(h -> h.pluginId() != null).toList();
        if (pluginHooks.isEmpty()) {
            return null;
        }
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (MatchedHook h : pluginHooks) {
            String pluginId = h.pluginId();
            int atIndex = pluginId.lastIndexOf('@');
            boolean isOfficial = atIndex > 0
                && com.nexusai.application.agent.plugin.PluginSchemas.ALLOWED_OFFICIAL_MARKETPLACE_NAMES
                    .contains(pluginId.substring(atIndex + 1));
            counts.merge(isOfficial ? pluginId : "third-party", 1, Integer::sum);
        }
        return counts;
    }

    /** Map → JSON 字符串 (hookTypeCounts 载荷, CC jsonStringify 等价). */
    private static String jsonStringify(java.util.Map<String, Integer> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return String.valueOf(map);
        }
    }

    /**
     * [B GAP-HOOK-02] PreToolUse hook 执行错误 attachment sink 函数式接口 ·
     * 对齐 CC utils/hooks.ts:2698-2730 executeHooks catch 分支 (Pattern #9 实证):
     * CC 对 hook 异常 yield {@code hook_non_blocking_error} attachment 消息 (LLM/transcript 可见),
     * 工具继续执行. Java 端 HookRegistry 内部 catch 单个 hook 异常后, 经本 sink 把错误
     * 转成 attachment 注入 (由 StreamingToolExecutor 装配 agentStateRef.appendAttachment);
     * sink 为 null 时 no-op (LlmAgentLoop 直连路径不受影响).
     */
    @FunctionalInterface
    public interface PreToolUseHookErrorSink {
        /**
         * 回调 hook 执行错误.
         *
         * @param hookName   失败的 hook 名
         * @param toolUseId  当前工具调用 ID (CC toolUseID, attachment 归属)
         * @param throwable  失败原因
         */
        void accept(String hookName, String toolUseId, Throwable throwable);
    }

    private volatile PreToolUseHookErrorSink preToolUseHookErrorSink;

    /**
     * 注入 PreToolUse hook 错误 attachment sink (StreamingToolExecutor 装配用).
     *
     * @param sink 3 参回调 (hookName, toolUseId, throwable); null 时清除
     */
    public void setPreToolUseHookErrorSink(PreToolUseHookErrorSink sink) {
        this.preToolUseHookErrorSink = sink;
    }

    private void firePreToolUseHookErrorSink(String hookName, String toolUseId,
                                             String toolName, Throwable th) {
        var sink = this.preToolUseHookErrorSink;
        if (sink == null) return;
        try {
            sink.accept(hookName, toolUseId, th);
        } catch (Throwable sinkTh) {
            log.warn("HOOK PreToolUse hookErrorSink 失败: hook={} tool={} err={}",
                hookName, toolName, sinkTh.toString());
        }
    }

    private void emitHookErrorTelemetryForGeneric(HookEvent event, String hookName,
                                                  long durationMs, Throwable th) {
        if (event == null) return;
        String eventName = null;
        switch (event.type()) {
            case PRE_TOOL_USE:
                eventName = "tengu_pre_tool_hook_error";
                break;
            case POST_TOOL_USE:
                eventName = "tengu_post_tool_hook_error";
                break;
            case POST_TOOL_USE_FAILURE:
                eventName = "tengu_post_tool_failure_hook_error";
                break;
            default:
                return;
        }
        emitHookErrorTelemetry(hookName, eventName, durationMs, th);
    }

    /** Generic hook 事件类型过滤器 · key=hook name, value=允许的事件类型集合（空=全部允许） */
    private final Map<String, Set<HookEventType>> hookEventFilters = new HashMap<>();

    // [IMPL-10] DEL-L03-04: 旧 GenericHook 会话作用域机制已删除 —
    //   SessionHookStore 是 CC SessionStore 等价物，会话作用域由它承担。

    /**
     * [Session H5] SessionHookStore 三级存储 · 对齐 CC sessionHooks.ts (SessionHooksState L62).
     *
     * <p>WHY: 承载 sub-agent 会话内临时 hook (sessionId→event→matcher→hooks), 独立于
     * settings.json 持久化 hooks ({@link #genericHooks}). {@link SubagentExecutor} finally
     * 调用 {@link #clearSessionHooks(String)} 清理 (对齐 CC runAgent.ts:822).
     *
     * <p>[IMPL-10] DEL-L03-04: 旧 GenericHook 会话作用域机制已删除，
     * 本 store 是 CC SessionStore 唯一等价物（frontmatter hooks 已迁入，见 FrontmatterHooks）。
     */
    private final SessionHookStore sessionHookStore = new SessionHookStore();

    // [IMPL-10] DEL-TH-05 评估: 缺省对齐 CC TOOL_HOOK_EXECUTION_TIMEOUT_MS=600000ms
    //   （hooks.ts:166，executeHooks 每 hook 超时缺省）；属性仍可显式覆盖（operator 旋钮）。
    @Value("${nexusai.permission.hook.timeoutMs:600000}")
    private long hookTimeoutMs = 600000L;

    private static final ExecutorService HOOK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "nexusai-hook-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    /**
     * [IMP-C D2-A/F3] 跨线程 projectRoot 传播载体 —— 调度线程捕获值注入 HOOK_EXECUTOR 线程。
     *
     * <p>WHY: hook 在 {@link #HOOK_EXECUTOR} cached 池线程执行，ThreadLocal 不跨线程；不传播则
     *   hook 载荷（cwd/transcript_path/agent-memory carve-out 等）在池线程读回落值
     *   （CLAUDE_PROJECT_DIR env ?? config home）而非会话绑定 P（M-04/D2）。模式对齐
     *   LlmAgentLoop.run() capture/restore（:1637/:1645）：调度线程（hook 提交线程 =
     *   会话/工具执行线程）捕获一次，任务体开头 set，finally restore 外层原值（restore 而非
     *   remove —— 线程池复用防泄漏，null 捕获值不 set，保持回落语义）。
     */
    private static <T> Supplier<T> withSessionProjectRoot(Supplier<T> task) {
        final String scheduledProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
        return () -> {
            String prevProjectRoot = AutoMemPaths.captureCurrentProjectRoot();
            try {
                if (scheduledProjectRoot != null && !scheduledProjectRoot.isBlank()) {
                    AutoMemPaths.setCurrentProjectRoot(scheduledProjectRoot);
                }
                return task.get();
            } finally {
                AutoMemPaths.restoreCurrentProjectRoot(prevProjectRoot);
            }
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // 注册 / 注销 — PreToolUse / PostToolUse
    // ════════════════════════════════════════════════════════════════════════

    public synchronized void registerPreToolUse(String name, PreToolUseHook hook) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Hook name is blank");
        }
        if (hook == null) {
            throw new IllegalArgumentException("Hook is null");
        }
        preToolUseHooks.put(name, hook);
        log.info("HOOK registered PreToolUse: name={}", name);
    }

    public synchronized void registerPostToolUse(String name, PostToolUseHook hook) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Hook name is blank");
        }
        if (hook == null) {
            throw new IllegalArgumentException("Hook is null");
        }
        postToolUseHooks.put(name, hook);
        log.info("HOOK registered PostToolUse: name={}", name);
    }

    /**
     * [D01] 注册 internal PostToolUse hook · CC isInternalHook (hooks.ts:1440-1442).
     *   internal=true 的 callback hook 不入 tengu_run_hook userHooks (hooks.ts:2019).
     *   SessionFileAccessHooks 等 internal 类 hook 走本入口.
     */
    public synchronized void registerPostToolUseInternal(String name, PostToolUseHook hook) {
        registerPostToolUse(name, hook);
        internalHookNames.add(name);
    }

    /**
     * [D01] 注册 internal PreToolUse hook · 同 {@link #registerPostToolUseInternal}.
     */
    public synchronized void registerPreToolUseInternal(String name, PreToolUseHook hook) {
        registerPreToolUse(name, hook);
        internalHookNames.add(name);
    }

    /**
     * [IMP-GP-01 · OPD-WF7-GC-02] registerAttributionHooks 注册入口 · 对齐 CC setup.ts:350-360
     * {@code registerAttributionHooks()}（commit attribution tracking hooks）。
     *
     * <p>CC 真源（已实读）: setup.ts:355-360 在 CLI 初始化期（非 bare 模式）动态
     * import './utils/attributionHooks.js' 并调 {@code registerAttributionHooks()}，由
     * {@code feature('COMMIT_ATTRIBUTION')} 编译期宏单门控（setup.ts:350；setup.ts:337 的
     * {@code USER_TYPE==='ant'} 为并列兄弟块 repo 分类预热，不门控注册）。Java 端
     * 等价注册点 = 本方法（PostToolUse internal hooks，经 {@link #registerPostToolUseInternal}，
     * 同 SessionFileAccessHooks 模式）。实际 hook 逻辑在 {@link RegisterAttributionHooks}，
     * 单门控（COMMIT_ATTRIBUTION，默认关）在 {@link RegisterAttributionHooks#isEnabled}
     * 内部判定 —— 门控关时本方法为 no-op（对齐 CC 发布构建宏替换 false）。
     *
     * <p>LlmAgentLoop run() 装配点（对齐 setup.ts 注册位）见 {@code registerSessionFileAccessHooks}
     * 同段 —— 注册门控默认关，启用需置 {@code RegisterAttributionHooks.COMMIT_ATTRIBUTION_ENABLED}
     * 并接线（见实施记录 IMP-GP-01 §9 follow-up）。
     *
     * @param attributionHooks attribution hooks 注册器; null → 跳过（warn 日志）
     */
    public void registerAttributionHooks(RegisterAttributionHooks attributionHooks) {
        if (attributionHooks == null) {
            log.warn("registerAttributionHooks: 注入为 null, 跳过注册");
            return;
        }
        attributionHooks.registerAttributionHooks(this);
    }

    public synchronized boolean unregisterPreToolUse(String name) {
        boolean removed = preToolUseHooks.remove(name) != null;
        if (removed) {
            log.info("HOOK unregistered PreToolUse: name={}", name);
        }
        return removed;
    }

    public synchronized boolean unregisterPostToolUse(String name) {
        boolean removed = postToolUseHooks.remove(name) != null;
        if (removed) {
            log.info("HOOK unregistered PostToolUse: name={}", name);
        }
        return removed;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 注册 / 注销 — GenericHook (§14 扩展)
    // ════════════════════════════════════════════════════════════════════════

    public synchronized void register(String name, GenericHook hook, HookEventType... events) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Hook name is blank");
        }
        if (hook == null) {
            throw new IllegalArgumentException("Hook is null");
        }
        genericHooks.put(name, hook);
        hookEventFilters.put(name, events == null || events.length == 0
            ? Set.of()
            : Set.of(events));
        log.info("HOOK registered GenericHook: name={}, events={}", name,
            events == null || events.length == 0 ? "ALL" : Set.of(events));
    }

    // [IMPL-10] DEL-L03-04: 4 参 sessionId register 重载已删除（会话作用域由 SessionHookStore 承担）
    public synchronized boolean unregister(String name) {
        boolean removed = genericHooks.remove(name) != null;
        hookEventFilters.remove(name);
        if (removed) {
            log.info("HOOK unregistered GenericHook: name={}", name);
        }
        return removed;
    }
    // ════════════════════════════════════════════════════════════════════════
    // [MPL7] 插件 hook 注册/清理 · 对齐 CC loadPluginHooks.ts 的 registerHookCallbacks
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [MPL7] 注册插件 hook（HookSource.PLUGIN_HOOK）· 对齐 CC registerHookCallbacks
     * （loadPluginHooks.ts:148）+ PluginHookMatcher 的 pluginName 上下文。
     *
     * <p>name 约定 {@code plugin:{pluginName}:{event}}，owner 记录 pluginName 供
     * {@link #prunePluginHooks(java.util.Set)} 按启用集剪除。
     *
     * <p>[MT-02] 同时把插件 hook 的 matcher 数据（matcher + plugin context + CommandHooks）
     * 记入 registered 源 store，供 {@link #getMatchingHooks(HookEvent)} 并入统一单链
     * （对齐 CC getHooksConfig 把 PluginHookMatcher 并入 getMatchingHooks，hooks.ts:1519-1529）。
     *
     * @param name       hook 名（需唯一）
     * @param pluginName 所属插件名（prune 依据）
     * @param hook       GenericHook 实现
     * @param events     监听事件（空 → 全部）
     */
    public synchronized void registerPluginHook(String name, String pluginName,
                                                GenericHook hook, HookEventType... events) {
        register(name, hook, events);
        pluginHookOwners.put(name, pluginName);
        if (log.isInfoEnabled()) {
            log.info("[MPL7] PLUGIN_HOOK registered: name={} plugin={} events={}", name, pluginName,
                events == null || events.length == 0 ? "ALL" : java.util.Arrays.toString(events));
        }
    }

    /**
     * [MT-02] registered/插件 hook matcher 注册 · 对齐 CC PluginHookMatcher
     * （loadPluginHooks.ts:74-81, {@code convertPluginHooksToMatchers}）+ {@code registerHookCallbacks}
     * （state.ts:1419-1434）。
     *
     * <p>WHY: CC getHooksConfig 把 registered 源（SDK callback + plugin native hooks）并入
     * getMatchingHooks 统一单链 (hooks.ts:1519-1529), 每个 registered matcher 与 settings
     * matcher 一样参与统一 matcher 过滤/去重/if (hooks.ts:1681-1848). 本方法把插件 hook 的
     * matcher 数据记入 {@link #registeredHookMatchers} store, 供 {@link #getMatchingHooks(HookEvent)}
     * 在 managedOnly 门控后并入 {@link HookMatcherEngine#getMatchingHooks} 统一链 —— 使
     * {@code getMatchingHooks} 返回集对齐 CC 单链 (跨模块消费 遥测/executeEvent 聚合/Stop 基于统一链).
     *
     * @param event      监听事件
     * @param matcher    CC original: PluginHookMatcher.matcher（loadPluginHooks.ts:74-81）;
     *                   null/空 = 匹配全部
     * @param pluginRoot CC original: PluginHookMatcher.pluginRoot（插件根目录）; null = 非插件源
     * @param pluginId   CC original: PluginHookMatcher.pluginId（插件 ID, pluginHookCounts 分类）
     * @param pluginName CC original: PluginHookMatcher.pluginName（hookSource 'plugin:name'）; null = 非插件源
     * @param skillRoot  CC original: SkillHookMatcher.skillRoot; null = 非 skill 源
     * @param hooks      CC original: matcher.hooks（CommandHook 数组）
     */
    public synchronized void registerRegisteredHookMatcher(HookEventType event, String matcher,
                                                           String pluginRoot, String pluginId,
                                                           String pluginName, String skillRoot,
                                                           java.util.List<? extends HookCommand> hooks) {
        java.util.List<HookCommand> hooksCopy = hooks == null
            ? java.util.List.of()
            : java.util.List.<HookCommand>copyOf(hooks);
        registeredHookMatchers.computeIfAbsent(event, k -> new ArrayList<>())
            .add(new HookMatcherEngine.RegisteredHookMatcher(matcher, pluginRoot, pluginId,
                pluginName, skillRoot, hooksCopy));
        if (log.isDebugEnabled()) {
            log.debug("HOOK registered matcher: event={} matcher={} plugin={} hooks={}",
                event, matcher, pluginName, hooks == null ? 0 : hooks.size());
        }
    }

    /**
     * [MT-02] registered/插件 hook matcher store · event → matcher 列表。
     *
     * <p>对齐 CC {@code getRegisteredHooks()?.[hookEvent]} (hooks.ts:1519, state.ts:1436-1440)
     * —— CC 的 registered 源按事件存储 matcher 数组 (RegisteredHookMatcher = HookCallbackMatcher
     * | PluginHookMatcher). Java 端用 {@link HookMatcherEngine.RegisteredHookMatcher} 统一承载
     * 插件 native hooks (matcher + plugin context) 与 SDK callback matcher。
     */
    private final Map<HookEventType, List<HookMatcherEngine.RegisteredHookMatcher>>
        registeredHookMatchers = new LinkedHashMap<>();

    /** [MPL7] 已注册插件 hook 名（测试可观测 + clear/prune 范围）。 */
    public synchronized java.util.Set<String> pluginHookNames() {
        return java.util.Set.copyOf(pluginHookOwners.keySet());
    }

    /**
     * [hooks-plugin-display] 读取已注册<b>插件</b> hook 配置（供 UI 展示）· 对齐 CC
     * {@code hooksConfigManager.ts:323-345}（{@code groupHooksByEventAndMatcher} 的
     * {@code getRegisteredHooks()} 分支：对含 {@code pluginRoot} 的 PluginHookMatcher，把
     * matcher.hooks 逐个转 {@code {event, config, matcher, source:'pluginHook', pluginName}}）。
     *
     * <p><b>WHY（插件 hook 展示链路）</b>：CC 端插件 hook 从不进 {@code getAllHooks}
     * （hooksSettings.ts:92-161 sources 硬编码 3 editable + session），而是走独立
     * registeredHooks 通道进 UI 分组 map。Java 端同理——插件 hook 元数据存于
     * {@link #registeredHookMatchers}（{@code registerRegisteredHookMatcher} 写入），
     * 本方法把该 store 中 {@code pluginRoot != null}（插件，非 SDK callback）的 matcher
     * 转成 {@link IndividualHookConfig} 列表，供 {@code HookController} 合并进
     * {@code GET /api/v1/hooks} 响应（前端 HookPanel 按 source=PLUGIN_HOOK + pluginName 渲染）。
     *
     * <p><b>与 getAllHooks 折叠无关</b>：本方法<b>不</b>写 HooksSettings PLUGIN_HOOK 源
     * （DEL-CFG-B 单轨化维持），只读 registeredHookMatchers 生成展示视图——对齐 CC
     * registeredHooks 通道（非 getAllHooks），执行链（GenericHook）零影响。
     *
     * <p><b>pluginName vs pluginId</b>：展示取 {@code matcher.pluginName()}（= plugin.name()，
     * 如 {@code zjkycode@zjkycode}），不用 pluginId（= plugin.source().name()，如
     * {@code MARKETPLACE}）——对齐前端 HookPanel 渲染 {@code {pluginName}}。
     *
     * @return 插件 hook 配置列表（event/config/matcher/source=PLUGIN_HOOK/pluginName）；
     *         无插件 hook → 空列表
     */
    public synchronized List<IndividualHookConfig> getRegisteredPluginHookConfigs() {
        List<IndividualHookConfig> out = new ArrayList<>();
        for (Map.Entry<HookEventType, List<HookMatcherEngine.RegisteredHookMatcher>> e
                : registeredHookMatchers.entrySet()) {
            HookEventType event = e.getKey();
            List<HookMatcherEngine.RegisteredHookMatcher> matchers = e.getValue();
            if (matchers == null || matchers.isEmpty()) {
                continue;
            }
            for (HookMatcherEngine.RegisteredHookMatcher matcher : matchers) {
                // CC hooksConfigManager.ts:335 'pluginRoot' in matcher —— 插件（非 SDK callback）
                if (matcher.pluginRoot() == null) {
                    continue;
                }
                if (matcher.hooks() == null) {
                    continue;
                }
                for (HookCommand hook : matcher.hooks()) {
                    if (hook == null) {
                        continue;
                    }
                    out.add(new IndividualHookConfig(
                        event, hook, matcher.matcher(), HookSource.PLUGIN_HOOK, matcher.pluginName()));
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[hooks-plugin-display] getRegisteredPluginHookConfigs: {} 个插件 hook（CC hooksConfigManager.ts:323-345 registeredHooks 通道）",
                out.size());
        }
        return List.copyOf(out);
    }

    /** [MPL7] 清空全部插件 hook · 对齐 loadPluginHooks clear-then-register 的 clear 半（:147）。 */
    public synchronized void clearPluginHooks() {
        int n = pluginHookOwners.size();
        for (String name : new ArrayList<>(pluginHookOwners.keySet())) {
            unregister(name);
        }
        pluginHookOwners.clear();
        // [IMP-HR-02 R-3] registeredHookMatchers store 同步清空插件 matchers · 对齐 CC
        //   clearRegisteredPluginHooks（state.ts:1446-1461 仅移除插件 matchers, 保留 SDK callback
        //   matchers, 无 pluginRoot）——否则 loadPluginHooks 每会话/热重载逐组追加, 插件 matcher
        //   重复 N 份 + 禁用插件 matcher 残留（loadPluginHooks.ts:147 clearRegisteredPluginHooks
        //   + :148 registerHookCallbacks 原子换的 clear 半必须覆盖 registered 源）。
        clearRegisteredPluginMatchers();
        if (log.isInfoEnabled()) {
            log.info("[MPL7] clearPluginHooks: 移除 {} 个插件 hook + 清空 registered 插件 matchers (对齐 CC loadPluginHooks.ts:147)", n);
        }
    }

    /** [IMP-HR-02 R-3] 移除 registeredHookMatchers 中全部插件 matchers（pluginRoot != null），
     *  保留 SDK callback matchers（pluginRoot == null）· 对齐 CC clearRegisteredPluginHooks
     *  （state.ts:1446-1461）。 */
    private void clearRegisteredPluginMatchers() {
        registeredHookMatchers.entrySet().removeIf(e -> {
            List<HookMatcherEngine.RegisteredHookMatcher> kept = new ArrayList<>();
            for (HookMatcherEngine.RegisteredHookMatcher rm : e.getValue()) {
                if (rm.pluginRoot() == null) {
                    kept.add(rm); // SDK callback matcher 保留（无 pluginRoot）
                }
            }
            e.setValue(kept);
            return kept.isEmpty();
        });
    }

    /**
     * [MPL7] 剪除不再 enabled 插件的 hooks · 对齐 CC pruneRemovedPluginHooks（loadPluginHooks.ts:179-204）。
     * 仅移除（不新增），使禁用/卸载插件立即停止触发（gh-36995 语义）。
     *
     * <p>[IMP-HR-02 R-3] registeredHookMatchers store 同步剪除：移除 pluginName 不在 enabled 集的
     * 插件 matchers（保留 SDK callback matchers, pluginRoot == null）——否则已禁用插件 env hooks
     * 经 env 收集链继续发射（loadPluginHooks.ts:179-204 pruneRemovedPluginHooks survivors 重建）。
     *
     * @param enabledPluginNames 当前 enabled 插件名集合
     */
    public synchronized void prunePluginHooks(java.util.Set<String> enabledPluginNames) {
        List<String> toRemove = pluginHookOwners.entrySet().stream()
            .filter(e -> !enabledPluginNames.contains(e.getValue()))
            .map(Map.Entry::getKey)
            .toList();
        for (String name : toRemove) {
            unregister(name);
            pluginHookOwners.remove(name);
        }
        pruneRegisteredPluginMatchers(enabledPluginNames);
        if (!toRemove.isEmpty() && log.isInfoEnabled()) {
            log.info("[MPL7] prunePluginHooks: 移除 {} 个插件 hook + 剪除 registered 插件 matchers (CC loadPluginHooks.ts:179-204)", toRemove.size());
        }
    }

    /** [IMP-HR-02 R-3] 从 registeredHookMatchers 剪除 pluginName 不在 enabled 集的插件 matchers ·
     *  对齐 CC pruneRemovedPluginHooks（loadPluginHooks.ts:179-204 survivors 重建）。SDK callback
     *  matchers（pluginRoot == null）不受 prune 影响。 */
    private void pruneRegisteredPluginMatchers(java.util.Set<String> enabledPluginNames) {
        registeredHookMatchers.entrySet().removeIf(e -> {
            List<HookMatcherEngine.RegisteredHookMatcher> kept = new ArrayList<>();
            for (HookMatcherEngine.RegisteredHookMatcher rm : e.getValue()) {
                if (rm.pluginRoot() == null
                        || (rm.pluginName() != null && enabledPluginNames.contains(rm.pluginName()))) {
                    kept.add(rm); // SDK callback 或仍 enabled 的插件 matcher 保留
                }
            }
            e.setValue(kept);
            return kept.isEmpty();
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    // [Session H5] SessionHookStore 委托 · 对齐 CC sessionHooks.ts (SessionHooksState)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 注册 session command hook · 委托 {@link SessionHookStore} (对齐 CC sessionHooks.ts:68-86).
     *
     * <p>WHY: sub-agent 会话内临时 hook 走本入口; 委托而非内联 — SessionHookStore 独立持有
     * 三级存储, 本类只做数据流日志 + 转发 (规则五: 路由分发由代码完成).
     */
    public void addSessionHook(String sessionId, HookEventType event, String matcher, HookCommand hook,
                               SessionHookStore.OnHookSuccess onHookSuccess, String skillRoot) {
        sessionHookStore.addSessionHook(sessionId, event, matcher, hook, onHookSuccess, skillRoot);
        log.info("[H5] 数据流: addSessionHook session={} event={} matcher={} type={}",
                sessionId, event, matcher, hook.type());
    }

    /**
     * 注册 session function hook · 委托 {@link SessionHookStore} (对齐 CC sessionHooks.ts:93-115).
     *
     * @return 生成的 hook id (供 removeFunctionHook 移除)
     */
    public String addFunctionHook(String sessionId, HookEventType event, String matcher,
                                  FunctionHookCallback callback, String errorMessage,
                                  Long timeout, String id) {
        String hookId = sessionHookStore.addFunctionHook(sessionId, event, matcher, callback, errorMessage, timeout, id);
        log.info("[H5] 数据流: addFunctionHook session={} event={} matcher={} hookId={}",
                sessionId, event, matcher, hookId);
        return hookId;
    }

    /**
     * [IMP-HR-08 · OPD-WF6-01-06-?-3] 主循环 structured output enforcement 接线 · 对齐 CC
     * {@code registerStructuredOutputEnforcement} (hookHelpers.ts:70-83) + 主循环注册点
     * (QueryEngine.ts:327-333)。
     *
     * <p><b>CC 真源（已实读，非抄注释）</b>:
     * <ul>
     *   <li>{@code QueryEngine.ts:328-333}: {@code if (jsonSchema && hasStructuredOutputTool)
     *       registerStructuredOutputEnforcement(setAppState, getSessionId())} —— 主循环 jsonSchema
     *       门控注册（GC-002 缺口：Java 仅 ExecAgentHook fork 路径注册，主循环无 jsonSchema 引用）。
     *       <b>语义差异（IMP-HR-08 返工 R-1 更正）</b>：CC 该门控的 {@code hasStructuredOutputTool}
     *       检查 LLM-facing 工具数组（main.tsx:1886 把 SyntheticOutputTool 加入 tools）；Java 主循环
     *       {@link ToolRegistry#toOpenAiToolsArray} 对 SYNTHETIC_OUTPUT_TOOL_NAME 走 SPECIAL_TOOLS
     *       过滤（仅 skipSpecialToolsFilter 才暴露）→ Java 侧本方法由单门控
     *       {@code params.jsonSchema() != null} 驱动，CC 语义的 hasStructuredOutputTool 当前主循环恒
     *       false，enforcement 在工具未暴露前不可满足（R1，完整 enablement 需 per-tool exemption）。</li>
     *   <li>{@code hookHelpers.ts:74-82}: {@code addFunctionHook(setAppState, sessionId, 'Stop',
     *       '', messages => hasSuccessfulToolCall(messages, SYNTHETIC_OUTPUT_TOOL_NAME),
     *       ENFORCEMENT_PROMPT, {timeout: 5000})} —— session 级 Stop function hook</li>
     *   <li>{@code messages.ts:4719-4760}: {@code hasSuccessfulToolCall} 反向扫最近一次
     *       tool_use → tool_result {@code is_error !== true} 即成功</li>
     * </ul>
     *
     * <p><b>Java 适配</b>: 经 session function hook 通道（{@link SessionHookStore}，CC session 级
     * function hook 等价）注册 Stop 事件强制回调，与 fork 路径（ExecAgentHook 的
     * {@link StructuredOutputEnforcementHook} GenericHook）共用 {@link
     * StructuredOutputEnforcementHook#hasSuccessfulToolCall} 静态判定。callback 返回
     * {@code true} = 已成功调用 StructuredOutput → 放行；{@code false} = 未调用 → blocking
     * （blockingError = ENFORCEMENT_PROMPT）触发 LlmAgentLoop stop 段重入注入强制提示
     * （query.ts:1274-1277 同语义）。timeout 复用 CC hookHelpers.ts:81 {@code {timeout:5000}}。
     *
     * <p><b>remove-then-add</b>: 稳定 id {@value #ENFORCEMENT_FUNCTION_HOOK_ID} 防同会话多
     * run() 累积重复 enforcement hook（CC 每 query() 注册一次、跨 query 累积；Java run()=一次
     * 用户回合，remove-then-add 保证任意时刻至多一条，避免 Stop 重复注入相同强制提示）。
     *
     * @param sessionId 主会话 ID（CC {@code getSessionId()}）; null/blank → 不注册（无可执行
     *                  session hook）
     * @return 生成的 function hook id（供 {@link #removeFunctionHook} 移除）; sessionId 空 → null
     */
    public String registerStructuredOutputEnforcement(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            if (log.isWarnEnabled()) {
                log.warn("registerStructuredOutputEnforcement: sessionId 空, 跳过注册（CC 无会话时 getSessionId 不可用）");
            }
            return null;
        }
        removeFunctionHook(sessionId, HookEventType.STOP, ENFORCEMENT_FUNCTION_HOOK_ID);
        return addFunctionHook(sessionId, HookEventType.STOP, "",
            (messages, signal) -> CompletableFuture.completedFuture(
                StructuredOutputEnforcementHook.hasSuccessfulToolCall(
                    messages, ToolNameConstants.SYNTHETIC_OUTPUT_TOOL_NAME)),
            StructuredOutputEnforcementHook.ENFORCEMENT_PROMPT,
            StructuredOutputEnforcementHook.CALL_TIMEOUT_MS,
            ENFORCEMENT_FUNCTION_HOOK_ID);
    }

    /**
     * 按 id 移除 session function hook · 委托 {@link SessionHookStore} (对齐 CC sessionHooks.ts:120-162).
     */
    public void removeFunctionHook(String sessionId, HookEventType event, String hookId) {
        sessionHookStore.removeFunctionHook(sessionId, event, hookId);
        log.info("[H5] 数据流: removeFunctionHook session={} event={} hookId={}", sessionId, event, hookId);
    }

    /**
     * 按 isHookEqual 移除 session command hook · 委托 {@link SessionHookStore} (对齐 CC sessionHooks.ts:225-268).
     */
    public void removeSessionHook(String sessionId, HookEventType event, HookCommand hook) {
        sessionHookStore.removeSessionHook(sessionId, event, hook);
        log.info("[H5] 数据流: removeSessionHook session={} event={} type={}", sessionId, event, hook.type());
    }

    /**
     * 查询 session hooks (不含 function) · 委托 {@link SessionHookStore} (对齐 CC sessionHooks.ts:302-330).
     */
    public Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> getSessionHooks(
            String sessionId, HookEventType event) {
        return sessionHookStore.getSessionHooks(sessionId, event);
    }

    /**
     * 查询 session function hooks · 委托 {@link SessionHookStore} (对齐 CC sessionHooks.ts:345-392).
     */
    public Map<HookEventType, List<SessionHookStore.FunctionHookMatcher>> getSessionFunctionHooks(
            String sessionId, HookEventType event) {
        return sessionHookStore.getSessionFunctionHooks(sessionId, event);
    }

    /**
     * 查询完整 hook entry (含 onHookSuccess 回调) · 委托 {@link SessionHookStore} (对齐 CC sessionHooks.ts:397-430).
     */
    public Optional<SessionHookStore.SessionHookEntry> getSessionHookCallback(
            String sessionId, HookEventType event, String matcher, SessionHook hook) {
        return sessionHookStore.getSessionHookCallback(sessionId, event, matcher, hook);
    }

    /**
     * 清空 session 全部临时 hook · 委托 {@link SessionHookStore} (对齐 CC sessionHooks.ts:437-447).
     *
     * <p>WHY: {@link SubagentExecutor} finally 调用本方法 (对齐 CC runAgent.ts:822 clearSessionHooks),
     * 防止 sub-agent 会话的临时 hook 泄漏到后续会话复用.
     */
    public void clearSessionHooks(String sessionId) {
        sessionHookStore.clearSessionHooks(sessionId);
        log.info("[H5] 数据流: clearSessionHooks session={}", sessionId);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 执行 — PreToolUse / PostToolUse
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [Session H8] PreTool hook blocking error 格式化 · 对齐 CC
     * {@code getPreToolHookBlockingMessage} (Open-ClaudeCode/src/utils/hooks.ts:1882-1887):
     * <pre>
     *   return `${hookName} hook error: ${blockingError.blockingError}`
     * </pre>
     *
     * <p>WHY: CC runPreToolUseHooks (toolHooks.ts:481-498) 把 blockingError 格式化为
     * 该文本后作为 deny 决策的 message 注入 LLM (工具不执行 + 模型可见反馈).
     * Java 端 StreamingToolExecutor 在 PreToolUse 消费段用本方法翻译 blockingError
     * → hook deny, 保证 LLM 反馈文本与 CC 生态逐字符一致.
     *
     * @param hookName       hook 名 (e.g., 'PreToolUse:Bash' · CC original: hookName, hooks.ts:1883)
     * @param blockingError  阻塞错误 (CC original: blockingError: HookBlockingError, hooks.ts:1884)
     * @return 格式化文本 {@code "<hookName> hook error: <blockingError>"}
     */
    public static String getPreToolHookBlockingMessage(
            String hookName, HookBlockingError blockingError) {
        // [IMPL-03 DEL-TH-03] CC hooks.ts:1882-1887 无 null-hookName 缺省 (模板字符串直拼);
        //   旧 Java 缺省 "PreToolUse" 是防御性偏离, 唯一生产调用方恒传非 null → 移除.
        String error = blockingError != null ? blockingError.blockingError() : null;
        return hookName + " hook error: " + (error != null ? error : "");
    }

    /**
     * 执行所有 PreToolUse hooks (全量执行, 不短路) · 对齐 CC toolHooks.ts:435-461 7 类 case union.
     *
     * <p><b>P0-3 全量对齐</b>: 6 hook 全部返回 {@link AggregatedHookResult} (16 字段),
     * 本方法聚合所有 hook 的 16 字段 ([IMPL-07 r3] reason/source last-wins 配对、
     * message/additionalContexts concat 全保留、blockingError last-wins, 其余
     * 字段首非空), permissionBehavior 字段特殊按 deny > ask > allow 优先级聚合,
     * preventContinuation / retry 等 boolean/Boolean 字段取首个 true.
     *
     * <p><b>对齐 CC toolHooks.ts:480-580</b> 翻译层: 本聚合方法对应 CC 的 generator 逐 yield
     * 行为 — 16 字段聚合结果是 CC 多 yield 累积的"扁平 record" 形态. 消费侧
     * {@link com.nexusai.application.agent.tool.StreamingToolExecutor} 按 AHR 字段
     * 分支处理 7 类 case + 9 个额外字段.
     *
     * <p>hook 抛异常 → warn 日志 + 跳过 + 继续下一个. AbortException 透传.
     *
     * @param toolName 工具名
     * @param input    工具输入
     * @param ctx      工具调用上下文
     * @return 16 字段 {@link AggregatedHookResult} 聚合结果; 无 hook 或全 proceed → proceed().
     */
    public AggregatedHookResult executePreToolUse(String toolName, JsonNode input, ToolUseContext ctx) {
        return executePreToolUse(toolName, input, ctx, null, null);
    }

    /**
     * [IMP-HOOKS-S6 ⊕1] 4 参 executePreToolUse · 对齐 CC toolExecution.ts:800-809
     * runPreToolUseHooks — CC hook 入参面无 userModified/parentMessage/requestId
     * (旧 7 参重载删除, T6-⊕1).
     *
     * <p>P0-3 强化: 返回类型从 {@link PermissionResult} (4 态) 升级为
     * {@link AggregatedHookResult} (16 字段), 完整覆盖 CC 7 类 yield case.
     */
    public AggregatedHookResult executePreToolUse(String toolName, JsonNode input, ToolUseContext ctx,
                                                   String toolUseId) {
        return executePreToolUse(toolName, input, ctx, toolUseId, null);
    }

    /**
     * [IMP-HOOKS-S9 DEL-02d] 5 参 executePreToolUse · 对齐 CC executePreToolHooks 9 参签名
     * (hooks.ts:3394-3405) 的 hook 入参面 — CC 无 userModified/parentMessage/requestId
     * (旧 9 参重载删除, T6-⊕1); prompt 回调通道 参数已删 (DEL-02, Java 无 UI 消费端,
     * 删除前 HookRegistry 恒传 null → 可观测行为不变), 保留末 1 参 {@code toolInputSummary}.
     *
     * <p>WHY: CC 把 {@code tool.getToolUseSummary?.(processedInput)} (toolHooks.ts:475)
     * 透传给 hook 链, toolUseSummary 是工具输入的可读摘要.
     *
     * @param toolName        工具名
     * @param input           工具输入 (processedInput: strip+backfill 后, E9·CCJ-T6-22)
     * @param ctx             工具调用上下文
     * @param toolUseId       LLM 工具调用 ID
     * @param toolUseSummary  工具输入摘要 (CC original: toolInputSummary, hooks.ts:3405;
     *                        null = 工具未提供, 对齐 getToolUseSummary optional ?.)
     * @return 16 字段 {@link AggregatedHookResult} 聚合结果
     */
    public AggregatedHookResult executePreToolUse(String toolName, JsonNode input, ToolUseContext ctx,
                                                   String toolUseId,
                                                   String toolUseSummary) {
        return executePreToolUse(toolName, input, ctx, toolUseId, toolUseSummary, null);
    }

    /**
     * [IMP-RS-01 DEL-01e 补回] 6 参 executePreToolUse · 对齐 CC executePreToolHooks 9 参签名
     * (hooks.ts:3394-3405) 的 prompt 回调通道 (hooks.ts:3402-3405 requestPrompt 参数) —
     * requestPrompt 经 executeHooks 绑定 (hooks.ts:1990) 后传给 execCommandHook (hooks.ts:2460),
     * 使 command hook 可向用户请求补充输入.
     *
     * <p>WHY (DEL-01e 补回): 通道在 CC 真实存在 (REPL.tsx:2520 feature('HOOK_PROMPTS') 门控 →
     * toolHooks.ts:474 透传 → hooks.ts:1072-1110 消费); 用户拍板补回. 绑定发生在调用方
     * (StreamingToolExecutor PreToolUse 链, 等价 toolHooks.ts:474), 本方法只透传.
     *
     * @param toolName        工具名
     * @param input           工具输入 (processedInput)
     * @param ctx             工具调用上下文
     * @param toolUseId       LLM 工具调用 ID
     * @param toolUseSummary  工具输入摘要 (CC original: toolInputSummary, hooks.ts:3405)
     * @param promptRequester CC original: requestPrompt (hooks.ts:759); 绑定版 prompt 回调
     *                        (已绑 sourceName + toolInputSummary); null=通道关闭
     * @return 16 字段 {@link AggregatedHookResult} 聚合结果
     */
    public AggregatedHookResult executePreToolUse(String toolName, JsonNode input, ToolUseContext ctx,
                                                   String toolUseId,
                                                   String toolUseSummary,
                                                   PromptRequester promptRequester) {
        // [IMPL-01 D1-5 / INV-11 / OD-07] 政策闸门: programmatic 链不豁免
        //   (对齐 CC executePreToolHooks = yield* executeHooks, 入口短路 hooks.ts:1978-1980).
        //   旧实现 programmatic PreToolUse 完全绕过 disableAllHooks 政策.
        if (shouldDisableAllHooksIncludingManaged()) {
            if (log.isDebugEnabled()) {
                log.debug("executePreToolUse: policySettings.disableAllHooks=true, 跳过 programmatic hook (工具 {})",
                    toolName);
            }
            return AggregatedHookResult.proceed();
        }
        // [2026-08-12 △-04] CLAUDE_CODE_SIMPLE 短路 · 对齐 CC executeHooks 入口
        //   (hooks.ts:1981-1983) — executePreToolHooks = yield* executeHooks, programmatic
        //   链同门控.
        if (com.nexusai.application.agent.config.MemoryBareModeConfig.isBareMode()) {
            if (log.isDebugEnabled()) {
                log.debug("executePreToolUse: bare 模式 (CLAUDE_CODE_SIMPLE), 跳过 programmatic hook (工具 {})",
                    toolName);
            }
            return AggregatedHookResult.proceed();
        }
        // [IMPL-04 D9-1 / INV-9 / OD-13] trust 门控: programmatic 链不豁免 · 对齐 CC
        //   executePreToolHooks = yield* executeHooks (入口 trust 门控 hooks.ts:1994).
        //   旧实现 PreToolUse 链完全无 trust 门控 (EV-CCE-034).
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("executePreToolUse: workspace trust 未接受, 跳过 programmatic hook (工具 {})",
                    toolName);
            }
            return AggregatedHookResult.proceed();
        }
        // [IMPL-03 D6-1 / INV-7 / OD-05] 单链: 配置驱动 hook (settings.json + session) 与
        //   programmatic 链同链聚合 · 对齐 CC runPreToolUseHooks (toolHooks.ts:435-563)
        //   = executePreToolHooks = yield* executeHooks — config + registered + session
        //   全部 hook 同链执行, deny/allow/updatedInput/stopReason/additionalContext 全量消费.
        //   旧实现 fireGenericEvent 双总线桥接 (DEL-TH-02) 返回值丢弃 → 配置 hook 决策不生效.
        //   X5: toolPre 5 参重载传真实 toolUseId (旧 4 参重载 tool_use_id=null).
        // [fix-ts04 IMPL-01 OD-TS04-01 方案 B] 工具链 ctx 透传批级: 3 参 executeEvent
        //   (签名 :1903 已存在, Stop 段在用) — ctx 送达 executeConfiguredHooks,
        //   enrichBaseFields 在序列化前合并 agent_id/agent_type/permission_mode/cwd.
        // [IMP-HOOKS-S6 CCJ-T6-06/11] 批级事件 + 遥测接线: 事件先构造 (匹配复用),
        //   tengu_run_hook 批首 + 每匹配 hook started/progress 预执行 (CC hooks.ts:2094-2116),
        //   批尾 tengu_repl_hook_finished (方法出口统一发射).
        HookEvent toolPreEvent = HookEvent.toolPre(
            toolName, input,
            // [EX-HOOK R7 修正] CC 双轨语义（hooks.ts:315/:2003）：
            //   载荷 session_id 恒主会话（createBaseHookInput sessionId 参数 undefined →
            //   getSessionId()）；匹配 key 用 agentId ?? getSessionId()（hooks.ts:2003，
            //   仅 getMatchingHooks 用）。Java HookEvent 单字段双语义 → 事件 sessionId
            //   承载载荷（主会话），匹配侧查询用 event.agentId() ?? event.sessionId()
            //   （见 sessionCommandMatched）。修复前把 agentId 写进 sessionId 载荷
            //   导致 buildJsonInput session_id=agentId，偏离 CC。
            ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null,
            ctx != null && ctx.agentId() != null ? ctx.agentId().toString() : null,
            toolUseId);
        List<MatchedHook> matched = getMatchingHooks(toolPreEvent);
        List<Map.Entry<String, PreToolUseHook>> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(preToolUseHooks.entrySet());
        }
        long toolChainStartMs = System.currentTimeMillis();
        ToolChainOutcomes outcomes = new ToolChainOutcomes();
        long userSnapshotCount = userProgrammaticCount(snapshot);
        // [JS-05 GAP-9] beta-tracing OTEL hook_definitions 载荷 + hook span holder
        //   (批首 start, 各批尾 complete 配对, 对齐 CC hooks.ts:2070-2084/2946-2970).
        String betaDefsJson = isBetaTracingEnabled()
            ? getHookDefinitionsForTelemetry(matched, snapshot) : "[]";
        HookSpan betaSpan = HookSpan.disabled();
        if (!matched.isEmpty() || userSnapshotCount > 0) {
            emitToolHookRunTelemetry("PreToolUse", matched.size() + (int) userSnapshotCount,
                toolHookTypeCounts(matched, (int) userSnapshotCount),
                pluginHookCounts(matched));
            // [JS-05 GAP-9] beta-tracing OTEL hook_execution_start (CC hooks.ts:2076-2084).
            betaSpan = emitHookExecutionStartTelemetry(
                "PreToolUse", "PreToolUse:" + toolName,
                matched.size() + snapshot.size(), betaDefsJson);
            for (MatchedHook mh : matched) {
                emitToolHookStartedProgress(hookNameFor(mh), "PreToolUse", mh.hook(), null);
            }
            for (Map.Entry<String, PreToolUseHook> entry : snapshot) {
                emitToolHookStartedProgress(entry.getKey(), "PreToolUse", null, entry.getKey());
            }
        }
        // [IMP-RS-01 DEL-01e 补回] 透传 promptRequester (绑定版) · 对齐 CC toolHooks.ts:474
        //   executePreToolHooks 透传 toolUseContext.requestPrompt → executeHooks :3433 → execCommandHook
        GenericHook.HookResult configuredResult = executeEvent(toolPreEvent, null, ctx, promptRequester);
        // 配置驱动结果 → AHR (PermissionBehavior → PermissionResult; deny/ask 缺省文案对齐 CC)
        AggregatedHookResult configuredAhr = toAggregatedHookResult(configuredResult, toolName, input, toolUseId);
        // 配置驱动段 outcome 计数 (折叠表达, 见 emitToolHookFinishedTelemetry 注释)
        if (!matched.isEmpty()) {
            if (configuredResult == null || !hasIntervention(configuredResult)) {
                outcomes.success += matched.size();
            } else {
                int n = matched.size();
                switch (configuredResult.outcome()) {
                    case BLOCKING -> outcomes.blocking += n;
                    case NON_BLOCKING_ERROR -> outcomes.nonBlockingError += n;
                    case CANCELLED -> outcomes.cancelled += n;
                    case SUCCESS -> outcomes.success += n;
                }
            }
        }

        // [H4] programmatic hook 并行执行 · 对齐 CC executeHooks all(hookPromises) (hooks.ts:2744):
        //   每 hook 一个 future (独立超时 + 异常隔离). [S4 G14] 收集改完成序
        //   (CC all() generators.ts:56-71 逐完成序消费) — 旧 snapshot 注册序收集已删.
        //   WHY: CC executePreToolHooks (hooks.ts:3394-3444) = yield* executeHooks, 无串行路径.
        List<CompletableFuture<AggregatedHookResult>> futures = new ArrayList<>(snapshot.size());
        // 完成序载体 · thenAccept 回调在完成线程 postComplete 级联中执行
        java.util.Queue<IndexedAhr> completed = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 0; i < snapshot.size(); i++) {
            Map.Entry<String, PreToolUseHook> entry = snapshot.get(i);
            int idx = i;
            CompletableFuture<AggregatedHookResult> f = submitPreToolUseHook(entry.getKey(), entry.getValue(),
                toolName, input, ctx, toolUseId, toolUseSummary);
            f.thenAccept(r -> completed.add(new IndexedAhr(idx, r)));
            futures.add(f);
        }
        // [H4] 并行等待全部 hook 完成 · 对齐 CC all(hookPromises) (hooks.ts:2744).
        //   单 hook 超时/异常已在 future 内隔离为 null 正常完成; 仅 AbortException 会异常完成
        //   (用户中止意图不可吞, 对齐 CC hooks.ts:2045-2051), 此处解包后透传给 caller.
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof AbortException ae) {
                // [IMP-HOOKS-S6 CCJ-T6-11] abort 中断批 → 全部未完成 hook 按 cancelled
                //   计数 (CC executeHooks AbortError → 逐 hook cancelled outcome), 批尾
                //   遥测先发射再透传 (用户中止意图不可吞).
                outcomes.cancelled += snapshot.size();
                emitToolHookFinishedTelemetry("PreToolUse", outcomes,
                    System.currentTimeMillis() - toolChainStartMs);
                // [JS-05 GAP-9] beta-tracing OTEL hook_execution_complete (abort 路径,
                //   遵循既有 Java tengu 惯例 (abort 仍发射批尾遥测); CC AbortError 经
                //   all() Promise.race reject 传播 (generators.ts:32-72), 中断 executeHooks
                //   的 for-await 循环 (hooks.ts:2744), 尾部遥测 hooks.ts:2935-2971
                //   (tengu_repl_hook_finished / hook_execution_complete / endHookSpan) 被跳过,
                //   此处发射 complete 为 Java 侧延续偏差).
                //   [R-3] 与 start 同条件门控: all-internal 批次 (matched 空 && 无非 internal
                //   programmatic) 对齐 CC fast-path (hooks.ts:2019-2067) start/complete 均不发射.
                if (!matched.isEmpty() || userSnapshotCount > 0) {
                    emitHookExecutionCompleteTelemetry("PreToolUse", "PreToolUse:" + toolName,
                        matched.size() + snapshot.size(), outcomes, betaDefsJson, betaSpan);
                }
                throw ae;
            }
            log.warn("HOOK 并行等待异常(非 abort): {}", cause != null ? cause.toString() : ce.toString());
        }
        // [P0-3 + IMPL-07 r3 16 字段聚合] reason/source last-wins 配对 (behaviorSeen 后
        // 随 next 覆盖含 null), message/additionalContexts concat 全保留, blockingError
        // last-wins, 其余字段首非空, boolean 字段 any-true (见 mergeAggregated).
        // [S4 G14] 完成序 drain — thenAccept 回调体仅入队 (不可阻塞), allOf().join()
        // 返回瞬间最后一批回调可能尚在完成线程级联中, 有界自旋等待队列满 (微秒级, 确定性).
        int spins = 0;
        while (completed.size() < futures.size() && spins++ < 100_000) {
            Thread.onSpinWait();
        }
        if (completed.size() < futures.size()) {
            // 防御兜底 (理论不可达): 按注册序补齐缺失项, 防聚合丢 hook
            for (int i = 0; i < futures.size(); i++) {
                int idx = i;
                if (completed.stream().noneMatch(ir -> ir.index() == idx)) {
                    completed.add(new IndexedAhr(idx, futures.get(i).join()));
                }
            }
        }
        AggregatedHookResult firstResult = AggregatedHookResult.proceed();
        // priority-permitting buckets for permissionBehavior (Deny > Ask > Allow)
        PermissionResult denyBehavior = null;
        PermissionResult askBehavior = null;
        PermissionResult allowBehavior = null;
        // [IMPL-03 D6-1 / OD-05] 配置驱动结果并入聚合链 (与 programmatic 平权):
        //   deny > ask > allow 桶同规则, 逐字段聚合同 mergeAggregated 语义 (IMPL-07 后:
        //   reason/source last-wins 配对, message/additionalContexts concat, 其余首非空).
        //   WHY: CC 单链中配置 hook 的决策与 programmatic hook 无优先级差 (同一 HookResult
        //   流), 仅 deny > ask > allow 行为优先级 (CC hooks.ts:2820-2847).
        if (configuredAhr != null) {
            firstResult = mergeAggregated(firstResult, configuredAhr);
            PermissionResult cpb = configuredAhr.permissionBehavior();
            if (cpb instanceof PermissionResult.Deny) {
                denyBehavior = cpb;
            } else if (cpb instanceof PermissionResult.Ask && askBehavior == null) {
                askBehavior = cpb;
            } else if (cpb instanceof PermissionResult.Allow && allowBehavior == null) {
                allowBehavior = cpb;
            }
            if (log.isDebugEnabled()) {
                log.debug("HOOK PreToolUse 配置驱动结果并入 (工具 {}): permBehavior={}",
                    toolName, cpb != null ? cpb.getClass().getSimpleName() : "null");
            }
        }
        int drainIndex = 0;
        for (IndexedAhr ir : completed) {
            String hookName = snapshot.get(ir.index()).getKey();
            AggregatedHookResult result = ir.result();
            if (result == null) {
                // 超时/异常/空结果 → 跳过 (已在上游 future 内 warn+telemetry);
                // [IMP-HOOKS-S6 CCJ-T6-11] 超时/异常按 non_blocking_error 计数 (CC :2735-2738)
                outcomes.nonBlockingError++;
                drainIndex++;
                continue;
            }

            // [IMP-HOOKS-S6 CCJ-T6-11] programmatic outcome 计数 (AHR 无 outcome 字段,
            //   按 CC PreToolUse 语义推导: blockingError/Deny → blocking, 其余 success)
            if (result.permissionBehavior() instanceof PermissionResult.Deny
                || result.blockingError() != null) {
                outcomes.blocking++;
            } else {
                outcomes.success++;
            }
            drainIndex++;

            // permissionBehavior 优先级聚合 (deny > ask > allow, 对齐 CC hooks.ts:2820-2847)
            PermissionResult pb = result.permissionBehavior();
            if (pb != null) {
                if (pb instanceof PermissionResult.Deny) {
                    denyBehavior = pb;
                } else if (pb instanceof PermissionResult.Ask && askBehavior == null) {
                    askBehavior = pb;
                } else if (pb instanceof PermissionResult.Allow && allowBehavior == null) {
                    allowBehavior = pb;
                }
            }

            // [IMPL-07 r3] 字段级聚合: reason/source last-wins、message/additionalContexts
            // concat、blockingError last-wins, 其余首非空 (见 mergeAggregated)
            firstResult = mergeAggregated(firstResult, result);
            if (log.isDebugEnabled()) {
                log.debug("HOOK PreToolUse '{}' merged for tool={}: permBehavior={}, hasUpdatedInput={}",
                    hookName, toolName,
                    pb != null ? pb.getClass().getSimpleName() : "null",
                    result.updatedInput() != null);
            }
            // [Session H5] CC toolHooks.ts:582 if (abortController.signal.aborted) ->
            //   logEvent('tengu_pre_tool_hooks_cancelled') + yield hook_cancelled + yield stop + return.
            //   Java 同步聚合: 检测 abort 后发 cancelled telemetry + 终止后续聚合 (break).
            if (ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled()) {
                emitHookCancelledTelemetry(toolName, "tengu_pre_tool_hooks_cancelled");
                // [IMP-HOOKS-S6 CCJ-T6-11] 剩余未 drain hook 按 cancelled 计数 (CC 逐
                //   result cancelled 计数等价); completed 队列后续回调可能继续入队,
                //   以当前已知未处理数计 (近似, 登记于进度文件).
                outcomes.cancelled += completed.size() - drainIndex;
                break;
            }
        }
        // [IMP-HOOKS-S6 CCJ-T6-11] 批尾遥测 (CC hooks.ts:2935-2944)
        emitToolHookFinishedTelemetry("PreToolUse", outcomes,
            System.currentTimeMillis() - toolChainStartMs);
        // [JS-05 GAP-9] beta-tracing OTEL hook_execution_complete (CC hooks.ts:2946-2963).
        //   [R-3] 与 start 同条件门控: all-internal 批次对齐 CC fast-path start/complete 均不发射.
        if (!matched.isEmpty() || userSnapshotCount > 0) {
            emitHookExecutionCompleteTelemetry("PreToolUse", "PreToolUse:" + toolName,
                matched.size() + snapshot.size(), outcomes, betaDefsJson, betaSpan);
        }
        // permissionBehavior 优先级解析 (deny > ask > allow, 对齐 CC hooks.ts:2820-2847)
        PermissionResult finalPermissionBehavior = denyBehavior != null ? denyBehavior
            : askBehavior != null ? askBehavior
            : allowBehavior != null ? allowBehavior
            : firstResult.permissionBehavior();

        return new AggregatedHookResult(
            firstResult.message(),
            firstResult.blockingError(),
            firstResult.preventContinuation(),
            firstResult.stopReason(),
            firstResult.hookPermissionDecisionReason(),
            firstResult.hookSource(),
            finalPermissionBehavior,
            firstResult.additionalContexts(),
            firstResult.initialUserMessage(),
            firstResult.updatedInput(),
            firstResult.updatedMCPToolOutput(),
            firstResult.permissionRequestResult(),
            firstResult.watchPaths(),
            firstResult.elicitationResponse(),
            firstResult.elicitationResultResponse(),
            firstResult.retry()
        );
    }

    /**
     * 16 字段 AHR 字段级聚合 · 用于 HookRegistry.executePreToolUse.
     *
     * <p><b>[IMPL-07 D3-1/D3-2/EV-L01-028] reason/source 改 last-wins 配对</b>:
     * CC executeHooks 逐结果 yield {@code {permissionBehavior(聚合), hookPermissionDecisionReason,
     * hookSource, updatedInput}} (hooks.ts:2849-2868), 消费端 {@code hookPermissionResult =
     * result.hookPermissionResult} 后到覆盖 (toolExecution.ts:831-832). 一旦聚合链出现
     * permissionBehavior, 后续每个 result 都以<b>自身</b> reason/source 覆盖 (含 null —
     * 对齐 CC yield 携带 undefined); 最小反例 A(allow,reasonA)+B(ask,reasonB) →
     * CC {ask,reasonB} vs 旧 Java {ask,reasonA} (首非空).
     *
     * <p><b>[IMPL-07 D3-3/EV-003/019] 多 hook 消息不丢</b>:
     * <ul>
     *   <li>{@code message} — 全保留 (List concat). CC 逐结果 yield message (hooks.ts:2765-2767),
     *       消费端 resultingMessages.push 全部生效 (toolExecution.ts:815-829).</li>
     *   <li>{@code additionalContexts} — 全保留 (List concat, 同上 CC :2783-2790 yield).</li>
     *   <li>{@code blockingError} — last-wins. CC PreToolUse 逐 blockingError yield deny
     *       hookPermissionResult (toolHooks.ts:481-498), 消费端后到覆盖 → 最后阻断生效.</li>
     * </ul>
     *
     * <p>boolean/Boolean 字段取 any-true (首个 true 胜出后保留); boolean primitive 视为
     * 默认 false (不聚合, 除非新值 true). 其他 Object/String/Map/List 字段取首个非 null
     * (reason/source/message/additionalContexts/blockingError 例外, 见上).
     *
     * <p>permissionBehavior 字段不在此处做优先级解析 — 调用方按 deny > ask > allow 单独
     * 聚合 (CC hooks.ts:2820-2847), 本方法仅做 last-non-null 传递.
     */
    private static AggregatedHookResult mergeAggregated(AggregatedHookResult base, AggregatedHookResult next) {
        // 阻止 (preventContinuation) — 任何 true 胜出
        boolean pc = base.preventContinuation() || next.preventContinuation();
        // retry — 任何 true 胜出 (null 视为 false)
        Boolean retry = base.retry() != null && base.retry() ? base.retry()
            : (next.retry() != null && next.retry() ? next.retry()
            : (base.retry() != null ? base.retry() : next.retry()));
        // [IMPL-07 D3-1/D3-2] reason/source last-wins · 对齐 CC hooks.ts:2862-2867
        //   (reason/source 随"当前 result"配对) + toolExecution.ts:831-832 (后到覆盖).
        //   一旦聚合链出现 permissionBehavior, 后续每个 result 覆盖 reason/source (含 null).
        boolean behaviorSeen = base.permissionBehavior() != null || next.permissionBehavior() != null;
        String reason = behaviorSeen ? next.hookPermissionDecisionReason()
            : (base.hookPermissionDecisionReason() != null ? base.hookPermissionDecisionReason()
                : next.hookPermissionDecisionReason());
        String source = behaviorSeen ? next.hookSource()
            : (base.hookSource() != null ? base.hookSource() : next.hookSource());
        // [IMPL-07 D3-3] message 全保留 (concat) · CC 逐结果 yield message → 消费端全 push
        List<AttachmentMessageDto> messages = concatLists(base.message(), next.message());
        // [IMPL-07 D3-3] additionalContexts 全保留 (concat) · CC :2783-2790 逐结果 yield
        List<String> contexts = concatLists(base.additionalContexts(), next.additionalContexts());
        // [IMPL-07 D3-3] blockingError last-wins · CC PreToolUse 逐 blockingError yield deny,
        //   消费端后到覆盖 (toolHooks.ts:481-498 + toolExecution.ts:831-832)
        HookBlockingError blockingError = next.blockingError() != null
            ? next.blockingError() : base.blockingError();
        // [EX-HOOK R2] stopReason last-wins · 对齐 CC toolHooks.ts:503-507 逐 hook
        //   preventContinuation 时 yield stopReason + toolExecution.ts:831-832 消费端
        //   {@code stopReason = result.stopReason} 后到覆盖。多 hook 均 preventContinuation
        //   且带 stopReason → 取最后一个；next 无 stopReason → 保留 base（CC 不 yield 即不覆盖）。
        String stopReason = next.stopReason() != null ? next.stopReason() : base.stopReason();
        // [DEL-WF1-TY-02 v4 实施] systemMessages 已就地折叠进 message 通道 (foldSystemMessages 在
        //   HookResult→AHR 转换边界逐条 hook_system_message), 此处无需再 concat 聚合字段 —
        //   message concat 即承载全部 (对齐 CC executeHooks 逐结果 yield → 消费端逐条).
        return new AggregatedHookResult(
            messages,
            blockingError,
            pc,
            stopReason,
            reason,
            source,
            // permissionBehavior: 处理由 caller 完成, 这里用 next (待 caller override)
            next.permissionBehavior() != null ? next.permissionBehavior() : base.permissionBehavior(),
            contexts,
            base.initialUserMessage() != null ? base.initialUserMessage() : next.initialUserMessage(),
            // updatedInput: full-replacement 语义 (CC toolHooks.ts:556-563), hook 返回即替换
            next.updatedInput() != null ? next.updatedInput() : base.updatedInput(),
            base.updatedMCPToolOutput() != null ? base.updatedMCPToolOutput() : next.updatedMCPToolOutput(),
            base.permissionRequestResult() != null ? base.permissionRequestResult() : next.permissionRequestResult(),
            base.watchPaths() != null ? base.watchPaths() : next.watchPaths(),
            base.elicitationResponse() != null ? base.elicitationResponse() : next.elicitationResponse(),
            base.elicitationResultResponse() != null ? base.elicitationResultResponse() : next.elicitationResultResponse(),
            retry
        );
    }

    /** null-safe List concat · 全保留聚合 (IMPL-07 D3-3). */
    private static <T> List<T> concatLists(List<T> a, List<T> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        List<T> out = new ArrayList<>(a.size() + b.size());
        out.addAll(a);
        out.addAll(b);
        return out;
    }

    /**
     * [IMPL-03 D6-1 / OD-05] GenericHook.HookResult → AggregatedHookResult · 配置驱动 hook
     * 结果并入 PreToolUse 聚合链 (对齐 CC 单链: config+registered+session 同链).
     *
     * <p>字段映射 (CC original): {@code permissionBehavior} (hooks.ts:349) → PermissionResult;
     * {@code message}/{@code blockingError}/{@code preventContinuation}/{@code stopReason}/
     * {@code hookPermissionDecisionReason}/{@code updatedInput}/{@code updatedMCPToolOutput}/
     * {@code retry} 直通; {@code additionalContext} (单值) → AHR.additionalContexts (列表).
     * {@code hookSource} 无对应 (last-wins 归因属 IMPL-07 聚合改造范围).
     *
     * <p>deny/ask 消息对齐 CC toolHooks.ts:535-552: {@code hookPermissionDecisionReason} ||
     * 缺省文案 ("Hook PreToolUse:&lt;tool&gt; denied this tool" / "asked for confirmation for this tool",
     * PermissionResult.ts:24-35 getRuleBehaviorDescription). CC 中 blockingError yield 先于
     * permissionBehavior deny yield, 后者覆盖前者 (last-wins) — Java 端若两者并存,
     * permissionBehavior 优先 (本方法构建的 Deny 消息即最终 deny 文案).
     *
     * @param r         配置驱动链聚合结果 (executeEvent 返回); null/proceed → 返回 null (不并入)
     * @param toolName  工具名 (deny/ask 缺省文案 + decisionReason.hookName)
     * @param input     工具原输入 (CC original: processedInput; Allow.updatedInput Java 强制
     *                  非空, hook 未给 updatedInput 时以原输入占位 — 与 resolver 的
     *                  hookUpdatedInput 参数判别分离, 见 HookPermissionResolver H8 契约)
     * @param toolUseId 工具调用 ID (CC original: tool_use_id)
     * @return 16 字段 AHR; 无任何干预字段 → null (调用方跳过合并)
     */
    private static AggregatedHookResult toAggregatedHookResult(GenericHook.HookResult r,
                                                               String toolName, JsonNode input,
                                                               String toolUseId) {
        if (r == null || !hasIntervention(r)) {
            return null;
        }
        PermissionResult pb = toPermissionResult(r, toolName, input, toolUseId);
        // [IMPL-07 OD-14] message 转换边界: instanceof String 截断附件载荷 → AttachmentMessageDto
        //   通道原样透传 (stdout/stderr/exitCode/command/durationMs 不丢); String 包装为
        //   hook_user_message (旧消费端 StreamingToolExecutor 同款包装语义).
        // [DEL-WF1-TY-02 v4 实施] HookResult.systemMessages 就地折叠为 hook_system_message 附件
        //   并入 message 通道 (对齐 CC hooks.ts:2769-2780 逐结果 yield) — AHR 不再承载
        //   systemMessages 聚合字段, 由 injectPreToolUseHookAttachments 逐条 appendAttachment.
        String hookName = "PreToolUse:" + toolName;
        return new AggregatedHookResult(
            AggregatedHookResult.foldSystemMessages(
                AggregatedHookResult.messageChannel(r.message(), hookName, toolUseId, "PreToolUse"),
                r.systemMessages(), hookName, toolUseId, "PreToolUse"),
            r.blockingError(),
            r.preventContinuation(),
            r.stopReason(),
            r.hookPermissionDecisionReason(),
            null,
            pb,
            // [H-WF5a-02 折叠链项2] HookResult.additionalContexts 已是 List<String> → 直接透传 (全保留)
            r.additionalContexts(),
            null,
            r.updatedInput(),
            r.updatedMCPToolOutput(),
            // [Session S07] permissionRequestResult 回填 AHR · 对齐 CC utils/hooks.ts:373
            //   AggregatedHookResult.permissionRequestResult 顶层字段 — 配置驱动 hook 的
            //   PermissionRequest 决策从此进 PreToolUse 聚合链 (AHR 16 字段第 12 位).
            r.permissionRequestResult(),
            null, null, null,
            r.retry()
        );
    }

    /** GenericHook.HookResult 是否含任何干预字段 (无 → 等价 proceed, 不并入聚合). */
    private static boolean hasIntervention(GenericHook.HookResult r) {
        return r.preventContinuation()
            || r.blockingError() != null
            || r.permissionBehavior() != null
            || r.updatedInput() != null
            || r.additionalContexts() != null
            // [H-WF5a-02 折叠链项3] 旧漏 systemMessage 判空 → 仅产 systemMessage 的 hook
            //   在 executePreToolUse 聚合 (toAggregatedHookResult → hasIntervention) 被丢弃,
            //   N 条 systemMessages 附件丢失; 补判空.
            || r.systemMessages() != null
            || r.stopReason() != null
            || r.hookPermissionDecisionReason() != null
            || r.retry() != null
            // [Session S07] PermissionRequest 决策本身即干预 (CC hooks.ts:2882-2886 yield)
            || r.permissionRequestResult() != null
            // [IMPL-07 OD-14] 附件消息也算干预 (旧 instanceof String 漏掉 AttachmentMessageDto)
            || r.message() != null;
    }


    /**
     * [IMPL-03 D6-1] PermissionBehavior (hook 域 4 态枚举) → {@link PermissionResult} ·
     * 对齐 CC HookResult.permissionBehavior union (hooks.ts:349).
     *
     * <p>PASSTHROUGH → null (CC passthrough = 无决策, 工具走正常权限流);
     * ALLOW.updatedInput Java 强制非空 → hook 未提供时以原 input 占位.
     *
     * @param r         配置驱动链 hook 结果
     * @param toolName  工具名 (CC original: tool.name, toolHooks.ts:520-552 缺省文案)
     * @param input     工具原输入 (Allow 占位)
     * @param toolUseId 工具调用 ID
     */
    private static PermissionResult toPermissionResult(GenericHook.HookResult r,
                                                       String toolName, JsonNode input,
                                                       String toolUseId) {
        com.nexusai.application.agent.hook.PermissionBehavior pb = r.permissionBehavior();
        if (pb == null) {
            return null;
        }
        String reason = r.hookPermissionDecisionReason();
        PermissionDecisionReason dr = new PermissionDecisionReason.Hook(
            "PreToolUse:" + toolName, null, reason);
        switch (pb) {
            case ALLOW -> {
                JsonNode allowInput = r.updatedInput() != null ? toJsonNode(r.updatedInput()) : input;
                return new PermissionResult.Allow(allowInput, dr, toolUseId, false, null, List.of());
            }
            case DENY -> {
                String denyMsg = reason != null && !reason.isBlank() ? reason
                    : "Hook PreToolUse:" + toolName + " "
                        + PermissionMessageGenerator.getRuleBehaviorDescription(
                            com.nexusai.application.agent.permission.PermissionBehavior.DENY)
                        + " this tool";
                return new PermissionResult.Deny(denyMsg, dr, toolUseId);
            }
            case ASK -> {
                String askMsg = reason != null && !reason.isBlank() ? reason
                    : "Hook PreToolUse:" + toolName + " "
                        + PermissionMessageGenerator.getRuleBehaviorDescription(
                            com.nexusai.application.agent.permission.PermissionBehavior.ASK)
                        + " this tool";
                // [TH-01 OPD-WF3-TH-01] config ask updatedInput 透传 · 对齐 CC
                //   toolHooks.ts:534 (ask hookPermissionResult 携带 updatedInput) +
                //   toolHooks.ts:417-421 (askInput = ask.updatedInput ?? input)。
                //   旧实现 9 参构造第 5 位 (updatedInput) 传 null → Ask.updatedInput() 恒 null,
                //   coordinator/swarm/投机 classifier 读 AskView.updatedInput() 丢失 hook
                //   updatedInput (X-PROBE EV-XP-W3-004). hook 未给 updatedInput → null
                //   (CC updatedInput? optional, askInput 回落 input).
                JsonNode askUpdatedInput = r.updatedInput() != null ? toJsonNode(r.updatedInput()) : null;
                return new PermissionResult.Ask(askMsg, dr, List.of(), null, askUpdatedInput, null,
                    false, null, List.of());
            }
            case PASSTHROUGH -> {
                return null; // CC passthrough = 无决策 (不进入 deny/ask/allow 桶)
            }
            default -> {
                return null;
            }
        }
    }

    /** Map → JsonNode (updatedInput 全替换语义的 JsonNode 载体). */
    private static JsonNode toJsonNode(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(map);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [H4] programmatic hook 并行执行辅助 · 对齐 CC executeHooks all(hookPromises)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [H4] 提交单个 PreToolUse programmatic hook 并行执行 · 对齐 CC executeHooks.
     *
     * <p>WHY (规则九): CC executePreToolHooks (hooks.ts:3394-3444) = yield* executeHooks,
     * 全部 hook 并行 (all(hookPromises), hooks.ts:2744) + 每 hook 独立超时
     * (createCombinedAbortSignal timeoutMs, hooks.ts:2148). Java 端每 hook 一个
     * supplyAsync + {@code orTimeout(hookTimeoutMs)} 独立超时; 异常在 future 内隔离
     * (单个 hook 失败不阻塞其他, 对齐 CC :2698-2729), AbortException 透传.
     *
     * @return future: 成功 → hook 结果 (可能 null); 超时/异常 → null (已 warn+telemetry);
     *         AbortException → 异常完成 (透传)
     */
    private CompletableFuture<AggregatedHookResult> submitPreToolUseHook(
            String hookName, PreToolUseHook hook, String toolName, JsonNode input, ToolUseContext ctx,
            String toolUseId,
            String toolUseSummary) {
        long preHookStartNs = System.nanoTime();
        return CompletableFuture.supplyAsync(
                withSessionProjectRoot(() -> hook.onPreToolUse(toolName, input, ctx,
                    toolUseId, toolUseSummary)), HOOK_EXECUTOR)
            .orTimeout(hookTimeoutMs, TimeUnit.MILLISECONDS)
            .handle((result, ex) -> {
                if (ex == null) {
                    if (result == null) {
                        log.warn("HOOK PreToolUse '{}' returned null for tool={}, treating as proceed",
                            hookName, toolName);
                    }
                    return result;
                }
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                    ? ex.getCause() : ex;
                if (cause instanceof AbortException ae) {
                    log.warn("HOOK PreToolUse '{}' abort: {}", hookName, ae.getMessage());
                    throw ae; // 透传 (用户中止意图不可吞, 对齐 CC hooks.ts:2045-2051)
                }
                long preHookDurationMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - preHookStartNs);
                if (cause instanceof TimeoutException te) {
                    log.warn("HOOK PreToolUse '{}' timed out after {}ms for tool={}",
                        hookName, hookTimeoutMs, toolName);
                    emitHookErrorTelemetry(toolName, "tengu_pre_tool_hook_error",
                        preHookDurationMs, te);
                    // [B GAP-HOOK-02] CC: hook 超时 → non-blocking error attachment (hooks.ts:2698-2730)
                    firePreToolUseHookErrorSink(hookName, toolUseId, toolName, te);
                } else {
                    log.warn("HOOK PreToolUse '{}' threw for tool={}: {}",
                        hookName, toolName, cause != null ? cause.getMessage() : ex.getMessage());
                    emitHookErrorTelemetry(toolName, "tengu_pre_tool_hook_error",
                        preHookDurationMs, cause != null ? cause : ex);
                    // [B GAP-HOOK-02] CC: hook 抛错 → hook_error_during_execution attachment, 工具继续
                    firePreToolUseHookErrorSink(hookName, toolUseId, toolName,
                        cause != null ? cause : ex);
                }
                return null; // 超时/异常 → 跳过该 hook (对齐原 continue)
            });
    }

    /**
     * [H4] 提交单个 PostToolUse programmatic hook 并行执行 · 对齐 CC executeHooks.
     *
     * <p>WHY: CC executePostToolUseHooks (hooks.ts:3447-3487) = yield* executeHooks 并行.
     * 超时/异常在 future 内隔离返回 null, AbortException 透传.
     *
     * @return future: 成功 → hook 结果; 超时/异常 → null; AbortException → 异常完成 (透传)
     */
    private CompletableFuture<GenericHook.HookResult> submitPostToolUseHook(
            String hookName, PostToolUseHook hook, String toolName, JsonNode input,
            ToolResult<?> result, ToolUseContext ctx, boolean stopHookActive) {
        long postHookStartNs = System.nanoTime();
        return CompletableFuture.supplyAsync(
                withSessionProjectRoot(() -> hook.onPostToolUse(toolName, input, result, ctx, stopHookActive)), HOOK_EXECUTOR)
            .orTimeout(hookTimeoutMs, TimeUnit.MILLISECONDS)
            .handle((hookResult, ex) -> {
                if (ex == null) {
                    return hookResult;
                }
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                    ? ex.getCause() : ex;
                if (cause instanceof AbortException ae) {
                    log.warn("HOOK PostToolUse '{}' abort: {}", hookName, ae.getMessage());
                    throw ae;
                }
                long postHookDurationMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - postHookStartNs);
                if (cause instanceof TimeoutException te) {
                    log.warn("HOOK PostToolUse '{}' timed out after {}ms for tool={}",
                        hookName, hookTimeoutMs, toolName);
                    emitHookErrorTelemetry(toolName, "tengu_post_tool_hook_error",
                        postHookDurationMs, te);
                } else {
                    log.warn("HOOK PostToolUse '{}' threw for tool={}: {}",
                        hookName, toolName, cause != null ? cause.getMessage() : ex.getMessage());
                    emitHookErrorTelemetry(toolName, "tengu_post_tool_hook_error",
                        postHookDurationMs, cause != null ? cause : ex);
                }
                return null; // 超时/异常 → 跳过该 hook (对齐原 continue)
            });
    }

    /**
     * [H4] 提交单个 PostToolUseFailure programmatic hook 并行执行 · 对齐 CC executeHooks.
     *
     * <p>WHY: CC executePostToolUseFailureHooks (hooks.ts:3492-3527) = yield* executeHooks 并行.
     * 超时/异常在 future 内隔离返回 null, AbortException 透传.
     *
     * @return future: 成功 → hook 结果; 超时/异常 → null; AbortException → 异常完成 (透传)
     */
    private CompletableFuture<GenericHook.HookResult> submitPostToolUseFailureHook(
            String hookName, PostToolUseHook hook, String toolName, JsonNode input,
            ToolResult<?> errorResult, ToolUseContext ctx, boolean stopHookActive) {
        long failHookStartNs = System.nanoTime();
        return CompletableFuture.supplyAsync(
                withSessionProjectRoot(() -> hook.onPostToolUseFailure(toolName, input, errorResult, ctx, stopHookActive)),
                HOOK_EXECUTOR)
            .orTimeout(hookTimeoutMs, TimeUnit.MILLISECONDS)
            .handle((hookResult, ex) -> {
                if (ex == null) {
                    return hookResult;
                }
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                    ? ex.getCause() : ex;
                if (cause instanceof AbortException ae) {
                    log.warn("HOOK PostToolUseFailure '{}' abort: {}", hookName, ae.getMessage());
                    throw ae;
                }
                long failHookDurationMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - failHookStartNs);
                if (cause instanceof TimeoutException te) {
                    log.warn("HOOK PostToolUseFailure '{}' timed out after {}ms for tool={}",
                        hookName, hookTimeoutMs, toolName);
                    emitHookErrorTelemetry(toolName, "tengu_post_tool_failure_hook_error",
                        failHookDurationMs, te);
                } else {
                    log.warn("HOOK PostToolUseFailure '{}' threw for tool={}: {}",
                        hookName, toolName, cause != null ? cause.getMessage() : ex.getMessage());
                    emitHookErrorTelemetry(toolName, "tengu_post_tool_failure_hook_error",
                        failHookDurationMs, cause != null ? cause : ex);
                }
                return null; // 超时/异常 → 跳过该 hook (对齐原 continue)
            });
    }

    /**
     * [H4] 提交单个 GenericHook programmatic hook 并行执行 · 对齐 CC executeHooks.
     *
     * <p>WHY: CC executeEvent 全部 generic hook 并行 (all(hookPromises), hooks.ts:2744).
     * 超时/异常在 future 内隔离返回 null, AbortException 透传.
     *
     * @return future: 成功 → hook 结果 (可能 null); 超时/异常 → null (已 warn+telemetry);
     *         AbortException → 异常完成 (透传)
     */
    private CompletableFuture<GenericHook.HookResult> submitGenericHook(
            String hookName, GenericHook hook, HookEvent event) {
        long genericHookStartNs = System.nanoTime();
        return CompletableFuture.supplyAsync(
                withSessionProjectRoot(() -> hook.onEvent(event)), HOOK_EXECUTOR)
            .orTimeout(hookTimeoutMs, TimeUnit.MILLISECONDS)
            .handle((result, ex) -> {
                if (ex == null) {
                    return result;
                }
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                    ? ex.getCause() : ex;
                if (cause instanceof AbortException ae) {
                    log.warn("GenericHook '{}' abort: {}", hookName, ae.getMessage());
                    throw ae;
                }
                long genericHookDurationMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - genericHookStartNs);
                if (cause instanceof TimeoutException te) {
                    log.warn("GenericHook '{}' timed out after {}ms", hookName, hookTimeoutMs);
                    emitHookErrorTelemetryForGeneric(event, hookName, genericHookDurationMs, te);
                } else {
                    log.warn("Hook {} failed: {}", hookName,
                        cause != null ? cause.getMessage() : ex.getMessage());
                    emitHookErrorTelemetryForGeneric(event, hookName, genericHookDurationMs,
                        cause != null ? cause : ex);
                }
                return null; // 超时/异常 → 跳过该 hook
            });
    }

    /**
     * 执行所有 PostToolUse hooks (全部执行, 非短路) · 对齐 CC toolExecution.ts:1483-1531 runPostToolUseHooks.
     *
     * <p><b>[P0-3 强化]</b> 字段集扩展为 13 字段 {@link GenericHook.HookResult} (16 →
     * 按 PostToolUse 实际可达筛选): blockingError / systemMessage / updatedMCPToolOutput /
     * additionalContext (List) / hookPermissionDecisionReason / hookSource / hookPermissionResult
     * (升级为 Object 承载 permissionRequestResult) 等.
     *
     * <p>对齐 CC PostToolUseHooksResult 2 类:
     * <ul>
     *   <li>message.update → producing additional message in resultingMessages</li>
     *   <li>{@code { updatedMCPToolOutput: Output }} → only for MCP tools, replaces tool output</li>
     * </ul>
     *
     * @return 13 字段聚合 {@link GenericHook.HookResult}; 无任何 hook 干预 → proceed().
     */
    public GenericHook.HookResult executePostToolUse(String toolName, JsonNode input, ToolResult<?> result, ToolUseContext ctx) {
        return executePostToolUse(toolName, input, result, ctx, false);
    }
    public GenericHook.HookResult executePostToolUse(String toolName, JsonNode input, ToolResult<?> result, ToolUseContext ctx, boolean stopHookActive) {
        // [IMP-C2] toolUseId/isError 由执行器推导透传（ToolResult 4 字段契约；对齐 CC executePostToolHooks 无 errorCategory）
        return executePostToolUse(toolName, input, result, ctx, stopHookActive,
            ctx != null ? ctx.toolUseId() : null,
            com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(result != null ? result.data() : null));
    }
    public GenericHook.HookResult executePostToolUse(String toolName, JsonNode input, ToolResult<?> result, ToolUseContext ctx, boolean stopHookActive,
                                                    String toolUseId, boolean isError) {
        // [IMPL-01 D1-5 / INV-11 / OD-07] 政策闸门: programmatic PostToolUse 链不豁免
        //   (对齐 CC runPostToolUseHooks = yield* executeHooks, 入口短路 hooks.ts:1978-1980).
        if (shouldDisableAllHooksIncludingManaged()) {
            if (log.isDebugEnabled()) {
                log.debug("executePostToolUse: policySettings.disableAllHooks=true, 跳过 programmatic hook (工具 {})",
                    toolName);
            }
            return GenericHook.HookResult.proceed();
        }
        // [IMPL-04 D9-1 / INV-9 / OD-13] trust 门控: programmatic PostToolUse 链不豁免 ·
        //   对齐 CC runPostToolUseHooks = yield* executeHooks (入口 trust 门控 hooks.ts:1994).
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("executePostToolUse: workspace trust 未接受, 跳过 programmatic hook (工具 {})",
                    toolName);
            }
            return GenericHook.HookResult.proceed();
        }
        List<Map.Entry<String, PostToolUseHook>> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(postToolUseHooks.entrySet());
        }
        // [IMP-HOOKS-S6 CCJ-T6-06/11] 批级事件 + 遥测: 事件先构造 (匹配复用),
        //   tengu_run_hook 批首 + 每匹配 hook started/progress 预执行.
        JsonNode postResultJson = result != null ? resultToJsonNode(result, toolUseId, isError) : null;
        HookEvent toolPostEvent = HookEvent.toolPost(
            toolName, input, postResultJson,
            // [EX-HOOK R7 修正] 载荷 session_id 恒主会话（CC 双轨语义，见 executePreToolUse 注释）
            ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null,
            ctx != null && ctx.agentId() != null ? ctx.agentId().toString() : null,
            toolUseId);
        List<MatchedHook> matched = getMatchingHooks(toolPostEvent);
        long toolChainStartMs = System.currentTimeMillis();
        ToolChainOutcomes outcomes = new ToolChainOutcomes();
        long userSnapshotCount = userProgrammaticCount(snapshot);
        // [JS-05 GAP-9] beta-tracing OTEL hook_definitions 载荷 + hook span holder.
        String betaDefsJson = isBetaTracingEnabled()
            ? getHookDefinitionsForTelemetry(matched, snapshot) : "[]";
        HookSpan betaSpan = HookSpan.disabled();
        if (!matched.isEmpty() || userSnapshotCount > 0) {
            emitToolHookRunTelemetry("PostToolUse", matched.size() + (int) userSnapshotCount,
                toolHookTypeCounts(matched, (int) userSnapshotCount),
                pluginHookCounts(matched));
            // [JS-05 GAP-9] beta-tracing OTEL hook_execution_start (CC hooks.ts:2076-2084).
            betaSpan = emitHookExecutionStartTelemetry(
                "PostToolUse", "PostToolUse:" + toolName,
                matched.size() + snapshot.size(), betaDefsJson);
            for (MatchedHook mh : matched) {
                emitToolHookStartedProgress(hookNameFor(mh), "PostToolUse", mh.hook(), null);
            }
            for (Map.Entry<String, PostToolUseHook> entry : snapshot) {
                emitToolHookStartedProgress(entry.getKey(), "PostToolUse", null, entry.getKey());
            }
        }
        // [H4] PostToolUse 聚合 (对齐 CC GenericHook.HookResult 12 字段, 删除 hookPermissionResult/hookSource)
        boolean preventContinuation = false;
        String firstReason = null;
        HookBlockingError firstBlockingError = null;
        // [IMP-DA-01 TY-01] allBlockingErrors 聚合字段已删除 (对齐 CC HookResult 无此字段).
        //   CC executeHooks 逐结果 yield blockingError (hooks.ts:2759-2763) →
        //   runPostToolUseHooks 逐条产独立 hook_blocking_error 附件 (toolHooks.ts:105-115).
        //   折叠层把每 blocking result 的 hook_blocking_error 附件并入 messages (已有 message
        //   复用, 无 message 的 exit-2/programmatic 合成), 由 injectPostToolUseHookAttachments 逐条注入.
        // [H-WF5a-02 折叠链5项 + 5-W3-4] message/systemMessage/additionalContext 折叠改 List 全保留
        //   (CC executeHooks 逐结果 yield → toolHooks.ts 消费端全 push/逐条附件; 旧 first-* 丢 2..N).
        java.util.List<Object> messages = new java.util.ArrayList<>();
        java.util.List<String> systemMessages = new java.util.ArrayList<>();
        // [H-WF5a-02 折叠链项5] updatedMCPToolOutput last-wins (CC toolHooks.ts:145-151, 后到覆盖)
        Object updatedOutput = null;
        String firstHookPermissionDecisionReason = null;
        java.util.List<String> additionalContexts = new java.util.ArrayList<>();
        if (!snapshot.isEmpty()) {
            // [H4] programmatic hook 并行执行 · 对齐 CC executeHooks all(hookPromises) (hooks.ts:2744).
            //   每 hook 一个 future (独立超时 + 异常隔离), allOf 等待后按 snapshot 顺序聚合,
            //   逐字段取首个非空值语义不变 (HookResult 层聚合).
            List<CompletableFuture<GenericHook.HookResult>> futures = new ArrayList<>(snapshot.size());
            for (Map.Entry<String, PostToolUseHook> entry : snapshot) {
                futures.add(submitPostToolUseHook(entry.getKey(), entry.getValue(),
                    toolName, input, result, ctx, stopHookActive));
            }
            // [H4] 并行等待全部 hook 完成 · 对齐 CC all(hookPromises) (hooks.ts:2744).
            //   单 hook 超时/异常已在 future 内隔离为 null 正常完成; 仅 AbortException 会异常完成
            //   (用户中止意图不可吞, 对齐 CC hooks.ts:2045-2051), 此处解包后透传给 caller.
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof AbortException ae) {
                    // [IMP-HOOKS-S6 CCJ-T6-11] abort 中断批 → 全部未完成 hook 按 cancelled 计数
                    outcomes.cancelled += snapshot.size();
                    emitToolHookFinishedTelemetry("PostToolUse", outcomes,
                        System.currentTimeMillis() - toolChainStartMs);
                    // [JS-05 GAP-9] beta-tracing OTEL hook_execution_complete (abort 路径).
                    //   [R-3] 与 start 同条件门控: all-internal 批次对齐 CC fast-path start/complete 均不发射.
                    if (!matched.isEmpty() || userSnapshotCount > 0) {
                        emitHookExecutionCompleteTelemetry("PostToolUse", "PostToolUse:" + toolName,
                            matched.size() + snapshot.size(), outcomes, betaDefsJson, betaSpan);
                    }
                    throw ae;
                }
                log.warn("HOOK 并行等待异常(非 abort): {}", cause != null ? cause.toString() : ce.toString());
            }
            for (int i = 0; i < snapshot.size(); i++) {
                String hookName = snapshot.get(i).getKey();
                GenericHook.HookResult hookResult = futures.get(i).join(); // 并行已完成, join 不阻塞
                if (hookResult == null) {
                    // [IMP-HOOKS-S6 CCJ-T6-11] 超时/异常 → non_blocking_error 计数 (CC :2735-2738)
                    outcomes.nonBlockingError++;
                    continue;
                }
                // [IMP-HOOKS-S6 CCJ-T6-11] programmatic outcome 计数 (HookResult.outcome 直读)
                outcomes.add(hookResult.outcome());
                if (hookResult.preventContinuation() && !preventContinuation) {
                    preventContinuation = true;
                    firstReason = hookResult.stopReason();
                }
                if (firstBlockingError == null && hookResult.blockingError() != null) {
                    firstBlockingError = hookResult.blockingError();
                }
                // [IMP-DA-01 TY-01] blockingError 逐 result → 1 hook_blocking_error 附件:
                //   CC toolHooks.ts:105-115 逐 blocking 产独立 attachment. allBlockingErrors 字段删除后,
                //   该附件并入 messages 列表 — 已有 hook_blocking_error message (processHookJSONOutput
                //   生成, 含 JSON block) 由下方通用 message 收集注入; 无 message 的 exit-2/programmatic
                //   在此合成. hasBlockingAttachment 区分 JSON block(有 message→跳过合成, 下方通用收集
                //   在 !preventContinuation 下注入) 与 exit-2(无 message→合成, 保留 CC blocking 反馈);
                //   JSON block+continue:false 不产 (message 被下方 !preventContinuation 门控丢弃,
                //   对齐 CC toolHooks.ts:118-130 生成器 abandon).
                if (hookResult.blockingError() != null) {
                    HookBlockingError be = hookResult.blockingError();
                    boolean hasBlockingAttachment = hookResult.message() instanceof AttachmentMessageDto att
                        && "hook_blocking_error".equals(att.type());
                    if (!hasBlockingAttachment) {
                        messages.add(AttachmentMessageDto.hookBlockingError(
                            "PostToolUse:" + toolName,
                            toolUseId,
                            "PostToolUse", be.blockingError(), be.command()));
                    }
                }
                // [MERG-01 R1 修复] CC 成功链: 阻断 result 自身后续 yield 的 message/systemMessage/
                //   additionalContext 亦不消费 (toolHooks.ts:118-130 preventContinuation yield 后 return,
                //   生成器 abandon, 后续 yield 永不消费) → preventContinuation=true 时不收集本 result
                //   这三类载荷. updatedOutput last-wins 与 firstBlockingError 收集语义保留.
                if (!preventContinuation && hookResult.systemMessages() != null) {
                    systemMessages.addAll(hookResult.systemMessages());
                }
                if (!preventContinuation && hookResult.message() != null) {
                    messages.add(hookResult.message());
                }
                if (hookResult.updatedMCPToolOutput() != null) {
                    updatedOutput = hookResult.updatedMCPToolOutput(); // last-wins (CC toolHooks.ts:147-148)
                }
                if (firstHookPermissionDecisionReason == null
                    && hookResult.hookPermissionDecisionReason() != null) {
                    firstHookPermissionDecisionReason = hookResult.hookPermissionDecisionReason();
                }
                if (!preventContinuation && hookResult.additionalContexts() != null) {
                    additionalContexts.addAll(hookResult.additionalContexts());
                }
                if (log.isDebugEnabled()) {
                    log.debug("HOOK PostToolUse '{}' result: blockingError={} systemMessages={} updatedOutput={}",
                        hookName, hookResult.blockingError() != null,
                        hookResult.systemMessages() != null, hookResult.updatedMCPToolOutput() != null);
                }
                // [H-WF5a-02 折叠链项4] preventContinuation 早停 (CC toolHooks.ts:129 return) —
                //   本 result 载荷已收集完, 后续 result 不再消费 (CC 首个阻断后 return 不 yield 后续).
                if (preventContinuation) {
                    break;
                }
                // [Session H5] CC toolHooks.ts:68-88 result.message.attachment.type==='hook_cancelled' ->
                //   logEvent('tengu_post_tool_hooks_cancelled'). Java 同步聚合: 检测 abort 后发 cancelled telemetry + break.
                if (ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled()) {
                    emitHookCancelledTelemetry(toolName, "tengu_post_tool_hooks_cancelled");
                    // [IMP-HOOKS-S6 CCJ-T6-11] 剩余未 drain hook 按 cancelled 计数 (近似, 同 executePreToolUse)
                    outcomes.cancelled += snapshot.size() - i - 1;
                    break;
                }
            }
        }
        // [IMPL-03 D6-1 / OD-05] 单链: 配置驱动 PostToolUse hook 结果并入聚合
        //   (旧 fireGenericEvent 双总线桥接丢弃返回值 → 配置 hook 的 message/blockingError/
        //   additionalContext/updatedMCPToolOutput 不生效). X5: toolPost 6 参重载传真实
        //   toolUseId (result.toolUseId(), CC coreSchemas.ts:444 tool_use_id 必传).
        GenericHook.HookResult configuredResult = executeEvent(toolPostEvent, null, ctx);
        // 配置驱动段 outcome 计数 (折叠表达, 见 emitToolHookFinishedTelemetry 注释)
        if (!matched.isEmpty()) {
            if (configuredResult == null || !hasIntervention(configuredResult)) {
                outcomes.success += matched.size();
            } else {
                int n = matched.size();
                switch (configuredResult.outcome()) {
                    case BLOCKING -> outcomes.blocking += n;
                    case NON_BLOCKING_ERROR -> outcomes.nonBlockingError += n;
                    case CANCELLED -> outcomes.cancelled += n;
                    case SUCCESS -> outcomes.success += n;
                }
            }
        }
        // [IMP-HOOKS-S6 CCJ-T6-11] 批尾遥测 (CC hooks.ts:2935-2944)
        emitToolHookFinishedTelemetry("PostToolUse", outcomes,
            System.currentTimeMillis() - toolChainStartMs);
        // [JS-05 GAP-9] beta-tracing OTEL hook_execution_complete (CC hooks.ts:2946-2963).
        //   [R-3] 与 start 同条件门控: all-internal 批次对齐 CC fast-path start/complete 均不发射.
        if (!matched.isEmpty() || userSnapshotCount > 0) {
            emitHookExecutionCompleteTelemetry("PostToolUse", "PostToolUse:" + toolName,
                matched.size() + snapshot.size(), outcomes, betaDefsJson, betaSpan);
        }
        // [H-WF5a-02 折叠链项4] programmatic 段已 preventContinuation → configured 结果不再消费
        //   (CC toolHooks.ts:129 return 后不消费后续 result; Java 批序 programmatic 先 + configured 后)
        if (configuredResult != null && hasIntervention(configuredResult) && !preventContinuation) {
            if (configuredResult.preventContinuation() && !preventContinuation) {
                preventContinuation = true;
                firstReason = configuredResult.stopReason();
            }
            if (firstBlockingError == null && configuredResult.blockingError() != null) {
                firstBlockingError = configuredResult.blockingError();
            }
            // [IMP-DA-01 TY-01] 同 programmatic 段: configured blocking 附件的唯一载体是 messages.
            //   已有 hook_blocking_error message (processHookJSONOutput 生成) 由下方通用收集注入;
            //   无 message (exit-2) 在此合成. 语义同 programmatic 段 (hasBlockingAttachment 区分,
            //   JSON block+continue:false 的 message 被下方 !preventContinuation 门控丢弃 → 不产).
            if (configuredResult.blockingError() != null) {
                HookBlockingError cbe = configuredResult.blockingError();
                boolean cHasBlockingAttachment = configuredResult.message() instanceof AttachmentMessageDto catt
                    && "hook_blocking_error".equals(catt.type());
                if (!cHasBlockingAttachment) {
                    messages.add(AttachmentMessageDto.hookBlockingError(
                        "PostToolUse:" + toolName,
                        toolUseId,
                        "PostToolUse", cbe.blockingError(), cbe.command()));
                }
            }
            // [MERG-01 R1 修复] 同 programmatic 段: configured 结果自身阻断时其三载荷亦不收集
            //   (CC 成功链 toolHooks.ts:129 return 语义, 阻断 result 自身载荷不消费)
            if (!preventContinuation && configuredResult.systemMessages() != null) {
                systemMessages.addAll(configuredResult.systemMessages());
            }
            if (!preventContinuation && configuredResult.message() != null) {
                messages.add(configuredResult.message());
            }
            if (configuredResult.updatedMCPToolOutput() != null) {
                updatedOutput = configuredResult.updatedMCPToolOutput(); // last-wins
            }
            if (firstHookPermissionDecisionReason == null
                && configuredResult.hookPermissionDecisionReason() != null) {
                firstHookPermissionDecisionReason = configuredResult.hookPermissionDecisionReason();
            }
            if (!preventContinuation && configuredResult.additionalContexts() != null) {
                additionalContexts.addAll(configuredResult.additionalContexts());
            }
            if (log.isDebugEnabled()) {
                log.debug("HOOK PostToolUse 配置驱动结果并入 (工具 {}): preventContinuation={} blockingError={}",
                    toolName, configuredResult.preventContinuation(),
                    configuredResult.blockingError() != null);
            }
        }

        // 是否完全无干预 → proceed()
        boolean allNull = !preventContinuation
            && firstBlockingError == null && systemMessages.isEmpty()
            && messages.isEmpty() && updatedOutput == null
            && firstHookPermissionDecisionReason == null
            && additionalContexts.isEmpty();
        if (allNull) {
            return GenericHook.HookResult.proceed();
        }
        return new GenericHook.HookResult(preventContinuation,
            firstBlockingError,
            systemMessages.isEmpty() ? null : systemMessages,
            additionalContexts.isEmpty() ? null : additionalContexts,
            messages.isEmpty() ? null : messages,
            null, updatedOutput,
            null, firstHookPermissionDecisionReason,
            // [Session I P3-1 + M.2.2] 3 字段扩展 · CC HookOutcome + stopReason + permissionBehavior
            // [IMP-DA-01 TY-01] allBlockingErrors 参数已删除 — blocking 附件经 messages 逐条承载
            GenericHook.HookOutcome.SUCCESS, firstReason, null, null, null, null, null, null, null);
    }

    /**
     * [Session H5] 执行所有 PostToolUseFailure hooks · 对齐 CC toolHooks.ts:193-319 runPostToolUseFailureHooks +
     * utils/hooks.ts:3492-3527 executePostToolUseFailureHooks (8 参).
     *
     * <p>WHY: CC 工具执行失败 (含 AbortError) 路径独立触发 PostToolUseFailure hooks, 独立于成功路径的
     * PostToolUse hooks. Java 端 {@link PostToolUseHook#onPostToolUseFailure} default 存在但之前无调度入口
     * (失败路径走 executeEvent 通用总线, 不调 onPostToolUseFailure). 本方法补专用调度入口, 镜像
     * {@link #executePostToolUse} 结构, 调 onPostToolUseFailure.
     *
     * <p>对齐 CC runPostToolUseFailureHooks 4 类 attachment (无 hook_stopped_continuation, 与 PostToolUse 不同):
     * hook_cancelled / hook_blocking_error / hook_additional_context / hook_error_during_execution.
     * preventContinuation 字段透传 (Java hook 契约), 但消费侧不产出 hook_stopped_continuation attachment.
     *
     * <p>abort 检测: CC toolHooks.ts:224 -> tengu_post_tool_failure_hooks_cancelled.
     * error 检测: CC toolHooks.ts:283 -> tengu_post_tool_failure_hook_error.
     *
     * @param toolName       工具名
     * @param input          工具输入
     * @param errorResult    错误 ToolResult (isError=true)
     * @param ctx            工具调用上下文
     * @param stopHookActive 是否在另一 PostToolUseFailure hook 内被调用 (CC 嵌套守卫)
     * @param isInterrupt    [P-25] 是否用户中止 (CC isInterrupt = error instanceof AbortError,
     *                       toolExecution.ts:1694)；由调用方显式传入（旧内部
     *                       "abort".equals(errorCategory) 字符串匹配已删）
     * @param toolUseId      [IMP-C2] 工具调用 ID（ToolResult 4 字段契约透传；5/6 参便捷重载从
     *                       ctx.toolUseId() 推导）
     * @param isError        [IMP-C2] 是否错误结果（ToolResult 4 字段契约透传；5/6 参便捷重载由
     *                       LlmAgentLoop.isToolErrorData(data) 推导）
     * @return 聚合 {@link GenericHook.HookResult}; 无干预 -> proceed()
     */
    public GenericHook.HookResult executePostToolUseFailure(String toolName, JsonNode input,
                                                            ToolResult<?> errorResult, ToolUseContext ctx,
                                                            boolean stopHookActive) {
        // [IMP-C2] toolUseId/isError 由执行器推导透传（ToolResult 4 字段契约；ToolResult 不存
        //   toolUseId —— IMP-C2 已删字段, 由 ctx.toolUseId() 推导, 对齐 tool-v3 5 参便捷重载）
        return executePostToolUseFailure(toolName, input, errorResult, ctx, stopHookActive, false,
            ctx != null ? ctx.toolUseId() : null,
            com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(errorResult != null ? errorResult.data() : null));
    }
    public GenericHook.HookResult executePostToolUseFailure(String toolName, JsonNode input,
                                                            ToolResult<?> errorResult, ToolUseContext ctx,
                                                            boolean stopHookActive, boolean isInterrupt) {
        // [P-25] isInterrupt 显式入参（CC isInterrupt = error instanceof AbortError, toolExecution.ts:1694）
        return executePostToolUseFailure(toolName, input, errorResult, ctx, stopHookActive, isInterrupt,
            ctx != null ? ctx.toolUseId() : null,
            com.nexusai.application.agent.LlmAgentLoop.isToolErrorData(errorResult != null ? errorResult.data() : null));
    }
    public GenericHook.HookResult executePostToolUseFailure(String toolName, JsonNode input,
                                                            ToolResult<?> errorResult, ToolUseContext ctx,
                                                            boolean stopHookActive, boolean isInterrupt,
                                                            String toolUseId, boolean isError) {
        // [IMPL-01 D1-5 / INV-11 / OD-07] 政策闸门: programmatic PostToolUseFailure 链不豁免
        //   (对齐 CC runPostToolUseFailureHooks = yield* executeHooks, 入口短路 hooks.ts:1978-1980).
        if (shouldDisableAllHooksIncludingManaged()) {
            if (log.isDebugEnabled()) {
                log.debug("executePostToolUseFailure: policySettings.disableAllHooks=true, 跳过 programmatic hook (工具 {})",
                    toolName);
            }
            return GenericHook.HookResult.proceed();
        }
        // [IMPL-04 D9-1 / INV-9 / OD-13] trust 门控: programmatic PostToolUseFailure 链不豁免 ·
        //   对齐 CC runPostToolUseFailureHooks = yield* executeHooks (入口 trust 门控 hooks.ts:1994).
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("executePostToolUseFailure: workspace trust 未接受, 跳过 programmatic hook (工具 {})",
                    toolName);
            }
            return GenericHook.HookResult.proceed();
        }
        List<Map.Entry<String, PostToolUseHook>> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(postToolUseHooks.entrySet());
        }
        // [IMP-HOOKS-S6 CCJ-T6-06/11] 批级事件 + 遥测: 事件先构造 (匹配复用),
        //   tengu_run_hook 批首 + 每匹配 hook started/progress 预执行.
        JsonNode failureResultJson = errorResult != null ? resultToJsonNode(errorResult, toolUseId, false) : null;
        String errorText = errorResult != null && errorResult.data() != null
            ? String.valueOf(errorResult.data()) : null;
        // [P-25] isInterrupt 显式入参直用 (旧 "abort".equals(errorCategory) 字符串匹配已删 —
        //   errorCategory 已改 CC 细粒度类名, 不再含 "abort" 桶)
        HookEvent toolPostFailureEvent = HookEvent.toolPostFailure(
            toolName, input, failureResultJson, errorText, isInterrupt,
            toolUseId,
            // [EX-HOOK R7 修正] 载荷 session_id 恒主会话（CC 双轨语义，见 executePreToolUse 注释）
            ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null,
            ctx != null && ctx.agentId() != null ? ctx.agentId().toString() : null);
        List<MatchedHook> matched = getMatchingHooks(toolPostFailureEvent);
        long toolChainStartMs = System.currentTimeMillis();
        ToolChainOutcomes outcomes = new ToolChainOutcomes();
        long userSnapshotCount = userProgrammaticCount(snapshot);
        // [JS-05 GAP-9] beta-tracing OTEL hook_definitions 载荷 + hook span holder.
        String betaDefsJson = isBetaTracingEnabled()
            ? getHookDefinitionsForTelemetry(matched, snapshot) : "[]";
        HookSpan betaSpan = HookSpan.disabled();
        if (!matched.isEmpty() || userSnapshotCount > 0) {
            emitToolHookRunTelemetry("PostToolUseFailure", matched.size() + (int) userSnapshotCount,
                toolHookTypeCounts(matched, (int) userSnapshotCount),
                pluginHookCounts(matched));
            // [JS-05 GAP-9] beta-tracing OTEL hook_execution_start (CC hooks.ts:2076-2084).
            betaSpan = emitHookExecutionStartTelemetry(
                "PostToolUseFailure", "PostToolUseFailure:" + toolName,
                matched.size() + snapshot.size(), betaDefsJson);
            for (MatchedHook mh : matched) {
                emitToolHookStartedProgress(hookNameFor(mh), "PostToolUseFailure", mh.hook(), null);
            }
            for (Map.Entry<String, PostToolUseHook> entry : snapshot) {
                emitToolHookStartedProgress(entry.getKey(), "PostToolUseFailure", null, entry.getKey());
            }
        }
        boolean preventContinuation = false;
        String firstReason = null;
        HookBlockingError firstBlockingError = null;
        // [IMP-DA-01 TY-01] allBlockingErrors 聚合字段已删除 (同 executePostToolUse) —
        //   CC runPostToolUseFailureHooks toolHooks.ts:257-267 逐条 hook_blocking_error:
        //   折叠层把每 blocking result 的 hook_blocking_error 附件并入 messages (复用/合成),
        //   由 injectPostToolUseHookAttachments 逐条注入.
        // [H-WF5a-02 折叠链5项 + 5-W3-4] message/systemMessage/additionalContext 折叠改 List 全保留
        java.util.List<Object> messages = new java.util.ArrayList<>();
        java.util.List<String> systemMessages = new java.util.ArrayList<>();
        String firstHookPermissionDecisionReason = null;
        java.util.List<String> additionalContexts = new java.util.ArrayList<>();
        if (!snapshot.isEmpty()) {
            // [H4] programmatic hook 并行执行 · 对齐 CC executeHooks all(hookPromises) (hooks.ts:2744).
            //   每 hook 一个 future (独立超时 + 异常隔离), allOf 等待后按 snapshot 顺序聚合,
            //   逐字段取首个非空值语义不变 (HookResult 层聚合).
            List<CompletableFuture<GenericHook.HookResult>> futures = new ArrayList<>(snapshot.size());
            for (Map.Entry<String, PostToolUseHook> entry : snapshot) {
                futures.add(submitPostToolUseFailureHook(entry.getKey(), entry.getValue(),
                    toolName, input, errorResult, ctx, stopHookActive));
            }
            // [H4] 并行等待全部 hook 完成 · 对齐 CC all(hookPromises) (hooks.ts:2744).
            //   单 hook 超时/异常已在 future 内隔离为 null 正常完成; 仅 AbortException 会异常完成
            //   (用户中止意图不可吞, 对齐 CC hooks.ts:2045-2051), 此处解包后透传给 caller.
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof AbortException ae) {
                    // [IMP-HOOKS-S6 CCJ-T6-11] abort 中断批 → 全部未完成 hook 按 cancelled 计数
                    outcomes.cancelled += snapshot.size();
                    emitToolHookFinishedTelemetry("PostToolUseFailure", outcomes,
                        System.currentTimeMillis() - toolChainStartMs);
                    // [JS-05 GAP-9] beta-tracing OTEL hook_execution_complete (abort 路径).
                    //   [R-3] 与 start 同条件门控: all-internal 批次对齐 CC fast-path start/complete 均不发射.
                    if (!matched.isEmpty() || userSnapshotCount > 0) {
                        emitHookExecutionCompleteTelemetry("PostToolUseFailure", "PostToolUseFailure:" + toolName,
                            matched.size() + snapshot.size(), outcomes, betaDefsJson, betaSpan);
                    }
                    throw ae;
                }
                log.warn("HOOK 并行等待异常(非 abort): {}", cause != null ? cause.toString() : ce.toString());
            }
            for (int i = 0; i < snapshot.size(); i++) {
                String hookName = snapshot.get(i).getKey();
                GenericHook.HookResult hookResult = futures.get(i).join(); // 并行已完成, join 不阻塞
                if (hookResult == null) {
                    // [IMP-HOOKS-S6 CCJ-T6-11] 超时/异常 → non_blocking_error 计数 (CC :2735-2738)
                    outcomes.nonBlockingError++;
                    continue;
                }
                // [IMP-HOOKS-S6 CCJ-T6-11] programmatic outcome 计数 (HookResult.outcome 直读)
                outcomes.add(hookResult.outcome());
                if (hookResult.preventContinuation() && !preventContinuation) {
                    preventContinuation = true;
                    firstReason = hookResult.stopReason();
                }
                if (firstBlockingError == null && hookResult.blockingError() != null) {
                    firstBlockingError = hookResult.blockingError();
                }
                // [IMP-DA-01 TY-01] 失败链同 CC runPostToolUseFailureHooks toolHooks.ts:257-267:
                //   逐 blocking result 产 1 hook_blocking_error 附件, 无 preventContinuation 分支,
                //   循环持续 (MERG-01 R2) → 每 blocking result 都并入 messages (复用/合成).
                if (hookResult.blockingError() != null) {
                    HookBlockingError be = hookResult.blockingError();
                    boolean hasBlockingAttachment = hookResult.message() instanceof AttachmentMessageDto att
                        && "hook_blocking_error".equals(att.type());
                    if (!hasBlockingAttachment) {
                        messages.add(AttachmentMessageDto.hookBlockingError(
                            "PostToolUseFailure:" + toolName,
                            toolUseId,
                            "PostToolUseFailure", be.blockingError(), be.command()));
                    }
                }
                // [IMP-HOOKS-S6 CCJ-T6-19 + H-WF5a-02] systemMessages 折叠 (CC hooks.ts:2769-2780
                //   逐结果 yield, failure 链同样逐条注入 hook_system_message attachment)
                if (hookResult.systemMessages() != null) {
                    systemMessages.addAll(hookResult.systemMessages());
                }
                if (hookResult.message() != null) {
                    messages.add(hookResult.message());
                }
                if (firstHookPermissionDecisionReason == null
                    && hookResult.hookPermissionDecisionReason() != null) {
                    firstHookPermissionDecisionReason = hookResult.hookPermissionDecisionReason();
                }
                if (hookResult.additionalContexts() != null) {
                    additionalContexts.addAll(hookResult.additionalContexts());
                }
                if (log.isDebugEnabled()) {
                    log.debug("HOOK PostToolUseFailure '{}' result: blockingError={} preventContinuation={}",
                        hookName, hookResult.blockingError() != null, hookResult.preventContinuation());
                }
                // [MERG-01 R2 修复] CC 失败链无 preventContinuation 早停: runPostToolUseFailureHooks
                //   (toolHooks.ts:193-319) 无 preventContinuation 分支/无 return, 阻断 payload 落入
                //   无匹配分支, 循环持续 → 后续失败 hook 的 message/additionalContexts 全消费.
                //   (原 :2260-2263 break 误引成功链 toolHooks.ts:129 return 语义, 已删除)
                // [Session H5] CC toolHooks.ts:224-243 hook_cancelled -> tengu_post_tool_failure_hooks_cancelled.
                if (ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled()) {
                    emitHookCancelledTelemetry(toolName, "tengu_post_tool_failure_hooks_cancelled");
                    // [IMP-HOOKS-S6 CCJ-T6-11] 剩余未 drain hook 按 cancelled 计数 (近似, 同 executePostToolUse)
                    outcomes.cancelled += snapshot.size() - i - 1;
                    break;
                }
            }
        }
        // [IMPL-03 D6-1 / OD-05] 单链: 配置驱动 PostToolUseFailure hook 结果并入聚合
        //   (旧 fireGenericEvent 丢弃返回值).
        // [IMP-HOOKS-S6 CCJ-T6-14] PostToolUseFailure 载荷补 error/is_interrupt ·
        //   对齐 CC executePostToolUseFailureHooks (hooks.ts:3492-3527): hook_input 含
        //   error (coreSchemas.ts:455, 必传 string) + is_interrupt (:456, optional) —
        //   旧 6 参工厂载荷缺两字段 (⊕3 死代码反转). error 推导: ToolResult.error 的
        //   data = message (ToolResult.java:186-189 自验); isInterrupt 由调用方显式传入
        //   (StreamingToolExecutor catch 路径传 isAbort, 对齐 CC isInterrupt = error instanceof
        //   AbortError, toolExecution.ts:1694; P-25 删旧 "abort" 分类字符串匹配).
        //   tool_use_id 经 8 参工厂 data map 承载
        //   (permissionDenied 先例, coreSchemas.ts:448 必传不丢).
        // [fix-ts04 IMPL-01 OD-TS04-01] 工具链 ctx 透传批级 (3 参 executeEvent, 同 executePreToolUse)
        GenericHook.HookResult configuredResult = executeEvent(toolPostFailureEvent, null, ctx);
        // 配置驱动段 outcome 计数 (折叠表达, 见 emitToolHookFinishedTelemetry 注释)
        if (!matched.isEmpty()) {
            if (configuredResult == null || !hasIntervention(configuredResult)) {
                outcomes.success += matched.size();
            } else {
                int n = matched.size();
                switch (configuredResult.outcome()) {
                    case BLOCKING -> outcomes.blocking += n;
                    case NON_BLOCKING_ERROR -> outcomes.nonBlockingError += n;
                    case CANCELLED -> outcomes.cancelled += n;
                    case SUCCESS -> outcomes.success += n;
                }
            }
        }
        // [IMP-HOOKS-S6 CCJ-T6-11] 批尾遥测 (CC hooks.ts:2935-2944)
        emitToolHookFinishedTelemetry("PostToolUseFailure", outcomes,
            System.currentTimeMillis() - toolChainStartMs);
        // [JS-05 GAP-9] beta-tracing OTEL hook_execution_complete (CC hooks.ts:2946-2963).
        //   [R-3] 与 start 同条件门控: all-internal 批次对齐 CC fast-path start/complete 均不发射.
        if (!matched.isEmpty() || userSnapshotCount > 0) {
            emitHookExecutionCompleteTelemetry("PostToolUseFailure", "PostToolUseFailure:" + toolName,
                matched.size() + snapshot.size(), outcomes, betaDefsJson, betaSpan);
        }
        // [MERG-01 R2 修复] 失败链无 preventContinuation 早停 (CC toolHooks.ts:193-319 无 return),
        //   programmatic 段 preventContinuation 不阻断 configured 结果消费 (原 !preventContinuation
        //   门控为成功链 toolHooks.ts:129 return 语义误引, 已移除)
        if (configuredResult != null && hasIntervention(configuredResult)) {
            if (configuredResult.preventContinuation() && !preventContinuation) {
                preventContinuation = true;
                firstReason = configuredResult.stopReason();
            }
            if (firstBlockingError == null && configuredResult.blockingError() != null) {
                firstBlockingError = configuredResult.blockingError();
            }
            // [IMP-DA-01 TY-01] 失败链 configured blocking 附件并入 messages (复用/合成) ·
            //   CC runPostToolUseFailureHooks 无 preventContinuation 分支, 每 blocking 均产附件.
            if (configuredResult.blockingError() != null) {
                HookBlockingError cbe = configuredResult.blockingError();
                boolean cHasBlockingAttachment = configuredResult.message() instanceof AttachmentMessageDto catt
                    && "hook_blocking_error".equals(catt.type());
                if (!cHasBlockingAttachment) {
                    messages.add(AttachmentMessageDto.hookBlockingError(
                        "PostToolUseFailure:" + toolName,
                        toolUseId,
                        "PostToolUseFailure", cbe.blockingError(), cbe.command()));
                }
            }
            if (configuredResult.systemMessages() != null) {
                systemMessages.addAll(configuredResult.systemMessages());
            }
            if (configuredResult.message() != null) {
                messages.add(configuredResult.message());
            }
            if (firstHookPermissionDecisionReason == null
                && configuredResult.hookPermissionDecisionReason() != null) {
                firstHookPermissionDecisionReason = configuredResult.hookPermissionDecisionReason();
            }
            if (configuredResult.additionalContexts() != null) {
                additionalContexts.addAll(configuredResult.additionalContexts());
            }
            if (log.isDebugEnabled()) {
                log.debug("HOOK PostToolUseFailure 配置驱动结果并入 (工具 {}): preventContinuation={} blockingError={}",
                    toolName, configuredResult.preventContinuation(),
                    configuredResult.blockingError() != null);
            }
        }

        boolean allNull = !preventContinuation
            && firstBlockingError == null && systemMessages.isEmpty()
            && messages.isEmpty()
            && firstHookPermissionDecisionReason == null && additionalContexts.isEmpty();
        if (allNull) {
            return GenericHook.HookResult.proceed();
        }
        return new GenericHook.HookResult(preventContinuation,
            firstBlockingError,
            systemMessages.isEmpty() ? null : systemMessages,
            additionalContexts.isEmpty() ? null : additionalContexts,
            messages.isEmpty() ? null : messages,
            null, null,
            null, firstHookPermissionDecisionReason,
            // [IMP-DA-01 TY-01] allBlockingErrors 参数已删除 — blocking 附件经 messages 逐条承载
            GenericHook.HookOutcome.SUCCESS, firstReason, null, null, null, null, null, null, null);
    }

    /**
     * [P1-1] 将 ToolResult 转为 JsonNode 供 generic HookEvent 使用.
     * [A1·退役 content()] content() 改 data(): ToolResult&lt;T&gt; data 跨类型, 取 String 表示.
     */
    private static JsonNode resultToJsonNode(ToolResult<?> result, String toolUseId, boolean isError) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.createObjectNode();
            node.put("toolUseId", toolUseId);
            // [A1] data() 替代 content(): T 可能 String / JsonNode 等, 统一取字符串
            String dataStr = result.data() instanceof String s ? s : String.valueOf(result.data());
            node.put("content", dataStr);
            node.put("isError", isError);
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 执行 — GenericHook (§14 扩展)
    // ════════════════════════════════════════════════════════════════════════

    public GenericHook.HookResult executeEvent(HookEvent event) {
        // [H5-GAP-1] 无 messages 的默认入口 → 委托带 messages 重载 (function hook 回调收到空消息).
        return executeEvent(event, null);
    }

    /**
     * [H5-GAP-1] executeEvent 带会话消息 · 对齐 CC executeHooks (messages 参数)
     *
     * <p>WHY (H5-GAP-1 登记 J.md §J.2.1): CC session hooks 由 getAllHooks (hooksSettings.ts:146-158)
     * 并入匹配集再执行 — session 作用域临时 hook (addSessionHook/addFunctionHook 注册的) 在
     * {@code executeEvent} 时被检索并执行. Java 端 {@link SessionHookStore} 是惰性存储, 此前
     * {@code executeEvent} 只遍历 {@link #genericHooks} (settings 持久化), session hook 存储与
     * 执行链路断开. 本重载在此闭合"三级存储 → 执行"闭环:
     * <ul>
     *   <li>command/prompt/agent/http session hook → 按 matcher 匹配后走 {@link #executeOneConfiguredHook}
     *       (CommandHookExecutor / ExecPromptHook / ...), 结果并入 firstStop/blockingError/retry 收集器</li>
     *   <li>function hook → 按 matcher 匹配后走 {@link #executeSessionFunctionHook} (内存回调 +
     *       timeout 超时, 对齐 CC executeFunctionHook hooks.ts:4740-4830)</li>
     *   <li>每个 session hook 执行后 → {@link #invokeSessionHookOnSuccess} (对齐 CC hooks.ts:2906-2925
     *       getSessionHookCallback + onHookSuccess, 仅 outcome=success 触发)</li>
     * </ul>
     *
     * @param event    hook 事件
     * @param messages 当前会话消息列表 (CC executeHooks 的 messages; function hook callback 入参;
     *                 null → function hook 收到空列表)
     * @return 聚合结果 (优先 firstStop; 否则 firstBlockingError; 否则 retry; 否则 proceed)
     */
    public GenericHook.HookResult executeEvent(HookEvent event, List<ChatMessageDto> messages) {
        return executeEvent(event, messages, null);
    }

    /**
     * [对抗核验 H13-GAP-1 v3] executeEvent 带父 ToolUseContext · 供 ExecAgentHook 继承父权限规则。
     *
     * <p>WHY (J.md H13-GAP-1 登记): CC execAgentHook.ts:141-153 的 {@code getAppState()} override
     * 从父 {@code toolPermissionContext} 继承 alwaysAllowRules 并追加 {@code Read(/transcriptPath)}
     * session 规则 + mode:'dontAsk'。Java 旧实现 ExecAgentHook 无父 permission context 继承
     * （ToolUseContext.of(hookAgentUuid, sessionId) 空规则集），DONT_ASK+空规则会拒绝 hook agent
     * 全部工具。本重载把当前父 per-turn TUC（含每轮重建后的最新 permCtx）沿分发链透传到
     * {@code executeConfiguredAgent} → {@link ExecAgentHook#exec}。
     *
     * @param event     hook 事件
     * @param messages  当前会话消息列表 (null → function hook 收到空列表)
     * @param parentTuc 当前父 per-turn ToolUseContext（含最新 permissionContext；null = 无父上下文,
     *                  agent hook 保持旧行为）
     * @return 聚合结果 (优先 firstStop; 否则 firstBlockingError; 否则 retry; 否则 proceed)
     */
    public GenericHook.HookResult executeEvent(HookEvent event, List<ChatMessageDto> messages,

                                               com.nexusai.application.agent.tool.ToolUseContext parentTuc) {
        // [IMP-RS-01 DEL-01e 补回] 3 参公开入口默认无 prompt 回调通道 (对齐 CC feature('HOOK_PROMPTS')
        //   =false 时 executeEvent 无 requestPrompt 参数); 需要通道的调用方 (executePreToolUse /
        //   UserPromptSubmit 链) 走 4 参重载.
        return executeEvent(event, messages, parentTuc, null);
    }

    /**
     * [IMP-RS-01 DEL-01e 补回] executeEvent 带 prompt 回调通道 (4 参) · 对齐 CC executeHooks
     * 的 requestPrompt 参数 (hooks.ts:1961/1972-1975) — 通道只在 PreToolUse / UserPromptSubmit
     * 链激活 (executePreToolUse 6 参 / UserPromptSubmit 调用方显式传入), 其他事件无通道
     * (与 CC executeStopHooks/executePostToolUseHooks 不传 requestPrompt 一致, hooks.ts:3286).
     *
     * <p>生产调用方: {@link #executePreToolUse(String, JsonNode, ToolUseContext, String, String, PromptRequester)}
     * (PreToolUse) + LlmAgentLoop UserPromptSubmit 段 (等价 CC executeUserPromptSubmitHooks
     * processUserInput.ts:186 透传 context.requestPrompt, hooks.ts:3830-3853)。
     *
     * @param promptRequester CC original: requestPrompt (hooks.ts:759); 绑定版 prompt 回调
     *                        (已绑 sourceName + toolInputSummary); null=通道关闭
     * @return 聚合结果 (优先 firstStop; 否则 firstBlockingError; 否则 retry; 否则 proceed)
     */
    public GenericHook.HookResult executeEvent(HookEvent event, List<ChatMessageDto> messages,

                                               com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                               PromptRequester promptRequester) {
        // [IMPL-01 D1-1 / INV-1] 政策闸门: disableAllHooks 短路先于任何匹配/执行
        //   (对齐 CC executeHooks 入口 hooks.ts:1978-1980 — 短路在 getMatchingHooks 之前).
        if (shouldDisableAllHooksIncludingManaged()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEvent: policySettings.disableAllHooks=true, 跳过全部 hook (事件 {})",
                    event.type());
            }
            return GenericHook.HookResult.proceed();
        }
        // [2026-08-12 △-04] CLAUDE_CODE_SIMPLE 短路 · 对齐 CC executeHooks 入口
        //   (hooks.ts:1981-1983: `if (isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE)) return`).
        //   位于 disableAllHooks 之后、trust 之前（CC 顺序一致）。Java 复用
        //   MemoryBareModeConfig.isBareMode()（对齐 CC isBareMode envUtils.ts:60-65，
        //   同一 CLAUDE_CODE_SIMPLE truthy 判定 + Java 独有配置覆盖）。
        if (com.nexusai.application.agent.config.MemoryBareModeConfig.isBareMode()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEvent: bare 模式 (CLAUDE_CODE_SIMPLE), 跳过全部 hook (事件 {})",
                    event.type());
            }
            return GenericHook.HookResult.proceed();
        }
        // [IMPL-04 D9-1 / INV-9 / OD-13] trust 门控: 交互模式全部 hook 要求 workspace trust ·
        //   对齐 CC executeHooks 入口 (hooks.ts:1994) — 短路先于 getMatchingHooks.
        //   历史漏洞: SessionEnd 在拒绝 trust 后执行 (hooks.ts:280-283 注释) 防 RCE 的安全门.
        //   旧实现 executeEvent 无任何 trust 门控 (EV-CCE-034).
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEvent: workspace trust 未接受, 跳过全部 hook (事件 {})",
                    event.type());
            }
            return GenericHook.HookResult.proceed();
        }
        // [H1] 配置驱动 hook 主链路接线: 让 settings.json 配置到达执行入口.
        // [H2] 命令执行器已接线 (下方 instanceof CommandHook 分发), 观测日志保留命中数.
        List<MatchedHook> matched = getMatchingHooks(event);
        if (!matched.isEmpty() && log.isInfoEnabled()) {
            log.info("配置驱动 hooks 命中 {} 个 (事件 {})", matched.size(), event.type());
        }

        // [fix-ts04 IMPL-02 / OD-TS04-03] 入口级 signal 早退 · 对齐 CC executeHooks 入口
        //   (hooks.ts:2015-2017 `if (signal?.aborted) return`) — 位置 = 匹配后、执行前.
        //   父 per-turn TUC 已取消 → 整批静默跳过 (配置驱动 + programmatic + session
        //   function 全链, 对齐 CC 单点早返整批跳过), 零结果产出 (proceed = 无干预).
        //   executor 层既有预检 (command :2707/http ExecHttpHook/prompt ExecPromptHook)
        //   保留不删 (OD-TS04-03 定案 C 不动, 防未来绕过入口的直调路径).
        com.nexusai.application.agent.tool.AbortController parentAbort =
            parentTuc != null ? parentTuc.abortController() : null;
        if (parentAbort != null && parentAbort.isCancelled()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEvent: 父 abort 已取消, 整批跳过全部 hook (事件 {})", event.type());
            }
            return GenericHook.HookResult.proceed();
        }

        List<Map.Entry<String, GenericHook>> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(genericHooks.entrySet());
        }
        GenericHook.HookResult firstStop = null;
        HookBlockingError firstBlockingError = null;
        // [H3 v4 Gap①] 首个提供 blockingError 的完整结果 · 保留其 message attachment
        //   (hook_blocking_error). WHY: 聚合层 blockingError 分支用 HookResult.stop 合成
        //   stop 占位会丢弃原结果的 message — 非工具事件消费者拿不到 hook_blocking_error
        //   attachment 注入 LLM (对抗复验 PARTIAL 残留).
        GenericHook.HookResult firstBlockingResult = null;
        Boolean firstRetry = null;
        // [H7-v3 Gap①] 非阻断结果折叠收集器 · 对齐 CC executeHooks
        //   (hooks.ts:2796 `if (result.message) yield { message: result.message }`).
        //   WHY: httpToHookResult 对 aborted/error/validationError/wrong-event-name 产出
        //   outcome=CANCELLED/NON_BLOCKING_ERROR (+ hook_cancelled/hook_non_blocking_error
        //   attachment 在 message 字段), 但 v2 聚合层只折叠 preventContinuation/blockingError/
        //   retry, 非阻断结果在 executeEvent 被丢弃 (v2 对抗复验 PARTIAL). v3 修复: 折叠首个
        //   非阻断 outcome (CANCELLED/NON_BLOCKING_ERROR) 的完整结果 (保留 message + hook 字段),
        //   供调用方 (Stop 路径 / 通用事件消费者) 注入 LLM. 折叠条件用 outcome 而非 message,
        //   因为 wrong-event-name 路径 (processHookJSONOutput 抛异常被 catch) 只设 outcome
        //   NON_BLOCKING_ERROR 而 message=null (H7-GAP-3 fail-loud 语义) — 仅按 message 折叠
        //   会漏掉该结果, ccName 接线回归测试无法观测.
        GenericHook.HookResult firstMessageResult = null;
        // [Session S07] 首个 permissionRequestResult 折叠收集器 · 对齐 CC hooks.ts:2882-2886
        //   (`if (result.permissionRequestResult) yield { permissionRequestResult }`).
        //   WHY: PermissionRequest 事件 (coordinator/interactive 的 runHooks 等价) 的决策
        //   从 HookResult 顶层读取; CC runHooks 取首个带决策的结果 (PermissionContext.ts:231-259),
        //   故按"首非空"折叠, 随折叠结果透传给消费方.
        PermissionRequestResult firstPermissionRequestResult = null;

        // [MT-04 / OPD-WF2-MT-04] session 作用域临时 hook 执行 · 聚合序对齐 CC 完成序.
        //   [X-PROBE EV-XP-W2-001~007] CC getHooksConfig (hooks.ts:1552-1562) 把 session
        //   function hooks push 进单链 hooks 数组, executeHooks 经 all(hookPromises)
        //   (hooks.ts:2744 = Promise.race, generators.ts:32-64) 按**完成序**折叠 —
        //   function hook 是内存回调 (亚毫秒完成), 通常最先完成 → 其 stop/blockingError
        //   最先折叠 (first-wins 胜者). Java 旧实现确定性桶序 (configured → session →
        //   programmatic) 把 function hook 排后 → 同事件 command hook + session function
        //   hook 双产 stop/blockingError 时胜者与 CC 相反. 本变更把 session function
        //   hooks 先于配置驱动 hooks 执行+折叠, 对齐 CC 完成序.
        //   [IMPL-07] command/prompt/agent/http session hooks 已并入统一匹配链
        //   (getMatchingHooks → executeConfiguredHooks, 见下 executeEvent); 本调用只执行
        //   function hooks (内存回调, 无法持久化/去重).
        //   [IMP-HR-07 · OPD-WF6-01-05 · 返工 R-1/R-2] 仅 CC appState 发射点事件执行（镜像 CC
        //   appState 语义）——事件类型 ∈ CC appState 发射点集合 + 事件 sessionId 有活跃 agent
        //   循环才执行 session function hooks。[返工 R-2] SessionEnd(:2200) 是 CC appState
        //   发射点（executeSessionEndHooks → executeHooksOutsideREPL 传 getAppState，hooks.ts:4118）
        //   → 会话运行时参与；Notification/ConfigChange/Elicitation 等 executeHooksOutsideREPL
        //   事件调用方不传 getAppState → appState undefined → 排除。
        List<GenericHook.HookResult> sessionResults = isSessionHookEligible(event)
            ? executeSessionHooks(event, messages)
            : List.of();
        for (GenericHook.HookResult hookResult : sessionResults) {
            if (hookResult == null) {
                continue;
            }
            if (hookResult.preventContinuation() && firstStop == null) {
                if (log.isDebugEnabled()) {
                    log.debug("session hook 阻止继续: {}", hookResult.stopReason());
                }
                firstStop = hookResult;
            }
            if (hookResult.blockingError() != null && firstBlockingError == null) {
                firstBlockingError = hookResult.blockingError();
                // [H3 v4 Gap①] 保留完整结果供 message attachment 提取
                firstBlockingResult = hookResult;
            }
            if (Boolean.TRUE.equals(hookResult.retry()) && firstRetry == null) {
                firstRetry = hookResult.retry();
            }
            if (hookResult.permissionRequestResult() != null && firstPermissionRequestResult == null) {
                firstPermissionRequestResult = hookResult.permissionRequestResult();
            }
            // [H7-v3 Gap①] 折叠首个非阻断结果 (outcome=CANCELLED/NON_BLOCKING_ERROR)
            // [H3 v4 Gap①] 扩展到任何带 message 的结果 (含 SUCCESS 的 hook_success)
            if (firstMessageResult == null && (isNonBlockingOutcome(hookResult) || hookResult.message() != null)) {
                firstMessageResult = hookResult;
            }
        }

        // [H4] 配置驱动 hook 并行分发 · 对齐 CC executeHooks (hooks.ts:2142-2744):
        //   全部 hook 并行 (allOf, hooks.ts:2744) + 每类型独立 executor 分支
        //   (command/prompt/agent/http, hooks.ts:2147-2613). callback/function 由
        //   programmatic GenericHook 桥接 (FunctionHook 留 H5).
        //   结果折叠进 firstStop/firstBlockingError/firstRetry/firstMessageResult 收集器,
        //   与 programmatic hook 合并. parentTuc 透传给 hook agent (H13 v3 父权限上下文).
        //   [MT-04 / OPD-WF2-MT-04] 聚合序: 位于 session function hooks 之后 (CC 完成序:
        //   function hook 内存回调先完成 → 先折叠; command/prompt/agent/http 起进程后完成).
        // [H2/CCJ-EXEC-01] messages 透传配置驱动 prompt hook（CC hooks.ts:1959/:2230-2239
        //   executeHooks messages → execPromptHook 第 7 参；仅 Stop/SubagentStop 生产传入）
        // [IMP-RS-01 编译协调修复] promptRequester 属 6 参重载 (long, PromptRequester); 补 default timeout
        //   (原 5 参传 promptRequester 报 "PromptRequester 无法转换为 long" — 并行 IMP-RS-01 进行中遗留)
        List<GenericHook.HookResult> configuredResults = executeConfiguredHooks(event, matched, parentTuc, messages,
            CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, promptRequester);
        for (GenericHook.HookResult hookResult : configuredResults) {
            if (hookResult == null) {
                continue;
            }
            if (hookResult.preventContinuation() && firstStop == null) {
                if (log.isDebugEnabled()) {
                    log.debug("配置驱动 hook 阻止继续: {}", hookResult.stopReason());
                }
                firstStop = hookResult;
            }
            if (hookResult.blockingError() != null && firstBlockingError == null) {
                firstBlockingError = hookResult.blockingError();
                // [H3 v4 Gap①] 保留完整结果供 message attachment 提取
                firstBlockingResult = hookResult;
            }
            if (Boolean.TRUE.equals(hookResult.retry()) && firstRetry == null) {
                firstRetry = hookResult.retry();
            }
            if (hookResult.permissionRequestResult() != null && firstPermissionRequestResult == null) {
                firstPermissionRequestResult = hookResult.permissionRequestResult();
            }
            // [H7-v3 Gap①] 折叠首个非阻断结果 (outcome=CANCELLED/NON_BLOCKING_ERROR).
            //   含 message 的完整结果 — 保留 message + hook 字段供调用方注入 LLM.
            // [H3 v4 Gap①] 扩展到任何带 message 的结果 (含 SUCCESS 的 hook_success): 非工具事件
            //   消费者需要 hook_success/hook_blocking_error attachment 注入 LLM (CC executeHooks
            //   yield message 对齐), 此前仅折叠非阻断 outcome 会把 SUCCESS 的 message 丢弃.
            if (firstMessageResult == null && (isNonBlockingOutcome(hookResult) || hookResult.message() != null)) {
                firstMessageResult = hookResult;
            }
        }

        if (genericHooks.isEmpty()) {
            return resolveEventResult(firstStop, firstBlockingError, firstBlockingResult, firstRetry, firstMessageResult, firstPermissionRequestResult);
        }
        if (!snapshot.isEmpty()) {
            // [H4] programmatic generic hook 并行执行 · 对齐 CC executeHooks all(hookPromises) (hooks.ts:2744).
            //   先按事件类型过滤, 再并行提交, 结果按原 snapshot 顺序聚合到
            //   firstStop/firstBlockingError/firstRetry 收集器.
            //   [IMPL-10] DEL-L03-04: sessionScope 过滤已删除（会话作用域由 SessionHookStore 承担）。
            List<Map.Entry<String, GenericHook>> eligible = new ArrayList<>(snapshot.size());
            synchronized (this) {
                for (Map.Entry<String, GenericHook> entry : snapshot) {
                    String hookName = entry.getKey();
                    Set<HookEventType> filter = hookEventFilters.get(hookName);
                    if (filter != null && !filter.isEmpty() && !filter.contains(event.type())) {
                        continue;
                    }
                    eligible.add(entry);
                }
            }
            List<CompletableFuture<GenericHook.HookResult>> futures = new ArrayList<>(eligible.size());
            for (Map.Entry<String, GenericHook> entry : eligible) {
                futures.add(submitGenericHook(entry.getKey(), entry.getValue(), event));
            }
            // [H4] 并行等待全部 hook 完成 · 对齐 CC all(hookPromises) (hooks.ts:2744).
            //   单 hook 超时/异常已在 future 内隔离为 null 正常完成; 仅 AbortException 会异常完成
            //   (用户中止意图不可吞, 对齐 CC hooks.ts:2045-2051), 此处解包后透传给 caller.
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
            } catch (CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof AbortException ae) {
                    throw ae;
                }
                log.warn("HOOK 并行等待异常(非 abort): {}", cause != null ? cause.toString() : ce.toString());
            }
            for (int i = 0; i < eligible.size(); i++) {
                String hookName = eligible.get(i).getKey();
                GenericHook.HookResult result = futures.get(i).join(); // 并行已完成, join 不阻塞
                boolean prevented = result != null && result.preventContinuation();
                if (prevented && firstStop == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Hook {} prevented continuation: {}", hookName, result.stopReason());
                    }
                    firstStop = result;
                }
                if (result != null && result.blockingError() != null && firstBlockingError == null) {
                    firstBlockingError = result.blockingError();
                    // [H3 v4 Gap①] 保留完整结果供 message attachment 提取
                    firstBlockingResult = result;
                    if (log.isDebugEnabled()) {
                        String preview = firstBlockingError.blockingError() != null
                            && firstBlockingError.blockingError().length() > 100
                            ? firstBlockingError.blockingError().substring(0, 100) + "..."
                            : (firstBlockingError.blockingError() != null ? firstBlockingError.blockingError() : "");
                        log.debug("Hook '{}' 提供 blockingError 通道: {}", hookName, preview);
                    }
                }
                if (result != null && Boolean.TRUE.equals(result.retry()) && firstRetry == null) {
                    firstRetry = result.retry();
                }
                if (result != null && result.permissionRequestResult() != null
                        && firstPermissionRequestResult == null) {
                    firstPermissionRequestResult = result.permissionRequestResult();
                }
                // [H7-v3 Gap①] 折叠首个非阻断结果 (outcome=CANCELLED/NON_BLOCKING_ERROR)
                // [H3 v4 Gap①] 扩展到任何带 message 的结果 (含 SUCCESS 的 hook_success);
                //   result 可为 null (hook 失败/返回 null 的 future 归一化) → 先判 null
                if (firstMessageResult == null && result != null
                    && (isNonBlockingOutcome(result) || result.message() != null)) {
                    firstMessageResult = result;
                }
            }
        }
        return resolveEventResult(firstStop, firstBlockingError, firstBlockingResult, firstRetry, firstMessageResult, firstPermissionRequestResult);
    }

    /**
     * [hook-aggregate] 执行事件并返回全部 hook 结果（不折叠）· 对齐 CC executeHooks
     * {@code for await (const result of all(hookPromises))} 逐 hook yield（hooks.ts:2744, :2757-2762）。
     *
     * <p><b>WHY</b>: {@link #executeEvent(HookEvent)} 在 :1331-1332 只保留 firstBlockingError
     * 并经 {@link #resolveEventResult}（:1666-1720）折叠为单个 HookResult —— 工具层永远只能
     * 见到 1 个阻塞错误。CC executeTaskCreatedHooks / executeTaskCompletedHooks 消费方逐
     * result.blockingError 收集全部（TaskCreateTool.ts:104-108 / TaskUpdateTool.ts:247-253），
     * 本方法暴露全部非 null hook 结果供工具逐条聚合。
     *
     * <p><b>纯增量</b>: 完全复刻 {@link #executeEvent(HookEvent)} 的并行分发（配置驱动
     * executeConfiguredHooks + programmatic eligible 过滤 + submitGenericHook 并行 +
     * AbortException 透传），仅将"折叠为单结果"改为"按序收集全部结果"，不改 executeEvent
     * 现有 27 个调用方的折叠语义（风险隔离）。
     *
     * @param event hook 事件
     * @return 全部非 null hook 结果（配置驱动在前、programmatic 按注册序在后）；无 hook → 空 List
     */
    public List<GenericHook.HookResult> executeEventAll(HookEvent event) {
        // [R6-IMP] DEL-TH-06 恢复：parentTuc 重载的 1 参便捷版（parentTuc=null，行为与
        //   重构前硬编码 null 完全一致）。Stop 段用 2 参重载透传父 TUC（H13-GAP-1 父权限规则）。
        // [IMP-A2-2] 显式 cast 消歧：新增 (HookEvent, AbortController) 批级 abort 重载后
        //   null 字面量二义（ToolUseContext vs AbortController）。
        return executeEventAll(event, (com.nexusai.application.agent.tool.ToolUseContext) null);
    }

    /**
     * [IMP-A2-2 · OPD-CM5-A-07] 批级 abort 信号的 executeEventAll 重载 · 对齐 CC
     * executePreCompactHooks/executePostCompactHooks 传 {@code context.abortController.signal}
     * （compact.ts:418 / :728，executeHooksOutsideREPL 入口 {@code if (signal?.aborted) return []}
     * hooks.ts:3051-3053）。
     *
     * <p><b>WHY 不传 parentTuc</b>: 父 per-turn TUC 透传会经 {@link #executeConfiguredHooks} →
     * {@code enrichBaseFields} 注入 agent_id（ToolUseContext.agentId 恒非 null，UUID 兜底），
     * 而 CC compact hook input 用 {@code createBaseHookInput(undefined)}（hooks.ts:3966）——
     * agent_id 应为 undefined。故只传批级 abort（null 父 TUC → base 字段保持 CC 语义），
     * abort 早退由 {@code batchAbort.isCancelled()} 承担（同 5 参重载入口检查）。
     *
     * @param event      hook 事件
     * @param batchAbort 批级 abort 信号（CC signal；null/NOOP = 永不取消）
     * @return 全部非 null hook 结果；无 hook / 已 abort → 空 List
     */
    public List<GenericHook.HookResult> executeEventAll(HookEvent event,
                                                        com.nexusai.application.agent.tool.AbortController batchAbort) {
        // 父 TUC=null（对齐 CC createBaseHookInput(undefined)，不注入 agent_id）·
        // 缺省超时 10min · 批级 abort = 调用方 compact 上下文 abortController
        return executeEventAll(event, null, null,
            CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, batchAbort);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [EX-HOOK R5] Worktree hooks · 对齐 CC executeWorktreeCreateHook /
    //   executeWorktreeRemoveHook (hooks.ts:4928-4958 / :4967-5003)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [EX-HOOK R5] 执行 WorktreeCreate hooks · 对齐 CC hooks.ts:4928-4958
     * {@code executeWorktreeCreateHook}。
     *
     * <p>流程: 构造 WorktreeCreate 事件（data.name）→ {@link #executeEventAll}
     * （executeHooksOutsideREPL 等价入口）→ 取第一个 {@code succeeded && output.trim() 非空}
     * 的结果 → worktreePath = output.trim()。无成功结果 → throw
     * {@code 'WorktreeCreate hook failed: ...'}（CC :4951-4956，失败明细拼接）。
     *
     * <p>output 语义（CC :3336-3340）: exit 0 → stdout；失败 → stderr。Java 端映射为
     * hook_success attachment.stdout（exit 0）/ hook_non_blocking_error.stderr /
     * hook_blocking_error.content（失败明细）。
     *
     * @param name worktree 名（CC hookInput.name）
     * @return hook 输出 trim 后的 worktree path（CC successfulResult.output.trim()）
     * @throws IllegalArgumentException 无成功且输出非空的 hook（CC throw new Error）
     */
    /**
     * [IMP-HOOKS-S5 H4 / D-06b] 检查是否配置了 WorktreeCreate hooks（不执行）·
     * 对齐 CC hooks.ts:4910-4920 {@code hasWorktreeCreateHook()}。
     *
     * <p>仅双源检查（settings 快照 + registered programmatic hooks），<b>session 源不参与</b>
     * （CC 无 appState 参数 → getHooksConfig 不合并 session hooks；旧三源门控（WorktreeCreate
     * 事件 + sessionKey）在仅 session hook 配置时误报 true，
     * 使 executeWorktreeCreateHook 空执行 throw，本应走 git worktree 回退路径）。
     *
     * <p>registered 源镜像 CC managedOnly 过滤（hooks.ts:4915-4919
     * {@code !(managedOnly && 'pluginRoot' in matcher)}）：shouldAllowManagedHooksOnly()
     * 为 true 时排除 plugin hook（Java 端 {@link #pluginHookOwners} 跟踪 plugin 归属）。
     *
     * @return true = 任一源存在 WORKTREE_CREATE 配置（含 managedOnly 过滤后的 registered）
     */
    public synchronized boolean hasWorktreeCreateHook() {
        // 源1: settings 配置快照（CC hooks.ts:4911 getHooksConfigFromSnapshot()?.['WorktreeCreate']）
        HooksConfigSnapshot snapshot = this.hooksConfigSnapshot;
        if (snapshot != null) {
            List<HookMatcher> configMatchers =
                snapshot.getHooksConfigFromSnapshot().get(HookEventType.WORKTREE_CREATE);
            if (configMatchers != null && !configMatchers.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("hasWorktreeCreateHook: config 快照源命中 matchers={}",
                        configMatchers.size());
                }
                return true;
            }
        }
        // 源2: registered programmatic hooks（CC hooks.ts:4913 getRegisteredHooks()?.['WorktreeCreate']）
        //   Java hookEventFilters: null/空 filter = 监听全部事件
        boolean managedOnly = shouldAllowManagedHooksOnly();
        for (Map.Entry<String, Set<HookEventType>> entry : hookEventFilters.entrySet()) {
            Set<HookEventType> filter = entry.getValue();
            if (filter != null && !filter.isEmpty() && !filter.contains(HookEventType.WORKTREE_CREATE)) {
                continue;
            }
            // 镜像 CC hooks.ts:4916-4919：managedOnly 时跳过 plugin hooks
            if (managedOnly && pluginHookOwners.containsKey(entry.getKey())) {
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug("hasWorktreeCreateHook: registered 源命中 hook={}", entry.getKey());
            }
            return true;
        }
        return false;
    }

    public String executeWorktreeCreateHook(String name) {
        HookEventData data = new HookEventData.WorktreeCreate(name);
        HookEvent event = new HookEvent(HookEventType.WORKTREE_CREATE, null, null, null, null, null,
            null, null, null, null, null, null, data, 0);
        List<GenericHook.HookResult> results = executeEventAll(event);
        for (GenericHook.HookResult r : results) {
            if (r == null) {
                continue;
            }
            String output = worktreeHookOutputOf(r);
            // CC :4944-4947 successfulResult = results.find(r => r.succeeded && r.output.trim().length > 0)
            if (r.outcome() == GenericHook.HookOutcome.SUCCESS && output != null && !output.trim().isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("HOOK executeWorktreeCreateHook 成功: worktreePath={}", output.trim());
                }
                return output.trim();
            }
        }
        // CC :4948-4956: failedOutputs = results.filter(!succeeded)
        //   .map(`${r.command}: ${r.output.trim() || 'no output'}`)
        List<String> failedOutputs = new ArrayList<>();
        for (GenericHook.HookResult r : results) {
            if (r == null) {
                continue;
            }
            String command = r.hook() != null ? stopHookCommandOf(r.hook()) : "hook";
            String output = worktreeHookOutputOf(r);
            failedOutputs.add((command != null ? command : "hook") + ": "
                + (output == null || output.trim().isEmpty() ? "no output" : output.trim()));
        }
        String detail = failedOutputs.isEmpty() ? "no successful output" : String.join("; ", failedOutputs);
        log.warn("HOOK executeWorktreeCreateHook 失败: {}", detail);
        throw new IllegalArgumentException("WorktreeCreate hook failed: " + detail);
    }

    /**
     * [EX-HOOK R5] 执行 WorktreeRemove hooks · 对齐 CC hooks.ts:4967-5003
     * {@code executeWorktreeRemoveHook}。
     *
     * <p>流程: 双源检查（settings 配置快照 + registered programmatic hooks，CC
     * getHooksConfigFromSnapshot + getRegisteredHooks）→ 无配置 → false；有 → 构造
     * WorktreeRemove 事件（data.worktree_path）→ {@link #executeEventAll} → 结果空 →
     * false；逐 result 失败 log（CC :4995-4999 logForDebugging error）；返回 true。
     *
     * @param worktreePath worktree 路径（CC hookInput.worktree_path）
     * @return true = 已配置且已执行（无论成败）；false = 无配置或结果为空
     */
    public boolean executeWorktreeRemoveHook(String worktreePath) {
        // CC :4973-4978: snapshotHooks + registeredHooks 双源空 → false
        if (!hasHookForEvent("WorktreeRemove", null)) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK executeWorktreeRemoveHook: 无 WorktreeRemove 配置, 返回 false");
            }
            return false;
        }
        HookEventData data = new HookEventData.WorktreeRemove(worktreePath);
        HookEvent event = new HookEvent(HookEventType.WORKTREE_REMOVE, null, null, null, null, null,
            null, null, null, null, null, null, data, 0);
        List<GenericHook.HookResult> results = executeEventAll(event);
        // CC :4990-4992: results.length === 0 → false
        if (results == null || results.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK executeWorktreeRemoveHook: 执行结果为空, 返回 false");
            }
            return false;
        }
        for (GenericHook.HookResult r : results) {
            // CC :4994-4999: 逐 result !succeeded → logForDebugging(error)
            if (r != null && r.outcome() != GenericHook.HookOutcome.SUCCESS) {
                String command = r.hook() != null ? stopHookCommandOf(r.hook()) : "hook";
                String output = worktreeHookOutputOf(r);
                log.warn("HOOK WorktreeRemove hook 失败 [{}]: {}", command,
                    output == null || output.trim().isEmpty() ? "no output" : output.trim());
            }
        }
        return true;
    }

    /**
     * [EX-HOOK R5] Worktree hook 结果 output 提取 · 对齐 CC :3336-3340
     * {@code output = result.status === 0 ? result.stdout || '' : result.stderr || ''}。
     *
     * <p>Java 端映射: outcome==SUCCESS（exit 0）→ hook_success attachment.stdout；
     * 失败 → hook_non_blocking_error.stderr（exit≠0,≠2）或 hook_blocking_error.content
     * （exit 2，CC 失败 output 同取 stderr，Java 阻塞 attachment 以 content 承载原因）。
     *
     * @return 提取的 output；无输出通道 → null（CC '' 等价，调用方 trim 判空）
     */
    private static String worktreeHookOutputOf(GenericHook.HookResult r) {
        if (r == null || r.message() == null) {
            return null;
        }
        if (r.message() instanceof AttachmentMessageDto att) {
            if (r.outcome() == GenericHook.HookOutcome.SUCCESS) {
                return att.stdout() != null ? att.stdout() : "";
            }
            return switch (att.type()) {
                case "hook_non_blocking_error" -> att.stderr() != null ? att.stderr() : "";
                case "hook_blocking_error" -> att.content() != null ? att.content() : "";
                default -> null;
            };
        }
        return null;
    }

    /**
     * [R6-IMP] {@link #executeEventAll(HookEvent)} 的 parentTuc 重载 · 透传父 per-turn
     * ToolUseContext 给配置驱动 hook（ExecAgentHook 父权限继承 H13-GAP-1 / abort 透传
     * IMPL-06 OD-EX-02）。其余语义与 1 参版本逐字一致（同一实现体，仅
     * {@code executeConfiguredHooks(event, matched, parentTuc)} 传参不同）。
     *
     * @param event     hook 事件
     * @param parentTuc 父 per-turn ToolUseContext（含最新 permissionContext；null = 无父上下文）
     * @return 全部非 null hook 结果（配置驱动在前、programmatic 按注册序在后）；无 hook → 空 List
     */
    public List<GenericHook.HookResult> executeEventAll(HookEvent event,
                                                        com.nexusai.application.agent.tool.ToolUseContext parentTuc) {
        // [H2/CCJ-EXEC-01] 无 messages 重载（既有 2 参调用方零改动）· CC executeHooks messages 可选
        return executeEventAll(event, parentTuc, null);
    }

    /**
     * [H2/CCJ-EXEC-01] executeEventAll + messages 透传 · CC executeHooks messages 可选参数
     * （Stop/SubagentStop 生产传入 → execPromptHook 第 7 参）。
     *
     * @param event     hook 事件
     * @param parentTuc 父 per-turn ToolUseContext（含最新 permissionContext；null = 无父上下文）
     * @param messages  会话历史（CC executeHooks messages；Stop/SubagentStop 生产传入）
     * @return 全部非 null hook 结果（配置驱动在前、programmatic 按注册序在后）；无 hook → 空 List
     */
    public List<GenericHook.HookResult> executeEventAll(HookEvent event,
                                                        com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                        List<ChatMessageDto> messages) {
        // [IMP-HOOKS-S5 D-01] 缺省超时与批级 abort 由 5 参重载承载（SessionEnd 专用）·
        //   既有调用方零改动（10min 缺省 + 无批级 abort）
        return executeEventAll(event, parentTuc, messages,
            CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, null);
    }

    /**
     * [IMP-HOOKS-S5 D-01] executeEventAll 核心重载 · 额外承载 SessionEnd 的收紧超时与
     * 批级 abort cap（对齐 CC executeSessionEndHooks → executeHooksOutsideREPL 的
     * {@code signal: AbortSignal.timeout(sessionEndTimeoutMs), timeoutMs: sessionEndTimeoutMs}，
     * gracefulShutdown.ts:472-477）。
     *
     * @param event             hook 事件
     * @param parentTuc         父 per-turn ToolUseContext（null = 无父上下文）
     * @param messages          会话历史（null = 无）
     * @param defaultTimeoutMs  配置驱动 command hook 的缺省超时（CC :3280
     *                          {@code hook.timeout ? hook.timeout*1000 : timeoutMs}；
     *                          SessionEnd 传 1500，其余走 10min 缺省）
     * @param batchAbort        批级 abort 信号（CC AbortSignal.timeout 等价；null = 无；
     *                          到点 abort → 运行中 command hook 经 onCancel → destroyForcibly）
     * @return 全部非 null hook 结果
     */
    public List<GenericHook.HookResult> executeEventAll(HookEvent event,
                                                        com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                        List<ChatMessageDto> messages,
                                                        long defaultTimeoutMs,
                                                        com.nexusai.application.agent.tool.AbortController batchAbort) {
        // [IMPL-01 D1-1 / INV-1] 政策闸门: executeHooksOutsideREPL 等价入口同样短路返回 []
        //   (对齐 CC hooks.ts:3022-3027), 防止只堵 executeEvent 留下第二条执行通道.
        if (shouldDisableAllHooksIncludingManaged()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEventAll: policySettings.disableAllHooks=true, 跳过全部 hook (事件 {})",
                    event.type());
            }
            return List.of();
        }
        // [2026-08-12 △-04] CLAUDE_CODE_SIMPLE 短路 · 对齐 CC executeHooks 入口
        //   (hooks.ts:1981-1983) — executeHooksOutsideREPL 等价入口同门控, 防止只堵
        //   executeEvent 留下第二条执行通道.
        if (com.nexusai.application.agent.config.MemoryBareModeConfig.isBareMode()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEventAll: bare 模式 (CLAUDE_CODE_SIMPLE), 跳过全部 hook (事件 {})",
                    event.type());
            }
            return List.of();
        }
        // [IMPL-04 D9-1 / INV-9 / OD-13] trust 门控: executeHooksOutsideREPL 等价入口同门控 ·
        //   对齐 CC executeHooks (hooks.ts:1994) 覆盖全部消费者 (TaskCreate/TaskCompleted).
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEventAll: workspace trust 未接受, 跳过全部 hook (事件 {})",
                    event.type());
            }
            return List.of();
        }
        // [H1] 配置驱动 hook 主链路接线（对齐 executeEvent :1242）
        List<MatchedHook> matched = getMatchingHooks(event);
        if (!matched.isEmpty() && log.isInfoEnabled()) {
            log.info("配置驱动 hooks 命中 {} 个 (事件 {})", matched.size(), event.type());
        }

        // [fix-ts04 IMPL-02 / OD-TS04-03] 入口级 signal 早退 · 对齐 CC executeHooksOutsideREPL
        //   (hooks.ts:3051-3053 `if (signal?.aborted) return []`) — 位置 = 匹配后、执行前.
        //   父 per-turn TUC 已取消 → 整批静默跳过, 零结果产出 (空列表, 对齐 CC 早返 return []).
        com.nexusai.application.agent.tool.AbortController parentAbort =
            parentTuc != null ? parentTuc.abortController() : null;
        if (parentAbort != null && parentAbort.isCancelled()
            || batchAbort != null && batchAbort.isCancelled()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEventAll: 父/批级 abort 已取消, 整批跳过全部 hook (事件 {})",
                    event.type());
            }
            return List.of();
        }

        List<GenericHook.HookResult> allResults = new ArrayList<>();

        // [MT-04 / OPD-WF2-MT-04] 聚合序对齐 CC 完成序: session function hooks（内存回调，
        //   亚毫秒完成）先于配置驱动 hooks（起进程）收集 — CC executeHooks all(hookPromises)
        //   (hooks.ts:2744) 按完成序 yield, function hook 通常最先.
        // [IMP-HR-07 · OPD-WF6-01-05 · 返工 R-1/R-2] 仅 CC appState 发射点事件补执行 session function
        //   hooks · CC executeStopHooks（hooks.ts:3688-3696）把含 appState 的 executeHooks 作为
        //   唯一执行路径 → session command hooks（getMatchingHooks 已并入）+ session function
        //   hooks（此处）都参与 Stop/SubagentStop 收集。[返工 R-1] 双条件（isSessionHookEligible）：
        //   事件类型 ∈ CC appState 发射点集合 + 会话运行。[返工 R-2] SessionEnd 为 CC appState
        //   发射点 → 会话运行时 session function hooks 参与（主 SessionEnd 经 executeSessionEndHooks
        //   → executeEventAll 本路径）；Worktree/ConfigChange/Notification 等 executeHooksOutsideREPL
        //   事件调用方不传 getAppState → appState undefined → 排除。
        if (isSessionHookEligible(event)) {
            List<GenericHook.HookResult> sessionResults = executeSessionHooks(event, messages);
            for (GenericHook.HookResult hookResult : sessionResults) {
                if (hookResult != null) {
                    allResults.add(hookResult);
                    if (log.isDebugEnabled()) {
                        log.debug("HOOK executeEventAll 收集 session function 结果: blockingError={} preventContinuation={}",
                            hookResult.blockingError() != null, hookResult.preventContinuation());
                    }
                }
            }
        }

        // 配置驱动 hook 并行分发（对齐 executeEvent :1260, CC executeHooks all(hookPromises) hooks.ts:2744）
        // [workflow v3] executeConfiguredHooks 3 参签名（parentTuc 透传）；2 参重载透传调用方父 TUC
        // [H2/CCJ-EXEC-01] messages 透传配置驱动 hook（CC executeHooks messages → execPromptHook）
        // [IMP-HOOKS-S5 D-01] defaultTimeoutMs 透传（SessionEnd 1500ms 收紧）
        // [MT-04 / OPD-WF2-MT-04] 聚合序: 位于 session function hooks 之后（CC 完成序）.
        // [IMP-A2-1 · MG-5 batchAbort 透传] 批级 abort 透传 executeConfiguredHooks → 执行器
        //   parentAbort（对齐 CC executeHooksOutsideREPL signal → createCombinedAbortSignal
        //   hooks.ts:3305-3312 → execCommandHook/execHttpHook，运行中 hook 批 abort 时被终止）。
        List<GenericHook.HookResult> configuredResults = executeConfiguredHooks(
            event, matched, parentTuc, messages, defaultTimeoutMs, null, batchAbort);
        for (GenericHook.HookResult hookResult : configuredResults) {
            if (hookResult != null) {
                allResults.add(hookResult);
                if (log.isDebugEnabled()) {
                    log.debug("HOOK executeEventAll 收集配置驱动结果: blockingError={} preventContinuation={}",
                        hookResult.blockingError() != null, hookResult.preventContinuation());
                }
            }
        }

        List<Map.Entry<String, GenericHook>> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(genericHooks.entrySet());
        }
        if (snapshot.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK executeEventAll 完成: event={} 结果数={}（无 programmatic hook）",
                    event.type(), allResults.size());
            }
            return allResults;
        }

        // programmatic hook eligible 过滤（对齐 executeEvent：事件类型）
        // [IMPL-10] DEL-L03-04: sessionScope 过滤已删除（会话作用域由 SessionHookStore 承担）。
        List<Map.Entry<String, GenericHook>> eligible = new ArrayList<>(snapshot.size());
        synchronized (this) {
            for (Map.Entry<String, GenericHook> entry : snapshot) {
                String hookName = entry.getKey();
                Set<HookEventType> filter = hookEventFilters.get(hookName);
                if (filter != null && !filter.isEmpty() && !filter.contains(event.type())) {
                    continue;
                }
                eligible.add(entry);
            }
        }

        if (eligible.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK executeEventAll 完成: event={} 结果数={}（无 eligible programmatic hook）",
                    event.type(), allResults.size());
            }
            return allResults;
        }

        // 并行提交（对齐 executeEvent :1305-1308, CC all(hookPromises) hooks.ts:2744）
        List<CompletableFuture<GenericHook.HookResult>> futures = new ArrayList<>(eligible.size());
        for (Map.Entry<String, GenericHook> entry : eligible) {
            futures.add(submitGenericHook(entry.getKey(), entry.getValue(), event));
        }
        // 并行等待全部 hook 完成（对齐 executeEvent :1309-1320）：AbortException 透传（用户中止意图不可吞）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof AbortException ae) {
                throw ae;
            }
            log.warn("HOOK 并行等待异常(非 abort): {}", cause != null ? cause.toString() : ce.toString());
        }
        // 按序收集全部非 null 结果（不折叠，对齐 CC 逐 result yield blockingError hooks.ts:2757-2762）
        for (int i = 0; i < eligible.size(); i++) {
            String hookName = eligible.get(i).getKey();
            GenericHook.HookResult result = futures.get(i).join(); // 并行已完成, join 不阻塞
            if (result != null) {
                allResults.add(result);
                if (log.isDebugEnabled()) {
                    log.debug("HOOK '{}' executeEventAll 结果: blockingError={} preventContinuation={}",
                        hookName, result.blockingError() != null, result.preventContinuation());
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("HOOK executeEventAll 完成: event={} 结果数={}", event.type(), allResults.size());
        }
        return allResults;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [R6-IMP] Stop hook 逐条累计收集 · 对齐 CC stopHooks.ts:175-333 主消费追踪
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [R6-IMP] Stop/SubagentStop hook 收集结果 · 对齐 CC stopHooks.ts:175-333 逐 result
     * 累计字段（hookCount/hookInfos/hookErrors/hasOutput/preventedContinuation/stopReason）。
     *
     * <p>{@code results} 单独保留供调用方逐 result 应用 blockingError/preventContinuation
     * 语义（CC for-await 循环体后半段），与累计字段同一次遍历产出。
     */
    public record StopHookCollectResult(
        // CC stopHooks.ts:175-333 逐 result 消费的对象；调用方据此应用 blocking/prevent 语义
        List<GenericHook.HookResult> results,
        // CC hookCount++（每 progress 消息计 1 = 每 hook 1 条，stopHooks.ts:193）
        int hookCount,
        // CC hookInfos.push({command})（stopHooks.ts:198-203）；Java 通道为 List<String> 命令清单
        List<String> hookInfos,
        // CC hookErrors.push（stopHooks.ts:209-235/244-247：non_blocking_error / error_during_execution / blockingError）
        List<String> hookErrors,
        // CC preventedContinuation = true（stopHooks.ts:254）
        boolean preventedContinuation,
        // CC stopReason = result.stopReason || 'Stop hook prevented continuation'（stopHooks.ts:255-256）
        String stopReason,
        // CC hasOutput（stopHooks.ts:208/215/222-227/243）
        boolean hasOutput
    ) {
        public StopHookCollectResult {
            results = List.copyOf(results);
            hookInfos = List.copyOf(hookInfos);
            hookErrors = List.copyOf(hookErrors);
        }
    }

    /**
     * [R6-IMP] 执行 Stop/SubagentStop 事件并逐 hook 累计消费追踪 · 对齐 CC
     * {@code handleStopHooks} 主循环 (stopHooks.ts:175-333)：
     *
     * <pre>
     *   for await (const result of generator) {        // 每 result = 每 hook
     *     result.message.type==='progress'  → hookCount++ + hookInfos.push({command})
     *     result.message.type==='attachment' (hookEvent Stop/SubagentStop):
     *       hook_non_blocking_error  → hookErrors.push(stderr || 'Exit code N') + hasOutput=true
     *       hook_error_during_execution → hookErrors.push(content) + hasOutput=true
     *       hook_success             → stdout/stderr trim 非空 → hasOutput=true
     *     result.blockingError       → hookErrors.push(blockingError) + hasOutput=true
     *     result.preventContinuation → preventedContinuation=true
     *                                  + stopReason = result.stopReason || 'Stop hook prevented continuation'
     *   }
     * </pre>
     *
     * <p><b>Java 聚合模型差异登记</b>（DEL-TH-06 恢复理由, 09 §7.5）：
     * <ul>
     *   <li>CC per-progress 消息（hookCount/hookInfos 在 executeHooks yield progress 时计数）；
     *       Java 无 per-progress 通道 → 以逐 result 等价累计（1 result = 1 hook = 1 progress）</li>
     *   <li>CC promptText（HookProgress.promptText, 仅 prompt hook）→ Java HookMessage 通道
     *       为 List&lt;String&gt;，仅承载命令清单，promptText 不可表达</li>
     *   <li>abort 早返（stopHooks.ts:265-282）在循环体内逐 result 检查；Java 批量收集后由
     *       调用方一次性检查（收集与检查之间无 yield 点，语义等价）</li>
     *   <li>exit=2 blocking 的 Java HookResult 同时置 preventContinuation=true（CommandHookExecutor
     *       既有映射）；CC 真源 exit=2 仅 yield blockingError。故 preventContinuation 累计排除
     *       blockingError 结果（{@code r.preventContinuation() && r.blockingError() == null}），
     *       避免 blocking 路径污染 summary 的 preventedContinuation 标志</li>
     * </ul>
     *
     * @param event     Stop 或 SubagentStop 事件
     * @param parentTuc 父 per-turn ToolUseContext（透传 executeEventAll → 配置驱动 hook）
     * @return 逐 hook 结果 + 累计字段；无 hook 执行 → hookCount=0 且 results 空（调用方零副作用）
     */
    /**
     * [H2/CCJ-EXEC-01] executeStopHooksCollecting + messages 透传 · CC hooks.ts:3688-3696
     *   executeStopHooks 把 messages 传给 executeHooks（Stop/SubagentStop 唯一生产传入；
     *   LlmAgentLoop :4665 调用 3 参版本）。既有 2 参调用方零改动。
     */
    public StopHookCollectResult executeStopHooksCollecting(HookEvent event,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc) {
        // [H2/CCJ-EXEC-01] 无 messages 重载（既有 2 参调用方零改动）· CC executeHooks messages 可选
        return executeStopHooksCollecting(event, parentTuc, null);
    }

    public StopHookCollectResult executeStopHooksCollecting(HookEvent event,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            List<ChatMessageDto> messages) {
        List<GenericHook.HookResult> results = executeEventAll(event, parentTuc, messages);
        int hookCount = 0;
        List<String> hookInfos = new ArrayList<>();
        List<String> hookErrors = new ArrayList<>();
        boolean preventedContinuation = false;
        String stopReason = "";
        boolean hasOutput = false;
        for (GenericHook.HookResult r : results) {
            if (r == null) {
                continue;
            }
            // CC stopHooks.ts:193 hookCount++（每 progress 消息 = 每 hook）
            hookCount++;
            // CC stopHooks.ts:198-203 hookInfos.push({command: progressData.command})
            //   command = getHookDisplayText(hook)（hooksSettings.ts:68-90: statusMessage ?? command/prompt/url）
            String command = stopHookCommandOf(r.hook());
            if (command != null && !command.isBlank()) {
                hookInfos.add(command);
            }
            // CC stopHooks.ts:203-234 attachment 分类（仅 hookEvent Stop/SubagentStop）
            if (r.message() instanceof AttachmentMessageDto att
                && ("Stop".equals(att.hookEvent()) || "SubagentStop".equals(att.hookEvent()))) {
                switch (att.type()) {
                    case "hook_non_blocking_error" -> {
                        // CC stopHooks.ts:207-212: stderr || `Exit code ${exitCode}` + hasOutput=true
                        String stderr = att.stderr() != null ? att.stderr() : "";
                        hookErrors.add(stderr.isEmpty()
                            ? "Exit code " + (att.exitCode() != null ? att.exitCode() : 1) : stderr);
                        hasOutput = true;
                    }
                    case "hook_error_during_execution" -> {
                        // CC stopHooks.ts:213-216: content + hasOutput=true
                        hookErrors.add(att.content() != null ? att.content() : "");
                        hasOutput = true;
                    }
                    case "hook_success" -> {
                        // CC stopHooks.ts:217-228: stdout/stderr trim 非空 → hasOutput=true
                        if ((att.stdout() != null && !att.stdout().trim().isEmpty())
                            || (att.stderr() != null && !att.stderr().trim().isEmpty())) {
                            hasOutput = true;
                        }
                    }
                    default -> {
                        // 其他 attachment 类型（hook_blocking_error / hook_user_message 等）不参与累计
                    }
                }
            }
            // CC stopHooks.ts:240-247: blockingError → hookErrors.push + hasOutput=true
            if (r.blockingError() != null) {
                hookErrors.add(r.blockingError().blockingError());
                hasOutput = true;
            }
            // CC stopHooks.ts:252-256: preventContinuation → preventedContinuation=true
            //   + stopReason = result.stopReason || 'Stop hook prevented continuation'
            //   [Java 差异] 排除 blockingError 结果（Java exit=2 映射双置，见类注释）
            if (r.preventContinuation() && r.blockingError() == null) {
                preventedContinuation = true;
                stopReason = r.stopReason() != null && !r.stopReason().isBlank()
                    ? r.stopReason() : "Stop hook prevented continuation";
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("HOOK executeStopHooksCollecting 完成: event={} hookCount={} hookErrors={} prevented={}",
                event.type(), hookCount, hookErrors.size(), preventedContinuation);
        }
        // [IMP-HOOKS-S5 D-15] totalDurationMs 已删除（CC stopHooks.ts 无 per-batch 耗时通道；
        //   per-hook durationMs 在 hook_success/hook_non_blocking_error attachment 上承载）
        return new StopHookCollectResult(results, hookCount, hookInfos, hookErrors,
            preventedContinuation, stopReason, hasOutput);
    }

    /**
     * [R6-IMP] Stop summary 命令清单提取 · CC getHookDisplayText 等价
     * (hooksSettings.ts:68-90)：statusMessage 优先，缺省 command/prompt/url。
     */
    private static String stopHookCommandOf(HookCommand hook) {
        if (hook == null) {
            return null;
        }
        if (hook.statusMessage() != null && !hook.statusMessage().isBlank()) {
            return hook.statusMessage();
        }
        return switch (hook.hookType()) {
            case COMMAND -> ((CommandHook) hook).command();
            case PROMPT -> ((PromptHook) hook).prompt();
            case AGENT -> ((AgentHook) hook).prompt();
            case HTTP -> ((HttpHook) hook).url();
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // [H4] 配置驱动 hook 并行分发 · 对齐 CC executeHooks (hooks.ts:2142-2744)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [H4] 配置驱动 hook 并行执行入口 · 对齐 CC executeHooks (hooks.ts:2142-2744).
     *
     * <p>WHY (规则三: CC 怎么复杂, 我们怎么复杂): CC executeHooks 把所有 matched hooks
     * {@code matchingHooks.map(...)} 成独立 generator (hooks.ts:2143), 每个按 type 分支到
     * 独立 executor (callback/function/prompt/agent/http/command), 最后
     * {@code for await (const result of all(hookPromises))} (hooks.ts:2744) 并行等待全部完成.
     * Java 端用 {@link CompletableFuture#allOf} 等价: 每 hook 一个 supplyAsync (复用
     * {@link #HOOK_EXECUTOR}), {@code allOf().join()} 等待全部, 单 hook 异常在 future 内
     * try-catch 隔离 (对齐 CC :2698-2729 catch → non_blocking_error, 不阻断其他 hook).
     *
     * <p><b>AbortException 透传</b>: 用户中止意图不可吞 (对齐 CC hooks.ts:2045-2051),
     * 由 {@link #executeOneConfiguredHook} rethrow, 本方法从 {@link CompletionException}
     * 解包后再次 rethrow.
     * <p><b>AbortException 透传</b>: 用户中止意图不可吞 (对齐 CC errors.ts:12-17 AbortError +
     * hooks.ts:4812-4818 outcome 'cancelled'),
     * 由 {@link #executeOneConfiguredHook} rethrow, 本方法从 {@link CompletionException}
     * 解包后再次 rethrow.
     * @param event   hook 事件
     * @param matched 匹配的配置驱动 hook 列表 (可能为空)
     * @return 各 hook 的 HookResult (顺序 = matched 顺序; 未接线/异常的 hook 返回 proceed 兜底)
     */
    private List<GenericHook.HookResult> executeConfiguredHooks(HookEvent event, List<MatchedHook> matched,
                                                                com.nexusai.application.agent.tool.ToolUseContext parentTuc) {
        // [H2/CCJ-EXEC-01] 无 messages 重载（既有 3 参调用方零改动）· CC executeHooks messages 可选
        return executeConfiguredHooks(event, matched, parentTuc, null);
    }

    /**
     * [H2/CCJ-EXEC-01] 配置驱动 hook 并行分发 + messages 透传 · CC executeHooks
     * （hooks.ts:1959 messages → execPromptHook 第 7 参，Stop/SubagentStop 唯一生产传入）。
     */
    private List<GenericHook.HookResult> executeConfiguredHooks(HookEvent event, List<MatchedHook> matched,
                                                                com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                                List<ChatMessageDto> messages) {
        // [IMP-HOOKS-S5 D-01] 缺省超时由 5 参重载承载（SessionEnd 1500ms 收紧）· 既有调用方 10min
        // [IMP-RS-01 DEL-01e 补回] promptRequester=null (默认无通道)
        return executeConfiguredHooks(event, matched, parentTuc, messages,
            CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, null);
    }

    /**
     * [IMP-HOOKS-S5 D-01] 配置驱动 hook 并行分发 + messages 透传 + 缺省超时 ·
     * CC executeHooks command 分支 (hooks.ts:3280 {@code hook.timeout ? hook.timeout*1000 : timeoutMs})
     * 的缺省超时由调用方注入（SessionEnd 传 1500ms）。[IMP-RS-01 DEL-01e 补回] promptRequester 透传。
     */
    private List<GenericHook.HookResult> executeConfiguredHooks(HookEvent event, List<MatchedHook> matched,
                                                                com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                                List<ChatMessageDto> messages,
                                                                long defaultTimeoutMs) {
        return executeConfiguredHooks(event, matched, parentTuc, messages, defaultTimeoutMs, null);
    }

    /**
     * [IMP-RS-01 DEL-01e 补回] 配置驱动 hook 并行分发 + messages + 缺省超时 + prompt 回调通道 ·
     * promptRequester 透传给 command hook 执行 (executeConfiguredCommand → CommandHookExecutor.execute
     * 13 参), 对齐 CC executeHooks requestPrompt → execCommandHook (hooks.ts:1972/2460)。
     */
    private List<GenericHook.HookResult> executeConfiguredHooks(HookEvent event, List<MatchedHook> matched,
                                                                com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                                List<ChatMessageDto> messages,
                                                                long defaultTimeoutMs,
                                                                PromptRequester promptRequester) {
        // [IMP-A2-1 · MG-5 batchAbort 透传] 无批级 abort 重载（既有调用方零改动）·
        //   批级 abort 由 7 参重载承载（executeEventAll 5 参透传 compact/SessionEnd 的批信号）。
        return executeConfiguredHooks(event, matched, parentTuc, messages, defaultTimeoutMs,
            promptRequester, null);
    }

    /**
     * [IMP-A2-1 · MG-5 batchAbort 透传] 配置驱动 hook 并行分发 + messages + 缺省超时 +
     * prompt 回调通道 + 批级 abort 信号 · 对齐 CC executeHooksOutsideREPL（hooks.ts:3003-3380）：
     * {@code signal} 经 {@code createCombinedAbortSignal} 合并到 command hook 的 abortSignal
     * （hooks.ts:3305-3312），运行中 hook 在批级 abort 时被终止。
     *
     * <p>入口第二道早退同步检查批级 abort（与 executeEventAll 入口检查幂等，防绕过新路径漏检）；
     * 批信号透传 {@link #executeOneConfiguredHook} → CommandHookExecutor/ExecHttpHook 的
     * parentAbort（运行中 command 子进程经 onCancel → destroyForcibly SIGKILL，
     * 对齐 CC wrapSpawn abort → treeKill SIGKILL ShellCommand.ts:186-193/:345-347）。
     */
    private List<GenericHook.HookResult> executeConfiguredHooks(HookEvent event, List<MatchedHook> matched,
                                                                com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                                List<ChatMessageDto> messages,
                                                                long defaultTimeoutMs,
                                                                PromptRequester promptRequester,
                                                                com.nexusai.application.agent.tool.AbortController batchAbort) {
        if (matched == null || matched.isEmpty()) {
            return List.of();
        }

        // [fix-ts04 IMPL-02 / OD-TS04-03] 第二道批级 signal 早退 · 对齐 CC executeHooks
        //   (hooks.ts:2015-2017) — 空检查后、序列化前 (enrich/buildJsonInput 零副作用前)
        //   短路, 防未来绕过 executeEvent/executeEventAll 入口的新调用路径漏检.
        //   与入口级检查幂等 (同条件同结果 List.of()).
        // [IMP-A2-1 · MG-5] 批级 abort 同条件早退（对齐 CC executeHooksOutsideREPL
        //   hooks.ts:3051-3053 {@code if (signal?.aborted) return []}）。
        com.nexusai.application.agent.tool.AbortController parentAbort =
            parentTuc != null ? parentTuc.abortController() : null;
        if (parentAbort != null && parentAbort.isCancelled()
            || batchAbort != null && batchAbort.isCancelled()) {
            if (log.isDebugEnabled()) {
                log.debug("executeConfiguredHooks: 父/批级 abort 已取消, 整批跳过 (事件 {})", event.type());
            }
            return List.of();
        }
        // [fix-ts04 IMPL-01 OD-TS04-01 方案 B] 批级 base 字段合并 · 对齐 CC createBaseHookInput
        //   恒备字段单点计算 (hooks.ts:301-328)。enrich 在序列化前完成, 全批共享同一副本:
        //   session_id 回退 (RequestContext MDC) / transcript_path / cwd / permission_mode /
        //   agent_id / agent_type(ctx) 单值合并 (REQ-06, 无双轨)。副本同步传给
        //   executeOneConfiguredHook → executeConfiguredAgent (event.transcriptPath() 消费,
        //   ExecAgentHook transcript 读取能力, PROBE-01 关键事实 4)。
        HookEvent enriched = CommandHookExecutor.enrichBaseFields(event, parentTuc);
        // Lazy-once stringify of hookInput · 对齐 CC getJsonInput() (hooks.ts:2124-2140),
        // 全部 command/prompt/agent/http hook 共享同一 jsonInput.
        String jsonInput = CommandHookExecutor.buildJsonInput(enriched);
        // [MT-02 / OPD-WF2-MT-02] 执行链 reconciliation：registered/插件源 MatchedHook
        //   （getMatchingHooks 统一单链返回，含 plugin context）不在此并行执行 —— 插件
        //   native hooks 执行经 PluginLoader.buildPluginGenericHook → genericHooks 链
        //   （对齐 CC 插件 hook 由 executeHooks 执行，Java 端保留既有执行链），避免
        //   插件 hook 双发（matched 链 + genericHooks 链各一次）。
        java.util.List<MatchedHook> execMatched = new ArrayList<>(matched.size());
        for (MatchedHook mh : matched) {
            if (mh.pluginId() != null || mh.pluginRoot() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("executeConfiguredHooks: 跳过插件源 matched hook {} (执行走 genericHooks 链, 防双发)",
                        stopHookCommandOf(mh.hook()));
                }
                continue;
            }
            execMatched.add(mh);
        }
        if (execMatched.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<GenericHook.HookResult>> futures = new ArrayList<>(execMatched.size());
        // [S4 G14] 完成序收集 · 对齐 CC all(hookPromises) (generators.ts:56-71) 逐完成序消费
        //   (hooks.ts:2744): 每 future 任务体完成时入队 (index, result) ConcurrentLinkedQueue,
        //   allOf().join() 后按队序 drain — 结果列表/onHookSuccess 随完成序 (旧注册序
        //   futures.get(i).join() 收集已删). [风险登记] 同步完成的 hook (绝大多数)
        //   完成序==注册序, 仅真实并发 hook 顺序翻转 (CC 同语义).
        java.util.Queue<IndexedHookResult> completed = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 0; i < execMatched.size(); i++) {
            MatchedHook mh = execMatched.get(i);
            // [ALIGN-HOOKS-2] hookIndex = 事件匹配列表位置 · 对齐 CC :3084-3085
            //   matchingHooks.map(index) → :3293 execCommandHook → :925 CLAUDE_ENV_FILE
            //   (session 环境脚本路径). 覆盖 settings + session 全来源 (getMatchingHooks
            //   合并序 == CC getHooksConfig 合并序), 恒唯一.
            int hookIndex = i;
            futures.add(CompletableFuture.supplyAsync(withSessionProjectRoot(() -> {
                // [IMP-A2-1 · MG-5] 批级 abort 透传 executeOneConfiguredHook → 执行器 parentAbort
                GenericHook.HookResult r = executeOneConfiguredHook(enriched, mh, jsonInput,
                    parentTuc, hookIndex, messages, defaultTimeoutMs, promptRequester, batchAbort);
                completed.add(new IndexedHookResult(hookIndex, r));
                return r;
            }), HOOK_EXECUTOR));
        }
        // 并行等待全部完成 · 对齐 CC all(hookPromises) (hooks.ts:2744).
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
        } catch (CompletionException ce) {
            // 仅 AbortException 会透传 (用户中止意图不可吞); 其他异常已在 future 内隔离 → 兜底记录.
            Throwable cause = ce.getCause();
            if (cause instanceof AbortException ae) {
                throw ae;
            }
            log.warn("配置驱动 hooks 并行等待异常(非 abort): {}",
                cause != null ? cause.toString() : ce.toString());
        }
        List<GenericHook.HookResult> results = new ArrayList<>(futures.size());
        // [IMPL-07 OD-11] session hooks 并入统一链后, onHookSuccess 回调随统一执行链触发
        //   (对齐 CC hooks.ts:2906-2925: executeHooks 内对所有 command/prompt/function hook
        //   结果查 getSessionHookCallback + onHookSuccess). [S4 G14] 按完成序 drain,
        //   onHookSuccess 随完成序调用 (CC :2907-2928 逐完成序消费). 对齐 CC :2907-2911
        //   （CC 无 hookSource 门, 对每个 command/prompt/function hook 查 SessionHookStore）:
        //   hookSource="settings"（CC :1694-1702 三元默认值, session 派生无 plugin/skill）过门后,
        //   session hook 命中 store 回调; settings 文件 hook 查询为空 → no-op.
        for (IndexedHookResult ir : completed) {
            results.add(ir.result);
            MatchedHook mh = execMatched.get(ir.index);
            if (ir.result != null && "settings".equals(mh.hookSource())) {
                String sessionId = enriched.sessionId();
                HookMatcherEngine engine = this.hookMatcherEngine;
                String matchQuery = engine != null ? engine.matchQueryFor(enriched) : null;
                // [3-1 拆开] MatchedHook.hook 收窄为 HookCommand（X-WF2-01 判定可观察等价）,
                // 其具体实现均 implements SessionHook → 收窄为 union 载体 (CC: HookCommand | FunctionHook)。
                invokeSessionHookOnSuccess(sessionId, enriched, matchQuery, (SessionHook) mh.hook(), ir.result);
            }
        }
        return results;
    }

    /** [S4 G14] 完成序收集载体 · index = 注册序位置 (hookIndex/onHookSuccess 配对用). */
    private record IndexedHookResult(int index, GenericHook.HookResult result) {
    }

    /** [S4 G14] programmatic 完成序收集载体 · index = snapshot 注册序位置. */
    private record IndexedAhr(int index, AggregatedHookResult result) {
    }

    /**
     * [H4] 单个配置驱动 hook 执行 · 按 hookType 分发到 4 类型 executor.
     *
     * <p>WHY (规则三): CC executeHooks 内部按 {@code hook.type} 显式 if-else 链分发
     * (hooks.ts:2147 callback / :2165 function / :2224 prompt / :2256 agent / :2296 http /
     * :2448 command). Java 端 sealed interface {@link HookCommand.HookType} 保证 4 类型穷尽,
     * 显式 switch 对齐. 单 hook 异常 catch (对齐 CC :2698-2729 → non_blocking_error) 返回
     * proceed, 不阻断同批其他 hook.
     */
    private GenericHook.HookResult executeOneConfiguredHook(HookEvent event, MatchedHook mh, String jsonInput,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            int hookIndex) {
        return executeOneConfiguredHook(event, mh, jsonInput, parentTuc, hookIndex, null);
    }

    /**
     * [H2/CCJ-EXEC-01] 单个配置驱动 hook 执行 + messages 透传（prompt 分支 → execPromptHook 第 7 参）。
     */
    private GenericHook.HookResult executeOneConfiguredHook(HookEvent event, MatchedHook mh, String jsonInput,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            int hookIndex,
                                                            List<ChatMessageDto> messages) {
        // [IMP-HOOKS-S5 D-01] 缺省超时由 8 参重载承载（SessionEnd 1500ms 收紧）· 既有调用方 10min
        return executeOneConfiguredHook(event, mh, jsonInput, parentTuc, hookIndex, messages,
            CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS);
    }

    /**
     * [IMP-HOOKS-S5 D-01] 单个配置驱动 hook 执行 + messages 透传 + 缺省超时 ·
     * CC executeHooks command 分支缺省超时（hooks.ts:3280）。
     */
    private GenericHook.HookResult executeOneConfiguredHook(HookEvent event, MatchedHook mh, String jsonInput,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            int hookIndex,
                                                            List<ChatMessageDto> messages,
                                                            long defaultTimeoutMs) {
        // [IMP-RS-01 DEL-01e 补回] promptRequester=null (默认无通道)
        return executeOneConfiguredHook(event, mh, jsonInput, parentTuc, hookIndex, messages,
            defaultTimeoutMs, null);
    }

    /**
     * [IMP-RS-01 DEL-01e 补回] 单个配置驱动 hook 执行 + messages + 缺省超时 + prompt 回调通道 ·
     * promptRequester 只传给 command hook 分支 (executeConfiguredCommand → CommandHookExecutor),
     * 对齐 CC executeHooks command 分支 (hooks.ts:2448-2461 execCommandHook 第 12 参 requestPrompt)。
     */
    private GenericHook.HookResult executeOneConfiguredHook(HookEvent event, MatchedHook mh, String jsonInput,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            int hookIndex,
                                                            List<ChatMessageDto> messages,
                                                            long defaultTimeoutMs,
                                                            PromptRequester promptRequester) {
        // [IMP-A2-1 · MG-5 batchAbort 透传] 无批级 abort 重载（既有调用方零改动）·
        //   批级 abort 由 9 参重载承载。
        return executeOneConfiguredHook(event, mh, jsonInput, parentTuc, hookIndex, messages,
            defaultTimeoutMs, promptRequester, null);
    }

    /**
     * [IMP-A2-1 · MG-5 batchAbort 透传] 单个配置驱动 hook 执行 + messages + 缺省超时 +
     * prompt 回调通道 + 批级 abort 信号 · 对齐 CC executeHooksOutsideREPL 各 hook 分支
     * （command hooks.ts:3305-3312 / http :2299-2301）：批信号透传给执行器 parentAbort。
     *
     * <p>prompt/agent 分支不接收批信号 —— CC executeHooksOutsideREPL 对 prompt/agent 直接
     * 返回 "not yet supported outside REPL"（hooks.ts:3152-3170），Java 经 isOutsideReplEvent
     * 门禁同等早返，不进入执行器。
     */
    private GenericHook.HookResult executeOneConfiguredHook(HookEvent event, MatchedHook mh, String jsonInput,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            int hookIndex,
                                                            List<ChatMessageDto> messages,
                                                            long defaultTimeoutMs,
                                                            PromptRequester promptRequester,
                                                            com.nexusai.application.agent.tool.AbortController batchAbort) {
        HookCommand hook = mh.hook();
        // [S4 G07] outside-REPL 事件 prompt/agent 拒绝 · 对齐 CC executeHooksOutsideREPL
        //   (hooks.ts:3152-3170): prompt/agent stop hooks 在 outside-REPL 事件下直接返回
        //   succeeded:false ('...not yet supported outside REPL') 不执行 — Java 等价:
        //   outcome=NON_BLOCKING_ERROR + message=null (无 attachment), 不产模型调用.
        //   事件分类见 {@link #isOutsideReplEvent(HookEventType)} (13 事件; Stop/SubagentStop/
        //   SessionStart/Setup/TaskCreated/TaskCompleted/UserPromptSubmit/SubagentStart 属 REPL
        //   允许执行, 禁止按 executeEventAll 入口一刀切).
        if ((hook instanceof PromptHook || hook instanceof AgentHook) && isOutsideReplEvent(event.type())) {
            if (log.isWarnEnabled()) {
                log.warn("HOOK prompt/agent hook '{}' 在 {} 事件下不执行: not yet supported outside REPL (对齐 CC :3152-3170)",
                    hookNameFor(mh), event.type());
            }
            return new GenericHook.HookResult(false, null, null, null, null, null, null, null, null,
                GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
        }
        try {
            // CC executeHooks 显式 if-else 链 (hooks.ts:2147 callback / :2224 prompt /
            // :2256 agent / :2296 http / :2448 command) · Java instanceof pattern matching 等价.
            if (hook instanceof CommandHook commandHook) {
                // [EX-HOOK R5] parentTuc 透传 · 对齐 prompt/agent/http 分支既有模式
                //   (IMPL-06 D5-1/OD-EX-02)：command 分支执行前检查父 abort（CC executeHooks
                //   入口 signal.aborted 早返 hooks.ts:2015-2017）。
                // [IMP-RS-01 DEL-01e 补回] promptRequester 透传 command 分支
                // [IMP-A2-1 · MG-5] 批级 abort 透传 executeConfiguredCommand → CommandHookExecutor.parentAbort
                //   （对齐 CC execCommandHook abortSignal hooks.ts:2453 → wrapSpawn 杀子进程）
                return executeConfiguredCommand(event, mh, commandHook, jsonInput, parentTuc, hookIndex,
                    defaultTimeoutMs, promptRequester, batchAbort);
            }
            if (hook instanceof PromptHook promptHook) {
                return executeConfiguredPrompt(event, mh, promptHook, jsonInput, parentTuc, messages);
            }
            if (hook instanceof AgentHook agentHook) {
                return executeConfiguredAgent(event, mh, agentHook, jsonInput, parentTuc);
            }
            if (hook instanceof HttpHook httpHook) {
                // [IMP-A2-1 · MG-5] 批级 abort 透传 executeConfiguredHttp → ExecHttpHook.parentAbort
                //   （对齐 CC executeHooksOutsideREPL http 分支传 signal hooks.ts:2299-2301）
                return executeConfiguredHttp(event, mh, httpHook, jsonInput, parentTuc, batchAbort);
            }
            // 未知类型 → 跳过不抛 (sealed interface 保证 4 类型穷尽, 此分支为防御性兜底)
            if (log.isDebugEnabled()) {
                log.debug("配置驱动 hook 未知类型, 跳过: type={}", hook.hookType());
            }
            return GenericHook.HookResult.proceed();
        } catch (AbortException ae) {
            log.warn("配置驱动 hook '{}' abort: {}", hookNameFor(mh), ae.getMessage());
            throw ae; // 用户中止 → 透传 (hooks.ts:2045-2051)
        } catch (Exception e) {
            // [S4] CC runHook catch (hooks.ts:2698-2729) → non_blocking_error +
            //   hook_non_blocking_error attachment (`Failed to run: ...`, stdout:'', exitCode=1,
            //   command=getHookDisplayText(hook) hooks.ts:2201). 旧 proceed 静默吞错已删 —
            //   用户可观测 executor 失败原因.
            if (log.isWarnEnabled()) {
                log.warn("配置驱动 hook '{}' 执行失败, 视为 non_blocking_error: {}", hookNameFor(mh), e.toString());
            }
            return new GenericHook.HookResult(false, null, null, null,
                AttachmentMessageDto.hookNonBlockingError(
                    hookNameFor(mh), event.toolUseId(), event.type().ccName(),
                    "Failed to run: " + e.getMessage(), "", 1,
                    stopHookCommandOf(mh.hook()), 0L),
                null, null, null, null,
                GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * [S4 G07] outside-REPL 事件分类 · 对齐 CC 事件→入口映射 (E-HOOKS-T1-20/50 自验):
     * <ul>
     *   <li>outside-REPL (executeHooksOutsideREPL, 13 事件): SessionEnd / StopFailure /
     *       Notification / ConfigChange / CwdChanged / FileChanged / PreCompact / PostCompact /
     *       InstructionsLoaded / Elicitation / ElicitationResult / WorktreeCreate / WorktreeRemove
     *       (hooks.ts:4119/:4226/:3587/:4225/:4249/:3979/:4051/:4364/:4502/:4554/:4937/:4984)</li>
     *   <li>REPL (executeHooks, prompt/agent 允许): PreToolUse / PostToolUse /
     *       PostToolUseFailure / PermissionDenied / Stop / SubagentStop / SessionStart / Setup /
     *       SubagentStart / TaskCreated / TaskCompleted / UserPromptSubmit
     *       (hooks.ts:3426/:3469/:3519/:3554/:3688/:3884/:3914/:3944/:3766/:3810/:3847)</li>
     * </ul>
     * 工具事件 5 类型不在此列表 (走 executePreToolUse 链, 不经过本门禁).
     */
    private static boolean isOutsideReplEvent(HookEventType type) {
        return switch (type) {
            case SESSION_END, STOP_FAILURE, NOTIFICATION, CONFIG_CHANGE, CWD_CHANGED,
                FILE_CHANGED, PRE_COMPACT, POST_COMPACT, INSTRUCTIONS_LOADED,
                ELICITATION, ELICITATION_RESULT, WORKTREE_CREATE, WORKTREE_REMOVE -> true;
            default -> false;
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-HOOKS-S5 D-01/D-02] SessionEnd hooks · 对齐 CC executeSessionEndHooks
    //   (hooks.ts:4097-4141) + gracefulShutdown.ts:409-480
    // ════════════════════════════════════════════════════════════════════════

    /** [IMP-HOOKS-S5 D-01] SessionEnd hook 超时缺省 1500ms · 对齐 CC hooks.ts:175
     *  {@code SESSION_END_HOOK_TIMEOUT_MS_DEFAULT = 1500}。 */
    private static final long SESSION_END_HOOK_TIMEOUT_MS_DEFAULT = 1500L;

    public static long getSessionEndHookTimeoutMs() {
        return parseSessionEndHookTimeoutMs(System.getenv("CLAUDE_CODE_SESSIONEND_HOOKS_TIMEOUT_MS"));
    }

    /**
     * [IMP-HOOKS-S5 D-01] 解析原始 env 值 → 超时毫秒 · 测试可注入（JDK 25 模块封装
     * 禁止测试反射改 env，解析语义抽为纯函数）。
     *
     * @param raw env 原值（null = 缺失）
     * @return 合法正整数 → 原值；缺失/非法/≤0 → 1500
     */
    static long parseSessionEndHookTimeoutMs(String raw) {
        long parsed = -1;
        if (raw != null && !raw.isBlank()) {
            try {
                parsed = Long.parseLong(raw.trim());
            } catch (NumberFormatException nfe) {
                parsed = -1;
            }
        }
        return parsed > 0 ? parsed : SESSION_END_HOOK_TIMEOUT_MS_DEFAULT;
    }

    /**
     * [IMP-HOOKS-S5 D-01] 执行 SessionEnd hooks · 对齐 CC executeSessionEndHooks
     * （hooks.ts:4097-4141）：
     * <ol>
     *   <li>超时预算 = {@link #getSessionEndHookTimeoutMs()}（默认 1500ms）</li>
     *   <li>新建 AbortController + daemon 调度器到点 abort —— 整体 cap，等价 CC
     *       gracefulShutdown.ts:475 {@code signal: AbortSignal.timeout(sessionEndTimeoutMs)}；
     *       abort → 运行中 command hook 经 onCancel → destroyForcibly（CC createCombinedAbortSignal）</li>
     *   <li>per-hook 缺省超时 = sessionEndTimeoutMs（CC :3280 {@code hook.timeout ?
     *       hook.timeout*1000 : timeoutMs}，executeSessionEndHooks 传 timeoutMs=1500）；
     *       显式 hook.timeout 配置仍优先</li>
     *   <li>失败结果逐个 log.error（CC :4127-4134 写 stderr
     *       {@code 'SessionEnd hook [command] failed: output'}；Java 服务端无 stderr 通道 → log）</li>
     *   <li>执行后 {@link #clearSessionHooks(String)}（CC :4136-4140 setAppState 时清理）</li>
     * </ol>
     *
     * <p>差异登记：CC failsafe {@code max(5000, t+3500)}（gracefulShutdown.ts:417-426）为
     * 进程级退出保证，Java 服务端无进程退出语义 → N/A（整体 cap 已由批级 abort 表达）。
     *
     * @param sessionId 会话 ID（载荷 + clearSessionHooks key）
     * @param agentId   agent ID（载荷；子代理结束场景）
     * @param reason    CC ExitReason（matcher matchQuery）
     * @param agentType agent_type（CC BaseHookInput，coreSchemas.ts:393）
     */
    public void executeSessionEndHooks(String sessionId, String agentId, ExitReasons reason, String agentType) {
        long sessionEndTimeoutMs = getSessionEndHookTimeoutMs();
        HookEvent endEvent = HookEvent.sessionEnd(sessionId, agentId, reason, agentType);
        com.nexusai.application.agent.tool.AbortController batchAbort =
            new com.nexusai.application.agent.tool.AbortController();
        // daemon 调度器 · 到点 abort 整批（等价 CC AbortSignal.timeout；daemon + interrupt 清理，
        // 不持有非 daemon 线程句柄）
        Thread timeoutThread = new Thread(() -> {
            try {
                Thread.sleep(sessionEndTimeoutMs);
                batchAbort.abort("SessionEnd hook timeout");
            } catch (InterruptedException ie) {
                // hook 提前完成 → 调度器被 interrupt 清理，不 abort
            }
        }, "session-end-hook-timeout");
        timeoutThread.setDaemon(true);
        timeoutThread.start();
        try {
            if (log.isDebugEnabled()) {
                log.debug("HOOK executeSessionEndHooks 开始: sessionId={} reason={} timeoutMs={}",
                    sessionId, reason, sessionEndTimeoutMs);
            }
            // D-02: 不注入任何 message（CC executeSessionEndHooks 无 message 注入，仅失败写
            //   stderr hooks.ts:4127-4134；旧 injectHookResultMessage 是 Java 误读，见删除清单 D-02）
            java.util.List<GenericHook.HookResult> results =
                executeEventAll(endEvent, null, null, sessionEndTimeoutMs, batchAbort);
            for (GenericHook.HookResult r : results) {
                if (r == null || r.outcome() == GenericHook.HookOutcome.SUCCESS) {
                    continue;
                }
                String command = r.hook() != null ? stopHookCommandOf(r.hook()) : "hook";
                String output = failureOutputOf(r);
                if (output == null || output.isEmpty()) {
                    continue;
                }
                // CC :4130-4132 process.stderr.write(`SessionEnd hook [${command}] failed: ${output}`)
                log.error("SessionEnd hook [{}] failed: {}", command, output);
            }
        } catch (AbortException ae) {
            // 超时 cap abort → 等价 CC gracefulShutdown.ts:478-480 catch 忽略
            if (log.isDebugEnabled()) {
                log.debug("HOOK SessionEnd 被批级超时 cap 中止 ({}ms), 忽略: {}",
                    sessionEndTimeoutMs, ae.getMessage());
            }
        } catch (Exception e) {
            log.warn("HOOK SessionEnd failed: {}", e.getMessage());
        } finally {
            timeoutThread.interrupt();
            // CC :4136-4140 setAppState 时 clearSessionHooks
            if (sessionId != null && !sessionId.isBlank()) {
                clearSessionHooks(sessionId);
            }
            if (log.isDebugEnabled()) {
                log.debug("HOOK executeSessionEndHooks 完成: sessionId={} (clearSessionHooks 已执行)",
                    sessionId);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-HOOKS-S5 D-04] ConfigChange hooks · 对齐 CC executeConfigChangeHooks
    //   (hooks.ts:4214-4239) + hasBlockingResult (hooks.ts:2983-2985)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [IMP-HOOKS-S5 D-04] 执行 ConfigChange hooks · 对齐 CC hooks.ts:4214-4239
     * {@code executeConfigChangeHooks(source, filePath)}。
     *
     * <p>流程: 构造 ConfigChange 事件（data.source + data.file_path）→ matchQuery=source
     * （HookMatcherEngine CONFIG_CHANGE 分支 dataStr("source")）→ {@link #executeEventAll}
     * （executeHooksOutsideREPL 等价入口）。CC 注释 :4206-4208：policy settings 为企业管控，
     * hooks 照发（审计日志）但阻断结果被忽略（:4234-4236 blocked=false）—— Java 端阻断判定
     * 收敛到 {@link #hasBlockingConfigChangeResult(String, List)}（policy_settings 恒 false）。
     *
     * @param source   CC ConfigChangeSource 5 值（user_settings/project_settings/
     *                 local_settings/policy_settings/skills，hooks.ts:4194-4199）
     * @param filePath 变更文件路径（CC hookInput.file_path；可为 null）
     * @return 全部 hook 结果（不折叠；policy_settings 时结果原样返回，阻断判定由调用方按源跳过）
     */
    public java.util.List<GenericHook.HookResult> executeConfigChangeHooks(String source, String filePath) {
        HookEvent event = HookEvent.configChange(source, filePath, null);
        if (log.isDebugEnabled()) {
            log.debug("HOOK executeConfigChangeHooks: source={} filePath={}", source, filePath);
        }
        return executeEventAll(event);
    }

    /**
     * [IMP-HOOKS-S5 D-04] ConfigChange 结果阻断判定 · 对齐 CC hooks.ts:2983-2985
     * {@code hasBlockingResult(results) = results.some(r => r.blocked)}。
     *
     * <p>CC HookOutsideReplResult.blocked = exit 2 或 JSON decision:'block'（:3261）→ Java
     * 等价 = {@code preventContinuation() || blockingError() != null}（exit 2 双置映射）。
     *
     * <p><b>policy_settings 恒不阻断</b>（CC :4234-4236 blocked=false 映射）：Java HookResult
     * 无 blocked 字段，以"按源跳过判定"表达——source=policy_settings 直接返回 false。
     *
     * @param source  CC ConfigChangeSource（null/unknown 按非 policy 处理）
     * @param results executeConfigChangeHooks 返回结果（null → false）
     * @return true = 存在阻断结果且非 policy 源（调用方应跳过 reload/fanOut）
     */
    public boolean hasBlockingConfigChangeResult(String source, java.util.List<GenericHook.HookResult> results) {
        if ("policy_settings".equals(source)) {
            return false;
        }
        if (results == null) {
            return false;
        }
        for (GenericHook.HookResult r : results) {
            if (r != null && (r.preventContinuation() || r.blockingError() != null)) {
                return true;
            }
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-LC-02 OPD-WF4-LC-02] Notification hooks 通用发射点 · 对齐 CC
    //   executeNotificationHooks (hooks.ts:3570-3592)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [IMP-LC-02 OPD-WF4-LC-02] Notification hook 通用发射点 · 对齐 CC
     * {@code executeNotificationHooks}（utils/hooks.ts:3570-3592）。
     *
     * <p>WHY（探查 ✗-4，EV-WF4-LC-004）：CC Notification hook 由<b>多源</b>触发
     * {@code executeNotificationHooks} —— services/notifier.ts:25 {@code sendNotification}
     * （通用通知）、cli/print.ts:1366（elicitation_complete）、services/mcp/elicitationHandler.ts
     * （elicitation_response/elicitation_complete）。Java 旧实现仅
     * {@link com.nexusai.application.agent.mcp.ElicitationHandler} 一处内联发射
     * （ElicitationHandler.java:438-450 私有 fireNotification），非 elicitation 通知
     * （后台任务完成、异步 hook exit=2 等）无发射点 → 配置 Notification hook 永不触发。
     * 本方法暴露通用发射点：任何生产者均可调用，触发 Notification hook
     * （HookMatcherEngine 按 notification_type 匹配，HookMatcherEngine.java:332）。
     *
     * <p>对齐语义（hooks.ts:3570-3592 + executeHooksOutsideREPL :3003-3049）：
     * <ul>
     *   <li>hook_event_name='Notification' + message + title + notification_type 载荷</li>
     *   <li>matchQuery = notificationType（CC :3590；HookMatcherEngine NOTIFICATION 分支
     *       {@code dataStr("notification_type")}）</li>
     *   <li>executeHooksOutsideREPL 等价入口 = {@link #executeEvent}：disableAllHooks /
     *       CLAUDE_CODE_SIMPLE / trust 门控由 executeEvent 入口统一处理（hooks.ts:3016-3036）</li>
     *   <li>Notification ∉ {@link #CC_APP_STATE_PRESENT_EVENTS} → session hooks 排除
     *       （对齐 CC executeNotificationHooks 不传 getAppState → appState undefined，
     *       hooks.ts:3003-3004 无 getAppState 参）</li>
     *   <li>无决策消费：CC 返回 {@code Promise<void>}，阻断结果被丢弃（hooks.ts:3587-3591
     *       {@code await executeHooksOutsideREPL(...)} 返回值不消费）</li>
     * </ul>
     *
     * <p>sessionId/agentId 为 null（对齐 CC {@code createBaseHookInput(undefined)}，
     * hooks.ts:3580 无 sessionId 载荷）。需要会话作用域的生产者可经 {@link #executeEvent}
     * 传 {@link HookEvent#notification(String, String, String, String, String)} 全字段工厂。
     *
     * @param message          CC original: message（通知正文）
     * @param title            CC original: title（通知标题；可 null）
     * @param notificationType CC original: notification_type（matcher 匹配 key）
     * @return 聚合结果（对齐 CC void 语义——阻断结果不消费；返回仅供调用方遥测/调试观测）
     */
    public GenericHook.HookResult executeNotificationHooks(String message, String title, String notificationType) {
        if (message == null || notificationType == null || notificationType.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("executeNotificationHooks: message/notificationType 为空, 跳过发射 (message={}, notificationType={})",
                    message, notificationType);
            }
            return GenericHook.HookResult.proceed();
        }
        HookEvent event = HookEvent.notification(null, null, message, title, notificationType);
        if (log.isDebugEnabled()) {
            log.debug("HOOK executeNotificationHooks: notificationType={}", notificationType);
        }
        return executeEvent(event);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-LC-03 OPD-WF4-LC-04] Setup hooks 通用发射点 · 对齐 CC
    //   executeSetupHooks (hooks.ts:3902-3922)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [IMP-LC-03 · OPD-WF4-LC-04] Setup hook 通用发射点 · 对齐 CC
     * {@code executeSetupHooks(trigger: 'init' | 'maintenance', ...)}（utils/hooks.ts:3902-3922）。
     *
     * <p>WHY（探查 ✗-3 · EV-WF4-LC-043）：CC Setup hook trigger 为 union 'init'|'maintenance'
     * （main.tsx:2571 {@code setupTrigger = initOnly || init ? 'init' : maintenance ? 'maintenance' : null}），
     * 经 matchQuery=trigger 匹配（hooks.ts:3914-3921）。Java 旧实现仅 LlmAgentLoop 会话启动
     * 硬编码 trigger='init'（LlmAgentLoop.java:1949-1962），maintenance 场景无发射点 → 配置
     * matcher='maintenance' 的 Setup hook 永不触发。本方法暴露通用 Setup 发射点：任意 trigger
     * （'init'/'maintenance'）均可经 {@link #executeEvent} 发射，HookMatcherEngine SETUP →
     * data.trigger 匹配（HookMatcherEngine.java:331）。会话启动 'init' 与 maintenance 触发点
     * （LlmAgentLoop.fireSetupMaintenanceHooks）共用本方法，消除硬编码字面量重复。
     *
     * @param sessionId 会话 ID（可为 null；对齐 CC createBaseHookInput(undefined)）
     * @param agentId   agent ID（主线程 null）
     * @param trigger   Setup trigger：'init' 或 'maintenance'（CC union；null/blank → 跳过发射）
     * @return 聚合结果（对齐 CC executeSetupHooks 聚合语义；trigger 无效时返回 proceed）
     */
    public GenericHook.HookResult executeSetupHooks(String sessionId, String agentId, String trigger) {
        if (trigger == null || trigger.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("executeSetupHooks: trigger 为空, 跳过 Setup hook 发射 (sessionId={})", sessionId);
            }
            return GenericHook.HookResult.proceed();
        }
        HookEvent event = HookEvent.setup(sessionId, agentId, trigger);
        if (log.isDebugEnabled()) {
            log.debug("HOOK executeSetupHooks: trigger={} sessionId={}", trigger, sessionId);
        }
        return executeEvent(event);
    }

    /**
     * [H4] 配置驱动 CommandHook 执行 · 对齐 CC executeHooks command 分支 (hooks.ts:2448-2613)
     * exit-code 分流. 行为与 H2 保持完全一致 (exit 0 → stdout JSON; exit 2 → blocking; 其他 → non_blocking).
     *
     * @param parentTuc 父 per-turn ToolUseContext（提取 abortController 做执行前取消检查；
     *                  null = 无父上下文，与 CC signal 缺省语义一致）
     */
    /**
     * [H4] 配置驱动 CommandHook 执行 · 对齐 CC executeHooks command 分支 (hooks.ts:2448-2613)
     * exit-code 分流. 行为与 H2 保持完全一致 (exit 0 → stdout JSON; exit 2 → blocking; 其他 → non_blocking).
     *
     * @param parentTuc 父 per-turn ToolUseContext（提取 abortController 做执行前取消检查；
     *                  null = 无父上下文，与 CC signal 缺省语义一致）
     */
    private GenericHook.HookResult executeConfiguredCommand(HookEvent event, MatchedHook mh,
                                                            CommandHook commandHook, String jsonInput,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            int hookIndex) {
        // [IMP-HOOKS-S5 D-01] 缺省超时由 8 参重载承载（SessionEnd 1500ms 收紧）· 既有调用方 10min
        // [IMP-RS-01 DEL-01e 补回] promptRequester=null (默认无通道)
        return executeConfiguredCommand(event, mh, commandHook, jsonInput, parentTuc, hookIndex,
            CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS, null);
    }

    /**
     * [IMP-HOOKS-S5 D-01] 配置驱动 CommandHook 执行 + 缺省超时（CC hooks.ts:3280
     * {@code hook.timeout ? hook.timeout*1000 : timeoutMs}；SessionEnd 传 1500ms）。
     * [IMP-RS-01 DEL-01e 补回] promptRequester 透传 CommandHookExecutor.execute 13 参。
     */
    private GenericHook.HookResult executeConfiguredCommand(HookEvent event, MatchedHook mh,
                                                            CommandHook commandHook, String jsonInput,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            int hookIndex,
                                                            long defaultTimeoutMs) {
        return executeConfiguredCommand(event, mh, commandHook, jsonInput, parentTuc, hookIndex,
            defaultTimeoutMs, null);
    }

    /**
     * [IMP-RS-01 DEL-01e 补回] 配置驱动 CommandHook 执行 + 缺省超时 + prompt 回调通道 ·
     * 对齐 CC execCommandHook (hooks.ts:759) — requestPrompt 非 null 时 command hook 可向用户
     * 请求补充输入 (stdout 逐行检测 + 写回 stdin)。
     */
    private GenericHook.HookResult executeConfiguredCommand(HookEvent event, MatchedHook mh,
                                                            CommandHook commandHook, String jsonInput,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            int hookIndex,
                                                            long defaultTimeoutMs,
                                                            PromptRequester promptRequester) {
        // [IMP-A2-1 · MG-5 batchAbort 透传] 无批级 abort 重载（既有调用方零改动）·
        //   批级 abort 由 9 参重载承载。
        return executeConfiguredCommand(event, mh, commandHook, jsonInput, parentTuc, hookIndex,
            defaultTimeoutMs, promptRequester, null);
    }

    /**
     * [IMP-A2-1 · MG-5 batchAbort 透传] 配置驱动 CommandHook 执行 + 缺省超时 + prompt 回调通道
     * + 批级 abort 信号 · 对齐 CC execCommandHook abortSignal（hooks.ts:2453 → 运行期
     * combinedAbortSignal 杀子进程 ShellCommand.ts:264-265）。
     *
     * <p>effective parentAbort = 父 per-turn TUC abortController（parentTuc）优先，无则批级
     * abort（batchAbort）。当前批级 abort 调用方（compact hooks / SessionEnd，均 outside-REPL）
     * parentTuc 恒 null → batchAbort 为唯一 abort 源，与 CC compact 路径单一 signal
     * （context.abortController.signal，compact.ts:418/:728）一致。
     */
    private GenericHook.HookResult executeConfiguredCommand(HookEvent event, MatchedHook mh,
                                                            CommandHook commandHook, String jsonInput,
                                                            com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                            int hookIndex,
                                                            long defaultTimeoutMs,
                                                            PromptRequester promptRequester,
                                                            com.nexusai.application.agent.tool.AbortController batchAbort) {
        CommandHookExecutor executor = this.commandHookExecutor;
        if (executor == null) {
            return GenericHook.HookResult.proceed(); // 未接线 → 不执行 (不破坏现有路径)
        }
        String hookName = "config-command:" + commandHook.command();
        // [EX-HOOK R5] 父 abort 透传 · 对齐 CC executeHooks 入口 if (signal?.aborted) return
        //   (hooks.ts:2015-2017)。父 per-turn TUC 已取消 → 不 spawn 子进程（CC 在钩子运行
        // [EX-HOOK R7/全局反思 P1-1 修复] 透传父 abort 给 11 参 execute（CC execCommandHook
        //   abortSignal hooks.ts:2453 → 运行期 combinedAbortSignal 杀子进程 ShellCommand.ts:264-265）。
        //   执行前早返只覆盖"已取消"；运行中取消由 CommandHookExecutor onCancel → destroyForcibly 承担。
        // [IMP-A2-1 · MG-5] 批级 abort 同条件早返 + 透传为执行器 parentAbort（运行中批 abort →
        //   CommandHookExecutor onCancel → destroyForcibly SIGKILL，对齐 CC wrapSpawn 杀子进程）。
        com.nexusai.application.agent.tool.AbortController parentAbort =
            parentTuc != null ? parentTuc.abortController() : null;
        com.nexusai.application.agent.tool.AbortController effectiveParentAbort =
            parentAbort != null ? parentAbort : batchAbort;
        if (parentAbort != null && parentAbort.isCancelled()
            || batchAbort != null && batchAbort.isCancelled()) {
            if (log.isDebugEnabled()) {
                log.debug("配置驱动 CommandHook '{}' 跳过: 父/批级 abort 已取消 (对齐 CC executeHooks 入口 signal.aborted 早返)", hookName);
            }
            return GenericHook.HookResult.proceed();
        }
        if (log.isDebugEnabled()) {
            log.debug("配置驱动 CommandHook '{}' 并行分发执行", hookName);
        }
        // [H3 v3 修复] Gap 3: 计算 hook 执行耗时 durationMs 透传给 message attachment
        //   (CC execCommandHook hooks.ts:2463 `const durationMs = Date.now() - hookStartMs`).
        // [S4 G09 / G14] spawn cwd = 会话 cwd · resolveSpawnCwd(event) 收敛 CwdResolution 单一入口
        //   (event.cwd() ?? CwdResolution.getCwd(sessionId) → 对齐 CC hooks.ts:931-938/:969/:979).
        long hookStartMs = System.currentTimeMillis();
        // [IMP-RS-01 DEL-01e 补回] prompt 回调通道透传 · 对齐 CC execCommandHook requestPrompt
        //   (hooks.ts:759). 双路径: promptRequester==null → 走 12 参便捷版 (既有测试 fake 覆写
        //   12 参重载, 保持既有拦截行为不变); promptRequester!=null → 走 13 参全量版 (stdout
        //   prompt 检测 + stdin keep-open + prompt 行过滤, CC :1072-1110/:1199-1216/:1243-1249).
        CommandHookExecutor.CommandHookResult execResult;
        if (promptRequester == null) {
            execResult = executor.execute(
                commandHook, event, hookName, jsonInput,
                mh.pluginRoot(), mh.pluginId(), mh.skillRoot(), hookIndex, false,
                effectiveParentAbort, defaultTimeoutMs,
                CommandHookExecutor.resolveSpawnCwd(event));
        } else {
            execResult = executor.execute(
                commandHook, event, hookName, jsonInput,
                mh.pluginRoot(), mh.pluginId(), mh.skillRoot(), hookIndex, false,
                effectiveParentAbort, defaultTimeoutMs,
                CommandHookExecutor.resolveSpawnCwd(event),
                promptRequester);
        }
        long durationMs = System.currentTimeMillis() - hookStartMs;
        // [H-WF2-02 WF6-X4 patch-note] Elicitation/ElicitationResult 走 CC 专用宽松解析
        //   (hooks.ts:4388-4468), 绕过通用严格 toHookResultCore (validationError→NON_BLOCKING_ERROR /
        //   expectedHookEvent 抛错 / 纯文本 hook_success attachment — 三处偏离 CC).
        HookEventType eventType = event.type();
        if (eventType == HookEventType.ELICITATION || eventType == HookEventType.ELICITATION_RESULT) {
            // 对齐 CC executeHooksOutsideREPL 命令分支 (hooks.ts:3333-3348):
            //   blocked = status==2 (jsonBlocked 在 parseElicitationHookOutput 内部已按 decision==='block' 覆盖),
            //   output = status==0 ? stdout : stderr, succeeded = status==0
            boolean succeeded = execResult.status() == 0;
            String output = succeeded
                ? (execResult.stdout() != null ? execResult.stdout() : "")
                : (execResult.stderr() != null ? execResult.stderr() : "");
            boolean blocked = execResult.status() == 2;
            HookOutputParser.ElicitationParseResult el = HookOutputParser.parseElicitationHookOutput(
                output, blocked, succeeded, commandHook.command(),
                eventType == HookEventType.ELICITATION ? "Elicitation" : "ElicitationResult");
            GenericHook.HookResult base = new GenericHook.HookResult(false,
                el.blockingError(), null, null, null, null, null, null, null,
                GenericHook.HookOutcome.SUCCESS, null, null, null, null, null, null,
                eventType == HookEventType.ELICITATION ? el.response() : null,
                eventType == HookEventType.ELICITATION_RESULT ? el.response() : null);
            return base.withHook(commandHook);
        }
        GenericHook.HookResult hookResult =
            // [H3] 传 CommandHook → hook 字段有值 (hooks.ts:356)
            // [H3 v2 修复] Gap 3 (H3-GAP-4): 接线 expectedHookEvent — 传实际 CC 事件名
            //   (HookEvent.type().ccName(), 如 "PreToolUse"). CC hooks.ts:583-590 非空
            //   expectedHookEvent 且 hookSpecificOutput.hookEventName 不匹配 → throw (fail-loud),
            //   toHookResult catch 后降级 NON_BLOCKING_ERROR (对齐 CC runHook catch :2698-2729).
            //   此前传 null 跳过校验, hook 返回错误事件名时静默接受.
            // [H3 v3 修复] Gap 3: 透传 hookName/toolUseID/hookEvent → message attachment 元数据
            //   完整 (CC hooks.ts:2544-2557 processHookJSONOutput 载荷). 用 String-hookCommand
            //   7 参版本 (commandHook.command() 为 hookCommand), 再 withHook 补 hook 字段.
            CommandHookExecutor.toHookResult(execResult, commandHook.command(),
                hookName, event.type().ccName(), event.toolUseId(), event.type().ccName(), durationMs)
                .withHook(commandHook);
        if (log.isDebugEnabled()) {
            log.debug("配置驱动 CommandHook '{}' 完成: exit={} preventContinuation={}",
                hookName, execResult.status(), hookResult.preventContinuation());
        }
        return hookResult;
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMP-CF-03] statusLine / fileSuggestion 后端执行器
    //   对齐 CC executeStatusLineCommand (utils/hooks.ts:4584-4666) +
    //   executeFileSuggestionCommand (utils/hooks.ts:4675-4738)。
    //   前端 (FNT-CFG-03) 经 REST/WebSocket 调用；web 后端无 REPL UI，仅暴露执行器
    //   （CC 的 StatusLine.tsx / fileSuggestions.ts 前端输入构造归前端，后端只执行）。
    // ════════════════════════════════════════════════════════════════════════

    /** CC executeStatusLineCommand 缺省超时 (utils/hooks.ts:4587) · statusLine 短超时. */
    private static final long STATUS_LINE_DEFAULT_TIMEOUT_MS = 5000L;
    /** CC executeFileSuggestionCommand 缺省超时 (utils/hooks.ts:4678) · typeahead 短超时. */
    private static final long FILE_SUGGESTION_DEFAULT_TIMEOUT_MS = 5000L;

    /** 输入序列化 ObjectMapper (HookEvent JSON 载荷复用; thread-safe). */
    private static final ObjectMapper STATUS_INPUT_MAPPER = new ObjectMapper();

    /**
     * 执行 statusLine command hook · 对齐 CC executeStatusLineCommand (utils/hooks.ts:4584-4666).
     *
     * <p>CC 语义 (不信注释看行为, utils/hooks.ts 行号):
     * <ol>
     *   <li>shouldDisableAllHooksIncludingManaged() → undefined (:4591-4593)</li>
     *   <li>shouldSkipHookDueToTrust() → debug log + undefined (:4597-4602)</li>
     *   <li>shouldAllowManagedHooksOnly() → policySettings.statusLine，否则 merged settings.statusLine
     *       (:4606-4611)</li>
     *   <li>无 statusLine 或 type!=='command' → undefined (:4613-4615)</li>
     *   <li>execCommandHook(statusLine, 'StatusLine', 'statusLine', jsonInput, abort, uuid)
     *       (:4624-4631)</li>
     *   <li>aborted → undefined; status===0 → stdout trim+split+空行过滤+'\n' join;
     *       logResult 时 debug log (:4633-4659)</li>
     *   <li>异常 → error log + undefined (:4662-4665)</li>
     * </ol>
     *
     * @param statusLineInput 结构化 status 输入 (前端构建; CC StatusLineCommandInput,
     *                        StatusLine.tsx:36-127 createBaseHookInput + workspace/context_window 等)
     * @return status line 文本; 无配置/门控拦截/非零退出/异常 → null (CC undefined)
     */
    public String executeStatusLineCommand(Map<String, Object> statusLineInput) {
        return executeStatusLineCommand(statusLineInput, STATUS_LINE_DEFAULT_TIMEOUT_MS, false);
    }

    /**
     * 执行 statusLine command hook · 带超时与日志开关.
     *
     * @param statusLineInput 结构化 status 输入
     * @param timeoutMs       执行超时 (ms) · CC 缺省 5000 (utils/hooks.ts:4587)
     * @param logResult       是否在成功/非零退出时输出 debug/warn 日志 (CC logResult, utils/hooks.ts:4588)
     * @return status line 文本; 无配置/门控拦截/非零退出/异常 → null
     */
    public String executeStatusLineCommand(Map<String, Object> statusLineInput, long timeoutMs, boolean logResult) {
        // 1. disableAll 门控 (含 managed) · CC :4591-4593
        if (shouldDisableAllHooksIncludingManaged()) {
            return null;
        }
        // 2. workspace trust 门控 · CC :4597-4602
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping StatusLine command execution - workspace trust not accepted (对齐 CC hooks.ts:4598)");
            }
            return null;
        }
        // 3. 解析 statusLine 配置 · CC :4606-4611 (managedOnly → policy, 否则 merged)
        CommandHook statusLine = parseCommandConfig(resolveTopLevelSetting("statusLine"));
        if (statusLine == null) {
            return null;
        }
        CommandHookExecutor executor = this.commandHookExecutor;
        if (executor == null) {
            if (log.isDebugEnabled()) {
                log.debug("executeStatusLineCommand: commandHookExecutor 未接线, 跳过 statusLine 命令");
            }
            return null;
        }
        try {
            String jsonInput = serializeInput(statusLineInput);
            CommandHookExecutor.CommandHookResult result = executor.execute(
                statusLine, markerEvent(HookEventType.STATUS_LINE), "statusLine", jsonInput,
                null, null, null, null, false, null, timeoutMs, null, null);
            if (result.aborted()) {
                return null;
            }
            if (result.status() == 0) {
                String output = normalizeStatusLineOutput(result.stdout());
                if (!output.isEmpty()) {
                    if (logResult && log.isDebugEnabled()) {
                        log.debug("StatusLine [{}] completed with status {}", statusLine.command(), result.status());
                    }
                    return output;
                }
            } else if (logResult && log.isWarnEnabled()) {
                log.warn("StatusLine [{}] completed with status {}", statusLine.command(), result.status());
            }
            return null;
        } catch (Exception e) {
            log.error("StatusLine hook failed: {}", e.toString());
            return null;
        }
    }

    /**
     * 执行 fileSuggestion command hook · 对齐 CC executeFileSuggestionCommand (utils/hooks.ts:4675-4738).
     *
     * <p>CC 语义 (utils/hooks.ts 行号): disableAll → [] (:4681-4683); trust → [] (:4687-4692);
     * managedOnly → policy.fileSuggestion，否则 merged (:4696-4701); type!=='command' → []
     * (:4703-4705); execCommandHook({type:'command', command}, 'FileSuggestion', 'FileSuggestion',
     * jsonInput, abort, uuid) (:4713-4722); aborted||status!==0 → [] (:4724-4726);
     * stdout split '\n' → trim → filter 空串 (:4728-4731)。
     *
     * @param fileSuggestionInput 结构化 file suggestion 输入 (CC FileSuggestionCommandInput,
     *                            fileSuggestions.ts:727-730 createBaseHookInput + query)
     * @return 文件路径列表; 无配置/门控拦截/非零退出/异常 → 空列表
     */
    public List<String> executeFileSuggestionCommand(Map<String, Object> fileSuggestionInput) {
        return executeFileSuggestionCommand(fileSuggestionInput, FILE_SUGGESTION_DEFAULT_TIMEOUT_MS);
    }

    /**
     * 执行 fileSuggestion command hook · 带超时.
     *
     * @param fileSuggestionInput 结构化 file suggestion 输入
     * @param timeoutMs           执行超时 (ms) · CC 缺省 5000 (utils/hooks.ts:4678)
     * @return 文件路径列表; 无配置/门控拦截/非零退出/异常 → 空列表
     */
    public List<String> executeFileSuggestionCommand(Map<String, Object> fileSuggestionInput, long timeoutMs) {
        // 1. disableAll 门控 (含 managed) · CC :4681-4683
        if (shouldDisableAllHooksIncludingManaged()) {
            return List.of();
        }
        // 2. workspace trust 门控 · CC :4687-4692
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping FileSuggestion command execution - workspace trust not accepted (对齐 CC hooks.ts:4688)");
            }
            return List.of();
        }
        // 3. 解析 fileSuggestion 配置 · CC :4696-4701 (managedOnly → policy, 否则 merged)
        CommandHook fileSuggestion = parseCommandConfig(resolveTopLevelSetting("fileSuggestion"));
        if (fileSuggestion == null) {
            return List.of();
        }
        CommandHookExecutor executor = this.commandHookExecutor;
        if (executor == null) {
            if (log.isDebugEnabled()) {
                log.debug("executeFileSuggestionCommand: commandHookExecutor 未接线, 跳过 fileSuggestion 命令");
            }
            return List.of();
        }
        try {
            String jsonInput = serializeInput(fileSuggestionInput);
            // CC :4713: 构造 {type:'command', command: fileSuggestion.command} (丢弃 timeout/padding 等字段)
            CommandHook hook = new CommandHook(fileSuggestion.command(), null, null, null, null, null, null, null);
            CommandHookExecutor.CommandHookResult result = executor.execute(
                hook, markerEvent(HookEventType.FILE_SUGGESTION), "FileSuggestion", jsonInput,
                null, null, null, null, false, null, timeoutMs, null, null);
            if (result.aborted() || result.status() != 0) {
                return List.of();
            }
            return parseFileSuggestionOutput(result.stdout());
        } catch (Exception e) {
            log.error("File suggestion helper failed: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 解析 statusLine/fileSuggestion 顶层配置 · 对齐 CC executeStatusLineCommand/executeFileSuggestionCommand
     * 的配置选择分支 (utils/hooks.ts:4606-4611 / 4696-4701):
     * {@code shouldAllowManagedHooksOnly() ? getSettingsForSource('policySettings')?.[key]
     * : getSettings_DEPRECATED()?.[key]}.
     *
     * <p>Java 等价: managedOnly → {@link HooksSettings#policySettingsValue} (ManagedPolicySettingsSupplier
     * 读 policy 文件, 对象 → JsonNode); 否则 → {@link MultiSourceHooksConfigLoader#mergedTopLevelObject}
     * (user→project→local→policy 深合并, 对齐 getSettings_DEPRECATED = getInitialSettings 全源合并,
     * settings.ts:812-815)。
     *
     * @param key settings 顶层键 (statusLine / fileSuggestion)
     * @return 配置对象 (JsonNode); 未接线读源 / 键缺失 → null
     */
    private Object resolveTopLevelSetting(String key) {
        if (shouldAllowManagedHooksOnly()) {
            HooksSettings settings = this.hooksSettings;
            return settings != null ? settings.policySettingsValue(key) : null;
        }
        MultiSourceHooksConfigLoader loader = this.hooksConfigLoader;
        return loader != null ? loader.mergedTopLevelObject(key) : null;
    }

    /**
     * 解析 statusLine/fileSuggestion 配置为 CommandHook · 对齐 CC 校验
     * {@code !statusLine || statusLine.type !== 'command'} (utils/hooks.ts:4613 / 4703)。
     *
     * <p>CC statusLine/fileSuggestion 配置形态 (settings/types.ts:311-316 / 550-556):
     * {@code {type:'command', command: string}} (+ statusLine 可选 padding:number)。
     * type 非 'command' 或 command 缺失 → null (CC undefined → 不执行)。
     *
     * @param config 顶层配置值 (policy 或 merged 读出的 JsonNode)
     * @return CommandHook (含 timeout 秒数); 非 command 类型 / 缺失 → null
     */
    private static CommandHook parseCommandConfig(Object config) {
        if (!(config instanceof JsonNode node) || !node.isObject()) {
            return null;
        }
        JsonNode type = node.get("type");
        if (type == null || !type.isTextual() || !"command".equals(type.asText())) {
            return null;
        }
        JsonNode command = node.get("command");
        if (command == null || !command.isTextual() || command.asText().isBlank()) {
            return null;
        }
        Integer timeout = null;
        JsonNode t = node.get("timeout");
        if (t != null && t.isNumber()) {
            timeout = t.asInt();
        }
        return new CommandHook(command.asText(), null, null, timeout, null, null, null, null);
    }

    /**
     * 构造 marker 事件的 HookEvent (StatusLine/FileSuggestion) · CC execCommandHook 的
     * hookEvent 参数接受 'StatusLine'/'FileSuggestion' 字面量 (utils/hooks.ts:749,
     * {@code HookEvent | 'StatusLine' | 'FileSuggestion'})。CommandHookExecutor 仅用
     * type 做 diag/env 判定 (StatusLine/FileSuggestion 均不命中) 与 ccName 事件名。
     */
    private static HookEvent markerEvent(HookEventType markerType) {
        return new HookEvent(markerType, null, null, null, null, null, null, null, null, null, null, null, null, 0);
    }

    /** 输入 Map → stdin JSON · CC jsonStringify(statusLineInput/fileSuggestionInput)
     *  (utils/hooks.ts:4622/4711; jsonStringify 不抛, 失败回退空对象)。 */
    private static String serializeInput(Map<String, Object> input) {
        try {
            return STATUS_INPUT_MAPPER.writeValueAsString(input != null ? input : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }

    /** statusLine stdout 归一化 · CC :4640-4644 (trim → split '\n' → 行 trim 去空 → join '\n'). */
    private static String normalizeStatusLineOutput(String stdout) {
        if (stdout == null) {
            return "";
        }
        return Arrays.stream(stdout.trim().split("\n", -1))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining("\n"));
    }

    /** fileSuggestion stdout 解析 · CC :4728-4731 (split '\n' → trim → filter 空串). */
    private static List<String> parseFileSuggestionOutput(String stdout) {
        if (stdout == null) {
            return List.of();
        }
        return Arrays.stream(stdout.split("\n", -1))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * [H4 + IMPL-05] 配置驱动 PromptHook 执行 · 对齐 CC executeHooks prompt 分支
     * (hooks.ts:2224-2254) + execPromptHook.ts:62-99 (queryModelWithoutStreaming)。
     * Java 端 {@link PromptHook} 直接作为 exec 入参（对齐 CC 单一 PromptHook 类型）。
     */
    private GenericHook.HookResult executeConfiguredPrompt(HookEvent event, MatchedHook mh,
                                                           PromptHook promptHook, String jsonInput,
                                                           com.nexusai.application.agent.tool.ToolUseContext parentTuc) {
        // [H2/CCJ-EXEC-01] 无 messages 重载（既有调用方零改动；CC messages 可选参数）
        return executeConfiguredPrompt(event, mh, promptHook, jsonInput, parentTuc, null);
    }

    /**
     * [H2/CCJ-EXEC-01] 配置驱动 PromptHook 执行 + messages 透传 · CC hooks.ts:2230-2239
     *   {@code execPromptHook(..., messages, toolUseID)}（Stop/SubagentStop 生产传入会话历史）。
     */
    private GenericHook.HookResult executeConfiguredPrompt(HookEvent event, MatchedHook mh,
                                                           PromptHook promptHook, String jsonInput,
                                                           com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                           List<ChatMessageDto> messages) {
        ExecPromptHook executor = this.execPromptHook;
        if (executor == null) {
            return GenericHook.HookResult.proceed(); // 未接线 → 不执行
        }
        String hookName = "config-prompt:" + promptHook.prompt();
        if (llmProviderFactory == null) {
            // 无 provider 工厂 → 无法调 LLM, 显式跳过
            log.warn("配置驱动 PromptHook '{}' 跳过: llmProviderFactory 未接线", hookName);
            return GenericHook.HookResult.proceed();
        }
        if (modelConfigResolver == null) {
            // 无 provider 解析通道 → 无法解析真实 provider, 显式跳过（不落 mock）
            log.warn("配置驱动 PromptHook '{}' 跳过: modelConfigResolver 未接线 (真实 provider 解析不可用)", hookName);
            return GenericHook.HookResult.proceed();
        }
        // 模型名解析 · 对齐 CC execPromptHook.ts:79 hook.model ?? getSmallFastModel()
        String modelName = (promptHook.model() != null && !promptHook.model().isBlank())
            ? promptHook.model() : defaultFastModel;
        if (modelName == null || modelName.isBlank()) {
            // [EX-HOOK R4] 空模型不跳过 · 对齐 CC execPromptHook.ts:79 hook.model ?? getSmallFastModel()
            //   + model.ts:36-38 getSmallFastModel env 链（ANTHROPIC_SMALL_FAST_MODEL →
            //   ANTHROPIC_DEFAULT_HAIKU_MODEL → 默认 haiku45）。同 ExecAgentHook 波1 回落模式，
            //   共享 SkillImprovementHook.getSmallFastModel 单一实现。
            modelName = SkillImprovementHook.getSmallFastModel();
            if (log.isDebugEnabled()) {
                log.debug("配置驱动 PromptHook '{}' 模型名未配置, 回落 getSmallFastModel: {}", hookName, modelName);
            }
        }
        // 真实 provider 解析 · 对齐 ChatService.buildConfigForModel + providerTypeForModel
        PromptProviderResolution resolution = resolvePromptProvider(modelName);
        if (resolution == null) {
            log.warn("配置驱动 PromptHook '{}' 跳过: 模型 '{}' 无可用的 enabled provider/apiKey (不落 mock)", hookName, modelName);
            return GenericHook.HookResult.proceed();
        }
        // [对抗核验 H13-GAP-3 v3] 父工具集透传 → PromptLlmContext.tools（CC execPromptHook.ts:72
        //   tools: toolUseContext.options.tools）。parentTuc null → 无工具（旧行为）。
        List<com.nexusai.application.agent.tool.Tool> parentTools =
            parentTuc != null && parentTuc.availableTools() != null
                ? parentTuc.availableTools() : List.of();
        ExecPromptHook.PromptLlmContext llmContext = new ExecPromptHook.PromptLlmContext(
            resolution.provider(), resolution.config(), modelName, parentTools);
        // [IMPL-06 D5-1/OD-EX-02] 父 abort 透传 · 对齐 CC hooks.ts:2235 abortSignal（父 per-turn TUC
        //   abortController；null = 无父上下文 → 无父取消，与 CC signal 缺省语义一致）。
        com.nexusai.application.agent.tool.AbortController parentAbort =
            parentTuc != null ? parentTuc.abortController() : null;
        long hookStartMs = System.currentTimeMillis();
        if (log.isDebugEnabled()) {
            log.debug("配置驱动 PromptHook '{}' 并行分发执行: model={} providerType={} baseUrl={}",
                hookName, modelName, resolution.provider().type(), resolution.config().baseUrl());
        }
        // [IMPL-06 OD-EX-04] attachment 注入 command/durationMs · 对齐 CC hooks.ts:2241-2250
        return injectHookCommandAndDuration(
            executor.exec(promptHook, hookName, event, jsonInput, llmContext, parentAbort, messages),
            hookCommandFor(promptHook), hookStartMs);
    }

    /**
     * [IMPL-05 D10-1 / OD-EX-05][RV-FOLLOWUP DEDUP-01] 按模型名解析真实 provider · 薄委托
     * {@link ModelConfigResolver#resolve(String)}（单一解析来源，消重副本#2）。
     *
     * <p>语义（对齐 CC queryModel 的模型 → provider 解析）：
     * <ol>
     *   <li>resolver 未注入（null）→ warn + null（不落 mock）</li>
     *   <li>resolver 内部任一步不可用（model 未命中 / provider 缺失未 enabled / apiKey 解密空）
     *       → warn + null</li>
     *   <li>成功 → {@code factory.getProvider(r.config(), r.providerType())} → (provider + config)</li>
     * </ol>
     *
     * <p>异常语义（核验新增，必保留）：ModelConfigResolver.resolve 无 try/catch（直接 DB + 解密调用，
     * 可能抛异常）→ 本方法以 try/catch 包裹防止异常穿透 {@link #executeConfiguredPrompt}。
     *
     * <p>数据源变化登记（rules 十二）：原实现遍历 providerService 全量 provider（provider 聚合视角）；
     * 委托后走 modelMapper 全局 enabled model 表。两者都收敛到 enabled model + enabled provider +
     * 解密 key，判定结果大概率等价；若发现行为差异，回退并登记。
     *
     * @param modelName 非空模型名（hook.model 或 fast model）
     * @return 解析结果 (provider + config)；不可用 → null（调用方显式跳过，不落 mock）
     */
    private PromptProviderResolution resolvePromptProvider(String modelName) {
        LlmProviderFactory factory = this.llmProviderFactory;
        if (modelConfigResolver == null) {
            log.warn("配置驱动 PromptHook provider 解析: ModelConfigResolver 未注入，跳过 (warn+null 不落 mock)");
            return null;
        }
        try {
            ModelConfigResolver.ResolvedModel r = modelConfigResolver.resolve(modelName);
            if (r == null) {
                log.warn("配置驱动 PromptHook provider 解析: 模型 '{}' 无可用的 enabled provider/apiKey", modelName);
                return null;
            }
            return new PromptProviderResolution(factory.getProvider(r.config(), r.providerType()), r.config());
        } catch (Exception e) {
            log.warn("配置驱动 PromptHook provider 解析失败: {}", e.toString());
            return null;
        }
    }

    /** [IMPL-05] provider 解析结果 · (真实 provider + 其运行时配置). */
    private record PromptProviderResolution(LlmProvider provider, ProviderConfig config) {
        private PromptProviderResolution {
            if (provider == null) {
                throw new IllegalArgumentException("provider is null");
            }
            if (config == null || !config.isUsable()) {
                throw new IllegalArgumentException("config is not usable");
            }
        }
    }

    /**
     * [H4] 配置驱动 AgentHook 执行 · 对齐 CC executeHooks agent 分支 (hooks.ts:2256-2294).
     *
     * <p>WHY: CC agent hook 启动独立子 agent 多轮验证条件 (execAgentHook.ts). Java 端走
     * {@link ExecAgentHook#exec}. sessionId 是 String, exec 签名需要 UUID, parse 失败传 null
     * (hook agent 仍可运行, 父 sessionId 复用能力降级).
     */
    private GenericHook.HookResult executeConfiguredAgent(HookEvent event, MatchedHook mh,
                                                          AgentHook agentHook, String jsonInput,
                                                          com.nexusai.application.agent.tool.ToolUseContext parentTuc) {
        ExecAgentHook executor = this.execAgentHook;
        if (executor == null) {
            return GenericHook.HookResult.proceed(); // 未接线 → 不执行
        }
        String hookName = "config-agent:" + agentHook.prompt();
        if (log.isDebugEnabled()) {
            log.debug("配置驱动 AgentHook '{}' 并行分发执行", hookName);
        }
        // [对抗核验 H13-GAP] agentName 对齐 CC hooks.ts:2283-2286: hookInput.agent_type → exec agentName。
        // event.data() 的 agent_type 即 buildJsonInput 注入的 hookInput.agent_type（CommandHookExecutor.
        // buildJsonInput 逐项透传 data）；无该字段时降级 null（对齐 CC 'agent_type' in hookInput 判别）。
        // [对抗核验 H13-GAP-1 v3] 父 permission context 继承 · 对齐 CC execAgentHook.ts:141-153
        // getAppState() override 继承父 alwaysAllowRules。parentTuc 为当前父 per-turn TUC（含每轮
        // 重建后的最新 permCtx）；null → 旧行为（无父规则继承）。
        com.nexusai.application.agent.permission.ToolPermissionContext parentPermCtx =
            parentTuc != null ? parentTuc.permissionContext() : null;
        // [IMPL-06 D5-2/OD-EX-02] 父 abort 透传（修复旧实现恒传 null → 父已取消仍跑完整 loop，
        //   EV-EX-027/028）· 对齐 CC hooks.ts:2272 abortSignal → execAgentHook.ts:79-85
        //   createCombinedAbortSignal(signal, {timeoutMs}) 硬取消。
        com.nexusai.application.agent.tool.AbortController parentAbort =
            parentTuc != null ? parentTuc.abortController() : null;
        long hookStartMs = System.currentTimeMillis();
        // [IMPL-06 OD-EX-04] attachment 注入 command/durationMs · 对齐 CC hooks.ts:2281-2290
        return injectHookCommandAndDuration(
            executor.exec(agentHook, hookName, event, jsonInput,
                event.transcriptPath(), parentAbort, event.sessionId(),
                hookAgentNameFrom(event), parentPermCtx),
            hookCommandFor(agentHook), hookStartMs);
    }

    /**
     * [对抗核验 H13-GAP] 从 hook 事件 data 提取 agent_type → exec agentName。
     *
     * <p>WHY: CC hooks.ts:2283-2286 {@code 'agent_type' in hookInput ? (hookInput.agent_type as string) : undefined}。
     * Java HookEvent.data() 的 {@code agent_type} key 承载（对齐 buildJsonInput 逐项透传 event.data()）。
     *
     * @param event hook 事件（可能不含 agent_type）
     * @return agent 名（事件无 agent_type 时 null）
     */
    private static String hookAgentNameFrom(HookEvent event) {
        if (event == null || event.data() == null) {
            return null;
        }
        Object agentType = event.data().get("agent_type");
        return agentType instanceof String s && !s.isBlank() ? s : null;
    }
    private GenericHook.HookResult executeConfiguredHttp(HookEvent event, MatchedHook mh,
                                                         HttpHook httpHook, String jsonInput,
                                                         com.nexusai.application.agent.tool.ToolUseContext parentTuc) {
        // [IMP-A2-1 · MG-5 batchAbort 透传] 无批级 abort 重载（既有调用方零改动）·
        //   批级 abort 由 6 参重载承载。
        return executeConfiguredHttp(event, mh, httpHook, jsonInput, parentTuc, null);
    }

    /**
     * [IMP-A2-1 · MG-5 batchAbort 透传] 配置驱动 HttpHook 执行 + 批级 abort 信号 ·
     * 对齐 CC executeHooksOutsideREPL http 分支（hooks.ts:2299-2301 {@code execHttpHook(..., signal)}
     * —— http 分支传 signal 而非 combined abortSignal，execHttpHook 内部自行管理 timeout）。
     *
     * <p>effective parentAbort = 父 per-turn TUC abortController（parentTuc）优先，无则批级
     * abort（batchAbort）。批 abort → ExecHttpHook 竞速短路终止在途请求（对齐 CC signal.aborted
     * 检查 hooks.ts:2306）。
     */
    private GenericHook.HookResult executeConfiguredHttp(HookEvent event, MatchedHook mh,
                                                         HttpHook httpHook, String jsonInput,
                                                         com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                         com.nexusai.application.agent.tool.AbortController batchAbort) {
        ExecHttpHook executor = this.execHttpHook;
        if (executor == null) {
            return GenericHook.HookResult.proceed(); // 未接线 → 不执行
        }
        String hookName = "config-http:" + httpHook.url();
        if (log.isDebugEnabled()) {
            log.debug("配置驱动 HttpHook '{}' 并行分发执行", hookName);
        }
        // [IMPL-06 D5-3/OD-EX-02] 父 abort 透传 · 对齐 CC hooks.ts:2306 signal
        //   （http 分支传 signal 而非 abortSignal —— execHttpHook 内部自行管理 timeout，
        //   CC :2299-2301 注释；Java 端 HttpRequest.timeout 承载 timeout，父 abort 经竞速短路）。
        com.nexusai.application.agent.tool.AbortController parentAbort =
            parentTuc != null ? parentTuc.abortController() : null;
        com.nexusai.application.agent.tool.AbortController effectiveParentAbort =
            parentAbort != null ? parentAbort : batchAbort;
        ExecHttpHook.HttpHookResult httpResult = executor.exec(httpHook, hookName, event, jsonInput, effectiveParentAbort);
        // [H3 v2 + H7-v2 H7-GAP-3] expectedHookEvent 接入实际事件 CC PascalCase 名
        //   (event.type().ccName(), H10 提供映射): CC processHookJSONOutput 校验
        //   hookSpecificOutput.hookEventName (hooks.ts:583-590 不匹配→throw fail-loud).
        //   toolUseId 透传给 attachment (hook_cancelled / hook_non_blocking_error 的 toolUseID 字段).
        return httpToHookResult(httpResult, httpHook, hookName, event.type().ccName(), event.toolUseId());
    }

    // ════════════════════════════════════════════════════════════════════════
    // [IMPL-06 OD-EX-04] attachment command/durationMs 注入 · 对齐 CC hooks.ts:2241-2250/2281-2290
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 向 prompt/agent hook 结果 attachment 注入 command/durationMs · 对齐 CC hooks.ts:2241-2250
     * （prompt 分支）/ :2281-2290（agent 分支）:
     * <pre>
     *   if (result.message?.type === 'attachment') {
     *     const att = result.message.attachment
     *     if (att.type === 'hook_success' || att.type === 'hook_non_blocking_error') {
     *       att.command = hookCommand
     *       att.durationMs = Date.now() - hookStartMs
     *     }
     *   }
     * </pre>
     *
     * @param result     exec 返回的 HookResult（message 非 attachment / 非两类 type → 原样返回）
     * @param hookCommand CC getHookDisplayText(hook)（statusMessage ?? prompt）
     * @param hookStartMs 本次 hook 分发起始时间戳（CC hookStartMs = Date.now()）
     * @return 注入后的结果（record 不可变 → 新副本）
     */
    private static GenericHook.HookResult injectHookCommandAndDuration(GenericHook.HookResult result,
                                                                       String hookCommand, long hookStartMs) {
        if (result == null || result.message() == null
                || !(result.message() instanceof AttachmentMessageDto att)) {
            return result;
        }
        String type = att.type();
        if (!"hook_success".equals(type) && !"hook_non_blocking_error".equals(type)) {
            return result;
        }
        AttachmentMessageDto enriched =
            AttachmentMessageDto.withCommandAndDuration(att, hookCommand,
                System.currentTimeMillis() - hookStartMs);
        return new GenericHook.HookResult(result.preventContinuation(), result.blockingError(),
            result.systemMessages(), result.additionalContexts(), enriched,
            result.updatedInput(), result.updatedMCPToolOutput(), result.retry(),
            result.hookPermissionDecisionReason(), result.outcome(), result.stopReason(),
            result.permissionBehavior(), null, result.hook(), null, null, null, null);
    }

    /** CC getHookDisplayText 等价（hooksSettings.ts:68-90）· statusMessage 优先，缺省 prompt/url/command. */
    private static String hookCommandFor(PromptHook hook) {
        return (hook.statusMessage() != null && !hook.statusMessage().isBlank())
            ? hook.statusMessage() : hook.prompt();
    }

    /** CC getHookDisplayText 等价（hooksSettings.ts:68-90）· statusMessage 优先，缺省 prompt. */
    private static String hookCommandFor(AgentHook hook) {
        return (hook.statusMessage() != null && !hook.statusMessage().isBlank())
            ? hook.statusMessage() : hook.prompt();
    }

    /**
     * [H4+H7] HttpHookResult → GenericHook.HookResult · 对齐 CC executeHooks http 分支
     * (hooks.ts:2310-2443) + execHttpHook.ts:128-134 5 字段返回.
     *
     * <p>WHY (规则九): H4 只按 ok/error/aborted 映射 outcome, 不解析 body JSON — HTTP hook
     * 返回的 {@code {"continue":false}} / {@code {"decision":"block"}} 会被静默吞掉 (权限拦截失效).
     * [H7] 补 CC 调用方 JSON 解释层:
     * <p><b>[H7-v2 H7-GAP-4 修复] message attachment</b>: 前 3 条失败/取消路径补 CC createAttachmentMessage 等价
     * (hooks.ts:2322-2391): aborted → hook_cancelled / error + validationError → hook_non_blocking_error
     * (stderr/stdout 入 content, CC 的 exitCode/stderr/stdout 字段 Java AttachmentMessageDto 收敛为 content,
     * 记录 J 遗憾). message 由调用方 (StreamingToolExecutor / 聚合层) 按 type 消费注入 LLM.
     *
     * <ol>
     *   <li>{@code aborted=true} → outcome=cancelled, 不阻断 + hook_cancelled attachment (CC :2310-2332)</li>
     *   <li>{@code error != null || !ok} → outcome=non_blocking_error + hook_non_blocking_error attachment
     *       (CC :2334-2360)</li>
     *   <li>{@code ok=true} → body 经 {@link HookOutputParser#parseHttpHookOutput} (CC :2363-2392):
     *       <ul>
     *         <li>validationError → outcome=non_blocking_error + hook_non_blocking_error attachment
     *             (CC :2367-2391, HTTP hook 必须返 JSON)</li>
     *         <li>async ({@code {"async":true}}) → outcome=success, 不再处理 (CC :2394-2411)</li>
     *         <li>sync → {@link HookOutputParser#processHookJSONOutput} (CC :2413-2440),
     *             outcome 恒 SUCCESS (阻断语义由 preventContinuation/permissionBehavior/blockingError
     *             承载, 对齐 CommandHookExecutor.parseStdoutJson), hook 字段填 {@link HttpHook}</li>
     *       </ul></li>
     * </ol>
     *
     * @param r                 exec 返回 5+1 字段结果
     * @param hook              触发本 result 的 HttpHook (hook 字段承载)
     * @param hookName          hook 展示名 (processHookJSONOutput hookName + attachment.hookName)
     * @param expectedHookEvent 期望事件名 · CC PascalCase 名 (如 "PreToolUse"); null/空 = 跳过
     *                          hookSpecificOutput 事件名校验 (仅测试/无事件上下文场景)
     * @param toolUseId         工具调用 ID · attachment.toolUseID 字段 (非工具事件 → null)
     */
    static GenericHook.HookResult httpToHookResult(ExecHttpHook.HttpHookResult r, HttpHook hook,
                                                   String hookName, String expectedHookEvent, String toolUseId) {
        if (r == null) {
            return GenericHook.HookResult.proceed();
        }
        // 1. aborted → cancelled (CC :2310-2332) + hook_cancelled attachment (H7-GAP-4 修复)
        if (r.aborted()) {
            AttachmentMessageDto cancelled = AttachmentMessageDto.hookCancelled(
                hookName, toolUseId, expectedHookEvent);
            return new GenericHook.HookResult(false, null, null, null, cancelled, null, null,
                null, null, GenericHook.HookOutcome.CANCELLED, null, null, null, hook, null, null, null, null);
        }
        // 2. error || !ok → non_blocking_error (CC :2334-2360) + hook_non_blocking_error attachment
        //    stderr = error || `HTTP ${statusCode} from ${url}` (CC :2336), stdout='' (CC :2350)
        if (r.error() != null || !r.ok()) {
            String stderr = r.error() != null ? r.error()
                : "HTTP " + r.statusCode() + " from " + hook.url();
            AttachmentMessageDto errAtt = AttachmentMessageDto.hookNonBlockingError(
                hookName, toolUseId, expectedHookEvent, stderr, "", 0);
            return new GenericHook.HookResult(false, null, null, null, errAtt, null, null,
                null, null, GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, hook, null, null, null, null);
        }
        // 3. ok → 解释 body JSON (CC :2363-2392)
        HookOutputParser.ParseResult parsed = HookOutputParser.parseHttpHookOutput(r.body());
        if (parsed.validationError() != null) {
            // JSON 校验失败 → non_blocking_error (CC :2367-2391) + hook_non_blocking_error attachment
            //   stderr=`JSON validation failed: ${error}` (CC :2382), stdout=body (CC :2383)
            if (log.isWarnEnabled()) {
                log.warn("HTTP hook '{}' body 非 JSON (validationError={}), 视为 non_blocking_error",
                    hookName, parsed.validationError());
            }
            AttachmentMessageDto validationAtt = AttachmentMessageDto.hookNonBlockingError(
                hookName, toolUseId, expectedHookEvent,
                "JSON validation failed: " + parsed.validationError(), r.body(), 0);
            return new GenericHook.HookResult(false, null, null, null, validationAtt, null, null,
                null, null, GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, hook, null, null, null, null);
        }
        // 4. async → success, 不再处理 (CC :2394-2411)
        if (parsed.json() != null && HookJSONOutput.isAsyncHookJSONOutput(parsed.json())) {
            return new GenericHook.HookResult(false, null, null, null, null, null, null,
                null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, hook, null, null, null, null);
        }
        // 5. sync → processHookJSONOutput (CC :2413-2440), outcome 恒 SUCCESS, hook 字段填 HttpHook
        // [H3 v3 修复] Gap 3: 透传 stdout/stderr/exitCode/toolUseId/hookEvent → message attachment
        //   载荷对齐 CC (hooks.ts:2413-2425: stdout=body, stderr='', exitCode=statusCode, toolUseID).
        if (parsed.json() instanceof HookJSONOutput.SyncHookOutput sync) {
            try {
                HookOutputParser.ParsedHookJSONOutput out =
                    HookOutputParser.processHookJSONOutput(sync, hook.url(), hookName, expectedHookEvent,
                        toolUseId, expectedHookEvent, r.body(), "", r.statusCode(), null);
                if (out != null && out.result() != null) {
                    return out.result().withHook(hook);
                }
            } catch (Exception e) {
                // processHookJSONOutput 对非法 decision / expectedHookEvent 不匹配抛 throw
                // (hooks.ts:538-541 / :583-590), 调用方等价 CC runHook catch (hooks.ts:2698-2729)
                // → hook_non_blocking_error attachment + outcome NON_BLOCKING_ERROR → 不阻断.
                // [H3 v4 修复 Gap②] 对齐 command 路径 (CommandHookExecutor.parseStdoutJson catch):
                //   补 hook_non_blocking_error attachment (stderr=`Failed to run: ...`, stdout:'',
                //   exitCode=1, command=hook.url()), 此前 message=null → 调用方拿到 attachment 可注入 LLM.
                if (log.isWarnEnabled()) {
                    log.warn("HTTP hook '{}' body JSON 处理失败, 视为 non_blocking_error: {}",
                        hookName, e.getMessage());
                }
                return new GenericHook.HookResult(false, null, null, null,
                    AttachmentMessageDto.hookNonBlockingError(
                        hookName, toolUseId, expectedHookEvent,
                        "Failed to run: " + e.getMessage(), "", 1, hook.url(), null),
                    null, null, null, null,
                    GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, hook, null, null, null, null);
            }
        }
        return new GenericHook.HookResult(false, null, null, null, null, null, null,
            null, null, GenericHook.HookOutcome.SUCCESS, null, null, null, hook, null, null, null, null);
    }

    /**
     * [H7-v2] 4 参便捷重载 · toolUseId 缺省为 null (非工具事件 / 测试直接调用).
     *
     * <p>WHY: 生产接线 {@link #executeConfiguredHttp} 走 5 参 (传真实 ccName + toolUseId);
     * 既有测试 ({@code ExecHttpHookEndToEndTest.interpretBody}) 无工具上下文, 用本重载保持兼容.
     */
    static GenericHook.HookResult httpToHookResult(ExecHttpHook.HttpHookResult r, HttpHook hook,
                                                   String hookName, String expectedHookEvent) {
        return httpToHookResult(r, hook, hookName, expectedHookEvent, null);
    }

    /**
     * [H4] 配置驱动 hook 展示名 · 对齐 CC getHookDisplayText (hooksSettings.ts:68-90).
     *
     * <p>WHY: 日志关联用. command 保持 H2 的 {@code config-command:<command>} 格式不变;
     * prompt/agent/http 用 {@code config-<type>:<display>} 等价.
     */
    private static String hookNameFor(MatchedHook mh) {
        return switch (mh.hook().hookType()) {
            case COMMAND -> "config-command:" + ((CommandHook) mh.hook()).command();
            case PROMPT -> "config-prompt:" + ((PromptHook) mh.hook()).prompt();
            case AGENT -> "config-agent:" + ((AgentHook) mh.hook()).prompt();
            case HTTP -> "config-http:" + ((HttpHook) mh.hook()).url();
        };
    }

    // ════════════════════════════════════════════════════════════════════════
    // [H5-GAP-1] Session hook 执行 · 对齐 CC getAllHooks 合并匹配集 + executeHooks
    // ════════════════════════════════════════════════════════════════════════

    /**
     * [H5-GAP-1 + IMPL-07 OD-11] 执行当前事件的 session 作用域<b>function</b> hook ·
     * 对齐 CC {@code getHooksConfig} (hooks.ts:1500-1560) + {@code executeHooks}
     * (hooks.ts:2142-2744).
     *
     * <p><b>[IMPL-07 OD-11] 范围收窄</b>: session <b>command/prompt/agent/http</b> hooks
     * 已并入统一匹配链 ({@link #getMatchingHooks(HookEvent)} → engine 去重, settings 与
     * session 同命令折叠) 并由 {@link #executeConfiguredHooks} 执行 — 本方法只保留
     * <b>function hooks</b> (内存回调, 无法持久化/去重, CC getSessionFunctionHooks
     * L345-392 独立查询).
     *
     * <p>WHY (H5-GAP-1 登记 J.md §J.2.1): SessionHookStore 是惰性存储, 此前的 executeEvent 只
     * 消费 settings 持久化 {@link #genericHooks}, session hooks (addSessionHook/addFunctionHook
     * 注册的) 从不被检索/执行. CC 在 getHooksConfig 把 session hooks 并入匹配集再 getMatchingHooks
     * 过滤.
     *
     * @param event    hook 事件 (sessionId 空 → 无可执行 session hooks, 返回空)
     * @param messages 当前会话消息 (function hook callback 入参; null → 空列表)
     * @return 各 function hook 的 HookResult (未匹配/异常的 hook 被过滤或兜底 proceed)
     */
    private List<GenericHook.HookResult> executeSessionHooks(HookEvent event, List<ChatMessageDto> messages) {
        // [IMPL-01 D1-2 / INV-2 / OD-10] managedOnly 门控: allowManagedHooksOnly 时 session
        //   hooks 全部跳过（含 function hooks）· 对齐 CC getHooksConfig hooks.ts:1534-1540
        //   （"Skip session hooks entirely when allowManagedHooksOnly is set"）。
        //   方向修正: 旧实现快照杀 settings、session 分链照跑（EV-L01-027）;
        //   现在 settings 配置 hook 经快照保留（policy hooks），session 分链跳过。
        if (shouldAllowManagedHooksOnly()) {
            if (log.isDebugEnabled()) {
                log.debug("executeSessionHooks: allowManagedHooksOnly=true, 跳过全部 session hooks (sessionId={}, 事件 {})",
                    event.sessionId(), event.type());
            }
            return List.of();
        }
        // [IMPL-04 D9-1 / INV-9 / OD-13] trust 门控: session 作用域 hook 同要求 workspace trust ·
        //   对齐 CC getMatchingHooks 合并链 (hooksSettings.ts:146-158) → executeHooks 入口
        //   trust 门控 (hooks.ts:1994). 防御纵深: 即使未来出现不经 executeEvent 的新调用方,
        //   本入口仍独立门控.
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("executeSessionHooks: workspace trust 未接受, 跳过全部 session hooks (sessionId={}, 事件 {})",
                    event.sessionId(), event.type());
            }
            return List.of();
        }
        // [EX-HOOK R7 修正] 匹配 key = agentId ?? sessionId（CC hooks.ts:2003 语义，
        //   同 sessionCommandMatched；事件 sessionId 是载荷主会话）。
        String sessionId = event.agentId() != null && !event.agentId().isBlank()
            ? event.agentId()
            : event.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        HookMatcherEngine engine = this.hookMatcherEngine;
        String matchQuery = engine != null ? engine.matchQueryFor(event) : null;
        List<GenericHook.HookResult> results = new ArrayList<>();

        // ── function hooks (CC getSessionFunctionHooks L345-392) ──
        Map<HookEventType, List<SessionHookStore.FunctionHookMatcher>> functionHooks =
                sessionHookStore.getSessionFunctionHooks(sessionId, event.type());
        for (Map.Entry<HookEventType, List<SessionHookStore.FunctionHookMatcher>> e : functionHooks.entrySet()) {
            for (SessionHookStore.FunctionHookMatcher m : e.getValue()) {
                if (m.hooks() == null || m.hooks().isEmpty()) {
                    continue;
                }
                if (engine != null && !engine.matchesSessionMatcher(event, m.matcher())) {
                    if (log.isDebugEnabled()) {
                        log.debug("session function hook 跳过 (matcher 不匹配): matcher={} matchQuery={}",
                                m.matcher(), matchQuery);
                    }
                    continue;
                }
                for (FunctionHook hook : m.hooks()) {
                    GenericHook.HookResult result = executeSessionFunctionHook(event, hook, messages);
                    results.add(result);
                    invokeSessionHookOnSuccess(sessionId, event, matchQuery, hook, result);
                }
            }
        }
        return results;
    }

    /**
     * [H5-GAP-1] 执行单个 function hook · 对齐 CC executeFunctionHook (hooks.ts:4740-4830).
     *
     * <p>WHY (规则三 · CC 怎么复杂我们怎么复杂): CC function hook 是 session 内存回调, 执行语义:
     * <ol>
     *   <li>callback 超时上限 = {@code hook.timeout ?? timeoutMs} (hooks.ts:4754; Java 端
     *       {@link FunctionHook#timeout()} 注册时已缺省 5000)</li>
     *   <li>callback 返回 true → success (放行); false → blocking (blockingError =
     *       {@code {blockingError: hook.errorMessage, command: 'function'}}, hooks.ts:4792-4797)</li>
     *   <li>超时/取消 → cancelled (hooks.ts:4810-4815 AbortError); 其他异常 → non_blocking_error
     *       (hooks.ts:4818-4826, 不阻断)</li>
     * </ol>
     * <b>signal (D-06)</b>: CC 以 {@code createCombinedAbortSignal} (hooks.ts:4758) 给回调
     * 传 AbortSignal, 超时/取消时 abort → 回调可感知并提前停止. Java 端以
     * {@link com.nexusai.application.agent.tool.AbortController} 承载该信号: 每次执行新建
     * (无父 signal 可组合), 超时路径先 {@code signal.abort("timeout")} 再返回 CANCELLED,
     * 回调经 {@code isCancelled()/onCancel} 观察取消.
     *
     * @param event    hook 事件
     * @param hook     FunctionHook (sessionHooks.ts:24-31)
     * @param messages 会话消息 (CC Message[]; null → 空列表)
     * @return HookResult (blocking / success / cancelled / non_blocking_error)
     */
    private GenericHook.HookResult executeSessionFunctionHook(HookEvent event, FunctionHook hook,
                                                               List<ChatMessageDto> messages) {
        if (hook.callback() == null) {
            // 无回调无法校验 → 非阻断错误, 对齐 CC executeFunctionHook 缺 messages/异常分支
            if (log.isWarnEnabled()) {
                log.warn("session function hook 无 callback, 跳过 (id={})", hook.id());
            }
            return new GenericHook.HookResult(false, null, null, null, null, null, null,
                    null, null, GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
        }
        List<ChatMessageDto> effectiveMessages = messages != null ? messages : List.of();
        long callbackTimeoutMs = hook.timeout() > 0 ? hook.timeout() : FunctionHook.DEFAULT_TIMEOUT_MS;
        // D-06: 本次执行的取消信号 (CC createCombinedAbortSignal 等价物; 当前无父 signal,
        // 新建即未取消, 仅承载超时取消) — 回调经 isCancelled()/onCancel 感知
        com.nexusai.application.agent.tool.AbortController signal =
            new com.nexusai.application.agent.tool.AbortController();
        // CC :4764 已 abort 预检 → cancelled (结构对齐; 新建控制器恒未取消, 保留语义位置)
        if (signal.isCancelled()) {
            return new GenericHook.HookResult(false, null, null, null, null, null, null,
                    null, null, GenericHook.HookOutcome.CANCELLED, null, null, null, null, null, null, null, null);
        }
        try {
            CompletableFuture<Boolean> future = hook.callback().apply(effectiveMessages, signal);
            Boolean passed = future.get(callbackTimeoutMs, TimeUnit.MILLISECONDS);
            if (Boolean.TRUE.equals(passed)) {
                if (log.isDebugEnabled()) {
                    log.debug("session function hook 通过: id={} event={}", hook.id(), event.type());
                }
                return GenericHook.HookResult.proceed();
            }
            // callback 返回 false → blocking (hooks.ts:4791-4797) · command='function'
            if (log.isInfoEnabled()) {
                log.info("session function hook 拦截: id={} errorMessage={}", hook.id(), hook.errorMessage());
            }
            return new GenericHook.HookResult(true,
                    new HookBlockingError(hook.errorMessage() != null ? hook.errorMessage() : "Function hook blocked", "function"),
                    null, null, null, null, null, null, null,
                    GenericHook.HookOutcome.BLOCKING, null, null, null, null, null, null, null, null);
        } catch (TimeoutException te) {
            // D-06: 超时先 abort signal (对齐 CC combined 超时取消, 使回调可观察取消) 再返回
            // cancelled (hooks.ts:4810-4815 AbortError → cancelled)
            signal.abort("timeout");
            if (log.isWarnEnabled()) {
                log.warn("session function hook 超时取消: id={} timeout={}ms", hook.id(), callbackTimeoutMs);
            }
            return new GenericHook.HookResult(false, null, null, null, null, null, null,
                    null, null, GenericHook.HookOutcome.CANCELLED, null, null, null, null, null, null, null, null);
        } catch (Exception e) {
            // 回调异常 → non_blocking_error, 不阻断 (hooks.ts:4818-4826)
            if (log.isWarnEnabled()) {
                log.warn("session function hook 执行异常, 不阻断: id={} err={}", hook.id(), e.toString());
            }
            return new GenericHook.HookResult(false, null, null, null, null, null, null,
                    null, null, GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * [H5-GAP-1] 执行后触发 session hook 的 onHookSuccess · 对齐 CC hooks.ts:2906-2925.
     *
     * <p>WHY: CC executeHooks 每个 command/prompt/function hook 执行成功后, 用
     * {@code getSessionHookCallback} (sessionHooks.ts:397-430) 取完整 entry, 若
     * {@code onHookSuccess && result.outcome === 'success'} 则调用 onHookSuccess (hooks.ts:2911-2925).
     * Java 端 session hook 的成功通知链路此前断开 — 本方法补上, 让调用方 (如 sub-agent 校验)
     * 在 hook 成功时可收到通知.
     *
     * @param sessionId 会话 ID
     * @param event     hook 事件
     * @param matchQuery 事件 matchQuery (CC {@code matchQuery ?? ''}, hooks.ts:2909)
     * @param hook      已执行的 session hook (command 或 function)
     * @param result    执行结果 (仅 outcome=success 触发)
     */
    private void invokeSessionHookOnSuccess(String sessionId, HookEvent event, String matchQuery,
                                            SessionHook hook, GenericHook.HookResult result) {
        if (result == null || result.outcome() != GenericHook.HookOutcome.SUCCESS) {
            return;
        }
        String matcher = matchQuery != null ? matchQuery : "";
        Optional<SessionHookStore.SessionHookEntry> entry =
                sessionHookStore.getSessionHookCallback(sessionId, event.type(), matcher, hook);
        if (entry.isEmpty() || entry.get().onHookSuccess() == null) {
            return;
        }
        try {
            entry.get().onHookSuccess().onSuccess(hook, toAggregatedHookResult(result));
            if (log.isDebugEnabled()) {
                log.debug("session hook onHookSuccess 已触发: session={} event={} type={}",
                        sessionId, event.type(), hook.type());
            }
        } catch (Exception e) {
            // CC hooks.ts:2919-2923 catch → 只记录, 不阻断
            if (log.isWarnEnabled()) {
                log.warn("session hook onHookSuccess 回调失败: {}", e.getMessage());
            }
        }
    }

    /**
     * [H5-GAP-1] HookResult → 16 字段 AggregatedHookResult · 镜像
     * {@code PermissionDeniedHookExecutor.toAggregated} (缺失字段保持 null/false).
     */
    private static AggregatedHookResult toAggregatedHookResult(GenericHook.HookResult r) {
        if (r == null) {
            return AggregatedHookResult.proceed();
        }
        Map<String, Object> effectiveUpdatedInput = r.updatedInput() instanceof Map
                ? (Map<String, Object>) r.updatedInput() : null;
        // [IMPL-07 OD-14] message 转换边界: AttachmentMessageDto 通道 (旧 instanceof String 截断)
        //   String 消息包装为 hook_user_message (无 hook 上下文 → null hookName, 语义与
        //   PermissionDeniedHookExecutor.toAggregated 一致)
        return new AggregatedHookResult(
                AggregatedHookResult.messageChannel(r.message(), null, null, null),
                r.blockingError(),
                r.preventContinuation(),
                r.stopReason(),
                r.hookPermissionDecisionReason(),
                null,
                null,
                r.additionalContexts(),
                null,
                effectiveUpdatedInput,
                r.updatedMCPToolOutput(),
                null,
                null,
                null,
                null,
                r.retry());
    }

    /**
     * [H7-v3 Gap①] 是否非阻断结果 · outcome ∈ {CANCELLED, NON_BLOCKING_ERROR}.
     *
     * <p>WHY: executeEvent 聚合层用 outcome 判别"该结果承载非阻断语义" (对齐 CC
     * hooks.ts:2796 message yield + :342 outcome union). 用 outcome 而非 message 判别,
     * 因为 wrong-event-name 路径 (processHookJSONOutput 抛异常被 catch) 只设 outcome
     * NON_BLOCKING_ERROR 而 message=null (H7-GAP-3 fail-loud) — 仅按 message 折叠会漏掉
     * 该结果, ccName 接线回归测试无法观测.
     *
     * @param r hook 结果 (null → false)
     * @return true = 非阻断 (CANCELLED / NON_BLOCKING_ERROR), 应折叠进聚合结果供注入 LLM
     */
    private static boolean isNonBlockingOutcome(GenericHook.HookResult r) {
        if (r == null) {
            return false;
        }
        return r.outcome() == GenericHook.HookOutcome.CANCELLED
            || r.outcome() == GenericHook.HookOutcome.NON_BLOCKING_ERROR;
    }

    /**
     * [H2] executeEvent 末尾聚合解析 · 从 firstStop/firstBlockingError/firstRetry/
     * firstMessageResult 收集器解析最终 {@link GenericHook.HookResult}.
     *
     * <p>[H7-v3 Gap①] 新增 firstMessageResult 参数: 承载非阻断 message attachment
     * (httpToHookResult 对 aborted/error/validationError 产的 hook_cancelled /
     * hook_non_blocking_error). v2 前只折叠 stop/blockingError/retry, 非阻断 message 被
     * 静默丢弃 (v2 对抗复验 PARTIAL). v3 修复: 无 stop/blockingError/retry 时, 首个带
     * message 的结果原样返回 (保留其 outcome NON_BLOCKING_ERROR/CANCELLED + message +
     * hook 字段), 让调用方 (Stop 路径 / 通用事件消费者) 能把它注入 LLM.
     *
     * <p>优先级 (对齐 CC executeHooks 各 yield): firstStop 阻断语义 > firstBlockingError >
     * firstRetry > firstMessageResult 透传 > proceed. 原内联逻辑 (R30-P0-1 / R32-b13 B9)
     * 行为不变, 仅新增 message 通道.
     */
    private static GenericHook.HookResult resolveEventResult(GenericHook.HookResult firstStop,
                                                             HookBlockingError firstBlockingError,
                                                             GenericHook.HookResult firstBlockingResult,
                                                             Boolean firstRetry,
                                                             GenericHook.HookResult firstMessageResult,
                                                             PermissionRequestResult firstPermissionRequestResult) {
        if (firstStop == null && firstBlockingError == null && firstRetry == null
            && firstMessageResult == null && firstPermissionRequestResult == null) {
            return GenericHook.HookResult.proceed();
        }
        if (firstStop != null && (firstStop.blockingError() != null || firstBlockingError == null)) {
            // [Session S07] 首个带 permissionRequestResult 的结果可能是另一个 hook —
            //   返回 firstStop 前用 wither 补填, 保证 PermissionRequest 决策不随折叠丢失.
            return applyPermissionRequestResult(firstStop, firstPermissionRequestResult);
        }
        if (firstStop != null) {
            if (log.isInfoEnabled()) {
                String preview = firstBlockingError != null && firstBlockingError.blockingError() != null
                    && firstBlockingError.blockingError().length() > 100
                    ? firstBlockingError.blockingError().substring(0, 100) + "..."
                    : (firstBlockingError != null && firstBlockingError.blockingError() != null
                        ? firstBlockingError.blockingError() : "");
                log.info("[R30-P0-1] executeEvent: 合并 firstStop + firstBlockingError（不同 hook 提供）: "
                    + "reason={} blockingError={}",
                    firstStop.stopReason(),
                    preview);
            }
            // [H7-v3 Gap①] firstStop 无 message 时补带 firstMessageResult 的 message
            // [H3 v4 Gap①] 额外补 firstBlockingResult 的 message（blockingError hook 的 hook_blocking_error）
            Object message = firstStop.message() != null ? firstStop.message()
                : (firstBlockingResult != null && firstBlockingResult.message() != null
                    ? firstBlockingResult.message()
                    : (firstMessageResult != null ? firstMessageResult.message() : null));
            // [IMPL-03 D6-1] 决策字段保留: permissionBehavior/hookPermissionDecisionReason/
            //   stopReason 随 firstStop (CC HookResult 全字段承载; 此前 permissionBehavior
            //   硬编码 null → 配置 hook deny/allow 决策在聚合折叠时丢失, 工具链不可见).
            // [2026-08-12 △-01] awaiting 4 字段随 firstStop 保留 (CC executeHooks 逐 result
            //   yield initialUserMessage/watchPaths/elicitation* hooks.ts:2780-2810).
            return new GenericHook.HookResult(firstStop.preventContinuation(),
            firstBlockingError,
            firstStop.systemMessages(),
            firstStop.additionalContexts(),
            message,
            firstStop.updatedInput(),
            firstStop.updatedMCPToolOutput(),
            firstRetry,
            firstStop.hookPermissionDecisionReason(),
            // [Session I P3-1 + M.2.2] 3 字段扩展 · CC HookOutcome + stopReason + permissionBehavior
            GenericHook.HookOutcome.SUCCESS, firstStop.stopReason(),
            firstStop.permissionBehavior(), firstPermissionRequestResult, firstStop.hook(),
            firstStop.initialUserMessage(), firstStop.watchPaths(),
            firstStop.elicitationResponse(), firstStop.elicitationResultResponse());
        }
        if (firstBlockingError != null) {
            if (log.isInfoEnabled()) {
                String preview = firstBlockingError.blockingError() != null
                    && firstBlockingError.blockingError().length() > 100
                    ? firstBlockingError.blockingError().substring(0, 100) + "..."
                    : (firstBlockingError.blockingError() != null ? firstBlockingError.blockingError() : "");
                log.info("[R30-P0-1] executeEvent: 仅 blockingError 无 preventContinuation → 兜底返回 stop 占位: {}",
                    preview);
            }
            // [H7-v3 Gap①] blockingError 兜底 stop 时携带非阻断 message (不丢弃)
            // [H3 v4 Gap①] 优先保留同一 blockingError 结果的 message attachment
            //   (hook_blocking_error, processHookJSONOutput hooks.ts:710-715 生成) — 此前
            //   firstMessageResult 只折叠非阻断 outcome (CANCELLED/NON_BLOCKING_ERROR), blockingError
            //   结果 (outcome=SUCCESS + blockingError) 的 message 被丢弃, 非工具事件消费者拿不到
            //   hook_blocking_error attachment 注入 LLM.
            Object blockingMessage = firstBlockingResult != null && firstBlockingResult.message() != null
                ? firstBlockingResult.message()
                : (firstMessageResult != null ? firstMessageResult.message() : null);
            GenericHook.HookResult stop = GenericHook.HookResult.stop(null,
                firstBlockingError.blockingError() != null ? firstBlockingError.blockingError() : null);
            if (blockingMessage != null) {
                return new GenericHook.HookResult(stop.preventContinuation(), stop.blockingError(),
                    stop.systemMessages(), stop.additionalContexts(), blockingMessage,
                    stop.updatedInput(), stop.updatedMCPToolOutput(), stop.retry(),
                    // [IMPL-03 D6-1] blockingError 结果携带的决策字段保留 (permissionBehavior/
                    //   hookPermissionDecisionReason/stopReason 随 firstBlockingResult 原值,
                    //   此前折叠为 stop 占位时全部丢失 → 配置 hook deny 决策不达工具链)
                    firstBlockingResult != null ? firstBlockingResult.hookPermissionDecisionReason() : null,
                    stop.outcome(),
                    firstBlockingResult != null ? firstBlockingResult.stopReason() : null,
                    firstBlockingResult != null ? firstBlockingResult.permissionBehavior() : null,
                    firstPermissionRequestResult, stop.hook(),
                    // [2026-08-12 △-01] awaiting 4 字段随 firstBlockingResult 保留
                    firstBlockingResult != null ? firstBlockingResult.initialUserMessage() : null,
                    firstBlockingResult != null ? firstBlockingResult.watchPaths() : null,
                    firstBlockingResult != null ? firstBlockingResult.elicitationResponse() : null,
                    firstBlockingResult != null ? firstBlockingResult.elicitationResultResponse() : null);
            }
            // 无 message 时同样保留决策字段 (构造等价 stop + 决策字段)
            return new GenericHook.HookResult(stop.preventContinuation(), stop.blockingError(),
                stop.systemMessages(), stop.additionalContexts(), null,
                stop.updatedInput(), stop.updatedMCPToolOutput(), stop.retry(),
                firstBlockingResult != null ? firstBlockingResult.hookPermissionDecisionReason() : null,
                stop.outcome(),
                firstBlockingResult != null ? firstBlockingResult.stopReason() : null,
                firstBlockingResult != null ? firstBlockingResult.permissionBehavior() : null,
                firstPermissionRequestResult, stop.hook(),
                firstBlockingResult != null ? firstBlockingResult.initialUserMessage() : null,
                firstBlockingResult != null ? firstBlockingResult.watchPaths() : null,
                firstBlockingResult != null ? firstBlockingResult.elicitationResponse() : null,
                firstBlockingResult != null ? firstBlockingResult.elicitationResultResponse() : null);
        }
        if (firstRetry != null) {
            if (log.isInfoEnabled()) {
                log.info("[R32-b13 B9] executeEvent: 仅 retry=true 无 stop/blockingError → 传递 withRetry() flag: {}",
                    firstRetry);
            }
            GenericHook.HookResult retry = GenericHook.HookResult.withRetry();
            if (firstMessageResult != null && firstMessageResult.message() != null) {
                return new GenericHook.HookResult(retry.preventContinuation(), retry.blockingError(),
                    retry.systemMessages(), retry.additionalContexts(), firstMessageResult.message(),
                    retry.updatedInput(), retry.updatedMCPToolOutput(), retry.retry(),
                    retry.hookPermissionDecisionReason(), retry.outcome(), retry.stopReason(),
                    retry.permissionBehavior(), firstPermissionRequestResult, retry.hook(),
                    // [2026-08-12 △-01] awaiting 4 字段随 firstMessageResult 保留
                    firstMessageResult.initialUserMessage(), firstMessageResult.watchPaths(),
                    firstMessageResult.elicitationResponse(), firstMessageResult.elicitationResultResponse());
            }
            return retry;
        }
        // [H7-v3 Gap① + H3 v4 Gap①] 仅 message (无 stop/blockingError/retry) → 原样透传首个带 message 的结果
        //   H3 v4 扩展到 SUCCESS 的 hook_success: 非工具事件消费者需要 hook_success/hook_blocking_error
        //   attachment 注入 LLM (CC executeHooks yield message 对齐), 不能只折叠非阻断 outcome.
        if (firstMessageResult != null) {
            if (log.isInfoEnabled()) {
                log.info("[H7-v3 Gap①] executeEvent: 仅 message attachment (无 stop/blockingError/retry), "
                    + "透传首个带 message 结果, outcome={}", firstMessageResult.outcome());
            }
            return applyPermissionRequestResult(firstMessageResult, firstPermissionRequestResult);
        }
        if (firstPermissionRequestResult != null) {
            // [Session S07] 仅 PermissionRequest 决策 (无 stop/blockingError/retry/message) →
            //   以 proceed 为基座回填决策 (CC hooks.ts:2882-2886 yield { permissionRequestResult })
            if (log.isDebugEnabled()) {
                log.debug("executeEvent: 仅 permissionRequestResult, 回填折叠结果");
            }
            return GenericHook.HookResult.proceed()
                .withPermissionRequestResult(firstPermissionRequestResult);
        }
        return GenericHook.HookResult.proceed();
    }

    /**
     * [Session S07] 折叠结果补填 permissionRequestResult · 对齐 CC hooks.ts:2882-2886.
     *
     * <p>WHY: 多 hook 并行折叠时, 首个带 permissionRequestResult 的结果 (决定权) 可能
     * 与选中的折叠结果 (firstStop/firstMessageResult) 不是同一结果 — 用 wither 补填,
     * 保证 PermissionRequest 消费方 (coordinator/interactive runHooks 等价) 从折叠结果
     * 顶层总能读到决策. 结果已携带则原样返回 (首非空语义).
     */
    private static GenericHook.HookResult applyPermissionRequestResult(
            GenericHook.HookResult result, PermissionRequestResult prr) {
        if (prr == null || result.permissionRequestResult() != null) {
            return result;
        }
        return result.withPermissionRequestResult(prr);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 公共事件 fire 入口
    // ════════════════════════════════════════════════════════════════════════

    public void fireFileChanged(String path, String event, String sessionId) {
        HookEvent hookEvent = HookEvent.fileChanged(path, event, sessionId);
        executeEvent(hookEvent);
    }

    /**
     * [H14 v3 Gap④] fireFileChanged + watchPaths 收集 · 对齐 CC executeFileChangedHooks
     * (hooks.ts:4246-4264) 返回 {@code {results, watchPaths, systemMessages}}.
     *
     * <p>WHY: FileChangedWatcher.handleFileEvent 需要 hook 结果中的 watchPaths 动态扩展监听
     * (CC fileChangedWatcher.ts:86-89)。原 fireFileChanged 只 executeEvent 丢弃 watchPaths。
     * 本方法执行同事件 + 收集 watchPaths（供 watcher updateWatchPaths）。
     *
     * @return hook 结果聚合的 watchPaths (空 = 无动态监听路径)
     */
    public FileChangedWatcher.EnvHookResult fireFileChangedCollectingWatchPaths(String path, String event, String sessionId) {
        HookEvent hookEvent = HookEvent.fileChanged(path, event, sessionId);
        return executeEnvHookCollectingWatchPaths(hookEvent);
    }

    /**
     * [H14 v3 Gap④] CwdChanged hooks + watchPaths 收集 · 对齐 CC executeCwdChangedHooks
     * (hooks.ts:4266-4285) 返回 {@code {results, watchPaths, systemMessages}}.
     *
     * <p>WHY: FileChangedWatcher.onCwdChangedForHooks 需要 hook 结果中的 watchPaths 存为
     * dynamicWatchPaths (CC fileChangedWatcher.ts:160-161)。原 executeEvent(cwdChanged) 丢弃
     * watchPaths。本方法执行同事件 + 收集 watchPaths。
     *
     * @return hook 结果聚合的 watchPaths (空 = 无动态监听路径)
     */
    public FileChangedWatcher.EnvHookResult executeCwdChangedHooksCollectingWatchPaths(String oldCwd, String newCwd,
                                                                                       String sessionId) {
        HookEvent hookEvent = HookEvent.cwdChanged(oldCwd, newCwd, sessionId);
        return executeEnvHookCollectingWatchPaths(hookEvent);
    }

    /**
     * [H14 v3 Gap④ + CCJ-EXEC-05] 执行 env hook 事件 + 聚合结果.
     *
     * <p>遍历配置驱动 hooks (CwdChanged/FileChanged 事件只走配置驱动 hook，CC 的
     * executeCwdChangedHooks/executeFileChangedHooks 亦如此)，把每个 hook 结果
     * hookSpecificOutput.watchPaths (hooks.ts:630-635) 聚合 + systemMessage +
     * 失败输出（CC hooks.ts:4253-4257 executeEnvHooks → {results, watchPaths,
     * systemMessages}；failureOutputs 为 CC fileChangedWatcher.ts:94-97
     * {@code !r.succeeded && r.output} 的 Java 映射）。programmatic generic hooks
     * 不参与 (env hook 无 generic 注册路径)。
     *
     * @return {@link FileChangedWatcher.EnvHookResult}（watchPaths/systemMessages/failureOutputs）
     */
    private FileChangedWatcher.EnvHookResult executeEnvHookCollectingWatchPaths(HookEvent hookEvent) {
        // [IMPL-01 D1-1 / INV-1] 政策闸门: env hook (CwdChanged/FileChanged) 同走
        //   executeHooksOutsideREPL 等价链 (对齐 CC hooks.ts:4249 executeEnvHooks →
        //   executeHooksOutsideREPL, 入口短路 :3022-3027), 禁用时同样早返空.
        //   旧实现此路径绕过 disableAllHooks — 留门即留执行通道.
        if (shouldDisableAllHooksIncludingManaged()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEnvHookCollectingWatchPaths: policySettings.disableAllHooks=true, 跳过 env hook (事件 {})",
                    hookEvent.type());
            }
            return FileChangedWatcher.EnvHookResult.empty();
        }
        // [IMPL-04 D9-1 / INV-9 / OD-13] trust 门控: env hook (CwdChanged/FileChanged) 同走
        //   executeHooksOutsideREPL 等价链 (对齐 CC hooks.ts:4249 executeEnvHooks →
        //   executeHooksOutsideREPL, 入口 trust 门控 :3031), 交互模式未接受 trust 同样跳过.
        if (shouldSkipHookDueToTrust()) {
            if (log.isDebugEnabled()) {
                log.debug("executeEnvHookCollectingWatchPaths: workspace trust 未接受, 跳过 env hook (事件 {})",
                    hookEvent.type());
            }
            return FileChangedWatcher.EnvHookResult.empty();
        }
        java.util.List<String> watchPaths = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<MatchedHook> matched = getMatchingHooks(hookEvent);
        if (matched == null || matched.isEmpty()) {
            return FileChangedWatcher.EnvHookResult.empty();
        }
        String jsonInput = CommandHookExecutor.buildJsonInput(hookEvent);
        java.util.List<CompletableFuture<GenericHook.HookResult>> futures = new java.util.ArrayList<>(matched.size());
        for (int i = 0; i < matched.size(); i++) {
            MatchedHook mh = matched.get(i);
            // [ALIGN-HOOKS-2] hookIndex = 事件匹配列表位置 · 同 executeConfiguredHooks
            //   (CC :3084-3085 map index → :3293 → :925 CLAUDE_ENV_FILE)
            int hookIndex = i;
            futures.add(CompletableFuture.supplyAsync(withSessionProjectRoot(
                () -> executeOneConfiguredHookCollecting(
                    hookEvent, mh, jsonInput, watchPaths, null, hookIndex)), HOOK_EXECUTOR));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof AbortException ae) {
                throw ae;
            }
            log.warn("env hook 并行等待异常(非 abort): {}",
                cause != null ? cause.toString() : ce.toString());
        }
        // [S4 G14 一致化] env hook 是 outside-REPL 链 (executeHooksOutsideREPL :
        //   `return await Promise.all(hookPromises)` hooks.ts:3380) — 注册序收集
        //   (futures.get(i).join), 非完成序. 旧 synchronizedList 插入序为并发不确定序,
        //   改注册序确定性对齐 CC (systemMessages/failureOutputs 顺序可观测).
        java.util.List<GenericHook.HookResult> hookResults = new java.util.ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            GenericHook.HookResult r = futures.get(i).join();
            if (r != null) {
                hookResults.add(r);
            }
        }
        // [CCJ-EXEC-05] CC hooks.ts:4254-4257 — systemMessages = results.map(r => r.systemMessage).filter(!!)
        java.util.List<String> systemMessages = new java.util.ArrayList<>();
        java.util.List<String> failureOutputs = new java.util.ArrayList<>();
        for (GenericHook.HookResult r : hookResults) {
            if (r.systemMessages() != null) {
                systemMessages.addAll(r.systemMessages());
            }
            String failureOutput = failureOutputOf(r);
            if (failureOutput != null) {
                failureOutputs.add(failureOutput);
            }
        }
        return new FileChangedWatcher.EnvHookResult(watchPaths, systemMessages, failureOutputs);
    }

    /**
     * [CCJ-EXEC-05] 失败结果输出提取 · CC fileChangedWatcher.ts:94-97
     * {@code !r.succeeded && r.output} 的 Java 映射：outcome!=SUCCESS 且输出非空才通知。
     * 输出取值优先级：blockingError 文本（CC blocking 路径 output=stderr 语义）→
     * attachment stderr → attachment stdout。
     *
     * @return 通知文本；null = 不通知（成功结果 / 无输出）
     */
    private static String failureOutputOf(GenericHook.HookResult r) {
        if (r == null || r.outcome() == GenericHook.HookOutcome.SUCCESS) {
            return null;
        }
        if (r.blockingError() != null && r.blockingError().blockingError() != null
                && !r.blockingError().blockingError().isBlank()) {
            return r.blockingError().blockingError();
        }
        if (r.message() instanceof AttachmentMessageDto att) {
            if (att.stderr() != null && !att.stderr().isBlank()) {
                return att.stderr();
            }
            if (att.stdout() != null && !att.stdout().isBlank()) {
                return att.stdout();
            }
        }
        return null;
    }

    /**
     * [H14 v3 Gap④] 单个配置驱动 hook 执行 + watchPaths 收集.
     *
     * <p>与 {@link #executeOneConfiguredHook} 同分发逻辑，仅对 command hook 把 watchPathsOut
     * 透传给 {@link CommandHookExecutor#toHookResult(CommandHookResult, CommandHook, String, List)}。
     */
    private GenericHook.HookResult executeOneConfiguredHookCollecting(HookEvent event, MatchedHook mh,
                                                                       String jsonInput,
                                                                       java.util.List<String> watchPathsOut,
                                                                       com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                                       int hookIndex) {
        HookCommand hook = mh.hook();
        // [S4 G07] outside-REPL 事件 prompt/agent 拒绝 · 同 executeOneConfiguredHook
        //   (CwdChanged/FileChanged 属 outside-REPL 13 事件, CC :3152-3170).
        if ((hook instanceof PromptHook || hook instanceof AgentHook) && isOutsideReplEvent(event.type())) {
            if (log.isWarnEnabled()) {
                log.warn("HOOK prompt/agent hook '{}' 在 {} 事件下不执行: not yet supported outside REPL (对齐 CC :3152-3170)",
                    hookNameFor(mh), event.type());
            }
            return new GenericHook.HookResult(false, null, null, null, null, null, null, null, null,
                GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
        }
        try {
            if (hook instanceof CommandHook commandHook) {
                // [EX-HOOK R5] parentTuc 透传 · 同 executeOneConfiguredHook command 分支
                //   （执行前父 abort 检查，对齐 CC executeHooks 入口 signal.aborted 早返）
                return executeConfiguredCommandCollecting(event, mh, commandHook, jsonInput, watchPathsOut, parentTuc, hookIndex);
            }
            if (hook instanceof PromptHook promptHook) {
                return executeConfiguredPrompt(event, mh, promptHook, jsonInput, parentTuc);
            }
            if (hook instanceof AgentHook agentHook) {
                return executeConfiguredAgent(event, mh, agentHook, jsonInput, parentTuc);
            }
            if (hook instanceof HttpHook httpHook) {
                return executeConfiguredHttp(event, mh, httpHook, jsonInput, parentTuc);
            }
            return GenericHook.HookResult.proceed();
        } catch (AbortException ae) {
            log.warn("配置驱动 hook '{}' abort: {}", hookNameFor(mh), ae.getMessage());
            throw ae;
        } catch (Exception e) {
            // [S4] 同 executeOneConfiguredHook catch — CC runHook catch → non_blocking_error
            //   + hook_non_blocking_error attachment (旧 proceed 静默吞错已删).
            if (log.isWarnEnabled()) {
                log.warn("配置驱动 hook '{}' 执行失败, 视为 non_blocking_error: {}", hookNameFor(mh), e.toString());
            }
            return new GenericHook.HookResult(false, null, null, null,
                AttachmentMessageDto.hookNonBlockingError(
                    hookNameFor(mh), event.toolUseId(), event.type().ccName(),
                    "Failed to run: " + e.getMessage(), "", 1,
                    stopHookCommandOf(mh.hook()), 0L),
                null, null, null, null,
                GenericHook.HookOutcome.NON_BLOCKING_ERROR, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * [H14 v3 Gap④] CommandHook 执行 + watchPaths 收集 · 对齐 CC executeCwdChangedHooks /
     * executeFileChangedHooks 的 command 分支.
     *
     * <p>把 watchPathsOut 透传给 {@link CommandHookExecutor#toHookResult(CommandHookResult,
     * CommandHook, String, List)} — command hook stdout JSON 的
     * hookSpecificOutput.watchPaths (CwdChanged/FileChanged) 被收集。
     */
    private GenericHook.HookResult executeConfiguredCommandCollecting(HookEvent event, MatchedHook mh,
                                                                      CommandHook commandHook, String jsonInput,
                                                                      java.util.List<String> watchPathsOut,
                                                                      com.nexusai.application.agent.tool.ToolUseContext parentTuc,
                                                                      int hookIndex) {
        CommandHookExecutor executor = this.commandHookExecutor;
        if (executor == null) {
            return GenericHook.HookResult.proceed();
        }
        String hookName = "config-command:" + commandHook.command();
        // [EX-HOOK R5] 父 abort 透传 · 同 executeConfiguredCommand（对齐 CC executeHooks 入口
        //   if (signal?.aborted) return hooks.ts:2015-2017；运行时中止缺口同登记）
        com.nexusai.application.agent.tool.AbortController parentAbort =
            parentTuc != null ? parentTuc.abortController() : null;
        if (parentAbort != null && parentAbort.isCancelled()) {
            if (log.isDebugEnabled()) {
                log.debug("配置驱动 CommandHook '{}' 跳过: 父 abort 已取消 (对齐 CC executeHooks 入口 signal.aborted 早返)", hookName);
            }
            return GenericHook.HookResult.proceed();
        }
        // [S4 G09] spawn cwd = 会话 cwd (同 executeConfiguredCommand) · 13 参 execute 透传
        CommandHookExecutor.CommandHookResult execResult = executor.execute(
            commandHook, event, hookName, jsonInput,
            mh.pluginRoot(), mh.pluginId(), mh.skillRoot(), hookIndex, false,
            parentAbort, CommandHookExecutor.DEFAULT_HOOK_EXECUTION_TIMEOUT_MS,
            CommandHookExecutor.resolveSpawnCwd(event));
        return CommandHookExecutor.toHookResult(
            execResult, commandHook, event.type().ccName(), watchPathsOut);
    }

    // ════════════════════════════════════════════════════════════════════════
    // [R32-b13 B9] executePermissionDeniedRetryCheck
    // ════════════════════════════════════════════════════════════════════════

    public Boolean executePermissionDeniedRetryCheck(String toolName, String toolUseId,
                                                     JsonNode input, String reason,
                                                     ToolUseContext ctx) {
        synchronized (this) {
            if (genericHooks.isEmpty()) {
                return null;
            }
        }
        String sessionId = ctx != null && ctx.sessionId() != null
            ? ctx.sessionId() : null;
        String agentId = ctx != null && ctx.agentId() != null
            ? ctx.agentId().toString() : null;
        HookEvent event = HookEvent.permissionDenied(toolName, input, reason, toolUseId, sessionId, agentId);
        // [fix-ts04 IMPL-01 OD-TS04-01] PermissionDenied retry check ctx 在手 → 3 参透传批级
        //   (enrich 注入 agent_type/permission_mode, 对齐 CC executePermissionDeniedHooks agentInfo).
        GenericHook.HookResult result = executeEvent(event, null, ctx);
        if (result == null) {
            return null;
        }
        return result.retry();
    }


    // ════════════════════════════════════════════════════════════════════════
    // 查询 (测试 / 调试用)
    // ════════════════════════════════════════════════════════════════════════

    public synchronized int preToolUseCount() {
        return preToolUseHooks.size();
    }

    public synchronized int postToolUseCount() {
        return postToolUseHooks.size();
    }

    public synchronized int genericHookCount() {
        return genericHooks.size();
    }
    // [IMP-HOOKS-S6 ⊕2] preToolUseNames()/postToolUseNames() 已删除 — CC 无对应 API,
    //   纯日志辅助 (StreamingToolExecutor 数据流日志改用 decision 归因, 不再打印名单).

    public synchronized List<String> genericHookNames() {
        return List.copyOf(genericHooks.keySet());
    }

    /**
     * 早返查询: 是否有任一 hook 监听给定事件名 · 对齐 CC
     * {@code Open-ClaudeCode/src/utils/hooks.ts:1582-1593 hasHookForEvent}.
     *
     * <p>[IMPL-02 D2] CC 三源检查 (任一命中即 true, INV-3):
     * <ol>
     *   <li>config 源: {@link HooksConfigSnapshot#getHooksConfigFromSnapshot()} 中该事件
     *       的 matcher 列表非空 (CC {@code getHooksConfigFromSnapshot()?.[hookEvent]}
     *       hooks.ts:1587-1588)</li>
     *   <li>registered 源: {@link #hookEventFilters} programmatic 注册过滤器
     *       (CC {@code getRegisteredHooks()?.[hookEvent]} hooks.ts:1589-1590)</li>
     *   <li>session 源: {@link SessionHookStore} 中 sessionId 对应的会话内临时 hook
     *       (CC {@code appState?.sessionHooks.get(sessionId)?.hooks[hookEvent]}
     *       hooks.ts:1591)</li>
     * </ol>
     * 旧实现仅查 registered 源 (hookEventFilters), 导致 settings.json 配置的
     * PermissionDenied / StopFailure hook 永不触发 (EV-001/EV-L03-016).
     *
     * <p>CC 注释 (hooks.ts:1573-1576): 有意过度近似 (over-approximate) —— managed-only
     * 过滤或 matcher 模式匹配后续可能丢弃的 hook 也算 true; 误报只多走完整匹配路径,
     * 漏报会跳过 hook, 宁可 true. 本方法保持该语义: 只做存在性检查, 不做过滤.
     *
     * @param eventName CC 字符串事件名 (匹配 HookEventType 枚举 name, PascalCase/UPPER_SNAKE)
     * @param sessionId 会话 ID (CC original: sessionId, hooks.ts:1585); null = 无会话
     *                  上下文, 跳过 session 源 (对齐 CC appState undefined 时 :1591 短路)
     * @return true 表示至少有一个 hook 可能接收该事件
     */
    public synchronized boolean hasHookForEvent(String eventName, String sessionId) {
        if (eventName == null || eventName.isBlank()) {
            return false;
        }
        // [R32-c-1] 字符串 → HookEventType enum 映射. 调用方传 'PreToolUse' / 'PermissionDenied'
        // 等 PascalCase, 注册侧用 HookEventType.PRE_TOOL_USE 等 UPPER_SNAKE 枚举. Java 25
        // String.toLowerCase() 默认按 Unicode 规则折叠, 同时 'PERMISSION' vs 'Permission'
        // 结构不同 (下划线 vs camelCase) 单纯 equalsIgnoreCase 也不足以匹配. 这里显式把
        // 下划线格式 + PascalCase 都归一化到 enum name (UPPER_SNAKE) 再比.
        String normalizedEvent = normalizeEventName(eventName);
        if (normalizedEvent == null) {
            return false;
        }
        HookEventType target = null;
        for (HookEventType t : HookEventType.values()) {
            if (t.name().equals(normalizedEvent)) {
                target = t;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        // [IMPL-02] 源1: config 快照 (CC hooks.ts:1587-1588 getHooksConfigFromSnapshot()?.[hookEvent])
        HooksConfigSnapshot snapshot = this.hooksConfigSnapshot;
        if (snapshot != null) {
            List<HookMatcher> configMatchers = snapshot.getHooksConfigFromSnapshot().get(target);
            if (configMatchers != null && !configMatchers.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("hasHookForEvent: config 快照源命中 eventName={} matchers={}",
                        eventName, configMatchers.size());
                }
                return true;
            }
        }
        // [IMPL-02] 源2: registered programmatic filters (CC hooks.ts:1589-1590
        // getRegisteredHooks()?.[hookEvent])
        for (Map.Entry<String, Set<HookEventType>> entry : hookEventFilters.entrySet()) {
            Set<HookEventType> filter = entry.getValue();
            if (filter == null || filter.isEmpty() || filter.contains(target)) {
                if (log.isDebugEnabled()) {
                    log.debug("hasHookForEvent: registered 源命中 eventName={} hook={}",
                        eventName, entry.getKey());
                }
                return true;
            }
        }
        // [IMPL-02] 源3: session 源 (CC hooks.ts:1591
        // appState?.sessionHooks.get(sessionId)?.hooks[hookEvent])
        if (sessionId != null && !sessionId.isBlank()) {
            if (sessionHookStore.hasHooksForEvent(sessionId, target)) {
                if (log.isDebugEnabled()) {
                    log.debug("hasHookForEvent: session 源命中 eventName={} sessionId={}",
                        eventName, sessionId);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 把外部传入的事件名归一化到 {@link HookEventType#name()} (UPPER_SNAKE) 格式.
     *
     * <p>支持 PascalCase (CC 真源: "PreToolUse" / "PermissionDenied") + UPPER_SNAKE
     * (Java enum: "PRE_TOOL_USE" / "PERMISSION_DENIED") 两种命名风格. 转换规则:
     * 1. 将 PascalCase 拆字符: 在每个大写字母前插入下划线, 然后整体转 UPPER.
     * 2. UPPER_SNAKE 保持不变 (仅大写 + 已是下划线).
     * 3. Locale.ROOT 避免 JDK 25 默认 Locale 影响 (zh_CN 下 Unicode 折叠会变).
     */
    private static String normalizeEventName(String eventName) {
        if (eventName == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eventName.length(); i++) {
            char c = eventName.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && eventName.charAt(i - 1) != '_') {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }
}
