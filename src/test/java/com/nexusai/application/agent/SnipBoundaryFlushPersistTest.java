package com.nexusai.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.StreamingToolExecutor;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolParent;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SnipTool;
import com.nexusai.application.chat.ChatService;
import com.nexusai.eventbus.ws.MessageBoundaryEvent;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [snip-boundary-persist] SnipTool boundary 经 newMessages 通道投递（flush / drain）落库 + STOMP 验证。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>: 生产路径下 boundary 由工具 {@code newMessages}
 * 通道投递 —— {@code ToolResultApplier.apply → state.stashNewMessages}，随后
 * {@code AgentLoopContext.flushNewMessagesAfterToolResult}（tool_result 之后）或
 * {@code drainLeftoverNewMessages}（无配对 tool_result 边缘路径）追加到 {@code state.rawMessages()}。
 * 历史上两处均走裸 {@code state.rawMessages().addAll(...)}，<b>不触发</b> {@code appendListener}，
 * 而 snip_boundary 的<b>唯一</b>落库分支 {@code ChatService.persistAppendedMessage} 恰是监听器实现体
 * → 生产路径永不落库（会话 sess-fcdcdc68 实证：2550 条消息 / 59 次 Snip 成功，库中
 * {@code snip_metadata} 恒 0 行）→ F5/重启后上下文回归。
 *
 * <p><b>对照 CC</b>: CC {@code /force-snip} 命令直接 {@code setMessages(prev => [...prev, boundary])}
 * （commands/force-snip.ts:42）——boundary 是「消息存储追加」而非旁路，故必被
 * {@code recordTranscript} 写盘（sessionStorage.ts:2003-2010 自述同款事故：不落盘 → resume 立即 PTL）。
 * nexusai 的等价物 = {@code appendMessage}（唯一触达 recordTranscript 等价物的通道）。
 *
 * <p><b>变异点（本测试锁定的 RED→GREEN 分界）</b>:
 * <ol>
 *   <li>把 {@code flushNewMessagesAfterToolResult} 的「逐条 appendFlushedNewMessage」退回
 *       {@code messages().addAll(pending)} → 测试一红（无 snip_boundary insert / 无 MessageBoundaryEvent）。</li>
 *   <li>把 {@code drainLeftoverNewMessages} 同上退回 → 测试二红。</li>
 *   <li>把整条 newMessages 通道无差别切 {@code appendMessage}（越过本缺陷范围）→ 测试三红
 *       （isMeta user newMessages 会开始落库）。</li>
 * </ol>
 *
 * <p><b>为何不走反射</b>: 端到端驱动真 {@code SnipTool} + {@code ToolRegistry} +
 * {@code StreamingToolExecutor} + {@code AgentLoopContext.handleToolCallsTurn}，与生产同链路，
 * 且武装真 {@code ChatService.persistAppendedMessage}（mock MessageMapper 捕 DB insert）。
 * 既有 {@code SnipBoundaryPersistTest} 手工 {@code state.appendMessage(boundary)} 走的正是生产
 * <b>不走</b>的监听器路径，故对本缺陷永久绿 —— 本测试补上真实通道。
 *
 * <p><b>包选择</b>: {@code com.nexusai.application.agent}（与 AgentState 同包 → 可调包私有
 * {@code incrementTurn()}/{@code stashNewMessages 公开}，handleToolCallsTurn 末尾构造
 * {@code AgentTurnCompletedEvent(state, turnCount,...)} 要求 turnCount &gt;= 1）。
 */
