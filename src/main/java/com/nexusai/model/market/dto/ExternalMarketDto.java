package com.nexusai.model.market.dto;

import java.util.List;

/**
 * 外部插件市场（自建 MinIO / 静态 HTTP）统一 DTO · GET /api/market/external 返回。
 *
 * <p>解析 {@code marketplace.json}（projects/marketplace 规范）为前端市场弹窗可消费的视图：
 * 市场元信息 + 扁平插件列表（按 {@code category} 前端分流到专家/技能/连接器 Tab）。
 * 字段取 marketplace.json 的扩展字段（displayName/description/createdAt 等，项目
 * {@code PluginMarketplace.Entry} 不消费但保留给市场 UI 展示）。
 *
 * @param name        市场名（marketplace.json 的 name）
 * @param owner       市场所有者
 * @param description 市场描述
 * @param plugins     插件列表（category = expert/skill/connector）
 */
public record ExternalMarketDto(
        String name,
        String owner,
        String description,
        List<ExternalPluginDto> plugins
) {}
