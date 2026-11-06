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
 * {@code mcp__computer-use__perform_action} · 执行元素声明的<b>任意</b> Accessibility 动作
 * （对齐 dsh {@code dsh-computer-use} 的 {@code computer_perform_action}）。
 *
 * <h2>WHY 需要它（不只 AXPress）</h2>
 * <p>macOS 的 AX 元素除 {@code AXPress} 外还声明了语义动作：
 * {@code AXExpand} / {@code AXCollapse}（展开树、显示更多）、
 * {@code AXShowMenu}（弹出菜单）、{@code AXOpen}（打开文件）、
 * {@code AXConfirm} / {@code AXCancel}（对话框按钮）……
 * <p>这些动作<b>不是坐标点击</b>：它们直接作用于控件语义 ⇒ 不会点偏、不会误触相邻元素。
 * ⇒ 「用名字 + 语义动作」比「截图 → 估坐标 → 点」既准又便宜。
 *
 * <h2>语义（逐条对齐 dsh {@code main.swift:1221-1247}）</h2>
 * <ol>
 *   <li>必须有<b>已观察到的</b>目标（observationId + index / targetHandle）；</li>
 *   <li><b>动作名必须在该元素声明的 {@code actions} 里</b> ——
 *       dsh 原文 {@code guard available.contains(actionName)}，
 *       否则报 "the element did not advertise the requested Accessibility action"；</li>
 *   <li>执行失败 ⇒ 明确报错（不静默）。</li>
 * </ol>
 *
 * <h2>与 dsh 的差异（如实标注）</h2>
 * <ul>
 *   <li>dsh 有 {@code focusPolicy} 门控（某些动作会抬升窗口，需显式允许）；
 *       本仓沿用<b>既有 computer-use 档位门控</b>（{@link ComputerUseInputGate}），
 *       不再叠一层 focusPolicy（避免两套授权语义并存）。</li>
 *   <li>dsh 的动作在 Swift 侧执行；本仓走 JXA（同一 AX API，能力等价）。</li>
 * </ul>
 */
