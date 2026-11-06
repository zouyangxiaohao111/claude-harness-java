package com.nexusai.kb.app.retrieval.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 其余 8 个驱动的<b>占位声明</b> —— 它们<b>不可用</b>，选中即报错。
 *
 * <h2>⭐⭐ 为什么不做成「能跑」的样子</h2>
 * 用户要求完整复刻，但本机没有 pgvector / Qdrant / Milvus / Weaviate / Doris /
 * ES / OpenSearch / 腾讯 VectorDB 的任何环境，也没有对应 SDK 依赖。
 * <p>在这种前提下写「实现」，只能写出<b>从未被执行过</b>的代码 ——
 * 它既不能证明正确，也无法在出问题时给出线索，却会让界面显示「已支持」。
 * <p>⇒ 诚实的做法是：<b>声明能力边界，选中时明确报错并列出可用驱动</b>。
 * 这样用户不会误以为数据进了外部库（那类故障换机器才发现，日志全正常）。
 *
 * <h2>接入某个驱动时改什么</h2>
 * <ol>
 *   <li>加该驱动的 SDK / HTTP 客户端依赖；</li>
 *   <li>新建 {@code KbXxxRetrievalEngine implements KbRetrievalEngine}，
 *       {@code engineType()} 返回对应 wire 值，{@code available()} 返回真实探测结果；</li>
 *   <li>把这个占位类里的同名常量删掉（避免重复注册告警）。</li>
 * </ol>
 * ⛔ 不需要改注册表、不需要改任何调用方 —— 这正是接口化的价值。
 */
public final class KbUnavailableEngines {

    private static final Logger log = LoggerFactory.getLogger(KbUnavailableEngines.class);

    private KbUnavailableEngines() {
    }

    /**
     * 未接入驱动的统一实现：能力声明为空、任何操作都抛「本构建不可用」。
     *
     * <p>⚠️ {@link #available()} 返回 false ⇒ 注册表在 {@code require()} 阶段就会拒绝，
     * 这个方法体实际不会被执行到；保留它是为了「万一被绕过」时也 fail-loud。
     */
    abstract static class Unavailable implements KbRetrievalEngine {

        private final String type;

        Unavailable(String type) {
            this.type = type;
            log.info("[检索引擎] 驱动 {} 已声明但本构建未接入（选中会报错）", type);
        }

        @Override
        public String engineType() {
            return type;
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean supports(Feature feature) {
            return false;
        }

        @Override
        public void index(String kbId, List<IndexDocument> documents, String modelId) {
            throw unavailable();
        }

        @Override
        public void deleteByKnowledgeIds(String kbId, List<String> knowledgeIds) {
            throw unavailable();
        }

        @Override
        public List<Hit> retrieve(RetrieveParams params) {
            throw unavailable();
        }

        private IllegalStateException unavailable() {
            return new IllegalStateException(
                "检索驱动 '" + type + "' 在本构建中未接入（缺少 SDK 依赖或外部服务未配置）");
        }
    }

    /** PostgreSQL（pgvector + ParadeDB/tsvector）。源侧默认驱动。 */
    @Component
    static class Postgres extends Unavailable {
        Postgres() { super("postgres"); }
    }

    /** Elasticsearch（v7 仅关键词 / v8 带 kNN）。 */
    @Component
    static class Elasticsearch extends Unavailable {
        Elasticsearch() { super("elasticsearch"); }
    }

    /** OpenSearch k-NN。 */
    @Component
    static class OpenSearch extends Unavailable {
        OpenSearch() { super("opensearch"); }
    }

    /** Qdrant。 */
    @Component
    static class Qdrant extends Unavailable {
        Qdrant() { super("qdrant"); }
    }

    /** Milvus。 */
    @Component
    static class Milvus extends Unavailable {
        Milvus() { super("milvus"); }
    }

    /** Weaviate。 */
    @Component
    static class Weaviate extends Unavailable {
        Weaviate() { super("weaviate"); }
    }

    /** Apache Doris。 */
    @Component
    static class Doris extends Unavailable {
        Doris() { super("doris"); }
    }

    /** 腾讯云 VectorDB。 */
    @Component
    static class TencentVectorDb extends Unavailable {
        TencentVectorDb() { super("tencent_vectordb"); }
    }
}
