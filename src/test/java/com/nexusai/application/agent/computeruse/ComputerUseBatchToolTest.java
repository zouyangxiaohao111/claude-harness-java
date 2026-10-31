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
 * {@code computer_batch} 单测（Phase 2a）。
 *
 * <p><b>WHY（规则 9）</b>：
 * <ul>
 *   <li><b>先全量校验再执行</b>：CCB {@code toolCalls.ts:3931-3946} ——
 *       若「边校验边执行」，模型给错一个 action 名就会<b>先点了一下再报错</b>
 *       （用户屏幕被误触）⇒ 断言「校验失败 ⇒ 一个动作都没执行」；</li>
 *   <li><b>遇错即停并回报进度</b>：CCB {@code toolCalls.ts:4008-4025} ⇒
 *       返回 {@code {completed, failed, remaining}} ⇒ 断言这三者；</li>
 *   <li><b>批内截图不改坐标基准</b>：CCB {@code toolCalls.ts:3994-4002} ⇒
 *       断言批内截图后，后续点击仍相对<b>批前</b>的全屏截图。</li>
 * </ul>
 */
class ComputerUseBatchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-1";

    private static ObjectNode action(String name) {
        ObjectNode node = JSON.createObjectNode();
        node.put("action", name);
        return node;
    }

    private static ObjectNode batch(ObjectNode... actions) {
        ObjectNode node = JSON.createObjectNode();
        ArrayNode arr = node.putArray("actions");
        for (ObjectNode a : actions) {
            arr.add(a);
        }
        return node;
    }

    private static ObjectNode clickAction(double x, double y) {
        ObjectNode node = action("left_click");
        ArrayNode coord = node.putArray("coordinate");
        coord.add(x);
        coord.add(y);
        return node;
    }

    private static ComputerUseBatchTool tool(ComputerUsePhase2aFakes.RecordingMouseActions mouse,
                                             ComputerUsePhase2aFakes.FakeKeyboard keyboard,
                                             ComputerUseGeometryStore geometry,
                                             ComputerUseGrantStore grantStore,
                                             ComputerUseMouseState state,
                                             ComputerUsePhase2aFakes.FakeCapturer capturer) {
        ComputerUsePhase2aFakes.FakeCursor cursor = new ComputerUsePhase2aFakes.FakeCursor();
        ComputerUseMouseAnimator animator = new ComputerUseMouseAnimator(cursor,
                new ComputerUsePhase2aFakes.RecordingSleeper(), false);
        return new ComputerUseBatchTool(keyboard, mouse, cursor, animator,
                capturer == null ? new ComputerUsePhase2aFakes.FakeCapturer() : capturer,
                geometry, ComputerUseCoordinateMode.PIXELS, grantStore,
                new ComputerUsePhase2aFakes.FixedResolver("notepad"), state,
                new ComputerUsePhase2aFakes.RecordingSleeper());
    }

    @Test
    @DisplayName("工具名 / 描述逐字对齐 CCB tools.ts:968-974")
    void nameAndDescriptionAreVerbatim() {
        ComputerUseBatchTool t = tool(new ComputerUsePhase2aFakes.RecordingMouseActions(),
                new ComputerUsePhase2aFakes.FakeKeyboard(), new ComputerUseGeometryStore(),
                null, new ComputerUseMouseState(), null);

        assertThat(t.name()).isEqualTo("mcp__computer-use__computer_batch");
        assertThat(t.description()).isEqualTo(
                "Execute a sequence of actions in ONE tool call. "
                        + "Each individual tool call requires a model→API round trip (seconds); "
                        + "batching a predictable sequence eliminates all but one. "
                        + "Use this whenever you can predict the outcome of several actions ahead — "
                        + "e.g. click a field, type into it, press Return. "
                        + "Actions execute sequentially and stop on the first error. "
                        + "The frontmost application must be in the session allowlist at the time of this call, "
                        + "or this tool returns an error and does nothing. "
                        + "The frontmost check runs before EACH action inside the batch — "
                        + "if an action opens a non-allowed app, the next action's gate fires and the batch stops there. "
                        + "Mid-batch screenshot actions are allowed for inspection but coordinates in subsequent clicks "
                        + "always refer to the PRE-BATCH full-screen screenshot.");
    }

    @Test
    @DisplayName("schema 逐字对齐 CCB tools.ts:975-986 + 40-103（16 个可批动作的 enum）")
    void schemaIsVerbatim() {
        ComputerUseBatchTool t = tool(new ComputerUsePhase2aFakes.RecordingMouseActions(),
                new ComputerUsePhase2aFakes.FakeKeyboard(), new ComputerUseGeometryStore(),
                null, new ComputerUseMouseState(), null);
        JsonNode schema = t.inputSchema();

        assertThat(schema.path("type").asText()).isEqualTo("object");
        JsonNode actions = schema.path("properties").path("actions");
        assertThat(actions.path("type").asText()).isEqualTo("array");
        assertThat(actions.path("minItems").asInt()).isEqualTo(1);
        JsonNode item = actions.path("items");
        JsonNode allowed = item.path("properties").path("action").path("enum");
        assertThat(allowed).hasSize(16);
        assertThat(allowed.get(0).asText()).isEqualTo("key");
        assertThat(allowed.get(15).asText()).isEqualTo("wait");
        assertThat(item.path("required").get(0).asText()).isEqualTo("action");
        assertThat(item.path("properties").path("coordinate").path("description").asText())
                .isEqualTo("(x, y) for click/mouse_move/scroll/left_click_drag end point.");
        assertThat(item.path("properties").path("start_coordinate").path("description").asText())
                .isEqualTo("(x, y) drag start — left_click_drag only. Omit to drag from current cursor.");
        assertThat(item.path("properties").path("text").path("description").asText())
                .isEqualTo("For type: the text. For key/hold_key: the chord string. "
                        + "For click/scroll: modifier keys to hold.");
        assertThat(item.path("properties").path("scroll_amount").path("minimum").asInt()).isZero();
        assertThat(item.path("properties").path("scroll_amount").path("maximum").asInt()).isEqualTo(100);
        assertThat(item.path("properties").path("repeat").path("minimum").asInt()).isEqualTo(1);
        assertThat(item.path("properties").path("repeat").path("maximum").asInt()).isEqualTo(100);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("actions");
        assertThat(schema.path("required")).hasSize(1);
    }

    @Test
    @DisplayName("actions 为空 ⇒ 报错（CCB toolCalls.ts:3931-3934）")
    void emptyActionsIsRejected() {
        ComputerUseBatchTool t = tool(new ComputerUsePhase2aFakes.RecordingMouseActions(),
                new ComputerUsePhase2aFakes.FakeKeyboard(), new ComputerUseGeometryStore(),
                null, new ComputerUseMouseState(), null);

        var result = t.execute(new ToolUseBlock("c1", t.name(), batch()));

        assertThat(((ToolResult<?>) result).data().toString()).contains("actions must be a non-empty array");
    }

    @Test
    @DisplayName("动作名不在可批集合 ⇒ 报错，且一个动作都不执行")
    void unknownActionIsRejectedBeforeAnyExecution() {
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseBatchTool t = tool(mouse, new ComputerUsePhase2aFakes.FakeKeyboard(),
                ComputerUsePhase2aFakes.identityGeometry(SESSION), null, new ComputerUseMouseState(), null);

        var result = t.execute(new ToolUseBlock("c1", t.name(),
                batch(clickAction(10, 20), action("zoom"))),
                ToolUseContext.of(null, SESSION));

        assertThat(((ToolResult<?>) result).data().toString()).contains("is not allowed in a batch");
        assertThat(mouse.clicks).as("⛔ 校验失败 ⇒ 不得执行任何动作（用户屏幕不得被误触）").isEmpty();
    }

    @Test
    @DisplayName("顺序执行多个动作并全部成功")
    void actionsRunSequentially() {
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUsePhase2aFakes.FakeKeyboard keyboard = new ComputerUsePhase2aFakes.FakeKeyboard();
        ComputerUseBatchTool t = tool(mouse, keyboard, ComputerUsePhase2aFakes.identityGeometry(SESSION),
                null, new ComputerUseMouseState(), null);

        ObjectNode typeAction = action("type");
        typeAction.put("text", "hi");

        var result = t.execute(new ToolUseBlock("c1", t.name(),
                batch(clickAction(10, 20), typeAction)),
                ToolUseContext.of(null, SESSION));

        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(data.path("completed")).hasSize(2);
        assertThat(data.path("completed").get(0).path("action").asText()).isEqualTo("left_click");
        assertThat(data.path("completed").get(1).path("action").asText()).isEqualTo("type");
        assertThat(mouse.clicks).hasSize(1);
        assertThat(keyboard.typed).containsExactly((int) 'h', (int) 'i');
    }

    @Test
    @DisplayName("遇错即停 ⇒ 返回 completed / failed / remaining（CCB toolCalls.ts:4008-4025）")
    void stopsOnFirstErrorAndReportsProgress() {
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseBatchTool t = tool(mouse, new ComputerUsePhase2aFakes.FakeKeyboard(),
                ComputerUsePhase2aFakes.identityGeometry(SESSION), null, new ComputerUseMouseState(), null);

        // 第 2 步坐标缺失 ⇒ 失败
        var result = t.execute(new ToolUseBlock("c1", t.name(),
                batch(clickAction(10, 20), action("left_click"), clickAction(30, 40))),
                ToolUseContext.of(null, SESSION));

        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(data.path("completed")).hasSize(1);
        assertThat(data.path("failed").path("action").asText()).isEqualTo("left_click");
        assertThat(data.path("failed").path("ok").asBoolean()).isFalse();
        assertThat(data.path("remaining").asInt()).isEqualTo(1);
        assertThat(mouse.clicks).as("⛔ 失败后不得继续执行剩余动作").hasSize(1);
    }

    @Test
    @DisplayName("批内截图不改坐标基准（CCB toolCalls.ts:3994-4002）")
    void batchScreenshotKeepsPreBatchCoordinateBasis() {
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUsePhase2aFakes.FakeCapturer capturer = new ComputerUsePhase2aFakes.FakeCapturer();
        ComputerUseGeometryStore geometry = new ComputerUseGeometryStore();
        geometry.put(SESSION, new ComputerUseScreenshotGeometry(1456, 819, 1920, 1080, 0, 0));
        ComputerUseBatchTool t = tool(mouse, new ComputerUsePhase2aFakes.FakeKeyboard(),
                geometry, null, new ComputerUseMouseState(), capturer);

        t.execute(new ToolUseBlock("c1", t.name(),
                batch(action("screenshot"), clickAction(728, 409.5d))),
                ToolUseContext.of(null, SESSION));

        // 批内截图后，后续点击仍按【批前】快照换算 ⇒ 仍是 (960, 540)
        assertThat(mouse.clicks).hasSize(1);
        assertThat(mouse.clicks.get(0)).containsExactly(960, 540);
        // 批内截图确实发生了（capturer.capture 被调用）
        assertThat(capturer.captureCount).isEqualTo(1);
    }

    @Test
    @DisplayName("批内动作同样受档位门控（授权是会话级，不因批量放宽）")
    void batchActionsAreGated() {
        ComputerUseGrantStore grantStore = ComputerUsePhase2aFakes.grantStore(SESSION,
                new ComputerUseGrants(List.of(new ComputerUseGrant("notepad", "记事本", 1L, "read")),
                        ComputerUseGrantFlags.DEFAULT).toJson());
        ComputerUsePhase2aFakes.RecordingMouseActions mouse = new ComputerUsePhase2aFakes.RecordingMouseActions();
        ComputerUseBatchTool t = tool(mouse, new ComputerUsePhase2aFakes.FakeKeyboard(),
                ComputerUsePhase2aFakes.identityGeometry(SESSION), grantStore,
                new ComputerUseMouseState(), null);

        var result = t.execute(new ToolUseBlock("c1", t.name(), batch(clickAction(10, 20))),
                ToolUseContext.of(null, SESSION));

        JsonNode data = (JsonNode) ((ToolResult<?>) result).data();
        assertThat(data.path("failed").path("output").asText()).contains("tier");
        assertThat(mouse.clicks).isEmpty();
    }
}
