package com.nexusai.application.agent.app;

import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.app.dto.AppDto;
import com.nexusai.model.mcp.dto.McpRuntimeStatus;
import com.nexusai.model.mcp.dto.McpServerDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用目录（{@code GET /api/v1/apps}）· 应用层只含 MCP（浏览器 / CU 回工具面板）。
 *
 * <p>覆盖：只返回 MCP、排序不混入静态条目、defaultOpen（connector 安装=false）、
 * icon 优先级（连接器 catalog → 配置 icon → null）、可用性原因、tools 原始名。
 */
@DisplayName("应用目录 · 只含 MCP（defaultOpen / icon / tools）")
class AppCatalogServiceTest {

    private static Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() { return null; }
            @Override public AgentToolResult<?> execute(ToolUseBlock call) {
                return ToolResult.success(call.id(), name);
            }
        };
    }

    private static McpServerDto server(String name, boolean enabled, McpRuntimeStatus rt) {
        return new McpServerDto(
            "id-" + name, name, "cmd", List.of(), Map.of(), null, null, enabled, null,
            "stdio", "approved", null, false, null, null, null, null, null, null,
            false, rt);
    }

    private static AppDto find(List<AppDto> apps, String key) {
        return apps.stream().filter(a -> key.equals(a.key())).findFirst().orElseThrow();
    }

    private static com.nexusai.model.connector.ConnectorDefinition connectorDef(String key,
                                                                                String icon) {
        return new com.nexusai.model.connector.ConnectorDefinition(
            key, key, null, "oauth2", null, List.of(), null, null, "http",
            null, null, null, null, null, null, null, null, null, icon, null);
    }

    @AfterEach
    void tearDown() {
        AppEnablement.reset();
    }

    @Test
    @DisplayName("只返回 MCP：不再出现 browser / computer-use 静态条目")
    void listApps_onlyMcp() {
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.listAll()).thenReturn(List.of(
            server("notion", true, McpRuntimeStatus.connected),
            server("off", true, McpRuntimeStatus.disabled)));
        AppCatalogService svc = new AppCatalogService(
            mcp, mock(com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper.class),
            mock(com.nexusai.domain.connector.ConnectorCatalog.class));

        List<AppDto> apps = svc.listApps();

        assertThat(apps).extracting(AppDto::key).containsExactly("notion", "off");
        assertThat(apps).allMatch(a -> "mcp".equals(a.kind()));
    }

    @Test
    @DisplayName("MCP 条目：可用性/工具名/defaultOpen/icon（null 或不透传）")
    void mcpApps_availabilityDefaultOpenAndIcon() {
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.listAll()).thenReturn(List.of(
            server("notion", true, McpRuntimeStatus.connected),
            server("off", true, McpRuntimeStatus.disabled),
            server("idle", true, McpRuntimeStatus.stopped)));
        when(mcp.getCurrentTools()).thenReturn(List.of(
            tool("mcp__notion__search"), tool("mcp__notion__create")));
        com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper markers =
            mock(com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper.class);
        // 批量读（一次）——不再 N+1
        when(markers.findAllMarkers()).thenReturn(List.of(
            Map.of("name", "notion",
                "marker", "{\"source\":\"connector\",\"connectorKey\":\"notion\"}"),
            Map.of("name", "off", "marker", "{\"icon\":\"custom-icon\"}")));
        com.nexusai.domain.connector.ConnectorCatalog catalog =
            mock(com.nexusai.domain.connector.ConnectorCatalog.class);
        when(catalog.find("notion")).thenReturn(java.util.Optional.of(
            connectorDef("notion", "notion-icon")));
        AppCatalogService svc = new AppCatalogService(mcp, markers, catalog);

        List<AppDto> apps = svc.listApps();
        AppDto notion = find(apps, "notion");
        assertThat(notion.kind()).isEqualTo("mcp");
        assertThat(notion.usable()).isTrue();
        assertThat(notion.disabledReason()).isNull();
        assertThat(notion.toolCount()).isEqualTo(2);
        assertThat(notion.tools()).containsExactly(
            "mcp__notion__create", "mcp__notion__search");
        assertThat(notion.runtimeStatus()).isEqualTo("connected");
        assertThat(notion.defaultEnabled()).isTrue();
        assertThat(notion.defaultOpen()).isFalse(); // connector 安装 ≠ 默认开
        assertThat(notion.icon()).isEqualTo("notion-icon"); // 连接器 catalog 图标优先

        assertThat(find(apps, "off").disabledReason()).isEqualTo("应用已禁用");
        assertThat(find(apps, "idle").disabledReason()).isEqualTo("未连接（启动后可用）");
        assertThat(find(apps, "off").defaultOpen()).isTrue(); // 既有 server
        assertThat(find(apps, "off").icon()).isEqualTo("custom-icon"); // 配置 icon 透传
        assertThat(find(apps, "idle").icon()).isNull(); // 无 icon → null
        verify(markers, times(1)).findAllMarkers(); // 批量读，无 N+1
    }

    @Test
    @DisplayName("种子 fail-soft：DB 读失败 ⇒ null（不裁剪），⛔ 不是空集")
    void defaultOpenSeed_dbFailure_returnsNull() {
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.listAll()).thenThrow(new RuntimeException("db down"));
        AppCatalogService svc = new AppCatalogService(
            mcp, mock(com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper.class),
            mock(com.nexusai.domain.connector.ConnectorCatalog.class));

        assertThat(svc.defaultOpenAppKeys()).isNull();
        assertThat(svc.listApps()).isEmpty();
    }

    @Test
    @DisplayName("defaultOpen 种子：connector 安装=false，其余 MCP=true")
    void defaultOpenSeed_rules() {
        McpServerService mcp = mock(McpServerService.class);
        when(mcp.listAll()).thenReturn(List.of(
            server("notion", true, McpRuntimeStatus.connected),
            server("off", true, McpRuntimeStatus.stopped)));
        com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper markers =
            mock(com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper.class);
        when(markers.findAllMarkers()).thenReturn(List.of(
            Map.of("name", "notion",
                "marker", "{\"source\":\"connector\",\"connectorKey\":\"notion\"}")));
        AppCatalogService svc = new AppCatalogService(
            mcp, markers, mock(com.nexusai.domain.connector.ConnectorCatalog.class));

        assertThat(svc.defaultOpenAppKeys()).contains("off").doesNotContain("notion");
    }
}
