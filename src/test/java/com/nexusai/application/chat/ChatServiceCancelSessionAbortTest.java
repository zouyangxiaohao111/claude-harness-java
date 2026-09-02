package com.nexusai.application.chat;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.eventbus.ws.MessageCancelledEvent;
import com.nexusai.eventbus.ws.SessionStatusEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [S4-FIX] cancelSession 接通 loop abort 通道测试。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：fix 前 {@link ChatService#cancelSession} 只置
 * {@code task.cancel}（该 flag 仅在 {@code loop.run()} 返回后 {@code processUserMessage:299} 检查），
 * 运行中的 LlmAgentLoop 无任何对 ChatTask.cancel 的引用 → 已删会话 in-flight turn 仍继续跑并推
 * STOMP 至已删 topic（探查 S4 目标击穿）。fix 后 cancelSession 先经
 * {@link SessionAgentStateRegistry} 取在飞主 AgentState（agentId==null，LlmAgentLoop.run() 入口
 * LlmAgentLoop:1881-1891 注册）调 {@code state.cancel()}，使 LlmAgentLoop.run() 内
 * {@code state.cancelled()} 轮询（LlmAgentLoop:4660/4682）500ms 内退出。
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入
 * {@code sessionAgentStateRegistry}（对齐 ChatServiceResumeWorktreeTest 先例）。会话键归一化断言
 * raw {@code 'sess-xxx'} 与派生 UUID 同键（SessionKeys.canonicalUuid）。
 */
@DisplayName("[S4-FIX] ChatService.cancelSession → 在飞 AgentState.cancel()")
class ChatServiceCancelSessionAbortTest {

    private final SimpMessagingTemplate wsTemplate = mock(SimpMessagingTemplate.class);

    @Test
    @DisplayName("cancelSession 无 in-progress task 也 cancel 在飞 AgentState（session 删除路径核心）")
    void cancelSession_cancelsInFlightAgentState_evenWithoutChatTask() {
        // [2026-08-24 会话 id 统一 short] 会话唯一键 = "sess-xxxxxxxx"（short，派生 UUID 已废弃），
        //   registry.sessions 键与 AgentState.sessionId 均用 short（rawSession）
        String rawSession = "sess-abc12345";

        ChatService service = new ChatService();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        ReflectionTestUtils.setField(service, "sessionAgentStateRegistry", registry);

        // 在飞主 AgentState（agentId=null 主会话，LlmAgentLoop.run() 入口按 sessionId=short 注册）
        AgentState inFlight = new AgentState(null, rawSession, null);
        registry.register(rawSession, inFlight);
        // 另一会话的 AgentState（不应被误 cancel）
        AgentState otherSession = new AgentState(null, "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), null);
        registry.register(UUID.randomUUID(), otherSession);

        // delete 路径即便无 in-progress ChatTask 也须 abort 在飞 AgentState（S4 核心目标）
        boolean result = service.cancelSession(rawSession, wsTemplate);

        assertThat(result).as("无 in-progress task → false（task.cancel 主路径 no-op）").isFalse();
        assertThat(inFlight.cancelled())
            .as("在飞 AgentState 必须被 cancel（loop state.cancelled() 轮询 500ms 内退出）").isTrue();
        assertThat(otherSession.cancelled()).as("其他会话 AgentState 不得被误 cancel").isFalse();
    }

    @Test
    @DisplayName("cancelSession 无在飞 AgentState 时安全 no-op 不抛异常")
    void cancelSession_noInFlightState_isNoop() {
        ChatService service = new ChatService();
        SessionAgentStateRegistry registry = new SessionAgentStateRegistry();
        ReflectionTestUtils.setField(service, "sessionAgentStateRegistry", registry);

        // registry 为空（无在飞 turn）→ cancelSession 不应 NPE，正常返回 false
        boolean result = service.cancelSession("sess-abc12345", wsTemplate);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("[cancel-必达] cancelSession task==null 也推 message.cancelled + status=idle（前端 activeStreams 清理不卡死）")
    void cancelSession_taskNull_stillPushesCancelled() {
        // WHY（规则九 · 停止键卡死修复）: 前端 activeStreams 依赖 cancelled 事件清理——task==null
        //   （@Async 启动竞态 / run 已结束）时修复前静默 return false 一个事件不发，前端永久等
        //   cancelled → 停止键卡死。修复后 cancel 必回 cancelled + idle（幂等：前端 handleSessionDone
        //   topic 校验 + 已删则 no-op，重复无害）。
        ChatService service = new ChatService();
        SimpMessagingTemplate spyWs = mock(SimpMessagingTemplate.class);

        boolean result = service.cancelSession("sess-abc12345", spyWs);

        assertThat(result).as("无实际 ChatTask 被取消 → 返回 false，但事件仍已发").isFalse();
        verify(spyWs).convertAndSend(eq("/topic/sessions/sess-abc12345/stream"),
            any(MessageCancelledEvent.class));
        verify(spyWs).convertAndSend(eq("/topic/sessions/sess-abc12345/stream"),
            any(SessionStatusEvent.class));
    }
}
