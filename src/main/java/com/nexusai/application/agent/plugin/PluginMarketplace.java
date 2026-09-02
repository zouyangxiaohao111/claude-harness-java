package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * marketplace.json 完整 schema · 对齐 CC {@code utils/plugins/schemas.ts}：
 * {@code PluginMarketplaceSchema}（:1293-1330）、{@code PluginMarketplaceEntrySchema}
 * （:1254-1285）、{@code PluginMarketplaceMetadata}（:1308-1318）。
 *
 * <p><b>Entry.source 简化</b>：CC {@code PluginSourceSchema}（schemas.ts:1062-1150+）是
 * {@code RelativePath 字符串 | {source:'npm'|'pip'|'url'|'github'|...} 判别联合} 的大 union。
 * L3 查找层只消费 {@code entry.name}（plugins.find 匹配），source 以 {@link JsonNode} 原样保形
 * （round-trip 不丢字段），完整判别联合建模归 L4 安装层。这与 zod object 默认 strip 未知键的
 * 行为一致——本模型只声明 L3 需要的字段，多余字段由 {@code @JsonIgnoreProperties} 忽略。
 */
public final class PluginMarketplace {

    private PluginMarketplace() {
    }

    /**
     * 单个插件条目 · CC {@code PluginMarketplaceEntrySchema}（schemas.ts:1254-1285）。
     * name 必填（min(1) + 无空格 refine）；source/category/tags/strict 可选；
     * version/dependencies 继承自 {@code PluginManifestSchema().partial()}（schemas.ts:313 dependencies）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
        @JsonProperty("name") String name,
        @JsonProperty("source") JsonNode source,
        @JsonProperty("category") String category,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("strict") Boolean strict,
        @JsonProperty("version") String version,
        @JsonProperty("dependencies") List<String> dependencies) {

        public Entry {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("PluginMarketplaceEntry.name 不能为空");
            }
        }
    }

    /** 可选 marketplace 元数据 · CC PluginMarketplaceSchema.metadata（schemas.ts:1308-1318）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metadata(
        @JsonProperty("pluginRoot") String pluginRoot,
        @JsonProperty("version") String version,
        @JsonProperty("description") String description) {
    }

    /**
     * marketplace 本体 · CC {@code PluginMarketplaceSchema}（schemas.ts:1293-1330）。
     * name 必填；owner 必填但读取宽松（缺失不抛）；plugins 默认空表（对齐 CC find 空数组安全）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Marketplace(
        @JsonProperty("name") String name,
        @JsonProperty("owner") String owner,
        @JsonProperty("plugins") List<Entry> plugins,
        @JsonProperty("forceRemoveDeletedPlugins") Boolean forceRemoveDeletedPlugins,
        @JsonProperty("metadata") Metadata metadata,
        @JsonProperty("allowCrossMarketplaceDependenciesOn") List<String> allowCrossMarketplaceDependenciesOn) {

        public Marketplace {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("PluginMarketplace.name 不能为空");
            }
            if (plugins == null) {
                plugins = List.of();
            }
        }
    }

    /**
     * getPluginById* 返回载体 · CC {@code getPluginByIdCacheOnly}:2220-2223 /
     * {@code getPluginById}:2269-2272 的 {@code {entry, marketplaceInstallLocation}}。
     */
    public record LookupResult(Entry entry, String marketplaceInstallLocation) {
    }
}
