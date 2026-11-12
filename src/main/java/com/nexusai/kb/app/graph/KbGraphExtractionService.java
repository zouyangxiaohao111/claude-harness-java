package com.nexusai.kb.app.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.cost.LlmSideCallClient;
import com.nexusai.kb.app.ingest.KbChunkText;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.UsageCategory;
import com.nexusai.kb.repo.KbTimeFormat;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbGraphEntityRecord;
import com.nexusai.kb.repo.entity.KbGraphRelationRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeRecord;
import com.nexusai.kb.app.graph.store.KbGraphStore;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.kb.repo.mapper.KbKnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 图谱抽取（单篇文档一轮）· 对齐 WeKnora
 * {@code graph.go BuildGraph}（{@code extractEntities} → {@code extractRelationships} →
 * {@code calculateWeights} → {@code calculateDegrees}）。
 *
 * <h2>与源侧的两处有意差异</h2>
 * <ol>
 *   <li><b>不并发</b>。源侧用 errgroup 并发 4 路抽实体。本仓是单机 SQLite + 本地模型调用，
 *       并发只会让上游限流更早触发，且让日志无法按块对齐。串行更慢但可追。</li>
 *   <li><b>不逐块起任务</b>。源侧每块一个 asynq 任务（占一个 finalizing 槽位）。
 *       本仓按「一篇文档一个任务」做（与摘要/问句一致）：图谱是文档内的关系图，
 *       拆成块级任务后跨块关系要额外合并，收益不值得复杂度。</li>
 * </ol>
 *
 * <p><b>⭐⭐ 幂等语义：先清后写</b>。同一篇文档重抽时，旧的实体/关系全部逻辑删，
 * 再按本次结果重写。⛔ 不做 upsert 增量合并 —— 模型这次可能抽出了上次没有的实体，
 * 增量合并会留下「上次抽错、这次没抽到」的幽灵节点。
 */
@Component
public class KbGraphExtractionService {

