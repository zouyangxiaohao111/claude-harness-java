package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.bubble.BubblePermissionMode;
import com.nexusai.application.agent.permission.bubble.PermissionBubbleService;
import com.nexusai.application.agent.permission.bubble.SubagentPermissionContext;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.permission.classifier.DenialTracker;
import com.nexusai.application.agent.permission.classifier.SpeculativeClassifier;
import com.nexusai.application.agent.permission.hook.GenericHook;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.permission.hook.PermissionRequestResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Notification;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolDecisionInfo;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 工具调用权限门 · 对齐 CC {@code useCanUseTool.tsx:27-191} 三态分支.
 *
 * <h2>职责</h2>
 * <p>本类位于 {@link com.nexusai.application.agent.tool.StreamingToolExecutor} 与
 * {@link Tool#execute(ToolUseBlock, ToolUseContext)} 之间，作为"工具执行前"的统一闸门。
 * 它把 {@link PermissionPipeline#check} 输出的 {@link PermissionResult} 二次加工为
 * 3 态决策：
 * <ul>
 *   <li>{@link Decision#ALLOW} — 立即执行工具</li>
 *   <li>{@link Decision#DENY} — 阻断 + {@link ToolResult#error} 注入</li>
 *   <li>{@link Decision#ASK} — [Session H9] Ask 分发链 (对齐 CC useCanUseTool.tsx:93-169):
 *       coordinator worker (awaitAutomatedChecksBeforeDialog) → resolveIfAborted →
 *       swarm worker → interactive
 *       (同步阻塞调 {@link PermissionPrompter#prompt}，用户决策 Allow → ALLOW；Deny → DENY)</li>
 * </ul>
 *
 * <h2>为什么单独成类</h2>
 * <p>对齐 CC {@code useCanUseTool.tsx} 拆分：
 * <ul>
 *   <li>{@link PermissionPipeline} — 纯 10 层规则检查编排（无副作用）</li>
 *   <li>{@link ToolPermissionGate} — 编排后的副作用层（弹窗）</li>
 *   <li>{@link StreamingToolExecutor} — 执行流编排（并发 + gate 插入点）</li>
 * </ul>
 * 单独成类便于：
 * <ol>
 *   <li>单元测试只 mock PermissionPipeline + PermissionPrompter，无须拉起 Spring 上下文</li>
 *   <li>StreamingToolExecutor 通过构造器注入 gate，方便测试时换 mock</li>
 *   <li>Phase 3+ 加 sandbox / yolo classifier 等决策层时只扩展本类</li>
 * </ol>
 *
 * <h2>阻塞契约</h2>
 * <p>{@link Decision#ASK} 分支同步阻塞调用方线程，直到：
 * <ul>
 *   <li>用户在 {@link PermissionPrompter} 中回答（返回 Allow/Deny）</li>
 *   <li>超时 → 实现方负责降级为 Deny（{@link WebSocketPermissionPrompter} 默认 30s）</li>
 *   <li>线程中断 → 实现方负责降级为 Deny（CLAUDE.md 规则十二）</li>
 * </ul>
 *
 * <h2>local-only 约束</h2>
 * <p>本类不持有任何 token usage / cost 数据（BudgetTracker 红线），
 * 不会上传到 server / 写入 DTO / 出现在日志 payload。
 *
 * @see PermissionPipeline
 * @see PermissionPrompter
 * @see com.nexusai.application.agent.tool.StreamingToolExecutor
 */
@Component
public class ToolPermissionGate {

    /**
     * 3 态决策 · 对齐 CC {@code useCanUseTool.tsx:27-191} 的 switch (decision)。
     */
    public enum Decision {
        /** 允许执行。 */
        ALLOW,
        /** 拒绝执行 + ToolResult.error 注入 LLM。 */
        DENY,
        /**
         * 询问用户。本枚举内部使用 — {@link #check} 会同步阻塞
         * {@link PermissionPrompter#prompt} 后转为 ALLOW 或 DENY，
         * 不会真正返回 ASK 给调用方。
         */
        ASK
    }

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionGate.class);

    /** JsonNode → Map 转换 (分发链入参) · 静态实例 (线程安全). */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * [perm-timeout] swarm leader 等待有界兜底 · 30s。
     *
     * <p>仅当 {@code abortController == null}（测试直构 / 无 ctx）时使用，防无 abort 信号时永久
     * 挂死。生产（有 abortController）走无限等待 + abort listener 解除（对齐 CC
     * useCanUseTool.tsx:113-125 + swarmWorkerHandler.ts:137-146 —— CC 无 30s 超时，
     * worker 与 leader 弹窗同为无限等待，靠 abort 解除）。
     */
    private static final long SWARM_WAIT_TIMEOUT_MS_FALLBACK = 30_000L;

    /**
     * [U6-A1] 投机分类器竞速等待上限 · 2s（对齐 CC useCanUseTool.tsx:192-196
     * {@code setTimeout(res, 2000, {type:"timeout" as const})} 硬编码 2000ms 定时器竞速）。
     *
     * <p>CC {@code Promise.race}（useCanUseTool.tsx:131）以 2s setTimeout 竞速：
     * 分类器结果 {@code _temp}（{@code {type:"result", result}}，useCanUseTool.tsx:197-201）与
     * {@code _temp2}（useCanUseTool.tsx:192-196，2000ms 后 resolve {@code {type:"timeout"}}）
     * 竞速，超时 resolve timeout 回落 interactive（useCanUseTool.tsx:135 仅 type=result 且
     * matches && confidence=high 才 allow）。Java 无单例 timer → 用有界
     * {@code get(2s, TimeUnit.MILLISECONDS)} 等价。首闸恒 false 下本等待为死代码（peek 恒 null）。
     */
    private static final long SPECULATIVE_WAIT_TIMEOUT_MS = 2_000L;

    /**
     * CC DENIAL_WORKAROUND_GUIDANCE (utils/messages.ts:226-232) · DONT_ASK_REJECT_MESSAGE
     * (messages.ts:238) 的固定后缀。
     *
     * <p>[IMPL-09 r2] 反思 R2: r1 只拼第一句, 模型拿不到"可用其它工具自然替代、但不得
     * 恶意绕过拒绝意图"的行为边界。补齐后拒绝文案与 CC 全文逐字一致。
     */
    private static final String DONT_ASK_REJECT_GUIDANCE =
        "IMPORTANT: You *may* attempt to accomplish this action using other tools that might naturally be used to accomplish this goal, "
        + "e.g. using head instead of cat. But you *should not* attempt to work around this denial in malicious ways, "
        + "e.g. do not use your ability to run tests to execute non-test actions. "
        + "You should only try to work around this restriction in reasonable ways that do not attempt to bypass the intent behind this denial. "
        + "If you believe this capability is essential to complete the user's request, STOP and explain to the user "
        + "what you were trying to do and why you need this permission. Let the user decide how to proceed.";

    private final PermissionPipeline pipeline;
    private final PermissionPrompter prompter;
    /**
     * auto mode 开关 · 可为 null（向后兼容：未启用 auto mode 时不调）。
     */
    private final AutoModeGate autoModeGate;
    /**
     * deny 计数 · 可为 null（向后兼容）。
     */
    private final DenialTracker denialTracker;
    /**
     * 子 agent 权限冒泡服务 · 可为 null（向后兼容：未注入时退化为单 agent 决策）。
     *
     * <p>[F Session P1-4] bubble caller 接入点 —— 仅当 {@code ctx.permissionMode() == BUBBLE}
     * 时调用 {@link PermissionBubbleService#handleBubble}, 严格 guard (Pattern #11).
     * 非 BUBBLE mode 永远不触发, 防止污染父 agent 弹窗.
     *
     * <p><b>WHY 可为 null</b>: 单 agent 部署 (无 fork 子 agent) 不需要 bubble 路径,
     * 此时 bubbleService 未注入, gate 直接走 promptUser 路径, 与 R32 之前行为一致.
     */
    private final PermissionBubbleService bubbleService;

    // ── [Session H9] Ask 分发链三 handler + 遥测 (对齐 CC useCanUseTool.tsx:93-169) ──

    /**
     * Coordinator worker 权限 handler · 可为 null (默认实例: hooks/classifier runner 未接线
     * → 恒 fall through, 与 H9 前行为一致).
     *
     * <p>CC: {@code awaitAutomatedChecksBeforeDialog} 时顺序 await hooks → classifier
     * (useCanUseTool.tsx:95-109), 非空决策即采用.
     */
    private final CoordinatorPermissionHandler coordinatorHandler;

    /**
     * Swarm worker 权限 handler · 可为 null (默认实例: swarms 未启用 → 恒 fall through).
     *
     * <p>CC: swarm worker 时转发 leader 决策 (useCanUseTool.tsx:113-125).
     */
    private final SwarmWorkerPermissionHandler swarmWorkerHandler;

    /**
     * Interactive 权限 handler · 恒非 null (默认实例委托 {@link #prompter} 同步阻塞).
     *
     * <p>CC: 默认分支 queue + 弹窗 (useCanUseTool.tsx:160-167). P3 竞速重构前保持同步阻塞.
     */
    private final InteractiveHandler interactiveHandler;

    /**
     * 权限决策遥测 · 恒非 null (telemetry 可为 null → 仅返回归因不发射事件).
     *
     * <p>对齐 CC {@code logPermissionDecision} (permissionLogging.ts:181-235).
     */
    private final PermissionDecisionLogger decisionLogger;

    /**
     * [Session WF3-02 A4] PermissionRequest hooks 注册中心 · 可为 null.
     *
     * <p>headless 决策链（CC {@code runPermissionRequestHooksForHeadlessAgent}，
     * permissions.ts:400-471）依赖本字段执行 PermissionRequest hooks。null（未注入 /
     * fallback 构造路径）→ hook 链直接跳过，落入 auto-deny（CC hook 失败 fall through 同款）。
     */
    private final HookRegistry hookRegistry;

    /**
     * [Session WF3-02 A4] 权限更新 Applier · 可为 null（未注入时 hook allow 的
     * updatedPermissions 仅跳过 apply，对齐 CC {@code applyPermissionUpdates}）。
     */
    private final PermissionUpdateApplier permissionUpdateApplier;

    /**
     * [Session WF3-02 A4] 权限更新 Persister · 可为 null（未注入时跳过持久化，
     * 对齐 CC {@code persistPermissionUpdates}）。
     */
    private final PermissionUpdatePersister permissionUpdatePersister;

    /**
     * [U6-A1] BASH_CLASSIFIER 特性开关 · 可为 null（未注入时 feature 判定为 false）。
     *
     * <p>CC {@code feature('BASH_CLASSIFIER')}（useCanUseTool.tsx:126）门控投机竞速分支；
     * Java 等价 {@link BashClassifierFeature#isEnabled()} 读 {@code nexusai.feature.bash-classifier}
     * （缺省 false）。null → feature 恒 false → 竞速恒跳过回落 interactive。
     */
    private final BashClassifierFeature bashClassifierFeature;

    /**
     * 完整构造器（Spring bean + 测试共用）.
     *
     * <p>Spring 自动注入时，{@code autoModeGate} / {@code denialTracker} 未注入时为 null
     * （{@code @Autowired(required = false)}） —— null 时退化为纯 10 层规则 + prompter
     * 二态决策，不做 auto mode 投机决策（对齐 Phase 1 行为）。
     *
     * <p>{@code bubbleService} 未注入时为 null —— null 时不触发 bubble caller,
     * 走纯 prompter 路径. Spring 容器内 {@code PermissionBubbleService} 是
     * {@code @Component}, 正常情况下总是注入; 测试可传 mock/spy.
     *
     * @param pipeline       10 层权限检查管线（必填）
     * @param prompter       弹窗询问器（必填，用于 ASK 分支）
     * @param autoModeGate   auto mode 开关（可为 null）
     * @param denialTracker  deny 计数（可为 null）
     * @param bubbleService  子 agent 权限冒泡服务（可为 null，详见字段说明）
     */
    public ToolPermissionGate(
            PermissionPipeline pipeline,
            PermissionPrompter prompter,
            AutoModeGate autoModeGate,
            DenialTracker denialTracker,
            PermissionBubbleService bubbleService) {
        this(pipeline, prompter, autoModeGate, denialTracker, bubbleService,
            null, null, null, null, null, null, null, null);
    }

    /**
     * [Session H9] 完整构造器 · Ask 分发链三 handler + 投机分类器 + 决策遥测全部可注入.
     *
     * <p>null 处理:
     * <ul>
     *   <li>{@code coordinatorHandler} null → 默认实例 (runner 未接线, 恒 fall through)</li>
     *   <li>{@code swarmWorkerHandler} null → 默认实例 (swarms 未启用, 恒 fall through)</li>
     *   <li>{@code interactiveHandler} null → 默认实例 (委托 prompter 同步阻塞)</li>
     *   <li>{@code decisionLogger} null → 默认实例 (telemetry null, 仅返回归因)</li>
     * </ul>
     *
     * @param pipeline              10 层权限检查管线（必填）
     * @param prompter              弹窗询问器（必填，用于 ASK 分支）
     * @param autoModeGate          auto mode 开关（可为 null）
     * @param denialTracker         deny 计数（可为 null）
     * @param bubbleService         子 agent 权限冒泡服务（可为 null）
     * @param coordinatorHandler    coordinator worker handler（可为 null → 默认）
     * @param swarmWorkerHandler    swarm worker handler（可为 null → 默认）
     * @param interactiveHandler    interactive handler（可为 null → 默认委托 prompter）
     * @param decisionLogger        权限决策遥测（可为 null → 默认无 telemetry）
     *
     * <p><b>[H9-GAP-2] Spring 实例化</b>: 本构造器标注 {@code @Autowired} 让 Spring 能
     * 直接实例化 {@code @Component} gate — 消除"@Component 注解但无默认/可注入构造器,
     * 生产走 createSpringBean 静态工厂 fallback"的注解/实例化路径不一致. 非 bean 依赖
     * ({@code autoModeGate} / {@code denialTracker} / 三 handler) 经 per-param
     * {@code @Autowired(required=false)} 注入 null → 构造器内部建默认实例 (与手工 new 一致).
     */
    @Autowired
    public ToolPermissionGate(
            PermissionPipeline pipeline,
            PermissionPrompter prompter,
            @Autowired(required = false) AutoModeGate autoModeGate,
            @Autowired(required = false) DenialTracker denialTracker,
            PermissionBubbleService bubbleService,
            @Autowired(required = false) CoordinatorPermissionHandler coordinatorHandler,
            @Autowired(required = false) SwarmWorkerPermissionHandler swarmWorkerHandler,
            @Autowired(required = false) InteractiveHandler interactiveHandler,
            PermissionDecisionLogger decisionLogger,
            @Autowired(required = false) HookRegistry hookRegistry,
            @Autowired(required = false) PermissionUpdateApplier permissionUpdateApplier,
            @Autowired(required = false) PermissionUpdatePersister permissionUpdatePersister,
            @Autowired(required = false) BashClassifierFeature bashClassifierFeature) {
        if (pipeline == null) {
            throw new IllegalArgumentException("ToolPermissionGate.pipeline is null");
        }
        if (prompter == null) {
            throw new IllegalArgumentException("ToolPermissionGate.prompter is null");
        }
        this.pipeline = pipeline;
        this.prompter = prompter;
        this.autoModeGate = autoModeGate;
        this.denialTracker = denialTracker;
        this.bubbleService = bubbleService;
        this.coordinatorHandler = coordinatorHandler != null
            ? coordinatorHandler
            : new CoordinatorPermissionHandler(
                () -> false,
                params -> null,
                (check, updatedInput, toolUseId) -> null,
                th -> log.warn("协调器自动化检查异常 (runner 未接线, 落入交互): {}", th.toString()));
        this.swarmWorkerHandler = swarmWorkerHandler != null
            ? swarmWorkerHandler
            : new SwarmWorkerPermissionHandler(
                () -> false, () -> false, () -> false,
                null, null, null, null, null);
        this.interactiveHandler = interactiveHandler != null
            ? interactiveHandler
            : new InteractiveHandler(prompter);
        this.decisionLogger = decisionLogger != null
            ? decisionLogger
            : new PermissionDecisionLogger(null);
        this.hookRegistry = hookRegistry;
        this.permissionUpdateApplier = permissionUpdateApplier;
        this.permissionUpdatePersister = permissionUpdatePersister;
        this.bashClassifierFeature = bashClassifierFeature;
    }

    /**
     * [REV-FIX-1 U6-A1] 12 参构造器（无 BashClassifierFeature）· 恢复 4 测试类 test-compile.
     *
     * <p>U-6 merge（083518b3）将 {@code @Autowired} 全参构造器扩到 13 参（新增第 13 参
     * {@code bashClassifierFeature}），未保留 12 参重载，导致 CanUseToolDispatchTest /
     * H9V2GapFixTest / HeadlessPermissionChainTest / InteractiveRaceModelTest 直接调前
     * 12 参的 {@code new ToolPermissionGate(...)} 编译失败。本构造器补回 12 参便捷重载，
     * 委托 13 参构造器，末位 {@code bashClassifierFeature=null} → feature 恒 false →
     * 投机竞速恒跳过回落 interactive（对齐 stub 恒禁用，与 :333 9 参构造器同款约定，
     * 无 CC 行为偏差 —— 对齐 useCanUseTool.tsx:126 {@code feature('BASH_CLASSIFIER')}）。
     *
     * <p>非 {@code @Autowired}：避免与 13 参 {@code @Autowired} 构造器构成 Spring 双构造器
     * 注入歧义（与 :333 9 参构造器同款先例）。仅用于测试 / 手工 new 场景。
     *
     * @param pipeline                  10 层权限检查管线（必填）
     * @param prompter                  弹窗询问器（必填，用于 ASK 分支）
     * @param autoModeGate              auto mode 开关（可为 null）
     * @param denialTracker             deny 计数（可为 null）
     * @param bubbleService             子 agent 权限冒泡服务（可为 null）
     * @param coordinatorHandler        coordinator worker handler（可为 null → 默认）
     * @param swarmWorkerHandler        swarm worker handler（可为 null → 默认）
     * @param interactiveHandler        interactive handler（可为 null → 默认委托 prompter）
     * @param decisionLogger            权限决策遥测（可为 null → 默认无 telemetry）
     * @param hookRegistry              hook 注册表（可为 null → 无 hook 决策时 auto-deny）
     * @param permissionUpdateApplier   权限更新应用器（可为 null）
     * @param permissionUpdatePersister 权限更新持久化器（可为 null）
     */
    public ToolPermissionGate(
            PermissionPipeline pipeline,
            PermissionPrompter prompter,
            AutoModeGate autoModeGate,
            DenialTracker denialTracker,
            PermissionBubbleService bubbleService,
            CoordinatorPermissionHandler coordinatorHandler,
            SwarmWorkerPermissionHandler swarmWorkerHandler,
            InteractiveHandler interactiveHandler,
            PermissionDecisionLogger decisionLogger,
            HookRegistry hookRegistry,
            PermissionUpdateApplier permissionUpdateApplier,
            PermissionUpdatePersister permissionUpdatePersister) {
        this(pipeline, prompter, autoModeGate, denialTracker, bubbleService,
            coordinatorHandler, swarmWorkerHandler, interactiveHandler, decisionLogger,
            hookRegistry, permissionUpdateApplier, permissionUpdatePersister, null);
        if (log.isDebugEnabled()) {
            log.debug("PERMISSION gate 12 参便捷构造器: bashClassifierFeature=null → 投机竞速恒跳过回落交互 (测试/手工 new 路径)");
        }
    }

    /**
     * [U6-A1] 9 参构造器（无 BashClassifierFeature）· 向后兼容单测直接调全参构造器.
     *
     * <p>仅用于测试 / 手工 new 场景（CanUseToolDispatchTest / H9V2GapFixTest /
     * InteractiveRaceModelTest 等直接调全参构造器）。{@code bashClassifierFeature=null}
     * → feature 恒 false → 投机竞速恒跳过回落 interactive（对齐 stub 恒禁用）。
     */
    public ToolPermissionGate(
            PermissionPipeline pipeline,
            PermissionPrompter prompter,
            AutoModeGate autoModeGate,
            DenialTracker denialTracker,
            PermissionBubbleService bubbleService,
            CoordinatorPermissionHandler coordinatorHandler,
            SwarmWorkerPermissionHandler swarmWorkerHandler,
            InteractiveHandler interactiveHandler,
            PermissionDecisionLogger decisionLogger) {
        this(pipeline, prompter, autoModeGate, denialTracker, bubbleService,
            coordinatorHandler, swarmWorkerHandler, interactiveHandler, decisionLogger, null, null, null, null);
    }

    /**
     * 向后兼容 4 参构造器 · bubbleService=null.
     *
     * <p>保留旧 4 参 ctor 让 Spring 自动选取 5 参版本注入; 手工测试若不需要 bubble
     * 路径可继续用 4 参 ctor, bubbleService 默认 null.
     *
     * @param pipeline       10 层权限检查管线（必填）
     * @param prompter       弹窗询问器（必填）
     * @param autoModeGate   auto mode 开关（可为 null）
     * @param denialTracker  deny 计数（可为 null）
     */
    public ToolPermissionGate(
            PermissionPipeline pipeline,
            PermissionPrompter prompter,
            AutoModeGate autoModeGate,
            DenialTracker denialTracker) {
        this(pipeline, prompter, autoModeGate, denialTracker, null);
    }

    /**
     * Spring bean 构造便捷方法 — 当 autoModeGate / denialTracker 未注入时为 null.
     *
     * <p>本方法由 Spring 容器调用，签名差异（重载）让 Spring 自动选取 5 参版本注入。
     * 手工测试请用 5 参构造器直接传 mock。
     */
    public static ToolPermissionGate createSpringBean(
            PermissionPipeline pipeline,
            PermissionPrompter prompter) {
        return new ToolPermissionGate(pipeline, prompter, null, null, null);
    }

    /**
     * [Session H9] Spring bean 构造便捷方法 · 带 telemetry 的决策遥测.
     *
     * <p>生产 fallback 路径 (AgentLoopContext.buildStreamingExecutor) 使用本重载,
     * 让 {@link PermissionDecisionLogger} 持有真实 telemetry (tengu_* 事件 + counter).
     *
     * @param pipeline  10 层权限检查管线
     * @param prompter  弹窗询问器
     * @param telemetry 遥测 (可为 null → 事件不发射)
     */
    public static ToolPermissionGate createSpringBean(
            PermissionPipeline pipeline,
            PermissionPrompter prompter,
            com.nexusai.application.agent.telemetry.Telemetry telemetry) {
        return createSpringBean(pipeline, prompter, telemetry, null, null, null);
    }

    /**
     * [canUseTool v2] Spring bean 构造便捷方法 · 携带三 handler.
     *
     * <p>生产 fallback 路径 (AgentLoopContext.buildStreamingExecutor) 使用本重载，
     * 让 gate 持有 coordinator/swarm/interactive 三 handler（Ask 分发链生产接线）。
     *
     * @param pipeline            10 层权限检查管线
     * @param prompter            弹窗询问器
     * @param telemetry           遥测 (可为 null → 事件不发射)
     * @param coordinatorHandler  coordinator worker handler (可为 null → 默认 fall-through)
     * @param swarmWorkerHandler  swarm worker handler (可为 null → 默认 fall-through)
     * @param interactiveHandler  interactive handler (可为 null → 默认委托 prompter)
     */
    public static ToolPermissionGate createSpringBean(
            PermissionPipeline pipeline,
            PermissionPrompter prompter,
            com.nexusai.application.agent.telemetry.Telemetry telemetry,
            CoordinatorPermissionHandler coordinatorHandler,
            SwarmWorkerPermissionHandler swarmWorkerHandler,
            InteractiveHandler interactiveHandler) {
        return createSpringBean(pipeline, prompter, telemetry,
            coordinatorHandler, swarmWorkerHandler, interactiveHandler, null);
    }

    /**
     * [U6-A1] Spring bean 构造便捷方法 · 携带 BashClassifierFeature（投机竞速门控）。
     *
     * <p>生产 fallback 路径（AgentLoopContext.buildStreamingExecutor）使用本重载，
     * 让 gate 的投机竞速分支持有真实 {@code feature('BASH_CLASSIFIER')} 判定
     * （对齐 useCanUseTool.tsx:126）。{@code bashClassifierFeature} null → feature 恒
     * false → 竞速恒跳过回落 interactive（向后兼容单测）。
     *
     * @param pipeline            10 层权限检查管线
     * @param prompter            弹窗询问器
     * @param telemetry           遥测（可为 null → 事件不发射）
     * @param coordinatorHandler  coordinator worker handler（可为 null → 默认 fall-through）
     * @param swarmWorkerHandler  swarm worker handler（可为 null → 默认 fall-through）
     * @param interactiveHandler  interactive handler（可为 null → 默认委托 prompter）
     * @param bashClassifierFeature BASH_CLASSIFIER 特性开关（可为 null → feature 恒 false）
     *
     * <p>[REV-FIX-4 WF-3 缝隙2] 本 7 参重载委托 8 参重载传 {@code hookRegistry=null}（后向兼容）：
     * 旧路径/测试下 headless PermissionRequest hook 链不接线 → 直接 auto-deny（fail-closed）。
     * 生产必须走 8 参重载传真实 {@link HookRegistry}（AgentLoopContext.buildStreamingExecutor）。
     */
    public static ToolPermissionGate createSpringBean(
            PermissionPipeline pipeline,
            PermissionPrompter prompter,
            com.nexusai.application.agent.telemetry.Telemetry telemetry,
            CoordinatorPermissionHandler coordinatorHandler,
            SwarmWorkerPermissionHandler swarmWorkerHandler,
            InteractiveHandler interactiveHandler,
            BashClassifierFeature bashClassifierFeature) {
        return createSpringBean(pipeline, prompter, telemetry, coordinatorHandler,
            swarmWorkerHandler, interactiveHandler, bashClassifierFeature, null);
    }

    /**
     * [REV-FIX-4 WF-3 缝隙2] Spring bean 构造便捷方法 · 携带 BashClassifierFeature + HookRegistry.
     *
     * <p>生产 fallback 路径（AgentLoopContext.buildStreamingExecutor）使用本重载，
     * 让 gate 的 headless 决策链 {@link #runHeadlessPermissionRequestHooks}（对齐 CC
     * {@code runPermissionRequestHooksForHeadlessAgent} permissions.ts:400-471）持真实
     * {@link HookRegistry} —— CC headless 恒先跑 PermissionRequest hooks 链（permissions.ts:932-951
     * {@code if (shouldAvoidPermissionPrompts) { const hookDecision = await
     * runPermissionRequestHooksForHeadlessAgent(...); if (hookDecision) return hookDecision }}），
     * 无 hook 决策才 auto-deny asyncAgent（CC :944-951）。{@code hookRegistry} null → 链不接线 →
     * 直接 auto-deny（fail-closed，向后兼容单测）。{@code bashClassifierFeature} null → feature 恒
     * false → 竞速恒跳过回落 interactive（向后兼容单测）。
     *
     * @param pipeline            10 层权限检查管线
     * @param prompter            弹窗询问器
     * @param telemetry           遥测（可为 null → 事件不发射）
     * @param coordinatorHandler  coordinator worker handler（可为 null → 默认 fall-through）
     * @param swarmWorkerHandler  swarm worker handler（可为 null → 默认 fall-through）
     * @param interactiveHandler  interactive handler（可为 null → 默认委托 prompter）
     * @param bashClassifierFeature BASH_CLASSIFIER 特性开关（可为 null → feature 恒 false）
     * @param hookRegistry        hook 注册表（可为 null → headless 无 hook 决策时 auto-deny）
     */
    public static ToolPermissionGate createSpringBean(
            PermissionPipeline pipeline,
            PermissionPrompter prompter,
            com.nexusai.application.agent.telemetry.Telemetry telemetry,
            CoordinatorPermissionHandler coordinatorHandler,
            SwarmWorkerPermissionHandler swarmWorkerHandler,
            InteractiveHandler interactiveHandler,
            BashClassifierFeature bashClassifierFeature,
            HookRegistry hookRegistry) {
        return new ToolPermissionGate(pipeline, prompter, null, null, null,
            coordinatorHandler, swarmWorkerHandler, interactiveHandler,
            new PermissionDecisionLogger(telemetry), hookRegistry, null, null, bashClassifierFeature);
    }

    /**
     * 3 态决策结果 · 对齐 CC useCanUseTool.tsx:27-191 决策 + 上下文.
     *
     * <p>{@link Decision} 简化上游调用, {@link #result} 让 DENY 路径能把原始 message
     * 注入 ToolResult.error (CLAUDE.md 规则十二: 错误消息完整保留).
     *
     * <p>[Session H9] {@link #decisionInfo} — logPermissionDecision 的
     * {@code {source, decision, timestamp}} 归因 (permissionLogging.ts:220-228),
     * 由 StreamingToolExecutor.injectDecisionInfo 写入 toolDecisions map;
     * null = 该决策未记录遥测 (如 abort 路径, 对齐 CC 仅 logCancelled).
     */
    public record DecisionResult(Decision decision, PermissionResult result,
                                 ToolDecisionInfo decisionInfo) {
        public DecisionResult {
            if (decision == null) {
                throw new IllegalArgumentException("DecisionResult.decision is null");
            }
            // result 可为 null (无原 PermissionResult 上下文), 默认 OK
        }

        /** 2 参兼容构造器 · decisionInfo=null (H9 前行为). */
        public DecisionResult(Decision decision, PermissionResult result) {
            this(decision, result, null);
        }

        /** 快速创建 ALLOW + null result. */
        public static DecisionResult allow() {
            return new DecisionResult(Decision.ALLOW, null);
        }

        /** 快速创建 DENY + result (供 ToolResult.error 提取 message). */
        public static DecisionResult deny(PermissionResult result) {
            return new DecisionResult(Decision.DENY, result);
        }

        /** 快速创建 DENY + result + 遥测归因. */
        public static DecisionResult deny(PermissionResult result, ToolDecisionInfo decisionInfo) {
            return new DecisionResult(Decision.DENY, result, decisionInfo);
        }
    }

    /**
     * 执行 3 态决策检查 · 5 参兼容入口 (forceDecision=null).
     *
     * @param tool    工具实例（用于弹窗显示 / hook 路由）
     * @param call    LLM 的工具调用
     * @param input   已解析 JSON 输入
     * @param ctx     工具调用上下文
     * @param permCtx 权限上下文（8 source 合并结果 + 当前 mode）
     * @return        3 态决策 + 原始 PermissionResult（DENY 时用于 message 提取）
     */
    public DecisionResult check(Tool tool,
                                 ToolUseBlock call,
                                 JsonNode input,
                                 ToolUseContext ctx,
                                 ToolPermissionContext permCtx) {
        return check(tool, call, input, ctx, permCtx, null);
    }

    /**
     * 执行 3 态决策检查 · 6 参 (forceDecision) · 对齐 CC
     * {@code canUseTool(tool, input, ctx, msg, id, forceDecision)}
     * (Open-ClaudeCode/src/hooks/useCanUseTool.tsx:27,37).
     *
     * <p><b>forceDecision 语义</b> (useCanUseTool.tsx:37 真源):
     * <pre>
     *   const decisionPromise = forceDecision !== undefined
     *     ? Promise.resolve(forceDecision)
     *     : hasPermissionsToUseTool(tool, input, toolUseContext, assistantMessage, toolUseID)
     * </pre>
     * forceDecision 非 null 时<b>直接作为决策</b>跳过 10 层管线 — hook ask 场景
     * (HookPermissionResolver ask 分支) 弹窗展示 hook 的 ask 消息而非规则消息.
     * null → 正常管线决策.
     *
     * @param tool          工具实例
     * @param call          LLM 的工具调用
     * @param input         已解析 JSON 输入
     * @param ctx           工具调用上下文
     * @param permCtx       权限上下文
     * @param forceDecision 强制决策 (通常为 hook 的 ask; 可为 null)
     * @return              3 态决策 + 原始 PermissionResult
     */
    public DecisionResult check(Tool tool,
                                 ToolUseBlock call,
                                 JsonNode input,
                                 ToolUseContext ctx,
                                 ToolPermissionContext permCtx,
                                 PermissionResult forceDecision) {
        PermissionResult result = forceDecision != null
            ? forceDecision
            // [IMPL-09] dontAsk ask→deny 变换 · 对齐 CC hasPermissionsToUseTool 尾部
            //   (permissions.ts:503-517): 变换在管线<strong>之后</strong>执行, 任何
            //   'ask' 结果转 'deny' (reason={type:'mode', mode:'dontAsk'},
            //   message=DONT_ASK_REJECT_MESSAGE, messages.ts:237-239).
            //   forceDecision 非 null 时短路整个管线 (CC useCanUseTool.tsx:37
            //   forceDecision !== undefined ? Promise.resolve(forceDecision) : ...)
            //   故变换不应用于 forceDecision 路径.
            //   <p>旧实现（R26 hook 层 [H13 v4]）在 hook 内做同样变换,
            //   随 6 hook 删除收敛到本处 (OD-SS-01 单链).
            : applyDontAskTransform(pipeline.check(tool, call, input, ctx, permCtx),
                                    tool, call, permCtx);
        // [Session WF3-02 A4] headless 决策链 · 对齐 CC permissions.ts:932-951:
        //   shouldAvoidPermissionPrompts → runPermissionRequestHooksForHeadlessAgent
        //   → hook 决策优先, 无决策 → asyncAgent auto-deny (AUTO_REJECT_MESSAGE).
        //   落点: applyDontAskTransform (A2) 之后、mapToDecision (A3 分发链) 之前 —
        //   与 CC 序 A2 → A3 → A4 的 auto-mode classifier 前置存在已论证的结构不对称
        //   (A3 分类器深嵌 mapToDecision, headless 主场景为 DEFAULT mode 无分类器).
        result = applyHeadlessDecision(result, tool, call, input, ctx, permCtx);
        return mapToDecision(result, call, tool, input, ctx, permCtx);
    }

    /**
     * dontAsk 模式 ask→deny 变换 · 对齐 CC permissions.ts:503-517.
     *
     * <p>仅当 {@code permCtx.mode() == DONT_ASK} 且管线结果为 Ask 时生效 —
     * 无 allow 规则命中的工具在 headless (hook agent) 场景不弹窗而是拒绝.
     * Allow/Deny 原样透传 (CC: 变换在 allow 早返之后, 且只处理 ask).
     *
     * @param result  管线决策 (非 null)
     * @param tool    工具实例 (消息模板用)
     * @param call    工具调用 (toolUseID 注入)
     * @param permCtx 权限上下文 (mode 判定)
     * @return 变换后的决策
     */
    private PermissionResult applyDontAskTransform(
            PermissionResult result, Tool tool, ToolUseBlock call,
            ToolPermissionContext permCtx) {
        if (!(result instanceof PermissionResult.Ask)
                || permCtx == null || permCtx.mode() != PermissionMode.DONT_ASK) {
            return result;
        }
        // CC DONT_ASK_REJECT_MESSAGE 全文 (utils/messages.ts:237-239 首句 + :238 指南后缀)
        String message = "Permission to use " + tool.name()
            + " has been denied because NexusAI is running in don't ask mode. "
            + DONT_ASK_REJECT_GUIDANCE;
        if (log.isInfoEnabled()) {
            log.info("PERMISSION gate DONT_ASK transform: ask → deny: tool={} callId={}",
                tool.name(), call.id());
        }
        return new PermissionResult.Deny(
            message,
            new PermissionDecisionReason.Mode(PermissionMode.DONT_ASK),
            call.id());
    }

    /**
     * [Session WF3-02 A4] headless 决策链 · 对齐 CC permissions.ts:932-951
     * {@code if (appState.toolPermissionContext.shouldAvoidPermissionPrompts)}.
     *
     * <p>仅当管线结果仍为 Ask 且 {@code permCtx.shouldAvoidPermissionPrompts()} 为 true
     * （后台/headless 异步 agent，CC {@code resolveShouldAvoidPermissionPrompts} =
     * {@code mode != bubble && isAsync}）时触发：
     * <ol>
     *   <li>先跑 PermissionRequest hooks（CC {@code runPermissionRequestHooksForHeadlessAgent}
     *       permissions.ts:400-471）—— hook allow/deny 优先采纳；</li>
     *   <li>无 hook 决策 → auto-deny {@code {type:'asyncAgent', reason:'Permission prompts
     *       are not available in this context'}} + {@code AUTO_REJECT_MESSAGE(tool.name)}
     *       （CC permissions.ts:944-951 + messages.ts:234-235）。</li>
     * </ol>
     *
     * <p>Allow/Deny 原样透传（CC A4 块仅在 result.behavior === 'ask' 分支内）。
     */
    private PermissionResult applyHeadlessDecision(
            PermissionResult result, Tool tool, ToolUseBlock call, JsonNode input,
            ToolUseContext ctx, ToolPermissionContext permCtx) {
        if (!(result instanceof PermissionResult.Ask ask)
                || permCtx == null || !permCtx.shouldAvoidPermissionPrompts()) {
            return result;
        }
        PermissionResult hookDecision = runHeadlessPermissionRequestHooks(ask, tool, input, ctx, permCtx, call.id());
        if (hookDecision != null) {
            return hookDecision;
        }
        if (log.isInfoEnabled()) {
            log.info("PERMISSION gate A4 headless auto-deny: tool={} callId={} (无 PermissionRequest hook 决策, asyncAgent)",
                tool.name(), call.id());
        }
        return new PermissionResult.Deny(
            buildAutoRejectMessage(tool.name()),
            new PermissionDecisionReason.AsyncAgent("Permission prompts are not available in this context"),
            call.id());
    }

    /**
     * [Session WF3-02 A4] 执行 PermissionRequest hooks · 对齐 CC
     * {@code runPermissionRequestHooksForHeadlessAgent} (permissions.ts:400-471).
     *
     * <p>CC 决策提取仅看 {@code hookResult.permissionRequestResult}（不 fall back 到
     * permissionBehavior/blockingError/preventContinuation，与 prompter 的竞速版不同）：
     * <ul>
     *   <li>allow → persist updatedPermissions + 返回 Allow(updatedInput)；</li>
     *   <li>deny → interrupt 则 abort，返回 Deny(hook message)；</li>
     *   <li>null / 无决策 / hook 抛错 → 返回 null（调用方 auto-deny）。</li>
     * </ul>
     *
     * @return hook 决策（Allow/Deny）；null = 无 hook 决策，调用方 auto-deny
     */
    private PermissionResult runHeadlessPermissionRequestHooks(
            PermissionResult.Ask ask, Tool tool, JsonNode input, ToolUseContext ctx,
            ToolPermissionContext permCtx, String toolUseId) {
        if (hookRegistry == null) {
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION gate A4: hookRegistry 未注入, 直接 auto-deny tool={} callId={}",
                    tool.name(), toolUseId);
            }
            return null;
        }
        try {
            HookEvent event = HookEvent.permissionRequest(
                tool.name(), input, toPermissionSuggestionMaps(ask.suggestions()),
                permCtx != null && permCtx.mode() != null ? modeToCcString(permCtx.mode()) : null,
                toolUseId,
                ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null,
                ctx != null && ctx.agentId() != null ? ctx.agentId().toString() : null);
            GenericHook.HookResult hookResult = hookRegistry.executeEvent(event);
            PermissionRequestResult prr = hookResult != null ? hookResult.permissionRequestResult() : null;
            if (prr instanceof PermissionRequestResult.Allow allow) {
                // CC permissions.ts:432-444 — finalInput = decision.updatedInput ?? input
                JsonNode rewritten = allow.updatedInput() != null
                    ? JSON.valueToTree(allow.updatedInput()) : input;
                // CC :436-451 — updatedPermissions 非空 → persist + apply
                List<PermissionUpdate> updates =
                    WebSocketPermissionPrompter.toPermissionUpdateList(allow.updatedPermissions());
                if (!updates.isEmpty()) {
                    applyAndPersistPermissionUpdates(updates, ctx, toolUseId);
                }
                if (log.isInfoEnabled()) {
                    log.info("PERMISSION gate A4 hook allow: tool={} callId={} 输入改写={}",
                        tool.name(), toolUseId, allow.updatedInput() != null);
                }
                return new PermissionResult.Allow(rewritten,
                    new PermissionDecisionReason.Hook("PermissionRequest", null, "allow"),
                    toolUseId, false, null, List.of());
            }
            if (prr instanceof PermissionRequestResult.Deny deny) {
                // CC permissions.ts:452-462 — interrupt → abortController.abort()
                if (Boolean.TRUE.equals(deny.interrupt())) {
                    abortIfPossible(ctx);
                }
                String message = deny.message() != null && !deny.message().isBlank()
                    ? deny.message() : "Permission denied by hook";
                if (log.isInfoEnabled()) {
                    log.info("PERMISSION gate A4 hook deny: tool={} callId={} interrupt={}",
                        tool.name(), toolUseId, Boolean.TRUE.equals(deny.interrupt()));
                }
                return new PermissionResult.Deny(message,
                    new PermissionDecisionReason.Hook("PermissionRequest", null, "deny"),
                    toolUseId);
            }
            return null;
        } catch (Throwable th) {
            // CC permissions.ts:469-476 — hook 失败 fall through 到 auto-deny, 不崩溃
            if (log.isWarnEnabled()) {
                log.warn("PERMISSION gate A4 PermissionRequest hook 失败, 落入 auto-deny: tool={} callId={} err={}",
                    tool.name(), toolUseId, th.toString());
            }
            return null;
        }
    }

    /**
     * [Session WF3-02 A4] AUTO_REJECT_MESSAGE · 对齐 CC utils/messages.ts:234-235:
     * {@code `Permission to use ${toolName} has been denied. ${DENIAL_WORKAROUND_GUIDANCE}`}.
     *
     * <p>复用 {@link #DONT_ASK_REJECT_GUIDANCE}（即 CC DENIAL_WORKAROUND_GUIDANCE 全文）。
     */
    private static String buildAutoRejectMessage(String toolName) {
        return "Permission to use " + toolName + " has been denied. " + DONT_ASK_REJECT_GUIDANCE;
    }

    /**
     * [Session WF3-02 A4] hook allow 携带的 updatedPermissions apply + persist ·
     * 对齐 CC permissions.ts:436-451（{@code persistPermissionUpdates} +
     * {@code setAppState(applyPermissionUpdates)}）。
     *
     * <p>applier / persister 未注入（旧测试构造路径）→ 仅日志不抛（幂等增强）。
     */
    private void applyAndPersistPermissionUpdates(List<PermissionUpdate> updates,
                                                  ToolUseContext ctx, String toolUseId) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        ToolPermissionContext current = ctx != null ? ctx.permissionContext() : null;
        if (current == null) {
            if (log.isWarnEnabled()) {
                log.warn("PERMISSION gate A4: hook allow 携带 updatedPermissions 但 ctx 无 permissionContext, 跳过 apply+persist callId={} updates={}",
                    toolUseId, updates.size());
            }
            return;
        }
        ToolPermissionContext applied = current;
        if (permissionUpdateApplier != null) {
            // [DEL-WF1-03] SESSION 桶跨轮持久已删（SessionSource/syncSessionStore 移除），
            // 不再传 sessionId —— "Allow this session" 跨轮持久待后续 appState 承载任务。
            applied = permissionUpdateApplier.applyAll(updates, current);
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION gate A4: updatedPermissions apply 完成 callId={} updates={}",
                    toolUseId, updates.size());
            }
        }
        if (permissionUpdatePersister != null) {
            permissionUpdatePersister.persistAll(updates);
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION gate A4: updatedPermissions persist 完成 callId={} updates={}",
                    toolUseId, updates.size());
            }
        }
        // CC setAppState 同步 (permissions.ts:448-451)
        if (ctx != null && ctx.setAppState() != null) {
            final ToolPermissionContext finalCtx = applied;
            ctx.setAppState().accept(prev -> {
                Map<String, Object> next = new java.util.LinkedHashMap<>(prev);
                next.put("toolPermissionContext", finalCtx);
                return next;
            });
        }
    }

    /**
     * [Session WF3-02 A4] PermissionUpdate 建议 → HookEvent.permissionSuggestions Map 列表 ·
     * CC original: {@code permission_suggestions} (coreSchemas.ts:431).
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toPermissionSuggestionMaps(List<PermissionUpdate> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>(suggestions.size());
        for (PermissionUpdate suggestion : suggestions) {
            out.add(JSON.convertValue(suggestion, Map.class));
        }
        return out;
    }

    /**
     * 把 {@link PermissionResult} 映射为 3 态决策 · ASK 分发链 (对齐 CC useCanUseTool.tsx:93-169).
     *
     * <p>Ask 分支顺序 (CC 真源, 顺序不可调换):
     * <ol>
     *   <li>resolveIfAborted 预检 (CC :34-36 + PermissionContext.ts:148-153)</li>
     *   <li>coordinator worker (awaitAutomatedChecksBeforeDialog=true, CC :95-109)</li>
     *   <li>resolveIfAborted (CC :110-112)</li>
     *   <li>swarm worker (CC :113-125)</li>
     *   <li>interactive (queue + 弹窗, CC :160-167 — Java 保留同步阻塞)</li>
     * </ol>
     * 任何分支抛出 → catch → logCancelled + cancelAndAbort (CC :171-179).
     */
    private DecisionResult mapToDecision(PermissionResult result,
                                          ToolUseBlock call,
                                          Tool tool,
                                          JsonNode input,
                                          ToolUseContext ctx,
                                          ToolPermissionContext permCtx) {
        if (result instanceof PermissionResult.Allow allow) {
            // CC :39-53 allow 分支 — logDecision(accept, 'config') + buildAllow(updatedInput, decisionReason)
            if (log.isDebugEnabled()) {
                log.debug("PERMISSION gate ALLOW: tool={} callId={}",
                    tool.name(), call.id());
            }
            // [H14] CC useCanUseTool.tsx:43-45 — TRANSCRIPT_CLASSIFIER + classifier
            //   decisionReason (auto-mode) → setYoloClassifierApproval (UI 显示分类器放行原因).
            if (allow.reason() instanceof PermissionDecisionReason.Classifier classifier
                    && "auto-mode".equals(classifier.classifier())) {
                ClassifierApprovals.setYoloClassifierApproval(call.id(), classifier.reason(), null);
            }
            ToolDecisionInfo info = decisionLogger.logPermissionDecision(
                tool, input, ctx, call.id(), "accept",
                new PermissionDecisionLogger.Source.Config(), null);
            // result 原样透传: updatedInput / decisionReason 保留 (CC buildAllow 语义)
            return new DecisionResult(Decision.ALLOW, result, info);
        }
        if (result instanceof PermissionResult.Deny deny) {
            if (log.isInfoEnabled()) {
                log.info("PERMISSION gate DENY: tool={} callId={} message={}",
                    tool.name(), call.id(), deny.message());
            }
            ToolDecisionInfo info = decisionLogger.logPermissionDecision(
                tool, input, ctx, call.id(), "reject",
                new PermissionDecisionLogger.Source.Config(), null);
            // CC :77-89 auto-mode classifier deny → recordAutoModeDenial + notification
            if (deny.reason() instanceof PermissionDecisionReason.Classifier classifier
                    && "auto-mode".equals(classifier.classifier())) {
                String display = describe(tool, input);
                // GC-04 (OPD-WF7-GC-04): 补 SDK 面 tool_use_id/tool_input — CC QueryEngine.ts:260-267
                //   permission_denials.push({ tool_name, tool_use_id, tool_input }), Java store 原缺
                //   这两字段 (EV-WF7-GC-001). call.id() = tool_use_id, input = tool_input.
                AutoModeDenials.recordAutoModeDenial(
                    tool.name(), display, classifier.reason(), System.currentTimeMillis(),
                    call.id(), input);
                notifyAutoModeDenied(ctx, tool, classifier.reason());
            }
            return DecisionResult.deny(result, info);
        }
        // ── Ask / Passthrough → CC useCanUseTool.tsx:93-169 ask 分发链 ──
        // 前置: resolveIfAborted 预检 (CC :34-36, PermissionContext.ts:148-153) —
        //   signal aborted → logCancelled + cancelAndAbort, 不再弹窗
        if (isAborted(ctx)) {
            return cancelAndAbortDecision(tool, call, ctx);
        }

        AskView askView = AskView.of(result);
        try {
            // 1. coordinator worker 分支 (CC :95-109) — awaitAutomatedChecksBeforeDialog 时
            //    顺序 await hooks → classifier, 非空决策即采用 (Java 同步 join 等价 await)
            if (permCtx != null && permCtx.awaitAutomatedChecksBeforeDialog()) {
                CoordinatorPermissionHandler.Params cParams = new CoordinatorPermissionHandler.Params(
                    coordinatorPendingCheck(askView, tool, input),
                    toMap(askView.updatedInput()),
                    toObjectList(askView.suggestions()),
                    modeToCcString(permCtx.mode()),
                    // [Session S07] hook 执行上下文补全 — 生产 hooksRunner 需要
                    //   tool/input/toolUseId/session/agent 才能执行 PermissionRequest hooks
                    //   (CC ctx.runHooks 实参, PermissionContext.ts:216-230)
                    tool.name(),
                    input,
                    call.id(),
                    ctx != null && ctx.sessionId() != null ? ctx.sessionId() : null,
                    ctx != null && ctx.agentId() != null ? ctx.agentId().toString() : null);
                Optional<CoordinatorPermissionHandler.PermissionDecision> coordDecision =
                    coordinatorHandler.handle(cParams);
                if (coordDecision.isPresent()) {
                    CoordinatorPermissionHandler.PermissionDecision cd = coordDecision.get();
                    // [H9-GAP-1] 遥测 source 归因 · 对齐 CC PermissionContext.ts:45-53:
                    //   HooksRunner 决策 → source hook (granted_by_permission_hook /
                    //   rejected_in_prompt isHook); ClassifierRunner 决策 → source classifier
                    //   (granted_by_classifier)。不能一律按 hook 上报, 否则 classifier 命中
                    //   污染 hook 漏斗.
                    if ("allow".equals(cd.decision())) {
                        // [hooks_v3 WF3-X6] 采纳 hook 的 updatedInput 改写 + updatedPermissions
                        //   apply+persist — 对齐 CC PermissionContext.ts:233-239 handleHookAllow
                        //   (finalInput = updatedInput ?? input; persistPermissions)。修复
                        //   coordinator 路径 "hook 批准的规则变更静默丢弃" (X-WF7-06 不变量 A)。
                        JsonNode finalInput = cd.updatedInput() != null
                            ? JSON.valueToTree(cd.updatedInput()) : input;
                        if (cd.updatedPermissions() != null && !cd.updatedPermissions().isEmpty()) {
                            applyAndPersistPermissionUpdates(cd.updatedPermissions(), ctx, call.id());
                        }
                        if (log.isInfoEnabled()) {
                            log.info("PERMISSION gate ALLOW (coordinator): tool={} callId={} source={} hasUpdatedInput={} updatedPermissions={}",
                                tool.name(), call.id(), cd.source(),
                                cd.updatedInput() != null, cd.updatedPermissions().size());
                        }
                        PermissionDecisionLogger.Source coordSource =
                            cd.source() == CoordinatorPermissionHandler.Source.CLASSIFIER
                                ? new PermissionDecisionLogger.Source.Classifier()
                                : new PermissionDecisionLogger.Source.Hook(false);
                        ToolDecisionInfo info = decisionLogger.logPermissionDecision(
                            tool, finalInput, ctx, call.id(), "accept", coordSource, null);
                        // [IMP-HOOKS-S6 CCJ-T6-18] hook source + decisionReason 非 null →
                        //   携带 PermissionResult (Hook("PermissionRequest") reason) 至
                        //   executor 注入点, 产 hook_permission_decision attachment
                        //   (CC toolExecution.ts:979-993). classifier 决策保持 null result
                        //   (executor 经 gateDecisionToPermission 合成 Other 归因, 不混同).
                        PermissionDecisionReason hookReason = cd.source() == CoordinatorPermissionHandler.Source.HOOK
                            ? cd.decisionReason() : null;
                        if (hookReason != null) {
                            return new DecisionResult(Decision.ALLOW,
                                new PermissionResult.Allow(finalInput, hookReason, call.id(),
                                    false, null, List.of()),
                                info);
                        }
                        return new DecisionResult(Decision.ALLOW, null, info);
                    }
                    if ("deny".equals(cd.decision())) {
                        // [hooks_v3 WF3-X6] deny.interrupt → 会话级 abort — 对齐 CC
                        //   PermissionContext.ts:245-250 (deny && interrupt →
                        //   abortController.abort(), 先 abort 后 buildDeny)。修复 coordinator
                        //   路径 "deny.interrupt 会话级中断未表达" (X-WF7-06 不变量 B)。
                        if (cd.interrupt()) {
                            abortIfPossible(ctx);
                        }
                        if (log.isInfoEnabled()) {
                            log.info("PERMISSION gate DENY (coordinator): tool={} callId={} message={} source={} interrupt={}",
                                tool.name(), call.id(), cd.reason(), cd.source(), cd.interrupt());
                        }
                        PermissionDecisionLogger.Source coordSource =
                            cd.source() == CoordinatorPermissionHandler.Source.CLASSIFIER
                                ? new PermissionDecisionLogger.Source.Classifier()
                                : new PermissionDecisionLogger.Source.Hook(true);
                        ToolDecisionInfo info = decisionLogger.logPermissionDecision(
                            tool, input, ctx, call.id(), "reject", coordSource, null);
                        String message = (cd.reason() != null && !cd.reason().isBlank())
                            ? cd.reason() : "Permission denied by coordinator";
                        // [IMP-HOOKS-S6 CCJ-T6-18] 同上: hook source 决策携带 Hook reason,
                        //   classifier 决策保持 Other("coordinator_denied") 归因.
                        PermissionDecisionReason denyReason =
                            cd.source() == CoordinatorPermissionHandler.Source.HOOK
                                && cd.decisionReason() != null
                            ? cd.decisionReason()
                            : new PermissionDecisionReason.Other("coordinator_denied");
                        return DecisionResult.deny(new PermissionResult.Deny(
                            message, denyReason, call.id()), info);
                    }
                    if (log.isWarnEnabled()) {
                        log.warn("PERMISSION gate: coordinator 返回未知决策 {} , 落入交互分支 tool={} callId={}",
                            cd.decision(), tool.name(), call.id());
                    }
                }
            }
            // 2. resolveIfAborted (CC :110-112) — coordinator 检查期间可能被中止
            if (isAborted(ctx)) {
                return cancelAndAbortDecision(tool, call, ctx);
            }
            // 3. swarm worker 分支 (CC :113-125) — 非 swarm worker / swarms 未启用时
            //    handle 返回 null → 落入后续分支
            CompletableFuture<SwarmWorkerPermissionHandler.PermissionDecision> swarmFuture =
                swarmWorkerHandler.handle(new SwarmWorkerPermissionHandler.Params(
                    tool.name(), call.id(), toMap(input), describe(tool, input),
                    toMap(askView.updatedInput()),
                    // [OPD-WF7-02-01] swarm classifier runner 需 pendingClassifierCheck（CC :52-57）
                    askView.pendingClassifierCheck()));
            if (swarmFuture != null) {
                // [perm-timeout] 对齐 CC useCanUseTool.tsx:113-125 + swarmWorkerHandler.ts:137-146：
                //   worker 无限等待 leader 决策，abort listener 解除等待（消除 Java 特有 30s 有界等待）。
                //   abortController 为 null（测试直构/无 ctx）→ 有界兜底防挂死（生产必有 abortController）。
                AbortController abortController = ctx != null ? ctx.abortController() : null;
                try {
                    SwarmWorkerPermissionHandler.PermissionDecision swarmDecision;
                    if (abortController != null) {
                        // abort 触发 → cancel(future) → get() 抛 CancellationException → 返回取消决策
                        // （对齐 CC swarmWorkerHandler.ts:137-146 cancelAndAbort）。
                        // handler 响应线程在 future 已 cancel 时为 no-op（claim 语义），与 abort 竞速不冲突。
                        java.util.function.Consumer<AbortController> abortListener =
                            c -> swarmFuture.cancel(false);
                        abortController.onCancel(abortListener);
                        try {
                            swarmDecision = swarmFuture.get();          // 无限等待
                        } finally {
                            abortController.removeOnCancel(abortListener);  // 防 listener 泄漏
                        }
                    } else {
                        swarmDecision = swarmFuture.get(SWARM_WAIT_TIMEOUT_MS_FALLBACK, TimeUnit.MILLISECONDS);
                    }
                    if (swarmDecision != null && "allow".equals(swarmDecision.behavior())) {
                        // [OPD-WF7-02-02] 对齐 CC handleUserAllow → persistPermissions
                        //   （PermissionContext.ts:291-318/139-147）: swarm allow 路径此前
                        //   permissionUpdates 被丢弃（D2，严重度高）——leader "Always allow" 规则
                        //   未 apply+persist。gate 持 ctx 负责 apply+persist（CC persist+apply 语义）。
                        if (swarmDecision.permissionUpdates() != null
                                && !swarmDecision.permissionUpdates().isEmpty()) {
                            List<PermissionUpdate> swarmUpdates = WebSocketPermissionPrompter
                                .toPermissionUpdateList(swarmDecision.permissionUpdates());
                            applyAndPersistPermissionUpdates(swarmUpdates, ctx, call.id());
                            if (log.isInfoEnabled()) {
                                log.info("PERMISSION gate ALLOW (swarm leader) updatedPermissions apply+persist: "
                                    + "tool={} callId={} updates={}", tool.name(), call.id(), swarmUpdates.size());
                            }
                        }
                        if (log.isInfoEnabled()) {
                            log.info("PERMISSION gate ALLOW (swarm leader): tool={} callId={}",
                                tool.name(), call.id());
                        }
                        ToolDecisionInfo info = decisionLogger.logPermissionDecision(
                            tool, input, ctx, call.id(), "accept",
                            new PermissionDecisionLogger.Source.User(false), null);
                        return new DecisionResult(Decision.ALLOW, null, info);
                    }
                    if (swarmDecision != null) {
                        if (log.isInfoEnabled()) {
                            log.info("PERMISSION gate DENY (swarm leader): tool={} callId={}",
                                tool.name(), call.id());
                        }
                        ToolDecisionInfo info = decisionLogger.logPermissionDecision(
                            tool, input, ctx, call.id(), "reject",
                            new PermissionDecisionLogger.Source.UserReject(false), null);
                        // CC swarmWorkerHandler.ts:118 cancelAndAbort(feedback) — reject 消息模板
                        String message = PermissionRejectMessages.buildRejectMessage(isSubagent(ctx), null);
                        return DecisionResult.deny(new PermissionResult.Deny(
                            message, new PermissionDecisionReason.Other("swarm_leader_rejected"), call.id()), info);
                    }
                } catch (CancellationException cancelled) {
                    // CC swarmWorkerHandler.ts:137-146 — abort 解除等待 → cancelAndAbort（DENY + user_abort）
                    if (log.isDebugEnabled()) {
                        log.debug("PERMISSION gate: swarm leader 等待被 abort 解除, 返回取消决策 tool={} callId={}",
                            tool.name(), call.id());
                    }
                    return cancelAndAbortDecision(tool, call, ctx);
                } catch (TimeoutException timeout) {
                    // 仅 abortController=null 兜底路径可达（有 abortController 时 get() 无限等待无超时）
                    if (log.isWarnEnabled()) {
                        log.warn("PERMISSION gate: swarm leader 等待超时 ({}ms 兜底), 落入交互分支 tool={} callId={}",
                            SWARM_WAIT_TIMEOUT_MS_FALLBACK, tool.name(), call.id());
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (log.isWarnEnabled()) {
                        log.warn("PERMISSION gate: swarm leader 等待中断, 落入交互分支 tool={} callId={}",
                            tool.name(), call.id());
                    }
                } catch (ExecutionException execution) {
                    if (log.isWarnEnabled()) {
                        log.warn("PERMISSION gate: swarm future 异常, 落入交互分支 tool={} callId={} err={}",
                            tool.name(), call.id(), execution.toString());
                    }
                }
            }
            // 3.5 ── [U6-A1] 投机分类器竞速 (CC useCanUseTool.tsx:126-158, swarm 后/bubble 前) ──
            //   门控: feature('BASH_CLASSIFIER') && pendingClassifierCheck && Bash && !awaitAutomatedChecks.
            //   peek → Promise.race 等价 (CompletableFuture.get 有界等待) → matches && high →
            //   consume + setClassifierApproval + allow(source classifier); 否则回落 bubble/interactive.
            //   首闸 isClassifierPermissionsEnabled() 恒 false → start 永不填充 → peek 恒 null →
            //   本分支 feature false 时恒跳过（结构接线保留, 可随时开启）.
            if (isBashClassifierEnabled()
                    && askView.pendingClassifierCheck() != null
                    && ToolNameConstants.BASH_TOOL_NAME.equals(tool.name())
                    && (permCtx == null || !permCtx.awaitAutomatedChecksBeforeDialog())) {
                String command = askView.pendingClassifierCheck().command();
                CompletableFuture<SpeculativeClassifier.SpeculativeClassifierResult> speculativeFuture =
                    SpeculativeClassifier.peekSpeculativeClassifierCheck(command);
                if (speculativeFuture != null) {
                    SpeculativeClassifier.SpeculativeClassifierResult speculative = null;
                    try {
                        speculative = speculativeFuture.get(SPECULATIVE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException | InterruptedException | ExecutionException e) {
                        // 超时/中断/异常 → 回落 interactive (CC Promise.race 永不 resolve 时回落)
                        if (log.isDebugEnabled()) {
                            log.debug("PERMISSION gate: 投机分类器竞速无结果, 回落交互 tool={} callId={} err={}",
                                tool.name(), call.id(), e.toString());
                        }
                    }
                    if (speculative != null && speculative.matches()
                            && "high".equals(speculative.confidence())
                            && isBashClassifierEnabled()) {
                        SpeculativeClassifier.consumeSpeculativeClassifierCheck(command);
                        String matchedRule = speculative.matchedDescription();
                        if (matchedRule != null) {
                            ClassifierApprovals.setClassifierApproval(call.id(), matchedRule, null);
                        }
                        if (log.isInfoEnabled()) {
                            log.info("PERMISSION gate ALLOW (speculative classifier): tool={} callId={} matchedRule={}",
                                tool.name(), call.id(), matchedRule);
                        }
                        ToolDecisionInfo info = decisionLogger.logPermissionDecision(
                            tool, input, ctx, call.id(), "accept",
                            new PermissionDecisionLogger.Source.Classifier(), null);
                        // [F3B-UPD] 对齐 CC useCanUseTool.tsx:149-159 buildAllow:
                        //   resolve(ctx.buildAllow(result.updatedInput ?? input, {
                        //     decisionReason: { type:'classifier', classifier:'bash_allow',
                        //       reason: `Allowed by prompt rule: "${matchedDescription}"` }}))
                        //   旧实现 return DecisionResult(ALLOW, null) 丢弃 updatedInput +
                        //   bash_allow decisionReason, 与 CC buildAllow 契约错位; 对齐后返回
                        //   带 effectiveInput(updatedInput ?? input) + bash_allow Classifier 的 Allow.
                        JsonNode effectiveInput = askView.updatedInput() != null
                            ? askView.updatedInput() : input;
                        // bash_allow 无 auto-mode 语义 (CC types/permissions.ts:303-307 classifier
                        //   变体 {type;classifier;reason} 无 mode 字段), classifier 字段为 "bash_allow"
                        //   而非 "auto-mode", 故不触发 PermissionDenied retry hook (toolExecution.ts:1078).
                        PermissionDecisionReason.Classifier reason = new PermissionDecisionReason.Classifier(
                            "bash_allow", "Allowed by prompt rule: \"" + matchedRule + "\"");
                        PermissionResult.Allow allow = new PermissionResult.Allow(
                            effectiveInput, reason, call.id(), false, null, List.of());
                        if (log.isDebugEnabled()) {
                            log.debug("PERMISSION gate 投机 allow buildAllow: tool={} callId={} hasUpdatedInput={} classifier={}",
                                tool.name(), call.id(), effectiveInput != null, "bash_allow:" + matchedRule);
                        }
                        return new DecisionResult(Decision.ALLOW, allow, info);
                    }
                    // 无投机命中 → 回落 bubble/interactive
                }
            }
            // 4. ── [F Session P1-4] bubble caller: 子 agent ASK 决策冒泡到父 agent ──
            //   对齐 CC Open-ClaudeCode/src/tools/AgentTool/runAgent.ts:443
            //     `agentPermissionMode === 'bubble'`
            //   + toolExecution.ts:599 checkPermissionsAndCallTool 阻塞弹窗路径.
            //
            //   Pattern #11 严格 guard (NOT bypass):
            //     - 仅当 ctx.permissionMode() == BUBBLE 才触发 bubble
            //     - bubbleService == null → 跳过 (向后兼容单 agent 部署)
            //     - bubble 返回原 Ask → 继续走 interactive (上层父 agent 用户弹窗)
            //   不会"为了冒泡"绕过现有 10 层规则检查 —— bubble 只决定"弹窗给谁看",
            //   不修改 10 层检查的最终 ALLOW/DENY 语义.
            //   [Session H9] 位置决策: 分发链 (coordinator/swarm/classifier) 在 bubble 之前 —
            //   bubble 是子 agent 弹窗路由 (对齐 CC forkSubagent.ts:67 'bubble'),
            //   CC 无深度守卫/工具黑名单; 自动化检查命中时不应再冒泡, 只有
            //   "需要人决定"才冒泡给父 agent.
            PermissionResult effectiveResult = result;
            if (bubbleService != null
                    && ctx != null
                    && ctx.permissionMode() == PermissionMode.BUBBLE
                    && result instanceof PermissionResult.Ask askResult) {
                SubagentPermissionContext childCtx = deriveSubagentContext(ctx);
                if (log.isDebugEnabled()) {
                    log.debug("权限门 BUBBLE 触发冒泡: agentId={} 工具={} 调用ID={} → handleBubble",
                        ctx.agentId(), tool.name(), call.id());
                }
                PermissionResult bubbled = bubbleService.handleBubble(
                    childCtx, tool.name(), input, askResult);
                // handleBubble 对齐 CC runAgent.ts:440-446 返回原 Ask（bubble 必弹窗，
                // 不短路、不篡改决策）；bubbled 交由上层 interactive 继续处理
                effectiveResult = bubbled;
            }
            // 5. interactive 分支 (CC :160-167) — queue + 弹窗, 同步阻塞等用户响应
            //    [Session H9] Java 同步模型: queue push = prompter 内部 STOMP 推送,
            //    awaitUserDecision = 同步阻塞 (P3 四路竞速重构留待 H9.5, 决策点 1)
            //    [canUseTool v2] description/suggestions/blockedPath 进弹窗
            //    (CC useCanUseTool.tsx:56-60 + interactiveHandler.ts:250-253).
            long promptStart = System.currentTimeMillis();
            PermissionPromptDetails promptDetails = promptDetailsOf(effectiveResult, tool, input, permCtx);
            PermissionResult afterPrompt = interactiveHandler.awaitUserDecision(
                tool, input, promptReasonOf(effectiveResult), ctx, call.id(), promptDetails);
            return mapPromptResult(afterPrompt, tool, input, ctx, call, promptStart);
        } catch (Throwable th) {
            // CC :171-179 catch → logCancelled + cancelAndAbort(undefined, true)
            //   任何自动化检查抛错都不能静默放行 — fail loud (CLAUDE.md 规则十二)
            if (log.isErrorEnabled()) {
                log.error("PERMISSION gate ask 分发链异常, 按取消处理: tool={} callId={} err={}",
                    tool.name(), call.id(), th.toString());
            }
            return cancelAndAbortDecision(tool, call, ctx);
        } finally {
            // [H14] CC useCanUseTool.tsx:180-182 — promise.finally 里 clearClassifierChecking,
            //   确保任何退出路径 (coordinator/swarm/interactive/catch) 都清掉"分类器运行中"
            //   指示器, 防止 UI 卡"checking"状态.
            ClassifierApprovals.clearClassifierChecking(call.id(), null);
        }
    }

    /**
     * [U6-A1] BASH_CLASSIFIER 特性判定 · 对齐 CC {@code feature('BASH_CLASSIFIER')}
     * (useCanUseTool.tsx:126)。
     *
     * <p>{@code null}（未注入）→ feature 恒 false → 投机竞速恒跳过回落 interactive。
     *
     * @return true = feature 启用（投机竞速可触发）
     */
    private boolean isBashClassifierEnabled() {
        return bashClassifierFeature != null && bashClassifierFeature.isEnabled();
    }

    /**
     * [Session H9] interactive 分支结果映射 · 对齐 CC handleUserAllow / onReject
     * (interactiveHandler.ts:154-203) + logDecision (permissionLogging.ts:181-235).
     *
     * @param afterPrompt prompter 返回的用户决策
     * @param promptStart 弹窗起点时间戳 (waiting_for_user_permission_ms 计算)
     */
    private DecisionResult mapPromptResult(PermissionResult afterPrompt, Tool tool, JsonNode input,
                                            ToolUseContext ctx, ToolUseBlock call, long promptStart) {
        if (afterPrompt instanceof PermissionResult.Allow) {
            if (log.isInfoEnabled()) {
                log.info("PERMISSION gate ALLOW (user allowed): tool={} callId={}",
                    tool.name(), call.id());
            }
            // CC interactiveHandler.ts:172-181 handleUserAllow → source user (permanent=false)
            ToolDecisionInfo info = decisionLogger.logPermissionDecision(
                tool, input, ctx, call.id(), "accept",
                new PermissionDecisionLogger.Source.User(false), promptStart);
            return new DecisionResult(Decision.ALLOW, afterPrompt, info);
        }
        if (afterPrompt instanceof PermissionResult.Deny) {
            if (log.isInfoEnabled()) {
                log.info("PERMISSION gate DENY (user denied): tool={} callId={} message={}",
                    tool.name(), call.id(), ((PermissionResult.Deny) afterPrompt).message());
            }
            // CC interactiveHandler.ts:195-202 onReject → source user_reject (hasFeedback 未知 → false)
            ToolDecisionInfo info = decisionLogger.logPermissionDecision(
                tool, input, ctx, call.id(), "reject",
                new PermissionDecisionLogger.Source.UserReject(false), promptStart);
            return new DecisionResult(Decision.DENY, afterPrompt, info);
        }
        // Passthrough 不应出现（10 层第 3 层兜底永远 Ask）—— 防御性 fallback
        if (log.isWarnEnabled()) {
            log.warn("PERMISSION gate: prompter returned unexpected Passthrough, denying by default: tool={} callId={}",
                tool.name(), call.id());
        }
        return DecisionResult.deny(afterPrompt);
    }

    /**
     * [Session H9] cancelAndAbort 等价决策 · 对齐 CC
     * {@code resolveIfAborted} / {@code cancelAndAbort}
     * (PermissionContext.ts:148-173).
     *
     * <p>CC 返回 {@code {behavior: 'ask', message: REJECT_MESSAGE...}} 并 abort controller;
     * Java 同步模型无"异步 ask 返回"通道, 映射为 DENY + REJECT_MESSAGE 模板消息
     * (toToolResult 把消息注入 LLM 让模型自纠 — 与 CC cancelAndAbort 后 tool 被取消
     * 且拒绝消息进 tool_result 等价). {@code logCancelled} 在调用方执行.
     *
     * @param tool 工具实例
     * @param call 工具调用
     * @param ctx  工具调用上下文 (可为 null)
     * @return DENY + REJECT_MESSAGE
     */
    private DecisionResult cancelAndAbortDecision(Tool tool, ToolUseBlock call, ToolUseContext ctx) {
        decisionLogger.logCancelled(tool, ctx, call.id());
        String message = PermissionRejectMessages.buildRejectMessage(isSubagent(ctx), null);
        abortIfPossible(ctx);
        return new DecisionResult(Decision.DENY,
            new PermissionResult.Deny(message,
                new PermissionDecisionReason.Other("user_abort"), call.id()),
            null);
    }

    /** signal aborted 检查 · CC signal.aborted (PermissionContext.ts:149). */
    private static boolean isAborted(ToolUseContext ctx) {
        return ctx != null && ctx.abortController() != null && ctx.abortController().isCancelled();
    }

    /** CC sub = !!toolUseContext.agentId (PermissionContext.ts:159) — Java 等价: agentType != null. */
    private static boolean isSubagent(ToolUseContext ctx) {
        return ctx != null && ctx.agentType() != null;
    }

    /** CC cancelAndAbort isAbort=true → abortController.abort() (PermissionContext.ts:166-171).
     *  [hooks_v3 WF3-X6] package-private 供 WebSocketPermissionPrompter 复用 (deny.interrupt 会话级 abort). */
    static void abortIfPossible(ToolUseContext ctx) {
        if (ctx != null && ctx.abortController() != null) {
            ctx.abortController().abort("permission_cancelled");
        }
    }

    /**
     * CC :77-89 auto-mode deny 通知 · addNotification({key: 'auto-mode-denied', ...}).
     * Java Notification 为 payload 契约 (前端渲染), 对齐 CC jsx 文本.
     */
    private void notifyAutoModeDenied(ToolUseContext ctx, Tool tool, String reason) {
        if (ctx == null || ctx.addNotification() == null) {
            return;
        }
        try {
            String displayName = tool.userFacingName().toLowerCase();
            ctx.addNotification().accept(new Notification(
                "auto-mode-denied-" + System.nanoTime(),
                displayName + " denied by auto mode",
                reason != null ? reason : "",
                Notification.Level.ERROR));
        } catch (Throwable th) {
            if (log.isWarnEnabled()) {
                log.warn("权限门 auto-mode deny 通知失败: tool={} err={}", tool.name(), th.toString());
            }
        }
    }

    /** 工具描述 · CC tool.description(input) (useCanUseTool.tsx:56-60). */
    private static String describe(Tool tool, JsonNode input) {
        try {
            return input != null ? tool.description(input) : tool.description();
        } catch (Throwable th) {
            return tool.name();
        }
    }

    /** Ask / Passthrough 公共字段视图 · 分发链统一读取. */
    private record AskView(List<PermissionUpdate> suggestions, JsonNode updatedInput,
                           PermissionResult.PendingClassifierCheck pendingClassifierCheck,
                           PermissionResult.PermissionMetadata metadata) {
        static AskView of(PermissionResult result) {
            if (result instanceof PermissionResult.Ask ask) {
                return new AskView(ask.suggestions(), ask.updatedInput(),
                    ask.pendingClassifierCheck(), ask.metadata());
            }
            if (result instanceof PermissionResult.Passthrough passthrough) {
                return new AskView(passthrough.suggestions(), null,
                    passthrough.pendingClassifierCheck(), null);
            }
            return new AskView(List.of(), null, null, null);
        }
    }

    /**
     * coordinator Params.pendingClassifierCheck · 直接取结构体 command (H14 升级:
     * boolean → PendingClassifierCheck 结构体, 不再走 metadata pendingClassifierDetails).
     */
    private CoordinatorPermissionHandler.PendingClassifierCheck coordinatorPendingCheck(
            AskView view, Tool tool, JsonNode input) {
        if (view.pendingClassifierCheck() == null) {
            return null;
        }
        return new CoordinatorPermissionHandler.PendingClassifierCheck(
            tool.name(), view.pendingClassifierCheck().command());
    }

    /** JsonNode → Map (分发链入参转换; null 安全). */
    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return JSON.convertValue(node, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toObjectList(List<PermissionUpdate> suggestions) {
        return suggestions == null ? List.of() : (List<Object>) (List<?>) suggestions;
    }

    /** PermissionMode → CC 字符串 (coreSchemas.ts:391 permission_mode 字面量).
     *
     * <p>[reflector-C F3 返工] 由 private 提升为 public: retry hook 载荷
     * (StreamingToolExecutor.maybeFirePermissionDeniedRetry) 复用同一映射,
     * 保证全库 permission_mode 序列化统一为 CC 小写字面量 (default/auto/acceptEdits/...),
     * 不再出现枚举名大写 (DEFAULT) 双轨. */
    public static String modeToCcString(PermissionMode mode) {
        return switch (mode) {
            case DEFAULT -> "default";
            case ACCEPT_EDITS -> "acceptEdits";
            case BYPASS_PERMISSIONS -> "bypassPermissions";
            case DONT_ASK -> "dontAsk";
            case PLAN -> "plan";
            case AUTO -> "auto";
            case BUBBLE -> "bubble";
        };
    }

    /**
     * 从 {@link ToolUseContext} 派生子 agent 权限上下文 · bubble caller 入参.
     *
     * <p>对齐 CC Open-ClaudeCode/src/tools/AgentTool/runAgent.ts:415-450: 子 agent 的
     * permission mode 在 fork 时被覆盖为 'bubble'. Java 端通过 {@code ctx.permissionMode() == BUBBLE}
     * 检测, 此时构造 {@link SubagentPermissionContext} 传给 bubble service.
     *
     * <p><b>WHY parentAgentId=null</b>: ToolUseContext 没有 parent 字段. parent ID
     * 由更上层的 AgentDefinition / LlmAgentLoop 维护, 留 null 让日志标注"父 agent 未知".
     * bubble service 不强依赖 parent agentId (审计日志而已).
     *
     * @param ctx 工具调用上下文 (permissionMode 必须为 BUBBLE)
     * @return 子 agent 权限上下文
     */
    private SubagentPermissionContext deriveSubagentContext(ToolUseContext ctx) {
        return new SubagentPermissionContext(
            ctx.agentId() != null ? ctx.agentId().toString() : "unknown",
            null,                        // parentAgentId (ToolUseContext 未携带, 留 null)
            BubblePermissionMode.BUBBLE  // 子 agent 在 BUBBLE mode 下默认走 BUBBLE
        );
    }

    /**
     * [canUseTool v2 + v3] 弹窗展示细节构建 · 对齐 CC useCanUseTool.tsx:56-60
     * {@code await tool.description(input, ...)} + interactiveHandler.ts:250-253
     * （description / suggestions / blockedPath 进弹窗队列与 bridge）。
     *
     * <p>description 用 {@link #describe}（工具描述，失败退 tool.name()）；
     * suggestions / blockedPath 取自 Ask / Passthrough 决策字段。
     *
     * <p>[canUseTool v3] 交互竞速规格填充：
     * <ul>
     *   <li>{@code pendingClassifierCheck} — CC interactiveHandler.ts:434-439 后台 classifier
     *       竞速条件 {@code bash + pendingClassifierCheck + !awaitAutomatedChecksBeforeDialog}。
     *       awaitAutomatedChecks=true 时 coordinator 已顺序 await classifier（CC :95-109），
     *       结果已消费，interactive 不再重复竞速（CC :126 投机竞速 + :438 interactive 后台
     *       都带 {@code !awaitAutomatedChecksBeforeDialog} 守卫）→ 传 null。</li>
     *   <li>{@code runHookRace} — CC interactiveHandler.ts:411 {@code !awaitAutomatedChecksBeforeDialog}
     *       时 hooks 由 interactive 分支后台异步竞速；awaitAutomatedChecks=true 时 hooks 已被
     *       coordinator 消费，不再重复。</li>
     * </ul>
     */
    private static PermissionPromptDetails promptDetailsOf(PermissionResult askOrPassthrough,
                                                           Tool tool, JsonNode input,
                                                           ToolPermissionContext permCtx) {
        List<PermissionUpdate> suggestions = List.of();
        String blockedPath = null;
        PermissionResult.PendingClassifierCheck pendingCheck = null;
        if (askOrPassthrough instanceof PermissionResult.Ask ask) {
            suggestions = ask.suggestions();
            blockedPath = ask.blockedPath();
            pendingCheck = ask.pendingClassifierCheck();
        } else if (askOrPassthrough instanceof PermissionResult.Passthrough pass) {
            suggestions = pass.suggestions();
            blockedPath = pass.blockedPath();
            pendingCheck = pass.pendingClassifierCheck();
        }
        boolean awaitAutomatedChecks =
            permCtx != null && permCtx.awaitAutomatedChecksBeforeDialog();
        // CC :434-439 — 后台 classifier 竞速仅非 awaitAutomatedChecks 且 Bash 工具
        PermissionResult.PendingClassifierCheck classifierRace = (!awaitAutomatedChecks
                && "Bash".equals(tool.name()) && pendingCheck != null)
            ? pendingCheck : null;
        // CC :411 — hooks 后台竞速仅非 awaitAutomatedChecks
        return new PermissionPromptDetails(
            describe(tool, input), suggestions, blockedPath,
            classifierRace, !awaitAutomatedChecks);
    }

    /**
     * 弹窗原因提取 · Ask / Passthrough 优先自带归因, 兜底 Other.
     */
    private static PermissionDecisionReason promptReasonOf(PermissionResult askOrPassthrough) {
        if (askOrPassthrough instanceof PermissionResult.Ask ask
                && ask.reason() != null) {
            return ask.reason();
        }
        if (askOrPassthrough instanceof PermissionResult.Passthrough passthrough
                && passthrough.reason() != null) {
            return passthrough.reason();
        }
        return new PermissionDecisionReason.Other("permission requested");
    }

    /**
     * DENY 决策 → {@link ToolResult#error} 映射（ALLOW / ASK 不应调用本方法）。
     *
     * <p>仅用于 StreamingToolExecutor 把 DENY 决策转成 ToolResult，注入 LLM 让模型
     * 自纠。ALLOW 路径走正常 {@code tool.execute}；ASK 路径已在 {@link #check} 内
     * 同步阻塞转 ALLOW/DENY。
     *
     * @param decision       决策（必须为 DENY）
     * @param result         {@link PermissionPipeline#check} 返回的原结果（含 message / reason）
     * @param toolUseId      工具调用 ID
     * @return               ToolResult.error（含 deny message）
     * @throws IllegalStateException 若 decision 非 DENY
     */
    public static ToolResult toToolResult(Decision decision,
                                           PermissionResult result,
                                           String toolUseId) {
        if (decision != Decision.DENY) {
            throw new IllegalStateException(
                "toToolResult 仅 DENY 决策可调用, 实际 decision=" + decision);
        }
        String message = (result instanceof PermissionResult.Deny deny)
                ? deny.message()
                : ("Permission denied for tool call " + toolUseId);
        // [R32-b15 C15] 注入 permission 错误分类 · 对齐 CC toolExecution.ts:150-170
        return ToolResult.error(toolUseId, message, "permission");
    }
}