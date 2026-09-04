package com.nexusai.application.chat;

import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * title 生成行为验证（FIX-1/2/3 独立对抗核验定死 + FIX-4 补测试，规则九）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 标题生成是「触发-生成-置位」三态机
 * （titleExplicit：0 可自动 / 1 显式 /rename 不覆盖 / 2 已自动刷新停闸），
 * 任何一环越位都会造成 <i>每轮重复刷新 / 失败锁死 / 覆盖用户命名</i> 的线上事故。
 * 本测试锁 CC initReplBridge.ts:349-378 onUserMessage 语义：count1 首条生成、count3 全量刷新且
 * 成功后停闸（:377）、显式命名永不覆盖（:350）、失败可重试（haikuTitleAttemptedRef）。
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock
 * MessageMapper / SessionMapper / ModelConfigResolver / LlmProviderFactory；Haiku 用 mock
 * LlmProvider（不真调 LLM）；wsTemplate=null（sendAndLog null 守卫仅落库不推送）。
 * buildConfigForModel 未注入 DB → 抛异常落 ProviderConfig.empty() 兜底（生产对齐 G-9）。
 */
@DisplayName("[title-cc-align] maybeGenerateTitle 行为（FIX-1/2/3 + FIX-4）")
class ChatServiceTitleGenerationCcTest {

