package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.compact.TokenEstimator;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentLoopContext.extractContextUsage 测试（snip-nudge-percent 2026-09-13）。
 *
 * <p>WHY（CLAUDE.md 规则 9）：nudge 判据的「已用 token」必须取**真实 API 返回的 usage**，不是估算。
 * 两条边界决定判据对错：
 * ① 无 usage → 必须返回 null（不是 EMPTY），否则 snapshot 会算出 used=0 → 剩余 100%，
 *    在阈值被配成 100 时行为与「无数据」相反；
 * ② DB 水合消息的 usage().cacheXxx 恒为 null（withUsage 硬编码 cache 为 null），必须从 ChatMessageDto
 *    的 cache 组件回填，否则 Anthropic 协议下少算 cache → used 偏小 → 剩余偏大 → 该提示时不提示。
 */
class ContextUsageRemainingPercentTest {

    // ══════════ 消息构造 ══════════
    // 本仓无共享消息工厂：每个测试类自带 private static helper（全树无测试基类，TestContexts 也不含
    // ChatMessageDto helper）。用 17 参兼容构造器（全树约 150 个测试都在用它）：
    //   (id, sessionId, role, author, content, reasoning, toolCalls, finishReason,
    //    inputTokens, outputTokens, time, createdAt, toolCallId, assistantMessageId,
    //    acceptFeedback, contentBlocks, imagePasteIds)

    private static ChatMessageDto assistantText(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.assistant, "assistant", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(),
            FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    /**
     * 带 usage 的 assistant（走 withUsage 投影）。
     *
     * <p>⚠️ {@code AgentUsage} 7 参兼容构造器顺序 = {@code (input, output, cacheCreation, cacheRead,
     * serverToolUse, serviceTier, cacheCreationObj)} —— <b>cacheCreation 在 cacheRead 之前</b>，极易写反。
     */
    private static ChatMessageDto assistantWithUsage(String id, AgentUsage usage) {
        return assistantText(id, "reply").withUsage(usage);
    }

    /**
     * DB 水合形态：{@code usage()==null}，仅 inputTokens/outputTokens 有值。
     *
     * <p>注意 {@code withUsage(null)} 会**短路 {@code return this}**（不产生新实例），表达不了这一形态
     * —— 必须走构造器直写第 9/10 参。
     */
    private static ChatMessageDto hydratedAssistant(String id, Integer in, Integer out) {
        return new ChatMessageDto(
            id, null, Role.assistant, "assistant", "reply", null, List.of(),
            FinishReason.stop, in, out, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    @Test
    @DisplayName("无 assistant 消息 → null（不是 EMPTY：EMPTY 会让 snapshot 算出剩余 100%）")
    void noAssistant_returnsNull() {
        assertThat(AgentLoopContext.extractContextUsage(List.of(userMessage("u1", "hi")))).isNull();
        assertThat(AgentLoopContext.extractContextUsage(List.of())).isNull();
        assertThat(AgentLoopContext.extractContextUsage(null)).isNull();
    }

    @Test
    @DisplayName("assistant 无 usage 且无 input/output → 继续向前找，最终 null")
    void assistantWithoutAnyTokens_returnsNull() {
        assertThat(AgentLoopContext.extractContextUsage(List.of(assistantText("a1", "x")))).isNull();
    }

    @Test
    @DisplayName("assistant 有 input/output 但 usage 为 null → fromInputOutput 投影（DB 水合形态）")
    void assistantWithTokensOnly_projectsFromInputOutput() {
        AgentUsage u = AgentLoopContext.extractContextUsage(List.of(hydratedAssistant("a1", 1000, 50)));
        assertThat(u).isNotNull();
        assertThat(u.inputTokens()).isEqualTo(1000L);
        assertThat(u.outputTokens()).isEqualTo(50L);
    }

    @Test
    @DisplayName("回扫取最近一条 assistant（跳过更早的）")
    void scansBackwards_takesMostRecent() {
        AgentUsage u = AgentLoopContext.extractContextUsage(List.of(
            assistantWithUsage("a1", new AgentUsage(111L, 1L, null, null, null, null, null)),
            userMessage("u1", "hi"),
            assistantWithUsage("a2", new AgentUsage(222L, 2L, null, null, null, null, null))));
        assertThat(u).isNotNull();
        assertThat(u.inputTokens()).isEqualTo(222L);
    }

    @Test
    @DisplayName("usage 的 cache 字段为 null 时，从 ChatMessageDto 的 cache 组件回填")
    void cacheFields_backfilledFromDtoComponents() {
        ChatMessageDto msg = assistantText("a1", "reply")
            .withUsage(new AgentUsage(1000L, 50L, null, null, null, null, null))
            .withUsageCache(800, 100);   // 顺序不可反：withUsage 会把 DTO 的 cache 组件清成 null
        AgentUsage u = AgentLoopContext.extractContextUsage(List.of(msg));
        assertThat(u).isNotNull();
        assertThat(u.cacheReadInputTokens()).as("DTO 组件回填").isEqualTo(800L);
        assertThat(u.cacheCreationInputTokens()).as("DTO 组件回填").isEqualTo(100L);
    }

    @Test
    @DisplayName("usage 自带 cache 时优先用 usage 的值，不被 DTO 组件覆盖")
    void cacheFields_preferUsageOverDto() {
        ChatMessageDto msg = assistantText("a1", "reply")
            // AgentUsage 7 参顺序 = (input, output, cacheCreation, cacheRead, serverToolUse, serviceTier, cacheCreationObj)
            .withUsage(new AgentUsage(1000L, 50L, 111L, 222L, null, null, null))
            .withUsageCache(800, 100);
        AgentUsage u = AgentLoopContext.extractContextUsage(List.of(msg));
        assertThat(u.cacheReadInputTokens()).isEqualTo(222L);
        assertThat(u.cacheCreationInputTokens()).isEqualTo(111L);
    }

    // ══════════ ctx 构造 ══════════
    // 惯用法（照抄 backend/src/test/java/com/nexusai/application/agent/compact/ContextUsageCalculatorTest.java）：
    // **不是**真 SQLite、**不是** @TempDir、**不是** Spring。
    // ModelMapper / ProviderMapper 都只是 extends BaseMapper<T> 的空接口，全部查询靠 mock + any() 通配桩。

    /**
     * 造「窗口=window、provider.type=providerType」可被 {@code ModelNameResolver.resolve} 解析的 ctx。
     *
     * <p>打桩要点（抄错任一处即静默失效）：
     * <ul>
     *   <li>providerId 必须写字面量 {@code "p1"} —— {@code ContextUsageCalculator.isAnthropic} 用
     *       {@code selectOneById(model.getProviderId())} **精确串**匹配，不是 {@code any()}</li>
     *   <li>{@code provider.setType(...)} 是必须的一步：既有 helper {@code TestContexts.tokenBudgetBeans}
     *       不设 type → 恒 null → isAnthropic 恒 false</li>
     *   <li>{@code window == null} → 不设 maxContextTokens（模拟「窗口未配置」→ snapshot 回落 1_048_576）</li>
     *   <li>{@code ModelRecord.maxContextTokens} 是 Integer → 入参需 {@code .intValue()} 收窄</li>
     * </ul>
     */
    private static AgentLoopContext ctxWithWindow(Long window, String providerType) {
        ModelRecord model = new ModelRecord();
        model.setId("m1");
        model.setProviderId("p1");
        model.setName("deepseek-v4-flash");
        model.setEnabled(true);
        if (window != null) {
            model.setMaxContextTokens(window.intValue());
        }
        ModelMapper modelMapper = mock(ModelMapper.class);
        when(modelMapper.selectOneByQuery(any())).thenReturn(model);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model));

        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setType(providerType);
        ProviderMapper providerMapper = mock(ProviderMapper.class);
        when(providerMapper.selectOneById("p1")).thenReturn(provider);
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider);

        AgentLoopContext.TokenBudgetBeans beans = new AgentLoopContext.TokenBudgetBeans(
            mock(TokenEstimator.class), modelMapper, providerMapper);
        return TestContexts.agentLoopContext(null, null, null, null, beans);
    }

