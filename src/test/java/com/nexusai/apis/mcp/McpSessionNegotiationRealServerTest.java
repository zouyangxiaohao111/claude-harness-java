package com.nexusai.apis.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.mcp.HttpMcpTransport;
import com.nexusai.application.agent.mcp.McpTransport;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionPrompter;
import com.nexusai.application.agent.permission.ToolPermissionGate;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S02 Q-1] 真实 Streamable HTTP MCP server 补证 · session 从 initialize 响应协商
 * （HttpMcpTransport 全流：initialize → Mcp-Session-Id 协商 → initialized 通知 →
 * tools/list SSE 响应解析）。
 *
 * <p><b>WHY（规则九 / Q-1）</b>：mock 测试无法证明「支持 session 的服务器」行为。本测试
 * 连本仓真实 Spring AI 入站 server（/mcp，protocol=streamable，InboundMcpServerTest
 * MinimalMcpContextConfig 先例）：
 * <ol>
 *   <li><b>正路径</b>：HttpMcpTransport 无预发头首连 → initialize 响应 Mcp-Session-Id →
 *       tools/list（SSE 流响应）返回真实工具集——证明协商 + SSE 响应解析端到端成立</li>
 *   <li><b>负控制</b>：预发随机 UUID 头 → 服务器 404 session-not-found——证明旧路径
 *       （自产 UUID 预发）在真实服务器上必然 404 循环（D-2 脏代码删除的依据，测试非 mock 假 E4）</li>
 * </ol>
 *
 * <p>SSE 流语义实证：mcp-core HttpServletStreamableServerTransportProvider 对非
 * initialize 请求恒回 text/event-stream（响应事件后流保持打开）→ HttpMcpTransport
 * ofLines 流式解析匹配帧后 close（SDK 客户端「首个事件后完成」语义）。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = McpSessionNegotiationRealServerTest.MinimalMcpContextConfig.class
)
class McpSessionNegotiationRealServerTest {

    /**
     * 最小上下文配置 · 对齐 InboundMcpServerTest.MinimalMcpContextConfig（该测试类仍在仓内，
     * 本测试原注释误称其已删除——此处以 InboundMcpServerTest.java:91-99 为真源对齐）。
     *
     * <p>装配 {@link ToolRegistry} + 探针工具 + {@link InboundMcpServerConfig}（/mcp 端点
     * ToolCallback 快照）+ Boot Web/Jackson + Spring AI MCP server 自动配置，不扫描应用包
     * （工具集可控、无文件/进程副作用），DB/Flyway/Quartz 自动配置已排除。缺
     * {@code @EnableAutoConfiguration} 时无 ServletWebServerFactory → RANDOM_PORT 起不来
     * （MissingWebServerFactoryBeanException，返工修复）。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        QuartzAutoConfiguration.class,
    })
    @Import(InboundMcpServerConfig.class)
    static class MinimalMcpContextConfig {

        @Bean
        ToolRegistry testToolRegistry(List<Tool> tools) {
            return new ToolRegistry(tools);
        }

        @Bean
        Tool mcpEchoProbe() {
            return new McpProbeTool("mcp_echo_probe", "echo desc", "echo prompt", false, false);
        }

        @Bean
        Tool mcpPermDenyProbe() {
            return new McpProbeTool("mcp_perm_deny_probe", "perm desc", null, true, false);
        }

        @Bean
        Tool mcpFailProbe() {
            return new McpProbeTool("mcp_fail_probe", "fail desc", null, false, true);
        }

        @Bean
        Tool mcpToggleProbe() {
            return new McpProbeTool("mcp_toggle_probe", "toggle desc", null, false, false);
        }

        /** 与 SPECIAL_TOOLS 同名 —— 验证 tools/list 过滤掉内部调度工具（不暴露给 MCP client）。 */
        @Bean
        Tool mcpSpecialNamedProbe() {
            return new McpProbeTool("StructuredOutput", "special desc", null, false, false);
        }

        @Bean("todoTaskTools")
        List<Tool> todoTaskTools() {
            return List.of();
        }

        @Bean
        ToolPermissionGate inboundPermissionGate() {
            return ToolPermissionGate.createSpringBean(
                new PermissionPipeline(), Mockito.mock(PermissionPrompter.class));
        }

        @Bean
        InboundPermissionContextFactory inboundPermissionContextFactory() {
            // [OPD-WF8-02-GS-01 拍板] v4 对齐 CC getEmptyToolPermissionContext：构造器已改为无参
            // （InboundPermissionContextFactory:46），旧 List.of() 重载已删除 → 修复测试树阻断。
            return new InboundPermissionContextFactory();
        }
    }

    @LocalServerPort
    private int port;

    private String mcpUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    private static McpTransport.TransportConfig httpConfig(String url) {
        return new McpTransport.TransportConfig(url, List.of(), Map.of(), null, "real-srv", "http");
    }

    @Test
    @DisplayName("正路径：initialize 协商 Mcp-Session-Id → initialized 通知 → tools/list SSE 响应返回真实工具")
    void fullFlow_initialize_negotiatesSession_toolsListWorks() throws Exception {
        HttpMcpTransport transport = new HttpMcpTransport();
        transport.start(httpConfig(mcpUrl()));

        JsonNode init = transport.sendRequest("initialize", Map.of(
            "protocolVersion", "2024-11-05",
            "capabilities", Map.of("roots", Map.of(), "elicitation", Map.of()),
            "clientInfo", Map.of("name", "nexusai-mcp-client", "version", "1.0.0")
        )).get(15, TimeUnit.SECONDS);
        assertThat(init.path("serverInfo").path("name").asText())
            .as("真实 Spring AI server 必须回 serverInfo.name（application.yml server.name=claude/tengu）")
            .isEqualTo("claude/tengu");

        // initialized 通知（202）
        transport.sendNotification("notifications/initialized", Map.of());

        // tools/list → SSE 流响应 → 流式解析匹配帧
        JsonNode tools = transport.sendRequest("tools/list", Map.of()).get(15, TimeUnit.SECONDS);
        assertThat(tools.path("tools")).as("tools/list 必须返回探针工具集（SSE 响应解析端到端）")
            .isNotEmpty();
        assertThat(tools.path("tools")).anySatisfy(t ->
            assertThat(t.path("name").asText()).isEqualTo("mcp_echo_probe"));

        transport.close();
    }

    @Test
    @DisplayName("负控制：预发随机 UUID Mcp-Session-Id → 真实服务器 404 session-not-found（旧路径 404 循环依据）")
    void negativeControl_randomUuidHeader_returns404SessionNotFound() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(mcpUrl()))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Session-Id", UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).as("未知 session id → 404（旧自产 UUID 预发路径必然 404 循环，D-2）")
            .isEqualTo(404);
        assertThat(resp.body()).as("404 必须携带 session-not-found 语义（真实服务器校验未知会话）")
            .contains("Session not found");
    }
}
