package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.CompactSettingsResolver;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * snip nudge 门 4「上下文剩余百分比」端到端测试（snip-nudge-percent 2026-09-13）。
 *
 * <p>WHY（CLAUDE.md 规则 9）：判据从「模型可见消息条数 ≥ 窗口自适应档位」换成「上下文剩余百分比 ≤ 阈值」。
 * 本类的两条对照用例（900 条/剩余 80% 不注入 · 10 条/剩余 20% 注入）是**语义迁移的定点证据** ——
 * 它们在旧判据下的结论恰好相反，两条同时通过即证明「消息条数已完全不参与判据」。
 *
 * <p>阈值来源：本类大多用例**不注入 settingsResolver**（走 3 参 queryLoop，resolver 恒 null）→ 门 4 阈值
 * 取默认 30；门 1 取 {@code ctx.featureFlags().historySnip()}（由 snipOnFlags() 置 true）。仅
 * {@code dbThresholdOverridesDefault} 需要 resolver，故那一条改走 11 参 queryLoop。
 */
class LlmAgentLoopNudgePercentGateTest {

    /** 所有用例统一的窗口：100_000。 */
    private static final long WINDOW = 100_000L;

    // ══════════════ 断言辅助 ══════════════

    /**
     * nudge 注入文本 = {@code "<system-reminder>\n" + SNIP_NUDGE_TEXT + "\n</system-reminder>"}。
     * ⚠️ 绝不能写成 {@code SNIP_NUDGE_TEXT.equals(m.content())} —— 那样**恒假**（真实内容带包裹），
     * 是零鉴别力断言（既有测试踩过，本仓已登记）。
     */
    private static boolean isSnipNudge(ChatMessageDto m) {
        return m.isMeta() && Role.user == m.role()
            && ("<system-reminder>\n" + com.nexusai.application.agent.compact.SnipCompactor.SNIP_NUDGE_TEXT
                + "\n</system-reminder>").equals(m.content());
    }

    private static void assertNudged(List<ChatMessageDto> sent, String why) {
        assertThat(sent.stream().anyMatch(LlmAgentLoopNudgePercentGateTest::isSnipNudge)).as(why).isTrue();
    }

    private static void assertNotNudged(List<ChatMessageDto> sent, String why) {
        assertThat(sent.stream().anyMatch(LlmAgentLoopNudgePercentGateTest::isSnipNudge)).as(why).isFalse();
    }

