package com.nexusai.kb.app.qa;

import com.nexusai.application.agent.loop.ModelRequest;
import com.nexusai.application.agent.prompt.CacheScope;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.domain.session.MessageService;
import com.nexusai.domain.settings.SettingsService;
import com.nexusai.eventbus.ws.MessageChunkEvent;
import com.nexusai.eventbus.ws.MessageCompleteEvent;
import com.nexusai.eventbus.ws.MessageErrorEvent;
import com.nexusai.eventbus.ws.StreamEventSeq;
import com.nexusai.infra.llm.LlmInvocationGateway;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.app.retrieval.KbRerankStage;
import com.nexusai.kb.config.KbConfigService;
import com.nexusai.kb.domain.KnowledgeBaseService;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.model.session.dto.ChatMessageDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 知识库问答流水线 · 逐个阶段对齐 WeKnora {@code chat_pipeline/**}（27 文件 / 12,880 行）。
 *
 * <h2>阶段清单（源侧 {@code session_knowledge_qa.go:221-233} 的动态装配）</h2>
 * <pre>
 * 0  LOAD_HISTORY          历史成对加载 + 去 think + 截断
 * 1  MEMORY_RECALL         长期记忆常驻块（不调模型）
 * 2  QUERY_UNDERSTAND      改写 + 意图 + 记忆背景 + 截断 JSON 抢救
 * 3  EXTRACT_ENTITY        图谱实体抽取（仅 extract_config 启用的库）
 * 4  CHUNK_SEARCH_PARALLEL 混合检索 + 联网 + 查询扩展 + 实体检索 + 阈值过滤
 * 5  CHUNK_RERANK          重排 + WIKI_BOOST + MEMORY_AFFINITY
 * 6  WEB_FETCH             抓网页正文替换 snippet
 * 7  CHUNK_MERGE           八步合并（含历史引用注入 / FAQ 答案填充）
 * 8  FILTER_TOP_K          父块槽位聚合 + 确定性排序 + 保图片
 * 9  DATA_ANALYSIS         DuckDB 装载表格问数
 * 10 INTO_CHAT_MESSAGE     模板 + FAQ 优先 + 引用编号
 * 11 CHAT_COMPLETION_STREAM 流式生成（incomplete→错误 / truncated 兜底）
 * 12 引用                  落库 + 流中推送
 * </pre>
 *
 * <h2>⚠️ 与 {@code KnowledgeQaService} 的关系</h2>
 * 后者是本条链的**第一版**（四段编排：改写 → 检索 → 重排合并 → 生成），
 * 保留给既有调用方与测试；本类是它的**逐阶段对齐版**。两者不并存于同一入口 ——
 * {@link KbQaTurnService} 只走本类。
 *
 * <h2>失败语义</h2>
 * {@link #run} <b>永不抛异常</b>：任何阶段失败都转成 {@code message.error} 事件 + 失败结果。
 * 单阶段失败的处理与该阶段在源侧一致（大多数是「跳过该阶段，继续」）。
 */
@Service
public class KbQaPipeline {

    private static final Logger log = LoggerFactory.getLogger(KbQaPipeline.class);

    /** 生成阶段等待上限（秒）。 */
    private static final long GENERATE_TIMEOUT_SECONDS = 300;

    private final KbQueryUnderstanding queryUnderstanding;
    private final KbSearchFanOut searchFanOut;
    private final KbRerankStage rerankStage;
    private final KbMergeStage mergeStage;
    private final KbContextBuilder contextBuilder;
    private final KbCitationWriter citationWriter;
    private final KnowledgeBaseService knowledgeBaseService;
    private final SettingsService settingsService;
    private final KbConfigService kbConfigService;
    private final MessageService messageService;

    /**
     * 文档兜底召回用（字段注入 · 单测可不装配）。
     *
     * <p>只在「用户显式 @ 了文档，但向量/关键词都没命中」时使用 —— 见 {@link #explicitDocFallback}。
     */
    @Autowired(required = false)
    private KbChunkMapper chunkMapper;

    @Autowired(required = false) private LlmProviderFactory providerFactory;
    @Autowired(required = false) private ModelConfigResolver modelConfigResolver;
    @Autowired(required = false) private KbMemoryRecallStage memoryRecallStage;
    @Autowired(required = false) private KbWebSearchPort webSearchPort;
    /** 阶段 3 实体抽取（未装配 ⇒ 跳过，不影响其它阶段）。 */
    @Autowired(required = false) private KbQaEntityExtract entityExtract;
    /** 阶段 4c 实体检索（未装配 ⇒ 跳过）。 */
    @Autowired(required = false) private KbQaEntitySearch entitySearch;
    /** 阶段 5c 记忆亲和（未装配 ⇒ 跳过）。 */
    @Autowired(required = false) private KbMemoryAffinityStage memoryAffinityStage;
    /** 阶段 5b wiki 加成（未装配 ⇒ 跳过）。 */
    @Autowired(required = false) private KbWikiBoostStage wikiBoostStage;
    /** wiki 页服务（5b 第二道门查「库内有没有 wiki 页」）。 */
    @Autowired(required = false)
    private com.nexusai.kb.app.wiki.KbWikiPageService wikiPageService;
    /** 阶段 7 补 · 历史引用注入。 */
    private final KbHistoryCitationStage historyCitationStage = new KbHistoryCitationStage();
    /** 阶段 8 · FILTER_TOP_K（未装配 ⇒ 跳过）。 */
    @Autowired(required = false) private KbFilterTopKStage filterTopKStage;
    /** 阶段 9 · DATA_ANALYSIS（未装配 ⇒ 跳过）。 */
    @Autowired(required = false) private KbDataAnalysisStage dataAnalysisStage;
    /** 记忆服务（读文档亲和次数；未装配 ⇒ 5c 跳过）。 */
    @Autowired(required = false)
    private com.nexusai.kb.app.memory.KbMemoryService memoryService;
    @Autowired(required = false)
    private com.nexusai.kb.agent.KbAgentService kbAgentService;
    @Autowired(required = false)
    private com.nexusai.repository.session.mapper.SessionMapper sessionMapper;
    @Autowired(required = false)
    private com.nexusai.application.session.SessionArtifactService sessionArtifactService;

    /** 问答链的 IO 并发池：联网检索/抓正文/块检索都是阻塞 IO，用虚拟线程承载。 */
    private final ExecutorService ioPool = Executors.newVirtualThreadPerTaskExecutor();

    public KbQaPipeline(
        KbQueryUnderstanding queryUnderstanding,
        KbSearchFanOut searchFanOut,
        KbRerankStage rerankStage,
        KbMergeStage mergeStage,
        KbContextBuilder contextBuilder,
        KbCitationWriter citationWriter,
        KnowledgeBaseService knowledgeBaseService,
        SettingsService settingsService,
        KbConfigService kbConfigService,
        MessageService messageService
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
        this.messageService = messageService;
    }

    /** 应用关闭时释放 IO 池（虚拟线程池不建线程，但不关会随上下文长期存活）。 */
    @PreDestroy
    void shutdownIoPool() {
        ioPool.shutdown();
    }

    /**
     * 一次问答请求。
     *
     * @param sessionId         会话 id
     * @param userMessageId     本轮 user 消息 id（= 轮键）
     * @param assistantMessageId 本轮 assistant 消息 id
     * @param question          用户原始问题
     * @param knowledgeBaseIds  检索范围（null/空 ⇒ 智能体范围 > 全部库）
     * @param knowledgeIds      文档级范围（@ 选的文件 · null/空 ⇒ 不限文档）。对齐源站
     *                          {@code knowledge_ids}（{@code qa.go:300 mergeKnowledgeTargets}：
     *                          {@code mentioned_items} 里 type=kb 并进 kbIds、type=file 并进本字段）
     * @param modelName         生成模型（null ⇒ settings.mainModelName）
     * @param webSearchEnabled  联网检索开关
     * @param cancelled         取消标志（调用方维护；本类在每个阶段边界检查）
     */
    public record Request(
        String sessionId, String userMessageId, String assistantMessageId,
        String question, List<String> knowledgeBaseIds, List<String> knowledgeIds, String modelName,
        boolean webSearchEnabled, AtomicBoolean cancelled
    ) {
    }

    /**
     * 一次问答的结果。
     *
     * @param answer        回答全文（失败时为错误说明）
     * @param reasoning     思考流全文（无思考 ⇒ null）
     * @param intent        判定出的意图（失败 ⇒ null）
     * @param retrieved     是否真的走了检索
     * @param contextChunks 进入上下文的段数
     * @param citationCount 落库的引用条数
     * @param cancelled     是否被用户停止
     * @param failed        是否失败（answer 里是错误说明）
     */
    public record Result(
        String answer, String reasoning, String intent, boolean retrieved,
        int contextChunks, int citationCount, boolean cancelled, boolean failed,
        /** [RAG 时间线] 本轮检索阶段工具（ragpipe- 前缀）；落库用，回放给模型时过滤 */
        List<com.nexusai.model.session.dto.ToolCallDto> stageTools
    ) {
    }

    /**
     * 执行一次问答（同步跑完整条流水线；chunk 通过 STOMP 实时推）。
     *
     * @param req 请求（null ⇒ 立即返回失败结果）
     * @param ws  STOMP 模板（null ⇒ 不推送，只返回结果；便于测试）
     * @return 结果（永不 null、永不抛）
     */
    public Result run(Request req, SimpMessagingTemplate ws) {
        if (req == null || req.question() == null || req.question().isBlank()) {
            return new Result("问题不能为空", null, null, false, 0, 0, false, true, null);
        }
        String sessionId = req.sessionId();
        String topic = "/topic/sessions/" + sessionId + "/stream";
        String question = req.question().strip();
        KbConfigService.QaParams params = kbConfigService.readQaParams();
        // 检索进度窗口（源站 `BeginRetrievalProgress` / `ShouldCloseRetrievalProgress`）：
        // 声明在 try 外 —— 检索段抛异常时 catch 里也要能把它关掉，否则前端那个
        // knowledge_search 工具卡会一直转圈（源站就是为这个专门加了 stageErr 分支）。
        KbQaProgress.Stage retrievalStage = null;
        // [RAG 时间线] 本轮检索阶段工具（对齐源站 ragpipe- 前缀）；随 assistant 消息落库，
        //   刷新后前端据此重画时间线。⛔ 回放给模型的历史上下文里必须过滤掉（模型没发过这些调用）。
        List<com.nexusai.model.session.dto.ToolCallDto> stageTools = new java.util.ArrayList<>();

        try {
            // ── 阶段 0：历史 ──────────────────────────────────────
            List<String> history = loadHistory(sessionId, params.maxRounds());
            log.info("[问答链] ⓪ 历史 session={} 轮数={}", sessionId, history.size() / 2);

            if (isCancelled(req)) {
                return cancelledResult("已停止");
            }

            // ── 阶段 1：记忆召回 ──────────────────────────────────
            String memoryPrompt = recallMemory();
            if (!memoryPrompt.isEmpty()) {
                log.info("[问答链] ① 记忆注入 session={} 长度={}", sessionId, memoryPrompt.length());
            }
            // 改写用的背景与答案用的常驻块是两份不同粒度（见 KbMemoryService.rewriteBackground）
            String memoryBackground = recallBackground();

            // ── 阶段 2：改写 + 意图 ───────────────────────────────
            // 进度窗口 · 对齐源站 `ShouldEmitQueryUnderstandProgress`（`progress.go:31-36`）：
            // 改写开关打开才发；本仓问答链不接受图片输入，故没有 `Images > 0` 那半个条件。
            KbQaProgress.Stage understandStage = params.enableRewrite()
                ? KbQaProgress.beginQueryUnderstand(ws, topic, sessionId,
                    req.userMessageId(), req.assistantMessageId(), question)
                : null;
            KbQueryUnderstanding.Result understanding =
                understand(req, question, history, params, memoryBackground);
            // 降级（解析不出改写模型 ⇒ 用原问题）如实报失败：源站那一格是 stageErr，
            // ⛔ 不能显示「已完成问题理解」而实际没跑模型（显式失败，别掩盖）。
            KbQaProgress.endQueryUnderstand(understandStage, !understanding.degraded(),
                understanding.degraded() ? "未使用改写模型，已按原问题检索" : null);
            // [RAG 时间线] 问题理解这一格落库（只在真发过进度事件时收；ragpipe- 前缀 = 模型没发过的调用）
            if (understandStage != null) {
                stageTools.add(new com.nexusai.model.session.dto.ToolCallDto(
                    "ragpipe-" + understandStage.toolCallId(), understandStage.toolName(),
                    "{\"query\":\"" + jsonEscape(question) + "\"}",
                    understanding.degraded() ? "未使用改写模型，已按原问题检索"
                        : "改写为：" + understanding.rewriteQuery(),
                    understanding.degraded()));
            }
            boolean needsRetrieval = understanding.needsRetrieval(req.webSearchEnabled());
            log.info("[问答链] ② 改写完成 session={} 意图={}({}) 要检索={} 降级={}",
                sessionId, understanding.intent().wire(), understanding.intent().label(),
                needsRetrieval, understanding.degraded());

            if (isCancelled(req)) {
                return cancelledResult("已停止");
            }

            // ── 阶段 3：实体抽取（源侧 extract_entity，由改写触发）──────
            // ⚠️ 只对「开启图谱」的库做；全关 ⇒ 一次模型都不调（见 KbQaEntityExtract 类注释）
            KbQaEntityExtract.Outcome entityOutcome = KbQaEntityExtract.Outcome.empty();
            // 检索范围提到 if 外：下面算 search_source 要用（源站 `retrievalSearchSource`）
            List<String> scope = needsRetrieval ? resolveKbScope(req) : List.of();
            if (needsRetrieval) {
                String extractQuery = understanding.rewriteQuery() == null
                    || understanding.rewriteQuery().isBlank()
                    ? question : understanding.rewriteQuery();
                entityOutcome = extractEntities(extractQuery, scope, req, params);
            }

            // ── 阶段 4 + 4a/6 + 4c：块检索 / 联网+抓正文 / 实体检索 并发 ────
            // 三段互不依赖，源侧同样是并发发起；串行跑等于把三段网络延迟相加。
            List<String> queries = needsRetrieval ? understanding.retrievalQueries() : List.of();
            boolean willRetrieve = needsRetrieval && !queries.isEmpty();
            if (needsRetrieval && queries.isEmpty()) {
                log.warn("[问答链] ④ 意图要检索但无查询文本，跳过检索 session={}", sessionId);
            }
            final KbQaEntityExtract.Outcome entities = entityOutcome;
            // 检索窗口 · 对齐源站 `BeginRetrievalProgress`（`progress.go:79-106`）：
            // 在「搜索 → 重排 → 合并」整段之前发一条 tool_call，前端据此显示一个工具卡。
            // `search_source` 用**检索前已知的配置**判定（源站 `retrievalSearchSource`）。
            String retrievalQuery = understanding.rewriteQuery() == null
                || understanding.rewriteQuery().isBlank()
                ? question : understanding.rewriteQuery();
            retrievalStage = needsRetrieval
                ? KbQaProgress.beginRetrieval(ws, topic, sessionId, req.userMessageId(),
                    req.assistantMessageId(), retrievalQuery,
                    retrievalSearchSource(scope, req.webSearchEnabled()))
                : null;
            CompletableFuture<List<KbHybridSearcher.Hit>> hitsFuture = willRetrieve
                ? CompletableFuture.supplyAsync(() -> retrieve(req, queries, params), ioPool)
                : CompletableFuture.completedFuture(List.<KbHybridSearcher.Hit>of());
            CompletableFuture<List<KbContextBuilder.ExternalSegment>> webFuture =
                CompletableFuture.supplyAsync(
                    () -> webSearch(req, understanding.rewriteQuery(), params), ioPool);
            CompletableFuture<List<KbHybridSearcher.Hit>> entityHitFuture =
                CompletableFuture.supplyAsync(
                    () -> searchEntities(entities, params), ioPool);

            // ⚠️ 三段都要 join 完再判失败：任何一段抛 ⇒ 整轮失败（与串行版一致）；
            //   检索那条优先，因为串行版里它就是先跑的那段，错误文案不能换。
            RuntimeException failure = null;
            List<KbHybridSearcher.Hit> hits = List.of();
            try {
                hits = await(hitsFuture);
            } catch (RuntimeException e) {
                failure = e;
            }
            boolean retrieved = willRetrieve;
            List<KbContextBuilder.ExternalSegment> webSegments = List.of();
            try {
                webSegments = await(webFuture);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
            List<KbHybridSearcher.Hit> entityHits = List.of();
            try {
                entityHits = await(entityHitFuture);
            } catch (RuntimeException e) {
                // ⭐ 实体检索是增强：这一路挂了不该让整轮失败（源侧同样只记日志）
                log.warn("[问答链] ④c 实体检索异常，本轮忽略该路 原因={}", e.getMessage());
            }
            if (failure != null) {
                throw failure;
            }
            if (!entityHits.isEmpty()) {
                // ⚠️ 合并：实体路 score=0，与块路同 chunkId 时保留高分那条
                hits = mergeHits(hits, entityHits);
                log.info("[问答链] ④c 实体路并入 {} 条，合并后 {} 条", entityHits.size(), hits.size());
            }

            // ── 阶段 7 补：历史引用注入（源侧 injectHistoryResults）──────
            if (!hits.isEmpty()) {
                List<KbHistoryCitationStage.HistoryCitation> historyRefs =
                    loadHistoryCitations(sessionId);
                if (!historyRefs.isEmpty()) {
                    hits = historyCitationStage.inject(hits, historyRefs);
                }
            }

            if (isCancelled(req)) {
                return cancelledResult("已停止");
            }

            // ── 阶段 5 + 7 + 10：重排 → 合并 → 拼上下文 ────────────
            KbContextBuilder.Rendered rendered = buildContext(
                understanding.rewriteQuery(), hits, params, req, webSegments);
            log.info("[问答链] ⑩ 上下文就绪 session={} 命中={} 入上下文={}",
                sessionId, hits.size(), rendered.candidateCount());

            // ⭐ count 必须是「真正进了上下文的条数」而不是召回条数（源站 `progress.go:185-199`）：
            //   召回了 N 条但阈值把它们全筛掉时，答案里一条引用都不会有 —— 这时报 N 就是骗人，
            //   改报「命中 N 条候选，相关性不足，未用于回答」。
            KbQaProgress.endRetrieval(retrievalStage,
                !hits.isEmpty() && rendered.candidateCount() == 0,
                rendered.candidateCount(), hits.size(), null);
            // [RAG 时间线] 检索这一格落库（result 文案带命中数，供刷新后重画）
            if (retrievalStage != null) {
                stageTools.add(new com.nexusai.model.session.dto.ToolCallDto(
                    "ragpipe-" + retrievalStage.toolCallId(), retrievalStage.toolName(),
                    "{\"query\":\"" + jsonEscape(retrievalQuery) + "\"}",
                    rendered.candidateCount() > 0
                        ? "找到 " + rendered.candidateCount() + " 个结果"
                        : "未找到相关内容",
                    false));
            }

            // ── 阶段 12 补：**生成前**推一次引用（源侧 references 事件同位置）──
            //   ⚠️ 必须在这里，不能等生成完：引用只依赖检索结果，生成还没开始就已经确定；
            //   放到 complete 之后推，前端会在「回答流完」到「引用出现」之间有一段空白。
            pushCitations(req, ws, topic, rendered, webSegments);

            // ── 阶段 11：流式生成 ─────────────────────────────────
            Gen gen = generate(req, understanding.rewriteQuery(), rendered, memoryPrompt,
                understanding.intent(), ws, topic, params);

            // ── 阶段 12：引用落库 ─────────────────────────────────
            int citationCount = 0;
            if (rendered.candidateCount() > 0) {
                citationCount = saveCitations(sessionId, req.assistantMessageId(), rendered);
            }
            // ── 阶段 5c 的写入侧：把本轮引用过的文档记进亲和表 ──────
            //   ⚠️ 此前 `KbMemoryService.recordAnswerSources` **全仓零调用** ⇒
            //   `kb_memory_doc_affinity` 永远是空表 ⇒ 阶段 5c 每轮都打
            //   「提升=0/19 无历史使用记录」—— 读侧写好了、写侧没人调，
            //   与缺陷 27/33 同一形态（2026-11-06 真机 3499 抓到）。
            recordDocAffinity(rendered);
            // 网页没有 chunk 可落 ⇒ 走「来源」通道（源侧 KnowledgeReferences 里
            //   web 结果也是按来源记的，不是按块引用）
            recordWebSources(req, webSegments);

            return new Result(gen.answer(), gen.reasoning(), understanding.intent().wire(),
                retrieved, rendered.candidateCount(), citationCount, false, gen.failed(), stageTools);

        } catch (CancelledRunException ce) {
            log.info("[问答链] 已按用户停止收口 session={}", sessionId);
            KbQaProgress.endRetrieval(retrievalStage, false, 0, 0, "已停止");
            return cancelledResult("已停止");
        } catch (RuntimeException e) {
            log.error("[问答链] 执行失败 session={} 原因={}", sessionId, e.getMessage(), e);
            // 检索窗口还开着（检索段抛异常）⇒ 必须关掉，否则前端工具卡一直转圈。
            // 已正常收口过的窗口在这里是空操作（KbQaProgress 按 toolCallId 只发一次）。
            KbQaProgress.endRetrieval(retrievalStage, false, 0, 0, e.getMessage());
            StreamEventSeq.send(ws, topic, new MessageErrorEvent(
                sessionId, req.userMessageId(), req.assistantMessageId(),
                "knowledge_qa_failed", "问答执行失败：" + e.getMessage()));
            return new Result("问答执行失败：" + e.getMessage(), null, null, false, 0, 0, false, true, null);
        }
    }

    // ── 阶段 0：历史 ────────────────────────────────────────────────

    /**
     * 加载对话历史（对齐源侧 {@code common.go:132-190}）。
     *
     * <p>源侧规则：按 {@code RequestID} 成对 → 只留 Query/Answer 都非空 →
     * Answer 去 {@code <think>} → 倒序截 {@code maxRounds} → 反转成时间正序。
     * 本仓用 {@code userMessageId} 作为轮键（与前端 {@code turnKeyOf} 同源）。
     *
     * @param maxRounds 最大轮数（0 = 显式关闭多轮 ⇒ 返回空）
     * @return 扁平化的历史行（每行形如 {@code 用户: ...} / {@code 助手: ...}）
     */
    private List<String> loadHistory(String sessionId, int maxRounds) {
        if (maxRounds <= 0 || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<ChatMessageDto> all;
        try {
            all = messageService.listForModel(sessionId);
        } catch (RuntimeException e) {
            log.warn("[问答链] ⓪ 读历史失败，本轮无历史 session={} 原因={}", sessionId, e.getMessage());
            return List.of();
        }
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        // 按轮键成对（userMessageId = 该轮 user 消息 id）
        java.util.LinkedHashMap<String, String[]> byTurn = new java.util.LinkedHashMap<>();
        for (ChatMessageDto m : all) {
            if (m == null || m.role() == null) {
                continue;
            }
            String turnKey = m.userMessageId();
            if (turnKey == null || turnKey.isBlank()) {
                continue;
            }
            String[] pair = byTurn.computeIfAbsent(turnKey, k -> new String[2]);
            if (m.role() == com.nexusai.model.session.dto.Role.user) {
                // 只取真实提问：注入行（isMeta）与工具回执不算
                if (m.isMeta()) {
                    continue;
                }
                pair[0] = m.content();
            } else if (m.role() == com.nexusai.model.session.dto.Role.assistant) {
                pair[1] = stripThink(m.content());
            }
        }
        // 只留成对且都非空
        List<String[]> pairs = new ArrayList<>();
        for (String[] p : byTurn.values()) {
            if (p[0] != null && !p[0].isBlank() && p[1] != null && !p[1].isBlank()) {
                pairs.add(p);
            }
        }
        if (pairs.isEmpty()) {
            return List.of();
        }
        // 倒序取最近 maxRounds 条 → 反转回时间正序
        int from = Math.max(0, pairs.size() - maxRounds);
        List<String> out = new ArrayList<>();
        for (int i = from; i < pairs.size(); i++) {
            String[] p = pairs.get(i);
            out.add("用户: " + p[0].trim());
            out.add("助手: " + p[1].trim());
        }
        return out;
    }

    /** 去掉 {@code <think>…</think>}（对齐源侧 {@code regThinkTags}）。 */
    private static String stripThink(String content) {
        if (content == null) {
            return null;
        }
        return content.replaceAll("(?is)<think>.*?</think>", "").trim();
    }

    // ── 阶段 1：记忆召回 ────────────────────────────────────────────

    /**
     * 读长期记忆的常驻块（对齐源侧 {@code memory_recall.go}：不调模型）。
     *
     * @return 注入系统提示词的文本；无记忆 / 未装配 → 空串
     */
    private String recallMemory() {
        if (memoryRecallStage == null) {
            return "";
        }
        try {
            return memoryRecallStage.recallBlock();
        } catch (RuntimeException e) {
            // 记忆是增强：读失败不该让整轮失败（源侧同样只记 WARN）
            log.warn("[问答链] ① 记忆召回失败，本轮不注入 原因={}", e.getMessage());
            return "";
        }
    }

    // ── 阶段 2：改写 ────────────────────────────────────────────────

    /** 读改写用的提问者背景（失败 → 空串）。 */
    private String recallBackground() {
        if (memoryRecallStage == null) {
            return "";
        }
        try {
            return memoryRecallStage.rewriteBackground();
        } catch (RuntimeException e) {
            log.warn("[问答链] ① 读改写背景失败，本轮不加背景 原因={}", e.getMessage());
            return "";
        }
    }

    private KbQueryUnderstanding.Result understand(
        Request req, String question, List<String> history, KbConfigService.QaParams params,
        String memoryBackground
    ) {
        String model = req.modelName();
        ProviderConfig config = null;
        try {
            if (model != null && !model.isBlank() && modelConfigResolver != null) {
                ModelConfigResolver.ResolvedModel resolved = modelConfigResolver.resolve(model);
                if (resolved != null) {
                    config = resolved.config();
                }
            }
        } catch (RuntimeException e) {
            log.warn("[问答链] ② 改写模型解析失败，将降级为原问题 model={} 原因={}",
                model, e.getMessage());
        }
        if (config == null) {
            log.info("[问答链] ② 无可用改写模型，改用原问题 model={}", model);
        }
        if (!params.enableRewrite()) {
            // 关闭改写：仍要判意图（否则整轮不检索）。源侧关闭改写时直接跳过本阶段，
            // 但本仓没有「不判意图」的安全默认 ⇒ 保持 KB_SEARCH（fail-open 到检索）。
            log.info("[问答链] ② 改写已关闭，按 kb_search 处理 session={}", req.sessionId());
            return new KbQueryUnderstanding.Result(question, KbQueryIntent.KB_SEARCH, "", false);
        }
        return queryUnderstanding.understand(
            config, model, question, history, false, false, "中文",
            KbQueryUnderstanding.usageFor(req.sessionId(), null), memoryBackground);
    }

    /**
     * 阶段 3 执行（失败 ⇒ 空结果，⛔ 不阻断整轮）。
     *
     * @param question 用于抽取的问题（已改写）
     * @param scope    本轮检索范围
     */
    private KbQaEntityExtract.Outcome extractEntities(
        String question, List<String> scope, Request req, KbConfigService.QaParams params
    ) {
        if (entityExtract == null || scope == null || scope.isEmpty()) {
            return KbQaEntityExtract.Outcome.empty();
        }
        try {
            // 用「生成模型」抽实体：本仓没有单独的抽取模型配置，复用生成的模型最省事
            String model = req.modelName() != null && !req.modelName().isBlank()
                ? req.modelName() : resolveDefaultModelName();
            return entityExtract.extract(question, scope, model);
        } catch (RuntimeException e) {
            log.warn("[问答链] ③ 实体抽取异常，本轮跳过 原因={}", e.getMessage());
            return KbQaEntityExtract.Outcome.empty();
        }
    }

    /**
     * 阶段 4c 执行（失败 ⇒ 空结果）。
     *
     * @param outcome 阶段 3 的产出
     */
    private List<KbHybridSearcher.Hit> searchEntities(
        KbQaEntityExtract.Outcome outcome, KbConfigService.QaParams params
    ) {
        if (entitySearch == null || outcome == null || outcome.isEmpty()) {
            return List.of();
        }
        try {
            List<KbQaEntitySearch.EntityHit> got =
                entitySearch.search(outcome.entitiesByKb(), params.rerankTopK());
            return KbQaEntitySearch.toHits(got);
        } catch (RuntimeException e) {
            log.warn("[问答链] ④c 实体检索失败，本轮忽略该路 原因={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析本轮检索范围（**唯一一处**，阶段 3/4/4c 共用，⛔ 不得各写一份）。
     *
     * <p>优先级：请求显式指定 &gt; 会话绑定智能体扩展 &gt; 全部可检索库。
     *
     * @return 知识库 id 列表（永不为 null；空 = 本轮不检索）
     */
    private List<String> resolveKbScope(Request req) {
        List<String> kbIds = req.knowledgeBaseIds();
        if (kbIds != null && !kbIds.isEmpty()) {
            return kbIds;
        }
        List<String> agentScope = agentKnowledgeScope(req.sessionId());
        if (agentScope != null) {
            log.info("[问答链] 使用智能体指定的知识库范围 库数={} session={}",
                agentScope.size(), req.sessionId());
            return agentScope;
        }
        return listSearchableKnowledgeBaseIds();
    }

    /**
     * 检索来源标签 · 对齐源站 {@code retrievalSearchSource}（{@code progress.go:262-273}）。
     *
     * <p>判定依据是<b>检索前已知的配置</b>，不是检索结果 —— 源站在 begin 阶段就要把它塞进
     * tool_call 的 arguments 里，那时结果还不存在。
     *
     * @param scope            本轮检索范围（空 = 没有知识库目标）
     * @param webSearchEnabled 是否开联网
     * @return {@code knowledge} / {@code web} / {@code mixed}
     */
    private static String retrievalSearchSource(List<String> scope, boolean webSearchEnabled) {
        boolean hasKb = scope != null && !scope.isEmpty();
        if (hasKb && webSearchEnabled) {
            return KbQaProgress.SOURCE_MIXED;
        }
        if (webSearchEnabled) {
            return KbQaProgress.SOURCE_WEB;
        }
        return KbQaProgress.SOURCE_KNOWLEDGE;
    }

    // ── 阶段 4：检索 ────────────────────────────────────────────────

    private List<KbHybridSearcher.Hit> retrieve(
        Request req, List<String> queries, KbConfigService.QaParams params
    ) {
        List<String> kbIds = resolveKbScope(req);
        if (kbIds.isEmpty()) {
            log.info("[问答链] ④ 没有可检索的知识库，跳过检索 session={}", req.sessionId());
            return List.of();
        }
        String embeddingModelId = resolveEmbeddingModelId();
        List<KbSearchFanOut.ScopedPlan> plans = new ArrayList<>();
        for (String kbId : kbIds) {
            for (String q : queries) {
                plans.add(new KbSearchFanOut.ScopedPlan(
                    new KbSearchFanOut.QueryPlan(kbId, q, params.embeddingTopK(), embeddingModelId),
                    params.vectorThreshold(), params.keywordThreshold()));
            }
        }
        List<KbHybridSearcher.Hit> hits = filterByKnowledgeIds(
            new ArrayList<>(searchFanOut.searchAllScoped(
                plans, Math.max(params.embeddingTopK() * 3, params.rerankTopK()))), req);
        log.info("[问答链] ④ 检索完成 库数={} 查询数={} 命中={} 向量模型={} 阈值(v/k)={}/{}",
            kbIds.size(), queries.size(), hits.size(),
            embeddingModelId == null ? "未配置" : embeddingModelId,
            params.vectorThreshold(), params.keywordThreshold());

        // 阶段 4b 对齐（源侧 query_expansion.go）：召回不足时用「关键词路」补一轮
        if (params.enableQueryExpansion() && hits.size() < params.embeddingTopK()) {
            List<KbHybridSearcher.Hit> expanded = expandQueries(
                req, kbIds, queries, params, embeddingModelId);
            if (!expanded.isEmpty()) {
                hits = mergeHits(hits, filterByKnowledgeIds(expanded, req));
                log.info("[问答链] ④ 查询扩展补入 {} 条，合并后 {} 条", expanded.size(), hits.size());
            }
        }
        // ⭐ 指定文档兜底：用户 @ 了文档但两路都没召回（典型「这个文件讲了什么」——
        //    泛问与任何块都不相似，全库 topK 又基本来自别的文档 ⇒ 事后过滤后归零）。
        //    此时直接取该文档的前 N 个文本块，保证模型至少看到这份文件的内容。
        if (hits.isEmpty() && req.knowledgeIds() != null && !req.knowledgeIds().isEmpty()) {
            hits = explicitDocFallback(req, Math.max(4, params.embeddingTopK()));
            log.info("[问答链] ④ 指定文档未命中，改用文档兜底 文档数={} 块数={} session={}",
                req.knowledgeIds().size(), hits.size(), req.sessionId());
        }
        return hits;
    }

    /**
     * 指定文档的兜底召回：按 {@code chunk_index} 取该文档前 N 个启用中的文本块。
     *
     * <p><b>为什么需要</b>：`filterByKnowledgeIds` 是<b>事后过滤</b>，而「这个文件讲了什么」
     * 这类概括性问题与任何具体块的相似度都低 ⇒ 召回的全库 topK 里几乎没有该文档，
     * 过滤后为 0，模型只能回答「没找到」。
     *
     * <p>⚠️ 这是**兜底**，不是主路径：正常命中时不会走到这里（{@code hits.isEmpty()} 前置）。
     * 分数给恒定值（0.5）—— 下游只按顺序拼接上下文，不参与排序。
     */
    private List<KbHybridSearcher.Hit> explicitDocFallback(Request req, int limit) {
        if (chunkMapper == null) {
            return List.of();
        }
        List<KbHybridSearcher.Hit> out = new ArrayList<>();
        for (String docId : req.knowledgeIds()) {
            if (docId == null || docId.isBlank()) {
                continue;
            }
            List<KbChunkRecord> rows;
            try {
                rows = chunkMapper.selectListByQuery(QueryWrapper.create()
                    .where("knowledge_id = ?", docId)
                    .and("deleted = 0")
                    .and("is_enabled = 1")
                    .and("chunk_type = 'text'")
                    .orderBy("chunk_index", true)
                    .limit(limit));
            } catch (RuntimeException e) {
                log.warn("[问答链] 文档兜底查询失败 doc={} 原因={}", docId, e.toString());
                continue;
            }
            for (KbChunkRecord r : rows) {
                out.add(new KbHybridSearcher.Hit(
                    r.getId(), r.getKnowledgeId(), r.getContent(), "", 0.5, 0, 0,
                    r.getChunkType() == null ? KbHybridSearcher.Hit.TYPE_TEXT : r.getChunkType()));
            }
        }
        return out;
    }

    /**
     * 文档级范围过滤（对齐源站 {@code knowledge_ids} 的 {@code allow} 语义，
     * 见 {@code KnowledgeService.java:611-625}）。
     *
     * <p>⚠️ 是<b>事后过滤</b>：召回的 limit 已按 {@code embeddingTopK * 3} 放大，一般够用；
     * 若某文档块数少导致命中变稀，靠阶段 4b 的查询扩展补一轮（扩展结果同样过本过滤）。
     */
    private List<KbHybridSearcher.Hit> filterByKnowledgeIds(
        List<KbHybridSearcher.Hit> hits, Request req) {
        List<String> docIds = req.knowledgeIds();
        if (docIds == null || docIds.isEmpty() || hits.isEmpty()) {
            return hits;
        }
        java.util.Set<String> allow = new java.util.HashSet<>(docIds);
        List<KbHybridSearcher.Hit> kept = new ArrayList<>(hits.size());
        for (KbHybridSearcher.Hit h : hits) {
            if (h != null && allow.contains(h.knowledgeId())) {
                kept.add(h);
            }
        }
        log.info("[问答链] ④ 文档范围过滤 指定文档={} 命中 {} → {} session={}",
            docIds.size(), hits.size(), kept.size(), req.sessionId());
        return kept;
    }

    /**
     * 查询扩展（对齐源侧 {@code query_expansion.go}）。
     *
     * <p>源侧的关键取舍必须照抄：
     * <ul>
     *   <li><b>只走关键词路</b>（{@code DisableVectorMatch = true}）—— 变体是本地生成的
     *       词序/去停用词变体，它们的向量就在原问题附近，再跑一遍向量路等于重复主检索；</li>
     *   <li>变体生成<b>不调 LLM</b>（停用词剔除 / 引号短语 / 分隔符切段 / 去疑问词，上限 5）；</li>
     *   <li>关键词阈值 <b>×0.8</b>（放宽，扩展本来就是找长尾）；</li>
     *   <li>召回条数 = {@code max(embeddingTopK*2, rerankTopK*2)}。</li>
     * </ul>
     *
     * @return 扩展命中（可能为空）
     */
    private List<KbHybridSearcher.Hit> expandQueries(
        Request req, List<String> kbIds, List<String> queries,
        KbConfigService.QaParams params, String embeddingModelId
    ) {
        List<String> variants = KbQueryExpansion.expand(queries);
        if (variants.isEmpty()) {
            log.debug("[问答链] ④ 无可扩展变体 session={}", req.sessionId());
            return List.of();
        }
        List<KbSearchFanOut.ScopedPlan> plans = new ArrayList<>();
        for (String kbId : kbIds) {
            for (String v : variants) {
                // embeddingModelId 传 null ⇒ 跳过向量路（源侧 DisableVectorMatch=true 等价）
                plans.add(new KbSearchFanOut.ScopedPlan(
                    new KbSearchFanOut.QueryPlan(kbId, v,
                        Math.max(params.embeddingTopK() * 2, params.rerankTopK() * 2), null),
                    params.vectorThreshold(), params.keywordThreshold() * 0.8));
            }
        }
        log.info("[问答链] ④ 查询扩展 变体={} 计划={} session={}",
            variants.size(), plans.size(), req.sessionId());
        return searchFanOut.searchAllScoped(plans, 0);
    }

    /** 合并两批命中（同 chunkId 保留高分），按分数降序 + id 升序。 */
    private static List<KbHybridSearcher.Hit> mergeHits(
        List<KbHybridSearcher.Hit> base, List<KbHybridSearcher.Hit> extra
    ) {
        java.util.Map<String, KbHybridSearcher.Hit> best = new java.util.LinkedHashMap<>();
        for (KbHybridSearcher.Hit h : base) {
            if (h != null && h.chunkId() != null) {
                best.put(h.chunkId(), h);
            }
        }
        for (KbHybridSearcher.Hit h : extra) {
            if (h == null || h.chunkId() == null) {
                continue;
            }
            KbHybridSearcher.Hit prev = best.get(h.chunkId());
            if (prev == null || h.score() > prev.score()) {
                best.put(h.chunkId(), h);
            }
        }
        List<KbHybridSearcher.Hit> out = new ArrayList<>(best.values());
        out.sort(java.util.Comparator
            .comparingDouble((KbHybridSearcher.Hit h) -> h.score()).reversed()
            .thenComparing(KbHybridSearcher.Hit::chunkId));
        return out;
    }

    /** 会话绑定智能体的知识库范围（null = 不限；空 = 禁止；非空 = 白名单）。 */
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
            List<String> scope = kbAgentService.resolveKnowledgeBaseIds(row.getMainThreadAgent().trim());
            if (scope == null || scope.isEmpty()) {
                return scope;
            }
            java.util.Set<String> existing = new java.util.HashSet<>();
            knowledgeBaseService.list().forEach(kb -> existing.add(kb.id()));
            List<String> filtered = scope.stream().filter(existing::contains).toList();
            if (filtered.size() != scope.size()) {
                log.warn("[问答链] ④ 智能体知识库白名单有 {} 个库已不存在，已忽略 session={}",
                    scope.size() - filtered.size(), sessionId);
            }
            return filtered;
        } catch (RuntimeException e) {
            log.warn("[问答链] ④ 读智能体知识库范围失败，本轮按全部可检索库 原因={}", e.getMessage());
            return null;
        }
    }

    private List<String> listSearchableKnowledgeBaseIds() {
        try {
            List<String> ids = new ArrayList<>();
            knowledgeBaseService.list().forEach(kb -> ids.add(kb.id()));
            return ids;
        } catch (RuntimeException e) {
            log.warn("[问答链] 知识库列表查询失败，本轮不检索 原因={}", e.getMessage());
            return List.of();
        }
    }

    private String resolveEmbeddingModelId() {
        try {
            return kbConfigService.readEmbeddingModelId();
        } catch (RuntimeException e) {
            log.warn("[问答链] 向量模型配置读取失败，本轮只走关键词 原因={}", e.getMessage());
            return null;
        }
    }

    // ── 阶段 5 + 7 + 10 ─────────────────────────────────────────────

    /**
     * 阶段 7 补 · 读上一轮的引用块（对齐源侧 {@code injectHistoryResults}）。
     *
     * <p>⭐ 只读**引用**（{@code kb_citation}），⛔ 不读上一轮的回答正文 ——
     * 回答是模型生成的，拿它当证据等于让模型引用自己可能出错的结论。
     *
     * @param sessionId 会话 id
     * @return 上一轮引用（无历史 / 失败 ⇒ 空）
     */
    private List<KbHistoryCitationStage.HistoryCitation> loadHistoryCitations(String sessionId) {
        if (citationWriter == null || sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            List<com.nexusai.kb.repo.entity.KbCitationRecord> rows =
                citationWriter.listRecentBySession(sessionId, KbHistoryCitationStage.MAX_INJECTED * 2);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            // ⭐ 只取「最近一次有引用的 assistant 消息」那批 —— 跨多轮混注会引入陈旧证据
            String latestMsg = null;
            for (com.nexusai.kb.repo.entity.KbCitationRecord r : rows) {
                if (r != null && r.getAssistantMessageId() != null) {
                    latestMsg = r.getAssistantMessageId();
                    break;
                }
            }
            if (latestMsg == null) {
                return List.of();
            }
            List<KbHistoryCitationStage.HistoryCitation> out = new ArrayList<>();
            for (com.nexusai.kb.repo.entity.KbCitationRecord r : rows) {
                if (r == null || !latestMsg.equals(r.getAssistantMessageId())) {
                    continue;
                }
                out.add(new KbHistoryCitationStage.HistoryCitation(
                    r.getChunkId(), r.getKnowledgeId(), r.getSnippet(), r.getKnowledgeTitle()));
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("[问答链] ⑦ 读历史引用失败，本轮不注入 原因={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 阶段 12 补 · 生成前推一次引用事件（失败只 WARN，⛔ 不阻断问答）。
     *
     * <p>⚠️ 与 REST {@code GET /messages/{id}/citations} 并存：事件是增量通知，
     * REST 是权威查询入口（刷新/历史回放走它）。两者数据同源。
     */
    private void pushCitations(
        Request req, SimpMessagingTemplate ws, String topic,
        KbContextBuilder.Rendered rendered,
        List<KbContextBuilder.ExternalSegment> webSegments
    ) {
        if (ws == null) {
            return;   // 测试/无推送上下文
        }
        try {
            List<com.nexusai.eventbus.ws.MessageCitationsEvent.CitationItem> items = new ArrayList<>();
            if (rendered != null && rendered.citations() != null) {
                for (KbContextBuilder.CitationRef c : rendered.citations()) {
                    items.add(new com.nexusai.eventbus.ws.MessageCitationsEvent.CitationItem(
                        c.refIndex(), c.chunkId(), c.knowledgeId(), c.knowledgeBaseId(),
                        c.knowledgeTitle(), c.content(), c.chunkIndex()));
                }
            }
            List<com.nexusai.eventbus.ws.MessageCitationsEvent.WebSource> web = new ArrayList<>();
            if (webSegments != null) {
                for (KbContextBuilder.ExternalSegment e : webSegments) {
                    if (e == null) {
                        continue;
                    }
                    web.add(new com.nexusai.eventbus.ws.MessageCitationsEvent.WebSource(
                        e.title() == null || e.title().isBlank() ? e.url() : e.title(), e.url()));
                }
            }
            if (items.isEmpty() && web.isEmpty()) {
                return;   // 没有来源 ⇒ 不推空事件
            }
            StreamEventSeq.send(ws, topic, new com.nexusai.eventbus.ws.MessageCitationsEvent(
                req.sessionId(), req.userMessageId(), req.assistantMessageId(), items, web));
            log.info("[问答链] ⑫ 引用已推送 块引用={} 网页={} session={}",
                items.size(), web.size(), req.sessionId());
        } catch (RuntimeException e) {
            // 引用是附加证据：推失败不影响已经能给出的答案
            log.warn("[问答链] ⑫ 引用推送失败，本轮不推 原因={}", e.getMessage());
        }
    }

    /**
     * 阶段 9 执行（默认关闭；失败 ⇒ 原样返回，⛔ 不阻断）。
     *
     * <p>⚠️ 只在开关打开且**确实命中 CSV/Excel** 时才跑 ——
     * 不加两层判断的话，每次问答都会白建一次 DuckDB 连接、白问模型一次。
     */
    private List<KbMergeStage.MergedChunk> applyDataAnalysis(
        List<KbMergeStage.MergedChunk> merged, String rewriteQuery,
        Request req, KbConfigService.QaParams params
    ) {
        if (dataAnalysisStage == null || merged == null || merged.isEmpty()
                || !params.dataAnalysisEnabled()) {
            return merged;
        }
        try {
            // 只有命中的文档里有 CSV/Excel 才值得做（stage 内部也会再判一次）
            List<String> knowledgeIds = new ArrayList<>();
            for (KbMergeStage.MergedChunk c : merged) {
                if (c != null && c.knowledgeId() != null && !knowledgeIds.contains(c.knowledgeId())) {
                    knowledgeIds.add(c.knowledgeId());
                }
            }
            String question = rewriteQuery == null || rewriteQuery.isBlank()
                ? req.question() : rewriteQuery;
            String model = req.modelName() != null && !req.modelName().isBlank()
                ? req.modelName() : resolveDefaultModelName();
            KbDataAnalysisStage.Outcome out =
                dataAnalysisStage.analyze(question, knowledgeIds, model);
            if (!out.hasResult()) {
                if (out.note() != null) {
                    log.info("[问答链] ⑨ 数据分析跳过 原因={}", out.note());
                }
                return merged;
            }
            // 作为一段合成片段追加：score=1.0，排在前面（它就是「算出来的答案」）
            List<KbMergeStage.MergedChunk> withAnalysis = new ArrayList<>(merged.size() + 1);
            withAnalysis.add(new KbMergeStage.MergedChunk(
                "data-analysis-" + req.assistantMessageId(), knowledgeIds.isEmpty() ? null : knowledgeIds.get(0),
                null, out.markdown(), "数据分析结果", "data_analysis", 0, 1.0, List.of()));
            withAnalysis.addAll(merged);
            log.info("[问答链] ⑨ 分析结果已并入上下文 行数={}", out.rows());
            return withAnalysis;
        } catch (RuntimeException e) {
            log.warn("[问答链] ⑨ 数据分析失败，本轮不并入 原因={}", e.getMessage());
            return merged;
        }
    }

    /**
     * 阶段 5b 执行（wiki 块 +30%；失败 ⇒ 原样返回，⛔ 不阻断）。
     *
     * <p>两道门：①结果里没有 wiki 块 ⇒ 立刻返回，**不查库**；
     * ②库内必须确实有 wiki 页（防「块没清干净」的残留被加成）。
     */
    private List<KbHybridSearcher.Hit> applyWikiBoost(
        List<KbHybridSearcher.Hit> hits, Request req
    ) {
        if (wikiBoostStage == null || hits == null || hits.isEmpty()) {
            return hits;
        }
        // 门 1：先扫一遍（这一步不碰数据库）
        if (!KbWikiBoostStage.hasWikiChunk(hits)) {
            return hits;
        }
        try {
            return wikiBoostStage.apply(hits, wikiPagePresent(req));
        } catch (RuntimeException e) {
            log.warn("[问答链] ⑤b wiki 加成失败，本轮不加成 原因={}", e.getMessage());
            return hits;
        }
    }

    /**
     * 库内是否确实存在 wiki 页（阶段 5b 的第二道门）。
     *
     * <p>⚠️ 用「库里有没有 wiki 页」代替源侧的 {@code indexing_strategy.wiki_enabled}：
     * 本仓没有那个配置位，而运行时事实比一个没人写的配置更可靠。
     */
    private boolean wikiPagePresent(Request req) {
        if (wikiPageService == null) {
            return false;
        }
        try {
            List<String> scopes = resolveKbScope(req);
            for (String kbId : scopes) {
                if (kbId != null && !kbId.isBlank() && !wikiPageService.listPages(kbId).isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.warn("[问答链] ⑤b 查 wiki 页失败，本轮不加成 原因={}", e.getMessage());
            return false;
        }
    }

    /**
     * 阶段 5c 执行（失败/无数据 ⇒ 原样返回，⛔ 不阻断）。
     *
     * <p>★ 顺序不能挪：必须紧跟在重排之后。重排会用模型分覆盖我们的加成，
     * 放到重排前面等于白算。
     */
    private List<KbHybridSearcher.Hit> applyMemoryAffinity(List<KbHybridSearcher.Hit> hits) {
        if (memoryAffinityStage == null || memoryService == null
                || hits == null || hits.isEmpty()) {
            return hits;
        }
        try {
            List<String> lookup = KbMemoryAffinityStage.knowledgeIdsToLookup(hits);
            if (lookup.isEmpty()) {
                return hits;
            }
            java.util.Map<String, Integer> affinity = memoryService.documentAffinity(lookup);
            if (affinity.isEmpty()) {
                log.info("[问答链] ⑤c 记忆亲和 提升=0/{} 无历史使用记录", hits.size());
                return hits;
            }
            return memoryAffinityStage.apply(hits, affinity, KbHybridSearcher.Hit::knowledgeId);
        } catch (RuntimeException e) {
            // 亲和是增强：读失败不该让整轮问答失败
            log.warn("[问答链] ⑤c 记忆亲和失败，本轮不加成 原因={}", e.getMessage());
            return hits;
        }
    }

    private KbContextBuilder.Rendered buildContext(
        String rewriteQuery, List<KbHybridSearcher.Hit> hits,
        KbConfigService.QaParams params, Request req,
        List<KbContextBuilder.ExternalSegment> webSegments
    ) {
        boolean noKbHits = hits == null || hits.isEmpty();
        if (noKbHits && (webSegments == null || webSegments.isEmpty())) {
            return KbContextBuilder.Rendered.empty();
        }
        List<KbMergeStage.MergedChunk> merged = List.of();
        int rerankInput = 0;
        if (!noKbHits) {
            rerankInput = Math.min(hits.size(), params.rerankTopK());
            List<KbHybridSearcher.Hit> reranked = hits;
            try {
                reranked = rerankStage.rerank(rewriteQuery, hits.subList(0, rerankInput),
                    resolveRerankModelId(), null, params.rerankThreshold());
            } catch (RuntimeException e) {
                log.warn("[问答链] ⑤ 重排异常，使用融合顺序 原因={}", e.getMessage());
            }
            // ── 阶段 5b：wiki 加成（同样必须在重排之后）──────────────
            reranked = applyWikiBoost(reranked, req);
            // ── 阶段 5c：记忆亲和（必须在重排**之后**，否则重排会覆盖加成）──
            reranked = applyMemoryAffinity(reranked);
            merged = mergeStage.merge(reranked, params.contextMaxChunks());
            // ── 阶段 8：FILTER_TOP_K（确定性排序 + 按 rerankTopK 收口）──
            if (filterTopKStage != null && !merged.isEmpty()) {
                merged = filterTopKStage.filter(merged, params.rerankTopK());
            }
            // ── 阶段 9：DATA_ANALYSIS（默认关闭；开关在 kb_config）──────
            merged = applyDataAnalysis(merged, rewriteQuery, req, params);
        }
        log.info("[问答链] ⑦ 合并 重排输入={} 合并输出={} 联网段={} session={}",
            rerankInput, merged.size(),
            webSegments == null ? 0 : webSegments.size(), req.sessionId());
        // 阶段 10：FAQ 优先分离 + 精确匹配标记（用 merge 后分数，已登记偏离）
        KbContextBuilder.Options ctxOptions = new KbContextBuilder.Options(
            params.faqPriorityEnabled(), params.faqDirectAnswerThreshold());
        return contextBuilder.build(merged, webSegments, ctxOptions);
    }

    /**
     * 阶段 4a（联网检索）+ 阶段 6（抓正文）· 对齐源侧
     * {@code search.go:searchWebIfEnabled} + {@code web_fetch.go}。
     *
     * <p><b>顺序</b>：先检索拿 URL，再对前 {@code webFetchTopN} 条抓正文替换 snippet。
     * 源侧的 WEB_FETCH 排在重排之后（只抓「已入选」的前 N 条）；本仓把两段合在这里做，
     * 因为网页没有 chunk、不参与重排 —— 拆开只会多一次穿透。
     *
     * <p><b>失败语义</b>：检索失败 → 空列表；单页抓取失败 → 丢弃该条（源侧保留 snippet，
     * 但本仓引擎不返回 snippet，留着一条只有标题和 URL 的空段会污染上下文）。
     *
     * @return 可直接进上下文的外部段
     */
    private List<KbContextBuilder.ExternalSegment> webSearch(
        Request req, String rewriteQuery, KbConfigService.QaParams params
    ) {
        if (!req.webSearchEnabled() || webSearchPort == null) {
            return List.of();
        }
        String query = rewriteQuery == null || rewriteQuery.isBlank()
            ? req.question() : rewriteQuery;
        List<KbWebSearchPort.WebHit> hits = webSearchPort.search(query, params.webSearchMaxResults());
        if (hits.isEmpty()) {
            return List.of();
        }
        int fetchN = params.webFetchEnabled() ? Math.max(0, params.webFetchTopN()) : 0;
        int toFetch = Math.min(fetchN, hits.size());
        List<String> pages = fetchAllConcurrently(ioPool, toFetch,
            i -> webSearchPort.fetch(hits.get(i).url()));
        List<KbContextBuilder.ExternalSegment> out = new ArrayList<>();
        int fetched = 0;
        for (int i = 0; i < hits.size(); i++) {
            KbWebSearchPort.WebHit h = hits.get(i);
            String text = h.snippet();
            if (i < toFetch) {
                String page = pages.get(i);
                if (page != null && !page.isBlank()) {
                    text = page;
                    fetched++;
                }
            }
            if (text == null || text.isBlank()) {
                // 没正文的联网结果只剩标题/URL —— 放进上下文等于让模型对着链接编答案
                continue;
            }
            out.add(new KbContextBuilder.ExternalSegment(h.title(), h.url(), text));
        }
        log.info("[问答链] ⑥ 联网 命中={} 抓正文={}/{} 入上下文={} session={}",
            hits.size(), fetched, Math.min(fetchN, hits.size()), out.size(), req.sessionId());
        return out;
    }

    private String resolveRerankModelId() {
        try {
            return kbConfigService.readRerankModelId();
        } catch (RuntimeException e) {
            log.warn("[问答链] ⑤ 重排模型配置读取失败，不重排 原因={}", e.getMessage());
            return null;
        }
    }

    // ── 阶段 11：生成 ───────────────────────────────────────────────

    /** 生成结果（回答 + 思考流 + 是否失败）。 */
    /** 用户主动停止：从生成段抛出，由 {@code run()} 转成 cancelledResult（⛔ 不当作失败）。 */
    private static final class CancelledRunException extends RuntimeException {
        CancelledRunException() { super("用户已停止"); }
    }

    private record Gen(String answer, String reasoning, boolean failed) {
    }

    /** [RAG 时间线] 极简 JSON 字符串转义（工具参数里只放查询文本）。 */
    private static String jsonEscape(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /**
     * 调模型并逐 chunk 推 STOMP。
     *
     * <p>用「完成回调 + latch」而不是 future.get(result)：本仓流式 provider 是回调式，
     * 没有返回带结果的 Future。
     */
    private Gen generate(
        Request req, String rewrittenQuery, KbContextBuilder.Rendered rendered,
        String memoryPrompt, KbQueryIntent intent, SimpMessagingTemplate ws, String topic,
        KbConfigService.QaParams params
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
        // 阶段 10 对齐（源侧 into_chat_message.go:applyIntentPromptOverride）：
        //   意图判定为「不需要检索」时，允许用配置的专属系统提示词替换默认的
        //   SYSTEM_NO_CONTEXT。例如问候意图配一句「简短寒暄，不要提知识库」。
        //   ⚠️ 只在**没有资料**时替换：有资料还按非检索意图答，等于凭空忽略证据。
        if (!hasContext && intent != null) {
            String override = intentPromptOf(params.intentPrompts(), intent);
            if (override != null) {
                systemPrompt = override;
                log.info("[问答链] ⑩ 使用意图专属提示词 意图={}", intent.wire());
            }
        }
        if (memoryPrompt != null && !memoryPrompt.isEmpty()) {
            // 记忆是「背景」，与资料分开：⛔ 不放进 contexts，否则模型会把它当证据引用
            systemPrompt = systemPrompt + "\n\n## 关于提问者的背景\n" + memoryPrompt;
        }
        String userMessage = KbQaPrompts.userMessage(
            rendered.text(), rewrittenQuery == null || rewrittenQuery.isBlank()
                ? req.question() : rewrittenQuery);

        List<ChatMessageDto> messages = List.of(
            new ChatMessageDto(null, req.sessionId(), com.nexusai.model.session.dto.Role.user,
                "user", userMessage, null, null, null,
                null, null, null, null, null, null, null, null, null, null, false));

        StringBuilder answer = new StringBuilder();
        StringBuilder reasoningBuf = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicBoolean truncatedEmpty = new AtomicBoolean(false);

        String finish = null;
        String text = "";
        boolean lengthTruncated = false;

        // 生成最多 2 次：首次「正常结束但一个字都没出」时自动重试一次（用户 2026-11-13 定）。
        // ⛔ 三条不重试：①已经推过正文（delta 收不回，重来会让用户看到重复内容）
        //   ②已经推过思考（重试会把思考再推一遍）③长度截断（输出预算问题，重试必然同样撞顶）
        for (int attempt = 1; attempt <= 2; attempt++) {
            answer.setLength(0);
            reasoningBuf.setLength(0);
            error.set(null);
            // 用户「停止」要能真中断上游流：传真实 controller，轮询取消标志后 abort（NOOP 只能等阶段边界）
            AbortController abortCtl = new AbortController();
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicReference<String> finishReason = new AtomicReference<>();
            CompletableFuture<Void> done = new CompletableFuture<>();
            // 引用句柄 → 可读编号 `[N]` 的流式展开器（每个 attempt 重建，重试不残留半截标签）
            RefTagStreamExpander refTagStream = new RefTagStreamExpander();

            ModelRequest request = new ModelRequest(
                config, modelName,
                List.of(new SystemPromptBlock(systemPrompt, CacheScope.NULL)),
                "knowledge_qa",
                messages,
                null, null, null, null, null,
                delta -> {
                    if (delta == null || delta.isEmpty()) {
                        return;
                    }
                    answer.append(delta);
                    // ⭐ 逐块推送，前端才是真流式（攒完再推等于非流式）。
                    // [2026-11-13 源站引用协议] 提示词要求模型输出 `<ref id="chunk://N"/>`（对齐
                    //   WeKnora modelcontext/citations.go 的 system-owned 协议）。这里做源站同款
                    //   「stream expander」：把句柄标签就地展开成可读编号 `[N]` 再推，
                    //   并**扣住跨 chunk 的半截标签**（否则用户会看到 `<ref id="chu` 这种裸文本）。
                    String safe = refTagStream.accept(delta);
                    if (!safe.isEmpty()) {
                        StreamEventSeq.send(ws, topic, MessageChunkEvent.of(
                            req.sessionId(), req.userMessageId(), req.assistantMessageId(), safe));
                    }
                },
                msg -> {
                    // 阶段 11 补：拿 finishReason（stop / length / tool_calls …）。
                    // ⚠️ provider 在流结束时给完整消息；缺失 ⇒ 保持 null，由后续判据兜底。
                    if (msg != null && msg.finishReason() != null) {
                        finishReason.set(msg.finishReason());
                    }
                },
                toolCall -> { /* 问答不产生工具调用 */ },
                reasoning -> {
                    if (reasoning == null || reasoning.isEmpty()) {
                        return;
                    }
                    reasoningBuf.append(reasoning);
                    StreamEventSeq.send(ws, topic, MessageChunkEvent.ofReasoning(
                        req.sessionId(), req.userMessageId(), req.assistantMessageId(), reasoning));
                },
                () -> log.debug("[问答链] ⑪ 流式降级为非流式 session={}", req.sessionId()),
                error::set,
                () -> {
                    if (completed.compareAndSet(false, true)) {
                        done.complete(null);
                    }
                },
                abortCtl,
                null, null, null, null,
                com.nexusai.infra.llm.StreamIdleControl.NONE);

            LlmInvocationGateway.stream(providerFactory, modelConfigResolver, request);

            try {
                while (true) {
                    try {
                        done.get(200, TimeUnit.MILLISECONDS);
                        break;
                    } catch (java.util.concurrent.TimeoutException te) {
                        if (req.cancelled() != null && req.cancelled().get()) {
                            // 用户点了停止：先中断上游流，再等最多 2s 收尾（provider 不响应也照常收口）
                            abortCtl.abort("user_cancelled");
                            try {
                                done.get(2, TimeUnit.SECONDS);
                            } catch (Exception ignore) {
                                // 收尾超时：按已取消处理，不再等
                            }
                            throw new CancelledRunException();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("生成被中断");
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("生成失败：" + e.getCause());
            }

            finish = finishReason.get();
            // 先把展开器缓冲的尾巴推完（对齐源站：流结束才吐出被扣住的残片）
            String refTail = refTagStream.flush();
            if (!refTail.isEmpty() && ws != null) {
                StreamEventSeq.send(ws, topic, MessageChunkEvent.of(
                    req.sessionId(), req.userMessageId(), req.assistantMessageId(), refTail));
            }
            // 落库/complete 用整段清洗后的文本：否则完成后重渲染又会冒出 `<ref .../>` 原文
            text = KbQaPrompts.stripRefTags(answer.toString());
            lengthTruncated = KbQaPrompts.isLengthFinishReason(finish);

            // 自动重试一次：仅「无错误 + 零正文 + 零思考 + 非长度截断」
            if (attempt == 1 && error.get() == null && text.isBlank()
                && reasoningBuf.length() == 0 && !lengthTruncated) {
                log.warn("[问答链] ⑪ 模型返回空回答（非截断），自动重试一次 session={} 模型={} finishReason={}",
                    req.sessionId(), modelName, finish);
                continue;
            }
            break;
        }

        boolean failed = false;

        // ── 阶段 11 四补（源侧 chat_completion_stream.go）────────────────
        // 补 1：Done && finishReason == incomplete/length 且有正文 ⇒ 不是「短答案」，
        //   是**被截断的答案**。源侧把它转成错误；本仓在末尾追加明确的截断提示，
        //   让用户知道「后面还有」而不是以为模型就答到这。
        // 补 2：长度截断且**从未产出任何文本** ⇒ 空截断回退（不是「模型没话可说」）。
        if (error.get() != null) {
            failed = true;
            text = "生成失败：" + error.get().getMessage();
            StreamEventSeq.send(ws, topic, MessageChunkEvent.of(
                req.sessionId(), req.userMessageId(), req.assistantMessageId(), text));
        } else if (text.isBlank()) {
            text = lengthTruncated
                ? KbQaPrompts.EMPTY_TRUNCATED_FALLBACK
                : KbQaPrompts.EMPTY_ANSWER_FALLBACK;
            truncatedEmpty.set(true);
            StreamEventSeq.send(ws, topic, MessageChunkEvent.of(
                req.sessionId(), req.userMessageId(), req.assistantMessageId(), text));
            log.warn("[问答链] ⑪ 模型返回空回答（已重试） session={} 模型={} finishReason={} 长度截断={}",
                req.sessionId(), modelName, finish, lengthTruncated);
        } else if (lengthTruncated) {
            // 有正文但被截断 ⇒ 追加提示（⛔ 不改写已推出去的正文）
            String note = KbQaPrompts.TRUNCATED_SUFFIX;
            text = text + note;
            StreamEventSeq.send(ws, topic, MessageChunkEvent.of(
                req.sessionId(), req.userMessageId(), req.assistantMessageId(), note));
            log.warn("[问答链] ⑪ 回答被长度截断 session={} 模型={} finishReason={} 长度={}",
                req.sessionId(), modelName, finish, text.length());
        }
        String reasoning = reasoningBuf.isEmpty() ? null : reasoningBuf.toString();
        log.info("[问答链] ⑪ 生成完成 session={} 模型={} 长度={} 空截断兜底={} 长度截断={} finishReason={} 失败={}",
            req.sessionId(), modelName, text.length(), truncatedEmpty.get(),
            lengthTruncated, finish, failed);
        return new Gen(text, reasoning, failed);
    }

    private String resolveDefaultModelName() {
        try {
            if (settingsService != null) {
                com.nexusai.model.settings.dto.SettingsDto dto = settingsService.get();
                if (dto != null && dto.mainModelName() != null && !dto.mainModelName().isBlank()) {
                    return dto.mainModelName();
                }
            }
        } catch (RuntimeException e) {
            log.warn("[问答链] 主模型名解析失败，回落默认 原因={}", e.getMessage());
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
            log.warn("[问答链] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    // ── 阶段 12：引用 ───────────────────────────────────────────────

    /**
     * 阶段 5c 的<b>写入侧</b>：把本轮引用过的文档记一次「用过」。
     *
     * <h2>⭐⭐ 为什么必须补这一段</h2>
     * 阶段 5c 的读侧（{@link KbMemoryAffinityStage}）实现完整：算
     * {@code 1 + 0.15 × log1p(hits)/log1p(8)}、低于最小命中数跳过、最多 200 文档。
     * 但它的数据源 {@code kb_memory_doc_affinity} <b>从来没人写</b> ——
     * {@code KbMemoryService.recordAnswerSources} 全仓零调用（2026-11-06 确认）。
     * 结果：每轮日志都是「⑤c 记忆亲和 提升=0/19 无历史使用记录」，
     * 功能看着在跑，实际上永远不生效。这是第 27 类缺陷（读了没人写）的又一例。
     *
     * <p>⚠️ 记的是<b>本轮引用的文档</b>（去重），不是所有命中 —— 源侧
     * {@code KnowledgeReferences} 也只记最终引用的文档。
     * 「命中但没被引用」的文档不该获得亲和加成，否则高频出现的泛文档会自我强化。
     *
     * <p>失败静默：记亲和是增强项，⛔ 不能因为它让整轮问答失败。
     */
    private void recordDocAffinity(KbContextBuilder.Rendered rendered) {
        if (memoryService == null || rendered == null || rendered.citations().isEmpty()) {
            return;
        }
        // 按 knowledgeId 去重（同一文档多个块被引用 ⇒ 只 +1 次）
        java.util.LinkedHashMap<String, com.nexusai.kb.dto.memory.KbMemoryDocView> byDoc =
            new java.util.LinkedHashMap<>();
        for (KbContextBuilder.CitationRef ref : rendered.citations()) {
            if (ref.knowledgeId() == null || ref.knowledgeId().isBlank()) {
                continue;
            }
            byDoc.putIfAbsent(ref.knowledgeId(),
                new com.nexusai.kb.dto.memory.KbMemoryDocView(
                    ref.knowledgeId(), ref.knowledgeId(), ref.knowledgeBaseId(),
                    ref.knowledgeTitle(), 0, null));
        }
        if (byDoc.isEmpty()) {
            return;
        }
        try {
            memoryService.recordAnswerSources(new ArrayList<>(byDoc.values()));
            log.info("[问答链] ⑤c 记录文档亲和 文档数={}", byDoc.size());
        } catch (RuntimeException e) {
            log.warn("[问答链] ⑤c 记录文档亲和失败，本轮跳过 原因={}", e.getMessage());
        }
    }

    private int saveCitations(String sessionId, String assistantMessageId,
                              KbContextBuilder.Rendered rendered) {
        List<KbCitationWriter.Citation> rows = new ArrayList<>();
        for (KbContextBuilder.CitationRef ref : rendered.citations()) {
            rows.add(new KbCitationWriter.Citation(
                ref.refIndex(), ref.chunkId(), ref.knowledgeId(), ref.knowledgeBaseId(),
                ref.knowledgeTitle(), ref.content(), ref.chunkIndex()));
        }
        return citationWriter.saveAll(sessionId, assistantMessageId, rows);
    }

    /**
     * 把本轮用到的网页记进「来源」通道（{@code session_artifact.kind='source'}）。
     *
     * <p>⛔ 不写 {@code kb_citation}：那张表的 {@code chunk_id} 指向知识库块，
     * 网页没有块 —— 塞一个 URL 进去，前端点引用会去查一个不存在的 chunk。
     *
     * <p>失败静默（来源采集是增强，不影响回答）。
     */
    private void recordWebSources(Request req, List<KbContextBuilder.ExternalSegment> segments) {
        if (segments == null || segments.isEmpty() || sessionArtifactService == null) {
            return;
        }
        int ok = 0;
        for (KbContextBuilder.ExternalSegment s : segments) {
            try {
                if (sessionArtifactService.recordSource(
                        req.sessionId(), s.title(), s.url(), null, req.userMessageId())) {
                    ok++;
                }
            } catch (RuntimeException e) {
                log.warn("[问答链] ⑫ 记录网页来源失败 url={} 原因={}", s.url(), e.getMessage());
            }
        }
        log.info("[问答链] ⑫ 网页来源落库 session={} {}/{}", req.sessionId(), ok, segments.size());
    }

    // ── 工具 ──────────────────────────────────────────────────────

    /**
     * 取某意图的专属系统提示词（{@code kb_config.intent_prompts} 的 JSON 映射）。
     *
     * <p>JSON 形状：{@code {"greeting": "...", "chitchat": "..."}}。
     * 解析失败 / 键不存在 / 值为空白 ⇒ {@code null}（回落默认提示词，⛔ 不报错）。
     *
     * @param json   配置原文（可 null）
     * @param intent 意图
     * @return 专属提示词；没有 → null
     */
    private static String intentPromptOf(String json, KbQueryIntent intent) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(json);
            com.fasterxml.jackson.databind.JsonNode v = node.get(intent.wire());
            if (v == null || v.isNull() || !v.isTextual()) {
                return null;
            }
            String text = v.asText();
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("[问答链] ⑩ intent_prompts 解析失败，回落默认提示词 原因={}", e.getMessage());
            return null;
        }
    }

    private static boolean isCancelled(Request req) {
        return req.cancelled() != null && req.cancelled().get();
    }

    /**
     * 并发抓取 {@code n} 个页面正文，<b>按索引顺序</b>返回。
     *
     * <p><b>为什么抽成静态方法</b>：它是「并发语义」的全部所在，必须能被单测直接打到
     * （{@code KbQaPipeline} 是 10 参构造 + 一堆 {@code @Autowired}，起真容器测试成本过高）。
     * 抽出来之后，测试验的是<b>生产代码这一条路径</b>，而不是另写一份复刻。
     *
     * <p><b>⭐ 为什么按索引而不是按完成顺序</b>：网页段在上下文里的顺序每轮都变，会让
     * 模型对同一条查询给出不一致的答案，也让回归测试无法比对。源侧并发抓取后同样保序。
     *
     * <p><b>异常语义</b>：单页失败 → 返回 {@code null}（调用方回落到摘要/丢弃该条），
     * ⛔ 不让一页抓取失败毁掉整轮问答 —— 与串行版逐条 try 的语义一致。
     *
     * @param pool     线程池
     * @param count    要抓的条数（&lt;= 0 ⇒ 空列表）
     * @param fetcher  按索引取正文（返回 null/空表示该页没抓到）
     * @return 按 {@code 0..count-1} 顺序的正文（长度恒为 {@code max(0,count)}）
     */
    static List<String> fetchAllConcurrently(
        ExecutorService pool, int count, java.util.function.IntFunction<String> fetcher
    ) {
        if (count <= 0) {
            return List.of();
        }
        List<CompletableFuture<String>> futures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return fetcher.apply(idx);
                } catch (RuntimeException e) {
                    log.warn("[问答链] ⑥ 单页抓取失败，该条回落到摘要 idx={} 原因={}", idx, e.getMessage());
                    return null;
                }
            }, pool));
        }
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(await(futures.get(i)));
        }
        return out;
    }

    /**
     * 等并发段的结果，并把异常摊平成运行时异常（调用方按「整轮失败」处理）。
     *
     * <p>⚠️ {@code join()} 抛的是 {@link java.util.concurrent.CompletionException}，直接上抛会让
     * 最外层 catch 打出一句 {@code java.util.concurrent.CompletionException: ...} 的噪音；
     * 这里剥到根因，保持串行版的错误文案形状。
     */
    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        } catch (java.util.concurrent.CancellationException e) {
            throw new IllegalStateException("问答链并发段被取消", e);
        }
    }

    private static Result cancelledResult(String note) {
        return new Result(note, null, null, false, 0, 0, true, false, null);
    }
}
