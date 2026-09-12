package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.config.ToolRegistrationConfig;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.loop.LoopDeps;
import com.nexusai.application.agent.loop.QueryParams;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * [D4 双门源合并] HISTORY_SNIP 门单一来源 + 静态槽接活测试。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: HISTORY_SNIP 门此前被算两遍——
 * <ol>
 *   <li><b>门①（实例/参数链）</b> {@code LlmAgentLoop} snip 步骤：{@code ctx.featureFlags().historySnip()}
 *       经 {@code settingsResolver}（参数，非 null 时）DB 覆盖，消费点 = SnipCompactor / nudge / [id:] tag。</li>
 *   <li><b>门②（静态槽）</b> {@link BoundaryReader}：同公式但输入是静态槽 {@code settingsResolver}
 *       + 静态槽 {@code featureFlags}（默认 {@code ALL_DISABLED}，此前<b>全仓无人写</b> = 死槽），
 *       消费点 = {@code getMessagesAfterCompactBoundary} 的 {@code projectSnippedView}，
 *       循环入口剥离 {@code LlmAgentLoop} + CompactCommand/PartialCompactService/
 *       StreamCompactSummary/SkillifySkillRegistrar 共 5 处静态调用面。</li>
 * </ol>
 * DB {@code settings.history_snip_enabled} 有值时两门短路到同一值（暂时一致）；一旦该列为 NULL：
 * 门① 回落 FeatureFlags（application.yml:337 = true），门② 回落死槽 = false → <b>分叉</b>——被 snip
 * 删除的消息经入口剥离不剔除 → 泄漏进模型请求面 / 手工压缩输入面。
 *
 * <p><b>[N2 2026-09-11 更新]</b> 用户拍板 (B)「历史 snip 不复活」后，门② <b>已整体消失</b>：
 * snip 投影改为**回放门**（{@code !includeSnipped} 即投影，不看任何开关），故：
 * <ul>
 *   <li>静态槽 {@code setFeatureFlags} / {@code setSettingsResolver} 及其唯一读者
 *       {@code isHistorySnipEnabled()} 无参重载 → <b>死接线</b>（保留待用户裁定清理）；
 *       本类中依赖静态槽的断言因此退化为「恒成立」，不再有区分力（见各方法 as() 说明）。</li>
 *   <li>「门关 → 投影 no-op」的旧断言已按 (B) <b>反向改写</b>
 *       （{@link #microCompactorBean_flagOff_stillProjectsHistoricalSnip}）——
 *       这正是 N2 要的语义变化。</li>
 * </ul>
 *
 * <p><b>RED teeth</b>: revert ① {@code LlmAgentLoop} 门① 改回内联公式
 * （不走 {@link BoundaryReader#isHistorySnipEnabled(CompactSettingsResolver, FeatureFlags)}）→
 * {@link #llmAgentLoop_delegatesToBoundaryReader} 必须 fail；② 把 snip 投影改回带开关门控 →
 * {@link #microCompactorBean_flagOff_stillProjectsHistoricalSnip} 必须 fail。
 *
 * <p><b>测试隔离</b>（surefire 默认 forkCount=1 + reuseForks=true，静态槽跨测试类共享）：
 * 本类 {@code @AfterEach} 把三个静态槽复位（见 {@link #restoreStaticSlots()}），否则会让
 * {@code LlmAgentLoopSnipMicroWiringTest.snipGateOn_runsSnip}（断言 state 保留全量）等兄弟测试红。
 */
@DisplayName("[D4] BoundaryReader HISTORY_SNIP 门单一来源 + 静态槽接线（DB 覆写保留）")
class BoundaryReaderHistorySnipSingleSourceTest {

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    /** 静态槽复位：surefire 单 JVM 复用 → 不复位会污染兄弟测试类（见类头 测试隔离）。 */
    @AfterEach
    void restoreStaticSlots() {
        BoundaryReader.setFeatureFlags(null);
        BoundaryReader.setSettingsResolver(null);
        MicroCompactor.setSettingsResolver(null);
        MicroCompactor.setCachedMicrocompactFeatureEnabled(false);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 接线：静态 featureFlags 槽不再是死槽（ToolRegistrationConfig :964 旁）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("接线: microCompactor @Bean 后静态 featureFlags 槽鲜活（[N2] 投影已去门控，本用例不再有区分力）")
    void microCompactorBean_wiresStaticFeatureFlagsSlot() throws Exception {
        // ⚠️ [N2 2026-09-11] 投影改为回放门后，本用例的断言恒成立（不再依赖静态槽接线）——
        //   保留仅为回归「@Bean 仍可被调用、不抛异常」，其原「接线才生效」的区分力已由 N2 移除。
        //   静态槽是否清理由用户拍板，届时本用例应一并处理。
        // WHY: DB settings.history_snip_enabled 为 NULL（resolver 返回 null）+ FeatureFlags.historySnip=true
        //   → 门②必须回落 FeatureFlags（投影像门①一致生效）。修复前静态 featureFlags 槽无写入点
        //   （恒 ALL_DISABLED）→ 投影恒 no-op → 被 snip 删除的消息从 getMessagesAfterCompactBoundary
        //   泄漏（该返回值即 :4738 入口剥离替换 state 的内容 + 4 处静态调用面的模型输入）。
        CompactSettingsResolver resolver = Mockito.mock(CompactSettingsResolver.class);
        when(resolver.historySnipEnabled()).thenReturn(null); // 模拟 DB 列未设

        ToolRegistrationConfig config = new ToolRegistrationConfig();
        setPrivateField(config, "featureFlags", flagsWithHistorySnip());

        // 生产路径：microCompactor @Bean（唯一写入点，Spring 启动一次，早于任何请求）
        config.microCompactor(false, 60, 5, null, resolver);

        List<ChatMessageDto> projected = BoundaryReader.getMessagesAfterCompactBoundary(snipMessages());

        assertThat(ids(projected))
            .as("DB=null 回落 FeatureFlags.historySnip=true → removedUuids 中 u0 必须被剔除"
                + "（CC messages.ts:4648-4653 projectSnippedView；修复前静态槽死 → u0 泄漏）")
            .doesNotContain("u0")
            .contains("u1", "snip-boundary-1");
    }

    @Test
    @DisplayName("[N2 语义反转] HISTORY_SNIP 关（flag=false + DB=null）→ 回放门仍剔除被 snipped 消息")
    void microCompactorBean_flagOff_stillProjectsHistoricalSnip() throws Exception {
        // 门关场景：DB 列 null + FeatureFlags 全关
        CompactSettingsResolver resolver = Mockito.mock(CompactSettingsResolver.class);
        when(resolver.historySnipEnabled()).thenReturn(null);

        ToolRegistrationConfig config = new ToolRegistrationConfig();
        setPrivateField(config, "featureFlags", FeatureFlags.ALL_DISABLED);

        config.microCompactor(false, 60, 5, null, resolver);

        assertThat(ids(BoundaryReader.getMessagesAfterCompactBoundary(snipMessages())))
            .as("[N2 2026-09-11 决策 B「历史 snip 不复活」] 开关只禁「产生新 snip」，不使已执行过的 snip 失效"
                + "——历史 snip_boundary 是既成事实，与 compact boundary 剥离（恒定无门）对称")
            .doesNotContain("u0")
            .contains("u1", "snip-boundary-1");
    }

    @Test
    @DisplayName("[N2 语义反转] 无 snip 历史时门关行为与改前逐字节一致（不误伤）")
    void gateOff_noSnipHistory_behavesAsBefore() {
        List<ChatMessageDto> noSnip = new ArrayList<>();
        noSnip.add(singleMessage("u0", "hi"));
        noSnip.add(singleMessage("u1", "hi"));

        assertThat(ids(BoundaryReader.getMessagesAfterCompactBoundary(noSnip)))
            .as("[N2] 无 snip_boundary → projectSnippedView 收集到空 removedSet 原样返回（projectSnippedView:325-331）"
                + "→ 「门关 + 无历史 snip」零行为变化")
            .containsExactly("u0", "u1");
    }

    @Test
    @DisplayName("[N2] includeSnipped=true（UI/REPL 全量面）→ 仍拿全量，不受回放门影响")
    void includeSnippedTrue_stillFull() {
        assertThat(ids(BoundaryReader.getMessagesAfterCompactBoundary(snipMessages(), true)))
            .as("[N2] includeSnipped 语义不变（CC REPL.tsx:3167-3169 UI 面）→ true 时不做任何投影")
            .contains("u0", "u1", "snip-boundary-1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 双门同源：主循环请求面 + 入口剥离面（DB=null + FeatureFlags.historySnip=true）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("双门同源: DB=null + historySnip=true → 请求面被 snipped 消息剔除（门①②同源不泄漏）")
    void bothGates_agree_snippedMessageNotInLlmRequest() throws Exception {
        // WHY: 门②（入口剥离 :4738）与门①（snip 步骤 :5001）必须同源：DB=null 时二者都回落
        //   FeatureFlags.historySnip。任一门回落死槽都会让被 snip 删除的消息进入模型请求面。
        // 生产接线等价：先跑 microCompactor @Bean（静态槽接线），再驱动主循环（settingsResolver=null）。
        wireProductionStaticSlots(flagsWithHistorySnip());

        AgentState state = new AgentState("sys", "sess-" + UUID.randomUUID().toString().substring(0, 8), null);
        for (ChatMessageDto m : snipMessages()) {
            state.appendMessage(m);
        }
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        LlmProviderFactory factory = capturingProviderFactory(histories);

        AgentLoopContext ctx = TestContexts.agentLoopContext(null, factory, null, null, null,
            flagsWithHistorySnip());
        drive(ctx, state);

        assertThat(histories).as("LLM 至少被调用一次（history 被捕获）").isNotEmpty();
        assertThat(ids(histories.get(histories.size() - 1)))
            .as("请求面（发给 provider 的 history）不得含被 snip 剔除的 u0")
            .doesNotContain("u0");

        // 入口剥离面（:4738 的返回值直接替换 state 前缀）——同一 API 亦被 CompactCommand:215 /
        // PartialCompactService:195 / StreamCompactSummary:744 / SkillifySkillRegistrar:301 消费。
        assertThat(ids(BoundaryReader.getMessagesAfterCompactBoundary(state.rawMessages())))
            .as("入口剥离面（:4738 · 静态槽门②）不得含被 snip 剔除的 u0 —— 修复前静态槽死 → 泄漏")
            .doesNotContain("u0")
            .contains("snip-boundary-1");
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. 唯一纯函数（DB 覆盖语义 + null 安全 + 实时读不缓存）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("纯函数: DB 非 null 覆盖 flag（true/false 双向）；DB=null 回落 flag")
    void pureFunction_dbOverridesFlag_bothDirections() {
        FeatureFlags flagOn = flagsWithHistorySnip();

        CompactSettingsResolver dbFalse = Mockito.mock(CompactSettingsResolver.class);
        when(dbFalse.historySnipEnabled()).thenReturn(false);
        assertThat(BoundaryReader.isHistorySnipEnabled(dbFalse, flagOn))
            .as("DB=false 覆盖 FeatureFlags.historySnip=true（DB 主控，nexusai 扩展语义保留）")
            .isFalse();

        CompactSettingsResolver dbNull = Mockito.mock(CompactSettingsResolver.class);
        when(dbNull.historySnipEnabled()).thenReturn(null);
        assertThat(BoundaryReader.isHistorySnipEnabled(dbNull, flagOn))
            .as("DB=null 回落 FeatureFlags.historySnip=true")
            .isTrue();
        assertThat(BoundaryReader.isHistorySnipEnabled(dbNull, FeatureFlags.ALL_DISABLED))
            .as("DB=null + flag 关 → false（CC flag-off 等价）")
            .isFalse();

        CompactSettingsResolver dbTrue = Mockito.mock(CompactSettingsResolver.class);
        when(dbTrue.historySnipEnabled()).thenReturn(true);
        assertThat(BoundaryReader.isHistorySnipEnabled(dbTrue, FeatureFlags.ALL_DISABLED))
            .as("DB=true 覆盖 flag 关（DB 主控）")
            .isTrue();
    }

    @Test
    @DisplayName("纯函数 null 安全: resolver=null / flags=null 不 NPE")
    void pureFunction_nullSafe() {
        assertThat(BoundaryReader.isHistorySnipEnabled(null, flagsWithHistorySnip()))
            .as("resolver=null（未接线）→ 回落 flags")
            .isTrue();
        assertThat(BoundaryReader.isHistorySnipEnabled(null, null))
            .as("双 null → false（视作全关，不 NPE）")
            .isFalse();
    }

    @Test
    @DisplayName("纯函数: resolver 每次调用实时读 DB（不缓存进静态槽）")
    void pureFunction_readsResolverEveryCall() {
        // WHY: DB 覆盖必须实时（前端 PUT settings 后下一轮生效）——若把首次结果缓存，
        //   "改库即生效" 契约破裂。
        CompactSettingsResolver resolver = Mockito.mock(CompactSettingsResolver.class);
        when(resolver.historySnipEnabled()).thenReturn(false, true);

        assertThat(BoundaryReader.isHistorySnipEnabled(resolver, flagsWithHistorySnip()))
            .as("第 1 次调用：DB=false 覆盖").isFalse();
        assertThat(BoundaryReader.isHistorySnipEnabled(resolver, flagsWithHistorySnip()))
            .as("第 2 次调用：DB 改判 true → 实时生效（无缓存）").isTrue();
        Mockito.verify(resolver, Mockito.times(2)).historySnipEnabled();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. 门① 静态守卫（LlmAgentLoop 不得回退内联重复公式）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("静态守卫: LlmAgentLoop snip 门必须委托 BoundaryReader.isHistorySnipEnabled（双源合一）")
    void llmAgentLoop_delegatesToBoundaryReader() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(LLM_LOOP_PATH));
        assertThat(source)
            .as("门①必须委托 BoundaryReader.isHistorySnipEnabled(settingsResolver, ctx.featureFlags())"
                + "（双门同源；内联重复公式即回归）")
            .contains("BoundaryReader.isHistorySnipEnabled(settingsResolver, ctx.featureFlags())");
    }

    // ════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════

    /** 生产等价接线：ToolRegistrationConfig.microCompactor @Bean（静态槽唯一写入点）。 */
    private static void wireProductionStaticSlots(FeatureFlags flags) throws Exception {
        CompactSettingsResolver resolver = Mockito.mock(CompactSettingsResolver.class);
        when(resolver.historySnipEnabled()).thenReturn(null);
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        setPrivateField(config, "featureFlags", flags);
        config.microCompactor(false, 60, 5, null, resolver);
    }

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 24 参 canonical 构造：仅 historySnip(pos6)=true，其余全 false。 */
    private static FeatureFlags flagsWithHistorySnip() {
        return new FeatureFlags(false, false, false, false, false, true, false, false, false, false,
            false, false, false, false, false, false, false, false, false, false, false, false, false, false);
    }

    /** [u0, u1, snip_boundary(removedUuids=[u0])] · 无 compact boundary（走全量切片 + snip 投影）。 */
    private static List<ChatMessageDto> snipMessages() {
        List<ChatMessageDto> msgs = new ArrayList<>();
        msgs.add(singleMessage("u0", "hi"));
        msgs.add(singleMessage("u1", "hi"));
        msgs.add(snipBoundary("snip-boundary-1", List.of("u0")));
        return msgs;
    }

    private static void drive(AgentLoopContext ctx, AgentState state) {
        LoopDeps deps = new LoopDeps() {
            @Override public AgentLoopContext context() { return ctx; }
            @Override public boolean isMainLoop() { return true; }
        };
        QueryParams params = QueryParams.forLoop(
            state.rawMessages(), null,
            ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8)),
            QuerySource.USER, "test-model", null, null, null, null, null,
            deps, ProviderConfig.empty());
        LlmAgentLoop.queryLoop(params, state, new ArrayList<>());
    }

    private static List<String> ids(List<ChatMessageDto> messages) {
        return messages.stream().map(m -> m.id() != null ? m.id() : "").toList();
    }

    private static ChatMessageDto singleMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /** 37 参 canonical 构造 snip_boundary（subtype + snipMetadata 承载，CC snipCompact.ts:99-106）。 */
    private static ChatMessageDto snipBoundary(String id, List<String> removedUuids) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("removedUuids", removedUuids);
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

    /** provider 正常完成 + 捕获每次 LLM 调用的 history（stream arg3）。 */
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
            any(), anyString(), anyList(), anyList(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any());
        LlmProviderFactory factory = Mockito.mock(LlmProviderFactory.class);
        when(factory.getProvider(any(), any())).thenReturn(provider);
        return factory;
    }
}
