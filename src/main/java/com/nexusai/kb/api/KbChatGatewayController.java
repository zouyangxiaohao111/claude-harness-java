package com.nexusai.kb.api;

import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.chat.ChatService;
import com.nexusai.kb.agent.KbBuiltInAgentDefs;
import com.nexusai.kb.app.qa.KbQaPipeline;
import com.nexusai.kb.app.qa.KbQaTurnService;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库对话<b>统一入口</b> · 按会话绑定 Agent 的 {@code agent_mode} 分流到两条链。
 *
 * <h2>⭐⭐ 为什么单独一个聚合入口，而不是改现有接口</h2>
 * 本仓已经有两条独立的对话链，各自的对端、状态机、流式事件、停止语义都不同：
 * <table border="1">
 *   <tr><th>链</th><th>现有入口</th><th>承载</th></tr>
 *   <tr><td>RAG 固定流水线</td><td>{@code POST /api/v1/knowledge-chat/{sid}}</td>
 *       <td>{@link KbQaTurnService}（理解→检索→生成→引用）</td></tr>
 *   <tr><td>ReAct 工具循环</td><td>{@code POST /api/v1/sessions/{sid}/messages}</td>
 *       <td>{@link ChatService} → {@code LlmAgentLoop}（调工具）</td></tr>
 * </table>
 *
 * <p>把分流塞进这两个已有入口，等于<b>改主对话链路</b> —— 所有会话（含用户日常在用的）
 * 都要过一道新判断，判错就会把正常对话喂给 RAG 链。那是最不该冒的险。
 *
 * <p>⇒ 本类只做<b>一件事</b>：读会话的 agent → 看它的 {@code agent_mode} → 转发给对应链。
 * <b>两个已有入口一行不改</b>；调用方（知识库面板）改用本入口即可，
 * 老调用方继续用老入口，行为完全不变。
 *
 * <h2>分流规则（与源站 {@code agent_mode} 语义一致）</h2>
 * <ul>
 *   <li>{@code quick-answer} ⇒ RAG 链。源站此模式下 {@code agent_type} 被忽略、也不调工具</li>
 *   <li>{@code smart-reasoning} ⇒ ReAct 链，会调工具</li>
 *   <li><b>会话未绑定 agent</b> ⇒ ReAct 链（保持本仓既有默认，⛔ 新入口不改变默认）</li>
 *   <li>绑定的不是知识库 agent（如 {@code Explore}）⇒ ReAct 链</li>
 * </ul>
 *
 * <h2>⚠️ 不做的事</h2>
 * <ul>
 *   <li>⛔ 不复制两条链的任何逻辑，只转发</li>
 *   <li>⛔ 不改 {@code ChatController} / {@code KnowledgeChatController}</li>
 *   <li>⛔ 不在本类判断「该不该检索知识库」——那是各链自己的事</li>
 *   <li>⛔ 不实现 busy 排队 / 即时命令 —— ReAct 链内部有兜底（{@code processUserMessage}
 *       自己会判 busy），本入口不重复造那套状态机</li>
 * </ul>
 */
@RestController
public class KbChatGatewayController {

    private static final Logger log = LoggerFactory.getLogger(KbChatGatewayController.class);

    /** 分流结果：走哪条链。 */
    public enum Route {
        /** RAG 固定流水线（quick-answer）。 */
        RAG,
        /** ReAct 工具循环（smart-reasoning / 未绑定 / 非 kb agent）。 */
        REACT
    }

    @Autowired private SessionMapper sessionMapper;
    @Autowired private KbQaTurnService turnService;
    @Autowired private ChatService chatService;
    @Autowired private com.nexusai.domain.session.MessageService messageService;
    @Autowired private SimpMessagingTemplate wsTemplate;

    /**
     * 统一对话入口。
     *
     * @param sessionId 会话 ID
     * @param req       请求体
     * @return 202 + 受理信息（{@code route} 说明实际走了哪条链）
     */
    @PostMapping("/api/v1/kb/chat/{sessionId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GatewayAccepted chat(@PathVariable String sessionId, @RequestBody GatewayRequest req) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("content 不能为空");
        }

        String agentId = boundAgentId(sessionId);
        Route route = resolveRoute(agentId);
        log.info("[KB对话入口] 分流 session={} agent={} mode={} → {}",
            sessionId, agentId == null ? "(未绑定)" : agentId,
            agentId == null ? "-" : BuiltInAgents.agentModeOf(agentId).orElse("-"),
            route);

