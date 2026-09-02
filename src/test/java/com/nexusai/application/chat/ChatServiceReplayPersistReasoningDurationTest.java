package com.nexusai.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [reasoningDurationMs] replayAndPersist 落库 + STOMP 收口 + transcript 双轨测试 · 净新增（非 CC 对齐）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 用户拍板（2026-08-24）后端测推理耗时，三轨
 * 下发/记录——① messages 表（工具轮 + final assistant 落库 reasoningDurationMs）②
 * MessageCompleteEvent（第 9 参 = lastAssistantReasoningDurationMs）③ transcript 文件
 * （{@code {type:'reasoning-duration'}} entry）。变异点：
 * <ul>
 *   <li>工具轮 assistant 落库不携带 duration → GET /messages 工具轮历史无耗时 → 红</li>
 *   <li>final assistant 落库不携带 duration → 最终回复无耗时 → 红</li>
 *   <li>transcript 双轨不写 / 无 reasoning 也写 → 审计留痕错 → 红</li>
 *   <li>lastAssistantReasoningDurationMs 取错消息 → STOMP 收口耗时错 → 红</li>
 * </ul>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock mapper +
 * mock wsTemplate；{@code replayAndPersist} 私有方法反射调用（同
 * {@code ChatServiceReplayPersistReasoningTest} 模式）。transcript 双轨经
 * {@link ClaudePaths#setConfigDirOverride} 重定向到 temp dir（防污染真实 config-home）。
 */
@DisplayName("[reasoningDurationMs] replayAndPersist 落库 + transcript 双轨 + STOMP 收口")
class ChatServiceReplayPersistReasoningDurationTest {

    @TempDir
    Path tempDir;

    private ChatService service;
    private MessageMapper messageMapper;
    private ToolCallMapper toolCallMapper;
    private MessageService messageService;
    private SimpMessagingTemplate wsTemplate;

    private static final ObjectMapper JSON = new ObjectMapper();

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
        // transcript 双轨重定向到 temp config-home（防真实用户 config-home 污染）
        ClaudePaths.setConfigDirOverride(tempDir.toString());
        // G5：transcript 写路径经 SessionStorage（nexusai 自有根优先）→ 唯一 appName 隔离（防写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        NexusaiPaths.setAppNameOverride(null);
    }

    @Test
    @DisplayName("工具轮 + final assistant 落库均携带 reasoningDurationMs（V41 列）")
    void toolAndFinalAssistantPersistReasoningDuration() throws Exception {
        // GIVEN: 工具轮 assistant（dur=800）+ tool_result + final assistant（dur=1500）
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "a-tool", "sess-1", Role.assistant, null, "", "思考1",
            List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
            FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false).withReasoningDurationMs(800L));
        state.appendMessage(new ChatMessageDto(
            "t1", "sess-1", Role.tool, "tool", "ok", null, null,
            null, null, null, "刚刚", OffsetDateTime.now(), "tc1", null, null,
            List.of(), List.of(), null, false, false));
        state.appendMessage(new ChatMessageDto(
            "a-final", "sess-1", Role.assistant, null, "最终回复", "思考2",
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false).withReasoningDurationMs(1500L));

        invokeReplay(state);

        // THEN: 工具轮 assistant（content 空）与 final assistant（content=最终回复）落库均带耗时
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        // 3 次 MessageRecord.insert：工具轮 assistant + tool_result + final assistant
        verify(messageMapper, times(3)).insert(captor.capture());
        MessageRecord toolAsst = captor.getAllValues().stream()
            .filter(r -> "".equals(r.getContent()) && "assistant".equals(r.getRole()))
            .findFirst().orElseThrow();
        MessageRecord finalAsst = captor.getAllValues().stream()
            .filter(r -> "最终回复".equals(r.getContent()))
            .findFirst().orElseThrow();
        assertThat(toolAsst.getReasoningDurationMs())
            .as("工具轮 assistant 落库必须携带本消息推理耗时（V41 列）")
            .isEqualTo(800L);
        assertThat(finalAsst.getReasoningDurationMs())
            .as("final assistant 落库必须携带 finalReasoningDurationMs（V41 列）")
            .isEqualTo(1500L);
    }

    @Test
    @DisplayName("transcript 双轨：assistant 落库时追加 {type:'reasoning-duration'} 行（temp config-home）")
    void transcriptDualTrackAppendsReasoningDurationLine() throws Exception {
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "a-final", "sess-1", Role.assistant, null, "最终回复", "思考",
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false).withReasoningDurationMs(1500L));

        invokeReplay(state);

        // THEN: transcript 文件（temp config-home）追加一条 reasoning-duration entry
        Path workspaceDir = Path.of(CwdResolution.getOriginalCwdLayer("sess-1"));
        Path transcript = SessionStorage.getTranscriptPath(workspaceDir, "sess-1");
        assertThat(transcript)
            .as("带 reasoningDurationMs 的 assistant 落库必须触发 transcript 双轨写点")
            .exists();
        JsonNode line = JSON.readTree(Files.readString(transcript).trim());
        assertThat(line.path("type").asText()).isEqualTo("reasoning-duration");
        // messageId = final 落库的 assistantId（同源改造后 = 源 assistant 消息真实 id = turnAssistantId，
        //   非随机 finalAssistantId "msg-" 前缀）
        assertThat(line.path("messageId").asText())
            .as("transcript entry 必须携带 final assistant 的持久化消息 id（源消息真实 id = turnAssistantId）")
            .isEqualTo("a-final");
        assertThat(line.path("reasoningDurationMs").asLong()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("无 reasoning → transcript 双轨不写文件（null 不记录）")
    void noReasoning_transcriptNotWritten() throws Exception {
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "a-final", "sess-1", Role.assistant, null, "回复", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false));

        invokeReplay(state);

        Path workspaceDir = Path.of(CwdResolution.getOriginalCwdLayer("sess-1"));
        Path transcript = SessionStorage.getTranscriptPath(workspaceDir, "sess-1");
        assertThat(transcript)
            .as("无 reasoning（durationMs null）→ transcript 双轨 no-op 不写文件（干净语义）")
            .doesNotExist();
    }

    @Test
    @DisplayName("message.complete 收口：lastAssistantReasoningDurationMs 取末条 assistant 的耗时")
    void lastAssistantReasoningDurationMs_returnsLastAssistantDuration() throws Exception {
        // GIVEN: 两条 assistant，末条带 duration
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "a1", "sess-1", Role.assistant, null, "第一", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false).withReasoningDurationMs(100L));
        state.appendMessage(new ChatMessageDto(
            "a2", "sess-1", Role.assistant, null, "末条", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false).withReasoningDurationMs(2500L));

        assertThat(lastDuration(state))
            .as("MessageCompleteEvent 第 9 参数据源 = 末条 assistant 的真实推理耗时")
            .isEqualTo(2500L);
    }

    @Test
    @DisplayName("无 assistant → lastAssistantReasoningDurationMs 返回 null（安全兜底）")
    void lastAssistantReasoningDurationMs_noAssistant_returnsNull() throws Exception {
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "u1", "sess-1", Role.user, null, "你好", null,
            null, null, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false));

        assertThat(lastDuration(state)).isNull();
    }

    /**
     * [reflect-warning · DB 顺序保序] 原位落库 queued-user created_at 单调序锚点。
     *
     * <p><b>WHY（规则九 · 测试验证意图）</b>：DB 消息顺序只靠 {@code created_at} 单键
     * （ChatService.loadRecentHistory orderBy created_at；无二级排序键）。修复前同一 replayAndPersist
     * 循环内 assistantA / queued-user / assistantB 连续 insert 落在同一毫秒（OffsetDateTime.now()
     * 毫秒精度）时 created_at 并列 → 并列序不确定，queued-user 可能间歇排在 assistantB 之后，击败
     * B 目标「回复A后回复B前」DB 顺序承诺。变异点：循环内任何 insert 回退 {@code OffsetDateTime.now()}
     * 或 queued-user 不走 4 参单调重载 → 本断言红（时间戳并列/逆序）。
     */
    @Test
    @DisplayName("原位落库 queued-user created_at 严格介于 assistantA 与 assistantB 之间（单调时间戳保序）")
    void inPlaceQueuedUserCreatedAt_isMonotonicBetweenAssistants() throws Exception {
        // GIVEN: assistantA(工具轮) → tool → mid-turn 注入 queued-user → assistantB(final)，
        //   queued-user 同时 append 进 state.messages()（真实 LlmAgentLoop 工具边界注入模型）
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "a-tool", "sess-1", Role.assistant, null, "", "思考1",
            List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
            FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false));
        state.appendMessage(new ChatMessageDto(
            "t1", "sess-1", Role.tool, "tool", "ok", null, null,
            null, null, null, "刚刚", OffsetDateTime.now(), "tc1", null, null,
            List.of(), List.of(), null, false, false));
        state.appendMessage(LlmAgentLoop.toMessage(Role.user, "忙时追问", null, "msg-queued-1"));
        state.addInjectedQueuedMessage("msg-queued-1", "忙时追问");
        state.appendMessage(LlmAgentLoop.toMessage(Role.assistant, "最终回复", null, "a-final"));

        invokeReplay(state);

        // THEN: 3 次 MessageRecord.insert 顺序 = a-tool(assistant tool_calls) → t1(tool) → a-final(final)
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(3)).insert(captor.capture());
        OffsetDateTime tsAssistantA = OffsetDateTime.parse(captor.getAllValues().get(0).getCreatedAt());
        OffsetDateTime tsAssistantB = OffsetDateTime.parse(captor.getAllValues().get(2).getCreatedAt());
        // queued-user 走 4 参重载（原位落库单调时间戳）
        ArgumentCaptor<OffsetDateTime> tsCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(messageService).createQueuedUserMessage(
            eq("sess-1"), eq("msg-queued-1"), eq("忙时追问"), tsCaptor.capture());
        OffsetDateTime tsQueuedUser = tsCaptor.getValue();

        assertThat(tsQueuedUser)
            .as("queued-user created_at 必须严格位于 assistantA 之后（修复前毫秒并列/逆序 → 间歇排后）")
            .isAfter(tsAssistantA);
        assertThat(tsQueuedUser)
            .as("queued-user created_at 必须严格位于 assistantB 之前（回复A后回复B前 DB 顺序承诺）")
            .isBefore(tsAssistantB);
    }

    /** 反射调用 private {@code replayAndPersist(sessionId, userMessageId, state, streamTopic, wsTemplate)}。 */
    private void invokeReplay(AgentState state) throws Exception {
        Method m = ChatService.class.getDeclaredMethod(
            "replayAndPersist",
            String.class, String.class, AgentState.class, String.class, SimpMessagingTemplate.class);
        m.setAccessible(true);
        m.invoke(service, "sess-1", "msg-user", state, "/topic/stream", wsTemplate);
    }

    /** 反射调用 private {@code lastAssistantReasoningDurationMs(AgentState)}。 */
    private Long lastDuration(AgentState state) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("lastAssistantReasoningDurationMs", AgentState.class);
        m.setAccessible(true);
        return (Long) m.invoke(service, state);
    }
}
