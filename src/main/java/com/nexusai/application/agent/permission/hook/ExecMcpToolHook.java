package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.mcp.McpToolPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置驱动 {@code mcp_tool} hook 执行器 · 对齐 claude.exe v2.1.325
 * {@code McpToolHookSchema}（type='mcp_tool'，exe off≈309,299,683）。
 *
 * <p><b>语义</b>：调用已配置 MCP server 上的工具，把返回内容按 hook JSON 输出解释。
 * {@code input} 的字符串值支持从 hook 输入 JSON 插值（如 {@code ${tool_input.file_path}}）。
 *
 * <p><b>失败策略</b>：mcp_tool 子 schema <b>没有</b> {@code onFailure} 字段 ⇒ 调用失败 /
 * 超时 / 输出非法一律 {@code non_blocking_error}（不阻断事件守护的动作）。
 *
 * <p><b>结果形状</b>：MCP {@code tools/call} 通常返回
 * {@code {content:[{type:"text",text:"<hook json>"}], isError?:boolean}}；本类取全部 text 块拼接，
 * 没有 text 块时用整个 JSON 文本。
 */
public final class ExecMcpToolHook {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CC mcp_tool timeout 缺省（秒字段缺省时的兜底）· 与 agent hook 缺省 60s 一致。 */
    public static final long DEFAULT_TIMEOUT_MS = 60_000L;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final McpToolPool pool;

    public ExecMcpToolHook(McpToolPool pool) {
        if (pool == null) {
            throw new IllegalArgumentException("McpToolPool is null");
        }
        this.pool = pool;
    }

    /**
     * 执行 mcp_tool hook。
     *
     * @param hook       配置
     * @param hookName   展示名（attachment）
     * @param event      触发事件
     * @param jsonInput  hook 输入 JSON（插值源）
     * @param defaultTimeoutMs 调用方兜底超时（hook.timeout 未配置时使用）
     * @return HookResult（成功/阻断/non_blocking）
     */
    public GenericHook.HookResult exec(McpToolHook hook, String hookName, HookEvent event,
                                       String jsonInput, long defaultTimeoutMs) {
        String display = display(hook);
        long timeoutMs = hook.timeout() != null && hook.timeout() > 0
            ? hook.timeout() * 1000L
            : (defaultTimeoutMs > 0 ? defaultTimeoutMs : DEFAULT_TIMEOUT_MS);
        JsonNode inputRoot;
        try {
            inputRoot = jsonInput == null || jsonInput.isBlank()
                ? MAPPER.createObjectNode()
                : MAPPER.readTree(jsonInput);
        } catch (Exception e) {
            return nonBlocking(event, hookName, display,
                "mcp_tool input JSON parse failed: " + e.getMessage(), hook);
        }

        Map<String, Object> args = interpolateMap(hook.input(), inputRoot);
        CompletableFuture<JsonNode> future = pool.callTool(hook.server(), hook.tool(), args);
        JsonNode result;
        try {
            result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            return nonBlocking(event, hookName, display,
                "mcp_tool timed out after " + timeoutMs + "ms: " + display, hook);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return nonBlocking(event, hookName, display,
                "mcp_tool interrupted: " + display, hook);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            return nonBlocking(event, hookName, display,
                "mcp_tool failed: " + cause.getMessage(), hook);
        }

        if (result == null) {
            return GenericHook.HookResult.proceed();
        }
        if (result.path("isError").asBoolean(false)) {
            return nonBlocking(event, hookName, display,
                "mcp_tool returned isError=true", hook);
        }
        String text = extractText(result);
        if (text == null || text.isBlank()) {
            return GenericHook.HookResult.proceed();
        }

        HookOutputParser.ParseResult parsed = HookOutputParser.parseHookOutput(text);
        if (parsed.validationError() != null) {
            return nonBlocking(event, hookName, display,
                "JSON validation failed: " + parsed.validationError(), hook);
        }
        if (!(parsed.json() instanceof HookJSONOutput.SyncHookOutput sync)) {
            // 纯文本 / async：mcp_tool 只解释 sync JSON；其余视为无干预成功（对齐 command
            // 纯文本 exit0 = hook_success，不阻断）。
            return GenericHook.HookResult.proceed();
        }
        try {
            HookOutputParser.ParsedHookJSONOutput out = HookOutputParser.processHookJSONOutput(
                sync, display, hookName, event != null ? event.type().ccName() : null,
                event != null ? event.toolUseId() : null,
                event != null ? event.type().ccName() : null,
                text, "", 0, null);
            if (out != null && out.result() != null) {
                return withHook(out.result(), hook);
            }
        } catch (Exception e) {
            return nonBlocking(event, hookName, display,
                "Failed to run: " + e.getMessage(), hook);
        }
        return GenericHook.HookResult.proceed();
    }

