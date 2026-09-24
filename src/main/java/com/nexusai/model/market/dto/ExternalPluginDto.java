package com.nexusai.model.market.dto;

import java.util.List;

/**
 * 外部市场单个插件 DTO · GET /api/market/external 返回（plugins[] 元素）。
 *
 * <p>字段对齐用户 marketplace.json 的扩展条目（全英文键）：name 为安装定位标识，
 * displayName 为中文名称，其余为展示元数据。category 用于前端分流到专家/技能/连接器 Tab。
 *
 * @param name        插件英文标识（marketplace.json entry.name，安装定位用）
 * @param displayName 中文展示名
 * @param description 描述
 * @param version     版本号
 * @param category    分类（expert / skill / connector）
 * @param tags        标签
 * @param createdAt   创建时间（ISO 8601）
 */
public record ExternalPluginDto(
        String name,
        String displayName,
        String description,
        String version,
        String category,
        List<String> tags,
        String createdAt
) {}
