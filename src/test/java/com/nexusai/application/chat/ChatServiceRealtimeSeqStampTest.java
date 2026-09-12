package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.compact.SnipCompactor;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
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
 * [seq 排序键] ChatService 实时落库 4 条直写 {@code messageMapper.insert} 分支的 seq / created_at 取号。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：{@code messages.seq}（V70）是会话内<b>位置键</b>
 * —— 读侧 {@code MessageService.listRawForTranscript} / {@code listPageBySession} 一律 {@code ORDER BY seq ASC}，
 * compact 重挂 kept 段也只改 seq。ChatService 实时落库是会话消息的<b>主要 writer</b>（4 处直写
 * {@code messageMapper.insert} 绕过 MessageService），每处都必须
 * {@code rec.setSeq(nextSeq(sessionId))} + {@code rec.setCreatedAt(ts.toString())}：
 * <ul>
 *   <li><b>seq 缺失</b> → 该行 seq=NULL；SQLite 中 NULL 在 {@code ORDER BY seq ASC} 下<b>排最前</b>
 *       → 新消息被排到会话开头，前端顺序错乱；分页游标（{@code lt("seq", pivot)}）静默丢行。</li>
 *   <li><b>created_at 缺失</b> → 时间列null，前端「X 分钟前」与 compact 边界时间基判据同时崩坏。</li>
 * </ul>
 * 这 4 行此前零覆盖 —— 删掉任一处 {@code setSeq}/{@code setCreatedAt} 全仓测试仍绿（静默退化）。
 *
 * <p><b>RED 条件（硬指标）</b>：删除/改错以下任一行生产代码 → 对应测试断言必红：
 * <ul>
 *   <li>{@code ChatService.java:1556} 纯文本 assistant 的 {@code rec.setSeq(nextSeq(sessionId))}
 *       → {@link #plainTextAssistant_rowCarriesSeqAndCreatedAt()} 的 {@code getSeq()} 为 null → 红</li>
 *   <li>{@code ChatService.java:1504} 工具轮 assistant 的 {@code rec.setSeq(...)}
 *       → {@link #toolCallsAssistant_rowCarriesSeqAndCreatedAt()} 红</li>
 *   <li>{@code ChatService.java:1594} tool 行的 {@code rec.setSeq(...)}
 *       → {@link #toolResultRow_carriesSeqAndCreatedAt()} 红</li>
 *   <li>{@code ChatService.java:1378} snip_boundary 行的 {@code rec.setSeq(...)}
 *       → {@link #snipBoundaryRow_carriesSeqAndCreatedAt()} 红</li>
 *   <li>任一处 {@code rec.setCreatedAt(ts.toString())} → 该行 created_at 会落回 factory 默认的
 *       {@code OffsetDateTime.now()}（2026 年），而本测试把会话既有的 max(created_at) 虚构为
 *       {@link #SEED_EXISTING_MAX}（2099 年）→ 「新写入恒晚于该会话所有已有行」不成立 →
 *       {@link #assertAllocatorStamped} 红（<b>注意</b>：assistant/tool 三个分支的
 *       {@code newAssistantMessage}/{@code newToolMessage} 工厂本身就预置了 {@code now()}，
 *       故「仅断言非 null」抓不到该变异 —— 必须用未来种子 + 严格晚于来判）</li>
 *   <li>{@code nextSeq} 的四条分支共用：把 {@code nextSeq} 改成常量/复用同一值
 *       → {@link #sameSessionMultiWrite_seqStrictlyIncreasing_createdAtMonotonic()} 红</li>
 * </ul>
 *
 * <p><b>装配</b>：纯单测（无 Spring 上下文）—— {@code new ChatService()} + {@link ReflectionTestUtils}
 * 注入 mock mapper；注入<b>真实</b> {@link MessageService}（仅替换其 messageMapper），
 * 使 {@code nextSeq}（雪花）/{@code nextCreatedAt}（per-session 单调分配器）走真实实现，
 * 从而「严格递增 / 不回退」是真断言而不是自证 mock。生产链路触发：{@code armRealTimePersist}
 * + {@code state.appendMessage}（对齐 doRun「先 arm 后 append」）。
 */
@DisplayName("[seq 排序键] ChatService 实时落库 seq/created_at 取号")
class ChatServiceRealtimeSeqStampTest {

    private static final String SESSION = "sess-1";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";
    private static final String USER_MSG_ID = "msg-user";

    /**
     * 虚构的「该会话 DB 既有 max(created_at)」= 2099 年（远未来）。
     *
     * <p><b>WHY 必须是未来值</b>：created_at 分配器的契约是「任何新写入恒晚于该会话<b>所有</b>已有行」
     * （{@code MessageService.nextCreatedAt} = {@code max(now, 已知最大 + 1ns)}）。assistant/tool
     * 分支的 record 工厂（{@code newAssistantMessage}/{@code newToolMessage}）本身就预置了
     * {@code OffsetDateTime.now()}（2026）—— 若 Chatservice 里的 {@code rec.setCreatedAt(ts.toString())}
     * 被删，值会落回 2026，<b>仍然非 null</b>（仅断言非 null 抓不到该变异）。把种子设到 2099 后，
     * 「落回 now()」的值会早于种子 → 断言恒红。
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

        // 虚构该会话 DB 既有 max(created_at)=2099（未来）→ 分配器取号必 > 它；见 SEED_EXISTING_MAX。
        MessageRecord existingMax = new MessageRecord();
        existingMax.setCreatedAt(SEED_EXISTING_MAX.toString());
        when(messageMapper.selectListByQuery(any())).thenReturn(List.of(existingMax));
    }

    // ─────────────────────────── 驱动 helper（逐字对齐既有 ChatServiceReplayPersist*Test） ───────────────────────────

    /** 生产链路触发：武装实时落库 listener 后逐条 append（对齐 doRun「先 arm 后 append」）。 */
    private void armAndAppend(AgentState state, ChatMessageDto... messages) {
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, null, USER_MSG_ID);
        for (ChatMessageDto m : messages) {
            state.appendMessage(m);
        }
    }

    private ChatMessageDto plainTextAssistant(String id, String content) {
        return new ChatMessageDto(
            id, SESSION, Role.assistant, null, content, null,
            List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    private ChatMessageDto toolCallsAssistant(String id, String toolCallId) {
        return new ChatMessageDto(
            id, SESSION, Role.assistant, null, "", "工具轮思考",
            List.of(new ToolCallDto(toolCallId, "Bash", "{}", null, false)),
            FinishReason.tool_calls, null, null,
            "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    private ChatMessageDto toolResult(String id, String toolCallId, String assistantId) {
        return new ChatMessageDto(
            id, SESSION, Role.tool, "tool", "ok", null,
            null, null, null, null,
            "刚刚", OffsetDateTime.now(), toolCallId, assistantId, null,
            List.of(), List.of(), null, false, false);
    }

    /** snip_boundary：role=system + subtype=snip_boundary（走 :1356-1399 分支）。 */
    private ChatMessageDto snipBoundary(String id, String content) {
        return new ChatMessageDto(
            id, SESSION, Role.system, "system", content, null,
            null, null, null, null,
            "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false)
            .withSubtype(SnipCompactor.SUBTYPE_SNIP_BOUNDARY);
    }

    // ─────────────────────────── 4 条直写 insert 分支 ───────────────────────────

    @Test
    @DisplayName("纯文本 assistant 实时落库：seq 非空 + created_at 非空（RED：删 :1556 的 setSeq → 红）")
    void plainTextAssistant_rowCarriesSeqAndCreatedAt() {
        AgentState state = new AgentState("sys");

        armAndAppend(state, plainTextAssistant("a1", "你好"));

        MessageRecord rec = captureSingleInsert();
        assertThat(rec.getSeq())
            .as("纯文本 assistant 行必须取 seq（缺失 → ORDER BY seq 下 NULL 排最前 → 会话顺序错乱）")
            .isNotNull();
        assertAllocatorStamped(rec, "纯文本 assistant");
    }

    @Test
    @DisplayName("工具轮 assistant 实时落库：seq 非空 + created_at 非空（RED：删 :1504 的 setSeq → 红）")
    void toolCallsAssistant_rowCarriesSeqAndCreatedAt() {
        AgentState state = new AgentState("sys");

        armAndAppend(state, toolCallsAssistant("a2", "tc1"));

        MessageRecord rec = captureSingleInsert();
        assertThat(rec.getSeq())
            .as("工具轮 assistant 行必须取 seq")
            .isNotNull();
        assertAllocatorStamped(rec, "工具轮 assistant");
        // 同分支的 ToolCallRecord 不走 seq（tool_calls 表无该列）—— 此处仅确认分支确实被执行
        verify(toolCallMapper, times(1)).insert(any());
    }

    @Test
    @DisplayName("tool_result 行实时落库：seq 非空 + created_at 非空（RED：删 :1594 的 setSeq → 红）")
    void toolResultRow_carriesSeqAndCreatedAt() {
        AgentState state = new AgentState("sys");

        armAndAppend(state, toolResult("tr1", "tc1", "a2"));

        MessageRecord rec = captureSingleInsert();
        assertThat(rec.getSeq())
            .as("tool_result 行必须取 seq（缺 seq 的 tool 结果会飘到会话最前，与 tool_use 拆对）")
            .isNotNull();
        assertAllocatorStamped(rec, "tool_result");
    }

    @Test
    @DisplayName("snip_boundary 行实时落库：seq 非空 + created_at 非空（RED：删 :1378 的 setSeq → 红）")
    void snipBoundaryRow_carriesSeqAndCreatedAt() {
        // WHY: boundary 行是「按最后 boundary 剪枝」（BoundaryReader.getMessagesAfterCompactBoundary）
        //   的锚点；若其 seq 缺失（NULL 排最前）→ 剪枝锚点位置错 → 该轮上下文/投影错乱。
        AgentState state = new AgentState("sys");

        armAndAppend(state, snipBoundary("b1", "[已裁剪]"));

        MessageRecord rec = captureSingleInsert();
        assertThat(rec.getSeq())
            .as("snip_boundary 行必须取 seq（boundary 是剪枝锚点，位置错则投影错）")
            .isNotNull();
        assertAllocatorStamped(rec, "snip_boundary");
    }

    // ─────────────────────────── 同会话多写：严格递增（位置序 + 时间序） ───────────────────────────

    @Test
    @DisplayName("同会话 4 分支连续落库：seq 严格递增 + created_at 不回退（RED：nextSeq 退化/复用同值 → 红）")
    void sameSessionMultiWrite_seqStrictlyIncreasing_createdAtMonotonic() {
        // WHY: 会话内位置序的正确性 = 后写的 seq 恒 > 先写的（读侧 ORDER BY seq ASC）。若 nextSeq
        //   退化（共用常量 / 未逐条取号）→ 前端消息乱序；若 created_at 可回退 → compact 边界时间基倒挂。
        AgentState state = new AgentState("sys");

        armAndAppend(state,
            plainTextAssistant("a1", "第一段"),
            toolCallsAssistant("a2", "tc1"),
            toolResult("tr1", "tc1", "a2"),
            snipBoundary("b1", "[已裁剪]"));

        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(4)).insert(captor.capture());
        List<MessageRecord> rows = captor.getAllValues();
        assertThat(rows).as("4 条分支各落 1 行").hasSize(4);

        // 先逐行断言「seq 非空 + created_at 由分配器写入（恒晚于会话既有 max=2099）」——
        // 先把 null / 未取号的情形以清晰断言暴露，再做排序断言（避免 null 直接 NPE 掩盖真实缺失点）。
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i).getSeq())
                .as("第 %d 行 seq 不得为 null（顺序键缺失即会话乱序）", i)
                .isNotNull();
            assertAllocatorStamped(rows.get(i), "多分支连续落库第 " + i + " 行");
        }
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).getSeq())
                .as("同会话内后写的 seq 必须严格大于先写的（第 %d 行 vs 第 %d 行）", i, i - 1)
                .isGreaterThan(rows.get(i - 1).getSeq());
            OffsetDateTime prev = OffsetDateTime.parse(rows.get(i - 1).getCreatedAt());
            OffsetDateTime cur = OffsetDateTime.parse(rows.get(i).getCreatedAt());
            assertThat(cur)
                .as("同会话内 created_at 不得回退（第 %d 行 vs 第 %d 行）", i, i - 1)
                .isAfter(prev);
        }
    }

    // ─────────────────────────── 排队 user（第 4 类命名分支） ───────────────────────────

    @Test
    @DisplayName("mid-turn 注入排队 user 落库：seq 非空（取号在 MessageService.createQueuedUserMessage 内）")
    void queuedUserRow_carriesSeq() {
        // WHY（与上述 4 处直写的差异，显式说明）：ChatService 的 queued-user 分支不直写
        //   messageMapper.insert，而是委托 {@code MessageService.createQueuedUserMessage}（8 参重载）
        //   —— seq 取号发生在 MessageService.java:856。本条锁定「排队 user 行同样带 seq」，
        //   防止该委托路径的取号被删（RED：删 MessageService.java:856 的 m.setSeq(...) → 红）。
        AgentState state = new AgentState("sys");
        state.addInjectedQueuedMessage("q1", "排队消息");

        SessionRecord session = new SessionRecord();
        session.setId(SESSION);
        session.setMessageCount(0);
        when(sessionMapper.selectOneById(SESSION)).thenReturn(session);

        ChatMessageDto queued = new ChatMessageDto(
            "q1", SESSION, Role.user, null, "排队消息", null,
            null, null, null, null,
            "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);

        armAndAppend(state, queued);

        MessageRecord rec = captureSingleInsert();
        assertThat(rec.getSeq())
            .as("排队 user 行必须取 seq（MessageService.createQueuedUserMessage 内取号）")
            .isNotNull();
        assertThat(rec.getRole()).isEqualTo(Role.user.name());
        assertThat(rec.getId()).isEqualTo("q1");
    }

    // ─────────────────────────── helper ───────────────────────────

    /** 捕获唯一一次 messageMapper.insert 的 MessageRecord。 */
    private MessageRecord captureSingleInsert() {
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }

    /**
     * 断言该行 created_at 由 {@code MessageService.nextCreatedAt}（SSOT 单调分配器）写入。
     *
     * <p><b>判据</b>：分配器契约 = 新写入恒晚于该会话<b>所有</b>已有行；本测试把会话既有
     * max(created_at) 虚构为 2099（{@link #SEED_EXISTING_MAX}）→ 该行必须严格晚于 2099。
     * 若 {@code rec.setCreatedAt(ts.toString())} 被删，值落回 record 工厂的 {@code OffsetDateTime.now()}
     * （2026）→ 早于种子 → 红。<b>仅断言非 null 抓不到该变异</b>（工厂已预置 now()）。
     *
     * @param rec   被捕获的落库行
     * @param label 断言失败时的分支标签（定位是 4 处中的哪一处）
     */
    private static void assertAllocatorStamped(MessageRecord rec, String label) {
        assertThat(rec.getCreatedAt())
            .as("%s：created_at 必须非空", label)
            .isNotNull();
        assertThat(OffsetDateTime.parse(rec.getCreatedAt()))
            .as("%s：created_at 必须来自 SSOT 单调分配器（恒晚于该会话既有 max=2099）；"
                + "落回工厂 now()(2026) 即说明 setCreatedAt 被删 → compact 边界时间基倒挂", label)
            .isAfter(SEED_EXISTING_MAX);
    }
}
