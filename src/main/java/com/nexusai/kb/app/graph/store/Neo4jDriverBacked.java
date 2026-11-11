package com.nexusai.kb.app.graph.store;

import com.nexusai.kb.repo.entity.KbGraphEntityRecord;
import com.nexusai.kb.repo.entity.KbGraphRelationRecord;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一台远程 Neo4j 实例的存储实现（<b>非 Spring bean</b>，由
 * {@link KbGraphStoreRouter} 按库创建与关闭）。
 *
 * <h2>为什么不做成 Spring bean</h2>
 * 配置是<b>每库一份</b>（用户明确「图谱是知识库里面的」），而 Spring bean 是单例。
 * 若做成 bean，就只能有一个全局连接 —— 无法满足「A 库本地、B 库远程」。
 * ⇒ 由路由按库实例化，配置变了就关旧的建新的。
 *
 * <h2>Cypher 与源站的对应</h2>
 * 写入照抄源站 {@code neo4j/repository.go:76-103}：
 * <pre>
 * UNWIND $data AS row
 * CALL apoc.merge.node(row.labels, {name, kg}, row.props, {}) YIELD node
 * SET node.chunks = apoc.coll.union(node.chunks, row.chunks)
 * </pre>
 * ⚠️ <b>远程实例必须装 APOC 插件</b>（{@code apoc.merge.node} / {@code apoc.merge.relationship}）。
 * 没装 ⇒ 写入直接报错，不是静默失败。
 *
 * <h2>命名空间 = 节点标签</h2>
 * 源站用 {@code ENTITY<kb_id>} / {@code ENTITY<knowledge_id>}（连字符换下划线）做标签，
 * 本类照抄 ⇒ 同一台 Neo4j 上源站与本仓的数据不会互相干扰。
 */
