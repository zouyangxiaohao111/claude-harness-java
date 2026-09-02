package com.nexusai.domain.session;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [token-compact-fix ⑤方案B] MessageService 重拉上下文快照补算测试 · 净新增（非 CC 对齐）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 实时 message.complete 事件推
 * contextTokensUsed/percentLeft/contextWindow（ChatService:559-581，对齐 CC context.ts
 * current_usage/percentLeft），历史消息重拉（GET /messages → MessageService.listBySession）
 * 不落库 → 重拉丢失。方案B：不落库，每次重拉对末条 assistant 消息补算
 * （applyContextSnapshotToLastAssistant）。变异点：
 * <ul>
 *   <li>末条 assistant 无 usage（input/output 均 null）→ 不出快照（三字段 null，前端 null=无数据）→ 红</li>
 *   <li>会话模型不可判定（会话 override + settings 均空）→ 不出快照（对齐实时 usage null 省略语义）→ 红</li>
 *   <li>正常路径：末条 assistant 得 contextTokensUsed=inputTokens（DB 只存 input，cache 未存 →
 *       重算近似）、contextWindow=模型表 models.max_context_tokens、percentLeft=max(0,
 *       round((1 - used/window)*100))；非末条消息三字段恒 null → 红</li>
 * </ul>
 */
@DisplayName("[token-compact-fix ⑤方案B] MessageService 重拉上下文快照补算")
class MessageServiceContextSnapshotTest {

    private MessageService service;
    private MessageMapper messageMapper;
    private SessionMapper sessionMapper;
    private ToolCallMapper toolCallMapper;
    private ModelMapper modelMapper;
    private ProviderMapper providerMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        sessionMapper = mock(SessionMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        modelMapper = mock(ModelMapper.class);
        providerMapper = mock(ProviderMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "modelMapper", modelMapper);
        ReflectionTestUtils.setField(service, "providerMapper", providerMapper);
        // settingsMapper 未注入（null）→ resolveSessionModel 跳过 settings 层，仅会话 override
        when(toolCallMapper.selectListByQuery(any())).thenReturn(List.of());
    }

    private static MessageRecord rec(String id, String role, Integer input, Integer output) {
        return rec(id, role, input, output, null, null);
    }

    /** [token-compact-fix B1 方案A] 6 参重载：显式 cache 字段（V53 列读回 → toDto 回填 DTO cache）。 */
    private static MessageRecord rec(String id, String role, Integer input, Integer output,
                                     Integer cacheRead, Integer cacheCreation) {
        MessageRecord r = new MessageRecord();
        r.setId(id);
        r.setSessionId("sess-1");
        r.setRole(role);
        r.setInputTokens(input);
        r.setOutputTokens(output);
        r.setCacheReadInputTokens(cacheRead);
        r.setCacheCreationInputTokens(cacheCreation);
        return r;
    }

    private void stubSessionModel(String modelName) {
        SessionRecord s = new SessionRecord();
        s.setModelName(modelName);
        when(sessionMapper.selectOneById(any())).thenReturn(s);
    }

    /** 模型全名路径可解析：providers 前缀命中 → models 返回带 max_context_tokens 的 ModelRecord。 */
    private void stubModelWindow(int maxContextTokens) {
        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        when(providerMapper.selectOneByQuery(any())).thenReturn(provider);
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId("p1");
        m.setName("deepseek-v4-flash");
        m.setEnabled(true);
        m.setMaxContextTokens(maxContextTokens);
        when(modelMapper.selectOneByQuery(any())).thenReturn(m);
    }

    /** [修复] 显式 provider.type · isAnthropic 判定走 providerMapper.selectOneById(model.providerId)。 */
    private void stubProviderType(String type) {
        ProviderRecord p = new ProviderRecord();
        p.setId("p1");
        p.setType(type);
        when(providerMapper.selectOneById(any())).thenReturn(p);
    }

