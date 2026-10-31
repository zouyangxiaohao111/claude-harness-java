package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.mcp.InProcessMcpTransport;
import com.nexusai.application.agent.mcp.McpCallContext;
import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.application.agent.mcp.McpToolPool;
import com.nexusai.application.agent.mcp.McpTransportFactory;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * computer-use <b>真 in-process MCP server</b> 单测 · 对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/mcpServer.ts:255-314}。
 *
 * <p><b>WHY 这些断言存在（规则 9 · 测试验证意图）</b>：
 * <ul>
 *   <li><b>工具面逐字</b>：模型看到的工具名 / schema 是 CCB 逐字移植的产物；
 *       改名或改 schema ⇒ 模型调用方式漂移 ⇒ 行为静默劣化 ⇒ 必须锁死；</li>
 *   <li><b>平台裁剪</b>：Windows 35 / macOS 24（11 个 Windows 专属在 macOS 不下发）
 *       —— 这是 CCB {@code tools.ts:418-836} 的条件展开语义；</li>
 *   <li><b>真 MCP 往返</b>：必须证明走的是 <b>JSON-RPC 协议</b>（tools/list → tools/call），
 *       而不是进程内直调 —— 否则「真 server」只是换了个名字；</li>
 *   <li><b>wire 字段收窄</b>：CCB 会剥掉 {@code screenshot} / {@code telemetry} piggyback
 *       ⇒ 本仓也必须剥（截图几何仍由会话级 store 持有）；</li>
 *   <li><b>免弹窗 + request_access 仍走 Ask</b>：审批唯一入口不能被 CU 免弹窗规则带跑。</li>
 * </ul>
 */
class ComputerUseMcpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * CCB 全集 35 个工具（<b>逐字抄自</b> {@code packages/@ant/computer-use-mcp/src/tools.ts}
     * 的 {@code buildComputerUseTools} 数组顺序，teach 三件套因 {@code teachMode=false} 不下发）。
     */
    private static final List<String> CCB_35 = List.of(
            "request_access", "screenshot", "zoom", "left_click", "double_click", "triple_click",
            "right_click", "middle_click", "type", "key", "scroll", "left_click_drag", "mouse_move",
            "open_application",
            "window_management", "click_element", "type_into_element", "open_terminal", "bind_window",
            "activate_window", "prompt_respond", "status_indicator", "virtual_keyboard", "virtual_mouse",
            "mouse_wheel",
            "switch_display", "list_granted_applications", "read_clipboard", "write_clipboard", "wait",
            "cursor_position", "hold_key", "left_mouse_down", "left_mouse_up", "computer_batch");

    /** CCB 的 macOS 工具面 = 35 − 11（Windows 专属条件块不展开）· 逐字同上顺序。 */
    private static final List<String> CCB_24_MACOS = List.of(
            "request_access", "screenshot", "zoom", "left_click", "double_click", "triple_click",
            "right_click", "middle_click", "type", "key", "scroll", "left_click_drag", "mouse_move",
            "open_application",
            "switch_display", "list_granted_applications", "read_clipboard", "write_clipboard", "wait",
            "cursor_position", "hold_key", "left_mouse_down", "left_mouse_up", "computer_batch");

    /** Windows 专属 11 个（macOS 工具面里必须缺席）。 */
    private static final List<String> WINDOWS_ONLY_11 = List.of(
            "window_management", "click_element", "type_into_element", "open_terminal", "bind_window",
            "activate_window", "prompt_respond", "status_indicator", "virtual_keyboard", "virtual_mouse",
            "mouse_wheel");

    // ─────────────────────────── 夹具 ───────────────────────────

    /** 最小 JPEG 字节（SOI FF D8 FF … EOI FF D9）· 够验证 base64 + magic。 */
    private static final byte[] TINY_JPEG = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xD9};

    /** 假截屏器（不加载 JNA native）· 产出固定 JPEG。 */
    private static final class FakeCapturer implements ComputerUseScreenCapturer {
        @Override
        public ComputerUseCaptureResult capture() {
            return new ComputerUseCaptureResult(TINY_JPEG, "image/jpeg",
                    2, 2, 1920, 1080, 0, 0, 1.0d, 1920, 1080);
        }

        @Override
        public ComputerUseDisplayGeometry currentDisplayGeometry() {
            return new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0);
        }
    }

    private static ComputerUseCapabilities caps(String platform) {
        return new ComputerUseCapabilities(ComputerUseRequestAccessResult.SCREENSHOT_FILTERING, platform, false);
    }

    /** 造一个 server（win32 平台 + 截图工具 + request_access 工具）。 */
    private static ComputerUseMcpServer newServer(String platform, boolean disabled) {
        Map<String, Tool> tools = new LinkedHashMap<>();
        tools.put("screenshot", new ComputerUseScreenshotTool(new FakeCapturer(), new ComputerUseGeometryStore()));
        tools.put("request_access", new ComputerUseRequestAccessTool(
                ComputerUsePhase2aFakes.grantStore("sess-1", ComputerUseGrants.empty().toJson()),
                new ComputerUseLockService(lockDataSource(), true)));
        return ComputerUseMcpServer.createComputerUseMcpServer(caps(platform),
                ComputerUseCoordinateMode.PIXELS, null, tools, disabled,
                ComputerUseSessionContext::of);
    }

    /**
     * 临时 SQLite 锁表数据源（<b>只碰临时文件，绝不碰用户真库</b>）。
     *
     * <p>WHY 需要真 sqlite：{@code request_access} 的权限自决要先做跨会话锁检查
     * （{@code ComputerUseLockService.check}）⇒ 需要能真正查表的 DataSource。
     */
    private static DataSource lockDataSource() {
        try {
            var ds = ComputerUseTestSqlite.create(
                    java.nio.file.Files.createTempDirectory("cu-mcp-test"), "cu-lock.db");
            ComputerUseTestSqlite.createLockTable(ds);
            return ds;
        } catch (Exception e) {
            throw new IllegalStateException("构造临时 sqlite 失败: " + e.getMessage(), e);
        }
    }

    /** tools/list 走真协议（linked transport pair）· 返回解析后的 tools 数组。 */
    private static List<Map<String, Object>> listToolsOverWire(ComputerUseMcpServer server) throws Exception {
        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);
        JsonNode result = pair[0].sendRequest("tools/list", Map.of()).join();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (JsonNode node : result.path("tools")) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", node.path("name").asText());
            t.put("description", node.path("description").asText());
            t.put("inputSchema", node.path("inputSchema"));
            tools.add(t);
        }
        return tools;
    }

    /** 规范化 JSON（键排序）· 让 schema 断言与键序无关。 */
    private static String canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(keys.get(i)).append("\":").append(canonical(node.get(keys.get(i))));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonical(node.get(i)));
            }
            return sb.append(']').toString();
        }
        return node.isTextual() ? node.textValue() : node.toString();
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("测试内 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────── ① 工具面 ───────────────────────────

    @Test
    @DisplayName("Windows：tools/list = 35 个，工具名逐字等于 CCB 全集（顺序也一致）")
    void listToolsWin32_isExactlyCcbSet() throws Exception {
        List<Map<String, Object>> tools = listToolsOverWire(newServer("win32", false));
        assertThat(tools).hasSize(35);
        assertThat(tools.stream().map(t -> (String) t.get("name")).toList()).isEqualTo(CCB_35);
    }

    @Test
    @DisplayName("macOS：tools/list = 24 个，11 个 Windows 专属工具不注册")
    void listToolsDarwin_is24WithoutWindowsOnlyTools() throws Exception {
        List<Map<String, Object>> tools = listToolsOverWire(newServer("darwin", false));
        assertThat(tools).hasSize(24);
        assertThat(tools.stream().map(t -> (String) t.get("name")).toList()).isEqualTo(CCB_24_MACOS);
        for (String winOnly : WINDOWS_ONLY_11) {
            assertThat(tools.stream().map(t -> (String) t.get("name")).toList())
                    .as("macOS 工具面不得含 Windows 专属工具 %s", winOnly)
                    .doesNotContain(winOnly);
        }
    }

    @Test
    @DisplayName("schema 逐字等于 CCB（screenshot / zoom / left_click / wait / cursor_position）")
    void toolSchemas_areVerbatimCcb() throws Exception {
        List<Map<String, Object>> tools = listToolsOverWire(newServer("win32", false));
        Map<String, JsonNode> schemas = new LinkedHashMap<>();
        for (Map<String, Object> t : tools) {
            schemas.put((String) t.get("name"), (JsonNode) t.get("inputSchema"));
        }

        // CCB tools.ts:202-219（screenshot）
        assertThat(canonical(schemas.get("screenshot"))).isEqualTo(canonical(parse("""
                {"type":"object","properties":{"save_to_disk":{"type":"boolean",
                 "description":"Save the image to disk so it can be attached to a message for the user. Returns the saved path in the tool result. Only set this when you intend to share the image — screenshots you're just looking at don't need saving."}},
                 "required":[]}""")));

        // CCB tools.ts:221-243（zoom）
        assertThat(canonical(schemas.get("zoom"))).isEqualTo(canonical(parse("""
                {"type":"object","properties":{
                   "region":{"type":"array","items":{"type":"integer"},"minItems":4,"maxItems":4,
                     "description":"(x0, y0, x1, y1): Rectangle to zoom into, in the coordinate space of the most recent full-screen screenshot. x0,y0 = top-left, x1,y1 = bottom-right."},
                   "save_to_disk":{"type":"boolean",
                     "description":"Save the image to disk so it can be attached to a message for the user. Returns the saved path in the tool result. Only set this when you intend to share the image."}},
                 "required":["region"]}""")));

        // CCB tools.ts:245-258（left_click · pixels 制的 coordinateTuple + clickModifierText）
        assertThat(canonical(schemas.get("left_click"))).isEqualTo(canonical(parse("""
                {"type":"object","properties":{
                   "coordinate":{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2,
                     "description":"(x, y): Horizontal pixel position read directly from the most recent screenshot image, measured from the left edge. The server handles all scaling."},
                   "text":{"type":"string",
                     "description":"Modifier keys to hold during the click (e.g. \\"shift\\", \\"ctrl+shift\\"). Supports the same syntax as the key tool."}},
                 "required":["coordinate"]}""")));

        // CCB tools.ts:896-908（wait）
        assertThat(canonical(schemas.get("wait"))).isEqualTo(canonical(parse("""
                {"type":"object","properties":{"duration":{"type":"number",
                 "description":"Duration in seconds (0–100)."}},"required":["duration"]}""")));

        // CCB tools.ts:910-921（cursor_position）
        assertThat(canonical(schemas.get("cursor_position")))
                .isEqualTo(canonical(parse("{\"type\":\"object\",\"properties\":{},\"required\":[]}")));
    }

    @Test
    @DisplayName("描述逐字等于 CCB（screenshot 的 none 态文案 + 前台门控文案）")
    void toolDescriptions_areVerbatimCcb() throws Exception {
        List<Map<String, Object>> tools = listToolsOverWire(newServer("win32", false));
        Map<String, String> descs = new LinkedHashMap<>();
        for (Map<String, Object> t : tools) {
            descs.put((String) t.get("name"), (String) t.get("description"));
        }
        // CCB tools.ts:135-139（screenshotFiltering='none' 分支）+ tools.ts:204-205
        assertThat(descs.get("screenshot")).isEqualTo(
                "Take a screenshot of the primary display. On this platform, screenshots are NOT filtered — "
                        + "all open windows are visible. Input actions targeting apps not in the session "
                        + "allowlist are rejected. Returns an error if the allowlist is empty. The returned "
                        + "image is what subsequent click coordinates are relative to.");
        // CCB tools.ts:32-33（FRONTMOST_GATE_DESC）
        assertThat(descs.get("left_click")).isEqualTo(
                "Left-click at the given coordinates. The frontmost application must be in the session "
                        + "allowlist at the time of this call, or this tool returns an error and does nothing.");
    }

    // ─────────────────────────── ② 真 MCP 往返 ───────────────────────────

    @Test
    @DisplayName("真 MCP 往返：tools/list → tools/call(screenshot) ⇒ image content block（base64 可解码 + JPEG magic）")
    void callToolOverWire_returnsImageContentBlock() throws Exception {
        ComputerUseMcpServer server = newServer("win32", false);
        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);

        // ① tools/list（真协议）
        JsonNode listResult = pair[0].sendRequest("tools/list", Map.of()).join();
        assertThat(listResult.path("tools")).hasSize(35);

        // ② tools/call（真协议）· 带会话信封
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "screenshot");
        params.put("arguments", Map.of());
        params.put(McpCallContext.PARAM_NAME, new McpCallContext(
                "sess-1", null, "gpt-4o", "openai").toWire());
        JsonNode callResult = pair[0].sendRequest("tools/call", params).join();

        assertThat(callResult.path("isError").asBoolean()).isFalse();
        JsonNode content = callResult.path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("type").asText()).isEqualTo("image");
        assertThat(content.get(0).path("mimeType").asText()).isEqualTo("image/jpeg");

        byte[] decoded = Base64.getDecoder().decode(content.get(0).path("data").asText());
        assertThat(decoded).hasSize(TINY_JPEG.length);
        assertThat(decoded[0] & 0xFF).isEqualTo(0xFF);
        assertThat(decoded[1] & 0xFF).isEqualTo(0xD8);
        assertThat(decoded[2] & 0xFF).isEqualTo(0xFF);
    }

    @Test
    @DisplayName("CallTool 结果只含 MCP wire 字段（content + isError）· 无 screenshot / telemetry piggyback")
    void callToolResult_hasOnlyWireFields() throws Exception {
        ComputerUseMcpServer server = newServer("win32", false);
        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "screenshot");
        params.put("arguments", Map.of());
        params.put(McpCallContext.PARAM_NAME,
                new McpCallContext("sess-1", null, null, null).toWire());
        JsonNode callResult = pair[0].sendRequest("tools/call", params).join();

        List<String> fields = new ArrayList<>();
        callResult.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("content", "isError");
        assertThat(callResult.has("screenshot")).isFalse();
        assertThat(callResult.has("telemetry")).isFalse();
        // 截图的<b>几何元数据</b>也不进 wire（仍由会话级 store 持有）
        assertThat(callResult.toString()).doesNotContain("displayWidth").doesNotContain("scaleFactor");
    }

    @Test
    @DisplayName("请求/响应原文可取证：JSON-RPC 帧 + tools/call 响应结构")
    void wireFrames_areInspectable() throws Exception {
        ComputerUseMcpServer server = newServer("win32", false);
        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);
        List<String> frames = new ArrayList<>();
        pair[1].setFrameInspector(frames::add);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "screenshot");
        params.put("arguments", Map.of());
        params.put(McpCallContext.PARAM_NAME,
                new McpCallContext("sess-1", null, null, null).toWire());
        JsonNode callResult = pair[0].sendRequest("tools/call", params).join();

        assertThat(frames).hasSize(1);
        String frame = frames.get(0);
        assertThat(frame).contains("\"jsonrpc\":\"2.0\"").contains("\"method\":\"tools/call\"")
                .contains("\"name\":\"screenshot\"").contains(McpCallContext.PARAM_NAME);
        // 响应是 JSON-RPC result（result.content[0].type = image）
        assertThat(callResult.path("content").get(0).path("type").asText()).isEqualTo("image");
    }

    // ─────────────────────────── ③ 工具名生成 ───────────────────────────

    @Test
    @DisplayName("模型可见工具名由 McpStringUtils.buildMcpToolName 产出（mcp__computer-use__<tool>）")
    void mcpToolNames_comeFromMcpStringUtils() {
        for (String bare : CCB_35) {
            assertThat(McpStringUtils.buildMcpToolName(ComputerUseMcpServer.SERVER_NAME, bare))
                    .as("工具名 %s", bare)
                    .isEqualTo("mcp__computer-use__" + bare);
        }
        // 底层 CU 工具的 TOOL_NAME 也必须由同一函数产出（⛔ 不自拼字符串）
        assertThat(ComputerUseScreenshotTool.TOOL_NAME)
                .isEqualTo(McpStringUtils.buildMcpToolName(ComputerUseMcpServer.SERVER_NAME, "screenshot"));
        assertThat(ComputerUseRequestAccessTool.TOOL_NAME)
                .isEqualTo(McpStringUtils.buildMcpToolName(ComputerUseMcpServer.SERVER_NAME, "request_access"));
    }

    @Test
    @DisplayName("经 MCP 池装配后：注册进 ToolRegistry 的工具名 = mcp__computer-use__<tool>（35 个）")
    void assembledPool_registersMcpNamedTools() {
        ComputerUseMcpServer server = newServer("win32", false);
        McpTransportFactory factory = new McpTransportFactory();
        ToolRegistry registry = new ToolRegistry();
        McpToolPool pool = new McpToolPool(factory, registry, new JsonRpcMcpClient());
        server.setMcpToolPool(pool);
        server.setTransportFactory(factory);

        List<Tool> assembled = server.assembleMcpTools();

        assertThat(assembled).hasSize(35);
        for (String bare : CCB_35) {
            assertThat(registry.has(McpStringUtils.buildMcpToolName(ComputerUseMcpServer.SERVER_NAME, bare)))
                    .as("ToolRegistry 应含 %s", bare)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("模型可见：装配后 CU 工具出现在 LLM tools 数组里（名字逐字 + parameters 是 CCB 原样 schema）")
    void assembledTools_areVisibleToLlmWithCcbSchema() {
        ComputerUseMcpServer server = newServer("win32", false);
        McpTransportFactory factory = new McpTransportFactory();
        ToolRegistry registry = new ToolRegistry();
        McpToolPool pool = new McpToolPool(factory, registry, new JsonRpcMcpClient());
        server.setMcpToolPool(pool);
        server.setTransportFactory(factory);
        server.assembleMcpTools();

        var array = registry.toOpenAiToolsArray();
        JsonNode found = null;
        for (int i = 0; i < array.size(); i++) {
            JsonNode fn = array.get(i).path("function");
            if ("mcp__computer-use__screenshot".equals(fn.path("name").asText())) {
                found = fn;
                break;
            }
        }
        assertThat(found).as("LLM tools 数组应含 mcp__computer-use__screenshot").isNotNull();
        // parameters 必须是 CCB 的原样 schema（McpServerTool.inputJSONSchema 优先）
        assertThat(canonical(found.path("parameters"))).isEqualTo(canonical(parse("""
                {"type":"object","properties":{"save_to_disk":{"type":"boolean",
                 "description":"Save the image to disk so it can be attached to a message for the user. Returns the saved path in the tool result. Only set this when you intend to share the image — screenshots you're just looking at don't need saving."}},
                 "required":[]}""")));
    }

    // ─────────────────────────── ④ 开关 ───────────────────────────

    @Test
    @DisplayName("开关关闭（disabled）⇒ tools/list 返回空列表 ⇒ 工具不出现")
    void disabledServer_yieldsEmptyToolList() throws Exception {
        List<Map<String, Object>> tools = listToolsOverWire(newServer("win32", true));
        assertThat(tools).isEmpty();
    }

    @Test
    @DisplayName("开关关闭 ⇒ 装配阶段不产出任何工具（不注册进 ToolRegistry）")
    void disabledServer_registersNothing() {
        ComputerUseMcpServer server = newServer("win32", true);
        McpTransportFactory factory = new McpTransportFactory();
        ToolRegistry registry = new ToolRegistry();
        McpToolPool pool = new McpToolPool(factory, registry, new JsonRpcMcpClient());
        server.setMcpToolPool(pool);
        server.setTransportFactory(factory);

        assertThat(server.assembleMcpTools()).isEmpty();
        assertThat(registry.has("mcp__computer-use__screenshot")).isFalse();
    }

    // ─────────────────────────── ⑤ 未绑定会话 ───────────────────────────

    @Test
    @DisplayName("未绑定会话（无 provider）⇒ stub handler 明确报错（逐字 CCB 原文），不静默")
    void noSessionContext_returnsExplicitStubError() throws Exception {
        Map<String, Tool> tools = new LinkedHashMap<>();
        tools.put("screenshot", new ComputerUseScreenshotTool(new FakeCapturer(), new ComputerUseGeometryStore()));
        ComputerUseMcpServer server = ComputerUseMcpServer.createComputerUseMcpServer(
                caps("win32"), ComputerUseCoordinateMode.PIXELS, null, tools, false, null);

        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "screenshot");
        params.put("arguments", Map.of());
        JsonNode result = pair[0].sendRequest("tools/call", params).join();

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo(
                "This computer-use server instance is not wired to a session. "
                        + "Per-session app permissions are not available on this code path.");
    }

    @Test
    @DisplayName("缺会话信封 ⇒ 同样走 stub 分支明确报错（不静默执行）")
    void missingCallContext_returnsExplicitStubError() throws Exception {
        ComputerUseMcpServer server = newServer("win32", false);
        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "screenshot");
        params.put("arguments", Map.of());
        // 无 _callContext
        JsonNode result = pair[0].sendRequest("tools/call", params).join();

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText())
                .isEqualTo(ComputerUseMcpServer.NOT_WIRED_MESSAGE);
    }

    // ─────────────────────────── ⑥ 权限语义 ───────────────────────────

    @Test
    @DisplayName("免弹窗：CU 工具的权限自决经委托回底层工具 ⇒ Allow（第 3 层原样透传，不弹窗）")
    void cuTools_areAllowedViaPermissionDelegate() {
        Tool screenshot = assembledTool("screenshot");
        PermissionResult result = screenshot.checkPermissions(
                parse("{\"save_to_disk\":false}"), null);
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("request_access 仍走 Ask：权限自决 = Passthrough（第 3 层转 Ask，审批唯一入口不被免弹窗规则带跑）")
    void requestAccess_stillAsks() {
        Tool requestAccess = assembledTool("request_access");
        PermissionResult result = requestAccess.checkPermissions(
                parse("{\"apps\":[\"notepad\"],\"reason\":\"test\"}"), null);
        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(result).isNotInstanceOf(PermissionResult.Allow.class);
    }

    /** 装配一次并取指定裸工具名对应的 MCP 包装工具。 */
    private static Tool assembledTool(String bareName) {
        ComputerUseMcpServer server = newServer("win32", false);
        McpTransportFactory factory = new McpTransportFactory();
        ToolRegistry registry = new ToolRegistry();
        McpToolPool pool = new McpToolPool(factory, registry, new JsonRpcMcpClient());
        server.setMcpToolPool(pool);
        server.setTransportFactory(factory);
        server.assembleMcpTools();
        Tool tool = registry.get(McpStringUtils.buildMcpToolName(ComputerUseMcpServer.SERVER_NAME, bareName))
                .orElseThrow(() -> new AssertionError("未装配工具: " + bareName));
        return tool;
    }

    // ─────────────────────────── ⑦ 会话信封 ───────────────────────────

    @Test
    @DisplayName("会话信封把 sessionId / 模型名带给底层工具（工具按会话分桶取授权位与几何）")
    void callContext_carriesSessionIdentity() {
        McpCallContext ctx = McpCallContext.fromWire(parse("""
                {"sessionId":"sess-9","agentId":null,
                 "effectiveModelName":"gpt-4o","effectiveProviderType":"openai"}"""));
        assertThat(ctx).isNotNull();
        assertThat(ctx.sessionId()).isEqualTo("sess-9");
        assertThat(ctx.effectiveModelName()).isEqualTo("gpt-4o");

        ComputerUseSessionContext sessionContext = ComputerUseSessionContext.of(ctx);
        ToolUseContext tuc = sessionContext.toolUseContext();
        assertThat(tuc.sessionId()).isEqualTo("sess-9");
        assertThat(tuc.effectiveModelName()).isEqualTo("gpt-4o");
        assertThat(tuc.effectiveProviderType()).isEqualTo("openai");
    }

    @Test
    @DisplayName("会话信封缺 sessionId ⇒ 解析为 null ⇒ 走 stub（明确报错，不静默）")
    void callContext_withoutSessionId_isNull() {
        assertThat(McpCallContext.fromWire(parse("{\"agentId\":null}"))).isNull();
        assertThat(McpCallContext.fromWire(null)).isNull();
    }

    @Test
    @DisplayName("initialize 握手返回 CCB 同款 serverInfo + capabilities")
    void initialize_handshakeMatchesCcb() throws Exception {
        ComputerUseMcpServer server = newServer("win32", false);
        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);
        JsonNode result = pair[0].sendRequest("initialize", Map.of()).join();
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("computer-use");
        assertThat(result.path("serverInfo").path("version").asText()).isEqualTo("0.1.3");
        assertThat(result.path("capabilities").path("tools").isObject()).isTrue();
    }

    @Test
    @DisplayName("未知工具 ⇒ 明确报错（isError=true），不静默")
    void unknownTool_returnsExplicitError() throws Exception {
        ComputerUseMcpServer server = newServer("win32", false);
        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "no_such_tool");
        params.put("arguments", Map.of());
        params.put(McpCallContext.PARAM_NAME,
                new McpCallContext("sess-1", null, null, null).toWire());
        JsonNode result = pair[0].sendRequest("tools/call", params).join();
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText()).contains("no_such_tool");
    }

    @Test
    @DisplayName("请求帧是标准 JSON-RPC（jsonrpc/id/method/params）· 证明真的走了 MCP 协议")
    void requestFrame_isStandardJsonRpc() throws Exception {
        ComputerUseMcpServer server = newServer("win32", false);
        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);
        List<String> frames = new ArrayList<>();
        pair[1].setFrameInspector(frames::add);

        pair[0].sendRequest("tools/list", Map.of()).join();

        assertThat(frames).hasSize(1);
        JsonNode frame = parse(frames.get(0));
        assertThat(frame.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(frame.path("method").asText()).isEqualTo("tools/list");
        assertThat(frame.has("id")).isTrue();
        assertThat(frame.path("params").isObject()).isTrue();
    }

    @Test
    @DisplayName("UTF-8 文本内容（中文）经 wire 往返不乱码")
    void textContent_survivesUtf8RoundTrip() throws Exception {
        ComputerUseMcpServer server = ComputerUseMcpServer.createComputerUseMcpServer(
                caps("win32"), ComputerUseCoordinateMode.PIXELS, null,
                Map.of("echo", new EchoTool()), false, ComputerUseSessionContext::of);
        InProcessMcpTransport[] pair = ComputerUseMcpServer.createLinkedPair(server);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "echo");
        params.put("arguments", Map.of("text", "截图已就绪 · 中文"));
        params.put(McpCallContext.PARAM_NAME,
                new McpCallContext("sess-1", null, null, null).toWire());
        JsonNode result = pair[0].sendRequest("tools/call", params).join();
        assertThat(result.path("content").get(0).path("text").asText()).isEqualTo("截图已就绪 · 中文");
    }

    /** 回声工具（验证文本 content 块映射）。 */
    private static final class EchoTool implements Tool {
        @Override
        public String name() {
            return McpStringUtils.buildMcpToolName(ComputerUseMcpServer.SERVER_NAME, "echo");
        }

        @Override
        public String description() {
            return "echo";
        }

        @Override
        public JsonNode inputSchema() {
            ObjectNode schema = JSON.createObjectNode();
            schema.put("type", "object");
            return schema;
        }

        @Override
        public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                com.nexusai.application.agent.tool.ToolUseBlock call) {
            String text = call.input().path("text").asText("");
            return com.nexusai.application.agent.tool.ToolResult.success(call.id(), text);
        }
    }

    @Test
    @DisplayName("工具实现表的键是裸工具名（与 tools/list 的 name 同源）")
    void toolImplementations_areKeyedByBareName() {
        ComputerUseMcpServer server = newServer("win32", false);
        assertThat(server.toolImplementations().keySet())
                .containsExactlyInAnyOrder("screenshot", "request_access");
        for (String bare : server.toolImplementations().keySet()) {
            assertThat(server.toolImplementations().get(bare).name())
                    .isEqualTo(McpStringUtils.buildMcpToolName(ComputerUseMcpServer.SERVER_NAME, bare));
        }
    }

    @Test
    @DisplayName("base64 载荷可解码且与源 JPEG 逐字节相同")
    void imagePayload_isLosslessBase64() {
        String encoded = Base64.getEncoder().encodeToString(TINY_JPEG);
        assertThat(new String(Base64.getDecoder().decode(encoded), StandardCharsets.ISO_8859_1))
                .isEqualTo(new String(TINY_JPEG, StandardCharsets.ISO_8859_1));
    }
}
