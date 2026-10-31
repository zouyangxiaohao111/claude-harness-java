package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code left_click_drag} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>起点 / 终点语义</b>：CCB {@code tools.ts:375-384} 刻意让两个坐标描述不同
 *       （{@code coordinate} = 终点、{@code start_coordinate} = 起点）⇒ 断言 schema 描述逐字，
 *       并断言「省略起点 ⇒ 从当前光标拖」（CCB {@code toolCalls.ts:2719}）；</li>
 *   <li><b>异常路径不卡键</b>：拖拽中途失败会把用户的左键<b>卡在按下态</b>
 *       ⇒ 断言「拖拽结束（或抛异常）后左键一定被抬起」；</li>
 *   <li><b>防呆清场</b>：CCB {@code toolCalls.ts:2698-2702} —— 之前有悬空的
 *       {@code left_mouse_down} ⇒ 拖拽前先释放（否则旗标与 OS 状态失同步）。</li>
 * </ul>
 */
class ComputerUseLeftClickDragToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private static ObjectNode input(double endX, double endY, Double startX, Double startY) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode end = node.putArray("coordinate");
        end.add(endX);
        end.add(endY);
        if (startX != null && startY != null) {
            ArrayNode start = node.putArray("start_coordinate");
            start.add(startX);
            start.add(startY);
        }
        return node;
    }

    private static ComputerUseMouseAnimator animator(ComputerUsePhase2aFakes.FakeCursor cursor) {
        // 缓动关闭 ⇒ 一次到位（断言确定性）
        return new ComputerUseMouseAnimator(cursor, new ComputerUsePhase2aFakes.RecordingSleeper(), false);
    }

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:371-372")
    void nameAndDescriptionAreVerbatim() {
        ComputerUseLeftClickDragTool tool = new ComputerUseLeftClickDragTool(
                animator(new ComputerUsePhase2aFakes.FakeCursor()), new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS, null, null, new ComputerUseMouseState());

        assertThat(tool.name()).isEqualTo("mcp__computer-use__left_click_drag");
        assertThat(tool.description()).isEqualTo(
                "Press, move to target, and release. The frontmost application must be in the session allowlist "
                        + "at the time of this call, or this tool returns an error and does nothing.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:375-385（终点 / 起点两段描述不同；required=[coordinate]）")
    void schemaIsVerbatim() {
        ComputerUseLeftClickDragTool tool = new ComputerUseLeftClickDragTool(
                animator(new ComputerUsePhase2aFakes.FakeCursor()), new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS, null, null, new ComputerUseMouseState());
        JsonNode schema = tool.inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("coordinate").path("description").asText())
                .isEqualTo("(x, y) end point: " + ComputerUseCoordinateDescriptions.PIXELS_X);
        assertThat(schema.path("properties").path("start_coordinate").path("description").asText())
                .isEqualTo("(x, y) start point. If omitted, drags from the current cursor position. "
                        + ComputerUseCoordinateDescriptions.PIXELS_X);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("coordinate");
        assertThat(schema.path("required")).hasSize(1);
    }

    @Test
    @DisplayName("给了起点 ⇒ 先移到起点再按下拖到终点")
    void dragWithExplicitStart() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUseLeftClickDragTool tool = new ComputerUseLeftClickDragTool(animator(cursor), geometry,
                ComputerUseCoordinateMode.PIXELS, null, null, new ComputerUseMouseState());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input(300, 400, 100d, 200d)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Dragged.");
        assertThat(cursor.moves).hasSize(2);
        assertThat(cursor.moves.get(0)).containsExactly(100, 200);
        assertThat(cursor.moves.get(1)).containsExactly(300, 400);
        assertThat(cursor.downCount).isEqualTo(1);
        assertThat(cursor.upCount).isEqualTo(1);
    }

    @Test
    @DisplayName("省略起点 ⇒ 从当前光标位置拖（不先移动到别处）")
    void dragFromCurrentCursor() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUseLeftClickDragTool tool = new ComputerUseLeftClickDragTool(animator(cursor), geometry,
                ComputerUseCoordinateMode.PIXELS, null, null, new ComputerUseMouseState());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input(300, 400, null, null)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Dragged.");
        assertThat(cursor.moves).hasSize(1);
        assertThat(cursor.moves.get(0)).containsExactly(300, 400);
        assertThat(cursor.downCount).isEqualTo(1);
        assertThat(cursor.upCount).isEqualTo(1);
    }

    @Test
    @DisplayName("坐标按截图快照比例换算")
    void coordinatesAreScaled() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        geometry.put(null, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        ComputerUseLeftClickDragTool tool = new ComputerUseLeftClickDragTool(animator(cursor), geometry,
                ComputerUseCoordinateMode.PIXELS, null, null, new ComputerUseMouseState());

        tool.execute(new ToolUseBlock("c1", tool.name(), input(728, 409.5d, 364d, 204.75d)));

        assertThat(cursor.moves.get(0)).containsExactly(480, 270);
        assertThat(cursor.moves.get(1)).containsExactly(960, 540);
    }

    @Test
    @DisplayName("拖拽前有悬空的 left_mouse_down ⇒ 先释放（防呆清场 CCB toolCalls.ts:2698-2702）")
    void defensiveClearBeforeDrag() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUseMouseState state = new ComputerUseMouseState();
        state.markHeld(null);
        ComputerUseLeftClickDragTool tool = new ComputerUseLeftClickDragTool(animator(cursor), geometry,
                ComputerUseCoordinateMode.PIXELS, null, null, state);

        tool.execute(new ToolUseBlock("c1", tool.name(), input(300, 400, 100d, 200d)));

        // 清场释放 1 次 + 拖拽自身的释放 1 次 = 2
        assertThat(cursor.upCount).isEqualTo(2);
        assertThat(state.isHeld(null)).as("拖拽结束后左键不应仍被视为按住").isFalse();
    }

    @Test
    @DisplayName("拖拽中途失败 ⇒ 左键仍被抬起（绝不卡键）")
    void failureDoesNotLeaveButtonHeld() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        cursor.failOnUp = true;
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUseLeftClickDragTool tool = new ComputerUseLeftClickDragTool(animator(cursor), geometry,
                ComputerUseCoordinateMode.PIXELS, null, null, new ComputerUseMouseState());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input(300, 400, 100d, 200d)));

        // 抬起失败 ⇒ 工具报错（fail loud），而不是假装成功
        assertThat(((ToolResult<?>) result).data().toString()).contains("拖拽失败");
        assertThat(cursor.downCount).isEqualTo(1);
    }

    @Test
    @DisplayName("read 档被拒（拖拽属 mouse 档）")
    void readTierRejectsDrag() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "read")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(SESSION);
        ComputerUseLeftClickDragTool tool = new ComputerUseLeftClickDragTool(animator(cursor), geometry,
                ComputerUseCoordinateMode.PIXELS, grantStore,
                new ComputerUsePhase2aFakes.FixedResolver("notepad"), new ComputerUseMouseState());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input(300, 400, 100d, 200d)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("tier");
        assertThat(cursor.downCount).as("⛔ 档位不足 ⇒ 不得按下").isZero();
    }
}
