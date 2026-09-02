package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.McpOutputStorage;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultStorage;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Read MCP Resource 工具 · 对齐 CC {@code ReadMcpResourceTool.ts}（{@code src/tools/ReadMcpResourceTool/}）。
 *
 * <p>真实现（替换原 PENDING placeholder 假成功）。CC 真源（grep 自验，不信注释）：
 * <ul>
 *   <li>name: 'ReadMcpResourceTool'（ReadMcpResourceTool.ts:60）</li>
 *   <li>isConcurrencySafe/isReadOnly=true（:50-55）；toAutoClassifierInput = `${input.server} ${input.uri}`（:56-58）</li>
 *   <li>shouldDefer=true（:59）；searchHint（:61）；maxResultSizeChars=100_000（:62）</li>
 *   <li>inputSchema: {server(必填), uri(必填)}（:22-27）；outputSchema: {contents: [{uri, mimeType?, text?, blobSavedTo?}]}（:30-44）</li>
 *   <li>3 throw 前置（:78-92）：server not found / not connected / no resources capability</li>
 *   <li>resources/read（:95-101）→ blob 拦截：base64 解码 → 落盘 → blobSavedTo 替换（:106-138）；persist 失败 → text 错误（:120-126）</li>
 *   <li>返回 {@code {data: {contents}}}（:141-143）</li>
 * </ul>
 *
 * <p>Java 偏离（登记 WF-D-O4）：CC 错误经 {@code throw} 抛给工具执行器；Java 端按 Tool 契约
 * 返回 {@link ToolResult#error}（错误文本一致，行为等价）。blob 落盘目录基于
 * {@link ToolUseContext#effectiveCwd()} + sessionId（对齐 ReadFileTool.pdfOutputDir 模式）；
 * [G30⑮] ctx 为 null（纯测试直调）不落盘返回失败提示（tmpdir 回退已删除，对齐 CC
 * persistBinaryContent 恒用 getToolResultsDir）。
 *
 * <p><b>[决策 #65] 恒注册（@Component 自动装配）</b>：对齐 CC {@code tools.ts:245-246}
 * {@code getAllBaseTools()} 恒含 {@code ListMcpResourcesTool, ReadMcpResourceTool}（无条件，
 * 无 isEnabled 门控）。故本类恢复为 Spring {@code @Component}，经 ToolRegistry 的
 * {@code @Autowired List<Tool>} 恒注册；{@link McpToolPool} 不再条件注册/反注册本工具
 * （原 {@code client.ts:2182-2191 / 2360-2364 resourceToolsAdded} 条件注册语义由
 * getAllBaseTools 恒含取代 —— 见 McpToolPool MC-09 注释移除说明）。无 resources 能力部署下
 * 本工具仍恒在（execute 3 前置 fail-soft：Server not found / not connected / no resources）。
 */
@Component
public class ReadMcpResourceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReadMcpResourceTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CC ReadMcpResourceTool.ts:62 maxResultSizeChars = 100_000. */
    private static final long MAX_RESULT_SIZE_CHARS = 100_000L;

    /** CC ReadMcpResourceTool.ts:61 searchHint. */
    private static final String SEARCH_HINT = "read a specific MCP resource by URI";

    private final McpToolPool mcpToolPool;

    /** 恒注册构造 · @Component 自动装配（决策 #65，CC getAllBaseTools 恒含）。 */
    @Autowired
    public ReadMcpResourceTool(McpToolPool mcpToolPool) {
        this.mcpToolPool = mcpToolPool;
    }

    @Override
    public String name() {
        return "ReadMcpResourceTool";
    }

    @Override
    public String description() {
        // CC ReadMcpResourceTool/prompt.ts:1-6 DESCRIPTION
        return "Reads a specific resource from an MCP server.\n"
             + "- server: The name of the MCP server to read from\n"
             + "- uri: The URI of the resource to read\n"
             + "\nUsage examples:\n"
             + "- Read a resource from a server: `readMcpResource({ server: \"myserver\", uri: \"my-resource-uri\" })`";
    }

    @Override
    public String prompt() {
        // CC ReadMcpResourceTool/prompt.ts:8-14 PROMPT
        return "Reads a specific resource from an MCP server, identified by server name and resource URI.\n\n"
             + "Parameters:\n"
             + "- server (required): The name of the MCP server from which to read the resource\n"
             + "- uri (required): The URI of the resource to read";
    }

    @Override
    public JsonNode inputSchema() {
        // CC ReadMcpResourceTool.ts:22-27 inputSchema: z.object({server, uri})
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("server").put("type", "string").put("description", "The MCP server name");
        props.putObject("uri").put("type", "string").put("description", "The resource URI to read");
        var req = schema.putArray("required");
        req.add("server");
        req.add("uri");
        return schema;
    }

    @Override
    public JsonNode outputSchema() {
        // CC ReadMcpResourceTool.ts:30-44 outputSchema: {contents: [{uri, mimeType?, text?, blobSavedTo?}]}
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        var contents = props.putObject("contents");
        contents.put("type", "array");
        var item = contents.putObject("items");
        item.put("type", "object");
        var itemProps = item.putObject("properties");
        itemProps.putObject("uri").put("type", "string").put("description", "Resource URI");
        itemProps.putObject("mimeType").put("type", "string").put("description", "MIME type of the content");
        itemProps.putObject("text").put("type", "string").put("description", "Text content of the resource");
        itemProps.putObject("blobSavedTo").put("type", "string")
            .put("description", "Path where binary blob content was saved");
        return schema;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /**
     * 搜索提示 · 对齐 CC ReadMcpResourceTool.ts:61 searchHint。
     * Tool 接口契约成员 override（G5 提升，CC Tool.ts:378；消费方 ToolSearch 待 OPD-23）。
     */
    @Override
    public String searchHint() {
        return SEARCH_HINT;
    }

    @Override
    public String toAutoClassifierInput(JsonNode input) {
        // CC ReadMcpResourceTool.ts:56-58 toAutoClassifierInput(input) = `${input.server} ${input.uri}`
        String server = input != null && input.has("server") ? input.get("server").asText("") : "";
        String uri = input != null && input.has("uri") ? input.get("uri").asText("") : "";
        String encoded = server + " " + uri;
        if (log.isDebugEnabled()) {
            log.debug("[ReadMcpResourceTool] toAutoClassifierInput: server={} uri={} → 编码='{}'",
                server, uri, encoded);
        }
        return encoded;
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String serverName = input.has("server") ? input.get("server").asText() : null;
        String uri = input.has("uri") ? input.get("uri").asText() : null;

        if (serverName == null || serverName.isBlank()) {
            return ToolResult.error(call.id(), "ReadMcpResourceTool: missing 'server'");
        }
        if (uri == null || uri.isBlank()) {
            return ToolResult.error(call.id(), "ReadMcpResourceTool: missing 'uri'");
        }

        try {
            // ── CC 3 throw 前置（ReadMcpResourceTool.ts:78-92）──
            Set<String> active = mcpToolPool.activeServers();

            // CC :80-84 server not found
            if (!active.contains(serverName)) {
                String available = String.join(", ", active);
                if (log.isDebugEnabled()) {
                    log.debug("[ReadMcpResourceTool] server 未找到: {} 可用={}", serverName, available);
                }
                return ToolResult.error(call.id(),
                    "Server \"" + serverName + "\" not found. Available servers: " + available);
            }

            // CC :86-88 client.type !== 'connected' → not connected
            if (!mcpToolPool.isServerConnected(serverName)) {
                if (log.isDebugEnabled()) {
                    log.debug("[ReadMcpResourceTool] server 未连接: {}", serverName);
                }
                return ToolResult.error(call.id(), "Server \"" + serverName + "\" is not connected");
            }

            // CC :90-92 !client.capabilities?.resources → no resources capability
            if (!mcpToolPool.getServerCapabilities(serverName)
                .map(caps -> caps.resources()).orElse(false)) {
                if (log.isDebugEnabled()) {
                    log.debug("[ReadMcpResourceTool] server 无 resources 能力: {}", serverName);
                }
                return ToolResult.error(call.id(), "Server \"" + serverName + "\" does not support resources");
            }

            // ── CC :95-101 resources/read 往返（完整 contents，含 blob 字段）──
            List<JsonNode> contents = mcpToolPool.readResourceContents(serverName, uri);
            if (log.isDebugEnabled()) {
                log.debug("[ReadMcpResourceTool] resources/read server={} uri={} 内容数={}",
                    serverName, uri, contents.size());
            }

            // ── CC :106-138 blob 拦截：text 直接透传；blob base64 解码落盘 → blobSavedTo 替换 ──
            ArrayNode mapped = JSON.createArrayNode();
            for (int i = 0; i < contents.size(); i++) {
                JsonNode c = contents.get(i);
                mapped.add(mapContent(serverName, c, i, ctx));
            }

            // CC :141-143 return { data: { contents } }；:151-156 mapToolResultToToolResultBlockParam
            //   content = jsonStringify(content) → 模型看到 {"contents":[...]}。
            // [IMP-E1 组 2-7 △-4] Java 旧实现返回 {"data":{"contents":[...]}} 与 CC 形状漂移；
            //   对齐 CC 移除外层 data 包装，直接返回 {"contents":[...]}。
            ObjectNode out = JSON.createObjectNode();
            out.set("contents", mapped);
            return ToolResult.success(call.id(), out.toString());
        } catch (Exception e) {
            log.warn("[ReadMcpResourceTool] execute 失败 server={} uri={}: {}", serverName, uri, e.getMessage());
            return ToolResult.error(call.id(), "ReadMcpResourceTool: " + e.getMessage());
        }
    }

    /**
     * 单条 resource content 映射 · 对齐 CC ReadMcpResourceTool.ts:107-138：
     * <ul>
     *   <li>'text' in c → {uri, mimeType, text}（:108-110）</li>
     *   <li>!('blob' in c) || typeof c.blob !== 'string' → {uri, mimeType}（:111-113）</li>
     *   <li>blob 解码落盘：persist 成功 → {uri, mimeType, blobSavedTo, text: saved message}（:127-137）；失败 → {uri, mimeType, text: 错误}（:120-126）</li>
     * </ul>
     */
    private ObjectNode mapContent(String serverName, JsonNode c, int index, ToolUseContext ctx) {
        ObjectNode entry = JSON.createObjectNode();
        entry.put("uri", c.path("uri").asText());
        if (c.hasNonNull("mimeType")) {
            entry.put("mimeType", c.path("mimeType").asText());
        }
        // CC :108-110 text 分支（blob 与 text 互斥，MCP ReadResourceResultSchema 二选一）
        // [G28② TR-E2-DEC-4] 'text' in c 含 null —— 对齐 CC 键存在判定（含 text:null），
        //   不再要求非 null（Java 旧实现 c.has("text") && !isNull() 会漏掉 text:null 资源）
        if (c.has("text")) {
            entry.put("text", c.path("text").asText());
            return entry;
        }
        // CC :111-113 无 blob 或 blob 非 string → 仅 uri/mimeType
        JsonNode blobNode = c.get("blob");
        if (blobNode == null || blobNode.isNull() || !blobNode.isTextual()) {
            if (log.isDebugEnabled()) {
                log.debug("[ReadMcpResourceTool] 资源无 text 亦无 string blob，仅保留元数据: uri={}", c.path("uri").asText());
            }
            return entry;
        }
        // CC :114 persistId = `mcp-resource-${Date.now()}-${i}-${random(6)}`
        String persistId = "mcp-resource-" + System.currentTimeMillis() + "-" + index + "-"
            + randomAlphaNumeric(6);
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(blobNode.asText());
        } catch (IllegalArgumentException e) {
            log.warn("[ReadMcpResourceTool] blob base64 解码失败 uri={}: {}", c.path("uri").asText(), e.getMessage());
            entry.put("text", "Binary content could not be saved to disk: " + e.getMessage());
            return entry;
        }
        Path dir = resolveToolResultsDir(ctx);
        if (dir == null) {
            // [G30⑮] tmpdir 回退已删除（CC persistBinaryContent 无 tmpdir 回退，mcpOutputStorage.ts:148-174）：
            //   无会话上下文（纯测试直调）→ 不落盘，返回失败提示（对齐 McpResultTransformer persistBlob 失败模板）
            entry.put("text", "Binary content could not be saved to disk: missing persistence context");
            return entry;
        }
        McpOutputStorage.PersistBinaryResult persisted =
            McpOutputStorage.persistBinaryContent(dir, decoded, c.path("mimeType").asText(null), persistId);
        if (persisted.isError()) {
            // CC :120-126 persist 失败 → text 错误
            entry.put("text", "Binary content could not be saved to disk: " + persisted.error());
            return entry;
        }
        // CC :127-137 persist 成功 → blobSavedTo + saved message
        entry.put("blobSavedTo", persisted.filepath());
        entry.put("text", McpOutputStorage.getBinaryBlobSavedMessage(
            persisted.filepath(),
            c.path("mimeType").asText(null),
            persisted.size(),
            "[Resource from " + serverName + " at " + c.path("uri").asText() + "] "));
        return entry;
    }

    /**
     * blob 落盘目录 · 对齐 ReadFileTool.pdfOutputDir 模式（ReadFileTool.java:1103-1109）：
     * ctx 非空且 effectiveCwd 可用 → {@code ToolResultStorage.getToolResultsDir(effectiveCwd, sessionId)}
     * （CC getToolResultsDir = projectDir/sessionId/tool-results，toolResultStorage.ts:97-105）。
     *
     * <p>[G30⑮] tmpdir 回退已删除：CC persistBinaryContent 无 tmpdir 回退（mcpOutputStorage.ts:148-174，
     * 恒用 getToolResultsDir()）；ctx 为 null（纯测试直调）→ 返回 null → 调用方不落盘返回失败提示。
     */
    private Path resolveToolResultsDir(ToolUseContext ctx) {
        if (ctx != null && ctx.effectiveCwd() != null && ctx.sessionId() != null) {
            return ToolResultStorage.getToolResultsDir(ctx.effectiveCwd(), ctx.sessionId());
        }
        return null;
    }

    /** 6 位随机字母数字（CC Math.random().toString(36).slice(2,8) 等价，工具级够用）。 */
    private static String randomAlphaNumeric(int len) {
        StringBuilder sb = new StringBuilder(len);
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
        }
        return sb.toString();
    }
}
