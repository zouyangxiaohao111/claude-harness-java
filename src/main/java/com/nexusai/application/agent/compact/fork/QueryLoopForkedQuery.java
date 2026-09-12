package com.nexusai.application.agent.compact.fork;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.AgentLoopContextFactory;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.loop.SubagentLoopDeps;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 生产 ForkedQuery · fork 改走主循环 {@code LlmAgentLoop.queryLoop}（对齐 CC
 * {@code forkedAgent.ts:564} 直接调主循环 {@code query()}）。
 *
 * <p><b>WHY 存在（E-1b-2 · fork 收敛）</b>: CC 的 fork <b>没有独立循环/工具执行代码</b> ——
 * {@code runForkedAgent}（forkedAgent.ts:489-626）把 cache-safe 参数透传给全局 {@code query()}
 * （:545-556），工具执行的 hook 链 / schema 校验 / 权限门 / decision telemetry 全部由主循环承担。
 * 本仓旧路径 {@link ProductionForkedQuery} 自建循环（逐轮 provider 调用 + {@code HookPermissionResolver}
 * 门控 + {@code ToolRegistry.dispatch}），只覆盖主循环工具执行链的一小部分。本类把 6 个 fork 入口
 * 收回到同一个 {@code queryLoop}，工具执行链全量继承（用户决策 2）。
 *
 * <p><b>与 {@link ProductionForkedQuery} 的关系（E-2 才删旧类）</b>: 两者实现同一
 * {@link RunForkedAgent.ForkedQuery} seam；{@code ToolRegistrationConfig} 只把注入点从旧实现切到
 * 本类，<b>旧类与 seam 均保留 ⇒ 一行回退</b>。本类内<b>不设</b>「回落 ProductionForkedQuery」分支
 * （会让新旧等价验证失去判别力）。
 *
 * <p><b>E-1b-2 相对旧实现的语义对齐点（逐条）</b>:
 * <ol>
 *   <li><b>会话模型直传</b>：{@code forkCtx.effectiveModelName()}（= CC
 *       {@code toolUseContext.options.mainLoopModel}）→ 现算 config（真实 baseUrl + 解密 apiKey）。
 *       模型名<b>按原值（provider 全名）</b>传给主循环，由 {@code ModelCaller} 统一剥前缀
 *       （{@code resolveSdkModelName} → 裸发送名）并按全名解析 providerType —— 与旧实现
 *       {@code route.model()}（裸名）+ {@code route.providerType()} 逐字节同源，且避免了裸名跨
 *       provider 重名误路由。</li>
 *   <li><b>usage</b>：{@code AgentUsage.accumulateFromMessages(produced)} 逐条累加 4 字段
 *       （对齐 CC forkedAgent.ts:557-566 message_delta 累加 + {@code SingleMessageAccumulator}
 *       等价物 {@code ChatMessageDto.usage()} 承载逐条 usage）。</li>
 *   <li><b>产出面切片</b>：{@code producedMessages(rawMessages, initialMsgCount)} —— 只回
 *       fork 后新产出的消息（摘要取末条 assistant / 提取扫写路径都依赖该切片）。</li>
 *   <li><b>工具权限</b>：{@code canUseTool} 经 {@code QueryParams.withCanUseTool} 注入 —— 主循环
 *       {@code queryLoop} 入口对后台 fork 来源 fail-loud 守卫（E-1a），工具执行链内
 *       {@code StreamingToolExecutor} 优先消费它（批次 5b）。</li>
 *   <li><b>onMessage</b>：{@code QueryParams.withOnMessage} —— 本类是 D 批铺的该通道的
 *       <b>第一个生产注入点</b>（auto-dream watcher 逐条收集 touchedPaths）。</li>
 * </ol>
 *
 * <p><b>有意不做的（登记处 {@code docs/zjkycode/plans/2026-09-12-fork-converge-E-registry.md}）</b>:
 * <ul>
 *   <li><b>不记 sidechain transcript</b>（用户决策 4 / D-E1a-01）⇒ 不造 CC 的
 *       {@code createAgentId(forkLabel)}（forkedAgent.ts:533）—— CC 该 agentId <b>只</b>用于
 *       sidechain transcript 的键（:534-551），本仓既然不记，agentId 取 {@code null}
 *       （= CC {@code skipTranscript=true} 时 {@code agentId=undefined} 分支）。</li>
 *   <li><b>不引入 thinking/effort</b>：CC {@code runForkedAgent} 透传的参数表里<b>没有</b>
 *       thinkingConfig（forkedAgent.ts:545-556 只有 systemPrompt/userContext/systemContext/
 *       canUseTool/toolUseContext/querySource/maxOutputTokensOverride/maxTurns/skipCacheWrite），
 *       旧实现同样关闭（reasoning 回调空实现）⇒ 保持现状（{@code forLoop} 默认 disabled）。</li>
 * </ul>
 */
