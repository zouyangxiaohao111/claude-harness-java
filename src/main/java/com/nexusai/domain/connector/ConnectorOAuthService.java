package com.nexusai.domain.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.connector.ConnectorCredential;
import com.nexusai.model.connector.ConnectorDefinition;
import com.nexusai.model.connector.dto.ConnectorDto;
import com.nexusai.model.connector.dto.ConnectorListResponse;
import com.nexusai.model.connector.dto.ManualCredentialRequest;
import com.nexusai.model.connector.dto.PollResponse;
import com.nexusai.model.connector.dto.RevokedResponse;
import com.nexusai.model.connector.dto.StartResponse;
import com.nexusai.model.connector.dto.StatusResponse;
import com.nexusai.model.connector.dto.TestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 连接器市场服务 · 列表 / 状态 / 手动凭据 / 真探测 / 断开。
 *
 * <p>OAuth 授权流程在 {@link ConnectorAuthFlow}；本类只做读视图与连接生命周期。
 * 凭据读写一律经 {@link ConnectorCredentialService}（密文落库，不回传明文）。
 */
@Service
public class ConnectorOAuthService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorOAuthService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConnectorCatalog catalog;
    private final ConnectorCredentialService credentials;
    private final ConnectorAuthFlow authFlow;
    private final ConnectorMcpBinding mcpBinding;
    private final McpConnectorProbe connectorProbe;

    public ConnectorOAuthService(ConnectorCatalog catalog,
                                 ConnectorCredentialService credentials,
                                 ConnectorAuthFlow authFlow,
                                 ConnectorMcpBinding mcpBinding,
                                 McpConnectorProbe connectorProbe) {
        this.catalog = catalog;
        this.credentials = credentials;
        this.authFlow = authFlow;
        this.mcpBinding = mcpBinding;
        this.connectorProbe = connectorProbe;
    }

    // ── 列表 ────────────────────────────────────────────────────────────

    public ConnectorListResponse list(boolean includeHidden) {
        Map<String, ConnectorCredential> byKey = credentials.findAll().stream()
                .collect(Collectors.toMap(ConnectorCredential::getConnectorKey, c -> c, (a, b) -> a));
        List<ConnectorDto> out = new ArrayList<>();
        for (ConnectorDefinition def : includeHidden ? catalog.all() : catalog.visible()) {
            // [2026-11-23] 与 poll 同一对账口径：在途 state 过期先落 error，再出 DTO
            ConnectorCredential c = credentials.reconcileExpired(byKey.get(def.key()));
            String status = c == null || c.getStatus() == null ? "unconnected" : c.getStatus();
            out.add(new ConnectorDto(
                    def.key(), def.key(), def.name(), def.description(), def.authType(),
                    def.category(), def.scopes(), isConnected(c), status,
                    c == null ? null : c.getAccount(), c == null ? null : c.getExpiresAt(),
                    def.hiddenByDefault(), def.icon(), def.color(),
                    def.phase(), def.supportTier(), def.unavailableReason(),
                    def.docUrl(), def.authHint(), hasCredentials(c), def.credentialFields(),
                    def.official(), def.authMode()));
        }
        return new ConnectorListResponse(out);
    }

    // ── 授权流程（委托） ────────────────────────────────────────────────

    public StartResponse start(String key) {
        return authFlow.start(key);
    }

    public PollResponse poll(String key, String state) {
        return authFlow.poll(key, state);
    }

    // ── 状态 ────────────────────────────────────────────────────────────

    public StatusResponse status(String key) {
        ConnectorDefinition def = catalog.require(key);
        ConnectorCredential c = credentials.reconcileExpired(
                credentials.find(key).orElse(null));
        if (c != null && "connected".equals(c.getStatus())
                && c.getExpiresAt() != null && c.getExpiresAt() <= System.currentTimeMillis()
                && c.getRefreshTokenEncrypted() != null) {
            authFlow.tryRefresh(c, def);
            c = credentials.find(key).orElse(c);
        }
        boolean connected = isConnected(c);
        return new StatusResponse(connected, def.authType(),
                c == null ? null : c.getAccount(), c == null ? null : c.getExpiresAt(),
                missingScopes(def, c, connected));
    }

    // ── 手动凭据 ────────────────────────────────────────────────────────

    /**
     * 保存手动凭据（DCR 不可用时的 clientId/secret，或非 oauth2 类型的凭据字段）。
     *
     * <p>所有字段整体加密落 {@code extra_encrypted}；已知字段另存专列（token 可被 test/refresh 消费）。
     * 非 oauth2 类型本轮不展示、不绑 mcp server（没有 header 名称元数据，不做假成功）。
     */
    public StatusResponse saveManualCredentials(String key, ManualCredentialRequest req) {
        ConnectorDefinition def = catalog.require(key);
        if (def.unavailable()) {
            throw new ValidationException(
                    "连接器「" + def.name() + "」暂不可用：" + def.unavailableMessage());
        }
        Map<String, String> fields = req == null || req.fields() == null ? Map.of() : req.fields();
        String authType = req != null && !isBlank(req.authType()) ? req.authType().trim() : def.authType();
        if (!authType.equals(def.authType())) {
            throw new ValidationException("authType 与连接器声明不一致（期望 " + def.authType() + "）");
        }
        ConnectorCredential c = credentials.ensure(key, authType);
        String clientId = first(fields, "clientId", "client_id");
        if (clientId != null) {
            c.setClientId(clientId);
            // 手工 client 不能因回调端口变化而重注册
            c.setClientSource("manual");
        }
        String clientSecret = first(fields, "clientSecret", "client_secret");
        if (clientSecret != null) {
            c.setClientSecretEncrypted(credentials.encrypt(clientSecret));
        }
        // [2026-11-23] 主凭据字段：catalog.tokenField 优先（ak/key/apikey/accessToken/…）
        String accessToken = tokenOf(def, fields);
        String refreshToken = first(fields, "refreshToken", "refresh_token");
        if (accessToken != null && def.requiresBearer()) {
            // 只有 oauth2/bearer 才把 token 存为运行时 Bearer；header/query 模式绝不发 Authorization
            c.setAccessTokenEncrypted(credentials.encrypt(accessToken));
        } else if (!def.requiresBearer()) {
            // header/query：清掉可能存在的旧 Bearer 列，避免运行期同时发 Authorization
            c.setAccessTokenEncrypted(null);
        }
        if (refreshToken != null) {
            c.setRefreshTokenEncrypted(credentials.encrypt(refreshToken));
        }
        String tokenEndpoint = first(fields, "tokenEndpoint", "token_endpoint");
        if (tokenEndpoint != null) {
            c.setTokenEndpoint(tokenEndpoint);
        }
        String expiresIn = first(fields, "expiresIn", "expires_in");
        if (expiresIn != null) {
            try {
                long sec = Long.parseLong(expiresIn);
                if (sec > 0) {
                    c.setExpiresAt(System.currentTimeMillis() + sec * 1000L);
                }
            } catch (NumberFormatException ignored) {
                // expiresIn 不是数字 → 不设过期时间（按未知处理）
            }
        }
        try {
            c.setExtraEncrypted(credentials.encrypt(JSON.writeValueAsString(fields)));
        } catch (Exception e) {
            throw new IllegalStateException("凭据序列化失败: " + e.getMessage(), e);
        }
        c.setState(null);
        c.setStateExpiresAt(null);

        boolean hasToken = accessToken != null;
        if (hasToken && def.isOAuth()) {
            // 标准 OAuth（手动 token）→ 运行时 Bearer
            mcpBinding.ensureServer(def);
            c.setStatus("connected");
            c.setLastError(null);
        } else if (hasToken && def.bindsWithCredentials()) {
            // bearer / header / query：按模板渲染 URL / 注入请求头后绑定
            mcpBinding.ensureServer(def, fields);
            c.setStatus("connected");
            c.setLastError(null);
        } else if (def.credentialOnly()) {
            c.setStatus("unconnected");
            c.setLastError(hasToken
                    ? "凭据已保存；该连接器暂不绑定 MCP 工具（暂不能运行）"
                    : "缺少凭据字段（accessToken/apiKey/token）");
        } else {
            c.setStatus("unconnected");
            c.setLastError("缺少凭据字段：" + (def.tokenField() == null ? "accessToken" : def.tokenField()));
        }
        credentials.save(c);
        return status(key);
    }

    // ── 真探测 ──────────────────────────────────────────────────────────

    /**
     * 真 MCP 测试连接：initialize + notifications/initialized + tools/list（经生产传输层与
     * 连接器加密凭据源）。返回 {@code probeLevel} 让前端区分「握手成功」与「仅能连上」。
     */
    public TestResponse test(String key) {
        ConnectorDefinition def = catalog.require(key);
        // 非 supported 档不发 MCP 握手（gateway/internal/stdio 无真实直连地址；credentialOnly 不绑 mcp）
        if (def.unavailable()) {
            return new TestResponse(false,
                    "该连接器暂不可用：" + def.unavailableMessage(), 0L, "none", List.of(),
                    null, "unavailable");
        }
        if (!def.supported()) {
            return new TestResponse(false, "该连接器仅支持保存凭据，暂不能运行或测试连接",
                    0L, "none", List.of(), null, "credential_only");
        }
        ConnectorCredential c = credentials.find(key).orElse(null);
        if (c == null) {
            return connectorProbe.probe(def, Map.of(), false);
        }
        if (def.isOAuth()) {
            String token = credentials.decrypt(c.getAccessTokenEncrypted());
            return connectorProbe.probe(def, Map.of(), !isBlank(token));
        }
        // bearer/header/query：从整包密文里取模板占位值；有 extra 即视为已存凭据
        Map<String, String> values = decryptFields(c.getExtraEncrypted());
        boolean hasCredential = !values.isEmpty() || c.getAccessTokenEncrypted() != null;
        return connectorProbe.probe(def, values, hasCredential);
    }

    private Map<String, String> decryptFields(String encrypted) {
        String json = credentials.decrypt(encrypted);
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ── 断开 ────────────────────────────────────────────────────────────

    /** 断开连接（幂等）：删凭据行 + 删连接器贡献的 mcp server。 */
    public RevokedResponse revoke(String key) {
        catalog.require(key);
        mcpBinding.removeServer(key);
        credentials.delete(key);
        return new RevokedResponse(true);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────

    /**
     * 是否已有用户凭据（详情抽屉显示「已有凭据/未配置」）。
     *
     * <p>只认 access/refresh/extra 三列密文：DCR 注册得到的 clientSecret 不算用户凭据
     * （否则「刚点过授权但没登录」也会显示已有凭据）。
     */
    static boolean hasCredentials(ConnectorCredential c) {
        if (c == null) {
            return false;
        }
        return c.getAccessTokenEncrypted() != null
                || c.getRefreshTokenEncrypted() != null
                || c.getExtraEncrypted() != null;
    }

    static boolean isConnected(ConnectorCredential c) {
        if (c == null || !"connected".equals(c.getStatus()) || c.getAccessTokenEncrypted() == null) {
            return false;
        }
        return c.getExpiresAt() == null || c.getExpiresAt() > System.currentTimeMillis();
    }

    private static List<String> missingScopes(ConnectorDefinition def, ConnectorCredential c,
                                              boolean connected) {
        if (!connected || c == null || c.getScope() == null || c.getScope().isBlank()) {
            return List.of();
        }
        Set<String> granted = new LinkedHashSet<>(Arrays.asList(c.getScope().trim().split("\\s+")));
        return def.scopes().stream().filter(s -> !granted.contains(s)).toList();
    }

    /** 主凭据：catalog.tokenField 优先，其次兼容历史键名。 */
    private static String tokenOf(ConnectorDefinition def, Map<String, String> fields) {
        if (def.tokenField() != null && !def.tokenField().isBlank()) {
            String v = fields.get(def.tokenField());
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return first(fields, "accessToken", "access_token", "apiKey", "api_key",
                "key", "apikey", "token", "personalToken");
    }

    private static String first(Map<String, String> fields, String... keys) {
        for (String k : keys) {
            String v = fields.get(k);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
