package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code left_mouse_down} / {@code left_mouse_up} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>跨调用状态</b>：这两个工具是<b>成对</b>的（按下 → 移动 → 抬起），
 *       「已按住」必须跨调用保持 ⇒ 断言「按下后再按 ⇒ 报错」；
 *       若状态不保持，用户屏幕上会卡着一个按下的左键；</li>
 *   <li><b>抬起幂等</b>：CCB {@code toolCalls.ts:3801-3803} —— 未按住时调用
 *       {@code left_mouse_up} <b>不报错</b>（模型常在错误路径后补偿性调用它）。</li>
 *   <li><b>会话隔离</b>：本仓按 sessionId 分桶（CCB 是模块级变量）⇒ 断言
 *       「A 会话按下的左键不影响 B 会话」。</li>
 * </ul>
 */
class ComputerUseLeftMouseButtonToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";
    private static final String OTHER_SESSION = "sess-2";

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:944-947 / 956-959")
    void namesAndDescriptionsAreVerbatim() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseMouseState state = new ComputerUseMouseState();
        ComputerUseLeftMouseDownTool down = new ComputerUseLeftMouseDownTool(cursor, null, null, state);
        ComputerUseLeftMouseUpTool up = new ComputerUseLeftMouseUpTool(cursor, null, null, state);

        assertThat(down.name()).isEqualTo("mcp__computer-use__left_mouse_down");
        assertThat(down.description()).isEqualTo(
                "Press the left mouse button at the current cursor position and leave it held. "
                        + "The frontmost application must be in the session allowlist at the time of this call, "
                        + "or this tool returns an error and does nothing. "
                        + "Use mouse_move first to position the cursor. Call left_mouse_up to release. "
                        + "Errors if the button is already held.");
        assertThat(up.name()).isEqualTo("mcp__computer-use__left_mouse_up");
        assertThat(up.description()).isEqualTo(
                "Release the left mouse button at the current cursor position. "
                        + "The frontmost application must be in the session allowlist at the time of this call, "
                        + "or this tool returns an error and does nothing. "
                        + "Pairs with left_mouse_down. Safe to call even if the button is not currently held.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:948-951 / 960-963（两者都无参数）")
    void schemasAreVerbatim() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseMouseState state = new ComputerUseMouseState();
        for (JsonNode schema : new JsonNode[]{
                new ComputerUseLeftMouseDownTool(cursor, null, null, state).inputSchema(),
                new ComputerUseLeftMouseUpTool(cursor, null, null, state).inputSchema()}) {
            assertThat(schema.path("type").asText()).isEqualTo("object");
            assertThat(schema.path("properties").isEmpty()).isTrue();
            assertThat(schema.path("required").isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("按下 ⇒ 成功文案逐字；再按 ⇒ 报错「已按住」")
    void secondPressIsRejected() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseMouseState state = new ComputerUseMouseState();
        ComputerUseLeftMouseDownTool down = new ComputerUseLeftMouseDownTool(cursor, null, null, state);

        var first = down.execute(new ToolUseBlock("c1", down.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));
        assertThat(((ToolResult<?>) first).data()).isEqualTo("Mouse button pressed.");
        assertThat(cursor.downCount).isEqualTo(1);

        var second = down.execute(new ToolUseBlock("c2", down.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));
        assertThat(((ToolResult<?>) second).data().toString())
                .contains("mouse button already held, call left_mouse_up first");
        assertThat(cursor.downCount).as("⛔ 已按住 ⇒ 不得重复按下").isEqualTo(1);
    }

    @Test
    @DisplayName("未按住时调用抬起 ⇒ 幂等放行（CCB toolCalls.ts:3801-3803）")
    void releaseIsIdempotent() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseMouseState state = new ComputerUseMouseState();
        ComputerUseLeftMouseUpTool up = new ComputerUseLeftMouseUpTool(cursor, null, null, state);

        var result = up.execute(new ToolUseBlock("c1", up.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data()).isEqualTo("Mouse button released.");
    }

    @Test
    @DisplayName("会话隔离：A 会话按下的左键不影响 B 会话")
    void stateIsIsolatedPerSession() {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseMouseState state = new ComputerUseMouseState();
        ComputerUseLeftMouseDownTool down = new ComputerUseLeftMouseDownTool(cursor, null, null, state);

        down.execute(new ToolUseBlock("c1", down.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));

        var other = down.execute(new ToolUseBlock("c2", down.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, OTHER_SESSION));

        assertThat(((ToolResult<?>) other).data()).isEqualTo("Mouse button pressed.");
    }

    @Test
    @DisplayName("read 档被拒（按下 / 抬起属 mouse 档）")
    void readTierRejectsMouseButtons() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "read")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseMouseState state = new ComputerUseMouseState();
        ComputerUseLeftMouseDownTool down = new ComputerUseLeftMouseDownTool(cursor, grantStore,
                new ComputerUsePhase2aFakes.FixedResolver("notepad"), state);

        var result = down.execute(new ToolUseBlock("c1", down.name(), JSON.createObjectNode()),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("tier");
        assertThat(cursor.downCount).as("⛔ 档位不足 ⇒ 不得按下").isZero();
    }
}
