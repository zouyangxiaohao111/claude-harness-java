package com.nexusai.kb.app.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入库六池并发配置的 JSON 载体 · {@code settings.ingest_concurrency_config}（V293）与
 * {@code /api/v1/settings/ingest-concurrency} 请求体共用。
 *
 * <p><b>字段全部可空</b>：HTTP PUT 里缺失的字段 = 「这次不改」（merge 语义），
 * 数据库里缺失的字段 = 「回落 yml」（{@link KbIngestPoolProperties}）。
 * 三个静态方法分别承担三件事：
 * <ul>
 *   <li>{@link #parse(String)} —— 列文本 → 配置；空/非法 → {@code null}（按未配置处理，⛔ 不抛）；</li>
 *   <li>{@link #resolve(KbIngestPoolProperties)} —— 本对象缺的字段用 yml 值补齐，得到「完整生效值」；</li>
 *   <li>{@link #overlay} —— 把 PUT 里出现的字段盖到当前生效值上（{@code null} = 不覆盖）。</li>
 * </ul>
 *
 * <p>⚠️ JSON 键名就是 record 组件名（{@code parse / postprocess / enrichment / wiki /
 * maintenance / shared / maxTasksPerTick}）—— 这是与前端固定的契约，⛔ 不要改名。
 */
public record IngestConcurrencyConfig(
    Integer parse,
    Integer postprocess,
    Integer enrichment,
    Integer wiki,
    Integer maintenance,
    Integer shared,
    Integer maxTasksPerTick
) {

    private static final Logger log = LoggerFactory.getLogger(IngestConcurrencyConfig.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 解析 settings 列里的 JSON 文本。
     *
     * <p>空串 / 非法 JSON / 非对象 一律返回 {@code null}（= 未配置 → 回落 yml），
     * 只打 warn 不抛：一行坏配置不应该让 worker 启动失败或让 GET 报 500。
     *
     * @param json 列文本（可空）
     * @return 配置对象；空/非法 → {@code null}
     */
    public static IngestConcurrencyConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                log.warn("[入库并发配置] settings.ingest_concurrency_config 不是 JSON 对象，按未配置处理");
                return null;
            }
            return new IngestConcurrencyConfig(
                intOf(node, "parse"),
                intOf(node, "postprocess"),
                intOf(node, "enrichment"),
                intOf(node, "wiki"),
                intOf(node, "maintenance"),
                intOf(node, "shared"),
                intOf(node, "maxTasksPerTick"));
        } catch (Exception e) {
            log.warn("[入库并发配置] settings.ingest_concurrency_config 解析失败，按未配置处理: {}",
                e.getMessage());
            return null;
        }
    }

    private static Integer intOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    /** yml 侧的完整配置（{@code KbIngestPoolProperties} 当前值）。 */
    public static IngestConcurrencyConfig ymlDefaults(KbIngestPoolProperties properties) {
        return new IngestConcurrencyConfig(
            properties.getParseConcurrency(),
            properties.getPostprocessConcurrency(),
            properties.getEnrichmentConcurrency(),
            properties.getWikiConcurrency(),
            properties.getMaintenanceConcurrency(),
            properties.getSharedConcurrency(),
            properties.getMaxTasksPerTick());
    }

    /**
     * 补全缺失字段（本对象为 DB 值，缺的字段回落 yml）。
     *
     * @param properties yml 配置（null 时缺失字段按 {@link KbIngestPoolProperties} 默认值）
     * @return 每项都非 null 的完整配置
     */
    public IngestConcurrencyConfig resolve(KbIngestPoolProperties properties) {
        IngestConcurrencyConfig fallback = ymlDefaults(
            properties != null ? properties : new KbIngestPoolProperties());
        return new IngestConcurrencyConfig(
            parse != null ? parse : fallback.parse,
            postprocess != null ? postprocess : fallback.postprocess,
            enrichment != null ? enrichment : fallback.enrichment,
            wiki != null ? wiki : fallback.wiki,
            maintenance != null ? maintenance : fallback.maintenance,
            shared != null ? shared : fallback.shared,
            maxTasksPerTick != null ? maxTasksPerTick : fallback.maxTasksPerTick);
    }

    /**
     * 用 {@code patch} 里非 null 的字段覆盖本对象（PUT 的「缺失=不改」）。
     *
     * @param patch 请求体（可空）
     * @return 合并后的新对象（本对象不变）
     */
    public IngestConcurrencyConfig overlay(IngestConcurrencyConfig patch) {
        if (patch == null) {
            return this;
        }
        return new IngestConcurrencyConfig(
            patch.parse != null ? patch.parse : parse,
            patch.postprocess != null ? patch.postprocess : postprocess,
            patch.enrichment != null ? patch.enrichment : enrichment,
            patch.wiki != null ? patch.wiki : wiki,
            patch.maintenance != null ? patch.maintenance : maintenance,
            patch.shared != null ? patch.shared : shared,
            patch.maxTasksPerTick != null ? patch.maxTasksPerTick : maxTasksPerTick);
    }

    /** 序列化为落库 JSON（键名 = record 组件名，顺序即声明顺序）。 */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalArgumentException("入库并发配置序列化失败: " + e.getMessage(), e);
        }
    }
}
