package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.telemetry.Telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 上下文（analytics 归因）· 对齐 CC {@code utils/agentContext.ts:1-178} (AsyncLocalStorage).
 *
 * <p><b>WHY (CLAUDE.md 规则一 · CC :16-21 注释真源)</b>：
 * CC 用 {@code AsyncLocalStorage} 隔离每条异步执行链的 agent 身份，避免并发 agent 的
 * analytics 事件归因串台（agent A 的 event 归到 agent B）。
 *
 * <p><b>⛔ [S1-T7-2 终态] Java 端<b>没有</b> ambient 存储</b>：本接口曾以 plain
 * {@code ThreadLocal} 等价 CC 的 AsyncLocalStorage，<b>已整体删除</b>（载体 + 全部读写方法
 * + 线程包裹）。根因：plain ThreadLocal <b>不跨线程继承</b>，而本仓归因消费点大量跑在
 * commonPool / tool-exec 池 / STREAM_EXECUTOR 虚拟线程上 ⇒ ambient 读在那些线程上恒 null
 * （归因边静默丢失）；「一 JVM 多会话」下还会退化为跨会话串扰。
 *
 * <p>现行契约 = <b>显式值传递</b>（用户铁律：会话态一律不得经 ThreadLocal/MDC 读，回放不算合规）：
 * {@code RunRequest.agentContext} → {@code ToolUseContext.agentContext()} → 工具
 * {@code execute} 形参 / {@code ModelRequest.agentContext} → provider 显式形参。
 * 本接口只保留<b>接收显式实参</b>的类型守卫与纯函数。
 *
 * <p><b>两种 context (discriminated union · CC :91)</b>:
 * <ul>
 *   <li>{@link SubagentContext} — CC :32-54, {@code agentType: 'subagent'} (Agent tool 子 agent)</li>
 *   <li>{@link TeammateAgentContext} — CC :60-85, {@code agentType: 'teammate'} (进程内 swarm teammate)</li>
 * </ul>
 *
 * <p><b>[S7] invocationEmitted 可变性偏差 (concern 记录)</b>:
 * CC :173 {@code context.invocationEmitted = true} 原地翻转布尔标记。Java record 组件不可变，
 * 故 {@code invocationEmitted} 用 {@link AtomicBoolean} 承载（语义等价: consume 一次后置 true）。
 * caller 每次 spawn/resume 必须新建 context 实例（CC 每次 spawn/resume 也新建对象并 reset 该标记）。
 *
 * @see <a href="../../../../../Open-ClaudeCode/src/utils/agentContext.ts">CC 真源</a>
 */
public sealed interface AgentContext {

    Logger log = LoggerFactory.getLogger(AgentContext.class);

    // ────────────────────────────────────────────────────────────────────────────
    // Context 类型
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * 子 Agent 上下文 · 对齐 CC SubagentContext (agentContext.ts:32-54).
     *
     * @param agentId         CC original: agentId (string, :34) — 子 agent UUID (createAgentId())
     * @param parentSessionId CC original: parentSessionId? (string, :36) — team lead session ID, main REPL subagent 为 undefined
     * @param agentType       CC original: agentType ('subagent', :38)
     * @param subagentName    CC original: subagentName? (string, :40) — 子 agent 类型名 (e.g. "Explore")
     * @param isBuiltIn       CC original: isBuiltIn? (boolean, :42) — 是否内置 agent (vs 用户自定义)
     * @param invokingRequestId CC original: invokingRequestId? (string, :46) — spawn/resume 方 request_id
     * @param invocationKind  CC original: invocationKind? ('spawn'|'resume', :49)
     * @param invocationEmitted CC original: invocationEmitted? (boolean, :53) — 已消费标记 (S7: AtomicBoolean 承载可变)
     */
    record SubagentContext(
            String agentId,
            String parentSessionId,
            String agentType,
            String subagentName,
            Boolean isBuiltIn,
            String invokingRequestId,
            String invocationKind,
            AtomicBoolean invocationEmitted
    ) implements AgentContext {

        /**
         * 便捷构造器 · 默认 agentType='subagent' + invocationEmitted=new AtomicBoolean(false)
         * (CC :38 literal 'subagent' + :53 初始 false, reset per spawn).
         */
        public SubagentContext(String agentId, String parentSessionId, String subagentName,
                               Boolean isBuiltIn, String invokingRequestId, String invocationKind) {
            this(agentId, parentSessionId, "subagent", subagentName, isBuiltIn,
                invokingRequestId, invocationKind, new AtomicBoolean(false));
        }
    }

