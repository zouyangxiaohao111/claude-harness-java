package com.nexusai.kb.app.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbGraphEntityRecord;
import com.nexusai.kb.repo.entity.KbGraphRelationRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.app.graph.store.KbGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图谱检索 · 对齐 WeKnora
 * {@code internal/application/service/graph_search.go Search} 的**契约形状**，
 * 检索策略按本仓能力重做（源侧是 Neo4j 全文/向量检索，本仓是名字匹配 + 邻域扩展）。
 *
 * <h2>返回形状逐字对齐源侧 {@code GraphSearchResult}</h2>
 * {@code entities / relationships / source_chunks / meta} 四个字段与源侧同名同形，
 * 前端 `graphTypes.ts` 里那份 TS 契约可以直接对接。
 *
 * <h2>⭐⭐ 三条语义（照抄源侧，别顺手改）</h2>
 * <ol>
 *   <li><b>关系必须两端都在本次返回的 entities 里</b>。源侧注释写明
 *       "{@code connects two returned, same-document entities}"。
 *       返回一条指向没返回的实体的边，前端画不出来（悬空边）。</li>
 *   <li><b>{@code source_chunks} 是受限投影</b>，不含文件路径 / metadata / 图片 URL。
 *       它是给前端展示证据用的，泄露内部路径没有价值只有风险。</li>
 *   <li><b>{@code meta.truncated} 必须如实上报</b>。截断而不说，用户会以为
 *       「这个知识库就只有这么多实体」。</li>
 * </ol>
 *
 * <p><b>查询为空 ⇒ 返回整个库的图谱</b>（源侧要求 query 非空；本仓放宽，
 * 因为星图页默认就是要看全库概览）。这条差异写在这里，避免以后被当成 bug 改掉。
 */
@Service
public class KbGraphSearchService {

