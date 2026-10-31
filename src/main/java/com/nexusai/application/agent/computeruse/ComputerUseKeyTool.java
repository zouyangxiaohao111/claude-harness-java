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
 * {@code mcp__computer-use__key} · 按键 / 组合键工具（对齐 CCB 2.8.4 computer-use MCP 的
 * {@code key}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:323-341}）</h2>
 * <pre>
 * name: 'key',
 * description: `Press a key or key combination (e.g. "return", "escape", "cmd+a", "ctrl+shift+tab"). ${FRONTMOST_GATE_DESC} ` +
 *   'System-level combos (quit app, switch app, lock screen) require the `systemKeyCombos` grant — without it they return an error. All other combos work.',
 * inputSchema: {
 *   type: 'object',
 *   properties: {
 *     text:   { type: 'string', description: 'Modifiers joined with "+", e.g. "cmd+shift+a".' },
 *     repeat: { type: 'integer', minimum: 1, maximum: 100, description: 'Number of times to repeat the key press. Default is 1.' },
 *   },
 *   required: ['text'],
 * }
 * </pre>
 *
 * <h2>组合键派发顺序（逐字对齐 CCB）</h2>
 * <p>CCB {@code src/utils/computerUse/win32/windowMessage.ts:341-366} 的 {@code sendKeys}
 * 与 {@code packages/@ant/computer-use-input/src/backends/win32.ts:253-282} 的 {@code keys}
 * <b>两处同款</b>：<b>修饰键按下（按输入顺序）→ 主键 down → 主键 up → 修饰键逆序释放</b>。
 * 本仓把这段纯逻辑抽成 {@link ComputerUseKeyCombo#parse(String)}（产出有序事件序列），
 * 由 {@link ComputerUseKeyboard#sendKeyEvent(ComputerUseKeyEvent)} 逐个注入。
 *
 * <h2>系统级快捷键黑名单（描述既然这么写，行为就必须真的如此）</h2>
 * <p>工具描述明写 “System-level combos … require the {@code systemKeyCombos} grant —
 * without it they return an error.” ⇒ 本工具在<b>门控之前</b>做黑名单判定
 * （CCB {@code toolCalls.ts:2588-2600} 的顺序：黑名单先于前台门控 ——
 * “A blocked combo with an ungranted app frontmost should return the blocklist error,
 * not the frontmost error — the model's fix is to request the flag, not change focus.”）。
 * <br>黑名单本体见 {@link ComputerUseKeyBlocklist}（CCB {@code keyBlocklist.ts} 的 Windows 分支）。
 *
 * <p>⛔ 未接授权体系（{@code grantStore == null}）⇒ 授权位按 CCB 的
 * {@code DEFAULT_GRANT_FLAGS}（全 false）处理 ⇒ 黑名单组合<b>照拦</b>
 * （安全方向：alt+F4 会关窗口、ctrl+alt+del 是安全序列）。
 */
public final class ComputerUseKeyTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseKeyTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__key";

    /**
     * 工具描述 · 逐字对齐 CCB {@code tools.ts:326-327}。
     *
     * <p>CCB 原文：
     * “Press a key or key combination (e.g. "return", "escape", "cmd+a", "ctrl+shift+tab").
     * ${FRONTMOST_GATE_DESC} System-level combos (quit app, switch app, lock screen) require
     * the {@code systemKeyCombos} grant — without it they return an error. All other combos work.”
     */
    private static final String DESCRIPTION =
            "Press a key or key combination (e.g. \"return\", \"escape\", \"cmd+a\", \"ctrl+shift+tab\"). "
                    + "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing. "
                    + "System-level combos (quit app, switch app, lock screen) require the "
                    + "`systemKeyCombos` grant — without it they return an error. All other combos work.";

    /**
     * 输入 schema · 逐字对齐 CCB {@code tools.ts:328-340}。
     */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "text": {
                  "type": "string",
                  "description": "Modifiers joined with \\"+\\", e.g. \\"cmd+shift+a\\"."
                },
                "repeat": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 100,
                  "description": "Number of times to repeat the key press. Default is 1."
                }
              },
              "required": ["text"]
            }
            """;

    /** 重复按键之间的间隔 · CCB {@code executorCrossPlatform.ts:302}（{@code await sleep(8)}）。 */
    static final long REPEAT_INTERVAL_MS = 8L;

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
    public ComputerUseKeyTool(ComputerUseKeyboard keyboard,
                              ComputerUseSleeper sleeper,
                              ComputerUseGrantStore grantStore,
                              ComputerUseForegroundAppResolver foregroundAppResolver) {
        if (keyboard == null) {
            throw new IllegalArgumentException("ComputerUseKeyTool 需要键盘实现");
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
            throw new IllegalStateException("[computer-use] key inputSchema 解析失败: " + e.getMessage(), e);
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
     * 执行按键。
     *
     * <p>流程：取 {@code text} → 校验 {@code repeat} → <b>黑名单</b>（先于门控）
     * → 档位门控（keyboard）→ 派发 {@code repeat} 次。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        JsonNode textNode = input.path("text");
        if (!textNode.isTextual()) {
            // CCB toolCalls.ts:2573-2574 —— handleKey 覆写了 requireString 的文案
            return ToolResult.error(call.id(), "text is required");
        }
        String keySequence = textNode.asText();

        // repeat 校验 · CCB toolCalls.ts:2577-2590（错误文案逐字）
        //   JS: typeof repeat !== 'number' || !Number.isInteger(repeat) || repeat < 1 ⇒ 报错
        //   ⚠️ JS 没有 int/float 之分 ⇒ 1.0 是合法整数；Java 侧 Jackson 把 1.0 解析成 DoubleNode
        //      ⇒ 这里按【数值是否为整数】判定，而不是按节点类型判定。
        int repeat = 1;
        if (input.has("repeat")) {
            JsonNode repeatNode = input.path("repeat");
            if (!repeatNode.isNumber()) {
                return ToolResult.error(call.id(), "repeat must be a positive integer");
            }
            double value = repeatNode.asDouble();
            if (!Double.isFinite(value) || value != Math.rint(value) || value < 1d) {
                return ToolResult.error(call.id(), "repeat must be a positive integer");
            }
            if (value > 100d) {
                return ToolResult.error(call.id(), "repeat exceeds maximum of 100");
            }
            repeat = (int) value;
        }

        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 黑名单判定【先于门控】· CCB toolCalls.ts:2588-2600
        if (ComputerUseKeyBlocklist.isSystemKeyCombo(keySequence)) {
            boolean granted = grantStore != null
                    && grantStore.read(sessionId).flags().systemKeyCombos();
            if (!granted) {
                return ToolResult.error(call.id(), ComputerUseKeyBlocklist.blockedMessage(keySequence));
            }
        }

        // 档位门控（keyboard）· CCB runInputActionGates
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.KEYBOARD);
        if (rejection != null) {
            return ToolResult.error(call.id(), rejection);
        }

        List<ComputerUseKeyEvent> events;
        try {
            events = ComputerUseKeyCombo.parse(keySequence);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.id(), e.getMessage());
        }

        try {
            for (int i = 0; i < repeat; i++) {
                if (i > 0) {
                    // CCB executorCrossPlatform.ts:302 —— 两次重复之间 sleep 8ms（末次之后不睡）
                    sleeper.sleep(REPEAT_INTERVAL_MS);
                }
                for (ComputerUseKeyEvent event : events) {
                    keyboard.sendKeyEvent(event);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[computer-use] {} 被中断: session={}", TOOL_NAME, sessionId);
            return ToolResult.error(call.id(), "按键被中断。");
        } catch (Exception e) {
            log.error("[computer-use] {} 派发失败: session={} seq={} err={}",
                    TOOL_NAME, sessionId, keySequence, e.getMessage(), e);
            return ToolResult.error(call.id(), "按键派发失败: " + e.getMessage());
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} seq={} 事件数={} 重复={}",
                    TOOL_NAME, sessionId, keySequence, events.size(), repeat);
        }
        // CCB toolCalls.ts:2616 成功文案逐字对齐："Key pressed."
        return ToolResult.success(call.id(), "Key pressed.");
    }
}
