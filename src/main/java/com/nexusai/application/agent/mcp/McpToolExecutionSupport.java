package com.nexusai.application.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MCP 工具执行面共享静态工具（单一包装面 · Q-09-R2-2）。
 *
 * <p><b>WHY 存在</b>：agent 轨（{@code AgentMcpTool}）与生产轨（{@code McpServerTool}）的
 * 包装面必须共用同一实现面（「无第二套复制实现」），进度事件 / mcpMeta / searchHint /
 * autoClassifier / userFacingName / isResultTruncated 等 per-tool 覆盖全部收敛在本类，
 * 两端消费。CC 真源：{@code client.ts} fetchToolsForClient per-tool 覆盖（:1779-1803、
 * :1846-1936、:1972-1976）+ {@code MCPTool.ts:67-69} + {@code terminal.ts:119-131}。
 *
 * <p>纯静态工具类，不建 Spring bean。
 */
public final class McpToolExecutionSupport {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutionSupport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CC MAX_MCP_DESCRIPTION_LENGTH = 2048（client.ts:218）。 */
    public static final int MAX_MCP_DESCRIPTION_LENGTH = 2048;

    /** CC terminal.ts:7 MAX_LINES_TO_SHOW = 3（isOutputLineTruncated 算法常量）。 */
    private static final int MAX_LINES_TO_SHOW = 3;

    private McpToolExecutionSupport() {
    }

    /**
     * C1 进度事件发射 · 对齐 CC client.ts:1846-1856/:1884-1895/:1925-1936：
     * <pre>
     * { toolUseID, data: { type:'mcp_progress', status:'started'|'completed'|'failed',
     *   serverName, toolName, elapsedTimeMs? } }
     * </pre>
     * 进度通道：StreamingToolExecutor 三参 dispatch → wrappedCallback 入队 pendingProgress。
     *
     * @param onProgress 进度回调（可为 null，null 则跳过发射）
     * @param serverName MCP server 名
     * @param toolName   MCP server 上的 tool 名
     * @param mcpToolName 注册名（mcp__{server}__{tool}，仅日志用）
     * @param toolUseId  工具调用 ID
     * @param status     started / completed / failed
     * @param elapsedMs  已耗时 ms（started 为 null）
     */
    public static void emitMcpProgress(Consumer<Tool.ToolProgress> onProgress,
                                       String serverName, String toolName, String mcpToolName,
                                       String toolUseId, String status, Long elapsedMs) {
        if (onProgress == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "mcp_progress");
        data.put("status", status);
        data.put("serverName", serverName);
        data.put("toolName", toolName);
        if (elapsedMs != null) {
            data.put("elapsedTimeMs", elapsedMs);
        }
        onProgress.accept(new Tool.ToolProgress(toolUseId, data));
        if (log.isDebugEnabled()) {
            log.debug("MCP 进度事件: tool={} id={} status={} 耗时={}ms",
                mcpToolName, abbreviateId(toolUseId), status, elapsedMs);
        }
    }

    /**
     * [A1·对齐 CC mcpMeta] 构造 MCP 透传元数据: _meta (Map) + structuredContent (JsonNode).
     * MCP tools/call result 形如 {content, isError, _meta?, structuredContent?}.
     * CC 真源：client.ts:1897-1908（mcpResult._meta || mcpResult.structuredContent → mcpMeta）。
     */
    public static ToolResult.McpMeta buildMcpMeta(JsonNode result) {
        JsonNode metaNode = result.path("_meta");
        Map<String, Object> meta = metaNode.isMissingNode() || metaNode.isNull()
            ? null
            : MAPPER.convertValue(metaNode, new TypeReference<Map<String, Object>>() {});
        JsonNode structuredContent = result.path("structuredContent");
        if ((meta == null || meta.isEmpty()) && (structuredContent.isMissingNode() || structuredContent.isNull())) {
            return null;
        }
        return new ToolResult.McpMeta(
            meta, structuredContent.isMissingNode() || structuredContent.isNull() ? null : structuredContent);
    }