    private static final Logger log = LoggerFactory.getLogger(KbGraphExtractionService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单块正文上限（防止超长块打爆提示词）。 */
    static final int MAX_CHUNK_CHARS = 4000;

    /** 关系抽取时送进去的最大总字符数。 */
    static final int MAX_RELATION_CONTENT_CHARS = 12000;

    // ⚠️ 2026-11-12 删除 MAX_TOKENS_ENTITIES / MAX_TOKENS_RELATIONS（原 4000）：
    //   输出上限唯一入口 = models.max_tokens。写死 4000 会让大文档（如 288 实体、
    //   80 块）的实体/关系输出被截断 ⇒ 静默丢关系（11-12 千岛湖高铁缺陷）。

    /** 图谱抽取的温度（源侧 {@code DefaultLLMTemperature = 0.1}，要稳定）。 */
    private static final double TEMPERATURE = 0.1;

    private final KbKnowledgeMapper knowledgeMapper;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper chunkMapper;
    /**
     * 图谱存储（本地 SQLite 或远程 Neo4j）。
     *
     * <p>⭐ 2026-11-11 抽接口：原直连两个 mapper，接远程 Neo4j 时这里必须跟着改。
     * 现在写入统一走 {@link KbGraphStore#replaceGraph}（先清后写），
     * 两种实现各自决定怎么落。
     */
    private final KbGraphStore graphStore;
    private final LlmSideCallClient sideCallClient;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    public KbGraphExtractionService(
        KbKnowledgeMapper knowledgeMapper,
        KbKnowledgeBaseMapper knowledgeBaseMapper,
        KbChunkMapper chunkMapper,
        KbGraphStore graphStore,
        LlmSideCallClient sideCallClient
    ) {
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.chunkMapper = chunkMapper;
        this.graphStore = graphStore;
        this.sideCallClient = sideCallClient;
    }

    /**
     * 执行一轮图谱抽取。
     *
     * @param knowledgeId 文档 ID
     * @param attempt     本轮解析 attempt（陈旧任务识别）
     * @param modelName   模型名（空 ⇒ 未配置，跳过）
     * @return 结果（永不 null、永不抛）
     */
    public KbGraphOutcome extract(String knowledgeId, int attempt, String modelName) {
        KbKnowledgeRecord knowledge = knowledgeMapper.selectOneById(knowledgeId);
        if (knowledge == null) {
            return KbGraphOutcome.skipped("知识条目不存在");
        }
        int current = knowledge.getParseAttempt() == null ? 0 : knowledge.getParseAttempt();
        if (current != attempt) {
            return KbGraphOutcome.superseded("陈旧任务 attempt=" + attempt + " 当前=" + current);
        }
        String status = knowledge.getParseStatus();
        if ("deleting".equals(status) || "cancelled".equals(status)) {
            return KbGraphOutcome.skipped("文档处于 " + status);
        }
        String kbId = knowledge.getKnowledgeBaseId();
        KbKnowledgeBaseRecord kb = kbId == null || kbId.isBlank()
            ? null : knowledgeBaseMapper.selectOneById(kbId);
        if (kb == null) {
            return KbGraphOutcome.skipped("知识库不存在");
        }
        if (modelName == null || modelName.isBlank()) {
            return KbGraphOutcome.skipped("未配置图谱抽取模型");
        }
        ModelConfigResolver.ResolvedModel resolved = resolveModel(modelName);
        ProviderConfig provider = resolved == null ? null : resolved.config();
        if (provider == null) {
            return KbGraphOutcome.skipped("提供方未配置");
        }
        // ⚠️ 传给模型 API 的必须是 **models.name**，而调用方手里通常只有 **models.id**
        //    （知识库的 *_model_id 列存 id）。用错会真的打到对方 API 上并 400：
        //    实测 `The supported API model names are deepseek-flash, deepseek-v4-pro,
        //    but you passed model-0823a98d`。
        String apiModelName = resolved.apiModelName() != null && !resolved.apiModelName().isBlank()
            ? resolved.apiModelName() : modelName;

        List<KbChunkRecord> chunks = graphChunksOf(knowledgeId);
        if (chunks.isEmpty()) {
            return KbGraphOutcome.skipped("无可抽取文本块");
        }

        // ── ① 逐块抽实体 ──────────────────────────────────────────────
        // name → 出现过的块 + 描述 + 类型 + 属性
        Map<String, Acc> entities = new LinkedHashMap<>();
        int entityFailures = 0;
        for (KbChunkRecord c : chunks) {
            String content = c.getContent();
            if (!KbChunkText.hasExtractableText(content)) {
                continue;
            }
            String clipped = clip(content, MAX_CHUNK_CHARS);
            List<KbGraphParser.ExtractedEntity> got;
            try {
                String raw = sideCallClient.chat(
                    provider, apiModelName, KbGraphPrompts.EXTRACT_ENTITIES, clipped,
                    options("graph_extract_entities"),
                    LlmSideCallClient.SideCallUsage.standalone(
                        UsageCategory.SIDE_CALL, "graph_extract_entities"));
                got = KbGraphParser.parseEntities(raw);
            } catch (RuntimeException e) {
                entityFailures++;
                log.warn("[图谱] 实体抽取失败 chunk={} 原因={}", c.getId(), e.getMessage());
                continue;
            }
            for (KbGraphParser.ExtractedEntity e : got) {
                entities.computeIfAbsent(e.name(), k -> new Acc(e))
                    .chunkIds.add(c.getId());
            }
        }
        if (entities.isEmpty()) {
            clearExisting(knowledgeId);
            return KbGraphOutcome.skipped(
                "未抽出实体（块数=" + chunks.size() + " 失败块=" + entityFailures + "）");
        }

        // ── ② 抽关系（全文档一次，端点限定在已抽出的实体名内）─────────
        Set<String> names = new LinkedHashSet<>(entities.keySet());
        List<KbGraphParser.ExtractedRelation> relations = new ArrayList<>();
        String merged = mergeChunks(chunks);
        try {
            String entitiesJson = entitiesJsonOf(entities.values());
            String raw = sideCallClient.chat(
                provider, apiModelName, KbGraphPrompts.EXTRACT_RELATIONSHIPS,
                KbGraphPrompts.relationshipUserMessage(entitiesJson, merged),
                options("graph_extract_relations"),
                LlmSideCallClient.SideCallUsage.standalone(
                    UsageCategory.SIDE_CALL, "graph_extract_relations"));
            relations = new ArrayList<>(KbGraphParser.parseRelations(raw, names));
        } catch (RuntimeException e) {
            // ⭐ 关系失败不判整体失败：实体已经抽到了，图能画出来（只是没有边）
            log.warn("[图谱] 关系抽取失败 knowledge={} 原因={}", knowledgeId, e.getMessage());
        }

        // ── ③ 权重 / 度数 ─────────────────────────────────────────────
        List<KbGraphWeights.EntityInput> eIn = new ArrayList<>();
        for (Acc a : entities.values()) {
            eIn.add(new KbGraphWeights.EntityInput(a.name, List.copyOf(a.chunkIds)));
        }
        // ⭐ 支撑块为空的边丢弃 · 对齐源侧 graph.go:273-277（{@code findRelationChunkIDs} 返回空 ⇒ skip）。
        //   ⚠️ 源侧该方法实际取的是**并集**（source 或 target 任一实体的 chunkIDs），
        //   与 {@link #relationChunks} 同义；只有「两端实体都没有 chunk」才会为空，
        //   正常抽取不会触发，此处是语义对齐的兜底。
        //   过滤必须同时作用于 relations —— buildRelations 按三元组下标对齐。
        List<KbGraphParser.ExtractedRelation> keptRelations = new ArrayList<>(relations.size());
        List<KbGraphWeights.RelationInput> rIn = new ArrayList<>(relations.size());
        for (KbGraphParser.ExtractedRelation r : relations) {
            List<String> support = relationChunks(r, entities);
            if (support.isEmpty()) {
                log.debug("[图谱] 丢弃无支撑块的关系 {}→{}", r.source(), r.target());
                continue;
            }
            keptRelations.add(r);
            rIn.add(new KbGraphWeights.RelationInput(r.source(), r.target(), r.strength(), support));
        }
        relations = keptRelations;
        List<KbGraphWeights.Result> results = KbGraphWeights.compute(eIn, rIn);

        // ── ④ 落库（一次 replaceGraph：内部先清后写）────────────────────
        //    ⚠️ 不能拆成「先 delete 再 insert 两次」：远程 Neo4j 实现需要在一个
        //    session 里完成清理与写入，否则中途失败会留下「清空了但没写」的空图谱。
        //    抽取失败路径（上面的 catch）不会走到这里，旧数据保持不动。
        List<KbGraphEntityRecord> entityRows =
            buildEntities(kbId, knowledgeId, entities, results, rIn);
        List<KbGraphRelationRecord> relationRows =
            buildRelations(kbId, knowledgeId, relations, rIn, results);
        graphStore.replaceGraph(kbId, knowledgeId, entityRows, relationRows);

        log.info("[图谱] 抽取完成 knowledge={} 实体={} 关系={} 块={}",
            knowledgeId, entities.size(), relations.size(), chunks.size());
        return KbGraphOutcome.done(
            "实体=" + entities.size() + " 关系=" + relations.size());
    }

    // ══════════════════════════════════════════════════════════
    // 落库
    // ══════════════════════════════════════════════════════════

    /**
     * 清掉该文档的旧图谱（逻辑删）。
     *
     * <p>⚠️ 逻辑删而不是物理删：与知识域其余表一致；且部分唯一索引
     * {@code WHERE deleted = 0} 让旧行不挡新行。
     */
    private void clearExisting(String knowledgeId) {
        // ⚠️ 保留此方法只为「只删不写」的调用场景（如抽取失败但需清旧数据）。
        //    正常写入路径已由 KbGraphStore#replaceGraph 内部先清后写。
        graphStore.deleteByKnowledge(knowledgeId);
    }

    private List<KbGraphEntityRecord> buildEntities(
        String kbId, String knowledgeId, Map<String, Acc> entities,
        List<KbGraphWeights.Result> results, List<KbGraphWeights.RelationInput> rIn
    ) {
        List<KbGraphEntityRecord> out = new ArrayList<>();
        String now = KbTimeFormat.now();
        // 度数：从关系里累加（同名实体在多个关系里出现就加多次）
        Map<String, Integer> degree = new LinkedHashMap<>();
        for (KbGraphWeights.RelationInput r : rIn) {
            degree.merge(r.source(), 1 + 1, Integer::sum);
            degree.merge(r.target(), 1 + 1, Integer::sum);
        }
        for (Acc a : entities.values()) {
            KbGraphEntityRecord row = new KbGraphEntityRecord();
            row.setId(UUID.randomUUID().toString());
            row.setKnowledgeBaseId(kbId);
            row.setKnowledgeId(knowledgeId);
            row.setName(a.name);
            row.setEntityType(a.entity.entityType());
            row.setDescription(a.entity.description());
            row.setAttributes(toJson(a.entity.attributes()));
            row.setChunkIds(toJson(List.copyOf(a.chunkIds)));
            row.setFrequency(a.chunkIds.size());
            row.setDegree(degree.getOrDefault(a.name, 0));
            audit(row, now);
            out.add(row);
        }
        return out;
    }

    private List<KbGraphRelationRecord> buildRelations(
        String kbId, String knowledgeId,
        List<KbGraphParser.ExtractedRelation> relations,
        List<KbGraphWeights.RelationInput> rIn,
        List<KbGraphWeights.Result> results
    ) {
        List<KbGraphRelationRecord> out = new ArrayList<>();
        String now = KbTimeFormat.now();
        for (int i = 0; i < relations.size(); i++) {
            KbGraphParser.ExtractedRelation r = relations.get(i);
            KbGraphWeights.Result res = results.get(i);
            KbGraphRelationRecord row = new KbGraphRelationRecord();
            row.setId(UUID.randomUUID().toString());
            row.setKnowledgeBaseId(kbId);
            row.setKnowledgeId(knowledgeId);
            row.setSourceName(r.source());
            row.setTargetName(r.target());
            row.setRelationType(r.type());
            row.setDescription(r.description());
            row.setStrength(r.strength());
            row.setWeight(res.weight());
            row.setCombinedDegree(res.combinedDegree());
            row.setChunkIds(toJson(List.copyOf(rIn.get(i).chunkIds())));
            audit(row, now);
            out.add(row);
        }
        return out;
    }

    /** 审计六列必须显式赋值（Flex 会把 null 也列进 INSERT ⇒ DB DEFAULT 不生效）。 */
    private static void audit(KbGraphEntityRecord row, String now) {
        row.setCreator("");
        row.setUpdater("");
        row.setDeleted(0);
        row.setTenantId(0L);
        row.setCreateTime(KbTimeFormat.parse(now));
        row.setUpdateTime(KbTimeFormat.parse(now));
    }

    private static void audit(KbGraphRelationRecord row, String now) {
        row.setCreator("");
        row.setUpdater("");
        row.setDeleted(0);
        row.setTenantId(0L);
        row.setCreateTime(KbTimeFormat.parse(now));
        row.setUpdateTime(KbTimeFormat.parse(now));
    }

    // ══════════════════════════════════════════════════════════
    // 内部
    // ══════════════════════════════════════════════════════════

    /** 累加器：同名实体跨块合并。 */
    private static final class Acc {
        final String name;
        final KbGraphParser.ExtractedEntity entity;
        final Set<String> chunkIds = new LinkedHashSet<>();

        Acc(KbGraphParser.ExtractedEntity e) {
            this.name = e.name();
            this.entity = e;
        }
    }

    /** 取参与抽取的块（text / image_ocr / image_caption，按 chunkIndex 升序）。 */
    private List<KbChunkRecord> graphChunksOf(String knowledgeId) {
        List<KbChunkRecord> rows = chunkMapper.selectListByQuery(
            QueryWrapper.create()
                .where("knowledge_id = ?", knowledgeId)
                .and("is_enabled = ?", 1)
                .orderBy("chunk_index", true));
        List<KbChunkRecord> out = new ArrayList<>();
        for (KbChunkRecord c : rows) {
            String t = c.getChunkType();
            if ("text".equals(t) || "image_ocr".equals(t) || "image_caption".equals(t)) {
                out.add(c);
            }
        }
        return out;
    }

    /** 关系的支撑块 = 两端实体出现块的并集（源侧 {@code findRelationChunkIDs}）。 */
    private static List<String> relationChunks(
        KbGraphParser.ExtractedRelation r, Map<String, Acc> entities
    ) {
        Set<String> out = new LinkedHashSet<>();
        Acc s = entities.get(r.source());
        Acc t = entities.get(r.target());
        if (s != null) {
            out.addAll(s.chunkIds);
        }
        if (t != null) {
            out.addAll(t.chunkIds);
        }
        return List.copyOf(out);
    }

    /** 拼全文（关系抽取用）。 */
    private static String mergeChunks(List<KbChunkRecord> chunks) {
        StringBuilder sb = new StringBuilder();
        for (KbChunkRecord c : chunks) {
            if (c.getContent() == null || c.getContent().isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(c.getContent().trim());
            if (sb.length() >= MAX_RELATION_CONTENT_CHARS) {
                break;
            }
        }
        return clip(sb.toString(), MAX_RELATION_CONTENT_CHARS);
    }

    /**
     * 送给关系抽取的实体 JSON：只带 title/type/description。
     *
     * <p>⚠️ 属性与 chunk 列表不传 —— 既占 token 又与「判关系」无关。
     */
    private static String entitiesJsonOf(java.util.Collection<Acc> accs) {
        List<Map<String, String>> list = new ArrayList<>(accs.size());
        for (Acc a : accs) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("title", a.name);
            m.put("type", a.entity.entityType());
            m.put("description", a.entity.description());
            list.add(m);
        }
        return toJson(list);
    }

    /** 截到指定 code point 数（emoji 安全）。 */
    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        int len = s.codePointCount(0, s.length());
        return len <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }

