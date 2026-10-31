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
 * {@code mcp__computer-use__left_mouse_down} · 按下左键并<b>保持按住</b>（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:943-953}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:944-952}）</h2>
 * <pre>
 * name: 'left_mouse_down',
 * description: `Press the left mouse button at the current cursor position and leave it held. ${FRONTMOST_GATE_DESC} Use mouse_move first to position the cursor. Call left_mouse_up to release. Errors if the button is already held.`,
 * inputSchema: { type: 'object', properties: {}, required: [] },
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:3764-3799} 的 {@code handleLeftMouseDown}）</h2>
 * <ul>
 *   <li><b>已按住 ⇒ 报错</b>（CCB {@code toolCalls.ts:3769-3773}）：
 *       {@code 'mouse button already held, call left_mouse_up first'}（{@code state_conflict}）。
 *       <br>⇒ 本仓用 {@link ComputerUseMouseState}（按 sessionId 分桶）承载这个跨调用状态。</li>
 *   <li>档位门控：{@code mouse}（CCB {@code toolCalls.ts:3776}）⇒ 需 click 或 full 档；</li>
 *   <li>命中测试门（hit-test）：CCB {@code toolCalls.ts:3785-3793}。
 *       ⛔ <b>本仓不做</b>：CCB 的 {@code appUnderPoint} 在 Windows <b>恒返回 null</b>
 *       （{@code src/utils/computerUse/executorCrossPlatform.ts:524-529}）
 *       ⇒ CCB 自己在这条路径上也是<b>死路径</b>（与既有 {@code left_click} 的处理一致）。</li>
 *   <li>按下并<b>不释放</b>：置 {@code mouseButtonHeld = true}、{@code mouseMoved = false}
 *       （CCB {@code toolCalls.ts:3795-3797}）。</li>
 *   <li>成功文案：CCB {@code toolCalls.ts:3798} {@code 'Mouse button pressed.'}（逐字）。</li>
 * </ul>
 *
 * <h2>坐标</h2>
 * <p>本工具<b>不带坐标</b>（CCB 同款：{@code "at the current cursor position"}）
 * ⇒ 模型需先调 {@code mouse_move} 定位。
 */
public final class ComputerUseLeftMouseDownTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseLeftMouseDownTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__left_mouse_down";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:946-947}。 */
    private static final String DESCRIPTION =
            "Press the left mouse button at the current cursor position and leave it held. "
                    + "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing. "
                    + "Use mouse_move first to position the cursor. Call left_mouse_up to release. "
                    + "Errors if the button is already held.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:948-951}（无参数）。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    /** 已按住时的错误文案 · 逐字对齐 CCB {@code toolCalls.ts:3770-3771}。 */
    static final String ALREADY_HELD_MESSAGE =
            "mouse button already held, call left_mouse_up first";

    private final ComputerUseCursor cursor;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;
    private final ComputerUseMouseState mouseState;

    /**
     * @param cursor                光标原语（左键按下 / 抬起）
     * @param grantStore            会话授权位读写（null ⇒ 不做门控，单测路径）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     * @param mouseState            左键按住状态（跨调用；null ⇒ 不做「已按住」判定）
     */
    public ComputerUseLeftMouseDownTool(ComputerUseCursor cursor,
                                        ComputerUseGrantStore grantStore,
                                        ComputerUseForegroundAppResolver foregroundAppResolver,
                                        ComputerUseMouseState mouseState) {
        if (cursor == null) {
            throw new IllegalArgumentException("ComputerUseLeftMouseDownTool 需要光标实现");
        }
        this.cursor = cursor;
        this.grantStore = grantStore;
        this.foregroundAppResolver = foregroundAppResolver;
        this.mouseState = mouseState;
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
                    "[computer-use] left_mouse_down inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 输入动作会改系统状态 ⇒ 非只读、不可并发。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false;
    }

    /**
     * 输入动作 · <b>权限自决 Allow（免弹窗）</b> · 对齐 CCB
     * {@code src/utils/computerUse/setup.ts:25-32}。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use input action is gated by the "
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

    /**
     * 执行「按下左键并保持」。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 已按住 ⇒ 报错 · CCB toolCalls.ts:3769-3773
        if (mouseState != null && mouseState.isHeld(sessionId)) {
            return ToolResult.error(call.id(), ALREADY_HELD_MESSAGE);
        }

        // 档位门控 · CCB toolCalls.ts:3776（'mouse'）
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.MOUSE);
        if (rejection != null) {
            return ToolResult.error(call.id(), rejection);
        }

        try {
            cursor.leftButtonDown();
        } catch (Exception e) {
            log.error("[computer-use] {} 按下失败: session={} err={}", TOOL_NAME, sessionId, e.getMessage(), e);
            return ToolResult.error(call.id(), "左键按下失败: " + e.getMessage());
        }

        if (mouseState != null) {
            mouseState.markHeld(sessionId);
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} 左键已按下并保持", TOOL_NAME, sessionId);
        }
        // CCB toolCalls.ts:3798 成功文案逐字对齐："Mouse button pressed."
        return ToolResult.success(call.id(), "Mouse button pressed.");
    }
}
