package com.nexusai.domain.connector;

import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.connector.ConnectorDefinition;
import com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 官方 MCP 绑定：query 模板渲染进 command，header 模板写进 env（mcp_servers.headers）。
 */
class ConnectorMcpBindingTemplateTest {

    private McpServerMapper serverMapper;
    private ConnectorMcpMarkerMapper markerMapper;
    private ConnectorMcpBinding binding;

    @BeforeEach
    void setUp() {
        serverMapper = mock(McpServerMapper.class);
        markerMapper = mock(ConnectorMcpMarkerMapper.class);
        binding = new ConnectorMcpBinding(serverMapper, mock(McpServerService.class), markerMapper);
        when(serverMapper.selectOneByName(anyString())).thenReturn(null);
    }

    @Test
    @DisplayName("query 模板：key 渲染进 URL（encode），marker 仍写入")
    void queryTemplateRenderedIntoUrl() {
        ConnectorDefinition baidu = def("baidu-map", "apiKey", "query",
                null, "https://mcp.map.baidu.com/mcp?ak={ak}", Map.of(), "ak");

        binding.ensureServer(baidu, Map.of("ak", "K&1"));

        ArgumentCaptor<McpServerRecord> captor = ArgumentCaptor.forClass(McpServerRecord.class);
        verify(serverMapper).insert(captor.capture());
        assertThat(captor.getValue().getCommand())
                .isEqualTo("https://mcp.map.baidu.com/mcp?ak=K%261");
        assertThat(captor.getValue().getEnv()).isNull();
        verify(markerMapper).writeMarker(anyString(), eq(ConnectorMcpBinding.markerJson("baidu-map")));
    }

    @Test
    @DisplayName("header 模板：渲染后写入 env（JSON），URL 用 mcpUrl")
    void headerTemplateWritesEnv() {
        ConnectorDefinition feishu = def("feishu-cli", "apiKey", "header",
                "https://mcp.feishu.cn/mcp", null,
                Map.of("X-Lark-MCP-UAT", "{accessToken}",
                        "X-Lark-MCP-Allowed-Tools", "{allowedTools}"), "accessToken");

        binding.ensureServer(feishu, Map.of("accessToken", "uat-1", "allowedTools", "*"));

        ArgumentCaptor<McpServerRecord> captor = ArgumentCaptor.forClass(McpServerRecord.class);
        verify(serverMapper).insert(captor.capture());
        assertThat(captor.getValue().getCommand()).isEqualTo("https://mcp.feishu.cn/mcp");
        assertThat(captor.getValue().getEnv())
                .contains("\"X-Lark-MCP-UAT\":\"uat-1\"")
                .contains("\"X-Lark-MCP-Allowed-Tools\":\"*\"");
    }

    @Test
    @DisplayName("缺占位值 → 400，不落库")
    void missingPlaceholderFails() {
        ConnectorDefinition baidu = def("baidu-map", "apiKey", "query",
                null, "https://mcp.map.baidu.com/mcp?ak={ak}", Map.of(), "ak");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> binding.ensureServer(baidu, Map.of()))
                .isInstanceOf(com.nexusai.infra.exception.ValidationException.class);
        verify(serverMapper, org.mockito.Mockito.never()).insert(any());
    }

    private static ConnectorDefinition def(String key, String authType, String authMode,
                                           String url, String template,
                                           Map<String, String> headers, String tokenField) {
        return new ConnectorDefinition(
                key, key, "desc", authType, "office", List.of(),
                url, null, "http", null, null, null, null,
                false, false, false, null, null, key, "#000000",
                "available", "remote", "supported", null,
                List.of(), true, authMode, template, headers, tokenField);
    }
}
