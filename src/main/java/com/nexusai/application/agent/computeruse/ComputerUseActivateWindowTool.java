package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpStringUtils;
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
 * {@code mcp__computer-use__activate_window} · 窗口激活工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:617-639}）</h2>
 * <pre>
 * name: 'activate_window',
 * description: 'Activate the bound window: bring it to foreground, click to ensure keyboard focus, ' +
 *   'and optionally send an initial key sequence. Use this before any input operations to guarantee ' +
 *   'the window is ready to receive keyboard/mouse events. ' +
 *   'Combines SetForegroundWindow + BringWindowToTop + SendMessage(WM_LBUTTONDOWN) in one call.',
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     click_x: { type: 'integer', description: 'X coordinate to click after activation (client-area). If omitted, clicks center of window.' },
 *     click_y: { type: 'integer', description: 'Y coordinate to click after activation (client-area). If omitted, clicks center of window.' },
 *   },
 *   required: [],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code executorCrossPlatform.ts:942-972}）</h2>
 * <ol>
 *   <li>未绑定窗口 ⇒ 报错 {@code "activate_window is only available on Windows with a bound window."}；</li>
 *   <li>{@code manageWindow('focus')}（最小化时先 {@code SW_RESTORE}，再
 *       {@code SetForegroundWindow} + {@code BringWindowToTop}）；</li>
 *   <li>给了 {@code click_x}/{@code click_y} ⇒ 在该<b>客户区</b>坐标点击；
 *       没给 ⇒ 点窗口中心。</li>
 * </ol>
 */
public final class ComputerUseActivateWindowTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseActivateWindowTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "activate_window");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:618-622}。 */
    private static final String DESCRIPTION =
            "Activate the bound window: bring it to foreground, click to ensure keyboard focus, "
                    + "and optionally send an initial key sequence. "
                    + "Use this before any input operations to guarantee "
                    + "the window is ready to receive keyboard/mouse events. "
                    + "Combines SetForegroundWindow + BringWindowToTop + SendMessage(WM_LBUTTONDOWN) in one call.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:623-638}。 */
    private static final String INPUT_SCHEMA = """
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
            """;

    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;

    /**
     * @param windowOps    窗口操作原语
     * @param bindingStore 绑定窗口存储
     */
    public ComputerUseActivateWindowTool(ComputerUseWindowOps windowOps,
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
                    "[computer-use] activate_window inputSchema 解析失败: " + e.getMessage(), e);
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
                new PermissionDecisionReason.Other("computer-use activate_window is gated by the "
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
        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;
        if (binding == null) {
            // CCB toolCalls.ts:3084-3087
            return ToolResult.error(call.id(),
                    "activate_window is only available on Windows with a bound window.");
        }
        long hwnd = binding.hwnd();

        JsonNode xNode = input.path("click_x");
        JsonNode yNode = input.path("click_y");
        boolean hasClick = xNode.isNumber() && yNode.isNumber();

        try {
            // 置前（CCB executorCrossPlatform.ts:950-952）
            windowOps.focusWindow(hwnd);

            int clientX;
            int clientY;
            if (hasClick) {
                // CCB executorCrossPlatform.ts:956-958 —— 已是客户区坐标，直接用
                clientX = xNode.asInt();
                clientY = yNode.asInt();
            } else {
                // CCB executorCrossPlatform.ts:960-965 —— 点窗口中心
                int[] rect = windowOps.getWindowRect(hwnd);
                int[] offset = windowOps.getClientOffset(hwnd);
                int width = rect != null ? rect[2] : 0;
                int height = rect != null ? rect[3] : 0;
                int dx = offset != null ? offset[0] : 0;
                int dy = offset != null ? offset[1] : 0;
                clientX = Math.round(width / 2f) - dx;
                clientY = Math.round(height / 2f) - dy;
            }
            // CCB executorCrossPlatform.ts:957 —— SendMessage 点击
            windowOps.sendClick(hwnd, clientX, clientY, false);

            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: hwnd={} client=({},{}) 显式坐标={}",
                        TOOL_NAME, hwnd, clientX, clientY, hasClick);
            }
            // CCB toolCalls.ts:3098
            return ToolResult.success(call.id(), "Window activated and focused. Ready for input.");
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: hwnd={} err={}", TOOL_NAME, hwnd, e.getMessage(), e);
            return ToolResult.error(call.id(), "窗口激活失败: " + e.getMessage());
        }
    }
}
