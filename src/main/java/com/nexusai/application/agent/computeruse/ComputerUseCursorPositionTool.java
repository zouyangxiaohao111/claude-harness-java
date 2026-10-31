package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * {@code mcp__computer-use__cursor_position} · 读当前鼠标光标位置工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:911-920}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:912-919}）</h2>
 * <pre>
 * name: 'cursor_position',
 * description: 'Get the current mouse cursor position. Returns image-pixel coordinates relative to the most recent screenshot, or logical points if no screenshot has been taken.',
 * inputSchema: { type: 'object', properties: {}, required: [] },
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:3667-3704} 的 {@code handleCursorPosition}）</h2>
 * <ul>
 *   <li>取逻辑坐标 {@code executor.getCursorPosition()}；</li>
 *   <li><b>有</b>上次截图 ⇒ 换算成<b>图像像素</b>：
 *       {@code localX = logical.x - shot.originX}；
 *       {@code x = round(localX * (shot.width / shot.displayWidth))}
 *       （CCB {@code toolCalls.ts:3694-3695}，即 scaleCoord 的<b>逆变换</b>）；</li>
 *   <li>光标在截图那块屏<b>之外</b> ⇒ 返回 {@code logical_points} + 提示
 *       （CCB {@code toolCalls.ts:3687-3693}）；</li>
 *   <li><b>无</b>上次截图 ⇒ 返回 {@code logical_points} + 提示
 *       （CCB {@code toolCalls.ts:3698-3703}）。</li>
 * </ul>
 *
 * <h2>返回形状</h2>
 * <p>CCB 返回 JSON：{@code {x, y, coordinateSpace}}（+ 视情带 {@code note}）。
 * 本仓返回<b>同形状</b>的 {@link ObjectNode}（{@code coordinateSpace ∈
 * {"image_pixels", "logical_points"}}）。
 *
 * <p>⛔ <b>无门控</b>：CCB 的 {@code handleCursorPosition} <b>不</b>走
 * {@code runInputActionGates}（纯读操作，无输入 ⇒ 没有可保护的东西）——
 * 与本仓 {@link ComputerUseInputGate} 的适用范围一致（只保护<b>输入</b>动作）。
 */
public final class ComputerUseCursorPositionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseCursorPositionTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "cursor_position");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:913-914}。 */
    private static final String DESCRIPTION =
            "Get the current mouse cursor position. Returns image-pixel coordinates relative to the most "
                    + "recent screenshot, or logical points if no screenshot has been taken.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:915-918}（无参数）。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    /** 坐标空间标记 · 图像像素（CCB {@code toolCalls.ts:3696}）。 */
    static final String SPACE_IMAGE_PIXELS = "image_pixels";

    /** 坐标空间标记 · 逻辑点（CCB {@code toolCalls.ts:3689} / {@code 3700}）。 */
    static final String SPACE_LOGICAL_POINTS = "logical_points";

    private final ComputerUseCursor cursor;
    private final ComputerUseGeometryStore geometryStore;

    /**
     * @param cursor        光标位置读取实现
     * @param geometryStore 截图几何快照存储（提供「上次截图」的换算基准）
     */
    public ComputerUseCursorPositionTool(ComputerUseCursor cursor, ComputerUseGeometryStore geometryStore) {
        if (cursor == null || geometryStore == null) {
            throw new IllegalArgumentException("ComputerUseCursorPositionTool 依赖不能为空");
        }
        this.cursor = cursor;
        this.geometryStore = geometryStore;
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
                    "[computer-use] cursor_position inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 纯读（无副作用）⇒ 只读、并发安全。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 只读工具 · <b>权限自决 Allow（免弹窗）</b>。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use cursor_position is read-only"),
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
     * 读取光标位置。
     *
     * <p>有上次截图 ⇒ 返回图像像素坐标（scaleCoord 的逆变换）；
     * 否则返回逻辑点坐标 + 提示。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;
        int[] logical;
        try {
            logical = cursor.location();
        } catch (Exception e) {
            log.error("[computer-use] {} 读取光标位置失败: {}", TOOL_NAME, e.getMessage(), e);
            return ToolResult.error(call.id(), "读取光标位置失败: " + e.getMessage());
        }

        ObjectNode root = JSON.createObjectNode();
        ComputerUseScreenshotGeometry shot = geometryStore.get(sessionId);
        if (shot == null) {
            // 无上次截图 ⇒ CCB toolCalls.ts:3698-3703
            root.put("x", logical[0]);
            root.put("y", logical[1]);
            root.put("coordinateSpace", SPACE_LOGICAL_POINTS);
            root.put("note", "take a screenshot first for image-pixel coordinates");
        } else {
            // 减去截图时刻的虚拟屏幕原点（CCB toolCalls.ts:3674-3676）
            int localX = logical[0] - shot.originX();
            int localY = logical[1] - shot.originY();
            if (localX < 0 || localX > shot.displayWidth()
                    || localY < 0 || localY > shot.displayHeight()) {
                // 光标在截图那块屏之外（CCB toolCalls.ts:3687-3693）
                root.put("x", logical[0]);
                root.put("y", logical[1]);
                root.put("coordinateSpace", SPACE_LOGICAL_POINTS);
                root.put("note", "cursor is on a different monitor than your last screenshot; take a fresh screenshot");
            } else {
                // scaleCoord 的逆变换（CCB toolCalls.ts:3694-3695）
                root.put("x", Math.round(localX * (shot.width() / (double) shot.displayWidth())));
                root.put("y", Math.round(localY * (shot.height() / (double) shot.displayHeight())));
                root.put("coordinateSpace", SPACE_IMAGE_PIXELS);
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: 逻辑坐标 ({},{}) · 空间={} · 有快照={}",
                    TOOL_NAME, logical[0], logical[1], root.path("coordinateSpace").asText(), shot != null);
        }
        return ToolResult.success(call.id(), root);
    }
}
