package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code switch_display} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>显示器名就是 "display N"</b>：CCB-Windows 的 {@code listAll}
 *       （{@code platforms/win32.ts:452-479}）<b>不产出 label</b> ⇒ CCB 的
 *       {@code uniqueDisplayLabels} 回落成 {@code "display 0"} ⇒ 本仓照此
 *       ⇒ 断言「按 "display 0" 能选中」；</li>
 *   <li><b>auto 回自动选择</b>：CCB {@code toolCalls.ts:3492-3497}；</li>
 *   <li><b>选不中 ⇒ 报错并列出可用显示器</b>：CCB {@code toolCalls.ts:3523-3530}。</li>
 * </ul>
 */
class ComputerUseSwitchDisplayToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private static ObjectNode input(String display) {
        ObjectNode node = JSON.createObjectNode();
        node.put("display", display);
        return node;
    }

    private static ComputerUsePhase2aFakes.FakeDisplayProvider provider() {
        ComputerUsePhase2aFakes.FakeDisplayProvider provider = new ComputerUsePhase2aFakes.FakeDisplayProvider();
        provider.displays.add(new ComputerUseDisplay(0, "display 0", 1920, 1080, 0, 0, true, "\\\\.\\DISPLAY1"));
        provider.displays.add(new ComputerUseDisplay(1, "display 1", 2560, 1440, 1920, 0, false, "\\\\.\\DISPLAY2"));
        return provider;
    }

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:839-846")
    void nameAndDescriptionAreVerbatim() {
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(
                provider(), new ComputerUseDisplayStore());

        assertThat(tool.name()).isEqualTo("mcp__computer-use__switch_display");
        assertThat(tool.description()).isEqualTo(
                "Switch which monitor subsequent screenshots capture. "
                        + "Use this when the application you need is on a different monitor than the one shown. "
                        + "The screenshot tool tells you which monitor it captured and lists "
                        + "other attached monitors by name — pass one of those names here. "
                        + "After switching, call screenshot to see the new monitor. "
                        + "Pass \"auto\" to return to automatic monitor selection.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:847-857（display 必填字符串）")
    void schemaIsVerbatim() {
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(
                provider(), new ComputerUseDisplayStore());
        JsonNode schema = tool.inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("display").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("display").path("description").asText()).isEqualTo(
                "Monitor name from the screenshot note (e.g. \"Built-in Retina Display\", "
                        + "\"LG UltraFine\"), or \"auto\" to re-enable automatic selection.");
        assertThat(schema.path("required").get(0).asText()).isEqualTo("display");
        assertThat(schema.path("required")).hasSize(1);
    }

    @Test
    @DisplayName("按显示器名选中并记录（CCB 的显示器名在 Windows 上就是 \"display N\"）")
    void displayIsPinnedByName() {
        ComputerUseDisplayStore store = new ComputerUseDisplayStore();
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(provider(), store);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("display 1")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data())
                .isEqualTo("Switched to monitor \"display 1\". Call screenshot to see it.");
        assertThat(store.get(SESSION)).isNotNull();
        assertThat(store.get(SESSION).displayId()).isEqualTo(1);
    }

    @Test
    @DisplayName("auto ⇒ 回到自动选择（CCB toolCalls.ts:3492-3497 逐字文案）")
    void autoReturnsToAutomaticSelection() {
        ComputerUseDisplayStore store = new ComputerUseDisplayStore();
        store.put(SESSION, new ComputerUseDisplay(1, "display 1", 2560, 1440, 1920, 0, false, ""));
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(provider(), store);

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("auto")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data())
                .isEqualTo("Returned to automatic monitor selection. Call screenshot to continue.");
        assertThat(store.get(SESSION)).isNull();
    }

    @Test
    @DisplayName("选不中 ⇒ 报错并列出可用显示器（CCB toolCalls.ts:3523-3530）")
    void unknownDisplayIsRejectedWithAvailableList() {
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(
                provider(), new ComputerUseDisplayStore());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("LG UltraFine")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString())
                .contains("No monitor named \"LG UltraFine\" is connected")
                .contains("\"display 0\"")
                .contains("\"display 1\"");
    }

    @Test
    @DisplayName("无显示器可枚举 ⇒ 报错（CCB toolCalls.ts:3505-3509）")
    void noDisplaysIsRejected() {
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(
                new ComputerUsePhase2aFakes.FakeDisplayProvider(), new ComputerUseDisplayStore());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("display 0")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("Failed to enumerate displays.");
    }

    @Test
    @DisplayName("只有一块屏 ⇒ 无从切换 ⇒ 报错（CCB toolCalls.ts:3514-3519 逐字文案）")
    void singleMonitorIsRejected() {
        ComputerUsePhase2aFakes.FakeDisplayProvider provider = new ComputerUsePhase2aFakes.FakeDisplayProvider();
        provider.displays.add(new ComputerUseDisplay(0, "display 0", 1920, 1080, 0, 0, true, ""));
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(provider, new ComputerUseDisplayStore());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("display 0")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString())
                .isEqualTo("Only one monitor is connected. There is nothing to switch to.");
    }

    @Test
    @DisplayName("纯读工具 ⇒ isReadOnly=true（切显示器不改系统状态）")
    void isReadOnly() {
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(
                provider(), new ComputerUseDisplayStore());
        assertThat(tool.isReadOnly(JSON.createObjectNode())).isTrue();
    }

    @Test
    @DisplayName("会话隔离：A 会话选的显示器不影响 B 会话")
    void pinIsIsolatedPerSession() {
        ComputerUseDisplayStore store = new ComputerUseDisplayStore();
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(provider(), store);

        tool.execute(new ToolUseBlock("c1", tool.name(), input("display 1")),
                ToolUseContext.of(null, SESSION));

        assertThat(store.get("sess-2")).isNull();
        assertThat(store.get(SESSION)).isNotNull();
    }

    @Test
    @DisplayName("可用显示器清单按枚举顺序输出")
    void availableListKeepsOrder() {
        ComputerUseSwitchDisplayTool tool = new ComputerUseSwitchDisplayTool(
                provider(), new ComputerUseDisplayStore());

        var result = tool.execute(new ToolUseBlock("c1", tool.name(), input("nope")),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString())
                .contains("Available monitors: \"display 0\", \"display 1\".");
        assertThat(List.of("display 0", "display 1")).containsExactly("display 0", "display 1");
    }
}
