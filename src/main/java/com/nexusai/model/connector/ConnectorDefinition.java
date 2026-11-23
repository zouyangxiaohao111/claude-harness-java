package com.nexusai.model.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 内置连接器目录条目 · {@code backend/src/main/resources/connectors/catalog.json} 的 connectors[] 元素。
 *
 * <p><b>WHY 内置 JSON 而不是数据库</b>：连接器元数据（名称/描述/scope/MCP 地址）是随版本发布的
 * 静态资产，与 octop 的 catalog.json 一一对应；只有「用户连接状态」才需要落库
 * （{@code connector_credentials}，V336）。这样升级版本即可增删连接器，不用写迁移。
 *
 * <p><b>key 即 mcp server 名</b>（本轮已裁定）：授权成功后按 key upsert 一条 {@code mcp_servers}
 * （user scope），工具管道直接复用，不额外建映射表。
 *
 * @param key                 连接器标识（同时是 mcp server 名），如 {@code notion}
 * @param name                展示名（已预转中文，运行时不解析 {@code {en,zh}} 对象）
 * @param description         描述
 * @param authType            oauth2 | apiKey | personalToken | customFields | imapAppPassword
 * @param category            knowledge | office | productivity | professional …
 * @param scopes              期望 scope（缺失/空 = 不请求 scope）
 * @param mcpUrl              MCP server 地址（upsert 到 mcp_servers 的远程 URL）
 * @param oauthIssuer         OAuth issuer / discovery 基础 URL（RFC 8414 well-known 前缀）
 * @param transport           http（streamable_http）| sse
 * @param discoveryUrl        显式 discovery 文档 URL（缺省 = oauthIssuer）
 * @param authorizationEndpoint 直接给定授权端点（缺省 = 走 discovery）
 * @param tokenEndpoint       直接给定 token 端点（缺省 = 走 discovery）
 * @param clientId            预注册 client id（缺省 = 走 DCR 或用户手填）
 * @param requiresManualClient 无 DCR 时是否必须由用户手填 clientId/clientSecret
 * @param hidden              默认是否在列表隐藏（腾讯内部网关依赖等）
 * @param tokenExpires        token 响应是否一定带 expires_in（true 时缺失即失败；缺省 false = 宽容）
 * @param docUrl              官方文档
 * @param authHint            授权提示（扁平中文串）
 * @param icon                图标标识（前端按 key 找本地图标）
 * @param color               主题色
 * @param phase               [2026-11-23] Octop 目录阶段（available 等）
 * @param mcpMode             [2026-11-23] remote | gateway | internal | stdio（决定能否直连）
 * @param supportTier         [2026-11-23] supported（可授权运行）/ credentialOnly（只存凭据）/
 *                            unavailable（需 Octop 网关/CLI，置灰）
 * @param unavailableReason   unavailable 时给用户看的中文原因
 * @param credentialFields    手动凭据表单字段（仅部分条目声明；空列表 = 前端用通用表单）
 * @param official            [2026-11-23] 是否有厂商官方 MCP（false = 仅 Skill/CLI/无）
 * @param authMode            [2026-11-23] oauth2 | bearer | header | query | none | unsupported
 * @param mcpUrlTemplate      带占位符的 MCP URL（{token}/{key}/{apikey}/自定义字段名）
 * @param mcpHeaders          需要注入的请求头（值可含占位符）
 * @param tokenField          主凭据字段名（bearer/query/header 用；null = 无）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectorDefinition(
        String key,
        String name,
        String description,
        String authType,
        String category,
        List<String> scopes,
        String mcpUrl,
        String oauthIssuer,
        String transport,
        String discoveryUrl,
        String authorizationEndpoint,
        String tokenEndpoint,
        String clientId,
        Boolean requiresManualClient,
        Boolean hidden,
        Boolean tokenExpires,
        String docUrl,
        String authHint,
        String icon,
        String color,
        String phase,
        String mcpMode,
        String supportTier,
        String unavailableReason,
        List<ConnectorCredentialField> credentialFields,
        Boolean official,
        String authMode,
        String mcpUrlTemplate,
        Map<String, String> mcpHeaders,
        String tokenField
) {

    public ConnectorDefinition {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        credentialFields = credentialFields == null ? List.of() : List.copyOf(credentialFields);
        mcpHeaders = mcpHeaders == null ? Map.of() : Map.copyOf(mcpHeaders);
        official = Boolean.TRUE.equals(official);
        if (transport == null || transport.isBlank()) {
            transport = "http";
        }
        if (phase == null || phase.isBlank()) {
            phase = "available";
        }
        if (mcpMode == null || mcpMode.isBlank()) {
            mcpMode = "remote";
        }
        if (supportTier == null || supportTier.isBlank()) {
            // 老 catalog（无分档字段）按同一口径推导，保证升级后行为可解释
            supportTier = deriveTier(authType, mcpMode);
        }
        if (authMode == null || authMode.isBlank()) {
            authMode = "oauth2".equals(authType) ? "oauth2"
                    : ("supported".equals(supportTier) ? "bearer" : "unsupported");
        }
    }

    /** 25 参构造（无 official/authMode/模板/头/主字段）· 供既有调用方/测试。 */
    public ConnectorDefinition(String key, String name, String description, String authType,
                               String category, List<String> scopes, String mcpUrl,
                               String oauthIssuer, String transport, String discoveryUrl,
                               String authorizationEndpoint, String tokenEndpoint, String clientId,
                               Boolean requiresManualClient, Boolean hidden, Boolean tokenExpires,
                               String docUrl, String authHint, String icon, String color,
                               String phase, String mcpMode, String supportTier,
                               String unavailableReason, List<ConnectorCredentialField> credentialFields) {
        this(key, name, description, authType, category, scopes, mcpUrl, oauthIssuer, transport,
                discoveryUrl, authorizationEndpoint, tokenEndpoint, clientId, requiresManualClient,
                hidden, tokenExpires, docUrl, authHint, icon, color,
                phase, mcpMode, supportTier, unavailableReason, credentialFields,
                null, null, null, null, null);
    }

    /** 24 参构造（无 credentialFields）· 供既有调用方/测试。 */
    public ConnectorDefinition(String key, String name, String description, String authType,
                               String category, List<String> scopes, String mcpUrl,
                               String oauthIssuer, String transport, String discoveryUrl,
                               String authorizationEndpoint, String tokenEndpoint, String clientId,
                               Boolean requiresManualClient, Boolean hidden, Boolean tokenExpires,
                               String docUrl, String authHint, String icon, String color,
                               String phase, String mcpMode, String supportTier,
                               String unavailableReason) {
        this(key, name, description, authType, category, scopes, mcpUrl, oauthIssuer, transport,
                discoveryUrl, authorizationEndpoint, tokenEndpoint, clientId, requiresManualClient,
                hidden, tokenExpires, docUrl, authHint, icon, color,
                phase, mcpMode, supportTier, unavailableReason, List.of(),
                null, null, null, null, null);
    }

    /**
     * 旧 20 参构造（无分档字段）：分档按 authType/mcpMode 推导。
     * 新增字段后保留，避免既有测试与调用方按位置构造全部改一遍。
     */
    public ConnectorDefinition(String key, String name, String description, String authType,
                               String category, List<String> scopes, String mcpUrl,
                               String oauthIssuer, String transport, String discoveryUrl,
                               String authorizationEndpoint, String tokenEndpoint, String clientId,
                               Boolean requiresManualClient, Boolean hidden, Boolean tokenExpires,
                               String docUrl, String authHint, String icon, String color) {
        this(key, name, description, authType, category, scopes, mcpUrl, oauthIssuer, transport,
                discoveryUrl, authorizationEndpoint, tokenEndpoint, clientId, requiresManualClient,
                hidden, tokenExpires, docUrl, authHint, icon, color,
                null, null, null, null);
    }

    private static String deriveTier(String authType, String mcpMode) {
        if ("oauth2".equals(authType)) {
            return "supported";
        }
        if ("gateway".equals(mcpMode) || "internal".equals(mcpMode) || "stdio".equals(mcpMode)) {
            return "unavailable";
        }
        return "credentialOnly";
    }

    /** 是否 oauth2 类型（一键授权形态）· authMode 优先（腾讯微云等 authType 历史值可能不同）。 */
    public boolean isOAuth() {
        return "oauth2".equals(authMode) || "oauth2".equals(authType);
    }

    /** 绑定 MCP 时可用的端点模板（query 模板优先）。 */
    public String endpointTemplate() {
        return mcpUrlTemplate != null && !mcpUrlTemplate.isBlank() ? mcpUrlTemplate : mcpUrl;
    }

    /** 走 Bearer 注入（oauth2 / bearer）。 */
    public boolean requiresBearer() {
        return "oauth2".equals(authMode) || "bearer".equals(authMode);
    }

    /** 是否走「手动凭据 → 绑定模板/头」路径。 */
    public boolean bindsWithCredentials() {
        return supported() && !isOAuth() && endpointTemplate() != null;
    }

    /** 可授权运行（只有 supported 档才能 start / test / 绑 mcp server）。 */
    public boolean supported() {
        return "supported".equals(supportTier);
    }

    /** 只能保存凭据（不绑 mcp、不假装能运行）。 */
    public boolean credentialOnly() {
        return "credentialOnly".equals(supportTier);
    }

    /** 需要 Octop 网关/CLI，置灰不可用。 */
    public boolean unavailable() {
        return "unavailable".equals(supportTier);
    }

    /** 置灰时给用户看的原因（缺省按 mcpMode 兜底）。 */
    public String unavailableMessage() {
        if (unavailableReason != null && !unavailableReason.isBlank()) {
            return unavailableReason;
        }
        return "stdio".equals(mcpMode)
                ? "需要本机 CLI/Node 运行环境，本轮暂不可用"
                : "需要 Octop 网关适配器，本轮暂不可用";
    }

    /** 默认是否隐藏（qcc / tencent-ardot 为 true）。 */
    public boolean hiddenByDefault() {
        return Boolean.TRUE.equals(hidden);
    }

    /** 是否要求用户手填 client id/secret（无 DCR 时）。 */
    public boolean manualClientRequired() {
        return Boolean.TRUE.equals(requiresManualClient);
    }

    /** mcp_servers 传输类型：http / sse。 */
    public String mcpTransport() {
        return "sse".equals(transport) ? "sse" : "http";
    }

    /** scope 以空格拼接（RFC 6749 §3.3）。 */
    public String scopeString() {
        return String.join(" ", scopes);
    }

    /** discovery 入口：显式 discoveryUrl 优先，否则 oauthIssuer。 */
    public String discoveryEntry() {
        if (discoveryUrl != null && !discoveryUrl.isBlank()) {
            return discoveryUrl.trim();
        }
        return oauthIssuer == null || oauthIssuer.isBlank() ? null : oauthIssuer.trim();
    }
}
