package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [skill-listing-cc-align 2026-09-10] skill_listing 注入消息实时落库。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图）</b>：CC 的 skill_listing 是 attachment，随 userMessage 进入
 * transcript（processTextPrompt.ts:97 位于首条用户消息之后；messages.ts:4160-4170 渲染），resume/重放可见。
 * nexusai 端 doRun 每 run 决策后 {@code state.appendMessage(meta user, subtype='skill_listing')} —— 必须经
 * {@code persistAppendedMessage} 的 user 分支落库（保留 isMeta/subtype/author/content 原文）。
 * <b>位置键 = {@code messages.seq}（V70）</b>：经 2 参 {@code MessageService.appendMessage(dto, ts)}
 * 落库 → 内部 seq=null → nextSeq 雪花自动取号；{@code created_at}（ts）仅展示时间，不承载位置。
 * 否则 resume 后模型看不到注入的清单、位置也无法还原。删掉该分支即 RED。
 * <p>本类用 mock MessageService 验「落库分支被命中 + 字段保真 + 不广播」；<b>seq 取号</b>由
 * {@link ChatServiceSkillListingSeqStampTest}（真实 MessageService）独立守门。
 *
 * <p>纯单测：{@code new ChatService()} + ReflectionTestUtils 注入 mock（对齐
 * ChatServiceHookAdditionalContextPersistTest 同款）。
 */
@DisplayName("[skill-listing-cc-align] skill_listing 注入消息实时落库")
class ChatServiceSkillListingPersistTest {

    private static final String SESSION = "sess-1";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

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
    @DisplayName("skill_listing(user, author=attachment, isMeta) → 经 messageService.appendMessage(m, ts) 落库（seq 自动取号），字段保真、不广播")
    void skillListing_persistsViaAppendMessage() {
        AgentState state = new AgentState("sys");
        // 生产构造入口（LlmAgentLoop.injectSkillListingForRun 同源）
        ChatMessageDto listing = AgentLoopContext.skillListingMessage(SESSION, "- commit: 提交代码");
        assertThat(listing).as("非空 listing 必须构造出消息").isNotNull();

        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user");
        state.appendMessage(listing);

        ArgumentCaptor<ChatMessageDto> dtoCap = ArgumentCaptor.forClass(ChatMessageDto.class);
        ArgumentCaptor<OffsetDateTime> tsCap = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(messageService, times(1)).appendMessage(dtoCap.capture(), tsCap.capture());
        ChatMessageDto persisted = dtoCap.getValue();
        assertThat(persisted.id()).as("落库 id = 注入消息自身 id").isEqualTo(listing.id());
        assertThat(persisted.role()).isEqualTo(Role.user);
        assertThat(persisted.author()).isEqualTo("attachment");
        assertThat(persisted.subtype()).as("重放判别键").isEqualTo("skill_listing");
        assertThat(persisted.isMeta()).as("前端隐藏元消息").isTrue();
        assertThat(persisted.content()).as("content = 渲染原文").isEqualTo(listing.content());
        assertThat(tsCap.getValue()).as("createdAt 由落库侧提供（非 null；仅展示时间，位置由 seq 承载）").isNotNull();
        // 不广播（与 hook_additional_context 一致：isMeta user 隐式消息靠 GET /messages 出现）
        verify(wsTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("守卫精度: 非 skill_listing 的 meta user / skill_listing 但 isMeta=false → 均不落库")
    void nonSkillListing_doNotPersist() {
        AgentState state = new AgentState("sys");
        // isMeta=true 但 subtype 不符（sanity：不得误落）
        ChatMessageDto otherMeta = new ChatMessageDto(
            "other-1", SESSION, Role.user, "attachment", "other", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, true, false, null, "invoked_skills");
        // subtype 相符但 isMeta=false（非本通道）
        ChatMessageDto notMeta = new ChatMessageDto(
            "other-2", SESSION, Role.user, "attachment", "listing", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false, false, null, "skill_listing");

        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user");
        state.appendMessage(otherMeta);
        state.appendMessage(notMeta);

        verify(messageService, never()).appendMessage(any(ChatMessageDto.class), any(OffsetDateTime.class));
    }

    /**
     * [2026-09-10 /clear 收敛 · 先插后删] /clear 重发的整份清单（state 标记
     * {@code skillListingSupersedesPriorRows=true}）落库后，必须删除该会话其它 skill_listing 行
     * （excludeId = 新份 id）→ DB 恒 1 条。
     *
     * <p><b>WHY（CLAUDE.md 规则 9）</b>：CC 的 /clear 清空 messages → 旧清单附件消失、仅剩重发的整份；
     * nexusai 的 /clear 不删 DB 消息行 → 旧整份被 resume 重放与新整份共存 = 两份等价清单。
     * 本用例锁「先插（appendMessage）后删（deleteBySessionAndSubtype excludeId=新份）」。
     * RED：删掉 ChatService 里受 {@code isSkillListingSupersedesPriorRows()} 守卫的 delete 调用 → 红；
     * 或把 excludeId 传成旧份/不排除新份 → 断言不符 → 红。
     */
    @Test
    @DisplayName("/clear 重发整份（supersede 标记）→ 先插后删，删除该会话其它 skill_listing 行（excludeId=新份）")
    void clearSupersede_deletesPriorRows_excludingNew() {
        AgentState state = new AgentState("sys");
        ChatMessageDto listing = AgentLoopContext.skillListingMessage(SESSION, "- commit: 提交代码");

        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user");
        // /clear 触发的整份重发：loop 侧（LlmAgentLoop.injectSkillListingForRun）在 append 前置位
        state.setSkillListingSupersedesPriorRows(true);
        state.appendMessage(listing);

        verify(messageService, times(1)).appendMessage(any(ChatMessageDto.class), any(OffsetDateTime.class));
        verify(messageService, times(1))
            .deleteBySessionAndSubtype(SESSION, "skill_listing", listing.id());
    }

    /**
     * [回归守卫] 增量注入 / 全新会话首 run 的清单（{@code supersedesPriorRows=false}）<b>绝不</b>删旧行。
     *
     * <p><b>WHY（CC 语义）</b>：增量清单只含新技能名，旧整份是未变技能的唯一来源（CC 同场景旧附件留在
     * messages 数组里累积）；skill 文件变更触发的整份重发 CC 亦保留旧附件累积。若无条件「先插后删」，
     * 中途新增技能时会删掉旧整份 → 模型丢失未变技能（严重回归）。RED：去掉 supersede 守卫让 delete 恒执行 → 红。
     */
    @Test
    @DisplayName("增量注入（未置 supersede）→ 不删除任何旧行（CC 累积语义）")
    void deltaInject_doesNotDeletePriorRows() {
        AgentState state = new AgentState("sys");
        ChatMessageDto listing = AgentLoopContext.skillListingMessage(SESSION, "new-skill");

        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user");
        // 未置 supersede（默认 false）＝增量/首 run 注入
        state.appendMessage(listing);

        verify(messageService, times(1)).appendMessage(any(ChatMessageDto.class), any(OffsetDateTime.class));
        verify(messageService, never())
            .deleteBySessionAndSubtype(anyString(), anyString(), anyString());
    }
}
