package com.nexusai.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.FeatureFlags;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.SnipTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [is_meta 接线 2026-09-12] ChatService 4 条实时落库写路径把 V51 {@code is_meta} 列真实接线。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：ChatService 直写 {@code messageMapper.insert}
 * <b>绕过 MessageService</b>（同 cwd 戳 / V70 两标记列的既有先例），故其 4 条路径
 * （snip_boundary / assistant(tool_calls) / assistant(纯文本) / tool_result）需要独立接线。
 * 接线前这 4 条路径全部 {@code grep setIsMeta = 0 命中} → 落 NULL —— 真库实证
 * {@code is_meta NULL=5090} vs 显式 0 仅 127，NULL 绝大多数来自这 4 条路径。
 *
 * <p><b>为什么落 NULL 今天「看起来没错」但仍是缺陷</b>：读侧 {@code countNonMetaMessages}
 * 的 SQL 显式把 NULL 当「非 meta」（{@code is_meta IS NULL OR is_meta != 1}）→ 行为等价。
 * 但「未接线时代的 NULL」与「V51 前的历史 NULL」从此<b>不可区分</b>，任何回填/校验迁移失去判据
 * （与 V70 两标记列同源同因，见 {@code ChatServiceTranscriptFlagsPersistTest} 的同类论证）。
 *
 * <p><b>逐路径语义判定（判准 = CC 对应位置的 isMeta 实际取值）</b>：
 * <table border="1">
 *   <tr><th>路径</th><th>判定</th><th>CC 依据</th></tr>
 *   <tr><td>snip_boundary（role=system）</td><td>{@code false}</td>
 *       <td>CC {@code force-snip.ts:34} 写 {@code isMeta: true}，而 CC 的 isMeta 过滤<b>只作用于
 *           user 行</b>（{@code components/Messages.tsx:176} 在 {@code if (msg.type === 'user')} 下
 *           {@code return !msg.isMeta}；{@code :156} 对 {@code type === 'system'} 另有放行分支）
 *           → 边界在 CC 转录渲染路径照常可见；nexusai 前端两处过滤<b>无 role 豁免</b>
 *           （{@code MessageList.tsx:871} / {@code TraceView.tsx:180}）→ 落 true 会让「已裁剪」标记条
 *           从对话区与轨迹<b>同时消失</b>（P2-19 刚接线的 snipBoundaryMarker 分支成死代码）。
 *           故以 false 补偿前端差异，取得与 CC <b>同一可见结果</b>；同消息的 DTO 侧亦为 false
 *           （{@code SnipTool.buildSnipBoundaryMessage} 第 19 位实参）→ DTO↔DB 一致。</td></tr>
 *   <tr><td>assistant(tool_calls) / assistant(纯文本)</td><td>{@code false}</td>
 *       <td>CC assistant 消息无 isMeta 概念（isMeta 只在 {@code createUserMessage}，messages.ts:463-483）</td></tr>
 *   <tr><td>tool_result</td><td>{@code false}</td>
 *       <td>CC tool_result 承载于 user 消息、未传 isMeta（messages.ts:463-483 缺省非元消息）</td></tr>
 * </table>
 *
 * <p><b>RED 条件</b>：删除 {@code newAssistantMessage} / {@code newToolMessage} / snip_boundary 内联 rec
 * 中任一处 {@code setIsMeta(...)} → 捕获到的 record 该字段为 null → 本类断言红。
 *
 * <p>纯单测：{@code new ChatService()} + ReflectionTestUtils 注入 mock MessageMapper / ToolCallMapper；
 * 生产链路触发（{@code armRealTimePersist} + {@code state.appendMessage}）。
 */
@DisplayName("[is_meta] ChatService 实时落库 4 路径写 V51 is_meta 列（显式值，非 NULL）")
class ChatServiceIsMetaPersistTest {

    private static final String SESSION = "sess-ismeta-persist";
    private static final String STREAM_TOPIC = "/topic/sessions/" + SESSION + "/stream";

