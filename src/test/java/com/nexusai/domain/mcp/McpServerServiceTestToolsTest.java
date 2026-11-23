package com.nexusai.domain.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.model.provider.dto.TestConnectionResponse;
import com.nexusai.repository.mcp.entity.McpServerRecord;
import com.nexusai.repository.mcp.mapper.McpServerMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [2026-11-23] {@code POST /api/v1/mcp/{id}/test} 必须回真实 {@code tools: string[]}
 * （对齐前端 P2 契约；⛔ 不再只回 "ok"）。
 */
class McpServerServiceTestToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("initialize 成功后 tools/list → 工具名清单进 TestConnectionResponse.tools")
    void testReturnsToolNames() throws Exception {
        McpServerService service = new McpServerService();
        McpServerMapper mapper = mock(McpServerMapper.class);
        McpTransportFactory factory = mock(McpTransportFactory.class);
        McpTransport transport = mock(McpTransport.class);
        setField(service, "mcpServerMapper", mapper);
        setField(service, "mcpTransportFactory", factory);

        McpServerRecord record = new McpServerRecord();
        record.setId("mcp-1");
        record.setName("notion");
        record.setType("http");
        record.setCommand("https://mcp.notion.com/mcp");
        when(mapper.selectOneById("mcp-1")).thenReturn(record);
        when(factory.create(any())).thenReturn(transport);
        when(transport.sendRequest(eq("initialize"), any())).thenReturn(
                CompletableFuture.completedFuture(JSON.readTree("{\"protocolVersion\":\"2024-11-05\"}")));
        when(transport.sendRequest(eq("tools/list"), any())).thenReturn(
                CompletableFuture.completedFuture(JSON.readTree("""
                        {"tools":[{"name":"search"},{"name":"fetch"},{"description":"no-name"}]}
                        """)));

        TestConnectionResponse r = service.test("mcp-1");

        assertThat(r.ok()).isTrue();
        assertThat(r.tools()).containsExactly("search", "fetch");
        verify(transport).start(any());
        verify(transport).close();
    }

    @Test
    @DisplayName("tools/list 失败 → initialize 仍算成功，tools 为空数组（不把测试连接判失败）")
    void toolsListFailureDoesNotFailTest() throws Exception {
        McpServerService service = new McpServerService();
        McpServerMapper mapper = mock(McpServerMapper.class);
        McpTransportFactory factory = mock(McpTransportFactory.class);
        McpTransport transport = mock(McpTransport.class);
        setField(service, "mcpServerMapper", mapper);
        setField(service, "mcpTransportFactory", factory);

        McpServerRecord record = new McpServerRecord();
        record.setId("mcp-2");
        record.setName("srv");
        record.setType("http");
        record.setCommand("https://mcp.example.com/mcp");
        when(mapper.selectOneById("mcp-2")).thenReturn(record);
        when(factory.create(any())).thenReturn(transport);
        when(transport.sendRequest(eq("initialize"), any())).thenReturn(
                CompletableFuture.completedFuture(JSON.readTree("{}")));
        CompletableFuture<com.fasterxml.jackson.databind.JsonNode> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("no tools"));
        when(transport.sendRequest(eq("tools/list"), any())).thenReturn(failed);

        TestConnectionResponse r = service.test("mcp-2");

        assertThat(r.ok()).isTrue();
        assertThat(r.tools()).isEmpty();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = McpServerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