    /**
     * 进程内 teammate 上下文 · 对齐 CC TeammateAgentContext (agentContext.ts:60-85).
     *
     * @param agentId         CC original: agentId (string, :62) — 完整 ID, e.g. "researcher@my-team"
     * @param agentName       CC original: agentName (string, :64) — 显示名, e.g. "researcher"
     * @param teamName        CC original: teamName (string, :66) — 所属 team
     * @param agentColor      CC original: agentColor? (string, :68) — UI 颜色
     * @param planModeRequired CC original: planModeRequired (boolean, :70) — 实施前是否强制 plan mode
     * @param parentSessionId CC original: parentSessionId (string, :72) — team lead session ID
     * @param isTeamLead      CC original: isTeamLead (boolean, :74)
     * @param agentType       CC original: agentType ('teammate', :76)
     * @param invokingRequestId CC original: invokingRequestId? (string, :80)
     * @param invocationKind  CC original: invocationKind? ('spawn'|'resume', :82)
     * @param invocationEmitted CC original: invocationEmitted? (boolean, :84) — S7: AtomicBoolean 承载可变
     */
    record TeammateAgentContext(
            String agentId,
            String agentName,
            String teamName,
            String agentColor,
            boolean planModeRequired,
            String parentSessionId,
            boolean isTeamLead,
            String agentType,
            String invokingRequestId,
            String invocationKind,
            AtomicBoolean invocationEmitted
    ) implements AgentContext {

        /**
         * 便捷构造器 · 默认 agentType='teammate' + invocationEmitted=new AtomicBoolean(false)
         * (CC :76 literal 'teammate' + :84 初始 false, reset per spawn).
         */
        public TeammateAgentContext(String agentId, String agentName, String teamName,
                                    String agentColor, boolean planModeRequired,
                                    String parentSessionId, boolean isTeamLead,
                                    String invokingRequestId, String invocationKind) {
            this(agentId, agentName, teamName, agentColor, planModeRequired, parentSessionId,
                isTeamLead, "teammate", invokingRequestId, invocationKind, new AtomicBoolean(false));
        }
    }

    /**
     * consumeInvokingRequestId 返回值 · 对齐 CC agentContext.ts:163-168
     * {@code { invokingRequestId: string, invocationKind: 'spawn'|'resume'|undefined }}.
     */
    record InvokingRequestEdge(String invokingRequestId, String invocationKind) {}

