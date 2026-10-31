package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactWarningState;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.compact.StreamCompactSummary;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.cost.SessionUsageLedger;
import com.nexusai.application.agent.cost.SessionUsageAccountingRegistry;
import com.nexusai.application.agent.cost.UsageBucket;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmCallScope;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [usage-gateway · manual] manual {@code /compact} 那次 LLM 调用的 usage 必须经<b>请求网关</b>入会话账本。
 *
 * <h2>WHY 改造（原 [compact-cost] 通道已删）</h2>
 * 用户裁定：压缩的用量入账必须走统一流程，旧通道
 * {@code LlmAgentLoop.accumulateCompactionSessionCost} 必须删除（不允许两套）。manual {@code /compact}
 * 的摘要生产走 {@code handleCompactCommand} → {@code CompactCommand.call} → compactConversation 的
 * summaryProducer = {@link StreamCompactSummary} → 请求网关
 * （{@code LlmInvocationGateway.streamWith}）→ sink → 结算 → 会话账本
 * （{@link SessionUsageLedger}），归属 {@code UsageCategory.COMPACT}。本类验证该新通道。
 *
 * <h2>RED teeth（哪条断言红）</h2>
 * <ol>
 *   <li>摘要不经网关（不盖 recorder / 不写 sink）→
 *       {@link #manualCompactSuccess_accountedViaGatewayLedger()} 的账本断言全红；</li>
 *   <li>把模型口径取错（不传 {@code modelSupplier} 的真实模型）→ 计价断言与金额断言红；</li>
 *   <li>失败路径也记账 → {@link #manualCompactFailure_doesNotAddCost()} 红。</li>
 * </ol>
 *
 * <p><b>隔离</b>：{@code CompactConversation.modelMapper/providerMapper} 是进程级静态槽，
 * 本用例 {@code @BeforeEach} 快照 / {@code @AfterEach} 还原（同
 * {@code CompactIdleRebuildModelWiringTest} 手法）。
 */
@DisplayName("[usage-gateway · manual] /compact 压缩调用 usage 经请求网关入会话账本")
class ManualCompactSessionCostWiringTest {

    private static final String SESSION = "sess-manualcost";
    private static final String COMPACT_MODEL = "compact-model-manual";

    private static final int C_INPUT = 1_000;
    private static final int C_OUTPUT = 200;
    private static final int C_CACHE_READ = 3_000;
    private static final int C_CACHE_CREATE = 400;

    /** 单价 1 元/输入侧 token（output 不计）→ 1000+3000+400 = 4400。 */
    private static final double EXPECTED_COMPACT_COST = 1.0 * (C_INPUT + C_CACHE_READ + C_CACHE_CREATE);

    private Object savedModelMapper;
    private Object savedProviderMapper;
    private List<CostCall> costCalls;

    private record CostCall(String model, AgentUsage usage) {}

    @BeforeEach
    void setUp() throws Exception {
        savedModelMapper = readStaticMapper("modelMapper");
        savedProviderMapper = readStaticMapper("providerMapper");
        costCalls = Collections.synchronizedList(new ArrayList<>());
    }

    @AfterEach
    void tearDown() throws Exception {
        writeStaticMapper("modelMapper", savedModelMapper);
        writeStaticMapper("providerMapper", savedProviderMapper);
        CompactWarningState.clearCompactWarningSuppression(null, null);
        PostCompactionState.clear(SESSION);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1 · 成功路径：压缩调用 usage 经网关入会话账本
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/compact 成功：会话账本增加压缩那次调用的量（COMPACT 归属）")
    void manualCompactSuccess_accountedViaGatewayLedger() {
        stubAnthropicMappers();
        AgentState live = liveState();
        SessionAgentStateRegistry stateRegistry = stateRegistryWith(live);
        SessionUsageAccountingRegistry usageLedgerRegistry = ledgerRegistry(recordingCalculator());

        ToolRegistrationConfig config = new ToolRegistrationConfig();
        String out = invokeHandleCompact(config, stateRegistry,
            summaryProvider(COMPACT_MODEL, usageLedgerRegistry));

        assertThat(out)
            .as("压缩必须成功（否则本测试空转）")
            .doesNotContain("压缩失败")
            .doesNotContain("压缩异常");

        SessionUsageLedger ledger = usageLedgerRegistry.forSession(SESSION);
        // ① 账本：1 次调用，四通道 token 入账
        assertThat(ledger.totalCalls())
            .as("manual /compact 摘要调用必须经请求网关记入会话账本（旧通道已删）；漏接线 → 0")
            .isEqualTo(1L);
        UsageBucket bucket = ledger.bucketOf(SESSION);
        assertThat(bucket.inputTokens()).isEqualTo(C_INPUT);
        assertThat(bucket.outputTokens()).isEqualTo(C_OUTPUT);
        assertThat(bucket.cacheReadTokens()).isEqualTo(C_CACHE_READ);
        assertThat(bucket.cacheWriteTokens()).isEqualTo(C_CACHE_CREATE);
        assertThat(bucket.totalCostYuan()).isEqualTo(EXPECTED_COMPACT_COST);
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
        // ④ 回归锁：runUsage（complete.usage 源）不含压缩 —— 压缩是 side call
        assertThat(live.runUsage().inputTokens()).isZero();
        assertThat(live.runUsage().outputTokens()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2 · 失败路径：压缩没成功 → 不记账（网关只在成功调用结算后落账）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/compact 失败：会话账本不增加（无成功调用 → 无记账）")
    void manualCompactFailure_doesNotAddCost() {
        stubAnthropicMappers();
        AgentState live = liveState();
        SessionAgentStateRegistry stateRegistry = stateRegistryWith(live);
        SessionUsageAccountingRegistry usageLedgerRegistry = ledgerRegistry(recordingCalculator());

        StreamCompactSummary failing = mock(StreamCompactSummary.class);
        when(failing.summarize(anyString(), anyList(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new RuntimeException("boom: summary production failed"));

        ToolRegistrationConfig config = new ToolRegistrationConfig();
        String out = invokeHandleCompact(config, stateRegistry, failing);

        assertThat(out)
            .as("失败必须被 CompactCommand.call 翻译为 IllegalArgumentException → handler catch 分支")
            .contains("压缩失败");
        assertThat(usageLedgerRegistry.forSession(SESSION).totalCalls())
            .as("压缩失败（无成功调用）→ 会话账本不得增加")
            .isZero();
        assertThat(costCalls).as("失败路径不得发生任何计价调用").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 脚手架
    // ════════════════════════════════════════════════════════════════════

    /** live（在运行）会话 AgentState：模型已冻结 + 隔离 TUC（sessionId 直键，压缩归属取此处）。 */
    private static AgentState liveState() {
        AgentState live = new AgentState("sys", SESSION, null);
        live.setCurrentModel(COMPACT_MODEL);
        live.setCurrentToolUseContext(ToolUseContext.of(UUID.randomUUID(), SESSION));
        live.appendMessage(msg("u1", Role.user, "first question"));
        live.appendMessage(msg("a1", Role.assistant, "first answer"));
        return live;
    }

    private static SessionAgentStateRegistry stateRegistryWith(AgentState live) {
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        registry.register(SESSION, live);
        return registry;
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

    /** 反射驱动私有 handleCompactCommand（参数序与生产 registerCompactSlashCommand lambda 一致）。 */
    private String invokeHandleCompact(ToolRegistrationConfig config,
                                       SessionAgentStateRegistry registry,
                                       StreamCompactSummary summary) {
        MessageService messageService = mock(MessageService.class);
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        // [批 3c] 参数序与生产 registerCompactSlashCommand lambda 一致：
        //   (args, sessionId, inFlightUserMessageId, sessionRegistry, ...) —— 会话标识显式传参
        Object out = ReflectionTestUtils.invokeMethod(config, "handleCompactCommand",
            "", SESSION, null, registry, null, summary, null, null, null, null, null, null, messageService);
        assertThat(out).isInstanceOf(String.class);
        return (String) out;
    }

    /**
     * 记录型计费器：单价按模型分派（COMPACT_MODEL=1.0 / 其他=1000.0）× 输入侧 token 之和
     * （output 不计 → 精确断言压缩贡献）。
     */
    private ModelCostCalculator recordingCalculator() {
        ModelCostCalculator calc = mock(ModelCostCalculator.class);
        when(calc.isPeakHour()).thenReturn(false);
        // [2026-10-29] 账本带 providerType 调用 → 2 参重载（providerType 可 null，用 any() 匹配）
        when(calc.contextWindowFor(anyString(), any())).thenReturn(200_000);
        when(calc.maxOutputFor(anyString(), any())).thenReturn(8_192L);
        // V101：账本计价点 = price(...)（总额 = breakdown.totalYuan()）→ 替身的金额放 inputYuan，其余分项 0。
        // [2026-10-29] 账本计价点带 providerType → 4 参重载（providerType 可 null）
        when(calc.price(anyString(), any(AgentUsage.class), anyBoolean(), any())).thenAnswer(inv -> {
            String model = inv.getArgument(0);
            AgentUsage u = inv.getArgument(1);
            costCalls.add(new CostCall(model, u));
            double unit = COMPACT_MODEL.equals(model) ? 1.0 : 1000.0;
            long inputSide = u.inputTokens()
                + (u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0L)
                + (u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0L);
            double amount = unit * inputSide;
            return new ModelCostCalculator.CostBreakdown(amount, 0.0, 0.0, 0.0, amount, amount);
        });
        return calc;
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    private static void stubAnthropicMappers() {
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setType("anthropic");
        provider.setEnabled(true);
        ModelRecord model = new ModelRecord();
        model.setId("m1");
        model.setProviderId("p1");
        model.setName(COMPACT_MODEL);
        model.setEnabled(true);
        when(mm.selectOneByQuery(any())).thenReturn(model);
        when(pm.selectOneByQuery(any())).thenReturn(provider);
        when(pm.selectOneById(any())).thenReturn(provider);
        CompactConversation.setMappers(mm, pm);
    }

    private static Object readStaticMapper(String field) throws Exception {
        Field f = CompactConversation.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void writeStaticMapper(String field, Object value) throws Exception {
        Field f = CompactConversation.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }
}
