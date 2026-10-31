package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpStringUtils;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * {@code mcp__computer-use__mouse_wheel} · 窗口定向滚轮工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:801-834}）</h2>
 * <pre>
 * name: 'mouse_wheel',
 * description: 'Scroll inside the bound window using mouse wheel (WM_MOUSEWHEEL / WM_MOUSEHWHEEL). ' +
 *   "Unlike the generic 'scroll' tool which uses WM_VSCROLL (only works on scrollbar controls), " +
 *   'mouse_wheel simulates the physical mouse wheel and works on Excel spreadsheets, web pages, ' +
 *   'code editors, PDF viewers, and any modern UI. ' +
 *   'Specify the click point within the window where the scroll should occur — ' +
 *   'this determines which panel/pane/element receives the scroll.',
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     coordinate: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2, description: '(x, y) client-area coordinate where the scroll should occur. Determines which element receives the scroll.' },
 *     delta: { type: 'integer', description: "Scroll amount in 'clicks'. Positive = scroll up, negative = scroll down. Each click = 3 lines typically. Use -3 to -5 for page-like scrolling." },
 *     direction: { type: 'string', enum: [vertical,horizontal], description: "Scroll direction. Default: 'vertical'. Use 'horizontal' for side-scrolling (e.g. wide Excel sheets, timeline views)." },
 *   },
 *   required: ['coordinate', 'delta'],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code executorCrossPlatform.ts:903-939} + {@code windowMessage.ts:613-653}）</h2>
 * <ul>
 *   <li>{@code delta} 是<b>格数</b>（正 = 上 / 右，负 = 下 / 左），投递时乘 {@code WHEEL_DELTA=120}；</li>
 *   <li>{@code WM_MOUSEWHEEL} / {@code WM_MOUSEHWHEEL} 的 lParam 必须是<b>屏幕</b>坐标
 *       （{@code ClientToScreen} 换算）；</li>
 *   <li>{@code coordinate} 是<b>客户区</b>坐标（不经 {@code scaleCoord}）。</li>
 * </ul>
 */
public final class ComputerUseMouseWheelTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseMouseWheelTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "mouse_wheel");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:802-808}。 */
    private static final String DESCRIPTION =
            "Scroll inside the bound window using mouse wheel (WM_MOUSEWHEEL / WM_MOUSEHWHEEL). "
                    + "Unlike the generic 'scroll' tool which uses WM_VSCROLL (only works on scrollbar controls), "
                    + "mouse_wheel simulates the physical mouse wheel and works on Excel spreadsheets, web pages, "
                    + "code editors, PDF viewers, and any modern UI. "
                    + "Specify the click point within the window where the scroll should occur — "
                    + "this determines which panel/pane/element receives the scroll.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:809-833}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 2,
                  "maxItems": 2,
                  "description": "(x, y) client-area coordinate where the scroll should occur. Determines which element receives the scroll."
                },
                "delta": {
                  "type": "integer",
                  "description": "Scroll amount in 'clicks'. Positive = scroll up, negative = scroll down. Each click = 3 lines typically. Use -3 to -5 for page-like scrolling."
                },
                "direction": {
                  "type": "string",
                  "enum": ["vertical", "horizontal"],
                  "description": "Scroll direction. Default: 'vertical'. Use 'horizontal' for side-scrolling (e.g. wide Excel sheets, timeline views)."
                }
              },
              "required": ["coordinate", "delta"]
            }
            """;

    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;

    /**
     * @param windowOps    窗口操作原语
     * @param bindingStore 绑定窗口存储
     */
    public ComputerUseMouseWheelTool(ComputerUseWindowOps windowOps,
                                     ComputerUseWindowBindingStore bindingStore) {
        this.windowOps = windowOps;
        this.bindingStore = bindingStore;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonNode inputSchema() {
        try {
            return JSON.readTree(INPUT_SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[computer-use] mouse_wheel inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 会改系统状态 ⇒ 非只读、不可并发。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use mouse_wheel is gated by the "
                        + "session allowlist granted via request_access"),
                null,
                false,
                null,
                List.of());
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        JsonNode coordNode = input.path("coordinate");
        if (!coordNode.isArray() || coordNode.size() < 2) {
            // CCB toolCalls.ts:3051-3053
            return ToolResult.error(call.id(), "coordinate must be [x, y] array.");
        }
        int x = coordNode.get(0).isNumber() ? coordNode.get(0).asInt() : 0;
        int y = coordNode.get(1).isNumber() ? coordNode.get(1).asInt() : 0;

        JsonNode deltaNode = input.path("delta");
        if (!deltaNode.isNumber()) {
            // CCB toolCalls.ts:3054-3057
            return ToolResult.error(call.id(), "delta is required (positive=up, negative=down).");
        }
        int delta = deltaNode.asInt();
        boolean horizontal = "horizontal".equals(input.path("direction").asText("vertical"));

        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;
        if (binding == null) {
            // CCB toolCalls.ts:3068-3071
            return ToolResult.error(call.id(),
                    "No window is currently bound. Use open_application or bind_window first.");
        }

        try {
            windowOps.sendMouseWheel(binding.hwnd(), x, y, delta, horizontal);
            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: client=({},{}) delta={} 方向={}",
                        TOOL_NAME, x, y, delta, horizontal ? "horizontal" : "vertical");
            }
            // CCB toolCalls.ts:3074-3076
            return ToolResult.success(call.id(),
                    "Mouse wheel: " + (horizontal ? "horizontal" : "vertical")
                            + " scroll " + (delta > 0 ? "up" : "down")
                            + " " + Math.abs(delta) + " click(s) at (" + x + "," + y + ").");
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: err={}", TOOL_NAME, e.getMessage(), e);
            return ToolResult.error(call.id(), "滚轮操作失败: " + e.getMessage());
        }
    }
}
