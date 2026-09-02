package com.nexusai.application.agent.compact.fork;

import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.CompactConstants;
import com.nexusai.application.agent.compact.StreamCompactSummary;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.permission.hook.HookPermissionResolver;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 通用 fork 查询执行 · 对齐 CC {@code runForkedAgent}
 * (Open-ClaudeCode/src/utils/forkedAgent.ts:489-626)。
 *
 * <p><b>WHY 存在（REQ-27 / INV-7）</b>: compact fork 缓存共享（compact.ts:1155-1248）
 * 通过 fork 复用主线程 prompt cache：fork 与主请求发出完全一致的 cache-key 参数
 * （system prompt / tools / model / messages prefix / thinking config）。本类移植 CC
 * {@code runForkedAgent} 的通用语义：
 * <ol>
 *   <li><b>initialMessages = [...forkContextMessages, ...promptMessages]</b>
 *       （forkedAgent.ts:524）——fork 消息 = 主线程前缀 + 新请求，保证 cache prefix 命中。</li>
 *   <li><b>隔离上下文</b>（forkedAgent.ts:514-518 createSubagentContext）——防止 mutation
 *       父状态；abortController 透传（CC :350-354 {@code overrides?.abortController}），
 *       权限 context 从父继承。</li>
 *   <li><b>query loop 透传 cache-safe 参数</b>（forkedAgent.ts:545-556）——systemPrompt /
 *       userContext / systemContext / canUseTool / querySource / maxOutputTokensOverride /
 *       maxTurns / skipCacheWrite。</li>
 *   <li><b>fork 路径不设 maxOutputTokens</b>（INV-7）——设了会改 budget_tokens 破坏
 *       cache key（compact.ts:1181-1187）。</li>
 * </ol>
 *
 * <p><b>Java query() 等价 seam</b>: CC {@code runForkedAgent} 直接调全局 {@code query()}；
 * Java 端无全局 query()，故本类以 {@link ForkedQuery} 函数式接口作为 query loop seam
 * （测试注入 RecordingQuery 断言 fork 参数；生产由 ToolRegistrationConfig 注入
 * {@link com.nexusai.application.agent.compact.fork.ProductionForkedQuery} —— 专用多轮
 * fork loop，自建 provider 流式循环，<b>不调 LlmAgentLoop.run()</b>，canUseTool 受限门控
 * INV-6 真实生效）。
 *
 * <p><b>本类归属</b>（IMP-18 实施 Agent 决定，已登记进度文件）: 归 compact fork 子域，
 * 独立于 {@link StreamCompactSummary}（IMP-01 保留其内联 tryForkCacheSharing；
 * 后续收敛任务再切到本通用实现）。
 */
public final class RunForkedAgent {

    private static final Logger log = LoggerFactory.getLogger(RunForkedAgent.class);

    /**
     * 遥测实例（静态注入 · Spring 装配时经 {@link #setTelemetry} 设置一次；测试/未装配 →
     * null → {@code tengu_fork_agent_query} 发射静默跳过，零行为变化）。
     *
     * <p><b>为什么静态</b>（IMP-SUB-29 · B10）：本类为无状态工具类，无 Spring 实例字段；
     * 发射点需在 {@link #run} 完成处（CC forkedAgent.ts:612-620 logForkAgentQueryEvent），
     * 单漏斗覆盖 compact / extract-memories / auto-dream / session-memory 全部 fork 路径。
     * 静态 volatile 等价 CC 模块级状态（forkedAgent.ts:73 lastCacheSafeParams 同型）。
     */
    private static volatile Telemetry telemetry;

    /** 注入遥测实例（ToolRegistrationConfig 装配；对齐 AutoDreamConsolidator.setTelemetry 惯例）。 */
    public static void setTelemetry(Telemetry telemetry) {
        RunForkedAgent.telemetry = telemetry;
    }

    private RunForkedAgent() {
        // 工具类不可实例化
    }

    // ════════════════════════════════════════════════════════════════════
    // query() seam（Java 端无全局 query · 以函数式接口表达）
    // ════════════════════════════════════════════════════════════════════