    /** 跑一轮，把「最近一次 provider.stream 收到的 history」交给 check。 */
    private static void driveAndCapture(List<ChatMessageDto> messages, long window,
                                        Consumer<List<ChatMessageDto>> check) {
        AgentState state = new AgentState("sys",
            "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : messages) {
            state.appendMessage(m);
        }
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            null, factory, null, null, beansForWindow(window), snipOnFlags());
        LoopResult result = drive(ctx, state);

        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(histories).as("LLM 至少被调用一次（history 被捕获）").isNotEmpty();
        check.accept(histories.get(histories.size() - 1));
    }

    // ══════════════ 用例 ══════════════

    @Test
    @DisplayName("语义迁移定点 A: 900 条消息但剩余 80% → 不注入（旧判据下 900≥900 会注入）")
    void messageCountIrrelevant_skipsWhenRemainingHigh() {
        List<ChatMessageDto> msgs = new ArrayList<>(largeMessages(900));
        msgs.add(hydratedAssistant("a1", 20_000, 100));   // used 20000 / window 100000 → 剩余 80%
        driveAndCapture(msgs, WINDOW, sent -> assertNotNudged(sent,
            "剩余 80% > 阈值 30% → 不注入；消息 900 条不再参与判据"));
    }

    @Test
    @DisplayName("语义迁移定点 B: 10 条消息但剩余 20% → 注入（旧判据下 10<30 不注入）")
    void shortConversationButLowRemaining_injects() {
        List<ChatMessageDto> msgs = new ArrayList<>(largeMessages(10));
        msgs.add(hydratedAssistant("a1", 80_000, 100));   // → 剩余 20%
        driveAndCapture(msgs, WINDOW, sent -> assertNudged(sent,
            "剩余 20% ≤ 阈值 30% → 必须注入；短会话不豁免"));
    }

    @Test
    @DisplayName("边界: 剩余恰好 30% → 注入（判据用 <=，含等号）")
    void remainingExactlyAtThreshold_injects() {
        // ⚠️ 条数必须 < 30：否则旧判据（消息条数 ≥ 30）也会注入 → 本用例在切换前后都绿，无鉴别力。
        // 压到 10 条后：旧判据 10<30 → 不注入（本用例会红）；新判据 剩余 30% ≤ 30% → 注入（绿）。
        List<ChatMessageDto> msgs = new ArrayList<>(largeMessages(10));
        msgs.add(hydratedAssistant("a1", 70_000, 100));   // → 剩余恰好 30%
        driveAndCapture(msgs, WINDOW, sent -> assertNudged(sent, "剩余 30% == 阈值 30% → 注入（<= 含等号）"));
    }

    @Test
    @DisplayName("边界: 剩余 31% → 不注入")
    void remainingJustAboveThreshold_skips() {
        List<ChatMessageDto> msgs = new ArrayList<>(largeMessages(60));
        msgs.add(hydratedAssistant("a1", 69_000, 100));   // → 剩余恰好 31%
        driveAndCapture(msgs, WINDOW, sent -> assertNotNudged(sent, "剩余 31% > 阈值 30% → 不注入"));
    }

    @Test
    @DisplayName("无 usage（只有 user 消息）→ 不注入，且不抛")
    void noUsage_skips() {
        List<ChatMessageDto> msgs = new ArrayList<>(largeMessages(100));   // 无 assistant → extractContextUsage=null
        driveAndCapture(msgs, WINDOW, sent -> assertNotNudged(sent,
            "无 usage → contextRemainingPercent=null → 不注入（宁可不提示，也不臆测）"));
    }

    @Test
    @DisplayName("子代理路径（agentId != null）按同一判据评估: 剩余 20% → 注入")
    void subagentContext_evaluatedWithSameJudge() {
        // ⚠️ 与任务书的唯一偏差：AgentState 第 3 参实际类型是 UUID（AgentState.java:399
        //    `AgentState(String systemPrompt, String sessionId, UUID agentId)`），不是 String。
        //    任务书写的是 "agent-" + UUID.randomUUID()（String）→ 编译不过，故按实际签名传 UUID。
        AgentState state = new AgentState("sys",
            "sess-" + UUID.randomUUID().toString().substring(0, 8),
            UUID.randomUUID());                               // 第 3 参非 null = 子代理形态
        // ⚠️ 条数必须 < 30：否则旧判据（消息条数 ≥ 30）也会注入 → 本用例在切换前后都绿，无鉴别力。
        for (ChatMessageDto m : largeMessages(10)) {
            state.appendMessage(m);
        }
        state.appendMessage(hydratedAssistant("a1", 80_000, 100));   // → 剩余 20%

        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            null, factory, null, null, beansForWindow(WINDOW), snipOnFlags());
        drive(ctx, state);

        assertThat(histories).isNotEmpty();
        assertNudged(histories.get(histories.size() - 1),
            "子代理路径与主线程同判据：剩余 20% ≤ 30% → 注入。"
            + "这是「子代理 effectiveWindow=0 已被消除」的定点证据（新判据不读 thresholdSystem）");
    }

    @Test
    @DisplayName("DB 阈值覆盖默认: DB=80 时剩余 80% 也注入（须走 11 参 queryLoop 注入 resolver）")
    void dbThresholdOverridesDefault() {
        AgentState state = new AgentState("sys",
            "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : largeMessages(60)) {
            state.appendMessage(m);
        }
        state.appendMessage(hydratedAssistant("a1", 20_000, 100));   // → 剩余 80%（默认阈值下不会注入）

        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);
        AgentLoopContext ctx = TestContexts.agentLoopContext(
            null, factory, null, null, beansForWindow(WINDOW), snipOnFlags());

        // ⚠️ 关键：3 参 drive(ctx,state) 走 queryLoop 3 参重载，该重载把 settingsResolver **硬编码为 null**
        //    （LlmAgentLoop.java:3761-3766 → :3765 传 null,null），无法注入。必须走 11 参重载
        //    （LlmAgentLoop.java:3894-3905），settingsResolver 在**第 6 位**。
        CompactSettingsResolver resolver = Mockito.mock(CompactSettingsResolver.class);
        when(resolver.historySnipEnabled()).thenReturn(true);
        when(resolver.snipNudgeThreshold()).thenReturn(80);

        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        LlmAgentLoop.queryLoop(
            LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state),
            state, new ArrayList<>(),
            null, null, resolver, null, null, null, null, false);

        assertThat(histories).isNotEmpty();
        assertNudged(histories.get(histories.size() - 1),
            "DB snip_nudge_threshold=80 → 剩余 80% ≤ 80% → 注入（DB 值覆盖默认 30）");
    }

    // ══════════════ helper ══════════════

    private static List<ChatMessageDto> largeMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(singleMessage("u" + i, "hi"));
        }
        return list;
    }

    private static ChatMessageDto singleMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** DB 水合形态：usage()==null，仅 inputTokens/outputTokens 有值。 */
    private static ChatMessageDto hydratedAssistant(String id, Integer in, Integer out) {
        return new ChatMessageDto(
            id, null, Role.assistant, "assistant", "reply", null, List.of(),
            FinishReason.stop, in, out, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** FeatureFlags：仅 historySnip 位为 true。**逐字抄 LlmAgentLoopSnipMicroWiringTest.snipOnFlags()** —— 位序不可猜。 */
    private static FeatureFlags snipOnFlags() {
        // 17 参 = 融合后 FeatureFlags record 全字段：仅 historySnip(pos6)=true，其余全 false
        return new FeatureFlags(false, false, false, false, false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
    }

    /**
     * Mockito 打桩两个 mapper，使 ContextUsageCalculator.snapshot 解析到 window。
     * **逐字抄 ContextUsageRemainingPercentTest.ctxWithWindow 的打桩方式**；
     * 注意 providerId 必须写字面量 "p1"（isAnthropic 走精确串匹配），且 provider.setType 必须设。
     */
    private static AgentLoopContext.TokenBudgetBeans beansForWindow(long window) {
        ModelRecord model = new ModelRecord();
        model.setId("m1");
        model.setProviderId("p1");
        model.setName("deepseek-v4-flash");
        model.setEnabled(true);
        model.setMaxContextTokens((int) window);
        ModelMapper modelMapper = mock(ModelMapper.class);
        when(modelMapper.selectOneByQuery(any())).thenReturn(model);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model));

        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setType("openai_compatible");
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        when(providerMapper.selectOneById("p1")).thenReturn(provider);
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider);

        return new AgentLoopContext.TokenBudgetBeans(
            mock(TokenEstimator.class), modelMapper, providerMapper);
    }

    private static LoopResult drive(AgentLoopContext ctx, AgentState state) {
        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        return LlmAgentLoop.queryLoop(LlmAgentLoop.collectRunMaterial(params.deps().context(), params, state), state, new ArrayList<>());
    }

    private static QueryParams forLoopParams(AgentLoopContext ctx, QuerySource source, AgentState state) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        return QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8)),
            source, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
    }

    /** provider 正常完成 + 捕获每次 LLM 调用的 history（messagesForLlm 等价 · stream arg3）列表。 */
    private static LlmProviderFactory capturingProviderFactory(List<List<ChatMessageDto>> capturedHistories) {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<ChatMessageDto> history = inv.getArgument(3);
            capturedHistories.add(history);
            java.util.function.Consumer<String> onChunk = inv.getArgument(9);
            java.util.function.Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            onChunk.accept("plain text reply");
            if (onMsg != null) {
                onMsg.accept(new AssistantMessage("plain text reply", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(provider).stream(
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
