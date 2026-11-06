package com.nexusai.kb.app.retrieval.engine;

import java.util.List;

/**
 * 检索引擎接口 · 对齐 WeKnora {@code RetrieveEngineService}
 * （{@code internal/types/interfaces/retriever.go:96-118}）。
 *
 * <h2>⭐ 为什么用 Java interface 而不是照抄 Go 的常量映射表</h2>
 * 源侧的做法是 {@code retrieverEngineMapping map[string][]RetrieverEngineParams} +
 * 环境变量 {@code RETRIEVE_DRIVER} 选驱动。这是因为 Go 没有 Spring 那样的
 * 「按类型收集全部实现」的容器能力。
 * <p>Java 侧：{@code interface} + 多个 {@code @Component} 实现 +
 * {@code List<KbRetrievalEngine>} 构造注入 ⇒ <b>Spring 天然就是注册表</b>，
 * 加驱动 = 加一个类，⛔ 不需要改任何映射表。
 *
 * <h2>⚠️ 未实现的驱动必须 fail-loud</h2>
 * 本接口只声明契约。某个驱动如果没有可用实现（没装 SDK / 本机构建没带），
 * 选择它时必须<b>明确报错并列出可用驱动</b>，
 * ⛔ 绝不能静默回落成 sqlite —— 那样用户会以为数据写进了 pgvector，
 * 实际全在本地 SQLite 里，直到换机器才发现全丢。
 *
 * <h2>方法范围说明</h2>
 * 源侧接口 13 个方法。本仓当前只用到其中「索引写入 / 检索 / 删除 / 状态更新」四类；
 * {@code copyIndices}（跨库复制向量）与 {@code estimateStorageSize}（存储估算）
 * 声明了但允许抛 {@link UnsupportedOperationException} —— 调用方必须自己判
 * {@link #supports(Feature)}，⛔ 不要假设每个驱动都一样。
 */
public interface KbRetrievalEngine {

    /**
     * 驱动类型标识（与源侧 wire 值一致）。
     *
     * @return {@code sqlite} / {@code postgres} / {@code qdrant} / {@code milvus} /
     *         {@code weaviate} / {@code doris} / {@code elasticsearch} / {@code opensearch} /
     *         {@code tencent_vectordb}
     */
    String engineType();

    /**
     * 该驱动是否在本机可用（已装配、依赖齐全、配置可解析）。
     *
     * <p>⚠️ 这是「能不能用」而不是「支不支持某种检索方式」——
     * 后者看 {@link #supports(Feature)}。
     *
     * @return false = 选中它应当报错
     */
    default boolean available() {
        return true;
    }

    /**
     * 能力声明。
     *
     * @param feature 能力项
     * @return true = 支持
     */
    boolean supports(Feature feature);

    /**
     * 写入索引（单条）。
     *
     * @param kbId       知识库 ID
     * @param documents  待索引文档
     * @param modelId    向量模型 ID（仅向量路需要，null = 只建关键词索引）
     */
    void index(String kbId, List<IndexDocument> documents, String modelId);

    /**
     * 删除某个知识条目的全部索引（重解析 / 删文档）。
     *
     * @param kbId         知识库 ID
     * @param knowledgeIds 知识条目 ID 列表
     */
    void deleteByKnowledgeIds(String kbId, List<String> knowledgeIds);

    /**
     * 检索。
     *
     * @param params 检索参数（查询文本 / 向量 / 条数 / 是否禁用某路）
     * @return 命中（按相关性降序）
     */
    List<Hit> retrieve(RetrieveParams params);

    /**
     * 能力项。
     */
    enum Feature {
        /** 关键词检索 */
        KEYWORD,
        /** 向量检索 */
        VECTOR,
        /** 跨库复制索引（免重算向量） */
        COPY_INDICES,
        /** 存储用量估算 */
        ESTIMATE_STORAGE,
        /** 按 chunk 批量改启用状态 */
        BATCH_ENABLED_STATUS,
        /** 按 chunk 批量改标签 */
        BATCH_TAG
    }

    /**
     * 待索引文档。
     *
     * @param chunkId     块 ID
     * @param knowledgeId 所属文档
     * @param header      上下文表头（参与关键词检索）
     * @param content     正文
     */
    record IndexDocument(String chunkId, String knowledgeId, String header, String content) {
    }

    /**
     * 检索参数。
     *
     * @param kbId          知识库 ID
     * @param query         查询文本
     * @param queryVector   查询向量（null = 不走向量路）
     * @param modelId       向量模型 ID（与 {@code queryVector} 配套）
     * @param limit         返回条数
     * @param disableVector 关闭向量路
     * @param disableKeyword 关闭关键词路
     */
    record RetrieveParams(
        String kbId, String query, float[] queryVector, String modelId,
        int limit, boolean disableVector, boolean disableKeyword
    ) {
        /** 两路都关掉是非法组合（调用方应提前 400）。 */
        public boolean bothDisabled() {
            return disableVector && disableKeyword;
        }
    }

    /**
     * 检索命中。
     *
     * @param chunkId     块 ID
     * @param knowledgeId 所属文档
     * @param content     正文
     * @param header      上下文表头
     * @param score       相关性分数（各驱动量纲不同，⛔ 不可跨驱动比较）
     * @param vectorRank  向量路名次（0 = 未命中）
     * @param keywordRank 关键词路名次（0 = 未命中）
     */
    record Hit(
        String chunkId, String knowledgeId, String content, String header,
        double score, int vectorRank, int keywordRank
    ) {
    }
}
