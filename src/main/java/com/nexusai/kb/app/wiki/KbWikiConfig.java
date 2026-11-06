package com.nexusai.kb.app.wiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识库级 Wiki 配置（{@code kb_knowledge_base.wiki_config} 的 JSON）。
 *
 * <p><b>源侧对照</b>：WeKnora {@code internal/types/wiki_config.go} +
 * {@code prompts_wiki.go:592 IsAutoGenerateEnabled}。
 *
 * <h2>⭐⭐ {@code autoGenerate} 是<b>三态</b>，不是布尔</h2>
 * {@code null}（字段缺失 / 整个 JSON 为空）⇒ <b>true</b>。
 * <p>理由：这个字段是后来加的，老知识库的 JSON 里没有它；
 * 若按 {@code Boolean.FALSE.equals(...)} 判，那些库会<b>永远不再生成 Wiki</b>，
 * 而且不报错 —— 用户只会觉得「新功能没生效」。
 * <p>⇒ 用 {@link Boolean}（可空）而不是 {@code boolean}，并且在
 * {@link #isAutoGenerateEnabled()} 里显式把 null 当 true。
 *
 * <h2>⚠️ 数值字段用 {@code 0} 表示「用默认值」，不是「零」</h2>
 * {@code maxPagesPerIngest=0} 是「不限」，{@code ingestBatchSize=0} 是「取系统默认」。
 * 这与用户设置里「0 = 不限」的惯例一致。⛔ 不要把它们当除数/上限直接用，
 * 一律先经 {@link #effective*} 方法归一。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KbWikiConfig {

    private static final Logger log = LoggerFactory.getLogger(KbWikiConfig.class);

    /** 单次入库最多生成/更新多少页；0 = 不限。 */
    @JsonProperty("max_pages_per_ingest")
    private Integer maxPagesPerIngest;

    /** 抽取粒度：focused / standard / exhaustive。 */
    @JsonProperty("extraction_granularity")
    private String extractionGranularity;

    /** 写页面的模型 ID（空 = 用知识库的 summary_model_id）。 */
    @JsonProperty("synthesis_model_id")
    private String synthesisModelId;

    /**
     * 是否自动生成 · <b>三态</b>：null ⇒ true（见类注释）。
     */
    @JsonProperty("auto_generate")
    private Boolean autoGenerate;

    /** 文风与侧重说明（不影响引用与事实）。 */
    @JsonProperty("content_instructions")
    private String contentInstructions;

    /** 抽取时保留哪些概念的说明（不影响输出格式）。 */
    @JsonProperty("extraction_instructions")
    private String extractionInstructions;

    /** 批大小；0 = 系统默认（5，对齐源侧 wikiMaxDocsPerBatch）。 */
    @JsonProperty("ingest_batch_size")
    private Integer ingestBatchSize;

    /** Map 阶段并发；0 = 系统默认。 */
    @JsonProperty("ingest_map_parallel")
    private Integer ingestMapParallel;

    /** Reduce 阶段并发；0 = 系统默认。 */
    @JsonProperty("ingest_reduce_parallel")
    private Integer ingestReduceParallel;

    /** 同时在跑的 ingest 上限；0 = 系统默认。 */
    @JsonProperty("ingest_max_inflight")
    private Integer ingestMaxInflight;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 系统默认值（对齐源侧常量）──────────────────────────────────

    /** 源侧 {@code wikiMaxDocsPerBatch}。 */
    public static final int DEFAULT_BATCH_SIZE = 5;

    /**
     * 解析 JSON 文本。
     *
     * <p><b>fail-soft</b>：JSON 坏 / 为空 ⇒ 返回全默认配置（不是抛异常）。
     * 理由：一份写坏的配置不该让整个知识库的 Wiki 功能永久 500 ——
     * 用户连「怎么修」的入口都进不去。
     *
     * @param json 列值（可为 null / 空 / 非法 JSON）
     * @return 配置（永不 null）
     */
    public static KbWikiConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return new KbWikiConfig();
        }
        try {
            KbWikiConfig cfg = MAPPER.readValue(json, KbWikiConfig.class);
            return cfg == null ? new KbWikiConfig() : cfg;
        } catch (Exception e) {
            log.warn("[Wiki] wiki_config 解析失败，按默认配置处理: {}", e.toString());
            return new KbWikiConfig();
        }
    }

    /**
     * 是否启用自动生成。
     *
     * <p>⭐⭐ {@code null ⇒ true}（见类注释）。
     *
     * @return 是否启用
     */
    public boolean isAutoGenerateEnabled() {
        // ⛔ 不要写成 Boolean.FALSE.equals(autoGenerate) —— 那会把「未设置」当成「关闭」
        return autoGenerate == null || autoGenerate;
    }

    /**
     * 生效的抽取粒度。
     *
     * @return focused / standard / exhaustive（非法值回落 standard）
     */
    public String effectiveGranularity() {
        String g = extractionGranularity == null ? "" : extractionGranularity.trim().toLowerCase();
        return switch (g) {
            case "focused", "standard", "exhaustive" -> g;
            default -> "standard";
        };
    }

    /** 生效的批大小。 */
    public int effectiveBatchSize() {
        return ingestBatchSize == null || ingestBatchSize <= 0 ? DEFAULT_BATCH_SIZE : ingestBatchSize;
    }

    /** 生效的单批页数上限；{@code Integer.MAX_VALUE} = 不限。 */
    public int effectiveMaxPagesPerIngest() {
        return maxPagesPerIngest == null || maxPagesPerIngest <= 0
            ? Integer.MAX_VALUE : maxPagesPerIngest;
    }

    /**
     * 生效的 Map 并发。
     *
     * @param fallback 调用方给的默认值（{@code 0} ⇒ 串行）
     */
    public int effectiveMapParallel(int fallback) {
        return ingestMapParallel == null || ingestMapParallel <= 0 ? fallback : ingestMapParallel;
    }

    /**
     * 生效的 Reduce 并发。
     *
     * @param fallback 调用方给的默认值
     */
    public int effectiveReduceParallel(int fallback) {
        return ingestReduceParallel == null || ingestReduceParallel <= 0 ? fallback : ingestReduceParallel;
    }

    /** 生效的 inflight 上限。 */
    public int effectiveMaxInflight(int fallback) {
        return ingestMaxInflight == null || ingestMaxInflight <= 0 ? fallback : ingestMaxInflight;
    }

    public Integer getMaxPagesPerIngest() { return maxPagesPerIngest; }
    public void setMaxPagesPerIngest(Integer v) { this.maxPagesPerIngest = v; }
    public String getExtractionGranularity() { return extractionGranularity; }
    public void setExtractionGranularity(String v) { this.extractionGranularity = v; }
    public String getSynthesisModelId() { return synthesisModelId; }
    public void setSynthesisModelId(String v) { this.synthesisModelId = v; }
    public Boolean getAutoGenerate() { return autoGenerate; }
    public void setAutoGenerate(Boolean v) { this.autoGenerate = v; }
    public String getContentInstructions() { return contentInstructions; }
    public void setContentInstructions(String v) { this.contentInstructions = v; }
    public String getExtractionInstructions() { return extractionInstructions; }
    public void setExtractionInstructions(String v) { this.extractionInstructions = v; }
    public Integer getIngestBatchSize() { return ingestBatchSize; }
    public void setIngestBatchSize(Integer v) { this.ingestBatchSize = v; }
    public Integer getIngestMapParallel() { return ingestMapParallel; }
    public void setIngestMapParallel(Integer v) { this.ingestMapParallel = v; }
    public Integer getIngestReduceParallel() { return ingestReduceParallel; }
    public void setIngestReduceParallel(Integer v) { this.ingestReduceParallel = v; }
    public Integer getIngestMaxInflight() { return ingestMaxInflight; }
    public void setIngestMaxInflight(Integer v) { this.ingestMaxInflight = v; }
}
