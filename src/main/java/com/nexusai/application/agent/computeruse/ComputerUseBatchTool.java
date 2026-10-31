package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code mcp__computer-use__computer_batch} · 批量动作工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:967-988}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:968-987}）</h2>
 * <pre>
 * name: 'computer_batch',
 * description: 'Execute a sequence of actions in ONE tool call. Each individual tool call requires a model→API round trip (seconds); batching a predictable sequence eliminates all but one. Use this whenever you can predict the outcome of several actions ahead — e.g. click a field, type into it, press Return. Actions execute sequentially and stop on the first error. ' +
 *   `${FRONTMOST_GATE_DESC} The frontmost check runs before EACH action inside the batch — if an action opens a non-allowed app, the next action's gate fires and the batch stops there. ` +
 *   'Mid-batch screenshot actions are allowed for inspection but coordinates in subsequent clicks always refer to the PRE-BATCH full-screen screenshot.',
 * inputSchema: {
 *   type: 'object',
 *   properties: {
 *     actions: { type: 'array', minItems: 1, items: BATCH_ACTION_ITEM_SCHEMA, description: 'List of actions. Example: [{"action":"left_click","coordinate":[100,200]},{"action":"type","text":"hello"},{"action":"key","text":"Return"}]' },
 *   },
 *   required: ['actions'],
 * }
 * </pre>
 *
 * <h2>批内动作项的 schema（逐字对齐 CCB {@code tools.ts:40-103} 的
 * {@code BATCH_ACTION_ITEM_SCHEMA}）</h2>
 * <pre>
 * {
 *   type: 'object',
 *   properties: {
 *     action: { type: 'string', enum: [key, type, mouse_move, left_click, left_click_drag, right_click, middle_click, double_click, triple_click, scroll, hold_key, screenshot, cursor_position, left_mouse_down, left_mouse_up, wait], description: 'The action to perform.' },
 *     coordinate: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2, description: '(x, y) for click/mouse_move/scroll/left_click_drag end point.' },
 *     start_coordinate: { type: 'array', items: { type: 'number' }, minItems: 2, maxItems: 2, description: '(x, y) drag start — left_click_drag only. Omit to drag from current cursor.' },
 *     text: { type: 'string', description: 'For type: the text. For key/hold_key: the chord string. For click/scroll: modifier keys to hold.' },
 *     scroll_direction: { type: 'string', enum: ['up','down','left','right'] },
 *     scroll_amount: { type: 'integer', minimum: 0, maximum: 100 },
 *     duration: { type: 'number', description: 'Seconds (0–100). For hold_key/wait.' },
 *     repeat: { type: 'integer', minimum: 1, maximum: 100, description: 'For key: repeat count.' },
 *   },
 *   required: ['action'],
 * }
 * </pre>
 *
 * <h2>失败语义（逐字对齐 CCB {@code toolCalls.ts:3920-4029}）</h2>
 * <ol>
 *   <li><b>先全量校验</b>（CCB {@code toolCalls.ts:3931-3946}）：
 *       <ul>
 *         <li>{@code actions} 必须是非空数组 ⇒ {@code 'actions must be a non-empty array'}；</li>
 *         <li>每一项必须是对象 ⇒ {@code `actions[${i}] must be an object`}；</li>
 *         <li>{@code action} 必须是字符串 ⇒ {@code `actions[${i}].action must be a string`}；</li>
 *         <li>{@code action} 必须在可批动作集合内 ⇒
 *             {@code `actions[${i}].action="${action}" is not allowed in a batch. Allowed: ...`}。</li>
 *       </ul></li>
 *   <li><b>顺序执行、遇错即停</b>（CCB {@code toolCalls.ts:3974-4025}）：
 *       第一步失败 ⇒ 停止，返回 {@code {completed: [...], failed: {...}, remaining: N}}；</li>
 *   <li><b>批内截图不改坐标基准</b>（CCB {@code toolCalls.ts:3994-4002} 的
 *       {@code const { screenshot: _dropped, ...inner } = await dispatchAction(...)}）：
 *       批内 {@code screenshot} 的结果<b>不</b>更新会话的「上次整屏截图」⇒
 *       后续点击的坐标仍相对<b>批前</b>的那张全屏截图。</li>
 *   <li><b>步骤间 10ms 间隔</b>（CCB {@code toolCalls.ts:3989}：{@code if (i > 0) await sleep(10)}）——
 *       给前一步的输入留出被应用处理的时间。</li>
 * </ol>
 *
 * <h2>⚠️ 与 CCB 的<b>两处路线差异</b>（如实标注）</h2>
 * <ul>
 *   <li><b>批内截图不返回图片</b>：CCB 的批结果只取每个动作的<b>首个文本内容</b>
 *       （{@code toolCalls.ts:4004} 的 {@code firstTextContent(inner)}）⇒ 图片内容被丢弃。
 *       本仓照此（批结果只回文本摘要）。</li>
 *   <li><b>点击的修饰键</b>：CCB 支持点击时按住修饰键；本仓的点击工具（既有
 *       {@code left_click} 与本批新增的变体）<b>一律显式拒绝</b> {@code text} 修饰键
 *       （fail loud）⇒ 批内点击带 {@code text} 同样报错（保持与单个工具一致）。</li>
 * </ul>
 */
