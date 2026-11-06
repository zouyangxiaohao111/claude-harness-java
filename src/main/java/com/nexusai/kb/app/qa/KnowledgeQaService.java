package com.nexusai.kb.app.qa;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.app.retrieval.KbRerankStage;
import com.nexusai.kb.domain.KnowledgeBaseService;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.eventbus.ws.MessageChunkEvent;
import com.nexusai.eventbus.ws.MessageCompleteEvent;
import com.nexusai.eventbus.ws.MessageErrorEvent;
import com.nexusai.eventbus.ws.StreamEventSeq;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 知识库问答编排 · 复刻 WeKnora {@code KnowledgeQA} 的完整四阶段链。
 *
 * <h2>编排方式：⭐ 显式顺序代码，不是 Graph</h2>
 * 源侧是「{@code Plugin} 接口 + {@code next()} 回调」的手写中间件链。
 * 本类把它压成<b>一个方法里的四段顺序调用</b> —— 阶段之间是固定的先后关系，
 * 没有分支/回环，拆成 DAG 只会增加理解成本。
 *
 * <h2>四阶段</h2>
 * <ol>
 *   <li><b>改写 + 意图</b>（{@link KbQueryUnderstanding}）：消解指代、判定要不要检索；</li>
 *   <li><b>并行检索</b>（{@link KbSearchFanOut}）：多库/多查询扇出，单路失败不影响其它；</li>
 *   <li><b>重排 + 合并</b>（{@link KbRerankStage} → {@link KbMergeStage} → {@link KbContextBuilder}）
 *       ：精排 → 拼接相邻块 / 补上下文 / 去重 → 编号成文；</li>
 *   <li><b>流式生成</b>：调模型，逐 chunk 推 STOMP，完成后落引用。</li>
 * </ol>
 *
 * <h2>⭐⭐ 意图不检索时也要走生成</h2>
 * 问候 / 闲聊 / 追问这类问题不该查库，但<b>仍然要回答</b>。
 * 源侧同样是「跳过检索 → 直接生成」，而不是返回空。
 *
 * <h2>⭐ 没有资料时不能直接用通用知识回答</h2>
 * 用 {@link KbQaPrompts#SYSTEM_NO_CONTEXT} 明确要求模型说「没找到」。
 * 否则模型会用常识答得很流畅，用户误以为知识库里就是这么写的 ——
 * 这是知识库产品最致命的一类误导。
 *
 * <h2>⚠️ 本类不做消息落库</h2>
 * 落库（user/assistant 消息、会话计数）由调用方（ChatService 那条链）负责。
 * 本类只负责「检索 + 生成 + 推流 + 引用」。这样它可以在没有会话的情况下被单独测试。
 */
@Service
public class KnowledgeQaService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaService.class);

    /** 每路检索默认返回条数。 */
    private static final int DEFAULT_MATCH_COUNT = 10;

    /** 送进重排的候选上限（重排服务对文档数有上限）。 */
    private static final int MAX_RERANK_CANDIDATES = 30;

    /** 最终保留的段数上限（合并之后）。 */
    private static final int MAX_CONTEXT_CHUNKS = 12;

    /** 生成阶段等待上限（秒）。超时视为失败，避免请求永远挂着。 */
    private static final long GENERATE_TIMEOUT_SECONDS = 300;

    private final KbQueryUnderstanding queryUnderstanding;
    private final KbSearchFanOut searchFanOut;
    private final KbRerankStage rerankStage;
    private final KbMergeStage mergeStage;
    private final KbContextBuilder contextBuilder;
    private final KbCitationWriter citationWriter;
    private final KnowledgeBaseService knowledgeBaseService;
    /** 仅用于「默认对话模型名」（settings.mainModelName）；知识库域配置在 {@link #kbConfigService}。 */
    private final SettingsService settingsService;
    /**
     * 知识库域配置（{@code kb_config}）。
     *
     * <p>⭐ 2026-11-05：settings 的 8 个 {@code kb_*} 列已全部搬走，
     * 模型读取改为 {@link com.nexusai.kb.config.KbConfigService#readEmbeddingModelId()}
     * —— 未显式配置时会自动选用第一个启用的同类型模型。
     */
    private final com.nexusai.kb.config.KbConfigService kbConfigService;

    /**
     * 智能体扩展配置（{@code kb_agent}）· 批次 W10。
     *
     * <p>会话绑定了主线程智能体（{@code sessions.main_thread_agent}）时，
     * 用它上面的知识库范围收窄检索 —— 这是「智能体决定查哪些库」的唯一消费点。
     */
    @Autowired(required = false)
    private com.nexusai.kb.agent.KbAgentService kbAgentService;

    /** 读会话绑定哪个智能体（{@code main_thread_agent} 列）。 */
    @Autowired(required = false)
    private com.nexusai.repository.session.mapper.SessionMapper sessionMapper;

    @Autowired(required = false)
    private LlmProviderFactory providerFactory;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    public KnowledgeQaService(
        KbQueryUnderstanding queryUnderstanding,
        KbSearchFanOut searchFanOut,
        KbRerankStage rerankStage,
        KbMergeStage mergeStage,
        KbContextBuilder contextBuilder,
        KbCitationWriter citationWriter,
        KnowledgeBaseService knowledgeBaseService,
        SettingsService settingsService,
        com.nexusai.kb.config.KbConfigService kbConfigService
    ) {
        this.queryUnderstanding = queryUnderstanding;
        this.searchFanOut = searchFanOut;
        this.rerankStage = rerankStage;
        this.mergeStage = mergeStage;
        this.contextBuilder = contextBuilder;
        this.citationWriter = citationWriter;
        this.knowledgeBaseService = knowledgeBaseService;
        this.settingsService = settingsService;
        this.kbConfigService = kbConfigService;
    }

    /**
     * 一次知识库问答请求。
     *
     * @param sessionId         会话 ID
     * @param userMessageId     触发本轮的 user 消息 ID
     * @param assistantMessageId 本轮 assistant 消息 ID（前端按它归位）
     * @param question          用户原始问题
     * @param knowledgeBaseIds  要检索的知识库 ID（null/空 ⇒ 用全部可检索库）
     * @param history           最近若干轮对话（每条如 {@code 用户: ...}），可空
     * @param modelName         生成用模型名（null ⇒ 按设置解析）
     * @param rewriteModelName  改写用模型名（null ⇒ 复用生成模型）
     * @param webSearchEnabled  联网检索开关（当前未实现外部搜索，仅影响意图判定）
     */
    public record Request(
        String sessionId, String userMessageId, String assistantMessageId,
        String question, List<String> knowledgeBaseIds, List<String> history,
        String modelName, String rewriteModelName, boolean webSearchEnabled
    ) {
    }

    /**
     * 一次问答的执行结果。
     *
     * @param answer          生成的回答全文
     * @param intent          判定出的意图
     * @param retrieved       是否真的走了检索
     * @param contextChunks   进入上下文的数据段数
     * @param citationCount   落库的引用条数
     * @param degradedRewrite 改写阶段是否降级
     */
    public record Result(
        String answer, KbQueryIntent intent, boolean retrieved,
        int contextChunks, int citationCount, boolean degradedRewrite
    ) {
    }

    /**
     * 执行问答（同步等待生成完成；chunk 通过 STOMP 实时推送）。
     *
     * <p><b>永不抛异常</b>：任何阶段失败都转成 {@code message.error} 事件 + 返回失败结果。
     * 调用方（异步线程）不需要再兜一层。
     *
     * @param req 请求
     * @param ws  STOMP 模板（null ⇒ 不推送，只返回结果；便于测试）
     * @return 结果（失败时 {@code answer} 为错误说明）
     */
    public Result ask(Request req, SimpMessagingTemplate ws) {
        String sessionId = req == null ? null : req.sessionId();
        String userMessageId = req == null ? null : req.userMessageId();
        String assistantMessageId = req == null || req.assistantMessageId() == null
            ? "msg-" + UUID.randomUUID().toString().substring(0, 8)
            : req.assistantMessageId();
        String topic = "/topic/sessions/" + sessionId + "/stream";

        if (req == null || questionBlank(req.question())) {
            // 空问题不该走到这里（入口已校验），但防御一下：静默返回空回答比抛异常好排查
            log.warn("[知识问答] 空问题，直接返回 session={}", sessionId);
            sendError(ws, topic, sessionId, userMessageId, assistantMessageId, "问题不能为空");
            return new Result("问题不能为空", KbQueryIntent.KB_SEARCH, false, 0, 0, false);
        }
        String question = req.question().strip();

        // ── ① 改写 + 意图 ───────────────────────────────────────────
        KbQueryUnderstanding.Result understanding = rewrite(req, question);
        boolean needsRetrieval = understanding.needsRetrieval(req.webSearchEnabled());
        if (log.isInfoEnabled()) {
            log.info("[知识问答] ① 改写完成 session={} 意图={}({}) 要检索={} 改写降级={}",
                sessionId, understanding.intent().wire(), understanding.intent().label(),
                needsRetrieval, understanding.degraded());
        }

        // ── ② 检索（不需要则跳过）───────────────────────────────────
        List<KbHybridSearcher.Hit> hits = List.of();
        boolean retrieved = false;
        if (needsRetrieval) {
            List<String> queries = understanding.retrievalQueries();
            if (queries.isEmpty()) {
                log.warn("[知识问答] ② 意图要检索但无查询文本，跳过检索 session={}", sessionId);
            } else {
                hits = retrieve(req, queries);
                retrieved = true;
            }
        } else {
            log.info("[知识问答] ② 意图={} 不需要检索，直接生成 session={}",
                understanding.intent().wire(), sessionId);
        }

        // ── ③ 重排 + 合并 + 拼上下文 ───────────────────────────────
        KbContextBuilder.Rendered rendered = buildContext(question, hits, req);
        if (log.isInfoEnabled()) {
            log.info("[知识问答] ③ 上下文就绪 检索命中={} 入上下文={} session={}",
                hits.size(), rendered.candidateCount(), sessionId);
        }

        // ── ④ 流式生成 ─────────────────────────────────────────────
        String answer;
        try {
            answer = generate(req, understanding.rewriteQuery(), rendered, ws, topic,
                userMessageId, assistantMessageId);
        } catch (RuntimeException e) {
            log.error("[知识问答] ④ 生成失败 session={} 原因={}", sessionId, e.getMessage());
            sendError(ws, topic, sessionId, userMessageId, assistantMessageId,
                "生成回答失败：" + e.getMessage());
            return new Result("生成回答失败", understanding.intent(), retrieved,
                rendered.candidateCount(), 0, understanding.degraded());
        }

        // 引用落库（只在真的用了资料时写）
        int citationCount = 0;
        if (rendered.candidateCount() > 0) {
            citationCount = saveCitations(sessionId, assistantMessageId, rendered);
        }

        if (log.isInfoEnabled()) {
            log.info("[知识问答] 完成 session={} 回答长度={} 引用={} 上下文段数={}",
                sessionId, answer == null ? 0 : answer.length(), citationCount,
                rendered.candidateCount());
        }
        return new Result(answer, understanding.intent(), retrieved,
            rendered.candidateCount(), citationCount, understanding.degraded());
    }

    // ── ① 改写 ──────────────────────────────────────────────────────

    private KbQueryUnderstanding.Result rewrite(Request req, String question) {
        String model = req.rewriteModelName() != null && !req.rewriteModelName().isBlank()
            ? req.rewriteModelName() : req.modelName();
        ProviderConfig config = null;
        try {
            if (model != null && !model.isBlank() && modelConfigResolver != null) {
                ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(model);
                if (resolved != null) {
                    config = resolved.config();
                }
            }
        } catch (RuntimeException e) {
            log.warn("[知识问答] ① 改写模型解析失败，将降级为原问题 model={} 原因={}",
                model, e.getMessage());
        }
        if (config == null) {
            // ⚠️ 不是错误路径：没配模型时 KbQueryUnderstanding 会自己降级，这里只是不传 config
            log.info("[知识问答] ① 无可用改写模型，改用原问题 model={}", model);
        }
        return queryUnderstanding.understand(
            config, model, question, req.history(), false, false, "中文",
            KbQueryUnderstanding.usageFor(req.sessionId(), null));
    }

    // ── ② 检索 ─────────────────────────────────────────────────────

    /**
     * 只检索、不生成（源侧 {@code POST /knowledge-search}）。
     *
     * <p>⚠️ 与 {@link #ask} 的区别：不查会话历史、不落引用、不推流 ——
     * 调用方要的是「检索结果本身」（用于调试、外部集成、评测前的手动探查）。
     * 仍然走①改写（意图+扩展）与②检索，与问答链的口径一致。
     *
     * @param req 请求（sessionId 可为空；为空时不做智能体范围收窄）
     * @return 命中的片段（按分数降序）
     */
    public List<KbHybridSearcher.Hit> search(Request req) {
        if (req == null || questionBlank(req.question())) {
            return List.of();
        }
        KbQueryUnderstanding.Result understanding = rewrite(req, req.question());
        if (!understanding.needsRetrieval(req.webSearchEnabled())) {
            log.info("[知识检索] 意图={} 判定无需检索，返回空", understanding.intent().wire());
            return List.of();
        }
        List<String> queries = understanding.retrievalQueries();
        if (queries.isEmpty()) {
            // 意图要检索但没有可用查询文本 ⇒ 用原问题兜底（⛔ 不静默返回空）
            queries = List.of(req.question());
        }
        List<KbHybridSearcher.Hit> hits = retrieve(req, queries);
        log.info("[知识检索] 完成 session={} 查询数={} 命中={}",
            req.sessionId(), queries.size(), hits.size());
        return hits;
    }

    private List<KbHybridSearcher.Hit> retrieve(Request req, List<String> queries) {
        List<String> kbIds = req.knowledgeBaseIds();
        if (kbIds == null || kbIds.isEmpty()) {
            // ⭐ W10：会话绑定的智能体可以收窄检索范围（kb_agent.knowledge_bases）。
            //    优先级：请求显式指定 > 智能体配置 > 全部可检索库。
            List<String> agentScope = agentKnowledgeScope(req.sessionId());
            if (agentScope != null) {
                kbIds = agentScope;
                log.info("[知识问答] ② 使用智能体指定的知识库范围 库数={} session={}",
                    kbIds.size(), req.sessionId());
            } else {
                // ⚠️ 用户裁定「后期会对接权限，默认就知道知识库范围」⇒ 当前按全部可检索库
                kbIds = listSearchableKnowledgeBaseIds();
            }
        }
        if (kbIds.isEmpty()) {
            log.info("[知识问答] ② 没有可检索的知识库，跳过检索 session={}", req.sessionId());
            return List.of();
        }
        String embeddingModelId = resolveEmbeddingModelId();
        List<KbSearchFanOut.QueryPlan> plans = new ArrayList<>();
        for (String kbId : kbIds) {
            for (String q : queries) {
                plans.add(new KbSearchFanOut.QueryPlan(
                    kbId, q, DEFAULT_MATCH_COUNT, embeddingModelId));
            }
        }
        List<KbHybridSearcher.Hit> hits = searchFanOut.searchAll(plans, DEFAULT_MATCH_COUNT * 3);
        if (log.isInfoEnabled()) {
            log.info("[知识问答] ② 检索完成 库数={} 查询数={} 命中={} 向量模型={}",
                kbIds.size(), queries.size(), hits.size(),
                embeddingModelId == null ? "未配置" : embeddingModelId);
        }
        return hits;
    }

    /**
     * 会话绑定智能体的知识库范围。
     *
     * <p>返回语义（对齐源侧 {@code resolveKnowledgeBasesFromAgent}）：
     * <ul>
     *   <li>{@code null} = 不限制（没绑智能体 / 智能体没配扩展 / 配置为 all）</li>
     *   <li>空列表 = 明确不检索</li>
     *   <li>非空 = 白名单（已过滤掉不存在的库 id）</li>
     * </ul>
     *
     * @param sessionId 会话 id
     * @return 见上
     */
    private List<String> agentKnowledgeScope(String sessionId) {
        if (kbAgentService == null || sessionMapper == null
                || sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            com.nexusai.repository.session.entity.SessionRecord row =
                sessionMapper.selectOneById(sessionId);
            if (row == null || row.getMainThreadAgent() == null
                    || row.getMainThreadAgent().isBlank()) {
                return null;
            }
            String agentId = row.getMainThreadAgent().trim();
            List<String> scope = kbAgentService.resolveKnowledgeBaseIds(agentId);
            if (scope == null) {
                return null;
            }
            if (scope.isEmpty()) {
                return List.of();
            }
            // 过滤掉已删除的库：配置里留着旧 id 时，不要让检索层收到不存在的库。
            java.util.Set<String> existing;
            try {
                existing = new java.util.HashSet<>();
                knowledgeBaseService.list().forEach(kb -> existing.add(kb.id()));
            } catch (RuntimeException e) {
                // 列表查不出来 ⇒ 不擅自收窄（宁可多查，不可静默跳过）
                log.warn("[知识问答] ② 库列表查询失败，跳过白名单过滤 原因={}", e.getMessage());
                return scope;
            }
            List<String> filtered = scope.stream().filter(existing::contains).toList();
            if (filtered.size() != scope.size()) {
                log.warn("[知识问答] ② 智能体知识库白名单有 {} 个库已不存在，已忽略 session={} agent={}",
                    scope.size() - filtered.size(), sessionId, agentId);
            }
            return filtered;
        } catch (RuntimeException e) {
            // fail-open：读配置失败时按原范围（全部库）—— 宁可多查，不可静默不查。
            log.warn("[知识问答] ② 读智能体知识库范围失败，本轮按全部可检索库 原因={}", e.getMessage());
            return null;
        }
    }

    /** 全部可检索的知识库（当前 = 全部；权限接入后由权限收窄）。 */
    private List<String> listSearchableKnowledgeBaseIds() {
        try {
            List<String> ids = new ArrayList<>();
            knowledgeBaseService.list().forEach(kb -> ids.add(kb.id()));
            return ids;
        } catch (RuntimeException e) {
            log.warn("[知识问答] 知识库列表查询失败，本轮不检索 原因={}", e.getMessage());
            return List.of();
        }
    }

    private String resolveEmbeddingModelId() {
        try {
            return kbConfigService == null ? null : kbConfigService.readEmbeddingModelId();
        } catch (RuntimeException e) {
            log.warn("[知识问答] 向量模型配置读取失败，本轮只走关键词 原因={}", e.getMessage());
            return null;
        }
    }

    // ── ③ 重排 + 合并 + 上下文 ──────────────────────────────────────

    private KbContextBuilder.Rendered buildContext(
        String question, List<KbHybridSearcher.Hit> hits, Request req
    ) {
        if (hits == null || hits.isEmpty()) {
            return KbContextBuilder.Rendered.empty();
        }
        // 重排（未配模型/失败 ⇒ 原样回落）
        int rerankInput = Math.min(hits.size(), MAX_RERANK_CANDIDATES);
        List<KbHybridSearcher.Hit> reranked = hits;
        try {
            reranked = rerankStage.rerank(question, hits.subList(0, rerankInput),
                resolveRerankModelId(req), null);
        } catch (RuntimeException e) {
            // KbRerankStage 内部已兜四类降级；走到这里是意料之外，仍然不打断
            log.warn("[知识问答] ③ 重排异常，使用融合顺序 原因={}", e.getMessage());
        }
        List<KbMergeStage.MergedChunk> merged = mergeStage.merge(reranked, MAX_CONTEXT_CHUNKS);
        if (log.isInfoEnabled()) {
            log.info("[知识问答] ③ 合并 重排输入={} 合并输出={} session={}",
                rerankInput, merged.size(), req.sessionId());
        }
        return contextBuilder.build(merged);
    }

    private String resolveRerankModelId(Request req) {
        try {
            return kbConfigService == null ? null : kbConfigService.readRerankModelId();
        } catch (RuntimeException e) {
            log.warn("[知识问答] ③ 重排模型配置读取失败，不重排 原因={}", e.getMessage());
            return null;
        }
    }

    // ── ④ 生成 ─────────────────────────────────────────────────────

    /**
     * 调模型并逐 chunk 推 STOMP。
     *
     * <p><b>为什么用「完成回调 + CountDownLatch」而不是 {@code future.get(result)}</b>：
     * 本仓的流式 provider 是回调式（{@code onChunk} / {@code onComplete} / {@code onError}），
     * 没有返回一个带结果的 Future。用 latch 等待终结回调是最贴合现有契约的写法。
     *
     * @return 完整回答
     */
    private String generate(
        Request req, String rewrittenQuery, KbContextBuilder.Rendered rendered,
        SimpMessagingTemplate ws, String topic, String userMessageId, String assistantMessageId
    ) {
        String modelName = req.modelName() != null && !req.modelName().isBlank()
            ? req.modelName() : resolveDefaultModelName();
        ProviderConfig config = resolveProviderConfig(modelName);
        if (config == null || providerFactory == null) {
            throw new IllegalStateException("没有可用的生成模型（" + modelName + "）");
        }
        String providerType = ModelConfigResolver.resolveProviderType(modelConfigResolver, modelName);
        LlmProvider provider = providerFactory.getProvider(config, providerType);

        boolean hasContext = rendered.candidateCount() > 0;
        String systemPrompt = KbQaPrompts.systemPrompt(hasContext);
        String userMessage = KbQaPrompts.userMessage(
            rendered.text(), rewrittenQuery == null || rewrittenQuery.isBlank()
                ? req.question() : rewrittenQuery);

        // 19 参构造器：id / sessionId / role / author / content / reasoning / toolCalls /
        //   finishReason / inputTokens / outputTokens / time / createdAt / toolCallId /
        //   assistantMessageId / acceptFeedback / contentBlocks / imagePasteIds /
        //   structuredOutput / isMeta
        List<ChatMessageDto> messages = List.of(
            new ChatMessageDto(null, req.sessionId(),
                com.nexusai.model.session.dto.Role.user,
                "user", userMessage,
                null, null, null,
                null, null, null, null,
                null, null,
                null, null, null,
                null,
                false)
        );

        StringBuilder answer = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CompletableFuture<Void> done = new CompletableFuture<>();

        long startedAt = System.currentTimeMillis();
        /*
         * ⚠️ 参数顺序必须逐字对齐 LlmProvider.stream 的 blocks 重载：
         *   … querySource, onChunk, onAssistantMessage, onToolCallComplete, onReasoningChunk,
         *   onStreamingFallback, abortController, onError, onComplete, skipCacheWrite,
         *   scope, streamIdleControl
         * 这一串回调全是函数式接口，错位**不会编译失败**，只会静默跑到错误的回调里
         * （例如把 onError 传进 abortController 位 ⇒ 类型不符才报错；但 Runnable 之间互换就查不出来）。
         */
        com.nexusai.application.agent.loop.ModelRequest request =
            new com.nexusai.application.agent.loop.ModelRequest(
                config, modelName,
                List.of(new com.nexusai.application.agent.prompt.SystemPromptBlock(
                    systemPrompt, com.nexusai.application.agent.prompt.CacheScope.NULL)),
                "knowledge_qa",
                messages,
                null,                                  // tools：问答不用工具
                null,                                  // maxOutputTokensOverride：按模型配置
                null,                                  // taskBudget
                null,                                  // effortValue
                null,                                  // thinkingConfig：问答不开思考
                delta -> {
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    answer.append(delta);
                    // ⭐ 逐块推送，前端才是真流式（攒完再推等于非流式）
                    StreamEventSeq.send(ws, topic, MessageChunkEvent.of(
                        req.sessionId(), userMessageId, assistantMessageId, delta));
                },
                msg -> { /* 完整 assistant message 回调：问答不需要 */ },
                toolCall -> { /* 问答不产生工具调用 */ },
                reasoning -> { /* 问答关闭思考 */ },
                () -> log.debug("[知识问答] 流式降级为非流式 session={}", req.sessionId()),
                error::set,
                () -> {
                    if (completed.compareAndSet(false, true)) {
                        done.complete(null);
                    }
                },
                AbortController.NOOP,
                null,                                  // skipCacheWrite
                null,                                  // agentContext：本路径无 agent 归因
                null,                                  // usageAttribution：暂不记账（见下）
                null,                                  // usageRecorder
                com.nexusai.infra.llm.StreamIdleControl.NONE);

        com.nexusai.infra.llm.LlmInvocationGateway.stream(
            providerFactory, modelConfigResolver, request);

        try {
            done.get(GENERATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("生成超时（" + GENERATE_TIMEOUT_SECONDS + " 秒）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("生成被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("生成失败：" + e.getCause());
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get().getMessage());
        }

        String text = answer.toString();
        if (text.isBlank()) {
            // ⭐ 空回答不是「成功」：下游会显示一个空气泡，用户以为界面坏了
            log.warn("[知识问答] 模型返回空回答 session={} 模型={} 耗时={}ms",
                req.sessionId(), modelName, System.currentTimeMillis() - startedAt);
            text = "抱歉，本轮没有生成任何内容。请换一种问法，或缩小问题范围后重试。";
            StreamEventSeq.send(ws, topic, MessageChunkEvent.of(
                req.sessionId(), userMessageId, assistantMessageId, text));
        }
        StreamEventSeq.send(ws, topic, new MessageCompleteEvent(
            req.sessionId(), userMessageId, assistantMessageId, text, null,
            "stop", null, null, null));
        if (log.isInfoEnabled()) {
            log.info("[知识问答] ④ 生成完成 session={} 模型={} 长度={} 耗时={}ms",
                req.sessionId(), modelName, text.length(), System.currentTimeMillis() - startedAt);
        }
        return text;
    }

    private String resolveDefaultModelName() {
        // 与主对话同一套解析：settings.mainModelName
        try {
            SettingsService svc = settingsService;
            if (svc != null) {
                com.nexusai.model.settings.dto.SettingsDto dto = svc.get();
                if (dto != null && dto.mainModelName() != null && !dto.mainModelName().isBlank()) {
                    return dto.mainModelName();
                }
            }
        } catch (RuntimeException e) {
            log.warn("[知识问答] 主模型名解析失败，回落默认 原因={}", e.getMessage());
        }
        return "mock-fast";
    }

    private ProviderConfig resolveProviderConfig(String modelName) {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(modelName);
            return resolved == null ? null : resolved.config();
        } catch (RuntimeException e) {
            log.warn("[知识问答] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    // ── 引用 ────────────────────────────────────────────────────────

    /**
     * 引用落库。
     *
     * <p>⚠️ 直接用 {@link KbContextBuilder.Rendered#citations()} —— 编号在渲染时已定稿。
     * ⛔ 不要在这里重新排序或重新编号：那会让落库序号与提示词里的 {@code [n]} 错位，
     * 用户点引用会跳到别的段落，且不报错。
     */
    private int saveCitations(
        String sessionId, String assistantMessageId, KbContextBuilder.Rendered rendered
    ) {
        List<KbCitationWriter.Citation> rows = new ArrayList<>();
        for (KbContextBuilder.CitationRef ref : rendered.citations()) {
            rows.add(new KbCitationWriter.Citation(
                ref.refIndex(), ref.chunkId(), ref.knowledgeId(), ref.knowledgeBaseId(),
                ref.knowledgeTitle(), ref.content(), ref.chunkIndex()));
        }
        return citationWriter.saveAll(sessionId, assistantMessageId, rows);
    }

    // ── 事件 ────────────────────────────────────────────────────────

    private void sendError(SimpMessagingTemplate ws, String topic, String sessionId,
                           String userMessageId, String assistantMessageId, String message) {
        StreamEventSeq.send(ws, topic, new MessageErrorEvent(
            sessionId, userMessageId, assistantMessageId, "knowledge_qa_failed", message));
    }

    private static boolean questionBlank(String q) {
        return q == null || q.isBlank();
    }
}
