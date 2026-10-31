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
 * {@code mcp__computer-use__write_clipboard} · 写剪贴板工具（对齐 CCB 2.8.4
 * {@code packages/@ant/computer-use-mcp/src/tools.ts:883-894}）。
 *
 * <h2>工具面（逐字对齐 CCB {@code tools.ts:884-893}）</h2>
 * <pre>
 * name: 'write_clipboard',
 * description: 'Write text to the clipboard. Requires the `clipboardWrite` grant.',
 * inputSchema: {
 *   type: 'object',
 *   properties: { text: { type: 'string' } },
 *   required: ['text'],
 * }
 * </pre>
 *
 * <h2>授权位（CCB {@code toolCalls.ts:3586-3590}）</h2>
 * <p>CCB 的 {@code handleWriteClipboard}：
 * <pre>
 * if (!overrides.grantFlags.clipboardWrite) {
 *   return errorResult('Clipboard write is not granted. Request `clipboardWrite` via request_access.', 'grant_flag_required')
 * }
 * </pre>
 * ⇒ <b>{@code clipboardWrite} 授权位是硬门</b>，未授权 ⇒ 直接报错、<b>不写剪贴板</b>。
 * <br>⇒ <b>本批首次接上这个授权位</b>（此前 {@code clipboardWrite} 无人消费）。
 *
 * <h2>⚠️ 无前台门控（CCB 同款，如实标注）</h2>
 * <p>CCB 的 {@code handleWriteClipboard} <b>不走</b> {@code runInputActionGates}
 * （{@code toolCalls.ts:3621-3623} 的注释明确：“write_clipboard doesn't route through
 * runInputActionGates — sync here instead”）⇒ 写剪贴板<b>不</b>受前台白名单/档位门控约束。
 * <br>⇒ 本仓<b>照此</b>：只查 {@code clipboardWrite} 授权位。
 *
 * <h2>⚠️ 已知差异：CCB 的 {@code clipboardGuard} 保护<b>本仓未实现</b></h2>
 * <p>CCB 的 {@code handleWriteClipboard} 里另有一条独立的 {@code if (subGates.clipboardGuard)}
 * 分支（{@code toolCalls.ts:3593-3623}）：
 * <ul>
 *   <li>前台应用是 tier-"click" ⇒ <b>拒绝写剪贴板</b>（{@code toolCalls.ts:3610-3620}，
 *       错误文案含 "a tier-\"click\" app and currently frontmost ... write_clipboard is blocked"）；</li>
 *   <li>否则 ⇒ {@code syncClipboardStash(...)} 恢复用户被暂存的剪贴板（{@code toolCalls.ts:402-442}）。</li>
 * </ul>
 * <b>⚠️ 这条分支在 CCB-Windows 上是<b>可达</b>的</b>（它<b>不</b>在
 * {@code runInputActionGates} 里 ⇒ 不受 {@code toolCalls.ts:475-477} 的
 * 「{@code screenshotFiltering === 'none'} ⇒ 提前 return」影响 —— 那个提前 return
 * 只让<b>逐动作</b>的 clipboardGuard 不可达，<b>读写剪贴板自己的</b> clipboardGuard 仍可达）。
 * <br>⇒ 本仓<b>未实现</b>：它需要一个会话级「剪贴板暂存器」（用户剪贴板的存/取/恢复），
 * 半实现（只清不恢复）会<b>吞掉用户剪贴板内容</b> ⇒ 宁可<b>整体不做</b>（已登记为待办）。
 * <br>⇒ 影响面：本仓在 tier-"click" 应用前台时<b>允许</b>写剪贴板（CCB 会拒绝）。
 * 授权位判定本身<b>完全一致</b>。
 *
 * <h2>成功文案</h2>
 * <p>CCB {@code toolCalls.ts:3628} {@code 'Clipboard written.'}（逐字）。
 */
public final class ComputerUseWriteClipboardTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseWriteClipboardTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具全名 · CCB {@code setup.ts:17-21} 要求固定为 {@code mcp__computer-use__<tool>}。 */
    public static final String TOOL_NAME = McpStringUtils.buildMcpToolName(
            ComputerUseMcpServer.SERVER_NAME, "write_clipboard");

    /** 工具描述 · 逐字对齐 CCB {@code tools.ts:885-886}。 */
    private static final String DESCRIPTION =
            "Write text to the clipboard. Requires the `clipboardWrite` grant.";

    /** 输入 schema · 逐字对齐 CCB {@code tools.ts:887-892}。 */
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "text": { "type": "string" }
              },
              "required": ["text"]
            }
            """;

    /** 未授权时的错误文案 · 逐字对齐 CCB {@code toolCalls.ts:3588}。 */
    static final String NOT_GRANTED_MESSAGE =
            "Clipboard write is not granted. Request `clipboardWrite` via request_access.";

    private final ComputerUseClipboard clipboard;
    private final ComputerUseGrantStore grantStore;

    /**
     * @param clipboard  剪贴板实现
     * @param grantStore 会话授权位读写（null ⇒ 授权位按全 false 处理 ⇒ 一律拒绝）
     */
    public ComputerUseWriteClipboardTool(ComputerUseClipboard clipboard, ComputerUseGrantStore grantStore) {
        if (clipboard == null) {
            throw new IllegalArgumentException("ComputerUseWriteClipboardTool 需要剪贴板实现");
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
                    "[computer-use] write_clipboard inputSchema 解析失败: " + e.getMessage(), e);
        }
    }

    /** 写剪贴板会改系统状态 ⇒ 非只读、不可并发。 */
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
                new PermissionDecisionReason.Other("computer-use write_clipboard is gated by the "
                        + "clipboardWrite grant flag"),
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
     * 写入剪贴板。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        String sessionId = ctx != null ? ctx.sessionId() : null;

        // 授权位硬门 · CCB toolCalls.ts:3586-3590
        boolean granted = grantStore != null
                && grantStore.read(sessionId).flags().clipboardWrite();
        if (!granted) {
            return ToolResult.error(call.id(), NOT_GRANTED_MESSAGE);
        }

        JsonNode textNode = call.input().path("text");
        if (!textNode.isTextual()) {
            // CCB toolCalls.ts:3592-3593（requireString 的错误文案逐字）
            return ToolResult.error(call.id(), "\"text\" must be a string.");
        }
        String text = textNode.asText();

        try {
            clipboard.writeText(text);
        } catch (Exception e) {
            log.error("[computer-use] {} 写入失败: session={} 长度={} err={}",
                    TOOL_NAME, sessionId, text.length(), e.getMessage(), e);
            return ToolResult.error(call.id(), "写入剪贴板失败: " + e.getMessage());
        }

        if (log.isInfoEnabled()) {
            log.info("[computer-use] {} 完成: session={} 长度={}",
                    TOOL_NAME, sessionId, text.length());
        }
        // CCB toolCalls.ts:3628 成功文案逐字对齐："Clipboard written."
        return ToolResult.success(call.id(), "Clipboard written.");
    }
}
