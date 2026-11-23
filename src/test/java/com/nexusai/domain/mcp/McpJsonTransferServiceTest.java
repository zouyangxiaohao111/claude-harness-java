package com.nexusai.domain.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP JSON 导入/导出（DB 真源）· 掩码、严格校验、diff、备份。
 */
class McpJsonTransferServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private McpServerMapper mapper;
    private McpJsonTransferService service;

    @BeforeEach
    void setUp() {
        mapper = mock(McpServerMapper.class);
        service = new McpJsonTransferService(mapper);
        service.setBackupDirForTests(temp.resolve("backups"));
    }

    @Test
    @DisplayName("导出：env/header 值掩码（****末4），URL 敏感 query 参数掩码，剥 __mcp_oauth__，仅 stdio/http/sse")
    void exportMasks() {
        when(mapper.selectAll()).thenReturn(List.of(
                row("s1", "stdio", "/bin/x", "[\"--t=1\"]",
                        "{\"API_KEY\":\"abcd1234\",\"DEBUG\":\"1\"}", true, true, "user"),
                row("s2", "http", "https://mcp.example/mcp?key=SECRET1234&x=1",
                        null, "{\"X-Lark-MCP-UAT\":\"uat-abcd\",\"__mcp_oauth__\":\"b64\"}",
                        true, false, "user"),
                row("s3", "ws", "wss://x", null, null, true, true, "user")));

        ObjectNode out = service.exportMasked();
        JsonNode servers = out.get("mcpServers");
        assertThat(servers.has("s3")).isFalse();
        assertThat(servers.path("s1").path("env").path("API_KEY").asText()).isEqualTo("****1234");
        assertThat(servers.path("s1").path("env").path("DEBUG").asText()).isEqualTo("****");
        assertThat(servers.path("s1").path("args").get(0).asText()).isEqualTo("--t=1");
        assertThat(servers.path("s2").path("url").asText())
                .contains("key=****1234").contains("x=1");
        assertThat(servers.path("s2").path("headers").has("__mcp_oauth__")).isFalse();
        assertThat(servers.path("s2").path("headers").path("X-Lark-MCP-UAT").asText())
                .isEqualTo("****abcd");
        assertThat(servers.path("s1").path("scope").asText()).isEqualTo("user");
    }

    @Test
    @DisplayName("校验失败：未知字段整单拒绝，不写库")
    void validationFailureNoWrite() {
        JsonNode body = json("""
                {"mcpServers":{"bad":{"type":"http","url":"https://x","foo":1}}}
                """);

        assertThatThrownBy(() -> service.importJson(body))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("未知字段");
        verify(mapper, never()).selectAll();
        verify(mapper, never()).insert(any(McpServerRecord.class));
        verify(mapper, never()).updateByQuery(any(McpServerRecord.class),
                org.mockito.ArgumentMatchers.anyBoolean(), any(com.mybatisflex.core.query.QueryWrapper.class));
        verify(mapper, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("掩码值保持原值：更新时 env/header 不被 **** 覆盖")
    void maskedValuesKeepOriginal() {
        McpServerRecord existing = row("httpx", "http", "https://x/mcp", null,
                "{\"X-Test\":\"real-secret-1234\"}", true, true, "user");
        when(mapper.selectAll()).thenReturn(List.of(existing));

        JsonNode body = json("""
                {"mcpServers":{"httpx":{"type":"http","url":"https://x/mcp",
                  "headers":{"X-Test":"****1234"},"enabled":false,"autoStart":true,"scope":"user"}}}
                """);
        service.importJson(body);

        ArgumentCaptor<McpServerRecord> captor = ArgumentCaptor.forClass(McpServerRecord.class);
        verify(mapper).updateByQuery(captor.capture(),
                org.mockito.ArgumentMatchers.eq(false),
                any(com.mybatisflex.core.query.QueryWrapper.class));
        assertThat(captor.getValue().getEnv()).contains("real-secret-1234");
        assertThat(captor.getValue().getEnv()).doesNotContain("****");
        assertThat(captor.getValue().getEnabled()).isFalse();
    }

    @Test
    @DisplayName("diff 计数：added 1 / updated 1 / removed 1 / unchanged 1；备份文件落盘")
    void diffCountsAndBackup() throws Exception {
        McpServerRecord a = row("a", "http", "https://a/mcp", null, null, true, true, "user");
        McpServerRecord b = row("b", "stdio", "/bin/b", "[\"x\"]", "{\"K\":\"v\"}", true, true, "user");
        McpServerRecord c = row("c", "http", "https://c/mcp", null, null, true, true, "user");
        when(mapper.selectAll()).thenReturn(List.of(a, b, c));

        JsonNode body = json("""
                {"deleteMissing":true,"mcpServers":{
                  "a":{"type":"http","url":"https://a/mcp"},
                  "b":{"type":"stdio","command":"/bin/b","args":["x"],"env":{"K":"v"},"enabled":false},
                  "d":{"type":"http","url":"https://d/mcp"}
                }}
                """);
        McpJsonTransferService.ImportResult r = service.importJson(body);

        assertThat(r.added()).isEqualTo(1);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.removed()).isEqualTo(1);
        assertThat(r.unchanged()).isEqualTo(1);
        assertThat(r.addedNames()).containsExactly("d");
        assertThat(r.removedNames()).containsExactly("c");
        assertThat(Files.exists(Path.of(r.backupPath()))).isTrue();
        verify(mapper).deleteById(c.getId());
        verify(mapper).insert(any(McpServerRecord.class));
        verify(mapper).updateByQuery(any(McpServerRecord.class),
                org.mockito.ArgumentMatchers.eq(false),
                any(com.mybatisflex.core.query.QueryWrapper.class));
    }

    @Test
    @DisplayName("新增 server 出现掩码 secret / 掩码 query → 整单拒绝（无法恢复原值）")
    void maskedValueForNewServerRejected() {
        when(mapper.selectAll()).thenReturn(List.of());
        JsonNode body = json("""
                {"mcpServers":{"new":{"type":"http","url":"https://x/mcp?key=****1234"}}}
                """);
        assertThatThrownBy(() -> service.importJson(body))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("掩码");
        verify(mapper, never()).insert(any(McpServerRecord.class));
    }

    private static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static McpServerRecord row(String name, String type, String command, String args,
                                       String env, boolean enabled, boolean autoStart, String scope) {
        McpServerRecord r = new McpServerRecord();
        r.setId("mcp-" + name);
        r.setName(name);
        r.setType(type);
        r.setCommand(command);
        r.setArgs(args);
        r.setEnv(env);
        r.setEnabled(enabled);
        r.setAutoStart(autoStart);
        r.setScope(scope);
        r.setApprovalStatus("approved");
        r.setCreatedAt("2026-11-23T00:00:00+08:00");
        return r;
    }
}
