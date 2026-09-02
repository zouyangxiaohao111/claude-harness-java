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
 * {@code MessageChunkEvent.ofReasoning} 推前端（LlmAgentLoop:4560），但 {@code replayAndPersist}
 * 落库时曾只存 content、丢 reasoning → GET /messages 历史回放 reasoning 恒 null（前端
 * ChatMessageDto.reasoning + MessageList + cleanReasoning 就绪也无数据）。本测试锁定
 * "落库的 assistant 消息必须携带 reasoning"，防回归。
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock MessageMapper；
 * {@code replayAndPersist} 为私有方法，反射调用（与 ChatServiceResumeWorktreeTest 同模式）。
 */
@DisplayName("[联调修复] replayAndPersist 落库 assistant 消息携带 reasoning")
class ChatServiceReplayPersistReasoningTest {

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
            "msg-asst", "sess-1", Role.assistant, null, content, reasoning,
            List.of(), FinishReason.stop, null, null,
            null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    @Test
    @DisplayName("纯文本 final assistant 落库 reasoning 非空 → 历史回放有思考")
    void persistFinalAssistantWithReasoning() throws Exception {
        // GIVEN: state 含一条带 thinking 的纯文本 assistant（无 tool_calls → 走 final 落库）
        AgentState state = new AgentState("sys");
        state.appendMessage(assistant("你好，有什么可以帮你？", "这是思考过程…"));

        // WHEN: 回放持久化（生产路径 replayAndPersist）
        invokeReplay(state);

        // THEN: messageMapper.insert 的 final assistant 记录 reasoning == 思考过程
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getReasoning())
            .as("final assistant 落库必须携带 reasoning（历史回放前端可展示思考）")
            .isEqualTo("这是思考过程…");
    }

    @Test
    @DisplayName("无 thinking 的 assistant 落库 reasoning 为 null（不误写）")
    void persistAssistantWithoutReasoningIsNull() throws Exception {
        // GIVEN: state 含一条无 thinking 的纯文本 assistant
        AgentState state = new AgentState("sys");
        state.appendMessage(assistant("普通回复", null));

        // WHEN: 回放持久化
        invokeReplay(state);

        // THEN: 落库记录 reasoning 为 null（与修复前行为一致，不误补）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getReasoning())
            .as("无思考时 reasoning 保持 null（干净语义，前端 cleanReasoning 直接放行）")
            .isNull();
    }

    @Test
    @DisplayName("prePersistedMessageIds 命中的历史 assistant 不重插（防 PK 冲突双写）")
    void skipPrePersistedHistoryAssistant() throws Exception {
        // WHY（fix-loop-resume-history 双通道铁律）: doRun 主路径恢复把 DB 历史灌入 state.messages()
        //   并登记 prePersistedMessageIds。若 replayAndPersist 仍遍历重插 history 消息 —— 其携带
        //   原始 DB id 作 PK，重插必 duplicate-key 崩；合成 sentinel/Continue（临时 UUID id，已收集进
        //   集合）CC 也不写 transcript。变异点：跳过逻辑不生效 → insert 两次（h1 + final）→ 红。
        // GIVEN: 注入历史 assistant（已存 DB id=h1，带 tool_calls → 不跳过时会在循环内重插）+
        //        当前轮新 assistant（纯文本，走 final 落库）+ prePersistedMessageIds={h1}
        ChatMessageDto history = new ChatMessageDto(
            "h1", "sess-1", Role.assistant, null, "历史工具调用", null,
            List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
            FinishReason.tool_calls, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        ChatMessageDto fresh = assistant("本轮新回复", null);
        AgentState state = new AgentState("sys");
        state.appendMessage(history);
        state.appendMessage(fresh);
        state.setPrePersistedMessageIds(java.util.Set.of("h1"));

        // WHEN: 回放持久化
        invokeReplay(state);

        // THEN: 历史 h1 被跳过，insert 仅 final 一次
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getId())
            .as("历史消息已存 DB，replayAndPersist 不得重插（防 PK 冲突）；仅本轮 final 落库")
            .isNotEqualTo("h1");
    }

    @Test
    @DisplayName("注入历史（末条 assistant ∈ prePersisted）+ 本轮未产出新 assistant → 零落库（幽灵行防御）")
    void skipFinalPersistWhenLastAssistantIsInjectedHistory() throws Exception {
        // WHY（fix-loop-resume-history 新回归）: doRun 注入块把 DB 历史灌入 state.messages() 后，
        //   lastAssistant() 从末向前扫描会命中注入的历史 assistant。若本轮 run 未 append 新
        //   assistant 即退出（取消/中断 / NO_ASSISTANT_TEXT 空流 / stream timeout / stop-hook 阻断），
        //   历史内容会被以全新随机 id 落库 = 幽灵重复行。变异点：final 块未跳过注入历史 → insert 一次
        //   （内容=历史回复）→ 红。
        // GIVEN: 仅注入历史 assistant（id=h1 ∈ prePersisted），本轮无新产出
        ChatMessageDto history = new ChatMessageDto(
            "h1", "sess-1", Role.assistant, null, "历史回复", null,
            List.of(), FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        AgentState state = new AgentState("sys");
        state.appendMessage(history);
        state.setPrePersistedMessageIds(java.util.Set.of("h1"));

        // WHEN: 回放持久化
        invokeReplay(state);

        // THEN: final 块跳过注入历史 → messageMapper.insert 零调用（不得以新 id 重写历史内容）
        verify(messageMapper, times(0)).insert(any());
    }

    @Test
    @DisplayName("末条 assistant 是 deserializer 合成 sentinel → 零落库（sentinel 绝不可落库）")
    void skipFinalPersistWhenLastAssistantIsSentinel() throws Exception {
        // WHY: 中断恢复（末条为 user）时 SessionResumeDeserializer splice 注入
        //   assistant sentinel「No response requested.」（conversationRecovery.ts:234-248），其 id
        //   已被注入块登记 prePersistedMessageIds。若 final 块用 lastAssistant() 命中 sentinel 并以
        //   新 id 落库 → 幽灵「No response requested.」行污染 transcript + 前端。变异点：final 块
        //   未按「末条 assistant id ∈ prePersisted」判定 → insert 一次（内容=sentinel）→ 红。
        // GIVEN: 注入历史 assistant（h1）+ sentinel（s1），两者均 ∈ prePersisted
        ChatMessageDto history = new ChatMessageDto(
            "h1", "sess-1", Role.assistant, null, "历史回复", null,
            List.of(), FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        ChatMessageDto sentinel = new ChatMessageDto(
            "s1", "sess-1", Role.assistant, null, "No response requested.", null,
            List.of(), FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        AgentState state = new AgentState("sys");
        state.appendMessage(history);
        state.appendMessage(sentinel);
        state.setPrePersistedMessageIds(java.util.Set.of("h1", "s1"));

        // WHEN: 回放持久化
        invokeReplay(state);

        // THEN: sentinel 为末条 assistant 且 ∈ prePersisted → 零落库
        verify(messageMapper, times(0)).insert(any());
    }

    @Test
    @DisplayName("注入历史 + 本轮新 assistant → 仅新 assistant 落库（历史不得以新 id 重写）")
    void persistNewAssistantWhileSkippingInjectedHistory() throws Exception {
        // WHY: 注入历史后本轮正常产出新 assistant → 仅新 assistant 走 final 落库；历史内容绝不得
        //   以新随机 id 重写。变异点：final 块把「末条 assistant ∈ prePersisted」误判为跳过 → 新
        //   assistant 也不落库 → 红；或误伤把历史当新产出重写 → 双写 → 红。
        // GIVEN: 注入历史 assistant（h1 ∈ prePersisted）+ 本轮新 assistant（msg-new ∉ prePersisted）
        ChatMessageDto history = new ChatMessageDto(
            "h1", "sess-1", Role.assistant, null, "历史回复", null,
            List.of(), FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        ChatMessageDto fresh = new ChatMessageDto(
            "msg-new", "sess-1", Role.assistant, null, "本轮新回复", "本轮思考", null,
            FinishReason.stop, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
        AgentState state = new AgentState("sys");
        state.appendMessage(history);
        state.appendMessage(fresh);
        state.setPrePersistedMessageIds(java.util.Set.of("h1"));

        // WHEN: 回放持久化
        invokeReplay(state);

        // THEN: 仅 final 落库一次，内容=本轮新回复（历史未被重写、新 assistant 未被误跳）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getContent())
            .as("注入历史后本轮新 assistant 必须落库（final 块只跳过注入历史）")
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
        //   replayAndPersist :432 finalReasoning = m.reasoning() 捕获语义）。变异点：取错消息
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
            "u1", "sess-1", Role.user, null, "你好", null,
            null, null, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false));

        assertThat(lastReasoning(state)).isNull();
    }

    /** 反射调用 private {@code lastAssistantReasoning(AgentState)}。 */
    private String lastReasoning(AgentState state) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("lastAssistantReasoning", AgentState.class);
        m.setAccessible(true);
        return (String) m.invoke(service, state);
    }

    /** 反射调用 private {@code replayAndPersist(sessionId, userMessageId, state, streamTopic, wsTemplate)}。 */
    private void invokeReplay(AgentState state) throws Exception {
        Method m = ChatService.class.getDeclaredMethod(
            "replayAndPersist",
            String.class, String.class, AgentState.class, String.class, SimpMessagingTemplate.class);
        m.setAccessible(true);
        m.invoke(service, "sess-1", "msg-user", state, "/topic/stream", null);
    }
}