        if (route == Route.RAG) {
            return viaRag(sessionId, req);
        }
        return viaReact(sessionId, req);
    }

    // ── 两条链的转发 ──────────────────────────────────────────────────────────

    /** RAG 链：与 {@code KnowledgeChatController.ask} 完全同一个调用。 */
    private GatewayAccepted viaRag(String sessionId, GatewayRequest req) {
        KbQaPipeline.Request pipelineReq = new KbQaPipeline.Request(
            sessionId, null, null, req.content(),
            req.knowledgeBaseIds(), req.knowledgeIds(), req.modelName(),
            Boolean.TRUE.equals(req.webSearchEnabled()), new AtomicBoolean(false));
        KbQaTurnService.Accepted accepted = turnService.accept(sessionId, req.content(), pipelineReq, req.attachments());
        return new GatewayAccepted(accepted.userMessageId(), accepted.assistantMessageId(),
            accepted.streamTopic(), false, Route.RAG.name());
    }

    /**
     * ReAct 链：与 {@code ChatController.send} 的空闲路径完全同一组调用
     * （{@code validateFallbackModelDistinct} → {@code createUserMessage} → {@code processUserMessage}）。
     *
     * <p>⚠️ busy 时由 {@code processUserMessage} 内部兜底排队（它自己有
     * {@code isSessionRunning} 判定），本入口不重复实现排队框那套状态机 ——
     * 那会让两条路径的排队行为出现分叉。
     */
    private GatewayAccepted viaReact(String sessionId, GatewayRequest req) {
        SendMessageRequest sendReq = new SendMessageRequest(
            req.content(),
            req.modelName(),
            null,                 // useFastModel：网关不指定，走会话默认
            req.attachments() == null ? List.of() : req.attachments(),
            null,                 // appendSystemPrompt
            null,                 // fallbackModel
            null,                 // permissionMode
            null,                 // dangerouslySkipPermissions
            null);                // jsonSchema
        chatService.validateFallbackModelDistinct(sessionId, sendReq);
        MessageCreatedResponse resp = messageService.createUserMessage(sessionId, sendReq);
        chatService.processUserMessage(sessionId, resp.userMessageId(), sendReq, wsTemplate);
        return new GatewayAccepted(resp.userMessageId(), resp.assistantMessageId(),
            resp.streamTopic(), resp.queued(), Route.REACT.name());
    }

    // ── 分流判据 ──────────────────────────────────────────────────────────────

    /**
     * 读会话绑定的 agent id。
     *
     * @param sessionId 会话 ID
     * @return agent id；未绑定 / 会话不存在 ⇒ {@code null}
     */
    String boundAgentId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            var row = sessionMapper.selectOneById(sessionId);
            if (row == null) {
                return null;
            }
            String agent = row.getMainThreadAgent();
            return agent == null || agent.isBlank() ? null : agent.trim();
        } catch (RuntimeException e) {
            // 读会话失败不该让整轮失败 —— 按「未绑定」处理（走 ReAct，本仓既有默认）
            log.warn("[KB对话入口] 读会话失败，按未绑定处理 session={} 原因={}", sessionId, e.toString());
            return null;
        }
    }

    /**
     * 按 agent 的执行模式决定走哪条链。
     *
     * <p><b>判据只有一条</b>：{@code agentMode == "quick-answer"} ⇒ RAG；其余一律 ReAct。
     * ⚠️「其余」包含三种情况，共同点是<b>都不该走 RAG</b>：
     * <ol>
     *   <li>{@code smart-reasoning} ⇒ 本来就是 ReAct</li>
     *   <li>未绑定 agent ⇒ 保持本仓既有默认（ReAct），⛔ 新入口不改变默认</li>
     *   <li>绑定了非 kb agent（{@code Explore} 等）⇒ 它们没有 agentMode，走 ReAct</li>
     * </ol>
     *
     * @param agentId 会话绑定的 agent id（可 null）
     * @return 分流结果
     */
    Route resolveRoute(String agentId) {
        if (agentId == null) {
            return Route.REACT;
        }
        Optional<String> mode = BuiltInAgents.agentModeOf(agentId);
        if (mode.isPresent() && KbBuiltInAgentDefs.MODE_QUICK_ANSWER.equals(mode.get())) {
            return Route.RAG;
        }
        return Route.REACT;
    }

    // ── 请求 / 响应 ───────────────────────────────────────────────────────────

    /**
     * 统一入参。
     *
     * @param content           用户问题（必填）
     * @param knowledgeBaseIds  指定知识库（可空 = 全部授权库；仅 RAG 链消费）
     * @param modelName         模型名（可空 = 会话/配置默认）
     * @param webSearchEnabled  是否允许联网（仅 RAG 链消费）
     */
    public record GatewayRequest(
        String content,
        List<String> knowledgeBaseIds,
        /** 文档级范围（@ 选的文件）· 对齐源站 {@code knowledge_ids} */
        List<String> knowledgeIds,
        /** 本轮 @ 选中的知识库/文件快照（落 user 气泡胶囊；不参与检索范围） */
        List<com.nexusai.model.session.dto.AttachmentRequest> attachments,
        String modelName,
        Boolean webSearchEnabled
    ) {
    }

    /**
     * 统一出参 —— 两条链的返回形状不同（4 字段 vs 3 字段），这里归一成同一份。
     *
     * @param userMessageId      用户消息 id
     * @param assistantMessageId 助手消息 id（{@code msg-stub-pending} = 待生成）
     * @param streamTopic        流式 topic
     * @param queued             是否因上一轮未结束而入队
     * @param route              实际走的链：{@code RAG} / {@code REACT}
     */
    public record GatewayAccepted(
        String userMessageId,
        String assistantMessageId,
        String streamTopic,
        boolean queued,
        String route
    ) {
    }
}
