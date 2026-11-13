package com.nexusai.kb.app.qa;

import com.nexusai.application.chat.ChatService;
import com.nexusai.domain.session.MessageService;
import com.nexusai.eventbus.ws.MessageCompleteEvent;
import com.nexusai.eventbus.ws.MessageErrorEvent;
import com.nexusai.eventbus.ws.MessageUserEvent;
import com.nexusai.eventbus.ws.StreamEventSeq;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.mapper.SessionMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 问答轮的「外壳」· 对应源侧 {@code handler/session/qa.go:executeQA} 里**与执行器无关**的那部分。
 *
 * <h2>源侧结构（照抄的骨架）</h2>
 * <pre>
 * POST /knowledge-chat/:sid → KnowledgeQA → executeQA(mode=normal) ┐
 * POST /agent-chat/:sid     → AgentQA     → executeQA(mode=agent)  ┘ 同一个外壳
 * </pre>
 * 外壳负责：落 user/assistant 消息、开流、收流、失败回滚、标题生成。
 * <b>只有「执行器」不同</b> —— 本仓的执行器是 {@link KbQaPipeline}（RAG），
 * 主对话有自己的执行器（{@code LlmAgentLoop}），两者不共享执行逻辑。
 *
 * <h2>⭐⭐ 与主对话/Agent 运行时的隔离边界（硬约束，用户 2026-11-05 裁定）</h2>
 * <ul>
 *   <li><b>共享</b>：{@code messages} 表、STOMP topic {@code /topic/sessions/{sid}/stream}、
 *       既有事件类（{@link MessageChunkEvent} / {@link MessageCompleteEvent} /
 *       {@link MessageErrorEvent}）⇒ 前端 {@code useChatSocket} 不需要为 RAG 轮写第二套解析</li>
 *   <li><b>⛔ 绝不经过</b>：{@code LlmAgentLoop} / {@code AgentState} /
 *       {@code SessionAgentStateRegistry} / 工具系统 / 权限 / skill</li>
 * </ul>
 *
 * <h2>为什么单独一个类而不是塞进 {@code ChatService}</h2>
 * {@code ChatService.processUserMessage} 有 4000+ 行、深度绑定 {@code AgentState}。
 * 把 RAG 塞进去等于让「问答链」依赖「编程智能体的状态机」——一旦 AgentLoop 改内部结构，
 * 知识库问答就跟着坏。这里是**并列的第二条执行路径**，只复用消息表与推流原语。
 *
 * <h2>停止语义</h2>
 * 语义对齐主对话的 {@code cancelSession}：置取消标志 → 执行器在下一个检查点退出。
 * 本类自己维护 {@code sessionId → 取消标志} 的表（⛔ 不注册进
 * {@code SessionAgentStateRegistry}，那会污染 Agent 运行时）。
 */
@Service
public class KbQaTurnService {

    private static final Logger log = LoggerFactory.getLogger(KbQaTurnService.class);

    /** 一轮问答的取消标志表（键 = sessionId；同一会话同一时刻只允许一轮）。 */
    private final ConcurrentHashMap<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired private KbQaPipeline pipeline;
    @Autowired private MessageService messageService;
    @Autowired(required = false) private SessionMapper sessionMapper;
    @Autowired(required = false) private SimpMessagingTemplate wsTemplate;
    @Autowired(required = false) private ChatService chatService;
    @Autowired(required = false)
    private com.nexusai.application.session.SessionArtifactService sessionArtifactService;
    /** 引用落库后回填真实 assistant id（见 reassignAssistantMessageId 的 WHY）。 */
    @Autowired(required = false) private KbCitationWriter citationWriter;

    /**
     * 一次受理结果。
     *
     * @param userMessageId      落库后的 user 消息 id（= 本轮轮键）
     * @param assistantMessageId 本轮 assistant 消息 id（前端按它归位流式块）
     * @param streamTopic        STOMP 订阅地址
     */
    public record Accepted(String userMessageId, String assistantMessageId, String streamTopic) {
    }

    /**
     * 受理一轮知识库问答（异步执行）。
     *
     * <p><b>顺序对齐源侧</b>：先落 user 消息（拿到 id）→ 推 user 事件建锚点 →
     * 再异步跑流水线。⛔ 不能先跑流水线再落库：那样前端收到的 chunk 没有锚点，
     * 会插到列表末尾造成顺序倒挂（主对话已踩过这个坑）。
     *
     * @param sessionId          会话 id
     * @param content            用户问题
     * @param req                额外参数（知识库范围/模型/联网开关）
     * @return 受理信息（HTTP 202 回它）
     * @throws com.nexusai.infra.exception.ValidationException 问题为空 / 会话不存在
     */
    public Accepted accept(String sessionId, String content, KbQaPipeline.Request req) {
        return accept(sessionId, content, req, null);
    }

