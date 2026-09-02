package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.tasks.TaskSystemConfig;

/**
 * [H7-arch Phase 5 P4] 三项 feature-gated 能力开关 · 对齐 CC feature() flag。
 *
 * <p><b>WHY 存在</b>: CC query.ts 中 reactiveCompact / contextCollapse / skillPrefetch
 * 均为 feature flag 控制（{@code REACTIVE_COMPACT} / {@code CONTEXT_COLLAPSE} /
 * {@code EXPERIMENTAL_SKILL_SEARCH}），flag 关闭时模块为 {@code null}、所有调用点带
 * 空值保护（{@code ?.} / {@code &&}）。Java 端把这三个 flag 收敛为单一不可变 record，
 * 与 {@link com.nexusai.application.agent.loop.AgentLoopContext} 中可空组件字段配合，
*  实现"默认全关 + 空值保护"的 CC 对齐语义。
 *
 * <p><b>默认全关</b>: 对齐 CC 快照中这三个组件均为实验性、默认不启用。要开启某项能力，
 * 需同时把对应 flag 置 true 并注入对应组件（{@link AgentLoopContext#reactiveCompactor()} /
 * {@link AgentLoopContext#contextCollapse()} / {@link AgentLoopContext#skillDiscoveryPrefetch()}）。
 *
 * <p><b>[IMP-02 D-27] Spring bean 接线</b>: {@link FeatureFlagsConfig} 提供生产配置
 * {@code @Bean FeatureFlags}，从属性 {@code nexusai.feature.*} 读取（默认全关）。
 * {@link com.nexusai.application.agent.loop.AgentLoopContextFactory} 注入后，
 * reactive/collapse 分支在生产可开关（D-27 W1 接线缺口修复）。
 *
 * @param reactiveCompact {@code REACTIVE_COMPACT} flag · CC query.ts:15
 * @param contextCollapse {@code CONTEXT_COLLAPSE} flag · CC query.ts:18
 * @param skillPrefetch   {@code EXPERIMENTAL_SKILL_SEARCH} flag · CC query.ts:66
 * @param promptCacheBreakDetection {@code PROMPT_CACHE_BREAK_DETECTION} flag ·
 *        CC claude.ts:1469 / promptCacheBreakDetection.ts · 默认 false（OPD-SP-14 门控关，
 *        关时 PromptCacheBreakDetection 的 record/check 为 no-op）
 * @param tenguMothCopse {@code tengu_moth_copse} GB flag · CC 单一 flag 源驱动 4 处：
 *        attachments.ts:2367（startRelevantMemoryPrefetch 预取门）+ claudemd.ts:1146
 *        （filterInjectedMemoryFiles 过滤）+ memdir.ts:423（loadMemoryPrompt skipIndex）+
 *        extractMemories.ts:367（提取 prompt skipIndex）· 默认 false（GB 未接入）。
 *        IMP-MV2-12（单轨收敛）：Java 4 消费点全收敛本字段 —— memoryPrefetcher /
 *        claudemdEngine（ToolRegistrationConfig :982/:1115）+ MemoryPromptBuilder
 *        productionDefaultWithMothCopse（LlmAgentLoop 装配）+ extractMemoriesAgent
 *        setSkipIndexGate（ToolRegistrationConfig bean）；轨 1 mothCopseFlag 已删除。
 * @param historySnip {@code HISTORY_SNIP} flag · CC query.ts:115/401 · 默认 false（关时
 *        snip 模块为 null，query 主循环跳过 snip 步骤，在 microcompact 之前运行、二者非互斥）
 * @param budgetAggregateGate {@code tengu_hawthorn_steeple} GB flag · CC toolResultStorage.ts:451-455 ·
 *        默认 false。true=执行 applyToolResultBudget（强制聚合 tool-result 预算）、false=跳过
 *        （contentReplacementState=undefined，query.ts:369-372 applyToolResultBudget no-op）
 * @param smSessionMemory {@code tengu_session_memory} GB flag · CC sessionMemoryCompact.ts:412-415 ·
 *        默认 false（与 smCompact 作 AND，决定 shouldUseSessionMemoryCompaction）
 * @param smCompact {@code tengu_sm_compact} GB flag · CC sessionMemoryCompact.ts:416-419 ·
 *        默认 false（与 smSessionMemory 作 AND）
 * @param tenguDisableStreamingToNonStreamingFallback
 *        {@code tengu_disable_streaming_to_non_streaming_fallback} GB flag ·
 *        CC claude.ts:2469-2474 · 默认 false。true=禁用流式→非流式回退（mid-stream fallback 导致
 *        流式工具执行时双工具执行，inc-4258；门开启时直接抛流式错误不回退，CC claude.ts:2476-2501）
 * @param bgSessions {@code BG_SESSIONS} flag · CC query.ts:118/1685 · 默认 false（关时
 *        taskSummaryModule=null，periodic task summary 整链断链 —— 对齐 CC 当前基线构建期 off）
 * @param overflowTestTool {@code OVERFLOW_TEST_TOOL} feature flag · CC tools.ts:107-108
 *        （OverflowTestTool 条件构建门 · 默认 false）。<b>死标志（G30⑫ 显式登记）</b>：
 *        OverflowTestTool 类 + 注册 + 常量 + AutoModeAllowlist 已删除（CC tools.ts:107-108/221
 *        的 OVERFLOW_TEST_TOOL 在 CC 亦为纯测试工具、Java 无对应测试通道）。本字段无消费方，
 *        保留以对齐 CC tools.ts:107 {@code feature('OVERFLOW_TEST_TOOL')} 死代码结构，字段不
 *        再驱动任何注册逻辑。
 * @param terminalPanel {@code TERMINAL_PANEL} feature flag · CC tools.ts:113-115
 *        （TerminalCaptureTool 条件构建门 · 默认 false）
 * @param verifyPlan {@code CLAUDE_CODE_VERIFY_PLAN} env gate · CC tools.ts:91-94
 *        （VerifyPlanExecutionTool 条件构建门 · 默认 false，env==='true' 时开启）
 * @param workflowScripts {@code WORKFLOW_SCRIPTS} feature flag · CC tools.ts:129-133
 *        （WorkflowTool 条件构建门 · 默认 false）
 * @param monitorTool {@code MONITOR_TOOL} feature flag · CC tools.ts:39-40
 *        （MonitorTool 条件构建门 · 默认 false）
 * @param testingPermission {@code NODE_ENV==='test'} env gate · CC tools.ts:244
 *        （TestingPermissionTool 注册点 · 默认 false；CC 恒 "production"==='test' 即恒 false，
 *        Java 按 NODE_ENV=test 门控，语义差异登记 B5 concerns）
 * @param usePowerShellTool {@code CLAUDE_CODE_USE_POWERSHELL_TOOL} + {@code USER_TYPE} 三元 env gate ·
 *        CC shellToolUtils.ts:17-22 {@code isPowerShellToolEnabled()} 的「平台之外」门控因子：
 *        Windows 且 USER_TYPE==='ant' → 默认开（env 显式假才关）；Windows 且 USER_TYPE!=='ant' → 默认关
 *        （env 显式真才开）；非 Windows 恒 false（由 PowerShellTool.isEnabled() 的 isWindows() 短路）。
 *        本字段只承载 USER_TYPE + env 的三元结论，平台因子由 PowerShellTool 侧合成
 *        （OPD-TOOL-35 门控统一：消除 PowerShellTool.isEnabled() 纯平台判断）。
 * @param tokenBudget {@code TOKEN_BUDGET} flag · CC query.ts:280/:1308（checkTokenBudget 预算
 *        门控上下文）+ prompts.ts:538（token_budget system prompt section 注册门）+
 *        attachments.ts:3829（output_token_usage attachment 生产门）· 默认 false（关时
 *        token_budget section 与 output_token_usage attachment 均不注入，对齐 CC feature() 缺省）
 * @param teamMem {@code TEAMMEM} flag · CC memory/types.ts:9（MEMORY_TYPE_VALUES 中
 *        'TeamMem' 仅 {@code feature('TEAMMEM')} 开启时在值域）· 默认 false（OPD-CM3-10/B03：
 *        bun:bundle 编译期宏仓库不可读，用可配置开关模拟，对齐 CC 发行默认关；供 IMP-CM-09
 *        双门控拆分 / IMP-CM-11 条件值域消费）
 * @param tenguHerringClock {@code tengu_herring_clock} GB flag · CC teamMemPaths.ts:77
 *        （isTeamMemoryEnabled 运行时闸：isAutoMemoryEnabled() && getFeatureValue_CACHED_MAY_BE_STALE('tengu_herring_clock', false)）
 *        + watcher.ts:256 · 默认 false（OPD-CM3-11/B04：运行时开关，与编译开关 teamMem 独立控制；
 *        供 IMP-CM-09 TeamMemPaths 双门控拆分消费）
 * @param destructiveCommandWarning {@code tengu_destructive_command_warning} GB flag · CC
 *        BashPermissionRequest.tsx:274 {@code getFeatureValue_CACHED_MAY_BE_STALE('tengu_destructive_command_warning', false) ?
 *        getDestructiveCommandWarning(command) : null} · 默认 false（外部构建默认关 → destructiveWarning 恒 null；
 *        对齐 CC 外部构建语义）。IMP-H R1：WebSocketPermissionPrompter.renderPermissionWarning 破坏性命令
 *        分支门控接线用；sed 编辑分支（CC BashPermissionRequest.tsx:89）不门控。
 * @param coralFern {@code tengu_coral_fern} GB flag · CC memdir.ts:376
 *        （{@code buildSearchingPastContextSection} 门控：feature 开 → 注入「Searching past context」段，
 *        关 → 返回空数组）。默认 false。OPD-CM5-C-10 补开关：新增本访问器 +
 *        MemoryPromptBuilder.productionDefault 接线（feature 开时 searching-past 段生产可达，对齐 CC
 *        运行时 GB flag 切换）+ projectRoot 注入（transcript 搜索路径用会话 projectRoot，
 *        buildSearchingPastContextSection 已读 {@code autoMemPaths.projectRoot()}）。
 * @param tenguPaperHalyard {@code tengu_paper_halyard} GB flag · CC claudemd.ts:1158-1161
 *        （getClaudeMds skipProjectLevel）+ attachments.ts:1823-1826（getNestedMemoryAttachmentsForFile
 *        skipProjectLevel）· 默认 false。true=跳过 Project/Local 记忆注入（claudemd.ts:1165-1166 /
 *        attachments.ts:1833-1835/1850-1852）。F1-4 补接线：FeatureFlagsConfig 属性 +
 *        访问器 + ToolRegistrationConfig claudemdEngine bean setPaperHalyardGate。
 */
