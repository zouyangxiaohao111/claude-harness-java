package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * computer-use 的 MCP 工具集 · 逐字移植 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts} 的
 * {@code buildComputerUseTools}（tools.ts:118-992）。
 *
 * <p>对齐口径：
 * <ul>
 *   <li>CCB 侧 {@code buildComputerUseTools(caps, coordinateMode, installedAppNames?)} 是函数不是数组
 *       ⇒ 本类同名同结构；</li>
 *   <li>schema 唯一真源 = tools.ts（手写对象字面量、无 zod）；每工具只有 name/description/inputSchema
 *       三键，inputSchema.type 恒 "object"，properties 与 required 恒存在；</li>
 *   <li>Windows 专属 11 个在 tools.ts:418-836 以
 *       {@code ...(caps.platform === 'win32' ? [...] : [])} 就地展开于数组字面量（位置在 open_application
 *       之后、switch_display 之前）⇒ 本类同位置同展开；</li>
 *   <li>teach 三件套在 tools.ts:990 以 {@code ...(caps.teachMode ? buildTeachTools(...) : [])} 展开；
 *       CCB CLI 的 CLI_CU_CAPABILITIES 不含 teachMode ⇒ 本仓同样恒 false、不下发，但分支结构保留；</li>
 *   <li>installedAppsHint 拼接无分隔空格（hint 自带前导空格）—— CCB 原文行为，不修正。</li>
 * </ul>
 */
public final class ComputerUseTools {

    /** Jackson mapper（与本仓其它 CU 工具同款）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** CCB tools.ts:13-32 · 坐标描述两态（模型只看到冻结的那一种）。 */
    private static final Map<String, String[]> COORD_DESC = new LinkedHashMap<>();

    static {
        COORD_DESC.put("pixels", new String[]{
            "Horizontal pixel position read directly from the most recent screenshot image, measured from the left edge. The server handles all scaling.",
            "Vertical pixel position read directly from the most recent screenshot image, measured from the top edge. The server handles all scaling."});
        COORD_DESC.put("normalized_0_100", new String[]{
            "Horizontal position as a percentage of screen width, 0.0–100.0 (0 = left edge, 100 = right edge).",
            "Vertical position as a percentage of screen height, 0.0–100.0 (0 = top edge, 100 = bottom edge)."});
    }

    /** CCB tools.ts:32-33 · 前台应用门控描述。 */
    private static final String FRONTMOST_GATE_DESC =
            "The frontmost application must be in the session allowlist at the time of this call, or this tool returns an error and does nothing.";

    /** CCB tools.ts:148-152 · 5 个点击变体共用的修饰键描述。 */
    private static final String CLICK_MODIFIER_TEXT =
            "Modifier keys to hold during the click (e.g. \"shift\", \"ctrl+shift\"). Supports the same syntax as the key tool.";

    /** CCB tools.ts:40-103 · batch 动作项 schema（computer_batch 与 teach 共用）。 */
    private static final String BATCH_ACTION_ITEM_SCHEMA_JSON =
            """
{
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
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y) for click/mouse_move/scroll/left_click_drag end point."
    },
    "start_coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
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
      "enum": [
        "up",
        "down",
        "left",
        "right"
      ]
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
  "required": [
    "action"
  ]
}
""";

    private ComputerUseTools() {
    }

    /** CCB tools.ts:154-157 · screenshot 描述（按截图过滤能力二选一）。 */
    private static String screenshotDesc(ComputerUseCapabilities caps) {
        return "native".equals(caps.screenshotFiltering())
                ?                 "Take a screenshot of the primary display. Applications not in the session allowlist are excluded at the compositor level — only granted apps and the desktop are visible."
                : "Take a screenshot of the primary display. On this platform, screenshots are NOT filtered — all open windows are visible. Input actions targeting apps not in the session allowlist are rejected.";
    }

    /** CCB tools.ts:40-103 · batch 动作项 schema（共用常量）。 */
    private static ObjectNode batchActionItemSchema() throws JsonProcessingException {
        return (ObjectNode) JSON.readTree(BATCH_ACTION_ITEM_SCHEMA_JSON);
    }

