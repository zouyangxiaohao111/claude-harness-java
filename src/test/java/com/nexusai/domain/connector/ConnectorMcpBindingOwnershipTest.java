package com.nexusai.domain.connector;

import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.model.connector.ConnectorDefinition;
import com.nexusai.repository.connector.mapper.ConnectorMcpMarkerMapper;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * mcp_servers 归属标记（[2026-11-23] 防误删）：
 * 连接器只删「标记 source=connector 且 connectorKey 匹配」的行；用户自建同名 server 永不被删。
 */
class ConnectorMcpBindingOwnershipTest {

    private McpServerMapper serverMapper;
    private McpServerService serverService;
    private ConnectorMcpMarkerMapper markerMapper;
    private ConnectorMcpBinding binding;

    @BeforeEach
    void setUp() {
        serverMapper = mock(McpServerMapper.class);
        serverService = mock(McpServerService.class);
        markerMapper = mock(ConnectorMcpMarkerMapper.class);
        binding = new ConnectorMcpBinding(serverMapper, serverService, markerMapper);
    }

    @Test
    @DisplayName("标记 JSON 可往返；其它 key / 旧 NULL 标记不算本连接器")
    void markerSemantics() {
        String marker = ConnectorMcpBinding.markerJson("notion");
        assertThat(ConnectorMcpBinding.isConnectorMarkerFor(marker, "notion")).isTrue();
        assertThat(ConnectorMcpBinding.isConnectorMarkerFor(marker, "dida365")).isFalse();
        assertThat(ConnectorMcpBinding.isConnectorMarkerFor(null, "notion")).isFalse();
        assertThat(ConnectorMcpBinding.isConnectorMarkerFor("{\"source\":\"plugin\"}", "notion")).isFalse();
        assertThat(ConnectorMcpBinding.isConnectorMarkerFor("not-json", "notion")).isFalse();
    }

    @Test
    @DisplayName("断开：带本连接器标记才删（scope 不再作为判据）")
    void removeDeletesOwnedServer() {
        when(serverMapper.selectOneByName("notion")).thenReturn(row("mcp-1", "notion", "user"));
        when(markerMapper.findMarkerByName("notion"))
                .thenReturn(ConnectorMcpBinding.markerJson("notion"));

        binding.removeServer("notion");

        verify(serverService).delete("mcp-1");
    }

    @Test
    @DisplayName("断开：无标记（用户自建同名）→ 不删")
    void removeSkipsUnmarkedServer() {
        when(serverMapper.selectOneByName("notion")).thenReturn(row("mcp-2", "notion", "project"));
        when(markerMapper.findMarkerByName("notion")).thenReturn(null);

        binding.removeServer("notion");

        verify(serverService, never()).delete(anyString());
    }

    @Test
    @DisplayName("断开：别的连接器/插件的标记 → 不删")
    void removeSkipsForeignMarker() {
        when(serverMapper.selectOneByName("notion")).thenReturn(row("mcp-3", "notion", "user"));
        when(markerMapper.findMarkerByName("notion"))
                .thenReturn(ConnectorMcpBinding.markerJson("dida365"));

        binding.removeServer("notion");

        verify(serverService, never()).delete(anyString());
    }

    @Test
    @DisplayName("同名异 URL（非本连接器建的）→ 拒绝覆盖（ConflictException）")
    void ensureRejectsForeignSameNameDifferentUrl() {
        McpServerRecord foreign = row("mcp-x", "notion", "project");
        foreign.setCommand("https://other.example.com/mcp");
        when(serverMapper.selectOneByName("notion")).thenReturn(foreign);
        when(markerMapper.findMarkerByName("notion")).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> binding.ensureServer(def()))
                .isInstanceOf(com.nexusai.infra.exception.ConflictException.class);

        verify(serverMapper, never()).update(any(McpServerRecord.class));
        verify(markerMapper, never()).writeMarker(anyString(), anyString());
    }

    @Test
    @DisplayName("连接成功：写入归属标记")
    void ensureWritesMarker() {
        when(serverMapper.selectOneByName("notion")).thenReturn(null);

        binding.ensureServer(def());

        verify(serverMapper).insert(any(McpServerRecord.class));
        verify(markerMapper).writeMarker(anyString(), eq(ConnectorMcpBinding.markerJson("notion")));
    }

    private static McpServerRecord row(String id, String name, String scope) {
        McpServerRecord r = new McpServerRecord();
        r.setId(id);
        r.setName(name);
        r.setScope(scope);
        r.setType("http");
        r.setCommand("https://mcp.notion.com/mcp");
        return r;
    }

    private static ConnectorDefinition def() {
        return new ConnectorDefinition(
                "notion", "Notion", "desc", "oauth2", "knowledge", List.of(),
                "https://mcp.notion.com/mcp", "https://mcp.notion.com", "http", null, null, null, null,
                false, false, false, null, null, "notion", "#000000");
    }
}