@DisplayName("[snip-boundary-persist] boundary 经 newMessages 通道（flush/drain）落库 + STOMP")
class SnipBoundaryFlushPersistTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SESSION = "sess-snip-flush";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

    // ── helpers ──────────────────────────────────────────────────────────────

    /** 照 SnipBoundaryPersistTest:74-80 —— historySnip=true（第 6 位）其余全关。 */
    private static FeatureFlags snipEnabledFlags() {
        return new FeatureFlags(
            false, false, false, false, false, true,
            false, false, false, false, false, false,
            false, false, false, false, false, false,
            false, false, false, false, false, false);
    }

    private static ChatMessageDto msg(String id, Role role, String content) {
        return msg(id, role, content, null);
    }

    /** 20 参 ChatMessageDto 便捷构造（照 SnipBoundaryPersistTest:86 同构）。 */
    private static ChatMessageDto msg(String id, Role role, String content, String toolCallId) {
        return new ChatMessageDto(
            id, SESSION, role, role == Role.user ? "user" : (role == Role.tool ? "tool" : "assistant"),
            content, null, List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), toolCallId, null, null,
            List.of(), List.of(), null, false, false);
    }

    /** Snip 工具调用块（message_ids 传完整 id，SnipTool.resolveTargetUserIndices 兼容完整 id）。 */
    private static ToolUseBlock snipCall(String... messageIds) {
        ObjectNode input = JSON.createObjectNode();
        ArrayNode ids = input.putArray("message_ids");
        for (String id : messageIds) {
            ids.add(id);
        }
        return new ToolUseBlock(UUID.randomUUID().toString(), SnipTool.NAME, input);
    }

    @SuppressWarnings("unchecked")
    private static ToolResult<String> asToolResult(AgentToolResult<?> r) {
        return (ToolResult<String>) r;
    }

    /** 武装真 ChatService 实时落库（mock mapper 捕 insert），返回 messageMapper 供断言。 */
    private static MessageMapper armPersist(AgentState state, String sessionId, String topic,
                                            SimpMessagingTemplate wsTemplate) {
        ChatService service = new ChatService();
        MessageMapper messageMapper = mock(MessageMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", mock(ToolCallMapper.class));
        service.armRealTimePersist(state, sessionId, topic, wsTemplate, "msg-user");
        return messageMapper;
    }

    /** 从全部 insert 中按 subtype 过滤出 snip_boundary 行（勿假设只有一条 insert）。 */
    private static MessageRecord snipBoundaryRow(MessageMapper messageMapper) {
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues().stream()
            .filter(r -> "snip_boundary".equals(r.getSubtype()))
            .findFirst()
            .orElse(null);
    }

    /** 从全部 STOMP 推送中过滤出 MessageBoundaryEvent（tool_result 等其它事件同 topic）。 */
    private static MessageBoundaryEvent boundaryEvent(SimpMessagingTemplate wsTemplate, String topic) {
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(wsTemplate, atLeastOnce()).convertAndSend(eq(topic), payloads.capture());
        return payloads.getAllValues().stream()
            .filter(MessageBoundaryEvent.class::isInstance)
            .map(MessageBoundaryEvent.class::cast)
            .findFirst()
            .orElse(null);
    }

    /** 携带 isMeta image user newMessages 的工具（模拟 Read pdf pages → 页图，非 boundary 生产者）。 */
    private static Tool imageReturningTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "reads pdf pages and returns page images as newMessages"; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public AgentToolResult execute(ToolUseBlock call) {
                ObjectNode img = JSON.createObjectNode();
                img.put("type", "image");
                img.put("media_type", "image/png");
                img.put("data", "iVBORw0KGgo=");
                ChatMessageDto pageImages = new ChatMessageDto(
                    UUID.randomUUID().toString(), null, Role.user, "system",
                    null, null, null, null, null, null,
                    "刚刚", OffsetDateTime.now(), null, null, null,
                    List.of(img), List.of(), null,
                    true, false, null, null, false, null, null, null);
                return ToolResult.successWithNewMessages(call.id(), "Read 2 pages", List.of(pageImages));
            }
            @Override public boolean isConcurrencySafe(com.fasterxml.jackson.databind.JsonNode input) { return true; }
            @Override public String interruptBehavior() { return "block"; }
        };
    }

    // ── 测试一：flush 旁路点（tool_result 之后） ───────────────────────────────

    @Test
    @DisplayName("真链路 flush：SnipTool boundary 在 tool_result 后 flush → 落库 role=system + snipMetadata + 推 MessageBoundaryEvent")
    void snipBoundary_flushAfterToolResult_persisted() {
        // ── 1. 基础设施：真 SnipTool + registry + AgentLoopContext ──
        Tool snipTool = new SnipTool(snipEnabledFlags());
        ToolRegistry registry = new ToolRegistry();
        registry.register(snipTool);
        AgentLoopContext ctx = TestContexts.agentLoopContext(registry, null, null, null, null);

        AgentState state = new AgentState("sys", SESSION, null);
        state.incrementTurn();

        // ── 2. 武装真持久化（mock mapper 捕 insert + mock wsTemplate 捕 STOMP）──
        SimpMessagingTemplate wsTemplate = mock(SimpMessagingTemplate.class);
        MessageMapper messageMapper = armPersist(state, SESSION, STREAM_TOPIC, wsTemplate);

        // ── 3. 播入目标 user 消息 u0（历史锚点；appendMessage 触监听器但 user 分支 no-op）──
        ChatMessageDto u0 = msg("u0", Role.user, "open baidu");
        state.appendMessage(u0);
        assertThat(state.prePersistedMessageIds() == null || !state.prePersistedMessageIds().contains("u0"))
            .as("u0 非历史注入（prePersisted 未登记）→ 若为 boundary 不会被监听器跳过").isTrue();

        // ── 4. per-turn TUC：availableTools=snipTool + messages=state.rawMessages()（工具读 ctx.messages）──
        ToolUseContext perTurnTuc = ToolUseContext.of(UUID.randomUUID(), SESSION)
            .withAvailableTools(List.of(snipTool))
            .withMessages(state.rawMessages());
        String turnAssistantId = UUID.randomUUID().toString();

        // ── 4b. 前置断言：SnipTool 确实产出 boundary（否则会因 TUC 未带 messages 而"假红"）──
        ToolResult<String> pre = asToolResult(snipTool.execute(snipCall("u0"), perTurnTuc));
        assertThat(pre.newMessages()).as("SnipTool 必须产出 boundary（removedUuids 含 u0）").isNotEmpty();
        assertThat(pre.newMessages().get(0).snipMetadata()).as("boundary 携带 snipMetadata").isNotNull();
        assertThat(String.valueOf(pre.newMessages().get(0).snipMetadata())).contains("u0");

        // ── 5. 真 executor + handleToolCallsTurn（内部自然走 flushNewMessagesAfterToolResult）──
        ToolUseBlock call = snipCall("u0");
        AssistantMessage msg = new AssistantMessage("I'll snip old history", "tool_calls", List.of(call));
        StreamingToolExecutor exec = AgentLoopContext.buildStreamingExecutor(
            ctx, perTurnTuc, state, turnAssistantId, null, true, null, null);
        assertThat(exec).as("availableTools 非空 → 必须构建出真实 executor").isNotNull();
        exec.add(call, ToolParent.of(turnAssistantId), null);

        String result = AgentLoopContext.handleToolCallsTurn(
            ctx, perTurnTuc, state, msg, "I'll snip old history", 0, turnAssistantId,
            exec, new ArrayList<>(), QuerySource.USER, null,
            null, null, null, null, null);
        assertThat(result).as("工具轮返回 continue").isEqualTo("continue");

        // ── 6. THEN：boundary 以 role=system + subtype=snip_boundary 落库（对齐 CC setMessages 追加 → recordTranscript）──
        MessageRecord inserted = snipBoundaryRow(messageMapper);
        assertThat(inserted)
            .as("flush 通道必须触发 appendListener → persistAppendedMessage snip 分支落库（当前 addAll 旁路 → RED）")
            .isNotNull();
        assertThat(inserted.getRole()).isEqualTo(Role.system.name());
        assertThat(inserted.getSnipMetadata()).as("snipMetadata.removedUuids 持久化（V62 列）").contains("u0");

        // ── 7. THEN：STMOP 推 MessageBoundaryEvent（前端实时标注被裁剪消息）──
        MessageBoundaryEvent evt = boundaryEvent(wsTemplate, STREAM_TOPIC);
        assertThat(evt).as("boundary 落库同批推 MessageBoundaryEvent").isNotNull();
        assertThat(evt.getRemovedUuids()).contains("u0");
    }

    // ── 测试二：drain 旁路点（无配对 tool_result 边缘路径） ─────────────────────

    @Test
    @DisplayName("drain 旁路点：无配对 tool_result 的暂存 boundary 在末尾 drain → 同样落库 + 推 MessageBoundaryEvent")
    void snipBoundary_leftoverDrain_persisted() {
        Tool snipTool = new SnipTool(snipEnabledFlags());
        Tool dummy = TestContexts.dummyTool("Grep");
        ToolRegistry registry = new ToolRegistry();
        registry.register(dummy);
        AgentLoopContext ctx = TestContexts.agentLoopContext(registry, null, null, null, null);

        String session = SESSION + "-drain";
        String topic = "/topic/sessions/" + session + "/stream";
        AgentState state = new AgentState("sys", session, null);
        state.incrementTurn();

        SimpMessagingTemplate wsTemplate = mock(SimpMessagingTemplate.class);
        MessageMapper messageMapper = armPersist(state, session, topic, wsTemplate);

        ChatMessageDto u0 = msg("u0", Role.user, "open baidu");
        state.appendMessage(u0);

        // 真 SnipTool 产出 boundary（同一实现链路），随后模拟「暂存了但本 turn 无对应 tool_result 配对」──
        ToolUseContext snipTuc = ToolUseContext.of(UUID.randomUUID(), session)
            .withAvailableTools(List.of(snipTool))
            .withMessages(state.rawMessages());
        ToolResult<String> snipResult = asToolResult(snipTool.execute(snipCall("u0"), snipTuc));
        assertThat(snipResult.newMessages()).as("前置：SnipTool 产出 boundary").isNotEmpty();
        ChatMessageDto boundary = snipResult.newMessages().get(0);

        String orphanToolUseId = "call_orphan_no_paired_tool_result";
        state.stashNewMessages(orphanToolUseId, List.of(boundary));
        assertThat(state.hasPendingNewMessages()).as("暂存未消费 → 触发末尾 drain").isTrue();

        // 本 turn 只有 Grep 调用（不消费 orphan 暂存）→ 主循环后走 drainLeftoverNewMessages ──
        ToolUseContext perTurnTuc = ToolUseContext.of(UUID.randomUUID(), session)
            .withAvailableTools(List.of(dummy));
        String turnAssistantId = UUID.randomUUID().toString();
        ToolUseBlock grepCall = new ToolUseBlock("call_grep_1", "Grep", JSON.createObjectNode().put("pattern", "TODO"));
        AssistantMessage msg = new AssistantMessage("I'll grep", "tool_calls", List.of(grepCall));
        StreamingToolExecutor exec = AgentLoopContext.buildStreamingExecutor(
            ctx, perTurnTuc, state, turnAssistantId, null, true, null, null);
        exec.add(grepCall, ToolParent.of(turnAssistantId), null);

        String result = AgentLoopContext.handleToolCallsTurn(
            ctx, perTurnTuc, state, msg, "I'll grep", 0, turnAssistantId,
            exec, new ArrayList<>(), QuerySource.USER, null,
            null, null, null, null, null);
        assertThat(result).isEqualTo("continue");

        // THEN：drain 旁路点同样定向分流 → appendListener 触发落库 + 推送
        MessageRecord inserted = snipBoundaryRow(messageMapper);
        assertThat(inserted)
            .as("drainLeftoverNewMessages 必须同批改（否则 abort/配对缺口路径仍漏 boundary）")
            .isNotNull();
        assertThat(inserted.getRole()).isEqualTo(Role.system.name());
        assertThat(inserted.getSnipMetadata()).contains("u0");

        MessageBoundaryEvent evt = boundaryEvent(wsTemplate, topic);
        assertThat(evt).as("drain 落库同批推 MessageBoundaryEvent").isNotNull();
        assertThat(evt.getRemovedUuids()).contains("u0");
    }

    // ── 测试三（护栏）：非 boundary newMessages 维持旁路（不落库），顺序不变 ─────────

    @Test
    @DisplayName("护栏：非 boundary 的 isMeta user newMessages 仍走旁路（不落库）+ 顺序 assistant→tool→user 不变")
    void nonBoundaryNewMessages_staySilent_orderPreserved() {
        Tool pdfTool = imageReturningTool("Read");
        ToolRegistry registry = new ToolRegistry();
        registry.register(pdfTool);
        AgentLoopContext ctx = TestContexts.agentLoopContext(registry, null, null, null, null);

        String session = SESSION + "-guard";
        String topic = "/topic/sessions/" + session + "/stream";
        AgentState state = new AgentState("sys", session, null);
        state.incrementTurn();

        SimpMessagingTemplate wsTemplate = mock(SimpMessagingTemplate.class);
        MessageMapper messageMapper = armPersist(state, session, topic, wsTemplate);

        ToolUseContext perTurnTuc = ToolUseContext.of(UUID.randomUUID(), session)
            .withAvailableTools(List.of(pdfTool))
            .withMessages(state.rawMessages());
        String turnAssistantId = UUID.randomUUID().toString();
        ToolUseBlock call = new ToolUseBlock("call_pdf_1", "Read",
            JSON.createObjectNode().put("file_path", "/tmp/sample.pdf").put("pages", "1-2"));
        AssistantMessage msg = new AssistantMessage("I'll read the pdf pages", "tool_calls", List.of(call));

        StreamingToolExecutor exec = AgentLoopContext.buildStreamingExecutor(
            ctx, perTurnTuc, state, turnAssistantId, null, true, null, null);
        exec.add(call, ToolParent.of(turnAssistantId), null);

        String result = AgentLoopContext.handleToolCallsTurn(
            ctx, perTurnTuc, state, msg, "I'll read the pdf pages", 0, turnAssistantId,
            exec, new ArrayList<>(), QuerySource.USER, null,
            null, null, null, null, null);
        assertThat(result).isEqualTo("continue");

        // 顺序护栏：逐条 add 与 addAll 等价 → 仍为 assistant(tool_calls) → tool(tool_result) → user(isMeta 页图)
        List<ChatMessageDto> msgs = state.rawMessages();
        assertThat(msgs).hasSize(3);
        assertThat(msgs.get(0).role()).isEqualTo(Role.assistant);
        assertThat(msgs.get(1).role()).isEqualTo(Role.tool);
        assertThat(msgs.get(1).toolCallId()).isEqualTo("call_pdf_1");
        assertThat(msgs.get(2).role()).as("末条 = isMeta 页图 user newMessages").isEqualTo(Role.user);
        assertThat(msgs.get(2).isMeta()).isTrue();

        // 定向分流护栏：非 boundary newMessages 不得落库（若整通道切 appendMessage 则会开始 insert）
        ArgumentCaptor<MessageRecord> captor = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, atLeastOnce()).insert(captor.capture());
        assertThat(captor.getAllValues())
            .as("isMeta user 页图不得落库（定向分流：只 boundary 走 appendMessage）")
            .allSatisfy(r -> assertThat(r.getSubtype()).isNotEqualTo("snip_boundary"));
        assertThat(captor.getAllValues().stream().noneMatch(r -> Role.user.name().equals(r.getRole())))
            .as("user 分支 no-op → 不得出现 user 行 insert").isTrue();
    }
}
