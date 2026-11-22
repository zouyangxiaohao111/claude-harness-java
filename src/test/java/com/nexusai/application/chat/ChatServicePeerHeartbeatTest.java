package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.messaging.PeerSessionRegistry;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.domain.provider.ProviderService;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [peer-heartbeat] 会话起轮端点（{@code PeerSessionRegistry}）的最近活动时间必须被刷新。
 *
 * <p><b>WHY</b>：{@code heartbeat} 此前无任何生产调用点 ⇒ {@code lastActivityAt} 注册后永不前进，
 * 是死字段。本测试钉住 ChatService 主路径「每轮开始/结束刷活动时间」的接线：一轮结束后
 * {@code lastActivityAt} 必须严格前进（ListAgents 按最近活动排序的用户面依据）。
 *
 * <p><b>手法</b>：沿用 {@code ChatServiceTurnEndIdleAlwaysPushedTest} 的
 * {@code new ChatService()} + {@link ReflectionTestUtils} harness（{@code processUserMessage} 直调，
 * 无 Spring proxy），只多注入一个真实 {@link PeerSessionRegistry}。
 *
 * <p><b>变异判据</b>：删掉 ChatService 里两处 {@code heartbeatPeerEndpoint} 调用 ⇒ 本用例必红
 * （存量 endpoint 的活动时间停在上一轮）。为避免 {@code updateMode} 顺带刷新活动时间掩盖缺口，
 * 测试注册表覆写 {@code updateMode} 为 no-op（只隔离本用例关心的 heartbeat 效果）。
 */
@DisplayName("[peer-heartbeat] 一轮结束后 endpoint.lastActivityAt 必须前进")
class ChatServicePeerHeartbeatTest {

    private ChatService service;
    private SessionMapper sessionMapper;
    @SuppressWarnings("unchecked")
    private ObjectProvider<LlmAgentLoop> loopProvider;
    private SimpMessagingTemplate wsTemplate;
    private PeerSessionRegistry registry;
    private String sid;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);

        sessionMapper = mock(SessionMapper.class);
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        MessageMapper messageMapper = mock(MessageMapper.class);
        when(messageMapper.selectListByQuery(any())).thenReturn(new ArrayList<>());
        ReflectionTestUtils.setField(service, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "modelMapper", mock(ModelMapper.class));
        ReflectionTestUtils.setField(service, "providerMapper", mock(ProviderMapper.class));
        ReflectionTestUtils.setField(service, "settingsMapper", mock(SettingsMapper.class));
        ReflectionTestUtils.setField(service, "providerService", mock(ProviderService.class));
        LlmProviderFactory llmProviderFactory = mock(LlmProviderFactory.class);
        when(llmProviderFactory.getProvider(any(), any())).thenReturn(mock(LlmProvider.class));
        ReflectionTestUtils.setField(service, "llmProviderFactory", llmProviderFactory);
        ReflectionTestUtils.setField(service, "modelConfigResolver", mock(ModelConfigResolver.class));
        ReflectionTestUtils.setField(service, "notificationQueue", new NotificationQueue());
        ReflectionTestUtils.setField(service, "queueEventPublisher", mock(QueueEventPublisher.class));
        ReflectionTestUtils.setField(service, "messageService",
            mock(com.nexusai.domain.session.MessageService.class));

        loopProvider = mock(ObjectProvider.class);
        ReflectionTestUtils.setField(service, "loopProvider", loopProvider);
        wsTemplate = mock(SimpMessagingTemplate.class);

        when(sessionMapper.selectOneById(sid)).thenReturn(mock(SessionRecord.class));

        // 隔离：让 lastActivityAt 只由 heartbeat 推进（生产里 updateMode 也会写 now）。
        registry = new PeerSessionRegistry() {
            @Override
            public boolean updateMode(String sessionId, long endpointEpoch,
                                      PermissionMode mode, long modeVersion) {
                return false;
            }
        };
        ReflectionTestUtils.setField(service, "peerSessionRegistry", registry);
    }

    @AfterEach
    void tearDown() {
        LlmAgentLoop.markIdle(sid);
    }

    /** 每次 run 返回一个独立的正常终态 state。 */
    private void wireLoopReturningNormalState() {
        LlmAgentLoop mockLoop = mock(LlmAgentLoop.class);
        when(mockLoop.run(any(RunRequest.class))).thenAnswer(inv -> {
            AgentState state = new AgentState("sys", sid, null);
            state.appendMessage(LlmAgentLoop.toMessage(Role.assistant, "同轮回复", null, "a-final"));
            return state;
        });
        when(loopProvider.getObject()).thenReturn(mockLoop);
    }

    private void runTurn() {
        service.processUserMessage(sid, "msg-" + UUID.randomUUID().toString().substring(0, 8),
            new SendMessageRequest("主问题", null, null, null, null, null, null, null, null),
            wsTemplate);
    }

    @Test
    @DisplayName("a) 一轮结束后 lastActivityAt 前进（有实际用途：ListAgents 按最近活动排序）")
    void turnEndAdvancesLastActivityAt() throws Exception {
        wireLoopReturningNormalState();

        // 第一轮：登记 endpoint（注册时 lastActivityAt=now）并完成一轮
        runTurn();
        Instant baseline = registry.resolve(sid).orElseThrow().lastActivityAt();

        // 明确拉开时间窗，避免毫秒级相等导致「未前进」不可判别
        Thread.sleep(25L);

        // 第二轮：复用同一 endpoint（不重新 register）→ 只有 heartbeat 能推进活动时间
        runTurn();
        Instant after = registry.resolve(sid).orElseThrow().lastActivityAt();

        assertThat(after)
            .as("一轮结束后 must refresh lastActivityAt（否则 ListAgents 的最近活动排序/展示永远是注册时刻）")
            .isAfter(baseline);
    }
}