    // ────────────────────────────────────────────────────────────────────────────
    // 静态方法 (等价 CC module-level functions)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * 一次消费 invokingRequestId · <b>本接口唯一入口（显式实参）</b>。
     *
     * <p><b>Sparse edge 语义 (CC agentContext.ts:159-161 注释真源)</b>: invokingRequestId 只在每个
     * invocation 的第一个 terminal API event 出现一次, 消费后 {@code invocationEmitted=true}
     * 阻止后续重复返回。调用方在收到非 null 返回值时标记一个 spawn/resume 边界。
     *
     * <p><b>WHY 只保留显式实参版（本仓根因）</b>：CC 的 {@code consumeInvokingRequestId()}
     * （agentContext.ts:163-178）读 {@code AsyncLocalStorage} —— Node 的 ALS 跨 await/异步自动传播；
     * Java 的 plain {@code ThreadLocal} <b>不跨线程继承</b>，而本方法的调用点
     * （{@code AnthropicSdkProvider.emitApiTerminalEvent}）跑在
     * {@code LlmAgentLoop.STREAM_EXECUTOR} 虚拟线程上 ⇒ ambient 读恒 null ⇒
     * {@code invokingRequestId} 生产恒空。故 ambient 版已删除，只留显式版。
     *
     * <p><b>载体链（显式值传递）</b>：调用方在<b>上下文仍有效的线程</b>（子代理 query loop 线程）
     * 取出 {@link AgentContext} 实例，经 {@code ModelRequest.agentContext} → {@code ModelCaller}
     * → {@code LlmProvider.stream} / {@code ChatRequestOptions.agentContext} 显式下传，provider 在本方法消费。
     * 稀疏语义（{@code invocationEmitted} 一次翻转）由共享的同一 {@link SubagentContext} 实例承载，
     * 与 CC 逐字一致（CC 也是原地翻 {@code context.invocationEmitted}）。
     *
     * @param context 显式上下文（可 null = 主线程 / 无归因上下文，与 CC {@code context?.invokingRequestId} 等价）
     * @return 首次调用返回 {@link InvokingRequestEdge}; 已消费 / 无 invokingRequestId / context null 返回 null
     */
    static InvokingRequestEdge consumeInvokingRequestId(AgentContext context) {
        if (context == null) {
            return null;
        }
        String invokingRequestId = null;
        String invocationKind = null;
        String agentId = null;
        AtomicBoolean emitted = null;
        if (context instanceof SubagentContext sc) {
            invokingRequestId = sc.invokingRequestId();
            invocationKind = sc.invocationKind();
            agentId = sc.agentId();
            emitted = sc.invocationEmitted();
        } else if (context instanceof TeammateAgentContext tc) {
            invokingRequestId = tc.invokingRequestId();
            invocationKind = tc.invocationKind();
            agentId = tc.agentId();
            emitted = tc.invocationEmitted();
        }
        // CC :170 前半: if (!context?.invokingRequestId) return undefined
        // [S1-T14 缺值策略 (b)] 「上下文存在但 invokingRequestId 缺值」= 本 run 的所有 terminal
        //   API event 都不带归因边（结构性缺失，不是偶发）。记 ≥WARN（禁只 DEBUG）披露该事实。
        //   ⚠️ 该分支在热路径（每次 terminal API event 都会走到）⇒ 用**每实例一次性**闩锁防刷屏：
        //      claim 该实例的 invocationEmitted 作闩锁（invokingRequestId 是 record 的 final 组件，
        //      为 null 即永远为 null ⇒ 翻转 emitted 对本方法语义完全惰性）。
        //      ⛔ 不使用进程级 AtomicBoolean 单次闸——那会把「≥WARN 可观测」结构性地降级成
        //      「每 JVM 一行」（本仓裁定-8 的实证）。
        if (invokingRequestId == null) {
            if (emitted != null && emitted.compareAndSet(false, true)) {
                log.warn("[AgentContext 缺值策略(b)] 归因上下文存在但缺 invokingRequestId"
                    + "（agentId={}, contextType={}）⇒ 本 run 的全部 terminal API event 均不带"
                    + " invokingRequestId/invocationKind 归因边（『缺值 ⇒ 无属性』，绝不归因到 ambient）"
                    + "；本条为该上下文实例的一次性披露，不是每调用告警",
                    agentId, context.getClass().getSimpleName());
            }
            return null;
        }
        // CC :170 后半: || context.invocationEmitted → return undefined（sparse edge 已消费）
        if (emitted == null || emitted.get()) {
            return null;
        }
        // CC :173: context.invocationEmitted = true (一次消费后清空)
        emitted.set(true);
        if (log.isDebugEnabled()) {
            log.debug("[AgentContext] consumeInvokingRequestId 已消费: requestId={}, kind={}", invokingRequestId, invocationKind);
        }
        return new InvokingRequestEdge(invokingRequestId, invocationKind);
    }

