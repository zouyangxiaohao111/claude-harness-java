package com.nexusai.application.agent.tool.impl.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * WebBrowser Tool 真实现 · 对齐 CC {@code Open-ClaudeCode/src/tools/WebBrowserTool/WebBrowserTool.ts}
 * （G32① 按真源重写，G11 改名 PascalCase）。
 *
 * <p><b>WHY（G32①）</b>: CC 真源已就位（WebBrowserTool.ts，179 行）——轻量 HTTP 抓取工具
 * （非完整浏览器引擎），支持 {@code navigate}/{@code screenshot} 两种 action。原 fail-loud 注册桩
 * （WFI-R1）替换为真实现，输出契约 {@code {title, url, content?, screenshot?}} 对齐 CC。
 *
 * <p><b>门控语义</b>: {@code nexusai.feature.web-browser-tool=true} 时 bean 创建（CC tools.ts:117
 * {@code feature('WEB_BROWSER_TOOL')} 模块门控），{@link #isEnabled()} 默认 true（CC descriptor
 * 无 isEnabled override）。
 *
 * <p>CC 注册点: Open-ClaudeCode/src/tools.ts:217。
 *
 * <p><b>受控残留</b>: Java 用 {@link HttpClient} 直连（无 JS 执行引擎），与 CC {@code fetch}
 * 语义一致——仅看到 server-rendered HTML；screenshot 与 navigate 同为文本快照（CC :90-140）。
 * 无 SSRF 预检链（CC WebBrowserTool 亦无，plain fetch；WebFetchTool 才是 SSRF 防护路径）。
 */
@Component
@ConditionalOnProperty(prefix = "nexusai.feature", name = "web-browser-tool",
        havingValue = "true", matchIfMissing = false)
public class WebBrowserTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebBrowserTool.class);

    /** CC 工具名 · {@code WebBrowserTool.ts:6} WEB_BROWSER_TOOL_NAME='WebBrowser'。 */
    public static final String NAME = ToolNameConstants.WEB_BROWSER_TOOL_NAME;

    /** CC original: maxResultSizeChars=100_000（WebBrowserTool.ts:32）。 */
    private static final long MAX_RESULT_SIZE_CHARS = 100_000L;

    /** 文本内容截断上限 50k · CC original: :128-130 {@code textContent.length > 50_000}。 */
    private static final int TEXT_TRUNCATE_LIMIT = 50_000;

    /** Java 防御上限（CC 无显式 body 上限，防 OOM）· 10MB，与 WebFetchSecurity 对齐。 */
    private static final long MAX_BODY_BYTES = 10L * 1024 * 1024;

    /** CC UA · WebBrowserTool.ts:95-99（Chrome 120 UA）。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final HttpClient httpClient;

    public WebBrowserTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Fetch and read web page content via HTTP";
    }

    /** 搜索提示 · 对齐 CC WebBrowserTool.ts:31 searchHint。 */
    @Override
    public String searchHint() {
        return "web browser navigate url page screenshot click";
    }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /** 不可并发 · 对齐 CC WebBrowserTool.ts:60-62 isConcurrencySafe() → false。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /** 只读 · 对齐 CC WebBrowserTool.ts:63-65 isReadOnly() → true。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 用户可见名 · 对齐 CC WebBrowserTool.ts:67-69 userFacingName() → 'Browser'。 */
    @Override
    public String userFacingName() {
        return "Browser";
    }

    /** 工具提示词 · 对齐 CC WebBrowserTool.ts:42-58 prompt()（逐字）。 */
    @Override
    public String prompt() {
        return """
                Fetch web pages via HTTP and extract their text content. This is a lightweight browser tool (HTTP fetch, not a full browser engine).

                Supported actions:
                - navigate: Fetch a URL and extract page title + text content
                - screenshot: Same as navigate (returns text snapshot, not a visual screenshot)

                Limitations:
                - No JavaScript execution — only sees server-rendered HTML
                - click/type/scroll require a full browser runtime (not available)
                - For full browser interaction, use the NexusAI-in-Chrome MCP tools instead

                Use this for:
                - Reading web page content and documentation
                - Checking API endpoints that return HTML
                - Quick page title/content extraction""";
    }

    /** 输入 schema · 对齐 CC WebBrowserTool.ts:8-18 {@code z.strictObject({url, action?})}。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode url = props.putObject("url");
        url.put("type", "string");
        url.put("description", "URL to fetch and extract content from.");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.putArray("enum").add("navigate").add("screenshot");
        action.put("description",
                "Action to perform. \"navigate\" fetches page content (default). "
                        + "\"screenshot\" returns a text snapshot of the page.");

        schema.putArray("required").add("url");
        schema.put("additionalProperties", false);
        return schema;
    }

    /** 工具使用消息渲染 · 对齐 CC WebBrowserTool.ts:71-74（action 缺省 navigate）。 */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        String action = input != null && input.has("action") ? input.get("action").asText() : "navigate";
        String url = input != null && input.has("url") ? input.get("url").asText() : "...";
        return "Browser " + action + ": " + url;
    }

    /**
     * 结果块渲染 · 对齐 CC WebBrowserTool.ts:76-85 mapToolResultToToolResultBlockParam：
     * {@code `${content.title} (${content.url})\n${content.content ?? ''}`}。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(
            AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (isError) {
            String msg = result instanceof ToolResult<?> tr
                    ? ToolResult.renderToolResultPayloadText(tr) : "Browser fetch failed.";
            return new ToolResultBlockParam(toolUseId, "tool_result", msg, true);
        }
        String title = "";
        String url = "";
        String content = "";
        if (result != null && result.data() instanceof String s) {
            try {
                JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                title = node.path("title").asText("");
                url = node.path("url").asText("");
                content = node.has("content") ? node.path("content").asText("") : "";
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[WebBrowser] 结果解析失败, 回退原文: {}", e.toString());
                }
                content = s;
            }
        }
        return new ToolResultBlockParam(toolUseId, "tool_result",
                title + " (" + url + ")\n" + (content == null ? "" : content), false);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * CC WebBrowserTool.ts:87-168 call — HTTP 抓取 + 标题/文本提取。
     *
     * <p>navigate/screenshot 均执行抓取；非 2xx → {@code {title: HTTP N, url, content: Error: ...}}；
     * 抓取异常 → {@code {title: 'Error', url, content: 'Failed to fetch: ...'}}（不抛，CC :149-157）。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String url = input != null && input.has("url") ? input.get("url").asText() : null;
        String action = input != null && input.has("action") ? input.get("action").asText() : "navigate";
        if (url == null || url.isBlank()) {
            return ToolResult.error(call.id(), "missing required input: url");
        }
        if (!"navigate".equals(action) && !"screenshot".equals(action)) {
            return ToolResult.success(call.id(), buildOutput("", url,
                    "Unknown action \"" + action + "\"."));
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (body != null && body.length > MAX_BODY_BYTES) {
                // Java 防御上限（CC 无显式 body 上限；防 OOM）
                return ToolResult.success(call.id(), buildOutput("", url,
                        "Error: response too large (max " + MAX_BODY_BYTES + " bytes)"));
            }

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String html = body == null ? "" : new String(body, StandardCharsets.UTF_8);
                String title = extractTitle(html);
                String text = extractText(html);
                String finalUrl = response.uri() != null ? response.uri().toString() : url;
                if ("screenshot".equals(action)) {
                    return ToolResult.success(call.id(), buildOutput(title, finalUrl,
                            "[Text snapshot — visual screenshots require Chrome browser tools]\n\n" + text));
                }
                return ToolResult.success(call.id(), buildOutput(title, finalUrl, text));
            }
            return ToolResult.success(call.id(), buildOutput(
                    "HTTP " + response.statusCode(), url,
                    "Error: " + response.statusCode() + " " + reasonPhrase(response.statusCode())));
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("[WebBrowserTool] 抓取失败: url={} err={}", url, e.getMessage());
            }
            return ToolResult.success(call.id(), buildOutput("Error", url,
                    "Failed to fetch: " + (e.getMessage() == null ? e.toString() : e.getMessage())));
        }
    }

    /** CC :116-117 title 提取 — {@code <title>...</title>} 正则。 */
    private static String extractTitle(String html) {
        var m = java.util.regex.Pattern.compile("<title[^>]*>([^<]*)</title>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        return m.find() ? m.group(1).trim() : "";
    }

    /** CC :120-130 文本提取 — 去 script/style/标签/空白折叠 + 50k 截断。 */
    private static String extractText(String html) {
        String text = html
                .replaceAll("(?is)<script[\\s\\S]*?</script>", "")
                .replaceAll("(?is)<style[\\s\\S]*?</style>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() > TEXT_TRUNCATE_LIMIT) {
            text = text.substring(0, TEXT_TRUNCATE_LIMIT) + "\n[truncated]";
        }
        return text;
    }

    /** 输出契约 · CC :142-147 BrowserOutput {title, url, content}。 */
    private static String buildOutput(String title, String url, String content) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("title", title);
        node.put("url", url);
        node.put("content", content);
        return node.toString();
    }

    /** HTTP 状态文本（CC response.statusText 等价，JDK 不暴露）。 */
    private static String reasonPhrase(int code) {
        return switch (code) {
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> String.valueOf(code);
        };
    }
}
