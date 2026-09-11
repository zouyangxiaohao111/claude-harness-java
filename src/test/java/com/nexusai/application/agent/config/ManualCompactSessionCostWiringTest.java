package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactWarningState;
import com.nexusai.application.agent.compact.PostCompactionState;
import com.nexusai.application.agent.compact.StreamCompactSummary;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [compact-cost · manual] manual {@code /compact} 那次 LLM 调用的 usage 必须计入会话成本/用量合计。
 *
 * <h2>WHY（CLAUDE.md 规则九 · 测试验证意图）</h2>
 * 压缩调用是 side call —— CC 在 {@code queryModel} 的 message_delta 分支把它计入会话合计
 * （{@code claude.ts:2361 costUSD += addToTotalSessionCost(costUSDForPart, usage, options.model)}
 * → {@code cost-tracker.ts:250-276 addToTotalModelUsage} → {@code state.ts:551-558
 * STATE.totalCostUSD += cost}）。auto / reactive 两路已在
 * {@code LlmAgentLoop:5361/6969} 接线（见 {@code CompactSessionCostWiringTest}），
 * <b>manual /compact 此前完全没有成本接线</b> → 用户手工压缩的那次调用 token 白花（不进
 * sessions.total_cost_yuan / model_usage_json）。
 *
 * <h2>RED teeth（注掉接线 → 哪条断言红）</h2>
 * <ol>
 *   <li>删/注掉 {@code ToolRegistrationConfig.handleCompactCommand} 里
 *       {@code LlmAgentLoop.accumulateCompactionSessionCost(...)} 一行 →
 *       {@link #manualCompactSuccess_addsCompactionCallToSessionCost} 的 sessionCostYuan /
 *       sessionInputTokens / model_usage_json 桶 / 计价器捕获断言全红（金额 0.0）；</li>
 *   <li>把模型形参换成 {@code null} 或会话主模型（而非 {@code resolveManualCompactModel(state)}）
 *       → 计价器捕获断言（model == COMPACT_MODEL）与金额断言红；</li>
 *   <li>把接线挪到 {@code catch} 分支（失败也计）→
 *       {@link #manualCompactFailure_doesNotAddCost} 红（失败路径金额非 0）。</li>
 * </ol>
 *
 * <p><b>隔离</b>：{@code CompactConversation.modelMapper/providerMapper} 是进程级静态槽，
 * 本用例 {@code @BeforeEach} 快照 / {@code @AfterEach} 还原（同
 * {@code CompactIdleRebuildModelWiringTest} 手法）。
 */
@DisplayName("[compact-cost · manual] /compact 压缩调用 usage 计入会话成本（CC claude.ts:2361）")
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
        RequestContext.clear();
        CompactWarningState.clearCompactWarningSuppression();
        PostCompactionState.clear(SESSION);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1 · 成功路径：压缩调用 usage 入会话合计（与 auto/reactive 同一通道）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/compact 成功：会话成本/用量增加压缩那次调用的量 · CC claude.ts:2361")
    void manualCompactSuccess_addsCompactionCallToSessionCost() {
        stubAnthropicMappers();
        AgentState live = liveState();
        SessionAgentStateRegistry registry = registryWith(live);

        ToolRegistrationConfig config = configWithCalculator();
        String out = invokeHandleCompact(config, registry, summaryReturningUsage());

        assertThat(out)
            .as("压缩必须成功（否则本测试空转）")
            .doesNotContain("压缩失败")
            .doesNotContain("压缩异常");

        // ① 成本：4400 元入会话合计（单价 1 元/输入侧 token）
        assertThat(live.sessionCostYuan())
            .as("manual /compact 压缩调用的 usage 必须计入会话成本（CC claude.ts:2361）；"
                + "漏接线 → 0.0")
            .isEqualTo(EXPECTED_COMPACT_COST);
        // ② input token 合计
        assertThat(live.sessionInputTokens())
            .as("会话 input token 合计必须含压缩调用的 1000")
            .isEqualTo(C_INPUT);
        // ③ output 镜像字段
        assertThat(live.sessionOutputTokens())
            .as("会话 output token 镜像字段必须含压缩调用的 200")
            .isEqualTo(C_OUTPUT);
        // ④ 按模型桶（sessions.model_usage_json 源）
        CostTracker.ModelUsage bucket = live.sessionModelUsage().get(COMPACT_MODEL);
        assertThat(bucket).as("model_usage_json 桶必须有压缩模型条目").isNotNull();
        assertThat(bucket.inputTokens()).isEqualTo(C_INPUT);
        assertThat(bucket.cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
        assertThat(bucket.cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
        assertThat(bucket.costUSD())
            .as("桶 costUSD = 压缩调用折算金额（CC addToTotalModelUsage cost 字段）")
            .isEqualTo(EXPECTED_COMPACT_COST);
        // ⑤ 模型口径：计价调用收到的是真正执行压缩的模型（resolveManualCompactModel 同源）
        assertThat(costCalls)
            .as("压缩调用必须经 ModelCostCalculator 计价，模型 = 真正执行压缩的模型")
            .anySatisfy(c -> {
                assertThat(c.model()).isEqualTo(COMPACT_MODEL);
                assertThat(c.usage().inputTokens()).isEqualTo(C_INPUT);
                assertThat(c.usage().outputTokens()).isEqualTo(C_OUTPUT);
                assertThat(c.usage().cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
                assertThat(c.usage().cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
            });
        // ⑥ 回归锁：runUsage（complete.usage 源）不含压缩 —— CC result.usage 只在主循环
        //    message_stop 累加，压缩是 side call
        assertThat(live.runUsage().inputTokens()).isZero();
        assertThat(live.runUsage().outputTokens()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2 · 失败路径：压缩没成功 → 不计（call 抛异常 → catch 分支，接线在 call 之后）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("/compact 失败：会话成本/用量不增加（接线只在成功路径 · CC 只在 message_delta 计）")
    void manualCompactFailure_doesNotAddCost() {
        stubAnthropicMappers();
        AgentState live = liveState();
        SessionAgentStateRegistry registry = registryWith(live);

        StreamCompactSummary failing = mock(StreamCompactSummary.class);
        when(failing.summarize(anyString(), anyList()))
            .thenThrow(new RuntimeException("boom: summary production failed"));

        ToolRegistrationConfig config = configWithCalculator();
        String out = invokeHandleCompact(config, registry, failing);

        assertThat(out)
            .as("失败必须被 CompactCommand.call 翻译为 IllegalArgumentException → handler catch 分支")
            .contains("压缩失败");
        assertThat(live.sessionCostYuan())
            .as("压缩失败（无成功 usage）→ 会话成本不得增加；接线挪到 catch 分支即红")
            .isZero();
        assertThat(live.sessionInputTokens()).isZero();
        assertThat(live.sessionOutputTokens()).isZero();
        assertThat(live.sessionModelUsage()).isEmpty();
        assertThat(costCalls).as("失败路径不得发生任何计价调用").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 脚手架
    // ════════════════════════════════════════════════════════════════════

    /** live（在运行）会话 AgentState：模型已冻结 → resolveManualCompactModel 取到压缩模型。 */
    private static AgentState liveState() {
        AgentState live = new AgentState("sys", SESSION, null);
        live.setCurrentModel(COMPACT_MODEL);
        live.appendMessage(msg("u1", Role.user, "first question"));
        live.appendMessage(msg("a1", Role.assistant, "first answer"));
        return live;
    }

    private static SessionAgentStateRegistry registryWith(AgentState live) {
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        registry.register(SESSION, live);
        return registry;
    }

    /** 摘要生产 mock：返回真实 usage（= compactionUsage 的原料）。 */
    private static StreamCompactSummary summaryReturningUsage() {
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary text",
                new CompactConversation.TokenUsage(C_INPUT, C_OUTPUT, C_CACHE_READ, C_CACHE_CREATE)));
        return summary;
    }

    private ToolRegistrationConfig configWithCalculator() {
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        ReflectionTestUtils.setField(config, "modelCostCalculator", recordingCalculator());
        return config;
    }

    /** 反射驱动私有 handleCompactCommand（参数序与生产 registerCompactSlashCommand lambda 一致）。 */
    private String invokeHandleCompact(ToolRegistrationConfig config,
                                       SessionAgentStateRegistry registry,
                                       StreamCompactSummary summary) {
        MessageService messageService = mock(MessageService.class);
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        RequestContext.set(SESSION, null);
        Object out = ReflectionTestUtils.invokeMethod(config, "handleCompactCommand",
            "", registry, null, summary, null, null, null, null, null, null, messageService);
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
        when(calc.contextWindowFor(anyString())).thenReturn(200_000);
        when(calc.maxOutputFor(anyString())).thenReturn(8_192L);
        when(calc.calculateCostYuan(anyString(), any(AgentUsage.class), anyBoolean())).thenAnswer(inv -> {
            String model = inv.getArgument(0);
            AgentUsage u = inv.getArgument(1);
            costCalls.add(new CostCall(model, u));
            double unit = COMPACT_MODEL.equals(model) ? 1.0 : 1000.0;
            long inputSide = u.inputTokens()
                + (u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0L)
                + (u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0L);
            return unit * inputSide;
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
