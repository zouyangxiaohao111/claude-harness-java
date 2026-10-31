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
 * {@code mcp__computer-use__status_indicator} · 状态浮层控制工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:687-710}）</h2>
 * <pre>
 * name: 'status_indicator',
 * description: 'Control the visual status indicator overlay on the bound window. ' +
 *   'The indicator is a small floating label at the bottom of the window that shows what Computer Use is doing. ' +
 *   'It auto-shows during click/type/key/scroll operations, but you can also send custom messages. ' +
 *   'Actions: show (display a custom message), hide (dismiss), status (check if active).',
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     action: { type: 'string', enum: [show,hide,status], description: 'show: display a custom message on the indicator. hide: dismiss the indicator. status: check if indicator is active.' },
 *     message: { type: 'string', description: "Custom message to display (for 'show' action). Supports emoji. Auto-fades after 2 seconds." },
 *   },
 *   required: ['action'],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code toolCalls.ts:3004-3038}）</h2>
 * <ul>
 *   <li>{@code show} ⇒ 必须带 {@code message}（缺 ⇒ 报错
 *       {@code "'show' requires a message parameter."}）；</li>
 *   <li>{@code hide} ⇒ 关闭浮层；</li>
 *   <li>{@code status} ⇒ 报浮层是否在显示。</li>
 * </ul>
 *
 * <p><b>⚠️ 浮层挂在哪个窗口</b>：CCB 的浮层跟随<b>绑定窗口</b>（{@code bindWindow} 时
 * {@code showIndicator(hwnd)}，见 {@code platforms/win32.ts:198}）。
 * 本仓的浮层在 {@code show} 时才创建 ⇒ 挂在<b>当次 {@code show} 时的绑定窗口</b>。
 */
public final class ComputerUseStatusIndicatorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseStatusIndicatorTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = "mcp__computer-use__status_indicator";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:688-692}。 */
    private static final String DESCRIPTION =
            "Control the visual status indicator overlay on the bound window. "
                    + "The indicator is a small floating label at the bottom of the window that shows what Computer Use is doing. "
                    + "It auto-shows during click/type/key/scroll operations, but you can also send custom messages. "
                    + "Actions: show (display a custom message), hide (dismiss), status (check if active).";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:693-709}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["show", "hide", "status"],
                  "description": "show: display a custom message on the indicator. hide: dismiss the indicator. status: check if indicator is active."
                },
                "message": {
                  "type": "string",
                  "description": "Custom message to display (for 'show' action). Supports emoji. Auto-fades after 2 seconds."
                }
              },
              "required": ["action"]
            }
            """;

    private final ComputerUseStatusIndicator indicator;
    private final ComputerUseWindowBindingStore bindingStore;

    /**
     * @param indicator    状态浮层
     * @param bindingStore 绑定窗口存储
     */
    public ComputerUseStatusIndicatorTool(ComputerUseStatusIndicator indicator,
                                          ComputerUseWindowBindingStore bindingStore) {
        this.indicator = indicator;
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
                    "[computer-use] status_indicator inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 浮层会改桌面显示 ⇒ 非只读、不可并发。 */
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
                new PermissionDecisionReason.Other("computer-use status_indicator is gated by the "
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
        if (!List.of("show", "hide", "status").contains(action)) {
            // CCB toolCalls.ts:3016-3019
            return ToolResult.error(call.id(),
                    "Invalid action \"" + action + "\". Valid: show, hide, status.");
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;

        switch (action) {
            case "show": {
                JsonNode messageNode = input.path("message");
                if (!messageNode.isTextual() || messageNode.asText().isEmpty()) {
                    // CCB toolCalls.ts:3023-3025
                    return ToolResult.error(call.id(), "'show' requires a message parameter.");
                }
                if (binding == null) {
                    // CCB toolCalls.ts:3084-3087 —— 没有绑定窗口就没有浮层的挂载点
                    return ToolResult.error(call.id(),
                            "status_indicator is only available on Windows with a bound window.");
                }
                indicator.show(binding.hwnd(), messageNode.asText());
                if (log.isInfoEnabled()) {
                    log.info("[computer-use] {} 显示浮层: hwnd={}", TOOL_NAME, binding.hwnd());
                }
                // CCB toolCalls.ts:3035
                return ToolResult.success(call.id(), "Indicator showing: \"" + messageNode.asText() + "\"");
            }
            case "hide":
                indicator.hide();
                // CCB toolCalls.ts:3037
                return ToolResult.success(call.id(), "Indicator hidden.");
            case "status":
                // CCB toolCalls.ts:3027-3032 —— active 取决于绑定窗口是否存在
                return ToolResult.success(call.id(),
                        binding != null
                                ? "Indicator is active on the bound window."
                                : "Indicator is not active (no window bound).");
            default:
                return ToolResult.error(call.id(),
                        "Invalid action \"" + action + "\". Valid: show, hide, status.");
        }
    }
}
