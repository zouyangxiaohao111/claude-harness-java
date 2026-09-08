package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [session-start-cc-align P0-1] SessionStart hook_additional_context 注入消息实时落库（核心修复）。
 *
 * <p><b>根因</b>：LlmAgentLoop §14（:2689-2695）每次用户消息执行 SessionStart hook，注入一条
 * {@code Role.user + author=hook + subtype=hook_additional_context + isMeta=true} 消息（随机 UUID）。
 * 旧 {@code persistAppendedMessage} user 分支（图片回写 + mid-turn 排队后）无条件 {@code return}
 * 跳过 → 注入消息不落库 → 09-01 anyMatch(subtype) 守卫跨 run 失效（恢复历史里无它）→ 每轮尾部重塞一份。
 *
 * <p><b>本测试锁定（规则九 · 验证意图）</b>：修复后注入消息经 {@code MessageService.appendMessage(m, 单调ts)}
 * 落库（保留注入消息自身 id/role=user/author=hook/subtype/isMeta=true/content 原文），且
 * <ul>
 *   <li>新分支不得向 wsTemplate 广播（hook isMeta=true 前端已隐藏，靠 GET /messages 出现即可）</li>
 *   <li>created_at 使用 ctx 单调 ts（baseTs.plusNanos(seq)，:1251）——两次顺序 append 严格递增</li>
 *   <li>兄弟分支零改动：普通 user / 图片 user / 排队 user 不受新分支拦截（守卫判据精确：author=hook +
 *       subtype=hook_additional_context + isMeta=true）</li>
 * </ul>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock messageService +
 * mock mapper + mock wsTemplate；生产链路触发（{@code armRealTimePersist} + {@code state.appendMessage}）。
 */
@DisplayName("[session-start-cc-align P0-1] hook_additional_context 注入消息实时落库")
class ChatServiceHookAdditionalContextPersistTest {

    private static final String SESSION = "sess-1";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";
    /** §14 注入文本形态（对齐 CC messages.ts:4117-4127 wrapInSystemReminder + createUserMessage isMeta）。 */
    private static final String WRAPPED_CONTEXT =
        "<system-reminder>\nSessionStart hook additional context: zjkycode orchestrating\n</system-reminder>";

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

