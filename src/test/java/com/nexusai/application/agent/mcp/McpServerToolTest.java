package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * McpServerTool CC 语义聚焦测试 · 断言与 CC 真源行为一致（F1-F7/C5/S1）。
 *
 * <p>WHY：全局 MCP 工具包装（生产主路径）此前仅返回拼接描述 + 字符串化结果，
 * 与子 agent 版 AgentMcpTool 的 annotations/_meta 映射分裂；本测试锁定
 * annotations/_meta/description/prompt/classify/toAutoClassifierInput/
 * maxResultSizeChars/userFacingName/searchHint/isResultTruncated 逐项对齐。
 */
class McpServerToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolPool pool() {
        return new McpToolPool(new McpTransportFactory(), new ToolRegistry(), new JsonRpcMcpClient());
    }

    private McpServerTool tool(JsonNode annotations, JsonNode meta, String description) {
        return new McpServerTool("filesystem", "read_file", "mcp__filesystem__read_file", MAPPER.createObjectNode(), annotations, meta, description, null, pool());
    }

    private static JsonNode obj(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    // ───────────── F1: annotations 映射（client.ts:1795-1809/:1972-1976）─────────────

    @Test
    void annotations_readOnlyDestructiveOpenWorld_hintMappedToToolSemantics() throws Exception {
        // WHY: CC readOnlyHint→isConcurrencySafe+isReadOnly，destructiveHint→isDestructive，
        // openWorldHint→isOpenWorld；缺失时 ?? false（不默认放行破坏性）
        JsonNode annotations = obj("""
            {"readOnlyHint": true, "destructiveHint": true, "openWorldHint": true, "title": "读文件"}
            """);
        McpServerTool t = tool(annotations, null, null);
        assertTrue(t.isConcurrencySafe(null), "readOnlyHint=true → isConcurrencySafe");
        assertTrue(t.isReadOnly(null), "readOnlyHint=true → isReadOnly");
        assertTrue(t.isDestructive(null), "destructiveHint=true → isDestructive");
        assertTrue(t.isOpenWorld(null), "openWorldHint=true → isOpenWorld");
        // title → userFacingName（client.ts:1972-1976 `${client.name} - ${title} (MCP)`）
        assertEquals("filesystem - 读文件 (MCP)", t.userFacingName());
    }

    @Test
    void annotations_missing_hintsDefaultFalse() {
        // WHY: annotations 缺失时 ?? false —— 不能默认破坏性/开放世界
        McpServerTool t = tool(null, null, "desc");
        assertFalse(t.isConcurrencySafe(null));
        assertFalse(t.isReadOnly(null));
        assertFalse(t.isDestructive(null));
        assertFalse(t.isOpenWorld(null));
    }

    @Test
    void userFacingName_fallbackToToolName_whenTitleMissing() throws Exception {
        // WHY: CC `annotations?.title || tool.name` —— 无 title 回退 tool 名
        McpServerTool t = tool(obj("{}"), null, null);
        assertEquals("filesystem - read_file (MCP)", t.userFacingName());
    }

    // ───────────── F2: _meta 映射（client.ts:1776-1785）─────────────

    @Test
    void meta_searchHint_collapseWhitespaceAndTrim() throws Exception {
        // WHY: _meta 对外部 MCP server 开放，换行/连续空白会在 deferred-tool 列表注入孤儿行
        // （formatDeferredToolLine 以 '\n' 拼接）→ 空白折叠 + trim
        JsonNode meta = obj("{\"anthropic/searchHint\": \"  find\\n  files  \\n  here  \"}");
        McpServerTool t = tool(null, meta, null);
        assertEquals("find files here", t.searchHint());
    }

    @Test
    void meta_searchHint_nonStringOrMissing_returnsNull() throws Exception {
        // WHY: CC typeof === 'string' 才取；数字/缺失 → undefined
        McpServerTool t1 = tool(null, obj("{\"anthropic/searchHint\": 123}"), null);
        assertNull(t1.searchHint(), "非 string → null");
        McpServerTool t2 = tool(null, null, null);
        assertNull(t2.searchHint(), "_meta 缺失 → null");
        McpServerTool t3 = tool(null, obj("{\"anthropic/searchHint\": \"   \"}"), null);
        assertNull(t3.searchHint(), "空白折叠后空串 → null（对齐 || undefined）");
    }

    @Test
    void meta_alwaysLoad_exactTrueBoolean() throws Exception {
        // WHY: CC `_meta?.['anthropic/alwaysLoad'] === true` —— 严格 ===，非 true 不加载
        assertTrue(tool(null, obj("{\"anthropic/alwaysLoad\": true}"), null).alwaysLoad());
        assertFalse(tool(null, obj("{\"anthropic/alwaysLoad\": false}"), null).alwaysLoad());
        assertFalse(tool(null, obj("{\"anthropic/alwaysLoad\": \"true\"}"), null).alwaysLoad(), "string true 不是 === true");
        assertFalse(tool(null, null, null).alwaysLoad());
    }

    // ───────────── F3/F4: description / prompt（client.ts:1786-1794/:218）─────────────

    @Test
    void description_returnsToolDescription_notHardcodedConcat() {
        // WHY: F3 —— 描述必须来自 tool.description ?? ''，不能是 Java 拼接的
        // "MCP tool X from server Y"（消除与 AgentMcpTool 的分裂）
        McpServerTool t = tool(null, null, "真实 MCP 描述");
        assertEquals("真实 MCP 描述", t.description());
        assertEquals("真实 MCP 描述", t.prompt(), "未超长时 prompt == description");
    }

    @Test
    void description_missing_returnsEmptyString() {
        // WHY: CC `tool.description ?? ''` —— 缺失回退空串（非 null，避免下游 NPE）
        assertEquals("", tool(null, null, null).description());
    }

    @Test
    void prompt_over2048_truncatedWithSuffix() {
        // WHY: F4 —— 超过 MAX_MCP_DESCRIPTION_LENGTH=2048 截断 + '… [truncated]'（client.ts:218/1791-1793）
        String longDesc = "x".repeat(3000);
        McpServerTool t = tool(null, null, longDesc);
        assertEquals(2048 + "… [truncated]".length(), t.prompt().length());
        assertEquals(longDesc.substring(0, 2048) + "… [truncated]", t.prompt());
    }

    @Test
    void prompt_at2048_notTruncated() {
        // WHY: CC `desc.length > MAX` 严格大于才截断；恰好 2048 不截断
        String desc = "x".repeat(2048);
        assertEquals(desc, tool(null, null, desc).prompt());
    }

    // ───────────── F5: classify（client.ts:1810-1812 + classifyForCollapse.ts）─────────────

    @Test
    void searchReadKind_searchAndReadAllowlist() {
        // WHY: classifyMcpToolForCollapse —— normalize 后查 SEARCH/READ allowlist
        McpServerTool searchTool = new McpServerTool("server", "search_files", "mcp__server__search_files", MAPPER.createObjectNode(), null, null, null, null, pool());
        assertEquals(Tool.SearchReadKind.IS_SEARCH, searchTool.searchReadKind(null));
        McpServerTool readTool = new McpServerTool("server", "read_file", "mcp__server__read_file", MAPPER.createObjectNode(), null, null, null, null, pool());
        assertEquals(Tool.SearchReadKind.IS_READ, readTool.searchReadKind(null));
        McpServerTool other = new McpServerTool("server", "write_file", "mcp__server__write_file", MAPPER.createObjectNode(), null, null, null, null, pool());
        assertEquals(Tool.SearchReadKind.NONE, other.searchReadKind(null));
    }

    @Test
    void searchReadKind_normalizeCamelKebabToSnake() {
        // WHY: CC normalize（classifyForCollapse.ts:586-592）把 camelCase/kebab 归一到 snake_case
        // 再查表 —— searchJiraIssuesUsingJql / gcal-find-my-free-time 都应命中
        McpServerTool camel = new McpServerTool("atlassian", "searchJiraIssuesUsingJql", "mcp__atlassian__searchJiraIssuesUsingJql", MAPPER.createObjectNode(), null, null, null, null, pool());
        assertEquals(Tool.SearchReadKind.IS_SEARCH, camel.searchReadKind(null));
        McpServerTool kebab = new McpServerTool("gcal", "gcal-find-my-free-time", "mcp__gcal__gcal-find-my-free-time", MAPPER.createObjectNode(), null, null, null, null, pool());
        assertEquals(Tool.SearchReadKind.IS_SEARCH, kebab.searchReadKind(null));
    }

    // ───────────── F6: toAutoClassifierInput（client.ts:1733-1741/:1801-1803）─────────────

    @Test
    void toAutoClassifierInput_kvJoinSpace() throws Exception {
        // WHY: F6 —— mcpToolInputToAutoClassifierInput：keys.map(k=>k=val).join(' ')
        McpServerTool t = tool(null, null, null);
        assertEquals("path=/tmp/x maxResults=5",
            t.toAutoClassifierInput(obj("{\"path\": \"/tmp/x\", \"maxResults\": 5}")));
    }

    @Test
    void toAutoClassifierInput_emptyInput_fallsBackToToolName() {
        // WHY: CC keys.length>0 才拼接；空输入回退 tool.name（不是空串）
        McpServerTool t = tool(null, null, null);
        assertEquals("read_file", t.toAutoClassifierInput(MAPPER.createObjectNode()));
    }

    // ───────────── F7 / C5 / S1 ─────────────

    @Test
    void maxResultSizeChars_100000() {
        // WHY: F7 —— MCPTool.ts:35 maxResultSizeChars = 100_000（非 Tool 默认 50_000）
        assertEquals(100_000L, tool(null, null, null).maxResultSizeChars());
    }

    @Test
    void isResultTruncated_newlineOver3() {
        // WHY: C5 —— isOutputLineTruncated（terminal.ts:119-125）：需多于 3 个换行 + 第 4 个换行后仍有内容
        McpServerTool t = tool(null, null, null);
        assertFalse(t.isResultTruncated("a\nb\nc\nd"), "3 个换行不截断");
        assertFalse(t.isResultTruncated("a\nb\nc\nd\n"), "第 4 个换行是终止符（trimEnd 语义，无后续内容）→ 不截断");
        assertTrue(t.isResultTruncated("a\nb\nc\nd\nX"), "第 4 个换行后有内容 → 截断");
        assertFalse(t.isResultTruncated(""), "空串不截断");
    }

    @Test
    void mapToToolResultBlockParam_structuredBlocks() {
        // WHY: S1 —— MCPTool.ts:70-76 {tool_use_id, type:'tool_result', content}；
        // content 保留块结构（data 为 JsonNode 块数组，G2 后经 @JsonSubTypes 转 List<ContentBlockParam>）
        com.fasterxml.jackson.databind.node.ArrayNode content = MAPPER.createArrayNode();
        content.addObject().put("type", "text").put("text", "hello");
        ToolResult<JsonNode> result = new ToolResult<>(content, null, null, null);
        ToolResultBlockParam block = tool(null, null, null).mapToToolResultBlockParam(result, "use-1", false);
        assertEquals("use-1", block.toolUseId());
        assertEquals("tool_result", block.type());
        assertTrue(block.content() instanceof java.util.List<?>, "content 应为 List<ContentBlockParam>（块结构保留，非字符串化）");
    }

    @Test
    void mapToToolResultBlockParam_isError_returnsNull() {
        // WHY: CC mapper 仅在成功路径被调（toolExecution.ts:1292-1295 位于 endToolExecutionSpan(success) 之后），
        // isError 时返回 null（调用方回退默认渲染器），不渲染失败数据为成功块
        ToolResult<String> err = ToolResult.error("use-1", "MCP call failed");
        assertTrue(LlmAgentLoop.isToolErrorData(err.data()), "前置: 该结果必须是 isError（IMP-C2 后 isError 由执行路径推导）");
        assertNull(tool(null, null, null).mapToToolResultBlockParam(err, "use-1", true));
    }

    @Test
    void execute_preservesContentBlockArrayStructure() throws Exception {
        // WHY: S1 —— CC MCPToolResult = string | ContentBlockParam[]（mcpValidation.ts:49）：
        // 非截断 contentArray 的 execute 返回 data = 块数组（JsonNode），不得 .toString() 整体
        // 字符串化丢失块结构（image/text 块在 LLM tool_result 消费）。image 块经 transformResultContent
        // 转换（client.ts:2495-2511）嵌套于 source.data（非原始顶层 data），块结构保留即达标。
        InProcessMcpTransport[] pair = InProcessMcpTransport.createLinkedPair();
        pair[1].start(new McpTransport.TransportConfig("inproc", java.util.List.of(), java.util.Map.of(), null, null));
        // [F1 rework] 真实 1x1 PNG（ImageIO 写入 → ImageResizer 解码/resize 走成功路径）。
        // 旧假数据 "base64abc"（非法 base64，长度 9 非 4 倍数）→ Base64.getDecoder() 抛
        // IllegalArgumentException → McpServerTool catch → isError=true → 断言失败。
        java.io.ByteArrayOutputStream pngOut = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
            new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB),
            "png", pngOut);
        String pngBase64 = java.util.Base64.getEncoder().encodeToString(pngOut.toByteArray());
        pair[1].setRequestHandler((method, params) -> {
            if ("initialize".equals(method)) {
                return Map.of("protocolVersion", "2024-11-05",
                    "serverInfo", Map.of("name", "fs", "version", "1.0.0"),
                    "capabilities", Map.of());
            }
            if ("tools/list".equals(method)) {
                return Map.of("tools", java.util.List.of(
                    Map.of("name", "read_file", "description", "Read", "inputSchema", Map.of())));
            }
            if ("tools/call".equals(method)) {
                return Map.of(
                    "content", java.util.List.of(
                        Map.of("type", "text", "text", "Hello from MCP"),
                        Map.of("type", "image", "data", pngBase64, "mimeType", "image/png")),
                    "isError", false);
            }
            return Map.of();
        });
        McpToolPool pool = new McpToolPool(new FakeTransportFactory(pair[0]), new ToolRegistry(),
            new JsonRpcMcpClient());
        // 先装配（activeTransports 记录 fs → callTool 才能委派；对齐 A2 Golden Trace）
        pool.assembleToolPool("fs", new McpTransport.TransportConfig("inproc",
            java.util.List.of(), java.util.Map.of(), null, null));
        McpServerTool t = new McpServerTool("fs", "read_file", "mcp__fs__read_file", MAPPER.createObjectNode(), null, null, null, null, pool);

        AgentToolResult<?> result = t.execute(new ToolUseBlock("use-9", "mcp__fs__read_file",
            MAPPER.createObjectNode()));
        assertFalse(LlmAgentLoop.isToolErrorData(result.data()), "MCP 调用成功");
        assertTrue(result.data() instanceof JsonNode, "data 应为 JsonNode（非字符串）");
        JsonNode data = (JsonNode) result.data();
        assertTrue(data.isArray(), "data 应为块数组");
        assertEquals("Hello from MCP", data.get(0).path("text").asText(),
            "首个 text 块内容保留");
        // 1x1 PNG 未超尺寸/字节上限 → maybeResizeAndDownsampleImageBuffer 原样直发（buffer 不变）
        // → source.data 等于输入 base64（resized.base64() = 重编码同字节），锁定 resize 成功路径
        assertEquals(pngBase64, data.get(1).path("source").path("data").asText(),
            "image 块结构保留（CC transformResultContent :2495-2511 嵌套于 source.data，非整体字符串化）");
    }

    @Test
    void name_returnsMcpToolName() {
        // WHY: CC name = fullyQualifiedName（mcp__{server}__{tool}）
        assertEquals("mcp__filesystem__read_file", tool(null, null, null).name());
    }

    @Test
    void inputJSONSchema_returnsMcpInputSchema() throws Exception {
        // WHY (G3): CC MCPTool 以 inputJSONSchema 直接声明 JSON Schema（api.ts:157-160
        //           inputJSONSchema 优先）— McpServerTool 应把 tools/list 返回的 input schema
        //           原样声明为 inputJSONSchema()，避免 inputSchema() 二次转换。
        JsonNode schema = MAPPER.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}");
        McpServerTool t = new McpServerTool("filesystem", "read_file", "mcp__filesystem__read_file", schema, null, null, "desc", null, pool());
        assertEquals(schema, t.inputJSONSchema());
    }

    @Test
    void inputSchema_passthrough_alwaysAllows() throws Exception {
        // WHY (IMP-E1 S-1): CC MCPTool.ts:14 inputSchema = z.object({}).passthrough() — 校验面
        //   恒放行，LLM 缺参/错型 MCP 参数原样发 server（不注入 InputValidationError）。
        //   真实 tools/list schema 由 inputJSONSchema() 承载（序列化层 inputJSONSchema 优先，
        //   ToolRegistry.toOpenAiToolsArray:468 仍见真实 schema）。
        JsonNode realSchema = MAPPER.readTree("{\"type\":\"object\",\"required\":[\"x\"],\"properties\":{\"x\":{\"type\":\"string\"}}}");
        McpServerTool t = new McpServerTool("filesystem", "read_file", "mcp__filesystem__read_file",
            realSchema, null, null, "desc", null, pool());
        // inputSchema() 为空 object（passthrough，z.object({}).passthrough() toJSONSchema={}）
        JsonNode schema = t.inputSchema();
        assertTrue(schema.isObject(), "inputSchema 应为 passthrough 空 object（非真实 schema）");
        assertTrue(schema.isEmpty(), "passthrough schema 应为空（{}）→ safeParseSchema 直接 pass");
        assertTrue(!schema.has("required"), "passthrough 不声明 required（缺参不拒）");
        // inputJSONSchema() 保留真实 schema（序列化/校验展示层）
        assertEquals(realSchema, t.inputJSONSchema(), "inputJSONSchema 仍返回 tools/list 真实 schema");
        // 关键语义：inputSchema()（passthrough）与 inputJSONSchema()（真实 schema）分离——
        // ToolInputValidator.safeParseSchema 消费 inputSchema() → 恒放行；序列化层消费
        // inputJSONSchema() → LLM 仍见真实参数契约。
        assertTrue(!schema.equals(realSchema), "passthrough schema 必须区别于真实 schema");
    }

    // ───────────── checkPermissions（OPD-TOOL-07-3 · client.ts:1814-1829）─────────────

    @Test
    void checkPermissions_passthroughWithWholeToolAllowSuggestion() {
        // WHY: MCP 工具必须自表态 passthrough（对齐 CC client.ts:1814-1829 生产路径 per-tool 覆盖，
        //      非 MCPTool.ts base 模板——后者无 suggestions）。默认 Allow（1c 快速放行）会让 MCP
        //       工具绕过第 3 层兜底 Ask，直接放行任意 MCP 调用——更贴 CC、更安全的语义是 passthrough。
        // 不写 = 权限越权：MCP 工具恒默认放行，用户无法在默认模式下对 MCP 调用授权。
        McpServerTool t = tool(null, null, "desc");
        PermissionResult r = t.checkPermissions(null, null);

        assertTrue(r instanceof PermissionResult.Passthrough, "MCP 工具应返回 Passthrough（不表态，交第 3 层）");
        PermissionResult.Passthrough p = (PermissionResult.Passthrough) r;
        assertEquals("MCPTool requires permission.", p.message(), "message 精确对齐 CC client.ts:1816");
        assertNull(p.reason(), "CC passthrough 变体无 reason 字段 → reason=null");
        assertEquals(1, p.suggestions().size(), "CC 只给 1 个 addRules 建议");
        assertTrue(p.suggestions().get(0) instanceof PermissionUpdate.AddRules, "suggestion 类型应为 AddRules");
        PermissionUpdate.AddRules add = (PermissionUpdate.AddRules) p.suggestions().get(0);
        assertEquals(PermissionUpdate.Destination.LOCAL_SETTINGS, add.destination(),
            "CC destination:'localSettings' → LOCAL_SETTINGS");
        assertEquals(PermissionBehavior.ALLOW, add.behavior(), "CC behavior:'allow' → ALLOW");
        assertEquals(1, add.rules().size(), "CC 只给 1 条规则（whole tool）");
        assertEquals(PermissionRuleValue.wholeTool("mcp__filesystem__read_file"),
            add.rules().get(0).ruleValue(),
            "CC rules[0].toolName=fullyQualifiedName → wholeTool(mcpToolName)");
    }

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
}
