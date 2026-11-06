package com.nexusai.kb.api;

import com.nexusai.kb.app.qa.KbQaPipeline;
import com.nexusai.kb.app.qa.KbQaTurnService;
import com.nexusai.kb.app.qa.KnowledgeQaService;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.repo.entity.KbCitationRecord;
import com.nexusai.kb.app.qa.KbCitationWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库问答端点 · 复刻 WeKnora {@code POST /knowledge-chat/:session_id}
 * （源侧 {@code routes_chat.go:146}）。
 *
 * <h2>⚠️ 2026-11-05 起改走 {@link KbQaTurnService}</h2>
 * 此前本端点直接调 {@code KnowledgeQaService.ask}（只检索+生成+推流，**不落消息行**）⇒
 * 刷新后这一轮凭空消失，且与主对话的停止/标题/产物归属全部不通。
 *
 * <p>现在对齐源侧 {@code handler/session/qa.go:executeQA} 的结构：
 * <b>外壳</b>（落 user/assistant 消息、开流、收流、失败回滚、标题生成）由
 * {@link KbQaTurnService} 负责，<b>执行器</b>是 {@link KbQaPipeline}（12 阶段 RAG 链）。
 * ⛔ 不经过 {@code LlmAgentLoop}。
 *
 * <h2>为什么是 202 而不是 200</h2>
 * 生成是流式的：HTTP 立即返回，内容通过 STOMP {@code /topic/sessions/{sid}/stream} 推送。
 * 返回 200 + 空体会让前端以为「答完了但没内容」。
 */
@RestController
public class KnowledgeChatController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChatController.class);

    private final KbQaTurnService turnService;
    private final KnowledgeQaService qaService;
    private final KbCitationWriter citationWriter;

    public KnowledgeChatController(
        KbQaTurnService turnService,
        KnowledgeQaService qaService,
        KbCitationWriter citationWriter
    ) {
        this.turnService = turnService;
        this.qaService = qaService;
        this.citationWriter = citationWriter;
    }

    /**
     * 问答请求。
     *
     * @param content           用户问题（必填）
     * @param knowledgeBaseIds  检索范围（null/空 ⇒ 智能体范围 > 全部可检索库）
     * @param modelName         生成模型（null ⇒ 按设置解析）
     * @param webSearchEnabled  联网检索开关
     */
    public record AskRequest(
        String content, List<String> knowledgeBaseIds, String modelName,
        Boolean webSearchEnabled
    ) {
    }

    /**
     * 受理响应（与主对话 {@code MessageCreatedResponse} 同构的最小集）。
     *
     * @param userMessageId      本轮 user 消息 id（= 轮键）
     * @param assistantMessageId 本轮 assistant 消息 id
     * @param streamTopic        STOMP 订阅地址
     * @param accepted           true = 已受理并开始生成
     */
    public record QaAccepted(String userMessageId, String assistantMessageId,
                             String streamTopic, boolean accepted) {
    }

    /**
     * 提交一个知识库问答。
     *
     * @param sessionId 会话 ID
     * @param req       请求体
     * @return 202 + 受理信息；内容走 STOMP
     */
    @PostMapping({"/api/v1/sessions/{sessionId}/knowledge-chat",
                  "/api/v1/knowledge-chat/{sessionId}"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QaAccepted ask(@PathVariable String sessionId, @RequestBody AskRequest req) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("content 不能为空");
        }
        KbQaPipeline.Request pipelineReq = new KbQaPipeline.Request(
            sessionId, null, null, req.content(),
            req.knowledgeBaseIds(), req.modelName(),
            Boolean.TRUE.equals(req.webSearchEnabled()), new AtomicBoolean(false));
        KbQaTurnService.Accepted accepted = turnService.accept(sessionId, req.content(), pipelineReq);
        log.info("[知识问答] 受理 session={} userMsg={} assistantMsg={} 库数={} 问题长度={}",
            sessionId, accepted.userMessageId(), accepted.assistantMessageId(),
            req.knowledgeBaseIds() == null ? "全部" : req.knowledgeBaseIds().size(),
            req.content().length());
        return new QaAccepted(accepted.userMessageId(), accepted.assistantMessageId(),
            accepted.streamTopic(), true);
    }

    /**
     * 停止某会话正在跑的问答轮。
     *
     * <p>⚠️ 只停 RAG 轮：主对话的停止仍走 {@code POST /api/v1/sessions/{sid}/stop}
     * （那条路操作 {@code AgentState}）。两者互不影响。
     *
     * @param sessionId 会话 ID
     * @return 是否有轮次被停止
     */
    @PostMapping("/api/v1/knowledge-chat/{sessionId}/stop")
    public StopResponse stop(@PathVariable String sessionId) {
        boolean stopped = turnService.cancel(sessionId);
        log.info("[知识问答] 停止请求 session={} 命中={}", sessionId, stopped);
        return new StopResponse(stopped);
    }

    /** 停止结果。 */
    public record StopResponse(boolean stopped) {
    }

    /**
     * 只检索、不生成（源侧 {@code POST /knowledge-search}，无 session）。
     *
     * <p>⚠️ 与问答的区别：不生成、不落消息、不落引用、不推流 —— 直接返回命中片段。
     * 仍走改写 + 混合检索 + 智能体范围收窄，口径与问答链一致。
     *
     * @param req 请求体（{@code content} 必填；{@code sessionId} 可选）
     * @return 命中片段
     */
    @PostMapping("/api/v1/knowledge-search")
    public List<SearchHit> search(@RequestBody SearchRequest req) {
        if (req == null || req.content() == null || req.content().isBlank()) {
            // ⚠️ 用本仓统一的 ValidationException（→ 400）。⛔ 不要用 ResponseStatusException：
            //    GlobalExceptionHandler 不认它，会兜成 500（W12 真机实测）。
            throw new com.nexusai.infra.exception.ValidationException("content 不能为空");
        }
        KnowledgeQaService.Request qaReq = new KnowledgeQaService.Request(
            req.sessionId(), null, null, req.content(), req.knowledgeBaseIds(),
            null, req.modelName(), null, Boolean.TRUE.equals(req.webSearchEnabled()));
        List<KbHybridSearcher.Hit> hits = qaService.search(qaReq);
        log.info("[知识检索] 返回 {} 条 session={}", hits.size(), req.sessionId());
        return hits.stream().map(h -> new SearchHit(
            h.chunkId(), h.knowledgeId(), h.content(), h.contextHeader(), h.score())).toList();
    }

    /** 检索请求（{@code sessionId} 可选）。 */
    public record SearchRequest(
        String sessionId, String content, List<String> knowledgeBaseIds,
        String modelName, Boolean webSearchEnabled
    ) {
    }

    /** 检索命中（只出前端需要的字段，⛔ 不外泄向量/排名细节）。 */
    public record SearchHit(
        @com.fasterxml.jackson.annotation.JsonProperty("chunk_id") String chunkId,
        @com.fasterxml.jackson.annotation.JsonProperty("knowledge_id") String knowledgeId,
        String content,
        @com.fasterxml.jackson.annotation.JsonProperty("context_header") String contextHeader,
        double score
    ) {
    }

    /**
     * 取某条 assistant 消息的引用（前端渲染「来源」列表用）。
     *
     * @param messageId assistant 消息 ID
     * @return 引用列表（按编号升序）；无则空数组（⛔ 不返回 404 —— 没引用是正常状态）
     */
    @GetMapping("/api/v1/messages/{messageId}/citations")
    public List<KbCitationRecord> citations(@PathVariable String messageId) {
        return citationWriter.listByMessage(messageId);
    }
}
