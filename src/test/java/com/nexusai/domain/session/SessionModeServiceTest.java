package com.nexusai.domain.session;

import com.nexusai.application.agent.config.SessionModeState;
import com.nexusai.application.agent.config.SessionToolDisableConfig;
import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话「无人值守 / Brief」级联（V329）· 纯 Mockito，不启动 Spring。
 *
 * <h2>WHY（规则九：验证意图）</h2>
 * <ul>
 *   <li>T3：开无人值守的唯一硬前置 = 渠道实例 ≥ 1；开 ⇒ Brief on + IM 工具 on（R16）；</li>
 *   <li>T4：手动退出只关 unattended，Brief 保持（R17）；</li>
 *   <li>T6：移除最后渠道 ⇒ unattended off ∧ Brief off（R14）；</li>
 *   <li>Q8：无人值守开启时关 Brief 必须<b>拒绝且状态不变</b>，⛔ 不偷偷选 A/B/C。</li>
 * </ul>
 * 变异点：开无人值守不校验渠道 / 退出时顺手关 Brief / Q8 静默放行 → 对应用例 RED。
 */
class SessionModeServiceTest {

    private SessionModeService service;
    private SessionService sessionService;
    private ImChannelService channelService;
    private com.nexusai.domain.im_channel.ImChatService imChatService;
    private com.nexusai.domain.mcp_channel_binding.ChannelBindingService channelBindingService;
    private SessionRecord record;

    @BeforeEach
    void setUp() {
        service = new SessionModeService();
        sessionService = mock(SessionService.class);
        channelService = mock(ImChannelService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sessionService", sessionService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "channelService", channelService);
        imChatService = mock(com.nexusai.domain.im_channel.ImChatService.class);
        channelBindingService = mock(com.nexusai.domain.mcp_channel_binding.ChannelBindingService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "imChatService", imChatService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "channelBindingService", channelBindingService);
        when(imChatService.listBySession(any())).thenReturn(java.util.List.of());
        when(channelBindingService.listBySession(any())).thenReturn(java.util.List.of());

        record = new SessionRecord();
        record.setId("sess-1");
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(any())).thenReturn(record);
        SessionModeState.setSessionMapper(mapper);
        SessionToolDisableConfig.setSessionMapper(mapper);

        when(sessionService.getRecord("sess-1")).thenReturn(record);
        when(channelService.list()).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        SessionModeState.reset();
        SessionToolDisableConfig.reset();
    }

    private void givenChannelInstances(int n) {
        when(channelService.list()).thenReturn(n <= 0 ? Collections.emptyList()
                : Arrays.asList(new ImInstanceEntry[n]));
    }

