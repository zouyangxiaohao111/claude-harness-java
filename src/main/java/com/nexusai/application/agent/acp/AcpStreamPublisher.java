package com.nexusai.application.agent.acp;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.SessionAgentStateRegistry;
import com.nexusai.eventbus.ws.MessageChunkEvent;
import com.nexusai.eventbus.ws.MessageToolCallEvent;
import com.nexusai.eventbus.ws.MessageToolResultEvent;
import com.nexusai.eventbus.ws.StreamEvent;
import com.nexusai.eventbus.ws.StreamEventSeq;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ACP 实时推送器 · {@code session/update} → 会话级流 topic。
 *
 * <p>形状照 {@link com.nexusai.application.agent.tool.impl.BashProgressPublisher}
 * （{@code @Autowired(required=false)} + fail-soft + {@link StreamEventSeq#send} 盖 seq），
 * 差别只在「谁来调」：ACP 的执行链是纯静态类，拿不到 Spring 注入 ⇒ 本 bean 在
 * {@code @PostConstruct} 时把自己登记进静态槽，执行链经 {@link #current()} 取用。
 *
 * <p><b>assistantMessageId 归属</b>：照 {@code ChatService.java:1262-1267} 的取法，读
 * {@link SessionAgentStateRegistry} 的当前轮在飞 id。取不到就返回 null ⇒ 调用方跳过实时
 * 推送（⛔ 不伪造 id 把卡片挂到错误气泡上；transcript 仍然落盘）。
 */
@Component
public class AcpStreamPublisher implements AcpEventSink {

    private static final Logger log = LoggerFactory.getLogger(AcpStreamPublisher.class);

    private static volatile AcpStreamPublisher INSTANCE;

    @Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    @Autowired(required = false)
    private SessionAgentStateRegistry stateRegistry;

    @PostConstruct
    void register() {
        INSTANCE = this;
    }

    @PreDestroy
    void unregister() {
        if (INSTANCE == this) {
            INSTANCE = null;
        }
    }

    /** 执行链取用入口：无 Spring 环境（单测/直构）返回 {@link AcpEventSink#NOOP}。 */
    public static AcpEventSink current() {
        AcpStreamPublisher instance = INSTANCE;
        return instance == null ? AcpEventSink.NOOP : instance;
    }

    /** 测试注入点。 */
    public void setWsTemplate(SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }

    /** 测试注入点。 */
    public void setStateRegistry(SessionAgentStateRegistry stateRegistry) {
        this.stateRegistry = stateRegistry;
    }

    @Override
    public String resolveAssistantMessageId(String sessionId) {
        if (stateRegistry == null || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        AgentState state = stateRegistry.get(sessionId);
        return state == null ? null : state.currentAssistantMessageId();
    }

    @Override
    public void chunk(String sessionId, String assistantMessageId, String delta, String reasoning) {
        if (!ready(sessionId, assistantMessageId)) {
            return;
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            send(sessionId, MessageChunkEvent.ofReasoning(sessionId, null, assistantMessageId, reasoning));
        } else if (delta != null && !delta.isEmpty()) {
            send(sessionId, MessageChunkEvent.of(sessionId, null, assistantMessageId, delta));
        }
    }

    @Override
    public void toolCall(String sessionId, String assistantMessageId, String toolCallId,
                         String toolName, Map<String, Object> arguments) {
        if (!ready(sessionId, assistantMessageId) || toolCallId == null || toolCallId.isBlank()) {
            return;
        }
        send(sessionId, new MessageToolCallEvent(sessionId, null, assistantMessageId,
                toolCallId, toolName == null ? "acp-tool" : toolName, arguments));
    }

    @Override
    public void toolResult(String sessionId, String assistantMessageId, String toolCallId,
                           String result, boolean isError, Long durationMs) {
        if (!ready(sessionId, assistantMessageId) || toolCallId == null || toolCallId.isBlank()) {
            return;
        }
        send(sessionId, new MessageToolResultEvent(sessionId, null, assistantMessageId,
                toolCallId, result, isError, durationMs));
    }

    private boolean ready(String sessionId, String assistantMessageId) {
        if (wsTemplate == null) {
            if (log.isDebugEnabled()) {
                log.debug("[AcpStreamPublisher] wsTemplate 未注入，跳过实时推送");
            }
            return false;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[AcpStreamPublisher] 取不到当前 assistantMessageId，跳过实时推送 session={}", sessionId);
            }
            return false;
        }
        return true;
    }

    private void send(String sessionId, StreamEvent event) {
        String topic = "/topic/sessions/" + sessionId + "/stream";
        try {
            StreamEventSeq.send(wsTemplate, topic, event);
        } catch (Throwable t) {
            // 推送失败不阻断外部进程（best-effort）
            log.warn("[AcpStreamPublisher] 推送失败 session={} type={} err={}",
                    sessionId, event.getType(), t.toString());
        }
    }
}
