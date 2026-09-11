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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [reasoningDurationMs] 实时落库 + STOMP 收口 + transcript 双轨测试 · 净新增（非 CC 对齐）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: 用户拍板（2026-08-24）后端测推理耗时，三轨
 * 下发/记录——① messages 表（工具轮 + 纯文本 assistant 落库 reasoningDurationMs）②
 * MessageCompleteEvent（第 9 参 = lastAssistantReasoningDurationMs）③ transcript 文件
 * （{@code {type:'reasoning-duration'}} entry）。变异点：
 * <ul>
 *   <li>工具轮 assistant 落库不携带 duration → GET /messages 工具轮历史无耗时 → 红</li>
 *   <li>纯文本 assistant 落库不携带 duration → 最终回复无耗时 → 红</li>
 *   <li>transcript 双轨不写 / 无 reasoning 也写 → 审计留痕错 → 红</li>
 *   <li>lastAssistantReasoningDurationMs 取错消息 → STOMP 收口耗时错 → 红</li>
 * </ul>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock mapper +
 * mock wsTemplate；生产链路触发（{@code armRealTimePersist} + {@code state.appendMessage}，
 * 替代已删 replayAndPersist 反射调用）。transcript 双轨经
 * {@link ClaudePaths#setConfigDirOverride} 重定向到 temp dir（防污染真实 config-home）。
 */
@DisplayName("[reasoningDurationMs] 实时落库 + transcript 双轨 + STOMP 收口")
class ChatServiceReplayPersistReasoningDurationTest {

    @TempDir
    Path tempDir;

    private static final String SESSION = "sess-1";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

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

    /** 生产链路触发：武装实时落库 listener 后逐条 append（对齐 doRun「先 arm 后 append」）。 */
    private void armAndAppend(AgentState state, String userMessageId, ChatMessageDto... messages) {
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, userMessageId);
        for (ChatMessageDto m : messages) {
            state.appendMessage(m);
        }
    }

    @Test
    @DisplayName("工具轮 + 纯文本 assistant 落库均携带 reasoningDurationMs（V41 列）")
    void toolAndFinalAssistantPersistReasoningDuration() {
        // GIVEN: 工具轮 assistant（dur=800）+ tool_result + 纯文本 assistant（dur=1500）
        AgentState state = new AgentState("sys");

        // WHEN: 生产链路实时落库（逐条 append 即落）
        armAndAppend(state, "msg-user",
            new ChatMessageDto(
                "a-tool", SESSION, Role.assistant, null, "", "思考1",
                List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
                FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
                List.of(), List.of(), null, false, false).withReasoningDurationMs(800L),
            new ChatMessageDto(
                "t1", SESSION, Role.tool, "tool", "ok", null, null,
                null, null, null, "刚刚", OffsetDateTime.now(), "tc1", null, null,
                List.of(), List.of(), null, false, false),
            new ChatMessageDto(
                "a-final", SESSION, Role.assistant, null, "最终回复", "思考2",
                List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
                List.of(), List.of(), null, false, false).withReasoningDurationMs(1500L));

        // THEN: 工具轮 assistant（content 空）与纯文本 assistant（content=最终回复）落库均带耗时
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        // 3 次 MessageRecord.insert：工具轮 assistant + tool_result + 纯文本 assistant
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
            .as("纯文本 assistant 落库必须携带 reasoningDurationMs（V41 列）")
            .isEqualTo(1500L);
    }

    @Test
    @DisplayName("transcript 双轨：assistant 落库时追加 {type:'reasoning-duration'} 行（temp config-home）")
    void transcriptDualTrackAppendsReasoningDurationLine() throws Exception {
        AgentState state = new AgentState("sys");

        // WHEN: 生产链路实时落库（append 即触发 persistAppendedMessage → transcript 双轨写点）
        armAndAppend(state, "msg-user", new ChatMessageDto(
            "a-final", SESSION, Role.assistant, null, "最终回复", "思考",
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false).withReasoningDurationMs(1500L));

        // THEN: transcript 文件（temp config-home）追加一条 reasoning-duration entry
        Path workspaceDir = Path.of(CwdResolution.getOriginalCwdLayer(SESSION));
        Path transcript = SessionStorage.getTranscriptPath(workspaceDir, SESSION);
        assertThat(transcript)
            .as("带 reasoningDurationMs 的 assistant 落库必须触发 transcript 双轨写点")
            .exists();
        JsonNode line = JSON.readTree(Files.readString(transcript).trim());
        assertThat(line.path("type").asText()).isEqualTo("reasoning-duration");
        // messageId = 落库的 assistantId（同源改造后 = 源 assistant 消息真实 id = turnAssistantId）
        assertThat(line.path("messageId").asText())
            .as("transcript entry 必须携带 assistant 的持久化消息 id（源消息真实 id = turnAssistantId）")
            .isEqualTo("a-final");
        assertThat(line.path("reasoningDurationMs").asLong()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("无 reasoning → transcript 双轨不写文件（null 不记录）")
    void noReasoning_transcriptNotWritten() {
        AgentState state = new AgentState("sys");

        armAndAppend(state, "msg-user", new ChatMessageDto(
            "a-final", SESSION, Role.assistant, null, "回复", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false));

        Path workspaceDir = Path.of(CwdResolution.getOriginalCwdLayer(SESSION));
        Path transcript = SessionStorage.getTranscriptPath(workspaceDir, SESSION);
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
            "a1", SESSION, Role.assistant, null, "第一", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false).withReasoningDurationMs(100L));
        state.appendMessage(new ChatMessageDto(
            "a2", SESSION, Role.assistant, null, "末条", null,
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
            "u1", SESSION, Role.user, null, "你好", null,
            null, null, null, null, null, OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false));

        assertThat(lastDuration(state)).isNull();
    }

    /**
     * [reflect-warning · DB 顺序保序] 实时原位落库 queued-user created_at 单调序锚点。
     *
     * <p><b>WHY（规则九 · 测试验证意图）</b>：DB 消息顺序只靠 {@code created_at} 单键
     * （ChatService.loadRecentHistory orderBy created_at；无二级排序键）。修复前同一实时落库
     * （persistAppendedMessage 逐条）内 assistantA / queued-user / assistantB 连续 insert 落在同一毫秒
     * （OffsetDateTime.now() 毫秒精度）时 created_at 并列 → 并列序不确定，queued-user 可能间歇排在
     * assistantB 之后，击败 B 目标「回复A后回复B前」DB 顺序承诺。变异点：任意 insert 回退
     * {@code OffsetDateTime.now()} 或 queued-user 不走 4 参单调重载 → 本断言红（时间戳并列/逆序）。
     */
    @Test
    @DisplayName("实时原位落库 queued-user created_at 严格介于 assistantA 与 assistantB 之间（单调时间戳保序）")
    void inPlaceQueuedUserCreatedAt_isMonotonicBetweenAssistants() {
        // GIVEN: assistantA(工具轮) → tool → mid-turn 注入 queued-user → assistantB(纯文本)，
        //   queued-user 同时 append 进 state.messages()（真实 LlmAgentLoop 工具边界注入模型）。
        //   注意实时化时序：addInjectedQueuedMessage 必须先于 append（user 分支在 append 时点反查）。
        AgentState state = new AgentState("sys");
        state.addInjectedQueuedMessage("msg-queued-1", "忙时追问");
        // [created_at 单调分配器] 生产链路 created_at 由 MessageService.nextCreatedAt 取号（本测试
        //   messageService 为 mock → 缺省返回 null 会走 baseTs 回落分支，测不到新路径）→ 打桩为
        //   严格递增序列，使断言锁死「分配器取号 → DB created_at 单调」这条真实生产语义。
        java.util.concurrent.atomic.AtomicLong allocSeq = new java.util.concurrent.atomic.AtomicLong();
        when(messageService.nextCreatedAt(anyString()))
            .thenAnswer(inv -> OffsetDateTime.now().plusNanos(allocSeq.incrementAndGet()));

        // WHEN: 生产链路实时落库——逐条 append（queued-user 走 4 参单调重载原位落库）
        armAndAppend(state, "msg-user",
            new ChatMessageDto(
                "a-tool", SESSION, Role.assistant, null, "", "思考1",
                List.of(new ToolCallDto("tc1", "Bash", "{}", null, false)),
                FinishReason.tool_calls, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
                List.of(), List.of(), null, false, false),
            new ChatMessageDto(
                "t1", SESSION, Role.tool, "tool", "ok", null, null,
                null, null, null, "刚刚", OffsetDateTime.now(), "tc1", null, null,
                List.of(), List.of(), null, false, false),
            LlmAgentLoop.toMessage(Role.user, "忙时追问", null, "msg-queued-1"),
            LlmAgentLoop.toMessage(Role.assistant, "最终回复", null, "a-final"));

        // THEN: 3 次 MessageRecord.insert 顺序 = a-tool(assistant tool_calls) → t1(tool) → a-final(纯文本)
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, times(3)).insert(captor.capture());
        OffsetDateTime tsAssistantA = OffsetDateTime.parse(captor.getAllValues().get(0).getCreatedAt());
        OffsetDateTime tsAssistantB = OffsetDateTime.parse(captor.getAllValues().get(2).getCreatedAt());
        // queued-user 走 8 参重载（实时原位落库单调时间戳 + queuedOrigin 透传 + [OD-D13] imagePasteIds/
        //   userAttachments 落自身行；本测试 2 参 addInjectedQueuedMessage 登记 → origin null 不标记）
        //   ⚠️ 断言口径同步：该调用点在 HEAD 已是 8 参（旧断言仍按 6 参 verify → 基线红，非本批引入）。
        ArgumentCaptor<OffsetDateTime> tsCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(messageService).createQueuedUserMessage(
            eq(SESSION), eq("msg-queued-1"), eq("忙时追问"), tsCaptor.capture(), eq(false), isNull(),
            anyList(), isNull());
        OffsetDateTime tsQueuedUser = tsCaptor.getValue();

        assertThat(tsQueuedUser)
            .as("queued-user created_at 必须严格位于 assistantA 之后（修复前毫秒并列/逆序 → 间歇排后）")
            .isAfter(tsAssistantA);
        assertThat(tsQueuedUser)
            .as("queued-user created_at 必须严格位于 assistantB 之前（回复A后回复B前 DB 顺序承诺）")
            .isBefore(tsAssistantB);
    }

    /** 反射调用 private {@code lastAssistantReasoningDurationMs(AgentState)}（仍存在，收口 helper）。 */
    private Long lastDuration(AgentState state) throws Exception {
        Method m = ChatService.class.getDeclaredMethod("lastAssistantReasoningDurationMs", AgentState.class);
        m.setAccessible(true);
        return (Long) m.invoke(service, state);
    }

    // ════════════════════════════════════════════════════════════════════
    // [P3-c] complete 上下文快照口径 = 末条 assistant 的 usage（非 run 累计）
    // ════════════════════════════════════════════════════════════════════

    /**
     * <b>WHY（规则九 · 上下文数字必须与重拉一致）</b>：{@code contextTokensUsed} 表达「当前上下文
     * 大小」= 最后一轮 API 调用的 prompt（CC {@code getCurrentUsage}，tokens.ts:150-177 倒序取最后
     * 一条带 usage 的消息）。旧实现传 {@code state.runUsage()}（本 run <b>每条</b> assistant usage 的
     * 累加）→ 多轮工具 turn（N 次 LLM 调用）≈N 倍虚高；而重拉补算（MessageService 单条口径）给的是
     * 真实值 → 前端「F5 前后跳变」。
     *
     * <p><b>RED 条件</b>：把 {@code ContextUsageCalculator.snapshot(...)} 的 usage 实参改回
     * {@code state.runUsage()} → 本用例断言 1000 得到 3000 → 红。
     *
     * <p>同时钉死「{@code runUsage()} 不得删」：{@code complete.usage}（CC result.usage = query 级
     * 累计）仍必须是 3 次调用之和 3000。
     */
    @Test
    @DisplayName("[P3-c] complete 上下文快照 = 末条 assistant usage（多轮 turn 不虚高）；complete.usage 仍为 run 累计")
    void completeContextSnapshot_usesLastAssistantUsage_notRunTotal() {
        AgentState state = new AgentState("sys");
        // 3 轮 LLM 调用（工具轮 x2 + 纯文本收尾），每轮 input=1000 → run 累计 3000
        com.nexusai.application.agent.tool.AgentUsage perCall =
            new com.nexusai.application.agent.tool.AgentUsage(1_000L, 10L, null, null, null, null, null);
        for (int i = 1; i <= 3; i++) {
            state.appendMessage(new ChatMessageDto(
                "a" + i, SESSION, Role.assistant, null, "轮" + i, null,
                List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
                List.of(), List.of(), null, false, false).withUsage(perCall));
            // 生产：LlmAgentLoop.publishMessageUsage 每轮 append(withUsage) 后立即累加
            state.accumulateRunUsage(perCall);
        }
        // 模型不可判定（state.currentModel() == null + 本测试未注入 mapper）→ 窗口回落 1M + 非 anthropic
        // → contextTokensUsed = 单条 input（协议分派），使断言口径干净。

        service.publishCompleteEvent(SESSION, "msg-user", state, STREAM_TOPIC, wsTemplate, 0L, "a3");

        ArgumentCaptor<com.nexusai.eventbus.ws.StreamEvent> captor =
            ArgumentCaptor.forClass(com.nexusai.eventbus.ws.StreamEvent.class);
        verify(wsTemplate).convertAndSend(eq(STREAM_TOPIC), captor.capture());
        com.nexusai.eventbus.ws.MessageCompleteEvent evt =
            (com.nexusai.eventbus.ws.MessageCompleteEvent) captor.getValue();

        assertThat(evt.getContextTokensUsed())
            .as("上下文快照 = 末条 assistant 的 input=1000（当前上下文），不是 run 累计 3000")
            .isEqualTo(1_000L);
        assertThat(evt.getUsage().inputTokens())
            .as("complete.usage 仍是本轮 run 累计 3000（CC result.usage · runUsage() 不得删）")
            .isEqualTo(3_000L);
    }

    /**
     * 无任何带 usage 的 assistant（如 abort 空跑）→ 快照表达为「无 usage 数据」：
     * used=0 + percentLeft 省略（NON_NULL），而不是拿 run 累计的全零哨兵冒充「已用 0 / 100% 剩余」。
     *
     * <p><b>RED 条件</b>：快照 usage 源改回 {@code runUsage()}（恒非 null）→ percentLeft=100 → 红。
     */
    @Test
    @DisplayName("[P3-c] 无带 usage 的 assistant → 快照 used=0 + percentLeft 省略（与「真的空」区分）")
    void completeContextSnapshot_noAssistantUsage_isUnknownNotZeroed() {
        AgentState state = new AgentState("sys");
        state.appendMessage(new ChatMessageDto(
            "a1", SESSION, Role.assistant, null, "无 usage 回复", null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false));

        service.publishCompleteEvent(SESSION, "msg-user", state, STREAM_TOPIC, wsTemplate, 0L, "a1");

        ArgumentCaptor<com.nexusai.eventbus.ws.StreamEvent> captor =
            ArgumentCaptor.forClass(com.nexusai.eventbus.ws.StreamEvent.class);
        verify(wsTemplate).convertAndSend(eq(STREAM_TOPIC), captor.capture());
        com.nexusai.eventbus.ws.MessageCompleteEvent evt =
            (com.nexusai.eventbus.ws.MessageCompleteEvent) captor.getValue();

        assertThat(evt.getContextTokensUsed()).as("无 usage 数据 → used 0").isZero();
        assertThat(evt.getPercentLeft())
            .as("无 usage 数据 → percentLeft 省略（null），不得报「剩余 100%」")
            .isNull();
    }

    /**
     * <b>WHY（口径统一 · 与重拉路径同值）</b>：同一份单条 usage，实时 complete 事件与重拉补算
     * （MessageService.applyContextSnapshotToLastAssistant）必须给出<b>同一个</b> contextTokensUsed
     * —— 这正是 P3-c/P3-d 要根治的「两套口径并存」。
     *
     * <p><b>RED 条件</b>：任一实现改用 run 累计 / 改用其他公式 → 两者不等 → 红。
     */
    @Test
    @DisplayName("[P3-d] 实时 complete 快照与重拉快照同值（同一单条 usage → 无漂移）")
    void completeSnapshot_equalsReloadSnapshot_forSameSingleUsage() {
        AgentState state = new AgentState("sys");
        com.nexusai.application.agent.tool.AgentUsage single =
            new com.nexusai.application.agent.tool.AgentUsage(4_321L, 10L, 500L, 700L, null, null, null);
        state.appendMessage(new ChatMessageDto(
            "a1", SESSION, Role.assistant, null, "回复", null,
            List.of(), FinishReason.stop, 4_321, 10, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false).withUsage(single));

        service.publishCompleteEvent(SESSION, "msg-user", state, STREAM_TOPIC, wsTemplate, 0L, "a1");

        ArgumentCaptor<com.nexusai.eventbus.ws.StreamEvent> captor =
            ArgumentCaptor.forClass(com.nexusai.eventbus.ws.StreamEvent.class);
        verify(wsTemplate).convertAndSend(eq(STREAM_TOPIC), captor.capture());
        com.nexusai.eventbus.ws.MessageCompleteEvent evt =
            (com.nexusai.eventbus.ws.MessageCompleteEvent) captor.getValue();

        // 重拉口径（MessageService：非 anthropic → 仅 input；同 window/1M 回落）
        long reloadUsed = com.nexusai.application.agent.compact.ContextUsageCalculator.computeContextTokensUsed(
            4_321L, 700L, 500L, false);
        assertThat(evt.getContextTokensUsed())
            .as("实时与重拉必须同值（同一条 usage + 同一协议分派单点）")
            .isEqualTo(reloadUsed)
            .isEqualTo(4_321L);
        assertThat(evt.getContextWindow()).isEqualTo(1_048_576L);
    }
}
