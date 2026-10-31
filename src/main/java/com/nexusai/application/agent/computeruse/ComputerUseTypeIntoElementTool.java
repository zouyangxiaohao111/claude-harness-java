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
 * {@code mcp__computer-use__type_into_element} · UIA 元素输入工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:506-533}）</h2>
 * <pre>
 * name: 'type_into_element',
 * description: 'Type text into a named GUI element using Windows UI Automation ValuePattern. ' +
 *   'Finds the element by name/role/automationId, then sets its value directly — ' +
 *   'no need to click first or use pixel coordinates. Works on Edit, ComboBox, and other value-holding controls.',
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     name: { type: 'string', description: 'Accessible name of the target element.' },
 *     role: { type: 'string', description: 'Control type (optional, e.g. "Edit").' },
 *     automationId: { type: 'string', description: 'Exact automationId.' },
 *     text: { type: 'string', description: 'Text to type/set into the element.' },
 *   },
 *   required: ['text'],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code executorCrossPlatform.ts:1091-1143}）</h2>
 * <ol>
 *   <li>有 {@code automationId} ⇒ 直接 {@code ValuePattern.setValue}；</li>
 *   <li>否则按 {@code name}/{@code role} 在 UIA 树里找元素 ⇒ 用它的 {@code automationId} 走
 *       {@code ValuePattern.setValue}；</li>
 *   <li>都不成 ⇒ 回落「点击元素中心 + 窗口定向输入文本」；</li>
 *   <li>找不到元素 ⇒ 报错。</li>
 * </ol>
 */
public final class ComputerUseTypeIntoElementTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseTypeIntoElementTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = "mcp__computer-use__type_into_element";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:507-510}。 */
    private static final String DESCRIPTION =
            "Type text into a named GUI element using Windows UI Automation ValuePattern. "
                    + "Finds the element by name/role/automationId, then sets its value directly — "
                    + "no need to click first or use pixel coordinates. "
                    + "Works on Edit, ComboBox, and other value-holding controls.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:511-532}。 */
    private static final String INPUT_SCHEMA = """
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
              "required": ["text"]
            }
            """;

    private final ComputerUseUiaService uiaService;
    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;

    /**
     * @param uiaService   UIA 服务
     * @param windowOps    窗口操作原语（回落路径的点击 / 输入）
     * @param bindingStore 绑定窗口存储
     */
    public ComputerUseTypeIntoElementTool(ComputerUseUiaService uiaService,
                                          ComputerUseWindowOps windowOps,
                                          ComputerUseWindowBindingStore bindingStore) {
        this.uiaService = uiaService;
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
                    "[computer-use] type_into_element inputSchema 解析失败: " + e.getMessage(), e);
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
                new PermissionDecisionReason.Other("computer-use type_into_element is gated by the "
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
        JsonNode textNode = input.path("text");
        if (!textNode.isTextual()) {
            // CCB toolCalls.ts:3357-3358 —— requireString 的错误文案
            return ToolResult.error(call.id(), "\"text\" must be a string.");
        }
        String text = textNode.asText();
        String name = input.path("name").isTextual() ? input.path("name").asText() : null;
        String role = input.path("role").isTextual() ? input.path("role").asText() : null;
        String automationId = input.path("automationId").isTextual() ? input.path("automationId").asText() : null;

        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;
        if (binding == null) {
            // CCB executorCrossPlatform.ts:1097-1100
            return ToolResult.error(call.id(),
                    "type_into_element is only available on Windows with a bound window.");
        }
        long hwnd = binding.hwnd();

        try {
            // 路径 1：直接按 automationId 设值（CCB executorCrossPlatform.ts:1112-1114）
            if (automationId != null && !automationId.isEmpty()
                    && uiaService.setElementValue(hwnd, automationId, text)) {
                return ToolResult.success(call.id(),
                        "Typed " + text.length() + " chars into: " + describe(name, role, automationId));
            }

            // 路径 2：按 name/role 找元素，再走 ValuePattern（CCB executorCrossPlatform.ts:1115-1121）
            ComputerUseAccessibilitySnapshot snapshot =
                    uiaService.captureSnapshot(hwnd, Win32UiaService.DEFAULT_MAX_DEPTH);
            ComputerUseAccessibilityNode node =
                    ComputerUseAccessibilitySnapshot.findNode(snapshot.nodes(), name, role, automationId);
            if (node == null) {
                // CCB toolCalls.ts:3367-3371
                return ToolResult.error(call.id(),
                        "Could not type into element: " + describe(name, role, automationId)
                                + ". The element was not found or doesn't support text input.");
            }
            if (!node.automationId().isEmpty()
                    && uiaService.setElementValue(hwnd, node.automationId(), text)) {
                return ToolResult.success(call.id(),
                        "Typed " + text.length() + " chars into: " + describe(name, role, automationId));
            }

            // 路径 3：回落「点击中心 + 窗口定向输入」（CCB executorCrossPlatform.ts:1123-1139）
            int[] center = node.center();
            int[] client = windowOps.screenToClient(hwnd, center[0], center[1]);
            windowOps.sendClick(hwnd, client[0], client[1], false);
            windowOps.sendText(hwnd, text);

            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 回落点击+输入: element=\"{}\" client=({},{}) len={}",
                        TOOL_NAME, node.name(), client[0], client[1], text.length());
            }
            return ToolResult.success(call.id(),
                    "Typed " + text.length() + " chars into: " + describe(name, role, automationId));
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: name={} role={} automationId={} err={}",
                    TOOL_NAME, name, role, automationId, e.getMessage(), e);
            return ToolResult.error(call.id(), "元素输入失败: " + e.getMessage());
        }
    }

    /** 元素描述（CCB {@code toolCalls.ts:3369} / {@code :3374} 的同款拼接）。 */
    private static String describe(String name, String role, String automationId) {
        StringBuilder sb = new StringBuilder();
        if (name != null && !name.isEmpty()) {
            sb.append('"').append(name).append('"');
        }
        if (role != null && !role.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(role);
        }
        if (automationId != null && !automationId.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("id=").append(automationId);
        }
        return sb.toString();
    }
}
