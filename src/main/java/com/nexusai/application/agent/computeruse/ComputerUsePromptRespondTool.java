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
 * {@code mcp__computer-use__prompt_respond} · 终端提示应答工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:641-685}）</h2>
 * <pre>
 * name: 'prompt_respond',
 * description: 'Handle interactive CLI/terminal prompts (Yes/No, selection menus, confirmations). ' +
 *   'Sends a sequence of key events to the bound window to navigate and confirm a prompt. ' +
 *   'This is a convenience wrapper around bound-window keyboard input for common prompt flows. ' +
 *   'Typical flows: ' +
 *   "1) Yes/No prompt → send 'y' or 'n' + Enter. " +
 *   '2) Arrow-key selection menu → send arrow_down/arrow_up N times + Enter. ' +
 *   "3) Text input prompt → type the response + Enter. " +
 *   'After responding, take a screenshot to verify the result.',
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     response_type: { type: 'string', enum: [yes,no,enter,escape,select,type], description: '...' },
 *     arrow_direction: { type: 'string', enum: [up,down], description: "Arrow key direction for 'select' type. Default: 'down'." },
 *     arrow_count: { type: 'integer', minimum: 0, maximum: 50, description: "Number of arrow key presses for 'select' type. Default: 1." },
 *     text: { type: 'string', description: "Text to type for 'type' response_type." },
 *   },
 *   required: ['response_type'],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code executorCrossPlatform.ts:974-1042}）</h2>
 * <ul>
 *   <li>{@code yes} ⇒ 发 {@code 'y'} + Enter；{@code no} ⇒ {@code 'n'} + Enter；</li>
 *   <li>{@code enter} / {@code escape} ⇒ 单键；</li>
 *   <li>{@code select} ⇒ 方向键 N 次 + Enter（{@code arrow_count} 缺省 1）；</li>
 *   <li>{@code type} ⇒ 输入文本 + Enter。</li>
 * </ul>
 *
 * <p><b>键投递方式</b>：字符走 {@code SendMessage(WM_CHAR)}，功能键走
 * {@code PostMessage(WM_KEYDOWN/UP)}（CCB {@code windowMessage.ts:252-308}）——
 * 全部<b>只发给绑定窗口</b>，不动全局键盘。
 */
public final class ComputerUsePromptRespondTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUsePromptRespondTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = "mcp__computer-use__prompt_respond";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:642-650}。 */
    private static final String DESCRIPTION =
            "Handle interactive CLI/terminal prompts (Yes/No, selection menus, confirmations). "
                    + "Sends a sequence of key events to the bound window to navigate and confirm a prompt. "
                    + "This is a convenience wrapper around bound-window keyboard input for common prompt flows. "
                    + "Typical flows: "
                    + "1) Yes/No prompt → send 'y' or 'n' + Enter. "
                    + "2) Arrow-key selection menu → send arrow_down/arrow_up N times + Enter. "
                    + "3) Text input prompt → type the response + Enter. "
                    + "After responding, take a screenshot to verify the result.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:651-684}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "response_type": {
                  "type": "string",
                  "enum": ["yes", "no", "enter", "escape", "select", "type"],
                  "description": "yes: send 'y' + Enter. no: send 'n' + Enter. enter: send Enter only. escape: send Escape (cancel). select: use arrow keys to navigate to an option, then Enter. Requires 'arrow_count'. type: type custom text then Enter. Requires 'text'."
                },
                "arrow_direction": {
                  "type": "string",
                  "enum": ["up", "down"],
                  "description": "Arrow key direction for 'select' type. Default: 'down'."
                },
                "arrow_count": {
                  "type": "integer",
                  "minimum": 0,
                  "maximum": 50,
                  "description": "Number of arrow key presses for 'select' type. Default: 1."
                },
                "text": {
                  "type": "string",
                  "description": "Text to type for 'type' response_type."
                }
              },
              "required": ["response_type"]
            }
            """;

    /** 合法响应类型 · CCB {@code toolCalls.ts:3115}。 */
    private static final List<String> VALID_TYPES = List.of("yes", "no", "enter", "escape", "select", "type");

    /** {@code VK_RETURN}。 */
    private static final int VK_RETURN = 0x0d;
    /** {@code VK_ESCAPE}。 */
    private static final int VK_ESCAPE = 0x1b;
    /** {@code VK_UP}。 */
    private static final int VK_UP = 0x26;
    /** {@code VK_DOWN}。 */
    private static final int VK_DOWN = 0x28;

    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;
    private final ComputerUseSleeper sleeper;

    /**
     * @param windowOps    窗口操作原语
     * @param bindingStore 绑定窗口存储
     * @param sleeper      休眠器（可注入假时钟）
     */
    public ComputerUsePromptRespondTool(ComputerUseWindowOps windowOps,
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
                    "[computer-use] prompt_respond inputSchema 解析失败: " + e.getMessage(), e);
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
                new PermissionDecisionReason.Other("computer-use prompt_respond is gated by the "
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
        JsonNode typeNode = input.path("response_type");
        if (!typeNode.isTextual()) {
            return ToolResult.error(call.id(), "\"response_type\" must be a string.");
        }
        String responseType = typeNode.asText();
        if (!VALID_TYPES.contains(responseType)) {
            // CCB toolCalls.ts:3116-3119
            return ToolResult.error(call.id(),
                    "Invalid response_type \"" + responseType + "\". Valid: " + String.join(", ", VALID_TYPES));
        }

        JsonNode arrowCountNode = input.path("arrow_count");
        String arrowDirection = input.path("arrow_direction").isTextual()
                ? input.path("arrow_direction").asText() : "down";
        String text = input.path("text").isTextual() ? input.path("text").asText() : null;

        if ("select".equals(responseType) && !arrowCountNode.isNumber()) {
            // CCB toolCalls.ts:3123-3125
            return ToolResult.error(call.id(), "'select' requires arrow_count parameter.");
        }
        if ("type".equals(responseType) && text == null) {
            // CCB toolCalls.ts:3126-3128
            return ToolResult.error(call.id(), "'type' requires text parameter.");
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;
        if (binding == null) {
            // CCB toolCalls.ts:3141-3144
            return ToolResult.error(call.id(),
                    "prompt_respond is only available on Windows with a bound window.");
        }
        long hwnd = binding.hwnd();

        try {
            String description;
            switch (responseType) {
                case "yes":
                    windowOps.sendChar(hwnd, 'y');
                    sleeper.sleep(50L);
                    pressEnter(hwnd);
                    description = "Sent 'y' + Enter.";
                    break;
                case "no":
                    windowOps.sendChar(hwnd, 'n');
                    sleeper.sleep(50L);
                    pressEnter(hwnd);
                    description = "Sent 'n' + Enter.";
                    break;
                case "enter":
                    pressEnter(hwnd);
                    description = "Sent Enter.";
                    break;
                case "escape":
                    windowOps.sendKeyDown(hwnd, VK_ESCAPE);
                    windowOps.sendKeyUp(hwnd, VK_ESCAPE);
                    description = "Sent Escape.";
                    break;
                case "select": {
                    int count = arrowCountNode.asInt();
                    int vk = "up".equals(arrowDirection) ? VK_UP : VK_DOWN;
                    for (int i = 0; i < count; i++) {
                        windowOps.sendKeyDown(hwnd, vk);
                        windowOps.sendKeyUp(hwnd, vk);
                        sleeper.sleep(30L);
                    }
                    sleeper.sleep(50L);
                    pressEnter(hwnd);
                    description = "Navigated " + arrowDirection + " " + count + " time(s) + Enter.";
                    break;
                }
                case "type":
                    windowOps.sendText(hwnd, text);
                    sleeper.sleep(50L);
                    pressEnter(hwnd);
                    description = "Typed \"" + text + "\" + Enter.";
                    break;
                default:
                    return ToolResult.error(call.id(),
                            "Invalid response_type \"" + responseType + "\". Valid: " + String.join(", ", VALID_TYPES));
            }

            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: type={} hwnd={}", TOOL_NAME, responseType, hwnd);
            }
            // CCB toolCalls.ts:3157-3159
            return ToolResult.success(call.id(),
                    "Prompt responded: " + description + ". Take a screenshot to verify.");
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: type={} hwnd={} err={}",
                    TOOL_NAME, responseType, hwnd, e.getMessage(), e);
            return ToolResult.error(call.id(), "提示应答失败: " + e.getMessage());
        }
    }

    /** 发 Enter 键（CCB {@code executorCrossPlatform.ts:998-999}）。 */
    private void pressEnter(long hwnd) {
        windowOps.sendKeyDown(hwnd, VK_RETURN);
        windowOps.sendKeyUp(hwnd, VK_RETURN);
    }
}
