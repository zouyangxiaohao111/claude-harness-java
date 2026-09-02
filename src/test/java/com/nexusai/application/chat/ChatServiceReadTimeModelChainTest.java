package com.nexusai.application.chat;

import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [T2.5] ChatService 读时模型解析链各层测试（4 层 + 回落边界）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>：T2.3 改造后的读时兜底链
 * {@code req.modelName → session.model_name → settings.main_model_id → DEFAULT_MODEL}
 * 是原始 Bug 2 的<b>读时等价面</b>——title-only 会话（无 session.model_name）在
 * 不同 settings 形态下若解析链断裂，会 NPE 或 provider 解析失败。四层优先级与各层
 * "断裂时如何回落"必须逐一锁定：
 * <ol>
 *   <li><b>req 优先</b>——请求体 modelName 非空恒胜出，session/settings 不触达。</li>
 *   <li><b>blank 视为缺省</b>——空串不拦截，落到会话层。</li>
 *   <li><b>会话 override</b>——无 req 时 session.model_name 直接返回，settings 不 consult。</li>
 *   <li><b>settings 层</b>——mainModelName 全名反查（ModelNameResolver.resolve，[R-1 P0] 取代 id 直查）
 *       解析出<b>启用</b> model 名才返回；null/缺失/未启用均回落。</li>
 *   <li><b>异常吞并</b>——settings 查询异常 catch 后回落 DEFAULT_MODEL，不得向上传播出
 *       processUserMessage 的 async 线程（否则无 catch 静默失败，违反规则五/十二）。</li>
 * </ol>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock 的
 * settingsMapper / modelMapper（其余字段 null，被测方法不触达）。被测符号为
 * package-visible {@code resolveModelNameForSession}，本测试与 ChatService 同包可直接调用。
 */
@DisplayName("[T2.5] ChatService 读时模型解析链（4 层 + 回落边界）")
class ChatServiceReadTimeModelChainTest {

    private static final String DEFAULT_MODEL_LITERAL = "mock-fast"; // DEFAULT_MODEL 为 private，用字面量锁定

