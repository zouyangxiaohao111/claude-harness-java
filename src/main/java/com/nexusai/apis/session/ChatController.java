package com.nexusai.apis.session;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.MessageCreatedResponse;
import com.nexusai.model.session.dto.PartialCompactRequest;
import com.nexusai.model.session.dto.PartialCompactResponse;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.application.chat.ChatService;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.compact.PartialCompactService;
import com.nexusai.application.agent.team.TeammateMessageFoldingChain;
import com.nexusai.application.agent.tasks.MainSessionBackgroundService;
import com.nexusai.application.agent.tasks.NotificationQueue;
import com.nexusai.application.agent.tasks.QueueEventPublisher;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.session.SessionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat REST 端点（路径在 /api/v1/sessions/{sessionId}/messages 之下）：
 * - GET    /api/v1/sessions/{sessionId}/messages                       → 200 list
 * - POST   /api/v1/sessions/{sessionId}/messages                       → 202 created (Phase 5: 真实异步流)
 * - DELETE /api/v1/sessions/{sessionId}/messages/{messageId}           → 204
 * - DELETE /api/v1/sessions/{sessionId}/messages/after/{messageId}     → 200 (gap28 对话裁剪：删 pivot 起全部 + 旋转 conversationId)
 * - POST   /api/v1/sessions/{sessionId}/cancel                         → 202 (Phase 5: 真取消 in-progress 流)
 * - POST   /api/v1/sessions/{sessionId}/partial-compact                → 200 (OD-14 D-1 partial 压缩)
 *
 * Phase 5 改动：
 * <ul>
 *   <li>POST /messages：调 MessageService.createUserMessage 拿到 userMessageId，
 *       再调 ChatService.processUserMessage 异步触发 LLM 流（HTTP 立刻返 202）</li>
 *   <li>POST /cancel：调 ChatService.cancelSession 标记取消 + 推 cancelled 事件</li>
 *   <li>POST /partial-compact：调 {@link PartialCompactService}（对齐 CC REPL.tsx:4918-4972
 *       onSummarize，REST 载体对齐 AwaySummaryController 模式）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired private MessageService messageService;
    @Autowired private ChatService chatService;
    @Autowired private SimpMessagingTemplate wsTemplate;
    @Autowired private PartialCompactService partialCompactService;
    @Autowired private MainSessionBackgroundService mainSessionBackgroundService;
    @Autowired private SessionService sessionService;
    /** [queue-first B4/B6] 队列 · /queue/pop 弹出可编辑排队命令 + send 前置 busy 判定。 */
    @Autowired private NotificationQueue notificationQueue;
    /** [queue-first B5] 队列出站事件 · pop 后 emitChanged（排队框刷新）。 */
    @Autowired private QueueEventPublisher queueEventPublisher;

    @GetMapping("/messages")
    public List<ChatMessageDto> list(@PathVariable String sessionId) {
        // [W8-04 / OPD-TP-07] Java 消息折叠链（对齐 CC Messages.tsx:520）：出站 transcript 组装点
        // 应用 collapseTeammateShutdowns —— 连续 in-process teammate shutdown task_status attachment
        // 折叠为 teammate_shutdown_batch（否则前端收到多条「Teammate @x shut down gracefully」洪泛）。
        return TeammateMessageFoldingChain.collapse(messageService.listBySession(sessionId));
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageCreatedResponse send(@PathVariable String sessionId,
                                       @Valid @RequestBody SendMessageRequest req) {
        // [P-26] fallback==main 校验落 HTTP 请求体层（同步 400）· CC main.tsx:1336-1340
        //   stderr + exit(1) 等价；ValidationException → GlobalExceptionHandler 400
        //   （createUserMessage 前校验，避免非法请求落库）
        chatService.validateFallbackModelDistinct(sessionId, req);
        // [queue-order-fix 方案A] busy 判定提前到落库前：turn 运行中再发 → **不落库** user 消息
        //   （预生成 pendingId 入队，等当前轮结束后由 CronIdleExecutor 落库 + 起新轮）——修复
        //   user 消息在 controller 同步落库而前一轮 assistant 消息轮结束才落库 → 插入到未落库
        //   assistant 前的 DB 顺序错位（前端 GET /messages 显示错乱）。对齐 CC enqueue 内存队列 →
        //   消费时 createUserMessage 落库。
        boolean busy = LlmAgentLoop.isSessionRunning(sessionId);
        if (busy) {
            // [P5-②] immediate local-jsx 命令 busy 优先 · 对齐 CC handlePromptSubmit.ts:239-252
            //   （queryGuard.isActive 优先语义）：命中且已注册命名 handler → 不 enqueueBusyPrompt，
            //   直接 dispatch 立即执行 + 推 message.user（web 无 TUI，显式推送；CC setToolJSX 展示）。
            //   未注册命名 handler → dispatchImmediateLocalJsx 返回 false（内部 log.warn fail loud）
            //   → 回落原 busy 排队（CC dequeue 后重走 handlePromptSubmit）。
            if (chatService.isImmediateLocalJsxCommand(req != null ? req.content() : null)) {
                // 同一 msgId 贯穿 dispatch 落库/推送 与 响应（前端按 message.user.id 幂等去重）
                String immediateMsgId = "msg-immediate-" + UUID.randomUUID().toString().substring(0, 8);
                if (chatService.dispatchImmediateLocalJsx(
                        sessionId, immediateMsgId, req != null ? req.content() : null, true, wsTemplate)) {
                    return new MessageCreatedResponse(immediateMsgId, "msg-stub-pending",
                        "/topic/sessions/" + sessionId + "/stream", false);
                }
            }
            String pendingId = "msg-queued-" + UUID.randomUUID().toString().substring(0, 8);
            chatService.enqueueBusyPrompt(sessionId, pendingId, req);
            if (log.isInfoEnabled()) {
                log.info("POST /messages busy → 入队等待（不落库）: session={} pendingId={}（queue-first，"
                        + "前端排队框，消费时落库）", sessionId, pendingId);
            }
            // queued=true：前端不乐观插入气泡，交给排队框；streamTopic 返会话级 topic（前端已在会话
            //   topic 单一订阅，排队消费后流式也推同一 topic，无需切换——原「streamTopic 空 + queue.drained
            //   携带新订阅地址」描述作废）。
            return new MessageCreatedResponse(pendingId, "msg-stub-pending",
                "/topic/sessions/" + sessionId + "/stream", true);
        }

        // 1. 持久化 user 消息（同步，仅空闲路径）
        MessageCreatedResponse resp = messageService.createUserMessage(sessionId, req);

        // 2. 异步触发 LLM 流（fire-and-forget — @Async 立刻返回）
        chatService.processUserMessage(sessionId, resp.userMessageId(), req, wsTemplate);

        // 3. HTTP 立刻返 202 · 客户端拿 assistantMessageId（这里 v1 仍为 "msg-stub-pending"，
        //    因为 ChatService 内部才生成真 ID；Phase 6 让 MessageService 也返回真实 ID 或
        //    ChatService 同步预算 assistantId 再回写 resp）。queued=false（空闲直接跑）。
        return new MessageCreatedResponse(resp.userMessageId(), resp.assistantMessageId(),
            resp.streamTopic(), false);
    }

    /**
     * [queue-first B4] 弹出可编辑排队命令（对齐 CC 排队条 Esc/↑ 拉回编辑）· 返回 {content} 填输入框。
     *
     * <p>从会话队列移除最旧一条 mode=prompt 命令并返回其 content（前端 popEditableQueuedCommand
     * 调用，当前端点曾 404）；无排队命令 → 空 Map（前端视为 null，无编辑内容）。
     */
    @PostMapping("/queue/pop")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> popQueuedCommand(@PathVariable String sessionId) {
        if (notificationQueue == null) {
            log.warn("ChatController: NotificationQueue 未注入, /queue/pop 返回空");
            return Map.of();
        }
        List<NotificationQueue.QueueItem> popped = notificationQueue.removeByFilter(
            cmd -> cmd.sessionId() != null && cmd.sessionId().equals(sessionId)
                && NotificationQueue.MODE_PROMPT.equals(cmd.mode()));
        if (popped.isEmpty()) {
            return Map.of();
        }
        // 只返回最旧一条的 content（拉回编辑）；已从队列移除
        if (queueEventPublisher != null) {
            queueEventPublisher.emitChanged(sessionId);
        }
        if (log.isInfoEnabled()) {
            log.info("POST /queue/pop: session={} 弹出排队命令 content前20字符={}",
                sessionId, abbreviate(popped.get(0).value(), 20));
        }
        return Map.of("content", popped.get(0).value());
    }

    /** 截断日志字符串（超长省略）· 对齐 ChatService.abbreviate 语义。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @DeleteMapping("/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String sessionId,
                       @PathVariable String messageId) {
        messageService.delete(messageId);
    }

    /**
     * 对话裁剪 · DELETE /api/v1/sessions/{sessionId}/messages/after/{messageId}（gap28）。
     *
     * <p><b>语义（对齐 CC rewindConversationTo，REPL.tsx:3661-3699）</b>: 删除 pivot 消息
     * （含）起全部后续消息（{@code setMessages(prev.slice(0, messageIndex))}，REPL.tsx:3671），
     * 并旋转 conversationId（{@code setConversationId(randomUUID())}，REPL.tsx:3673）——
     * 前端可 {@code setMessages} + 刷新 row key。响应结构复用 {@link PartialCompactResponse}
     * （messages + conversationId，对齐 CC REPL.tsx:4964/4971 同款）。
     *
     * <p><b>in-flight 并发处置（owner 拍板 · 默认 cancel-first）</b>: 先
     * {@code chatService.cancelSession(sessionId, wsTemplate)} 再 trim —— 对齐 CC
     * {@code messageActionCaps.edit} 先 {@code onCancel()} 再 rewind
     * （REPL.tsx:3777-3780「rewindConversationTo's setMessages races stream appends —
     * cancel first (idempotent)」），否则本 turn 流式追加会与裁剪竞态。
     *
     * <p><b>错误翻译</b>: pivot 不存在 → 404；session 不存在 → 404（MessageService.trimSessionAfter
     * NotFoundException，GlobalExceptionHandler 转 404）；首条 pivot → 200 空列表（全删）。
     *
     * @param sessionId 会话 ID（路径变量）
     * @param messageId 裁剪 pivot 消息 ID（含 pivot 起全部删除）
     * @return 200 裁剪后剩余消息列表 + 新 conversationId（前端 setMessages + row key 刷新）
     */
    @DeleteMapping("/messages/after/{messageId}")
    public PartialCompactResponse trimAfter(@PathVariable String sessionId,
                                            @PathVariable String messageId) {
        // cancel-first（幂等，对齐 CC REPL.tsx:3777-3780）——裁剪前取消 in-flight 流，
        // 防本 turn 消息追加与裁剪竞态。
        chatService.cancelSession(sessionId, wsTemplate);
        List<ChatMessageDto> kept = messageService.trimSessionAfter(sessionId, messageId);
        String newCid = UUID.randomUUID().toString();
        sessionService.updateConversationId(sessionId, newCid);
        if (log.isInfoEnabled()) {
            log.info("ChatController.trimAfter: session={} pivot={} 裁剪完成，剩余消息={}，"
                    + "conversationId 旋转={}（gap28 · CC REPL.tsx:3671/3673）",
                sessionId, messageId, kept.size(), newCid);
        }
        return new PartialCompactResponse(kept, newCid);
    }

    /**
     * Phase 5: 真取消 in-progress 流。
     * 标记 cancel flag 并立即推 message.cancelled + session.status=idle。
     */
    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void cancel(@PathVariable String sessionId) {
        chatService.cancelSession(sessionId, wsTemplate);
    }

    /**
     * 主会话后台化 · POST /api/v1/sessions/{sessionId}/background（OPD-TP-14）。
     *
     * <p><b>语义（对齐 CC REPL.tsx:2559 + LocalMainSessionTask.ts:338-479 startBackgroundSession）</b>：
     * 从当前会话消息派生一条<b>独立</b> LlmAgentLoop 查询（后台任务），不复用前台查询的
     * {@code /topic/sessions/{S}/stream}（会话级单 topic）—— 后台流式事件走任务级独立 topic
     * {@code /topic/tasks/{taskId}/stream}（w5-01 隔离设计 1），前台查询继续运行互不串流。
     *
     * <p>请求体可选（{@code SendMessageRequest}）：{@code content} 作为派生 user prompt /
     * 描述（缺省回落到会话最近用户消息）；{@code modelName} 指定后台模型。
     *
     * <p><b>session_backgrounded 事件（OPD-TP-13/15 · 待前端联调.md:14）</b>：后台化时向
     * {@code /topic/sessions/{id}} 推 {@code {sessionId, backgrounded:true}}，前端订阅该 topic
     * 后台化时更新 UI。发射时机对齐 CC {@code useSessionBackgrounding.ts:41-66} —— Ctrl+B 先翻转
     * isBackgrounded（UI 立即感知后台化），再触发 onBackgroundQuery 派生查询，故先推事件再启动查询。
     *
     * @param sessionId 会话 ID（路径变量）
     * @param req       可选请求体（null = 用会话历史派生）
     * @return 200 {@code {taskId}} · 后台查询在 runWithAgentContext 隔离下异步运行
     */
    @PostMapping("/background")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> background(@PathVariable String sessionId,
                                          @RequestBody(required = false) SendMessageRequest req) {
        String description = (req != null && req.content() != null && !req.content().isBlank())
            ? req.content() : "Background session";
        String modelName = (req != null && req.modelName() != null && !req.modelName().isBlank())
            ? req.modelName() : null;
        // 会话历史 → transcript Map（CC query({messages}) 的 bgMessages 等价）
        // [S1] 后台化 = 续聊加载历史通道 → listForResume（对齐 CC deserializeMessagesWithInterruptDetection：
        //   未配对 tool_use/孤立 thinking/纯空白 assistant 剥离 + 中断 turn "Continue" sentinel 注入，
        //   避免"有问无答"背景化后 LLM 上下文缺 assistant 响应）。DB 权威写入不变（listBySession 仍供 GET /messages）。
        List<Map<String, Object>> history = messageService.listForResume(sessionId).stream()
            .map(m -> Map.<String, Object>of("role", m.role() != null ? m.role().name() : "user",
                "content", m.content() != null ? m.content() : ""))
            .toList();
        // 主会话后台化 STOMP 事件（OPD-TP-13/15 · 待前端联调.md:14）：topic /topic/sessions/{id}，
        // 载荷 {sessionId, backgrounded:true}。先推事件（前端立即感知后台化，对齐 CC UI 先翻转），
        // 再启动后台查询（startBackgroundSession fire-and-forget 立即返回 taskId，查询在 chatExecutor 池
        // 异步运行，HTTP 线程不等待查询结束 —— 对齐 CC LocalMainSessionTask.ts:375/:478）。
        wsTemplate.convertAndSend("/topic/sessions/" + sessionId,
            Map.of("sessionId", sessionId, "backgrounded", true));
        log.info("主会话后台化事件已推送: sessionId={}", sessionId);

        String taskId = mainSessionBackgroundService.startBackgroundSession(
            sessionId, description, history, wsTemplate, req != null ? req.content() : null,
            modelName, null, null);
        return Map.of("taskId", taskId);
    }

    /**
     * partial 压缩 · POST /api/v1/sessions/{sessionId}/partial-compact（OD-14 D-1）。
     *
     * <p><b>语义（对齐 CC REPL.tsx:4918-4972 onSummarize）</b>: 前端消息选择器选中消息 +
     * direction（from/up_to）+ 可选 feedback → 后端剥离 boundary → messageId 定 pivot →
     * PartialCompactConversation 摘要 → direction-aware 重组 → 写回消息列表 + 新 conversationId。
     * REST 载体对齐 AwaySummaryController 模式（前端触发，后端同步执行）。
     *
     * <p><b>错误翻译</b>: messageId 不在剥离后 active 列表 → 404；nothing_to_summarize → 400；
     * 摘要生成失败 → 500。
     *
     * @param sessionId 会话 ID（路径变量）
     * @param req       请求体 { messageId, direction: from/up_to, feedback? }
     * @return 200 重组后消息列表 + 新 conversationId（前端 setMessages + setConversationId）
     */
    @PostMapping("/partial-compact")
    public PartialCompactResponse partialCompact(@PathVariable String sessionId,
                                                 @Valid @RequestBody PartialCompactRequest req) {
        return partialCompactService.partialCompact(sessionId, req);
    }
}