    private static String toJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("图谱数据序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析「模型 ID / 名」→ 配置 + 真实模型名。
     *
     * <p>⚠️ 必须整份返回 {@link ModelConfigResolver.ResolvedModel}：调用点要的是
     * {@code apiModelName}（= {@code models.name}），而库里存的是 {@code models.id}。
     * 只带 config 出去，调用点就只好把 ID 当名字发，实测被对方 400 拒绝。
     */
    private ModelConfigResolver.ResolvedModel resolveModel(String modelName) {
        if (modelConfigResolver == null) {
            return null;
        }
        try {
            return modelConfigResolver.resolve(modelName);
        } catch (RuntimeException e) {
            log.warn("[图谱] provider 配置解析失败 model={} 原因={}", modelName, e.getMessage());
            return null;
        }
    }

    /**
     * 抽取调用选项。
     *
     * <p>⚠️ 2026-11-12 <b>不再传 max_tokens</b>（传 null）：输出上限唯一入口 =
     * 模型配置页「生成参数 · max tokens」（{@code models.max_tokens}）。
     * 原写死的 4000 导致 288 实体的大文档输出被截断 ⇒ 关系全部丢失（11-12 千岛湖高铁缺陷）。
     */
    private static LlmProvider.ChatRequestOptions options(String label) {
        return new LlmProvider.ChatRequestOptions(
            null, null, null,
            // 抽取是「按格式搬运事实」，不需要推理链
            LlmProvider.ChatRequestOptions.ThinkingConfig.disabled(),
            TEMPERATURE, label, null, null, null, null,
            null, null, null, null, null, null);
    }
}
