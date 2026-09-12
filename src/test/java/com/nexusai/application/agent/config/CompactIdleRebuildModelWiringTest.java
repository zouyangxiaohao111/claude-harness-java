package com.nexusai.application.agent.config;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.command.CompactCommand;
import com.nexusai.application.agent.compact.CompactConversation;
import com.nexusai.application.agent.compact.CompactConversationContext;
import com.nexusai.application.agent.compact.ContextUsageCalculator;
import com.nexusai.application.agent.compact.Tokens;
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
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [P3-a-2] 空闲/DB 重建路径的压缩模型装配 · 意图测试。
 *
 * <h2>WHY（CLAUDE.md 规则九 · 测试验证意图）</h2>
 * <b>真实缺陷</b>：[P3-a] 把手工 {@code /compact} 的模型源收敛到
 * {@code ToolRegistrationConfig.resolveManualCompactModel(state)} 后，<b>空闲会话</b>
 * （registry miss → {@link ToolRegistrationConfig#rebuildIdleStateFromDb} 临时重建）的 state
 * <b>没有</b> {@code currentModel()}（{@code LlmAgentLoop.doRun:2461} 只对 live 会话写）→ 模型返 null
 * → {@code CompactConversation.resolveAnthropic(null)} 按唯一权威回落 <b>非 Anthropic</b> →
 * <ul>
 *   <li>deepseek 会话：<b>结果正确</b>（DeepSeek 的 {@code prompt_tokens} 已含 cache hit，
 *       只该算 {@code input + output}）；</li>
 *   <li><b>anthropic 会话：少计</b>（Claude usage 三字段独立，该算
 *       {@code input + cacheRead + cacheCreate + output} 4 项和却只算了 input + output）→
 *       preTokens 偏小 → 阈值/展示系统性低估。</li>
 * </ul>
 *
 * <h2>RED 条件（逐条，对应变异点）</h2>
 * <ol>
 *   <li>删掉 {@code rebuildIdleStateFromDb} 里的 {@code rebuilt.setCurrentModel(resolveSessionModelName(...))}
 *       → {@link #idleRebuildCarriesSessionModel_anthropicGoesFourFieldSum} 的
 *       {@code state.currentModel()} / {@code ctx.getModel()} / {@code preTokens=188374} 三条断言全红
 *       （退化回 input+output=94625）；而 {@link #idleRebuildCarriesSessionModel_deepseekStaysNonAnthropic}
 *       仍绿 —— 正是「缺陷只在 anthropic 侧显形」的实证。</li>
 *   <li>把 {@code resolveSessionModelName} 的会话层去掉（只读 settings）→
 *       {@link #sessionOverrideWins_overSettingsMainModel} 红。</li>
 *   <li>把 settings 回落去掉（返 null）→ {@link #fallsBackToSettingsMainModel} 红。</li>
 *   <li>把「两处皆空 → null」改成臆断任一协议（如返 {@code true} 的哨兵名）→
 *       {@link #noModelAnywhere_fallsBackConsistentWithIsAnthropic} 红。</li>
 * </ol>
 *
 * <p><b>隔离</b>：{@code CompactConversation.modelMapper/providerMapper} 是进程级静态槽，
 * 本用例 {@code @BeforeEach} 快照 / {@code @AfterEach} 还原（同 {@code ManualCompactModelWiringTest} 手法），
 * 绝不把注入值泄漏给同 JVM 其他用例。
 */
@DisplayName("[P3-a-2] 空闲/DB 重建压缩的模型装配 + 协议分派")
class CompactIdleRebuildModelWiringTest {

    private static final String SESSION = "sess-p3a2-idle";
    private static final String AGENT = "a-1";
    private static final String ANTHROPIC = "anthropic/claude-sonnet-4-6";
    private static final String DEEPSEEK = "deepseek/deepseek-v4-flash";
    private static final String SETTINGS_ANTHROPIC = "anthropic/claude-opus-4-6";

    /** DB 实证数字（P3-a 报告 · 真实 boundary）：4 项和 vs input+output。 */
    private static final int ANTHROPIC_4_FIELD_SUM = 93749 + 93568 + 181 + 876; // 188374
    private static final int DEEPSEEK_INPUT_OUTPUT = 93749 + 876;               // 94625

    private Object savedModelMapper;
    private Object savedProviderMapper;

    @BeforeEach
    void snapshotStaticMappers() throws Exception {
        savedModelMapper = readStaticMapper("modelMapper");
        savedProviderMapper = readStaticMapper("providerMapper");
        // 与生产同形态：/compact 行已落库（ChatController:149）且经 requestId 带下来
        // （ChatService.processUserMessage:624）→ 重建时按守卫排除它在途行，压缩输入不含自身
        RequestContext.set(SESSION, "h3");
    }

    @AfterEach
    void restoreStaticMappersAndContext() throws Exception {
        writeStaticMapper("modelMapper", savedModelMapper);
        writeStaticMapper("providerMapper", savedProviderMapper);
        RequestContext.clear();
    }

    // ════════════════════════════════════════════════════════════════════
    // 1 · 空闲重建 state = 会话模型 → 装配进 ctx → 协议分派 → 求和口径
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("anthropic 会话：重建 state 带模型 → ctx.model 同值 → 4 项和（188374，非 94625）")
    void idleRebuildCarriesSessionModel_anthropicGoesFourFieldSum() {
        MessageService messageService = idleMessageService();
        ToolRegistrationConfig config = configWith(sessionRecord(ANTHROPIC), null);

        AgentState rebuilt = config.rebuildIdleStateFromDb(SESSION, messageService);

        assertThat(rebuilt).as("DB 历史非空 → 必须重建出 state（前置）").isNotNull();
        assertThat(rebuilt.currentModel())
            .as("重建 state 必须带会话模型（sessions.model_name）—— 不写则 null → 协议不可分派 → anthropic 少计")
            .isEqualTo(ANTHROPIC);

        // 与 handleCompactCommand:2279 同一调用形态（模型经 resolveManualCompactModel 求值）
        String model = ToolRegistrationConfig.resolveManualCompactModel(rebuilt);
        CompactConversationContext cc = compactContextOf(config, rebuilt, model);

        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        stubAnthropicModel(mm, pm);

        assertThat(cc.getModel())
            .as("压缩 ctx 必须带上重建出来的会话模型（旧实现：临时 state 无 currentModel → ctx.model=null）")
            .isEqualTo(ANTHROPIC);
        assertThat(ContextUsageCalculator.isAnthropic(mm, pm, cc.getModel()))
            .as("anthropic provider → 4 项和分派")
            .isTrue();
        assertThat(Tokens.tokenCountWithEstimation(rebuilt.rawMessages(),
                ContextUsageCalculator.isAnthropic(mm, pm, cc.getModel())))
            .as("压缩前 token = Claude usage 三字段独立 → input+cacheRead+cacheCreate+output = 188374"
                + "（若模型缺失 → 回落非 Anthropic → 只得 input+output=94625 = 少计）")
            .isEqualTo(ANTHROPIC_4_FIELD_SUM);
    }

    @Test
    @DisplayName("deepseek 会话：重建 state 带模型 → 非 4 项和（94625）—— 修复不动 deepseek 结果")
    void idleRebuildCarriesSessionModel_deepseekStaysNonAnthropic() {
        MessageService messageService = idleMessageService();
        ToolRegistrationConfig config = configWith(sessionRecord(DEEPSEEK), null);

        AgentState rebuilt = config.rebuildIdleStateFromDb(SESSION, messageService);
        CompactConversationContext cc =
            compactContextOf(config, rebuilt, ToolRegistrationConfig.resolveManualCompactModel(rebuilt));

        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        stubOpenAiCompatibleModel(mm, pm);

        assertThat(cc.getModel()).isEqualTo(DEEPSEEK);
        assertThat(ContextUsageCalculator.isAnthropic(mm, pm, cc.getModel()))
            .as("deepseek provider.type=openai_compatible → 非 Anthropic（prompt_tokens 已含 cache hit）")
            .isFalse();
        assertThat(Tokens.tokenCountWithEstimation(rebuilt.rawMessages(),
                ContextUsageCalculator.isAnthropic(mm, pm, cc.getModel())))
            .as("deepseek 求和 = input 93749 + output 876 = 94625（4 项和 188374 会把 cache read 双计）")
            .isEqualTo(DEEPSEEK_INPUT_OUTPUT);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2 · 回落链（会话 override 优先 / settings 兜底 / 两处皆空）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("sessions.model_name 优先于 settings.main_model_name（会话 override 语义）")
    void sessionOverrideWins_overSettingsMainModel() {
        ToolRegistrationConfig config =
            configWith(sessionRecord(ANTHROPIC), SETTINGS_ANTHROPIC);

        assertThat(config.resolveSessionModelName(SESSION))
            .as("会话 override（sessions.model_name）必须胜过全局 settings.main_model_name")
            .isEqualTo(ANTHROPIC);
        AgentState rebuilt = config.rebuildIdleStateFromDb(SESSION, idleMessageService());
        assertThat(rebuilt.currentModel())
            .as("重建 state 的模型 = 会话 override（同 resolveSessionModelName）")
            .isEqualTo(ANTHROPIC);
    }

    @Test
    @DisplayName("sessions.model_name 为空 → 回落 settings.main_model_name（与 auto 路径四层链的 settings 层同源）")
    void fallsBackToSettingsMainModel() {
        ToolRegistrationConfig config = configWith(sessionRecord(null), SETTINGS_ANTHROPIC);

        assertThat(config.resolveSessionModelName(SESSION))
            .as("会话无 override → 必须回落 settings.main_model_name（否则 anthropic 会话仍少计）")
            .isEqualTo(SETTINGS_ANTHROPIC);
        AgentState rebuilt = config.rebuildIdleStateFromDb(SESSION, idleMessageService());
        assertThat(rebuilt.currentModel()).isEqualTo(SETTINGS_ANTHROPIC);
    }

    @Test
    @DisplayName("两处皆空 / mapper 缺失 → null（不臆断协议）→ 分派方向与 ContextUsageCalculator.isAnthropic 一致")
    void noModelAnywhere_fallsBackConsistentWithIsAnthropic() {
        // ① 会话行存在但 model_name=null + settings 未注入
        ToolRegistrationConfig config = configWith(sessionRecord(null), null);
        AgentState rebuilt = config.rebuildIdleStateFromDb(SESSION, idleMessageService());

        assertThat(rebuilt.currentModel())
            .as("两处皆空 → null（不得臆断为 anthropic 或 deepseek 任一协议）")
            .isNull();
        assertThat(ToolRegistrationConfig.resolveManualCompactModel(rebuilt))
            .as("resolveManualCompactModel 亦为 null → 交 resolveAnthropic 统一回落")
            .isNull();
        CompactConversationContext cc =
            compactContextOf(config, rebuilt, ToolRegistrationConfig.resolveManualCompactModel(rebuilt));
        assertThat(cc.getModel()).isNull();

        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        // 唯一权威：模型不可得 → false（非 Anthropic），与 CompactConversation.resolveAnthropic 同向
        assertThat(ContextUsageCalculator.isAnthropic(mm, pm, cc.getModel())).isFalse();
        assertThat(Tokens.tokenCountWithEstimation(rebuilt.rawMessages(),
                ContextUsageCalculator.isAnthropic(mm, pm, cc.getModel())))
            .as("回落方向与唯一权威逐值一致：非 Anthropic → input+output（不是 4 项和）")
            .isEqualTo(DEEPSEEK_INPUT_OUTPUT);

        // ② settings mapper 存在但 id=1 行缺失 / 空值 → 同样 null
        SettingsMapper emptySettings = mock(SettingsMapper.class);
        when(emptySettings.selectOneById(1)).thenReturn(new SettingsRecord());
        ToolRegistrationConfig noSettings = configWith(sessionRecord(null), null);
        ReflectionTestUtils.setField(noSettings, "settingsMapper", emptySettings);
        assertThat(noSettings.resolveSessionModelName(SESSION))
            .as("settings.main_model_name 为空 → null（不把空串当模型名）")
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3 · 端到端：真跑 handleCompactCommand（空闲/registry miss）→ 落库 boundary 的 preTokens
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("端到端（空闲会话真压缩）：boundary.compactMetadata.preTokens = 188374（anthropic 4 项和）")
    void endToEnd_idleCompactPersistsFourFieldSumForAnthropic() throws Exception {
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        stubAnthropicModel(mm, pm);
        CompactConversation.setMappers(mm, pm);

        MessageService messageService = idleMessageService();
        com.nexusai.application.agent.compact.StreamCompactSummary summary =
            mock(com.nexusai.application.agent.compact.StreamCompactSummary.class);
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary text", null));
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));

        ToolRegistrationConfig config = configWith(sessionRecord(ANTHROPIC), null);

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry(); // 空 = 空闲会话
        RequestContext.set(SESSION, "h3"); // 在途 /compact 行（ChatController:149 已落库）
        ReflectionTestUtils.invokeMethod(config, "handleCompactCommand",
            "", registry, null, summary, null, null, null, null, null, null, messageService);

        ArgumentCaptor<List<ChatMessageDto>> persisted = ArgumentCaptor.forClass(List.class);
        verify(messageService).appendPostCompactMessages(eq(SESSION), persisted.capture());
        ChatMessageDto boundary = persisted.getValue().get(0);
        assertThat(boundary.subtype()).isEqualTo("compact_boundary");
        assertThat(boundary.compactMetadata()).isNotNull();
        assertThat(((Number) boundary.compactMetadata().get("preTokens")).intValue())
            .as("落库 boundary 的 preTokens = 4 项和 188374（旧实现模型缺失 → 94625，anthropic 侧少计）")
            .isEqualTo(ANTHROPIC_4_FIELD_SUM);
    }

    @Test
    @DisplayName("端到端（空闲会话真压缩）：deepseek 会话 boundary.preTokens = 94625（不被 4 项和双计）")
    void endToEnd_idleCompactPersistsInputOnlyForDeepseek() throws Exception {
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        stubOpenAiCompatibleModel(mm, pm);
        CompactConversation.setMappers(mm, pm);

        MessageService messageService = idleMessageService();
        com.nexusai.application.agent.compact.StreamCompactSummary summary =
            mock(com.nexusai.application.agent.compact.StreamCompactSummary.class);
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary text", null));
        when(messageService.appendPostCompactMessages(eq(SESSION), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));

        ToolRegistrationConfig config = configWith(sessionRecord(DEEPSEEK), null);
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        RequestContext.set(SESSION, "h3");
        ReflectionTestUtils.invokeMethod(config, "handleCompactCommand",
            "", registry, null, summary, null, null, null, null, null, null, messageService);

        ArgumentCaptor<List<ChatMessageDto>> persisted = ArgumentCaptor.forClass(List.class);
        verify(messageService).appendPostCompactMessages(eq(SESSION), persisted.capture());
        assertThat(((Number) persisted.getValue().get(0).compactMetadata().get("preTokens")).intValue())
            .as("deepseek：input 已含 cache hit → preTokens = input+output = 94625（4 项和 188374 = 双计）")
            .isEqualTo(DEEPSEEK_INPUT_OUTPUT);
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试脚手架
    // ════════════════════════════════════════════════════════════════════

    /**
     * 空闲会话的 DB 转录：{@code [h1 user, h2 assistant(usage), h3 /compact]}。
     * h3 = 在途 /compact 行（ChatController:149 在 dispatch 前落库）→ 经 requestId 排除，
     * 故压缩输入 = [h1, h2]（与生产同形态），末条 h2 携带 usage → preTokens 恒为 usage 求和（无 rough 尾段）。
     */
    private static MessageService idleMessageService() {
        List<ChatMessageDto> dbRows = new ArrayList<>(List.of(
            msg("h1", Role.user, "first question"),
            usageMsg("h2", "first answer"),
            msg("h3", Role.user, "/compact")));
        MessageService messageService = mock(MessageService.class);
        when(messageService.listRawForTranscript(SESSION)).thenReturn(dbRows);
        // 与生产同语义：按 requestId 真排除在途 /compact 行（不是原样返回）
        when(messageService.listForResumeExcluding(anyList(), nullable(String.class)))
            .thenAnswer(inv -> {
                List<ChatMessageDto> raw = inv.getArgument(0);
                String exclude = inv.getArgument(1);
                List<ChatMessageDto> out = new ArrayList<>();
                for (ChatMessageDto m : raw) {
                    if (m != null && (exclude == null || !exclude.equals(m.id()))) {
                        out.add(m);
                    }
                }
                return out;
            });
        return messageService;
    }

    /** 与 handleCompactCommand:2278-2288 同形态装配 ctx（模型经 resolveManualCompactModel 传入）。 */
    private static CompactConversationContext compactContextOf(ToolRegistrationConfig config,
                                                               AgentState state,
                                                               String model) {
        CompactCommand.CompactCommandContext commandCtx = config.buildCompactCommandContext(
            state.rawMessages(), SESSION, AGENT, model,
            null,   // reactiveCompactor
            null,   // streamCompactSummary
            null,   // sessionMemoryService
            state.currentToolUseContext(),
            null,   // sysPromptCtxProvider
            null,   // defaultSysPromptAssemble
            null,   // customSystemPrompt
            null,   // appendSystemPrompt
            false,  // useGlobalCacheScope
            null);  // telemetry
        return commandCtx.compactConversationContextSupplier().get();
    }

    private static ToolRegistrationConfig configWith(SessionRecord session, String settingsMainModel) {
        ToolRegistrationConfig config = new ToolRegistrationConfig();
        SessionMapper sessionMapper = mock(SessionMapper.class);
        when(sessionMapper.selectOneById(anyString())).thenReturn(session);
        ReflectionTestUtils.setField(config, "sessionMapper", sessionMapper);
        if (settingsMainModel != null) {
            SettingsMapper settingsMapper = mock(SettingsMapper.class);
            SettingsRecord row = new SettingsRecord();
            row.setMainModelName(settingsMainModel);
            when(settingsMapper.selectOneById(any())).thenReturn(row);
            ReflectionTestUtils.setField(config, "settingsMapper", settingsMapper);
        }
        return config;
    }

    private static SessionRecord sessionRecord(String modelName) {
        SessionRecord s = new SessionRecord();
        s.setId(SESSION);
        s.setModelName(modelName);
        return s;
    }

    private static void stubAnthropicModel(ModelMapper mm, ProviderMapper pm) {
        ProviderRecord provider = provider("anthropic");
        when(mm.selectOneByQuery(any())).thenReturn(model("claude-sonnet-4-6", provider.getId()));
        when(pm.selectOneByQuery(any())).thenReturn(provider);
        when(pm.selectOneById(any())).thenReturn(provider);
    }

    private static void stubOpenAiCompatibleModel(ModelMapper mm, ProviderMapper pm) {
        ProviderRecord provider = provider("openai_compatible");
        when(mm.selectOneByQuery(any())).thenReturn(model("deepseek-v4-flash", provider.getId()));
        when(pm.selectOneByQuery(any())).thenReturn(provider);
        when(pm.selectOneById(any())).thenReturn(provider);
    }

    private static ProviderRecord provider(String type) {
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType(type);
        p.setEnabled(true);
        return p;
    }

    private static ModelRecord model(String name, String providerId) {
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId(providerId);
        m.setName(name);
        m.setEnabled(true);
        return m;
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            content, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    /** DB 实证 usage：input=93749 / output=876 / cacheRead=93568 / cacheCreate=181（DeepSeek 会话）。 */
    private static ChatMessageDto usageMsg(String id, String content) {
        return msg(id, Role.assistant, content)
            .withUsage(new AgentUsage(93749L, 876L, 181L, 93568L, null, null, null))
            .withUsageCache(93568, 181);
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
