package com.nexusai.kb.app.qa;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbGraphEntityRecord;
import com.nexusai.kb.repo.entity.KbGraphRelationRecord;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import com.nexusai.kb.repo.mapper.KbGraphEntityMapper;
import com.nexusai.kb.repo.mapper.KbGraphRelationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阶段 4c · 实体检索（对齐源侧 {@code chat_pipeline/search_entity.go}）。
 *
 * <h2>它在补什么</h2>
 * 关键词/向量两路都是「文本相似」。用户问「施工单位的安全生产责任」，
 * 若文档里写的是「承包方职责」，两路都可能漏。而图谱里
 * 「施工单位 —[承担]→ 安全生产责任」这条边是结构性证据，不依赖字面重合。
 *
 * <h2>⭐ 三条必须照抄的语义</h2>
 * <ol>
 *   <li><b>分数固定 0</b>：实体路命中是「图上有关系」，不是「文本像」——
 *       给它编一个分数会污染融合排序。源侧同样不给分，靠后续重排把它提上来。</li>
 *   <li><b>只一跳</b>：源侧也只从实体直接关联的块取，不做两跳扩展
 *       （两跳会把整张图拉进来，上下文直接爆）。</li>
 *   <li><b>失败不阻断</b>：图没抽过 / 实体不存在 / 块已删 ⇒ 只是这一路没结果。</li>
 * </ol>
 *
 * <h2>⚠️ 与阶段 7 的耦合</h2>
 * 这里产出的 {@code score=0} 命中，在阶段 7「按分降序截 topK」时会被排到最末。
 * 源侧对此有明确豁免：<b>分数 0 的 graph 命中不被截</b>（见 {@code merge.go} 第 1 步）。
 * 本类的 {@link KbHybridSearcher.Hit} 没有 graph 标记位，只能靠 score==0 识别，
 * ⇒ 阶段 7 的截断逻辑必须把 score==0 视为 graph 命中保留。这一点写在那边。
 */
@Component
public class KbQaEntitySearch {

    private static final Logger log = LoggerFactory.getLogger(KbQaEntitySearch.class);

    /** 单个实体最多带出的证据块数（防高度数实体把上下文灌满）。 */
    static final int MAX_CHUNKS_PER_ENTITY = 5;

    private final KbGraphEntityMapper entityMapper;
    private final KbGraphRelationMapper relationMapper;
    private final KbChunkMapper chunkMapper;

    public KbQaEntitySearch(
        KbGraphEntityMapper entityMapper,
        KbGraphRelationMapper relationMapper,
        KbChunkMapper chunkMapper
    ) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 一条实体检索命中。
     *
     * @param chunkId     块 ID
     * @param knowledgeId 所属文档
     * @param content     正文
     * @param contextHeader 表头
     * @param score       恒为 0（见类注释）
     * @param viaEntities 由哪些实体带出来的（日志/排查用）
     */
    public record EntityHit(
        String chunkId, String knowledgeId, String content, String contextHeader,
        double score, List<String> viaEntities
    ) {
    }