    @Test
    @DisplayName("无 usage / beans 缺失 / 模型名为空 → null（调用方据此不注入）")
    void missingData_returnsNull() {
        AgentLoopContext noBeans = TestContexts.agentLoopContext(null, null, null, null, null);
        assertThat(AgentLoopContext.contextRemainingPercent(noBeans, "deepseek/deepseek-v4-flash",
            List.of(hydratedAssistant("a1", 1000, 50)))).isNull();
        assertThat(AgentLoopContext.contextRemainingPercent(null, "m", List.of())).isNull();
        assertThat(AgentLoopContext.contextRemainingPercent(noBeans, "  ", List.of())).isNull();
    }

    @Test
    @DisplayName("非 anthropic：窗口 200k、input 150k → 剩余 25%（output 不计入上下文占用）")
    void nonAnthropic_usedIsInputOnly() {
        AgentLoopContext ctx = ctxWithWindow(200_000L, "openai_compatible");
        // 消息**必须带 cache 组件**，否则 anthropic 分支（input+cacheRead+cacheCreate）与
        // non-anthropic 分支（input）恒等 → 本用例对「协议分派」零鉴别力（只有对「output 不计入」有）。
        // 带 cache 后：误判为 anthropic → used=150000+40000+10000=200000 → 剩余 0（红）；正确 → 25（绿）。
        assertThat(AgentLoopContext.contextRemainingPercent(ctx, "deepseek/deepseek-v4-flash",
            List.of(hydratedAssistant("a1", 150_000, 9_999).withUsageCache(40_000, 10_000))))
            .as("used=150000（不含 output 9999，也不含 cache —— DeepSeek 的 prompt_tokens 已含 cache hit）"
                + "→ round((1-150000/200000)*100) = 25")
            .isEqualTo(25);
    }

    @Test
    @DisplayName("anthropic：窗口 200k、input 100k + cacheRead 40k + cacheCreate 10k → 剩余 25%")
    void anthropic_usedIncludesCache() {
        AgentLoopContext ctx = ctxWithWindow(200_000L, "anthropic");
        // AgentUsage 7 参顺序 = (input, output, cacheCreation, cacheRead, serverToolUse, serviceTier, cacheCreationObj)
        ChatMessageDto msg = assistantWithUsage("a1",
            new AgentUsage(100_000L, 9_999L, 10_000L, 40_000L, null, null, null));
        assertThat(AgentLoopContext.contextRemainingPercent(ctx, "claude-x", List.of(msg)))
            .as("used=100000+40000(cacheRead)+10000(cacheCreate)=150000 → 剩余 25%")
            .isEqualTo(25);
    }

    @Test
    @DisplayName("窗口未配置（maxContextTokens=null）→ 回落 1_048_576，不返回 null 也不炸")
    void windowUnresolvable_fallsBackTo1M() {
        AgentLoopContext ctx = ctxWithWindow(null, "openai_compatible");
        assertThat(AgentLoopContext.contextRemainingPercent(ctx, "deepseek/deepseek-v4-flash",
            List.of(hydratedAssistant("a1", 1_000_000, 100))))
            .as("回落窗口 1048576：used=1000000 → round((1-1000000/1048576)*100) = round(4.63) = 5")
            .isEqualTo(5);
    }
}
