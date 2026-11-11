package com.nexusai.kb.app.graph.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.kb.repo.entity.KbGraphEntityRecord;
import com.nexusai.kb.repo.entity.KbGraphRelationRecord;
import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import com.nexusai.infra.util.CryptoUtil;
import jakarta.annotation.PreDestroy;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图谱存储路由 · <b>按知识库</b>决定用本地 SQLite 还是远程 Neo4j。
 *
 * <h2>⭐ 为什么需要这一层（2026-11-11 用户要求）</h2>
 * 「图谱是知识库里面的东西」—— 每个知识库可以独立选择图谱存在哪：
 * A 库用本地、B 库用自己公司的 Neo4j。配置在
 * {@code kb_knowledge_base.graph_store_config}（V281），每库一份。
 *
 * <h2>路由规则</h2>
 * <pre>
 * graph_store_config 缺省 / "{}" / store=local  → 本地 SQLite（默认，零行为变化）
 * store=neo4j                                  → 远程 Neo4j（连不上直接抛，⛔ 不回落）
 * </pre>
 *
 * <h2>⚠️ 连接按库缓存，配置变了必须重建</h2>
 * Neo4j {@link Driver} 是重量级对象（内部有连接池），不能每次调用都新建。
 * 这里按 {@code knowledgeBaseId → 实例} 缓存，并用「配置指纹」判断是否需要重建：
 * 用户把 uri 从 A 改到 B 时，指纹变化 ⇒ 关掉旧驱动、建新驱动。
 * ⛔ 不能只按 kbId 缓存 —— 那样改了配置不重启永远用旧连接。
 *
 * <h2>⛔ 连不上不回落（用户明确要求）</h2>
 * 配了 {@code store=neo4j} 但连不上 ⇒ 抛异常。静默用本地会让「数据到底在哪」
 * 无法对账，比直接失败更糟。
 */
@Component
@Primary
public class KbGraphStoreRouter implements KbGraphStore {

    private static final Logger log = LoggerFactory.getLogger(KbGraphStoreRouter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 本地实现（永远可用）。 */
    private final LocalSqliteGraphStore localStore;
    private final KbKnowledgeBaseMapper knowledgeBaseMapper;
    private final CryptoUtil cryptoUtil;

    /**
     * 每库的远程实例缓存：kbId → (配置指纹 + store)。
     *
     * <p>⚠️ key 用 kbId；value 里带指纹，指纹不匹配就重建（改配置后立刻生效）。
     */
    private final Map<String, CachedRemote> remotes = new ConcurrentHashMap<>();

    /** 一台远程实例（含创建它的配置指纹）。 */
    private record CachedRemote(String fingerprint, Neo4jDriverBacked store) {
    }

    /**
     * 生产构造器。
     *
     * @param localStore          本地实现（{@code @Primary} 不作用于构造参数，显式注入具体类型）
     * @param knowledgeBaseMapper 读每库的 {@code graph_store_config}
     * @param cryptoUtil          解密 {@code password_encrypted}
     */
    @Autowired
    public KbGraphStoreRouter(LocalSqliteGraphStore localStore,
                              KbKnowledgeBaseMapper knowledgeBaseMapper,
                              @Autowired(required = false) CryptoUtil cryptoUtil) {
        this.localStore = localStore;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.cryptoUtil = cryptoUtil;
    }

    @PreDestroy
    void closeAll() {
        for (CachedRemote r : remotes.values()) {
            r.store().close();
        }
        remotes.clear();
    }

    // ── 路由核心 ─────────────────────────────────────────────────────

    /**
     * 取该库应使用的存储实现。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 本地或远程实现（永不 null；远程连不上 ⇒ 抛）
     */
    KbGraphStore resolveFor(String knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return localStore;
        }
        GraphStoreConfig cfg = readConfig(knowledgeBaseId);
        if (!cfg.isNeo4j()) {
            return localStore;
        }
        CachedRemote cached = remotes.get(knowledgeBaseId);
        if (cached != null && cached.fingerprint().equals(cfg.fingerprint())) {
            return cached.store();
        }
        // 配置变了（或首次）⇒ 重建。旧驱动必须先关，否则连接池泄漏
        if (cached != null) {
            log.info("[图谱存储] 配置变更，重建连接 kb={}", knowledgeBaseId);
            cached.store().close();
        }
        Neo4jDriverBacked fresh = Neo4jDriverBacked.open(knowledgeBaseId, cfg);
        remotes.put(knowledgeBaseId, new CachedRemote(cfg.fingerprint(), fresh));
        return fresh;
    }

    /** 读某库的图谱存储配置（缺省 ⇒ 本地）。 */
    private GraphStoreConfig readConfig(String knowledgeBaseId) {
        if (knowledgeBaseMapper == null) {
            return GraphStoreConfig.LOCAL;
        }
        try {
            KbKnowledgeBaseRecord row = knowledgeBaseMapper.selectOneById(knowledgeBaseId);
            return GraphStoreConfig.parse(row == null ? null : row.getGraphStoreConfig(), cryptoUtil);
        } catch (RuntimeException e) {
            // 读配置失败 ⇒ 当作未配置（本地）。这是「读不到配置」而不是「配了远程却连不上」，
            // 后者才是必须报错的场景。
            log.warn("[图谱存储] 读取 graph_store_config 失败，按本地处理 kb={} 原因={}",
                knowledgeBaseId, e.getMessage());
            return GraphStoreConfig.LOCAL;
        }
    }

