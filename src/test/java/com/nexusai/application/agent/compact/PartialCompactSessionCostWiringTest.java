package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.cost.CostTracker;
import com.nexusai.application.agent.cost.ModelCostCalculator;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.PartialCompactRequest;
import com.nexusai.model.session.dto.PartialCompactResponse;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [compact-cost · partial] partial 压缩摘要那次 LLM 调用的 usage 必须计入会话成本/用量合计。
 *
 * <h2>WHY（CLAUDE.md 规则九 · 测试验证意图）</h2>
 * partial 压缩（前端消息选择器 → 「压缩到此处 / 压缩此后」）会真发一次摘要 LLM 调用，usage 经
 * {@code PartialCompactConversation:469} 从 {@code SummaryResult.usage} 透传进
 * {@code CompactionResult.compactionUsage}。CC 在 {@code claude.ts:2361
 * costUSD += addToTotalSessionCost(costUSDForPart, usage, options.model)} 把它计入会话合计；
 * 本仓 auto / reactive / manual 三路均已接，<b>partial 此前完全没接</b> → 这次调用的 token
 * 不进 sessions.total_cost_yuan / model_usage_json。
 *
 * <h2>RED teeth（注掉接线 → 哪条断言红）</h2>
 * <ol>
 *   <li>删/注掉 {@code PartialCompactService.partialCompact} 里
 *       {@code LlmAgentLoop.accumulateCompactionSessionCost(...)} 一行 →
 *       {@link #partialCompactSuccess_addsCompactionCallToSessionCost} 的成本/桶/计价器捕获断言全红；</li>
 *   <li>把 {@code live} 换成新建的空 AgentState（不取 registry）→ 同上红（证明写的是会话 live state）；</li>
 *   <li>未注册会话（registry 为 null）→ {@link #unregisteredSession_noNpeNoCost}（选定语义：不猜、
 *       不重建 state → no-op，不 NPE）。</li>
 * </ol>
 */
@DisplayName("[compact-cost · partial] partial 压缩调用 usage 计入会话成本（CC claude.ts:2361）")
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
        RequestContext.clear();
        CompactProgressState.clear();
        CompactProgressState.clearAbort();
        CompactProgressState.removeSessionAbort(SESSION);
        // 静态 holder 复位（防跨用例串台）· setter 接受 null → 解注册
        PartialCompactConversation.setSessionAgentStateRegistry(null);
        PartialCompactConversation.setSettingsResolver(null);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1 · 成功路径：已注册（在跑）会话 → 压缩调用 usage 入会话合计
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("partial 成功（已注册会话）：会话成本/用量增加压缩那次调用的量 · CC claude.ts:2361")
    void partialCompactSuccess_addsCompactionCallToSessionCost() {
        AgentState live = liveState();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        registry.register(SESSION, live);
        PartialCompactService svc = serviceWithRegistry(fourMessages(), registry);

        PartialCompactResponse resp = svc.partialCompact(SESSION,
            new PartialCompactRequest("u1", PartialCompactRequest.Direction.FROM, null));

        assertThat(resp).as("partial 压缩必须成功（否则本测试空转）").isNotNull();
        assertThat(resp.messages()).isNotEmpty();

        // ① 成本：4400 元入会话合计（单价 1 元/输入侧 token）
        assertThat(live.sessionCostYuan())
            .as("partial 摘要调用的 usage 必须计入会话成本（CC claude.ts:2361）；漏接线 → 0.0")
            .isEqualTo(EXPECTED_COMPACT_COST);
        // ② input/output tokens
        assertThat(live.sessionInputTokens())
            .as("会话 input token 合计必须含压缩调用的 1000")
            .isEqualTo(C_INPUT);
        assertThat(live.sessionOutputTokens())
            .as("会话 output token 镜像字段必须含压缩调用的 200")
            .isEqualTo(C_OUTPUT);
        // ③ 按模型桶（sessions.model_usage_json 源）· 模型 = resolveCompactModel(sessionId)
        CostTracker.ModelUsage bucket = live.sessionModelUsage().get(P_MODEL);
        assertThat(bucket).as("model_usage_json 桶必须有压缩模型条目").isNotNull();
        assertThat(bucket.inputTokens()).isEqualTo(C_INPUT);
        assertThat(bucket.cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
        assertThat(bucket.cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
        assertThat(bucket.costUSD())
            .as("桶 costUSD = 压缩调用折算金额（CC addToTotalModelUsage cost 字段）")
            .isEqualTo(EXPECTED_COMPACT_COST);
        // ④ 模型口径：计价调用收到的是真正执行摘要调用的模型（live.currentModel 同源）
        assertThat(costCalls)
            .as("压缩调用必须经 ModelCostCalculator 计价，模型 = 真正执行压缩的模型")
            .anySatisfy(c -> {
                assertThat(c.model()).isEqualTo(P_MODEL);
                assertThat(c.usage().inputTokens()).isEqualTo(C_INPUT);
                assertThat(c.usage().outputTokens()).isEqualTo(C_OUTPUT);
                assertThat(c.usage().cacheReadInputTokens()).isEqualTo(C_CACHE_READ);
                assertThat(c.usage().cacheCreationInputTokens()).isEqualTo(C_CACHE_CREATE);
            });
        // ⑤ 回归锁：runUsage（complete.usage 源）不含压缩 —— 压缩是 side call
        assertThat(live.runUsage().inputTokens()).isZero();
        assertThat(live.runUsage().outputTokens()).isZero();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2 · 未注册会话（registry 为 null）：不 NPE、不臆造 state（选定语义 = no-op）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("未注册会话（live=null）：不 NPE，且不产生计价调用（选定语义 = 不猜、不重建 state）")
    void unregisteredSession_noNpeNoCost() {
        // 3 参便捷构造 = sessionAgentStateRegistry null（生产：会话未在跑循环 / REST 空闲触发）
        PartialCompactService svc = serviceWithRegistry(fourMessages(), null);

        assertThatCode(() -> svc.partialCompact(SESSION,
                new PartialCompactRequest("u1", PartialCompactRequest.Direction.FROM, null)))
            .as("live=null 必须安全（accumulateCompactionSessionCost 首行 null 守卫），不得 NPE")
            .doesNotThrowAnyException();
        assertThat(costCalls)
            .as("未注册会话无 AgentState 载体 → 本次压缩成本不入账（与修复前同值，非回归）；"
                + "不产生计价调用 = 明确 no-op 语义，而非把成本算到某个臆造对象上")
            .isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // 脚手架
    // ════════════════════════════════════════════════════════════════════

    /** 已注册（在跑）会话 AgentState：模型已冻结 → resolveCompactModel 取到压缩模型。 */
    private static AgentState liveState() {
        AgentState live = new AgentState("sys", SESSION, null);
        live.setCurrentModel(P_MODEL);
        return live;
    }

    /** 构造服务：mock MessageService/SessionService + SummaryResult 携带真实 usage。 */
    private PartialCompactService serviceWithRegistry(List<ChatMessageDto> sessionMessages,
                                                      SessionAgentStateRegistry registry) {
        MessageService messageService = mock(MessageService.class);
        SessionService sessionService = mock(SessionService.class);
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(messageService.listForResume(anyString())).thenReturn(sessionMessages);
        when(messageService.appendPostCompactMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary text",
                new CompactConversation.TokenUsage(C_INPUT, C_OUTPUT, C_CACHE_READ, C_CACHE_CREATE)));
        PartialCompactService svc = new PartialCompactService(
            messageService, sessionService, summary, registry, null, null);
        ReflectionTestUtils.setField(svc, "modelCostCalculator", recordingCalculator());
        return svc;
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
        when(calc.calculateCostYuan(anyString(), any(AgentUsage.class), anyBoolean())).thenAnswer(inv -> {
            String model = inv.getArgument(0);
            AgentUsage u = inv.getArgument(1);
            costCalls.add(new CostCall(model, u));
            double unit = P_MODEL.equals(model) ? 1.0 : 1000.0;
            long inputSide = u.inputTokens()
                + (u.cacheReadInputTokens() != null ? u.cacheReadInputTokens() : 0L)
                + (u.cacheCreationInputTokens() != null ? u.cacheCreationInputTokens() : 0L);
            return unit * inputSide;
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
