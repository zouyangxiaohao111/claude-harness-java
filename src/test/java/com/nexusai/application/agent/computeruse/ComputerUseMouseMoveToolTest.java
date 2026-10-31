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
 * {@code mouse_move} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>档位分档</b>：CCB {@code toolCalls.ts:2795} 规定「没按住左键 ⇒ mouse_position
 *       ⇒ <b>任何档位都放行</b>」（纯光标定位不与应用交互）；「按住了 ⇒ mouse
 *       ⇒ 需 click/full」⇒ 断言「read 档也能纯移动，但按住后再移动要档位」。</li>
 *   <li><b>坐标换算</b>：hover 特效也得落在正确的元素上 ⇒ 断言换算后的屏幕坐标。</li>
 * </ul>
 */
class ComputerUseMouseMoveToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private static ObjectNode coordinate(double x, double y) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode coord = node.putArray("coordinate");
        coord.add(x);
        coord.add(y);
        return node;
    }

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:390-391")
    void nameAndDescriptionAreVerbatim() {
        ComputerUseMouseMoveTool tool = new ComputerUseMouseMoveTool(
                new ComputerUsePhase2aFakes.RecordingMouseActions(),
                new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS, null, null, new ComputerUseMouseState());

        assertThat(tool.name()).isEqualTo("mcp__computer-use__mouse_move");
        assertThat(tool.description()).isEqualTo(
                "Move the mouse cursor without clicking. Useful for triggering hover states. "
                        + "The frontmost application must be in the session allowlist at the time of this call, "
                        + "or this tool returns an error and does nothing.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:392-397（只有 coordinate；required=[coordinate]）")
    void schemaIsVerbatim() {
        ComputerUseMouseMoveTool tool = new ComputerUseMouseMoveTool(
                new ComputerUsePhase2aFakes.RecordingMouseActions(),
                new ComputerUseGeometryStore(),
                ComputerUseCoordinateMode.PIXELS, null, null, new ComputerUseMouseState());
        JsonNode schema = tool.inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").fieldNames()).toIterable().containsExactly("coordinate");
        assertThat(schema.path("properties").path("coordinate").path("minItems").asInt()).isEqualTo(2);
        assertThat(schema.path("properties").path("coordinate").path("maxItems").asInt()).isEqualTo(2);
        assertThat(schema.path("properties").path("coordinate").path("description").asText())
                .isEqualTo("(x, y): " + ComputerUseCoordinateDescriptions.PIXELS_X);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("coordinate");
        assertThat(schema.path("required")).hasSize(1);
    }

    @Test
    @DisplayName("坐标按截图快照比例换算后移动")
    void coordinatesAreScaled() {
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        geometry.put(null, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseMouseMoveTool tool = new ComputerUseMouseMoveTool(mouse, geometry,
                ComputerUseCoordinateMode.PIXELS, null, null, new ComputerUseMouseState());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), coordinate(728, 409.5d)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Moved.");
        assertThat(mouse.moves).hasSize(1);
        assertThat(mouse.moves.get(0)).containsExactly(960, 540);
    }

    @Test
    @DisplayName("未按住左键 ⇒ mouse_position 档 ⇒ read 档也放行（CCB toolCalls.ts:2795）")
    void readTierAllowsPureMove() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("chrome", "Chrome", 1L, "read")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(SESSION);
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseMouseMoveTool tool = new ComputerUseMouseMoveTool(mouse, geometry,
                ComputerUseCoordinateMode.PIXELS, grantStore,
                new ComputerUsePhase2aFakes.FixedResolver("chrome"), new ComputerUseMouseState());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), coordinate(10, 20)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Moved.");
        assertThat(mouse.moves).hasSize(1);
    }

    @Test
    @DisplayName("左键按住时 ⇒ mouse 档 ⇒ read 档被拒（按住后再移动 = 拖拽交互）")
    void heldButtonRequiresClickTier() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("chrome", "Chrome", 1L, "read")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(SESSION);
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseMouseState state = new ComputerUseMouseState();
        state.markHeld(SESSION);
        ComputerUseMouseMoveTool tool = new ComputerUseMouseMoveTool(mouse, geometry,
                ComputerUseCoordinateMode.PIXELS, grantStore,
                new ComputerUsePhase2aFakes.FixedResolver("chrome"), state);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), coordinate(10, 20)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("tier");
        assertThat(mouse.moves).as("⛔ 档位不足 ⇒ 不得移动").isEmpty();
    }
}
