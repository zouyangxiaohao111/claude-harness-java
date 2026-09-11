package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.command.CompactCommand;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactConversationContext;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolUseContext;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P3-a] 手工 /compact 的模型装配 + 协议回落方向统一 · 意图测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图而非行为）</b>
 * <ol>
 *   <li><b>翻倍根因</b>：手工 /compact 的上下文装配
 *       （{@code ToolRegistrationConfig.buildCompactConversationContext}）从不 {@code setModel} →
 *       {@code ctx.getModel()=null} → {@code CompactConversation.resolveAnthropic(null)} 旧实现
 *       恒回落 {@code true}（按 Anthropic 4 项和）→ DeepSeek 会话
 *       {@code preTokens = input + output + cache_read + cache_creation}
 *       而 DeepSeek 的 {@code input} <b>已含 cache</b> → 实测 preTokens=188374 ≈ 2× 真实值 94625
 *       （input 93749 + output 876）。auto 路径显式 {@code .setModel(this.model)}
 *       （AutoCompactor.java，源 = 主循环 effectiveModel）故不翻倍 —— 只有手工路径翻倍。</li>
 *   <li><b>回落方向相反</b>：{@code CompactConversation.resolveAnthropic} 判不出时回落
 *       {@code true}（Anthropic），而唯一权威 {@code ContextUsageCalculator.isAnthropic}
 *       判不出时回落 {@code false} —— 两处相反。本测试钉死「委托唯一权威」，
 *       任何人把回落改回 {@code true} 即 RED。</li>
 * </ol>
 *
 * <p><b>RED 条件（逐条，对应变异点）</b>
 * <ul>
 *   <li>{@code manualCompactContextCarriesSessionModel}：删掉
 *       {@code buildCompactConversationContext} 里的 {@code cc.setModel(model)} → {@code getModel()} null → 红。</li>
 *   <li>{@code manualModelSourceIsAgentStateCurrentModel}：模型源改成常量/改写为不读
 *       {@code state.currentModel()}（如只读 TUC 或恒 null）→ 红。</li>
 *   <li>{@code fallbackDirectionMatchesContextUsageCalculator}：把
 *       {@code resolveAnthropic} 改回「mapper/model 不可得 → return true」早退 → 红。</li>
 *   <li>{@code deepseekSessionSkipsFourFieldSum}：把 {@code resolveAnthropic} 改成恒 {@code true}
 *       （= 旧「一律按 Anthropic」语义）→ deepseek 断言红；{@code TokenUsage.total(false)} 为
 *       A5-2 既有纯函数（非本次改动），其 4 项和对照防「求和公式」反转。</li>
 * </ul>
 *
 * <p><b>隔离</b>：{@code CompactConversation.modelMapper/providerMapper} 是进程级静态槽，
 * 本用例 {@code @BeforeEach} 快照 / {@code @AfterEach} 还原，绝不把注入值泄漏给同 JVM 其他用例。
 */
@DisplayName("[P3-a] manual /compact 模型装配 + 回落方向统一")
class ManualCompactModelWiringTest {

    private static final String SESSION = "sess-p3a";
    private static final String AGENT = "a-1";
    private static final String DEEPSEEK = "deepseek/deepseek-v4-flash";

    /** 静态槽快照（还原用）· 防本用例注入的 mock mapper 泄漏到同 JVM 其他测试类。 */
    private Object savedModelMapper;
    private Object savedProviderMapper;

    @BeforeEach
    void snapshotStaticMappers() throws Exception {
        savedModelMapper = readStaticMapper("modelMapper");
        savedProviderMapper = readStaticMapper("providerMapper");
    }

