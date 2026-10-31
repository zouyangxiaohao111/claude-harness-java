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
 * {@code scroll} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>方向 → 位移的符号约定</b>：CCB {@code toolCalls.ts:2647-2649}
 *       （up ⇒ dy=-amount、down ⇒ dy=+amount、left ⇒ dx=-amount、right ⇒ dx=+amount）
 *       —— 符号反了就是「往上滚却滚下去了」⇒ 四个方向逐个断言；</li>
 *   <li><b>Windows 忽略坐标</b>：CCB {@code executorCrossPlatform.ts:501-509} 的
 *       {@code scroll(_x, _y, dx, dy)} ⇒ 滚轮落在<b>当前光标</b>所在窗口
 *       ⇒ 断言「不发 moveTo」；</li>
 *   <li><b>格数上限 100</b>：CCB {@code toolCalls.ts:2645-2646}。</li>
 * </ul>
 */
class ComputerUseScrollToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private static ObjectNode input(double x, double y, String direction, int amount) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode coord = node.putArray("coordinate");
        coord.add(x);
        coord.add(y);
        node.put("scroll_direction", direction);
        node.put("scroll_amount", amount);
        return node;
    }

    private static ComputerUseScrollTool tool(ComputerUsePhase2aFakes.RecordingMouseActions mouse,
                                              ComputerUseGeometryStore geometry,
                                              ComputerUseGrantStore grantStore,
                                              ComputerUseMouseState state) {
        return new ComputerUseScrollTool(mouse, geometry, ComputerUseCoordinateMode.PIXELS,
                grantStore, new ComputerUsePhase2aFakes.FixedResolver("notepad"), state);
    }

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:348-349")
    void nameAndDescriptionAreVerbatim() {
        ComputerUseScrollTool t = tool(new ComputerUsePhase2aFakes.RecordingMouseActions(),
                new ComputerUseGeometryStore(), null, new ComputerUseMouseState());
        assertThat(t.name()).isEqualTo("mcp__computer-use__scroll");
        assertThat(t.description()).isEqualTo(
                "Scroll at the given coordinates. The frontmost application must be in the session allowlist "
                        + "at the time of this call, or this tool returns an error and does nothing.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:350-366（三个必填项 + 方向枚举 + 格数上下限）")
    void schemaIsVerbatim() {
        ComputerUseScrollTool t = tool(new ComputerUsePhase2aFakes.RecordingMouseActions(),
                new ComputerUseGeometryStore(), null, new ComputerUseMouseState());
        JsonNode schema = t.inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        JsonNode props = schema.path("properties");
        assertThat(props.path("coordinate").path("description").asText())
                .isEqualTo("(x, y): " + ComputerUseCoordinateDescriptions.PIXELS_X);
        assertThat(props.path("scroll_direction").path("enum")).hasSize(4);
        assertThat(props.path("scroll_direction").path("enum").get(0).asText()).isEqualTo("up");
        assertThat(props.path("scroll_direction").path("enum").get(3).asText()).isEqualTo("right");
        assertThat(props.path("scroll_direction").path("description").asText()).isEqualTo("Direction to scroll.");
        assertThat(props.path("scroll_amount").path("minimum").asInt()).isZero();
        assertThat(props.path("scroll_amount").path("maximum").asInt()).isEqualTo(100);
        assertThat(props.path("scroll_amount").path("description").asText()).isEqualTo("Number of scroll ticks.");
        assertThat(schema.path("required")).hasSize(3);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("coordinate");
        assertThat(schema.path("required").get(1).asText()).isEqualTo("scroll_direction");
        assertThat(schema.path("required").get(2).asText()).isEqualTo("scroll_amount");
    }

    @Test
    @DisplayName("四个方向的位移符号逐字对齐 CCB toolCalls.ts:2647-2649")
    void directionMappingMatchesCcb() {
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUseScrollTool t = tool(mouse, geometry, null, new ComputerUseMouseState());

        t.execute(new ToolUseBlock("c1", t.name(), input(10, 20, "up", 3)));
        t.execute(new ToolUseBlock("c2", t.name(), input(10, 20, "down", 3)));
        t.execute(new ToolUseBlock("c3", t.name(), input(10, 20, "left", 2)));
        t.execute(new ToolUseBlock("c4", t.name(), input(10, 20, "right", 2)));

        assertThat(mouse.scrolls).hasSize(4);
        assertThat(mouse.scrolls.get(0)).containsExactly(0, -3);
        assertThat(mouse.scrolls.get(1)).containsExactly(0, 3);
        assertThat(mouse.scrolls.get(2)).containsExactly(-2, 0);
        assertThat(mouse.scrolls.get(3)).containsExactly(2, 0);
    }

    @Test
    @DisplayName("Windows 侧忽略坐标：不移动光标，只发滚轮（CCB executorCrossPlatform.ts:501-509）")
    void windowsIgnoresCoordinates() {
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUseScrollTool t = tool(mouse, geometry, null, new ComputerUseMouseState());

        var result = t.execute(new ToolUseBlock("c1", t.name(), input(100, 200, "down", 1)));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Scrolled.");
        assertThat(mouse.scrolls).hasSize(1);
        assertThat(mouse.moves).as("⛔ CCB-Windows 滚轮作用于当前光标 ⇒ 不得先移动光标").isEmpty();
    }

    @Test
    @DisplayName("方向非法 ⇒ 报错不滚动")
    void invalidDirectionIsRejected() {
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUseScrollTool t = tool(mouse, geometry, null, new ComputerUseMouseState());

        var result = t.execute(new ToolUseBlock("c1", t.name(), input(10, 20, "sideways", 1)));

        assertThat(((ToolResult<?>) result).data().toString()).contains("scroll_direction");
        assertThat(mouse.scrolls).isEmpty();
    }

    @Test
    @DisplayName("格数 > 100 ⇒ 报错不滚动（CCB toolCalls.ts:2645-2646）")
    void amountOverLimitIsRejected() {
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUseScrollTool t = tool(mouse, geometry, null, new ComputerUseMouseState());

        var result = t.execute(new ToolUseBlock("c1", t.name(), input(10, 20, "down", 101)));

        assertThat(((ToolResult<?>) result).data().toString()).contains("scroll_amount");
        assertThat(mouse.scrolls).isEmpty();
    }

    @Test
    @DisplayName("左键按住时 ⇒ mouse_full 档 ⇒ click 档被拒（CCB toolCalls.ts:2672-2679）")
    void heldButtonRequiresFullTier() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "click")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(SESSION);
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseMouseState state = new ComputerUseMouseState();
        state.markHeld(SESSION);
        ComputerUseScrollTool t = tool(mouse, geometry, grantStore, state);

        var result = t.execute(new ToolUseBlock("c1", t.name(), input(10, 20, "down", 1)),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("tier");
        assertThat(mouse.scrolls).isEmpty();
    }
}
