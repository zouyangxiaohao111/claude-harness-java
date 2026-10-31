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
 * {@code mcp__computer-use__window_management} · 窗口管理工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:423-475}）</h2>
 * <pre>
 * name: 'window_management',
 * description: 'Manage the bound application window via Win32 API calls (ShowWindow, SetWindowPos, SendMessage). ' +
 *   'All operations target the bound HWND directly — NO global shortcuts (Win+Down, Alt+F4, etc.). ' +
 *   'The window must have been opened via open_application first. ' +
 *   'Actions: minimize (hide to taskbar), maximize (fill screen), restore (undo min/max), ' +
 *   'close (graceful WM_CLOSE), focus (bring to front), move_offscreen (move to -32000,-32000 for background operation). ' +
 *   'Use move_resize to reposition or resize the window to specific coordinates.',
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     action: { type: 'string', enum: [minimize,maximize,restore,close,focus,move_offscreen,move_resize,get_rect], description: '...' },
 *     x: { type: 'integer', description: 'X position for move_resize.' },
 *     y: { type: 'integer', description: 'Y position for move_resize.' },
 *     width: { type: 'integer', description: 'Width for move_resize.' },
 *     height: { type: 'integer', description: 'Height for move_resize.' },
 *   },
 *   required: ['action'],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code toolCalls.ts:3378-3476}）</h2>
 * <ul>
 *   <li><b>只作用于已绑定窗口</b>：未绑定 ⇒ 报错
 *       {@code "No window is currently bound. Call open_application first."}；</li>
 *   <li>{@code minimize} ⇒ {@code ShowWindow(SW_SHOWMINNOACTIVE)}；
 *       {@code maximize} ⇒ {@code SW_MAXIMIZE}；{@code restore} ⇒ {@code SW_RESTORE}；</li>
 *   <li>{@code close} ⇒ {@code SendMessage(WM_CLOSE)} + <b>解除绑定</b>；</li>
 *   <li>{@code focus} ⇒ 最小化时先 {@code SW_RESTORE}，再 {@code SetForegroundWindow} +
 *       {@code BringWindowToTop}；</li>
 *   <li>{@code move_offscreen} ⇒ {@code SetWindowPos(-32000,-32000, SWP_NOSIZE|SWP_NOZORDER|SWP_NOACTIVATE)}；</li>
 *   <li>{@code move_resize} ⇒ {@code SetWindowPos(x,y,w,h)}（宽高缺省 ⇒ 只移动）；</li>
 *   <li>{@code get_rect} ⇒ 读 {@code GetWindowRect} 返回 {@code x,y,width,height}。</li>
 * </ul>
 *
 * <p><b>⚠️ 无全局快捷键</b>：CCB 明确不做 {@code Win+Down} / {@code Alt+F4} 之类的全局快捷键
 * （{@code tools.ts:425-427}）⇒ 本工具只调 Win32 API 作用于<b>绑定窗口</b>，不抢用户焦点。
 */
public final class ComputerUseWindowManagementTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseWindowManagementTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = "mcp__computer-use__window_management";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:424-430}。 */
    private static final String DESCRIPTION =
            "Manage the bound application window via Win32 API calls (ShowWindow, SetWindowPos, SendMessage). "
                    + "All operations target the bound HWND directly — NO global shortcuts (Win+Down, Alt+F4, etc.). "
                    + "The window must have been opened via open_application first. "
                    + "Actions: minimize (hide to taskbar), maximize (fill screen), restore (undo min/max), "
                    + "close (graceful WM_CLOSE), focus (bring to front), move_offscreen (move to -32000,-32000 for background operation). "
                    + "Use move_resize to reposition or resize the window to specific coordinates.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:431-474}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["minimize", "maximize", "restore", "close", "focus", "move_offscreen", "move_resize", "get_rect"],
                  "description": "minimize: ShowWindow(SW_MINIMIZE). maximize: ShowWindow(SW_MAXIMIZE). restore: ShowWindow(SW_RESTORE) — undo minimize or maximize. close: SendMessage(WM_CLOSE) — graceful close. focus: SetForegroundWindow + BringWindowToTop. move_offscreen: SetWindowPos(-32000,-32000) — keeps window usable by SendMessage/PrintWindow but invisible. move_resize: SetWindowPos to specific x,y,width,height. get_rect: GetWindowRect — returns current position and size."
                },
                "x": { "type": "integer", "description": "X position for move_resize." },
                "y": { "type": "integer", "description": "Y position for move_resize." },
                "width": { "type": "integer", "description": "Width for move_resize." },
                "height": { "type": "integer", "description": "Height for move_resize." }
              },
              "required": ["action"]
            }
            """;

    /** 合法动作集合 · CCB {@code toolCalls.ts:3385-3394}。 */
    private static final List<String> VALID_ACTIONS = List.of(
            "minimize", "maximize", "restore", "close", "focus", "move_offscreen", "move_resize", "get_rect");

    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;

    /**
     * @param windowOps    窗口操作原语
     * @param bindingStore 绑定窗口存储
     */
    public ComputerUseWindowManagementTool(ComputerUseWindowOps windowOps,
                                           ComputerUseWindowBindingStore bindingStore) {
        this.windowOps = windowOps;
        this.bindingStore = bindingStore;
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
                    "[computer-use] window_management inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 窗口管理会改系统状态 ⇒ 非只读、不可并发。 */
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
                new PermissionDecisionReason.Other("computer-use window management is gated by the "
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
            // CCB toolCalls.ts:3380 —— requireString 的错误文案
            return ToolResult.error(call.id(), "\"action\" must be a string.");
        }
        String action = actionNode.asText();
        if (!VALID_ACTIONS.contains(action)) {
            // CCB toolCalls.ts:3396-3399
            return ToolResult.error(call.id(),
                    "Unknown window_management action \"" + action + "\". Valid: " + String.join(", ", VALID_ACTIONS));
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;
        if (binding == null) {
            // CCB toolCalls.ts:3417-3419 / :3443-3445
            return ToolResult.error(call.id(),
                    "No window is currently bound. Call open_application first.");
        }
        long hwnd = binding.hwnd();

        try {
            switch (action) {
                case "minimize":
                    // CCB platforms/win32.ts:858-862 —— 实际用 SW_SHOWMINNOACTIVE
                    windowOps.showWindow(hwnd, ComputerUseWindowOps.SW_SHOWMINNOACTIVE);
                    return ToolResult.success(call.id(), "Window minimized (ShowWindow SW_MINIMIZE).");
                case "maximize":
                    windowOps.showWindow(hwnd, ComputerUseWindowOps.SW_MAXIMIZE);
                    return ToolResult.success(call.id(), "Window maximized (ShowWindow SW_MAXIMIZE).");
                case "restore":
                    windowOps.showWindow(hwnd, ComputerUseWindowOps.SW_RESTORE);
                    return ToolResult.success(call.id(), "Window restored (ShowWindow SW_RESTORE).");
                case "close":
                    // CCB platforms/win32.ts:876-883 —— WM_CLOSE 后解除绑定
                    windowOps.closeWindow(hwnd);
                    if (bindingStore != null) {
                        bindingStore.unbind(sessionId);
                    }
                    return ToolResult.success(call.id(),
                            "Window closed (SendMessage WM_CLOSE). The window binding has been released.");
                case "focus":
                    windowOps.focusWindow(hwnd);
                    return ToolResult.success(call.id(), "Window brought to front (SetForegroundWindow).");
                case "move_offscreen":
                    windowOps.moveOffscreen(hwnd);
                    return ToolResult.success(call.id(),
                            "Window moved offscreen (-32000,-32000). Still usable via SendMessage/PrintWindow.");
                case "move_resize":
                    return moveResize(call, input, hwnd);
                case "get_rect":
                    return getRect(call, hwnd);
                default:
                    return ToolResult.error(call.id(),
                            "Unknown window_management action \"" + action + "\". Valid: " + String.join(", ", VALID_ACTIONS));
            }
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: action={} hwnd={} err={}",
                    TOOL_NAME, action, hwnd, e.getMessage(), e);
            return ToolResult.error(call.id(), "窗口操作失败: " + e.getMessage());
        }
    }

    /** {@code move_resize} · CCB {@code toolCalls.ts:3427-3452}。 */
    private AgentToolResult<?> moveResize(ToolUseBlock call, JsonNode input, long hwnd) {
        JsonNode xNode = input.path("x");
        JsonNode yNode = input.path("y");
        if (!xNode.isNumber() || !yNode.isNumber()) {
            // CCB toolCalls.ts:3430-3432
            return ToolResult.error(call.id(), "move_resize requires x and y parameters.");
        }
        int x = xNode.asInt();
        int y = yNode.asInt();
        Integer width = input.path("width").isNumber() ? input.path("width").asInt() : null;
        Integer height = input.path("height").isNumber() ? input.path("height").asInt() : null;
        windowOps.moveResize(hwnd, x, y, width, height);
        if (width != null && height != null) {
            return ToolResult.success(call.id(),
                    "Moved window to (" + x + ", " + y + ") and resized to " + width + "×" + height + ".");
        }
        return ToolResult.success(call.id(), "Moved window to (" + x + ", " + y + ").");
    }

    /** {@code get_rect} · CCB {@code toolCalls.ts:3410-3424}。 */
    private AgentToolResult<?> getRect(ToolUseBlock call, long hwnd) {
        int[] rect = windowOps.getWindowRect(hwnd);
        if (rect == null) {
            return ToolResult.error(call.id(), "No window is currently bound. Call open_application first.");
        }
        return ToolResult.success(call.id(),
                "Window rect: x=" + rect[0] + ", y=" + rect[1]
                        + ", width=" + rect[2] + ", height=" + rect[3]);
    }
}