    /** mcp_tool 展示名 · CC getHookDisplayText：{@code server/tool}。 */
    static String display(McpToolHook hook) {
        return hook.server() + "/" + hook.tool();
    }

    /** 复制 HookResult 27 字段并设置 hook=McpToolHook（parsed 结果 hook 字段为空）。 */
    private static GenericHook.HookResult withHook(GenericHook.HookResult r, McpToolHook hook) {
        return new GenericHook.HookResult(
            r.preventContinuation(), r.blockingError(), r.systemMessages(), r.additionalContexts(),
            r.message(), r.updatedInput(), r.updatedMCPToolOutput(), r.retry(),
            r.hookPermissionDecisionReason(), r.outcome(), r.stopReason(), r.permissionBehavior(),
            r.permissionRequestResult(), hook, r.initialUserMessage(), r.watchPaths(),
            r.elicitationResponse(), r.elicitationResultResponse(), r.impossible(),
            r.terminalSequence(), r.updatedToolOutput(), r.classifierContext(), r.sessionTitle(),
            r.suppressOriginalPrompt(), r.reloadSkills(), r.displayContent(), r.permissionDisplay());
    }

    /** mcp_tool 失败（无 onFailure 字段）→ non_blocking_error attachment。 */
    private static GenericHook.HookResult nonBlocking(HookEvent event, String hookName,
                                                      String display, String message,
                                                      McpToolHook hook) {
        com.nexusai.application.agent.attachment.AttachmentMessageDto att =
            com.nexusai.application.agent.attachment.AttachmentMessageDto.hookNonBlockingError(
                hookName, event != null ? event.toolUseId() : null,
                event != null ? event.type().ccName() : null,
                message, "", 1, display, 0L);
        return new GenericHook.HookResult(false, null, null, null, att,
            null, null, null, null, GenericHook.HookOutcome.NON_BLOCKING_ERROR,
            null, null, null, hook, null, null, null, null);
    }

    // ════════════════════════════════════════════════════════════════════════
    // input 插值 / 结果文本提取（package-private 供测试）
    // ════════════════════════════════════════════════════════════════════════

    static Map<String, Object> interpolateMap(Map<String, Object> input, JsonNode root) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            out.put(e.getKey(), interpolateValue(e.getValue(), root));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object interpolateValue(Object value, JsonNode root) {
        if (value instanceof String s) {
            return interpolateString(s, root);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), interpolateValue(e.getValue(), root));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(interpolateValue(item, root));
            }
            return out;
        }
        return value;
    }

    /** {@code ${a.b.c}} 按点路径从 hook 输入 JSON 取值；缺值 → 空串（不保留原串）。 */
    static String interpolateString(String s, JsonNode root) {
        if (s == null || s.indexOf("${") < 0) {
            return s;
        }
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            JsonNode node = resolvePath(root, path);
            String replacement = node == null || node.isNull() ? ""
                : (node.isTextual() ? node.asText() : node.toString());
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static JsonNode resolvePath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || cur.isNull()) {
                return null;
            }
            if (cur.isArray()) {
                try {
                    cur = cur.get(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                cur = cur.get(part);
            }
        }
        return cur;
    }

    /** MCP tools/call 结果 → hook JSON 文本（content text 块拼接优先）。 */
    static String extractText(JsonNode result) {
        if (result == null) {
            return null;
        }
        JsonNode content = result.get("content");
        if (content != null && content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText(null))) {
                    JsonNode t = block.get("text");
                    if (t != null && t.isTextual()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(t.asText());
                    }
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        return result.isTextual() ? result.asText() : result.toString();
    }
}