    /**
     * Java query() 等价 · 对齐 CC {@code query(queryParams)}
     * (Open-ClaudeCode/src/query.ts:181-199 QueryParams)。
     */
    @FunctionalInterface
    public interface ForkedQuery {
        ForkedAgentResult run(ForkQueryParams params);
    }

    /**
     * 透传给 query() 的参数 · 对齐 CC runForkedAgent 的 query() 调用
     * （forkedAgent.ts:545-556）。
     *
     * @param messages             initialMessages（forkContextMessages + promptMessages）
     * @param systemPrompt         主线程 system prompt <b>发送前数组</b>（含 boundary 元素；
     *                             发送边界才剥离）· CC original:
     *                             {@code systemPrompt: SystemPrompt} (forkedAgent.ts:59)
     * @param userContext          user context（cache-safe）
     * @param systemContext        system context（cache-safe）
     * @param canUseTool           权限检查函数（compact = createCompactCanUseTool deny）
     * @param toolUseContext       隔离的子 Agent 上下文（继承权限 + abortController 透传）
     * @param querySource          来源标识（compact = QuerySource.COMPACT）
     * @param maxOutputTokensOverride 输出 token 上限（fork 缓存共享路径恒 null，INV-7）
     * @param maxTurns             轮次上限（compact = 1）
     * @param skipCacheWrite       fork 不写缓存（compact = true）
     * @param useGlobalCacheScope  boundary 剥离 gate（发送边界 splitSysPromptPrefix 用；
     *                             由 CacheSafeParams 第 6 字段透传，保证 fork 与主线程 gate 一致）·
     *                             CC original: {@code shouldUseGlobalCacheScope()} (utils/betas.ts:227-233)
     * @param onMessage            每条产出消息到达即回调（G-79 流式 · CC forkedAgent.ts:578
     *                             {@code onMessage?.(message)} —— query loop 产出一条回调一条，
     *                             非完成后回放；null → 紧凑构造器兜底 no-op）
     */
    public record ForkQueryParams(
            List<ChatMessageDto> messages,
            List<String> systemPrompt,
            Map<String, String> userContext,
            Map<String, String> systemContext,
            HookPermissionResolver.CanUseTool canUseTool,
            ToolUseContext toolUseContext,
            QuerySource querySource,
            Integer maxOutputTokensOverride,
            Integer maxTurns,
            boolean skipCacheWrite,
            boolean useGlobalCacheScope,
            Consumer<ChatMessageDto> onMessage) {
        public ForkQueryParams {
            if (messages == null) {
                messages = List.of();
            }
            if (systemPrompt == null) {
                systemPrompt = List.of();
            }
            if (userContext == null) {
                userContext = Map.of();
            }
            if (systemContext == null) {
                systemContext = Map.of();
            }
            if (querySource == null) {
                throw new IllegalArgumentException("ForkQueryParams.querySource is null");
            }
            // G-79: onMessage null → no-op（调用方不传回调时 query loop 可直接调用，
            //   对齐 CC `onMessage?.(message)` 可选调用语义）
            if (onMessage == null) {
                onMessage = msg -> { };
            }
        }

    }

