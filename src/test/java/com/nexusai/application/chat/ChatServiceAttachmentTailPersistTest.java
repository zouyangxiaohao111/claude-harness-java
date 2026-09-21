package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.loop.AgentLoopContext;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.McpClientRuntime;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.ChatMessageDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * <b>[待解 C · 定性] 「尾部 attachment 投递」通道产出的消息<b>是否落库</b></b> ——
 * 这是复验者标为 {@code PLAUSIBLE_UNVERIFIED}（源码级 CONFIRMED、无运行验证）的那一条。
 *
 * <h2>为什么需要本类</h2>
 * 每个 run 都从 DB 全量重载历史（{@code ChatService.processUserMessage} → doRun 历史注入）。
 * 「尾部投递」通道（{@code AgentLoopContext.maybeEmitDateChange} / {@code maybeEmitChangedFiles} /
 * {@code maybeEmitMcpInstructionsDelta}）都走 {@code state.appendMessage(...)}，而
 * {@code appendMessage} 只把消息加进内存并触 {@code appendListener}；<b>真正决定落不落 DB 的是
 * {@link ChatService#persistAppendedMessage}</b>（{@code ChatService.java:1462}，user 分支
 * {@code :1563-1755}）。本类用<b>生产链路</b>（{@code armRealTimePersist} + {@code appendMessage}）
 * 观察该分支对三支 attachment 消息的处置。
 *
 * <h2>结论（本类锁定的当前形态）</h2>
 * user 分支只有 4 个出口：图片回写 / mid-turn 排队（{@code injectedQueuedById}）/
 * {@code author=hook & subtype=hook_additional_context} / {@code subtype=skill_listing}，
 * 其余一律 {@code return} 不 insert。三支尾投消息的形状都是
 * {@code role=user, author='attachment', isMeta=true, subtype=<各自>}
 * ⇒ <b>三支全都不落库</b>（不是本通道漏落，是既有形态）。
 *
 * <h2>对照设计（CLAUDE.md 规则九）</h2>
 * <ul>
 *   <li><b>正对照</b>：{@code hook_additional_context} 与 {@code skill_listing} 两条<b>同形状</b>
 *       （{@code role=user + isMeta=true}）但<b>有出口</b>的消息必须落库 ⇒ 证明夹具/武装链有效
 *       （否则「没落库」可能只是 listener 没武装的假绿）。</li>
 *   <li><b>被测</b>：{@code date_change} / {@code edited_text_file} / {@code mcp_instructions_delta}
 *       三支 ⇒ 一条都不落库。</li>
 * </ul>
 *
 * <p><b>⚠️ 本类是「刻画测试」不是「意图测试」</b>：它锁定的是<b>当前实现事实</b>，用于把
 * finding C 从 PLAUSIBLE_UNVERIFIED 抬到 VERIFIED。若产品裁定改为「尾投 attachment 也应落库」，
 * 本类必须随之更新（届时它变红正是期望行为 —— 它强制那次改动显式发生）。
 *
 * <p>纯单测（⛔ 无 Spring / 无 @SpringBootTest / ⛔ 不触真库）：{@code new ChatService()} +
 * ReflectionTestUtils 注入 mock mapper / mock MessageService；MCP 连接用真
 * {@code McpClientRuntime}；env 门走 {@link ToolSearchService#envOverride}。
 */
@DisplayName("[待解 C] 尾部 attachment 投递是否落库（三支全不落 = 既有形态）")
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

    /** 正对照 ② · skill_listing 注入消息（作者=attachment，但有专属出口）。 */
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
        // [去门 · 2026-09-21] 原此处注入 CLAUDE_CODE_MCP_INSTR_DELTA=true 打开门；门已按 2.1.278
        //   整体删除 ⇒ 该通道无条件产出，无需任何 env 注入。
        int before = state.rawMessages().size();
        AgentLoopContext.maybeEmitMcpInstructionsDelta(
            state, tucWithServer(SESSION, "docs-server", "先查目录再读文件"), "claude-sonnet-4-5");
        List<ChatMessageDto> after = state.rawMessages();
        assertThat(after)
            .as("前置：本通道必须真的产出了一条 delta（否则下面的「没落库」是空断言）")
            .hasSize(before + 1);
        return after.get(after.size() - 1);
    }

    // ═══════════════════════ 用例 ═══════════════════════

    @Test
    @DisplayName("正对照落库 + 三支尾投 attachment 一条都不落库（既有形态，非本通道独有）")
    void tailAttachments_areNotPersisted_positiveControlsAre() {
        AgentState state = new AgentState("sys", SESSION, null);
        // 先武装（生产序：arm 后 append）；用户消息本身由 controller 落库，此处不重复 append
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user");

        // ── 正对照：两条同形状（role=user + isMeta=true）但有出口的消息必须落库 ──
        ChatMessageDto hook = hookAdditionalContext();
        ChatMessageDto skill = skillListing();
        state.appendMessage(hook);
        state.appendMessage(skill);
        verify(messageService, atLeastOnce()).appendMessage(any(ChatMessageDto.class), any(OffsetDateTime.class));

        // ── 被测：三支尾投 attachment ──
        ChatMessageDto dc = dateChange();
        ChatMessageDto cf = changedFile();
        ChatMessageDto mid = mcpInstructionsDelta(state);
        assertThat(mid.subtype()).as("前置：第三支确实来自 mcp_instructions_delta 通道")
            .isEqualTo("mcp_instructions_delta");
        state.appendMessage(dc);
        state.appendMessage(cf);
        // mid 已由生产链路 append（= 触发了 appendListener），此处不重复 append

        // 断言 1：三支的形状确实同族（否则「不落库」可能只是形状不对）
        for (ChatMessageDto m : List.of(dc, cf, mid)) {
            assertThat(m.role()).as("尾投 attachment 一律 role=user").isEqualTo(Role.user);
            assertThat(m.author()).as("author=attachment（attachment 通道产出的标识）").isEqualTo("attachment");
            assertThat(m.isMeta()).as("isMeta=true（前端据此隐藏）").isTrue();
        }

        // 断言 2：三支都<b>不</b>经 MessageService 落库（hook/skill_listing 的出口不适用）
        ArgumentCaptor<ChatMessageDto> svcCap = ArgumentCaptor.forClass(ChatMessageDto.class);
        verify(messageService, atLeastOnce()).appendMessage(svcCap.capture(), any(OffsetDateTime.class));
        assertThat(svcCap.getAllValues()).extracting(ChatMessageDto::id)
            .as("MessageService.appendMessage 只应被正对照两条命中")
            .containsExactlyInAnyOrder(hook.id(), skill.id())
            .doesNotContain(dc.id(), cf.id(), mid.id());

        // 断言 3：三支也<b>不</b>经 ChatService 直写 messageMapper.insert
        //   （user 分支无 insert 出口；直写只发生在 snip_boundary / assistant / tool 三类）
        verify(messageMapper, never()).insert(any(MessageRecord.class));

        // 断言 4：三支确实在内存里（投递通道本身工作正常 ⇒ 上面「不落库」不是因消息没产出）
        assertThat(new ArrayList<>(state.rawMessages())).extracting(ChatMessageDto::id)
            .as("三支都在内存（通道产出正常）—— 缺的只是 DB 落库这一格")
            .contains(dc.id(), cf.id(), mid.id());
    }

    @Test
    @DisplayName("跨 run 复现：delta 消息不进 DB ⇒ 下一个 run 从 DB 重建的历史里没有它")
    void deltaNotPersisted_survivesOnlyInMemory() {
        AgentState state = new AgentState("sys", SESSION, null);
        service.armRealTimePersist(state, SESSION, STREAM_TOPIC, wsTemplate, "msg-user");
        ChatMessageDto mid = mcpInstructionsDelta(state);

        // 「DB 历史」= 本 run 里所有真正落过库的行；mock 下只有 messageMapper.insert /
        //   messageService.appendMessage 两条写口（nextCreatedAt 是取号读口，不算落库）
        verify(messageMapper, never()).insert(any(MessageRecord.class));
        verify(messageService, never()).appendMessage(any(ChatMessageDto.class), any(OffsetDateTime.class));

        // ⇒ 重载历史（新 run 的 state）里不会有这条 delta：producer 的跨轮 diff 扫描源为空集
        AgentState reloaded = new AgentState("sys", SESSION, null);
        assertThat(reloaded.rawMessages()).extracting(ChatMessageDto::id).doesNotContain(mid.id());
    }
}
