package com.nexusai.kb.app.config;

import java.util.List;
import java.util.Map;

/**
 * 向量库引擎类型元数据（批次 W8）· {@code GET /vector-stores/types} 的数据源。
 *
 * <p><b>源侧对照</b>：WeKnora {@code types.GetVectorStoreTypes()}
 * （{@code internal/types/vectorstore.go:665}），返回
 * {@code []VectorStoreTypeInfo{Type, DisplayName, ConnectionFields[], IndexFields[]}}，
 * 字段形状 {@code VectorStoreFieldInfo{Name/Type/Required/Sensitive/Default/Enum/Description}}。
 *
 * <p><b>⭐ 为什么只有 7 种而不是 9 种</b>：
 * 源侧 {@code validEngineTypes}（{@code :79-88}）明确<b>排除</b> postgres 与 sqlite，
 * 注释原文：这两个引擎「only meaningful when bound to the app's default DB connection…
 * the Postgres retriever's embeddings table is a single hard-coded name with no per-store
 * partitioning, so registering a second Postgres store on the same instance has no separation
 * effect」。它们走 {@code RETRIEVE_DRIVER} 环境变量那条路（{@code BuildEnvVectorStores}），
 * 不经过本校验。
 *
 * <p>⚠️ 本仓的 W1 sqlite 驱动属于「类型层」，不走这张表 —— 见 {@link KbVectorStoreRecord} 的说明。
 *
 * <p><b>字段元数据为什么要给默认值</b>：前端编辑器用它预填表单。
 * ⛔ 不要把这些默认值当成「服务端兜底」：真正写库的是用户提交的值，
 * 缺省时由各驱动的实现自己决定（我们不在这里替驱动做决定）。
 */
public final class KbVectorStoreTypes {

    private KbVectorStoreTypes() {
    }

    /**
     * 一个配置字段的元数据。
     *
     * @param name        字段名（JSON 键）
     * @param type        字段类型：{@code string} / {@code number} / {@code boolean}
     * @param required    是否必填
     * @param sensitive   是否敏感（密码类）。⚠️ 读取时按此标记脱敏，写入时原样存
     * @param defaultValue 编辑器预填值（可空）
     * @param enumValues  封闭取值空间（可空）
     * @param description 说明
     * @param immutable   建后不可改（源侧 HNSW 参数属于此类）
     * @param min         数值下界（可空）
     * @param max         数值上界（可空）
     */
    public record Field(
        String name,
        String type,
        boolean required,
        boolean sensitive,
        Object defaultValue,
        List<String> enumValues,
        String description,
        boolean immutable,
        Double min,
        Double max
    ) {
        /** 普通字符串字段。 */
        public static Field str(String name, boolean required, String def, String desc) {
            return new Field(name, "string", required, false, def, null, desc, false, null, null);
        }

        /** 必填字符串字段。 */
        public static Field requiredStr(String name, String def, String desc) {
            return new Field(name, "string", true, false, def, null, desc, false, null, null);
        }

        /** 敏感字符串字段（密码/密钥）。 */
        public static Field secret(String name, String desc) {
            return new Field(name, "string", false, true, null, null, desc, false, null, null);
        }

        /** 必填敏感字段。 */
        public static Field requiredSecret(String name, String desc) {
            return new Field(name, "string", true, true, null, null, desc, false, null, null);
        }

        /** 数值字段。 */
        public static Field num(String name, Number def, String desc) {
            return new Field(name, "number", false, false, def, null, desc, false, null, null);
        }

        /** 带上下界的数值字段。 */
        public static Field num(String name, Number def, String desc, double min, double max) {
            return new Field(name, "number", false, false, def, null, desc, false, min, max);
        }

        /** 建后不可改的数值字段。 */
        public static Field immutableNum(String name, Number def, String desc, double min, double max) {
            return new Field(name, "number", false, false, def, null, desc, true, min, max);
        }

        /** 布尔字段。 */
        public static Field bool(String name, boolean def, String desc) {
            return new Field(name, "boolean", false, false, def, null, desc, false, null, null);
        }

        /** 枚举字段。 */
        public static Field enumOf(String name, String def, List<String> values, String desc, boolean immutable) {
            return new Field(name, "string", false, false, def, values, desc, immutable, null, null);
        }
    }

    /** 一种引擎的类型元数据。 */
    public record TypeInfo(
        String type,
        String displayName,
        List<Field> connectionFields,
        List<Field> indexFields
    ) {
    }

    /** 可注册为实例的引擎类型（7 种）。 */
    private static final Map<String, TypeInfo> TYPES = buildTypes();

