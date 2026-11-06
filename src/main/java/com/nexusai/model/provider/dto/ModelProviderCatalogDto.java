package com.nexusai.model.provider.dto;

import java.util.List;

/**
 * 模型厂商目录项（批次 W8）· 源侧 {@code ModelProviderDTO} 的本仓对应物。
 *
 * <p><b>⚠️ 这不是 {@code providers} 表</b>：表里是<b>用户配好的</b>厂商实例
 * （带 api_key、base_url）；本 DTO 是<b>平台支持的厂商种类</b>
 * （源侧 {@code modelruntime.List()} 返回的静态目录）。
 * 前者用 {@code GET /providers}，后者用 {@code GET /models/providers}，⛔ 别混。
 *
 * <p>源侧字段（{@code handler/model_catalog.go}）：
 * {@code ID / Name / ModelTypes / DefaultURLs / Models[] / ExtraFields[]}。
 * 本仓只保留本仓真正有含义的前四项 —— 后两项（厂商级的额外配置字段）
 * 在本仓没有对应的运行时机制，给了也只会是空数组。
 */
public record ModelProviderCatalogDto(
    /** 厂商标识（与 {@link ProviderType} 同名）。 */
    String id,
    String name,
    /** 该厂商可承载的模型类型。 */
    List<String> modelTypes,
    /** 默认接入地址（编辑器预填）。 */
    List<String> defaultUrls
) {
}