public record FeatureFlags(
        boolean reactiveCompact,
        boolean contextCollapse,
        boolean skillPrefetch,
        boolean promptCacheBreakDetection,
        boolean tenguMothCopse,
        boolean historySnip,
        boolean budgetAggregateGate,
        boolean smSessionMemory,
        boolean smCompact,
        boolean tenguDisableStreamingToNonStreamingFallback,
        boolean bgSessions,
        boolean overflowTestTool,
        boolean terminalPanel,
        boolean verifyPlan,
        boolean workflowScripts,
        boolean monitorTool,
        boolean testingPermission,
        boolean usePowerShellTool,
        boolean tokenBudget,
        boolean teamMem,
        boolean tenguHerringClock,
        boolean destructiveCommandWarning,
        boolean coralFern,
        boolean tenguPaperHalyard
) {

    /**
     * [IMP-H R1] 21 参兼容构造器 · destructiveCommandWarning 缺省 false（对齐 CC 外部构建默认关）。
     * 保留既有 21 参调用方（FeatureFlags 前 21 字段），新增字段默认 false。
     * <p>[IMP-C-6 · OPD-CM5-C-10] coralFern 亦缺省 false（对齐 CC GB flag 默认关）——经 22 参兼容
     * 构造器链到规范构造器。
     */
    public FeatureFlags(boolean reactiveCompact, boolean contextCollapse, boolean skillPrefetch,
                        boolean promptCacheBreakDetection, boolean tenguMothCopse,
                        boolean historySnip, boolean budgetAggregateGate, boolean smSessionMemory,
                        boolean smCompact, boolean tenguDisableStreamingToNonStreamingFallback,
                        boolean bgSessions, boolean overflowTestTool, boolean terminalPanel,
                        boolean verifyPlan, boolean workflowScripts, boolean monitorTool,
                        boolean testingPermission, boolean usePowerShellTool, boolean tokenBudget,
                        boolean teamMem, boolean tenguHerringClock) {
        this(reactiveCompact, contextCollapse, skillPrefetch, promptCacheBreakDetection,
            tenguMothCopse, historySnip, budgetAggregateGate, smSessionMemory, smCompact,
            tenguDisableStreamingToNonStreamingFallback, bgSessions, overflowTestTool,
            terminalPanel, verifyPlan, workflowScripts, monitorTool, testingPermission,
            usePowerShellTool, tokenBudget, teamMem, tenguHerringClock, false);
    }

    /**
     * [IMP-C-6 · OPD-CM5-C-10] 22 参兼容构造器 · coralFern 缺省 false（对齐 CC GB flag 默认关）。
     * 保留既有 22 参调用方（FeatureFlagsBeanWiringTest 规范构造器测试），新增字段默认 false。
     * <p>[F1-4] tenguPaperHalyard 亦缺省 false（对齐 CC GB flag 默认关）——经 23 参兼容
     * 构造器链到规范构造器。
     */
    public FeatureFlags(boolean reactiveCompact, boolean contextCollapse, boolean skillPrefetch,
                        boolean promptCacheBreakDetection, boolean tenguMothCopse,
                        boolean historySnip, boolean budgetAggregateGate, boolean smSessionMemory,
                        boolean smCompact, boolean tenguDisableStreamingToNonStreamingFallback,
                        boolean bgSessions, boolean overflowTestTool, boolean terminalPanel,
                        boolean verifyPlan, boolean workflowScripts, boolean monitorTool,
                        boolean testingPermission, boolean usePowerShellTool, boolean tokenBudget,
                        boolean teamMem, boolean tenguHerringClock, boolean destructiveCommandWarning) {
        this(reactiveCompact, contextCollapse, skillPrefetch, promptCacheBreakDetection,
            tenguMothCopse, historySnip, budgetAggregateGate, smSessionMemory, smCompact,
            tenguDisableStreamingToNonStreamingFallback, bgSessions, overflowTestTool,
            terminalPanel, verifyPlan, workflowScripts, monitorTool, testingPermission,
            usePowerShellTool, tokenBudget, teamMem, tenguHerringClock, destructiveCommandWarning, false, false);
    }

    /**
     * 默认全关 · 对齐 CC flag 关闭时模块为 null。
     */
    public static final FeatureFlags ALL_DISABLED = new FeatureFlags(false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);

    /**
     * [IMP-02 D-27] 生产配置 @Bean · 对齐 CC feature() flag（query.ts:15/18/66）。
     *
     * <p><b>WHY</b>: D-27 W1 接线缺口——{@code AgentLoopContextFactory} 的
     * {@code @Autowired(required=false) FeatureFlags} 生产恒取默认值 ALL_DISABLED
     * （无 bean 注入），reactive/collapse/snip 三能力无法开启。本配置类被组件扫描
     * 加载，提供 {@code featureFlags} bean，使 {@code nexusai.feature.reactive-compact} /
     * {@code nexusai.feature.context-collapse} / {@code nexusai.feature.skill-prefetch}
     * 三项属性可在生产配置中开关（默认 false，对齐 CC flag 默认关闭）。
     */
    @org.springframework.context.annotation.Configuration
    public static class FeatureFlagsConfig {

        /** {@code REACTIVE_COMPACT} flag · CC query.ts:15 · 默认 false。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.reactive-compact:false}")
        private boolean reactiveCompact;

        /** {@code CONTEXT_COLLAPSE} flag · CC query.ts:18 · 默认 false。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.context-collapse:false}")
        private boolean contextCollapse;

        /** {@code EXPERIMENTAL_SKILL_SEARCH} flag · CC query.ts:66 · 默认 false。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.skill-prefetch:false}")
        private boolean skillPrefetch;

        /** {@code PROMPT_CACHE_BREAK_DETECTION} flag · CC claude.ts:1469 · 默认 false（OPD-SP-14 门控关）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.prompt-cache-break-detection:false}")
        private boolean promptCacheBreakDetection;

        /** {@code tengu_moth_copse} GB flag · CC attachments.ts:2367 · 默认 false（GB 未接入）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.tengu-moth-copse:false}")
        private boolean tenguMothCopse;

        /** {@code HISTORY_SNIP} flag · CC query.ts:115/401 · 默认 false（关时 snip 模块为 null）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.history-snip:false}")
        private boolean historySnip;

        /** {@code tengu_hawthorn_steeple} GB flag · CC toolResultStorage.ts:451-455 · 默认 false（true=执行 applyToolResultBudget、false=跳过）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.budget-aggregate-gate:false}")
        private boolean budgetAggregateGate;

        /** {@code tengu_session_memory} GB flag · CC sessionMemoryCompact.ts:412-415 · 默认 false（与 smCompact AND）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.sm-session-memory:false}")
        private boolean smSessionMemory;

        /** {@code tengu_sm_compact} GB flag · CC sessionMemoryCompact.ts:416-419 · 默认 false（与 smSessionMemory AND）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.sm-compact:false}")
        private boolean smCompact;

        /** {@code tengu_disable_streaming_to_non_streaming_fallback} GB flag · CC claude.ts:2469-2474 · 默认 false（false=流式失败仍回退非流式）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.tengu-disable-streaming-to-nonstreaming-fallback:false}")
        private boolean tenguDisableStreamingToNonStreamingFallback;

        /** {@code BG_SESSIONS} flag · CC query.ts:118/1685 · 默认 false（关时 periodic task summary 整链断链）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.bg-sessions:false}")
        private boolean bgSessions;

        /** {@code OVERFLOW_TEST_TOOL} flag · CC tools.ts:107-108 · 默认 false。<b>死标志（G30⑫）</b>：OverflowTestTool 已删除，无消费方，保留对齐 CC tools.ts:107 死代码。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.overflow-test-tool:false}")
        private boolean overflowTestTool;

        /** {@code TERMINAL_PANEL} flag · CC tools.ts:113-115（TerminalCaptureTool 条件构建门）· 默认 false。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.terminal-panel:false}")
        private boolean terminalPanel;

        /** {@code CLAUDE_CODE_VERIFY_PLAN} env gate · CC tools.ts:91-94（VerifyPlanExecutionTool 条件构建门）· 默认 false。 */
        @org.springframework.beans.factory.annotation.Value("${CLAUDE_CODE_VERIFY_PLAN:false}")
        private boolean verifyPlan;

        /** {@code WORKFLOW_SCRIPTS} flag · CC tools.ts:129-133（WorkflowTool 条件构建门）· 默认 false。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.workflow-scripts:false}")
        private boolean workflowScripts;

        /** {@code MONITOR_TOOL} flag · CC tools.ts:39-40（MonitorTool 条件构建门）· 默认 false。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.monitor-tool:false}")
        private boolean monitorTool;

        /** {@code NODE_ENV==='test'} env gate · CC tools.ts:244（TestingPermissionTool 注册点）· 默认 false（仅 test 环境）。 */
        @org.springframework.beans.factory.annotation.Value("${NODE_ENV:}")
        private String nodeEnv;

        /** {@code USER_TYPE} env · CC shellToolUtils.ts:19（'ant' = 内部构建 → PowerShell 默认开）。默认空 = 外部。 */
        @org.springframework.beans.factory.annotation.Value("${USER_TYPE:}")
        private String userType;

        /** {@code CLAUDE_CODE_USE_POWERSHELL_TOOL} env · CC shellToolUtils.ts:20-21（ant opt-out / 外部 opt-in）。默认空 = 未定义。 */
        @org.springframework.beans.factory.annotation.Value("${CLAUDE_CODE_USE_POWERSHELL_TOOL:}")
        private String usePowerShellToolEnv;
        /** {@code TOKEN_BUDGET} flag · CC prompts.ts:538 / attachments.ts:3829 / query.ts:280,1308 · 默认 false（关时 token_budget section 与 output_token_usage 均不注入）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.token-budget:false}")
        private boolean tokenBudget;


        /** {@code TEAMMEM} flag · CC memory/types.ts:9（MEMORY_TYPE_VALUES 中 TeamMem 仅 feature('TEAMMEM') 开启时在值域）· 默认 false（OPD-CM3-10/B03 可配置开关模拟）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.team-mem:false}")
        private boolean teamMem;


        /** {@code tengu_herring_clock} GB flag · CC teamMemPaths.ts:77（isTeamMemoryEnabled 运行时闸）· 默认 false（OPD-CM3-11/B04 可配置开关模拟，运行时开关）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.tengu-herring-clock:false}")
        private boolean tenguHerringClock;

        /** {@code tengu_destructive_command_warning} GB flag · CC BashPermissionRequest.tsx:274 · 默认 false（外部构建默认关 → destructiveWarning 恒 null；对齐 CC 外部构建语义）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.destructive-command-warning:false}")
        private boolean destructiveCommandWarning;

        /** {@code tengu_coral_fern} GB flag · CC memdir.ts:376（buildSearchingPastContextSection 门控）· 默认 false（OPD-CM5-C-10 补开关）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.coral-fern:false}")
        private boolean coralFern;

        /** {@code tengu_paper_halyard} GB flag · CC claudemd.ts:1158-1161 + attachments.ts:1823-1826（skipProjectLevel 门控）· 默认 false（F1-4 补开关）。 */
        @org.springframework.beans.factory.annotation.Value("${nexusai.feature.tengu-paper-halyard:false}")
        private boolean tenguPaperHalyard;

        /** [IMP-SP-08] promptCacheBreakDetection 值访问器 · AnthropicSdkProvider gatedBy 接线用。 */
        public boolean promptCacheBreakDetection() {
            return this.promptCacheBreakDetection;
        }

        /** [FIX-FR] tengu_moth_copse 值访问器 · ToolRegistrationConfig 门控接线用。 */
        public boolean tenguMothCopse() {
            return this.tenguMothCopse;
        }

        /** [OD-01 S1] historySnip 值访问器 · SnipCompactor 接线用。 */
        public boolean historySnip() {
            return this.historySnip;
        }

        /** [OD-01 S1] budgetAggregateGate 值访问器 · applyToolResultBudget 门控接线用（true=执行、false=跳过）。 */
        public boolean budgetAggregateGate() {
            return this.budgetAggregateGate;
        }

        /** [OD-01 S1] smSessionMemory 值访问器 · SessionMemory 双 flag 接线用（与 smCompact AND）。 */
        public boolean smSessionMemory() {
            return this.smSessionMemory;
        }

        /** [OD-01 S1] smCompact 值访问器 · SessionMemory 双 flag 接线用（与 smSessionMemory AND）。 */
        public boolean smCompact() {
            return this.smCompact;
        }

        /** [RV-03-03] tengu_disable_streaming_to_non_streaming_fallback 值访问器 · AnthropicSdkProvider 回退门控接线用。 */
        public boolean tenguDisableStreamingToNonStreamingFallback() {
            return this.tenguDisableStreamingToNonStreamingFallback;
        }

        /** [WF9-4] bgSessions 值访问器 · LlmAgentLoop A11 周期 task summary 调用点门控接线用。 */
        public boolean bgSessions() {
            return this.bgSessions;
        }

        /** [B5] OVERFLOW_TEST_TOOL 值访问器 · <b>死标志（G30⑫）</b>：OverflowTestTool 已删除后无消费方（无注册门控接线），保留对齐 CC tools.ts:107 死代码。 */
        public boolean overflowTestTool() {
            return this.overflowTestTool;
        }

        /** [B5] TERMINAL_PANEL 值访问器 · TerminalCaptureTool 桩注册门控接线用（CC tools.ts:113）。 */
        public boolean terminalPanel() {
            return this.terminalPanel;
        }

        /** [B5] CLAUDE_CODE_VERIFY_PLAN 值访问器 · VerifyPlanExecutionTool 桩注册门控接线用（CC tools.ts:91）。 */
        public boolean verifyPlan() {
            return this.verifyPlan;
        }

        /** [B5] WORKFLOW_SCRIPTS 值访问器 · WorkflowTool 桩注册门控接线用（CC tools.ts:129）。 */
        public boolean workflowScripts() {
            return this.workflowScripts;
        }

        /** [B5] MONITOR_TOOL 值访问器 · MonitorTool 桩注册门控接线用（CC tools.ts:39）。 */
        public boolean monitorTool() {
            return this.monitorTool;
        }

        /** [B5] NODE_ENV==='test' 值访问器 · TestingPermissionTool 桩注册门控接线用（CC tools.ts:244）。 */
        public boolean testingPermission() {
            return "test".equals(this.nodeEnv);
        }

        /**
         * [OPD-TOOL-35] PowerShell 可见性门控（USER_TYPE + env 三元）· 对齐 CC
         * {@code shellToolUtils.ts:17-22 isPowerShellToolEnabled()} 的「平台之外」因子：
         * {@code USER_TYPE==='ant' ? !isEnvDefinedFalsy(env) : isEnvTruthy(env)}。
         * 平台因子（isWindows）由 PowerShellTool.isEnabled() 合成；本访问器只产出三元结论。
         */
        public boolean usePowerShellTool() {
            return "ant".equals(this.userType)
                ? !TaskSystemConfig.isEnvDefinedFalsy(this.usePowerShellToolEnv)
                : isEnvTruthy(this.usePowerShellToolEnv);
        }
        /** [ER-IMP-2026-04] TOKEN_BUDGET 值访问器 · SystemPromptSections token_budget section + LlmAgentLoop output_token_usage 注入门控接线用（CC prompts.ts:538 / attachments.ts:3829）。 */
        public boolean tokenBudget() {
            return this.tokenBudget;
        }


        /** [IMP-CM-08] teamMem 值访问器 · TEAMMEM feature 门控接线用（OPD-CM3-10/B03 · CC memory/types.ts:9，供 IMP-CM-09/11 消费）。 */
        public boolean teamMem() {
            return this.teamMem;
        }

        /** [IMP-CM-09] tenguHerringClock 值访问器 · tengu_herring_clock 运行时开关接线用（OPD-CM3-11/B04 · CC teamMemPaths.ts:77）。 */
        public boolean tenguHerringClock() {
            return this.tenguHerringClock;
        }

        /** [IMP-H R1] destructiveCommandWarning 值访问器 · WebSocketPermissionPrompter renderPermissionWarning 破坏性命令分支门控接线用（CC BashPermissionRequest.tsx:274）。 */
        public boolean destructiveCommandWarning() {
            return this.destructiveCommandWarning;
        }

        /** [IMP-C-6 · OPD-CM5-C-10] tengu_coral_fern 值访问器 · MemoryPromptBuilder buildSearchingPastContextSection 门控接线用（CC memdir.ts:376）。 */
        public boolean coralFern() {
            return this.coralFern;
        }

        /** [F1-4] tengu_paper_halyard 值访问器 · ToolRegistrationConfig claudemdEngine bean setPaperHalyardGate 门控接线用（CC claudemd.ts:1158-1161 / attachments.ts:1823-1826）。 */
        public boolean tenguPaperHalyard() {
            return this.tenguPaperHalyard;
        }

        /**
         * env truthy 判定 · 对齐 CC envUtils.ts:32-37 isEnvTruthy
         * （['1','true','yes','on']，小写归一；null/空 → false）。
         * 无公共单例可复用，故本类自建（isEnvDefinedFalsy 复用 TaskSystemConfig 公共静态版）。
         */
        private static boolean isEnvTruthy(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            String lower = value.trim().toLowerCase();
            return "1".equals(lower) || "true".equals(lower)
                || "yes".equals(lower) || "on".equals(lower);
        }

        /**
         * 生产 FeatureFlags bean · 供 {@code AgentLoopContextFactory} / LlmAgentLoop 注入。
         */
        @org.springframework.context.annotation.Bean
        public FeatureFlags featureFlags() {
            return new FeatureFlags(reactiveCompact, contextCollapse, skillPrefetch,
                promptCacheBreakDetection, tenguMothCopse,
                historySnip, budgetAggregateGate, smSessionMemory, smCompact,
                tenguDisableStreamingToNonStreamingFallback, bgSessions,
                overflowTestTool, terminalPanel, verifyPlan, workflowScripts, monitorTool,
                testingPermission(), usePowerShellTool(), tokenBudget, teamMem, tenguHerringClock,
                destructiveCommandWarning, coralFern, tenguPaperHalyard);
        }
    }
}
