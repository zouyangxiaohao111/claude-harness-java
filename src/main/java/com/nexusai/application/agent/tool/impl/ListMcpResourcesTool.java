package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpResource;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * List MCP Resources 工具 · 对齐 CC {@code ListMcpResourcesTool.ts}（{@code src/tools/ListMcpResourcesTool/}）。
 *
 * <p>P1-17/D-5: 从 config-metadata stub 改为真实 resources/list 往返 · 对齐
 * {@code ListMcpResourcesTool.ts:66-101}：
 * <ul>
 *   <li>:69-77 targetServer 过滤（不存在则 throw :73-77）</li>
 *   <li>:84-96 逐 server 遍历，client.type!=='connected' → []（:86），
 *       ensureConnectedClient + fetchResourcesForClient（:88-89），catch → logMCPError + []（:90-94）</li>
 *   <li>:98-100 return {@code {data: results.flat()}}</li>
 * </ul>
 *
 * <p>D2 对齐（本 session）：name → CC 真名 {@code 'ListMcpResourcesTool'}
 * （prompt.ts:1 {@code LIST_MCP_RESOURCES_TOOL_NAME = 'ListMcpResourcesTool'}）；
 * toAutoClassifierInput = {@code input.server ?? ''}（:47-49）；shouldDefer=true（:50）；
 * searchHint（:52）；maxResultSizeChars=100_000（:53）；空结果 → CC 提示语
 * （mapToolResultToToolResultBlockParam :108-116，Java 端 mapToToolResultBlockParam 生产 dead，
 * 改为 execute 注入 ToolResult content，行为等价）。
 *
 * <p>outputSchema（:25-35）：uri/name/mimeType?/description?/server。
 *
 * <p><b>[决策 #65] 恒注册（@Component 自动装配）</b>：对齐 CC {@code tools.ts:245-246}
 * {@code getAllBaseTools()} 恒含 {@code ListMcpResourcesTool, ReadMcpResourceTool}（无条件，
 * 无 isEnabled 门控）。故本类恢复为 Spring {@code @Component}，经 ToolRegistry 的
 * {@code @Autowired List<Tool>} 恒注册；{@link McpToolPool} 不再条件注册/反注册本工具
 * （原 {@code client.ts:2182-2191 / 2360-2364 resourceToolsAdded} 条件注册语义由
 * getAllBaseTools 恒含取代 —— 见 McpToolPool MC-09 注释移除说明）。无 resources 能力部署下
 * 本工具仍恒在（execute fail-soft：activeServers 空 → EMPTY_RESULT_TEXT）。
 */
@Component
public class ListMcpResourcesTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ListMcpResourcesTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC ListMcpResourcesTool.ts:53 maxResultSizeChars = 100_000. */
    private static final long MAX_RESULT_SIZE_CHARS = 100_000L;

    /** CC ListMcpResourcesTool.ts:52 searchHint. */
    private static final String SEARCH_HINT = "list resources from connected MCP servers";

    /** CC ListMcpResourcesTool.ts:113-114 空结果提示语（mapToolResultToToolResultBlockParam 内，非 call data）。 */
    static final String EMPTY_RESULT_TEXT =
        "No resources found. MCP servers may still provide tools even if they have no resources.";

    private final McpToolPool mcpToolPool;

    /** 恒注册构造 · @Component 自动装配（决策 #65，CC getAllBaseTools 恒含）。 */
    @Autowired
    public ListMcpResourcesTool(McpToolPool mcpToolPool) {
        this.mcpToolPool = mcpToolPool;
    }

    @Override
    public String name() {
        // CC prompt.ts:1 LIST_MCP_RESOURCES_TOOL_NAME = 'ListMcpResourcesTool'
        return "ListMcpResourcesTool";
    }

    @Override
    public String description() {
        // CC ListMcpResourcesTool/prompt.ts:2-9 DESCRIPTION 原文（含 usage examples，逐字）
        return "Lists available resources from configured MCP servers.\n"
             + "Each resource object includes a 'server' field indicating which server it's from.\n"
             + "\nUsage examples:\n"
             + "- List all resources from all servers: `listMcpResources`\n"
             + "- List resources from a specific server: `listMcpResources({ server: \"myserver\" })`";
    }

    /**
     * 工具提示词 · 对齐 CC {@code ListMcpResourcesTool/prompt.ts:11-18 PROMPT}（P3-19 补齐，与
     * {@link ReadMcpResourceTool#prompt()} 对称）。CC {@code ListMcpResourcesTool.ts:57-59}
     * {@code async prompt() { return PROMPT }} 注入 system prompt 指导 LLM 使用。
     */
    @Override
    public String prompt() {
        return "List available resources from configured MCP servers.\n"
             + "Each returned resource will include all standard MCP resource fields plus a 'server' field \n"
             + "indicating which server the resource belongs to.\n"
             + "\nParameters:\n"
             + "- server (optional): The name of a specific MCP server to get resources from. If not provided,\n"
             + "  resources from all servers will be returned.";
    }

    @Override
    public JsonNode inputSchema() {
        // CC ListMcpResourcesTool.ts:15-22 inputSchema: {server?: string（可选过滤）}
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("server").put("type", "string")
            .put("description", "Optional server name to filter resources by");
        return schema;
    }

    @Override
    public JsonNode outputSchema() {
        // CC ListMcpResourcesTool.ts:25-35 outputSchema: 数组 {uri, name, mimeType?, description?, server}
        var schema = JSON.createObjectNode();
        schema.put("type", "array");
        var item = schema.putObject("items");
        item.put("type", "object");
        var props = item.putObject("properties");
        props.putObject("uri").put("type", "string").put("description", "Resource URI");
        props.putObject("name").put("type", "string").put("description", "Resource name");
        props.putObject("mimeType").put("type", "string").put("description", "MIME type of the resource");
        props.putObject("description").put("type", "string").put("description", "Resource description");
        props.putObject("server").put("type", "string").put("description", "Server that provides this resource");
        return schema;
    }

    @Override
    public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public boolean shouldDefer(JsonNode input) { return true; }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /**
     * 搜索提示 · 对齐 CC ListMcpResourcesTool.ts:52 searchHint。
     * Tool 接口契约成员 override（G5 提升，CC Tool.ts:378；消费方 ToolSearch 待 OPD-23）。
     */
    @Override
    public String searchHint() {
        return SEARCH_HINT;
    }

    @Override
    public String toAutoClassifierInput(JsonNode input) {
        // CC ListMcpResourcesTool.ts:47-49 toAutoClassifierInput(input) = input.server ?? ''
        String server = input != null && input.has("server") ? input.get("server").asText("") : "";
        if (log.isDebugEnabled()) {
            log.debug("[ListMcpResourcesTool] toAutoClassifierInput: server={} → 编码='{}'", server, server);
        }
        return server;
    }

    @Override
    public ToolResult<String> execute(ToolUseBlock call) {
        JsonNode input = call.input();
        String targetServer = input.has("server") && !input.get("server").asText().isEmpty()
            ? input.get("server").asText() : null;

        try {
            Set<String> active = mcpToolPool.activeServers();

            // CC :69-77 targetServer 过滤 — 指定不存在则 throw（对齐 :73-77）
            if (targetServer != null && !active.contains(targetServer)) {
                String available = String.join(", ", active);
                if (log.isDebugEnabled()) {
                    log.debug("[ListMcpResourcesTool] server 未找到: {} 可用={}", targetServer, available);
                }
                return ToolResult.error(call.id(),
                    "Server \"" + targetServer + "\" not found. Available servers: " + available);
            }

            List<String> serversToProcess = targetServer != null
                ? List.of(targetServer)
                : new ArrayList<>(active);

            // [G28② TR-E2-DEC-5] activeServers() 现返回确定性有序（TreeSet 按名排序，替代原
            //   ConcurrentHashMap keySet 非确定序；CC 为配置序，Java 运行时无配置序 → 按名排序
            //   确定性快照，同 getCurrentTools 模式）。[G27③] active 含 failed/needs-auth 降级态
            //   （对齐 CC mcpClients），fetchResources 对未连接 server fail-soft → []（CC :86）。
            // CC :84-96 逐 server 遍历（P2-15: fetchResources 已 memoize per-server LRU 缓存
            // client.ts:2029-2030 → 消费方拿到缓存快照，直到 list_changed/失效/断开才刷新）
            List<McpResource> results = new ArrayList<>();
            for (String serverName : serversToProcess) {
                // CC :86 client.type !== 'connected' → []（fetchResources 内部对未连接 fail-soft）
                List<McpResource> fetched = mcpToolPool.fetchResources(serverName);
                results.addAll(fetched);
            }

            // CC :108-116 空结果 → 提示语（mapToolResultToToolResultBlockParam 内；Java 端
            // mapToToolResultBlockParam 生产 dead，LlmAgentLoop 直序列化 → execute 注入 content，行为等价）
            if (results.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("[ListMcpResourcesTool] 空结果，注入 CC 提示语: filter={}", targetServer);
                }
                return ToolResult.success(call.id(), EMPTY_RESULT_TEXT);
            }

            // CC :98-100 return { data: results.flat() }；:108-120 mapToolResultToToolResultBlockParam
            //   content = jsonStringify(content) → 模型看到裸数组 [{uri:...}, ...]。
            // [IMP-E1 组 2-7 △-3] Java 旧实现返回 {"data":[...]} 与声明的 array outputSchema 矛盾
            //   （被 ListMcpResourcesToolAlignmentTest.execute_nonEmpty_returnsDataArray 固化）；
            //   对齐 CC 移除 data 包装，直接返回裸数组 JSON。
            ArrayNode arr = JSON.createArrayNode();
            for (McpResource r : results) {
                ObjectNode entry = arr.addObject();
                entry.put("uri", r.uri());
                entry.put("name", r.name());
                if (r.mimeType() != null) entry.put("mimeType", r.mimeType());
                if (r.description() != null) entry.put("description", r.description());
                entry.put("server", r.server());
            }
            if (log.isDebugEnabled()) {
                log.debug("[ListMcpResourcesTool] filter={} resources={}", targetServer, results.size());
            }
            return ToolResult.success(call.id(), arr.toString());
        } catch (Exception e) {
            log.warn("[ListMcpResourcesTool] execute 失败 filter={}: {}", targetServer, e.getMessage());
            return ToolResult.error(call.id(), "ListMcpResourcesTool: " + e.getMessage());
        }
    }
}
