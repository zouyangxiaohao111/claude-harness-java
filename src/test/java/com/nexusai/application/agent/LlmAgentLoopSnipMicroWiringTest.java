package com.nexusai.application.agent;

import com.nexusai.application.agent.compact.AutoCompactor;
import com.nexusai.application.agent.compact.AutoCompactTrackingState;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactBoundaryMessage;
import com.nexusai.application.agent.compact.CompactThresholdSystem;
import com.nexusai.application.agent.compact.MicroCompactor;
import com.nexusai.application.agent.compact.MicroCompactResult;
import com.nexusai.application.agent.compact.SnipCompactor;
import com.nexusai.application.agent.event.AgentBoundaryMessageEvent;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.LoopResult;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [S3] 主循环压缩块 4 处 CC 对齐聚焦测试（snip 门控 + micro 接线 + snipTokensFreed 透传 + blocking 窗口）。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图，非 WHAT):
 * <ol>
 *   <li><b>(a) snip 门控（CC query.ts:401）</b> — HISTORY_SNIP flag 关时主循环必须跳过 snip
 *       （对齐 CC 外部构建 snip 模块为 null）；开时按真源算法执行（removedUuids 剔除 + boundary 保留）。
 *       门控是 B2 透传的前提。</li>
 *   <li><b>(a) B2 snipTokensFreed 透传（CC query.ts:466 / autoCompact.ts:225）</b> — autoCompactIfNeeded
 *       第二参必须透传 snip 释放量（非硬编码 0），否则 autocompact 阈值判定看不到 snip 已释放的量
 *       （INV-9）。经 mock autoCompactor 捕获第二参断言。</li>
 *   <li><b>(b) B6 blocking 窗口（CC autoCompact.ts:33-49/122-134）</b> — blocking 预检窗口从
 *       rawWindow−3000 收紧为 effectiveWindow−3000（effectiveWindow = contextWindow − min(maxOutput,20000)）。
 *       断言注入 CompactThresholdSystem 后同 usage 提前拦截（与旧公式不同）。</li>
 *   <li><b>(c) B4 测量减 snipTokensFreed（CC query.ts:638）</b> — blocking 预检测量
 *       {@code tokenCountWithEstimation(messagesForQuery) - snipTokensFreed}；门开 + snip 触发时
 *       预检用 (usage − snipFreed) 判定。</li>
 *   <li><b>B1 micro 接线（CC query.ts:414-426）</b> — 注入 MicroCompactor 后主循环真实调用
 *       microcompactMessages 并替换消息（snip 后、collapse 前）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>: revert LlmAgentLoop 中 snip 门控（恒执行）/ B2 透传（改回 0）/ B6（改回
 * rawWindow−3000）/ B4（不减 snipTokensFreed）/ B1（不调用 micro）→ 对应测试必须 fail。
 */
@DisplayName("[S3] snip 门控 + micro 接线 + snipTokensFreed 透传 + blocking 窗口（CC query.ts:401-466/637-638）")
class LlmAgentLoopSnipMicroWiringTest {

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    /** snip 触发前置 = 存在 snip_boundary（CC 真源 snipCompact.ts:96-109）；SNIP_TRIGGER_COUNT 仅消息基数。 */
    private static final int SNIP_TRIGGER_COUNT = 60;
    /** snipTriggerMessages() 中被 removedUuids 剔除的消息数（"hi" content=2 字符 → 各 1 token）。 */
    private static final int SNIP_REMOVED_COUNT = 10;
    /** 移除消息释放 token：10 × ceil(2/4)=1 = 10 tokens（真源 estimateMessageTokens，snipCompact.ts:35-58）。 */
    private static final int SNIP_TOKENS_FREED = SNIP_REMOVED_COUNT * 1;