    /** 生产链路触发：武装实时落库 listener 后逐条 append（对齐 doRun「先 arm 后 append」→ §14 注入命中 listener）。 */
    private void armAndAppend(AgentState state, String userMessageId, ChatMessageDto... messages) {
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, userMessageId);
        for (ChatMessageDto m : messages) {
            state.appendMessage(m);
        }
    }

    /** 复刻 LlmAgentLoop §14 注入构造（:2689-2695：22 参 canonical 形状，isMeta=第 19 参 true）。 */
    private static ChatMessageDto injectedHook(String id, String content) {
        return new ChatMessageDto(
            id, SESSION, Role.user, "hook", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, true, false,
            null, "hook_additional_context");
    }

    @Test
    @DisplayName("P0-1: hook_additional_context(user, author=hook, isMeta) → 经 messageService.appendMessage(m, ts) 落库，字段保真、不广播")
    void hookMessage_persistsViaAppendMessage() {
        // WHY: 修复前 user 分支无条件 return → 注入消息不落库 → 守卫跨 run miss → 每轮尾部重塞一份。
        //      修复后必须经 appendMessage 落库（保留注入自身 id / isMeta / subtype / content 原文）。
        AgentState state = new AgentState("sys");
        String hookId = "hook-" + UUID.randomUUID();
        ChatMessageDto hook = injectedHook(hookId, WRAPPED_CONTEXT);

        // WHEN: §14 注入消息 append → appendListener → persistAppendedMessage hook 分支
        armAndAppend(state, "msg-user", hook);

        // THEN: 恰好 1 次 appendMessage(m, 单调ts)，字段保真
        ArgumentCaptor<ChatMessageDto> dtoCap = ArgumentCaptor.forClass(ChatMessageDto.class);
        ArgumentCaptor<OffsetDateTime> tsCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(messageService, times(1)).appendMessage(dtoCap.capture(), tsCap.capture());
        ChatMessageDto persisted = dtoCap.getValue();
        assertThat(persisted.id()).as("落库 id = 注入消息自身 id（UUID 保真，供守卫/去重引用）").isEqualTo(hookId);
        assertThat(persisted.role()).as("role 保留 Role.user").isEqualTo(Role.user);
        assertThat(persisted.author()).as("author 保留 hook").isEqualTo("hook");
        assertThat(persisted.subtype()).as("subtype 保留（toDto :957 读回 → 守卫命中）").isEqualTo("hook_additional_context");
        assertThat(persisted.isMeta()).as("isMeta=true（前端隐藏 / CC messages.ts:4117 createUserMessage({isMeta:true})）").isTrue();
        assertThat(persisted.content()).as("content = 注入原文（<system-reminder> 包装体）").isEqualTo(WRAPPED_CONTEXT);
        assertThat(tsCap.getValue()).as("createdAt 来自 ctx 单调 ts（非 null）").isNotNull();
        // THEN: 不向 wsTemplate 广播（hook isMeta=true 前端隐藏，user 分支现状不推送事件）
        verify(wsTemplate, never()).convertAndSend(anyString(), any(Object.class));
        // THEN: 不误走兄弟分支（图片回写 / mid-turn 排队均不触发）
        verify(messageService, never()).updateUserImagePasteIds(anyString(), any(List.class));
    }

    @Test
    @DisplayName("P0-1: createdAt 用 ctx 单调 ts——同 ctx 顺序 append 两次 ts 严格递增（baseTs.plusNanos(seq)）")
    void appendMessage_createdAtUsesMonotonicTs() {
        // WHY: hook 行 created_at 必须落在这轮 ctx 单调序列上（与 assistant/工具行保序，DB created_at ASC）。
        AgentState state = new AgentState("sys");

        armAndAppend(state, "msg-user",
            injectedHook("hook-1-" + UUID.randomUUID(), WRAPPED_CONTEXT),
            injectedHook("hook-2-" + UUID.randomUUID(), WRAPPED_CONTEXT));

        ArgumentCaptor<OffsetDateTime> tsCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(messageService, times(2)).appendMessage(any(ChatMessageDto.class), tsCap.capture());
        List<OffsetDateTime> ts = tsCap.getAllValues();
        assertThat(ts.get(1)).as("同 ctx 第二次 append 的 created_at 必须严格晚于第一次（单调保序）")
            .isAfter(ts.get(0));
    }

    @Test
    @DisplayName("P0-1 守卫精度: isMeta=false 的 hook / 非 hook 作者的 isMeta user → 均不落库（判据精确）")
    void nonMatchingMetaOrHookUsers_doNotPersist() {
        // WHY: 守卫判据 = author=hook && subtype=hook_additional_context && isMeta=true。三者缺一不得落库——
        //   误匹配会污染普通 user / 记忆注入（memory 是 isMeta user 但 author!=hook）/ compact 通道（isMeta=false）。
        AgentState state = new AgentState("sys");

        // (a) isMeta=false 但 author=hook + subtype（compact 通道形态，走其它持久化路径，不得经本分支双落）
        ChatMessageDto compactStyle = new ChatMessageDto(
            "hook-notmeta", SESSION, Role.user, "hook", "compact additional context", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false,
            null, "hook_additional_context");
        // (b) isMeta=true user 但 author!=hook（记忆注入等随机 UUID，作者非 hook → 不命中）
        ChatMessageDto memoryStyle = new ChatMessageDto(
            "mem-" + UUID.randomUUID(), SESSION, Role.user, "memory", "remembered context", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, true, false, null, null);
        // (c) 普通 user（isMeta=false, author=null）——controller 已预落，实时分支不得重复 insert
        ChatMessageDto ordinaryUser = new ChatMessageDto(
            "msg-user", SESSION, Role.user, null, "hello", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, null);

        armAndAppend(state, "msg-user", compactStyle, memoryStyle, ordinaryUser);

        verify(messageService, never()).appendMessage(any(ChatMessageDto.class), any(OffsetDateTime.class));
        verify(messageService, never()).updateUserImagePasteIds(anyString(), any(List.class));
    }
}
