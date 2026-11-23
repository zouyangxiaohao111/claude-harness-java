package com.nexusai.application.agent.app;

import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.app.dto.AppActionResult;
import com.nexusai.model.app.dto.AppDto;
import com.nexusai.model.mcp.dto.McpRuntimeStatus;
import com.nexusai.model.mcp.dto.McpServerDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用目录（{@code GET /api/v1/apps}）：<b>只含 MCP 应用</b>。
 *
 * <p>用户 2026-11-23 裁定：浏览器 / computer-use 不需要应用指定，回工具面板（
 * {@code SessionToolCategory} 的 BR/CU 分类与会话级开关保持原样），应用层只留 MCP。
 *
 * <p><b>只读</b>，不建表；MCP 条目来自 DB 配置 + 运行时连接态。可用性按
 * {@code McpRuntimeStatus} 判定（与前端回落口径同源）。
 */
@Service
public class AppCatalogService {

    /** 应用 kind（应用层只含 MCP）。 */
    private static final String KIND_MCP = "mcp";
    /** 服务在配置里被禁用。 */
    static final String REASON_DISABLED = "应用已禁用";
    /** MCP：需 OAuth / 凭据。 */
    static final String REASON_NEEDS_AUTH = "需要先授权";
    /** MCP：本进程连接失败。 */
    static final String REASON_FAILED = "连接失败";
    /** MCP：等待审批。 */
    static final String REASON_PENDING = "等待审批";
    /** MCP：启用但本进程没连。 */
    static final String REASON_NOT_CONNECTED = "未连接（启动后可用）";

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(AppCatalogService.class);

    private final McpServerService mcpServerService;
    private final com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper connectorMarkerMapper;
    private final com.nexusai.domain.connector.ConnectorCatalog connectorCatalog;

    /** 会话 enabled_apps 写入（应用 start/stop）。字段注入：避免与 SessionService 的早期装配互相牵制。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.domain.session.SessionService sessionService;

    public AppCatalogService(McpServerService mcpServerService,
                             com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper connectorMarkerMapper,
                             com.nexusai.domain.connector.ConnectorCatalog connectorCatalog) {
        this.mcpServerService = mcpServerService;
        this.connectorMarkerMapper = connectorMarkerMapper;
        this.connectorCatalog = connectorCatalog;
        // [V337] 安装 defaultOpen 种子供应器：enabled_apps=NULL 时的 schema 门种子。
        AppEnablement.installDefaultOpenKeysSupplier(this::defaultOpenAppKeys);
    }

    /**
     * defaultOpen 种子（enabled_apps 未设置时的有效启动集）：MCP 应用内
     * connector 安装的=false，其余=true（应用层只含 MCP）。
     *
     * <p><b>fail-soft 方向</b>：DB 读失败（server 列表或标记读取异常）⇒ 返回 {@code null}
     * （= 无种子 ⇒ 上层不裁剪）；⛔ 不返回空集（空集会把全部应用工具裁掉）。
     *
     * @return 种子应用 key 集合；null = 读取失败（不裁剪）
     */
    public java.util.Set<String> defaultOpenAppKeys() {
        try {
            List<McpServerDto> servers = mcpServerService == null
                ? List.of() : mcpServerService.listAll();
            if (servers == null) {
                return null;
            }
            java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> markers = loadMarkers();
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            for (McpServerDto s : servers) {
                if (s != null && s.name() != null && !s.name().isBlank()
                        && AppEnablement.defaultOpenMcp(
                            isConnectorMarker(markers.get(s.name())))) {
                    out.add(s.name());
                }
            }
            return java.util.Set.copyOf(out);
        } catch (Exception e) {
            log.warn("[AppCatalog] defaultOpen 种子读取失败（按「不裁剪」处理）: {}", e.toString());
            return null;
        }
    }

    /**
     * 列出应用目录（应用层只含 MCP；浏览器 / computer-use 回工具面板，不在此列出）。
     *
     * @return MCP 条目（DB server）；异常一律 fail-soft 为空/原因，不抛
     */
    public List<AppDto> listApps() {
        return List.copyOf(mcpApps());
    }