    /**
     * 按「库 → 实体名」查关联块。
     *
     * @param entitiesByKb 阶段 3 的产出（空 ⇒ 立即返回空，不查库）
     * @param maxTotal     总条数上限（&lt;= 0 ⇒ 不限制）
     * @return 命中（按实体名顺序、组内按 chunkIndex）；永不 null、永不抛
     */
    public List<EntityHit> search(Map<String, List<String>> entitiesByKb, int maxTotal) {
        if (entitiesByKb == null || entitiesByKb.isEmpty()) {
            return List.of();
        }
        Map<String, EntityHit> byChunk = new LinkedHashMap<>();
        int truncated = 0;
        for (Map.Entry<String, List<String>> e : entitiesByKb.entrySet()) {
            String kbId = e.getKey();
            if (kbId == null || kbId.isBlank() || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            int before = byChunk.size();
            searchOneKb(kbId, e.getValue(), byChunk);
            if (maxTotal > 0 && byChunk.size() > maxTotal) {
                truncated += byChunk.size() - maxTotal;
                // 保序截断：LinkedHashMap 的迭代顺序 = 实体顺序，截掉的是后面的实体带出的
                List<String> keep = new ArrayList<>(byChunk.keySet()).subList(0, maxTotal);
                byChunk.keySet().retainAll(new LinkedHashSet<>(keep));
                break;
            }
            if (log.isDebugEnabled()) {
                log.debug("[问答链] ④c 实体检索 kb={} 实体数={} 新增块={}",
                    kbId, e.getValue().size(), byChunk.size() - before);
            }
        }
        List<EntityHit> out = new ArrayList<>(byChunk.values());
        log.info("[问答链] ④c 实体检索 库数={} 命中块={} 截断={}",
            entitiesByKb.size(), out.size(), truncated);
        return out;
    }

    /**
     * 在一个库里按实体名查关联块。
     *
     * <h2>⭐⭐ 匹配语义必须对齐源侧：{@code CONTAINS}，不是等值</h2>
     * 源侧 Neo4j {@code graphSearchCypher}（{@code repository.go:328}）：
     * <pre>
     * WHERE ANY(nodeText IN $nodes WHERE n.name CONTAINS nodeText)
     * ORDER BY CASE WHEN n.name IN $nodes THEN 0 ELSE 1 END, ...   // 精确名优先
     * </pre>
     * 即「库内实体名**包含**抽取出的文本」，且<b>完全相等者排前面</b>。
     *
     * <p><b>为什么不能等值</b>：查询期抽的是<b>问题里出现的说法</b>（「农村生活污水处理设施
     * 覆盖率要求」），库内实体是<b>文档里的短名</b>（「农村生活污水处理设施」）。
     * 两者天然不同形 —— 等值匹配在真机上是<b>恒定 0 命中</b>
     * （2026-11-06 3499 实测：抽取 3 个实体、库内 22 个实体、命中 0 块），
     * 而且不报错、不告警，只是「实体路永远没结果」。
     *
     * <p>⚠️ 反向不成立：不用「抽取文本 CONTAINS 库内名」。源侧写的是
     * {@code n.name CONTAINS nodeText}（库内名 ⊇ 查询文本），方向照抄。
     *
     * @param kbId  知识库 id
     * @param names 查询期抽出的实体名
     * @param byChunk 跨库累积的命中表（块 id → 命中）
     */
    /**
     * LIKE 通配符转义：实体名里出现 {@code %} / {@code _} 时不该匹配一切。
     *
     * <p>包级可见是为了让单测直接钉住转义结果 —— 通过完整 {@code search()} 测不出来
     * （要 mock 整条 mapper 链，反而弱化判别力）。
     */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private void searchOneKb(String kbId, List<String> names, Map<String, EntityHit> byChunk) {
        List<KbGraphEntityRecord> entities;
        try {
            // name LIKE '%<query>%' OR ... ：SQL 骨架只用 '?' 占位（⛔ 不插用户文本，无注入面）
            StringBuilder sql = new StringBuilder("(");
            List<Object> args = new ArrayList<>();
            for (String n : names) {
                if (n == null || n.isBlank()) {
                    continue;
                }
                if (!args.isEmpty()) {
                    sql.append(" OR ");
                }
                sql.append("name LIKE ?");
                args.add("%" + escapeLike(n) + "%");
            }
            sql.append(")");
            if (args.isEmpty()) {
                // 全部名字为空 ⇒ 没有可查条件。⛔ 不能拼出 "()"（SQL 语法错）；
                //    调用方已保证 names 非空，走到这里只可能是全空白，直接当作无命中。
                return;
            }
            QueryWrapper q = QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId)
                .and(sql.toString(), args.toArray());
            entities = entityMapper.selectListByQuery(q);
        } catch (RuntimeException ex) {
            log.warn("[问答链] ④c 查实体失败，跳过该库 kb={} 原因={}", kbId, ex.getMessage());
            return;
        }
        if (entities == null || entities.isEmpty()) {
            return;
        }
        // ⭐ 精确名优先（源侧 ORDER BY seed_rank）：完全相等的实体排在前面，
        //   保证「高分证据」先占 chunkIds 的插入顺序 ⇒ 阶段 8 保序截断时先留下。
        Set<String> exact = new LinkedHashSet<>(names);
        entities.sort((a, b) -> {
            boolean ea = a != null && exact.contains(a.getName());
            boolean eb = b != null && exact.contains(b.getName());
            if (ea != eb) {
                return ea ? -1 : 1;   // 精确的在前
            }
            // 同档内按名字长度升序（源侧 name_len）：短名更具体，先取
            int la = a == null || a.getName() == null ? Integer.MAX_VALUE : a.getName().length();
            int lb = b == null || b.getName() == null ? Integer.MAX_VALUE : b.getName().length();
            return Integer.compare(la, lb);
        });
        // 名字 → 该实体直接关联的块
        Map<String, List<String>> chunkIds = new LinkedHashMap<>();
        for (KbGraphEntityRecord e : entities) {
            if (e == null) {
                continue;
            }
            List<String> ids = parseList(e.getChunkIds());
            if (ids.size() > MAX_CHUNKS_PER_ENTITY) {
                ids = new ArrayList<>(ids.subList(0, MAX_CHUNKS_PER_ENTITY));
            }
            if (!ids.isEmpty()) {
                chunkIds.put(e.getName(), ids);
            }
        }
        // 关系两端都在本次实体里 ⇒ 把关系挂的块也带进来（源侧同一跳语义）
        try {
            List<KbGraphRelationRecord> rels = relationMapper.selectListByQuery(QueryWrapper.create()
                .where("knowledge_base_id = ?", kbId));
            Set<String> known = chunkIds.keySet();
            for (KbGraphRelationRecord r : rels) {
                if (r == null) {
                    continue;
                }
                boolean hit = known.contains(r.getSourceName()) || known.contains(r.getTargetName());
                if (!hit) {
                    continue;
                }
                List<String> ids = parseList(r.getChunkIds());
                if (ids.isEmpty()) {
                    continue;
                }
                String via = known.contains(r.getSourceName()) ? r.getSourceName() : r.getTargetName();
                chunkIds.computeIfAbsent(via, k -> new ArrayList<>()).addAll(ids);
            }
        } catch (RuntimeException ex) {
            // 关系表读失败不影响「实体直接关联块」这一路
            log.warn("[问答链] ④c 查关系失败，只用实体块 kb={} 原因={}", kbId, ex.getMessage());
        }
        if (chunkIds.isEmpty()) {
            return;
        }

        // 收集所有待回填的块 id
        Set<String> wanted = new LinkedHashSet<>();
        for (List<String> ids : chunkIds.values()) {
            wanted.addAll(ids);
        }
        Map<String, KbChunkRecord> rows;
        try {
            List<KbChunkRecord> got = chunkMapper.selectListByQuery(
                QueryWrapper.create().in("id", wanted));
            rows = new HashMap<>();
            for (KbChunkRecord c : got) {
                if (c != null && c.getId() != null) {
                    // ⚠️ 只收「启用且属于本库」的块：图谱里可能残留已删/已禁用块的 id
                    if (isDisabled(c)) {
                        continue;
                    }
                    if (c.getKnowledgeBaseId() != null && !kbId.equals(c.getKnowledgeBaseId())) {
                        continue;
                    }
                    rows.put(c.getId(), c);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("[问答链] ④c 回填块失败，跳过该库 kb={} 原因={}", kbId, ex.getMessage());
            return;
        }

        for (Map.Entry<String, List<String>> e : chunkIds.entrySet()) {
            for (String cid : e.getValue()) {
                KbChunkRecord c = rows.get(cid);
                if (c == null) {
                    continue;  // 块已删/禁用 ⇒ 静默跳过
                }
                EntityHit prev = byChunk.get(cid);
                if (prev == null) {
                    byChunk.put(cid, new EntityHit(
                        c.getId(), c.getKnowledgeId(), c.getContent(), c.getContextHeader(),
                        0.0, new ArrayList<>(List.of(e.getKey()))));
                } else if (!prev.viaEntities().contains(e.getKey())) {
                    // 同一块被多个实体带出 ⇒ 记录全部来源，分数仍为 0
                    List<String> via = new ArrayList<>(prev.viaEntities());
                    via.add(e.getKey());
                    byChunk.put(cid, new EntityHit(prev.chunkId(), prev.knowledgeId(),
                        prev.content(), prev.contextHeader(), 0.0, via));
                }
            }
        }
    }

    /**
     * 把「实体名 → 块 id 列表」压成「块 id → 经由哪些实体」。
     *
     * <p>⭐ 抽成纯函数是因为这里有三个容易写错的点：
     * <ol>
     *   <li><b>跨实体去重</b>：同一块被两个实体带出时只保留一条命中，但要把两个实体都记下来
     *       （否则排查时看不出「为什么这块进来了」）；</li>
     *   <li><b>保序</b>：顺序 = 实体出现顺序 + 组内块顺序，⛔ 用 HashMap 会打乱；</li>
     *   <li><b>上限</b>：单实体最多 {@link #MAX_CHUNKS_PER_ENTITY} 块，防高度数实体灌满上下文。</li>
     * </ol>
     *
     * @param chunkIdsByEntity 实体名 → 关联块 id（可含重复/空）
     * @return 块 id → 实体名列表（保序）
     */
    static Map<String, List<String>> mergeByChunk(Map<String, List<String>> chunkIdsByEntity) {
        Map<String, List<String>> byChunk = new LinkedHashMap<>();
        if (chunkIdsByEntity == null || chunkIdsByEntity.isEmpty()) {
            return byChunk;
        }
        for (Map.Entry<String, List<String>> e : chunkIdsByEntity.entrySet()) {
            String entity = e.getKey();
            if (entity == null || entity.isBlank() || e.getValue() == null) {
                continue;
            }
            List<String> ids = e.getValue();
            int used = 0;
            for (String cid : ids) {
                if (cid == null || cid.isBlank()) {
                    continue;
                }
                if (used >= MAX_CHUNKS_PER_ENTITY) {
                    break;
                }
                used++;
                List<String> via = byChunk.computeIfAbsent(cid, k -> new ArrayList<>());
                if (!via.contains(entity)) {
                    via.add(entity);
                }
            }
        }
        return byChunk;
    }

    /** 块是否已禁用（{@code is_enabled == 0}）。 */
    private static boolean isDisabled(KbChunkRecord c) {
        return c.getIsEnabled() != null && c.getIsEnabled() == 0;
    }

    /**
     * 解析 JSON 字符串数组（脏数据 ⇒ 空列表）。
     *
     * <p>⚠️ 这些列可能被手工改坏 ⇒ 必须 fail-soft，⛔ 不能一条坏记录让整轮问答失败。
     */
    private static List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            if (n == null || !n.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(n.size());
            for (com.fasterxml.jackson.databind.JsonNode v : n) {
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    out.add(v.asText().trim());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 转成检索层命中（供阶段 5/7 统一处理）。
     *
     * <p>⚠️ {@code score} 恒 0 —— 阶段 7 的截断逻辑依赖这个值识别 graph 命中。
     */
    public static List<KbHybridSearcher.Hit> toHits(List<EntityHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<KbHybridSearcher.Hit> out = new ArrayList<>(hits.size());
        for (EntityHit h : hits) {
            out.add(new KbHybridSearcher.Hit(
                h.chunkId(), h.knowledgeId(), h.content(), h.contextHeader(),
                0.0, 0, 0));
        }
        return out;
    }
}
