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
import java.util.Locale;

/**
 * {@code mcp__computer-use__bind_window} · 窗口绑定工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:577-615}）</h2>
 * <pre>
 * name: 'bind_window',
 * description: 'Bind to a specific window for all subsequent operations (screenshot, click, type, etc.). ' +
 *   'Once bound, screenshots capture only that window via PrintWindow, and all input goes through SendMessageW — ' +
 *   "no cursor movement, no focus steal, no interference with the user's desktop. " +
 *   'Actions: bind (by title, hwnd, or pid), unbind (release binding), status (show current binding), list (show all visible windows). ' +
 *   "Use 'list' first to see available windows, then 'bind' with a title or hwnd. " +
 *   'open_application auto-binds the launched window, but use this tool to bind to already-running windows or switch between windows.',
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     action: { type: 'string', enum: [bind,unbind,status,list], description: '...' },
 *     title: { type: 'string', description: "Window title to search for (partial match, case-insensitive). For 'bind' action." },
 *     hwnd: { type: 'string', description: "Exact window handle from 'list' output. For 'bind' action." },
 *     pid: { type: 'integer', description: "Process ID to find window for. For 'bind' action." },
 *   },
 *   required: ['action'],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code toolCalls.ts:3228-3313}）</h2>
 * <ul>
 *   <li>{@code list} ⇒ 列出所有<b>可见且有标题</b>的窗口
 *       （{@code "Visible windows (N):\nhwnd=... pid=... \"title\""}）；</li>
 *   <li>{@code status} ⇒ 报当前绑定（{@code "Bound to: hwnd=... \"title\" pid=... rect=(x,y WxH)"}）；</li>
 *   <li>{@code bind} ⇒ 按 {@code title}（大小写不敏感<b>部分</b>匹配）/ {@code hwnd}（精确）/ {@code pid} 绑定；</li>
 *   <li>{@code unbind} ⇒ 解除绑定，回到全屏模式。</li>
 * </ul>
 *
 * <p><b>⚠️ 绑定顺序（CCB {@code executorCrossPlatform.ts:661-672}）</b>：{@code hwnd} 优先
 * ⇒ {@code title} ⇒ {@code pid}。
 */