    private ChatService service;
    private MessageMapper messageMapper;
    private SessionMapper sessionMapper;
    private LlmProviderFactory llmProviderFactory;
    private LlmProvider provider;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        messageMapper = mock(MessageMapper.class);
        sessionMapper = mock(SessionMapper.class);
        ModelConfigResolver modelConfigResolver = mock(ModelConfigResolver.class);
        llmProviderFactory = mock(LlmProviderFactory.class);
        provider = mock(LlmProvider.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "modelConfigResolver", modelConfigResolver);
        ReflectionTestUtils.setField(service, "llmProviderFactory", llmProviderFactory);
        // 弱档小快模型解析成功（未配置会静默跳过，测试必须显式解析到 fast 模型）
        when(modelConfigResolver.resolveFastModelName(anyString())).thenReturn("mock-fast");
        when(llmProviderFactory.getProvider(any(ProviderConfig.class), anyString())).thenReturn(provider);
    }

    // ─────────────────────────── helpers ───────────────────────────

    private SessionRecord session(String id, String title, String modelName, Integer titleExplicit) {
        SessionRecord s = new SessionRecord();
        s.setId(id);
        s.setTitle(title);
        s.setModelName(modelName);
        s.setTitleExplicit(titleExplicit);
        return s;
    }

    private MessageRecord userMsg(String id, String content, boolean isMeta) {
        MessageRecord m = new MessageRecord();
        m.setId(id);
        m.setRole(Role.user.name());
        m.setContent(content);
        m.setIsMeta(isMeta);
        m.setCreatedAt("2026-09-04T00:00:00");
        return m;
    }

    /** Haiku mock 恒返回结构化 JSON 标题（成功路径）。 */
    private void stubHaiku(String json) {
        when(provider.chatWithOptions(any(ProviderConfig.class), anyString(), anyString(), anyString(),
            any(LlmProvider.ChatRequestOptions.class))).thenReturn(json);
    }

    /** Haiku mock 抛异常（生成失败路径 → generateTitleText 回落"新会话"占位）。 */
    private void stubHaikuFail(RuntimeException e) {
        when(provider.chatWithOptions(any(ProviderConfig.class), anyString(), anyString(), anyString(),
            any(LlmProvider.ChatRequestOptions.class))).thenThrow(e);
    }

    // ─────────────────────────── 测试用例 ───────────────────────────

    @Test
    @DisplayName("count1：单条 title-worthy user 且 title 默认 → 生成标题，titleExplicit 仍 0（可被 count3 进化）")
    void count1_generatesTitle_keepsTitleExplicitZero() {
        // WHY: 对齐 CC initReplBridge.ts:365-368「userMessageCount===1 && !hasTitle → deriveTitle(首条消息)」。
        //   count1 成功必须保持 titleExplicit=0（plan §3.4.5）：后续 userCount>=3 时 count3 才能进化覆盖
        //   自动 title。变异点：count1 未执行 / titleExplicit 被误置非 0 → title 仍占位 或 失去进化资格 → 红。
        // GIVEN: 1 条 title-worthy 用户消息 + 标题默认占位 + titleExplicit=0
        SessionRecord session = session("sess-1", "新会话", "deepseek/x", 0);
        when(messageMapper.selectCountByQuery(any())).thenReturn(1L);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            userMsg("msg-1", "帮我修复登录按钮", false)));
        stubHaiku("{\"title\":\"修复登录按钮\"}");

        // WHEN: 收口触发 maybeGenerateTitle（wsTemplate=null 仅落库）
        service.maybeGenerateTitle(session, "msg-1", "", null);

        // THEN: count1 生成首条消息摘要并落库；titleExplicit 保持 0
        assertThat(session.getTitle())
            .as("count1 用首条 title-worthy 用户消息生成非占位标题")
            .isEqualTo("修复登录按钮");
        assertThat(session.getTitleExplicit())
            .as("count1 成功保持 titleExplicit=0，count3 才可进化（对齐 CC count1→count3 覆盖自动 title）")
            .isEqualTo(0);
        verify(sessionMapper, times(1)).update(any(SessionRecord.class));
    }

    @Test
    @DisplayName("count3：titleExplicit=0 且 userCount>=3 → 刷新并置 2；再次调用不再触发（FIX-1 幂等）")
    void count3_refreshesOnce_thenIdempotent() {
        // WHY（FIX-1 对抗核验 MAJOR）: count3 成功置 titleExplicit=2 后必须停闸 —— 旧 explicitBlocked 只拦 1
        //   不拦 2，下一收口 userCount 仍>=3 又满足条件 → 每轮重复刷新（浪费 Haiku 调用 + 标题抖动）。
        //   对齐 CC initReplBridge.ts:377「return userMessageCount >= 3 后停 done」。
        // GIVEN: 已有自动标题（title 非默认 → 只走 count3）+ userCount=3 + titleExplicit=0
        SessionRecord session = session("sess-1", "修复登录按钮", "deepseek/x", 0);
        when(messageMapper.selectCountByQuery(any())).thenReturn(3L);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            userMsg("m1", "帮我修复登录按钮", false),
            userMsg("m2", "加上超时重试", false),
            userMsg("m3", "还要处理并发", false)));
        stubHaiku("{\"title\":\"重构权限模块\"}");

        // WHEN: 第一次收口 → count3 用完整会话尾部刷新
        service.maybeGenerateTitle(session, "m3", "", null);

        // THEN: 刷新成功并置 2（已自动刷新）
        assertThat(session.getTitle())
            .as("count3 用完整会话文本尾部刷新标题")
            .isEqualTo("重构权限模块");
        assertThat(session.getTitleExplicit())
            .as("count3 成功置 titleExplicit=2 = 已自动刷新，不再重复刷新")
            .isEqualTo(2);
        verify(sessionMapper, times(1)).update(any(SessionRecord.class));

        // WHEN: 再次收口（FIX-1 幂等核心断言）
        service.maybeGenerateTitle(session, "m4", "", null);

        // THEN: titleExplicit=2 → autoEligible=false → 不触发任何生成（update 总数仍为 1）
        verify(sessionMapper, times(1)).update(any(SessionRecord.class));
        assertThat(session.getTitle())
            .as("二次收口不得重复刷新已刷新标题（FIX-1 停闸）")
            .isEqualTo("重构权限模块");
        assertThat(session.getTitleExplicit())
            .as("二次收口后 titleExplicit 仍为 2")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("显式 /rename（titleExplicit=1）→ count1/count3 均不触发，永不自动覆盖")
    void explicitRename_neverOverridden() {
        // WHY: PATCH /rename 置 titleExplicit=1（SessionService.update:134），对齐 CC
        //   initReplBridge.ts:350-351「hasExplicitTitle || getCurrentSessionTitle() → return true」——
        //   用户显式命名的标题永不自动覆盖。变异点：停闸条件放宽（如只拦 count3 不拦 count1）→
        //   count1 仍会触发 → update 被调用 → 红。
        // GIVEN: titleExplicit=1（显式 /rename）+ userCount=3（即使满足 count3 数量门槛也不触发）
        SessionRecord session = session("sess-1", "用户指定标题", "deepseek/x", 1);
        when(messageMapper.selectCountByQuery(any())).thenReturn(3L);

        // WHEN: 收口触发
        service.maybeGenerateTitle(session, "m1", "", null);

        // THEN: 不触发任何生成/落库
        verify(sessionMapper, never()).update(any(SessionRecord.class));
        assertThat(session.getTitle())
            .as("显式 /rename 标题不被自动生成覆盖")
            .isEqualTo("用户指定标题");
        assertThat(session.getTitleExplicit())
            .as("显式命名标志不被改动")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("长任务聚合：userCount>=3 且 count1 未成功（title 仍默认）→ count3 补生成成功（FIX-2 不锁死）")
    void longTaskAggregation_count3FillsAfterCount1Failed() {
        // WHY（plan MAJOR-3 聚合兜底 + §3.4.6）: 长任务首轮一次性落库多条 → 收口聚合 userCount 跳变>=3，
        //   count1 首条摘要失败（title 仍默认）时不得锁死 —— 必须由 count3 用完整会话文本补生成成功
        //   （失败 titleExplicit 不变 + 有界重试，对齐 CC haikuTitleAttemptedRef）。同时验证 FIX-3：
        //   count1 输入 = 首条消息原文（A），而非最近一条。
        // GIVEN: title 仍默认 + userCount=3；count1（首条 A）失败、count3（会话尾部 A\\nB\\nC）成功
        SessionRecord session = session("sess-1", "新会话", "deepseek/x", 0);
        when(messageMapper.selectCountByQuery(any())).thenReturn(3L);
        // selectListByQuery 同时服务 extractFirstUserContent（取 msg-1="A"）与 extractConversationTextTail（拼 "A\\nB\\nC"）
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            userMsg("m1", "A", false),
            userMsg("m2", "B", false),
            userMsg("m3", "C", false)));
        when(provider.chatWithOptions(any(ProviderConfig.class), anyString(), anyString(), anyString(),
            any(LlmProvider.ChatRequestOptions.class))).thenAnswer(inv -> {
            String userMessage = inv.getArgument(3);
            // count1 输入 = 首条消息原文 "A"（"用 5-10 个字总结: A"）；count3 输入 = 会话尾部全文（更长）→ 精确匹配区分
            if ("用 5-10 个字总结: A".equals(userMessage)) {
                throw new RuntimeException("模拟 Haiku 首条摘要失败");
            }
            return "{\"title\":\"长任务补生成标题\"}";
        });

        // WHEN: 收口聚合触发（count1 先补、失败；count3 再补、成功）
        service.maybeGenerateTitle(session, "m3", "", null);

        // THEN: count3 补生成成功，标题非占位；count1 失败未锁死（titleExplicit 最终为 2 而非提前锁死）
        assertThat(session.getTitle())
            .as("count3 用完整会话文本补生成成功，标题非占位")
            .isEqualTo("长任务补生成标题");
        assertThat(session.getTitleExplicit())
            .as("count3 成功置 2；count1 失败保持 titleExplicit 不变可重试（FIX-2）")
            .isEqualTo(2);
        verify(sessionMapper, times(1)).update(any(SessionRecord.class));
    }

    @Test
    @DisplayName("count3 生成失败（回落新会话占位）→ 不置 titleExplicit=2，保持原状态可重试（FIX-2 直接验证）")
    void count3_failure_doesNotSetTitleExplicitTwo() {
        // WHY（FIX-2 对抗核验 MINOR + plan §3.4.6）: 生成失败无条件 setTitleExplicit(2) 会把「可重试」锁死为
        //   「已刷新」，后续收口永不再补 —— 且可能用"新会话"占位覆写已有自动标题（双重损坏）。
        //   失败必须保持原 title 与 titleExplicit 不变，让后续收口按条件重试。
        // GIVEN: 已有自动标题（title 非默认 → 只走 count3）+ userCount=3 + titleExplicit=0
        SessionRecord session = session("sess-1", "修复登录按钮", "deepseek/x", 0);
        when(messageMapper.selectCountByQuery(any())).thenReturn(3L);
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(
            userMsg("m1", "A", false),
            userMsg("m2", "B", false),
            userMsg("m3", "C", false)));
        stubHaikuFail(new RuntimeException("Haiku 超时"));

        // WHEN: count3 进化刷新，Haiku 生成失败 → 回落"新会话"
        service.maybeGenerateTitle(session, "m3", "", null);

        // THEN: 不落库不置位；原 title 与 titleExplicit 保持可重试状态
        verify(sessionMapper, never()).update(any(SessionRecord.class));
        assertThat(session.getTitle())
            .as("count3 失败不得用占位标题覆写已有自动标题")
            .isEqualTo("修复登录按钮");
        assertThat(session.getTitleExplicit())
            .as("count3 失败保持 titleExplicit=0（未置 2 锁死），后续收口可重试（FIX-2）")
            .isEqualTo(0);
    }
}
