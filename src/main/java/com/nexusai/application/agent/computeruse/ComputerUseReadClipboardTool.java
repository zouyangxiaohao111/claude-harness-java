package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * {@code mcp__computer-use__read_clipboard} · 读剪贴板工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:872-881}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:873-880}）</h2>
 * <pre>
 * name: 'read_clipboard',
 * description: 'Read the current clipboard contents as text. Requires the `clipboardRead` grant.',
 * inputSchema: { type: 'object', properties: {}, required: [] },
 * </pre>
 *
 * <h2>授权位（CCB {@code toolCalls.ts:3553-3557}）</h2>
 * <p>CCB 的 {@code handleReadClipboard}：
 * <pre>
 * if (!overrides.grantFlags.clipboardRead) {
 *   return errorResult('Clipboard read is not granted. Request `clipboardRead` via request_access.', 'grant_flag_required')
 * }
 * </pre>
 * ⇒ <b>{@code clipboardRead} 授权位是硬门</b>，未授权 ⇒ 直接报错、<b>不读剪贴板</b>。
 * <br>⇒ <b>本批首次接上这个授权位</b>（此前 {@code clipboardRead} 无人消费）。
 *
 * <h2>无前台门控（CCB 同款）</h2>
 * <p>CCB 的 {@code handleReadClipboard} <b>不走</b> {@code runInputActionGates}
 * （读剪贴板本身不改系统状态）⇒ 本仓<b>不做</b>前台白名单/档位门控，与
 * {@link ComputerUseInputGate} 的适用范围（只保护<b>输入</b>动作）一致。
 *
 * <h2>⚠️ 已知差异：CCB 的 {@code clipboardGuard} 保护<b>本仓未实现</b></h2>
 * <p>CCB 的 {@code handleReadClipboard} 里另有一条独立的 {@code if (subGates.clipboardGuard)}
 * 分支（{@code toolCalls.ts:3563-3574}）：前台应用是 tier-"click" ⇒
 * <b>先暂存并清空用户剪贴板</b>（{@code syncClipboardStash}）⇒ 随后读到的
 * 是<b>空剪贴板</b>（模型看不到 click 档应用里用户的剪贴板内容）。
 * <br>⚠️ 这条分支在 CCB-Windows 上<b>可达</b>（它<b>不</b>在 {@code runInputActionGates} 里
 * ⇒ 不受 {@code toolCalls.ts:475-477} 的提前 return 影响）。
 * <br>⇒ 本仓<b>未实现</b>（需要会话级「剪贴板暂存器」；半实现会吞掉用户剪贴板内容）
 * ⇒ 影响面：本仓读到的永远是<b>真实</b>剪贴板内容（CCB 在 click 档前台时返回空）。
 * 授权位判定本身<b>完全一致</b>。
 *
 * <h2>返回形状</h2>
 * <p>CCB 返回 JSON：{@code okJson({ text })}（{@code toolCalls.ts:3577}）⇒ 本仓返回同形状
 * 的 {@link ObjectNode}（{@code {"text": "..."}}）。
 */
public final class ComputerUseReadClipboardTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseReadClipboardTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = "mcp__computer-use__read_clipboard";

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:874-875}。 */
    private static final String DESCRIPTION =
            "Read the current clipboard contents as text. Requires the `clipboardRead` grant.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:876-879}（无参数）。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    /** 未授权时的错误文案 · 逐字对齐 CCB {@code toolCalls.ts:3555}。 */
    static final String NOT_GRANTED_MESSAGE =
            "Clipboard read is not granted. Request `clipboardRead` via request_access.";

    private final ComputerUseClipboard clipboard;
    private final ComputerUseGrantStore grantStore;

    /**
     * @param clipboard  剪贴板实现
     * @param grantStore 会话授权位读写（null ⇒ 授权位按全 false 处理 ⇒ 一律拒绝）
     */
    public ComputerUseReadClipboardTool(ComputerUseClipboard clipboard, ComputerUseGrantStore grantStore) {
        if (clipboard == null) {
            throw new IllegalArgumentException("ComputerUseReadClipboardTool 需要剪贴板实现");
        }
        this.clipboard = clipboard;
        this.grantStore = grantStore;
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
                    "[computer-use] read_clipboard inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 纯读（不改系统状态）⇒ 只读、并发安全。 */
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
                new PermissionDecisionReason.Other("computer-use read_clipboard is read-only"),
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
     * 读取剪贴板文本。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 授权位硬门 · CCB toolCalls.ts:3553-3557
        boolean granted = grantStore != null
                && grantStore.read(sessionId).flags().clipboardRead();
        if (!granted) {
            return ToolResult.error(call.id(), NOT_GRANTED_MESSAGE);
        }

        String text;
        try {
            text = clipboard.readText();
        } catch (Exception e) {
            log.error("[computer-use] {} 读取失败: session={} err={}", TOOL_NAME, sessionId, e.getMessage(), e);
            return ToolResult.error(call.id(), "读取剪贴板失败: " + e.getMessage());
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("text", text == null ? "" : text);

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} 长度={}", TOOL_NAME, sessionId,
                    text == null ? 0 : text.length());
        }
        // CCB toolCalls.ts:3577 返回形状：okJson({ text })
        return ToolResult.success(call.id(), root);
    }
}
