package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.StreamCompactSummary;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.cost.SessionUsageLedger;
import com.nexusai.application.agent.cost.SessionUsageAccountingRegistry;
import com.nexusai.application.agent.cost.UsageBucket;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmCallScope;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [usage-gateway · compact] 压缩那次 LLM 调用的 usage 必须经<b>请求网关</b>入会话账本
 * （CC claude.ts:2361 的成本口径）。
 *
 * <p><b>WHY 改造（原 [compact-cost] 通道已删）</b>：用户裁定「压缩的用量入账必须走统一流程，
 * 旧通道 {@code LlmAgentLoop.accumulateCompactionSessionCost} 必须删除（不允许两套）」。改造后
 * auto / reactive 压缩的 LLM 调用（经 {@code AutoCompactor}/{@code ReactiveCompactor} 的
 * summaryProducer = {@link StreamCompactSummary}）在 {@code StreamCompactSummary.streamOnce} 内
 * 以真实 sessionId 盖章归属（{@code UsageCategory.COMPACT}）→ 请求网关
 * （{@code LlmInvocationGateway.streamWith}）sink → 结算 → 会话账本
 * （{@link SessionUsageLedger}），四类结局统一记账。本类验证该新通道（不再验证已删的旧通道）。
 *
 * <p><b>RED teeth（哪条断言红）</b>：
 * <ul>
 *   <li>压缩摘要不经网关（例如直接 {@code provider.stream} 不写 sink / 不盖 recorder）→
 *       {@link #autoCompactSuccess_accountedViaGatewayLedger()} 的账本 calls/token/cost 断言全红；</li>
 *   <li>reactive 分支删掉同源接线 → {@link #reactiveCompactSuccess_accountedViaGatewayLedger()} 红；</li>
 *   <li>模型口径取错（不传 {@code modelSupplier} 的真实模型）→
 *       {@link #compactionCost_followsTheModelThatRanTheCompactionCall()} 红（金额按模型单价分派）；</li>
 *   <li>把压缩 usage 塞进 runUsage → 各用例的 {@code runUsage()} 零值断言红（side call 边界）。</li>
 * </ul>
 */
class CompactSessionCostWiringTest {

    /** 真正执行压缩调用的模型（= CC options.mainLoopModel；deps.resolveModel() 返回值）。 */
    private static final String COMPACT_MODEL = "compact-model-a";

    /** 单价陷阱模型：非 COMPACT_MODEL 按 1000 元/输入侧 token 计价 → 模型取错金额放大 1000 倍。 */
    private static final String TRAP_MODEL = "declared-model-expensive";

    /** 压缩调用 usage 四元组（input 1000 / output 200 / cache_read 3000 / cache_create 400）。 */
    private static final int C_INPUT = 1_000;
    private static final int C_OUTPUT = 200;
    private static final int C_CACHE_READ = 3_000;
    private static final int C_CACHE_CREATE = 400;

    /** 单价 1 元/输入侧 token（output 不计 → 主循环文本回合的估算输出不污染金额断言）→ 4400.0。 */
    private static final double EXPECTED_COMPACT_COST = 1.0 * (C_INPUT + C_CACHE_READ + C_CACHE_CREATE);

    private List<CostCall> costCalls;

    @BeforeEach
    void setUp() {
        costCalls = Collections.synchronizedList(new ArrayList<>());
    }

    private record CostCall(String model, AgentUsage usage) {}

    // ════════════════════════════════════════════════════════════════════
    // 1. auto 路径（经 StreamCompactSummary → 请求网关 → 会话账本）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("auto 压缩成功：压缩调用 usage 经请求网关入会话账本（COMPACT 归属）· 不进 runUsage")
    void autoCompactSuccess_accountedViaGatewayLedger() {
        String sessionId = "sess-auto-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", sessionId, null);
        // 注：不带 assistant usage 的消息集（AutoCompactor 对「含真实 assistant usage」的会话走
        // usage-walk 阈值，本测试用注入 tokenCounter=200000 的估算路径触发自动压缩）。
        state.replaceMessages(List.of(
            message("m1", Role.user, "question-1", null, null),
            message("m2", Role.user, "question-2", null, null)));

        SessionUsageAccountingRegistry registry = ledgerRegistry(recordingCalculator());
        StreamCompactSummary summary = summaryProvider(COMPACT_MODEL, registry);
        AutoCompactor autoCompactor = new AutoCompactor(msgs -> 2_000_000, summary);

        AgentLoopContext ctx = agentLoopContext(plainReplyProvider(), null, null);
        QueryParams callerParams = params(state, COMPACT_MODEL, ctx, sessionId);
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(
            callerParams.deps().context(), callerParams, state), state, new ArrayList<>(), autoCompactor);

        // ① 账本：压缩那次调用记 1 次，四通道 token 逐字段入账（COMPACT 节点 = 会话根）
        SessionUsageLedger ledger = registry.forSession(sessionId);
        assertThat(ledger.totalCalls())
            .as("压缩调用必须经请求网关记入会话账本；未接线 → 0（旧通道已删，不允许两套）")
            .isEqualTo(1L);
        UsageBucket bucket = ledger.bucketOf(sessionId);
        assertThat(bucket.inputTokens()).isEqualTo(C_INPUT);
        assertThat(bucket.outputTokens()).isEqualTo(C_OUTPUT);
        assertThat(bucket.cacheReadTokens()).isEqualTo(C_CACHE_READ);
        assertThat(bucket.cacheWriteTokens()).isEqualTo(C_CACHE_CREATE);
        assertThat(bucket.streamCalls()).isEqualTo(1L);
        assertThat(bucket.totalCostYuan())
            .as("成本 = ModelCostCalculator 对压缩调用 usage 的折算（单价 1 元/输入侧 token）")
            .isEqualTo(EXPECTED_COMPACT_COST);
        // ② 按模型 rollup（sessions.model_usage_json 源）
        CostTracker.ModelUsage mu = ledger.modelUsage().get(COMPACT_MODEL);
        assertThat(mu).as("model_usage rollup 必须有压缩模型条目").isNotNull();
        assertThat(mu.inputTokens()).isEqualTo(C_INPUT);
        assertThat(mu.cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
        assertThat(mu.cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
        assertThat(mu.costUSD()).isEqualTo(EXPECTED_COMPACT_COST);
        // ③ 模型口径：账本计价拿到的是真正执行压缩的模型
        assertThat(costCalls)
            .as("压缩调用必须经 ModelCostCalculator 计价，模型 = 真正执行压缩的模型")
            .anySatisfy(c -> {
                assertThat(c.model()).isEqualTo(COMPACT_MODEL);
                assertThat(c.usage().inputTokens()).isEqualTo(C_INPUT);
                assertThat(c.usage().outputTokens()).isEqualTo(C_OUTPUT);
                assertThat(c.usage().cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
                assertThat(c.usage().cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
            });
        // ④ 回归锁：runUsage（complete.usage 源）不含压缩 —— CC result.usage 只在主循环
        //    message_stop 累加，压缩是 side call（塞进去反而不对齐 CC）
        assertThat(state.runUsage().inputTokens()).isZero();
        assertThat(state.runUsage().outputTokens()).isZero();
        assertThat(state.runUsage().cacheReadInputTokens()).isZero();
        assertThat(state.runUsage().cacheCreationInputTokens()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. reactive 路径（同一条网关记账通道）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reactive 压缩成功：压缩调用 usage 同样经请求网关入会话账本 · CC query.ts:1134")
    void reactiveCompactSuccess_accountedViaGatewayLedger() {
        String sessionId = "sess-reactive-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", sessionId, null);
        preCompactMessages().forEach(state::appendMessage);
        int original = state.rawMessages().size();

        SessionUsageAccountingRegistry registry = ledgerRegistry(recordingCalculator());
        ReactiveCompactor rc = new ReactiveCompactor(
            new TokenEstimator()::estimateMessageTokens, summaryProvider(COMPACT_MODEL, registry));
        rc.setEnabled(true);
        AgentLoopContext ctx = agentLoopContext(ptlOnceThenStopProvider(), rc, null);

        QueryParams callerParams = reactiveParams(state, COMPACT_MODEL, ctx, sessionId);
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(
            callerParams.deps().context(), callerParams, state), state, new ArrayList<>());

        // 前置：reactive compact 真实发生（消息被压缩）——否则本测试空转
        assertThat(state.rawMessages().size())
            .as("PTL 必须走 reactive compact（消息数下降）· CC query.ts:1138")
            .isLessThan(original);

        SessionUsageLedger ledger = registry.forSession(sessionId);
        assertThat(ledger.totalCalls())
            .as("reactive 压缩调用必须经请求网关记入会话账本（旧通道已删）")
            .isEqualTo(1L);
        UsageBucket bucket = ledger.bucketOf(sessionId);
        assertThat(bucket.inputTokens()).isEqualTo(C_INPUT);
        assertThat(bucket.cacheReadTokens()).isEqualTo(C_CACHE_READ);
        assertThat(bucket.cacheWriteTokens()).isEqualTo(C_CACHE_CREATE);
        assertThat(bucket.totalCostYuan()).isEqualTo(EXPECTED_COMPACT_COST);
        CostTracker.ModelUsage mu = ledger.modelUsage().get(COMPACT_MODEL);
        assertThat(mu).isNotNull();
        assertThat(mu.inputTokens()).isEqualTo(C_INPUT);
        assertThat(costCalls)
            .as("reactive 压缩调用必须以真正执行压缩的模型计价")
            .anySatisfy(c -> {
                assertThat(c.model()).isEqualTo(COMPACT_MODEL);
                assertThat(c.usage().inputTokens()).isEqualTo(C_INPUT);
            });
        // 回归锁：runUsage 仍不含压缩（side call 边界）
        assertThat(state.runUsage().inputTokens()).isZero();
        assertThat(state.runUsage().outputTokens()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 模型口径：金额随压缩调用的模型变（取错模型 → 金额错）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("模型口径：账本金额按真正执行压缩的模型算 —— 换模型金额随之变（取错模型即算错）")
    void compactionCost_followsTheModelThatRanTheCompactionCall() {
        // ① modelSupplier=COMPACT_MODEL（单价 1 元/输入侧 token）→ 4400
        String sessionA = "sess-scope-a-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState a = new AgentState("sys", sessionA, null);
        a.replaceMessages(List.of(message("m1", Role.user, "q", null, null)));
        SessionUsageAccountingRegistry regA = ledgerRegistry(recordingCalculator());
        AgentLoopContext ctxA = agentLoopContext(plainReplyProvider(), null, null);
        QueryParams callerA = params(a, COMPACT_MODEL, ctxA, sessionA);
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(
            callerA.deps().context(), callerA, a), a, new ArrayList<>(),
            new AutoCompactor(msgs -> 2_000_000, summaryProvider(COMPACT_MODEL, regA)));
        SessionUsageLedger ledgerA = regA.forSession(sessionA);
        assertThat(ledgerA.bucketOf(sessionA).totalCostYuan())
            .as("模型 = compact-model-a（单价 1）→ 4400")
            .isEqualTo(EXPECTED_COMPACT_COST);
        assertThat(ledgerA.modelUsage()).containsKey(COMPACT_MODEL);
        assertThat(ledgerA.modelUsage()).doesNotContainKey(TRAP_MODEL);

        // ② modelSupplier=TRAP_MODEL（单价 1000 元/输入侧 token）→ 放大 1000 倍
        List<CostCall> callsB = new ArrayList<>(costCalls);
        String sessionB = "sess-scope-b-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState b = new AgentState("sys", sessionB, null);
        b.replaceMessages(List.of(message("m1", Role.user, "q", null, null)));
        SessionUsageAccountingRegistry regB = ledgerRegistry(recordingCalculator(callsB));
        AgentLoopContext ctxB = agentLoopContext(plainReplyProvider(), null, null);
        QueryParams callerB = params(b, TRAP_MODEL, ctxB, sessionB);
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(
            callerB.deps().context(), callerB, b), b, new ArrayList<>(),
            new AutoCompactor(msgs -> 2_000_000, summaryProvider(TRAP_MODEL, regB)));
        SessionUsageLedger ledgerB = regB.forSession(sessionB);
        assertThat(ledgerB.bucketOf(sessionB).totalCostYuan())
            .as("模型换成高单价的 declared-model-expensive → 金额必须随之放大（证明模型真实参与计价，"
                + "而非写死/取会话主模型）")
            .isEqualTo(EXPECTED_COMPACT_COST * 1000.0);
        assertThat(callsB)
            .as("压缩调用的计价模型必须 = 本次压缩调用实际使用的模型（TRAP_MODEL）")
            .anySatisfy(c -> {
                assertThat(c.model()).isEqualTo(TRAP_MODEL);
                assertThat(c.usage().inputTokens()).isEqualTo(C_INPUT);
            });
        assertThat(ledgerB.modelUsage()).containsKey(TRAP_MODEL);
        assertThat(ledgerB.modelUsage()).doesNotContainKey(COMPACT_MODEL);
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 重复压缩：每次都记（不漏计、不覆盖）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("重复压缩：两次压缩各自入账（累加不覆盖、不漏计）")
    void repeatedCompaction_countsEachTime() {
        String sessionId = "sess-repeat-" + UUID.randomUUID().toString().substring(0, 8);
        AgentState state = new AgentState("sys", sessionId, null);
        state.replaceMessages(List.of(message("m1", Role.user, "q", null, null)));

        SessionUsageAccountingRegistry registry = ledgerRegistry(recordingCalculator());
        StreamCompactSummary summary = summaryProvider(COMPACT_MODEL, registry);
        AutoCompactor autoCompactor = new AutoCompactor(msgs -> 2_000_000, summary);
        AgentLoopContext ctx = agentLoopContext(plainReplyProvider(), null, null);

        QueryParams caller1 = params(state, COMPACT_MODEL, ctx, sessionId);
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(
            caller1.deps().context(), caller1, state), state, new ArrayList<>(), autoCompactor);
        SessionUsageLedger ledger = registry.forSession(sessionId);
        assertThat(ledger.bucketOf(sessionId).totalCostYuan())
            .as("第 1 次压缩后 = 4400")
            .isEqualTo(EXPECTED_COMPACT_COST);
        assertThat(ledger.bucketOf(sessionId).inputTokens()).isEqualTo(C_INPUT);

        // 第二次压缩：重置为普通消息集（压缩后的 boundary/summary 携带 usage → 会走 usage-walk
        // 阈值判定，与本次断言的「压缩调用触发」无关），同一 AgentState 继续累计。
        state.replaceMessages(List.of(message("m9", Role.user, "second-round", null, null)));
        QueryParams caller2 = params(state, COMPACT_MODEL, ctx, sessionId);
        LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(
            caller2.deps().context(), caller2, state), state, new ArrayList<>(), autoCompactor);

        assertThat(ledger.bucketOf(sessionId).totalCostYuan())
            .as("第 2 次压缩必须再记一次（成本累加 8800，既不漏计也不覆盖）")
            .isEqualTo(2 * EXPECTED_COMPACT_COST);
        assertThat(ledger.bucketOf(sessionId).inputTokens())
            .as("两次压缩 input token 各计一次 = 2000")
            .isEqualTo(2L * C_INPUT);
        assertThat(ledger.totalCalls()).isEqualTo(2L);
        CostTracker.ModelUsage mu = ledger.modelUsage().get(COMPACT_MODEL);
        assertThat(mu).isNotNull();
        assertThat(mu.inputTokens()).as("按模型 rollup 累加（非覆盖）= 2000").isEqualTo(2L * C_INPUT);
        assertThat(mu.cacheReadInputTokens()).isEqualTo(2L * C_CACHE_READ);
        assertThat(mu.costUSD()).isEqualTo(2 * EXPECTED_COMPACT_COST);
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * 真实 {@link StreamCompactSummary}（压缩摘要生产）· provider 桩在流内把真实 usage 写入
     * 网关 sink（{@code LlmCallScope.recordSettled} → UPSTREAM），registry 注入 → 网关记账进会话账本。
     * modelSupplier 决定本次压缩调用实际使用的模型（成本口径验证依赖它）。
     */
    private static StreamCompactSummary summaryProvider(String model, SessionUsageAccountingRegistry registry) {
        LlmProvider provider = usageProvider();
        StreamCompactSummary summary = new StreamCompactSummary(
            () -> provider, () -> model, () -> ProviderConfig.empty());
        summary.setUsageLedgerRegistry(registry);
        return summary;
    }

    /** 会话账本注册表 + 记录型计费器（ReflectionTestUtils 注入 · 生产由 Spring 注入）。 */
    private SessionUsageAccountingRegistry ledgerRegistry(ModelCostCalculator calc) {
        SessionUsageAccountingRegistry registry = new SessionUsageAccountingRegistry();
        ReflectionTestUtils.setField(registry, "costCalculator", calc);
        return registry;
    }

    /** 压缩摘要 provider 桩：返回 "summary text" + 真实 usage，并把 usage 写入网关 sink。 */
    private static LlmProvider usageProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        AgentUsage usage = new AgentUsage(C_INPUT, C_OUTPUT, (long) C_CACHE_CREATE, (long) C_CACHE_READ,
            null, null, null);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            LlmCallScope scope = inv.getArgument(18);
            onChunk.accept("summary text");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("summary text", "stop", List.of(), "", null, usage));
            }
            // 该次尝试结局的累计 usage 写入采集器 → 网关结算记入账本（UPSTREAM 来源）。
            LlmCallScope.recordSettled(scope, usage);
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /**
     * 记录型计费器：单价按模型分派（COMPACT_MODEL=1.0 / 其他=1000.0）× 输入侧 token 之和
     * （output 不计 → 主循环文本回合的估算输出不污染金额断言 → 可精确断言压缩贡献）。
     */
    private ModelCostCalculator recordingCalculator() {
        return recordingCalculator(costCalls);
    }

    private ModelCostCalculator recordingCalculator(List<CostCall> sink) {
        ModelCostCalculator calc = mock(ModelCostCalculator.class);
        when(calc.isPeakHour()).thenReturn(false);
        when(calc.contextWindowFor(anyString())).thenReturn(200_000);
        when(calc.maxOutputFor(anyString())).thenReturn(8_192L);
        // V101：账本计价点 = price(...)（总额 = breakdown.totalYuan()）→ 替身的金额放 inputYuan，其余分项 0。
        Answer<ModelCostCalculator.CostBreakdown> answer = inv -> {
            String model = inv.getArgument(0);
            AgentUsage u = inv.getArgument(1);
            sink.add(new CostCall(model, u));
            double unit = COMPACT_MODEL.equals(model) ? 1.0 : 1000.0;
            long inputSide = u.inputTokens()
                + (u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0L)
                + (u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0L);
            double amount = unit * inputSide;
            return new ModelCostCalculator.CostBreakdown(amount, 0.0, 0.0, 0.0, amount, amount);
        };
        when(calc.price(anyString(), any(AgentUsage.class), anyBoolean())).thenAnswer(answer);
        return calc;
    }

    private static QueryParams params(AgentState state, String resolvedModel, AgentLoopContext ctx,
                                      String sessionId) {
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), sessionId),
            QuerySource.USER, TRAP_MODEL, null,
            null, null, null, null,
            deps(resolvedModel, ctx), ProviderConfig.empty());
    }

    /** reactive 场景：60 条历史 + 末位 assistant 带 usage（触发 PTL → reactive compact）。 */
    private static QueryParams reactiveParams(AgentState state, String resolvedModel, AgentLoopContext ctx,
                                              String sessionId) {
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), sessionId),
            QuerySource.USER, "test-model", 8,
            null, null, null, null,
            deps(resolvedModel, ctx), ProviderConfig.empty());
    }

    /** deps.resolveModel() 非 null → loop 用它作本 turn 有效模型（= 压缩调用模型）。 */
    private static LoopDeps deps(String resolvedModel, AgentLoopContext ctx) {
        return new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
            @Override public String resolveModel() { return resolvedModel; }
        };
    }

    private static final FeatureFlags REACTIVE_FLAGS = new FeatureFlags(
        true, false, false, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, false, false, false);

    /**
     * 构造 36 组件 ctx（第 36 位 = modelCostCalculator）。
     *
     * <p><b>[usage-gateway] 传入 null</b>：压缩记账的计价点已内聚在 {@code SessionUsageLedger}
     * 的 calculator（本测试经 registry 注入记录型计费器）—— ctx 的 modelCostCalculator 只服务
     * 主循环文本回合的会话累计，本测试文本回合 usage 全零且不参与断言，传 null 避免噪声调用。
     */
    private static AgentLoopContext agentLoopContext(LlmProviderFactory factory,
                                                     ReactiveCompactor rc, ModelCostCalculator calc) {
        return new AgentLoopContext(
            null, null, null, null, null, null, null, null, null, null,   // 1-10
            factory, null, null, null, null, null, null, null, null,      // 11-19
            rc != null ? REACTIVE_FLAGS : FeatureFlags.ALL_DISABLED,      // 20 featureFlags
            rc, null, null, null, null,                                   // 21-25
            null, null, null, null, null,                                 // 26-30
            null, null, null, null, null,                                 // 31-35
            calc,                                                         // 36 modelCostCalculator
            null,                                                         // 37 microCompactor（G1）
            null);                                                        // 38 autoCompactor（G1）
    }

    // ── provider 桩 ──

    /** 单次调用返回 stop 纯文本（auto 路径：无 PTL，正常收尾；无 usage 上报）。 */
    private static LlmProviderFactory plainReplyProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            Consumer<String> onChunk = inv.getArgument(9);
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    /** 首次 PTL(413) → reactive compact → 重试返回 stop 纯文本。 */
    private static LlmProviderFactory ptlOnceThenStopProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(inv -> {
            Consumer<Throwable> onErr = inv.getArgument(15);
            Runnable onComplete = inv.getArgument(16);
            if (calls.incrementAndGet() == 1) {
                onErr.accept(new LlmApiException(
                    413, Collections.emptyMap(),
                    "prompt is too long: 137500 tokens > 135000 maximum"));
            } else {
                Consumer<String> onChunk = inv.getArgument(9);
                Consumer<AssistantMessage> onMsg = inv.getArgument(10);
                onChunk.accept("recovered reply");
                if (onMsg != null) {
                    onMsg.accept(new AssistantMessage("recovered reply", "stop", List.of()));
                }
            }
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),any(), any());
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }

    private static List<ChatMessageDto> preCompactMessages() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            msgs.add(message("m" + i, Role.user, "content " + i, null, null));
        }
        msgs.add(new ChatMessageDto(
            "last-a", null, Role.assistant, "assistant", "final response", null, List.of(),
            FinishReason.stop, 30_000, 5_000,
            "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null,
            false, false, null));
        return msgs;
    }

    private static ChatMessageDto message(String id, Role role, String content,
                                          Integer inputTokens, Integer outputTokens) {
        return new ChatMessageDto(
            id, null, role, role.name(), content, null, List.of(),
            FinishReason.stop, inputTokens, outputTokens,
            "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(), null,
            false, false, null);
    }
}