public final class ComputerUseBindWindowTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseBindWindowTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = "mcp__computer-use__bind_window";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:578-584}。 */
    private static final String DESCRIPTION =
            "Bind to a specific window for all subsequent operations (screenshot, click, type, etc.). "
                    + "Once bound, screenshots capture only that window via PrintWindow, and all input goes through SendMessageW — "
                    + "no cursor movement, no focus steal, no interference with the user's desktop. "
                    + "Actions: bind (by title, hwnd, or pid), unbind (release binding), status (show current binding), list (show all visible windows). "
                    + "Use 'list' first to see available windows, then 'bind' with a title or hwnd. "
                    + "open_application auto-binds the launched window, but use this tool to bind to already-running windows or switch between windows.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:585-614}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["bind", "unbind", "status", "list"],
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
              "required": ["action"]
            }
            """;

    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;
    private final ComputerUseProcessAppResolver appResolver;
    private final ComputerUseGrantStore grantStore;

    /**
     * @param windowOps    窗口操作原语
     * @param bindingStore 绑定窗口存储
     * @param appResolver  进程 → 授权键解析器（用于查绑定窗口的档位；null ⇒ 不查）
     * @param grantStore   会话授权位存储（null ⇒ 不查）
     */
    public ComputerUseBindWindowTool(ComputerUseWindowOps windowOps,
                                     ComputerUseWindowBindingStore bindingStore,
                                     ComputerUseProcessAppResolver appResolver,
                                     ComputerUseGrantStore grantStore) {
        this.windowOps = windowOps;
        this.bindingStore = bindingStore;
        this.appResolver = appResolver;
        this.grantStore = grantStore;
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
                    "[computer-use] bind_window inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 绑定会改会话状态 ⇒ 非只读、不可并发。 */
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
                new PermissionDecisionReason.Other("computer-use window binding is gated by the "
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
            return ToolResult.error(call.id(), "\"action\" must be a string.");
        }
        String action = actionNode.asText();
        String sessionId = ctx != null ? ctx.sessionId() : null;

        switch (action) {
            case "list":
                return listWindows(call);
            case "status":
                return status(call, sessionId);
            case "bind":
                return bind(call, input, sessionId);
            case "unbind":
                if (bindingStore != null) {
                    bindingStore.unbind(sessionId);
                }
                return ToolResult.success(call.id(),
                        "Window binding released. Operations now target the full screen.");
            default:
                // CCB toolCalls.ts:3308-3310
                return ToolResult.error(call.id(),
                        "Unknown bind_window action \"" + action + "\". Valid: list, bind, unbind, status.");
        }
    }

    /** {@code list} · CCB {@code toolCalls.ts:3236-3247}。 */
    private AgentToolResult<?> listWindows(ToolUseBlock call) {
        List<ComputerUseWindowInfo> windows = windowOps.listVisibleWindows();
        if (windows.isEmpty()) {
            return ToolResult.success(call.id(), "No visible windows found.");
        }
        StringBuilder sb = new StringBuilder("Visible windows (").append(windows.size()).append("):");
        for (ComputerUseWindowInfo w : windows) {
            sb.append('\n')
                    .append("hwnd=").append(w.hwndText())
                    .append(" pid=").append(w.pid())
                    .append(" \"").append(w.title()).append('"');
        }
        return ToolResult.success(call.id(), sb.toString());
    }

    /** {@code status} · CCB {@code toolCalls.ts:3248-3267}。 */
    private AgentToolResult<?> status(ToolUseBlock call, String sessionId) {
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;
        if (binding == null) {
            return ToolResult.success(call.id(),
                    "No window is currently bound. Use bind_window(action='list') to see available windows, "
                            + "then bind_window(action='bind', title='...') to bind.");
        }
        ComputerUseWindowInfo window = binding.window();
        StringBuilder sb = new StringBuilder("Bound to: hwnd=").append(window.hwndText());
        if (!window.title().isEmpty()) {
            sb.append(" \"").append(window.title()).append('"');
        }
        sb.append(" pid=").append(window.pid());
        int[] rect = windowOps.getWindowRect(window.hwnd());
        if (rect != null) {
            sb.append(" rect=(").append(rect[0]).append(',').append(rect[1]).append(' ')
                    .append(rect[2]).append('x').append(rect[3]).append(')');
        }
        return ToolResult.success(call.id(), sb.toString());
    }

    /** {@code bind} · CCB {@code toolCalls.ts:3268-3294} + {@code executorCrossPlatform.ts:661-672}。 */
    private AgentToolResult<?> bind(ToolUseBlock call, JsonNode input, String sessionId) {
        String title = input.path("title").isTextual() ? input.path("title").asText() : null;
        String hwndText = input.path("hwnd").isTextual() ? input.path("hwnd").asText() : null;
        Integer pid = input.path("pid").isNumber() ? input.path("pid").asInt() : null;
        if ((title == null || title.isEmpty())
                && (hwndText == null || hwndText.isEmpty())
                && pid == null) {
            // CCB toolCalls.ts:3278-3281
            return ToolResult.error(call.id(), "Specify at least one of: title, hwnd, or pid.");
        }

        List<ComputerUseWindowInfo> windows = windowOps.listVisibleWindows();
        ComputerUseWindowInfo target = null;
        if (hwndText != null && !hwndText.isEmpty()) {
            target = findByHwnd(windows, hwndText);
        } else if (title != null && !title.isEmpty()) {
            // CCB executorCrossPlatform.ts:666-668 —— 大小写不敏感的部分匹配
            String lower = title.toLowerCase(Locale.ROOT);
            for (ComputerUseWindowInfo w : windows) {
                if (w.title().toLowerCase(Locale.ROOT).contains(lower)) {
                    target = w;
                    break;
                }
            }
        } else if (pid != null) {
            for (ComputerUseWindowInfo w : windows) {
                if (w.pid() == pid) {
                    target = w;
                    break;
                }
            }
        }

        if (target == null) {
            // CCB toolCalls.ts:3286-3290
            StringBuilder criteria = new StringBuilder();
            if (title != null && !title.isEmpty()) {
                criteria.append("title=\"").append(title).append('"');
            }
            if (hwndText != null && !hwndText.isEmpty()) {
                criteria.append(criteria.length() > 0 ? ", " : "").append("hwnd=").append(hwndText);
            }
            if (pid != null) {
                criteria.append(criteria.length() > 0 ? ", " : "").append("pid=").append(pid);
            }
            return ToolResult.error(call.id(),
                    "No window found matching: " + criteria
                            + ". Use bind_window(action='list') to see available windows.");
        }

        // 绑定时冻结该窗口所属应用的授权键与档位（后续窗口定向输入按它门控）
        String appId = appResolver != null ? appResolver.appIdForPid(target.pid()) : null;
        String tier = null;
        if (grantStore != null) {
            ComputerUseGrant grant = grantStore.read(sessionId).findByAppId(appId);
            tier = grant != null ? grant.tier() : null;
        }
        if (bindingStore != null) {
            bindingStore.bind(sessionId, new ComputerUseWindowBinding(target, appId, tier));
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 绑定窗口: hwnd={} pid={} title=\"{}\" appId={} tier={}",
                    TOOL_NAME, target.hwndText(), target.pid(), target.title(), appId, tier);
        }

        // CCB toolCalls.ts:3291-3293
        return ToolResult.success(call.id(),
                "Bound to window: hwnd=" + target.hwndText() + " pid=" + target.pid()
                        + " \"" + target.title() + "\". "
                        + "All subsequent screenshot/click/type operations target this window.");
    }

    /** 按 hwnd 精确匹配（CCB {@code executorCrossPlatform.ts:662-664}）。 */
    private static ComputerUseWindowInfo findByHwnd(List<ComputerUseWindowInfo> windows, String hwndText) {
        for (ComputerUseWindowInfo w : windows) {
            if (w.hwndText().equals(hwndText)) {
                return w;
            }
        }
        return null;
    }
}
