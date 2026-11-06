package com.nexusai.kb.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nexusai.kb.app.graph.KbGraphExtractionService;
import com.nexusai.kb.app.graph.KbGraphOutcome;
import com.nexusai.kb.app.graph.KbGraphSearchService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱 REST 端点 · W7（对齐源侧 swagger「知识管理」tag 的 graph-search）。
 *
 * <h2>三个端点</h2>
 * <ol>
 *   <li>{@code POST /knowledge-bases/{id}/graph-search} —— 源侧契约，逐字对齐
 *       {@code GraphSearchResult}（entities / relationships / source_chunks / meta）。</li>
 *   <li>{@code GET /knowledge-bases/{id}/graph} —— 本仓补充的只读概览，
 *       前端星图默认就是全库视图，用 GET 更自然（无 body、可缓存）。</li>
 *   <li>{@code POST /knowledge-bases/{id}/graph/rebuild} —— 本仓补充的手动重建。
 *       ⚠️ 源侧只有「后处理自动派发」一条路，改模型后想立刻看效果只能重传文档。</li>
 * </ol>
 *
 * <p><b>⚠️ DTO 逐字段 {@code @JsonProperty}</b>：源侧 JSON 是 snake_case，
 * NexusAI 全局是 camelCase。⛔ 不给全局加 SNAKE_CASE 策略（会改现有所有端点契约）。
 */
@RestController
public class GraphController {

    private static final Logger log = LoggerFactory.getLogger(GraphController.class);

    private final KbGraphSearchService searchService;
    private final KbGraphExtractionService extractionService;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbKnowledgeMapper knowledgeMapper;

    public GraphController(
        KbGraphSearchService searchService,
        KbGraphExtractionService extractionService,
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbKnowledgeMapper knowledgeMapper
    ) {
        this.searchService = searchService;
        this.extractionService = extractionService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeMapper = knowledgeMapper;
    }

    // ══════════════════════════════════════════════════════════
    // 1. 图谱检索（源侧契约）
    // ══════════════════════════════════════════════════════════

    /**
     * 图谱检索。
     *
     * @param id      知识库 ID
     * @param request 请求（可空；query 为空 ⇒ 全库概览）
     * @return 图谱结果
     */
    @PostMapping("/api/v1/knowledge-bases/{id}/graph-search")
    public GraphSearchResponse graphSearch(
        @PathVariable String id,
        @RequestBody(required = false) GraphSearchRequest request
    ) {
        requireKb(id);
        KbGraphSearchService.Request req = request == null
            ? new KbGraphSearchService.Request(null, null, null, null)
            : new KbGraphSearchService.Request(
                request.query(), request.maxNodes(), request.maxRelations(), request.maxSourceChunks());
        KbGraphSearchService.Result r = searchService.search(id, req);
        log.info("[图谱API] POST /knowledge-bases/{}/graph-search → 实体={} 关系={}",
            id, r.entities().size(), r.relationships().size());
        return toResponse(r);
    }

    // ══════════════════════════════════════════════════════════
    // 2. 图谱概览（GET）
    // ══════════════════════════════════════════════════════════

    /**
     * 图谱概览。
     *
     * @param id           知识库 ID
     * @param query        查询文本（可空 ⇒ 全库）
     * @param maxNodes     实体上限
     * @param maxRelations 关系上限
     * @return 图谱结果
     */
    @GetMapping("/api/v1/knowledge-bases/{id}/graph")
    public GraphSearchResponse graphGet(
        @PathVariable String id,
        @RequestParam(value = "query", required = false) String query,
        @RequestParam(value = "max_nodes", required = false) Integer maxNodes,
        @RequestParam(value = "max_relations", required = false) Integer maxRelations
    ) {
        requireKb(id);
        KbGraphSearchService.Result r = searchService.search(id,
            new KbGraphSearchService.Request(query, maxNodes, maxRelations, null));
        return toResponse(r);
    }

