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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Phase 2b · <b>Windows 专属 11 个工具</b>单测。
 *
 * <p><b>WHY（规则 9 · 测试验证意图）</b>：这 11 个工具的<b>名字 / 描述 / schema</b>
 * 是模型与本仓之间的<b>唯一契约</b>（CCB {@code tools.ts:420-836} 逐字抄来）⇒
 * 一旦漂移，模型就会按错误的参数调用 ⇒ 必须<b>逐字</b>断言。
 * 此外还要锁住三条<b>行为语义</b>：
 * <ul>
 *   <li><b>门控接入</b>：未绑定窗口 ⇒ 明确报错（不是静默无动作）；</li>
 *   <li><b>坐标换算</b>：窗口定向时坐标必须换算到<b>客户区</b>；</li>
 *   <li><b>修饰键释放顺序</b>：正序按下 → 动作 → <b>逆序</b>释放，异常也不卡键。</li>
 * </ul>
 */
class ComputerUseWindowsToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-win";

    private static ToolUseContext ctx() {
        return ToolUseContext.of(null, SESSION);
    }

    // ══════════════════════════════════════════════════════════════════
    // ① 契约：11 个工具的名字 / 描述 / schema 逐字对齐 CCB
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("11 个 Windows 专属工具的名字逐字对齐 CCB（CCB setup.ts:17-21 硬约束）")
    void toolNamesAreExact() {
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUsePhase2bFakes.FakeUiaService uia = new ComputerUsePhase2bFakes.FakeUiaService();
        ComputerUsePhase2bFakes.FakeStatusIndicator indicator = new ComputerUsePhase2bFakes.FakeStatusIndicator();
        ComputerUsePhase2bFakes.FakeTerminalLauncher launcher = new ComputerUsePhase2bFakes.FakeTerminalLauncher();

        List<String> names = List.of(
                new ComputerUseWindowManagementTool(ops, store).name(),
                new ComputerUseClickElementTool(uia, ops, store).name(),
                new ComputerUseTypeIntoElementTool(uia, ops, store).name(),
                new ComputerUseOpenTerminalTool(launcher, ops, store, ComputerUseSleeper.REAL).name(),
                new ComputerUseBindWindowTool(ops, store, null, null).name(),
                new ComputerUseActivateWindowTool(ops, store).name(),
                new ComputerUsePromptRespondTool(ops, store, ComputerUseSleeper.REAL).name(),
                new ComputerUseStatusIndicatorTool(indicator, store).name(),
                new ComputerUseVirtualKeyboardTool(ops, store, ComputerUseSleeper.REAL).name(),
                new ComputerUseVirtualMouseTool(ops, store, ComputerUseSleeper.REAL).name(),
                new ComputerUseMouseWheelTool(ops, store).name());

        assertThat(names).containsExactly(
                "mcp__computer-use__window_management",
                "mcp__computer-use__click_element",
                "mcp__computer-use__type_into_element",
                "mcp__computer-use__open_terminal",
                "mcp__computer-use__bind_window",
                "mcp__computer-use__activate_window",
                "mcp__computer-use__prompt_respond",
                "mcp__computer-use__status_indicator",
                "mcp__computer-use__virtual_keyboard",
                "mcp__computer-use__virtual_mouse",
                "mcp__computer-use__mouse_wheel");
    }

    @Test
    @DisplayName("window_management 的 action enum 逐字对齐 CCB tools.ts:436-445（8 个动作）")
    void windowManagementActionEnumIsExact() {
        JsonNode schema = new ComputerUseWindowManagementTool(
                new ComputerUsePhase2bFakes.RecordingWindowOps(), new ComputerUseWindowBindingStore()).inputSchema();
        assertThat(schema.path("properties").path("action").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("action").path("enum")).extracting(JsonNode::asText)
                .containsExactly("minimize", "maximize", "restore", "close", "focus",
                        "move_offscreen", "move_resize", "get_rect");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("action");
    }

    @Test
    @DisplayName("bind_window 的 action enum 逐字对齐 CCB tools.ts:590（bind/unbind/status/list）")
    void bindWindowActionEnumIsExact() {
        JsonNode schema = new ComputerUseBindWindowTool(
                new ComputerUsePhase2bFakes.RecordingWindowOps(), new ComputerUseWindowBindingStore(),
                null, null).inputSchema();
        assertThat(schema.path("properties").path("action").path("enum")).extracting(JsonNode::asText)
                .containsExactly("bind", "unbind", "status", "list");
        // hwnd 是字符串（CCB tools.ts:602-605）
        assertThat(schema.path("properties").path("hwnd").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("pid").path("type").asText()).isEqualTo("integer");
    }

    @Test
    @DisplayName("virtual_keyboard / virtual_mouse / mouse_wheel 的 enum 与坐标约束逐字对齐 CCB")
    void virtualToolEnumsAreExact() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();

        JsonNode kb = new ComputerUseVirtualKeyboardTool(ops, store, ComputerUseSleeper.REAL).inputSchema();
        assertThat(kb.path("properties").path("action").path("enum")).extracting(JsonNode::asText)
                .containsExactly("type", "combo", "press", "release", "hold");
        assertThat(kb.path("required")).extracting(JsonNode::asText).containsExactly("action", "text");
        assertThat(kb.path("properties").path("repeat").path("minimum").asInt()).isEqualTo(1);
        assertThat(kb.path("properties").path("repeat").path("maximum").asInt()).isEqualTo(100);

        JsonNode ms = new ComputerUseVirtualMouseTool(ops, store, ComputerUseSleeper.REAL).inputSchema();
        assertThat(ms.path("properties").path("action").path("enum")).extracting(JsonNode::asText)
                .containsExactly("click", "double_click", "right_click", "move", "drag", "down", "up");
        assertThat(ms.path("required")).extracting(JsonNode::asText).containsExactly("action", "coordinate");
        assertThat(ms.path("properties").path("coordinate").path("minItems").asInt()).isEqualTo(2);
        assertThat(ms.path("properties").path("coordinate").path("maxItems").asInt()).isEqualTo(2);

        JsonNode wh = new ComputerUseMouseWheelTool(ops, store).inputSchema();
        assertThat(wh.path("properties").path("direction").path("enum")).extracting(JsonNode::asText)
                .containsExactly("vertical", "horizontal");
        assertThat(wh.path("required")).extracting(JsonNode::asText).containsExactly("coordinate", "delta");
    }

    @Test
    @DisplayName("prompt_respond 的 response_type enum 与 arrow_count 边界逐字对齐 CCB tools.ts:655-676")
    void promptRespondSchemaIsExact() {
        JsonNode schema = new ComputerUsePromptRespondTool(
                new ComputerUsePhase2bFakes.RecordingWindowOps(), new ComputerUseWindowBindingStore(),
                ComputerUseSleeper.REAL).inputSchema();
        assertThat(schema.path("properties").path("response_type").path("enum")).extracting(JsonNode::asText)
                .containsExactly("yes", "no", "enter", "escape", "select", "type");
        assertThat(schema.path("properties").path("arrow_direction").path("enum")).extracting(JsonNode::asText)
                .containsExactly("up", "down");
        assertThat(schema.path("properties").path("arrow_count").path("minimum").asInt()).isZero();
        assertThat(schema.path("properties").path("arrow_count").path("maximum").asInt()).isEqualTo(50);
    }

    @Test
    @DisplayName("open_terminal 的 agent enum 逐字对齐 CCB tools.ts:548（claude/codex/gemini/custom）")
    void openTerminalAgentEnumIsExact() {
        JsonNode schema = new ComputerUseOpenTerminalTool(
                new ComputerUsePhase2bFakes.FakeTerminalLauncher(),
                new ComputerUsePhase2bFakes.RecordingWindowOps(),
                new ComputerUseWindowBindingStore(), ComputerUseSleeper.REAL).inputSchema();
        assertThat(schema.path("properties").path("agent").path("enum")).extracting(JsonNode::asText)
                .containsExactly("claude", "codex", "gemini", "custom");
        assertThat(schema.path("properties").path("terminal").path("enum")).extracting(JsonNode::asText)
                .containsExactly("wt", "powershell", "cmd");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("agent");
    }

    @Test
    @DisplayName("click_element / type_into_element 的选择器字段逐字对齐 CCB（name/role/automationId）")
    void elementToolSelectorsAreExact() {
        ComputerUsePhase2bFakes.FakeUiaService uia = new ComputerUsePhase2bFakes.FakeUiaService();
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();

        JsonNode click = new ComputerUseClickElementTool(uia, ops, store).inputSchema();
        assertThat(click.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("name", "role", "automationId");
        assertThat(click.path("required")).isEmpty();

        JsonNode type = new ComputerUseTypeIntoElementTool(uia, ops, store).inputSchema();
        assertThat(type.path("properties").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("name", "role", "automationId", "text");
        assertThat(type.path("required")).extracting(JsonNode::asText).containsExactly("text");
    }

    // ══════════════════════════════════════════════════════════════════
    // ② 门控接入：未绑定窗口 ⇒ 明确报错
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("未绑定窗口时，窗口定向类工具明确报错（不是静默无动作）")
    void windowTargetedToolsRejectWithoutBinding() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        ComputerUsePhase2bFakes.FakeUiaService uia = new ComputerUsePhase2bFakes.FakeUiaService();

        var wm = new ComputerUseWindowManagementTool(ops, store)
                .execute(new ToolUseBlock("c1", "x", action("minimize")), ctx());
        assertThat(((ToolResult<?>) wm).data().toString()).contains("No window is currently bound");

        var ce = new ComputerUseClickElementTool(uia, ops, store)
                .execute(new ToolUseBlock("c2", "x", JSON.createObjectNode().put("name", "Save")), ctx());
        assertThat(((ToolResult<?>) ce).data().toString()).contains("bound window");

        var te = new ComputerUseTypeIntoElementTool(uia, ops, store)
                .execute(new ToolUseBlock("c3", "x", JSON.createObjectNode().put("text", "hi")), ctx());
        assertThat(((ToolResult<?>) te).data().toString()).contains("bound window");

        var aw = new ComputerUseActivateWindowTool(ops, store)
                .execute(new ToolUseBlock("c4", "x", JSON.createObjectNode()), ctx());
        assertThat(((ToolResult<?>) aw).data().toString()).contains("bound window");

        var pr = new ComputerUsePromptRespondTool(ops, store, ComputerUseSleeper.REAL)
                .execute(new ToolUseBlock("c5", "x", JSON.createObjectNode().put("response_type", "enter")), ctx());
        assertThat(((ToolResult<?>) pr).data().toString()).contains("bound window");

        var vk = new ComputerUseVirtualKeyboardTool(ops, store, ComputerUseSleeper.REAL)
                .execute(new ToolUseBlock("c6", "x", actionText("type", "a")), ctx());
        assertThat(((ToolResult<?>) vk).data().toString()).contains("bound window");

        var vm = new ComputerUseVirtualMouseTool(ops, store, ComputerUseSleeper.REAL)
                .execute(new ToolUseBlock("c7", "x", actionCoord("click", 1, 2)), ctx());
        assertThat(((ToolResult<?>) vm).data().toString()).contains("No window is currently bound");

        var mw = new ComputerUseMouseWheelTool(ops, store)
                .execute(new ToolUseBlock("c8", "x", coordDelta(1, 2, 3)), ctx());
        assertThat(((ToolResult<?>) mw).data().toString()).contains("No window is currently bound");

        // 未绑定 ⇒ 一次都不许真的发消息
        assertThat(ops.calls).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ 坐标换算：窗口定向 ⇒ 客户区坐标
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("virtual_mouse 用客户区坐标点击（坐标不经 scaleCoord —— CCB tools.ts:786-787 明写 client-area）")
    void virtualMouseUsesClientAreaCoordinates() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        var result = new ComputerUseVirtualMouseTool(ops, store, ComputerUseSleeper.REAL)
                .execute(new ToolUseBlock("c1", "x", actionCoord("click", 30, 40)), ctx());

        assertThat(((ToolResult<?>) result).data().toString()).isEqualTo("Click at (30,40)");
        assertThat(ops.clicks).hasSize(1);
        assertThat(ops.clicks.get(0)).containsExactly(30, 40);
    }

    @Test
    @DisplayName("mouse_wheel 用客户区坐标且 delta 保留格数（CCB windowMessage.ts:625 内部才乘 120）")
    void mouseWheelUsesClientAreaCoordinates() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        var result = new ComputerUseMouseWheelTool(ops, store)
                .execute(new ToolUseBlock("c1", "x", coordDelta(11, 22, -4)), ctx());

        assertThat(((ToolResult<?>) result).data().toString())
                .isEqualTo("Mouse wheel: vertical scroll down 4 click(s) at (11,22).");
        assertThat(ops.wheelEvents).hasSize(1);
        assertThat(ops.wheelEvents.get(0)).containsExactly(11, 22, -4, 0);
    }

    @Test
    @DisplayName("activate_window 未给坐标时点窗口中心（CCB executorCrossPlatform.ts:960-965）")
    void activateWindowClicksCenterWithoutCoordinates() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ops.windowRect = new int[]{100, 200, 800, 600};
        ops.clientOffset = new int[]{8, 31};
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        new ComputerUseActivateWindowTool(ops, store)
                .execute(new ToolUseBlock("c1", "x", JSON.createObjectNode()), ctx());

        // 中心 = round(800/2) - dx = 400 - 8 = 392；round(600/2) - dy = 300 - 31 = 269
        assertThat(ops.clicks).hasSize(1);
        assertThat(ops.clicks.get(0)).containsExactly(392, 269);
        assertThat(ops.calls).contains("focusWindow:4242");
    }

    // ══════════════════════════════════════════════════════════════════
    // ④ 修饰键：正序按下 → 动作 → 逆序释放
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("修饰键点击：正序按下 → 点击 → 逆序释放（CCB windowMessage.ts:341-366）")
    void modifierClickPressesInOrderAndReleasesReversed() throws Exception {
        ComputerUsePhase2bFakes.RecordingKeyboard keyboard = new ComputerUsePhase2bFakes.RecordingKeyboard();
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        ComputerUseClickRouter router = new ComputerUseClickRouter(store, null, keyboard);
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);

        var tool = new ComputerUseDoubleClickTool(mouse, geometry,
                ComputerUseCoordinateMode.PIXELS, null, null);

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(10).add(20);
        input.put("text", "ctrl+shift");

        // 直接驱动路由（工具子类不带路由参数 ⇒ 用 router 验证核心语义）
        router.clickGlobal(
                ComputerUseModifierChord.parse("ctrl+shift"),
                () -> mouse.click(10, 20, ComputerUseMouseButton.LEFT, 2));

        assertThat(keyboard.events).containsExactly(
                "down:ctrl", "down:shift", "up:shift", "up:ctrl");
        assertThat(mouse.clicks).hasSize(1);
    }

    @Test
    @DisplayName("修饰键点击：动作抛异常时修饰键仍被逆序释放（异常路径不卡键）")
    void modifierClickReleasesOnFailure() {
        ComputerUsePhase2bFakes.RecordingKeyboard keyboard = new ComputerUsePhase2bFakes.RecordingKeyboard();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        ComputerUseClickRouter router = new ComputerUseClickRouter(store, null, keyboard);

        assertThatThrownBy(() -> router.clickGlobal(
                ComputerUseModifierChord.parse("ctrl+shift"),
                () -> {
                    throw new IllegalStateException("注入的点击失败");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(keyboard.events).containsExactly(
                "down:ctrl", "down:shift", "up:shift", "up:ctrl");
    }

    @Test
    @DisplayName("修饰键解析：只放行修饰键，非修饰键显式报错（fail loud）")
    void modifierChordRejectsNonModifierKeys() {
        List<ComputerUseKeyEvent> parsed = assertDoesNotThrow(
                () -> ComputerUseModifierChord.parse("ctrl+shift"));
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).name()).isEqualTo("ctrl");
        assertThat(parsed.get(1).name()).isEqualTo("shift");

        assertThatThrownBy(() -> ComputerUseModifierChord.parse("ctrl+a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("修饰键");
    }

    @Test
    @DisplayName("修饰键释放顺序 = 按下顺序的逆序（CCB executor.ts:188-197 releasePressed）")
    void modifierReleaseOrderIsReversed() {
        List<ComputerUseKeyEvent> pressed = List.of(
                ComputerUseKeyEvent.named("ctrl", true),
                ComputerUseKeyEvent.named("shift", true),
                ComputerUseKeyEvent.named("alt", true));
        List<ComputerUseKeyEvent> released = ComputerUseModifierChord.releaseOrder(pressed);
        assertThat(released).extracting(ComputerUseKeyEvent::name).containsExactly("alt", "shift", "ctrl");
        assertThat(released).allMatch(e -> !e.down());
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑤ 授权位 / 黑名单
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("系统级组合键作修饰键被拦（无 systemKeyCombos 授权）—— CCB toolCalls.ts:2412-2422")
    void systemKeyComboModifierIsBlockedWithoutGrant() {
        ComputerUsePhase2bFakes.RecordingMouse mouse = new ComputerUsePhase2bFakes.RecordingMouse();
        ComputerUseGeometryStore geometry = ComputerUsePhase2aFakes.identityGeometry(null);
        ComputerUseClickRouter router = new ComputerUseClickRouter(
                new ComputerUseWindowBindingStore(), null, new ComputerUsePhase2bFakes.RecordingKeyboard());

        var tool = new ComputerUseLeftClickTool(
                new ComputerUsePhase2bFakes.FakeCapturer(
                        new ComputerUseDisplayGeometry(0, 1920, 1080, 1.0d, 0, 0)),
                mouse, geometry, ComputerUseCoordinateMode.PIXELS, null, null, null, null, router);

        ObjectNode input = JSON.createObjectNode();
        input.putArray("coordinate").add(10).add(20);
        input.put("text", "alt+f4");

        var result = tool.execute(new ToolUseBlock("c1", "x", input), ctx());
        assertThat(((ToolResult<?>) result).data().toString()).contains("system shortcut");
        assertThat(mouse.clicks).isEmpty();
    }

    @Test
    @DisplayName("绑定的窗口所属应用未授权 ⇒ 窗口定向工具明确拒绝（绑定窗口门控）")
    void boundWindowAppMustBeGranted() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), "terminal", null));

        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(
                SESSION, ComputerUseGrants.empty().toJson());

        String rejection = ComputerUseWindowGate.check(SESSION,
                store.get(SESSION),
                new ComputerUsePhase2bFakes.FixedProcessAppResolver("terminal"),
                grantStore,
                ComputerUseActionKind.MOUSE);

        assertThat(rejection).isNotNull();
        assertThat(rejection).contains("not granted for this session");
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑥ UIA 快照文本格式
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("UIA 快照文本格式逐字对齐 CCB accessibilitySnapshot.ts:170-192")
    void uiaSnapshotTextFormatIsExact() {
        ComputerUseAccessibilityNode button = ComputerUsePhase2bFakes.node(
                "Button", "Save", "btnSave", 120, 50, 80, 30, true, null, List.of());
        ComputerUseAccessibilityNode edit = ComputerUsePhase2bFakes.node(
                "Edit", "", "textBox1", 200, 80, 400, 25, false, "hello world", List.of());

        String text = ComputerUseAccessibilitySnapshot.formatForModel(List.of(button, edit));

        assertThat(text).isEqualTo(
                "[Button] \"Save\" (120,50 80x30) id=btnSave\n"
                        + "[Edit] (200,80 400x25) DISABLED value=\"hello world\" id=textBox1");
    }

    @Test
    @DisplayName("UIA 快照子节点按 2 空格缩进（CCB formatForModel 的 pad）")
    void uiaSnapshotIndentsChildren() {
        ComputerUseAccessibilityNode child = ComputerUsePhase2bFakes.node(
                "Button", "OK", "", 10, 10, 20, 20, true, null, List.of());
        ComputerUseAccessibilityNode parent = ComputerUsePhase2bFakes.node(
                "Window", "Dlg", "", 0, 0, 100, 100, true, null, List.of(child));

        String text = ComputerUseAccessibilitySnapshot.formatForModel(List.of(parent));
        assertThat(text).isEqualTo("[Window] \"Dlg\" (0,0 100x100)\n  [Button] \"OK\" (10,10 20x20)");
    }

    @Test
    @DisplayName("UIA 快照 JSON 解析：'c' 缺失/单对象/数组三态都要吃（PowerShell ConvertTo-Json 的实际形状）")
    void uiaJsonParsingHandlesAllChildShapes() throws Exception {
        // ① 'c' 缺失 ⇒ 无子节点（⛔ 不能用 path("c") 判缺失 —— MissingNode 会导致无限递归）
        List<ComputerUseAccessibilityNode> noChildren =
                Win32UiaService.parseNodes(JSON.readTree("{\"role\":\"Button\",\"name\":\"OK\",\"id\":\"b1\","
                        + "\"x\":1,\"y\":2,\"w\":3,\"h\":4,\"on\":true}"));
        assertThat(noChildren).hasSize(1);
        assertThat(noChildren.get(0).children()).isEmpty();

        // ② 'c' 是<b>单对象</b>（PowerShell 会把单元素数组塌缩成对象）
        List<ComputerUseAccessibilityNode> singleObject =
                Win32UiaService.parseNodes(JSON.readTree(
                        "{\"role\":\"Tab\",\"name\":\"\",\"c\":{\"role\":\"TabItem\",\"name\":\"A\"}}"));
        assertThat(singleObject).hasSize(1);
        assertThat(singleObject.get(0).children()).hasSize(1);
        assertThat(singleObject.get(0).children().get(0).name()).isEqualTo("A");

        // ③ 'c' 是真数组
        List<ComputerUseAccessibilityNode> array =
                Win32UiaService.parseNodes(JSON.readTree(
                        "{\"role\":\"Tab\",\"c\":[{\"role\":\"A\"},{\"role\":\"B\"}]}"));
        assertThat(array.get(0).children()).hasSize(2);
        assertThat(array.get(0).children().get(1).role()).isEqualTo("B");

        // ④ 顶层是数组（CCB 的 parseNode 对数组与单对象都处理）
        List<ComputerUseAccessibilityNode> rootArray =
                Win32UiaService.parseNodes(JSON.readTree("[{\"role\":\"A\"},{\"role\":\"B\"}]"));
        assertThat(rootArray).hasSize(2);
    }

    @Test
    @DisplayName("UIA 快照：name 部分匹配 / role 完全匹配 / automationId 完全匹配（CCB findNodeInSnapshot）")
    void uiaNodeFindingSemanticsAreExact() {
        ComputerUseAccessibilityNode node = ComputerUsePhase2bFakes.node(
                "Button", "Save File", "btnSave", 0, 0, 10, 10, true, null, List.of());
        List<ComputerUseAccessibilityNode> nodes = List.of(node);

        // name：大小写不敏感的部分匹配
        assertThat(ComputerUseAccessibilitySnapshot.findNode(nodes, "save", null, null)).isSameAs(node);
        assertThat(ComputerUseAccessibilitySnapshot.findNode(nodes, "FILE", null, null)).isSameAs(node);
        // role：大小写不敏感但必须完全匹配
        assertThat(ComputerUseAccessibilitySnapshot.findNode(nodes, null, "button", null)).isSameAs(node);
        assertThat(ComputerUseAccessibilitySnapshot.findNode(nodes, null, "but", null)).isNull();
        // automationId：完全匹配
        assertThat(ComputerUseAccessibilitySnapshot.findNode(nodes, null, null, "btnSave")).isSameAs(node);
        assertThat(ComputerUseAccessibilitySnapshot.findNode(nodes, null, null, "btnSave2")).isNull();
        // 一个条件都不给 ⇒ 不匹配
        assertThat(ComputerUseAccessibilitySnapshot.findNode(nodes, null, null, null)).isNull();
    }

    @Test
    @DisplayName("click_element：优先 InvokePattern，失败才回落 BoundingRect 中心点击（CCB executorCrossPlatform.ts:1063-1084）")
    void clickElementPrefersInvokePattern() {
        ComputerUsePhase2bFakes.FakeUiaService uia = new ComputerUsePhase2bFakes.FakeUiaService();
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        uia.snapshot = ComputerUsePhase2bFakes.snapshotOf(
                ComputerUsePhase2bFakes.node("Button", "Save", "btnSave", 120, 50, 80, 30, true, null, List.of()));

        // 路径 1：InvokePattern 成功 ⇒ 不发坐标点击
        uia.invokeResult = true;
        var result = new ComputerUseClickElementTool(uia, ops, store)
                .execute(new ToolUseBlock("c1", "x", JSON.createObjectNode().put("name", "Save")), ctx());
        assertThat(((ToolResult<?>) result).data().toString()).isEqualTo("Clicked element: \"Save\"");
        assertThat(uia.invoked).containsExactly("btnSave");
        assertThat(ops.clicks).isEmpty();

        // 路径 2：InvokePattern 不支持 ⇒ 回落 BoundingRect 中心 + 窗口定向点击
        uia.invokeResult = false;
        new ComputerUseClickElementTool(uia, ops, store)
                .execute(new ToolUseBlock("c2", "x", JSON.createObjectNode().put("name", "Save")), ctx());
        assertThat(ops.clicks).hasSize(1);
        assertThat(ops.clicks.get(0)).containsExactly(160, 65);   // (120+40, 50+15)
    }

    @Test
    @DisplayName("click_element 找不到元素 ⇒ 明确报错（不猜、不盲点）")
    void clickElementReportsNotFound() {
        ComputerUsePhase2bFakes.FakeUiaService uia = new ComputerUsePhase2bFakes.FakeUiaService();
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        var result = new ComputerUseClickElementTool(uia, ops, store)
                .execute(new ToolUseBlock("c1", "x", JSON.createObjectNode().put("name", "Nope")), ctx());
        assertThat(((ToolResult<?>) result).data().toString()).contains("Element not found");
        assertThat(ops.clicks).isEmpty();
    }

    @Test
    @DisplayName("UIA 通道异常必须明确报错（不静默当空树）")
    void uiaFailureIsLoud() {
        ComputerUsePhase2bFakes.FakeUiaService uia = new ComputerUsePhase2bFakes.FakeUiaService();
        uia.failWith = new IllegalStateException("UIA 快照 JSON 解析失败: xxx");
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        var result = new ComputerUseClickElementTool(uia, ops, store)
                .execute(new ToolUseBlock("c1", "x", JSON.createObjectNode().put("name", "Save")), ctx());
        assertThat(((ToolResult<?>) result).data().toString()).contains("元素点击失败");
        assertThat(ops.clicks).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑦ 绑定 / 解绑 / 窗口管理
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("bind_window：按标题部分匹配绑定（大小写不敏感）并冻结授权键/档位")
    void bindWindowMatchesTitleCaseInsensitively() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ops.windows.add(ComputerUsePhase2bFakes.window(4242L, 100, "Windows Terminal"));
        ops.windows.add(ComputerUsePhase2bFakes.window(4343L, 200, "Notepad"));
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                "{\"apps\":[{\"appId\":\"WindowsTerminal\",\"displayName\":\"Windows Terminal\","
                        + "\"grantedAt\":1750000000000,\"tier\":\"full\"}],"
                        + "\"flags\":{\"clipboardRead\":false,\"clipboardWrite\":false,\"systemKeyCombos\":false}}");

        var tool = new ComputerUseBindWindowTool(ops, store,
                new ComputerUsePhase2bFakes.FixedProcessAppResolver("WindowsTerminal"), grantStore);

        var result = tool.execute(new ToolUseBlock("c1", "x",
                JSON.createObjectNode().put("action", "bind").put("title", "terminal")), ctx());

        assertThat(((ToolResult<?>) result).data().toString()).contains("Bound to window: hwnd=4242");
        ComputerUseWindowBinding binding = store.get(SESSION);
        assertThat(binding).isNotNull();
        assertThat(binding.hwnd()).isEqualTo(4242L);
        assertThat(binding.appId()).isEqualTo("WindowsTerminal");
        assertThat(binding.tier()).isEqualTo("full");
    }

    @Test
    @DisplayName("bind_window：unbind 解除绑定 ⇒ 后续回到全屏模式")
    void bindWindowUnbindClearsBinding() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        var tool = new ComputerUseBindWindowTool(ops, store, null, null);
        var result = tool.execute(new ToolUseBlock("c1", "x",
                JSON.createObjectNode().put("action", "unbind")), ctx());

        assertThat(((ToolResult<?>) result).data().toString())
                .isEqualTo("Window binding released. Operations now target the full screen.");
        assertThat(store.get(SESSION)).isNull();
    }

    @Test
    @DisplayName("window_management：close 走 WM_CLOSE 并解除绑定（CCB platforms/win32.ts:876-883）")
    void windowManagementCloseUnbinds() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        var result = new ComputerUseWindowManagementTool(ops, store)
                .execute(new ToolUseBlock("c1", "x", action("close")), ctx());

        assertThat(((ToolResult<?>) result).data().toString())
                .isEqualTo("Window closed (SendMessage WM_CLOSE). The window binding has been released.");
        assertThat(ops.calls).contains("closeWindow:4242");
        assertThat(store.get(SESSION)).isNull();
    }

    @Test
    @DisplayName("window_management：minimize 用 SW_SHOWMINNOACTIVE（CCB platforms/win32.ts:858-862 的实际行为）")
    void windowManagementMinimizeUsesShowMinNoActive() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        new ComputerUseWindowManagementTool(ops, store)
                .execute(new ToolUseBlock("c1", "x", action("minimize")), ctx());

        assertThat(ops.calls).contains("showWindow:4242:" + ComputerUseWindowOps.SW_SHOWMINNOACTIVE);
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑧ 提示应答 / 浮层 / 终端
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("prompt_respond：yes 发 'y' + Enter（CCB executorCrossPlatform.ts:995-999）")
    void promptRespondYesSendsYThenEnter() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        var result = new ComputerUsePromptRespondTool(ops, store, ComputerUseSleeper.REAL)
                .execute(new ToolUseBlock("c1", "x",
                        JSON.createObjectNode().put("response_type", "yes")), ctx());

        assertThat(((ToolResult<?>) result).data().toString())
                .isEqualTo("Prompt responded: Sent 'y' + Enter.. Take a screenshot to verify.");
        assertThat(ops.texts).containsExactly("y");
        // Enter = VK_RETURN(0x0d) 的 down + up
        assertThat(ops.keyEvents).containsExactly("down:13", "up:13");
    }

    @Test
    @DisplayName("prompt_respond：select 发 N 次方向键 + Enter（CCB executorCrossPlatform.ts:1015-1027）")
    void promptRespondSelectSendsArrowsThenEnter() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        var result = new ComputerUsePromptRespondTool(ops, store, ComputerUseSleeper.REAL)
                .execute(new ToolUseBlock("c1", "x", JSON.createObjectNode()
                        .put("response_type", "select").put("arrow_count", 2)), ctx());

        assertThat(((ToolResult<?>) result).data().toString())
                .isEqualTo("Prompt responded: Navigated down 2 time(s) + Enter.. Take a screenshot to verify.");
        // VK_DOWN = 0x28，两次 down/up，最后 Enter
        assertThat(ops.keyEvents).containsExactly(
                "down:40", "up:40", "down:40", "up:40", "down:13", "up:13");
    }

    @Test
    @DisplayName("status_indicator：show 需 message，status 反映绑定窗口，hide 关浮层")
    void statusIndicatorActionsBehave() {
        ComputerUsePhase2bFakes.FakeStatusIndicator indicator = new ComputerUsePhase2bFakes.FakeStatusIndicator();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        var tool = new ComputerUseStatusIndicatorTool(indicator, store);

        // 无绑定 ⇒ status 报「未激活」
        var noBind = tool.execute(new ToolUseBlock("c1", "x",
                JSON.createObjectNode().put("action", "status")), ctx());
        assertThat(((ToolResult<?>) noBind).data().toString())
                .isEqualTo("Indicator is not active (no window bound).");

        // show 缺 message ⇒ 明确报错
        var missing = tool.execute(new ToolUseBlock("c2", "x",
                JSON.createObjectNode().put("action", "show")), ctx());
        assertThat(((ToolResult<?>) missing).data().toString()).contains("requires a message parameter");

        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));

        var shown = tool.execute(new ToolUseBlock("c3", "x", JSON.createObjectNode()
                .put("action", "show").put("message", "Typing...")), ctx());
        assertThat(((ToolResult<?>) shown).data().toString())
                .isEqualTo("Indicator showing: \"Typing...\"");
        assertThat(indicator.shown).containsExactly("4242:Typing...");

        var status = tool.execute(new ToolUseBlock("c4", "x",
                JSON.createObjectNode().put("action", "status")), ctx());
        assertThat(((ToolResult<?>) status).data().toString())
                .isEqualTo("Indicator is active on the bound window.");

        var hidden = tool.execute(new ToolUseBlock("c5", "x",
                JSON.createObjectNode().put("action", "hide")), ctx());
        assertThat(((ToolResult<?>) hidden).data().toString()).isEqualTo("Indicator hidden.");
        assertThat(indicator.hideCount).isEqualTo(1);
    }

    @Test
    @DisplayName("open_terminal：启动终端 → 等到新窗口 → 绑定它（CCB executorCrossPlatform.ts:616-644）")
    void openTerminalBindsNewWindow() {
        ComputerUsePhase2bFakes.FakeTerminalLauncher launcher = new ComputerUsePhase2bFakes.FakeTerminalLauncher();
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        launcher.target = ops;
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        // 假时钟 ⇒ 轮询不真的睡 5 秒（单测零耗时）
        var tool = new ComputerUseOpenTerminalTool(launcher, ops, store,
                new ComputerUsePhase2aFakes.RecordingSleeper());

        // 启动前已有旧窗口；启动后<b>不</b>出现新窗口 ⇒ 轮询 5 秒后明确报错
        ops.windows.add(ComputerUsePhase2bFakes.window(111L, 1, "Old"));
        launcher.newWindow = null;
        var missing = tool.execute(new ToolUseBlock("c1", "x",
                JSON.createObjectNode().put("agent", "claude")), ctx());
        assertThat(((ToolResult<?>) missing).data().toString()).contains("Terminal window not detected");
        // 非 custom agent ⇒ command 传 null（CCB executorCrossPlatform.ts:591-596 的 agentCmd 映射）
        assertThat(launcher.calls).containsExactly("claude|null|wt|null");

        // 启动后新窗口出现 ⇒ 绑定
        launcher.newWindow = ComputerUsePhase2bFakes.window(222L, 2, "Claude Code");
        var result = tool.execute(new ToolUseBlock("c2", "x",
                JSON.createObjectNode().put("agent", "claude")), ctx());
        assertThat(((ToolResult<?>) result).data().toString()).contains("Terminal opened and Claude Code launched.");
        assertThat(store.get(SESSION)).isNotNull();
        assertThat(store.get(SESSION).hwnd()).isEqualTo(222L);
    }

    @Test
    @DisplayName("open_terminal：agent=custom 必须带 command（CCB toolCalls.ts:3182-3184）")
    void openTerminalCustomRequiresCommand() {
        ComputerUsePhase2bFakes.FakeTerminalLauncher launcher = new ComputerUsePhase2bFakes.FakeTerminalLauncher();
        var tool = new ComputerUseOpenTerminalTool(launcher,
                new ComputerUsePhase2bFakes.RecordingWindowOps(),
                new ComputerUseWindowBindingStore(), ComputerUseSleeper.REAL);

        var result = tool.execute(new ToolUseBlock("c1", "x",
                JSON.createObjectNode().put("agent", "custom")), ctx());
        assertThat(((ToolResult<?>) result).data().toString()).contains("requires 'command' parameter");
        assertThat(launcher.calls).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑨ 窗口定向 vs 全局注入的选择逻辑
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("点击路由：有绑定 ⇒ 窗口定向（不动真实鼠标）；无绑定 ⇒ 全局注入")
    void clickRoutingChoosesWindowDirectedWhenBound() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ops.clientOrigin = new int[]{100, 200};
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        ComputerUseClickRouter router = new ComputerUseClickRouter(store, ops, null);

        // 无绑定 ⇒ 全局注入
        assertThat(router.isWindowTargeted(SESSION)).isFalse();

        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));
        assertThat(router.isWindowTargeted(SESSION)).isTrue();

        router.clickWindowDirected(SESSION, 300, 500, false, 1, List.of());
        // 屏幕 (300,500) → 客户区 (300-100, 500-200) = (200,300)
        assertThat(ops.clicks).hasSize(1);
        assertThat(ops.clicks.get(0)).containsExactly(200, 300);
    }

    @Test
    @DisplayName("窗口定向点击：修饰键先按下再点击、最后逆序释放")
    void windowDirectedClickHoldsModifiers() {
        ComputerUsePhase2bFakes.RecordingWindowOps ops = new ComputerUsePhase2bFakes.RecordingWindowOps();
        ComputerUseWindowBindingStore store = new ComputerUseWindowBindingStore();
        store.bind(SESSION, new ComputerUseWindowBinding(
                ComputerUsePhase2bFakes.window(4242L, 100, "Terminal"), null, null));
        ComputerUseClickRouter router = new ComputerUseClickRouter(store, ops, null);

        router.clickWindowDirected(SESSION, 10, 20, false, 1,
                List.of(ComputerUseKeyEvent.named("ctrl", true), ComputerUseKeyEvent.named("shift", true)));

        assertThat(ops.keyEvents).containsExactly(
                "down:17", "down:16", "up:16", "up:17");
        assertThat(ops.clicks).hasSize(1);
    }

    // ── 小工具 ──

    private static ObjectNode action(String name) {
        return JSON.createObjectNode().put("action", name);
    }

    /** 造一个 {"action": ..., "coordinate": [x, y]} 入参。 */
    private static ObjectNode actionCoord(String action, int x, int y) {
        ObjectNode node = JSON.createObjectNode();
        node.put("action", action);
        node.putArray("coordinate").add(x).add(y);
        return node;
    }

    /** 造一个 {"action": ..., "text": ...} 入参。 */
    private static ObjectNode actionText(String action, String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("action", action);
        node.put("text", text);
        return node;
    }

    /** 造一个 {"coordinate": [x, y], "delta": d} 入参。 */
    private static ObjectNode coordDelta(int x, int y, int delta) {
        ObjectNode node = JSON.createObjectNode();
        node.putArray("coordinate").add(x).add(y);
        node.put("delta", delta);
        return node;
    }
}
