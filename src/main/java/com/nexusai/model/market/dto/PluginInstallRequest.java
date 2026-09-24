package com.nexusai.model.market.dto;

/**
 * 插件安装请求 · POST /api/plugins/install。
 *
 * @param pluginId       插件标识（name@marketplace，如 zjky-data-analyst@zjky-market）
 * @param marketplaceUrl 市场 URL（自建 MinIO marketplace zip/json · 未物化时自动 reconcile 用）
 * @param scope          安装 scope（user/project/local，缺省 user）
 */
public record PluginInstallRequest(
        String pluginId,
        String marketplaceUrl,
        String scope
) {}