    @Test
    @DisplayName("无渠道开启无人值守 ⇒ ValidationException，且不写任何状态（R16 硬前置）")
    void enableWithoutChannelIsRejected() {
        assertThatThrownBy(() -> service.setUnattended("sess-1", true))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(SessionModeService.NEED_CHANNEL_MESSAGE);
        verify(sessionService, never()).setUnattendedMode(anyString(), anyBoolean());
        verify(sessionService, never()).setBriefMode(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("有渠道开启无人值守 ⇒ unattended=true + Brief=true + IM 工具从禁用集移除（R16）")
    void enableWithChannelCascades() {
        // [F1] 开启前置改为「本会话已绑定渠道」⇒ 需要一条真实绑定（不再是全局实例数）
        givenChannelInstances(1);
        when(imChatService.listBySession("sess-1")).thenReturn(java.util.List.of(
            new com.nexusai.model.im_channel.ImChatEntry(
                "chat-1", "inst-1", "feishu", "peer-1", "对方", "p2p", 2,
                null, null, "sess-1", "manual", null, null, null, null)));

        service.setUnattended("sess-1", true);

        verify(sessionService).setUnattendedMode("sess-1", true);
        verify(sessionService).setBriefMode("sess-1", true);
        // [V330] IM 组走增量覆盖；⛔ 不再把动态默认集固化进 disabled_tools（P1-5）。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Boolean>> cap = ArgumentCaptor.forClass(java.util.Map.class);
        verify(sessionService).patchImToolOverrides(eq("sess-1"), cap.capture());
        assertThat(cap.getValue())
                .as("开无人值守 ⇒ IM 组（不含 SendUserMessage）增量为 true")
                .containsEntry("SendImAsUser", true)
                .containsEntry("ListImChats", true)
                .doesNotContainKey("SendUserMessage");
        verify(sessionService, never()).setDisabledTools(anyString(), any());
    }

    @Test
    @DisplayName("手动退出无人值守 ⇒ 只关 unattended；Brief 保持（R17）")
    void disableKeepsBrief() {
        service.setUnattended("sess-1", false);

        verify(sessionService).setUnattendedMode("sess-1", false);
        verify(sessionService, never()).setBriefMode(anyString(), anyBoolean());
        verify(sessionService, never()).setDisabledTools(anyString(), any());
    }

    @Test
    @DisplayName("[Q8] 无人值守开启时关 Brief（未确认）⇒ 拒绝且两状态都不写")
    void briefOffWhileUnattendedWithoutConfirmationIsRejected() {
        record.setUnattendedMode(1);

        assertThatThrownBy(() -> service.setBrief("sess-1", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无人值守");
        verify(sessionService, never()).setBriefMode(anyString(), anyBoolean());
        verify(sessionService, never()).setUnattendedMode(anyString(), anyBoolean());
        verify(sessionService, never()).setBriefAndUnattendedMode(anyString(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("[Q8] 已确认关 Brief ⇒ 一次原子写入 brief=false + unattended=false（不拆两次写）")
    void briefOffWithConfirmationCascadesAtomically() {
        record.setUnattendedMode(1);

        service.setBrief("sess-1", false, true);

        verify(sessionService).setBriefAndUnattendedMode("sess-1", false, false);
        verify(sessionService, never()).setBriefMode(anyString(), anyBoolean());
        verify(sessionService, never()).setUnattendedMode(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("[Q8] 无人值守本就关闭时关 Brief ⇒ 不打扰、不级联、直接生效")
    void briefOffWhenUnattendedClosedTakesEffectDirectly() {
        record.setUnattendedMode(0);

        service.setBrief("sess-1", false);

        verify(sessionService).setBriefMode("sess-1", false);
        verify(sessionService, never()).setBriefAndUnattendedMode(anyString(), anyBoolean(), anyBoolean());
        verify(sessionService, never()).setUnattendedMode(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Brief 单独开 ⇒ 只动 SendUserMessage，不动 Ask 所需状态（R12）")
    void briefToggleOnlyTouchesBriefTool() {
        service.setBrief("sess-1", true);

        verify(sessionService).setBriefMode("sess-1", true);
        verify(sessionService, never()).setUnattendedMode(anyString(), anyBoolean());
        // [V330] Brief 只写 brief_mode；既不镜像 disabled_tools，也不碰 IM 组。
        verify(sessionService, never()).setDisabledTools(anyString(), any());
        verify(sessionService, never()).patchImToolOverrides(anyString(), any());
    }

    @Test
    @DisplayName("移除本会话最后一个渠道 ⇒ unattended off ∧ Brief off（R14）")
    void lastChannelClosedTurnsBothOff() {
        service.onLastChannelClosed("sess-1");

        verify(sessionService).setUnattendedMode("sess-1", false);
        verify(sessionService).setBriefMode("sess-1", false);
        // [V330] SendUserMessage 可见性由 brief_mode 推导（SessionToolVisibility），不写禁用集。
        verify(sessionService, never()).setDisabledTools(anyString(), any());
    }

    @Test
    @DisplayName("还有别的渠道时渠道关闭收敛不动作")
    void channelClosedKeepsStateWhenAnotherChannelRemains() {
        record.setUnattendedMode(1);
        record.setBriefMode(1);
        when(imChatService.listBySession("sess-1"))
            .thenReturn(java.util.List.of(mock(com.nexusai.model.im_channel.ImChatEntry.class)));

        service.onChannelClosed("sess-1");

        verify(sessionService, never()).setUnattendedMode(anyString(), anyBoolean());
        verify(sessionService, never()).setBriefMode(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("无任何渠道时渠道关闭收敛 ⇒ unattended off ∧ Brief off")
    void channelClosedTurnsBothOffWhenNoChannelLeft() {
        record.setUnattendedMode(1);
        record.setBriefMode(1);

        service.onChannelClosed("sess-1");

        verify(sessionService).setUnattendedMode("sess-1", false);
        verify(sessionService).setBriefMode("sess-1", false);
    }

    @Test
    @DisplayName("仅剩 MCP 渠道绑定也算还有渠道")
    void mcpBindingCountsAsChannel() {
        record.setUnattendedMode(1);
        when(channelBindingService.listBySession("sess-1"))
            .thenReturn(java.util.List.of(mock(com.nexusai.model.mcp_channel_binding.ChannelBindingEntry.class)));

        service.onChannelClosed("sess-1");

        verify(sessionService, never()).setUnattendedMode(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("配置渠道（聊天接到会话）⇒ Brief on + IM 工具 on；不动 Ask")
    void channelBoundEnablesBriefAndIm() {
        service.onChannelBound("sess-1");

        verify(sessionService).setBriefMode("sess-1", true);
        verify(sessionService, never()).setUnattendedMode(anyString(), anyBoolean());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Boolean>> cap = ArgumentCaptor.forClass(java.util.Map.class);
        verify(sessionService).patchImToolOverrides(eq("sess-1"), cap.capture());
        assertThat(cap.getValue()).containsEntry("SendImAsUser", true).doesNotContainKey("SendUserMessage");
        verify(sessionService, never()).setDisabledTools(anyString(), any());
    }
}