public class QueryLoopForkedQuery implements RunForkedAgent.ForkedQuery {

    private static final Logger log = LoggerFactory.getLogger(QueryLoopForkedQuery.class);

    /**
     * 会话模型直传解析（方案 A · 对齐 CC {@code toolUseContext.options.mainLoopModel}）：
     * 入参 = {@code forkCtx.effectiveModelName()}（父会话当前运行模型，provider 全名）；
     * 返回 {@link ProductionForkedQuery.ForkModelRoute}（裸发送名 + provider config + LlmProvider）。
     * 非 null 且解析成功 → 用其 config 路由；失败/缺失 → 回落 supplier（旧 settings 路径，行为不变）。
     * 与 {@link ProductionForkedQuery} 同源（{@code ToolRegistrationConfig.sessionForkModelRoute}）。
     */
    private final Function<String, ProductionForkedQuery.ForkModelRoute> sessionModelRouteResolver;

    /** 回落模型名 supplier（settings → DB 主模型，裸名）· 会话模型缺失/解析失败时使用（同旧实现）。 */
    private final Supplier<String> modelSupplier;

    /** 回落 provider 配置 supplier（settings 模型解析）· 会话模型缺失/解析失败时使用（同旧实现）。 */
    private final Supplier<ProviderConfig> configSupplier;

    /**
     * 主循环上下文工厂 · 经 {@code shared(projectRoot)} 造 fork 隔离 {@link AgentLoopContext}
     * （不共享主会话 LoopSessionState）。仅本 bean 需要它（4 个 fork 调用方 bean 不注入）。
     */
    private final AgentLoopContextFactory contextFactory;

    /**
     * [fork 模型直传] 「会话模型缺失」告警去重（每 querySource 一次）—— 与
     * {@link ProductionForkedQuery} 同判据同文案（缺失时 fork 只能回落全局 supplier，settings 无
     * model → Mock 假回复；首次 warn 显式暴露，后续 debug 防刷屏）。
     */
    private final Set<String> sessionModelMissingWarned = ConcurrentHashMap.newKeySet();

    /**
     * 构造 · 由 {@code ToolRegistrationConfig} 生产注入（与 {@code productionForkedQuery} 同位置）。
     *
     * @param sessionModelRouteResolver 会话模型直传解析（{@code ToolRegistrationConfig.sessionForkModelRoute}）
     * @param modelSupplier             回落模型 supplier（settings/DB 裸名）
     * @param configSupplier            回落 config supplier（settings/DB provider 配置）
     * @param contextFactory            主循环上下文工厂（fork 隔离 ctx）
     */
    public QueryLoopForkedQuery(
            Function<String, ProductionForkedQuery.ForkModelRoute> sessionModelRouteResolver,
            Supplier<String> modelSupplier,
            Supplier<ProviderConfig> configSupplier,
            AgentLoopContextFactory contextFactory) {
        this.sessionModelRouteResolver = sessionModelRouteResolver;
        this.modelSupplier = modelSupplier;
        this.configSupplier = configSupplier;
        this.contextFactory = contextFactory;
    }