    /**
     * CC MCPTool.ts:67-69 isResultTruncated(output) { return isOutputLineTruncated(output) }
     * → terminal.ts:119-131 isOutputLineTruncated 逐字算法：内容需多于
     * {@code MAX_LINES_TO_SHOW(3)} 个换行（占满 &gt; 3 行），且第 4 个换行后仍有内容
     * （尾随换行是终止符不是新行，对齐 renderTruncatedContent 的 trimEnd）。
     *
     * @param content 工具结果文本（MCP output 即 string）
     * @return true = 超 3 行截断语义命中（有更多内容可展开）
     */
    public static boolean isResultTruncated(String content) {
        if (content == null) {
            return false;
        }
        int pos = 0;
        for (int i = 0; i <= MAX_LINES_TO_SHOW; i++) {
            pos = content.indexOf('\n', pos);
            if (pos == -1) {
                return false;
            }
            pos++;
        }
        return pos < content.length();
    }

    /**
     * CC client.ts:1972-1976 userFacingName()：{@code `${client.name} - ${
     * tool.annotations?.title || tool.name} (MCP)`}——annotations.title 优先，否则 tool 名。
     */
    public static String userFacingName(String serverName, String toolName, JsonNode annotations) {
        String displayName = toolName;
        if (annotations != null && annotations.path("title").isTextual()) {
            String title = annotations.path("title").asText();
            if (!title.isEmpty()) {
                displayName = title;
            }
        }
        return serverName + " - " + displayName + " (MCP)";
    }

    /**
     * CC client.ts:1779-1783 searchHint 提取：{@code typeof tool._meta?.['anthropic/searchHint']
     * === 'string' → .replace(/\s+/g,' ').trim() || undefined}。非 string（外部 MCP server
     * 可给任意类型）→ null。
     */
    public static String extractSearchHint(JsonNode meta) {
        if (meta == null || meta.isNull() || meta.isMissingNode()) {
            return null;
        }
        JsonNode hint = meta.get("anthropic/searchHint");
        if (hint == null || !hint.isTextual()) {
            return null;
        }
        String collapsed = collapseWhitespace(hint.asText()).trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    /**
     * 空白折叠 · 等价 CC {@code .replace(/\s+/g, ' ')} (client.ts:1781)。
     *
     * <p><b>WHY 手写而非正则 {@code \\s+}</b>：Java 25 起 {@code "\s"} 字符串转义语义改变
     * （{@code \s} 编译为空格字符，正则 {@code \s} 不再匹配换行/制表），用
     * {@link Character#isWhitespace(char)} 逐字符折叠与 JS {@code \s} 行为一致
     * （换行/制表/CR/Unicode 空白均折叠为单个空格）。
     */
    public static String collapseWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        return sb.toString();
    }

    /**
     * CC client.ts:1801-1803 + :1733-1741 toAutoClassifierInput：
     * <pre>
     * mcpToolInputToAutoClassifierInput(input, tool.name):
     *   keys.length > 0 ? keys.map(k => `${k}=${String(input[k])}`).join(' ') : toolName
     * </pre>
     * 值用 JS {@code String()} 语义强转（对象→[object Object]、数组→逗号拼接、null→"null"）。
     *
     * @param input    工具入参（对象节点；空/非对象 → 回退 toolName）
     * @param toolName MCP server 上的 tool 名（空输入回退值）
     */
    public static String toAutoClassifierInput(JsonNode input, String toolName) {
        if (input == null || input.isNull() || input.isMissingNode()
            || !input.isObject() || input.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("MCP toAutoClassifierInput 空输入回退 toolName: {}", toolName);
            }
            return toolName;
        }
        List<String> parts = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = input.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            parts.add(e.getKey() + "=" + jsString(e.getValue()));
        }
        if (log.isDebugEnabled()) {
            log.debug("MCP toAutoClassifierInput 编码: tool={} 字段数={}", toolName, parts.size());
        }
        return String.join(" ", parts);
    }

    /**
     * JS {@code String(value)} 语义强转（CC client.ts:1736 ${String(input[k])}）：
     * 对象→[object Object]，数组→元素逗号拼接（嵌套递归），null→"null"，文本/数字/布尔→自身。
     */
    public static String jsString(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(jsString(node.get(i)));
            }
            return sb.toString();
        }
        if (node.isObject()) {
            return "[object Object]";
        }
        return "null";
    }

    private static String abbreviateId(String id) {
        return id == null ? "null" : (id.length() <= 24 ? id : id.substring(0, 24));
    }
}