    // ── KbGraphStore 委托 ───────────────────────────────────────────

    @Override
    public void replaceGraph(String knowledgeBaseId, String knowledgeId,
                             List<KbGraphEntityRecord> entities,
                             List<KbGraphRelationRecord> relations) {
        resolveFor(knowledgeBaseId).replaceGraph(knowledgeBaseId, knowledgeId, entities, relations);
    }

    /**
     * 按文档删 —— 需要先知道它属于哪个库才能路由。
     *
     * <p>⚠️ 不能只按本地删：远程库的图谱不在本地表里，只删本地等于没删。
     * 这里走「库 → 实现」路由；库查不到（文档已删）⇒ 两边都尝试删（幂等）。
     */
    @Override
    public void deleteByKnowledge(String knowledgeId) {
        if (knowledgeId == null || knowledgeId.isBlank()) {
            return;
        }
        // 本地永远删（绝大多数库是本地；多删一次无副作用）
        localStore.deleteByKnowledge(knowledgeId);
        // 远程：对每个已建立的远程连接也删一次
        for (Map.Entry<String, CachedRemote> e : remotes.entrySet()) {
            try {
                e.getValue().store().deleteByKnowledge(knowledgeId);
            } catch (RuntimeException ex) {
                log.warn("[图谱存储] 远程删除失败 kb={} knowledge={} 原因={}",
                    e.getKey(), knowledgeId, ex.getMessage());
            }
        }
    }

    @Override
    public List<KbGraphEntityRecord> listEntities(String knowledgeBaseId) {
        return resolveFor(knowledgeBaseId).listEntities(knowledgeBaseId);
    }

    @Override
    public List<KbGraphRelationRecord> listRelations(String knowledgeBaseId) {
        return resolveFor(knowledgeBaseId).listRelations(knowledgeBaseId);
    }

    // ── 配置模型 ─────────────────────────────────────────────────────

    /**
     * 每库的图谱存储配置。
     *
     * @param store         "local" / "neo4j"
     * @param uri           Neo4j bolt 地址
     * @param username      用户名
     * @param password      明文密码（解密后；用于建连接，⛔ 不落日志）
     */
    record GraphStoreConfig(String store, String uri, String username, String password) {

        static final GraphStoreConfig LOCAL = new GraphStoreConfig("local", "", "", "");

        boolean isNeo4j() {
            return "neo4j".equalsIgnoreCase(store == null ? "" : store.trim());
        }

        /**
         * 配置指纹 · 任一字段变化都要重建连接。
         *
         * <p>⚠️ 密码参与指纹：改密码后不重建会导致新密码不生效。
         */
        String fingerprint() {
            return store + "|" + uri + "|" + username + "|" + password;
        }

        /**
         * 解析 JSON。
         *
         * @param raw        {@code kb_knowledge_base.graph_store_config}
         * @param cryptoUtil 解密密码（可为 null ⇒ 视为无密码）
         */
        static GraphStoreConfig parse(String raw, CryptoUtil cryptoUtil) {
            if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
                return LOCAL;
            }
            try {
                JsonNode n = JSON.readTree(raw);
                if (n == null || !n.isObject()) {
                    return LOCAL;
                }
                String store = textOf(n, "store");
                if (!"neo4j".equalsIgnoreCase(store)) {
                    return LOCAL;
                }
                String uri = textOf(n, "uri");
                String user = textOf(n, "username");
                String enc = textOf(n, "password_encrypted");
                String plain = "";
                if (!enc.isEmpty() && cryptoUtil != null) {
                    try {
                        String d = cryptoUtil.decrypt(enc);
                        plain = d == null ? "" : d;
                    } catch (RuntimeException e) {
                        // 解不开（换过密钥）⇒ 当作空密码，让连接失败暴露出来
                        // ⛔ 不要假装成功
                        log.warn("[图谱存储] 解密 Neo4j 密码失败（密钥变过？）原因={}", e.getMessage());
                    }
                }
                return new GraphStoreConfig(store, uri, user, plain);
            } catch (Exception e) {
                log.warn("[图谱存储] graph_store_config 不是合法 JSON，按本地处理：{}", e.getMessage());
                return LOCAL;
            }
        }

        private static String textOf(JsonNode n, String field) {
            JsonNode v = n.get(field);
            return v == null || v.isNull() ? "" : v.asText("").trim();
        }
    }

    /** 仅供测试：当前缓存了几个远程实例。 */
    int remoteCacheSize() {
        return remotes.size();
    }

    /** 仅供测试：注入一个已知指纹的缓存项，避免真连 Neo4j。 */
    void putForTest(String kbId, String fingerprint, Neo4jDriverBacked store) {
        remotes.put(kbId, new CachedRemote(fingerprint, store));
    }
}