    private List<AppDto> mcpApps() {
        List<McpServerDto> servers;
        try {
            servers = mcpServerService == null ? List.of() : mcpServerService.listAll();
        } catch (Exception e) {
            return List.of();
        }
        if (servers == null || servers.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> markers = loadMarkersSafe();
        List<AppDto> out = new ArrayList<>(servers.size());
        for (McpServerDto s : servers) {
            if (s == null || s.name() == null || s.name().isBlank()) {
                continue;
            }
            String display = s.userFacingName() != null && !s.userFacingName().isBlank()
                ? s.userFacingName() : s.name();
            String reason = mcpUnusableReason(s);
            List<String> tools = mcpToolNames(s.name());
            com.fasterxml.jackson.databind.JsonNode marker = markers.get(s.name());
            boolean connectorInstalled = isConnectorMarker(marker);
            out.add(new AppDto(
                s.name(), KIND_MCP, display, null,
                tools.size(), tools,
                true,                       // MCP 不在默认禁用集 ⇒ 缺省算开
                AppEnablement.defaultOpenMcp(connectorInstalled),
                reason == null,
                reason,
                s.runtimeStatus() != null ? s.runtimeStatus().name() : null,
                null,
                mcpIcon(marker),
                s.id(),
                canStart(s),
                blankToNull(s.lastError())));
        }
        return out;
    }

    /** 批量读全部 {@code config_json} 标记（name → JSON）；读取异常向上抛（种子 fail-soft 用）。 */
    private java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> loadMarkers()
            throws Exception {
        if (connectorMarkerMapper == null) {
            return java.util.Map.of();
        }
        java.util.List<java.util.Map<String, Object>> rows = connectorMarkerMapper.findAllMarkers();
        if (rows == null || rows.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> out = new java.util.HashMap<>();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        for (java.util.Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Object name = row.get("name");
            Object marker = row.get("marker");
            if (!(name instanceof String n) || (marker == null)
                    || marker.toString().isBlank()) {
                continue;
            }
            try {
                out.put(n, om.readTree(marker.toString()));
            } catch (Exception ignore) {
                // 单行脏标记跳过（不影响其它应用）
            }
        }
        return out;
    }

    /** 列表渲染用：标记读取失败 ⇒ 空 map（不阻断目录）。 */
    private java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> loadMarkersSafe() {
        try {
            return loadMarkers();
        } catch (Exception e) {
            log.warn("[AppCatalog] 读取应用标记失败（按无标记渲染）: {}", e.toString());
            return java.util.Map.of();
        }
    }

    /**
     * 应用「启动」：启用并启动 MCP server（失败如实返回原因），成功后把 key 加入会话 enabled_apps。
     *
     * @param key       应用 key（= server name）
     * @param sessionId 目标会话（必填）
     */
    public AppActionResult startApp(String key, String sessionId) {
        requireSession(sessionId);
        McpServerDto server = findServer(key);
        if (server == null) {
            throw new com.nexusai.infra.exception.NotFoundException("App " + key + " not found");
        }
        boolean started = false;
        String message = null;
        try {
            mcpServerService.enableAndStart(server.id(), sessionId);
            started = true;
        } catch (RuntimeException e) {
            message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[AppCatalog] 应用启动失败 key={} session={}: {}", key, sessionId, message);
        }
        if (started) {
            addEnabledApp(sessionId, key);
        }
        return new AppActionResult(findApp(key), effectiveEnabled(sessionId), started, message);
    }

    /** 应用「停止」：仅从会话 enabled_apps 移除（不全局停 server）。 */
    public AppActionResult stopApp(String key, String sessionId) {
        requireSession(sessionId);
        if (findServer(key) == null) {
            throw new com.nexusai.infra.exception.NotFoundException("App " + key + " not found");
        }
        removeEnabledApp(sessionId, key);
        return new AppActionResult(findApp(key), effectiveEnabled(sessionId), false, null);
    }

    private McpServerDto findServer(String key) {
        for (McpServerDto s : safeServers()) {
            if (s != null && key != null && key.equals(s.name())) {
                return s;
            }
        }
        return null;
    }

    private AppDto findApp(String key) {
        return mcpApps().stream().filter(a -> key != null && key.equals(a.key()))
            .findFirst().orElse(null);
    }

    private List<McpServerDto> safeServers() {
        try {
            List<McpServerDto> servers = mcpServerService == null ? null : mcpServerService.listAll();
            return servers == null ? List.of() : servers;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> effectiveEnabled(String sessionId) {
        List<String> raw = com.nexusai.application.agent.tool.SessionToolVisibility
            .effectiveEnabledAppKeys(sessionId);
        return raw == null ? List.of() : raw;
    }

    private void addEnabledApp(String sessionId, String key) {
        java.util.TreeSet<String> set = new java.util.TreeSet<>(effectiveEnabled(sessionId));
        set.add(key);
        sessionService.setEnabledApps(sessionId, List.copyOf(set));
    }

    private void removeEnabledApp(String sessionId, String key) {
        java.util.TreeSet<String> set = new java.util.TreeSet<>(effectiveEnabled(sessionId));
        set.remove(key);
        sessionService.setEnabledApps(sessionId, List.copyOf(set));
    }

    private static void requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new com.nexusai.infra.exception.ValidationException("sessionId is required");
        }
    }

    /** 是否可点「启动」：未连接且非 pending 审批（已禁用也可点，启动时会先启用）。 */
    private static boolean canStart(McpServerDto s) {
        boolean connected = s.runtimeStatus() == McpRuntimeStatus.connected;
        boolean pending = "pending".equals(s.approvalStatus());
        return !connected && !pending;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static boolean isConnectorMarker(com.fasterxml.jackson.databind.JsonNode marker) {
        return marker != null && "connector".equals(marker.path("source").asText());
    }

    /**
     * MCP 应用图标（优先级）：
     * <ol>
     *   <li>连接器安装：连接器 catalog 条目的 icon（{@code connectorKey} → {@link ConnectorCatalog#find}）；</li>
     *   <li>否则配置标记里的 {@code icon}（其它 MCP server 的显式配置）；</li>
     *   <li>都没有 ⇒ null（前端回落首字母）。</li>
     * </ol>
     */
    private String mcpIcon(com.fasterxml.jackson.databind.JsonNode marker) {
        if (marker == null) {
            return null;
        }
        if (isConnectorMarker(marker) && connectorCatalog != null) {
            String connectorKey = marker.path("connectorKey").asText("");
            if (!connectorKey.isBlank()) {
                String icon = connectorCatalog.find(connectorKey)
                    .map(com.nexusai.model.connector.ConnectorDefinition::icon)
                    .orElse(null);
                if (icon != null && !icon.isBlank()) {
                    return icon;
                }
            }
        }
        String configured = marker.path("icon").asText("");
        return configured.isBlank() ? null : configured;
    }

    /** MCP server 名下当前注册的原始 schema 工具名（server 段规范化后比对，排序去重）。 */
    private List<String> mcpToolNames(String serverName) {
        List<Tool> tools = liveMcpTools();
        if (tools.isEmpty()) {
            return List.of();
        }
        String normalized = McpStringUtils.normalizeNameForMCP(serverName);
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (Tool t : tools) {
            String name = t == null ? null : t.name();
            if (name == null) {
                continue;
            }
            String server = SessionToolCategory.mcpServerOf(name);
            if (normalized.equals(server)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private List<Tool> liveMcpTools() {
        try {
            List<Tool> tools = mcpServerService == null ? null : mcpServerService.getCurrentTools();
            return tools == null ? List.of() : tools;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * MCP 条目不可用原因（与前端 {@code unusableReasonOf} 同口径）。
     *
     * @return null = 可用
     */
    static String mcpUnusableReason(McpServerDto s) {
        McpRuntimeStatus rt = s.runtimeStatus();
        if (rt == McpRuntimeStatus.connected) {
            return null;
        }
        if (!s.enabled() || rt == McpRuntimeStatus.disabled) {
            return REASON_DISABLED;
        }
        if (rt == McpRuntimeStatus.needsAuth) {
            return REASON_NEEDS_AUTH;
        }
        if (rt == McpRuntimeStatus.failed) {
            return REASON_FAILED;
        }
        if (rt == McpRuntimeStatus.pending) {
            return REASON_PENDING;
        }
        return REASON_NOT_CONNECTED;
    }
}
