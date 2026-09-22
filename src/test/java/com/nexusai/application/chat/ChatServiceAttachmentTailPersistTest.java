package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.compact.PostCompactAttachmentRestorer;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.McpClientRuntime;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * <b>[C1 ③ · 用户裁定「尾部投递 attachment 落库」] 该通道产出的消息落库语义</b>。
 *
 * <h2>本类为何存在（意图，非仅行为）</h2>
 * 每个 run 都从 DB 全量重载历史（{@code ChatService.processUserMessage} → doRun 历史注入）。
 * 「尾部投递」通道（{@code AgentLoopContext.maybeEmitDateChange} / {@code maybeEmitChangedFiles} /
 * {@code maybeEmitMcpInstructionsDelta}）都走 {@code state.appendMessage(...)}，而
 * {@code appendMessage} 只把消息加进内存并触 {@code appendListener}；<b>真正决定落不落 DB 的是
 * {@link ChatService#persistAppendedMessage}</b>（user 分支）。<b>这些消息若不落库，模型看到的
 * 「会话尾部提醒」只活一个 run：跨 run 的 delta 去重（{@code scanAnnouncedDeltaNames} 扫描
 * {@code state.rawMessages()}）失效、resume 重放丢失。</b>
 *
 * <h2>判据（CC 2.1.278 发行产物，非源码臆测）</h2>
 * {@code bin/claude.exe} 内 {@code isLoggableMessage} 等价函数为<b>黑名单（默认放）</b>：
 * 拒绝集 {@code C3o = new Set([])}（空集，全 exe 仅 1 处声明、{@code C3o.add} 零命中）
 * ⇒ 规则 = <b>不排除任何附件类型</b>。故本仓出口判据取【形状】
 * （{@code role=user && author=attachment && isMeta}），而不是「给三支开后门」。
 *
 * <h2>对照设计（CLAUDE.md 规则九）</h2>
 * <ul>
 *   <li><b>正对照</b>：{@code hook_additional_context}（author=hook）与 {@code skill_listing}
 *       （author=attachment + isMeta=true，与三支<b>同形状</b>但走更早的专属出口）——两者都必须
 *       仍然<b>恰好一次</b>：既证明夹具/武装链有效，又证明新出口<b>不与既有出口双写</b>。</li>
 *   <li><b>被测</b>：{@code date_change} / {@code edited_text_file} / {@code mcp_instructions_delta}
 *       三支 ⇒ 各<b>恰好一次</b>，且 subtype/author/isMeta 列值正确。</li>
 *   <li><b>反面</b>：{@code author=attachment && isMeta=false}（deferred_tools_delta 的真实形状）、
 *       {@code author=user && isMeta=true}（relevant_memories / nested_memory 形状）、
 *       {@code role=assistant && author=attachment} ⇒ 一律<b>不</b>经本出口落库。</li>
 * </ul>
 *
 * <p>纯单测（⛔ 无 Spring / 无 @SpringBootTest / ⛔ 不触真库）：{@code new ChatService()} +
 * ReflectionTestUtils 注入 mock mapper / mock MessageService；MCP 连接用真
 * {@code McpClientRuntime}；env 门走 {@link ToolSearchService#envOverride}。
 */
@DisplayName("[C1 ③] 尾部 attachment 投递落库（三支各恰好一次 · 既有出口不双写）")
class ChatServiceAttachmentTailPersistTest {

    private static final String SESSION = "sess-attachment-tail-persist";
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

    @AfterEach
    void resetEnvSeam() {
        ToolSearchService.envOverride = null;
    }

    // ═══════════════════════ 夹具 ═══════════════════════

    private static ToolUseContext tucWithServer(String sessionId, String serverName, String instructions) {
        Map<String, McpClientRuntime> clients = new LinkedHashMap<>();
        clients.put(serverName, new McpClientRuntime(serverName, "mcp__" + serverName + "__tool", instructions));
        return ToolUseContext.of(
                null, sessionId, PermissionMode.DEFAULT,
                List.of(), "", AbortController.NOOP, List.of(),
                null, PermissionMode.DEFAULT, clients, false, "")
            .withEffectiveProviderType("anthropic");
    }

    /** 正对照 ① · §14 SessionStart hook 注入消息（作者=hook，有专属出口）。 */
    private static ChatMessageDto hookAdditionalContext() {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, Role.user, "hook",
            "<system-reminder>\nSessionStart hook additional context\n</system-reminder>",
            null, List.of(), null, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, true)
            .withSubtype("hook_additional_context");
    }

    /** 正对照 ② · skill_listing 注入消息（作者=attachment + isMeta=true，与三支同形状但有专属出口）。 */
    private static ChatMessageDto skillListing() {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, Role.user, "attachment",
            "<system-reminder>\nskill listing\n</system-reminder>",
            null, List.of(), null, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, true)
            .withSubtype("skill_listing");
    }

    /** 被测 ① · date_change 尾投消息（生产构造器）。 */
    private static ChatMessageDto dateChange() {
        return AgentLoopContext.dateChangeMessage(SESSION,
            "<system-reminder>\nThe date has changed. Today's date is now 2026-09-22.\n</system-reminder>");
    }

    /** 被测 ② · edited_text_file 尾投消息（生产构造器）。 */
    private static ChatMessageDto changedFile() {
        return AgentLoopContext.changedFileMessage(SESSION,
            "<system-reminder>\nA file was modified: /tmp/x.md\n</system-reminder>");
    }

    /** 被测 ③ · mcp_instructions_delta 尾投消息 —— 走<b>生产链路</b>产出（⛔ 不手搓 DTO）。 */
    private static ChatMessageDto mcpInstructionsDelta(AgentState state) {
        int before = state.rawMessages().size();
        AgentLoopContext.maybeEmitMcpInstructionsDelta(
            state, tucWithServer(SESSION, "docs-server", "先查目录再读文件"), "claude-sonnet-4-5");
        List<ChatMessageDto> after = state.rawMessages();
        assertThat(after)
            .as("前置：本通道必须真的产出了一条 delta（否则下面的落库断言是空断言）")
            .hasSize(before + 1);
        return after.get(after.size() - 1);
    }

    /** 反面 ① · author=attachment + isMeta=false —— deferred_tools_delta 尾投的真实形状。 */
    private static ChatMessageDto attachmentNonMeta() {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, Role.user, "attachment",
            "{\"type\":\"deferred_tools_delta\",\"addedNames\":[\"t\"],\"removedNames\":[]}",
            null, List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null,
            false,   // isMeta=false（buildAttachmentMessage 的真实取值）
            false,
            "deferred_tools_delta");
    }

    /** 反面 ② · author=user + isMeta=true —— relevant_memories / nested_memory 的真实形状。 */
    private static ChatMessageDto metaUserNonAttachment() {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, Role.user, "user",
            "<system-reminder>\nmemories\n</system-reminder>",
            null, List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null,
            true,
            false,
            "relevant_memories");
    }

    /** 反面 ③ · role=assistant + author=attachment（形状同族但 role 不对）。 */
    private static ChatMessageDto assistantAttachment() {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), SESSION, Role.assistant, "attachment",
            "{}", null, List.of(), FinishReason.stop, null, null,
            "刚刚", OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null,
            true,
            false,
            "mcp_instructions_delta");
    }

    // ═══════════════════════ 用例 ═══════════════════════

    @Test
    @DisplayName("三支尾投 attachment 各落库恰好一次；既有两出口仍恰好一次（不双写）")
    void tailAttachments_persistExactlyOnce_legacyExitsDoNotDoubleWrite() {
        AgentState state = new AgentState("sys", SESSION, null);
        // 先武装（生产序：arm 后 append）；用户消息本身由 controller 落库，此处不重复 append
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user");

        // ── 正对照：两条有专属出口的消息（hook / skill_listing）──
        ChatMessageDto hook = hookAdditionalContext();
        ChatMessageDto skill = skillListing();
        state.appendMessage(hook);
        state.appendMessage(skill);

        // ── 被测：三支尾投 attachment ──
        ChatMessageDto dc = dateChange();
        ChatMessageDto cf = changedFile();
        state.appendMessage(dc);
        state.appendMessage(cf);
        ChatMessageDto mid = mcpInstructionsDelta(state); // 生产链路 append（已触 listeper）
        assertThat(mid.subtype()).as("前置：第三支确实来自 mcp_instructions_delta 通道")
            .isEqualTo("mcp_instructions_delta");

        // 断言 1：三支形状同族（author=attachment + isMeta=true + role=user）
        for (ChatMessageDto m : List.of(dc, cf, mid)) {
            assertThat(m.role()).as("尾投 attachment 一律 role=user").isEqualTo(Role.user);
            assertThat(m.author()).as("author=attachment（attachment 通道产出的标识）").isEqualTo("attachment");
            assertThat(m.isMeta()).as("isMeta=true（前端据此隐藏）").isTrue();
        }

        // 断言 2：五条消息各<b>恰好一次</b>（既有出口不双写 + 新出口不漏不多）
        ArgumentCaptor<ChatMessageDto> svcCap = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messageService, atLeastOnce()).appendMessage(svcCap.capture(), any(OffsetDateTime.class));
        List<ChatMessageDto> persisted = svcCap.getAllValues();
        Map<String, Long> countById = persisted.stream()
            .collect(Collectors.groupingBy(ChatMessageDto::id, Collectors.counting()));
        assertThat(countById.keySet())
            .as("落库集合 = 正对照两条 + 三支尾投（无额外消息）")
            .containsExactlyInAnyOrder(hook.id(), skill.id(), dc.id(), cf.id(), mid.id());
        for (Map.Entry<String, Long> e : countById.entrySet()) {
            assertThat(e.getValue()).as("id=%s 必须恰好落库一次（双写 = 2，漏写 = 不存在）", e.getKey())
                .isEqualTo(1L);
        }

        // 断言 3：落库 DTO 的列值来源正确（subtype/author/isMeta 一条不改地透传）
        Map<String, ChatMessageDto> byId = persisted.stream()
            .collect(Collectors.toMap(ChatMessageDto::id, m -> m, (a, b) -> a));
        assertThat(byId.get(dc.id()).subtype()).isEqualTo("date_change");
        assertThat(byId.get(cf.id()).subtype()).isEqualTo("edited_text_file");
        assertThat(byId.get(mid.id()).subtype()).isEqualTo("mcp_instructions_delta");
        for (String id : List.of(dc.id(), cf.id(), mid.id(), skill.id())) {
            assertThat(byId.get(id).author()).as("author 列").isEqualTo("attachment");
            assertThat(byId.get(id).isMeta()).as("is_meta 列").isTrue();
        }
        assertThat(byId.get(mid.id()).content())
            .as("mcp delta 落库 content = producer 的 JSON payload（跨轮 diff 扫描源），"
                + "不是渲染文案（文案在发送边界 maybeRenderDeltaAttachmentsForApi 生成）")
            .startsWith("{\"type\":\"mcp_instructions_delta\"");

        // 断言 4：全部消息在内存里（投递通道本身工作正常）
        assertThat(new ArrayList<>(state.rawMessages())).extracting(ChatMessageDto::id)
            .contains(hook.id(), skill.id(), dc.id(), cf.id(), mid.id());
    }

    @Test
    @DisplayName("跨 run：delta 落库后，下一个 run 的『已公告集合』重建 ⇒ 内容未变不再重公告")
    void deltaPersisted_crossRunDoesNotReAnnounce() {
        AgentState state = new AgentState("sys", SESSION, null);
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user");
        ChatMessageDto mid = mcpInstructionsDelta(state);

        // 「DB 历史」= 本 run 里真正落过库的行
        ArgumentCaptor<ChatMessageDto> svcCap = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messageService, atLeastOnce()).appendMessage(svcCap.capture(), any(OffsetDateTime.class));
        List<ChatMessageDto> dbRows = svcCap.getAllValues();
        assertThat(dbRows).extracting(ChatMessageDto::id)
            .as("delta 已落库 ⇒ 下一个 run 从 DB 重建的历史里带着它")
            .contains(mid.id());

        // 下一个 run：producer 的扫描源 = 从 DB 重建的历史（此处以落库行代表）→ 已公告集合非空
        ChatMessageDto again = PostCompactAttachmentRestorer.mcpInstructionsDeltaAttachment(
            tucWithServer(SESSION, "docs-server", "先查目录再读文件"),
            "claude-sonnet-4-5", dbRows);
        assertThat(again)
            .as("已公告集合跨 run 成立（落库前只在 run 内成立）⇒ 连接集/指令未变 → 零追加")
            .isNull();
    }

    @Test
    @DisplayName("反面：非本出口形状（attachment+isMeta=false / 非 attachment 的 meta user / assistant）不落库")
    void nonMatchingShapes_areNotPersistedByThisExit() {
        AgentState state = new AgentState("sys", SESSION, null);
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user");

        ChatMessageDto nonMetaAttachment = attachmentNonMeta();
        ChatMessageDto metaUser = metaUserNonAttachment();
        ChatMessageDto assistantAttachment = assistantAttachment();
        state.appendMessage(nonMetaAttachment);
        state.appendMessage(metaUser);
        state.appendMessage(assistantAttachment);

        // 三条都不经 user 分支的 attachment 出口落库
        verify(messageService, never()).appendMessage(any(ChatMessageDto.class), any(OffsetDateTime.class));
        // 形状前置（否则「没落库」可能只是形状没造对）
        assertThat(nonMetaAttachment.author()).isEqualTo("attachment");
        assertThat(nonMetaAttachment.isMeta()).as("deferred_tools_delta 的真实形状是 isMeta=false").isFalse();
        assertThat(metaUser.isMeta()).isTrue();
        assertThat(metaUser.author()).as("relevant_memories 的 author 是 user，不是 attachment").isEqualTo("user");
        assertThat(assistantAttachment.role()).isEqualTo(Role.assistant);
        // role=assistant 行仍走其本来的直写通道（messageMapper.insert），与本出口无关
        verify(messageMapper, atLeastOnce()).insert(any(MessageRecord.class));
    }
}
