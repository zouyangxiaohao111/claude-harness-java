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
 * {@code mcp__computer-use__virtual_mouse} · 窗口定向鼠标工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:753-799}）</h2>
 * <pre>
 * name: 'virtual_mouse',
 * description: 'Control a virtual mouse on the bound window via SendMessageW — independent of the physical mouse. ' +
 *   "The user's real cursor stays free. All operations target the bound HWND only.",
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     action: { type: 'string', enum: [click,double_click,right_click,move,drag,down,up], description: '...' },
 *     coordinate: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2, description: '(x, y) client-area coordinate on the bound window.' },
 *     start_coordinate: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2, description: '(x, y) start point for drag. If omitted, drags from current virtual cursor position.' },
 *   },
 *   required: ['action', 'coordinate'],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code executorCrossPlatform.ts:831-900}）</h2>
 * <ul>
 *   <li>{@code click} / {@code double_click} / {@code right_click} ⇒ {@code SendMessage(WM_LBUTTON/RBUTTON)}；</li>
 *   <li>{@code move} ⇒ {@code SendMessage(WM_MOUSEMOVE)}；</li>
 *   <li>{@code drag} ⇒ 按下 → 移动 → 抬起；</li>
 *   <li>{@code down} / {@code up} ⇒ 单边按下 / 抬起。</li>
 * </ul>
 *
 * <p><b>⚠️ 坐标不经 scaleCoord</b>：本工具的 {@code coordinate} 是<b>绑定窗口的客户区坐标</b>
 * （schema 明写 “client-area coordinate on the bound window”），<b>不是</b>模型截图上的像素
 * ⇒ 走 {@code scaleCoord} 会得到完全错误的落点（CCB 同样如此，见
 * {@code toolCalls.ts:2897-3077} 整段不经 {@code scaleCoord}）。
 */
public final class ComputerUseVirtualMouseTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseVirtualMouseTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "virtual_mouse");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:754-756}。 */
    private static final String DESCRIPTION =
            "Control a virtual mouse on the bound window via SendMessageW — independent of the physical mouse. "
                    + "The user's real cursor stays free. All operations target the bound HWND only.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:757-798}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["click", "double_click", "right_click", "move", "drag", "down", "up"],
                  "description": "click: left-click at coordinate. double_click: double left-click. right_click: right-click. move: move virtual cursor (visual only, no click). drag: press at start, move to end, release. Requires coordinate (end) and start_coordinate. down: press left button at coordinate (hold). up: release left button at coordinate."
                },
                "coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 2,
                  "maxItems": 2,
                  "description": "(x, y) client-area coordinate on the bound window."
                },
                "start_coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 2,
                  "maxItems": 2,
                  "description": "(x, y) start point for drag. If omitted, drags from current virtual cursor position."
                }
              },
              "required": ["action", "coordinate"]
            }
            """;

    /** 合法动作 · CCB {@code toolCalls.ts:2913-2921}。 */
    private static final List<String> VALID_ACTIONS = List.of(
            "click", "double_click", "right_click", "move", "drag", "down", "up");

    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;
    private final ComputerUseSleeper sleeper;

    /**
     * @param windowOps    窗口操作原语
     * @param bindingStore 绑定窗口存储
     * @param sleeper      休眠器（可注入假时钟）
     */
    public ComputerUseVirtualMouseTool(ComputerUseWindowOps windowOps,
                                        ComputerUseWindowBindingStore bindingStore,
                                        ComputerUseSleeper sleeper) {
        this.windowOps = windowOps;
        this.bindingStore = bindingStore;
        this.sleeper = sleeper == null ? ComputerUseSleeper.REAL : sleeper;
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
                    "[computer-use] virtual_mouse inputSchema 解析失败: " + e.getMessage(), e);
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
                new PermissionDecisionReason.Other("computer-use virtual_mouse is gated by the "
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
        JsonNode actionNode = input.path("action");
        if (!actionNode.isTextual()) {
            return ToolResult.error(call.id(), "\"action\" must be a string.");
        }
        String action = actionNode.asText();

        JsonNode coordNode = input.path("coordinate");
        if (!coordNode.isArray() || coordNode.size() < 2
                || !coordNode.get(0).isNumber() || !coordNode.get(1).isNumber()) {
            // CCB toolCalls.ts:2909-2911
            return ToolResult.error(call.id(), "coordinate [x, y] is required.");
        }
        int x = coordNode.get(0).asInt();
        int y = coordNode.get(1).asInt();

        if (!VALID_ACTIONS.contains(action)) {
            // CCB toolCalls.ts:2922-2925
            return ToolResult.error(call.id(),
                    "Invalid action \"" + action + "\". Valid: " + String.join(", ", VALID_ACTIONS));
        }

        Integer startX = null;
        Integer startY = null;
        JsonNode startNode = input.path("start_coordinate");
        if (startNode.isArray() && startNode.size() >= 2
                && startNode.get(0).isNumber() && startNode.get(1).isNumber()) {
            startX = startNode.get(0).asInt();
            startY = startNode.get(1).asInt();
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;
        if (binding == null) {
            // CCB toolCalls.ts:2938-2940
            return ToolResult.error(call.id(), "No window is currently bound.");
        }
        long hwnd = binding.hwnd();

        try {
            switch (action) {
                case "click":
                    windowOps.sendClick(hwnd, x, y, false);
                    break;
                case "double_click":
                    // CCB executorCrossPlatform.ts:862-866 —— 两次点击，中间 50ms
                    windowOps.sendClick(hwnd, x, y, false);
                    sleeper.sleep(50L);
                    windowOps.sendClick(hwnd, x, y, false);
                    break;
                case "right_click":
                    windowOps.sendClick(hwnd, x, y, true);
                    break;
                case "move":
                    windowOps.sendMouseMove(hwnd, x, y);
                    break;
                case "drag": {
                    // CCB executorCrossPlatform.ts:875-885
                    int sx = startX == null ? x : startX;
                    int sy = startY == null ? y : startY;
                    windowOps.sendMouseDown(hwnd, sx, sy);
                    try {
                        sleeper.sleep(16L);
                        windowOps.sendMouseMove(hwnd, x, y);
                        sleeper.sleep(16L);
                    } finally {
                        // 异常路径也要抬起（不卡住左键）
                        windowOps.sendMouseUp(hwnd, x, y);
                    }
                    break;
                }
                case "down":
                    windowOps.sendMouseDown(hwnd, x, y);
                    break;
                case "up":
                    windowOps.sendMouseUp(hwnd, x, y);
                    break;
                default:
                    return ToolResult.error(call.id(),
                            "Invalid action \"" + action + "\". Valid: " + String.join(", ", VALID_ACTIONS));
            }

            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: action={} client=({},{}) hwnd={}",
                        TOOL_NAME, action, x, y, hwnd);
            }
            return ToolResult.success(call.id(), describe(action, x, y, startX, startY));
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: action={} hwnd={} err={}",
                    TOOL_NAME, action, hwnd, e.getMessage(), e);
            return ToolResult.error(call.id(), "虚拟鼠标操作失败: " + e.getMessage());
        }
    }

    /** 成功文案 · CCB {@code toolCalls.ts:2941-2949}。 */
    private static String describe(String action, int x, int y, Integer startX, Integer startY) {
        switch (action) {
            case "click":
                return "Click at (" + x + "," + y + ")";
            case "double_click":
                return "Double-click at (" + x + "," + y + ")";
            case "right_click":
                return "Right-click at (" + x + "," + y + ")";
            case "move":
                return "Moved to (" + x + "," + y + ")";
            case "drag":
                return "Dragged " + (startX == null ? "current" : "(" + startX + "," + startY + ")")
                        + " → (" + x + "," + y + ")";
            case "down":
                return "Button down at (" + x + "," + y + ")";
            case "up":
                return "Button up at (" + x + "," + y + ")";
            default:
                return action;
        }
    }
}
