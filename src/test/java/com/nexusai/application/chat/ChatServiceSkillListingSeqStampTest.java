package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [seq 排序键 · skill-listing-cc-align] skill_listing 注入消息落库的 {@code seq} 位置键取号。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：{@code messages.seq}（V70）是会话内<b>位置键</b>
 * —— 读侧 {@code MessageService.listBySession} / {@code listPageBySession} 一律 {@code ORDER BY seq ASC}，
 * {@code created_at} 退回纯展示时间。CC 的 skill_listing 是 attachment，位于首条用户消息<b>之后</b>
 * （processTextPrompt.ts:97）；nexusai 靠 write-order 还原该位置 —— 用户行先落库（seq=S_user），
 * 清单行在 run 内 turn-0 drain 之后 append → 落库 {@code MessageService.appendMessage(dto, ts)}
 * （内部 seq=null → {@code nextSeq} 雪花自动取号）→ nextSeq &gt; S_user。
 *
 * <p><b>RED 条件（硬指标）</b>：
 * <ul>
 *   <li>把 {@link ChatService} skill_listing 落库分支的 {@code messageService.appendMessage(m, ts)}
 *       改为直写 {@code messageMapper.insert} 而忘了 {@code rec.setSeq(nextSeq(sessionId))} →
 *       {@link #listingRow_carriesSeq()} 的 {@code getSeq()} 为 null → 红（位置键未知：读侧虽有
 *       {@code SEQ_NULLS_LAST_ORDER} 兜到末尾、但已还原不出「紧随用户消息之后」的位置）；
 *       注：V71 起这种写入在真库上还会被 BEFORE INSERT 触发器直接 ABORT（位置键不许为 NULL）；</li>
 *   <li>把该落库分支删掉 / appendMessage 不再被调用 → 无 insert → 两用例皆红；</li>
 *   <li>seq 取号退化为常量/复用同值 → {@link #listingRow_seqAfterPreExistingUserRow()} 的
 *       「清单 seq &gt; 用户 seq」不成立 → 红。</li>
 * </ul>
 *
 * <p><b>装配</b>：纯单测 —— {@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock mapper；
 * 注入<b>真实</b> {@link MessageService}（仅替换其 mapper），使 {@code nextSeq}（雪花）/ {@code nextCreatedAt}
 * （per-session 单调分配器）走真实实现 → 断言的是真实取号而非自证 mock。触发链路对齐生产：
 * {@code armRealTimePersist} + {@code state.appendMessage}（doRun「先 arm 后 append」）。
 */
@DisplayName("[seq 排序键] skill_listing 落库 seq 取号")
class ChatServiceSkillListingSeqStampTest {

    private static final String SESSION = "sess-1";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";
    private static final String USER_MSG_ID = "msg-user";

    /**
     * 虚构「该会话 DB 既有 max(created_at)」= 2099（远未来）：{@code nextCreatedAt} 契约 = 新写入恒晚于
     * 会话既有所有行，故落库行的 created_at 必严格晚于它；若被删则落回工厂 now()（2026）→ 断言红。
     */
    private static final OffsetDateTime SEED_EXISTING_MAX =
        OffsetDateTime.of(2099, 1, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC);

    private ChatService service;
    private MessageMapper messageMapper;
    private ToolCallMapper toolCallMapper;
    private SessionMapper sessionMapper;
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        messageMapper = mock(MessageMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        sessionMapper = mock(SessionMapper.class);

        // 真实 MessageService（seq 雪花 + created_at 单调分配器真实现）· 仅替换其落库 mapper。
        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);

        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(service, "messageService", messageService);

        // created_at 分配器 seed：会话既有 max(created_at)=2099 → 取号必 > 它；见 SEED_EXISTING_MAX。
        MessageRecord existingMax = new MessageRecord();
        existingMax.setCreatedAt(SEED_EXISTING_MAX.toString());
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(existingMax));
    }

    @Test
    @DisplayName("skill_listing 落库：seq 非空 + created_at 由分配器写入（RED：改直写 insert 漏 setSeq → 红）")
    void listingRow_carriesSeq() {
        // WHY: 落库分支必须走 messageService.appendMessage（内部 nextSeq 取号，且新写入已被 V71 触发器
        //   强制非 NULL）。若改成直写 messageMapper.insert 而不自行 setSeq → seq=NULL → 位置未知：
        //   本批读侧已把 NULL 兜到结果集末尾（MessageService.SEQ_NULLS_LAST_ORDER，不再排最前），
        //   但位置仍是「未知」→ 重放（listBySession 按 seq）还原不出「紧随用户消息之后」，
        //   与 CC 语义相反（本批核心语义被破坏），且会被读侧 ERROR 报警。
        AgentState state = new AgentState("sys");
        ChatMessageDto listing = AgentLoopContext.skillListingMessage(SESSION, "- commit: 提交代码");
        assertThat(listing).as("非空 listing 必须构造出消息").isNotNull();

        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, null, USER_MSG_ID);
        state.appendMessage(listing);

        MessageRecord rec = captureSingleInsert();
        assertThat(rec.getSubtype()).as("落库行 subtype=skill_listing（重放判别键）").isEqualTo("skill_listing");
        assertThat(rec.getSeq())
            .as("skill_listing 行必须取 seq（缺失 → ORDER BY seq 下 NULL 排最前 → 清单飞到上下文最前）")
            .isNotNull()
            .isGreaterThan(0L);
        assertThat(rec.getCreatedAt()).as("created_at 必须非空").isNotNull();
        assertThat(OffsetDateTime.parse(rec.getCreatedAt()))
            .as("created_at 必须来自 SSOT 单调分配器（恒晚于会话既有 max=2099）；落回工厂 now()(2026) 即说明 setCreatedAt 被删")
            .isAfter(SEED_EXISTING_MAX);
    }

    @Test
    @DisplayName("清单行 seq 严格大于其前置用户行（位置键序 = 紧随用户消息之后）")
    void listingRow_seqAfterPreExistingUserRow() {
        // WHY: 位置语义靠 write-order —— 用户行先落库（ChatController 在 run 前同步写，seq=S_user），
        //   清单行在 run 内 turn-0 drain 之后落库（nextSeq > S_user）。若 nextSeq 退化/复用同值，
        //   「清单紧随用户消息之后」在 DB 序上不成立 → 重放位置错。
        ChatMessageDto user = new ChatMessageDto(
            USER_MSG_ID, SESSION, Role.user, null, "初始问题", null,
            List.of(), null, null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false, false, null, null);
        // 用户行先落库（不经 state 监听器，模拟 controller 侧写入）
        messageService.appendMessage(user, null);

        AgentState state = new AgentState("sys");
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, null, USER_MSG_ID);
        ChatMessageDto listing = AgentLoopContext.skillListingMessage(SESSION, "- commit: 提交代码");
        state.appendMessage(listing);

        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        List<MessageRecord> rows = captor.getAllValues();
        assertThat(rows).as("用户行 + 清单行各落 1 行").hasSize(2);

        MessageRecord userRow = rows.get(0);
        MessageRecord listingRow = rows.get(1);
        assertThat(userRow.getRole()).isEqualTo(Role.user.name());
        assertThat(userRow.getSubtype()).as("用户行非 skill_listing").isNull();
        assertThat(listingRow.getSubtype()).isEqualTo("skill_listing");
        assertThat(userRow.getSeq()).as("用户行 seq 非空").isNotNull();
        assertThat(listingRow.getSeq())
            .as("清单行 seq 必须严格大于其前置用户行（位置键序 = 紧随用户消息之后）")
            .isNotNull()
            .isGreaterThan(userRow.getSeq());
    }

    // ─────────────────────────── helper ───────────────────────────

    /** 捕获唯一一次 messageMapper.insert 的 MessageRecord。 */
    private MessageRecord captureSingleInsert() {
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }
}
