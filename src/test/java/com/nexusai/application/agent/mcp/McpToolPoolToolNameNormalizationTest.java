package com.nexusai.application.agent.mcp;

import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEL-MCP-4: McpToolPool 装配工具名规范化聚焦测试 · 对齐 CC client.ts:1768
 * {@code fullyQualifiedName = buildMcpToolName(client.name, tool.name)}。
 *
 * <p>WHY：装配/抓取路径此前裸拼接 {@code "mcp__"+server+"__"+tool}，未 normalize
 * server/tool —— MCP server 名或工具名含非法字符（点/空格/claude.ai 前缀）时会破坏
 * {@code mcp__server__tool} 分隔符契约（权限匹配、mcpInfoFromString 反向解析、工具名
 * 唯一性）。本测试锁定装配名 = {@code mcp__<norm_s>__<norm_t>}（server/tool 各经
 * normalizeNameForMCP）。
 */
@DisplayName("DEL-MCP-4 McpToolPool 装配工具名规范化（buildMcpToolName）")
class McpToolPoolToolNameNormalizationTest {

    private static final McpTransport.TransportConfig CONFIG =
        new McpTransport.TransportConfig("inproc", List.of(), Map.of(), null, null);

    /** fake transport 工厂: 每次 create 返回指定 transport（InProcess 客户端侧）. */
    static class FakeTransportFactory extends McpTransportFactory {
        private final McpTransport transport;

        FakeTransportFactory(McpTransport transport) {
            this.transport = transport;
        }

        @Override
        public McpTransport create(McpTransport.TransportConfig config) {
            return transport;
        }
    }

    /**
     * fake MCP server：返回 initialize + tools/list。
     *
     * @param toolNames tools/list 返回的原始工具名
     */
    private static InProcessMcpTransport[] serverWithTools(List<String> toolNames) {
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        pair[1].start(CONFIG);
        pair[1].setRequestHandler((method, params) -> {
            if ("initialize".equals(method)) {
                return Map.of(
                    "protocolVersion", "2024-11-05",
                    "serverInfo", Map.of("name", "fake-server", "version", "1.0.0"),
                    "capabilities", Map.of());
            }
            if ("tools/list".equals(method)) {
                return Map.of("tools", toolNames.stream()
                    .map(n -> (Map<String, Object>) Map.of(
                        "name", n, "description", "Tool " + n, "inputSchema", Map.of()))
                    .toList());
            }
            return Map.of();
        });
        return pair;
    }

    @Test
    void assembleToolPool_normalizesServerAndToolNames() {
        // WHY: 非法字符（点/空格/感叹号）必须替换为 _（normalization.ts:17-23），
        // 否则 mcp__server__tool 分隔符契约被破坏（权限规则与 mcpInfoFromString 依赖）
        InProcessMcpTransport[] pair = serverWithTools(List.of("read file.txt", "run!cmd"));
        McpToolPool pool = new McpToolPool(new FakeTransportFactory(pair[0]),
            new ToolRegistry(), new JsonRpcMcpClient());

        var entries = pool.assembleToolPool("my server.local", CONFIG);

        assertEquals("mcp__my_server_local__read_file_txt", entries.get(0).mcpToolName());
        assertEquals("mcp__my_server_local__run_cmd", entries.get(1).mcpToolName());
    }

    @Test
    void assembleToolPool_claudeAiPrefixCollapsesUnderscores() {
        // WHY: claude.ai 前缀的 server 名额外合并连续 _ + 去首尾 _（normalization.ts:20-23），
        // 避免与 __ 分隔符冲突（如 "claude.ai Code" → "claude_ai_Code"）
        InProcessMcpTransport[] pair = serverWithTools(List.of("search"));
        McpToolPool pool = new McpToolPool(new FakeTransportFactory(pair[0]),
            new ToolRegistry(), new JsonRpcMcpClient());

        var entries = pool.assembleToolPool("claude.ai Code", CONFIG);

        assertEquals("mcp__claude_ai_Code__search", entries.get(0).mcpToolName());
    }

    @Test
    void assembleToolPool_registersNormalizedNameInRegistry() {
        // WHY: 注册名必须与装配名一致 —— 工具权限匹配 / LLM 调用都按注册名寻址
        InProcessMcpTransport[] pair = serverWithTools(List.of("fetch.user"));
        ToolRegistry registry = new ToolRegistry();
        McpToolPool pool = new McpToolPool(new FakeTransportFactory(pair[0]),
            registry, new JsonRpcMcpClient());

        pool.assembleToolPool("github", CONFIG);

        assertEquals("mcp__github__fetch_user",
            registry.get("mcp__github__fetch_user").map(Tool::name).orElse(null));
        assertTrue(registry.get("mcp__github__fetch_user").isPresent(),
            "规范化注册名应在 registry 中");
    }

    @Test
    void doFetchTools_normalizesToolNames() {
        // WHY: fetchTools（list_changed 刷新 / 直接抓取）路径与装配同源 ——
        // CC fetchToolsForClient 两条入口都走 buildMcpToolName（client.ts:1768）
        InProcessMcpTransport[] pair = serverWithTools(List.of("a.b", "c!d"));
        McpToolPool pool = new McpToolPool(new FakeTransportFactory(pair[0]),
            new ToolRegistry(), new JsonRpcMcpClient());
        pool.assembleToolPool("svc", CONFIG);

        var tools = pool.fetchTools("svc");

        assertEquals("mcp__svc__a_b", tools.get(0).mcpToolName());
        assertEquals("mcp__svc__c_d", tools.get(1).mcpToolName());
    }
}
