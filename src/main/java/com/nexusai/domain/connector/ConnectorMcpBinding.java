package com.nexusai.domain.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.model.connector.ConnectorDefinition;
import com.nexusai.model.mcp.McpServer;
import com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 连接器 → MCP server 绑定（已裁定：连接器 key 即 mcp server 名，工具管道复用，不加映射表）。
 *
 * <p>授权成功后 upsert 一条 {@code mcp_servers}（scope=user、approved、enabled、autoStart），
 * 与插件 MCP 供给（{@code McpServerService.upsertPluginMcpServers}）走同一个私有的
 * {@code upsertServer} 落库形态：远程 server 的 URL 存在 {@code command} 列（现有 Java 契约）。
 * 这里直接用 Mapper 落库，避免 {@code create()} 的「同名即 409」把重复授权卡死。
 *
 * <p><b>防劫持</b>：若 DB 已有同名 server 且指向别的地址，拒绝覆盖（用户手建的 server 不能被连接器
 * 静默改掉）。
 */
@Component
public class ConnectorMcpBinding {

    private static final Logger log = LoggerFactory.getLogger(ConnectorMcpBinding.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpServerMapper mcpServerMapper;
    private final McpServerService mcpServerService;
    private final ConnectorMcpMarkerMapper markerMapper;

    public ConnectorMcpBinding(McpServerMapper mcpServerMapper,
                               McpServerService mcpServerService,
                               ConnectorMcpMarkerMapper markerMapper) {
        this.mcpServerMapper = mcpServerMapper;
        this.mcpServerService = mcpServerService;
        this.markerMapper = markerMapper;
    }

    /**
     * 授权成功后确保 mcp_servers 有对应 server（user scope）· OAuth 路径（无模板凭据）。
     *
     * @throws ConflictException 同名 server 指向其它地址（拒绝劫持）
     */
    public void ensureServer(ConnectorDefinition def) {
        ensureServer(def, Map.of());
    }

    /**
     * 按凭据值绑定 MCP server（[2026-11-23] 官方 MCP 模板）。
     *
     * <p>URL/headers 由 {@link ConnectorEndpointResolver} 渲染（query 参数 / 自定义头）；
     * headers 写入 {@code mcp_servers.env}（HttpMcpTransport/SseMcpTransport 会带出去）。
     * ⛔ 日志不打印含 token 的完整 URL/头。
     *
     * @param def             连接器定义
     * @param credentialValues 已解密的凭据字段（token/key/companyFrom/…）
     */
    public void ensureServer(ConnectorDefinition def, Map<String, String> credentialValues) {
        String url = ConnectorEndpointResolver.endpoint(def, credentialValues);
        Map<String, String> headers = ConnectorEndpointResolver.headers(def, credentialValues);
        McpServerRecord existing = mcpServerMapper.selectOneByName(def.key());
        boolean ownsExisting = existing != null
                && isConnectorMarkerFor(markerMapper.findMarkerByName(def.key()), def.key());
        if (existing != null && !ownsExisting
                && existing.getCommand() != null && !existing.getCommand().isBlank()
                && !existing.getCommand().equals(url)) {
            throw new ConflictException("已存在同名 MCP server（" + def.key()
                    + "）且指向其它地址，请先改名或删除后再连接");
        }
        McpServer s = existing != null ? existing.toDomain() : newServer(def);
        s.setType(def.mcpTransport());
        s.setCommand(url);
        s.setScope("user");
        s.setApprovalStatus("approved");
        s.setEnabled(true);
        s.setAutoStart(true);
        s.setLastError(null);
        s.setPid(null);
        if (!headers.isEmpty()) {
            // 合并既有 env（远程 server 的 env = headers），不覆盖其它已有头
            Map<String, String> env = parseEnv(existing == null ? null : existing.getEnv());
            env.putAll(headers);
            try {
                s.setEnv(JSON.writeValueAsString(env));
            } catch (Exception e) {
                throw new IllegalStateException("序列化 MCP headers 失败: " + e.getMessage(), e);
            }
        }
        McpServerRecord record = McpServerRecord.fromDomain(s);
        if (existing != null) {
            mcpServerMapper.update(record);
        } else {
            mcpServerMapper.insert(record);
        }
        // 归属标记：断开时只删本标记且 key 匹配的行（用户自建同名 server 不受影响）
        markerMapper.writeMarker(s.getId(), markerJson(def.key()));
        log.info("[ConnectorMcpBinding] 已 upsert mcp server name={} type={} authMode={} headers={}",
                def.key(), def.mcpTransport(), def.authMode(), headers.keySet());
    }

    private static Map<String, String> parseEnv(String envJson) {
        Map<String, String> env = new java.util.LinkedHashMap<>();
        if (envJson == null || envJson.isBlank()) {
            return env;
        }
        try {
            JsonNode node = JSON.readTree(envJson);
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(e -> {
                    if (e.getValue() != null && e.getValue().isValueNode()) {
                        env.put(e.getKey(), e.getValue().asText());
                    }
                });
            }
        } catch (Exception ignored) {
            // 历史脏 env → 从空开始（headers 仍会写入）
        }
        return env;
    }

    /** 断开连接时清掉连接器贡献的 server（只删带本连接器归属标记的行，best-effort）。 */
    public void removeServer(String connectorKey) {
        McpServerRecord existing = mcpServerMapper.selectOneByName(connectorKey);
        if (existing == null) {
            return;
        }
        // 归属门：必须带 connector 标记且 connectorKey 一致 ⇒ 绝不误删用户自建同名 server
        if (!isConnectorMarkerFor(markerMapper.findMarkerByName(connectorKey), connectorKey)) {
            return;
        }
        try {
            mcpServerService.delete(existing.getId());
            log.info("[ConnectorMcpBinding] 已删除连接器对应的 mcp server name={}", connectorKey);
        } catch (Exception e) {
            log.warn("[ConnectorMcpBinding] 删除 mcp server 失败（best-effort）name={}: {}",
                    connectorKey, e.toString());
        }
    }

    /** 归属标记 JSON：{"source":"connector","connectorKey":"<key>"}。 */
    static String markerJson(String connectorKey) {
        ObjectNode node = JSON.createObjectNode();
        node.put("source", "connector");
        node.put("connectorKey", connectorKey);
        return node.toString();
    }

    /** 标记是否属于该连接器（source=connector 且 connectorKey 相等）。 */
    static boolean isConnectorMarkerFor(String markerJson, String connectorKey) {
        if (markerJson == null || markerJson.isBlank() || connectorKey == null) {
            return false;
        }
        try {
            JsonNode node = JSON.readTree(markerJson);
            return node != null
                    && "connector".equals(node.path("source").asText(null))
                    && connectorKey.equals(node.path("connectorKey").asText(null));
        } catch (Exception e) {
            return false;
        }
    }

    private static McpServer newServer(ConnectorDefinition def) {
        McpServer s = new McpServer();
        s.setId("mcp-" + UUID.randomUUID().toString().substring(0, 8));
        s.setName(def.key());
        s.setCreatedAt(OffsetDateTime.now().format(ISO));
        return s;
    }
}
