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
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "click_element");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:478-482}。 */
    private static final String DESCRIPTION =
            "Click a GUI element by its accessible name, role, or automationId — no pixel coordinates needed. "
                    + "Uses Windows UI Automation to find the element and InvokePattern to click it. "
                    + "Prefer this over left_click when the element name is visible in the accessibility snapshot. "
                    + "Falls back to BoundingRect center-click if InvokePattern is not supported.";

    /** 输入 schema（CCB 三项 + 本仓新增 dsh 风格观察绑定参数）。 */
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
                  "description": "Accessible name of the element (e.g. \\"Save\\", \\"File\\", \\"Search...\\"). Case-insensitive partial match."
                },
                "role": {
                  "type": "string",
                  "description": "Control type (e.g. \\"Button\\", \\"MenuItem\\", \\"Edit\\", \\"Link\\"). Optional — narrows the search."
                },
                "automationId": {
                  "type": "string",
                  "description": "Exact automationId from the accessibility snapshot. Most precise selector on Windows."
                },
                "sensitive": {
                  "type": "boolean",
                  "description": "Set true for an action classified as high impact or sensitive. Requires a confirmationToken from confirm."
                },
                "confirmationToken": {
                  "type": "string",
                  "description": "One-use token from confirm for this exact action."
                }
              },
              "required": []
            }
            """;

    private final ComputerUseUiaService uiaService;
    private final ComputerUseWindowOps windowOps;
    private final ComputerUseWindowBindingStore bindingStore;
    private final ComputerUseUiSnapshotStore snapshotStore;
    private final ComputerUseConfirmationStore confirmationStore;
    /** TCC 权限检查（macOS 侧用于报告权限状态；可为 null）。 */
    private final MacOsTccChecker tcc;
    /** 当前会话 id（execute 时写入，供动作后置观察登记用）。 */
    private String currentSessionId;

    /**
     * @param uiaService    UIA/AX 服务
     * @param windowOps     窗口操作原语（仅 Windows 的回落路径需要；macOS 传 null）
     * @param bindingStore  绑定窗口存储（仅 Windows 需要；macOS 传 null）
     * @param snapshotStore 观察存储（observationId + index 解析）
     * @param confirmationStore 敏感动作令牌存储（可空 ⇒ 敏感动作一律拒绝）
     */
    public ComputerUseClickElementTool(ComputerUseUiaService uiaService,
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
    public ComputerUseClickElementTool(ComputerUseUiaService uiaService,
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
     * 兼容构造（无观察存储）：{@code observationId / index / targetHandle} 路径不可用，
     * 只能走 {@code name / role / automationId} 现查 —— 即 Windows 的既有用法。
     *
     * @param uiaService   UIA 服务
     * @param windowOps    窗口操作原语
     * @param bindingStore 绑定窗口存储
     */
    public ComputerUseClickElementTool(ComputerUseUiaService uiaService,
                                       ComputerUseWindowOps windowOps,
                                       ComputerUseWindowBindingStore bindingStore) {
        this(uiaService, windowOps, bindingStore, null, null);
    }

    /**
     * 兼容构造（有观察存储、无令牌存储）：敏感动作一律被拒（保守）。
     *
     * @param uiaService    UIA 服务
     * @param windowOps     窗口操作原语
     * @param bindingStore  绑定窗口存储
     * @param snapshotStore 观察存储
     */
    public ComputerUseClickElementTool(ComputerUseUiaService uiaService,
                                       ComputerUseWindowOps windowOps,
                                       ComputerUseWindowBindingStore bindingStore,
                                       ComputerUseUiSnapshotStore snapshotStore) {
        this(uiaService, windowOps, bindingStore, snapshotStore, null);
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
        String observationId = input.path("observationId").isTextual()
                ? input.path("observationId").asText() : null;
        int index = input.path("index").asInt(-1);
        String handle = input.path("targetHandle").isTextual()
                ? input.path("targetHandle").asText() : null;
        boolean allowRebind = input.path("allowRebind").asBoolean(false);
        String name = input.path("name").isTextual() ? input.path("name").asText() : null;
        String role = input.path("role").isTextual() ? input.path("role").asText() : null;
        String automationId = input.path("automationId").isTextual() ? input.path("automationId").asText() : null;
        boolean sensitive = input.path("sensitive").asBoolean(false);
        String confirmationToken = input.path("confirmationToken").isTextual()
                ? input.path("confirmationToken").asText() : null;
        if (index < 0
                && (handle == null || handle.isEmpty())
                && (name == null || name.isEmpty())
                && (role == null || role.isEmpty())
                && (automationId == null || automationId.isEmpty())) {
            // CCB toolCalls.ts:3329-3332（本仓额外允许 index / targetHandle）
            return ToolResult.error(call.id(),
                    "At least one of index, targetHandle, name, role, or automationId is required.");
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;
        this.currentSessionId = sessionId;

        // 敏感动作的一次性确认（dsh confirmations.ts:89-113）——在任何动作之前
        ComputerUseSensitiveGate.Gate gate = ComputerUseSensitiveGate.check(
                confirmationStore, snapshotStore, sessionId, sensitive, confirmationToken,
                observationId, index, handle, "click", null);
        if (!gate.allowed()) {
            return ToolResult.error(call.id(), gate.rejection());
        }

        boolean needsBinding = uiaService.requiresBoundWindow();
        ComputerUseWindowBinding binding = needsBinding && bindingStore != null
                ? bindingStore.get(sessionId) : null;
        if (needsBinding && binding == null) {
            // CCB executorCrossPlatform.ts:1052-1055
            return ToolResult.error(call.id(),
                    "click_element is only available on Windows with a bound window.");
        }
        long hwnd = needsBinding ? binding.hwnd() : 0L;

        try {
            // 路径 1：observationId + index/targetHandle（dsh 语义 · macOS 主路径）
            String targetId;
            String expectedStateHash = null;
            ComputerUseUiSnapshotStore.ObservedElement observed = null;
            if (observationId != null || index >= 0 || (handle != null && !handle.isEmpty())) {
                ComputerUseElementResolver.Resolution resolution =
                        ComputerUseElementResolver.resolveObserved(
                                snapshotStore, sessionId, observationId, index, handle, allowRebind, "click");
                if (resolution.failed()) {
                    return ToolResult.error(call.id(), resolution.errorCode() + ": " + resolution.error());
                }
                observed = resolution.element();
                targetId = resolution.locator();
                // dsh expectedStateHash：动作前校验树没变（macOS 的 locator 是树序派生的）
                expectedStateHash = resolution.stateHash();
                // 动作前的观察：动作后的后置观察要用**同一个应用**定位
                ComputerUseUiSnapshotStore.Observation beforeObservation =
                        snapshotStore != null ? snapshotStore.require(sessionId, observationId) : null;

                // dsh service.ts:509-513 —— 允许重绑且提供了 targetHandle 时：
                //   重新 observe 一次，用新树做三级解析（exact → native-id → semantic）
                if (allowRebind && handle != null && !handle.isEmpty() && snapshotStore != null) {
                    ComputerUseUiSnapshotStore.Observation original = snapshotStore.require(sessionId, observationId);
                    ComputerUseAccessibilitySnapshot freshSnapshot =
                            uiaService.captureSnapshot(hwnd, MacOsUiaService.DEFAULT_MAX_DEPTH);
                    List<ComputerUseUiSnapshotStore.ObservedElement> freshElements =
                            ComputerUseObserveTool.toObservedElements(freshSnapshot.nodes());
                    long now = System.currentTimeMillis();
                    ComputerUseUiSnapshotStore.Observation fresh = new ComputerUseUiSnapshotStore.Observation(
                            "fresh", sessionId, freshSnapshot.appName(), freshSnapshot.bundleId(),
                            freshSnapshot.pid(), freshSnapshot.windowTitle(), now, Long.MAX_VALUE,
                            freshSnapshot.stateHash(), "full", freshSnapshot.text(),
                            freshSnapshot.truncated(), freshElements, 1);
                    boolean sameApp = original != null
                            && original.bundleId().equals(fresh.bundleId())
                            && original.pid() == fresh.pid();
                    boolean sameWindow = original != null
                            && original.windowTitle().equals(fresh.windowTitle());
                    ComputerUseTargetResolver.Resolution target = ComputerUseElementResolver.rebind(
                            original, fresh, observed, true, sameApp, sameWindow);
                    if (target.failed()) {
                        // 敏感动作遇到重绑 ⇒ 一次性确认失效（dsh service.ts:516-521）
                        if (sensitive && confirmationToken != null) {
                            confirmationStore.invalidate(sessionId, confirmationToken);
                        }
                        return ToolResult.error(call.id(),
                                target.errorCode() + ": " + target.error());
                    }
                    if (target.targetChanged() && sensitive && confirmationToken != null) {
                        confirmationStore.invalidate(sessionId, confirmationToken);
                        return ToolResult.error(call.id(),
                                "COMPUTER_TARGET_REBIND_REQUIRES_CONFIRMATION: the sensitive target rebound to a "
                                        + "fresh element; observe the current UI and request a new one-use "
                                        + "confirmation before acting");
                    }
                    observed = target.element();
                    targetId = observed.locator();
                    expectedStateHash = fresh.stateHash();
                }

                return clickAndReport(call, hwnd, needsBinding, targetId,
                        labelOfObserved(observed, index, name, role, automationId), observed,
                        expectedStateHash, beforeObservation,
                        resolution.targetResolution(),
                        beforeObservation == null ? 0L : beforeObservation.pid());
            } else if (automationId != null && !automationId.isEmpty()) {
                targetId = automationId;
            } else {
                // 路径 2：name/role 现查（Windows 主用；macOS 也可用）
                ComputerUseAccessibilitySnapshot snapshot =
                        uiaService.captureSnapshot(hwnd, MacOsUiaService.DEFAULT_MAX_DEPTH);
                ComputerUseAccessibilityNode found =
                        ComputerUseAccessibilitySnapshot.findNode(snapshot.nodes(), name, role, automationId);
                if (found == null) {
                    // CCB toolCalls.ts:3336-3340
                    return ToolResult.error(call.id(),
                            "Element not found: " + describe(name, role, automationId)
                                    + ". Call observe (or take a screenshot) to see current GUI elements.");
                }
                targetId = found.automationId().isEmpty() ? found.locator() : found.automationId();
                if (targetId.isEmpty()) {
                    return ToolResult.error(call.id(),
                            "Element has no usable locator: " + describe(name, role, automationId));
                }
                // ⚠️ 必须把找到的节点坐标带进回落路径（Windows 的 BoundingRect 中心点击依赖它）
                return clickAndReport(call, hwnd, needsBinding, targetId,
                        labelOfObserved(null, index, name, role, automationId), fromNode(found), null);
            }

            return clickAndReport(call, hwnd, needsBinding, targetId,
                    labelOfObserved(observed, index, name, role, automationId), observed, null);
        } catch (ComputerUseStaleObservationException e) {
            // dsh COMPUTER_STALE_OBSERVATION：树变了 ⇒ 让模型重新 observe，而不是把它
            // 当成「元素不可用」去重复尝试同一个 locator
            log.warn("[computer-use] {} 拒绝执行：观察已陈旧。{}", TOOL_NAME, e.getMessage());
            return ToolResult.error(call.id(),
                    ComputerUseUiSnapshotStore.ERR_STALE_OBSERVATION + ": " + e.getMessage());
        } catch (Exception e) {
            log.error("[computer-use] {} 执行失败: index={} name={} role={} automationId={} err={}",
                    TOOL_NAME, index, name, role, automationId, e.getMessage(), e);
            return ToolResult.error(call.id(), "元素点击失败: " + e.getMessage());
        }
    }

    /**
     * 执行点击（AXPress 优先；Windows 才回落坐标）+ 动作后<b>新观察</b>
     * （对齐 dsh 动作结果里必带 {@code observation}：模型不用再单独 observe）。
     */
    private AgentToolResult<?> clickAndReport(ToolUseBlock call,
                                              long hwnd,
                                              boolean needsBinding,
                                              String targetId,
                                              String label,
                                              ComputerUseUiSnapshotStore.ObservedElement observed,
                                              String expectedStateHash) {
        return clickAndReport(call, hwnd, needsBinding, targetId, label, observed, expectedStateHash, null);
    }

    /**
     * 执行点击 + 返回结构化动作结果（dsh {@code actionResultSchema}）。
     *
     * @param before    动作前的观察（提供应用身份与 stateHash；可空）
     * @param resolution 目标解析结果（可空）
     */
    private AgentToolResult<?> clickAndReport(ToolUseBlock call,
                                              long hwnd,
                                              boolean needsBinding,
                                              String targetId,
                                              String label,
                                              ComputerUseUiSnapshotStore.ObservedElement observed,
                                              String expectedStateHash,
                                              ComputerUseUiSnapshotStore.Observation before) {
        return clickAndReport(call, hwnd, needsBinding, targetId, label, observed, expectedStateHash,
                before, null);
    }

    /**
     * 执行点击 + 返回结构化动作结果（dsh {@code actionResultSchema}）。
     *
     * @param before     动作前的观察（提供应用身份与 stateHash；可空）
     * @param resolution 目标解析结果（可空）
     */
    private AgentToolResult<?> clickAndReport(ToolUseBlock call,
                                              long hwnd,
                                              boolean needsBinding,
                                              String targetId,
                                              String label,
                                              ComputerUseUiSnapshotStore.ObservedElement observed,
                                              String expectedStateHash,
                                              ComputerUseUiSnapshotStore.Observation before,
                                              ComputerUseTargetResolver.Resolution resolution) {
        // ⛔ 转发给带 targetPid 的那个重载（pid 从「动作前的观察」取）
        //   ⚠️ 不能转发给自己（同名同参 ⇒ 无限递归 ⇒ StackOverflowError）
        return clickAndReport(call, hwnd, needsBinding, targetId, label, observed, expectedStateHash,
                before, resolution, before == null ? 0L : before.pid());
    }

    /**
     * 执行点击 + 返回结构化动作结果（dsh {@code actionResultSchema}）。
     *
     * @param before     动作前的观察（提供应用身份与 stateHash；可空）
     * @param resolution 目标解析结果（可空）
     */
    private AgentToolResult<?> clickAndReport(ToolUseBlock call,
                                              long hwnd,
                                              boolean needsBinding,
                                              String targetId,
                                              String label,
                                              ComputerUseUiSnapshotStore.ObservedElement observed,
                                              String expectedStateHash,
                                              ComputerUseUiSnapshotStore.Observation before,
                                              ComputerUseTargetResolver.Resolution resolution,
                                              long targetPid) {
        // ⭐ macOS 侧返回结构化动作结果（dsh actionResultSchema）：
        //   动作后的新观察 + effect（生效没有）+ resolution（点的是不是那个元素）
        if (needsBinding) {
            // Windows 侧维持既有文案（CCB 路线，不改 Windows 行为）
            boolean pressedWin = expectedStateHash == null
                    ? uiaService.invokeElement(hwnd, targetId)
                    : uiaService.invokeElement(hwnd, targetId, expectedStateHash);
            if (!pressedWin) {
                if (observed == null || !observed.hasBounds()) {
                    return ToolResult.error(call.id(),
                            "Element could not be pressed and has no usable bounds for a fallback click: " + label);
                }
                int cx = observed.x() + Math.round(observed.width() / 2f);
                int cy = observed.y() + Math.round(observed.height() / 2f);
                int[] client = windowOps.screenToClient(hwnd, cx, cy);
                windowOps.sendClick(hwnd, client[0], client[1], false);
            }
            return ToolResult.success(call.id(), "Clicked element: " + label);
        }
        // dsh service.ts:441-446 —— 同一进程的动作排队，串到上一个动作的后置观察完成
        //   ⇒ observe → click → observe 交错时不会读到半更新的界面树
        String appKey = before == null ? "unknown" : before.bundleId() + ":" + before.pid();
        return ComputerUseActionExecutor.enqueue(appKey, () -> {
            boolean pressed = expectedStateHash == null
                    ? uiaService.invokeElement(hwnd, targetId)
                    : uiaService.invokeElement(hwnd, targetId, expectedStateHash, targetPid);
            if (!pressed && windowOps == null) {
                // macOS 无坐标回落 ⇒ AXPress 失败就是失败，显式报错不盲点
                return ToolResult.error(call.id(),
                        "Element cannot be pressed via accessibility (no AXPress). macOS has no "
                                + "coordinate fallback for observer-only elements — use screenshot + "
                                + "left_click if the element is drawn without accessibility support.");
            }
            ComputerUseActionResult result = ComputerUseActionExecutor.buildResult(
                    uiaService, snapshotStore, sessionIdOf(call),
                    ComputerUseActionResult.ACTION_CLICK,
                    ComputerUseActionResult.CHANNEL_ACCESSIBILITY,
                    resolution, before, MacOsUiaService.DEFAULT_MAX_DEPTH, tcc);
            return ToolResult.success(call.id(),
                    ComputerUseActionResult.render(result, permissionsNode(), null));
        });
    }

    /** 会话 id（execute 时缓存，避免层层传参）。 */
    private String sessionIdOf(ToolUseBlock call) {
        return currentSessionId;
    }

    /** 权限状态节点（dsh {@code permissions}）。 */
    private com.fasterxml.jackson.databind.node.ObjectNode permissionsNode() {
        com.fasterxml.jackson.databind.node.ObjectNode node = JSON.createObjectNode();
        if (tcc != null) {
            MacOsTccChecker.Status status = tcc.status();
            node.put("accessibility", status.accessibilityGranted() ? "granted" : "denied");
            node.put("screenRecording", status.screenRecordingGranted() ? "granted" : "denied");
        } else {
            node.put("accessibility", "unavailable");
            node.put("screenRecording", "unavailable");
        }
        return node;
    }

    /** 节点 → 观察元素（只为把坐标带进回落路径；Windows 的 name/role 现查路径用）。 */
    private static ComputerUseUiSnapshotStore.ObservedElement fromNode(ComputerUseAccessibilityNode node) {
        if (node == null) {
            return null;
        }
        return new ComputerUseUiSnapshotStore.ObservedElement(
                node.index(), "", node.locator(), node.role(), node.name(), node.value(),
                node.enabled(), node.actions(), node.x(), node.y(), node.width(), node.height());
    }

    /** 目标描述（优先用观察元素里的真实角色/名字，回落到请求参数）。 */
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

    /** 目标描述（优先用观察快照里的真实角色/名字，回落到请求参数）。 */
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
