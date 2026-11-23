package com.nexusai.apis.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.domain.mcp.McpJsonTransferService;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.infra.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MCP JSON 导出/导入 REST 契约（standalone，不连库）。 */
class McpServerControllerTransferTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private McpServerService serverService;
    private McpJsonTransferService transferService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        serverService = mock(McpServerService.class);
        transferService = mock(McpJsonTransferService.class);
        mvc = MockMvcBuilders.standaloneSetup(new McpServerController(serverService, transferService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/mcp/export → mcpServers + X-Suggested-Save-Path 头")
    void export() throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.putObject("mcpServers").putObject("notion").put("type", "http");
        when(transferService.exportMasked()).thenReturn(body);
        when(transferService.suggestedSavePath()).thenReturn("C:/data/exports/mcp.json");

        mvc.perform(get("/api/v1/mcp/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Suggested-Save-Path", "C:/data/exports/mcp.json"))
                .andExpect(jsonPath("$.mcpServers.notion.type").value("http"));
    }

    @Test
    @DisplayName("POST /api/v1/mcp/import（mcpServers）→ 走 JSON 导入并返回 diff")
    void importJson() throws Exception {
        when(transferService.importJson(any())).thenReturn(new McpJsonTransferService.ImportResult(
                1, 2, 0, 3, List.of("a"), List.of("b", "c"), List.of(), List.of("d", "e", "f"),
                "C:/data/backups/x.json", List.of()));

        mvc.perform(post("/api/v1/mcp/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mcpServers":{"a":{"type":"http","url":"https://a"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.unchanged").value(3))
                .andExpect(jsonPath("$.backupPath").value("C:/data/backups/x.json"));
        verify(transferService).importJson(any());
    }

    @Test
    @DisplayName("POST /api/v1/mcp/import（files）→ 兼容既有 .mcp.json 文件导入")
    void importFilesLegacy() throws Exception {
        when(serverService.importFromMcpJson(eq(Map.of("user", "C:/p/.mcp.json"))))
                .thenReturn(new McpServerService.McpServerImportResult(1, List.of(), List.of()));

        mvc.perform(post("/api/v1/mcp/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"files":{"user":"C:/p/.mcp.json"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));
        verify(serverService).importFromMcpJson(eq(Map.of("user", "C:/p/.mcp.json")));
    }

    @Test
    @DisplayName("两种形态都没有 → 400")
    void importUnknownShape() throws Exception {
        mvc.perform(post("/api/v1/mcp/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"foo\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
