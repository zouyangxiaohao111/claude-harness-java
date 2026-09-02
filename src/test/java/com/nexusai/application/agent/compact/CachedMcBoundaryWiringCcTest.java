package com.nexusai.application.agent.compact;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.command.CompactCommand;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AnthropicSdkProvider;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.anthropic.models.messages.MessageCreateParams;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * IMP2-11 · cached-MC 引用面闭环聚焦测试（门控四条件 V2-S3 + 延迟 boundary yield MISS-1 + /compact 入口 V2-S4）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图，非 WHAT）:
 * <ol>
 *   <li><b>V2-S3 门控矩阵（microCompact.ts:276-286）</b> — cached 路径进入 = feature('CACHED_MICROCOMPACT')
 *       && isCachedMicrocompactEnabled() && isModelSupportedForCacheEditing(model)
 *       && isMainThreadSource(querySource)，四条件全真才进入 cachedMicrocompactPath；前三项
 *       模块态（OD-01 "?"，cachedMicrocompact.js 缺失）由测试缝单独翻转，第四项独立判定
 *       （null=undefined 视为 main-thread，:249-251）。</li>
 *   <li><b>MISS-1 延迟 boundary yield（query.ts:866-892）</b> — cached-MC 的 boundary 不在
 *       microcompactMessages 内产出，而在 API 流结束后以真实上报的 cache_deleted_input_tokens
 *       减基线算 delta（API 字段 sticky/cumulative），delta &gt; 0 才 yield
 *       createMicrocompactBoundaryMessage。流结束生产接线 = LlmAgentLoop 流结束点调用
 *       {@link MicroCompactor#maybeCreateMicrocompactBoundaryMessage(long)}（本任务生产接线）。</li>
 *   <li><b>V2-S4 /compact 入口（compact.ts:98）</b> — CC /compact 调 microcompactMessages 不传
 *       querySource（undefined → isMainThreadSource=true，cached 门控可进）；Java 旧实现传
 *       "compact" 字符串（门控不可进）→ 语义偏移，本任务对齐为传 null（undefined 等价）。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>:
 * <ul>
 *   <li>revert LlmAgentLoop 流结束接线（不调 maybeCreateMicrocompactBoundaryMessage）→
 *       {@link #flowEnd_consumesPendingCacheEdits} 必须 FAIL（pendingCacheEdits 未被消费）。</li>
 *   <li>CompactCommand.microcompactMessages 改回传 effectiveQuerySource()="compact" →
 *       {@link #compactEntry_passesNull_undefinedEquivalent} 必须 FAIL（捕获到 "compact"）。</li>
 * </ul>
 */
@DisplayName("IMP2-11 · cached-MC 引用面闭环（门控四条件 V2-S3 + 延迟 boundary yield MISS-1 + /compact 入口 V2-S4）")
class CachedMcBoundaryWiringCcTest {

    @BeforeEach
    void resetStaticState() {
        MicroCompactor.setCachedMicrocompactEnabled(false);
        MicroCompactor.setNowForTest(0L);
        MicroCompactor.resetMicrocompactState();
    }

    @AfterEach
    void detachLogAppenders() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MicroCompactor.class);
        logger.detachAndStopAllAppenders();
        logger.setLevel(null); // 恢复继承级别
    }

    // ════════════════════════════════════════════════════════════════════
    // V2-S3 · cached 门控四条件矩阵（microCompact.ts:276-286）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 捕获 MicroCompactor debug 日志（cached 路径进入以 debug 日志为唯一可观察口——
     * OD-01 内部算法受限，cachedMicrocompactPath 无编辑产出无副作用）。
     */
    private static ListAppender<ILoggingEvent> attachDebugCapture() {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MicroCompactor.class);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static boolean enteredCachedPath(ListAppender<ILoggingEvent> app) {
        return app.list.stream()
            .anyMatch(e -> e.getFormattedMessage().contains("cached-MC 路径进入"));
    }

    @Test
    @DisplayName("V2-S3 门控矩阵: feature×module×model 8 组合 × main-thread → 仅全真进入 cached 路径")
    void gateMatrix_threeModuleToggles() {
        List<ChatMessageDto> messages = buildMessagesWithTools();
        boolean[] bools = {false, true};
        for (boolean feature : bools) {
            for (boolean module : bools) {
                for (boolean model : bools) {
                    MicroCompactor.setCachedMicrocompactFeatureEnabledForTest(feature);
                    MicroCompactor.setCachedMicrocompactModuleEnabledForTest(module);
                    MicroCompactor.setCachedMicrocompactModelSupportedForTest(model);
                    ListAppender<ILoggingEvent> app = attachDebugCapture();
                    try {
                        new MicroCompactor().microcompactMessages(messages, "repl_main_thread");
                        boolean expect = feature && module && model;
                        assertThat(enteredCachedPath(app))
                            .as("门控矩阵 feature=%s module=%s model=%s → 期望进入=%s（CC microCompact.ts:280-282）",
                                feature, module, model, expect)
                            .isEqualTo(expect);
                    } finally {
                        app.stop();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("V2-S3 第四条件 isMainThreadSource: null/前缀/大写 canonical 视为 main-thread，compact/session_memory/sdk 非")
    void gateFourthCondition_sourceVariants() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        List<ChatMessageDto> messages = buildMessagesWithTools();
        MicroCompactor mc = new MicroCompactor();

        // main-thread 值域：null（undefined，CC :250 !querySource）/ 裸前缀 / 非默认 outputStyle 前缀 / 大写 canonical
        for (String source : new String[] {null, "repl_main_thread", "repl_main_thread:outputStyle:sonnet",
                "REPL_MAIN_THREAD"}) {
            ListAppender<ILoggingEvent> app = attachDebugCapture();
            try {
                mc.microcompactMessages(messages, source);
                assertThat(enteredCachedPath(app))
                    .as("source=%s 必须视为 main-thread → cached 门控可进（microCompact.ts:249-251 canonical）", source)
                    .isTrue();
            } finally {
                app.stop();
            }
        }

        // 非 main-thread：compact（/compact 旧字面量，V2-S4 后入口不再传）/ COMPACT 大写 /
        // session_memory / SDK（canonical 'sdk' 非 main-thread，QuerySource.java:114 对齐 CC）
        for (String source : new String[] {"compact", "COMPACT", "session_memory", "SDK"}) {
            ListAppender<ILoggingEvent> app = attachDebugCapture();
            try {
                mc.microcompactMessages(messages, source);
                assertThat(enteredCachedPath(app))
                    .as("source=%s 非 main-thread → cached 门控不可进（microCompact.ts:249-251）", source)
                    .isFalse();
            } finally {
                app.stop();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // MISS-1 · 延迟 boundary yield 单元面（query.ts:866-892）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MISS-1: feature 关 → 不消费 pendingCacheEdits、不 yield（query.ts:870 门短路）")
    void boundary_featureOff_noConsume_noYield() {
        MicroCompactor.setCachedMicrocompactEnabled(false);
        MicroCompactor.setPendingCacheEditsForTest(edits("auto", List.of("t1"), 0L));

        assertThat(MicroCompactor.maybeCreateMicrocompactBoundaryMessage(100L))
            .as("feature('CACHED_MICROCOMPACT') 关 → 不 yield（query.ts:870 外部构建等价）").isNull();
        assertThat(MicroCompactor.consumePendingCacheEdits())
            .as("feature 关门短路 → 不消费模块态（下次启用时仍可下发）").isNotNull();
    }
    @Test
    @DisplayName("MISS-1: 无 pendingCacheEdits → 不 yield（cached-MC 未产出 cache_edits）")
    void boundary_noPendingEdits_noYield() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        MicroCompactor.resetMicrocompactState();

        assertThat(MicroCompactor.maybeCreateMicrocompactBoundaryMessage(100L)).isNull();
        assertThat(MicroCompactor.consumePendingCacheEdits()).isNull();
    }

    @Test
    @DisplayName("MISS-1: 累计 ≤ 基线 → delta=0 不 yield，且消费清空模块态（query.ts:879-883）")
    void boundary_deltaZero_noYield() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        MicroCompactor.setPendingCacheEditsForTest(edits("auto", List.of("t1"), 50L));

        assertThat(MicroCompactor.maybeCreateMicrocompactBoundaryMessage(30L))
            .as("delta=max(0,30-50)=0 → 不 yield（API 字段累计/sticky，query.ts:879-882）").isNull();
        assertThat(MicroCompactor.consumePendingCacheEdits())
            .as("消费发生在 delta 计算前（consume 取走并清空，query.ts:870-878）").isNull();
    }

    @Test
    @DisplayName("MISS-1: 累计 > 基线 → yield microcompact_boundary（delta=tokensSaved，删除工具 id 透传）")
    void boundary_deltaPositive_yieldsBoundary() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        MicroCompactor.setPendingCacheEditsForTest(edits("auto", List.of("t1", "t2"), 100L));

        CompactBoundaryMessage b = MicroCompactor.maybeCreateMicrocompactBoundaryMessage(250L);

        assertThat(b).as("delta=250-100=150 > 0 → 必须 yield boundary（query.ts:884-890）").isNotNull();
        assertThat(b.subtype()).isEqualTo(CompactBoundaryMessage.SUBTYPE_MICROCOMPACT_BOUNDARY);
        assertThat(b.content()).isEqualTo(CompactBoundaryMessage.CONTENT_MICROCOMPACTED);
        assertThat(b.microcompactMetadata()).isNotNull();
        assertThat(b.microcompactMetadata().trigger()).as("CC trigger 恒 'auto'（microCompact.ts:389）").isEqualTo("auto");
        assertThat(b.microcompactMetadata().preTokens()).as("query.ts:885 第二参恒 0").isZero();
        assertThat(b.microcompactMetadata().tokensSaved())
            .as("tokensSaved = API 上报 delta（非客户端估算，query.ts:866-868）").isEqualTo(150);
        assertThat(b.microcompactMetadata().compactedToolIds())
            .as("deletedToolIds 透传（query.ts:888）").containsExactly("t1", "t2");
        assertThat(MicroCompactor.consumePendingCacheEdits())
            .as("yield 后模块态已清空（consume 语义，microCompact.ts:88-94）").isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // MISS-1 · 流结束生产接线（LlmAgentLoop）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MISS-1 流结束接线: pendingCacheEdits 注入 → 主循环流结束后被消费（生产调用命中，非仅测试）")
    void flowEnd_consumesPendingCacheEdits() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        MicroCompactor.setPendingCacheEditsForTest(edits("auto", List.of("t1"), 0L));

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(singleMessage("m1", "question"));
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, completingProviderFactory(), null, null, null);
        LlmAgentLoop.queryLoop(forLoopParams(ctx, state), state, new ArrayList<>());

        // 流正常完成（assistant 消息已提交）→ 流结束点必须已消费 pendingCacheEdits
        assertThat(state.messages().stream().anyMatch(m -> m.role() == Role.assistant))
            .as("完成 provider 必须产出 assistant 消息（流正常完成前置）").isTrue();
        assertThat(MicroCompactor.consumePendingCacheEdits())
            .as("MISS-1: LlmAgentLoop 流结束点必须调用 maybeCreateMicrocompactBoundaryMessage 消费模块态（query.ts:866-892 生产接线）")
            .isNull();
    }

    @Test
    @DisplayName("MISS-1 对照: feature 关 → 流结束不消费（query.ts:870 门短路，与单测一致）")
    void flowEnd_featureOff_doesNotConsume() {
        MicroCompactor.setCachedMicrocompactEnabled(false);
        MicroCompactor.setPendingCacheEditsForTest(edits("auto", List.of("t1"), 0L));

        AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        state.appendMessage(singleMessage("m1", "question"));
        AgentLoopContext ctx = TestContexts.agentLoopContext(null, completingProviderFactory(), null, null, null);
        LlmAgentLoop.queryLoop(forLoopParams(ctx, state), state, new ArrayList<>());

        assertThat(MicroCompactor.consumePendingCacheEdits())
            .as("feature 关 → 流结束点不消费（query.ts:870 短路，不触碰模块态）").isNotNull();
    }

    @Test
    @DisplayName("OD-01 流结束接线: 门开 + cachedMCState 已初始化 → markToolsSentToAPIState 被调用（claude.ts:2834-2836）")
    void flowEnd_gateOn_callsMarkToolsSentToAPI() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        // 先经 cached 门控进入路径初始化 cachedMCState 模块态（ensureCachedMCState），
        // 使流结束点 markToolsSentToAPIState 的 toolsSentToAPI=true 日志可观测。
        new MicroCompactor().microcompactMessages(buildMessagesWithTools(), "repl_main_thread");

        ListAppender<ILoggingEvent> app = attachDebugCapture();
        try {
            AgentState state = new AgentState("sys", "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
            state.appendMessage(singleMessage("m1", "question"));
            AgentLoopContext ctx = TestContexts.agentLoopContext(null, completingProviderFactory(), null, null, null);
            LlmAgentLoop.queryLoop(forLoopParams(ctx, state), state, new ArrayList<>());

            assertThat(app.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("markToolsSentToAPIState")))
                .as("OD-01: 流成功完成后 cachedMicrocompactEnabledForModel 门通过 → markToolsSentToAPIState 被调用（claude.ts:2834-2836）")
                .isTrue();
        } finally {
            app.stop();
        }
    }

    @Test
    @DisplayName("OD-01 provider 消费: 门开 → buildMessageParams 请求构造前 consume 一次 pendingCacheEditsBlock（claude.ts:1528-1535）")
    void providerRequestConsumesPendingCacheEditsBlock_once() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        MicroCompactor.resetMicrocompactState();
        List<ChatMessageDto> messages = buildThirteenToolMessages();
        // 13 个可压缩工具 → active > triggerThreshold(10) → cachedMicrocompactPath 触发删除 → 入队 block
        new MicroCompactor().microcompactMessages(messages, "repl_main_thread");
        assertThat(MicroCompactor.consumePendingCacheEditsBlock())
            .as("前置：cached 路径触发删除 → pendingCacheEditsBlock 已入队").isNotNull();
        // 重新触发入队（上一步已 consume 清空），供 buildMessageParams 消费
        new MicroCompactor().microcompactMessages(messages, "repl_main_thread");

        MessageCreateParams params = AnthropicSdkProvider.buildMessageParams(
            "claude-opus-4-1", null, messages, null, null, null, null, null, false, null, false, null);

        assertThat(params).as("请求构造正常产出 params").isNotNull();
        assertThat(MicroCompactor.consumePendingCacheEditsBlock())
            .as("OD-01: buildMessageParams 请求构造点已 consume 一次并清空（claude.ts:1528-1535 consume-once）")
            .isNull();
    }

    private static List<ChatMessageDto> buildThirteenToolMessages() {
        // 注意：不能用 assistantWithToolCall 助手（其 ToolCallDto 落在 contentBlocks 而非 toolCalls 位，
        // collectCompactableToolIds 读 toolCalls() → 空集）；须把 ToolCallDto 放 toolCalls 位置。
        List<ChatMessageDto> list = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            list.add(new ChatMessageDto(
                "asst-" + i, null, Role.assistant, "assistant", "thinking", null,
                List.of(new com.nexusai.model.session.dto.ToolCallDto(
                    "t" + i, "Bash", "{}", null, false)),
                FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of()));
            list.add(new ChatMessageDto(
                "tool-" + i, null, Role.tool, "tool", "result content", null,
                List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                "t" + i, null, null, List.of(), List.of()));
        }
        return list;
    }

    // ════════════════════════════════════════════════════════════════════
    // V2-S4 · /compact 入口「compact」vs undefined 语义（compact.ts:98）
    // ════════════════════════════════════════════════════════════════════

    /** 记录型 MicroCompactor：捕获 microcompactMessages 收到的 querySource。 */
    static class RecordingMicroCompactor extends MicroCompactor {
        String capturedSource = "__unset__";

        @Override
        public MicroCompactResult microcompactMessages(List<ChatMessageDto> messages, String querySource) {
            capturedSource = querySource;
            return new MicroCompactResult(messages, null);
        }
    }

    @Test
    @DisplayName("V2-S4: /compact 入口传 null（CC undefined，compact.ts:98）而非 'compact' 字符串")
    void compactEntry_passesNull_undefinedEquivalent() throws Exception {
        RecordingMicroCompactor micro = new RecordingMicroCompactor();
        CompactCommand.CompactCommandContext ctx = new CompactCommand.CompactCommandContext(
            List.of(singleMessage("m1", "hi")), null, null, null, false, null, null,
            micro, null, null, null, null, null, null, null, null, null, false, () -> false);

        Method m = CompactCommand.class.getDeclaredMethod(
            "microcompactMessages", List.class, CompactCommand.CompactCommandContext.class);
        m.setAccessible(true);
        m.invoke(null, ctx.messages(), ctx);

        assertThat(micro.capturedSource)
            .as("CC compact.ts:98 microcompactMessages(messages, context) 不传 querySource → undefined；Java 必须传 null（isMainThreadSource(undefined)=true，cached 门控可进）")
            .isNull();
    }

    @Test
    @DisplayName("V2-S4 语义: null source（/compact 入口）+ 门全开 → cached 门控可进；time-based 仍不触发")
    void nullSource_cachedGateCanPass_timeBasedSkipped() {
        MicroCompactor.setCachedMicrocompactEnabled(true);
        // time-based 配置启用 + gap 超阈值：null source 也必须不触发（microCompact.ts:427-433）
        long now = System.currentTimeMillis();
        MicroCompactor.setNowForTest(now);
        MicroCompactor mc = new MicroCompactor(() -> new MicroCompactor.TimeBasedMCConfig(true, 60, 1));
        List<ChatMessageDto> messages = buildTimeBasedMessages(now);

        ListAppender<ILoggingEvent> app = attachDebugCapture();
        try {
            MicroCompactResult result = mc.microcompactMessages(messages, null);
            assertThat(enteredCachedPath(app))
                .as("V2-S4: null（undefined）→ isMainThreadSource=true，cached 门控可进（microCompact.ts:249-251/280-282）")
                .isTrue();
            assertThat(result.messages()).isSameAs(messages);
        } finally {
            app.stop();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    private static MicroCompactResult.PendingCacheEdits edits(String trigger, List<String> ids, long baseline) {
        return new MicroCompactResult.PendingCacheEdits(trigger, ids, baseline);
    }

    private static List<ChatMessageDto> buildMessagesWithTools() {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(singleMessage("u1", "user question"));
        list.add(assistantWithToolCall("a1", "t1"));
        list.add(toolResultMsg("t1", "result content"));
        return list;
    }

    /** 最后一条 assistant 消息 createdAt = now - 120min（超 gapThreshold=60min）。 */
    private static List<ChatMessageDto> buildTimeBasedMessages(long now) {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(singleMessage("u1", "user question"));
        list.add(assistantWithToolCall("a1", "t1", OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(now - 120L * 60_000), java.time.ZoneOffset.UTC)));
        list.add(toolResultMsg("t1", "result content"));
        list.add(assistantWithToolCall("a2", "t2", OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(now - 60L * 60_000), java.time.ZoneOffset.UTC)));
        list.add(toolResultMsg("t2", "result content"));
        return list;
    }

    private static ChatMessageDto assistantWithToolCall(String id, String toolUseId) {
        return assistantWithToolCall(id, toolUseId, OffsetDateTime.now());
    }

    private static ChatMessageDto assistantWithToolCall(String id, String toolUseId, OffsetDateTime createdAt) {
        return new ChatMessageDto(
            id, null, Role.assistant, "assistant", "thinking", null, List.of(),
            FinishReason.tool_calls, null, null, "刚刚", createdAt,
            null, null, null, List.of(new com.nexusai.model.session.dto.ToolCallDto(
                toolUseId, "Bash", "{\"command\":\"ls\"}", null, false)), List.of());
    }

    private static ChatMessageDto toolResultMsg(String toolUseId, String content) {
        return new ChatMessageDto(
            toolUseId, null, Role.tool, "tool", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }


    private static ChatMessageDto singleMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static QueryParams forLoopParams(AgentLoopContext ctx, AgentState state) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        return QueryParams.forLoop(
            state.messages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8)),
            QuerySource.USER, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
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
            any(), any(), (java.util.List<com.nexusai.application.agent.prompt.SystemPromptBlock>) any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
