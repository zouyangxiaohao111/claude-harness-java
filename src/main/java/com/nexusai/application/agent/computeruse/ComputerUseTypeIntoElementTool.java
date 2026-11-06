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
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "type_into_element");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:507-510}。 */
    private static final String DESCRIPTION =
            "Type text into a named GUI element using Windows UI Automation ValuePattern. "
                    + "Finds the element by name/role/automationId, then sets its value directly — "
                    + "no need to click first or use pixel coordinates. "
                    + "Works on Edit, ComboBox, and other value-holding controls.";

    /** 输入 schema（CCB 四项 + 本仓新增 dsh 风格观察绑定参数）。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "observationId": {
                  "type": "string",
                  "description": "observationId from the most recent observe. Required on macOS; element indexes belong only to that observation."
                },
                "index": {
                  "type": "integer",
                  "description": "Element index from that observe output (the [#N] prefix)."
                },
                "targetHandle": {
                  "type": "string",
                  "description": "Opaque handle from observe. Required when allowRebind is true."
                },
                "allowRebind": {
                  "type": "boolean",
                  "description": "Allow deterministic rebinding by locator inside the same process/window when the tree reordered harmlessly."
                },
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
                },
                "sensitive": {
                  "type": "boolean",
                  "description": "Set true for an action classified as high impact or sensitive (e.g. typing a password). Requires a confirmationToken from confirm."
                },
                "confirmationToken": {
                  "type": "string",
                  "description": "One-use token from confirm for this exact action."
                }
              },
              "required": ["text"]
            }
            """;

    private final ComputerUseUiaService uiaService;
    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;
    private final ComputerUseUiSnapshotStore snapshotStore;
    private final ComputerUseConfirmationStore confirmationStore;
    /** TCC 权限检查（macOS 侧用于报告权限状态；可为 null）。 */
    private final MacOsTccChecker tcc;

    /**
     * @param uiaService    UIA/AX 服务
     * @param windowOps     窗口操作原语（仅 Windows 的回落路径需要；macOS 传 null）
     * @param bindingStore  绑定窗口存储（仅 Windows 需要；macOS 传 null）
     * @param snapshotStore 观察存储（observationId + index 解析）
     * @param confirmationStore 敏感动作令牌存储（可空 ⇒ 敏感动作一律拒绝）
     */
    public ComputerUseTypeIntoElementTool(ComputerUseUiaService uiaService,
                                          ComputerUseWindowOps windowOps,
                                          ComputerUseWindowBindingStore bindingStore,
                                          ComputerUseUiSnapshotStore snapshotStore,
                                          ComputerUseConfirmationStore confirmationStore) {
        this(uiaService, windowOps, bindingStore, snapshotStore, confirmationStore, null);
    }

    /**
     * @param uiaService    UIA/AX 服务
     * @param windowOps     窗口操作原语（仅 Windows 的回落路径需要；macOS 传 null）
     * @param bindingStore  绑定窗口存储（仅 Windows 需要；macOS 传 null）
     * @param snapshotStore 观察存储（observationId + index 解析）
     * @param confirmationStore 敏感动作令牌存储（可空 ⇒ 敏感动作一律拒绝）
     * @param tcc           TCC 权限检查（可空 ⇒ 权限报 unavailable）
     */
    public ComputerUseTypeIntoElementTool(ComputerUseUiaService uiaService,
                                          ComputerUseWindowOps windowOps,
                                          ComputerUseWindowBindingStore bindingStore,
                                          ComputerUseUiSnapshotStore snapshotStore,
                                          ComputerUseConfirmationStore confirmationStore,
                                          MacOsTccChecker tcc) {
        this.uiaService = uiaService;
        this.windowOps = windowOps;
        this.bindingStore = bindingStore;
        this.snapshotStore = snapshotStore;
        this.confirmationStore = confirmationStore;
        this.tcc = tcc;
    }

    /**
     * 兼容构造（有观察存储、无令牌存储）：敏感动作一律被拒（保守）。
     *
     * @param uiaService    UIA 服务
     * @param windowOps     窗口操作原语
     * @param bindingStore  绑定窗口存储
     * @param snapshotStore 观察存储
     */
    public ComputerUseTypeIntoElementTool(ComputerUseUiaService uiaService,
                                          ComputerUseWindowOps windowOps,
                                          ComputerUseWindowBindingStore bindingStore,
                                          ComputerUseUiSnapshotStore snapshotStore) {
        this(uiaService, windowOps, bindingStore, snapshotStore, null);
    }

    /**
     * 兼容构造（无观察存储）：只能走 {@code name / role / automationId} 现查。
     *
     * @param uiaService   UIA 服务
     * @param windowOps    窗口操作原语
     * @param bindingStore 绑定窗口存储
     */
    public ComputerUseTypeIntoElementTool(ComputerUseUiaService uiaService,
                                          ComputerUseWindowOps windowOps,
                                          ComputerUseWindowBindingStore bindingStore) {
        this(uiaService, windowOps, bindingStore, null);
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
        String observationId = input.path("observationId").isTextual()
                ? input.path("observationId").asText() : null;
        int index = input.path("index").asInt(-1);
        String handle = input.path("targetHandle").isTextual()
                ? input.path("targetHandle").asText() : null;
        boolean allowRebind = input.path("allowRebind").asBoolean(false);
        String name = input.path("name").isTextual() ? input.path("name").asText() : null;
        String role = input.path("role").isTextual() ? input.path("role").asText() : null;
        String automationId = input.path("automationId").isTextual() ? input.path("automationId").asText() : null;

        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 敏感动作的一次性确认（dsh confirmations.ts:89-113）——在任何动作之前
        boolean sensitive = input.path("sensitive").asBoolean(false);
        String confirmationToken = input.path("confirmationToken").isTextual()
                ? input.path("confirmationToken").asText() : null;
        ComputerUseSensitiveGate.Gate gate = ComputerUseSensitiveGate.check(
                confirmationStore, snapshotStore, sessionId, sensitive, confirmationToken,
                observationId, index, handle, "type", text);
        if (!gate.allowed()) {
            return ToolResult.error(call.id(), gate.rejection());
        }

        boolean needsBinding = uiaService.requiresBoundWindow();
        ComputerUseWindowBinding binding = needsBinding && bindingStore != null
                ? bindingStore.get(sessionId) : null;
        if (needsBinding && binding == null) {
            // CCB executorCrossPlatform.ts:1097-1100
            return ToolResult.error(call.id(),
                    "type_into_element is only available on Windows with a bound window.");
        }
        long hwnd = needsBinding ? binding.hwnd() : 0L;

        try {
            // 路径 0：observationId + index/targetHandle（dsh 语义 · macOS 主路径）
            String targetId;
            String expectedStateHash = null;
            ComputerUseUiSnapshotStore.ObservedElement observed = null;
            ComputerUseTargetResolver.Resolution targetResolution = null;
            ComputerUseUiSnapshotStore.Observation beforeObservation = null;
            if (observationId != null || index >= 0 || (handle != null && !handle.isEmpty())) {
                ComputerUseElementResolver.Resolution resolution =
                        ComputerUseElementResolver.resolveObserved(
                                snapshotStore, sessionId, observationId, index, handle, allowRebind,
                                "type_into_element");
                if (resolution.failed()) {
                    return ToolResult.error(call.id(), resolution.errorCode() + ": " + resolution.error());
                }
                observed = resolution.element();
                targetId = resolution.locator();
                targetResolution = resolution.targetResolution();
                // dsh expectedStateHash：动作前校验树没变（macOS 的 locator 是树序派生的，
                // 界面一变同一路径就指向别的元素 ⇒ 写值会写错字段）
                expectedStateHash = resolution.stateHash();
                beforeObservation = snapshotStore != null
                        ? snapshotStore.require(sessionId, observationId) : null;
            } else if (automationId != null && !automationId.isEmpty()) {
                targetId = automationId;
            } else {
                targetId = null;
            }

            String label = labelOfObserved(observed, index, name, role, automationId);

            // 路径 1：按 locator 直接设值（CCB executorCrossPlatform.ts:1112-1114）
            long targetPid = beforeObservation == null ? 0L : beforeObservation.pid();
            if (targetId != null && uiaService.setElementValue(
                    hwnd, targetId, text, expectedStateHash, targetPid)) {
                return typeAndReport(call, sessionId, label, text.length(), beforeObservation,
                        targetResolution, targetPid);
            }

            if (targetId == null) {
                // 路径 2：按 name/role 找元素，再设值（CCB executorCrossPlatform.ts:1115-1121）
                ComputerUseAccessibilitySnapshot snapshot =
                        uiaService.captureSnapshot(hwnd, MacOsUiaService.DEFAULT_MAX_DEPTH);
                ComputerUseAccessibilityNode found =
                        ComputerUseAccessibilitySnapshot.findNode(snapshot.nodes(), name, role, automationId);
                if (found == null) {
                    // CCB toolCalls.ts:3367-3371
                    return ToolResult.error(call.id(),
                            "Could not type into element: " + describe(name, role, automationId)
                                    + ". The element was not found or doesn't support text input.");
                }
                String foundId = found.automationId().isEmpty() ? found.locator() : found.automationId();
                if (!foundId.isEmpty() && uiaService.setElementValue(hwnd, foundId, text, targetPid)) {
                    return typeAndReport(call, sessionId, label, text.length(), beforeObservation,
                            targetResolution, targetPid);
                }
            }

            // 路径 3：回落「点击中心 + 输入」（CCB executorCrossPlatform.ts:1123-1139）
            //         ⛔ macOS 拿不到坐标 ⇒ 走 windowOps 会 NPE，必须显式失败
            if (windowOps == null) {
                return ToolResult.error(call.id(),
                        "Element does not accept direct value assignment (no writable AXValue / "
                                + "AXSelectedText). Focus it with click_element, then use type.");
            }
            if (observed == null || !observed.hasBounds()) {
                return ToolResult.error(call.id(),
                        "Element could not be set and has no usable bounds for a fallback click: " + label);
            }
            int centerX = observed.x() + Math.round(observed.width() / 2f);
            int centerY = observed.y() + Math.round(observed.height() / 2f);
            int[] client = windowOps.screenToClient(hwnd, centerX, centerY);
            windowOps.sendClick(hwnd, client[0], client[1], false);
            windowOps.sendText(hwnd, text);

            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 回落点击+输入: element=\"{}\" client=({},{}) len={}",
                        TOOL_NAME, label, client[0], client[1], text.length());
            }
            return typeAndReport(call, sessionId, label, text.length(), beforeObservation,
                    targetResolution, targetPid);
        } catch (ComputerUseStaleObservationException e) {
            // dsh COMPUTER_STALE_OBSERVATION：树变了 ⇒ 让模型重新 observe，而不是把它
            // 当成「元素不可用」去重复尝试同一个 locator
            log.warn("[computer-use] {} 拒绝执行：观察已陈旧。{}", TOOL_NAME, e.getMessage());
            return ToolResult.error(call.id(),
                    ComputerUseUiSnapshotStore.ERR_STALE_OBSERVATION + ": " + e.getMessage());
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: index={} name={} role={} automationId={} err={}",
                    TOOL_NAME, index, name, role, automationId, e.getMessage(), e);
            return ToolResult.error(call.id(), "元素输入失败: " + e.getMessage());
        }
    }

    /**
     * 输入成功后返回结构化动作结果（dsh {@code actionResultSchema}）。
     *
     * <p>macOS 侧带后置观察 + effect；Windows 侧维持既有文案（CCB 路线，不改 Windows 行为）。
     */
    private AgentToolResult<?> typeAndReport(ToolUseBlock call,
                                             String sessionId,
                                             String label,
                                             int charCount,
                                             ComputerUseUiSnapshotStore.Observation before,
                                             ComputerUseTargetResolver.Resolution resolution) {
        return typeAndReport(call, sessionId, label, charCount, before, resolution, 0L);
    }

    /**
     * 输入成功后返回结构化动作结果（dsh {@code actionResultSchema}）。
     *
     * @param targetPid 目标应用 pid（观察后台应用时必须带上 ⇒ 写值落在同一棵树上）
     */
    private AgentToolResult<?> typeAndReport(ToolUseBlock call,
                                             String sessionId,
                                             String label,
                                             int charCount,
                                             ComputerUseUiSnapshotStore.Observation before,
                                             ComputerUseTargetResolver.Resolution resolution,
                                             long targetPid) {
        if (uiaService.requiresBoundWindow()) {
            return ToolResult.success(call.id(), "Typed " + charCount + " chars into: " + label);
        }
        ComputerUseActionResult result = ComputerUseActionExecutor.buildResult(
                uiaService, snapshotStore, sessionId,
                ComputerUseActionResult.ACTION_SET_VALUE,
                ComputerUseActionResult.CHANNEL_ACCESSIBILITY,
                resolution, before, MacOsUiaService.DEFAULT_MAX_DEPTH, tcc);
        com.fasterxml.jackson.databind.node.ObjectNode permissions = JSON.createObjectNode();
        if (tcc != null) {
            MacOsTccChecker.Status status = tcc.status();
            permissions.put("accessibility", status.accessibilityGranted() ? "granted" : "denied");
            permissions.put("screenRecording", status.screenRecordingGranted() ? "granted" : "denied");
        } else {
            permissions.put("accessibility", "unavailable");
            permissions.put("screenRecording", "unavailable");
        }
        com.fasterxml.jackson.databind.node.ObjectNode extra = JSON.createObjectNode();
        extra.put("typedChars", charCount);
        extra.put("target", label);
        return ToolResult.success(call.id(), ComputerUseActionResult.render(result, permissions, extra));
    }

    /** 目标描述（优先用观察元素里的真实角色/名字）。 */
    private static String labelOfObserved(ComputerUseUiSnapshotStore.ObservedElement observed,
                                          int index,
                                          String name,
                                          String role,
                                          String automationId) {
        if (observed != null) {
            StringBuilder sb = new StringBuilder();
            if (index >= 0) {
                sb.append('#').append(index).append(' ');
            }
            sb.append(observed.role());
            if (!observed.name().isEmpty()) {
                sb.append(" \"").append(observed.name()).append('"');
            }
            return sb.toString().trim();
        }
        return describe(name, role, automationId);
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
