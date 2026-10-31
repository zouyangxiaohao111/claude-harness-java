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
 * {@code mcp__computer-use__type} · 文本输入工具（对齐 CCB 2.8.4 computer-use MCP 的
 * {@code type}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code packages/@ant/computer-use-mcp/src/tools.ts:311-321}）</h2>
 * <pre>
 * name: 'type',
 * description: `Type text into whatever currently has keyboard focus. ${FRONTMOST_GATE_DESC} Newlines are supported. For keyboard shortcuts use \`key\` instead.`,
 * inputSchema: {
 *   type: 'object',
 *   properties: { text: { type: 'string', description: 'Text to type.' } },
 *   required: ['text'],
 * }
 * </pre>
 *
 * <h2>注入路径（本仓相对 CCB 的<b>缺陷修正</b>）</h2>
 * <p>CCB 的 Windows 文本注入走 PowerShell 内联
 * {@code [System.Windows.Forms.SendKeys]::SendWait('...')}
 * （{@code packages/@ant/computer-use-input/src/backends/win32.ts:284-289}），
 * 而 {@code SendKeys} 把 {@code + ^ % ~ ( ) { } [ ]} 当<b>特殊键标记</b>
 * ⇒ 文本里的 {@code +} 会被当成 Shift、{@code %} 会被当成 Alt、{@code (} 会被当成 Shift+9。
 *
 * <p>⇒ <b>本仓改用 JNA {@code SendInput} + {@code KEYEVENTF_UNICODE} 逐字符注入</b>
 * （{@link Win32Keyboard#typeChar(int)}）：字符码元直接塞进 {@code wScan}，
 * 由系统当"这个字符"投递 ⇒ <b>任何字符都只是字符，无特殊键语义</b>。
 *
 * <p>⛔ <b>剪贴板快路径本仓不做</b>：CCB 在 MCP 层确有"多行 + {@code clipboardWrite} 授权 +
 * {@code clipboardPasteMultiline} 子开关 ⇒ 走剪贴板"的路由
 * （{@code toolCalls.ts:2524-2534}），但其 Windows 执行器<b>忽略</b> {@code viaClipboard}
 * （{@code src/utils/computerUse/executorCrossPlatform.ts:315-317}
 * → {@code async type(text, _opts) { await platform.input.typeText(text) }}）
 * ⇒ <b>Windows 侧实际不会走剪贴板</b>。
 * <br>⇒ 本仓同样<b>不实现</b>剪贴板快路径（也就不会有"读回校验 / 100ms 等待 / 剪贴板恢复"
 * 这三件事 —— 它们属于剪贴板路径的一部分，整条路径都不做）。
 *
 * <h2>换行 / 制表符必须走按键</h2>
 * <p>CCB 原文注释（{@code toolCalls.ts:2537-2546}）：
 * <blockquote>
 * \n, \r, \t MUST route through executor.key(), not type(). Two reasons:
 * 1. enigo.text("\n") on macOS posts a stale CGEvent with virtualKey=0 …
 * 2. Unicode text-insertion of '\n' is not a Return key press. URL bars and terminals ignore it;
 * the model's intent (submit/execute) is lost.
 * </blockquote>
 * ⇒ 本仓同款：{@code \n} / {@code \r} / {@code \r\n} ⇒ Return 键；{@code \t} ⇒ Tab 键。
 *
 * <h2>字形簇 + 8ms 节奏</h2>
 * <p>CCB {@code toolCalls.ts:2553-2565}：每个字形簇<b>之前</b> sleep 8ms
 * （{@code INTER_GRAPHEME_SLEEP_MS = 8}，125Hz USB 轮询节奏；注释明确 "sleep BEFORE
 * each keystroke, not after"）。本仓用 {@link ComputerUseSleeper} 承载这段等待
 * ⇒ 单测可零耗时断言调用序列。
 */
public final class ComputerUseTypeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseTypeTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__type";

    /**
     * 工具描述 · 逐字对齐 CCB {@code tools.ts:313}。
     *
     * <p>CCB 原文：
     * “Type text into whatever currently has keyboard focus. ${FRONTMOST_GATE_DESC} Newlines
     * are supported. For keyboard shortcuts use {@code `key`} instead.”
     *
     * <p>⚠️ Phase 0 的 {@code left_click} 把 {@code FRONTMOST_GATE_DESC} 摘掉了
     * （当时还没有白名单 ⇒ 留着等于对模型说谎）；Phase 1a 已有白名单 + 档位门控
     * ⇒ 本工具<b>逐字带回</b>该句（行为由 {@link ComputerUseInputGate} 兑现）。
     */
    private static final String DESCRIPTION =
            "Type text into whatever currently has keyboard focus. "
                    + "The frontmost application must be in the session allowlist at the time of this call, "
                    + "or this tool returns an error and does nothing. "
                    + "Newlines are supported. For keyboard shortcuts use `key` instead.";

    /**
     * 输入 schema · 逐字对齐 CCB {@code tools.ts:314-320}。
     */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "text": {
                  "type": "string",
                  "description": "Text to type."
                }
              },
              "required": ["text"]
            }
            """;

    /** 字形簇之间的间隔 · CCB {@code toolCalls.ts:715} {@code INTER_GRAPHEME_SLEEP_MS = 8}。 */
    static final long INTER_GRAPHEME_SLEEP_MS = 8L;

    private final ComputerUseKeyboard keyboard;
    private final ComputerUseSleeper sleeper;
    private final ComputerUseGrantStore grantStore;
    private final ComputerUseForegroundAppResolver foregroundAppResolver;

    /**
     * @param keyboard               键盘注入实现
     * @param sleeper                休眠器（可注入假时钟）
     * @param grantStore             会话授权位读写（null ⇒ 不做门控，仅 PoC/单测路径）
     * @param foregroundAppResolver  前台应用解析（null ⇒ 门控不生效）
     */
    public ComputerUseTypeTool(ComputerUseKeyboard keyboard,
                               ComputerUseSleeper sleeper,
                               ComputerUseGrantStore grantStore,
                               ComputerUseForegroundAppResolver foregroundAppResolver) {
        if (keyboard == null) {
            throw new IllegalArgumentException("ComputerUseTypeTool 需要键盘实现");
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
            throw new IllegalStateException("[computer-use] type inputSchema 解析失败: " + e.getMessage(), e);
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
     * {@code src/utils/computerUse/setup.ts:25-32}（{@code mcp__computer-use__*} 进
     * {@code allowedTools}）⇒ 审批由 {@code request_access} 一次授权整会话。
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
     * 执行文本输入。
     *
     * <p>流程：取 {@code text} → 档位门控（keyboard）→ 切字形簇 → 逐簇（先 8ms）注入。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        JsonNode textNode = input.path("text");
        if (!textNode.isTextual()) {
            // CCB toolCalls.ts:2512-2513（requireString 的错误文案逐字）
            return ToolResult.error(call.id(), "\"text\" must be a string.");
        }
        String text = textNode.asText();
        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 档位门控（keyboard）· CCB runInputActionGates（toolCalls.ts:520-560）
        String rejection = ComputerUseInputGate.check(
                sessionId, grantStore, foregroundAppResolver, ComputerUseActionKind.KEYBOARD);
        if (rejection != null) {
            return ToolResult.error(call.id(), rejection);
        }

        List<String> graphemes = ComputerUseGraphemes.segment(text);
        try {
            for (String grapheme : graphemes) {
                // CCB toolCalls.ts:2558 —— sleep BEFORE each keystroke, not after
                sleeper.sleep(INTER_GRAPHEME_SLEEP_MS);
                injectGrapheme(grapheme);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[computer-use] {} 被中断: session={}", TOOL_NAME, sessionId);
            return ToolResult.error(call.id(), "文本输入被中断。");
        } catch (Exception e) {
            log.error("[computer-use] {} 注入失败: session={} err={}", TOOL_NAME, sessionId, e.getMessage(), e);
            return ToolResult.error(call.id(), "文本输入失败: " + e.getMessage());
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} 字形簇数={} 字符数={}",
                    TOOL_NAME, sessionId, graphemes.size(), text.length());
        }
        // CCB toolCalls.ts:2566 成功文案逐字对齐："Typed N grapheme(s)."
        return ToolResult.success(call.id(), "Typed " + graphemes.size() + " grapheme(s).");
    }

    /**
     * 注入一个字形簇。
     *
     * <p>换行 / 制表符走按键（{@link ComputerUseKeyEvent#named}）；其余按码点逐个
     * {@link ComputerUseKeyboard#typeChar(int)} 注入（一个 emoji 簇的多个码点会按序注入，
     * 由目标应用自行合成）。
     *
     * @param grapheme 字形簇（非空）
     * @throws Exception 注入失败
     */
    private void injectGrapheme(String grapheme) throws Exception {
        if ("\n".equals(grapheme) || "\r".equals(grapheme) || "\r\n".equals(grapheme)) {
            pressAndRelease("return");
            return;
        }
        if ("\t".equals(grapheme)) {
            pressAndRelease("tab");
            return;
        }
        int i = 0;
        while (i < grapheme.length()) {
            int codePoint = grapheme.codePointAt(i);
            keyboard.typeChar(codePoint);
            i += Character.charCount(codePoint);
        }
    }

    /**
     * 按下并释放一个具名键。
     *
     * @param keyName 键名（如 {@code "return"} / {@code "tab"}）
     * @throws Exception 注入失败
     */
    private void pressAndRelease(String keyName) throws Exception {
        keyboard.sendKeyEvent(ComputerUseKeyEvent.named(keyName, true));
        keyboard.sendKeyEvent(ComputerUseKeyEvent.named(keyName, false));
    }
}
