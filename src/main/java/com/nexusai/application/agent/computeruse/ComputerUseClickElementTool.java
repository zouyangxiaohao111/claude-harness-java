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
 * {@code mcp__computer-use__click_element} · UIA 元素点击工具（Windows 专属）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:477-504}）</h2>
 * <pre>
 * name: 'click_element',
 * description: 'Click a GUI element by its accessible name, role, or automationId — no pixel coordinates needed. ' +
 *   'Uses Windows UI Automation to find the element and InvokePattern to click it. ' +
 *   'Prefer this over left_click when the element name is visible in the accessibility snapshot. ' +
 *   'Falls back to BoundingRect center-click if InvokePattern is not supported.',
 * inputSchema: {
 *   type: 'object' as const,
 *   properties: {
 *     name: { type: 'string', description: 'Accessible name of the element (e.g. "Save", "File", "Search..."). Case-insensitive partial match.' },
 *     role: { type: 'string', description: 'Control type (e.g. "Button", "MenuItem", "Edit", "Link"). Optional — narrows the search.' },
 *     automationId: { type: 'string', description: 'Exact automationId from the accessibility snapshot. Most precise selector.' },
 *   },
 *   required: [],
 * }
 * </pre>
 *
 * <h2>行为（逐字对齐 CCB {@code executorCrossPlatform.ts:1045-1089}）</h2>
 * <ol>
 *   <li>抓绑定窗口的 UIA 快照（深度 4 + 交互型白名单）；</li>
 *   <li>{@code findNodeInSnapshot}（name 部分匹配 / role 完全匹配 / automationId 完全匹配）；</li>
 *   <li>找到节点 ⇒ 优先 {@code InvokePattern}（{@code uiAutomation.clickElement}）；
 *       不支持 InvokePattern ⇒ 回落「BoundingRect 中心点 + 窗口定向点击」；</li>
 *   <li>没找到 ⇒ 报错（不猜、不盲点）。</li>
 * </ol>
 *
 * <p><b>⚠️ 屏幕坐标 → 客户区坐标</b>：UIA 的 {@code BoundingRectangle} 是<b>屏幕</b>坐标，
 * 而窗口定向点击要<b>客户区</b>坐标 ⇒ 必须换算
 * （{@link ComputerUseWindowOps#screenToClient}）。
 * CCB {@code executorCrossPlatform.ts:1077-1084} 用 {@code cx - nc.dx} 换算，
 * 而 {@code nc.dx} 是「客户区原点相对<b>窗口矩形</b>原点的偏移」
 * （{@code executorCrossPlatform.ts:105}）⇒ 该式只在<b>窗口矩形原点恰为屏幕原点</b>时成立
 * （窗口不在屏幕左上角时落点会偏）。本仓按<b>数学正确</b>的方式换算
 * （屏幕坐标 − 客户区原点的屏幕坐标），并在此如实标注该差异。
 */
public final class ComputerUseClickElementTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseClickElementTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21}。 */
    public static final String TOOL_NAME = "mcp__computer-use__click_element";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:478-482}。 */
    private static final String DESCRIPTION =
            "Click a GUI element by its accessible name, role, or automationId — no pixel coordinates needed. "
                    + "Uses Windows UI Automation to find the element and InvokePattern to click it. "
                    + "Prefer this over left_click when the element name is visible in the accessibility snapshot. "
                    + "Falls back to BoundingRect center-click if InvokePattern is not supported.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:483-503}。 */
    private static final String INPUT_SCHEMA = """
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
            """;

    private final ComputerUseUiaService uiaService;
    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;

    /**
     * @param uiaService   UIA 服务
     * @param windowOps    窗口操作原语（回落路径的窗口定向点击）
     * @param bindingStore 绑定窗口存储
     */
    public ComputerUseClickElementTool(ComputerUseUiaService uiaService,
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
                    "[computer-use] click_element inputSchema 解析失败: " + e.getMessage(), e);
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
                new PermissionDecisionReason.Other("computer-use click_element is gated by the "
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
        String name = input.path("name").isTextual() ? input.path("name").asText() : null;
        String role = input.path("role").isTextual() ? input.path("role").asText() : null;
        String automationId = input.path("automationId").isTextual() ? input.path("automationId").asText() : null;
        if ((name == null || name.isEmpty())
                && (role == null || role.isEmpty())
                && (automationId == null || automationId.isEmpty())) {
            // CCB toolCalls.ts:3329-3332
            return ToolResult.error(call.id(),
                    "At least one of name, role, or automationId is required.");
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;
        ComputerUseWindowBinding binding = bindingStore != null ? bindingStore.get(sessionId) : null;
        if (binding == null) {
            // CCB executorCrossPlatform.ts:1052-1055
            return ToolResult.error(call.id(),
                    "click_element is only available on Windows with a bound window.");
        }
        long hwnd = binding.hwnd();

        try {
            ComputerUseAccessibilitySnapshot snapshot =
                    uiaService.captureSnapshot(hwnd, Win32UiaService.DEFAULT_MAX_DEPTH);
            ComputerUseAccessibilityNode node =
                    ComputerUseAccessibilitySnapshot.findNode(snapshot.nodes(), name, role, automationId);
            if (node == null) {
                // CCB toolCalls.ts:3336-3340
                return ToolResult.error(call.id(),
                        "Element not found: " + describe(name, role, automationId)
                                + ". Take a screenshot to see current GUI elements.");
            }

            // 优先 InvokePattern（CCB executorCrossPlatform.ts:1071-1074）
            if (!node.automationId().isEmpty()
                    && uiaService.invokeElement(hwnd, node.automationId())) {
                return ToolResult.success(call.id(),
                        "Clicked element: " + describe(name, role, automationId));
            }

            // 回落：BoundingRect 中心点 + 窗口定向点击（CCB executorCrossPlatform.ts:1076-1084）
            int[] center = node.center();
            int[] client = windowOps.screenToClient(hwnd, center[0], center[1]);
            windowOps.sendClick(hwnd, client[0], client[1], false);

            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 回落中心点击: element=\"{}\" screen=({},{}) client=({},{})",
                        TOOL_NAME, node.name(), center[0], center[1], client[0], client[1]);
            }
            return ToolResult.success(call.id(),
                    "Clicked element: " + describe(name, role, automationId));
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: name={} role={} automationId={} err={}",
                    TOOL_NAME, name, role, automationId, e.getMessage(), e);
            return ToolResult.error(call.id(), "元素点击失败: " + e.getMessage());
        }
    }

    /** 元素描述（CCB {@code toolCalls.ts:3338} / {@code :3343} 的同款拼接）。 */
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
