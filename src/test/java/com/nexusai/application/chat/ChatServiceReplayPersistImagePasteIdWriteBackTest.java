package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * [图片 F5 回传修复] replayAndPersist 回写 imagePasteIds 目标必须为 lastUserMessageId。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 前端图片 ≤5MB base64 直传无 contentId →
 * {@code createUserMessage} 落库 imagePasteIds=null；LlmAgentLoop F1 自增分配 id + A4 生成带
 * imagePasteIds 的 user 消息（id=随机 UUID，buildUserMessageWithImages id=null → toMessage 随机 UUID）
 * → replayAndPersist 回写 {@code updateUserImagePasteIds}。修复前回写目标用 {@code m.id()}
 * （A4 随机 UUID，DB 无此行 → SQLite UPDATE 0 行静默）→ 已落库 user 消息 image_paste_ids 恒 null
 * → F5 出站无 id → 前端不拉图。变异点：
 * <ul>
 *   <li>回写目标用 {@code m.id()}（A4 随机 UUID）→ UPDATE 命不中已落库行 → 红</li>
 *   <li>回写目标用 {@code lastUserMessageId}（controller 预落库行 id）→ 绿</li>
 *   <li>mid-turn 排队推进后回写目标未跟随 → 图片归属排队 flow 失败 → 红</li>
 * </ul>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock mapper +
 * mock messageService + mock wsTemplate；{@code replayAndPersist} 私有方法反射调用（同
 * {@code ChatServiceReplayPersistReasoningDurationTest} 模式）。
 */
@DisplayName("[图片 F5 回传修复] replayAndPersist 回写 imagePasteIds 目标 = lastUserMessageId")
class ChatServiceReplayPersistImagePasteIdWriteBackTest {

    private ChatService service;
    private MessageMapper messageMapper;
    private ToolCallMapper toolCallMapper;
    private MessageService messageService;
    private SimpMessagingTemplate wsTemplate;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        messageMapper = mock(MessageMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        messageService = mock(MessageService.class);
        wsTemplate = mock(SimpMessagingTemplate.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "messageService", messageService);
    }

    @Test
    @DisplayName("A4 临时 user 消息（随机 UUID id）→ 回写目标 = lastUserMessageId 而非 A4 随机 UUID")
    void imagePasteIdsWriteBackTargetsLastUserMessageId() throws Exception {
        // GIVEN: A4 生成的 user 消息——id 为随机 UUID（buildUserMessageWithImages id=null），
        //   imagePasteIds=[1,2]（F1 自增分配）。controller 预落库 user 消息 id = 入参 userMessageId "msg-user"。
        String a4RandomId = "a4-" + java.util.UUID.randomUUID();
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            a4RandomId, "sess-1", Role.user, null, "带图片的消息", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of("1", "2"), null, false, false));

        invokeReplay(state, "msg-user");

        // THEN: 回写目标 = lastUserMessageId（controller 预落库行 id），绝非 A4 随机 UUID（DB 无此行）
        verify(messageService).updateUserImagePasteIds(eq("msg-user"), eq(List.of("1", "2")));
        verify(messageService, never()).updateUserImagePasteIds(eq(a4RandomId), eq(List.of("1", "2")));
    }

    @Test
    @DisplayName("mid-turn 排队推进 lastUserMessageId 后 → 回写目标跟随排队 uuid（图片归属排队 flow）")
    void imagePasteIdsWriteBackFollowsQueuedAdvance() throws Exception {
        // GIVEN: mid-turn 排队 user 先推进 lastUserMessageId → 后续 A4 图片归属排队 flow
        //   （对齐事件通道 effectiveStreamUserMessageId 逐条动态 + CC parentUuid 链根）
        String a4RandomId = "a4-" + java.util.UUID.randomUUID();
        AgentState state = new AgentState("sys");
        state.appendMessage(LlmAgentLoop.toMessage(Role.user, "排队追问", null, "msg-queued-1"));
        state.addInjectedQueuedMessage("msg-queued-1", "排队追问");
        state.appendMessage(new ChatMessageDto(
            a4RandomId, "sess-1", Role.user, null, "排队期间的带图消息", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of("3"), null, false, false));

        invokeReplay(state, "msg-original");

        // THEN: 回写目标被推进为排队 uuid（非原始轮 user），图片归排队 flow
        verify(messageService).updateUserImagePasteIds(eq("msg-queued-1"), eq(List.of("3")));
    }

    /** 反射调用 private {@code replayAndPersist(sessionId, userMessageId, state, streamTopic, wsTemplate)}。 */
    private void invokeReplay(AgentState state, String userMessageId) throws Exception {
        Method m = ChatService.class.getDeclaredMethod(
            "replayAndPersist",
            String.class, String.class, AgentState.class, String.class, SimpMessagingTemplate.class);
        m.setAccessible(true);
        m.invoke(service, "sess-1", userMessageId, state, "/topic/stream", wsTemplate);
    }
}
