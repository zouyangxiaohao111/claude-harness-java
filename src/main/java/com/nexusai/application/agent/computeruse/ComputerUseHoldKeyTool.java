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

import java.util.ArrayList;
import java.util.List;

/**
 * {@code mcp__computer-use__hold_key} · 按住组合键一段时间后释放（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:922-941}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:923-940}）</h2>
 * <pre>
 * name: 'hold_key',
 * description: `Press and hold a key or key combination for the specified duration, then release. ${FRONTMOST_GATE_DESC} System-level combos require the \`systemKeyCombos\` grant.`,
 * inputSchema: {
 *   type: 'object',
 *   properties: {
 *     text: { type: 'string', description: 'Key or chord to hold, e.g. "space", "shift+down".' },
 *     duration: { type: 'number', description: 'Duration in seconds (0–100).' },
 *   },
 *   required: ['text', 'duration'],
 * }
 * </pre>
 *
 * <h2>CCB 行为对照（{@code toolCalls.ts:3712-3758} 的 {@code handleHoldKey}）</h2>
 * <ol>
 *   <li>取 {@code text}（{@code requireString}）与 {@code duration}（必须是有限数、
 *       {@code 0 ≤ d ≤ 100}；错误文案逐字对齐 CCB {@code toolCalls.ts:3722-3733}）；</li>
 *   <li><b>黑名单先于门控</b>（CCB {@code toolCalls.ts:3737-3745}）：命中系统级快捷键且无
 *       {@code systemKeyCombos} 授权 ⇒ 报错（与 {@code key} 工具<b>同一顺序</b>，
 *       见 {@code toolCalls.ts:2596-2607} 的注释：黑名单错误优先于前台门控错误）；</li>
 *   <li>档位门控：{@code keyboard}（CCB {@code toolCalls.ts:3747-3753}）⇒ 需 full 档；</li>
 *   <li>按住：解析 chord ⇒ <b>逐个按下</b> ⇒ 等待 duration ⇒ <b>逆序释放</b>
 *       （CCB {@code executorCrossPlatform.ts:305-313} 的 {@code holdKey}：
 *       {@code for (const k of keyNames) key(k,'press'); sleep(durationMs);
 *       for (const k of [...keyNames].reverse()) key(k,'release')}）。</li>
 * </ol>
 *
 * <h2>异常路径下不卡键（任务硬要求）</h2>
 * <p>CCB 的 macOS 执行器用 {@code releasePressed}（{@code executor.ts:180-196}）在异常时
 * 逆序释放已按下的键。本仓照此：<b>已按下的键一定被逆序释放</b>（try/finally），
 * 绝不把用户的 Shift / Ctrl 卡在按下态。
 */
public final class ComputerUseHoldKeyTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseHoldKeyTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "hold_key");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:925-926}。 */
    private static final String DESCRIPTION =
            "Press and hold a key or key combination for the specified duration, then release. "
                    + "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing. "
                    + "System-level combos require the `systemKeyCombos` grant.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:927-939}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "text": {
                  "type": "string",
                  "description": "Key or chord to hold, e.g. \\"space\\", \\"shift+down\\"."
                },
                "duration": {
                  "type": "number",
                  "description": "Duration in seconds (0–100)."
                }
              },
              "required": ["text", "duration"]
            }
            """;

    /** 时长上限（秒）· CCB {@code toolCalls.ts:3728-3733}。 */
    static final double MAX_DURATION_SECONDS = 100d;

    private final ComputerUseKeyboard keyboard;
    private final ComputerUseSleeper sleeper;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;

    /**
     * @param keyboard              键盘注入实现
     * @param sleeper               休眠器（可注入假时钟）
     * @param grantStore            会话授权位读写（null ⇒ 授权位按全 false 处理）
     * @param foregroundAppResolver 前台应用解析（null ⇒ 门控不生效）
     */
    public ComputerUseHoldKeyTool(ComputerUseKeyboard keyboard,
                                  ComputerUseSleeper sleeper,
                                  ComputerUseGrantStore grantStore,
                                  ComputerUseForegroundAppResolver foregroundAppResolver) {
        if (keyboard == null) {
            throw new IllegalArgumentException("ComputerUseHoldKeyTool 需要键盘实现");
        }
        this.keyboard = keyboard;
        this.sleeper = sleeper == null ? ComputerUseSleeper.REAL : sleeper;
        this.grantStore = grantStore;
        this.foregroundAppResolver = foregroundAppResolver;
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
            throw new IllegalStateException("[computer-use] hold_key inputSchema 解析失败: " + e.getMessage(), e);
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
     * 执行「按住组合键 → 等待 → 释放」。
     *
     * <p>流程：解析 text/duration → 黑名单 → 档位门控 → 逐个按下 → 等待 → 逆序释放。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        JsonNode textNode = input.path("text");
        if (!textNode.isTextual()) {
            // CCB toolCalls.ts:3718-3719（requireString 的错误文案逐字）
            return ToolResult.error(call.id(), "\"text\" must be a string.");
        }
        String chord = textNode.asText();

        // duration 校验 · CCB toolCalls.ts:3721-3733（错误文案逐字）
        JsonNode durationNode = input.path("duration");
        if (!durationNode.isNumber() || !Double.isFinite(durationNode.asDouble())) {
            return ToolResult.error(call.id(), "duration must be a number");
        }
        double duration = durationNode.asDouble();
        if (duration < 0) {
            return ToolResult.error(call.id(), "duration must be non-negative");
        }
        if (duration > MAX_DURATION_SECONDS) {
            return ToolResult.error(call.id(), "duration is too long. Duration is in seconds.");
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 黑名单判定【先于门控】· CCB toolCalls.ts:3737-3745（与 key 工具同款顺序）
        if (ComputerUseKeyBlocklist.isSystemKeyCombo(chord)) {
            boolean granted = grantStore != null
                    && grantStore.read(sessionId).flags().systemKeyCombos();
            if (!granted) {
                return ToolResult.error(call.id(), ComputerUseKeyBlocklist.blockedMessage(chord));
            }
        }

        // 档位门控 · CCB toolCalls.ts:3747-3753（keyboard ⇒ 需 full 档）
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.KEYBOARD);
        if (rejection != null) {
            return ToolResult.error(call.id(), rejection);
        }

        // 解析 chord · CCB 的 parseKeyChord（toolCalls.ts:748-752）只切分 '+'、
        // <b>不区分修饰键 / 主键</b> ⇒ hold_key 按住的是<b>每一个</b>分段
        // （例："shift+down" ⇒ 同时按住 Shift 与 ↓；"shift" ⇒ 只按住 Shift）。
        List<ComputerUseKeyEvent> downs = new ArrayList<>();
        List<ComputerUseKeyEvent> ups = new ArrayList<>();
        for (String part : ComputerUseKeyCombo.splitParts(chord)) {
            int vk;
            try {
                vk = ComputerUseKeyCombo.resolveVirtualKey(part);
            } catch (IllegalArgumentException e) {
                return ToolResult.error(call.id(), e.getMessage());
            }
            downs.add(new ComputerUseKeyEvent(part, vk, true));
            ups.add(new ComputerUseKeyEvent(part, vk, false));
        }
        if (downs.isEmpty()) {
            return ToolResult.error(call.id(), "\"text\" must be a string.");
        }
        // 释放顺序 = 按下顺序的逆序（CCB executorCrossPlatform.ts:310-312：
        // for (const k of [...keyNames].reverse()) key(k, 'release')）
        List<ComputerUseKeyEvent> reversedUps = new ArrayList<>();
        for (int i = ups.size() - 1; i >= 0; i--) {
            reversedUps.add(ups.get(i));
        }
        ups = reversedUps;

        List<ComputerUseKeyEvent> pressed = new ArrayList<>();
        try {
            for (ComputerUseKeyEvent down : downs) {
                keyboard.sendKeyEvent(down);
                pressed.add(down);
            }
            sleeper.sleep(Math.round(duration * 1000d));
            for (ComputerUseKeyEvent up : ups) {
                keyboard.sendKeyEvent(up);
            }
            pressed.clear();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releasePressed(pressed);
            log.warn("[computer-use] {} 被中断: session={}", TOOL_NAME, sessionId);
            return ToolResult.error(call.id(), "按键保持被中断。");
        } catch (Exception e) {
            releasePressed(pressed);
            log.error("[computer-use] {} 失败: session={} chord={} err={}",
                    TOOL_NAME, sessionId, chord, e.getMessage(), e);
            return ToolResult.error(call.id(), "按键保持失败: " + e.getMessage());
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} chord={} 时长={}s · 按下={} · 释放={}",
                    TOOL_NAME, sessionId, chord, duration, downs.size(), ups.size());
        }
        // CCB toolCalls.ts:3757 成功文案逐字对齐："Key held."
        return ToolResult.success(call.id(), "Key held.");
    }

    /**
     * 异常路径下逆序释放已按下的键（CCB {@code executor.ts:180-196} 的 {@code releasePressed}
     * 语义）—— 绝不让用户的修饰键卡在按下态。
     *
     * @param pressed 已按下、尚未释放的键（按下顺序）
     */
    private void releasePressed(List<ComputerUseKeyEvent> pressed) {
        for (int i = pressed.size() - 1; i >= 0; i--) {
            ComputerUseKeyEvent down = pressed.get(i);
            try {
                keyboard.sendKeyEvent(new ComputerUseKeyEvent(down.name(), down.virtualKey(), false));
            } catch (Exception e) {
                // 释放失败只记日志：此时原始异常更有价值（不要掩盖它）
                log.warn("[computer-use] {} 异常路径释放键失败: {} · {}", TOOL_NAME, down.name(), e.getMessage());
            }
        }
    }
}