    @AfterEach
    void restoreStaticMappers() throws Exception {
        writeStaticMapper("modelMapper", savedModelMapper);
        writeStaticMapper("providerMapper", savedProviderMapper);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. 装配：手工 /compact 的 CompactConversationContext 必须带模型
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("手工 /compact 装配的 ctx.model = 传入的会话模型（非 null —— 旧实现恒 null → deepseek 翻倍）")
    void manualCompactContextCarriesSessionModel() {
        ToolRegistrationConfig config = new ToolRegistrationConfig();

        CompactCommand.CompactCommandContext commandCtx = config.buildCompactCommandContext(
            List.of(msg("m1", Role.user, "hi")), SESSION, AGENT, DEEPSEEK,
            null,   // reactiveCompactor
            null,   // streamCompactSummary
            null,   // sessionMemoryService
            null,   // toolUseContext
            null,   // sysPromptCtxProvider
            null,   // defaultSysPromptAssemble
            null,   // customSystemPrompt
            null,   // appendSystemPrompt
            false,  // useGlobalCacheScope
            null);  // telemetry

        CompactConversationContext cc = commandCtx.compactConversationContextSupplier().get();

        assertThat(cc.getModel())
            .as("手工 /compact 的 ctx.model 必须是本会话有效模型：null → resolveAnthropic 不可分派 → "
                + "deepseek 会话走 Anthropic 4 项和 → preTokens 翻倍（DB 实证 188374 ≈ 2×94625）")
            .isEqualTo(DEEPSEEK);
    }

    @Test
    @DisplayName("模型源 = AgentState.currentModel()（与 auto 路径 resolveTurnEffectiveModel 同口径），"
        + "缺失时回落 per-turn TUC.effectiveModelName，两者皆无 → null")
    void manualModelSourceIsAgentStateCurrentModel() {
        // 主源：state.currentModel()（LlmAgentLoop 每轮 effectiveModel 覆盖写，含 fallbackModel）
        AgentState state = new AgentState("sys", SESSION, null);
        state.setCurrentModel(DEEPSEEK);
        assertThat(ToolRegistrationConfig.resolveManualCompactModel(state))
            .as("主源必须是 AgentState.currentModel()（auto 路径 RecoveryState.getCurrentModel() 等价物）")
            .isEqualTo(DEEPSEEK);

        // 回落链：currentModel 缺失 → per-turn TUC.effectiveModelName（AgentLoopContext 由 currentModel 写入）
        state.setCurrentModel(null);
        state.setCurrentToolUseContext(new ToolUseContext(
            UUID.randomUUID(), SESSION, PermissionMode.DEFAULT, Map.of(), List.of(), "",
            new AbortController(), List.of()).withEffectiveModelName("anthropic/claude-sonnet-4-6"));
        assertThat(ToolRegistrationConfig.resolveManualCompactModel(state))
            .as("currentModel 缺失 → 回落 TUC.effectiveModelName（不返 null）")
            .isEqualTo("anthropic/claude-sonnet-4-6");

        // 两者皆不可得（空闲/DB 重建 state）→ null → 交 resolveAnthropic 统一回落非 Anthropic
        state.setCurrentToolUseContext(null);
        assertThat(ToolRegistrationConfig.resolveManualCompactModel(state))
            .as("判不出来 → null（回落由 CompactConversation 侧唯一权威决定，不在此处臆断协议）")
            .isNull();
        assertThat(ToolRegistrationConfig.resolveManualCompactModel(null))
            .as("state 为 null（防御）→ null")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. 回落方向统一：resolveAnthropic ≡ ContextUsageCalculator.isAnthropic
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("mapper/模型不可得 → resolveAnthropic 与 ContextUsageCalculator.isAnthropic 同向（false），不再回落 anthropic")
    void fallbackDirectionMatchesContextUsageCalculator() throws Exception {
        CompactConversation.setMappers(null, null); // 未注入 = mapper 不可得（生产 mapper bean 缺失/测试直构）

        assertThat(invokeResolveAnthropic(null))
            .as("旧实现 model=null → 回落 true（Anthropic 4 项和）→ 手工 /compact 翻倍根因；"
                + "现必须与唯一权威同向 = false")
            .isFalse();
        assertThat(invokeResolveAnthropic("   "))
            .as("blank 模型名 → 同样不可判定 → false")
            .isFalse();
        assertThat(invokeResolveAnthropic(null))
            .as("与唯一权威逐值一致（不是各判一套）")
            .isEqualTo(ContextUsageCalculator.isAnthropic(null, null, null));

        // 已注入 mapper 但模型不可判定（未命中）→ 同样 false，且与唯一权威一致
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        when(mm.selectListByQuery(any())).thenReturn(List.of());
        CompactConversation.setMappers(mm, pm);
        assertThat(invokeResolveAnthropic("unknown-model"))
            .as("模型未命中 → false（与 ContextUsageCalculator.isAnthropic 同向）")
            .isFalse();
        assertThat(invokeResolveAnthropic("unknown-model"))
            .isEqualTo(ContextUsageCalculator.isAnthropic(mm, pm, "unknown-model"));
    }

    @Test
    @DisplayName("deepseek 会话 → 非 Anthropic（false）→ 求和 = input+output，不再 4 项和（真实 boundary 数字）")
    void deepseekSessionSkipsFourFieldSum() throws Exception {
        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setType("openai_compatible");
        ModelRecord model = new ModelRecord();
        model.setId("m1");
        model.setProviderId("p1");
        model.setName("deepseek-v4-flash");
        model.setEnabled(true);
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        when(mm.selectOneByQuery(any())).thenReturn(model);
        when(pm.selectOneByQuery(any())).thenReturn(provider);
        when(pm.selectOneById(any())).thenReturn(provider);
        CompactConversation.setMappers(mm, pm);

        assertThat(invokeResolveAnthropic(DEEPSEEK))
            .as("deepseek provider.type=openai_compatible → false（input 已含 cache，不得 4 项和）")
            .isFalse();
        assertThat(invokeResolveAnthropic(DEEPSEEK))
            .isEqualTo(ContextUsageCalculator.isAnthropic(mm, pm, DEEPSEEK));

        // 真实 boundary 数字（DB 实证）：4 项和=188374（翻倍）vs 非 anthropic=input+output=94625
        CompactConversation.TokenUsage realBoundary = new CompactConversation.TokenUsage(93749, 876, 93568, 181);
        assertThat(realBoundary.total(false))
            .as("deepseek 求和 = input 93749 + output 876 = 94625（真实上下文）")
            .isEqualTo(94625);
        assertThat(realBoundary.total(true))
            .as("对照：anthropic 4 项和 = 188374（旧行为，≈2×）")
            .isEqualTo(188374);
    }

    @Test
    @DisplayName("anthropic 会话仍走 4 项和（委托不是「恒 false」——防过度修复反转另一侧）")
    void anthropicSessionKeepsFourFieldSum() throws Exception {
        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setType("anthropic");
        ModelRecord model = new ModelRecord();
        model.setId("m1");
        model.setProviderId("p1");
        model.setName("claude-sonnet-4-6");
        model.setEnabled(true);
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        when(mm.selectOneByQuery(any())).thenReturn(model);
        when(pm.selectOneByQuery(any())).thenReturn(provider);
        when(pm.selectOneById(any())).thenReturn(provider);
        CompactConversation.setMappers(mm, pm);

        assertThat(invokeResolveAnthropic("anthropic/claude-sonnet-4-6"))
            .as("provider.type=anthropic → true（Claude usage 三字段独立，仍需 4 项和）")
            .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试脚手架
    // ════════════════════════════════════════════════════════════════════

    /** 反射调 {@code CompactConversation.resolveAnthropic}（compact 包 package-private）。 */
    private static boolean invokeResolveAnthropic(String model) throws Exception {
        Method m = CompactConversation.class.getDeclaredMethod("resolveAnthropic", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, model);
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

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }
}