    private static Map<String, TypeInfo> buildTypes() {
        Map<String, TypeInfo> m = new java.util.LinkedHashMap<>();

        m.put("elasticsearch", new TypeInfo("elasticsearch", "Elasticsearch",
            List.of(
                Field.requiredStr("addr", "http://localhost:9200", "URL"),
                Field.str("username", false, "elastic", "Username"),
                Field.secret("password", "Password")),
            List.of(
                Field.str("index_name", false, "weknora", "Index Name"),
                Field.num("number_of_shards", 4, "Shards"),
                Field.num("number_of_replicas", 1, "Replicas"))));

        m.put("qdrant", new TypeInfo("qdrant", "Qdrant",
            List.of(
                Field.requiredStr("host", "localhost", "Host"),
                Field.num("port", 6334, "Port"),
                Field.secret("api_key", "API Key"),
                Field.bool("use_tls", false, "Use TLS")),
            List.of(
                Field.str("collection_prefix", false, "weknora_embeddings", "Collection Prefix"),
                Field.num("shard_number", 1, "Shard Number"),
                Field.num("replication_factor", 1, "Replication Factor"))));

        m.put("milvus", new TypeInfo("milvus", "Milvus",
            List.of(
                Field.requiredStr("addr", "localhost:19530", "Address"),
                Field.str("database", false, null, "Database Name"),
                Field.str("username", false, "root", "Username"),
                Field.secret("password", "Password")),
            List.of(
                Field.str("collection_name", false, "weknora_embeddings", "Collection Name"),
                Field.num("shards_num", 1, "Shards (write parallelism)"),
                Field.num("replica_number", 1, "In-memory Replicas (read HA)"))));

        m.put("tencent_vectordb", new TypeInfo("tencent_vectordb", "Tencent VectorDB",
            List.of(
                Field.requiredStr("addr", "http://localhost:8080", "Address"),
                Field.requiredStr("username", null, "Username"),
                Field.requiredSecret("api_key", "API Key"),
                Field.str("database", false, "weknora", "Database")),
            List.of(
                Field.str("collection_name", false, "weknora_embeddings", "Collection Name"),
                Field.num("shards_num", 1, "Shards"),
                // ⚠️ 源侧这个默认值来自环境变量（resolveTencentVectorDBReplicaNumber）；
                //    本仓没有该环境变量 ⇒ 用 1，与源侧「解析失败」时的回落一致。
                Field.num("replica_number", 1, "Replicas"))));

        m.put("weaviate", new TypeInfo("weaviate", "Weaviate",
            List.of(
                Field.requiredStr("host", "weaviate:8080", "Host"),
                Field.str("grpc_address", false, "weaviate:50051", "gRPC Address"),
                Field.str("scheme", false, "http", "Scheme"),
                Field.secret("api_key", "API Key")),
            List.of(
                Field.str("collection_prefix", false, "Weknora_embeddings", "Collection Prefix"),
                Field.num("desired_shard_count", 1, "Shard Count"),
                Field.num("replication_factor", 1, "Replication Factor"))));

        m.put("doris", new TypeInfo("doris", "Apache Doris",
            List.of(
                Field.requiredStr("addr", "doris-fe:9030", "FE MySQL Address (host:port)"),
                Field.num("http_port", 8030, "FE HTTP Port (Stream Load)"),
                Field.requiredStr("database", "weknora", "Database"),
                Field.str("username", false, "root", "Username"),
                Field.secret("password", "Password")),
            List.of(
                Field.str("collection_prefix", false, "weknora_embeddings", "Table Prefix"),
                Field.num("buckets_num", 10, "Buckets per table"),
                Field.num("replication_num", 1, "Replication Num"))));

        m.put("opensearch", new TypeInfo("opensearch", "OpenSearch",
            List.of(
                Field.requiredStr("addr", "https://localhost:9200", "URL"),
                Field.str("username", false, "admin", "Username"),
                Field.secret("password", "Password"),
                Field.bool("insecure_skip_verify", false,
                    "Skip TLS certificate verification. For self-signed dev clusters only — "
                        + "never enable in production.")),
            List.of(
                Field.str("index_name", false, "weknora", "Index Name"),
                Field.num("number_of_shards", 4, "Shards", 1, 64),
                Field.num("number_of_replicas", 1, "Replicas", 0, 10),
                Field.immutableNum("hnsw_m", 16,
                    "HNSW graph degree (M). Immutable after index creation.", 2, 100),
                Field.immutableNum("hnsw_ef_construction", 100,
                    "HNSW build candidate list. Higher (e.g. 200-512) improves recall at the cost of "
                        + "build time. Immutable after creation.", 2, 4096),
                Field.immutableNum("hnsw_ef_search", 100,
                    "HNSW search candidate list. Effective on the faiss engine; the lucene engine reads "
                        + "it at query time. Immutable (no settings-update path).", 1, 10000),
                Field.enumOf("knn_engine", "lucene", List.of("lucene", "faiss"), "k-NN backend.", true))));

        return java.util.Collections.unmodifiableMap(m);
    }

    /** 全部类型（按声明顺序，前端下拉框直接按此排列）。 */
    public static List<TypeInfo> all() {
        return List.copyOf(TYPES.values());
    }

    /** 按类型名取元数据；未知返回 {@code null}（⛔ 不抛 —— 列表端点不应因脏数据整包失败）。 */
    public static TypeInfo get(String type) {
        return type == null ? null : TYPES.get(type.trim().toLowerCase());
    }

    /**
     * 该类型能否注册为实例。
     *
     * <p>⚠️ 与 {@link #get} 的区别：{@code get} 只查表，本方法表达「合不合法」的业务判断。
     * 现在两者集合相同，但分开写是为了将来若要放开某个类型时，
     * 只要改这里而不影响元数据返回。
     */
    public static boolean isValid(String type) {
        return get(type) != null;
    }

    /**
     * 该类型的必填连接字段名。
     *
     * @param type 引擎类型
     * @return 必填字段名（未知类型返回空列表）
     */
    public static List<String> requiredConnectionFields(String type) {
        TypeInfo info = get(type);
        if (info == null) {
            return List.of();
        }
        return info.connectionFields().stream()
            .filter(Field::required)
            .map(Field::name)
            .toList();
    }

    /**
     * 该类型的敏感字段名（读取时脱敏用）。
     *
     * @param type 引擎类型
     * @return 敏感字段名（未知类型返回空列表）
     */
    public static List<String> sensitiveFields(String type) {
        TypeInfo info = get(type);
        if (info == null) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        for (Field f : info.connectionFields()) {
            if (f.sensitive()) {
                out.add(f.name());
            }
        }
        return List.copyOf(out);
    }
}
