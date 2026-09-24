package com.nexusai.model.market.dto;

/**
 * 插件安装响应 · POST /api/plugins/install。
 *
 * @param success   是否成功
 * @param message   结果消息（中文；失败含具体原因）
 * @param pluginId  已安装插件标识（name@marketplace）
 * @param pluginName 插件名
 * @param scope     安装 scope
 */
public record PluginInstallResult(
        boolean success,
        String message,
        String pluginId,
        String pluginName,
        String scope
) {}