    private ChatService service;
    private MessageMapper messageMapper;
    private ToolCallMapper toolCallMapper;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        messageMapper = mock(MessageMapper.class);
        toolCallMapper = mock(ToolCallMapper.class);
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "toolCallMapper", toolCallMapper);
    }

    private static FeatureFlags snipEnabledFlags() {
        return new FeatureFlags(
            false, false, false, false, false, true,
            false, false, false, false, false, false,
            false, false, false, false, false, false,
            false, false, false, false, false, false);
    }

    private static ChatMessageDto text(String id, Role role, String content) {
        return text(id, role, content, List.of(), null);
    }

    private static ChatMessageDto text(String id, Role role, String content,
                                       List<ToolCallDto> toolCalls, String toolCallId) {
        return new ChatMessageDto(
            id, SESSION, role,
            role == Role.user ? "user" : (role == Role.tool ? "tool" : "assistant"),
            content, null, toolCalls, FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), toolCallId, null, null,
            List.of(), List.of(), null, false, false);
    }

    private static ToolUseContext ctxWithMessages(List<?> messages) {
        return ToolUseContext.of(UUID.randomUUID(), SESSION, PermissionMode.DEFAULT, List.of(), "",
            AbortController.NOOP, messages);
    }

    private static ToolUseBlock snipCall(String... messageIds) {
        ObjectNode input = new ObjectMapper().createObjectNode();
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

    @Test
    @DisplayName("4 条落库路径（snip_boundary / assistant×2 / tool_result）落的 is_meta 非 NULL 且为 0")
    void allChatServicePersistPaths_writeNonNullIsMeta() {
        // GIVEN: 被 snipe 区间（user0+assistant0+tool0）+ 后续 user1 + SnipTool 注入的 boundary
        ChatMessageDto user0 = text("u0", Role.user, "open baidu");
        ChatMessageDto assistantWithTools = text("a0", Role.assistant, "searching",
            List.of(new ToolCallDto("call_0", "Bash", "{\"command\":\"ls\"}", null, false)), null);
        ChatMessageDto tool0 = text("t0", Role.tool, "result", List.of(), "call_0");
        ChatMessageDto assistantPlain = text("a1", Role.assistant, "done");
        ChatMessageDto user1 = text("u1", Role.user, "next");
        List<ChatMessageDto> history = new ArrayList<>(
            List.of(user0, assistantWithTools, tool0, assistantPlain, user1));

        ToolResult<String> snipResult = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u0"), ctxWithMessages(history)));
        ChatMessageDto boundary = snipResult.newMessages().get(0);
        assertThat(boundary.snipMetadata()).as("前置：SnipTool 产出了 boundary").isNotNull();

        AgentState state = new AgentState("sys");

        // WHEN: 生产链路逐条 append（每条 append 即走 persistAppendedMessage 对应分支落库）
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, mock(SimpMessagingTemplate.class), "msg-user");
        for (ChatMessageDto m : List.of(user0, assistantWithTools, tool0, assistantPlain, user1, boundary)) {
            state.appendMessage(m);
        }

        // THEN: 所有落库行的 is_meta 都必须<b>非 NULL</b>（NULL = 未接线 → 红）
        ArgumentCaptor<MessageRecord> cap = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper, org.mockito.Mockito.atLeastOnce()).insert(cap.capture());
        List<MessageRecord> rows = cap.getAllValues();
        assertThat(rows).as("应至少落 4 条（assistant×2 + tool + snip_boundary）").hasSizeGreaterThanOrEqualTo(4);
        assertThat(rows).extracting(MessageRecord::getSubtype).contains("snip_boundary");
        assertThat(rows).extracting(MessageRecord::getRole)
            .contains(Role.assistant.name(), Role.tool.name(), Role.system.name());

        for (MessageRecord r : rows) {
            String where = "role=" + r.getRole() + " subtype=" + r.getSubtype() + " id=" + r.getId();
            assertThat(r.getIsMeta())
                .as("is_meta 必须显式落值（不是 NULL —— 真库 is_meta NULL=5090 主要来自本 4 路径）· " + where)
                .isNotNull();
            // 4 条路径全部是「真实对话消息 / 可见系统边界」→ 语义恒 false（逐路径依据见类 javadoc 判定表）
            assertThat(r.getIsMeta())
                .as("本批 4 路径恒 false：assistant/tool = 真实对话消息；snip_boundary = 有意偏离 CC 的 "
                    + "isMeta:true 以补偿前端无 system 豁免的过滤（见类 javadoc）· " + where)
                .isFalse();
        }
        // 工厂复用面确认：assistant(tool_calls) 与 assistant(纯文本) 两条分支各落 1 条
        assertThat(rows).extracting(MessageRecord::getId).contains("a0", "a1");
        assertThat(rows).extracting(MessageRecord::getToolCallId).contains("call_0");
    }

    @Test
    @DisplayName("snip_boundary 落 is_meta=0 且与 SnipTool DTO 的 isMeta 同值（DTO↔DB 不得分叉）")
    void snipBoundary_isMetaMatchesDto() {
        // WHY: 同一条 boundary 消息有两条落库通道 —— 本类走 ChatService 直写 mapper（用 MessageRecord
        //   内联构造，不读 DTO 的 isMeta），而 compact 的 replaceSessionMessages / appendMessage 走
        //   rec.setIsMeta(dto.isMeta())。两条通道若取值不同，同一条逻辑消息会在 DB 里出现两种 is_meta
        //   → 读侧口径（countNonMetaMessages / TraceView 过滤）随写入时序漂移。故此处锁死「同值」。
        ChatMessageDto user0 = text("u0", Role.user, "hello");
        List<ChatMessageDto> history = new ArrayList<>(List.of(user0));
        ChatMessageDto boundary = asToolResult(
            new SnipTool(snipEnabledFlags()).execute(snipCall("u0"), ctxWithMessages(history)))
            .newMessages().get(0);

        assertThat(boundary.isMeta())
            .as("前置：SnipTool 构造的 boundary DTO isMeta=false（§5.1 约束 1 —— 带 true 会让裁剪条静默消失）")
            .isFalse();

        AgentState state = new AgentState("sys");
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, mock(SimpMessagingTemplate.class), "msg-user");
        state.appendMessage(boundary);

        ArgumentCaptor<MessageRecord> cap = ArgumentCaptor.forClass(MessageRecord.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getSubtype()).isEqualTo("snip_boundary");
        assertThat(cap.getValue().getIsMeta())
            .as("ChatService 直写通道与 DTO 通道必须同值（否则同消息两 is_meta）")
            .isEqualTo(boundary.isMeta());
    }
}
