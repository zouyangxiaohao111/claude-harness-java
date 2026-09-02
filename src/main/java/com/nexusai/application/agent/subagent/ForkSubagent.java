package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.coordinator.CoordinatorMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * ForkSubagent · 对齐 CC tools/AgentTool/forkSubagent.ts:33-95。
 *
 * <p>L1 语义: fork subagent 特性 — 省略 subagent_type 时隐式 fork, 子 agent 继承父的完整对话上下文与
 * system prompt。特性门: FORK_SUBAGENT 开启 <b>且</b> 非 coordinator 模式 <b>且</b> 非交互式会话才启用
 * (与 coordinator 互斥)。fork agent 用 permissionMode="bubble" 把权限提示冒泡给父终端。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: FORK_SUBAGENT_TYPE="fork"; FORK_TOOLS=['*']; MAX_TURNS=200; MODEL="inherit";
 *       PERMISSION_MODE="bubble"; FORK_BOILERPLATE_TAG="fork-boilerplate"; isEnabled(...)/isInForkChild(...)</li>
 *   <li><b>A2 Golden Trace</b>: featureOn + 非coordinator + 交互式 → isEnabled=true</li>
 *   <li><b>A3 纯函数</b>: isEnabled 仅依赖 3 布尔; isInForkChild 仅依赖消息文本</li>
 *   <li><b>A4 边界</b>: coordinator 模式 → false; 非交互 → false; feature off → false</li>
 *   <li><b>A5 业务场景</b>: 对话历史含 &lt;fork-boilerplate&gt; → isInForkChild=true (防递归 fork)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS satisfies BuiltInAgentDefinition + feature() gate → Java 静态常量 + 纯谓词,
 * feature/coordinator/interactive 状态注入为布尔; permissionMode="bubble" 对齐 PermissionBubbleService。
 *
 * <p><b>R-A12 收敛（fork 开关双源 → 单一来源，WF-D-UN-3）</b>: 旧实现 fork gate 三参全部走静态运行时
 * 槽位（{@link #syncRuntimeGate} 由 {@code ForkSubagentConfig} 配置值写入），其中 {@code coordinatorMode}
 * 与 {@link CoordinatorMode} bean（动态 env 源 {@code CLAUDE_CODE_COORDINATOR_MODE}，
 * coordinatorMode.ts:36-41）双源并存：当 env 开 coordinator 但 {@code nexusai.fork.coordinator-mode}
 * 未配时，SubagentTool 内部 fork gate（env 源）与 SessionGuidanceSection prompt 链（config 槽）分叉。
 * R-A12 收敛：coordinator 项经 {@link #setCoordinatorModeSupplier(Supplier)} 接动态 {@code CoordinatorMode}
 * bean（env 单一真源，对齐 CC 每次渲染读全局 isCoordinatorMode，forkSubagent.ts:34），bean 未注入
 * （测试/直构）回退 config 静态槽；{@code featureOn}/{@code nonInteractive} 仍走 config 单一源。
 */
public final class ForkSubagent {

    private static final Logger log = LoggerFactory.getLogger(ForkSubagent.class);

    public static final String FORK_SUBAGENT_TYPE = "fork";
    public static final List<String> FORK_TOOLS = List.of("*");
    public static final int MAX_TURNS = 200;
    public static final String MODEL = "inherit";
    public static final String PERMISSION_MODE = "bubble";
    public static final String SOURCE = "built-in";
    /** CC constants/xml.ts:63 */
    public static final String FORK_BOILERPLATE_TAG = "fork-boilerplate";

    /**
     * fork 子 agent 的 querySource · 对齐 CC AgentTool.tsx:332 template literal
     * {@code `agent:builtin:${FORK_AGENT.agentType}`} + promptCategory.ts:23
     * {@code getQuerySourceForAgent('fork', true)}.
     *
     * <p>WHY 消除 magic string + 修正连字符不匹配 bug (Pattern #11): 旧 SubagentExecutor 用
     * {@code "agent:" + agentDefinition.source() + ":" + agentDefinition.agentType()} 拼出
     * {@code 'agent:built-in:fork'} (source='built-in' 带连字符), 与递归守卫 SubagentTool
     * 检查的 {@code 'agent:builtin:fork'} (无连字符) 永不相等 → autocompact 后守卫失效 bypass.
     * fork path 必须用本常量, 禁止用 {@code agentDefinition.source()} 拼接.
     */
    public static final String FORK_QUERY_SOURCE = "agent:builtin:" + FORK_SUBAGENT_TYPE;

    /**
     * 运行时 fork gate 三参静态持有器（进程级全局）· 对齐 CC {@code feature('FORK_SUBAGENT')} /
     * {@code isCoordinatorMode()} / {@code getIsNonInteractiveSession()} 的<b>全局读取</b>语义
     * （forkSubagent.ts:32-39 isForkSubagentEnabled 每次渲染时读全局状态）。
     *
     * <p><b>WHY 静态</b>：CC 的 feature/env 判定是进程级全局（bun:bundle compile-time + env），
     * 而 Java 的 prompt 组装链（SystemPromptSections/SessionGuidanceSection）是纯静态工具类，
     * 无 Spring 上下文；唯一 gate 值源是 {@link ForkSubagentConfig}（@ConfigurationProperties
     * prefix={@code nexusai.fork}，feature-on/coordinator-mode/non-interactive，默认 true/false/false）。
     * {@code SubagentTool} 构造器经 {@link ForkSubagentConfig#current()} 读取并写入本静态槽位，
     * prompt 链经 {@link #isForkSubagentEnabled()} 读取 —— 读写分离，对齐 CC "全局读 + 配置源写"。
     *
     * <p><b>默认值</b>：与 {@code SubagentTool} 无配置默认 {true,false,false} 一致（M1.2 对齐
     * 硬编码基线），即生产默认 fork 开启（对齐 CC 发行版 FORK_SUBAGENT 启用 → session_guidance
     * 注入 :318 fork 变体）。
     */
    private static volatile boolean runtimeFeatureOn = true;
    private static volatile boolean runtimeCoordinatorMode = false;
    private static volatile boolean runtimeNonInteractive = false;

    /**
     * [R-A12] coordinator 判定动态源（env 真源）· 对齐 CC {@code isCoordinatorMode()}（coordinatorMode.ts:36-41）。
     *
     * <p><b>单一来源收敛（WF-D-UN-3）</b>: 非 null 时优先本 supplier（生产 = {@link CoordinatorMode} bean，
     * 由 {@code ForkSubagentConfigBootstrap} 注册），静态槽 {@link #runtimeCoordinatorMode} 退为 fallback
     * （bean 未注入的测试/直构路径）。对齐 CC "每次渲染读全局 isCoordinatorMode"（forkSubagent.ts:34）。
     */
    private static volatile Supplier<Boolean> coordinatorModeSupplier;

    private ForkSubagent() {}

    /**
     * 同步运行时 fork gate 三参 · 由 gate 值源（{@code SubagentTool} 的 setter / register）写入。
     *
     * <p>CC original: 无直接等价 —— CC 是全局 feature/env 读取，本方法对应"配置源写入全局槽位"。
     * 幂等；默认 {true,false,false}。
     *
     * <p><b>[R-A12] coordinator 语义变化</b>: coordinator 槽位经 {@link #setCoordinatorModeSupplier} 注册
     * 动态 bean 后仅作 fallback（bean 未注入的测试/直构路径）；生产以 supplier（env 源）为准。featureOn /
     * nonInteractive 仍由本槽位承载（config 单一源）。
     *
     * @param featureOn        主开关 · CC original: feature('FORK_SUBAGENT')（forkSubagent.ts:33）
     * @param coordinatorMode  coordinator 模式 · CC original: isCoordinatorMode()（forkSubagent.ts:34；
     *                           R-A12 后为 fallback，生产走 {@link CoordinatorMode} bean env 源）
     * @param nonInteractive   非交互式 · CC original: getIsNonInteractiveSession()（forkSubagent.ts:35）
     */
    public static void syncRuntimeGate(boolean featureOn, boolean coordinatorMode, boolean nonInteractive) {
        runtimeFeatureOn = featureOn;
        runtimeCoordinatorMode = coordinatorMode;
        runtimeNonInteractive = nonInteractive;
    }

    /**
     * [R-A12] 注入 coordinator 判定动态源 · 由 Spring 装配方（{@code ForkSubagentConfigBootstrap}）注册
     * {@link CoordinatorMode} bean（env 真源），消除 config 静态槽与动态 bean 双源分叉（WF-D-UN-3）。
     *
     * <p>null → 复位回退（测试 seam / 直构回退 config 静态槽 {@link #runtimeCoordinatorMode}）。幂等。
     *
     * @param supplier coordinator 判定 · CC original: isCoordinatorMode()（coordinatorMode.ts:36-41）
     */
    public static void setCoordinatorModeSupplier(Supplier<Boolean> supplier) {
        coordinatorModeSupplier = supplier;
        if (log.isInfoEnabled()) {
            log.info("[ForkSubagent] R-A12: coordinator 判定源={}（null=回退 config 静态槽）",
                supplier != null ? "动态 CoordinatorMode bean（env 真源）" : "config 静态槽");
        }
    }

    /**
     * isForkSubagentEnabled() · 对齐 CC forkSubagent.ts:32-39（无参全局判定，prompts.ts:317
     * getAgentToolSection 双分支选择 + :374 explore-plan 停注共用此判定）。
     *
     * <p>语义：{@code feature('FORK_SUBAGENT') && !isCoordinatorMode() && !getIsNonInteractiveSession()}。
     * 委托纯谓词 {@link #isEnabled}；[R-A12] featureOn / nonInteractive 取静态运行时槽位（SubagentTool
     * 同步），coordinator 优先 {@link #setCoordinatorModeSupplier} 注册的动态 bean（env 真源），
     * 未注入时回退静态槽位 —— 与 SubagentTool 内部 fork gate 单一来源（WF-D-UN-3 收敛）。
     *
     * @return fork 子代理特性是否启用（true → session_guidance 注入 prompts.ts:318 fork 变体）
     */
    public static boolean isForkSubagentEnabled() {
        // [R-A12] 单一来源收敛（WF-D-UN-3）：coordinator 项优先动态 CoordinatorMode bean（env 真源），
        //   bean 未注入（测试/直构）回退 config 静态槽 —— 与 SubagentTool 内部 fork gate 同源，不再分叉。
        boolean coordinator = coordinatorModeSupplier != null
            ? coordinatorModeSupplier.get() : runtimeCoordinatorMode;
        boolean enabled = isEnabled(runtimeFeatureOn, coordinator, runtimeNonInteractive);
        if (log.isDebugEnabled()) {
            log.debug("[ForkSubagent] R-A12: fork gate 判定 featureOn={} coordinator={}({}) nonInteractive={} → {}",
                runtimeFeatureOn, coordinator,
                coordinatorModeSupplier != null ? "动态 bean" : "config 静态槽",
                runtimeNonInteractive, enabled);
        }
        return enabled;
    }

    /**
     * CC forkSubagent.ts:34-41 isForkSubagentEnabled — feature 开启且非 coordinator 且交互式。
     */
    public static boolean isEnabled(boolean featureOn, boolean coordinatorMode, boolean nonInteractive) {
        if (!featureOn) return false;
        if (coordinatorMode) return false;
        if (nonInteractive) return false;
        return true;
    }

    /**
     * CC forkSubagent.ts:83-95 isInForkChild — 对话历史中出现 fork boilerplate 标签则为 fork 子代理。
     *
     * @param userTextBlocks 所有 user 消息的 text block 内容
     */
    public static boolean isInForkChild(List<String> userTextBlocks) {
        if (userTextBlocks == null) return false;
        String open = "<" + FORK_BOILERPLATE_TAG + ">";
        return userTextBlocks.stream().anyMatch(t -> t != null && t.contains(open));
    }
}