    private ChatService service;
    private SettingsMapper settingsMapper;
    private ModelMapper modelMapper;
    private ProviderMapper providerMapper;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        settingsMapper = mock(SettingsMapper.class);
        modelMapper = mock(ModelMapper.class);
        providerMapper = mock(ProviderMapper.class);
        ReflectionTestUtils.setField(service, "settingsMapper", settingsMapper);
        ReflectionTestUtils.setField(service, "modelMapper", modelMapper);
        // [R-1 P0] resolveSettingsModelName 走 ModelNameResolver.resolve 全名反查需 providerMapper
        ReflectionTestUtils.setField(service, "providerMapper", providerMapper);
    }

    private SettingsRecord settings(String mainModelName) {
        SettingsRecord s = new SettingsRecord();
        s.setMainModelName(mainModelName);
        return s;
    }

    private ModelRecord model(String id, String name, boolean enabled) {
        ModelRecord m = new ModelRecord();
        m.setId(id);
        m.setName(name);
        m.setEnabled(enabled);
        return m;
    }

    private ProviderRecord provider(String id) {
        ProviderRecord p = new ProviderRecord();
        p.setId(id);
        p.setName(id);
        return p;
    }

    private SessionRecord session(String modelName) {
        SessionRecord s = new SessionRecord();
        s.setModelName(modelName);
        return s;
    }

    // ── 第 1 层：请求体 modelName ──────────────────────────────────────────

    @Test
    @DisplayName("第 1 层：req 非空胜出——即使 session/settings 有值也不触达")
    void reqModelName_wins() {
        // WHY: CC model.ts getMainLoopModel 请求 override 恒最优先；若 session.model_name
        // 抢先，用户显式指定模型会被会话历史覆盖，属优先级倒置。
        // [R-1 P0] settings 层已切换全名反查，stub 对齐新解析路径（req 恒胜出，mapper 均不触达）。
        when(settingsMapper.selectOneById(1)).thenReturn(settings("openai/deepseek-x"));
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider("p-openai"));
        when(modelMapper.selectOneByQuery(any())).thenReturn(model("m1", "deepseek-x", true));

        String result = service.resolveModelNameForSession(session("claude-x"), "gpt-x");

        assertEquals("gpt-x", result);
        verify(settingsMapper, never()).selectOneById(any());
        verify(modelMapper, never()).selectOneByQuery(any());
    }

    @Test
    @DisplayName("第 1 层边界：req 空串视为缺省——落到会话层而非返回空串")
    void reqBlank_ignored() {
        // WHY: 前端可能传 ""（未指定）——空串不得作为最终 model 名返回，否则 provider
        // 按空串解析必然失败；应视为缺省落到下一层。
        String result = service.resolveModelNameForSession(session("claude-x"), "");

        assertEquals("claude-x", result);
    }

    // ── 第 2 层：会话 override ─────────────────────────────────────────────

    @Test
    @DisplayName("第 2 层：无 req 时 session.model_name 直接返回，settings 不 consult")
    void sessionOverride_whenNoReq() {
        // WHY: title-only 之外的显式选模型会话，model_name 已在创建时落库；读时无需再撞 settings。
        String result = service.resolveModelNameForSession(session("claude-x"), null);

        assertEquals("claude-x", result);
        verify(settingsMapper, never()).selectOneById(any());
    }

    // ── 第 3 层：settings.mainModelName 全名反查解析（[R-1 P0] 取代 id 直查） ───────────────

    @Test
    @DisplayName("第 3 层：settings.mainModelName 全名反查（provider/name 联合查）命中启用 model 名")
    void settingsMainModelName_fullName_resolves() {
        // WHY: [R-1 P0] settings 存全名（V28 main_model_id→main_model_name），id 直查 selectOneById 恒 miss
        // → 主循环恒回落 mock；必须走 ModelNameResolver.resolve 全名反查（providerName/modelName 联合查）
        // 才能命中启用模型（对齐 CronIdleExecutor:546-548）。
        when(settingsMapper.selectOneById(1)).thenReturn(settings("openai/deepseek-x"));
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider("p-openai"));
        when(modelMapper.selectOneByQuery(any())).thenReturn(model("m1", "deepseek-x", true));

        String result = service.resolveModelNameForSession(session(null), null);

        assertEquals("deepseek-x", result);
        // [R-1] 全名反查不再走 models.id 直查
        verify(modelMapper, never()).selectOneById(any());
    }

    @Test
    @DisplayName("第 3 层：settings.mainModelName 裸名（无 /）→ 按 name 兼容路径命中")
    void settingsMainModelName_bareName_resolves() {
        // WHY: [R-1 P0] settings 亦可能存裸模型名（无 provider 前缀）——ModelNameResolver.resolve
        // 走按 name 兼容路径（selectListByQuery eq name + enabled），裸名同样能命中，不再依赖 id。
        when(settingsMapper.selectOneById(1)).thenReturn(settings("deepseek-x"));
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(model("m1", "deepseek-x", true)));

        String result = service.resolveModelNameForSession(session(null), null);

        assertEquals("deepseek-x", result);
    }

    @Test
    @DisplayName("第 3 层边界：settings.mainModelName 反查出未启用模型 → 回落 DEFAULT_MODEL")
    void settingsMainModelName_disabledModel_fallsThrough() {
        // WHY: 解析返回的模型必须 enabled；未启用模型不得返回（provider 无法使用）。即便反查
        // 误返 disabled 模型，ChatService 的 enabled 复检（getEnabled 判真）仍兜底 → 回落。
        when(settingsMapper.selectOneById(1)).thenReturn(settings("openai/deepseek-x"));
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider("p-openai"));
        when(modelMapper.selectOneByQuery(any())).thenReturn(model("m1", "deepseek-x", false));

        String result = service.resolveModelNameForSession(session(null), null);

        assertEquals(DEFAULT_MODEL_LITERAL, result);
    }

    @Test
    @DisplayName("第 3 层边界：settings.mainModelName 全名反查未命中（提供商下无此模型）→ 回落 DEFAULT_MODEL")
    void settingsMainModelName_missingModel_fallsThrough() {
        // WHY: [R-1 P0] settings 引用了一个 DB 不存在/该提供商下无此模型的全名 → ModelNameResolver
        // 全名反查 fail-loud 返回 null → 回落 DEFAULT_MODEL（不再按 models.id 直查，杜绝 id 恒 miss 空转）。
        when(settingsMapper.selectOneById(1)).thenReturn(settings("openai/ghost-model"));
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider("p-openai"));
        when(modelMapper.selectOneByQuery(any())).thenReturn(null);

        String result = service.resolveModelNameForSession(session(null), null);

        assertEquals(DEFAULT_MODEL_LITERAL, result);
    }

    @Test
    @DisplayName("第 3 层边界（最重要）：main_model_id 为 null（全新库真实形态）→ 回落 DEFAULT_MODEL")
    void settingsMainModelId_null_fallsThrough() {
        // WHY: 全新库 settings 行存在但 main_model_id 为 NULL（V1 仅种入 settings 行）。
        // 这是原始 Bug 2 的读时等价面——若此路径不回落 DEFAULT_MODEL 而返回 null/抛异常，
        // title-only 会话首条消息会 provider 解析失败。
        when(settingsMapper.selectOneById(1)).thenReturn(settings(null));

        String result = service.resolveModelNameForSession(session(null), null);

        assertEquals(DEFAULT_MODEL_LITERAL, result);
    }

    // ── 第 4 层：DEFAULT_MODEL 兜底 ────────────────────────────────────────

    @Test
    @DisplayName("第 4 层：settings 记录不存在（selectOneById 返回 null）→ DEFAULT_MODEL")
    void settingsNull_allDefault() {
        // WHY: settings 行被删/表空的极端形态——settingsMapper 返回 null 时不得 NPE，须回落。
        when(settingsMapper.selectOneById(1)).thenReturn(null);

        String result = service.resolveModelNameForSession(session(null), null);

        assertEquals(DEFAULT_MODEL_LITERAL, result);
    }

    @Test
    @DisplayName("第 4 层：req/session/settings 全缺省 → DEFAULT_MODEL 恒不 null")
    void allDefault_DEFAULT_MODEL() {
        // WHY: 四层全空时的最终兜底，解析链契约是"恒非 null 非 blank"。
        when(settingsMapper.selectOneById(1)).thenReturn(settings(null));

        String result = service.resolveModelNameForSession(null, null);

        assertEquals(DEFAULT_MODEL_LITERAL, result);
    }

    @Test
    @DisplayName("防御语义：settings 查询抛异常 → 吞并并回落 DEFAULT_MODEL（不向上传播）")
    void settingsMapperThrows_fallsBackToDefault() {
        // WHY: settingsMapper.selectOneById 异常若向上传播出 processUserMessage 的 async 线程，
        // 该线程无 catch 会静默失败；必须 catch 吞并回落 DEFAULT_MODEL。锁定继承自
        // SessionService.resolveDefaultModelName 的 try/catch + log.warn 防御语义（doc-b 若去掉
        // try/catch 本用例即红）。
        when(settingsMapper.selectOneById(1)).thenThrow(new RuntimeException("db down"));

        String result = service.resolveModelNameForSession(session(null), null);

        assertEquals(DEFAULT_MODEL_LITERAL, result);
    }
}
