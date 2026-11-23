package com.nexusai.model.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * marketplace.json 插件条目上的 {@code connection} 块（连接器授权元数据）。
 *
 * <p><b>WHY 单独建模</b>：三处逐键解析（{@code PluginMarketplace.Entry} 的 Jackson 反序列化、
 * {@code ExternalMarketService} 的显式取键、{@code MarketPublishService} 的逐个 put）必须用同一形状，
 * 否则「市场清单里写了、界面永远看不到」。本 record 就是那唯一形状。
 *
 * <p><b>语言形态</b>：本仓 manifest 用扁平中文串（不保留 octop 的 {@code {en,zh}} 对象），
 * 运行时零解析。
 *
 * @param authType              oauth2 | apiKey | personalToken | customFields | imapAppPassword
 * @param scopes                期望 scope 列表（可空）
 * @param discoveryUrl          OAuth discovery 文档 URL（可空）
 * @param authorizationEndpoint 授权端点（可空 · 缺省走 discovery）
 * @param tokenEndpoint         token 端点（可空 · 缺省走 discovery）
 * @param resource              RFC 8707 resource / MCP 资源标识（可空）
 * @param requiresManualClient  true = 用户需先填 clientId/clientSecret
 * @param callbackPath          loopback 回调路径（可空 · 缺省 {@code /callback}）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorConnectionInfo(
        String authType,
        List<String> scopes,
        String discoveryUrl,
        String authorizationEndpoint,
        String tokenEndpoint,
        String resource,
        Boolean requiresManualClient,
        String callbackPath
) {

    public ConnectorConnectionInfo {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }
}
