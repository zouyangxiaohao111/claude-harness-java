package com.nexusai.application.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * querySource 枚举 · 对齐 CC src/constants/querySource.ts.
 *
 * <p>CC 实际有 6 种来源（query.ts:189 + 1568-1578）：
 * <ul>
 *   <li>{@link #USER} — 主线程用户输入</li>
 *   <li>{@link #SUBAGENT} — Subagent 调用（agentId != sessionId）；<b>守卫类别占位</b>，
 *       agentType 级精确值（{@code agent:builtin:&lt;type&gt;}）由
 *       {@link com.nexusai.application.agent.loop.QueryParams#querySourceValue()} +
 *       {@link #effectiveValue(QuerySource, String)} 承载（IMP2-05 值域复活，见 {@link #canonical()}
 *       SUBAGENT 条目）</li>
 *   <li>{@link #COMPACT} — /compact 触发的 forked agent</li>
 *   <li>{@link #SESSION_MEMORY} — Session memory 提取的 forked agent</li>
 *   <li>{@link #REPL_MAIN_THREAD} — REPL 主线程（agentId 为 null）</li>
 *   <li>{@link #SDK} — SDK 透传（CCR Remote / cowork）</li>
 *   <li>{@link #WORKFLOW} — workflow 子代理（claudeCodeBackend 委托 runAgent），CC 默认
 *       {@code 'workflow'}（claudeCodeBackend.ts:304）</li>
 * </ul>
 *
 * <p>Java 端 R28-1 改造：querySource 从 RunRequest 必传，不再用 deriveQuerySource 派生。
 * 保留 {@link #deriveFrom(UUID, UUID)} 用于 fallback 兼容。
 */
public enum QuerySource {
    USER,
    SUBAGENT,
    COMPACT,
    SESSION_MEMORY,
    REPL_MAIN_THREAD,
    SDK,
    /**
     * H7-arch Phase 3: CC original {@code querySource:'hook_agent'}
     * (Open-ClaudeCode/src/utils/hooks/execAgentHook.ts:174).
     * agent hook 复用主 query() 时传此值，Java 端 ExecAgentHook 调 queryLoop 同步传入。
     */
    HOOK_AGENT,
    /**
     * extract-memories 后台 fork 的 querySource 标记 · 对齐 CC
     * {@code querySource: 'extract_memories'} (Open-ClaudeCode/src/services/extractMemories/extractMemories.ts:419)。
     *
     * <p><b>为什么新增（IMP-M-P0-3）</b>：extract 收敛到 CC forked-agent（复用
     * compact/fork/RunForkedAgent）时，ForkedAgentParams.querySource 必须携带 CC 的
     * 'extract_memories' 值 —— 该值进入 query loop 后驱动 autocompact 递归守卫 /
     * memory 注入门控（对齐 CC query.ts:1568-1578 值域，不含主线程 memory side-query）。
     */
    EXTRACT_MEMORIES,
    /**
     * auto-dream 后台 fork 的 querySource 标记 · 对齐 CC
     * {@code querySource: 'auto_dream'} (Open-ClaudeCode/src/services/autoDream/autoDream.ts:229)。
     *
     * <p><b>为什么新增（IMP-M-P0-3）</b>：auto-dream 收敛到 CC forked-agent 时，
     * ForkedAgentParams.querySource 必须携带 CC 的 'auto_dream' 值（autoDream.ts:229），
     * 与 extract 同理。
     */
    AUTO_DREAM,
    /**
     * fork 子 agent 的 querySource 标记 · 对齐 CC
     * {@code 'agent:builtin:fork'} (Open-ClaudeCode/src/tools/AgentTool/runAgent.ts:694
     * {@code ...(useExactTools && { querySource })} + promptCategory.ts:23
     * {@code getQuerySourceForAgent('fork', true)}).
     *
     * <p>CC 的 QuerySource 是 string 类型别名 (import type { QuerySource })，{@code 'agent:builtin:fork'}
     * 是 fork 子 agent 的字符串标记。Java 端用 enum FORK 携带此标记，由
     * {@code LlmAgentLoop.buildSubagentAgentOptions} 映射回 {@code ForkSubagent.FORK_QUERY_SOURCE}
     * ({@code "agent:builtin:fork"}) 字符串，注入 AgentOptions.querySource 抗 autocompact 递归守卫
     * (CC AgentTool.tsx:332 主检查读 context.options.querySource 字符串精确等于
     * {@code `agent:builtin:${FORK_AGENT.agentType}`}，autocompact 只重写 messages 不重写
     * context.options，故抗 autocompact)。
     *
     * <p><b>WHY fork 本质是 subagent</b>：内存门控 (findRelevant side-query / memory index 加载)
     * 必须同时排除 {@link #SUBAGENT} 与 {@link #FORK}，对齐 CC query.ts:480 只主线程走 side-query。
     * 否则 fork 子 agent 会错误加载主线程 memory index / 触发 findRelevant side-query。
     */
    FORK,
    /**
     * ctx-agent（marble origami）的 querySource 标记 · 对齐 CC
     * {@code querySource: 'marble_origami'}（autoCompact.ts:180-182 递归守卫）。
     *
     * <p><b>为什么新增（IMP2-01 · §7-14）</b>：CC 递归守卫在 CONTEXT_COLLAPSE 启用时
     * 禁止 ctx-agent 触发 autocompact（其压缩会经 runPostCompactCleanup 调
     * resetContextCollapse 销毁 MAIN thread 的 committed log）。Java 此前无该枚举值，
     * 守卫无法命中（S-3 同根因值域缺失）。
     */
    MARBLE_ORIGAMI,
    /**
     * workflow 子代理的 querySource 标记 · 对齐 CC
     * {@code querySource: 'workflow'} (Open-ClaudeCode/src/workflow/backends/claudeCodeBackend.ts:304
     * {@code toolUseContext.options.querySource ?? 'workflow'} —— runAgent 委托默认值)。
     *
     * <p><b>为什么新增（P1 Report D-4）</b>：ClaudeCodeBackendAdapter 委托 runAgent
     * （SubagentExecutor.executeStreaming）时，workflow 子代理 querySource 应为 CC 的
     * {@code 'workflow'}，而非 {@link #SUBAGENT} 类别（canonical {@code agent:subagent}）。
     * 语义差异落在 <b>persist gate</b>（query.ts:376-378）：CC 判 {@code startsWith('agent:') ||
     * startsWith('repl_main_thread')} 才 persist；{@code 'workflow'} 两者都不命中 →
     * content replacement 不持久化（workflow 子代理结果经父 agent summary 回传，无 resume
     * 重建源需求）。旧实现（SUBAGENT 类别）会让 workflow 子代理 replacement 错误落 sidechain。
     *
     * <p><b>其他守卫语义不变</b>（对齐 CC 字符串值域）：
     * <ul>
     *   <li>autocompact 递归守卫（autoCompact.ts:171-183）：'workflow' 非 session_memory/compact/
     *       marble_origami → 守卫不命中，超阈照常压缩</li>
     *   <li>529 / main-thread 判定（query.ts:1567-1568）：'workflow' 非 repl_main_thread 前缀 /
     *       sdk → 后台来源，不重试 / 非主线程</li>
     * </ul>
     */
    WORKFLOW;

    /** 数据流日志 · [IMP2-05 值域复活] effectiveValue 解析数据流（slf4j + logback，中文）。 */
    private static final Logger log = LoggerFactory.getLogger(QuerySource.class);

    /**
     * CC 小写值域映射（canonical）· 对齐 CC QuerySource 字符串值域（query.ts:189/1568-1578、
     * promptCategory.ts:41、runAgent.ts:694、execAgentHook.ts:174、extractMemories.ts:419、
     * autoDream.ts:229、autoCompact.ts:180）。
     *
     * <p><b>映射侧归一（OD-20 §7-12/§7-20 方向裁决）</b>：生产传参侧统一经本方法取
     * canonical 小写值（替代 {@code name()} 大写枚举名），匹配侧（main-thread 门 / 递归
     * 守卫 / getTrackingKey / persist gate）统一消费 canonical；不改 SDK 序列化输出面
     * （agent/remote/ 仍可用 {@code name()}）。
     *
     * <p><b>值域说明</b>：
     * <ul>
     *   <li>{@link #USER} → {@code repl_main_thread}——Java USER 语义为主线程用户输入
     *       （agentId==sessionId），CC 主线程唯一值 {@code 'repl_main_thread'}
     *       （promptCategory.ts:41；ErrorClassifier.java:96-100 同判据）</li>
     *   <li>{@link #SUBAGENT} → {@code agent:subagent}——Java <b>守卫类别聚合占位</b>
     *       （agentType 已丢失）；CC subagent 值域为动态 {@code 'agent:builtin:<type>'}
     *       （runAgent.ts:694 + promptCategory.ts:16-28），{@code agent:} 前缀语义对齐
     *       （persist gate query.ts:376-378 / getTrackingKey 前缀族）；<b>IMP2-05 值域复活</b>：
     *       agentType 级精确值（{@code agent:builtin:&lt;type&gt;} / {@code agent:custom} /
     *       {@code agent:default} / {@code agent:builtin:fork}）由
     *       {@link com.nexusai.application.agent.loop.QueryParams#querySourceValue()} 透传，
     *       {@link #effectiveValue(QuerySource, String)} 解析——本枚举仍是守卫消费侧，
     *       发射侧精确值优先（见 effectiveValue 的守卫不变性论证）</li>
     *   <li>{@link #FORK} → {@code agent:builtin:fork}——CC 真值（runAgent.ts:694 +
     *       promptCategory.ts:23；{@code ForkSubagent.FORK_QUERY_SOURCE} 同值）</li>
     * </ul>
     *
     * @return CC 小写字面量
     */
    public String canonical() {
        return switch (this) {
            case USER, REPL_MAIN_THREAD -> "repl_main_thread";
            case SUBAGENT -> "agent:subagent";
            case COMPACT -> "compact";
            case SESSION_MEMORY -> "session_memory";
            case SDK -> "sdk";
            case HOOK_AGENT -> "hook_agent";
            case EXTRACT_MEMORIES -> "extract_memories";
            case AUTO_DREAM -> "auto_dream";
            case FORK -> "agent:builtin:fork";
            case MARBLE_ORIGAMI -> "marble_origami";
            case WORKFLOW -> "workflow";
        };
    }

    /**
     * 字符串 → canonical 小写值域归一（幂等）。
     *
     * <p><b>匹配侧单点归一（IMP2-01）</b>：消费方（isMainThreadSource / isMainThreadCompact /
     * getTrackingKey / 递归守卫 / shouldPersistReplacements）统一先经本方法归一再比较，
     * 兼容三种输入形态：
     * <ol>
     *   <li>生产传参侧（LlmAgentLoop 等）经 {@link #canonical()} 产出的 CC 小写值 → 原样返回</li>
     *   <li>历史/测试直接传 {@code name()} 大写枚举名（如 {@code REPL_MAIN_THREAD}）→ 归一小写</li>
     *   <li>CC 风格字符串（{@code repl_main_thread:outputStyle:custom} / {@code compact} /
     *       {@code agent:...}）→ 原样返回（前缀匹配语义保持）</li>
     * </ol>
     *
     * @param raw 任意来源字符串（null → null）
     * @return canonical 小写值（未知名原样返回）
     */
    public static String canonicalize(String raw) {
        if (raw == null) {
            return null;
        }
        for (QuerySource q : values()) {
            if (q.name().equals(raw)) {
                return q.canonical();
            }
        }
        return raw;
    }

    /**
     * 字符串 → 枚举解析（兼容 {@code name()} 与 {@link #canonical()} 双形态）。
     *
     * <p>供 String→enum 解析面（ErrorClassifier.shouldRetry529 等）在 canonical 化后
     * 仍能解析（原 {@code valueOf(name())} 在 canonical 输入下抛 IllegalArgumentException）。
     *
     * @param s 查询来源字符串（null → null）
     * @return 匹配枚举；未知字符串 → null（调用方按 null 语义处理）
     */
    public static QuerySource fromString(String s) {
        if (s == null) {
            return null;
        }
        for (QuerySource q : values()) {
            if (q.name().equals(s) || q.canonical().equals(s)) {
                return q;
            }
        }
        return null;
    }

    /**
     * [IMP2-05 值域复活] 运行时 querySource 最终值解析 · 对齐 CC promptCategory.ts:16-28
     * getQuerySourceForAgent + AgentTool.tsx:609（子代理 querySource 唯一来源：
     * {@code toolUseContext.options.querySource ?? getQuerySourceForAgent(...)}）+
     * runAgent.ts:694（fork 子继承父 querySource）。
     *
     * <p><b>值域分工</b>：枚举 {@link QuerySource} 只承担<b>守卫类别</b>（autocompact 递归守卫 /
     * persist gate / 529 判定 / main-thread 判定均按类别消费），agentType 级精确值
     * （{@code agent:builtin:&lt;type&gt;} / {@code agent:custom} / {@code agent:default} /
     * {@code agent:builtin:fork}）由 {@code exactValue} 承载。精确值生产来源：
     * {@code AgentDefinition.querySourceForAgent()}（委托 {@code PromptCategory.getQuerySourceForAgent}，
     * 已对齐 CC promptCategory.ts:16-28），经 {@code QueryParams.querySourceValue} 透传到 loop
     * 发射侧（模型请求 / analytics）。
     *
     * <p><b>解析规则</b>：
     * <ol>
     *   <li>{@code exactValue != null} → 原样返回（agentType 级精确值优先）</li>
     *   <li>{@code exactValue == null} → 回退 {@link #canonical()}（类别聚合值，向后兼容：
     *       SUBAGENT → {@code agent:subagent}；FORK → {@code agent:builtin:fork}）</li>
     * </ol>
     *
     * <p><b>守卫不变性论证（不可破坏清单）</b>：
     * <ul>
     *   <li><b>fork 递归守卫</b>：fork 精确值 {@code agent:builtin:fork} 与枚举 {@link #FORK} 的
     *       {@link #canonical()} 字面量一致（SubagentTool.tsx:1641 读 AgentOptions.querySource
     *       字符串，LlmAgentLoop.buildSubagentAgentOptions FORK 分支恒注入
     *       ForkSubagent.FORK_QUERY_SOURCE → 不受影响）</li>
     *   <li><b>persist gate</b>：精确值恒以 {@code agent:} 前缀开头 → 命中
     *       {@code startsWith('agent:')}（CC query.ts:376-378）</li>
     *   <li><b>529 / main-thread 判定</b>：精确值不命中 {@code repl_main_thread} 前缀 / sdk
     *       （CC query.ts:1567-1568 isMainThread）→ 后台来源，不重试/非主线程语义不变</li>
     *   <li><b>autocompact 递归守卫</b>：精确值非 session_memory/compact/marble_origami
     *       （autoCompact.ts 守卫值域）→ 守卫不命中，超阈照常压缩（与 'agent:subagent' 同语义）</li>
     * </ul>
     *
     * @param category   枚举类别（守卫消费侧；null → 仅 exactValue 有意义）
     * @param exactValue agentType 级精确字符串（null = 未接线 → 回退 category.canonical()）
     * @return 运行时 querySource 最终字符串
     */
    public static String effectiveValue(QuerySource category, String exactValue) {
        if (exactValue != null) {
            if (log.isDebugEnabled()) {
                log.debug("[IMP2-05] effectiveValue 精确值命中: querySourceValue={}（agentType 级，"
                        + "CC promptCategory.ts:16-28 getQuerySourceForAgent → AgentTool.tsx:609）",
                    exactValue);
            }
            return exactValue;
        }
        String fallback = category != null ? category.canonical() : null;
        if (log.isDebugEnabled()) {
            // [收尾 IMP2-05] 接线已完成：精确值由 SubagentExecutor.withQuerySourceValue 注入
            //   （agent B 闭环）；本回退分支仅当调用方未注入精确值（主线程 / 旧路径 / forLoop 默认
            //   null）时兜底，SUBAGENT 仍回退 'agent:subagent' 聚合占位（守卫类别消费，向后兼容）。
            log.debug("[IMP2-05] effectiveValue 回退: querySourceValue=null → category={} canonical={}"
                    + "（精确值未注入，回退类别聚合值；接线已闭环见 QueryParams.withQuerySourceValue）",
                category, fallback);
        }
        return fallback;
    }

    /**
     * 派生 fallback · 对齐 Java 旧版本 deriveQuerySource(state) 行为。
     *
     * <p>仅在 caller 没显式传 querySource 时使用。主线程通常应该传 USER/REPL_MAIN_THREAD，
     * subagent 应该传 SUBAGENT，本方法仅作为防御性 fallback。
     *
     * <p>[session-id-short] 主线程判定 agentId==null（对齐 CC !context.agentId）。
     * 删除 {@code agentId.equals(sessionId)→USER} Phase-1 遗留分支：sessionId 已 String，
     * agentId!=null 时恒不等 → 该分支实为死代码。
     *
     * @param agentId   AgentState.agentId（null = 主线程）
     * @param sessionId AgentState.sessionId（short；null = 主线程）
     * @return 推导的 QuerySource
     */
    public static QuerySource deriveFrom(UUID agentId, String sessionId) {
        if (agentId == null) {
            return REPL_MAIN_THREAD;
        }
        return SUBAGENT;
    }
}