final class Neo4jDriverBacked implements KbGraphStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Neo4jDriverBacked.class);

    /** 标签前缀（源站 {@code nodePrefix = "ENTITY"}）。 */
    private static final String LABEL_PREFIX = "ENTITY";

    private final String knowledgeBaseId;
    private final Driver driver;

    private Neo4jDriverBacked(String knowledgeBaseId, Driver driver) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.driver = driver;
    }

    /**
     * 建连接（⛔ 连不上直接抛，不回落本地 —— 用户明确要求）。
     *
     * @param knowledgeBaseId 知识库 id（日志与标签用）
     * @param cfg             配置（已解密密码）
     * @throws IllegalStateException 连不上 / 配置不全
     */
    static Neo4jDriverBacked open(String knowledgeBaseId, KbGraphStoreRouter.GraphStoreConfig cfg) {
        if (cfg.uri() == null || cfg.uri().isBlank()) {
            throw new IllegalStateException(
                "知识库 " + knowledgeBaseId + " 配了 graph_store_config.store=neo4j 但没填 uri"
                    + " —— 请补上 uri/username/password，或把 store 改回 local");
        }
        Driver driver = GraphDatabase.driver(cfg.uri(), AuthTokens.basic(
            cfg.username() == null || cfg.username().isBlank() ? "neo4j" : cfg.username(),
            cfg.password() == null ? "" : cfg.password()));
        try {
            driver.verifyConnectivity();
            log.info("[图谱存储·Neo4j] 已连接 kb={} uri={} user={}",
                knowledgeBaseId, cfg.uri(), cfg.username());
            return new Neo4jDriverBacked(knowledgeBaseId, driver);
        } catch (RuntimeException e) {
            driver.close();
            // ⛔ 不回落本地：配了远程却静默用本地，会让「数据到底在哪」无法对账
            throw new IllegalStateException("[图谱存储·Neo4j] 连接失败 kb=" + knowledgeBaseId
                + " uri=" + cfg.uri() + " —— 图谱功能不可用（不会回落到本地表）。原因: "
                + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            driver.close();
        } catch (RuntimeException e) {
            log.warn("[图谱存储·Neo4j] 关闭驱动失败（忽略）kb={} 原因={}",
                knowledgeBaseId, e.getMessage());
        }
    }

    // ── 写入 ────────────────────────────────────────────────────────

    @Override
    public void replaceGraph(String knowledgeBaseId, String knowledgeId,
                             List<KbGraphEntityRecord> entities,
                             List<KbGraphRelationRecord> relations) {
        // 先删该文档旧图（与本地实现同语义：替换而非追加）
        deleteByKnowledge(knowledgeId);

        List<String> labels = labelsOf(knowledgeBaseId, knowledgeId);
        try (Session s = driver.session()) {
            s.executeWrite(tx -> {
                List<Map<String, Object>> nodeData = new ArrayList<>();
                if (entities != null) {
                    for (KbGraphEntityRecord e : entities) {
                        if (e == null || e.getName() == null) {
                            continue;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", e.getName());
                        row.put("knowledge_id", knowledgeId);
                        row.put("chunks", parseJsonArray(e.getChunkIds()));
                        row.put("attributes", parseJsonArray(e.getAttributes()));
                        row.put("degree", e.getDegree() == null ? 0 : e.getDegree());
                        row.put("frequency", e.getFrequency() == null ? 0 : e.getFrequency());
                        nodeData.add(row);
                    }
                }
                if (!nodeData.isEmpty()) {
                    tx.run("UNWIND $data AS row "
                            + "CALL apoc.merge.node($labels, {name: row.name, kg: row.knowledge_id}, "
                            + "  {attributes: row.attributes, degree: row.degree, frequency: row.frequency}, {}) "
                            + "YIELD node "
                            + "SET node.chunks = apoc.coll.union(coalesce(node.chunks, []), row.chunks)",
                        Values.parameters("labels", labels, "data", nodeData));
                }

                List<Map<String, Object>> relData = new ArrayList<>();
                if (relations != null) {
                    for (KbGraphRelationRecord r : relations) {
                        if (r == null || r.getSourceName() == null || r.getTargetName() == null) {
                            continue;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("source", r.getSourceName());
                        row.put("target", r.getTargetName());
                        row.put("type", sanitizeRelType(r.getRelationType()));
                        row.put("description", r.getDescription());
                        row.put("weight", r.getWeight());
                        row.put("chunks", parseJsonArray(r.getChunkIds()));
                        relData.add(row);
                    }
                }
                if (!relData.isEmpty()) {
                    tx.run("UNWIND $data AS row "
                            + "CALL apoc.merge.node($labels, {name: row.source, kg: $kg}, {}, {}) "
                            + "  YIELD node AS src "
                            + "CALL apoc.merge.node($labels, {name: row.target, kg: $kg}, {}, {}) "
                            + "  YIELD node AS tgt "
                            + "CALL apoc.merge.relationship(src, row.type, {}, "
                            + "  {description: row.description, weight: row.weight, chunks: row.chunks}, tgt) "
                            + "  YIELD rel "
                            + "RETURN distinct 'done'",
                        Values.parameters("labels", labels, "kg", knowledgeId, "data", relData));
                }
                return null;
            });
        }
        log.info("[图谱存储·Neo4j] 写入完成 kb={} knowledge={} 实体={} 关系={}",
            knowledgeBaseId, knowledgeId,
            entities == null ? 0 : entities.size(),
            relations == null ? 0 : relations.size());
    }

    @Override
    public void deleteByKnowledge(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return;
        }
        try (Session s = driver.session()) {
            // 按 kg 属性删（比按标签删更精确：同标签下可能有别的文档）
            // ⚠️ 分批：hub 文档可能有上万个节点，一次性 DETACH DELETE 会撑爆事务内存
            s.executeWrite(tx -> {
                tx.run("MATCH (n) WHERE n.kg = $kg "
                        + "CALL (n) { DETACH DELETE n } IN TRANSACTIONS OF 1000 ROWS",
                    Values.parameters("kg", knowledgeId));
                return null;
            });
        }
    }

    // ── 读取 ────────────────────────────────────────────────────────

    @Override
    public List<KbGraphEntityRecord> listEntities(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return List.of();
        }
        List<KbGraphEntityRecord> out = new ArrayList<>();
        try (Session s = driver.session()) {
            s.executeRead(tx -> {
                var result = tx.run("MATCH (n:" + labelOf(knowledgeBaseId) + ") "
                        + "WHERE n.kg IS NOT NULL "
                        + "RETURN n.name AS name, n.kg AS kg, n.chunks AS chunks, "
                        + "       n.attributes AS attributes, n.degree AS degree, "
                        + "       n.frequency AS frequency");
                while (result.hasNext()) {
                    var rec = result.next();
                    KbGraphEntityRecord row = new KbGraphEntityRecord();
                    // Neo4j 没有关系表主键 ⇒ 用「库+名」派生稳定 id（同一轮查询内一致即可）
                    row.setId(knowledgeBaseId + "/" + rec.get("name").asString());
                    row.setKnowledgeBaseId(knowledgeBaseId);
                    row.setKnowledgeId(rec.get("kg").isNull() ? null : rec.get("kg").asString());
                    row.setName(rec.get("name").asString());
                    row.setChunkIds(toJson(rec.get("chunks")));
                    row.setAttributes(toJson(rec.get("attributes")));
                    row.setDegree(rec.get("degree").isNull() ? 0 : rec.get("degree").asInt(0));
                    row.setFrequency(rec.get("frequency").isNull() ? 0 : rec.get("frequency").asInt(0));
                    out.add(row);
                }
                return null;
            });
        }
        return out;
    }

    @Override
    public List<KbGraphRelationRecord> listRelations(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return List.of();
        }
        List<KbGraphRelationRecord> out = new ArrayList<>();
        try (Session s = driver.session()) {
            String label = labelOf(knowledgeBaseId);
            s.executeRead(tx -> {
                var result = tx.run("MATCH (a:" + label + ")-[r]->(b:" + label + ") "
                        + "WHERE a.kg IS NOT NULL "
                        + "RETURN a.name AS src, b.name AS dst, type(r) AS relType, "
                        + "       r.description AS description, r.weight AS weight, "
                        + "       r.chunks AS chunks, a.kg AS kg");
                while (result.hasNext()) {
                    var rec = result.next();
                    KbGraphRelationRecord row = new KbGraphRelationRecord();
                    row.setKnowledgeBaseId(knowledgeBaseId);
                    row.setKnowledgeId(rec.get("kg").isNull() ? "" : rec.get("kg").asString());
                    row.setSourceName(rec.get("src").asString());
                    row.setTargetName(rec.get("dst").asString());
                    row.setRelationType(rec.get("relType").asString());
                    row.setDescription(rec.get("description").isNull()
                        ? null : rec.get("description").asString());
                    row.setWeight(rec.get("weight").isNull()
                        ? null : Double.valueOf(rec.get("weight").asDouble(0)));
                    row.setChunkIds(toJson(rec.get("chunks")));
                    out.add(row);
                }
                return null;
            });
        }
        return out;
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /** 源站 {@code Labels()} 的等价物：{@code ENTITY<id>}，连字符换下划线。 */
    static List<String> labelsOf(String knowledgeBaseId, String knowledgeId) {
        List<String> out = new ArrayList<>(2);
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
            out.add(LABEL_PREFIX + knowledgeBaseId.replace("-", "_"));
        }
        if (knowledgeId != null && !knowledgeId.isBlank()) {
            out.add(LABEL_PREFIX + knowledgeId.replace("-", "_"));
        }
        return out;
    }

    /** 单个知识库标签（查询用）。 */
    static String labelOf(String knowledgeBaseId) {
        return LABEL_PREFIX + knowledgeBaseId.replace("-", "_");
    }

    /**
     * 关系类型名净化。
     *
     * <p>⚠️ Cypher 的关系类型是<b>标识符</b>，不能带空格/中文标点 ⇒ 非法字符换成下划线。
     * 空 ⇒ {@code RELATED}。⛔ 不能把原始字符串直接拼进 Cypher（注入面 + 语法错）。
     */
    static String sanitizeRelType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "RELATED";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '_';
            sb.append(ok ? c : '_');
        }
        String out = sb.toString();
        // 首字符不能是数字（Cypher 标识符规则）
        if (out.isEmpty() || Character.isDigit(out.charAt(0))) {
            return "RELATED_" + out;
        }
        return out;
    }

    /** 解析 JSON 字符串数组（脏数据 ⇒ 空表）。 */
    private static List<String> parseJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>(node.size());
            for (var v : node) {
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    out.add(v.asText());
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Neo4j list 值 → JSON 字符串数组。 */
    private static String toJson(org.neo4j.driver.Value v) {
        List<String> list = new ArrayList<>();
        if (v != null && !v.isNull()) {
            try {
                for (Object o : v.asList()) {
                    if (o != null) {
                        list.add(String.valueOf(o));
                    }
                }
            } catch (RuntimeException e) {
                // 属性不是列表 ⇒ 空表（⛔ 不让脏数据让整次检索失败）
            }
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
