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
 * {@code mcp__computer-use__scroll} · 鼠标滚轮滚动工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:347-368}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:348-367}）</h2>
 * <pre>
 * name: 'scroll',
 * description: `Scroll at the given coordinates. ${FRONTMOST_GATE_DESC}`,
 * inputSchema: {
 *   type: 'object',
 *   properties: {
 *     coordinate: coordinateTuple,
 *     scroll_direction: { type: 'string', enum: ['up', 'down', 'left', 'right'], description: 'Direction to scroll.' },
 *     scroll_amount: { type: 'integer', minimum: 0, maximum: 100, description: 'Number of scroll ticks.' },
 *   },
 *   required: ['coordinate', 'scroll_direction', 'scroll_amount'],
 * }
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:2621-2685}）</h2>
 * <ol>
 *   <li>解析 {@code coordinate}（{@code extractCoordinate}）—— <b>必填</b>；</li>
 *   <li>校验 {@code scroll_direction ∈ {up, down, left, right}}
 *       （CCB {@code toolCalls.ts:2634-2638}）；</li>
 *   <li>校验 {@code scroll_amount} 是整数且 {@code 0 ≤ n ≤ 100}
 *       （CCB {@code toolCalls.ts:2640-2646}）；</li>
 *   <li>方向 → 位移映射（CCB {@code toolCalls.ts:2647-2649}）：
 *       {@code up → dy=-amount}、{@code down → dy=+amount}、
 *       {@code left → dx=-amount}、{@code right → dx=+amount}；</li>
 *   <li>档位门控：CCB {@code toolCalls.ts:2651} 传 {@code 'mouse'}
 *       （左键按住时升为 {@code mouse_full}，CCB {@code toolCalls.ts:2672-2679}）;</li>
 *   <li>坐标换算（CCB {@code toolCalls.ts:2657-2664} 的 {@code scaleCoord}）
 *       ⇒ 本仓走 {@link ComputerUseCoordinateScaler}（截图时刻快照）。</li>
 * </ol>
 *
 * <h2>⚠️ Windows 侧<b>忽略坐标</b>（CCB 逐字对齐，如实标注）</h2>
 * <p>CCB 的跨平台执行器
 * {@code src/utils/computerUse/executorCrossPlatform.ts:501-509} 的签名是
 * {@code async scroll(_x: number, _y: number, dx: number, dy: number)} ——
 * <b>下划线前缀 = 形参未被使用</b> ⇒ Windows 的滚轮事件落在<b>当前光标</b>所在窗口，
 * <b>不会</b>先移动到请求坐标（macOS 的执行器 {@code executor.ts:664-666} 才会
 * {@code moveAndSettle(x, y)}）。
 * <br>⇒ 本仓<b>照此实现</b>：<b>坐标仍必填、仍走换算校验</b>（模型契约不变），
 * 但<b>执行时不移动光标</b>，只发滚轮事件。
 * <br>⇒ 副作用：若模型给的坐标与当前光标位置不一致，滚动会作用在<b>当前光标</b>的窗口上。
 * 这与 CCB-Windows <b>完全一致</b>，已在交付报告里如实标注。
 *
 * <h2>水平滚动的实现缺口（如实标注）</h2>
 * <p>CCB 的水平滚动走 {@code MOUSEEVENTF_HWHEEL}（CCB {@code platforms/win32.ts:369-375}）。
 * 本仓的 {@link ComputerUseMouseActions#scroll} 契约里 {@code dx ≠ 0} 表示水平滚动，
 * 由 Win32 实现层负责投递 {@code MOUSEEVENTF_HWHEEL}。
 */
public final class ComputerUseScrollTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseScrollTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "scroll");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:349}。 */
    private static final String DESCRIPTION =
            "Scroll at the given coordinates. The frontmost application must be in the session allowlist "
                    + "at the time of this call, or this tool returns an error and does nothing.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:350-366}。 */
    private static final String INPUT_SCHEMA_TEMPLATE = """
            {
              "type": "object",
              "properties": {
                "coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 2,
                  "maxItems": 2,
                  "description": "%s"
                },
                "scroll_direction": {
                  "type": "string",
                  "enum": ["up", "down", "left", "right"],
                  "description": "Direction to scroll."
                },
                "scroll_amount": {
                  "type": "integer",
                  "minimum": 0,
                  "maximum": 100,
                  "description": "Number of scroll ticks."
                }
              },
              "required": ["coordinate", "scroll_direction", "scroll_amount"]
            }
            """;

    /** 滚动档位上限 · CCB {@code toolCalls.ts:2645-2646}（{@code scroll_amount exceeds maximum of 100}）。 */
    private static final int MAX_SCROLL_AMOUNT = 100;

    private final ComputerUseMouseActions mouse;
    private final ComputerUseGeometryStore geometryStore;
    private final ComputerUseCoordinateMode coordinateMode;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;
    private final ComputerUseMouseState mouseState;

    /**
     * @param mouse                 鼠标动作实现
     * @param geometryStore         截图几何快照存储
     * @param coordinateMode        坐标模式（null ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     * @param grantStore            会话授权位读写（null ⇒ 不做门控，单测路径）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     * @param mouseState            左键按住状态（null ⇒ 不做「按住时升级档位」的判定）
     */
    public ComputerUseScrollTool(ComputerUseMouseActions mouse,
                                 ComputerUseGeometryStore geometryStore,
                                 ComputerUseCoordinateMode coordinateMode,
                                 ComputerUseGrantStore grantStore,
                                 ComputerUseForegroundAppResolver foregroundAppResolver,
                                 ComputerUseMouseState mouseState) {
        if (mouse == null || geometryStore == null) {
            throw new IllegalArgumentException("ComputerUseScrollTool 依赖不能为空");
        }
        this.mouse = mouse;
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
            return JSON.readTree(String.format(INPUT_SCHEMA_TEMPLATE,
                    ComputerUseCoordinateDescriptions.tupleDescription(coordinateMode)));
        } catch (Exception e) {
            throw new IllegalStateException("[computer-use] scroll inputSchema 解析失败: " + e.getMessage(), e);
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
     * 执行滚动。
     *
     * <p>流程：解析坐标 → 校验方向 → 校验格数 → 档位门控 → 坐标换算 → 发滚轮事件。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        JsonNode coordinate = input.path("coordinate");
        if (!coordinate.isArray() || coordinate.size() != 2
                || !coordinate.get(0).isNumber() || !coordinate.get(1).isNumber()) {
            return ToolResult.error(call.id(),
                    "\"coordinate\" must be an array of length 2 with numeric x and y");
        }
        double rawX = coordinate.get(0).asDouble();
        double rawY = coordinate.get(1).asDouble();
        if (!Double.isFinite(rawX) || !Double.isFinite(rawY) || rawX < 0 || rawY < 0) {
            return ToolResult.error(call.id(),
                    "\"coordinate\" must contain non-negative finite numbers");
        }

        // 方向校验 · CCB toolCalls.ts:2634-2638
        String direction = input.path("scroll_direction").asText("");
        if (!"up".equals(direction) && !"down".equals(direction)
                && !"left".equals(direction) && !"right".equals(direction)) {
            return ToolResult.error(call.id(),
                    "\"scroll_direction\" must be one of: up, down, left, right");
        }

        // 格数校验 · CCB toolCalls.ts:2640-2646
        JsonNode amountNode = input.path("scroll_amount");
        if (!amountNode.isIntegralNumber()) {
            return ToolResult.error(call.id(), "\"scroll_amount\" must be an integer");
        }
        long amount = amountNode.asLong();
        if (amount < 0) {
            return ToolResult.error(call.id(), "\"scroll_amount\" must be non-negative");
        }
        if (amount > MAX_SCROLL_AMOUNT) {
            return ToolResult.error(call.id(),
                    "\"scroll_amount\" must not exceed " + MAX_SCROLL_AMOUNT);
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 档位门控 · CCB toolCalls.ts:2651（'mouse'）；左键按住时升为 mouse_full
        // （CCB toolCalls.ts:2672-2679）。
        boolean buttonHeld = mouseState != null && mouseState.isHeld(sessionId);
        ComputerUseActionKind actionKind = buttonHeld
                ? ComputerUseActionKind.MOUSE_FULL
                : ComputerUseActionKind.MOUSE;
        String gateRejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, actionKind);
        if (gateRejection != null) {
            return ToolResult.error(call.id(), gateRejection);
        }

        // 坐标换算（必填校验 + 快照换算）· 与既有 click 工具同源
        ComputerUseScreenshotGeometry lastScreenshot = geometryStore.get(sessionId);
        int[] point;
        try {
            point = ComputerUseCoordinateScaler.scaleCoord(rawX, rawY, coordinateMode, lastScreenshot);
        } catch (ComputerUseGeometryMissingException e) {
            log.warn("[computer-use] {} 拒绝执行: {}", TOOL_NAME, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }

        // 方向 → 位移 · CCB toolCalls.ts:2647-2649
        int dx = 0;
        int dy = 0;
        switch (direction) {
            case "up" -> dy = -(int) amount;
            case "down" -> dy = (int) amount;
            case "left" -> dx = -(int) amount;
            case "right" -> dx = (int) amount;
            default -> {
                // 不可能到达（上面已校验）· fail loud
                return ToolResult.error(call.id(), "\"scroll_direction\" must be one of: up, down, left, right");
            }
        }

        try {
            // ⚠️ Windows 侧忽略坐标（CCB executorCrossPlatform.ts:501-509）—— 见类 javadoc
            mouse.scroll(dx, dy);
        } catch (Exception e) {
            log.error("[computer-use] {} 滚动失败: 方向={} 格数={} · {}", TOOL_NAME, direction, amount, e.getMessage(), e);
            return ToolResult.error(call.id(), "滚动失败: " + e.getMessage());
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: 方向={} 格数={} · dx={} dy={} · 请求图坐标 ({},{}) → 换算屏幕坐标 ({},{})"
                            + "（Windows 侧滚轮作用于当前光标，坐标仅用于校验与换算）",
                    TOOL_NAME, direction, amount, dx, dy, rawX, rawY, point[0], point[1]);
        }

        // CCB toolCalls.ts:2684 成功文案逐字对齐："Scrolled."
        return ToolResult.success(call.id(), "Scrolled.");
    }
}