    private static final Logger log = LoggerFactory.getLogger(KbGraphSearchService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认返回上限（源侧同类限制的量级）。 */
    public static final int DEFAULT_MAX_ENTITIES = 200;
    public static final int DEFAULT_MAX_RELATIONS = 400;
    public static final int DEFAULT_MAX_SOURCE_CHUNKS = 100;

    /** 上限的硬顶（防止一次请求把整库拉穿）。 */
    private static final int HARD_MAX_ENTITIES = 500;
    private static final int HARD_MAX_RELATIONS = 1000;
    private static final int HARD_MAX_SOURCE_CHUNKS = 300;

    /** 证据正文截断长度。 */
    private static final int SOURCE_CONTENT_MAX_CHARS = 400;

    /**
     * 图谱存储（本地 SQLite 或远程 Neo4j）。
     *
     * <p>⭐ 2026-11-11 抽接口：本类原来直连两个 mapper。现在读全量后在内存里排序 ——
     * 排序必须在这里做（而不是依赖 SQL 的 {@code ORDER BY}），否则远程 Neo4j 实现
     * 得再实现一遍同样的排序，两边容易分叉。
     */
    private final KbGraphStore graphStore;
    private final KbChunkMapper chunkMapper;

    public KbGraphSearchService(KbGraphStore graphStore, KbChunkMapper chunkMapper) {
        this.graphStore = graphStore;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 图谱检索请求。
     *
     * @param query          查询文本（空 ⇒ 全库）
     * @param maxEntities    实体上限（null ⇒ 默认）
     * @param maxRelations   关系上限（null ⇒ 默认）
     * @param maxSourceChunks 证据块上限（null ⇒ 默认）
     */
    public record Request(
        String query, Integer maxEntities, Integer maxRelations, Integer maxSourceChunks
    ) {
    }

    /**
     * 检索结果 · 形状对齐源侧 {@code GraphSearchResult}。
     *
     * @param knowledgeBaseId 知识库
     * @param query           原查询
     * @param entities        实体
     * @param relationships   关系
     * @param sourceChunks    证据块
     * @param meta            截断信息
     */
    public record Result(
        String knowledgeBaseId, String query,
        List<Entity> entities, List<Relation> relationships,
        List<SourceChunk> sourceChunks, Meta meta
    ) {
    }

    /** 实体 · 对齐源侧 {@code GraphSearchEntity}。 */
    public record Entity(
        String id, String name, String knowledgeId,
        List<String> attributes, List<String> sourceChunkIds, String entityType
    ) {
    }

    /** 关系 · 对齐源侧 {@code GraphSearchRelation}。 */
    public record Relation(
        String id, String sourceId, String targetId, String type,
        List<String> sourceChunkIds, double weight, int strength
    ) {
    }

    /** 证据块 · 对齐源侧 {@code GraphSearchSourceChunk}（受限投影）。 */
    public record SourceChunk(
        String id, String knowledgeId, String title, String content,
        int chunkIndex, int startAt, int endAt,
        boolean contentTruncated, boolean titleTruncated
    ) {
    }

    /** 截断信息 · 对齐源侧 {@code GraphSearchMeta}。 */
    public record Meta(
        boolean truncated, List<String> truncationReasons,
        int maxNodes, int maxRelations, int maxSourceChunks
    ) {
    }

    /**
     * 执行检索。
     *
     * @param kbId    知识库 ID
     * @param request 请求
     * @return 结果（永不 null）
     */
    public Result search(String kbId, Request request) {
        Request req = request == null ? new Request(null, null, null, null) : request;
        int maxEntities = clamp(req.maxEntities(), DEFAULT_MAX_ENTITIES, HARD_MAX_ENTITIES);
        int maxRelations = clamp(req.maxRelations(), DEFAULT_MAX_RELATIONS, HARD_MAX_RELATIONS);
        int maxChunks = clamp(req.maxSourceChunks(), DEFAULT_MAX_SOURCE_CHUNKS, HARD_MAX_SOURCE_CHUNKS);

        String query = req.query() == null ? "" : req.query().trim();
        Set<String> reasons = new LinkedHashSet<>();

        // ── ① 选实体 ─────────────────────────────────────────────────
        // 度数/频次降序（原 SQL ORDER BY degree DESC, frequency DESC 的等价物）。
        // ⚠️ 在内存里排：store 接口不承诺顺序，两种实现都不能依赖 SQL 排序。
        List<KbGraphEntityRecord> all = new ArrayList<>(graphStore.listEntities(kbId));
        all.sort(java.util.Comparator
            .comparingInt((KbGraphEntityRecord e) -> e.getDegree() == null ? 0 : e.getDegree())
            .thenComparingInt(e -> e.getFrequency() == null ? 0 : e.getFrequency())
            .reversed());

        List<KbGraphEntityRecord> picked = new ArrayList<>();
        if (query.isEmpty()) {
            // 全库概览：按度数/频次降序取前 N
            for (KbGraphEntityRecord e : all) {
                if (picked.size() >= maxEntities) {
                    reasons.add("entities");
                    break;
                }
                picked.add(e);
            }
        } else {
            // 名字匹配（大小写不敏感的子串）+ 描述命中的兜底
            String q = query.toLowerCase(Locale.ROOT);
            for (KbGraphEntityRecord e : all) {
                if (picked.size() >= maxEntities) {
                    reasons.add("entities");
                    break;
                }
                String name = e.getName() == null ? "" : e.getName().toLowerCase(Locale.ROOT);
                String desc = e.getDescription() == null ? "" : e.getDescription().toLowerCase(Locale.ROOT);
                if (name.contains(q) || desc.contains(q)) {
                    picked.add(e);
                }
            }
            // ⭐ 一个都没命中 ⇒ 退化为「按度数取前 N」，⛔ 不返回空
            //    （用户只是没打对名字，不等于这个库没有图谱）
            if (picked.isEmpty()) {
                reasons.add("query_no_match_fallback");
                for (KbGraphEntityRecord e : all) {
                    if (picked.size() >= maxEntities) {
                        break;
                    }
                    picked.add(e);
                }
            }
        }

        Map<String, KbGraphEntityRecord> byName = new HashMap<>();
        Map<String, String> nameToId = new HashMap<>();
        for (KbGraphEntityRecord e : picked) {
            byName.put(e.getName(), e);
            nameToId.put(e.getName(), e.getId());
        }

        // ── ② 选关系（两端必须都在 picked 里）─────────────────────────
        // 权重降序（原 SQL ORDER BY weight DESC 的等价物），同样在内存里排。
        List<KbGraphRelationRecord> allRel = new ArrayList<>(graphStore.listRelations(kbId));
        allRel.sort(java.util.Comparator.comparingDouble(
            (KbGraphRelationRecord r) -> r.getWeight() == null ? 0.0 : r.getWeight()).reversed());
        List<Relation> relations = new ArrayList<>();
        for (KbGraphRelationRecord r : allRel) {
            if (relations.size() >= maxRelations) {
                reasons.add("relationships");
                break;
            }
            // ⭐ 两端都要在本次返回的实体里，否则前端会画出悬空边
            String sid = nameToId.get(r.getSourceName());
            String tid = nameToId.get(r.getTargetName());
            if (sid == null || tid == null) {
                continue;
            }
            relations.add(new Relation(
                r.getId(), sid, tid,
                r.getRelationType() == null ? "" : r.getRelationType(),
                parseList(r.getChunkIds()),
                r.getWeight() == null ? 1.0 : r.getWeight(),
                r.getStrength() == null ? 5 : r.getStrength()));
        }

        // ── ③ 证据块 ──────────────────────────────────────────────────
        Set<String> wanted = new LinkedHashSet<>();
        for (KbGraphEntityRecord e : picked) {
            for (String cid : parseList(e.getChunkIds())) {
                if (wanted.size() >= maxChunks) {
                    reasons.add("source_chunks");
                    break;
                }
                wanted.add(cid);
            }
        }
        List<SourceChunk> sources = new ArrayList<>();
        if (!wanted.isEmpty()) {
            List<KbChunkRecord> chunks = chunkMapper.selectListByQuery(
                QueryWrapper.create().in("id", wanted));
            // ⚠️ 按 wanted 的顺序输出（select 的返回顺序不保证）
            Map<String, KbChunkRecord> chunkById = new HashMap<>();
            for (KbChunkRecord c : chunks) {
                chunkById.put(c.getId(), c);
            }
            for (String cid : wanted) {
                KbChunkRecord c = chunkById.get(cid);
                if (c == null) {
                    // 块可能已被删/重解析 ⇒ 静默跳过（证据块缺失不是错误）
                    continue;
                }
                String content = c.getContent() == null ? "" : c.getContent();
                int cpLen = content.codePointCount(0, content.length());
                boolean contentTrunc = cpLen > SOURCE_CONTENT_MAX_CHARS;
                if (contentTrunc) {
                    content = content.substring(0,
                        content.offsetByCodePoints(0, SOURCE_CONTENT_MAX_CHARS));
                }
                sources.add(new SourceChunk(
                    c.getId(), c.getKnowledgeId(), "", content,
                    c.getChunkIndex() == null ? 0 : c.getChunkIndex(),
                    c.getStartAt() == null ? 0 : c.getStartAt(),
                    c.getEndAt() == null ? 0 : c.getEndAt(),
                    contentTrunc, false));
            }
        }

        // ── ④ 组装 ────────────────────────────────────────────────────
        List<Entity> entities = new ArrayList<>(picked.size());
        for (KbGraphEntityRecord e : picked) {
            entities.add(new Entity(
                e.getId(), e.getName(), e.getKnowledgeId(),
                parseList(e.getAttributes()), parseList(e.getChunkIds()),
                e.getEntityType() == null ? KbGraphPrompts.DEFAULT_ENTITY_TYPE : e.getEntityType()));
        }

        List<String> reasonList = new ArrayList<>(reasons);
        // 排序后上报，便于前端稳定展示与测试断言
        reasonList.sort(String::compareTo);

        log.info("[图谱检索] kb={} 查询={} 实体={} 关系={} 证据={} 截断={}",
            kbId, query.isEmpty() ? "(全库)" : query, entities.size(), relations.size(),
            sources.size(), reasonList);

        return new Result(kbId, query,
            List.copyOf(entities), List.copyOf(relations), List.copyOf(sources),
            new Meta(!reasonList.isEmpty(), List.copyOf(reasonList),
                maxEntities, maxRelations, maxChunks));
    }

    /**
     * 解析 JSON 字符串数组（脏数据 ⇒ 空列表）。
     *
     * <p>⚠️ 这类列表来自库里的 TEXT 列，可能被手工改坏 ⇒ 必须 fail-soft，
     * 否则一条坏记录会让整个图谱接口 500。
     */
    static List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            var n = JSON.readTree(json);
            if (n == null || !n.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(n.size());
            for (var v : n) {
                String s = v.isTextual() ? v.asText() : v.toString();
                if (s != null && !s.isBlank()) {
                    out.add(s);
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int clamp(Integer given, int fallback, int hardMax) {
        int v = given == null ? fallback : given;
        if (v <= 0) {
            v = fallback;
        }
        return Math.min(v, hardMax);
    }
}
