package com.nexusai.kb.api;

import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.model.session.dto.SendMessageRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 问答链别名端点 · 批次 W12 · 4 条（源侧路径与主对话链不同）。
 *
 * <p><b>WHY 需要这一层</b>：源侧把「知识问答 / Agent 问答 / 停止 / 续流」暴露在
 * <b>顶层路径</b>（{@code /agent-chat/{sid}}、{@code /sessions/{sid}/stop}、
 * {@code /sessions/continue-stream/{sid}}），而本仓主对话链用的是
 * {@code /sessions/{sid}/messages} + {@code /cancel}。契约校验（{@code validate_contracts.py}）
 * 把这几条照成 missing —— 本类补**同语义的别名**，⛔ 不复制实现。
 *
 * <p><b>⚠️ 为什么 stop 不叫 cancel</b>：源侧叫 stop，前端按它接。别名两个都留，
 * 删掉 {@code /cancel} 会让本仓自己的前端坏掉。
 */
@RestController
public class QaAliasController {

    private static final Logger log = LoggerFactory.getLogger(QaAliasController.class);

    @Autowired private ChatService chatService;
    @Autowired private MessageService messageService;
    @Autowired private SimpMessagingTemplate wsTemplate;

    /**
     * Agent 问答（源侧 {@code POST /agent-chat/{session_id}}）。
     *
     * <p>本仓的「Agent 问答」就是主对话链（走 {@code AgentLoop} + 工具），
     * 与知识问答（固定四阶段检索链）是两条链。⇒ 直接委托 {@link ChatService}。
     *
     * @param sessionId 会话
     * @param req       消息
     * @return 202 + 消息 id
     */
    @PostMapping("/api/v1/agent-chat/{sessionId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageCreatedResponse agentChat(@PathVariable String sessionId,
                                            @Valid @RequestBody SendMessageRequest req) {
        log.info("[问答别名] POST /agent-chat/{} 委托主对话链", sessionId);
        // 与 ChatController#send 同一顺序：先落 user 消息，再异步触发 LLM 流
        MessageCreatedResponse resp = messageService.createUserMessage(sessionId, req);
        chatService.processUserMessage(sessionId, resp.userMessageId(), req, wsTemplate);
        return resp;
    }

    /**
     * 停止生成（源侧 {@code POST /sessions/{session_id}/stop}）。
     *
     * @param sessionId 会话
     * @return 202（无响应体，与 {@code /cancel} 一致）
     */
    @PostMapping("/api/v1/sessions/{sessionId}/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void stop(@PathVariable String sessionId) {
        log.info("[问答别名] POST /sessions/{}/stop → cancelSession", sessionId);
        chatService.cancelSession(sessionId, wsTemplate);
    }

    /**
     * 继续接收活跃流（源侧 {@code GET /sessions/continue-stream/{session_id}}）。
     *
     * <p><b>⚠️ 本仓的流是 STOMP 推送</b>，不是 HTTP 长连接 ⇒ 「继续接收」= 客户端重新订阅
     * 该会话的 topic。这里如实返回订阅地址与本仓的语义说明，⛔ 不返回一个假的流式响应。
     *
     * @param sessionId 会话
     * @return topic 与说明
     */
    @GetMapping("/api/v1/sessions/continue-stream/{sessionId}")
    public Map<String, Object> continueStream(@PathVariable String sessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("stream_topic", "/topic/sessions/" + sessionId + "/stream");
        body.put("mode", "stomp");
        body.put("note", "本仓用 STOMP 推送（非 HTTP 流）：客户端重新订阅 stream_topic 即继续接收，"
            + "无需单独的续流请求。");
        log.info("[问答别名] GET /sessions/continue-stream/{} → STOMP topic", sessionId);
        return body;
    }
}