    /**
     * 同 {@link #accept(String, String, KbQaPipeline.Request)}，额外把「本轮 @ 选中的知识库/文件」
     * 以附件快照落进 user 消息（{@code messages.user_attachments}）—— 前端 user 气泡直接复用
     * 现成的附件胶囊渲染，⛔ 不需要为 mention 新增列。
     */
    public Accepted accept(String sessionId, String content, KbQaPipeline.Request req,
                           java.util.List<com.nexusai.model.session.dto.AttachmentRequest> attachments) {
        if (content == null || content.isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("content 不能为空");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("sessionId 不能为空");
        }
        if (cancels.containsKey(sessionId)) {
            // 同一会话并发两轮会让两条流交错进同一 topic（前端无法拆分）
            throw new com.nexusai.infra.exception.ValidationException("该会话已有一轮问答在运行");
        }

        // ① 落 user 消息（拿 id = 本轮轮键）
        com.nexusai.model.session.dto.MessageCreatedResponse created;
        try {
            // SendMessageRequest 9 字段：content / modelName / useFastModel / attachments /
            //   appendSystemPrompt / fallbackModel / permissionMode / dangerouslySkipPermissions / jsonSchema
            created = messageService.createUserMessage(
                sessionId, new SendMessageRequest(content, req.modelName(), null, attachments,
                    null, null, null, null, null));
        } catch (com.nexusai.infra.exception.NotFoundException e) {
            throw new com.nexusai.infra.exception.ValidationException("会话不存在：" + sessionId);
        }
        String userMessageId = created.userMessageId();
        String assistantMessageId = created.assistantMessageId();
        String topic = "/topic/sessions/" + sessionId + "/stream";

        // ② 推 user 事件建锚点（前端 appendMetaUser 立 flow 锚点）
        if (wsTemplate != null) {
            StreamEventSeq.send(wsTemplate, topic,
                new MessageUserEvent(sessionId, userMessageId, userMessageId, content, false));
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancels.put(sessionId, cancelled);

        // ③ 产物归属：本轮看到的产物都记在这个 userMessageId 下
        if (sessionArtifactService != null) {
            try {
                sessionArtifactService.startTurnWatch(sessionId, userMessageId);
            } catch (RuntimeException e) {
                log.warn("[问答轮] 启动产物监听失败 session={} 原因={}", sessionId, e.getMessage());
            }
        }

        KbQaPipeline.Request runReq = new KbQaPipeline.Request(
            sessionId, userMessageId, assistantMessageId,
            content, req.knowledgeBaseIds(), req.knowledgeIds(), req.modelName(),
            req.webSearchEnabled(), cancelled);

        // ④ 异步执行（HTTP 立刻返回 202，内容走 STOMP）
        executor.submit(() -> runTurn(sessionId, topic, userMessageId, assistantMessageId, runReq));

        return new Accepted(userMessageId, assistantMessageId, topic);
    }

    /**
     * 中止某会话正在跑的一轮。
     *
     * @param sessionId 会话 id
     * @return true = 确实有一轮在跑并已置取消标志
     */
    public boolean cancel(String sessionId) {
        AtomicBoolean flag = sessionId == null ? null : cancels.get(sessionId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        log.info("[问答轮] 已请求停止 session={}", sessionId);
        return true;
    }

    /** 该会话是否有一轮在跑。 */
    public boolean isRunning(String sessionId) {
        return sessionId != null && cancels.containsKey(sessionId);
    }

    // ── 内部 ──────────────────────────────────────────────────────

    private void runTurn(String sessionId, String topic, String userMessageId,
                         String assistantMessageId, KbQaPipeline.Request req) {
        long startedAt = System.currentTimeMillis();
        KbQaPipeline.Result result;
        try {
            result = pipeline.run(req, wsTemplate);
        } catch (RuntimeException e) {
            // pipeline.run 自述「永不抛」；走到这里是意料之外 —— 仍然要给前端一个收口
            log.error("[问答轮] 执行异常 session={} 原因={}", sessionId, e.getMessage(), e);
            sendError(topic, sessionId, userMessageId, assistantMessageId, "问答执行失败：" + e.getMessage());
            finishTurn(sessionId, null);
            return;
        } finally {
            cancels.remove(sessionId);
        }

        String answer = result.answer() == null ? "" : result.answer();
        String finishReason = result.cancelled() ? "cancelled"
            : (result.failed() ? "error" : "stop");

        // 落 assistant 消息（用户停止 / 失败也要落，否则刷新后这一轮凭空消失）
        ChatMessageDto saved = null;
        try {
            saved = persistAssistant(sessionId, userMessageId, answer, finishReason, result.stageTools());
        } catch (RuntimeException e) {
            log.warn("[问答轮] 落 assistant 消息失败 session={} 原因={}", sessionId, e.getMessage());
        }
        String resolvedAssistantId = saved != null && saved.id() != null
            ? saved.id() : assistantMessageId;

        // 引用行的 assistant_message_id 是受理时的占位 id ⇒ 落库后回填真实 id，
        // 否则 GET /messages/{id}/citations 恒空（前端历史回放没有来源面板的根因）。
        if (citationWriter != null && assistantMessageId != null
            && !assistantMessageId.equals(resolvedAssistantId)) {
            citationWriter.reassignAssistantMessageId(sessionId, assistantMessageId, resolvedAssistantId);
        }

        // 收口事件：content 为全文（与主对话 complete 同款）。
        // 用 9 参重载：问答链不做工具轮，input/output tokens 与上下文快照暂不上报
        // （usage 由生成阶段单独记；⛔ 不伪造 0，null 表示「未采集」）。
        StreamEventSeq.send(wsTemplate, topic, new MessageCompleteEvent(
            sessionId, userMessageId, resolvedAssistantId, answer,
            result.reasoning(), finishReason,
            null, null, null));

        // 标题生成（与主对话同一入口，条件由 ChatService 自己判）
        maybeGenerateTitle(sessionId, userMessageId, answer);

        log.info("[问答轮] 完成 session={} 长度={} 意图={} 引用={} 上下文段数={} 耗时={}ms",
            sessionId, answer.length(), result.intent(),
            result.citationCount(), result.contextChunks(),
            System.currentTimeMillis() - startedAt);

        finishTurn(sessionId, userMessageId);
    }

    /** 落 assistant 消息行（返回落库后 DTO，失败 → null）。 */
    private ChatMessageDto persistAssistant(String sessionId, String userMessageId,
                                            String content, String finishReason,
                                            List<com.nexusai.model.session.dto.ToolCallDto> stageTools) {
        // 19 参构造器（与 KnowledgeQaService.generate 里构造 user 消息同款形状）：
        //   id/sessionId/role/author/content/reasoning/toolCalls/finishReason/
        //   inputTokens/outputTokens/time/createdAt/toolCallId/assistantMessageId/
        //   acceptFeedback/contentBlocks/imagePasteIds/structuredOutput/isMeta
        ChatMessageDto dto = new ChatMessageDto(
            null, sessionId, com.nexusai.model.session.dto.Role.assistant, "nexus",
            content, null, stageTools,
            com.nexusai.model.session.dto.FinishReason.parse(finishReason),
            null, null, null, null,
            null, null,
            null, null, null,
            null,
            false);
        // ⚠️ userMessageId 必须指向本轮 user 消息：前端按它把 assistant 归到这一轮
        ChatMessageDto withTurn = dto.withUserMessageId(userMessageId);
        return messageService.appendMessage(withTurn, OffsetDateTime.now());
    }

    /** 标题生成（失败静默：标题是增强，不影响回答）。 */
    private void maybeGenerateTitle(String sessionId, String userMessageId, String answer) {
        if (chatService == null || sessionMapper == null) {
            return;
        }
        try {
            SessionRecord session = sessionMapper.selectOneById(sessionId);
            if (session != null) {
                chatService.maybeGenerateTitle(session, userMessageId, answer, wsTemplate);
            }
        } catch (RuntimeException e) {
            log.warn("[问答轮] 标题生成失败 session={} 原因={}", sessionId, e.getMessage());
        }
    }

    /** 本轮收尾（产物监听关闭 + 取消标志清理）。 */
    private void finishTurn(String sessionId, String userMessageId) {
        if (sessionArtifactService != null) {
            try {
                sessionArtifactService.stopTurnWatch(sessionId);
            } catch (RuntimeException e) {
                log.warn("[问答轮] 关闭产物监听失败 session={} 原因={}", sessionId, e.getMessage());
            }
        }
        if (userMessageId != null) {
            log.debug("[问答轮] 收尾 session={} turn={}", sessionId, userMessageId);
        }
    }

    private void sendError(String topic, String sessionId, String userMessageId,
                           String assistantMessageId, String message) {
        StreamEventSeq.send(wsTemplate, topic,
            new MessageErrorEvent(sessionId, userMessageId, assistantMessageId,
                "knowledge_qa_failed", message));
    }

    /** 容器销毁时停掉虚拟线程池。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        List<String> running = List.copyOf(cancels.keySet());
        if (!running.isEmpty()) {
            log.warn("[问答轮] 关闭时有 {} 个会话的问答未结束: {}", running.size(), running);
        }
    }
}
