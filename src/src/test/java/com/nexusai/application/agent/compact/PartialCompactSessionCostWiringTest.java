package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.cost.SessionUsageLedger;
import com.nexusai.application.agent.cost.SessionUsageAccountingRegistry;
import com.nexusai.application.agent.cost.UsageBucket;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmCallScope;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.PartialCompactRequest;
import com.nexusai.model.session.dto.PartialCompactResponse;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [usage-gateway · partial] partial 压缩摘要那次 LLM 调用的 usage 必须经<b>请求网关</b>入会话账本。
 *
 * <h2>WHY 改造（原 [compact-cost] 通道已删）</h2>
 * 用户裁定：压缩的用量入账必须走统一流程，旧通道
 * {@code LlmAgentLoop.accumulateCompactionSessionCost} 必须删除（不允许两套）。partial 的摘要
 * 生产走 {@code PartialCompactService.buildContext} 的 summaryProducer →
 * {@link StreamCompactSummary#summarize} → {@code StreamCompactSummary.streamOnce} 内的请求网关
 * （{@code LlmInvocationGateway.streamWith}）→ sink → 结算 → 会话账本
 * （{@link SessionUsageLedger}），归属 {@code UsageCategory.COMPACT}。本类验证该新通道。
 *
 * <h2>RED teeth（哪条断言红）</h2>
 * <ol>
 *   <li>摘要不经网关（不盖 recorder / 不写 sink）→
 *       {@link #partialCompactSuccess_accountedViaGatewayLedger()} 的账本断言全红；</li>
 *   <li>未注册会话（无 live state）→ TUC 缺失 → 归属退化为 unknown(null)，用量记入
 *       {@code NO_SESSION_KEY} 槽（不 NPE、也不臆造会话）→
 *       {@link #unregisteredSession_recordedToNoSessionSlot()}；</li>
 * </ol>
 */
@DisplayName("[usage-gateway · partial] partial 压缩调用 usage 经请求网关入会话账本")
class PartialCompactSessionCostWiringTest {

    private static final String SESSION = "sess-partialcost";
    private static final String P_MODEL = "compact-model-partial";

    private static final int C_INPUT = 1_000;
    private static final int C_OUTPUT = 200;
    private static final int C_CACHE_READ = 3_000;
    private static final int C_CACHE_CREATE = 400;

    /** 单价 1 元/输入侧 token（output 不计）→ 1000+3000+400 = 4400。 */
    private static final double EXPECTED_COMPACT_COST = 1.0 * (C_INPUT + C_CACHE_READ + C_CACHE_CREATE);

    private final List<CostCall> costCalls = Collections.synchronizedList(new ArrayList<>());

    private record CostCall(String model, AgentUsage usage) {}

    @AfterEach
    void tearDown() {
        CompactProgressState.removeSessionAbort(SESSION);
        // 静态 holder 复位（防跨用例串台）· setter 接受 null → 解注册
        PartialCompactConversation.setSessionAgentStateRegistry(null);
        PartialCompactConversation.setSettingsResolver(null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1 · 成功路径：已注册（在跑）会话 → 压缩调用 usage 经网关入会话账本
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("partial 成功（已注册会话）：会话账本增加压缩那次调用的量（COMPACT 归属）")
    void partialCompactSuccess_accountedViaGatewayLedger() {
        AgentState live = liveState();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        registry.register(SESSION, live);
        SessionUsageAccountingRegistry usageLedgerRegistry = ledgerRegistry(recordingCalculator());
        PartialCompactService svc = serviceWith(registry, usageLedgerRegistry);

        PartialCompactResponse resp = svc.partialCompact(SESSION,
            new PartialCompactRequest("u1", PartialCompactRequest.Direction.FROM, null));

        assertThat(resp).as("partial 压缩必须成功（否则本测试空转）").isNotNull();
        assertThat(resp.messages()).isNotEmpty();

        SessionUsageLedger ledger = usageLedgerRegistry.forSession(SESSION);
        // ① 账本：1 次调用，四通道 token 入账
        assertThat(ledger.totalCalls())
            .as("partial 摘要调用必须经请求网关记入会话账本（旧通道已删，不允许两套）；漏接线 → 0")
            .isEqualTo(1L);
        UsageBucket bucket = ledger.bucketOf(SESSION);
        assertThat(bucket.inputTokens()).isEqualTo(C_INPUT);
        assertThat(bucket.outputTokens()).isEqualTo(C_OUTPUT);
        assertThat(bucket.cacheReadTokens()).isEqualTo(C_CACHE_READ);
        assertThat(bucket.cacheWriteTokens()).isEqualTo(C_CACHE_CREATE);
        assertThat(bucket.totalCostYuan()).isEqualTo(EXPECTED_COMPACT_COST);
        // ② 按模型 rollup（sessions.model_usage_json 源）· 模型 = summaryProducer 的 modelSupplier
        CostTracker.ModelUsage mu = ledger.modelUsage().get(P_MODEL);
        assertThat(mu).as("model_usage rollup 必须有压缩模型条目").isNotNull();
        assertThat(mu.inputTokens()).isEqualTo(C_INPUT);
        assertThat(mu.cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
        assertThat(mu.cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
        assertThat(mu.costUSD()).isEqualTo(EXPECTED_COMPACT_COST);
        // ③ 模型口径：账本计价拿到的是真正执行摘要调用的模型
        assertThat(costCalls)
            .as("压缩调用必须经 ModelCostCalculator 计价，模型 = 真正执行压缩的模型")
            .anySatisfy(c -> {
                assertThat(c.model()).isEqualTo(P_MODEL);
                assertThat(c.usage().inputTokens()).isEqualTo(C_INPUT);
                assertThat(c.usage().outputTokens()).isEqualTo(C_OUTPUT);
                assertThat(c.usage().cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
                assertThat(c.usage().cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
            });
        // ④ 回归锁：runUsage（complete.usage 源）不含压缩 —— 压缩是 side call
        assertThat(live.runUsage().inputTokens()).isZero();
        assertThat(live.runUsage().outputTokens()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2 · 未注册会话（无 live state）：不 NPE，用量记入无会话槽（不臆造会话）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("未注册会话：不 NPE，且归属退化为 unknown → 记入无会话槽（不臆造会话 id）")
    void unregisteredSession_recordedToNoSessionSlot() {
        SessionUsageAccountingRegistry usageLedgerRegistry = ledgerRegistry(recordingCalculator());
        PartialCompactService svc = serviceWith(null, usageLedgerRegistry);

        assertThatCode(() -> svc.partialCompact(SESSION,
                new PartialCompactRequest("u1", PartialCompactRequest.Direction.FROM, null)))
            .as("无注册 AgentState（TUC 缺失）必须安全，不得 NPE")
            .doesNotThrowAnyException();
        // 用量不丢：归属退化为 unknown(null) → 网关记入 NO_SESSION 槽（会话账本不受污染）
        assertThat(usageLedgerRegistry.forSession(SESSION).totalCalls())
            .as("无会话上下文 → 不得把这次调用臆造到某个会话节点上")
            .isZero();
        assertThat(usageLedgerRegistry.forSession(null).totalCalls())
            .as("无会话上下文的调用仍统计（归入 NO_SESSION 槽，不丢弃）")
            .isEqualTo(1L);
    }

    // ════════════════════════════════════════════════════════════════════
    // 脚手架
    // ════════════════════════════════════════════════════════════════════

    /** 已注册（在跑）会话 AgentState：模型已冻结 + 隔离 TUC（sessionId 直键，压缩归属取此处）。 */
    private static AgentState liveState() {
        AgentState live = new AgentState("sys", SESSION, null);
        live.setCurrentModel(P_MODEL);
        live.setCurrentToolUseContext(ToolUseContext.of(UUID.randomUUID(), SESSION));
        return live;
    }

    /** 构造服务：mock MessageService/SessionService + 真实摘要生产（StreamCompactSummary → 网关）。 */
    private PartialCompactService serviceWith(SessionAgentStateRegistry stateRegistry,
                                             SessionUsageAccountingRegistry usageLedgerRegistry) {
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        when(messageService.listForResume(anyString())).thenReturn(fourMessages());
        when(messageService.appendPostCompactMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        StreamCompactSummary summary = summaryProvider(P_MODEL, usageLedgerRegistry);
        return new PartialCompactService(
            messageService, sessionService, summary, stateRegistry, null, null);
    }

    /** 真实 StreamCompactSummary：provider 桩写网关 sink（UPSTREAM），registry → 会话账本。 */
    private static StreamCompactSummary summaryProvider(String model, SessionUsageAccountingRegistry registry) {
        LlmProvider provider = usageProvider();
        StreamCompactSummary summary = new StreamCompactSummary(
            () -> provider, () -> model, () -> ProviderConfig.empty());
        summary.setUsageLedgerRegistry(registry);
        return summary;
    }

    /** provider 桩：返回 "summary text" + 真实 usage，并把 usage 写入网关 sink。 */
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
            LlmCallScope.recordSettled(scope, usage);
            onComplete.run();
            return null;
        }).when(provider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        return provider;
    }

    /** 会话账本注册表 + 记录型计费器（ReflectionTestUtils 注入 · 生产由 Spring 注入）。 */
    private SessionUsageAccountingRegistry ledgerRegistry(ModelCostCalculator calc) {
        SessionUsageAccountingRegistry registry = new SessionUsageAccountingRegistry();
        ReflectionTestUtils.setField(registry, "costCalculator", calc);
        return registry;
    }

    /**
     * 记录型计费器：单价按模型分派（P_MODEL=1.0 / 其他=1000.0）× 输入侧 token 之和
     * （output 不计 → 精确断言压缩贡献）。
     */
    private ModelCostCalculator recordingCalculator() {
        ModelCostCalculator calc = mock(ModelCostCalculator.class);
        when(calc.isPeakHour()).thenReturn(false);
        when(calc.contextWindowFor(anyString())).thenReturn(200_000);
        when(calc.maxOutputFor(anyString())).thenReturn(8_192L);
        // V101：账本计价点 = price(...)（总额 = breakdown.totalYuan()）→ 替身的金额放 inputYuan，其余分项 0。
        when(calc.price(anyString(), any(AgentUsage.class), anyBoolean())).thenAnswer(inv -> {
            String model = inv.getArgument(0);
            AgentUsage u = inv.getArgument(1);
            costCalls.add(new CostCall(model, u));
            double unit = P_MODEL.equals(model) ? 1.0 : 1000.0;
            long inputSide = u.inputTokens()
                + (u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0L)
                + (u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0L);
            double amount = unit * inputSide;
            return new ModelCostCalculator.CostBreakdown(amount, 0.0, 0.0, 0.0, amount, amount);
        });
        return calc;
    }

    private static ChatMessageDto msg(String id, Role role) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            "content-" + id, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** 无 boundary 会话：[u0, a0, u1, a1]（pivot = u1）。 */
    private static List<ChatMessageDto> fourMessages() {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(msg("u0", Role.user));
        list.add(msg("a0", Role.assistant));
        list.add(msg("u1", Role.user));
        list.add(msg("a1", Role.assistant));
        return list;
    }
}