    /**
     * 运行 fork · 对齐 CC {@code for await (const message of query({...}))}
     * （forkedAgent.ts:545-564）。
     *
     * @param p fork 查询参数（messages / systemPrompt(pre-append) / userContext / systemContext /
     *          canUseTool / toolUseContext(已隔离) / querySource / maxOutputTokensOverride /
     *          maxTurns / skipCacheWrite / onMessage）
     * @return fork 结果（新产出消息切片 + 逐轮累计 usage + providerType）
     */
    @Override
    public ForkedAgentResult run(RunForkedAgent.ForkQueryParams p) {
        long startTime = System.currentTimeMillis();
        ToolUseContext forkCtx = p.toolUseContext();

        // ── 1. 会话模型直传（语义逐条对齐 ProductionForkedQuery.run :248-290）──
        String sessionModel = forkCtx != null ? forkCtx.effectiveModelName() : null;
        ProductionForkedQuery.ForkModelRoute route = null;
        if (sessionModelRouteResolver != null && sessionModel != null && !sessionModel.isBlank()) {
            ProductionForkedQuery.ForkModelRoute candidate = sessionModelRouteResolver.apply(sessionModel);
            if (candidate != null && candidate.provider() != null && candidate.config() != null
                    && candidate.config().isUsable() && candidate.model() != null
                    && !candidate.model().isBlank()) {
                route = candidate;
            } else {
                log.warn("[QueryLoopForkedQuery] fork 会话模型路由失败(sessionModel={})，回落全局 supplier",
                    sessionModel);
            }
        }

        String model;
        ProviderConfig config;
        if (route != null) {
            log.info("[QueryLoopForkedQuery] fork 会话模型直传: sessionModel={} → providerType={} "
                    + "model={} baseUrl={}（模型名按全名透传，ModelCaller 剥前缀 + 解析 providerType）",
                sessionModel, route.providerType(), route.model(), route.config().baseUrl());
            model = sessionModel;
            config = route.config();
        } else {
            model = modelSupplier != null ? modelSupplier.get() : null;
            config = configSupplier != null ? configSupplier.get() : null;
            // [fail-loud 规则十二 · 同 ProductionForkedQuery :269-284] 会话模型缺失 → 直传分支静默
            //   跳过，只能回落全局 supplier；此前无日志则 SM/extract/dream fork 落 MockLlmProvider
            //   （假回复/永不 Edit）时排障需逐行读代码。每 querySource warn 一次（去重字段）。
            if (sessionModelRouteResolver != null && forkCtx != null) {
                String qs = String.valueOf(p.querySource());
                if (sessionModel == null || sessionModel.isBlank()) {
                    if (sessionModelMissingWarned.add(qs)) {
                        log.warn("[QueryLoopForkedQuery] fork 上下文 effectiveModelName 为空（querySource={}）→ "
                                + "会话模型直传跳过，回落全局 supplier(model={})。若随后 provider=MockLlmProvider，"
                                + "根因即此处：调用方须把会话模型写入 CacheSafeParams.toolUseContext"
                                + ".effectiveModelName（= CC toolUseContext.options.mainLoopModel）",
                            qs, model);
                    } else if (log.isDebugEnabled()) {
                        log.debug("[QueryLoopForkedQuery] fork 会话模型仍为空（querySource={}），继续回落全局 supplier: "
                            + "model={}", qs, model);
                    }
                }
            }
        }
        if (config == null) {
            config = ProviderConfig.empty();
        }

        // ── 2. 隔离 AgentState ──
        //   [session] sessionId 取 fork 隔离上下文的 sessionId（= 父会话 id；无父 = standalone 造）。
        //   [agentId=null 的 CC 依据] CC forkedAgent.ts:533
        //     {@code const agentId = skipTranscript ? undefined : createAgentId(forkLabel)} ——
        //     该 agentId 的唯一用途是 sidechain transcript 的键（:534-551 recordSidechainTranscript）；
        //     本仓 fork 有意不记 sidechain transcript（用户决策 4 / D-E1a-01）⇒ 等价于
        //     {@code skipTranscript=true} 分支 ⇒ agentId=undefined（→ null）。
        //   [systemPrompt=null] fork 的模型面提示由 params.systemPrompt() 承载（CC query({
        //     systemPrompt }) 同源）；state.systemPrompt() 是 CC customSystemPrompt 语义，fork 不设。
        String forkSessionId = forkCtx != null ? forkCtx.sessionId() : null;
        AgentState state = new AgentState(null, forkSessionId, null);
        if (p.maxTurns() != null) {
            // maxTurns 落地在 state（主循环轮末判）+ 透传 QueryParams（循环外消费点）
            state.maxTurns(p.maxTurns());
        }

        // ── 3. 灌 initialMessages（= RunForkedAgent :186-192 的 forkContextMessages + promptMessages）──
        //   注意：不在此处 prependUserContext —— 主循环 s10 在发送边界恰贴一次
        //   （query.ts:900，与旧实现 :238-240 的「唯一前置点」同语义，重复贴会让 prompt cache 永不命中）。
        if (p.messages() != null) {
            for (ChatMessageDto m : p.messages()) {
                if (m != null) {
                    state.appendMessage(m);
                }
            }
        }
        int initialMsgCount = state.rawMessages().size();

        // ── 4. 隔离 deps（主循环上下文工厂 · 不共享主会话 LoopSessionState）──
        //   与 SubagentExecutor.runSubagentQueryLoop 同一构造法（contextFactory.shared + SubagentLoopDeps）。
        AgentLoopContext ctx = contextFactory.shared(AutoMemPaths.currentSessionProjectRoot());
        SubagentLoopDeps deps = new SubagentLoopDeps(ctx);

        // ── 5. QueryParams（pre-append systemPrompt + userContext/systemContext/canUseTool/onMessage）──
        //   systemPrompt 传 fork 的 pre-append 数组（E-1a 契约）；appendSystemContext 由主循环
        //   s10 使用点恰做一次（与旧实现 :323-326 同点同语义）。
        //   maxOutputTokensOverride 透传（INV-7：fork 缓存共享路径恒 null）。
        QueryParams queryParams = QueryParams.forLoop(
                state.rawMessages(), p.systemPrompt(), forkCtx, p.querySource(), model, p.maxTurns(),
                null, /* taskBudget · fork 无 task_budget 语义 */
                null, /* fallbackModel · fork 不做模型降级切换（旧实现同样无 fallback） */
                p.skipCacheWrite(), p.maxOutputTokensOverride(), deps, config)
            .withUserContext(p.userContext())
            .withSystemContext(p.systemContext())
            .withCanUseTool(p.canUseTool())
            .withOnMessage(p.onMessage());

        // ── abort 关联（对齐 SubagentExecutor :4120-4128）──
        AbortController abort = forkCtx != null && forkCtx.abortController() != null
            ? forkCtx.abortController() : AbortController.NOOP;
        abort.onCancel(ac -> {
            if (!state.cancelled()) {
                state.cancel();
            }
        });

        if (log.isInfoEnabled()) {
            log.info("[QueryLoopForkedQuery] fork 发起（主循环 queryLoop）: querySource={} model={} "
                    + "maxTurns={} skipCacheWrite={} maxOutputTokensOverride={} messages={} canUseTool={} "
                    + "onMessage={}",
                p.querySource(), model, p.maxTurns(), p.skipCacheWrite(), p.maxOutputTokensOverride(),
                initialMsgCount, p.canUseTool() != null ? "有(受限)" : "null",
                p.onMessage() != null ? "有" : "null");
        }

        // ── 6. 主循环（唯一流程 · 工具执行链全量继承）──
        LoopResult result = LlmAgentLoop.queryLoop(queryParams, state, new ArrayList<>());

        // ── 7/8. 产出面切片 + 逐条 usage 累加 ──
        List<ChatMessageDto> produced =
            AgentLoopContext.producedMessages(result.finalState().rawMessages(), initialMsgCount);
        AgentUsage usage = AgentUsage.accumulateFromMessages(produced);
        ForkedAgentResult.ForkUsage totalUsage = new ForkedAgentResult.ForkUsage(
            usage.inputTokens(),
            usage.outputTokens(),
            usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0L,
            usage.cacheCreationInputTokens() != null ? usage.cacheCreationInputTokens() : 0L);

        // ── 9. providerType（命中率口径分派载荷）──
        //   来源 = ModelConfigResolver.resolveProviderType(ctx.modelConfigResolver(), model) ——
        //   <b>与 ModelCaller 实际选 provider 的判定同源</b>（ModelCaller:72 同一 static 方法 + 同一
        //   resolver）。归一为工厂实际路由结果（LlmProviderFactory:56-70：anthropic → AnthropicSdkProvider，
        //   其余 → OpenAiSdkProvider），即旧实现 {@code provider.type()} 的等价物（后者只被
        //   RunForkedAgent:264 {@code "anthropic".equals(...)} 消费）。
        String dbProviderType = ModelConfigResolver.resolveProviderType(ctx.modelConfigResolver(), model);
        String providerType = "anthropic".equalsIgnoreCase(dbProviderType) ? "anthropic" : "openai_sdk";

        if (usage.outputTokens() == 0L) {
            log.warn("[QueryLoopForkedQuery] usage 缺口: 全程无 usage 上报，totalUsage 仍空 "
                    + "（CC forkedAgent.ts:557-566 从 message_delta 累计）——provider 未提取 "
                    + "usage 或真实响应无 usage；input/cache 随 usage 缺失同为 0（如实不伪造）");
        }
        log.info("[QueryLoopForkedQuery] fork 完成（主循环 queryLoop）: querySource={} turns={} "
                + "producedMessages={} usageInput={} usageOutput={} usageCacheRead={} usageCacheCreate={} "
                + "aborted={} providerType={} durationMs={}",
            p.querySource(), result.totalTurns(), produced.size(),
            totalUsage.inputTokens(), totalUsage.outputTokens(),
            totalUsage.cacheReadInputTokens(), totalUsage.cacheCreationInputTokens(),
            result.aborted(), providerType, System.currentTimeMillis() - startTime);

        return new ForkedAgentResult(produced, totalUsage, providerType);
    }
}
