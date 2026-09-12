package com.nexusai.domain.session;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SendMessageRequest;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [is_meta 接线 2026-09-12] {@code messages.is_meta} 的<b>写侧逐路径语义 + 读侧口径</b>契约。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：本批把 {@code is_meta} 接线的意义全部落在
 * <b>读侧两条用户可见口径</b>上 —— 写侧取反方向时，单看任何一个写路径都不会报错，只有把
 * 「写侧取值 → 读侧过滤」串起来才看得见后果：
 * <ol>
 *   <li><b>轨迹条数徽标</b>：{@code countNonMetaMessages}（{@code countBySession} / 分页 total）
 *       的 SQL 是 {@code session_id = ? AND (is_meta IS NULL OR is_meta != 1)}
 *       —— NULL（V51 存量旧行）当「非 meta」，仅显式 1 被排除。把该写 true 的行写成 false
 *       → 条数<b>虚高</b>（系统注入行被算成真实消息）。</li>
 *   <li><b>{@code lastUserMessageId} 归属</b>：{@link AgentState#lastUserMessageId()} 显式跳过
 *       {@code isMeta=true} 的 user（类 javadoc：记忆注入等随机 UUID 非真实用户消息）。
 *       把该写 true 的写成 false → 事件/落库归属指向该注入消息的<b>随机 UUID</b>。</li>
 * </ol>
 *
 * <p><b>逐路径语义判定（判准 = CC 对应位置的 isMeta 实际取值）</b>：
 * <table border="1">
 *   <tr><th>MessageService 路径</th><th>判定</th><th>CC 依据</th></tr>
 *   <tr><td>{@code createUserMessage}（真实用户输入）</td><td>{@code false}</td>
 *       <td>messages.ts:463-483 createUserMessage 未传 isMeta ⇒ undefined ⇒ 非元消息</td></tr>
 *   <tr><td>{@code createQueuedUserMessage}（cron=true / busy-queued=false）</td><td>按入参</td>
 *       <td>useScheduledTasks.ts:76 cron 入队 {@code isMeta:true}；busy-queued 排队 prompt 是用户输入 ⇒ false</td></tr>
 *   <tr><td>{@code appendSystemSubtypeMessage}（scheduled_task_fire）</td><td>{@code false}</td>
 *       <td>messages.ts:4385-4392 createScheduledTaskFireMessage {@code isMeta=false}（仍可见）</td></tr>
 *   <tr><td>{@code appendMessage(dto)} / {@code replaceSessionMessages}</td><td>透传 {@code dto.isMeta()}</td>
 *       <td>出站 DTO 同源（hook_additional_context {@code isMeta:true}；compact 摘要 {@code isMeta} 缺省 false）</td></tr>
 * </table>
 *
 * <p><b>RED 条件</b>：把任一 {@code setIsMeta(...)} 改成反向常量 → 对应断言红；
 * 把 {@code countNonMetaMessages} 的 SQL 谓词（{@code is_meta IS NULL OR is_meta != 1}）改成
 * {@code is_meta != 1} → SQL 断言红（NULL 存量行会被误排除）。
 *
 * <p><b>⚠️ 覆盖边界（显式声明，不掩盖）</b>：本类用 mock mapper 捕获 {@link QueryWrapper#toSQL()}
 * 与 {@code insert} 实参，<b>不</b>起真实 SQLite —— 验证的是「写侧取值 + 读侧谓词」的契约，
 * 不是端到端 SQL 执行结果。
 */
@DisplayName("[is_meta] 写侧逐路径语义 + 读侧 countNonMetaMessages 口径（mock mapper + SQL 捕获）")
class MessageServiceIsMetaContractTest {

    private static final String SESSION = "sess-ismeta01";

    private MessageService service;
    private MessageMapper messageMapper;
    private SessionMapper sessionMapper;

    @BeforeEach
    void setUp() {
        service = new MessageService();
        messageMapper = mock(MessageMapper.class);
        sessionMapper = mock(SessionMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", mock(ToolCallMapper.class));
        when(messageMapper.insert(any(MessageRecord.class))).thenReturn(1);
        when(sessionMapper.selectOneById(any())).thenReturn(new SessionRecord());
    }

    // ────────────────────────────────────────────────────────────────────
    // 写侧 · 逐路径语义
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("appendMessage 透传 dto.isMeta：hook 注入(true) 落 1；真实对话(false) 落 0（不得为 null）")
    void appendMessage_passesThroughDtoIsMeta() {
        // hook_additional_context 注入消息（CC sessionStart.ts:163-172 / messages.ts:4117-4127
        //   createAttachmentMessage {type:'hook_additional_context', isMeta:true}）
        service.appendMessage(new ChatMessageDto(
            "msg-hook", SESSION, Role.user, "hook",
            "<system-reminder>injected</system-reminder>", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null,
            true,   // isMeta
            false, null, "hook_additional_context"));

        assertThat(captureSingleInsert().getIsMeta())
            .as("hook 注入消息 dto.isMeta=true 必须落 1（落 NULL/0 会让它计入轨迹条数并被选中为 lastUser）")
            .isTrue();

        // 真实对话消息（同一个 appendMessage 通道，另一方向）
        service.appendMessage(new ChatMessageDto(
            "msg-plain", SESSION, Role.assistant, "assistant",
            "普通回复", null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false, null, null));

        assertThat(captureLastInsert().getIsMeta())
            .as("真实对话消息必须落 false（非 null —— NULL 保留给 V51 前历史行）")
            .isFalse();
    }

    @Test
    @DisplayName("createQueuedUserMessage：cron 入队 isMeta=true 落 1；busy-queued 排队 isMeta=false 落 0")
    void createQueuedUserMessage_honoursIsMetaParam() {
        when(messageMapper.insert(any(MessageRecord.class))).thenReturn(1);
        service.createQueuedUserMessage(SESSION, "msg-cron", "cron prompt", null, true, "cron");
        assertThat(captureSingleInsert().getIsMeta())
            .as("cron 触发的 user prompt 是系统生成（useScheduledTasks.ts:76 isMeta:true）→ 不得计入真实消息")
            .isTrue();

        service.createQueuedUserMessage(SESSION, "msg-busy", "busy prompt", null, false, "busy-queued");
        assertThat(captureLastInsert().getIsMeta())
            .as("mid-turn busy 排队是用户自己输入 → isMeta=false（须被 lastUserMessageId 选中）")
            .isFalse();
    }

    @Test
    @DisplayName("createUserMessage（真实用户输入）落 is_meta=0")
    void createUserMessage_writesFalse() {
        service.createUserMessage(SESSION, new SendMessageRequest(
            "你好", null, null, List.of(), null, null, null, null, null));
        assertThat(captureSingleInsert().getIsMeta())
            .as("真实用户输入必须落 false（否则轨迹条数与 lastUser 归属都会被自身排除）")
            .isFalse();
    }

    @Test
    @DisplayName("appendSystemSubtypeMessage（scheduled_task_fire）落 is_meta=0（CC isMeta=false 仍可见）")
    void appendSystemSubtypeMessage_writesFalse() {
        service.appendSystemSubtypeMessage(SESSION, "scheduled_task_fire", "Running scheduled task");
        assertThat(captureSingleInsert().getRole()).isEqualTo("system");
        assertThat(captureLastInsert().getIsMeta())
            .as("CC createScheduledTaskFireMessage isMeta=false（messages.ts:4385-4392）→ 前端仍渲染「任务执行中」")
            .isFalse();
    }

    // ────────────────────────────────────────────────────────────────────
    // 读侧 · 轨迹条数口径（把写侧取值与过滤谓词串起来）
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("countNonMetaMessages 谓词：is_meta=1 被排除、NULL（V51 存量行）保留 → 与写侧 true 合起来才不虚高")
    void countNonMetaMessages_excludesExplicitOne_keepsLegacyNull() {
        when(messageMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(3L);

        assertThat(service.countBySession(SESSION)).isEqualTo(3);

        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(messageMapper).selectCountByQuery(cap.capture());
        String sql = String.valueOf(cap.getValue().toSQL());
        assertThat(sql)
            .as("显式 1 必须被排除 —— 这是本批把 hook 注入/系统注入行写成 true 的<b>唯一理由</b>；"
                + "写成 false 会让它们计入条数徽标（虚高）")
            .contains("is_meta != 1");
        assertThat(sql)
            .as("NULL 必须保留计数 —— V51 存量旧行未接线不可被静默丢出徽标（正是本次接线前 5090 行 NULL 的处境）")
            .contains("is_meta IS NULL");
    }

    // ────────────────────────────────────────────────────────────────────
    // 读侧 · lastUserMessageId 归属（跨读侧行为断言：写 true 的注入消息不得劫持归属）
    // ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("is_meta=true 的注入 user 消息不劫持 lastUserMessageId；写成 false 则被劫持（反证 WHY）")
    void metaUserMessage_doesNotHijackLastUserMessageId() {
        AgentState state = new AgentState("sys");
        state.appendMessage(userMsg("m-real", "user", "真实提问", false));
        state.appendMessage(userMsg("m-injected", "hook", "TaskCompleted hook feedback:...", true));

        assertThat(state.lastUserMessageId())
            .as("hook/系统注入的 user（isMeta=true）绝不可成为「最后 user」—— 否则事件与落库归属"
                + "指向该注入消息的随机 UUID（AgentState.java:421-423 已声明该守卫）")
            .isEqualTo("m-real");

        // 反证：同一序列若注入消息错写成 isMeta=false（本批修复前的实际取值方向）→ 归属被劫持。
        //   这条断言就是 RED 条件本身 —— 它说明「写 true」不是风格偏好，而是归属正确性的前提。
        AgentState regressed = new AgentState("sys");
        regressed.appendMessage(userMsg("m-real", "user", "真实提问", false));
        regressed.appendMessage(userMsg("m-injected", "hook", "TaskCompleted hook feedback:...", false));
        assertThat(regressed.lastUserMessageId())
            .as("反证：注入消息若 isMeta=false，归属即被劫持到注入的随机 UUID")
            .isEqualTo("m-injected");
    }

    // ────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────

    private static ChatMessageDto userMsg(String id, String author, String content, boolean isMeta) {
        return new ChatMessageDto(
            id, SESSION, Role.user, author, content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, isMeta, false, null, null);
    }

    /** 捕获本方法内唯一一次 insert（方法内只调用一次写路径时用）。 */
    private MessageRecord captureSingleInsert() {
        ArgumentCaptor<MessageRecord> cap = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(cap.capture());
        return cap.getValue();
    }

    /** 捕获最近一次 insert（方法内调用多次写路径时用；同 captureSingleInsert 的时序语义）。 */
    private MessageRecord captureLastInsert() {
        ArgumentCaptor<MessageRecord> cap = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, org.mockito.Mockito.atLeastOnce()).insert(cap.capture());
        List<MessageRecord> all = cap.getAllValues();
        return all.get(all.size() - 1);
    }
}
