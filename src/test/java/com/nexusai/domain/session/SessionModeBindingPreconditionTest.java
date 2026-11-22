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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 无人值守的开启前置必须与「关闭」同源：**本会话已绑定渠道**（[F1]）。
 *
 * <p>缺陷形态：开启前置读的是「全局已接入渠道实例数」，关闭/自动退出的判据读的是
 * 「本会话绑定」⇒ 一个没绑任何渠道的会话，只要别处连了任意一个 IM 账号就能开无人值守；
 * 开了以后 Ask 被摘、消息却无处可送（用户以为在派活，实际没人收）。
 *
 * <p>本测试用 A/B 两会话隔离证明：
 * <ul>
 *   <li>会话 A 有绑定 ⇒ 能开；</li>
 *   <li>会话 B 没有绑定（但全局渠道实例数 ≥ 1）⇒ 拒绝，且状态未被写入。</li>
 * </ul>
 */
@DisplayName("[F1] 无人值守开启前置 = 本会话绑定渠道（与关闭同源）")
class SessionModeBindingPreconditionTest {

    private SessionModeService service;
    private SessionService sessionService;
    private ImChannelService channelService;
    private com.nexusai.domain.im_channel.ImChatService imChatService;
    private com.nexusai.domain.mcp_channel_binding.ChannelBindingService channelBindingService;

    private static final String SESSION_A = "sess-A-has-binding";
    private static final String SESSION_B = "sess-B-no-binding";

    @BeforeEach
    void setUp() {
        service = new SessionModeService();
        sessionService = mock(SessionService.class);
        channelService = mock(ImChannelService.class);
        imChatService = mock(com.nexusai.domain.im_channel.ImChatService.class);
        channelBindingService = mock(com.nexusai.domain.mcp_channel_binding.ChannelBindingService.class);
        ReflectionTestUtils.setField(service, "sessionService", sessionService);
        ReflectionTestUtils.setField(service, "channelService", channelService);
        ReflectionTestUtils.setField(service, "imChatService", imChatService);
        ReflectionTestUtils.setField(service, "channelBindingService", channelBindingService);

        // 全局存在 1 个渠道实例（旧判据会因此放行 B —— 这正是要钉死的缺陷）
        when(channelService.list()).thenReturn(java.util.Arrays.asList(new ImInstanceEntry[1]));

        SessionRecord rowA = new SessionRecord();
        rowA.setId(SESSION_A);
        SessionRecord rowB = new SessionRecord();
        rowB.setId(SESSION_B);
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOneById(SESSION_A)).thenReturn(rowA);
        when(mapper.selectOneById(SESSION_B)).thenReturn(rowB);
        SessionModeState.setSessionMapper(mapper);
        SessionToolDisableConfig.setSessionMapper(mapper);

        when(sessionService.getRecord(SESSION_A)).thenReturn(rowA);
        when(sessionService.getRecord(SESSION_B)).thenReturn(rowB);

        // A 已绑定一个聊天；B 什么都没绑
        when(imChatService.listBySession(SESSION_A)).thenReturn(List.of(
            new com.nexusai.model.im_channel.ImChatEntry(
                "chat-A", "inst-1", "feishu", "peer-A", "对方", "p2p", 2,
                null, null, SESSION_A, "manual", null, null, null, null)));
        when(imChatService.listBySession(SESSION_B)).thenReturn(List.of());
        when(channelBindingService.listBySession(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SessionModeState.reset();
        SessionToolDisableConfig.reset();
    }

    @Test
    @DisplayName("会话 A 有绑定 ⇒ 可以开启无人值守")
    void sessionWithBindingCanEnable() {
        service.setUnattended(SESSION_A, true);

        // 级联：unattended=true + Brief on（mock 的 SessionService 不回写 row，故断言写入动作）
        verify(sessionService).setUnattendedMode(eq(SESSION_A), eq(true));
        verify(sessionService).setBriefMode(eq(SESSION_A), eq(true));
    }

    @Test
    @DisplayName("会话 B 无绑定（全局有渠道）⇒ 拒绝开启，且不写状态")
    void sessionWithoutBindingIsRejectedEvenIfGlobalChannelExists() {
        assertThatThrownBy(() -> service.setUnattended(SESSION_B, true))
            .isInstanceOf(ValidationException.class);

        verify(sessionService, never()).setUnattendedMode(eq(SESSION_B), eq(true));
    }

    @Test
    @DisplayName("getState.canEnableUnattended 同样按本会话绑定判定")
    void canEnableReflectsSessionBinding() {
        assertThat(service.getState(SESSION_A).canEnableUnattended()).isTrue();
        assertThat(service.getState(SESSION_B).canEnableUnattended()).isFalse();
    }
}
