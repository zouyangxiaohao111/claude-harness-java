package com.nexusai.application.agent.browser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * nexusai-in-chrome 浏览器工具的 {@link Tool} 适配器 · 对齐 CCB {@code browserTools.ts} BROWSER_TOOLS。
 *
 * <p>每个实例对应一个浏览器工具（{@code name = mcp__nexusai-in-chrome__<tool>}），description +
 * inputSchema 逐字对齐 CCB（见 {@link BrowserToolSpec}）。execute 走 {@link BrowserChannel}
 * 转发通道（WS 批次实现）；通道未注入（null）→ <b>fail loud</b> 返回
 * {@link #EXTENSION_NOT_CONNECTED_MESSAGE}（「浏览器扩展未连接，请先连接 NexusAI in Chrome 扩展」）。
 *
 * <p><b>本阶段范围</b>：工具面 —— 模型可见可调、入参校验、通道转发预留。真实浏览器动作执行
 * （click/type/navigate/console/network 等）由后续 WS 批次在 {@link BrowserChannel} 实现内落地。
 */
final class BrowserMcpTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BrowserMcpTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** 扩展未连接时的 fail-loud 文案（对齐任务契约：「浏览器扩展未连接，请先连接 NexusAI in Chrome 扩展」）。 */
    static final String EXTENSION_NOT_CONNECTED_MESSAGE =
            "浏览器扩展未连接，请先连接 NexusAI in Chrome 扩展";

    private final String name;        // 全名：mcp__nexusai-in-chrome__<tool>
    private final String toolName;    // 原名（无前缀，如 "read_page"）
    private final BrowserToolSpec spec;
    private final JsonNode inputSchema;
    private final BrowserChannel channel;   // 可 null（未接线 → execute fail loud）

    /**
     * @param spec    工具定义（name/description/inputSchema/只读标记，对齐 CCB）
     * @param channel 转发通道；{@code null} = 未注入实现 → execute fail loud
     */
    BrowserMcpTool(BrowserToolSpec spec, BrowserChannel channel) {
        this.spec = spec;
        this.toolName = spec.toolName();
        this.name = BrowserToolRegistry.TOOL_PREFIX + spec.toolName();
        this.inputSchema = parseSchema(spec.inputSchemaJson(), spec.ccRef());
        this.channel = channel;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return spec.description();
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    /** 只读判定 · 对齐 spec（读类工具 true → 可并发 + 免写入权限检查）。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return spec.readOnly();
    }

    /** 并发安全判定 · 对齐 spec（读类工具 true；写类 computer/form_input/navigate/upload 等 false）。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return spec.concurrencySafe();
    }

    /**
     * 浏览器工具权限自表态 · 按 {@link BrowserToolSpec#readOnly()} 接入既有权限漏斗（Plan B-3 后端侧）。
     *
     * <p><b>为什么需要 override</b>：浏览器 18 工具经 {@link ToolRegistry#registerAll} 注册为普通
     * {@link Tool}，执行链 {@link com.nexusai.application.agent.tool.StreamingToolExecutor} →
     * {@link com.nexusai.application.agent.permission.ToolPermissionGate} → 10 层
     * {@link com.nexusai.application.agent.permission.PermissionPipeline} 天然覆盖（无独立绕过层、
     * 无 mcp__ 前缀 immune 白名单）。但若沿用 {@link Tool} 接口默认 {@code checkPermissions → Allow}
     * （Tool.java:292-300），1c 层缓存 Allow → 第 3 层原样透传 → 「无规则 + 非 bypass」下写类工具
     * 也被静默放行（不弹前端气泡），与产品决策相悖（对齐 MCP 工具先例：McpServerTool.checkPermissions
     * → Passthrough，见 McpServerTool.java:213-235）。
     *
     * <p><b>决策</b>：
     * <ul>
     *   <li>读类（readOnly=true：read_page / find / get_page_text / read_console_messages /
     *       read_network_requests / tabs_context_mcp / shortcuts_list）→ 自决 {@link PermissionResult.Allow}，
     *       <b>不拦</b>（用户显式 deny/ask 规则仍在 1a/1b 层先命中，不绕过显式配置）。</li>
     *   <li>写类（readOnly=false：form_input / computer / navigate / upload / javascript /
     *       tabs_create_mcp / resize_window / gif_creator / update_plan / shortcuts_execute /
     *       switch_browser 等）→ 自决 {@link PermissionResult.Passthrough}，<b>走通用漏斗</b>：
     *       bypass 模式 Layer2a 短路 Allow（不弹气泡）；非 bypass 第 3 层转 Ask →
     *       {@link com.nexusai.application.agent.permission.bubble.PermissionBubbleService} 弹前端气泡 /
     *       子 agent BUBBLE 冒泡；用户 deny / allow 规则在 1a / 2b 层独立生效。</li>
     * </ul>
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (spec.readOnly()) {
            if (log.isDebugEnabled()) {
                log.debug("BrowserMcpTool: {} 只读浏览器工具 → 权限自决 Allow（不拦；用户 deny/ask 规则仍在 1a/1b 生效）",
                    name);
            }
            return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("browser read-only tool allow"),
                null, false, null, java.util.List.of());
        }
        if (log.isDebugEnabled()) {
            log.debug("BrowserMcpTool: {} 写浏览器工具 → 权限自决 Passthrough（走通用漏斗：bypass 2a 短路 / 非 bypass Ask 弹气泡）",
                name);
        }
        return new PermissionResult.Passthrough(
            "Browser tool requires permission.",
            null,
            java.util.List.of(),
            null,
            null);
    }

    /**
     * 执行：优先转发通道；通道未注入 → fail loud 返回「浏览器扩展未连接」。
     *
     * <p><b>多会话并行</b>：从 {@link RequestContext#sessionId()} 取当前会话，透传给
     * {@link BrowserChannel#send(String, String, Map)} —— 扩展按 sessionId 定位/创建该会话的
     * tab 组（对齐 CCB tabs_context_mcp「每个会话自己的 tab 组」）；结果回传经 callId 匹配，
     * 与 sessionId 无关。
     *
     * <p><b>fail loud（规则十二）</b>：本阶段 WS 通道未实现，模型调用浏览器工具时不得静默
     * 吞掉或假成功 —— 必须返回明确错误文案，让模型/用户知道需要先连接 NexusAI in Chrome 扩展。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        BrowserChannel ch = this.channel;
        if (ch == null) {
            log.warn("BrowserMcpTool: {} 调用但浏览器扩展未连接（BrowserChannel 未注入）→ fail loud 返回「{}」",
                name, EXTENSION_NOT_CONNECTED_MESSAGE);
            return ToolResult.error(call.id(), EXTENSION_NOT_CONNECTED_MESSAGE);
        }
        Map<String, Object> args;
        try {
            // call.input() 为已解析 JsonNode（ToolUseBlock record），按扁平 Map 转发给扩展
            args = JSON.convertValue(call.input(), MAP_TYPE);
        } catch (Exception e) {
            log.error("BrowserMcpTool: {} 入参转 Map 失败: {}", name, e.getMessage(), e);
            return ToolResult.error(call.id(), "浏览器工具入参解析失败: " + e.getMessage());
        }
        try {
            String sessionId = RequestContext.sessionId();
            String result = ch.send(sessionId, toolName, args);
            // 截图类大 base64 不直接给模型（纯噪音 + 巨 token）：落盘为文件，tool_result 给指针
            result = persistScreenshotIfPresent(result, sessionId);
            if (log.isDebugEnabled()) {
                log.debug("BrowserMcpTool: {} 转发成功（channel 返回 {} 字符）", name,
                    result == null ? 0 : result.length());
            }
            return ToolResult.success(call.id(), result);
        } catch (Exception e) {
            log.error("BrowserMcpTool: {} 转发失败: {}", name, e.getMessage(), e);
            return ToolResult.error(call.id(), "浏览器工具调用失败: " + e.getMessage());
        }
    }

    /**
     * 截图落盘处理：扩展返回的截图结果含 {@code dataUrl}（base64 图）→ 解码写文件，
     * tool_result 改为「保存路径 + 体积」等指针，不再把整段 base64 回给模型。
     * 非截图 / 非 dataUrl / 落盘失败 → 原样返回。
     *
     * <p>存储目录：{tmp}/nexusai-browser-shots/&lt;sessionId&gt;/shot-*.png|jpeg
     * （跨层约定：后续前端展示图片需经 HTTP 资源端点映射该目录，另议）。
     */
    private String persistScreenshotIfPresent(String result, String sessionId) {
        if (result == null || result.isEmpty()) {
            return result;
        }
        JsonNode node;
        try {
            node = JSON.readTree(result);
        } catch (Exception e) {
            return result;
        }
        if (node == null || !node.isObject()) {
            return result;
        }
        JsonNode dataUrlNode = node.get("dataUrl");
        if (dataUrlNode == null || !dataUrlNode.isTextual()) {
            return result;
        }
        String dataUrl = dataUrlNode.asText();
        if (!dataUrl.startsWith("data:image/")) {
            return result;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            return result;
        }
        String ext = dataUrl.regionMatches(true, 0, "data:image/png", 0, "data:image/png".length())
                ? "png" : "jpeg";
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            String safe = (sessionId == null || sessionId.isBlank())
                    ? "anon"
                    : sessionId.replaceAll("[^A-Za-z0-9_.-]", "_");
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"),
                    "nexusai-browser-shots", safe);
            Files.createDirectories(dir);
            Path file = dir.resolve("shot-" + System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + "." + ext);
            Files.write(file, bytes);
            if (log.isInfoEnabled()) {
                log.info("BrowserMcpTool: 截图已落盘 {}（{} 字节，原 base64 {} 字符，tool_result 不再回传 base64）",
                    file.toAbsolutePath(), bytes.length, dataUrl.length());
            }
            ObjectNode summary = JSON.createObjectNode();
            summary.put("ok", true);
            summary.put("action", node.path("action").asText("screenshot"));
            summary.put("savedTo", file.toAbsolutePath().toString());
            summary.put("bytes", bytes.length);
            summary.put("note", "截图已保存到本地文件路径 savedTo；若需模型看该图请走视觉/读文件通道读取，不要用 base64");
            return JSON.writeValueAsString(summary);
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("BrowserMcpTool: 截图落盘失败，退回原结果（含 base64）: {}", e.getMessage());
            }
            return result;
        }
    }

    /** 解析 CCB inputSchema JSON 文本 → JsonNode；解析失败抛 IllegalStateException（fail fast，定义错误必现）。 */
    private static JsonNode parseSchema(String json, String ccRef) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(
                "BrowserMcpTool schema 解析失败 " + ccRef + ": " + e.getMessage(), e);
        }
    }
}