public final class ComputerUseBatchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseBatchTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__computer_batch";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:970-974}。 */
    private static final String DESCRIPTION =
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
                    + "always refer to the PRE-BATCH full-screen screenshot.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:975-986} + {@code tools.ts:40-103}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "actions": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "action": {
                        "type": "string",
                        "enum": [
                          "key",
                          "type",
                          "mouse_move",
                          "left_click",
                          "left_click_drag",
                          "right_click",
                          "middle_click",
                          "double_click",
                          "triple_click",
                          "scroll",
                          "hold_key",
                          "screenshot",
                          "cursor_position",
                          "left_mouse_down",
                          "left_mouse_up",
                          "wait"
                        ],
                        "description": "The action to perform."
                      },
                      "coordinate": {
                        "type": "array",
                        "items": { "type": "number" },
                        "minItems": 2,
                        "maxItems": 2,
                        "description": "(x, y) for click/mouse_move/scroll/left_click_drag end point."
                      },
                      "start_coordinate": {
                        "type": "array",
                        "items": { "type": "number" },
                        "minItems": 2,
                        "maxItems": 2,
                        "description": "(x, y) drag start — left_click_drag only. Omit to drag from current cursor."
                      },
                      "text": {
                        "type": "string",
                        "description": "For type: the text. For key/hold_key: the chord string. For click/scroll: modifier keys to hold."
                      },
                      "scroll_direction": {
                        "type": "string",
                        "enum": ["up", "down", "left", "right"]
                      },
                      "scroll_amount": {
                        "type": "integer",
                        "minimum": 0,
                        "maximum": 100
                      },
                      "duration": {
                        "type": "number",
                        "description": "Seconds (0–100). For hold_key/wait."
                      },
                      "repeat": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 100,
                        "description": "For key: repeat count."
                      }
                    },
                    "required": ["action"]
                  },
                  "description": "List of actions. Example: [{\\"action\\":\\"left_click\\",\\"coordinate\\":[100,200]},{\\"action\\":\\"type\\",\\"text\\":\\"hello\\"},{\\"action\\":\\"key\\",\\"text\\":\\"Return\\"}]"
                }
              },
              "required": ["actions"]
            }
            """;

    /** 可批动作集合 · 逐字对齐 CCB {@code toolCalls.ts:3865-3882} 的 {@code BATCHABLE_ACTIONS}。 */
    static final List<String> BATCHABLE_ACTIONS = List.of(
            "key", "type", "mouse_move", "left_click", "left_click_drag", "right_click",
            "middle_click", "double_click", "triple_click", "scroll", "hold_key",
            "screenshot", "cursor_position", "left_mouse_down", "left_mouse_up", "wait");

    /** 步骤间隔（毫秒）· CCB {@code toolCalls.ts:3989}（{@code await sleep(10)}）。 */
    static final long STEP_SETTLE_MS = 10L;

    /** 字形簇间隔（毫秒）· CCB {@code toolCalls.ts:2558}（{@code INTER_GRAPHEME_SLEEP_MS}）。 */
    static final long INTER_GRAPHEME_SLEEP_MS = 8L;

    /** 按键重复间隔（毫秒）· CCB {@code toolCalls.ts:242}（{@code await sleep(8)}）。 */
    static final long REPEAT_INTERVAL_MS = 8L;

    private final ComputerUseKeyboard keyboard;
    private final ComputerUseMouseActions mouse;
    private final ComputerUseCursor cursor;
    private final ComputerUseMouseAnimator animator;
    private final ComputerUseScreenCapturer capturer;
    private final ComputerUseGeometryStore geometryStore;
    private final ComputerUseCoordinateMode coordinateMode;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;
    private final ComputerUseMouseState mouseState;
    private final ComputerUseSleeper sleeper;

    /**
     * @param keyboard              键盘注入实现
     * @param mouse                 鼠标动作实现（点击 / 移动 / 滚动）
     * @param cursor                光标原语（位置 / 左键按下抬起）
     * @param animator              鼠标缓动器（拖拽）
     * @param capturer              截屏实现
     * @param geometryStore         截图几何快照存储
     * @param coordinateMode        坐标模式（null ⇒ {@link ComputerUseCoordinateMode#PIXELS}）
     * @param grantStore            会话授权位读写（null ⇒ 不做门控，单测路径）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     * @param mouseState            左键按住状态（null ⇒ 不维护）
     * @param sleeper               休眠器（可注入假时钟）
     */
    public ComputerUseBatchTool(ComputerUseKeyboard keyboard,
                                ComputerUseMouseActions mouse,
                                ComputerUseCursor cursor,
                                ComputerUseMouseAnimator animator,
                                ComputerUseScreenCapturer capturer,
                                ComputerUseGeometryStore geometryStore,
                                ComputerUseCoordinateMode coordinateMode,
                                ComputerUseGrantStore grantStore,
                                ComputerUseForegroundAppResolver foregroundAppResolver,
                                ComputerUseMouseState mouseState,
                                ComputerUseSleeper sleeper) {
        if (keyboard == null || mouse == null || cursor == null || animator == null
                || capturer == null || geometryStore == null) {
            throw new IllegalArgumentException("ComputerUseBatchTool 依赖不能为空");
        }
        this.keyboard = keyboard;
        this.mouse = mouse;
        this.cursor = cursor;
        this.animator = animator;
        this.capturer = capturer;
        this.geometryStore = geometryStore;
        this.coordinateMode = coordinateMode == null ? ComputerUseCoordinateMode.PIXELS : coordinateMode;
        this.grantStore = grantStore;
        this.foregroundAppResolver = foregroundAppResolver;
        this.mouseState = mouseState;
        this.sleeper = sleeper == null ? ComputerUseSleeper.REAL : sleeper;
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
                    "[computer-use] computer_batch inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 批内含输入动作 ⇒ 非只读、不可并发。 */
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
     * 执行批量动作。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;
        JsonNode input = call.input();
        JsonNode actionsNode = input.path("actions");

        // ── 全量校验（CCB toolCalls.ts:3927-3946）──
        if (!actionsNode.isArray() || actionsNode.isEmpty()) {
            return ToolResult.error(call.id(), "actions must be a non-empty array");
        }
        List<JsonNode> actions = new ArrayList<>();
        for (int i = 0; i < actionsNode.size(); i++) {
            JsonNode act = actionsNode.get(i);
            if (act == null || !act.isObject()) {
                return ToolResult.error(call.id(), "actions[" + i + "] must be an object");
            }
            JsonNode actionNode = act.path("action");
            if (!actionNode.isTextual()) {
                return ToolResult.error(call.id(), "actions[" + i + "].action must be a string");
            }
            String action = actionNode.asText();
            if (!BATCHABLE_ACTIONS.contains(action)) {
                return ToolResult.error(call.id(),
                        "actions[" + i + "].action=\"" + action + "\" is not allowed in a batch. Allowed: "
                                + String.join(", ", BATCHABLE_ACTIONS) + ".");
            }
            actions.add(act);
        }

        // 批前的几何快照（CCB toolCalls.ts:3994-4002：批内截图不改坐标基准）
        ComputerUseScreenshotGeometry preBatch = geometryStore.get(sessionId);

        List<ObjectNode> completed = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            JsonNode act = actions.get(i);
            String action = act.path("action").asText();

            // 步骤间隔 · CCB toolCalls.ts:3989
            if (i > 0) {
                try {
                    sleeper.sleep(STEP_SETTLE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error(call.id(), "批量操作被中断。");
                }
            }

            Outcome outcome;
            try {
                outcome = executeAction(action, act, sessionId);
            } catch (Exception e) {
                log.error("[computer-use] {} 批内动作异常: action={} err={}", TOOL_NAME, action, e.getMessage(), e);
                outcome = Outcome.failure(e.getMessage() == null ? "动作执行失败" : e.getMessage());
            }

            if (!outcome.ok()) {
                // 遇错即停 · CCB toolCalls.ts:4008-4025
                ObjectNode failed = completedEntry(action, false, outcome.message());
                ObjectNode root = JSON.createObjectNode();
                ArrayNode done = root.putArray("completed");
                completed.forEach(done::add);
                root.set("failed", failed);
                root.put("remaining", actions.size() - (i + 1));
                if (log.isInfoEnabled()) {
                    log.info("[computer-use] {} 中止: 已完成 {} 步，第 {} 步失败: {} · 剩余 {} 步",
                            TOOL_NAME, completed.size(), i, outcome.message(), actions.size() - (i + 1));
                }
                return ToolResult.success(call.id(), root);
            }

            completed.add(completedEntry(action, true, outcome.message()));

            // 批内截图不改坐标基准 · CCB toolCalls.ts:3994-4002
            if ("screenshot".equals(action)) {
                geometryStore.put(sessionId, preBatch);
            }
        }

        ObjectNode root = JSON.createObjectNode();
        ArrayNode done = root.putArray("completed");
        completed.forEach(done::add);
        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: {} 步全部成功", TOOL_NAME, completed.size());
        }
        return ToolResult.success(call.id(), root);
    }

    /**
     * 执行一个批内动作。
     *
     * @param action    动作名
     * @param act       动作项（含该动作自己的参数）
     * @param sessionId 会话 id
     * @return 执行结果
     * @throws Exception 执行异常（由调用方转成失败结果）
     */
    private Outcome executeAction(String action, JsonNode act, String sessionId) throws Exception {
        return switch (action) {
            case "key" -> doKey(act, sessionId);
            case "type" -> doType(act, sessionId);
            case "mouse_move" -> doMouseMove(act, sessionId);
            case "left_click" -> doClick(act, sessionId, ComputerUseMouseButton.LEFT, 1);
            case "double_click" -> doClick(act, sessionId, ComputerUseMouseButton.LEFT, 2);
            case "triple_click" -> doClick(act, sessionId, ComputerUseMouseButton.LEFT, 3);
            case "right_click" -> doClick(act, sessionId, ComputerUseMouseButton.RIGHT, 1);
            case "middle_click" -> doClick(act, sessionId, ComputerUseMouseButton.MIDDLE, 1);
            case "left_click_drag" -> doDrag(act, sessionId);
            case "scroll" -> doScroll(act, sessionId);
            case "hold_key" -> doHoldKey(act, sessionId);
            case "screenshot" -> doScreenshot(sessionId);
            case "cursor_position" -> doCursorPosition(sessionId);
            case "left_mouse_down" -> doLeftMouseDown(sessionId);
            case "left_mouse_up" -> doLeftMouseUp(sessionId);
            case "wait" -> doWait(act);
            default -> Outcome.failure("不支持的批内动作: " + action);
        };
    }

    /** {@code key} 动作 · 对齐 CCB {@code toolCalls.ts:2570-2619} 的 {@code handleKey}。 */
    private Outcome doKey(JsonNode act, String sessionId) throws Exception {
        JsonNode textNode = act.path("text");
        if (!textNode.isTextual()) {
            return Outcome.failure("text is required");
        }
        String chord = textNode.asText();
        int repeat = 1;
        if (act.has("repeat")) {
            JsonNode repeatNode = act.path("repeat");
            if (!repeatNode.isNumber() || repeatNode.asDouble() != Math.rint(repeatNode.asDouble())
                    || repeatNode.asDouble() < 1) {
                return Outcome.failure("repeat must be a positive integer");
            }
            if (repeatNode.asDouble() > 100) {
                return Outcome.failure("repeat exceeds maximum of 100");
            }
            repeat = repeatNode.asInt();
        }
        // 黑名单先于门控 · CCB toolCalls.ts:2596-2607
        if (ComputerUseKeyBlocklist.isSystemKeyCombo(chord)) {
            boolean granted = grantStore != null
                    && grantStore.read(sessionId).flags().systemKeyCombos();
            if (!granted) {
                return Outcome.failure(ComputerUseKeyBlocklist.blockedMessage(chord));
            }
        }
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.KEYBOARD);
        if (rejection != null) {
            return Outcome.failure(rejection);
        }
        List<ComputerUseKeyEvent> events;
        try {
            events = ComputerUseKeyCombo.parse(chord);
        } catch (IllegalArgumentException e) {
            return Outcome.failure(e.getMessage());
        }
        for (int i = 0; i < repeat; i++) {
            if (i > 0) {
                sleeper.sleep(REPEAT_INTERVAL_MS);
            }
            for (ComputerUseKeyEvent event : events) {
                keyboard.sendKeyEvent(event);
            }
        }
        return Outcome.success("Key pressed.");
    }

    /** {@code type} 动作 · 对齐 CCB {@code toolCalls.ts:2507-2568} 的 {@code handleType}。 */
    private Outcome doType(JsonNode act, String sessionId) throws Exception {
        JsonNode textNode = act.path("text");
        if (!textNode.isTextual()) {
            return Outcome.failure("\"text\" must be a string.");
        }
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.KEYBOARD);
        if (rejection != null) {
            return Outcome.failure(rejection);
        }
        List<String> graphemes = ComputerUseGraphemes.segment(textNode.asText());
        for (String grapheme : graphemes) {
            sleeper.sleep(INTER_GRAPHEME_SLEEP_MS);
            injectGrapheme(grapheme);
        }
        return Outcome.success("Typed " + graphemes.size() + " grapheme(s).");
    }

    /** 注入一个字形簇（换行 / 制表符走按键）· 对齐 CCB {@code toolCalls.ts:2559-2565}。 */
    private void injectGrapheme(String grapheme) throws Exception {
        if ("\n".equals(grapheme) || "\r".equals(grapheme) || "\r\n".equals(grapheme)) {
            keyboard.sendKeyEvent(ComputerUseKeyEvent.named("return", true));
            keyboard.sendKeyEvent(ComputerUseKeyEvent.named("return", false));
            return;
        }
        if ("\t".equals(grapheme)) {
            keyboard.sendKeyEvent(ComputerUseKeyEvent.named("tab", true));
            keyboard.sendKeyEvent(ComputerUseKeyEvent.named("tab", false));
            return;
        }
        int i = 0;
        while (i < grapheme.length()) {
            int codePoint = grapheme.codePointAt(i);
            keyboard.typeChar(codePoint);
            i += Character.charCount(codePoint);
        }
    }

    /** {@code mouse_move} 动作 · 对齐 CCB {@code toolCalls.ts:2780-2834}。 */
    private Outcome doMouseMove(JsonNode act, String sessionId) {
        JsonNode coordinate = act.path("coordinate");
        String error = validateCoordinate(coordinate, "coordinate");
        if (error != null) {
            return Outcome.failure(error);
        }
        boolean held = mouseState != null && mouseState.isHeld(sessionId);
        String rejection = ComputerUseInputGate.check(sessionId, grantStore, foregroundAppResolver,
                held ? ComputerUseActionKind.MOUSE : ComputerUseActionKind.MOUSE_POSITION);
        if (rejection != null) {
            return Outcome.failure(rejection);
        }
        int[] point = scale(coordinate, sessionId);
        if (point == null) {
            return Outcome.failure(scaleError());
        }
        try {
            mouse.moveTo(point[0], point[1]);
        } catch (Exception e) {
            return Outcome.failure("鼠标移动失败: " + e.getMessage());
        }
        if (held && mouseState != null) {
            mouseState.markMoved(sessionId);
        }
        return Outcome.success("Moved.");
    }

    /** 点击变体动作 · 对齐 CCB {@code toolCalls.ts:2376-2505} 的 {@code handleClickVariant}。 */
    private Outcome doClick(JsonNode act, String sessionId, ComputerUseMouseButton button, int count) {
        JsonNode coordinate = act.path("coordinate");
        String error = validateCoordinate(coordinate, "coordinate");
        if (error != null) {
            return Outcome.failure(error);
        }
        // 修饰键未实现 ⇒ 显式报错（与单个点击工具一致，fail loud）
        JsonNode modifiers = act.path("text");
        if (modifiers.isTextual() && !modifiers.asText().isBlank()) {
            return Outcome.failure("修饰键点击（text 参数）本仓未实现，请仅传 coordinate");
        }
        ComputerUseActionKind actionKind = button == ComputerUseMouseButton.LEFT
                ? ComputerUseActionKind.MOUSE
                : ComputerUseActionKind.MOUSE_FULL;
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, actionKind);
        if (rejection != null) {
            return Outcome.failure(rejection);
        }
        int[] point = scale(coordinate, sessionId);
        if (point == null) {
            return Outcome.failure(scaleError());
        }
        try {
            mouse.click(point[0], point[1], button, count);
        } catch (Exception e) {
            return Outcome.failure("点击失败: " + e.getMessage());
        }
        return Outcome.success("Clicked.");
    }

    /** {@code left_click_drag} 动作 · 对齐 CCB {@code toolCalls.ts:2687-2778}。 */
    private Outcome doDrag(JsonNode act, String sessionId) {
        JsonNode coordinate = act.path("coordinate");
        String error = validateCoordinate(coordinate, "coordinate");
        if (error != null) {
            return Outcome.failure(error);
        }
        JsonNode startNode = act.path("start_coordinate");
        boolean hasStart = !startNode.isMissingNode() && !startNode.isNull();
        if (hasStart) {
            error = validateCoordinate(startNode, "start_coordinate");
            if (error != null) {
                return Outcome.failure(error);
            }
        }
        // 防呆清场 · CCB toolCalls.ts:2698-2702
        if (mouseState != null && mouseState.isHeld(sessionId)) {
            try {
                animator.releaseHeldButton();
            } catch (Exception e) {
                log.warn("[computer-use] {} 批内拖拽前释放悬空左键失败: {}", TOOL_NAME, e.getMessage());
            }
            mouseState.markReleased(sessionId);
        }
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.MOUSE);
        if (rejection != null) {
            return Outcome.failure(rejection);
        }
        int[] end = scale(coordinate, sessionId);
        if (end == null) {
            return Outcome.failure(scaleError());
        }
        int[] start = null;
        if (hasStart) {
            start = scale(startNode, sessionId);
            if (start == null) {
                return Outcome.failure(scaleError());
            }
        }
        try {
            if (start != null) {
                animator.drag(start[0], start[1], end[0], end[1]);
            } else {
                animator.dragFromCurrent(end[0], end[1]);
            }
        } catch (Exception e) {
            return Outcome.failure("拖拽失败: " + e.getMessage());
        }
        if (mouseState != null) {
            mouseState.markReleased(sessionId);
        }
        return Outcome.success("Dragged.");
    }

    /** {@code scroll} 动作 · 对齐 CCB {@code toolCalls.ts:2621-2685}。 */
    private Outcome doScroll(JsonNode act, String sessionId) {
        JsonNode coordinate = act.path("coordinate");
        String error = validateCoordinate(coordinate, "coordinate");
        if (error != null) {
            return Outcome.failure(error);
        }
        String direction = act.path("scroll_direction").asText("");
        if (!"up".equals(direction) && !"down".equals(direction)
                && !"left".equals(direction) && !"right".equals(direction)) {
            return Outcome.failure("\"scroll_direction\" must be one of: up, down, left, right");
        }
        JsonNode amountNode = act.path("scroll_amount");
        if (!amountNode.isIntegralNumber()) {
            return Outcome.failure("\"scroll_amount\" must be an integer");
        }
        long amount = amountNode.asLong();
        if (amount < 0) {
            return Outcome.failure("\"scroll_amount\" must be non-negative");
        }
        if (amount > 100) {
            return Outcome.failure("\"scroll_amount\" must not exceed 100");
        }
        boolean held = mouseState != null && mouseState.isHeld(sessionId);
        String rejection = ComputerUseInputGate.check(sessionId, grantStore, foregroundAppResolver,
                held ? ComputerUseActionKind.MOUSE_FULL : ComputerUseActionKind.MOUSE);
        if (rejection != null) {
            return Outcome.failure(rejection);
        }
        int[] point = scale(coordinate, sessionId);
        if (point == null) {
            return Outcome.failure(scaleError());
        }
        int dx = 0;
        int dy = 0;
        int ticks = (int) Math.min(amount, Integer.MAX_VALUE);
        switch (direction) {
            case "up" -> dy = -ticks;
            case "down" -> dy = ticks;
            case "left" -> dx = -ticks;
            case "right" -> dx = ticks;
            default -> {
                return Outcome.failure("\"scroll_direction\" must be one of: up, down, left, right");
            }
        }
        try {
            mouse.scroll(dx, dy);
        } catch (Exception e) {
            return Outcome.failure("滚动失败: " + e.getMessage());
        }
        return Outcome.success("Scrolled.");
    }

    /** {@code hold_key} 动作 · 对齐 CCB {@code toolCalls.ts:3712-3758} 的 {@code handleHoldKey}。 */
    private Outcome doHoldKey(JsonNode act, String sessionId) throws Exception {
        JsonNode textNode = act.path("text");
        if (!textNode.isTextual()) {
            return Outcome.failure("\"text\" must be a string.");
        }
        String chord = textNode.asText();
        JsonNode durationNode = act.path("duration");
        if (!durationNode.isNumber() || !Double.isFinite(durationNode.asDouble())) {
            return Outcome.failure("duration must be a number");
        }
        double duration = durationNode.asDouble();
        if (duration < 0) {
            return Outcome.failure("duration must be non-negative");
        }
        if (duration > 100) {
            return Outcome.failure("duration is too long. Duration is in seconds.");
        }
        if (ComputerUseKeyBlocklist.isSystemKeyCombo(chord)) {
            boolean granted = grantStore != null
                    && grantStore.read(sessionId).flags().systemKeyCombos();
            if (!granted) {
                return Outcome.failure(ComputerUseKeyBlocklist.blockedMessage(chord));
            }
        }
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.KEYBOARD);
        if (rejection != null) {
            return Outcome.failure(rejection);
        }
        List<ComputerUseKeyEvent> downs = new ArrayList<>();
        List<ComputerUseKeyEvent> ups = new ArrayList<>();
        for (String part : ComputerUseKeyCombo.splitParts(chord)) {
            int vk;
            try {
                vk = ComputerUseKeyCombo.resolveVirtualKey(part);
            } catch (IllegalArgumentException e) {
                return Outcome.failure(e.getMessage());
            }
            downs.add(new ComputerUseKeyEvent(part, vk, true));
            ups.add(new ComputerUseKeyEvent(part, vk, false));
        }
        if (downs.isEmpty()) {
            return Outcome.failure("\"text\" must be a string.");
        }
        List<ComputerUseKeyEvent> pressed = new ArrayList<>();
        try {
            for (ComputerUseKeyEvent down : downs) {
                keyboard.sendKeyEvent(down);
                pressed.add(down);
            }
            sleeper.sleep(Math.round(duration * 1000d));
            for (int i = ups.size() - 1; i >= 0; i--) {
                keyboard.sendKeyEvent(ups.get(i));
            }
            pressed.clear();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releasePressed(pressed);
            return Outcome.failure("按键保持被中断。");
        } catch (Exception e) {
            releasePressed(pressed);
            return Outcome.failure("按键保持失败: " + e.getMessage());
        }
        return Outcome.success("Key held.");
    }

    /**
     * {@code screenshot} 动作 · 对齐 CCB {@code toolCalls.ts:2092-2305} 的 {@code handleScreenshot}。
     *
     * <p>⚠️ CCB 批内截图的<b>图片内容被丢弃</b>（{@code toolCalls.ts:4004} 只取首个文本内容），
     * 且<b>不改坐标基准</b>（{@code toolCalls.ts:3994-4002} 丢弃 {@code .screenshot} piggyback）。
     */
    private Outcome doScreenshot(String sessionId) {
        try {
            ComputerUseCaptureResult capture = capturer.capture();
            return Outcome.success("Screenshot captured (" + capture.imageWidth() + "x"
                    + capture.imageHeight() + ").");
        } catch (Exception e) {
            return Outcome.failure("截屏失败: " + e.getMessage());
        }
    }

    /** {@code cursor_position} 动作 · 对齐 CCB {@code toolCalls.ts:3667-3704}。 */
    private Outcome doCursorPosition(String sessionId) {
        try {
            int[] logical = cursor.location();
            ComputerUseScreenshotGeometry shot = geometryStore.get(sessionId);
            if (shot == null) {
                return Outcome.success("cursor at (" + logical[0] + "," + logical[1]
                        + ") (logical points; no screenshot yet)");
            }
            int localX = logical[0] - shot.originX();
            int localY = logical[1] - shot.originY();
            if (localX < 0 || localX > shot.displayWidth()
                    || localY < 0 || localY > shot.displayHeight()) {
                return Outcome.success("cursor at (" + logical[0] + "," + logical[1]
                        + ") (logical points; on a different monitor)");
            }
            int x = (int) Math.round(localX * (shot.width() / (double) shot.displayWidth()));
            int y = (int) Math.round(localY * (shot.height() / (double) shot.displayHeight()));
            return Outcome.success("cursor at (" + x + "," + y + ") (image pixels)");
        } catch (Exception e) {
            return Outcome.failure("读取光标位置失败: " + e.getMessage());
        }
    }

    /** {@code left_mouse_down} 动作 · 对齐 CCB {@code toolCalls.ts:3764-3799}。 */
    private Outcome doLeftMouseDown(String sessionId) {
        if (mouseState != null && mouseState.isHeld(sessionId)) {
            return Outcome.failure("mouse button already held, call left_mouse_up first");
        }
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.MOUSE);
        if (rejection != null) {
            return Outcome.failure(rejection);
        }
        try {
            cursor.leftButtonDown();
        } catch (Exception e) {
            return Outcome.failure("左键按下失败: " + e.getMessage());
        }
        if (mouseState != null) {
            mouseState.markHeld(sessionId);
        }
        return Outcome.success("Mouse button pressed.");
    }

    /** {@code left_mouse_up} 动作 · 对齐 CCB {@code toolCalls.ts:3805-3854}。 */
    private Outcome doLeftMouseUp(String sessionId) {
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.MOUSE);
        if (rejection != null) {
            // CCB toolCalls.ts:3820-3827 —— 先释放再报错
            try {
                cursor.leftButtonUp();
            } catch (Exception e) {
                log.warn("[computer-use] {} 门控拒绝后的强制释放失败: {}", TOOL_NAME, e.getMessage());
            }
            if (mouseState != null) {
                mouseState.markReleased(sessionId);
            }
            return Outcome.failure(rejection);
        }
        try {
            cursor.leftButtonUp();
        } catch (Exception e) {
            return Outcome.failure("左键释放失败: " + e.getMessage());
        }
        if (mouseState != null) {
            mouseState.markReleased(sessionId);
        }
        return Outcome.success("Mouse button released.");
    }

    /** {@code wait} 动作 · 对齐 CCB {@code toolCalls.ts:3636-3654} 的 {@code handleWait}。 */
    private Outcome doWait(JsonNode act) {
        JsonNode durationNode = act.path("duration");
        if (!durationNode.isNumber() || !Double.isFinite(durationNode.asDouble())) {
            return Outcome.failure("duration must be a number");
        }
        double duration = durationNode.asDouble();
        if (duration < 0) {
            return Outcome.failure("duration must be non-negative");
        }
        if (duration > 100) {
            return Outcome.failure("duration is too long. Duration is in seconds.");
        }
        try {
            sleeper.sleep(Math.round(duration * 1000d));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.failure("等待被中断。");
        }
        return Outcome.success("Waited " + formatDuration(duration) + "s.");
    }

    /** 异常路径下逆序释放已按下的键（绝不让修饰键卡在按下态）。 */
    private void releasePressed(List<ComputerUseKeyEvent> pressed) {
        for (int i = pressed.size() - 1; i >= 0; i--) {
            ComputerUseKeyEvent down = pressed.get(i);
            try {
                keyboard.sendKeyEvent(new ComputerUseKeyEvent(down.name(), down.virtualKey(), false));
            } catch (Exception e) {
                log.warn("[computer-use] {} 异常路径释放键失败: {} · {}", TOOL_NAME, down.name(), e.getMessage());
            }
        }
    }

    /** 坐标校验（CCB {@code extractCoordinate} 的同款规则）。 */
    private static String validateCoordinate(JsonNode node, String paramName) {
        if (node.isMissingNode() || node.isNull()) {
            return "\"" + paramName + "\" is required";
        }
        if (!node.isArray() || node.size() != 2) {
            return "\"" + paramName + "\" must be an array of length 2";
        }
        JsonNode x = node.get(0);
        JsonNode y = node.get(1);
        if (!x.isNumber() || !y.isNumber()
                || !Double.isFinite(x.asDouble()) || !Double.isFinite(y.asDouble())
                || x.asDouble() < 0 || y.asDouble() < 0) {
            return "\"" + paramName + "\" must be a tuple of non-negative numbers";
        }
        return null;
    }

    /** 坐标换算（走 {@link ComputerUseCoordinateScaler}）。失败 ⇒ null。 */
    private int[] scale(JsonNode coordinate, String sessionId) {
        try {
            return ComputerUseCoordinateScaler.scaleCoord(
                    coordinate.get(0).asDouble(),
                    coordinate.get(1).asDouble(),
                    coordinateMode,
                    geometryStore.get(sessionId));
        } catch (ComputerUseGeometryMissingException e) {
            return null;
        }
    }

    /** 换算失败时的错误文案（与既有工具同款）。 */
    private String scaleError() {
        return "没有找到先前的屏幕截图，无法确定坐标对应的屏幕位置。"
                + "请先调用截图工具（mcp__computer-use__screenshot）再点击。";
    }

    /** 构造批结果的一个条目（CCB {@code toolCalls.ts:3884-3888} 的 {@code BatchActionResult}）。 */
    private static ObjectNode completedEntry(String action, boolean ok, String output) {
        ObjectNode node = JSON.createObjectNode();
        node.put("action", action);
        node.put("ok", ok);
        node.put("output", output == null ? "" : output);
        return node;
    }

    /** 秒数的 JS 风格格式化（对齐 CCB 的模板串输出）。 */
    private static String formatDuration(double duration) {
        if (duration == Math.floor(duration) && !Double.isInfinite(duration) && Math.abs(duration) < 1e15) {
            return Long.toString((long) duration);
        }
        return Double.toString(duration);
    }

    /** 一个批内动作的执行结果。 */
    private record Outcome(boolean ok, String message) {

        static Outcome success(String message) {
            return new Outcome(true, message);
        }

        static Outcome failure(String message) {
            return new Outcome(false, message);
        }
    }
}
