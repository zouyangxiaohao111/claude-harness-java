package com.nexusai.kb.app.qa;

import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.kb.app.retrieval.KbHybridSearcher;
import com.nexusai.kb.repo.entity.KbChunkRecord;
import com.nexusai.kb.repo.entity.KbGraphEntityRecord;
import com.nexusai.kb.repo.entity.KbGraphRelationRecord;
import com.nexusai.kb.app.graph.store.KbGraphStore;
import com.nexusai.kb.repo.mapper.KbChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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

    /**
     * 单次检索的种子实体上限（对齐源站 {@code graphSearchMaxSeedNodes = 200}）。
     *
     * <p>⭐ 为什么必须有它：匹配是<b>子串</b>（{@code LIKE '%x%'}）。抽出一个宽泛词
     * （「公司」「项目」）会把库里几乎所有实体捞进来，每个再带出若干块。
     * 源站注释记录了真实事故：<i>「without a bound this returned 1309 nodes / 1917
     * relations for a single query, which then exceeded the reranker's per-request limit
     * and made chunk_merge walk the entire set」</i>。
     */
    static final int MAX_SEED_NODES = 200;

    /**
     * 一次检索最多展开的关系行数（对齐源站 {@code graphSearchMaxRows = 2000}）。
     *
     * <p>即使种子只有 200 个，hub 实体（度 36 的实测值）的邻域也可能很大。
     */
    static final int MAX_ROWS = 2000;

    /**
     * 一次检索最多带进上下文的块数（对齐源站 {@code maxEntityChunks = 30}）。
     *
     * <p>⚠️ 这是<b>总量</b>上限，不是每实体上限 —— 源站没有 per-entity 限制，
     * 按节点顺序先到先得。此处照抄（⛔ 不要自己加一层 per-entity 上限，
     * 那会让「排在后面的实体永远拿不到块」，与源站行为不一致）。
     */
    static final int MAX_TOTAL_CHUNKS = 30;

    /**
     * {@link #mergeByChunk} 的单实体上限（仅该纯函数/单测使用）。
     *
     * <p>⚠️ 生产主路径（{@code searchOneKb}）已改为「总量 30」以对齐源站
     * {@code maxEntityChunks}，⛔ 不要把这个 per-entity 上限加回生产路径 ——
     * 那会让排在后面的实体永远拿不到块。
     */
    static final int MAX_CHUNKS_PER_ENTITY = 5;

    /**
     * 图谱存储（本地 SQLite 或远程 Neo4j，由 {@code KbGraphStore} 决定）。
     *
     * <p>⭐ 2026-11-11 抽接口：本类原来直连 {@code KbGraphEntityMapper} /
     * {@code KbGraphRelationMapper}，接远程 Neo4j 时这里必须跟着改。
     * 现在只依赖 {@link KbGraphStore} 的两个读方法（{@code listEntities} /
     * {@code listRelations}），两种实现都能用。
     */
    private final KbGraphStore graphStore;
    private final KbChunkMapper chunkMapper;

    public KbQaEntitySearch(KbGraphStore graphStore, KbChunkMapper chunkMapper) {
        this.graphStore = graphStore;
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
        // ── ① 子串匹配候选实体 ────────────────────────────────────────
        List<KbGraphEntityRecord> matched = queryMatchedEntities(kbId, names);
        if (matched.isEmpty()) {
            return;
        }

        // ── ② 本库关系表：既用来判「有无关系」，也用来求一跳邻居 ─────────
        //    源侧 Cypher 里 `EXISTS { (n)--() }` 与 `MATCH (n)-[r]-(m)` 都来自同一张边集，
        //    所以一次读全库关系即可（本库实体量远小于全库，且已有 try/catch fail-soft）。
        List<KbGraphRelationRecord> rels = queryRelations(kbId);
        Set<String> related = new HashSet<>();
        Map<String, List<String>> neighbours = new HashMap<>();
        for (KbGraphRelationRecord r : rels) {
            String src = r.getSourceName();
            String dst = r.getTargetName();
            if (src == null || src.isBlank() || dst == null || dst.isBlank()) {
                continue;
            }
            related.add(src);
            related.add(dst);
            neighbours.computeIfAbsent(src, k -> new ArrayList<>()).add(dst);
            neighbours.computeIfAbsent(dst, k -> new ArrayList<>()).add(src);
        }
        if (related.isEmpty()) {
            // 整库一条边都没有 ⇒ 实体路必然无结果（源侧 EXISTS 子句同样返回空）
            return;
        }

        // ── ③ 种子实体：只留「有关系」的，按源侧 ORDER BY 排序后截 200 ────
        //    源侧：`ORDER BY seed_rank, name_len, name, n.kg, elementId(n) LIMIT 200`。
        //    前三级（精确名 → 名字短 → 字典序）决定「谁进 200」，后两级（kg / 元素 id）
        //    只是同前三级的确定性兜底 —— 缺了它们，同名前缀的实体每次顺序可能不同。
        Set<String> exact = new LinkedHashSet<>(names);
        Comparator<KbGraphEntityRecord> order = Comparator
            .comparingInt((KbGraphEntityRecord e) -> exact.contains(e.getName()) ? 0 : 1)
            .thenComparingInt(e -> e.getName() == null ? Integer.MAX_VALUE : e.getName().length())
            .thenComparing(e -> nz(e.getName()))
            .thenComparing(e -> nz(e.getKnowledgeId()))
            .thenComparing(e -> nz(e.getId()));
        List<KbGraphEntityRecord> seeds = new ArrayList<>();
        for (KbGraphEntityRecord e : matched) {
            if (e == null || e.getName() == null || !related.contains(e.getName())) {
                continue;
            }
            seeds.add(e);
        }
        seeds.sort(order);
        boolean seedsTruncated = seeds.size() > MAX_SEED_NODES;
        if (seedsTruncated) {
            seeds = new ArrayList<>(seeds.subList(0, MAX_SEED_NODES));
        }
        if (seeds.isEmpty()) {
            return;
        }

        // ── ④ 节点顺序：种子先（按上面的排序），再按种子顺序展开邻居 ─────────
        //    源侧 decodeGraphSearchResult 把 `n`（种子）和 `m`（邻居）**都**放进 graph.Node，
        //    filterSeenChunk 随后遍历 `graph.Node[].Chunks` ⇒ 邻居实体**自己的块**也会被取用。
        //    ⚠️ 我们此前只取「关系行上的 chunk_ids」，与源站不等价：邻居实体在别的文档里
        //       反复出现时（自身 chunks 多），关系行只记「这条边在哪抽出来的」（通常 1 个）
        //       ⇒ 召回系统性偏少。此轮改为取节点自身的 chunks。
        LinkedHashSet<String> nodeOrder = new LinkedHashSet<>();
        for (KbGraphEntityRecord seed : seeds) {
            nodeOrder.add(seed.getName());
        }
        int rowBudget = MAX_ROWS;
        boolean rowsTruncated = false;
        for (KbGraphEntityRecord seed : seeds) {
            List<String> ns = neighbours.get(seed.getName());
            if (ns == null) {
                continue;
            }
            for (String n : ns) {
                if (rowBudget <= 0) {
                    rowsTruncated = true;
                    break;
                }
                rowBudget--;
                nodeOrder.add(n);
            }
            if (rowsTruncated) {
                break;
            }
        }

        // ── ⑤ 取所有节点（种子 + 邻居）各自的 chunks ─────────────────────
        //    种子行已经在手（matched 里），只有邻居需要补查。
        Set<String> seedNames = new LinkedHashSet<>();
        for (KbGraphEntityRecord seed : seeds) {
            seedNames.add(seed.getName());
        }
        Map<String, List<String>> chunksByNode = new LinkedHashMap<>();
        for (KbGraphEntityRecord seed : seeds) {
            List<String> ids = parseList(seed.getChunkIds());
            if (!ids.isEmpty()) {
                chunksByNode.put(seed.getName(), ids);
            }
        }
        List<String> neighbourNames = new ArrayList<>();
        for (String n : nodeOrder) {
            if (!seedNames.contains(n)) {
                neighbourNames.add(n);
            }
        }
        if (!neighbourNames.isEmpty()) {
            for (KbGraphEntityRecord nb : queryEntitiesByNames(kbId, neighbourNames)) {
                if (nb == null || nb.getName() == null || seedNames.contains(nb.getName())) {
                    continue;
                }
                List<String> ids = parseList(nb.getChunkIds());
                if (!ids.isEmpty()) {
                    chunksByNode.putIfAbsent(nb.getName(), ids);
                }
            }
        }

        // ── ⑥ 按节点顺序收集块，总量封顶 30（源侧 maxEntityChunks）─────────
        Map<String, List<String>> viaByChunk = new LinkedHashMap<>();
        int budget = MAX_TOTAL_CHUNKS;
        boolean chunksTruncated = false;
        for (String node : nodeOrder) {
            List<String> ids = chunksByNode.get(node);
            if (ids == null) {
                continue;
            }
            for (String cid : ids) {
                if (cid == null || cid.isBlank()) {
                    continue;
                }
                if (viaByChunk.containsKey(cid)) {
                    // 同一块被多个节点带出 ⇒ 记下全部来源（分数仍为 0）
                    List<String> via = viaByChunk.get(cid);
                    if (!via.contains(node)) {
                        via.add(node);
                    }
                    continue;
                }
                if (budget <= 0) {
                    chunksTruncated = true;
                    break;
                }
                budget--;
                List<String> via = new ArrayList<>(1);
                via.add(node);
                viaByChunk.put(cid, via);
            }
            if (chunksTruncated) {
                break;
            }
        }
        if (viaByChunk.isEmpty()) {
            return;
        }

        // ── ⑦ 回填块内容（只收「启用且属于本库」的块）─────────────────────
        Map<String, KbChunkRecord> rows;
        try {
            List<KbChunkRecord> got = chunkMapper.selectListByQuery(
                QueryWrapper.create().in("id", viaByChunk.keySet()));
            rows = new HashMap<>();
            for (KbChunkRecord c : got) {
                if (c == null || c.getId() == null) {
                    continue;
                }
                // ⚠️ 图谱里可能残留已删/已禁用块的 id（源侧同样在 filterSeenChunk 后校验 IsEnabled）
                if (isDisabled(c)) {
                    continue;
                }
                if (c.getKnowledgeBaseId() != null && !kbId.equals(c.getKnowledgeBaseId())) {
                    continue;
                }
                rows.put(c.getId(), c);
            }
        } catch (RuntimeException ex) {
            log.warn("[问答链] ④c 回填块失败，跳过该库 kb={} 原因={}", kbId, ex.getMessage());
            return;
        }

        for (Map.Entry<String, List<String>> e : viaByChunk.entrySet()) {
            KbChunkRecord c = rows.get(e.getKey());
            if (c == null) {
                continue;  // 块已删/禁用 ⇒ 静默跳过
            }
            EntityHit prev = byChunk.get(c.getId());
            if (prev == null) {
                byChunk.put(c.getId(), new EntityHit(
                    c.getId(), c.getKnowledgeId(), c.getContent(), c.getContextHeader(),
                    0.0, new ArrayList<>(e.getValue())));
            } else {
                // 跨库/跨实体重复命中 ⇒ 合并来源，分数仍为 0
                List<String> via = new ArrayList<>(prev.viaEntities());
                for (String v : e.getValue()) {
                    if (!via.contains(v)) {
                        via.add(v);
                    }
                }
                byChunk.put(c.getId(), new EntityHit(prev.chunkId(), prev.knowledgeId(),
                    prev.content(), prev.contextHeader(), 0.0, via));
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[问答链] ④c 实体检索 kb={} 匹配={} 种子={}{} 节点={} 块={}{}",
                kbId, matched.size(), seeds.size(),
                seedsTruncated ? "(截断自 " + matched.size() + "，上限 " + MAX_SEED_NODES + ")" : "",
                nodeOrder.size(), viaByChunk.size(),
                (rowsTruncated || chunksTruncated) ? "（达到上限，已截断）" : "");
        }
    }

    /**
     * 子串匹配候选实体（源侧 {@code n.name CONTAINS nodeText}）。失败 ⇒ 空表。
     *
     * <p>⭐ 2026-11-11 抽接口后改为「取该库全量实体 + 内存子串过滤」：
     * <ul>
     *   <li><b>为什么不再让 SQL 做 LIKE</b>：远程 Neo4j 实现也要遵守同一语义，
     *       若在这里用 SQL 通配符，Neo4j 那边就得重写一遍匹配逻辑，两边容易分叉；</li>
     *   <li><b>代价可控</b>：本库实体数在千级（实测 253），全量拉回再过滤远低于一次往返开销；
     *       真正的边界是种子上限 200，与这里无关。</li>
     * </ul>
     * ⚠️ 仍保留 {@link #escapeLike} 的语义对照 —— 内存过滤天然无通配符问题。
     */
    private List<KbGraphEntityRecord> queryMatchedEntities(String kbId, List<String> names) {
        List<String> needles = new ArrayList<>();
        for (String n : names) {
            if (n != null && !n.isBlank()) {
                needles.add(n.trim());
            }
        }
        if (needles.isEmpty()) {
            return List.of();
        }
        List<KbGraphEntityRecord> all;
        try {
            all = graphStore.listEntities(kbId);
        } catch (RuntimeException ex) {
            log.warn("[问答链] ④c 查实体失败，跳过该库 kb={} 原因={}", kbId, ex.getMessage());
            return List.of();
        }
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<KbGraphEntityRecord> out = new ArrayList<>();
        for (KbGraphEntityRecord e : all) {
            if (e == null || e.getName() == null) {
                continue;
            }
            for (String needle : needles) {
                if (e.getName().contains(needle)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 按精确名字取实体（用于取邻居节点自身的 chunks）。失败 ⇒ 空表。
     *
     * <p>同上：从 store 取全量后在内存里按名字集合过滤，保证两种存储实现语义一致。
     */
    private List<KbGraphEntityRecord> queryEntitiesByNames(String kbId, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new HashSet<>(names);
        List<KbGraphEntityRecord> all;
        try {
            all = graphStore.listEntities(kbId);
        } catch (RuntimeException ex) {
            // 拿不到邻居块不影响「种子实体自己的块」这一路
            log.warn("[问答链] ④c 查邻居实体失败 kb={} 原因={}", kbId, ex.getMessage());
            return List.of();
        }
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<KbGraphEntityRecord> out = new ArrayList<>();
        for (KbGraphEntityRecord e : all) {
            if (e != null && e.getName() != null && wanted.contains(e.getName())) {
                out.add(e);
            }
        }
        return out;
    }

    /** 本库全部关系（判「有无关系」+ 求一跳邻居共用）。失败 ⇒ 空表。 */
    private List<KbGraphRelationRecord> queryRelations(String kbId) {
        try {
            List<KbGraphRelationRecord> got = graphStore.listRelations(kbId);
            return got == null ? List.of() : got;
        } catch (RuntimeException ex) {
            log.warn("[问答链] ④c 查关系失败 kb={} 原因={}", kbId, ex.getMessage());
            return List.of();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }


    /**
     * 把「实体名 → 块 id 列表」压成「块 id → 经由哪些实体」。
     *
     * <p>⭐ 抽成纯函数是因为这里有三个容易写错的点：
     * <ol>
     *   <li><b>跨实体去重</b>：同一块被两个实体带出时只保留一条命中，但要把两个实体都记下来
     *       （否则排查时看不出「为什么这块进来了」）；</li>
     *   <li><b>保序</b>：顺序 = 实体出现顺序 + 组内块顺序，⛔ 用 HashMap 会打乱；</li>
     *   <li><b>上限</b>：单实体最多 {@link #MAX_CHUNKS_PER_ENTITY} 块（此函数只服务单测/兜底，
 *       生产主路径已改为 {@code MAX_TOTAL_CHUNKS} 总量上限，对齐源侧 maxEntityChunks）。</li>
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
