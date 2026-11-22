package com.nexusai.application.chat;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tool.ToolErrorKind;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [错误通道 · CCJ-ERR-011/012] {@code is_error} 落库与重放<b>同源</b>（真 sqlite，非 Spring）。
 *
 * <p><b>WHY</b>：CC 把 {@code is_error} 当 tool_result 块属性持久化（sessionStorage.ts:1039-1065），
 * 重启后原样回放；模型侧因此永远看得到历史失败。Java 旧实现有三处各自重猜：
 * <ol>
 *   <li>{@code ChatService} 落 Role.tool 时按内容启发式判错（{@code startsWith("Error") ||
 *       contains("dangerous"/"not found"/"no such tool")}），完全忽略执行层已经算出的
 *       {@code ChatMessageDto.isError()}；</li>
 *   <li>{@code MessageService.toDto} 把消息级 {@code isError} 硬编码成 {@code false}；</li>
 *   <li>因此 {@code AnthropicSdkProvider} 在重启/续接后丢掉 wire 上的 {@code is_error}。</li>
 * </ol>
 *
 * <p>本测试用<b>真实落库链路</b>（{@code armRealTimePersist} → {@code appendMessage} →
 * {@code persistAppendedMessage}）验证三件事：
 * <ul>
 *   <li>失败结果 → {@code tool_calls.is_error=1} 且 {@code getById(...).isError()==true}
 *       （模型面 {@code listForModel} 同值）；</li>
 *   <li>成功结果即使文案里出现 "not found" 也<b>不得</b>被启发式误判成失败；</li>
 *   <li>round-trip 后 {@code is_error} 与执行层给定的真值逐位一致。</li>
 * </ul>
 *
 * <p><b>RED tooth</b>：把 {@code ChatService} 的 {@code isError} 恢复成内容启发式 ⇒ 用例 1 红；
 * 把 {@code MessageService.toDto} 的 isError 回填删掉（回到恒 false）⇒ 用例 1/2 的
 * {@code getById} 断言红。
 */
@DisplayName("[错误通道] is_error 落库 + 重放同源（真 sqlite）")
class ToolResultIsErrorPersistenceTest {

    private static MessageMapper messageMapper;
    private static SessionMapper sessionMapper;
    private static ToolCallMapper toolCallMapper;

    private MessageService messageService;
    private ChatService chatService;
    private AgentState state;
    private String session;
    private String topic;
    private String userMessageId;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dir = Path.of("target", "flex-dbtest",
            "tool-error-persist-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(dir);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("flex.db").toAbsolutePath());
        ds.setBusyTimeout(10_000);
        // 全量迁移：直接读源目录（真文件），保证含最新 V3xx 迁移。
        Flyway.configure()
            .dataSource(ds)
            .locations("filesystem:" + Path.of("src", "main", "resources", "db", "migration")
                .toAbsolutePath().toString().replace('\\', '/'))
            .baselineOnMigrate(true)
            .load()
            .migrate();
        MybatisFlexDbTestSupport.resetAndStart(ds,
            MessageMapper.class, SessionMapper.class, ToolCallMapper.class);
        messageMapper = MybatisFlexBootstrap.getInstance().getMapper(MessageMapper.class);
        sessionMapper = MybatisFlexBootstrap.getInstance().getMapper(SessionMapper.class);
        toolCallMapper = MybatisFlexBootstrap.getInstance().getMapper(ToolCallMapper.class);
    }

