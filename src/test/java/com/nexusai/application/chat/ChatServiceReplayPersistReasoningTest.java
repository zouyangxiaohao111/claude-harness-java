package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 历史回放 reasoning 持久化验证（前后端联调修复）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 流式路径已把 thinking 经
 * {@code MessageChunkEvent.ofReasoning} 推前端（LlmAgentLoop:4560），但实时落库（persistAppendedMessage）
 * 落库时曾只存 content、丢 reasoning → GET /messages 历史回放 reasoning 恒 null（前端
 * ChatMessageDto.reasoning + MessageList + cleanReasoning 就绪也无数据）。本测试锁定
 * "落库的 assistant 消息必须携带 reasoning"，防回归。
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock MessageMapper；
 * 生产链路触发（{@code armRealTimePersist} + {@code state.appendMessage}，替代已删 replayAndPersist
 * 反射调用）。prePersisted 场景按 doRun「先 setPrePersistedMessageIds 后 append」时序武装。
 */
@DisplayName("[联调修复] 实时落库 assistant 消息携带 reasoning")
class ChatServiceReplayPersistReasoningTest {

    private static final String SESSION = "sess-1";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

    private ChatService service;
    private MessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        messageMapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
    }

    private ChatMessageDto assistant(String content, String reasoning) {
        return new ChatMessageDto(
            "msg-asst", SESSION, Role.assistant, null, content, reasoning,
            List.of(), FinishReason.stop, null, null,
            null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    /** 生产链路触发：武装实时落库 listener 后逐条 append（对齐 doRun「先 arm 后 append」）。 */
    private void armAndAppend(AgentState state, ChatMessageDto... messages) {
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, null, "msg-user");
        for (ChatMessageDto m : messages) {
            state.appendMessage(m);
        }
    }

    @Test
    @DisplayName("纯文本 assistant 落库 reasoning 非空 → 历史回放有思考")
    void persistFinalAssistantWithReasoning() {
        // GIVEN: 纯文本 assistant 带 thinking
        AgentState state = new AgentState("sys");

        // WHEN: 生产链路实时落库（append 即触发 persistAppendedMessage）
        armAndAppend(state, assistant("你好，有什么可以帮你？", "这是思考过程…"));

        // THEN: messageMapper.insert 的 assistant 记录 reasoning == 思考过程
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getReasoning())
            .as("assistant 落库必须携带 reasoning（历史回放前端可展示思考）")
            .isEqualTo("这是思考过程…");
    }

    @Test
    @DisplayName("无 thinking 的 assistant 落库 reasoning 为 null（不误写）")
    void persistAssistantWithoutReasoningIsNull() {
        AgentState state = new AgentState("sys");

        armAndAppend(state, assistant("普通回复", null));

        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getReasoning())
            .as("无思考时 reasoning 保持 null（干净语义，前端 cleanReasoning 直接放行）")
            .isNull();
    }

    @Test
    @DisplayName("prePersistedMessageIds 命中的历史 assistant 不重插（防 PK 冲突双写）")
    void skipPrePersistedHistoryAssistant() {
        // WHY（fix-loop-resume-history 双通道铁律）: doRun 主路径恢复把 DB 历史灌入 state.messages()
        //   并登记 prePersistedMessageIds。若实时落库仍遍历重插 history 消息 —— 其携带
        //   原始 DB id 作 PK，重插必 duplicate-key 崩；合成 sentinel/Continue（临时 UUID id，已收集进
        //   集合）CC 也不写 transcript。变异点：跳过逻辑不生效 → insert 两次（h1 + final）→ 红。
        // GIVEN: 注入历史 assistant（已存 DB id=h1，带 tool_calls → 不跳过时会在 append 时重插）+
        //        当前轮新 assistant（纯文本）+ prePersistedMessageIds={h1}
        ChatMessageDto history = new ChatMessageDto(
            "h1", SESSION, Role.assistant, null, "历史工具调用", null,
            List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
            FinishReason.tool_calls, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        ChatMessageDto fresh = assistant("本轮新回复", null);
        AgentState state = new AgentState("sys");
        state.setPrePersistedMessageIds(java.util.Set.of("h1"));

        // WHEN: 生产链路实时落库（h1 先被 prePersisted 跳过，fresh 落库）
        armAndAppend(state, history, fresh);

        // THEN: 历史 h1 被跳过，insert 仅 fresh 一次
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getId())
            .as("历史消息已存 DB，实时落库不得重插（防 PK 冲突）；仅本轮新 assistant 落库")
            .isNotEqualTo("h1");
    }

    @Test
    @DisplayName("注入历史（末条 assistant ∈ prePersisted）+ 本轮未产出新 assistant → 零落库（幽灵行防御）")
    void skipFinalPersistWhenLastAssistantIsInjectedHistory() {
        // WHY（fix-loop-resume-history 新回归）: doRun 注入块把 DB 历史灌入 state.messages() 后，
        //   lastAssistant() 从末向前扫描会命中注入的历史 assistant。若本轮 run 未 append 新
        //   assistant 即退出（取消/中断 / NO_ASSISTANT_TEXT 空流 / stream timeout / stop-hook 阻断），
        //   历史内容会被以全新随机 id 落库 = 幽灵重复行。变异点：prePersisted 跳过未生效 → insert 一次
        //   （内容=历史回复）→ 红。
        // GIVEN: 仅注入历史 assistant（id=h1 ∈ prePersisted），本轮无新产出
        ChatMessageDto history = new ChatMessageDto(
            "h1", SESSION, Role.assistant, null, "历史回复", null,
            List.of(), FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        AgentState state = new AgentState("sys");
        state.setPrePersistedMessageIds(java.util.Set.of("h1"));

        // WHEN: 生产链路实时落库（h1 命中 prePersisted → append 时点即跳过）
        armAndAppend(state, history);

        // THEN: 历史 h1 被跳过 → messageMapper.insert 零调用（不得以新 id 重写历史内容）
        verify(messageMapper, times(0)).insert(any());
    }

    @Test
    @DisplayName("末条 assistant 是 deserializer 合成 sentinel → 零落库（sentinel 绝不可落库）")
    void skipFinalPersistWhenLastAssistantIsSentinel() {
        // WHY: 中断恢复（末条为 user）时 SessionResumeDeserializer splice 注入
        //   assistant sentinel「No response requested.」（conversationRecovery.ts:234-248），其 id
        //   已被注入块登记 prePersistedMessageIds。若实时落库按 lastAssistant() 命中 sentinel 并以
        //   新 id 落库 → 幽灵「No response requested.」行污染 transcript + 前端。变异点：prePersisted
        //   跳过未生效 → insert 一次（内容=sentinel）→ 红。
        // GIVEN: 注入历史 assistant（h1）+ sentinel（s1），两者均 ∈ prePersisted
        ChatMessageDto history = new ChatMessageDto(
            "h1", SESSION, Role.assistant, null, "历史回复", null,
            List.of(), FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        ChatMessageDto sentinel = new ChatMessageDto(
            "s1", SESSION, Role.assistant, null, "No response requested.", null,
            List.of(), FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        AgentState state = new AgentState("sys");
        state.setPrePersistedMessageIds(java.util.Set.of("h1", "s1"));

        // WHEN: 生产链路实时落库（h1 + sentinel 均命中 prePersisted → 跳过）
        armAndAppend(state, history, sentinel);

        // THEN: sentinel 为末条 assistant 且 ∈ prePersisted → 零落库
        verify(messageMapper, times(0)).insert(any());
    }

    @Test
    @DisplayName("注入历史 + 本轮新 assistant → 仅新 assistant 落库（历史不得以新 id 重写）")
    void persistNewAssistantWhileSkippingInjectedHistory() {
        // WHY: 注入历史后本轮正常产出新 assistant → 仅新 assistant 走落库；历史内容绝不得
        //   以新随机 id 重写。变异点：prePersisted 跳过误伤把历史当新产出重写 → 双写 → 红；或
        //   把新 assistant 也误跳过 → 不落库 → 红。
        // GIVEN: 注入历史 assistant（h1 ∈ prePersisted）+ 本轮新 assistant（msg-new ∉ prePersisted）
        ChatMessageDto history = new ChatMessageDto(
            "h1", SESSION, Role.assistant, null, "历史回复", null,
            List.of(), FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        ChatMessageDto fresh = new ChatMessageDto(
            "msg-new", SESSION, Role.assistant, null, "本轮新回复", "本轮思考", null,
            FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        AgentState state = new AgentState("sys");
        state.setPrePersistedMessageIds(java.util.Set.of("h1"));

        // WHEN: 生产链路实时落库（h1 跳过，msg-new 落库）
        armAndAppend(state, history, fresh);

        // THEN: 仅 msg-new 落库一次，内容=本轮新回复（历史未被重写、新 assistant 未被误跳）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getContent())
            .as("注入历史后本轮新 assistant 必须落库（仅跳过注入历史）")
            .isEqualTo("本轮新回复");
    }

    @Test
    @DisplayName("占位标题（'新会话'）视为默认 → 摘要生成不被跳过")
    void placeholderTitleIsDefault() {
        // WHY: 前端创建会话硬编码 title:'新会话'（nexusai App.tsx:433/514/846），修复前被
        //   looksLikeDefault 误判为"显式命名"→ maybeGenerateTitle 直接 return → list 里 title 恒为占位。
        //   对齐 CC initReplBridge.ts:299-336：仅显式 /rename 才阻止生成，占位必须可被覆盖。
        assertThat(ChatService.isDefaultTitle("新会话", "deepseek/deepseek-v4-flash")).isTrue();
        assertThat(ChatService.isDefaultTitle("新对话", "deepseek/x")).isTrue();
        assertThat(ChatService.isDefaultTitle("Untitled", "deepseek/x")).isTrue();
        assertThat(ChatService.isDefaultTitle("New Chat", "deepseek/x")).isTrue();
    }

    @Test
    @DisplayName("null / blank / 等于 modelName 仍视为默认（旧语义不回归）")
    void legacyDefaultTitlesStayDefault() {
        assertThat(ChatService.isDefaultTitle(null, "deepseek/x")).isTrue();
        assertThat(ChatService.isDefaultTitle("  ", "deepseek/x")).isTrue();
        assertThat(ChatService.isDefaultTitle("deepseek/x", "deepseek/x")).isTrue();
    }

    @Test
    @DisplayName("用户显式命名（非占位）不视为默认 → 不被自动覆盖")
    void explicitUserTitleIsNotDefault() {
        // WHY: 用户自定义标题（等价 CC /rename 显式命名）绝不能被摘要生成 clobber。
        assertThat(ChatService.isDefaultTitle("修复登录按钮", "deepseek/x")).isFalse();
        assertThat(ChatService.isDefaultTitle("OAuth 对接", "deepseek/x")).isFalse();
    }

    @Test
    @DisplayName("message.complete 收口：末条 assistant 带 reasoning → lastAssistantReasoning 返回真实值")
    void lastAssistantReasoning_returnsLastAssistantReasoning() throws Exception {
        // WHY: 修复前 MessageCompleteEvent 构造 reasoning 恒传 null → 前端收口消息思考丢失。
        //   lastAssistantReasoning 是 message.complete 事件 reasoning 的数据源（对齐
        //   persistAppendedMessage finalReasoning = m.reasoning() 捕获语义）。变异点：取错消息
        //   （取第一条 assistant / 取 content）→ 红。
        // GIVEN: 两条 assistant，末条带 thinking
        AgentState state = new AgentState("sys");
        state.appendMessage(assistant("第一条回复", null));
        state.appendMessage(assistant("末条回复", "末条思考…"));

        // WHEN/THEN
        assertThat(lastReasoning(state))
            .as("message.complete 收口 reasoning 必须取末条 assistant 的真实 reasoning")
            .isEqualTo("末条思考…");
    }

    @Test
    @DisplayName("message.complete 收口：末条 assistant 无 thinking → lastAssistantReasoning 返回 null（不误补）")
    void lastAssistantReasoning_noThinking_returnsNull() throws Exception {
        AgentState state = new AgentState("sys");
        state.appendMessage(assistant("普通回复", null));

        assertThat(lastReasoning(state))
            .as("无思考时 reasoning 保持 null（干净语义，前端 cleanReasoning 直接放行）")
            .isNull();
    }

    @Test
    @DisplayName("message.complete 收口：仅 user 消息 → lastAssistantReasoning 返回 null（安全兜底）")
    void lastAssistantReasoning_noAssistant_returnsNull() throws Exception {
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "u1", SESSION, Role.user, null, "你好", null,
            null, null, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false));

        assertThat(lastReasoning(state)).isNull();
    }

    /** 反射调用 private {@code lastAssistantReasoning(AgentState)}（仍存在，收口 helper）。 */
    private String lastReasoning(AgentState state) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("lastAssistantReasoning", AgentState.class);
        m.setAccessible(true);
        return (String) m.invoke(service, state);
    }
}