    // ════════════════════════════════════════════════════════════════════
    // (a) snip 门控（CC query.ts:401 HISTORY_SNIP flag）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("snip 门控: HISTORY_SNIP=false → 主循环跳过 snip（有 boundary 也不执行，u0 保留）")
    void snipGateOff_skipsSnip() throws IOException {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : snipTriggerMessages()) {
            state.appendMessage(m);
        }
        LlmProviderFactory factory = completingProviderFactory();

        // 默认 feature flags（historySnip=false）
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        LoopResult result = drive(ctx, state);

        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(state.exitReason())
            .as("historySnip=false → 不 snip，正常 LLM 调用")
            .isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
        assertThat(ids(state))
            .as("HISTORY_SNIP=false → snip 被 feature 门跳过（CC query.ts:401 关时不执行），removedUuids 消息保留")
            .contains("u0");
    }

    @Test
    @DisplayName("snip 门控: HISTORY_SNIP=true → 主循环执行 snip（请求面 removedUuids 剔除，state 保留全量 · B5 d-2）")
    void snipGateOn_runsSnip() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : snipTriggerMessages()) {
            state.appendMessage(m);
        }
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);

        FeatureFlags flags = snipOnFlags();
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null, flags);
        LoopResult result = drive(ctx, state);

        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(histories).as("LLM 至少被调用一次（history 被捕获）").isNotEmpty();
        assertThat(ids(histories.get(histories.size() - 1)))
            .as("HISTORY_SNIP=true + snip_boundary → 请求面（messagesForQuery）removedUuids 中 u0..u9 必须被剔除（CC 真源 snipCompact.ts:128-139）")
            .doesNotContain("u0", "u9");
        assertThat(ids(state))
            .as("B5 d-2: removedUuids 中 u0..u9 必须保留在 state.messages()（snip 请求级投影，不再持久化删除）")
            .contains("u0", "u9");
        assertThat(ids(state))
            .as("snip 后 boundary 保留在 state（输入含 boundary，真源 kept 含 boundary，snipCompact.ts:128-139）")
            .contains("snip-boundary-1");
        assertThat(ids(state))
            .as("snip 不再插入占位符（head3+tail47 启发式已删，CC 真源无占位符）")
            .doesNotContain("snip-placeholder");
    }

    // ════════════════════════════════════════════════════════════════════
    // (a) B2 snipTokensFreed 透传（CC query.ts:466 / autoCompact.ts:225，INV-9）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B2: HISTORY_SNIP=false → autoCompactIfNeeded 第二参=0（无 snip 释放量可透传）")
    void b2PassThrough_gateOff_zero() {
        int captured = captureSnipTokensFreed(false);
        assertThat(captured)
            .as("门关时 snipTokensFreed 必须为 0（CC query.ts:466 透传 0，INV-9）")
            .isZero();
    }

    @Test
    @DisplayName("B2: HISTORY_SNIP=true + snip 触发 → autoCompactIfNeeded 第二参=真实 tokensFreed（非硬编码 0）")
    void b2PassThrough_gateOn_forwardsTokensFreed() {
        int captured = captureSnipTokensFreed(true);
        assertThat(captured)
            .as("门开 + snip 触发时 autoCompactIfNeeded 第二参必须透传 snipResult.tokensFreed()（CC query.ts:466）")
            .isEqualTo(SNIP_TOKENS_FREED);
    }

    /** 经 4 参 queryLoop 注入 mock autoCompactor，捕获 autoCompactIfNeeded 第二参。 */
    private static int captureSnipTokensFreed(boolean historySnipOn) {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : snipTriggerMessages()) {
            state.appendMessage(m);
        }
        LlmProviderFactory factory = completingProviderFactory();

        AutoCompactor auto = Mockito.mock(AutoCompactor.class);
        when(auto.autoCompactIfNeeded(anyList(), anyInt(), anyString(), any()))
            .thenReturn(new AutoCompactor.AutoCompactResult(false, List.of(), null, 0, null, null));
        when(auto.getTracking()).thenReturn(new AutoCompactTrackingState());

        FeatureFlags flags = historySnipOn ? snipOnFlags() : FeatureFlags.ALL_DISABLED;
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null, flags);

        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>(), auto);

        ArgumentCaptor<Integer> cap = ArgumentCaptor.forClass(Integer.class);
        verify(auto).autoCompactIfNeeded(anyList(), cap.capture(), anyString(), any());
        return cap.getValue();
    }

    /** 静态守卫：autoCompactIfNeeded 第二参必须写 snipTokensFreed 变量（非硬编码 0）。 */
    @Test
    @DisplayName("B2 静态: autoCompactIfNeeded 第二参必须透传 snipTokensFreed 变量（CC query.ts:466）")
    void b2SourceForwardsVariable() throws IOException {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("B2: 第二参必须写 snipTokensFreed 变量（非字面量 0 · CC query.ts:466 透传；"
                + "B5 d-2 请求面第一参为 messagesForQuery 局部）")
            .contains("messagesForQuery, snipTokensFreed, params.querySource().canonical(), ccCtx");
        assertThat(source)
            .as("B2: snipTokensFreed 必须在 snip 块前以 0 声明（门关默认值，供透传/测量复用）")
            .contains("int snipTokensFreed = 0;");
    }

    // ════════════════════════════════════════════════════════════════════
    // (b) B6 blocking 窗口（CC autoCompact.ts:33-49/122-134）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B6: 注入 autoCompactor+CompactThresholdSystem → blockingLimit=effectiveWindow−3000，同 usage 提前拦截")
    void b6BlockingWindow_effectiveWindowMinus3000() {
        // resolver contextWindow=150000 —— 必须 ≥100k（CONTEXT_WINDOW_CAPABILITY_GATE，CC context.ts:75：
        //   <100k 窗口落穿 → getContextWindowForModel 回落默认 200k，测不到 effectiveWindow 收紧语义）
        // test-model maxOutput=32000 → min(32000,20000)=20000
        // effectiveWindow = 150000−20000 = 130000 → blockingLimit = 130000−3000 = 127000
        // usage=130000 >= 127000 → 拦截（旧公式 rawWindow−3000=197000 下不拦截；
        //   对照 b6BlockingWindow_fallbackRawWindow 同 usage=130000 → 兜底 197000 不拦截）
        AutoCompactor auto = new AutoCompactor(msgs -> 50,
            (p, m) -> new CompactConversation.SummaryResult("<summary>", null));
        CompactThresholdSystem cts = new CompactThresholdSystem(null);
        cts.setModelContextWindowResolver(model -> 150_000);
        // 测试隔离 model 解析：resolveMaxOutputTokensForModel('test-model') 走真实链返回值不受控 →
        //   注入 maxOutput resolver（对齐 contextWindow resolver）使 effectiveWindow/blockingLimit 可控。
        cts.setMaxOutputTokensResolver(model -> 32_000);
        auto.setThresholdSystem(cts);

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(singleMessage("m1", "question"));
        LlmProviderFactory factory = completingProviderFactory();
        // rawWindow（computeBudgetFromGates）= 200000 → 旧兜底公式 rawWindow−3000=197000 不拦截
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null,
            TestContexts.tokenBudgetBeans(200_000, 130_000));

        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>(), auto);

        assertThat(state.exitReason())
            .as("B6: blockingLimit=effectiveWindow−3000=127000 ≤ usage=130000 → BLOCKING_LIMIT（旧公式 197000 不拦截）")
            .isEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
    }

    @Test
    @DisplayName("B6 对照: 未注入 autoCompactor（兜底 rawWindow−3000）→ 同 usage 不拦截")
    void b6BlockingWindow_fallbackRawWindow() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(singleMessage("m1", "question"));
        LlmProviderFactory factory = completingProviderFactory();
        // 同主测试 usage=130000，但未注入 autoCompactor → 兜底 blockingLimit=rawWindow−3000=197000 > 130000 → 不拦截
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null,
            TestContexts.tokenBudgetBeans(200_000, 130_000));

        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());

        assertThat(state.exitReason())
            .as("B6 对照: autoCompactor=null 兜底 blockingLimit=rawWindow−3000=197000 > usage=130000 → 不拦截")
            .isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
    }

    // ════════════════════════════════════════════════════════════════════
    // (c) B4 测量减 snipTokensFreed（CC query.ts:638）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B4: blocking 测量减 snipTokensFreed（usage−snipFreed 判定，CC query.ts:638）")
    void b4MeasurementSubtractsSnipTokensFreed() {
        // 兜底窗口：contextWindow=30000 → blockingLimit=30000−3000=27000
        // usage=27200，snip 触发释放 500 → 测量 = 27200−500 = 26700 < 27000 → 不拦截
        // （若 B4 回归不减 snipFreed：27200 >= 27000 → 拦截，本测试 fail）
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : b4SnipTriggerMessages()) {
            state.appendMessage(m);
        }
        LlmProviderFactory factory = completingProviderFactory();
        FeatureFlags flags = snipOnFlags();
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null,
            TestContexts.tokenBudgetBeans(30_000, 27_200), flags);

        LoopResult result = drive(ctx, state);

        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(state.exitReason())
            .as("B4: 测量必须减 snipTokensFreed → (27200−500)=26700 < 27000 不拦截；不减则 27200>=27000 拦截")
            .isNotEqualTo(AgentState.ExitReason.BLOCKING_LIMIT);
    }

    // ════════════════════════════════════════════════════════════════════
    // B1 micro 接线（CC query.ts:414-426）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B1: 注入 MicroCompactor → 主循环真实调用 microcompactMessages 并替换请求级消息（B5 d-2：state 保留全量）")
    void b1MicroWiring_replacesMessages() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(singleMessage("m1", "first"));
        state.appendMessage(singleMessage("m2", "second"));
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);

        MicroCompactor micro = Mockito.mock(MicroCompactor.class);
        List<ChatMessageDto> reduced = List.of(singleMessage("m1", "first"));
        when(micro.microcompactMessages(anyList(), anyString()))
            .thenReturn(new MicroCompactResult(reduced, null));

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>(), null, micro);

        verify(micro).microcompactMessages(anyList(), anyString());
        assertThat(histories)
            .as("LLM 至少被调用一次（history 被捕获）")
            .isNotEmpty();
        assertThat(ids(histories.get(histories.size() - 1)))
            .as("B1+B5 d-2: microcompact 结果必须替换请求级 messagesForQuery（m2 被 micro 丢弃后不应进 LLM 请求）· CC query.ts:415")
            .doesNotContain("m2");
        assertThat(ids(state))
            .as("B5 d-2: microcompact 不再持久化替换 state.messages()（state 保留全量，REPL/transcript 保留 m2）")
            .contains("m2");
    }

    // ════════════════════════════════════════════════════════════════════
    // (d) S4/DRIFT-3 snip boundaryMessage yield 到流事件通道（CC query.ts:406-408）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S4: HISTORY_SNIP=true + snip 触发 → boundary 消息发布到事件通道 + 请求级投影（CC query.ts:404-408）")
    void snipBoundaryYield_publishedToEventChannel() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : snipTriggerMessages()) {
            state.appendMessage(m);
        }
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);

        ApplicationEventPublisher publisher = Mockito.mock(ApplicationEventPublisher.class);
        AgentLoopContext.EventBridge bridge = new AgentLoopContext.EventBridge(publisher, null, null);
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null,
            snipOnFlags(), bridge);

        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeastOnce()).publishEvent(cap.capture());
        List<AgentBoundaryMessageEvent> boundaryEvents = cap.getAllValues().stream()
            .filter(AgentBoundaryMessageEvent.class::isInstance)
            .map(AgentBoundaryMessageEvent.class::cast)
            .toList();
        assertThat(boundaryEvents)
            .as("snip 触发时必须发布 AgentBoundaryMessageEvent（CC query.ts:406-408 yield 等价）")
            .hasSize(1);
        ChatMessageDto yielded = boundaryEvents.get(0).boundaryMessage();
        assertThat(yielded)
            .as("yield 载荷 = SnipCompactor 产出的 boundary 消息（messages[boundaryIdx] 原样，snipCompact.ts:115）")
            .isNotNull();
        assertThat(yielded.id())
            .as("yield 载荷 = 原样 boundary（非凭空构造，id=snip-boundary-1）")
            .isEqualTo("snip-boundary-1");
        assertThat(yielded.subtype())
            .as("yield 载荷 subtype = snip_boundary（CC 判别依据 snipProjection.ts:15-18）")
            .isEqualTo(SnipCompactor.SUBTYPE_SNIP_BOUNDARY);
        // 双通道语义（CC query.ts:404-408）：boundary 既在请求面消息链（messagesForQuery 含 boundary），
        // 又经事件流 yield（前端可呈现）——不再有 head3+tail47 占位符
        assertThat(ids(state))
            .as("真源语义: snip 后 boundary 保留在 state（输入含 boundary，snipCompact.ts:128-139）")
            .contains(yielded.id());
        // [B5 d-2] 请求级投影：removedUuids 中 u0..u9 只在请求面剔除，state.messages() 保留全量
        //（REPL/transcript 保留；CC query.ts:404 `messagesForQuery = snipResult.messages`）
        assertThat(ids(state))
            .as("B5 d-2: removedUuids 中 u0..u9 必须保留在 state.messages()（snip 不再持久化删除）")
            .contains("u0", "u9");
        assertThat(histories)
            .as("LLM 至少被调用一次（history 被捕获）")
            .isNotEmpty();
        assertThat(ids(histories.get(histories.size() - 1)))
            .as("B5 d-2: removedUuids 中 u0..u9 必须从请求面（messagesForQuery）剔除（CC query.ts:404）")
            .doesNotContain("u0", "u9");
        assertThat(ids(histories.get(histories.size() - 1)))
            .as("B5 d-2: 请求面必须保留 snip_boundary（kept 含 boundary，snipCompact.ts:128-139）")
            .contains(yielded.id());
        assertThat(ids(state))
            .as("snip 不再插入占位符（head3+tail47 启发式已删，CC 真源无占位符）")
            .doesNotContain("snip-placeholder");
    }

    @Test
    @DisplayName("S4 对照: HISTORY_SNIP=false → 不发布 AgentBoundaryMessageEvent（CC query.ts:401 门控）")
    void snipBoundaryYield_gateOff_noEvent() {
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : snipTriggerMessages()) {
            state.appendMessage(m);
        }
        LlmProviderFactory factory = completingProviderFactory();

        ApplicationEventPublisher publisher = Mockito.mock(ApplicationEventPublisher.class);
        AgentLoopContext.EventBridge bridge = new AgentLoopContext.EventBridge(publisher, null, null);
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null,
            FeatureFlags.ALL_DISABLED, bridge);

        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());

        verify(publisher, never())
            .publishEvent(Mockito.any(AgentBoundaryMessageEvent.class));
    }

    @Test
    @DisplayName("S4: AgentBoundaryMessageEvent 适配为流事件 AgentEvent.BoundaryMessage（runStream 流输出通道）")
    void snipBoundaryYield_adaptsToStreamEvent() {
        ChatMessageDto boundary =
            CompactBoundaryMessage.createCompactBoundaryMessage("auto", 0, null, null, null)
                .toChatMessageDto();
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        AgentBoundaryMessageEvent springEvent = new AgentBoundaryMessageEvent(state, boundary);

        AgentEvent adapted = LlmAgentLoop.adaptToAgentEvent(springEvent);

        assertThat(adapted)
            .as("publishEvent → adaptToAgentEvent → bufferEvent → runStream 流必须出现 BoundaryMessage")
            .isInstanceOf(AgentEvent.BoundaryMessage.class);
        AgentEvent.BoundaryMessage bm = (AgentEvent.BoundaryMessage) adapted;
        assertThat(bm.message())
            .as("流事件载荷必须透传 boundary 消息本身")
            .isSameAs(boundary);
        assertThat(bm.sessionId())
            .as("流事件 sessionId 取自 state")
            .isEqualTo(state.sessionId());
    }

    // ════════════════════════════════════════════════════════════════════
    // (e) context_efficiency nudge 注入（CC attachments.ts:929-937 getAttachments
    //     maybe('context_efficiency', ...) + attachments.ts:3963-3983
    //     getContextEfficiencyAttachment + messages.ts:4148-4161 渲染）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("nudge: HISTORY_SNIP=true + 会话 ≥30 条 → context_efficiency nudge 注入 LLM 消息流（CC attachments.ts:929-937/:3978）")
    void nudgeGateOn_injectsContextEfficiencyNudge() {
        // WHY: Java SnipCompactor.shouldNudgeForSnips/SNIP_NUDGE_TEXT 已实现但无消费方；CC 会话足够长
        //   （≥30 条，snipCompact.ts:163-165）时经 getContextEfficiencyAttachment（attachments.ts:3963-3983）
        //   注入「提示模型考虑 /force-snip」的 isMeta user 消息（messages.ts:4148-4161）。
        //   本测试验证消费方接线端到端：nudge 必须到达 provider.stream 的 history。
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : snipTriggerMessages()) {
            state.appendMessage(m);
        }
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null, snipOnFlags());
        LoopResult result = drive(ctx, state);

        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(histories)
            .as("LLM 至少被调用一次（history 被捕获）")
            .isNotEmpty();

        List<ChatMessageDto> sent = histories.get(histories.size() - 1);
        assertThat(sent.stream().anyMatch(m ->
            m.isMeta() && Role.user == m.role()
                && ("<system-reminder>\n" + SnipCompactor.SNIP_NUDGE_TEXT + "\n</system-reminder>")
                    .equals(m.content())))
            .as("B5 d-2: state.messages()=61 保留全量（snip 不再持久化删除，仍 ≥30）→ 必须注入 isMeta user nudge（CC messages.ts:4148-4161 wrapInSystemReminder）")
            .isTrue();
    }

    @Test
    @DisplayName("nudge 门控: HISTORY_SNIP=false → 不注入（CC attachments.ts:934/:3966）")
    void nudgeGateOff_skipsNudge() {
        // WHY: CC getAttachments:934 + getContextEfficiencyAttachment:3966 均 feature('HISTORY_SNIP')
        //   门控，关时 nudge 完全不产生；Java 若在门关时仍注入会污染 LLM 上下文。
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : snipTriggerMessages()) {
            state.appendMessage(m);
        }
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);
        // 默认 feature flags（historySnip=false）
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null);
        LoopResult result = drive(ctx, state);

        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(histories).isNotEmpty();
        List<ChatMessageDto> sent = histories.get(histories.size() - 1);
        assertThat(sent.stream().anyMatch(m ->
            m.isMeta() && SnipCompactor.SNIP_NUDGE_TEXT.equals(m.content())))
            .as("HISTORY_SNIP=false → 不得出现 SNIP_NUDGE_TEXT nudge（CC attachments.ts:934/:3966）")
            .isFalse();
    }

    @Test
    @DisplayName("nudge 阈值: HISTORY_SNIP=true 但消息 <30 → 不注入（snipCompact.ts:163-165）")
    void nudgeShortConversation_skipsNudge() {
        // WHY: shouldNudgeForSnips = messages.length >= 30（snipCompact.ts:163-165）；10 条短会话
        //   不应触发 nudge，避免短会话被无关提示污染。
        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : largeMessages(10)) {
            state.appendMessage(m);
        }
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null, snipOnFlags());
        LoopResult result = drive(ctx, state);

        assertThat(result.aborted()).as("正常完成不应 aborted").isFalse();
        assertThat(histories).isNotEmpty();
        List<ChatMessageDto> sent = histories.get(histories.size() - 1);
        assertThat(sent.stream().anyMatch(m ->
            m.isMeta() && SnipCompactor.SNIP_NUDGE_TEXT.equals(m.content())))
            .as("消息 10 条 < 30 → shouldNudgeForSnips=false，不注入 nudge（snipCompact.ts:163-165）")
            .isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private static FeatureFlags snipOnFlags() {
        // 17 参 = 融合后 FeatureFlags record 全字段：仅 historySnip(pos6)=true，其余全 false
        return new FeatureFlags(false, false, false, false, false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
    }

    private static LoopResult drive(AgentLoopContext ctx, AgentState state) {
        QueryParams params = forLoopParams(ctx, QuerySource.USER, state);
        return LlmAgentLoop.queryLoop(params, state, new ArrayList<>());
    }

    private static QueryParams forLoopParams(AgentLoopContext ctx, QuerySource source, AgentState state) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        return QueryParams.forLoop(
            state.messages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
            source, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
    }

    private static List<String> ids(AgentState state) {
        return state.messages().stream()
            .map(m -> m.id() != null ? m.id() : "").toList();
    }

    private static List<String> ids(List<ChatMessageDto> messages) {
        return messages.stream()
            .map(m -> m.id() != null ? m.id() : "").toList();
    }

    private static ChatMessageDto singleMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static List<ChatMessageDto> largeMessages(int count) {
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(singleMessage("u" + i, "hi"));
        }
        return list;
    }

    /**
     * 主循环 snip 触发消息集 = 60 条 "hi" user + 尾部 snip_boundary（removedUuids=u0..u9）。
     * 真源算法（snipCompact.ts:128-139）剔除 u0..u9、保留 boundary + u10..u59。
     */
    private static List<ChatMessageDto> snipTriggerMessages() {
        List<ChatMessageDto> msgs = new ArrayList<>(largeMessages(SNIP_TRIGGER_COUNT));
        msgs.add(snipBoundary("snip-boundary-1", removedUuids(0, SNIP_REMOVED_COUNT)));
        return msgs;
    }

    /**
     * B4 blocking 预检专用：5 条 content=400 字符（各 ceil(400/4)=100 token）被移除 → freed=500。
     * （"hi" 消息各 1 token 不足以把 27200−snipFreed 压到 27000 以下。）
     */
    private static List<ChatMessageDto> b4SnipTriggerMessages() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            msgs.add(singleMessage("u" + i, "x".repeat(400)));
        }
        msgs.add(snipBoundary("snip-boundary-b4", removedUuids(0, 5)));
        return msgs;
    }

    private static List<String> removedUuids(int from, int count) {
        List<String> removed = new ArrayList<>();
        for (int i = from; i < from + count; i++) {
            removed.add("u" + i);
        }
        return removed;
    }

    /** 37 参 canonical 构造 snip_boundary 消息（subtype + snipMetadata 承载，CC snipCompact.ts:99-106）。 */
    private static ChatMessageDto snipBoundary(String id, List<String> removedUuids) {
        Map<String, Object> meta = null;
        if (removedUuids != null) {
            meta = new LinkedHashMap<>();
            meta.put("removedUuids", removedUuids);
        }
        return new ChatMessageDto(
            id, "s", Role.system, "system", "snip boundary", null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of(),
            null, false, false, null, SnipCompactor.SUBTYPE_SNIP_BOUNDARY,
            false, null, null, null,
            null, null,
            null, null, null, false, false,
            null, null, null, meta);
    }

    /** provider 正常完成（onChunk/onMsg/onComplete）· blocking 不拦截时 loop 可快速完成。 */
    private static LlmProviderFactory completingProviderFactory() {
        LlmProvider provider = Mockito.mock(LlmProvider.class);
        Mockito.doAnswer(inv -> {
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
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
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
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
