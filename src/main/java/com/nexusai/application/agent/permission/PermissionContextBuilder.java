package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.permission.source.PermissionSourceLoader;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 权限上下文构造器 · PR 4 把 {@link AgentState} 翻译成 10 层规则检查所需的两个参数。
 *
 * <h2>职责</h2>
 * <p>{@link com.nexusai.application.agent.LlmAgentLoop} 在每次工具调用前调 {@link #buildToolUseContext(AgentState)} 拿
 * {@link ToolUseContext}（传给 {@code tool.checkPermissions / validateInput}），调
 * {@link #buildPermissionContext(AgentState, boolean, PermissionMode, boolean, boolean)} 拿 {@link ToolPermissionContext}（传给
 * {@link PermissionPipeline#check} 10 层）。
 *
 * <h2>Phase 2 改动（本 PR）</h2>
 * <p>PR 1 之前 {@link #buildPermissionContext(AgentState, boolean, PermissionMode, boolean, boolean)} 返回空规则集
 * （3 个 {@code Map.of()}）→ 所有规则层 miss → 几乎所有工具调用都触发弹窗。
 *
 * <p>PR 1（本 PR）起：
 * <ol>
 *   <li>Spring 注入 {@code List<PermissionSourceLoader>}（PR 1: 3 个 editable source）</li>
 *   <li>每次 {@code buildPermissionContext} 调用所有 loader.load()（无缓存，每次重读盘）</li>
 *   <li>按 {@link PermissionBehavior} 3 桶（allow / deny / ask）+ {@link PermissionRuleSource} 分组</li>
 *   <li>填入 {@link ToolPermissionContext}</li>
 * </ol>
 *
 * <h2>合并算法</h2>
 * <pre>
 *   Map&lt;Source, Set&lt;Rule&gt;&gt; allow = {}
 *   Map&lt;Source, Set&lt;Rule&gt;&gt; deny  = {}
 *   Map&lt;Source, Set&lt;Rule&gt;&gt; ask   = {}
 *   for (loader in loaders):
 *     for (rule in loader.load()):
 *       bucket = switch(rule.behavior):
 *         ALLOW → allow[rule.source].add(rule)
 *         DENY  → deny[rule.source].add(rule)
 *         ASK   → ask[rule.source].add(rule)
 * </pre>
 *
 * <p>同 source 内同 toolName+content 的 rule 因 {@link PermissionRule} record 的
 * {@code equals/hashCode} 自动去重（field-by-field 比较）。
 * 跨 source 的去重由 PermissionPipeline 的 8 source 合并逻辑处理（Phase 3 加）。
 *
 * <h2>行为契约</h2>
 * <p>同一 {@link AgentState} 上多次调用 builder 应得到等价结果（无状态、可重入）。
 * 这是为了支撑"多个 tool 并行权限检查"场景 —— 同一 turn 的多个 tool 共享同一 context。
 *
 * <h2>向后兼容</h2>
 * <p>保留无参构造器 {@link #PermissionContextBuilder()}（用于 PR 1-4 测试）。
 * Spring 注入用 {@link #PermissionContextBuilder(List)}（PR 1+）。
 *
 * @see ToolUseContext
 * @see ToolPermissionContext
 * @see PermissionPipeline
 * @see PermissionSourceLoader
 */
@Component
public class PermissionContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(PermissionContextBuilder.class);

    /**
     * 8 source loader 链（PR 1: 3 个 editable；PR 2+: 5 个 read-only / runtime）。
     *
     * <p>Spring 按 bean discovery 顺序注入（{@code @Component} 类按类名字典序或
     * {@code @Order} 注解）。builder 按 list 顺序遍历 loader —— 这意味着 source
     * 合并顺序也是确定的，与 {@link PermissionRuleSource} 枚举声明顺序一致。
     */
    private final List<PermissionSourceLoader> loaders;

    /**
     * 规则冲突检测器（s03 §9.14）。
     *
     * <p>{@code @Autowired(required=false)}：如果 Spring 容器中存在 ShadowedRuleDetector bean
     * 则注入；否则为 null（向后兼容，跳过冲突检测）。
     *
     * <p>构建完 ToolPermissionContext 后，检测被高优先级 source 覆盖的 allow 规则，
     * 记录 warn 日志（不阻断流程），提供规则冲突可见性。
     */
    @Autowired(required = false)
    private ShadowedRuleDetector shadowedRuleDetector;


    /**
     * s04 PR 3: 危险模式检测器（auto mode 入口剥离危险规则）。
     * null → 不做危险规则剥离（向后兼容）。
     */
    @Autowired(required = false)
    private DangerousPatternDetector dangerousPatternDetector;

    /**
     * s04 PR 3: Auto mode 开关。
     * null → auto mode 不可用（向后兼容）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.permission.classifier.AutoModeGate autoModeGate;

    /**
     * [canUseTool v2] coordinator worker 模式检测 · awaitAutomatedChecksBeforeDialog 的数据源。
     *
     * <p>对齐 CC runAgent.ts:461（coordinator worker fork 时
     * {@code awaitAutomatedChecksBeforeDialog: true}）+ useCanUseTool.tsx:95-109
     * （该标志 true → ask 分支先顺序 await hooks + classifier 再弹窗）。
     * null（无 bean / 未配置）→ false（保持 H9 前行为，不改变默认弹窗路径）。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.coordinator.CoordinatorMode coordinatorMode;

    /**
     * [IMP-3 G1] 企业管控 managed-only 判定器；null = 未注入（无企业管控，不门控）。
     *
     * <p>经 {@link #setManagedPolicy(PermissionManagedPolicy)} 注入（生产由 Spring 注入，
     * 测试可手动注入），驱动加载循环的 managed-only 过滤（对齐 CC
     * {@code loadAllPermissionRulesFromDisk}，permissionsLoader.ts:120-133）。
     */
    private PermissionManagedPolicy managedPolicy;

    /** [canUseTool v2] awaitAutomatedChecksBeforeDialog 计算 · 仅 coordinator worker 模式为 true。 */
    private boolean isAwaitAutomatedChecksBeforeDialog() {
        return coordinatorMode != null && coordinatorMode.isCoordinatorMode();
    }

    /**
     * [IMP-3 G1] 注入 managed-only 判定器 · 对齐 CC {@code loadAllPermissionRulesFromDisk}
     * （permissionsLoader.ts:120-133）managed-only 时只加载 policy 源。
     *
     * <p>生产由 {@code @Autowired(required = false)} 注入 {@link PermissionManagedPolicy} bean
     * （与 hooks 侧 managed policy supplier 同源）；非 Spring 单元测试可经本 setter 注入
     * （null = 无企业管控，不门控）。
     *
     * @param managedPolicy managed-only 判定器（null 忽略，保持全源加载）
     */
    @Autowired(required = false)
    public void setManagedPolicy(PermissionManagedPolicy managedPolicy) {
        this.managedPolicy = managedPolicy;
    }

    /**
     * 无参构造器 · 保留以兼容 PR 1-4 已 push 的测试
     * （{@code PermissionContextBuilderTest} / {@code LlmAgentLoopPermissionIntegrationTest}）。
     *
     * <p>无 loader 时 {@link #buildPermissionContext(AgentState, boolean, PermissionMode, boolean, boolean)} 等价 PR 1 行为
     * （空规则集）。Spring 注入路径走 {@link #PermissionContextBuilder(List)}。
     */
    public PermissionContextBuilder() {
        this.loaders = Collections.emptyList();
    }

    /**
     * Spring 注入构造器。
     *
     * @param loaders source loader 链（Spring 注入所有 {@link PermissionSourceLoader} bean）
     */
    public PermissionContextBuilder(List<PermissionSourceLoader> loaders) {
        if (loaders == null) {
            throw new IllegalArgumentException("loaders is null");
        }
        // 防御性 copy + 不可变 view（注入的 List 后续可能 mutate）
        this.loaders = List.copyOf(loaders);
        if (log.isInfoEnabled()) {
            log.info("PermissionContextBuilder initialized with {} loader(s): {}",
                this.loaders.size(),
                this.loaders.stream().map(l -> l.source().name()).toList());
        }
    }

    /**
     * 构造 {@link ToolUseContext}（传 {@code tool.checkPermissions / validateInput}）。
     *
     * <p>Phase 1 字段映射：
     * <ul>
     *   <li>{@code agentId} = {@link AgentState#agentId()}</li>
     *   <li>{@code sessionId} = {@link AgentState#sessionId()}</li>
     *   <li>{@code mode} = {@link PermissionMode#DEFAULT}</li>
     *   <li>{@code additionalWorkingDirectories} — 不属于 {@link ToolUseContext}，由
     *       {@link #buildPermissionContext(AgentState, boolean, PermissionMode, boolean, boolean)} 构造（IMP-5 起含 symlink PWD 注入）</li>
     * </ul>
     *
     * @param state 当前 agent state
     * @return 工具调用上下文
     * @throws IllegalArgumentException 若 {@code state.sessionId/agentId} 为 null
     */
    public ToolUseContext buildToolUseContext(AgentState state) {
        if (state == null) {
            throw new IllegalArgumentException("state is null");
        }
        UUID agentId = state.agentId();
        String sessionId = state.sessionId();
        if (sessionId == null) {
            throw new IllegalArgumentException("AgentState.sessionId is null");
        }
        if (agentId == null) {
            throw new IllegalArgumentException("AgentState.agentId is null");
        }
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    /**
     * [R28] 带 AbortController + messages 的 buildToolUseContext · 对齐 CC Tool.ts:180 + Tool.ts:250。
     *
     * <p>LlmAgentLoop 在 run() 入口构造 AbortController + messages 不可变快照，
     * applyPermissionFilter 调此方法透传到 hook/permission 检查。L2 不可触碰：
     * 老 buildToolUseContext(state) 仍可用（messages 默认空 List）。
     */
    public ToolUseContext buildToolUseContext(AgentState state,
            com.nexusai.application.agent.tool.AbortController abortController,
            List<?> messages) {
        ToolUseContext base = buildToolUseContext(state);
        if (abortController == null && (messages == null || messages.isEmpty())) return base;
        return ToolUseContext.of(base.agentId(), base.sessionId(), base.mode(),
            base.availableTools(), base.taskListId(), abortController, messages);
    }

    /**
     * [F1-BY] 带 base {@code isBypassPermissionsModeAvailable} 的 5 参重载 · per-turn 重建保真来源。
     *
     * <p><b>WHY (F1)</b>: CC 的 {@code isBypassPermissionsModeAvailable} 是启动时<b>一次性</b>计算
     * （permissionSetup.ts:938-944），per-turn 重建<b>保留原值、不重算</b>（applyPermissionUpdate
     * setMode 用 {@code {...context, mode}} spread 保留该字段，PermissionUpdate.ts:60-67）。旧便捷重载（1/2/3/4 参，已删除）
     * 硬编码 true → org/settings 在启动时禁用 bypass 后，per-turn 重建又把可用性翻回 true，
     * 使 {@code CheckLayer2a_BypassMode:80} 的禁用门在 per-turn 失效。本重载让调用方透传 base
     * permCtx 的 {@code isBypassPermissionsModeAvailable()}，对齐 CC 保留语义。
     *
     * <p>调用方：{@link AgentLoopContext#toolExecContext} 读
     * {@code baseTuc.permissionContext().isBypassPermissionsModeAvailable()}（与
     * {@code shouldAvoidPermissionPrompts} 同法保真）。base permCtx 为 null 时 fallback false
     * （对齐 CC Tool.ts:147 默认 false）。
     *
     * @param state                            当前 agent state
     * @param awaitAutomatedChecksBeforeDialog 是否等待自动化检查后再弹窗
     * @param mode                             调用方显式权限模式 (null → DEFAULT)
     * @param shouldAvoidPermissionPrompts     是否避免权限弹窗
     * @param baseBypassPermissionsModeAvailable base permCtx 的 isBypassPermissionsModeAvailable
     *                                          （启动时按 CC 三条件公式计算的一次性值）
     * @return 权限上下文
     */
    public ToolPermissionContext buildPermissionContext(AgentState state,
            boolean awaitAutomatedChecksBeforeDialog, PermissionMode mode,
            boolean shouldAvoidPermissionPrompts, boolean baseBypassPermissionsModeAvailable) {
        return buildPermissionContextCore(state, awaitAutomatedChecksBeforeDialog, mode,
            shouldAvoidPermissionPrompts,
            InitialPermissionModeResolver.Input.empty(),
            InitialPermissionModeResolver.Config.defaults(),
            baseBypassPermissionsModeAvailable);
    }

    /**
     * [WF3-01 RV-11] 带初始 mode 多源解析输入的构造 · 生产来源（初始 mode 链 + bypass 禁用门）。
     *
     * <p><b>WHY (RV-11)</b>: 旧实现 {@code effectiveMode = mode != null ? mode : DEFAULT_MODE}
     * 恒 DEFAULT + {@code isBypassPermissionsModeAvailable=true} 硬编码 —— 无 CLI/settings/env 链。
     * 本重载注入 {@link InitialPermissionModeResolver.Input}/{@link InitialPermissionModeResolver.Config}，
     * 在 {@code mode == null}（调用方未显式指定）时按 CC {@code initialPermissionModeFromCLI}
     * 多源优先级链（dangerouslySkipPermissions &gt; CLI --permission-mode &gt; settings.defaultMode
     * &gt; default）解析初始 mode，并用 {@link BypassPermissionsKillswitch#isBypassPermissionsModeDisabled}
     * 判定 bypass 可用性（CC permissionSetup.ts:939-944）。
     *
     * @param state                            当前 agent state
     * @param awaitAutomatedChecksBeforeDialog 是否等待自动化检查后再弹窗
     * @param mode                             调用方显式权限模式（null → 走初始 mode 解析链）
     * @param shouldAvoidPermissionPrompts     是否避免权限弹窗
     * @param initialModeInput                 初始 mode 多源输入（CLI/dangerouslySkip/settings）
     * @param initialModeConfig                初始 mode 依赖注入（Statsig 门/classifier 门/CCR）
     * @return 权限上下文
     */
    public ToolPermissionContext buildPermissionContext(AgentState state,
            boolean awaitAutomatedChecksBeforeDialog, PermissionMode mode,
            boolean shouldAvoidPermissionPrompts,
            InitialPermissionModeResolver.Input initialModeInput,
            InitialPermissionModeResolver.Config initialModeConfig) {
        // [REV-FIX-3] 6 参真实输入路径 → override=null → 走 CC 公式（permissionSetup.ts:939-943），
        // 行为零变化。
        return buildPermissionContextCore(state, awaitAutomatedChecksBeforeDialog, mode,
            shouldAvoidPermissionPrompts, initialModeInput, initialModeConfig, null);
    }

    /**
     * 核心构造 · 5/6 参重载共用（[REV-FIX-3] 自 6 参重载抽出）。
     *
     * <p><b>WHY</b>: CC 的 {@code isBypassPermissionsModeAvailable} 是
     * {@code initializeToolPermissionContext}（permissionSetup.ts:872-886）启动时<b>一次性</b>
     * 计算的 ToolPermissionContext 字段（:939-943），per-turn 重建保留原值、不重算。Java 端
     * 5 参链（per-turn 重建）无真实 CLI/settings 输入，若把可用性交给
     * {@code Input.empty()} 重算 → 恒 false → {@code CheckLayer2a_BypassMode:80} 使 bypass
     * 静默失效。本方法以 {@code availabilityOverride} 区分两条可用性来源：
     * <ul>
     *   <li><b>{@code override != null}</b>（5 参链传 base 值）→ 直接用 override，
     *       临时兜底 pre-RV-11 语义（reflector「临时保留 true 兜底」）；</li>
     *   <li><b>{@code override == null}</b>（6 参真实输入路径）→ CC 公式
     *       （permissionSetup.ts:939-943）。</li>
     * </ul>
     *
     * @param state                            当前 agent state
     * @param awaitAutomatedChecksBeforeDialog 是否等待自动化检查后再弹窗
     * @param mode                             调用方显式权限模式（null → 走初始 mode 解析链）
     * @param shouldAvoidPermissionPrompts     是否避免权限弹窗
     * @param initialModeInput                 初始 mode 多源输入（CLI/dangerouslySkip/settings）
     * @param initialModeConfig                初始 mode 依赖注入（Statsig 门/classifier 门/CCR）
     * @param availabilityOverride             可用性覆盖（null → CC 公式；非 null → 直接用）
     * @return 权限上下文
     */
    private ToolPermissionContext buildPermissionContextCore(AgentState state,
            boolean awaitAutomatedChecksBeforeDialog, PermissionMode mode,
            boolean shouldAvoidPermissionPrompts,
            InitialPermissionModeResolver.Input initialModeInput,
            InitialPermissionModeResolver.Config initialModeConfig,
            Boolean availabilityOverride) {
        if (state == null) {
            throw new IllegalArgumentException("state is null");
        }

        // Phase 2 PR 1: 3 桶（allow/deny/ask）+ 8 source 索引
        Map<PermissionRuleSource, Set<PermissionRule>> allow =
            new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> deny =
            new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> ask =
            new EnumMap<>(PermissionRuleSource.class);

        // [IMP-3 G1] 对齐 CC loadAllPermissionRulesFromDisk（permissionsLoader.ts:120-133）：
        //   allowManagedPermissionRulesOnly 为 true 时只加载 POLICY_SETTINGS 源规则，
        //   跳过 user/project/local 等所有可编辑源。
        boolean managedOnly = managedPolicy != null && managedPolicy.shouldAllowManagedPermissionRulesOnly();
        if (managedOnly && log.isDebugEnabled()) {
            log.debug("PermissionContextBuilder: managed-only 门控生效，仅加载 POLICY_SETTINGS 源规则（对齐 CC loadAllPermissionRulesFromDisk:120-133）");
        }

        int totalRules = 0;
        for (PermissionSourceLoader loader : loaders) {
            if (managedOnly && loader.source() != PermissionRuleSource.POLICY_SETTINGS) {
                // managed-only 跳过非 policy 源（CC :122-123 只 return getPermissionRulesForSource('policySettings')）
                continue;
            }
            List<PermissionRule> rules;
            try {
                // 调 load(String)：现有 loader 的 default load(String) 自动转调 load()
                // （[DEL-WF1-03] SessionSource 已删，无 loader 覆写 per-session 语义）
                rules = loader.load(state.sessionId());
            } catch (Exception e) {
                // lenient 加载：单个 loader 抛异常不影响其他 loader
                // 大多数 loader 内部已经 try-catch，这里是兜底
                log.warn("PermissionContextBuilder: loader {} failed: {}",
                    loader.source(), e.getMessage());
                continue;
            }
            if (rules == null || rules.isEmpty()) {
                continue;
            }

            for (PermissionRule rule : rules) {
                Set<PermissionRule> bucket = switch (rule.ruleBehavior()) {
                    case ALLOW -> allow.computeIfAbsent(rule.source(),
                        k -> new HashSet<>());
                    case DENY  -> deny.computeIfAbsent(rule.source(),
                        k -> new HashSet<>());
                    case ASK   -> ask.computeIfAbsent(rule.source(),
                        k -> new HashSet<>());
                };
                bucket.add(rule);
            }
            totalRules += rules.size();
        }

        // [WF3-01 RV-11] effectiveMode：显式 mode 优先；null → 初始 mode 多源解析链
        // （对齐 CC initialPermissionModeFromCLI，permissionSetup.ts:689-808）。
        InitialPermissionModeResolver.Result initialMode =
            InitialPermissionModeResolver.resolve(initialModeInput, initialModeConfig);
        PermissionMode effectiveMode = mode != null ? mode : initialMode.mode();

        // [WF3-01 RV-11] isBypassPermissionsModeAvailable 从 hardcoded true → CC 判定
        // （permissionSetup.ts:939-944）：(mode==bypassPermissions || allowDangerouslySkipPermissions)
        //   && !禁用门（Statsig cached 门 || settings.disableBypassPermissionsMode==='disable'）。
        // [REV-FIX-3] 5 参链（override!=null）跳过 CC 公式：无真实 CLI/settings 输入时
        //   不把可用性交给空输入重算（恒 false 会静默禁用 bypass），临时兜底 pre-RV-11 语义。
        boolean isBypassPermissionsModeAvailable;
        if (availabilityOverride != null) {
            isBypassPermissionsModeAvailable = availabilityOverride;
        } else {
            // [F1-BY] 收敛统一计算：改调 BypassPermissionsKillswitch.isBypassPermissionsModeAvailable
            //   （CC permissionSetup.ts:938-944 三条件公式），去内联（原 :445-451 内联公式移除）。
            isBypassPermissionsModeAvailable =
                BypassPermissionsKillswitch.isBypassPermissionsModeAvailable(
                    effectiveMode,
                    initialModeInput.dangerouslySkipPermissions(),
                    initialModeConfig.statsigDisableBypassPermissionsMode(),
                    initialModeInput.settingsDisableBypassPermissionsMode());
        }

        if (log.isDebugEnabled()) {
            log.debug(
                "PermissionContextBuilder.buildPermissionContextCore: sessionId={} mode={} "
                    + "bypassAvailable={}（来源：{}）shouldAvoidPrompts={} loaders={} rules={} "
                    + "(allow={}, deny={}, ask={})",
                state.sessionId(), effectiveMode, isBypassPermissionsModeAvailable,
                availabilityOverride != null ? "保留 base 值(per-turn/兜底)" : "CC 公式",
                shouldAvoidPermissionPrompts, loaders.size(), totalRules,
                countRules(allow), countRules(deny), countRules(ask)
            );
        }

        ToolPermissionContext ctx = new ToolPermissionContext(
            effectiveMode, allow, deny, ask,
            symlinkPwdWorkingDirectories(System.getenv("PWD"),
                // [G10 · OD-FINAL-3] originalCwd 走统一入口 CwdResolution.getOriginalCwdLayer(sessionId)
                //   （对齐 CC initializeToolPermissionContext permissionSetup.ts:917-928 读 getOriginalCwd()，
                //   非 user.dir）。原 Java 直读 System.getProperty("user.dir") → worktree/绑定项目场景
                //   originalCwd 恒 JVM 启动目录，PWD 与之不等时误判非 symlink（违反 G10 对齐）。
                //   state.sessionId() 此处已校验非 null（buildPermissionContextCore 入口 :205）。
                //   [session-id-short] sessionId 已 String，恒等直传。
                CwdResolution.getOriginalCwdLayer(state.sessionId())),
            isBypassPermissionsModeAvailable, true,
            // [H9-v2 + H14/H13 v3] awaitAutomatedChecksBeforeDialog 参数（BUBBLE 派生）OR
            //   coordinatorMode 来源：H14/H13 v3 都补接线 isAwaitAutomatedChecksBeforeDialog()
            //   （此前死 helper 未调用 → coordinator worker 模式永不置 true）。现在 coordinator
            //   worker 模式 (CC runAgent.ts:457-464) 也置 true（gate 的 coordinator 分支有生产入口）。
            Map.of(), shouldAvoidPermissionPrompts, awaitAutomatedChecksBeforeDialog || isAwaitAutomatedChecksBeforeDialog(), null
        );

        // s03 ShadowedRuleDetector：检测被覆盖的规则，记录 warn 日志（不阻断流程）
        if (shadowedRuleDetector != null) {
            List<ShadowedRuleDetector.ShadowedRule> shadowedRules =
                shadowedRuleDetector.detectShadowedRules(ctx);
            if (!shadowedRules.isEmpty()) {
                log.warn("PermissionContextBuilder: detected {} shadowed rule(s): {}",
                    shadowedRules.size(),
                    shadowedRules.stream()
                        .map(sr -> sr.rule().ruleValue().toolName()
                            + " [" + sr.rule().source() + "] — " + sr.reason())
                        .toList());
            }
        }

        // [S04] DangerousPatternDetector × AutoModeGate —— auto 模式构建上下文时剥离危险规则并 stash
        // 对齐 CC stripDangerousPermissionsForAutoMode（permissionSetup.ts:510-553）：
        //   - 剥离只发生在 auto 模式（CC-PERM-09；旧实现仅看 gate 开关、非 auto 也剥离，已修正）
        //   - 被剥离规则 stash 进 ctx.strippedDangerousRules（CC-PERM-25），退出 auto 时由
        //     DangerousPatternDetector.restoreDangerousPermissions 恢复（恢复触发接线在 S10）
        if (dangerousPatternDetector != null
                && autoModeGate != null && autoModeGate.isEnabled()
                && effectiveMode == PermissionMode.AUTO) {
            ctx = dangerousPatternDetector.stripDangerousPermissionsForAutoMode(ctx);
            if (log.isDebugEnabled()) {
                int stashed = ctx.strippedDangerousRules().values().stream()
                    .mapToInt(Set::size).sum();
                log.debug("PermissionContextBuilder: auto 模式剥离危险规则完成，"
                    + "strippedDangerousRules={} 条", stashed);
            }
        }

        return ctx;
    }

    /**
     * [IMP-5 WDS] symlink PWD → additionalWorkingDirectories(source=session) 注入 ·
     * 对齐 CC {@code initializeToolPermissionContext}（permissionSetup.ts:917-928）。
     *
     * <p><b>WHY</b>: CC 启动时读 {@code process.env.PWD}（shell 上报的 cwd，可能是 symlink 路径，
     * 如用户 {@code cd} 进 symlink 目录），若该 PWD 是 symlink 且 realpath 解析等于 originalCwd，
     * 则把 PWD 以 {@code source='session'} 注入 additionalWorkingDirectories，让用户通过 symlink
     * 看到的 cwd 落入权限工作目录范围（后续 ReadPermissionChecker / BashPathValidator 按此判定）。
     * Java 端权限上下文 per-turn 重建，本方法每次幂等重算（一次 lstat + realpath，开销可忽略）。
     *
     * <p>注入条件（与 CC 一致）：processPwd 非空 且 != originalCwd 且 realpath(processPwd) != processPwd
     * （realpath 与入参不等即视为 symlink，对齐 CC {@code safeResolvePath} fsOperations.ts:163-171
     * {@code isSymlink = resolvedPath !== filePath}，含中间组件 symlink，如 /var/log 是 symlink、
     * PWD=/var/log/foo）且 realpath(processPwd) == realpath(originalCwd)。解析失败视为非 symlink
     * （对齐 CC {@code safeResolvePath} fsOperations.ts:172-177 失败返回原路径 + isSymlink=false）。
     *
     * @param processPwd  shell 上报的 PWD（{@code System.getenv("PWD")}），可能为 symlink 路径
     * @param originalCwd 进程真实 cwd（{@code System.getProperty("user.dir")}，等价 CC getOriginalCwd）
     * @return 附加工作目录 Map（无 symlink PWD 时为空 Map）
     */
    static Map<String, AdditionalWorkingDirectory> symlinkPwdWorkingDirectories(
            String processPwd, String originalCwd) {
        if (processPwd == null || processPwd.isBlank()
                || originalCwd == null || originalCwd.isBlank()) {
            return Map.of();
        }
        if (processPwd.equals(originalCwd)) {
            return Map.of();
        }
        try {
            Path processPwdPath = Path.of(processPwd);
            Path resolvedProcessPwd = processPwdPath.toRealPath();
            // [IMP-5 WDS 返工] isSymlink 判定对齐 CC safeResolvePath（fsOperations.ts:163-171）：
            //   isSymlink = resolvedPath !== filePath（realpath 与入参不等即视为 symlink）。
            //   旧实现 Files.isSymbolicLink(processPwdPath) 只判路径最后一段是否 symlink，
            //   漏判中间组件 symlink（如 /var/log 是 symlink、PWD=/var/log/foo）。
            //   现用 realpath != 入参 语义，中间组件 symlink 也会被识别并注入。
            if (resolvedProcessPwd.equals(processPwdPath)) {
                return Map.of();
            }
            Path resolvedOriginalCwd = Path.of(originalCwd).toRealPath();
            if (resolvedProcessPwd.equals(resolvedOriginalCwd)) {
                if (log.isDebugEnabled()) {
                    log.debug("PermissionContextBuilder: 检测到 symlink PWD {} 解析为 originalCwd {}，注入 session 附加工作目录",
                        processPwd, originalCwd);
                }
                return Map.of(processPwd,
                    new AdditionalWorkingDirectory(processPwd, PermissionRuleSource.SESSION));
            }
        } catch (IOException | RuntimeException e) {
            // CC safeResolvePath（fsOperations.ts:172-177）：lstat/realpath 失败返回原路径 + isSymlink=false，不注入
            if (log.isDebugEnabled()) {
                log.debug("PermissionContextBuilder: symlink PWD 解析失败，跳过注入: {}", e.getMessage());
            }
        }
        return Map.of();
    }

    /**
     * 数所有 source 的 rule 总数（debug 日志用）。
     *
     * @param map source → rule set 映射
     * @return 所有 source 的 rule 总数
     */
    private static int countRules(Map<PermissionRuleSource, Set<PermissionRule>> map) {
        int total = 0;
        for (Set<PermissionRule> rules : map.values()) {
            total += rules.size();
        }
        return total;
    }

    /**
     * 暴露给测试 / 调试：当前注入的 loader 数量。
     *
     * @return loader 数量（0 = 无 loader 注入）
     */
    public int loaderCount() {
        return loaders.size();
    }

    /**
     * 暴露给测试 / 调试：当前注入的 loader 列表（不可变 copy）。
     *
     * @return loader 列表
     */
    public List<PermissionSourceLoader> loaders() {
        return new ArrayList<>(loaders);
    }
}