    /**
     * 消费稀疏边并接入遥测事件属性 · <b>唯一入口（provider 侧唯一调用点）</b>。
     *
     * <p><b>CC 锚点真源（logging.ts，grep -n 自验）</b>：error 路径 :294
     * {@code const invocation = consumeInvokingRequestId()} + :320-327 spread；success 路径 :461 同款
     * + :493-500 spread —— edge 非 null 时把 invokingRequestId/invocationKind 展开进
     * tengu_api_error/tengu_api_success 事件负载（CC 的 sparse-edge 激活点不在 agentContext.ts
     * 自身，而在 API 遥测链路）。Java 侧本方法即该接入点：edge 非 null 时事件 attrs 写入
     * {@code invokingRequestId}/{@code invocationKind} 两键（CC logging.ts:322-325/:495-498 spread）。
     *
     * <p><b>WHY 上下文是显式实参（本仓根因）</b>：{@code AnthropicSdkProvider} 的 12 个
     * per-LLM-call terminal 发射点全部走本方法，上下文经 {@code LlmProvider.stream} /
     * {@code ChatRequestOptions} 显式携带到 {@code STREAM_EXECUTOR} 虚拟线程（理由见
     * {@link #consumeInvokingRequestId(AgentContext)}）。ambient 版已删除。
     *
     * @param eventAttrs   事件属性 Map（null → 仅消费不写属性）
     * @param context      显式上下文（null → 主线程，等价 CC {@code context?.invokingRequestId} undefined）
     * @return 本次消费的 {@link InvokingRequestEdge}（null = 无 / 已消费 / context null）
     */
    static InvokingRequestEdge attachInvokingRequestEdge(Map<String, Object> eventAttrs, AgentContext context) {
        InvokingRequestEdge edge = consumeInvokingRequestId(context);
        if (edge == null || eventAttrs == null) {
            return edge;
        }
        // CC logging.ts:322-325 / :495-498: edge 非 null 时展开 invokingRequestId + invocationKind
        eventAttrs.put("invokingRequestId", edge.invokingRequestId());
        eventAttrs.put("invocationKind", edge.invocationKind());
        if (log.isDebugEnabled()) {
            log.debug("[AgentContext] attachInvokingRequestEdge 已接入遥测事件: invokingRequestId={}, invocationKind={}",
                edge.invokingRequestId(), edge.invocationKind());
        }
        return edge;
    }

    /**
     * Type guard: 是否 SubagentContext · 等价 CC {@code isSubagentContext()} (agentContext.ts:115-119).
     *
     * @param context 可为 null (CC :116 {@code context: AgentContext | undefined})
     * @return context 非 null 且 agentType == 'subagent'
     */
    static boolean isSubagentContext(AgentContext context) {
        return context instanceof SubagentContext;
    }