    // ════════════════════════════════════════════════════════════════════
    // runForkedAgent 主体（forkedAgent.ts:489-626）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 运行 fork 查询 loop · 对齐 CC {@code runForkedAgent}（forkedAgent.ts:489-626）。
     *
     * @param params fork 参数（promptMessages / cacheSafeParams / canUseTool / querySource /
     *               forkLabel / maxOutputTokens / maxTurns / skipCacheWrite / abortController）
     * @param query  Java query() seam（生产接 LlmAgentLoop.run；测试注入 RecordingQuery）
     * @return fork 结果（messages + totalUsage）
     */
    public static ForkedAgentResult run(ForkedAgentParams params, ForkedQuery query) {
        if (params == null) {
            throw new IllegalArgumentException("RunForkedAgent.run: params is null");
        }
        if (params.cacheSafeParams() == null) {
            throw new IllegalArgumentException("RunForkedAgent.run: cacheSafeParams is null");
        }
        if (query == null) {
            throw new IllegalArgumentException("RunForkedAgent.run: query is null");
        }
        long startTime = System.currentTimeMillis();
        CacheSafeParams cs = params.cacheSafeParams();

        // ── 1. initialMessages = [...forkContextMessages, ...promptMessages]（forkedAgent.ts:524）──
        List<ChatMessageDto> initialMessages = new ArrayList<>();
        if (cs.forkContextMessages() != null) {
            initialMessages.addAll(cs.forkContextMessages());
        }
        if (params.promptMessages() != null) {
            initialMessages.addAll(params.promptMessages());
        }

        // ── 2. 隔离上下文（继承权限 + abortController 透传 + readFileState 共享）· 对齐 createSubagentContext ──
        //    （forkedAgent.ts:514-518 + :350-354 overrides?.abortController ?? ...
        //    + :324 overrides?.readFileState ?? undefined —— session-memory fork 传
        //    setupContext.readFileState 共享缓存，Edit read-before-write 门禁放行）
        ToolUseContext isolatedCtx = createIsolatedContext(
            cs.toolUseContext(), params.abortController(), params.readFileState());

        // ── 3. query loop 透传 cache-safe 参数（forkedAgent.ts:545-556）──
        // [RES-R4-2] CacheSafeParams.systemPrompt 数组语义直接透传（forkedAgent.ts:59 + :545-556
        // query({systemPrompt}) 不经 join 扁平化）：boundary 元素保留到发送边界（ProductionForkedQuery
        // streamOnce 前按 useGlobalCacheScope gate 调 splitSysPromptPrefix 剥离，与主线程
        // LlmAgentLoop:2903-2911 同款），boundary 永不达 LLM。
        ForkQueryParams queryParams = new ForkQueryParams(
            initialMessages,
            cs.systemPrompt(),
            cs.userContext(),
            cs.systemContext(),
            params.canUseTool(),
            isolatedCtx,
            params.querySource(),
            params.maxOutputTokens(),   // INV-7: fork 缓存共享路径恒 null（不设）
            params.maxTurns(),
            params.skipCacheWrite(),
            cs.useGlobalCacheScope(),  // gate 透传：fork 发送边界与主线程同一判定
            params.onMessage());       // G-79 流式透传：query loop 产出一条回调一条（forkedAgent.ts:578）

        if (log.isDebugEnabled()) {
            log.debug("[RunForkedAgent] fork 查询发起: forkLabel={} querySource={} maxTurns={} "
                    + "skipCacheWrite={} maxOutputTokens={} messages={} abortShared={}",
                params.forkLabel(), params.querySource(), params.maxTurns(), params.skipCacheWrite(),
                params.maxOutputTokens(), initialMessages.size(),
                isolatedCtx.abortController() == params.abortController());
        }

        // G-79（F1 · forkedAgent.ts:578）：onMessage 由 query loop 在消息产出时逐条回调，
        //   本类不再 post-hoc replay（旧 :192-195 回放已删）——回调时序对齐 CC 流式语义
        //   （dream task 进度 UI 在 fork 进行中即更新，非完成后一次性）。
        ForkedAgentResult result = query.run(queryParams);

        long durationMs = System.currentTimeMillis() - startTime;
        ForkedAgentResult.ForkUsage usage =
            result.totalUsage() == null ? ForkedAgentResult.ForkUsage.empty() : result.totalUsage();
        if (log.isInfoEnabled()) {
            log.info("[RunForkedAgent] fork 查询完成: forkLabel={} querySource={} messages={} "
                    + "durationMs={} cacheRead={} cacheCreate={}",
                params.forkLabel(), params.querySource(),
                result.messages() == null ? 0 : result.messages().size(), durationMs,
                usage.cacheReadInputTokens(), usage.cacheCreationInputTokens());
        }
        // B10（IMP-SUB-29）: 发射 tengu_fork_agent_query 遥测 · 对齐 CC forkedAgent.ts:612-620
        //   （logForkAgentQueryEvent 在 runForkedAgent 完成时发射，全字段 + cacheHitRate 派生）。
        //   queryTracking 取父 toolUseContext（CC :619 toolUseContext.queryTracking）·
        //   querySource 取 canonical 小写值（QuerySource.canonical，如 'compact'/'session_memory'）。
        AgentContext.emitForkAgentQueryEvent(
            telemetry,
            params.forkLabel(),
            params.querySource().canonical(),
            durationMs,
            result.messages() == null ? 0 : result.messages().size(),
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheReadInputTokens(),
            usage.cacheCreationInputTokens(),
            cs.toolUseContext() != null ? cs.toolUseContext().queryTracking() : null);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════
    // 支撑（创建隔离上下文 / deny canUseTool / maxOutputTokensOverride）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 创建隔离的子 Agent 上下文 · 对齐 CC forkedAgent.ts:514-518 createSubagentContext。
     *
     * <p><b>abortController 透传</b>（CC :350-354 {@code overrides?.abortController ?? ...}）:
     * 通过 {@link ToolUseContext#with(ToolUseContext.SubagentContextOverrides)} 的
     * {@code overrides.abortController()} 字段实现 —— {@code with()} 内部 honor
     * override（ToolUseContext.java:1162-1165）。用户 Esc → abort() 可中止 fork。
     *
     * <p><b>继承权限</b>: {@code with()} 从父继承 permissionContext / permissionMode，
     * fork 使用与主线程一致的权限（CC "继承主线程 toolPermissionContext"）。
     *
     * <p><b>readFileState 共享</b>（session-memory 提取链，CC sessionMemory.ts:324）:
     * {@code overrides.readFileState()} 非 null → fork 与 setup 上下文共享同一缓存
     * （{@code with()} :1356-1358 override 直用），setup 阶段 FileReadTool 回填的
     * entry 让 fork 内 Edit read-before-write 门禁放行；null（compact/extract-memories）
     * → with() 从父 clone，行为不变。
     *
     * @param parent        父 ToolUseContext（cacheSafeParams.toolUseContext；可为 null = standalone）
     * @param abortOverride abortController 透传（compact.ts:1196-1199；null → with() 默认
     *                      share/createChild）
     * @param readFileState 共享 readFileState 缓存（sessionMemory.ts:324；null = 父 clone）
     */
    private static ToolUseContext createIsolatedContext(
            ToolUseContext parent, AbortController abortOverride,
            FileStateCache readFileState) {
        ToolUseContext.SubagentContextOverrides overrides =
            new ToolUseContext.SubagentContextOverrides(
                null, null, null, abortOverride, null, readFileState, null, null, null, null,
                null, null, null, null);
        if (parent != null) {
            return parent.with(overrides);
        }
        // 无父（standalone）→ 最小独立上下文（对齐 CC createSubagentContext(null, overrides)）
        // [session-id-short] sessionId 统一 short 形态（sess-xxx）
        ToolUseContext standalone = new ToolUseContext(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT,
            Map.of(), List.of(), "",
            abortOverride != null ? abortOverride : AbortController.NOOP,
            List.of());
        return standalone.with(overrides);
    }

    /**
     * 创建最小 cache-safe params · 对齐 CC {@code createCacheSafeParams}
     * (Open-ClaudeCode/src/utils/forkedAgent.ts:131-141)。
     *
     * <p><b>为什么存在（IMP-M-P0-3 后台 fork 接线 + RES-C5 填 systemPrompt）</b>：extract-memories /
     * auto-dream / session-memory 后台 fork（querySource='extract_memories'/'auto_dream'/
     * 'session_memory'）在主会话 cache-safe params 未注入时兜底构造 CacheSafeParams：
     * forkContextMessages = 待处理消息前缀（forkedAgent.ts:67），systemPrompt/userContext/
     * systemContext 由调用方注入会话原料（REPLHookContext 等价 PostSamplingContext 的
     * systemPrompt/userContext/systemContext），toolUseContext = 独立最小上下文
     * （PermissionMode.DEFAULT + NOOP abort，对齐 RunForkedAgentTest.baseContext 语义）。
     *
     * <p><b>[RES-C5] systemPrompt 不再恒空</b>：CC {@code createCacheSafeParams(context)} 从
     * REPLHookContext 完整构建 systemPrompt/userContext/systemContext/toolUseContext/
     * forkContextMessages（forkedAgent.ts:131-141）——兜底 CacheSafeParams 的 systemPrompt 若
     * 恒空，fork 缓存 key 与主循环不一致（缓存永不命中）且发送边界 splitSysPromptPrefix 无真实
     * 输入。本方法改为接受调用方注入的真实会话原料；null/空原料仍降级为原 {@code List.of()}
     * 行为（RES-C5 验收 2：无主会话原料不抛错）。
     *
     * <p><b>[RES-C5] gate 透传</b>：{@code useGlobalCacheScope} 由调用方注入（GlobalCacheScope
     * 单实现消费方 · 对齐 CC {@code shouldUseGlobalCacheScope()} utils/betas.ts:227-233），
     * 保证 fork 发送边界与主线程同一 gate 判定（REQ-C5-4）。
     *
     * @param forkContextMessages 主线程消息前缀（fork 的 cache prefix）
     * @param systemPrompt        主线程 system prompt 发送前数组（含 boundary 元素；调用方注入
     *                             会话原料；null → 原 List.of() 降级）· CC original:
     *                             {@code systemPrompt: SystemPrompt} (forkedAgent.ts:131)
     * @param userContext         user context（cache-safe）· CC original:
     *                            {@code userContext} (forkedAgent.ts:132)
     * @param systemContext       system context（cache-safe）· CC original:
     *                            {@code systemContext} (forkedAgent.ts:133)
     * @param useGlobalCacheScope boundary/gate 判定值（fork 与主线程一致）· CC original:
     *                            {@code shouldUseGlobalCacheScope()} (utils/betas.ts:227-233)
     * @return 最小 CacheSafeParams（toolUseContext 恒非 null；systemPrompt/userContext/
     *         systemContext 由原料填充，null 降级空）
     */
    public static CacheSafeParams createMinimalCacheSafeParams(
            List<ChatMessageDto> forkContextMessages,
            List<String> systemPrompt,
            Map<String, String> userContext,
            Map<String, String> systemContext,
            boolean useGlobalCacheScope) {
        // [session-id-short] sessionId 统一 short 形态（sess-xxx）
        ToolUseContext standalone = new ToolUseContext(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT,
            Map.of(), List.of(), "", AbortController.NOOP, List.of());
        // CacheSafeParams 紧凑构造对 null 兜底（systemPrompt/userContext/systemContext/
        // forkContextMessages → List.of()/Map.of()）· 无主会话原料不抛错（RES-C5 验收 2）
        return new CacheSafeParams(systemPrompt, userContext, systemContext, standalone,
            forkContextMessages, useGlobalCacheScope);
    }

    /**
     * 压缩 fork 的 canUseTool · deny 语义 · 对齐 CC {@code createCompactCanUseTool}
     * (Open-ClaudeCode/src/services/compact/compact.ts:1125-1133)。
     *
     * <p><b>WHY</b>: 压缩 summary 模型<b>不能调用任意工具</b>，只允许产出文本摘要
     * （compact.ts:1125-1133 返回 deny + "Tool use is not allowed during compaction"）。
     * Java 端 {@link ToolPermissionGate.DecisionResult} 无原始 PermissionResult 上下文时
     * result=null（ToolPermissionGate.java:380-384），deny 语义由 {@link ToolPermissionGate.Decision#DENY}
     * 表达。
     *
     * @return 恒返回 DENY 的 CanUseTool
     */
    public static HookPermissionResolver.CanUseTool createCompactCanUseTool() {
        return (tool, input, ctx, toolUseId, forceDecision) ->
            ToolPermissionGate.DecisionResult.deny(null);
    }

    /**
     * 压缩摘要最大输出 token override · 对齐 CC compact.ts:1317-1320：
     * {@code maxOutputTokensOverride = Math.min(COMPACT_MAX_OUTPUT_TOKENS,
     * getMaxOutputTokensForModel(model))}。
     *
     * <p><b>使用点（INV-7 / REQ-27）</b>: 引用 {@link CompactConstants#COMPACT_MAX_OUTPUT_TOKENS}
     * （utils/context.ts:12 = 20_000）。本方法供<b>流式 fallback</b>（非 cache 共享路径）
     * 使用；fork 缓存共享路径<b>不设 maxOutputTokens</b>（见 {@link ForkedAgentParams#maxOutputTokens()}）。
     *
     * @param model 当前模型
     * @return min(20000, getMaxOutputTokensForModel(model))
     */
    public static int maxOutputTokensOverride(String model) {
        return Math.min(
            CompactConstants.COMPACT_MAX_OUTPUT_TOKENS,
            StreamCompactSummary.getMaxOutputTokensForModel(model));
    }
}
