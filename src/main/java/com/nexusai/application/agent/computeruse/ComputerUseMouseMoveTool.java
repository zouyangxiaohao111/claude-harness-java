package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@code mcp__computer-use__mouse_move} · 移动鼠标（不点击）工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:389-399}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:390-398}）</h2>
 * <pre>
 * name: 'mouse_move',
 * description: `Move the mouse cursor without clicking. Useful for triggering hover states. ${FRONTMOST_GATE_DESC}`,
 * inputSchema: {
 *   type: 'object',
 *   properties: { coordinate: coordinateTuple },
 *   required: ['coordinate'],
 * }
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:2780-2834}）</h2>
 * <ul>
 *   <li><b>档位</b>：CCB {@code toolCalls.ts:2795}
 *       {@code const actionKind = mouseButtonHeld ? 'mouse' : 'mouse_position'}
 *       ⇒ <b>未按住左键 ⇒ {@code mouse_position}</b>（<b>任何档位都放行</b>，
 *       见 {@link ComputerUseTierGate}：纯光标定位不与应用交互）；
 *       <b>按住左键 ⇒ {@code mouse}</b>（此时移动 = 拖拽交互 ⇒ 需 click/full 档）。</li>
 *   <li><b>坐标换算</b>：CCB {@code toolCalls.ts:2807-2814} 的 {@code scaleCoord}
 *       ⇒ 本仓走 {@link ComputerUseCoordinateScaler}（截图时刻快照）。</li>
 *   <li><b>成功文案</b>：CCB {@code toolCalls.ts:2833} {@code 'Moved.'}（逐字）。</li>
 * </ul>
 */
public final class ComputerUseMouseMoveTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseMouseMoveTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__mouse_move";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:391}。 */
    private static final String DESCRIPTION =
            "Move the mouse cursor without clicking. Useful for triggering hover states. "
                    + "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:392-397}。 */
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
                }
              },
              "required": ["coordinate"]
            }
            """;

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
     * @param mouseState            左键按住状态（null ⇒ 恒按「未按住」判定）
     */
    public ComputerUseMouseMoveTool(ComputerUseMouseActions mouse,
                                    ComputerUseGeometryStore geometryStore,
                                    ComputerUseCoordinateMode coordinateMode,
                                    ComputerUseGrantStore grantStore,
                                    ComputerUseForegroundAppResolver foregroundAppResolver,
                                    ComputerUseMouseState mouseState) {
        if (mouse == null || geometryStore == null) {
            throw new IllegalArgumentException("ComputerUseMouseMoveTool 依赖不能为空");
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
            throw new IllegalStateException("[computer-use] mouse_move inputSchema 解析失败: " + e.getMessage(), e);
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
     * 执行鼠标移动。
     *
     * <p>流程：解析坐标 → 档位门控 → 坐标换算 → 移动光标。
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

        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 档位门控 · CCB toolCalls.ts:2795（按住左键 ⇒ mouse；否则 mouse_position）
        boolean buttonHeld = mouseState != null && mouseState.isHeld(sessionId);
        ComputerUseActionKind actionKind = buttonHeld
                ? ComputerUseActionKind.MOUSE
                : ComputerUseActionKind.MOUSE_POSITION;
        String gateRejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, actionKind);
        if (gateRejection != null) {
            return ToolResult.error(call.id(), gateRejection);
        }

        ComputerUseScreenshotGeometry lastScreenshot = geometryStore.get(sessionId);
        int[] point;
        try {
            point = ComputerUseCoordinateScaler.scaleCoord(rawX, rawY, coordinateMode, lastScreenshot);
        } catch (ComputerUseGeometryMissingException e) {
            log.warn("[computer-use] {} 拒绝执行: {}", TOOL_NAME, e.getMessage());
            return ToolResult.error(call.id(), e.getMessage());
        }

        try {
            mouse.moveTo(point[0], point[1]);
        } catch (Exception e) {
            log.error("[computer-use] {} 移动失败: 图坐标 ({},{}) → 屏幕坐标 ({},{}) · {}",
                    TOOL_NAME, rawX, rawY, point[0], point[1], e.getMessage(), e);
            return ToolResult.error(call.id(), "鼠标移动失败: " + e.getMessage());
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: 图坐标 ({},{}) → 屏幕坐标 ({},{}) · 左键按住={} · 档位={}",
                    TOOL_NAME, rawX, rawY, point[0], point[1], buttonHeld, actionKind.wire());
        }

        // CCB toolCalls.ts:2833 成功文案逐字对齐："Moved."
        return ToolResult.success(call.id(), "Moved.");
    }
}