    @Test
    @DisplayName("正常路径：末条 assistant 得上下文快照（input 重算 + 模型表窗口 + percentLeft）")
    void listBySession_attachesSnapshot_toLastAssistant() {
        // GIVEN: 会话模型 override + 模型表窗口 128000 + 消息 [user, assistant(input=2000)]
        stubSessionModel("deepseek/deepseek-v4-flash");
        stubModelWindow(128000);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            rec("m-user", "user", null, null),
            rec("m-asst", "assistant", 2000, 500)));

        // WHEN: 重拉（GET /messages）
        List<ChatMessageDto> read = service.listBySession("sess-1");

        // THEN: 末条 assistant 携带快照（DB 只存 input，cache 未存 → used=input；窗口=模型表；percentLeft 四舍五入）
        assertThat(read).hasSize(2);
        ChatMessageDto last = read.get(1);
        assertThat(last.contextTokensUsed()).as("重算 = 已落库 inputTokens + cache(0)").isEqualTo(2000L);
        assertThat(last.contextWindow()).as("窗口 = 模型表 models.max_context_tokens").isEqualTo(128000L);
        assertThat(last.percentLeft())
            .as("percentLeft = max(0, round((1 - 2000/128000)*100)) = 98")
            .isEqualTo(98);
        // 非末条消息不出快照（三字段恒 null）
        assertThat(read.get(0).contextTokensUsed()).isNull();
        assertThat(read.get(0).percentLeft()).isNull();
        assertThat(read.get(0).contextWindow()).isNull();
    }

    @Test
    @DisplayName("末条 assistant 无 usage（input/output 均 null）→ 不出快照")
    void listBySession_lastAssistantNoUsage_noSnapshot() {
        // GIVEN: 会话模型 + 模型窗口已可解析，但末条 assistant 无 usage
        stubSessionModel("deepseek/deepseek-v4-flash");
        stubModelWindow(128000);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            rec("m-user", "user", null, null),
            rec("m-asst", "assistant", null, null)));

        // WHEN
        List<ChatMessageDto> read = service.listBySession("sess-1");

        // THEN: 三字段全 null（对齐实时 usage null 省略，前端 null=无数据）
        assertThat(read.get(1).contextTokensUsed()).isNull();
        assertThat(read.get(1).percentLeft()).isNull();
        assertThat(read.get(1).contextWindow()).isNull();
    }

    @Test
    @DisplayName("会话模型不可判定（override + settings 均空）→ 不出快照")
    void listBySession_modelUnresolvable_noSnapshot() {
        // GIVEN: 会话 modelName 空白 + settingsMapper 未注入（null）→ 模型不可判定
        stubSessionModel("   ");
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            rec("m-user", "user", null, null),
            rec("m-asst", "assistant", 2000, 500)));

        // WHEN
        List<ChatMessageDto> read = service.listBySession("sess-1");

        // THEN: 不出快照（对齐实时 usage null 省略语义，前端 null=无数据）
        assertThat(read.get(1).contextTokensUsed()).isNull();
        assertThat(read.get(1).percentLeft()).isNull();
        assertThat(read.get(1).contextWindow()).isNull();
    }

    @Test
    @DisplayName("[修复] openai_compatible 模型（DeepSeek）重算仅 input——加 cacheRead 双计 → 红")
    void listBySession_openaiCompatibleCacheTokensNoDoubleCount() {
        // WHY (规则 9 · 测试验证意图): 主模型 DeepSeek 走 openai_compatible 协议——实时 complete 事件
        //   仅按 input（prompt_tokens 已含 cache hit）。重算若仍三字段和（B1 旧实现）→ 与实时不一致，
        //   双计 cache → 红。变异点：重算对该协议加 cacheRead/cacheCreation → used=2800 → 红。
        // GIVEN: 末条 assistant input=2000 cacheRead=500 cacheCreation=300（V53 列已落库）+
        //   provider.type="openai_compatible"
        stubSessionModel("deepseek/deepseek-v4-flash");
        stubModelWindow(128000);
        stubProviderType("openai_compatible");
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            rec("m-user", "user", null, null),
            rec("m-asst", "assistant", 2000, 500, 500, 300)));

        // WHEN: 重拉（GET /messages）
        List<ChatMessageDto> read = service.listBySession("sess-1");

        // THEN: contextTokensUsed = input = 2000（cache 不双计，与实时 ChatService:572-578 同源）
        ChatMessageDto last = read.get(1);
        assertThat(last.inputCacheReadTokens())
            .as("toDto 读侧必须从 V53 列回填 cacheRead 到 DTO（读侧保留，仅计算分派）")
            .isEqualTo(500);
        assertThat(last.inputCacheCreationTokens())
            .as("toDto 读侧必须从 V53 列回填 cacheCreation 到 DTO")
            .isEqualTo(300);
        assertThat(last.contextTokensUsed())
            .as("openai_compatible 重算 = 仅 input（加 cacheRead 会双计，主模型 DeepSeek）")
            .isEqualTo(2000L);
        assertThat(last.percentLeft())
            .as("percentLeft = max(0, round((1 - 2000/128000)*100)) = 98")
            .isEqualTo(98);
    }

    @Test
    @DisplayName("[修复] Anthropic 模型重算含 cache（input+cacheRead+cacheCreation 三字段和）")
    void listBySession_anthropicCacheTokensIncludedInRecompute() {
        // WHY (规则 9 · 测试验证意图): Claude API usage 三字段独立（CC utils/context.ts:131-133），
        //   Anthropic 协议重算必须三字段和，否则 cache 少算 → 红。与实时 complete 事件同源分派。
        // GIVEN: provider.type="anthropic" + 末条 assistant input=2000 cacheRead=500 cacheCreation=300
        stubSessionModel("anthropic/claude-sonnet-4-6");
        stubModelWindow(200000);
        stubProviderType("anthropic");
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            rec("m-user", "user", null, null),
            rec("m-asst", "assistant", 2000, 500, 500, 300)));

        // WHEN: 重拉（GET /messages）
        List<ChatMessageDto> read = service.listBySession("sess-1");

        // THEN: contextTokensUsed = input + cacheRead + cacheCreation = 2800
        ChatMessageDto last = read.get(1);
        assertThat(last.contextTokensUsed())
            .as("Anthropic 重算 = input + cacheRead + cacheCreation = 2800")
            .isEqualTo(2800L);
        assertThat(last.percentLeft())
            .as("percentLeft = max(0, round((1 - 2800/200000)*100)) = 99")
            .isEqualTo(99);
    }

    @Test
    @DisplayName("[B1 方案A] 旧行无 cache（V53 列 NULL）→ 重算回退 input（不 NPE，与存量兼容）")
    void listBySession_oldRowNoCache_recomputeFallsBackToInput() {
        // WHY: V53 迁移前旧行 cache 列 NULL → toDto 回填 null → 重算 null 回退 0。
        //   变异点：重算直接对 null 做加法 → NPE → 红（存量历史消息重拉即崩）。
        stubSessionModel("deepseek/deepseek-v4-flash");
        stubModelWindow(128000);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            rec("m-user", "user", null, null),
            rec("m-asst-old", "assistant", 2000, 500)));

        List<ChatMessageDto> read = service.listBySession("sess-1");

        assertThat(read.get(1).contextTokensUsed())
            .as("旧行无 cache → used = input + 0 + 0 = 2000（null 容错）")
            .isEqualTo(2000L);
    }
}
