package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@code mcp__computer-use__virtual_keyboard} · 窗口定向键盘工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:712-751}）</h2>
 * <pre>
 * name: 'virtual_keyboard',
 * description: 'Send keyboard input directly to the bound window via SendMessageW — independent of the physical keyboard. ' +
 *   'The user can keep typing on their own keyboard without interference. ' +
 *   'Supports: single keys, key combinations (Ctrl+S, Alt+F4), text input, and hold-key operations. ' +
 *   'All input targets the bound HWND only — no global keyboard events.',
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     action: { type: 'string', enum: [type,combo,press,release,hold], description: '...' },
 *     text: { type: 'string', description: '...' },
 *     duration: { type: 'number', description: "For 'hold': seconds to hold the key(s) before releasing. Default: 1." },
 *     repeat: { type: 'integer', minimum: 1, maximum: 100, description: 'Number of times to repeat the action. Default: 1.' },
 *   },
 *   required: ['action', 'text'],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code executorCrossPlatform.ts:748-828}）</h2>
 * <ul>
 *   <li>{@code type} ⇒ 逐码点 {@code SendMessage(WM_CHAR)}（支持中文 / emoji）；</li>
 *   <li>{@code combo} ⇒ 按 {@code +} 拆键 ⇒ 修饰键按下 → 主键按下/抬起 → 修饰键逆序抬起
 *       （{@code windowMessage.ts:341-366}）；</li>
 *   <li>{@code press} / {@code release} ⇒ 单键按下 / 抬起；</li>
 *   <li>{@code hold} ⇒ 按住 N 秒后释放；</li>
 *   <li>{@code repeat} ⇒ 重复执行（重复之间 sleep 30ms）。</li>
 * </ul>
 *
 * <p><b>⚠️ 与「全局」键盘工具的分工</b>：{@code key} / {@code type} 走<b>全局注入</b>
 * （{@code SendInput}，会影响用户正在做的事）；本工具走<b>窗口定向</b>
 * （{@code SendMessage} / {@code PostMessage} 到绑定 HWND，<b>不抢用户焦点</b>）。
 */
public final class ComputerUseVirtualKeyboardTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseVirtualKeyboardTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = "mcp__computer-use__virtual_keyboard";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:713-717}。 */
    private static final String DESCRIPTION =
            "Send keyboard input directly to the bound window via SendMessageW — independent of the physical keyboard. "
                    + "The user can keep typing on their own keyboard without interference. "
                    + "Supports: single keys, key combinations (Ctrl+S, Alt+F4), text input, and hold-key operations. "
                    + "All input targets the bound HWND only — no global keyboard events.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:718-750}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["type", "combo", "press", "release", "hold"],
                  "description": "type: Send text string via WM_CHAR (Unicode, supports Chinese/emoji). combo: Send a key combination like ctrl+s, alt+f4, ctrl+shift+a (press all, release in reverse). press: Press a key down and hold it (pair with 'release'). release: Release a previously pressed key. hold: Press key(s) for a duration then release."
                },
                "text": {
                  "type": "string",
                  "description": "For 'type': the text to input. For 'combo': key combination string (e.g. 'ctrl+s', 'alt+tab', 'ctrl+shift+a'). For 'press'/'release': single key name (e.g. 'shift', 'ctrl', 'a')."
                },
                "duration": {
                  "type": "number",
                  "description": "For 'hold': seconds to hold the key(s) before releasing. Default: 1."
                },
                "repeat": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 100,
                  "description": "Number of times to repeat the action. Default: 1."
                }
              },
              "required": ["action", "text"]
            }
            """;

    /** 合法动作 · CCB {@code toolCalls.ts:2968}。 */
    private static final List<String> VALID_ACTIONS = List.of("type", "combo", "press", "release", "hold");

    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;
    private final ComputerUseSleeper sleeper;

    /**
     * @param windowOps    窗口操作原语
     * @param bindingStore 绑定窗口存储
     * @param sleeper      休眠器（可注入假时钟）
     */
    public ComputerUseVirtualKeyboardTool(ComputerUseWindowOps windowOps,
                                           ComputerUseWindowBindingStore bindingStore,
                                           ComputerUseSleeper sleeper) {
        this.windowOps = windowOps;
        this.bindingStore = bindingStore;
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
                    "[computer-use] virtual_keyboard inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 会改系统状态 ⇒ 非只读、不可并发。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use virtual_keyboard is gated by the "
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

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        JsonNode actionNode = input.path("action");
        if (!actionNode.isTextual()) {
            // CCB toolCalls.ts:2964-2965 —— requireString 的错误文案
            return ToolResult.error(call.id(), "\"action\" must be a string.");
        }
        String action = actionNode.asText();
        JsonNode textNode = input.path("text");
        if (!textNode.isTextual()) {
            // CCB toolCalls.ts:2965-2966
            return ToolResult.error(call.id(), "\"text\" must be a string.");
        }
        String text = textNode.asText();
        if (!VALID_ACTIONS.contains(action)) {
            // CCB toolCalls.ts:2969-2972
            return ToolResult.error(call.id(),
                    "Invalid action \"" + action + "\". Valid: " + String.join(", ", VALID_ACTIONS));
        }

        int repeat = 1;
        JsonNode repeatNode = input.path("repeat");
        if (repeatNode.isNumber()) {
            repeat = repeatNode.asInt();
            if (repeat < 1 || repeat > 100) {
                return ToolResult.error(call.id(), "repeat must be between 1 and 100");
            }
        }
        Double duration = input.path("duration").isNumber() ? input.path("duration").asDouble() : null;

        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;
        if (binding == null) {
            // CCB toolCalls.ts:2986-2989
            return ToolResult.error(call.id(),
                    "virtual_keyboard is only available on Windows with a bound window.");
        }
        long hwnd = binding.hwnd();

        try {
            for (int r = 0; r < repeat; r++) {
                if (r > 0) {
                    // CCB executorCrossPlatform.ts:765 —— 重复之间 sleep 30ms
                    sleeper.sleep(30L);
                }
                dispatch(hwnd, action, text, duration);
            }
            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: action={} repeat={} hwnd={}",
                        TOOL_NAME, action, repeat, hwnd);
            }
            return ToolResult.success(call.id(), describe(action, text, duration, repeat));
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: action={} text={} hwnd={} err={}",
                    TOOL_NAME, action, text, hwnd, e.getMessage(), e);
            return ToolResult.error(call.id(), "虚拟键盘输入失败: " + e.getMessage());
        }
    }

    /** 单次动作派发 · CCB {@code executorCrossPlatform.ts:767-822}。 */
    private void dispatch(long hwnd, String action, String text, Double duration) throws Exception {
        switch (action) {
            case "type":
                windowOps.sendText(hwnd, text);
                break;
            case "combo": {
                List<String> parts = splitCombo(text);
                // 修饰键按下 → 主键按下/抬起 → 修饰键逆序抬起（windowMessage.ts:341-366）
                List<String> modifiers = new java.util.ArrayList<>();
                String mainKey = null;
                for (String part : parts) {
                    if (ComputerUseVirtualKeys.MODIFIER_KEYS.contains(part)) {
                        modifiers.add(part);
                    } else {
                        mainKey = part;
                    }
                }
                if (mainKey == null) {
                    throw new IllegalArgumentException("组合键缺少主键: " + text);
                }
                for (String mod : modifiers) {
                    windowOps.sendKeyDown(hwnd, ComputerUseKeyCombo.resolveVirtualKey(mod));
                }
                try {
                    int mainVk = ComputerUseKeyCombo.resolveVirtualKey(mainKey);
                    windowOps.sendKeyDown(hwnd, mainVk);
                    windowOps.sendKeyUp(hwnd, mainVk);
                } finally {
                    // 逆序释放（异常路径也不卡键）
                    for (int i = modifiers.size() - 1; i >= 0; i--) {
                        windowOps.sendKeyUp(hwnd, ComputerUseKeyCombo.resolveVirtualKey(modifiers.get(i)));
                    }
                }
                break;
            }
            case "press":
                windowOps.sendKeyDown(hwnd, resolveKey(text));
                break;
            case "release":
                windowOps.sendKeyUp(hwnd, resolveKey(text));
                break;
            case "hold": {
                List<String> parts = splitCombo(text);
                List<Integer> pressed = new java.util.ArrayList<>();
                try {
                    for (String part : parts) {
                        int vk = resolveKey(part);
                        windowOps.sendKeyDown(hwnd, vk);
                        pressed.add(vk);
                    }
                    sleeper.sleep((long) ((duration == null ? 1d : duration) * 1000d));
                } finally {
                    // 逆序释放（异常路径也不卡键）
                    for (int i = pressed.size() - 1; i >= 0; i--) {
                        windowOps.sendKeyUp(hwnd, pressed.get(i));
                    }
                }
                break;
            }
            default:
                throw new IllegalArgumentException("不支持的动作: " + action);
        }
    }

    /** 组合键拆分（CCB {@code executorCrossPlatform.ts:773-777}）。 */
    private static List<String> splitCombo(String text) {
        List<String> parts = new java.util.ArrayList<>();
        for (String raw : text.split("\\+")) {
            String p = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return parts;
    }

    /** 单键名 → 虚拟键码（CCB {@code executorCrossPlatform.ts:782-787}）。 */
    private static int resolveKey(String name) {
        Integer vk = ComputerUseVirtualKeys.VK_MAP.get(name.toLowerCase(java.util.Locale.ROOT));
        if (vk != null) {
            return vk;
        }
        if (name.length() == 1) {
            return name.charAt(0);
        }
        throw new IllegalArgumentException("未知键名: " + name);
    }

    /** 成功文案 · CCB {@code toolCalls.ts:2993-3001}。 */
    private static String describe(String action, String text, Double duration, int repeat) {
        String desc;
        switch (action) {
            case "type":
                desc = "Typed \"" + (text.length() > 40 ? text.substring(0, 40) + "..." : text) + "\"";
                break;
            case "combo":
                desc = "Sent " + text;
                break;
            case "press":
                desc = "Pressed " + text + " (holding)";
                break;
            case "release":
                desc = "Released " + text;
                break;
            case "hold":
                desc = "Held " + text + " for " + (duration == null ? 1 : duration.intValue()) + "s";
                break;
            default:
                desc = action;
        }
        return desc + (repeat > 1 ? " ×" + repeat : "");
    }
}