    @BeforeEach
    void setUp() {
        session = "sess-toolerr-" + UUID.randomUUID().toString().substring(0, 8).replace('-', '0');
        topic = "/topic/sessions/" + session + "/stream";
        userMessageId = "msg-user-" + UUID.randomUUID().toString().substring(0, 8);

        SessionRecord s = new SessionRecord();
        s.setId(session);
        s.setModelTag("DS");
        s.setModelName("test-model");
        s.setTitle("tool error persist");
        s.setTime("刚刚");
        s.setSessionGroup("default");
        sessionMapper.insertSelective(s);
        // 夹具：CwdResolution 需要有项目根才能造 tool 消息行（newToolMessage → CwdResolution.getCwd）。
        //   本测试不测 cwd，只冻结一个真实存在的目录；tearDown 清掉防跨用例污染。
        com.nexusai.common.SessionProjectRoot.setForSession(session,
            Path.of("target").toAbsolutePath().toString());

        messageService = new MessageService();
        ReflectionTestUtils.setField(messageService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(messageService, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(messageService, "toolCallMapper", toolCallMapper);

        chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(chatService, "toolCallMapper", toolCallMapper);
        ReflectionTestUtils.setField(chatService, "messageService", messageService);
        ReflectionTestUtils.setField(chatService, "notificationQueue", new NotificationQueue());

        state = new AgentState("sys", session, null, null);
        // wsTemplate = null → 只落库不推 STOMP（单测口径，见 ChatService.sendAndLog）
        chatService.armRealTimePersist(state, session, topic, null, userMessageId);
    }

    @AfterEach
    void tearDown() {
        messageMapper.deleteByQuery(QueryWrapper.create().eq("session_id", session));
        sessionMapper.deleteById(session);
        com.nexusai.common.SessionProjectRoot.clearSession(session);
        com.nexusai.eventbus.ws.StreamEventSeq.reset(session);
    }

    @Test
    @DisplayName("失败结果落库：tool_calls.is_error=1 + 读回 DTO isError=true + 模型视图同值")
    void failedToolResultPersistsIsErrorTrue() {
        String assistantId = insertAssistantMessage("msg-asst-1");
        String toolCallId = "tc-fail-1";
        insertToolCall(toolCallId, assistantId, "Edit");

        // 真实生产者：执行层失败载荷（VALIDATE_INPUT → 套 <tool_use_error> 外壳）
        ToolResult<?> failure = ToolResult.failure(toolCallId,
            "File has not been read yet. Read it first before writing to it.",
            ToolErrorKind.VALIDATE_INPUT);
        ChatMessageDto toolMsg = LlmAgentLoop.toolResultMessage(failure, toolCallId, true, null,
            assistantId, null, List.of(), List.of(), Map.of());
        assertThat(toolMsg.isError())
            .as("执行层已算出的真值（toolResultMessage errorFlag）")
            .isTrue();

        state.appendMessage(toolMsg);   // 触发 armRealTimePersist → persistAppendedMessage

        ToolCallRecord tc = toolCallMapper.selectOneById(toolCallId);
        assertThat(tc).as("tool_calls 行存在").isNotNull();
        assertThat(tc.getIsError())
            .as("落库 is_error 必须取执行层真值（⛔ 不按内容重猜）")
            .isTrue();

        // 落库行 id 由 ChatService 生成（不沿用 DTO id）⇒ 按 tool_call_id 反查真实行 id
        String persistedId = idOfToolMessage(toolCallId);
        ChatMessageDto reloaded = messageService.getById(persistedId);
        assertThat(reloaded.isError())
            .as("重放（getById → toDto）必须回填消息级 isError")
            .isTrue();

        ChatMessageDto inModelView = messageService.listForModel(session).stream()
            .filter(m -> persistedId.equals(m.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("模型视图缺该 tool 消息"));
        assertThat(inModelView.isError())
            .as("重启续接后模型 wire 仍能看到失败（CCJ-ERR-012）")
            .isTrue();
    }

    @Test
    @DisplayName("成功结果即使文案含 'not found' 也不得被内容启发式判成失败")
    void successfulResultContainingErrorWordsStaysSuccessful() {
        String assistantId = insertAssistantMessage("msg-asst-2");
        String toolCallId = "tc-ok-1";
        insertToolCall(toolCallId, assistantId, "Grep");

        // 成功输出里天然会出现 "not found"（grep 无匹配的常用文案）
        ToolResult<?> ok = ToolResult.success(toolCallId, "Grep: 'Foo' not found in 0 files");
        ChatMessageDto toolMsg = LlmAgentLoop.toolResultMessage(ok, toolCallId, false, null,
            assistantId, null, List.of(), List.of(), Map.of());
        assertThat(toolMsg.isError()).isFalse();

        state.appendMessage(toolMsg);

        ToolCallRecord tc = toolCallMapper.selectOneById(toolCallId);
        assertThat(tc).isNotNull();
        assertThat(tc.getIsError())
            .as("成功结果不得因文案含 'not found' 被误判为失败（旧启发式缺陷）")
            .isFalse();
        assertThat(messageService.getById(idOfToolMessage(toolCallId)).isError())
            .as("成功消息读回仍为 false")
            .isFalse();
    }

    @Test
    @DisplayName("取消/中断（原文直出不带 Error 前缀）落库仍为失败")
    void cancelResultWithoutErrorPrefixPersistsIsErrorTrue() {
        String assistantId = insertAssistantMessage("msg-asst-3");
        String toolCallId = "tc-cancel-1";
        insertToolCall(toolCallId, assistantId, "Bash");

        ToolResult<?> cancelled = ToolResult.failure(toolCallId,
            "The user doesn't want to take this action right now. STOP what you are doing.",
            ToolErrorKind.CANCEL);
        ChatMessageDto toolMsg = LlmAgentLoop.toolResultMessage(cancelled, toolCallId, true, null,
            assistantId, null, List.of(), List.of(), Map.of());
        state.appendMessage(toolMsg);

        assertThat(toolCallMapper.selectOneById(toolCallId).getIsError())
            .as("CANCEL_MESSAGE 不以 Error 开头 ⇒ 旧启发式会误判成功")
            .isTrue();
        assertThat(messageService.getById(idOfToolMessage(toolCallId)).isError()).isTrue();
    }

    // ─────────────────────────── helpers ───────────────────────────

    /** 按 tool_call_id 反查落库的 tool 消息行 id（ChatService 另行生成 id，不沿用 DTO id）。 */
    private String idOfToolMessage(String toolCallId) {
        List<MessageRecord> rows = messageMapper.selectListByQuery(
            QueryWrapper.create().eq("tool_call_id", toolCallId));
        assertThat(rows).as("tool 消息行已落库: toolCallId=%s", toolCallId).hasSize(1);
        return rows.get(0).getId();
    }

    /** 插 assistant 行（tool_calls.message_id 外键父行）。 */
    private String insertAssistantMessage(String id) {
        MessageRecord rec = new MessageRecord();
        rec.setId(id);
        rec.setSessionId(session);
        rec.setRole("assistant");
        rec.setAuthor("assistant");
        rec.setContent("calling tools");
        rec.setFinishReason("tool_calls");
        rec.setCreatedAt(OffsetDateTime.now().toString());
        rec.setSeq(messageService.nextSeq(session));
        messageMapper.insert(rec);
        return id;
    }

    /** 插 tool_calls 行（模拟 assistant 分支写入；is_error 初值 false，随后由 tool_result 覆盖）。 */
    private void insertToolCall(String id, String assistantMessageId, String toolName) {
        ToolCallRecord tc = new ToolCallRecord();
        tc.setId(id);
        tc.setMessageId(assistantMessageId);
        tc.setToolName(toolName);
        tc.setArguments("{}");
        tc.setResult(null);
        tc.setIsError(false);
        tc.setCreatedAt(OffsetDateTime.now().toString());
        toolCallMapper.insert(tc);
    }
}
