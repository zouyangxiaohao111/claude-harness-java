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
import java.util.Locale;

/**
 * {@code mcp__computer-use__switch_display} · 切换截图目标显示器工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:838-859}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:839-858}）</h2>
 * <pre>
 * name: 'switch_display',
 * description: 'Switch which monitor subsequent screenshots capture. Use this when the application you need is on a different monitor than the one shown. The screenshot tool tells you which monitor it captured and lists other attached monitors by name — pass one of those names here. After switching, call screenshot to see the new monitor. Pass "auto" to return to automatic monitor selection.',
 * inputSchema: {
 *   type: 'object',
 *   properties: {
 *     display: { type: 'string', description: 'Monitor name from the screenshot note (e.g. "Built-in Retina Display", "LG UltraFine"), or "auto" to re-enable automatic selection.' },
 *   },
 *   required: ['display'],
 * }
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:3477-3537} 的 {@code handleSwitchDisplay}）</h2>
 * <ol>
 *   <li>取 {@code display}（{@code requireString}）；</li>
 *   <li>{@code "auto"}（大小写不敏感）⇒ 取消固定 ⇒ 提示
 *       （CCB {@code toolCalls.ts:3492-3497}：{@code 'Returned to automatic monitor selection. Call screenshot to continue.'}）；</li>
 *   <li>否则按<b>显示器名</b>匹配（CCB {@code toolCalls.ts:3519-3523}：
 *       {@code labels.get(d.displayId)?.toLowerCase() === display.toLowerCase()}）；</li>
 *   <li><b>只有一块屏 ⇒ 无从切换</b> ⇒ 报错（CCB {@code toolCalls.ts:3512-3517}：
 *       {@code 'Only one monitor is connected. There is nothing to switch to.'}）；</li>
 *   <li>找不到 ⇒ 报错（CCB {@code toolCalls.ts:3524-3532}：
 *       {@code 'No monitor named "${display}" is connected. Available monitors: ...'}）；</li>
 *   <li>找到 ⇒ 固定到该显示器（CCB {@code toolCalls.ts:3533} 的 {@code onDisplayPinned}）
 *       ⇒ 提示（CCB {@code toolCalls.ts:3534-3536}：
 *       {@code 'Switched to monitor "${label}". Call screenshot to see it.'}）。</li>
 * </ol>
 *
 * <h2>⚠️ Windows 侧：CCB 的多显示器<b>实际只有一块屏</b>（如实标注）</h2>
 * <p>CCB-Windows 的显示器枚举（{@code src/utils/computerUse/platforms/win32.ts:452-479}）
 * <b>确实</b>会列出多块屏（PowerShell {@code System.Windows.Forms.Screen::AllScreens}），
 * 但 CCB 的 <b>截图路径</b>（{@code src/utils/computerUse/win32/bridge.py:75-90} 的
 * {@code screenshot_full(display_id)}）走的是 {@code mss} 的 {@code sct.monitors[display_id + 1]}
 * ⇒ <b>能</b>按 id 抓到对应屏。
 * <br>⇒ 但 CCB 的 {@code getSize}（{@code platforms/win32.ts:481-488}）在 id 找不到时
 * <b>恒回落 {@code all[0]}</b>（主屏）⇒ 语义是「选不中就退回主屏」。
 * <br>⇒ 本仓<b>照做</b>：找不到匹配 ⇒ 报错并列出可用显示器（CCB 同款错误文案）；
 * 找到 ⇒ 记录选择。
 *
 * <h2>⚠️ 显示器名是 {@code "display N"}（CCB-Windows 的真实形态）</h2>
 * <p>CCB 的 {@code uniqueDisplayLabels}（{@code toolCalls.ts:2015-2032}）用
 * {@code d.label ?? `display ${d.displayId}`}；而 CCB-Windows 的 {@code listAll}
 * <b>不产出 label</b>（{@code platforms/win32.ts:452-479}）⇒
 * <b>Windows 上 CCB 的显示器名就是 {@code "display 0"} / {@code "display 1"}</b>
 * （macOS 才有 "Built-in Retina Display" 这类友好名）。
 * <br>⇒ 本仓<b>照此</b>（{@link ComputerUseDisplay#label()} = {@code "display N"}），
 * 避免模型拿到一个 Windows 上根本不存在的名字。
 *
 * <h2>无门控</h2>
 * <p>切显示器不改系统状态、不产生输入 ⇒ <b>不走</b> {@link ComputerUseInputGate}
 * （与 CCB 一致：{@code handleSwitchDisplay} 没有 {@code runInputActionGates} 调用）。
 */
public final class ComputerUseSwitchDisplayTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseSwitchDisplayTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__switch_display";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:840-846}。 */
    private static final String DESCRIPTION =
            "Switch which monitor subsequent screenshots capture. "
                    + "Use this when the application you need is on a different monitor than the one shown. "
                    + "The screenshot tool tells you which monitor it captured and lists other attached "
                    + "monitors by name — pass one of those names here. "
                    + "After switching, call screenshot to see the new monitor. "
                    + "Pass \"auto\" to return to automatic monitor selection.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:847-857}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "display": {
                  "type": "string",
                  "description": "Monitor name from the screenshot note (e.g. \\"Built-in Retina Display\\", \\"LG UltraFine\\"), or \\"auto\\" to re-enable automatic selection."
                }
              },
              "required": ["display"]
            }
            """;

    /** {@code "auto"} 的固定串（CCB {@code toolCalls.ts:3492}）。 */
    static final String AUTO = "auto";

    private final ComputerUseDisplayProvider displayProvider;
    private final ComputerUseDisplayStore displayStore;

    /**
     * @param displayProvider 显示器枚举实现
     * @param displayStore   会话级显示器选择存储
     */
    public ComputerUseSwitchDisplayTool(ComputerUseDisplayProvider displayProvider,
                                        ComputerUseDisplayStore displayStore) {
        if (displayProvider == null || displayStore == null) {
            throw new IllegalArgumentException("ComputerUseSwitchDisplayTool 依赖不能为空");
        }
        this.displayProvider = displayProvider;
        this.displayStore = displayStore;
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
                    "[computer-use] switch_display inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 切显示器不改系统状态 ⇒ 只读、并发安全。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /**
     * 只读工具 · <b>权限自决 Allow（免弹窗）</b>。
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        return new PermissionResult.Allow(
                input,
                new PermissionDecisionReason.Other("computer-use switch_display has no side effects"),
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
     * 执行显示器切换。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode displayNode = call.input().path("display");
        if (!displayNode.isTextual()) {
            // CCB toolCalls.ts:3482-3483（requireString 的错误文案逐字）
            return ToolResult.error(call.id(), "\"display\" must be a string.");
        }
        String display = displayNode.asText().trim();
        if (display.isEmpty()) {
            return ToolResult.error(call.id(), "\"display\" must be a string.");
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;

        // "auto" ⇒ 回到自动选择 · CCB toolCalls.ts:3492-3497
        if (AUTO.equals(display.toLowerCase(Locale.ROOT))) {
            displayStore.clear(sessionId);
            if (log.isInfoEnabled()) {
                log.info("[computer-use] {} 完成: session={} 回到自动选择", TOOL_NAME, sessionId);
            }
            // CCB toolCalls.ts:3494-3496（成功文案逐字）
            return ToolResult.success(call.id(),
                    "Returned to automatic monitor selection. Call screenshot to continue.");
        }

        List<ComputerUseDisplay> displays = displayProvider.listDisplays();
        if (displays.isEmpty()) {
            // 枚举失败 ⇒ 无可用显示器（CCB toolCalls.ts:3505-3509 的同款失败语义）
            return ToolResult.error(call.id(), "Failed to enumerate displays.");
        }
        if (displays.size() < 2) {
            // 只有一块屏 ⇒ 没有「切换」可言（CCB toolCalls.ts:3512-3517 逐字文案）
            return ToolResult.error(call.id(),
                    "Only one monitor is connected. There is nothing to switch to.");
        }

        // 按名字匹配 · CCB toolCalls.ts:3518-3522（大小写不敏感）
        ComputerUseDisplay target = null;
        for (ComputerUseDisplay candidate : displays) {
            if (candidate.label().toLowerCase(Locale.ROOT).equals(display.toLowerCase(Locale.ROOT))) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            // CCB toolCalls.ts:3523-3530（错误文案逐字，含可用显示器清单）
            StringBuilder available = new StringBuilder();
            for (ComputerUseDisplay candidate : displays) {
                if (available.length() > 0) {
                    available.append(", ");
                }
                available.append('"').append(candidate.label()).append('"');
            }
            return ToolResult.error(call.id(),
                    "No monitor named \"" + display + "\" is connected. Available monitors: " + available + ".");
        }

        displayStore.put(sessionId, target);

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} 切到 {}", TOOL_NAME, sessionId, target);
        }
        // CCB toolCalls.ts:3534-3536（成功文案逐字）
        return ToolResult.success(call.id(),
                "Switched to monitor \"" + target.label() + "\". Call screenshot to see it.");
    }
}