    /**
     * Type guard: 是否 TeammateAgentContext · 等价 CC {@code isTeammateAgentContext()} (agentContext.ts:124-131).
     *
     * <p>CC :127 gate {@code isAgentSwarmsEnabled()} — swarms 未启用时恒 false。
     * Java 端接 {@link TaskSystemConfig#isAgentSwarmsEnabled()} (对齐 CC utils/agentSwarmsEnabled.ts)。
     *
     * @param context 可为 null
     * @return swarms 启用 且 context 是 TeammateAgentContext
     */
    static boolean isTeammateAgentContext(AgentContext context) {
        if (!TaskSystemConfig.isAgentSwarmsEnabled()) {
            return false;
        }
        return context instanceof TeammateAgentContext;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // agentId 生成 (对齐 CC utils/uuid.ts:24-27 createAgentId · D18/B2 拍板)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * 生成 agent ID · 对齐 CC {@code createAgentId(label?)} (Open-ClaudeCode/src/utils/uuid.ts:24-27).
     *
     * <p><b>CC 真源 (uuid.ts:24-27, Read 自验非注释转述)</b>:
     * <pre>{@code
     *   const suffix = randomBytes(8).toString('hex')
     *   return (label ? `a${label}-${suffix}` : `a${suffix}`) as AgentId
     * }</pre>
     *
     * <p>格式 {@code a}{label-}{16 hex}（<b>非 UUID 8-4-4-4-12</b>，D18/B2 拍板对齐 CC）:
     * <ul>
     *   <li>{@code a}{16 hex} — e.g. {@code aa3f2c1b4d5e6f7a8}</li>
     *   <li>{@code a}{label}-{16 hex} — e.g. {@code acompact-a3f2c1b4d5e6f7a8} (forkLabel 场景)</li>
     * </ul>
     *
     * <p>Java 以 {@link SecureRandom} 等价 CC {@code crypto.randomBytes(8)}（8 字节 → 16 hex 小写）。
     * 结果恒匹配 CC 校验正则 {@code /^a(?:.+-)?[0-9a-f]{16}$/}（AGENT_ID_PATTERN，types/ids.ts:35；校验函数 toAgentId :42）。
     * 格式差异影响 transcript 目录名 / analytics agent_id / resume 续写键
     * （探查 EV-WF4-SC-068/075 标注；生产生成点接线见 IMP-SUB-12 concerns）。
     *
     * @param label 可选标签（CC :25 {@code label?}），null 或空串（JS falsy）→ 无标签格式
     * @return agent ID，恒 'a' 前缀
     */
    static String createAgentId(String label) {
        byte[] suffixBytes = new byte[8];
        new SecureRandom().nextBytes(suffixBytes);
        String suffix = HexFormat.of().formatHex(suffixBytes);
        String id = (label != null && !label.isEmpty())
            ? "a" + label + "-" + suffix
            : "a" + suffix;
        if (log.isDebugEnabled()) {
            log.debug("[AgentContext] createAgentId 生成: label={}, id={} (CC uuid.ts:24-27)", label, id);
        }
        return id;
    }

    /**
     * 生成 agent ID（无标签）· 对齐 CC {@code createAgentId()} (uuid.ts:24-27).
     *
     * @return {@code a}{16 hex}，e.g. {@code aa3f2c1b4d5e6f7a8}
     */
    static String createAgentId() {
        return createAgentId(null);
    }

    /**
     * a+16hex → UUID 可逆编码桥 · 生产生成点接线（R3-WF-F IMP-SUB-12 返工 · D18/B2 拍板）。
     *
     * <p><b>[S-12] Java record 类型基础设施</b>: Java 侧 {@code ToolUseContext.agentId} 字段类型为
     * {@link UUID}（既有基础设施，超出本返工写集），而 CC {@code ToolUseContext.agentId} 为 string
     * a+16hex（forkedAgent.ts:448 {@code agentId: overrides?.agentId ?? createAgentId()}）。
     * 本方法把 a+16hex 的 16-hex 后缀（64 bit）打包进 {@code UUID} 的 {@code mostSigBits}
     * （leastSigBits 恒 0），使 a+16hex ↔ UUID 双向确定可逆：
     * <ul>
     *   <li>{@code packAgentId("acd5812100ae224fe")} → {@code UUID(cd5812100ae224fe, 0)}</li>
     *   <li>{@code unpackAgentId(uuid)} → {@code "a"+16hex}（{@link #unpackAgentId} 逆运算）</li>
     * </ul>
     * 生产生成点（R-A45 全量接线 · a+16hex 为单一身份源）均以
     * {@code packAgentId(createAgentId())} 产出 ToolUseContext.agentId：
     * <ul>
     *   <li>{@code createSubagentContext.create()}（CC forkedAgent.ts:448 等价，spawn 主路径）</li>
     *   <li>{@code ToolUseContext} compact ctor 兜底 + {@code with()} override 缺省（A-4）</li>
     *   <li>{@code SubagentTool.executeAsync} async 生成点（A-5，不再独立 UUID.randomUUID()）</li>
     * </ul>
     * transcript/metadata/resume/analytics 表面点经 {@link #unpackAgentId} 还原 a+16hex 输出；
     * resume 返回形制统一 a+16hex（B-4）。CC ToolUseContext.agentId 即 a+16hex string，
     * Java 以可逆编码桥（UUID 字段承载）对齐语义。
     *
     * @param agentIdHex a+16hex（{@link #createAgentId} 产物，17 字符：'a'+16 hex；容忍无 'a' 前缀）
     * @return 确定性 UUID（16-hex 后缀入 mostSigBits）；null → null
     */
    static UUID packAgentId(String agentIdHex) {
        if (agentIdHex == null) {
            return null;
        }
        String hex = agentIdHex.startsWith("a") ? agentIdHex.substring(1) : agentIdHex;
        long msb = Long.parseUnsignedLong(hex, 16);
        return new UUID(msb, 0L);
    }

    /**
     * UUID → a+16hex 逆运算 · {@link #packAgentId} 的逆映射。
     *
     * <p>对任意 UUID 均确定性还原 16 hex（mostSigBits 两补码格式化），故 legacy/随机 UUID 也能
     * 映射到合法 a+16hex（resume 双键查找的 fallback 通道，见 ResumeService.resumeAgentBackground）。
     *
     * @param agentIdUuid ToolUseContext.agentId（{@link #packAgentId} 产物或任意 UUID）
     * @return a+16hex（'a'+16 hex）；null → null
     */
    public static String unpackAgentId(UUID agentIdUuid) {
        if (agentIdUuid == null) {
            return null;
        }
        String hex = String.format("%016x", agentIdUuid.getMostSignificantBits());
        return "a" + hex;
    }

    // ────────────────────────────────────────────────────────────────────
    // tengu_fork_agent_query 遥测事件（B10 · 对齐 CC forkedAgent.ts:631-689
    // logForkAgentQueryEvent · IMP-SUB-29）
    // ────────────────────────────────────────────────────────────────────

    /**
     * 构建 {@code tengu_fork_agent_query} 事件属性 · 对齐 CC {@code logForkAgentQueryEvent}
     * (Open-ClaudeCode/src/utils/forkedAgent.ts:631-689)。
     *
     * <p><b>CC 字段逐项映射（forkedAgent.ts:656-688）</b>：
     * <ul>
     *   <li>forkLabel — :658-659（analytics 标签，如 'compact'）</li>
     *   <li>querySource — :660-661（CC QuerySource 字符串小写值，如 'compact'/'session_memory'）</li>
     *   <li>durationMs — :663</li>
     *   <li>messageCount — :664</li>
     *   <li>inputTokens/outputTokens/cacheReadInputTokens/cacheCreationInputTokens — :667-670
     *       （NonNullableUsage 四 token 字段）</li>
     *   <li>serviceTier — :671（Java fork 域无数据源 → null；ForkedAgentResult.ForkUsage 不承载）</li>
     *   <li>cacheCreationEphemeral1hTokens/cacheCreationEphemeral5mTokens — :672-675
     *       （Java fork 域无数据源 → 0，对齐 CC EMPTY_USAGE cache_creation 零初始化）</li>
     *   <li>cacheHitRate — :678（派生 · <b>A 命中率口径协议分派</b>：anthropic →
     *       {@code cache_read/(input+cache_read+cache_create)}，:647-654；非 anthropic
     *       （openai_sdk/deepseek，prompt_tokens 已含 cache hit）→ {@code cache_read/input}；
     *       read ≤ 0 或分母 ≤ 0 → 0）</li>
     *   <li>queryChainId/queryDepth — :681-687（queryTracking 存在才附带，CC 条件展开）</li>
     * </ul>
     *
     * @param forkLabel       analytics 标签 · CC original: {@code forkLabel} (forkedAgent.ts:93)
     * @param querySource     CC querySource 字符串（canonical 小写值）· CC original:
     *                        {@code querySource} (forkedAgent.ts:91)
     * @param durationMs      fork 查询耗时毫秒 · CC original: {@code durationMs} (forkedAgent.ts:610)
     * @param messageCount    fork 产出消息数 · CC original: {@code messageCount} (forkedAgent.ts:617)
     * @param inputTokens     usage 输入 token · CC original: {@code totalUsage.input_tokens} (:667)
     * @param outputTokens    usage 输出 token · CC original: {@code totalUsage.output_tokens} (:668)
     * @param cacheReadInputTokens    usage cache 读 token · CC original:
     *                        {@code totalUsage.cache_read_input_tokens} (:669)
     * @param cacheCreationInputTokens usage cache 写 token · CC original:
     *                        {@code totalUsage.cache_creation_input_tokens} (:670)
     * @param queryTracking   查询链路跟踪（chainId/depth）· CC original:
     *                        {@code toolUseContext.queryTracking} (forkedAgent.ts:619)；
     *                        null → 不附带 queryChainId/queryDepth（CC :681 条件展开）
     * @param anthropic       协议判定（CacheHitRate 分派）：true=Anthropic 三字段分母；
     *                        false=openai/deepseek read/input（input 已含 cache hit）·
     *                        CC 无对应（Java fork 路径需 provider 判定，见 RunForkedAgent）
     * @return 事件属性 Map（telemetry.recordEvent / logOTelEvent 入参）
     */
    static Map<String, Object> buildForkAgentQueryEventAttrs(
            String forkLabel, String querySource, long durationMs, int messageCount,
            long inputTokens, long outputTokens, long cacheReadInputTokens,
            long cacheCreationInputTokens, Map<String, Object> queryTracking, boolean anthropic) {
        // [A 命中率口径] 协议分派单点（ContextUsageCalculator.computeCacheHitRate）：
        //   anthropic → read/(input+read+create)（CC :647-654）；非 anthropic → read/input
        //   （deepseek prompt_tokens 已含 cache hit，旧分母恒为真实一半）
        double cacheHitRate = ContextUsageCalculator.computeCacheHitRate(
            inputTokens, cacheReadInputTokens, cacheCreationInputTokens, anthropic);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("forkLabel", forkLabel);
        attrs.put("querySource", querySource);
        attrs.put("durationMs", durationMs);
        attrs.put("messageCount", messageCount);
        attrs.put("inputTokens", inputTokens);
        attrs.put("outputTokens", outputTokens);
        attrs.put("cacheReadInputTokens", cacheReadInputTokens);
        attrs.put("cacheCreationInputTokens", cacheCreationInputTokens);
        // Java fork 域无 service_tier / cache_creation 嵌套数据源（ForkUsage 仅 4 token 字段）
        attrs.put("serviceTier", null);
        attrs.put("cacheCreationEphemeral1hTokens", 0L);
        attrs.put("cacheCreationEphemeral5mTokens", 0L);
        attrs.put("cacheHitRate", cacheHitRate);
        // CC :681-687 queryTracking 存在才附带（条件展开）
        if (queryTracking != null) {
            if (queryTracking.get("chainId") instanceof String chainId) {
                attrs.put("queryChainId", chainId);
            }
            if (queryTracking.get("depth") instanceof Integer depth) {
                attrs.put("queryDepth", depth);
            }
        }
        return attrs;
    }

    /**
     * 发射 {@code tengu_fork_agent_query} 遥测事件 · 对齐 CC {@code logForkAgentQueryEvent}
     * (forkedAgent.ts:631-689) 调 {@code logEvent('tengu_fork_agent_query', {...})} (:656)。
     *
     * <p><b>双发射</b>（recordEvent 1P 计数 + logOTelEvent OTel 转发 · HookRegistry:278-279
     * 惯例，同 AutoDreamConsolidator/ExtractMemoriesAgent）。telemetry 未注入（null）→
     * 静默跳过 + debug 日志（测试/未接线零行为变化）。
     *
     * @param telemetry 遥测实例（RunForkedAgent 静态注入，见 RunForkedAgent.setTelemetry；
     *                  null → 跳过）
     * @param forkLabel analytics 标签 · CC original: {@code forkLabel} (forkedAgent.ts:93)
     * @param querySource CC querySource 字符串（canonical 小写值）· CC original:
     *                    {@code querySource} (forkedAgent.ts:91)
     * @param durationMs fork 查询耗时毫秒 · CC original: {@code durationMs} (forkedAgent.ts:610)
     * @param messageCount fork 产出消息数 · CC original: {@code messageCount} (forkedAgent.ts:617)
     * @param inputTokens usage 输入 token · CC original: {@code totalUsage.input_tokens} (:667)
     * @param outputTokens usage 输出 token · CC original: {@code totalUsage.output_tokens} (:668)
     * @param cacheReadInputTokens usage cache 读 token · CC original:
     *                             {@code totalUsage.cache_read_input_tokens} (:669)
     * @param cacheCreationInputTokens usage cache 写 token · CC original:
     *                             {@code totalUsage.cache_creation_input_tokens} (:670)
     * @param queryTracking 查询链路跟踪（chainId/depth）· CC original:
     *                      {@code toolUseContext.queryTracking} (forkedAgent.ts:619)
     * @param anthropic     协议判定（透传 build · CacheHitRate 分派）：true=Anthropic；
     *                      false=openai/deepseek（input 已含 cache hit）
     */
    static void emitForkAgentQueryEvent(
            Telemetry telemetry, String forkLabel, String querySource, long durationMs,
            int messageCount, long inputTokens, long outputTokens, long cacheReadInputTokens,
            long cacheCreationInputTokens, Map<String, Object> queryTracking, boolean anthropic) {
        if (telemetry == null) {
            if (log.isDebugEnabled()) {
                log.debug("[AgentContext] tengu_fork_agent_query 遥测跳过（telemetry 未注入）: forkLabel={} querySource={}",
                    forkLabel, querySource);
            }
            return;
        }
        Map<String, Object> attrs = buildForkAgentQueryEventAttrs(
            forkLabel, querySource, durationMs, messageCount, inputTokens, outputTokens,
            cacheReadInputTokens, cacheCreationInputTokens, queryTracking, anthropic);
        telemetry.recordEvent("tengu_fork_agent_query", attrs);
        telemetry.logOTelEvent("tengu_fork_agent_query", attrs);
        if (log.isDebugEnabled()) {
            log.debug("[AgentContext] tengu_fork_agent_query 遥测已发射: forkLabel={} querySource={} messages={} durationMs={} cacheHitRate={}",
                forkLabel, querySource, messageCount, durationMs, attrs.get("cacheHitRate"));
        }
    }
}
