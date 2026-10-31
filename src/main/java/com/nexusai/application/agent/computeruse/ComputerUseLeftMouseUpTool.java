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
 * {@code mcp__computer-use__left_mouse_up} · 释放左键（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:955-965}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:956-964}）</h2>
 * <pre>
 * name: 'left_mouse_up',
 * description: `Release the left mouse button at the current cursor position. ${FRONTMOST_GATE_DESC} Pairs with left_mouse_down. Safe to call even if the button is not currently held.`,
 * inputSchema: { type: 'object', properties: {}, required: [] },
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:3805-3854} 的 {@code handleLeftMouseUp}）</h2>
 * <ul>
 *   <li><b>关键安全语义</b>（CCB {@code toolCalls.ts:3807-3827} 的 {@code releaseFirst}）：
 *       <b>门控拒绝时也必须先释放按键</b> —— 否则 OS 的按钮停在按下态，后续的移动会变成
 *       拖拽（对只读窗口就是<b>误拖</b>）。
 *       <br>⇒ 本仓逐字照此：门控拒绝 ⇒ <b>先释放</b> ⇒ 再返回错误。</li>
 *   <li>档位门控：{@code mouse}（CCB {@code toolCalls.ts:3829}）；</li>
 *   <li>命中测试门（hit-test）：CCB {@code toolCalls.ts:3840-3848}
 *       （{@code mouseMoved ? 'mouse_full' : 'mouse'}）。
 *       ⛔ <b>本仓不做</b>：CCB 的 {@code appUnderPoint} 在 Windows <b>恒返回 null</b>
 *       （{@code src/utils/computerUse/executorCrossPlatform.ts:524-529}）⇒ CCB 自己在这条
 *       路径上也是死路径。</li>
 *   <li><b>幂等</b>：未按住时调用<b>不报错</b>（CCB {@code toolCalls.ts:3801-3803} 的
 *       “Safe to call even if the button is not currently held”）。</li>
 *   <li>成功文案：CCB {@code toolCalls.ts:3853} {@code 'Mouse button released.'}（逐字）。</li>
 * </ul>
 */
public final class ComputerUseLeftMouseUpTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseLeftMouseUpTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "left_mouse_up");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:957-959}。 */
    private static final String DESCRIPTION =
            "Release the left mouse button at the current cursor position. "
                    + "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing. "
                    + "Pairs with left_mouse_down. Safe to call even if the button is not currently held.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:960-963}（无参数）。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    private final ComputerUseCursor cursor;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;
    private final ComputerUseMouseState mouseState;

    /**
     * @param cursor                光标原语（左键按下 / 抬起）
     * @param grantStore            会话授权位读写（null ⇒ 不做门控，单测路径）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     * @param mouseState            左键按住状态（跨调用；null ⇒ 不维护状态）
     */
    public ComputerUseLeftMouseUpTool(ComputerUseCursor cursor,
                                      ComputerUseGrantStore grantStore,
                                      ComputerUseForegroundAppResolver foregroundAppResolver,
                                      ComputerUseMouseState mouseState) {
        if (cursor == null) {
            throw new IllegalArgumentException("ComputerUseLeftMouseUpTool 需要光标实现");
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
                    "[computer-use] left_mouse_up inputSchema 解析失败: " + e.getMessage(), e);
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
     * 执行「释放左键」。
     *
     * <p>⚠️ <b>门控拒绝也要释放</b>（CCB {@code releaseFirst} 语义）——
     * 否则按钮卡在按下态，后续移动会变成拖拽。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;

        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.MOUSE);
        if (rejection != null) {
            // CCB toolCalls.ts:3820-3827 —— 先释放，再返回门控错误
            releaseQuietly(sessionId);
            return ToolResult.error(call.id(), rejection);
        }

        try {
            cursor.leftButtonUp();
        } catch (Exception e) {
            log.error("[computer-use] {} 释放失败: session={} err={}", TOOL_NAME, sessionId, e.getMessage(), e);
            return ToolResult.error(call.id(), "左键释放失败: " + e.getMessage());
        }

        if (mouseState != null) {
            mouseState.markReleased(sessionId);
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} 左键已释放", TOOL_NAME, sessionId);
        }
        // CCB toolCalls.ts:3853 成功文案逐字对齐："Mouse button released."
        return ToolResult.success(call.id(), "Mouse button released.");
    }

    /**
     * 门控拒绝路径上的<b>强制释放</b>（失败只记日志：此刻门控错误才是主错误）。
     *
     * @param sessionId 会话 id
     */
    private void releaseQuietly(String sessionId) {
        try {
            cursor.leftButtonUp();
        } catch (Exception e) {
            log.warn("[computer-use] {} 门控拒绝后的强制释放失败: session={} err={}",
                    TOOL_NAME, sessionId, e.getMessage());
        }
        if (mouseState != null) {
            mouseState.markReleased(sessionId);
        }
    }
}
