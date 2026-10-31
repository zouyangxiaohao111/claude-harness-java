package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code cursor_position} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>只读 ⇒ 不进门控</b>：CCB 的 {@code handleCursorPosition} <b>不</b>走
 *       {@code runInputActionGates} ⇒ 断言 {@code isReadOnly} 为 true 且不查授权位
 *       （若误接门控，read 档会话就问不出光标位置）；</li>
 *   <li><b>两种坐标空间</b>：CCB {@code toolCalls.ts:3687-3703} ——
 *       有截图且光标在截图那块屏内 ⇒ 返回<b>图像像素</b>（scaleCoord 的逆变换）；
 *       否则返回<b>逻辑点</b> + note。模型据此判断「光标在不在画面里」
 *       ⇒ 两种分支都要断言。</li>
 * </ul>
 */
class ComputerUseCursorPositionToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:912-914")
    void nameAndDescriptionAreVerbatim() {
        ComputerUseCursorPositionTool tool = new ComputerUseCursorPositionTool(
                new ComputerUsePhase2aFakes.FakeCursor(), new ComputerUseGeometryStore());

        assertThat(tool.name()).isEqualTo("mcp__computer-use__cursor_position");
        assertThat(tool.description()).isEqualTo(
                "Get the current mouse cursor position. Returns image-pixel coordinates relative to the most "
                        + "recent screenshot, or logical points if no screenshot has been taken.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:915-918（无参数）")
    void schemaIsVerbatim() {
        ComputerUseCursorPositionTool tool = new ComputerUseCursorPositionTool(
                new ComputerUsePhase2aFakes.FakeCursor(), new ComputerUseGeometryStore());
        JsonNode schema = tool.inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").isEmpty()).isTrue();
        assertThat(schema.path("required").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("纯读工具 ⇒ isReadOnly=true（不进输入门控）")
    void isReadOnly() {
        ComputerUseCursorPositionTool tool = new ComputerUseCursorPositionTool(
                new ComputerUsePhase2aFakes.FakeCursor(), new ComputerUseGeometryStore());
        assertThat(tool.isReadOnly(JSON.createObjectNode())).isTrue();
    }

    @Test
    @DisplayName("有截图快照 ⇒ 返回图像像素（scaleCoord 的逆变换）")
    void returnsImagePixelsWhenSnapshotExists() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        cursor.locations.add(new int[]{960, 540});
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        // 图像 1456x819 ↔ 显示 1920x1080 ⇒ 逆变换系数 1456/1920
        geometry.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        ComputerUseCursorPositionTool tool = new ComputerUseCursorPositionTool(cursor, geometry);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));

        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(data.path("coordinateSpace").asText()).isEqualTo("image_pixels");
        assertThat(data.path("x").asInt()).isEqualTo(728);
        assertThat(data.path("y").asInt()).isEqualTo(410);
    }

    @Test
    @DisplayName("无截图快照 ⇒ 返回逻辑点 + 提示（CCB toolCalls.ts:3698-3703）")
    void returnsLogicalPointsWithoutSnapshot() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        cursor.locations.add(new int[]{100, 200});
        ComputerUseCursorPositionTool tool = new ComputerUseCursorPositionTool(
                cursor, new ComputerUseGeometryStore());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));

        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(data.path("coordinateSpace").asText()).isEqualTo("logical_points");
        assertThat(data.path("x").asInt()).isEqualTo(100);
        assertThat(data.path("y").asInt()).isEqualTo(200);
        assertThat(data.path("note").asText()).contains("screenshot");
    }

    @Test
    @DisplayName("光标在截图那块屏之外 ⇒ 返回逻辑点 + 提示（CCB toolCalls.ts:3687-3693）")
    void returnsLogicalPointsWhenOutsideScreenshotBounds() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        cursor.locations.add(new int[]{3000, 200});
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        geometry.put(SESSION, new ComputerUseScreenshotGeometry(1920, 1080, 1920, 1080, 0, 0));
        ComputerUseCursorPositionTool tool = new ComputerUseCursorPositionTool(cursor, geometry);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));

        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(data.path("coordinateSpace").asText()).isEqualTo("logical_points");
        assertThat(data.path("note").asText()).contains("different monitor");
    }
}
