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
 * {@code mcp__computer-use__left_click_drag} · 按下 → 移动 → 抬起（拖拽）工具
 * （对齐 CCB 2.8.4 {@code packages/@ant/computer-use-mcp/src/tools.ts:370-387}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:371-386}）</h2>
 * <pre>
 * name: 'left_click_drag',
 * description: `Press, move to target, and release. ${FRONTMOST_GATE_DESC}`,
 * inputSchema: {
 *   type: 'object',
 *   properties: {
 *     coordinate: { ...coordinateTuple, description: `(x, y) end point: ${coord.x}` },
 *     start_coordinate: { ...coordinateTuple, description: `(x, y) start point. If omitted, drags from the current cursor position. ${coord.x}` },
 *   },
 *   required: ['coordinate'],
 * }
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:2687-2778} 的 {@code handleDrag}）</h2>
 * <ul>
 *   <li><b>{@code coordinate} = 终点</b>（必填）；<b>{@code start_coordinate} = 起点</b>（可选，
 *       省略 ⇒ <b>从当前光标位置拖</b>，CCB {@code toolCalls.ts:2712-2719}）；</li>
 *   <li><b>防呆清场</b>：CCB {@code toolCalls.ts:2698-2702} —— 若之前有
 *       {@code left_mouse_down} 悬着未放，<b>先释放</b>再拖（否则旗标与 OS 状态失同步）；</li>
 *   <li>档位门控：{@code mouse}（CCB {@code toolCalls.ts:2721}）；</li>
 *   <li>命中测试门（hit-test）：CCB {@code toolCalls.ts:2757-2774}（起点 {@code mouse} /
 *       终点 {@code mouse_full}）。
 *       ⛔ <b>本仓不做</b>：CCB 的 {@code appUnderPoint} 在 Windows <b>恒返回 null</b>
 *       （{@code src/utils/computerUse/executorCrossPlatform.ts:524-529}）
 *       ⇒ CCB 自己在这条路径上也是死路径。</li>
 *   <li>成功文案：CCB {@code toolCalls.ts:2777} {@code 'Dragged.'}（逐字）。</li>
 * </ul>
 *
 * <h2>实现路线</h2>
 * <p>本仓复用 {@link ComputerUseMouseAnimator#drag(int, int, int, int)}（对齐 CCB
 * {@code src/utils/computerUse/executor.ts:643-659} 的 {@code drag}）：
 * <b>终点按住时必释放</b>（{@code finally} 块）—— 拖拽中途抛异常也不会把用户的左键卡住。
 */
public final class ComputerUseLeftClickDragTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseLeftClickDragTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "left_click_drag");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:372}。 */
    private static final String DESCRIPTION =
            "Press, move to target, and release. "
                    + "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing.";

    /**
     * 输入 schema 模板 · 逐字对齐 CCB {@code tools.ts:375-385}。
     *
     * <p>⚠️ CCB 的两个坐标描述<b>刻意不同</b>：
     * {@code coordinate} 是「终点」、{@code start_coordinate} 是「起点（省略则从当前光标拖）」。
     */
    private static final String INPUT_SCHEMA_TEMPLATE = """
            {
              "type": "object",
              "properties": {
                "coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 2,
                  "maxItems": 2,
                  "description": "(x, y) end point: %s"
                },
                "start_coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 2,
                  "maxItems": 2,
                  "description": "(x, y) start point. If omitted, drags from the current cursor position. %s"
                }
              },
              "required": ["coordinate"]
            }
            """;

    private final ComputerUseMouseAnimator animator;
    private final ComputerUseGeometryStore geometryStore;
    private final ComputerUseCoordinateMode coordinateMode;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;
    private final ComputerUseMouseState mouseState;

    /**
     * @param animator               鼠标缓动器（提供 press → move → release 的拖拽）
     * @param geometryStore          截图几何快照存储
     * @param coordinateMode         坐标模式（null ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     * @param grantStore             会话授权位读写（null ⇒ 不做门控，单测路径）
     * @param foregroundAppResolver  前台应用解析（null ⇒ 门控不生效）
     * @param mouseState             左键按住状态（null ⇒ 不做防呆清场）
     */
    public ComputerUseLeftClickDragTool(ComputerUseMouseAnimator animator,
                                        ComputerUseGeometryStore geometryStore,
                                        ComputerUseCoordinateMode coordinateMode,
                                        ComputerUseGrantStore grantStore,
                                        ComputerUseForegroundAppResolver foregroundAppResolver,
                                        ComputerUseMouseState mouseState) {
        if (animator == null || geometryStore == null) {
            throw new IllegalArgumentException("ComputerUseLeftClickDragTool 依赖不能为空");
        }
        this.animator = animator;
        this.geometryStore = geometryStore;
        this.coordinateMode = coordinateMode == null ? ComputerUseCoordinateMode.PIXELS : coordinateMode;
        this.grantStore = grantStore;
        this.foregroundAppResolver = foregroundAppResolver;
        this.mouseState = mouseState;
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
            String coordX = ComputerUseCoordinateDescriptions.x(coordinateMode);
            return JSON.readTree(String.format(INPUT_SCHEMA_TEMPLATE, coordX, coordX));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[computer-use] left_click_drag inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 输入动作会改系统状态 ⇒ 非只读、不可并发。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /**
     * 输入动作 · <b>权限自决 Allow（免弹窗）</b> · 对齐 CCB
     * {@code src/utils/computerUse/setup.ts:25-32}。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use input action is gated by the "
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

    /**
     * 执行拖拽。
     *
     * <p>流程：解析终点（必填）/ 起点（可选）→ 防呆清场 → 档位门控 → 坐标换算 → 拖拽。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String endError = validateCoordinate(input.path("coordinate"), "coordinate");
        if (endError != null) {
            return ToolResult.error(call.id(), endError);
        }
        JsonNode startNode = input.path("start_coordinate");
        boolean hasStart = !startNode.isMissingNode() && !startNode.isNull();
        if (hasStart) {
            String startError = validateCoordinate(startNode, "start_coordinate");
            if (startError != null) {
                return ToolResult.error(call.id(), startError);
            }
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 防呆清场 · CCB toolCalls.ts:2698-2702（悬着的左键先放掉）
        if (mouseState != null && mouseState.isHeld(sessionId)) {
            try {
                animator.releaseHeldButton();
            } catch (Exception e) {
                log.warn("[computer-use] {} 拖拽前释放悬空左键失败: session={} err={}",
                        TOOL_NAME, sessionId, e.getMessage());
            }
            mouseState.markReleased(sessionId);
        }

        // 档位门控 · CCB toolCalls.ts:2721（'mouse'）
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.MOUSE);
        if (rejection != null) {
            return ToolResult.error(call.id(), rejection);
        }

        ComputerUseScreenshotGeometry lastScreenshot = geometryStore.get(sessionId);
        int[] end;
        try {
            end = ComputerUseCoordinateScaler.scaleCoord(
                    input.path("coordinate").get(0).asDouble(),
                    input.path("coordinate").get(1).asDouble(),
                    coordinateMode, lastScreenshot);
        } catch (ComputerUseGeometryMissingException e) {
            log.warn("[computer-use] {} 拒绝执行: {}", TOOL_NAME, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }

        int[] start = null;
        if (hasStart) {
            try {
                start = ComputerUseCoordinateScaler.scaleCoord(
                        startNode.get(0).asDouble(),
                        startNode.get(1).asDouble(),
                        coordinateMode, lastScreenshot);
            } catch (ComputerUseGeometryMissingException e) {
                return ToolResult.error(call.id(), e.getMessage());
            }
        }

        try {
            if (start != null) {
                animator.drag(start[0], start[1], end[0], end[1]);
            } else {
                // 省略起点 ⇒ 从当前光标位置拖（CCB toolCalls.ts:2719）
                animator.dragFromCurrent(end[0], end[1]);
            }
        } catch (Exception e) {
            log.error("[computer-use] {} 拖拽失败: 起点={} 终点=({},{}) · {}",
                    TOOL_NAME, start == null ? "当前光标" : "(" + start[0] + "," + start[1] + ")",
                    end[0], end[1], e.getMessage(), e);
            return ToolResult.error(call.id(), "拖拽失败: " + e.getMessage());
        }

        if (mouseState != null) {
            mouseState.markReleased(sessionId);
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: 起点={} 终点=({},{})",
                    TOOL_NAME, start == null ? "当前光标" : "(" + start[0] + "," + start[1] + ")",
                    end[0], end[1]);
        }
        // CCB toolCalls.ts:2777 成功文案逐字对齐："Dragged."
        return ToolResult.success(call.id(), "Dragged.");
    }

    /**
     * 校验一个坐标元组（CCB {@code extractCoordinate} 的同款校验）。
     *
     * @param node     坐标节点
     * @param paramName 参数名（错误文案里用）
     * @return null = 合法；非 null = 错误文案
     */
    private static String validateCoordinate(JsonNode node, String paramName) {
        if (node.isMissingNode() || node.isNull()) {
            return "\"" + paramName + "\" is required";
        }
        if (!node.isArray() || node.size() != 2) {
            return "\"" + paramName + "\" must be an array of length 2";
        }
        JsonNode x = node.get(0);
        JsonNode y = node.get(1);
        if (!x.isNumber() || !y.isNumber()
                || !Double.isFinite(x.asDouble()) || !Double.isFinite(y.asDouble())
                || x.asDouble() < 0 || y.asDouble() < 0) {
            return "\"" + paramName + "\" must be a tuple of non-negative numbers";
        }
        return null;
    }
}
