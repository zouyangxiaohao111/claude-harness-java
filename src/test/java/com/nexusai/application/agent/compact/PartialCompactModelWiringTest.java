package com.nexusai.application.agent.compact;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.application.agent.tool.AgentUsage;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import com.nexusai.model.provider.dto.ModelTag;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.PartialCompactRequest;
import com.nexusai.model.session.dto.PartialCompactResponse;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SessionDto;
import com.nexusai.model.session.dto.SessionGroup;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P3-a-2] PartialCompactService 压缩上下文模型装配 · 意图测试。
 *
 * <h2>WHY（CLAUDE.md 规则九 · 测试验证意图）</h2>
 * partial 压缩（前端消息选择器触发）的 {@code preCompactTokenCount}
 * （{@code PartialCompactConversation:295}）与 {@code compactionCallTotalTokens}（{@code :468}）
 * 都按 {@code ctx.getModel()} 分派协议（{@code CompactConversation.resolveAnthropic} →
 * 唯一权威 {@code ContextUsageCalculator.isAnthropic}）：
 * Anthropic = {@code input + cacheRead + cacheCreate + output}（4 项和）；OpenAI/DeepSeek =
 * 仅 {@code input + output}（{@code prompt_tokens} 已含 cache hit，再加即双计）。
 * {@code PartialCompactService.buildContext} 原先<b>从不 {@code setModel}</b> →
 * {@code ctx.getModel()=null} → 分派不可判定 → 回落非 Anthropic →
 * <b>anthropic 会话少计</b>（deepseek 会话恰好正确，缺陷只在 anthropic 侧显形）。
 *
 * <h2>RED 条件（逐条，对应变异点）</h2>
 * <ol>
 *   <li>删掉 {@code buildContext} 里的 {@code cc.setModel(resolveCompactModel(sessionId))} →
 *       {@link #anthropicSession_ctxCarriesModel_fourFieldSum} 的 preTokens 断言红
 *       （188374 → 94625，少计）；{@link #deepseekSession_ctxCarriesModel_inputOnly} 仍绿
 *       —— 正是「只有 anthropic 侧少计」的实证。</li>
 *   <li>把 {@code resolveCompactModel} 的 DB 回落链去掉（只读 live state）→
 *       {@link #unregisteredSession_fallsBackToSessionRecord} 与
 *       {@link #fallsBackToSettingsMainModel} 红。</li>
 *   <li>把「会话记录缺失/模型为空」改成臆断 anthropic → {@link #noModelAnywhere_fallsBackAsNonAnthropic} 红。</li>
 * </ol>
 *
 * <p><b>观察点</b>：不是内部字段，而是<b>真实压缩产物</b>——落库 boundary 的
 * {@code compactMetadata.preTokens}（{@code PartialCompactConversation:489} 由
 * {@code preCompactTokenCount} 写入，经 {@code toChatMessageDto} 落到返回消息）。
 */
@DisplayName("[P3-a-2] partial 压缩的模型装配 + 协议分派")
class PartialCompactModelWiringTest {

    private static final String SESSION = "sess-p3a2-partial";
    private static final String ANTHROPIC = "anthropic/claude-sonnet-4-6";
    private static final String DEEPSEEK = "deepseek/deepseek-v4-flash";
    private static final String SETTINGS_ANTHROPIC = "anthropic/claude-opus-4-6";

    private static final int ANTHROPIC_4_FIELD_SUM = 93749 + 93568 + 181 + 876; // 188374
    private static final int DEEPSEEK_INPUT_OUTPUT = 93749 + 876;               // 94625

    private Object savedModelMapper;
    private Object savedProviderMapper;

    @BeforeEach
    void snapshotStaticMappers() throws Exception {
        savedModelMapper = readStaticMapper("modelMapper");
        savedProviderMapper = readStaticMapper("providerMapper");
    }

    @AfterEach
    void restore() throws Exception {
        writeStaticMapper("modelMapper", savedModelMapper);
        writeStaticMapper("providerMapper", savedProviderMapper);
        RequestContext.clear();
        CompactProgressState.clear();
        CompactProgressState.clearAbort();
        CompactProgressState.removeSessionAbort(SESSION);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1 · 未注册会话（REST 线程历史会话）：DB 链取模型
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("anthropic 会话（未注册，sessions.model_name 命中）→ ctx 带模型 → preTokens=188374（4 项和）")
    void anthropicSession_ctxCarriesModel_fourFieldSum() {
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        stubAnthropicModel(mm, pm);
        CompactConversation.setMappers(mm, pm);

        PartialCompactService svc = service(sessionDto(ANTHROPIC), null);

        assertThat(svc.resolveCompactModel(SESSION))
            .as("未注册会话 → 回落 sessions.model_name（与 manual 空闲重建同链）")
            .isEqualTo(ANTHROPIC);

        int preTokens = compactAndReadPreTokens(svc);

        assertThat(preTokens)
            .as("anthropic：Claude usage 三字段独立 → preTokens = input+cacheRead+cacheCreate+output"
                + " = 188374（旧实现 ctx.model=null → 94625，少计）")
            .isEqualTo(ANTHROPIC_4_FIELD_SUM);
    }

    @Test
    @DisplayName("deepseek 会话（未注册）→ ctx 带模型 → preTokens=94625（input+output，非 4 项和）")
    void deepseekSession_ctxCarriesModel_inputOnly() {
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        stubOpenAiCompatibleModel(mm, pm);
        CompactConversation.setMappers(mm, pm);

        PartialCompactService svc = service(sessionDto(DEEPSEEK), null);

        assertThat(svc.resolveCompactModel(SESSION)).isEqualTo(DEEPSEEK);
        assertThat(compactAndReadPreTokens(svc))
            .as("deepseek：prompt_tokens 已含 cache hit → preTokens = input+output = 94625"
                + "（4 项和 188374 = 把 cache read/creation 双计）")
            .isEqualTo(DEEPSEEK_INPUT_OUTPUT);
    }

    @Test
    @DisplayName("未注册 + 会话 model_name 为空 → 回落 settings.main_model_name（与 auto 路径 settings 层同源）")
    void fallsBackToSettingsMainModel() {
        ProviderRecord provider = provider("anthropic");
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        when(mm.selectOneByQuery(any())).thenReturn(modelRecord("claude-opus-4-6", provider.getId()));
        when(pm.selectOneByQuery(any())).thenReturn(provider);
        when(pm.selectOneById(any())).thenReturn(provider);
        CompactConversation.setMappers(mm, pm);

        // 会话行存在但无 override → settings 兜底
        PartialCompactService svc = service(sessionDto(null), SETTINGS_ANTHROPIC);

        assertThat(svc.resolveSessionModelName(SESSION)).isEqualTo(SETTINGS_ANTHROPIC);
        assertThat(svc.resolveCompactModel(SESSION)).isEqualTo(SETTINGS_ANTHROPIC);
        assertThat(compactAndReadPreTokens(svc))
            .as("settings 兜底到 anthropic 主模型 → 仍必须走 4 项和（否则少计）")
            .isEqualTo(ANTHROPIC_4_FIELD_SUM);
    }

    @Test
    @DisplayName("会话记录缺失 + settings 空 → 模型 null → 回落方向与 ContextUsageCalculator.isAnthropic 一致（非 4 项和）")
    void noModelAnywhere_fallsBackAsNonAnthropic() {
        // sessionService.getById 抛 NotFoundException（会话行缺失）且 settings 未注入
        PartialCompactService svc = serviceMissingSession(null);

        assertThat(svc.resolveCompactModel(SESSION))
            .as("判不出来 → null（不得臆断任一协议；回落由唯一权威决定）")
            .isNull();
        assertThat(ContextUsageCalculator.isAnthropic(null, null, null)).isFalse();
        assertThat(compactAndReadPreTokens(svc))
            .as("回落非 Anthropic → input+output = 94625（与唯权威同向；不是 4 项和的 188374）")
            .isEqualTo(DEEPSEEK_INPUT_OUTPUT);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2 · 已注册 live state：currentModel 优先（与 auto/manual 同口径）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("已注册会话 → 模型取 live state.currentModel()（胜过 DB 记录；与 auto 路径同口径）")
    void registeredSession_prefersLiveStateCurrentModel() {
        ProviderRecord provider = provider("anthropic");
        ModelMapper mm = mock(ModelMapper.class);
        ProviderMapper pm = mock(ProviderMapper.class);
        when(mm.selectOneByQuery(any())).thenReturn(modelRecord("claude-sonnet-4-6", provider.getId()));
        when(pm.selectOneByQuery(any())).thenReturn(provider);
        when(pm.selectOneById(any())).thenReturn(provider);
        CompactConversation.setMappers(mm, pm);

        MessageService messageService = messageService();
        SessionService sessionService = mock(SessionService.class);
        // DB 记录写的是 deepseek，但 live 主循环正在用 anthropic（fallbackModel 切换 / ConfigTool SET model）
        when(sessionService.getById(anyString())).thenReturn(sessionDto(DEEPSEEK));

        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        AgentState live = new AgentState("sys", SESSION, java.util.UUID.randomUUID());
        live.setCurrentModel(ANTHROPIC);
        registry.register(SESSION, live);

        PartialCompactService svc = new PartialCompactService(
            messageService, sessionService, summaryMock(), registry, null, null);

        assertThat(svc.resolveCompactModel(SESSION))
            .as("live state.currentModel()（LlmAgentLoop 每轮 effectiveModel 覆盖写）必须胜过 DB 记录 —— "
                + "否则压缩口径与本轮真实请求的模型不一致（auto 路径 AutoCompactor.model 同源）")
            .isEqualTo(ANTHROPIC);
        assertThat(compactAndReadPreTokens(svc))
            .as("anthropic live state → preTokens = 4 项和 188374")
            .isEqualTo(ANTHROPIC_4_FIELD_SUM);
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试脚手架
    // ════════════════════════════════════════════════════════════════════

    /**
     * 触发真实 partialCompact（UP_TO 选末条 a1）并读出落库 boundary 的 preTokens。
     *
     * <p>选 UP_TO a1：summarize=[u0,a0,u1] 非空（不触 nothing_to_summarize）；keep=[a1]。
     * {@code preCompactTokenCount} 在<b>全量</b> allMessages 上求值，且末条 a1 带 usage →
     * usage-walk 锚定末条、尾段切片为空 → 期望值恒为 usage 求和（无 rough 噪声）。
     */
    private static int compactAndReadPreTokens(PartialCompactService svc) {
        PartialCompactResponse resp = svc.partialCompact(SESSION,
            new PartialCompactRequest("a1", PartialCompactRequest.Direction.UP_TO, null));
        ChatMessageDto boundary = resp.messages().get(0);
        assertThat(boundary.subtype()).isEqualTo("compact_boundary");
        assertThat(boundary.compactMetadata()).isNotNull();
        return ((Number) boundary.compactMetadata().get("preTokens")).intValue();
    }

    private static PartialCompactService service(SessionDto session, String settingsMainModel) {
        PartialCompactService svc = new PartialCompactService(
            messageService(), sessionService(session), summaryMock());
        if (settingsMainModel != null) {
            SettingsMapper settingsMapper = mock(SettingsMapper.class);
            SettingsRecord row = new SettingsRecord();
            row.setMainModelName(settingsMainModel);
            when(settingsMapper.selectOneById(any())).thenReturn(row);
            ReflectionTestUtils.setField(svc, "settingsMapper", settingsMapper);
        }
        return svc;
    }

    /** 会话行缺失（getById 抛 NotFoundException）+ 无 settings → 模型不可判定。 */
    private static PartialCompactService serviceMissingSession(String settingsMainModel) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getById(anyString()))
            .thenThrow(new NotFoundException("Session " + SESSION + " not found"));
        PartialCompactService svc = new PartialCompactService(
            messageService(), sessionService, summaryMock());
        if (settingsMainModel != null) {
            SettingsMapper settingsMapper = mock(SettingsMapper.class);
            SettingsRecord row = new SettingsRecord();
            row.setMainModelName(settingsMainModel);
            when(settingsMapper.selectOneById(any())).thenReturn(row);
            ReflectionTestUtils.setField(svc, "settingsMapper", settingsMapper);
        }
        return svc;
    }

    private static SessionService sessionService(SessionDto session) {
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getById(anyString())).thenReturn(session);
        return sessionService;
    }

    private static MessageService messageService() {
        MessageService messageService = mock(MessageService.class);
        when(messageService.listForResume(anyString())).thenReturn(history());
        when(messageService.appendPostCompactMessages(anyString(), anyList()))
            .thenAnswer(inv -> inv.getArgument(1));
        return messageService;
    }

    private static StreamCompactSummary summaryMock() {
        StreamCompactSummary summary = mock(StreamCompactSummary.class);
        when(summary.summarize(anyString(), anyList()))
            .thenReturn(new CompactConversation.SummaryResult("summary ok", null));
        return summary;
    }

    /** [u0, a0, u1, a1] —— 末条 a1 携带 DB 实证 usage（input=93749 / output=876 / read=93568 / create=181）。 */
    private static List<ChatMessageDto> history() {
        List<ChatMessageDto> list = new ArrayList<>();
        list.add(msg("u0", Role.user));
        list.add(msg("a0", Role.assistant));
        list.add(msg("u1", Role.user));
        list.add(usageMsg("a1"));
        return list;
    }

    private static SessionDto sessionDto(String modelName) {
        return new SessionDto(SESSION, ModelTag.DS, modelName, "title", "现在", SessionGroup.current,
            null, null, 0, OffsetDateTime.now(), OffsetDateTime.now(),
            null, null, null, null, null, null, null, null, null);
    }

    private static void stubAnthropicModel(ModelMapper mm, ProviderMapper pm) {
        ProviderRecord provider = provider("anthropic");
        when(mm.selectOneByQuery(any())).thenReturn(modelRecord("claude-sonnet-4-6", provider.getId()));
        when(pm.selectOneByQuery(any())).thenReturn(provider);
        when(pm.selectOneById(any())).thenReturn(provider);
    }

    private static void stubOpenAiCompatibleModel(ModelMapper mm, ProviderMapper pm) {
        ProviderRecord provider = provider("openai_compatible");
        when(mm.selectOneByQuery(any())).thenReturn(modelRecord("deepseek-v4-flash", provider.getId()));
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

    private static ModelRecord modelRecord(String name, String providerId) {
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId(providerId);
        m.setName(name);
        m.setEnabled(true);
        return m;
    }

    private static ChatMessageDto msg(String id, Role role) {
        return new ChatMessageDto(id, SESSION, role, role == Role.assistant ? "assistant" : "user",
            "content-" + id, null, List.of(), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, List.of(), List.of(), null, false, false);
    }

    private static ChatMessageDto usageMsg(String id) {
        return msg(id, Role.assistant)
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