    // ══════════════════════════════════════════════════════════
    // 3. 手动重建
    // ══════════════════════════════════════════════════════════

    /**
     * 手动重建某篇文档的图谱。
     *
     * <p>⚠️ <b>同步执行</b>（阻塞到抽完）：一篇文档通常几秒到几十秒。
     * 做成同步而不入队，是因为用户点「重建」时期待立刻看到结果；
     * 入队后还要再轮询一次，体验反而更差。
     *
     * @param id          知识库 ID
     * @param knowledgeId 文档 ID
     * @param model       模型名（可空 ⇒ 用知识库的摘要模型）
     * @return 结果摘要
     */
    @PostMapping("/api/v1/knowledge-bases/{id}/graph/rebuild")
    public GraphRebuildResponse rebuild(
        @PathVariable String id,
        @RequestParam("knowledge_id") String knowledgeId,
        @RequestParam(value = "model", required = false) String model
    ) {
        KbKnowledgeBaseRecord kb = requireKb(id);
        KbKnowledgeRecord knowledge = knowledgeMapper.selectOneById(knowledgeId);
        if (knowledge == null) {
            throw new NotFoundException("知识条目不存在: " + knowledgeId);
        }
        if (!id.equals(knowledge.getKnowledgeBaseId())) {
            throw new ValidationException("该文档不属于此知识库");
        }
        String modelName = model;
        if (modelName == null || modelName.isBlank()) {
            modelName = kb.getSummaryModelId();
        }
        if (modelName == null || modelName.isBlank()) {
            throw new ValidationException("未配置图谱抽取模型（知识库 summary_model_id 为空）");
        }
        // ⭐ 必须传真实 attempt：传 0 会被 extract() 的陈旧任务守卫拦下，
        //    用户点「重建」什么也不会发生，而 HTTP 仍是 200。
        int attempt = knowledge.getParseAttempt() == null ? 0 : knowledge.getParseAttempt();
        KbGraphOutcome outcome = extractionService.extract(knowledgeId, attempt, modelName);
        log.info("[图谱API] 重建 knowledge={} 结果={} 说明={}",
            knowledgeId, outcome.status(), outcome.detail());
        return new GraphRebuildResponse(
            outcome.status().name().toLowerCase(), outcome.detail());
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private KbKnowledgeBaseRecord requireKb(String id) {
        KbKnowledgeBaseRecord kb = knowledgeBaseMapper.selectOneById(id);
        if (kb == null) {
            throw new NotFoundException("知识库不存在: " + id);
        }
        return kb;
    }

    /** 领域结果 → 出站 DTO（逐字段映射，⛔ 不直接序列化 record —— 需要 snake_case）。 */
    private static GraphSearchResponse toResponse(KbGraphSearchService.Result r) {
        List<GraphSearchResponse.Entity> entities = new ArrayList<>(r.entities().size());
        for (KbGraphSearchService.Entity e : r.entities()) {
            entities.add(new GraphSearchResponse.Entity(
                e.id(), e.name(), e.knowledgeId(),
                e.attributes(), e.sourceChunkIds(), e.entityType()));
        }
        List<GraphSearchResponse.Relation> relations = new ArrayList<>(r.relationships().size());
        for (KbGraphSearchService.Relation rel : r.relationships()) {
            relations.add(new GraphSearchResponse.Relation(
                rel.id(), rel.sourceId(), rel.targetId(), rel.type(),
                rel.sourceChunkIds(), rel.weight(), rel.strength()));
        }
        List<GraphSearchResponse.SourceChunk> sources = new ArrayList<>(r.sourceChunks().size());
        for (KbGraphSearchService.SourceChunk c : r.sourceChunks()) {
            sources.add(new GraphSearchResponse.SourceChunk(
                c.id(), c.knowledgeId(), c.title(), c.content(),
                c.chunkIndex(), c.startAt(), c.endAt(),
                c.contentTruncated(), c.titleTruncated()));
        }
        return new GraphSearchResponse(
            r.knowledgeBaseId(), r.query(),
            entities, relations, sources,
            new GraphSearchResponse.Meta(
                r.meta().truncated(), r.meta().truncationReasons(),
                r.meta().maxNodes(), r.meta().maxRelations(), r.meta().maxSourceChunks()));
    }

    // ══════════════════════════════════════════════════════════
    // DTO
    // ══════════════════════════════════════════════════════════

    /**
     * 图谱检索请求 · 对齐源侧 {@code GraphSearchRequest}。
     *
     * @param query          查询文本（可空 ⇒ 全库）
     * @param maxNodes       实体上限
     * @param maxRelations   关系上限
     * @param maxSourceChunks 证据块上限
     */
    public record GraphSearchRequest(
        @JsonProperty("query") String query,
        @JsonProperty("max_nodes") Integer maxNodes,
        @JsonProperty("max_relations") Integer maxRelations,
        @JsonProperty("max_source_chunks") Integer maxSourceChunks
    ) {
    }

    /**
     * 图谱检索结果 · <b>逐字对齐</b>源侧 {@code GraphSearchResult}。
     */
    public record GraphSearchResponse(
        @JsonProperty("knowledge_base_id") String knowledgeBaseId,
        @JsonProperty("query") String query,
        @JsonProperty("entities") List<Entity> entities,
        @JsonProperty("relationships") List<Relation> relationships,
        @JsonProperty("source_chunks") List<SourceChunk> sourceChunks,
        @JsonProperty("meta") Meta meta
    ) {
        /** 实体（源侧 {@code GraphSearchEntity}）+ 一个本仓扩展字段 {@code entity_type}。 */
        public record Entity(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("knowledge_id") String knowledgeId,
            @JsonProperty("attributes") List<String> attributes,
            @JsonProperty("source_chunk_ids") List<String> sourceChunkIds,
            @JsonProperty("entity_type") String entityType
        ) {
        }

        /**
         * 关系（源侧 {@code GraphSearchRelation}）+ 本仓扩展字段 {@code weight}/{@code strength}。
         *
         * <p>⚠️ 源侧没有 weight/strength（那是内部计算值）。本仓暴露它们是因为
         * 星图要按权重调线的粗细 —— 让前端自己算等于把算法复制一份。
         */
        public record Relation(
            @JsonProperty("id") String id,
            @JsonProperty("source_id") String sourceId,
            @JsonProperty("target_id") String targetId,
            @JsonProperty("type") String type,
            @JsonProperty("source_chunk_ids") List<String> sourceChunkIds,
            @JsonProperty("weight") double weight,
            @JsonProperty("strength") int strength
        ) {
        }

        /** 证据块（源侧 {@code GraphSearchSourceChunk}）· 受限投影。 */
        public record SourceChunk(
            @JsonProperty("id") String id,
            @JsonProperty("knowledge_id") String knowledgeId,
            @JsonProperty("title") String title,
            @JsonProperty("content") String content,
            @JsonProperty("chunk_index") int chunkIndex,
            @JsonProperty("start_at") int startAt,
            @JsonProperty("end_at") int endAt,
            @JsonProperty("content_truncated") boolean contentTruncated,
            @JsonProperty("title_truncated") boolean titleTruncated
        ) {
        }

        /** 截断信息（源侧 {@code GraphSearchMeta}）。 */
        public record Meta(
            @JsonProperty("truncated") boolean truncated,
            @JsonProperty("truncation_reasons") List<String> truncationReasons,
            @JsonProperty("max_nodes") int maxNodes,
            @JsonProperty("max_relations") int maxRelations,
            @JsonProperty("max_source_chunks") int maxSourceChunks
        ) {
        }
    }

    /**
     * 重建结果。
     *
     * @param status 结果分类（done / skipped / superseded / failed）
     * @param detail 人可读说明
     */
    public record GraphRebuildResponse(
        @JsonProperty("status") String status,
        @JsonProperty("detail") String detail
    ) {
    }
}