    /** CCB tools.ts · request_access */
    private static ComputerUseToolDefinition RequestAccess(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "apps": {
      "type": "array",
      "items": {
        "type": "string"
      },
      "description": "Application display names (e.g. \\"Slack\\", \\"Calendar\\") or bundle identifiers (e.g. \\"com.tinyspeck.slackmacgap\\"). Display names are resolved case-insensitively against installed apps."
    },
    "reason": {
      "type": "string",
      "description": "One-sentence explanation shown to the user in the approval dialog. Explain the task, not the mechanism."
    },
    "clipboardRead": {
      "type": "boolean",
      "description": "Also request permission to read the user's clipboard (separate checkbox in the dialog)."
    },
    "clipboardWrite": {
      "type": "boolean",
      "description": "Also request permission to write the user's clipboard. When granted, multi-line `type` calls use the clipboard fast path."
    },
    "systemKeyCombos": {
      "type": "boolean",
      "description": "Also request permission to send system-level key combos (quit app, switch app, lock screen). Without this, those specific combos are blocked."
    }
  },
  "required": [
    "apps",
    "reason"
  ]
}
""");
        ((ObjectNode) s.at("/properties/apps")).put("description",
                s.at("/properties/apps").get("description").asText() + installedAppsHint);
        return new ComputerUseToolDefinition("request_access", "Request user permission to control a set of applications for this session. Must be called before any other tool in this server. The user sees a single dialog listing all requested apps and either allows the whole set or denies it. Call this again mid-session to add more apps; previously granted apps remain granted. Returns the granted apps, denied apps, and screenshot filtering capability.", s);
    }

    /** CCB tools.ts · screenshot */
    private static ComputerUseToolDefinition Screenshot(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "save_to_disk": {
      "type": "boolean",
      "description": "Save the image to disk so it can be attached to a message for the user. Returns the saved path in the tool result. Only set this when you intend to share the image — screenshots you're just looking at don't need saving."
    }
  },
  "required": []
}
""");
        return new ComputerUseToolDefinition("screenshot", screenshotDesc(caps) + " Returns an error if the allowlist is empty. The returned image is what subsequent click coordinates are relative to.", s);
    }

    /** CCB tools.ts · zoom */
    private static ComputerUseToolDefinition Zoom(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "region": {
      "type": "array",
      "items": {
        "type": "integer"
      },
      "minItems": 4,
      "maxItems": 4,
      "description": "(x0, y0, x1, y1): Rectangle to zoom into, in the coordinate space of the most recent full-screen screenshot. x0,y0 = top-left, x1,y1 = bottom-right."
    },
    "save_to_disk": {
      "type": "boolean",
      "description": "Save the image to disk so it can be attached to a message for the user. Returns the saved path in the tool result. Only set this when you intend to share the image."
    }
  },
  "required": [
    "region"
  ]
}
""");
        return new ComputerUseToolDefinition("zoom", "Take a higher-resolution screenshot of a specific region of the last full-screen screenshot. Use this liberally to inspect small text, button labels, or fine UI details that are hard to read in the downsampled full-screen image. IMPORTANT: Coordinates in subsequent click calls always refer to the full-screen screenshot, never the zoomed image. This tool is read-only for inspecting detail.", s);
    }

    /** CCB tools.ts · left_click */
    private static ComputerUseToolDefinition LeftClick(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y): PLACEHOLDER_COORD"
    },
    "text": {
      "type": "string",
      "description": "PLACEHOLDER_CLICKMOD"
    }
  },
  "required": [
    "coordinate"
  ]
}
""");
        ((ObjectNode) s.at("/properties/coordinate")).put("description", "(x, y): " + coord[0]);
        ((ObjectNode) s.at("/properties/text")).put("description", CLICK_MODIFIER_TEXT);
        return new ComputerUseToolDefinition("left_click", "Left-click at the given coordinates. " + FRONTMOST_GATE_DESC, s);
    }

    /** CCB tools.ts · double_click */
    private static ComputerUseToolDefinition DoubleClick(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y): PLACEHOLDER_COORD"
    },
    "text": {
      "type": "string",
      "description": "PLACEHOLDER_CLICKMOD"
    }
  },
  "required": [
    "coordinate"
  ]
}
""");
        ((ObjectNode) s.at("/properties/coordinate")).put("description", "(x, y): " + coord[0]);
        ((ObjectNode) s.at("/properties/text")).put("description", CLICK_MODIFIER_TEXT);
        return new ComputerUseToolDefinition("double_click", "Double-click at the given coordinates. Selects a word in most text editors. " + FRONTMOST_GATE_DESC, s);
    }

    /** CCB tools.ts · triple_click */
    private static ComputerUseToolDefinition TripleClick(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y): PLACEHOLDER_COORD"
    },
    "text": {
      "type": "string",
      "description": "PLACEHOLDER_CLICKMOD"
    }
  },
  "required": [
    "coordinate"
  ]
}
""");
        ((ObjectNode) s.at("/properties/coordinate")).put("description", "(x, y): " + coord[0]);
        ((ObjectNode) s.at("/properties/text")).put("description", CLICK_MODIFIER_TEXT);
        return new ComputerUseToolDefinition("triple_click", "Triple-click at the given coordinates. Selects a line in most text editors. " + FRONTMOST_GATE_DESC, s);
    }

    /** CCB tools.ts · right_click */
    private static ComputerUseToolDefinition RightClick(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y): PLACEHOLDER_COORD"
    },
    "text": {
      "type": "string",
      "description": "PLACEHOLDER_CLICKMOD"
    }
  },
  "required": [
    "coordinate"
  ]
}
""");
        ((ObjectNode) s.at("/properties/coordinate")).put("description", "(x, y): " + coord[0]);
        ((ObjectNode) s.at("/properties/text")).put("description", CLICK_MODIFIER_TEXT);
        return new ComputerUseToolDefinition("right_click", "Right-click at the given coordinates. Opens a context menu in most applications. " + FRONTMOST_GATE_DESC, s);
    }

    /** CCB tools.ts · middle_click */
    private static ComputerUseToolDefinition MiddleClick(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y): PLACEHOLDER_COORD"
    },
    "text": {
      "type": "string",
      "description": "PLACEHOLDER_CLICKMOD"
    }
  },
  "required": [
    "coordinate"
  ]
}
""");
        ((ObjectNode) s.at("/properties/coordinate")).put("description", "(x, y): " + coord[0]);
        ((ObjectNode) s.at("/properties/text")).put("description", CLICK_MODIFIER_TEXT);
        return new ComputerUseToolDefinition("middle_click", "Middle-click (scroll-wheel click) at the given coordinates. " + FRONTMOST_GATE_DESC, s);
    }

    /** CCB tools.ts · type */
    private static ComputerUseToolDefinition Type(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "text": {
      "type": "string",
      "description": "Text to type."
    }
  },
  "required": [
    "text"
  ]
}
""");
        return new ComputerUseToolDefinition("type", "Type text into whatever currently has keyboard focus. " + FRONTMOST_GATE_DESC + " Newlines are supported. For keyboard shortcuts use `key` instead.", s);
    }

    /** CCB tools.ts · key */
    private static ComputerUseToolDefinition Key(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "text": {
      "type": "string",
      "description": "Modifiers joined with \\"+\\", e.g. \\"cmd+shift+a\\"."
    },
    "repeat": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "description": "Number of times to repeat the key press. Default is 1."
    }
  },
  "required": [
    "text"
  ]
}
""");
        return new ComputerUseToolDefinition("key", "Press a key or key combination (e.g. \"return\", \"escape\", \"cmd+a\", \"ctrl+shift+tab\"). " + FRONTMOST_GATE_DESC + " System-level combos (quit app, switch app, lock screen) require the `systemKeyCombos` grant — without it they return an error. All other combos work.", s);
    }

    /** CCB tools.ts · scroll */
    private static ComputerUseToolDefinition Scroll(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y): PLACEHOLDER_COORD"
    },
    "scroll_direction": {
      "type": "string",
      "enum": [
        "up",
        "down",
        "left",
        "right"
      ],
      "description": "Direction to scroll."
    },
    "scroll_amount": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100,
      "description": "Number of scroll ticks."
    }
  },
  "required": [
    "coordinate",
    "scroll_direction",
    "scroll_amount"
  ]
}
""");
        ((ObjectNode) s.at("/properties/coordinate")).put("description", "(x, y): " + coord[0]);
        return new ComputerUseToolDefinition("scroll", "Scroll at the given coordinates. " + FRONTMOST_GATE_DESC, s);
    }

    /** CCB tools.ts · left_click_drag */
    private static ComputerUseToolDefinition LeftClickDrag(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y) end point: PLACEHOLDER_COORD"
    },
    "start_coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y) start point. If omitted, drags from the current cursor position. PLACEHOLDER_COORD"
    }
  },
  "required": [
    "coordinate"
  ]
}
""");
        ((ObjectNode) s.at("/properties/coordinate")).put("description", "(x, y) end point: " + coord[0]);
        ((ObjectNode) s.at("/properties/start_coordinate")).put("description", "(x, y) start point. If omitted, drags from the current cursor position. " + coord[0]);
        return new ComputerUseToolDefinition("left_click_drag", "Press, move to target, and release. " + FRONTMOST_GATE_DESC, s);
    }

    /** CCB tools.ts · mouse_move */
    private static ComputerUseToolDefinition MouseMove(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y): PLACEHOLDER_COORD"
    }
  },
  "required": [
    "coordinate"
  ]
}
""");
        ((ObjectNode) s.at("/properties/coordinate")).put("description", "(x, y): " + coord[0]);
        return new ComputerUseToolDefinition("mouse_move", "Move the mouse cursor without clicking. Useful for triggering hover states. " + FRONTMOST_GATE_DESC, s);
    }

    /** CCB tools.ts · open_application */
    private static ComputerUseToolDefinition OpenApplication(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "app": {
      "type": "string",
      "description": "Display name (e.g. \\"Slack\\") or bundle identifier (e.g. \\"com.tinyspeck.slackmacgap\\")."
    }
  },
  "required": [
    "app"
  ]
}
""");
        return new ComputerUseToolDefinition("open_application", "Bring an application to the front, launching it if necessary. The target application must already be in the session allowlist — call request_access first.", s);
    }

    /** CCB tools.ts · window_management */
    private static ComputerUseToolDefinition WindowManagement(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": [
        "minimize",
        "maximize",
        "restore",
        "close",
        "focus",
        "move_offscreen",
        "move_resize",
        "get_rect"
      ],
      "description": "minimize: ShowWindow(SW_MINIMIZE). maximize: ShowWindow(SW_MAXIMIZE). restore: ShowWindow(SW_RESTORE) — undo minimize or maximize. close: SendMessage(WM_CLOSE) — graceful close. focus: SetForegroundWindow + BringWindowToTop. move_offscreen: SetWindowPos(-32000,-32000) — keeps window usable by SendMessage/PrintWindow but invisible. move_resize: SetWindowPos to specific x,y,width,height. get_rect: GetWindowRect — returns current position and size."
    },
    "x": {
      "type": "integer",
      "description": "X position for move_resize."
    },
    "y": {
      "type": "integer",
      "description": "Y position for move_resize."
    },
    "width": {
      "type": "integer",
      "description": "Width for move_resize."
    },
    "height": {
      "type": "integer",
      "description": "Height for move_resize."
    }
  },
  "required": [
    "action"
  ]
}
""");
        return new ComputerUseToolDefinition("window_management", "Manage the bound application window via Win32 API calls (ShowWindow, SetWindowPos, SendMessage). All operations target the bound HWND directly — NO global shortcuts (Win+Down, Alt+F4, etc.). The window must have been opened via open_application first. Actions: minimize (hide to taskbar), maximize (fill screen), restore (undo min/max), close (graceful WM_CLOSE), focus (bring to front), move_offscreen (move to -32000,-32000 for background operation). Use move_resize to reposition or resize the window to specific coordinates.", s);
    }

    /** CCB tools.ts · click_element */
    private static ComputerUseToolDefinition ClickElement(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "description": "Accessible name of the element (e.g. \\"Save\\", \\"File\\", \\"Search...\\"). Case-insensitive partial match."
    },
    "role": {
      "type": "string",
      "description": "Control type (e.g. \\"Button\\", \\"MenuItem\\", \\"Edit\\", \\"Link\\"). Optional — narrows the search."
    },
    "automationId": {
      "type": "string",
      "description": "Exact automationId from the accessibility snapshot. Most precise selector."
    }
  },
  "required": []
}
""");
        return new ComputerUseToolDefinition("click_element", "Click a GUI element by its accessible name, role, or automationId — no pixel coordinates needed. Uses Windows UI Automation to find the element and InvokePattern to click it. Prefer this over left_click when the element name is visible in the accessibility snapshot. Falls back to BoundingRect center-click if InvokePattern is not supported.", s);
    }

    /** CCB tools.ts · type_into_element */
    private static ComputerUseToolDefinition TypeIntoElement(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "description": "Accessible name of the target element."
    },
    "role": {
      "type": "string",
      "description": "Control type (optional, e.g. \\"Edit\\")."
    },
    "automationId": {
      "type": "string",
      "description": "Exact automationId."
    },
    "text": {
      "type": "string",
      "description": "Text to type/set into the element."
    }
  },
  "required": [
    "text"
  ]
}
""");
        return new ComputerUseToolDefinition("type_into_element", "Type text into a named GUI element using Windows UI Automation ValuePattern. Finds the element by name/role/automationId, then sets its value directly — no need to click first or use pixel coordinates. Works on Edit, ComboBox, and other value-holding controls.", s);
    }

    /** CCB tools.ts · open_terminal */
    private static ComputerUseToolDefinition OpenTerminal(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "agent": {
      "type": "string",
      "enum": [
        "claude",
        "codex",
        "gemini",
        "custom"
      ],
      "description": "Which agent to launch. claude: runs 'claude' command. codex: runs 'codex' command. gemini: runs 'gemini' command. custom: runs the command specified in 'command' parameter."
    },
    "command": {
      "type": "string",
      "description": "Custom command to run in the terminal. Only used when agent='custom'. Example: 'python app.py'"
    },
    "terminal": {
      "type": "string",
      "enum": [
        "wt",
        "powershell",
        "cmd"
      ],
      "description": "Which terminal to open. Default: 'wt' (Windows Terminal). 'powershell' for PowerShell window, 'cmd' for Command Prompt."
    },
    "working_directory": {
      "type": "string",
      "description": "Working directory for the terminal. If omitted, uses current directory."
    }
  },
  "required": [
    "agent"
  ]
}
""");
        return new ComputerUseToolDefinition("open_terminal", "Open a new terminal window and launch an AI agent CLI. This is a workflow tool that automates: open terminal → type startup command → press Enter → wait → verify. Supported agents: claude (runs 'claude'), codex (runs 'codex'), gemini (runs 'gemini'), or any custom command. After launching, the tool binds to the new terminal window and takes a screenshot to verify the agent started successfully. Use this when the user says: 'open Claude Code', 'start a Codex terminal', 'launch Gemini', etc.", s);
    }

    /** CCB tools.ts · bind_window */
    private static ComputerUseToolDefinition BindWindow(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": [
        "bind",
        "unbind",
        "status",
        "list"
      ],
      "description": "bind: Bind to a window (specify title, hwnd, or pid). unbind: Release the current binding, return to full-screen mode. status: Show the currently bound window (hwnd, title, rect). list: List all visible windows with hwnd, pid, and title."
    },
    "title": {
      "type": "string",
      "description": "Window title to search for (partial match, case-insensitive). For 'bind' action."
    },
    "hwnd": {
      "type": "string",
      "description": "Exact window handle from 'list' output. For 'bind' action."
    },
    "pid": {
      "type": "integer",
      "description": "Process ID to find window for. For 'bind' action."
    }
  },
  "required": [
    "action"
  ]
}
""");
        return new ComputerUseToolDefinition("bind_window", "Bind to a specific window for all subsequent operations (screenshot, click, type, etc.). Once bound, screenshots capture only that window via PrintWindow, and all input goes through SendMessageW — no cursor movement, no focus steal, no interference with the user's desktop. Actions: bind (by title, hwnd, or pid), unbind (release binding), status (show current binding), list (show all visible windows). Use 'list' first to see available windows, then 'bind' with a title or hwnd. open_application auto-binds the launched window, but use this tool to bind to already-running windows or switch between windows.", s);
    }

    /** CCB tools.ts · activate_window */
    private static ComputerUseToolDefinition ActivateWindow(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "click_x": {
      "type": "integer",
      "description": "X coordinate to click after activation (client-area). If omitted, clicks center of window."
    },
    "click_y": {
      "type": "integer",
      "description": "Y coordinate to click after activation (client-area). If omitted, clicks center of window."
    }
  },
  "required": []
}
""");
        return new ComputerUseToolDefinition("activate_window", "Activate the bound window: bring it to foreground, click to ensure keyboard focus, and optionally send an initial key sequence. Use this before any input operations to guarantee the window is ready to receive keyboard/mouse events. Combines SetForegroundWindow + BringWindowToTop + SendMessage(WM_LBUTTONDOWN) in one call.", s);
    }

    /** CCB tools.ts · prompt_respond */
    private static ComputerUseToolDefinition PromptRespond(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "response_type": {
      "type": "string",
      "enum": [
        "yes",
        "no",
        "enter",
        "escape",
        "select",
        "type"
      ],
      "description": "yes: send 'y' + Enter. no: send 'n' + Enter. enter: send Enter only. escape: send Escape (cancel). select: use arrow keys to navigate to an option, then Enter. Requires 'arrow_count'. type: type custom text then Enter. Requires 'text'."
    },
    "arrow_direction": {
      "type": "string",
      "enum": [
        "up",
        "down"
      ],
      "description": "Arrow key direction for 'select' type. Default: 'down'."
    },
    "arrow_count": {
      "type": "integer",
      "description": "Number of arrow key presses for 'select' type. Default: 1.",
      "minimum": 0,
      "maximum": 50
    },
    "text": {
      "type": "string",
      "description": "Text to type for 'type' response_type."
    }
  },
  "required": [
    "response_type"
  ]
}
""");
        return new ComputerUseToolDefinition("prompt_respond", "Handle interactive CLI/terminal prompts (Yes/No, selection menus, confirmations). Sends a sequence of key events to the bound window to navigate and confirm a prompt. This is a convenience wrapper around bound-window keyboard input for common prompt flows. Typical flows: 1) Yes/No prompt → send 'y' or 'n' + Enter. 2) Arrow-key selection menu → send arrow_down/arrow_up N times + Enter. 3) Text input prompt → type the response + Enter. After responding, take a screenshot to verify the result.", s);
    }

    /** CCB tools.ts · status_indicator */
    private static ComputerUseToolDefinition StatusIndicator(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": [
        "show",
        "hide",
        "status"
      ],
      "description": "show: display a custom message on the indicator. hide: dismiss the indicator. status: check if indicator is active."
    },
    "message": {
      "type": "string",
      "description": "Custom message to display (for 'show' action). Supports emoji. Auto-fades after 2 seconds."
    }
  },
  "required": [
    "action"
  ]
}
""");
        return new ComputerUseToolDefinition("status_indicator", "Control the visual status indicator overlay on the bound window. The indicator is a small floating label at the bottom of the window that shows what Computer Use is doing. It auto-shows during click/type/key/scroll operations, but you can also send custom messages. Actions: show (display a custom message), hide (dismiss), status (check if active).", s);
    }

    /** CCB tools.ts · virtual_keyboard */
    private static ComputerUseToolDefinition VirtualKeyboard(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": [
        "type",
        "combo",
        "press",
        "release",
        "hold"
      ],
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
      "description": "Number of times to repeat the action. Default: 1.",
      "minimum": 1,
      "maximum": 100
    }
  },
  "required": [
    "action",
    "text"
  ]
}
""");
        return new ComputerUseToolDefinition("virtual_keyboard", "Send keyboard input directly to the bound window via SendMessageW — independent of the physical keyboard. The user can keep typing on their own keyboard without interference. Supports: single keys, key combinations (Ctrl+S, Alt+F4), text input, and hold-key operations. All input targets the bound HWND only — no global keyboard events.", s);
    }

    /** CCB tools.ts · virtual_mouse */
    private static ComputerUseToolDefinition VirtualMouse(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": [
        "click",
        "double_click",
        "right_click",
        "move",
        "drag",
        "down",
        "up"
      ],
      "description": "click: left-click at coordinate. double_click: double left-click. right_click: right-click. move: move virtual cursor (visual only, no click). drag: press at start, move to end, release. Requires coordinate (end) and start_coordinate. down: press left button at coordinate (hold). up: release left button at coordinate."
    },
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y) client-area coordinate on the bound window."
    },
    "start_coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y) start point for drag. If omitted, drags from current virtual cursor position."
    }
  },
  "required": [
    "action",
    "coordinate"
  ]
}
""");
        return new ComputerUseToolDefinition("virtual_mouse", "Control a virtual mouse on the bound window via SendMessageW — independent of the physical mouse. The user's real cursor stays free. All operations target the bound HWND only.", s);
    }

    /** CCB tools.ts · mouse_wheel */
    private static ComputerUseToolDefinition MouseWheel(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "coordinate": {
      "type": "array",
      "items": {
        "type": "number"
      },
      "minItems": 2,
      "maxItems": 2,
      "description": "(x, y) client-area coordinate where the scroll should occur. Determines which element receives the scroll."
    },
    "delta": {
      "type": "integer",
      "description": "Scroll amount in 'clicks'. Positive = scroll up, negative = scroll down. Each click = 3 lines typically. Use -3 to -5 for page-like scrolling."
    },
    "direction": {
      "type": "string",
      "enum": [
        "vertical",
        "horizontal"
      ],
      "description": "Scroll direction. Default: 'vertical'. Use 'horizontal' for side-scrolling (e.g. wide Excel sheets, timeline views)."
    }
  },
  "required": [
    "coordinate",
    "delta"
  ]
}
""");
        return new ComputerUseToolDefinition("mouse_wheel", "Scroll inside the bound window using mouse wheel (WM_MOUSEWHEEL / WM_MOUSEHWHEEL). Unlike the generic 'scroll' tool which uses WM_VSCROLL (only works on scrollbar controls), mouse_wheel simulates the physical mouse wheel and works on Excel spreadsheets, web pages, code editors, PDF viewers, and any modern UI. Specify the click point within the window where the scroll should occur — this determines which panel/pane/element receives the scroll.", s);
    }

    /** CCB tools.ts · switch_display */
    private static ComputerUseToolDefinition SwitchDisplay(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "display": {
      "type": "string",
      "description": "Monitor name from the screenshot note (e.g. \\"Built-in Retina Display\\", \\"LG UltraFine\\"), or \\"auto\\" to re-enable automatic selection."
    }
  },
  "required": [
    "display"
  ]
}
""");
        return new ComputerUseToolDefinition("switch_display", "Switch which monitor subsequent screenshots capture. Use this when the application you need is on a different monitor than the one shown. The screenshot tool tells you which monitor it captured and lists other attached monitors by name — pass one of those names here. After switching, call screenshot to see the new monitor. Pass \"auto\" to return to automatic monitor selection.", s);
    }

    /** CCB tools.ts · list_granted_applications */
    private static ComputerUseToolDefinition ListGrantedApplications(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {},
  "required": []
}
""");
        return new ComputerUseToolDefinition("list_granted_applications", "List the applications currently in the session allowlist, plus the active grant flags and coordinate mode. No side effects.", s);
    }

    /** CCB tools.ts · read_clipboard */
    private static ComputerUseToolDefinition ReadClipboard(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {},
  "required": []
}
""");
        return new ComputerUseToolDefinition("read_clipboard", "Read the current clipboard contents as text. Requires the `clipboardRead` grant.", s);
    }

    /** CCB tools.ts · write_clipboard */
    private static ComputerUseToolDefinition WriteClipboard(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "text": {
      "type": "string"
    }
  },
  "required": [
    "text"
  ]
}
""");
        return new ComputerUseToolDefinition("write_clipboard", "Write text to the clipboard. Requires the `clipboardWrite` grant.", s);
    }

    /** CCB tools.ts · wait */
    private static ComputerUseToolDefinition Wait(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "duration": {
      "type": "number",
      "description": "Duration in seconds (0–100)."
    }
  },
  "required": [
    "duration"
  ]
}
""");
        return new ComputerUseToolDefinition("wait", "Wait for a specified duration.", s);
    }

    /** CCB tools.ts · cursor_position */
    private static ComputerUseToolDefinition CursorPosition(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {},
  "required": []
}
""");
        return new ComputerUseToolDefinition("cursor_position", "Get the current mouse cursor position. Returns image-pixel coordinates relative to the most recent screenshot, or logical points if no screenshot has been taken.", s);
    }

    /** CCB tools.ts · hold_key */
    private static ComputerUseToolDefinition HoldKey(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {
    "text": {
      "type": "string",
      "description": "Key or chord to hold, e.g. \\"space\\", \\"shift+down\\"."
    },
    "duration": {
      "type": "number",
      "description": "Duration in seconds (0–100)."
    }
  },
  "required": [
    "text",
    "duration"
  ]
}
""");
        return new ComputerUseToolDefinition("hold_key", "Press and hold a key or key combination for the specified duration, then release. " + FRONTMOST_GATE_DESC + " System-level combos require the `systemKeyCombos` grant.", s);
    }

    /** CCB tools.ts · left_mouse_down */
    private static ComputerUseToolDefinition LeftMouseDown(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {},
  "required": []
}
""");
        return new ComputerUseToolDefinition("left_mouse_down", "Press the left mouse button at the current cursor position and leave it held. " + FRONTMOST_GATE_DESC + " Use mouse_move first to position the cursor. Call left_mouse_up to release. Errors if the button is already held.", s);
    }

    /** CCB tools.ts · left_mouse_up */
    private static ComputerUseToolDefinition LeftMouseUp(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
{
  "type": "object",
  "properties": {},
  "required": []
}
""");
        return new ComputerUseToolDefinition("left_mouse_up", "Release the left mouse button at the current cursor position. " + FRONTMOST_GATE_DESC + " Pairs with left_mouse_down. Safe to call even if the button is not currently held.", s);
    }

    /** CCB tools.ts · computer_batch */
    private static ComputerUseToolDefinition ComputerBatch(String[] coord, ComputerUseCapabilities caps, String installedAppsHint) throws JsonProcessingException {
        ObjectNode s = (ObjectNode) JSON.readTree(
            """
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
            "items": {
              "type": "number"
            },
            "minItems": 2,
            "maxItems": 2,
            "description": "(x, y) for click/mouse_move/scroll/left_click_drag end point."
          },
          "start_coordinate": {
            "type": "array",
            "items": {
              "type": "number"
            },
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
            "enum": [
              "up",
              "down",
              "left",
              "right"
            ]
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
        "required": [
          "action"
        ]
      },
      "description": "List of actions. Example: [{\\"action\\":\\"left_click\\",\\"coordinate\\":[100,200]},{\\"action\\":\\"type\\",\\"text\\":\\"hello\\"},{\\"action\\":\\"key\\",\\"text\\":\\"Return\\"}]"
    }
  },
  "required": [
    "actions"
  ]
}
""");
        ((ObjectNode) s.at("/properties/actions")).put("items", batchActionItemSchema());
        return new ComputerUseToolDefinition("computer_batch", "Execute a sequence of actions in ONE tool call. Each individual tool call requires a model→API round trip (seconds); batching a predictable sequence eliminates all but one. Use this whenever you can predict the outcome of several actions ahead — e.g. click a field, type into it, press Return. Actions execute sequentially and stop on the first error. " + FRONTMOST_GATE_DESC + " The frontmost check runs before EACH action inside the batch — if an action opens a non-allowed app, the next action's gate fires and the batch stops there. Mid-batch screenshot actions are allowed for inspection but coordinates in subsequent clicks always refer to the PRE-BATCH full-screen screenshot.", s);
    }

    /**
     * 构建 computer-use 的 MCP 工具集 · 逐字对齐 CCB tools.ts:118-992 的
     * buildComputerUseTools(caps, coordinateMode, installedAppNames?)。
     *
     * @param caps              能力记录（screenshotFiltering / platform / teachMode）
     * @param coordinateMode    坐标模式（pixels / normalized_0_100）—— 冻结值
     * @param installedAppNames 已安装应用名（可空 ⇒ installedAppsHint 为空串，CCB 同款行为）
     * @return 工具定义列表（顺序与 CCB 一致）
     */
    public static List<ComputerUseToolDefinition> buildComputerUseTools(
            ComputerUseCapabilities caps, String coordinateMode, List<String> installedAppNames)
            throws JsonProcessingException {
        String[] coord = COORD_DESC.get(coordinateMode);
        String installedAppsHint = (installedAppNames == null || installedAppNames.isEmpty())
                ? ""
                : " Available applications on this machine: " + String.join(", ", installedAppNames) + ".";
        List<ComputerUseToolDefinition> tools = new ArrayList<>();
        tools.add(RequestAccess(coord, caps, installedAppsHint));
        tools.add(Screenshot(coord, caps, installedAppsHint));
        tools.add(Zoom(coord, caps, installedAppsHint));
        tools.add(LeftClick(coord, caps, installedAppsHint));
        tools.add(DoubleClick(coord, caps, installedAppsHint));
        tools.add(TripleClick(coord, caps, installedAppsHint));
        tools.add(RightClick(coord, caps, installedAppsHint));
        tools.add(MiddleClick(coord, caps, installedAppsHint));
        tools.add(Type(coord, caps, installedAppsHint));
        tools.add(Key(coord, caps, installedAppsHint));
        tools.add(Scroll(coord, caps, installedAppsHint));
        tools.add(LeftClickDrag(coord, caps, installedAppsHint));
        tools.add(MouseMove(coord, caps, installedAppsHint));
        tools.add(OpenApplication(coord, caps, installedAppsHint));
        // ── Windows 专属（CCB tools.ts:418-836 的 ...(caps.platform === 'win32' ? [...] : []) 就地展开）──
        if ("win32".equals(caps.platform())) {
            tools.add(WindowManagement(coord, caps, installedAppsHint));
            tools.add(ClickElement(coord, caps, installedAppsHint));
            tools.add(TypeIntoElement(coord, caps, installedAppsHint));
            tools.add(OpenTerminal(coord, caps, installedAppsHint));
            tools.add(BindWindow(coord, caps, installedAppsHint));
            tools.add(ActivateWindow(coord, caps, installedAppsHint));
            tools.add(PromptRespond(coord, caps, installedAppsHint));
            tools.add(StatusIndicator(coord, caps, installedAppsHint));
            tools.add(VirtualKeyboard(coord, caps, installedAppsHint));
            tools.add(VirtualMouse(coord, caps, installedAppsHint));
            tools.add(MouseWheel(coord, caps, installedAppsHint));
        }
        tools.add(SwitchDisplay(coord, caps, installedAppsHint));
        tools.add(ListGrantedApplications(coord, caps, installedAppsHint));
        tools.add(ReadClipboard(coord, caps, installedAppsHint));
        tools.add(WriteClipboard(coord, caps, installedAppsHint));
        tools.add(Wait(coord, caps, installedAppsHint));
        tools.add(CursorPosition(coord, caps, installedAppsHint));
        tools.add(HoldKey(coord, caps, installedAppsHint));
        tools.add(LeftMouseDown(coord, caps, installedAppsHint));
        tools.add(LeftMouseUp(coord, caps, installedAppsHint));
        tools.add(ComputerBatch(coord, caps, installedAppsHint));
        // ── teach 三件套（CCB tools.ts:990 的 ...(caps.teachMode ? buildTeachTools(...) : [])）──
        // 本仓 teachMode 恒 false（与 CCB CLI 的 CLI_CU_CAPABILITIES 一致）⇒ 恒不下发；分支结构保留。
        if (caps.teachMode()) {
            throw new UnsupportedOperationException(
                    "teach 工具（request_teach_access / teach_step / teach_batch）本仓不实现："
                            + "CCB CLI 侧同样因 teachMode 恒 false 而不下发（tools.ts:990）。");
        }

        return List.copyOf(tools);
    }
}