public final class ComputerUsePerformActionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUsePerformActionTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "perform_action");

    /** 工具描述。 */
    private static final String DESCRIPTION =
            "Perform one Accessibility action advertised by an observed element — for example AXPress, "
                    + "AXExpand, AXCollapse, AXShowMenu, AXOpen. The action must be one the element "
                    + "actually advertises (observe lists them). Prefer this over coordinate clicking: "
                    + "it targets the control's semantics rather than a pixel, so it cannot miss.";

    /** 输入 schema。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "observationId": {
                  "type": "string",
                  "description": "observationId from the most recent observe."
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
                "action": {
                  "type": "string",
                  "description": "Accessibility action name to perform, exactly as advertised by the element (e.g. AXPress, AXExpand, AXShowMenu)."
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
              "required": ["action"]
            }
            """;

    private final ComputerUseUiaService uiaService;
    private final ComputerUseUiSnapshotStore snapshotStore;
    private final ComputerUseConfirmationStore confirmationStore;
    /** TCC 权限检查（macOS 侧用于报告权限状态；可为 null）。 */
    private final MacOsTccChecker tcc;

    /**
     * @param uiaService         AX/UIA 服务
     * @param snapshotStore      观察存储
     * @param confirmationStore  敏感动作令牌存储（可空 ⇒ 敏感动作一律拒绝）
     */
    public ComputerUsePerformActionTool(ComputerUseUiaService uiaService,
                                        ComputerUseUiSnapshotStore snapshotStore,
                                        ComputerUseConfirmationStore confirmationStore) {
        this(uiaService, snapshotStore, confirmationStore, null);
    }

    /**
     * @param uiaService         AX/UIA 服务
     * @param snapshotStore      观察存储
     * @param confirmationStore  敏感动作令牌存储（可空 ⇒ 敏感动作一律拒绝）
     * @param tcc                TCC 权限检查（可空 ⇒ 权限报 unavailable）
     */
    public ComputerUsePerformActionTool(ComputerUseUiaService uiaService,
                                        ComputerUseUiSnapshotStore snapshotStore,
                                        ComputerUseConfirmationStore confirmationStore,
                                        MacOsTccChecker tcc) {
        this.uiaService = uiaService;
        this.snapshotStore = snapshotStore;
        this.confirmationStore = confirmationStore;
        this.tcc = tcc;
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
                    "[computer-use] perform_action inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 会改应用状态 ⇒ 非只读、不可并发。 */
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
                new PermissionDecisionReason.Other("computer-use perform_action is gated by the "
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
        String action = input.path("action").isTextual() ? input.path("action").asText() : "";
        if (action.isEmpty()) {
            return ToolResult.error(call.id(), "\"action\" must be a string.");
        }
        String observationId = input.path("observationId").isTextual()
                ? input.path("observationId").asText() : null;
        int index = input.path("index").asInt(-1);
        String handle = input.path("targetHandle").isTextual()
                ? input.path("targetHandle").asText() : null;
        boolean allowRebind = input.path("allowRebind").asBoolean(false);
        boolean sensitive = input.path("sensitive").asBoolean(false);
        String confirmationToken = input.path("confirmationToken").isTextual()
                ? input.path("confirmationToken").asText() : null;
        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 敏感动作的一次性确认（与 click_element / type_into_element 同一套）
        ComputerUseSensitiveGate.Gate gate = ComputerUseSensitiveGate.check(
                confirmationStore, snapshotStore, sessionId, sensitive, confirmationToken,
                observationId, index, handle, "perform_action", action);
        if (!gate.allowed()) {
            return ToolResult.error(call.id(), gate.rejection());
        }

        try {
            ComputerUseElementResolver.Resolution resolution =
                    ComputerUseElementResolver.resolveObserved(
                            snapshotStore, sessionId, observationId, index, handle, allowRebind,
                            "perform_action");
            if (resolution.failed()) {
                return ToolResult.error(call.id(), resolution.errorCode() + ": " + resolution.error());
            }
            ComputerUseUiSnapshotStore.ObservedElement element = resolution.element();
            if (element == null || element.locator().isEmpty()) {
                return ToolResult.error(call.id(),
                        ComputerUseUiSnapshotStore.ERR_ELEMENT_UNAVAILABLE
                                + ": the target has no locator to act on");
            }
            // 前端语义：动作名必须被元素声明过（dsh main.swift:1225）
            if (!element.actions().contains(action)) {
                return ToolResult.error(call.id(),
                        ComputerUseUiSnapshotStore.ERR_ELEMENT_UNAVAILABLE
                                + ": the element did not advertise the requested Accessibility action \""
                                + action + "\" (available: "
                                + (element.actions().isEmpty() ? "none" : String.join(",", element.actions()))
                                + ")");
            }

            long hwnd = 0L;
            if (uiaService.requiresBoundWindow()) {
                return ToolResult.error(call.id(),
                        "perform_action requires a bound window on this platform.");
            }
            // 动作前的观察：提供【目标应用 pid】（后台应用必须按它定位，不能用前台应用）
            ComputerUseUiSnapshotStore.Observation before = snapshotStore != null
                    ? snapshotStore.require(sessionId, observationId) : null;
            long targetPid = before == null ? 0L : before.pid();
            // dsh expectedStateHash：动作前校验树没变（locator 是树序派生的）
            if (!uiaService.performAction(hwnd, element.locator(), action,
                    resolution.stateHash(), targetPid)) {
                return ToolResult.error(call.id(),
                        "COMPUTER_ACTION_BLOCKED: the Accessibility action \"" + action
                                + "\" was rejected by the application");
            }
            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: session={} action={} element=#{}",
                        TOOL_NAME, sessionId, action, element.index());
            }
            // ⭐ macOS 侧返回结构化动作结果（dsh actionResultSchema）：
            //   动作后的新观察 + effect（生效没有）+ resolution（目标变了没有）
            ComputerUseActionResult result = ComputerUseActionExecutor.buildResult(
                    uiaService, snapshotStore, sessionId,
                    ComputerUseActionResult.ACTION_PERFORM_ACTION,
                    ComputerUseActionResult.CHANNEL_ACCESSIBILITY,
                    resolution.targetResolution(), before, MacOsUiaService.DEFAULT_MAX_DEPTH, tcc);
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
            extra.put("performedAction", action);
            return ToolResult.success(call.id(), ComputerUseActionResult.render(result, permissions, extra));
        } catch (ComputerUseStaleObservationException e) {
            // dsh COMPUTER_STALE_OBSERVATION：树变了 ⇒ 让模型重新 observe，
            // 而不是当成「动作被拒」去重复尝试同一个 locator
            log.warn("[computer-use] {} 拒绝执行：观察已陈旧。{}", TOOL_NAME, e.getMessage());
            return ToolResult.error(call.id(),
                    ComputerUseUiSnapshotStore.ERR_STALE_OBSERVATION + ": " + e.getMessage());
        } catch (Exception e) {
            log.error("[computer-use] {} 失败: session={} action={} err={}",
                    TOOL_NAME, sessionId, action, e.getMessage(), e);
            return ToolResult.error(call.id(), "执行辅助功能动作失败: " + e.getMessage());
        }
    }
}
