package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.ReactiveCompactor;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmApiException;
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
 * [compact-cost] 压缩那次 LLM 调用的 usage 必须计入会话成本/用量合计（CC claude.ts:2361）。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：压缩调用是 side call —— CC 在
 * {@code queryModel} 的 message_delta 分支把它计入会话合计
 * （{@code claude.ts:2361 costUSD += addToTotalSessionCost(costUSDForPart, usage, options.model)}
 * → {@code cost-tracker.ts:250-276 addToTotalModelUsage} → {@code state.ts:551-558
 * STATE.totalCostUSD += cost}），两条压缩路径（fork {@code forkedAgent.ts:564} / 流式回落
 * {@code compact.ts:1331}）都命中。Java 端此前 {@code compactionUsage} 只被两个遥测点消费
 * （{@code emitTenguCompactTelemetry} / {@code emitAutoCompactSucceededTelemetry}），
 * <b>没有任何成本接线</b> → 真机漏算（sess-c72a825a auto compact preTokens≈46 万未进
 * sessions.total_cost_yuan / model_usage_json）。
 *
 * <p><b>RED tooth（删掉接线 → 本类哪条断言红）</b>：
 * <ul>
 *   <li>{@code LlmAgentLoop} auto 成功分支的 {@code accumulateCompactionSessionCost(...)} 调用
 *       → {@link #autoCompactSuccess_addsCompactionCallToSessionCost()} 成本/桶/捕获断言全红；</li>
 *   <li>reactive 成功分支的同款调用 → {@link #reactiveCompactSuccess_addsCompactionCallToSessionCost()} 红；</li>
 *   <li>模型口径改错（传 params.modelName() / 会话主模型 / null）→
 *       {@link #compactionCost_followsTheModelThatRanTheCompactionCall()} 红（金额按模型单价分派）；</li>
 *   <li>把压缩 usage 塞进 runUsage → {@link #autoCompactSuccess_addsCompactionCallToSessionCost()}
 *       的 {@code runUsage()} 零值断言红（与 CC result.usage 不含 side call 的边界）。</li>
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
    // 1. auto 路径（LlmAgentLoop 压缩成功分支 · emitAutoCompactSucceededTelemetry 旁）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("auto 压缩成功：压缩调用 usage 进会话成本/用量合计（同一通道）· 不进 runUsage · CC claude.ts:2361")
    void autoCompactSuccess_addsCompactionCallToSessionCost() {
        AgentState state = newState();
        // 注：不带 assistant usage 的消息集（AutoCompactor 对「含真实 assistant usage」的会话走
        // usage-walk 阈值，本测试用注入 tokenCounter=200000 的估算路径触发自动压缩）。
        state.replaceMessages(List.of(
            message("m1", Role.user, "question-1", null, null),
            message("m2", Role.user, "question-2", null, null)));

        AgentLoopContext ctx = agentLoopContext(plainReplyProvider(), null, recordingCalculator());
        LlmAgentLoop.queryLoop(params(state, COMPACT_MODEL, ctx), state, new ArrayList<>(),
            autoCompactor());

        // ① 成本：压缩调用 4400 元入会话合计（单价 1 元/输入侧 token）
        assertThat(state.sessionCostYuan())
            .as("压缩调用的 usage 必须计入会话成本（CC claude.ts:2361 addToTotalSessionCost）；"
                + "漏接线 → 0.0")
            .isEqualTo(EXPECTED_COMPACT_COST);
        // ② 输入 token 合计
        assertThat(state.sessionInputTokens())
            .as("会话 input token 合计必须含压缩调用的 1000（主循环文本回合估算 input=0）")
            .isEqualTo(C_INPUT);
        // ③ 按模型桶（sessions.model_usage_json 源）
        CostTracker.ModelUsage bucket = state.sessionModelUsage().get(COMPACT_MODEL);
        assertThat(bucket).as("model_usage_json 桶必须有压缩模型条目").isNotNull();
        assertThat(bucket.inputTokens()).isEqualTo(C_INPUT);
        assertThat(bucket.cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
        assertThat(bucket.cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
        assertThat(bucket.outputTokens())
            .as("桶 output = 压缩调用 200 + 主循环文本回合估算（≥200）")
            .isGreaterThanOrEqualTo(C_OUTPUT);
        assertThat(bucket.costUSD())
            .as("桶 costUSD 必须为压缩调用折算金额（CC addToTotalModelUsage cost 字段）")
            .isEqualTo(EXPECTED_COMPACT_COST);
        // ④ 模型口径：计价调用收到的是真正执行压缩的模型
        assertThat(costCalls)
            .as("压缩调用必须经 ModelCostCalculator 计价，模型 = 真正执行压缩的模型")
            .anySatisfy(c -> {
                assertThat(c.model()).isEqualTo(COMPACT_MODEL);
                assertThat(c.usage().inputTokens()).isEqualTo(C_INPUT);
                assertThat(c.usage().outputTokens()).isEqualTo(C_OUTPUT);
                assertThat(c.usage().cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
                assertThat(c.usage().cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
            });
        // ⑤ 回归锁：runUsage（complete.usage 源）不含压缩 —— CC result.usage 只在主循环
        //    message_stop 累加，压缩是 side call（塞进去反而不对齐 CC）
        assertThat(state.runUsage().inputTokens()).isZero();
        assertThat(state.runUsage().outputTokens()).isZero();
        assertThat(state.runUsage().cacheReadInputTokens()).isZero();
        assertThat(state.runUsage().cacheCreationInputTokens()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. reactive 路径（LlmAgentLoop reactive 成功分支）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("reactive 压缩成功：压缩调用 usage 同样进会话成本合计（与 auto 同一通道）· CC query.ts:1134")
    void reactiveCompactSuccess_addsCompactionCallToSessionCost() {
        AgentState state = newState();
        preCompactMessages().forEach(state::appendMessage);
        int original = state.rawMessages().size();

        ReactiveCompactor rc = new ReactiveCompactor(
            new TokenEstimator()::estimateMessageTokens,
            (prompt, msgs) -> new CompactConversation.SummaryResult("reactive summary", compactionUsage()));
        rc.setEnabled(true);
        AgentLoopContext ctx = agentLoopContext(ptlOnceThenStopProvider(), rc, recordingCalculator());

        LlmAgentLoop.queryLoop(reactiveParams(state, COMPACT_MODEL, ctx), state, new ArrayList<>());

        // 前置：reactive compact 真实发生（消息被压缩）——否则本测试空转
        assertThat(state.rawMessages().size())
            .as("PTL 必须走 reactive compact（消息数下降）· CC query.ts:1138")
            .isLessThan(original);

        assertThat(state.sessionCostYuan())
            .as("reactive 压缩调用的 usage 必须计入会话成本（CC claude.ts:2361 命中两条压缩路径）；"
                + "漏接线 → 0.0")
            .isEqualTo(EXPECTED_COMPACT_COST);
        assertThat(state.sessionInputTokens()).isEqualTo(C_INPUT);
        CostTracker.ModelUsage bucket = state.sessionModelUsage().get(COMPACT_MODEL);
        assertThat(bucket).isNotNull();
        assertThat(bucket.inputTokens()).isEqualTo(C_INPUT);
        assertThat(bucket.cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
        assertThat(bucket.cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
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
    @DisplayName("模型口径：金额按真正执行压缩的模型算 —— 换模型金额随之变（取错模型即算错）")
    void compactionCost_followsTheModelThatRanTheCompactionCall() {
        // ① resolveModel()=COMPACT_MODEL（单价 1 元/输入侧 token）→ 4400
        AgentState a = newState();
        a.replaceMessages(List.of(message("m1", Role.user, "q", null, null)));
        AgentLoopContext ctxA = agentLoopContext(plainReplyProvider(), null, recordingCalculator());
        LlmAgentLoop.queryLoop(params(a, COMPACT_MODEL, ctxA), a, new ArrayList<>(), autoCompactor());
        assertThat(a.sessionCostYuan())
            .as("模型 = compact-model-a（单价 1）→ 4400")
            .isEqualTo(EXPECTED_COMPACT_COST);
        assertThat(a.sessionModelUsage()).containsKey(COMPACT_MODEL);
        assertThat(a.sessionModelUsage()).doesNotContainKey(TRAP_MODEL);

        // ② resolveModel()=TRAP_MODEL（单价 1000 元/输入侧 token）→ 放大 1000 倍
        List<CostCall> callsB = new ArrayList<>(costCalls);
        AgentState b = newState();
        b.replaceMessages(List.of(message("m1", Role.user, "q", null, null)));
        AgentLoopContext ctxB = agentLoopContext(plainReplyProvider(), null, recordingCalculator(callsB));
        LlmAgentLoop.queryLoop(params(b, TRAP_MODEL, ctxB), b, new ArrayList<>(), autoCompactor());
        assertThat(b.sessionCostYuan())
            .as("模型换成高单价的 declared-model-expensive → 金额必须随之放大（证明模型真实参与计价，"
                + "而非写死/取会话主模型）")
            .isEqualTo(EXPECTED_COMPACT_COST * 1000.0);
        assertThat(callsB)
            .as("压缩调用的计价模型必须 = 本次压缩调用实际使用的模型（TRAP_MODEL）")
            .anySatisfy(c -> {
                assertThat(c.model()).isEqualTo(TRAP_MODEL);
                assertThat(c.usage().inputTokens()).isEqualTo(C_INPUT);
            });
        // 分桶各归各（model_usage_json）
        assertThat(b.sessionModelUsage()).containsKey(TRAP_MODEL);
        assertThat(b.sessionModelUsage()).doesNotContainKey(COMPACT_MODEL);
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 重复压缩：每次都计（不漏计、不重复计）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("重复压缩：两次压缩各自计入（累加不覆盖、不漏计）")
    void repeatedCompaction_countsEachTime() {
        AgentState state = newState();
        state.replaceMessages(List.of(message("m1", Role.user, "q", null, null)));

        AgentLoopContext ctx = agentLoopContext(plainReplyProvider(), null, recordingCalculator());
        AutoCompactor autoCompactor = autoCompactor();

        LlmAgentLoop.queryLoop(params(state, COMPACT_MODEL, ctx), state, new ArrayList<>(), autoCompactor);
        assertThat(state.sessionCostYuan())
            .as("第 1 次压缩后 = 4400")
            .isEqualTo(EXPECTED_COMPACT_COST);
        assertThat(state.sessionInputTokens()).isEqualTo(C_INPUT);

        // 第二次压缩：重置为普通消息集（压缩后的 boundary/summary 携带 usage → 会走 usage-walk
        // 阈值判定，与本次断言的「压缩调用触发」无关），同一 AgentState 继续累计。
        state.replaceMessages(List.of(message("m9", Role.user, "second-round", null, null)));
        LlmAgentLoop.queryLoop(params(state, COMPACT_MODEL, ctx), state, new ArrayList<>(), autoCompactor);

        assertThat(state.sessionCostYuan())
            .as("第 2 次压缩必须再计一次（成本累加 8800，既不漏计也不覆盖）")
            .isEqualTo(2 * EXPECTED_COMPACT_COST);
        assertThat(state.sessionInputTokens())
            .as("两次压缩 input token 各计一次 = 2000")
            .isEqualTo(2L * C_INPUT);
        CostTracker.ModelUsage bucket = state.sessionModelUsage().get(COMPACT_MODEL);
        assertThat(bucket).isNotNull();
        assertThat(bucket.inputTokens())
            .as("桶内压缩输入 token 累加（非覆盖）= 2000")
            .isEqualTo(2L * C_INPUT);
        assertThat(bucket.cacheReadInputTokens()).isEqualTo(2L * C_CACHE_READ);
        assertThat(bucket.costUSD())
            .as("桶 cost 累加 = 8800")
            .isEqualTo(2 * EXPECTED_COMPACT_COST);
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** 压缩调用 usage（SummaryResult.usage → CompactionResult.compactionUsage 的原料）。 */
    private static CompactConversation.TokenUsage compactionUsage() {
        return new CompactConversation.TokenUsage(C_INPUT, C_OUTPUT, C_CACHE_READ, C_CACHE_CREATE);
    }

    /**
     * token 计数恒 2,000,000 → 必触发 auto compact（阈值随窗口解析波动：默认 1M 窗口下
     * threshold≈978,576；注入值取足够大以对所有窗口解析结果都越阈值，避免测试受
     * CompactThresholdSystem 窗口默认值影响）。
     */
    private static AutoCompactor autoCompactor() {
        return new AutoCompactor(msgs -> 2_000_000,
            (p, m) -> new CompactConversation.SummaryResult("<summary>ok</summary>", compactionUsage()));
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
        Answer<Double> answer = inv -> {
            String model = inv.getArgument(0);
            AgentUsage u = inv.getArgument(1);
            sink.add(new CostCall(model, u));
            double unit = COMPACT_MODEL.equals(model) ? 1.0 : 1000.0;
            long inputSide = u.inputTokens()
                + (u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0L)
                + (u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0L);
            return unit * inputSide;
        };
        when(calc.calculateCostYuan(anyString(), any(AgentUsage.class), anyBoolean())).thenAnswer(answer);
        return calc;
    }

    private static AgentState newState() {
        return new AgentState("sys", "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
    }

    private static QueryParams params(AgentState state, String resolvedModel, AgentLoopContext ctx) {
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8)),
            QuerySource.USER, TRAP_MODEL, null,
            null, null, null, null,
            deps(resolvedModel, ctx), ProviderConfig.empty());
    }

    /** reactive 场景：60 条历史 + 末位 assistant 带 usage（触发 PTL → reactive compact）。 */
    private static QueryParams reactiveParams(AgentState state, String resolvedModel, AgentLoopContext ctx) {
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8)),
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

    /** 构造 36 组件 ctx（第 36 位 = modelCostCalculator，本批新接线依赖它的计价分派）。 */
    private static AgentLoopContext agentLoopContext(LlmProviderFactory factory,
                                                     ReactiveCompactor rc, ModelCostCalculator calc) {
        return new AgentLoopContext(
            null, null, null, null, null, null, null, null, null, null,   // 1-10
            factory, null, null, null, null, null, null, null, null,      // 11-19
            rc != null ? REACTIVE_FLAGS : FeatureFlags.ALL_DISABLED,      // 20 featureFlags
            rc, null, null, null, null,                                   // 21-25
            null, null, null, null, null,                                 // 26-30
            null, null, null, null, null,                                 // 31-35
            calc);                                                        // 36 modelCostCalculator
    }

    // ── provider 桩 ──

    /** 单次调用返回 stop 纯文本（auto 路径：无 PTL，正常收尾）。 */
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
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
